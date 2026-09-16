package com.decikeeper.app.oem

import android.content.Context
import com.decikeeper.app.manager.AccessibilityControlManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AdaptationDisableBehaviorTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("decikeeper_ledger", Context.MODE_PRIVATE)
            .edit()
            .remove("first_setup_timestamp_v2")
            .putBoolean("has_manually_ended_adaptation", false)
            .commit()
    }

    @Test
    fun accessibilityProtectionDisablesImmediatelyDuringAdaptation() {
        AccessibilityControlManager.setEnabled(context, true)
        AccessibilityControlManager.setEnabled(context, false)

        assertEquals(
            AccessibilityControlManager.State.DISABLED,
            AccessibilityControlManager.getState(context)
        )
        assertFalse(
            context.getSharedPreferences("pending_config_prefs", Context.MODE_PRIVATE)
                .contains("accessibility_pending_disable_effective_time")
        )
    }
}
