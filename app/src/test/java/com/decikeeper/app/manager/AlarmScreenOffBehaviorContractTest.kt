package com.decikeeper.app.manager

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
    fun alarmActivityClosesCutoutAndHardRestartsCameraAfterPauseResume() {
        val activitySource = source("app/src/main/java/com/decikeeper/app/ui/AlarmActivity.kt")

        assertTrue(
            "AlarmActivity onPause 必须在扫码模式下关闭透明孔并暂停相机分析",
            activitySource.contains("override fun onPause()") &&
                activitySource.contains("scanNeedsCameraRestartOnResume = true") &&
                activitySource.contains("LockWindowManager.setAlarmOverlayPassthrough(false)") &&
                activitySource.contains("barcodeView?.pause()")
        )
        assertTrue(
            "AlarmActivity onResume 必须在发生过 pause 后调用 CameraX 硬重绑，而不是只恢复分析标志",
            activitySource.contains("override fun onResume()") &&
                activitySource.contains("if (scanNeedsCameraRestartOnResume)") &&
                activitySource.contains("barcodeView?.restart()")
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
