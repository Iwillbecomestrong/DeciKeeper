package com.decikeeper.app.manager.statistics

import android.content.Context
import java.time.Instant
import java.time.ZoneId

enum class HistoryRecordKind(val preferenceKey: String) {
    WAKE("wake"),
    QUOTA("quota")
}

enum class HistoryRetention(val key: String, val displayName: String) {
    THIRTY_DAYS("30d", "30天"),
    THREE_MONTHS("3m", "3个月"),
    ONE_YEAR("1y", "1年"),
    THREE_YEARS("3y", "3年"),
    FOREVER("forever", "永久");
}

object HistoryRetentionPolicy {
    const val PREFS_NAME = "statistics_retention_prefs"
    private const val KEY_PREFIX = "retention_"

    fun get(context: Context, kind: HistoryRecordKind): HistoryRetention {
        val default = when (kind) {
            HistoryRecordKind.WAKE -> HistoryRetention.THREE_YEARS
            HistoryRecordKind.QUOTA -> HistoryRetention.THREE_MONTHS
        }
        val key = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_PREFIX + kind.preferenceKey, default.key)
        return HistoryRetention.entries.firstOrNull { it.key == key } ?: default
    }

    fun set(context: Context, kind: HistoryRecordKind, retention: HistoryRetention) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_PREFIX + kind.preferenceKey, retention.key)
            .apply()
    }

    fun options(@Suppress("UNUSED_PARAMETER") kind: HistoryRecordKind): List<HistoryRetention> =
        HistoryRetention.entries.toList()

    fun cutoffMillis(
        retention: HistoryRetention,
        nowMillis: Long,
        zoneId: ZoneId = ZoneId.systemDefault()
    ): Long? {
        if (retention == HistoryRetention.FOREVER) return null
        val now = Instant.ofEpochMilli(nowMillis).atZone(zoneId)
        val cutoff = when (retention) {
            HistoryRetention.THIRTY_DAYS -> now.minusDays(30)
            HistoryRetention.THREE_MONTHS -> now.minusMonths(3)
            HistoryRetention.ONE_YEAR -> now.minusYears(1)
            HistoryRetention.THREE_YEARS -> now.minusYears(3)
            HistoryRetention.FOREVER -> return null
        }
        return cutoff.toInstant().toEpochMilli()
    }
}
