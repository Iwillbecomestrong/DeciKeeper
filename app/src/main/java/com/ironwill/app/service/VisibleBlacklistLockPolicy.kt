package com.ironwill.app.service

internal data class VisibleBlacklistLockInput(
    val isDndLocked: Boolean,
    val isPrivilegeActive: Boolean,
    val isLockShowing: Boolean,
    val isDesktopReturnGrace: Boolean,
    val hasTemporaryAccessForTarget: Boolean,
    val presenceConfirmed: Boolean
)

internal fun shouldTriggerVisibleBlacklistLock(
    input: VisibleBlacklistLockInput
): Boolean {
    // Presence is specifically the small-window/PIP enforcement path. UsageStats can still
    // discover candidates, but without a live AccessibilityService we do not trust that
    // historical visibility signal enough to block. Ordinary foreground blocking is separate.
    return CoreService.isServiceActive &&
        input.presenceConfirmed &&
        input.isDndLocked &&
        !input.isPrivilegeActive &&
        !input.isLockShowing &&
        !input.isDesktopReturnGrace &&
        !input.hasTemporaryAccessForTarget
}
