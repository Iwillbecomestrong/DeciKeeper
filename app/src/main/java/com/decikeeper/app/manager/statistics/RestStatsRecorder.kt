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

        val records = getAllRecords(context).toMutableList()
        records.add(0, record) // 最新记录在前
        saveRecords(context, records)
        return record
    }

    @Synchronized
    fun getAllRecords(context: Context): List<RestUsageRecord> {
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
        val records = getAllRecords(context).toMutableList()
        val removed = records.removeAll { it.id == id }
        if (removed) {
            saveRecords(context, records)
        }
        return removed
    }

    @Synchronized
    fun updateRecord(context: Context, updated: RestUsageRecord): Boolean {
        val records = getAllRecords(context).toMutableList()
        val idx = records.indexOfFirst { it.id == updated.id }
        if (idx == -1) return false
        records[idx] = updated
        saveRecords(context, records)
        return true
    }

    @Synchronized
    fun clearAll(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_RECORDS_JSON)
            .apply()
    }

    private fun saveRecords(context: Context, records: List<RestUsageRecord>) {
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
}
