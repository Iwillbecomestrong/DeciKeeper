package com.decikeeper.app

import com.decikeeper.app.manager.LockDecisionPolicy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LockDecisionPolicyTest {

    @Test
    fun `safe privilege or temporary access exempts from all locks`() {
        // Safe app (launcher/settings)
        assertFalse(
            LockDecisionPolicy.shouldBlock(
                isBlacklisted = true,
                isSafe = true,
                isPrivilege = false,
                isDndLocked = true,
                isAppWindowBlocked = true,
                segmentDecoupled = false,
                isManaged = { false },
                effectivePackageName = "com.android.settings",
                isTemporaryAccess = false
            )
        )

        // Privilege active
        assertFalse(
            LockDecisionPolicy.shouldBlock(
                isBlacklisted = true,
                isSafe = false,
                isPrivilege = true,
                isDndLocked = true,
                isAppWindowBlocked = true,
                segmentDecoupled = false,
                isManaged = { false },
                effectivePackageName = "com.tencent.mm",
                isTemporaryAccess = false
            )
        )

        // Temporary access active
        assertFalse(
            LockDecisionPolicy.shouldBlock(
                isBlacklisted = true,
                isSafe = false,
                isPrivilege = false,
                isDndLocked = true,
                isAppWindowBlocked = true,
                segmentDecoupled = false,
                isManaged = { false },
                effectivePackageName = "com.tencent.mm",
                isTemporaryAccess = true
            )
        )
    }

    @Test
    fun `blacklisted and dnd locked blocks app`() {
        assertTrue(
            LockDecisionPolicy.shouldBlock(
                isBlacklisted = true,
                isSafe = false,
                isPrivilege = false,
                isDndLocked = true,
                isAppWindowBlocked = false, // Even if window is open, DND blocks
                segmentDecoupled = false,
                isManaged = { false },
                effectivePackageName = "com.tencent.mm"
            )
        )
    }

    @Test
    fun `blacklisted dnd unlocked app window blocked blocks app`() {
        assertTrue(
            LockDecisionPolicy.shouldBlock(
                isBlacklisted = true,
                isSafe = false,
                isPrivilege = false,
                isDndLocked = false, // DND unlocked (e.g. within free time or resting)
                isAppWindowBlocked = true, // But outside allowed app window
                segmentDecoupled = false,
                isManaged = { false },
                effectivePackageName = "com.tencent.mm"
            )
        )
    }

    @Test
    fun `blacklisted dnd unlocked app window allowed passes app`() {
        assertFalse(
            LockDecisionPolicy.shouldBlock(
                isBlacklisted = true,
                isSafe = false,
                isPrivilege = false,
                isDndLocked = false,
                isAppWindowBlocked = false,
                segmentDecoupled = false,
                isManaged = { false },
                effectivePackageName = "com.tencent.mm"
            )
        )
    }

    @Test
    fun `not blacklisted segment decoupled managed app blocked by window`() {
        assertTrue(
            LockDecisionPolicy.shouldBlock(
                isBlacklisted = false,
                isSafe = false,
                isPrivilege = false,
                isDndLocked = false,
                isAppWindowBlocked = true,
                segmentDecoupled = true,
                isManaged = { true },
                effectivePackageName = "com.bilibili.app"
            )
        )
    }

    @Test
    fun `not blacklisted not decoupled does not invoke isManaged`() {
        var isManagedCalled = false
        assertFalse(
            LockDecisionPolicy.shouldBlock(
                isBlacklisted = false,
                isSafe = false,
                isPrivilege = false,
                isDndLocked = false,
                isAppWindowBlocked = true,
                segmentDecoupled = false, // not decoupled
                isManaged = {
                    isManagedCalled = true
                    true
                },
                effectivePackageName = "com.example.unmanaged"
            )
        )
        assertFalse("When segment is not decoupled, isManaged must not be called", isManagedCalled)
    }
}
