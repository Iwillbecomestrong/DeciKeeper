package com.decikeeper.app.manager

import android.content.Context
import com.decikeeper.app.manager.DevGuardExemptionManager.Guard
import com.decikeeper.app.manager.DevGuardExemptionManager.Outcome
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.Calendar

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class DevGuardExemptionManagerTest {

    private lateinit var context: Context

    private fun prefs(name: String) = context.getSharedPreferences(name, Context.MODE_PRIVATE)

    private fun clearAll() {
        listOf(
            DevGuardExemptionManager.PREFS_NAME,
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


    /** 基准里两个拦截都是开的：豁免才有可放松的保护，改宽才需要次日生效。 */
    private fun guardsProtectedAtBaseline(install: Boolean = true, developerOptions: Boolean = true) {
        prefs("decikeeper_rules_baseline").edit()
            .putBoolean(InstallGuardManager.KEY_IS_ENABLED, install)
            .putBoolean(DeveloperOptionsGuardManager.KEY_IS_ENABLED, developerOptions)
            .commit()
    }

    private fun w(sh: Int, sm: Int, eh: Int, em: Int) = DevGuardExemptionWindow(sh * 60 + sm, eh * 60 + em)

    /** 把某个待生效项的生效时间改到过去，模拟次日刷新时刻已过。 */
    private fun expirePending(key: String) {
        prefs("pending_config_prefs").edit()
            .putLong("${key}_effective_time", System.currentTimeMillis() - 1L).commit()
    }

    private fun millisAt(hour: Int, minute: Int): Long = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, hour); set(Calendar.MINUTE, minute); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    @Test
    fun defaults_windowIsEveningAndNothingExempt() {
        assertEquals(DevGuardExemptionWindow.DEFAULT, DevGuardExemptionManager.getWindow(context))
        assertFalse(DevGuardExemptionManager.isExemptionEnabled(context, Guard.INSTALL))
        assertFalse(DevGuardExemptionManager.isExemptionEnabled(context, Guard.DEVELOPER_OPTIONS))
        assertFalse(DevGuardExemptionManager.isExempt(context, Guard.INSTALL, millisAt(20, 45)))
    }

    @Test
    fun enablingExemptionIsLooserSoItWaitsForNextDay() {
        leaveAdaptationPeriod()
        guardsProtectedAtBaseline()

        val outcome = DevGuardExemptionManager.setExemptionEnabled(context, Guard.INSTALL, true)

        assertEquals(Outcome.PENDING, outcome)
        assertFalse(DevGuardExemptionManager.isExemptionEnabled(context, Guard.INSTALL))
        assertEquals(true, DevGuardExemptionManager.getPendingExemption(context, Guard.INSTALL))
        assertTrue(DevGuardExemptionManager.getPendingEffectiveTime(context, DevGuardExemptionManager.Item.INSTALL_FLAG) > System.currentTimeMillis())
        assertFalse(DevGuardExemptionManager.isExempt(context, Guard.INSTALL, millisAt(20, 45)))
    }

    @Test
    fun pendingExemptionBecomesActiveAfterEffectiveTime() {
        leaveAdaptationPeriod()
        guardsProtectedAtBaseline()
        DevGuardExemptionManager.setExemptionEnabled(context, Guard.INSTALL, true)

        expirePending(DevGuardExemptionManager.KEY_PENDING_INSTALL_FLAG)

        assertTrue(DevGuardExemptionManager.isExemptionEnabled(context, Guard.INSTALL))
        assertNull(DevGuardExemptionManager.getPendingExemption(context, Guard.INSTALL))
        assertTrue(DevGuardExemptionManager.isExempt(context, Guard.INSTALL, millisAt(20, 45)))
    }

    @Test
    fun enablingDuringAdaptationIsImmediate() {
        prefs("decikeeper_ledger").edit().putLong("first_setup_timestamp_v2", System.currentTimeMillis()).commit()

        assertEquals(Outcome.APPLIED, DevGuardExemptionManager.setExemptionEnabled(context, Guard.DEVELOPER_OPTIONS, true))
        assertTrue(DevGuardExemptionManager.isExemptionEnabled(context, Guard.DEVELOPER_OPTIONS))
    }

    @Test
    fun disablingExemptionIsStricterAndImmediate() {
        prefs("decikeeper_ledger").edit().putLong("first_setup_timestamp_v2", System.currentTimeMillis()).commit()
        DevGuardExemptionManager.setExemptionEnabled(context, Guard.INSTALL, true)
        leaveAdaptationPeriod()

        assertEquals(Outcome.APPLIED, DevGuardExemptionManager.setExemptionEnabled(context, Guard.INSTALL, false))
        assertFalse(DevGuardExemptionManager.isExemptionEnabled(context, Guard.INSTALL))
    }

    @Test
    fun guardsKeepSeparateFlags() {
        prefs("decikeeper_ledger").edit().putLong("first_setup_timestamp_v2", System.currentTimeMillis()).commit()
        DevGuardExemptionManager.setExemptionEnabled(context, Guard.INSTALL, true)

        assertTrue(DevGuardExemptionManager.isExempt(context, Guard.INSTALL, millisAt(21, 0)))
        assertFalse(DevGuardExemptionManager.isExempt(context, Guard.DEVELOPER_OPTIONS, millisAt(21, 0)))
    }

    @Test
    fun exemptOnlyInsideWindow() {
        prefs("decikeeper_ledger").edit().putLong("first_setup_timestamp_v2", System.currentTimeMillis()).commit()
        DevGuardExemptionManager.setExemptionEnabled(context, Guard.INSTALL, true)

        assertFalse(DevGuardExemptionManager.isExempt(context, Guard.INSTALL, millisAt(20, 29)))
        assertTrue(DevGuardExemptionManager.isExempt(context, Guard.INSTALL, millisAt(20, 30)))
        assertTrue(DevGuardExemptionManager.isExempt(context, Guard.INSTALL, millisAt(21, 29)))
        assertFalse(DevGuardExemptionManager.isExempt(context, Guard.INSTALL, millisAt(21, 30)))
        assertFalse(DevGuardExemptionManager.isExempt(context, Guard.INSTALL, millisAt(12, 0)))
    }

    @Test
    fun shrinkingWindowIsImmediate() {
        leaveAdaptationPeriod()

        assertEquals(Outcome.APPLIED, DevGuardExemptionManager.setWindow(context, w(20, 40, 21, 20)))
        assertEquals(w(20, 40, 21, 20), DevGuardExemptionManager.getWindow(context))
        assertNull(DevGuardExemptionManager.getPendingWindow(context))
    }

    @Test
    fun widerOrShiftedWindowWaitsForNextDayAndOldWindowStaysActive() {
        leaveAdaptationPeriod()
        guardsProtectedAtBaseline()

        assertEquals(Outcome.PENDING, DevGuardExemptionManager.setWindow(context, w(20, 0, 21, 30)))
        assertEquals(DevGuardExemptionWindow.DEFAULT, DevGuardExemptionManager.getWindow(context))
        assertEquals(w(20, 0, 21, 30), DevGuardExemptionManager.getPendingWindow(context))

        assertEquals(Outcome.PENDING, DevGuardExemptionManager.setWindow(context, w(21, 0, 22, 0)))
        assertEquals(DevGuardExemptionWindow.DEFAULT, DevGuardExemptionManager.getWindow(context))
        assertEquals(w(21, 0, 22, 0), DevGuardExemptionManager.getPendingWindow(context))
    }

    @Test
    fun pendingWindowBecomesActiveAfterEffectiveTime() {
        leaveAdaptationPeriod()
        guardsProtectedAtBaseline()
        DevGuardExemptionManager.setWindow(context, w(20, 0, 22, 0))

        expirePending(DevGuardExemptionManager.KEY_PENDING_WINDOW)

        assertEquals(w(20, 0, 22, 0), DevGuardExemptionManager.getWindow(context))
        assertNull(DevGuardExemptionManager.getPendingWindow(context))
    }

    @Test
    fun windowChangeDuringAdaptationIsImmediate() {
        prefs("decikeeper_ledger").edit().putLong("first_setup_timestamp_v2", System.currentTimeMillis()).commit()

        assertEquals(Outcome.APPLIED, DevGuardExemptionManager.setWindow(context, w(19, 0, 23, 0)))
        assertEquals(w(19, 0, 23, 0), DevGuardExemptionManager.getWindow(context))
    }

    @Test
    fun settingActiveValueAgainCancelsPendingChange() {
        leaveAdaptationPeriod()
        guardsProtectedAtBaseline()
        DevGuardExemptionManager.setWindow(context, w(20, 0, 22, 0))
        DevGuardExemptionManager.setExemptionEnabled(context, Guard.INSTALL, true)

        assertEquals(Outcome.UNCHANGED, DevGuardExemptionManager.setWindow(context, DevGuardExemptionWindow.DEFAULT))
        assertEquals(Outcome.UNCHANGED, DevGuardExemptionManager.setExemptionEnabled(context, Guard.INSTALL, false))

        assertNull(DevGuardExemptionManager.getPendingWindow(context))
        assertNull(DevGuardExemptionManager.getPendingExemption(context, Guard.INSTALL))
    }

    @Test
    fun tighteningWhileAWiderChangeIsPendingCancelsThePending() {
        leaveAdaptationPeriod()
        guardsProtectedAtBaseline()
        DevGuardExemptionManager.setWindow(context, w(20, 0, 22, 0))

        assertEquals(Outcome.APPLIED, DevGuardExemptionManager.setWindow(context, w(21, 0, 21, 20)))

        assertEquals(w(21, 0, 21, 20), DevGuardExemptionManager.getWindow(context))
        assertNull(DevGuardExemptionManager.getPendingWindow(context))
    }

    @Test
    fun restoringTowardsBaselineIsImmediate() {
        leaveAdaptationPeriod()
        TimeRuleManager.saveBaselineParams(context) // 基准 = 默认窗口
        guardsProtectedAtBaseline()
        DevGuardExemptionManager.setWindow(context, w(21, 0, 21, 20)) // 改严，立即

        // 放回基准窗口不比基准宽，立即生效。
        assertEquals(Outcome.APPLIED, DevGuardExemptionManager.setWindow(context, DevGuardExemptionWindow.DEFAULT))
        assertEquals(DevGuardExemptionWindow.DEFAULT, DevGuardExemptionManager.getWindow(context))
    }

    @Test
    fun restoringExemptionFlagToBaselineIsImmediate() {
        prefs("decikeeper_ledger").edit().putLong("first_setup_timestamp_v2", System.currentTimeMillis()).commit()
        DevGuardExemptionManager.setExemptionEnabled(context, Guard.INSTALL, true)
        leaveAdaptationPeriod()
        TimeRuleManager.saveBaselineParams(context) // 基准：安装豁免 = 开
        guardsProtectedAtBaseline()
        DevGuardExemptionManager.setExemptionEnabled(context, Guard.INSTALL, false)

        assertEquals(Outcome.APPLIED, DevGuardExemptionManager.setExemptionEnabled(context, Guard.INSTALL, true))
        assertTrue(DevGuardExemptionManager.isExemptionEnabled(context, Guard.INSTALL))
    }

    @Test
    fun invalidWindowIsRejectedAndNothingChanges() {
        leaveAdaptationPeriod()

        assertEquals(Outcome.INVALID, DevGuardExemptionManager.setWindow(context, DevGuardExemptionWindow(22 * 60, 6 * 60)))
        assertEquals(DevGuardExemptionWindow.DEFAULT, DevGuardExemptionManager.getWindow(context))
        assertNull(DevGuardExemptionManager.getPendingWindow(context))
    }

    @Test
    fun baselineSnapshotRecordsActiveValuesOnly() {
        leaveAdaptationPeriod()
        guardsProtectedAtBaseline()
        DevGuardExemptionManager.setExemptionEnabled(context, Guard.INSTALL, true) // 挂起，不进基准
        DevGuardExemptionManager.setWindow(context, w(20, 40, 21, 20)) // 立即

        TimeRuleManager.saveBaselineParams(context)

        assertEquals(w(20, 40, 21, 20), TimeRuleManager.getBaselineDevGuardExemptionWindow(context))
        assertFalse(TimeRuleManager.getBaselineDevGuardExemptionEnabled(context, Guard.INSTALL))
        assertFalse(TimeRuleManager.getBaselineDevGuardExemptionEnabled(context, Guard.DEVELOPER_OPTIONS))
    }

    @Test
    fun baselineDefaultsWhenNeverSnapshotted() {
        assertEquals(DevGuardExemptionWindow.DEFAULT, TimeRuleManager.getBaselineDevGuardExemptionWindow(context))
        assertFalse(TimeRuleManager.getBaselineDevGuardExemptionEnabled(context, Guard.INSTALL))
    }

    @Test
    fun savingRulesDoesNotSnapshotBaseline() {
        prefs("decikeeper_ledger").edit().putLong("first_setup_timestamp_v2", System.currentTimeMillis()).commit()
        DevGuardExemptionManager.setWindow(context, w(19, 0, 23, 0)) // 适应期内立即
        leaveAdaptationPeriod()

        TimeRuleManager.stagePendingRules(context, emptyList(), 60, 60, 60, 5, 50)
        TimeRuleManager.analyzeAndSaveGlobalParams(
            context, newRest = 60, newGap = 60, newLockDuration = 15L, newBorrow = 50,
            newNotifyFar = 5, newNotifyNear = 5, newWeeklyCount = 1, newWeeklyDuration = 480, newRefreshTime = "08:00"
        )

        assertEquals(DevGuardExemptionWindow.DEFAULT, TimeRuleManager.getBaselineDevGuardExemptionWindow(context))
    }

    @Test
    fun whenNoGuardWasOnAtBaseline_everythingIsImmediate() {
        leaveAdaptationPeriod()
        guardsProtectedAtBaseline(install = false, developerOptions = false)

        assertEquals(Outcome.APPLIED, DevGuardExemptionManager.setExemptionEnabled(context, Guard.INSTALL, true))
        assertEquals(Outcome.APPLIED, DevGuardExemptionManager.setExemptionEnabled(context, Guard.DEVELOPER_OPTIONS, true))
        assertEquals(Outcome.APPLIED, DevGuardExemptionManager.setWindow(context, w(19, 0, 23, 0)))

        assertTrue(DevGuardExemptionManager.isExemptionEnabled(context, Guard.INSTALL))
        assertEquals(w(19, 0, 23, 0), DevGuardExemptionManager.getWindow(context))
        assertNull(DevGuardExemptionManager.getPendingWindow(context))
    }

    @Test
    fun previewWindowPredictsTheOutcomeWithoutChangingAnything() {
        leaveAdaptationPeriod()
        guardsProtectedAtBaseline()

        assertEquals(Outcome.APPLIED, DevGuardExemptionManager.previewWindow(context, w(20, 40, 21, 20)))
        assertEquals(Outcome.PENDING, DevGuardExemptionManager.previewWindow(context, w(20, 0, 22, 0)))
        assertEquals(Outcome.UNCHANGED, DevGuardExemptionManager.previewWindow(context, DevGuardExemptionWindow.DEFAULT))
        assertEquals(Outcome.INVALID, DevGuardExemptionManager.previewWindow(context, DevGuardExemptionWindow(22 * 60, 6 * 60)))

        assertEquals(DevGuardExemptionWindow.DEFAULT, DevGuardExemptionManager.getWindow(context))
        assertNull(DevGuardExemptionManager.getPendingWindow(context))
    }

    @Test
    fun exemptionFlagOfAGuardThatWasOffAtBaselineIsImmediateButTheOtherWaits() {
        leaveAdaptationPeriod()
        guardsProtectedAtBaseline(install = false, developerOptions = true)

        assertEquals(Outcome.APPLIED, DevGuardExemptionManager.setExemptionEnabled(context, Guard.INSTALL, true))
        assertEquals(Outcome.PENDING, DevGuardExemptionManager.setExemptionEnabled(context, Guard.DEVELOPER_OPTIONS, true))
    }

    @Test
    fun windowWidenWaitsWhenAnyGuardWasOnAtBaseline() {
        leaveAdaptationPeriod()
        guardsProtectedAtBaseline(install = false, developerOptions = true)

        assertEquals(Outcome.PENDING, DevGuardExemptionManager.setWindow(context, w(19, 0, 23, 0)))
        assertEquals(DevGuardExemptionWindow.DEFAULT, DevGuardExemptionManager.getWindow(context))
    }

    @Test
    fun enablingAGuardTodayDoesNotLockTheExemptionBehindTomorrow() {
        leaveAdaptationPeriod()
        // 基准里拦截是关的；今天才开启拦截，豁免仍可当天自由调节（基准只在每日快照写入）。
        DeveloperOptionsGuardManager.setProtectionEnabled(context, true)
        InstallGuardManager.setProtectionEnabled(context, true)

        assertEquals(Outcome.APPLIED, DevGuardExemptionManager.setExemptionEnabled(context, Guard.INSTALL, true))
        assertEquals(Outcome.APPLIED, DevGuardExemptionManager.setWindow(context, w(20, 0, 22, 0)))
    }

    @Test
    fun afterTheDailySnapshotWithGuardsOnWideningWaitsAgain() {
        leaveAdaptationPeriod()
        DeveloperOptionsGuardManager.setProtectionEnabled(context, true)
        InstallGuardManager.setProtectionEnabled(context, true)
        DevGuardExemptionManager.setExemptionEnabled(context, Guard.INSTALL, true)

        TimeRuleManager.saveBaselineParams(context) // 次日刷新时刻的每日快照：拦截与豁免进入基准

        assertEquals(Outcome.PENDING, DevGuardExemptionManager.setWindow(context, w(20, 0, 22, 0)))
        assertEquals(Outcome.APPLIED, DevGuardExemptionManager.setExemptionEnabled(context, Guard.INSTALL, false))
        assertEquals(Outcome.APPLIED, DevGuardExemptionManager.setExemptionEnabled(context, Guard.INSTALL, true)) // 回到基准
        assertEquals(Outcome.PENDING, DevGuardExemptionManager.setExemptionEnabled(context, Guard.DEVELOPER_OPTIONS, true))
    }
}
