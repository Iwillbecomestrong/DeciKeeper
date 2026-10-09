package com.decikeeper.app.manager.statistics

import com.decikeeper.app.data.statistics.AlarmWakeRecord
import com.decikeeper.app.data.statistics.RestUsageRecord
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

enum class HistoryDisplayRange(val displayName: String) {
    TODAY("今天"),
    LAST_7_DAYS("近 7 天"),
    THIS_MONTH("本月"),
    ALL("全部")
}

object HistoryRecordFilter {

    /**
     * @param businessDayStartMinutes 每天的分界（距 00:00 的分钟数）。0 = 自然日；
     * 额度流水属于时间规则，按业务日切：传入生效时间（refresh time）的分钟数。
     */
    fun filterQuota(
        records: List<RestUsageRecord>,
        range: HistoryDisplayRange,
        nowMillis: Long = System.currentTimeMillis(),
        zoneId: ZoneId = ZoneId.systemDefault(),
        businessDayStartMinutes: Int = 0
    ): List<RestUsageRecord> {
        if (range == HistoryDisplayRange.ALL) return records

        val now = Instant.ofEpochMilli(nowMillis).atZone(zoneId)
        // 业务日：分界之前仍算前一天
        val today = now.minusMinutes(businessDayStartMinutes.toLong()).toLocalDate()
        val startDate = when (range) {
            HistoryDisplayRange.TODAY -> today
            HistoryDisplayRange.LAST_7_DAYS -> today.minusDays(6)
            HistoryDisplayRange.THIS_MONTH -> today.with(TemporalAdjusters.firstDayOfMonth())
            HistoryDisplayRange.ALL -> return records
        }
        val endDate = when (range) {
            HistoryDisplayRange.THIS_MONTH -> startDate.plusMonths(1)
            else -> today.plusDays(1)
        }
        val startMillis = startDate.atStartOfDay(zoneId).plusMinutes(businessDayStartMinutes.toLong()).toInstant().toEpochMilli()
        val endMillis = endDate.atStartOfDay(zoneId).plusMinutes(businessDayStartMinutes.toLong()).toInstant().toEpochMilli()
        return records
            .filter { it.timestamp >= startMillis && it.timestamp < endMillis }
            .sortedByDescending { it.timestamp }
    }

    /**
     * 起床记录属于自然日口径：按日期标签（起床那天的自然日）切，不看业务日。
     * 日期标签解析失败时退回用完成时间所在的自然日。结果一律按完成时间新的在前（含「全部」）。
     */
    fun filterWake(
        records: List<AlarmWakeRecord>,
        range: HistoryDisplayRange,
        nowMillis: Long = System.currentTimeMillis(),
        zoneId: ZoneId = ZoneId.systemDefault()
    ): List<AlarmWakeRecord> {
        val newestFirst = records.sortedByDescending { it.completedAt }
        if (range == HistoryDisplayRange.ALL) return newestFirst

        val today = Instant.ofEpochMilli(nowMillis).atZone(zoneId).toLocalDate()
        val startDate = when (range) {
            HistoryDisplayRange.TODAY -> today
            HistoryDisplayRange.LAST_7_DAYS -> today.minusDays(6)
            HistoryDisplayRange.THIS_MONTH -> today.with(TemporalAdjusters.firstDayOfMonth())
            HistoryDisplayRange.ALL -> return newestFirst
        }
        return newestFirst.filter {
            val date = runCatching { java.time.LocalDate.parse(it.dateLabel) }.getOrNull()
                ?: Instant.ofEpochMilli(it.completedAt).atZone(zoneId).toLocalDate()
            !date.isBefore(startDate) && !date.isAfter(today)
        }
    }
}
