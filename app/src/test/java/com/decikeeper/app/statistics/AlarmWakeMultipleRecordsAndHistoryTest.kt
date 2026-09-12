package com.decikeeper.app.statistics

import android.content.Context
import com.decikeeper.app.data.statistics.AlarmWakeRecord
import com.decikeeper.app.data.statistics.StatsDateRange
import com.decikeeper.app.data.statistics.WeekdayFilter
import com.decikeeper.app.manager.SettingsBackupManager
import com.decikeeper.app.manager.statistics.AlarmWakeStatsDatabase
import com.decikeeper.app.manager.statistics.AlarmWakeStatsRecorder
import com.decikeeper.app.manager.statistics.HistoryRecordKind
import com.decikeeper.app.manager.statistics.HistoryRetention
import com.decikeeper.app.manager.statistics.HistoryRetentionPolicy
import com.decikeeper.app.manager.statistics.StatisticsRepository
import com.decikeeper.app.manager.statistics.StatsDateRangeResolver
import org.json.JSONArray
import org.json.JSONObject
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
        context.deleteDatabase(AlarmWakeStatsDatabase.DATABASE_NAME)
        context.getSharedPreferences("alarm_stats_ledger", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("alarm_stats_session", Context.MODE_PRIVATE).edit().clear().commit()
        HistoryRetentionPolicy.set(context, HistoryRecordKind.WAKE, HistoryRetention.FOREVER)
    }

    @After
    fun tearDown() {
        context.deleteDatabase(AlarmWakeStatsDatabase.DATABASE_NAME)
        context.getSharedPreferences("alarm_stats_ledger", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("alarm_stats_session", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences(HistoryRetentionPolicy.PREFS_NAME, Context.MODE_PRIVATE).edit().clear().commit()
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
        assertEquals(2, summary.recordedRecordsCount)
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

    @Test
    fun completingAnotherAlarmDoesNotDiscardTheOldestPersistedRecord() {
        val ledger = context.getSharedPreferences("alarm_stats_ledger", Context.MODE_PRIVATE)
        val records = JSONArray()
        repeat(1000) { index ->
            records.put(JSONObject().apply {
                put("id", "old_$index")
                put("dateLabel", "2026-01-01")
                put("alarmMinuteOfDay", 420)
                put("firstStageCompletedMinute", 425)
                put("firstStageDurationMinutes", 5)
                put("totalFlowDurationMinutes", 8)
                put("completedAt", index.toLong() + 1L)
                put("alarmId", index + 1)
            })
        }
        ledger.edit().putString("alarm_wake_records_json", records.toString()).commit()

        val startedAt = System.currentTimeMillis()
        AlarmWakeStatsRecorder.startSession(context, 2001, 8 * 60, startedAt)
        assertTrue(AlarmWakeStatsRecorder.completeSession(context, 2001, startedAt + 5 * 60_000L))

        val allRecords = StatisticsRepository.getAllWakeRecords(context)
        assertEquals(1001, allRecords.size)
        assertTrue("oldest record must remain after appending a new record", allRecords.any { it.id == "old_0" })
    }

    @Test
    fun oneMalformedJsonItemDoesNotHideTheOtherWakeRecords() {
        val ledger = context.getSharedPreferences("alarm_stats_ledger", Context.MODE_PRIVATE)
        val records = JSONArray().apply {
            put(JSONObject().apply {
                put("id", "valid_1")
                put("dateLabel", "2026-01-01")
                put("alarmMinuteOfDay", 420)
                put("firstStageCompletedMinute", 425)
                put("firstStageDurationMinutes", 5)
                put("totalFlowDurationMinutes", 8)
                put("completedAt", 2L)
            })
            put("unexpected_scalar")
            put(JSONObject().apply {
                put("id", "valid_2")
                put("dateLabel", "2026-01-02")
                put("alarmMinuteOfDay", 480)
                put("firstStageCompletedMinute", 485)
                put("firstStageDurationMinutes", 5)
                put("totalFlowDurationMinutes", 8)
                put("completedAt", 1L)
            })
        }
        ledger.edit().putString("alarm_wake_records_json", records.toString()).commit()

        val loaded = StatisticsRepository.getAllWakeRecords(context)
        assertEquals(listOf("valid_1", "valid_2"), loaded.map { it.id })
    }

    @Test
    fun sqliteMigrationIsIdempotentAndNewRecordsDoNotRewriteLegacyJson() {
        val legacy = JSONArray().apply {
            put(JSONObject().apply {
                put("id", "legacy_json_1")
                put("dateLabel", "2026-01-01")
                put("alarmMinuteOfDay", 420)
                put("firstStageCompletedMinute", 425)
                put("firstStageDurationMinutes", 5)
                put("totalFlowDurationMinutes", 8)
                put("completedAt", 10L)
                put("alarmId", 1)
            })
            put(JSONObject().apply {
                put("id", "legacy_json_2")
                put("dateLabel", "2026-01-02")
                put("alarmMinuteOfDay", 480)
                put("firstStageCompletedMinute", 485)
                put("firstStageDurationMinutes", 5)
                put("totalFlowDurationMinutes", 8)
                put("completedAt", 20L)
                put("alarmId", 2)
            })
        }
        val ledger = context.getSharedPreferences("alarm_stats_ledger", Context.MODE_PRIVATE)
        ledger.edit().putString("alarm_wake_records_json", legacy.toString()).commit()

        assertEquals(listOf("legacy_json_2", "legacy_json_1"), AlarmWakeStatsRecorder.getAllRecords(context).map { it.id })
        // 第二次读取不能再次追加迁移记录。
        assertEquals(2, AlarmWakeStatsRecorder.getAllRecords(context).size)

        ledger.edit().putString(
            "alarm_wake_records_json",
            JSONArray().put(JSONObject().put("id", "stale_after_migration")).toString()
        ).commit()
        assertEquals(
            listOf("legacy_json_2", "legacy_json_1"),
            AlarmWakeStatsRecorder.getAllRecords(context).map { it.id }
        )
    }

    @Test
    fun backupContainsAndRestoresSqliteWakeHistory() {
        val record = AlarmWakeRecord(
            id = "sqlite_backup_1",
            dateLabel = "2026-09-12",
            alarmMinuteOfDay = 420,
            firstStageCompletedMinute = 425,
            firstStageDurationMinutes = 5,
            totalFlowDurationMinutes = 8,
            completedAt = 123L,
            alarmId = 9
        )
        AlarmWakeStatsRecorder.replaceAllRecords(context, listOf(record))

        val json = SettingsBackupManager.buildBackupJson(
            context,
            listOf(SettingsBackupManager.BackupCategory.LEDGER)
        )
        val root = JSONObject(json)
        assertEquals(1, root.getInt("alarmWakeRecordCount"))
        assertEquals("sqlite_backup_1", root.getJSONArray("alarmWakeRecords").getJSONObject(0).getString("id"))

        AlarmWakeStatsRecorder.replaceAllRecords(context, emptyList())
        SettingsBackupManager.applyBackupJson(
            context,
            json,
            listOf(SettingsBackupManager.BackupCategory.LEDGER)
        )
        assertEquals(listOf("sqlite_backup_1"), AlarmWakeStatsRecorder.getAllRecords(context).map { it.id })
    }

    @Test
    fun oldPreferencesOnlyBackupStillRestoresWakeHistory() {
        val legacy = JSONArray().put(JSONObject().apply {
            put("id", "old_backup_record")
            put("dateLabel", "2026-09-11")
            put("alarmMinuteOfDay", 420)
            put("firstStageCompletedMinute", 425)
            put("firstStageDurationMinutes", 5)
            put("totalFlowDurationMinutes", 8)
            put("completedAt", 321L)
            put("alarmId", 11)
        })
        context.getSharedPreferences("alarm_stats_ledger", Context.MODE_PRIVATE)
            .edit().putString("alarm_wake_records_json", legacy.toString()).commit()

        // 生成一个带旧 prefs 的快照，再去掉新版专用字段，模拟升级前导出的备份。
        val oldBackup = JSONObject(
            SettingsBackupManager.buildBackupJson(
                context,
                listOf(SettingsBackupManager.BackupCategory.LEDGER)
            )
        ).apply {
            remove("alarmWakeRecordsVersion")
            remove("alarmWakeRecords")
            remove("alarmWakeRecordCount")
        }.toString()

        AlarmWakeStatsRecorder.replaceAllRecords(context, emptyList())
        SettingsBackupManager.applyBackupJson(
            context,
            oldBackup,
            listOf(SettingsBackupManager.BackupCategory.LEDGER)
        )
        assertEquals(listOf("old_backup_record"), AlarmWakeStatsRecorder.getAllRecords(context).map { it.id })
    }

}
