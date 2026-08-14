package com.ironwill.app

internal data class AutomaticRestrictionReleaseDecision(
    val settleRest: Boolean,
    val settleTemporaryAccess: Boolean,
    val dismissBlacklistOverlay: Boolean
)

internal fun resolveAutomaticRestrictionRelease(
    isDndBlocked: Boolean,
    isAppWindowBlocked: Boolean,
    hasActiveRest: Boolean,
    hasActiveTemporaryAccess: Boolean,
    hasBlacklistOverlay: Boolean
): AutomaticRestrictionReleaseDecision {
    val naturallyFree = !isDndBlocked && !isAppWindowBlocked
    return AutomaticRestrictionReleaseDecision(
        settleRest = naturallyFree && hasActiveRest,
        settleTemporaryAccess = naturallyFree && hasActiveTemporaryAccess,
        dismissBlacklistOverlay = naturallyFree && hasBlacklistOverlay
    )
}
