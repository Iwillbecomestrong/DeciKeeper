package com.decikeeper.app.oem

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import com.decikeeper.app.R
import com.decikeeper.app.manager.SettingsNavigator

/**
 * OPPO / 一加 / 真我 (ColorOS / Oplus) 深度适配器。
 * 特性聚焦：支持并推荐设备管理器防卸载锁；默认隐藏隐私空间保护。
 */
class OppoOemAdapter : DefaultOemAdapter() {

    override val brandName: String = "oppo"

    override fun isDeviceAdminRecommended(): Boolean = true

    override fun isPrivacySpaceGuardSupported(): Boolean = false

    override fun getProtectionHelpNoteRes(): Int? = R.string.settings_protection_help_oppo_note

    override fun getBatteryOptimizationLabel(): String = "完全允许后台行为"

    override fun getAutostartLabel(): String = "自启动"

    override fun getKeepAliveTips(): String =
        "请在「应用详情」中找到「耗电管理/电池」，并勾选「完全允许后台行为」"

    override fun getSleepOptimizationTips(): String =
        "请在「电池 → 高级设置」或「省电设置」中关闭「睡眠待机优化」"

    override fun openAutostartSettings(context: Context): Boolean {
        val candidates = listOf(
            Intent().setComponent(ComponentName("com.oplus.securitypermission", "com.oplus.securitypermission.startup.StartupAppListActivity")),
            Intent().setComponent(ComponentName("com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity")),
            Intent().setComponent(ComponentName("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity")),
            Intent().setComponent(ComponentName("com.coloros.safecenter", "com.coloros.safecenter.permission.startupInit.StartupNormalAppListActivity")),
            Intent().setComponent(ComponentName("com.oplus.securitypermission", "com.oplus.securitypermission.startup.AutoBootActivity")),
            Intent().setComponent(ComponentName("com.coloros.safecenter", "com.coloros.startupdetails.StartupCombineActivity")),
            Intent().setComponent(ComponentName("com.coloros.oppoguardelf", "com.coloros.startupdetails.StartupCombineActivity")),
            Intent().setComponent(ComponentName("com.oplus.battery", "com.oplus.battery.batteryoptimization.BatteryOptimizationActivity")),
            Intent(Settings.ACTION_APPLICATION_SETTINGS),
            Intent("android.settings.APPLICATION_SETTINGS"),
            Intent().setComponent(ComponentName("com.android.settings", "com.android.settings.Settings\$ManageApplicationsActivity")),
            Intent().setComponent(ComponentName("com.android.settings", "com.android.settings.Settings\$AppAndNotificationDashboardActivity")),
            Intent().setComponent(ComponentName("com.oplus.settings", "com.oplus.settings.Settings\$AppAndNotificationDashboardActivity"))
        )
        for (intent in candidates) {
            if (SettingsNavigator.startActivitySafely(context, intent)) return true
        }
        return SettingsNavigator.openAppDetails(context)
    }

    override fun openAssociateStartSettings(context: Context): Boolean {
        val candidates = listOf(
            Intent().setComponent(ComponentName("com.oplus.securitypermission", "com.oplus.securitypermission.startup.AssociateStartupAppListActivity")),
            Intent().setComponent(ComponentName("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.AssociateStartupAppListActivity")),
            Intent().setComponent(ComponentName("com.oplus.securitypermission", "com.oplus.securitypermission.startup.AssociateStartupActivity")),
            Intent().setComponent(ComponentName("com.coloros.safecenter", "com.coloros.safecenter.startupapp.AssociateStartupActivity")),
            Intent().setComponent(ComponentName("com.coloros.safecenter", "com.coloros.safecenter.startupapp.AssociateStartupAppListActivity")),
            Intent().setComponent(ComponentName("com.oplus.safecenter", "com.oplus.safecenter.permission.startup.AssociateStartupAppListActivity")),
            Intent(Settings.ACTION_APPLICATION_SETTINGS),
            Intent("android.settings.APPLICATION_SETTINGS"),
            Intent().setComponent(ComponentName("com.android.settings", "com.android.settings.Settings\$ManageApplicationsActivity")),
            Intent().setComponent(ComponentName("com.android.settings", "com.android.settings.Settings\$AppAndNotificationDashboardActivity")),
            Intent().setComponent(ComponentName("com.oplus.settings", "com.oplus.settings.Settings\$AppAndNotificationDashboardActivity"))
        )
        for (intent in candidates) {
            if (SettingsNavigator.startActivitySafely(context, intent)) return true
        }
        return SettingsNavigator.openAppDetails(context)
    }

    override fun openBatteryKeepAliveSettings(context: Context): Boolean {
        // OPPO ColorOS 的「耗电行为控制」位于「应用详情 → 耗电管理」内，直达 App 详情页最稳妥
        return SettingsNavigator.openAppDetails(context)
    }

    override fun openSleepOptimizationSettings(context: Context): Boolean {
        val candidates = listOf(
            Intent().setComponent(ComponentName("com.oplus.battery", "com.oplus.battery.BatteryMainActivity")),
            Intent().setComponent(ComponentName("com.oplus.battery", "com.oplus.battery.batteryoptimization.BatteryOptimizationActivity")),
            Intent().setComponent(ComponentName("com.coloros.oppoguardelf", "com.coloros.powermanager.fuelgaard.PowerSaverModeActivity")),
            Intent().setComponent(ComponentName("com.coloros.powermanager", "com.coloros.powermanager.fuelgaard.PowerSaverModeActivity")),
            Intent("android.settings.BATTERY_SAVER_SETTINGS"),
            Intent(Intent.ACTION_POWER_USAGE_SUMMARY)
        )
        for (intent in candidates) {
            if (SettingsNavigator.startActivitySafely(context, intent)) return true
        }
        return super.openSleepOptimizationSettings(context)
    }
}
