package com.decikeeper.app.manager

import android.app.Application
import android.content.Context
import com.decikeeper.app.data.TemporaryAccessQuotaPlan
import com.decikeeper.app.data.calculateTemporaryAccessQuotaPlan
import com.decikeeper.app.ui.LockWindowManager
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class TemporaryAccessCooldownRuntimeTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val ledger get() = context.getSharedPreferences(CreditManager.PREFS_NAME, Context.MODE_PRIVATE)
    private val lockState get() = context.getSharedPreferences("decikeeper_lock_window_state", Context.MODE_PRIVATE)

    @Test
    fun everyGlobalCooldownBlocksUntilItsExactDeadline() {
        val now = 10_000L
        ledger.edit().putLong("cool_down_end_timestamp", now + 1L).commit()
        assertEquals(1L, LockWindowManager.getTemporaryAccessCooldownRemainingSeconds(context, now))
        assertEquals(0L, LockWindowManager.getTemporaryAccessCooldownRemainingSeconds(context, now + 1L))
    }

    @Test
    fun scheduledRestCooldownIsNotActiveButPersistedTemporaryCooldownStillBlocksDuringRest() {
        val now = 10_000L
        ledger.edit().putLong("free_time_end", now + 60_000L)
            .putLong("cool_down_end_timestamp", now + 180_000L).commit()
        assertEquals(0L, LockWindowManager.getTemporaryAccessCooldownRemainingSeconds(context, now))
        lockState.edit().putLong("temp_access_until", now - 1L)
            .putLong("temp_reuse_cooldown_until", now + 30_000L).commit()
        assertEquals(30L, LockWindowManager.getTemporaryAccessCooldownRemainingSeconds(context, now))
        assertEquals(120L, LockWindowManager.getTemporaryAccessCooldownRemainingSeconds(context, now + 60_000L))
    }

    @Test
    fun staleConfirmationCannotGrantAccessOrDeductPreconfirmedQuota() {
        val now = System.currentTimeMillis()
        val plan = calculateTemporaryAccessQuotaPlan(60, 60, 0, 1, 1, 0)
        ledger.edit().putLong("cool_down_end_timestamp", now + 120_000L).commit()
        val ledgerBefore = ledger.all
        val lockBefore = lockState.all
        val start = LockWindowManager::class.java.getDeclaredMethod(
            "startTemporaryAccess", Context::class.java, Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType,
            Boolean::class.javaPrimitiveType, TemporaryAccessQuotaPlan::class.java
        ).apply { isAccessible = true }
        for (seconds in listOf(10, 60, 180)) {
            start.invoke(LockWindowManager, context, seconds, 180, true, true, plan)
        }
        assertEquals(lockBefore, lockState.all)
        assertEquals(ledgerBefore, ledger.all.filterKeys { !it.startsWith("exit_cooldown_attempt_count_") })
        assertEquals(0L, LockWindowManager.getTemporaryAccessSnapshot(context).accessRemainingSeconds)
    }

    @Test
    fun persistedCooldownRejectsFreeTenSecondAccessEvenWhileResting() {
        val now = System.currentTimeMillis()
        ledger.edit().putLong("free_time_end", now + 60_000L).commit()
        lockState.edit().putLong("temp_access_until", now - 1L)
            .putLong("temp_reuse_cooldown_until", now + 30_000L)
            .putBoolean("temp_log_emergency", true)
            .putLong("temp_emergency_last_use_at", 0L)
            .putInt("temp_emergency_remaining_uses", 99).commit()
        val start = LockWindowManager::class.java.getDeclaredMethod(
            "startTemporaryAccess", Context::class.java, Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType,
            Boolean::class.javaPrimitiveType, TemporaryAccessQuotaPlan::class.java
        ).apply { isAccessible = true }
        val before = lockState.all
        start.invoke(LockWindowManager, context, 10, 180, false, false, null)
        assertEquals(before, lockState.all)
        assertEquals(0L, LockWindowManager.getTemporaryAccessSnapshot(context).accessRemainingSeconds)
    }
}
