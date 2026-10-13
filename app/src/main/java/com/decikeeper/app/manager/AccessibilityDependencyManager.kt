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
            if (GuardFloatingWindowManager.isFloatingGuardEnabled(context)) {
                GuardFloatingWindowManager.show(context, caller = caller, reason = "accessibility_reconnected")
            }
            return
        }

        // 无障碍不可用时仅挂起运行时展示，绝不破坏用户的持久化保护意图与配置
        Log.w(
            TAG,
            "[A11Y_DEP_RECONCILE] ${runIdTag}decision=SUSPEND_RUNTIME reason=SYSTEM_A11Y_UNAVAILABLE (persistent configs preserved)"
        )
        GuardFloatingWindowManager.hide(context, caller = caller, reason = "accessibility_unavailable")
    }
}
