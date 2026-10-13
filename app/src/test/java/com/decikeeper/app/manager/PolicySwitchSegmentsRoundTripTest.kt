package com.decikeeper.app.manager

import android.content.Context
import com.decikeeper.app.data.TimeSegment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 时段在「宽严有别」与「统一延后」之间来回切换：待生效内容不能丢，生效中的值要和策略口径一致。
 * （参数页有同样的问题，见 PolicySwitchGlobalParamsTest；时段这条路径本来就有回滚逻辑，这里锁住它。）
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PolicySwitchSegmentsRoundTripTest {

    private lateinit var context: Context
    private val allNextDay = EffectiveActivationPolicyManager.Policy.ALL_NEXT_DAY
    private val favorable = EffectiveActivationPolicyManager.Policy.FAVORABLE_IMMEDIATE

    private fun seg(limit: Int) = TimeSegment(
        startHour = 0, startMinute = 0, endHour = 23, endMinute = 59,
        limitMins = limit, name = "全天"
    )

    private fun activeLimit() = TimeRuleManager.getTimeSegments(context).single().limitMins
    private fun pendingLimit() = TimeRuleManager.getPendingTimeSegments(context)?.single()?.limitMins

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        listOf(
            "decikeeper_rules", "decikeeper_rules_pending", "decikeeper_rules_baseline",
            "effective_activation_policy"
        ).forEach { context.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit() }
        CreditManager.markAdaptationEnded(context)
        // 全天时段不能严格包含生效时间（08:00），否则保存会被边界守卫拒绝
        TimeRuleManager.saveRefreshTime(context, "00:00")
        val baseline = TimeRuleManager.segmentsToJson(listOf(seg(40)))
        context.getSharedPreferences("decikeeper_rules", Context.MODE_PRIVATE).edit().putString("segments_json", baseline).commit()
        context.getSharedPreferences("decikeeper_rules_baseline", Context.MODE_PRIVATE).edit().putString("segments_json", baseline).commit()
        TimeRuleManager.invalidateSegmentsCache()
    }

    @Test
    fun aTightenedSegmentSurvivesEveryPolicyRoundTrip() {
        // 宽严有别下加严（额度 40 -> 20）：立即生效
        EffectiveActivationPolicyManager.setPolicy(context, favorable)
        TimeRuleManager.analyzeAndSaveTimeSegments(context, listOf(seg(20)))
        assertEquals(20, activeLimit())

        // 切到统一延后：生效中的值回滚到基准，加严的值挂待生效
        EffectiveActivationPolicyManager.setPolicy(context, allNextDay)
        assertEquals(40, activeLimit())
        assertEquals(20, pendingLimit())

        // 切回宽严有别：挂着的加严按宽严有别立即生效
        EffectiveActivationPolicyManager.setPolicy(context, favorable)
        assertEquals(20, activeLimit())
        assertNull(pendingLimit())

        // 再切回统一延后：仍然是「基准 40，待生效 20」
        EffectiveActivationPolicyManager.setPolicy(context, allNextDay)
        assertEquals(40, activeLimit())
        assertNotNull(pendingLimit())
        assertEquals(20, pendingLimit())
    }
}
