package com.decikeeper.app.manager

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
        // Once a blacklist overlay is visible, a rule transition is not an unlock authority.
        // The overlay remains until REST / TEMPORARY_ACCESS / WEEKLY_PRIVILEGE / RETURN_HOME.
        dismissBlacklistOverlay = false
    )
}
