package com.decikeeper.app

import android.content.Context
import com.decikeeper.app.manager.AccessibilityControlManager
import com.decikeeper.app.manager.AntiUninstallManager
import com.decikeeper.app.manager.StartupProtectionManager
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class AntiUninstallStartupProtectionContractTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("accessibility_control_prefs", Context.MODE_PRIVATE)
            .edit().clear().commit()
        context.getSharedPreferences("anti_uninstall_prefs", Context.MODE_PRIVATE)
            .edit().clear().commit()
        context.getSharedPreferences("pending_config_prefs", Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @Test
    fun accessibilityServiceAloneDoesNotRestorePermissionProtection() {
        assertEquals(
            AccessibilityControlManager.State.DISABLED,
            AccessibilityControlManager.getState(context)
        )
    }

    @Test
    fun coreServiceMustNotTurnOnAntiUninstallWhenAccessibilityConnects() {
        val source = source("app/src/main/java/com/decikeeper/app/service/CoreService.kt")
        assertFalse(
            "无障碍连接不能无条件打开用户关闭的防卸载保护",
            source.contains("AntiUninstallManager.setProtectionEnabled(this, true)")
        )
    }

    @Test
    fun combinedSwitchRequiresBothProtectionManagers() {
        val source = source("app/src/main/java/com/decikeeper/app/ui/RulesFragment.kt")
        val combinedState = source.substringAfter("private fun getCombinedProtectionState")
            .substringBefore("private fun updateUninstallAndPermProtectionUI")
        assertTrue(combinedState.contains("antiState == AntiUninstallManager.State.ACTIVE &&"))
        assertTrue(combinedState.contains("permissionState == AccessibilityControlManager.State.ACTIVE"))
        assertFalse(combinedState.contains("antiState == AntiUninstallManager.State.ACTIVE ||"))
    }

    @Test
    fun startupProtectionUsesTenSecondWarmupWindow() {
        assertEquals(10_000L, StartupProtectionManager.FAST_LOCK_MAX_VISIBLE_MS)
    }

    @Test
    fun disabledProtectionStopsFastLockBeforeWarmupRetry() {
        val source = source("app/src/main/java/com/decikeeper/app/service/FastLockService.kt")
        val handoff = source.substringAfter("private fun closeAfterMinimumVisibleWindow")
        val protectionGate = handoff.indexOf("StartupProtectionManager.shouldKeepInitialization")
        val warmupGate = handoff.indexOf("!CoreService.antiUninstallWarmedUp")
        assertTrue(protectionGate >= 0)
        assertTrue(warmupGate >= 0)
        assertTrue("防卸载关闭时必须先退出，不能进入预热重试", protectionGate < warmupGate)
    }

    @Test
    fun accessibilityLossStopsStartupProtectionWithoutWaitingForTimeout() {
        val source = source("app/src/main/java/com/decikeeper/app/service/CoreService.kt")
        val onUnbind = source.substringAfter("override fun onUnbind")
        assertTrue(onUnbind.contains("StartupProtectionManager.stopFastLock(this)"))
        assertTrue(onUnbind.contains("LockWindowManager.dismissSystemInitOverlay"))
    }

    @Test
    fun typewriterLayoutsUseTheActualUiPackage() {
        val gentleWarning = source("app/src/main/res/layout/dialog_gentle_warning.xml")
        val lightsOut = source("app/src/main/res/layout/dialog_lights_out.xml")
        assertTrue(gentleWarning.contains("com.decikeeper.app.ui.TypewriterTextView"))
        assertTrue(lightsOut.contains("com.decikeeper.app.ui.TypewriterTextView"))
        assertFalse(gentleWarning.contains("com.decikeeper.app.TypewriterTextView"))
        assertFalse(lightsOut.contains("com.decikeeper.app.TypewriterTextView"))
    }

    private fun source(path: String): String {
        val cwd = File(System.getProperty("user.dir") ?: ".")
        val candidates = listOf(
            File(cwd, path),
            File(cwd, path.removePrefix("app/")),
            File(cwd.parentFile ?: cwd, path),
            File(cwd.parentFile ?: cwd, path.removePrefix("app/"))
        )
        return candidates.firstOrNull { it.isFile }?.readText()
            ?: error("Cannot locate $path from ${cwd.absolutePath}")
    }
}
