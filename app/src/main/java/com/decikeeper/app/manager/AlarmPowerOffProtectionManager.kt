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
package com.decikeeper.app.manager

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat

object AlarmPowerOffProtectionManager {

    internal const val PREFS_NAME = "alarm_power_off_protection_prefs"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_LOCK_SCREEN_DISPLAY_CONFIRMED = "lock_screen_display_confirmed"

    data class PrerequisiteStatus(
        val a11yReady: Boolean,
        val overlayReady: Boolean,
        val notificationPermissionReady: Boolean,
        val notificationsEnabled: Boolean,
        val lockScreenDisplayReady: Boolean
    ) {
        val allReady: Boolean
            get() = a11yReady &&
                overlayReady &&
                notificationPermissionReady &&
                notificationsEnabled &&
                lockScreenDisplayReady

        val missingLabels: List<String>
            get() = buildList {
                if (!a11yReady) add("无障碍服务")
                if (!overlayReady) add("悬浮窗权限")
                if (!notificationPermissionReady) add("通知权限")
                if (!notificationsEnabled) add("允许通知")
                if (!lockScreenDisplayReady) add("锁屏显示")
            }
    }

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

    internal fun disableImmediatelyForAccessibilityUnavailable(
        context: Context,
        caller: String = "AccessibilityDependencyManager"
    ) {
        val oldEnabled = isEnabled(context)
        setEnabled(context, false)
        val newEnabled = isEnabled(context)

        val runIdTag = ProtectionDiagnostics.currentTestRunId?.let { "runId=$it " } ?: ""
        android.util.Log.w(
            "ProtectionDiagnostics",
            "[PROTECTION_FORCE_DISABLE] ${runIdTag}component=alarm_power_off caller=$caller " +
                    "oldEnabled=$oldEnabled oldState=${if (oldEnabled) "ACTIVE" else "DISABLED"} oldPending=0 " +
                    "newEnabled=$newEnabled newState=${if (newEnabled) "ACTIVE" else "DISABLED"}"
        )
    }

    fun canEnable(context: Context): Boolean {
        return getPrerequisiteStatus(context).allReady
    }

    fun getMissingPrerequisites(context: Context): List<String> {
        return getPrerequisiteStatus(context).missingLabels
    }

    fun setLockScreenDisplayConfirmed(context: Context, confirmed: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_LOCK_SCREEN_DISPLAY_CONFIRMED, confirmed)
            .apply()
    }

    fun getPrerequisiteStatus(context: Context): PrerequisiteStatus {
        return PrerequisiteStatus(
            a11yReady = isAccessibilityServiceEnabled(context),
            overlayReady = PermissionManager.hasOverlayPermission(context),
            notificationPermissionReady = hasNotificationPermission(context),
            notificationsEnabled = NotificationManagerCompat.from(context).areNotificationsEnabled(),
            lockScreenDisplayReady = isLockScreenDisplayReady(context)
        )
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

    fun isLockScreenDisplayReady(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_LOCK_SCREEN_DISPLAY_CONFIRMED, false)
    }
}
