package com.decikeeper.app.manager

import android.content.Context
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
import org.robolectric.annotation.Config

/**
 * 备份导入导出对闹钟相关数据的覆盖：
 * 应急退出额度（合并，只增不减）、应急白名单、新建闹钟默认值，以及运行状态不进出备份。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SettingsBackupAlarmCoverageTest {

    private lateinit var context: Context
    private val alarm = listOf(SettingsBackupManager.BackupCategory.ALARM)

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        listOf(
            AlarmEmergencyExitStore.PREFS_NAME,
            "alarm_challenge_emergency_whitelist",
            "alarm_prefs",
            CreditManager.PREFS_NAME
        ).forEach { context.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit() }
    }

    private fun entry(type: String, value: Any) = JSONObject().apply { put("type", type); put("value", value) }

    private fun backupOf(vararg files: Pair<String, JSONObject>): String = JSONObject().apply {
        put("type", "decikeeper_settings_backup")
        put("schemaVersion", 2)
        put("exportedAt", System.currentTimeMillis())
        put("preferenceFiles", JSONArray().apply {
            files.forEach { (name, entries) ->
                put(JSONObject().apply { put("name", name); put("entries", entries) })
            }
        })
    }.toString()

    private fun currentMonthKey(now: Long): String =
        AlarmEmergencyExitPolicy.monthKey(CreditManager.getBusinessDateStringAt(context, now))

    private fun emergencyEntries(used: Int, monthKey: String, active: Int = 1) = JSONObject().apply {
        put("active", entry("Int", active))
        put("baseline", entry("Int", active))
        put("baseline_day", entry("String", "20000101"))
        put("month_key", entry("String", monthKey))
        put("month_start_config", entry("Int", active))
        put("used_this_month", entry("Int", used))
    }

    @Test
    fun importKeepsUsedEmergencyExitsFromBackupOnFreshInstall() {
        val now = System.currentTimeMillis()
        assertEquals(1, AlarmEmergencyExitStore.remaining(context, now))

        SettingsBackupManager.applyBackupJson(
            context,
            backupOf(AlarmEmergencyExitStore.PREFS_NAME to emergencyEntries(used = 1, monthKey = currentMonthKey(now))),
            alarm
        )

        assertEquals(0, AlarmEmergencyExitStore.remaining(context, now))
    }

    @Test
    fun importNeverLowersLocalUsedCount() {
        val now = System.currentTimeMillis()
        assertTrue(AlarmEmergencyExitStore.consumeOne(context, now))

        SettingsBackupManager.applyBackupJson(
            context,
            backupOf(AlarmEmergencyExitStore.PREFS_NAME to emergencyEntries(used = 0, monthKey = currentMonthKey(now))),
            alarm
        )

        assertEquals(0, AlarmEmergencyExitStore.remaining(context, now))
    }

    @Test
    fun importIgnoresUsedCountFromAnotherMonth() {
        val now = System.currentTimeMillis()

        SettingsBackupManager.applyBackupJson(
            context,
            backupOf(AlarmEmergencyExitStore.PREFS_NAME to emergencyEntries(used = 1, monthKey = "200001")),
            alarm
        )

        assertEquals(1, AlarmEmergencyExitStore.remaining(context, now))
    }

    @Test
    fun importTightenedMonthlyLimitAppliesImmediately() {
        val now = System.currentTimeMillis()

        SettingsBackupManager.applyBackupJson(
            context,
            backupOf(AlarmEmergencyExitStore.PREFS_NAME to emergencyEntries(used = 0, monthKey = currentMonthKey(now), active = 0)),
            alarm
        )

        assertEquals(0, AlarmEmergencyExitStore.load(context, now).active)
        assertEquals(0, AlarmEmergencyExitStore.remaining(context, now))
    }

    @Test
    fun exportCarriesEmergencyExitQuotaAndWhitelist() {
        val now = System.currentTimeMillis()
        AlarmEmergencyExitStore.consumeOne(context, now)
        context.getSharedPreferences("alarm_challenge_emergency_whitelist", Context.MODE_PRIVATE).edit()
            .putString("package_1", "com.android.mms").putString("package_2", "com.android.dialer").commit()

        val json = SettingsBackupManager.buildBackupJson(context, alarm)
        val names = JSONObject(json).getJSONArray("preferenceFiles").let { files ->
            (0 until files.length()).map { files.getJSONObject(it).getString("name") }
        }

        assertTrue(AlarmEmergencyExitStore.PREFS_NAME in names)
        assertTrue("alarm_challenge_emergency_whitelist" in names)
    }

    @Test
    fun importRestoresEmergencyWhitelistPackages() {
        SettingsBackupManager.applyBackupJson(
            context,
            backupOf(
                "alarm_challenge_emergency_whitelist" to JSONObject().apply {
                    put("package_1", entry("String", "com.android.mms"))
                    put("package_2", entry("String", "com.android.dialer"))
                }
            ),
            alarm
        )

        val prefs = context.getSharedPreferences("alarm_challenge_emergency_whitelist", Context.MODE_PRIVATE)
        assertEquals("com.android.mms", prefs.getString("package_1", null))
        assertEquals("com.android.dialer", prefs.getString("package_2", null))
    }

    @Test
    fun alarmCategoryRestoresNewAlarmDefaultsButNotRunningAlarmState() {
        SettingsBackupManager.applyBackupJson(
            context,
            backupOf(
                "alarm_prefs" to JSONObject().apply {
                    put("default_cloze_count", entry("Int", 7))
                    put("default_ring_volume", entry("Int", 55))
                    put("default_auto_escalate", entry("Boolean", true))
                    put("alarm_active", entry("Boolean", true))
                    put("active_alarm_id", entry("Int", 9))
                    put("snooze_used_9", entry("Int", 2))
                    put("alarm_recovery_count_9", entry("Int", 5))
                }
            ),
            alarm
        )

        val prefs = context.getSharedPreferences("alarm_prefs", Context.MODE_PRIVATE)
        assertEquals(7, prefs.getInt("default_cloze_count", -1))
        assertEquals(55, prefs.getInt("default_ring_volume", -1))
        assertTrue(prefs.getBoolean("default_auto_escalate", false))
        assertFalse(prefs.contains("alarm_active"))
        assertFalse(prefs.contains("active_alarm_id"))
        assertFalse(prefs.contains("snooze_used_9"))
        assertFalse(prefs.contains("alarm_recovery_count_9"))
    }

    @Test
    fun fullImportAlsoSkipsRunningAlarmState() {
        SettingsBackupManager.applyBackupJson(
            context,
            backupOf(
                "alarm_prefs" to JSONObject().apply {
                    put("default_cloze_count", entry("Int", 4))
                    put("alarm_active", entry("Boolean", true))
                    put("vibration_start_time", entry("Long", 123L))
                }
            )
        )

        val prefs = context.getSharedPreferences("alarm_prefs", Context.MODE_PRIVATE)
        assertEquals(4, prefs.getInt("default_cloze_count", -1))
        assertFalse(prefs.contains("alarm_active"))
        assertFalse(prefs.contains("vibration_start_time"))
    }

    @Test
    fun exportDoesNotIncludeRunningAlarmState() {
        context.getSharedPreferences("alarm_prefs", Context.MODE_PRIVATE).edit()
            .putBoolean("alarm_active", true)
            .putInt("snooze_used_3", 1)
            .putInt("default_cloze_count", 6)
            .commit()

        val files = JSONObject(SettingsBackupManager.buildBackupJson(context, alarm)).getJSONArray("preferenceFiles")
        val alarmPrefs = (0 until files.length()).map { files.getJSONObject(it) }
            .first { it.getString("name") == "alarm_prefs" }
            .getJSONObject("entries")

        assertTrue(alarmPrefs.has("default_cloze_count"))
        assertFalse(alarmPrefs.has("alarm_active"))
        assertFalse(alarmPrefs.has("snooze_used_3"))
    }

    @Test
    fun fullExportOnlyWritesFilesThatAreImportedMergedOrPendingReview() {
        listOf(
            "privacy_consent_prefs", "accessibility_control_prefs", "anti_uninstall_prefs",
            "reminder_alerts", "decikeeper_ui_theme", AlarmEmergencyExitStore.PREFS_NAME
        ).forEach {
            context.getSharedPreferences(it, Context.MODE_PRIVATE).edit().putString("k", "v").commit()
        }

        val files = JSONObject(SettingsBackupManager.buildBackupJson(context)).getJSONArray("preferenceFiles")
        val names = (0 until files.length()).map { files.getJSONObject(it).getString("name") }.toSet()

        assertFalse("隐私同意不得进备份", "privacy_consent_prefs" in names)
        assertFalse("无障碍状态不得进备份", "accessibility_control_prefs" in names)
        assertFalse("防卸载状态不得进备份", "anti_uninstall_prefs" in names)
        assertFalse("运行状态不得进备份", "reminder_alerts" in names)
        assertTrue("待评审文件仍随全选导出", "decikeeper_ui_theme" in names)
        assertTrue(AlarmEmergencyExitStore.PREFS_NAME in names)
    }
}
