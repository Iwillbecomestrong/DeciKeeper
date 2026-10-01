package com.decikeeper.app.manager

import android.content.Context
import android.content.Intent
import android.os.Looper
import android.os.UserManager
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger

/** 多通道触发：先到先得，只 attach 一次；解锁广播能为 Direct Boot 出生的进程补挂。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class FastLockStartupAccelerationTest {

    private val app get() = RuntimeEnvironment.getApplication()
    private val userManager get() = app.getSystemService(UserManager::class.java)

    @Before
    fun setUp() {
        StartupProtectionManager.resetBootFastLockStateForTest()
        StartupProtectionManager.overlayViewFactory = { android.view.View(it) }
        BootUnlockTrigger.resetForTest(app)
        Shadows.shadowOf(userManager).setUserUnlocked(true)
        StartupA11yCache.setA11yWasEnabled(app, true)
        setAntiUninstall(true)
    }

    @After
    fun tearDown() {
        Shadows.shadowOf(userManager).setUserUnlocked(true)
        StartupProtectionManager.dismissEarlyBootOverlay(app, "test_teardown")
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        StartupProtectionManager.resetBootFastLockStateForTest()
        BootUnlockTrigger.resetForTest(app)
        setAntiUninstall(false)
    }

    private fun setAntiUninstall(enabled: Boolean) {
        app.getSharedPreferences("anti_uninstall_prefs", Context.MODE_PRIVATE)
            .edit().putBoolean("is_protection_enabled", enabled).commit()
    }

    @Test
    fun `multiple channels attach only once`() {
        assertTrue(StartupProtectionManager.triggerBootFastLock(app, "app_on_create"))
        val first = StartupProtectionManager.earlyBootOverlayView
        assertNotNull(first)
        assertFalse(StartupProtectionManager.triggerBootFastLock(app, "unlock:USER_UNLOCKED"))
        assertFalse(StartupProtectionManager.triggerBootFastLock(app, "a11y_connected"))
        assertFalse(StartupProtectionManager.triggerBootFastLock(app, "boot_receiver:BOOT_COMPLETED"))
        assertTrue(first === StartupProtectionManager.earlyBootOverlayView)
    }

    @Test
    fun `concurrent claim lets exactly one thread win`() {
        val wins = AtomicInteger(0)
        val start = CountDownLatch(1)
        val done = CountDownLatch(16)
        repeat(16) {
            Thread {
                start.await()
                if (StartupProtectionManager.tryClaimBootFastLock()) wins.incrementAndGet()
                done.countDown()
            }.start()
        }
        start.countDown()
        done.await()
        assertEquals(1, wins.get())
    }

    @Test
    fun `anti-uninstall disabled closes the gate for every channel`() {
        setAntiUninstall(false)
        assertFalse(StartupProtectionManager.triggerBootFastLock(app, "app_on_create"))
        assertNull(StartupProtectionManager.earlyBootOverlayView)
        assertFalse(StartupProtectionManager.triggerBootFastLock(app, "unlock:USER_UNLOCKED"))
        assertNull(StartupProtectionManager.earlyBootOverlayView)
    }

    @Test
    fun `a11y cache false skips without claiming`() {
        StartupA11yCache.setA11yWasEnabled(app, false)
        assertFalse(StartupProtectionManager.triggerBootFastLock(app, "app_on_create"))
        assertFalse(StartupProtectionManager.earlyBootFastLockStarted)
    }

    @Test
    fun `USER_UNLOCKED broadcast attaches overlay for a process born locked`() {
        Shadows.shadowOf(userManager).setUserUnlocked(false)
        BootUnlockTrigger.armIfLocked(app)
        assertTrue(StartupProtectionManager.isUnlockTriggerArmed())
        assertNull(StartupProtectionManager.earlyBootOverlayView)

        Shadows.shadowOf(userManager).setUserUnlocked(true)
        app.sendBroadcast(Intent(Intent.ACTION_USER_UNLOCKED))
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        assertNotNull(StartupProtectionManager.earlyBootOverlayView)
        assertTrue(StartupProtectionManager.earlyBootFastLockStarted)
    }
}
