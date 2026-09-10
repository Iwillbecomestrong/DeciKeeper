package com.decikeeper.app

import com.decikeeper.app.manager.EffectiveActivationPolicyManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EffectiveActivationPolicyCopyTest {

    @Test
    fun favorableImmediate_copyMatchesTimeRuleSemantics() {
        val policy = EffectiveActivationPolicyManager.Policy.FAVORABLE_IMMEDIATE

        assertEquals("宽严有别", EffectiveActivationPolicyManager.getPolicyTitle(policy))
        assertEquals(
            "加严立即生效；放宽在下一刷新时刻生效；恢复到当前基准的修改立即生效。仅影响勿扰时段和应用时间窗。",
            EffectiveActivationPolicyManager.getPolicyDescription(policy)
        )
    }

    @Test
    fun allNextDay_copyStatesScopeAndActivationPoint() {
        val policy = EffectiveActivationPolicyManager.Policy.ALL_NEXT_DAY

        assertEquals("统一延后", EffectiveActivationPolicyManager.getPolicyTitle(policy))
        assertEquals(
            "勿扰时段和应用时间窗的所有修改，都在下一刷新时刻生效。",
            EffectiveActivationPolicyManager.getPolicyDescription(policy)
        )
    }

    @Test
    fun policyScope_isExplicitlyLimitedToTwoTimeRules() {
        val copy = EffectiveActivationPolicyManager.getPolicyScopeDescription()
        assertTrue(copy.contains("勿扰时段"))
        assertTrue(copy.contains("应用时间窗"))
        assertTrue(copy.contains("仅"))
    }
}
