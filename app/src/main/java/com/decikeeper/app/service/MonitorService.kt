/**
 * =================================================================================
 * [Context Header]
 * * @file: MonitorService.kt
 * * @role: Domain Skill
 * * @description: 前台监控服务，负责前台应用检测与拦截触发。
 * * @responsibilities:
 * 1. 轮询前台应用并执行黑名单/时间窗判定。
 * 2. 触发锁屏与规则刷新、待生效应用。
 * * @context:
 * - 上游: MainActivity, 系统前台服务机制
 * - 下游: LockWindowManager, CreditManager, AppWindowManager, TimeRuleManager
 * * @constraints (必读):
 * 1. 轮询必须在后台线程运行，避免阻塞主线程。
 * 2. 判定需遵循双锁优先级与特权绕过规则。
 * 3. 必须处理无前台包名时的安全返回。
 * =================================================================================
 */
package com.decikeeper.app.service

import android.annotation.SuppressLint
import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.PowerManager
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.decikeeper.app.R
import com.decikeeper.app.data.AccessibilityForegroundSnapshot
import com.decikeeper.app.data.AndroidLogcatNormalEventSink
import com.decikeeper.app.data.DefaultLogDebugConfig
import com.decikeeper.app.data.DefaultNormalEventRenderer
import com.decikeeper.app.data.EventLog
import com.decikeeper.app.data.ForegroundBusinessAction
import com.decikeeper.app.data.ForegroundCandidate
import com.decikeeper.app.data.ForegroundDetectionLogCompressor
import com.decikeeper.app.data.ForegroundDiagnosticCycle
import com.decikeeper.app.data.ForegroundFaultSnapshotFactory
import com.decikeeper.app.data.ForegroundReportEmission
import com.decikeeper.app.data.ForegroundReportGate
import com.decikeeper.app.data.ForegroundReportOutcome
import com.decikeeper.app.data.ForegroundSourceSnapshot
import com.decikeeper.app.data.ForegroundSourceStatus
import com.decikeeper.app.data.ForegroundVote
import com.decikeeper.app.data.LogExporter
import com.decikeeper.app.data.NormalEventLogger
import com.decikeeper.app.data.SegmentBlacklist
import com.decikeeper.app.data.SystemEventClock
import com.decikeeper.app.data.TimeSegment
import com.decikeeper.app.manager.expiry.ExpiryCoordinator
import com.decikeeper.app.manager.expiry.ExpiryReason
import com.decikeeper.app.manager.statistics.QuotaGrantRecorder
import com.decikeeper.app.manager.statistics.RestStatsRecorder
import com.decikeeper.app.data.resolveAccessibilityFailureClass
import com.decikeeper.app.manager.AppWindowManager
import com.decikeeper.app.manager.CreditManager
import com.decikeeper.app.manager.DailyBaselineManager
import com.decikeeper.app.manager.ForegroundEvidence
import com.decikeeper.app.manager.ForegroundEvidenceDecision
import com.decikeeper.app.manager.ForegroundEvidencePolicy
import com.decikeeper.app.manager.LockDecisionPolicy
import com.decikeeper.app.manager.PackageForegroundEvents
import com.decikeeper.app.manager.PermissionManager
import com.decikeeper.app.manager.PwaBypassManager
import com.decikeeper.app.manager.StartupProtectionManager
import com.decikeeper.app.manager.TimeRuleManager
import com.decikeeper.app.manager.resolveAutomaticRestrictionRelease
import com.decikeeper.app.ui.LockWindowManager
import com.decikeeper.app.ui.MainActivity
import com.decikeeper.app.ui.MainActivityEntry
import com.decikeeper.app.util.AppStateStableKey
import com.decikeeper.app.util.DiagnosticCompressor
import com.decikeeper.app.util.DiagnosticTimestampFormatter
import com.decikeeper.app.util.FgBypassStableKey
import com.decikeeper.app.util.FgGuardStableKey
import com.decikeeper.app.logging.IntervalSampler
import com.decikeeper.app.logging.LastForegroundEvidence
import com.decikeeper.app.logging.RecentSampleLog
import com.decikeeper.app.util.ForegroundDecisionRetention
import com.decikeeper.app.util.ForegroundNormalEventBridge
import com.decikeeper.app.util.ForegroundNormalEventStateTracker
import com.decikeeper.app.util.ForegroundReportFormatter
import com.decikeeper.app.util.ForegroundTransition
import com.decikeeper.app.util.ForegroundUsageStatsSnapshotTracker
import com.decikeeper.app.util.ShouldBlockStableKey
import com.decikeeper.app.util.VisCheckStableKey















































@SuppressLint("NotificationPermission")
class MonitorService : Service() {

    private val TAG = "MonitorService"
    private val usageCarrierNoisePackages = setOf(
        "com.vivo.upslide",
        "com.bbk.launcher2",
        "com.vivo.launcher",
        "com.android.systemui",
        "com.vivo.card" // 🚀 [vivo] OriginOS 超级卡包 / 快捷卡片
    )
    
    companion object {
    private var wasPrivilegeActiveLastPoll = false
        const val ACTION_END_FREE_TIME = "com.decikeeper.app.ACTION_END_FREE_TIME"
        const val ACTION_REFRESH_RULES = "com.decikeeper.app.ACTION_REFRESH_RULES"
        private const val NOTIFICATION_ID_WARNING = 2002
        private const val HOME_PENDING_USAGE_STATS_RELEASE_DELAY_MS = 5000L
        private const val USAGE_STATS_FOREGROUND_EVIDENCE_WINDOW_MS = 5000L

        internal fun reconcileStaleLockedPackage(
            currentLockedPkg: String?,
            isLockShowing: Boolean
        ): String? {
            return if (!isLockShowing) null else currentLockedPkg
        }

        internal fun isOrphanInputPanelOpen(
            isLockShowing: Boolean,
            isInputPanelOpen: Boolean
        ): Boolean {
            return !isLockShowing && isInputPanelOpen
        }

        internal fun shouldReleaseHomePendingForSamePackage(
            evidenceSource: String,
            pendingAgeMs: Long,
            hasForegroundEventAfterPending: Boolean
        ): Boolean {
            if (pendingAgeMs < 0L) return false
            return when (evidenceSource) {
                "EVENTS" -> hasForegroundEventAfterPending
                "USAGE_STATS" -> pendingAgeMs >= HOME_PENDING_USAGE_STATS_RELEASE_DELAY_MS
                else -> false
            }
        }

        internal fun shouldAllowUsageStatsBasedLock(
            hasRecentForegroundEvidence: Boolean,
            usageStatDeltaMs: Long,
            resolvedDeltaMs: Long
        ): Boolean {
            if (!hasRecentForegroundEvidence) return false
            if (usageStatDeltaMs < 0L || resolvedDeltaMs < 0L) return false
            return usageStatDeltaMs <= 10_000L && resolvedDeltaMs <= 12_000L
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // Presence-Triggered Locking (基于 lastTimeVisible 的前后台解耦检测)
    // 核心原理：不问"系统说谁在前台"，只问"黑名单应用的窗口在屏幕上吗"
    // 信号源：UsageStatsManager.queryUsageStats().lastTimeVisible
    // 不依赖无障碍、不依赖 MOVE_TO_FOREGROUND、不依赖前台状态机
    // ═══════════════════════════════════════════════════════════════

    // --- Presence State ---
    private var mPresentPkg: String? = null
    private var mPresentConfirmCount: Int = 0
    private var mPresentFirstSeenMs: Long = 0L
    private var mPresentLockedPkg: String? = null
    private var mPresentLockedAtMs: Long = 0L
    private var mRequireFastRecheck = false
    // 🚀 [回到桌面宽限] 上一轮 effective 是否桌面（用于检测"非桌面→桌面"过渡）
    private var lastEffectiveWasLauncher = false
    private var mPresenceStaleConfirmCount = 0
    private val PRESENCE_VISIBLE_WINDOW_MS = 2000L
    private val PRESENCE_CONFIRM_CYCLES = 2
    private val PRESENCE_STALE_UNLOCK_MS = 30000L
    // --- Presence Stats ---
    private var mPresenceTickCount = 0
    private var mPresenceStatScans = 0
    private var mPresenceStatConfirmed = 0
    private var mPresenceStatLocked = 0
    private var mPresenceStatUnlocked = 0
    private var mPresenceStatRejectedStale = 0
    private val PRESENCE_STAT_INTERVAL = 20

    private enum class TopPackageEvidence {
        EVENTS,
        USAGE_STATS,
        CACHE,
        HISTORY,
        NONE
    }

    private var isRunning = false
    private var monitorThread: android.os.HandlerThread? = null
    private var backgroundHandler: Handler? = null
    private var usageStatsManager: UsageStatsManager? = null
    // 🚀 [Cache] Cache the last detected top package
    private var mLastTopPackage: String? = null
    private var mLastTopEvidence: TopPackageEvidence = TopPackageEvidence.NONE
    private var mLastForegroundEventTs: Long = 0L
    private var mLastBackgroundEventTs: Long = 0L
    private var mLastTopUsageStatTs: Long = 0L
    private var mLastTopResolvedAt: Long = 0L
    private var mLastHistoryQueryTime: Long = 0L  // [防御性] 12小时历史查询冷却，避免每1000ms查询一次
    // 🚀 [Optimization] Cache the currently locked package to avoid redundant checks
    private var currentLockedPkg: String? = null

    // Polling Interval
    private val INTERVAL = 500L
    private val EXTENDED_INTERVAL_BASE = 5000L // Base extension: 5 seconds
    private val CARRIER_FALLBACK_FRESH_MS = 4000L
    
    // 🛡️ [Anti-Flicker] Counter for safe apps (Launcher/Settings)
    // We only dismiss lock if we see a safe app for N consecutive times
    private var safeAppCounter = 0
    private val SAFE_APP_THRESHOLD = 3 // ~3 seconds at 1000ms interval
    private val monitorScheduleGate = MonitorLoopScheduleGate()
    private val foregroundDetectionLogCompressor = ForegroundDetectionLogCompressor()
    private val foregroundGuardFallbackGate = ForegroundGuardFallbackGate()
    internal val foregroundSessionTracker = ForegroundSessionTracker { pkg ->
        ForegroundSessionTracker.defaultIsNoisePackage(pkg) || runCatching { CreditManager.isInputMethod(this@MonitorService, pkg) }.getOrDefault(false)
    }.apply {
        onSessionClosed = { pkg ->
            mLastBusinessBlockStateByPackage.remove(pkg)
            foregroundGuardFallbackGate.reset()
        }
    }
    private val mPreviouslyBlockedPackages = mutableSetOf<String>()
    private val mLastBusinessBlockStateByPackage = java.util.concurrent.ConcurrentHashMap<String, BusinessBlockState>()
    private var mForceRuleBoundaryCheck = false

    // 诊断日志压缩器（去重高频 DIAG）
    private val visCheckCompressor = DiagnosticCompressor<VisCheckStableKey>("VIS_CHECK_DIAG")
    private val fgBypassCompressor = DiagnosticCompressor<FgBypassStableKey>("FG_BYPASS_DIAG")
    private val fgGuardCompressor = DiagnosticCompressor<FgGuardStableKey>("FG_GUARD_DIAG")
    private val shouldBlockCompressor = DiagnosticCompressor<ShouldBlockStableKey>("SHOULD_BLOCK_DIAG")
    private val appStateCompressor = DiagnosticCompressor<AppStateStableKey>("APP_STATE_DIAG", summaryIntervalMs = Long.MAX_VALUE)

    // 追踪上次 VIS / FG_GUARD / SHOULD_BLOCK 结果，用于判断是否输出 [EVENT]
    private var lastFgGuardDecision: Boolean? = null
    private var lastFgGuardPkg: String? = null

    // 当前轮次的 traceId，在 checkForegroundApp() 入口预分配
    private var currentTraceId: String = ""

    // [Diagnostics-only] 当前轮次的前台汇报 cycle 与编号
    @Volatile private var currentForegroundCycle: ForegroundDiagnosticCycle? = null
    private var foregroundReportSeq: Long = 0L

    // [Diagnostics-only] 单例 gate：跨轮持久化 conflictSignature / 30s 续报 / 争议恢复状态。
    // 必须保持单例，否则每次发日志前 new 都会重置 lastConflictEmittedAtMs，争议去重失效。
    private val foregroundReportGate: ForegroundReportGate = ForegroundReportGate()

    // The normal foreground record is emitted only after the existing gate and business outcome
    // are known. The logger does not take part in source selection, lock policy, or gating.
    private val foregroundNormalEventLogger by lazy {
        NormalEventLogger(
            renderer = DefaultNormalEventRenderer,
            sinks = listOf(LogExporter.normalEventSink(), AndroidLogcatNormalEventSink())
        )
    }
    private val foregroundNormalEventBridge by lazy {
        ForegroundNormalEventBridge(SystemEventClock, foregroundNormalEventLogger::emit)
    }
    private val foregroundDecisionRetention = ForegroundDecisionRetention()
    // 前台采样：5 秒一条，只保留最近 3 分钟，仅供事后取证，业务代码不读取。
    private val foregroundSampler = IntervalSampler(RecentSampleLog.SAMPLE_INTERVAL_MS)
    private val foregroundNormalEventStateTracker = ForegroundNormalEventStateTracker()

    // [Diagnostics-only] real query wall-clock timestamps captured around the
    // UsageStats / UsageEvents system calls so the cycle snapshots show real
    // queryStartedAtMs / queryFinishedAtMs rather than fabricated window ranges.
    @Volatile private var mLastEventsQueryStartedAtMs: Long = 0L
    @Volatile private var mLastEventsQueryFinishedAtMs: Long = 0L
    @Volatile private var mLastStatsQueryStartedAtMs: Long = 0L
    @Volatile private var mLastStatsQueryFinishedAtMs: Long = 0L
    // Accessibility is event-driven rather than a query result, so its previous record is the
    // last actual window snapshot observed by this service, never a guessed candidate list.
    private var lastAccessibilitySnapshot: AccessibilityForegroundSnapshot? = null
    private val usageStatsEvidenceTracker = ForegroundUsageStatsSnapshotTracker()

    // [Diagnostics-only] 在真实采样路径上记录三源快照，由 getTopPackageName /
    // getTopPackageFromUsageStats / isAppActuallyVisible 各自在更新 mLastTopEvidence
    // 的同时调用，绝不在发日志前临时覆盖一次。

    private fun pushEventsSnapshotToCycle(
        packageName: String?,
        eventTimeMs: Long,
        candidates: List<ForegroundCandidate>,
        selectionReason: String?,
        status: ForegroundSourceStatus = if (packageName != null) ForegroundSourceStatus.AVAILABLE else ForegroundSourceStatus.UNAVAILABLE,
        failureClass: String? = null,
        queryStartedAtMs: Long? = null,
        queryFinishedAtMs: Long? = null
    ) {
        val c = currentForegroundCycle ?: return
        val now = System.currentTimeMillis()
        val vote = when {
            packageName == null -> ForegroundVote.ABSTAIN
            CreditManager.shouldBlockApp(this, packageName, "") -> ForegroundVote.BLACKLIST
            else -> ForegroundVote.NON_BLACKLIST
        }
        val qStart = queryStartedAtMs ?: mLastEventsQueryStartedAtMs.takeIf { it > 0L }
        val qEnd = queryFinishedAtMs ?: mLastEventsQueryFinishedAtMs.takeIf { it > 0L } ?: now
        c.updateUsageEvents(
            ForegroundSourceSnapshot(
                source = "USAGE_EVENTS",
                status = status,
                packageName = packageName,
                vote = vote,
                capturedAtMs = now,
                queryStartedAtMs = qStart,
                queryFinishedAtMs = qEnd,
                candidates = candidates,
                selectionReason = selectionReason,
                unavailableReason = if (status == ForegroundSourceStatus.UNAVAILABLE && failureClass == null) "NO_RECENT_FOREGROUND_EVENT" else null,
                failureClass = failureClass,
                previousEvidence = candidates.dropLast(1).lastOrNull()?.let(::renderSourceCandidate) ?: "NONE",
                currentEvidence = candidates.lastOrNull()?.let(::renderSourceCandidate) ?: packageName ?: "NONE"
            )
        )
    }

    private fun renderSourceCandidate(candidate: ForegroundCandidate): String = buildString {
        append(DiagnosticTimestampFormatter.msWithRaw(candidate.eventTimeMs)).append(' ').append(candidate.eventName).append(' ').append(candidate.packageName)
        candidate.lastTimeVisibleMs?.let { append(" lastTimeVisible=").append(it) }
        candidate.lastTimeUsedMs?.let { append(" lastTimeUsed=").append(it) }
    }

    private fun pushUsageStatsSnapshotToCycle(
        packageName: String?,
        eventTimeMs: Long,
        candidates: List<ForegroundCandidate>,
        selectionReason: String?,
        status: ForegroundSourceStatus = if (packageName != null) ForegroundSourceStatus.AVAILABLE else ForegroundSourceStatus.UNAVAILABLE,
        failureClass: String? = null,
        queryStartedAtMs: Long? = null,
        queryFinishedAtMs: Long? = null
    ) {
        val c = currentForegroundCycle ?: return
        val now = System.currentTimeMillis()
        val vote = when {
            packageName == null -> ForegroundVote.ABSTAIN
            CreditManager.shouldBlockApp(this, packageName, "") -> ForegroundVote.BLACKLIST
            else -> ForegroundVote.NON_BLACKLIST
        }
        val qStart = queryStartedAtMs ?: mLastStatsQueryStartedAtMs.takeIf { it > 0L }
        val qEnd = queryFinishedAtMs ?: mLastStatsQueryFinishedAtMs.takeIf { it > 0L } ?: now
        // Only the actual selected snapshot participates in UsageStats prev/curr. Other rows
        // from this query are candidates, not a previous foreground selection.
        val selectedEvidence = usageStatsEvidenceTracker.recordSelectedFromCandidates(packageName, candidates)
        c.updateUsageStats(
            ForegroundSourceSnapshot(
                source = "USAGE_STATS",
                status = status,
                packageName = packageName,
                vote = vote,
                capturedAtMs = now,
                queryStartedAtMs = qStart,
                queryFinishedAtMs = qEnd,
                candidates = candidates,
                selectionReason = selectionReason,
                unavailableReason = if (status == ForegroundSourceStatus.UNAVAILABLE && failureClass == null) "USAGE_STATS_MANAGER_NULL_OR_EMPTY" else null,
                failureClass = failureClass,
                previousEvidence = selectedEvidence.previous,
                currentEvidence = selectedEvidence.current
            )
        )
    }

    private fun pushAccessibilitySnapshotToCycle(
        packageName: String?,
        candidates: List<ForegroundCandidate>,
        reason: String,
        status: ForegroundSourceStatus
    ) {
        val c = currentForegroundCycle ?: return
        val now = System.currentTimeMillis()
        val vote = when {
            packageName == null -> ForegroundVote.ABSTAIN
            CreditManager.shouldBlockApp(this, packageName, "") -> ForegroundVote.BLACKLIST
            else -> ForegroundVote.NON_BLACKLIST
        }
        val currentSnapshot = AccessibilityForegroundSnapshot(
            eventAtMs = CoreService.diagnosticsLastEligibleEventAtMs.takeIf { it > 0L },
            handledAtMs = now,
            eventType = CoreService.diagnosticsLastEligibleEventType,
            eventPackageName = CoreService.diagnosticsLastEligibleEventPackage,
            snapshotAtMs = CoreService.diagnosticsWindowSnapshotAtMs.takeIf { it > 0L },
            windowSummary = "windows=" + if (candidates.size <= 1) "一致" else candidates.size
        )
        c.updateAccessibility(
            ForegroundSourceSnapshot(
                source = "ACCESSIBILITY",
                status = status,
                packageName = packageName,
                vote = vote,
                capturedAtMs = now,
                queryStartedAtMs = null,
                queryFinishedAtMs = null,
                candidates = candidates,
                selectionReason = reason,
                unavailableReason = if (status == ForegroundSourceStatus.UNAVAILABLE) "A11Y_INACTIVE" else null,
                failureClass = if (status == ForegroundSourceStatus.FAILED) "A11Y_QUERY_FAILED" else null,
                previousAccessibilitySnapshot = lastAccessibilitySnapshot,
                currentAccessibilitySnapshot = currentSnapshot
            )
        )
        lastAccessibilitySnapshot = currentSnapshot
    }

    // 追踪最后一次进入前台的黑名单应用，用于 NAVIGATION 去重
    private var mLastBlacklistedForegroundPkg: String? = null

    // 🩺 [健康心跳] 计数器
    private var mHealthTickCount = 0
    private val HEALTH_LOG_INTERVAL = 300 // 每 300 次检测（~5 分钟）打一次心跳

    /**
     * 获取当前检测快照，用于锁定日志（向后兼容，保留简短格式）
     * 返回格式: "EVENTS:com.bbk.launcher2@15:33:13 | USAGE_STATS:com.ss.android.ugc.aweme@15:33:10"
     */
    fun getDetectionSnapshot(): String {
        val now = System.currentTimeMillis()
        val sdf = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.getDefault())

        fun formatLocal(ts: Long): String =
            if (ts > 0L) sdf.format(java.util.Date(ts)) else "N/A"

        fun formatCandidate(pkg: String?, ts: Long, extra: String = ""): String {
            val packagePart = pkg ?: "UNAVAILABLE"
            val base = "$packagePart@${formatLocal(ts)}"
            return if (extra.isBlank()) base else "$base($extra)"
        }

        // 🛡️ [P0 Fix] Cache 不再作为前台候选展示，仅作为内部状态追踪
        val selectedPkg = mLastTopPackage
        val selectedSource = mLastTopEvidence.name
        val selectedTs = mLastTopResolvedAt

        val usagePkg = try { getTopPackageFromUsageStats(now) } catch (e: Exception) { null }
        val usageTs = mLastTopUsageStatTs
        val usageSecond = usagePkg?.let { getSecondDistinctCandidate(it) }

        val eventsPkg = if (mLastTopEvidence == TopPackageEvidence.EVENTS) mLastTopPackage else null
        val eventsTs = if (mLastTopEvidence == TopPackageEvidence.EVENTS) mLastTopResolvedAt else 0L
        val eventsSecond = eventsPkg?.let { getSecondDistinctCandidate(it) }

        return buildString {
            append("selected=")
            append(selectedSource)
            append(":")
            append(formatCandidate(selectedPkg, selectedTs))
            append(" | EVENTS[#1=")
            append(formatCandidate(eventsPkg, eventsTs))
            append(",#2=")
            append(eventsSecond ?: "UNAVAILABLE")
            append("] | USAGE_STATS[#1=")
            append(formatCandidate(usagePkg, usageTs, "lastTimeUsed"))
            append(",#2=")
            append(usageSecond ?: "UNAVAILABLE")
            append("]")
        }
    }

    // ── 前台识别证据链（仅日志，不改业务规则） ──

    /** 判断事件序列号，每次锁屏递增 */
    private var lockDecisionSeq = 0L

    /**
     * 获取最近60秒内存缓冲中与 [currentPkg] 不同的第2个候选应用。
     * 通过 LockWindowManager.recentForegroundLog 环形缓冲区获取。
     */
    private fun getSecondDistinctCandidate(currentPkg: String): String? {
        return LockWindowManager.getSecondDistinctCandidatePkg(currentPkg)
    }

    private val monitorRunnable = object : Runnable {
        override fun run() {
            monitorScheduleGate.onRunnableDispatched()
            if (!isRunning) return

            var nextInterval = INTERVAL
            try {
                // 🩺 [健康心跳] 独立于检测循环，每 ~30 秒打一次
                mHealthTickCount++
                if (mHealthTickCount >= HEALTH_LOG_INTERVAL) {
                    mHealthTickCount = 0
                    val a11yAvailable = CoreService.isServiceActive
                    val mode = if (a11yAvailable) "FULL" else "DEGRADED_WITHOUT_ACCESSIBILITY"
                    // 同一次快照采样，避免心跳与分歧检测使用不同时间点的状态
                    val snapshotLockedPkg = currentLockedPkg
                    val snapshotIsLocked = LockWindowManager.isLocked
                    val isFresh = snapshotIsLocked == (snapshotLockedPkg != null && snapshotLockedPkg != "none")
                    LogExporter.logEvent(
                        "MonitorService",
                        "[健康心跳] isRunning=$isRunning, mode=$mode, a11y=$a11yAvailable, " +
                        "lastPkg=${mLastTopPackage ?: "null"}, " +
                        "lastMethod=${mLastTopEvidence.name}, lastResolvedAt=${mLastTopResolvedAt}, " +
                        "lockedPkg=${snapshotLockedPkg ?: "none"}, handlerAlive=${backgroundHandler?.looper?.isCurrentThread ?: false}, " +
                        "isLocked=$snapshotIsLocked isFresh=$isFresh"
                    )
                    if (!isFresh) {
                        Log.w(TAG, "[HEARTBEAT_DIVERGENT] currentLockedPkg=${snapshotLockedPkg ?: "none"} isLocked=$snapshotIsLocked")
                    }
                }

                // 💎 Weekly Privilege: auto-finalize on expiry or 24:00 cutoff
                // Ensures usage accounting is correct and unused minutes are effectively returned.
                CreditManager.finalizeWeeklyPrivilegeIfExpiredOrCrossedMidnight(this@MonitorService)

                // 🚀 [FIXED] Concurrent Monitoring
                // Previously, we disabled polling if CoreService (Accessibility) was active.
                // This created a "Blind Spot" for apps like TikTok that don't emit A11y events
                // while a video is playing passively.
                // Now, we ALWAYS poll as a safety net, but we respect the LockWindow's state.
                checkForegroundApp()

                // 🚀 [Feature] Time-based Warnings (Pre-Segment)
                // One check per tick to avoid duplicate warnings/logs.
                checkTimeWarnings()

                // 🚀 [Fix] Check and Apply Pending Rules (Daily Refresh)
                checkPendingRules()

                // 🚀 [Auto-Blacklist] 定期扫描已安装 App，发现类别内未屏蔽的自动加入
                CreditManager.periodicAutoBlacklistScan(this@MonitorService)

                // 🚀 [Dynamic Interval] 动态扫描间隔
                // 在强制锁机期间，如果黑名单应用仍在前台，延长扫描间隔以防止用户卡死
                nextInterval = calculateDynamicInterval()
            } catch (t: Throwable) {
                Log.e(TAG, "monitorRunnable failed, keep loop alive", t)
            } finally {
                scheduleMonitorLoop(nextInterval)
            }
        }
    }

    private fun scheduleMonitorLoop(delayMs: Long) {
        val handler = backgroundHandler ?: return
        if (!isRunning) return
        if (!monitorScheduleGate.tryMarkScheduled()) {
            return
        }
        if (delayMs <= 0L) {
            handler.post(monitorRunnable)
        } else {
            handler.postDelayed(monitorRunnable, delayMs)
        }
    }

    private fun scheduleMonitorLoopNow() {
        scheduleMonitorLoop(0L)
    }

    fun requestImmediateForegroundCheck(reason: String) {
        val handler = backgroundHandler ?: return
        Log.d(TAG, "[requestImmediateForegroundCheck] reason=$reason")
        if (reason.contains("DND", ignoreCase = true) ||
            reason.contains("BOUNDARY", ignoreCase = true) ||
            reason.contains("RULE", ignoreCase = true) ||
            reason.contains("START", ignoreCase = true)
        ) {
            mForceRuleBoundaryCheck = true
        }
        handler.post { checkForegroundApp() }
    }

    private fun cancelMonitorLoop() {
        backgroundHandler?.removeCallbacks(monitorRunnable)
        monitorScheduleGate.reset()
    }
    
    // 🚀 [Logic] Pending Rules Application & Baseline Update
    private fun checkPendingRules() {
        val context = this
        val now = System.currentTimeMillis()

        // 1. Blacklist Apps — 仅在有 pending 时调用
        if (CreditManager.hasPendingApps(context)) {
            CreditManager.applyPendingAppsToActive(context)
        }

        // 2. Time Rules — 内部已有 has_pending_changes 守卫
        TimeRuleManager.applyPendingToActive(context)

        // 3. App Window — 仅在有 pending 时调用
        if (AppWindowManager.hasPendingChanges(context)) {
            AppWindowManager.applyPendingToActive(context)
        }

        // 4. Weekly Privilege Limit
        CreditManager.checkAndApplyPendingWeeklyLimit(context)

        // 5. Baseline Update — 每日基准由 DailyBaselineManager 幂等写入（Application、主界面也会先到）。
        DailyBaselineManager.ensureTodaySnapshot(context)

        // 6. A11y Health Check — 🔴 [Fix] 使用业务日替代 java.util.Date()（自然日零点）
        try {
            val currentSegIdx = TimeRuleManager.getCurrentSegmentIndex(context)
            if (currentSegIdx >= 0) {
                val a11yPrefs = context.getSharedPreferences("monitor_a11y_health", Context.MODE_PRIVATE)
                val lastCheckDate = a11yPrefs.getString("last_a11y_health_check_date", "")
                val todayBizDate = CreditManager.getBusinessDateString(context)

                if (lastCheckDate != todayBizDate) {
                    val a11yEnabled = PermissionManager.isAccessibilityEnabled(context)
                    val a11yActive = CoreService.isServiceActive || CoreService.hasRecentAccessibilitySignal()
                    if (a11yEnabled && !a11yActive) {
                        LogExporter.logEvent(TAG, "[无障碍状态] Enabled but not active in segment, prompting user")
                        a11yPrefs.edit().putString("last_a11y_health_check_date", todayBizDate).apply()
                        val intent = Intent(context, MainActivity::class.java).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                            putExtra("show_permission_dialog", true)
                        }
                        // REQ-5: 走 MainActivityEntry 业务入口。未同意 → 跳 PrivacyConsentActivity
                        MainActivityEntry.start(context, intent)
                    } else {
                        a11yPrefs.edit().putString("last_a11y_health_check_date", todayBizDate).apply()
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "a11y health check failed", e)
        }
    }

    // private var fastLockDismissed = false // Logic moved to CoreService
    
    /**
     * 🚀 [Dynamic Interval] 计算动态扫描间隔
     * - 强制锁机期间 + 黑名单应用在前台: (冷却时间 + 5) 秒
     * - 其他情况: 正常 500ms 扫描
     */
    private fun calculateDynamicInterval(): Long {
        // 过渡期极速轮询
        if (mRequireFastRecheck) {
            return 150L
        }
        // Presence 检测活跃时保持快速轮询
        if (mPresentPkg != null || mPresentLockedPkg != null) {
            return INTERVAL // 500ms
        }
        if (mLastTopEvidence == TopPackageEvidence.NONE) {
            return 1000L // 🚀 [Fix] Reduced from 5000L to 1000L to wake up faster on screen-on/first entry
        }
        return INTERVAL
    }

    // 🕒 Warning State Tracking
    private var lastWarningSegmentStart = -1L
    private var hasWarnedFar = false
    private var hasWarnedNear = false

    private fun checkTimeWarnings() {
        val nextSeg = TimeRuleManager.getNextSegment(this) ?: return
        val now = System.currentTimeMillis()
        
        // Calculate Next Start Time (Today or Tomorrow)
        val cal = java.util.Calendar.getInstance()
        val currentH = cal.get(java.util.Calendar.HOUR_OF_DAY)
        val currentM = cal.get(java.util.Calendar.MINUTE)
        val currentTotal = currentH * 60 + currentM
        val startTotal = nextSeg.startHour * 60 + nextSeg.startMinute
        
        // Handling cross-midnight logic for START time is tricky if we rely on simple minutes
        // But usually warnings are for "Upcoming" segment.
        // If startTotal < currentTotal, it means it's tomorrow (assuming getNextSegment logic returns correct next)
        
        var timeToStartMins = startTotal - currentTotal
        if (timeToStartMins < 0) {
            timeToStartMins += 1440 // It's tomorrow
        }
        
        // Reset state if we switched target segments
        // We use a unique ID for segment instance? Or just StartTime?
        // Using StartTime + Limit + Name as signature
        val segSignature = (nextSeg.startHour * 100 + nextSeg.startMinute).toLong()
        if (segSignature != lastWarningSegmentStart) {
            lastWarningSegmentStart = segSignature
            hasWarnedFar = false
            hasWarnedNear = false
        }
        
        // Far Warning (20 min default)
        val farThresh = TimeRuleManager.getNotifyEntryFarMinutes(this)
        val nearThresh = TimeRuleManager.getNotifyEntryNearMinutes(this)

        // 🔍 [Debug] Log notification timing check
        if (timeToStartMins <= farThresh + 5) { // Only log when getting close
             Log.d(TAG, "checkTimeWarnings: timeToStart=$timeToStartMins, far=$farThresh, near=$nearThresh, warnedFar=$hasWarnedFar, warnedNear=$hasWarnedNear")
        }
        if (!hasWarnedFar && timeToStartMins <= farThresh && timeToStartMins > farThresh - 2) {
             val title = "📅 即将进入自律时段"
             val msg = "距离【${nextSeg.name}】开始还有 ${timeToStartMins} 分钟，请做好准备。"
             sendNotification(title, msg)
             // Optional: Toast or Dialog if requested? Manual says "Popup" (Window) for 20min?
             // "20分钟弹窗提醒"
             showToast(msg)
             hasWarnedFar = true
        }

        // Near Warning (5 min default)
        // Near Warning (5 min default)
        // val nearThresh already defined above

        if (!hasWarnedNear && timeToStartMins <= nearThresh && timeToStartMins > 0) {
             val title = "⚠️ 马上开始监管"
             val msg = "仅剩 ${timeToStartMins} 分钟！将限制娱乐应用，保持专注。"
             sendNotification(title, msg)
             // Manual says "Vibrate + Popup"
             performVibration()
             showToast(msg)
             hasWarnedNear = true
        } else if (timeToStartMins <= 0) {
             // 🚀 [Fix] Cancel warning notification when time is up (Segment Started)
             val nm = getSystemService(NotificationManager::class.java)
             nm.cancel(NOTIFICATION_ID_WARNING)
        }
    }

    private fun sendNotification(title: String, msg: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channelId = "decikeeper_monitor_channel"
            val notification = NotificationCompat.Builder(this, channelId)
                .setContentTitle(title)
                .setContentText(msg)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .build()
            postNotificationIfAllowed(notification)
        }
    }

    private fun canPostNotifications(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return true
        }
        val permissionGranted =
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        if (!permissionGranted) {
            Log.w(TAG, "Skip notify: POST_NOTIFICATIONS not granted")
            return false
        }
        val notificationsEnabled = NotificationManagerCompat.from(this).areNotificationsEnabled()
        if (!notificationsEnabled) {
            Log.w(TAG, "Skip notify: notifications disabled")
        }
        return notificationsEnabled
    }

    @SuppressLint("NotificationPermission")
    private fun postNotificationIfAllowed(notification: Notification) {
        if (Build.VERSION.SDK_INT >= 33) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                return
            }
        }
        try {
            NotificationManagerCompat.from(this).notify(NOTIFICATION_ID_WARNING, notification)
        } catch (e: SecurityException) {
            Log.e(TAG, "Failed to post notification: permission denied", e)
        }
    }
    
    private fun showToast(msg: String) {
        Handler(Looper.getMainLooper()).post {
            android.widget.Toast.makeText(applicationContext, msg, android.widget.Toast.LENGTH_LONG).show()
        }
    }

    private fun performVibration() {
        val v = getSystemService(Context.VIBRATOR_SERVICE) as android.os.Vibrator
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            v.vibrate(android.os.VibrationEffect.createOneShot(500, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            v.vibrate(500)
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "MonitorService Created")
        ensureForeground()
        usageStatsManager = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        // 🚀 [Init] Ensure default blacklist is populated on first run
        CreditManager.initDefaultBlacklist(this)
        LockWindowManager.init(this)
        // 🛡️ [Finding 1 修复] 锁屏真正成功 attach 渲染后（或已处于稳态 attach 时），才消费业务边沿为 BLOCK_CONSUMED
        LockWindowManager.onLockScreenAttachedListener = { pkg ->
            mLastBusinessBlockStateByPackage[pkg] = BusinessBlockState.BLOCK_CONSUMED
        }
        
        // 🚀 [Moved] FastLockService stop logic moved to MonitorRunnable
        // to ensure it only stops after the first status check is complete.
        
        // 🚀 [Double Insurance] Restored logic from "Success Experience"
        // Also stop FastLockService from here after 15s delay, in case CoreService fails.
        Handler(Looper.getMainLooper()).postDelayed({
            try {
                StartupProtectionManager.requestFastLockHandoff(this)
                Log.d(TAG, "MonitorService: FastLockService handoff requested (15s fallback)")
            } catch (e: Exception) {
                Log.e(TAG, "FastLock handoff failed", e)
            }
        }, StartupProtectionManager.FAST_LOCK_MAX_VISIBLE_MS)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureForeground()
        if (!isRunning) {
            isRunning = true
            
            // 🚀 [Performance] Move monitoring to background thread
            monitorThread = android.os.HandlerThread("MonitorBgThread").apply { start() }
            backgroundHandler = Handler(monitorThread!!.looper)
            scheduleMonitorLoopNow()
            
            Log.d(TAG, "MonitorService Started (Background Thread)")
            
            val filter = android.content.IntentFilter(ACTION_END_FREE_TIME)
            // 🚀 [Battery] Add Screen Actions
            filter.addAction(Intent.ACTION_SCREEN_OFF)
            filter.addAction(Intent.ACTION_SCREEN_ON)
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                // Screen broadcasts are protected, so RECEIVER_EXPORTED shouldn't matter for them, 
                // but for our custom action it might. Mixing them is tricky.
                // Best to split or just use Context.RECEIVER_EXPORTED if one of them is exported.
                // ACTION_END_FREE_TIME is internal broadcast usually?
                // Let's use separate registrations if needed, but for now combined is okay with EXPORTED 
                // if we expect external triggers, or NOT_EXPORTED if internal.
                // Assuming internal for custom action. But SYSTEM broadcasts require no flags or specific handling.
                // Registering combined needs care. System broadcasts don't need Exported checks.
                // Let's register separately.
            }

            // Register Custom Action
            val customFilter = android.content.IntentFilter().apply {
                addAction(ACTION_END_FREE_TIME)
                addAction(ACTION_REFRESH_RULES)
            }
            androidx.core.content.ContextCompat.registerReceiver(
                this,
                endFreeTimeReceiver,
                customFilter,
                androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED
            )
             
             // Register Screen Action
             val screenFilter = android.content.IntentFilter().apply {
                 addAction(Intent.ACTION_SCREEN_OFF)
                 addAction(Intent.ACTION_SCREEN_ON)
             }
             if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                 registerReceiver(screenStateReceiver, screenFilter, Context.RECEIVER_NOT_EXPORTED)
             } else {
                 registerReceiver(screenStateReceiver, screenFilter)
             }

            // 🚀 [Auto-Blacklist] Dynamic fallback for package install (complements manifest receiver)
            val packageFilter = android.content.IntentFilter().apply {
                addAction(Intent.ACTION_PACKAGE_ADDED)
                addAction(Intent.ACTION_PACKAGE_REPLACED)
                addDataScheme("package")
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    registerReceiver(packageInstallReceiver, packageFilter, Context.RECEIVER_NOT_EXPORTED)
                } else {
                    registerReceiver(packageInstallReceiver, packageFilter)
                }
                Log.d(TAG, "PackageInstall dynamic receiver registered")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to register package install receiver", e)
            }
        }
        return START_STICKY
    }

    @Volatile
    private var isForegroundStarted = false

    private fun ensureForeground() {
        if (isForegroundStarted) return
        try {
            startForegroundService()
            isForegroundStarted = true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start foreground", e)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        LockWindowManager.onLockScreenAttachedListener = null
        isRunning = false
        cancelMonitorLoop()
        monitorThread?.quitSafely()
        try {
            unregisterReceiver(endFreeTimeReceiver)
            unregisterReceiver(screenStateReceiver)
            unregisterReceiver(packageInstallReceiver)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to unregister receivers", e)
        }
        mFreeTimeTimer?.cancel()
        Log.d(TAG, "MonitorService Destroyed")
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    private fun startForegroundService() {
        val channelId = "decikeeper_monitor_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "决定守护后台监控服务",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }

        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("决定守护 正在运行")
            .setContentText("正在守护自律时间边界...")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        startForeground(2001, notification)
    }

    // =========================================================
    // Core Logic Ported from CoreService
    // =========================================================

    private val endFreeTimeReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_END_FREE_TIME) {
                val privilegeTakeover = intent.getBooleanExtra("privilege_takeover", false)
                endFreeTimeEarly(privilegeTakeover)
            } else if (intent?.action == ACTION_REFRESH_RULES) {
                TimeRuleManager.invalidateSegmentsCache()
                AppWindowManager.invalidateCache() // 🚀 [Fix] Force reload from prefs
                
                // 🚀 [App Window] Apply Pending Rules if Effective Time Passed
                AppWindowManager.applyPendingToActive(context!!) // Use context here
                
                // Re-check blocking status immediately
                backgroundHandler?.post { checkForegroundApp() } // Use backgroundHandler
            }
        }
    }
    
    // 🚀 [Battery Optimization] Stop polling when screen is OFF
    private val screenStateReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    Log.d(TAG, "Screen OFF -> Pausing Monitor")
                    foregroundSessionTracker.onScreenOff(System.currentTimeMillis())
                    foregroundGuardFallbackGate.reset()
                    cancelMonitorLoop()
                }
                Intent.ACTION_SCREEN_ON -> {
                    Log.d(TAG, "Screen ON -> Resuming Monitor")
                    // Reset first, then start a fresh single loop.
                    cancelMonitorLoop()
                    scheduleMonitorLoopNow()
                }
            }
        }
    }

    // 🚀 [Auto-Blacklist] Dynamic fallback receiver for package install events
    // Complements the manifest-registered PackageInstallReceiver for when OEM restrictions block it
    private val packageInstallReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (context == null || intent == null) return
            val action = intent.action
            if (action != Intent.ACTION_PACKAGE_ADDED && action != Intent.ACTION_PACKAGE_REPLACED) return
            val pkg = intent.data?.schemeSpecificPart ?: return
            Log.d(TAG, "[PackageInstall] Dynamic receiver: $action for $pkg")
            CreditManager.tryAutoBlacklistNewPackage(context, pkg)
        }
    }

    private var mFreeTimeTimer: android.os.CountDownTimer? = null

    // Called by LockWindowManager (via mService reference, technically we are the service now if we pass context)
    // Wait, LockWindowManager expects CoreService for grantFreeTime. 
    // We fixed LockWindowManager to take Context, but it calls mService?.grantFreeTime if available.
    // MonitorService IS A Service, but not CoreService class. 
    // We need to implement grantFreeTime in MonitorService and ensure LockWindowManager calls it.
    // Issue: LockWindowManager calls `mService?.grantFreeTime` where mService is `CoreService?`.
    // We need to update LockWindowManager to accept an interface or generic Service, OR just use Context + Reflection/Interface.
    // For now, let's implement the method here.
    
    // 🚀 [Refund Logic] Track Rest Session Data
    private var mRestStartTime = 0L
    private var mRestTotalCost = 0.0
    private var mRestRequestedDurationMins = 0.0
    private var mRestTargetPackageName: String? = null

    // Called by LockWindowManager
    fun grantFreeTime(duration: Long, costMinutes: Double = 0.0) {
        val now = System.currentTimeMillis()

        // 1. Record Session Data for potential refund
        mRestStartTime = now
        mRestTotalCost = costMinutes
        mRestTargetPackageName = LockWindowManager.getCurrentBlockedPackageName()
        // 精确到0.1分钟（6秒粒度）
        mRestRequestedDurationMins = kotlin.math.round(duration / 6000.0) / 10.0

        Log.i(TAG, "grantFreeTime: duration=${duration}ms, cost=${costMinutes}min (Rate: ${if(mRestRequestedDurationMins>0) costMinutes/mRestRequestedDurationMins else 0}x)")

        val endTime = now + duration
        CreditManager.setFreeTimeEnd(this, endTime)
        CreditManager.recordRestEvent(this)

        // 🚀 [Fix] 预计算冷却结束时间并持久化，防止进程被杀后冷却状态丢失
        val coolingSec = TimeRuleManager.getCoolDownRestSeconds(this)
        val coolingDuration = coolingSec.coerceAtLeast(0) * 1000L
        CreditManager.setCoolDownEnd(this, endTime + coolingDuration)

        // Start Timer to auto-lock when done
        startAutoLockTimer(duration)
        val lifecycleSession = CreditManager.beginRestLogSession(this)
        LockWindowManager.recordRestLifecycleStarted(
            session = lifecycleSession,
            requestedMins = mRestRequestedDurationMins,
            costMins = costMinutes
        )

        EventLog.unlockResult(
            traceId = currentTraceId,
            pkg = mRestTargetPackageName,
            unlockType = "TEMP",
            source = EventLog.UnlockSource.USER_TEMP_ACCESS,
            durationSec = duration / 1000,
            endTime = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date(endTime)),
            quotaConsumeSec = (costMinutes * 60).toLong(),
            goHome = false,
            reason = "Rest session started"
        )
    }

    private fun endFreeTimeEarly(
        privilegeTakeover: Boolean = false,
        naturalFreedom: Boolean = false
    ) {
        val now = System.currentTimeMillis()
        val sessionPkg = mRestTargetPackageName
        val privilegeActive = privilegeTakeover || CreditManager.isWeeklyPrivilegeSessionActive(this)
        if (!privilegeActive) {
            CreditManager.setFreeTimeEnd(this, 0)
        }
        mFreeTimeTimer?.cancel()

        // 🚀 [Cooldown Logic Fix]
        // Determine the logical end time of the rest.
        // If the session finished naturally (or device slept and we woke up late), 
        // the Cooldown should start from the PLANNED end time, not NOW.
        var actualEndTime = now
        val requestedDurationMillis = (mRestRequestedDurationMins * 60000L).toLong()

        if (mRestStartTime > 0 && requestedDurationMillis > 0) {
            val elapsed = now - mRestStartTime
            if (elapsed >= requestedDurationMillis) {
                // Natural Finish (or Overslept)
                actualEndTime = mRestStartTime + requestedDurationMillis
                Log.i(TAG, "Rest Finished Naturally (or Sleep): BaseTime adjusted to ${java.text.SimpleDateFormat("HH:mm:ss").format(java.util.Date(actualEndTime))}")
            }
        }

        // 🚀 [Refund Logic] Calculate actual usage and refund excess cost
        if (mRestStartTime > 0 && mRestTotalCost > 0 && mRestRequestedDurationMins > 0) {
            val usedDuration = now - mRestStartTime

            // 精确到0.1分钟（6秒粒度），四舍五入（与 CreditManager.getRestRefundPreview 同口径）
            val usedMinutes = kotlin.math.round(usedDuration / 6000.0) / 10.0

            if (usedMinutes < mRestRequestedDurationMins) {
                // 已用部分按申请时的费率（冷却期申请即 ×2）收费，未用部分全额退还，无折扣
                val rate = mRestTotalCost / mRestRequestedDurationMins
                val finalCost = usedMinutes * rate

                val refundAmount = mRestTotalCost - finalCost

                // Round to 1 decimal to avoid floating-point noise
                val roundedRefund = kotlin.math.round(refundAmount * 10.0) / 10.0
                val roundedFinalCost = kotlin.math.round(finalCost * 10.0) / 10.0

                Log.i(TAG, "Rest Ended Early: Used ${"%.1f".format(usedMinutes)}m of ${"%.1f".format(mRestRequestedDurationMins)}m. Cost: $mRestTotalCost -> $roundedFinalCost. Refund: $roundedRefund.")

                if (roundedRefund > 0) {
                    CreditManager.refundMinutes(this, roundedRefund)
                    val refundStr = if (roundedRefund == roundedRefund.toLong().toDouble()) roundedRefund.toLong().toString() else "%.1f".format(roundedRefund)
                    if (!naturalFreedom) {
                        android.widget.Toast.makeText(this, "提前结束休息，已退还 ${refundStr} 分钟额度", android.widget.Toast.LENGTH_LONG).show()
                    }
                    RestStatsRecorder.record(
                        context = this,
                        type = "REFUND",
                        typeName = "提前退还",
                        reason = "提前结束休息返还未用额度",
                        requestedMinutes = mRestRequestedDurationMins,
                        actualMinutes = usedMinutes,
                        costMinutes = -roundedRefund,
                        costCount = 0.0,
                        balanceMinutes = CreditManager.getRemainingMinutes(this),
                        balanceCount = CreditManager.getRemainingUsableCount(this)
                    )
                }
            }
            // Reset Session Data handled at end
        }

        if (privilegeActive) {
            // 🚀 [Rule] Privilege takeover: no cooldown, do not override free_time_end
            CreditManager.setCoolDownEnd(this, 0L)
            CreditManager.getRestLogSession(this)?.let { session ->
                LockWindowManager.recordRestLifecycleEnded(
                    session = session,
                    actualDurationMs = (now - mRestStartTime).coerceAtLeast(0L),
                    reason = "PRIVILEGE_STARTED",
                    cooldownEndMs = 0L
                )
            }
            mRestStartTime = 0
            mRestTotalCost = 0.0
            mRestRequestedDurationMins = 0.0
            mRestTargetPackageName = null
            CreditManager.clearRestSession(this)
            CreditManager.clearRestLogSession(this)
            return
        }

        // 🚀 [Rule] Set Rest Cooling Period（读取用户配置的休息冷却秒数）
        val coolingSec = TimeRuleManager.getCoolDownRestSeconds(this)
        val coolingDuration = coolingSec.coerceAtLeast(0) * 1000L
        if (naturalFreedom) {
            CreditManager.setCoolDownEnd(this, 0L)
        } else {
            CreditManager.setCoolDownEnd(this, actualEndTime + coolingDuration)
        }

        CreditManager.getRestLogSession(this)?.let { session ->
            LockWindowManager.recordRestLifecycleEnded(
                session = session,
                actualDurationMs = (now - mRestStartTime).coerceAtLeast(0L),
                reason = if (naturalFreedom) "NATURAL_FREEDOM" else "TIME_EXPIRED",
                cooldownEndMs = CreditManager.getCoolDownEnd(this)
            )
        }
        if (naturalFreedom) CreditManager.clearRestLogSession(this)

        // Reset Session
        mRestStartTime = 0
        mRestTotalCost = 0.0
        mRestRequestedDurationMins = 0.0
        mRestTargetPackageName = null
        CreditManager.clearRestSession(this)
        
        if (!naturalFreedom) {
            // 🚀 [Unified Expiry] 普通到期通过 ExpiryCoordinator 评估，不直接硬锁
            Log.i(TAG, "Free time ended, evaluating via ExpiryCoordinator")
            ExpiryCoordinator.evaluate(this, ExpiryReason.FREE_TIME_END)
        } else {
            LogExporter.i("Rest", "[NATURAL_FREEDOM] settled silently, cooldown cleared")
        }
        EventLog.unlockResult(
            traceId = currentTraceId,
            pkg = sessionPkg,
            unlockType = "SESSION_END",
            source = if (naturalFreedom) EventLog.UnlockSource.NATURAL_FREEDOM else EventLog.UnlockSource.SESSION_END,
            durationSec = 0,
            endTime = null,
            quotaConsumeSec = 0,
            goHome = naturalFreedom,
            reason = if (naturalFreedom) "Natural freedom entered" else "Session ended early"
        )
    }

    private fun startAutoLockTimer(duration: Long) {
        mFreeTimeTimer?.cancel()
        mFreeTimeTimer = object : android.os.CountDownTimer(duration, 1000) {
            override fun onTick(millis: Long) {}
            override fun onFinish() {
                // Auto-finish also triggers endFreeTimeEarly logic.
                // We MUST NOT clear mRestStartTime here, because endFreeTimeEarly needs it
                // to calculate the correct cooldown base time (Planned End vs Now).
                endFreeTimeEarly()
            }
        }.start()
    }
    
    private var mLastUsedMinuteTime = 0L
    private var mLastWasGap = false
    private var mLastWasRest = false
    private var stateInitialized = false

    // 🛡️ [启动保护期] 防止服务启动时误触发冷静期
    // 只在真正的模式切换（休息结束、空档期结束）时才触发冷静期
    private var mStartupProtectionEndTime = 0L
    private val STARTUP_PROTECTION_DURATION = 3000L // Reduced to 3s for faster protection

    // 🚀 [Presence Tracker] 状态机：负责跟踪已确认黑名单小窗的生命周期直到收到明确关闭证据
    private val mPresenceTracker by lazy(LazyThreadSafetyMode.NONE) {
        VisibleBlacklistPresenceTracker(
            desktopGraceDurationMs = 3000L,
            protectionContext = applicationContext
        )
    }
    // 🛡️ [High-Water Mark] 记录每个包最近处理过的事件时间戳与同毫秒事件签名集合，避免 30s UsageEvents 重复遍历导致旧事件重放
    private val mLastProcessedPresenceEventAtMs = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val mProcessedSignaturesAtLastTs = java.util.concurrent.ConcurrentHashMap<String, MutableSet<String>>()

    private fun establishVisibleBlacklistPresence(pkg: String, now: Long) {
        mPresenceTracker.onPresenceConfirmed(pkg, now)
        mPresentLockedPkg = pkg
        mPresentLockedAtMs = now
        Log.d(TAG, "[PRESENCE_STATE] pkg=$pkg to=CONFIRMED_VISIBLE reason=ESTABLISH_SESSION")
    }

    // =========================================================
    // UsageStats Fallback Blocking (Non-Accessibility)
    // =========================================================

    // ═══════════════════════════════════════════════════════════
    // Presence-Triggered Detection Functions
    // ═══════════════════════════════════════════════════════════

    /**
     * 扫描所有应用的 lastTimeVisible，返回黑名单中近期有可见窗口的应用。
     * 不关心"前台是谁"——只关心黑名单应用的窗口是否在屏幕上。
     */
    private fun scanBlacklistPresence(now: Long): Map<String, Long> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return emptyMap()
        val usm = usageStatsManager ?: return emptyMap()
        mPresenceStatScans++

        val stats: List<android.app.usage.UsageStats>
        try {
            stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, now - 60_000L, now) ?: emptyList()
        } catch (e: Exception) {
            Log.w(TAG, "[Presence] scan error: ${e.message}")
            return emptyMap()
        }
        if (stats.isNullOrEmpty()) {
            return emptyMap()
        }

        val result = mutableMapOf<String, Long>()
        val rejected = mutableListOf<String>()

        for (stat in stats) {
            val pkg = stat.packageName ?: continue
            if (pkg == this.packageName) continue

            val ltv = stat.lastTimeVisible
            if (ltv <= 0L) continue

            if (!mPresenceTracker.isLastTimeVisibleValid(pkg, ltv)) {
                rejected.add("$pkg(ltv<=lastStopped,closed_residual)")
                continue
            }

            val delta = now - ltv
            if (delta > PRESENCE_VISIBLE_WINDOW_MS) {
                if (delta < PRESENCE_VISIBLE_WINDOW_MS + 2000L) {
                    rejected.add("$pkg(delta=${delta}ms,old)")
                }
                continue
            }

            val isBlocked = try {
                SegmentBlacklist.shouldLock(this, pkg)
            } catch (e: Exception) { false }

            if (isBlocked) {
                result[pkg] = delta
            } else if (delta < 2000L) {
                rejected.add("$pkg(delta=${delta}ms,not_blacklisted)")
            }
        }

        return result
    }

    /**
     * 双重确认：连续 N 轮都看到该应用才确认窗口真的存在。
     * 每轮重新读取 UsageStats，防止通知闪烁（<1s 的瞬时 lastTimeVisible）误触发。
     */
    private fun confirmPresence(pkg: String, now: Long): Boolean {
        val currentMap = scanBlacklistPresence(now)
        val currentDelta = currentMap[pkg]

        if (currentDelta == null) {
            if (mPresentPkg == pkg) {
                mPresenceStatRejectedStale++
                resetPresenceDetection()
            }
            return false
        }

        if (pkg == mPresentPkg) {
            mPresentConfirmCount++
        } else {
            mPresentPkg = pkg
            mPresentConfirmCount = 1
            mPresentFirstSeenMs = now
        }

        if (mPresentConfirmCount >= PRESENCE_CONFIRM_CYCLES) {
            mPresenceStatConfirmed++
            return true
        }
        return false
    }

    /** 检查已锁应用的小窗是否已关闭（lastTimeVisible 过期 > 30s） */
    private fun isPresenceStale(pkg: String, now: Long): Boolean {
        if (CoreService.isServiceActive) {
            val visiblePkgs = CoreService.lastVisiblePackagesFromWindows
            return !visiblePkgs.contains(pkg)
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return true
        val usm = usageStatsManager ?: return true
        return try {
            val stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, now - 60_000L, now)
            val appStat = stats?.firstOrNull { it.packageName == pkg }
            if (appStat == null) {
                return true
            }
            val ltv = appStat.lastTimeVisible
            if (ltv <= 0L) return true
            val delta = now - ltv
            var stale = delta > PRESENCE_STALE_UNLOCK_MS
            // 🚀 [Fix] UsageStats 在锁屏覆盖后不更新 lastTimeVisible，导致30秒后误判 stale
            // 如果 App 仍是我们检测到的前台应用，不应判定为 stale
            if (stale && mLastTopPackage == pkg) {
                stale = false
            }
            stale
        } catch (e: Exception) { true }
    }

    /** Presence 触发锁机 */
    private fun triggerPresenceLock(pkg: String, now: Long) {
        val appLabel = try {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
        } catch (e: Exception) { pkg }
        val confirmMs = now - mPresentFirstSeenMs
        Log.w(TAG, "[VisibleBlacklist] action=LOCK pkg=$pkg label=$appLabel topAtLock=${mLastTopPackage ?: "null"} source=PRESENCE reason=BLACKLIST_VISIBLE_WINDOW confirmMs=${confirmMs}ms")
        LogExporter.logEvent(
            "VisibleBlacklist",
            "action=LOCK pkg=$pkg source=PRESENCE foreground=${mLastTopPackage ?: "null"} reason=BLACKLIST_VISIBLE_WINDOW"
        )
        val curSeg = TimeRuleManager.getCurrentSegment(this)
        val isAppWindowBlocked = AppWindowManager.isManaged(this, pkg) && !AppWindowManager.isAppAllowed(this, pkg)
        val isRealBlacklist = if (curSeg != null && curSeg.listApps != null) {
            SegmentBlacklist.isInEffectiveList(this, curSeg, pkg)
        } else {
            CreditManager.getBlacklistApps(this).any { it.equals(pkg, ignoreCase = true) }
        }
        Log.d(TAG, "LockReason: blacklist=$isRealBlacklist appWindow=$isAppWindowBlocked pkg=$pkg")
        LockWindowManager.showLockScreen(
            reason = "Presence: $pkg",
            isBlacklist = isRealBlacklist,
            isAppWindowBlocked = isAppWindowBlocked,
            blockedAppLabel = appLabel,
            blockedPackageName = pkg,
            detectionSource = "PRESENCE",
            detectionSnapshot = getDetectionSnapshot()
        )
        mPresentLockedPkg = pkg
        mPresentLockedAtMs = now
        currentLockedPkg = pkg
        mPresenceStatLocked++
        resetPresenceDetection()
    }

    /** 小窗 Presence 消失后仅清理检测状态；锁屏只能由四个显式出口撤销。 */
    private fun handlePresenceUnlock(pkg: String, reason: String) {
        val lockedFor = (System.currentTimeMillis() - mPresentLockedAtMs).coerceAtLeast(0L)
        Log.i(TAG, "[Presence] CLEAR_ONLY: pkg=$pkg reason=$reason lockedFor=${lockedFor}ms")
        LogExporter.logEvent("MonitorService", "[Presence_CLEAR_ONLY] pkg=$pkg reason=$reason lockedFor=${lockedFor}ms")
        mPresentLockedPkg = null
        mPresentLockedAtMs = 0L
        mPresenceStatUnlocked++
    }

    /** 重置检测状态 */
    private fun resetPresenceDetection() {
        mPresentPkg = null
        mPresentConfirmCount = 0
        mPresentFirstSeenMs = 0L
    }

    /** 每 N 轮输出统计摘要 */
    private fun logPresenceStatsIfNeeded() {
        if (mPresenceStatScans == 0) return
        Log.i(TAG, "[Presence] stats(${PRESENCE_STAT_INTERVAL}ticks): scans=${mPresenceStatScans} confirmed=${mPresenceStatConfirmed} locked=${mPresenceStatLocked} unlocked=${mPresenceStatUnlocked} rejectedStale=${mPresenceStatRejectedStale}")
        mPresenceStatScans = 0; mPresenceStatConfirmed = 0
        mPresenceStatLocked = 0; mPresenceStatUnlocked = 0; mPresenceStatRejectedStale = 0
    }

    private fun calculateIsBusinessBlocked(
        pkg: String,
        currentSeg: TimeSegment?,
        isPrivilege: Boolean,
        isDndLocked: Boolean
    ): Boolean {
        val isBlacklisted = SegmentBlacklist.isSubjectToBlock(this, currentSeg, pkg)
        val isSafe = CreditManager.isLauncher(pkg) ||
            pkg == "com.android.settings" ||
            CreditManager.isInputMethod(this, pkg) ||
            pkg.contains("systemui", ignoreCase = true) ||
            pkg.equals("com.vivo.systemuiplugin", ignoreCase = true)
        val segmentDecoupled = currentSeg != null && currentSeg.listApps != null
        val isAppWindowBlocked = if (AppWindowManager.isManaged(this, pkg)) {
            !AppWindowManager.isAppAllowed(this, pkg)
        } else {
            false
        }
        val isTemporaryAccess = LockWindowManager.isTemporaryAccessActive()
        return LockDecisionPolicy.shouldBlock(
            isBlacklisted = isBlacklisted,
            isSafe = isSafe,
            isPrivilege = isPrivilege,
            isDndLocked = isDndLocked,
            isAppWindowBlocked = isAppWindowBlocked,
            segmentDecoupled = segmentDecoupled,
            isManaged = { AppWindowManager.isManaged(this, pkg) },
            effectivePackageName = pkg,
            isTemporaryAccess = isTemporaryAccess
        )
    }

    private fun checkRuleBoundaryCandidateLock(
        now: Long,
        resolvedPackageName: String?,
        isCarrierPackage: Boolean,
        isRecentAppsOrLauncher: Boolean,
        currentSeg: TimeSegment?,
        isPrivilege: Boolean,
        isDndLocked: Boolean,
        isKeyguardLocked: Boolean,
        isScreenInteractive: Boolean,
        forceCheckThisCycle: Boolean,
        leftGap: Boolean,
        endedRest: Boolean
    ): Boolean {
        val activeSession = foregroundSessionTracker.currentSessionSnapshot()
        val sessionPkg = activeSession?.packageName
        val isSessionNoiseOrLauncherOrSelf = if (sessionPkg != null) {
            CreditManager.isLauncher(sessionPkg) ||
                ForegroundSessionTracker.defaultIsNoisePackage(sessionPkg) ||
                CreditManager.isInputMethod(this, sessionPkg) ||
                sessionPkg == this.packageName
        } else false

        val isBusinessBlockedNow = if (sessionPkg != null && !isSessionNoiseOrLauncherOrSelf) {
            calculateIsBusinessBlocked(
                pkg = sessionPkg,
                currentSeg = currentSeg,
                isPrivilege = isPrivilege,
                isDndLocked = isDndLocked
            )
        } else false

        val previousBusinessBlockState = if (sessionPkg != null) mLastBusinessBlockStateByPackage[sessionPkg] else null

        val decision = BoundaryCandidateCoordinator.evaluate(
            BoundaryCandidateInput(
                resolvedPackageName = resolvedPackageName,
                isCarrierPackage = isCarrierPackage,
                isRecentAppsOrLauncher = isRecentAppsOrLauncher,
                isLockShowing = LockWindowManager.isLocked,
                isInDesktopReturnGrace = LockWindowManager.isInDesktopReturnGrace(now),
                homeClickPendingPackage = LockWindowManager.homeClickPendingPackage,
                activeSessionPackage = sessionPkg,
                isSessionClosed = activeSession?.closed ?: true,
                isSessionNoiseOrLauncherOrSelf = isSessionNoiseOrLauncherOrSelf,
                isBusinessBlockedNow = isBusinessBlockedNow,
                previousBusinessBlockState = previousBusinessBlockState,
                isKeyguardLocked = isKeyguardLocked,
                isScreenInteractive = isScreenInteractive
            )
        )

        // 持续同步业务状态记录（解决 Finding 1 & 2）：
        // 1. 若当前判定为 ALLOW，同步更新为 ALLOWED；
        // 2. 候选触发时保持 BLOCK_PENDING，锁屏成功交付后才置为 BLOCK_CONSUMED；
        // 3. 稳态 BLOCK 门禁拒绝时置为 BLOCK_CONSUMED。
        if (sessionPkg != null && decision.updatedBusinessBlockState != null) {
            if (!decision.shouldLockCandidate || decision.updatedBusinessBlockState == BusinessBlockState.ALLOWED) {
                mLastBusinessBlockStateByPackage[sessionPkg] = decision.updatedBusinessBlockState
            } else {
                mLastBusinessBlockStateByPackage[sessionPkg] = BusinessBlockState.BLOCK_PENDING
            }
        }

        if (!decision.shouldLockCandidate || decision.candidatePackage == null) {
            return false
        }

        val targetPkg = decision.candidatePackage
        Log.w(
            TAG,
            "[ForegroundGuard] Active session preemptive boundary lock triggered: " +
            "pkg=$targetPkg, sessionOpenedAt=${activeSession?.openedAtMs}, reason=${decision.reason}"
        )

        val isManaged = AppWindowManager.isManaged(this, targetPkg)
        val isAppWindowBlocked = if (isManaged) !AppWindowManager.isAppAllowed(this, targetPkg) else false
        val isRealBlacklist = if (currentSeg != null && currentSeg.listApps != null) {
            SegmentBlacklist.isInEffectiveList(this, currentSeg, targetPkg)
        } else {
            CreditManager.getBlacklistApps(this).any { it.equals(targetPkg, ignoreCase = true) }
        }
        val pm = packageManager
        val appLabel = try {
            pm.getApplicationLabel(pm.getApplicationInfo(targetPkg, 0)).toString()
        } catch (e: Exception) { targetPkg }

        val shouldStrictLock = LockWindowManager.onBlacklistHit(
            this,
            targetPkg,
            createLockScreen = false
        )
        if (shouldStrictLock) {
            val cycleSnapshot = currentForegroundCycle
            val fgReportId = cycleSnapshot?.reportId
            recordFgRequestLock(
                cycle = cycleSnapshot,
                effectivePkg = targetPkg,
                effectiveBlacklisted = true,
                businessReason = "BLACKLIST_DND_LOCKED",
                allowanceState = "ALLOW_NONE",
                guardPassed = true,
                visibilityPassed = null
            )
            val requestGeneration = CoreService.getAccessibilityGeneration()
            LockWindowManager.showLockScreen(
                "Blocked: $targetPkg",
                isBlacklist = isRealBlacklist,
                isAppWindowBlocked = isAppWindowBlocked,
                blockedAppLabel = appLabel,
                blockedPackageName = targetPkg,
                detectionSource = "SESSION_BOUNDARY",
                detectionSnapshot = getDetectionSnapshot(),
                foregroundReportId = fgReportId,
                foregroundRequestId = currentTraceId,
                foregroundRequestAtMs = now,
                requestGeneration = requestGeneration
            )
            currentLockedPkg = targetPkg
            mPreviouslyBlockedPackages.add(targetPkg)
            // 🛡️ [Finding 1 修复] 状态保持 BLOCK_PENDING，只有在 targetWM.addView() 成功（或已 attach）时
            // 由 onLockScreenAttachedListener 回调置为 BLOCK_CONSUMED；
            // 异步 attach 失败、取消或未 dispatch 时均保持 BLOCK_PENDING 支持次轮重试。
            return true
        }
        return false
    }

    private fun checkForegroundApp() {
        val now = System.currentTimeMillis()

        // 🚀 [Cleanup & Self-Heal] Clear stale lockedPkg early before any pause gate
        // If the lock screen is not currently showing, currentLockedPkg MUST be cleared
        // so that heartbeat and subsequent detection never get stranded.
        currentLockedPkg = reconcileStaleLockedPackage(currentLockedPkg, LockWindowManager.isLocked)

        // 🚀 [Self-Heal] If lock screen is gone, input panel cannot legitimately remain open
        if (isOrphanInputPanelOpen(LockWindowManager.isLocked, LockWindowManager.isInputPanelOpen)) {
            Log.w(TAG, "[AUTO_HEAL] Orphan isInputPanelOpen detected while lock is not showing, dismissing")
            LockWindowManager.dismissRestWindow("stale_unlocked_monitor_heal")
        }

        // 🚀 [NEW] Skip check if user is typing in the input panel
        if (LockWindowManager.shouldPauseForegroundBlockingChecks(now)) return

        // 🚀 [NEW] Force active window tree scan from Accessibility Service
        // This guarantees that we always have 100% fresh window visibility data, 
        // completely bypassing the limitation where Accessibility doesn't receive events 
        // while the user is passively watching a PIP/small window video on the Home screen!
        // 🛡️ [Keyguard 与屏幕状态前置反证 (Finding 3)]
        // 在任何前台包名解析与早退分支前优先检测系统锁屏状态，确保锁屏时无条件失效 Session 并重置保底门禁
        val km = getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        val isKeyguardLocked = km?.isKeyguardLocked == true
        val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
        val isScreenInteractive = pm?.isInteractive ?: true
        if (isKeyguardLocked || !isScreenInteractive) {
            foregroundSessionTracker.onKeyguardLocked(now)
            foregroundGuardFallbackGate.reset()
        }

        if (CoreService.isServiceActive) {
            val snapshot = CoreService.captureWindowSnapshot(now)
            mPresenceTracker.onAccessibilitySnapshot(snapshot, now)
        }
        mRequireFastRecheck = false

        // 预分配 traceId（本轮回合，递增由 logForegroundResolution() 负责）
        currentTraceId = EventLog.generateTraceId(now, lockDecisionSeq + 1)

        // [Diagnostics-only] FG 编号：前台汇报专用 traceId
        foregroundReportSeq += 1
        val foregroundReportId = EventLog.generateForegroundTraceId(now, foregroundReportSeq)
        currentForegroundCycle = ForegroundDiagnosticCycle(
            reportId = foregroundReportId,
            checkedAtMs = now,
            previousForegroundPackageName = mLastTopPackage
        )

        // ── Presence 统计 ──
        mPresenceTickCount++
        if (mPresenceTickCount % PRESENCE_STAT_INTERVAL == 0) {
            logPresenceStatsIfNeeded()
        }

        // 2. State Check for Transitions
        val isFreeTime = now < CreditManager.getFreeTimeEnd(this)
        val currentSeg = TimeRuleManager.getCurrentSegment(this)
        val isInSegment = currentSeg != null
        val isPrivilege = CreditManager.isWeeklyPrivilegeSessionActive(this)
        val privilegeJustEnded = wasPrivilegeActiveLastPoll && !isPrivilege
        wasPrivilegeActiveLastPoll = isPrivilege
        val isDndLocked = isInSegment && !isFreeTime

        // 进入新的勿扰时段 → 记一条「额度发放」流水（每个时段每个业务日只记一次，失败不影响监控）
        if (isInSegment) {
            runCatching { QuotaGrantRecorder.recordIfNewSegment(this, now) }
        }

        // 🚀 [Unified Expiry] 特权到期 → 统一通过 ExpiryCoordinator 评估，不直接硬锁
        if (privilegeJustEnded) {
            Log.i(TAG, "Weekly privilege ended, evaluating via ExpiryCoordinator")
            ExpiryCoordinator.evaluate(this, ExpiryReason.PRIVILEGE_END)
        }

        // 🚀 [Logic] Trigger Cooling ONLY on transition to Monitored Segment
        // 🛡️ [Fix] 添加启动保护期：防止服务启动时误触发冷静期
        // 只有在启动保护期结束后，且真正发生模式切换时才触发冷静期
        val isPastStartupProtection = now > mStartupProtectionEndTime

        if (isInSegment && !isFreeTime) {
             // 初始化启动保护期（只在第一次进入勿扰时段时设置）
             if (mStartupProtectionEndTime == 0L) {
                 mStartupProtectionEndTime = now + STARTUP_PROTECTION_DURATION
                 Log.d(TAG, "Startup protection started until ${mStartupProtectionEndTime}")
             }

             // 只有在启动保护期结束后，且发生真正的模式切换时才触发冷静期
             if (stateInitialized && isPastStartupProtection) {
                 if (mLastWasGap) {
                     // 🚀 进入勿扰时段 → 清空空档期应用时间窗豁免
                     LockWindowManager.resetGapAppWindowExemptions()
                     val gapCd = TimeRuleManager.getCoolDownGapSeconds(this)
                     if (gapCd > 0) {
                         // 🚀 [Fix] 用时段实际开始时间作冷却基准，而非当前时间
                         // 屏幕关闭时 MonitorService 暂停，过渡检测延迟。
                         // 冷却应从时段开始时起算，确保在正确时间完成。
                         val seg = currentSeg
                         if (seg != null) {
                             val cal = java.util.Calendar.getInstance().apply {
                                 set(java.util.Calendar.HOUR_OF_DAY, seg.startHour)
                                 set(java.util.Calendar.MINUTE, seg.startMinute)
                                 set(java.util.Calendar.SECOND, 0)
                                 set(java.util.Calendar.MILLISECOND, 0)
                             }
                             val segStartMs = cal.timeInMillis
                             val baseTime = if (segStartMs < now) segStartMs else now
                             val coolEnd = baseTime + gapCd * 1000L
                             if (coolEnd > now) {
                                 CreditManager.setCoolDownEnd(this, coolEnd)
                                 Log.d(TAG, "Back-dated Gap->Work cooling (base=${java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date(baseTime))}, ends=${java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date(coolEnd))})")
                             } else {
                                 Log.d(TAG, "Gap->Work cooling already expired (would have ended at ${java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date(coolEnd))}). Clearing.")
                                 CreditManager.setCoolDownEnd(this, 0L)
                             }
                         } else {
                             CreditManager.setCoolDownEnd(this, now + gapCd * 1000L)
                             Log.d(TAG, "Triggering Gap->Work cooling (fallback): $gapCd s")
                         }
                     }
                 } else if (mLastWasRest) {
                      val restCd = TimeRuleManager.getCoolDownRestSeconds(this)
                      if (restCd > 0) {
                          val currentCoolDownEnd = CreditManager.getCoolDownEnd(this)
                          val freeTimeEnd = CreditManager.getFreeTimeEnd(this)
                          
                          // 🚀 [Fix] Smart Cooldown Logic (Rest -> Work)
                          // 1. If Cooldown is ALREADY active (future), respect it.
                          if (currentCoolDownEnd > now) {
                              Log.d(TAG, "Cooldown already active (Ends: ${java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date(currentCoolDownEnd))}). Skipping override.")
                          } 
                          // 2. If FreeTimeEnd is set and valid (missed timer?), use it as base.
                          else if (freeTimeEnd > 0 && freeTimeEnd < now) {
                               val newEnd = freeTimeEnd + restCd * 1000L
                               if (newEnd > now) {
                                   CreditManager.setCoolDownEnd(this, newEnd)
                                   Log.d(TAG, "Back-dated Cooldown (Base: ${java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date(freeTimeEnd))}). Ends: ${java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date(newEnd))}")
                               } else {
                                   Log.d(TAG, "Cooldown expired while screen off. Skipping penalty.")
                               }
                               // Clean up stale free time
                               CreditManager.setFreeTimeEnd(this, 0)
                          }
                          // 3. Fallback: If freeTimeEnd is 0 (Cleared by endFreeTimeEarly) AND not cooling...
                          // It means cooldown expired naturally. Do nothing.
                          else if (currentCoolDownEnd > 0 && currentCoolDownEnd <= now) {
                              Log.d(TAG, "Rest ended and Cooldown expired naturally. No new penalty.")
                          }
                          // 4. Safety Fallback: Only if we have NO data (Shouldn't happen if endFreeTimeEarly ran)
                          // But if it happened, maybe force a short penalty? Or assume safe?
                          // For now, let's assume if we lost track, we default to NOW to be strict, 
                          // BUT strictness is what caused the bug. 
                          // If freeTimeEnd is 0, likely endFreeTimeEarly ran.
                          else {
                               // If we are here, it means FreeTimeEnd is 0, ExistingCooldown is 0 (or never set).
                               // This implies we might have just started monitoring or state was reset.
                               // In the spirit of "Don't penalize blindly", if we verify isFreeTime is false, we assume compliant.
                               Log.d(TAG, "State ambiguous (FreeTimeEnd=0, CD=0). Skipping penalty to avoid false positive.")
                          }
                      }
                      
                  }
              }
             stateInitialized = true
        }
        
        // Update State Memory (Crucial: Update regardless of package detection)
        val wasGap = mLastWasGap
        val wasRest = mLastWasRest
        val currentIsGap = !isInSegment
        val currentIsRest = isFreeTime
        val leftGap = wasGap && !currentIsGap
        val endedRest = wasRest && !currentIsRest
        // 🛡️ [Finding 3 修复] 单次边沿标志立即提取为当前轮次局部变量，并在任何早退分支之前立即消费清零，绝不跨周期残留
        val forceCheckThisCycle = mForceRuleBoundaryCheck || leftGap || endedRest
        mForceRuleBoundaryCheck = false
        mLastWasGap = currentIsGap
        mLastWasRest = currentIsRest

        // 🧹 [Cleanup] Clear stale lockedPkg when lock screen is not showing.
        // currentLockedPkg is set on every lock but never cleared, causing the
        // health heartbeat to report a stale package as "locked" long after the
        // user has dismissed the lock screen or used temporary access.
        if (currentLockedPkg != null && !LockWindowManager.isLocked) {
            currentLockedPkg = null
        }

        val activeRestrictionTarget =
            LockWindowManager.getActiveTemporaryAccessTargetPackage()
                ?: mRestTargetPackageName
                ?: currentLockedPkg
        if (activeRestrictionTarget != null &&
            applyAutomaticRestrictionRelease(now, currentSeg, activeRestrictionTarget, isPrivilege)
        ) {
            return
        }

        // ── Presence Detection 前置评估与独立扫描 (基于 Sticky Presence Tracker 状态机) ──
        if (isDndLocked && !isPrivilege) {
            if (LockWindowManager.isLocked) {
                // 已有锁屏显示：扫描是否有新可见黑名单小窗，同步建立 Session 跟踪
                val presenceMap = scanBlacklistPresence(now)
                if (presenceMap.isNotEmpty()) {
                    val candidate = presenceMap.entries.first().key
                    if (confirmPresence(candidate, now)) {
                        establishVisibleBlacklistPresence(candidate, now)
                        mPresenceStatLocked++
                        resetPresenceDetection()
                    }
                }
            } else {
                // 锁屏未显示：
                // A. 优先评估 Tracker 当前动作（如 Grace 3s 到期仍无关闭证据，或处于 CONFIRMED_VISIBLE）
                val trackerAction = mPresenceTracker.evaluate(now)
                val trackedPkg = mPresenceTracker.currentPackage

                if (trackerAction == PresenceTrackerAction.LOCK && trackedPkg != null) {
                    val hasTemporaryAccessForTarget =
                        LockWindowManager.getActiveTemporaryAccessTargetPackage() == trackedPkg
                    val isGrace = LockWindowManager.isInDesktopReturnGrace(now) || (mPresenceTracker.state == PresenceTrackerState.DESKTOP_GRACE && now < mPresenceTracker.graceUntilMs)
                    val canLock = shouldTriggerVisibleBlacklistLock(
                        VisibleBlacklistLockInput(
                            isDndLocked = isDndLocked,
                            isPrivilegeActive = isPrivilege,
                            isLockShowing = LockWindowManager.isLocked,
                            isDesktopReturnGrace = isGrace,
                            hasTemporaryAccessForTarget = hasTemporaryAccessForTarget,
                            presenceConfirmed = true
                        )
                    )
                    if (canLock) {
                        Log.i("PresenceDiag", "[PRESENCE_ACTION] action=LOCK pkg=$trackedPkg reason=GRACE_EXPIRED_WITHOUT_CLOSE_EVIDENCE")
                        Log.d(TAG, "[PRESENCE_STATE] pkg=$trackedPkg action=LOCK reason=GRACE_EXPIRED_WITHOUT_CLOSE_EVIDENCE")
                        triggerPresenceLock(trackedPkg, now)
                        return
                    }
                } else {
                    // B. 首次扫描发现可见黑名单候选
                    val presenceMap = scanBlacklistPresence(now)
                    if (presenceMap.isEmpty()) {
                        if (mPresentPkg != null) {
                            resetPresenceDetection()
                        }
                    } else {
                        val candidate = presenceMap.entries.first().key
                        if (confirmPresence(candidate, now)) {
                            establishVisibleBlacklistPresence(candidate, now)
                            val hasTemporaryAccessForTarget =
                                LockWindowManager.getActiveTemporaryAccessTargetPackage() == candidate
                            val canLock = shouldTriggerVisibleBlacklistLock(
                                VisibleBlacklistLockInput(
                                    isDndLocked = isDndLocked,
                                    isPrivilegeActive = isPrivilege,
                                    isLockShowing = LockWindowManager.isLocked,
                                    isDesktopReturnGrace = LockWindowManager.isInDesktopReturnGrace(now),
                                    hasTemporaryAccessForTarget = hasTemporaryAccessForTarget,
                                    presenceConfirmed = true
                                )
                            )
                            if (canLock) {
                                Log.i("PresenceDiag", "[PRESENCE_ACTION] action=LOCK pkg=$candidate reason=FIRST_PRESENCE_CONFIRMED")
                                triggerPresenceLock(candidate, now)
                                return
                            }
                        }
                    }
                }
            }
        } else {
            if (mPresenceTracker.state != PresenceTrackerState.NONE) {
                mPresenceTracker.reset("LOCK_SHOWING_SESSION_RESET")
            }
        }

        val detectedTopPackage = getTopPackageName()
        val forcedRelockTargetPackage = LockWindowManager.resolveForcedRelockTargetPackage(this, now)
        val canUseTempRelockFallback = shouldUseTemporaryRelockFallback(
            detectedTopPackage = detectedTopPackage,
            forcedRelockTargetPackage = forcedRelockTargetPackage,
            now = now
        )
        var resolvedPackageName = detectedTopPackage
            ?: if (canUseTempRelockFallback) forcedRelockTargetPackage else null

        var isMiniWindowOverridden = false
        // 🚀 [NEW] Double-layer window matching:
        // If Accessibility is enabled, and a blacklisted app is actually visible on screen (e.g. as a small window/PIP),
        // we override resolvedPackageName with that blacklisted package BEFORE the null return check!
        if (CoreService.isServiceActive && !LockWindowManager.isInDesktopReturnGrace(now)) {
            val visiblePkgs = CoreService.lastVisiblePackagesFromWindows
            val visibleBlacklistedPkg = visiblePkgs.firstOrNull { pkg ->
                pkg != this.packageName &&
                    mPresenceTracker.isPhysicallyVisible(pkg, visiblePkgs) &&
                    SegmentBlacklist.shouldLock(this, pkg)
            }
            if (visibleBlacklistedPkg != null) {
                resolvedPackageName = visibleBlacklistedPkg
                isMiniWindowOverridden = true
                Log.d(TAG, "[MiniWindowOverrider] Overriding resolvedPackageName with visible blacklisted small window: $resolvedPackageName")
            }
        }

        if (resolvedPackageName == null) {
            // 🛡️ [Finding 1 修复] 边界候选主动拉取：
            // 当常规 resolver 得到 null（如用户在微信中静止，UsageStats 暂时无解析结果），
            // 且正处于规则边沿时，主动检查 foregroundSessionTracker.currentSession
            if (checkRuleBoundaryCandidateLock(
                    now = now,
                    resolvedPackageName = null,
                    isCarrierPackage = false,
                    isRecentAppsOrLauncher = false,
                    currentSeg = currentSeg,
                    isPrivilege = isPrivilege,
                    isDndLocked = isDndLocked,
                    isKeyguardLocked = isKeyguardLocked,
                    isScreenInteractive = isScreenInteractive,
                    forceCheckThisCycle = forceCheckThisCycle,
                    leftGap = leftGap,
                    endedRest = endedRest
                )
            ) {
                return
            }

            Log.d(TAG, "[checkForegroundApp] No package detected, skipping blocking check")
            // [Diagnostics-only] emit NoLock report for unresolvable foreground
            recordFgNoLock(
                cycle = currentForegroundCycle,
                effectivePkg = null,
                effectiveBlacklisted = null,
                businessReason = "NO_FOREGROUND_RESOLVED",
                allowanceState = "UNKNOWN",
                guardPassed = null,
                visibilityPassed = null
            )
            return
        }
        val packageName = resolvedPackageName
        val isCarrierPackage =
            CreditManager.isLauncher(packageName) ||
                packageName == "com.vivo.upslide" ||
                packageName == "com.vivo.launcher" ||
                packageName == "com.bbk.launcher2"
        var fallbackCandidatePackage = if (isCarrierPackage) {
            resolveBlockedCandidateFromCarrier(
                now = now,
                isDndLocked = isDndLocked,
                isPrivilege = isPrivilege
            )
        } else {
            null
        }

        // 🚀 [NEW] Real-time window verification of homeClickPendingPackage:
        // If the pending package that we are suppressing is actually visible on screen right now
        // (detected via CoreService's live window set), then it's a real visible window (e.g. small window/PIP),
        // not a stale UsageStats lag event! In this case, we MUST clear the pending suppression immediately
        // so that the block is not bypassed, and the lock screen remains active.
        if (CoreService.isServiceActive && !LockWindowManager.isInDesktopReturnGrace(now)) {
            val pendingPkg = LockWindowManager.homeClickPendingPackage
            if (pendingPkg != null && mPresenceTracker.isPhysicallyVisible(pendingPkg, CoreService.lastVisiblePackagesFromWindows)) {
                Log.w(TAG, "[Anti-Deadlock] Pending pkg $pendingPkg is physically visible on screen, clearing suppression!")
                LockWindowManager.clearHomeClickPendingPackage()
            }
        }

        // 🚀 [Anti-Deadlock] Suppress fallback if user explicitly returned to Home.
        // While we are still on launcher carrier, keep suppressing the same pending package.
        // This prevents stale UsageStats from relocking the user on desktop.
        if (fallbackCandidatePackage != null && LockWindowManager.homeClickPendingPackage == fallbackCandidatePackage) {
            Log.d(TAG, "[Anti-Deadlock] Suppressing carrier fallback for $fallbackCandidatePackage because user already clicked Home")
            fallbackCandidatePackage = null
        }

        var effectivePackageName = if (isMiniWindowOverridden) resolvedPackageName else (fallbackCandidatePackage ?: packageName)

        // 🚀 [回到桌面宽限] 非桌面 → 桌面 过渡时启动 3s 小窗锁宽限
        // （覆盖系统 Home 键/手势返回桌面；锁屏"回到桌面"按钮已在 LockWindowManager 内启动；
        // 已在宽限期内则不续期，防止桌面/误报抖动无限延长宽限）
        val isEffLauncher = CreditManager.isLauncher(effectivePackageName)
        val isCarrierOrRecentAppsOrLauncher = isCarrierPackage ||
            isEffLauncher ||
            effectivePackageName.contains("launcher") ||
            effectivePackageName.contains("recents")

        if (!CreditManager.isLauncher(effectivePackageName)) {
            LockWindowManager.failPendingReturnDesktopExitForForeground(effectivePackageName, mLastTopEvidence.name)
        }

        // 🛡️ [Finding 3 修复] 边界候选主动抢占必须发生在“更新桌面状态与开启桌面 Grace”之前：
        // 当常规 resolver 得到 Launcher/Carrier/Recents（如 Vivo 等 ROM 在用户静止停留在微信时误报桌面 launcher 或 upslide），
        // 且正处于规则边沿时，必须优先拉取 Session 进行抢占检查，绝不能被本轮误报建立的 3s 桌面 Grace 拦截！
        if (isCarrierOrRecentAppsOrLauncher) {
            if (checkRuleBoundaryCandidateLock(
                    now = now,
                    resolvedPackageName = effectivePackageName,
                    isCarrierPackage = isCarrierPackage,
                    isRecentAppsOrLauncher = isCarrierOrRecentAppsOrLauncher,
                    currentSeg = currentSeg,
                    isPrivilege = isPrivilege,
                    isDndLocked = isDndLocked,
                    isKeyguardLocked = isKeyguardLocked,
                    isScreenInteractive = isScreenInteractive,
                    forceCheckThisCycle = forceCheckThisCycle,
                    leftGap = leftGap,
                    endedRest = endedRest
                )
            ) {
                return
            }
        }

        // 🚀 [回到桌面宽限] 非桌面 → 桌面 过渡时启动 3s 小窗锁宽限
        // （覆盖系统 Home 键/手势返回桌面；锁屏"回到桌面"按钮已在 LockWindowManager 内启动；
        // 已在宽限期内则不续期，防止桌面/误报抖动无限延长宽限）
        if (isEffLauncher && !lastEffectiveWasLauncher && !LockWindowManager.isInDesktopReturnGrace(now)) {
            LockWindowManager.setDesktopReturnGrace(now)
            mPresenceTracker.onDesktopReturn(now)
        } else if (LockWindowManager.isInDesktopReturnGrace(now) && mPresenceTracker.state == PresenceTrackerState.CONFIRMED_VISIBLE) {
            mPresenceTracker.onDesktopReturn(now)
        }
        lastEffectiveWasLauncher = isEffLauncher

        if (activeRestrictionTarget == null &&
            applyAutomaticRestrictionRelease(now, currentSeg, effectivePackageName, isPrivilege)
        ) {
            return
        }

        val temporarySnapshot = LockWindowManager.getTemporaryAccessSnapshot(this, now)

        LockWindowManager.recordForegroundDetection(effectivePackageName)
        // 🚀 [Unified Expiry] 通知 ExpiryCoordinator 进行桌面等待状态机监听
        ExpiryCoordinator.onForegroundDetected(this@MonitorService, effectivePackageName)

        // Track last non-launcher app for privilege desktop streak
        if (!CreditManager.isLauncher(effectivePackageName) && effectivePackageName != this@MonitorService.packageName) { LockWindowManager.privilegeLastNonLauncherPkg = effectivePackageName }

        // ⏱️ [规则 A] 桌面累计计时器：临时解锁期间连续桌面满 35 秒就锁
        if (LockWindowManager.checkAndHandleDesktopStreakLock(this, effectivePackageName)) {
            return
        }

        if (effectivePackageName == this.packageName) {
            foregroundGuardFallbackGate.reset()
            return
        }

        if (fallbackCandidatePackage != null) {
            LogExporter.logEvent(
                "MonitorService",
                "[桌面回退] carrier=$packageName, candidate=$fallbackCandidatePackage, deltaMs=${now - mLastTopUsageStatTs}"
            )
        }

        if (PwaBypassManager.shouldBypassTopPackage(this, effectivePackageName)) {
            LogExporter.logEvent("MonitorService", "[PWA绕过] skip lock for pkg=$effectivePackageName")
            foregroundGuardFallbackGate.reset()
            // [Diagnostics-only] emit NoLock for PWA bypass
            recordFgNoLock(
                cycle = currentForegroundCycle,
                effectivePkg = effectivePackageName,
                effectiveBlacklisted = CreditManager.shouldBlockApp(this, effectivePackageName, ""),
                businessReason = "PWA_BYPASS",
                allowanceState = "PWA_BYPASS",
                guardPassed = true,
                visibilityPassed = null
            )
            return
        }
        
        // 🚀 [Optimization] State Preservation
        // If already locked on this package, do NOTHING (save CPU/Battery).
        // But verify the lock screen window is still actually showing — OEMs like Vivo
        // may silently remove overlay windows, leaving isLocked=true but the view detached.
        // In that case, fall through to re-establish the lock.
        if (LockWindowManager.isLocked && effectivePackageName == currentLockedPkg) {
            if (!LockWindowManager.isLockScreenViewAttached()) {
                Log.d(TAG, "[StatePreservation] Lock view detached for $effectivePackageName, allowing re-lock")
            } else {
                mLastBusinessBlockStateByPackage[effectivePackageName] = BusinessBlockState.BLOCK_CONSUMED
                // [Diagnostics-only] emit NoLock for already-attached lock
                recordFgNoLock(
                    cycle = currentForegroundCycle,
                    effectivePkg = effectivePackageName,
                    effectiveBlacklisted = CreditManager.shouldBlockApp(this, effectivePackageName, ""),
                    businessReason = "LOCK_ALREADY_ATTACHED",
                    allowanceState = "LOCK_ATTACHED",
                    guardPassed = true,
                    visibilityPassed = true
                )
                return
            }
        }
        
        // 🚀 [Rule] Explicitly ALLOW Recent Apps / Launcher / SystemUI / Carriers
        if (isCarrierOrRecentAppsOrLauncher) {
             if (CreditManager.isLauncher(effectivePackageName)) {
                 LockWindowManager.confirmPendingReturnDesktopExit(
                     evidence = "FOREGROUND_CONFIRMED source=${mLastTopEvidence.name} package=$effectivePackageName"
                 )
                 // This branch has no foreground diagnostic outcome, so explicitly close the
                 // previous logged state and emit the sole desktop transition when applicable.
                 emitForegroundStateClosureOrTransition(
                     packageName = effectivePackageName,
                     stateKey = "$effectivePackageName|DESKTOP",
                     hasFullDiagnostic = false,
                     nowMs = now
                 )
             }
             // Explicitly RETURN to prevent any blocking
             foregroundGuardFallbackGate.reset()
             return
        }

        // 🚀 [Anti-Deadlock] 仅在“真实前台应用”命中同 pending 包时释放抑制。
        // 目的：桌面载体回放旧包名时持续抑制；用户真正再次进入该应用时立即恢复拦截。
        if (LockWindowManager.homeClickPendingPackage != null &&
            LockWindowManager.homeClickPendingPackage == effectivePackageName &&
            !isCarrierPackage &&
            fallbackCandidatePackage == null) {
            val pendingSetAt = LockWindowManager.getHomeClickPendingSetAt()
            val pendingAgeMs = LockWindowManager.getHomeClickPendingAgeMs(now)
            val hasForegroundEventAfterPending =
                pendingSetAt > 0L &&
                    mLastForegroundEventTs >= pendingSetAt &&
                    (mLastBackgroundEventTs <= 0L || mLastBackgroundEventTs < mLastForegroundEventTs)
            val shouldReleasePending = shouldReleaseHomePendingForSamePackage(
                evidenceSource = mLastTopEvidence.name,
                pendingAgeMs = pendingAgeMs,
                hasForegroundEventAfterPending = hasForegroundEventAfterPending
            )
            if (shouldReleasePending) {
                Log.d(
                    TAG,
                    "[Anti-Deadlock] Real foreground re-entry detected for pending pkg=$effectivePackageName, source=${mLastTopEvidence.name}, pendingAge=${pendingAgeMs}ms, clearing pending suppression"
                )
                LockWindowManager.clearHomeClickPendingPackage()
            } else {
                Log.d(
                    TAG,
                    "[Anti-Deadlock] Keep pending suppression for pkg=$effectivePackageName, source=${mLastTopEvidence.name}, pendingAge=${pendingAgeMs}ms, fgAfterPending=$hasForegroundEventAfterPending"
                )
            }
        }

        // 🚀 [Anti-Deadlock] 如果用户导航到了不同的应用（非pending包），说明已离开被阻止的应用
        if (LockWindowManager.homeClickPendingPackage != null && LockWindowManager.homeClickPendingPackage != effectivePackageName) {
            Log.d(TAG, "[Anti-Deadlock] User navigated away from pending pkg, clearing homeClickPendingPackage=${LockWindowManager.homeClickPendingPackage}")
            LockWindowManager.clearHomeClickPendingPackage()
        }

        // 🛡️ [Foreground Guard] 仅"前台证据充分"时才允许触发锁机。
        // 目的：避免黑名单应用仅在后台播放音乐/音频时被误判为前台并触发锁机。
        // 【Phase 3 解耦】名单命中语义唯一来源：SegmentBlacklist.isSubjectToBlock
        val isBlacklisted = SegmentBlacklist.isSubjectToBlock(this, currentSeg, effectivePackageName)
        if (isBlacklisted && effectivePackageName != mLastBlacklistedForegroundPkg) {
            mLastBlacklistedForegroundPkg = effectivePackageName
        }
        val allowByFallback = fallbackCandidatePackage != null
        val forceRelockAfterTemporaryAccess =
            isBlacklisted && LockWindowManager.shouldForceRelockAfterTemporaryAccess(now)
        // 🛡️ [P0 Fix] forcePostTempAccessRelock 必须限定目标包。
        // 原始逻辑：只要 flag=true 就绕过守卫，对任何应用都生效。
        // 问题：临时访问 com.luna.music 到期后，用户已切到 ChatGPT，
        // flag 仍为 true，导致 com.luna.music 被 USAGE_STATS 误锁。
        // 修复：只对临时访问的目标包生效（使用 clear 前保存的包名）。
        val forcePostTempAccessRelock = LockWindowManager.temporaryAccessFinalRelockNeeded &&
            LockWindowManager.temporaryAccessFinalRelockTargetPkg != null &&
            effectivePackageName.equals(
                LockWindowManager.temporaryAccessFinalRelockTargetPkg, ignoreCase = true
            )
        // 🚀 [Fix] 锁屏 View 已被系统移除但 isLocked 仍为 true 时，
        // 绕过前台守卫直接重新锁屏，避免长时间无锁屏的"空窗期"。
        val isDetachedRelock = LockWindowManager.isLocked &&
            effectivePackageName == currentLockedPkg &&
            !LockWindowManager.isLockScreenViewAttached()
        val guardResult = shouldEnforceLockForCurrentTop(effectivePackageName, now)

        // 🚀 [提前计算最终锁策略业务状态 (Finding 2)]
        // 解决跨规则边界与时间窗闭合判定：完整评估 DND、应用时间窗（App Window）、特权、白名单与临时解锁状态
        val isSafe = CreditManager.isLauncher(effectivePackageName) || 
                 effectivePackageName == "com.android.settings" ||
                 CreditManager.isInputMethod(this, effectivePackageName) ||
                 // 系统导航 / SystemUI 属于系统组件，永不拦截（红线，见 SegmentBlacklist.isHardExempt）
                 effectivePackageName.contains("systemui", ignoreCase = true) ||
                 effectivePackageName.equals("com.vivo.systemuiplugin", ignoreCase = true)

        val isAppWindowBlocked = if (AppWindowManager.isManaged(this, effectivePackageName)) {
            !AppWindowManager.isAppAllowed(this, effectivePackageName)
        } else {
            false
        }
        val isBusinessBlocked = calculateIsBusinessBlocked(
            pkg = effectivePackageName,
            currentSeg = currentSeg,
            isPrivilege = isPrivilege,
            isDndLocked = isDndLocked
        )

        // 🛡️ [Keyguard 反证生命周期复核]
        if (isKeyguardLocked || !isScreenInteractive) {
            foregroundSessionTracker.onKeyguardLocked(now)
            foregroundGuardFallbackGate.reset()
        }
        val hasActiveForegroundSession = foregroundSessionTracker.isSessionActiveFor(effectivePackageName)

        val sustainedForegroundFallbackEligible =
            isBusinessBlocked && isSustainedForegroundFallbackEligible(
                evidenceSource = mLastTopEvidence.name,
                selectedPackage = effectivePackageName,
                cachedPackage = mLastTopPackage,
                hasActiveForegroundSession = hasActiveForegroundSession
            )
        val forceGuardFallback = foregroundGuardFallbackGate.shouldForce(
            packageName = effectivePackageName,
            nowMs = now,
            eligible = sustainedForegroundFallbackEligible,
            guardPassed = guardResult
        )
        if (forceGuardFallback) {
            Log.w(
                TAG,
                "[ForegroundGuard] Force lock after 60s sustained CACHE/HISTORY/SESSION uncertainty: pkg=$effectivePackageName"
            )
        }

        // 🛡️ [Rule Boundary Lock] 规则从 ALLOW -> BLOCK 发生业务边沿跃迁（Finding 1 & 2）
        // 1. 解耦前台守卫结果，纯粹基于业务状态评估 ALLOW -> BLOCK 边沿；
        // 2. 状态机：ALLOW -> BLOCK_PENDING -> BLOCK_CONSUMED；
        //    仅当锁屏真正成功执行交付后才置为 BLOCK_CONSUMED，被瞬时宽限拒绝时保持 BLOCK_PENDING 支持次轮立即重试。
        val previousBlockState = mLastBusinessBlockStateByPackage[effectivePackageName] ?: BusinessBlockState.ALLOWED
        val isBusinessAllowToBlock = if (!isBusinessBlocked) {
            mLastBusinessBlockStateByPackage[effectivePackageName] = BusinessBlockState.ALLOWED
            false
        } else {
            val isEdge = (previousBlockState == BusinessBlockState.ALLOWED || previousBlockState == BusinessBlockState.BLOCK_PENDING)
            if (isEdge) {
                mLastBusinessBlockStateByPackage[effectivePackageName] = BusinessBlockState.BLOCK_PENDING
            }
            isEdge
        }

        val allowByRuleBoundary = RuleBoundaryLockGate.shouldAllowBoundaryLock(
            RuleBoundaryLockDecisionInput(
                isBlockedNow = isBusinessBlocked,
                isBusinessAllowToBlock = isBusinessAllowToBlock,
                hasActiveForegroundSession = hasActiveForegroundSession,
                isKeyguardLocked = isKeyguardLocked,
                isScreenInteractive = isScreenInteractive
            )
        )

        if (allowByRuleBoundary) {
            Log.w(
                TAG,
                "[ForegroundGuard] Allow lock by Rule Boundary (ALLOW->BLOCK) with active session: " +
                "pkg=$effectivePackageName, sessionOpenedAt=${foregroundSessionTracker.currentSession?.openedAtMs}"
            )
        }

        val passedForegroundGuard = allowByFallback || forceRelockAfterTemporaryAccess ||
            forcePostTempAccessRelock || isDetachedRelock || isMiniWindowOverridden || guardResult || forceGuardFallback || allowByRuleBoundary
        // [Diagnostics-only] record guard result on cycle
        currentForegroundCycle?.recordGuardResult(passed = passedForegroundGuard, reason = mLastTopEvidence.name)

        // 诊断日志压缩
        if (isBlacklisted) {
            val bypassKey = FgBypassStableKey(
                pkg = effectivePackageName,
                guard = guardResult,
                fallback = allowByFallback,
                forceRelock = forceRelockAfterTemporaryAccess,
                forcePost = forcePostTempAccessRelock,
                detached = isDetachedRelock,
                passed = passedForegroundGuard,
                src = mLastTopEvidence.name,
                a11y = CoreService.isServiceActive,
                inA11yWindows = CoreService.lastVisiblePackagesFromWindows.contains(effectivePackageName)
            )
            if (DefaultLogDebugConfig.foregroundRaw) {
                fgBypassCompressor.observe(bypassKey, now).forEach { msg ->
                    LogExporter.logEvent(TAG, msg)
                }
            }
        }

        if (!passedForegroundGuard) {
            if (isBlacklisted) {
                Log.d(TAG, "[ForegroundGuard] Blacklisted app $effectivePackageName failed FG guard, requesting fast 150ms recheck")
                mRequireFastRecheck = true
            }
            // [Diagnostics-only] emit NoLock for FG guard rejection
            currentForegroundCycle?.recordGuardResult(passed = false, reason = "FOREGROUND_GUARD_REJECTED")
            recordFgNoLock(
                cycle = currentForegroundCycle,
                effectivePkg = effectivePackageName,
                effectiveBlacklisted = isBlacklisted,
                businessReason = "FOREGROUND_GUARD_REJECTED",
                allowanceState = if (isFreeTime) "FREE_TIME_ACTIVE"
                else if (isPrivilege) "WEEKLY_PRIVILEGE_ACTIVE"
                else if (isDndLocked) "ALLOW_NONE"
                else "ALLOW_OK",
                guardPassed = false,
                visibilityPassed = null
            )
            return
        }
        if (forceRelockAfterTemporaryAccess || forcePostTempAccessRelock || isDetachedRelock) {
            if (!isAppActuallyVisible(effectivePackageName, now, 2000L)) {
                Log.d(TAG, "[FG] Skip lock despite bypass: pkg=" + effectivePackageName)
                // [Diagnostics-only] emit NoLock for visibility-bypass failure
                currentForegroundCycle?.recordVisibilityResult(passed = false, reason = "BYPASS_VISIBILITY_REJECTED")
                recordFgNoLock(
                    cycle = currentForegroundCycle,
                    effectivePkg = effectivePackageName,
                    effectiveBlacklisted = isBlacklisted,
                    businessReason = "BYPASS_VISIBILITY_REJECTED",
                    allowanceState = "ALLOW_NONE",
                    guardPassed = true,
                    visibilityPassed = false
                )
                return
            }
            Log.d(TAG, "[FG] Bypass verified visible: pkg=" + effectivePackageName)
            currentForegroundCycle?.recordVisibilityResult(passed = true, reason = "VISIBLE")
        }

        val isRealBlacklist = if (currentSeg != null && currentSeg.listApps != null) {
            SegmentBlacklist.isInEffectiveList(this, currentSeg, effectivePackageName)
        } else {
            CreditManager.getBlacklistApps(this).any { it.equals(effectivePackageName, ignoreCase = true) }
        }
        val shouldBlock = isBusinessBlocked

        // 诊断日志压缩
        if (isBlacklisted) {
            val blockKey = ShouldBlockStableKey(
                pkg = effectivePackageName,
                isBlacklisted = isBlacklisted,
                isDndLocked = isDndLocked,
                isFreeTime = isFreeTime,
                shouldBlock = shouldBlock,
                isManaged = AppWindowManager.isManaged(this, effectivePackageName),
                isAllowed = AppWindowManager.isAppAllowed(this, effectivePackageName)
            )
            if (DefaultLogDebugConfig.foregroundRaw) {
                shouldBlockCompressor.observe(blockKey, now).forEach { msg ->
                    LogExporter.logEvent(TAG, msg)
                }
            }
        }

        if (!shouldBlock) {
            mPreviouslyBlockedPackages.remove(effectivePackageName)
            // 🚀 [Billing Logic - MOVED]
            // Only deduct minutes if the app is BLACKLISTED and NOT BLOCKED (e.g. consuming quota/free time)
            // If the user is staring at a lock screen, we should NOT charge them.
            // 🟢 [Fix] Billing Bug: Prevent billing if it is Free Time (Rest) or Gap (isInSegment=false)
             if (SegmentBlacklist.shouldBill(this, currentSeg, effectivePackageName, isInSegment, isFreeTime, mLastUsedMinuteTime, now)) {
                 mLastUsedMinuteTime = now
                 CreditManager.addUsedMinutes(this, 1)
                 Log.d(TAG, "Billing 1 minute for $effectivePackageName (Allowed)")
             }

            // 🚀 [FIX] 普通锁机界面只能通过按钮解锁，不会因为白名单应用而自动 dismiss
            // 锁屏界面只有在以下情况才能解锁：
            // 1. 用户点击"回到桌面"按钮
            // 2. 用户点击"申请休息"按钮（强制锁机期间不可用）
            // 移除了自动 dismiss 逻辑，避免闪屏
            // 
            // ⚠️ ERROR CORRECTION:
            // "移除了自动 dismiss 逻辑" (Removed auto dismiss) IS WRONG for normal usage!
            // If I switch from a Blocked App to an Allowed App (e.g. Settings, or Back to Home),
            // The Lock Screen MUST be removed, otherwise it stays on top of the Allowed App!
            // The user report "IronWill enters lock screen" confirms this. 
            // IronWill is allowed, but if previous app was blocked, lock screen persists.

            if (isBlacklisted) {
                EventLog.suspect(
                    traceId = currentTraceId,
                    pkg = effectivePackageName,
                    issue = if (isFreeTime) "FREE_TIME_BYPASS" else if (isPrivilege) "PRIVILEGE_BYPASS" else if (isSafe) "SAFE_APP_BYPASS" else "SHOULD_BLOCK_FALSE",
                    a11y = CoreService.isServiceActive,
                    guard = passedForegroundGuard,
                    vis = false,
                    durationMs = 0,
                    reason = "Blacklisted app $effectivePackageName passed shouldBlock check: isDndLocked=$isDndLocked isFreeTime=$isFreeTime isPrivilege=$isPrivilege"
                )

                // [Diagnostics-only] emit NoLock with the exact existing-path reason.
                // Diagnostic recommendation (LOCK_BY_DIAGNOSTIC_MAJORITY) must NEVER
                // override the business NO_LOCK decision.
                val noLockReason = when {
                    isSafe -> "SAFE_APP_BYPASS"
                    isPrivilege -> "WEEKLY_PRIVILEGE_ACTIVE"
                    isFreeTime -> "FREE_TIME_BYPASS"
                    isDndLocked -> "REST_OR_APP_WINDOW_ALLOW"
                    temporarySnapshot.accessRemainingSeconds > 0L -> "TEMPORARY_ACCESS_ACTIVE"
                    else -> "ON_BLACKLIST_HIT_NOT_STRICT"
                }
                val noLockAllowance = when {
                    isSafe -> "SAFE_APP_BYPASS"
                    isPrivilege -> "WEEKLY_PRIVILEGE_ACTIVE"
                    isFreeTime -> "FREE_TIME_ACTIVE"
                    temporarySnapshot.accessRemainingSeconds > 0L -> "TEMP_ACCESS_ACTIVE"
                    else -> "ALLOW_OK"
                }
                recordFgNoLock(
                    cycle = currentForegroundCycle,
                    effectivePkg = effectivePackageName,
                    effectiveBlacklisted = true,
                    businessReason = noLockReason,
                    allowanceState = noLockAllowance,
                    guardPassed = passedForegroundGuard,
                    visibilityPassed = null
                )
            }

            return
        }

        // 🛡️ Reset counter as we found a target app
        safeAppCounter = 0
        mPreviouslyBlockedPackages.add(effectivePackageName)
        mForceRuleBoundaryCheck = false

        // 5. Blocking (Status text will be set by LockWindowManager.updateStatus)
        // We are blocking, so we DO NOT bill the user.
        // 🚀 [FIX] Pass isBlacklist flag for faster response

        if (isBlacklisted) {
            val pm = packageManager
            val appLabel = try {
                pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
            } catch (e: Exception) { packageName }

            val effectiveAppLabel = if (effectivePackageName == packageName) {
                appLabel
            } else {
                try {
                    pm.getApplicationLabel(pm.getApplicationInfo(effectivePackageName, 0)).toString()
                } catch (e: Exception) {
                    effectivePackageName
                }
            }

            Log.d(TAG, "[checkForegroundApp] Blacklist app detected: $effectivePackageName ($effectiveAppLabel). Calling onBlacklistHit...")
            val isManaged = AppWindowManager.isManaged(this, effectivePackageName)
            val isAllowed = if (isManaged) AppWindowManager.isAppAllowed(this, effectivePackageName) else true
            val shouldStrictLock = LockWindowManager.onBlacklistHit(
                this,
                effectivePackageName,
                createLockScreen = false
            )
            Log.d(TAG, "[checkForegroundApp] onBlacklistHit returned: $shouldStrictLock")
            
            if (shouldStrictLock) {
                Log.w("IronWill_Lock", "🔴 Triggering Lock Screen. Reason: Blacklisted App ($effectivePackageName) & Strict Lock Required")
                val cycleSnapshot = currentForegroundCycle
                val fgReportId = cycleSnapshot?.reportId
                recordFgRequestLock(
                    cycle = cycleSnapshot,
                    effectivePkg = effectivePackageName,
                    effectiveBlacklisted = true,
                    businessReason = "BLACKLIST_DND_LOCKED",
                    allowanceState = "ALLOW_NONE",
                    guardPassed = passedForegroundGuard,
                    visibilityPassed = null
                )
                // [WP-1 / T2] Snapshot the live accessibility generation immediately
                // before this owned blacklist showLockScreen call. Passing the exact
                // snapshot lets LockWindowManager classify a later request from a
                // dead generation as stale. Do not derive this inside a posted UI callback.
                val requestGeneration = CoreService.getAccessibilityGeneration()
                Log.d(TAG, "LockReason: blacklist=$isRealBlacklist appWindow=$isAppWindowBlocked pkg=$effectivePackageName")
                LockWindowManager.showLockScreen(
                    "Blocked: $effectivePackageName",
                    isBlacklist = isRealBlacklist,
                    isAppWindowBlocked = isAppWindowBlocked,
                    blockedAppLabel = effectiveAppLabel,
                    blockedPackageName = effectivePackageName,
                    detectionSource = mLastTopEvidence.name,
                    detectionSnapshot = getDetectionSnapshot(),
                    foregroundReportId = fgReportId,
                    foregroundRequestId = currentTraceId,
                    foregroundRequestAtMs = System.currentTimeMillis(),
                    requestGeneration = requestGeneration
                )
                currentLockedPkg = effectivePackageName
            }
        } else {
             val pm = packageManager
             val appLabel = try {
                 pm.getApplicationLabel(pm.getApplicationInfo(effectivePackageName, 0)).toString()
             } catch (e: Exception) { effectivePackageName }

             Log.w("IronWill_Lock", "🔴 Triggering Lock Screen. Reason: App ($effectivePackageName) is Blocked (Not Safe/Not Free/Blacklisted)")
             val cycleSnapshot2 = currentForegroundCycle
             val fgReportId2 = cycleSnapshot2?.reportId
             recordFgRequestLock(
                 cycle = cycleSnapshot2,
                 effectivePkg = effectivePackageName,
                 effectiveBlacklisted = false,
                 businessReason = "BLOCKED_APP_LOCK",
                 allowanceState = "ALLOW_NONE",
                 guardPassed = passedForegroundGuard,
                 visibilityPassed = null
             )
             // [WP-1 / T2] Snapshot the live accessibility generation immediately
             // before this owned ordinary blocked-app showLockScreen call, mirroring
             // the blacklist branch. Same reasons/metadata; only the generation is added.
             val requestGeneration2 = CoreService.getAccessibilityGeneration()
             Log.d(TAG, "LockReason: blacklist=$isRealBlacklist appWindow=$isAppWindowBlocked pkg=$effectivePackageName")
             LockWindowManager.showLockScreen(
                 "Blocked: $effectivePackageName",
                 isBlacklist = isRealBlacklist,
                 isAppWindowBlocked = isAppWindowBlocked,
                 blockedAppLabel = appLabel,
                 blockedPackageName = effectivePackageName,
                 detectionSource = mLastTopEvidence.name,
                 detectionSnapshot = getDetectionSnapshot(),
                 foregroundReportId = fgReportId2,
                 foregroundRequestId = currentTraceId,
                 foregroundRequestAtMs = System.currentTimeMillis(),
                 requestGeneration = requestGeneration2
             )
             currentLockedPkg = effectivePackageName
        }
    }

    private fun applyAutomaticRestrictionRelease(
        now: Long,
        currentSeg: TimeSegment?,
        targetPackage: String,
        isPrivilege: Boolean
    ): Boolean {
        if (isPrivilege) return false

        // 🚀 [Fix] 锁屏显示时，不因前台检测到桌面而解锁
        // Vivo 会把用户在黑名单应用中不动报成桌面，导致误解锁
        // 只有空档期、时间窗限制消失、或用户主动操作（休息/临时解锁/特权）才解锁
        val isLauncherDetected = CreditManager.isLauncher(targetPackage)
        if (isLauncherDetected && LockWindowManager.isLocked) {
            Log.d(TAG, "[AUTO_RELEASE] Skip: launcher detected while lock screen is showing, target=$targetPackage")
            return false
        }

        val temporarySnapshot = LockWindowManager.getTemporaryAccessSnapshot(this, now)
        val hasActiveTemporaryAccess = temporarySnapshot.accessRemainingSeconds > 0L
        val hasActiveRest = CreditManager.getFreeTimeEnd(this) > now
        val isRawBlacklisted = CreditManager.getBlacklistApps(this)
            .any { it.equals(targetPackage, ignoreCase = true) }
        val isDndBlocked = currentSeg != null && (if (currentSeg.listApps != null) SegmentBlacklist.isInEffectiveList(this, currentSeg, targetPackage) else isRawBlacklisted)
        val isAppWindowBlocked = AppWindowManager.isManaged(this, targetPackage) &&
            !AppWindowManager.isAppAllowed(this, targetPackage)
        val decision = resolveAutomaticRestrictionRelease(
            isDndBlocked = isDndBlocked,
            isAppWindowBlocked = isAppWindowBlocked,
            hasActiveRest = hasActiveRest,
            hasActiveTemporaryAccess = hasActiveTemporaryAccess,
            hasBlacklistOverlay = LockWindowManager.isBlacklistLock
        )

        if (!decision.settleRest &&
            !decision.settleTemporaryAccess &&
            !decision.dismissBlacklistOverlay
        ) {
            return false
        }

        if (decision.settleRest) {
            endFreeTimeEarly(naturalFreedom = true)
        }
        if (decision.settleTemporaryAccess) {
            LockWindowManager.settleTemporaryAccessForNaturalFreedom(this, now)
        }
        if (decision.dismissBlacklistOverlay) {
            Log.d(TAG, "[AUTO_RELEASE_NATURAL] restrictions cleared naturally, lock overlay unblocked by LockWindowManager")
        }
        return true
    }

    private fun resolveBlockedCandidateFromCarrier(
        now: Long,
        isDndLocked: Boolean,
        isPrivilege: Boolean
    ): String? {
        val candidatePkg = getTopPackageFromUsageStats(now) ?: return null
        if (candidatePkg == packageName) return null
        if (candidatePkg == "com.android.systemui") return null
        if (CreditManager.isLauncher(candidatePkg)) return null

        // Require recent foreground evidence for carrier fallback candidate.
        // This avoids locking when media apps keep updating usage stats in background.
        if (!hasRecentForegroundEvidenceForPackage(candidatePkg, now)) {
            Log.d(TAG, "[CARRIER_FALLBACK] Reject candidate without fresh foreground evidence: $candidatePkg")
            return null
        }

        // 🛡️ [Background Audio Guard] 交叉验证 lastTimeVisible，防止后台音频应用被误判
        if (!isAppActuallyVisible(candidatePkg, now)) {
            Log.d(TAG, "[CARRIER_FALLBACK] Reject candidate not actually visible: $candidatePkg")
            return null
        }

        val candidateDeltaMs = if (mLastTopUsageStatTs > 0L) now - mLastTopUsageStatTs else Long.MAX_VALUE
        val isManaged = AppWindowManager.isManaged(this, candidatePkg)
        val isAppWindowBlocked = isManaged && !AppWindowManager.isAppAllowed(this, candidatePkg)
        val isBlacklisted = SegmentBlacklist.isSubjectToBlock(this, TimeRuleManager.getCurrentSegment(this), candidatePkg)
        val isBlockable = if (isPrivilege) {
            false
        } else {
            (isDndLocked && isBlacklisted) || isAppWindowBlocked
        }

        if (!shouldAdoptCarrierFallbackCandidate(candidateDeltaMs, isBlockable)) {
            return null
        }
        return candidatePkg
    }

    private fun queryForegroundEventsForPackage(
        candidatePkg: String,
        now: Long,
        windowMs: Long = 8000L
    ): PackageForegroundEvents {
        val usm = usageStatsManager ?: return PackageForegroundEvents()
        val events = try {
            usm.queryEvents(now - windowMs, now)
        } catch (e: Exception) {
            Log.w(TAG, "[queryForegroundEventsForPackage] queryEvents failed: ${e.javaClass.simpleName}: ${e.message}")
            return PackageForegroundEvents()
        }
        val event = android.app.usage.UsageEvents.Event()

        val moveToFg = android.app.usage.UsageEvents.Event.MOVE_TO_FOREGROUND
        val moveToBg = android.app.usage.UsageEvents.Event.MOVE_TO_BACKGROUND
        val resumed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) 15 else -1
        val paused = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) 16 else -1
        val stopped = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) 23 else -1

        var lastFgTs = -1L
        var lastBgTs = -1L
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (event.packageName != candidatePkg) continue
            val type = event.eventType
            if (type == moveToFg || type == resumed) {
                lastFgTs = event.timeStamp
            } else if (type == moveToBg || type == paused || type == stopped) {
                lastBgTs = event.timeStamp
            }
        }
        return PackageForegroundEvents(
            lastForegroundAtMs = if (lastFgTs > 0L) lastFgTs else 0L,
            lastBackgroundAtMs = if (lastBgTs > 0L) lastBgTs else 0L
        )
    }

    private fun hasRecentForegroundEvidenceForPackage(
        candidatePkg: String,
        now: Long,
        windowMs: Long = 8000L
    ): Boolean {
        val events = queryForegroundEventsForPackage(candidatePkg, now, windowMs)
        if (events.lastForegroundAtMs <= 0L) return false
        if (events.lastBackgroundAtMs >= events.lastForegroundAtMs) return false
        return (now - events.lastForegroundAtMs) <= windowMs
    }

    internal fun shouldAdoptCarrierFallbackCandidate(
        candidateDeltaMs: Long,
        isBlockable: Boolean,
        freshMs: Long = 4000L
    ): Boolean {
        if (!isBlockable) return false
        if (candidateDeltaMs < 0L) return false
        return candidateDeltaMs <= freshMs
    }

    private fun shouldUseTemporaryRelockFallback(
        detectedTopPackage: String?,
        forcedRelockTargetPackage: String?,
        now: Long
    ): Boolean {
        if (detectedTopPackage != null) {
            return false
        }
        val targetPkg = forcedRelockTargetPackage ?: return false
        if (targetPkg.isBlank()) {
            return false
        }
        if (targetPkg == this.packageName) {
            return false
        }
        if (targetPkg in usageCarrierNoisePackages || CreditManager.isLauncher(targetPkg)) {
            return false
        }
        if (!LockWindowManager.shouldForceRelockAfterTemporaryAccess(now)) {
            return false
        }
        if (!hasRecentForegroundEvidenceForPackage(targetPkg, now, windowMs = 8000L)) {
            return false
        }
        return shouldEnforceLockForCurrentTop(targetPkg, now)
    }

    private fun shouldEnforceLockForCurrentTop(packageName: String, now: Long): Boolean {
        val source = mLastTopEvidence
        if (source == TopPackageEvidence.USAGE_STATS) {
            // 🛡️ [P0 Fix] UsageStats-only 黑名单锁机必须有独立佐证。
            // UsageStats.lastTimeUsed 在后台音频播放时也会更新（如汽水音乐），
            // 不能单独作为"应用进入前台"的可靠证据。
            // 可靠佐证来源：UsageEvents 前台事件 或 Accessibility 窗口切换。
            val hasRecentFgEvidence = hasRecentForegroundEvidenceForPackage(
                candidatePkg = packageName,
                now = now,
                windowMs = USAGE_STATS_FOREGROUND_EVIDENCE_WINDOW_MS
            )
            val a11yAvailable = CoreService.isServiceActive
            val pkgInA11yWindows = CoreService.lastVisiblePackagesFromWindows.contains(packageName)
            // 预计算 usageStatDelta / resolvedDelta 供压缩器 + 后续判定复用
            val usageStatDelta = if (mLastTopUsageStatTs > 0L) now - mLastTopUsageStatTs else -1L
            val resolvedDelta = if (mLastTopResolvedAt > 0L) now - mLastTopResolvedAt else -1L
            val guardDecision = if (!hasRecentFgEvidence && !pkgInA11yWindows) {
                false
            } else {
                shouldAllowUsageStatsBasedLock(hasRecentFgEvidence, usageStatDelta, resolvedDelta)
            }
            // 诊断日志压缩
            val guardKey = FgGuardStableKey(
                pkg = packageName, src = source.name,
                hasRecentFg = hasRecentFgEvidence, pkgInA11y = pkgInA11yWindows,
                decision = guardDecision,
                a11yService = CoreService.isServiceActive
            )
            if (DefaultLogDebugConfig.foregroundRaw) {
                fgGuardCompressor.observe(guardKey, now).forEach { msg ->
                    LogExporter.logEvent(TAG, msg)
                }
            }
            // 状态变化时输出 [EVENT]
            val guardChanged = lastFgGuardPkg != packageName || lastFgGuardDecision != guardDecision
            if (guardChanged) {
                val confidence = when {
                    a11yAvailable && pkgInA11yWindows -> "MEDIUM"
                    hasRecentFgEvidence -> "MEDIUM"
                    else -> "LOW"
                }
                // RB-03: 捕获 3 个诊断字段
                val usageEventsStatus = when {
                    mLastForegroundEventTs > 0L && (now - mLastForegroundEventTs) <= 45000L -> "AVAILABLE"
                    mLastForegroundEventTs > 0L -> "NO_DATA"
                    else -> "NONE"
                }
                val accessibilityWindowsCount = CoreService.lastVisiblePackagesFromWindows.size
                val conflictReason = when {
                    !hasRecentFgEvidence && !pkgInA11yWindows -> "foreground_guard_rejected"
                    !hasRecentFgEvidence -> "a10y_event_pkg_mismatch"
                    !guardDecision -> "usage_stats_no_data"
                    else -> ""
                }
                if (DefaultLogDebugConfig.foregroundRaw) {
                    EventLog.fgDecision(
                        traceId = currentTraceId,
                        selectedPkg = packageName,
                        selectedSrc = source.name,
                        confidence = confidence,
                        conflict = false,
                        a11yAvailable = a11yAvailable,
                        eventsPkg = if (mLastTopEvidence == TopPackageEvidence.EVENTS) mLastTopPackage else null,
                        eventsAgeMs = if (mLastForegroundEventTs > 0) now - mLastForegroundEventTs else -1L,
                        usmPkg = try { getTopPackageFromUsageStats(now) } catch (e: Exception) { null },
                        usmAgeMs = if (mLastTopUsageStatTs > 0) now - mLastTopUsageStatTs else -1L,
                        a11yPkg = if (CoreService.lastVisiblePackagesFromWindows.isNotEmpty()) CoreService.lastVisiblePackagesFromWindows.firstOrNull() else null,
                        cachePkg = mLastTopPackage,
                        cacheAgeMs = if (mLastTopResolvedAt > 0) now - mLastTopResolvedAt else -1L,
                        cacheUsedForDecision = false,
                        usageEventsStatus = usageEventsStatus,
                        accessibilityWindowsCount = accessibilityWindowsCount,
                        conflictReason = conflictReason
                    )
                }
                lastFgGuardPkg = packageName
                lastFgGuardDecision = guardDecision
            }
            if (!hasRecentFgEvidence && !pkgInA11yWindows) {
                Log.w(
                    TAG,
                    "[ForegroundGuard] REJECT strict lock: USAGE_STATS-only without corroboration. " +
                    "pkg=$packageName, usageDelta=${usageStatDelta}ms. " +
                    "Need UsageEvents or Accessibility to confirm foreground entry."
                )
                return false
            }

            val allowUsageStatsLock = shouldAllowUsageStatsBasedLock(
                hasRecentForegroundEvidence = hasRecentFgEvidence,
                usageStatDeltaMs = usageStatDelta,
                resolvedDeltaMs = resolvedDelta
            )
            if (allowUsageStatsLock) {
                Log.d(
                    TAG,
                    "[ForegroundGuard] Allow lock by usage-stats+foreground-evidence: pkg=$packageName, usageDelta=${usageStatDelta}ms"
                )
                return true
            }
            Log.d(
                TAG,
                "[ForegroundGuard] Skip lock by weak usage-stats evidence: " +
                "pkg=$packageName, hasRecentFgEvidence=$hasRecentFgEvidence, " +
                "usageDelta=$usageStatDelta, resolvedDelta=$resolvedDelta, decision=reject"
            )
            return false
        }

        if (source != TopPackageEvidence.EVENTS) {
            // 🛡️ [P0 Fix v2] 非 EVENTS 来源统一要求包级别佐证
            val hasRecentFgEvidence = hasRecentForegroundEvidenceForPackage(
                candidatePkg = packageName, now = now,
                windowMs = USAGE_STATS_FOREGROUND_EVIDENCE_WINDOW_MS
            )
            val pkgInA11yWindows = CoreService.lastVisiblePackagesFromWindows.contains(packageName)
            val a11yAvailable = CoreService.isServiceActive
            // 预计算 isAppActuallyVisible 结果用于 decision
            val willRejectImmediately = !hasRecentFgEvidence && !pkgInA11yWindows
            val guardDecision = if (willRejectImmediately) {
                false
            } else {
                isAppActuallyVisible(packageName, now, windowMs = 5000L)
            }
            // 诊断日志压缩
            val guardKey = FgGuardStableKey(
                pkg = packageName, src = source.name,
                hasRecentFg = hasRecentFgEvidence, pkgInA11y = pkgInA11yWindows,
                decision = guardDecision,
                a11yService = CoreService.isServiceActive
            )
            if (DefaultLogDebugConfig.foregroundRaw) {
                fgGuardCompressor.observe(guardKey, now).forEach { msg ->
                    LogExporter.logEvent(TAG, msg)
                }
            }
            // 状态变化时输出 [EVENT]
            val guardChanged = lastFgGuardPkg != packageName || lastFgGuardDecision != guardDecision
            if (guardChanged) {
                val confidence = when {
                    a11yAvailable && pkgInA11yWindows -> "MEDIUM"
                    hasRecentFgEvidence -> "MEDIUM"
                    else -> "LOW"
                }
                // RB-03: 捕获 3 个诊断字段
                val usageEventsStatus = when {
                    mLastForegroundEventTs > 0L && (now - mLastForegroundEventTs) <= 45000L -> "AVAILABLE"
                    mLastForegroundEventTs > 0L -> "NO_DATA"
                    else -> "NONE"
                }
                val accessibilityWindowsCount = CoreService.lastVisiblePackagesFromWindows.size
                val conflictReason = when {
                    willRejectImmediately -> "foreground_guard_rejected"
                    !guardDecision -> "foreground_not_confirmed"
                    else -> ""
                }
                if (DefaultLogDebugConfig.foregroundRaw) {
                    EventLog.fgDecision(
                        traceId = currentTraceId,
                        selectedPkg = packageName,
                        selectedSrc = source.name,
                        confidence = confidence,
                        conflict = false,
                        a11yAvailable = a11yAvailable,
                        eventsPkg = if (mLastTopEvidence == TopPackageEvidence.EVENTS) mLastTopPackage else null,
                        eventsAgeMs = if (mLastForegroundEventTs > 0) now - mLastForegroundEventTs else -1L,
                        usmPkg = try { getTopPackageFromUsageStats(now) } catch (e: Exception) { null },
                        usmAgeMs = if (mLastTopUsageStatTs > 0) now - mLastTopUsageStatTs else -1L,
                        a11yPkg = if (CoreService.lastVisiblePackagesFromWindows.isNotEmpty()) CoreService.lastVisiblePackagesFromWindows.firstOrNull() else null,
                        cachePkg = mLastTopPackage,
                        cacheAgeMs = if (mLastTopResolvedAt > 0) now - mLastTopResolvedAt else -1L,
                        cacheUsedForDecision = false,
                        usageEventsStatus = usageEventsStatus,
                        accessibilityWindowsCount = accessibilityWindowsCount,
                        conflictReason = conflictReason
                    )
                }
                lastFgGuardPkg = packageName
                lastFgGuardDecision = guardDecision
            }
            if (willRejectImmediately) {
                Log.w(TAG,
                    "[ForegroundGuard] REJECT: $source without corroboration. pkg=$packageName"
                )
                return false
            }
            // 有佐证时, 用 lastTimeVisible 作为额外验证
            val lastVisibleDelta = if (mLastTopUsageStatTs > 0L) now - mLastTopUsageStatTs else -1L
            if (guardDecision) {
                Log.d(TAG,
                    "[ForegroundGuard] Allow lock by $source+corroboration+lastTimeVisible: " +
                    "pkg=$packageName, lastVisibleDelta=${lastVisibleDelta}ms, source=$source"
                )
                return true
            }
            Log.d(TAG, "[ForegroundGuard] Skip lock: pkg=$packageName, source=$source, lastTimeVisible failed")
            return false
        }

        val pkgEvents = queryForegroundEventsForPackage(
            candidatePkg = packageName,
            now = now,
            windowMs = 30_000L
        )
        val fgTs = pkgEvents.lastForegroundAtMs
        val bgTs = pkgEvents.lastBackgroundAtMs
        val lastVisible = queryLastTimeVisibleForPackage(packageName, now)

        val evidence = ForegroundEvidence(
            packageName = packageName,
            foregroundEventAtMs = fgTs,
            backgroundEventAtMs = bgTs,
            lastTimeVisibleMs = lastVisible,
            checkedAtMs = now
        )
        val decision = ForegroundEvidencePolicy.evaluate(evidence)

        if (decision == ForegroundEvidenceDecision.CONFIRMED) {
            Log.d(
                TAG,
                "[ForegroundGuard] Allow lock by EVENTS evidence correlation: " +
                    "pkg=$packageName, fgTs=$fgTs, bgTs=$bgTs, lastVisible=$lastVisible, check=$now"
            )
            return true
        }

        if (decision == ForegroundEvidenceDecision.VISIBLE_DATA_PENDING) {
            Log.d(
                TAG,
                "[ForegroundGuard] EVENTS evidence pending for pkg=$packageName " +
                    "(fgTs=$fgTs, bgTs=$bgTs, lastVisible=$lastVisible, check=$now), requesting fast 150ms recheck"
            )
            mRequireFastRecheck = true
            return false
        }

        Log.d(
            TAG,
            "[ForegroundGuard] Skip lock by EVENTS evidence ($decision): " +
                "pkg=$packageName, fgTs=$fgTs, bgTs=$bgTs, lastVisible=$lastVisible, check=$now"
        )
        return false
    }

    /**
     * 查询指定包名的 UsageStats.lastTimeVisible（无选举副作用）。
     * 若无障碍服务已明确确认该包在当前活动窗口，则直接返回当前时间 [now]。
     */
    private fun queryLastTimeVisibleForPackage(packageName: String, now: Long): Long {
        if (CoreService.isServiceActive && CoreService.lastVisiblePackagesFromWindows.contains(packageName)) {
            return now
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return now // API < 29 无 lastTimeVisible，透传
        }
        val usm = usageStatsManager ?: return 0L
        return try {
            val stats = usm.queryUsageStats(
                UsageStatsManager.INTERVAL_DAILY,
                now - 60_000L,
                now
            )
            val appStat = stats?.firstOrNull { it.packageName == packageName }
            appStat?.lastTimeVisible ?: 0L
        } catch (e: Exception) {
            Log.w(TAG, "[queryLastTimeVisibleForPackage] queryUsageStats failed for $packageName: ${e.message}")
            0L
        }
    }

    /**
     * 通过 UsageStats.lastTimeVisible 验证应用是否确实在屏幕上可见。
     * 后台音频服务会持续更新 lastTimeUsed，但不会更新 lastTimeVisible。
     * @return true 如果 lastTimeVisible 在最近 [windowMs] 内，说明应用确实在前台
     */
    private fun isAppActuallyVisible(
        packageName: String,
        now: Long,
        windowMs: Long = 10_000L
    ): Boolean {
        if (CoreService.isServiceActive) {
            val visible = CoreService.lastVisiblePackagesFromWindows.contains(packageName)
            if (visible) {
                Log.d(TAG, "[isAppActuallyVisible] Verified visible via CoreService active windows: $packageName")
                // [Diagnostics-only] push accessibility snapshot for the verified package
                pushAccessibilitySnapshotToCycle(
                    packageName = packageName,
                    candidates = CoreService.lastVisiblePackagesFromWindows.toList().take(3)
                        .map { ForegroundCandidate(it, "A11Y_WINDOW", System.currentTimeMillis()) },
                    reason = "A11Y_WINDOW_MATCH",
                    status = ForegroundSourceStatus.AVAILABLE
                )
                return true
            }
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            // [Diagnostics-only] Accessibility snapshot is independent of UsageStats.
            // mark UNAVAILABLE for pre-Q so it doesn't get conflated with USAGE_STATS data.
            pushAccessibilitySnapshotToCycle(
                packageName = null,
                candidates = emptyList(),
                reason = "A11Y_UNAVAILABLE_PRE_Q",
                status = ForegroundSourceStatus.UNAVAILABLE
            )
            return true // API < 29 无 lastTimeVisible，不拦截
        }
        val usm = usageStatsManager ?: run {
            // [Diagnostics-only] Accessibility: failed because no USM (visibility check below will use stale assumption)
            pushAccessibilitySnapshotToCycle(
                packageName = null,
                candidates = emptyList(),
                reason = "A11Y_FAILED_NO_USM",
                status = ForegroundSourceStatus.FAILED
            )
            return true // 无 UsageStatsManager 时不拦截
        }
        return try {
            val stats = try {
                usm.queryUsageStats(
                    UsageStatsManager.INTERVAL_DAILY,
                    now - 60_000L,
                    now
                )
            } catch (e: Exception) {
                Log.w(TAG, "[isAppActuallyVisible] queryUsageStats failed: ${e.javaClass.simpleName}: ${e.message}")
                // [Diagnostics-only] Accessibility snapshot stays independent; no overwrite here.
                return true
            }
            val appStat = stats?.firstOrNull { it.packageName == packageName } ?: run {
                // [Diagnostics-only] no app stat found → Accessibility stays at its prior window-based snapshot.
                // Do NOT pollute ACCESSIBILITY with USAGE_STATS data here.
                return true
            }
            val lastVisible = appStat.lastTimeVisible
            val result = if (lastVisible <= 0L) {
                true
            } else {
                (now - lastVisible) <= windowMs
            }
            // [Diagnostics-only] Accessibility snapshot is determined ONLY by CoreService
            // window observation (or its absence). The queryUsageStats().lastTimeVisible result
            // is logged into VisCheckStableKey / EventLog.visDecision for business use but
            // must NEVER be written into the ACCESSIBILITY diagnostic source — that would
            // collapse the three independent sources into a single USAGE_STATS wrapper.
            // If CoreService had reported this package as visible, the early return above
            // already produced the correct ACCESSIBILITY snapshot; if not, we mark it
            // AMBIGUOUS (service active but no window match) or UNAVAILABLE.
            // 诊断日志压缩 + 状态变化时输出 EVENT（仅在 foregroundRaw=true 时输出）
            if (DefaultLogDebugConfig.foregroundRaw) {
                val visKey = VisCheckStableKey(packageName, result, windowMs,
                    a11yWindows = CoreService.lastVisiblePackagesFromWindows.contains(packageName),
                    lastVisibleRefreshed = lastVisible > 0)
                visCheckCompressor.observe(visKey, now).forEach { msg ->
                    LogExporter.logEvent(TAG, msg)
                }
                val reason = when {
                    CoreService.isServiceActive && CoreService.lastVisiblePackagesFromWindows.contains(packageName) -> "A11Y_WINDOW_MATCH"
                    result -> "VISIBLE"
                    lastVisible <= 0L -> "NO_LAST_VISIBLE_DATA"
                    (now - lastVisible) > windowMs -> "VISIBLE_EVIDENCE_STALE"
                    else -> "NOT_VISIBLE"
                }
                EventLog.visDecision(
                    traceId = currentTraceId,
                    pkg = packageName,
                    result = result,
                    lastVisibleDeltaMs = if (lastVisible > 0) now - lastVisible else -1L,
                    windowMs = windowMs,
                    a11yWindows = CoreService.lastVisiblePackagesFromWindows.contains(packageName),
                    a11yService = CoreService.isServiceActive,
                    reason = reason
                )
            }
            result
        } catch (e: Exception) {
            Log.d(TAG, "[isAppActuallyVisible] Error checking visibility for $packageName: ${e.message}")
            true // 出错时不拦截，避免误杀正常锁机
        }
    }

    /**
     * 检测当前前台应用包名。
     *
     * 🐛 [Bug Fix] 2026-02-07: vivo等ROM上 queryEvents 可能不返回旧的 MOVE_TO_FOREGROUND(1)/
     * MOVE_TO_BACKGROUND(2) 事件，导致一直返回缓存的桌面launcher包名（com.bbk.launcher2），
     * 实际上王者荣耀(com.tencent.tmgp.sgame)在前台也检测不到。
     *
     * 修复方案：
     * 1. 同时检测 ACTIVITY_RESUMED(15) 和 ACTIVITY_PAUSED(16) 事件（Android 10+ / API 29+）
     * 2. 增大查询窗口从5秒到30秒，避免短窗口内无事件
     * 3. 当 queryEvents 完全无事件时，使用 queryUsageStats 交叉验证缓存
     * 4. queryUsageStats 兜底也检查缓存一致性，防止缓存过期
     */
    private fun getTopPackageName(): String? {
        val usm = usageStatsManager ?: run {
            Log.e(TAG, "[getTopPackageName] UsageStatsManager is NULL!")
            Log.e("IronWill_Debug", "[Critical] UsageStatsManager is NULL. Cannot detect apps.")
            mLastTopEvidence = TopPackageEvidence.NONE
            // [Diagnostics-only] record FAILED fault for both sources via the testable
            // factory so production wiring is exactly what the tests exercise.
            val cycleRef = currentForegroundCycle ?: return null
            val nowMs = System.currentTimeMillis()
            cycleRef.updateUsageEvents(ForegroundFaultSnapshotFactory.eventsFailed("USAGE_STATS_MANAGER_NULL", nowMs))
            cycleRef.updateUsageStats(ForegroundFaultSnapshotFactory.usageStatsFailed("USAGE_STATS_MANAGER_NULL", nowMs))
            return null
        }
        val time = System.currentTimeMillis()

        // 🔧 [Fix] 定义前台/后台事件类型常量
        // Android 10+ (API 29) 使用 ACTIVITY_RESUMED(15)/ACTIVITY_PAUSED(16)
        // 旧版本使用 MOVE_TO_FOREGROUND(1)/MOVE_TO_BACKGROUND(2)
        // 不同ROM可能只发送其中一种，所以两种都要处理
        val MOVE_TO_FG = android.app.usage.UsageEvents.Event.MOVE_TO_FOREGROUND     // 1
        val MOVE_TO_BG = android.app.usage.UsageEvents.Event.MOVE_TO_BACKGROUND     // 2
        // ACTIVITY_RESUMED = 15, ACTIVITY_PAUSED = 16, ACTIVITY_STOPPED = 23 (API 29+)
        val ACTIVITY_RESUMED = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) 15 else -1
        val ACTIVITY_PAUSED  = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) 16 else -1
        val ACTIVITY_STOPPED = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) 23 else -1

        // 1. Query wider window (30s) for recent events
        // 🔧 [Fix] 从5秒扩大到30秒，确保游戏稳定运行时也能看到最近的切换事件
        // Plan: "On exception, record failure class and rethrow" — record the
        // fault for the SAME source that failed (USAGE_EVENTS only) and rethrow so
        // any existing upper-layer handler still sees the original exception.
        // CRITICAL: mLastEventsQueryStartedAtMs must be set BEFORE the call so that
        // if the call throws, the catch block reads the real start time of THIS
        // attempt rather than a stale value from a previous round.
        val queryStartedAtMs = System.currentTimeMillis()
        mLastEventsQueryStartedAtMs = queryStartedAtMs
        mLastEventsQueryFinishedAtMs = 0L
        val events: android.app.usage.UsageEvents = try {
            usm.queryEvents(time - 30000, time)
        } catch (e: Exception) {
            Log.w(TAG, "[getTopPackageName] queryEvents failed: ${e.javaClass.simpleName}: ${e.message}")
            mLastTopEvidence = TopPackageEvidence.NONE
            val queryFinishedAtMs = System.currentTimeMillis()
            mLastEventsQueryFinishedAtMs = queryFinishedAtMs
            // USAGE_STATS stays at INITIALIZING (not queried this round) — do not
            // fabricate a FAILED state for a source we never called.
            currentForegroundCycle?.updateUsageEvents(
                ForegroundFaultSnapshotFactory.eventsFailed(
                    failureClass = e.javaClass.simpleName,
                    capturedAtMs = queryFinishedAtMs,
                    queryStartedAtMs = queryStartedAtMs,
                    queryFinishedAtMs = queryFinishedAtMs
                )
            )
            throw e
        }
        // Successful path: record finished time so push helpers can use it.
        mLastEventsQueryFinishedAtMs = System.currentTimeMillis()
        val event = android.app.usage.UsageEvents.Event()
        
        var foundRelevantEvent = false
        var calculatedTop: String? = null // Start from null — don't seed from cache.
        // [Diagnostics-only] collect up to 3 most recent foreground/background events for the snapshot
        val recentEvents = ArrayDeque<ForegroundCandidate>()
        val maxEventCandidates = 3

        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            val type = event.eventType
            val eventPkg = event.packageName.orEmpty()
            val eventClass = event.className?.toString().orEmpty()

            if (type == MOVE_TO_FG || type == ACTIVITY_RESUMED) {
                if (eventPkg.isNotBlank()) {
                    PwaBypassManager.observeUsageEventSignal(this, eventPkg, eventClass)
                }
            }

            val lastProcessedTs = mLastProcessedPresenceEventAtMs[eventPkg] ?: 0L
            val sig = "$type:$eventClass"
            val shouldProcess = when {
                event.timeStamp > lastProcessedTs -> {
                    mLastProcessedPresenceEventAtMs[eventPkg] = event.timeStamp
                    val newSet = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
                    newSet.add(sig)
                    mProcessedSignaturesAtLastTs[eventPkg] = newSet
                    true
                }
                event.timeStamp == lastProcessedTs -> {
                    val sigSet = mProcessedSignaturesAtLastTs.computeIfAbsent(eventPkg) {
                        java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
                    }
                    sigSet.add(sig) // 返回 true 表示之前尚未处理过该特定签名
                }
                else -> false // 事件时间戳早于 high-water mark，属于 30s 窗口中已处理过的历史事件
            }

            if (shouldProcess) {
                if (eventPkg.isNotBlank()) {
                    Log.d(TAG, "[UsageEvents] eventPkg=$eventPkg type=$type eventAt=${event.timeStamp}")
                    mPresenceTracker.onUsageEvent(eventPkg, type, event.timeStamp)
                    foregroundSessionTracker.onUsageEvent(eventPkg, type, event.timeStamp, eventClass)
                }
            }

            // 检测前台事件：MOVE_TO_FOREGROUND(1) 或 ACTIVITY_RESUMED(15)
            if (type == MOVE_TO_FG || type == ACTIVITY_RESUMED) {
                calculatedTop = event.packageName
                mLastForegroundEventTs = event.timeStamp
                foundRelevantEvent = true
            }
            // 检测后台/退出事件：MOVE_TO_BACKGROUND(2)、ACTIVITY_PAUSED(16) 或 ACTIVITY_STOPPED(23)
            else if (type == MOVE_TO_BG || type == ACTIVITY_PAUSED || type == ACTIVITY_STOPPED) {
                if (calculatedTop == event.packageName) {
                    calculatedTop = null
                }
                mLastBackgroundEventTs = event.timeStamp
                foundRelevantEvent = true
            }

            // [Diagnostics-only] record this event as a candidate (newest last; cap at 3)
            if (eventPkg.isNotBlank() &&
                (type == MOVE_TO_FG || type == ACTIVITY_RESUMED ||
                 type == MOVE_TO_BG || type == ACTIVITY_PAUSED)
            ) {
                val eventName = when (type) {
                    MOVE_TO_FG -> "MOVE_TO_FOREGROUND"
                    MOVE_TO_BG -> "MOVE_TO_BACKGROUND"
                    ACTIVITY_RESUMED -> "ACTIVITY_RESUMED"
                    ACTIVITY_PAUSED -> "ACTIVITY_PAUSED"
                    else -> "UNKNOWN"
                }
                recentEvents.addLast(
                    ForegroundCandidate(
                        packageName = eventPkg,
                        eventName = eventName,
                        eventTimeMs = event.timeStamp
                    )
                )
                while (recentEvents.size > maxEventCandidates) {
                    recentEvents.removeFirst()
                }
            }
        }

        fun traceAndReturn(
            result: String?,
            evidence: TopPackageEvidence,
            @Suppress("UNUSED_PARAMETER") finalReason: String
        ): String? {
            mLastTopEvidence = evidence
            mLastTopResolvedAt = time
            // 不输出日志，仅保留窗口快照采集：它会刷新 CoreService.lastVisiblePackagesFromWindows，
            // 该缓存被前台可见性判定读取，删除调用会改变业务时序。
            CoreService.captureWindowSnapshot(time)

            return result
        }

        // 2. If we found relevant events, update cache and return
        if (foundRelevantEvent) {
            mLastTopPackage = calculatedTop
            mLastTopEvidence = TopPackageEvidence.EVENTS
            mLastTopResolvedAt = time
            // [Diagnostics-only] push UsageEvents snapshot with real 3 most recent candidates
            pushEventsSnapshotToCycle(
                packageName = calculatedTop,
                eventTimeMs = mLastForegroundEventTs,
                candidates = recentEvents.toList(),
                selectionReason = "EVENTS_QUERY",
                status = if (calculatedTop != null) ForegroundSourceStatus.AVAILABLE else ForegroundSourceStatus.UNAVAILABLE
            )
            return traceAndReturn(calculatedTop, TopPackageEvidence.EVENTS, "EVENTS_BUSINESS_MATCH")
        }

        // 3. If NO events in last 30s, use queryUsageStats to cross-validate cache
        // 🔧 [Fix] 不再盲目信任 mLastTopPackage 缓存！
        // 当 queryEvents 无事件时，用 queryUsageStats 验证是否有更新的前台应用
        val usageStatsTop = getTopPackageFromUsageStats(time)
        if (usageStatsTop != null) {
            if (usageStatsTop != mLastTopPackage) {
                Log.d(TAG, "[getTopPackageName] UsageStats cross-check found different app: $usageStatsTop (cached: $mLastTopPackage)")
                mLastTopPackage = usageStatsTop
            }
            mLastTopEvidence = TopPackageEvidence.USAGE_STATS
            mLastTopResolvedAt = time
            // [Diagnostics-only] mark UsageEvents as unavailable this round (no recent FG/BG events)
            pushEventsSnapshotToCycle(
                packageName = null,
                eventTimeMs = 0L,
                candidates = emptyList(),
                selectionReason = null,
                status = ForegroundSourceStatus.UNAVAILABLE
            )
            return traceAndReturn(mLastTopPackage, TopPackageEvidence.USAGE_STATS, "EVENTS_EMPTY_30S->USAGE_STATS_MATCH")
        }
        
        // 4. queryUsageStats 也没有结果，使用缓存
        if (mLastTopPackage != null) {
            mLastTopEvidence = TopPackageEvidence.CACHE
            mLastTopResolvedAt = time
            // [Diagnostics-only] mark UsageEvents unavailable; we'll let CACHE serve as evidence
            pushEventsSnapshotToCycle(
                packageName = null,
                eventTimeMs = 0L,
                candidates = emptyList(),
                selectionReason = null,
                status = ForegroundSourceStatus.UNAVAILABLE
            )
            // [Diagnostics-only] mark UsageStats unavailable too
            pushUsageStatsSnapshotToCycle(
                packageName = null,
                eventTimeMs = 0L,
                candidates = emptyList(),
                selectionReason = null,
                status = ForegroundSourceStatus.UNAVAILABLE
            )
            return traceAndReturn(mLastTopPackage, TopPackageEvidence.CACHE, "EVENTS_EMPTY_30S->STATS_EMPTY->CACHE_HIT")
        }

        // 5. Initialization Case: Cache is null and no recent events (service restart?).
        // Query deeper history (12 hours) to find the last FG event.
        // [防御性] 12小时查询有30秒冷却，避免每500ms轮询时重复执行重查询（关联 Issue_Tracker 05-13）
        if (time - mLastHistoryQueryTime < 30_000L) {
            Log.d(TAG, "[getTopPackageName] Skipping 12h history query (cooldown, last=${time - mLastHistoryQueryTime}ms ago)")
            mLastTopEvidence = TopPackageEvidence.NONE
            mLastTopResolvedAt = time
            // [Diagnostics-only] mark both Events and UsageStats unavailable
            pushEventsSnapshotToCycle(
                packageName = null, eventTimeMs = 0L,
                candidates = emptyList(), selectionReason = null,
                status = ForegroundSourceStatus.UNAVAILABLE
            )
            pushUsageStatsSnapshotToCycle(
                packageName = null, eventTimeMs = 0L,
                candidates = emptyList(), selectionReason = null,
                status = ForegroundSourceStatus.UNAVAILABLE
            )
            return traceAndReturn(null, TopPackageEvidence.NONE, "EVENTS_EMPTY_30S->STATS_EMPTY->CACHE_NULL->HISTORY_COOLDOWN")
        }
        mLastHistoryQueryTime = time
        Log.d(TAG, "[getTopPackageName] Cache empty & no recent events. Querying history (12h)...")
        val historyEvents = usm.queryEvents(time - 43200000, time) // 12 hours
        var historyTop: String? = null
        val historyEvent = android.app.usage.UsageEvents.Event()
        
        while (historyEvents.hasNextEvent()) {
            historyEvents.getNextEvent(historyEvent)
            val type = historyEvent.eventType
            if (type == MOVE_TO_FG || type == ACTIVITY_RESUMED) {
                val eventPkg = historyEvent.packageName.orEmpty()
                val eventClass = historyEvent.className?.toString().orEmpty()
                if (eventPkg.isNotBlank()) {
                    PwaBypassManager.observeUsageEventSignal(this, eventPkg, eventClass)
                }
            }
            if (type == MOVE_TO_FG || type == ACTIVITY_RESUMED) {
                historyTop = historyEvent.packageName
                mLastForegroundEventTs = historyEvent.timeStamp
            } else if (type == MOVE_TO_BG || type == ACTIVITY_PAUSED) {
                if (historyTop == historyEvent.packageName) {
                    historyTop = null
                }
                mLastBackgroundEventTs = historyEvent.timeStamp
            }
        }
        
        if (historyTop != null) {
            mLastTopPackage = historyTop
            mLastTopEvidence = TopPackageEvidence.HISTORY
            mLastTopResolvedAt = time
            // [Diagnostics-only] mark both Events and UsageStats unavailable; HISTORY served
            pushEventsSnapshotToCycle(
                packageName = null, eventTimeMs = 0L,
                candidates = emptyList(), selectionReason = null,
                status = ForegroundSourceStatus.UNAVAILABLE
            )
            pushUsageStatsSnapshotToCycle(
                packageName = null, eventTimeMs = 0L,
                candidates = emptyList(), selectionReason = null,
                status = ForegroundSourceStatus.UNAVAILABLE
            )
            return traceAndReturn(historyTop, TopPackageEvidence.HISTORY, "EVENTS_EMPTY_30S->STATS_EMPTY->CACHE_NULL->HISTORY_MATCH")
        }

        Log.w(TAG, "[getTopPackageName] No foreground app detected via Events/UsageStats/History. Returning null.")
        mLastTopEvidence = TopPackageEvidence.NONE
        mLastTopResolvedAt = time
        // [Diagnostics-only] mark all sources unavailable
        pushEventsSnapshotToCycle(
            packageName = null, eventTimeMs = 0L,
            candidates = emptyList(), selectionReason = null,
            status = ForegroundSourceStatus.UNAVAILABLE
        )
        pushUsageStatsSnapshotToCycle(
            packageName = null, eventTimeMs = 0L,
            candidates = emptyList(), selectionReason = null,
            status = ForegroundSourceStatus.UNAVAILABLE
        )
        return traceAndReturn(null, TopPackageEvidence.NONE, "EVENTS_EMPTY_30S->STATS_EMPTY->CACHE_NULL->HISTORY_EMPTY")
    }

    /**
     * 使用 queryUsageStats 获取最近前台应用。
     * 🔧 [Fix] 增大窗口到60秒，同时优先选择非launcher的应用
     */
    private fun getTopPackageFromUsageStats(now: Long): String? {
        val usm = usageStatsManager ?: run {
            // [Diagnostics-only] USM is null on the UsageStats query path too;
            // use the testable factory so production wiring matches what the unit
            // tests exercise.
            currentForegroundCycle?.updateUsageStats(
                ForegroundFaultSnapshotFactory.usageStatsFailed("USAGE_STATS_MANAGER_NULL", now)
            )
            return null
        }
        // CRITICAL: mLastStatsQueryStartedAtMs must be set BEFORE the call so
        // that if the call throws, the catch block reads the real start time
        // of THIS attempt rather than a stale value from a previous round.
        val queryStartedAtMs = System.currentTimeMillis()
        mLastStatsQueryStartedAtMs = queryStartedAtMs
        mLastStatsQueryFinishedAtMs = 0L
        val stats = try {
            usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, now - 60000, now)
        } catch (e: Exception) {
            Log.w(TAG, "[getTopPackageFromUsageStats] queryUsageStats failed: ${e.javaClass.simpleName}: ${e.message}")
            val queryFinishedAtMs = System.currentTimeMillis()
            mLastStatsQueryFinishedAtMs = queryFinishedAtMs
            currentForegroundCycle?.updateUsageStats(
                ForegroundFaultSnapshotFactory.usageStatsFailed(
                    failureClass = e.javaClass.simpleName,
                    capturedAtMs = queryFinishedAtMs,
                    queryStartedAtMs = queryStartedAtMs,
                    queryFinishedAtMs = queryFinishedAtMs
                )
            )
            throw e
        }
        mLastStatsQueryFinishedAtMs = System.currentTimeMillis()
        if (stats.isNullOrEmpty()) {
            // [Diagnostics-only] UsageStats returned nothing
            pushUsageStatsSnapshotToCycle(
                packageName = null, eventTimeMs = 0L,
                candidates = emptyList(), selectionReason = null,
                status = ForegroundSourceStatus.UNAVAILABLE
            )
            return null
        }

        // 🚀 [Bug Fix] 使用 lastTimeVisible (API 29+) 过滤纯后台播放服务（如汽水音乐）
        // 因为后台音频服务的 lastTimeUsed 会一直更新，但 lastTimeVisible 不会。
        val recentActive = stats.filter { stat ->
            var isActive = false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                try {
                    val lastVisible = stat.lastTimeVisible
                    if (lastVisible > 0) {
                        isActive = lastVisible >= now - 30000
                    } else {
                        isActive = stat.lastTimeUsed >= now - 30000
                    }
                } catch (e: Exception) {
                    isActive = stat.lastTimeUsed >= now - 30000
                }
            } else {
                isActive = stat.lastTimeUsed >= now - 30000
            }
            isActive && stat.totalTimeInForeground > 0
        }

        if (recentActive.isEmpty()) {
            return null
        }

        // 🔧 [Fix] 优先选择非launcher/非系统UI的应用
        // 避免 launcher 因为后台活动被排在前面
        val timeSelector: (android.app.usage.UsageStats) -> Long = { stat ->
            var t = stat.lastTimeUsed
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                try { if (stat.lastTimeVisible > 0) t = stat.lastTimeVisible } catch (e: Exception) { Log.w(TAG, "Failed to read lastTimeVisible", e) }
            }
            t
        }

        val nonLauncher = recentActive
            .filter {
                !CreditManager.isLauncher(it.packageName) &&
                    it.packageName !in usageCarrierNoisePackages
            }
            .maxByOrNull(timeSelector)

        if (nonLauncher != null) {
            mLastTopUsageStatTs = timeSelector(nonLauncher)
            // [Diagnostics-only] push real UsageStats snapshot. Sort newest-first so
            // take(3) returns the actual 3 most recent candidates, not 3 arbitrary entries.
            val candidates = recentActive
                .sortedByDescending { timeSelector(it) }
                .take(3)
                .map { stat ->
                    ForegroundCandidate(
                        packageName = stat.packageName,
                        eventName = "USAGE_STAT",
                        eventTimeMs = timeSelector(stat),
                        lastTimeUsedMs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) stat.lastTimeUsed else null,
                        lastTimeVisibleMs = try {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) stat.lastTimeVisible else null
                        } catch (_: Exception) { null }
                    )
                }
            pushUsageStatsSnapshotToCycle(
                packageName = nonLauncher.packageName,
                eventTimeMs = timeSelector(nonLauncher),
                candidates = candidates,
                selectionReason = "lastTimeUsed-or-lastTimeVisible",
                status = ForegroundSourceStatus.AVAILABLE,
                queryStartedAtMs = mLastStatsQueryStartedAtMs.takeIf { it > 0L },
                queryFinishedAtMs = mLastStatsQueryFinishedAtMs.takeIf { it > 0L } ?: now
            )
            return nonLauncher.packageName
        }

        // 如果只剩 launcher，返回最近的
        val fallback = recentActive
            .filter { it.packageName !in usageCarrierNoisePackages }
            .maxByOrNull(timeSelector)
            ?: recentActive.maxByOrNull(timeSelector)
        mLastTopUsageStatTs = fallback?.let { timeSelector(it) } ?: 0L

        if (fallback != null) {
            // [Diagnostics-only] push UsageStats snapshot for fallback. Sorted.
            val candidates = recentActive
                .sortedByDescending { timeSelector(it) }
                .take(3)
                .map { stat ->
                    ForegroundCandidate(
                        packageName = stat.packageName,
                        eventName = "USAGE_STAT",
                        eventTimeMs = timeSelector(stat),
                        lastTimeUsedMs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) stat.lastTimeUsed else null,
                        lastTimeVisibleMs = try {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) stat.lastTimeVisible else null
                        } catch (_: Exception) { null }
                    )
                }
            pushUsageStatsSnapshotToCycle(
                packageName = fallback.packageName,
                eventTimeMs = timeSelector(fallback),
                candidates = candidates,
                selectionReason = "fallback-launcher-or-carrier",
                status = ForegroundSourceStatus.AVAILABLE,
                queryStartedAtMs = mLastStatsQueryStartedAtMs.takeIf { it > 0L },
                queryFinishedAtMs = mLastStatsQueryFinishedAtMs.takeIf { it > 0L } ?: now
            )
        } else {
            pushUsageStatsSnapshotToCycle(
                packageName = null, eventTimeMs = 0L,
                candidates = emptyList(), selectionReason = null,
                status = ForegroundSourceStatus.UNAVAILABLE
            )
        }

        return fallback?.packageName
    }

    // ═══════════════════════════════════════════════════════════
    // [Diagnostics-only] ForegroundDiagnosticCycle outcome recorder
    // Pure logging; does NOT alter any business decision.
    // ═══════════════════════════════════════════════════════════

    private fun recordFgDiagnosticOutcome(
        cycle: ForegroundDiagnosticCycle?,
        outcome: ForegroundReportOutcome,
        nowMs: Long
    ) {
        val c = cycle ?: return
        // [Diagnostics-only] Accessibility snapshot is already pushed from the real
        // polling path (isAppActuallyVisible). Do not overwrite it here.
        // If the cycle never received an Accessibility snapshot, fill it with the
        // best-effort diagnosticsAccessibilityStatus derived from CoreService state.
        if (c.accessibility.status == ForegroundSourceStatus.INITIALIZING) {
            try {
                val a11yStatus = try {
                    ForegroundSourceStatus.valueOf(
                        CoreService.diagnosticsAccessibilityStatus(this)
                    )
                } catch (e: Exception) {
                    ForegroundSourceStatus.AMBIGUOUS
                }
                val a11yPkg = CoreService.diagnosticsLastEligibleEventPackage
                // Accessibility vote must check STATUS first — AMBIGUOUS / FAILED /
                // UNAVAILABLE always abstains regardless of any stale eligible-event
                // package still cached in CoreService diagnostics.
                val a11yVote = when {
                    a11yStatus != ForegroundSourceStatus.AVAILABLE -> ForegroundVote.ABSTAIN
                    a11yPkg == null -> ForegroundVote.ABSTAIN
                    CreditManager.shouldBlockApp(this, a11yPkg, "") -> ForegroundVote.BLACKLIST
                    else -> ForegroundVote.NON_BLACKLIST
                }
                c.updateAccessibility(
                    ForegroundSourceSnapshot(
                        source = "ACCESSIBILITY",
                        status = a11yStatus,
                        packageName = a11yPkg,
                        vote = a11yVote,
                        capturedAtMs = nowMs,
                        queryStartedAtMs = null,
                        queryFinishedAtMs = null,
                        candidates = emptyList(),
                        selectionReason = if (a11yVote != ForegroundVote.ABSTAIN && a11yPkg != null) "ELIGIBLE_EVENT" else null,
                        unavailableReason = if (a11yStatus == ForegroundSourceStatus.UNAVAILABLE) "A11Y_INACTIVE" else null,
                        // Real failure class MUST come from the SAME detection process that produced
                        // the a11yStatus. a11yStatus comes from CoreService.diagnosticsAccessibilityStatus
                        // (Settings + service-connectivity check), so its failure class
                        // comes from diagnosticsAccessibilityStatusFailureClass. Window-scan
                        // exceptions are reported through diagnosticsLastWindowSnapshotFailureClass
                        // but those are NOT the source of the current a11yStatus.
                        failureClass = resolveAccessibilityFailureClass(
                            status = a11yStatus,
                            statusFailureClass = CoreService.diagnosticsAccessibilityStatusFailureClass
                        ),
                        previousAccessibilitySnapshot = lastAccessibilitySnapshot,
                        currentAccessibilitySnapshot = AccessibilityForegroundSnapshot(
                            eventAtMs = CoreService.diagnosticsLastEligibleEventAtMs.takeIf { it > 0L },
                            handledAtMs = nowMs,
                            eventType = CoreService.diagnosticsLastEligibleEventType,
                            eventPackageName = CoreService.diagnosticsLastEligibleEventPackage,
                            snapshotAtMs = CoreService.diagnosticsWindowSnapshotAtMs.takeIf { it > 0L },
                            windowSummary = "windows=" + if (CoreService.lastVisiblePackagesFromWindows.size <= 1) "一致" else CoreService.lastVisiblePackagesFromWindows.size
                        )
                    )
                )
                lastAccessibilitySnapshot = c.accessibility.currentAccessibilitySnapshot
            } catch (_: Exception) {
                // Diagnostics must never throw
            }
        }

        val gate = foregroundReportGate
        val decision = gate.evaluate(c, outcome, nowMs)
        // 只算一次：状态跟踪器与输出器必须对"这份完整报告是否真的输出"看法一致，
        // 否则被丢弃的报告会让桌面切换的转换行也跟着丢失。
        val fullRetention: ForegroundDecisionRetention.Level? =
            if (decision.emission == ForegroundReportEmission.FULL) foregroundDecisionRetention.assess(decision, c) else null
        // 仅供日志使用：给 LOCK_RESULT 等 A 级事件附上一行"为什么"，业务代码不读取。
        LastForegroundEvidence.update(outcome.effectivePackageName, mLastTopEvidence.name, outcome.businessReason)
        if (foregroundSampler.tryAcquire(nowMs)) {
            LogExporter.logRecentSample(nowMs, foregroundSampleBody(c, outcome))
        }
        emitForegroundStateClosureOrTransition(
            packageName = outcome.effectivePackageName ?: "NONE",
            stateKey = listOf(
                outcome.effectivePackageName ?: "NONE",
                outcome.businessAction.name,
                outcome.businessReason,
                outcome.allowanceState
            ).joinToString("|"),
            hasFullDiagnostic = decision.emission == ForegroundReportEmission.FULL &&
                fullRetention != ForegroundDecisionRetention.Level.DROP,
            nowMs = nowMs,
            stateFields = linkedMapOf(
                "action" to outcome.businessAction.name,
                "reason" to outcome.businessReason,
                "allowance" to outcome.allowanceState
            )
        )
        foregroundNormalEventBridge.emitDecision(
            cycle = c,
            outcome = outcome,
            decision = decision,
            selectedSource = mLastTopEvidence.name,
            requestId = currentTraceId,
            traceId = currentTraceId,
            transition = foregroundTransition(c.previousForegroundPackageName, outcome.effectivePackageName),
            retention = fullRetention
        )
        if (decision.emission == ForegroundReportEmission.NONE) return  // compact route already evaluated
        // R1-4: use decision.sourceProblemStartedAtMs as the single source of
        // truth for the continuation-line duration. We do NOT read
        // gate.lastConflictStartedAtMs() anymore — that field is cleared on
        // recovery, and the Decision carries the original startedAt forward
        // so the formatter can surface the recovery duration even after the
        // gate has cleared its internal state.
        if (decision.emission == ForegroundReportEmission.CONTINUING_CONFLICT_SUMMARY &&
            DefaultLogDebugConfig.foregroundRaw
        ) {
            // Continuing conflict summaries are raw diagnostics, not legal compact reports:
            // their source-conflict state fails the compact-event precondition.
            ForegroundReportFormatter.format(
                c, outcome, decision, nowMs, decision.sourceProblemStartedAtMs ?: 0L
            ).forEach { line -> LogExporter.logEvent("MonitorService", line) }
        }
    }

    /**
     * State-only normal events are derived after the existing gate decision. This records neither
     * a foreground choice nor a business decision; it only closes the already observed state.
     */
    private fun emitForegroundStateClosureOrTransition(
        packageName: String,
        stateKey: String,
        hasFullDiagnostic: Boolean,
        nowMs: Long,
        stateFields: Map<String, String> = emptyMap()
    ) {
        foregroundNormalEventStateTracker.observe(
            stateKey = stateKey,
            packageName = packageName,
            isDesktop = CreditManager.isLauncher(packageName),
            hasFullDiagnostic = hasFullDiagnostic,
            nowMs = nowMs,
            clock = SystemEventClock,
            stateFields = stateFields
        ).forEach(foregroundNormalEventLogger::emit)
    }

    /** 单行前台采样：前台包、来源、动作、原因，以及三个来源各自的状态。 */
    private fun foregroundSampleBody(cycle: ForegroundDiagnosticCycle, outcome: ForegroundReportOutcome): String {
        fun status(source: ForegroundSourceSnapshot): String =
            source.status.name + (source.unavailableReason?.takeIf { it.isNotBlank() }?.let { "($it)" } ?: "")
        return "[FG_SAMPLE] fg=${outcome.effectivePackageName ?: "NONE"} src=${mLastTopEvidence.name} " +
            "action=${outcome.businessAction.name} reason=${outcome.businessReason} allowance=${outcome.allowanceState} " +
            "events=${status(cycle.usageEvents)} stats=${status(cycle.usageStats)} a11y=${status(cycle.accessibility)}"
    }

    /** A real existing pre-cycle package becomes a transition only when it actually changed. */
    private fun foregroundTransition(previousPackage: String?, currentPackage: String?): ForegroundTransition? =
        if (!previousPackage.isNullOrBlank() && !currentPackage.isNullOrBlank() && previousPackage != currentPackage) {
            ForegroundTransition(previousPackage, currentPackage)
        } else null

    /**
     * Build outcome for NO_LOCK with one of the documented business reasons.
     * Diagnostic-only; business action stays unchanged.
     */
    fun recordFgNoLock(
        cycle: ForegroundDiagnosticCycle?,
        effectivePkg: String?,
        effectiveBlacklisted: Boolean?,
        businessReason: String,
        allowanceState: String,
        guardPassed: Boolean?,
        visibilityPassed: Boolean?
    ) {
        val now = System.currentTimeMillis()
        val outcome = ForegroundReportOutcome(
            effectivePackageName = effectivePkg,
            effectivePackageBlacklisted = effectiveBlacklisted,
            diagnosticRecommendation = computeDiagnosticRecommendation(),
            businessAction = ForegroundBusinessAction.NO_LOCK,
            businessReason = businessReason,
            allowanceState = allowanceState,
            guardPassed = guardPassed,
            visibilityPassed = visibilityPassed
        )
        recordFgDiagnosticOutcome(cycle, outcome, now)
    }

    /**
     * Build outcome for REQUEST_LOCK before each showLockScreen call.
     */
    fun recordFgRequestLock(
        cycle: ForegroundDiagnosticCycle?,
        effectivePkg: String?,
        effectiveBlacklisted: Boolean?,
        businessReason: String,
        allowanceState: String,
        guardPassed: Boolean?,
        visibilityPassed: Boolean?
    ) {
        val now = System.currentTimeMillis()
        val outcome = ForegroundReportOutcome(
            effectivePackageName = effectivePkg,
            effectivePackageBlacklisted = effectiveBlacklisted,
            diagnosticRecommendation = "LOCK_BY_DIAGNOSTIC_MAJORITY",
            businessAction = ForegroundBusinessAction.REQUEST_LOCK,
            businessReason = businessReason,
            allowanceState = allowanceState,
            guardPassed = guardPassed,
            visibilityPassed = visibilityPassed
        )
        recordFgDiagnosticOutcome(cycle, outcome, now)
    }

    private fun computeDiagnosticRecommendation(): String {
        val c = currentForegroundCycle ?: return "NO_DIAGNOSTIC_LOCK"
        val votes = c.sourceList().map { it.vote }
        val blacklistVotes = votes.count { it == ForegroundVote.BLACKLIST }
        val nonBlacklistVotes = votes.count { it == ForegroundVote.NON_BLACKLIST }
        return when {
            blacklistVotes == 0 -> "NO_DIAGNOSTIC_LOCK"
            blacklistVotes > nonBlacklistVotes -> "LOCK_BY_DIAGNOSTIC_MAJORITY"
            else -> "LOCK_ON_TIE"
        }
    }

    /** Public accessor for the current cycle (used by tests and LockWindowManager) */
    fun currentForegroundCycleOrNull(): ForegroundDiagnosticCycle? = currentForegroundCycle

    /** Public accessor for the FG trace id (used by LockWindowManager result log) */
    fun currentForegroundReportId(): String? = currentForegroundCycle?.reportId
}

internal class MonitorLoopScheduleGate {
    @Volatile
    private var hasScheduledTask = false

    @Synchronized
    fun tryMarkScheduled(): Boolean {
        if (hasScheduledTask) return false
        hasScheduledTask = true
        return true
    }

    @Synchronized
    fun onRunnableDispatched() {
        hasScheduledTask = false
    }

    @Synchronized
    fun reset() {
        hasScheduledTask = false
    }
}
