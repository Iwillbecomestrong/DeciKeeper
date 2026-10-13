package com.decikeeper.app.manager

import android.content.Context
import com.decikeeper.app.data.BlacklistStore
import com.decikeeper.app.data.TimeSegment
import com.decikeeper.app.manager.AppWindowManager.AppWindowRule
import com.decikeeper.app.manager.PendingChangeTable.Direction
import com.decikeeper.app.manager.PendingChangeTable.Scope
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 延迟生效表：四处待生效修改统一枚举，并能逐项撤销（只收回这一项，别的待生效项原样保留）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PendingChangeTableTest {

    private lateinit var context: Context

    private val rulesPrefs get() = context.getSharedPreferences("decikeeper_rules", Context.MODE_PRIVATE)
    private val pendingPrefs get() = context.getSharedPreferences("decikeeper_rules_pending", Context.MODE_PRIVATE)
    private val baselinePrefs get() = context.getSharedPreferences("decikeeper_rules_baseline", Context.MODE_PRIVATE)
    private val ledgerPrefs get() = context.getSharedPreferences(CreditManager.PREFS_NAME, Context.MODE_PRIVATE)
    private val policyPrefs get() = context.getSharedPreferences("effective_activation_policy", Context.MODE_PRIVATE)

    private val baseline = listOf(
        TimeSegment(9, 0, 12, 0, limitMins = 30, name = "上午", usableCountLimitHalfUnits = 14),
        TimeSegment(13, 0, 17, 0, limitMins = 35, name = "下午", usableCountLimitHalfUnits = 14),
        TimeSegment(18, 0, 22, 0, limitMins = 35, name = "晚上", usableCountLimitHalfUnits = 14)
    )

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        listOf(
            rulesPrefs, pendingPrefs, baselinePrefs, ledgerPrefs, policyPrefs,
            context.getSharedPreferences("app_window_rules", Context.MODE_PRIVATE),
            context.getSharedPreferences("app_window_pending", Context.MODE_PRIVATE),
            context.getSharedPreferences("app_window_baseline", Context.MODE_PRIVATE),
            context.getSharedPreferences("decikeeper_pending_apps", Context.MODE_PRIVATE),
            context.getSharedPreferences("decikeeper_changelog", Context.MODE_PRIVATE)
        ).forEach { it.edit().clear().commit() }
        CreditManager.invalidateCache()
        ledgerPrefs.edit().putBoolean("has_manually_ended_adaptation", true).commit()
        TimeRuleManager.saveRefreshTime(context, "06:00")
        rulesPrefs.edit()
            .putString("segments_json", TimeRuleManager.segmentsToJson(baseline))
            .putInt("cool_down_rest_seconds", 120).putInt("cool_down_gap_seconds", 120).commit()
        TimeRuleManager.invalidateSegmentsCache()
        TimeRuleManager.saveBaselineSnapshot(context)
        AppWindowManager.invalidateCache()
    }

    private fun draft(): List<TimeSegment> =
        TimeRuleManager.getPendingTimeSegments(context) ?: TimeRuleManager.getTimeSegments(context)

    private fun saveSegments(edit: (List<TimeSegment>) -> List<TimeSegment>) {
        TimeRuleManager.analyzeAndSaveTimeSegments(context, edit(draft()))
        TimeRuleManager.invalidateSegmentsCache()
    }

    /** 表单底稿：有 pending 的参数显示 pending 值，否则显示生效值（和规则参数页回填输入框一致）。 */
    private fun pendingOr(key: String, active: Int): Int =
        if (TimeRuleManager.hasPendingRules(context) && pendingPrefs.contains(key)) pendingPrefs.getInt(key, active) else active

    private fun saveParams(
        rest: Int = pendingOr("cool_down_rest_seconds", TimeRuleManager.getCoolDownRestSeconds(context)),
        gap: Int = pendingOr("cool_down_gap_seconds", TimeRuleManager.getCoolDownGapSeconds(context)),
        weeklyDuration: Int = if (CreditManager.hasPendingWeeklyPrivilegeLimit(context)) {
            CreditManager.getPendingWeeklyPrivilegeDurationLimit(context)
        } else {
            CreditManager.getWeeklyPrivilegeDurationLimit(context)
        }
    ) {
        TimeRuleManager.analyzeAndSaveGlobalParams(
            context,
            newRest = rest, newGap = gap,
            newLockDuration = TimeRuleManager.getStrictPenaltyDurationSeconds(context),
            newBorrow = TimeRuleManager.getBorrowPercentage(context),
            newNotifyFar = TimeRuleManager.getNotifyAdvanceMinutes(context),
            newNotifyNear = TimeRuleManager.getNotifyEntryNearMinutes(context),
            newWeeklyCount = CreditManager.getWeeklyPrivilegeLimit(context),
            newWeeklyDuration = weeklyDuration,
            newRefreshTime = TimeRuleManager.getRefreshTime(context)
        )
    }

    private fun item(scope: Scope, titlePart: String) =
        PendingChangeTable.items(context, scope).first { it.title.contains(titlePart) }

    // ── 勿扰时段 ──────────────────────────────────────────────────────

    @Test
    fun segmentLoosensAreListedOneByOneAndRevokedIndependently() {
        saveSegments { segs -> segs.map { if (it.name == "晚上") it.copy(endHour = 21, endMinute = 30) else it } }
        saveSegments { segs -> segs.map { if (it.name == "上午") it.copy(startHour = 9, startMinute = 30) else it } }

        val items = PendingChangeTable.items(context, Scope.SEGMENT)
        assertEquals(2, items.size)
        assertTrue(items.all { it.direction == Direction.LOOSER })

        assertTrue(PendingChangeTable.revoke(context, item(Scope.SEGMENT, "晚上")))

        val left = PendingChangeTable.items(context, Scope.SEGMENT)
        assertEquals("只撤销了晚上，上午的放宽仍在", 1, left.size)
        assertTrue(left.single().title.contains("上午"))
        assertEquals(30, TimeRuleManager.getPendingTimeSegments(context)!!.first { it.name == "上午" }.startMinute)
        assertEquals("晚上回到生效中的 22:00", 22,
            TimeRuleManager.getPendingTimeSegments(context)!!.first { it.name == "晚上" }.endHour)
        assertEquals("撤销不改生效中的时段", baseline.map { it.name to it.endHour },
            TimeRuleManager.getTimeSegments(context).map { it.name to it.endHour })
    }

    @Test
    fun revokingTheLastSegmentItemClearsAllPending() {
        saveSegments { segs -> segs.map { if (it.name == "晚上") it.copy(endHour = 21, endMinute = 30) else it } }
        assertTrue(TimeRuleManager.hasPendingRules(context))

        assertTrue(PendingChangeTable.revoke(context, item(Scope.SEGMENT, "晚上")))

        assertTrue(PendingChangeTable.items(context).isEmpty())
        assertFalse("没有任何待生效项时总开关要关掉", TimeRuleManager.hasPendingRules(context))
        assertNull(TimeRuleManager.getPendingTimeSegments(context))
    }

    @Test
    fun pendingDeletionAndAdditionCanBeRevoked() {
        policyPrefs.edit().putString("policy", "all_next_day").commit()
        saveSegments { segs ->
            segs.filter { it.name != "下午" } + TimeSegment(23, 0, 23, 30, limitMins = 5, name = "夜里")
        }

        val items = PendingChangeTable.items(context, Scope.SEGMENT)
        assertEquals(2, items.size)
        assertEquals("统一延后下连新增（加严）也挂 pending", Direction.STRICTER, item(Scope.SEGMENT, "夜里").direction)
        assertEquals(Direction.LOOSER, item(Scope.SEGMENT, "下午").direction)

        assertTrue(PendingChangeTable.revoke(context, item(Scope.SEGMENT, "下午")))
        assertTrue("撤销删除 = 待生效表里把下午加回来",
            TimeRuleManager.getPendingTimeSegments(context)!!.any { it.name == "下午" })
        assertTrue(PendingChangeTable.revoke(context, item(Scope.SEGMENT, "夜里")))

        assertTrue(PendingChangeTable.items(context).isEmpty())
        assertFalse(TimeRuleManager.hasPendingRules(context))
    }

    @Test
    fun strictChangeDelayedByAllNextDayCanBeRevoked() {
        policyPrefs.edit().putString("policy", "all_next_day").commit()
        saveSegments { segs -> segs.map { it.copy(usableCountLimitHalfUnits = 6) } }   // 次数 14 → 6：本来是加严

        val items = PendingChangeTable.items(context, Scope.SEGMENT)
        assertEquals(3, items.size)
        assertTrue("加严的项也在表里", items.all { it.direction == Direction.STRICTER })
        assertEquals("统一延后下加严不立即生效", 14, TimeRuleManager.getTimeSegments(context).first().usableCountLimitHalfUnits)

        items.forEach { assertTrue(PendingChangeTable.revoke(context, it)) }

        assertTrue(PendingChangeTable.items(context).isEmpty())
        assertFalse(TimeRuleManager.hasPendingRules(context))
    }

    // ── 规则参数 ──────────────────────────────────────────────────────

    @Test
    fun paramLoosensAreRevokedIndependently() {
        saveParams(rest = 90)
        saveParams(gap = 90)
        assertEquals(2, PendingChangeTable.items(context, Scope.PARAM).size)

        assertTrue(PendingChangeTable.revoke(context, item(Scope.PARAM, "休息冷却")))

        val left = PendingChangeTable.items(context, Scope.PARAM)
        assertEquals(1, left.size)
        assertTrue(left.single().title.contains("空档冷却"))
        assertEquals(90, pendingPrefs.getInt("cool_down_gap_seconds", -1))
        assertTrue(TimeRuleManager.hasPendingRules(context))
        assertEquals("生效中的休息冷却不动", 120, TimeRuleManager.getCoolDownRestSeconds(context))

        assertTrue(PendingChangeTable.revoke(context, left.single()))
        assertFalse(TimeRuleManager.hasPendingRules(context))
        assertTrue(PendingChangeTable.items(context).isEmpty())
    }

    @Test
    fun weeklyPrivilegeIsRevokedWithoutTouchingDailyParams() {
        val duration0 = CreditManager.getWeeklyPrivilegeDurationLimit(context)
        saveParams(rest = 90, weeklyDuration = duration0 + 120)
        assertEquals(2, PendingChangeTable.items(context, Scope.PARAM).size)

        assertTrue(PendingChangeTable.revoke(context, item(Scope.PARAM, "每周特权总时长")))

        assertFalse(CreditManager.hasPendingWeeklyPrivilegeLimit(context))
        assertEquals("日参数的放宽还在", 90, pendingPrefs.getInt("cool_down_rest_seconds", -1))
        assertEquals(1, PendingChangeTable.items(context, Scope.PARAM).size)
    }

    @Test
    fun onlyWeeklyLeftKeepsPendingFlagWithMondayEffectiveTime() {
        val duration0 = CreditManager.getWeeklyPrivilegeDurationLimit(context)
        saveParams(rest = 90, weeklyDuration = duration0 + 120)

        assertTrue(PendingChangeTable.revoke(context, item(Scope.PARAM, "休息冷却")))

        assertTrue("每周特权仍待生效，总开关保持", TimeRuleManager.hasPendingRules(context))
        assertEquals(
            CreditManager.getPendingWeeklyPrivilegeEffectiveTime(context),
            pendingPrefs.getLong("effective_timestamp", 0L)
        )
        assertFalse("陈旧的日参数快照键要清掉", pendingPrefs.contains("cool_down_rest_seconds"))
        assertEquals(1, PendingChangeTable.items(context, Scope.PARAM).size)
    }

    @Test
    fun strictParamDelayedByAllNextDayIsListedAsStricter() {
        policyPrefs.edit().putString("policy", "all_next_day").commit()
        saveParams(rest = 150)   // 冷却变长：本来是加严

        val rest = item(Scope.PARAM, "休息冷却")
        assertEquals(Direction.STRICTER, rest.direction)
        assertEquals(120, TimeRuleManager.getCoolDownRestSeconds(context))

        assertTrue(PendingChangeTable.revoke(context, rest))
        assertTrue(PendingChangeTable.items(context).isEmpty())
    }

    // ── 应用时间窗 ────────────────────────────────────────────────────

    @Test
    fun appWindowLoosenIsListedAndRevoked() {
        val ruleId = "rule_work"
        val base = JSONArray().put(JSONObject().apply {
            put("id", ruleId); put("name", "工作日娱乐"); put("startTime", 21 * 60); put("endTime", 23 * 60 + 30)
            put("packageNames", JSONArray(listOf("com.test.game"))); put("isEnabled", true)
            put("daysOfWeek", JSONArray(listOf(1, 2, 3, 4, 5)))
        }).toString()
        context.getSharedPreferences("app_window_rules", Context.MODE_PRIVATE).edit().putString("rules_json", base).commit()
        context.getSharedPreferences("app_window_baseline", Context.MODE_PRIVATE).edit()
            .putString("baseline_rules_json", base).putLong("snapshot_time", System.currentTimeMillis()).commit()
        AppWindowManager.invalidateCache()

        val rule = AppWindowManager.getRules(context).first { it.id == ruleId }
        AppWindowManager.updateRule(context, rule.copy(startTime = 20 * 60 + 30))   // 开始提前：放宽

        val items = PendingChangeTable.items(context, Scope.APP_WINDOW)
        assertEquals(1, items.size)
        assertEquals(Direction.LOOSER, items.single().direction)

        assertTrue(PendingChangeTable.revoke(context, items.single()))

        assertTrue(PendingChangeTable.items(context, Scope.APP_WINDOW).isEmpty())
        assertFalse(AppWindowManager.hasPendingChanges(context))
        assertEquals(21 * 60, AppWindowManager.getRules(context).first { it.id == ruleId }.startTime)
    }

    // ── 全局黑名单 ────────────────────────────────────────────────────

    @Test
    fun blacklistRemovalIsRevokedPerPackage() {
        val a = "com.test.a"
        val b = "com.test.b"
        BlacklistStore.invalidateBlacklistCache()
        BlacklistStore.saveTargetAppsImmediately(context, setOf(a, b))
        BlacklistStore.stagePendingBlacklistApps(context, emptySet(), System.currentTimeMillis() + 3_600_000L)

        val items = PendingChangeTable.items(context, Scope.BLACKLIST)
        assertEquals(2, items.size)
        assertTrue(items.all { it.direction == Direction.LOOSER })

        // 撤销 a 的解除：待生效名单里把 a 加回去，只剩 b 的解除还在
        assertTrue(PendingChangeTable.revoke(context, items.first { it.id == "bl:$a" }))
        assertEquals(setOf(a), BlacklistStore.getPendingBlacklistApps(context))
        assertEquals(listOf("bl:$b"), PendingChangeTable.items(context, Scope.BLACKLIST).map { it.id })

        assertTrue(PendingChangeTable.revoke(context, PendingChangeTable.items(context, Scope.BLACKLIST).single()))
        assertNull(BlacklistStore.getPendingBlacklistApps(context))
        assertNotNull(BlacklistStore.getBlacklistApps(context))
    }

    @Test
    fun revokingAnItemThatNoLongerExistsIsRejected() {
        saveParams(rest = 90)
        val rest = item(Scope.PARAM, "休息冷却")
        assertTrue(PendingChangeTable.revoke(context, rest))
        assertFalse(PendingChangeTable.revoke(context, rest))
    }

    // ── 表里左边固定展示基准值，而不是会随加严立刻变化的生效值 ──────────────

    @Test
    fun weeklyPendingShowsWeeklyBaselineNotTheTightenedActiveValue() {
        CreditManager.setWeeklyPrivilegeLimit(context, 2, 480)
        TimeRuleManager.saveBaselineParams(context, forceWeekly = true) // 周初的那一份基准
        // 本周中途加严：生效值立刻变成 1 次 / 60 分钟，随后又想放宽到 3 次 / 600 分钟（下周一生效）
        CreditManager.setWeeklyPrivilegeLimit(context, 1, 60)
        CreditManager.setPendingWeeklyPrivilegeLimit(context, 3, 600, System.currentTimeMillis() + 3_600_000L)

        assertEquals("2次 → 3次", item(Scope.PARAM, "每周特权次数").detail)
        assertEquals("480分钟 → 600分钟", item(Scope.PARAM, "每周特权总时长").detail)
    }

    @Test
    fun weeklyBaselineStaysPutForTheRestOfTheWeek() {
        CreditManager.setWeeklyPrivilegeLimit(context, 2, 480)
        TimeRuleManager.saveBaselineParams(context, forceWeekly = true) // 周初的那一份基准
        CreditManager.setWeeklyPrivilegeLimit(context, 1, 60)

        TimeRuleManager.saveBaselineParams(context) // 次日的每日快照
        TimeRuleManager.saveBaselineSnapshot(context)

        assertEquals(2, TimeRuleManager.getBaselineWeeklyPrivilegeLimit(context))
        assertEquals(480, TimeRuleManager.getBaselineWeeklyPrivilegeDurationLimit(context))
    }

    @Test
    fun weeklyBaselineFollowsActiveOnceANewWeekStarts() {
        CreditManager.setWeeklyPrivilegeLimit(context, 2, 480)
        TimeRuleManager.saveBaselineParams(context, forceWeekly = true) // 周初的那一份基准
        CreditManager.setWeeklyPrivilegeLimit(context, 3, 600)
        // 把基准里记的周起点拨回上一周，等价于「已经进入新的一周」
        baselinePrefs.edit().putLong("weekly_baseline_week_start", 1L).commit()

        TimeRuleManager.saveBaselineParams(context)

        assertEquals(3, TimeRuleManager.getBaselineWeeklyPrivilegeLimit(context))
        assertEquals(600, TimeRuleManager.getBaselineWeeklyPrivilegeDurationLimit(context))
    }

    @Test
    fun dailyParamPendingShowsDailyBaselineNotTheTightenedActiveValue() {
        // 基准 120 秒；当天加严到 300 秒（生效值立刻变），再想放宽到 90 秒 → 挂待生效
        rulesPrefs.edit().putInt("cool_down_rest_seconds", 300).commit()
        saveParams(rest = 90)

        assertEquals("120秒 → 90秒", item(Scope.PARAM, "休息冷却").detail)
    }
}
