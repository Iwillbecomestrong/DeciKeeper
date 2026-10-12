package com.decikeeper.app.protection

import android.content.Context
import com.decikeeper.app.manager.DateTimeGuardManager
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
class DateTimeGuardManagerTest {

    private lateinit var context: Context

    private fun prefs(name: String) = context.getSharedPreferences(name, Context.MODE_PRIVATE)

    private fun clearAll() {
        listOf(
            DateTimeGuardManager.PREFS_NAME,
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
        assertEquals(DateTimeGuardManager.State.DISABLED, DateTimeGuardManager.getProtectionState(context))
        assertFalse(DateTimeGuardManager.isEnforcing(context))
    }

    @Test
    fun enable_becomesActiveImmediately() {
        DateTimeGuardManager.setProtectionEnabled(context, true)
        assertEquals(DateTimeGuardManager.State.ACTIVE, DateTimeGuardManager.getProtectionState(context))
        assertTrue(DateTimeGuardManager.isEnforcing(context))
    }

    @Test
    fun disableWhenBaselineIsOn_isPendingAndStillEnforcing() {
        leaveAdaptationPeriod()
        prefs("decikeeper_rules_baseline").edit().putBoolean(DateTimeGuardManager.KEY_IS_ENABLED, true).commit()

        DateTimeGuardManager.setProtectionEnabled(context, true)
        DateTimeGuardManager.setProtectionEnabled(context, false)

        assertEquals(DateTimeGuardManager.State.PENDING, DateTimeGuardManager.getProtectionState(context))
        assertTrue(DateTimeGuardManager.isEnforcing(context))
        assertTrue(DateTimeGuardManager.getPendingDisableTime(context) > System.currentTimeMillis())
    }

    @Test
    fun disableWhenBaselineIsEmpty_restoresImmediately() {
        leaveAdaptationPeriod()

        DateTimeGuardManager.setProtectionEnabled(context, true)
        DateTimeGuardManager.setProtectionEnabled(context, false)

        assertEquals(DateTimeGuardManager.State.DISABLED, DateTimeGuardManager.getProtectionState(context))
        assertEquals(0L, DateTimeGuardManager.getPendingDisableTime(context))
        assertFalse(DateTimeGuardManager.isEnforcing(context))
    }

    @Test
    fun disableDuringAdaptation_isImmediate() {
        prefs("decikeeper_ledger").edit().putLong("first_setup_timestamp_v2", System.currentTimeMillis()).commit()
        prefs("decikeeper_rules_baseline").edit().putBoolean(DateTimeGuardManager.KEY_IS_ENABLED, true).commit()

        DateTimeGuardManager.setProtectionEnabled(context, true)
        DateTimeGuardManager.setProtectionEnabled(context, false)

        assertEquals(DateTimeGuardManager.State.DISABLED, DateTimeGuardManager.getProtectionState(context))
    }

    @Test
    fun reEnableCancelsPendingDisable() {
        leaveAdaptationPeriod()
        prefs("decikeeper_rules_baseline").edit().putBoolean(DateTimeGuardManager.KEY_IS_ENABLED, true).commit()
        DateTimeGuardManager.setProtectionEnabled(context, true)
        DateTimeGuardManager.setProtectionEnabled(context, false)

        DateTimeGuardManager.setProtectionEnabled(context, true)

        assertEquals(DateTimeGuardManager.State.ACTIVE, DateTimeGuardManager.getProtectionState(context))
        assertEquals(0L, DateTimeGuardManager.getPendingDisableTime(context))
    }

    @Test
    fun pendingDisableAfterEffectiveTime_becomesDisabled() {
        prefs(DateTimeGuardManager.PREFS_NAME)
            .edit().putBoolean(DateTimeGuardManager.KEY_IS_ENABLED, true).commit()
        prefs("pending_config_prefs").edit()
            .putLong("${DateTimeGuardManager.KEY_PENDING_DISABLE}_effective_time", System.currentTimeMillis() - 1L)
            .commit()

        assertEquals(DateTimeGuardManager.State.DISABLED, DateTimeGuardManager.getProtectionState(context))
        assertFalse(DateTimeGuardManager.isEnforcing(context))
    }

    @Test
    fun savingRulesDoesNotSnapshotBaseline_soTodaysNewSwitchCanStillBeRestoredImmediately() {
        leaveAdaptationPeriod()
        DateTimeGuardManager.setProtectionEnabled(context, true)

        // 保存时间规则不得补拍基准：基准只在每日刷新时刻与适应期结束时写入。
        TimeRuleManager.stagePendingRules(context, emptyList(), 60, 60, 60, 5, 50)
        TimeRuleManager.analyzeAndSaveGlobalParams(
            context, newRest = 60, newGap = 60, newLockDuration = 15L, newBorrow = 50,
            newNotifyFar = 5, newNotifyNear = 5, newWeeklyCount = 1, newWeeklyDuration = 480, newRefreshTime = "08:00"
        )
        assertFalse(TimeRuleManager.getBaselineDateTimeGuardState(context))

        DateTimeGuardManager.setProtectionEnabled(context, false)
        assertEquals(DateTimeGuardManager.State.DISABLED, DateTimeGuardManager.getProtectionState(context))
    }

    @Test
    fun afterDailySnapshotClosingBecomesPendingAndStillEnforcing() {
        leaveAdaptationPeriod()
        DateTimeGuardManager.setProtectionEnabled(context, true)

        TimeRuleManager.saveBaselineParams(context) // 次日刷新时刻的每日快照
        DateTimeGuardManager.setProtectionEnabled(context, false)

        assertEquals(DateTimeGuardManager.State.PENDING, DateTimeGuardManager.getProtectionState(context))
        assertTrue(DateTimeGuardManager.isEnforcing(context))
    }

    @Test
    fun baselineSnapshotOnlyRecordsActiveState() {
        DateTimeGuardManager.setProtectionEnabled(context, true)
        assertFalse(TimeRuleManager.getBaselineDateTimeGuardState(context))

        TimeRuleManager.saveBaselineParams(context)

        assertTrue(TimeRuleManager.getBaselineDateTimeGuardState(context))
    }
}
