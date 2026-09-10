package com.decikeeper.app

import android.content.Context
import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class AlarmPowerOffProtectionManagerTest {

    @Test
    fun prerequisiteSnapshotIsReadyOnlyWhenEveryCapabilityIsReady() {
        val ready = com.decikeeper.app.manager.AlarmPowerOffProtectionManager.PrerequisiteStatus(
            a11yReady = true,
            overlayReady = true,
            notificationPermissionReady = true,
            notificationsEnabled = true,
            lockScreenDisplayReady = true
        )
        val missingLockScreen = ready.copy(lockScreenDisplayReady = false)

        assertTrue(ready.allReady)
        assertFalse(missingLockScreen.allReady)
        assertTrue(missingLockScreen.missingLabels.contains("锁屏显示"))
    }

    @Test
    fun managerOwnsOneCompletePrerequisiteSnapshot() {
        val manager = source("app/src/main/java/com/decikeeper/app/manager/AlarmPowerOffProtectionManager.kt")
        assertTrue(manager.contains("data class PrerequisiteStatus"))
        assertTrue(manager.contains("lockScreenDisplayReady"))
        assertFalse(manager.contains("fullScreenIntentReady"))
        assertFalse(manager.contains("canUseFullScreenIntent"))
        assertTrue(manager.contains("overlayReady"))
        assertTrue(manager.contains("notificationPermissionReady"))
    }

    @Test
    fun userFacingCopyDoesNotAskForFullScreenNotification() {
        val fragment = source("app/src/main/java/com/decikeeper/app/ui/RulesFragment.kt")
        val strings = source("app/src/main/res/values/strings.xml")

        assertFalse(fragment.contains("全屏通知"))
        assertFalse(strings.contains("全屏通知"))
    }

    @Test
    fun lockScreenDisplayUsesIndependentManualConfirmation() {
        val context = RuntimeEnvironment.getApplication()
        val prefs = context.getSharedPreferences(
            "alarm_power_off_protection_prefs",
            Context.MODE_PRIVATE
        )
        prefs.edit().clear().putBoolean("assoc_start_confirmed", true).commit()

        assertFalse(com.decikeeper.app.manager.AlarmPowerOffProtectionManager.isLockScreenDisplayReady(context))

        com.decikeeper.app.manager.AlarmPowerOffProtectionManager.setLockScreenDisplayConfirmed(context, true)
        assertTrue(com.decikeeper.app.manager.AlarmPowerOffProtectionManager.isLockScreenDisplayReady(context))

        com.decikeeper.app.manager.AlarmPowerOffProtectionManager.setLockScreenDisplayConfirmed(context, false)
        assertFalse(com.decikeeper.app.manager.AlarmPowerOffProtectionManager.isLockScreenDisplayReady(context))
    }

    @Test
    fun settingsSwitchMustCheckManagerBeforePersistingEnabled() {
        val fragment = source("app/src/main/java/com/decikeeper/app/ui/RulesFragment.kt")
        val setEnabledIndex = fragment.indexOf("AlarmPowerOffProtectionManager.setEnabled(context, true)")
        val canEnableIndex = fragment.indexOf("AlarmPowerOffProtectionManager.canEnable(context)")
        assertTrue(setEnabledIndex >= 0)
        assertTrue(canEnableIndex >= 0 && canEnableIndex < setEnabledIndex)
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
