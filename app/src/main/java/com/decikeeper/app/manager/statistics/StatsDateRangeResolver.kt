package com.decikeeper.app.manager.statistics

import com.decikeeper.app.data.statistics.ChartType
import com.decikeeper.app.data.statistics.ResolvedDateRange
import com.decikeeper.app.data.statistics.StatsBucket
import com.decikeeper.app.data.statistics.StatsDateRange
import com.decikeeper.app.data.statistics.StatsGranularity
import com.decikeeper.app.data.statistics.WeekdayFilter
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

/**
 * 时间范围解析器与分桶引擎
 * 职责：
 * 1. 将 StatsDateRange 转换为绝对起止毫秒与名称；
 * 2. 依据天数跨度与预设规则推导聚合粒度（小时/天/周/月）；
 * 3. 按照粒度与星期过滤器切分出离散的 StatsBucket 列表；
 * 4. 依据数据点数量判定图表类型（<=12 柱状，>12 折线）。
 */
object StatsDateRangeResolver {

    private val DEFAULT_ZONE_ID = ZoneId.systemDefault()
    private val SHORT_DATE_FORMAT = DateTimeFormatter.ofPattern("MM/dd")

    fun resolveRange(
        range: StatsDateRange,
        now: LocalDateTime = LocalDateTime.now(),
        zoneId: ZoneId = DEFAULT_ZONE_ID
    ): ResolvedDateRange {
        val today = now.toLocalDate()
        val startDateTime: LocalDateTime
        val endDateTimeExclusive: LocalDateTime
        val name: String

        when (range) {
            StatsDateRange.Today -> {
                startDateTime = today.atStartOfDay()
                endDateTimeExclusive = today.plusDays(1).atStartOfDay()
                name = "今天"
            }
            StatsDateRange.Yesterday -> {
                val yesterday = today.minusDays(1)
                startDateTime = yesterday.atStartOfDay()
                endDateTimeExclusive = today.atStartOfDay()
                name = "昨天"
            }
            StatsDateRange.ThisWeek -> {
                val monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                startDateTime = monday.atStartOfDay()
                endDateTimeExclusive = monday.plusWeeks(1).atStartOfDay()
                name = "本周"
            }
            StatsDateRange.LastWeek -> {
                val thisMonday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                val lastMonday = thisMonday.minusWeeks(1)
                startDateTime = lastMonday.atStartOfDay()
                endDateTimeExclusive = thisMonday.atStartOfDay()
                name = "上周"
            }
            StatsDateRange.Last7Days -> {
                startDateTime = today.minusDays(6).atStartOfDay()
                endDateTimeExclusive = today.plusDays(1).atStartOfDay()
                name = "近 7 天"
            }
            StatsDateRange.ThisMonth -> {
                val firstDay = today.with(TemporalAdjusters.firstDayOfMonth())
                startDateTime = firstDay.atStartOfDay()
                endDateTimeExclusive = firstDay.plusMonths(1).atStartOfDay()
                name = "本月"
            }
            StatsDateRange.LastMonth -> {
                val firstDayThisMonth = today.with(TemporalAdjusters.firstDayOfMonth())
                val firstDayLastMonth = firstDayThisMonth.minusMonths(1)
                startDateTime = firstDayLastMonth.atStartOfDay()
                endDateTimeExclusive = firstDayThisMonth.atStartOfDay()
                name = "上月"
            }
            StatsDateRange.Last30Days -> {
                startDateTime = today.minusDays(29).atStartOfDay()
                endDateTimeExclusive = today.plusDays(1).atStartOfDay()
                name = "近 30 天"
            }
            StatsDateRange.ThisYear -> {
                val firstDay = today.with(TemporalAdjusters.firstDayOfYear())
                startDateTime = firstDay.atStartOfDay()
                endDateTimeExclusive = firstDay.plusYears(1).atStartOfDay()
                name = "本年"
            }
            is StatsDateRange.Custom -> {
                val startInstant = Instant.ofEpochMilli(range.startMillis)
                val endInstant = Instant.ofEpochMilli(range.endMillisExclusive)
                startDateTime = LocalDateTime.ofInstant(startInstant, zoneId)
                endDateTimeExclusive = LocalDateTime.ofInstant(endInstant, zoneId)
                name = "自定义"
            }
        }

        val startMillis = startDateTime.atZone(zoneId).toInstant().toEpochMilli()
        val endMillis = endDateTimeExclusive.atZone(zoneId).toInstant().toEpochMilli()
        val dayCount = (ChronoUnit.DAYS.between(startDateTime.toLocalDate(), endDateTimeExclusive.toLocalDate())).toInt().coerceAtLeast(1)

        val dateSubText = if (startDateTime.toLocalDate() == endDateTimeExclusive.minusDays(1).toLocalDate()) {
            startDateTime.format(SHORT_DATE_FORMAT)
        } else {
            "${startDateTime.format(SHORT_DATE_FORMAT)} – ${endDateTimeExclusive.minusDays(1).format(SHORT_DATE_FORMAT)}"
        }

        return ResolvedDateRange(
            startMillis = startMillis,
            endMillisExclusive = endMillis,
            dayCount = dayCount,
            displayName = name,
            dateSubText = dateSubText
        )
    }

    /**
     * 自动推导聚合粒度
     */
    fun resolveBucketGranularity(
        range: StatsDateRange,
        resolvedRange: ResolvedDateRange
    ): StatsGranularity {
        return when (range) {
            StatsDateRange.Today, StatsDateRange.Yesterday -> StatsGranularity.HOURLY
            StatsDateRange.ThisWeek, StatsDateRange.LastWeek, StatsDateRange.Last7Days -> StatsGranularity.DAILY
            StatsDateRange.ThisMonth, StatsDateRange.LastMonth, StatsDateRange.Last30Days -> StatsGranularity.DAILY
            StatsDateRange.ThisYear -> StatsGranularity.MONTHLY
            is StatsDateRange.Custom -> {
                when {
                    resolvedRange.dayCount <= 1 -> StatsGranularity.HOURLY
                    resolvedRange.dayCount <= 45 -> StatsGranularity.DAILY
                    resolvedRange.dayCount <= 180 -> StatsGranularity.WEEKLY
                    else -> StatsGranularity.MONTHLY
                }
            }
        }
    }

    /**
     * 生成并过滤分桶
     */
    fun buildBuckets(
        range: StatsDateRange,
        resolvedRange: ResolvedDateRange,
        weekdayFilter: WeekdayFilter = WeekdayFilter.ALL,
        zoneId: ZoneId = DEFAULT_ZONE_ID
    ): List<StatsBucket> {
        val granularity = resolveBucketGranularity(range, resolvedRange)
        val startDateTime = LocalDateTime.ofInstant(Instant.ofEpochMilli(resolvedRange.startMillis), zoneId)
        val endDateTime = LocalDateTime.ofInstant(Instant.ofEpochMilli(resolvedRange.endMillisExclusive), zoneId)

        val buckets = mutableListOf<StatsBucket>()

        when (granularity) {
            StatsGranularity.HOURLY -> {
                var cursor = startDateTime
                while (cursor.isBefore(endDateTime)) {
                    val next = cursor.plusHours(1)
                    val sM = cursor.atZone(zoneId).toInstant().toEpochMilli()
                    val eM = next.atZone(zoneId).toInstant().toEpochMilli()
                    val label = String.format("%02d:00", cursor.hour)
                    buckets.add(StatsBucket(sM, eM, label))
                    cursor = next
                }
            }

            StatsGranularity.DAILY -> {
                var cursor = startDateTime.toLocalDate()
                val endDate = endDateTime.toLocalDate()
                while (cursor.isBefore(endDate)) {
                    if (weekdayFilter.isEnabled(cursor.dayOfWeek)) {
                        val sM = cursor.atStartOfDay(zoneId).toInstant().toEpochMilli()
                        val eM = cursor.plusDays(1).atStartOfDay(zoneId).toInstant().toEpochMilli()
                        val label = cursor.format(SHORT_DATE_FORMAT)
                        buckets.add(StatsBucket(sM, eM, label))
                    }
                    cursor = cursor.plusDays(1)
                }
            }

            StatsGranularity.WEEKLY -> {
                var cursor = startDateTime.toLocalDate().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                val endDate = endDateTime.toLocalDate()
                while (cursor.isBefore(endDate)) {
                    val next = cursor.plusWeeks(1)
                    val sM = cursor.atStartOfDay(zoneId).toInstant().toEpochMilli()
                    val eM = next.atStartOfDay(zoneId).toInstant().toEpochMilli()
                    val label = cursor.format(SHORT_DATE_FORMAT)
                    buckets.add(StatsBucket(sM, eM, label))
                    cursor = next
                }
            }

            StatsGranularity.MONTHLY -> {
                var cursor = YearMonth.from(startDateTime)
                val endMonth = YearMonth.from(endDateTime.minusDays(1)).plusMonths(1)
                while (cursor.isBefore(endMonth)) {
                    val next = cursor.plusMonths(1)
                    val sM = cursor.atDay(1).atStartOfDay(zoneId).toInstant().toEpochMilli()
                    val eM = next.atDay(1).atStartOfDay(zoneId).toInstant().toEpochMilli()
                    val label = "${cursor.monthValue}月"
                    buckets.add(StatsBucket(sM, eM, label))
                    cursor = next
                }
            }
        }

        return buckets
    }

    /**
     * 依据最终实际绘图点数决定柱状图还是折线图
     * 规则：pointCount <= 12 -> 柱状图，> 12 -> 折线图
     */
    fun chooseChartType(pointCount: Int): ChartType =
        if (pointCount > 12) ChartType.LINE else ChartType.BAR
}
