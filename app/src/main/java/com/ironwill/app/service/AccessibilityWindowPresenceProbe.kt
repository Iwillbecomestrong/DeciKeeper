package com.ironwill.app.service

import android.app.KeyguardManager
import android.content.Context
import android.os.PowerManager
import android.util.Log
import com.ironwill.app.ui.LockWindowManager
import java.util.concurrent.ConcurrentHashMap

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
    private const val PRESENCE_DIAG_TAG = "PresenceDiag"

    // 记录上一次的观测状态（result + reason），发生变化时才输出日志，避免每 500ms 刷屏
    private val lastObservationStateByPkg = ConcurrentHashMap<String, String>()

    fun resetDiagnosticHistory() {
        lastObservationStateByPkg.clear()
    }

    fun observe(targetPackage: String): AccessibilityWindowPresenceObservation {
        val service = CoreService.instance
        if (service == null) {
            logObservationIfChanged(
                target = targetPackage,
                result = AccessibilityWindowPresenceObservation.UNKNOWN,
                reason = "SERVICE_MISSING",
                windowCount = 0,
                resolvedCount = 0,
                unresolvedCount = 0,
                resolvedPkgs = emptyList(),
                overlay = LockWindowManager.isLocked,
                interactive = false,
                keyguard = false
            )
            return AccessibilityWindowPresenceObservation.UNKNOWN
        }

        return try {
            val powerManager = service.getSystemService(Context.POWER_SERVICE) as? PowerManager
            val interactive = powerManager?.isInteractive ?: true
            if (!interactive) {
                logObservationIfChanged(
                    target = targetPackage,
                    result = AccessibilityWindowPresenceObservation.UNKNOWN,
                    reason = "SCREEN_OFF",
                    windowCount = 0,
                    resolvedCount = 0,
                    unresolvedCount = 0,
                    resolvedPkgs = emptyList(),
                    overlay = LockWindowManager.isLocked,
                    interactive = false,
                    keyguard = false
                )
                return AccessibilityWindowPresenceObservation.UNKNOWN
            }

            val keyguardManager = service.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
            val keyguardLocked = keyguardManager?.isKeyguardLocked ?: false
            if (keyguardLocked) {
                logObservationIfChanged(
                    target = targetPackage,
                    result = AccessibilityWindowPresenceObservation.UNKNOWN,
                    reason = "KEYGUARD",
                    windowCount = 0,
                    resolvedCount = 0,
                    unresolvedCount = 0,
                    resolvedPkgs = emptyList(),
                    overlay = LockWindowManager.isLocked,
                    interactive = interactive,
                    keyguard = true
                )
                return AccessibilityWindowPresenceObservation.UNKNOWN
            }

            val windows = service.windows
            if (windows == null) {
                logObservationIfChanged(
                    target = targetPackage,
                    result = AccessibilityWindowPresenceObservation.UNKNOWN,
                    reason = "WINDOWS_NULL",
                    windowCount = 0,
                    resolvedCount = 0,
                    unresolvedCount = 0,
                    resolvedPkgs = emptyList(),
                    overlay = LockWindowManager.isLocked,
                    interactive = interactive,
                    keyguard = false
                )
                return AccessibilityWindowPresenceObservation.UNKNOWN
            }

            val windowCount = windows.size
            var unresolvedWindowCount = 0
            var resolvedWindowCount = 0
            val resolvedPackages = mutableListOf<String>()
            var targetFound = false

            for (window in windows) {
                val root = window.root
                if (root == null) {
                    unresolvedWindowCount++
                    continue
                }

                val packageName = root.packageName?.toString()
                root.recycle()

                if (packageName != null) {
                    resolvedPackages.add(packageName)
                    resolvedWindowCount++
                    if (packageName == targetPackage) {
                        targetFound = true
                    }
                } else {
                    unresolvedWindowCount++
                }
            }

            val overlay = LockWindowManager.isLocked
            val (result, reason) = when {
                targetFound -> AccessibilityWindowPresenceObservation.PRESENT to "TARGET_FOUND"
                overlay -> AccessibilityWindowPresenceObservation.UNKNOWN to "OWN_LOCK_OVERLAY"
                unresolvedWindowCount > 0 -> AccessibilityWindowPresenceObservation.UNKNOWN to "UNRESOLVED_ROOT"
                else -> AccessibilityWindowPresenceObservation.ABSENT to "TARGET_NOT_FOUND"
            }

            logObservationIfChanged(
                target = targetPackage,
                result = result,
                reason = reason,
                windowCount = windowCount,
                resolvedCount = resolvedWindowCount,
                unresolvedCount = unresolvedWindowCount,
                resolvedPkgs = resolvedPackages,
                overlay = overlay,
                interactive = interactive,
                keyguard = keyguardLocked
            )

            result
        } catch (e: Exception) {
            logObservationIfChanged(
                target = targetPackage,
                result = AccessibilityWindowPresenceObservation.UNKNOWN,
                reason = "QUERY_EXCEPTION",
                windowCount = 0,
                resolvedCount = 0,
                unresolvedCount = 0,
                resolvedPkgs = emptyList(),
                overlay = LockWindowManager.isLocked,
                interactive = true,
                keyguard = false
            )
            AccessibilityWindowPresenceObservation.UNKNOWN
        }
    }

    private fun logObservationIfChanged(
        target: String,
        result: AccessibilityWindowPresenceObservation,
        reason: String,
        windowCount: Int,
        resolvedCount: Int,
        unresolvedCount: Int,
        resolvedPkgs: List<String>,
        overlay: Boolean,
        interactive: Boolean,
        keyguard: Boolean
    ) {
        val stateKey = "$result|$reason"
        val lastState = lastObservationStateByPkg[target]
        if (lastState != stateKey) {
            lastObservationStateByPkg[target] = stateKey
            val pkgsStr = resolvedPkgs.distinct().joinToString(separator = ",", prefix = "[", postfix = "]")
            Log.i(
                PRESENCE_DIAG_TAG,
                "[PRESENCE_OBS] target=$target result=$result reason=$reason windows=$windowCount resolved=$resolvedCount unresolved=$unresolvedCount pkgs=$pkgsStr overlay=$overlay interactive=$interactive keyguard=$keyguard"
            )
        }
    }
}
