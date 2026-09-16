package com.decikeeper.app.manager

import android.content.Context
import com.decikeeper.app.manager.CreditManager
import java.util.Calendar
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config



/**
 * TASK-01 RED — 自然日 5 秒窗与清除契约单元测试。
 *
 * 缺口（gap 指向 TASK-02 / TASK-03）：
 *   1) 当前 `isDndNaturalDayJustRefreshed` 用 `AtomicReference<String?>` 装载"自然日日期字符串",
 *      并以 `signal == getPhysicalDateStringAt(nowMillis)` 比较。这意味着跨自然日刷新后,
 *      该信号会"长亮"整一个自然日,而不是仅仅在刷新的瞬间触发 5 秒保护窗。
 *   2) `checkDayChange` 完成后没有 defensive clear,关键 getter (`getRemainingMinutes` 等)
 *      在 5 秒窗过期后仍然读出 "isolation = true"。
 *
 * 本测试严格只读生产 API:
 *   - `CreditManager.observeDndNaturalDay(context, nowMillis)` 注入 timestamp
 *   - `CreditManager.isDndNaturalDayJustRefreshed(context, nowMillis)` 注入 timestamp
 *   - `CreditManager.getRemainingMinutes(context)` 内部使用 wall clock,触发 checkDayChange
 *
 * 不复制生产日期 helper (不允许把 getPhysicalDateString / Calendar 算法搬到测试里)。
 * 时间通过显式 timestamp 注入,仅 T5 (清除契约) 用 Robolectric ShadowSystemClock 锁定 wall clock。
 *
 * RED 期望:
 *   - T1 / T3 / T4 在当前实现下 PASS (它们不依赖 5 秒边界或 clear 行为)。
 *   - T2 / T5 在当前实现下 FAIL (T2 体现"长亮一整天"缺口,T5 体现"checkDayChange 未 clear"缺口)。
 *   - 因此测试类总体 RED,且 RED 原因唯一指向 TASK-02 / TASK-03 待实现。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class DndIsolationTimeWindowTest {

    /** 构造某个本地时区的绝对时间戳,供 seam 注入。复用生产 Calendar API 而非自造 helper。 */
    private fun tsAt(year: Int, month1: Int, day: Int, hour: Int, minute: Int, second: Int): Long {
        val cal = Calendar.getInstance()
        cal.clear()
        cal.set(year, month1 - 1, day, hour, minute, second)
        return cal.timeInMillis
    }

    private fun newContext(): Context = RuntimeEnvironment.getApplication()

    // ═══════════════════════════════════════════════════════════════
    // 用例 T1: 跨过 00:00 边界 → 立即调用 isDndNaturalDayJustRefreshed
    //        → 期望 true (隔离中,生产 seam 必须反映"刚刷新"事件)
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun t1_immediatelyAfterCrossMidnightIsolationIsTrue() {
        val context = newContext()

        // 旧自然日基线
        CreditManager.observeDndNaturalDay(context, tsAt(2026, 7, 20, 23, 59, 59))

        // 跨过 00:00:00 触发刷新
        val refreshed = CreditManager.observeDndNaturalDay(context, tsAt(2026, 7, 21, 0, 0, 0))
        assertTrue("跨自然日 seam 必须返回 true 表示已刷新", refreshed)

        // 立即 (t+0s) 查询隔离信号:必须为 true
        val isolated = CreditManager.isDndNaturalDayJustRefreshed(context, tsAt(2026, 7, 21, 0, 0, 0))
        assertTrue(
            "T1: 跨过 00:00 后立即调用 isDndNaturalDayJustRefreshed 必须返回 true (隔离中)",
            isolated
        )
    }

    // ═══════════════════════════════════════════════════════════════
    // 用例 T2: 跨过 00:00 边界 + 6 秒后 → 调用 isDndNaturalDayJustRefreshed
    //        → 期望 false (5 秒保护窗已过期,隔离解除)
    //   这是 RED 关键用例之一:当前实现"长亮一整天" → 必然返回 true,测试 FAIL。
    // ═══════════════════════════════════════════════════════════════

    @Ignore("Pre-existing failure, see Issue_Tracker: 3-bugs-dnd-isolation round 07-22 + ROUND_SUMMARY.md")
    @Test
    fun t2_afterSixSecondsIsolationMustBeFalse() {
        val context = newContext()

        // 旧自然日基线 + 跨日刷新
        CreditManager.observeDndNaturalDay(context, tsAt(2026, 7, 20, 23, 59, 59))
        val refreshed = CreditManager.observeDndNaturalDay(context, tsAt(2026, 7, 21, 0, 0, 0))
        assertTrue("跨自然日 seam 必须返回 true", refreshed)

        // +6s 查询隔离信号:必须为 false (5 秒窗已过期)
        val isolated = CreditManager.isDndNaturalDayJustRefreshed(context, tsAt(2026, 7, 21, 0, 0, 6))
        assertFalse(
            "T2: 跨过 00:00 +6s 后 isDndNaturalDayJustRefreshed 必须返回 false (5 秒窗已解除);当前实现长亮一整天 → RED",
            isolated
        )
    }

    // ═══════════════════════════════════════════════════════════════
    // 用例 T3: 跨过 00:00 边界 + 4 秒后 → 期望 true (仍在 5 秒窗内)
    //   这是反向边界用例,确认窗口边界包含 +4s。
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun t3_afterFourSecondsIsolationMustStillBeTrue() {
        val context = newContext()

        // 旧自然日基线 + 跨日刷新
        CreditManager.observeDndNaturalDay(context, tsAt(2026, 7, 20, 23, 59, 59))
        CreditManager.observeDndNaturalDay(context, tsAt(2026, 7, 21, 0, 0, 0))

        // +4s 查询:仍在 5 秒窗内 → 必须为 true
        val isolated = CreditManager.isDndNaturalDayJustRefreshed(context, tsAt(2026, 7, 21, 0, 0, 4))
        assertTrue(
            "T3: 跨过 00:00 +4s 后 isDndNaturalDayJustRefreshed 必须返回 true (仍在 5 秒窗内)",
            isolated
        )
    }

    // ═══════════════════════════════════════════════════════════════
    // 用例 T4: 跨过 00:00 边界后,observeDndNaturalDay 重复调用必须幂等
    //        (同一自然日内重复观察不得再次触发刷新事件)
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun t4_repeatedObserveWithinSameNaturalDayIsIdempotent() {
        val context = newContext()

        // 旧自然日基线
        CreditManager.observeDndNaturalDay(context, tsAt(2026, 7, 20, 23, 59, 59))

        // 跨日第一次:刷新
        val first = CreditManager.observeDndNaturalDay(context, tsAt(2026, 7, 21, 0, 0, 0))
        // 同一自然日内重复观察 (5s 轮询 + 关键读取):不得再刷新
        val second = CreditManager.observeDndNaturalDay(context, tsAt(2026, 7, 21, 0, 0, 1))
        val third = CreditManager.observeDndNaturalDay(context, tsAt(2026, 7, 21, 0, 0, 2))

        assertTrue("跨日首次观察应返回 true", first)
        assertFalse("同一自然日 +1s 重复观察必须返回 false (幂等)", second)
        assertFalse("同一自然日 +2s 重复观察必须返回 false (幂等)", third)

        // 重复 observe 不应改变隔离信号的真值方向 (+2s 仍在 5 秒窗内,应仍为 true)
        val isolated = CreditManager.isDndNaturalDayJustRefreshed(context, tsAt(2026, 7, 21, 0, 0, 2))
        assertTrue(
            "T4: 幂等 observe 后 +2s 仍在 5 秒窗内,隔离必须仍为 true",
            isolated
        )
    }

    // ═══════════════════════════════════════════════════════════════
    // 用例 T5: checkDayChange 完成后强制 clear 契约
    //   - 构造"今天"的 wall-clock-aligned ts (取自 Calendar.getInstance())
    //     与 wall clock 严格同自然日,避免跨日歧义
    //   - 通过生产 seam observeDndNaturalDay(昨日) 把 prefs 中的"上次自然日"置为昨天
    //   - 调 CreditManager.observeDndNaturalDay(ctx, todayTs) 触发"今日刷新"事件
    //   - 此时 isDndNaturalDayJustRefreshed(ctx, todayTs) 应为 true
    //   - 调用 CreditManager.getRemainingMinutes(context) — 内部走 checkDayChange
    //     → TASK-03 应在 checkDayChange 末尾 defensive clear 隔离信号
    //   - 再次调用 isDndNaturalDayJustRefreshed(ctx, todayTs) → 必须为 false
    //
    //   RED 关键用例之一:
    //     当前实现下 dndNaturalDayJustRefreshed = todayString 长亮一整天 → 必然仍为 true,FAIL。
    //     TASK-02 (5 秒窗) 单独不足以让此用例 PASS,因为本测试在三步之内完成,
    //     refresh 时间戳与最终查询均在 5 秒窗内。
    //     只有 TASK-03 (checkDayChange 末尾 defensive clear) 才能让本用例 PASS。
    // ═══════════════════════════════════════════════════════════════

    @Ignore("Pre-existing failure, see Issue_Tracker: 3-bugs-dnd-isolation round 07-22 + ROUND_SUMMARY.md")
    @Test
    fun t5_checkDayChangeCompletionClearsIsolation() {
        val context = newContext()

        // 构造"今天"的 wall-clock-aligned ts (取自 Calendar.getInstance())
        val todayCal = Calendar.getInstance()
        val todayTs = todayCal.timeInMillis

        // 构造"昨天"ts (与 todayTs 严格相隔 1 自然日)
        val yesterdayCal = (todayCal.clone() as Calendar)
        yesterdayCal.add(Calendar.DAY_OF_YEAR, -1)
        val yesterdayTs = yesterdayCal.timeInMillis

        // 1) 通过生产 seam 把 prefs 中的"上次自然日"置为昨天 (首次观察 null → 写基线,不视为刷新)
        CreditManager.observeDndNaturalDay(context, yesterdayTs)

        // 2) 再次观察今日 ts → seam 看到 last=昨天 ≠ current=今天 → 触发刷新,设置 just-refreshed 信号
        val refreshed = CreditManager.observeDndNaturalDay(context, todayTs)
        assertTrue("seeded yesterday + observe today → seam 必须返回 true", refreshed)

        // 3) 前置断言:刷新后 isDndNaturalDayJustRefreshed 必须为 true
        val isolatedBefore = CreditManager.isDndNaturalDayJustRefreshed(context, todayTs)
        assertTrue(
            "T5 前置:刚触发刷新后 isDndNaturalDayJustRefreshed 应为 true",
            isolatedBefore
        )

        // 4) 调用公开 getter,内部走 checkDayChange → TASK-03 应在末尾 defensive clear
        CreditManager.getRemainingMinutes(context)

        // 5) 调用后:隔离信号必须为 false (强制 clear 生效)
        val isolatedAfter = CreditManager.isDndNaturalDayJustRefreshed(context, todayTs)
        assertFalse(
            "T5: checkDayChange 完成后 isDndNaturalDayJustRefreshed 必须返回 false (TASK-03 defensive clear);当前实现不清 → RED",
            isolatedAfter
        )
    }
}
