package com.zhizhi.app

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AlarmSessionIsolationContractTest {

    @Test
    fun rejectedTriggerCleanupDoesNotTearDownRunningAlarmSession() {
        val receiver = source("app/src/main/java/com/zhizhi/app/receiver/AlarmReceiver.kt")
        val cleanup = receiver
            .substringAfter("private fun cleanupStaleAlarm")
            .substringBefore("private data class ValidateResult")

        assertFalse(
            "a rejected stale trigger must not stop the shared vibration service",
            cleanup.contains("AlarmVibrationService.stop(context)")
        )
        assertFalse(
            "a rejected stale trigger must not clear the authoritative active alarm session",
            cleanup.contains("AlarmScheduler.clearAlarmActive(context)")
        )
        assertFalse(
            "a rejected stale trigger must not dismiss the current alarm window bookkeeping",
            cleanup.contains("AlarmWindowManager.dismiss()")
        )
    }

    @Test
    fun recoveryCallbackMustMatchTheActiveAlarmId() {
        val receiver = source("app/src/main/java/com/zhizhi/app/receiver/AlarmReceiver.kt")
        val recovery = receiver
            .substringAfter("if (action == AlarmScheduler.ACTION_ALARM_RECOVERY)")
            .substringBefore("// ----- 重启安全网")

        assertTrue(
            "recovery must read the authoritative active alarm id",
            recovery.contains("AlarmScheduler.getActiveAlarmId(context)")
        )
        assertTrue(
            "a stale recovery callback must be rejected when its id differs from the active alarm",
            recovery.contains("activeAlarmId == alarmId") || recovery.contains("activeAlarmId != alarmId")
        )
    }

    @Test
    fun rebootSafetyNetCallbackMustMatchTheActiveAlarmId() {
        val receiver = source("app/src/main/java/com/zhizhi/app/receiver/AlarmReceiver.kt")
        val safetyNet = receiver
            .substringAfter("if (action == AlarmScheduler.ACTION_ALARM_REBOOT_SAFETY_NET)")
            .substringBefore("// ----- 正常闹钟触发 / 休眠")

        assertTrue(
            "reboot safety-net must read the authoritative active alarm id",
            safetyNet.contains("AlarmScheduler.getActiveAlarmId(context)")
        )
        assertTrue(
            "a stale reboot safety-net callback must be rejected when its id differs from the active alarm",
            safetyNet.contains("activeAlarmId == alarmId") || safetyNet.contains("activeAlarmId != alarmId")
        )
    }

    @Test
    fun vibrationServiceDestructionDoesNotEndTheBusinessAlarmSession() {
        val service = source("app/src/main/java/com/zhizhi/app/service/AlarmVibrationService.kt")
        val onDestroy = service
            .substringAfter("override fun onDestroy()")
            .substringBefore("override fun onBind")

        assertFalse(
            "Android destroying the vibration service must not declare the alarm challenge finished",
            onDestroy.contains("AlarmScheduler.clearAlarmActive(this)")
        )
    }

    @Test
    fun explicitChallengeDismissStillOwnsAlarmSessionTeardown() {
        val overlay = source("app/src/main/java/com/zhizhi/app/ui/LockAlarmOverlay.kt")
        val dismiss = overlay
            .substringAfter("private fun dismissAlarmOverlay(reason: String, snooze: Boolean)")
            .substringBefore("/**\n     * [DO NOT DELETE] 强制重置闹钟覆盖层状态")

        assertTrue(
            "explicit challenge completion must still clear the active alarm session",
            dismiss.contains("AlarmScheduler.clearAlarmActive(ctx)")
        )
        assertTrue(
            "explicit challenge completion must still stop the vibration service",
            dismiss.contains("AlarmVibrationService.stop(ctx)")
        )
    }

    private fun source(relativePath: String): String =
        generateSequence(File(System.getProperty("user.dir") ?: ".").absoluteFile) { it.parentFile }
            .map { File(it, relativePath) }
            .firstOrNull { it.isFile }
            ?.readText()
            ?: error("cannot find $relativePath")
}
