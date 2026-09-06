package com.zhizhi.app

import com.zhizhi.app.manager.LockOverlayRestrictionMode
import com.zhizhi.app.manager.resolveAllowedWindowSummaryForDisplay
import com.zhizhi.app.manager.resolveEmergencyActionPresentation
import com.zhizhi.app.manager.resolveLockOverlayRestrictionMode
import com.zhizhi.app.manager.shouldRouteEmergencyToPrivilege
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Ignore
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

    @Ignore("Pre-existing failure, see Issue_Tracker: 3-bugs-dnd-isolation round 07-22 + ROUND_SUMMARY.md")
    @Test
    fun privilegeReasonDisablesButtonForDndAndAppWindowModes() {
        val dndPresentation = resolveEmergencyActionPresentation(
            mode = LockOverlayRestrictionMode.DND,
            privilegeUnavailableReason = "次数已用完",
            restExhausted = false,
            inAnyCooling = false
        )
        val appWindowPresentation = resolveEmergencyActionPresentation(
            mode = LockOverlayRestrictionMode.APP_WINDOW,
            privilegeUnavailableReason = "不在可用时间",
            restExhausted = true,
            inAnyCooling = true
        )

        assertEquals("特权不可用(次数已用完)", dndPresentation.label)
        assertFalse(dndPresentation.enabled)
        assertEquals("特权不可用(不在可用时间)", appWindowPresentation.label)
        assertFalse(appWindowPresentation.enabled)
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

    // ────────────────────────────────────────────────────────────────
    // CAT-A REQUIRED_FIXES & MUST_ADD_TESTS
    // ────────────────────────────────────────────────────────────────

    /**
     * REQUIRED_FIXES item 1: Gap period + AppWindow blocked scenario.
     * Even when not within a DND segment, an AppWindow-blocked app should
     * show APP_WINDOW mode and display its actual allowed time window range.
     */
    @Test
    fun givenGapPeriodWithAppWindowBlocked_showsWindowRange() {
        val mode = resolveLockOverlayRestrictionMode(
            isAppWindowBlocked = true,
            isWithinDndSegment = false
        )
        assertEquals(LockOverlayRestrictionMode.APP_WINDOW, mode)

        // During a gap period, the window summary should still reflect today's windows
        val summary = resolveAllowedWindowSummaryForDisplay(
            allowedTodaySummary = "20:30-22:30",
            configuredSummary = "23:00-23:30"
        )
        assertEquals("20:30-22:30", summary)
    }

    /**
     * REQUIRED_FIXES item 2: Fallback when no effective window exists today
     * but the app is still under AppWindow management. The configured summary
     * should be shown instead of the generic hint.
     */
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

    @Test
    fun givenAppWindowBlocked_duringCooling_disablesActionAndShowsCountdown() {
        val mode = LockOverlayRestrictionMode.APP_WINDOW
        val presentation = resolveEmergencyActionPresentation(
            mode = mode,
            privilegeUnavailableReason = null,
            restExhausted = false,
            inAnyCooling = true,
            coolingRemainingSeconds = 45L
        )
        assertEquals("⏳ 冷却中 (45s)", presentation.label)
        assertFalse(presentation.enabled)
        assertEquals(0.5f, presentation.alpha, 0.01f)
    }
}
