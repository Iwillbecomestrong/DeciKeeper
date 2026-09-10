/**
 * =================================================================================
 * [Context Header]
 * * @file: EffectiveActivationPolicyManager.kt
 * * @role: Domain Skill
 * * @description: 统一管理「时间规则」生效策略的读取、持久化与展示文案。
 * * @responsibilities:
 * 1. 管理勿扰时段与应用时间窗的生效策略。
 * 2. 提供面向 UI 的策略标题、说明与作用范围文案。
 * * @context:
 * - 上游: FocusRulesFragment
 * - 下游: TimeRuleManager, AppWindowManager, SharedPreferences
 * * @constraints (必读):
 * 1. 不引入 UI 组件依赖。
 * 2. 默认策略需与历史行为兼容。
 * 3. 该策略仅作用于勿扰时段与应用时间窗，不得扩展为全局规则策略。
 * 4. 空值与非法值必须回退默认策略。
 * =================================================================================
 */
package com.decikeeper.app.manager

import android.content.Context

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

        // Policy switch must reconcile pending/active state for DND + AppWindow only.
        TimeRuleManager.recalculatePendingAfterPolicySwitch(context, policy)
        AppWindowManager.recalculatePendingAfterPolicySwitch(context, policy)
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
                "加严立即生效；放宽在下一刷新时刻生效；恢复到当前基准的修改立即生效。仅影响勿扰时段和应用时间窗。"
            Policy.ALL_NEXT_DAY ->
                "勿扰时段和应用时间窗的所有修改，都在下一刷新时刻生效。"
        }
    }

    fun getPolicyScopeDescription(): String =
        "仅控制勿扰时段和应用时间窗的修改何时生效；黑白名单、全局参数和保护功能仍按各自规则处理。"
}
