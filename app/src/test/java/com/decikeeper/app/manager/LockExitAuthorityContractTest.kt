package com.decikeeper.app.manager

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LockExitAuthorityContractTest {
    private val root: File = run {
        val cwd = File(System.getProperty("user.dir") ?: ".")
        sequenceOf(cwd, cwd.parentFile)
            .filterNotNull()
            .first { File(it, "app/src/main").exists() }
    }

    private fun source(path: String): String = File(root, path).readText()

    @Test
    fun `visible lock dismiss api requires one of explicit authorities`() {
        val authorityFile = File(
            root,
            "app/src/main/java/com/decikeeper/app/ui/LockDismissAuthority.kt"
        )
        assertTrue("LockDismissAuthority.kt must exist", authorityFile.exists())
        val authority = authorityFile.readText()
        listOf("RETURN_HOME", "REST", "TEMPORARY_ACCESS", "WEEKLY_PRIVILEGE", "NATURAL_RULE_RELEASE").forEach { value ->
            assertTrue("missing explicit exit authority $value", authority.contains(value))
        }
        assertFalse("Presence must never become an unlock authority", authority.contains("PRESENCE"))
        assertFalse("System/OEM windows must never become an unlock authority", authority.contains("SYSTEM_DIALOG"))

        val lockWindow = source("app/src/main/java/com/decikeeper/app/ui/LockWindowManager.kt")
        assertTrue(
            "dismissLockScreen must require explicit authority",
            lockWindow.contains("fun dismissLockScreen(authority: LockDismissAuthority)")
        )
        assertFalse(lockWindow.contains("dismissLockScreen(force: Boolean"))
        assertFalse(lockWindow.contains("dismissLockScreen(force = true)"))
        assertFalse(lockWindow.contains("dismissLockScreen(true)"))

        listOf(
            "LockDismissAuthority.RETURN_HOME",
            "LockDismissAuthority.REST",
            "LockDismissAuthority.TEMPORARY_ACCESS",
            "LockDismissAuthority.WEEKLY_PRIVILEGE",
            "LockDismissAuthority.NATURAL_RULE_RELEASE"
        ).forEach { call ->
            assertTrue("missing authorized exit call $call", lockWindow.contains(call))
        }
        assertFalse(
            "temporary access must not bypass the authority gate with direct WindowManager removal",
            lockWindow.contains("startTemporaryAccess: force removeView ignored")
        )
        assertFalse(
            "emergency temporary access must not bypass the authority gate with direct WindowManager removal",
            lockWindow.contains("startTemporaryAccess(emergency): force removeView ignored")
        )
    }

    @Test
    fun `automatic presence and screen off paths cannot dismiss a visible blacklist lock`() {
        val monitor = source("app/src/main/java/com/decikeeper/app/service/MonitorService.kt")
        assertFalse(
            "MonitorService detection paths may not directly dismiss the lock",
            monitor.contains("LockWindowManager.dismissLockScreen")
        )

        val policy = source("app/src/main/java/com/decikeeper/app/manager/AutomaticRestrictionReleasePolicy.kt")
        assertTrue(
            "Spec v1.0: natural rule changes truly release visible overlay when naturallyFree",
            policy.contains("dismissBlacklistOverlay = naturallyFree && hasBlacklistOverlay")
        )

        val lockWindow = source("app/src/main/java/com/decikeeper/app/ui/LockWindowManager.kt")
        assertFalse(
            "screen-off is not one of the four exits",
            lockWindow.contains("resetLockState(\"ScreenOff\")")
        )
        assertFalse(
            "natural-freedom temporary-access settlement must not dismiss the existing lock",
            Regex("FREE_TIME_STARTED[\\s\\S]{0,500}dismissLockScreen").containsMatchIn(lockWindow)
        )
    }

    @Test
    fun `accessibility reconnect rebuilds an existing blacklist lock instead of trusting stale attachment`() {
        val core = source("app/src/main/java/com/decikeeper/app/service/CoreService.kt")
        assertTrue(core.contains("[A11Y_RECONNECT_REBUILD]"))
        assertTrue(core.contains("LockWindowManager.getCurrentBlockedPackageName()"))
        assertTrue(core.contains("forceBypassGrace = true"))
        assertTrue(core.contains("isScreenOnRedetection = true"))
    }
}
