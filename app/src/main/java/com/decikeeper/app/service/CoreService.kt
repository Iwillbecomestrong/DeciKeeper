/**
 * =================================================================================
 * [Context Header]
 * * @file: CoreService.kt
 * * @role: Domain Skill
 * * @description: 无障碍核心服务，负责前台事件采集、窗口扫描与系统级防护协调。
 * * @responsibilities:
 * 1. 监听无障碍事件并驱动黑名单/防卸载等核心拦截链路。
 * 2. 在系统窗口、小窗和桌面载体场景中提供前台识别兜底。
 * * @context:
 * - 上游: 系统无障碍框架、MyApplication
 * - 下游: MonitorService, LockWindowManager, ProtectionManager
 * * @constraints (必读):
 * 1. 禁止阻塞主线程；重扫描和重判断需保持轻量。
 * 2. 仅在证据充分时才可认定“真实前台”，避免后台误锁。
 * 3. 状态必须可恢复，服务重连后需继续保持保护能力。
 * =================================================================================
 */
package com.decikeeper.app.service

import android.accessibilityservice.AccessibilityService
import android.app.ActivityManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.decikeeper.app.R
import com.decikeeper.app.data.AndroidLogcatNormalEventSink
import com.decikeeper.app.data.DefaultLogDebugConfig
import com.decikeeper.app.data.DefaultNormalEventRenderer
import com.decikeeper.app.data.LogExporter
import com.decikeeper.app.data.NormalEventLogger
import com.decikeeper.app.data.SegmentBlacklist
import com.decikeeper.app.data.SystemEventClock
import com.decikeeper.app.manager.A11ySelfDisableAuthorization
import com.decikeeper.app.manager.PermissionManager
import com.decikeeper.app.manager.AlarmPowerOffProtectionManager
import com.decikeeper.app.manager.AlarmProtectionEventBridge
import com.decikeeper.app.manager.AntiUninstallManager
import com.decikeeper.app.manager.AppWindowManager
import com.decikeeper.app.manager.AccessibilityDependencyManager
import com.decikeeper.app.manager.CreditManager
import com.decikeeper.app.manager.ProbationManager
import com.decikeeper.app.manager.PwaBypassManager
import com.decikeeper.app.manager.ReminderManager
import com.decikeeper.app.manager.StartupA11yCache
import com.decikeeper.app.manager.StartupProtectionManager
import com.decikeeper.app.manager.TimeRuleManager
import com.decikeeper.app.protection.AccessibilityProtection
import com.decikeeper.app.protection.AntiUninstallProtection
import com.decikeeper.app.protection.ProtectionManager
import com.decikeeper.app.ui.LockWindowManager
import com.decikeeper.app.ui.MainActivity
import com.decikeeper.app.ui.MainActivityEntry






















class CoreService : AccessibilityService() {

    var mLastWindowScanTime: Long = 0
    var mLastContentEventTime: Long = 0 // 🚀 [Fix] Separate timer for content event throttling
    var mLastScanDuration: Long = 0
    var mLastScanPkg: String? = null

    // 🚀 [Entry Cooldown State]
    var mLastWasGap = false
    var mLastWasRest = false
    var stateInitialized = false
    private var mLastLocale: String? = null
    var mStartupProtectionEndTime = 0L
    val STARTUP_PROTECTION_DURATION = 3000L // Reduced to 3s for faster protection

    private val alarmProtectionNormalEventLogger by lazy {
        NormalEventLogger(
            renderer = DefaultNormalEventRenderer,
            sinks = listOf(LogExporter.normalEventSink(), AndroidLogcatNormalEventSink())
        )
    }
    private val alarmProtectionEventBridge by lazy {
        AlarmProtectionEventBridge(
            clock = SystemEventClock,
            emit = alarmProtectionNormalEventLogger::emit,
            debugConfig = DefaultLogDebugConfig
        )
    }
    // Logging-only state: last SERVICE_STATE value emitted, so we only fire on actual change.
    private var lastCoreServiceEventState: String = ""
    private var lastAccessibilityProtectionState: String = ""

    private fun emitServiceStateIfChanged(newState: String, reason: String) {
        if (lastCoreServiceEventState == newState) return
        val oldState = lastCoreServiceEventState
        lastCoreServiceEventState = newState
        alarmProtectionEventBridge.recordServiceState(
            serviceName = "CoreService",
            oldState = oldState,
            newState = newState,
            reason = reason
        )
    }

    private fun emitProtectionStateChangeIfChanged(
        component: String,
        oldState: String,
        newState: String,
        reason: String
    ) {
        if (component == "accessibility") {
            if (lastAccessibilityProtectionState == newState) return
            lastAccessibilityProtectionState = newState
        }
        alarmProtectionEventBridge.recordProtectionStateChange(
            component = component,
            oldState = oldState,
            newState = newState,
            reason = reason
        )
    }

    companion object {
        const val TAG = "AntiUninstall"
        private const val MINI_WINDOW_USAGE_LOOKBACK_MS = 15000L
        private const val MINI_WINDOW_USAGE_FRESH_MS = 4000L
        // [2026-07-06] 问题 3 (Codex review):配置变化路径的 FastLock 持有窗口 10s,
        // 与 onConfigurationChanged 内 startFastLock/stopFastLock/Fallback 都共用同一常量,
        // 保证 countdown 显示窗口(10s)与真实持有窗口一致,不再 hardcode 10000L 多处散落。
        private const val CONFIG_CHANGE_FAST_LOCK_VISIBLE_MS = 10_000L
        
        // 🚀 [NEW] 静态运行标志，用于精准状态同步
        @Volatile var isServiceActive = false
        @Volatile var lastAccessibilitySignalTime = 0L
        @Volatile var lastAccessibilityDispatchedTime = 0L
        @Volatile var lastVisiblePackagesFromWindows: Set<String> = emptySet()

        fun hasRecentAccessibilityDispatchedSignal(nowMillis: Long, gracePeriodMs: Long): Boolean {
            return nowMillis - lastAccessibilityDispatchedTime < gracePeriodMs
        }

        // [WP-1] Accessibility generation — monotonic, non-persistent process-local counter.
        // Bumped ONLY in onServiceConnected(). Used by LockWindowManager to detect stale
        // lock overlays from a dead accessibility generation.
        @Volatile private var accessibilityGeneration: Int = 0
        @JvmStatic
        fun getAccessibilityGeneration(): Int = accessibilityGeneration

        /**
         * Advance the generation and return the new value.
         * Must only be called from onServiceConnected().
         */
        internal fun advanceAccessibilityGenerationForServiceConnected(): Int {
            val next = accessibilityGeneration + 1
            accessibilityGeneration = next
            return next
        }

        /**
         * Read-only seam for JVM tests: classify a lock request against the current
         * generation. Returns one of: SAME, STALE, ANTI_UNINSTALL_PRIORITY.
         */
        internal fun classifyGenerationForRequest(
            attachedGeneration: Int,
            incomingIsAntiUninstall: Boolean,
            existingIsAntiUninstall: Boolean
        ): String {
            if (incomingIsAntiUninstall || existingIsAntiUninstall) return "ANTI_UNINSTALL_PRIORITY"
            if (attachedGeneration == accessibilityGeneration) return "SAME"
            return "STALE"
        }

        // ═══════════════════════════════════════════════════════════
        // [Diagnostics-only] immutable volatile state for ForegroundDiagnosticCycle
        // Never read by any blocking decision logic.
        // ═══════════════════════════════════════════════════════════
        @Volatile var diagnosticsServiceConnected: Boolean = false
        @Volatile var diagnosticsInstancePresent: Boolean = false
        @Volatile var diagnosticsLastEligibleEventPackage: String? = null
        @Volatile var diagnosticsLastEligibleEventType: String? = null
        @Volatile var diagnosticsLastEligibleEventAtMs: Long = 0L
        @Volatile var diagnosticsWindowSnapshotAtMs: Long = 0L
        // Window-scan failure class — written by recordDiagnosticsWindowSnapshot.
        // Distinct from the accessibility-status failure class below so each source
        // carries only failures from its own detection process.
        @Volatile var diagnosticsLastWindowSnapshotFailureClass: String? = null
        // Accessibility-status failure class — written by diagnosticsAccessibilityStatus
        // when the Settings.Secure / service-connectivity check itself throws.
        // Cleared on a successful status check.
        @Volatile var diagnosticsAccessibilityStatusFailureClass: String? = null
        @Volatile private var diagnosticsAccessibilityCheckAtMs: Long = 0L
        @Volatile private var diagnosticsAccessibilityCheckResult: String = "INITIALIZING"
        private const val ACCESSIBILITY_CHECK_CACHE_MS = 5_000L
        private const val NODE_IDENTIFIER_MAX_NODES = 128
        private const val NODE_IDENTIFIER_MAX_DEPTH = 8
        private const val NODE_IDENTIFIER_MAX_DURATION_MS = 20L

        /**
         * Diagnostics-only: classify accessibility status for ForegroundDiagnosticCycle.
         * Cached for 5 seconds. Does NOT replace isServiceActive in business logic.
         */
        fun diagnosticsAccessibilityStatus(context: android.content.Context?): String {
            val now = System.currentTimeMillis()
            if (now - diagnosticsAccessibilityCheckAtMs < ACCESSIBILITY_CHECK_CACHE_MS &&
                diagnosticsAccessibilityCheckResult != "INITIALIZING"
            ) {
                return diagnosticsAccessibilityCheckResult
            }
            diagnosticsAccessibilityCheckAtMs = now
            var result: String
            var failureClass: String? = null
            try {
                result = computeAccessibilityStatus(context)
                // successful status check clears any prior failure
                failureClass = null
            } catch (e: Exception) {
                result = "FAILED"
                failureClass = e.javaClass.simpleName
            }
            diagnosticsAccessibilityCheckResult = result
            diagnosticsAccessibilityStatusFailureClass = failureClass
            return result
        }

        private fun computeAccessibilityStatus(context: android.content.Context?): String {
            if (isServiceActive) return "AVAILABLE"
            val ctx = context ?: (instance?.applicationContext)
                ?: return if (diagnosticsServiceConnected) "FAILED" else "INITIALIZING"
            val enabled = android.provider.Settings.Secure.getString(
                ctx.contentResolver,
                android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return "UNAVAILABLE"
            val ourName = ctx.packageName + "/" + CoreService::class.java.name
            return if (!enabled.contains(ourName)) {
                "UNAVAILABLE"
            } else if (diagnosticsServiceConnected) {
                "AVAILABLE"
            } else {
                "AMBIGUOUS"
            }
        }

        private val recentA11yEventPackageTimes = java.util.concurrent.ConcurrentHashMap<String, Long>()

        fun recordDiagnosticsWindowSnapshot(success: Boolean, failureClass: String?) {
            diagnosticsWindowSnapshotAtMs = System.currentTimeMillis()
            if (success) {
                // Successful window scan clears any prior scan failure so a stale
                // exception can't leak into the next FAILED-status report.
                diagnosticsLastWindowSnapshotFailureClass = null
            } else {
                diagnosticsLastWindowSnapshotFailureClass = failureClass
            }
        }

        fun recordDiagnosticsEligibleEvent(packageName: String?, eventType: String?, atMs: Long) {
            diagnosticsLastEligibleEventPackage = packageName
            diagnosticsLastEligibleEventType = eventType
            diagnosticsLastEligibleEventAtMs = atMs
            if (!packageName.isNullOrEmpty()) {
                recentA11yEventPackageTimes[packageName] = atMs
            }
        }

        fun isPackageRecentlyActiveViaAccessibility(packageName: String, nowMs: Long = System.currentTimeMillis(), ttlMs: Long = 3000L): Boolean {
            val lastTime = recentA11yEventPackageTimes[packageName] ?: return false
            return (nowMs - lastTime) in 0L..ttlMs
        }

        fun captureWindowSnapshot(nowMs: Long = System.currentTimeMillis()): AccessibilityWindowSnapshot {
            val service = instance
            if (service == null || !isServiceActive) {
                recordDiagnosticsWindowSnapshot(success = false, failureClass = "ServiceNotActive")
                return AccessibilityWindowSnapshot(
                    capturedAtMs = nowMs,
                    status = AccessibilityWindowSnapshotStatus.FAILED,
                    packages = emptySet(),
                    unresolvedWindowCount = 0,
                    totalWindowCount = 0,
                    failureClass = "ServiceNotActive"
                )
            }

            return try {
                val windowList = service.windows
                if (windowList == null) {
                    recordDiagnosticsWindowSnapshot(success = false, failureClass = "WindowsNull")
                    AccessibilityWindowSnapshot(
                        capturedAtMs = nowMs,
                        status = AccessibilityWindowSnapshotStatus.FAILED,
                        packages = emptySet(),
                        unresolvedWindowCount = 0,
                        totalWindowCount = 0,
                        failureClass = "WindowsNull"
                    )
                } else {
                    val currentVisiblePkgs = mutableSetOf<String>()
                    var unresolvedCount = 0
                    for (window in windowList) {
                        val root = window.root
                        if (root == null) {
                            unresolvedCount++
                            continue
                        }
                        val winPkg = root.packageName?.toString()
                        root.recycle()
                        if (winPkg != null && winPkg != service.packageName) {
                            currentVisiblePkgs.add(winPkg)
                        } else if (winPkg == null) {
                            unresolvedCount++
                        }
                    }

                    // 🚀 [Event-Snapshot Fusion] Merge packages that emitted Accessibility events in the last 3000ms.
                    // This handles OEM floating/PiP windows (like Douyin) that render without standard AccessibilityWindow roots.
                    recentA11yEventPackageTimes.forEach { (pkg, lastMs) ->
                        if (nowMs - lastMs in 0L..3000L && pkg != service.packageName) {
                            currentVisiblePkgs.add(pkg)
                        }
                    }

                    lastVisiblePackagesFromWindows = currentVisiblePkgs
                    val status = if (unresolvedCount > 0) {
                        AccessibilityWindowSnapshotStatus.PARTIAL
                    } else {
                        AccessibilityWindowSnapshotStatus.COMPLETE
                    }

                    recordDiagnosticsWindowSnapshot(success = true, failureClass = null)

                    AccessibilityWindowSnapshot(
                        capturedAtMs = nowMs,
                        status = status,
                        packages = currentVisiblePkgs,
                        unresolvedWindowCount = unresolvedCount,
                        totalWindowCount = windowList.size,
                        failureClass = null
                    )
                }
            } catch (e: Exception) {
                val failureClass = e.javaClass.simpleName
                recordDiagnosticsWindowSnapshot(success = false, failureClass = failureClass)
                AccessibilityWindowSnapshot(
                    capturedAtMs = nowMs,
                    status = AccessibilityWindowSnapshotStatus.FAILED,
                    packages = emptySet(),
                    unresolvedWindowCount = 0,
                    totalWindowCount = 0,
                    failureClass = failureClass
                )
            }
        }

        fun updateActiveWindows() {
            captureWindowSnapshot()
        }
        // 防卸载保护链 JIT 预热完成标志。FastLock 在收到 handoff 时检查此标志，
        // 未完成则拒绝关闭，避免覆盖层消失后首次卸载检测因 JIT 编译延迟而变慢。
        @Volatile var antiUninstallWarmedUp = false
        @Volatile var antiUninstallWarmupStable = false

        fun resetAntiUninstallWarmupForProcess() {
            antiUninstallWarmedUp = false
            antiUninstallWarmupStable = false
        }

        fun markAntiUninstallWarmupComplete() {
            antiUninstallWarmedUp = true
            if (!antiUninstallWarmupStable) {
                antiUninstallWarmupStable = true
            }
        }
        // mLastBlockTime moved to ProtectionManager
        var mLastPendingCheck: Long = 0
        var mLastAntiUninstallTime: Long = 0
        
        var instance: CoreService? = null // 🚀 Singleton for detached fix

        // 识别逻辑已抽到 manager/SystemDialogDetectionPolicy.kt（纯函数，可 JVM 单测），
        // 此处仅保留同签名转发，避免改动既有调用点。
        fun isSystemDialogPackage(pkgName: String?): Boolean =
            com.decikeeper.app.manager.isSystemDialogPackage(pkgName)

        fun isShutdownDialogClass(className: String?): Boolean =
            com.decikeeper.app.manager.isShutdownDialogClass(className)

        fun shouldHandleShutdownDialogEvent(eventType: Int, pkgName: String?, className: String?): Boolean =
            com.decikeeper.app.manager.shouldHandleShutdownDialogEvent(eventType, pkgName, className)

        /** 系统弹层 BACK 限流：语音助手会连续吐出大量事件，不限流会疯狂按 BACK。 */
        private const val SYSTEM_DIALOG_BACK_MIN_INTERVAL_MS = 1200L
        private var mLastSystemDialogBackAt = 0L

        fun hasRecentAccessibilitySignal(
            nowMillis: Long = System.currentTimeMillis(),
            graceMs: Long = 15000L
        ): Boolean {
            val last = lastAccessibilitySignalTime
            if (last <= 0L) return false
            return nowMillis - last <= graceMs
        }

        internal fun shouldAdoptVivoMiniWindowFallback(
            candidateDeltaMs: Long,
            isBlockable: Boolean,
            freshMs: Long = MINI_WINDOW_USAGE_FRESH_MS
        ): Boolean {
            if (!isBlockable) return false
            if (candidateDeltaMs < 0L) return false
            return candidateDeltaMs <= freshMs
        }

        internal fun shouldTreatBlacklistHitAsAccessibility(
            fromUsageFallback: Boolean,
            fromDirectForegroundEvent: Boolean
        ): Boolean {
            if (fromUsageFallback) return false
            return fromDirectForegroundEvent
        }


        internal fun shouldAdoptWindowScanCandidate(
            windowType: Int,
            isActive: Boolean,
            isFocused: Boolean,
            hasVisibleBounds: Boolean
        ): Boolean {
            if (windowType != AccessibilityWindowInfo.TYPE_APPLICATION) return false
            if (!hasVisibleBounds) return false
            return isActive || isFocused
        }
    }

    // 🚀 [NEW] Reminder Ticker
    private val mHandler = Handler(Looper.getMainLooper())
    private val mReminderRunnable = object : Runnable {
        override fun run() {
            if (isServiceActive) {
                try {
                    // 🚀 [Heartbeat Log] - Essential for diagnosing system kills
                    Log.d("DeciKeeper", "CoreService Heartbeat: ALIVE. Time=${System.currentTimeMillis()}")

                    ReminderManager.checkReminders(this@CoreService)
                    
                    // Clear legacy probation entries from builds that used delayed baseline promotion.
                    val promoted = ProbationManager.checkAndPromote(this@CoreService)
                    if (promoted.isNotEmpty()) {
                        Log.i("CoreService", "Auto-Promoted Baselines: $promoted")
                    }
                    
                    // 🚀 [NEW] Check for Natural End of Adaptation Period
                    // If period expired temporally but wasn't finalized, do it now.
                    if (!CreditManager.isInAdaptationPeriod(this@CoreService) && 
                         !this@CoreService.getSharedPreferences(CreditManager.PREFS_NAME, Context.MODE_PRIVATE)
                             .getBoolean("has_manually_ended_adaptation", false)) {
                         
                         Log.i("CoreService", "Adaptation Period Expired Temporally. Finalizing...")
                         CreditManager.markAdaptationEnded(this@CoreService)
                    }

                } catch (e: Exception) {
                    Log.e(TAG, "Adaptation period check failed", e)
                }
                mHandler.postDelayed(this, 60000L) // Check every minute
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.d("AntiUninstall", "CoreService (Anti-Uninstall) Connected")
        // [2026-06-25] 诊断日志：lifecycle 事件，便于排查无障碍 flapping 问题
        // 详见 Issue_AntiUninstallBootGap 尝试 7
        Log.i("DeciKeeper", "[Lifecycle] onServiceConnected uptimeMs=${android.os.SystemClock.uptimeMillis()}")
        // [WP-1] Bump accessibility generation BEFORE any state change so any subsequent
        // MonitorService-snapshot request sees the new generation.
        val newGen = advanceAccessibilityGenerationForServiceConnected()
        Log.i("DeciKeeper", "[Lifecycle] onServiceConnected generation=$newGen")
        isServiceActive = true
        lastAccessibilitySignalTime = System.currentTimeMillis()
        instance = this // 🚀 Capture instance

        emitServiceStateIfChanged("OK", "ON_SERVICE_CONNECTED")
        emitProtectionStateChangeIfChanged("accessibility", "INITIALIZING", "OK", "ON_SERVICE_CONNECTED")

        // [Diagnostics-only] wire lifecycle to ForegroundDiagnosticCycle state.
        diagnosticsServiceConnected = true
        diagnosticsInstancePresent = true

        // 🚀 无障碍服务重新连接时，清除过期的自行关闭授权
        // 防止服务 flapping 后授权残留，导致保护持续跳过
        A11ySelfDisableAuthorization.clear("core_service_onServiceConnected")

        // 将全部初始化代码包裹在 try-catch 中，防止 CE 未就绪时单点崩溃导致整个服务死亡
        try {
            // DPS 双写：缓存无障碍已开启，供下次 Direct Boot 快速判断，避免读 Settings.Secure
            StartupA11yCache.setA11yWasEnabled(this, true)
            Log.d("AntiUninstall", "CoreService: cached a11y_was_enabled=true via DPS dual write")
            mLastLocale = resources.configuration.locales[0].toString() // Store initial locale
            ProtectionManager.init(this)
            AccessibilityProtection.markServiceConnected()
            if (AccessibilityProtection.shouldReturnToAppAfterConnect()) {
                mHandler.postDelayed({
                    try {
                        val intent = Intent(this, MainActivity::class.java).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                        }
                        // REQ-5: 走 MainActivityEntry 业务入口。未同意 → 跳 PrivacyConsentActivity
                        MainActivityEntry.start(this, intent)
                        LogExporter.logEvent(TAG, "[A11y] Returned to IronWill after manual accessibility enable")
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to return to 决定守护 after accessibility enable", e)
                    }
                }, 800L)
            }
            LockWindowManager.setCoreService(this)
            LockWindowManager.init(this)
            LockWindowManager.reconcileTransientOverlaysForNewGeneration(newGen)

            // A11y reconnect invalidates TYPE_ACCESSIBILITY_OVERLAY ownership. If a
            // blacklist lock was already visible, resubmit the same lock under the new
            // generation. LockWindowManager will classify the old generation as stale,
            // remove only the stale physical view, then rebuild it immediately.
            val reconnectLockedPkg = LockWindowManager.getCurrentBlockedPackageName()
            if (LockWindowManager.isLocked &&
                LockWindowManager.isBlacklistLock &&
                !reconnectLockedPkg.isNullOrBlank()
            ) {
                Log.w(TAG, "[A11Y_RECONNECT_REBUILD] generation=$newGen pkg=$reconnectLockedPkg")
                LogExporter.logEvent(
                    "LockScreen",
                    "[A11Y_RECONNECT_REBUILD] generation=$newGen pkg=$reconnectLockedPkg"
                )
                LockWindowManager.showLockScreen(
                    reason = "Accessibility reconnect rebuild",
                    isBlacklist = true,
                    forceBypassGrace = true,
                    blockedPackageName = reconnectLockedPkg,
                    isScreenOnRedetection = true,
                    detectionSource = "A11Y_RECONNECT"
                )
            }

            // 🚀 [Fix] Explicitly start MonitorService to ensure background logic runs
            // This is critical if BootReceiver failed to launch MainActivity
            try {
                val monitorIntent = Intent(this, MonitorService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(monitorIntent)
                } else {
                    startService(monitorIntent)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start MonitorService", e)
            }

            // CoreService 接管 FastLock：必须等 CE 存储就绪后才发 handoff，
            // 防止在用户刚解锁、CE 尚未可用时就关掉覆盖层留下真空期。
            mHandler.postDelayed(object : Runnable {
                override fun run() {
                    if (!StartupProtectionManager.shouldKeepInitialization(this@CoreService)) {
                        StartupProtectionManager.stopFastLock(this@CoreService)
                        LockWindowManager.dismissSystemInitOverlay("initialization_disabled")
                        Log.i("AntiUninstall", "CoreService: initialization no longer required, stopped FastLock")
                        return
                    }
                    if (!isCeStorageAvailable()) {
                        Log.d("AntiUninstall", "CoreService: CE storage not ready, deferring FastLock handoff")
                        mHandler.postDelayed(this, 2000L)
                        return
                    }
                    try {
                        StartupProtectionManager.requestFastLockHandoff(this@CoreService)
                        if (StartupProtectionManager.isEarlyBootOverlayAttached()) {
                            StartupProtectionManager.dismissEarlyBootOverlay(this@CoreService, "core_connected_ce_ready")
                        }
                        Log.d("AntiUninstall", "CoreService: FastLockService handoff requested (CE ready)")
                    } catch (e: Exception) {
                        Log.e(TAG, "FastLock handoff failed", e)
                    }
                }
            }, StartupProtectionManager.CORE_HANDOFF_REQUEST_DELAY_MS)

            // [fde3c93] 无障碍服务连接时**不得**无条件打开防卸载保护——
            // 用户主动关闭过的保护不能被服务重连悄悄恢复。
            // 契约守护：AntiUninstallStartupProtectionContractTest
            //   .coreServiceMustNotTurnOnAntiUninstallWhenAccessibilityConnects

            // 🚀 [JIT Warmup] Pre-compile critical code paths in background
            // This prevents the "first few times slow" issue by triggering JIT compilation early
            mHandler.postDelayed({
                warmupCriticalPaths()
            }, 500L)

            // Start Reminder Loop
            mHandler.post(mReminderRunnable)

            // 🚀 常驻守护小浮窗（自律小盾牌）启动检查
            if (com.decikeeper.app.manager.GuardFloatingWindowManager.isFloatingGuardEnabled(this)) {
                com.decikeeper.app.manager.GuardFloatingWindowManager.show(this)
            }
        } catch (e: Exception) {
            Log.e(TAG, "onServiceConnected: init failed but service stays alive", e)
        }
    }
    
    /**
     * 预热关键代码路径，触发 JIT 编译
     * 在后台线程执行假数据，确保首次真正检测时已编译
     */
    private fun warmupCriticalPaths() {
        // 🚀 [Phase 1] 主线程预热（UI组件、字符串资源）
        mHandler.post {
            try {
                Log.d(TAG, "🚀 [Warmup] Phase 1: UI components...")
                
                getString(R.string.app_name)
                
                Log.d(TAG, "🚀 [Warmup] Phase 1 completed!")
            } catch (e: Exception) {
                Log.e(TAG, "UI Warmup error (non-fatal)", e)
            }
        }
        
        // 🚀 [Phase 2] 后台线程预热（字符串匹配、SP读取）
        // 🔥 [CRITICAL] JIT 需要 500+ 次执行才能触发真正的热点编译优化
        Thread {
            try {
                Log.d(TAG, "🚀 [Warmup] Phase 2: Background paths (500+ iterations)...")
                
                // 预热测试文本覆盖多种语言
                val testTexts = listOf(
                    "决定守护 DeciKeeper 卸载 Uninstall 确定 删除",
                    "IronWill 意引 卸载 Uninstall 确定 删除",
                    "Deinstallieren App entfernen IronWill",
                    "解除安裝 刪除應用程式 决定守护 意引 確定",
                    "アンインストール 削除 决定守护 IronWill",
                    "Désinstaller Supprimer IronWill"
                )
                
                // 🔥 [JIT Warmup] 1500 次循环触发热点编译 (Increased from 100 -> 1500 for better optimization)
                for (round in 0 until 1500) {
                    for (testText in testTexts) {
                        // 模拟完整检测路径
                        AntiUninstallProtection.UNINSTALL_KEYWORDS.any { testText.contains(it) }
                        AntiUninstallProtection.PROTECTED_APP_NAMES.any { testText.contains(it, ignoreCase = true) }
                        AntiUninstallProtection.MONITORED_PACKAGES.any { "com.test.fake".contains(it) }
                        AntiUninstallProtection.VIVO_LAUNCHER_PACKAGES.contains("com.test.fake")
                        AntiUninstallProtection.SAFE_PACKAGES_KEYWORDS.any { "com.test".contains(it, ignoreCase = true) }
                        
                        // 棰勭儹 StringBuilder锛坈ollectAllText 鏍稿績锛?
                        val sb = StringBuilder()
                        sb.append(testText).append(" test ").append(round)
                        sb.toString()
                    }
                }
                
                // 预热 SharedPreferences 读取
                AntiUninstallManager.getProtectionState(this)
                CreditManager.shouldBlockApp(this, "com.test.fake", "")
                
                Log.d(TAG, "🚀 [Warmup] Phase 2 completed! (500 iterations done)")
                markAntiUninstallWarmupComplete()
                // 预热完成即请求接管；若快锁仍在显示，可提前结束，不必等满 10 秒。
                mHandler.post {
                    if (StartupProtectionManager.shouldKeepInitialization(this@CoreService)) {
                        StartupProtectionManager.requestFastLockHandoff(this@CoreService)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Background Warmup error (non-fatal)", e)
            }
        }.start()
        
        // 🚀 [Phase 3] 延迟预热全局动作（需要服务完全就绪）
        // ⚠️ 为避免授权后误触拉下通知栏，此阶段不再触发任何全局动作
        mHandler.postDelayed({
            try {
                Log.d(TAG, "[Warmup] Phase 3: Accessibility node tree traversal...")
                val root = rootInActiveWindow
                if (root != null) {
                    warmupAccessibilityNodeTree(root, 0)
                    // root.recycle() — handled by warmupAccessibilityNodeTree's finally block
                }
                Log.d(TAG, "[Warmup] Phase 3 completed!")
            } catch (e: Exception) {
                Log.e(TAG, "Node traversal warmup error (non-fatal)", e)
            }
        }, 3000L)
    }

    /** 轻量遍历 AccessibilityNodeInfo 树，触发 JIT 编译节点访问方法（getChild/text/className 等）。 */
    private fun warmupAccessibilityNodeTree(node: android.view.accessibility.AccessibilityNodeInfo, depth: Int) {
        if (depth > 6) {
            node.recycle()
            return
        }
        try {
            node.text
            node.className
            node.packageName
            node.contentDescription
            node.viewIdResourceName
            node.isClickable
            node.isEnabled
            node.isFocusable
            val count = node.childCount
            for (i in 0 until minOf(count, 4)) {
                val child = node.getChild(i) ?: continue
                warmupAccessibilityNodeTree(child, depth + 1)
                // child.recycle() — handled by recursive call's finally block
            }
        } catch (e: Exception) {
            Log.w(TAG, "Node warmup error (non-fatal)", e)
        } finally {
            node.recycle()
        }
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)

        // [2026-07-06 REQ-2 诊断] 扩展：记录 onConfigurationChanged 完整触发上下文。
        // 用于排查"划掉任务 → 倒计时跳变"现象的触发链路（候选：config-change 把 30s 覆盖为 10s）。
        // 如果 task 被杀重启，此处不会触发；若触发，说明存在另一种生命周期路径。
        // 在早期返回前和后都记录详细的 config 字段，以定位真正的触发原因。
        val currentLocale = newConfig.locales[0].toString()

        // 无论 locale 是否变化，都记录完整的 config 字段快照，用于事后分析触发原因。
        // 注意：此日志在"划掉任务"场景下如果出现，意味着不是进程被杀的重启，
        // 而是另有一条路径导致 onConfigurationChanged 被调用（如系统推配置、重启服务后自动调等）。
        Log.i(
            "DeciKeeper",
            "[ConfigChange] ENTRY " +
                "locale=$currentLocale (lastLocale=$mLastLocale) " +
                "isServiceActive=$isServiceActive " +
                "fastLockRunning=${FastLockService.isRunning} " +
                "now=${System.currentTimeMillis()} " +
                "mcc=${newConfig.mcc} mnc=${newConfig.mnc} " +
                "orientation=${newConfig.orientation} " +
                "uiMode=${newConfig.uiMode} " +
                "screenLayout=${newConfig.screenLayout} " +
                "screenWidthDp=${newConfig.screenWidthDp} " +
                "screenHeightDp=${newConfig.screenHeightDp} " +
                "densityDpi=${newConfig.densityDpi} " +
                "fontScale=${newConfig.fontScale} " +
                "keyboard=${newConfig.keyboard} " +
                "navigation=${newConfig.navigation} " +
                "touchscreen=${newConfig.touchscreen}"
        )

        // 早期返回：locale 未变化时，不触发 FastLock，但记录详细的触发现场。
        // 原代码仅输出 "Orientation/Resize detected" 但在只有 locale 被追踪的前提下
        // 这个结论不准确 — 实际可能是 uiMode/mcc/densityDpi 等发生变化。
        // 用 ENTRY 日志中的完整字段排查真正的触发原因。
        if (currentLocale == mLastLocale) {
            Log.d(TAG, "onConfigurationChanged: locale unchanged (most recent=$currentLocale), skipping lock/cache reset. See [ConfigChange] ENTRY for full field diff.")
            return
        }

        // [2026-06-25] 诊断日志：配置变化触发 FastLock 的入口
        // 这是"初始化界面刷新两次"的候选原因之一（候选 2：onConfigurationChanged 启动 FastLock 10秒）
        // 详见 Issue_AntiUninstallBootGap 尝试 8
        Log.i("DeciKeeper", "[ConfigChange] locale=$currentLocale → will start FastLock (10s)")

        // [2026-07-06 REQ-2 诊断] 详细记录 onConfigurationChanged 进入实际触发分支后的上下文。
        // ENTRY 日志（上面的无条件日志）已经录了完整的 config 字段，
        // 这里只录触发特有的信息：locale 变化方向 + 即将调用的 startFastLock 参数。
        Log.w(TAG, "onConfigurationChanged: Locale changed from $mLastLocale to $currentLocale. Triggering protection.")
        mLastLocale = currentLocale

        // 初始化界面只属于防卸载增强。关闭增强后，配置变化不得再制造初始化保护窗口；
        // PENDING 仍然保留保护，因此这里统一使用 ACTIVE/PENDING 判定。
        val antiUninstallState = AntiUninstallManager.getProtectionState(this)
        if (!AntiUninstallManager.shouldShowInitialization(antiUninstallState)) {
            Log.i(TAG, "ConfigChange: anti-uninstall initialization is disabled, skip protection overlay")
            ProtectionManager.resetCache(this)
            return
        }

        // 🚀 [Config Change] 监听到系统配置变化（如语言切换），使用“系统初始化锁”
        // 1. Start FastLockService (System Initialization Style Lock)
        val canDrawOverlays = PermissionManager.hasOverlayPermission(this)

        if (!canDrawOverlays) {
            Log.w(TAG, "ConfigChange: overlay permission unavailable, fallback to accessibility lock")
            LogExporter.logEvent("ConfigChange", "[FALLBACK] No overlay permission, using accessibility lock")
            LockWindowManager.showSystemInitOverlay("ConfigChangeNoOverlay", durationMs = 10000L)
            ProtectionManager.resetCache(this)
            return
        }

        try {
            // [2026-07-06] REQ-2 + D-007 + 问题 3 (Codex review):
            // 配置变化触发的 FastLock 必须显示 countdown,且 countdown 与真实持有窗口一致。
            // 复用 StartupProtectionManager.startFastLock 入口(传 maxVisibleMs=10_000L),
            // 确保 inflate/adopt 两条路径共享同一个集中 tick 逻辑。
            //
            // [2026-07-06 session 4 返工] Codex review 3 阻断问题修复:
            // 这里**不再**自带 `mHandler.postDelayed { stopFastLock }`。
            // 关停所有权统一收口到 FastLockService 内部的 mDeadlineMs + scheduleMaxTimeout +
            // mTimeoutRunnable 调度链:
            // - mTimeoutRunnable 在 mDeadlineMs 时刻(此处 = now + 10s)触发
            // - mExtensionCount(0) >= mMaxExtensions(0) → 直接 stopSelf
            // 这避免了"连续两次 config-change 时,前一次 postDelayed 不会被取消"的隐患:
            // 该旧 runnable 会在第一次 config-change + 10s 后强制 stop,导致第二次 config-change
            // 延后到 mDeadlineMs(=max(当前, now+10s))的窗口被提前关闭,违反 D-007 grow 语义。
            // 重构后所有"何时关闭"决策都由 mDeadlineMs 统一主导,CoreService 不再拥有
            // 独立的 stop 时机,可被重复调用而无残留。
            // [2026-07-06 REQ-2 诊断] 在调用前记录当前 FastLock 状态，便于复现路径核对。
            Log.i(
                "DeciKeeper",
                "[ConfigChange] calling startFastLock maxVisibleMs=$CONFIG_CHANGE_FAST_LOCK_VISIBLE_MS " +
                    "fastLockRunning=${FastLockService.isRunning} " +
                    "now=${System.currentTimeMillis()}"
            )
            StartupProtectionManager.startFastLock(this, maxVisibleMs = CONFIG_CHANGE_FAST_LOCK_VISIBLE_MS)

        } catch (e: Exception) {
            Log.e(TAG, "Config change protection failed", e)
            // Fallback to regular lock if service fails to start
            LockWindowManager.showSystemInitOverlay("ConfigChangeFallback", durationMs = CONFIG_CHANGE_FAST_LOCK_VISIBLE_MS)
        }
        
        // 3. Reset Cache
        ProtectionManager.resetCache(this)
    }


    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        try {
            onAccessibilityEventInternal(event)
        } catch (t: Throwable) {
            Log.e(TAG, "CRITICAL: Service Crash Prevention", t)
        }
    }

    private fun onAccessibilityEventInternal(event: AccessibilityEvent?) {
        if (event == null) return
        lastAccessibilitySignalTime = System.currentTimeMillis()
        
        // 🚀 [DIAGNOSTIC] Sampling Accessibility Events to prove service is receiving data
        // Log explicitly with IronWill_Debug tag
        // Filter common "spammy" events to keep log cleaner, but enough to show activity.
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED || 
            event.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED || 
            Math.random() < 0.05) { // 5% sample of other events
             Log.v("DeciKeeper", "A11y Event: Type=${event.eventType}, Pkg=${event.packageName}, Class=${event.className}")
        }
        
        // 🚀 [Self-Exclusion]
        // If we monitor our own UI events (especially EditText), it causes severe keyboard lag.
        if (event.packageName == CoreService.instance?.packageName) {
            return
        }


        // 🚀 [IME Exclusion] Ignore events from Keyboard/Input Method
        // Monitoring IME events (key presses/candidates) causes extreme lag during typing.
        val pkgName = event.packageName?.toString()
        val srv = CoreService.instance
        if (pkgName != null && srv != null && CreditManager.isInputMethod(srv, pkgName)) return

        // During lock-screen numeric input, pause non-critical event dispatching to keep typing smooth,
        // but NEVER swallow events destined for ProtectionManager on critical system pages.
        if (LockWindowManager.isInputPanelOpen &&
            !com.decikeeper.app.protection.CoreServiceProtectionDispatchPolicy.shouldDispatchProtectionEvent(true, pkgName)
        ) {
            return
        }

        // [Diagnostics-only] record the latest eligible (non-self, non-IME) event for ForegroundDiagnosticCycle.
        recordDiagnosticsEligibleEvent(pkgName, event.eventType.toString(), System.currentTimeMillis())
        
        // 馃殌 [System Dialog Compat] 妫€娴嬬郴缁熷璇濇锛堝叧鏈鸿彍鍗曘€侀煶閲忛潰鏉跨瓑锛?
        // 褰撻攣灞忚鐩栨椂锛岀郴缁熷璇濇浼氳鎸′綇瀵艰嚧鏃犳硶鍏虫満銆?
        // 妫€娴嬪埌绯荤粺绾у璇濇绐楀彛鐘舵€佸彉鍖栨椂锛屼复鏃惰鍑虹劍鐐广€?
        // 鍏煎锛欰OSP (android), Vivo (com.vivo.systemuiplugin), Samsung (com.android.systemui) 绛?
        // 🚀 [System Dialog Compat] 检测系统对话框（关机菜单、音量面板等）
        // 当锁屏覆盖时，系统对话框会被挡住导致无法关机。
        // 检测到系统级对话框窗口状态变化时，临时让出焦点。
        // 兼容：AOSP (android), Vivo (com.vivo.systemuiplugin), Samsung (com.android.systemui) 等
        if (LockWindowManager.isAlarmOverlayShowing && AlarmPowerOffProtectionManager.isEnabled(this)) {
            val className = event.className?.toString()
            val shutdownDialog = shouldHandleShutdownDialogEvent(event.eventType, pkgName, className)
            val voiceAssistant = com.decikeeper.app.manager
                .shouldDismissVoiceAssistantOverAlarm(event.eventType, pkgName)
            if (shutdownDialog || voiceAssistant) {
                val dialogKind = if (shutdownDialog) "shutdown" else "voice_assistant"
                LogExporter.logEvent(
                    "SystemDialog",
                    "[ALARM_BLOCK] kind=$dialogKind, type=${event.eventType}, pkg=$pkgName, class=$className"
                )
                // 内嵌模式下先切换到窗口模式，确保覆盖层能升至系统对话框之上
                LockWindowManager.ensureAlarmOverlayInWindowMode()
                LockWindowManager.reassertAlarmOverlayOverSystemDialog(
                    if (shutdownDialog) "shutdown_dialog" else "voice_assistant"
                )
                // 锁屏 + 挑战覆盖层同时激活时，一并降级锁屏让系统对话框能被正确处理，
                // 否则 isLocked 路径因本块提前 return 永远不会执行 onSystemDialogChanged(true)。
                if (LockWindowManager.isLocked) {
                    LockWindowManager.onSystemDialogChanged(true)
                }
                // 语音助手会连续吐出大量事件，必须限流，否则 BACK 会被打成连发
                val nowBlock = System.currentTimeMillis()
                if (nowBlock - mLastSystemDialogBackAt >= SYSTEM_DIALOG_BACK_MIN_INTERVAL_MS) {
                    mLastSystemDialogBackAt = nowBlock
                    try {
                        performGlobalAction(GLOBAL_ACTION_BACK)
                    } catch (_: Exception) {
                    }
                    mHandler.postDelayed({
                        LockWindowManager.reassertAlarmOverlayOverSystemDialog(
                            if (shutdownDialog) "shutdown_dialog_after_back" else "voice_assistant_after_back"
                        )
                    }, 120L)
                }
                return
            }
        }

        if (LockWindowManager.isLocked) {
            val className = event.className?.toString()

            if (shouldHandleShutdownDialogEvent(event.eventType, pkgName, className)) {
                Log.i(TAG, "🔔 System dialog detected: type=${event.eventType}, pkg=$pkgName, class=$className")
                LogExporter.logEvent(
                    "SystemDialog",
                    "[DEMOTE_REQUEST] source=CoreService, type=${event.eventType}, pkg=$pkgName, class=$className"
                )
                LockWindowManager.onSystemDialogChanged(true)
            } else if (
                event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
                pkgName != null &&
                !isSystemDialogPackage(pkgName)
            ) {
                // Guard against premature restore: power menu may still be visible while
                // non-system state changes are emitted from underlying windows.
                if (LockWindowManager.shouldAcceptSystemDialogEventRestore()) {
                    LockWindowManager.onSystemDialogChanged(false)
                }
            }
        }
        
        // 🚀 [Strict Mode] Periodic Check for Pending Rules (Throttled 30s)
        val now = System.currentTimeMillis()
        if (now - CoreService.mLastPendingCheck > 30000) {
            if (srv != null) checkPendingUpdates(srv)
            CoreService.mLastPendingCheck = now
        }

        // 1. App Blocking (Millisecond detection - Priority #1)
        if (srv != null) checkAppBlocking(event, srv)
        // 2. Window Content/State Monitoring (Requires root node)
        // 🚀 [Optimization] Exclude TYPE_WINDOWS_CHANGED (4194304) as it rarely contains text and causes overhead.
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED || 
            event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {

            val packageName = event.packageName?.toString().orEmpty()
            val className = event.className?.toString().orEmpty()
            val eventText = event.text?.toString().orEmpty()

            val isVivoLauncherNoise =
                event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED &&
                    (packageName == "com.bbk.launcher2" ||
                        packageName == "com.vivo.launcher" ||
                        packageName == "com.vivo.upslide") &&
                    !className.contains("dialog", ignoreCase = true) &&
                    !className.contains("alert", ignoreCase = true) &&
                    !className.contains("list", ignoreCase = true) &&
                    !className.contains("popup", ignoreCase = true) &&
                    !eventText.contains("卸载", ignoreCase = true) &&
                    !eventText.contains("uninstall", ignoreCase = true) &&
                    !eventText.contains("移除", ignoreCase = true) &&
                    !eventText.contains("删除", ignoreCase = true)

            if (isVivoLauncherNoise) {
                return
            }
            
            // 🚀 [Stability Optimization] Throttle Heavy Node Scanning
            // Only throttle content changes (scrolling/video).
            // State changes (dialogs/new windows) must be scanned IMMEDIATELY to catch uninstall prompts.
            if (event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
                val now = System.currentTimeMillis()
                if (now - CoreService.mLastAntiUninstallTime < 300) return
                CoreService.mLastAntiUninstallTime = now
            }
            // For STATE_CHANGED or WINDOWS_CHANGED, we proceed immediately.
            
            // 🚀 [Fix] Use event source traversal to get root, bypassing implicit 'this' scope issues
            val source = event.source
            var root = source?.window?.root
            if (root == null) root = source 
            
            if (root == null) {
                // 🚀 [Fix] Fallback to active window root if event source is null
                // This is critical during window transitions where event.source might be stale.
                root = rootInActiveWindow
            }





            if (root == null) {
                // 🚀 [Log Optimization] Downgrade to VERBOSE.
                // Null root is expected during window transitions (e.g. WINDOWS_CHANGED_REMOVED)
                // or rapid locale switching. Avoiding Log.w prevents logcat spam.
                if (ProtectionManager.DEBUG) {
                    Log.v(TAG, "CoreService: Root is NULL (after fallback) for event type: ${event.eventType}")
                }
                return
            }

            // Log.d(TAG, "CoreService: Processing Event for ${event.packageName} (Root: $root)")

            try {
                // 2. Anti-Uninstall Protection (Delegated)
                ProtectionManager.onAccessibilityEvent(this, event, root)
                lastAccessibilityDispatchedTime = System.currentTimeMillis()
            } catch (e: Exception) {
                Log.e(TAG, "!!! CoreService: ProtectionManager Crash !!!", e)
            } finally {
                root.recycle()
            }
        }
    }

    private fun checkPendingUpdates(context: Context) {
        try {
            TimeRuleManager.applyPendingToActive(context)
            CreditManager.applyPendingAppsToActive(context)
        } catch (e: Exception) {
            Log.e(TAG, "checkPendingUpdates failed", e)
        }
    }



    private fun checkAppBlocking(event: AccessibilityEvent, context: Context) {
        val service = context as CoreService
        if (LockWindowManager.shouldPauseForegroundBlockingChecks()) {
            return
        }
        // 🚀 [Rule] Cycle Pausing
        // If we are in the "Strict Penalty + Grace" cycle, do absolutely NOTHING.
        // This prevents the "refresh loop" where the underlying blacklisted app keeps triggering hits.
        // 🚀 [Rule] Cycle Pausing - REMOVED
        // We must NOT return early here. If the lock screen was dismissed (e.g. independently),
        // we need to re-detect the blacklist app and re-trigger the lock.
        // LockWindowManager.onBlacklistHit will handle the logic of "Already Locked" or "Grace Period".

        // 🚀 [Enhanced] Allow Content Changes to trigger scan (for Static Mini-Windows)
        // Throttle: For content changes, only scan every 1.5 seconds to save battery
        // For Window State/List changes, scan immediately
        val isImmediate = event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED || 
                          event.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED
        

        if (!isImmediate) {
            // If it's just a content change (scrolling etc.), we throttle the heavy window scan
            val now = System.currentTimeMillis()
            // 🚀 [Fix] Use separate timer for basic event throttling vs heavy window scan
            if (now - service.mLastContentEventTime < 500) return
            service.mLastContentEventTime = now
        }
        
        val packageName = event.packageName?.toString() ?: return
        val eventClassName = event.className?.toString().orEmpty()
        val eventText = buildString {
            append(event.text?.joinToString(" ").orEmpty())
            append(' ')
            append(event.contentDescription?.toString().orEmpty())
        }

        PwaBypassManager.observeAccessibilitySignal(
            context = context,
            packageName = packageName,
            className = eventClassName,
            eventText = eventText
        )

        if (PwaBypassManager.shouldBypassTopPackage(context, packageName)) {
            LogExporter.logEvent(
                "CoreService",
                "[PWA_BYPASS] pkg=$packageName class=$eventClassName"
            )
            return
        }
        
        // 1. Check Free Time AND Segment
        val now = System.currentTimeMillis()
        val isFreeTime = now < CreditManager.getFreeTimeEnd(context)
        val currentSeg = TimeRuleManager.getCurrentSegment(context)
        val isInSegment = currentSeg != null
        val isPrivilege = CreditManager.isWeeklyPrivilegeSessionActive(context)
        val isDndLocked = isInSegment && !isFreeTime
        
        // 🚀 [Fix] Only block blacklist during monitored segments AND not in free time
        val shouldEnforceBlacklist = isDndLocked

        // 🚀 [Entry Cooldown Logic] Ported from MonitorService
        // Trigger cooldown when transitioning FROM Gap/Rest TO Work segment
        
        // Initialize Startup Protection on first run
        if (service.mStartupProtectionEndTime == 0L) {
             service.mStartupProtectionEndTime = now + service.STARTUP_PROTECTION_DURATION
             // Also initialize state to avoid immediate trigger
             service.mLastWasGap = !isInSegment
             service.mLastWasRest = isFreeTime
             service.stateInitialized = true
        }

        val isPastStartupProtection = now > service.mStartupProtectionEndTime

        if (shouldEnforceBlacklist) {
             // We are in a restricted period (In Segment + Not Free)
             if (service.stateInitialized && isPastStartupProtection) {
                 // 🚀 [Fix] 不覆盖已有的活跃冷却期，防止冷却倒计时被重置
                 val existingCoolEnd = CreditManager.getCoolDownEnd(context)
                 if (existingCoolEnd <= now) {
                     if (service.mLastWasGap) {
                         val gapCd = TimeRuleManager.getCoolDownGapSeconds(context)
                         if (gapCd > 0) CreditManager.setCoolDownEnd(context, now + gapCd * 1000L)
                         Log.d("AntiUninstall", "Triggering Gap->Work cooling: $gapCd s")
                     } else if (service.mLastWasRest) {
                         val restCd = TimeRuleManager.getCoolDownRestSeconds(context)
                         if (restCd > 0) CreditManager.setCoolDownEnd(context, now + restCd * 1000L)
                         Log.d("AntiUninstall", "Triggering Rest->Work cooling: $restCd s")
                     }
                 } else {
                     Log.d("AntiUninstall", "Cooldown already active (ends at $existingCoolEnd), skipping override")
                 }
             }
             service.stateInitialized = true
        }
        
        // Update State Memory
        service.mLastWasGap = !isInSegment
        service.mLastWasRest = isFreeTime
        
        // 2. [Priority] Check Blacklist (Single App + Mini Windows)
        // 🚀 We check the event package AND all visible windows to catch floating apps
        // We do this BEFORE filtering "Safe Apps" (Launcher/Settings) because a blocked mini-window
        // might exist on top of a safe app.
        
        var blockedPackage: String? = null
        var blockedFromDirectForeground = false
        var blockedFromUsageFallback = false
        
        // 2.1 Check Event Package (Direct)
        // 🚀 [Fix] BACKGROUND NOISE FILTER & ACTIVE BLOCKING
        // Music apps (e.g. Resso) emit TYPE_WINDOW_CONTENT_CHANGED constantly.
        // We typically block only on STATE/WINDOWS_CHANGED (isImmediate).
        // HOWEVER, if the app is explicitly BLACKLISTED, we must capture it even if it's just "Active Content" (Video).
        // To prevent blocking background music, we verify against the Active Window Root.
        fun shouldBlockPackage(pkg: String): Boolean {
            if (isPrivilege) return false
            val currentSeg = TimeRuleManager.getCurrentSegment(context)
            val isBlacklisted = SegmentBlacklist.isSubjectToBlock(context, currentSeg, pkg)
            val isAppWindowBlocked = AppWindowManager.isManaged(context, pkg) && !AppWindowManager.isAppAllowed(context, pkg)
            return (isDndLocked && isBlacklisted) || isAppWindowBlocked
        }

        if (packageName != context.packageName) {
            val needsBlock = shouldBlockPackage(packageName)
            
            if (needsBlock) {
                 var isConfirmedForeground = isImmediate
                 
                 // If not a window state change (e.g. just video playing/scrolling), verify visibility
                 if (!isConfirmedForeground) {
                     // 🚀 [Verify] Check if this package effectively owns the screen
                     // activeRoot might be null during transitions, but if we have content events, we usually have a root.
                     val activeRoot = service.rootInActiveWindow
                     if (activeRoot != null && activeRoot.packageName == packageName) {
                         isConfirmedForeground = true
                     } else if (activeRoot == null) {
                         // Fallback: If root is null, but we are receiving events from a blacklisted app,
                         // we assume it MIGHT be foreground. But to be safe vs Background Music, we skip.
                         // Better safe than sorry (blocking launcher).
                     }
                 }
                 
                 if (isConfirmedForeground) {
                     blockedPackage = packageName
                     blockedFromDirectForeground = true
                 }
            }
        }

        // 2.2 Check Other Windows (Mini-Window Mode)
        // 🚀 [Fix] Only scan if enforcement is active (in segment AND not free time)
        // AND Throttle to avoid ANR (checking windows is heavy IPC)
        val nowWin = System.currentTimeMillis()
        if (blockedPackage == null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            
            // 🚀 [Performance] Throttle Window Scan (Max once per 1000ms)
            // Excessive scanning causes "Skipped frames" and ANR.
            if (nowWin - service.mLastWindowScanTime > 1000) {
                service.mLastWindowScanTime = nowWin
                
                val startTime = System.currentTimeMillis()
                val windowList = (context as? AccessibilityService)?.windows
                if (windowList != null && windowList.isNotEmpty()) {
                    // 🛑 Debug Logging for Mini-Window Detection
                    // Log.d(TAG, "Scanning ${windowList.size} windows for blacklist...")
                    val currentVisiblePkgs = mutableSetOf<String>()
                    
                    for (window in windowList) {
                        // 仅采纳仍在屏幕上且处于 active/focused 的应用窗口，避免后台残留窗口误判为前台。
                        val bounds = Rect()
                        window.getBoundsInScreen(bounds)
                        if (!shouldAdoptWindowScanCandidate(
                                windowType = window.type,
                                isActive = window.isActive,
                                isFocused = window.isFocused,
                                hasVisibleBounds = !bounds.isEmpty
                            )
                        ) {
                            continue
                        }

                        val winPkg = window.root?.packageName?.toString() ?: "null"
                        val type = window.type
                        
                        if (winPkg != "null" && winPkg != context.packageName) {
                            currentVisiblePkgs.add(winPkg)
                            if (PwaBypassManager.shouldBypassTopPackage(context, winPkg)) {
                                LogExporter.logEvent(
                                    "CoreService",
                                    "[PWA_BYPASS] skip window scan pkg=$winPkg"
                                )
                                continue
                            }
                            if (shouldBlockPackage(winPkg)) {
                                blockedPackage = winPkg
                                Log.d("AntiUninstall", "Detected blocked mini-window: $winPkg (Type: $type)")
                                break
                            }
                        }
                    }
                    lastVisiblePackagesFromWindows = currentVisiblePkgs
                }
            }
        }

        // 2.3 Vivo小窗兜底：当事件包名仅为launcher/upslide时，使用近时UsageStats反推真实前台应用
        val isVivoOverlayCarrier = packageName == "com.bbk.launcher2" ||
            packageName == "com.vivo.launcher" ||
            packageName == "com.vivo.upslide"
        if (blockedPackage == null && shouldEnforceBlacklist && isVivoOverlayCarrier) {
            blockedPackage = detectBlockedPackageFromMiniWindowUsageFallback(
                context = context,
                now = nowWin,
                shouldBlockPackage = { candidatePkg -> shouldBlockPackage(candidatePkg) }
            )
            // 🚀 [Anti-Deadlock] Suppress fallback if user JUST explicitly returned to Home
            if (blockedPackage != null && LockWindowManager.homeClickPendingPackage == blockedPackage) {
                Log.d("AntiUninstall", "[Anti-Deadlock] Suppressing mini-window fallback for $blockedPackage because user just clicked Home")
                blockedPackage = null
            }
            if (blockedPackage != null) {
                blockedFromUsageFallback = true
            }
        }

        if (blockedPackage != null) {
            // 锁机决定已统一由 MonitorService 的 UsageStats 轮询 + 交叉验证（UsageEvents + lastTimeVisible）处理。
            // CoreService 只负责非锁机防护（防卸载、防关权限）。
            // 此处只检测，不再调用 onBlacklistHit/showLockScreen，也不再写原始日志。
            return
        }

        // 3. Safety: Ignore Launcher, Settings, and IMEs (For Segment Blocking)
        // If we reached here, no blacklisted app was found. Now we apply safety filters
        // to prevent blocking the Launcher/Settings itself during restricted segments.
        if (packageName == context.packageName ||
            CreditManager.isLauncher(packageName) || 
            packageName == "com.android.settings" ||
            CreditManager.isInputMethod(context, packageName)) {
            
            // 🚀 [Rule: Grace Period Cancel] 
            // If user exited to launcher/settings/self, cancel any pending grace buffer
            LockWindowManager.cancelGraceBuffer()
            return
        }
            
        // 4. Fallback: If we are not in a monitored package for anti-uninstall, 
        // perform regular segment-based app blocking.
        val monitoredPackages = setOf(
            "com.android.settings", "com.android.packageinstaller", "com.google.android.packageinstaller",
            "com.miui.securitycenter", "com.huawei.systemmanager", "com.coloros.safecenter",
            "com.vivo.permissionmanager", "com.samsung.android.sm", "com.oneplus.security",
            "com.oplus.safecenter", "com.vivo.imanager", "com.iqoo.secure",
            "com.bbk.appstore", "com.oppo.market", "com.xiaomi.market", "com.huawei.appmarket",
            "com.android.vending", "com.coolapk.market", "com.tencent.android.qqdownloader"
        )
        val isMonitoredPackage = monitoredPackages.any { packageName.contains(it) }
        if (isMonitoredPackage) return // Handled by checkAntiUninstall

        // 5. Check Current Business Segment for NON-blacklisted apps
        // (Only reaches here if not blacklisted and not safe app)
        // 按“仅按钮解锁”规则，不再在此路径做自动 dismiss
        return
    }

    private fun detectBlockedPackageFromMiniWindowUsageFallback(
        context: Context,
        now: Long,
        shouldBlockPackage: (String) -> Boolean
    ): String? {
        val usageStatsManager = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager ?: return null
        
        val timeSelector: (android.app.usage.UsageStats) -> Long = { stat ->
            var t = stat.lastTimeUsed
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                try { if (stat.lastTimeVisible > 0) t = stat.lastTimeVisible } catch (e: Exception) { Log.w(TAG, "Failed to read lastTimeVisible", e) }
            }
            t
        }

        val recentActive = usageStatsManager
            .queryUsageStats(
                UsageStatsManager.INTERVAL_DAILY,
                now - MINI_WINDOW_USAGE_LOOKBACK_MS,
                now
            )
            ?.filter { stat -> 
                // 🚀 [Bug Fix] 使用 lastTimeVisible (API 29+) 过滤纯后台播放服务（如汽水音乐）
                val effectiveTime = timeSelector(stat)
                val delta = now - effectiveTime
                delta in 0..MINI_WINDOW_USAGE_LOOKBACK_MS 
            }
            ?.filter { stat ->
                val pkg = stat.packageName
                pkg != null &&
                    pkg != context.packageName &&
                    pkg != "com.android.systemui" &&
                    pkg != "com.bbk.launcher2" &&
                    pkg != "com.vivo.launcher" &&
                    pkg != "com.vivo.upslide" &&
                    !CreditManager.isLauncher(pkg)
            }
            ?.maxByOrNull(timeSelector)
            ?: return null

        val candidatePkg = recentActive.packageName
        val deltaMs = now - timeSelector(recentActive)
        val isBlockable = shouldBlockPackage(candidatePkg)
        if (!shouldAdoptVivoMiniWindowFallback(deltaMs, isBlockable)) {
            return null
        }

        return candidatePkg
    }





    private class NodeIdentifierBudget {
        private val startedAt = SystemClock.uptimeMillis()
        private var visitedNodes = 0
        var exhausted: Boolean = false
            private set

        fun tryVisit(depth: Int): Boolean {
            if (
                depth > NODE_IDENTIFIER_MAX_DEPTH ||
                visitedNodes >= NODE_IDENTIFIER_MAX_NODES ||
                SystemClock.uptimeMillis() - startedAt >= NODE_IDENTIFIER_MAX_DURATION_MS
            ) {
                exhausted = true
                return false
            }
            visitedNodes++
            return true
        }
    }

    private fun hasNodeIdentifier(
        root: AccessibilityNodeInfo?,
        targetId: String,
        depth: Int = 0,
        budget: NodeIdentifierBudget = NodeIdentifierBudget()
    ): Boolean {
        if (root == null) return false
        if (!budget.tryVisit(depth)) return false
        if (root.viewIdResourceName == targetId) return true
        
        val count = root.childCount
        for (i in 0 until count) {
            val child = root.getChild(i)
            if (child != null) {
                if (hasNodeIdentifier(child, targetId, depth + 1, budget)) {
                    child.recycle()
                    return true
                }
                child.recycle()
            }
        }
        return false
    }
    
    // 🛡️ Security: Kill Settings app to prevent accidental accessibility disabling
    private fun killSettingsApp() {
        try {
            val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val settingsPackages = listOf(
                "com.android.settings",
                "com.miui.securitycenter",
                "com.coloros.safecenter",
                "com.vivo.permissionmanager"
            )
            for (pkg in settingsPackages) {
                am.killBackgroundProcesses(pkg)
            }
        } catch (e: Exception) {
            // Silently fail
        }
    }

    override fun onUnbind(intent: Intent?): Boolean {
        Log.e(TAG, "CRITICAL: Accessibility Service Unbound/Terminated by System.")
        Log.e("DeciKeeper", "CRITICAL: CoreService Unbound (onUnbind). Check Settings.")
        // [2026-06-25] 诊断日志：lifecycle 事件，记录 unbind 前 isServiceActive 状态
        // 详见 Issue_AntiUninstallBootGap 尝试 7
        Log.w("DeciKeeper", "[Lifecycle] onUnbind isServiceActive was=$isServiceActive uptimeMs=${android.os.SystemClock.uptimeMillis()}")
        isServiceActive = false
        lastAccessibilitySignalTime = 0L
        instance = null
        stateInitialized = false
        emitServiceStateIfChanged("STOPPED", "ON_UNBIND")
        emitProtectionStateChangeIfChanged("accessibility", "OK", "REVOKED", "ON_UNBIND")
        val systemAccessibilityEnabled = com.decikeeper.app.manager.PermissionManager.isAccessibilityEnabled(this)
        AccessibilityDependencyManager.reconcile(this, systemAccessibilityEnabled)
        // 无障碍服务已解绑时，初始化覆盖层没有继续存在的意义，立即释放，避免卡到超时。
        StartupProtectionManager.stopFastLock(this)
        LockWindowManager.dismissSystemInitOverlay("accessibility_unbound")
        // [Diagnostics-only] clear lifecycle flag for ForegroundDiagnosticCycle
        diagnosticsServiceConnected = false
        mHandler.removeCallbacks(mReminderRunnable)
        mHandler.removeCallbacksAndMessages(null)
        // 🚀 [Fix] Clear LockWindowManager's stale CoreService reference
        // This prevents the new instance from failing due to a dead reference
        try { LockWindowManager.setCoreService(null) } catch (e: Exception) { Log.w(TAG, "Failed to clear CoreService ref", e) }
        // 🚀 Return true to receive onRebind() when user re-enables accessibility
        return true
    }

    /**
     * 🚀 [Fix] Called when accessibility service is re-enabled after onUnbind returned true.
     * This allows CoreService to fully re-initialize without needing a device reboot.
     */
    override fun onRebind(intent: Intent?) {
        super.onRebind(intent)
        Log.i("DeciKeeper", "CoreService onRebind: Re-initializing after re-enable.")
        // [2026-06-25] 诊断日志：lifecycle 事件，标记服务重新绑定
        // 每次 onRebind 都会刷新 AccessibilityProtection.lastServiceConnectedTime，可能掩盖 flapping 问题
        // 详见 Issue_AntiUninstallBootGap 尝试 7
        Log.i("DeciKeeper", "[Lifecycle] onRebind uptimeMs=${android.os.SystemClock.uptimeMillis()}")
        // Perform full initialization just like onServiceConnected
        onServiceConnected()
    }

    override fun onInterrupt() {
        // Framework callback for interrupting Accessibility feedback, not a service disconnect.
        // Android may continue delivering events after this callback, so connection state must remain valid.
        Log.w("DeciKeeper", "[Lifecycle] onInterrupt: feedback interrupted; service remains connected")
    }

    override fun onDestroy() {
        Log.e(TAG, "CoreService Destroyed!")
        Log.e("DeciKeeper", "CRITICAL: CoreService Destroyed (onDestroy).")
        super.onDestroy()
        isServiceActive = false
        lastAccessibilitySignalTime = 0L
        instance = null
        stateInitialized = false
        // [Diagnostics-only] clear lifecycle flag for ForegroundDiagnosticCycle
        diagnosticsServiceConnected = false
        diagnosticsInstancePresent = false
        try { LockWindowManager.setCoreService(null) } catch (e: Exception) { Log.w(TAG, "Failed to clear CoreService ref onDestroy", e) }
        mHandler.removeCallbacksAndMessages(null)
    }

    /**
     * 检查凭据加密存储是否可用。Direct Boot 模式下 CoreService 可能在用户解锁前
     * 就收到无障碍事件，此时读取 CE SharedPreferences 会抛 IllegalStateException。
     */
    private fun isCeStorageAvailable(): Boolean {
        return try {
            getSharedPreferences("decikeeper_startup_state", MODE_PRIVATE)
            true
        } catch (_: IllegalStateException) {
            false
        }
    }
}
