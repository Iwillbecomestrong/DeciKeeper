package com.decikeeper.app

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class AlarmHardwareSnoozeContractTest {
    private fun source(path: String): String {
        val cwd = File(System.getProperty("user.dir"))
        return listOf(File(cwd, path), File(cwd, "app/$path"))
            .firstOrNull { it.isFile }
            ?.readText()
            ?: error("Source file not found: $path")
    }

    @Test
    fun overlayAcceptsEitherVolumeKeyForSnooze() {
        val overlay = source("src/main/java/com/decikeeper/app/ui/LockAlarmOverlay.kt")

        assertTrue(overlay.contains("setOnKeyListener"))
        assertTrue(overlay.contains("KEYCODE_VOLUME_UP"))
        assertTrue(overlay.contains("KEYCODE_VOLUME_DOWN"))
        assertTrue(overlay.contains("requestSnoozeFromHardware(") && overlay.contains("\"VOLUME_KEY\""))
    }

    @Test
    fun powerKeyUsesScreenOffSnoozePath() {
        val service = source("src/main/java/com/decikeeper/app/service/AlarmVibrationService.kt")

        assertTrue(service.contains("Intent.ACTION_SCREEN_OFF"))
        assertTrue(service.contains("requestSnoozeFromHardware(\"POWER_KEY\")"))
    }
}
