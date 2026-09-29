/**
 * =================================================================================
 * [Context Header]
 * * @file: FloatingWindowProtectionManager.kt
 * * @role: Domain Skill
 * * @description: 小窗可见性增强策略的状态与逻辑管理。
 * * @responsibilities:
 * 1. 管理小窗可见性增强开关与延迟生效（次日生效）。
 * 2. 提供小窗识别增强状态判定接口。
 * * @context:
 * - 上游: RulesFragment, MonitorService, VisibleBlacklistPresenceTracker
 * - 下游: SharedPreferences, ConfigPendingManager
 * * @constraints (必读):
 * 1. 不引入 UI 依赖。
 * 2. 遵循适应期与延迟生效规则。
 * 3. 空值安全。
 * =================================================================================
 */
package com.decikeeper.app.manager

import android.content.Context
import android.content.SharedPreferences

/**
 * Manages Floating Window visibility enhancement state.
 * - Enable: Immediate (Favorable)
 * - Disable: Delayed (Adverse) unless in Adaptation or Baseline already disabled
 * - Enable: Immediate and updates baseline immediately
 */
object FloatingWindowProtectionManager {

    const val PREFS_NAME = "floating_window_protection_prefs"
    const val KEY_IS_ENABLED = "is_floating_window_protection_enabled"
    const val KEY_PENDING_DISABLE = "floating_window_protection_pending_disable"

    enum class State {
        DISABLED,
        ACTIVE,
        PENDING
    }

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun getProtectionState(context: Context): State {
        val prefs = getPrefs(context)
        val isEnabled = prefs.getBoolean(KEY_IS_ENABLED, false)
        if (!isEnabled) return State.DISABLED

        val effectiveTime = ConfigPendingManager.getEffectiveTime(context, KEY_PENDING_DISABLE)
        if (effectiveTime == 0L) return State.ACTIVE

        val now = System.currentTimeMillis()
        return if (now < effectiveTime) {
            State.PENDING
        } else {
            // Effective -> Disable immediately
            prefs.edit().putBoolean(KEY_IS_ENABLED, false).apply()
            ConfigPendingManager.clearPending(context, KEY_PENDING_DISABLE)
            State.DISABLED
        }
    }

    /**
     * 权威查询：小窗可见性增强是否处于强制生效中（ACTIVE 或未到期 PENDING）。
     */
    fun isEnforcing(context: Context): Boolean {
        return getProtectionState(context) != State.DISABLED
    }

    fun setProtectionEnabled(context: Context, enabled: Boolean) {
        val baselineEnabled = TimeRuleManager.getBaselineFloatingWindowProtectionState(context)

        if (enabled) {
            getPrefs(context).edit().putBoolean(KEY_IS_ENABLED, true).apply()
            ConfigPendingManager.clearPending(context, KEY_PENDING_DISABLE)
        } else {
            // Adverse: Delay unless adaptation or restoring to baseline disabled
            if (TimeRuleManager.isInAdaptationPeriod(context) || !baselineEnabled) {
                getPrefs(context).edit().putBoolean(KEY_IS_ENABLED, false).apply()
                ConfigPendingManager.clearPending(context, KEY_PENDING_DISABLE)
            } else {
                ConfigPendingManager.submitChange(context, KEY_PENDING_DISABLE, "false", strictNextDay = true)
            }
        }
    }

    internal fun disableImmediatelyForAccessibilityUnavailable(context: Context) {
        getPrefs(context).edit().putBoolean(KEY_IS_ENABLED, false).apply()
        ConfigPendingManager.clearPending(context, KEY_PENDING_DISABLE)
    }

    fun getPendingDisableTime(context: Context): Long {
        return ConfigPendingManager.getEffectiveTime(context, KEY_PENDING_DISABLE)
    }
}
