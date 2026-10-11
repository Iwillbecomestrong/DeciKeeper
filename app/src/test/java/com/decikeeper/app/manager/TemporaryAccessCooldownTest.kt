package com.decikeeper.app.manager

import com.decikeeper.app.ui.LockWindowManager
import org.junit.Assert.*
import org.junit.Test

class TemporaryAccessCooldownTest {
    @Test
    fun cooldownBlocksEveryDurationIncludingTenSeconds() {
        for (remaining in listOf(1L, 60L, 180L)) {
            for (seconds in listOf(6, 8, 10, 11, 60, 180)) {
                assertTrue(LockWindowManager.shouldBlockTemporaryAccessByReuseCooldown(remaining, seconds, 180))
            }
        }
        assertFalse(LockWindowManager.shouldBlockTemporaryAccessByReuseCooldown(0L, 180, 180))
    }

    @Test
    fun coolingDisablesBothTemporaryAccessPresentationsButKeepsRestAndPrivilege() {
        val temp = resolveTemporaryAccessPresentation(false, true, 120L)
        assertFalse(temp.enabled)
        assertTrue(temp.label.contains("冷却中"))
        assertFalse(temp.label.contains("翻倍"))
        assertFalse(resolveEmergencyActionPresentation(
            LockOverlayRestrictionMode.APP_WINDOW, null, false, true, 120L
        ).enabled)
        assertTrue(resolveEmergencyActionPresentation(
            LockOverlayRestrictionMode.DND, null, false, true, 120L
        ).enabled)
        assertTrue(resolveWeeklyPrivilegePresentation(null).enabled)
        assertTrue(resolveTemporaryAccessPresentation(false, false).enabled)
    }
}
