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
        setSystemA11y(true)
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

    private fun setSystemA11y(enabled: Boolean) {
        android.provider.Settings.Secure.putString(
            app.contentResolver,
            android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            if (enabled) "${app.packageName}/com.decikeeper.app.service.CoreService" else ""
        )
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
    @Test
    fun `locked process pre-attaches under keyguard and unlock activates the same view`() {
        StartupAntiUninstallCache.sync(app, true)
        Shadows.shadowOf(userManager).setUserUnlocked(false)
        BootUnlockTrigger.armIfLocked(app)

        val pre = StartupProtectionManager.earlyBootOverlayView
        assertNotNull(pre)
        assertFalse(StartupProtectionManager.earlyBootFastLockStarted)
        assertEquals(0L, StartupProtectionManager.earlyBootOverlayAttachedAtMs)

        Shadows.shadowOf(userManager).setUserUnlocked(true)
        app.sendBroadcast(Intent(Intent.ACTION_USER_UNLOCKED))
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        assertTrue(pre === StartupProtectionManager.earlyBootOverlayView)
        assertTrue(StartupProtectionManager.earlyBootFastLockStarted)
        assertTrue(StartupProtectionManager.earlyBootOverlayAttachedAtMs > 0L)
    }

    @Test
    fun `no pre-attach without anti-uninstall mirror`() {
        StartupAntiUninstallCache.sync(app, false)
        Shadows.shadowOf(userManager).setUserUnlocked(false)
        BootUnlockTrigger.armIfLocked(app)
        assertNull(StartupProtectionManager.earlyBootOverlayView)
    }

    @Test
    fun `pre-attached overlay is discarded when CE gate says anti-uninstall is off`() {
        StartupAntiUninstallCache.sync(app, true)
        Shadows.shadowOf(userManager).setUserUnlocked(false)
        BootUnlockTrigger.armIfLocked(app)
        assertNotNull(StartupProtectionManager.earlyBootOverlayView)

        setAntiUninstall(false)
        Shadows.shadowOf(userManager).setUserUnlocked(true)
        app.sendBroadcast(Intent(Intent.ACTION_USER_UNLOCKED))
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        assertNull(StartupProtectionManager.earlyBootOverlayView)
    }

    @Test
    fun `system a11y off with stale cache dismisses overlay and corrects cache`() {
        setSystemA11y(false)
        StartupProtectionManager.triggerBootFastLock(app, "app_on_create")
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        assertNull(StartupProtectionManager.earlyBootOverlayView)
        assertFalse(StartupA11yCache.isA11yWasEnabledForStartup(app))
    }

    @Test
    fun `a11y turned off while overlay is showing dismisses it on the next tick`() {
        assertTrue(StartupProtectionManager.triggerBootFastLock(app, "app_on_create"))
        assertNotNull(StartupProtectionManager.earlyBootOverlayView)

        setSystemA11y(false)
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(1))

        assertNull(StartupProtectionManager.earlyBootOverlayView)
    }
}
