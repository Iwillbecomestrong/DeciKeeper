package com.zhizhi.app

import com.zhizhi.app.manager.AppWindowManager
import com.zhizhi.app.manager.EffectiveActivationPolicyManager
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class RuleDisplayBaselineConsistencyTest {

    private fun source(path: String): String {
        val cwd = File(System.getProperty("user.dir"))
        val candidates = listOf(
            File(cwd, path),
            File(cwd, "app/$path"),
            File(cwd.parentFile ?: cwd, path),
            File(cwd.parentFile ?: cwd, "app/$path")
        )
        return candidates.firstOrNull { it.isFile }?.readText()
            ?: error("Cannot locate $path from ${cwd.absolutePath}")
    }

    @Test
    fun freshRuleRemovingAnAppIsPresentedAsImmediateBaselineRestore() {
        val context = RuntimeEnvironment.getApplication()
        val oldRule = AppWindowManager.AppWindowRule(
            id = "fresh-rule",
            name = "娱乐",
            startTime = 1200,
            endTime = 1320,
            packageNames = mutableSetOf("com.ss.android.ugc.aweme")
        )
        val newRule = oldRule.copy(packageNames = mutableSetOf<String>())

        val analysis = AppWindowManager.analyzeChangeForDisplay(
            context = context,
            baselineRule = null,
            oldRule = oldRule,
            newRule = newRule
        )

        assertFalse(analysis.isOverallAdverse)
        assertTrue(analysis.details.any { it.type == AppWindowManager.ChangeType.RESTORED })
        assertFalse(analysis.details.any { it.type == AppWindowManager.ChangeType.ADVERSE })
    }

    @Test
    fun editToggleAndDeleteUseTheSameBaselineDisplayAnalyzer() {
        val ui = source("src/main/java/com/zhizhi/app/ui/AppWindowFragment.kt")

        val callCount = Regex("AppWindowManager\\.analyzeChangeForDisplay\\(").findAll(ui).count()
        assertTrue("edit/toggle/delete should all use analyzeChangeForDisplay", callCount >= 3)
        assertFalse(ui.contains("val displayAnalysis = AppWindowManager.analyzeChange("))
        assertFalse(ui.contains("val baselineAnalysis = AppWindowManager.analyzeChange("))
        assertFalse(ui.contains("val analysis = AppWindowManager.analyzeChange(context, rule, newRule)"))
    }

    @Test
    fun userFacingRuleCopyUsesCanonicalTermsAndRefreshTiming() {
        val ui = source("src/main/java/com/zhizhi/app/ui/AppWindowFragment.kt")
        val policy = source("src/main/java/com/zhizhi/app/manager/EffectiveActivationPolicyManager.kt")

        assertTrue(policy.contains("Policy.ALL_NEXT_DAY -> \"统一延后\""))
        assertTrue(policy.contains("恢复到当前基准的修改立即生效"))

        listOf(
            "【有利变更",
            "【不利变更",
            "延迟生效表",
            "全部次日",
            "延迟到次日生效",
            "将于明日"
        ).forEach { legacy ->
            assertFalse("legacy user-facing copy remains: $legacy", ui.contains(legacy))
        }

        assertTrue(ui.contains("【加严"))
        assertTrue(ui.contains("【放宽"))
        assertTrue(ui.contains("【恢复基准"))
        assertTrue(ui.contains("下一刷新时刻"))
    }

    @Test
    fun policyDescriptionsStayAlignedWithCanonicalTerms() {
        val favorable = EffectiveActivationPolicyManager.getPolicyDescription(
            EffectiveActivationPolicyManager.Policy.FAVORABLE_IMMEDIATE
        )
        val delayed = EffectiveActivationPolicyManager.getPolicyDescription(
            EffectiveActivationPolicyManager.Policy.ALL_NEXT_DAY
        )

        assertTrue(favorable.contains("加严立即生效"))
        assertTrue(favorable.contains("放宽在下一刷新时刻生效"))
        assertTrue(favorable.contains("恢复到当前基准的修改立即生效"))
        assertTrue(delayed.contains("所有修改"))
        assertTrue(delayed.contains("下一刷新时刻生效"))
    }
}
