/**
 * =================================================================================
 * [Context Header]
 * * @file: EffectiveActivationPolicyManager.kt
 * * @role: Domain Skill
 * * @description: 统一管理「时间规则」生效策略的读取、持久化与展示文案。
 * * @responsibilities:
 * 1. 管理勿扰时段、应用时间窗、全局黑名单与规则参数的生效策略（「宽严有别」/「统一延后」）。
 * 2. 提供面向 UI 的策略标题、说明与作用范围文案。
 * * @context:
 * - 上游: FocusRulesFragment
 * - 下游: TimeRuleManager, AppWindowManager, SharedPreferences
 * * @constraints (必读):
 * 1. 不引入 UI 组件依赖。
 * 2. 默认策略需与历史行为兼容。
 * 3. 该策略只作用于时间规则（勿扰时段、应用时间窗、全局黑名单、规则参数），不得扩展到防卸载、防关等系统保护能力。
 * 4. 空值与非法值必须回退默认策略。
 * =================================================================================
 */
package com.decikeeper.app.manager

import android.content.Context
import android.content.Intent
import com.decikeeper.app.data.BlacklistStore
import com.decikeeper.app.service.MonitorService

object EffectiveActivationPolicyManager {

    enum class Policy(val key: String) {
        FAVORABLE_IMMEDIATE("favorable_immediate"),
        ALL_NEXT_DAY("all_next_day");

        companion object {
            fun fromKey(raw: String?): Policy {
                return entries.firstOrNull { it.key == raw } ?: FAVORABLE_IMMEDIATE
            }
        }
    }

    private const val PREFS_NAME = "effective_activation_policy"
    private const val KEY_POLICY = "policy"

    fun getPolicy(context: Context): Policy {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return Policy.fromKey(prefs.getString(KEY_POLICY, Policy.FAVORABLE_IMMEDIATE.key))
    }

    fun setPolicy(context: Context, policy: Policy) {
        val oldPolicy = getPolicy(context)
        if (oldPolicy == policy) return

        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_POLICY, policy.key)
            .apply()

        // Policy switch must reconcile pending/active state for DND + AppWindow + global blacklist.
        TimeRuleManager.recalculatePendingAfterPolicySwitch(context, policy)
        AppWindowManager.recalculatePendingAfterPolicySwitch(context, policy)
        BlacklistStore.recalculatePendingAfterPolicySwitch(context, policy)

        try {
            val intent = Intent(MonitorService.ACTION_REFRESH_RULES)
            intent.setPackage(context.packageName)
            context.sendBroadcast(intent)
        } catch (_: Exception) {}
    }

    fun getPolicyTitle(policy: Policy): String {
        return when (policy) {
            Policy.FAVORABLE_IMMEDIATE -> "宽严有别"
            Policy.ALL_NEXT_DAY -> "统一延后"
        }
    }

    fun getPolicyDescription(policy: Policy): String {
        return when (policy) {
            Policy.FAVORABLE_IMMEDIATE ->
                "加严立即生效；放宽在下一刷新时刻生效；恢复到当前基准的修改立即生效。影响勿扰时段、应用时间窗、规则参数与全局黑名单。"
            Policy.ALL_NEXT_DAY ->
                "勿扰时段、应用时间窗、规则参数与全局黑名单的所有修改，都在下一刷新时刻生效。"
        }
    }

    fun getPolicyScopeDescription(): String =
        "控制勿扰时段、应用时间窗、规则参数与全局黑名单的修改何时生效；系统保护功能仍按各自规则处理。"
}
