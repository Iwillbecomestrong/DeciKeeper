package com.decikeeper.app.protection

import android.content.Context
import com.decikeeper.app.ui.RulesFragment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class A11yProtectionDependencyRulesTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("accessibility_control_prefs", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("pending_config_prefs", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("decikeeper_rules_baseline", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("decikeeper_ledger", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun testIsA11yReady_pureLogic() {
        // 1. 系统开启且未申请待关闭 → Ready
        assertTrue(RulesFragment.isA11yReady(systemEnabled = true, pendingDisable = false))

        // 2. 系统开启但申请了明日关闭（待关闭） → NOT Ready
        assertFalse(RulesFragment.isA11yReady(systemEnabled = true, pendingDisable = true))

        // 3. 系统未开启且未申请待关闭 → NOT Ready
        assertFalse(RulesFragment.isA11yReady(systemEnabled = false, pendingDisable = false))

        // 4. 系统未开启且待关闭 → NOT Ready
        assertFalse(RulesFragment.isA11yReady(systemEnabled = false, pendingDisable = true))
    }

    @Test
    fun testA11yPendingDisable_storageAndReadyState() {
        // 模拟已过适应期且基准已开启，使关闭无障碍时进入严格次日生效 (PENDING)
        context.getSharedPreferences("decikeeper_ledger", Context.MODE_PRIVATE)
            .edit()
            .putLong("first_setup_timestamp_v2", System.currentTimeMillis() - 30L * 86400000L)
            .putBoolean("has_manually_ended_adaptation", true)
            .commit()
        context.getSharedPreferences("decikeeper_rules_baseline", Context.MODE_PRIVATE)
            .edit()
            .putBoolean("a11y_service_enabled", true)
            .commit()

        val fragment = RulesFragment()

        // 开启服务状态下非 pending
        fragment.setA11yPendingDisable(context, false)
        assertFalse(fragment.isA11yPendingDisable(context))

        // 申请关闭（进入待关闭状态 PENDING）
        fragment.setA11yPendingDisable(context, true)
        assertTrue(fragment.isA11yPendingDisable(context))
        // 当处于待关闭状态时，isA11yServiceReady 必须为 false
        assertFalse(fragment.isA11yServiceReady(context))

        // 重新开启（取消待关闭标记）
        fragment.setA11yPendingDisable(context, false)
        assertFalse(fragment.isA11yPendingDisable(context))
    }

    @Test
    fun testChildProtectionPrerequisite_blockedWhenA11yNotReady() {
        // 防卸载门禁测试：只要无障碍未就绪，必须拦截开启
        assertTrue(
            "无障碍待关闭或未开启时，防卸载必须拦截开启",
            RulesFragment.shouldBlockAntiUninstallEnable(
                isAccessibilityEnabled = false,
                allPrerequisitesReady = true
            )
        )

        // 隐私空间门禁测试：只要无障碍未就绪，必须拦截开启
        assertTrue(
            "无障碍待关闭或未开启时，隐私空间必须拦截开启",
            RulesFragment.shouldBlockPrivacySpaceGuardEnable(isAccessibilityEnabled = false)
        )
        assertFalse(
            "无障碍正常就绪时，隐私空间允许开启",
            RulesFragment.shouldBlockPrivacySpaceGuardEnable(isAccessibilityEnabled = true)
        )
    }
}
