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
        val provider = source("app/src/main/java/com/ironwill/app/ui/AlarmEmergencyWhitelistUiProvider.kt")
        val alarmActivity = source("app/src/main/java/com/ironwill/app/ui/AlarmActivity.kt")
        val guard = source("app/src/main/java/com/ironwill/app/service/AlarmChallengeEmergencyGuardService.kt")

        assertTrue(
            "global settings must install or refresh the whitelist card both when created and resumed",
            provider.contains("installOrRefresh(activity)") &&
                provider.contains("override fun onActivityCreated") &&
                provider.contains("override fun onActivityResumed")
        )
        assertTrue(
            "the existing guard must render the emergency-use section inside the current challenge view",
            guard.contains("应急使用") &&
                guard.contains("LockWindowManager.getAlarmView()") &&
                guard.contains("R.id.layout_alarm_actions")
        )
        assertTrue(
            "emergency shortcuts must launch through the existing whitelist manager",
            guard.contains("AlarmChallengeEmergencyWhitelist.launch")
        )
        assertTrue(
            "the challenge overlay must be hidden without resetting its in-memory progress while an emergency app is opened",
            guard.contains("LockWindowManager.hideAlarmOverlayForPermission()") &&
                guard.contains("LockWindowManager.showAlarmOverlayIfHidden()")
        )
        assertTrue(
            "the old independent floating emergency bar must be removed instead of duplicating the challenge UI",
            !guard.contains("private fun ensureBar()")
        )
        assertTrue(
            "lock-screen AlarmActivity must not immediately pull itself back while an approved emergency launch is in progress",
            alarmActivity.contains("AlarmChallengeEmergencyGuardService.isEmergencyAccessInProgress()")
        )
    }

    private fun source(relativePath: String): String =
        generateSequence(File(System.getProperty("user.dir") ?: ".").absoluteFile) { it.parentFile }
            .map { File(it, relativePath) }
            .firstOrNull { it.isFile }
            ?.readText()
            ?: error("cannot find $relativePath")
}
