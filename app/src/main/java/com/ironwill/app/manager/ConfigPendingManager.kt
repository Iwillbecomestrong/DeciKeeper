/**
 * =================================================================================
 * [Context Header]
 * * @file: ConfigPendingManager.kt
 * * @role: Domain Skill
 * * @description: 待生效配置的存储与应用控制。
 * * @responsibilities:
 * 1. 管理延迟生效参数的读写。
 * 2. 提供撤销与应用入口。
 * * @context:
 * - 上游: TimeRuleManager, AppWindowManager
 * - 下游: SharedPreferences
 * * @constraints (必读):
 * 1. 不引入 UI 依赖。
 * 2. 确保数据一致性。
 * 3. 处理空值安全。
 * =================================================================================
 */
package com.ironwill.app.manager

import android.content.Context
import android.content.SharedPreferences
import java.util.Calendar

/**
 * Manages configuration changes that are pending for a future effective time.
 * 
 * Logic:
 * - "Adverse" changes (laziness) are not applied immediately.
 * - They are stored here and become effective at a fixed time the NEXT day (e.g., 06:00).
 * - "Beneficial" changes (discipline) apply immediately and bypass this manager.
 */
object ConfigPendingManager {

    private const val PREFS_NAME = "pending_config_prefs"
    
    // Default Effective Time: Dynamically read from CreditManager.PREFS_NAME -> "config_refresh_time"

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    /**
     * 计算下一次生效时间 (次日 Refresh Time)
     * @param context required to read custom refresh time settings
     */
    /**
     * 计算下一次生效时间 (次日 Refresh Time)
     * @param context required to read custom refresh time settings
     */
    /**
     * 计算下一次生效时间 (次日 Refresh Time)
     * @param context required to read custom refresh time settings
     * @param strictNextDay [Fix] If true, ensures the result is strictly on the NEXT calendar day (or later), never today.
     */
    fun calculateNextEffectiveTime(context: android.content.Context, now: Long = System.currentTimeMillis(), strictNextDay: Boolean = false): Long {
        // 🚀 [Fix] Use CreditManager.PREFS_NAME ("iron_will_ledger") where Global Params are actually saved
        val prefs = context.getSharedPreferences(CreditManager.PREFS_NAME, android.content.Context.MODE_PRIVATE)
        val refreshTimeStr = prefs.getString("config_refresh_time", "08:00") ?: "08:00"
        
        val parts = refreshTimeStr.split(":")
        val targetH = parts.getOrNull(0)?.toIntOrNull() ?: 8
        val targetM = parts.getOrNull(1)?.toIntOrNull() ?: 0
        
        val cal = Calendar.getInstance()
        cal.timeInMillis = now
        
        // 设为当天的目标时间
        cal.set(Calendar.HOUR_OF_DAY, targetH)
        cal.set(Calendar.MINUTE, targetM)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        
        // 如果当前时间已经过了今天的目标时间 (或者刚好相等)，则顺延到明天
        if (now >= cal.timeInMillis) {
            cal.add(Calendar.DAY_OF_YEAR, 1)
        } else if (strictNextDay) {
            // 🚀 [Fix] Strict Next Day: Even if we haven't passed today's refresh time (e.g. 2AM vs 8AM),
            // if strictNextDay is requested, we MUST push to tomorrow.
            val nowCal = Calendar.getInstance()
            nowCal.timeInMillis = now
            if (cal.get(Calendar.DAY_OF_YEAR) == nowCal.get(Calendar.DAY_OF_YEAR) &&
                cal.get(Calendar.YEAR) == nowCal.get(Calendar.YEAR)) {
                 cal.add(Calendar.DAY_OF_YEAR, 1)     
            }
        }
        
        return cal.timeInMillis
    }

    /**
     * Calculates Effective Time for Weekly Cycle Reduction (Next Monday at Refresh Time).
     * 🚀 [Fix] Now respects dynamic Refresh Time instead of hardcoded 08:00
     */
    fun calculateNextMondayEffectiveTime(context: Context, now: Long): Long {
        val prefs = context.getSharedPreferences(CreditManager.PREFS_NAME, android.content.Context.MODE_PRIVATE)
        val refreshTimeStr = prefs.getString("config_refresh_time", "08:00") ?: "08:00"
        
        val parts = refreshTimeStr.split(":")
        val targetH = parts.getOrNull(0)?.toIntOrNull() ?: 8
        val targetM = parts.getOrNull(1)?.toIntOrNull() ?: 0

        val calendar = Calendar.getInstance()
        calendar.timeInMillis = now
        
        // Find next Monday
        var daysUntilMonday = (Calendar.MONDAY - calendar.get(Calendar.DAY_OF_WEEK) + 7) % 7
        if (daysUntilMonday == 0) daysUntilMonday = 7 // Ensure it's NEXT Monday, not today
        
        calendar.add(Calendar.DAY_OF_YEAR, daysUntilMonday)
        
        // Set to Refresh Time
        calendar.set(Calendar.HOUR_OF_DAY, targetH)
        calendar.set(Calendar.MINUTE, targetM)
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)
        
        return calendar.timeInMillis
    }

    /**
     * Submit a pending change.
     * @param key The config key (e.g., "anti_uninstall_disable")
     * @param value The target value (e.g., "false")
     * @param strictNextDay If true, enforces strict next-day even for early morning requests.
     */
    fun submitChange(context: Context, key: String, value: String, strictNextDay: Boolean = false) {
        val effectiveTime = calculateNextEffectiveTime(context, System.currentTimeMillis(), strictNextDay)
        getPrefs(context).edit()
            .putString(key, value)
            .putLong("${key}_effective_time", effectiveTime)
            .apply()
    }

    /**
     * Check if a pending change is ready to be applied.
     * @return true if (Now >= EffectiveTime)
     */
    fun isPendingEffective(context: Context, key: String): Boolean {
        val effectiveTime = getPrefs(context).getLong("${key}_effective_time", 0)
        if (effectiveTime == 0L) return false // No pending change
        
        return System.currentTimeMillis() >= effectiveTime
    }

    /**
     * Get the effective time for a specific key.
     */
    fun getEffectiveTime(context: Context, key: String): Long {
        return getPrefs(context).getLong("${key}_effective_time", 0)
    }

    /**
     * Clear a pending change (either applied or cancelled)
     */
    fun clearPending(context: Context, key: String) {
        getPrefs(context).edit()
            .remove(key)
            .remove("${key}_effective_time")
            .apply()
    }
}
