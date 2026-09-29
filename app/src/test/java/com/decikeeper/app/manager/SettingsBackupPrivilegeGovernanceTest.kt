package com.decikeeper.app.manager

import android.content.Context
import com.decikeeper.app.data.statistics.RestUsageRecord
import com.decikeeper.app.manager.statistics.RestStatsRecorder
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class SettingsBackupPrivilegeGovernanceTest {

    private val context: Context = RuntimeEnvironment.getApplication()

    @Before
    fun setUp() {
        context.getSharedPreferences(CreditManager.PREFS_NAME, Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("decikeeper_rules", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("decikeeper_rules_baseline", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("decikeeper_rules_pending", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("pending_config_prefs", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("rest_stats_ledger", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun testGlobalParamsCategory_doesNotContainUsageLastUsedKeys() {
        val globalParamLedgerKeys = SettingsBackupManager.getSharedKeysForCategory(
            SettingsBackupManager.BackupCategory.GLOBAL_PARAMS,
            "decikeeper_ledger"
        )
        assertFalse(
            "GLOBAL_PARAMS 绝不能包含 weekly_privilege_last_used_day 使用记录键",
            globalParamLedgerKeys.contains("weekly_privilege_last_used_day")
        )
        assertFalse(
            "GLOBAL_PARAMS 绝不能包含 weekly_privilege_last_used 使用记录键",
            globalParamLedgerKeys.contains("weekly_privilege_last_used")
        )
    }

    @Test
    fun testWeeklyPrivilegeUsage_highWaterMark_preventsResettingQuotaWithOldBackup() {
        val ledgerPrefs = context.getSharedPreferences(CreditManager.PREFS_NAME, Context.MODE_PRIVATE)
        val weekKey = "weekly_privilege_count_1758988800000"
        val durKey = "weekly_privilege_duration_used_1758988800000"

        // 本地本周已消耗 2 次，120 分钟
        ledgerPrefs.edit()
            .putInt(weekKey, 2)
            .putInt(durKey, 120)
            .putString("weekly_privilege_last_used_day", "2026-09-29")
            .putLong("weekly_privilege_last_used", 10000L)
            .commit()

        // 备份中记录为 0 次，0 分钟，较早的使用日期
        val backupJson = JSONObject().apply {
            put("type", "decikeeper_settings_backup")
            put("schemaVersion", 2)
            put("preferenceFiles", JSONArray().apply {
                put(JSONObject().apply {
                    put("name", "decikeeper_ledger")
                    put("entries", JSONObject().apply {
                        put(weekKey, JSONObject().apply { put("type", "Int"); put("value", 0) })
                        put(durKey, JSONObject().apply { put("type", "Int"); put("value", 0) })
                        put("weekly_privilege_last_used_day", JSONObject().apply { put("type", "String"); put("value", "2026-09-25") })
                        put("weekly_privilege_last_used", JSONObject().apply { put("type", "Long"); put("value", 5000L) })
                    })
                })
            })
        }.toString()

        SettingsBackupManager.applyBackupJson(
            context,
            backupJson,
            listOf(SettingsBackupManager.BackupCategory.LEDGER)
        )

        // 高水位合并：本地已用 2 次/120 分钟不得被 0 次冲小
        assertEquals(2, ledgerPrefs.getInt(weekKey, 0))
        assertEquals(120, ledgerPrefs.getInt(durKey, 0))
        assertEquals("2026-09-29", ledgerPrefs.getString("weekly_privilege_last_used_day", null))
        assertEquals(10000L, ledgerPrefs.getLong("weekly_privilege_last_used", 0L))
    }

    @Test
    fun testWeeklyPrivilegeUsage_highWaterMark_allowsDeviceMigration() {
        val ledgerPrefs = context.getSharedPreferences(CreditManager.PREFS_NAME, Context.MODE_PRIVATE)
        val weekKey = "weekly_privilege_count_1758988800000"
        val durKey = "weekly_privilege_duration_used_1758988800000"

        // 本地干净安装，使用量为 0
        ledgerPrefs.edit().clear().commit()

        // 备份中已消耗 2 次，120 分钟
        val backupJson = JSONObject().apply {
            put("type", "decikeeper_settings_backup")
            put("schemaVersion", 2)
            put("preferenceFiles", JSONArray().apply {
                put(JSONObject().apply {
                    put("name", "decikeeper_ledger")
                    put("entries", JSONObject().apply {
                        put(weekKey, JSONObject().apply { put("type", "Int"); put("value", 2) })
                        put(durKey, JSONObject().apply { put("type", "Int"); put("value", 120) })
                        put("weekly_privilege_last_used_day", JSONObject().apply { put("type", "String"); put("value", "2026-09-28") })
                        put("weekly_privilege_last_used", JSONObject().apply { put("type", "Long"); put("value", 8888L) })
                    })
                })
            })
        }.toString()

        SettingsBackupManager.applyBackupJson(
            context,
            backupJson,
            listOf(SettingsBackupManager.BackupCategory.LEDGER)
        )

        // 迁机导入：正常带走已用事实
        assertEquals(2, ledgerPrefs.getInt(weekKey, 0))
        assertEquals(120, ledgerPrefs.getInt(durKey, 0))
        assertEquals("2026-09-28", ledgerPrefs.getString("weekly_privilege_last_used_day", null))
        assertEquals(8888L, ledgerPrefs.getLong("weekly_privilege_last_used", 0L))
    }

    @Test
    fun testWeeklyPrivilegeLimitImport_adverseIncreaseIsDelayedToNextWeek_whenNotInAdaptation() {
        val ledgerPrefs = context.getSharedPreferences(CreditManager.PREFS_NAME, Context.MODE_PRIVATE)
        val baselinePrefs = context.getSharedPreferences("decikeeper_rules_baseline", Context.MODE_PRIVATE)

        // 结束适应期
        context.getSharedPreferences("decikeeper_ledger", Context.MODE_PRIVATE)
            .edit()
            .putLong("first_setup_timestamp_v2", 1L)
            .putBoolean("has_manually_ended_adaptation", true)
            .commit()

        // 基准与当前：每周 1 次，480 分钟
        baselinePrefs.edit()
            .putInt("weekly_privilege_limit", 1)
            .putInt("weekly_privilege_duration_limit", 480)
            .commit()
        CreditManager.setWeeklyPrivilegeLimit(context, 1, 480)
        CreditManager.clearPendingWeeklyPrivilegeLimit(context)

        // 备份中是不利修改：每周 3 次，600 分钟
        val backupJson = JSONObject().apply {
            put("type", "decikeeper_settings_backup")
            put("schemaVersion", 2)
            put("preferenceFiles", JSONArray().apply {
                put(JSONObject().apply {
                    put("name", "decikeeper_ledger")
                    put("entries", JSONObject().apply {
                        put("weekly_privilege_count_limit_v2", JSONObject().apply { put("type", "Int"); put("value", 3) })
                        put("weekly_privilege_duration_limit_v2", JSONObject().apply { put("type", "Int"); put("value", 600) })
                    })
                })
            })
        }.toString()

        SettingsBackupManager.applyBackupJson(
            context,
            backupJson,
            listOf(SettingsBackupManager.BackupCategory.GLOBAL_PARAMS)
        )

        // 验证：当前活跃上限不得立即变宽，仍为 1 次 / 480 分钟
        assertEquals(1, CreditManager.getWeeklyPrivilegeLimit(context))
        assertEquals(480, CreditManager.getWeeklyPrivilegeDurationLimit(context))

        // 验证：进入次周待生效 Pending
        assertTrue("不利特权上限变更必须进入 Pending", CreditManager.hasPendingWeeklyPrivilegeLimit(context))
        assertEquals(3, CreditManager.getPendingWeeklyPrivilegeLimit(context))
        assertEquals(600, CreditManager.getPendingWeeklyPrivilegeDurationLimit(context))
        assertTrue("生效时间必须指向未来的下周一", CreditManager.getPendingWeeklyPrivilegeEffectiveTime(context) > System.currentTimeMillis())
    }

    @Test
    fun testWeeklyPrivilegeLimitImport_favorableDecreaseIsImmediate_whenNotInAdaptation() {
        val baselinePrefs = context.getSharedPreferences("decikeeper_rules_baseline", Context.MODE_PRIVATE)

        // 结束适应期
        context.getSharedPreferences("decikeeper_ledger", Context.MODE_PRIVATE)
            .edit()
            .putLong("first_setup_timestamp_v2", 1L)
            .putBoolean("has_manually_ended_adaptation", true)
            .commit()

        // 基准与当前：每周 3 次，600 分钟
        baselinePrefs.edit()
            .putInt("weekly_privilege_limit", 3)
            .putInt("weekly_privilege_duration_limit", 600)
            .commit()
        CreditManager.setWeeklyPrivilegeLimit(context, 3, 600)

        // 备份中是有利更严修改：每周 1 次，480 分钟
        val backupJson = JSONObject().apply {
            put("type", "decikeeper_settings_backup")
            put("schemaVersion", 2)
            put("preferenceFiles", JSONArray().apply {
                put(JSONObject().apply {
                    put("name", "decikeeper_ledger")
                    put("entries", JSONObject().apply {
                        put("weekly_privilege_count_limit_v2", JSONObject().apply { put("type", "Int"); put("value", 1) })
                        put("weekly_privilege_duration_limit_v2", JSONObject().apply { put("type", "Int"); put("value", 480) })
                    })
                })
            })
        }.toString()

        SettingsBackupManager.applyBackupJson(
            context,
            backupJson,
            listOf(SettingsBackupManager.BackupCategory.GLOBAL_PARAMS)
        )

        // 验证：调严立即生效
        assertEquals(1, CreditManager.getWeeklyPrivilegeLimit(context))
        assertEquals(480, CreditManager.getWeeklyPrivilegeDurationLimit(context))
        assertFalse("有利变更不应保留 Pending", CreditManager.hasPendingWeeklyPrivilegeLimit(context))
    }

    @Test
    fun testRestUsageRecord_unionMerge_preservesLocalRecordsWithoutWiping() {
        val now = System.currentTimeMillis()
        // 本地先有一条特权流水记录
        val localRecord = RestStatsRecorder.record(
            context = context,
            type = "PRIVILEGE",
            typeName = "周特权",
            reason = "临时紧急用",
            requestedMinutes = 30.0,
            actualMinutes = 30.0,
            costMinutes = 30.0,
            costCount = 1.0,
            balanceMinutes = 450.0,
            balanceCount = 0,
            timestamp = now
        )

        // 备份中有一条历史普通休息流水记录
        val backupRecordObj = JSONObject().apply {
            put("id", "rest_backup_historical_1")
            put("timestamp", now - 60_000L)
            put("dateLabel", "2026-09-20 10:00")
            put("type", "NORMAL")
            put("typeName", "正常休息")
            put("reason", "番茄钟")
            put("requestedMinutes", 15.0)
            put("actualMinutes", 15.0)
            put("costMinutes", 15.0)
            put("costCount", 0.0)
            put("balanceMinutes", 0.0)
            put("balanceCount", 0)
        }

        val backupJson = JSONObject().apply {
            put("type", "decikeeper_settings_backup")
            put("schemaVersion", 2)
            put("preferenceFiles", JSONArray())
            put("restUsageRecordsVersion", 1)
            put("restUsageRecords", JSONArray().apply { put(backupRecordObj) })
            put("restUsageRecordCount", 1)
        }.toString()

        SettingsBackupManager.applyBackupJson(
            context,
            backupJson,
            listOf(SettingsBackupManager.BackupCategory.LEDGER)
        )

        val allRecords = RestStatsRecorder.getAllRecords(context)
        val ids = allRecords.map { it.id }
        assertTrue("本地特权流水记录必须被保留", ids.contains(localRecord.id))
        assertTrue("备份中的流水记录必须被合并入库", ids.contains("rest_backup_historical_1"))
    }
}
