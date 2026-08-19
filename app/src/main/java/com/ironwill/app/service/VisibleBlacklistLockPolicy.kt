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
    return input.presenceConfirmed &&
        input.isDndLocked &&
        !input.isPrivilegeActive &&
        !input.isLockShowing &&
        !input.isDesktopReturnGrace &&
        !input.hasTemporaryAccessForTarget
}
