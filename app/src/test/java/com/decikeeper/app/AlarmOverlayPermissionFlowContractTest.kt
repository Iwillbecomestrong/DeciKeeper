package com.decikeeper.app

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AlarmOverlayPermissionFlowContractTest {
    @Test
    fun alarmConfigurationEntryPointsRequireOverlayBeforeOpeningOrSaving() {
        val fragment = source("app/src/main/java/com/decikeeper/app/ui/AlarmFragment.kt")

        assertTrue(fragment.contains("if (ensureAlarmOverlayPermission()) showEditDialog(null)"))
        assertTrue(fragment.contains("if (ensureAlarmOverlayPermission()) showTestDialog()"))
        assertTrue(fragment.contains("if (!ensureAlarmOverlayPermission()) return@setOnClickListener"))
        assertTrue(fragment.contains("if (entry.enabled && !ensureAlarmOverlayPermission())"))
        assertTrue(fragment.contains("Settings.ACTION_MANAGE_OVERLAY_PERMISSION"))
        assertTrue(fragment.contains("AlarmOverlayRequirement.TITLE"))
        assertTrue(fragment.contains("AlarmOverlayRequirement.MESSAGE"))
    }

    @Test
    fun runtimeAlarmDoesNotFallbackToPageChallengeWithoutOverlay() {
        val manager = source("app/src/main/java/com/decikeeper/app/manager/AlarmWindowManager.kt")

        assertTrue(manager.contains("handleMissingOverlayPermission(context, alarmId)"))
        assertTrue(manager.contains("AlarmVibrationService.stop(appCtx)"))
        assertTrue(manager.contains("AlarmScheduler.clearAlarmActive(appCtx)"))
        assertFalse(manager.contains("NO_OVERLAY_PERMISSION_FALLBACK_ACTIVITY"))
    }

    @Test
    fun everyAlarmActivityEntryPathHasOverlayGuard() {
        val activity = source("app/src/main/java/com/decikeeper/app/ui/AlarmActivity.kt")

        assertTrue(activity.contains("if (!AlarmOverlayRequirement.isGranted(this))"))
        assertTrue(activity.contains("ACTIVITY_REFUSED_NO_OVERLAY"))
        assertTrue(activity.contains("onNewIntent: overlay permission missing"))
        assertTrue(activity.contains("onResume: overlay permission missing"))
    }

    private fun source(path: String): String {
        val cwd = File(System.getProperty("user.dir") ?: ".")
        val candidates = listOf(
            File(cwd, path),
            File(cwd, path.removePrefix("app/")),
            File(cwd.parentFile ?: cwd, path),
            File(cwd.parentFile ?: cwd, path.removePrefix("app/"))
        )
        return candidates.firstOrNull { it.isFile }?.readText()
            ?: error("Cannot locate $path from ${cwd.absolutePath}")
    }
}
