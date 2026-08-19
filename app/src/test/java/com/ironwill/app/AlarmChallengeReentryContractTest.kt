package com.ironwill.app

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class AlarmChallengeReentryContractTest {
    @Test
    fun activeAlarmChallengeButtonAlwaysRoutesToRecoveryEntryPoint() {
        val adapter = source("app/src/main/java/com/ironwill/app/ui/AlarmListAdapter.kt")

        assertTrue(
            "when the visible Challenge button is clicked, stale internal overlay state must not block recovery",
            adapter.contains("AlarmWindowManager.reenterChallenge(itemView.context, item.id)")
        )
    }

    @Test
    fun challengeRecoveryRefreshesScreenWakeAndHandlesUnexpectedScreenOff() {
        val window = source("app/src/main/java/com/ironwill/app/manager/AlarmWindowManager.kt")

        assertTrue(window.contains("fun reenterChallenge(context: Context, alarmId: Int)"))
        assertTrue(window.contains("CHALLENGE_REENTER_REQUEST"))
        assertTrue(window.contains("SCREEN_KEEP_ALIVE_REFRESH"))
        assertTrue(
            "while an alarm remains active, an unexpected screen-off must trigger a wake refresh",
            window.contains("Intent.ACTION_SCREEN_OFF") && window.contains("SCREEN_OFF_RECOVERY")
        )
    }

    @Test
    fun emergencyWhitelistIsVisibleInSettingsAndInsideTheChallenge() {
        val settingsLayout = source("app/src/main/res/layout/activity_global_params_new.xml")
        val alarmLayout = source("app/src/main/res/layout/window_alarm_overlay.xml")
        val alarmOverlay = source("app/src/main/java/com/ironwill/app/ui/LockAlarmOverlay.kt")
        val guard = source("app/src/main/java/com/ironwill/app/service/AlarmChallengeEmergencyGuardService.kt")

        assertTrue(
            "global settings must contain the existing emergency-whitelist settings card",
            settingsLayout.contains("com.ironwill.app.ui.AlarmEmergencyWhitelistSettingsCard")
        )
        assertTrue(
            "the challenge must expose a fixed emergency-use section at the bottom",
            alarmLayout.contains("@+id/layout_alarm_emergency_use") &&
                alarmLayout.contains("@+id/container_alarm_emergency_apps") &&
                alarmLayout.contains("应急使用")
        )
        assertTrue(
            "challenge shortcuts must launch through the existing whitelist manager without completing the challenge",
            alarmOverlay.contains("AlarmChallengeEmergencyWhitelist.launch")
        )
        assertTrue(
            "emergency launch must cooperate with the foreground guard instead of creating a second floating shortcut bar",
            alarmOverlay.contains("AlarmChallengeEmergencyGuardService.beginEmergencyAccess") &&
                !guard.contains("private fun ensureBar()")
        )
        assertTrue(
            "after the final challenge passes, emergency-only foreground restrictions must be released",
            alarmOverlay.contains("AlarmChallengeEmergencyGuardService.stop(ctx.applicationContext)")
        )
    }

    private fun source(relativePath: String): String =
        generateSequence(File(System.getProperty("user.dir") ?: ".").absoluteFile) { it.parentFile }
            .map { File(it, relativePath) }
            .firstOrNull { it.isFile }
            ?.readText()
            ?: error("cannot find $relativePath")
}
