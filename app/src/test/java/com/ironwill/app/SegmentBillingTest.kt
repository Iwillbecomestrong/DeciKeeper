package com.ironwill.app

import android.content.Context
import com.ironwill.app.data.SegmentBlacklist
import com.ironwill.app.data.TimeSegment
import com.ironwill.app.manager.AppWindowManager
import com.ironwill.app.manager.CreditManager
import com.ironwill.app.manager.TimeRuleManager
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config







/**
 * TASK-03 (segment-blacklist) RED — billing 一致性（SPEC §2.7 / §6 S7）。
 *
 * 语义：
 *   - 计费条件 = isBlacklisted && isInSegment && !isFreeTime && 距上次扣费 >= 60000ms。
 *   - isBlacklisted 已段感知（TASK-02）：脱钩段(listApps!=null) = isInEffectiveList；
 *     继承态(listApps==null) = CreditManager.shouldBlockApp。
 *   - 脱钩段：名单内应用计费；名单外/豁免应用不计费。
 *   - 继承段：全局黑名单命中计费；未命中不计费（与旧行为等价）。
 *   - 自由时间 / 空档期不计费；硬剔除应用即使在名单里也不计费。
 *
 * 为可测试性，TASK-03 将 billing 条件最小提取为 SegmentBlacklist.shouldBill 纯函数，
 * 由 MonitorService billing 处调用（行为与原内联条件逐位等价）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SegmentBillingTest {

    private lateinit var context: Context

    private val rulesPrefs get() =
        context.getSharedPreferences("iron_will_rules", Context.MODE_PRIVATE)
    private val ledgerPrefs get() =
        context.getSharedPreferences("iron_will_ledger", Context.MODE_PRIVATE)
    private val appWindowPrefs get() =
        context.getSharedPreferences("app_window_rules", Context.MODE_PRIVATE)

    // 普通应用包名：刻意避开 isLauncher("home"/"launcher") 与 isInputMethod 的保守匹配。
    private val normalGame = "com.example.game"
    private val segOnly = "com.example.segonly"
    private val settings = "com.android.settings"

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        rulesPrefs.edit().clear().commit()
        ledgerPrefs.edit().clear().commit()
        appWindowPrefs.edit().clear().commit()
        TimeRuleManager.invalidateSegmentsCache()
        CreditManager.invalidateCache()
        AppWindowManager.invalidateCache()
    }

    private fun writeBlacklist(apps: Set<String>) {
        ledgerPrefs.edit().putStringSet("blacklist_apps_list", apps).commit()
        CreditManager.invalidateCache()
    }

    /** 覆盖整天的段（start=00:00, end=24:00），与 SegmentBlacklistDecisionTest 一致。 */
    private fun fullDaySegment(listApps: List<String>?) = TimeSegment(
        startHour = 0, startMinute = 0,
        endHour = 24, endMinute = 0,
        limitMins = 60,
        name = "全天",
        listApps = listApps
    )

    private fun bill(
        seg: TimeSegment?,
        pkg: String,
        isInSegment: Boolean,
        isFreeTime: Boolean,
        lastBillMs: Long,
        nowMs: Long
    ) = SegmentBlacklist.shouldBill(context, seg, pkg, isInSegment, isFreeTime, lastBillMs, nowMs)

    // ────────────────────────────────────────────────────────────
    //  脱钩段
    // ────────────────────────────────────────────────────────────

    @Test
    fun decoupled_inListApp_inSegment_notFreeTime_bills() {
        writeBlacklist(emptySet())
        val seg = fullDaySegment(listApps = listOf(normalGame))

        assertTrue(
            "脱钩段名单内应用 + 勿扰时段内 + 非自由时间 → 满足计费条件",
            bill(seg, normalGame, isInSegment = true, isFreeTime = false, lastBillMs = 0L, nowMs = 60_000L)
        )
    }

    @Test
    fun decoupled_notInListApp_doesNotBill() {
        writeBlacklist(emptySet())
        val seg = fullDaySegment(listApps = listOf(segOnly))

        assertFalse(
            "脱钩段名单外应用 → 不满足计费条件",
            bill(seg, normalGame, isInSegment = true, isFreeTime = false, lastBillMs = 0L, nowMs = 60_000L)
        )
    }

    // ────────────────────────────────────────────────────────────
    //  自由时间 / 空档期
    // ────────────────────────────────────────────────────────────

    @Test
    fun freeTime_doesNotBill() {
        writeBlacklist(emptySet())
        val seg = fullDaySegment(listApps = listOf(normalGame))

        assertFalse(
            "自由时间 → 不满足计费条件",
            bill(seg, normalGame, isInSegment = true, isFreeTime = true, lastBillMs = 0L, nowMs = 60_000L)
        )
    }

    @Test
    fun gap_isInSegmentFalse_doesNotBill() {
        // 空档期 currentSeg == null，但全局黑名单仍命中 —— 仍不得扣费。
        writeBlacklist(setOf(normalGame))

        assertFalse(
            "空档期（isInSegment=false）→ 不满足计费条件",
            bill(null, normalGame, isInSegment = false, isFreeTime = false, lastBillMs = 0L, nowMs = 60_000L)
        )
    }

    // ────────────────────────────────────────────────────────────
    //  60s 节流
    // ────────────────────────────────────────────────────────────

    @Test
    fun throttle_under60s_doesNotBill() {
        writeBlacklist(emptySet())
        val seg = fullDaySegment(listApps = listOf(normalGame))

        assertFalse(
            "距上次扣费 < 60000ms → 不满足计费条件",
            bill(seg, normalGame, isInSegment = true, isFreeTime = false, lastBillMs = 0L, nowMs = 59_999L)
        )
    }

    @Test
    fun throttle_atLeast60s_bills() {
        writeBlacklist(emptySet())
        val seg = fullDaySegment(listApps = listOf(normalGame))

        assertTrue(
            "距上次扣费 >= 60000ms → 满足计费条件",
            bill(seg, normalGame, isInSegment = true, isFreeTime = false, lastBillMs = 0L, nowMs = 60_000L)
        )
    }

    // ────────────────────────────────────────────────────────────
    //  继承段（listApps == null）：与旧行为等价
    // ────────────────────────────────────────────────────────────

    @Test
    fun inherited_globalBlacklistHit_bills() {
        writeBlacklist(setOf(normalGame))
        val seg = fullDaySegment(listApps = null)

        assertTrue(
            "继承段：全局黑名单命中 → 满足计费条件（与旧行为等价）",
            bill(seg, normalGame, isInSegment = true, isFreeTime = false, lastBillMs = 0L, nowMs = 60_000L)
        )
    }

    @Test
    fun inherited_globalBlacklistMiss_doesNotBill() {
        writeBlacklist(setOf(normalGame))
        val seg = fullDaySegment(listApps = null)

        assertFalse(
            "继承段：全局黑名单未命中 → 不满足计费条件",
            bill(seg, segOnly, isInSegment = true, isFreeTime = false, lastBillMs = 0L, nowMs = 60_000L)
        )
    }

    // ────────────────────────────────────────────────────────────
    //  硬剔除
    // ────────────────────────────────────────────────────────────

    @Test
    fun hardExemptApp_inList_doesNotBill() {
        writeBlacklist(emptySet())
        val seg = fullDaySegment(listApps = listOf(settings))

        assertFalse(
            "硬剔除应用（如 com.android.settings）即使出现在名单里 → 不满足计费条件",
            bill(seg, settings, isInSegment = true, isFreeTime = false, lastBillMs = 0L, nowMs = 60_000L)
        )
    }
}
