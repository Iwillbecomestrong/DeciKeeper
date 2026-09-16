package com.decikeeper.app.oem

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import com.decikeeper.app.R
import com.decikeeper.app.manager.SettingsNavigator

/**
 * vivo / iQOO (OriginOS / Funtouch OS) 深度适配器。
 * 特性聚焦：支持原子隐私系统拦截；默认隐藏设备管理器。
 */
class VivoOemAdapter : DefaultOemAdapter() {

    override val brandName: String = "vivo"

    override fun isDeviceAdminRecommended(): Boolean = false

    override fun isPrivacySpaceGuardSupported(): Boolean = true

    override fun getProtectionHelpNoteRes(): Int? = R.string.settings_protection_help_vivo_note

    override fun getBatteryOptimizationLabel(): String = "允许后台高耗电"

    override fun getAutostartLabel(): String = "自启动权限"

    override fun getKeepAliveTips(): String =
        "请在系统设置中允许「后台高耗电」，并关闭电池设置中的「睡眠待机优化」"

    override fun getSleepOptimizationTips(): String =
        "请前往：电池 → 省电模式 → 省电管理，关闭「睡眠待机优化」"

    override fun openAutostartSettings(context: Context): Boolean {
        val candidates = listOf(
            Intent().setComponent(ComponentName("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity")),
            Intent().setComponent(ComponentName("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity")),
            Intent().setComponent(ComponentName("com.iqoo.secure", "com.iqoo.secure.MainActivity"))
        )
        for (intent in candidates) {
            if (SettingsNavigator.startActivitySafely(context, intent)) return true
        }
        return SettingsNavigator.openAppDetails(context)
    }

    override fun openAssociateStartSettings(context: Context): Boolean {
        val candidates = listOf(
            Intent().setComponent(ComponentName("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.SoftPermissionDetailActivity")).apply {
                putExtra("packagename", context.packageName)
                putExtra("packageName", context.packageName)
                putExtra("pkg", context.packageName)
            },
            Intent().setComponent(ComponentName("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"))
        )
        for (intent in candidates) {
            if (SettingsNavigator.startActivitySafely(context, intent)) return true
        }
        return SettingsNavigator.openAppDetails(context)
    }

    override fun openBatteryKeepAliveSettings(context: Context): Boolean {
        val candidates = listOf(
            Intent().setComponent(ComponentName("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.HighPowerConsumptionActivity")),
            Intent().setComponent(ComponentName("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.HighPowerConsumptionActivity")),
            Intent().setComponent(ComponentName("com.iqoo.powersaving", "com.iqoo.powersaving.activity.ExcessivePowerDescriptionActivity")).apply {
                putExtra("package_name", context.packageName)
            },
            Intent().setComponent(ComponentName("com.vivo.abe", "com.vivo.applicationbehaviorengine.ui.ExcessivePowerManagerActivity")).apply {
                putExtra("package_name", context.packageName)
            },
            Intent().setComponent(ComponentName("com.iqoo.powersaving", "com.iqoo.powersaving.activity.ExcessivePowerManagerActivity")).apply {
                putExtra("package_name", context.packageName)
            },
            Intent().setComponent(ComponentName("com.iqoo.powersaving", "com.iqoo.powersaving.BackgroundHighUsageActivity")).apply {
                putExtra("package_name", context.packageName)
            }
        )
        for (intent in candidates) {
            if (SettingsNavigator.startActivitySafely(context, intent)) return true
        }
        return super.openBatteryKeepAliveSettings(context)
    }

    override fun openSleepOptimizationSettings(context: Context): Boolean {
        val candidates = listOf(
            Intent().setComponent(ComponentName("com.iqoo.powersaving", "com.iqoo.powersaving.PowerSavingManagerActivity")),
            Intent().setComponent(ComponentName("com.iqoo.powersaving", "com.iqoo.powersaving.UpSlidePowerManagerActivity")),
            Intent().setComponent(ComponentName("com.iqoo.powersaving", "com.iqoo.powersaving.PowerManagerSettingsActivity")),
            Intent().setComponent(ComponentName("com.iqoo.powersaving", "com.iqoo.powersaving.PowerDirectorManagerActivity")),
            Intent().setComponent(ComponentName("com.iqoo.powersaving", "com.iqoo.powersaving.fuelgauge.PowerUsageSummaryActivity")),
            Intent("android.settings.BATTERY_SAVER_SETTINGS"),
            Intent(Intent.ACTION_POWER_USAGE_SUMMARY)
        )
        for (intent in candidates) {
            if (SettingsNavigator.startActivitySafely(context, intent)) return true
        }
        return super.openSleepOptimizationSettings(context)
    }
}
