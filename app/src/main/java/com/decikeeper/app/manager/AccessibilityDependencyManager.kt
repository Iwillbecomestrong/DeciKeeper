package com.decikeeper.app.manager

import android.content.Context

/**
 * Keeps enhancements that require the system AccessibilityService from
 * resurrecting after the user has disabled that system service.
 */
object AccessibilityDependencyManager {

    fun reconcile(context: Context, systemAccessibilityEnabled: Boolean) {
        if (systemAccessibilityEnabled) return

        AccessibilityControlManager.disableImmediatelyForAccessibilityUnavailable(context)
        AntiUninstallManager.disableImmediatelyForAccessibilityUnavailable(context)
        PrivacyProtectionManager.disableImmediatelyForAccessibilityUnavailable(context)
        AlarmPowerOffProtectionManager.disableImmediatelyForAccessibilityUnavailable(context)
    }
}
