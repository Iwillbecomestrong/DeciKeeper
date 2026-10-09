package com.decikeeper.app.manager

import android.content.Context
import com.decikeeper.app.manager.AppWindowManager.AppWindowRule
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 应用时间窗是「允许窗口」：缩短 = 加严（立即），延长 = 放宽（挂 pending，次日生效）。
 * 每次保存都拿「想要的完整状态」和基准逐项对比；保存别的变化不能冲掉已有的 pending。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AppWindowPendingAccumulationTest {

    private lateinit var context: Context

    private val activePrefs get() = context.getSharedPreferences("app_window_rules", Context.MODE_PRIVATE)
    private val pendingPrefs get() = context.getSharedPreferences("app_window_pending", Context.MODE_PRIVATE)
    private val baselinePrefs get() = context.getSharedPreferences("app_window_baseline", Context.MODE_PRIVATE)
    private val ledgerPrefs get() = context.getSharedPreferences(CreditManager.PREFS_NAME, Context.MODE_PRIVATE)

    private val ruleId = "rule_work"
    private fun rule(start: Int, end: Int) = AppWindowRule(
        id = ruleId, name = "工作日娱乐", startTime = start, endTime = end,
        packageNames = mutableSetOf("com.test.game"), isEnabled = true, daysOfWeek = setOf(1, 2, 3, 4, 5)
    )

    private fun rulesJson(rule: AppWindowRule): String = JSONArray().put(JSONObject().apply {
        put("id", rule.id); put("name", rule.name); put("startTime", rule.startTime); put("endTime", rule.endTime)
        put("packageNames", JSONArray(rule.packageNames.toList())); put("isEnabled", rule.isEnabled)
        put("daysOfWeek", JSONArray(rule.daysOfWeek.toList()))
    }).toString()

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        activePrefs.edit().clear().commit()
        pendingPrefs.edit().clear().commit()
        baselinePrefs.edit().clear().commit()
        ledgerPrefs.edit().clear().commit()
        CreditManager.invalidateCache()
        ledgerPrefs.edit().putBoolean("has_manually_ended_adaptation", true).commit()
        TimeRuleManager.saveRefreshTime(context, "06:00")
        val base = rulesJson(rule(21 * 60, 23 * 60 + 30))
        activePrefs.edit().putString("rules_json", base).commit()
        baselinePrefs.edit().putString("baseline_rules_json", base).putLong("snapshot_time", System.currentTimeMillis()).commit()
        AppWindowManager.invalidateCache()
    }

    /** 编辑底稿：有 pending 用 pending，否则用生效。 */
    private fun draft(): AppWindowRule =
        AppWindowManager.getPendingRules(context)?.firstOrNull { it.id == ruleId }
            ?: AppWindowManager.getRules(context).first { it.id == ruleId }

    private fun active(): AppWindowRule {
        AppWindowManager.invalidateCache()
        return AppWindowManager.getRules(context).first { it.id == ruleId }
    }

    private fun pending(): AppWindowRule? = AppWindowManager.getPendingRules(context)?.firstOrNull { it.id == ruleId }

    private fun save(edit: (AppWindowRule) -> AppWindowRule) {
        AppWindowManager.updateRule(context, edit(draft()))
    }

    @Test
    fun twoLoosenEditsAccumulateInPending() {
        save { it.copy(startTime = 20 * 60 + 30) }                  // 开始提前：放宽
        assertNotNull(pending())

        save { it.copy(endTime = 23 * 60 + 45) }                    // 结束推后：放宽

        assertEquals("先前放宽的开始时间不能丢", 20 * 60 + 30, pending()!!.startTime)
        assertEquals(23 * 60 + 45, pending()!!.endTime)
        assertEquals("放宽都不立即生效", 21 * 60, active().startTime)
        assertEquals(23 * 60 + 30, active().endTime)
    }

    @Test
    fun tightenAndLoosenInOneEditSplitsImmediateAndPending() {
        // 你备份里的真实操作：21:00-23:30 → 20:30-23:00（开始提前 = 放宽，结束提前 = 加严）
        save { it.copy(startTime = 20 * 60 + 30, endTime = 23 * 60) }

        assertEquals("加严的结束时间立即生效", 23 * 60, active().endTime)
        assertEquals("放宽的开始时间不立即生效", 21 * 60, active().startTime)
        assertEquals("放宽的开始时间挂 pending", 20 * 60 + 30, pending()!!.startTime)
    }

    @Test
    fun aTightenAfterALoosenKeepsThePendingAndGoesLive() {
        save { it.copy(startTime = 20 * 60 + 30) }                  // 放宽，挂 pending

        save { it.copy(endTime = 23 * 60) }                         // 底稿是 pending（20:30-23:30），再把结束提前：加严

        assertEquals("加严立即生效", 23 * 60, active().endTime)
        assertEquals("先前放宽的 pending 保留", 20 * 60 + 30, pending()!!.startTime)
        assertEquals(21 * 60, active().startTime)
    }
}
