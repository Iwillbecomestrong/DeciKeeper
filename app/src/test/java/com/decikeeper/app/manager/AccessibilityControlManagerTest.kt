package com.decikeeper.app.manager

import android.content.Context
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
class AccessibilityControlManagerTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("accessibility_control_prefs", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("pending_config_prefs", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("decikeeper_rules_baseline", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("decikeeper_ledger", Context.MODE_PRIVATE).edit().clear().commit()
        A11ySelfDisableAuthorization.clear("test_setup")
    }

    @Test
    fun defaultStateIsDisabled() {
        assertEquals(AccessibilityControlManager.State.DISABLED, AccessibilityControlManager.getState(context))
        assertFalse(AccessibilityControlManager.getDesiredEnabled(context))
    }

    @Test
    fun setEnabledTrueSetsDesiredEnabledAndActiveState() {
        AccessibilityControlManager.setEnabled(context, true)

        assertTrue(AccessibilityControlManager.getDesiredEnabled(context))
        assertEquals(AccessibilityControlManager.State.ACTIVE, AccessibilityControlManager.getState(context))
    }

    @Test
    fun whenInAdaptationPeriod_disablingTakesEffectImmediatelyAndAuthorizesBypass() {
        // 适应期内
        context.getSharedPreferences("decikeeper_ledger", Context.MODE_PRIVATE)
            .edit()
            .putLong("first_setup_timestamp_v2", System.currentTimeMillis())
            .putBoolean("has_manually_ended_adaptation", false)
            .commit()

        assertTrue(TimeRuleManager.isInAdaptationPeriod(context))

        AccessibilityControlManager.setEnabled(context, true)
        assertEquals(AccessibilityControlManager.State.ACTIVE, AccessibilityControlManager.getState(context))

        AccessibilityControlManager.setEnabled(context, false)

        assertEquals(AccessibilityControlManager.State.DISABLED, AccessibilityControlManager.getState(context))
        assertFalse(AccessibilityControlManager.getDesiredEnabled(context))
        assertTrue(A11ySelfDisableAuthorization.isAuthorized())
    }

    @Test
    fun whenBaselineIsEmpty_disablingTakesEffectImmediately() {
        // 非适应期，但无基准（或基准为 false）
        context.getSharedPreferences("decikeeper_ledger", Context.MODE_PRIVATE)
            .edit()
            .putLong("first_setup_timestamp_v2", System.currentTimeMillis() - 30L * 86400000L)
            .putBoolean("has_manually_ended_adaptation", true)
            .commit()

        assertFalse(TimeRuleManager.isInAdaptationPeriod(context))
        assertFalse(TimeRuleManager.getBaselineA11yServiceState(context))

        AccessibilityControlManager.setEnabled(context, true)
        assertEquals(AccessibilityControlManager.State.ACTIVE, AccessibilityControlManager.getState(context))

        AccessibilityControlManager.setEnabled(context, false)

        assertEquals(AccessibilityControlManager.State.DISABLED, AccessibilityControlManager.getState(context))
        assertFalse(AccessibilityControlManager.getDesiredEnabled(context))
        assertTrue(A11ySelfDisableAuthorization.isAuthorized())
    }

    @Test
    fun whenBaselineIsActive_disablingSubmitsStrictNextDayPending() {
        // 非适应期，且基准为 true
        context.getSharedPreferences("decikeeper_ledger", Context.MODE_PRIVATE)
            .edit()
            .putLong("first_setup_timestamp_v2", System.currentTimeMillis() - 30L * 86400000L)
            .putBoolean("has_manually_ended_adaptation", true)
            .commit()

        context.getSharedPreferences("decikeeper_rules_baseline", Context.MODE_PRIVATE)
            .edit()
            .putBoolean("a11y_service_enabled", true)
            .commit()

        assertTrue(TimeRuleManager.getBaselineA11yServiceState(context))

        AccessibilityControlManager.setEnabled(context, true)
        assertEquals(AccessibilityControlManager.State.ACTIVE, AccessibilityControlManager.getState(context))

        AccessibilityControlManager.setEnabled(context, false)

        assertEquals(AccessibilityControlManager.State.PENDING, AccessibilityControlManager.getState(context))
        assertTrue(AccessibilityControlManager.getDesiredEnabled(context))
        assertFalse(A11ySelfDisableAuthorization.isAuthorized())
    }
}
