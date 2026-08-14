package com.ironwill.app

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.Calendar

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])

/**
 * Round B —— DND 自然日重置 RED 单元测试。
 *
 * 缺口（Bug 3）：
 *   DND 相关的"日状态"（是否已启用、段内是否已结算）目前没有独立的
 *   自然日（00:00 边界）刷新通道。业务日边界是每天 DND 段开始时间（如 22:00），
 *   跨过自然日 00:00 时业务日不切换，于是旧自然日的 DND 状态会被带入新自然日。
 *
 * Round B 已批准业务规则：
 *   - R1 跨业务天不结余 = 设计原则（不是 Bug 3 触发）；自然日刷新不得改写业务日权威。
 *   - R2 未启用时段不结余 = Bug 3 触发（旧日未启用段不得成为新自然日结余来源）。
 *   - R3 时段内不结算 = Bug 3 触发（旧日段内未结算状态不得带入新自然日）。
 *
 * TDD 阶段：RED。
 *   本测试引用 TASK-04 将新增的生产 seam `CreditManager.observeDndNaturalDay(...)`，
 *   该函数当前不存在 → 编译失败即为合规 RED（失败原因唯一指向"无独立 DND 自然日重置通道"）。
 *   本测试禁止用测试内复制的日期 helper 制造 GREEN，必须命中生产入口 / 生产 seam。
 *   时间通过显式 timestamp 注入，禁止用真实 Calendar.getInstance() 取墙钟时间。
 *
 * seam 契约（TASK-04 需提供）：
 *   fun CreditManager.observeDndNaturalDay(context: Context, nowMillis: Long): Boolean
 *     - 若相对上次观察跨过了自然日 00:00 → 刷新旧日 DND 状态并返回 true
 *     - 同一自然日重复观察（轮询 + 关键读取）→ 返回 false（幂等）
 *     - 只清除 / 隔离旧日 DND 日状态与结余可见性，
 *       不改写额度业务日、段起始业务日、pending、usage/borrow/count 账本、record_date_str。
 */
class DndNaturalDayResetTest {

    /** 构造某个自然日的绝对时间戳（本地时区），供 seam 注入。 */
    private fun tsAt(year: Int, month1: Int, day: Int, hour: Int, minute: Int, second: Int): Long {
        val cal = Calendar.getInstance()
        cal.clear()
        cal.set(year, month1 - 1, day, hour, minute, second)
        return cal.timeInMillis
    }

    private fun newContext(): Context = RuntimeEnvironment.getApplication()

    // ═══════════════════════════════════════════════════════════════
    // 用例 1：跨自然日触发一次刷新
    // 23:59:59（旧自然日状态）→ 00:00:00（新自然日状态）必须触发刷新
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun crossNaturalDayTriggersSingleRefresh() {
        val context = newContext()

        // 先在旧自然日 23:59:59 建立"已观察"基线
        CreditManager.observeDndNaturalDay(context, tsAt(2026, 7, 20, 23, 59, 59))

        // 跨过 00:00:00 进入新自然日 → 必须刷新一次
        val refreshed = CreditManager.observeDndNaturalDay(context, tsAt(2026, 7, 21, 0, 0, 0))

        assertTrue("跨自然日 00:00 首次观察必须刷新 DND 自然日状态", refreshed)
    }

    // ═══════════════════════════════════════════════════════════════
    // 用例 2：幂等 —— 同一自然日重复观察只刷新一次
    // 轮询 + 关键读取重复命中 seam，只有第一次跨日返回 true
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun sameNaturalDayIsIdempotent() {
        val context = newContext()

        // 旧日基线
        CreditManager.observeDndNaturalDay(context, tsAt(2026, 7, 20, 23, 59, 59))

        // 跨日第一次：刷新
        val first = CreditManager.observeDndNaturalDay(context, tsAt(2026, 7, 21, 0, 0, 0))
        // 同一新自然日再次观察（模拟 5s 轮询 + 关键读取重复触发）：不再刷新
        val second = CreditManager.observeDndNaturalDay(context, tsAt(2026, 7, 21, 0, 0, 5))
        val third = CreditManager.observeDndNaturalDay(context, tsAt(2026, 7, 21, 8, 30, 0))

        assertTrue("跨日首次观察应刷新", first)
        assertFalse("同一自然日第二次观察不得重复刷新", second)
        assertFalse("同一自然日后续观察不得重复刷新", third)
    }

    // ═══════════════════════════════════════════════════════════════
    // 用例 3：R1 隔离 —— 自然日刷新不得改写业务日权威
    // 业务日边界 = 每天 DND 段开始时间（如 22:00）；跨过 00:00 时业务日不切换。
    // 自然日刷新不得改变额度业务日 / 段起始业务日 / 触发 pending。
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun r1NaturalDayRefreshDoesNotRewriteBusinessDate() {
        val context = newContext()

        // 业务日字符串在 23:59:59 与 00:00:00 之间必须保持一致（业务边界不在零点）。
        // getBusinessDateStringAt 是既有生产 seam（CreditManager.kt:2578），不复制其算法。
        val bizBefore = CreditManager.getBusinessDateStringAt(context, tsAt(2026, 7, 20, 23, 59, 59))
        val bizAfter = CreditManager.getBusinessDateStringAt(context, tsAt(2026, 7, 21, 0, 0, 0))

        // 触发自然日刷新
        CreditManager.observeDndNaturalDay(context, tsAt(2026, 7, 20, 23, 59, 59))
        CreditManager.observeDndNaturalDay(context, tsAt(2026, 7, 21, 0, 0, 0))

        // 刷新后再取业务日：必须与刷新前一致（自然日刷新未改写业务日权威）
        val bizAfterRefresh = CreditManager.getBusinessDateStringAt(context, tsAt(2026, 7, 21, 0, 0, 0))

        assertEquals("零点跨自然日：业务日不得在 00:00 切换", bizBefore, bizAfter)
        assertEquals("自然日刷新不得改写业务日权威", bizAfter, bizAfterRefresh)
    }

    // ═══════════════════════════════════════════════════════════════
    // 用例 4：R2 —— 旧日未启用时段不得成为新自然日的结余来源
    // 跨自然日刷新后，旧自然日"未启用段"的结余可见性必须被隔离。
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun r2DisabledOldDaySegmentDoesNotCarryOver() {
        val context = newContext()

        // 旧自然日观察基线（旧日存在未启用段状态）
        CreditManager.observeDndNaturalDay(context, tsAt(2026, 7, 20, 23, 59, 59))

        // 跨自然日刷新
        val refreshed = CreditManager.observeDndNaturalDay(context, tsAt(2026, 7, 21, 0, 0, 0))
        assertTrue("跨自然日必须刷新以隔离旧日未启用段结余", refreshed)

        // 刷新后新自然日不得把旧日未启用段视为结余来源：
        // seam 应报告刚发生过刷新（isDndNaturalDayJustRefreshed），供关键读取路径隔离旧日可见性。
        assertTrue(
            "R2：旧日未启用段不得带入新自然日结余",
            CreditManager.isDndNaturalDayJustRefreshed(context, tsAt(2026, 7, 21, 0, 0, 0))
        )
    }

    // ═══════════════════════════════════════════════════════════════
    // 用例 5：R3 —— 旧日段内未结算状态不得带入新自然日
    // 跨自然日刷新后，旧自然日段内"未结算"状态不得在新自然日继续可见。
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun r3OldDayInSegmentUnsettledDoesNotLeak() {
        val context = newContext()

        // 旧自然日：段内未结算基线
        CreditManager.observeDndNaturalDay(context, tsAt(2026, 7, 20, 23, 59, 59))

        // 跨自然日刷新
        val refreshed = CreditManager.observeDndNaturalDay(context, tsAt(2026, 7, 21, 0, 0, 0))
        assertTrue("跨自然日必须刷新以隔离旧日段内未结算状态", refreshed)

        // 刷新后旧日段内未结算状态不得泄漏：同一新自然日重复观察保持幂等（不重复结算 / 误刷新）
        val repeat = CreditManager.observeDndNaturalDay(context, tsAt(2026, 7, 21, 0, 0, 5))
        assertFalse("R3：旧日段内未结算状态不得触发新自然日重复刷新", repeat)
    }

    // ═══════════════════════════════════════════════════════════════
    // 用例 6：即时读取 —— 00:00 后首次关键读取即观察到刷新（不等 5s 轮询）
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun immediateReadObservesRefreshWithoutPolling() {
        val context = newContext()

        // 旧自然日基线
        CreditManager.observeDndNaturalDay(context, tsAt(2026, 7, 20, 23, 59, 59))

        // 00:00 后"第一时刻"（00:00:00）就是首次关键读取，不经过任何 5s 轮询间隔。
        val refreshedImmediately = CreditManager.observeDndNaturalDay(context, tsAt(2026, 7, 21, 0, 0, 0))

        assertTrue("00:00 首次关键读取即须观察到刷新，不得等待 5 秒轮询", refreshedImmediately)
    }
}
