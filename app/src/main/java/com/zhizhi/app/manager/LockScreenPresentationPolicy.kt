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
    // 兼容历史调用：特权现已作为所有锁屏的独立常驻按钮，主操作按钮分流为：时间窗->临时放行，勿扰->申请休息
    return false
}

internal fun shouldRouteEmergencyToTempAccess(
    mode: LockOverlayRestrictionMode
): Boolean {
    return mode == LockOverlayRestrictionMode.APP_WINDOW
}

internal fun shouldShowStandaloneTemporaryAccess(
    mode: LockOverlayRestrictionMode
): Boolean {
    return mode == LockOverlayRestrictionMode.DND
}

internal fun resolveTemporaryAccessPresentation(
    isTemporaryAccessExhausted: Boolean,
    inAnyCooling: Boolean,
    coolingRemainingSeconds: Long = 0L
): EmergencyActionPresentation {
    if (isTemporaryAccessExhausted) {
        return EmergencyActionPresentation(
            label = "临时解锁已用完",
            enabled = false,
            alpha = 0.5f
        )
    }
    val label = if (inAnyCooling) {
        if (coolingRemainingSeconds > 0L) {
            "⚡ 临时解锁（冷却翻倍 ${coolingRemainingSeconds}s）"
        } else {
            "⚡ 临时解锁（冷却翻倍）"
        }
    } else {
        "⚡ 临时解锁"
    }
    return EmergencyActionPresentation(
        label = label,
        enabled = true,
        alpha = 1.0f
    )
}

internal fun resolveEmergencyActionPresentation(
    mode: LockOverlayRestrictionMode,
    privilegeUnavailableReason: String?,
    restExhausted: Boolean,
    inAnyCooling: Boolean,
    coolingRemainingSeconds: Long = 0L,
    isTemporaryAccessExhausted: Boolean = false
): EmergencyActionPresentation {
    // 应用时间窗限制锁屏：主操作为【临时解锁】
    if (mode == LockOverlayRestrictionMode.APP_WINDOW) {
        return resolveTemporaryAccessPresentation(
            isTemporaryAccessExhausted = isTemporaryAccessExhausted,
            inAnyCooling = inAnyCooling,
            coolingRemainingSeconds = coolingRemainingSeconds
        )
    }


    // 勿扰时段锁机：主操作为【申请休息】（专解勿扰锁）
    if (restExhausted) {
        return EmergencyActionPresentation(label = "休息已用完", enabled = false, alpha = 0.5f)
    }

    return EmergencyActionPresentation(
        label = if (inAnyCooling) "⚠️ 冷静期申请 (翻倍扣费)" else "🌱 申请休息",
        enabled = true,
        alpha = 1.0f
    )
}

internal fun resolveWeeklyPrivilegePresentation(
    privilegeUnavailableReason: String?
): EmergencyActionPresentation {
    return if (privilegeUnavailableReason.isNullOrBlank()) {
        EmergencyActionPresentation(label = "👑 每周特权", enabled = true, alpha = 1.0f)
    } else {
        EmergencyActionPresentation(
            label = "特权不可用($privilegeUnavailableReason)",
            enabled = false,
            alpha = 0.5f
        )
    }
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
