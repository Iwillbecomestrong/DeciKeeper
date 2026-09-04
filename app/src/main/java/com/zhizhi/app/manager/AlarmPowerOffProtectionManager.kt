/**
 * =================================================================================
 * [Context Header]
 * * @file: AlarmPowerOffProtectionManager.kt
 * * @role: Domain Skill
 * * @description: 管理闹钟响铃期间防关机保护的用户开关与前置权限检查。
 * * @responsibilities:
 * 1. 持久化闹钟防关机保护开关。
 * 2. 聚合运行该保护所需的系统能力状态。
 * * @context:
 * - 上游: RulesFragment, CoreService
 * - 下游: SharedPreferences, Android permission/status APIs
 * * @constraints (必读):
 * 1. 不引入 UI 依赖。
 * 2. 开启保护前必须由调用方检查缺失项。
 * 3. 闹钟保护开关即时生效，不走不利修改延迟。
 * =================================================================================
 */
package com.zhizhi.app.manager

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat

object AlarmPowerOffProtectionManager {

    private const val PREFS_NAME = "alarm_power_off_protection_prefs"
    private const val KEY_ENABLED = "enabled"

    fun isEnabled(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_ENABLED, false)
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ENABLED, enabled)
            .apply()
    }

    fun canEnable(context: Context): Boolean {
        return getMissingPrerequisites(context).isEmpty()
    }

    fun getMissingPrerequisites(context: Context): List<String> {
        val missing = mutableListOf<String>()

        if (!isAccessibilityServiceEnabled(context)) {
            missing.add("无障碍服务")
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(context)) {
            missing.add("悬浮窗权限")
        }
        if (!hasNotificationPermission(context)) {
            missing.add("通知权限")
        }
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) {
            missing.add("允许通知")
        }
        if (!canUseFullScreenIntent(context)) {
            missing.add("全屏通知/锁屏显示")
        }

        return missing
    }

    fun isAccessibilityServiceEnabled(context: Context): Boolean {
        val enabledServices = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        )
        return enabledServices?.contains(context.packageName) == true
    }

    fun hasNotificationPermission(context: Context): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    }

    fun canUseFullScreenIntent(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < 34) return true
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        return notificationManager?.canUseFullScreenIntent() == true
    }
}
