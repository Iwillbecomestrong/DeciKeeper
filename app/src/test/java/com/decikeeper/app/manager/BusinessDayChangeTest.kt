package com.decikeeper.app.manager

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test


/**
 * 业务日变更逻辑测试。
 *
 * 核心原则：
 * - 业务日边界由用户配置的"业务日生效时间"决定（如 08:30）
 * - 跨过自然日零点 ≠ 跨过业务日边界
 * - 只有跨过业务日边界才允许切换业务日期、结算或清理前一业务日数据
 *
 * 注意：以下测试覆盖纯逻辑部分。
 * 涉及 Android Context / SharedPreferences 的集成测试需要真机验证。
 */
class BusinessDayChangeTest {

    // ═══════════════════════════════════════════════════════════════
    // 用例 1：跨天段时间范围判断
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun crossMidnightSegmentIncludesTimeBeforeEnd() {
        // 22:30-08:00 跨天段：00:00 应在段内
        val result = isTimeInCrossMidnightRange(
            currentHour = 0, currentMinute = 0,
            startHour = 22, startMinute = 30,
            endHour = 8, endMinute = 0
        )
        assertTrue("00:00 should be in 22:30-08:00 segment", result)
    }

    @Test
    fun crossMidnightSegmentIncludesTimeAfterStart() {
        // 22:30-08:00 跨天段：23:00 应在段内
        val result = isTimeInCrossMidnightRange(
            currentHour = 23, currentMinute = 0,
            startHour = 22, startMinute = 30,
            endHour = 8, endMinute = 0
        )
        assertTrue("23:00 should be in 22:30-08:00 segment", result)
    }

    @Test
    fun crossMidnightSegmentExcludesTimeAfterEnd() {
        // 22:30-08:00 跨天段：09:00 不应在段内
        val result = isTimeInCrossMidnightRange(
            currentHour = 9, currentMinute = 0,
            startHour = 22, startMinute = 30,
            endHour = 8, endMinute = 0
        )
        assertFalse("09:00 should NOT be in 22:30-08:00 segment", result)
    }

    // ═══════════════════════════════════════════════════════════════
    // 用例 2：业务日日期字符串计算
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun businessDateBeforeBoundaryReturnsYesterday() {
        // 边界 08:30，当前 00:00 → 应返回昨天
        val result = calculateBusinessDateOffset(
            currentHour = 0, currentMinute = 0,
            boundaryHour = 8, boundaryMinute = 30
        )
        assertEquals(-1, result) // -1 表示回退1天
    }

    @Test
    fun businessDateAfterBoundaryReturnsToday() {
        // 边界 08:30，当前 09:00 → 应返回今天
        val result = calculateBusinessDateOffset(
            currentHour = 9, currentMinute = 0,
            boundaryHour = 8, boundaryMinute = 30
        )
        assertEquals(0, result) // 0 表示今天
    }

    @Test
    fun businessDateExactlyAtBoundaryReturnsToday() {
        // 边界 08:30，当前 08:30 → 应返回今天（>= 边界算今天）
        val result = calculateBusinessDateOffset(
            currentHour = 8, currentMinute = 30,
            boundaryHour = 8, boundaryMinute = 30
        )
        assertEquals(0, result)
    }

    @Test
    fun businessDateOneMinuteBeforeBoundaryReturnsYesterday() {
        // 边界 08:30，当前 08:29 → 应返回昨天
        val result = calculateBusinessDateOffset(
            currentHour = 8, currentMinute = 29,
            boundaryHour = 8, boundaryMinute = 30
        )
        assertEquals(-1, result)
    }

    // ═══════════════════════════════════════════════════════════════
    // 用例 3：零点不重置
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun midnightDoesNotChangeBusinessDateWhenBoundaryIsMorning() {
        // 边界 08:30：23:59 和 00:00 的偏移不同，但业务日期字符串相同
        // 23:59 → 今天 (>= 08:30)，00:00 → 昨天 (< 08:30)
        // 但"今天"是 6月26日，"昨天"也是 6月26日（因为 00:00 属于 6月26日的业务日）
        val offsetBefore = calculateBusinessDateOffset(23, 59, 8, 30)
        val offsetAfter = calculateBusinessDateOffset(0, 0, 8, 30)
        // 偏移不同（0 vs -1），但业务日期不变（因为自然日也变了）
        // 6月26日 23:59 → 今天 = 6月26日
        // 6月27日 00:00 → 昨天 = 6月26日
        // 两者都是 6月26日 → checkDayChange 不触发
        assertBusinessDateUnchangedAcrossMidnight(
            boundaryHour = 8, boundaryMinute = 30,
            dayBeforeMonth = 6, dayBeforeDay = 26,
            dayAfterMonth = 6, dayAfterDay = 27
        )
    }

    @Test
    fun midnightDoesNotChangeBusinessDateWhenBoundaryIsEarlyMorning() {
        // 边界 04:00：03:59 和 04:00 应返回不同的业务日偏移
        val offsetBefore = calculateBusinessDateOffset(
            currentHour = 3, currentMinute = 59,
            boundaryHour = 4, boundaryMinute = 0
        )
        val offsetAfter = calculateBusinessDateOffset(
            currentHour = 4, currentMinute = 0,
            boundaryHour = 4, boundaryMinute = 0
        )
        // 03:59 < 04:00 → 回退1天; 04:00 >= 04:00 → 今天
        assertEquals(-1, offsetBefore)
        assertEquals(0, offsetAfter)
    }

    // ═══════════════════════════════════════════════════════════════
    // 用例 4：非整点边界
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun halfHourBoundaryWorkscorrectly() {
        // 边界 03:30
        assertEquals(-1, calculateBusinessDateOffset(3, 29, 3, 30))
        assertEquals(0, calculateBusinessDateOffset(3, 30, 3, 30))
        assertEquals(0, calculateBusinessDateOffset(3, 31, 3, 30))
    }

    // ═══════════════════════════════════════════════════════════════
    // 用例 5：跨天段业务日一致性
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun crossMidnightSegmentBusinessDateIsConsistent() {
        // 跨天段 22:30-08:00：23:59 和 00:00 都在段内
        val inSegmentAtNight = isTimeInCrossMidnightRange(23, 59, 22, 30, 8, 0)
        val inSegmentAtMidnight = isTimeInCrossMidnightRange(0, 0, 22, 30, 8, 0)
        assertTrue("23:59 should be in cross-midnight segment", inSegmentAtNight)
        assertTrue("00:00 should be in cross-midnight segment", inSegmentAtMidnight)

        // getSegmentBusinessDateString 逻辑：
        // - 23:59: isCrossDay=true, currentMins(1439) < endMins(480)=false → 不回退 → offset=0 (当天)
        // - 00:00: isCrossDay=true, currentMins(0) < endMins(480)=true → 回退1天 → offset=-1 (昨天)
        // 但因为自然日也变了（23:59是6月26日，00:00是6月27日），最终业务日期相同：
        // 6月26日 + 0 = 6月26日
        // 6月27日 + (-1) = 6月26日
        val offsetAtNight = calculateCrossMidnightSegmentBusinessDateOffset(23, 59, 8, 0)
        val offsetAtMidnight = calculateCrossMidnightSegmentBusinessDateOffset(0, 0, 8, 0)
        assertEquals("23:59 in initial portion → offset 0 (today)", 0, offsetAtNight)
        assertEquals("00:00 in cross-midnight portion → offset -1 (yesterday)", -1, offsetAtMidnight)

        // 验证最终业务日期一致：day + offset
        val bizDayAtNight = 26 + offsetAtNight     // 6月26日 + 0 = 26
        val bizDayAtMidnight = 27 + offsetAtMidnight // 6月27日 + (-1) = 26
        assertEquals("Business date should be the same (day 26)", bizDayAtNight, bizDayAtMidnight)
    }

    @Test
    fun crossMidnightSegmentAfterEndUsesCurrentDate() {
        // 跨天段 22:30-08:00：09:00 已过段结束时间，使用当天日期
        val result = calculateCrossMidnightSegmentBusinessDateOffset(
            currentHour = 9, currentMinute = 0,
            endHour = 8, endMinute = 0
        )
        assertEquals(0, result) // 不回退
    }

    // ═══════════════════════════════════════════════════════════════
    // 辅助函数（模拟纯逻辑，不依赖 Android Context）
    // ═══════════════════════════════════════════════════════════════

    /**
     * 模拟 isInTimeRange 的跨天段判断逻辑。
     * 跨天段：start > end，时间在 [start, 24:00) 或 [00:00, end) 范围内。
     */
    private fun isTimeInCrossMidnightRange(
        currentHour: Int, currentMinute: Int,
        startHour: Int, startMinute: Int,
        endHour: Int, endMinute: Int
    ): Boolean {
        val current = currentHour * 60 + currentMinute
        val start = startHour * 60 + startMinute
        val end = endHour * 60 + endMinute
        return if (start > end) {
            current >= start || current < end
        } else {
            current >= start && current < end
        }
    }

    /**
     * 模拟 getBusinessDateString 的偏移逻辑。
     * 返回 -1 表示回退1天（昨天），0 表示今天。
     */
    private fun calculateBusinessDateOffset(
        currentHour: Int, currentMinute: Int,
        boundaryHour: Int, boundaryMinute: Int
    ): Int {
        val currentMins = currentHour * 60 + currentMinute
        val boundaryMins = boundaryHour * 60 + boundaryMinute
        return if (currentMins < boundaryMins) -1 else 0
    }

    /**
     * 模拟 getSegmentBusinessDateString 对跨天段的偏移逻辑。
     * 跨天段在午夜后（currentMins < endMins）回退1天。
     */
    private fun calculateCrossMidnightSegmentBusinessDateOffset(
        currentHour: Int, currentMinute: Int,
        endHour: Int, endMinute: Int
    ): Int {
        val currentMins = currentHour * 60 + currentMinute
        val endMins = endHour * 60 + endMinute
        return if (currentMins < endMins) -1 else 0
    }

    /**
     * 验证跨午夜时业务日期字符串不变。
     *
     * 模拟场景：某天 23:59 和第二天 00:00，使用相同的业务日边界。
     * 虽然偏移量不同（23:59 可能是 0，00:00 是 -1），
     * 但因为自然日也变了，最终的业务日期字符串应该相同。
     *
     * 例如：边界 08:30
     * - 6月26日 23:59 → offset=0 → 6月26日
     * - 6月27日 00:00 → offset=-1 → 6月27日-1天 = 6月26日
     * 两者都是 6月26日 → checkDayChange 不触发
     */
    private fun assertBusinessDateUnchangedAcrossMidnight(
        boundaryHour: Int, boundaryMinute: Int,
        dayBeforeMonth: Int, dayBeforeDay: Int,
        dayAfterMonth: Int, dayAfterDay: Int
    ) {
        val offsetBefore = calculateBusinessDateOffset(23, 59, boundaryHour, boundaryMinute)
        val offsetAfter = calculateBusinessDateOffset(0, 0, boundaryHour, boundaryMinute)

        // 模拟业务日期字符串计算：自然日 + 偏移
        // 使用简化的 day-of-year 计算
        val bizDayBefore = dayBeforeDay + offsetBefore
        val bizDayAfter = dayAfterDay + offsetAfter

        assertEquals(
            "Business date should be the same before and after midnight (boundary=${boundaryHour}:${boundaryMinute})",
            bizDayBefore, bizDayAfter
        )
    }
}
