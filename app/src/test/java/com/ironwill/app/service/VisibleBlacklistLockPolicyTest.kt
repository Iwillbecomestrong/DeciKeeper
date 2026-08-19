package com.ironwill.app.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VisibleBlacklistLockPolicyTest {

    @Test
    fun `test A - core bug - chatgpt in foreground and douyin in small window triggers lock`() {
        val input = VisibleBlacklistLockInput(
            isDndLocked = true,
            isPrivilegeActive = false,
            isLockShowing = false,
            isDesktopReturnGrace = false,
            hasTemporaryAccessForTarget = false,
            presenceConfirmed = true
        )

        assertTrue(
            "Visible blacklisted small window must trigger lock when DND is active and no exemptions apply",
            shouldTriggerVisibleBlacklistLock(input)
        )
    }

    @Test
    fun `test B - whitelisted foreground app with no visible blacklisted window does not lock`() {
        val input = VisibleBlacklistLockInput(
            isDndLocked = true,
            isPrivilegeActive = false,
            isLockShowing = false,
            isDesktopReturnGrace = false,
            hasTemporaryAccessForTarget = false,
            presenceConfirmed = false
        )

        assertFalse(
            "Must not lock when presence is not confirmed",
            shouldTriggerVisibleBlacklistLock(input)
        )
    }

    @Test
    fun `test C - privilege period suppresses visible blacklist lock`() {
        val input = VisibleBlacklistLockInput(
            isDndLocked = true,
            isPrivilegeActive = true,
            isLockShowing = false,
            isDesktopReturnGrace = false,
            hasTemporaryAccessForTarget = false,
            presenceConfirmed = true
        )

        assertFalse(
            "Active privilege must prevent visible blacklist lock",
            shouldTriggerVisibleBlacklistLock(input)
        )
    }

    @Test
    fun `test D - inactive DND period suppresses visible blacklist lock`() {
        val input = VisibleBlacklistLockInput(
            isDndLocked = false,
            isPrivilegeActive = false,
            isLockShowing = false,
            isDesktopReturnGrace = false,
            hasTemporaryAccessForTarget = false,
            presenceConfirmed = true
        )

        assertFalse(
            "When DND is not locked, visible blacklist presence must not trigger lock",
            shouldTriggerVisibleBlacklistLock(input)
        )
    }

    @Test
    fun `test E - desktop return grace suppresses visible blacklist lock`() {
        val input = VisibleBlacklistLockInput(
            isDndLocked = true,
            isPrivilegeActive = false,
            isLockShowing = false,
            isDesktopReturnGrace = true,
            hasTemporaryAccessForTarget = false,
            presenceConfirmed = true
        )

        assertFalse(
            "Desktop return grace period must prevent visible blacklist lock to avoid relocking on home",
            shouldTriggerVisibleBlacklistLock(input)
        )
    }

    @Test
    fun `test F - temporary access for target app suppresses visible blacklist lock`() {
        val input = VisibleBlacklistLockInput(
            isDndLocked = true,
            isPrivilegeActive = false,
            isLockShowing = false,
            isDesktopReturnGrace = false,
            hasTemporaryAccessForTarget = true,
            presenceConfirmed = true
        )

        assertFalse(
            "Target app with active temporary access must not be locked by presence",
            shouldTriggerVisibleBlacklistLock(input)
        )
    }

    @Test
    fun `test G - existing lock showing suppresses new lock request`() {
        val input = VisibleBlacklistLockInput(
            isDndLocked = true,
            isPrivilegeActive = false,
            isLockShowing = true,
            isDesktopReturnGrace = false,
            hasTemporaryAccessForTarget = false,
            presenceConfirmed = true
        )

        assertFalse(
            "When lock screen is already showing, do not trigger a new lock request (maintenance handles it)",
            shouldTriggerVisibleBlacklistLock(input)
        )
    }
}
