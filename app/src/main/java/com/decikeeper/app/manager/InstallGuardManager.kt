/**
 * =================================================================================
 * [Context Header]
 * * @file: InstallGuardManager.kt
 * * @role: Domain Skill
 * * @description: 安装拦截的开关状态与延迟生效管理。
 * * @responsibilities:
 * 1. 管理安装拦截开关，开启立即生效，关闭走严格次日延迟。
 * 2. 提供拦截是否强制生效的判定接口。
 * * @context:
 * - 上游: RulesFragment, InstallProtection
 * - 下游: SharedPreferences, ConfigPendingManager
 * * @constraints (必读):
 * 1. 不引入 UI 依赖。
 * 2. 遵循适应期、基准与延迟生效规则（与 FloatingWindowProtectionManager 同口径）。
 * 3. 空值安全。
 * =================================================================================
 */
package com.decikeeper.app.manager

import android.content.Context
import android.content.SharedPreferences

/**
 * Manages the install interception switch.
 * - Enable: Immediate (Favorable)
 * - Disable: Delayed (Adverse) unless in Adaptation or Baseline already disabled
 */
object InstallGuardManager {

    const val PREFS_NAME = "install_guard_prefs"
    const val KEY_IS_ENABLED = "is_install_guard_enabled"
    const val KEY_PENDING_DISABLE = "install_guard_pending_disable"

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

        return if (System.currentTimeMillis() < effectiveTime) {
            State.PENDING
        } else {
            prefs.edit().putBoolean(KEY_IS_ENABLED, false).apply()
            ConfigPendingManager.clearPending(context, KEY_PENDING_DISABLE)
            State.DISABLED
        }
    }

    /** 权威查询：拦截是否处于强制生效中（ACTIVE 或未到期 PENDING）。 */
    fun isEnforcing(context: Context): Boolean {
        return getProtectionState(context) != State.DISABLED
    }

    fun setProtectionEnabled(context: Context, enabled: Boolean) {
        if (enabled) {
            getPrefs(context).edit().putBoolean(KEY_IS_ENABLED, true).apply()
            ConfigPendingManager.clearPending(context, KEY_PENDING_DISABLE)
            return
        }

        val baselineEnabled = TimeRuleManager.getBaselineInstallGuardState(context)
        if (TimeRuleManager.isInAdaptationPeriod(context) || !baselineEnabled) {
            getPrefs(context).edit().putBoolean(KEY_IS_ENABLED, false).apply()
            ConfigPendingManager.clearPending(context, KEY_PENDING_DISABLE)
        } else {
            ConfigPendingManager.submitChange(context, KEY_PENDING_DISABLE, "false", strictNextDay = true)
        }
    }

    fun getPendingDisableTime(context: Context): Long {
        return ConfigPendingManager.getEffectiveTime(context, KEY_PENDING_DISABLE)
    }
}
