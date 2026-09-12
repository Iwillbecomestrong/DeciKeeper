package com.decikeeper.app.manager.statistics

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

    fun filterQuota(
        records: List<RestUsageRecord>,
        range: HistoryDisplayRange,
        nowMillis: Long = System.currentTimeMillis(),
        zoneId: ZoneId = ZoneId.systemDefault()
    ): List<RestUsageRecord> {
        if (range == HistoryDisplayRange.ALL) return records

        val now = Instant.ofEpochMilli(nowMillis).atZone(zoneId)
        val startDate = when (range) {
            HistoryDisplayRange.TODAY -> now.toLocalDate()
            HistoryDisplayRange.LAST_7_DAYS -> now.toLocalDate().minusDays(6)
            HistoryDisplayRange.THIS_MONTH -> now.toLocalDate().with(TemporalAdjusters.firstDayOfMonth())
            HistoryDisplayRange.ALL -> return records
        }
        val endDate = when (range) {
            HistoryDisplayRange.THIS_MONTH -> startDate.plusMonths(1)
            else -> now.toLocalDate().plusDays(1)
        }
        val startMillis = startDate.atStartOfDay(zoneId).toInstant().toEpochMilli()
        val endMillis = endDate.atStartOfDay(zoneId).toInstant().toEpochMilli()
        return records
            .filter { it.timestamp >= startMillis && it.timestamp < endMillis }
            .sortedByDescending { it.timestamp }
    }
}
