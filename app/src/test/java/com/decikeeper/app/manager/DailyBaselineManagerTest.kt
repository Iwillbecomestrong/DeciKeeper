package com.decikeeper.app.manager

import android.content.Context
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
class DailyBaselineManagerTest {

    private lateinit var context: Context

    private fun prefs(name: String) = context.getSharedPreferences(name, Context.MODE_PRIVATE)

    private fun clearAll() {
        listOf(
            DeveloperOptionsGuardManager.PREFS_NAME,
            "pending_config_prefs",
            "decikeeper_rules_baseline",
            DailyBaselineManager.REPAIR_PREFS_NAME,
            CreditManager.PREFS_NAME
        ).forEach { prefs(it).edit().clear().commit() }
    }

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        clearAll()
        // 刷新时刻设为 00:00，保证「已过刷新时刻」恒成立，用例不依赖运行时间。
        prefs(CreditManager.PREFS_NAME).edit()
            .putString("config_refresh_time", "00:00")
            .putLong("first_setup_timestamp_v2", System.currentTimeMillis() - 2 * 24 * 60 * 60 * 1000L)
            .putBoolean("has_manually_ended_adaptation", true)
            .commit()
    }

    @After
    fun tearDown() = clearAll()

    @Test
    fun firstCallOfTheDayRecordsProtectionSwitchStates() {
        DeveloperOptionsGuardManager.setProtectionEnabled(context, true)

        assertTrue(DailyBaselineManager.ensureTodaySnapshot(context))

        assertTrue(TimeRuleManager.getBaselineDeveloperOptionsGuardState(context))
    }

    @Test
    fun secondCallOnTheSameDayIsANoOpAndDoesNotOverwriteTodaysBaseline() {
        DailyBaselineManager.ensureTodaySnapshot(context)

        // 拍照之后用户才开启开关：不得被后到的调用写进当天基准。
        DeveloperOptionsGuardManager.setProtectionEnabled(context, true)
        assertFalse(DailyBaselineManager.ensureTodaySnapshot(context))

        assertFalse(TimeRuleManager.getBaselineDeveloperOptionsGuardState(context))
        DeveloperOptionsGuardManager.setProtectionEnabled(context, false)
        assertEquals(DeveloperOptionsGuardManager.State.DISABLED, DeveloperOptionsGuardManager.getProtectionState(context))
    }

    @Test
    fun entryPointSnapshotRunsBeforeTheUserCanToggleSoLateServiceStartIsHarmless() {
        // 刷新时刻那一刻服务没跑：用户先打开应用（入口先拍），之后才开启开关。
        DailyBaselineManager.ensureTodaySnapshot(context)
        DeveloperOptionsGuardManager.setProtectionEnabled(context, true)

        // 服务稍后才启动，再来一次也不会把「当天开启」记成基准。
        DailyBaselineManager.ensureTodaySnapshot(context)

        assertFalse(TimeRuleManager.getBaselineDeveloperOptionsGuardState(context))
    }

    @Test
    fun oneTimeRepairClearsThePollutedDevOptionsGuardBaselineSoItCanBeRestoredImmediately() {
        // 旧版本把当天开启的开关误拍进了基准，且当天的每日快照已经跑过（升级后不会重拍）。
        prefs(CreditManager.PREFS_NAME).edit().putLong("last_baseline_update_time", System.currentTimeMillis()).commit()
        prefs("decikeeper_rules_baseline").edit()
            .putBoolean(DeveloperOptionsGuardManager.KEY_IS_ENABLED, true).commit()
        DeveloperOptionsGuardManager.setProtectionEnabled(context, true)

        DailyBaselineManager.ensureTodaySnapshot(context)

        assertFalse(TimeRuleManager.getBaselineDeveloperOptionsGuardState(context))
        DeveloperOptionsGuardManager.setProtectionEnabled(context, false)
        assertEquals(DeveloperOptionsGuardManager.State.DISABLED, DeveloperOptionsGuardManager.getProtectionState(context))
    }

    @Test
    fun oneTimeRepairRunsOnlyOnce() {
        DailyBaselineManager.ensureTodaySnapshot(context) // 第一次入口：修复并写标记

        // 之后若基准确实被正常写成「开」，不得再被修复覆盖。
        prefs("decikeeper_rules_baseline").edit()
            .putBoolean(DeveloperOptionsGuardManager.KEY_IS_ENABLED, true).commit()
        DailyBaselineManager.ensureTodaySnapshot(context)

        assertTrue(TimeRuleManager.getBaselineDeveloperOptionsGuardState(context))
    }

    @Test
    fun oneTimeRepairDoesNotTouchTheSwitchItself() {
        DeveloperOptionsGuardManager.setProtectionEnabled(context, true)

        DailyBaselineManager.ensureTodaySnapshot(context)

        assertEquals(DeveloperOptionsGuardManager.State.ACTIVE, DeveloperOptionsGuardManager.getProtectionState(context))
    }
}
