package com.decikeeper.app.protection

import com.decikeeper.app.manager.AccessibilityControlManager
import com.decikeeper.app.protection.AccessibilityProtection
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AccessibilityProtectionGateTest {

    @Test
    fun disabledBusinessProtectionMustNotInterceptAccessibilitySettings() {
        assertFalse(
            AccessibilityProtection.shouldEnforceProtection(
                AccessibilityControlManager.State.DISABLED
            )
        )
    }

    @Test
    fun activeAndPendingBusinessProtectionMustContinueProtecting() {
        assertTrue(
            AccessibilityProtection.shouldEnforceProtection(
                AccessibilityControlManager.State.ACTIVE
            )
        )
        assertTrue(
            AccessibilityProtection.shouldEnforceProtection(
                AccessibilityControlManager.State.PENDING
            )
        )
    }
}
