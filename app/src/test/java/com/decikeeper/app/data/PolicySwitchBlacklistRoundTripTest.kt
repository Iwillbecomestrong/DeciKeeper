package com.decikeeper.app.data

import android.content.Context
import com.decikeeper.app.manager.CreditManager
import com.decikeeper.app.manager.EffectiveActivationPolicyManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 全局黑名单在「宽严有别」与「统一延后」之间来回切换：
 * 统一延后下当天所有修改都是待生效，生效中的名单必须等于当天基准；切回宽严有别后，
 * 挂着的加严（新增屏蔽）按宽严有别立即生效。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PolicySwitchBlacklistRoundTripTest {

    private lateinit var context: Context
    private val allNextDay = EffectiveActivationPolicyManager.Policy.ALL_NEXT_DAY
    private val favorable = EffectiveActivationPolicyManager.Policy.FAVORABLE_IMMEDIATE
    private val base = setOf("com.test.a", "com.test.b")
    private val tighter = base + "com.test.c" // 多屏蔽一个应用 = 加严

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        listOf(
            "decikeeper_ledger", "decikeeper_pending_apps", "baseline_prefs",
            "decikeeper_rules", "decikeeper_rules_pending", "decikeeper_rules_baseline",
            "effective_activation_policy"
        ).forEach { context.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit() }
        BlacklistStore.invalidateBlacklistCache()
        CreditManager.markAdaptationEnded(context)
        BlacklistStore.saveTargetAppsImmediately(context, base)
        BlacklistStore.saveBaselineBlacklist(context)
    }

    @Test
    fun aTightenedBlacklistSurvivesEveryPolicyRoundTrip() {
        EffectiveActivationPolicyManager.setPolicy(context, favorable)
        BlacklistStore.analyzeAndSaveBlacklistApps(context, tighter)
        assertEquals("宽严有别下加严立即生效", tighter, BlacklistStore.getBlacklistApps(context))

        // 切到统一延后：生效中的名单回到当天基准，加严的名单挂待生效
        EffectiveActivationPolicyManager.setPolicy(context, allNextDay)
        assertEquals(base, BlacklistStore.getBlacklistApps(context))
        assertEquals(tighter, BlacklistStore.getPendingBlacklistApps(context))

        // 切回宽严有别：挂着的加严立即生效，不再有待生效
        EffectiveActivationPolicyManager.setPolicy(context, favorable)
        assertEquals(tighter, BlacklistStore.getBlacklistApps(context))
        assertFalse(BlacklistStore.hasPendingApps(context))

        // 再切回统一延后：仍然是「基准 base，待生效 tighter」
        EffectiveActivationPolicyManager.setPolicy(context, allNextDay)
        assertEquals(base, BlacklistStore.getBlacklistApps(context))
        assertTrue(BlacklistStore.hasPendingApps(context))
        assertEquals(tighter, BlacklistStore.getPendingBlacklistApps(context))
    }

    @Test
    fun switchingWithNoBlacklistChangeDoesNotInventAPending() {
        EffectiveActivationPolicyManager.setPolicy(context, favorable)
        EffectiveActivationPolicyManager.setPolicy(context, allNextDay)
        EffectiveActivationPolicyManager.setPolicy(context, favorable)

        assertEquals(base, BlacklistStore.getBlacklistApps(context))
        assertFalse(BlacklistStore.hasPendingApps(context))
    }

    @Test
    fun mixedPendingKeepsTheLooseningWaitingWhenSwitchingToFavorable() {
        // 统一延后下：新增屏蔽 c（加严）+ 解除屏蔽 b（放宽），都只是待生效
        EffectiveActivationPolicyManager.setPolicy(context, allNextDay)
        val target = setOf("com.test.a", "com.test.c")
        BlacklistStore.analyzeAndSaveBlacklistApps(context, target)
        assertEquals(base, BlacklistStore.getBlacklistApps(context))
        assertEquals(target, BlacklistStore.getPendingBlacklistApps(context))

        // 切到宽严有别：加严的 c 立即生效，放宽的解除 b 继续等
        EffectiveActivationPolicyManager.setPolicy(context, favorable)
        assertEquals(base + "com.test.c", BlacklistStore.getBlacklistApps(context))
        assertEquals(target, BlacklistStore.getPendingBlacklistApps(context))

        // 切回统一延后：生效中回到基准，目标名单不变
        EffectiveActivationPolicyManager.setPolicy(context, allNextDay)
        assertEquals(base, BlacklistStore.getBlacklistApps(context))
        assertEquals(target, BlacklistStore.getPendingBlacklistApps(context))
    }
}
