/**
 * =================================================================================
 * [Context Header]
 * * @file: TimeRuleEditWindowPolicyManager.kt
 * * @role: Domain Skill
 * * @description: 统一管理「时间规则可修改时段」策略的读取、持久化、受限时段判定与展示文案。
 * * @responsibilities:
 * 1. 管理「全天可修改」与「仅生效时间至 24:00 可修改」策略状态与 Pending 状态。
 * 2. 提供判定当前时刻是否处于修改锁定时段的接口。
 * 3. 提供面向 UI 的文案说明与选项展示。
 * * @context:
 * - 上游: GlobalParamsActivity, GlobalParamsFragment, FocusRulesFragment, TimeConfigFragment, AppWindowFragment
 * - 下游: TimeRuleManager, SharedPreferences
 * * @constraints:
 * 1. 不引入 Android UI 组件依赖。
 * 2. 默认策略为 ALL_DAY，与历史行为完全兼容。
 * 3. 锁定计算基于当前分钟数与每日生效时间，空值或非法值必须安全回退。
 * =================================================================================
 */
package com.decikeeper.app.manager

import android.content.Context
import java.util.Calendar

object TimeRuleEditWindowPolicyManager {

    enum class Policy(val key: String) {
        ALL_DAY("all_day"),
        EFFECTIVE_TIME_TO_MIDNIGHT("effective_time_to_midnight");

        companion object {
            fun fromKey(raw: String?): Policy {
                return entries.firstOrNull { it.key == raw } ?: ALL_DAY
            }
        }
    }

    const val PREFS_NAME = "decikeeper_rules"
    const val PREFS_PENDING = "decikeeper_rules_pending"
    const val KEY_POLICY = "time_rule_edit_window_policy"

    fun getPolicy(context: Context): Policy {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return Policy.fromKey(prefs.getString(KEY_POLICY, Policy.ALL_DAY.key))
    }

    fun setPolicy(context: Context, policy: Policy) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_POLICY, policy.key)
            .apply()

        // Sync to device protected storage if available
        TimeRuleManager.getDeviceProtectedPrefs(context)?.edit()
            ?.putString(KEY_POLICY, policy.key)
            ?.apply()
    }

    fun getPendingPolicy(context: Context): Policy? {
        val pendingPrefs = context.getSharedPreferences(PREFS_PENDING, Context.MODE_PRIVATE)
        return if (pendingPrefs.contains(KEY_POLICY)) {
            Policy.fromKey(pendingPrefs.getString(KEY_POLICY, null))
        } else {
            null
        }
    }

    fun setPendingPolicy(context: Context, policy: Policy) {
        context.getSharedPreferences(PREFS_PENDING, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_POLICY, policy.key)
            .apply()
    }

    fun clearPendingPolicy(context: Context) {
        context.getSharedPreferences(PREFS_PENDING, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_POLICY)
            .apply()
    }

    /**
     * 判断当前时间是否处于时间规则与参数禁止修改时段
     * @param context 上下文
     * @param nowCalendar 允许注入时间以便于测试，默认为当前系统时间
     */
    fun isTimeRuleEditLocked(context: Context, nowCalendar: Calendar = Calendar.getInstance()): Boolean {
        val policy = getPolicy(context)
        if (policy == Policy.ALL_DAY) {
            return false
        }
        val refreshTimeStr = TimeRuleManager.getRefreshTime(context)
        val refreshMinutes = TimeRuleManager.parseHHMMToMinutes(refreshTimeStr) ?: (8 * 60)
        val nowMinutes = nowCalendar.get(Calendar.HOUR_OF_DAY) * 60 + nowCalendar.get(Calendar.MINUTE)
        return nowMinutes in 0 until refreshMinutes
    }

    fun getPolicyTitle(policy: Policy): String {
        return when (policy) {
            Policy.ALL_DAY -> "全天可修改"
            Policy.EFFECTIVE_TIME_TO_MIDNIGHT -> "仅生效时间至 24:00 可修改"
        }
    }

    fun getPolicyDescription(policy: Policy): String {
        return when (policy) {
            Policy.ALL_DAY ->
                "全天任意时刻均可新增、编辑与调整时间规则及参数。"
            Policy.EFFECTIVE_TIME_TO_MIDNIGHT ->
                "00:00 至每日生效时间期间，时间规则与参数锁定为只读，仅在生效时间后允许修改。"
        }
    }

    fun getLockoutHintText(context: Context): String {
        val refreshTimeStr = TimeRuleManager.getRefreshTime(context)
        return "当前处于保护期（00:00 ~ $refreshTimeStr），时间规则与参数已锁定，仅供查看"
    }
}
