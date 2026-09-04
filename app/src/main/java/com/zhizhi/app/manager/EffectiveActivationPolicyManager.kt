/**
 * =================================================================================
 * [Context Header]
 * * @file: EffectiveActivationPolicyManager.kt
 * * @role: Domain Skill
 * * @description: 统一管理规则生效策略（统一次日/有利立即）的读取与持久化。
 * * @responsibilities:
 * 1. 提供全局生效策略读写能力。
 * 2. 提供面向 UI 的策略文案描述。
 * * @context:
 * - 上游: RulesFragment
 * - 下游: TimeRuleManager, AppWindowManager, SharedPreferences
 * * @constraints (必读):
 * 1. 不引入 UI 组件依赖。
 * 2. 默认策略需与历史行为兼容。
 * 3. 空值与非法值必须回退默认策略。
 * =================================================================================
 */
package com.zhizhi.app.manager

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

        // Policy switch must reconcile pending/active state for DND + AppWindow.
        TimeRuleManager.recalculatePendingAfterPolicySwitch(context, policy)
        AppWindowManager.recalculatePendingAfterPolicySwitch(context, policy)
    }

    fun getPolicyTitle(policy: Policy): String {
        return when (policy) {
            Policy.FAVORABLE_IMMEDIATE -> "宽严有别"
            Policy.ALL_NEXT_DAY -> "全部次日"
        }
    }

    fun getPolicyDescription(policy: Policy): String {
        return when (policy) {
            Policy.FAVORABLE_IMMEDIATE -> "放宽立即，加严次日。仅影响勿扰时段和应用时间窗；黑名单与全局参数保持原策略。"
            Policy.ALL_NEXT_DAY -> "所有修改次日生效。仅影响勿扰时段和应用时间窗；黑名单与全局参数保持原策略。"
        }
    }
}
