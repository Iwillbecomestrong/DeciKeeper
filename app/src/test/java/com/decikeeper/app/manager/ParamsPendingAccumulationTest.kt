package com.decikeeper.app.manager

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 规则参数的延迟生效：每次保存都拿「表单上的完整状态」和基准逐项对比。
 * 放宽的参数各自进 pending，保存别的参数不能把已有的放宽冲掉。
 *
 * 表单的底稿：有 pending 的参数显示 pending 值，否则显示生效值（GlobalParamsFragment 就是这样回填输入框的）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ParamsPendingAccumulationTest {

    private lateinit var context: Context

    private val ledgerPrefs get() = context.getSharedPreferences(CreditManager.PREFS_NAME, Context.MODE_PRIVATE)
    private val pendingPrefs get() = context.getSharedPreferences("decikeeper_rules_pending", Context.MODE_PRIVATE)

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("decikeeper_rules", Context.MODE_PRIVATE).edit().clear().commit()
        pendingPrefs.edit().clear().commit()
        context.getSharedPreferences("decikeeper_rules_baseline", Context.MODE_PRIVATE).edit().clear().commit()
        ledgerPrefs.edit().clear().commit()
        CreditManager.invalidateCache()
        TimeRuleManager.invalidateSegmentsCache()
        ledgerPrefs.edit().putBoolean("has_manually_ended_adaptation", true).commit()
        TimeRuleManager.saveRefreshTime(context, "06:00")
        // 先把冷却时间显式写进生效值（否则基准读到的默认值和生效读到的默认值不一致），再存成基准
        context.getSharedPreferences("decikeeper_rules", Context.MODE_PRIVATE).edit()
            .putInt("cool_down_rest_seconds", 120).putInt("cool_down_gap_seconds", 120).commit()
        TimeRuleManager.saveBaselineParams(context)
    }

    private class Form(
        var rest: Int, var gap: Int, var borrow: Int, var notifyFar: Int, var notifyNear: Int,
        var weeklyCount: Int, var weeklyDuration: Int
    )

    /** 表单底稿：pending 优先，否则生效。 */
    private fun form(): Form {
        fun pendingInt(key: String, fallback: Int): Int =
            if (TimeRuleManager.hasPendingRules(context) && pendingPrefs.contains(key)) pendingPrefs.getInt(key, fallback) else fallback
        val hasWeekly = CreditManager.hasPendingWeeklyPrivilegeLimit(context)
        return Form(
            rest = pendingInt("cool_down_rest_seconds", TimeRuleManager.getCoolDownRestSeconds(context)),
            gap = pendingInt("cool_down_gap_seconds", TimeRuleManager.getCoolDownGapSeconds(context)),
            borrow = pendingInt("borrow_percentage", TimeRuleManager.getBorrowPercentage(context)),
            notifyFar = pendingInt("notify_advance_minutes", TimeRuleManager.getNotifyAdvanceMinutes(context)),
            notifyNear = pendingInt("notify_entry_near_minutes", TimeRuleManager.getNotifyEntryNearMinutes(context)),
            weeklyCount = if (hasWeekly) CreditManager.getPendingWeeklyPrivilegeLimit(context) else CreditManager.getWeeklyPrivilegeLimit(context),
            weeklyDuration = if (hasWeekly) CreditManager.getPendingWeeklyPrivilegeDurationLimit(context) else CreditManager.getWeeklyPrivilegeDurationLimit(context)
        )
    }

    private fun save(edit: Form.() -> Unit) {
        val f = form().apply(edit)
        TimeRuleManager.analyzeAndSaveGlobalParams(
            context,
            newRest = f.rest, newGap = f.gap,
            newLockDuration = TimeRuleManager.getStrictPenaltyDurationSeconds(context),
            newBorrow = f.borrow, newNotifyFar = f.notifyFar, newNotifyNear = f.notifyNear,
            newWeeklyCount = f.weeklyCount, newWeeklyDuration = f.weeklyDuration,
            newRefreshTime = TimeRuleManager.getRefreshTime(context)
        )
    }

    @Test
    fun twoCoolDownLoosensAreBothKeptPending() {
        val rest0 = TimeRuleManager.getCoolDownRestSeconds(context)
        val gap0 = TimeRuleManager.getCoolDownGapSeconds(context)

        save { rest = rest0 - 30 }          // 冷却缩短：放宽
        save { gap = gap0 - 30 }            // 另一个冷却也缩短：放宽

        assertEquals("放宽的冷却都不立即生效", rest0, TimeRuleManager.getCoolDownRestSeconds(context))
        assertEquals(gap0, TimeRuleManager.getCoolDownGapSeconds(context))
        assertEquals("先放宽的休息冷却不能丢", rest0 - 30, pendingPrefs.getInt("cool_down_rest_seconds", -1))
        assertEquals(gap0 - 30, pendingPrefs.getInt("cool_down_gap_seconds", -1))
    }

    @Test
    fun weeklyCountThenDurationAreBothKeptPending() {
        val count0 = CreditManager.getWeeklyPrivilegeLimit(context)
        val duration0 = CreditManager.getWeeklyPrivilegeDurationLimit(context)

        save { weeklyCount = count0 + 2 }            // 次数放宽
        assertTrue(CreditManager.hasPendingWeeklyPrivilegeLimit(context))
        save { weeklyDuration = duration0 + 120 }    // 时长也放宽

        assertEquals("放宽都不立即生效", count0, CreditManager.getWeeklyPrivilegeLimit(context))
        assertEquals(duration0, CreditManager.getWeeklyPrivilegeDurationLimit(context))
        assertEquals("先放宽的次数不能丢", count0 + 2, CreditManager.getPendingWeeklyPrivilegeLimit(context))
        assertEquals(duration0 + 120, CreditManager.getPendingWeeklyPrivilegeDurationLimit(context))
    }

    @Test
    fun weeklyDurationThenCountAreBothKeptPending() {
        val count0 = CreditManager.getWeeklyPrivilegeLimit(context)
        val duration0 = CreditManager.getWeeklyPrivilegeDurationLimit(context)

        save { weeklyDuration = duration0 + 120 }
        save { weeklyCount = count0 + 2 }

        assertEquals(duration0 + 120, CreditManager.getPendingWeeklyPrivilegeDurationLimit(context))
        assertEquals(count0 + 2, CreditManager.getPendingWeeklyPrivilegeLimit(context))
    }

    @Test
    fun aCoolDownLoosenAndAWeeklyLoosenAreBothKept() {
        val rest0 = TimeRuleManager.getCoolDownRestSeconds(context)
        val duration0 = CreditManager.getWeeklyPrivilegeDurationLimit(context)

        save { rest = rest0 - 30 }
        save { weeklyDuration = duration0 + 120 }

        assertEquals(rest0 - 30, pendingPrefs.getInt("cool_down_rest_seconds", -1))
        assertEquals(duration0 + 120, CreditManager.getPendingWeeklyPrivilegeDurationLimit(context))
    }

    @Test
    fun restoringTheWeeklyCountToBaselineKeepsTheDurationPending() {
        val count0 = CreditManager.getWeeklyPrivilegeLimit(context)
        val duration0 = CreditManager.getWeeklyPrivilegeDurationLimit(context)
        save { weeklyCount = count0 + 2; weeklyDuration = duration0 + 120 }

        save { weeklyCount = count0 }       // 次数改回基准

        assertEquals("时长的放宽仍在 pending", duration0 + 120, CreditManager.getPendingWeeklyPrivilegeDurationLimit(context))
        assertEquals(count0, CreditManager.getPendingWeeklyPrivilegeLimit(context))
    }

    @Test
    fun restoringEverythingClearsThePending() {
        val count0 = CreditManager.getWeeklyPrivilegeLimit(context)
        save { weeklyCount = count0 + 2 }

        save { weeklyCount = count0 }

        assertFalse(CreditManager.hasPendingWeeklyPrivilegeLimit(context))
    }

    private fun pendingLogTexts(): List<String> =
        ChangeLogManager.getVisibleEntriesForCategory(context, "global_params").map { it.description }

    @Test
    fun theChangeLogKeepsBothWeeklyPrivilegeLoosens() {
        val count0 = CreditManager.getWeeklyPrivilegeLimit(context)
        val duration0 = CreditManager.getWeeklyPrivilegeDurationLimit(context)

        save { weeklyCount = count0 + 2 }
        save { weeklyDuration = duration0 + 120 }

        val texts = pendingLogTexts()
        assertTrue("待生效记录要有次数这一条：$texts", texts.any { it.contains("每周特权次数") })
        assertTrue("也要有总时长这一条：$texts", texts.any { it.contains("每周特权总时长") })
    }

    @Test
    fun loosenAgainTheSameWeeklyItemReplacesOnlyItsOwnLogEntry() {
        val count0 = CreditManager.getWeeklyPrivilegeLimit(context)
        val duration0 = CreditManager.getWeeklyPrivilegeDurationLimit(context)
        save { weeklyCount = count0 + 2; weeklyDuration = duration0 + 120 }

        save { weeklyDuration = duration0 + 240 }          // 总时长再放宽一点

        val texts = pendingLogTexts()
        assertEquals("总时长只留最新一条", 1, texts.count { it.contains("每周特权总时长") })
        assertTrue("次数那一条不受影响", texts.any { it.contains("每周特权次数") })
    }

    @Test
    fun restoringTheWeeklyCountRemovesOnlyTheCountLogEntry() {
        val count0 = CreditManager.getWeeklyPrivilegeLimit(context)
        val duration0 = CreditManager.getWeeklyPrivilegeDurationLimit(context)
        save { weeklyCount = count0 + 2; weeklyDuration = duration0 + 120 }

        save { weeklyCount = count0 }                      // 次数改回基准

        val texts = pendingLogTexts().filter { !it.contains("恢复") }
        assertTrue("总时长的待生效记录还在", texts.any { it.contains("每周特权总时长") })
        assertFalse("次数的待生效记录已清掉", texts.any { it.contains("每周特权次数") })
    }
}
