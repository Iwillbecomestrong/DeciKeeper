package com.decikeeper.app.manager.statistics

import android.content.Context
import com.decikeeper.app.data.statistics.AlarmWakeRecord
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Persists the real wake-alarm session that backs the 起床 statistics page.
 *
 * A session begins when a real alarm becomes active (not during pre-warm), keeps
 * the first-stage completion timestamp across Activity/overlay rebuilds, and is
 * committed only after the configured challenge flow is actually complete.
 * Test alarms (id <= 0) are intentionally ignored.
 */
object AlarmWakeStatsRecorder {

    private const val SESSION_PREFS = "alarm_stats_session"
    private const val LEDGER_PREFS = "alarm_stats_ledger"

    private const val KEY_ALARM_ID = "active_alarm_id"
    private const val KEY_PLANNED_MINUTE = "planned_minute_of_day"
    private const val KEY_STARTED_AT = "started_at_ms"
    private const val KEY_FIRST_STAGE_AT = "first_stage_at_ms"
    private const val KEY_RECORDS_JSON = "alarm_wake_records_json"

    private val dateFormatter = DateTimeFormatter.ofPattern("yyyyMMdd")
    private val shortDateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")

    fun startSession(
        context: Context,
        alarmId: Int,
        plannedMinuteOfDay: Int,
        startedAtMillis: Long = System.currentTimeMillis()
    ) {
        if (alarmId <= 0 || startedAtMillis <= 0L) return
        context.getSharedPreferences(SESSION_PREFS, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .putInt(KEY_ALARM_ID, alarmId)
            .putInt(KEY_PLANNED_MINUTE, plannedMinuteOfDay.coerceIn(0, 1439))
            .putLong(KEY_STARTED_AT, startedAtMillis)
            .apply()
    }

    fun markFirstStagePassed(
        context: Context,
        alarmId: Int,
        passedAtMillis: Long = System.currentTimeMillis()
    ) {
        if (alarmId <= 0 || passedAtMillis <= 0L) return
        val prefs = context.getSharedPreferences(SESSION_PREFS, Context.MODE_PRIVATE)
        if (prefs.getInt(KEY_ALARM_ID, -1) != alarmId) return
        if (prefs.getLong(KEY_STARTED_AT, 0L) <= 0L) return
        // First completion wins; recovery/re-hosting must not move the marker later.
        if (prefs.getLong(KEY_FIRST_STAGE_AT, 0L) > 0L) return
        prefs.edit().putLong(KEY_FIRST_STAGE_AT, passedAtMillis).apply()
    }

    /**
     * Commit one completed wake session. Returns true only when a live real-alarm
     * session was committed. Calling again after completion is a no-op.
     */
    fun completeSession(
        context: Context,
        alarmId: Int,
        completedAtMillis: Long = System.currentTimeMillis()
    ): Boolean {
        if (alarmId <= 0 || completedAtMillis <= 0L) return false
        val session = context.getSharedPreferences(SESSION_PREFS, Context.MODE_PRIVATE)
        if (session.getInt(KEY_ALARM_ID, -1) != alarmId) return false

        val startedAt = session.getLong(KEY_STARTED_AT, 0L)
        if (startedAt <= 0L) return false
        val firstStageAt = session.getLong(KEY_FIRST_STAGE_AT, 0L)
            .takeIf { it >= startedAt }
            ?: completedAtMillis
        val completedAt = completedAtMillis.coerceAtLeast(firstStageAt)
        val plannedMinute = session.getInt(KEY_PLANNED_MINUTE, 0).coerceIn(0, 1439)

        val zoneId = ZoneId.systemDefault()
        val sessionDate = Instant.ofEpochMilli(startedAt).atZone(zoneId).toLocalDate()
        val dateKey = sessionDate.format(dateFormatter)
        val firstStageLocal = Instant.ofEpochMilli(firstStageAt).atZone(zoneId)
        val firstStageMinuteOfDay = firstStageLocal.hour * 60 + firstStageLocal.minute
        val firstStageDurationMinutes = elapsedWholeMinutes(startedAt, firstStageAt)
        val totalDurationMinutes = elapsedWholeMinutes(startedAt, completedAt)

        val newRecord = AlarmWakeRecord(
            id = "wake_${System.currentTimeMillis()}_$alarmId",
            dateLabel = sessionDate.format(shortDateFormatter),
            alarmMinuteOfDay = plannedMinute,
            firstStageCompletedMinute = firstStageMinuteOfDay,
            firstStageDurationMinutes = firstStageDurationMinutes,
            totalFlowDurationMinutes = totalDurationMinutes,
            completedAt = completedAt,
            alarmId = alarmId
        )

        val existingRecords = getAllRecords(context).toMutableList()
        existingRecords.removeAll { it.id == newRecord.id }
        existingRecords.add(0, newRecord)
        saveRecords(context, existingRecords)

        // 维护旧键兼容：同一天有多个闹钟时，日均统计与旧键优先采用当天最早的一次
        val dayRecords = existingRecords.filter { it.dateLabel == newRecord.dateLabel }
        val earliestDayRecord = dayRecords.minByOrNull { it.alarmMinuteOfDay } ?: newRecord

        context.getSharedPreferences(LEDGER_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putInt("alarm_${dateKey}_wake_time", earliestDayRecord.alarmMinuteOfDay)
            .putInt("alarm_${dateKey}_stage1_completed_minute", earliestDayRecord.firstStageCompletedMinute)
            .putInt("alarm_${dateKey}_stage1_duration", earliestDayRecord.firstStageDurationMinutes)
            .putInt("alarm_${dateKey}_total_duration", earliestDayRecord.totalFlowDurationMinutes)
            .putLong("alarm_${dateKey}_completed_at", earliestDayRecord.completedAt)
            .apply()

        session.edit().clear().apply()
        return true
    }

    /**
     * 获取全量打卡流水列表（按完成时间倒序）
     */
    fun getAllRecords(context: Context): List<AlarmWakeRecord> {
        val prefs = context.getSharedPreferences(LEDGER_PREFS, Context.MODE_PRIVATE)
        val jsonStr = prefs.getString(KEY_RECORDS_JSON, null)
        if (!jsonStr.isNullOrBlank()) {
            try {
                val array = JSONArray(jsonStr)
                val list = mutableListOf<AlarmWakeRecord>()
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    list.add(
                        AlarmWakeRecord(
                            id = obj.optString("id", "wake_$i"),
                            dateLabel = obj.optString("dateLabel", ""),
                            alarmMinuteOfDay = obj.optInt("alarmMinuteOfDay", 0),
                            firstStageCompletedMinute = obj.optInt("firstStageCompletedMinute", 0),
                            firstStageDurationMinutes = obj.optInt("firstStageDurationMinutes", 0),
                            totalFlowDurationMinutes = obj.optInt("totalFlowDurationMinutes", 0),
                            completedAt = obj.optLong("completedAt", 0L),
                            alarmId = obj.optInt("alarmId", 0)
                        )
                    )
                }
                return list
            } catch (_: Exception) {
            }
        }

        // 迁移历史账本数据
        val migrated = migrateLegacyRecords(prefs)
        if (migrated.isNotEmpty()) {
            saveRecords(context, migrated)
        }
        return migrated
    }

    /**
     * 删除单条打卡记录
     */
    fun deleteRecord(context: Context, id: String): Boolean {
        val records = getAllRecords(context).toMutableList()
        val removed = records.removeAll { it.id == id }
        if (removed) {
            saveRecords(context, records)
            return true
        }
        return false
    }

    /**
     * 修改更新单条打卡记录
     */
    fun updateRecord(context: Context, updated: AlarmWakeRecord): Boolean {
        val records = getAllRecords(context).toMutableList()
        val index = records.indexOfFirst { it.id == updated.id }
        if (index >= 0) {
            records[index] = updated
            saveRecords(context, records)
            return true
        }
        return false
    }

    private fun saveRecords(context: Context, records: List<AlarmWakeRecord>) {
        try {
            val array = JSONArray()
            records.take(1000).forEach { r ->
                val obj = JSONObject().apply {
                    put("id", r.id)
                    put("dateLabel", r.dateLabel)
                    put("alarmMinuteOfDay", r.alarmMinuteOfDay)
                    put("firstStageCompletedMinute", r.firstStageCompletedMinute)
                    put("firstStageDurationMinutes", r.firstStageDurationMinutes)
                    put("totalFlowDurationMinutes", r.totalFlowDurationMinutes)
                    put("completedAt", r.completedAt)
                    put("alarmId", r.alarmId)
                }
                array.put(obj)
            }
            context.getSharedPreferences(LEDGER_PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_RECORDS_JSON, array.toString())
                .apply()
        } catch (_: Exception) {
        }
    }

    private fun migrateLegacyRecords(prefs: android.content.SharedPreferences): List<AlarmWakeRecord> {
        val list = mutableListOf<AlarmWakeRecord>()
        try {
            val all = prefs.all
            for (key in all.keys) {
                if (key.startsWith("alarm_") && key.endsWith("_wake_time")) {
                    val dateKey = key.removePrefix("alarm_").removeSuffix("_wake_time")
                    val wakeMin = prefs.getInt(key, 420)
                    val s1 = prefs.getInt("alarm_${dateKey}_stage1_duration", 5)
                    val total = prefs.getInt("alarm_${dateKey}_total_duration", 8)
                    val s1Completed = prefs.getInt("alarm_${dateKey}_stage1_completed_minute", (wakeMin + s1) % 1440)
                    val completedAt = prefs.getLong("alarm_${dateKey}_completed_at", 0L)
                    val label = try {
                        LocalDate.parse(dateKey, dateFormatter).format(shortDateFormatter)
                    } catch (_: Exception) {
                        dateKey
                    }
                    list.add(
                        AlarmWakeRecord(
                            id = "legacy_$dateKey",
                            dateLabel = label,
                            alarmMinuteOfDay = wakeMin,
                            firstStageCompletedMinute = s1Completed,
                            firstStageDurationMinutes = s1,
                            totalFlowDurationMinutes = total,
                            completedAt = completedAt
                        )
                    )
                }
            }
            list.sortByDescending { it.completedAt }
        } catch (_: Exception) {
        }
        return list
    }

    private fun elapsedWholeMinutes(startMillis: Long, endMillis: Long): Int {
        val elapsed = (endMillis - startMillis).coerceAtLeast(0L)
        return (elapsed / 60_000L).toInt()
    }
}
