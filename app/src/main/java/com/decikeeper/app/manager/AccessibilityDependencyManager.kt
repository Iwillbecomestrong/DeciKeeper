package com.decikeeper.app.manager

import android.content.Context
import android.util.Log
import com.decikeeper.app.service.CoreService

/**
 * Keeps enhancements that require the system AccessibilityService from
 * resurrecting after the user has disabled that system service.
 */
object AccessibilityDependencyManager {

    private const val TAG = "AccessibilityDependencyManager"

    fun reconcile(
        context: Context,
        systemAccessibilityEnabled: Boolean,
        caller: String = "unknown"
    ) {
        val now = System.currentTimeMillis()
        val serviceActive = CoreService.isServiceActive
        val recentSignal = CoreService.hasRecentAccessibilitySignal(now, 15_000L)
        val lostCounter = PermissionManager.getA11yLostConsecutiveCount()

        val runIdTag = ProtectionDiagnostics.currentTestRunId?.let { "runId=$it " } ?: ""
        Log.w(
            TAG,
            "[A11Y_DEP_RECONCILE] ${runIdTag}caller=$caller " +
                    "systemEnabled=$systemAccessibilityEnabled serviceActive=$serviceActive " +
                    "recentSignal=$recentSignal lostCounter=$lostCounter"
        )

        if (systemAccessibilityEnabled) {
            Log.i(TAG, "[A11Y_DEP_RECONCILE] ${runIdTag}decision=KEEP")
            return
        }

        // 如果 false：在执行任何清理之前先输出完整 BEFORE snapshot
        Log.w(
            TAG,
            "[A11Y_DEP_RECONCILE] ${runIdTag}decision=FORCE_DISABLE reason=SYSTEM_A11Y_UNAVAILABLE"
        )
        ProtectionDiagnostics.dumpSnapshot(
            context,
            trigger = "A11Y_DEP_RECONCILE_BEFORE(caller=$caller, lostCounter=$lostCounter)"
        )

        AccessibilityControlManager.disableImmediatelyForAccessibilityUnavailable(context, caller = "AccessibilityDependencyManager")
        AntiUninstallManager.disableImmediatelyForAccessibilityUnavailable(context, caller = "AccessibilityDependencyManager")
        PrivacyProtectionManager.disableImmediatelyForAccessibilityUnavailable(context, caller = "AccessibilityDependencyManager")
        FloatingWindowProtectionManager.disableImmediatelyForAccessibilityUnavailable(context, caller = "AccessibilityDependencyManager")
        GuardFloatingWindowManager.disableImmediatelyForAccessibilityUnavailable(context, caller = "AccessibilityDependencyManager")
        AlarmPowerOffProtectionManager.disableImmediatelyForAccessibilityUnavailable(context, caller = "AccessibilityDependencyManager")

        // 最后输出 AFTER snapshot
        ProtectionDiagnostics.dumpSnapshot(
            context,
            trigger = "A11Y_DEP_RECONCILE_AFTER(caller=$caller)"
        )
    }
}
