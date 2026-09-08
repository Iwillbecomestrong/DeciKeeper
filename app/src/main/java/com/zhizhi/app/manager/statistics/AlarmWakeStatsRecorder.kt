package com.zhizhi.app.manager.statistics

import android.content.Context
import java.time.Instant
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

    private val dateFormatter = DateTimeFormatter.ofPattern("yyyyMMdd")

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
     * Commit one completed wake day. Returns true only when a live real-alarm
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

        context.getSharedPreferences(LEDGER_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putInt("alarm_${dateKey}_wake_time", plannedMinute)
            .putInt("alarm_${dateKey}_stage1_completed_minute", firstStageMinuteOfDay)
            .putInt("alarm_${dateKey}_stage1_duration", firstStageDurationMinutes)
            .putInt("alarm_${dateKey}_total_duration", totalDurationMinutes)
            .putLong("alarm_${dateKey}_completed_at", completedAt)
            .apply()

        session.edit().clear().apply()
        return true
    }

    private fun elapsedWholeMinutes(startMillis: Long, endMillis: Long): Int {
        val elapsed = (endMillis - startMillis).coerceAtLeast(0L)
        return (elapsed / 60_000L).toInt()
    }
}
