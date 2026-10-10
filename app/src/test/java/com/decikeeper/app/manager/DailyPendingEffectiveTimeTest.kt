package com.decikeeper.app.manager

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 勿扰时段 / 日参数的待生效 = 次日刷新点；只有每周特权是下周一。
 * 二者共用 pending 里的 effective_timestamp，每周特权的下周一不能把勿扰时段的次日顶掉。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class DailyPendingEffectiveTimeTest {

    private lateinit var context: Context

    private val ledgerPrefs get() = context.getSharedPreferences(CreditManager.PREFS_NAME, Context.MODE_PRIVATE)
    private val pendingPrefs get() = context.getSharedPreferences("decikeeper_rules_pending", Context.MODE_PRIVATE)

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("decikeeper_rules", Context.MODE_PRIVATE).edit().clear().commit()
        pendingPrefs.edit().clear().commit()
        context.getSharedPreferences("decikeeper_rules_baseline", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("decikeeper_changelog", Context.MODE_PRIVATE).edit().clear().commit()
        ledgerPrefs.edit().clear().commit()
        CreditManager.invalidateCache()
        TimeRuleManager.invalidateSegmentsCache()
        ledgerPrefs.edit().putBoolean("has_manually_ended_adaptation", true).commit()
        TimeRuleManager.saveRefreshTime(context, "06:00")
        context.getSharedPreferences("decikeeper_rules", Context.MODE_PRIVATE).edit()
            .putInt("cool_down_rest_seconds", 120).putInt("cool_down_gap_seconds", 120).commit()
        TimeRuleManager.saveBaselineParams(context)
    }

    private fun nextMondayAfterNow() = ConfigPendingManager.calculateNextMondayEffectiveTime(context, System.currentTimeMillis())
    private fun tomorrowRefresh() = ConfigPendingManager.calculateNextEffectiveTime(context, System.currentTimeMillis())

    @Test
    fun staleMondayTimestampIsPulledBackToTomorrowWhenDailyChangesArePending() {
        ChangeLogManager.addEntries(context, "segments", listOf(Triple("[08:30-12:00] 上午 次数上限: 7次 -> 3次", true, "policy_all_next_day_0")))
        pendingPrefs.edit()
            .putBoolean("has_pending_changes", true)
            .putLong("effective_timestamp", nextMondayAfterNow())
            .commit()

        TimeRuleManager.healDailyPendingEffectiveTime(context)

        assertEquals(tomorrowRefresh(), pendingPrefs.getLong("effective_timestamp", 0L))
    }

    @Test
    fun weeklyOnlyPendingKeepsItsMondayTimestamp() {
        ChangeLogManager.addEntries(context, "global_params", listOf(Triple("每周特权次数: 2 -> 3 (下周生效)", true, "weekly_privilege_count")))
        val monday = nextMondayAfterNow()
        pendingPrefs.edit().putBoolean("has_pending_changes", true).putLong("effective_timestamp", monday).commit()

        TimeRuleManager.healDailyPendingEffectiveTime(context)

        assertEquals("只有每周特权待生效时不动它的下周一", monday, pendingPrefs.getLong("effective_timestamp", 0L))
    }

    @Test
    fun healNeverPushesAnEarlierDailyTimestampBack() {
        ChangeLogManager.addEntries(context, "segments", listOf(Triple("x", true, "policy_all_next_day_0")))
        val earlier = System.currentTimeMillis() - 60_000
        pendingPrefs.edit().putBoolean("has_pending_changes", true).putLong("effective_timestamp", earlier).commit()

        TimeRuleManager.healDailyPendingEffectiveTime(context)

        assertEquals(earlier, pendingPrefs.getLong("effective_timestamp", 0L))
    }

    @Test
    fun savingWeeklyPrivilegeDoesNotOverwriteTomorrowOfPendingSegments() {
        // 勿扰时段已有待生效（次日），再改每周特权，pending 的时间戳仍应是次日而不是下周一
        ChangeLogManager.addEntries(context, "segments", listOf(Triple("x", true, "policy_all_next_day_0")))
        pendingPrefs.edit()
            .putString("segments_json", "[]")
            .putBoolean("has_pending_changes", true)
            .putLong("effective_timestamp", tomorrowRefresh())
            .commit()

        val count0 = CreditManager.getWeeklyPrivilegeLimit(context)
        TimeRuleManager.analyzeAndSaveGlobalParams(
            context,
            newRest = TimeRuleManager.getCoolDownRestSeconds(context),
            newGap = TimeRuleManager.getCoolDownGapSeconds(context),
            newLockDuration = TimeRuleManager.getStrictPenaltyDurationSeconds(context),
            newBorrow = TimeRuleManager.getBorrowPercentage(context),
            newNotifyFar = TimeRuleManager.getNotifyAdvanceMinutes(context),
            newNotifyNear = TimeRuleManager.getNotifyEntryNearMinutes(context),
            newWeeklyCount = count0 + 1,
            newWeeklyDuration = CreditManager.getWeeklyPrivilegeDurationLimit(context),
            newRefreshTime = TimeRuleManager.getRefreshTime(context)
        )

        assertTrue(CreditManager.hasPendingWeeklyPrivilegeLimit(context))
        assertEquals(tomorrowRefresh(), pendingPrefs.getLong("effective_timestamp", 0L))
        assertEquals("每周特权自己仍是下周一", nextMondayAfterNow(), CreditManager.getPendingWeeklyPrivilegeEffectiveTime(context))
    }
}
