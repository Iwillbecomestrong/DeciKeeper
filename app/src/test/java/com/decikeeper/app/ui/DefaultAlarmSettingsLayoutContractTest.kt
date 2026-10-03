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
            File(cwd, "src/main/java/com/decikeeper/app/ui/AlarmReminderSettingsView.kt"),
            File(cwd, "app/src/main/java/com/decikeeper/app/ui/AlarmReminderSettingsView.kt"),
            File(cwd.parentFile ?: cwd, "app/src/main/java/com/decikeeper/app/ui/AlarmReminderSettingsView.kt")
        )
        val source = candidates.firstOrNull { it.isFile }?.readText()
            ?: error("Cannot locate AlarmReminderSettingsView.kt from ${cwd.absolutePath}")

        val start = source.indexOf("private fun refreshVisibility()")
        val end = source.indexOf("private fun buildRingtoneRow", start)
        assertTrue("visibility updater must exist", start >= 0)
        assertTrue("visibility updater must have a bounded body", end > start)

        val body = source.substring(start, end)
        assertTrue("hidden reminder controls must preserve their layout slots", body.contains("INVISIBLE"))
        assertFalse("hidden reminder controls must not collapse their layout slots", body.contains("GONE"))
    }
}
