package com.ironwill.app

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test


class AlarmProductionMigrationTest {
    @Test
    fun remainingAlarmAndProtectionProductionSitesUseTheBridge() {
        val worker = source("app/src/main/java/com/ironwill/app/worker/AlarmHeartbeatWorker.kt")
        val guardian = source("app/src/main/java/com/ironwill/app/service/AlarmGuardianService.kt")
        val window = source("app/src/main/java/com/ironwill/app/manager/AlarmWindowManager.kt")
        val activity = source("app/src/main/java/com/ironwill/app/ui/AlarmActivity.kt")
        val vibration = source("app/src/main/java/com/ironwill/app/service/AlarmVibrationService.kt")
        val lock = source("app/src/main/java/com/ironwill/app/ui/LockWindowManager.kt")
        val lockAlarm = source("app/src/main/java/com/ironwill/app/ui/LockAlarmOverlay.kt")
        val core = source("app/src/main/java/com/ironwill/app/service/CoreService.kt")

        assertGuarded(worker, "心跳(worker)", "DefaultLogDebugConfig.protectionVerbose")
        assertGuarded(guardian, "💓 心跳", "DefaultLogDebugConfig.protectionVerbose")
        assertTrue(window.contains("recordAlarmOverlayShow(") && window.contains("recordAlarmOverlayHide("))
        assertTrue(activity.contains("recordAlarmChallengeStart(") && activity.contains("recordAlarmChallengeResult("))
        assertTrue(vibration.contains("recordAlarmRingStart(") && vibration.contains("recordAlarmRingEnd("))
        // [open-source] 闹钟覆盖层簇已拆入 LockAlarmOverlay.kt，事件桥调用跨两文件检查
        assertTrue(
            (lock + lockAlarm).contains("recordAlarmSnooze(") &&
                (lock + lockAlarm).contains("recordAlarmDismiss(")
        )
        assertTrue(guardian.contains("recordAlarmWatchdog(") && guardian.contains("recordAlarmRecovery("))
        assertTrue(core.contains("recordServiceState(") && core.contains("recordProtectionStateChange("))
    }

    private fun assertGuarded(source: String, token: String, flag: String) {
        val at = source.indexOf(token)
        assertTrue("$token missing", at >= 0)
        val prefix = source.substring((at - 1200).coerceAtLeast(0), at)
        assertTrue("$token must be guarded", prefix.contains("if ($flag)"))
    }

    private fun source(relativePath: String): String =
        generateSequence(File(System.getProperty("user.dir") ?: ".").absoluteFile) { it.parentFile }
            .map { File(it, relativePath) }
            .firstOrNull { it.isFile }
            ?.readText()
            ?: error("cannot find $relativePath")
}
