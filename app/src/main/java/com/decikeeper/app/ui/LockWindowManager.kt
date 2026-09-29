/**
 * =================================================================================
 * [Context Header]
 * * @file: LockWindowManager.kt
 * * @role: UI
 * * @description: 锁屏覆盖层的创建、更新与解锁控制。
 * * @responsibilities:
 * 1. 管理锁屏视图的显示与状态更新。
 * 2. 处理黑名单/惩罚/特权等交互逻辑。

 * - 上游: MonitorService, CoreService
 * - 下游: WindowManager, CreditManager, AppWindowManager
 * * @constraints (必读):
 * 1. 必须保证锁屏状态一致性与可恢复性。
 * 2. 避免主线程阻塞，定时器需运行在后台线程。
 * 3. 处理空档/休息/特权与时间窗的双锁优先级规则。
 * =================================================================================
 */
package com.decikeeper.app.ui

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.provider.Settings
import android.text.InputFilter
import android.text.TextUtils
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewTreeObserver
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.gridlayout.widget.GridLayout
import com.decikeeper.app.R
import com.decikeeper.app.data.AndroidLogcatNormalEventSink
import com.decikeeper.app.data.CountAction
import com.decikeeper.app.manager.expiry.ExpiryCoordinator
import com.decikeeper.app.manager.expiry.ExpiryDecision
import com.decikeeper.app.manager.expiry.ExpiryReason
import com.decikeeper.app.manager.expiry.ExpiryResolver
import com.decikeeper.app.data.DefaultLogDebugConfig
import com.decikeeper.app.manager.StartupProtectionManager
import com.decikeeper.app.data.DefaultNormalEventRenderer
import com.decikeeper.app.data.EventClock
import com.decikeeper.app.data.LogDebugConfig
import com.decikeeper.app.data.LogExporter
import com.decikeeper.app.data.NormalEventDraft
import com.decikeeper.app.data.NormalEventLogger
import com.decikeeper.app.data.NormalEvents
import com.decikeeper.app.data.SegmentBlacklist
import com.decikeeper.app.data.SystemEventClock
import com.decikeeper.app.data.TemporaryAccessQuotaPlan
import com.decikeeper.app.data.UsableCount
import com.decikeeper.app.data.calculateQuotaDetail
import com.decikeeper.app.data.calculateTemporaryAccessQuotaPlan
import com.decikeeper.app.manager.AntiUninstallManager
import com.decikeeper.app.data.calculateTemporaryAccessRefundPlan
import com.decikeeper.app.data.formatUsableCountHalfUnits
import com.decikeeper.app.manager.AlarmChallengeManager
import com.decikeeper.app.manager.AlarmScheduler
import com.decikeeper.app.manager.AppWindowManager
import com.decikeeper.app.manager.CreditManager
import com.decikeeper.app.manager.LockOverlayRestrictionMode
import com.decikeeper.app.manager.TimeRuleManager
import com.decikeeper.app.manager.statistics.RestStatsRecorder
import com.decikeeper.app.manager.getEffectiveUsableCountLimit
import com.decikeeper.app.manager.resolveAllowedWindowSummaryForDisplay
import com.decikeeper.app.manager.resolveAutomaticRestrictionRelease
import com.decikeeper.app.manager.resolveEmergencyActionPresentation
import com.decikeeper.app.manager.resolveLockOverlayRestrictionMode
import com.decikeeper.app.manager.resolveTemporaryAccessPresentation
import com.decikeeper.app.manager.resolveWeeklyPrivilegePresentation
import com.decikeeper.app.manager.shouldRouteEmergencyToPrivilege
import com.decikeeper.app.manager.shouldRouteEmergencyToTempAccess
import com.decikeeper.app.manager.shouldShowStandaloneTemporaryAccess
import com.decikeeper.app.service.CoreService
import com.decikeeper.app.service.MonitorService
import com.decikeeper.app.util.ForegroundLockResultSettlementTracker
import com.decikeeper.app.util.ForegroundNormalEventBridge
import com.decikeeper.app.util.ForegroundTransition
import com.decikeeper.app.util.calculateMaxRawBorrowableCount
import com.decikeeper.app.util.calculateMaxRawBorrowableMinutes
import com.decikeeper.app.util.computeCurrentUsableRemaining
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean








































/**
 * Manages the Lock Screen Overlay.
 * Singleton to be shared between CoreService (Accessibility) and MonitorService (UsageStats).
 */
@SuppressLint("StaticFieldLeak")
object LockWindowManager {

    private const val TAG = "LockWindowManager"
    private val foregroundLockNormalEventLogger by lazy {
        NormalEventLogger(
            renderer = DefaultNormalEventRenderer,
            sinks = listOf(LogExporter.normalEventSink(), AndroidLogcatNormalEventSink())
        )
    }
    private val foregroundLockResultSettlements = ForegroundLockResultSettlementTracker()
    private val foregroundLockNormalEventBridge by lazy {
        ForegroundNormalEventBridge(
            clock = SystemEventClock,
            emit = foregroundLockNormalEventLogger::emit,
            lockSettlements = foregroundLockResultSettlements
        )
    }
    private val lifecycleNormalEventBridge by lazy {
        LockLifecycleEventBridge(
            clock = SystemEventClock,
            emit = foregroundLockNormalEventLogger::emit
        )
    }
    private val lifecycleIdSequence = java.util.concurrent.atomic.AtomicLong(0L)
    private var activeLifecycleLock: LifecycleLockReference? = null
    private var pendingReturnDesktopExit: PendingReturnDesktopExit? = null

    /**
     * 🛡️ [Finding 1 修复] 锁屏真正成功 attach（addView 完成）后的可观测交付回调。
     * 用于状态机精确由 BLOCK_PENDING 流转为 BLOCK_CONSUMED，杜绝提前消费。
     */
    @Volatile
    var onLockScreenAttachedListener: ((packageName: String) -> Unit)? = null

    private var mWindowManager: WindowManager? = null
    private var mLockView: View? = null
    private var mLastThemeKey: String? = null
    private var mContext: Context? = null
    @Volatile
    private var isLockScreenHiddenForAlarmScan = false
    @Volatile
    private var isLockScreenSuppressedByAlarm = false

    fun isLockScreenHiddenForAlarmScan(): Boolean = isLockScreenHiddenForAlarmScan
    fun isLockScreenSuppressedByAlarm(): Boolean = isLockScreenSuppressedByAlarm

    fun isAlarmActiveForLockSuppression(): Boolean {
        val ctx = mContext ?: getAlarmContext()
        return isAlarmOverlayShowing ||
            LockAlarmOverlayController.isScanInProgress ||
            isLockScreenHiddenForAlarmScan ||
            isLockScreenSuppressedByAlarm ||
            (ctx != null && AlarmScheduler.isAlarmActive(ctx))
    }
    // MonitorService 每次检测到的前台应用记录（带时间戳环形缓冲区）
    // 用于到期回调路径的交叉验证：getTopPackageName() 返回 null 时取最近一条
    internal data class ForegroundRecord(val pkg: String, val timestamp: Long)
    private val recentForegroundLog = ArrayDeque<ForegroundRecord>()
    private const val FOREGROUND_LOG_MAX = 5
    private const val FOREGROUND_LOG_STALE_MS = 30_000L

    fun recordForegroundDetection(pkg: String) {
        val now = System.currentTimeMillis()
        // 去重：如果和上一条相同，只更新时间戳（避免重复条目占满缓冲区）
        if (recentForegroundLog.isNotEmpty() && recentForegroundLog.last().pkg == pkg) {
            recentForegroundLog.removeLast()
        }
        recentForegroundLog.addLast(ForegroundRecord(pkg, now))
        while (recentForegroundLog.size > FOREGROUND_LOG_MAX) {
            recentForegroundLog.removeFirst()
        }
    }

    /**
     * 获取最近一次 MonitorService 检测到的前台应用（交叉验证用）。
     * 返回最近 [FOREGROUND_LOG_STALE_MS] ms 内的记录，超时视为过期。
     * MonitorService 每 500ms 调用 recordForegroundDetection，此方法在到期回调路径中使用。
     */
    fun getLastKnownForegroundPkg(): String? {
        val now = System.currentTimeMillis()
        for (i in recentForegroundLog.indices.reversed()) {
            val record = recentForegroundLog.elementAt(i)
            if (now - record.timestamp < FOREGROUND_LOG_STALE_MS) {
                return record.pkg
            }
        }
        return null
    }

    /** 获取 recentForegroundLog 副本，供 MonitorService 查询第2候选 */
    internal fun getRecentForegroundRecords(): List<ForegroundRecord> = recentForegroundLog.toList()

    internal fun getSecondDistinctCandidatePkg(currentPkg: String): String? {
        val now = System.currentTimeMillis()
        for (i in recentForegroundLog.indices.reversed()) {
            val record = recentForegroundLog.elementAt(i)
            if (record.pkg != currentPkg && now - record.timestamp < FOREGROUND_LOG_STALE_MS) {
                return record.pkg
            }
        }
        return null
    }

    /**
     * [规则 A] 桌面累计计时器
     * 临时解锁期间，连续检测到桌面满 35 秒就锁（到期后也继续，满了才锁）。
     * - 检测到桌面/IronWill → 累计桌面时间
     * - 切换到任何其他 App → 立即清零
     *
     * 启动时机：
     * - 解锁时长 > 60 秒 → 在最后 60 秒启动（避免早期碎片桌面污染累计）
     * - 解锁时长 ≤ 60 秒 → 立即启动
     */
    /**
     * 临时解锁/休息到期后重锁判断。
     * 每次前台检测都调用，负责：
     * 1. 跟踪 lastNonLauncherPkg（穿透桌面/IronWill 误报）
     * 2. 未到期：绝不立即锁。非 carrier 只跟踪；carrier 走 60s 兜底
     * 3. 到期后：黑名单直接锁、carrier 穿透 lastNonLauncherPkg 锁、60s 兜底
     */
    fun checkAndHandleDesktopStreakLock(context: Context, detectedPkg: String): Boolean {
        // 特权激活期间不触发桌面计时器锁屏（特权绕过所有悬浮窗）
        if (CreditManager.isWeeklyPrivilegeSessionActive(context)) return false
        val targetPkg = temporaryAccessTargetPackageName ?: privilegeDesktopStreakTargetPkg ?: return false
        val now = System.currentTimeMillis()
        val isLauncher = CreditManager.isLauncher(detectedPkg)
        val isIronWill = detectedPkg == context.packageName
        val isCarrier = isLauncher || isIronWill

        // 始终跟踪最近一次前台检测到的 pkg（用于到期定时器回退路径）
        temporaryAccessLastDetectedPkg = detectedPkg
        // 始终跟踪最后一个非桌面/IronWill 的真实 app
        if (!isCarrier) {
            temporaryAccessLastNonLauncherPkg = detectedPkg
        }

        val expired = shouldForceRelockAfterTemporaryAccess(now)

        if (!expired) {
            // ── 未到期 ──
            if (!isCarrier) {
                // 未到期 → 任何 app 都只跟踪不锁（lastNonLauncherPkg 已在前面更新）
                resetDesktopStreak()
                privilegeDesktopStreakTargetPkg = null
                privilegeLastNonLauncherPkg = null
                return false
            }
            // carrier（桌面/IronWill）→ 只计时不穿透，60s 满才查 temporaryAccessLastNonLauncherPkg
            // 计时器期间发生切换（包括桌面↔IronWill）→ 说明用户没有长时间呆在一个应用，清零
            if (lastDesktopStreakCarrierPkg != null && lastDesktopStreakCarrierPkg != detectedPkg) {
                resetDesktopStreak()
            }
            lastDesktopStreakCarrierPkg = detectedPkg

            // 桌面计时器启动条件：
            // - 整周期 < 35s → 检测到桌面/IronWill 直接启动
            // - 整周期 ≥ 35s → 仅到期前 35s 内检测到才启动
            val totalAccessMs = temporaryAccessSeconds * 1000L
            val remainingMs = temporaryAccessUntil - now
            val shouldStart = if (totalAccessMs < DESKTOP_STREAK_LOCK_MS) {
                true // 短周期：直接启动
            } else {
                remainingMs <= DESKTOP_STREAK_LOCK_MS // 长周期：到期前 35s 才启动
            }
            if (shouldStart && desktopStreakTickStartMs == 0L) {
                desktopStreakTickStartMs = now
                temporaryAccessLogSessionId?.let { lifecycleNormalEventBridge.recordDesktopTimerStarted(it, "PRE_EXPIRY_CARRIER") }
                LogExporter.i("DesktopTimer", "⏱ 启动 | phase=到期前 | carrier=$detectedPkg | accumulated=${desktopStreakAccumMs}ms | remaining=${remainingMs}ms")
            }
            val streakMs = now - desktopStreakTickStartMs
            val totalMs = desktopStreakAccumMs + streakMs
            temporaryAccessLogSessionId?.let { lifecycleNormalEventBridge.recordDesktopTimerTick(it, totalMs) }
            if (totalMs >= DESKTOP_STREAK_LOCK_MS) {
                // 35s 满，使用 ExpiryResolver History fallback 综合裁决
                val recentRecords = getRecentForegroundRecords().reversed().map { it.pkg }
                val candidateHistory = if (recentRecords.isNotEmpty()) recentRecords else listOfNotNull(temporaryAccessLastNonLauncherPkg)
                val (fallbackDecision, _) = ExpiryResolver.resolveHistoryFallback(
                    recentForegroundPackages = candidateHistory,
                    isBlacklist = { SegmentBlacklist.shouldLock(context, it) },
                    isLauncher = { CreditManager.isLauncher(it) }
                )
                val lockPkg = if (fallbackDecision == ExpiryDecision.LOCK) {
                    candidateHistory.firstOrNull { !CreditManager.isLauncher(it) && SegmentBlacklist.shouldLock(context, it) }
                        ?: temporaryAccessLastNonLauncherPkg
                } else null

                if (lockPkg == null) {
                    resetDesktopStreak()
                    privilegeDesktopStreakTargetPkg = null
                    privilegeLastNonLauncherPkg = null
                    return false
                }
                // 🛑 [Noise Reduction] 同一 lockPkg / 30s 窗口内不重复打(状态变化或 throttle 到期才打)
                if (lockPkg != lastDesktopTimerLogPkg || now - lastDesktopTimerLogMs >= DESKTOP_TIMER_LOG_THROTTLE_MS) {
                    LogExporter.i("DesktopTimer", "🔒 触发 | phase=到期前 | total=${totalMs}ms | lock=$lockPkg")
                    lastDesktopTimerLogPkg = lockPkg
                    lastDesktopTimerLogMs = now
                }
                cancelTemporaryAccessExpiryRelockRetry()
                val label = resolveTemporaryAccessTargetLabel(context, lockPkg)
                showLockScreen(reason = "Blocked: $lockPkg", isBlacklist = true, blockedAppLabel = label, blockedPackageName = lockPkg)
                if (isLocked) {
                    resetDesktopStreak()
                    temporaryAccessLastNonLauncherPkg = null
                    temporaryAccessLastDetectedPkg = null
                    privilegeDesktopStreakTargetPkg = null
                    privilegeLastNonLauncherPkg = null
                }
                return true
            }
            return false
        }

        // ═══════════════════════════════════════════════
        // 到期了
        // ═══════════════════════════════════════════════

        if (!isCarrier) {
            // foreground 明确 → 根据这个 app 决定
            if (SegmentBlacklist.shouldLock(context, detectedPkg)) {
                // 黑名单 → 立即锁
                LogExporter.logEvent("TemporaryAccess", "[EXPIRY_LOCK] blacklist=$detectedPkg")
                cancelTemporaryAccessExpiryRelockRetry()
                val label = resolveTemporaryAccessTargetLabel(context, detectedPkg)
                showLockScreen(reason = "Blocked: $detectedPkg", isBlacklist = true, blockedAppLabel = label, blockedPackageName = detectedPkg)
                if (isLocked) {
                    resetDesktopStreak()
                    temporaryAccessLastNonLauncherPkg = null
                    temporaryAccessLastDetectedPkg = null
                    temporaryAccessExpiredAt = 0L
                    temporaryAccessTargetPackageName = null
                    privilegeDesktopStreakTargetPkg = null
                    privilegeLastNonLauncherPkg = null
                }
                return true
            }
            // 白名单 → 放行，重置计时器，扔任务单
            resetDesktopStreak()
            temporaryAccessExpiredAt = 0L
            temporaryAccessTargetPackageName = null
            privilegeDesktopStreakTargetPkg = null
            privilegeLastNonLauncherPkg = null
            return false
        }

        // foreground 是桌面/IronWill → 只计时不穿透，60s 满才查
        // 没满 60s 就先放行，满了就锁
        if (desktopStreakTickStartMs == 0L) {
            desktopStreakTickStartMs = now
            temporaryAccessLogSessionId?.let { lifecycleNormalEventBridge.recordDesktopTimerStarted(it, "POST_EXPIRY_CARRIER") }
            LogExporter.i("DesktopTimer", "⏱ 启动 | phase=已到期 | accumulated=${desktopStreakAccumMs}ms")
        }
        val streakMs = now - desktopStreakTickStartMs
        val totalMs = desktopStreakAccumMs + streakMs
        temporaryAccessLogSessionId?.let { lifecycleNormalEventBridge.recordDesktopTimerTick(it, totalMs) }
        if (totalMs >= DESKTOP_STREAK_LOCK_MS) {
            // 35s 满，使用 ExpiryResolver History fallback 综合裁决
            val recentRecords = getRecentForegroundRecords().reversed().map { it.pkg }
            val candidateHistory = if (recentRecords.isNotEmpty()) recentRecords else listOfNotNull(temporaryAccessLastNonLauncherPkg)
            val (fallbackDecision, _) = ExpiryResolver.resolveHistoryFallback(
                recentForegroundPackages = candidateHistory,
                isBlacklist = { SegmentBlacklist.shouldLock(context, it) },
                isLauncher = { CreditManager.isLauncher(it) }
            )
            val lockPkg = if (fallbackDecision == ExpiryDecision.LOCK) {
                candidateHistory.firstOrNull { !CreditManager.isLauncher(it) && SegmentBlacklist.shouldLock(context, it) }
                    ?: temporaryAccessLastNonLauncherPkg
            } else null

            if (lockPkg == null) {
                resetDesktopStreak()
                temporaryAccessExpiredAt = 0L
                temporaryAccessTargetPackageName = null
                privilegeDesktopStreakTargetPkg = null
                privilegeLastNonLauncherPkg = null
                return false
            }
            // 🛑 [Noise Reduction] 同上(同一 lockPkg / 30s 窗口节流)
            if (lockPkg != lastDesktopTimerLogPkg || now - lastDesktopTimerLogMs >= DESKTOP_TIMER_LOG_THROTTLE_MS) {
                LogExporter.i("DesktopTimer", "🔒 触发 | phase=已到期 | total=${totalMs}ms | lock=$lockPkg")
                lastDesktopTimerLogPkg = lockPkg
                lastDesktopTimerLogMs = now
            }
            cancelTemporaryAccessExpiryRelockRetry()
            val label = resolveTemporaryAccessTargetLabel(context, lockPkg)
            showLockScreen(reason = "Blocked: $lockPkg", isBlacklist = true, blockedAppLabel = label, blockedPackageName = lockPkg)
            if (isLocked) {
                resetDesktopStreak()
                temporaryAccessLastNonLauncherPkg = null
                temporaryAccessLastDetectedPkg = null
                temporaryAccessExpiredAt = 0L
                temporaryAccessTargetPackageName = null
                privilegeDesktopStreakTargetPkg = null
                privilegeLastNonLauncherPkg = null
            }
            return true
        }
        // 还没满 60s → 先放行，计时器继续跑
        return false
    }

    private fun resetDesktopStreak() {
        if (desktopStreakTickStartMs != 0L) {
            temporaryAccessLogSessionId?.let { lifecycleNormalEventBridge.recordDesktopTimerStopped(it, "STATE_RESET") }
        }
        desktopStreakAccumMs = 0L
        desktopStreakTickStartMs = 0L
        lastDesktopStreakCarrierPkg = null
        // 🛑 [Noise Reduction] streak 重置 = 状态变化,允许下一次触发重新打日志
        lastDesktopTimerLogPkg = null
        lastDesktopTimerLogMs = 0L
    }

    // MonitorService is now the primary service
    private var mMonitorService: MonitorService? = null
    private var mCoreService: CoreService? = null
    
    // Status
    private val _isLocked = AtomicBoolean(false)
    val isLocked: Boolean get() = _isLocked.get()

    private val _isAntiUninstallLock = AtomicBoolean(false)
    val isAntiUninstallLock: Boolean get() = _isAntiUninstallLock.get()

    // 🚀 [NEW] Flag to indicate lock is due to blacklist app
    private val _isBlacklistLock = AtomicBoolean(false)
    val isBlacklistLock: Boolean get() = _isBlacklistLock.get()

    // 🚀 [NEW] Flag to indicate lock is due to app time window restriction
    private val _isAppWindowLock = AtomicBoolean(false)
    val isAppWindowLock: Boolean get() = _isAppWindowLock.get()

    // [WP-1] Accessibility generation that successfully attached the current overlay.
    // Non-persistent, process-local. Committed ONLY after targetWM.addView succeeds.
    // -1 = no generation committed yet. Used to classify current vs stale locks.
    @Volatile private var mAttachedLockGeneration: Int = -1

    // UI Elements
    private var btnEmergency: Button? = null
    private var btnWeeklyPrivilege: Button? = null
    private var btnBackToHome: Button? = null
    private var btnTemporaryAccess: Button? = null
    private var btnTemporaryAccessQuickTen: Button? = null
    private var btnTemporaryAccessHelp: ImageButton? = null
    private var penaltyOverlay: View? = null
    private var penaltyMessage: TextView? = null
    private var penaltyOk: Button? = null
    // 🆕 新 UI 元素
    private var tvUsableCountBig: TextView? = null
    private var tvSurplusBorrowLine: TextView? = null
    private var tvRestCountdownInCard: TextView? = null
    private var currentLockRestrictionMode: LockOverlayRestrictionMode = LockOverlayRestrictionMode.OTHER

    interface AlarmOverlayController {
        fun launchQrScan()
        fun onAlarmOverlayFinished()
        fun onScanCancel() {}
        fun toggleTorch(): Boolean { return false }
        fun isTorchOn(): Boolean { return false }
    }

    // ── 会话级复习数据（仅记录用户操作过的题目） ──
    data class SessionPoemEntry(
        val poem: AlarmChallengeManager.Poem,
        val status: String,       // "correct" | "skipped" | "bookmarked"
        val userAnswer: String? = null
    )

    data class SessionWordEntry(
        val word: AlarmChallengeManager.Word,
        val status: String,       // "correct" | "skipped" | "bookmarked"
        val userAnswer: String? = null
    )

    data class SessionClozeEntry(
        val cloze: AlarmChallengeManager.ClozeQuestion,
        val status: String,       // "correct" | "skipped"
        val userAnswer: String? = null
    )

    data class SessionCustomQuestionEntry(
        val question: com.decikeeper.app.data.questionbank.CustomQuestion,
        val status: String,       // "correct" | "wrong" | "skipped" | "bookmarked"
        val userAnswer: String? = null
    )

    private val sessionPoems = mutableListOf<SessionPoemEntry>()
    private val sessionWords = mutableListOf<SessionWordEntry>()
    private val sessionClozes = mutableListOf<SessionClozeEntry>()
    private val sessionCustomQuestions = mutableListOf<SessionCustomQuestionEntry>()


    // Test alarm configuration (set before starting test alarm)
    data class TestAlarmConfig(
        val useQr: Boolean = true,
        val useMath: Boolean = true,
        val usePoem: Boolean = true,
        val useWord: Boolean = true,
        val useCloze: Boolean = false,
        val useCustom: Boolean = false,
        val mathCount: Int = 1,
        val poemCount: Int = 1,
        val wordCount: Int = 1,
        val clozeCount: Int = 3,
        val customCount: Int = 3,
        val vibrationIntensity: Int = 7,
        val vibrationMode: AlarmScheduler.VibrationMode = AlarmScheduler.VibrationMode.PROGRESSIVE,
        val unlockMode: AlarmScheduler.UnlockMode = AlarmScheduler.UnlockMode.SINGLE,
        val stage1Types: Set<String> = setOf("qr", "math"),
        val stage2Types: Set<String> = setOf("math", "poem", "word"),
        val stageCooldownSeconds: Int = 0,
        val alarmMode: AlarmScheduler.AlarmMode = AlarmScheduler.AlarmMode.VIBRATE_AND_RING,
        val ringtoneUri: String? = null,
        val ringVolume: Int = 7,
        val progressiveMinIntensity: Int = 1,
        val progressiveMaxIntensity: Int = 10,
        val progressiveMaxRingVolume: Int = 10,
        val autoEscalate: Boolean = false,
        val stage1CustomBanks: Map<String, Int> = emptyMap(),
        val stage2CustomBanks: Map<String, Int> = emptyMap(),
        val snoozeMinutes: Int = 10,
        val snoozeMaxCount: Int = 2
    )

    // ── 闹钟覆盖层共享状态（保留在本对象，供 LockAlarmOverlayController 通过 internal getter 访问）──
    // ClozePagerAdapterTest 通过反射注入 mAlarmView / mContext / sessionPoems，因此这些字段必须留在 LockWindowManager。
    private var mAlarmView: View? = null

    // 🩺 诊断用 getter，用于排查扫码后黑屏等覆盖层渲染问题
    val isOverlayActive: Boolean get() = LockAlarmOverlayController.isActiveNow()
    val isOverlayAttached: Boolean get() = LockAlarmOverlayController.isAttachedNow()

    // ── 供 LockAlarmOverlayController 读取的共享状态 getter ──
    internal fun getAlarmContext(): Context? = mContext
    internal fun getAlarmWindowManager(): WindowManager? = mWindowManager
    internal fun getAlarmCoreService(): CoreService? = mCoreService
    internal fun getAlarmUiHandler(): Handler = mUiHandler
    internal fun getAlarmView(): View? = mAlarmView
    internal fun setAlarmView(view: View?) { mAlarmView = view }
    internal fun getSessionPoems(): MutableList<SessionPoemEntry> = sessionPoems
    internal fun getSessionWords(): MutableList<SessionWordEntry> = sessionWords
    internal fun getSessionClozes(): MutableList<SessionClozeEntry> = sessionClozes
    internal fun getSessionCustomQuestions(): MutableList<SessionCustomQuestionEntry> = sessionCustomQuestions
    internal fun isCurrentOverlayInline(): Boolean =
        mAlarmView != null && mAlarmView?.layoutParams !is WindowManager.LayoutParams

    // 🚀 [System Init Overlay] Separate non-interactive overlay for config changes
    private var mInitLockView: View? = null
    private var mInitActiveWindowManager: WindowManager? = null
    private var initDismissRunnable: Runnable? = null
    private var initCountdownRunnable: Runnable? = null
    private val isInitLockShowing = AtomicBoolean(false)
    
    // 🚀 [NEW] Separate Window for Rest Request (Resolves Input/Keyboard Focus Issues)
    private var mRestWindow: View? = null
    
    // 🚀 [Fix] Track which WindowManager added the view to ensure correct removal
    private var mActiveWindowManager: WindowManager? = null
    private var mActiveRestWindowManager: WindowManager? = null
    
    // 🚀 [New] Track Reminder Dialog to allow auto-dismissal
    private var mReminderDialog: AlertDialog? = null

    // 🚀 [NEW] Track the name/label of the app that triggered the lock
    private var mBlockedAppLabel: String? = null
    @Volatile
    private var mLockEntryReasonRaw: String? = null

    // 临时解锁（秒级）状态：用于扫码/短查看，不计入休息/特权
    @Volatile
    private var temporaryAccessUntil = 0L
    @Volatile
    private var temporaryAccessSeconds = 0
    @Volatile
    private var temporaryAccessStartAt = 0L
    @Volatile
    private var temporaryAccessChargedMinutes = 0.0
    private var temporaryAccessCurrentUsageKey: String? = null
    private var temporaryAccessNextUsageKey: String? = null
    private var temporaryAccessBorrowKey: String? = null
    private var temporaryAccessCurrentChargeSeconds = 0
    private var temporaryAccessNextChargeSeconds = 0
    private var temporaryAccessBorrowChargeSeconds = 0
    @Volatile
    private var temporaryReuseCooldownUntil = 0L
    @Volatile
    private var temporaryAccessExpiredAt = 0L
    @Volatile
    private var temporaryAccessTargetPackageName: String? = null
    private var temporaryAccessLogRequestId: String? = null
    private var temporaryAccessLogSessionId: String? = null
    private var temporaryAccessLogEmergency: Boolean = false
    private val temporaryCooldownSettlement = LifecycleCooldownSettlement()
    internal var privilegeDesktopStreakTargetPkg: String? = null
    internal var privilegeLastNonLauncherPkg: String? = null
    @Volatile
    var temporaryAccessFinalRelockNeeded: Boolean = false
    /** 临时访问到期后需要重锁的目标包名 — 防止对非目标应用误锁 */
    var temporaryAccessFinalRelockTargetPkg: String? = null
        private set

    // --- 频繁锁屏惩罚 ---
    private var mPenaltyWindowStart = 0L           // 当前30秒窗口起始时间
    private var mPenaltyHitCount = 0               // 当前窗口内返回桌面次数
    private val PENALTY_WINDOW_MS = 30_000L        // 30秒窗口
    private val PENALTY_EXTEND_COOL_MS = 30_000L   // 冷却延长 +30s
    private val PENALTY_DEDUCT_SECS = 30           // 扣除额度 30s
    private val PENALTY_THRESHOLD = 5              // 触发惩罚的次数
    private var mPendingPenaltyMessage: String? = null  // 待显示的惩罚弹窗消息（在锁屏View上展示）

    // --- 空档期应用时间窗豁免 ---
    private val mGapAppWindowDismissCount = mutableMapOf<String, Int>()
    private val mGapAppWindowExempt = mutableSetOf<String>()
    private val GAP_APP_WINDOW_EXEMPT_THRESHOLD = 3

    fun resetGapAppWindowExemptions() {
        if (mGapAppWindowExempt.isNotEmpty() || mGapAppWindowDismissCount.isNotEmpty()) {
            mGapAppWindowDismissCount.clear()
            mGapAppWindowExempt.clear()
        }
    }

    // --- 锁屏界面延迟显示 ---
    // 勿扰/时间窗锁不在锁屏界面显示，等用户解锁后再显示
    // 闹钟挑战锁因为有防关机功能，需要在锁屏界面显示
    private data class DeferredLockParams(
        val reason: String,
        val isBlacklist: Boolean,
        val blockedAppLabel: String?,
        val blockedPackageName: String?,
        val isScreenOnRedetection: Boolean = false,
        val timestamp: Long
    )
    @Volatile
    private var mDeferredLock: DeferredLockParams? = null
    private var mUserPresentReceiver: android.content.BroadcastReceiver? = null
    private var mUserPresentReceiverRegistered = false
    private const val DEFERRED_LOCK_TIMEOUT_MS = 5 * 60 * 1000L  // 延迟锁屏5分钟超时

    /**
     * 检查设备是否处于锁屏状态。
     */
    private fun isDeviceLocked(context: Context): Boolean {
        val km = context.getSystemService(Context.KEYGUARD_SERVICE) as? android.app.KeyguardManager
        return km?.isKeyguardLocked == true
    }

    /**
     * 注册解锁广播接收器，在用户解锁后显示延迟的锁屏。
     */
    private fun registerUserPresentReceiver(context: Context) {
        if (mUserPresentReceiverRegistered) return
        mUserPresentReceiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                if (intent.action != Intent.ACTION_USER_PRESENT) return
                val deferred = mDeferredLock ?: return
                mDeferredLock = null
                unregisterUserPresentReceiver(ctx)
                // 超时检查：延迟锁屏超过5分钟则丢弃
                val age = System.currentTimeMillis() - deferred.timestamp
                if (age > DEFERRED_LOCK_TIMEOUT_MS) {
                    Log.i(TAG, "[DeferredLock] Deferred lock expired (${age/1000}s > ${DEFERRED_LOCK_TIMEOUT_MS/1000}s), discarding")
                    LogExporter.logEvent("LockScreen", "[DEFERRED_EXPIRED] Discarding stale deferred lock")
                    return
                }
                Log.i(TAG, "[DeferredLock] User unlocked, showing deferred lock: reason=${deferred.reason}, isScreenOnRedetection=${deferred.isScreenOnRedetection}")
                LogExporter.logEvent("LockScreen", "[DEFERRED_SHOW] User unlocked, showing deferred lock: reason=${deferred.reason}, isScreenOnRedetection=${deferred.isScreenOnRedetection}")
                showLockScreen(
                    reason = deferred.reason,
                    isBlacklist = deferred.isBlacklist,
                    blockedAppLabel = deferred.blockedAppLabel,
                    blockedPackageName = deferred.blockedPackageName,
                    isScreenOnRedetection = deferred.isScreenOnRedetection
                )
            }
        }
        val filter = IntentFilter(Intent.ACTION_USER_PRESENT)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(mUserPresentReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(mUserPresentReceiver, filter)
        }
        mUserPresentReceiverRegistered = true
    }

    /**
     * 注销解锁广播接收器。
     */
    private fun unregisterUserPresentReceiver(context: Context) {
        if (!mUserPresentReceiverRegistered) return
        try {
            context.unregisterReceiver(mUserPresentReceiver)
        } catch (_: Exception) {}
        mUserPresentReceiver = null
        mUserPresentReceiverRegistered = false
    }

    /**
     * 检查锁屏 View 当前是否仍附着在 WindowManager 上。
     * 当系统自动移除覆盖窗口时（某些 OEM 会回收不可交互的悬浮窗），
     * mLockView.parent 会变为 null，此时需要重新展示锁屏。
     */
    fun isLockScreenViewAttached(): Boolean {
        return mLockView?.parent != null
    }
    private var temporaryAccessExpiryRunnable: Runnable? = null
    private var temporaryAccessExpiryRelockRetryRunnable: Runnable? = null
    @Volatile
    private var temporaryAccessExpiryRelockRetryCount = 0

    // ===== Boundary 1 / Bug 1 — expiry operation state machine (REQ-004 / REQ-005) =====
    /** Stable identity of the current expiry operation; per-session; generated on first
     *  expiry tick after `beginExpiryOperationForSession`. Carried in all post-fix logs. */
    @Volatile
    private var expiryOperationId: String? = null
    /** Bound to `temporaryAccessLogSessionId`; cleared together with `expiryOperationId`. */
    @Volatile
    private var expiryOperationSessionId: String? = null
    /** Timestamp of the first expiry tick that successfully entered EXPIRY_HANDLING. */
    @Volatile
    private var expiryOperationStartedAtMs: Long = 0L
    /** Counter feeding the 60s / 5-tick retry-storm guard (REQ-005). */
    @Volatile
    private var expiryOperationReentryCount: Int = 0
    /** One-shot latch ensuring [LOCK_RETRY_STORM] is emitted at most once per storm. */
    @Volatile
    private var expiryStormEventEmitted: Boolean = false
    // ============================================================================
    // 🗳️ 到期时使用 MonitorService 持续记录的 recentForegroundLog（带时间戳环形缓冲区）
    @Volatile
    private var temporaryEmergencyLastUseAtInCooldown = 0L
    @Volatile
    private var temporaryEmergencyRemainingUsesInCooldown = 0
    // 📝 临时解锁期间检测到的最后一个非桌面/IronWill app，用于到期时穿透误报
    private var temporaryAccessLastNonLauncherPkg: String? = null
    // 📝 临时解锁期间最近一次前台检测到的 pkg（用于到期定时器回退路径判断）
    private var temporaryAccessLastDetectedPkg: String? = null
    // ⏱️ 到期后桌面累计计时器（60s 兜底，防止过渡期误锁）
    private var desktopStreakAccumMs = 0L
    private var desktopStreakTickStartMs = 0L
    private var lastDesktopStreakCarrierPkg: String? = null  // 上一次 carrier 的 pkg，用于检测桌面↔IronWill 切换
    // 🛑 [Noise Reduction] 同一 lockPkg 的 🔒 触发只打一次,状态变化 / streak 重置后再打
    private var lastDesktopTimerLogPkg: String? = null
    private var lastDesktopTimerLogMs: Long = 0L
    private const val DESKTOP_STREAK_LOCK_MS = 35_000L
    private const val DESKTOP_TIMER_LOG_THROTTLE_MS = 30_000L  // 同 lockPkg 至少 30s 才重打
    private const val LOCK_STATE_PREFS = "decikeeper_lock_window_state"
    private const val KEY_TEMP_ACCESS_UNTIL = "temp_access_until"
    private const val KEY_TEMP_ACCESS_SECONDS = "temp_access_seconds"
    private const val KEY_TEMP_ACCESS_START_AT = "temp_access_start_at"
    private const val KEY_TEMP_ACCESS_CHARGED_MINUTES_BITS = "temp_access_charged_minutes_bits"
    private const val KEY_TEMP_ACCESS_CURRENT_USAGE_KEY = "temp_access_current_usage_key"
    private const val KEY_TEMP_ACCESS_NEXT_USAGE_KEY = "temp_access_next_usage_key"
    private const val KEY_TEMP_ACCESS_BORROW_KEY = "temp_access_borrow_key"
    private const val KEY_TEMP_ACCESS_CURRENT_CHARGE_SECONDS = "temp_access_current_charge_seconds"
    private const val KEY_TEMP_ACCESS_NEXT_CHARGE_SECONDS = "temp_access_next_charge_seconds"
    private const val KEY_TEMP_ACCESS_BORROW_CHARGE_SECONDS = "temp_access_borrow_charge_seconds"
    private const val KEY_TEMP_REUSE_COOLDOWN_UNTIL = "temp_reuse_cooldown_until"
    private const val KEY_TEMP_ACCESS_TARGET_PACKAGE = "temp_access_target_package"
    private const val KEY_TEMP_LOG_REQUEST_ID = "temp_log_request_id"
    private const val KEY_TEMP_LOG_SESSION_ID = "temp_log_session_id"
    private const val KEY_TEMP_LOG_EMERGENCY = "temp_log_emergency"
    private const val KEY_TEMP_EMERGENCY_LAST_USE_AT = "temp_emergency_last_use_at"
    private const val KEY_TEMP_EMERGENCY_REMAINING_USES = "temp_emergency_remaining_uses"
    
    // For debounce
    private var mLastLockTime = 0L

    // 🚀 [Anti-Deadlock] Track consecutive "回到桌面" clicks to escalate grace period
    private var mConsecutiveHomeClicks = 0
    private var mLastHomeClickTime = 0L
    // 🚀 [Anti-Deadlock] Package dismissed by user Home click - suppress re-lock until user leaves this app
    @Volatile
    var homeClickPendingPackage: String? = null
        private set
    @Volatile
    private var homeClickPendingSetAt = 0L
    // 🚀 [回到桌面宽限] 返回桌面后 3s 内不因小窗/浮窗触发锁屏（用户要求 2026-08-15）
    private const val DESKTOP_RETURN_GRACE_MS = 3000L
    private var desktopReturnGraceUntil = 0L

    /** 回到桌面：启动 3s 小窗锁宽限。 */
    fun setDesktopReturnGrace(now: Long = System.currentTimeMillis()) {
        desktopReturnGraceUntil = now + DESKTOP_RETURN_GRACE_MS
    }

    /** 是否处于回到桌面后的 3s 小窗锁宽限期。 */
    fun isInDesktopReturnGrace(now: Long = System.currentTimeMillis()): Boolean =
        now < desktopReturnGraceUntil

    // 🚀 Helper to check service state safely
    fun isServiceActive(): Boolean {
        return (mCoreService != null || CoreService.instance != null) && CoreService.isServiceActive
    }
    
    // 🚀 [NEW] Flag to pause monitoring while user is typing
    @Volatile
    var isInputPanelOpen = false
        private set(value) {
            field = value
            Log.d("LockWindow", "isInputPanelOpen set to $value")
        }

    internal fun shouldPauseForegroundBlockingChecks(): Boolean {
        return isInputPanelOpen || isSettingsPassThrough
    }
    
    // 🚀 [System Dialog Compat] Track whether a system dialog (power menu etc.) is visible
    @Volatile
    private var isSystemDialogVisible = false
    private var systemDialogRestoreRunnable: Runnable? = null
    private const val SYSTEM_DIALOG_RESTORE_DELAY_MS = 5000L // 5s safety restore timeout
    private const val SYSTEM_DIALOG_EVENT_RESTORE_GUARD_MS = 4500L
    private const val SYSTEM_DIALOG_DEFER_LOG_THROTTLE_MS = 800L
    private const val DEMOTED_ALPHA = 0.18f
    private const val STATUS_PULL_THRESHOLD_DP = 24f
    private enum class SystemDialogCompatMode {
        NONE,
        REAL_DEMOTE,
        FALLBACK_PASS_THROUGH
    }
    @Volatile
    private var systemDialogCompatMode = SystemDialogCompatMode.NONE
    private var systemDialogNormalParams: WindowManager.LayoutParams? = null
    private var systemDialogNormalViewAlpha = 1f
    @Volatile
    private var systemDialogShownAtMs = 0L
    private var systemDialogLastDeferLogAtMs = 0L
    private var statusBarFocusRestoreRunnable: Runnable? = null
    
    // Refresh - 🚀 [Thread Isolation] 独立后台线程驱动定时器，防止主线程阻塞导致倒计时冻结
    private val mUiHandler = Handler(Looper.getMainLooper()) // 仅用于 UI 更新
    private var mTimerThread: HandlerThread? = null // 独立定时器线程
    private var mTimerHandler: Handler? = null // 后台定时器 Handler
    private var mRefreshRunnable: Runnable? = null
    
    private fun ensureTimerThread(): Handler {
        if (mTimerThread == null || mTimerThread?.isAlive != true) {
            mTimerThread = HandlerThread("IronWill-Timer").apply { start() }
            mTimerHandler = Handler(mTimerThread!!.looper)
            LogExporter.logEvent("Timer", "[THREAD] Created HandlerThread: ${mTimerThread?.name}, id=${mTimerThread?.id}")
        }
        return mTimerHandler!!
    }

    private fun cloneLayoutParams(source: WindowManager.LayoutParams): WindowManager.LayoutParams {
        return WindowManager.LayoutParams().apply { copyFrom(source) }
    }

    private fun buildNormalLockParams(isA11y: Boolean): WindowManager.LayoutParams {
        return WindowManager.LayoutParams().apply {
            width = WindowManager.LayoutParams.MATCH_PARENT
            height = WindowManager.LayoutParams.MATCH_PARENT
            format = PixelFormat.TRANSLUCENT
            type = if (isA11y) {
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                WindowManager.LayoutParams.TYPE_PHONE
            }
            flags = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_FULLSCREEN or
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                systemUiVisibility = (View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        or View.SYSTEM_UI_FLAG_FULLSCREEN
                        or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or
                WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE
            alpha = 1.0f
        }
    }

    private fun buildDemotedParams(baseParams: WindowManager.LayoutParams): WindowManager.LayoutParams {
        val demoted = cloneLayoutParams(baseParams)
        demoted.type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            WindowManager.LayoutParams.TYPE_PHONE
        }
        demoted.flags = demoted.flags or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
        demoted.alpha = DEMOTED_ALPHA
        return demoted
    }

    private fun buildInitOverlayParams(isA11y: Boolean): WindowManager.LayoutParams {
        return WindowManager.LayoutParams().apply {
            width = WindowManager.LayoutParams.MATCH_PARENT
            height = WindowManager.LayoutParams.MATCH_PARENT
            format = PixelFormat.TRANSLUCENT
            type = if (isA11y) {
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                WindowManager.LayoutParams.TYPE_PHONE
            }
            flags = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_FULLSCREEN or
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                systemUiVisibility = (View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        or View.SYSTEM_UI_FLAG_FULLSCREEN
                        or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
            alpha = 1.0f
        }
    }

    private fun typeName(type: Int): String {
        return when (type) {
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY -> "TYPE_ACCESSIBILITY_OVERLAY"
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY -> "TYPE_APPLICATION_OVERLAY"
            WindowManager.LayoutParams.TYPE_PHONE -> "TYPE_PHONE"
            else -> "TYPE_$type"
        }
    }

    private fun clearSystemDialogCompatState(resetVisibility: Boolean = true) {
        if (resetVisibility) {
            isSystemDialogVisible = false
        }
        systemDialogRestoreRunnable?.let { mUiHandler.removeCallbacks(it) }
        systemDialogRestoreRunnable = null
        systemDialogCompatMode = SystemDialogCompatMode.NONE
        systemDialogNormalParams = null
        systemDialogNormalViewAlpha = 1f
        systemDialogShownAtMs = 0L
        systemDialogLastDeferLogAtMs = 0L
    }

    internal fun shouldAcceptSystemDialogEventRestoreByElapsed(
        elapsedMs: Long,
        guardMs: Long = SYSTEM_DIALOG_EVENT_RESTORE_GUARD_MS
    ): Boolean {
        return elapsedMs >= guardMs
    }

    internal fun shouldAcceptSystemDialogEventRestore(nowMs: Long = System.currentTimeMillis()): Boolean {
        if (!isSystemDialogVisible) return true
        val shownAt = systemDialogShownAtMs
        if (shownAt <= 0L) return false
        val elapsed = nowMs - shownAt
        return shouldAcceptSystemDialogEventRestoreByElapsed(elapsed)
    }

    fun init(context: Context) {
        if (mContext == null) {
            mContext = context.applicationContext
            mWindowManager = mContext?.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            restoreTemporaryAccessState(mContext!!)
        }
        if (context is MonitorService) {
            mMonitorService = context
        } else if (context is CoreService) {
            mCoreService = context
        }
        scheduleTemporaryAccessExpiryIfNeeded()
    }

    val isAlarmOverlayShowing: Boolean
        get() = LockAlarmOverlayController.isActiveNow()

    val isAlarmOverlayActuallyShowing: Boolean
        get() = LockAlarmOverlayController.isActiveNow() &&
            LockAlarmOverlayController.isAttachedNow() &&
            mAlarmView?.parent != null

    // ── 测试闹钟配置覆盖项（由 AlarmVibrationService 读取） ──
    val testVibrationMode: AlarmScheduler.VibrationMode? get() = LockAlarmOverlayController.testVibrationMode
    val testVibrationIntensity: Int? get() = LockAlarmOverlayController.testVibrationIntensity
    val testAlarmMode: AlarmScheduler.AlarmMode? get() = LockAlarmOverlayController.testAlarmMode
    val testRingtoneUri: String? get() = LockAlarmOverlayController.testRingtoneUri
    val testRingVolume: Int? get() = LockAlarmOverlayController.testRingVolume
    val testProgressiveMinIntensity: Int? get() = LockAlarmOverlayController.testProgressiveMinIntensity
    val testProgressiveMaxIntensity: Int? get() = LockAlarmOverlayController.testProgressiveMaxIntensity
    val testProgressiveMaxRingVolume: Int? get() = LockAlarmOverlayController.testProgressiveMaxRingVolume
    val testAutoEscalate: Boolean? get() = LockAlarmOverlayController.testAutoEscalate

    fun setTestAlarmConfig(config: TestAlarmConfig) {
        LockAlarmOverlayController.setTestAlarmConfig(config)
    }

    fun showAlarmOverlay(
        context: Context,
        alarmId: Int,
        snoozeMinutes: Int,
        controller: AlarmOverlayController,
        useInline: Boolean = false,
        newSession: Boolean = false
    ) {
        LockAlarmOverlayController.showAlarmOverlay(context, alarmId, snoozeMinutes, controller, useInline, newSession)
    }

    fun showAlarmOverlayIfHidden() {
        LockAlarmOverlayController.showAlarmOverlayIfHidden()
    }

    fun showAlarmOverlayInlineInActivity(activity: android.app.Activity, controller: AlarmOverlayController): Boolean {
        return LockAlarmOverlayController.showAlarmOverlayInlineInActivity(activity, controller)
    }

    fun hideAlarmOverlayForPermission() {
        LockAlarmOverlayController.hideAlarmOverlayForPermission()
    }

    fun setAlarmOverlayPassthrough(enable: Boolean) {
        LockAlarmOverlayController.setAlarmOverlayPassthrough(enable)
    }

    fun cancelAlarmScan() {
        LockAlarmOverlayController.cancelAlarmScan()
    }

    fun updateAlarmOverlayTorchState(isOn: Boolean) {
        LockAlarmOverlayController.updateTorchState(isOn)
    }

    fun setLockScreenVisibleForAlarmScan(visible: Boolean) {
        mUiHandler.post {
            isLockScreenHiddenForAlarmScan = !visible
            val view = mLockView ?: return@post
            if (view.parent == null) return@post
            view.visibility = if (visible) View.VISIBLE else View.GONE
            view.alpha = if (visible) 1.0f else 0.0f
            if (visible && isAlarmActiveForLockSuppression()) {
                view.visibility = View.GONE
                view.alpha = 0.0f
            }
            val effectiveVisible = view.visibility == View.VISIBLE
            try {
                val wm = mActiveWindowManager ?: return@post
                val lp = view.layoutParams as? WindowManager.LayoutParams ?: return@post
                if (!effectiveVisible) {
                    lp.flags = lp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                } else {
                    lp.flags = lp.flags and (WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv() and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv())
                }
                wm.updateViewLayout(view, lp)
            } catch (e: Exception) {
                Log.w(TAG, "setLockScreenVisibleForAlarmScan updateViewLayout failed: visible=$visible", e)
            }
            Log.i(TAG, "setLockScreenVisibleForAlarmScan: visible=$visible, effectiveVisible=$effectiveVisible")
        }
    }

    fun onAlarmScanResult(success: Boolean) {
        LockAlarmOverlayController.onAlarmScanResult(success)
    }

    fun suppressLockScreenForAlarm() {
        mUiHandler.post {
            isLockScreenSuppressedByAlarm = true
            val view = mLockView ?: return@post
            if (view.parent == null) return@post
            view.visibility = View.GONE
            view.alpha = 0.0f
            try {
                val wm = mActiveWindowManager ?: return@post
                val lp = view.layoutParams as? WindowManager.LayoutParams ?: return@post
                lp.flags = lp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                wm.updateViewLayout(view, lp)
            } catch (e: Exception) {
                Log.w(TAG, "suppressLockScreenForAlarm updateViewLayout failed", e)
            }
            Log.i(TAG, "suppressLockScreenForAlarm: lock view suppressed for alarm")
        }
    }

    fun resumeLockScreenAfterAlarmDismiss() {
        mUiHandler.post {
            isLockScreenSuppressedByAlarm = false
            isLockScreenHiddenForAlarmScan = false
            val view = mLockView ?: return@post
            if (view.parent == null || !isLocked) return@post
            view.visibility = View.VISIBLE
            view.alpha = 1.0f
            try {
                val wm = mActiveWindowManager ?: return@post
                val lp = view.layoutParams as? WindowManager.LayoutParams ?: return@post
                lp.flags = lp.flags and (WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv() and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv())
                wm.updateViewLayout(view, lp)
                view.isFocusable = true
                view.isFocusableInTouchMode = true
                view.requestFocus()
            } catch (e: Exception) {
                Log.w(TAG, "resumeLockScreenAfterAlarmDismiss updateViewLayout failed", e)
            }
            Log.i(TAG, "resumeLockScreenAfterAlarmDismiss: lock view resumed after alarm dismiss")
        }
    }

    fun detachAlarmController(controller: AlarmOverlayController) {
        LockAlarmOverlayController.detachAlarmController(controller)
    }

    fun reattachAlarmController(controller: AlarmOverlayController) {
        LockAlarmOverlayController.reattachAlarmController(controller)
    }

    fun startAlarmInlineScan() {
        LockAlarmOverlayController.startAlarmInlineScan()
    }

    fun stopAlarmInlineScan() {
        LockAlarmOverlayController.stopAlarmInlineScan()
    }

    fun fallbackFromQrToMath() {
        LockAlarmOverlayController.fallbackFromQrToMath()
    }

    fun reassertAlarmOverlayOverSystemDialog(reason: String) {
        LockAlarmOverlayController.reassertAlarmOverlayOverSystemDialog(reason)
    }

    fun ensureAlarmOverlayInWindowMode(): Boolean {
        return LockAlarmOverlayController.ensureAlarmOverlayInWindowMode()
    }

    fun onScanChallengePassed() {
        LockAlarmOverlayController.onScanChallengePassed()
    }

    internal fun isStage2QrEnabled(stage2Types: Set<String>, isQrEnabled: Boolean): Boolean {
        return LockAlarmOverlayController.isStage2QrEnabled(stage2Types, isQrEnabled)
    }

    private fun onAnyChallengePassed() {
        LockAlarmOverlayController.onAnyChallengePassed()
    }

    /** v3.1.1 Bug-Fix: ViewPager2 适配器, 每页一道 cloze 卡片.
     *  v3.1.1 fix: 子页 FrameLayout 高 MATCH_PARENT (ViewPager2 硬约束: 子页必须 fill 整个 ViewPager2). */
    internal class ClozePagerAdapter(
        private val ctx: Context,
        private val items: List<SessionClozeEntry>,
        private val d: Float
    ) : androidx.recyclerview.widget.RecyclerView.Adapter<ClozePagerAdapter.VH>() {

        class VH(val root: android.widget.FrameLayout) : androidx.recyclerview.widget.RecyclerView.ViewHolder(root)

        override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): VH {
            val frame = android.widget.FrameLayout(ctx).apply {
                layoutParams = android.view.ViewGroup.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT
                )
            }
            return VH(frame)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val frame = holder.root
            frame.removeAllViews()
            // buildReviewClozeCard 位于 LockAlarmOverlayController，本适配器通过跨对象调用访问。
            val card = LockAlarmOverlayController.buildReviewClozeCard(ctx, items[position], d)
            // v3.1.1 fix: card 宽高都 MATCH_PARENT, 撑满 FrameLayout
            card.layoutParams = android.widget.FrameLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT
            )
            // v3.1.1 fix: 卡片内容用 NestedScrollView 包裹, 解决内容不能竖向滚动 + 显示不全
            val scrollWrapper = androidx.core.widget.NestedScrollView(ctx).apply {
                layoutParams = android.widget.FrameLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT
                )
                isFillViewport = true  // 内容短时撑满 viewport, 避免短卡片贴顶部
                addView(card)
            }
            frame.addView(scrollWrapper)
        }

        override fun getItemCount(): Int = items.size
    }

    fun showSystemInitOverlay(reason: String, durationMs: Long = 10000L) {
        val context = mContext ?: return
        if (!StartupProtectionManager.shouldKeepInitialization(context)) {
            LogExporter.logEvent("InitOverlay", "[SKIP] anti-uninstall initialization disabled, reason=$reason")
            return
        }
        val canUseA11y = mCoreService != null && isServiceActive()

        if (isInitLockShowing.get()) {
            LogExporter.logEvent("InitOverlay", "[SKIP] already showing, reason=$reason")
            return
        }

        isInitLockShowing.set(true)
        val themedContext = android.view.ContextThemeWrapper(context, ThemeManager.themeRes(context))
        val inflater = LayoutInflater.from(themedContext)
        mInitLockView = inflater.inflate(R.layout.window_fast_lock, null).apply {
            isFocusable = true
            isFocusableInTouchMode = true
            requestFocus()
            setOnKeyListener { _, keyCode, _ ->
                keyCode == android.view.KeyEvent.KEYCODE_BACK ||
                    keyCode == android.view.KeyEvent.KEYCODE_MENU
            }
            setOnTouchListener { _, _ -> true }
        }

        // [Fix: anti-uninstall gap] a11y 不可用时降级到 TYPE_APPLICATION_OVERLAY，
        // 确保覆盖层始终能显示，防止闹钟结束后出现无保护空窗期。
        val targetWM = if (canUseA11y) {
            (mCoreService ?: CoreService.instance)!!.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        } else {
            mWindowManager ?: return
        }
        val params = buildInitOverlayParams(isA11y = canUseA11y)
        try {
            targetWM.addView(mInitLockView, params)
            mInitActiveWindowManager = targetWM
            LogExporter.i("Overlay", "🖥️ 初始化覆盖层 | action=显示 | reason=$reason | duration=${durationMs / 1000}s | a11y=$canUseA11y")
        } catch (e: Exception) {
            isInitLockShowing.set(false)
            mInitLockView = null
            LogExporter.logEvent("InitOverlay", "[SHOW_FAIL] err=${e.javaClass.simpleName} a11y=$canUseA11y")
            return
        }

        initDismissRunnable?.let { mUiHandler.removeCallbacks(it) }
        initDismissRunnable = Runnable { dismissSystemInitOverlay("timeout") }
        mUiHandler.postDelayed(initDismissRunnable!!, durationMs)

        val countdownTv = mInitLockView?.findViewById<android.widget.TextView>(R.id.countdown_text)
        val deadlineMs = System.currentTimeMillis() + durationMs
        val ceilingSeconds = ((durationMs + 999L) / 1000L).toInt()

        fun updateInitCountdown(): Boolean {
            val now = System.currentTimeMillis()
            val seconds = StartupProtectionManager.remainingCountdownSecondsFromDeadline(deadlineMs, now, ceilingSeconds)
            countdownTv?.text = "剩余 $seconds 秒"
            return seconds > 0
        }

        updateInitCountdown()

        initCountdownRunnable?.let { mUiHandler.removeCallbacks(it) }
        val tick = object : Runnable {
            override fun run() {
                if (!isInitLockShowing.get()) return
                if (updateInitCountdown()) {
                    mUiHandler.postDelayed(this, 1000L)
                }
            }
        }
        initCountdownRunnable = tick
        mUiHandler.postDelayed(tick, 1000L)
    }

    fun dismissSystemInitOverlay(reason: String = "manual") {
        if (!isInitLockShowing.get()) return
        initDismissRunnable?.let { mUiHandler.removeCallbacks(it) }
        initDismissRunnable = null
        initCountdownRunnable?.let { mUiHandler.removeCallbacks(it) }
        initCountdownRunnable = null
        try {
            if (mInitLockView != null && mInitActiveWindowManager != null && mInitLockView?.parent != null) {
                mInitActiveWindowManager?.removeView(mInitLockView)
            }
        } catch (e: Exception) {
            LogExporter.logEvent("InitOverlay", "[DISMISS_FAIL] err=${e.javaClass.simpleName}")
        } finally {
            mInitActiveWindowManager = null
            mInitLockView = null
            isInitLockShowing.set(false)
            LogExporter.i("Overlay", "🖥️ 初始化覆盖层 | action=关闭 | reason=$reason")
        }
    }

    fun setCoreService(service: CoreService?) {
        mCoreService = service
        if (service == null) {
            // CoreService 断开时不能盲目关闭定时器线程，否则会冻结锁屏倒计时。
            if (shouldShutdownTimerThreadOnCoreDetach()) {
                shutdownTimerThread()
            } else {
                LogExporter.logEvent(
                    "Timer",
                    "[KEEP] CoreService detached while lock/timer active, keep timer thread alive"
                )
            }
        }
    }

    fun setMonitorService(service: MonitorService) {
        mMonitorService = service
    }
    
    /**
     * 🚀 [Thread Cleanup] 安全关闭后台定时器线程
     */
    private fun shutdownTimerThread() {
        try {
            mTimerHandler?.removeCallbacksAndMessages(null)
            mTimerHandler = null
            mTimerThread?.quitSafely()
            mTimerThread = null
        } catch (e: Exception) {
            Log.e(TAG, "Error shutting down timer thread", e)
        }
    }

    internal fun shouldShutdownTimerThreadOnCoreDetach(): Boolean {
        val hasActiveLockUi = isLocked || isInitLockShowing.get()
        val hasActiveTimerLoop = mRefreshRunnable != null || blacklistMonitorRunnable != null
        return shouldShutdownTimerThreadOnCoreDetach(hasActiveLockUi, hasActiveTimerLoop)
    }

    internal fun shouldShutdownTimerThreadOnCoreDetach(
        hasActiveLockUi: Boolean,
        hasActiveTimerLoop: Boolean
    ): Boolean {
        return !hasActiveLockUi && !hasActiveTimerLoop
    }
    
    // 🚀 [Anti-Deadlock] Clear the pending package once user successfully navigated away
    fun clearHomeClickPendingPackage() {
        homeClickPendingPackage = null
        homeClickPendingSetAt = 0L
        Log.d(TAG, "[Anti-Deadlock] homeClickPendingPackage cleared")
    }

    /** Called only after MonitorService has selected a real launcher foreground observation. */
    fun confirmPendingReturnDesktopExit(evidence: String) {
        val pending = pendingReturnDesktopExit ?: return
        pendingReturnDesktopExit = null
        lifecycleNormalEventBridge.recordReturnDesktopExit(
            exitRequestId = pending.exitRequestId,
            lockRequestId = pending.lockRequestId,
            transition = ForegroundTransition(pending.fromPackage, "桌面"),
            result = "DESKTOP_VISIBLE",
            evidence = evidence
        )
    }

    /** A selected non-desktop foreground settles the explicit home request as failed. */
    fun failPendingReturnDesktopExitForForeground(packageName: String, evidence: String) {
        failPendingReturnDesktopExit(
            packageName = packageName,
            evidence = "DESKTOP_NOT_CONFIRMED|$evidence"
        )
    }

    private fun rememberLifecycleLock(
        requestId: String?,
        decisionRef: String?,
        packageName: String,
        reason: String
    ) {
        val lockRequestId = requestId?.takeIf { it.isNotBlank() } ?: nextLifecycleId("LOCK")
        activeLifecycleLock = LifecycleLockReference(lockRequestId, packageName)
        // Foreground-traced locks were already emitted by ForegroundNormalEventBridge.
        if (requestId.isNullOrBlank()) {
            lifecycleNormalEventBridge.recordLockResult(
                requestId = lockRequestId,
                decisionRef = decisionRef ?: "NONE",
                packageName = packageName,
                result = "LOCKED",
                reason = reason,
                duplicate = false,
                countedForPenalty = false
            )
        }
    }

    private fun recordPendingReturnDesktopExit(fromPackage: String) {
        val lock = activeLifecycleLock ?: return
        pendingReturnDesktopExit = PendingReturnDesktopExit(
            exitRequestId = nextLifecycleId("EXIT"),
            lockRequestId = lock.requestId,
            fromPackage = fromPackage
        )
    }

    private fun failPendingReturnDesktopExit(packageName: String?, evidence: String) {
        val pending = pendingReturnDesktopExit ?: return
        pendingReturnDesktopExit = null
        lifecycleNormalEventBridge.recordReturnDesktopExit(
            exitRequestId = pending.exitRequestId,
            lockRequestId = pending.lockRequestId,
            transition = ForegroundTransition(pending.fromPackage, "桌面"),
            result = "FAILED",
            reason = "DESKTOP_NOT_CONFIRMED",
            evidence = evidence,
            packageName = packageName
        )
    }

    private fun nextLifecycleId(prefix: String): String =
        "$prefix-${System.currentTimeMillis()}-${lifecycleIdSequence.incrementAndGet()}"

    internal fun recordRestLifecycleStarted(session: CreditManager.LifecycleLogSession, requestedMins: Double, costMins: Double) {
        lifecycleNormalEventBridge.recordRestStarted(
            requestId = session.requestId,
            sessionId = session.sessionId,
            requestedMinutes = "${formatLifecycleMinutes(requestedMins)}min",
            grantedMinutes = "${formatLifecycleMinutes(requestedMins)}min",
            borrowed = false,
            quotaCost = "${formatLifecycleMinutes(costMins)}min",
            opens = "APP_LOCK"
        )
    }

    internal fun recordRestLifecycleEnded(session: CreditManager.LifecycleLogSession, actualDurationMs: Long, reason: String, cooldownEndMs: Long) {
        val state = LifecycleTerminalState.resolve(System.currentTimeMillis(), cooldownEndMs, "ENDED")
        lifecycleNormalEventBridge.recordRestEnded(
            sessionId = session.sessionId,
            actualDuration = "${formatLifecycleMinutes(actualDurationMs / 60_000.0)}min",
            reason = reason,
            nextState = state.nextState,
            cooldownEndsAt = state.cooldownEndsAtMs?.let(::formatLifecycleTime)
        )
    }

    internal fun recordRestCooldownAvailable(session: CreditManager.LifecycleLogSession) =
        lifecycleNormalEventBridge.recordRestCooldownEnded(session.sessionId)

    private fun recordRestRejectedLifecycle(reason: String) =
        lifecycleNormalEventBridge.recordRestRejected(nextLifecycleId("REST"), reason)

    private fun recordPrivilegeRejectedLifecycle(reason: String) =
        lifecycleNormalEventBridge.recordPrivilegeRejected(nextLifecycleId("PRIV"), reason)

    internal fun recordPrivilegeRejectedAtCreditBoundary(reason: String) =
        lifecycleNormalEventBridge.recordPrivilegeRejected(nextLifecycleId("PRIV"), reason)

    internal fun recordPrivilegeLifecycleStarted(session: CreditManager.LifecycleLogSession, grantedMinutes: Int) {
        lifecycleNormalEventBridge.recordPrivilegeStarted(session.requestId, session.sessionId)
    }

    internal fun recordPrivilegeLifecycleEnded(session: CreditManager.LifecycleLogSession, reason: String) =
        lifecycleNormalEventBridge.recordPrivilegeEnded(session.requestId, session.sessionId, reason)

    internal fun recordPrivilegeLifecycleQuotaChanged(
        session: CreditManager.LifecycleLogSession,
        beforeMinutes: Int,
        afterMinutes: Int,
        reason: String
    ) = lifecycleNormalEventBridge.recordPrivilegeQuotaChanged(
        session.requestId,
        "${beforeMinutes}min",
        "${afterMinutes}min",
        reason
    )

    private fun formatLifecycleMinutes(value: Double): String =
        if (value == value.toLong().toDouble()) value.toLong().toString() else "%.1f".format(java.util.Locale.US, value)

    private fun formatLifecycleTime(value: Long): String =
        java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US).format(java.util.Date(value))

    private fun beginTemporaryAccessLifecycle(seconds: Int, emergency: Boolean) {
        temporaryAccessLogRequestId = nextLifecycleId(if (emergency) "EMERGENCY" else "TEMP")
        temporaryAccessLogSessionId = nextLifecycleId(if (emergency) "EMERGENCY-SESSION" else "TEMP-SESSION")
        temporaryAccessLogEmergency = emergency
        val requestId = requireNotNull(temporaryAccessLogRequestId)
        val sessionId = requireNotNull(temporaryAccessLogSessionId)
        // Boundary 1 — anchor the expiry controller to the freshly minted session id so
        // a DEFERRED_ACCEPTED tick in this lifecycle produces a stable operationId.
        beginExpiryOperationForSession(sessionId)
        if (emergency) {
            lifecycleNormalEventBridge.recordEmergencyAccessStarted(requestId, sessionId, "${seconds}s")
        } else {
            lifecycleNormalEventBridge.recordTemporaryAccessStarted(
                requestId = requestId,
                sessionId = sessionId,
                requested = "${seconds}s",
                granted = "${seconds}s",
                opens = "APP_LOCK+TIME_WINDOW_LOCK",
                borrowed = temporaryAccessChargedMinutes > 0.0,
                quotaCost = "${formatLifecycleMinutes(temporaryAccessChargedMinutes)}min"
            )
        }
    }

    private fun endTemporaryAccessLifecycle(nowMs: Long, reason: String = "TIME_EXPIRED", nextState: String? = null) {
        val sessionId = temporaryAccessLogSessionId ?: return
        val actualDuration = (nowMs - temporaryAccessStartAt).coerceAtLeast(0L)
        if (temporaryAccessLogEmergency) {
            lifecycleNormalEventBridge.recordEmergencyAccessEnded(sessionId, "${actualDuration / 1000L}s", reason)
        } else {
            val state = LifecycleTerminalState.resolve(nowMs, temporaryReuseCooldownUntil, nextState ?: "ENDED")
            lifecycleNormalEventBridge.recordTemporaryAccessEnded(
                sessionId = sessionId,
                actualDuration = "${formatLifecycleMinutes(actualDuration / 60_000.0)}min",
                reason = reason,
                nextState = state.nextState,
                cooldownEndsAt = state.cooldownEndsAtMs?.let(::formatLifecycleTime)
            )
        }
    }

    private fun rejectTemporaryAccessLifecycle(reason: String, cooldownEndMs: Long? = null) {
        lifecycleNormalEventBridge.recordTemporaryAccessRejected(
            requestId = nextLifecycleId("TEMP"),
            reason = reason,
            cooldownEndsAt = cooldownEndMs?.takeIf { it > 0L }?.let(::formatLifecycleTime)
        )
    }

    internal fun getHomeClickPendingSetAt(): Long = homeClickPendingSetAt

    internal fun getHomeClickPendingAgeMs(nowMs: Long = System.currentTimeMillis()): Long {
        if (homeClickPendingSetAt <= 0L) return -1L
        return (nowMs - homeClickPendingSetAt).coerceAtLeast(0L)
    }
    
    // 🚀 [NEW] Timestamp until which lock should be suppressed (for intentional user actions)
    @Volatile
    var mDismissUntil = 0L
    private const val HOME_GRACE_PERIOD_MS = 0L // 用户要求：回到桌面无额外关闭宽限
    private const val HOME_PENDING_SUPPRESS_TIMEOUT_MS = 5000L
    private const val SETTINGS_PASS_THROUGH_TIMEOUT_MS = 300000L // 5 min safety restore
    @Volatile
    private var isSettingsPassThrough = false
    private var settingsRestoreRunnable: Runnable? = null
    
    // 🚀 [Blacklist Monitor] Timer to check if blacklist app is still in foreground
    private var blacklistMonitorTimer: Handler? = null
    private var blacklistMonitorRunnable: Runnable? = null
    private var monitoredBlacklistPackage: String? = null
    
    private val REGULAR_DEBOUNCE_MS = 1000L  // 1s for regular locks (non-blacklist)
    private val BLACKLIST_DEBOUNCE_MS = 300L  // 300ms for blacklist apps (fast response)
    private val ANTI_UNINSTALL_DEBOUNCE_MS = 200L  // 200ms for anti-uninstall (quick response)
    private const val TEMP_ACCESS_MIN_SECONDS = 1
    private const val TEMP_ACCESS_DEFAULT_SECONDS = 120
    private const val TEMP_ACCESS_HARD_MAX_SECONDS = 180
    private const val TEMP_ACCESS_COOLDOWN_FREE_SECONDS = 10
    private const val TEMP_ACCESS_COOLDOWN_SINGLE_RATE_UPPER_SECONDS = 60
    private const val TEMP_ACCESS_EMERGENCY_MIN_COOLDOWN_REQUIRED_SECONDS = 60
    private const val TEMP_ACCESS_EMERGENCY_REUSE_GAP_SECONDS = 60
    private const val TEMP_ACCESS_EXPIRE_RELOCK_RETRY_INTERVAL_MS = 250L
    private const val TEMP_ACCESS_EXPIRE_RELOCK_MAX_RETRIES = 12
    private const val LOCK_ATTACH_PENDING_TIMEOUT_MS = 450L
    private const val LOCK_REASSERT_DEBOUNCE_MS = 600L
    @Volatile
    private var isLockAttachPending = false
    @Volatile
    private var lockAttachScheduledAt = 0L
    @Volatile
    private var lastLockAttachSuccessAt = 0L
    @Volatile
    private var lastLockReassertAt = 0L

    fun showLockScreen(
        reason: String = "Unknown",
        isAntiUninstall: Boolean = false,
        isBlacklist: Boolean = false,
        isAppWindowBlocked: Boolean = false,
        forceBypassGrace: Boolean = false,
        blockedAppLabel: String? = null,
        blockedPackageName: String? = null,
        isScreenOnRedetection: Boolean = false,
        detectionSource: String = "UNKNOWN",
        detectionSnapshot: String = "",
        foregroundReportId: String? = null,
        foregroundRequestId: String? = null,
        foregroundRequestAtMs: Long = 0L,
        requestGeneration: Int = CoreService.getAccessibilityGeneration()
    ) {
        cancelTemporaryAccessExpiryRelockRetry()
        cancelEmergencyRelock()
        // 🚀 [Fix] Clear post-temp-access flag so the MonitorService Foreground Guard
        // returns to normal checks after the lock screen is shown.
        if (temporaryAccessFinalRelockNeeded) {
            temporaryAccessFinalRelockNeeded = false
            temporaryAccessFinalRelockTargetPkg = null
            LogExporter.d("TemporaryAccess", "[FINAL_RELOCK_SUCCEEDED] cleared flag")
        }
        Log.w(TAG, "[DEBUG] showLockScreen CALLED: reason=$reason, app=$blockedAppLabel, pkg=$blockedPackageName, forceBypass=$forceBypassGrace")
        
        mBlockedAppLabel = blockedAppLabel
        if (blockedPackageName != null) {
            lastBlockedPackageName = blockedPackageName
        }
        
        val context = mContext ?: run {
            Log.w(TAG, "[DEBUG] showLockScreen BLOCKED: mContext is null")
            settleForegroundLockResult(
                foregroundReportId, foregroundRequestId, blockedPackageName,
                result = "NOT_DISPATCHED", reason = "CONTEXT_UNAVAILABLE"
            )
            return
        }
        val initialIsAppWindowBlocked = blockedPackageName?.let { pkg ->
            AppWindowManager.isManaged(context, pkg) && !AppWindowManager.isAppAllowed(context, pkg)
        } ?: false
        val effectiveIsAppWindowBlocked = isAppWindowBlocked || initialIsAppWindowBlocked
        Log.d(TAG, "LockReason: blacklist=$isBlacklist appWindow=$effectiveIsAppWindowBlocked pkg=$blockedPackageName")
        currentLockRestrictionMode = resolveLockOverlayRestrictionMode(
            isAppWindowBlocked = effectiveIsAppWindowBlocked,
            isWithinDndSegment = TimeRuleManager.getCurrentSegment(context) != null
        )

        if (isLocked) {
            val isAttached = mLockView?.parent != null
            if (!isAttached) {
                val attachCheckNow = System.currentTimeMillis()
                val pendingAge = attachCheckNow - lockAttachScheduledAt
                if (shouldSkipLockAttachPending(isLockAttachPending, pendingAge, LOCK_ATTACH_PENDING_TIMEOUT_MS)) {
                    Log.w(TAG, "[DEBUG] showLockScreen SKIP: lock attach pending (${pendingAge}ms)")
                    settleForegroundLockResult(
                        foregroundReportId, foregroundRequestId, blockedPackageName,
                        result = "NOT_DISPATCHED", reason = "ATTACH_PENDING"
                    )
                    return
                }
                Log.w(TAG, "[DEBUG] showLockScreen RESET: stale lock state (view not attached)")
                LogExporter.logEvent("LockScreen", "[RESET] Stale lock state (view detached), resetting")
                resetLockState("StaleLockView")
            } else {
                // [WP-1] Classify the attached overlay against the incoming request's
                // accessibility generation. A lock stamped by a dead generation is
                // stale and must be rebuilt instead of satisfying this request as a
                // duplicate. An anti-uninstall lock keeps its identity across
                // generations and is never downgraded by an incoming blacklist request.
                val generationDecision = classifyLockGenerationRequest(
                    isLocked = true,
                    isAttached = true,
                    attachedGeneration = mAttachedLockGeneration,
                    currentGeneration = requestGeneration,
                    existingIsAntiUninstall = isAntiUninstallLock,
                    incomingIsAntiUninstall = isAntiUninstall
                )
                if (generationDecision == "STALE_REBUILD") {
                    Log.w(TAG, "[DEBUG] showLockScreen REBUILD: stale generation (attached=$mAttachedLockGeneration current=$requestGeneration)")
                    LogExporter.logEvent("LockScreen", "[GENERATION_REBUILD] attached=$mAttachedLockGeneration current=$requestGeneration reason=$reason")
                    resetLockState("StaleGeneration")
                    // Fall through below the isLocked block to create a fresh overlay.
                } else if (isAlarmActiveForLockSuppression()) {
                    Log.i(TAG, "showLockScreen: lockView already attached but alarm active, keeping suppressed")
                    isLockScreenSuppressedByAlarm = true
                    suppressLockScreenForAlarm()
                    logFgLockResult("显示成功", foregroundReportId, foregroundRequestId, blockedPackageName, reused = true, failureClass = null)
                    if (blockedPackageName != null) {
                        try {
                            onLockScreenAttachedListener?.invoke(blockedPackageName)
                        } catch (_: Throwable) {}
                    }
                    return
                } else if (shouldReassertVisibleLock(
                        isLocked = true,
                        isAttached = true,
                        existingBlacklistLock = isBlacklistLock,
                        incomingBlacklistLock = isBlacklist,
                        systemDialogVisible = isSystemDialogVisible
                    )
                ) {
                    reassertAttachedLockVisibility("repeat_blacklist_hit")
                    // [Diagnostics-only] emit lock result for reassert path too,
                    // so every FG-traced request gets exactly one [锁屏结果] row.
                    logFgLockResult("显示成功", foregroundReportId, foregroundRequestId, blockedPackageName, reused = true, failureClass = null)
                    if (blockedPackageName != null) {
                        try {
                            onLockScreenAttachedListener?.invoke(blockedPackageName)
                        } catch (_: Throwable) {}
                    }
                    return
                } else {
                    // 不记录去重日志，避免洪水
                    logFgLockResult("显示成功", foregroundReportId, foregroundRequestId, blockedPackageName, reused = true, failureClass = null)
                    if (blockedPackageName != null) {
                        try {
                            onLockScreenAttachedListener?.invoke(blockedPackageName)
                        } catch (_: Throwable) {}
                    }
                    return
                }
            }
        }
        
        val now = System.currentTimeMillis()
        
        // 🚀 [Fix] 强制绕过宽限期 (用于警告后自动锁屏)
        val bypassGrace = isAntiUninstall || forceBypassGrace
        if (now < mDismissUntil && !bypassGrace) {
            Log.w(TAG, "[DEBUG] showLockScreen BLOCKED: mDismissUntil (${(mDismissUntil - now)/1000}s left)")
            settleForegroundLockResult(
                foregroundReportId, foregroundRequestId, blockedPackageName,
                result = "NOT_DISPATCHED", reason = "DISMISS_GRACE_ACTIVE"
            )
            return
        }
        
        // Debounce - use different intervals based on type
        val debounceMs = when {
            isAntiUninstall -> ANTI_UNINSTALL_DEBOUNCE_MS
            isBlacklist -> BLACKLIST_DEBOUNCE_MS
            else -> REGULAR_DEBOUNCE_MS
        }
        if (now - mLastLockTime < debounceMs) {
            Log.w(TAG, "[DEBUG] showLockScreen BLOCKED: debounce (${now - mLastLockTime}ms < ${debounceMs}ms)")
            settleForegroundLockResult(
                foregroundReportId, foregroundRequestId, blockedPackageName,
                result = "NOT_DISPATCHED", reason = "DEBOUNCED"
            )
            return
        }
        mLastLockTime = now

        Log.w(TAG, "[DEBUG] showLockScreen PASSED debounce checks")

        // 🚀 [FIX: 空档期/休息/特权保护] 黑名单锁屏不应该在空档期/休息/特权时创建
        // 如果不检查这个，CoreService/MonitorService 会在空档期反复调用 showLockScreen
        // 导致 blacklistMonitorTimer 的 dismiss(force=true) 被立即覆盖，形成死锁
        if (isBlacklist && !isAntiUninstall) {
            val ctx = mContext ?: context
            val seg = TimeRuleManager.getCurrentSegment(ctx)
            val inGap = seg == null
            val inRest = CreditManager.isFreeTime(ctx)
            val inPrivilege = CreditManager.isWeeklyPrivilegeSessionActive(ctx)
            val isAppWindowBlocked = blockedPackageName?.let { pkg ->
                AppWindowManager.isManaged(ctx, pkg) && !AppWindowManager.isAppAllowed(ctx, pkg)
            } ?: false

            if (inPrivilege) {
                Log.w(TAG, "[DEBUG] showLockScreen BLOCKED: Privilege=true - skip creating blacklist lock")
                settleForegroundLockResult(
                    foregroundReportId, foregroundRequestId, blockedPackageName,
                    result = "NOT_DISPATCHED", reason = "PRIVILEGE_ACTIVE"
                )
                return
            }

            if ((inGap || inRest) && !isAppWindowBlocked) {
                Log.w(TAG, "[DEBUG] showLockScreen BLOCKED: Gap=$inGap Rest=$inRest AppWindowBlocked=$isAppWindowBlocked - skip creating blacklist lock")
                settleForegroundLockResult(
                    foregroundReportId, foregroundRequestId, blockedPackageName,
                    result = "NOT_DISPATCHED", reason = if (inGap) "OUTSIDE_TIME_WINDOW" else "REST_ACTIVE"
                )
                return
            }

            if ((inGap || inRest) && isAppWindowBlocked) {
                Log.w(TAG, "[DEBUG] showLockScreen ALLOWED: Gap/Rest but AppWindowBlocked=true, continue showing lock")
            }
        }

        // 🚀 [Latency Fix] Double Check: Is the user already on a Safe App?
        // If the user quickly switched to Home/Settings before lock screen appeared, we should ABORT.
        if (!isAntiUninstall) {
             val topPkg = getTopPackageName(context)
             
             // 🚀 [Fix] Priority Check: User Blacklist > Vendor Whitelist
             // If we are locking specifically because of 'blockedPackageName', we TRUST CoreService (Accessibility Event).
             // UsageStats can be laggy (reporting Launcher/Previous App), causing the Lock to 'Abort' falsely, creating a Flash/Flicker.
             // So if blockedPackageName is set, we consider it "Still On Blocked App" regardless of UsageStats.
             val isStillOnBlockedApp = blockedPackageName != null
             
             if (!isStillOnBlockedApp) {
                 // Only perform Safe App Abort if we are NOT on a targeted blacklist lock.
                 if (topPkg != null && (CreditManager.isSafeSystemOrVendor(topPkg) || CreditManager.isLauncher(topPkg))) {
                     Log.w(TAG, "[DEBUG] showLockScreen ABORTED: User is on Safe App ($topPkg)")
                     settleForegroundLockResult(
                         foregroundReportId, foregroundRequestId, blockedPackageName,
                         result = "NOT_DISPATCHED", reason = "SAFE_APP_VISIBLE"
                     )
                     return
                 }
             } else {
                 Log.w(TAG, "[DEBUG] showLockScreen PRIORITY: Explicit Block Request for $blockedPackageName. Ignoring Safe Check (UsageStats lag).")
             }
        }

        // 🚀 [锁屏界面优化] 勿扰/时间窗锁不在锁屏界面显示，等用户解锁后再显示
        // 闹钟挑战锁（isAntiUninstall）因为有防关机功能，需要在锁屏界面显示
        if (!isAntiUninstall && isDeviceLocked(context)) {
            Log.i(TAG, "[DeferredLock] Device locked, deferring non-alarm lock: reason=$reason, isScreenOnRedetection=$isScreenOnRedetection")
            LogExporter.logEvent("LockScreen", "[DEFERRED] Device locked, deferring: reason=$reason, isScreenOnRedetection=$isScreenOnRedetection")
            mDeferredLock = DeferredLockParams(
                reason = reason,
                isBlacklist = isBlacklist,
                blockedAppLabel = blockedAppLabel,
                blockedPackageName = blockedPackageName,
                isScreenOnRedetection = isScreenOnRedetection,
                timestamp = System.currentTimeMillis()
            )
            registerUserPresentReceiver(context)
            settleForegroundLockResult(
                foregroundReportId, foregroundRequestId, blockedPackageName,
                result = "DEFERRED", reason = "DEVICE_LOCKED"
            )
            // Boundary 1 — REQ-004: deferred acceptance is the terminal acceptance path;
            // mark the expiry controller FINISHED so further ticks short-circuit instead
            // of stacking EXPIRY_LOCK + DEFERRED repeats. `mDeferredLock` stays — it is
            // consumed by ACTION_USER_PRESENT.
            finishExpiryOperation("deferred_accepted")
            return
        }

        Log.w(TAG, "[DEBUG] showLockScreen EXECUTING NOW")

        mLockEntryReasonRaw = reason

        // 🚀 [Race Condition Fix] 在 Handler.post 之前同步设置锁状态标志
        // 这可以防止以下竞态条件：
        // 1. showLockScreen 被调用，Handler.post 排队等待执行
        // 2. 在 Handler 执行之前，MonitorService 检测到 Launcher
        // 3. 调用 dismissLockScreen()，但此时 isBlacklistLock 还是 false
        // 4. dismiss 不被阻止，锁屏被意外关闭
        // 通过在这里提前设置标志，确保 dismiss 检查能够正确工作
        _isLocked.set(true)
        _isLocked.set(true)
        _isAntiUninstallLock.set(isAntiUninstall)
        _isBlacklistLock.set(isBlacklist)
        _isAppWindowLock.set(effectiveIsAppWindowBlocked)
        isLockAttachPending = true
        lockAttachScheduledAt = now

        Handler(Looper.getMainLooper()).post {
            // 🛡️ [Anti-Zombie] 二次校验：如果锁已在 Handler 排队期间被解除，不再 addView
            if (!isLocked) {
                isLockAttachPending = false
                lockAttachScheduledAt = 0L
                Log.w(TAG, "[DEBUG] showLockScreen ABORTED: isLocked became false before Handler executed")
                settleForegroundLockResult(
                    foregroundReportId, foregroundRequestId, blockedPackageName,
                    result = "NOT_DISPATCHED", reason = "CANCELLED_BEFORE_ATTACH"
                )
                return@post
            }

            try {
                val currentTheme = ThemeManager.currentKey(context)
                if (mLockView == null || mLastThemeKey != currentTheme) {
                    if (mLockView?.parent != null) {
                        try {
                            val wm = mActiveWindowManager ?: mWindowManager
                            wm?.removeView(mLockView)
                        } catch (_: Exception) {}
                    }
                    mLockView = null
                    createLockView(context)
                    mLastThemeKey = currentTheme
                }

                if (mLockView?.parent != null) {
                    isLockAttachPending = false
                    lockAttachScheduledAt = 0L
                    Log.w(TAG, "[DEBUG] showLockScreen BLOCKED: lock view already has parent")
                    settleForegroundLockResult(
                        foregroundReportId, foregroundRequestId, blockedPackageName,
                        result = "NOT_DISPATCHED", reason = "VIEW_ALREADY_ATTACHED"
                    )
                    return@post
                }

                val isA11y = mCoreService != null && LockWindowManager.isServiceActive()
                val targetWM = if (isA11y) {
                    mCoreService!!.getSystemService(Context.WINDOW_SERVICE) as WindowManager
                } else {
                    mWindowManager!!
                }

                val params = buildNormalLockParams(isA11y)

                updateLockViewUI()
                clearSystemDialogCompatState()
                val shouldSuppressForAlarm = isAlarmActiveForLockSuppression()
                if (shouldSuppressForAlarm) {
                    isLockScreenSuppressedByAlarm = true
                    mLockView?.visibility = View.GONE
                    mLockView?.alpha = 0.0f
                    params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    Log.i(TAG, "showLockScreen: suppressed for active alarm challenge/scan")
                } else {
                    isLockScreenSuppressedByAlarm = false
                    mLockView?.visibility = View.VISIBLE
                    mLockView?.alpha = 1.0f
                }

                targetWM.addView(mLockView, params)
                mActiveWindowManager = targetWM
                isLockAttachPending = false
                lockAttachScheduledAt = 0L
                lastLockAttachSuccessAt = System.currentTimeMillis()
                // [WP-1] Commit the accessibility generation that owns this overlay
                // ONLY after addView succeeds. A later request from a different
                // generation will be classified STALE_REBUILD.
                mAttachedLockGeneration = requestGeneration

                // 🛡️ [Finding 1 修复] 锁屏真正成功 attach（addView 成功）后通知监听器消费业务边沿
                if (blockedPackageName != null) {
                    try {
                        onLockScreenAttachedListener?.invoke(blockedPackageName)
                    } catch (_: Throwable) {}
                }

                // Repeated-return-home penalty is user-action driven.
                // Overlay attach/re-attach is a system action and never increments it.

                // 如果有待显示的惩罚弹窗，在锁屏 in-layout overlay 上展示（不会被锁屏遮挡）
                if (mPendingPenaltyMessage != null) {
                    penaltyMessage?.text = mPendingPenaltyMessage
                    penaltyOverlay?.visibility = View.VISIBLE
                    mPendingPenaltyMessage = null
                }

                updateStatus(context)
                startLockRefreshTimer(context)

                if (isBlacklist) {
                    startBlacklistMonitor(context, blockedPackageName)
                }

                updateWindowFocus(true)

                Log.i(TAG, "Locked Screen for reason: $reason")
                val requestToDisplayMs = if (foregroundRequestAtMs > 0L) {
                    System.currentTimeMillis() - foregroundRequestAtMs
                } else 0L
                rememberLifecycleLock(
                    requestId = foregroundRequestId,
                    decisionRef = foregroundReportId,
                    packageName = blockedPackageName ?: "unknown",
                    reason = reason
                )
                logFgLockResult("显示成功", foregroundReportId, foregroundRequestId, blockedPackageName, reused = false, failureClass = null, requestToDisplayMs = requestToDisplayMs)
            } catch (e: Exception) {
                isLockAttachPending = false
                lockAttachScheduledAt = 0L
                Log.e(TAG, "Failed to add window", e)
                logFgLockResult("显示失败", foregroundReportId, foregroundRequestId, blockedPackageName, reused = false, failureClass = e.javaClass.simpleName)
                resetLockState("AddViewFailed")
            }
        }
    }

    /**
     * Repeated-return-home penalty authority.
     *
     * Only the explicit RETURN_HOME button calls this method. Foreground detection,
     * overlay attach/re-attach, Accessibility replay and OEM launcher noise have no
     * authority to increment the counter.
     */
    private fun recordReturnHomePenaltyHit(
        context: Context,
        now: Long,
        reason: String
    ): Boolean {
        val isDndActive = isBlacklistLock && TimeRuleManager.getCurrentSegment(context) != null
                && now >= CreditManager.getFreeTimeEnd(context)
        if (!isDndActive) {
            if (DefaultLogDebugConfig.quotaCalculation) {
                Log.d(TAG, "[Penalty] return-home click not eligible | blacklist=$isBlacklistLock, hasSegment=${TimeRuleManager.getCurrentSegment(context) != null}, freeTimeEnded=${now >= CreditManager.getFreeTimeEnd(context)}")
            }
            return false
        }

        if (now - mPenaltyWindowStart > PENALTY_WINDOW_MS) {
            mPenaltyWindowStart = now
            mPenaltyHitCount = 1
            if (DefaultLogDebugConfig.quotaCalculation) {
                Log.d(TAG, "[Penalty] return-home window reset, count=1/$PENALTY_THRESHOLD | reason=$reason")
            }
        } else {
            mPenaltyHitCount++
            if (DefaultLogDebugConfig.quotaCalculation) {
                Log.d(TAG, "[Penalty] return-home increment, count=$mPenaltyHitCount/$PENALTY_THRESHOLD | reason=$reason")
            }
        }

        // Threshold → exactly one transaction per cycle.
        if (mPenaltyHitCount >= PENALTY_THRESHOLD) {
            issuePenaltyTransactionOnce(context, now, reason)
        }
        return true
    }

    /**
     * Issue exactly one penalty transaction per cycle. Extracted from
     * [recordReturnHomePenaltyHit] so the one-transaction-per-threshold
     * contract is visible at a glance.
     */
    private fun issuePenaltyTransactionOnce(context: Context, now: Long, reason: String) {
        // 🚀 额度彻底用完且不可借 → 免惩罚（和空档期一致）
        val remainingBefore = CreditManager.getRemainingMinutes(context)
        val maxBorrowable = CreditManager.getMaxBorrowableMinutes(context)
        if (remainingBefore <= 0.0 && maxBorrowable <= 0.0) {
            mPenaltyWindowStart = 0L
            mPenaltyHitCount = 0
            Log.d(TAG, "[Penalty] quota exhausted (no borrow headroom), skipping penalty | reason=$reason")
            return
        }
        val inCooling = CreditManager.isCooling(context)
        val inTempCooling = getTemporaryReuseCooldownRemainingSeconds() > 0L
        val isAnyCooling = inCooling || inTempCooling
        val transactionId = "PENALTY-${now}-${mPenaltyHitCount}"
        val chargeResult = CreditManager.consumePenaltySeconds(
            context = context,
            penaltySec = PENALTY_DEDUCT_SECS,
            transactionId = transactionId,
            isCooling = isAnyCooling
        )
        if (inCooling) {
            val coolEnd = CreditManager.getCoolDownEnd(context)
            val newEnd = coolEnd + PENALTY_EXTEND_COOL_MS
            CreditManager.setCoolDownEnd(context, newEnd)
        }
        if (inTempCooling) {
            temporaryReuseCooldownUntil += PENALTY_EXTEND_COOL_MS
            saveTemporaryAccessState()
        }

        // 惩罚事件日志（合并扣费+冷却延长）
        val coolExtendSec = PENALTY_EXTEND_COOL_MS / 1000
        // Boundary 4 (T4) — payload-equivalence GREEN 后的退场：
        // 删除 LogExporter 中文 Penalty 调用；retainedEvidence 由 PENALTY payload
        // （11 字段，per REQ-010）取代 — 见 docs/ai/LOG_EVENT_MIGRATION.md row 71
        // `══════ 每日状态播报 ══════` 行的 retainedEvidence 字段。

        mPendingPenaltyMessage = buildString {
            append("⚠️ 30秒内返回桌面${PENALTY_THRESHOLD}次，触发惩罚\n\n")
            append("• 扣除时长: ${chargeResult.timeDeductSec}s\n")
            if (chargeResult.borrowedTimeSec > 0) {
                if (chargeResult.normalTimeSec > 0) {
                    append("• 当前额度: ${chargeResult.normalTimeSec}s(×1)\n")
                }
                append("• 借用额度: ${chargeResult.borrowedTimeSec}s(×2) → 扣${chargeResult.borrowedTimeCostSec}s\n")
                append("• 合计消耗额度: ${chargeResult.totalTimeQuotaSec}s\n")
            } else {
                append("• 当前额度: ${chargeResult.normalTimeSec}s\n")
            }
            if (chargeResult.borrowedCountPrincipalHalfUnits > 0) {
                append("• 扣除次数: 当前扣除 ${formatUsableCountHalfUnits(chargeResult.currentCountCostHalfUnits)}次\n")
                append("• 借用下一段次数: ${formatUsableCountHalfUnits(chargeResult.nextCountCostHalfUnits)}次(×2)\n")
            } else {
                append("• 扣除次数: ${formatUsableCountHalfUnits(chargeResult.currentCountCostHalfUnits)}次")
                if (isAnyCooling) append(" (冷却中翻倍)")
                append("\n")
            }
            if (inCooling) {
                append("\n• 休息冷却期延长: +${coolExtendSec}s\n")
            }
            if (inTempCooling) {
                append("• 临时解锁冷却期延长: +${coolExtendSec}s\n")
            }
        }

        mPenaltyHitCount = 0
        mPenaltyWindowStart = now
        Log.d(TAG, "[Penalty] transaction issued (tx=$transactionId) | reason=$reason")
    }

    /**
     * [Diagnostics-only] Log FG lock-screen display result.
     * No-op when foregroundReportId is null (non-FG lock paths).
     * Cannot alter lock state, retry, exception handling or return paths.
     */
    private fun logFgLockResult(
        resultLabel: String,
        foregroundReportId: String?,
        foregroundRequestId: String?,
        blockedPackageName: String?,
        reused: Boolean,
        failureClass: String?,
        requestToDisplayMs: Long = 0L
    ) {
        if (foregroundReportId == null) return
        val pkg = blockedPackageName ?: "unknown"
        val success = resultLabel == "显示成功"
        settleForegroundLockResult(
            foregroundReportId = foregroundReportId,
            foregroundRequestId = foregroundRequestId,
            blockedPackageName = blockedPackageName,
            result = if (success) "LOCKED" else "FAILED",
            reason = if (success) {
                if (reused) "LOCK_ALREADY_ATTACHED" else "WINDOW_ATTACHED"
            } else {
                "ADD_VIEW_FAILED"
            },
            duplicate = reused,
            failureClass = if (success) null else failureClass
        )
    }

    /** Settles the normal result once without changing the lock request's control flow. */
    private fun settleForegroundLockResult(
        foregroundReportId: String?,
        foregroundRequestId: String?,
        blockedPackageName: String?,
        result: String,
        reason: String,
        duplicate: Boolean = false,
        failureClass: String? = null
    ) {
        foregroundLockNormalEventBridge.settleLock(
            requestId = foregroundRequestId,
            decisionRef = foregroundReportId,
            packageName = blockedPackageName ?: "unknown",
            result = result,
            reason = reason,
            duplicate = duplicate,
            failureClass = failureClass
        )
    }

    private fun resetLockState(reason: String) {
        Log.w(TAG, "[DEBUG] resetLockState: $reason")
        try {
            if (mLockView != null && mActiveWindowManager != null && mLockView?.parent != null) {
                // [Fix: a11y overlay crash] removeView 前清空焦点
                mLockView?.clearFocus()
                mActiveWindowManager?.removeView(mLockView)
            } else if (mLockView != null && mWindowManager != null && mLockView?.parent != null) {
                // [Fix: a11y overlay crash] removeView 前清空焦点
                mLockView?.clearFocus()
                mWindowManager?.removeView(mLockView)
            }
        } catch (e: Exception) {
            Log.e(TAG, "resetLockState failed", e)
        } finally {
            mActiveWindowManager = null
            isLockAttachPending = false
            lockAttachScheduledAt = 0L
            lastLockAttachSuccessAt = 0L
            mAttachedLockGeneration = -1
            _isLocked.set(false)
            _isAntiUninstallLock.set(false)
            _isBlacklistLock.set(false)
            _isAppWindowLock.set(false)
            mBlockedAppLabel = null
            mLockEntryReasonRaw = null
            mDeferredLock = null  // 清除延迟锁屏
            privilegeDesktopStreakTargetPkg = null  // 清除特权到期桌面穿透目标
            privilegeLastNonLauncherPkg = null
            mContext?.let { unregisterUserPresentReceiver(it) }
            stopLockRefreshTimer()
            stopBlacklistMonitor()
            clearSystemDialogCompatState()
        }
    }

    /**
     * 息屏时隐藏非闹钟锁屏覆盖层。
     * 闹钟挑战锁因为有防关机功能，需要在锁屏界面保持显示。
     * 用户解锁后，MonitorService 会重新检测并触发锁屏。
     */
    fun dismissNonAlarmLockForScreenOff() {
        // [WP-1 / A5] 息屏 dismiss 守卫走纯 seam：闹钟锁与防卸载锁（非黑名单锁）保持，
        // 仅普通黑名单锁被 dismiss。
        if (!shouldDismissForScreenOff(
                isAlarmOverlayActive = isAlarmOverlayShowing,
                isLocked = isLocked,
                isBlacklistLock = isBlacklistLock
            )
        ) {
            return
        }

        Log.i(TAG, "[ScreenOff] Dismissing non-alarm lock screen for screen off")
        LogExporter.logEvent("LockScreen", "[SCREEN_OFF_DISMISS] Dismissing non-alarm lock for screen off")
        LogExporter.logEvent("LockScreen", "[SCREEN_OFF_PRESERVE] visible lock kept; explicit exit required")
    }

    fun dismissLockScreen(authority: LockDismissAuthority) {
        // 🛡️ [Anti-Zombie] 防御性移除：不论 isLocked 为何值，先尝试清理可能残留的视图
        // 防止因 Handler.post 竞态产生的"幽灵锁屏"无法被清除
        try {
            if (mLockView != null && mLockView?.parent != null) {
                val wm = mActiveWindowManager ?: mWindowManager
                if (wm != null && mLockView?.parent != null) {
                    wm.removeView(mLockView)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "dismissLockScreen: defensive removeView ignored", e)
        }

        if (!isLocked) {
            // 防御性移除后同步重置标志位，确保状态一致
            _isLocked.set(false)
            _isAntiUninstallLock.set(false)
            _isBlacklistLock.set(false)
            _isAppWindowLock.set(false)
            mActiveWindowManager = null
            isLockAttachPending = false
            lockAttachScheduledAt = 0L
            stopLockRefreshTimer()
            stopBlacklistMonitor()
            clearSystemDialogCompatState()
            return
        }
        val context = mContext ?: return
        Log.i(TAG, "[LOCK_EXIT] authority=$authority pkg=$lastBlockedPackageName")

        val now = System.currentTimeMillis()

        val minGrace = now + 1000L
        if (mDismissUntil < minGrace) {
            mDismissUntil = minGrace
        }

        val dismissAction = Runnable {
            try {
                if (mLockView != null && mActiveWindowManager != null && mLockView?.parent != null) {
                    mActiveWindowManager?.removeView(mLockView)
                } else if (mLockView != null && mWindowManager != null && mLockView?.parent != null) {
                    mWindowManager?.removeView(mLockView)
                }

                mActiveWindowManager = null
                isLockAttachPending = false
                lockAttachScheduledAt = 0L
                lastLockAttachSuccessAt = 0L

                val wasAntiUninstall = _isAntiUninstallLock.get()
                val wasBlacklist = _isBlacklistLock.get()
                val dismissedPkg = lastBlockedPackageName
                // 🔴 [Fix] 拆分解锁原因：区分 REST / CHALLENGE / MANUAL
                val unlockMethod = when {
                    wasAntiUninstall -> "防卸载关闭"
                    wasBlacklist && CreditManager.isFreeTime(context) -> "REST"
                    wasBlacklist -> "CHALLENGE"
                    else -> "MANUAL"
                }
                // 解锁事件日志 (legacy lifecycle line retired; lifecycle normal event already emitted upstream)
                _isLocked.set(false)
                _isAntiUninstallLock.set(false)
                _isBlacklistLock.set(false)
                _isAppWindowLock.set(false)
                mBlockedAppLabel = null
                mLockEntryReasonRaw = null

                stopLockRefreshTimer()
                stopBlacklistMonitor()
                clearSystemDialogCompatState()
            } catch (e: Exception) {
                Log.e(TAG, "dismissLock internal error", e)
            }
        }

        if (Looper.myLooper() == Looper.getMainLooper()) {
            dismissAction.run()
        } else {
            Handler(Looper.getMainLooper()).post(dismissAction)
        }
    }

    // 🚀 [Performance] Track last full (heavy) update time for throttling
    private var lastFullUpdateTime = 0L
    private const val FULL_UPDATE_INTERVAL_MS = 2000L // Heavy update every 2s
    private const val REFRESH_TICK_MS = 500L // Lightweight tick every 500ms

    private fun startLockRefreshTimer(context: Context) {
        stopLockRefreshTimer()
        lastFullUpdateTime = System.currentTimeMillis()
        val timerH = ensureTimerThread()
        var tickCount = 0L
        mRefreshRunnable = object : Runnable {
            override fun run() {
                if (!isLocked) {
                    return
                }
                try {
                    val now = System.currentTimeMillis()
                    tickCount++
                    if (dismissObsoleteBlacklistLockIfNaturallyFree(context)) {
                        return
                    }
                    if (now - lastFullUpdateTime >= FULL_UPDATE_INTERVAL_MS) {
                        lastFullUpdateTime = now
                        updateStatus(context)
                        // 🛑 [Noise Reduction] [FULL] 是 2s 周期 tick 心跳,不属于状态变化;降到 Log.d(只走 logcat)
                        Log.d(TAG, "[FULL] tick=#$tickCount, thread=${Thread.currentThread().name}")
                    } else {
                        mUiHandler.post { tickLightweightUI() }
                    }
                } catch (t: Throwable) {
                    Log.e(TAG, "RefreshTimer tick failed, keep loop alive", t)
                    LogExporter.logEvent("Timer", "[ERROR] RefreshTimer tick failed: ${t.javaClass.simpleName}")
                } finally {
                    if (isLocked) {
                        val activeHandler = mTimerHandler ?: ensureTimerThread()
                        activeHandler.postDelayed(this, REFRESH_TICK_MS)
                    }
                }
            }
        }
        mRefreshRunnable?.let { timerH.postDelayed(it, REFRESH_TICK_MS) }
    }

    private fun dismissObsoleteBlacklistLockIfNaturallyFree(context: Context): Boolean {
        if (!isBlacklistLock) return false
        val targetPackage = lastBlockedPackageName ?: return false
        val currentSeg = TimeRuleManager.getCurrentSegment(context)
        // 【独立黑名单覆盖】勿扰时段名单命中语义唯一来源是 SegmentBlacklist：
        // 时段独立名单（listApps != null）必须覆盖全局黑名单，否则"时段名单有、
        // 全局名单无"的应用会在锁上后被 AUTO_RELEASE 立即解除（用户可见的锁屏闪退 bug）。
        val isDndBlocked = currentSeg != null &&
            SegmentBlacklist.isSubjectToBlock(context, currentSeg, targetPackage)
        val isAppWindowBlocked = AppWindowManager.isManaged(context, targetPackage) &&
            !AppWindowManager.isAppAllowed(context, targetPackage)
        val decision = resolveAutomaticRestrictionRelease(
            isDndBlocked = isDndBlocked,
            isAppWindowBlocked = isAppWindowBlocked,
            hasActiveRest = false,
            hasActiveTemporaryAccess = false,
            hasBlacklistOverlay = true
        )
        if (!decision.dismissBlacklistOverlay) return false

        LogExporter.i("LockScreen", "[AUTO_RELEASE] restrictions cleared naturally for pkg=$targetPackage, dismissing lock screen")
        mUiHandler.post {
            dismissLockScreen(LockDismissAuthority.NATURAL_RULE_RELEASE)
        }
        return true
    }

    private fun stopLockRefreshTimer() {
        mRefreshRunnable?.let {
            mTimerHandler?.removeCallbacks(it)
            mUiHandler.removeCallbacks(it)
        }
        mRefreshRunnable = null
    }

    private fun createLockView(context: Context) {
        // 🚀 [Fix] Wrap Context with Theme to support Material Components (TextInputLayout)
        val themedContext = android.view.ContextThemeWrapper(context, ThemeManager.themeRes(context))
        val inflater = LayoutInflater.from(themedContext)
        mLockView = inflater.inflate(R.layout.window_lock_screen, null)
        
        // 🚀 [Lock Screen] Trap Keys (Back/Menu)
        mLockView?.isFocusable = true
        mLockView?.isFocusableInTouchMode = true
        mLockView?.requestFocus()
        mLockView?.setOnKeyListener { _, keyCode, event ->
            // Consume BACK key to prevent closing
            if (keyCode == android.view.KeyEvent.KEYCODE_BACK) {
                return@setOnKeyListener true
            }
            // Consume MENU key just in case
            if (keyCode == android.view.KeyEvent.KEYCODE_MENU) {
                return@setOnKeyListener true
            }
            // Allow volume keys without demote
            if (keyCode == android.view.KeyEvent.KEYCODE_VOLUME_UP ||
                keyCode == android.view.KeyEvent.KEYCODE_VOLUME_DOWN ||
                keyCode == android.view.KeyEvent.KEYCODE_VOLUME_MUTE) {
                if (event.action == android.view.KeyEvent.ACTION_DOWN) {
                    LogExporter.logEvent("SystemDialog", "[VOLUME_KEY] key=$keyCode")
                }
                return@setOnKeyListener false
            }
            false
        }
        
        // 🚀 [Fix] Do not consume all touch events; allow child buttons to receive clicks
        // Full-screen overlay still blocks pass-through without swallowing clicks.
        mLockView?.setOnTouchListener { _, _ ->
            false
        }
        
        // Find Views
        btnEmergency = mLockView?.findViewById(R.id.btn_emergency)
        btnWeeklyPrivilege = mLockView?.findViewById(R.id.btn_weekly_privilege)
        btnBackToHome = mLockView?.findViewById(R.id.btn_back_to_home)
        btnTemporaryAccess = mLockView?.findViewById(R.id.btn_temporary_access)
        btnTemporaryAccessQuickTen = mLockView?.findViewById(R.id.btn_temporary_access_quick_10)
        btnTemporaryAccessHelp = mLockView?.findViewById(R.id.btn_temporary_access_help)
        val statusBarPullZone = mLockView?.findViewById<View>(R.id.view_status_bar_pull_zone)
        penaltyOverlay = mLockView?.findViewById(R.id.view_penalty_overlay)
        penaltyMessage = mLockView?.findViewById(R.id.tv_penalty_message)
        penaltyOk = mLockView?.findViewById(R.id.btn_penalty_ok)

        val layoutBgText = mLockView?.findViewById<View>(R.id.layout_background_text)
        val layoutBottomBtns = mLockView?.findViewById<View>(R.id.layout_bottom_buttons)
        if (mLockView != null && layoutBgText != null && layoutBottomBtns != null) {
            installOverlaySafeInsets(
                mLockView!!,
                InsetPaddingTarget(layoutBgText, applyLeft = true, applyTop = true, applyRight = true),
                InsetPaddingTarget(layoutBottomBtns, applyLeft = true, applyRight = true, applyBottom = true)
            )
        }

        tvUsableCountBig = mLockView?.findViewById(R.id.tv_usable_count_big)
        tvSurplusBorrowLine = mLockView?.findViewById(R.id.tv_surplus_borrow_line)
        tvRestCountdownInCard = mLockView?.findViewById(R.id.tv_rest_countdown_in_card)

        penaltyOk?.setOnClickListener {
            penaltyOverlay?.visibility = View.GONE
        }
        
        // 顶部下拉手势：仅临时放开焦点拉出通知/状态栏，不降层不透传
        statusBarPullZone?.setOnTouchListener(object : View.OnTouchListener {
            private var startY = 0f
            private var triggered = false

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        startY = event.rawY
                        triggered = false
                        return true
                    }

                    MotionEvent.ACTION_MOVE -> {
                        if (triggered) return true
                        val thresholdPx = v.resources.displayMetrics.density * STATUS_PULL_THRESHOLD_DP
                        if (event.rawY - startY >= thresholdPx) {
                            triggered = true
                            LogExporter.logEvent("SystemDialog", "[PULL] Status bar pull zone gesture")
                            updateWindowFocus(false)
                            statusBarFocusRestoreRunnable?.let { mUiHandler.removeCallbacks(it) }
                            statusBarFocusRestoreRunnable = Runnable {
                                updateWindowFocus(true)
                            }
                            mUiHandler.postDelayed(statusBarFocusRestoreRunnable!!, 1500L)
                            if (isServiceActive()) {
                                try {
                                    mCoreService?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS)
                                } catch (_: Exception) {
                                }
                            }
                        }
                        return true
                    }

                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        triggered = false
                        return true
                    }
                }
                return false
            }
        })

        // 🚀 [Feature] Back to Home - 由下方 setSafeOnClickListener 统一处理
        // 移除了重复的 setOnClickListener，避免监听器冲突

        // 🚀 [Helper] Safe Click Listener for Debouncing
        fun View.setSafeOnClickListener(onSafeClick: (View) -> Unit) {
            val safeClickListener = object : View.OnClickListener {
                private var lastClickTime = 0L
                override fun onClick(v: View) {
                    val now = System.currentTimeMillis()
                    if (now - lastClickTime > 1000) {
                        lastClickTime = now
                        onSafeClick(v)
                    }
                }
            }
            setOnClickListener(safeClickListener)
        }

        // Setup Listeners with Debounce

        btnBackToHome?.setSafeOnClickListener {
            // 🏠 “回到桌面”按钮
            val now = System.currentTimeMillis()

            // 正常模式：给予 3 秒宽限期处理小窗/浮窗
            val graceTime = HOME_GRACE_PERIOD_MS
            mDismissUntil = System.currentTimeMillis() + graceTime
            homeClickPendingPackage = lastBlockedPackageName
            homeClickPendingSetAt = now
            // 🚀 [回到桌面宽限] 启动 3s 小窗锁宽限，期间不因小窗/浮窗再锁
            setDesktopReturnGrace(now)
            recordPendingReturnDesktopExit(lastBlockedPackageName ?: "unknown")
            clearBlacklistHitDebounceForUserAction()
            recordReturnHomePenaltyHit(context, now, "RETURN_HOME_BUTTON")
            Log.i(
                "PresenceDiag",
                "[HOME_CLICK] action=RETURN_HOME_ONLY target=${lastBlockedPackageName ?: "unknown"}"
            )

            // 🏠 回到桌面 legacy lifecycle line retired; LOCK_EXIT normal event already records the outcome.
            Log.w(TAG, "[HOME_CLICK_RAW] pkg=${homeClickPendingPackage ?: "N/A"}, now=$now, dismissUntil=$mDismissUntil, graceMs=$graceTime, isLocked=$isLocked")

            // Allow actions
            val homeIntent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            dismissLockScreen(LockDismissAuthority.RETURN_HOME)
            try {
                context.startActivity(homeIntent)
            } catch (e: Exception) {
                Log.w(TAG, "[HOME_CLICK] startActivity(Home) failed", e)
            }
        }
        
        // 🌟 纯粹三按钮架构点击逻辑 (Three-Button Architecture)
        // 1. 每周特权常驻按钮：长时间解开任意锁 (120min)
        btnWeeklyPrivilege?.setSafeOnClickListener {
            showWeeklyPrivilegeDialog(context)
        }

        // 2. 场景专属主操作按钮：
        //    - 应用时间窗锁屏：⚡ 临时解锁（短期解开任意锁，无额外勾选）
        //    - 勿扰时段锁机：🌱 申请休息（专解勿扰锁）
        btnEmergency?.setSafeOnClickListener {
            if (shouldRouteEmergencyToTempAccess(currentLockRestrictionMode)) {
                if (isTemporaryAccessQuotaExhausted(context)) {
                    Toast.makeText(context, "今日临时解锁额度已用完，无法借用", Toast.LENGTH_SHORT).show()
                    return@setSafeOnClickListener
                }
                showTempAccessWindow(context)
            } else {
                if (isRestQuotaExhausted(context)) {
                    recordRestRejectedLifecycle("QUOTA_INSUFFICIENT")
                    Toast.makeText(context, "今日休息已用完，无法借用", Toast.LENGTH_SHORT).show()
                    return@setSafeOnClickListener
                }
                showRestWindow(context)
            }
        }

        btnTemporaryAccess?.setSafeOnClickListener {
            if (isTemporaryAccessQuotaExhausted(context)) {
                Toast.makeText(context, "今日临时解锁额度已用完，无法借用", Toast.LENGTH_SHORT).show()
                return@setSafeOnClickListener
            }
            showTemporaryAccessDialog(context)
        }

        // 二级应急按钮：冷却中可用时提供 10s 临时解锁
        btnTemporaryAccessQuickTen?.setSafeOnClickListener {
            val reuseRemain = getTemporaryReuseCooldownRemainingSeconds()
            val emergencyRemainingUses = resolveTemporaryEmergencyRemainingUses(
                reuseRemainSeconds = reuseRemain,
                fixedRemainingUses = temporaryEmergencyRemainingUsesInCooldown
            )
            if (reuseRemain > 0L && emergencyRemainingUses > 0) {
                showEmergencyTenConfirmDialog(context, emergencyRemainingUses, getTemporaryAccessMaxSeconds(context))
            } else {
                Toast.makeText(context, "二级应急暂不可用", Toast.LENGTH_SHORT).show()
            }
        }

        btnTemporaryAccessHelp?.setSafeOnClickListener {
            showTemporaryAccessHelpDialog(context)
        }
    }

    private fun scheduleSettingsRestoreTimeout() {
        settingsRestoreRunnable?.let { mUiHandler.removeCallbacks(it) }
        settingsRestoreRunnable = Runnable {
            LogExporter.logEvent(
                "SettingsPassThrough",
                "[RESTORE_BY_TIMEOUT] locked=$isLocked, mode=$systemDialogCompatMode"
            )
            exitSettingsPassThrough("timeout")
        }
        mUiHandler.postDelayed(settingsRestoreRunnable!!, SETTINGS_PASS_THROUGH_TIMEOUT_MS)
    }

    private fun enterSettingsPassThrough(reason: String) {
        if (!isLocked) return
        if (isSettingsPassThrough) return
        isSettingsPassThrough = true
        LogExporter.logEvent("SettingsPassThrough", "[ENTER] reason=$reason")

        if (isSystemDialogVisible) {
            scheduleSettingsRestoreTimeout()
            return
        }

        mUiHandler.post {
            if (mLockView == null || mActiveWindowManager == null) return@post
            try {
                val currentParams = mLockView!!.layoutParams as WindowManager.LayoutParams
                val originalParams = cloneLayoutParams(currentParams)
                if (systemDialogNormalParams == null) {
                    systemDialogNormalParams = cloneLayoutParams(originalParams)
                    systemDialogNormalViewAlpha = mLockView?.alpha ?: 1.0f
                }

                val primaryOk = tryApplyPrimaryDemote(originalParams)
                if (!primaryOk) {
                    val fallbackParams = cloneLayoutParams(originalParams).apply {
                        flags = flags or
                                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        alpha = DEMOTED_ALPHA
                    }
                    mActiveWindowManager!!.updateViewLayout(mLockView, fallbackParams)
                    mLockView?.alpha = DEMOTED_ALPHA
                    systemDialogCompatMode = SystemDialogCompatMode.FALLBACK_PASS_THROUGH
                }
            } catch (e: Exception) {
                LogExporter.logEvent(
                    "SettingsPassThrough",
                    "[ENTER_FAIL] err=${e.javaClass.simpleName}"
                )
            }
        }
        scheduleSettingsRestoreTimeout()
    }

    fun exitSettingsPassThrough(reason: String) {
        if (!isSettingsPassThrough) return
        isSettingsPassThrough = false
        settingsRestoreRunnable?.let { mUiHandler.removeCallbacks(it) }
        settingsRestoreRunnable = null
        LogExporter.logEvent("SettingsPassThrough", "[EXIT] reason=$reason")
        if (isSystemDialogVisible) return
        restoreLockFocus(byTimeout = false)
    }

    
    fun updateTimerText(mainText: String, subText: String? = null) {
        val view = mLockView ?: return
        Handler(Looper.getMainLooper()).post {
            view.findViewById<TextView>(R.id.tv_timer)?.text = mainText
            if (subText != null) {
                view.findViewById<TextView>(R.id.tv_sub_status)?.text = subText
            }
        }
    }

    internal fun resolveDisplayedLockReason(
        rawReason: String?,
        isAppWindowBlocked: Boolean,
        isAntiUninstall: Boolean,
        isBlacklist: Boolean,
        appLabel: String?,
        blockedPackageName: String?,
        packageLabelResolver: ((String) -> String?)? = null
    ): String {
        val label = resolveDisplayAppLabel(
            rawReason = rawReason,
            appLabel = appLabel,
            blockedPackageName = blockedPackageName,
            packageLabelResolver = packageLabelResolver
        )
        return when {
            isAppWindowBlocked -> "应用时间窗锁机（$label）"
            isAntiUninstall -> "防卸载拦截"
            isBlacklist && label.startsWith("二级解锁休息到期") -> label
            isBlacklist -> "黑名单锁机（$label）"
            !rawReason.isNullOrBlank() -> rawReason
            else -> "未知原因"
        }
    }

    internal fun resolveDisplayAppLabel(
        rawReason: String?,
        appLabel: String?,
        blockedPackageName: String?,
        packageLabelResolver: ((String) -> String?)? = null
    ): String {
        appLabel?.takeIf { it.isNotBlank() }?.let { return it }

        val pkgFromParam = blockedPackageName?.takeIf { it.isNotBlank() }
        val pkgFromReason = rawReason
            ?.takeIf { it.startsWith("Blocked:", ignoreCase = true) }
            ?.substringAfter(":", "")
            ?.trim()
            ?.takeIf { it.isNotBlank() }
        val targetPkg = pkgFromParam ?: pkgFromReason

        if (!targetPkg.isNullOrBlank()) {
            val resolved = packageLabelResolver?.invoke(targetPkg)
            if (!resolved.isNullOrBlank()) return resolved
        }

        return "限制应用"
    }

    private fun buildLockReasonPrefix(
        isAppWindowBlocked: Boolean = _isAppWindowLock.get()
    ): String {
        val effectiveAppWindowBlocked = isAppWindowBlocked || _isAppWindowLock.get()
        val reasonText = resolveDisplayedLockReason(
            rawReason = mLockEntryReasonRaw,
            isAppWindowBlocked = effectiveAppWindowBlocked,
            isAntiUninstall = isAntiUninstallLock,
            isBlacklist = isBlacklistLock,
            appLabel = mBlockedAppLabel,
            blockedPackageName = lastBlockedPackageName,
            packageLabelResolver = { pkg ->
                try {
                    val ctx = mContext ?: return@resolveDisplayedLockReason null
                    val pm = ctx.packageManager
                    pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
                } catch (_: Exception) {
                    null
                }
            }
        )
        return "触发原因: $reasonText"
    }

    fun updateFreeTimeText(text: String) {
        val view = mLockView ?: return
        Handler(Looper.getMainLooper()).post {
            val subStatus = view.findViewById<TextView>(R.id.tv_sub_status) ?: return@post
            val curText = subStatus.text?.toString() ?: ""
            // 替换或追加休息倒计时行
            val cleaned = curText.replace(Regex("\n?🟢 休息中.*"), "")
            subStatus.text = if (cleaned.isBlank()) text else "$cleaned\n$text"
        }
    }

    fun showFreeTimeLayout(show: Boolean) {
        // 休息信息已移至副标题显示，此函数不再需要
        // 保留空实现避免调用方报错
    }

    // Updates UI elements based on current state (Full Data Binding)
    private var mLastBorrowTickKey: String? = null

    fun updateStatus(context: Context) {
        val view = mLockView ?: return

        mUiHandler.post {
            try {
                isAppWindowRestrictionActive = false

                val remaining = CreditManager.getRemainingMinutes(context)
                val currentSeg = TimeRuleManager.getCurrentSegment(context)

                val prevRemaining = CreditManager.getAllPreviousSegmentsRemainingMinutes(context)
                val now = System.currentTimeMillis()
                val borrowedMinsFromNext = CreditManager.getBorrowedMinutesFromNext(context)
                val borrowedCountFromNext = CreditManager.getBorrowedUsableCountFromNext(context)

                // Time Stats
                val tvTimeBig = view.findViewById<TextView>(R.id.tv_stat_time_big)
                val tvTimeDetail = view.findViewById<TextView>(R.id.tv_stat_time_detail)
                
                val tvCurrentRange = view.findViewById<TextView>(R.id.tv_current_segment_range)


                if (currentSeg == null) {
                    // Gap Mode — 显示下一段预览
                    val gapTitle = "自由空档期"

                    val prevSeg = TimeRuleManager.getPreviousSegment(context)
                    val nextSeg = TimeRuleManager.getNextSegment(context)
                    val prevEnd = prevSeg?.let { String.format("%02d:%02d", it.endHour, it.endMinute) } ?: "00:00"
                    val nextStart = nextSeg?.let { String.format("%02d:%02d", it.startHour, it.startMinute) } ?: "24:00"

                    tvCurrentRange?.text = "$gapTitle ($prevEnd - $nextStart)"

                    // 空档期：正常显示前序结余的时长和次数
                    tvTimeBig?.visibility = View.VISIBLE
                    tvTimeDetail?.visibility = View.VISIBLE
                    tvUsableCountBig?.visibility = View.VISIBLE
                    tvSurplusBorrowLine?.visibility = View.GONE

                    val remaining = CreditManager.getRemainingMinutes(context)
                    val usableRemaining = CreditManager.getRemainingUsableCountHalfUnits(context)

                    tvTimeBig?.text = "${formatMinutesForLock(remaining)}min"
                    tvUsableCountBig?.text = "${formatUsableCountHalfUnits(usableRemaining)}次"

                    val timeDetail = if (remaining > 0.0) "时间 ${formatMinutesForLock(remaining)}min(前期结余)" else "时间 0min"
                    val countDetail = if (usableRemaining > 0) "次数 ${formatUsableCountHalfUnits(usableRemaining)}次(前期结余)" else "次数 0次"
                    tvTimeDetail?.text = "$timeDetail  |  $countDetail"

                    tvRestCountdownInCard?.apply {
                        if (CreditManager.isFreeTime(context)) {
                            visibility = View.VISIBLE
                            val restEnd = CreditManager.getFreeTimeEnd(context)
                            val remainSec = ((restEnd - now + 999) / 1000).toInt()
                            text = "🟢 休息中 剩余 ${remainSec}s"
                        } else {
                            visibility = View.GONE
                        }
                    }

                    // 显示下一段预览
                    if (nextSeg != null) {
                        val range = "${String.format("%02d:%02d", nextSeg.startHour, nextSeg.startMinute)} - ${String.format("%02d:%02d", nextSeg.endHour, nextSeg.endMinute)}"
                        val nextLimit = nextSeg.limitMins
                        val nextCountLimit = nextSeg.getEffectiveUsableCountLimit()
                        // 🔴 [BugFix] 空档期：若上一段是逻辑最后一段，则下一段属于下一个业务日，不可借用
                        val prevSegForBizCheck = TimeRuleManager.getPreviousSegment(context)
                        val isNextDifferentBizDay = prevSegForBizCheck != null && TimeRuleManager.isLastLogicalSegment(context, TimeRuleManager.getTimeSegments(context).indexOf(prevSegForBizCheck))
                        val borrowRatio = TimeRuleManager.getBorrowPercentage(context)
                        val (maxBorrowMins, maxBorrowCount) = if (isNextDifferentBizDay) Pair(0.0, 0) else computeMaxBorrowableForNextSegment(nextLimit, nextCountLimit, borrowRatio)
                        var infoText = "下一段 | ${nextSeg.name} ($range)"
                        infoText += "\n时长 ${nextLimit}min  |  次数 ${formatUsableCountHalfUnits(nextCountLimit)}次"
                        infoText += if (isNextDifferentBizDay) "\n不可借用（明日额度）" else "\n最多可借：时长 ${formatMinutesForLock(maxBorrowMins)}min  |  次数 ${formatUsableCountHalfUnits(maxBorrowCount)}次"
                        view.findViewById<TextView>(R.id.tv_next_segment_info)?.text = infoText
                        view.findViewById<TextView>(R.id.tv_next_segment_info)?.visibility = View.VISIBLE
                    } else {
                        view.findViewById<TextView>(R.id.tv_next_segment_info)?.text = "今日时段已结束"
                        view.findViewById<TextView>(R.id.tv_next_segment_info)?.visibility = View.VISIBLE
                    }
                } else {
                    // Normal Segment
                    tvCurrentRange?.text = "${currentSeg.name} (${currentSeg.formatTimeRange()})"

                    tvTimeBig?.visibility = View.VISIBLE
                    tvTimeDetail?.visibility = View.VISIBLE
                    tvUsableCountBig?.visibility = View.VISIBLE

                    val currentLimitMins = CreditManager.getSegmentEffectiveLimitMinutes(context, currentSeg)
                    val currentLimitStr = formatMinutesForLock(currentLimitMins)
                    val debtMinutes = CreditManager.getDebtMinutesToPrevious(context)

                    tvTimeBig?.text = "${formatMinutesForLock(remaining)}min"

                    // 🆕 次数栏
                    val usableLimit = CreditManager.getSegmentEffectiveUsableCountLimitHalfUnits(context, currentSeg)
                    val usableUsed = CreditManager.getUsedUsableCountForCurrentSegment(context)
                    val usableBorrowed = borrowedCountFromNext
                    val usablePrevRemaining = CreditManager.getAllPreviousSegmentsRemainingUsableCount(context)
                    // 🐛 [BugFix 2026-07-01] 借用后已立即消耗的 principal 不应再加进 remaining；
                    //     已借总额另起 “已借：时长+次数” 行渲染，详见 BorrowRemainingCalculator.kt。
                    val usableRemaining = computeCurrentUsableRemaining(usableLimit, usableUsed, usablePrevRemaining)

                    tvUsableCountBig?.text = "${formatUsableCountHalfUnits(usableRemaining)}次"

                    val carry = CreditManager.captureSurplusCarrySnapshot(context, currentSeg)
                    val timeModel = calculateQuotaDetail(remaining, currentLimitMins, prevRemaining, carry.timeTotal)
                    val countModel = calculateQuotaDetail(usableRemaining.toDouble(), usableLimit.toDouble(), usablePrevRemaining.toDouble(), carry.countTotal.toDouble())
                    val timeDetail = if (timeModel.surplusTotal > 0.0) {
                        "时间 ${formatMinutesForLock(timeModel.currentRemaining)}/${currentLimitStr} + ${formatMinutesForLock(timeModel.surplusRemaining)}/${formatMinutesForLock(timeModel.surplusTotal)}min(结余)"
                    } else {
                        "时间 ${formatMinutesForLock(remaining)}/${currentLimitStr}min"
                    }
                    val countDetail = if (countModel.surplusTotal > 0.0) {
                        "次数 ${formatUsableCountHalfUnits(countModel.currentRemaining.toInt())}/${formatUsableCountHalfUnits(usableLimit)} + ${formatUsableCountHalfUnits(countModel.surplusRemaining.toInt())}/${formatUsableCountHalfUnits(countModel.surplusTotal.toInt())}次(结余)"
                    } else {
                        "次数 ${formatUsableCountHalfUnits(usableRemaining)}/${formatUsableCountHalfUnits(usableLimit)}次"
                    }
                    tvTimeDetail?.text = "$timeDetail  |  $countDetail"

                    // 借用行：仅显示借了什么，不显示惩罚公式
                    val borrowedMins = if (borrowedMinsFromNext > 0) borrowedMinsFromNext else 0.0
                    val borrowedCount = if (usableBorrowed > 0) usableBorrowed else 0

                    if (borrowedMins > 0 || borrowedCount > 0) {
                        val parts = mutableListOf<String>()
                        if (borrowedMins > 0) parts.add("时长 ${formatMinutesForLock(borrowedMins)}min")
                        if (borrowedCount > 0) parts.add("次数 ${formatUsableCountHalfUnits(borrowedCount)}次")
                        tvSurplusBorrowLine?.text = "已借：${parts.joinToString(" + ")}"
                        tvSurplusBorrowLine?.setTextColor(ThemeManager.getColorAttr(context, R.attr.zzWarning))
                        tvSurplusBorrowLine?.visibility = View.VISIBLE
                    } else {
                        tvSurplusBorrowLine?.visibility = View.GONE
                    }

                    // 🆕 休息倒计时移入卡片
                    tvRestCountdownInCard?.apply {
                        if (CreditManager.isFreeTime(context)) {
                            visibility = View.VISIBLE
                            val restEnd = CreditManager.getFreeTimeEnd(context)
                            val remainSec = ((restEnd - now + 999) / 1000).toInt()
                            text = "🟢 休息中 剩余 ${remainSec}s"
                        } else {
                            visibility = View.GONE
                        }
                    }
                }

                // 惩罚计数可视化（仅 DND 时段活跃时展示）
                val tvPenaltyHitCount = view.findViewById<TextView>(R.id.tv_penalty_hit_count)
                val nowPenalty = System.currentTimeMillis()
                val inPenaltyWindow = nowPenalty - mPenaltyWindowStart <= PENALTY_WINDOW_MS && mPenaltyHitCount > 0
                val isDndActiveNow = TimeRuleManager.getCurrentSegment(context) != null
                        && System.currentTimeMillis() >= CreditManager.getFreeTimeEnd(context)
                if (isDndActiveNow && inPenaltyWindow && mPenaltyHitCount < PENALTY_THRESHOLD) {
                    tvPenaltyHitCount?.visibility = View.VISIBLE
                    tvPenaltyHitCount?.text = "⚠️ 30秒内返回桌面 ${mPenaltyHitCount}/${PENALTY_THRESHOLD} 次"
                } else {
                    tvPenaltyHitCount?.visibility = View.GONE
                }

                // 下一段卡片 — 6 行统一格式
                val tvNextInfo = view.findViewById<TextView>(R.id.tv_next_segment_info)
                val nextSegObj = TimeRuleManager.getNextSegment(context)
                if (nextSegObj != null) {
                    val range = "${String.format("%02d:%02d", nextSegObj.startHour, nextSegObj.startMinute)} - ${String.format("%02d:%02d", nextSegObj.endHour, nextSegObj.endMinute)}"
                    val nextLimit = nextSegObj.limitMins
                    val nextCountLimit = nextSegObj.getEffectiveUsableCountLimit()

                    // 🔴 [BugFix] 当前段是逻辑最后一段时，下一段属于下一个业务日，不可借用
                    val curIdx = TimeRuleManager.getCurrentSegmentIndex(context)
                    val isNextDifferentBizDay = curIdx != -1 && TimeRuleManager.isLastLogicalSegment(context, curIdx)

                    // 行1: 标题
                    var infoText = "下一段 | ${nextSegObj.name} ($range)"

                    // 行2: 配额
                    infoText += "\n时长 ${nextLimit}min  |  次数 ${formatUsableCountHalfUnits(nextCountLimit)}次"

                    // 行3: 最多可借
                    val borrowRatio = TimeRuleManager.getBorrowPercentage(context)
                    val (maxBorrowMins, maxBorrowCount) = if (isNextDifferentBizDay) Pair(0.0, 0) else computeMaxBorrowableForNextSegment(nextLimit, nextCountLimit, borrowRatio)
                    infoText += if (isNextDifferentBizDay) "\n不可借用（明日额度）" else "\n最多可借：时长 ${formatMinutesForLock(maxBorrowMins)}min  |  次数 ${formatUsableCountHalfUnits(maxBorrowCount)}次"

                    // 行4-6: 仅当有预支时显示
                    if (borrowedMinsFromNext > 0 || borrowedCountFromNext > 0) {
                        // 行4: 已预支
                        infoText += "\n已预支：时长 ${formatMinutesForLock(borrowedMinsFromNext)}min  |  次数 ${formatUsableCountHalfUnits(borrowedCountFromNext)}次"
                        // 行5: 惩罚扣除
                        val penaltyMins = borrowedMinsFromNext * 2.0
                        val penaltyCount = borrowedCountFromNext * 2
                        infoText += "\n惩罚扣除 (×2) ：-${formatMinutesForLock(penaltyMins)}min  |  -${formatUsableCountHalfUnits(penaltyCount)}次"
                        // 行6: 实际生效
                        val effectiveMins = (nextLimit - penaltyMins).coerceAtLeast(0.0)
                        val effectiveCount = (nextCountLimit - penaltyCount).coerceAtLeast(0)
                        infoText += "\n实际生效：时长 ${formatMinutesForLock(effectiveMins)}min  |  次数 ${formatUsableCountHalfUnits(effectiveCount)}次"
                    }

                    tvNextInfo?.text = infoText
                    tvNextInfo?.visibility = View.VISIBLE
                } else {
                    tvNextInfo?.text = "今日时段已结束"
                    tvNextInfo?.visibility = View.VISIBLE
                }
                
                // 5. 🚀 [UI] Status Text Logic - 显示强制锁机倒计时

                val pkgName = lastBlockedPackageName
                val isAppWindowManaged = !TextUtils.isEmpty(pkgName) && AppWindowManager.isManaged(context, pkgName!!)
                val allowedSummary = if (isAppWindowManaged) {
                    AppWindowManager.getAllowedWindowsSummary(context, pkgName!!)
                } else {
                    ""
                }
                val configuredSummary = if (isAppWindowManaged) {
                    AppWindowManager.getConfiguredWindowsSummary(context, pkgName!!)
                } else {
                    ""
                }
                val isWindowOpen = if (isAppWindowManaged) {
                    AppWindowManager.isAppAllowed(context, pkgName!!)
                } else {
                    true
                }
                val isAppWindowBlocked = (isAppWindowManaged && !isWindowOpen) || _isAppWindowLock.get()
                isAppWindowRestrictionActive = isAppWindowBlocked
                currentLockRestrictionMode = resolveLockOverlayRestrictionMode(
                    isAppWindowBlocked = isAppWindowBlocked,
                    isWithinDndSegment = currentSeg != null
                )
                
                // 统一计算主标题，冷却期不再切到"冷静强制锁"
                val label = resolveDisplayAppLabel(
                    rawReason = mLockEntryReasonRaw,
                    appLabel = mBlockedAppLabel,
                    blockedPackageName = lastBlockedPackageName,
                    packageLabelResolver = { pkg ->
                        try {
                            val pm = context.packageManager
                            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
                        } catch (_: Exception) { null }
                    }
                )
                val reasonPrefix = buildLockReasonPrefix(
                    isAppWindowBlocked = isAppWindowBlocked
                )
                var subText = "$reasonPrefix\n当前正在使用: $label"
                var mainText = "勿扰锁定中"

                if (!TextUtils.isEmpty(lastBlockedPackageName)) {
                    if (isAppWindowBlocked) {
                        val displaySummary = resolveAllowedWindowSummaryForDisplay(
                            allowedTodaySummary = allowedSummary,
                            configuredSummary = configuredSummary
                        )
                        subText = "$reasonPrefix\n⛔ ${label} 受时间窗限制，当前不可用\n"
                        subText += "📅 仅在以下时段可用:\n$displaySummary"
                        mainText = "🚫 应用时间窗限制"
                    } else {
                        if (currentSeg == null) {
                            // 空档期黑名单锁机（非时间窗拦截）
                            subText += "\n该应用已列入黑名单，当前不可用"
                            mainText = "黑名单锁定中"
                        } else {
                            subText += "\n受限时段请保持自律"
                            mainText = "勿扰锁定中"
                        }
                    }
                } else {
                    val currentSeg = TimeRuleManager.getCurrentSegment(context)
                    if (currentSeg == null) {
                        // 空档期黑名单锁机（非时间窗拦截）
                        subText += "\n该应用已列入黑名单，当前不可用"
                        mainText = "黑名单锁定中"
                    } else {
                        subText += "\n受限时段请保持自律"
                        mainText = "勿扰锁定中"
                    }
                }
                // 冷却倒计时（智能合并临时解锁冷却与休息冷却，副标题末尾展示）
                val restCoolEnd = CreditManager.getCoolDownEnd(context)
                val restRemainSec = if (now < restCoolEnd && !CreditManager.isFreeTime(context)) {
                    ((restCoolEnd - now + 999) / 1000).toInt().coerceAtLeast(0)
                } else 0
                val tempRemainSec = getTemporaryReuseCooldownRemainingSeconds(now).toInt().coerceAtLeast(0)

                if (tempRemainSec > 0 || restRemainSec > 0) {
                    val coolLabel = if (tempRemainSec >= restRemainSec) "临时解锁冷却中" else "休息冷却中"
                    val coolSec = maxOf(tempRemainSec, restRemainSec)
                    subText += "\n⏳ $coolLabel 剩余 ${coolSec}s"
                }
                updateTimerText(mainText, subText)

                updateLockViewUI() // Ensure button text reflects latest restriction type

            } catch (e: Exception) {
                Log.e(TAG, "refreshLockStateUI failed", e)
            }
        }
    }

    // 检查是否"休息配额已耗尽"（时长或次数一个用完即耗尽）
    fun isRestQuotaExhausted(context: Context): Boolean {
        // 空档期/休息中：临时解锁免费，不受额度限制（spec §免扣场景）
        val currentSeg = TimeRuleManager.getCurrentSegment(context)
        if (currentSeg == null) return false
        if (CreditManager.isFreeTime(context)) return false

        val remainingMins = CreditManager.getRemainingMinutes(context)
        val remainingBorrowable = CreditManager.getMaxBorrowableMinutes(context)
        if ((remainingMins + remainingBorrowable) < 0.1) return true

        // 次数检查
        val effCountLimit = currentSeg.getEffectiveUsableCountLimit()
        if (effCountLimit == 0) return true // 禁止临时解锁/休息

        val countRemaining = CreditManager.getRemainingUsableCount(context)
        val countBorrowable = CreditManager.getMaxBorrowableUsableCount(context)
        if ((countRemaining + countBorrowable) < 1) return true

        return false
    }

    // 检查是否"临时解锁配额已耗尽"（当前额度与借用额度均用尽）
    fun isTemporaryAccessQuotaExhausted(context: Context): Boolean {
        val currentSeg = TimeRuleManager.getCurrentSegment(context)
        if (currentSeg == null) return false
        if (CreditManager.isFreeTime(context)) return false

        val effCountLimit = currentSeg.getEffectiveUsableCountLimit()
        if (effCountLimit == 0) return true // 禁止临时解锁

        val remainingMins = CreditManager.getRemainingMinutes(context)
        val remainingBorrowable = CreditManager.getMaxBorrowableMinutes(context)
        if ((remainingMins + remainingBorrowable) < 0.1) return true

        val countRemaining = CreditManager.getRemainingUsableCount(context)
        val countBorrowable = CreditManager.getMaxBorrowableUsableCount(context)
        if ((countRemaining + countBorrowable) < 1) return true

        return false
    }

    internal fun formatMinutesForLock(minutes: Double): String = formatAvailableMinutesForLockImpl(minutes)

    // Updates UI elements based on current state (Basic)
    private fun updateLockViewUI() {
        val context = mContext ?: return
        val tempAccessMaxSeconds = getTemporaryAccessMaxSeconds(context)
        
        // 🚀 [FIX] Don't reset panel state if user is typing
        // 🚀 [FIX] Don't reset panel state if user is typing
        if (!isInputPanelOpen) {
            btnEmergency?.visibility = View.VISIBLE 
        }
        
        val now = System.currentTimeMillis()

        // NORMAL MODE
        btnBackToHome?.visibility = View.VISIBLE
        btnBackToHome?.text = "🏠 回到桌面"
        btnBackToHome?.isEnabled = true
        btnBackToHome?.alpha = 1.0f

        btnEmergency?.isEnabled = true
        btnEmergency?.alpha = 1.0f

        val tempCooldownRemain = getTemporaryReuseCooldownRemainingSeconds(now)
        val emergencyRemainingUses = resolveTemporaryEmergencyRemainingUses(
            reuseRemainSeconds = tempCooldownRemain,
            fixedRemainingUses = temporaryEmergencyRemainingUsesInCooldown
        )
        val emergencyGapRemain = resolveTemporaryEmergencyReuseGapRemainingSeconds(
            temporaryEmergencyLastUseAtInCooldown,
            now
        )

        val restExhausted = isRestQuotaExhausted(context)
        val tempExhausted = isTemporaryAccessQuotaExhausted(context)
        val privilegeReason = CreditManager.getWeeklyPrivilegeUnavailableReason(context)

        val restCoolEnd = CreditManager.getCoolDownEnd(context)
        val tempCoolActive = tempCooldownRemain > 0
        val inAnyCooling = now < restCoolEnd || tempCoolActive

        // 🌟 纯粹三出口架构渲染：DND 模式下主操作为申请休息，同时保留独立的临时解锁入口
        val tempPresentation = resolveTemporaryAccessPresentation(
            isTemporaryAccessExhausted = tempExhausted,
            inAnyCooling = inAnyCooling,
            coolingRemainingSeconds = tempCooldownRemain
        )
        btnTemporaryAccess?.visibility =
            if (shouldShowStandaloneTemporaryAccess(currentLockRestrictionMode)) {
                View.VISIBLE
            } else {
                View.GONE
            }
        btnTemporaryAccess?.text = tempPresentation.label
        btnTemporaryAccess?.isEnabled = tempPresentation.enabled
        btnTemporaryAccess?.alpha = tempPresentation.alpha

        btnTemporaryAccessQuickTen?.visibility = View.GONE
        btnTemporaryAccessHelp?.visibility = View.GONE

        // 1. 每周特权按钮状态更新
        val weeklyPresentation = resolveWeeklyPrivilegePresentation(privilegeReason)
        val isWeeklyEnabled = weeklyPresentation.enabled
        btnWeeklyPrivilege?.visibility = View.VISIBLE
        btnWeeklyPrivilege?.text = weeklyPresentation.label
        btnWeeklyPrivilege?.isEnabled = isWeeklyEnabled
        btnWeeklyPrivilege?.alpha = 1.0f
        if (isWeeklyEnabled) {
            btnWeeklyPrivilege?.backgroundTintList = ColorStateList.valueOf(ThemeManager.getColorAttr(context, R.attr.zzAltSoft))
            btnWeeklyPrivilege?.setTextColor(ThemeManager.getColorAttr(context, R.attr.zzAlt))
        } else {
            btnWeeklyPrivilege?.backgroundTintList = ColorStateList.valueOf(ThemeManager.getColorAttr(context, R.attr.zzSurface2))
            btnWeeklyPrivilege?.setTextColor(ThemeManager.getColorAttr(context, R.attr.zzMuted))
        }

        // 2. 场景专属主操作按钮更新 (时间窗->临时解锁, 勿扰->申请休息)
        val emergencyPresentation = resolveEmergencyActionPresentation(
            mode = currentLockRestrictionMode,
            privilegeUnavailableReason = privilegeReason,
            restExhausted = restExhausted,
            inAnyCooling = inAnyCooling,
            coolingRemainingSeconds = tempCooldownRemain,
            isTemporaryAccessExhausted = tempExhausted
        )
        val isEmergencyEnabled = emergencyPresentation.enabled
        btnEmergency?.visibility = View.VISIBLE
        btnEmergency?.text = emergencyPresentation.label
        btnEmergency?.isEnabled = isEmergencyEnabled
        btnEmergency?.alpha = 1.0f
        if (isEmergencyEnabled) {
            btnEmergency?.backgroundTintList = ColorStateList.valueOf(ThemeManager.getColorAttr(context, R.attr.zzAccent))
            btnEmergency?.setTextColor(Color.WHITE)
        } else {
            btnEmergency?.backgroundTintList = ColorStateList.valueOf(ThemeManager.getColorAttr(context, R.attr.zzSurface2))
            btnEmergency?.setTextColor(ThemeManager.getColorAttr(context, R.attr.zzMuted))
        }

        // 🌟 防穿透：同一操作槽内 btnEmergency 可见时，底层的 btnTemporaryAccess 强制 GONE
        btnTemporaryAccess?.visibility = View.GONE
    }

    // 🚀 [Performance] 轻量 tick：每秒由 REASSERT 触发，只更新变化的数字和按钮冷却
    // 不做应用时间窗/包名解析/跨段借贷计算等全量操作（保留给 updateStatus 事件驱动刷新）
    private fun tickLightweightUI() {
        val ctx = mContext ?: return
        try {
            val now = System.currentTimeMillis()
            val view = mLockView ?: return

            // 1. 副标题倒计时（每秒更新秒数）：智能合并临时解锁与休息冷却倒计时
            val subStatusView = view.findViewById<TextView>(R.id.tv_sub_status)
            if (subStatusView != null) {
                val curText = subStatusView.text?.toString() ?: ""
                val restCoolEnd = CreditManager.getCoolDownEnd(ctx)
                val restRemainSec = if (now < restCoolEnd && !CreditManager.isFreeTime(ctx)) {
                    ((restCoolEnd - now + 999) / 1000).toInt().coerceAtLeast(0)
                } else 0
                val tempRemainSec = getTemporaryReuseCooldownRemainingSeconds(now).toInt().coerceAtLeast(0)

                val hasCooldownPattern = curText.contains("冷却中 剩余")
                if (tempRemainSec > 0 || restRemainSec > 0) {
                    val coolLabel = if (tempRemainSec >= restRemainSec) "临时解锁冷却中" else "休息冷却中"
                    val coolSec = maxOf(tempRemainSec, restRemainSec)
                    if (hasCooldownPattern) {
                        subStatusView.text = curText.replace(
                            Regex("⏳ (?:临时解锁|休息)?冷却中 剩余 \\d+s"),
                            "⏳ $coolLabel 剩余 ${coolSec}s"
                        )
                    } else {
                        subStatusView.text = "$curText\n⏳ $coolLabel 剩余 ${coolSec}s"
                    }
                } else if (hasCooldownPattern) {
                    // 冷却结束，移除冷却行
                    subStatusView.text = curText.replace(
                        Regex("\n?⏳ (?:临时解锁|休息)?冷却中 剩余 \\d+s"),
                        ""
                    )
                }
            }

            // 2. 额度大数字（临时解锁后会变化）
            val remaining = CreditManager.getRemainingMinutes(ctx)
            val currentSeg = TimeRuleManager.getCurrentSegment(ctx)
            if (currentSeg != null) {
                view.findViewById<TextView>(R.id.tv_stat_time_big)?.text = "${formatMinutesForLock(remaining)}min"
                view.findViewById<TextView>(R.id.tv_usable_count_big)?.visibility = View.VISIBLE

                val usableLimit = CreditManager.getSegmentEffectiveUsableCountLimitHalfUnits(ctx, currentSeg)
                // 🐛 [Fix 2026-07-01] 借用后本金已立即消费，不应再作为可用次数展示；
                // 统一使用 getRemainingUsableCount()，与按钮可用性判定一致
                val usableRemainingFromManager = CreditManager.getRemainingUsableCount(ctx)
                val usableUsed = CreditManager.getUsedUsableCountForCurrentSegment(ctx)
                val usableBorrowed = CreditManager.getBorrowedUsableCountFromNext(ctx)
                val usablePrevRemaining = CreditManager.getAllPreviousSegmentsRemainingUsableCount(ctx)
                view.findViewById<TextView>(R.id.tv_usable_count_big)?.text = "${formatUsableCountHalfUnits(usableRemainingFromManager)}次"
                val tickStateKey = "d=${usableRemainingFromManager}|b=${usableBorrowed}|u=${usableUsed}|l=${usableLimit}|p=${usablePrevRemaining}"
                if (tickStateKey != mLastBorrowTickKey) {
                    mLastBorrowTickKey = tickStateKey
                    if (DefaultLogDebugConfig.quotaCalculation) {
                        LogExporter.d("LockWindowManager", "[BORROW_UI_DEBUG][tickUI] displayCount=${usableRemainingFromManager} sourceRemaining=${usableRemainingFromManager} sourceBorrowed=${usableBorrowed} used=${usableUsed} limit=${usableLimit} prevRemaining=${usablePrevRemaining}")
                    }
                }

                // 明细行同步刷新（与 updateStatus 保持一致）
                val prevRemaining = CreditManager.getAllPreviousSegmentsRemainingMinutes(ctx)
                val currentLimitMins = CreditManager.getSegmentEffectiveLimitMinutes(ctx, currentSeg)
                val currentLimitStr = formatMinutesForLock(currentLimitMins)
                val carry = CreditManager.captureSurplusCarrySnapshot(ctx, currentSeg)
                val timeModel = calculateQuotaDetail(remaining, currentLimitMins, prevRemaining, carry.timeTotal)
                val countModel = calculateQuotaDetail(
                    usableRemainingFromManager.toDouble(), usableLimit.toDouble(),
                    usablePrevRemaining.toDouble(), carry.countTotal.toDouble()
                )
                val timeDetail = if (timeModel.surplusTotal > 0.0) {
                    "时间 ${formatMinutesForLock(timeModel.currentRemaining)}/${currentLimitStr} + ${formatMinutesForLock(timeModel.surplusRemaining)}/${formatMinutesForLock(timeModel.surplusTotal)}min(结余)"
                } else {
                    "时间 ${formatMinutesForLock(remaining)}/${currentLimitStr}min"
                }
                val countDetail = if (countModel.surplusTotal > 0.0) {
                    "次数 ${formatUsableCountHalfUnits(countModel.currentRemaining.toInt())}/${formatUsableCountHalfUnits(usableLimit)} + ${formatUsableCountHalfUnits(countModel.surplusRemaining.toInt())}/${formatUsableCountHalfUnits(countModel.surplusTotal.toInt())}次(结余)"
                } else {
                    "次数 ${formatUsableCountHalfUnits(usableRemainingFromManager)}/${formatUsableCountHalfUnits(usableLimit)}次"
                }
                view.findViewById<TextView>(R.id.tv_stat_time_detail)?.text = "$timeDetail  |  $countDetail"
            } else {
                view.findViewById<TextView>(R.id.tv_stat_time_big)?.text = "${formatMinutesForLock(remaining)}min"
                val usableRemaining = CreditManager.getRemainingUsableCountHalfUnits(ctx)
                view.findViewById<TextView>(R.id.tv_usable_count_big)?.text = "${formatUsableCountHalfUnits(usableRemaining)}次"
                val timeDetail = if (remaining > 0.0) "时间 ${formatMinutesForLock(remaining)}min(前期结余)" else "时间 0min"
                val countDetail = if (usableRemaining > 0) "次数 ${formatUsableCountHalfUnits(usableRemaining)}次(前期结余)" else "次数 0次"
                view.findViewById<TextView>(R.id.tv_stat_time_detail)?.text = "$timeDetail  |  $countDetail"
            }

            // 🆕 卡片内休息倒计时 tick
            val restCountdownView = view.findViewById<TextView>(R.id.tv_rest_countdown_in_card)
            if (restCountdownView != null) {
                val freeTimeEnd = CreditManager.getFreeTimeEnd(ctx)
                if (now < freeTimeEnd) {
                    restCountdownView.visibility = View.VISIBLE
                    val remainSec = ((freeTimeEnd - now + 999) / 1000).toInt()
                    restCountdownView.text = "🟢 休息中 剩余 ${remainSec}s"
                } else {
                    restCountdownView.visibility = View.GONE
                }
            }

            // 3. 按钮状态（含冷却倒计时、应急间隔倒计时）
            updateLockViewUI()
        } catch (e: Exception) {
            // Silently ignore - next full update will fix any issues
        }
    }

    // strict 惩罚链路已移除，检测循环始终不因 strict 状态暂停。
    fun isInStrictPenaltyCycle(): Boolean {
        return false
    }

    fun updateWindowFocus(focusable: Boolean) {
        if (mLockView == null || mActiveWindowManager == null || !isLocked) return
        try {
            val params = mLockView!!.layoutParams as WindowManager.LayoutParams
            if (focusable) {
                params.flags = params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
            } else {
                params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            }
            mActiveWindowManager!!.updateViewLayout(mLockView, params)
        } catch (e: Exception) {
            Log.e(TAG, "updateWindowFocus failed", e)
        }
    }

    private fun scheduleSystemDialogRestoreTimeout() {
        systemDialogRestoreRunnable?.let { mUiHandler.removeCallbacks(it) }
        systemDialogRestoreRunnable = Runnable {
            LogExporter.logEvent(
                "SystemDialog",
                "[RESTORE_BY_TIMEOUT] mode=$systemDialogCompatMode, rom=${Build.MANUFACTURER}/${Build.MODEL}"
            )
            restoreLockFocus(byTimeout = true)
        }
        mUiHandler.postDelayed(systemDialogRestoreRunnable!!, SYSTEM_DIALOG_RESTORE_DELAY_MS)
    }

    private fun tryApplyPrimaryDemote(originalParams: WindowManager.LayoutParams): Boolean {
        val lockView = mLockView ?: return false
        val activeWm = mActiveWindowManager ?: return false
        val targetWm = mWindowManager ?: return false
        val demotedParams = buildDemotedParams(originalParams)

        if (demotedParams.type == originalParams.type && activeWm === targetWm) {
            return false
        }

        var removed = false
        return try {
            if (lockView.parent != null) {
                // [Fix: a11y overlay crash] removeView 前清空焦点
                lockView.clearFocus()
                activeWm.removeView(lockView)
                removed = true
            }
            targetWm.addView(lockView, demotedParams)
            mActiveWindowManager = targetWm
            lockView.alpha = DEMOTED_ALPHA
            systemDialogCompatMode = SystemDialogCompatMode.REAL_DEMOTE
            LogExporter.logEvent(
                "SystemDialog",
                "[DEMOTE_PRIMARY_OK] mode=$systemDialogCompatMode, from=${typeName(originalParams.type)}, to=${typeName(demotedParams.type)}, rom=${Build.MANUFACTURER}/${Build.MODEL}"
            )
            true
        } catch (_: Exception) {
            if (removed) {
                try {
                    activeWm.addView(lockView, originalParams)
                    mActiveWindowManager = activeWm
                } catch (restoreEx: Exception) {
                    LogExporter.logEvent(
                        "SystemDialog",
                        "[RESTORE_FAIL_RETRY] stage=primary_recover, err=${restoreEx.javaClass.simpleName}, rom=${Build.MANUFACTURER}/${Build.MODEL}"
                    )
                }
            }
            false
        }
    }

    /**
     * 🚀 [System Dialog Compat] 处理系统对话框（关机菜单等）的显示/隐藏
     * 语义：仅执行“临时降层兼容态”进入/恢复，不触发解锁。
     */
    fun onSystemDialogChanged(visible: Boolean) {
        if (!isLocked) return
        if (!visible && isSystemDialogVisible && !shouldAcceptSystemDialogEventRestore()) {
            val now = System.currentTimeMillis()
            if (now - systemDialogLastDeferLogAtMs >= SYSTEM_DIALOG_DEFER_LOG_THROTTLE_MS) {
                systemDialogLastDeferLogAtMs = now
                val elapsed = if (systemDialogShownAtMs > 0L) now - systemDialogShownAtMs else -1L
                LogExporter.logEvent(
                    "SystemDialog",
                    "[RESTORE_DEFER] elapsedMs=$elapsed, guardMs=$SYSTEM_DIALOG_EVENT_RESTORE_GUARD_MS, mode=$systemDialogCompatMode"
                )
            }
            return
        }
        if (visible == isSystemDialogVisible) return

        isSystemDialogVisible = visible
        if (visible) {
            systemDialogShownAtMs = System.currentTimeMillis()
        }
        Log.i(TAG, "🔔 System Dialog visibility changed: $visible")
        LogExporter.logEvent(
            "SystemDialog",
            "[${if (visible) "SHOW" else "HIDE"}] isLocked=$isLocked, mode=$systemDialogCompatMode, rom=${Build.MANUFACTURER}/${Build.MODEL}"
        )

        mUiHandler.post {
            if (mLockView == null || mActiveWindowManager == null) return@post
            try {
                if (visible) {
                    val currentParams = mLockView!!.layoutParams as WindowManager.LayoutParams
                    val originalParams = cloneLayoutParams(currentParams)
                    if (systemDialogNormalParams == null) {
                        systemDialogNormalParams = cloneLayoutParams(originalParams)
                        systemDialogNormalViewAlpha = mLockView?.alpha ?: 1.0f
                    }

                    LogExporter.logEvent(
                        "SystemDialog",
                        "[DEMOTE_REQUEST] mode=$systemDialogCompatMode, type=${typeName(originalParams.type)}, rom=${Build.MANUFACTURER}/${Build.MODEL}"
                    )

                    val primaryOk = tryApplyPrimaryDemote(originalParams)
                    if (!primaryOk) {
                        val fallbackParams = cloneLayoutParams(originalParams).apply {
                            flags = flags or
                                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                            alpha = DEMOTED_ALPHA
                        }
                        mActiveWindowManager!!.updateViewLayout(mLockView, fallbackParams)
                        mLockView?.alpha = DEMOTED_ALPHA
                        systemDialogCompatMode = SystemDialogCompatMode.FALLBACK_PASS_THROUGH
                        LogExporter.logEvent(
                            "SystemDialog",
                            "[DEMOTE_FALLBACK_OK] mode=$systemDialogCompatMode, type=${typeName(fallbackParams.type)}, rom=${Build.MANUFACTURER}/${Build.MODEL}"
                        )
                    }
                    scheduleSystemDialogRestoreTimeout()
                } else {
                    restoreLockFocus(byTimeout = false)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error updating lock for system dialog", e)
                LogExporter.logEvent(
                    "SystemDialog",
                    "[RESTORE_FAIL_RETRY] stage=demote_apply, err=${e.javaClass.simpleName}, rom=${Build.MANUFACTURER}/${Build.MODEL}"
                )
            }
        }
    }

    /**
     * 恢复锁屏的正常层级与拦截状态。
     */
    private fun restoreLockFocus(byTimeout: Boolean) {
        isSystemDialogVisible = false
        systemDialogRestoreRunnable?.let { mUiHandler.removeCallbacks(it) }
        systemDialogRestoreRunnable = null

        if (mLockView == null || mActiveWindowManager == null || !isLocked) {
            clearSystemDialogCompatState(resetVisibility = false)
            return
        }

        val lockView = mLockView!!
        val activeWm = mActiveWindowManager!!
        val currentMode = systemDialogCompatMode
        val restoreTag = if (byTimeout) "RESTORE_BY_TIMEOUT" else "RESTORE_BY_EVENT"

        try {
            when (currentMode) {
                SystemDialogCompatMode.REAL_DEMOTE -> {
                    val normalParams = systemDialogNormalParams?.let { cloneLayoutParams(it) }
                        ?: buildNormalLockParams(isServiceActive())
                    val restoreWm = if (normalParams.type == WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY &&
                        isServiceActive() && mCoreService != null
                    ) {
                        mCoreService!!.getSystemService(Context.WINDOW_SERVICE) as WindowManager
                    } else {
                        mWindowManager ?: activeWm
                    }

                    var removed = false
                    try {
                        if (lockView.parent != null) {
                            activeWm.removeView(lockView)
                            removed = true
                        }
                        restoreWm.addView(lockView, normalParams)
                        mActiveWindowManager = restoreWm
                    } catch (_: Exception) {
                        if (removed) {
                            try {
                                activeWm.addView(lockView, normalParams)
                                mActiveWindowManager = activeWm
                            } catch (retry: Exception) {
                                LogExporter.logEvent(
                                    "SystemDialog",
                                    "[RESTORE_FAIL_RETRY] stage=real_demote_restore, err=${retry.javaClass.simpleName}, rom=${Build.MANUFACTURER}/${Build.MODEL}"
                                )
                            }
                        }
                    }
                }

                SystemDialogCompatMode.FALLBACK_PASS_THROUGH,
                SystemDialogCompatMode.NONE -> {
                    val restoreParams = systemDialogNormalParams?.let { cloneLayoutParams(it) }
                        ?: (lockView.layoutParams as? WindowManager.LayoutParams)?.let { cloneLayoutParams(it) }
                        ?: buildNormalLockParams(isServiceActive())
                    if (isLockScreenHiddenForAlarmScan) {
                        restoreParams.flags = restoreParams.flags or
                                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        restoreParams.alpha = 0.0f
                    } else {
                        restoreParams.flags = restoreParams.flags and
                                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv() and
                                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv() and
                                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL.inv()
                        restoreParams.alpha = 1.0f
                    }
                    mActiveWindowManager!!.updateViewLayout(lockView, restoreParams)
                }
            }

            if (isLockScreenHiddenForAlarmScan) {
                lockView.visibility = View.GONE
                lockView.alpha = 0.0f
            } else {
                lockView.visibility = View.VISIBLE
                lockView.alpha = systemDialogNormalViewAlpha
            }
            val currentType = (lockView.layoutParams as? WindowManager.LayoutParams)?.type ?: -1
            LogExporter.logEvent(
                "SystemDialog",
                "[$restoreTag] mode=$currentMode, type=${typeName(currentType)}, rom=${Build.MANUFACTURER}/${Build.MODEL}"
            )
            clearSystemDialogCompatState(resetVisibility = false)
            Log.i(TAG, "🔒 Lock screen focus/layer restored")
        } catch (e: Exception) {
            Log.e(TAG, "Error restoring lock focus", e)
            LogExporter.logEvent(
                "SystemDialog",
                "[RESTORE_FAIL_RETRY] stage=restore_main, mode=$currentMode, err=${e.javaClass.simpleName}, rom=${Build.MANUFACTURER}/${Build.MODEL}"
            )
            try {
                val retryParams = systemDialogNormalParams?.let { cloneLayoutParams(it) }
                    ?: buildNormalLockParams(isServiceActive())
                retryParams.flags = retryParams.flags and
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv() and
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv() and
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL.inv()
                retryParams.alpha = 1.0f
                mActiveWindowManager?.updateViewLayout(lockView, retryParams)
                lockView.alpha = systemDialogNormalViewAlpha
            } catch (retry: Exception) {
                LogExporter.logEvent(
                    "SystemDialog",
                    "[RESTORE_FAIL_RETRY] stage=restore_update, mode=$currentMode, err=${retry.javaClass.simpleName}, rom=${Build.MANUFACTURER}/${Build.MODEL}"
                )
            }
            clearSystemDialogCompatState(resetVisibility = false)
        }
    }
    
    private var lastBlacklistHitTime = 0L
    private const val HIT_COUNT_DEBOUNCE_MS = 600L // 缩短去抖，避免回桌面后重入黑名单触发变慢

    private fun clearBlacklistHitDebounceForUserAction() {
        lastBlacklistHitTime = 0L
    }

    private var lastBlockedPackageName: String? = null
    fun getCurrentBlockedPackageName(): String? = lastBlockedPackageName

    fun getActiveTemporaryAccessTargetPackage(): String? =
        temporaryAccessTargetPackageName?.takeIf { temporaryAccessUntil > System.currentTimeMillis() }

    fun isTemporaryAccessActive(): Boolean =
        temporaryAccessUntil > System.currentTimeMillis()
    @Volatile
    private var isAppWindowRestrictionActive: Boolean = false

    var totalSegmentBlacklistHits = 0
        private set

    private var currentSegmentName: String? = "INIT"

    private fun resetConsecutiveNormalLockState(reason: String) {
        lastBlockedPackageName = null
        Log.d(TAG, "[LockState] reset counters -> $reason")
    }

    // Called by CoreService to check if segment changed and reset penalty state
    fun checkSegmentChange(context: Context) {
        val seg = TimeRuleManager.getCurrentSegment(context)
        val segName = seg?.name ?: "GAP" // Use "GAP" for free time

        if (currentSegmentName != segName) {
            Log.d(TAG, "Segment Changed: $currentSegmentName -> $segName. Resetting counters.")
            currentSegmentName = segName
            totalSegmentBlacklistHits = 0
            resetConsecutiveNormalLockState("segment_changed")
        }
    }
    private fun showRestWindow(context: Context) {
        if (mRestWindow != null) return // Already showing

        // 🚫 休息额度用尽时不允许借用，也不弹出面板
        if (isRestQuotaExhausted(context)) {
            Toast.makeText(context, "今日休息已用完，无法借用", Toast.LENGTH_SHORT).show()
            return
        }

        try {
            // 🚀 [Fix] Wrap Context with Theme here too
            val themedContext = android.view.ContextThemeWrapper(context, ThemeManager.themeRes(context))
            val inflater = LayoutInflater.from(themedContext)
            mRestWindow = inflater.inflate(R.layout.window_rest_request, null)

            val isA11y = mCoreService != null && LockWindowManager.isServiceActive()
            
            // 🚀 [Fix] Use A11y Overlay for Rest Window if Lock Screen is A11y
            // This ensures Rest Window is not obscured by Lock Screen
            val targetWM = if (isA11y) {
                mCoreService!!.getSystemService(Context.WINDOW_SERVICE) as WindowManager 
            } else {
                mWindowManager ?: context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            }

            // Setup LayoutParams
            val params = WindowManager.LayoutParams().apply {
                width = WindowManager.LayoutParams.MATCH_PARENT
                height = WindowManager.LayoutParams.MATCH_PARENT
                format = PixelFormat.TRANSLUCENT
                type = if (isA11y) {
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY 
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                } else {
                    WindowManager.LayoutParams.TYPE_PHONE
                }
                
                // 🟢 Critical: SOFT_INPUT_ADJUST_RESIZE + VISIBLE for keyboard
                flags = WindowManager.LayoutParams.FLAG_DIM_BEHIND
                        // Note: NO FLAG_NOT_FOCUSABLE here (Wait, wait)
                
                dimAmount = 0.6f
                softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or 
                                WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE
            }

            targetWM.addView(mRestWindow, params)
            mActiveRestWindowManager = targetWM
            isInputPanelOpen = true

            // Bind Views
            val tvTitle = mRestWindow?.findViewById<TextView>(R.id.tv_title)
            tvTitle?.text = "🌱 申请休息 (仅解开勿扰锁)"
            mRestWindow?.findViewById<View>(R.id.tv_rest_tier_hint)?.visibility = View.VISIBLE

            val btnCancel = mRestWindow?.findViewById<Button>(R.id.btn_cancel_emergency)
            val btnConfirm = mRestWindow?.findViewById<Button>(R.id.btn_confirm_calm)
            val etInput = mRestWindow?.findViewById<EditText>(R.id.tv_calm_minutes) // Note: ID is tv_calm_minutes in XML
            etInput?.filters = arrayOf(createOneDecimalInputFilter())
            val btnStopFreeTime = mRestWindow?.findViewById<Button>(R.id.btn_stop_free_time)

            // Calculate Logic
            val now = System.currentTimeMillis()
            val restCoolEnd = CreditManager.getCoolDownEnd(context)
            val tempCoolRemainShow = getTemporaryReuseCooldownRemainingSeconds(now)
            val inAnyCoolingShow = now < restCoolEnd || tempCoolRemainShow > 0
            val tvPenaltyHint = mRestWindow?.findViewById<TextView>(R.id.tv_cooling_penalty_hint)
            val tvQuotaHint = mRestWindow?.findViewById<TextView>(R.id.tv_quota_hint)

            // Populate Quota
            val availMins = CreditManager.getRemainingMinutes(context)
            val borrowableMins = CreditManager.getMaxBorrowableMinutes(context)
            val currentSeg = TimeRuleManager.getCurrentSegment(context)
            val availCount = CreditManager.getRemainingUsableCount(context)
                            val borrowableCount = CreditManager.getMaxBorrowableUsableCount(context)
                            tvQuotaHint?.text = "当前可用: ${formatMinutesForLock(availMins)}min / ${formatUsableCountHalfUnits(availCount)}次 | 可借用: ${formatMinutesForLock(borrowableMins)}min / ${formatUsableCountHalfUnits(borrowableCount)}次"

             if (inAnyCoolingShow) {
                 tvPenaltyHint?.visibility = View.VISIBLE
                 tvPenaltyHint?.text = "⚠️ 冷静期内申请会翻倍扣费（整单时长与次数 ×2）"
                 btnConfirm?.text = "⚠️ 冷却期申请 (翻倍扣费)"
             } else {
                 tvPenaltyHint?.visibility = View.GONE
                 btnConfirm?.text = "🚀 启动休息程序"
             }

             // Bind Actions
             btnCancel?.setOnClickListener {
                 dismissRestWindow()
             }
             
             btnStopFreeTime?.setOnClickListener {
                 val intent = Intent(MonitorService.ACTION_END_FREE_TIME)
                 intent.setPackage(context.packageName)
                 context.sendBroadcast(intent)
                 dismissRestWindow()
             }
             
             btnConfirm?.setOnClickListener {
                 // ... (Copy of logic) ...
                 val minStr = etInput?.text.toString()
                 val requestedMins = minStr.toDoubleOrNull() ?: 0.0
                 if (requestedMins <= 0.0) return@setOnClickListener

                 val inRestCooling = CreditManager.isCooling(context)
                 val inTempCooling = getTemporaryReuseCooldownRemainingSeconds() > 0L
                 val inAnyCooling = inRestCooling || inTempCooling
                 val requestedSec = (requestedMins * 60 + 0.5).toInt()
                 val multiplier = if (inAnyCooling) 2 else 1
                 val overlapSec = if (inAnyCooling) requestedSec else 0
                 val normalSec = if (inAnyCooling) 0 else requestedSec
                 var totalEffectiveSec = requestedSec * multiplier
                 var totalCost = totalEffectiveSec / 60.0
                 
                 val currentSeg = TimeRuleManager.getCurrentSegment(context)
                 
                         // 🚀 [Reward] Last Segment Completion -> Only charge for remaining time in segment * 0.5x Cost
                 // If this is the last segment AND the rest covers the remaining time (End >= SegEnd)
                 if (currentSeg != null) {
                     val currentIndex = TimeRuleManager.getCurrentSegmentIndex(context)
                     if (TimeRuleManager.isLastLogicalSegment(context, currentIndex)) {
                         val cal = java.util.Calendar.getInstance()
                         val nowMins = cal.get(java.util.Calendar.HOUR_OF_DAY) * 60 + cal.get(java.util.Calendar.MINUTE)
                         
                         var segEndMins = currentSeg.endHour * 60 + currentSeg.endMinute
                         val segStartMins = currentSeg.startHour * 60 + currentSeg.startMinute
                         if (segEndMins <= segStartMins) segEndMins += 24 * 60
                         
                         var currentMinsAdjusted = nowMins
                         if (currentMinsAdjusted < segStartMins) currentMinsAdjusted += 24 * 60
                         
                         val restEndMins = currentMinsAdjusted + requestedMins
                         
                         // If rest exceeds segment end (finishing the day)
                         if (restEndMins >= segEndMins) {
                             val timeInSegment = segEndMins - currentMinsAdjusted
                             val chargeableMins = if (timeInSegment > 0) timeInSegment else 0
                             
                             // Recalculate Base Cost using ONLY the time remaining in the segment
                             // 触及冷却期即整单翻倍（2026-09-11 口径）
                             val chargeableSec = (chargeableMins * 60).toInt()
                             val segCoolOverlapSec = overlapSec.coerceAtMost(chargeableSec)
                             val segNormalSec = chargeableSec - segCoolOverlapSec
                             val segTotalEffectiveSec = calculateWeightedQuotaSeconds(segNormalSec, segCoolOverlapSec)
                             val baseCost = segTotalEffectiveSec / 60.0
                             
                             // Apply 0.5x Reward
                             totalCost = calculateTemporaryAccessMinutes((baseCost * 0.5 * 60.0).toInt())
                             totalEffectiveSec = kotlin.math.round(totalCost * 60.0).toInt()
                             
                             Log.i(TAG, "🏆 Last Segment Challenge Completed: Chargeable($chargeableMins) coolOverlapSec=$segCoolOverlapSec baseCost=$baseCost * 0.5 = $totalCost")
                             Toast.makeText(context, "🏆 完成今日任务！仅扣除 $totalCost 分钟 (剩余时段减半)", Toast.LENGTH_SHORT).show()
                         }
                     }
                 }
                 
                 val availableMins = CreditManager.getRemainingMinutes(context)
                 
                if (currentSeg == null) {
                    mMonitorService?.grantFreeTime((requestedMins * 60 * 1000L).toLong(), requestedMins) // Gap Cost = 1x usually? Or just requestedMins.
                    RestStatsRecorder.record(
                        context = context,
                        type = "NORMAL",
                        typeName = "空档期休息",
                        reason = "非规则时段空档期休息",
                        requestedMinutes = requestedMins,
                        actualMinutes = requestedMins,
                        costMinutes = 0.0,
                        costCount = 0.0,
                        balanceMinutes = CreditManager.getRemainingMinutes(context),
                        balanceCount = CreditManager.getRemainingUsableCount(context)
                    )
                    dismissRestWindow()
                    resetConsecutiveNormalLockState("free_time_granted_gap")
                    totalSegmentBlacklistHits = 0
                    dismissLockScreen(LockDismissAuthority.REST)
                    return@setOnClickListener
                }

                 val countCost = calculateQuotaCountFromWeightedSeconds(totalEffectiveSec, requestedSec, QuotaActionType.REST)
                 val currentAvailableSec = kotlin.math.round(availableMins * 60.0).toInt()
                 val currentAvailableCount = CreditManager.getRemainingUsableCount(context)
                 val plan = calculateTemporaryAccessQuotaPlan(
                     totalTimeCostSec = totalEffectiveSec,
                     currentAvailableSec = currentAvailableSec,
                     nextAvailableSec = kotlin.math.round(CreditManager.getMaxBorrowableMinutes(context) * 120.0).toInt(),
                     totalCountCostHalfUnits = countCost,
                     currentAvailableCountHalfUnits = currentAvailableCount,
                     nextAvailableCountHalfUnits = CreditManager.getMaxBorrowableUsableCount(context) * 2
                 )

                 if (!plan.isNextStageAffordable) {
                     Toast.makeText(context, "🚫 下一阶段额度不足，无法借用", Toast.LENGTH_SHORT).show()
                 } else if (inAnyCooling || plan.needsNextStage) {
                     dismissRestWindow()
                     showQuotaTransactionConfirmDialog(
                         context = context,
                         title = if (plan.needsNextStage && inAnyCooling) "冷却借用确认" else if (plan.needsNextStage) "借用额度确认" else "冷却翻倍确认",
                         actionSummary = "申请休息 ${formatQuotaMinutes(requestedSec)} 分钟",
                         requestedSec = requestedSec,
                         freeSec = 0,
                         freeLabel = "空档期",
                         dndSec = requestedSec,
                         coolingSec = overlapSec,
                         plan = plan,
                         confirmText = "确认开启"
                     ) {
                         val charged = if (plan.needsNextStage) CreditManager.applyTemporaryAccessQuotaPlan(context, plan) else true
                         if (charged) performGrant(context, requestedMins, totalCost, plan.needsNextStage, countCost, quotaAlreadyDeducted = plan.needsNextStage)
                     }
                 } else {
                     performGrant(context, requestedMins, totalCost, false, countCost)
                     dismissRestWindow()
                 }
             }

             // Request Focus
             Handler(Looper.getMainLooper()).postDelayed({
                 etInput?.requestFocus()
                 val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager
                 imm?.showSoftInput(etInput, android.view.inputmethod.InputMethodManager.SHOW_FORCED)
             }, 200)

        } catch (e: Exception) {
            Log.e(TAG, "showRestRequestDialog failed", e)
             isInputPanelOpen = false
        }
    }

    private fun showWeeklyPrivilegeDialog(context: Context) {
        val themedContext = android.view.ContextThemeWrapper(context, ThemeManager.themeRes(context))

        if (CreditManager.isWeeklyPrivilegeSessionActive(context)) {
            val remainingMillis = (CreditManager.getFreeTimeEnd(context) - System.currentTimeMillis()).coerceAtLeast(0L)
            val remainingMins = (remainingMillis / 60000L).toInt() + (if (remainingMillis % 60000L > 0) 1 else 0)

            if (CreditManager.isInPrivilegeAdjustmentWindow(context)) {
                val isA11y = isServiceActive()
                val dialogContext = if (isA11y) mCoreService!! else themedContext
                AlertDialog.Builder(dialogContext)
                    .setTitle("特权进行中")
                    .setMessage("当前特权剩余约 $remainingMins 分钟\n启用未超过5分钟，可调整时长")
                    .setPositiveButton("调整时长") { _, _ -> showPrivilegeAdjustDialog(context) }
                    .setNegativeButton("取消", null)
                    .create()
                    .apply {
                        if (isA11y) window?.setType(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY)
                        else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) window?.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
                        else window?.setType(WindowManager.LayoutParams.TYPE_PHONE)
                    }
                    .show()
            } else {
                Toast.makeText(context, "特权进行中（剩余约 $remainingMins 分钟），超过5分钟无法调整", Toast.LENGTH_SHORT).show()
            }
            return
        }

        // 只在真正不可用时拒绝（进行中/今日已用/次数耗尽/时段外）
        // 时长耗尽或时间不足时，让用户看到截断后的可用时长
        val privilegeReason = CreditManager.getWeeklyPrivilegeUnavailableReason(context)
        if (privilegeReason != null && privilegeReason != "本周时长耗尽" && privilegeReason != "时间不足") {
            recordPrivilegeRejectedLifecycle("UNAVAILABLE_${privilegeReason}")
            Toast.makeText(context, "特权不可用：$privilegeReason", Toast.LENGTH_SHORT).show()
            return
        }

        val count = CreditManager.getWeeklyPrivilegeCount(context)
        val limit = CreditManager.getWeeklyPrivilegeLimit(context)
        val usedDur = CreditManager.getWeeklyPrivilegeDurationUsed(context)
        val limitDur = CreditManager.getWeeklyPrivilegeDurationLimit(context)
        val remainingDur = (limitDur - usedDur).coerceAtLeast(0)

        val isA11y = isServiceActive()
        val baseCtx = if (isA11y) mCoreService!! else context
        val dialogContext = android.view.ContextThemeWrapper(baseCtx, ThemeManager.themeRes(baseCtx))

        val dialog = AlertDialog.Builder(dialogContext)
            .setTitle("使用每周特权")
            .setMessage(
                "确定要使用每周特权吗？\n\n" +
                    "• 每日仅可启用一次\n" +
                    "• 你将选择一段【特权时长】，在该时长内进入【无限制】\n" +
                    "• 若跨越 24:00 将自动结束（夜间保护）\n" +
                    "• 特权进行中不可重复启用\n" +
                    "• 本周剩余机会: ${limit - count} 次，剩余时长: ${remainingDur} 分钟"
            )
            .setPositiveButton("确认") { _, _ ->
                if (remainingDur <= 0) {
                    recordPrivilegeRejectedLifecycle("QUOTA_INSUFFICIENT")
                    Toast.makeText(context, "本周特权时长已耗尽", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                val view = LayoutInflater.from(dialogContext).inflate(R.layout.dialog_privilege_time_picker, null)
                val npHour = view.findViewById<android.widget.NumberPicker>(R.id.np_hour)
                val npMinute = view.findViewById<android.widget.NumberPicker>(R.id.np_minute)

                npHour.minValue = 0
                npHour.maxValue = (remainingDur / 60).coerceAtLeast(0)
                npHour.value = 0

                npMinute.minValue = 0
                npMinute.maxValue = 59
                npMinute.value = 0
                npMinute.setFormatter { i -> String.format(java.util.Locale.US, "%02d", i) }

                val pickerDialog = AlertDialog.Builder(dialogContext)
                    .setTitle("选择特权时长")
                    .setMessage("本周剩余 $remainingDur 分钟。请选择本次要使用的时长（超过可用时段将自动截断），确认后消耗 1 次特权。")
                    .setView(view)
                    .setPositiveButton("确认启动", null)
                    .setNegativeButton("取消", null)
                    .create()

                pickerDialog.setOnShowListener {
                    pickerDialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        val totalMins = npHour.value * 60 + npMinute.value
                        if (totalMins <= 0) {
                            recordPrivilegeRejectedLifecycle("INVALID_DURATION")
                            Toast.makeText(context, "请先选择特权时长", Toast.LENGTH_SHORT).show()
                            return@setOnClickListener
                        }

                        if (!HomeFragment.isWeeklyPrivilegeRequestedDurationInRange(totalMins, remainingDur)) {
                            Toast.makeText(context, "本周剩余特权时长只有 $remainingDur 分钟", Toast.LENGTH_SHORT).show()
                            return@setOnClickListener
                        }

                        if (CreditManager.isWeeklyPrivilegeSessionActive(context)) {
                            recordPrivilegeRejectedLifecycle("ALREADY_ACTIVE")
                            Toast.makeText(context, "特权进行中，无法重复启用", Toast.LENGTH_SHORT).show()
                            return@setOnClickListener
                        }

                        val effective = CreditManager.calculateWeeklyPrivilegeEffectiveMinutes(context, totalMins)
                        if (effective <= 0) {
                            recordPrivilegeRejectedLifecycle("WINDOW_UNAVAILABLE")
                            val reason = CreditManager.getWeeklyPrivilegeUnavailableReason(context) ?: "当前时段无法使用特权（不在可用时间范围内）"
                            Toast.makeText(context, "启用失败：$reason", Toast.LENGTH_SHORT).show()
                            return@setOnClickListener
                        }

                        val clamp = HomeFragment.clampRequestedToEffectiveMinutes(totalMins, effective)
                        if (clamp.didClamp) {
                            val windowEnd = TimeRuleManager.getPrivilegeWindowEnd(context)
                            val boundaryLabel = HomeFragment.buildPrivilegeBoundaryLabel(windowEnd)
                            Toast.makeText(
                                context,
                                HomeFragment.buildPrivilegeAutoClampToast(clamp.actualMinutes, boundaryLabel),
                                Toast.LENGTH_LONG
                            ).show()
                        }

                        val ok = CreditManager.startWeeklyPrivilege(context, clamp.actualMinutes)
                        if (ok) {
                            val intent = Intent(MonitorService.ACTION_REFRESH_RULES)
                            context.sendBroadcast(intent.setPackage(context.packageName))
                            Toast.makeText(context, "每周特权已启用（${clamp.actualMinutes} 分钟）", Toast.LENGTH_SHORT).show()

                            mDismissUntil = System.currentTimeMillis() + 3000L
                            resetConsecutiveNormalLockState("weekly_privilege_granted")
                            totalSegmentBlacklistHits = 0

                            dismissLockScreen(LockDismissAuthority.WEEKLY_PRIVILEGE)
                            pickerDialog.dismiss()
                        } else {
                            val reason = CreditManager.getWeeklyPrivilegeUnavailableReason(context) ?: "条件不足"
                            Toast.makeText(context, "启用失败：$reason", Toast.LENGTH_SHORT).show()
                        }
                    }
                }

                if (isA11y) {
                    pickerDialog.window?.setType(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY)
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    pickerDialog.window?.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
                } else {
                    pickerDialog.window?.setType(WindowManager.LayoutParams.TYPE_PHONE)
                }

                pickerDialog.show()
                pickerDialog.applyEscapeDisciplineColors()
            }
            .setNegativeButton("取消", null)
            .create()

        if (isA11y) {
            dialog.window?.setType(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            dialog.window?.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
        } else {
            dialog.window?.setType(WindowManager.LayoutParams.TYPE_PHONE)
        }

        dialog.show()
        dialog.applyEscapeDisciplineColors()
    }

    private fun dismissRestWindow() {
        if (mRestWindow == null) return
        try {
            mWindowManager?.removeView(mRestWindow)
        } catch (e: Exception) {
            Log.e(TAG, "dismissRestWindow failed", e)
        } finally {
            mRestWindow = null
            isInputPanelOpen = false
            updateLockViewUI() // Restore buttons
        }
    }

    private fun showTemporaryAccessDialog(context: Context) {
        // 🚀 改用全屏窗口（与申请休息同模式），保证置顶，且支持内嵌借用确认
        showTempAccessWindow(context)
    }

    private fun showTempAccessWindow(context: Context) {
        if (mRestWindow != null) return
        if (isTemporaryAccessQuotaExhausted(context)) {
            Toast.makeText(context, "今日临时解锁额度已用完，无法借用", Toast.LENGTH_SHORT).show()
            return
        }
        val maxSeconds = getTemporaryAccessMaxSeconds(context)

        try {
            val themedContext = android.view.ContextThemeWrapper(context, ThemeManager.themeRes(context))
            val inflater = LayoutInflater.from(themedContext)
            mRestWindow = inflater.inflate(R.layout.window_rest_request, null)

            val isA11y = mCoreService != null && LockWindowManager.isServiceActive()
            val targetWM = if (isA11y) {
                mCoreService!!.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            } else {
                mWindowManager ?: context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            }

            val params = WindowManager.LayoutParams().apply {
                width = WindowManager.LayoutParams.MATCH_PARENT
                height = WindowManager.LayoutParams.MATCH_PARENT
                format = PixelFormat.TRANSLUCENT
                type = if (isA11y) {
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                } else {
                    WindowManager.LayoutParams.TYPE_PHONE
                }
                flags = WindowManager.LayoutParams.FLAG_DIM_BEHIND
                dimAmount = 0.6f
                softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or
                                WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE
            }

            targetWM.addView(mRestWindow, params)
            mActiveRestWindowManager = targetWM
            isInputPanelOpen = true

            // Bind views and customize for temp unlock
            val tvTitle = mRestWindow?.findViewById<TextView>(R.id.tv_title)
            tvTitle?.text = "⚡ 临时解锁 (短期解开任意锁)"
            mRestWindow?.findViewById<View>(R.id.tv_rest_tier_hint)?.visibility = View.GONE

            val tvQuotaHint = mRestWindow?.findViewById<TextView>(R.id.tv_quota_hint)
            val currentSegment = TimeRuleManager.getCurrentSegment(context)
            val isInDndSegment = currentSegment != null
            val isInRest = CreditManager.isFreeTime(context)

            val availMins = CreditManager.getRemainingMinutes(context)
            val borrowableMins = CreditManager.getMaxBorrowableMinutes(context)
            val availCount = CreditManager.getRemainingUsableCount(context)
            val borrowableCount = CreditManager.getMaxBorrowableUsableCount(context)

            val quotaPresentation = resolveTemporaryAccessQuotaPresentation(
                isInDndSegment = isInDndSegment,
                isInRest = isInRest,
                availMinsFormatted = formatMinutesForLock(availMins),
                availCountFormatted = formatUsableCountHalfUnits(availCount),
                borrowableMinsFormatted = formatMinutesForLock(borrowableMins),
                borrowableCountFormatted = formatUsableCountHalfUnits(borrowableCount)
            )
            tvQuotaHint?.text = quotaPresentation.hintText
            tvQuotaHint?.setTextColor(android.graphics.Color.parseColor(quotaPresentation.hintColorHex))

            val etInput = mRestWindow?.findViewById<EditText>(R.id.tv_calm_minutes)
            val maxMinutes = maxSeconds / 60.0
            val maxMinutesText = formatTemporaryAccessSecondsAsMinutes(maxSeconds)
            etInput?.hint = "输入分钟(0.1-$maxMinutesText)"
            etInput?.inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
            etInput?.filters = arrayOf(createOneDecimalInputFilter())

            val tilInput = mRestWindow?.findViewById<com.google.android.material.textfield.TextInputLayout>(R.id.til_input)
            tilInput?.hint = "输入分钟(0.1-$maxMinutesText)"

            val tvCoolingHint = mRestWindow?.findViewById<TextView>(R.id.tv_cooling_penalty_hint)
            val tvDeductionDetail = mRestWindow?.findViewById<TextView>(R.id.tv_deduction_detail)
            tvDeductionDetail?.visibility = View.VISIBLE

            val btnCancel = mRestWindow?.findViewById<Button>(R.id.btn_cancel_emergency)
            btnCancel?.text = "取消"
            btnCancel?.setOnClickListener { dismissRestWindow() }

            val btnConfirm = mRestWindow?.findViewById<Button>(R.id.btn_confirm_calm)
            btnConfirm?.text = "确认使用"

            val btnStopFreeTime = mRestWindow?.findViewById<Button>(R.id.btn_stop_free_time)
            btnStopFreeTime?.visibility = View.GONE

            // Real-time deduction preview as user types
            fun updateDeductionPreview() {
                val rawMinutes = etInput?.text?.toString()?.trim()
                if (!isTemporaryAccessMinuteInputValid(rawMinutes, maxMinutes)) {
                    if (quotaPresentation.isFreeScenario) {
                        tvDeductionDetail?.text = quotaPresentation.defaultDetailText
                        tvDeductionDetail?.setTextColor(android.graphics.Color.parseColor(quotaPresentation.hintColorHex))
                        tvDeductionDetail?.visibility = View.VISIBLE
                    } else {
                        tvDeductionDetail?.text = ""
                        tvDeductionDetail?.visibility = View.GONE
                    }
                    tvCoolingHint?.visibility = View.GONE
                    return
                }
                val sec = temporaryAccessMinutesToSeconds(rawMinutes!!.toDouble())

                val now = System.currentTimeMillis()

                // Calculate free segment seconds
                val freeSec: Int
                if (isInRest) {
                    val freeEndMs = CreditManager.getFreeTimeEnd(context)
                    freeSec = minOf(sec, maxOf(0, ((freeEndMs - now + 999) / 1000).toInt()))
                } else if (!isInDndSegment) {
                    val nextSeg = TimeRuleManager.getNextSegment(context)
                    val nextStartMs = if (nextSeg != null) {
                        val cal = java.util.Calendar.getInstance()
                        cal.set(java.util.Calendar.HOUR_OF_DAY, nextSeg.startHour)
                        cal.set(java.util.Calendar.MINUTE, nextSeg.startMinute)
                        cal.set(java.util.Calendar.SECOND, 0)
                        cal.set(java.util.Calendar.MILLISECOND, 0)
                        // 🛡️ [Fix] 跨天处理：如果计算出的时间早于当前时间，加一天
                        if (cal.timeInMillis < now) {
                            cal.add(java.util.Calendar.DAY_OF_YEAR, 1)
                        }
                        cal.timeInMillis
                    } else {
                        now + sec * 1000L
                    }
                    freeSec = minOf(sec, maxOf(0, ((nextStartMs - now + 999) / 1000).toInt()))
                } else {
                    freeSec = 0
                }

                val dndSec = sec - freeSec  // paid segment

                if (dndSec <= 0) {
                    // All free
                    val freeReason = if (isInRest) "休息时段" else "自由空档期"
                    tvDeductionDetail?.text = "💡 当前处于${freeReason}，本次临时解锁免费（不扣时间与次数，不计入预支）"
                    tvDeductionDetail?.setTextColor(ThemeManager.getColorAttr(context, R.attr.zzSuccess))
                    tvDeductionDetail?.visibility = View.VISIBLE
                    tvCoolingHint?.visibility = View.GONE
                    return
                }

                tvDeductionDetail?.setTextColor(ThemeManager.getColorAttr(context, R.attr.zzText))
                tvDeductionDetail?.visibility = View.VISIBLE

                // Cooling overlap calculation: 使用请求开始前已存在的冷却快照，与结算完全同源
                val reuseRemain = getTemporaryReuseCooldownRemainingSeconds(now)
                val existingCooldownEndMs = maxOf(
                    CreditManager.getCoolDownEnd(context),
                    if (reuseRemain > 0L) now + reuseRemain * 1000L else 0L
                )
                val coolingAnchorMs = if (isInRest) CreditManager.getFreeTimeEnd(context) else now
                val chargeSnapshot = resolveTemporaryAccessChargeSnapshot(
                    requestedSec = sec,
                    freeSec = freeSec,
                    coolingAnchorMs = coolingAnchorMs,
                    existingCooldownEndMs = existingCooldownEndMs
                )
                val coolOverlapSec = chargeSnapshot.coolingSec
                val totalTimeCost = chargeSnapshot.weightedTimeCostSec
                val countCost = chargeSnapshot.weightedCountHalfUnits

                val isCoolingActive = chargeSnapshot.coolingSec > 0
                val sb = StringBuilder("扣除详情：\n")
                if (freeSec > 0) sb.append("• 免费段: ${formatTemporaryAccessSecondsAsMinutes(freeSec)}分钟（不扣时间/次数）\n")
                if (dndSec > 0) {
                    sb.append("• 付费段: ${formatTemporaryAccessSecondsAsMinutes(dndSec)}分钟")
                    if (isCoolingActive) {
                        sb.append("（冷却期申请 ×2 整单翻倍）")
                    }
                    sb.append("\n• 时间消耗: ${formatTemporaryAccessSecondsAsMinutes(totalTimeCost)}分钟")
                    sb.append("\n• 次数消耗: ${formatUsableCountHalfUnits(countCost)}次")
                }

                val remainingSecs = (kotlin.math.round(CreditManager.getRemainingMinutes(context) * 60)).toInt()
                if (remainingSecs < totalTimeCost) {
                    sb.append("\n⚠️ 时间额度不足，需借用")
                }
                val countRemaining = CreditManager.getRemainingUsableCount(context)
                if (countRemaining < countCost && countCost > 0) {
                    val countCanBorrow = CreditManager.canBorrowCountFromNext(context, countCost)
                    if (countCanBorrow) {
                        sb.append("\n⚠️ 次数不足，需借用（×2惩罚）")
                    } else {
                        sb.append("\n⚠️ 次数不足且无法借用")
                    }
                }

                tvDeductionDetail?.text = sb.toString()

                if (isCoolingActive) {
                    tvCoolingHint?.visibility = View.VISIBLE
                    tvCoolingHint?.text = "⚠️ 处于冷却期内申请，时间与次数整单直接翻倍（×2）"
                } else {
                    tvCoolingHint?.visibility = View.GONE
                }
            }

            etInput?.addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { updateDeductionPreview() }
                override fun afterTextChanged(s: android.text.Editable?) {}
            })
            updateDeductionPreview()

            // Confirm: 统一交给 startTemporaryAccess 做额度预检、冷却加权确认和扣费
            btnConfirm?.setOnClickListener {
                val minuteStr = etInput?.text?.toString()?.trim()
                if (!isTemporaryAccessMinuteInputValid(minuteStr, maxMinutes)) {
                    Toast.makeText(context, "请输入有效分钟数(0.1-$maxMinutesText)", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                val sec = temporaryAccessMinutesToSeconds(minuteStr!!.toDouble())
                dismissRestWindow()
                startTemporaryAccess(context, sec, maxSeconds)
            }

            // Request focus for keyboard
            etInput?.post {
                etInput.requestFocus()
                val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager
                imm?.showSoftInput(etInput, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
            }
        } catch (e: Exception) {
            Log.e(TAG, "showTempAccessWindow failed", e)
            dismissRestWindow()
        }
    }

    private fun showTemporaryAccessHelpDialog(context: Context) {
        val isA11y = isServiceActive()
        val dialogContext = if (isA11y) mCoreService!! else context
        val maxSeconds = getTemporaryAccessMaxSeconds(context)
        val isCooling = CreditManager.isCooling(context)
        val coolMsg = if (isCooling) {
            "\n\n⚠️ 当前处于休息冷却期：本次申请的时间与次数整单翻倍（×2）。\n" +
                "例如：申请 0.5 分钟 → 计费 1.0 分钟；申请 1.0 分钟 → 计费 2.0 分钟。"
        } else ""
        val dialog = AlertDialog.Builder(dialogContext)
            .setTitle("临时解锁使用说明")
            .setMessage(
                "可临时解锁 1~$maxSeconds 秒。\n" +
                    "结束后不再强制锁机；冷却规则按时长分段递增：1分钟内按1倍，2分钟内按2倍，3分钟内按3倍。比如放行3分钟，冷却就是6分钟。二级临时总次数按本轮初始冷却秒数÷60四舍五入后固定，仅在实际使用10秒二级临时时扣减，且两次至少间隔60秒。" +
                    coolMsg
            )
            .setPositiveButton("我知道了", null)
            .create()

        if (isA11y) {
            dialog.window?.setType(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            dialog.window?.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
        } else {
            dialog.window?.setType(WindowManager.LayoutParams.TYPE_PHONE)
        }
        dialog.show()
    }

    /**
     * 临时解锁额度扣除确认弹窗。
     * 仅在勿扰时段内且将扣除休息额度时显示。
     */
    private fun showTemporaryAccessCreditConfirmDialog(
        context: Context,
        rawSeconds: Int,
        maxSeconds: Int,
        minutesToDeduct: Double
    ) {
        val isA11y = isServiceActive()
        val dialogContext = if (isA11y) mCoreService!! else context
        val remainingMins = CreditManager.getRemainingMinutes(context)

        val message = buildString {
            append("⚠️ 勿扰时段内使用临时解锁将消耗休息额度\n\n")
            append("• 临时解锁时长: ${rawSeconds} 秒\n")
            append("• 需扣除额度: ${formatMinutesForLock(minutesToDeduct)} 分钟\n")
            append("• 当前可用额度: ${formatMinutesForLock(remainingMins)} 分钟\n")
            if (remainingMins < minutesToDeduct) {
                append("\n⚠️ 额度不足，将自动借用下时段额度（借1分钟扣2分钟）\n")
            }
            append("\n确定要使用临时解锁吗？")
        }

        val dialog = AlertDialog.Builder(dialogContext)
            .setTitle("临时解锁确认")
            .setMessage(message)
            .setPositiveButton("确认") { _, _ ->
                startTemporaryAccess(context, rawSeconds, maxSeconds)
            }
            .setNegativeButton("取消", null)
            .create()

        if (isA11y) {
            dialog.window?.setType(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            dialog.window?.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
        } else {
            dialog.window?.setType(WindowManager.LayoutParams.TYPE_PHONE)
        }
        dialog.window?.clearFlags(
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM
        )

        dialog.show()
        dialog.applyEscapeDisciplineColors()
    }

    /**
     * 二级应急 10 秒确认弹窗。
     */
    private fun showEmergencyTenConfirmDialog(
        context: Context,
        remainingUses: Int,
        maxSeconds: Int
    ) {
        val isA11y = isServiceActive()
        val dialogContext = if (isA11y) mCoreService!! else context

        val message = buildString {
            append("⚠️ 二级应急（10 秒临时解锁）\n\n")
            append("• 用途：应急扫码、快速查看等场景\n")
            append("• 本次剩余可使用次数: ${remainingUses} 次\n")
            append("• 10秒内不消耗休息额度\n")
            append("• 使用后需等待 60 秒才能再次使用\n\n")
            append("确定要使用二级应急吗？")
        }

        val dialog = AlertDialog.Builder(dialogContext)
            .setTitle("二级应急确认")
            .setMessage(message)
            .setPositiveButton("确认") { _, _ ->
                startTemporaryAccess(context, TEMP_ACCESS_COOLDOWN_FREE_SECONDS, maxSeconds)
            }
            .setNegativeButton("取消", null)
            .create()

        if (isA11y) {
            dialog.window?.setType(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            dialog.window?.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
        } else {
            dialog.window?.setType(WindowManager.LayoutParams.TYPE_PHONE)
        }
        dialog.window?.clearFlags(
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM
        )

        dialog.show()
        dialog.applyEscapeDisciplineColors()
    }

    private fun startTemporaryAccess(
        context: Context,
        rawSeconds: Int,
        maxSeconds: Int = getTemporaryAccessMaxSeconds(context),
        skipBorrowDialog: Boolean = false,
        quotaAlreadyDeducted: Boolean = false,
        prechargedPlan: TemporaryAccessQuotaPlan? = null
    ) {
        val now = System.currentTimeMillis()
        val seconds = normalizeTemporaryAccessSeconds(rawSeconds, maxSeconds)
        var currentChargeSecondsForSession = prechargedPlan?.currentTimeCostSec ?: 0
        var nextChargeSecondsForSession = prechargedPlan?.nextTimeCostSec ?: 0
        var borrowChargeSecondsForSession = 0
        clearTemporaryAccessChargeReceipt()
        // ⏱️ 新的临时解锁开始，清空桌面累计计时器
        resetDesktopStreak()
        temporaryAccessLastNonLauncherPkg = null
        temporaryAccessLastDetectedPkg = null
        emergencyStartPackage = null

        val currentSegment = TimeRuleManager.getCurrentSegment(context)
        val isInDndSegment = currentSegment != null // 在勿扰时段（非空档期）
        // 🆕 免扣检测：休息时段和空档期不扣时间与次数（spec §免扣场景）
        val isInFreeTime = CreditManager.isFreeTime(context)
        val isInGap = currentSegment == null

        // 🔍 [BORROW_DIAG] 入口日志
        val diagBorrowedBefore = if (isInDndSegment) CreditManager.getBorrowedMinutesFromNext(context) else -1.0
        val diagRemainingBefore = if (isInDndSegment) CreditManager.getRemainingMinutes(context) else -1.0
        Log.d(TAG, "startTemporaryAccess[DIAG] ENTRY rawSeconds=$rawSeconds seconds=$seconds skipBorrowDialog=$skipBorrowDialog currentSegment=${currentSegment?.name ?: "null"}(${currentSegment?.formatTimeRange() ?: "N/A"}) isInDndSegment=$isInDndSegment borrowedBefore=${diagBorrowedBefore}min remainingBefore=${diagRemainingBefore}min")

        val reuseRemain = getTemporaryReuseCooldownRemainingSeconds(now)
        val emergencyRemainingUses = resolveTemporaryEmergencyRemainingUses(
            reuseRemainSeconds = reuseRemain,
            fixedRemainingUses = temporaryEmergencyRemainingUsesInCooldown
        )
        val emergencyGapRemain = resolveTemporaryEmergencyReuseGapRemainingSeconds(
            temporaryEmergencyLastUseAtInCooldown,
            now
        )
        val isEmergencyInCooldown = isEmergencyTemporaryAccessDuringCooldown(reuseRemain, seconds)

        val existingCooldownEndMs = maxOf(
            CreditManager.getCoolDownEnd(context),
            if (reuseRemain > 0L) now + reuseRemain * 1000L else 0L
        )
        val freeSec: Int = if (isInFreeTime) {
            val freeEndMs = CreditManager.getFreeTimeEnd(context)
            minOf(seconds, maxOf(0, ((freeEndMs - now + 999) / 1000).toInt()))
        } else if (isInGap) {
            seconds
        } else {
            0
        }
        val coolingAnchorMs = if (isInFreeTime) CreditManager.getFreeTimeEnd(context) else now
        val chargeSnapshot = resolveTemporaryAccessChargeSnapshot(
            requestedSec = seconds,
            freeSec = freeSec,
            coolingAnchorMs = coolingAnchorMs,
            existingCooldownEndMs = existingCooldownEndMs
        )

        val coolOverlapSec = chargeSnapshot.coolingSec
        val effectiveSeconds = chargeSnapshot.weightedTimeCostSec
        val countCost = chargeSnapshot.weightedCountHalfUnits

        // 二级应急（10s 冷却中）不扣额度，无需检查休息额度
        if (isInDndSegment && !isEmergencyInCooldown) {
            val remainingSecs = kotlin.math.round(CreditManager.getRemainingMinutes(context) * 60).toInt()
            Log.d(TAG, "startTemporaryAccess[DIAG] BORROW_CHECK seconds=$seconds freeSec=$freeSec dndSec=${chargeSnapshot.dndSec} coolOverlapSec=$coolOverlapSec effectiveSeconds=$effectiveSeconds remainingSecs=$remainingSecs needBorrow=${remainingSecs < effectiveSeconds}")

            if (!quotaAlreadyDeducted && effectiveSeconds > 0) {
                val countRemaining = CreditManager.getRemainingUsableCount(context)
                val effCountLimit = currentSegment.getEffectiveUsableCountLimit()
                if (effCountLimit == 0) {
                    rejectTemporaryAccessLifecycle("QUOTA_INSUFFICIENT")
                    Toast.makeText(context, "当前时段禁止临时解锁", Toast.LENGTH_SHORT).show()
                    return
                }

                val nextTimeCapacitySec = kotlin.math.round(
                    CreditManager.getMaxBorrowableMinutes(context) * 60.0 * 2.0
                ).toInt()
                val nextCountCapacity = CreditManager.getMaxBorrowableUsableCount(context) * 2
                val quotaPlan = calculateTemporaryAccessQuotaPlan(
                    totalTimeCostSec = effectiveSeconds,
                    currentAvailableSec = remainingSecs,
                    nextAvailableSec = nextTimeCapacitySec,
                    totalCountCostHalfUnits = countCost,
                    currentAvailableCountHalfUnits = countRemaining,
                    nextAvailableCountHalfUnits = nextCountCapacity
                )

                val touchesCooling = coolOverlapSec > 0
                if (!quotaPlan.isNextStageAffordable) {
                    rejectTemporaryAccessLifecycle("QUOTA_INSUFFICIENT")
                    showTemporaryAccessQuotaBlockedDialog(context, quotaPlan, touchesCooling)
                    return
                }

                if ((touchesCooling || quotaPlan.needsNextStage) && !skipBorrowDialog) {
                    showTemporaryAccessQuotaConfirmDialog(
                        context = context,
                        rawSeconds = rawSeconds,
                        maxSeconds = maxSeconds,
                        plan = quotaPlan,
                        freeSec = freeSec,
                        dndSec = chargeSnapshot.dndSec,
                        coolingSec = coolOverlapSec,
                        freeLabel = if (isInFreeTime) "休息时段" else "空档期"
                    )
                    return
                }
            }
        }
        if (isEmergencyInCooldown && !canUseTemporaryEmergencyDuringCooldown(
                reuseRemain,
                temporaryEmergencyLastUseAtInCooldown,
                now,
                seconds,
                remainingUsesInCooldown = emergencyRemainingUses
            )) {
            if (emergencyGapRemain > 0L) {
                rejectTemporaryAccessLifecycle("COOLDOWN_ACTIVE", now + reuseRemain * 1000L)
                Toast.makeText(
                    context,
                    "二级临时每次10秒结束后需再等${TEMP_ACCESS_EMERGENCY_REUSE_GAP_SECONDS}秒，请再等${emergencyGapRemain}s",
                    Toast.LENGTH_SHORT
                ).show()
                return
            }
            Toast.makeText(
                context,
                if (emergencyRemainingUses <= 0) {
                    "主冷却剩余不足60秒，当前不可申请二级临时"
                } else {
                    "冷却中二级临时每60秒仅可申请1次"
                },
                Toast.LENGTH_SHORT
            ).show()
            rejectTemporaryAccessLifecycle("COOLDOWN_ACTIVE", now + reuseRemain * 1000L)
            return
        }

        val activeCooldownUntil = if (reuseRemain > 0L) now + reuseRemain * 1000L else 0L

        // 二级应急：10s 临时解锁，若未切换 App 则到点自动锁屏
        // 期间和普通临时解锁一样可自由访问黑名单 App
        if (isEmergencyInCooldown) {
            val emergencyAccessUntil = now + seconds * 1000L
            temporaryAccessUntil = emergencyAccessUntil
            temporaryAccessSeconds = seconds
            temporaryAccessStartAt = now
            temporaryAccessChargedMinutes = 0.0
            clearTemporaryAccessChargeReceipt()
            // 二级应急解锁不延长一级冷却：闹钟挑战本身就是代价，冷却保持原有结束时间
            temporaryReuseCooldownUntil = activeCooldownUntil
            temporaryAccessExpiredAt = 0L
            temporaryAccessTargetPackageName = lastBlockedPackageName
            mDismissUntil = emergencyAccessUntil

            // 记录启动时的前台 App，供 10s 后判断是否切换
            emergencyStartPackage = lastBlockedPackageName

            // 更新应急冷却状态：记录本次使用时间、扣减剩余次数
            temporaryEmergencyLastUseAtInCooldown = emergencyAccessUntil
            temporaryEmergencyRemainingUsesInCooldown =
                (temporaryEmergencyRemainingUsesInCooldown - 1).coerceAtLeast(0)
            beginTemporaryAccessLifecycle(seconds, emergency = true)
            saveTemporaryAccessState()

            LogExporter.logEvent(
                "TemporaryAccess",
                "[EMERGENCY] 10s free access, startPkg=$emergencyStartPackage, cooldownUntil=$temporaryReuseCooldownUntil, emergencyLastUse=$temporaryEmergencyLastUseAtInCooldown, emergencyRemainingUses=$temporaryEmergencyRemainingUsesInCooldown, targetPkg=${temporaryAccessTargetPackageName ?: "N/A"}"
            )

            // 调度 10s 后检查：未切换 App 则强制锁屏
            scheduleEmergencyRelock(context, emergencyAccessUntil)

            // 共用：解锁并重置状态
            resetConsecutiveNormalLockState("temporary_access_started")
            totalSegmentBlacklistHits = 0
            Toast.makeText(context, "已临时解锁10s，若未切换App则到时自动锁定", Toast.LENGTH_SHORT).show()

            dismissLockScreen(LockDismissAuthority.TEMPORARY_ACCESS)
            return
        }
        val accessUntil = now + seconds * 1000L
        val globalCoolSec = TimeRuleManager.getCoolDownRestSeconds(context).let { if (it > 0) it else 60 }
        val newCooldownUntil = accessUntil + calculateTemporaryReuseCooldownMs(seconds, maxSeconds, globalCoolSec)
        val cooldownUntil = maxOf(activeCooldownUntil, newCooldownUntil)
        val cooldownAfterAccessRemainMs = (cooldownUntil - accessUntil).coerceAtLeast(0L)
        val cooldownSeconds = (cooldownAfterAccessRemainMs + 999L) / 1000L
        temporaryAccessUntil = accessUntil
        temporaryAccessSeconds = seconds
        temporaryAccessStartAt = now
        temporaryAccessChargedMinutes =
            (currentChargeSecondsForSession + nextChargeSecondsForSession) / 60.0
        temporaryReuseCooldownUntil = cooldownUntil
        // 🚀 同步更新全局休息冷静期结束时间，确保临时解锁后与休息冷却完全互通统一
        CreditManager.setCoolDownEnd(context, cooldownUntil)
        temporaryAccessExpiredAt = 0L
        temporaryAccessTargetPackageName = lastBlockedPackageName
        if (cooldownUntil <= now) {
            temporaryEmergencyLastUseAtInCooldown = 0L
            temporaryEmergencyRemainingUsesInCooldown = 0
        } else if (isEmergencyInCooldown) {
            // Secondary unlock gap is measured after the 10s access ends.
            temporaryEmergencyLastUseAtInCooldown = accessUntil
            temporaryEmergencyRemainingUsesInCooldown =
                (temporaryEmergencyRemainingUsesInCooldown - 1).coerceAtLeast(0)
        } else if (reuseRemain <= 0L) {
            temporaryEmergencyLastUseAtInCooldown = 0L
            temporaryEmergencyRemainingUsesInCooldown =
                calculateTemporaryEmergencyBudgetTotal(cooldownSeconds)
        }
        mDismissUntil = accessUntil
        saveTemporaryAccessState()
        scheduleTemporaryAccessExpiryIfNeeded()

        // 临时解锁 legacy lifecycle line retired; TEMP_ACCESS_START normal event already records the outcome.

        // [勿扰时段扣除休息额度] 在勿扰时段临时解锁需要扣除休息额度
        // 空档期不扣额度（空档期本来就是休息的），二级应急（10s冷却中）已提前返回
        // 🆕 免扣场景：休息中也不扣时间（spec §免扣场景）
        // 🛡️ [Finding 1 修复] 结算直接消费预检确定的 chargeSnapshot，严禁重新读取已被本次修改的冷却状态！
        if (isInDndSegment && !quotaAlreadyDeducted) {
            val dndSec = chargeSnapshot.dndSec
            if (dndSec > 0) {
                val effectiveMinutes = chargeSnapshot.weightedTimeCostSec / 60.0
                val remainingMins = CreditManager.getRemainingMinutes(context)

                Log.d(TAG, "startTemporaryAccess[DEDUCT] seconds=$seconds dndSec=$dndSec freeSec=${chargeSnapshot.freeSec} effectiveMinutes=$effectiveMinutes remainingMins=$remainingMins coolingSec=${chargeSnapshot.coolingSec}")

                if (remainingMins >= effectiveMinutes) {
                    currentChargeSecondsForSession = chargeSnapshot.weightedTimeCostSec
                    nextChargeSecondsForSession = 0
                    borrowChargeSecondsForSession = 0
                    Log.d(TAG, "startTemporaryAccess[DEDUCT] consuming $effectiveMinutes minutes (sufficient)")
                    CreditManager.consumeMinutes(context, effectiveMinutes)
                } else {
                    currentChargeSecondsForSession = kotlin.math.round(remainingMins * 60.0).toInt()
                    Log.d(TAG, "startTemporaryAccess[DEDUCT] insufficient! consuming $remainingMins, borrowing ${effectiveMinutes - remainingMins}")
                    if (remainingMins > 0) {
                        CreditManager.consumeMinutes(context, remainingMins)
                    }
                    val borrowMins = effectiveMinutes - remainingMins
                    nextChargeSecondsForSession = kotlin.math.round(borrowMins * 120.0).toInt()
                    borrowChargeSecondsForSession = nextChargeSecondsForSession
                    CreditManager.borrowFromNext(context, borrowMins)
                    Log.d(TAG, "startTemporaryAccess[DEDUCT] after borrow: borrowMins=$borrowMins penalty=${borrowMins * 2}")
                }
            } else {
                Log.d(TAG, "startTemporaryAccess[DEDUCT] SKIP: dndSec=$dndSec freeSec=${chargeSnapshot.freeSec} (all free, no deduction)")
            }
        } else {
            Log.d(TAG, "startTemporaryAccess[DEDUCT] SKIP: isInDndSegment=false (gap period, no deduction)")
        }

        // 🆕 消耗可用次数（以不可变 chargeSnapshot 结果为唯一事实）
        if (
            shouldCommitTemporaryAccessCount(
                quotaAlreadyDeducted = quotaAlreadyDeducted,
                isInDndSegment = isInDndSegment,
                weightedCountHalfUnits = chargeSnapshot.weightedCountHalfUnits
            )
        ) {
            val countCostHalfUnits = chargeSnapshot.weightedCountHalfUnits
            val countRemaining = CreditManager.getRemainingUsableCount(context) // halfUnits
            if (countRemaining < countCostHalfUnits) {
                val deficitHalfUnits = countCostHalfUnits - countRemaining
                CreditManager.borrowCountFromNext(context, deficitHalfUnits)
            }
            CreditManager.consumeUsableCountWeighted(context, countCostHalfUnits)
            Log.i(
                TAG,
                "startTemporaryAccess: consumed $countCostHalfUnits halfUnits (${"%.1f".format(countCostHalfUnits / 2.0)}次), " +
                        "coolingSec=${chargeSnapshot.coolingSec}, remaining=${CreditManager.getRemainingUsableCount(context)} halfUnits (${"%.1f".format(CreditManager.getRemainingUsableCount(context) / 2.0)}次)"
            )
        }

        // 🔍 [BORROW_DIAG] 扣费完成后的最终状态
        if (isInDndSegment) {
            val diagBorrowedEnd = CreditManager.getBorrowedMinutesFromNext(context)
            val diagRemainingEnd = CreditManager.getRemainingMinutes(context)
            Log.d(TAG, "startTemporaryAccess[DIAG] FINAL_STATE borrowedEnd=${diagBorrowedEnd}min remainingEnd=${diagRemainingEnd}min")
        }
        val chargeReceipt = CreditManager.buildTemporaryAccessTimeChargeReceipt(
            context,
            currentChargeSecondsForSession,
            nextChargeSecondsForSession,
            borrowChargeSecondsForSession
        )
        setTemporaryAccessChargeReceipt(chargeReceipt)
        temporaryAccessChargedMinutes =
            (currentChargeSecondsForSession + nextChargeSecondsForSession) / 60.0
        beginTemporaryAccessLifecycle(seconds, emergency = false)
        saveTemporaryAccessState()

        resetConsecutiveNormalLockState("temporary_access_started")
        totalSegmentBlacklistHits = 0

        val freeTag = when {
            !isInDndSegment -> "（空档期免扣额度）"
            isInFreeTime -> "（休息中免扣额度）"
            else -> ""
        }
        if (cooldownSeconds > 0L) {
            Toast.makeText(context, "已放行 ${seconds}s${freeTag}，结束后需等待 ${cooldownSeconds}s 才可再次使用", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(context, "已放行 ${seconds}s${freeTag}，本次无冷却", Toast.LENGTH_SHORT).show()
        }
        CreditManager.recordTempUnlockEvent(context)
        if (chargeSnapshot.coolingSec > 0 || CreditManager.isCooling(context) || getTemporaryReuseCooldownRemainingSeconds() > 0L) {
            CreditManager.recordCooldownAttemptEvent(context)
        }
        dismissLockScreen(LockDismissAuthority.TEMPORARY_ACCESS)
    }

    internal fun normalizeTemporaryAccessSeconds(rawSeconds: Int, maxSeconds: Int = TEMP_ACCESS_DEFAULT_SECONDS): Int {
        val safeMax = maxSeconds.coerceIn(TEMP_ACCESS_MIN_SECONDS, TEMP_ACCESS_HARD_MAX_SECONDS)
        return rawSeconds.coerceIn(TEMP_ACCESS_MIN_SECONDS, safeMax)
    }

    // 🚀 [勿扰时段临时解锁分钟数计算]
    // 改为按分钟保留一位小数（四舍五入），即 round(minutes × 10) / 10.0
    // 显示与扣减统一保留一位小数，便于与 UI 展示一致。
    internal fun calculateTemporaryAccessMinutes(seconds: Int): Double {
        if (seconds <= 0) return 0.0
        // 🚀 保留一位小数（四舍五入，half-up），即 floor(minutes × 10 + 0.5) / 10.0
        // 注意：kotlin.math.round 使用银行家舍入（half-to-even），2.5→2.0，不符合预期
        // 这里改用 floor(x + 0.5) 实现标准四舍五入（half-up），2.5→3.0
        return kotlin.math.floor(seconds / 60.0 * 10 + 0.5) / 10.0
    }

    /**
     * 计算休息申请 / 临时解锁的「次数消耗」——按申请时段在不同阶段的比例加权。
     *
     * 加权模型（用户原话，Issue Tracker 06-24 反馈）：
     * - 空档/休息段：× 0
     * - 勿扰段且不在冷却期内：× 1
     * - 勿扰段且在冷却期内（冷却重叠段）：× 2
     *
     * 加权平均 = (freeSec × 0 + (dndSec - coolOverlapSec) × 1 + coolOverlapSec × 2) / totalSec
     *         = (dndSec + coolOverlapSec) / totalSec
     *
     * 四舍五入（half-up，使用 floor(x + 0.5) 避免银行家舍入）后 clamp 到 [0, 2]。
     *
     * WHY 抽出此函数：原 `coolOverlapSec / totalSec × 2` 的写法只把"冷却部分"按权重计入，
     * 其它时段都被隐式当成 × 0，导致非冷却期 DND 的 1× 被吞掉、混合场景次数偏小。
     * 同时 5 处重复（位置 A/B/C/D/E）逻辑各写一份容易漂移，故统一收敛。
     */
    internal fun calculateWeightedRestCount(
        freeSec: Int,
        dndSec: Int,
        coolOverlapSec: Int,
        totalSec: Int
    ): Int = calculateWeightedRestCountImpl(freeSec, dndSec, coolOverlapSec, totalSec)

    internal fun calculateTemporaryReuseCooldownMs(
        seconds: Int,
        maxSeconds: Int = TEMP_ACCESS_DEFAULT_SECONDS,
        unifiedCooldownSeconds: Int? = null
    ): Long {
        // 🚀 若传入统一冷静期间隙配置（全局参数），直接采用统一冷静期秒数
        if (unifiedCooldownSeconds != null && unifiedCooldownSeconds > 0) {
            return unifiedCooldownSeconds * 1000L
        }
        val normalized = normalizeTemporaryAccessSeconds(seconds, maxSeconds)
        val tierThreeSeconds = (normalized - 120).coerceAtLeast(0)
        val tierTwoSeconds = ((normalized - TEMP_ACCESS_COOLDOWN_SINGLE_RATE_UPPER_SECONDS).coerceIn(0, 60))
        val tierOneSeconds = normalized.coerceAtMost(TEMP_ACCESS_COOLDOWN_SINGLE_RATE_UPPER_SECONDS)
        val cooldownSeconds = tierThreeSeconds * 3 + tierTwoSeconds * 2 + tierOneSeconds * 1
        return cooldownSeconds * 1000L
    }

    internal fun shouldBlockTemporaryAccessByReuseCooldown(
        reuseRemainSeconds: Long,
        requestedSeconds: Int,
        maxSeconds: Int = TEMP_ACCESS_DEFAULT_SECONDS
    ): Boolean {
        // SPEC §3.2 契约：冷静期再犯进入加权双倍扣除，不再硬性阻断临时解锁
        return false
    }

    internal fun shouldShowTemporaryEmergencyTenButton(reuseRemainSeconds: Long): Boolean {
        return reuseRemainSeconds > 0L
    }

    internal fun isEmergencyTemporaryAccessDuringCooldown(
        reuseRemainSeconds: Long,
        requestedSeconds: Int,
        maxSeconds: Int = TEMP_ACCESS_DEFAULT_SECONDS
    ): Boolean {
        if (reuseRemainSeconds <= 0L) return false
        val normalized = normalizeTemporaryAccessSeconds(requestedSeconds, maxSeconds)
        return normalized == TEMP_ACCESS_COOLDOWN_FREE_SECONDS
    }

    internal fun canUseTemporaryEmergencyDuringCooldown(
        reuseRemainSeconds: Long,
        lastUseAtMs: Long = 0L,
        nowMs: Long = System.currentTimeMillis(),
        requestedSeconds: Int = TEMP_ACCESS_COOLDOWN_FREE_SECONDS,
        minGapSeconds: Int = TEMP_ACCESS_EMERGENCY_REUSE_GAP_SECONDS,
        minCooldownRequiredSeconds: Int = TEMP_ACCESS_EMERGENCY_MIN_COOLDOWN_REQUIRED_SECONDS,
        remainingUsesInCooldown: Int = -1
    ): Boolean {
        if (reuseRemainSeconds <= 0L) return true
        if (requestedSeconds != TEMP_ACCESS_COOLDOWN_FREE_SECONDS) return false
        if (remainingUsesInCooldown == 0) return false
        if (remainingUsesInCooldown < 0 && reuseRemainSeconds < minCooldownRequiredSeconds) return false
        if (lastUseAtMs <= 0L) return true
        val elapsedMs = nowMs - lastUseAtMs
        return elapsedMs >= minGapSeconds * 1000L
    }

    internal fun calculateTemporaryEmergencyBudgetTotal(
        cooldownSeconds: Long,
        perUseCooldownSeconds: Int = TEMP_ACCESS_EMERGENCY_MIN_COOLDOWN_REQUIRED_SECONDS
    ): Int {
        if (cooldownSeconds <= 0L) return 0
        val divisor = perUseCooldownSeconds.toLong().coerceAtLeast(1L)
        return (cooldownSeconds / divisor).toInt().coerceAtLeast(0)
    }

    internal fun resolveTemporaryEmergencyReuseGapRemainingSeconds(
        lastUseAtMs: Long,
        nowMs: Long = System.currentTimeMillis(),
        minGapSeconds: Int = TEMP_ACCESS_EMERGENCY_REUSE_GAP_SECONDS
    ): Long {
        if (lastUseAtMs <= 0L) return 0L
        val remainMs = lastUseAtMs + minGapSeconds * 1000L - nowMs
        if (remainMs <= 0L) return 0L
        return ((remainMs + 999L) / 1000L)
    }

    internal fun resolveTemporaryEmergencyRemainingUses(
        reuseRemainSeconds: Long,
        fixedRemainingUses: Int = -1,
        perUseCooldownSeconds: Int = TEMP_ACCESS_EMERGENCY_MIN_COOLDOWN_REQUIRED_SECONDS
    ): Int {
        if (fixedRemainingUses >= 0) return fixedRemainingUses.coerceAtLeast(0)
        if (reuseRemainSeconds <= 0L) return 0
        // Use rounding consistent with calculateTemporaryEmergencyBudgetTotal so budget matches other calculations
        return calculateTemporaryEmergencyBudgetTotal(reuseRemainSeconds, perUseCooldownSeconds)
    }

    internal fun getTemporaryAccessRemainingSeconds(nowMs: Long = System.currentTimeMillis()): Long {
        return calculateTemporaryAccessRemainingSeconds(temporaryAccessUntil, nowMs)
    }

    internal fun calculateTemporaryAccessRemainingSeconds(
        accessUntilMs: Long,
        nowMs: Long
    ): Long {
        val remainMs = accessUntilMs - nowMs
        if (remainMs <= 0L) return 0L
        return (remainMs + 999L) / 1000L
    }

    internal fun calculateTemporaryAccessExpiryDelayMs(
        accessUntilMs: Long,
        nowMs: Long
    ): Long {
        if (accessUntilMs <= 0L) return 0L
        return (accessUntilMs - nowMs).coerceAtLeast(0L)
    }

    internal fun getTemporaryReuseCooldownRemainingSeconds(nowMs: Long = System.currentTimeMillis()): Long {
        val remainSeconds = transitionTemporaryAccessToCooldownIfNeeded(nowMs)

        if (remainSeconds <= 0L) {
            if (shouldClearTemporaryAccessState(
                    accessUntilMs = temporaryAccessUntil,
                    cooldownUntilMs = temporaryReuseCooldownUntil,
                    nowMs = nowMs
                )
            ) {
                // 🛡️ [P0 Fix] 保存目标包名，确保 relock 只对原始目标包生效。
                // 之前 flag=true 时对任何前台应用都绕过守卫，导致误锁。
                temporaryAccessFinalRelockTargetPkg = temporaryAccessTargetPackageName
                temporaryAccessFinalRelockNeeded = true
                clearTemporaryAccessState()
            }
            return 0L
        }
        return remainSeconds
    }

    internal fun calculateTemporaryReuseCooldownRemainingSeconds(
        accessUntilMs: Long,
        cooldownUntilMs: Long,
        nowMs: Long
    ): Long {
        if (cooldownUntilMs <= 0L) return 0L
        if (accessUntilMs > nowMs) return 0L
        val remainMs = cooldownUntilMs - nowMs
        if (remainMs <= 0L) return 0L
        return (remainMs + 999L) / 1000L
    }

    internal fun shouldClearTemporaryAccessState(
        accessUntilMs: Long,
        cooldownUntilMs: Long,
        nowMs: Long
    ): Boolean {
        if (accessUntilMs > nowMs) return false
        return cooldownUntilMs <= nowMs
    }

    internal fun shouldForceRelockAfterTemporaryAccess(
        expiredAtMs: Long,
        cooldownRemainSeconds: Long,
        nowMs: Long
    ): Boolean {
        if (cooldownRemainSeconds <= 0L) return false
        if (expiredAtMs <= 0L) return false
        val delta = nowMs - expiredAtMs
        if (delta < 0L) return false
        return true
    }

    internal fun shouldForceRelockAfterTemporaryAccess(nowMs: Long = System.currentTimeMillis()): Boolean {
        // 临时解锁到期后必须重新锁屏，不应受冷却期影响。
        // 冷却期决定的是"能否再申请临时解锁"，而不是"该不该重新锁"。
        if (temporaryAccessExpiredAt <= 0L) return false
        return nowMs >= temporaryAccessExpiredAt
    }

    internal fun resolveForcedRelockTargetPackage(
        context: Context,
        nowMs: Long = System.currentTimeMillis()
    ): String? {
        if (!shouldForceRelockAfterTemporaryAccess(nowMs)) return null
        val targetPkg = temporaryAccessTargetPackageName ?: return null
        if (!SegmentBlacklist.shouldLock(context, targetPkg)) return null
        return targetPkg
    }

    internal fun shouldTriggerTemporaryAccessExpiryRelock(
        targetPackageName: String?,
        currentTopPackageName: String?,
        isTargetStillBlocked: Boolean
    ): Boolean {
        if (targetPackageName.isNullOrBlank()) return false
        if (currentTopPackageName.isNullOrBlank()) return false
        if (!isTargetStillBlocked) return false
        return targetPackageName == currentTopPackageName
    }

    internal fun shouldRetryTemporaryAccessExpiryRelock(
        targetPackageName: String?,
        currentTopPackageName: String?,
        cooldownRemainSeconds: Long,
        retryCount: Int,
        maxRetryCount: Int
    ): Boolean {
        if (targetPackageName.isNullOrBlank()) return false
        if (cooldownRemainSeconds <= 0L) return false
        if (retryCount >= maxRetryCount) return false
        return currentTopPackageName.isNullOrBlank()
    }

    data class TemporaryAccessSnapshot(
        val accessRemainingSeconds: Long,
        val cooldownRemainingSeconds: Long
    )

    fun getTemporaryAccessSnapshot(
        context: Context,
        nowMs: Long = System.currentTimeMillis()
    ): TemporaryAccessSnapshot {
        val prefs = context.getSharedPreferences(LOCK_STATE_PREFS, Context.MODE_PRIVATE)
        val persistedAccessUntil = prefs.getLong(KEY_TEMP_ACCESS_UNTIL, 0L)
        val persistedCooldownUntil = prefs.getLong(KEY_TEMP_REUSE_COOLDOWN_UNTIL, 0L)

        val accessUntil = maxOf(temporaryAccessUntil, persistedAccessUntil)
        val cooldownUntil = maxOf(temporaryReuseCooldownUntil, persistedCooldownUntil)

        val accessRemaining = if (accessUntil > nowMs) (accessUntil - nowMs + 999L) / 1000L else 0L
        val cooldownRemaining = calculateTemporaryReuseCooldownRemainingSeconds(
            accessUntilMs = accessUntil,
            cooldownUntilMs = cooldownUntil,
            nowMs = nowMs
        )
        return TemporaryAccessSnapshot(
            accessRemainingSeconds = accessRemaining,
            cooldownRemainingSeconds = cooldownRemaining
        )
    }

    @Synchronized
    fun settleTemporaryAccessForNaturalFreedom(
        context: Context,
        nowMs: Long = System.currentTimeMillis()
    ) {
        if (temporaryAccessUntil <= 0L || nowMs >= temporaryAccessUntil) return
        val elapsedSeconds = ((nowMs - temporaryAccessStartAt).coerceAtLeast(0L) / 1000L).toInt()
        val refundPlan = calculateTemporaryAccessRefundPlan(
            currentChargeSeconds = temporaryAccessCurrentChargeSeconds,
            nextChargeSeconds = temporaryAccessNextChargeSeconds,
            borrowChargeSeconds = temporaryAccessBorrowChargeSeconds,
            plannedSeconds = temporaryAccessSeconds,
            elapsedSeconds = elapsedSeconds
        )
        if (refundPlan.refundedQuotaSeconds > 0) {
            CreditManager.refundTemporaryAccessTimeCharge(
                context,
                CreditManager.TemporaryAccessTimeChargeReceipt(
                    currentUsageKey = temporaryAccessCurrentUsageKey,
                    nextUsageKey = temporaryAccessNextUsageKey,
                    borrowKey = temporaryAccessBorrowKey,
                    currentChargeSeconds = temporaryAccessCurrentChargeSeconds,
                    nextChargeSeconds = temporaryAccessNextChargeSeconds,
                    borrowChargeSeconds = temporaryAccessBorrowChargeSeconds
                ),
                refundPlan
            )
        }
        LogExporter.i(
            "TemporaryAccess",
            "[NATURAL_FREEDOM] elapsed=${elapsedSeconds}s/${temporaryAccessSeconds}s refund=${refundPlan.refundedQuotaSeconds / 60.0}min"
        )
        temporaryAccessFinalRelockNeeded = false
        temporaryAccessFinalRelockTargetPkg = null
        mDismissUntil = maxOf(mDismissUntil, nowMs + 1_000L)
        endTemporaryAccessLifecycle(nowMs, reason = "FREE_TIME_STARTED", nextState = "FREE_TIME")
        clearTemporaryAccessState()
        LogExporter.i("TemporaryAccess", "[NATURAL_FREEDOM] visible lock preserved; explicit exit required")
    }

    internal fun isTemporaryAccessInputValid(rawSeconds: Int?, maxSeconds: Int = TEMP_ACCESS_DEFAULT_SECONDS): Boolean {
        val safeMax = maxSeconds.coerceIn(TEMP_ACCESS_MIN_SECONDS, TEMP_ACCESS_HARD_MAX_SECONDS)
        return rawSeconds != null && rawSeconds in TEMP_ACCESS_MIN_SECONDS..safeMax
    }

    private fun getTemporaryAccessMaxSeconds(context: Context): Int {
        return TimeRuleManager.getTemporaryRestrictedAccessMaxSeconds(context)
            .coerceIn(TEMP_ACCESS_MIN_SECONDS, TEMP_ACCESS_HARD_MAX_SECONDS)
    }

    private fun saveTemporaryAccessState() {
        val context = mContext ?: return
        try {
            getLockStatePrefs(context).edit()
                .putLong(KEY_TEMP_ACCESS_UNTIL, temporaryAccessUntil)
                .putInt(KEY_TEMP_ACCESS_SECONDS, temporaryAccessSeconds)
                .putLong(KEY_TEMP_ACCESS_START_AT, temporaryAccessStartAt)
                .putLong(
                    KEY_TEMP_ACCESS_CHARGED_MINUTES_BITS,
                    java.lang.Double.doubleToRawLongBits(temporaryAccessChargedMinutes)
                )
                .putString(KEY_TEMP_ACCESS_CURRENT_USAGE_KEY, temporaryAccessCurrentUsageKey)
                .putString(KEY_TEMP_ACCESS_NEXT_USAGE_KEY, temporaryAccessNextUsageKey)
                .putString(KEY_TEMP_ACCESS_BORROW_KEY, temporaryAccessBorrowKey)
                .putInt(KEY_TEMP_ACCESS_CURRENT_CHARGE_SECONDS, temporaryAccessCurrentChargeSeconds)
                .putInt(KEY_TEMP_ACCESS_NEXT_CHARGE_SECONDS, temporaryAccessNextChargeSeconds)
                .putInt(KEY_TEMP_ACCESS_BORROW_CHARGE_SECONDS, temporaryAccessBorrowChargeSeconds)
                .putLong(KEY_TEMP_REUSE_COOLDOWN_UNTIL, temporaryReuseCooldownUntil)
                .putString(KEY_TEMP_ACCESS_TARGET_PACKAGE, temporaryAccessTargetPackageName)
                .putString(KEY_TEMP_LOG_REQUEST_ID, temporaryAccessLogRequestId)
                .putString(KEY_TEMP_LOG_SESSION_ID, temporaryAccessLogSessionId)
                .putBoolean(KEY_TEMP_LOG_EMERGENCY, temporaryAccessLogEmergency)
                .putLong(KEY_TEMP_EMERGENCY_LAST_USE_AT, temporaryEmergencyLastUseAtInCooldown)
                .putInt(KEY_TEMP_EMERGENCY_REMAINING_USES, temporaryEmergencyRemainingUsesInCooldown)
                .apply()
        } catch (e: Exception) {
            Log.w("LockWindowManager", "saveTemporaryAccessState: failed to persist state: ${e.message}")
        }
    }

    private fun setTemporaryAccessChargeReceipt(
        receipt: CreditManager.TemporaryAccessTimeChargeReceipt
    ) {
        temporaryAccessCurrentUsageKey = receipt.currentUsageKey
        temporaryAccessNextUsageKey = receipt.nextUsageKey
        temporaryAccessBorrowKey = receipt.borrowKey
        temporaryAccessCurrentChargeSeconds = receipt.currentChargeSeconds
        temporaryAccessNextChargeSeconds = receipt.nextChargeSeconds
        temporaryAccessBorrowChargeSeconds = receipt.borrowChargeSeconds
    }

    private fun clearTemporaryAccessChargeReceipt() {
        temporaryAccessCurrentUsageKey = null
        temporaryAccessNextUsageKey = null
        temporaryAccessBorrowKey = null
        temporaryAccessCurrentChargeSeconds = 0
        temporaryAccessNextChargeSeconds = 0
        temporaryAccessBorrowChargeSeconds = 0
    }

    private fun clearTemporaryAccessState() {
        cancelTemporaryAccessExpiryCallback()
        temporaryAccessUntil = 0L
        temporaryAccessSeconds = 0
        temporaryAccessStartAt = 0L
        temporaryAccessChargedMinutes = 0.0
        clearTemporaryAccessChargeReceipt()
        temporaryReuseCooldownUntil = 0L
        temporaryAccessExpiredAt = 0L
        temporaryAccessTargetPackageName = null
        resetDesktopStreak()
        temporaryAccessLogRequestId = null
        temporaryAccessLogSessionId = null
        temporaryAccessLogEmergency = false
        temporaryEmergencyLastUseAtInCooldown = 0L
        temporaryEmergencyRemainingUsesInCooldown = 0
        temporaryAccessLastNonLauncherPkg = null
        temporaryAccessLastDetectedPkg = null
        val context = mContext ?: return
        try {
            getLockStatePrefs(context).edit()
                .remove(KEY_TEMP_ACCESS_UNTIL)
                .remove(KEY_TEMP_ACCESS_SECONDS)
                .remove(KEY_TEMP_ACCESS_START_AT)
                .remove(KEY_TEMP_ACCESS_CHARGED_MINUTES_BITS)
                .remove(KEY_TEMP_ACCESS_CURRENT_USAGE_KEY)
                .remove(KEY_TEMP_ACCESS_NEXT_USAGE_KEY)
                .remove(KEY_TEMP_ACCESS_BORROW_KEY)
                .remove(KEY_TEMP_ACCESS_CURRENT_CHARGE_SECONDS)
                .remove(KEY_TEMP_ACCESS_NEXT_CHARGE_SECONDS)
                .remove(KEY_TEMP_ACCESS_BORROW_CHARGE_SECONDS)
                .remove(KEY_TEMP_REUSE_COOLDOWN_UNTIL)
                .remove(KEY_TEMP_ACCESS_TARGET_PACKAGE)
                .remove(KEY_TEMP_LOG_REQUEST_ID)
                .remove(KEY_TEMP_LOG_SESSION_ID)
                .remove(KEY_TEMP_LOG_EMERGENCY)
                .remove(KEY_TEMP_EMERGENCY_LAST_USE_AT)
                .remove(KEY_TEMP_EMERGENCY_REMAINING_USES)
                .apply()
        } catch (e: Exception) {
            Log.w("LockWindowManager", "clearTemporaryAccessState: failed to clear prefs: ${e.message}")
        }
    }

    private fun restoreTemporaryAccessState(context: Context) {
        val prefs = try {
            getLockStatePrefs(context)
        } catch (e: Exception) {
            Log.w("LockWindowManager", "restoreTemporaryAccessState: failed to access prefs: ${e.message}")
            // If prefs are not available (credential encrypted), abort restore and keep defaults
            clearTemporaryAccessState()
            return
        }
        val persistedAccessUntil = prefs.getLong(KEY_TEMP_ACCESS_UNTIL, 0L)
        val persistedAccessSeconds = prefs.getInt(KEY_TEMP_ACCESS_SECONDS, 0)
        val persistedAccessStartAt = prefs.getLong(KEY_TEMP_ACCESS_START_AT, 0L)
        val persistedChargedMinutes = java.lang.Double.longBitsToDouble(
            prefs.getLong(KEY_TEMP_ACCESS_CHARGED_MINUTES_BITS, java.lang.Double.doubleToRawLongBits(0.0))
        )
        val persistedCurrentUsageKey = prefs.getString(KEY_TEMP_ACCESS_CURRENT_USAGE_KEY, null)
        val persistedNextUsageKey = prefs.getString(KEY_TEMP_ACCESS_NEXT_USAGE_KEY, null)
        val persistedBorrowKey = prefs.getString(KEY_TEMP_ACCESS_BORROW_KEY, null)
        val persistedCurrentChargeSeconds = prefs.getInt(KEY_TEMP_ACCESS_CURRENT_CHARGE_SECONDS, 0)
        val persistedNextChargeSeconds = prefs.getInt(KEY_TEMP_ACCESS_NEXT_CHARGE_SECONDS, 0)
        val persistedBorrowChargeSeconds = prefs.getInt(KEY_TEMP_ACCESS_BORROW_CHARGE_SECONDS, 0)
        val persistedCooldownUntil = prefs.getLong(KEY_TEMP_REUSE_COOLDOWN_UNTIL, 0L)
        val persistedTargetPackage = prefs.getString(KEY_TEMP_ACCESS_TARGET_PACKAGE, null)
        val persistedLogRequestId = prefs.getString(KEY_TEMP_LOG_REQUEST_ID, null)
        val persistedLogSessionId = prefs.getString(KEY_TEMP_LOG_SESSION_ID, null)
        val persistedLogEmergency = prefs.getBoolean(KEY_TEMP_LOG_EMERGENCY, false)
        val persistedEmergencyLastUseAt = prefs.getLong(KEY_TEMP_EMERGENCY_LAST_USE_AT, 0L)
        val persistedEmergencyRemainingUses = prefs.getInt(KEY_TEMP_EMERGENCY_REMAINING_USES, -1)
        val now = System.currentTimeMillis()

        if (persistedAccessUntil <= 0L && persistedCooldownUntil <= 0L) {
            clearTemporaryAccessState()
            return
        }

        temporaryAccessUntil = if (persistedAccessUntil > now) persistedAccessUntil else 0L
        temporaryAccessSeconds = if (temporaryAccessUntil > 0L) persistedAccessSeconds else 0
        temporaryAccessStartAt = if (temporaryAccessUntil > 0L) persistedAccessStartAt else 0L
        temporaryAccessChargedMinutes = if (temporaryAccessUntil > 0L) persistedChargedMinutes else 0.0
        if (temporaryAccessUntil > 0L) {
            temporaryAccessCurrentUsageKey = persistedCurrentUsageKey
            temporaryAccessNextUsageKey = persistedNextUsageKey
            temporaryAccessBorrowKey = persistedBorrowKey
            temporaryAccessCurrentChargeSeconds = persistedCurrentChargeSeconds
            temporaryAccessNextChargeSeconds = persistedNextChargeSeconds
            temporaryAccessBorrowChargeSeconds = persistedBorrowChargeSeconds
        } else {
            clearTemporaryAccessChargeReceipt()
        }
        temporaryReuseCooldownUntil = if (persistedCooldownUntil > now) persistedCooldownUntil else 0L
        temporaryAccessExpiredAt = when {
            temporaryAccessUntil > 0L -> 0L
            temporaryReuseCooldownUntil > 0L && persistedAccessUntil > 0L -> persistedAccessUntil
            else -> 0L
        }
        temporaryAccessTargetPackageName = if (temporaryAccessUntil > 0L || temporaryReuseCooldownUntil > 0L) {
            persistedTargetPackage
        } else {
            null
        }
        temporaryAccessLogRequestId = persistedLogRequestId
        temporaryAccessLogSessionId = persistedLogSessionId
        temporaryAccessLogEmergency = persistedLogEmergency
        temporaryEmergencyLastUseAtInCooldown = if (temporaryReuseCooldownUntil > now) {
            persistedEmergencyLastUseAt
        } else {
            0L
        }
        temporaryEmergencyRemainingUsesInCooldown = if (temporaryReuseCooldownUntil > now) {
            if (persistedEmergencyRemainingUses >= 0) {
                persistedEmergencyRemainingUses.coerceAtLeast(0)
            } else {
                // Migration fallback for old state without persisted fixed budget.
                calculateTemporaryEmergencyBudgetTotal(
                    cooldownSeconds = calculateTemporaryReuseCooldownRemainingSeconds(
                        accessUntilMs = temporaryAccessUntil,
                        cooldownUntilMs = temporaryReuseCooldownUntil,
                        nowMs = now
                    )
                )
            }
        } else {
            0
        }

        if (temporaryAccessUntil > 0L) {
            mDismissUntil = maxOf(mDismissUntil, temporaryAccessUntil)
        }

        if (temporaryAccessUntil <= 0L && temporaryReuseCooldownUntil <= 0L) {
            clearTemporaryAccessState()
            return
        }
        saveTemporaryAccessState()
        scheduleTemporaryAccessExpiryIfNeeded()
    }

    /**
     * Get SharedPreferences for lock state with a safe fallback for Direct Boot.
     * If credential-encrypted storage is not yet available, return device-protected prefs.
     */
    private fun getLockStatePrefs(context: Context): android.content.SharedPreferences {
        return try {
            context.getSharedPreferences(LOCK_STATE_PREFS, Context.MODE_PRIVATE)
        } catch (e: IllegalStateException) {
            // Credential-encrypted prefs not available yet (user locked). Use device-protected storage.
            Log.w("LockWindowManager", "getLockStatePrefs: credential-encrypted prefs unavailable, using device-protected storage")
            context.createDeviceProtectedStorageContext()
                .getSharedPreferences(LOCK_STATE_PREFS, Context.MODE_PRIVATE)
        }
    }

    private fun transitionTemporaryAccessToCooldownIfNeeded(nowMs: Long = System.currentTimeMillis()): Long {
        val remainSeconds = calculateTemporaryReuseCooldownRemainingSeconds(
            accessUntilMs = temporaryAccessUntil,
            cooldownUntilMs = temporaryReuseCooldownUntil,
            nowMs = nowMs
        )

        if (temporaryAccessUntil > 0L && nowMs >= temporaryAccessUntil) {
            val expiredAt = temporaryAccessUntil
            endTemporaryAccessLifecycle(nowMs)
            if (temporaryAccessExpiredAt <= 0L) {
                temporaryAccessExpiredAt = expiredAt
            }
            temporaryAccessUntil = 0L
            temporaryAccessSeconds = 0
            cancelTemporaryAccessExpiryCallback()
            if (remainSeconds > 0L) {
                LogExporter.logEvent(
                    "TemporaryAccess",
                    "[COOLDOWN_START] reuseWaitUntil=$temporaryReuseCooldownUntil, targetPkg=${temporaryAccessTargetPackageName ?: "N/A"}"
                )
            }
            saveTemporaryAccessState()
        }
        if (temporaryReuseCooldownUntil <= nowMs) {
            temporaryAccessLogSessionId?.let { sessionId ->
                temporaryCooldownSettlement.settleIfExpired(nowMs, temporaryReuseCooldownUntil) {
                    if (!temporaryAccessLogEmergency) {
                        lifecycleNormalEventBridge.recordTemporaryAccessCooldownEnded(sessionId)
                    }
                }
            }
            temporaryEmergencyLastUseAtInCooldown = 0L
            temporaryEmergencyRemainingUsesInCooldown = 0
        }
        return remainSeconds
    }

    private fun scheduleTemporaryAccessExpiryIfNeeded() {
        cancelTemporaryAccessExpiryCallback()
        cancelTemporaryAccessExpiryRelockRetry()
        val context = mContext ?: return
        val accessUntil = temporaryAccessUntil
        if (accessUntil <= 0L) return
        val delayMs = calculateTemporaryAccessExpiryDelayMs(accessUntil, System.currentTimeMillis())
        temporaryAccessExpiryRunnable = Runnable {
            handleTemporaryAccessExpired(context)
        }
        if (delayMs <= 0L) {
            mUiHandler.post(temporaryAccessExpiryRunnable!!)
        } else {
            mUiHandler.postDelayed(temporaryAccessExpiryRunnable!!, delayMs)
        }
    }

    private fun cancelTemporaryAccessExpiryCallback() {
        temporaryAccessExpiryRunnable?.let { mUiHandler.removeCallbacks(it) }
        temporaryAccessExpiryRunnable = null
    }

    private fun cancelTemporaryAccessExpiryRelockRetry() {
        temporaryAccessExpiryRelockRetryRunnable?.let { mUiHandler.removeCallbacks(it) }
        temporaryAccessExpiryRelockRetryRunnable = null
        temporaryAccessExpiryRelockRetryCount = 0
        // Boundary 1 — REQ-004: cancel wired into the existing helper so all retry
        // cancel sites pick up the FINISHED transition without needing to repeat it.
        finishExpiryOperation("cancel")
    }

    // ===== Boundary 1 / Bug 1 — expiry operation state machine API =====
    /**
     * Anchor a new expiry operation to the supplied temp-access session id.
     *
     * Idempotent: if the session id matches an in-flight operation the existing
     * operation id is preserved. Otherwise the controller rotates to a fresh id
     * (suffix derived from the supplied session) and resets the storm counters.
     */
    private fun beginExpiryOperationForSession(sessionId: String) {
        val existing = expiryOperationSessionId
        if (existing == sessionId && expiryOperationId != null) return
        expiryOperationSessionId = sessionId
        // Operation id suffix mirrors `TEMP-SESSION-N` → `EXPIRY-N` (spec §9.3).
        val suffix = sessionId.substringAfterLast('-', missingDelimiterValue = "")
        expiryOperationId = if (suffix.isNotEmpty()) "EXPIRY-$suffix" else "EXPIRY-${sessionId.hashCode()}"
        expiryOperationStartedAtMs = System.currentTimeMillis()
        expiryOperationReentryCount = 0
        expiryStormEventEmitted = false
    }

    /**
     * Attempt to advance the controller into EXPIRY_HANDLING. Returns true when the
     * caller is the unique winner of the CAS race; false means another invocation is
     * already inside the operation and the caller must short-circuit.
     */
    @Synchronized
    private fun tryEnterExpiryHandling(): Boolean {
        // If the previous operation is FINISHED we still allow a fresh entry — but only
        // if `beginExpiryOperationForSession` has already rotated the identity.
        if (expiryOperationId == null) return false
        if (expiryOperationReentryCount > 0) {
            // A retry tick is being handled by [recordExpiryReentry] (storm guard);
            // it must not be promoted to a fresh EXPIRY_HANDLING transition.
            return false
        }
        return true
    }

    /**
     * Force-cancel the current expiry operation: drop any scheduled retry, advance
     * to FINISHED and emit [LOCK_RETRY_STORM] exactly once when [recordExpiryReentry]
     * has flagged the controller as storming. The `mDeferredLock` field is preserved
     * on purpose so the deferred UI flow survives the retry-storm remediation.
     */
    @Synchronized
    private fun finishExpiryOperation(reason: String) {
        if (expiryOperationId == null) return
        // Cancel any pending retry tick before flipping the state — otherwise a tick
        // already queued at mUiHandler would observe the new FINISHED state but still
        // execute its body.
        temporaryAccessExpiryRelockRetryRunnable?.let { mUiHandler.removeCallbacks(it) }
        temporaryAccessExpiryRelockRetryRunnable = null
        temporaryAccessExpiryRelockRetryCount = 0
        if (expiryStormEventEmitted) {
            LogExporter.logEvent(
                "LockScreen",
                "[LOCK_RETRY_STORM] operationId=${expiryOperationId} " +
                    "sessionId=${expiryOperationSessionId} " +
                    "suppressedCount=$expiryOperationReentryCount " +
                    "result=FORCED_FINISHED reason=$reason"
            )
        }
        expiryOperationId = null
        expiryOperationSessionId = null
        expiryOperationStartedAtMs = 0L
        expiryOperationReentryCount = 0
        expiryStormEventEmitted = false
    }

    /**
     * Sliding-window counter for the storm guard (REQ-005). Increments on every retry
     * tick observed while a previous EXPIRY_HANDLING transition is still in flight;
     * when the count reaches 5 within 60 s of [expiryOperationStartedAtMs] the controller
     * is forced into FINISHED and [LOCK_RETRY_STORM] is emitted exactly once.
     */
    @Synchronized
    private fun recordExpiryReentry() {
        if (expiryOperationId == null) return
        val now = System.currentTimeMillis()
        if (now - expiryOperationStartedAtMs > STORM_WINDOW_MS) {
            // Window expired — reset the counter so a fresh storm has to re-arm from 0.
            expiryOperationReentryCount = 0
            expiryOperationStartedAtMs = now
        }
        expiryOperationReentryCount += 1
        if (expiryOperationReentryCount >= STORM_THRESHOLD && !expiryStormEventEmitted) {
            expiryStormEventEmitted = true
            finishExpiryOperation("storm_threshold_reached")
        }
    }

    private const val STORM_THRESHOLD = 5
    private const val STORM_WINDOW_MS = 60_000L
    // ============================================================================

    // 二级应急启动时的前台 App，供 10s 后判断是否切换
    private var emergencyStartPackage: String? = null

    // 二级应急 10s 后检查：若未切换 App 则强制锁屏
    private var emergencyRelockRunnable: Runnable? = null

    private fun scheduleEmergencyRelock(context: Context, relockAtMs: Long) {
        cancelEmergencyRelock()
        val delayMs = (relockAtMs - System.currentTimeMillis()).coerceAtLeast(0L)
        emergencyRelockRunnable = Runnable {
            val ctx = mContext ?: return@Runnable
            val nowFg = getLastKnownForegroundPkg()
            val startPkg = emergencyStartPackage
            val blacklistApps = CreditManager.getBlacklistApps(ctx)
            val isStillBlacklisted = nowFg == null || blacklistApps.contains(nowFg)
            if (isStillBlacklisted) {
                // 到期时前台仍是黑名单 App（或无法判定）→ 强制锁屏
                val appName = if (nowFg != null) {
                    try {
                        ctx.packageManager.getApplicationLabel(
                            ctx.packageManager.getApplicationInfo(nowFg, 0)
                        ).toString()
                    } catch (_: Exception) { nowFg }
                } else {
                    startPkg ?: "未知应用"
                }
                val label = "二级解锁休息到期（$appName）"
                LogExporter.logEvent("TemporaryAccess", "[EMERGENCY_RELOCK] still on blacklist, locking. startPkg=$startPkg, nowFg=$nowFg, label=$label")
                transitionTemporaryAccessToCooldownIfNeeded(System.currentTimeMillis())
                showLockScreen(
                    reason = label,
                    isAntiUninstall = false,
                    isBlacklist = true,
                    blockedAppLabel = label,
                    blockedPackageName = nowFg ?: temporaryAccessTargetPackageName
                )
            } else {
                // 切换到非黑名单 App（如桌面）→ 走正常到期流程
                LogExporter.logEvent("TemporaryAccess", "[EMERGENCY_RELOCK] switched to non-blacklist app, normal expiry. startPkg=$startPkg, nowFg=$nowFg")
                handleTemporaryAccessExpired(context)
            }
        }
        mUiHandler.postDelayed(emergencyRelockRunnable!!, delayMs)
    }

    private fun cancelEmergencyRelock() {
        emergencyRelockRunnable?.let { mUiHandler.removeCallbacks(it) }
        emergencyRelockRunnable = null
    }

    private fun scheduleTemporaryAccessExpiryRelockRetry(context: Context) {
        // Boundary 1 — REQ-004 / REQ-005: gate the unbounded tick behind a CAS attempt.
        // When the operation has already been delivered (FINISHED) or is storming, we
        // must not keep re-arming the retry; the storm insurance finishes the controller.
        if (!tryEnterExpiryHandling()) return
        temporaryAccessExpiryRelockRetryRunnable?.let { mUiHandler.removeCallbacks(it) }
        temporaryAccessExpiryRelockRetryRunnable = Runnable {
            triggerTemporaryAccessExpiryRelock(context)
        }
        mUiHandler.postDelayed(
            temporaryAccessExpiryRelockRetryRunnable!!,
            TEMP_ACCESS_EXPIRE_RELOCK_RETRY_INTERVAL_MS
        )
    }

    private fun handleTemporaryAccessExpired(context: Context) {
        val now = System.currentTimeMillis()
        // Boundary 1 — anchor the expiry operation to whatever session is currently
        // active before transitioning to cooldown, so subsequent ticks carry a stable
        // operationId rather than racing on a still-null identity.
        temporaryAccessLogSessionId?.let { beginExpiryOperationForSession(it) }
        val cooldownRemain = transitionTemporaryAccessToCooldownIfNeeded(now)
        if (isLocked) {
            updateStatus(context)
        } else {
            mUiHandler.post { updateStatus(context) }
        }
        if (cooldownRemain <= 0L) return

        // 🚀 [Unified Expiry] 统一通过 ExpiryCoordinator 评估临时解锁到期决策
        val decision = ExpiryCoordinator.evaluate(context, ExpiryReason.TEMP_ACCESS_END)
        if (decision == ExpiryDecision.LOCK) {
            cancelTemporaryAccessExpiryRelockRetry()
            return
        }

        triggerTemporaryAccessExpiryRelock(context)
    }

    /**
     * 交叉验证获取当前最可信的前台应用包名。
     *
     * 两源交叉验证，结果存入缓存，锁屏决策以缓存为准：
     * - 数据源1: UsageStats Events（系统前台切换事件，权威但可能无结果）
     * - 数据源2: UsageStats 统计（lastTimeUsed/lastTimeVisible，独立验证）
     * - 两源交叉验证后的产物 → mLastTopPackage 缓存（可靠，MonitorService 每 500ms 写入）
     *
     * 本方法以实时查询为主，再加两级兜底：
     * 1. getTopPackageName() → 实时查询（Events → UsageStats → 缓存）
     *    注意：返回的是原始检测值（可能是桌面包名），未经过载体穿透
     * 2. getLastKnownForegroundPkg() → MonitorService 每 500ms 记录的环形缓冲区（30s 内有效）
     *    注意：记录的是 effectivePackageName（载体穿透后的真实 App），与 ① 在桌面场景下可能不同
     * 3. temporaryAccessLastDetectedPkg → 最终兜底（仅过期路径使用）
     *
     * ① 与 ② 的不一致是设计内行为（用户停在桌面时），下游 checkAndHandleDesktopStreakLock 统一处理。
     *
     * 用于临时解锁到期、休息结束等需要精准判断"当前要不要锁"的场景。
     */
    fun getEffectiveForegroundPkg(context: Context): String? {
        val freshPkg = getTopPackageName(context)
        val logPkg = getLastKnownForegroundPkg()
        return when {
            // 两源一致 → 高置信度
            freshPkg != null && logPkg != null && freshPkg == logPkg -> freshPkg
            // UsageStats 有结果 → 优先使用（事件驱动更权威）
            freshPkg != null -> freshPkg
            // UsageStats 没结果，MonitorService 日志兜底（30s 内有效）
            logPkg != null -> logPkg
            // 都没结果 → 最后兜底
            else -> temporaryAccessLastDetectedPkg
        }
    }

    private fun triggerTemporaryAccessExpiryRelock(context: Context) {
        // Boundary 1 — REQ-005: every entry into this method is a re-entry tick; record
        // it before any work so the storm guard sees the count even if we early-return.
        recordExpiryReentry()
        val cooldownRemain = getTemporaryReuseCooldownRemainingSeconds()
        if (cooldownRemain <= 0L) {
            cancelTemporaryAccessExpiryRelockRetry()
            return
        }

        // 若 target 已被清除（白名单放行等场景），无需继续重试
        val targetPkg = temporaryAccessTargetPackageName ?: privilegeDesktopStreakTargetPkg
        if (targetPkg == null) {
            cancelTemporaryAccessExpiryRelockRetry()
            return
        }

        cancelTemporaryAccessExpiryRelockRetry()

        val effectivePkg = getEffectiveForegroundPkg(context)
        if (effectivePkg != null) {
            if (checkAndHandleDesktopStreakLock(context, effectivePkg)) {
                return // 已锁
            }
            // 没锁（白名单/carrier+非黑名单）→ 安排重试，让 60s 计时器继续跑
            scheduleTemporaryAccessExpiryRelockRetry(context)
            return
        }

        // 所有来源都没有前台信息 → 不锁，等下次前台检测时由 checkAndHandleDesktopStreakLock 处理
        LogExporter.logEvent("TemporaryAccess", "[EXPIRY_SKIP] no foreground info from any source, skip lock for now")
    }

    /**
     * 强制重锁：临时解锁到期后检测不到前台（息屏/睡着），
     * 无论如何锁屏都要弹出来。
     */
    private fun forceRelockAfterExpiry(context: Context, targetPackage: String?) {
        if (targetPackage == null) return
        val effectivePkg = getEffectiveForegroundPkg(context)
        if (effectivePkg != null) {
            if (checkAndHandleDesktopStreakLock(context, effectivePkg)) {
                return
            }
            scheduleTemporaryAccessExpiryRelockRetry(context)
            return
        }
        // 所有来源都没有前台信息 → 不锁，等下次前台检测
        LogExporter.logEvent("TemporaryAccess", "[FORCE_RELOCK_SKIP] no foreground info from any source, skip lock for now")
    }

    private fun resolveTemporaryAccessTargetLabel(context: Context, packageName: String): String? {
        return try {
            val pm = context.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
        } catch (_: Exception) {
            null
        }
    }

    internal fun shouldTriggerTemporaryPenalty(nowMs: Long, accessUntilMs: Long): Boolean {
        return accessUntilMs > 0L && nowMs >= accessUntilMs
    }

    internal fun shouldCountPenaltyForGapOrRest(
        isGap: Boolean,
        isRest: Boolean,
        isAppWindowBlocked: Boolean
    ): Boolean {
        if (!isGap && !isRest) return true
        return isAppWindowBlocked
    }

    internal fun isHomeClickPendingExpired(
        nowMs: Long,
        pendingSetAtMs: Long,
        timeoutMs: Long = HOME_PENDING_SUPPRESS_TIMEOUT_MS
    ): Boolean {
        if (pendingSetAtMs <= 0L) return false
        return nowMs - pendingSetAtMs >= timeoutMs
    }

    fun isCurrentHomeClickPendingExpired(nowMs: Long = System.currentTimeMillis()): Boolean {
        return isHomeClickPendingExpired(nowMs, homeClickPendingSetAt)
    }

    internal fun shouldClearPendingOnAccessibilityReentry(
        isFromAccessibility: Boolean,
        pendingPackageName: String?,
        incomingPackageName: String?
    ): Boolean {
        if (!isFromAccessibility) return false
        if (pendingPackageName.isNullOrBlank()) return false
        return pendingPackageName == incomingPackageName
    }

    fun onBlacklistHit(
        context: Context,
        packageName: String? = null,
        isFromAccessibility: Boolean = false,
        isScreenOnRedetection: Boolean = false,
        createLockScreen: Boolean = true
    ): Boolean {
        val now = System.currentTimeMillis()

        // Fast path: during user-action grace, skip all heavy checks/log spam to keep UI responsive.
        if (now < mDismissUntil) {
            return false
        }

        if (shouldPauseForegroundBlockingChecks()) {
            return false
        }

        Log.w(TAG, "========================================")
        Log.w(TAG, "[DEBUG] onBlacklistHit CALLED")
        Log.w(TAG, "[DEBUG] pkg=$packageName")
        Log.w(TAG, "[DEBUG] BEFORE: totalHits=$totalSegmentBlacklistHits")
        Log.w(TAG, "[DEBUG] isLocked=$isLocked")

        checkSegmentChange(context)

        if (now < mDismissUntil) {
            Log.w(TAG, "[DEBUG] SKIP: User Action Grace active (Home Clicked) - Allow exit")
            return false
        }

        if (shouldClearPendingOnAccessibilityReentry(
                isFromAccessibility = isFromAccessibility,
                pendingPackageName = homeClickPendingPackage,
                incomingPackageName = packageName
            )
        ) {
            Log.w(TAG, "[DEBUG] CLEAR: real accessibility re-entry detected for pending pkg=$packageName")
            clearHomeClickPendingPackage()
        }

        if (!isFromAccessibility && homeClickPendingPackage != null && homeClickPendingPackage == packageName) {
            // [Fix] 加入超时检查：pending 超过 5 秒后自动失效，
            // 防止用户主动重新打开 App 时锁屏被无限期阻止
            if (!isHomeClickPendingExpired(now, homeClickPendingSetAt)) {
                Log.w(TAG, "[DEBUG] SKIP: homeClickPendingPackage=$homeClickPendingPackage still matches (carrier/UsageStats replay)")
                return false
            } else {
                Log.w(TAG, "[DEBUG] CLEAR: homeClickPendingPackage expired (>${HOME_PENDING_SUPPRESS_TIMEOUT_MS}ms), clearing")
                failPendingReturnDesktopExit(
                    packageName = packageName,
                    evidence = "PENDING_TIMEOUT foreground=$packageName"
                )
                clearHomeClickPendingPackage()
            }
        }

        val currentSeg = TimeRuleManager.getCurrentSegment(context)
        val isGap = currentSeg == null
        val isRest = CreditManager.isFreeTime(context)
        val isPrivilege = CreditManager.isWeeklyPrivilegeSessionActive(context)

        if (isPrivilege) {
            Log.w(TAG, "[DEBUG] Privilege active - SKIP creating new lock (no auto-dismiss)")
            return false
        }

        if (isGap || isRest) {
            val isAppWindowBlocked = if (packageName != null && AppWindowManager.isManaged(context, packageName)) {
                !AppWindowManager.isAppAllowed(context, packageName)
            } else {
                false
            }

            val shouldCountPenalty = shouldCountPenaltyForGapOrRest(
                isGap = isGap,
                isRest = isRest,
                isAppWindowBlocked = isAppWindowBlocked
            )
            if (!shouldCountPenalty) {
                Log.w(TAG, "[DEBUG] Gap/Rest - AppWindow unlocked, SKIP creating new lock: Gap=$isGap, Rest=$isRest")
                return false
            }

            if (isRest && isAppWindowBlocked) {
                Log.w(TAG, "[DEBUG] Rest + AppWindow blocked - keep regular lock counting")
            }
        }

        // If lock is already visible, keep lock and do not count again.
        // 🚀 [FIX] 验证 View 是否真的附着在窗口上，防止 isLocked=true 但 View 已被系统移除的"幽灵锁"状态。
        // 此状态会在空档期结束后导致锁屏无法重新弹出。
        if (isLocked) {
            val viewAttached = mLockView?.parent != null
            // [WP-1] Classify the currently attached lock against the live generation.
            // A blacklist re-hit that finds an overlay from a dead generation must
            // rebuild instead of short-circuiting as LOCK_ALREADY_ATTACHED.
            // Anti-uninstall identity is preserved across generations.
            val generationDecision = classifyLockGenerationRequest(
                isLocked = true,
                isAttached = viewAttached,
                attachedGeneration = mAttachedLockGeneration,
                currentGeneration = CoreService.getAccessibilityGeneration(),
                existingIsAntiUninstall = isAntiUninstallLock,
                incomingIsAntiUninstall = false
            )
            when (generationDecision) {
                "ANTI_UNINSTALL_PRESERVE" -> {
                    Log.w(TAG, "[DEBUG] RESULT: Anti-uninstall lock preserved across generations, return LOCK")
                    return true
                }
                "STALE_REBUILD" -> {
                    Log.w(TAG, "[DEBUG] RESULT: Stale lock (detached or dead generation, attached=$mAttachedLockGeneration current=${CoreService.getAccessibilityGeneration()}), resetting")
                    resetLockState("StaleLockState_onBlacklistHit")
                    // 继续执行后续逻辑创建新锁屏
                }
                else -> {
                    Log.w(TAG, "[DEBUG] RESULT: Already locked, return LOCK (no count change)")
                    return true
                }
            }
        }

        if (now - lastBlacklistHitTime < HIT_COUNT_DEBOUNCE_MS) {
            Log.w(TAG, "[DEBUG] SKIP: Debounce (${now - lastBlacklistHitTime}ms < ${HIT_COUNT_DEBOUNCE_MS}ms)")
            return false
        }
        lastBlacklistHitTime = now

        Log.w(TAG, "[DEBUG] === VALID HIT - INCREMENTING COUNTS ===")
        totalSegmentBlacklistHits++
        Log.w(TAG, "[DEBUG] totalSegmentBlacklistHits++ => $totalSegmentBlacklistHits")

        // 用户新规则：取消“三次命中进入强制锁机”的升级链路，保持常规锁机即可。
        resetConsecutiveNormalLockState("regular_lock_only")
        Log.w(TAG, "[DEBUG] RESULT: Regular lock only (strict penalty disabled by user setting)")
        val appLabel = resolveDisplayAppLabel(
            rawReason = "Regular Lock",
            appLabel = null,
            blockedPackageName = packageName,
            packageLabelResolver = { pkg ->
                try {
                    val pm = context.packageManager
                    pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
                } catch (_: Exception) { null }
            }
        )
        if (createLockScreen) {
            val isAppWindowHit = if (packageName != null && AppWindowManager.isManaged(context, packageName)) {
                !AppWindowManager.isAppAllowed(context, packageName)
            } else {
                false
            }
            val isCurrentBlacklist = if (packageName != null) {
                val currentSeg = TimeRuleManager.getCurrentSegment(context)
                if (currentSeg != null && currentSeg.listApps != null) {
                    SegmentBlacklist.isInEffectiveList(context, currentSeg, packageName)
                } else {
                    CreditManager.getBlacklistApps(context).any { it.equals(packageName, ignoreCase = true) }
                }
            } else {
                true
            }
            showLockScreen(
                reason = "Regular Lock",
                isBlacklist = isCurrentBlacklist,
                isAppWindowBlocked = isAppWindowHit,
                forceBypassGrace = false,
                blockedAppLabel = appLabel,
                blockedPackageName = packageName,
                isScreenOnRedetection = isScreenOnRedetection,
                detectionSource = if (isFromAccessibility) "ACCESSIBILITY_RECHECK" else "LOCKWINDOW_RECHECK"
            )
        }
        return true
    }

    internal fun shouldSkipLockAttachPending(
        isAttachPending: Boolean,
        pendingAgeMs: Long,
        timeoutMs: Long
    ): Boolean {
        if (!isAttachPending) return false
        if (pendingAgeMs < 0L) return false
        return pendingAgeMs < timeoutMs
    }

    internal fun shouldReassertVisibleLock(
        isLocked: Boolean,
        isAttached: Boolean,
        existingBlacklistLock: Boolean,
        incomingBlacklistLock: Boolean,
        systemDialogVisible: Boolean
    ): Boolean {
        if (!isLocked || !isAttached) return false
        if (!existingBlacklistLock || !incomingBlacklistLock) return false
        if (systemDialogVisible) return false
        return true
    }

    /**
     * [WP-1] Pure classification seam for an incoming lock request against the
     * currently attached overlay's accessibility generation.
     *
     * Priority order (highest first):
     *   1. Not locked / no live overlay              -> NOT_LOCKED
     *   2. Existing OR incoming anti-uninstall lock  -> ANTI_UNINSTALL_PRESERVE
     *      (anti-uninstall identity outranks blacklist identity across generations;
     *       it is never reset or downgraded by an incoming blacklist request)
     *   3. Detached view, or attached generation differs from current generation
     *                                                 -> STALE_REBUILD
     *   4. Same generation, still attached, ordinary  -> SAME_GENERATION_DUPLICATE
     *
     * JVM-testable: no WindowManager / Context dependency.
     */
    internal fun classifyLockGenerationRequest(
        isLocked: Boolean,
        isAttached: Boolean,
        attachedGeneration: Int,
        currentGeneration: Int,
        existingIsAntiUninstall: Boolean,
        incomingIsAntiUninstall: Boolean
    ): String {
        if (!isLocked) return "NOT_LOCKED"
        // Anti-uninstall identity is protected regardless of generation so a dead
        // generation cannot strand it and a blacklist request cannot downgrade it.
        if (existingIsAntiUninstall || incomingIsAntiUninstall) return "ANTI_UNINSTALL_PRESERVE"
        // Ghost lock (state says locked but overlay detached) or a lock stamped by a
        // previous (dead) accessibility generation must be rebuilt.
        if (!isAttached) return "STALE_REBUILD"
        if (attachedGeneration != currentGeneration) return "STALE_REBUILD"
        return "SAME_GENERATION_DUPLICATE"
    }

    /**
     * [WP-1 / A5] Pure screen-off dismissal guard. An anti-uninstall lock
     * (isBlacklistLock == false) must survive screen-off; only ordinary blacklist
     * overlays are dismissed. Alarm overlays are never dismissed here.
     */
    internal fun shouldDismissForScreenOff(
        isAlarmOverlayActive: Boolean,
        isLocked: Boolean,
        isBlacklistLock: Boolean
    ): Boolean {
        // Screen-off is not one of the four explicit exits. Preserve every visible lock.
        return false
    }

    private fun reassertAttachedLockVisibility(reason: String) {
        val requestAt = System.currentTimeMillis()
        if (requestAt - lastLockReassertAt < LOCK_REASSERT_DEBOUNCE_MS) {
            return
        }
        lastLockReassertAt = requestAt
        Handler(Looper.getMainLooper()).post {
            val context = mContext ?: return@post
            val lockView = mLockView ?: return@post
            val activeWm = mActiveWindowManager ?: return@post
            if (lockView.parent == null || !isLocked || isSystemDialogVisible || isAlarmActiveForLockSuppression()) return@post

            try {
                val useA11y = mCoreService != null && isServiceActive()
                val targetWm = if (useA11y) {
                    mCoreService!!.getSystemService(Context.WINDOW_SERVICE) as WindowManager
                } else {
                    mWindowManager ?: activeWm
                }
                val normalParams = buildNormalLockParams(useA11y)
                val currentParams = lockView.layoutParams as? WindowManager.LayoutParams
                val shouldMoveWindowManager =
                    targetWm !== activeWm &&
                        currentParams?.type != normalParams.type

                if (shouldMoveWindowManager) {
                    // WindowManager 切换必须保留可见性：目标 addView 失败时立即回滚到
                    // 原窗口，避免“先摘下、后失败”造成保护层短暂游离。
                    val previousParams = currentParams ?: normalParams
                    try {
                        lockView.clearFocus()
                        activeWm.removeView(lockView)
                        targetWm.addView(lockView, normalParams)
                        mActiveWindowManager = targetWm
                    } catch (moveError: Exception) {
                        val rollbackSucceeded = try {
                            if (lockView.parent == null) {
                                activeWm.addView(lockView, previousParams)
                            }
                            mActiveWindowManager = activeWm
                            lockView.parent != null
                        } catch (rollbackError: Exception) {
                            Log.e(TAG, "[Reassert] lock view rollback failed", rollbackError)
                            false
                        }
                        if (!rollbackSucceeded) throw moveError
                        Log.w(TAG, "[Reassert] target WindowManager attach failed; rolled back", moveError)
                        LogExporter.logEvent("LockScreen", "[REASSERT_ROLLED_BACK] reason=$reason")
                        return@post
                    }
                } else {
                    // updateViewLayout 轻量刷新窗口参数，不会导致闪烁。
                    // 如果系统静默隐藏了悬浮窗，onBlacklistHit 中的 stale 检测
                    // 会自动重置 isLocked 并创建新视图。
                    activeWm.updateViewLayout(lockView, normalParams)
                }

                lockView.alpha = 1.0f
                clearSystemDialogCompatState()
                tickLightweightUI()
                updateWindowFocus(true)
                val now = System.currentTimeMillis()
                val previousAttachAt = lastLockAttachSuccessAt
                lastLockAttachSuccessAt = now
            } catch (e: Exception) {
                resetLockState("ReassertFailed")
            }
        }
    }
    
    // Redundant flags and monitoring logic removed to consolidate into "Immediate Lock + Post-Grace"
    private var isGraceBufferRunning = false
    private var isWaitingForUserConfirmation = false

    fun cancelGraceBuffer() {
        // No-op for now, kept for API compatibility with CoreService calls
        Log.d(TAG, "cancelGraceBuffer called (no-op in immediate lock mode)")
    }
    
    // 🚀 [Rule] 3s Grace Period when returning from Launcher
    // Called by CoreService when Launcher is detected
    fun extendLauncherGracePeriod() {
        val now = System.currentTimeMillis()
        // Improve: Only extend if needed to avoid redundant writes (though Volatile is fast)
        // Ensure we have at least 3s from now
        val target = now + HOME_GRACE_PERIOD_MS
        if (target > mDismissUntil) {
           mDismissUntil = target
        }
    }
    
    private fun performGrant(
        context: Context,
        requestedMins: Double,
        costMins: Double,
        isBorrowed: Boolean,
        countCost: Int = 1,
        quotaAlreadyDeducted: Boolean = false
    ) {
        // 核心扣费与授权流程
        if (!quotaAlreadyDeducted) CreditManager.consumeMinutes(context, costMins)

        // 🆕 申请休息消耗可用次数（冷却期加权）
        if (!quotaAlreadyDeducted) {
            for (i in 0 until countCost) {
                CreditManager.consumeUsableCount(context)
            }
        }

        if (CreditManager.isCooling(context) || getTemporaryReuseCooldownRemainingSeconds() > 0L) {
            CreditManager.recordCooldownAttemptEvent(context)
        }

        mMonitorService?.grantFreeTime((requestedMins * 60 * 1000L).toLong(), costMins) ?: run {
            Log.e(TAG, "MonitorService not attached! Using fallback to save FreeTime.")
            CreditManager.setFreeTimeEnd(context, System.currentTimeMillis() + requestedMins.toLong() * 60 * 1000L)
            CreditManager.recordRestEvent(context)
        }
        resetConsecutiveNormalLockState("free_time_granted")
        totalSegmentBlacklistHits = 0
        dismissLockScreen(LockDismissAuthority.REST) // 🚀 [Fix] Force dismiss for granted rest

        val untilTime = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date(System.currentTimeMillis() + requestedMins.toLong() * 60 * 1000L))
        val remainMins = CreditManager.getRemainingMinutes(context)
        val remainCount = CreditManager.getRemainingUsableCount(context)
        LogExporter.i("Rest", "休息: ${requestedMins}min | 扣费=${costMins}min/${countCost}次 | 截止=$untilTime | 剩余=${remainMins}min/${remainCount}次 | 借用=$isBorrowed")

        RestStatsRecorder.record(
            context = context,
            type = if (isBorrowed) "BORROW" else "NORMAL",
            typeName = if (isBorrowed) "借用休息" else "常规休息",
            reason = if (isBorrowed) "预支未来时段额度" else "常规申请休息",
            requestedMinutes = requestedMins,
            actualMinutes = requestedMins,
            costMinutes = costMins,
            costCount = countCost.toDouble(),
            balanceMinutes = remainMins,
            balanceCount = remainCount
        )

        val msg = if (isBorrowed) "预支成功！开启休息: $requestedMins 分钟" else "开启休息: $requestedMins 分钟"
        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
    }

    private fun showBorrowConfirmDialog(context: Context, requestedMins: Double, totalCost: Double, borrowMins: Double) {
        val nextSeg = TimeRuleManager.getNextSegment(context) ?: return

        // 加权计算
        val requestedSec = (requestedMins * 60).toInt()
        val isInRestCooling = CreditManager.isCooling(context)
        val coolRemainSec = if (isInRestCooling) {
            val coolEndMs = CreditManager.getCoolDownEnd(context)
            val nowMs = System.currentTimeMillis()
            maxOf(0, ((coolEndMs - nowMs + 999) / 1000).toInt())
        } else 0
        val coolingOverlapSec = minOf(requestedSec, coolRemainSec)
        val dndOverlapSec = requestedSec - coolingOverlapSec
        val weightedSec = CreditManager.calculateWeightedBorrowSeconds(coolingOverlapSec, dndOverlapSec, 0)
        val weightedCounts = CreditManager.calculateWeightedBorrowCounts(weightedSec, requestedSec)

        val nextCountLimitHalfUnits = nextSeg.getEffectiveUsableCountLimit()  // halfUnits
        val alreadyBorrowedCountHalfUnits = CreditManager.getBorrowedUsableCountFromNext(context)  // halfUnits
        val weightedCountsHalfUnits = weightedCounts * 2  // 次数 → halfUnits
        val deductMin = weightedSec / 60.0
        val alreadyBorrowedMin = CreditManager.getBorrowedMinutesFromNext(context)
        val remainMin = (nextSeg.limitMins - alreadyBorrowedMin - deductMin).coerceAtLeast(0.0)
        val remainCountHalfUnits = (nextCountLimitHalfUnits - alreadyBorrowedCountHalfUnits - weightedCountsHalfUnits).coerceAtLeast(0)
        val currentUseMin = (totalCost - borrowMins).coerceAtLeast(0.0)

        fun fmt(v: Double): String = if (v == v.toLong().toDouble()) v.toLong().toString() else "%.1f".format(v)

        val isA11y = isServiceActive()
        val dialogContext = if (isA11y) mCoreService!! else context
        val isBorrowing = borrowMins > 0

        // 纯冷却无借用：简化版（只显示实际消耗）
        if (!isBorrowing) {
            val titleCool = if (isInRestCooling) "⚠️ 冷却期确认" else "📋 休息确认"
            val coolMsg = buildString {
                append("申请: ${fmt(requestedMins)} 分钟\n")
                append("─────────────────────\n")
                append("当前额度足够，无需借用下一段\n")
                if (isInRestCooling) {
                    append("冷却期内申请，实际消耗加倍\n")
                    append("实际消耗: ${fmt(totalCost)} 分钟")
                    if (coolingOverlapSec > 0) {
                        append("（冷却重叠 ${coolingOverlapSec}s ×2）")
                    }
                }
            }
            val coolDialog = AlertDialog.Builder(dialogContext)
                .setTitle(titleCool)
                .setMessage(coolMsg.toString())
                .setPositiveButton("确认开启") { _, _ ->
                    performGrant(context, requestedMins, totalCost, false, 0)
                }
                .setNegativeButton("取消", null)
                .create()
            if (isA11y) coolDialog.window?.setType(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY)
            else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) coolDialog.window?.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
            else coolDialog.window?.setType(WindowManager.LayoutParams.TYPE_PHONE)
            coolDialog.window?.clearFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)
            coolDialog.show()
            coolDialog.applyEscapeDisciplineColors()
            return
        }

        // 借用模式：完整六行链条
        val borrowPercent = TimeRuleManager.getBorrowPercentage(context)
        // nextCountLimit/halfUnits、maxBorrowCount/halfUnits、alreadyBorrowedCount/halfUnits、weightedCounts/halfUnits
        val (maxBorrowMins, maxBorrowCountHalfUnits) = computeMaxBorrowableForNextSegment(nextSeg.limitMins, nextCountLimitHalfUnits, borrowPercent)
        val afterBorrowMin = (maxBorrowMins - alreadyBorrowedMin - borrowMins).coerceAtLeast(0.0)
        val afterBorrowCountHalfUnits = (maxBorrowCountHalfUnits - alreadyBorrowedCountHalfUnits - weightedCountsHalfUnits).coerceAtLeast(0)

        fun fmtCount(hu: Int): String = formatUsableCountHalfUnits(hu)

        // 默认视图（参考锁屏下一阶段卡片：总配额→借用配额→已预支→本次预支→惩罚扣除→剩余）
        val summaryMsg = buildString {
            append("申请: ${fmt(requestedMins)} 分钟\n")
            append("─────────────────────\n")
            append("下一段 (${nextSeg.name}):\n")
            append("  总配额:        ${nextSeg.limitMins} 分钟  /  ${fmtCount(nextCountLimitHalfUnits)} 次\n")
            append("  最多可借:      ${fmt(maxBorrowMins)} 分钟  /  ${fmtCount(maxBorrowCountHalfUnits)} 次\n")
            append("  已预支:        ${fmt(alreadyBorrowedMin)} 分钟  /  ${fmtCount(alreadyBorrowedCountHalfUnits)} 次\n")
            append("─────────────────────\n")
            append("  本次预支:      ${fmt(borrowMins)} 分钟  /  ${fmtCount(weightedCountsHalfUnits)} 次\n")
            append("  惩罚扣除:      ${fmt(deductMin)} 分钟  /  ${fmtCount(weightedCountsHalfUnits)} 次")
            if (isInRestCooling) append("  [含冷却×2]")
            append("\n")
            append("  预支后剩余:    ${fmt(afterBorrowMin)} 分钟  /  ${fmtCount(afterBorrowCountHalfUnits)} 次\n")
        }

        // 明细：加权计算过程，特别标注冷却期重叠部分
        val detailMsg = buildString {
            append(summaryMsg)
            append("─────────────────────\n")
            append("▸ 加权明细\n")
            append("  申请 ${requestedSec}s（冷却${coolingOverlapSec}s + 勿扰${dndOverlapSec}s）\n")
            append("  惩罚 = ×2 × [×2(冷却${coolingOverlapSec}s) + ×1(勿扰${dndOverlapSec}s) + ×0(空档0s)]\n")
            append("       = 2 × ${coolingOverlapSec * 2 + dndOverlapSec} = ${weightedSec}s\n")
            if (isInRestCooling && coolingOverlapSec > 0) {
                append("  ⚠️ ${coolingOverlapSec}s 落在冷却期，按 ×2 加权\n")
            }
            append("  次数 = ${weightedSec}s / ${requestedSec}s ≈ ${fmtCount(weightedCountsHalfUnits)} 次(四舍五入)\n")
            append("\n📍 预支对象: ${nextSeg.name} (${nextSeg.formatTimeRange()})")
        }

        val title = if (isInRestCooling) "⚠️ 借用额度确认（冷却期）" else "📋 借用额度确认"

        var showDetail = false
        val dialog = AlertDialog.Builder(dialogContext)
            .setTitle(title)
            .setMessage(summaryMsg.toString())
            .setPositiveButton("确认开启") { _, _ ->
                if (borrowMins > 0) {
                    CreditManager.borrowFromNext(context, borrowMins, coolingOverlapSec, dndOverlapSec, 0)
                }
                if (weightedCountsHalfUnits > 0) {
                    CreditManager.borrowCountFromNext(context, weightedCountsHalfUnits)
                }
                performGrant(context, requestedMins, totalCost, true, weightedCountsHalfUnits)
            }
            .setNeutralButton("查看详情 ▸") { d, _ ->
                showDetail = !showDetail
                (d as? AlertDialog)?.apply {
                    setButton(AlertDialog.BUTTON_NEUTRAL, if (showDetail) "收起详情 ▾" else "查看详情 ▸", null as? android.content.DialogInterface.OnClickListener)
                    setMessage(if (showDetail) detailMsg.toString() else summaryMsg.toString())
                }
            }
            .setNegativeButton("取消", null)
            .create()

        if (isA11y) {
            dialog.window?.setType(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            dialog.window?.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
        } else {
            dialog.window?.setType(WindowManager.LayoutParams.TYPE_PHONE)
        }
        dialog.window?.clearFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        dialog.show()
        dialog.applyEscapeDisciplineColors()
    }

    private fun formatQuotaMinutes(seconds: Int): String =
        String.format(Locale.US, "%.1f", seconds.coerceAtLeast(0) / 60.0)

    private fun showQuotaTransactionConfirmDialog(
        context: Context,
        title: String,
        actionSummary: String,
        requestedSec: Int,
        freeSec: Int,
        freeLabel: String,
        dndSec: Int,
        coolingSec: Int,
        plan: TemporaryAccessQuotaPlan,
        confirmText: String,
        onConfirm: () -> Unit
    ) {
        val isA11y = isServiceActive()
        val dialogContext = if (isA11y) mCoreService!! else context
        val view = LayoutInflater.from(dialogContext).inflate(R.layout.dialog_quota_transaction_confirm, null)
        val dialog = AlertDialog.Builder(dialogContext).setView(view).create()

        val isInCooling = coolingSec > 0
        view.findViewById<TextView>(R.id.tv_quota_dialog_title).text = title
        view.findViewById<TextView>(R.id.tv_quota_dialog_subtitle).text = actionSummary
        view.findViewById<TextView>(R.id.tv_required_time).text =
            if (isInCooling) "${formatQuotaMinutes(plan.totalTimeCostSec)} 分钟（冷却翻倍 ×2）"
            else "${formatQuotaMinutes(plan.totalTimeCostSec)} 分钟"
        view.findViewById<TextView>(R.id.tv_requested_time).text = "申请 ${formatQuotaMinutes(requestedSec)} 分钟"
        view.findViewById<TextView>(R.id.tv_required_count).text =
            if (isInCooling) "次数消耗  ${formatUsableCountHalfUnits(plan.totalCountCostHalfUnits)}次（冷却翻倍 ×2）"
            else "次数消耗  ${formatUsableCountHalfUnits(plan.totalCountCostHalfUnits)}次（≤3分钟扣0.5次，>3分钟扣1次）"

        val formulaRows = view.findViewById<LinearLayout>(R.id.ll_quota_formula_rows)
        val density = dialogContext.resources.displayMetrics.density
        fun addFormulaRow(label: String, sourceSec: Int, multiplier: Int) {
            if (sourceSec <= 0) return
            val row = LinearLayout(dialogContext).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, (3 * density).toInt(), 0, (3 * density).toInt())
            }
            row.addView(TextView(dialogContext).apply {
                text = "$label ${formatQuotaMinutes(sourceSec)}分钟 ×$multiplier"
                setTextColor(ThemeManager.getColorAttr(dialogContext, R.attr.zzText))
                textSize = 12f
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(TextView(dialogContext).apply {
                text = "${formatQuotaMinutes(sourceSec * multiplier)}分钟"
                setTextColor(ThemeManager.getColorAttr(dialogContext, R.attr.zzAccentStrong))
                textSize = 12f
                setTypeface(typeface, Typeface.BOLD)
            })
            formulaRows.addView(row)
        }

        if (freeSec > 0) {
            addFormulaRow(freeLabel, quantizeQuotaSecondsToTenthMinute(freeSec), 0)
        }
        val billableSec = (requestedSec - freeSec).coerceAtLeast(0)
        if (billableSec > 0) {
            val mult = if (isInCooling) 2 else 1
            val label = if (isInCooling) "冷却期申请 (整单翻倍)" else "基础申请 (单倍)"
            addFormulaRow(label, quantizeQuotaSecondsToTenthMinute(billableSec), mult)
        }

        view.findViewById<TextView>(R.id.tv_quota_source_header).text =
            "扣费来源    当前可用 ${formatQuotaAvailableMinutes(plan.currentAvailableSec)}分钟 · ${formatUsableCountHalfUnits(plan.currentAvailableCountHalfUnits)}次"
        val afterCurrentSec = (plan.currentAvailableSec - plan.currentTimeCostSec).coerceAtLeast(0)
        val afterCurrentCount = (plan.currentAvailableCountHalfUnits - plan.currentCountCostHalfUnits).coerceAtLeast(0)
        val paymentText = "${formatQuotaMinutes(plan.currentTimeCostSec)}分钟 · ${formatUsableCountHalfUnits(plan.currentCountCostHalfUnits)}次"
        val remainingText = "扣后剩余 ${formatQuotaMinutes(afterCurrentSec)}分钟 · ${formatUsableCountHalfUnits(afterCurrentCount)}次"

        val single = view.findViewById<LinearLayout>(R.id.ll_quota_single_source)
        val dual = view.findViewById<LinearLayout>(R.id.ll_quota_dual_source)
        val principalNote = view.findViewById<TextView>(R.id.tv_borrow_principal_note)
        if (plan.needsNextStage) {
            single.visibility = View.GONE
            dual.visibility = View.VISIBLE
            principalNote.visibility = View.VISIBLE
            view.findViewById<TextView>(R.id.tv_current_payment).text = paymentText
            view.findViewById<TextView>(R.id.tv_current_remaining).text = remainingText
            view.findViewById<TextView>(R.id.tv_next_payment).text =
                "${formatQuotaMinutes(plan.nextTimeCostSec)}分钟 · ${formatUsableCountHalfUnits(plan.nextCountCostHalfUnits)}次"
            view.findViewById<TextView>(R.id.tv_next_debt_formula).text =
                "借入 ${formatQuotaMinutes(plan.borrowedTimePrincipalSec)}分钟 / ${formatUsableCountHalfUnits(plan.borrowedCountPrincipalHalfUnits)}次，按×2偿还"
            principalNote.text =
                "本次借入本金：${formatQuotaMinutes(plan.borrowedTimePrincipalSec)}分钟 · ${formatUsableCountHalfUnits(plan.borrowedCountPrincipalHalfUnits)}次"
        } else {
            single.visibility = View.VISIBLE
            dual.visibility = View.GONE
            principalNote.visibility = View.GONE
            view.findViewById<TextView>(R.id.tv_single_payment).text = paymentText
            view.findViewById<TextView>(R.id.tv_single_remaining).text = remainingText
        }

        view.findViewById<Button>(R.id.btn_quota_cancel).setOnClickListener { dialog.dismiss() }
        view.findViewById<Button>(R.id.btn_quota_confirm).apply {
            text = confirmText
            setOnClickListener {
                isEnabled = false
                onConfirm()
                dialog.dismiss()
            }
        }

        configureOverlayDialogWindow(dialog, isA11y)
        dialog.show()
        dialog.window?.setLayout(
            (dialogContext.resources.displayMetrics.widthPixels * 0.92).toInt(),
            WindowManager.LayoutParams.WRAP_CONTENT
        )
    }

    private fun showTemporaryAccessQuotaConfirmDialog(
        context: Context,
        rawSeconds: Int,
        maxSeconds: Int,
        plan: TemporaryAccessQuotaPlan,
        freeSec: Int,
        dndSec: Int,
        coolingSec: Int,
        freeLabel: String
    ) {
        val touchesCooling = coolingSec > 0
        showQuotaTransactionConfirmDialog(
            context = context,
            title = if (plan.needsNextStage && touchesCooling) "冷却借用确认" else if (plan.needsNextStage) "借用额度确认" else "冷却翻倍确认",
            actionSummary = "临时使用 ${formatQuotaMinutes(rawSeconds)} 分钟",
            requestedSec = rawSeconds,
            freeSec = freeSec,
            freeLabel = freeLabel,
            dndSec = dndSec,
            coolingSec = coolingSec,
            plan = plan,
            confirmText = "确认使用"
        ) {
            if (CreditManager.applyTemporaryAccessQuotaPlan(context, plan)) {
                startTemporaryAccess(
                    context,
                    rawSeconds,
                    maxSeconds,
                    skipBorrowDialog = true,
                    quotaAlreadyDeducted = true,
                    prechargedPlan = plan
                )
            }
        }
    }

    private fun showTemporaryAccessQuotaBlockedDialog(
        context: Context,
        plan: TemporaryAccessQuotaPlan,
        touchesCooling: Boolean
    ) {
        val isA11y = isServiceActive()
        val dialogContext = if (isA11y) mCoreService!! else context
        fun secText(seconds: Int): String = "${formatTemporaryAccessSecondsAsMinutes(seconds)} 分钟"
        val msg = buildString {
            if (touchesCooling) {
                append("本次申请触碰冷却期后，需要扣到下一阶段，但下一阶段额度不足。\n\n")
            } else {
                append("本次申请需要扣到下一阶段，但下一阶段额度不足。\n\n")
            }
            append("请等冷却结束后再次尝试。\n")
            append("─────────────────────\n")
            append("            需要扣除      可用额度\n")
            if (plan.hasInsufficientTime) {
                append("时长        ${secText(plan.nextTimeCostSec)}      ${secText(plan.nextTimeCapacitySec)}\n")
            }
            if (plan.hasInsufficientCount) {
                append("次数        ${formatUsableCountHalfUnits(plan.nextCountCostHalfUnits)} 次          ${formatUsableCountHalfUnits(plan.nextCountCapacityHalfUnits)} 次\n")
            }
        }
        val dialog = AlertDialog.Builder(dialogContext)
            .setTitle("额度不足")
            .setMessage(msg)
            .setPositiveButton("知道了", null)
            .create()
        configureOverlayDialogWindow(dialog, isA11y)
        dialog.show()
        dialog.applyEscapeDisciplineColors()
    }

    private fun configureOverlayDialogWindow(dialog: AlertDialog, isA11y: Boolean) {
        if (isA11y) {
            dialog.window?.setType(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            dialog.window?.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
        } else {
            dialog.window?.setType(WindowManager.LayoutParams.TYPE_PHONE)
        }
        dialog.window?.clearFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)
    }

    private fun showPrivilegeAdjustDialog(context: Context) {
        val isA11y = isServiceActive()
        val baseCtx = if (isA11y) mCoreService!! else context
        val dialogContext = android.view.ContextThemeWrapper(baseCtx, ThemeManager.themeRes(baseCtx))
        val currentDuration = CreditManager.getCurrentPrivilegeDuration(context)
        val remainingDur = CreditManager.getRemainingWeeklyPrivilegeDuration(context)
        val maxAvailable = minOf(remainingDur + currentDuration, CreditManager.getWeeklyPrivilegeDurationLimit(context)).coerceAtLeast(0)
        val maxEffective = CreditManager.calculateWeeklyPrivilegeEffectiveMinutes(context, maxAvailable)
        val maxTotal = minOf(maxAvailable, maxEffective).coerceAtLeast(1)

        val view = LayoutInflater.from(dialogContext).inflate(R.layout.dialog_privilege_time_picker, null)
        val npHour = view.findViewById<android.widget.NumberPicker>(R.id.np_hour)
        val npMinute = view.findViewById<android.widget.NumberPicker>(R.id.np_minute)

        val maxHours = (maxTotal / 60).coerceAtMost(12)
        val maxMins = maxTotal % 60

        npHour.minValue = 0
        npHour.maxValue = maxHours
        npHour.value = currentDuration / 60

        npMinute.minValue = 0
        npMinute.maxValue = if (npHour.value == maxHours) maxMins.coerceAtLeast(1) else 59
        npMinute.value = (currentDuration % 60).coerceIn(npMinute.minValue, npMinute.maxValue)

        npHour.setOnValueChangedListener { _, _, newVal ->
            npMinute.maxValue = if (newVal == maxHours) maxMins.coerceAtLeast(1) else 59
        }

        val pickerDialog = AlertDialog.Builder(dialogContext)
            .setTitle("调整特权时长")
            .setMessage("当前时长: ${currentDuration}分钟\n请选择新的总时长")
            .setView(view)
            .setPositiveButton("确认") { _, _ ->
                val newDuration = npHour.value * 60 + npMinute.value
                if (newDuration <= 0) {
                    Toast.makeText(context, "时长必须大于0", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val success = CreditManager.adjustWeeklyPrivilegeDuration(context, newDuration)
                if (success) {
                    Toast.makeText(context, "特权时长已调整为 $newDuration 分钟", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(context, "调整失败，可能已超过5分钟窗口", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("取消", null)
            .create()

        if (isA11y) {
            pickerDialog.window?.setType(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            pickerDialog.window?.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
        } else {
            pickerDialog.window?.setType(WindowManager.LayoutParams.TYPE_PHONE)
        }

        pickerDialog.show()
        pickerDialog.applyEscapeDisciplineColors()
    }


    // NOTE:
    // 常规锁屏仅保留”申请休息/额度”；应用时间窗锁屏可进入”申请特权”。

    fun showReminderDialog(context: Context, title: String, message: String, isUrgent: Boolean) {
        // 🚀 [Fix] Dismiss any existing reminder dialog first
        dismissReminderDialog()

        val isA11y = isServiceActive()
        // 🚀 [Fix] Use Service Context if A11y is active
        val dialogContext = if (isA11y) mCoreService!! else context

        // Use system alert dialog
        val builder = AlertDialog.Builder(dialogContext)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("知道了", null)
            .setCancelable(!isUrgent) // Urgent (5m) is not cancelable via back key easily
            .setOnDismissListener { 
                mReminderDialog = null 
            }

        val dialog = builder.create()
        mReminderDialog = dialog // Track it
        
        if (isA11y) {
            dialog.window?.setType(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            dialog.window?.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
        } else {
            dialog.window?.setType(WindowManager.LayoutParams.TYPE_PHONE)
        }
        
        try {
            dialog.show()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to show reminder dialog", e)
        }
    }
    
    fun dismissReminderDialog() {
        if (mReminderDialog != null) {
            try {
                mReminderDialog?.dismiss()
            } catch (e: Exception) {
                Log.e(TAG, "dismissReminderDialog failed", e)
            } finally {
                mReminderDialog = null
            }
        }
    }

    /**
     * 给弹窗按钮统一着色：
     * - 左侧(Negative) = 守规路径 → 绿色 (希望用户点)
     * - 右侧(Positive) = 逃避路径 → 红色 (不想用户点)
     * 调用时机：必须在 dialog.show() 之后
     */
    private fun AlertDialog.applyEscapeDisciplineColors() {
        val dialogCtx = context
        val surfaceColor = ThemeManager.getColorAttr(dialogCtx, R.attr.zzSurface)
        val lineColor = ThemeManager.getColorAttr(dialogCtx, R.attr.zzLine)
        val density = dialogCtx.resources.displayMetrics.density
        val bg = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 20f * density
            setColor(surfaceColor)
            setStroke((1f * density).toInt().coerceAtLeast(1), lineColor)
        }
        window?.setBackgroundDrawable(bg)
        getButton(AlertDialog.BUTTON_NEGATIVE)?.setTextColor(0xFF2E7D32.toInt())
        getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(0xFFC62828.toInt())
    }
    
    // =========================================================
    // 🚀 [Helper] Usage Stats for Validation
    // =========================================================
    // 🚀 [Config Lock] Special Lock for Locale/Layout changes
    fun showConfigChangeLock(context: Context) {
        showLockScreen(
            reason = "ConfigChange", 
            isAntiUninstall = true,
            isBlacklist = false,
            forceBypassGrace = true
        )
        
        // Update UI text
        updateTimerText("决定守护防卸载功能正在初始化", "无障碍服务恢复后自动消失，请稍候...")
        
        // Hide details
        Handler(Looper.getMainLooper()).post {
             mLockView?.findViewById<View>(R.id.tv_stat_time_big)?.visibility = View.INVISIBLE
             mLockView?.findViewById<View>(R.id.tv_stat_time_detail)?.visibility = View.INVISIBLE
        }
    }

    // 黑名单锁监控：仅维护锁状态与线程生命周期，不做自动解锁
    private fun startBlacklistMonitor(context: Context, targetPkg: String?) {
        stopBlacklistMonitor()
        
        monitoredBlacklistPackage = targetPkg
        blacklistMonitorTimer = ensureTimerThread() // 🚀 使用独立后台线程
        blacklistMonitorRunnable = object : Runnable {
            override fun run() {
                // Stop if lock is no longer active or not blacklist lock
                if (!isLocked || !isBlacklistLock) {
                    stopBlacklistMonitor()
                    return
                }

                // 仅保持监控循环，解锁必须由按钮/申请流程触发
                blacklistMonitorTimer?.postDelayed(this, 1000L)  // 每秒检查一次
            }
        }
        blacklistMonitorTimer?.postDelayed(blacklistMonitorRunnable!!, 1000L)
        Log.d(TAG, "Blacklist Monitor: Started for package=$targetPkg, ctx=${context.packageName}")
    }

    // 🚀 [Blacklist Monitor] Stop monitoring
    private fun stopBlacklistMonitor() {
        blacklistMonitorRunnable?.let {
            blacklistMonitorTimer?.removeCallbacks(it)
        }
        blacklistMonitorRunnable = null
        blacklistMonitorTimer = null // 不 quit 线程，线程由 ensureTimerThread 管理
        monitoredBlacklistPackage = null
    }

    private fun getTopPackageName(context: Context): String? {
        try {
            val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val time = System.currentTimeMillis()

            // 🔧 [Fix] 优先使用 queryEvents 获取真实前台应用
            // queryUsageStats 的 lastTimeUsed 可能因后台活动而指向 launcher
            try {
                val events = usm.queryEvents(time - 30000, time)
                val event = android.app.usage.UsageEvents.Event()
                var topPkg: String? = null
                val MOVE_TO_FG = android.app.usage.UsageEvents.Event.MOVE_TO_FOREGROUND
                val MOVE_TO_BG = android.app.usage.UsageEvents.Event.MOVE_TO_BACKGROUND
                val ACTIVITY_RESUMED = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) 15 else -1
                val ACTIVITY_PAUSED  = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) 16 else -1

                while (events.hasNextEvent()) {
                    events.getNextEvent(event)
                    val type = event.eventType
                    if (type == MOVE_TO_FG || type == ACTIVITY_RESUMED) {
                        topPkg = event.packageName
                    } else if (type == MOVE_TO_BG || type == ACTIVITY_PAUSED) {
                        if (topPkg == event.packageName) topPkg = null
                    }
                }
                if (topPkg != null) return topPkg
            } catch (e: Exception) {
                Log.w(TAG, "queryEvents fallback failed", e)
            }

            // 兜底：queryUsageStats，优先非launcher
            val stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, time - 30000, time)
            if (stats.isNullOrEmpty()) return null
            
            val recent = stats.filter { it.lastTimeUsed >= time - 30000 && it.totalTimeInForeground > 0 }
            val nonLauncher = recent
                .filter { !CreditManager.isLauncher(it.packageName) && it.packageName != "com.android.systemui" }
                .maxByOrNull { it.lastTimeUsed }
            if (nonLauncher != null) return nonLauncher.packageName

            return recent.maxByOrNull { it.lastTimeUsed }?.packageName
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get top package usage", e)
            return null
        }
    }
}

/**
 * 顶层纯函数实现 — 放在 `LockWindowManager` object 外面，确保 JVM 单元测试可独立调用，
 * 不会触发 `object LockWindowManager` 的类初始化（其中包含 `Looper.getMainLooper()` 等 Android 调用）。
 */

/**
 * Req-1 扩展：返回文本中 offset 下标所在的"英文单词"。
 *
 * 用于选词填空题目段落的按词长按查词：给定一句文本和字符偏移，
 * 向左/右扩展到词边界后返回该词。只识别英文单词字符（含连写撇号，如 it's），
 * 所以中文、空白、标点以及选词空位 [    ] / ______ 处都会返回 null。
 *
 * @return 命中的英文单词；offset 越界 / 前后无词字符时返回 null。
 */
internal fun findWordAtIndex(text: String, offset: Int): String? {
    if (text.isEmpty()) return null
    val len = text.length
    if (offset < 0 || offset >= len) return null

    fun isWordChar(c: Char): Boolean = c.isLetter() && c.code < 128 || c == '\''

    if (!isWordChar(text[offset])) return null

    var start = offset
    var end = offset
    while (start > 0 && isWordChar(text[start - 1])) start--
    while (end < len - 1 && isWordChar(text[end + 1])) end++

    val word = text.substring(start, end + 1).trim('\'')
    return word.ifEmpty { null }
}

/**
 * 休息 / 临时解锁的计费次数。
 *
 * **口径（2026-09-11 产品决定）**：只要本次申请触及冷却期，就**整单翻倍**，
 * 不再按秒分区加权。原实现是 `round((dndSec + coolOverlapSec) / totalSec)`，
 * 会把"一半在空档、一半在冷却"这类混合场景算成 1 次而不是 2 次，与用户可见文案
 * 「时间与次数整单直接翻倍」不符。
 */
internal fun calculateWeightedRestCountImpl(
    freeSec: Int,
    dndSec: Int,
    coolOverlapSec: Int,
    totalSec: Int
): Int {
    if (totalSec <= 0) return 0
    // 防御性：冷却重叠不能超过 DND 总秒数；空档 + DND 应等于 total
    val safeCool = coolOverlapSec.coerceIn(0, dndSec.coerceAtLeast(0))
    val safeDnd = dndSec.coerceAtLeast(0)
    // 触及冷却期 → 整单翻倍（基础 1 次 × 2）
    if (safeCool > 0) return 2
    // 未触及冷却：仍按勿扰占比较计（空档 ×0、勿扰 ×1），保持既有行为
    val rounded = kotlin.math.floor(safeDnd / totalSec.toDouble() + 0.5).toInt()
    return rounded.coerceIn(0, 2)
}

/**
 * 纯 Kotlin 1位小数过滤器判定 Seam，供 JVM 单元测试与 InputFilter 共享。
 */
internal fun filterOneDecimal(
    source: CharSequence?,
    start: Int,
    end: Int,
    dest: CharSequence?,
    dstart: Int,
    dend: Int
): CharSequence? {
    val replacement = source?.subSequence(start, end)?.toString() ?: ""
    val destStr = dest?.toString() ?: ""
    val safeDstart = dstart.coerceIn(0, destStr.length)
    val safeDend = dend.coerceIn(safeDstart, destStr.length)
    val newText = destStr.substring(0, safeDstart) + replacement + destStr.substring(safeDend)
    if (newText.isEmpty()) return null
    val dotIndex = newText.indexOf('.')
    if (dotIndex >= 0) {
        val decimals = newText.length - 1 - dotIndex
        if (decimals > 1) return ""
        if (newText.indexOf('.', dotIndex + 1) >= 0) return ""
    }
    return null
}

/**
 * 限制仅允许输入非负数且小数点后最多 1 位数字（0.1分钟步进）的 InputFilter。
 */
internal fun createOneDecimalInputFilter(): InputFilter {
    return InputFilter { source, start, end, dest, dstart, dend ->
        filterOneDecimal(source, start, end, dest, dstart, dend)
    }
}

/**
 * 可用额度的保守截断格式化（秒 -> 保留 1 位小数的分钟），
 * 杜绝将 143 秒等带零头的物理时间四舍五入虚标成无法兑现的 2.4 分钟。
 */
internal fun formatQuotaAvailableMinutes(seconds: Int): String {
    val mins = kotlin.math.floor(seconds.coerceAtLeast(0) / 6.0) / 10.0
    return String.format(Locale.US, "%.1f", mins)
}

/**
 * 锁屏及弹窗可用额度的保守截断格式化（分钟浮点 -> 字符串），
 * 采用向下截断（Floor to 0.1min），杜绝将 2.383 分钟虚标为 2.4 分钟给用户开具无法足额兑现的空头支票。
 */
internal fun formatAvailableMinutesForLockImpl(minutes: Double): String {
    val floored = kotlin.math.floor(minutes.coerceAtLeast(0.0) * 10.0 + 1e-6) / 10.0
    return if (kotlin.math.abs(floored - floored.toInt()) < 0.0001) {
        floored.toInt().toString()
    } else {
        String.format(Locale.US, "%.1f", floored)
    }
}

internal fun quantizeQuotaSecondsToTenthMinute(seconds: Int): Int {
    if (seconds <= 0) return 0
    return kotlin.math.floor(seconds / 6.0 + 0.5).toInt() * 6
}

/**
 * 休息 / 临时解锁的计费时长（秒）。
 *
 * **口径（2026-09-11 产品决定）**：触及冷却期即**整单 ×2**，不再按秒分区加权。
 * 原实现 `normal + cooling × 2` 会把"一半在冷却"算成 1.5 倍，与用户可见文案
 * 「时间与次数整单直接翻倍（×2）」不符。
 *
 * 注：借用（`CreditManager.calculateWeightedBorrowSeconds`）**不在本次调整范围**，
 * 借用惩罚 ×2 与冷却 ×2 继续相乘。
 */
internal fun calculateWeightedQuotaSeconds(normalDndSec: Int, coolingSec: Int): Int {
    val normal = quantizeQuotaSecondsToTenthMinute(normalDndSec)
    if (coolingSec <= 0) return normal
    val cooling = quantizeQuotaSecondsToTenthMinute(coolingSec)
    return quantizeQuotaSecondsToTenthMinute(normal + cooling) * 2
}

internal enum class QuotaActionType { UNLOCK, REST }

internal fun calculateQuotaCountFromWeightedSeconds(
    weightedCostSec: Int,
    requestedSec: Int,
    actionType: QuotaActionType = QuotaActionType.UNLOCK
): Int {
    if (requestedSec <= 0) return 0
    // 基础次数：≤3分钟(180秒)扣 0.5 次 (1个 HalfUnit)，>3分钟扣 1.0 次 (2个 HalfUnits)
    val baseHalfUnits = if (requestedSec <= 180) 1 else 2
    // 整单翻倍(weightedCostSec >= requestedSec * 2)，次数直接翻倍；否则单倍
    val multiplier = if (weightedCostSec >= requestedSec * 2) 2 else 1
    return baseHalfUnits * multiplier
}

/**
 * 跨天段借用额度显示纯函数
 *
 * 背景：之前 4 处 UI（HomeFragment 空档/正常段、LockWindowManager 空档/休息申请）
 * 都对"最后一段（跨天段）"做了 if 分支硬编码 0/0，但 Issue_Tracker 05-23 明确写道：
 * "晚上仍可借用深夜段，深夜段不能再向次日借用" —— 即从跨天段借出是允许的，
 * UI 不应硬编码 0。CreditManager 内部 `isLastLogicalSegment` 检查的是 CURRENT
 * 段（借入方），不是 NEXT 段（借出方），所以 NEXT 是最后一段时仍应展示计算值。
 *
 * @param nextLimitMins  下一段时长配额（分钟，整数）
 * @param nextCountLimit 下一段次数配额（整数）
 * @param borrowPercent  借用比例（百分比，例如 50 表示 50%）
 * @return Pair(maxBorrowMinutes, maxBorrowCount)
 *         - maxBorrowMinutes：精度 0.1，公式 = nextLimitMins × (borrowPercent/100) / 2，half-up
 *         - maxBorrowCount：整数，公式 = nextCountLimit × (borrowPercent/100) / 2，half-up
 *
 * 输入越界保护：负数 / 0 都按 0 处理，避免 UI 显示负值。
 */
internal fun computeMaxBorrowableForNextSegment(
    nextLimitMins: Int,
    nextCountLimit: Int,
    borrowPercent: Int
): Pair<Double, Int> {
    // 时长展示为可申请的原始分钟数；借一还二会占用下一段偿还额度。
    val mins = kotlin.math.round(
        calculateMaxRawBorrowableMinutes(nextLimitMins, borrowPercent, alreadyBorrowedWeightedSec = 0) * 10.0
    ) / 10.0
    val count = calculateMaxRawBorrowableCount(nextCountLimit, borrowPercent, alreadyBorrowedWeightedCount = 0)
    return Pair(mins, count)
}

internal fun isTemporaryAccessMinuteInputValid(raw: String?, maxMinutes: Double): Boolean {
    val text = raw?.trim() ?: return false
    if (!Regex("""\d+(\.\d)?""").matches(text)) return false
    val minutes = text.toDoubleOrNull() ?: return false
    val safeMax = maxMinutes.coerceIn(0.1, 3.0)
    return minutes >= 0.1 && minutes <= safeMax
}

internal data class TemporaryAccessChargeSnapshot(
    val freeSec: Int,
    val dndSec: Int,
    val coolingSec: Int,
    val normalDndSec: Int,
    val weightedTimeCostSec: Int,
    val weightedCountHalfUnits: Int
)

internal fun resolveTemporaryAccessChargeSnapshot(
    requestedSec: Int,
    freeSec: Int,
    coolingAnchorMs: Long,
    existingCooldownEndMs: Long
): TemporaryAccessChargeSnapshot {
    val safeRequested = requestedSec.coerceAtLeast(0)
    val safeFree = freeSec.coerceIn(0, safeRequested)
    val dndSec = safeRequested - safeFree

    val isInCooling = existingCooldownEndMs > coolingAnchorMs

    if (dndSec <= 0) {
        return TemporaryAccessChargeSnapshot(
            freeSec = safeFree,
            dndSec = 0,
            coolingSec = 0,
            normalDndSec = 0,
            weightedTimeCostSec = 0,
            weightedCountHalfUnits = 0
        )
    }

    val multiplier = if (isInCooling) 2 else 1
    val weightedTimeCostSec = dndSec * multiplier
    val baseHalfUnits = if (safeRequested <= 180) 1 else 2
    val weightedCountHalfUnits = baseHalfUnits * multiplier

    return TemporaryAccessChargeSnapshot(
        freeSec = safeFree,
        dndSec = dndSec,
        coolingSec = if (isInCooling) dndSec else 0,
        normalDndSec = if (isInCooling) 0 else dndSec,
        weightedTimeCostSec = weightedTimeCostSec,
        weightedCountHalfUnits = weightedCountHalfUnits
    )
}

internal fun shouldCommitTemporaryAccessCount(
    quotaAlreadyDeducted: Boolean,
    isInDndSegment: Boolean,
    weightedCountHalfUnits: Int
): Boolean {
    return !quotaAlreadyDeducted &&
        isInDndSegment &&
        weightedCountHalfUnits > 0
}

internal data class TemporaryAccessQuotaPresentation(
    val hintText: String,
    val hintColorHex: String,
    val defaultDetailText: String,
    val isFreeScenario: Boolean
)

internal fun resolveTemporaryAccessQuotaPresentation(
    isInDndSegment: Boolean,
    isInRest: Boolean,
    availMinsFormatted: String,
    availCountFormatted: String,
    borrowableMinsFormatted: String,
    borrowableCountFormatted: String
): TemporaryAccessQuotaPresentation {
    return when {
        !isInDndSegment -> TemporaryAccessQuotaPresentation(
            hintText = "🟢 当前处于自由空档期：临时解锁免扣额度（不消耗时长与次数，不计入预支）",
            hintColorHex = "#2E7D32",
            defaultDetailText = "💡 当前处于自由空档期，本次临时解锁免费（不扣除时间与次数额度）",
            isFreeScenario = true
        )
        isInRest -> TemporaryAccessQuotaPresentation(
            hintText = "🟢 当前处于休息时段：临时解锁免扣额度（不消耗时长与次数，不计入预支）",
            hintColorHex = "#2E7D32",
            defaultDetailText = "💡 当前处于休息中，本次临时解锁免费（不扣除时间与次数额度）",
            isFreeScenario = true
        )
        else -> TemporaryAccessQuotaPresentation(
            hintText = "当前可用: ${availMinsFormatted}min / ${availCountFormatted}次 | 可借用: ${borrowableMinsFormatted}min / ${borrowableCountFormatted}次",
            hintColorHex = "#666666",
            defaultDetailText = "",
            isFreeScenario = false
        )
    }
}

internal fun temporaryAccessMinutesToSeconds(minutes: Double): Int {
    return kotlin.math.floor(minutes.coerceAtLeast(0.0) * 60.0 + 0.5).toInt()
}

internal fun formatTemporaryAccessSecondsAsMinutes(seconds: Int): String {
    val minutes = seconds.coerceAtLeast(0) / 60.0
    val rounded = kotlin.math.floor(minutes * 10.0 + 0.5) / 10.0
    return if (kotlin.math.abs(rounded - rounded.toLong()) < 0.0001) {
        rounded.toLong().toString()
    } else {
        "%.1f".format(java.util.Locale.US, rounded)
    }
}

/**
 * Turns completed lock-window lifecycle facts into normal events.  The caller owns every
 * decision and state transition; this adapter has no authority to unlock, consume quota, or
 * infer a foreground transition.  Keeping the adapter at this boundary makes the event grammar
 * testable without changing the lock-window business paths.
 */
class LockLifecycleEventBridge(
    private val clock: EventClock,
    private val emit: (NormalEventDraft) -> Unit,
    private val debugConfig: LogDebugConfig = DefaultLogDebugConfig
) {
    fun recordLockResult(
        requestId: String,
        decisionRef: String,
        packageName: String,
        result: String,
        reason: String,
        duplicate: Boolean,
        countedForPenalty: Boolean
    ) = event(
        "LOCK_RESULT",
        "requestId" to requestId,
        "decisionRef" to decisionRef,
        "packageName" to packageName,
        "result" to result,
        "reason" to reason,
        "duplicate" to duplicate.toString(),
        "countedForPenalty" to countedForPenalty.toString()
    )

    fun recordReturnDesktopExit(
        exitRequestId: String,
        lockRequestId: String,
        transition: ForegroundTransition,
        result: String,
        reason: String? = null,
        evidence: String,
        packageName: String? = null
    ) = event(
        "LOCK_EXIT",
        "exitRequestId" to exitRequestId,
        "lockRequestId" to lockRequestId,
        "exitMethod" to "RETURN_DESKTOP_BUTTON",
        "transition" to transition.render(),
        "result" to result,
        *optional("reason", reason),
        *optional("packageName", packageName),
        "evidence" to canonicalizeEvidence(evidence)
    )

    /** Launcher visibility is evidence for an explicit request, not an independent exit cause. */
    fun recordLauncherVisibilityEvidence(@Suppress("UNUSED_PARAMETER") packageName: String) = Unit

    fun recordRestStarted(
        requestId: String,
        sessionId: String,
        requestedMinutes: String,
        grantedMinutes: String,
        borrowed: Boolean,
        quotaCost: String,
        opens: String
    ) = event(
        "ALLOW_START",
        "requestId" to requestId,
        "sessionId" to sessionId,
        "requested" to requestedMinutes,
        "granted" to grantedMinutes,
        "result" to "APPROVED",
        "borrowed" to borrowed.toString(),
        "quotaCost" to quotaCost,
        "opens" to opens
    )

    fun recordRestRejected(requestId: String, reason: String) = event(
        "REST_REQUEST",
        "requestId" to requestId,
        "result" to "REJECTED",
        "reason" to reason
    )

    fun recordRestEnded(
        sessionId: String,
        actualDuration: String,
        reason: String,
        nextState: String = "COOLDOWN",
        cooldownEndsAt: String? = null
    ) = event(
        "ALLOW_END",
        "sessionId" to sessionId,
        "actualDuration" to actualDuration,
        "reason" to reason,
        "nextState" to nextState,
        *optional("cooldownEndsAt", cooldownEndsAt)
    )

    fun recordRestCooldownEnded(sessionId: String) = event(
        "COOLDOWN_END",
        "sessionId" to sessionId,
        "result" to "AVAILABLE"
    )

    fun recordTemporaryAccessRejected(
        requestId: String,
        reason: String,
        cooldownEndsAt: String? = null
    ) = event(
        "TEMP_ACCESS_REQUEST",
        "requestId" to requestId,
        "result" to "REJECTED",
        "reason" to reason,
        *optional("cooldownEndsAt", cooldownEndsAt)
    )

    fun recordTemporaryAccessStarted(
        requestId: String,
        sessionId: String,
        requested: String,
        granted: String,
        opens: String,
        borrowed: Boolean,
        quotaCost: String
    ) = event(
        "TEMP_ACCESS_START",
        "requestId" to requestId,
        "sessionId" to sessionId,
        "requested" to requested,
        "granted" to granted,
        "result" to "APPROVED",
        "opens" to opens,
        "borrowed" to borrowed.toString(),
        "quotaCost" to quotaCost
    )

    fun recordTemporaryAccessEnded(
        sessionId: String,
        actualDuration: String,
        reason: String,
        nextState: String = "COOLDOWN",
        cooldownEndsAt: String? = null
    ) = event(
        "TEMP_ACCESS_END",
        "sessionId" to sessionId,
        "actualDuration" to actualDuration,
        "reason" to reason,
        "nextState" to nextState,
        *optional("cooldownEndsAt", cooldownEndsAt)
    )

    fun recordTemporaryAccessCooldownEnded(sessionId: String) = event(
        "TEMP_ACCESS_COOLDOWN_END",
        "sessionId" to sessionId,
        "result" to "AVAILABLE"
    )

    fun recordEmergencyAccessStarted(requestId: String, sessionId: String, granted: String) = event(
        "EMERGENCY_ACCESS_START",
        "requestId" to requestId,
        "sessionId" to sessionId,
        "granted" to granted,
        "result" to "APPROVED"
    )

    fun recordEmergencyAccessEnded(sessionId: String, actualDuration: String, reason: String) = event(
        "EMERGENCY_ACCESS_END",
        "sessionId" to sessionId,
        "actualDuration" to actualDuration,
        "reason" to reason
    )

    fun recordPrivilegeRequested(requestId: String, requested: String) = event(
        "PRIVILEGE_REQUEST",
        "requestId" to requestId,
        "requested" to requested
    )

    fun recordPrivilegeApproved(requestId: String) = event(
        "PRIVILEGE_APPROVED",
        "requestId" to requestId,
        "result" to "APPROVED"
    )

    fun recordPrivilegeStarted(requestId: String, sessionId: String) = event(
        "PRIVILEGE_START",
        "requestId" to requestId,
        "sessionId" to sessionId,
        "result" to "APPROVED"
    )

    fun recordPrivilegeStartFailed(requestId: String, reason: String) = event(
        "PRIVILEGE_START",
        "requestId" to requestId,
        "result" to "FAILED",
        "reason" to reason
    )

    fun recordPrivilegeRejected(requestId: String, reason: String) = event(
        "PRIVILEGE_REJECTED",
        "requestId" to requestId,
        "result" to "REJECTED",
        "reason" to reason
    )

    fun recordPrivilegeEnded(requestId: String, sessionId: String, reason: String) = event(
        "PRIVILEGE_END",
        "requestId" to requestId,
        "sessionId" to sessionId,
        "reason" to reason
    )

    fun recordPrivilegeQuotaChanged(
        requestId: String,
        before: String,
        after: String,
        reason: String
    ) = event(
        "PRIVILEGE_QUOTA_CHANGE",
        "requestId" to requestId,
        "before" to before,
        "after" to after,
        "reason" to reason
    )

    /** An unchanged lock overlay is intentionally silent. */
    fun recordIdleLockScreenRefresh() = Unit

    fun recordDesktopTimerStarted(sessionId: String, reason: String) = event(
        "DESKTOP_TIMER_START",
        "sessionId" to sessionId,
        "reason" to reason
    )

    fun recordDesktopTimerTick(sessionId: String, elapsedMs: Long) {
        if (debugConfig.desktopTimerTicks && elapsedMs > 0L && elapsedMs % DESKTOP_TIMER_TICK_MS == 0L) {
            event(
                "DESKTOP_TIMER_TICK",
                "sessionId" to sessionId,
                "elapsedMs" to elapsedMs.toString()
            )
        }
    }

    fun recordDesktopTimerStopped(sessionId: String, reason: String) = event(
        "DESKTOP_TIMER_END",
        "sessionId" to sessionId,
        "reason" to reason
    )

    private fun event(type: String, vararg fields: Pair<String, String>) {
        emit(NormalEvents.create(type = type, clock = clock, fields = linkedMapOf(*fields)))
    }

    private fun optional(key: String, value: String?): Array<Pair<String, String>> =
        value?.takeIf { it.isNotBlank() }?.let { arrayOf(key to it) } ?: emptyArray()

    /**
     * Canonicalize LOCK_EXIT evidence into a single pipe-delimited value:
     * - split on whitespace, drop blanks
     * - drop `package=...` segments
     * - for `evidence=...` segments, keep only the value (strip the key prefix)
     * - keep other key=value segments (e.g. `source=...`, `foreground=...`, `ACCESSIBILITY=抖音`)
     *   verbatim so callers can later inspect the supporting evidence without re-parsing
     * - deduplicate while preserving first-seen order, join with `|`
     */
    internal fun canonicalizeEvidence(raw: String): String {
        val tokens = LinkedHashSet<String>()
        raw.trim().split(' ', '\t').forEach { segment ->
            val piece = segment.trim()
            if (piece.isEmpty()) return@forEach
            if (piece.startsWith("package=", ignoreCase = true)) return@forEach
            if (piece.startsWith("evidence=", ignoreCase = true)) {
                val value = piece.substringAfter("evidence=", missingDelimiterValue = piece)
                if (value.isNotEmpty()) tokens.add(value)
            } else {
                tokens.add(piece)
            }
        }
        return tokens.joinToString("|")
    }

    private companion object {
        private const val DESKTOP_TIMER_TICK_MS = 5_000L
    }
}

private data class LifecycleLockReference(
    val requestId: String,
    val packageName: String
)

/**
 * Boundary 1 / Bug 1 — Expiry operation state machine.
 *
 * State transitions:
 *   ACTIVE ─CAS─► EXPIRY_HANDLING ──► COOLDOWN ──► FINISHED
 *
 * CAS-based semantics mean only one concurrent expiry invocation can advance the state.
 * Subsequent ticks observing EXPIRY_HANDLING / COOLDOWN / FINISHED short-circuit.
 */
internal enum class ExpiryOperationState {
    ACTIVE,
    EXPIRY_HANDLING,
    COOLDOWN,
    FINISHED
}

/**
 * Stable identity of one expiry operation. Generated from a temp-access session id
 * (e.g. `EXPIRY-257` for session `TEMP-SESSION-257`); carried in all post-fix log lines
 * so duplicate ticks become grep-detectable instead of being noise.
 */
internal data class TempAccessExpiryOperationId(val value: String)

/**
 * Stable identity of one temporary-access lifecycle (start → end-of-cooldown).
 * Mirrors the existing `temporaryAccessLogSessionId` field but is a typed value object.
 */
internal data class TempAccessSessionId(val value: String)

/**
 * Single canonical event captured by the expiry controller. Surfaces both normal
 * lifecycle transitions and the one-shot `LOCK_RETRY_STORM` event required by REQ-005.
 */
internal data class ExpiryEvent(
    val tag: String,
    val fields: Map<String, String>
)

private data class PendingReturnDesktopExit(
    val exitRequestId: String,
    val lockRequestId: String,
    val fromPackage: String
)

/** Shared production resolver used by rest and temporary-access terminal event adapters. */
data class LifecycleTerminalState(val nextState: String, val cooldownEndsAtMs: Long?) {
    companion object {
        fun resolve(nowMs: Long, cooldownEndMs: Long, directEndState: String): LifecycleTerminalState =
            if (cooldownEndMs > nowMs) LifecycleTerminalState("COOLDOWN", cooldownEndMs)
            else LifecycleTerminalState(directEndState, null)
    }
}

/** One-shot guard for existing passive cooldown checks; it never schedules or changes a deadline. */
class LifecycleCooldownSettlement {
    private var settledDeadlineMs: Long = Long.MIN_VALUE

    @Synchronized
    fun settleIfExpired(nowMs: Long, cooldownEndMs: Long, onFirstExpired: (Long) -> Unit): Boolean {
        if (cooldownEndMs <= 0L || nowMs < cooldownEndMs || settledDeadlineMs == cooldownEndMs) return false
        settledDeadlineMs = cooldownEndMs
        onFirstExpired(cooldownEndMs)
        return true
    }
}
