package com.decikeeper.app.manager

import android.content.Context
import com.decikeeper.app.data.questionbank.CustomQuestion
import com.decikeeper.app.data.questionbank.CustomQuestionBank
import com.decikeeper.app.manager.questionbank.CustomQuestionBankManager
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File

@RunWith(RobolectricTestRunner::class)
class AutoBackupManagerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var context: Context

    @Before
    fun setup() {
        context = RuntimeEnvironment.getApplication()
    }

    @Test
    fun testSnapshotDualGenerationRotation() {
        val backupDir = tempFolder.newFolder("auto_backup")
        val snapshot1 = """{"type":"decikeeper_settings_backup","schemaVersion":2,"test":1}"""
        val snapshot2 = """{"type":"decikeeper_settings_backup","schemaVersion":2,"test":2}"""

        // 第一次写入快照
        AutoBackupManager.writeSnapshotToDir(backupDir, snapshot1)
        val currentFile = File(backupDir, AutoBackupManager.BACKUP_FILE_NAME)
        val prevFile = File(backupDir, AutoBackupManager.BACKUP_PREV_FILE_NAME)

        assertTrue(currentFile.exists())
        assertFalse(prevFile.exists())
        assertEquals(snapshot1, currentFile.readText())

        // 第二次写入快照：旧文件轮转到 _prev.json
        AutoBackupManager.writeSnapshotToDir(backupDir, snapshot2)
        assertTrue(currentFile.exists())
        assertTrue(prevFile.exists())
        assertEquals(snapshot2, currentFile.readText())
        assertEquals(snapshot1, prevFile.readText())
    }

    @Test
    fun testPerformBackup_onlyWritesInternalDir_noPublicWrite() {
        val result = AutoBackupManager.performBackup(context)
        assertTrue("Internal snapshot should be written", result.internalWritten)
        assertEquals("No errors during internal backup", 0, result.errorCount)
        assertTrue(result.success)

        val internalFile = File(File(context.filesDir, AutoBackupManager.INTERNAL_BACKUP_DIR), AutoBackupManager.BACKUP_FILE_NAME)
        assertTrue("Internal backup file must exist", internalFile.exists())
        assertTrue("Internal backup file must not be empty", internalFile.length() > 0)

        // 验证公共 Documents 目录不会被自动写入
        val publicDir = File(
            android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOCUMENTS),
            "DeciKeeper"
        )
        assertFalse("Public documents directory must NOT be written by auto backup", publicDir.exists())
    }

    @Test
    fun testIsFreshInstall() {
        // 1. 全新未配置状态
        context.getSharedPreferences("decikeeper_rules", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("decikeeper_ledger", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("alarm_prefs", Context.MODE_PRIVATE).edit().clear().commit()
        assertTrue(AutoBackupManager.isFreshInstall(context))

        // 2. 写入规则后不再属于全新安装
        context.getSharedPreferences("decikeeper_rules", Context.MODE_PRIVATE)
            .edit()
            .putString("segments_json", "[]")
            .commit()
        assertFalse(AutoBackupManager.isFreshInstall(context))

        // 3. 清理规则但有 ledger
        context.getSharedPreferences("decikeeper_rules", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("decikeeper_ledger", Context.MODE_PRIVATE)
            .edit()
            .putLong("first_setup_timestamp_v2", 123456L)
            .commit()
        assertFalse(AutoBackupManager.isFreshInstall(context))
    }

    @Test
    fun testSilentRestoreFromDirectory() {
        val backupDir = tempFolder.newFolder("test_restore")
        val sampleJson = SettingsBackupManager.buildBackupJson(context, SettingsBackupManager.BackupCategory.ALL)

        // 模拟外部快照文件存在
        File(backupDir, AutoBackupManager.BACKUP_FILE_NAME).writeText(sampleJson)

        val restored = AutoBackupManager.restoreFromDirectory(context, backupDir)
        assertTrue(restored)
    }

    @Test
    fun corruptCurrentSnapshot_fallsBackToPreviousSnapshot() {
        val backupDir = tempFolder.newFolder("fallback_restore")
        val validJson = SettingsBackupManager.buildBackupJson(
            context,
            SettingsBackupManager.BackupCategory.ALL
        )
        File(backupDir, AutoBackupManager.BACKUP_FILE_NAME).writeText("{not-json")
        File(backupDir, AutoBackupManager.BACKUP_PREV_FILE_NAME).writeText(validJson)

        assertTrue(AutoBackupManager.restoreFromDirectory(context, backupDir))
    }

    @Test
    fun malformedPreferenceEntry_isRejectedBeforeAnyPreferenceWrite() {
        val target = context.getSharedPreferences("decikeeper_rules", Context.MODE_PRIVATE)
        target.edit().putString("sentinel", "keep").commit()
        val malformed = """
            {"type":"decikeeper_settings_backup","schemaVersion":2,
             "preferenceFiles":[{"name":"decikeeper_rules","entries":
             {"broken":{"type":"Unknown","value":"x"}}}]}
        """.trimIndent()

        assertThrows(Exception::class.java) {
            SettingsBackupManager.applyBackupJson(context, malformed)
        }
        assertEquals("keep", target.getString("sentinel", null))
    }

    @Test
    fun testBackupCategoriesIncludeCountAndLedgers() {
        val dateStr = "20260907"
        val ledgerPrefs = context.getSharedPreferences("decikeeper_ledger", Context.MODE_PRIVATE)
        ledgerPrefs.edit()
            .putInt("count_usage_half_units_v2_${dateStr}_09:00-18:00", 3)
            .putInt("count_borrow_half_units_v2_${dateStr}_09:00-18:00", 2)
            .commit()

        val ruleProtPrefs = context.getSharedPreferences("rule_protection_ledger", Context.MODE_PRIVATE)
        ruleProtPrefs.edit().putInt("rule_change_streak", 7).commit()

        val alarmStatsPrefs = context.getSharedPreferences("alarm_stats_ledger", Context.MODE_PRIVATE)
        alarmStatsPrefs.edit().putInt("alarm_wake_count", 15).commit()

        val statsPrefs = context.getSharedPreferences("statistics_prefs", Context.MODE_PRIVATE)
        statsPrefs.edit().putString("app_category_overrides", """{"com.test.app":"game"}""").commit()

        val json = SettingsBackupManager.buildBackupJson(context, listOf(SettingsBackupManager.BackupCategory.LEDGER))

        assertTrue(json.contains("count_usage_half_units_v2"))
        assertTrue(json.contains("count_borrow_half_units_v2"))
        assertTrue(json.contains("rule_protection_ledger"))
        assertTrue(json.contains("alarm_stats_ledger"))
        assertTrue(json.contains("statistics_prefs"))
        assertTrue(json.contains("app_category_overrides"))
    }

    @Test
    fun testCustomQuestionBanksExportAndRestore() {
        val bank = CustomQuestionBank(
            id = "bank_test_123",
            title = "考研政治核心考点",
            description = "精选题库",
            questions = listOf(
                CustomQuestion(
                    id = "q_1",
                    bankId = "bank_test_123",
                    stem = "马克思主义哲学创立的直接理论来源是？",
                    options = listOf("黑格尔辩证法与费尔巴哈唯物主义", "英国古典政治经济学", "法国空想社会主义", "康德批判哲学"),
                    answer = "A",
                    explanation = "黑格尔辩证法和费尔巴哈唯物主义是马克思主义哲学的直接理论来源。"
                )
            )
        )
        CustomQuestionBankManager.saveBank(context, bank)

        // 导出 ALARM 分类备份
        val json = SettingsBackupManager.buildBackupJson(context, listOf(SettingsBackupManager.BackupCategory.ALARM))
        assertTrue(json.contains("customQuestionBanks"))
        assertTrue(json.contains("考研政治核心考点"))

        // 清空本地题库
        CustomQuestionBankManager.deleteBank(context, "bank_test_123")
        assertNull(CustomQuestionBankManager.getBank(context, "bank_test_123"))

        // 应用导入
        val result = SettingsBackupManager.applyBackupJson(context, json, listOf(SettingsBackupManager.BackupCategory.ALARM))
        assertTrue(result.fileCount >= 0)

        // 验证题库成功恢复
        val restoredBank = CustomQuestionBankManager.getBank(context, "bank_test_123")
        assertNotNull(restoredBank)
        assertEquals("考研政治核心考点", restoredBank!!.title)
        assertEquals(1, restoredBank.questions.size)
        assertEquals("A", restoredBank.questions[0].answer)
    }
}
