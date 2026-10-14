package com.decikeeper.app.manager

import android.content.Context
import com.decikeeper.app.data.AnalysisResult
import com.decikeeper.app.data.BlacklistStore
import com.decikeeper.app.manager.CreditManager
import com.decikeeper.app.manager.EffectiveActivationPolicyManager
import com.decikeeper.app.manager.TimeRuleEditWindowPolicyManager
import com.decikeeper.app.manager.TimeRuleManager
import java.util.Calendar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class TimeRuleEditWindowPolicyTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("decikeeper_rules", Context.MODE_PRIVATE).edit().clear().apply()
        context.getSharedPreferences("decikeeper_rules_pending", Context.MODE_PRIVATE).edit().clear().apply()
        context.getSharedPreferences("decikeeper_rules_baseline", Context.MODE_PRIVATE).edit().clear().apply()
        context.getSharedPreferences(CreditManager.PREFS_NAME, Context.MODE_PRIVATE).edit().clear().apply()
        CreditManager.markAdaptationEnded(context)
    }

    private fun makeCalendar(hour: Int, minute: Int): Calendar {
        return Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
    }

    @Test
    fun defaultPolicyIsAllDay() {
        val policy = TimeRuleEditWindowPolicyManager.getPolicy(context)
        assertEquals(TimeRuleEditWindowPolicyManager.Policy.ALL_DAY, policy)
        assertEquals("全天可修改", TimeRuleEditWindowPolicyManager.getPolicyTitle(policy))
    }

    @Test
    fun allDayPolicyNeverLocks() {
        TimeRuleEditWindowPolicyManager.setPolicy(context, TimeRuleEditWindowPolicyManager.Policy.ALL_DAY)

        assertFalse(TimeRuleManager.isTimeRuleEditLocked(context, makeCalendar(0, 0)))
        assertFalse(TimeRuleManager.isTimeRuleEditLocked(context, makeCalendar(3, 30)))
        assertFalse(TimeRuleManager.isTimeRuleEditLocked(context, makeCalendar(7, 59)))
        assertFalse(TimeRuleManager.isTimeRuleEditLocked(context, makeCalendar(8, 0)))
        assertFalse(TimeRuleManager.isTimeRuleEditLocked(context, makeCalendar(14, 0)))
        assertFalse(TimeRuleManager.isTimeRuleEditLocked(context, makeCalendar(23, 59)))
    }

    @Test
    fun effectiveTimeToMidnightPolicyLocksBeforeRefreshTime() {
        TimeRuleEditWindowPolicyManager.setPolicy(
            context,
            TimeRuleEditWindowPolicyManager.Policy.EFFECTIVE_TIME_TO_MIDNIGHT
        )
        // Default refresh time is 06:45
        assertEquals("06:45", TimeRuleManager.getRefreshTime(context))

        // Before 06:45 -> Locked
        assertTrue(TimeRuleManager.isTimeRuleEditLocked(context, makeCalendar(0, 0)))
        assertTrue(TimeRuleManager.isTimeRuleEditLocked(context, makeCalendar(5, 0)))
        assertTrue(TimeRuleManager.isTimeRuleEditLocked(context, makeCalendar(6, 44)))

        // From 06:45 onwards -> Unlocked
        assertFalse(TimeRuleManager.isTimeRuleEditLocked(context, makeCalendar(6, 45)))
        assertFalse(TimeRuleManager.isTimeRuleEditLocked(context, makeCalendar(12, 30)))
        assertFalse(TimeRuleManager.isTimeRuleEditLocked(context, makeCalendar(23, 59)))
    }

    @Test
    fun customRefreshTimeAdjustsLockWindow() {
        TimeRuleEditWindowPolicyManager.setPolicy(
            context,
            TimeRuleEditWindowPolicyManager.Policy.EFFECTIVE_TIME_TO_MIDNIGHT
        )
        TimeRuleManager.saveRefreshTime(context, "06:30")
        assertEquals("06:30", TimeRuleManager.getRefreshTime(context))

        assertTrue(TimeRuleManager.isTimeRuleEditLocked(context, makeCalendar(6, 29)))
        assertFalse(TimeRuleManager.isTimeRuleEditLocked(context, makeCalendar(6, 30)))
        assertFalse(TimeRuleManager.isTimeRuleEditLocked(context, makeCalendar(7, 0)))
    }

    @Test
    fun switchingFromAllDayToEffectiveToMidnightIsImmediateBenign() {
        // Baseline: ALL_DAY
        val basPrefs = context.getSharedPreferences("decikeeper_rules_baseline", Context.MODE_PRIVATE)
        basPrefs.edit().putString(TimeRuleEditWindowPolicyManager.KEY_POLICY, "all_day").apply()

        val result = TimeRuleManager.analyzeAndSaveGlobalParams(
            context = context,
            newRest = 60, newGap = 60, newLockDuration = 15,
            newBorrow = 50, newNotifyFar = 20, newNotifyNear = 5,
            newWeeklyCount = 1, newWeeklyDuration = 480,
            newRefreshTime = "08:00",
            newEditWindowPolicy = TimeRuleEditWindowPolicyManager.Policy.EFFECTIVE_TIME_TO_MIDNIGHT.key
        )

        // Stricter policy should apply immediately
        assertEquals(
            TimeRuleEditWindowPolicyManager.Policy.EFFECTIVE_TIME_TO_MIDNIGHT,
            TimeRuleEditWindowPolicyManager.getPolicy(context)
        )
        assertNull(TimeRuleEditWindowPolicyManager.getPendingPolicy(context))
        assertTrue(result is AnalysisResult.AllImmediate)
    }

    @Test
    fun switchingFromEffectiveToMidnightToAllDayIsAdverseDelayed() {
        // Active & Baseline: EFFECTIVE_TIME_TO_MIDNIGHT
        TimeRuleEditWindowPolicyManager.setPolicy(
            context,
            TimeRuleEditWindowPolicyManager.Policy.EFFECTIVE_TIME_TO_MIDNIGHT
        )
        val basPrefs = context.getSharedPreferences("decikeeper_rules_baseline", Context.MODE_PRIVATE)
        basPrefs.edit().putString(TimeRuleEditWindowPolicyManager.KEY_POLICY, "effective_time_to_midnight").apply()

        val result = TimeRuleManager.analyzeAndSaveGlobalParams(
            context = context,
            newRest = 60, newGap = 60, newLockDuration = 15,
            newBorrow = 50, newNotifyFar = 20, newNotifyNear = 5,
            newWeeklyCount = 1, newWeeklyDuration = 480,
            newRefreshTime = "08:00",
            newEditWindowPolicy = TimeRuleEditWindowPolicyManager.Policy.ALL_DAY.key
        )

        // Active should still be strict (EFFECTIVE_TIME_TO_MIDNIGHT)
        assertEquals(
            TimeRuleEditWindowPolicyManager.Policy.EFFECTIVE_TIME_TO_MIDNIGHT,
            TimeRuleEditWindowPolicyManager.getPolicy(context)
        )
        // Pending should be ALL_DAY
        val pendingPolicy = TimeRuleEditWindowPolicyManager.getPendingPolicy(context)
        assertEquals(TimeRuleEditWindowPolicyManager.Policy.ALL_DAY, pendingPolicy)
        assertTrue(result is AnalysisResult.Mixed || result is AnalysisResult.AllDelayed)

        // When pending is applied on refresh, active updates
        TimeRuleManager.applyPendingToActive(context, force = true)
        assertEquals(
            TimeRuleEditWindowPolicyManager.Policy.ALL_DAY,
            TimeRuleEditWindowPolicyManager.getPolicy(context)
        )
    }

    @Test
    fun switchingFromEffectiveToMidnightToAllDayDuringAdaptationPeriodIsImmediate() {
        // Reset adaptation period to active
        context.getSharedPreferences(CreditManager.PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putBoolean("has_manually_ended_adaptation", false)
            .putLong("first_setup_date", System.currentTimeMillis())
            .apply()
        assertTrue(TimeRuleManager.isInAdaptationPeriod(context))

        // Active & Baseline: EFFECTIVE_TIME_TO_MIDNIGHT
        TimeRuleEditWindowPolicyManager.setPolicy(
            context,
            TimeRuleEditWindowPolicyManager.Policy.EFFECTIVE_TIME_TO_MIDNIGHT
        )
        val basPrefs = context.getSharedPreferences("decikeeper_rules_baseline", Context.MODE_PRIVATE)
        basPrefs.edit().putString(TimeRuleEditWindowPolicyManager.KEY_POLICY, "effective_time_to_midnight").apply()

        val result = TimeRuleManager.analyzeAndSaveGlobalParams(
            context = context,
            newRest = 60, newGap = 60, newLockDuration = 15,
            newBorrow = 50, newNotifyFar = 20, newNotifyNear = 5,
            newWeeklyCount = 1, newWeeklyDuration = 480,
            newRefreshTime = "08:00",
            newEditWindowPolicy = TimeRuleEditWindowPolicyManager.Policy.ALL_DAY.key
        )

        // In adaptation period, relaxation applies immediately
        assertEquals(
            TimeRuleEditWindowPolicyManager.Policy.ALL_DAY,
            TimeRuleEditWindowPolicyManager.getPolicy(context)
        )
        assertNull(TimeRuleEditWindowPolicyManager.getPendingPolicy(context))
        assertTrue(result is AnalysisResult.AllImmediate)
    }

    @Test
    fun findRefreshTimeFromSegmentsSupportsFourToTwelveRange() {
        // Segments ending at:
        // 1) 03:30 (outside 04:00-12:00)
        // 2) 04:30 (inside 04:00-12:00)
        // 3) 11:45 (inside 04:00-12:00)
        // 4) 12:15 (outside 04:00-12:00)
        val seg1 = com.decikeeper.app.data.TimeSegment(1, 0, 3, 30, 10, "早晨前")
        val seg2 = com.decikeeper.app.data.TimeSegment(2, 0, 4, 30, 10, "清晨")
        val seg3 = com.decikeeper.app.data.TimeSegment(9, 0, 11, 45, 10, "上午")
        val seg4 = com.decikeeper.app.data.TimeSegment(10, 0, 12, 15, 10, "中午")

        // Earliest in 04:00-12:00 should be seg2 (04:30)
        val result1 = TimeRuleManager.findRefreshTimeFromSegments(listOf(seg1, seg2, seg3, seg4))
        assertEquals("04:30", result1)

        // If seg2 is disabled, earliest should be seg3 (11:45)
        val seg2Disabled = seg2.copy(isEnabled = false)
        val result2 = TimeRuleManager.findRefreshTimeFromSegments(listOf(seg1, seg2Disabled, seg3, seg4))
        assertEquals("11:45", result2)

        // If all in-range segments are disabled, use the default refresh time.
        val seg3Disabled = seg3.copy(isEnabled = false)
        val result3 = TimeRuleManager.findRefreshTimeFromSegments(listOf(seg1, seg2Disabled, seg3Disabled, seg4))
        assertEquals("06:45", result3)
    }

    @Test
    fun adaptationPeriodNeverLocksEvenUnderEffectiveTimeToMidnight() {
        context.getSharedPreferences(CreditManager.PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putBoolean("has_manually_ended_adaptation", false)
            .putLong("first_setup_date", System.currentTimeMillis())
            .apply()
        assertTrue(TimeRuleManager.isInAdaptationPeriod(context))

        TimeRuleEditWindowPolicyManager.setPolicy(
            context,
            TimeRuleEditWindowPolicyManager.Policy.EFFECTIVE_TIME_TO_MIDNIGHT
        )

        assertFalse(TimeRuleManager.isTimeRuleEditLocked(context, makeCalendar(0, 0)))
        assertFalse(TimeRuleManager.isTimeRuleEditLocked(context, makeCalendar(5, 0)))
        assertFalse(TimeRuleManager.isTimeRuleEditLocked(context, makeCalendar(7, 59)))
        assertFalse(TimeRuleManager.isTimeRuleEditLocked(context, makeCalendar(8, 0)))
        assertFalse(TimeRuleEditWindowPolicyManager.isEditLocked(context, makeCalendar(3, 0)))
    }

    @Test
    fun applyPolicyChangeInSettingsWorksWithAdaptationAndPendingRevoke() {
        // Non-adaptation
        CreditManager.markAdaptationEnded(context)
        TimeRuleEditWindowPolicyManager.setPolicy(context, TimeRuleEditWindowPolicyManager.Policy.ALL_DAY)

        // Stricter switch -> Immediate
        val res1 = TimeRuleEditWindowPolicyManager.applyPolicyChange(
            context,
            TimeRuleEditWindowPolicyManager.Policy.EFFECTIVE_TIME_TO_MIDNIGHT
        )
        assertTrue(res1 is TimeRuleEditWindowPolicyManager.PolicyChangeResult.Immediate)
        assertEquals(TimeRuleEditWindowPolicyManager.Policy.EFFECTIVE_TIME_TO_MIDNIGHT, TimeRuleEditWindowPolicyManager.getPolicy(context))
        assertNull(TimeRuleEditWindowPolicyManager.getPendingPolicy(context))

        // Looser switch -> Pending
        val res2 = TimeRuleEditWindowPolicyManager.applyPolicyChange(
            context,
            TimeRuleEditWindowPolicyManager.Policy.ALL_DAY
        )
        assertTrue(res2 is TimeRuleEditWindowPolicyManager.PolicyChangeResult.Pending)
        assertEquals(TimeRuleEditWindowPolicyManager.Policy.EFFECTIVE_TIME_TO_MIDNIGHT, TimeRuleEditWindowPolicyManager.getPolicy(context))
        assertEquals(TimeRuleEditWindowPolicyManager.Policy.ALL_DAY, TimeRuleEditWindowPolicyManager.getPendingPolicy(context))

        // Revoke pending by selecting active policy again
        val res3 = TimeRuleEditWindowPolicyManager.applyPolicyChange(
            context,
            TimeRuleEditWindowPolicyManager.Policy.EFFECTIVE_TIME_TO_MIDNIGHT
        )
        assertTrue(res3 is TimeRuleEditWindowPolicyManager.PolicyChangeResult.Immediate)
        assertNull(TimeRuleEditWindowPolicyManager.getPendingPolicy(context))

        // In adaptation period, looser switch is immediate
        context.getSharedPreferences(CreditManager.PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putBoolean("has_manually_ended_adaptation", false)
            .putLong("first_setup_date", System.currentTimeMillis())
            .apply()
        assertTrue(TimeRuleManager.isInAdaptationPeriod(context))

        val res4 = TimeRuleEditWindowPolicyManager.applyPolicyChange(
            context,
            TimeRuleEditWindowPolicyManager.Policy.ALL_DAY
        )
        assertTrue(res4 is TimeRuleEditWindowPolicyManager.PolicyChangeResult.Immediate)
        assertEquals(TimeRuleEditWindowPolicyManager.Policy.ALL_DAY, TimeRuleEditWindowPolicyManager.getPolicy(context))
        assertNull(TimeRuleEditWindowPolicyManager.getPendingPolicy(context))
    }

    @Test
    fun allNextDayPolicyDefersGlobalParamsStrictChangesAndBlacklistAdditions() {
        CreditManager.markAdaptationEnded(context)
        EffectiveActivationPolicyManager.setPolicy(context, EffectiveActivationPolicyManager.Policy.ALL_NEXT_DAY)

        // Baseline: rest=60
        context.getSharedPreferences("decikeeper_rules_baseline", Context.MODE_PRIVATE)
            .edit().putInt("cool_down_rest_seconds", 60).apply()
        context.getSharedPreferences("decikeeper_rules", Context.MODE_PRIVATE)
            .edit().putInt("cool_down_rest_seconds", 60).apply()

        // 1. Stricter param change under ALL_NEXT_DAY should be deferred to pending
        val result = TimeRuleManager.analyzeAndSaveGlobalParams(
            context = context,
            newRest = 120, // Stricter
            newGap = 60, newLockDuration = 15,
            newBorrow = 50, newNotifyFar = 20, newNotifyNear = 5,
            newWeeklyCount = 2, newWeeklyDuration = 600,
            newRefreshTime = "08:00"
        )
        // Active should still be 60
        assertEquals(60, TimeRuleManager.getCoolDownRestSeconds(context))
        // Pending should be 120
        val pendingPrefs = context.getSharedPreferences("decikeeper_rules_pending", Context.MODE_PRIVATE)
        assertEquals(120, pendingPrefs.getInt("cool_down_rest_seconds", 0))

        // 2. Blacklist addition under ALL_NEXT_DAY should be deferred to pending
        val resBlacklist = BlacklistStore.analyzeAndSaveBlacklistApps(context, setOf("com.test.distracting"))
        assertTrue(resBlacklist is AnalysisResult.Mixed)
        assertFalse(BlacklistStore.getBlacklistApps(context).contains("com.test.distracting"))
        assertTrue(BlacklistStore.getPendingBlacklistApps(context)?.contains("com.test.distracting") == true)

        // Under FAVORABLE_IMMEDIATE, stricter applies immediately
        EffectiveActivationPolicyManager.setPolicy(context, EffectiveActivationPolicyManager.Policy.FAVORABLE_IMMEDIATE)
        val resFav = BlacklistStore.analyzeAndSaveBlacklistApps(context, setOf("com.test.immediate"))
        assertTrue(BlacklistStore.getBlacklistApps(context).contains("com.test.immediate"))
    }
}
