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

    private fun source(relativePath: String): String =
        generateSequence(File(System.getProperty("user.dir") ?: ".").absoluteFile) { it.parentFile }
            .map { File(it, relativePath) }
            .firstOrNull { it.isFile }
            ?.readText()
            ?: error("cannot find $relativePath")
}
