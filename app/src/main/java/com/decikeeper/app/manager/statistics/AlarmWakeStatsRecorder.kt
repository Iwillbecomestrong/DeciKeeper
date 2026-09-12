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

        val earliestDayRecord = try {
            withDatabase(context) { database ->
                database.migrateIfNeeded { readLegacyRecords(context) }
                if (!database.insert(newRecord)) return@withDatabase null
                // 同一天有多个闹钟时，日均统计与旧键优先采用当天最早的一次。
                database.findEarliestForDate(newRecord.dateLabel) ?: newRecord
            }
        } catch (_: Exception) {
            // 保留活动会话，等待下次恢复/完成路径重试，不能把一次数据库故障伪装成成功。
            return false
        } ?: return false

        // 清理失败不能让已成功落库的闹钟会话被重复提交。
        runCatching { purgeExpired(context) }

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
        return try {
            withDatabase(context) { database ->
                database.migrateIfNeeded { readLegacyRecords(context) }
                purgeExpired(context, database)
                database.readAll()
            }
        } catch (_: Exception) {
            // 数据库暂时不可用时仍展示旧账本，避免统计页直接崩溃或变成空白。
            readLegacyRecords(context)
        }
    }

    /**
     * 删除单条打卡记录
     */
    fun deleteRecord(context: Context, id: String): Boolean {
        return try {
            withDatabase(context) { database ->
                database.migrateIfNeeded { readLegacyRecords(context) }
                database.delete(id)
            }
        } catch (_: Exception) {
            false
        }
    }

    /**
     * 修改更新单条打卡记录
     */
    fun updateRecord(context: Context, updated: AlarmWakeRecord): Boolean {
        return try {
            withDatabase(context) { database ->
                database.migrateIfNeeded { readLegacyRecords(context) }
                database.update(updated)
            }
        } catch (_: Exception) {
            false
        }
    }

    /** 导出当前 SQLite 明细，供设置备份使用。 */
    fun exportRecords(context: Context): List<AlarmWakeRecord> = getAllRecords(context)

    /**
     * 导入旧版备份：prefs 已先恢复，此处只在当前数据库为空时补入旧记录，
     * 避免把已有的新历史静默覆盖掉。
     */
    fun importLegacyBackupRecords(context: Context): Boolean {
        val records = readLegacyRecords(context)
        return try {
            withDatabase(context) { database -> database.replaceIfEmpty(records) }
        } catch (_: Exception) {
            false
        }
    }

    /** 应用包含 SQLite 明细的新版备份，空数组也代表明确清空该分类。 */
    fun replaceAllRecords(context: Context, records: List<AlarmWakeRecord>) {
        withDatabase(context) { database ->
            database.replaceAll(records)
        }
        runCatching { purgeExpired(context) }
    }

    fun purgeExpired(context: Context) {
        runCatching {
            withDatabase(context) { database ->
                database.migrateIfNeeded { readLegacyRecords(context) }
                purgeExpired(context, database)
            }
        }
    }

    private fun purgeExpired(context: Context, database: AlarmWakeStatsDatabase) {
        HistoryRetentionPolicy.cutoffMillis(
            HistoryRetentionPolicy.get(context, HistoryRecordKind.WAKE),
            System.currentTimeMillis()
        )?.let(database::deleteBefore)
    }

    private inline fun <T> withDatabase(
        context: Context,
        block: (AlarmWakeStatsDatabase) -> T
    ): T {
        val database = AlarmWakeStatsDatabase(context)
        return try {
            block(database)
        } finally {
            database.close()
        }
    }

    private fun readLegacyRecords(context: Context): List<AlarmWakeRecord> {
        val prefs = context.getSharedPreferences(LEDGER_PREFS, Context.MODE_PRIVATE)
        val jsonStr = prefs.getString(KEY_RECORDS_JSON, null)
        if (!jsonStr.isNullOrBlank()) {
            try {
                val array = JSONArray(jsonStr)
                val list = mutableListOf<AlarmWakeRecord>()
                for (i in 0 until array.length()) {
                    // A malformed item cannot invalidate the remaining history.
                    val obj = array.optJSONObject(i) ?: continue
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
                // Invalid JSON: use the older per-day ledger below.
            }
        }

        return migrateLegacyRecords(prefs)
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
