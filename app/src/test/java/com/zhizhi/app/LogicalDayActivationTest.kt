package com.zhizhi.app

import com.zhizhi.app.data.LogicalDayClock
import com.zhizhi.app.data.TimeSegment
import java.util.Calendar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression contract for quota-period activation.
 *
 * A quota "day" is the interval between two effective/refresh boundaries.
 * Natural midnight is not a settlement boundary and must not change which
 * weekday's segments are eligible for carry-over / borrowing.
 */
class LogicalDayActivationTest {

    private fun tsAt(year: Int, month1: Int, day: Int, hour: Int, minute: Int): Long {
        return Calendar.getInstance().run {
            clear()
            set(year, month1 - 1, day, hour, minute, 0)
            timeInMillis
        }
    }

    @Test
    fun logicalWeekdayDoesNotFlipAtNaturalMidnightBeforeRefresh() {
        val refreshMinutes = 8 * 60
        val mondayBeforeMidnight = tsAt(2026, 8, 31, 23, 59)
        val tuesdayAfterMidnight = tsAt(2026, 9, 1, 0, 1)

        assertEquals(
            Calendar.MONDAY,
            LogicalDayClock.resolveDayOfWeek(mondayBeforeMidnight, refreshMinutes)
        )
        assertEquals(
            "00:00 is still inside the same logical day; weekday eligibility must not flip",
            Calendar.MONDAY,
            LogicalDayClock.resolveDayOfWeek(tuesdayAfterMidnight, refreshMinutes)
        )
    }

    @Test
    fun logicalWeekdayFlipsAtRefreshBoundaryInsteadOfMidnight() {
        val refreshMinutes = 8 * 60
        val justBeforeRefresh = tsAt(2026, 9, 1, 7, 59)
        val atRefresh = tsAt(2026, 9, 1, 8, 0)

        assertEquals(Calendar.MONDAY, LogicalDayClock.resolveDayOfWeek(justBeforeRefresh, refreshMinutes))
        assertEquals(Calendar.TUESDAY, LogicalDayClock.resolveDayOfWeek(atRefresh, refreshMinutes))
    }

    @Test
    fun segmentEligibilityStaysOnLogicalDayAcrossMidnight() {
        val refreshMinutes = 8 * 60
        val afterMidnight = tsAt(2026, 9, 1, 0, 1)
        val logicalDay = LogicalDayClock.resolveDayOfWeek(afterMidnight, refreshMinutes)

        val mondayOnly = TimeSegment(
            startHour = 0,
            startMinute = 0,
            endHour = 1,
            endMinute = 0,
            limitMins = 30,
            name = "Monday only",
            daysOfWeek = listOf(Calendar.MONDAY),
            isEnabled = true
        )
        val tuesdayOnly = mondayOnly.copy(
            name = "Tuesday only",
            daysOfWeek = listOf(Calendar.TUESDAY)
        )
        val disabled = mondayOnly.copy(
            name = "Disabled",
            isEnabled = false
        )

        assertTrue("Monday segment remains eligible until Tuesday refresh", mondayOnly.isActiveToday(logicalDay))
        assertFalse("Tuesday segment must not become eligible merely because clock crossed 00:00", tuesdayOnly.isActiveToday(logicalDay))
        assertFalse("Explicitly disabled segment never contributes quota", disabled.isActiveToday(logicalDay))
    }
}
