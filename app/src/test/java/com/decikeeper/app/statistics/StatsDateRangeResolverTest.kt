package com.decikeeper.app.statistics

import com.decikeeper.app.data.statistics.ChartType
import com.decikeeper.app.data.statistics.StatsDateRange
import com.decikeeper.app.data.statistics.StatsGranularity
import com.decikeeper.app.data.statistics.WeekdayFilter
import com.decikeeper.app.manager.statistics.StatsDateRangeResolver
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

class StatsDateRangeResolverTest {

    private val fixedNow = LocalDateTime.of(2026, 9, 7, 12, 0, 0) // Monday
    private val zoneId = ZoneId.of("Asia/Shanghai")

    @Test
    fun testThisWeek_7points_BarChart() {
        val resolved = StatsDateRangeResolver.resolveRange(StatsDateRange.ThisWeek, fixedNow, zoneId)
        val granularity = StatsDateRangeResolver.resolveBucketGranularity(StatsDateRange.ThisWeek, resolved)
        assertEquals(StatsGranularity.DAILY, granularity)

        val buckets = StatsDateRangeResolver.buildBuckets(StatsDateRange.ThisWeek, resolved, WeekdayFilter.ALL, zoneId)
        assertEquals(7, buckets.size)
        assertEquals(ChartType.BAR, StatsDateRangeResolver.chooseChartType(buckets.size))
    }

    @Test
    fun testLastMonth_31points_LineChart() {
        // August 2026 has 31 days
        val resolved = StatsDateRangeResolver.resolveRange(StatsDateRange.LastMonth, fixedNow, zoneId)
        val granularity = StatsDateRangeResolver.resolveBucketGranularity(StatsDateRange.LastMonth, resolved)
        assertEquals(StatsGranularity.DAILY, granularity)

        val buckets = StatsDateRangeResolver.buildBuckets(StatsDateRange.LastMonth, resolved, WeekdayFilter.ALL, zoneId)
        assertEquals(31, buckets.size)
        assertEquals(ChartType.LINE, StatsDateRangeResolver.chooseChartType(buckets.size))
    }

    @Test
    fun testCustom12Days_BarChart() {
        val startMillis = LocalDate.of(2026, 8, 1).atStartOfDay(zoneId).toInstant().toEpochMilli()
        val endMillis = LocalDate.of(2026, 8, 13).atStartOfDay(zoneId).toInstant().toEpochMilli() // 12 days
        val customRange = StatsDateRange.Custom(startMillis, endMillis)

        val resolved = StatsDateRangeResolver.resolveRange(customRange, fixedNow, zoneId)
        assertEquals(12, resolved.dayCount)
        assertEquals(StatsGranularity.DAILY, StatsDateRangeResolver.resolveBucketGranularity(customRange, resolved))

        val buckets = StatsDateRangeResolver.buildBuckets(customRange, resolved, WeekdayFilter.ALL, zoneId)
        assertEquals(12, buckets.size)
        assertEquals(ChartType.BAR, StatsDateRangeResolver.chooseChartType(buckets.size))
    }

    @Test
    fun testCustom13Days_LineChart() {
        val startMillis = LocalDate.of(2026, 8, 1).atStartOfDay(zoneId).toInstant().toEpochMilli()
        val endMillis = LocalDate.of(2026, 8, 14).atStartOfDay(zoneId).toInstant().toEpochMilli() // 13 days
        val customRange = StatsDateRange.Custom(startMillis, endMillis)

        val resolved = StatsDateRangeResolver.resolveRange(customRange, fixedNow, zoneId)
        assertEquals(13, resolved.dayCount)
        assertEquals(StatsGranularity.DAILY, StatsDateRangeResolver.resolveBucketGranularity(customRange, resolved))

        val buckets = StatsDateRangeResolver.buildBuckets(customRange, resolved, WeekdayFilter.ALL, zoneId)
        assertEquals(13, buckets.size)
        assertEquals(ChartType.LINE, StatsDateRangeResolver.chooseChartType(buckets.size))
    }

    @Test
    fun testThisYear_12Months_BarChart() {
        val resolved = StatsDateRangeResolver.resolveRange(StatsDateRange.ThisYear, fixedNow, zoneId)
        val granularity = StatsDateRangeResolver.resolveBucketGranularity(StatsDateRange.ThisYear, resolved)
        assertEquals(StatsGranularity.MONTHLY, granularity)

        val buckets = StatsDateRangeResolver.buildBuckets(StatsDateRange.ThisYear, resolved, WeekdayFilter.ALL, zoneId)
        assertEquals(12, buckets.size)
        assertEquals(ChartType.BAR, StatsDateRangeResolver.chooseChartType(buckets.size))
        assertEquals("1月", buckets[0].label)
        assertEquals("12月", buckets[11].label)
    }

    @Test
    fun testWeekdayFilter_RemovesNonMatchingDays() {
        val resolved = StatsDateRangeResolver.resolveRange(StatsDateRange.ThisWeek, fixedNow, zoneId)
        val weekendFilter = WeekdayFilter(setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY))

        val buckets = StatsDateRangeResolver.buildBuckets(StatsDateRange.ThisWeek, resolved, weekendFilter, zoneId)
        assertEquals(2, buckets.size)
        assertEquals(ChartType.BAR, StatsDateRangeResolver.chooseChartType(buckets.size))
    }
}
