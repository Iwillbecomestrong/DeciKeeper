package com.zhizhi.app.service

import android.app.KeyguardManager
import android.content.Context
import android.os.PowerManager
import android.util.Log
import com.zhizhi.app.ui.LockWindowManager
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

    fun observe(
        targetPackage: String,
        snapshot: AccessibilityWindowSnapshot
    ): AccessibilityWindowPresenceObservation {
        val service = CoreService.instance
        val powerManager = service?.getSystemService(Context.POWER_SERVICE) as? PowerManager
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

        val keyguardManager = service?.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
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

        if (snapshot.status == AccessibilityWindowSnapshotStatus.FAILED) {
            logObservationIfChanged(
                target = targetPackage,
                result = AccessibilityWindowPresenceObservation.UNKNOWN,
                reason = snapshot.failureClass ?: "WINDOWS_QUERY_FAILED",
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

        val overlay = LockWindowManager.isLocked
        val resolvedPkgs = snapshot.packages.toList()
        val windowCount = snapshot.totalWindowCount
        val unresolvedWindowCount = snapshot.unresolvedWindowCount
        val resolvedWindowCount = windowCount - unresolvedWindowCount

        val (result, reason) = when {
            snapshot.packages.contains(targetPackage) -> {
                AccessibilityWindowPresenceObservation.PRESENT to "TARGET_FOUND"
            }
            overlay -> {
                AccessibilityWindowPresenceObservation.UNKNOWN to "OWN_LOCK_OVERLAY"
            }
            snapshot.status == AccessibilityWindowSnapshotStatus.PARTIAL -> {
                AccessibilityWindowPresenceObservation.UNKNOWN to "UNRESOLVED_ROOT"
            }
            else -> {
                AccessibilityWindowPresenceObservation.ABSENT to "TARGET_NOT_FOUND"
            }
        }

        logObservationIfChanged(
            target = targetPackage,
            result = result,
            reason = reason,
            windowCount = windowCount,
            resolvedCount = resolvedWindowCount,
            unresolvedCount = unresolvedWindowCount,
            resolvedPkgs = resolvedPkgs,
            overlay = overlay,
            interactive = interactive,
            keyguard = keyguardLocked
        )

        return result
    }

    fun observe(targetPackage: String): AccessibilityWindowPresenceObservation {
        val snapshot = CoreService.captureWindowSnapshot()
        return observe(targetPackage, snapshot)
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
