package com.decikeeper.app.protection

import android.content.Context
import com.decikeeper.app.manager.InstallGuardManager
import com.decikeeper.app.manager.TimeRuleManager
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
class InstallGuardManagerTest {

    private lateinit var context: Context

    private fun prefs(name: String) = context.getSharedPreferences(name, Context.MODE_PRIVATE)

    private fun clearAll() {
        listOf(InstallGuardManager.PREFS_NAME, "pending_config_prefs", "decikeeper_rules_baseline", "decikeeper_ledger")
            .forEach { prefs(it).edit().clear().commit() }
    }

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        clearAll()
    }

    @After
    fun tearDown() = clearAll()

    private fun leaveAdaptationPeriod() {
        prefs("decikeeper_ledger").edit()
            .putLong("first_setup_timestamp_v2", System.currentTimeMillis() - 2 * 24 * 60 * 60 * 1000L)
            .putBoolean("has_manually_ended_adaptation", true)
            .commit()
    }

    @Test
    fun initialState_isDisabled() {
        assertEquals(InstallGuardManager.State.DISABLED, InstallGuardManager.getProtectionState(context))
        assertFalse(InstallGuardManager.isEnforcing(context))
    }

    @Test
    fun enable_becomesActiveImmediately() {
        InstallGuardManager.setProtectionEnabled(context, true)
        assertEquals(InstallGuardManager.State.ACTIVE, InstallGuardManager.getProtectionState(context))
        assertTrue(InstallGuardManager.isEnforcing(context))
    }

    @Test
    fun disableWhenBaselineIsOn_isPendingAndStillEnforcing() {
        leaveAdaptationPeriod()
        prefs("decikeeper_rules_baseline").edit().putBoolean(InstallGuardManager.KEY_IS_ENABLED, true).commit()

        InstallGuardManager.setProtectionEnabled(context, true)
        InstallGuardManager.setProtectionEnabled(context, false)

        assertEquals(InstallGuardManager.State.PENDING, InstallGuardManager.getProtectionState(context))
        assertTrue(InstallGuardManager.isEnforcing(context))
        assertTrue(InstallGuardManager.getPendingDisableTime(context) > System.currentTimeMillis())
    }

    @Test
    fun disableWhenBaselineIsEmpty_restoresImmediately() {
        leaveAdaptationPeriod()
        InstallGuardManager.setProtectionEnabled(context, true)
        InstallGuardManager.setProtectionEnabled(context, false)

        assertEquals(InstallGuardManager.State.DISABLED, InstallGuardManager.getProtectionState(context))
        assertEquals(0L, InstallGuardManager.getPendingDisableTime(context))
    }

    @Test
    fun disableDuringAdaptation_isImmediate() {
        prefs("decikeeper_ledger").edit().putLong("first_setup_timestamp_v2", System.currentTimeMillis()).commit()
        prefs("decikeeper_rules_baseline").edit().putBoolean(InstallGuardManager.KEY_IS_ENABLED, true).commit()

        InstallGuardManager.setProtectionEnabled(context, true)
        InstallGuardManager.setProtectionEnabled(context, false)

        assertEquals(InstallGuardManager.State.DISABLED, InstallGuardManager.getProtectionState(context))
    }

    @Test
    fun reEnableCancelsPendingDisable() {
        leaveAdaptationPeriod()
        prefs("decikeeper_rules_baseline").edit().putBoolean(InstallGuardManager.KEY_IS_ENABLED, true).commit()
        InstallGuardManager.setProtectionEnabled(context, true)
        InstallGuardManager.setProtectionEnabled(context, false)

        InstallGuardManager.setProtectionEnabled(context, true)

        assertEquals(InstallGuardManager.State.ACTIVE, InstallGuardManager.getProtectionState(context))
        assertEquals(0L, InstallGuardManager.getPendingDisableTime(context))
    }

    @Test
    fun pendingDisableAfterEffectiveTime_becomesDisabled() {
        prefs(InstallGuardManager.PREFS_NAME).edit().putBoolean(InstallGuardManager.KEY_IS_ENABLED, true).commit()
        prefs("pending_config_prefs").edit()
            .putLong("${InstallGuardManager.KEY_PENDING_DISABLE}_effective_time", System.currentTimeMillis() - 1L)
            .commit()

        assertEquals(InstallGuardManager.State.DISABLED, InstallGuardManager.getProtectionState(context))
        assertFalse(InstallGuardManager.isEnforcing(context))
    }

    @Test
    fun dailySnapshotRecordsOnlyTheActiveState() {
        InstallGuardManager.setProtectionEnabled(context, true)
        assertFalse(TimeRuleManager.getBaselineInstallGuardState(context))

        TimeRuleManager.saveBaselineParams(context)

        assertTrue(TimeRuleManager.getBaselineInstallGuardState(context))
    }
}
