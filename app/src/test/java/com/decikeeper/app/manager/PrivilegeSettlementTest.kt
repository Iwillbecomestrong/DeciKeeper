package com.decikeeper.app.manager

import com.decikeeper.app.data.clampPrivilegeStopAt
import com.decikeeper.app.data.resolvePrivilegeSettlementTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PrivilegeSettlementTest {

    private val minute = 60_000L
    private val start = 10 * 60 * minute          // 10:16 起的 4 小时，简化为整点
    private val end = start + 240 * minute        // 计划结束
    private val midnight = 24 * 60 * minute

    @Test
    fun notDueBeforeEndOrMidnight() {
        assertNull(resolvePrivilegeSettlementTime(end, midnight, end - 1))
    }

    @Test
    fun settlesAtPlannedEndWhenReached() {
        assertEquals(end, resolvePrivilegeSettlementTime(end, midnight, end + 5 * minute))
    }

    @Test
    fun lateTriggerAfterMidnightStillSettlesAtPlannedEnd() {
        // 熄屏等原因拖到午夜后才触发：仍按计划结束时刻结算，不能算到 24:00
        assertEquals(end, resolvePrivilegeSettlementTime(end, midnight, midnight + 30 * minute))
    }

    @Test
    fun endAfterMidnightSettlesAtMidnight() {
        val lateEnd = midnight + 60 * minute
        assertEquals(midnight, resolvePrivilegeSettlementTime(lateEnd, midnight, midnight + 5 * minute))
    }

    @Test
    fun stopIsClampedToPlannedEnd() {
        assertEquals(end, clampPrivilegeStopAt(end + 600 * minute, start, 240))
    }

    @Test
    fun earlyStopKeepsActualTime() {
        val stop = start + 100 * minute
        assertEquals(stop, clampPrivilegeStopAt(stop, start, 240))
    }

    @Test
    fun stopNeverBeforeStartAndNoCapWithoutPlan() {
        assertEquals(start, clampPrivilegeStopAt(start - 5 * minute, start, 240))
        assertEquals(start + 999 * minute, clampPrivilegeStopAt(start + 999 * minute, start, 0))
    }
}
