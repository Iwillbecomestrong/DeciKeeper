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
    @Volatile
    private var pendingPackage: String? = null
    @Volatile
    private var pendingSinceMs: Long? = null

    @Synchronized
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

    @Synchronized
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
    hasActiveForegroundSession: Boolean = false
): Boolean {
    // 🛡️ SPEC 进程隔离与会话契约 (Finding 2 修复)：
    // 任何因前台证据陈旧（CACHE/HISTORY/USAGE_STATS）而要求 60s 保底锁定的场景，
    // 必须且只能由当前进程内真实建立且未关闭的活跃前台 Session (hasActiveForegroundSession == true) 支撑。
    // SessionTracker 已负责处理 MOVE_TO_BG / PAUSED / STOPPED / Launcher / 锁屏 / 息屏等 package 级反证，
    // 不再使用服务级全局时间戳，防止无关应用后台事件或应用内同毫秒 Activity 切换产生误判。
    if (!hasActiveForegroundSession) return false
    if (evidenceSource != "CACHE" && evidenceSource != "HISTORY" && evidenceSource != "USAGE_STATS") return false
    if (selectedPackage != cachedPackage) return false
    return true
}

