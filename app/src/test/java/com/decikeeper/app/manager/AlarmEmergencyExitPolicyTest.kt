package com.decikeeper.app.manager

import com.decikeeper.app.manager.AlarmEmergencyExitPolicy.State
import java.util.Calendar
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 闹钟应急退出：次数配置（加严立即/放宽延期/当日可恢复基准）、自然月额度、下月生效时间。纯 JVM。 */
class AlarmEmergencyExitPolicyTest {

    private val zone = TimeZone.getTimeZone("Asia/Shanghai")

    private fun millis(y: Int, mo: Int, d: Int, h: Int = 0, mi: Int = 0): Long =
        Calendar.getInstance(zone).apply {
            clear()
            set(y, mo - 1, d, h, mi, 0)
        }.timeInMillis

    private fun effectiveAfter(now: Long, rh: Int = 8, rm: Int = 0): Long =
        AlarmEmergencyExitPolicy.nextMonthEffectiveTime(now, rh, rm, zone)

    // ---------- 范围 ----------

    @Test
    fun clampKeepsConfigWithinZeroToThree() {
        assertEquals(0, AlarmEmergencyExitPolicy.clamp(-5))
        assertEquals(2, AlarmEmergencyExitPolicy.clamp(2))
        assertEquals(3, AlarmEmergencyExitPolicy.clamp(99))
        assertEquals(1, AlarmEmergencyExitPolicy.DEFAULT_MONTHLY)
    }

    // ---------- 下一个自然月的刷新时刻 ----------

    @Test
    fun nextMonthEffectiveIsFirstOfNextMonthAtRefreshTime() {
        assertEquals(millis(2026, 11, 1, 8, 0), effectiveAfter(millis(2026, 10, 3, 10, 0)))
        assertEquals(millis(2026, 11, 1, 8, 0), effectiveAfter(millis(2026, 10, 31, 23, 0)))
    }

    @Test
    fun beforeRefreshTimeOnFirstStillBelongsToPreviousBusinessMonth() {
        // 11 月 1 日 05:00 的业务日仍是 10-31，生效时间是当天 08:00
        assertEquals(millis(2026, 11, 1, 8, 0), effectiveAfter(millis(2026, 11, 1, 5, 0)))
        // 过了刷新时刻才是 11 月，下一次是 12 月 1 日
        assertEquals(millis(2026, 12, 1, 8, 0), effectiveAfter(millis(2026, 11, 1, 9, 0)))
    }

    @Test
    fun nextMonthEffectiveFollowsCustomRefreshTimeAndYearWrap() {
        assertEquals(millis(2027, 1, 1, 4, 30), effectiveAfter(millis(2026, 12, 15, 12, 0), 4, 30))
        assertEquals(millis(2026, 11, 1, 4, 30), effectiveAfter(millis(2026, 11, 1, 3, 0), 4, 30))
    }

    // ---------- 业务月键 ----------

    @Test
    fun monthKeyIsTakenFromBusinessDate() {
        assertEquals("202610", AlarmEmergencyExitPolicy.monthKey("20261031"))
        assertEquals("202611", AlarmEmergencyExitPolicy.monthKey("20261101"))
    }

    // ---------- 首次/日界/月界 rollover ----------

    @Test
    fun firstRolloverSnapshotsBaselineAndMonthStart() {
        val s = AlarmEmergencyExitPolicy.rollover(State(), "20261003", millis(2026, 10, 3, 10))
        assertEquals("20261003", s.baselineDay)
        assertEquals(1, s.baseline)
        assertEquals("202610", s.monthKey)
        assertEquals(1, s.monthStartConfig)
        assertEquals(0, s.usedThisMonth)
    }

    @Test
    fun newBusinessDayRefreshesBaselineToActive() {
        val lowered = State(active = 1, baseline = 2, baselineDay = "20261003", monthKey = "202610", monthStartConfig = 2)
        val sameDay = AlarmEmergencyExitPolicy.rollover(lowered, "20261003", millis(2026, 10, 3, 20))
        assertEquals(2, sameDay.baseline)
        val nextDay = AlarmEmergencyExitPolicy.rollover(lowered, "20261004", millis(2026, 10, 4, 9))
        assertEquals(1, nextDay.baseline)
        assertEquals("20261004", nextDay.baselineDay)
    }

    @Test
    fun dueNextMonthPendingBecomesActiveAndMonthStartAtBoundary() {
        val effectiveAt = millis(2026, 11, 1, 8, 0)
        val s = State(
            active = 1, baseline = 1, baselineDay = "20261031", pendingValue = 3, pendingEffectiveAt = effectiveAt,
            monthKey = "202610", monthStartConfig = 1, usedThisMonth = 1
        )
        val before = AlarmEmergencyExitPolicy.rollover(s, "20261031", effectiveAt - 1)
        assertEquals(1, before.active)
        assertEquals(3, before.pendingValue)

        val after = AlarmEmergencyExitPolicy.rollover(s, "20261101", effectiveAt)
        assertEquals(3, after.active)
        assertNull(after.pendingValue)
        assertEquals(3, after.baseline)
        assertEquals("202611", after.monthKey)
        assertEquals(3, after.monthStartConfig)
        assertEquals(0, after.usedThisMonth)
    }

    // ---------- 加严立即、放宽延期、当日可恢复 ----------

    private val stable = State(
        active = 2, baseline = 2, baselineDay = "20261003", monthKey = "202610", monthStartConfig = 2
    )
    private val now = millis(2026, 10, 3, 10)
    private val nextMonth = millis(2026, 11, 1, 8)

    private fun request(s: State, v: Int, adaptation: Boolean = false) =
        AlarmEmergencyExitPolicy.requestChange(s, v, now, adaptation, nextMonth)

    @Test
    fun loweringAppliesImmediately() {
        val s = request(stable, 1)
        assertEquals(1, s.active)
        assertNull(s.pendingValue)
        assertEquals(2, s.baseline)
        assertEquals(0, request(stable, 0).active)
    }

    @Test
    fun restoringBaselineSameDayAppliesImmediately() {
        val lowered = request(stable, 1)
        val restored = request(lowered, 2)
        assertEquals(2, restored.active)
        assertNull(restored.pendingValue)
    }

    @Test
    fun restoringAfterBaselineRefreshIsRelaxationAndGoesPending() {
        val lowered = request(stable, 1)
        val nextDay = AlarmEmergencyExitPolicy.rollover(lowered, "20261004", millis(2026, 10, 4, 9))
        val raised = AlarmEmergencyExitPolicy.requestChange(nextDay, 2, millis(2026, 10, 4, 9), false, nextMonth)
        assertEquals(1, raised.active)
        assertEquals(2, raised.pendingValue)
        assertEquals(nextMonth, raised.pendingEffectiveAt)
    }

    @Test
    fun raisingAboveBaselineGoesPendingAndKeepsActive() {
        val s = request(stable, 3)
        assertEquals(2, s.active)
        assertEquals(3, s.pendingValue)
        assertEquals(nextMonth, s.pendingEffectiveAt)
    }

    @Test
    fun repeatedSamePendingKeepsOriginalEffectiveTime() {
        val first = request(stable, 3)
        val later = AlarmEmergencyExitPolicy.requestChange(first, 3, millis(2026, 10, 20), false, millis(2026, 11, 1, 9))
        assertEquals(nextMonth, later.pendingEffectiveAt)
    }

    @Test
    fun requestingBaselineOrLowerCancelsPending() {
        val pending = request(stable, 3)
        assertNull(request(pending, 2).pendingValue)
        assertNull(request(pending, 1).pendingValue)
    }

    @Test
    fun adaptationPeriodAppliesRelaxationImmediately() {
        val s = request(stable, 3, adaptation = true)
        assertEquals(3, s.active)
        assertEquals(3, s.baseline)
        assertNull(s.pendingValue)
    }

    @Test
    fun requestIsClampedToRange() {
        assertEquals(0, request(stable, -1).active)
        assertEquals(3, request(stable, 99).pendingValue)
    }

    // ---------- 当月额度 ----------

    @Test
    fun remainingIsMinOfActiveAndMonthStartMinusUsed() {
        assertEquals(2, AlarmEmergencyExitPolicy.remaining(State(active = 2, monthStartConfig = 2)))
        assertEquals(1, AlarmEmergencyExitPolicy.remaining(State(active = 1, monthStartConfig = 2)))
        assertEquals(0, AlarmEmergencyExitPolicy.remaining(State(active = 2, monthStartConfig = 1, usedThisMonth = 1)))
        assertEquals(0, AlarmEmergencyExitPolicy.remaining(State(active = 0, monthStartConfig = 2)))
        assertEquals(0, AlarmEmergencyExitPolicy.remaining(State(active = 1, monthStartConfig = 1, usedThisMonth = 5)))
    }

    @Test
    fun loweringThenRestoringDoesNotChangeMonthQuotaOrUsed() {
        val used = AlarmEmergencyExitPolicy.consume(stable)!!
        val lowered = request(used, 1)
        assertEquals(0, AlarmEmergencyExitPolicy.remaining(lowered))
        val restored = request(lowered, 2)
        assertEquals(1, AlarmEmergencyExitPolicy.remaining(restored))
        assertEquals(1, restored.usedThisMonth)
    }

    @Test
    fun raisingMidMonthDoesNotIncreaseThisMonthQuota() {
        val pending = request(stable, 3)
        assertEquals(2, AlarmEmergencyExitPolicy.remaining(pending))
    }

    @Test
    fun consumeDecrementsUntilExhaustedThenReturnsNull() {
        val one = State(active = 1, monthStartConfig = 1)
        val used = AlarmEmergencyExitPolicy.consume(one)!!
        assertEquals(1, used.usedThisMonth)
        assertEquals(0, AlarmEmergencyExitPolicy.remaining(used))
        assertNull(AlarmEmergencyExitPolicy.consume(used))
    }

    @Test
    fun consumeIsRefusedWhenConfiguredZero() {
        assertNull(AlarmEmergencyExitPolicy.consume(State(active = 0, monthStartConfig = 1)))
        assertTrue(AlarmEmergencyExitPolicy.remaining(State(active = 0, monthStartConfig = 1)) == 0)
    }
}
