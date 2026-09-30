package com.decikeeper.app.manager.expiry

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.decikeeper.app.data.LogExporter
import com.decikeeper.app.data.SegmentBlacklist
import com.decikeeper.app.manager.CreditManager
import com.decikeeper.app.service.ForegroundDiagnosticTracker
import com.decikeeper.app.ui.LockWindowManager

/**
 * 到期状态协调器 (ExpiryCoordinator)
 *
 * 统一管理 Free Time、Temporary Access、Weekly Privilege 的到期评估流程与
 * Desktop Pending（桌面等待确认）状态机。
 */
object ExpiryCoordinator {

    private const val TAG = "ExpiryCoordinator"
    const val DESKTOP_CONFIRM_MS = 35_000L

    enum class State {
        NORMAL,
        DESKTOP_PENDING
    }

    @Volatile
    var currentState: State = State.NORMAL
        private set

    @Volatile
    private var pendingReason: ExpiryReason? = null

    @Volatile
    private var pendingStartedAtMs: Long = 0L

    private val mainHandler = Handler(Looper.getMainLooper())
    private var desktopConfirmRunnable: Runnable? = null

    /**
     * 到期事件主入口：由 Free Time、Temporary Access、Privilege 结束时统一调用。
     */
    @Synchronized
    fun evaluate(
        context: Context,
        reason: ExpiryReason,
        overrideForegroundPackage: String? = null
    ): ExpiryDecision {
        val expiryContext = VisibleContainerResolver.resolveContext(
            context = context,
            reason = reason,
            overrideForegroundPackage = overrideForegroundPackage
        )

        ForegroundDiagnosticTracker.logExpiryForegroundSnapshot(reason.name, expiryContext)

        val (decision, cause) = ExpiryResolver.resolve(expiryContext)
        ExpiryResolver.logResolution(expiryContext, decision, cause)

        when (decision) {
            ExpiryDecision.LOCK -> {
                cancelDesktopPending()
                executeLock(context, expiryContext, cause)
            }
            ExpiryDecision.UNLOCK -> {
                cancelDesktopPending()
                executeUnlock(context, expiryContext, cause)
            }
            ExpiryDecision.WAIT_DESKTOP_CONFIRM -> {
                ForegroundDiagnosticTracker.logRawAccessibilityEvidence("FREE_TIME_END_ENTER_DESKTOP_PENDING")
                startDesktopPending(context, reason)
            }
        }

        return decision
    }

    /**
     * 前台变化时被 MonitorService 每 500ms 持续调用。
     * 若当前处于 DESKTOP_PENDING 状态，监听黑白名单并即时判定。
     */
    @Synchronized
    fun onForegroundDetected(context: Context, detectedPkg: String) {
        if (currentState != State.DESKTOP_PENDING) return
        val currentReason = pendingReason ?: ExpiryReason.TEMP_ACCESS_END

        val expiryContext = VisibleContainerResolver.resolveContext(
            context = context,
            reason = currentReason,
            overrideForegroundPackage = detectedPkg
        )

        val (decision, cause) = ExpiryResolver.resolve(expiryContext)

        when (decision) {
            ExpiryDecision.LOCK -> {
                Log.i(TAG, "[DESKTOP_PENDING] Blacklist appeared -> Immediate LOCK: cause=$cause")
                cancelDesktopPending()
                ExpiryResolver.logResolution(expiryContext, decision, "PENDING_TRANSITION: $cause")
                executeLock(context, expiryContext, cause)
            }
            ExpiryDecision.UNLOCK -> {
                Log.i(TAG, "[DESKTOP_PENDING] Whitelist appeared -> Immediate UNLOCK: cause=$cause")
                cancelDesktopPending()
                ExpiryResolver.logResolution(expiryContext, decision, "PENDING_TRANSITION: $cause")
                executeUnlock(context, expiryContext, cause)
            }
            ExpiryDecision.WAIT_DESKTOP_CONFIRM -> {
                // 持续停留在桌面 / unknown，继续等待 35s 倒计时
            }
        }
    }

    private fun startDesktopPending(context: Context, reason: ExpiryReason) {
        cancelDesktopPending()
        currentState = State.DESKTOP_PENDING
        pendingReason = reason
        pendingStartedAtMs = System.currentTimeMillis()

        LogExporter.i(TAG, "⏱ 启动桌面等待状态机 | reason=$reason | timeout=${DESKTOP_CONFIRM_MS}ms")

        desktopConfirmRunnable = Runnable {
            handleDesktopPendingTimeout(context)
        }
        mainHandler.postDelayed(desktopConfirmRunnable!!, DESKTOP_CONFIRM_MS)
    }

    @Synchronized
    private fun handleDesktopPendingTimeout(context: Context) {
        if (currentState != State.DESKTOP_PENDING) return
        val reason = pendingReason ?: ExpiryReason.TEMP_ACCESS_END
        cancelDesktopPending()

        // 达到 35s，执行 History fallback 最终裁决
        val expiryContext = VisibleContainerResolver.resolveContext(context, reason)
        ForegroundDiagnosticTracker.logRawAccessibilityEvidence("DESKTOP_PENDING_TIMEOUT")
        ForegroundDiagnosticTracker.logExpiryForegroundSnapshot("DESKTOP_PENDING_TIMEOUT", expiryContext)

        val (decision, cause) = ExpiryResolver.resolveHistoryFallback(
            recentForegroundPackages = expiryContext.recentForegroundPackages,
            isBlacklist = { pkg -> SegmentBlacklist.shouldLock(context, pkg) },
            isLauncher = { pkg -> CreditManager.isLauncher(pkg) }
        )

        ExpiryResolver.logResolution(expiryContext, decision, "TIMEOUT_FALLBACK: $cause")

        when (decision) {
            ExpiryDecision.LOCK -> {
                executeLock(context, expiryContext, cause)
            }
            ExpiryDecision.UNLOCK,
            ExpiryDecision.WAIT_DESKTOP_CONFIRM -> {
                executeUnlock(context, expiryContext, cause)
            }
        }
    }

    private fun executeLock(
        context: Context,
        expiryContext: ExpiryContext,
        cause: String
    ) {
        val lockPkg = expiryContext.visibleBlacklistPackages.firstOrNull()
            ?: expiryContext.foregroundPackage
            ?: LockWindowManager.getLastKnownForegroundPkg()

        val label = lockPkg?.let { resolveAppLabel(context, it) }
        val reason = "到期拦截 (${expiryContext.reason})"

        LockWindowManager.showLockScreen(
            reason = reason,
            isAntiUninstall = false,
            isBlacklist = true,
            blockedAppLabel = label,
            blockedPackageName = lockPkg
        )
    }

    private fun executeUnlock(
        context: Context,
        expiryContext: ExpiryContext,
        cause: String
    ) {
        LogExporter.i(TAG, "🟢 到期放行: reason=${expiryContext.reason}, cause=$cause")
    }

    @Synchronized
    fun cancelDesktopPending() {
        desktopConfirmRunnable?.let { mainHandler.removeCallbacks(it) }
        desktopConfirmRunnable = null
        currentState = State.NORMAL
        pendingReason = null
        pendingStartedAtMs = 0L
    }

    private fun resolveAppLabel(context: Context, packageName: String): String {
        return try {
            val pm = context.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
        } catch (_: Exception) {
            packageName
        }
    }
}
