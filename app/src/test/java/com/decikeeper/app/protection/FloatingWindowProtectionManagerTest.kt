package com.decikeeper.app.protection

import android.content.Context
import com.decikeeper.app.manager.FloatingWindowProtectionManager
import org.junit.After
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
class FloatingWindowProtectionManagerTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences(FloatingWindowProtectionManager.PREFS_NAME, Context.MODE_PRIVATE)
            .edit().clear().commit()
        context.getSharedPreferences("pending_config_prefs", Context.MODE_PRIVATE)
            .edit().clear().commit()
        context.getSharedPreferences("decikeeper_rules_baseline", Context.MODE_PRIVATE)
            .edit().clear().commit()
        context.getSharedPreferences("decikeeper_ledger", Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @After
    fun tearDown() {
        context.getSharedPreferences(FloatingWindowProtectionManager.PREFS_NAME, Context.MODE_PRIVATE)
            .edit().clear().commit()
        context.getSharedPreferences("pending_config_prefs", Context.MODE_PRIVATE)
            .edit().clear().commit()
        context.getSharedPreferences("decikeeper_ledger", Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @Test
    fun testInitialState_isDisabled() {
        assertEquals(FloatingWindowProtectionManager.State.DISABLED, FloatingWindowProtectionManager.getProtectionState(context))
        assertFalse(FloatingWindowProtectionManager.isEnforcing(context))
    }

    @Test
    fun testEnable_becomesActiveImmediately() {
        FloatingWindowProtectionManager.setProtectionEnabled(context, true)
        assertEquals(FloatingWindowProtectionManager.State.ACTIVE, FloatingWindowProtectionManager.getProtectionState(context))
        assertTrue(FloatingWindowProtectionManager.isEnforcing(context))
    }

    @Test
    fun testDisableImmediatelyForAccessibilityUnavailable() {
        FloatingWindowProtectionManager.setProtectionEnabled(context, true)
        assertTrue(FloatingWindowProtectionManager.isEnforcing(context))

        FloatingWindowProtectionManager.disableImmediatelyForAccessibilityUnavailable(context)
        assertEquals(FloatingWindowProtectionManager.State.DISABLED, FloatingWindowProtectionManager.getProtectionState(context))
        assertFalse(FloatingWindowProtectionManager.isEnforcing(context))
    }

    @Test
    fun testDisableAfterActiveBaseline_isPendingAndStillEnforcing() {
        context.getSharedPreferences("decikeeper_ledger", Context.MODE_PRIVATE)
            .edit()
            .putLong("first_setup_timestamp_v2", System.currentTimeMillis() - 2 * 24 * 60 * 60 * 1000L)
            .putBoolean("has_manually_ended_adaptation", true)
            .commit()

        FloatingWindowProtectionManager.setProtectionEnabled(context, true)
        FloatingWindowProtectionManager.setProtectionEnabled(context, false)

        assertEquals(FloatingWindowProtectionManager.State.PENDING, FloatingWindowProtectionManager.getProtectionState(context))
        assertTrue(FloatingWindowProtectionManager.isEnforcing(context))
        assertTrue(FloatingWindowProtectionManager.getPendingDisableTime(context) > System.currentTimeMillis())
    }

    @Test
    fun testPendingDisableAfterEffectiveTime_becomesDisabled() {
        context.getSharedPreferences(FloatingWindowProtectionManager.PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(FloatingWindowProtectionManager.KEY_IS_ENABLED, true).commit()
        context.getSharedPreferences("pending_config_prefs", Context.MODE_PRIVATE)
            .edit().putLong("${FloatingWindowProtectionManager.KEY_PENDING_DISABLE}_effective_time", System.currentTimeMillis() - 1L).commit()

        assertEquals(FloatingWindowProtectionManager.State.DISABLED, FloatingWindowProtectionManager.getProtectionState(context))
        assertFalse(FloatingWindowProtectionManager.isEnforcing(context))
    }

    @Test
    fun testDisableDuringAdaptation_isImmediate() {
        context.getSharedPreferences("decikeeper_ledger", Context.MODE_PRIVATE)
            .edit().putLong("first_setup_timestamp_v2", System.currentTimeMillis()).commit()

        FloatingWindowProtectionManager.setProtectionEnabled(context, true)
        FloatingWindowProtectionManager.setProtectionEnabled(context, false)

        assertEquals(FloatingWindowProtectionManager.State.DISABLED, FloatingWindowProtectionManager.getProtectionState(context))
        assertEquals(0L, FloatingWindowProtectionManager.getPendingDisableTime(context))
    }
}
