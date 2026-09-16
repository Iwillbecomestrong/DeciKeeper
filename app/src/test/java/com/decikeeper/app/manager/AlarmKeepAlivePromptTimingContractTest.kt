package com.decikeeper.app.manager

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AlarmKeepAlivePromptTimingContractTest {

    private fun source(path: String): String {
        val cwd = File(System.getProperty("user.dir") ?: ".")
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
    fun keepaliveGuideIsTriggeredWhenAlarmTabIsEnteredNotDuringInitialReload() {
        val fragment = source("src/main/java/com/decikeeper/app/ui/AlarmFragment.kt")
        val mainActivity = source("src/main/java/com/decikeeper/app/ui/MainActivity.kt")
        val reloadGuideCheck = fragment.substringAfter(
            "private fun checkExistingAlarmsPermissionsAndGuide()"
        ).substringBefore("private fun refreshChallengeButtonsIfNeeded()")

        assertFalse(
            "initial alarm reload must not show the keepalive guide",
            reloadGuideCheck.contains("checkAndPromptAlarmKeepalive(ctx)")
        )
        assertTrue(
            "AlarmFragment must expose an alarm-tab entry hook",
            fragment.contains("fun onEnteredAlarmTab()")
        )
        assertTrue(
            "MainActivity must invoke the guide only when switching to the alarm tab",
            mainActivity.contains("alarmFragment.onEnteredAlarmTab()")
        )
    }

    @Test
    fun keepaliveGuideCanBeCancelled() {
        val fragment = source("src/main/java/com/decikeeper/app/ui/AlarmFragment.kt")

        assertTrue(
            "keepalive guide must provide a cancel button",
            fragment.contains(".setNegativeButton(\"取消\", null)")
        )
    }
}
