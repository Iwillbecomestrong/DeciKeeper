package com.decikeeper.app.service

import android.content.Intent
import android.os.UserManager
import com.decikeeper.app.manager.StartupA11yCache
import com.decikeeper.app.manager.StartupProtectionManager
import com.decikeeper.app.receiver.BootReceiver
import com.decikeeper.app.testutil.CeLockedContext
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config

/** Direct Boot 红线：未解锁时 FastLock 整条触发链不得访问 CE，也不得拉起前台服务。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class FastLockDirectBootSafetyTest {

    private val app get() = RuntimeEnvironment.getApplication()
    private val userManager get() = app.getSystemService(UserManager::class.java)

    @Before
    fun setUp() {
        StartupProtectionManager.resetBootFastLockStateForTest()
        StartupA11yCache.setA11yWasEnabled(app, true)
        Shadows.shadowOf(userManager).setUserUnlocked(false)
    }

    @After
    fun tearDown() {
        Shadows.shadowOf(userManager).setUserUnlocked(true)
        StartupProtectionManager.resetBootFastLockStateForTest()
    }

    @Test
    fun `shouldKeepInitialization is false and CE free while locked`() {
        val ctx = CeLockedContext(app)
        assertFalse(StartupProtectionManager.shouldKeepInitialization(ctx))
        assertTrue("CE accessed: ${ctx.ceAccess}", ctx.ceAccess.isEmpty())
    }

    @Test
    fun `attach and trigger are no-ops while locked`() {
        val ctx = CeLockedContext(app)
        assertFalse(StartupProtectionManager.attachEarlyBootFastLock(ctx))
        assertFalse(StartupProtectionManager.triggerBootFastLock(ctx, "test_locked"))
        assertNull(StartupProtectionManager.earlyBootOverlayView)
        assertFalse(StartupProtectionManager.earlyBootFastLockStarted)
        assertTrue(ctx.startedForegroundServices.isEmpty())
        assertTrue("CE accessed: ${ctx.ceAccess}", ctx.ceAccess.isEmpty())
    }

    @Test
    fun `LOCKED_BOOT_COMPLETED does not trigger FastLock nor touch theme or anti-uninstall prefs`() {
        val ctx = CeLockedContext(app)
        try {
            BootReceiver().onReceive(ctx, Intent(Intent.ACTION_LOCKED_BOOT_COMPLETED))
        } catch (_: Throwable) {
            // 其余 Direct Boot 逻辑（闹钟）不在本用例范围，只关心 FastLock 链路的 CE 访问。
        }
        assertFalse(StartupProtectionManager.earlyBootFastLockStarted)
        assertTrue(ctx.startedForegroundServices.none { it.component?.className?.endsWith("FastLockService") == true })
        assertFalse(ctx.ceAccess.contains("decikeeper_ui_theme"))
        assertFalse(ctx.ceAccess.contains("anti_uninstall_prefs"))
    }

    @Test
    fun `FastLockService created while locked stops itself without crashing`() {
        val controller = org.robolectric.Robolectric.buildService(FastLockService::class.java)
        controller.create()
        assertFalse(FastLockService.isRunning)
        assertTrue(Shadows.shadowOf(controller.get()).isStoppedBySelf)
    }
}
