/**
 * =================================================================================
 * [Context Header]
 * * @file: ManualPermissionRecoveryManager.kt
 * * @role: Domain Skill
 * * @description: 手动权限引导后的自动回填管理器（去设置→返回后自动确认）。
 * * @responsibilities:
 * 1. 记录权限引导跳转时间戳。
 * 2. 结合最近系统权限中心访问痕迹，提供自动回填判定。
 * * @context:
 * - 上游: HomeFragment, RulesFragment
 * - 下游: SharedPreferences, UsageStatsManager
 * * @constraints (必读):
 * 1. 不依赖 UI 组件，不弹窗。
 * 2. 异常需兜底，检测失败返回 false。
 * 3. 仅做轻量读取，不做阻塞式耗时任务。
 * =================================================================================
 */
package com.ironwill.app.manager

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Build

object ManualPermissionRecoveryManager {

    private const val PREF_SETTINGS_VISIT_PREFIX = "manual_permission_settings_visit_at_"
    private const val SETTINGS_VISIT_VALID_MS = 10 * 60 * 1000L
    private const val PERMISSION_CENTER_LOOKBACK_MS = 20 * 60 * 1000L

    private val vivoPermissionPackages = setOf(
        "com.vivo.permissionmanager",
        "com.android.settings"
    )

    fun shouldAutoMarkByGuidedFlow(detected: Boolean?, hasRecentGuidedVisit: Boolean): Boolean {
        return detected == true || (detected == null && hasRecentGuidedVisit)
    }

    fun recordSettingsVisit(context: Context, permissionKey: String) {
        context.getSharedPreferences(CreditManager.PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putLong("$PREF_SETTINGS_VISIT_PREFIX$permissionKey", System.currentTimeMillis())
            .apply()
    }

    fun clearSettingsVisit(context: Context, permissionKey: String) {
        context.getSharedPreferences(CreditManager.PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove("$PREF_SETTINGS_VISIT_PREFIX$permissionKey")
            .apply()
    }

    fun hasRecentGuidedVisit(context: Context, permissionKey: String): Boolean {
        if (!isVivoFamily()) return false

        val now = System.currentTimeMillis()
        val prefs = context.getSharedPreferences(CreditManager.PREFS_NAME, Context.MODE_PRIVATE)
        val openedAt = prefs.getLong("$PREF_SETTINGS_VISIT_PREFIX$permissionKey", 0L)
        if (openedAt <= 0L) return false

        if (now - openedAt > SETTINGS_VISIT_VALID_MS) {
            clearSettingsVisit(context, permissionKey)
            return false
        }

        val hasPermissionCenterEvent = hasPermissionCenterForegroundEvent(context, openedAt, now)
        if (hasPermissionCenterEvent) return true

        return shouldUseFastGuidedFallback(permissionKey)
    }

    internal fun shouldUseFastGuidedFallback(permissionKey: String): Boolean {
        return permissionKey == "auto_start_confirmed" ||
            permissionKey == "bg_popup_confirmed" ||
            permissionKey == "assoc_start_confirmed"
    }

    private fun hasPermissionCenterForegroundEvent(context: Context, openedAt: Long, now: Long): Boolean {
        val usageStatsManager = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager ?: return false
        val start = maxOf(openedAt - 5_000L, now - PERMISSION_CENTER_LOOKBACK_MS)

        return runCatching {
            val events = usageStatsManager.queryEvents(start, now)
            val event = UsageEvents.Event()
            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                if (event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND ||
                    event.eventType == UsageEvents.Event.ACTIVITY_RESUMED
                ) {
                    if (vivoPermissionPackages.contains(event.packageName)) {
                        return@runCatching true
                    }
                }
            }
            false
        }.getOrDefault(false)
    }

    private fun isVivoFamily(): Boolean {
        val manufacturer = Build.MANUFACTURER.lowercase()
        return manufacturer.contains("vivo") || manufacturer.contains("iqoo")
    }
}
