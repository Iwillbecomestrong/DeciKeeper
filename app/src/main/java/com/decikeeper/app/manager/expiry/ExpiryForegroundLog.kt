package com.decikeeper.app.manager.expiry

import android.util.Log
import com.decikeeper.app.data.LogExporter
import com.decikeeper.app.service.CoreService
import com.decikeeper.app.ui.LockWindowManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 到期决策现场的前台证据日志，仅在到期评估和桌面等待超时时各写一条，不属于逐 tick 诊断。
 * 纯日志，不参与任何放行或拦截决策；统一日志门面落地后迁入门面。
 */
internal object ExpiryForegroundLog {

    private const val TAG = "MonitorService"

    fun logRawAccessibilityEvidence(triggerReason: String) {
        val now = System.currentTimeMillis()
        val checkedAt = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(Date(now))
        val active = CoreService.isServiceActive
        val lastPkg = CoreService.diagnosticsLastEligibleEventPackage ?: "null"
        val lastType = CoreService.diagnosticsLastEligibleEventType ?: "null"
        val lastAgeMs = if (CoreService.diagnosticsLastEligibleEventAtMs > 0) now - CoreService.diagnosticsLastEligibleEventAtMs else -1L

        val snapshot = CoreService.captureWindowSnapshot(now)
        val visiblePkgs = snapshot.packages.ifEmpty { CoreService.lastVisiblePackagesFromWindows }

        val line = "[FG_RAW_A11Y] trigger=$triggerReason checkedAt=$checkedAt active=$active lastEligibleEventPkg=$lastPkg lastEligibleEventAgeMs=$lastAgeMs lastEligibleEventType=$lastType visiblePackages=[ ${visiblePkgs.joinToString(", ")} ]"
        Log.i(TAG, line)
        LogExporter.logEvent(TAG, line)
    }

    fun logExpiryForegroundSnapshot(reason: String, expiryContext: ExpiryContext) {
        val now = System.currentTimeMillis()
        val records = LockWindowManager.getRecentForegroundRecords()
        val recordsStr = records.reversed().joinToString(", ") {
            "pkg=${it.pkg}, ageMs=${now - it.timestamp}"
        }
        val snapshot = CoreService.captureWindowSnapshot(now)
        val a11yPkgs = snapshot.packages.ifEmpty { CoreService.lastVisiblePackagesFromWindows }

        val line = "[EXPIRY_FOREGROUND_SNAPSHOT] reason=$reason foregroundPackage=${expiryContext.foregroundPackage ?: "NONE"} recentForegroundRecords=[ $recordsStr ] visibleBlacklistPackages=[${expiryContext.visibleBlacklistPackages.joinToString(", ")}] visibleWhitelistPackages=[${expiryContext.visibleWhitelistPackages.joinToString(", ")}] a11yVisiblePackages=[${a11yPkgs.joinToString(", ")}]"
        Log.i(TAG, line)
        LogExporter.logEvent(TAG, line)
    }
}
