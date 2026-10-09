package com.decikeeper.app.manager.statistics

import com.decikeeper.app.data.statistics.AlarmWakeRecord
import com.decikeeper.app.data.statistics.RestUsageRecord
import com.decikeeper.app.data.statistics.StatsDateRange
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * 统计详情流水的显示范围与统计页共用 [StatsDateRange]（今天 / 昨天 / 本周 / 上周 / 近 7 天 / 本月 / 上月 / 近 30 天 / 本年 / 自定义）；
 * 历史页另有「全部」，用 null 表示。
 */
object HistoryRecordFilter {

    /** 范围解析成 [startDate, endDateExclusive) 两个自然日；「今天」等相对范围以 [today] 为准。 */
    private fun resolveDates(range: StatsDateRange, today: LocalDate, zoneId: ZoneId): Pair<LocalDate, LocalDate> {
        val resolved = StatsDateRangeResolver.resolveRange(range, today.atStartOfDay(), zoneId)
        fun dateOf(millis: Long) = LocalDateTime.ofInstant(Instant.ofEpochMilli(millis), zoneId).toLocalDate()
        return dateOf(resolved.startMillis) to dateOf(resolved.endMillisExclusive)
    }

    /**
     * @param range null = 全部
     * @param businessDayStartMinutes 每天的分界（距 00:00 的分钟数）。0 = 自然日；
     * 额度流水属于时间规则，按业务日切：传入生效时间（refresh time）的分钟数。
     */
    fun filterQuota(
        records: List<RestUsageRecord>,
        range: StatsDateRange?,
        nowMillis: Long = System.currentTimeMillis(),
        zoneId: ZoneId = ZoneId.systemDefault(),
        businessDayStartMinutes: Int = 0
    ): List<RestUsageRecord> {
        if (range == null) return records

        val now = Instant.ofEpochMilli(nowMillis).atZone(zoneId)
        // 业务日：分界之前仍算前一天
        val today = now.minusMinutes(businessDayStartMinutes.toLong()).toLocalDate()
        val (startDate, endDate) = resolveDates(range, today, zoneId)
        val startMillis = startDate.atStartOfDay(zoneId).plusMinutes(businessDayStartMinutes.toLong()).toInstant().toEpochMilli()
        val endMillis = endDate.atStartOfDay(zoneId).plusMinutes(businessDayStartMinutes.toLong()).toInstant().toEpochMilli()
        return records
            .filter { it.timestamp >= startMillis && it.timestamp < endMillis }
            .sortedByDescending { it.timestamp }
    }

    /**
     * 起床记录属于自然日口径：按日期标签（起床那天的自然日）切，不看业务日。
     * 日期标签解析失败时退回用完成时间所在的自然日。结果一律按完成时间新的在前（含「全部」）。
     *
     * @param range null = 全部
     */
    fun filterWake(
        records: List<AlarmWakeRecord>,
        range: StatsDateRange?,
        nowMillis: Long = System.currentTimeMillis(),
        zoneId: ZoneId = ZoneId.systemDefault()
    ): List<AlarmWakeRecord> {
        val newestFirst = records.sortedByDescending { it.completedAt }
        if (range == null) return newestFirst

        val today = Instant.ofEpochMilli(nowMillis).atZone(zoneId).toLocalDate()
        val (startDate, endDate) = resolveDates(range, today, zoneId)
        return newestFirst.filter {
            val date = runCatching { LocalDate.parse(it.dateLabel) }.getOrNull()
                ?: Instant.ofEpochMilli(it.completedAt).atZone(zoneId).toLocalDate()
            !date.isBefore(startDate) && date.isBefore(endDate)
        }
    }
}
