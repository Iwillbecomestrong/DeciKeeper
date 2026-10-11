package com.decikeeper.app.ui

import com.decikeeper.app.manager.LockOverlayRestrictionMode
import com.decikeeper.app.manager.resolveAllowedWindowSummaryForDisplay
import com.decikeeper.app.manager.resolveEmergencyActionPresentation
import com.decikeeper.app.manager.resolveLockOverlayRestrictionMode
import com.decikeeper.app.manager.resolveWeeklyPrivilegePresentation
import com.decikeeper.app.manager.shouldRouteEmergencyToTempAccess
import com.decikeeper.app.manager.shouldShowStandaloneTemporaryAccess
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LockScreenPresentationPolicyTest {

    @Test
    fun appWindowWinsOverDndWhenBothSignalsExist() {
        val mode = resolveLockOverlayRestrictionMode(
            isAppWindowBlocked = true,
            isWithinDndSegment = true
        )

        assertEquals(LockOverlayRestrictionMode.APP_WINDOW, mode)
        val presentation = resolveEmergencyActionPresentation(
            mode = mode,
            privilegeUnavailableReason = null,
            restExhausted = false,
            inAnyCooling = false
        )
        assertEquals("⚡ 临时解锁", presentation.label)
        assertTrue(presentation.enabled)
    }

    @Test
    fun dndLockShowsRestEntry() {
        val mode = resolveLockOverlayRestrictionMode(
            isAppWindowBlocked = false,
            isWithinDndSegment = true
        )

        assertEquals(LockOverlayRestrictionMode.DND, mode)
        val presentation = resolveEmergencyActionPresentation(
            mode = mode,
            privilegeUnavailableReason = null,
            restExhausted = false,
            inAnyCooling = false
        )
        assertEquals("🌱 申请休息", presentation.label)
        assertTrue(presentation.enabled)
    }

    @Test
    fun ordinaryLockKeepsRestEntryBehavior() {
        val mode = resolveLockOverlayRestrictionMode(
            isAppWindowBlocked = false,
            isWithinDndSegment = false
        )

        val presentation = resolveEmergencyActionPresentation(
            mode = mode,
            privilegeUnavailableReason = null,
            restExhausted = false,
            inAnyCooling = false
        )

        assertEquals(LockOverlayRestrictionMode.OTHER, mode)
        assertEquals("🌱 申请休息", presentation.label)
        assertTrue(presentation.enabled)
    }

    @Test
    fun windowSummaryFallsBackToConfiguredRangesBeforeGenericHint() {
        assertEquals(
            "20:30-22:30, 23:00-23:30",
            resolveAllowedWindowSummaryForDisplay("", "20:30-22:30, 23:00-23:30")
        )
        assertEquals(
            "20:30-22:30",
            resolveAllowedWindowSummaryForDisplay("20:30-22:30", "23:00-23:30")
        )
        assertEquals(
            "今日已配置可用时间窗，请在应用时间窗设置中查看具体范围",
            resolveAllowedWindowSummaryForDisplay("", "")
        )
    }

    @Test
    fun givenGapPeriodWithAppWindowBlocked_showsWindowRange() {
        val mode = resolveLockOverlayRestrictionMode(
            isAppWindowBlocked = true,
            isWithinDndSegment = false
        )
        assertEquals(LockOverlayRestrictionMode.APP_WINDOW, mode)

        val summary = resolveAllowedWindowSummaryForDisplay(
            allowedTodaySummary = "20:30-22:30",
            configuredSummary = "23:00-23:30"
        )
        assertEquals("20:30-22:30", summary)
    }

    @Test
    fun givenAppWindowBlocked_noEffectiveWindowToday_fallsBackToConfiguredSummary() {
        val summary = resolveAllowedWindowSummaryForDisplay(
            allowedTodaySummary = "",
            configuredSummary = "20:30-22:30"
        )
        assertEquals("20:30-22:30", summary)
    }

    @Test
    fun givenAppWindowBlocked_duringGap_period_showsAppWindowMode() {
        val mode = resolveLockOverlayRestrictionMode(
            isAppWindowBlocked = true,
            isWithinDndSegment = false
        )
        assertEquals(LockOverlayRestrictionMode.APP_WINDOW, mode)
        val presentation = resolveEmergencyActionPresentation(
            mode = mode,
            privilegeUnavailableReason = null,
            restExhausted = false,
            inAnyCooling = false
        )
        assertEquals("⚡ 临时解锁", presentation.label)
        assertTrue(presentation.enabled)
    }

    // ────────────────────────────────────────────────────────────────
    // SPEC §3.2 Triple-Exit Presentation & Matrix Tests (Remediation Plan)
    // ────────────────────────────────────────────────────────────────

    @Test
    fun givenAppWindowBlocked_duringCooling_disablesTemporaryAccess() {
        val presentation = resolveEmergencyActionPresentation(
            mode = LockOverlayRestrictionMode.APP_WINDOW,
            privilegeUnavailableReason = null,
            restExhausted = false,
            inAnyCooling = true,
            coolingRemainingSeconds = 45L
        )

        assertFalse(presentation.enabled)
        assertEquals(0.5f, presentation.alpha, 0.01f)
        assertTrue(presentation.label.contains("冷却中"))
        assertFalse(presentation.label.contains("翻倍"))
        assertTrue(presentation.label.contains("45s"))
    }

    @Test
    fun givenAppWindowBlocked_whenTemporaryAccessExhausted_disablesButton() {
        val presentation = resolveEmergencyActionPresentation(
            mode = LockOverlayRestrictionMode.APP_WINDOW,
            privilegeUnavailableReason = null,
            restExhausted = false,
            inAnyCooling = false,
            isTemporaryAccessExhausted = true
        )

        assertFalse(presentation.enabled)
        assertEquals(0.5f, presentation.alpha, 0.01f)
        assertEquals("临时解锁已用完", presentation.label)
    }

    @Test
    fun appWindowRoutesPrimaryActionToTemporaryRelease() {
        assertTrue(
            shouldRouteEmergencyToTempAccess(
                LockOverlayRestrictionMode.APP_WINDOW
            )
        )
    }

    @Test
    fun dndDoesNotReplaceRestPrimaryWithTemporaryRelease() {
        assertFalse(
            shouldRouteEmergencyToTempAccess(
                LockOverlayRestrictionMode.DND
            )
        )
    }

    @Test
    fun dndDoesNotExposeStandaloneTemporaryRelease() {
        assertFalse(
            "Spec v1.0 纯勿扰锁屏彻底移除临时解锁入口，仅提供申请休息与特权",
            shouldShowStandaloneTemporaryAccess(
                LockOverlayRestrictionMode.DND
            )
        )
    }

    @Test
    fun appWindowDoesNotShowStandaloneTemporaryRelease() {
        assertFalse(
            shouldShowStandaloneTemporaryAccess(
                LockOverlayRestrictionMode.APP_WINDOW
            )
        )
    }

    @Test
    fun weeklyPrivilegeAvailableWhenNoUnavailableReason() {
        val presentation = resolveWeeklyPrivilegePresentation(null)
        assertTrue(presentation.enabled)
        assertEquals("👑 每周特权", presentation.label)
        assertEquals(1.0f, presentation.alpha, 0.01f)
    }

    @Test
    fun weeklyPrivilegeDisabledWhenUnavailableReasonExists() {
        val presentation = resolveWeeklyPrivilegePresentation("本周次数已用完")
        assertFalse(presentation.enabled)
        assertTrue(presentation.label.contains("特权不可用"))
        assertEquals(0.5f, presentation.alpha, 0.01f)
    }
}
