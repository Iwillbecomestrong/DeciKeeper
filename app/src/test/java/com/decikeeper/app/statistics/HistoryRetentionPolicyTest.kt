package com.decikeeper.app.statistics

import android.content.Context
import com.decikeeper.app.manager.statistics.AlarmWakeStatsDatabase
import com.decikeeper.app.manager.statistics.AlarmWakeStatsRecorder
import com.decikeeper.app.manager.statistics.HistoryRecordKind
import com.decikeeper.app.manager.statistics.HistoryRetention
import com.decikeeper.app.manager.statistics.HistoryRetentionPolicy
import com.decikeeper.app.manager.statistics.HistoryDisplayRange
import com.decikeeper.app.manager.statistics.HistoryRecordFilter
import com.decikeeper.app.manager.statistics.RestStatsRecorder
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.time.LocalDateTime
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
class HistoryRetentionPolicyTest {

    private val context = RuntimeEnvironment.getApplication()
    private val zoneId = ZoneId.of("Asia/Shanghai")
    private val now = LocalDateTime.of(2026, 9, 12, 10, 30).atZone(zoneId).toInstant().toEpochMilli()

    @Before
    fun setUp() {
        context.getSharedPreferences(HistoryRetentionPolicy.PREFS_NAME, Context.MODE_PRIVATE)
            .edit().clear().commit()
        context.deleteDatabase(AlarmWakeStatsDatabase.DATABASE_NAME)
        context.getSharedPreferences("alarm_stats_ledger", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("alarm_stats_session", Context.MODE_PRIVATE).edit().clear().commit()
        RestStatsRecorder.clearAll(context)
    }

    @After
    fun tearDown() {
        context.getSharedPreferences(HistoryRetentionPolicy.PREFS_NAME, Context.MODE_PRIVATE)
            .edit().clear().commit()
        context.deleteDatabase(AlarmWakeStatsDatabase.DATABASE_NAME)
        context.getSharedPreferences("alarm_stats_ledger", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("alarm_stats_session", Context.MODE_PRIVATE).edit().clear().commit()
        RestStatsRecorder.clearAll(context)
    }

    @Test
    fun defaultsAreThreeYearsForWakeAndThreeMonthsForQuota() {
        assertEquals(HistoryRetention.THREE_YEARS, HistoryRetentionPolicy.get(context, HistoryRecordKind.WAKE))
        assertEquals(HistoryRetention.THREE_MONTHS, HistoryRetentionPolicy.get(context, HistoryRecordKind.QUOTA))
    }

    @Test
    fun cutoffUsesCalendarYearsAndMonths() {
        assertEquals(
            LocalDateTime.of(2023, 9, 12, 10, 30).atZone(zoneId).toInstant().toEpochMilli(),
            HistoryRetentionPolicy.cutoffMillis(HistoryRetention.THREE_YEARS, now, zoneId)
        )
        assertEquals(
            LocalDateTime.of(2026, 6, 12, 10, 30).atZone(zoneId).toInstant().toEpochMilli(),
            HistoryRetentionPolicy.cutoffMillis(HistoryRetention.THREE_MONTHS, now, zoneId)
        )
        assertEquals(null, HistoryRetentionPolicy.cutoffMillis(HistoryRetention.FOREVER, now, zoneId))
    }

    @Test
    fun quotaWritePurgesRecordsOlderThanSelectedRetention() {
        HistoryRetentionPolicy.set(context, HistoryRecordKind.QUOTA, HistoryRetention.THREE_MONTHS)
        val old = RestStatsRecorder.record(context, type = "NORMAL", typeName = "常规休息", timestamp = now - 120L * 24 * 60 * 60 * 1000)
        val current = RestStatsRecorder.record(context, type = "NORMAL", typeName = "常规休息", timestamp = now - 1L * 24 * 60 * 60 * 1000)

        val records = RestStatsRecorder.getAllRecords(context)
        assertTrue(records.none { it.id == old.id })
        assertTrue(records.any { it.id == current.id })
    }

    @Test
    fun wakeWritePurgesRecordsOlderThanSelectedRetention() {
        HistoryRetentionPolicy.set(context, HistoryRecordKind.WAKE, HistoryRetention.THREE_YEARS)
        val oldStart = LocalDateTime.of(2022, 1, 1, 7, 0).atZone(zoneId).toInstant().toEpochMilli()
        AlarmWakeStatsRecorder.startSession(context, 101, 420, oldStart)
        assertTrue(AlarmWakeStatsRecorder.completeSession(context, 101, oldStart + 5 * 60_000L))

        val currentStart = LocalDateTime.of(2026, 9, 11, 7, 0).atZone(zoneId).toInstant().toEpochMilli()
        AlarmWakeStatsRecorder.startSession(context, 102, 420, currentStart)
        assertTrue(AlarmWakeStatsRecorder.completeSession(context, 102, currentStart + 5 * 60_000L))

        val records = AlarmWakeStatsRecorder.getAllRecords(context)
        assertEquals(listOf(102), records.map { it.alarmId })
    }

    @Test
    fun quotaHistoryDefaultsToTodayButSupportsWiderDisplayRanges() {
        val yesterday = RestStatsRecorder.record(
            context, type = "NORMAL", typeName = "常规休息",
            timestamp = now - 2L * 24 * 60 * 60 * 1000
        )
        val today = RestStatsRecorder.record(
            context, type = "NORMAL", typeName = "常规休息",
            timestamp = now - 60 * 60 * 1000
        )
        val records = listOf(today, yesterday)

        assertEquals(
            listOf(today.id),
            HistoryRecordFilter.filterQuota(records, HistoryDisplayRange.TODAY, now, zoneId).map { it.id }
        )
        assertEquals(2, HistoryRecordFilter.filterQuota(records, HistoryDisplayRange.LAST_7_DAYS, now, zoneId).size)
        assertEquals(2, HistoryRecordFilter.filterQuota(records, HistoryDisplayRange.ALL, now, zoneId).size)
    }
}
