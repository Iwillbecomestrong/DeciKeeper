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
        // Spec v1.0: 时间进入允许区间（勿扰结束或时间窗开启）属于真正解除规则；
        // 当双锁均已解除且正处于锁屏上方时，自动关闭锁屏放行底层应用。
        dismissBlacklistOverlay = naturallyFree && hasBlacklistOverlay
    )
}
