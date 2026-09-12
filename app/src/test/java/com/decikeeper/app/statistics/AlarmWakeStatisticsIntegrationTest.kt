package com.decikeeper.app.statistics

import android.content.Context
import com.decikeeper.app.data.statistics.StatsDateRange
import com.decikeeper.app.data.statistics.WeekdayFilter
import com.decikeeper.app.manager.statistics.AlarmWakeStatsRecorder
import com.decikeeper.app.manager.statistics.AlarmWakeStatsDatabase
import com.decikeeper.app.manager.statistics.StatisticsRepository
import com.decikeeper.app.manager.statistics.StatsDateRangeResolver
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@RunWith(RobolectricTestRunner::class)
class AlarmWakeStatisticsIntegrationTest {

    private lateinit var context: Context
    private val zoneId: ZoneId = ZoneId.systemDefault()
    private val dateFormatter = DateTimeFormatter.ofPattern("yyyyMMdd")

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        context.deleteDatabase(AlarmWakeStatsDatabase.DATABASE_NAME)
        context.getSharedPreferences("alarm_stats_ledger", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("alarm_stats_session", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @After
    fun tearDown() {
        context.deleteDatabase(AlarmWakeStatsDatabase.DATABASE_NAME)
        context.getSharedPreferences("alarm_stats_ledger", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("alarm_stats_session", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun completedRealAlarmWritesTheLedgerReadByStatistics() {
        val date = LocalDate.now(zoneId)
        val startedAt = date.atTime(7, 30).atZone(zoneId).toInstant().toEpochMilli()
        val firstStageAt = startedAt + 3 * 60_000L
        val completedAt = startedAt + 5 * 60_000L

        AlarmWakeStatsRecorder.startSession(
            context = context,
            alarmId = 7,
            plannedMinuteOfDay = 7 * 60 + 30,
            startedAtMillis = startedAt
        )
        AlarmWakeStatsRecorder.markFirstStagePassed(context, 7, firstStageAt)
        assertTrue(AlarmWakeStatsRecorder.completeSession(context, 7, completedAt))

        val key = date.format(dateFormatter)
        val ledger = context.getSharedPreferences("alarm_stats_ledger", Context.MODE_PRIVATE)
        assertEquals(450, ledger.getInt("alarm_${key}_wake_time", -1))
        assertEquals(3, ledger.getInt("alarm_${key}_stage1_duration", -1))
        assertEquals(5, ledger.getInt("alarm_${key}_total_duration", -1))
        assertEquals(453, ledger.getInt("alarm_${key}_stage1_completed_minute", -1))

        val resolved = StatsDateRangeResolver.resolveRange(
            StatsDateRange.Today,
            LocalDateTime.now(zoneId),
            zoneId
        )
        val summary = StatisticsRepository.getAlarmStats(
            context,
            StatsDateRange.Today,
            resolved,
            WeekdayFilter.ALL
        )
        assertEquals(1, summary.recordedDaysCount)
        assertEquals(1, summary.recentRecords.size)
        assertEquals(453, summary.recentRecords.single().firstStageCompletedMinute)
    }

    @Test
    fun testAlarmDoesNotPolluteRealWakeStatistics() {
        val startedAt = System.currentTimeMillis()
        AlarmWakeStatsRecorder.startSession(context, 0, 480, startedAt)
        AlarmWakeStatsRecorder.markFirstStagePassed(context, 0, startedAt + 60_000L)

        assertFalse(AlarmWakeStatsRecorder.completeSession(context, 0, startedAt + 120_000L))
        assertTrue(context.getSharedPreferences("alarm_stats_ledger", Context.MODE_PRIVATE).all.isEmpty())
    }

    @Test
    fun todayRecordIsCountedOnceAndPlacedOnlyInItsPlannedHour() {
        val date = LocalDate.now(zoneId)
        val key = date.format(dateFormatter)
        context.getSharedPreferences("alarm_stats_ledger", Context.MODE_PRIVATE).edit()
            .putInt("alarm_${key}_wake_time", 7 * 60 + 30)
            .putInt("alarm_${key}_stage1_completed_minute", 7 * 60 + 36)
            .putInt("alarm_${key}_stage1_duration", 6)
            .putInt("alarm_${key}_total_duration", 10)
            .commit()

        val resolved = StatsDateRangeResolver.resolveRange(
            StatsDateRange.Today,
            LocalDateTime.now(zoneId),
            zoneId
        )
        val summary = StatisticsRepository.getAlarmStats(
            context,
            StatsDateRange.Today,
            resolved,
            WeekdayFilter.ALL
        )

        assertEquals("one completed wake day must not be duplicated into 24 hourly records", 1, summary.recordedDaysCount)
        assertEquals(
            "a date-level wake record belongs only to the hour containing its planned alarm time",
            1,
            summary.buckets.count { it.alarmMinuteOfDay != null }
        )
        assertEquals(450f, summary.buckets.single { it.alarmMinuteOfDay != null }.alarmMinuteOfDay!!, 0.001f)
    }

    @Test
    fun yearlyViewFindsCompletedWakeAwayFromTheFirstDayOfMonth() {
        val today = LocalDate.now(zoneId)
        val recordDate = LocalDate.of(today.year, 1, 2)
        val key = recordDate.format(dateFormatter)
        context.getSharedPreferences("alarm_stats_ledger", Context.MODE_PRIVATE).edit()
            .putInt("alarm_${key}_wake_time", 480)
            .putInt("alarm_${key}_stage1_completed_minute", 485)
            .putInt("alarm_${key}_stage1_duration", 5)
            .putInt("alarm_${key}_total_duration", 8)
            .commit()

        val resolved = StatsDateRangeResolver.resolveRange(
            StatsDateRange.ThisYear,
            LocalDateTime.of(today, LocalTime.NOON),
            zoneId
        )
        val summary = StatisticsRepository.getAlarmStats(
            context,
            StatsDateRange.ThisYear,
            resolved,
            WeekdayFilter.ALL
        )

        assertEquals("monthly aggregation must scan every date in the month, not only day 1", 1, summary.recordedDaysCount)
        assertTrue(summary.buckets.any { it.alarmMinuteOfDay == 480f })
    }

    @Test
    fun productionAlarmFlowIsWiredToStartStageAndCompleteStatistics() {
        val scheduler = File("src/main/java/com/decikeeper/app/manager/AlarmScheduler.kt").readText()
        val overlay = File("src/main/java/com/decikeeper/app/ui/LockAlarmOverlay.kt").readText()

        assertTrue(scheduler.contains("AlarmWakeStatsRecorder.startSession("))
        assertTrue(scheduler.contains("AlarmWakeStatsRecorder.markFirstStagePassed("))
        assertTrue(overlay.contains("AlarmWakeStatsRecorder.completeSession("))
    }
}
