package com.decikeeper.app.statistics

import com.decikeeper.app.data.statistics.AlarmWakeRecord
import com.decikeeper.app.manager.statistics.HistoryDisplayRange
import com.decikeeper.app.manager.statistics.HistoryRecordFilter
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Test

/** 起床记录属于自然日口径：「今天 / 近 7 天 / 本月」按日期标签（起床那天的自然日）切，不看业务日。 */
class WakeHistoryRangeTest {

    private val zone = ZoneId.of("Asia/Shanghai")

    private fun millis(month: Int, day: Int, hour: Int, minute: Int): Long =
        ZonedDateTime.of(2026, month, day, hour, minute, 0, 0, zone).toInstant().toEpochMilli()

    private fun record(id: String, dateLabel: String, completedAt: Long) = AlarmWakeRecord(
        id = id, dateLabel = dateLabel, alarmMinuteOfDay = 405, firstStageCompletedMinute = 420,
        firstStageDurationMinutes = 15, totalFlowDurationMinutes = 15, completedAt = completedAt
    )

    private val now = millis(10, 9, 10, 0)
    private val today = record("today", "2026-10-09", millis(10, 9, 7, 0))
    private val yesterday = record("yesterday", "2026-10-08", millis(10, 8, 7, 0))
    private val sixDaysAgo = record("sixDaysAgo", "2026-10-03", millis(10, 3, 7, 0))
    private val sevenDaysAgo = record("sevenDaysAgo", "2026-10-02", millis(10, 2, 7, 0))
    private val lastMonth = record("lastMonth", "2026-09-30", millis(9, 30, 7, 0))
    private val all = listOf(lastMonth, sevenDaysAgo, sixDaysAgo, yesterday, today)

    @Test
    fun todayKeepsOnlyTodaysDateLabel() {
        val result = HistoryRecordFilter.filterWake(all, HistoryDisplayRange.TODAY, now, zone)
        assertEquals(listOf("today"), result.map { it.id })
    }

    @Test
    fun last7DaysIncludesTodayAndThe6DaysBefore() {
        val result = HistoryRecordFilter.filterWake(all, HistoryDisplayRange.LAST_7_DAYS, now, zone)
        assertEquals(listOf("today", "yesterday", "sixDaysAgo"), result.map { it.id })
    }

    @Test
    fun thisMonthStartsOnTheFirst() {
        val result = HistoryRecordFilter.filterWake(all, HistoryDisplayRange.THIS_MONTH, now, zone)
        assertEquals(listOf("today", "yesterday", "sixDaysAgo", "sevenDaysAgo"), result.map { it.id })
    }

    @Test
    fun allKeepsEverythingNewestFirst() {
        val result = HistoryRecordFilter.filterWake(all, HistoryDisplayRange.ALL, now, zone)
        assertEquals(listOf("today", "yesterday", "sixDaysAgo", "sevenDaysAgo", "lastMonth"), result.map { it.id })
    }

    @Test
    fun earlyMorningTodayIsStillTodayEvenBeforeTheBusinessDayStart() {
        // 起床按自然日：现在是 10-09 03:00（业务日仍是 10-08），今天仍是 10-09
        val early = record("early", "2026-10-09", millis(10, 9, 2, 30))
        val result = HistoryRecordFilter.filterWake(listOf(early, yesterday), HistoryDisplayRange.TODAY, millis(10, 9, 3, 0), zone)
        assertEquals(listOf("early"), result.map { it.id })
    }

    @Test
    fun anUnparsableDateLabelFallsBackToCompletedAt() {
        val odd = record("odd", "not-a-date", millis(10, 9, 6, 0))
        val result = HistoryRecordFilter.filterWake(listOf(odd, yesterday), HistoryDisplayRange.TODAY, now, zone)
        assertEquals(listOf("odd"), result.map { it.id })
    }
}
