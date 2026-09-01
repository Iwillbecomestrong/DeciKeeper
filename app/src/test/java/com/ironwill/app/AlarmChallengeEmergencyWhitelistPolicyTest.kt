package com.ironwill.app

import com.ironwill.app.manager.AlarmChallengeEmergencyWhitelist
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AlarmChallengeEmergencyWhitelistPolicyTest {

    @Test
    fun `active challenge only allows challenge app and configured emergency apps`() {
        val allowed = setOf("com.android.dialer", "com.android.mms")

        assertTrue(AlarmChallengeEmergencyWhitelist.isPackageAllowedDuringChallenge(true, "com.ironwill.app", "com.ironwill.app", allowed))
        assertTrue(AlarmChallengeEmergencyWhitelist.isPackageAllowedDuringChallenge(true, "com.android.dialer", "com.ironwill.app", allowed))
        assertTrue(AlarmChallengeEmergencyWhitelist.isPackageAllowedDuringChallenge(true, "com.android.mms", "com.ironwill.app", allowed))
        assertFalse(AlarmChallengeEmergencyWhitelist.isPackageAllowedDuringChallenge(true, "com.ss.android.ugc.aweme", "com.ironwill.app", allowed))
    }

    @Test
    fun `inactive challenge does not impose emergency whitelist restriction`() {
        assertTrue(
            AlarmChallengeEmergencyWhitelist.isPackageAllowedDuringChallenge(
                false,
                "com.ss.android.ugc.aweme",
                "com.ironwill.app",
                emptySet()
            )
        )
    }

    @Test
    fun `selection is capped at two apps and excludes dangerous packages`() {
        val selected = listOf("com.android.dialer", "com.android.settings", "com.tencent.mm", "com.example.third")
        val sanitized = AlarmChallengeEmergencyWhitelist.sanitizeSelection(
            selected,
            selfPackage = "com.ironwill.app",
            dangerousPackages = setOf("com.android.settings")
        )

        assertTrue(sanitized == listOf("com.android.dialer", "com.tencent.mm"))
    }

    @Test
    fun `permission controller allowed during active challenge only when permission request in progress`() {
        val allowed = setOf("com.android.dialer")

        assertTrue(
            AlarmChallengeEmergencyWhitelist.isPackageAllowedDuringChallenge(
                alarmActive = true,
                foregroundPackage = "com.android.permissioncontroller",
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
        assertTrue(
            AlarmChallengeEmergencyWhitelist.isPackageAllowedDuringChallenge(
                alarmActive = true,
                foregroundPackage = "com.vivo.permissionmanager",
                selfPackage = "com.ironwill.app",
                allowedPackages = allowed,
                isPermissionRequestInProgress = true
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
        assertFalse(
            AlarmChallengeEmergencyWhitelist.isPackageAllowedDuringChallenge(
                alarmActive = true,
                foregroundPackage = "com.vivo.permissionmanager",
                selfPackage = "com.ironwill.app",
                allowedPackages = allowed,
                isPermissionRequestInProgress = false
            )
        )
    }
}

