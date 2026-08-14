package com.ironwill.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutomaticRestrictionReleasePolicyTest {
    @Test
    fun releasesAllActiveStatesWhenNoRestrictionRemains() {
        val result = resolveAutomaticRestrictionRelease(
            isDndBlocked = false,
            isAppWindowBlocked = false,
            hasActiveRest = true,
            hasActiveTemporaryAccess = true,
            hasBlacklistOverlay = true
        )

        assertTrue(result.settleRest)
        assertTrue(result.settleTemporaryAccess)
        assertTrue(result.dismissBlacklistOverlay)
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
    fun onlyRequestsActionsForStatesThatExist() {
        val result = resolveAutomaticRestrictionRelease(false, false, false, true, false)

        assertFalse(result.settleRest)
        assertTrue(result.settleTemporaryAccess)
        assertFalse(result.dismissBlacklistOverlay)
    }
}
