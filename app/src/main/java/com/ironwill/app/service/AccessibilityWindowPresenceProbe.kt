package com.ironwill.app.service

import android.app.KeyguardManager
import android.content.Context
import android.os.PowerManager
import com.ironwill.app.ui.LockWindowManager

/**
 * A point-in-time observation of whether one package still owns a visible Accessibility window.
 *
 * UNKNOWN is intentionally distinct from ABSENT: screen-off/keyguard/our own lock overlay,
 * partial window roots, missing service instances and query exceptions are all situations where
 * "not observed" is not strong enough evidence to say the mini-window was closed.
 */
internal enum class AccessibilityWindowPresenceObservation {
    PRESENT,
    ABSENT,
    UNKNOWN
}

internal object AccessibilityWindowPresenceProbe {

    fun observe(targetPackage: String): AccessibilityWindowPresenceObservation {
        val service = CoreService.instance ?: return AccessibilityWindowPresenceObservation.UNKNOWN

        return try {
            val powerManager = service.getSystemService(Context.POWER_SERVICE) as? PowerManager
            if (powerManager?.isInteractive == false) {
                return AccessibilityWindowPresenceObservation.UNKNOWN
            }

            val keyguardManager = service.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
            if (keyguardManager?.isKeyguardLocked == true) {
                return AccessibilityWindowPresenceObservation.UNKNOWN
            }

            val windows = service.windows ?: return AccessibilityWindowPresenceObservation.UNKNOWN
            var hasUnresolvedWindow = false

            for (window in windows) {
                val root = window.root
                if (root == null) {
                    hasUnresolvedWindow = true
                    continue
                }

                val packageName = root.packageName?.toString()
                root.recycle()

                if (packageName == targetPackage) {
                    return AccessibilityWindowPresenceObservation.PRESENT
                }
            }

            when {
                // Our own lock overlay can occlude the app underneath. Absence is not trustworthy here.
                LockWindowManager.isLocked -> AccessibilityWindowPresenceObservation.UNKNOWN
                // A partial tree cannot prove the target disappeared.
                hasUnresolvedWindow -> AccessibilityWindowPresenceObservation.UNKNOWN
                // Interactive + unlocked + complete successful window traversal with no target.
                else -> AccessibilityWindowPresenceObservation.ABSENT
            }
        } catch (_: Exception) {
            AccessibilityWindowPresenceObservation.UNKNOWN
        }
    }
}
