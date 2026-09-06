package com.zhizhi.app.service

/**
 * Safety net for a blacklisted app that was already in the foreground when a DND segment starts.
 *
 * The normal foreground guard remains authoritative. This gate only activates when the selected
 * package is backed by an unclosed CACHE/HISTORY foreground event (or USAGE_STATS when backed by an
 * unclosed active foreground session) and remains the same package continuously for [timeoutMs].
 * Plain background audio without an active session cannot earn the fallback by merely refreshing usage timestamps.
 */
internal class ForegroundGuardFallbackGate(
    private val timeoutMs: Long = 60_000L
) {
    private var pendingPackage: String? = null
    private var pendingSinceMs: Long? = null

    fun shouldForce(
        packageName: String,
        nowMs: Long,
        eligible: Boolean,
        guardPassed: Boolean
    ): Boolean {
        if (guardPassed || !eligible) {
            reset()
            return false
        }

        if (pendingPackage != packageName) {
            pendingPackage = packageName
            pendingSinceMs = nowMs
            return false
        }

        val startedAt = pendingSinceMs ?: nowMs.also { pendingSinceMs = it }
        return nowMs - startedAt >= timeoutMs
    }

    fun reset() {
        pendingPackage = null
        pendingSinceMs = null
        try {
            android.util.Log.d("ForegroundGuardFallbackGate", "Explicit reset called across sessions")
        } catch (_: Throwable) {
        }
    }
}

internal fun isSustainedForegroundFallbackEligible(
    evidenceSource: String,
    selectedPackage: String,
    cachedPackage: String?,
    lastForegroundEventTs: Long,
    lastBackgroundEventTs: Long,
    hasActiveForegroundSession: Boolean = false
): Boolean {
    // 🛡️ [Finding 2 修复] SPEC 进程隔离与会话契约：
    // 任何因前台证据陈旧（CACHE/HISTORY/USAGE_STATS）而要求 60s 保底锁定的场景，
    // 必须且只能由当前进程内真实建立且未关闭的活跃前台 Session (hasActiveForegroundSession == true) 支撑。
    // 无活跃 Session 时，严禁仅凭 12 小时历史事件或旧缓存触发保底锁定！
    if (!hasActiveForegroundSession) return false
    if (evidenceSource != "CACHE" && evidenceSource != "HISTORY" && evidenceSource != "USAGE_STATS") return false
    if (selectedPackage != cachedPackage) return false
    return true
}

