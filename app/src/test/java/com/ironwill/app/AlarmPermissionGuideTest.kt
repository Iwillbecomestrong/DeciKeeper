package com.ironwill.app

import com.ironwill.app.manager.AlarmChallengeEmergencyWhitelist
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AlarmPermissionGuideTest {

    @Test
    fun `isSystemPermissionController recognizes known system permission packages`() {
        val controllers = listOf(
            "com.android.permissioncontroller",
            "com.google.android.permissioncontroller",
            "com.vivo.permissionmanager",
            "com.android.packageinstaller"
        )
        for (pkg in controllers) {
            assertTrue(
                "Package $pkg must be recognized as system permission controller",
                AlarmChallengeEmergencyWhitelist.isSystemPermissionController(pkg)
            )
        }

        val nonControllers = listOf(
            "com.ironwill.app",
            "com.android.settings",
            "com.tencent.mm",
            "com.ss.android.ugc.aweme"
        )
        for (pkg in nonControllers) {
            assertFalse(
                "Package $pkg must not be recognized as system permission controller",
                AlarmChallengeEmergencyWhitelist.isSystemPermissionController(pkg)
            )
        }
    }

    @Test
    fun `permission controller allowed during active challenge when permission request in progress`() {
        val allowed = setOf("com.android.dialer")

        assertTrue(
            AlarmChallengeEmergencyWhitelist.isPackageAllowedDuringChallenge(
                alarmActive = true,
                foregroundPackage = "com.vivo.permissionmanager",
                selfPackage = "com.ironwill.app",
                allowedPackages = allowed,
                isPermissionRequestInProgress = true
            )
        )
        assertTrue(
            AlarmChallengeEmergencyWhitelist.isPackageAllowedDuringChallenge(
                alarmActive = true,
                foregroundPackage = "com.google.android.permissioncontroller",
                selfPackage = "com.ironwill.app",
                allowedPackages = allowed,
                isPermissionRequestInProgress = true
            )
        )
    }

    @Test
    fun `permission controller forbidden during active challenge when permission request NOT in progress`() {
        val allowed = setOf("com.android.dialer")

        assertFalse(
            AlarmChallengeEmergencyWhitelist.isPackageAllowedDuringChallenge(
                alarmActive = true,
                foregroundPackage = "com.vivo.permissionmanager",
                selfPackage = "com.ironwill.app",
                allowedPackages = allowed,
                isPermissionRequestInProgress = false
            )
        )
        assertFalse(
            AlarmChallengeEmergencyWhitelist.isPackageAllowedDuringChallenge(
                alarmActive = true,
                foregroundPackage = "com.android.permissioncontroller",
                selfPackage = "com.ironwill.app",
                allowedPackages = allowed,
                isPermissionRequestInProgress = false
            )
        )
    }
}
