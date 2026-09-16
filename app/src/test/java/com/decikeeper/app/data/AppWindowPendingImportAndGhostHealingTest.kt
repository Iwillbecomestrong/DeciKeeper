package com.decikeeper.app.data

import android.content.Context
import com.decikeeper.app.manager.AppWindowManager
import com.decikeeper.app.manager.ChangeLogManager
import com.decikeeper.app.manager.CreditManager
import com.decikeeper.app.manager.SettingsBackupManager
import com.decikeeper.app.manager.TimeRuleManager
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AppWindowPendingImportAndGhostHealingTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("app_window_rules", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("app_window_pending", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("app_window_baseline", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("decikeeper_rules", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("decikeeper_rules_pending", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("decikeeper_changelog", Context.MODE_PRIVATE).edit().clear().commit()
        AppWindowManager.invalidateCache()
    }

    @Test
    fun testLegacyRulesJsonKeyInPending_canBeReadAndMigrated() {
        val sampleRuleJson = """
            [{"id":"rule-1","name":"游戏时间窗","startTime":1080,"endTime":1200,"packageNames":["com.tencent.tmgp.sgame"],"isEnabled":true,"daysOfWeek":[1,2,3,4,5,6,7]}]
        """.trimIndent()

        // 模拟外部或旧导入将 key 存为了 rules_json（而非 pending_rules_json）
        val prefs = context.getSharedPreferences("app_window_pending", Context.MODE_PRIVATE)
        prefs.edit()
            .putString("rules_json", sampleRuleJson)
            .putBoolean("has_pending_changes", true)
            .putLong("effective_timestamp", System.currentTimeMillis() + 3600000)
            .commit()

        // 读取 pending 规则
        val pendingRules = AppWindowManager.getPendingRules(context)
        assertNotNull("应能容错读取到 rules_json 中的待生效规则", pendingRules)
        assertEquals(1, pendingRules!!.size)
        assertEquals("游戏时间窗", pendingRules[0].name)

        // 验证已自动迁移写回 pending_rules_json 标准键
        val migratedJson = prefs.getString("pending_rules_json", null)
        assertNotNull("读取后应自愈写回 pending_rules_json", migratedJson)
        assertTrue(migratedJson!!.contains("游戏时间窗"))
    }

    @Test
    fun testGhostPendingFlag_autoHealed() {
        // 模拟幽灵 pending 状态：flag 为 true，但 SharedPreferences 中没有任何待生效规则
        val prefs = context.getSharedPreferences("app_window_pending", Context.MODE_PRIVATE)
        prefs.edit()
            .putBoolean("has_pending_changes", true)
            .putLong("effective_timestamp", System.currentTimeMillis() + 3600000)
            .commit()

        // 此时 hasPendingChanges 应自动识别为幽灵状态，执行自愈清理并返回 false
        val hasPending = AppWindowManager.hasPendingChanges(context)
        assertFalse("幽灵 pending 状态应返回 false", hasPending)
        assertFalse("自愈后 SharedPreferences 中的 has_pending_changes 标记应被重置为 false",
            prefs.getBoolean("has_pending_changes", false))
    }

    @Test
    fun testSettingsBackupManager_importAppWindowRulesInNonAdaptation_allNextDay_writesPendingAndChangeLog() {
        // 保证当前处于非适应期，且策略为统一延后
        CreditManager.markAdaptationEnded(context)
        assertFalse("必须处于非适应期", TimeRuleManager.isInAdaptationPeriod(context))
        com.decikeeper.app.manager.EffectiveActivationPolicyManager.setPolicy(
            context,
            com.decikeeper.app.manager.EffectiveActivationPolicyManager.Policy.ALL_NEXT_DAY
        )

        val ruleArray = JSONArray().apply {
            put(JSONObject().apply {
                put("id", "rule-imported")
                put("name", "学习时间窗")
                put("startTime", 480)
                put("endTime", 600)
                put("packageNames", JSONArray(listOf("com.netease.youdao")))
                put("isEnabled", true)
                put("daysOfWeek", JSONArray(listOf(1, 2, 3, 4, 5)))
            })
        }

        val backupJson = JSONObject().apply {
            put("type", "decikeeper_settings_backup")
            put("schemaVersion", 2)
            put("appPackage", "com.decikeeper.app")
            put("exportedAt", System.currentTimeMillis())
            put("preferenceFiles", JSONArray().apply {
                put(JSONObject().apply {
                    put("name", "app_window_rules")
                    put("entries", JSONObject().apply {
                        put("rules_json", JSONObject().apply {
                            put("type", "String")
                            put("value", ruleArray.toString())
                        })
                    })
                })
            })
        }.toString()

        // 导入应用时间窗
        val result = SettingsBackupManager.applyBackupJson(
            context,
            backupJson,
            listOf(SettingsBackupManager.BackupCategory.APP_WINDOWS)
        )
        assertTrue(result.fileCount > 0)

        // 统一延后策略下：验证写入了 app_window_pending，且 key 是 pending_rules_json
        val pendingPrefs = context.getSharedPreferences("app_window_pending", Context.MODE_PRIVATE)
        val pendingJson = pendingPrefs.getString("pending_rules_json", null)
        assertNotNull("统一延后下导入应用时间窗必须存入 pending_rules_json 键", pendingJson)
        assertTrue(pendingJson!!.contains("学习时间窗"))

        // 验证 AppWindowManager 可以正常读取待生效规则
        val pendingRules = AppWindowManager.getPendingRules(context)
        assertNotNull(pendingRules)
        assertEquals(1, pendingRules!!.size)
        assertEquals("学习时间窗", pendingRules[0].name)

        // 验证 ChangeLog 记录存在
        val log = ChangeLogManager.getFormattedLogForCategory(context, "app_window_rules")
        assertTrue("导入后应生成待生效变更日志", log.contains("导入应用时间窗规则"))
    }

    @Test
    fun testSettingsBackupManager_importAppWindowRulesInFavorableImmediate_emptyBaseline_appliesImmediately() {
        // 非适应期 + 宽严有别策略
        CreditManager.markAdaptationEnded(context)
        com.decikeeper.app.manager.EffectiveActivationPolicyManager.setPolicy(
            context,
            com.decikeeper.app.manager.EffectiveActivationPolicyManager.Policy.FAVORABLE_IMMEDIATE
        )

        val ruleArray = JSONArray().apply {
            put(JSONObject().apply {
                put("id", "rule-first")
                put("name", "首个管控时间窗")
                put("startTime", 480)
                put("endTime", 600)
                put("packageNames", JSONArray(listOf("com.example.app")))
                put("isEnabled", true)
                put("daysOfWeek", JSONArray(listOf(1, 2, 3, 4, 5, 6, 7)))
            })
        }

        val backupJson = JSONObject().apply {
            put("type", "decikeeper_settings_backup")
            put("schemaVersion", 2)
            put("appPackage", "com.decikeeper.app")
            put("exportedAt", System.currentTimeMillis())
            put("preferenceFiles", JSONArray().apply {
                put(JSONObject().apply {
                    put("name", "app_window_rules")
                    put("entries", JSONObject().apply {
                        put("rules_json", JSONObject().apply {
                            put("type", "String")
                            put("value", ruleArray.toString())
                        })
                    })
                })
            })
        }.toString()

        val result = SettingsBackupManager.applyBackupJson(
            context,
            backupJson,
            listOf(SettingsBackupManager.BackupCategory.APP_WINDOWS)
        )
        assertTrue(result.fileCount > 0)

        // 基准为空时首批导入属于纯加严，应当场立即生效写入 active
        val activeRules = AppWindowManager.getRules(context)
        assertEquals(1, activeRules.size)
        assertEquals("首个管控时间窗", activeRules[0].name)

        // pending 应当为空，且无待生效标记
        assertFalse(AppWindowManager.hasPendingChanges(context))
        val pendingPrefs = context.getSharedPreferences("app_window_pending", Context.MODE_PRIVATE)
        assertFalse(pendingPrefs.getBoolean("has_pending_changes", false))

        // 基准应当已被同步建立
        val baselineRules = AppWindowManager.getBaselineRules(context)
        assertEquals(1, baselineRules.size)
        assertEquals("首个管控时间窗", baselineRules[0].name)

        // ChangeLog 记录立即生效日志（有利改动不进待生效列表，需在全量日志中查验）
        val allLogs = ChangeLogManager.getAllEntries(context).filter { it.category == "app_window_rules" }
        assertTrue("应记录加严规则立即生效日志", allLogs.any { it.description.contains("加严规则已立即生效") })
    }

    @Test
    fun testSettingsBackupManager_importAppWindowRulesInFavorableImmediate_adverseChange_routesToPending() {
        CreditManager.markAdaptationEnded(context)
        com.decikeeper.app.manager.EffectiveActivationPolicyManager.setPolicy(
            context,
            com.decikeeper.app.manager.EffectiveActivationPolicyManager.Policy.FAVORABLE_IMMEDIATE
        )

        // 预设本地已有时间窗与基准：08:00 - 10:00 (480 - 600)
        val initialRule = AppWindowManager.AppWindowRule(
            id = "rule-1",
            name = "早间管控",
            startTime = 480,
            endTime = 600,
            packageNames = mutableSetOf("com.example.app"),
            isEnabled = true,
            daysOfWeek = (1..7).toSet()
        )
        val activePrefs = context.getSharedPreferences("app_window_rules", Context.MODE_PRIVATE)
        activePrefs.edit().putString("rules_json", JSONArray().put(JSONObject().apply {
            put("id", "rule-1")
            put("name", "早间管控")
            put("startTime", 480)
            put("endTime", 600)
            put("packageNames", JSONArray(listOf("com.example.app")))
            put("isEnabled", true)
            put("daysOfWeek", JSONArray(listOf(1, 2, 3, 4, 5, 6, 7)))
        }).toString()).commit()
        AppWindowManager.saveBaseline(context)
        AppWindowManager.invalidateCache()

        // 模拟导入一个延长时间窗（放宽：480 - 720，即 08:00 - 12:00）的备份
        val relaxedRuleArray = JSONArray().apply {
            put(JSONObject().apply {
                put("id", "rule-1")
                put("name", "早间管控")
                put("startTime", 480)
                put("endTime", 720) // 放宽了 2 小时
                put("packageNames", JSONArray(listOf("com.example.app")))
                put("isEnabled", true)
                put("daysOfWeek", JSONArray(listOf(1, 2, 3, 4, 5, 6, 7)))
            })
        }

        val backupJson = JSONObject().apply {
            put("type", "decikeeper_settings_backup")
            put("schemaVersion", 2)
            put("appPackage", "com.decikeeper.app")
            put("exportedAt", System.currentTimeMillis())
            put("preferenceFiles", JSONArray().apply {
                put(JSONObject().apply {
                    put("name", "app_window_rules")
                    put("entries", JSONObject().apply {
                        put("rules_json", JSONObject().apply {
                            put("type", "String")
                            put("value", relaxedRuleArray.toString())
                        })
                    })
                })
            })
        }.toString()

        val result = SettingsBackupManager.applyBackupJson(
            context,
            backupJson,
            listOf(SettingsBackupManager.BackupCategory.APP_WINDOWS)
        )
        assertTrue(result.fileCount > 0)

        // 放宽变动必须被拦截并转入 pending 延迟生效
        val pendingRules = AppWindowManager.getPendingRules(context)
        assertNotNull("放宽项必须存入 pending", pendingRules)
        assertEquals(1, pendingRules!!.size)
        assertEquals(720, pendingRules[0].endTime)

        // active 规则应保持原有严格基准未被放宽
        val activeRules = AppWindowManager.getRules(context)
        assertEquals(1, activeRules.size)
        assertEquals(600, activeRules[0].endTime)

        // pending 标志必须置为 true
        assertTrue(AppWindowManager.hasPendingChanges(context))
    }

    @Test
    fun testPrivacyConsentLayoutAndAgreementWording_noSelfDiscipline() {
        val agreementFile = File("src/main/res/raw/user_agreement.txt")
        if (agreementFile.exists()) {
            val text = agreementFile.readText()
            assertFalse("用户协议不应出现自律说教字眼", text.contains("自律"))
        }

        val policyFile = File("src/main/res/raw/privacy_policy.txt")
        if (policyFile.exists()) {
            val text = policyFile.readText()
            assertFalse("隐私政策不应出现自律说教字眼", text.contains("自律"))
        }

        val layoutFile = File("src/main/res/layout/activity_privacy_consent.xml")
        if (layoutFile.exists()) {
            val text = layoutFile.readText()
            assertTrue("首屏应包含决定保护软件表述", text.contains("决定保护软件"))
            assertFalse("首屏卡片大标题不应包含自律字眼", text.contains("纯单机自律软件"))
        }
    }
}
