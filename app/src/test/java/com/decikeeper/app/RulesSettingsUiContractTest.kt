package com.decikeeper.app

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RulesSettingsUiContractTest {

    @Test
    fun settingsPageUsesApprovedProtectionHierarchy() {
        val xml = source("app/src/main/res/layout/fragment_rules.xml")

        assertTrue(xml.indexOf("android:text=\"无障碍服务\"") < xml.indexOf("android:text=\"加强约束功能\""))
        assertTrue(
            xml.indexOf("android:text=\"小窗可见性识别与防关权限保护\"") <
                xml.indexOf("android:text=\"隐私空间保护\"")
        )
        assertTrue(
            xml.indexOf("android:text=\"隐私空间保护\"") <
                xml.indexOf("android:text=\"防卸载增强\"")
        )
        assertTrue(
            xml.indexOf("android:text=\"防卸载增强\"") <
                xml.indexOf("android:text=\"闹钟防关机保护\"")
        )
        assertFalse(xml.contains("查看日志"))
        assertEquals(1, Regex("android:text=\"导出日志\"").findAll(xml).count())
    }

    @Test
    fun eachProtectionSwitchHasHelpControlImmediatelyBeforeIt() {
        val xml = source("app/src/main/res/layout/fragment_rules.xml")
        val rows = listOf(
            "switch_accessibility_service",
            "switch_accessibility",
            "switch_privacy_space_guard",
            "switch_uninstall_protection",
            "switch_alarm_power_off_protection"
        )

        rows.forEach { switchId ->
            val switchIndex = xml.indexOf("android:id=\"@+id/$switchId\"")
            assertTrue("missing $switchId", switchIndex >= 0)
            val rowStart = xml.lastIndexOf("<LinearLayout", switchIndex)
            val helpIndex = xml.lastIndexOf("?", switchIndex)
            assertTrue("$switchId needs a help control before its switch", helpIndex > rowStart)
        }
    }

    @Test
    fun recentTaskAndExportLogAreSeparateCards() {
        val xml = source("app/src/main/res/layout/fragment_rules.xml")
        val recentIndex = xml.indexOf("btn_hide_in_recent_tasks_help")
        val logIndex = xml.indexOf("btn_quick_send_logs")
        assertTrue(recentIndex >= 0 && logIndex >= 0)
        val recentCardStart = xml.lastIndexOf("<androidx.cardview.widget.CardView", recentIndex)
        val recentCardEnd = xml.indexOf("</androidx.cardview.widget.CardView>", recentIndex)
        assertTrue(logIndex > recentCardEnd)
        assertTrue(xml.lastIndexOf("<androidx.cardview.widget.CardView", logIndex) > recentCardStart)
    }

    @Test
    fun accessibilityGuideDoesNotOwnBackgroundBatteryRow() {
        val source = source("app/src/main/java/com/decikeeper/app/ui/RulesFragment.kt")
        val start = source.indexOf("private fun showAccessibilityServiceGuideDialog()")
        val end = source.indexOf("private fun setupAlarmPowerOffProtectionSwitch", start)
        val guide = source.substring(start, end)

        assertFalse(guide.contains("btnBgBattery"))
        assertFalse(guide.contains("后台耗电"))
        assertTrue(guide.contains("ASSOC_START_CONFIRMED_KEY"))
    }

    @Test
    fun homePermissionGuideKeepsBatteryBeforeAutoStartAndDropsUsageAndBackgroundPopup() {
        val source = source("app/src/main/java/com/decikeeper/app/ui/HomeFragment.kt")
        assertFalse(source.contains("addItem(\"使用情况访问权限\""))
        assertFalse(source.contains("name = \"后台弹出界面权限\""))
        assertTrue(source.indexOf("addItem(\"后台耗电管理\"") < source.indexOf("name = \"自启动权限\""))
    }

    @Test
    fun timeRulesPageOwnsGlobalBlacklistEntry() {
        val timeLayout = source("app/src/main/res/layout/fragment_time_config.xml")
        val settings = source("app/src/main/res/layout/fragment_rules.xml")
        assertTrue(timeLayout.contains("@+id/btn_global_blacklist"))
        assertFalse(settings.contains("全局黑名单"))
    }

    @Test
    fun timeRulesExposeGlobalParamsAsThirdTab() {
        val source = source("app/src/main/java/com/decikeeper/app/ui/FocusRulesFragment.kt")
        val settings = source("app/src/main/res/layout/fragment_rules.xml")

        assertTrue(source.contains("const val TAB_GLOBAL_PARAMS = 2"))
        assertTrue(source.contains("setText(\"全局参数\")"))
        assertTrue(source.contains("container_global_params"))
        assertTrue(source.contains("GlobalParamsFragment"))
        assertFalse(settings.contains("全局参数"))
    }

    @Test
    fun globalParamsStartAtSameTopInsetAsOtherTimeRulePages() {
        val layout = source("app/src/main/res/layout/activity_global_params_new.xml")
        assertTrue(layout.contains("android:paddingTop=\"0dp\""))
    }

    @Test
    fun alarmGuideOnlyOffersLockScreenActionAfterAccessibilityGate() {
        val source = source("app/src/main/java/com/decikeeper/app/ui/RulesFragment.kt")
        val start = source.indexOf("private fun showAlarmPowerOffProtectionGuideDialog()")
        val end = source.indexOf("private fun showLockScreenSuggestionDialog()", start)
        val guide = source.substring(start, end)

        assertTrue(guide.contains("去开启锁屏显示"))
        assertFalse(guide.contains("btnA11y"))
        assertFalse(guide.contains("cbA11y"))
        assertFalse(guide.contains("openAccessibilitySettings()"))
    }

    @Test
    fun alarmGuideUsesHorizontalPermissionMatrixLikeAccessibilityGuide() {
        val source = source("app/src/main/java/com/decikeeper/app/ui/RulesFragment.kt")
        val start = source.indexOf("private fun showAlarmPowerOffProtectionGuideDialog()")
        val end = source.indexOf("private fun showLockScreenSuggestionDialog()", start)
        val guide = source.substring(start, end)

        assertTrue(guide.contains("orientation = LinearLayout.HORIZONTAL"))
        assertTrue(guide.contains("gravity = Gravity.CENTER_VERTICAL"))
        assertTrue(guide.contains("LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)"))
        assertTrue(guide.contains("lockScreenRow.addView(btnLockScreen)"))
        assertTrue(guide.contains("lockScreenRow.addView(cbLockScreen)"))
    }

    @Test
    fun alarmPermissionGuideRefreshesAfterReturningFromSystemSettings() {
        val source = source("app/src/main/java/com/decikeeper/app/ui/RulesFragment.kt")
        assertTrue(source.contains("alarmPrerequisiteDialogRefresh?.invoke()"))
        assertTrue(source.contains("alarmPrerequisiteDialogRefresh = { updateDialogUi() }"))
    }

    private fun source(path: String): String {
        val cwd = File(System.getProperty("user.dir") ?: ".")
        val candidates = listOf(
            File(cwd, path),
            File(cwd, path.removePrefix("app/")),
            File(cwd.parentFile ?: cwd, path),
            File(cwd.parentFile ?: cwd, path.removePrefix("app/"))
        )
        return candidates.firstOrNull { it.isFile }?.readText()
            ?: error("Cannot locate $path from ${cwd.absolutePath}")
    }
}
