package com.decikeeper.app.protection

import android.content.Context
import com.decikeeper.app.manager.AccessibilityControlManager
import com.decikeeper.app.manager.AntiUninstallManager
import com.decikeeper.app.manager.FloatingWindowProtectionManager
import com.decikeeper.app.manager.GuardFloatingWindowManager
import com.decikeeper.app.manager.PrivacyProtectionManager
import com.decikeeper.app.manager.TimeRuleManager
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
class AccessibilityAndEnhancementBaselineRestorationContractTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        // 确保非适应期
        context.getSharedPreferences("decikeeper_ledger", Context.MODE_PRIVATE)
            .edit()
            .putLong("first_setup_timestamp_v2", System.currentTimeMillis() - 30L * 86400000L)
            .putBoolean("has_manually_ended_adaptation", true)
            .commit()

        context.getSharedPreferences("accessibility_control_prefs", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("anti_uninstall_prefs", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences(PrivacyProtectionManager.PREFS_NAME, Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences(FloatingWindowProtectionManager.PREFS_NAME, Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("guard_floating_window_prefs", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("pending_config_prefs", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("decikeeper_rules_baseline", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun whenBaselineIsEmptyOrDisabled_turningOffImmediatelyRestoresToDisabled() {
        // 基准为空（即默认为关），非适应期
        assertFalse(TimeRuleManager.isInAdaptationPeriod(context))
        assertFalse(TimeRuleManager.getBaselineA11yServiceState(context))
        assertFalse(TimeRuleManager.getBaselineFloatingGuardState(context))
        assertFalse(TimeRuleManager.getBaselineAntiUninstallState(context))
        assertFalse(TimeRuleManager.getBaselinePrivacyProtectionState(context))
        assertFalse(TimeRuleManager.getBaselineFloatingWindowProtectionState(context))

        // 今天开启
        AccessibilityControlManager.setEnabled(context, true)
        GuardFloatingWindowManager.setFloatingGuardEnabled(context, true)
        AntiUninstallManager.setProtectionEnabled(context, true)
        PrivacyProtectionManager.setProtectionEnabled(context, true)
        FloatingWindowProtectionManager.setProtectionEnabled(context, true)

        assertEquals(AccessibilityControlManager.State.ACTIVE, AccessibilityControlManager.getState(context))
        assertEquals(GuardFloatingWindowManager.State.ACTIVE, GuardFloatingWindowManager.getProtectionState(context))
        assertEquals(AntiUninstallManager.State.ACTIVE, AntiUninstallManager.getProtectionState(context))
        assertEquals(PrivacyProtectionManager.State.ACTIVE, PrivacyProtectionManager.getProtectionState(context))
        assertEquals(FloatingWindowProtectionManager.State.ACTIVE, FloatingWindowProtectionManager.getProtectionState(context))

        // 随后今天想关闭 -> 应该恢复基准，立即变为 DISABLED，不进 PENDING！
        AccessibilityControlManager.setEnabled(context, false)
        GuardFloatingWindowManager.setFloatingGuardEnabled(context, false)
        AntiUninstallManager.setProtectionEnabled(context, false)
        PrivacyProtectionManager.setProtectionEnabled(context, false)
        FloatingWindowProtectionManager.setProtectionEnabled(context, false)

        assertEquals(AccessibilityControlManager.State.DISABLED, AccessibilityControlManager.getState(context))
        assertEquals(GuardFloatingWindowManager.State.DISABLED, GuardFloatingWindowManager.getProtectionState(context))
        assertEquals(AntiUninstallManager.State.DISABLED, AntiUninstallManager.getProtectionState(context))
        assertEquals(PrivacyProtectionManager.State.DISABLED, PrivacyProtectionManager.getProtectionState(context))
        assertEquals(FloatingWindowProtectionManager.State.DISABLED, FloatingWindowProtectionManager.getProtectionState(context))
    }

    @Test
    fun whenBaselineIsActive_turningOffSubmitsPendingStrictNextDay() {
        // 模拟基准为 ACTIVE
        context.getSharedPreferences("decikeeper_rules_baseline", Context.MODE_PRIVATE)
            .edit()
            .putBoolean("a11y_service_enabled", true)
            .putBoolean("floating_guard_enabled", true)
            .putBoolean("is_anti_uninstall_enabled", true)
            .putBoolean("is_privacy_protection_enabled", true)
            .putBoolean("is_floating_window_protection_enabled", true)
            .commit()

        assertTrue(TimeRuleManager.getBaselineA11yServiceState(context))
        assertTrue(TimeRuleManager.getBaselineFloatingGuardState(context))
        assertTrue(TimeRuleManager.getBaselineAntiUninstallState(context))
        assertTrue(TimeRuleManager.getBaselinePrivacyProtectionState(context))
        assertTrue(TimeRuleManager.getBaselineFloatingWindowProtectionState(context))

        // 正常开启状态
        AccessibilityControlManager.setEnabled(context, true)
        GuardFloatingWindowManager.setFloatingGuardEnabled(context, true)
        AntiUninstallManager.setProtectionEnabled(context, true)
        PrivacyProtectionManager.setProtectionEnabled(context, true)
        FloatingWindowProtectionManager.setProtectionEnabled(context, true)

        // 申请关闭 -> 因基准为开，这是较基准放宽操作，应进入 PENDING
        AccessibilityControlManager.setEnabled(context, false)
        GuardFloatingWindowManager.setFloatingGuardEnabled(context, false)
        AntiUninstallManager.setProtectionEnabled(context, false)
        PrivacyProtectionManager.setProtectionEnabled(context, false)
        FloatingWindowProtectionManager.setProtectionEnabled(context, false)

        assertEquals(AccessibilityControlManager.State.PENDING, AccessibilityControlManager.getState(context))
        assertEquals(GuardFloatingWindowManager.State.PENDING, GuardFloatingWindowManager.getProtectionState(context))
        assertEquals(AntiUninstallManager.State.PENDING, AntiUninstallManager.getProtectionState(context))
        assertEquals(PrivacyProtectionManager.State.PENDING, PrivacyProtectionManager.getProtectionState(context))
        assertEquals(FloatingWindowProtectionManager.State.PENDING, FloatingWindowProtectionManager.getProtectionState(context))
    }

    @Test
    fun whenSystemA11yIsEnabled_saveBaselineParamsCapturesActiveAndHealsState() {
        // 模拟系统无障碍服务已开启（但尚未被明确 setEnabled）
        android.provider.Settings.Secure.putString(
            context.contentResolver,
            android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            context.packageName + "/com.decikeeper.app.service.CoreService"
        )
        // 此时 desiredEnabled 为 false
        assertEquals(AccessibilityControlManager.State.DISABLED, AccessibilityControlManager.getState(context))

        // 执行基准保存（如结束适应期时）
        TimeRuleManager.saveBaselineParams(context)

        // 验证：基准应记录为 true，且状态自愈为 ACTIVE
        assertTrue(TimeRuleManager.getBaselineA11yServiceState(context))
        assertEquals(AccessibilityControlManager.State.ACTIVE, AccessibilityControlManager.getState(context))

        // 关闭申请应严格走次日延迟
        AccessibilityControlManager.setEnabled(context, false)
        assertEquals(AccessibilityControlManager.State.PENDING, AccessibilityControlManager.getState(context))
    }
}
