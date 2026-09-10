package com.decikeeper.app.manager.statistics

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 规则变动账本管理器
 * 统一管理三类规则变动的打点记录与聚合：
 * 1. 有利改动 (FAVORABLE): 加严操作（撤销不记录）
 * 2. 不利改动 (UNFAVORABLE): 放宽操作（部分撤销仍计入不利改动）
 * 3. 不利改动撤销 (UNFAVORABLE_REVERSED): 放宽在 pending 期内被撤销复原
 */
object RuleChangeLedger {

    private const val PREFS_NAME = "rule_change_ledger"
    private const val KEY_RECENT_JSON = "recent_changes_json"
    private const val MAX_RECENT_RECORDS = 50

    enum class ChangeType {
        FAVORABLE,             // 有利改动（加严）
        UNFAVORABLE,           // 不利改动（放宽）
        UNFAVORABLE_REVERSED   // 不利改动撤销
    }

    data class ChangeRecord(
        val id: String,
        val timestamp: Long,
        val dateKey: String, // yyyyMMdd
        val type: ChangeType,
        val ruleName: String,
        val detail: String
    )

    private val zoneId: ZoneId get() = ZoneId.systemDefault()
    private val dateFormatter = DateTimeFormatter.ofPattern("yyyyMMdd")

    /**
     * 记录有利改动（加严）
     */
    fun recordFavorableChange(context: Context, ruleName: String, detail: String = "") {
        record(context, ChangeType.FAVORABLE, ruleName, detail)
    }

    /**
     * 记录不利改动（放宽）
     */
    fun recordUnfavorableChange(context: Context, ruleName: String, detail: String = "") {
        record(context, ChangeType.UNFAVORABLE, ruleName, detail)
    }

    /**
     * 记录不利改动撤销（在 pending 期内撤回）
     */
    fun recordUnfavorableReversal(context: Context, ruleName: String, detail: String = "") {
        record(context, ChangeType.UNFAVORABLE_REVERSED, ruleName, detail)
    }

    private fun record(context: Context, type: ChangeType, ruleName: String, detail: String) {
        val now = System.currentTimeMillis()
        val dateKey = LocalDateTime.ofInstant(Instant.ofEpochMilli(now), zoneId).format(dateFormatter)
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        val countKey = when (type) {
            ChangeType.FAVORABLE -> "rule_${dateKey}_favorable"
            ChangeType.UNFAVORABLE -> "rule_${dateKey}_unfavorable"
            ChangeType.UNFAVORABLE_REVERSED -> "rule_${dateKey}_reversed"
        }

        val currentCount = prefs.getInt(countKey, 0)
        val recentList = getRecentRecords(context).toMutableList()

        val record = ChangeRecord(
            id = "${now}_${type.name.lowercase()}",
            timestamp = now,
            dateKey = dateKey,
            type = type,
            ruleName = ruleName,
            detail = detail
        )
        recentList.add(0, record)
        if (recentList.size > MAX_RECENT_RECORDS) {
            recentList.subList(MAX_RECENT_RECORDS, recentList.size).clear()
        }

        val jsonArray = JSONArray()
        for (item in recentList) {
            val obj = JSONObject().apply {
                put("id", item.id)
                put("timestamp", item.timestamp)
                put("dateKey", item.dateKey)
                put("type", item.type.name)
                put("ruleName", item.ruleName)
                put("detail", item.detail)
            }
            jsonArray.put(obj)
        }

        prefs.edit()
            .putInt(countKey, currentCount + 1)
            .putString(KEY_RECENT_JSON, jsonArray.toString())
            .apply()
    }

    fun getCountsForDate(context: Context, date: LocalDate): Triple<Int, Int, Int> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val dateKey = date.format(dateFormatter)
        val favorable = prefs.getInt("rule_${dateKey}_favorable", 0)
        val unfavorable = prefs.getInt("rule_${dateKey}_unfavorable", 0)
        val reversed = prefs.getInt("rule_${dateKey}_reversed", 0)
        return Triple(favorable, unfavorable, reversed)
    }

    fun getRecentRecords(context: Context): List<ChangeRecord> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val jsonStr = prefs.getString(KEY_RECENT_JSON, null) ?: return emptyList()
        val result = mutableListOf<ChangeRecord>()
        try {
            val arr = JSONArray(jsonStr)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                result.add(
                    ChangeRecord(
                        id = obj.getString("id"),
                        timestamp = obj.getLong("timestamp"),
                        dateKey = obj.getString("dateKey"),
                        type = ChangeType.valueOf(obj.getString("type")),
                        ruleName = obj.optString("ruleName", "时间规则"),
                        detail = obj.optString("detail", "")
                    )
                )
            }
        } catch (_: Exception) {}
        return result
    }

    fun clearForTest(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().clear().apply()
    }
}
