package com.decikeeper.app

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AlarmScreenOffBehaviorContractTest {

    @Test
    fun alarmWindowManagerDoesNotWakeScreenOnScreenOff() {
        val wmSource = source("app/src/main/java/com/decikeeper/app/manager/AlarmWindowManager.kt")

        assertFalse(
            "AlarmWindowManager 绝不能在收到 ACTION_SCREEN_OFF 时调度 WakeLock 或唤醒屏幕",
            wmSource.contains("SCREEN_OFF_RECOVERY scheduled")
        )
        assertTrue(
            "AlarmWindowManager 必须在 ACTION_SCREEN_ON 或 ACTION_USER_PRESENT 时恢复覆盖层置顶",
            wmSource.contains("Intent.ACTION_SCREEN_ON") && wmSource.contains("showAlarmOverlayIfHidden()")
        )
    }

    @Test
    fun alarmActivityPausesAndResumesScannerOnPauseAndResume() {
        val activitySource = source("app/src/main/java/com/decikeeper/app/ui/AlarmActivity.kt")

        assertTrue(
            "AlarmActivity onPause 必须在扫码模式下调用 barcodeView?.pause()",
            activitySource.contains("override fun onPause()") &&
                activitySource.contains("if (isScanMode)") &&
                activitySource.contains("barcodeView?.pause()")
        )
        assertTrue(
            "AlarmActivity onResume 必须在扫码模式下调用 barcodeView?.resume()",
            activitySource.contains("override fun onResume()") &&
                activitySource.contains("barcodeView?.resume()")
        )
    }

    private fun source(path: String): String {
        val cwd = File(System.getProperty("user.dir") ?: ".")
        val normalized = path.removePrefix("app/")
        val candidates = listOf(
            File(cwd, path),
            File(cwd, normalized),
            File(cwd, "app/$normalized"),
            File(cwd.parentFile ?: cwd, path),
            File(cwd.parentFile ?: cwd, normalized),
            File(cwd.parentFile ?: cwd, "app/$normalized")
        )
        for (candidate in candidates) {
            if (candidate.isFile) return candidate.readText()
        }
        throw AssertionError("Could not find $path from ${cwd.absolutePath}")
    }
}
