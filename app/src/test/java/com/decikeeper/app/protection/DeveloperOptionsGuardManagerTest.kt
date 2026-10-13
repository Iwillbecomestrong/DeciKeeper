package com.decikeeper.app.protection

import android.content.Context
import com.decikeeper.app.manager.DeveloperOptionsGuardManager
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
class DeveloperOptionsGuardManagerTest {

    private lateinit var context: Context

    private fun prefs(name: String) = context.getSharedPreferences(name, Context.MODE_PRIVATE)

    private fun clearAll() {
        listOf(
            DeveloperOptionsGuardManager.PREFS_NAME,
            "pending_config_prefs",
            "decikeeper_rules_baseline",
            "decikeeper_ledger"
        ).forEach { prefs(it).edit().clear().commit() }
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
        assertEquals(DeveloperOptionsGuardManager.State.DISABLED, DeveloperOptionsGuardManager.getProtectionState(context))
        assertFalse(DeveloperOptionsGuardManager.isEnforcing(context))
    }

    @Test
    fun enable_becomesActiveImmediately() {
        DeveloperOptionsGuardManager.setProtectionEnabled(context, true)
        assertEquals(DeveloperOptionsGuardManager.State.ACTIVE, DeveloperOptionsGuardManager.getProtectionState(context))
        assertTrue(DeveloperOptionsGuardManager.isEnforcing(context))
    }

    @Test
    fun disableWhenBaselineIsOn_isPendingAndStillEnforcing() {
        leaveAdaptationPeriod()
        prefs("decikeeper_rules_baseline").edit().putBoolean(DeveloperOptionsGuardManager.KEY_IS_ENABLED, true).commit()

        DeveloperOptionsGuardManager.setProtectionEnabled(context, true)
        DeveloperOptionsGuardManager.setProtectionEnabled(context, false)

        assertEquals(DeveloperOptionsGuardManager.State.PENDING, DeveloperOptionsGuardManager.getProtectionState(context))
        assertTrue(DeveloperOptionsGuardManager.isEnforcing(context))
        assertTrue(DeveloperOptionsGuardManager.getPendingDisableTime(context) > System.currentTimeMillis())
    }

    @Test
    fun disableWhenBaselineIsEmpty_restoresImmediately() {
        leaveAdaptationPeriod()

        DeveloperOptionsGuardManager.setProtectionEnabled(context, true)
        DeveloperOptionsGuardManager.setProtectionEnabled(context, false)

        assertEquals(DeveloperOptionsGuardManager.State.DISABLED, DeveloperOptionsGuardManager.getProtectionState(context))
        assertEquals(0L, DeveloperOptionsGuardManager.getPendingDisableTime(context))
        assertFalse(DeveloperOptionsGuardManager.isEnforcing(context))
    }

    @Test
    fun disableDuringAdaptation_isImmediate() {
        prefs("decikeeper_ledger").edit().putLong("first_setup_timestamp_v2", System.currentTimeMillis()).commit()
        prefs("decikeeper_rules_baseline").edit().putBoolean(DeveloperOptionsGuardManager.KEY_IS_ENABLED, true).commit()

        DeveloperOptionsGuardManager.setProtectionEnabled(context, true)
        DeveloperOptionsGuardManager.setProtectionEnabled(context, false)

        assertEquals(DeveloperOptionsGuardManager.State.DISABLED, DeveloperOptionsGuardManager.getProtectionState(context))
    }

    @Test
    fun reEnableCancelsPendingDisable() {
        leaveAdaptationPeriod()
        prefs("decikeeper_rules_baseline").edit().putBoolean(DeveloperOptionsGuardManager.KEY_IS_ENABLED, true).commit()
        DeveloperOptionsGuardManager.setProtectionEnabled(context, true)
        DeveloperOptionsGuardManager.setProtectionEnabled(context, false)

        DeveloperOptionsGuardManager.setProtectionEnabled(context, true)

        assertEquals(DeveloperOptionsGuardManager.State.ACTIVE, DeveloperOptionsGuardManager.getProtectionState(context))
        assertEquals(0L, DeveloperOptionsGuardManager.getPendingDisableTime(context))
    }

    @Test
    fun pendingDisableAfterEffectiveTime_becomesDisabled() {
        prefs(DeveloperOptionsGuardManager.PREFS_NAME)
            .edit().putBoolean(DeveloperOptionsGuardManager.KEY_IS_ENABLED, true).commit()
        prefs("pending_config_prefs").edit()
            .putLong("${DeveloperOptionsGuardManager.KEY_PENDING_DISABLE}_effective_time", System.currentTimeMillis() - 1L)
            .commit()

        assertEquals(DeveloperOptionsGuardManager.State.DISABLED, DeveloperOptionsGuardManager.getProtectionState(context))
        assertFalse(DeveloperOptionsGuardManager.isEnforcing(context))
    }

    @Test
    fun savingRulesDoesNotSnapshotBaseline_soTodaysNewSwitchCanStillBeRestoredImmediately() {
        leaveAdaptationPeriod()
        DeveloperOptionsGuardManager.setProtectionEnabled(context, true)

        // 保存时间规则不得补拍基准：基准只在每日刷新时刻与适应期结束时写入。
        TimeRuleManager.stagePendingRules(context, emptyList(), 60, 60, 60, 5, 50)
        TimeRuleManager.analyzeAndSaveGlobalParams(
            context, newRest = 60, newGap = 60, newLockDuration = 15L, newBorrow = 50,
            newNotifyFar = 5, newNotifyNear = 5, newWeeklyCount = 1, newWeeklyDuration = 480, newRefreshTime = "08:00"
        )
        assertFalse(TimeRuleManager.getBaselineDeveloperOptionsGuardState(context))

        DeveloperOptionsGuardManager.setProtectionEnabled(context, false)
        assertEquals(DeveloperOptionsGuardManager.State.DISABLED, DeveloperOptionsGuardManager.getProtectionState(context))
    }

    @Test
    fun afterDailySnapshotClosingBecomesPendingAndStillEnforcing() {
        leaveAdaptationPeriod()
        DeveloperOptionsGuardManager.setProtectionEnabled(context, true)

        TimeRuleManager.saveBaselineParams(context) // 次日刷新时刻的每日快照
        DeveloperOptionsGuardManager.setProtectionEnabled(context, false)

        assertEquals(DeveloperOptionsGuardManager.State.PENDING, DeveloperOptionsGuardManager.getProtectionState(context))
        assertTrue(DeveloperOptionsGuardManager.isEnforcing(context))
    }

    @Test
    fun baselineSnapshotOnlyRecordsActiveState() {
        DeveloperOptionsGuardManager.setProtectionEnabled(context, true)
        assertFalse(TimeRuleManager.getBaselineDeveloperOptionsGuardState(context))

        TimeRuleManager.saveBaselineParams(context)

        assertTrue(TimeRuleManager.getBaselineDeveloperOptionsGuardState(context))
    }
}
