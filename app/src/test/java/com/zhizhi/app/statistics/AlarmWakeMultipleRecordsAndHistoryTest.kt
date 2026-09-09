package com.zhizhi.app.statistics

import android.content.Context
import com.zhizhi.app.data.statistics.StatsDateRange
import com.zhizhi.app.data.statistics.WeekdayFilter
import com.zhizhi.app.manager.statistics.AlarmWakeStatsRecorder
import com.zhizhi.app.manager.statistics.StatisticsRepository
import com.zhizhi.app.manager.statistics.StatsDateRangeResolver
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
class AlarmWakeMultipleRecordsAndHistoryTest {

    private lateinit var context: Context
    private val zoneId: ZoneId = ZoneId.systemDefault()

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("alarm_stats_ledger", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("alarm_stats_session", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @After
    fun tearDown() {
        context.getSharedPreferences("alarm_stats_ledger", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("alarm_stats_session", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun testSameDayMultipleAlarmsPersistAllRecordsAndChartUsesEarliestAlarm() {
        val date = LocalDate.now(zoneId)
        val morningStart = date.atTime(7, 0).atZone(zoneId).toInstant().toEpochMilli()
        val morningS1 = morningStart + 3 * 60_000L
        val morningEnd = morningStart + 5 * 60_000L

        // 早晨 07:00 第一次打卡
        AlarmWakeStatsRecorder.startSession(context, 101, 7 * 60, morningStart)
        AlarmWakeStatsRecorder.markFirstStagePassed(context, 101, morningS1)
        assertTrue(AlarmWakeStatsRecorder.completeSession(context, 101, morningEnd))

        // 上午 10:00 第二次打卡（例如临时或次级闹钟）
        val laterStart = date.atTime(10, 0).atZone(zoneId).toInstant().toEpochMilli()
        val laterS1 = laterStart + 2 * 60_000L
        val laterEnd = laterStart + 4 * 60_000L
        AlarmWakeStatsRecorder.startSession(context, 102, 10 * 60, laterStart)
        AlarmWakeStatsRecorder.markFirstStagePassed(context, 102, laterS1)
        assertTrue(AlarmWakeStatsRecorder.completeSession(context, 102, laterEnd))

        // 验证全量记录：两条记录均存在，没有被覆盖
        val allRecords = StatisticsRepository.getAllWakeRecords(context)
        assertEquals(2, allRecords.size)

        // 验证按日统计分桶以早起主闹钟 07:00 (420分钟) 为基准
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
        assertEquals(420, summary.medianAlarmMinuteOfDay)
    }

    @Test
    fun testUpdateAndDeleteWakeRecord() {
        val date = LocalDate.now(zoneId)
        val start = date.atTime(8, 0).atZone(zoneId).toInstant().toEpochMilli()
        AlarmWakeStatsRecorder.startSession(context, 105, 8 * 60, start)
        AlarmWakeStatsRecorder.markFirstStagePassed(context, 105, start + 3 * 60_000L)
        AlarmWakeStatsRecorder.completeSession(context, 105, start + 6 * 60_000L)

        val record = StatisticsRepository.getAllWakeRecords(context).first()
        assertEquals(480, record.alarmMinuteOfDay)

        // 修改计划时间为 07:30 (450)
        val updated = record.copy(alarmMinuteOfDay = 450)
        assertTrue(StatisticsRepository.updateWakeRecord(context, updated))
        val reloaded = StatisticsRepository.getAllWakeRecords(context).first()
        assertEquals(450, reloaded.alarmMinuteOfDay)

        // 删除记录
        assertTrue(StatisticsRepository.deleteWakeRecord(context, record.id))
        assertTrue(StatisticsRepository.getAllWakeRecords(context).isEmpty())
    }
}
