package com.zhizhi.app

import android.content.Context
import com.zhizhi.app.ui.RulesFragment
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
        val fragment = RulesFragment()

        // 初始状态下非 pending
        fragment.setA11yPendingDisable(context, false)
        assertFalse(fragment.isA11yPendingDisable(context))

        // 设置待关闭标记
        fragment.setA11yPendingDisable(context, true)
        assertTrue(fragment.isA11yPendingDisable(context))
        // 当处于待关闭状态时，isA11yServiceReady 必须为 false
        assertFalse(fragment.isA11yServiceReady(context))

        // 取消待关闭标记
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
