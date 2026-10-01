package com.decikeeper.app.service

import android.app.usage.UsageEvents
import android.app.usage.UsageStats
import android.os.Build
import android.util.Log
import com.decikeeper.app.data.LogExporter
import com.decikeeper.app.manager.CreditManager
import com.decikeeper.app.manager.expiry.ExpiryContext
import com.decikeeper.app.ui.LockWindowManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 前台判定专用诊断跟踪器 (ForegroundDiagnosticTracker)
 *
 * 诊断场景：Vivo 设备上停留 Claude 时 foreground=NONE 的根因排查。
 * 纯诊断代码，零业务影响，绝不作为任何放行或拦截决策依据。
 */
object ForegroundDiagnosticTracker {

    private const val TAG = "MonitorService"
    const val CLAUDE_PKG = "com.anthropic.claude"

    @Volatile
    var lastUsageStatsDiagSummary: UsageStatsDiagnosticSummary? = null

    private fun formatTime(timeMs: Long): String {
        if (timeMs <= 0L) return "NONE"
        return SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(Date(timeMs))
    }

    // ═══════════════════════════════════════════════════════════
    // Section III: RAW UsageEvents 诊断
    // ═══════════════════════════════════════════════════════════

    data class RawEventRecord(
        val timeStamp: Long,
        val ageMs: Long,
        val packageName: String,
        val className: String,
        val eventType: Int,
        val eventName: String
    )

    data class UsageEventsDiagnosticResult(
        val count: Int,
        val claudeLastForegroundAt: String?,
        val claudeLastForegroundAgeMs: Long,
        val claudeLastBackgroundAt: String?,
        val claudeLastBackgroundAgeMs: Long,
        val businessWindowContainsClaudeForeground: Boolean,
        val diagnosticWindowContainsClaudeForeground: Boolean,
        val diagnosticLastFgPkg: String?,
        val diagnosticLastFgAgeMs: Long,
        val eventsBusinessResult: String?
    )

    fun analyzeAndLogUsageEvents(
        checkedAtMs: Long,
        events180s: UsageEvents?,
        businessWindowMs: Long = 30000L,
        diagnosticWindowMs: Long = 180000L,
        eventsBusinessResult: String?,
        shouldLogRawEvents: Boolean
    ): UsageEventsDiagnosticResult {
        val checkedAtStr = formatTime(checkedAtMs)
        var claudeLastFgTs = 0L
        var claudeLastBgTs = 0L
        var businessContainsClaudeFg = false
        var diagContainsClaudeFg = false
        var lastFgTs = 0L
        var lastFgPkg: String? = null

        val eventList = mutableListOf<RawEventRecord>()

        if (events180s != null) {
            val event = UsageEvents.Event()
            while (events180s.hasNextEvent()) {
                events180s.getNextEvent(event)
                val type = event.eventType
                val pkg = event.packageName.orEmpty()
                val cls = event.className?.toString().orEmpty()
                val ageMs = checkedAtMs - event.timeStamp

                val isFg = type == 1 /* MOVE_TO_FG */ || (Build.VERSION.SDK_INT >= 29 && type == 15 /* ACTIVITY_RESUMED */)
                val isBg = type == 2 /* MOVE_TO_BG */ || (Build.VERSION.SDK_INT >= 29 && (type == 16 /* ACTIVITY_PAUSED */ || type == 23 /* ACTIVITY_STOPPED */))

                if (isFg || isBg) {
                    val eventName = when (type) {
                        1 -> "MOVE_TO_FOREGROUND"
                        2 -> "MOVE_TO_BACKGROUND"
                        15 -> "ACTIVITY_RESUMED"
                        16 -> "ACTIVITY_PAUSED"
                        23 -> "ACTIVITY_STOPPED"
                        else -> "EVENT_$type"
                    }
                    eventList.add(RawEventRecord(event.timeStamp, ageMs, pkg, cls, type, eventName))

                    if (isFg) {
                        if (event.timeStamp > lastFgTs) {
                            lastFgTs = event.timeStamp
                            lastFgPkg = pkg
                        }
                        if (pkg == CLAUDE_PKG) {
                            diagContainsClaudeFg = true
                            if (ageMs in 0L..businessWindowMs) {
                                businessContainsClaudeFg = true
                            }
                            if (event.timeStamp > claudeLastFgTs) {
                                claudeLastFgTs = event.timeStamp
                            }
                        }
                    } else if (isBg) {
                        if (pkg == CLAUDE_PKG) {
                            if (event.timeStamp > claudeLastBgTs) {
                                claudeLastBgTs = event.timeStamp
                            }
                        }
                    }
                }
            }
        }

        // 输出每一个 RAW EVENT
        if (shouldLogRawEvents) {
            for (rec in eventList) {
                val line = "[FG_RAW_EVENTS] checkedAt=$checkedAtStr businessWindowMs=$businessWindowMs diagnosticWindowMs=$diagnosticWindowMs count=${eventList.size} eventAt=${formatTime(rec.timeStamp)} ageMs=${rec.ageMs} pkg=${rec.packageName} type=${rec.eventName} class=${rec.className}"
                Log.d(TAG, line)
                LogExporter.logEvent(TAG, line)
            }
        }

        val claudeLastFgStr = if (claudeLastFgTs > 0) formatTime(claudeLastFgTs) else null
        val claudeLastFgAge = if (claudeLastFgTs > 0) checkedAtMs - claudeLastFgTs else -1L
        val claudeLastBgStr = if (claudeLastBgTs > 0) formatTime(claudeLastBgTs) else null
        val claudeLastBgAge = if (claudeLastBgTs > 0) checkedAtMs - claudeLastBgTs else -1L

        val summaryLine = "[FG_RAW_EVENTS_SUMMARY] claudeLastForegroundAt=${claudeLastFgStr ?: "NONE"} claudeLastForegroundAgeMs=$claudeLastFgAge claudeLastBackgroundAt=${claudeLastBgStr ?: "NONE"} claudeLastBackgroundAgeMs=$claudeLastBgAge businessWindowContainsClaudeForeground=$businessContainsClaudeFg diagnosticWindowContainsClaudeForeground=$diagContainsClaudeFg"
        Log.i(TAG, summaryLine)
        LogExporter.logEvent(TAG, summaryLine)

        return UsageEventsDiagnosticResult(
            count = eventList.size,
            claudeLastForegroundAt = claudeLastFgStr,
            claudeLastForegroundAgeMs = claudeLastFgAge,
            claudeLastBackgroundAt = claudeLastBgStr,
            claudeLastBackgroundAgeMs = claudeLastBgAge,
            businessWindowContainsClaudeForeground = businessContainsClaudeFg,
            diagnosticWindowContainsClaudeForeground = diagContainsClaudeFg,
            diagnosticLastFgPkg = lastFgPkg,
            diagnosticLastFgAgeMs = if (lastFgTs > 0) checkedAtMs - lastFgTs else -1L,
            eventsBusinessResult = eventsBusinessResult
        )
    }

    // ═══════════════════════════════════════════════════════════
    // Section IV: RAW UsageStats 诊断
    // ═══════════════════════════════════════════════════════════

    data class UsageStatsCandidateRecord(
        val pkg: String,
        val lastTimeUsed: Long,
        val lastTimeVisible: Long,
        val totalTimeInForeground: Long,
        val deltaUsedMs: Long,
        val deltaVisibleMs: Long,
        val recentBy30s: Boolean,
        val foregroundTimePositive: Boolean,
        val carrier: Boolean,
        val acceptedRecentActive: Boolean,
        val rejectReason: String
    )

    data class UsageStatsDiagnosticSummary(
        val rawCount: Int,
        val recentActiveCount: Int,
        val selectedPkg: String,
        val claudePresentInRaw: Boolean,
        val claudeAccepted: Boolean,
        val claudeRejectReason: String
    )

    data class UsageStatEntry(
        val packageName: String,
        val lastTimeUsed: Long,
        val lastTimeVisible: Long,
        val totalTimeInForeground: Long
    )

    fun analyzeAndLogUsageStats(
        now: Long,
        rawStats: List<UsageStats>?,
        recentActive: List<UsageStats>,
        selectedPkg: String?,
        carrierNoisePackages: Set<String>,
        isLauncher: (String) -> Boolean
    ): UsageStatsDiagnosticSummary {
        val rawEntries = rawStats?.map { stat ->
            val lastVis = if (Build.VERSION.SDK_INT >= 29) {
                try { stat.lastTimeVisible } catch (_: Exception) { 0L }
            } else 0L
            UsageStatEntry(
                packageName = stat.packageName.orEmpty(),
                lastTimeUsed = stat.lastTimeUsed,
                lastTimeVisible = lastVis,
                totalTimeInForeground = stat.totalTimeInForeground
            )
        }
        val recentEntries = recentActive.map { stat ->
            val lastVis = if (Build.VERSION.SDK_INT >= 29) {
                try { stat.lastTimeVisible } catch (_: Exception) { 0L }
            } else 0L
            UsageStatEntry(
                packageName = stat.packageName.orEmpty(),
                lastTimeUsed = stat.lastTimeUsed,
                lastTimeVisible = lastVis,
                totalTimeInForeground = stat.totalTimeInForeground
            )
        }
        return analyzeUsageStatsEntries(
            now = now,
            rawEntries = rawEntries,
            recentActiveEntries = recentEntries,
            selectedPkg = selectedPkg,
            carrierNoisePackages = carrierNoisePackages,
            isLauncher = isLauncher
        )
    }

    fun analyzeUsageStatsEntries(
        now: Long,
        rawEntries: List<UsageStatEntry>?,
        recentActiveEntries: List<UsageStatEntry>,
        selectedPkg: String?,
        carrierNoisePackages: Set<String>,
        isLauncher: (String) -> Boolean
    ): UsageStatsDiagnosticSummary {
        if (rawEntries.isNullOrEmpty()) {
            val emptyReason = if (rawEntries == null) "QUERY_EXCEPTION" else "QUERY_EMPTY"
            val summary = UsageStatsDiagnosticSummary(
                rawCount = 0,
                recentActiveCount = 0,
                selectedPkg = "NONE",
                claudePresentInRaw = false,
                claudeAccepted = false,
                claudeRejectReason = emptyReason
            )
            lastUsageStatsDiagSummary = summary
            val line = "[FG_RAW_STATS_SUMMARY] rawCount=0 recentActiveCount=0 selectedPkg=NONE claudePresentInRaw=false claudeAccepted=false claudeRejectReason=$emptyReason"
            Log.i(TAG, line)
            LogExporter.logEvent(TAG, line)
            return summary
        }

        var claudePresent = false
        var claudeAccepted = false
        var claudeRejectReason = "NOT_IN_RAW"

        val targetPackages = setOf(
            CLAUDE_PKG,
            "com.tencent.mm",
            "com.android.systemui",
            "com.vivo.upslide"
        )

        for (stat in rawEntries) {
            val pkg = stat.packageName
            val isTarget = pkg in targetPackages || (pkg.isNotEmpty() && isLauncher(pkg)) || stat.lastTimeUsed >= now - 60000 || stat.lastTimeVisible >= now - 60000

            val lastUsed = stat.lastTimeUsed
            val lastVis = stat.lastTimeVisible
            val deltaUsedMs = now - lastUsed
            val deltaVisMs = if (lastVis > 0) now - lastVis else -1L
            val totalFg = stat.totalTimeInForeground

            val hasVis = lastVis > 0
            val recentBy30s = if (hasVis) lastVis >= now - 30000 else lastUsed >= now - 30000
            val fgTimePos = totalFg > 0
            val isCarrier = pkg in carrierNoisePackages
            val isLnchr = isLauncher(pkg)

            val accepted = recentBy30s && fgTimePos

            val rejectReason = when {
                !fgTimePos -> "TOTAL_FOREGROUND_ZERO"
                hasVis && lastVis < now - 30000 -> "LAST_VISIBLE_STALE"
                !hasVis && lastUsed < now - 30000 -> "LAST_USED_STALE"
                isCarrier -> "CARRIER_NOISE"
                isLnchr -> "LAUNCHER"
                accepted -> "NONE"
                else -> "NO_LAST_VISIBLE_DATA"
            }

            if (pkg == CLAUDE_PKG) {
                claudePresent = true
                claudeAccepted = accepted && !isCarrier && !isLnchr
                claudeRejectReason = rejectReason
            }

            if (isTarget) {
                val statLine = "[FG_RAW_STATS] pkg=$pkg lastTimeUsed=${formatTime(lastUsed)} lastTimeVisible=${if (lastVis > 0) formatTime(lastVis) else "0"} totalTimeInForeground=$totalFg deltaUsedMs=$deltaUsedMs deltaVisibleMs=$deltaVisMs recentBy30s=$recentBy30s foregroundTimePositive=$fgTimePos carrier=$isCarrier acceptedRecentActive=$accepted rejectReason=$rejectReason"
                Log.d(TAG, statLine)
                LogExporter.logEvent(TAG, statLine)
            }
        }

        val summary = UsageStatsDiagnosticSummary(
            rawCount = rawEntries.size,
            recentActiveCount = recentActiveEntries.size,
            selectedPkg = selectedPkg ?: "NONE",
            claudePresentInRaw = claudePresent,
            claudeAccepted = claudeAccepted,
            claudeRejectReason = claudeRejectReason
        )
        lastUsageStatsDiagSummary = summary

        val summaryLine = "[FG_RAW_STATS_SUMMARY] rawCount=${summary.rawCount} recentActiveCount=${summary.recentActiveCount} selectedPkg=${summary.selectedPkg} claudePresentInRaw=${summary.claudePresentInRaw} claudeAccepted=${summary.claudeAccepted} claudeRejectReason=${summary.claudeRejectReason}"
        Log.i(TAG, summaryLine)
        LogExporter.logEvent(TAG, summaryLine)

        return summary
    }

    // ═══════════════════════════════════════════════════════════
    // Section V: Accessibility 原始证据
    // ═══════════════════════════════════════════════════════════

    fun logRawAccessibilityEvidence(triggerReason: String) {
        val now = System.currentTimeMillis()
        val checkedAt = formatTime(now)
        val active = CoreService.isServiceActive
        val lastPkg = CoreService.diagnosticsLastEligibleEventPackage ?: "null"
        val lastType = CoreService.diagnosticsLastEligibleEventType ?: "null"
        val lastAgeMs = if (CoreService.diagnosticsLastEligibleEventAtMs > 0) now - CoreService.diagnosticsLastEligibleEventAtMs else -1L

        val snapshot = CoreService.captureWindowSnapshot(now)
        val visiblePkgs = snapshot.packages.ifEmpty { CoreService.lastVisiblePackagesFromWindows }
        val claudeVisible = visiblePkgs.contains(CLAUDE_PKG)

        val line = "[FG_RAW_A11Y] trigger=$triggerReason checkedAt=$checkedAt active=$active lastEligibleEventPkg=$lastPkg lastEligibleEventAgeMs=$lastAgeMs lastEligibleEventType=$lastType visiblePackages=[ ${visiblePkgs.joinToString(", ")} ] claudeVisible=$claudeVisible"
        Log.i(TAG, line)
        LogExporter.logEvent(TAG, line)
    }

    // ═══════════════════════════════════════════════════════════
    // Section VI: 最终前台融合解释日志
    // ═══════════════════════════════════════════════════════════

    fun logResolveTrace(
        checkedAtMs: Long,
        result: String?,
        selectedSource: String,
        eventsBusinessResult: String?,
        eventsDiagnosticLastFgPkg: String?,
        eventsDiagnosticLastFgAgeMs: Long,
        usageStatsRawClaudePresent: Boolean,
        usageStatsClaudeAccepted: Boolean,
        usageStatsClaudeRejectReason: String,
        a11yClaudeVisible: Boolean,
        a11yLastEventPkg: String?,
        cachePkg: String?,
        cacheAgeMs: Long,
        finalReason: String
    ) {
        val line = "[FG_RESOLVE_TRACE] checkedAt=${formatTime(checkedAtMs)} result=${result ?: "NONE"} selectedSource=$selectedSource eventsBusinessResult=${eventsBusinessResult ?: "NONE"} eventsDiagnosticLastFgPkg=${eventsDiagnosticLastFgPkg ?: "NONE"} eventsDiagnosticLastFgAgeMs=$eventsDiagnosticLastFgAgeMs usageStatsRawClaudePresent=$usageStatsRawClaudePresent usageStatsClaudeAccepted=$usageStatsClaudeAccepted usageStatsClaudeRejectReason=$usageStatsClaudeRejectReason a11yClaudeVisible=$a11yClaudeVisible a11yLastEventPkg=${a11yLastEventPkg ?: "NONE"} cachePkg=${cachePkg ?: "NONE"} cacheAgeMs=$cacheAgeMs finalReason=$finalReason"
        Log.i(TAG, line)
        LogExporter.logEvent(TAG, line)
    }

    // ═══════════════════════════════════════════════════════════
    // Section VII: 到期链统一快照
    // ═══════════════════════════════════════════════════════════

    fun logExpiryForegroundSnapshot(
        reason: String,
        expiryContext: ExpiryContext
    ) {
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
