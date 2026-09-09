package com.zhizhi.app
 
import com.zhizhi.app.ui.LockWindowManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
 
class AlarmTestSnoozeContractTest {
 
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
    fun testAppWindowUndoButtonRemoved() {
        val appWindowXml = source("src/main/res/layout/fragment_app_window.xml")
        val appWindowFragment = source("src/main/java/com/zhizhi/app/ui/AppWindowFragment.kt")
 
        assertFalse("fragment_app_window.xml must not contain btn_undo", appWindowXml.contains("android:id=\"@+id/btn_undo\""))
        assertFalse("fragment_app_window.xml must not contain 撤销待生效变更", appWindowXml.contains("撤销待生效变更"))
        assertFalse("AppWindowFragment must not bind btn_undo", appWindowFragment.contains("R.id.btn_undo"))
        assertFalse("AppWindowFragment must not declare btnUndo property", appWindowFragment.contains("btnUndo"))
    }
 
    @Test
    fun testAlarmConfigHasSnoozeProperties() {
        val config = LockWindowManager.TestAlarmConfig(
            snoozeMinutes = 15,
            snoozeMaxCount = 3
        )
        assertEquals(15, config.snoozeMinutes)
        assertEquals(3, config.snoozeMaxCount)
    }
 
    @Test
    fun testLockAlarmOverlaySupportsTestSnooze() {
        val overlaySrc = source("src/main/java/com/zhizhi/app/ui/LockAlarmOverlay.kt")
        assertTrue("LockAlarmOverlay must read testCfg snoozeMinutes", overlaySrc.contains("testCfg?.snoozeMinutes"))
        assertTrue("LockAlarmOverlay must read testCfg snoozeMaxCount", overlaySrc.contains("testCfg?.snoozeMaxCount"))
        assertTrue("requestSnooze must check isTest instead of hard-rejecting alarmId <= 0", overlaySrc.contains("val isTest = alarmId <= 0"))
    }
 
    @Test
    fun testAlarmVibrationServiceAllowsTestAlarmSilence() {
        val vibrationSrc = source("src/main/java/com/zhizhi/app/service/AlarmVibrationService.kt")
        assertTrue("silenceForSnooze must allow alarmId == 0 (TEST_ALARM_ID)", vibrationSrc.contains("if (alarmId < 0 || durationMs <= 0L) return"))
        assertTrue("applySnoozeSilence must allow targetAlarmId == 0", vibrationSrc.contains("if (targetAlarmId < 0 || targetAlarmId != alarmId || durationMs <= 0L) return"))
    }
}
