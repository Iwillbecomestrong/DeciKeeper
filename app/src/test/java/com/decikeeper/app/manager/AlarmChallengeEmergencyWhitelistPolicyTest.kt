package com.decikeeper.app.manager

import com.decikeeper.app.manager.AlarmChallengeEmergencyWhitelist
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AlarmChallengeEmergencyWhitelistPolicyTest {

    @Test
    fun `active challenge only allows challenge app and configured emergency apps`() {
        val allowed = setOf("com.android.dialer", "com.android.mms")

        assertTrue(AlarmChallengeEmergencyWhitelist.isPackageAllowedDuringChallenge(true, "com.decikeeper.app", "com.decikeeper.app", allowed))
        assertTrue(AlarmChallengeEmergencyWhitelist.isPackageAllowedDuringChallenge(true, "com.android.dialer", "com.decikeeper.app", allowed))
        assertTrue(AlarmChallengeEmergencyWhitelist.isPackageAllowedDuringChallenge(true, "com.android.mms", "com.decikeeper.app", allowed))
        assertFalse(AlarmChallengeEmergencyWhitelist.isPackageAllowedDuringChallenge(true, "com.ss.android.ugc.aweme", "com.decikeeper.app", allowed))
    }

    @Test
    fun `call UI is allowed during challenge even when only the dialer is whitelisted`() {
        val allowed = setOf("com.android.contacts")

        listOf(
            "com.android.incallui",
            "com.samsung.android.incallui",
            "com.android.phone",
            "com.android.server.telecom"
        ).forEach { callUi ->
            assertTrue(
                callUi,
                AlarmChallengeEmergencyWhitelist.isPackageAllowedDuringChallenge(true, callUi, "com.decikeeper.app", allowed)
            )
        }
        assertFalse(
            AlarmChallengeEmergencyWhitelist.isPackageAllowedDuringChallenge(true, "com.ss.android.ugc.aweme", "com.decikeeper.app", allowed)
        )
        assertFalse(AlarmChallengeEmergencyWhitelist.isCallUiPackage("com.android.settings"))
    }

    @Test
    fun `inactive challenge does not impose emergency whitelist restriction`() {
        assertTrue(
            AlarmChallengeEmergencyWhitelist.isPackageAllowedDuringChallenge(
                false,
                "com.ss.android.ugc.aweme",
                "com.decikeeper.app",
                emptySet()
            )
        )
    }

    @Test
    fun `selection is capped at two apps and excludes dangerous packages`() {
        val selected = listOf("com.android.dialer", "com.android.settings", "com.tencent.mm", "com.example.third")
        val sanitized = AlarmChallengeEmergencyWhitelist.sanitizeSelection(
            selected,
            selfPackage = "com.decikeeper.app",
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
                selfPackage = "com.decikeeper.app",
                allowedPackages = allowed,
                isPermissionRequestInProgress = true
            )
        )
        assertTrue(
            AlarmChallengeEmergencyWhitelist.isPackageAllowedDuringChallenge(
                alarmActive = true,
                foregroundPackage = "com.google.android.permissioncontroller",
                selfPackage = "com.decikeeper.app",
                allowedPackages = allowed,
                isPermissionRequestInProgress = true
            )
        )
        assertTrue(
            AlarmChallengeEmergencyWhitelist.isPackageAllowedDuringChallenge(
                alarmActive = true,
                foregroundPackage = "com.vivo.permissionmanager",
                selfPackage = "com.decikeeper.app",
                allowedPackages = allowed,
                isPermissionRequestInProgress = true
            )
        )

        assertFalse(
            AlarmChallengeEmergencyWhitelist.isPackageAllowedDuringChallenge(
                alarmActive = true,
                foregroundPackage = "com.android.permissioncontroller",
                selfPackage = "com.decikeeper.app",
                allowedPackages = allowed,
                isPermissionRequestInProgress = false
            )
        )
        assertFalse(
            AlarmChallengeEmergencyWhitelist.isPackageAllowedDuringChallenge(
                alarmActive = true,
                foregroundPackage = "com.vivo.permissionmanager",
                selfPackage = "com.decikeeper.app",
                allowedPackages = allowed,
                isPermissionRequestInProgress = false
            )
        )
    }
}

