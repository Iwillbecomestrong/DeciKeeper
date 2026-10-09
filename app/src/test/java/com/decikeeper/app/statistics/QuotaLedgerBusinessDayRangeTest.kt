package com.decikeeper.app.statistics

import com.decikeeper.app.data.statistics.RestUsageRecord
import com.decikeeper.app.data.statistics.StatsDateRange
import com.decikeeper.app.manager.statistics.HistoryRecordFilter
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Test

/** 额度流水属于时间规则，「今天」等范围按业务日（生效时间为界）切，而不是自然日。 */
class QuotaLedgerBusinessDayRangeTest {

    private val zone = ZoneId.of("Asia/Shanghai")
    private val eightAm = 8 * 60

    private fun millis(day: Int, hour: Int, minute: Int): Long =
        ZonedDateTime.of(2026, 10, day, hour, minute, 0, 0, zone).toInstant().toEpochMilli()

    private fun record(id: String, ts: Long) = RestUsageRecord(
        id = id, timestamp = ts, dateLabel = "", type = "REST", typeName = "常规休息"
    )

    @Test
    fun earlyMorningBelongsToPreviousBusinessDay() {
        val lastNight = record("lastNight", millis(8, 23, 30))
        val earlyMorning = record("earlyMorning", millis(9, 3, 0))     // 08:00 之前，仍是 10-08 业务日
        val afterStart = record("afterStart", millis(9, 8, 30))       // 10-09 业务日
        val all = listOf(lastNight, earlyMorning, afterStart)

        // 现在是 10-09 10:00，业务日是 10-09
        val today = HistoryRecordFilter.filterQuota(
            all, StatsDateRange.Today, millis(9, 10, 0), zone, businessDayStartMinutes = eightAm
        )
        assertEquals(listOf("afterStart"), today.map { it.id })
    }

    @Test
    fun beforeBusinessDayStartTodayStillMeansYesterdaysBusinessDay() {
        val lastNight = record("lastNight", millis(8, 23, 30))
        val afterStart = record("afterStart", millis(8, 9, 0))
        val tomorrow = record("tomorrow", millis(9, 8, 30))
        val all = listOf(lastNight, afterStart, tomorrow)

        // 现在是 10-09 03:00，业务日还是 10-08（08:00 ~ 次日 08:00）
        val today = HistoryRecordFilter.filterQuota(
            all, StatsDateRange.Today, millis(9, 3, 0), zone, businessDayStartMinutes = eightAm
        )
        assertEquals(setOf("lastNight", "afterStart"), today.map { it.id }.toSet())
    }

    @Test
    fun defaultStaysNaturalDay() {
        val early = record("early", millis(9, 3, 0))
        val all = listOf(early)

        val today = HistoryRecordFilter.filterQuota(all, StatsDateRange.Today, millis(9, 10, 0), zone)
        assertEquals(listOf("early"), today.map { it.id })
    }

    @Test
    fun yesterdayFollowsTheBusinessDay() {
        val lastNight = record("lastNight", millis(8, 23, 30))   // 10-08 业务日
        val earlyMorning = record("earlyMorning", millis(9, 3, 0)) // 仍是 10-08 业务日
        val afterStart = record("afterStart", millis(9, 8, 30))   // 10-09 业务日
        val dayBefore = record("dayBefore", millis(7, 12, 0))     // 10-07 业务日

        val result = HistoryRecordFilter.filterQuota(
            listOf(lastNight, earlyMorning, afterStart, dayBefore),
            StatsDateRange.Yesterday, millis(9, 10, 0), zone, businessDayStartMinutes = eightAm
        )
        assertEquals(setOf("lastNight", "earlyMorning"), result.map { it.id }.toSet())
    }
}
