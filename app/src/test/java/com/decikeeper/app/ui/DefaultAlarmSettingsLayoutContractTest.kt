package com.decikeeper.app.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class DefaultAlarmSettingsLayoutContractTest {
    @Test
    fun ringtoneSettingsKeepTheirLayoutSlotsWhenOptionsChange() {
        val cwd = File(System.getProperty("user.dir"))
        val candidates = listOf(
            File(cwd, "src/main/java/com/decikeeper/app/ui/AlarmFragment.kt"),
            File(cwd, "app/src/main/java/com/decikeeper/app/ui/AlarmFragment.kt"),
            File(cwd.parentFile ?: cwd, "app/src/main/java/com/decikeeper/app/ui/AlarmFragment.kt")
        )
        val source = candidates.firstOrNull { it.isFile }?.readText()
            ?: error("Cannot locate AlarmFragment.kt from ${cwd.absolutePath}")

        val start = source.indexOf("fun updateTestRingtoneVisibility()")
        val end = source.indexOf("testRgRingMode.setOnCheckedChangeListener", start)
        assertTrue("ringtone visibility updater must exist", start >= 0)
        assertTrue("ringtone visibility updater must have a bounded body", end > start)

        val body = source.substring(start, end)
        assertTrue("hidden ringtone controls must preserve their layout slots", body.contains("View.INVISIBLE"))
        assertFalse("hidden ringtone controls must not collapse their layout slots", body.contains("View.GONE"))
    }
}
