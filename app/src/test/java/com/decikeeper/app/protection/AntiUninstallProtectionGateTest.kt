package com.decikeeper.app.protection

import com.decikeeper.app.manager.AntiUninstallManager
import com.decikeeper.app.protection.AntiUninstallProtection
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AntiUninstallProtectionGateTest {

    @Test
    fun forceStopProtectionOnlyRunsWhenAntiUninstallIsActiveOrPending() {
        assertFalse(
            AntiUninstallProtection.shouldEnforceForceStopProtection(
                AntiUninstallManager.State.DISABLED
            )
        )
        assertTrue(
            AntiUninstallProtection.shouldEnforceForceStopProtection(
                AntiUninstallManager.State.ACTIVE
            )
        )
        assertTrue(
            AntiUninstallProtection.shouldEnforceForceStopProtection(
                AntiUninstallManager.State.PENDING
            )
        )
    }

    @Test
    fun antiUninstallDoesNotClassifyAccessibilityDetailAsUninstall() {
        val accessibilityDetail = "决定守护 无障碍服务 停用"

        assertTrue(
            AntiUninstallProtection.shouldSkipAccessibilitySettings(accessibilityDetail)
        )
    }
}
