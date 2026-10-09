package com.decikeeper.app.manager

import android.content.Context
import com.decikeeper.app.data.AnalysisResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class EffectiveActivationPolicySaveIntegrationTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("decikeeper_rules", Context.MODE_PRIVATE).edit().clear().apply()
        context.getSharedPreferences("decikeeper_rules_pending", Context.MODE_PRIVATE).edit().clear().apply()
        context.getSharedPreferences("effective_activation_policy", Context.MODE_PRIVATE).edit().clear().apply()
        CreditManager.markAdaptationEnded(context)
        TimeRuleManager.saveBaselineParams(context)
    }

    @Test
    fun analyzeAndSaveGlobalParams_policySwitchToAllNextDay_returnsImmediateFavorable() {
        EffectiveActivationPolicyManager.setPolicy(context, EffectiveActivationPolicyManager.Policy.FAVORABLE_IMMEDIATE)
        
        val result = TimeRuleManager.analyzeAndSaveGlobalParams(
            context,
            newRest = TimeRuleManager.getCoolDownRestSeconds(context),
            newGap = TimeRuleManager.getCoolDownGapSeconds(context),
            newLockDuration = TimeRuleManager.getStrictPenaltyDurationSeconds(context),
            newBorrow = TimeRuleManager.getBorrowPercentage(context),
            newNotifyFar = TimeRuleManager.getNotifyEntryFarMinutes(context),
            newNotifyNear = TimeRuleManager.getNotifyEntryNearMinutes(context),
            newWeeklyCount = CreditManager.getWeeklyPrivilegeLimit(context),
            newWeeklyDuration = CreditManager.getWeeklyPrivilegeDurationLimit(context),
            newRefreshTime = TimeRuleManager.getRefreshTime(context),
            newEffectiveActivationPolicy = EffectiveActivationPolicyManager.Policy.ALL_NEXT_DAY
        )

        assertTrue(result is AnalysisResult.AllImmediate)
        val immediate = result as AnalysisResult.AllImmediate
        assertTrue(immediate.messages.any { it.contains("规则生效策略: 宽严有别 -> 统一延后 (有利)") })
        assertEquals(EffectiveActivationPolicyManager.Policy.ALL_NEXT_DAY, EffectiveActivationPolicyManager.getPolicy(context))
    }

    @Test
    fun analyzeAndSaveGlobalParams_policySwitchToFavorableImmediate_returnsImmediateFavorable() {
        EffectiveActivationPolicyManager.setPolicy(context, EffectiveActivationPolicyManager.Policy.ALL_NEXT_DAY)
        
        val result = TimeRuleManager.analyzeAndSaveGlobalParams(
            context,
            newRest = TimeRuleManager.getCoolDownRestSeconds(context),
            newGap = TimeRuleManager.getCoolDownGapSeconds(context),
            newLockDuration = TimeRuleManager.getStrictPenaltyDurationSeconds(context),
            newBorrow = TimeRuleManager.getBorrowPercentage(context),
            newNotifyFar = TimeRuleManager.getNotifyEntryFarMinutes(context),
            newNotifyNear = TimeRuleManager.getNotifyEntryNearMinutes(context),
            newWeeklyCount = CreditManager.getWeeklyPrivilegeLimit(context),
            newWeeklyDuration = CreditManager.getWeeklyPrivilegeDurationLimit(context),
            newRefreshTime = TimeRuleManager.getRefreshTime(context),
            newEffectiveActivationPolicy = EffectiveActivationPolicyManager.Policy.FAVORABLE_IMMEDIATE
        )

        assertTrue(result is AnalysisResult.AllImmediate)
        val immediate = result as AnalysisResult.AllImmediate
        assertTrue(immediate.messages.any { it.contains("规则生效策略: 统一延后 -> 宽严有别 (有利)") })
        assertEquals(EffectiveActivationPolicyManager.Policy.FAVORABLE_IMMEDIATE, EffectiveActivationPolicyManager.getPolicy(context))
    }

    @Test
    fun analyzeAndSaveGlobalParams_noPolicyChangeAndNoParamChange_returnsNoChange() {
        EffectiveActivationPolicyManager.setPolicy(context, EffectiveActivationPolicyManager.Policy.FAVORABLE_IMMEDIATE)
        
        val result = TimeRuleManager.analyzeAndSaveGlobalParams(
            context,
            newRest = TimeRuleManager.getCoolDownRestSeconds(context),
            newGap = TimeRuleManager.getCoolDownGapSeconds(context),
            newLockDuration = TimeRuleManager.getStrictPenaltyDurationSeconds(context),
            newBorrow = TimeRuleManager.getBorrowPercentage(context),
            newNotifyFar = TimeRuleManager.getNotifyEntryFarMinutes(context),
            newNotifyNear = TimeRuleManager.getNotifyEntryNearMinutes(context),
            newWeeklyCount = CreditManager.getWeeklyPrivilegeLimit(context),
            newWeeklyDuration = CreditManager.getWeeklyPrivilegeDurationLimit(context),
            newRefreshTime = TimeRuleManager.getRefreshTime(context),
            newEffectiveActivationPolicy = EffectiveActivationPolicyManager.Policy.FAVORABLE_IMMEDIATE
        )

        assertTrue(result is AnalysisResult.NoChange)
    }
}
