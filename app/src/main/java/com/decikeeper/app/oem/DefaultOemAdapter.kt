package com.decikeeper.app.oem

import android.content.Context
import android.content.Intent
import android.provider.Settings
import com.decikeeper.app.manager.SettingsNavigator

/**
 * 通用/未深度测试品牌的兜底适配器。
 * 核心原则：绝不向用户展示未经验证的机型专有特性（设备管理器、隐私空间等均默认隐藏），
 * 仅保留标准 Android 通用设置跳转与白名单请求。
 */
open class DefaultOemAdapter : OemAdapter {

    override val brandName: String = "default"

    override fun isDeviceAdminRecommended(): Boolean = false

    override fun isPrivacySpaceGuardSupported(): Boolean = false

    override fun getProtectionHelpNoteRes(): Int? = null

    override fun getBatteryOptimizationLabel(): String = "允许后台耗电/行为"

    override fun getAutostartLabel(): String = "自启动权限"

    override fun getKeepAliveTips(): String =
        "请在系统设置中找到「耗电管理」「电池优化」或类似选项，并允许应用后台运行"

    override fun getSleepOptimizationTips(): String =
        "请在系统电池或省电设置中，关闭夜间「睡眠待机优化」"

    override fun openAutostartSettings(context: Context): Boolean =
        SettingsNavigator.openAppDetails(context)

    override fun openAssociateStartSettings(context: Context): Boolean =
        SettingsNavigator.openAppDetails(context)

    override fun openBatteryKeepAliveSettings(context: Context): Boolean {
        val candidates = listOf(
            Intent(Intent.ACTION_POWER_USAGE_SUMMARY),
            Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        )
        for (intent in candidates) {
            if (SettingsNavigator.startActivitySafely(context, intent)) return true
        }
        if (SettingsNavigator.openBatteryOptimization(context)) return true
        return SettingsNavigator.openAppDetails(context)
    }

    override fun openSleepOptimizationSettings(context: Context): Boolean {
        val candidates = listOf(
            Intent("android.settings.BATTERY_SAVER_SETTINGS"),
            Intent(Intent.ACTION_POWER_USAGE_SUMMARY),
            Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        )
        for (intent in candidates) {
            if (SettingsNavigator.startActivitySafely(context, intent)) return true
        }
        if (SettingsNavigator.openBatteryOptimization(context)) return true
        return SettingsNavigator.openAppDetails(context)
    }
}
