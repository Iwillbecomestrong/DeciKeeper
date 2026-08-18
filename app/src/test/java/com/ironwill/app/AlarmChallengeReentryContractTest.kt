package com.ironwill.app

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AlarmChallengeReentryContractTest {
    @Test
    fun activeAlarmChallengeButtonAlwaysRoutesToRecoveryInsteadOfRejectingVisibleState() {
        val fragment = source("app/src/main/java/com/ironwill/app/ui/AlarmFragment.kt")

        assertTrue(
            "active alarm challenge must route through the recovery entry point",
            fragment.contains("AlarmWindowManager.reenterChallenge(ctx, activeAlarmId)")
        )
        assertFalse(
            "an internally attached overlay must not block the user's recovery button",
            fragment.contains("挑战界面已经在显示了，请回到前台查看")
        )
    }

    @Test
    fun challengeRecoveryRefreshesScreenKeepAliveAndOverlayVisibility() {
        val window = source("app/src/main/java/com/ironwill/app/manager/AlarmWindowManager.kt")
        val overlay = source("app/src/main/java/com/ironwill/app/ui/LockAlarmOverlay.kt")

        assertTrue(window.contains("fun reenterChallenge(context: Context, alarmId: Int)"))
        assertTrue(window.contains("CHALLENGE_REENTER_REQUEST"))
        assertTrue(window.contains("SCREEN_KEEP_ALIVE_REFRESH"))
        assertTrue(
            "alarm challenge view itself must request screen-on in addition to the window flag/wakelock",
            overlay.contains("keepScreenOn = true")
        )
    }

    private fun source(relativePath: String): String =
        generateSequence(File(System.getProperty("user.dir") ?: ".").absoluteFile) { it.parentFile }
            .map { File(it, relativePath) }
            .firstOrNull { it.isFile }
            ?.readText()
            ?: error("cannot find $relativePath")
}
