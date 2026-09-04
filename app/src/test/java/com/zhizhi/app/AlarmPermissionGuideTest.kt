package com.zhizhi.app

import com.zhizhi.app.manager.AlarmChallengeEmergencyWhitelist
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
            "com.zhizhi.app",
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
                selfPackage = "com.zhizhi.app",
                allowedPackages = allowed,
                isPermissionRequestInProgress = true
            )
        )
        assertTrue(
            AlarmChallengeEmergencyWhitelist.isPackageAllowedDuringChallenge(
                alarmActive = true,
                foregroundPackage = "com.google.android.permissioncontroller",
                selfPackage = "com.zhizhi.app",
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
                selfPackage = "com.zhizhi.app",
                allowedPackages = allowed,
                isPermissionRequestInProgress = false
            )
        )
        assertFalse(
            AlarmChallengeEmergencyWhitelist.isPackageAllowedDuringChallenge(
                alarmActive = true,
                foregroundPackage = "com.android.permissioncontroller",
                selfPackage = "com.zhizhi.app",
                allowedPackages = allowed,
                isPermissionRequestInProgress = false
            )
        )
    }

    @Test
    fun `emergency whitelist constants and max app limit conform to specification`() {
        org.junit.Assert.assertEquals("alarm_emergency_whitelist", com.zhizhi.app.ui.AppSelectionActivity.MODE_ALARM_EMERGENCY_WHITELIST)
        org.junit.Assert.assertEquals(2, AlarmChallengeEmergencyWhitelist.MAX_APPS)
    }
}

