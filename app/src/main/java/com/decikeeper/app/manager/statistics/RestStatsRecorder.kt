package com.decikeeper.app.manager.statistics

import android.content.Context
import com.decikeeper.app.data.statistics.RestUsageRecord
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 记录与维护「休息额度流水与使用记录」单真理源。
 *
 * 记录项包括：
 * - 发生时间 (timestamp / dateLabel)
 * - 类型 (NORMAL, BORROW, PRIVILEGE, PENALTY, REFUND)
 * - 类型名称 (常规休息, 借用休息, 周特权, 惩罚扣除, 提前退还)
 * - 申请时长、实际时长
 * - 扣减时长、扣减次数
 * - 变动后结余时长、结余次数
 */
object RestStatsRecorder {

    private const val PREFS_NAME = "rest_stats_ledger"
    private const val KEY_RECORDS_JSON = "rest_records_json"

    private val dateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

    @Synchronized
    fun record(
        context: Context,
        type: String,
        typeName: String,
        reason: String = "",
        requestedMinutes: Double = 0.0,
        actualMinutes: Double = 0.0,
        costMinutes: Double = 0.0,
        costCount: Double = 0.0,
        balanceMinutes: Double = 0.0,
        balanceCount: Int = 0,
        timestamp: Long = System.currentTimeMillis()
    ): RestUsageRecord {
        val zoneId = ZoneId.systemDefault()
        val dateLabel = Instant.ofEpochMilli(timestamp).atZone(zoneId).format(dateTimeFormatter)
        val id = "rest_${timestamp}_${(1000..9999).random()}"

        val record = RestUsageRecord(
            id = id,
            timestamp = timestamp,
            dateLabel = dateLabel,
            type = type,
            typeName = typeName,
            reason = reason,
            requestedMinutes = requestedMinutes,
            actualMinutes = actualMinutes,
            costMinutes = costMinutes,
            costCount = costCount,
            balanceMinutes = balanceMinutes,
            balanceCount = balanceCount
        )

        try {
            withDatabase(context) { database ->
                database.migrateIfNeeded { readLegacyRecords(context) }
                check(database.insert(record)) { "failed to insert rest statistic" }
                // 清理失败不能触发 SharedPreferences 兜底写入，否则会造成重复流水。
                runCatching { purgeExpired(database, context) }
            }
        } catch (_: Exception) {
            // A storage failure must not break the user-facing rest session.
            val records = readLegacyRecords(context).toMutableList()
            records.add(0, record)
            saveLegacyRecords(context, records)
        }
        return record
    }

    @Synchronized
    fun getAllRecords(context: Context): List<RestUsageRecord> {
        return try {
            withDatabase(context) { database ->
                database.migrateIfNeeded { readLegacyRecords(context) }
                runCatching { purgeExpired(database, context) }
                database.readAll()
            }
        } catch (_: Exception) {
            readLegacyRecords(context)
        }
    }

    private fun readLegacyRecords(context: Context): List<RestUsageRecord> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val jsonStr = prefs.getString(KEY_RECORDS_JSON, null) ?: return emptyList()
        val list = mutableListOf<RestUsageRecord>()
        try {
            val array = JSONArray(jsonStr)
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                list.add(
                    RestUsageRecord(
                        id = obj.optString("id", "rest_${System.currentTimeMillis()}_$i"),
                        timestamp = obj.optLong("timestamp", 0L),
                        dateLabel = obj.optString("dateLabel", ""),
                        type = obj.optString("type", "NORMAL"),
                        typeName = obj.optString("typeName", "休息"),
                        reason = obj.optString("reason", ""),
                        requestedMinutes = obj.optDouble("requestedMinutes", 0.0),
                        actualMinutes = obj.optDouble("actualMinutes", 0.0),
                        costMinutes = obj.optDouble("costMinutes", 0.0),
                        costCount = obj.optDouble("costCount", 0.0),
                        balanceMinutes = obj.optDouble("balanceMinutes", 0.0),
                        balanceCount = obj.optInt("balanceCount", 0)
                    )
                )
            }
        } catch (_: Exception) {
        }
        return list.sortedByDescending { it.timestamp }
    }

    @Synchronized
    fun getRecentRecords(context: Context, limit: Int = 5): List<RestUsageRecord> {
        return getAllRecords(context).take(limit.coerceAtLeast(0))
    }

    @Synchronized
    fun deleteRecord(context: Context, id: String): Boolean {
        return try {
            withDatabase(context) { database ->
                database.migrateIfNeeded { readLegacyRecords(context) }
                database.delete(id)
            }
        } catch (_: Exception) {
            val records = readLegacyRecords(context).toMutableList()
            val removed = records.removeAll { it.id == id }
            if (removed) saveLegacyRecords(context, records)
            removed
        }
    }

    @Synchronized
    fun updateRecord(context: Context, updated: RestUsageRecord): Boolean {
        return try {
            withDatabase(context) { database ->
                database.migrateIfNeeded { readLegacyRecords(context) }
                database.update(updated)
            }
        } catch (_: Exception) {
            val records = readLegacyRecords(context).toMutableList()
            val idx = records.indexOfFirst { it.id == updated.id }
            if (idx == -1) false else {
                records[idx] = updated
                saveLegacyRecords(context, records)
                true
            }
        }
    }

    @Synchronized
    fun replaceAllRecords(context: Context, records: List<RestUsageRecord>) {
        withDatabase(context) { database ->
            database.replaceAll(records)
            runCatching { purgeExpired(database, context) }
        }
    }

    private fun purgeExpired(
        database: RestStatsDatabase,
        context: Context,
        nowMillis: Long = System.currentTimeMillis()
    ) {
        database.migrateIfNeeded { readLegacyRecords(context) }
        HistoryRetentionPolicy.cutoffMillis(
            HistoryRetentionPolicy.get(context, HistoryRecordKind.QUOTA),
            nowMillis
        )?.let(database::deleteBefore)
    }

    fun purgeExpired(context: Context) {
        runCatching {
            withDatabase(context) { database ->
                purgeExpired(database, context)
            }
        }
    }

    @Synchronized
    fun clearAll(context: Context) {
        try {
            withDatabase(context) { database ->
                database.clear()
            }
        } catch (_: Exception) { }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().remove(KEY_RECORDS_JSON).apply()
    }

    private fun saveLegacyRecords(context: Context, records: List<RestUsageRecord>) {
        val array = JSONArray()
        for (r in records) {
            val obj = JSONObject()
            obj.put("id", r.id)
            obj.put("timestamp", r.timestamp)
            obj.put("dateLabel", r.dateLabel)
            obj.put("type", r.type)
            obj.put("typeName", r.typeName)
            obj.put("reason", r.reason)
            obj.put("requestedMinutes", r.requestedMinutes)
            obj.put("actualMinutes", r.actualMinutes)
            obj.put("costMinutes", r.costMinutes)
            obj.put("costCount", r.costCount)
            obj.put("balanceMinutes", r.balanceMinutes)
            obj.put("balanceCount", r.balanceCount)
            array.put(obj)
        }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_RECORDS_JSON, array.toString())
            .apply()
    }

    private inline fun <T> withDatabase(
        context: Context,
        block: (RestStatsDatabase) -> T
    ): T {
        val database = database(context)
        return try {
            block(database)
        } finally {
            database.close()
        }
    }

    private fun database(context: Context): RestStatsDatabase = RestStatsDatabase(context)
}
