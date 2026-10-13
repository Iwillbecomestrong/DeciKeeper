package com.decikeeper.app.manager

import android.content.Context
import com.decikeeper.app.data.AnalysisResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * 回归：规则参数在「宽严有别」与「统一延后」之间来回切换时，待生效内容不能凭空消失，
 * 生效中的值也不能悄悄偏离当天基准。
 *
 * 真机复现：每周特权次数基准 3 次。统一延后下改 2（挂待生效，下周一生效）→ 切到宽严有别
 * （加严立即生效，active=2）→ 再切回统一延后：active 仍是 2、待生效没了；此时改回 3 反而
 * 变成待生效「2->3」。统一延后下当天所有修改都应是待生效，active 必须等于当天基准。
 */
@RunWith(RobolectricTestRunner::class)
class PolicySwitchGlobalParamsTest {

    private lateinit var context: Context
    private val allNextDay = EffectiveActivationPolicyManager.Policy.ALL_NEXT_DAY
    private val favorable = EffectiveActivationPolicyManager.Policy.FAVORABLE_IMMEDIATE

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        listOf(
            "decikeeper_rules", "decikeeper_rules_pending", "decikeeper_rules_baseline",
            "effective_activation_policy"
        ).forEach { context.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().apply() }
        CreditManager.markAdaptationEnded(context)
        CreditManager.setWeeklyPrivilegeLimit(context, 3, 480)
        CreditManager.clearPendingWeeklyPrivilegeLimit(context)
        TimeRuleManager.saveBaselineParams(context, forceWeekly = true)
        EffectiveActivationPolicyManager.setPolicy(context, allNextDay)
    }

    /** 模拟参数页保存：除指定项外，其余输入框都显示当前生效值。 */
    private fun save(
        weekly: Int = CreditManager.getWeeklyPrivilegeLimit(context),
        weeklyDuration: Int = CreditManager.getWeeklyPrivilegeDurationLimit(context),
        rest: Int = TimeRuleManager.getCoolDownRestSeconds(context),
        policy: EffectiveActivationPolicyManager.Policy? = null
    ): AnalysisResult = TimeRuleManager.analyzeAndSaveGlobalParams(
        context,
        newRest = rest,
        newGap = TimeRuleManager.getCoolDownGapSeconds(context),
        newLockDuration = TimeRuleManager.getStrictPenaltyDurationSeconds(context),
        newBorrow = TimeRuleManager.getBorrowPercentage(context),
        newNotifyFar = TimeRuleManager.getNotifyEntryFarMinutes(context),
        newNotifyNear = TimeRuleManager.getNotifyEntryNearMinutes(context),
        newWeeklyCount = weekly,
        newWeeklyDuration = weeklyDuration,
        newRefreshTime = TimeRuleManager.getRefreshTime(context),
        newEffectiveActivationPolicy = policy
    )

    private fun pendingWeekly(): Int? =
        if (CreditManager.hasPendingWeeklyPrivilegeLimit(context)) CreditManager.getPendingWeeklyPrivilegeLimit(context) else null

    @Test
    fun weeklyCountTightenedUnderAllNextDaySurvivesAFavorableRoundTrip() {
        // 统一延后下 3 -> 2：只挂待生效
        save(weekly = 2)
        assertEquals(3, CreditManager.getWeeklyPrivilegeLimit(context))
        assertEquals(2, pendingWeekly())

        // 切到宽严有别：加严立即生效，并且要告诉用户
        val toFavorable = save(weekly = 2, policy = favorable)
        assertEquals(2, CreditManager.getWeeklyPrivilegeLimit(context))
        assertEquals(null, pendingWeekly())
        val messages = (toFavorable as AnalysisResult.AllImmediate).messages
        assertTrue("切换让待生效的加严立即生效，必须写进提示：$messages", messages.any { it.contains("每周特权次数") })

        // 再切回统一延后：当天所有修改都应是待生效，生效中的值回到当天基准 3
        val back = save(weekly = 2, policy = allNextDay)
        assertEquals("统一延后下生效中的值必须等于当天基准", 3, CreditManager.getWeeklyPrivilegeLimit(context))
        assertEquals("加严的 2 仍应挂着待生效", 2, pendingWeekly())
        assertFalse("切换提示里要看到这一项待生效：$back", back.toString().isEmpty())
    }

    @Test
    fun settingTheCountBackToTheBaselineClearsThePendingInsteadOfCreatingATwoToThreePending() {
        save(weekly = 2)
        save(weekly = 2, policy = favorable)
        save(weekly = 2, policy = allNextDay)

        // 用户在统一延后下把次数改回 3（= 基准）：撤销待生效，而不是出现「2 -> 3」
        save(weekly = 3)

        assertEquals(3, CreditManager.getWeeklyPrivilegeLimit(context))
        assertEquals(null, pendingWeekly())
    }

    @Test
    fun weeklyDurationHasTheSameRoundTripBehaviour() {
        save(weeklyDuration = 300)
        assertEquals(480, CreditManager.getWeeklyPrivilegeDurationLimit(context))

        save(weeklyDuration = 300, policy = favorable)
        assertEquals(300, CreditManager.getWeeklyPrivilegeDurationLimit(context))

        save(weeklyDuration = 300, policy = allNextDay)
        assertEquals(480, CreditManager.getWeeklyPrivilegeDurationLimit(context))
        assertTrue(CreditManager.hasPendingWeeklyPrivilegeLimit(context))
        assertEquals(300, CreditManager.getPendingWeeklyPrivilegeDurationLimit(context))
    }

    @Test
    fun dailyParamsRollBackToTheBaselineWhenSwitchingToAllNextDay() {
        // 真实设备上这些值早已写入过；显式写入，避免读到「未设置」的代码默认值
        context.getSharedPreferences("decikeeper_rules", Context.MODE_PRIVATE).edit()
            .putInt("cool_down_rest_seconds", 120).apply()
        TimeRuleManager.saveBaselineParams(context, forceWeekly = true)
        val baselineRest = TimeRuleManager.getCoolDownRestSeconds(context)
        val tighter = baselineRest + 30 // 冷却越长越严

        // 宽严有别下加严：立即生效
        EffectiveActivationPolicyManager.setPolicy(context, favorable)
        save(rest = tighter)
        assertEquals(tighter, TimeRuleManager.getCoolDownRestSeconds(context))

        // 切到统一延后：生效中的值回到当天基准，加严的值挂待生效
        save(rest = tighter, policy = allNextDay)
        assertEquals(baselineRest, TimeRuleManager.getCoolDownRestSeconds(context))
        val pending = context.getSharedPreferences("decikeeper_rules_pending", Context.MODE_PRIVATE)
        assertEquals(tighter, pending.getInt("cool_down_rest_seconds", -1))
        assertTrue(pending.getBoolean("has_pending_changes", false))
    }

    @Test
    fun switchingWithNothingChangedKeepsTheValuesAndDoesNotInventAPending() {
        save(policy = favorable)
        save(policy = allNextDay)

        assertEquals(3, CreditManager.getWeeklyPrivilegeLimit(context))
        assertEquals(null, pendingWeekly())
    }
}
