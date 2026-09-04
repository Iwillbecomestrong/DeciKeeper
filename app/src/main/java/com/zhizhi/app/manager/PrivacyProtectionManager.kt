/**
 * =================================================================================
 * [Context Header]
 * * @file: PrivacyProtectionManager.kt
 * * @role: Domain Skill
 * * @description: 隐私系统拦截策略的状态与逻辑管理。
 * * @responsibilities:
 * 1. 管理隐私拦截开关与延迟生效。
 * 2. 提供拦截状态判定接口。
 * * @context:
 * - 上游: RulesFragment, MonitorService
 * - 下游: SharedPreferences
 * * @constraints (必读):
 * 1. 不引入 UI 依赖。
 * 2. 遵循适应期与延迟生效规则。
 * 3. 空值安全。
 * =================================================================================
 */
package com.zhizhi.app.manager

import android.content.Context
import android.content.SharedPreferences

/**
 * Manages Privacy System interception state.
 * - Enable: Immediate (Favorable)
 * - Disable: Delayed (Adverse) unless in Adaptation or Baseline already disabled
 * - Enable: Immediate and updates baseline immediately
 */
object PrivacyProtectionManager {

    const val PREFS_NAME = "privacy_protection_prefs"
    const val KEY_IS_ENABLED = "is_privacy_protection_enabled"
    const val KEY_PENDING_DISABLE = "privacy_protection_pending_disable"

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
     * 唯一权威的"是否仍强制执行"查询。
     *
     * 语义: ACTIVE 或未到期 PENDING 都返回 true; 仅 DISABLED 返回 false.
     * PENDING 到期后由 [getProtectionState] 内部落地为 DISABLED, 此处跟随.
     *
     * 不允许绕过本方法重复实现 ACTIVE || PENDING 逻辑; 门禁、UI 强制判断、
     * 无障碍协同关闭资格判断必须经此方法.
     */
    fun isEnforcing(context: Context): Boolean {
        return getProtectionState(context) != State.DISABLED
    }

    fun setProtectionEnabled(context: Context, enabled: Boolean) {
        val baselineEnabled = TimeRuleManager.getBaselinePrivacyProtectionState(context)

        if (enabled) {
            getPrefs(context).edit().putBoolean(KEY_IS_ENABLED, true).apply()
            ConfigPendingManager.clearPending(context, KEY_PENDING_DISABLE)
            context.getSharedPreferences("iron_will_rules_baseline", Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_IS_ENABLED, true)
                .apply()
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

    fun getPendingDisableTime(context: Context): Long {
        return ConfigPendingManager.getEffectiveTime(context, KEY_PENDING_DISABLE)
    }
}
