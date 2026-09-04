package com.zhizhi.app.manager
/**
 * Boundary 1 / Bug 1 REQ-003 — application of [LockScreenRequestOutcome] as the single
 * machine-checkable result of `LockWindowManager.showLockScreen` / expiry path.
 *
 * Mirrored in `LockScreenPresentationPolicy.kt` so callers can exhaustively `when` over the
 * six original outcomes plus the new `ALREADY_HANDLED` state introduced for the expiry
 * state machine (REQ-004).
 */
internal sealed class LockScreenRequestOutcome {
    object SHOWED : LockScreenRequestOutcome()
    object DEFERRED_ACCEPTED : LockScreenRequestOutcome()
    object ALREADY_PENDING : LockScreenRequestOutcome()
    object ALREADY_LOCKED : LockScreenRequestOutcome()
    object REJECTED_RETRYABLE : LockScreenRequestOutcome()
    object REJECTED_TERMINAL : LockScreenRequestOutcome()
    /** New — issued by the expiry state machine after a successful DEFERRED_ACCEPTED or
     *  explicit cancellation; subsequent ticks for the same sessionId observe this and
     *  short-circuit without re-issuing the lock request. */
    object ALREADY_HANDLED : LockScreenRequestOutcome()
}

internal enum class LockOverlayRestrictionMode {
    APP_WINDOW,
    DND,
    OTHER
}

internal data class EmergencyActionPresentation(
    val label: String,
    val enabled: Boolean,
    val alpha: Float
)

internal fun resolveLockOverlayRestrictionMode(
    isAppWindowBlocked: Boolean,
    isWithinDndSegment: Boolean
): LockOverlayRestrictionMode {
    return when {
        isAppWindowBlocked -> LockOverlayRestrictionMode.APP_WINDOW
        isWithinDndSegment -> LockOverlayRestrictionMode.DND
        else -> LockOverlayRestrictionMode.OTHER
    }
}

internal fun shouldRouteEmergencyToPrivilege(
    mode: LockOverlayRestrictionMode
): Boolean {
    // 仅 AppWindow 锁屏路由到特权；DND 锁屏走"申请休息"路径
    return mode == LockOverlayRestrictionMode.APP_WINDOW
}

internal fun resolveEmergencyActionPresentation(
    mode: LockOverlayRestrictionMode,
    privilegeUnavailableReason: String?,
    restExhausted: Boolean,
    inAnyCooling: Boolean
): EmergencyActionPresentation {
    if (shouldRouteEmergencyToPrivilege(mode)) {
        return if (privilegeUnavailableReason.isNullOrBlank()) {
            EmergencyActionPresentation(label = "申请特权", enabled = true, alpha = 1.0f)
        } else {
            EmergencyActionPresentation(
                label = "特权不可用($privilegeUnavailableReason)",
                enabled = false,
                alpha = 0.5f
            )
        }
    }

    if (restExhausted) {
        return EmergencyActionPresentation(label = "休息已用完", enabled = false, alpha = 0.5f)
    }

    return EmergencyActionPresentation(
        label = if (inAnyCooling) "⚠️ 冷静期申请 (加权扣费)" else "申请休息",
        enabled = true,
        alpha = 1.0f
    )
}

internal fun resolveAllowedWindowSummaryForDisplay(
    allowedTodaySummary: String,
    configuredSummary: String
): String {
    val effectiveSummary = allowedTodaySummary.trim()
    if (effectiveSummary.isNotEmpty()) return effectiveSummary

    val configured = configuredSummary.trim()
    if (configured.isNotEmpty()) return configured

    return "今日已配置可用时间窗，请在应用时间窗设置中查看具体范围"
}
