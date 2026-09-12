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

    sealed class PolicyChangeResult {
        data class Immediate(val message: String) : PolicyChangeResult()
        data class Pending(val message: String) : PolicyChangeResult()
        object NoChange : PolicyChangeResult()
    }

    fun isTimeRuleEditLocked(context: Context, nowCalendar: Calendar = Calendar.getInstance()): Boolean {
        if (TimeRuleManager.isInAdaptationPeriod(context)) {
            return false
        }
        val policy = getPolicy(context)
        if (policy == Policy.ALL_DAY) {
            return false
        }
        val refreshTimeStr = TimeRuleManager.getRefreshTime(context)
        val refreshMinutes = TimeRuleManager.parseHHMMToMinutes(refreshTimeStr) ?: (8 * 60)
        val nowMinutes = nowCalendar.get(Calendar.HOUR_OF_DAY) * 60 + nowCalendar.get(Calendar.MINUTE)
        return nowMinutes in 0 until refreshMinutes
    }

    fun isEditLocked(context: Context, nowCalendar: Calendar = Calendar.getInstance()): Boolean {
        return isTimeRuleEditLocked(context, nowCalendar)
    }

    fun applyPolicyChange(context: Context, targetPolicy: Policy): PolicyChangeResult {
        val currentPolicy = getPolicy(context)
        val pending = getPendingPolicy(context)

        if (currentPolicy == targetPolicy) {
            if (pending != null) {
                clearPendingPolicy(context)
                return PolicyChangeResult.Immediate("已撤销待生效的时段修改，维持当前设置")
            }
            return PolicyChangeResult.NoChange
        }

        if (targetPolicy == Policy.EFFECTIVE_TIME_TO_MIDNIGHT) {
            clearPendingPolicy(context)
            setPolicy(context, targetPolicy)
            return PolicyChangeResult.Immediate("已切换为：仅生效时间至 24:00 可修改")
        }

        if (TimeRuleManager.isInAdaptationPeriod(context)) {
            clearPendingPolicy(context)
            setPolicy(context, targetPolicy)
            return PolicyChangeResult.Immediate("当前处于适应期，已立即切换为：全天可修改")
        } else {
            setPendingPolicy(context, targetPolicy)
            val refreshTime = TimeRuleManager.getRefreshTime(context)
            return PolicyChangeResult.Pending("切换为「全天可修改」将在明日 $refreshTime 生效")
        }
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
                "全天任意时刻均可新增、编辑与调整规则及系统保护功能。"
            Policy.EFFECTIVE_TIME_TO_MIDNIGHT ->
                "00:00 至每日生效时间期间，除闹钟外各项规则与保护功能锁定为只读，仅在生效时间后允许修改。"
        }
    }

    fun getLockoutHintText(context: Context): String {
        val refreshTimeStr = TimeRuleManager.getRefreshTime(context)
        return "当前处于保护期（00:00 ~ $refreshTimeStr），除闹钟外各项规则与保护功能已锁定，仅供查看"
    }
}
