package com.decikeeper.app.manager

import android.content.Context
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class DefaultFocusConfigurationTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()

    @Before
    @After
    fun clearPreferences() {
        listOf("decikeeper_rules", "decikeeper_rules_pending", CreditManager.PREFS_NAME).forEach {
            context.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit()
        }
    }

    @Test
    fun freshRuleParametersMatchTheSelectedPendingConfiguration() {
        assertEquals(90, TimeRuleManager.getCoolDownRestSeconds(context))
        assertEquals(90, TimeRuleManager.getCoolDownGapSeconds(context))
        assertEquals(50, TimeRuleManager.getBorrowPercentage(context))
        assertEquals(20, TimeRuleManager.getNotifyEntryFarMinutes(context))
        assertEquals(5, TimeRuleManager.getNotifyEntryNearMinutes(context))
        assertEquals(15L, TimeRuleManager.getStrictPenaltyDurationSeconds(context))
        assertEquals(180, TimeRuleManager.getTemporaryRestrictedAccessMaxSeconds(context))
        assertEquals(2, CreditManager.getWeeklyPrivilegeLimit(context))
        assertEquals(480, CreditManager.getWeeklyPrivilegeDurationLimit(context))
        assertEquals("06:45", TimeRuleManager.getRefreshTime(context))
        assertEquals("07:00", TimeRuleManager.getPrivilegeWindowStart(context))
        assertEquals("23:00", TimeRuleManager.getPrivilegeWindowEnd(context))
        assertTrue(TimeRuleManager.isRefreshTimeSynced(context))
        assertEquals("06:45", TimeRuleManager.findRefreshTimeFromSegments(emptyList()))
    }

    @Test
    fun oneClickSegmentsMatchPendingTimesCountsAndRepetitionWithoutPersonalLists() {
        val segments = TimeRuleManager.createDefaultTimeSegments(123L)
        assertEquals(listOf("深夜", "上午", "下午", "晚上"), segments.map { it.name })
        assertEquals(listOf(22 * 60 + 30, 8 * 60 + 30, 12 * 60 + 45, 17 * 60 + 45), segments.map { it.startHour * 60 + it.startMinute })
        assertEquals(listOf(6 * 60 + 45, 12 * 60, 17 * 60, 21 * 60 + 30), segments.map { it.endHour * 60 + it.endMinute })
        assertEquals(listOf(10, 30, 30, 30), segments.map { it.limitMins })
        segments.forEach {
            assertEquals(6, it.usableCountLimitHalfUnits)
            assertEquals(123L, it.lastModified)
            assertTrue(it.isEnabled)
            assertNull(it.listApps)
        }
        assertEquals(listOf(2, 3, 4, 5, 6), segments.last().daysOfWeek)
        segments.dropLast(1).forEach { assertEquals(listOf(1, 2, 3, 4, 5, 6, 7), it.daysOfWeek) }
        assertEquals("06:45", TimeRuleManager.findRefreshTimeFromSegments(segments))
    }

    @Test
    fun existingStoredParametersAreNotReplacedWithTheNewDefaults() {
        context.getSharedPreferences("decikeeper_rules", Context.MODE_PRIVATE).edit()
            .putInt("cool_down_rest_seconds", 120)
            .putInt("cool_down_gap_seconds", 30)
            .putInt("borrow_percentage", 60)
            .putString("privilege_window_start", "11:00")
            .putString("privilege_window_end", "24:00")
            .putBoolean("is_refresh_time_synced", false)
            .commit()
        context.getSharedPreferences(CreditManager.PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putString("config_refresh_time", "08:00")
            .putInt("weekly_privilege_count_limit_v2", 3)
            .putInt("weekly_privilege_duration_limit_v2", 600)
            .commit()
        assertEquals(120, TimeRuleManager.getCoolDownRestSeconds(context))
        assertEquals(30, TimeRuleManager.getCoolDownGapSeconds(context))
        assertEquals(60, TimeRuleManager.getBorrowPercentage(context))
        assertEquals("08:00", TimeRuleManager.getRefreshTime(context))
        assertEquals("11:00", TimeRuleManager.getPrivilegeWindowStart(context))
        assertEquals("24:00", TimeRuleManager.getPrivilegeWindowEnd(context))
        assertEquals(3, CreditManager.getWeeklyPrivilegeLimit(context))
        assertEquals(600, CreditManager.getWeeklyPrivilegeDurationLimit(context))
        assertFalse(TimeRuleManager.isRefreshTimeSynced(context))
    }

    @Test
    fun legacyStoredValuesAreMigratedWithoutApplyingNewDefaults() {
        context.getSharedPreferences("decikeeper_rules", Context.MODE_PRIVATE).edit()
            .putInt("cool_down_seconds", 45).commit()
        context.getSharedPreferences(CreditManager.PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putInt("weekly_privilege_limit", 1)
            .putInt("weekly_privilege_duration_limit", 300).commit()
        assertEquals(45, TimeRuleManager.getCoolDownRestSeconds(context))
        assertEquals(1, CreditManager.getWeeklyPrivilegeLimit(context))
        assertEquals(300, CreditManager.getWeeklyPrivilegeDurationLimit(context))
    }
}
