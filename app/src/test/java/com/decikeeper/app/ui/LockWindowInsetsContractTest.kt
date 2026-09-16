package com.decikeeper.app.ui

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class LockWindowInsetsContractTest {

    private fun source(path: String): String {
        val cwd = File(System.getProperty("user.dir"))
        val candidates = listOf(
            File(cwd, path),
            File(cwd, "app/$path"),
            File(cwd.parentFile ?: cwd, path),
            File(cwd.parentFile ?: cwd, "app/$path")
        )
        return candidates.firstOrNull { it.isFile }?.readText()
            ?: error("Cannot locate $path from ${cwd.absolutePath}")
    }

    @Test
    fun overlaySafeInsets_implementsBarsAndCutoutIntegration() {
        val insetsHelper = source("src/main/java/com/decikeeper/app/ui/OverlaySafeInsets.kt")
        assertTrue("Must query systemBars", insetsHelper.contains("WindowInsetsCompat.Type.systemBars()"))
        assertTrue("Must query displayCutout", insetsHelper.contains("WindowInsetsCompat.Type.displayCutout()"))
        assertTrue("Must provide resolveOverlaySafeInsets", insetsHelper.contains("fun resolveOverlaySafeInsets("))
        assertTrue("Must provide installOverlaySafeInsets", insetsHelper.contains("fun installOverlaySafeInsets("))
    }

    @Test
    fun lockWindowManager_wiresOverlaySafeInsets() {
        val lockWm = source("src/main/java/com/decikeeper/app/ui/LockWindowManager.kt")
        assertTrue(
            "LockWindowManager must install overlay safe insets",
            lockWm.contains("installOverlaySafeInsets")
        )
        assertTrue(
            "LockWindowManager must wire layout_background_text",
            lockWm.contains("R.id.layout_background_text")
        )
        assertTrue(
            "LockWindowManager must wire layout_bottom_buttons",
            lockWm.contains("R.id.layout_bottom_buttons")
        )
    }

    @Test
    fun lockAlarmOverlay_wiresOverlaySafeInsets() {
        val alarmOverlay = source("src/main/java/com/decikeeper/app/ui/LockAlarmOverlay.kt")
        assertTrue(
            "LockAlarmOverlay must install overlay safe insets",
            alarmOverlay.contains("installOverlaySafeInsets")
        )
        assertTrue(
            "LockAlarmOverlay must wire root_content_layout",
            alarmOverlay.contains("R.id.root_content_layout")
        )
    }

    @Test
    fun overlayLayouts_containRequiredStructuralIds() {
        val lockLayout = source("src/main/res/layout/window_lock_screen.xml")
        assertTrue("window_lock_screen must contain root_lock_screen", lockLayout.contains("@+id/root_lock_screen"))
        assertTrue("window_lock_screen must contain layout_background_text", lockLayout.contains("@+id/layout_background_text"))
        assertTrue("window_lock_screen must contain layout_bottom_buttons", lockLayout.contains("@+id/layout_bottom_buttons"))

        val alarmLayout = source("src/main/res/layout/window_alarm_overlay.xml")
        assertTrue("window_alarm_overlay must contain root_container", alarmLayout.contains("@+id/root_container"))
        assertTrue("window_alarm_overlay must contain root_content_layout", alarmLayout.contains("@+id/root_content_layout"))
    }
}
