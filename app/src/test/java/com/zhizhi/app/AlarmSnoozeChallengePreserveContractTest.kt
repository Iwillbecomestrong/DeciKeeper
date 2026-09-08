package com.zhizhi.app

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AlarmSnoozeChallengePreserveContractTest {

    @Test
    fun snoozeSilencesOutputWithoutDismissingTheChallengeOverlay() {
        val overlay = source("app/src/main/java/com/zhizhi/app/ui/LockAlarmOverlay.kt")
        val action = overlay
            .substringAfter("private fun handleAlarmAction(snooze: Boolean)")
            .substringBefore("private fun getAlarmLayoutParams()")

        assertTrue(
            "snooze must consume the existing one-shot snooze allowance without scheduling a new alarm session",
            action.contains("AlarmScheduler.recordSnoozeUsed")
        )
        assertTrue(
            "snooze must silence the currently running alarm output",
            action.contains("AlarmVibrationService.silenceForSnooze")
        )
        assertFalse(
            "snooze must not schedule a replacement alarm that tears down the current challenge session",
            action.contains("AlarmScheduler.scheduleSnooze")
        )
        assertFalse(
            "snooze must keep the challenge overlay alive",
            action.contains("dismissAlarmOverlay(\"已暂停")
        )
    }

    @Test
    fun snoozeButtonIsAvailableDuringTheFirstChallengeButCloseRemainsHidden() {
        val overlay = source("app/src/main/java/com/zhizhi/app/ui/LockAlarmOverlay.kt")
        val panel = overlay
            .substringAfter("private fun showAlarmActionPanel(")
            .substringBefore("private var alarmPassthroughRestoreRunnable")

        assertTrue(
            "the action row must stay visible during an active first-stage challenge when snooze is still available",
            panel.contains("val canSnooze = forceHide && alarmRemainingSnoozeCount > 0")
        )
        assertTrue(
            "the close button must remain hidden while the challenge is unfinished",
            panel.contains("btnClose?.visibility = if (forceHide) View.GONE else View.VISIBLE")
        )
        assertTrue(
            "snooze must remain visible while the challenge itself stays on screen",
            panel.contains("btnSnooze?.visibility = if (canSnooze) View.VISIBLE else View.GONE")
        )
    }

    @Test
    fun snoozeIsOneShotPerAlarmEvenIfLegacySettingsAllowedMore() {
        val overlay = source("app/src/main/java/com/zhizhi/app/ui/LockAlarmOverlay.kt")

        assertTrue(
            "runtime snooze availability must be capped to one use per alarm session",
            overlay.contains("AlarmScheduler.getSnoozeUsedCount(context, alarmId) == 0")
        )
        assertTrue(
            "successful snooze must remove the remaining allowance immediately",
            overlay.contains("alarmRemainingSnoozeCount = 0")
        )
    }

    @Test
    fun vibrationServiceImplementsARealZeroOutputSnoozeAndRestoresLater() {
        val service = source("app/src/main/java/com/zhizhi/app/service/AlarmVibrationService.kt")

        assertTrue(service.contains("ACTION_SNOOZE_SILENCE"))
        assertTrue(service.contains("fun silenceForSnooze("))
        assertTrue(service.contains("isSnoozeSilenced = true"))
        assertTrue(service.contains("currentDisplayVolume = 0"))
        assertTrue(service.contains("currentDisplayIntensity = 0"))
        assertTrue(service.contains("mediaPlayer?.setVolume(0f, 0f)"))
        assertTrue(service.contains("vibrator?.cancel()"))
        assertTrue(
            "the vibration keep-alive loop must not restart vibration during the silent snooze window",
            service.contains("if (!isSnoozeSilenced && vibrator != null)")
        )
        assertTrue(
            "the normal vibration entry point must also respect the snooze window",
            service.contains("if (isSnoozeSilenced)")
        )
        assertTrue(
            "the output must restore after the snooze duration if the challenge is still active",
            service.contains("snoozeRestoreRunnable") && service.contains("startVibrationImpl()")
        )
    }

    @Test
    fun volumeAndPowerButtonsCanTriggerTheSameSnoozeWithoutClosingTheChallenge() {
        val activity = source("app/src/main/java/com/zhizhi/app/ui/AlarmActivity.kt")
        val service = source("app/src/main/java/com/zhizhi/app/service/AlarmVibrationService.kt")

        assertTrue(activity.contains("KeyEvent.KEYCODE_VOLUME_UP"))
        assertTrue(activity.contains("KeyEvent.KEYCODE_VOLUME_DOWN"))
        assertTrue(activity.contains("LockAlarmOverlayController.requestSnoozeFromHardware"))
        assertTrue(
            "screen-off while the active alarm owns the screen is the power-key snooze path",
            service.contains("Intent.ACTION_SCREEN_OFF") &&
                service.contains("LockAlarmOverlayController.requestSnoozeFromHardware")
        )
    }

    @Test
    fun defaultSettingsKeepOnlyTheIntervalBecauseSnoozeIsNowOneShot() {
        val fragment = source("app/src/main/java/com/zhizhi/app/ui/AlarmFragment.kt")
        val defaults = fragment
            .substringAfter("// --- 3. 再响设置 ---")
            .substringBefore("// --- 4.")

        assertTrue(defaults.contains("本次闹钟仅可使用 1 次"))
        assertFalse(
            "the default settings should no longer ask users to configure repeated snooze counts",
            defaults.contains("getMaxSnoozeCount") || defaults.contains("\"最多\"")
        )
    }

    private fun source(relativePath: String): String =
        generateSequence(File(System.getProperty("user.dir") ?: ".").absoluteFile) { it.parentFile }
            .map { File(it, relativePath) }
            .firstOrNull { it.isFile }
            ?.readText()
            ?: error("cannot find $relativePath")
}
