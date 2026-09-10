package com.decikeeper.app

import com.decikeeper.app.manager.resolveAutomaticRestrictionRelease
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutomaticRestrictionReleasePolicyTest {
    @Test
    fun naturalFreedomSettlesSessionsButKeepsAnAlreadyVisibleBlacklistOverlay() {
        val result = resolveAutomaticRestrictionRelease(
            isDndBlocked = false,
            isAppWindowBlocked = false,
            hasActiveRest = true,
            hasActiveTemporaryAccess = true,
            hasBlacklistOverlay = true
        )

        assertTrue(result.settleRest)
        assertTrue(result.settleTemporaryAccess)
        assertFalse(
            "Natural rule changes are not an unlock authority once the lock is visible",
            result.dismissBlacklistOverlay
        )
    }

    @Test
    fun dndRestrictionKeepsAllStates() {
        val result = resolveAutomaticRestrictionRelease(true, false, true, true, true)

        assertFalse(result.settleRest)
        assertFalse(result.settleTemporaryAccess)
        assertFalse(result.dismissBlacklistOverlay)
    }

    @Test
    fun appWindowRestrictionKeepsAllStates() {
        val result = resolveAutomaticRestrictionRelease(false, true, true, true, true)

        assertFalse(result.settleRest)
        assertFalse(result.settleTemporaryAccess)
        assertFalse(result.dismissBlacklistOverlay)
    }

    @Test
    fun onlyRequestsSessionSettlementForStatesThatExist() {
        val result = resolveAutomaticRestrictionRelease(false, false, false, true, false)

        assertFalse(result.settleRest)
        assertTrue(result.settleTemporaryAccess)
        assertFalse(result.dismissBlacklistOverlay)
    }
}
