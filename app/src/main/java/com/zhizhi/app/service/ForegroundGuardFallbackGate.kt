package com.zhizhi.app.service

/**
 * Safety net for a blacklisted app that was already in the foreground when a DND segment starts.
 *
 * The normal foreground guard remains authoritative. This gate only activates when the selected
 * package is backed by an unclosed CACHE/HISTORY foreground event and remains the same package
 * continuously for [timeoutMs]. USAGE_STATS is intentionally excluded so background audio cannot
 * earn the fallback merely by refreshing usage timestamps.
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
    }
}

internal fun isSustainedForegroundFallbackEligible(
    evidenceSource: String,
    selectedPackage: String,
    cachedPackage: String?,
    lastForegroundEventTs: Long,
    lastBackgroundEventTs: Long
): Boolean {
    if (evidenceSource != "CACHE" && evidenceSource != "HISTORY") return false
    if (selectedPackage != cachedPackage) return false
    if (lastForegroundEventTs <= 0L) return false
    return lastBackgroundEventTs <= 0L || lastForegroundEventTs > lastBackgroundEventTs
}
