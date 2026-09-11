package com.decikeeper.app

import com.decikeeper.app.manager.resolveAutomaticRestrictionRelease
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutomaticRestrictionReleasePolicyTest {
    @Test
    fun naturalFreedomSettlesSessionsAndDismissesVisibleBlacklistOverlay() {
        val result = resolveAutomaticRestrictionRelease(
            isDndBlocked = false,
            isAppWindowBlocked = false,
            hasActiveRest = true,
            hasActiveTemporaryAccess = true,
            hasBlacklistOverlay = true
        )

        assertTrue(result.settleRest)
        assertTrue(result.settleTemporaryAccess)
        assertTrue(
            "Spec v1.0: 时间进入允许区间属于真正解除规则，必须自动解除黑名单锁屏覆盖层放行底层应用",
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
