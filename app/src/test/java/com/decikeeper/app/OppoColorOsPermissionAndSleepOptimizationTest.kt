package com.decikeeper.app

import com.decikeeper.app.manager.SettingsNavigator
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class OppoColorOsPermissionAndSleepOptimizationTest {

    private fun source(path: String): String {
        val cwd = File(System.getProperty("user.dir") ?: ".")
        val candidates = listOf(
            File(cwd, path),
            File(cwd, "app/$path"),
            File(cwd.parentFile ?: cwd, path),
            File(cwd.parentFile ?: cwd, "app/$path")
        )
        return candidates.firstOrNull { it.isFile }?.readText()
            ?: error("Cannot locate $path from ${cwd.absolutePath}")
    }

    @Test
    fun `alarmFragment shows sleep standby optimization for all devices without isVivo barrier`() {
        val fragment = source("src/main/java/com/decikeeper/app/ui/AlarmFragment.kt")

        assertFalse(
            "AlarmFragment must not restrict sleep standby optimization to only isVivo",
            fragment.contains("if (isVivo) {\n            val rowSleep = LinearLayout(ctx)")
        )
        assertTrue(
            "AlarmFragment must support generic sleep_standby_confirmed key",
            fragment.contains("sleep_standby_confirmed")
        )
        assertTrue(
            "AlarmFragment must maintain backward compatibility for vivo_sleep_standby_confirmed",
            fragment.contains("vivo_sleep_standby_confirmed")
        )
        assertTrue(
            "AlarmFragment must provide tip explaining sleep standby optimization",
            fragment.contains("睡眠待机优化")
        )
    }

    @Test
    fun `homeFragment adapts autostart and battery optimization labels with SettingsNavigator`() {
        val homeFragment = source("src/main/java/com/decikeeper/app/ui/HomeFragment.kt")

        assertTrue(
            "HomeFragment must use autostart label from SettingsNavigator",
            homeFragment.contains("SettingsNavigator.getAutostartLabel()")
        )
        assertTrue(
            "HomeFragment must adapt ColorOS startup app list components",
            homeFragment.contains("com.coloros.safecenter.startupapp.StartupAppListActivity")
        )
        assertTrue(
            "HomeFragment must invoke SettingsNavigator.openBatteryKeepAliveSettings",
            homeFragment.contains("SettingsNavigator.openBatteryKeepAliveSettings(ctx)")
        )
    }

    @Test
    fun `settingsNavigator provides dedicated OPPO hints and labels`() {
        val oppoHint = "请在「应用详情」中找到「耗电管理/电池」，并勾选「完全允许后台行为」"
        val navigatorSource = source("src/main/java/com/decikeeper/app/manager/SettingsNavigator.kt")

        assertTrue(
            "SettingsNavigator must include OPPO specific hint directing to AppDetails -> Battery Management",
            navigatorSource.contains(oppoHint)
        )
        assertTrue(
            "SettingsNavigator must define getBatteryOptimizationLabel",
            navigatorSource.contains("fun getBatteryOptimizationLabel(): String")
        )
        assertTrue(
            "SettingsNavigator must define getAutostartLabel",
            navigatorSource.contains("fun getAutostartLabel(): String")
        )
        assertTrue(
            "SettingsNavigator must define openAssociateStartSettings",
            navigatorSource.contains("fun openAssociateStartSettings(context: Context): Boolean")
        )
        assertTrue(
            "SettingsNavigator must define openApplicationSettings",
            navigatorSource.contains("fun openApplicationSettings(context: Context): Boolean")
        )
        assertTrue(
            "SettingsNavigator must define getOppoWindowTips for floating window keepalive",
            navigatorSource.contains("fun getOppoWindowTips(): String")
        )
    }
}
