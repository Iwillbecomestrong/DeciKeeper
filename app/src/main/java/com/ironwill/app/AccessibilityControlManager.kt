/**
 * =================================================================================
 * [Context Header]
 * * @file: AccessibilityControlManager.kt
 * * @role: Domain Skill
 * * @description: 管理规则页“无障碍独立开关”的状态机与延迟关闭流程。
 * * @responsibilities:
 * 1. 维护无障碍开关状态（ACTIVE/PENDING/DISABLED）。
 * 2. 处理“关闭无障碍”不利操作的次日生效逻辑。
 * * @context:
 * - 上游: RulesFragment
 * - 下游: ConfigPendingManager, SharedPreferences
 * * @constraints (必读):
 * 1. 不直接操作系统无障碍开关，仅管理业务状态。
 * 2. 不引入 UI 依赖。
 * 3. 状态流转需可回放、可恢复。
 * =================================================================================
 */
package com.ironwill.app

import android.content.Context

object AccessibilityControlManager {

    private const val PREFS_NAME = "accessibility_control_prefs"
    private const val KEY_DESIRED_ENABLED = "accessibility_desired_enabled"
    private const val KEY_PENDING_DISABLE = "accessibility_pending_disable"

    enum class State {
        ACTIVE,
        PENDING,
        DISABLED
    }

    internal fun shouldBlockDisableWhenAntiUninstallNotClosed(
        antiState: AntiUninstallManager.State
    ): Boolean {
        // 产品规则变更：允许“关闭防卸载 + 关闭无障碍”同次提交，二者统一延迟生效。
        return false
    }

    private fun getDesiredEnabled(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_DESIRED_ENABLED, true)
    }

    private fun setDesiredEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_DESIRED_ENABLED, enabled)
            .apply()
    }

    fun getState(context: Context): State {
        val desiredEnabled = getDesiredEnabled(context)
        if (!desiredEnabled) return State.DISABLED

        val effectiveTime = ConfigPendingManager.getEffectiveTime(context, KEY_PENDING_DISABLE)
        if (effectiveTime == 0L) return State.ACTIVE

        val now = System.currentTimeMillis()
        if (now < effectiveTime) {
            return State.PENDING
        }

        setDesiredEnabled(context, false)
        ConfigPendingManager.clearPending(context, KEY_PENDING_DISABLE)
        return State.DISABLED
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        if (enabled) {
            setDesiredEnabled(context, true)
            ConfigPendingManager.clearPending(context, KEY_PENDING_DISABLE)
            return
        }

        ConfigPendingManager.submitChange(
            context = context,
            key = KEY_PENDING_DISABLE,
            value = "false",
            strictNextDay = true
        )
    }

    fun getDisableEffectiveTime(context: Context): Long {
        return ConfigPendingManager.getEffectiveTime(context, KEY_PENDING_DISABLE)
    }
}
