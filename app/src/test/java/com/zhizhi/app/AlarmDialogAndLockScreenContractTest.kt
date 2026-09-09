package com.zhizhi.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AlarmDialogAndLockScreenContractTest {

    private fun readLayout(name: String): String {
        val cwd = File(System.getProperty("user.dir") ?: ".")
        val candidates = listOf(
            File(cwd, "app/src/main/res/layout/$name"),
            File(cwd, "src/main/res/layout/$name"),
            File(cwd.parentFile ?: cwd, "app/src/main/res/layout/$name")
        )
        val file = candidates.firstOrNull { it.isFile }
            ?: error("Cannot locate layout $name from ${cwd.absolutePath}")
        return file.readText()
    }

    private fun readSource(path: String): String {
        val cwd = File(System.getProperty("user.dir") ?: ".")
        val candidates = listOf(
            File(cwd, path),
            File(cwd, "app/$path"),
            File(cwd.parentFile ?: cwd, path),
            File(cwd.parentFile ?: cwd, "app/$path")
        )
        val file = candidates.firstOrNull { it.isFile }
            ?: error("Cannot locate source $path from ${cwd.absolutePath}")
        return file.readText()
    }

    @Test
    fun alarmEditDialogContainsSnoozeControls() {
        val xml = readLayout("dialog_alarm_edit.xml")
        assertTrue("dialog_alarm_edit must include et_snooze_minutes", xml.contains("@+id/et_snooze_minutes"))
        assertTrue("dialog_alarm_edit must include et_snooze_max_count", xml.contains("@+id/et_snooze_max_count"))
        assertTrue("dialog_alarm_edit must include snooze title", xml.contains("稍后提醒（小睡）"))
    }

    @Test
    fun alarmDialogsBothContainCooldownStageControls() {
        val editXml = readLayout("dialog_alarm_edit.xml")
        val testXml = readLayout("dialog_test_alarm.xml")
        assertTrue("edit dialog must contain stage cooldown", editXml.contains("@+id/et_stage_cooldown"))
        assertTrue("test dialog must contain stage cooldown", testXml.contains("@+id/et_stage_cooldown"))
    }

    @Test
    fun lockScreenButtonDisablesWithoutAlphaBleedThrough() {
        val lockManagerSrc = readSource("src/main/java/com/zhizhi/app/ui/LockWindowManager.kt")
        assertTrue(
            "Lock screen must hide underlying btnTemporaryAccess when btnEmergency is displayed",
            lockManagerSrc.contains("btnTemporaryAccess?.visibility = View.GONE")
        )
        assertTrue(
            "Disabled emergency button must use solid surface color to prevent bleeding",
            lockManagerSrc.contains("R.attr.zzSurface2")
        )
    }
}
