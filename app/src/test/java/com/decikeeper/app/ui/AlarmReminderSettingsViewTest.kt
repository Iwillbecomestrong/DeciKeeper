package com.decikeeper.app.ui

import com.decikeeper.app.manager.AlarmScheduler.AlarmMode
import com.decikeeper.app.manager.AlarmScheduler.VibrationMode
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AlarmReminderSettingsViewTest {

    private fun visibility(mode: AlarmMode, vib: VibrationMode, showRingtone: Boolean = true) =
        AlarmReminderSettingsView.resolveVisibility(mode, vib, showRingtone)

    @Test
    fun `progressive mode shows only the hint and no sliders`() {
        val v = visibility(AlarmMode.VIBRATE_AND_RING, VibrationMode.PROGRESSIVE)

        assertTrue(v.progressiveHint)
        assertFalse(v.vibrationSlider)
        assertFalse(v.volumeSlider)
    }

    @Test
    fun `fixed vibrate and ring shows both sliders`() {
        val v = visibility(AlarmMode.VIBRATE_AND_RING, VibrationMode.FIXED)

        assertFalse(v.progressiveHint)
        assertTrue(v.vibrationSlider)
        assertTrue(v.volumeSlider)
    }

    @Test
    fun `fixed vibrate only hides the volume slider and the ringtone row`() {
        val v = visibility(AlarmMode.VIBRATE_ONLY, VibrationMode.FIXED)

        assertTrue(v.vibrationSlider)
        assertFalse(v.volumeSlider)
        assertFalse(v.ringtone)
    }

    @Test
    fun `fixed ring only hides the vibration slider and keeps the ringtone row`() {
        val v = visibility(AlarmMode.RING_ONLY, VibrationMode.FIXED)

        assertFalse(v.vibrationSlider)
        assertTrue(v.volumeSlider)
        assertTrue(v.ringtone)
    }

    @Test
    fun `second stage card never shows a ringtone row`() {
        assertFalse(visibility(AlarmMode.VIBRATE_AND_RING, VibrationMode.FIXED, showRingtone = false).ringtone)
    }

    @Test
    fun `reminder view is themed instead of using stock radio buttons and default buttons`() {
        val src = source("app/src/main/java/com/decikeeper/app/ui/AlarmReminderSettingsView.kt")

        assertFalse("stock RadioButton looks out of place", src.contains("RadioButton("))
        assertFalse("stock Button looks out of place", Regex("""\bButton\(""").containsMatchIn(src))
        assertTrue(src.contains("bg_zz_option_btn_selected"))
        assertTrue(src.contains("R.attr.zzAccent"))
        assertEquals(1, Regex("""fun getConfig\(""").findAll(src).count())
    }

    private fun source(path: String): String {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            val f = File(dir, path)
            if (f.exists()) return f.readText()
            dir = dir.parentFile
        }
        error("source not found: $path")
    }
}
