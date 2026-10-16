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

/** 应急退出持久化：默认 1 次/月、扣减、调参立即/待生效。备份导入合并见 SettingsBackupAlarmCoverageTest。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AlarmEmergencyExitStoreTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences(AlarmEmergencyExitStore.PREFS_NAME, Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences(CreditManager.PREFS_NAME, Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun defaultsToOnePerMonthAndConsumeExhaustsIt() {
        val now = System.currentTimeMillis()
        assertEquals(1, AlarmEmergencyExitStore.remaining(context, now))
        assertTrue(AlarmEmergencyExitStore.consumeOne(context, now))
        assertEquals(0, AlarmEmergencyExitStore.remaining(context, now))
        assertFalse(AlarmEmergencyExitStore.consumeOne(context, now))
    }

    @Test
    fun usedCountSurvivesReload() {
        val now = System.currentTimeMillis()
        AlarmEmergencyExitStore.consumeOne(context, now)
        assertEquals(1, AlarmEmergencyExitStore.load(context, now).usedThisMonth)
    }

    @Test
    fun loweringToZeroTakesEffectImmediately() {
        val now = System.currentTimeMillis()
        AlarmEmergencyExitStore.requestMonthlyLimit(context, 0, now)
        assertEquals(0, AlarmEmergencyExitStore.remaining(context, now))
        assertFalse(AlarmEmergencyExitStore.consumeOne(context, now))
    }

    @Test
    fun raisingBeyondBaselineIsPendingUntilNextMonthRefreshTime() {
        val now = System.currentTimeMillis()
        // 适应期内放宽会立即生效；这里直接以非适应期口径验证 store 提供的下月生效时间
        val s = AlarmEmergencyExitPolicy.requestChange(
            AlarmEmergencyExitStore.load(context, now), 3, now, false,
            AlarmEmergencyExitStore.nextMonthEffectiveTime(context, now)
        )
        assertEquals(1, s.active)
        assertEquals(3, s.pendingValue)
        assertTrue(s.pendingEffectiveAt > now)
    }
}
