/**
 * =================================================================================
 * [Context Header]
 * * @file: CreditManager.kt
 * * @role: Domain Skill
 * * @description: 额度、黑名单与特权逻辑的统一判定与持久化管理。
 * * @responsibilities:
 * 1. 统一管理黑名单/额度/特权等核心业务状态。
 * 2. 输出一致的判定结果供 UI 与服务层消费。
 * * @context:
 * - 上游: MonitorService, LockWindowManager, RulesFragment
 * - 下游: SharedPreferences, TimeRuleManager
 * * @constraints (必读):
 * 1. 逻辑必须可复用，避免 UI 层重复计算。
 * 2. 需保持线程安全与空值安全。
 * 3. 状态判定必须与需求文档一致。
 * =================================================================================
 */

package com.zhizhi.app.manager

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.os.UserManager
import android.provider.Settings
import android.util.Log
import com.zhizhi.app.data.AnalysisResult
import com.zhizhi.app.data.AndroidLogcatNormalEventSink
import com.zhizhi.app.data.BlacklistStore
import com.zhizhi.app.data.DefaultLogDebugConfig
import com.zhizhi.app.data.DefaultNormalEventRenderer
import com.zhizhi.app.data.LogExporter
import com.zhizhi.app.data.NormalEventLogger
import com.zhizhi.app.data.SegmentBlacklist
import com.zhizhi.app.data.SystemEventClock
import com.zhizhi.app.data.TemporaryAccessQuotaPlan
import com.zhizhi.app.data.TemporaryAccessRefundPlan
import com.zhizhi.app.data.TimeSegment
import com.zhizhi.app.data.UsableCountLedgerMigration
import com.zhizhi.app.data.buildSurplusSnapshotIdentity
import com.zhizhi.app.data.formatUsableCountHalfUnits
import com.zhizhi.app.data.resolveRefundSegmentIndex
import com.zhizhi.app.service.MonitorService
import com.zhizhi.app.ui.LifecycleCooldownSettlement
import com.zhizhi.app.ui.LockWindowManager
import com.zhizhi.app.util.calculateBorrowRepaymentLimitCount
import com.zhizhi.app.util.calculateBorrowRepaymentLimitSeconds
import com.zhizhi.app.util.calculateMaxRawBorrowableCount
import com.zhizhi.app.util.calculateMaxRawBorrowableMinutes
import com.zhizhi.app.util.canOccupyBorrowRepaymentCapacity
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference

























// 顶级数据类 (保持不变)
data class DayStats(val date: String, val segmentMinutes: Map<String, Int>)
data class MonthStats(val month: Int, val totalMinutes: Int, val capacityMinutes: Int = 0)

// === Legacy extension alias for TimeSegment ===
fun TimeSegment.getEffectiveUsableCountLimit(): Int = getEffectiveUsableCountLimitHalfUnits()

object CreditManager {

    data class LifecycleLogSession(val requestId: String, val sessionId: String)

    const val PREFS_NAME = "iron_will_ledger"
    // [Boundary 6] count ledger half-unit charge constants for penalty
    // commits. 1 visible count = 2 half-units. SPEC §Bug 4 acceptance F:
    // current -1 visible count, next -2 visible counts, next-borrow +1.
    private const val COUNT_PENALTY_CURRENT_HALF_UNITS = 2
    private const val COUNT_PENALTY_NEXT_HALF_UNITS = 4
    private const val COUNT_PENALTY_NEXT_BORROW_HALF_UNITS = 2
    private const val KEY_FREE_TIME_END = "free_time_end"
    private const val KEY_PRIVILEGE_MODE = "is_privilege_mode"
    private const val KEY_PRIVILEGE_SESSION_START = "privilege_session_start"
    private const val KEY_PRIVILEGE_SESSION_DURATION = "privilege_session_duration"
    private const val KEY_PRIVILEGE_SESSION_PREDEDUCTED = "privilege_session_prededucted"
    private const val KEY_REST_START_TIME = "rest_start_time"
    private const val KEY_REST_TOTAL_COST = "rest_total_cost"
    private const val KEY_REST_REQUESTED_MINS = "rest_requested_mins"
    private const val KEY_REST_LOG_REQUEST_ID = "rest_log_request_id"
    private const val KEY_REST_LOG_SESSION_ID = "rest_log_session_id"
    private const val KEY_PRIVILEGE_LOG_REQUEST_ID = "privilege_log_request_id"
    private const val KEY_PRIVILEGE_LOG_SESSION_ID = "privilege_log_session_id"
    private const val KEY_PRIVILEGE_SESSION_CHARGED_MINUTES = "privilege_session_charged_minutes"
    private const val KEY_WEEKLY_PRIVILEGE_DURATION_LIMIT = "weekly_privilege_duration_limit_v2"
    private const val KEY_WEEKLY_PRIVILEGE_COUNT_LIMIT = "weekly_privilege_count_limit_v2"
    private const val KEY_LEGACY_WEEKLY_PRIVILEGE_DURATION_LIMIT = "weekly_privilege_duration_limit"
    private const val KEY_LEGACY_WEEKLY_PRIVILEGE_COUNT_LIMIT = "weekly_privilege_limit"
    private const val KEY_WEEKLY_PRIVILEGE_DAILY_LAST_USED = "weekly_privilege_last_used_day"
    private const val WEEKLY_PRIVILEGE_COUNT_PREFIX = "weekly_privilege_count_"
    private const val WEEKLY_PRIVILEGE_DURATION_USED_PREFIX = "weekly_privilege_duration_used_"
    private const val KEY_DND_NATURAL_DAY = "dnd_natural_day_str"
    
    private const val KEY_FIRST_SETUP_DATE = "first_setup_timestamp_v2"
    private const val KEY_MANUAL_END_ADAPTATION = "has_manually_ended_adaptation"
    private const val KEY_COOL_DOWN_END = "cool_down_end_timestamp"

    private fun newLifecycleLogId(prefix: String): String =
        "$prefix-${System.currentTimeMillis()}-${java.util.UUID.randomUUID()}"

    private fun lifecycleLogSession(
        prefs: SharedPreferences,
        requestKey: String,
        sessionKey: String
    ): LifecycleLogSession? {
        val requestId = prefs.getString(requestKey, null) ?: return null
        val sessionId = prefs.getString(sessionKey, null) ?: return null
        return LifecycleLogSession(requestId, sessionId)
    }


    // 🚀 [Optimization] Cache for Quota Check (Short-lived 2s to avoid redundant calculations during rapid scans)
    private var lastQuotaCheckResult: Boolean = false
    private var lastQuotaCheckTime: Long = 0L
    private val restCooldownSettlement = LifecycleCooldownSettlement()
    private const val QUOTA_CACHE_DURATION_MS = 2000L

    private val quotaConfigNormalEventLogger by lazy {
        NormalEventLogger(
            renderer = DefaultNormalEventRenderer,
            sinks = listOf(LogExporter.normalEventSink(), AndroidLogcatNormalEventSink())
        )
    }
    private val quotaConfigEventBridge by lazy {
        QuotaConfigEventBridge(
            clock = SystemEventClock,
            emit = quotaConfigNormalEventLogger::emit,
            debugConfig = DefaultLogDebugConfig
        )
    }

    private val quotaConfigIdSequence = java.util.concurrent.atomic.AtomicLong(0L)
    private fun nextQuotaConfigId(prefix: String): String =
        "${prefix}-${quotaConfigIdSequence.incrementAndGet()}"
    private val borrowTxIdSequence = java.util.concurrent.atomic.AtomicLong(0L)
    private fun nextBorrowTxId(): String =
        "TX-BORROW-${borrowTxIdSequence.incrementAndGet()}"

    // ==========================================
    // 🟢 核心拦截逻辑：极速反应 + 厂商级防误触
    // ==========================================
    
    // 🚀 Helper: Get appropriate SharedPreferences based on lock state
    private fun getPrefs(context: Context): SharedPreferences {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val um = context.getSystemService(UserManager::class.java)
            if (um != null && !um.isUserUnlocked) {
                return context.createDeviceProtectedStorageContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            }
        }
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    // 🚀 Helper: Get Device Protected Prefs for syncing (Nullable if not supported)
    private fun getDeviceProtectedPrefs(context: Context): SharedPreferences? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            context.createDeviceProtectedStorageContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        } else null
    }


    fun shouldBlockApp(context: Context, packageName: String, className: String = ""): Boolean {
        val pkg = packageName.lowercase()

        // 0. 🚀 [Privilege] Completely Free Mode
        if (isPrivilegeMode(context)) return false

        // 0.5. 豁免自己
        if (pkg == context.packageName) return false

        // 0.6. 硬豁免（桌面/设置/输入法/电话/短信/系统导航/时钟/系统跟踪等系统组件）永不拦截
        if (SegmentBlacklist.isHardExempt(context, pkg)) return false
        
        // 4. 输入法 (Always Safe)
        if (isInputMethod(context, pkg)) return false

        // 5. Blacklist Check
        val blacklist = getBlacklistApps(context)
        if (blacklist.any { it.equals(packageName, ignoreCase = true) }) {
            return true
        }

        // 6. App Time Window Managed Check
        // If managed, we treat it as "Monitored" (Subject to Interception)
        if (AppWindowManager.isManaged(context, packageName)) {
            return true
        }

        return false
    }


    fun isLauncher(pkg: String): Boolean = AppSafetyClassifiers.isLauncher(pkg)



    fun isInputMethod(context: Context, pkg: String): Boolean = AppSafetyClassifiers.isInputMethod(context, pkg)

    fun isSafeSystemOrVendor(pkg: String): Boolean = AppSafetyClassifiers.isSafeSystemOrVendor(pkg)

    fun isAccessibilityServiceRunning(context: Context, serviceClass: Class<*>): Boolean {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val runningServices = am.getRunningServices(Int.MAX_VALUE) ?: return false
        for (service in runningServices) {
            if (serviceClass.name == service.service.className) {
                return true
            }
        }
        return false
    }

    fun isAppOnBlacklist(context: Context, packageName: String): Boolean {
        if (packageName == context.packageName) return false
        val blacklist = getBlacklistApps(context)
        if (blacklist.contains(packageName)) return true
        // Special case for Chrome WebAPKs
        if (packageName == "com.android.chrome" && blacklist.any { it.startsWith("org.chromium.webapk.") }) return true
        return false
    }


    // ==========================================
    // (以下为原有的统计和数据管理代码，保持不变，为了文件完整性请确保保留)
    // ==========================================
    
    fun isInAdaptationPeriod(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_MANUAL_END_ADAPTATION, false)) return false
        var firstSetupDate = prefs.getLong(KEY_FIRST_SETUP_DATE, 0L)
        val now = System.currentTimeMillis()
        if (firstSetupDate == 0L) {
            firstSetupDate = now
            prefs.edit().putLong(KEY_FIRST_SETUP_DATE, firstSetupDate).apply()
            return true
        }
        val oneDayMillis = 24 * 60 * 60 * 1000L
        return (now - firstSetupDate) < oneDayMillis
    }

    fun markAdaptationEnded(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(KEY_MANUAL_END_ADAPTATION, false)) {
            prefs.edit().putBoolean(KEY_MANUAL_END_ADAPTATION, true).apply()
            
            // 🚀 [Baseline Snapshot] Save current state as baseline immediately upon adaptation end
            saveBaselineBlacklist(context)
            TimeRuleManager.saveBaselineSnapshot(context)

            LogExporter.i("CreditManager", "Adaptation Ended. Baseline Snapshot Saved.")
        }
    }
    
    fun markFirstSetupDone(context: Context) { markAdaptationEnded(context) }
    
    fun isFirstSetup(context: Context): Boolean = BlacklistStore.isFirstSetup(context)

    // --- Blacklist Management ---

    fun getBlacklistApps(context: Context): Set<String> = BlacklistStore.getBlacklistApps(context)

    // --- 全局黑名单模式（blacklist/whitelist）---
    // 存储的「黑名单集合」始终是有效黑名单；模式仅决定名单页如何展示/编辑。
    fun getBlacklistMode(context: Context): String =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString("blacklist_mode", SegmentBlacklist.LIST_MODE_BLACKLIST)
            ?: SegmentBlacklist.LIST_MODE_BLACKLIST

    fun setBlacklistMode(context: Context, mode: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString("blacklist_mode", mode).apply()
    }

    fun isWhitelistMode(context: Context): Boolean =
        getBlacklistMode(context) == SegmentBlacklist.LIST_MODE_WHITELIST

    fun getUserRemovedApps(context: Context): Set<String> = BlacklistStore.getUserRemovedApps(context)

    internal fun getAppLabel(context: Context, pkg: String): String {
        return try {
            context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(pkg, 0)).toString()
        } catch (e: Exception) { pkg }
    }


    /** 播报所有待生效的延迟变更（黑名单、勿扰时段、全局参数、应用时间窗） */
    private fun logPendingChangesSummary(context: Context) {
        // 1. 黑名单
        if (hasPendingApps(context)) {
            val pending = getPendingBlacklistApps(context) ?: emptySet()
            val active = getBlacklistApps(context)
            val willAdd = pending.minus(active).map { getAppLabel(context, it) }
            val willRemove = active.minus(pending).map { getAppLabel(context, it) }
            if (willAdd.isNotEmpty() || willRemove.isNotEmpty()) {
                LogExporter.i("CreditManager", "PENDING_BLACKLIST | willAdd=${willAdd.joinToString()} | willRemove=${willRemove.joinToString()}")
            }
        }
        // 2. 勿扰时段
        if (TimeRuleManager.hasPendingRules(context)) {
            LogExporter.i("TimeRuleManager", "PENDING_TIME_RULES | will apply pending segments & global params")
        }
        // 3. 应用时间窗
        if (AppWindowManager.hasPendingChanges(context)) {
            LogExporter.i("AppWindowManager", "PENDING_APP_WINDOW | will apply pending app window rules")
        }
    }

    // 🚀 [NEW] Initialize Default Blacklist Logic (Empty by default, waiting for user selection)
    fun initDefaultBlacklist(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (!prefs.contains(BlacklistStore.KEY_BLACKLIST_APPS)) {
            LogExporter.i("CreditManager", "First run detected. Initializing empty blacklist.")
            // Default to EMPTY. We will prompt user to "Quick Import" in AppSelectionActivity.
            prefs.edit().putStringSet(BlacklistStore.KEY_BLACKLIST_APPS, emptySet()).apply()
            BlacklistStore.setCachedBlacklist(emptySet())
            
            // Also ensure adaptation period starts now if not set
            if (!prefs.contains(KEY_FIRST_SETUP_DATE)) {
                prefs.edit().putLong(KEY_FIRST_SETUP_DATE, System.currentTimeMillis()).apply()
            }
            
            // 🚀 [Auto-Baseline]
            saveBaselineBlacklist(context)
        }
    }

    // 🚀 [Refactor] Categorized App Lists for Quick Import
    // 覆盖各大应用市场对应类别下载量前列的 App
    fun getBlacklistCategory(type: String): Set<String> = BlacklistStore.getBlacklistCategory(type)

    /**
     * 检查包名是否属于启用的黑名单类别，返回命中的类别名（comm/video/game/browser），否则 null。
     * 用于新装 App 自动分类加入黑名单。
     */
    fun getMatchedCategory(context: Context, pkg: String): String? {
        if (!BlacklistStore.isAutoBlacklistEnabled(context)) return null
        val enabledCategories = BlacklistStore.getAutoBlacklistCategories(context)
        for (cat in enabledCategories) {
            if (getBlacklistCategory(cat).contains(pkg)) return cat
        }
        return null
    }

    /**
     * 自动将新装 App 加入黑名单（如果属于已启用的四大类别且尚未屏蔽、且未被显式移除）。
     * 被 PackageInstallReceiver（manifest）和 MonitorService（dynamic fallback）共同调用。
     * @return true if app was auto-added, false if skipped (not in category / already blocked / disabled)
     */
    fun tryAutoBlacklistNewPackage(context: Context, pkg: String): Boolean {
        if (!BlacklistStore.isAutoBlacklistEnabled(context)) return false
        val matchedCategory = getMatchedCategory(context, pkg) ?: return false

        val userRemoved = getUserRemovedApps(context)
        if (userRemoved.contains(pkg)) {
            Log.d("CreditManager", "[AutoBlacklist] $pkg was manually removed by user, skipping auto-add")
            return false
        }

        val currentBlacklist = getBlacklistApps(context)
        if (currentBlacklist.contains(pkg)) {
            Log.d("CreditManager", "[AutoBlacklist] $pkg already in blacklist, skipping")
            return false
        }

        val newBlacklist = currentBlacklist.toMutableSet().apply { add(pkg) }
        val effectiveTime = saveBlacklistApps(context, newBlacklist)

        val appLabel = try {
            val pm = context.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        } catch (e: Exception) { pkg }

        val logMsg = if (effectiveTime > 0) {
            "[AutoBlacklist] ADDED: $appLabel ($pkg) category=$matchedCategory pendingEffective=$effectiveTime"
        } else {
            "[AutoBlacklist] ADDED: $appLabel ($pkg) category=$matchedCategory immediate"
        }

        LogExporter.i("CreditManager", logMsg)
        LogExporter.logEvent("AutoBlacklist", logMsg)
        return true
    }

    /** 上一次定期扫描的时间戳，避免频繁扫包 */
    @Volatile
    private var lastPeriodicScanTime = 0L
    private const val PERIODIC_SCAN_INTERVAL_MS = 30_000L

    /**
     * 定期扫描：获取已安装 App 列表，与启用的类别（社交/短视频/游戏/浏览器）求交集，
     * 发现"已装但未屏蔽"的 App 自动加入黑名单。
     * 不依赖广播、不依赖无障碍，只要 MonitorService 在运行就能工作。
     * @return 本次新加入黑名单的 App 数量
     */
    fun periodicAutoBlacklistScan(context: Context): Int {
        if (!BlacklistStore.isAutoBlacklistEnabled(context)) return 0
        val now = System.currentTimeMillis()
        if (now - lastPeriodicScanTime < PERIODIC_SCAN_INTERVAL_MS) return 0
        lastPeriodicScanTime = now

        val pm = context.packageManager

        // 1. 读取所有已安装的包名
        val installedPkgs = try {
            pm.getInstalledApplications(0).map { it.packageName }.toSet()
        } catch (e: Exception) {
            LogExporter.w("CreditManager", "[PeriodicScan] Failed to list installed apps: ${e.message}")
            return 0
        }

        // 2. 构建已启用的类别全集
        val enabledCategories = BlacklistStore.getAutoBlacklistCategories(context)
        if (enabledCategories.isEmpty()) return 0

        val categoryPkgs = mutableSetOf<String>()
        for (cat in enabledCategories) {
            categoryPkgs.addAll(getBlacklistCategory(cat))
        }

        // 3. 求交集：已安装 ∩ 属于类别 - 已在黑名单 - 用户显式移除的 = 需要新加的
        val currentBlacklist = getBlacklistApps(context)
        val userRemoved = getUserRemovedApps(context)
        val toAdd = installedPkgs.intersect(categoryPkgs).subtract(currentBlacklist).subtract(userRemoved)

        if (toAdd.isEmpty()) return 0

        val newBlacklist = currentBlacklist.toMutableSet().apply { addAll(toAdd) }
        saveBlacklistApps(context, newBlacklist)

        LogExporter.i("CreditManager", "[PeriodicScan] Auto-added ${toAdd.size} apps: ${toAdd.joinToString()}")
        LogExporter.logEvent("AutoBlacklist", "PERIODIC_SCAN added=${toAdd.size} pkgs=${toAdd.joinToString()}")
        return toAdd.size
    }

    /**
     * [STRICT MODE] Save Blacklist Apps - with Union Logic & Analysis
     * Rule:
     * - Additions -> Immediate (Blocked in Active List)
     * - Removals -> Delayed (Unblocked only in Pending List, ineffective today)
     */
    fun saveBlacklistApps(context: Context, newApps: Set<String>): Long = BlacklistStore.saveBlacklistApps(context, newApps)

    internal fun emitBlacklistConfigChange(
        context: Context,
        newApps: Set<String>,
        result: AnalysisResult
    ) {
        val currentApps = getBlacklistApps(context)
        if (currentApps == newApps) return
        val effectiveTimeMs = when (result) {
            is AnalysisResult.Mixed -> result.effectiveTime
            else -> 0L
        }
        val apply = if (effectiveTimeMs > 0L) "DEFERRED" else "IMMEDIATE"
        val pendingApps = (newApps.minus(currentApps) + currentApps.minus(newApps))
            .joinToString(",")
        quotaConfigEventBridge.recordConfigChange(
            configId = nextQuotaConfigId("BLACKLIST-SET"),
            scope = "BLACKLIST",
            action = if (newApps.size > currentApps.size) "UPDATE" else "DELETE",
            oldValue = "active=${currentApps.size}",
            newValue = "active=${newApps.size};pending=${pendingApps}",
            effectiveAt = effectiveTimeMs,
            apply = apply
        )
    }

    fun analyzeAndSaveBlacklistApps(context: Context, newApps: Set<String>): AnalysisResult = BlacklistStore.analyzeAndSaveBlacklistApps(context, newApps)
    
    // ==========================================
    // 🔵 Baseline Blacklist
    // ==========================================
    fun saveBaselineBlacklist(context: Context) = BlacklistStore.saveBaselineBlacklist(context)
    
    fun getBaselineBlacklistApps(context: Context): Set<String> = BlacklistStore.getBaselineBlacklistApps(context)
    
    // Legacy mapping (Direct save usually implies force/init, but strictly we should use the main method)
    // For compatibility, if this is called, we assume it's system/restore level.
    // But for UI calls, they should use saveBlacklistApps and handle the return value.
    // We'll redirect saveTargetApps to saveBlacklistApps
    fun saveTargetApps(context: Context, apps: Set<String>) { saveBlacklistApps(context, apps) }
    fun saveTargetAppsImmediately(context: Context, apps: Set<String>) = BlacklistStore.saveTargetAppsImmediately(context, apps)

    fun stagePendingBlacklistApps(context: Context, apps: Set<String>, effectiveTime: Long) = BlacklistStore.stagePendingBlacklistApps(context, apps, effectiveTime)

    fun getPendingBlacklistApps(context: Context): Set<String>? = BlacklistStore.getPendingBlacklistApps(context)

    // Legacy Support (Will be removed after refactoring callers)
    fun getPendingTargetApps(context: Context): Set<String>? = BlacklistStore.getPendingTargetApps(context)
    fun stagePendingTargetApps(context: Context, apps: Set<String>, isWhitelist: Boolean) = BlacklistStore.stagePendingTargetApps(context, apps, isWhitelist)

    fun hasPendingApps(context: Context): Boolean = BlacklistStore.hasPendingApps(context)

    fun revokePendingChanges(context: Context) = BlacklistStore.revokePendingChanges(context)

    fun applyPendingAppsToActive(context: Context) = BlacklistStore.applyPendingAppsToActive(context)

    // 🚀 [Helper] Get readable string for next effective time (from Global params)
    fun getNextEffectiveTimeString(context: Context): String {
        val nextTime = ConfigPendingManager.calculateNextEffectiveTime(context)
        return SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(nextTime))
    }

    fun resetAllData(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val lockedApps = getBlacklistApps(context)
        prefs.edit().clear().apply()
        BlacklistStore.invalidateBlacklistCache() // Clear Cache
        saveBlacklistApps(context, lockedApps)
    }

    fun invalidateCache() {
        BlacklistStore.invalidateBlacklistCache()
        lastQuotaCheckTime = 0L
        lastQuotaCheckResult = false
    }

    fun refreshAfterImport(context: Context, exportedAt: Long) {
        TimeRuleManager.applyPendingToActive(context)
        AppWindowManager.applyPendingToActive(context)
        applyPendingAppsToActive(context)
        checkAndApplyPendingWeeklyLimit(context, exportedAt)

        val todayDate = getBusinessDateString(context)
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString("record_date_str", todayDate)
            .apply()
        migrateFutureCrossDayUsageBackToPreviousBusinessDay(context)

        if (exportedAt > 0L) {
            val exportBusinessDate = getBusinessDateStringAt(context, exportedAt)
            if (exportBusinessDate != todayDate) {
                normalizeUsageForNewBusinessDay(context, todayDate)
            }

            val now = System.currentTimeMillis()
            val exportWeekStart = getThisWeekStartMillis(context, exportedAt)
            val currentWeekStart = getThisWeekStartMillis(context, now)
            if (exportWeekStart != currentWeekStart) {
                resetWeeklyPrivilegeForNewWeek(context)
            }
        }
    }

    private fun resetWeeklyPrivilegeForNewWeek(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val currentWeekStart = getThisWeekStartMillis(context)
        val editor = prefs.edit()

        // Clear daily last-used so privilege can be used in the new week
        editor.remove(KEY_WEEKLY_PRIVILEGE_DAILY_LAST_USED)

        // Clean up old-week privilege usage keys to prevent accumulation
        prefs.all.keys.forEach { key ->
            if (key.startsWith(WEEKLY_PRIVILEGE_COUNT_PREFIX) ||
                key.startsWith(WEEKLY_PRIVILEGE_DURATION_USED_PREFIX)) {
                val weekStart = key.removePrefix(WEEKLY_PRIVILEGE_COUNT_PREFIX)
                    .removePrefix(WEEKLY_PRIVILEGE_DURATION_USED_PREFIX)
                    .toLongOrNull()
                if (weekStart != null && weekStart != currentWeekStart) {
                    editor.remove(key)
                }
            }
        }

        // Force re-apply pending weekly limits for the new week
        editor.putLong("last_weekly_limit_apply_time_v2", 0L)
        editor.apply()

        checkAndApplyPendingWeeklyLimit(context)

        LogExporter.i("CreditManager", "Weekly privilege data reset for new week: $currentWeekStart")
    }

    private fun normalizeUsageForNewBusinessDay(context: Context, dateStr: String) {
        val segments = TimeRuleManager.getTimeSegments(context)
        if (segments.isEmpty()) return

        val currentIndex = TimeRuleManager.getCurrentSegmentIndex(context)
        val nextIndex = if (currentIndex == -1) {
            TimeRuleManager.getNextSegmentIndex(context)
        } else {
            currentIndex
        }
        if (nextIndex == -1) return

        val firstLogicalIndex = TimeRuleManager.getLogicalFirstSegmentIndex(context)
        val markAllPast = (currentIndex == -1 && nextIndex == firstLogicalIndex)

        val pastIndices = mutableSetOf<Int>()
        if (markAllPast) {
            segments.indices.forEach { pastIndices.add(it) }
        } else {
            var i = firstLogicalIndex
            while (i != nextIndex) {
                pastIndices.add(i)
                i = (i + 1) % segments.size
                if (i == firstLogicalIndex) break
            }
        }

        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val editor = prefs.edit()
        val dpPrefs = getDeviceProtectedPrefs(context)
        val dpEditor = dpPrefs?.edit()

        segments.forEachIndexed { idx, seg ->
            if (!seg.isActiveToday()) return@forEachIndexed
            val key = generateSegmentKey(seg)
            val usageKey = "usage_${dateStr}_${key}"
            val borrowKey = "borrow_time_${dateStr}_${key}"
            val privilegeUsageKey = "privilege_usage_${dateStr}_${key}"
            val privilegeSegmentKey = "privilege_${dateStr}_segment_$idx"
            val usageSeconds = if (pastIndices.contains(idx)) seg.limitMins * 60 else 0
            editor.putInt(usageKey, usageSeconds)
            editor.remove(borrowKey)
            editor.remove(privilegeUsageKey)
            editor.remove(privilegeSegmentKey)
            dpEditor?.putInt(usageKey, usageSeconds)
            dpEditor?.remove(borrowKey)
            dpEditor?.remove(privilegeUsageKey)
            dpEditor?.remove(privilegeSegmentKey)
        }
        editor.apply()
        dpEditor?.apply()
    }

    // --- Free Time & Stats ---
    fun setFreeTimeEnd(context: Context, endTimeMillis: Long) {
        // 1. Save to Standard
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putLong(KEY_FREE_TIME_END, endTimeMillis).apply()
        // 2. Sync to Device Protected
        getDeviceProtectedPrefs(context)?.edit()?.putLong(KEY_FREE_TIME_END, endTimeMillis)?.apply()
    }

    fun getFreeTimeEnd(context: Context): Long {
        return getPrefs(context).getLong(KEY_FREE_TIME_END, 0L)
    }

    /**
     * 保存休息会话参数（用于提前结束时计算比例返还）
     */
    fun saveRestSession(context: Context, startTimeMs: Long, totalCostMinutes: Double, requestedMins: Double) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putLong(KEY_REST_START_TIME, startTimeMs)
            .putFloat(KEY_REST_TOTAL_COST, totalCostMinutes.toFloat())
            .putFloat(KEY_REST_REQUESTED_MINS, requestedMins.toFloat())
            .apply()
    }

    // ── 统计出口事件计数打点 (Exit Stats Ledger) ───────────────────────────
    fun recordRestEvent(context: Context) {
        val cal = java.util.Calendar.getInstance()
        val dateStr = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.getDefault()).format(cal.time)
        val hour = cal.get(java.util.Calendar.HOUR_OF_DAY)
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val dailyKey = "exit_rest_count_$dateStr"
        val hourlyKey = "exit_rest_count_${dateStr}_$hour"
        prefs.edit()
            .putInt(dailyKey, prefs.getInt(dailyKey, 0) + 1)
            .putInt(hourlyKey, prefs.getInt(hourlyKey, 0) + 1)
            .apply()
        getDeviceProtectedPrefs(context)?.edit()
            ?.putInt(dailyKey, prefs.getInt(dailyKey, 0))
            ?.putInt(hourlyKey, prefs.getInt(hourlyKey, 0))
            ?.apply()
        AutoBackupManager.triggerDebouncedBackup(context)
    }

    fun recordTempUnlockEvent(context: Context) {
        val cal = java.util.Calendar.getInstance()
        val dateStr = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.getDefault()).format(cal.time)
        val hour = cal.get(java.util.Calendar.HOUR_OF_DAY)
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val dailyKey = "exit_temp_unlock_count_$dateStr"
        val hourlyKey = "exit_temp_unlock_count_${dateStr}_$hour"
        prefs.edit()
            .putInt(dailyKey, prefs.getInt(dailyKey, 0) + 1)
            .putInt(hourlyKey, prefs.getInt(hourlyKey, 0) + 1)
            .apply()
        getDeviceProtectedPrefs(context)?.edit()
            ?.putInt(dailyKey, prefs.getInt(dailyKey, 0))
            ?.putInt(hourlyKey, prefs.getInt(hourlyKey, 0))
            ?.apply()
        AutoBackupManager.triggerDebouncedBackup(context)
    }

    fun recordPrivilegeEvent(context: Context) {
        val cal = java.util.Calendar.getInstance()
        val dateStr = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.getDefault()).format(cal.time)
        val hour = cal.get(java.util.Calendar.HOUR_OF_DAY)
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val dailyKey = "exit_privilege_count_$dateStr"
        val hourlyKey = "exit_privilege_count_${dateStr}_$hour"
        prefs.edit()
            .putInt(dailyKey, prefs.getInt(dailyKey, 0) + 1)
            .putInt(hourlyKey, prefs.getInt(hourlyKey, 0) + 1)
            .apply()
        getDeviceProtectedPrefs(context)?.edit()
            ?.putInt(dailyKey, prefs.getInt(dailyKey, 0))
            ?.putInt(hourlyKey, prefs.getInt(hourlyKey, 0))
            ?.apply()
        AutoBackupManager.triggerDebouncedBackup(context)
    }

    fun recordCooldownAttemptEvent(context: Context) {
        val cal = java.util.Calendar.getInstance()
        val dateStr = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.getDefault()).format(cal.time)
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val dailyKey = "exit_cooldown_attempt_count_$dateStr"
        prefs.edit()
            .putInt(dailyKey, prefs.getInt(dailyKey, 0) + 1)
            .apply()
        getDeviceProtectedPrefs(context)?.edit()
            ?.putInt(dailyKey, prefs.getInt(dailyKey, 0))
            ?.apply()
        AutoBackupManager.triggerDebouncedBackup(context)
    }


    /**
     * 清除休息会话参数（正常结束或进程恢复后）
     */
    fun clearRestSession(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .remove(KEY_REST_START_TIME)
            .remove(KEY_REST_TOTAL_COST)
            .remove(KEY_REST_REQUESTED_MINS)
            .apply()
    }

    /** Logging-only identifiers retained with the existing rest session; never read by policy. */
    fun beginRestLogSession(context: Context): LifecycleLogSession {
        val session = LifecycleLogSession(newLifecycleLogId("REST-REQ"), newLifecycleLogId("REST"))
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putString(KEY_REST_LOG_REQUEST_ID, session.requestId)
            .putString(KEY_REST_LOG_SESSION_ID, session.sessionId)
            .apply()
        return session
    }

    fun getRestLogSession(context: Context): LifecycleLogSession? = lifecycleLogSession(
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE),
        KEY_REST_LOG_REQUEST_ID,
        KEY_REST_LOG_SESSION_ID
    )

    fun clearRestLogSession(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .remove(KEY_REST_LOG_REQUEST_ID).remove(KEY_REST_LOG_SESSION_ID).apply()
    }

    private fun beginPrivilegeLogSession(context: Context): LifecycleLogSession {
        val session = LifecycleLogSession(newLifecycleLogId("PRIV-REQ"), newLifecycleLogId("PRIV"))
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putString(KEY_PRIVILEGE_LOG_REQUEST_ID, session.requestId)
            .putString(KEY_PRIVILEGE_LOG_SESSION_ID, session.sessionId)
            .apply()
        return session
    }

    private fun getPrivilegeLogSession(context: Context): LifecycleLogSession? = lifecycleLogSession(
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE),
        KEY_PRIVILEGE_LOG_REQUEST_ID,
        KEY_PRIVILEGE_LOG_SESSION_ID
    )

    private fun clearPrivilegeLogSession(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .remove(KEY_PRIVILEGE_LOG_REQUEST_ID).remove(KEY_PRIVILEGE_LOG_SESSION_ID).apply()
    }

    data class RestRefundPreview(
        val usedMins: Double,
        val requestedMins: Double,
        val usedRatio: Double,
        val refundRatio: Double,
        val refundAmount: Double
    )

    /**
     * 获取休息提前结束的返还预览。
     */
    fun getRestRefundPreview(context: Context): RestRefundPreview? {
        val prefs = getPrefs(context)
        val startMs = prefs.getLong(KEY_REST_START_TIME, 0L)
        if (startMs <= 0L) return null
        val totalCost = prefs.getFloat(KEY_REST_TOTAL_COST, 0f).toDouble()
        val requestedMins = prefs.getFloat(KEY_REST_REQUESTED_MINS, 0f).toDouble()
        if (requestedMins <= 0.0 || totalCost <= 0.0) return null

        val now = System.currentTimeMillis()
        val usedMs = (now - startMs).coerceAtLeast(0L)
        val usedMins = kotlin.math.round(usedMs / 6000.0) / 10.0
        if (usedMins >= requestedMins) return null // 已用满，无返还

        val usedRatio = (usedMins / requestedMins).coerceIn(0.0, 1.0)
        val unusedCost = totalCost - (usedMins * (totalCost / requestedMins))
        val refundRatio = kotlin.math.max(0.75, 1.0 - usedRatio * 0.25)
        val refundAmount = kotlin.math.round(unusedCost * refundRatio * 10.0) / 10.0

        return RestRefundPreview(
            usedMins = usedMins,
            requestedMins = requestedMins,
            usedRatio = usedRatio,
            refundRatio = refundRatio,
            refundAmount = refundAmount
        )
    }

    /**
     * 获取特权提前结束的返还预览。
     */
    fun getPrivilegeRefundPreview(context: Context): RestRefundPreview? {
        val prefs = getPrefs(context)
        if (!prefs.getBoolean(KEY_PRIVILEGE_MODE, false)) return null
        val startMs = prefs.getLong(KEY_PRIVILEGE_SESSION_START, 0L)
        if (startMs <= 0L) return null
        val requestedMins = prefs.getInt(KEY_PRIVILEGE_SESSION_DURATION, 0).toDouble()
        if (requestedMins <= 0.0) return null

        val now = System.currentTimeMillis()
        val usedMs = (now - startMs).coerceAtLeast(0L)
        val usedMins = kotlin.math.round(usedMs / 6000.0) / 10.0
        if (usedMins >= requestedMins) return null

        val usedRatio = (usedMins / requestedMins).coerceIn(0.0, 1.0)
        val unused = requestedMins - usedMins
        val refundRatio = kotlin.math.max(0.75, 1.0 - usedRatio * 0.25)
        val refundAmount = kotlin.math.round(unused * refundRatio * 10.0) / 10.0

        return RestRefundPreview(
            usedMins = usedMins,
            requestedMins = requestedMins,
            usedRatio = usedRatio,
            refundRatio = refundRatio,
            refundAmount = refundAmount
        )
    }

    // --- Cool Down Management ---
    fun setCoolDownEnd(context: Context, endTime: Long) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putLong(KEY_COOL_DOWN_END, endTime).apply()
    }

    fun getCoolDownEnd(context: Context): Long {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getLong(KEY_COOL_DOWN_END, 0L)
    }

    fun isCooling(context: Context): Boolean {
        val now = System.currentTimeMillis()
        // 🚀 [Fix] 冷却只在休息结束后才生效，避免预设冷却时间干扰休息期间的解锁
        val freeTimeEnd = getFreeTimeEnd(context)
        if (freeTimeEnd > 0L && now < freeTimeEnd) return false
        val cooldownEnd = getCoolDownEnd(context)
        if (now >= cooldownEnd) {
            getRestLogSession(context)?.let { session ->
                restCooldownSettlement.settleIfExpired(now, cooldownEnd) {
                    LockWindowManager.recordRestCooldownAvailable(session)
                    clearRestLogSession(context)
                }
            }
            return false
        }
        return true
    }

    fun getCurrentSegmentName(context: Context): String {
        val currentSegment = TimeRuleManager.getCurrentSegment(context)
        return currentSegment?.name ?: "Restricted"
    }

    // 🚀 [Rule] Unlimited Threshold (User Defined: > 120 mins)
    private const val UNLIMITED_THRESHOLD_MINS = 120
    
    // 🚀 [Rule] Weekly Privilege Limit (Dynamic)
    // const val WEEKLY_PRIVILEGE_LIMIT = 3 // Removed constant
    
    fun getWeeklyPrivilegeLimit(context: Context): Int {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.contains(KEY_WEEKLY_PRIVILEGE_COUNT_LIMIT)) {
            return prefs.getInt(KEY_WEEKLY_PRIVILEGE_COUNT_LIMIT, 3)
        }

        val legacyLimit = prefs.getInt(KEY_LEGACY_WEEKLY_PRIVILEGE_COUNT_LIMIT, 3)
        prefs.edit().putInt(KEY_WEEKLY_PRIVILEGE_COUNT_LIMIT, legacyLimit).apply()
        return legacyLimit
    }

    fun getWeeklyPrivilegeDurationLimit(context: Context): Int {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.contains(KEY_WEEKLY_PRIVILEGE_DURATION_LIMIT)) {
            return prefs.getInt(KEY_WEEKLY_PRIVILEGE_DURATION_LIMIT, 600)
        }

        val legacyLimit = prefs.getInt(KEY_LEGACY_WEEKLY_PRIVILEGE_DURATION_LIMIT, 600)
        prefs.edit().putInt(KEY_WEEKLY_PRIVILEGE_DURATION_LIMIT, legacyLimit).apply()
        return legacyLimit
    }

    fun getWeeklyPrivilegeCount(context: Context): Int {
        return getWeeklyPrivilegeUsageTotal(context, WEEKLY_PRIVILEGE_COUNT_PREFIX)
    }

    fun getThisWeekStartMillis(context: Context): Long {
        return getThisWeekStartMillis(context, System.currentTimeMillis())
    }

    fun getThisWeekStartMillis(context: Context, nowMillis: Long): Long {
        val refreshTime = TimeRuleManager.getRefreshTime(context)
        val parts = refreshTime.split(":")
        val refreshH = parts.getOrNull(0)?.toIntOrNull() ?: 8
        val refreshM = parts.getOrNull(1)?.toIntOrNull() ?: 0

        val cal = Calendar.getInstance()
        cal.set(Calendar.HOUR_OF_DAY, refreshH)
        cal.set(Calendar.MINUTE, refreshM)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        
        // Find the most recent Monday at refreshH:refreshM
        // If today is Monday but before refresh time, it's last week's session
        if (cal.timeInMillis > nowMillis) {
            cal.add(Calendar.DAY_OF_YEAR, -1)
        }
        
        while (cal.get(Calendar.DAY_OF_WEEK) != Calendar.MONDAY) {
            cal.add(Calendar.DAY_OF_YEAR, -1)
        }
        
        return cal.timeInMillis
    }

    fun getNextWeekStartMillis(context: Context): Long {
        val thisWeek = getThisWeekStartMillis(context)
        val cal = Calendar.getInstance()
        cal.timeInMillis = thisWeek
        cal.add(Calendar.WEEK_OF_YEAR, 1)
        return cal.timeInMillis
    }

    private fun getWeeklyPrivilegeUsageTotal(
        context: Context,
        prefix: String,
        baseMillis: Long = System.currentTimeMillis()
    ): Int {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val currentWeekStart = getThisWeekStartMillis(context, baseMillis)
        return getWeeklyPrivilegeUsageEntries(prefs, prefix, currentWeekStart)
            .values
            .sum()
            .coerceAtLeast(0)
    }

    private fun setWeeklyPrivilegeUsageValueAt(
        context: Context,
        prefix: String,
        baseMillis: Long,
        value: Int
    ): Int {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val currentWeekStart = getThisWeekStartMillis(context, baseMillis)
        val currentKey = "$prefix$currentWeekStart"
        val relatedKeys = getWeeklyPrivilegeUsageEntries(prefs, prefix, currentWeekStart).keys

        val editor = prefs.edit().putInt(currentKey, value.coerceAtLeast(0))
        relatedKeys
            .filter { it != currentKey }
            .forEach { editor.remove(it) }
        editor.apply()
        return value.coerceAtLeast(0)
    }

    private fun addWeeklyPrivilegeUsageValueAt(
        context: Context,
        prefix: String,
        baseMillis: Long,
        delta: Int
    ): Int {
        val current = getWeeklyPrivilegeUsageTotal(context, prefix, baseMillis)
        return setWeeklyPrivilegeUsageValueAt(context, prefix, baseMillis, current + delta)
    }

    private fun getWeeklyPrivilegeUsageEntries(
        prefs: SharedPreferences,
        prefix: String,
        currentWeekStart: Long
    ): Map<String, Int> {
        return prefs.all.mapNotNull { (key, rawValue) ->
            if (!key.startsWith(prefix)) return@mapNotNull null

            val candidateWeekStart = key.removePrefix(prefix).toLongOrNull() ?: return@mapNotNull null
            if (!isSameWeeklyPrivilegeLedgerWeek(candidateWeekStart, currentWeekStart)) return@mapNotNull null

            val value = when (rawValue) {
                is Int -> rawValue
                is Long -> rawValue.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                else -> 0
            }.coerceAtLeast(0)

            key to value
        }.toMap()
    }

    private fun isSameWeeklyPrivilegeLedgerWeek(candidateWeekStart: Long, currentWeekStart: Long): Boolean {
        return getWeeklyPrivilegeLedgerDateKey(candidateWeekStart) == getWeeklyPrivilegeLedgerDateKey(currentWeekStart)
    }

    private fun getWeeklyPrivilegeLedgerDateKey(millis: Long): String {
        return SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(millis))
    }
    
    fun getPendingWeeklyPrivilegeLimit(context: Context): Int {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getInt("weekly_privilege_count_limit_pending", 3)
    }

    fun getPendingWeeklyPrivilegeDurationLimit(context: Context): Int {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getInt("weekly_privilege_duration_limit_pending", 600)
    }

    fun getPendingWeeklyPrivilegeEffectiveTime(context: Context): Long {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getLong("weekly_privilege_limit_effective_time", 0L)
    }

    fun hasPendingWeeklyPrivilegeLimit(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.contains("weekly_privilege_count_limit_pending") || 
               prefs.contains("weekly_privilege_duration_limit_pending")
    }

    fun setPendingWeeklyPrivilegeLimit(context: Context, newCountLimit: Int, newDurationLimit: Int, effectiveTime: Long) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putInt("weekly_privilege_count_limit_pending", newCountLimit)
            .putInt("weekly_privilege_duration_limit_pending", newDurationLimit)
            .putLong("weekly_privilege_limit_effective_time", effectiveTime)
            .apply()
    }
    
    fun setWeeklyPrivilegeLimit(context: Context, newCountLimit: Int, newDurationLimit: Int) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putInt(KEY_WEEKLY_PRIVILEGE_COUNT_LIMIT, newCountLimit)
            .putInt(KEY_WEEKLY_PRIVILEGE_DURATION_LIMIT, newDurationLimit)
            .apply()
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
            try {
                context.createDeviceProtectedStorageContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                    .putInt(KEY_WEEKLY_PRIVILEGE_COUNT_LIMIT, newCountLimit)
                    .putInt(KEY_WEEKLY_PRIVILEGE_DURATION_LIMIT, newDurationLimit)
                    .apply()
            } catch (_: Exception) {}
        }
    }
    
    fun clearPendingWeeklyPrivilegeLimit(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .remove("weekly_privilege_count_limit_pending")
            .remove("weekly_privilege_duration_limit_pending")
            .remove("weekly_privilege_limit_effective_time")
            .apply()
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
            try {
                context.createDeviceProtectedStorageContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                    .remove("weekly_privilege_count_limit_pending")
                    .remove("weekly_privilege_duration_limit_pending")
                    .remove("weekly_privilege_limit_effective_time")
                    .apply()
            } catch (_: Exception) {}
        }
    }

    // --- Weekly Privilege Session Management ---

    fun setWeeklyPrivilege(context: Context) {
        val remaining = getWeeklyPrivilegeDurationLimit(context) - getWeeklyPrivilegeDurationUsed(context)
        if (remaining > 0) {
            startWeeklyPrivilege(context, remaining)
        }
    }

    /**
     * 每周特权会话是否仍在进行（不受“夜间禁止时段”显示逻辑影响）。
     * 用于防止用户在上一次特权未结束时重复消耗次数。
     */
    fun isWeeklyPrivilegeSessionActive(context: Context): Boolean {
        finalizeWeeklyPrivilegeIfExpiredOrCrossedMidnight(context)
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(KEY_PRIVILEGE_MODE, false)) return false
        val end = prefs.getLong(KEY_FREE_TIME_END, 0L)
        return System.currentTimeMillis() < end
    }

    fun getCurrentPrivilegeDuration(context: Context): Int {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getInt(KEY_PRIVILEGE_SESSION_DURATION, 0)
    }

    fun isPrivilegeMode(context: Context): Boolean {
        finalizeWeeklyPrivilegeIfExpiredOrCrossedMidnight(context)
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(KEY_PRIVILEGE_MODE, false)) return false
        
        // 🚀 [Rule] 特权仅在"特权可用时间范围"内有效
        val cal = Calendar.getInstance()
        val currentMins = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        if (!TimeRuleManager.isInPrivilegeWindow(context, currentMins)) {
            return false
        }

        return isFreeTime(context)
    }

    private fun getNextMidnightMillis(baseMillis: Long): Long {
        return Calendar.getInstance().run {
            timeInMillis = baseMillis
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            add(Calendar.DAY_OF_YEAR, 1)
            timeInMillis
        }
    }

    /**
     * 自动结算每周特权：
     * - 到 free_time_end 自动结算（按实际生效分钟消耗）
     * - 若会话跨越 24:00，则在 24:00 强制结算，未用部分不计入本周已用时长（等价返还）
     */
    fun finalizeWeeklyPrivilegeIfExpiredOrCrossedMidnight(
        context: Context,
        nowMillis: Long = System.currentTimeMillis()
    ): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(KEY_PRIVILEGE_MODE, false)) return false

        val startTs = prefs.getLong(KEY_PRIVILEGE_SESSION_START, 0L)
        val endTs = prefs.getLong(KEY_FREE_TIME_END, 0L)
        if (startTs <= 0L || endTs <= 0L) return false

        val midnightTs = getNextMidnightMillis(startTs)
        val shouldStopAt = when {
            nowMillis >= midnightTs -> midnightTs
            nowMillis >= endTs -> endTs
            else -> null
        } ?: return false

        stopWeeklyPrivilegeInternal(context, shouldStopAt)
        return true
    }

    fun getWeeklyPrivilegeDurationUsed(context: Context): Int {
        return getWeeklyPrivilegeUsageTotal(context, WEEKLY_PRIVILEGE_DURATION_USED_PREFIX)
    }

    private fun addWeeklyPrivilegeDurationUsedAt(context: Context, baseMillis: Long, minutes: Int) {
        if (minutes <= 0) return
        addWeeklyPrivilegeUsageValueAt(context, WEEKLY_PRIVILEGE_DURATION_USED_PREFIX, baseMillis, minutes)
    }

    private fun subtractWeeklyPrivilegeDurationUsedAt(context: Context, baseMillis: Long, minutes: Int) {
        if (minutes <= 0) return
        addWeeklyPrivilegeUsageValueAt(context, WEEKLY_PRIVILEGE_DURATION_USED_PREFIX, baseMillis, -minutes)
    }

    /**
     * 计算“本次申请”在当前规则下的实际可生效分钟数。
     *
     * 规则：
     * - 仅允许在【特权可用时间范围】内使用；范围外返回 0
     * - 不能超过本周剩余可用时长
     * - 若申请时长会跨越 24:00，则自动截断到 24:00
     */
    fun calculateWeeklyPrivilegeEffectiveMinutes(
        context: Context,
        requestedMinutes: Int,
        nowMillis: Long = System.currentTimeMillis()
    ): Int {
        if (requestedMinutes <= 0) return 0

        val remainingDur = (getWeeklyPrivilegeDurationLimit(context) - getWeeklyPrivilegeDurationUsed(context)).coerceAtLeast(0)
        if (remainingDur <= 0) return 0

        val cal = Calendar.getInstance().apply { timeInMillis = nowMillis }
        val currentMins = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)

        // 🚀 [NEW] Check Privilege Usage Time Window (replaces old refreshTime check)
        if (!TimeRuleManager.isInPrivilegeWindow(context, currentMins)) return 0

        val nextMidnightMillis = Calendar.getInstance().run {
            timeInMillis = nowMillis
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            add(Calendar.DAY_OF_YEAR, 1)
            timeInMillis
        }

        val millisUntilMidnight = (nextMidnightMillis - nowMillis).coerceAtLeast(0L)
        // ceil minutes: 23:59:30 -> 1 分钟（到 24:00 结束）
        val minutesUntilMidnight = ((millisUntilMidnight + 60_000L - 1) / 60_000L).toInt().coerceAtLeast(0)

        // 🚀 [Rule] Auto-Truncate at Privilege Window End (Privilege must end exactly at boundary)
        val windowEndStr = TimeRuleManager.getPrivilegeWindowEnd(context)
        val endParts = windowEndStr.split(":")
        val endH = endParts.getOrNull(0)?.toIntOrNull() ?: 23
        val endM = endParts.getOrNull(1)?.toIntOrNull() ?: 59
        val windowEndMins = endH * 60 + endM
        
        val windowStartStr = TimeRuleManager.getPrivilegeWindowStart(context)
        val startParts = windowStartStr.split(":")
        val startH = startParts.getOrNull(0)?.toIntOrNull() ?: 8
        val startM = startParts.getOrNull(1)?.toIntOrNull() ?: 0
        val windowStartMins = startH * 60 + startM
            
        val targetWindowEndMillis = Calendar.getInstance().run {
            timeInMillis = nowMillis
            set(Calendar.HOUR_OF_DAY, endH)
            set(Calendar.MINUTE, endM)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            
            // Adjust day for Cross-Midnight Window (e.g. 22:00 - 08:00)
            if (windowStartMins > windowEndMins) {
                // If we are in the "Night" part (e.g. 23:00), the end is "Tomorrow 08:00"
                if (currentMins >= windowStartMins) {
                    add(Calendar.DAY_OF_YEAR, 1)
                }
                // If we are in the "Morning" part (e.g. 05:00), the end is "Today 08:00" (Default)
            }
            timeInMillis
        }
        
        val millisUntilWindowEnd = (targetWindowEndMillis - nowMillis).coerceAtLeast(0L)
        val minutesUntilWindowEnd = ((millisUntilWindowEnd + 60_000L - 1) / 60_000L).toInt().coerceAtLeast(0)

        return minOf(requestedMinutes, remainingDur, minutesUntilMidnight, minutesUntilWindowEnd)
    }

    fun startWeeklyPrivilege(context: Context, requestedMinutes: Int): Boolean {
        if (!isWeeklyPrivilegeAvailable(context)) {
            return rejectWeeklyPrivilegeStart(context, getWeeklyPrivilegeUnavailableReason(context) ?: "UNAVAILABLE")
        }

        // 🚫 [Rule] 特权会话进行中时禁止重复开启，避免重复消耗机会
        if (isWeeklyPrivilegeSessionActive(context)) {
            LogExporter.w("CreditManager", "startWeeklyPrivilege: Denied! Session already active")
            return rejectWeeklyPrivilegeStart(context, "ALREADY_ACTIVE")
        }

        // 🚫 [Rule] 每日只允许启用一次特权
        if (!isDailyPrivilegeAvailable(context)) {
            LogExporter.w("CreditManager", "startWeeklyPrivilege: Denied! Daily limit reached")
            return rejectWeeklyPrivilegeStart(context, "DAILY_LIMIT_REACHED")
        }
        
        val now = System.currentTimeMillis()

        // 🚀 [Rule] 仅允许在特权可用时间范围内使用，且不能跨越 24:00。
        val effectiveMinutes = calculateWeeklyPrivilegeEffectiveMinutes(context, requestedMinutes, now)
        if (effectiveMinutes <= 0) {
            val windowStart = TimeRuleManager.getPrivilegeWindowStart(context)
            val windowEnd = TimeRuleManager.getPrivilegeWindowEnd(context)
            LogExporter.w("CreditManager", "startWeeklyPrivilege: Denied! Not in valid window or no quota (window=$windowStart~$windowEnd)")
            return rejectWeeklyPrivilegeStart(context, "WINDOW_OR_QUOTA_UNAVAILABLE")
        }

        val nextMidnightMillis = Calendar.getInstance().run {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            add(Calendar.DAY_OF_YEAR, 1)
            timeInMillis
        }

        val endMillis = minOf(now + (effectiveMinutes * 60 * 1000L), nextMidnightMillis)
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        
        prefs.edit()
            .putBoolean(KEY_PRIVILEGE_MODE, true)
            .putLong(KEY_FREE_TIME_END, endMillis)
            .putLong(KEY_PRIVILEGE_SESSION_START, now)
            .putInt(KEY_PRIVILEGE_SESSION_DURATION, effectiveMinutes)
            .putBoolean(KEY_PRIVILEGE_SESSION_PREDEDUCTED, true)
            .putInt(KEY_PRIVILEGE_SESSION_CHARGED_MINUTES, effectiveMinutes)
            .apply()

        val lifecycleSession = beginPrivilegeLogSession(context)
        LockWindowManager.recordPrivilegeLifecycleStarted(lifecycleSession, effectiveMinutes)

        // 🚀 [Rule] Privilege takes over rest: end rest timers/UI without triggering cooldown
        val intent = Intent(MonitorService.ACTION_END_FREE_TIME).apply {
            setPackage(context.packageName)
            putExtra("privilege_takeover", true)
        }
        context.sendBroadcast(intent)

        // ✅ 预扣：启用即扣除本次“生效分钟”，给用户即时反馈；结束时按实际使用返还差额。
        val privilegeUsageBefore = getWeeklyPrivilegeDurationUsed(context)
        addWeeklyPrivilegeDurationUsedAt(context, now, effectiveMinutes)
        val privilegeUsageAfter = getWeeklyPrivilegeDurationUsed(context)
        if (privilegeUsageAfter != privilegeUsageBefore) {
            LockWindowManager.recordPrivilegeLifecycleQuotaChanged(
                lifecycleSession,
                beforeMinutes = privilegeUsageBefore,
                afterMinutes = privilegeUsageAfter,
                reason = "SESSION_START"
            )
        }

        // Increment count immediately. Usage keys are consolidated because older builds
        // keyed them by Monday@refreshTime, so a refresh-time change can otherwise hide usage.
        val newCount = addWeeklyPrivilegeUsageValueAt(context, WEEKLY_PRIVILEGE_COUNT_PREFIX, now, 1)
        recordPrivilegeEvent(context)
        prefs.edit()
            .putString(KEY_WEEKLY_PRIVILEGE_DAILY_LAST_USED, getBusinessDateString(context)) // 🔴 [Fix] 使用业务日
            .apply()

        LogExporter.i("CreditManager", "Weekly Privilege Session Started: $effectiveMinutes mins")
        LogExporter.logEvent("Privilege", "[START] Weekly privilege started: requested=$requestedMinutes, effective=$effectiveMinutes mins, count=$newCount, ends=${SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(endMillis))}")
        val untilStr = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(endMillis))
        val remainingQuota = getRemainingMinutes(context)
        LogExporter.i("Privilege", "💎 申请特权 | minutes=$effectiveMinutes | until=$untilStr | remainingQuota=${"%.0f".format(remainingQuota)}min")
        return true
    }

    private fun rejectWeeklyPrivilegeStart(context: Context, reason: String): Boolean {
        LockWindowManager.recordPrivilegeRejectedAtCreditBoundary(reason)
        return false
    }

    fun isInPrivilegeAdjustmentWindow(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(KEY_PRIVILEGE_MODE, false)) return false
        val sessionStart = prefs.getLong(KEY_PRIVILEGE_SESSION_START, 0L)
        if (sessionStart <= 0L) return false
        val elapsedMinutes = (System.currentTimeMillis() - sessionStart) / (1000 * 60)
        return elapsedMinutes <= 5
    }

    fun adjustWeeklyPrivilegeDuration(context: Context, newDurationMinutes: Int): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(KEY_PRIVILEGE_MODE, false)) return false
        val sessionStart = prefs.getLong(KEY_PRIVILEGE_SESSION_START, 0L)
        if (sessionStart <= 0L) return false
        val elapsedMinutes = (System.currentTimeMillis() - sessionStart) / (1000 * 60)
        if (elapsedMinutes > 5) return false
        if (!isWeeklyPrivilegeSessionActive(context)) return false

        val oldDuration = prefs.getInt(KEY_PRIVILEGE_SESSION_DURATION, 0)
        val durationDiff = newDurationMinutes - oldDuration
        val now = System.currentTimeMillis()
        val nextMidnightMillis = Calendar.getInstance().run {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            add(Calendar.DAY_OF_YEAR, 1)
            timeInMillis
        }
        val newEndMillis = minOf(sessionStart + (newDurationMinutes * 60 * 1000L), nextMidnightMillis)

        if (durationDiff > 0) {
            addWeeklyPrivilegeDurationUsedAt(context, sessionStart, durationDiff)
        } else if (durationDiff < 0) {
            subtractWeeklyPrivilegeDurationUsedAt(context, sessionStart, -durationDiff)
        }

        prefs.edit()
            .putLong(KEY_FREE_TIME_END, newEndMillis)
            .putInt(KEY_PRIVILEGE_SESSION_DURATION, newDurationMinutes)
            .putInt(KEY_PRIVILEGE_SESSION_CHARGED_MINUTES, newDurationMinutes)
            .apply()

        LogExporter.i("CreditManager", "Weekly Privilege Duration Adjusted: $oldDuration -> $newDurationMinutes mins")
        LogExporter.logEvent("Privilege", "[ADJUST] Weekly privilege duration adjusted: $oldDuration -> $newDurationMinutes mins")
        getPrivilegeLogSession(context)?.let { session ->
            LockWindowManager.recordPrivilegeLifecycleQuotaChanged(
                session,
                beforeMinutes = oldDuration,
                afterMinutes = newDurationMinutes,
                reason = "SESSION_ADJUST"
            )
        }
        return true
    }

    fun stopWeeklyPrivilege(context: Context) {
        stopWeeklyPrivilegeInternal(context, System.currentTimeMillis())
    }

    fun stopWeeklyPrivilegeAt(context: Context, stopAtMillis: Long) {
        stopWeeklyPrivilegeInternal(context, stopAtMillis)
    }

    private fun stopWeeklyPrivilegeInternal(context: Context, stopAtMillis: Long) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(KEY_PRIVILEGE_MODE, false)) return
        val privilegeUsageBeforeSettlement = getWeeklyPrivilegeDurationUsed(context)

        val startTs = prefs.getLong(KEY_PRIVILEGE_SESSION_START, stopAtMillis)
        val requestedMins = prefs.getInt(KEY_PRIVILEGE_SESSION_DURATION, 0)
        val wasPreDeducted = prefs.getBoolean(KEY_PRIVILEGE_SESSION_PREDEDUCTED, false)
        val chargedMins = prefs.getInt(
            KEY_PRIVILEGE_SESSION_CHARGED_MINUTES,
            if (wasPreDeducted) requestedMins else 0
        )
        val effectiveStopAt = stopAtMillis.coerceAtLeast(startTs)

        // Calculate actually used minutes (ceil)
        val elapsedMillis = (effectiveStopAt - startTs).coerceAtLeast(0)
        val elapsedMins = (elapsedMillis / 60000).toInt() + (if (elapsedMillis % 60000 > 0) 1 else 0)
        val finalUsage = kotlin.math.min(requestedMins, elapsedMins)

        if (wasPreDeducted && chargedMins > 0) {
            // 🚀 [比例返还] 提前结束不再全额退，按公式 max(75%, 100% - 已用%×25%)
            val unused = (chargedMins - finalUsage).coerceAtLeast(0)
            val usedRatio = if (requestedMins > 0) finalUsage.toDouble() / requestedMins.toDouble() else 0.0
            val refund = calculateProportionalRefund(unused.toDouble(), usedRatio)

            subtractWeeklyPrivilegeDurationUsedAt(context, startTs, refund.toInt())

            // 异常兜底：若 chargedMins < finalUsage（理论不应发生），补扣差额。
            val extraCharge = (finalUsage - chargedMins).coerceAtLeast(0)
            addWeeklyPrivilegeDurationUsedAt(context, startTs, extraCharge)
        } else {
            // 兼容旧会话/异常状态：未预扣(或仅残留标记但无预扣信息)则在结束时扣除实际使用分钟。
            addWeeklyPrivilegeDurationUsedAt(context, startTs, finalUsage)
        }

        // 📊 [Stats] Record privilege time into separate privilege_usage_* keys (does NOT affect rest quota)
        recordPrivilegeRestUsage(context, startTs, effectiveStopAt)

        prefs.edit()
            .putBoolean(KEY_PRIVILEGE_MODE, false)
            .putLong(KEY_FREE_TIME_END, 0L)
            .remove(KEY_PRIVILEGE_SESSION_START)
            .remove(KEY_PRIVILEGE_SESSION_DURATION)
            .remove(KEY_PRIVILEGE_SESSION_PREDEDUCTED)
            .remove(KEY_PRIVILEGE_SESSION_CHARGED_MINUTES)
            .apply()

        val usedRatio = if (requestedMins > 0) finalUsage.toDouble() / requestedMins.toDouble() else 0.0
        val refundRatio = kotlin.math.max(0.75, 1.0 - usedRatio * 0.25)
        LogExporter.i("CreditManager", "Weekly Privilege Session Stopped. Used: $finalUsage/$requestedMins mins (${(usedRatio*100).toInt()}%), Refund: ${refundRatio * 100}% of unused.")
        LogExporter.logEvent("Privilege", "[STOP] Weekly privilege stopped: used=$finalUsage/$requestedMins mins (${(usedRatio*100).toInt()}%), refundRatio=${(refundRatio*100).toInt()}%")
        getPrivilegeLogSession(context)?.let { session ->
            LockWindowManager.recordPrivilegeLifecycleEnded(
                session,
                reason = if (finalUsage >= requestedMins) "TIME_EXPIRED" else "STOPPED"
            )
            val privilegeUsageAfterSettlement = getWeeklyPrivilegeDurationUsed(context)
            if (privilegeUsageAfterSettlement != privilegeUsageBeforeSettlement) {
                LockWindowManager.recordPrivilegeLifecycleQuotaChanged(
                    session,
                    beforeMinutes = privilegeUsageBeforeSettlement,
                    afterMinutes = privilegeUsageAfterSettlement,
                    reason = "SESSION_END"
                )
            }
        }
        clearPrivilegeLogSession(context)
    }

    /**
     * 记录特权时长到“休息时长”统计：
     * - 仅统计落在勿扰时段的分钟
     * - 空闲(Gap)时段忽略
     * - 分别计入各自时段
     */
    fun recordPrivilegeRestUsage(context: Context, startTs: Long, stopTs: Long) {
        if (stopTs <= startTs) return

        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val usageToAdd = mutableMapOf<String, Int>()

        var cursor = startTs
        while (cursor < stopTs) {
            val segment = TimeRuleManager.getSegmentAt(context, cursor)
            if (segment != null) {
                val segKey = generateSegmentKey(segment)
                val usageKey = getPrivilegeUsageKeyByTimestamp(context, segKey, cursor)
                usageToAdd[usageKey] = (usageToAdd[usageKey] ?: 0) + 1
            }
            cursor += 60_000L
        }

        if (usageToAdd.isNotEmpty()) {
            val editor = prefs.edit()
            usageToAdd.forEach { (key, add) ->
                val current = prefs.getInt(key, 0)
                editor.putInt(key, current + add)
            }
            editor.apply()
        }
    }

    fun checkAndApplyPendingWeeklyLimit(context: Context, exportedAt: Long = 0L) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        // 适应期：所有规则立即生效，绝不保留未生效 pending，也不被旧 pending 覆盖
        if (isInAdaptationPeriod(context)) {
            clearPendingWeeklyPrivilegeLimit(context)
            return
        }

        val lastApplyWeek = prefs.getLong("last_weekly_limit_apply_time_v2", 0L)
        val thisWeekStart = getThisWeekStartMillis(context)
        
        if (lastApplyWeek == 0L) {
             prefs.edit().putLong("last_weekly_limit_apply_time_v2", thisWeekStart).apply()
             return
        }
        // Prefer explicit pending effective time if present. This ensures weekly pending
        // scheduled for next Monday@refreshTime is applied exactly when intended instead
        // of relying only on week-boundary detection which can be brittle.
        val pendingEffective = getPendingWeeklyPrivilegeEffectiveTime(context)
        val now = System.currentTimeMillis()

        if (hasPendingWeeklyPrivilegeLimit(context) && pendingEffective > 0L) {
            // 如果导出时间戳有效，且 pendingEffective 在导出之前就已经到期，说明导出时它就是陈旧死数据，坚决不反向覆盖当前 active！
            if (exportedAt > 0L && pendingEffective <= exportedAt) {
                clearPendingWeeklyPrivilegeLimit(context)
                return
            }

            if (now >= pendingEffective) {
                // Apply pending immediately
                val pendingCount = prefs.getInt("weekly_privilege_count_limit_pending", 3)
                val pendingDur = prefs.getInt("weekly_privilege_duration_limit_pending", 600)

                prefs.edit()
                    .putInt(KEY_WEEKLY_PRIVILEGE_COUNT_LIMIT, pendingCount)
                    .putInt(KEY_WEEKLY_PRIVILEGE_DURATION_LIMIT, pendingDur)
                    .remove("weekly_privilege_count_limit_pending")
                    .remove("weekly_privilege_duration_limit_pending")
                    .remove("weekly_privilege_limit_effective_time")
                    // update last applied week marker to current week start to avoid re-applying
                    .putLong("last_weekly_limit_apply_time_v2", thisWeekStart)
                    .apply()

                LogExporter.i("CreditManager", "✅ Weekly Privilege Limits Updated (by effective time): Count=$pendingCount, Duration=$pendingDur")
                return
            }
            // If pending exists but not yet reached, do nothing.
            return
        }

        // Fallback: if no explicit pending effective time is present, keep previous
        // week-boundary logic to advance the marker and apply any implicit pending.
        if (thisWeekStart > lastApplyWeek) {
            if (hasPendingWeeklyPrivilegeLimit(context)) {
                val pendingCount = prefs.getInt("weekly_privilege_count_limit_pending", 3)
                val pendingDur = prefs.getInt("weekly_privilege_duration_limit_pending", 600)
                
                prefs.edit()
                    .putInt(KEY_WEEKLY_PRIVILEGE_COUNT_LIMIT, pendingCount)
                    .putInt(KEY_WEEKLY_PRIVILEGE_DURATION_LIMIT, pendingDur)
                    .remove("weekly_privilege_count_limit_pending")
                    .remove("weekly_privilege_duration_limit_pending")
                    .remove("weekly_privilege_limit_effective_time")
                    .putLong("last_weekly_limit_apply_time_v2", thisWeekStart)
                    .apply()
                LogExporter.i("CreditManager", "✅ Weekly Privilege Limits Updated (week boundary): Count=$pendingCount, Duration=$pendingDur")
            } else {
                prefs.edit().putLong("last_weekly_limit_apply_time_v2", thisWeekStart).apply()
            }
        }
    }

    fun forceApplyPendingWeeklyLimit(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (hasPendingWeeklyPrivilegeLimit(context)) {
            val pendingCount = prefs.getInt("weekly_privilege_count_limit_pending", 3)
            val pendingDur = prefs.getInt("weekly_privilege_duration_limit_pending", 600)
            
            prefs.edit()
                .putInt(KEY_WEEKLY_PRIVILEGE_COUNT_LIMIT, pendingCount)
                .putInt(KEY_WEEKLY_PRIVILEGE_DURATION_LIMIT, pendingDur)
                .remove("weekly_privilege_count_limit_pending")
                .remove("weekly_privilege_duration_limit_pending")
                .remove("weekly_privilege_limit_effective_time")
                .apply()
            LogExporter.i("CreditManager", "✅ Weekly Privilege Limits Force Updated (Adaptation/Immediate)")
        }
    }

    fun isWeeklyPrivilegeAvailable(context: Context): Boolean {
        checkAndApplyPendingWeeklyLimit(context)
        if (!isDailyPrivilegeAvailable(context)) return false
        val usedCount = getWeeklyPrivilegeCount(context)
        val countLimit = getWeeklyPrivilegeLimit(context)
        val usedDur = getWeeklyPrivilegeDurationUsed(context)
        val durLimit = getWeeklyPrivilegeDurationLimit(context)
        
        return usedCount < countLimit && usedDur < durLimit
    }

    /**
     * 获取每周特权不可用原因，若可用则返回 null。
     */
    fun getWeeklyPrivilegeUnavailableReason(context: Context, nowMillis: Long = System.currentTimeMillis()): String? {
        if (isWeeklyPrivilegeSessionActive(context)) return "进行中"
        if (!isDailyPrivilegeAvailable(context)) return "今日已用"
        val usedCount = getWeeklyPrivilegeCount(context)
        val countLimit = getWeeklyPrivilegeLimit(context)
        if (usedCount >= countLimit) return "本周次数耗尽"
        val remainingDur = getRemainingWeeklyPrivilegeDuration(context)
        if (remainingDur <= 0) return "本周时长耗尽"

        val cal = Calendar.getInstance().apply { timeInMillis = nowMillis }
        val currentMins = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        if (!TimeRuleManager.isInPrivilegeWindow(context, currentMins)) return "时段外"

        val effective = calculateWeeklyPrivilegeEffectiveMinutes(context, 1, nowMillis)
        if (effective <= 0) return "时间不足"
        return null
    }

    fun isDailyPrivilegeAvailable(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val today = getBusinessDateString(context) // 🔴 [Fix] 使用业务日而非自然日，避免零点误判
        val lastUsed = prefs.getString(KEY_WEEKLY_PRIVILEGE_DAILY_LAST_USED, null)
        return lastUsed != today
    }

    fun getRemainingWeeklyPrivilegeDuration(context: Context): Int {
        val used = getWeeklyPrivilegeDurationUsed(context)
        val limit = getWeeklyPrivilegeDurationLimit(context)
        return (limit - used).coerceAtLeast(0)
    }

    fun getRemainingWeeklyPrivilegeCount(context: Context): Int {
        val used = getWeeklyPrivilegeCount(context)
        val limit = getWeeklyPrivilegeLimit(context)
        return (limit - used).coerceAtLeast(0)
    }

    private fun isSegmentPrivileged(context: Context, segmentIndex: Int): Boolean {
        val dateStr = getBusinessDateString(context)
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean("privilege_${dateStr}_segment_$segmentIndex", false)
    }
    
    // 🚀 [NEW] Public method to check if CURRENT segment is privileged (for "Free Phase" logic)
    fun isCurrentSegmentPrivileged(context: Context): Boolean {
        val currentIndex = TimeRuleManager.getCurrentSegmentIndex(context)
        if (currentIndex == -1) return false // Not in any segment
        return isSegmentPrivileged(context, currentIndex)
    }

    fun isFreeTime(context: Context): Boolean {
        return System.currentTimeMillis() < getFreeTimeEnd(context)
    }

    fun getRemainingMinutes(context: Context): Double {
        checkDayChange(context)
        // [TASK-04 / F-01] 在关键读取路径前调 seam,确保自然日刷新事件能在首次关键读取即时观察到
        observeDndNaturalDay(context, System.currentTimeMillis())
        val currentIndex = TimeRuleManager.getCurrentSegmentIndex(context)

        // [WP-02-T01 / F-02 removed] v3 撤掉刚跨过自然日 00:00 时屏蔽旧自然日 DND 日状态与结余可见性的短路逻辑
        //   R2(旧日未启用段不结余) + R3(段内不结算) 由 getSegmentBusinessDateString 业务日过滤独立保证

        // 零点附近诊断日志（仅在 23:59~00:01 输出，用于排查跨日额度问题）
        val diagCal = Calendar.getInstance()
        val diagH = diagCal.get(Calendar.HOUR_OF_DAY)
        val diagM = diagCal.get(Calendar.MINUTE)
        if ((diagH == 23 && diagM >= 59) || (diagH == 0 && diagM <= 1)) {
            val bizDate = getBusinessDateString(context)
            val recordDate = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString("record_date_str", "")
            val segName = if (currentIndex >= 0) TimeRuleManager.getTimeSegments(context).getOrNull(currentIndex)?.name ?: "?" else "GAP"
            LogExporter.i("CreditManager", "MIDNIGHT_DIAG | currentIndex=$currentIndex($segName) | bizDate=$bizDate | recordDate=$recordDate | time=${SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(diagCal.time)}")
        }

        if (currentIndex == -1) {
            return getAllPreviousSegmentsRemainingMinutes(context)
        }
        val segments = TimeRuleManager.getTimeSegments(context)
        val firstLogicalIndex = TimeRuleManager.getLogicalFirstSegmentIndex(context)
        var totalRemainingSeconds = 0

        // Logical Loop: Start from firstLogicalIndex, wrap around until currentIndex
        // Each segment's unused quota rolls over to the current segment.
        // Cross-day segments reliably use segment-aware keys for consistent read/write.
        var i = firstLogicalIndex
        while (true) {
            val seg = segments[i]

            // Skip inactive segments for rollover calculation
            if (!seg.isActiveToday()) {
                if (i == currentIndex) break
                i = (i + 1) % segments.size
                if (i == firstLogicalIndex) break
                continue
            }


            // 只滚存同一业务天的段，跨物理午夜后明天的段不应加入
            val currentBizDate = getSegmentBusinessDateString(context, segments[currentIndex])
            if (getSegmentBusinessDateString(context, seg) != currentBizDate) {
                if (i == currentIndex) break
                i = (i + 1) % segments.size
                if (i == firstLogicalIndex) break
                continue
            }
            val usedSeconds = getUsedSecondsForSegment(context, seg)
            // Privilege Check: override limit if privileged
            val isPrivileged = isSegmentPrivileged(context, i)
            val effectiveLimitSeconds = if (isPrivileged) 9999 * 60 else seg.limitMins * 60

            // Calculate remaining for this specific segment
            var segRemainingSeconds = (effectiveLimitSeconds - usedSeconds).coerceAtLeast(0)

            // Flush unlimited segments (no carryover)
            if (i != currentIndex) {
                val segDuration = seg.getDurationMinutes()
                val isEffectivelyUnlimited = isPrivileged ||
                                              (segDuration > 0 && seg.limitMins > segDuration * 0.5)
                if (isEffectivelyUnlimited) {
                    segRemainingSeconds = 0
                }
            }

            totalRemainingSeconds += segRemainingSeconds

            if (i == currentIndex) break
            i = (i + 1) % segments.size
            if (i == firstLogicalIndex) break
        }
        return totalRemainingSeconds / 60.0
    }
    
    fun getAllPreviousSegmentsRemainingMinutes(context: Context): Double {
        // [TASK-04 / F-01] 关键读取路径前先调 seam
        observeDndNaturalDay(context, System.currentTimeMillis())
        val segments = TimeRuleManager.getTimeSegments(context)
        val currentIndex = TimeRuleManager.getCurrentSegmentIndex(context)
        val targetIndex = if (currentIndex == -1) {
             TimeRuleManager.getNextSegmentIndex(context)
        } else {
             currentIndex
        }

        val firstLogicalIndex = TimeRuleManager.getLogicalFirstSegmentIndex(context)

        if (targetIndex == -1 || targetIndex == firstLogicalIndex) return 0.0

        // [WP-02-T01 / F-02 removed] v3 撤掉刚跨过自然日 00:00 时把旧日 DND 日状态视为未启用的短路

        // 🔴 [BugFix] 跨天过滤：只累计与目标段同一业务天的前序段，避免昨天未生效段被累计
        val targetBizDate = getSegmentBusinessDateString(context, segments[targetIndex])

        var totalRemainingSeconds = 0
        var i: Int = firstLogicalIndex

        while (i != targetIndex) {
            val seg = segments[i]

            if (!seg.isActiveToday()) {
                i = (i + 1) % segments.size
                if (i == firstLogicalIndex) break
                continue
            }

            // 🔴 [BugFix] 跨天过滤：跳过不属于同一业务天的段
            if (getSegmentBusinessDateString(context, seg) != targetBizDate) {
                LogExporter.i("CreditManager", "getAllPreviousSegmentsRemainingMinutes: skip seg=${seg.name} (bizDate=${getSegmentBusinessDateString(context, seg)} != targetBizDate=$targetBizDate)")
                i = (i + 1) % segments.size
                if (i == firstLogicalIndex) break
                continue
            }

            val usedSeconds = getUsedSecondsForSegment(context, seg)

            val isPrivileged = isSegmentPrivileged(context, i)
            val effectiveLimitSeconds = if (isPrivileged) 9999 * 60 else seg.limitMins * 60

            val segDuration = seg.getDurationMinutes()
            val isEffectivelyUnlimited = isPrivileged ||
                                          (segDuration > 0 && seg.limitMins > segDuration * 0.5)

            if (isEffectivelyUnlimited) {
                // This segment's balance is flushed.
            } else {
                totalRemainingSeconds += (effectiveLimitSeconds - usedSeconds).coerceAtLeast(0)
            }

            i = (i + 1) % segments.size
            if (i == firstLogicalIndex) break
        }
        return totalRemainingSeconds / 60.0
    }

    /** 获取前序段结余总额度（不计消耗，仅限额总和） */
    fun getAllPreviousSegmentsTotalMinutes(context: Context): Double {
        val segments = TimeRuleManager.getTimeSegments(context)
        val currentIndex = TimeRuleManager.getCurrentSegmentIndex(context)
        val targetIndex = if (currentIndex == -1) {
             TimeRuleManager.getNextSegmentIndex(context)
        } else {
             currentIndex
        }
        val firstLogicalIndex = TimeRuleManager.getLogicalFirstSegmentIndex(context)
        if (targetIndex == -1 || targetIndex == firstLogicalIndex) return 0.0

        val targetBizDate = getSegmentBusinessDateString(context, segments[targetIndex])
        var totalLimitSeconds = 0
        var i = firstLogicalIndex

        while (i != targetIndex) {
            val seg = segments[i]
            if (!seg.isActiveToday()) {
                i = (i + 1) % segments.size
                if (i == firstLogicalIndex) break
                continue
            }
            if (getSegmentBusinessDateString(context, seg) != targetBizDate) {
                i = (i + 1) % segments.size
                if (i == firstLogicalIndex) break
                continue
            }
            val isPrivileged = isSegmentPrivileged(context, i)
            val effectiveLimitSeconds = if (isPrivileged) 9999 * 60 else seg.limitMins * 60
            val segDuration = seg.getDurationMinutes()
            val isEffectivelyUnlimited = isPrivileged ||
                                          (segDuration > 0 && seg.limitMins > segDuration * 0.5)
            if (!isEffectivelyUnlimited) {
                totalLimitSeconds += effectiveLimitSeconds
            }
            i = (i + 1) % segments.size
            if (i == firstLogicalIndex) break
        }
        return totalLimitSeconds / 60.0
    }

    data class SurplusCarrySnapshot(
        val identity: String,
        val timeTotal: Double,
        val countTotal: Int
    )

    /**
     * Capture the actual amount carried into this segment once. The identity guard keeps
     * the denominator stable while the carried balance is consumed later in the segment.
     */
    fun captureSurplusCarrySnapshot(context: Context, segment: TimeSegment): SurplusCarrySnapshot {
        val identity = buildSurplusSnapshotIdentity(
            getSegmentBusinessDateString(context, segment),
            generateSegmentKey(segment)
        )
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.getString("surplus_snapshot_identity", null) != identity) {
            prefs.edit()
                .putString("surplus_snapshot_identity", identity)
                .putInt(
                    "surplus_snapshot_time_seconds",
                    kotlin.math.round(getAllPreviousSegmentsRemainingMinutes(context) * 60.0).toInt()
                )
                .putInt("surplus_snapshot_count", getAllPreviousSegmentsRemainingUsableCountHalfUnits(context))
                .apply()
        }
        return SurplusCarrySnapshot(
            identity = identity,
            timeTotal = prefs.getInt("surplus_snapshot_time_seconds", 0) / 60.0,
            countTotal = prefs.getInt("surplus_snapshot_count", 0)
        )
    }

    fun getPreviousSegmentName(context: Context): String {
        val segments = TimeRuleManager.getTimeSegments(context)
        val currentIndex = TimeRuleManager.getCurrentSegmentIndex(context)
        if (currentIndex <= 0) return ""
        return segments[currentIndex - 1].name
    }

    // 🆕 格式化分钟数用于日志输出
    private fun formatMinutesForLog(minutes: Double): String {
        val rounded = kotlin.math.round(minutes * 10.0) / 10.0
        return if (kotlin.math.abs(rounded - rounded.toInt()) < 0.0001) {
            rounded.toInt().toString()
        } else {
            String.format(Locale.US, "%.1f", rounded)
        }
    }

    fun getNextSegmentRemainingMinutes(context: Context): Double {
        val segments = TimeRuleManager.getTimeSegments(context)
        val currentIndex = TimeRuleManager.getCurrentSegmentIndex(context)
        if (currentIndex == -1 || currentIndex >= segments.size - 1) return 0.0
        val nextSegment = segments[currentIndex + 1]
        val nextUsedSeconds = getUsedSecondsForSegment(context, nextSegment)
        return ((nextSegment.limitMins * 60 - nextUsedSeconds).coerceAtLeast(0)) / 60.0
    }

    fun getNextSegmentName(context: Context): String {
        val segments = TimeRuleManager.getTimeSegments(context)
        val currentIndex = TimeRuleManager.getCurrentSegmentIndex(context)
        if (currentIndex == -1 || currentIndex >= segments.size - 1) return ""
        return segments[currentIndex + 1].name
    }



    // 🚀 [NEW] Check if user has ANY way to unlock (Quota, Surplus, or Borrow)
    fun hasAvailableQuotaCombined(context: Context): Boolean {
        // 🚀 [Optimization] Fast Return from Cache
        val now = System.currentTimeMillis()
        if (now - lastQuotaCheckTime < QUOTA_CACHE_DURATION_MS) {
            return lastQuotaCheckResult
        }

        val startTime = System.currentTimeMillis()

        // Time Availability Check
        val currentMins = getCurrentSegmentRemainingMinutes(context)
        val prevMins = getAllPreviousSegmentsRemainingMinutes(context)
        val canBorrowMins = canBorrowFromNext(context, 1.0) // Check if we can borrow at least 1 min
        val result = currentMins > 0 || prevMins > 0 || canBorrowMins

        // Update Cache
        lastQuotaCheckResult = result
        lastQuotaCheckTime = now

        val duration = System.currentTimeMillis() - startTime
        if (duration > 50) {
            LogExporter.w("CreditManager", "⚠️ slow hasAvailableQuotaCombined: ${duration}ms")
        }

        return result
    }


    // ═══════════════════════════════════════════════════════════════
    // 借用加权计算（从大到小排列，便于 grep "* 2 + * 1 + * 0"）
    //
    // 公式：×2(惩罚) × [×2(冷却秒) + ×1(勿扰秒) + ×0(空档秒)]
    // 次数 = 加权结果 / 申请秒数（四舍五入）
    // ═══════════════════════════════════════════════════════════════

    /**
     * 借用时长加权扣费。
     * 公式：×2(惩罚) × [×2(冷却秒) + ×1(勿扰秒) + ×0(空档秒)]
     * 从大到小排列，便于 grep。
     */
    fun calculateWeightedBorrowSeconds(
        coolingOverlapSec: Int,
        dndOverlapSec: Int,
        gapOverlapSec: Int
    ): Int {
        // 从大到小：cooling×2 + dnd×1 + gap×0
        val zoneWeighted = coolingOverlapSec * 2 + dndOverlapSec * 1 + gapOverlapSec * 0
        return zoneWeighted * 2  // 借用惩罚 ×2
    }

    /**
     * 借用次数 = 加权结果 / 申请秒数（四舍五入）
     */
    fun calculateWeightedBorrowCounts(weightedSec: Int, totalBorrowSec: Int): Int {
        if (totalBorrowSec <= 0) return 1
        return Math.round(weightedSec.toDouble() / totalBorrowSec).toInt()
    }

    /**
     * 检查能否借用下一段额度（时长 AND 次数双维度判定）。
     * 默认所有时间落在勿扰时段（向后兼容）。
     */
    fun canBorrowFromNext(context: Context, requestedMinutes: Double): Boolean {
        val borrowSec = kotlin.math.round(requestedMinutes * 60).toInt()
        return canBorrowFromNextWithZones(context, requestedMinutes, 0, borrowSec, 0)
    }

    /**
     * 检查能否借用下一段额度（含加权计算）。
     * @param coolingOverlapSec 落在冷却期的秒数
     * @param dndOverlapSec 落在勿扰时段的秒数
     * @param gapOverlapSec 落在空档期的秒数
     */
    fun canBorrowFromNextWithZones(
        context: Context,
        requestedMinutes: Double,
        coolingOverlapSec: Int,
        dndOverlapSec: Int,
        gapOverlapSec: Int
    ): Boolean {
        val segments = TimeRuleManager.getTimeSegments(context)
        val currentIndex = TimeRuleManager.getCurrentSegmentIndex(context)
        val borrowPercent = TimeRuleManager.getBorrowPercentage(context)
        val borrowSec = kotlin.math.round(requestedMinutes * 60).toInt()
        val weightedSec = calculateWeightedBorrowSeconds(coolingOverlapSec, dndOverlapSec, gapOverlapSec)
        val weightedCounts = calculateWeightedBorrowCounts(weightedSec, borrowSec)

        if (currentIndex == -1) {
            // 🚀 [Gap Borrow] 空档期：从下一段借用
            val nextIdx = TimeRuleManager.getNextSegmentIndex(context)
            if (nextIdx == -1) return false
            val nextSegment = segments[nextIdx]
            val repaymentLimitSec = calculateBorrowRepaymentLimitSeconds(nextSegment.limitMins, borrowPercent)
            val alreadyBorrowedSec = kotlin.math.round(getBorrowedMinutesFromNext(context) * 120).toInt()
            // 次数维度
            if (!canBorrowCountHalfUnitsFromNext(context, weightedCounts)) return false
            return canOccupyBorrowRepaymentCapacity(repaymentLimitSec, alreadyBorrowedSec, weightedSec)
        }

        // 🔴 [Fix] Last Segment of the Business Day CANNOT borrow from First (Next Day)
        if (TimeRuleManager.isLastLogicalSegment(context, currentIndex)) return false

        val nextIndex = (currentIndex + 1) % segments.size
        val nextSegment = segments[nextIndex]

        // ===== 时长维度 =====
        val remaining = getRemainingMinutes(context)
        val remainingSec = kotlin.math.round(remaining * 60).toInt()
        if (remainingSec >= weightedSec) return false  // 当前够用，不需借

        val deficitWeightedSec = weightedSec - remainingSec
        val repaymentLimitSec = calculateBorrowRepaymentLimitSeconds(nextSegment.limitMins, borrowPercent)
        val alreadyBorrowedWeightedSec = kotlin.math.round(getBorrowedMinutesFromNext(context) * 120).toInt()
        if (!canOccupyBorrowRepaymentCapacity(repaymentLimitSec, alreadyBorrowedWeightedSec, deficitWeightedSec)) return false

        // ===== 次数维度（AND 关系）=====
        if (!canBorrowCountHalfUnitsFromNext(context, weightedCounts)) return false

        return true
    }

    fun canBorrowTimeFromNext(context: Context): Boolean {
        val segments = TimeRuleManager.getTimeSegments(context)
        val currentIndex = TimeRuleManager.getCurrentSegmentIndex(context)
        val borrowPercent = TimeRuleManager.getBorrowPercentage(context)
        if (borrowPercent == 0) return false

        if (currentIndex == -1) {
            // 🚀 [Gap Borrow] 空档期：从下一段借用，只记双倍惩罚，不记借入
            val nextIdx = TimeRuleManager.getNextSegmentIndex(context)
            if (nextIdx == -1) return false
            return segments[nextIdx].limitMins >= 2
        }

        if (TimeRuleManager.isLastLogicalSegment(context, currentIndex)) return false
        val nextIndex = (currentIndex + 1) % segments.size
        return segments[nextIndex].limitMins >= 2
    }

    fun borrowFromNext(context: Context, borrowMinutes: Int) {
        borrowSecondsFromNext(context, borrowMinutes * 60)
    }

    fun borrowFromNext(context: Context, borrowMinutes: Double) {
        borrowSecondsFromNext(context, kotlin.math.round(borrowMinutes * 60).toInt())
    }

    /**
     * 借用下段额度（加权版）。
     * @param coolingOverlapSec 落在冷却期的秒数（默认 0）
     * @param dndOverlapSec 落在勿扰时段的秒数（默认 = borrowSeconds）
     * @param gapOverlapSec 落在空档期的秒数（默认 0）
     */
    fun borrowFromNext(
        context: Context,
        borrowMinutes: Double,
        coolingOverlapSec: Int,
        dndOverlapSec: Int,
        gapOverlapSec: Int
    ) {
        val borrowSec = kotlin.math.round(borrowMinutes * 60).toInt()
        borrowSecondsFromNextWeighted(context, borrowSec, coolingOverlapSec, dndOverlapSec, gapOverlapSec)
    }

    private fun borrowSecondsFromNext(context: Context, borrowSeconds: Int) {
        // 向后兼容：默认全部落在勿扰时段（×1），与旧行为一致 = borrowSeconds * 2
        borrowSecondsFromNextWeighted(context, borrowSeconds, 0, borrowSeconds, 0)
    }

    /**
     * 借用下段额度（加权扣费）。
     * 时长 = ×2(惩罚) × [×2(冷却秒) + ×1(勿扰秒) + ×0(空档秒)]
     */
    private fun borrowSecondsFromNextWeighted(
        context: Context,
        borrowSeconds: Int,
        coolingOverlapSec: Int,
        dndOverlapSec: Int,
        gapOverlapSec: Int
    ) {
        val segments = TimeRuleManager.getTimeSegments(context)
        val currentIndex = TimeRuleManager.getCurrentSegmentIndex(context)
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val edit = prefs.edit()

        // 加权扣费 = ×2(惩罚) × [×2(冷却) + ×1(勿扰) + ×0(空档)]
        val weightedSec = calculateWeightedBorrowSeconds(coolingOverlapSec, dndOverlapSec, gapOverlapSec)

        if (currentIndex == -1) {
            // 🚀 [Gap Borrow] 空档期借用：只记加权惩罚到下一段，不记借入（无当前段）
            val nextIdx = TimeRuleManager.getNextSegmentIndex(context)
            if (nextIdx == -1) return
            val nextSegment = segments[nextIdx]
            val nextUsageKey = getUsageKeyForSegment(context, nextSegment)
            val nextUsed = prefs.getInt(nextUsageKey, 0)
            edit.putInt(nextUsageKey, nextUsed + weightedSec)
            if (DefaultLogDebugConfig.quotaCalculation) {
                Log.d("CreditManager", "borrowSecondsFromNext[GAP]: writing +${weightedSec}s penalty (weighted: cooling=${coolingOverlapSec}s×2 + dnd=${dndOverlapSec}s×1 + gap=${gapOverlapSec}s×0) to nextUsageKey=$nextUsageKey (was $nextUsed, now ${nextUsed + weightedSec})")
            }
            edit.apply()
            return
        }

        if (TimeRuleManager.isLastLogicalSegment(context, currentIndex)) return

        val currentSegment = segments[currentIndex]
        val nextIndex = (currentIndex + 1) % segments.size
        val nextSegment = segments[nextIndex]

        // 下一段偿还加权结果
        val nextUsageKey = getUsageKeyForSegment(context, nextSegment)
        val nextUsed = prefs.getInt(nextUsageKey, 0)
        edit.putInt(nextUsageKey, nextUsed + weightedSec)
        if (DefaultLogDebugConfig.quotaCalculation) {
            Log.d("CreditManager", "borrowSecondsFromNext: writing +${weightedSec}s (weighted: ${coolingOverlapSec}s×2 + ${dndOverlapSec}s×1 + ${gapOverlapSec}s×0) to nextUsageKey=$nextUsageKey (was $nextUsed, now ${nextUsed + weightedSec})")
        }

        // 当前段只记录借入本金；该记录用于累计上限与审计，绝不参与当前可用余额。
        val borrowKey = getBorrowKeyForSegment(context, currentSegment)
        val currentBorrow = prefs.getInt(borrowKey, 0)
        edit.putInt(borrowKey, currentBorrow + borrowSeconds)
        if (DefaultLogDebugConfig.quotaCalculation) {
            Log.d("CreditManager", "borrowSecondsFromNext: writing principal +${borrowSeconds}s to borrowKey=$borrowKey (was $currentBorrow, now ${currentBorrow + borrowSeconds})")
        }

        edit.apply()

        val nextLimitSec = nextSegment.limitMins * 60
        val fromBalanceAfterSec = kotlin.math.max(0L, (nextLimitSec.toLong() - (nextUsed.toLong() + weightedSec)))
        quotaConfigEventBridge.recordBorrow(
            transactionId = nextBorrowTxId(),
            kind = "DURATION",
            fromSegment = nextSegment.name,
            toSegment = currentSegment.name,
            received = "${borrowSeconds / 60}min",
            multiplier = "${kotlin.math.round(weightedSec.toDouble() / borrowSeconds.toDouble() * 10.0) / 10.0}",
            charged = "${weightedSec / 60}min",
            fromBalanceBefore = "${nextSegment.limitMins}min",
            fromBalanceAfter = "${fromBalanceAfterSec / 60}min",
            reason = "TEMP_ACCESS",
            result = "APPLIED"
        )
    }

    fun getBorrowedMinutesFromNext(context: Context): Double {
        val currentSegment = TimeRuleManager.getCurrentSegment(context)
        if (currentSegment == null) {
            // 🚀 [Fix] Gap period: use previous segment's borrowKey
            // The previous segment is the one that borrowed from the upcoming next segment
            val prevSegment = TimeRuleManager.getPreviousSegment(context)
            if (prevSegment == null) {
                Log.d("CreditManager", "getBorrowedMinutesFromNext[DIAG] gap & no previous → return 0.0")
                return 0.0
            }
            val borrowed = getBorrowedSecondsForSegment(context, prevSegment) / 60.0
            Log.d("CreditManager", "getBorrowedMinutesFromNext[DIAG] gap period, prevSegment=${prevSegment.name} key=${getBorrowKeyForSegment(context, prevSegment)} borrowed=${borrowed}min")
            return borrowed
        }
        val borrowed = getBorrowedSecondsForSegment(context, currentSegment) / 60.0
        Log.d("CreditManager", "getBorrowedMinutesFromNext[DIAG] segment=${currentSegment.name} key=${getBorrowKeyForSegment(context, currentSegment)} borrowed=${borrowed}min")
        return borrowed
    }

    private fun getBorrowedSecondsForSegment(context: Context, segment: TimeSegment): Int {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getInt(getBorrowKeyForSegment(context, segment), 0)
    }

    fun getMaxBorrowableMinutes(context: Context): Double {
        val segments = TimeRuleManager.getTimeSegments(context)
        val currentIndex = TimeRuleManager.getCurrentSegmentIndex(context)
        val borrowPercent = TimeRuleManager.getBorrowPercentage(context)

        if (currentIndex == -1) {
            // 🚀 [Gap Borrow] 空档期：返回还能申请的原始分钟数，由剩余偿还额度反推。
            val nextIdx = TimeRuleManager.getNextSegmentIndex(context)
            if (nextIdx == -1) return 0.0
            val nextSegment = segments[nextIdx]
            return calculateMaxRawBorrowableMinutes(
                nextLimitMinutes = nextSegment.limitMins,
                borrowPercent = borrowPercent,
                alreadyBorrowedWeightedSec = kotlin.math.round(getBorrowedMinutesFromNext(context) * 120).toInt()
            ).coerceAtLeast(0.0)
        }

        if (TimeRuleManager.isLastLogicalSegment(context, currentIndex)) return 0.0

        val nextIndex = (currentIndex + 1) % segments.size
        val nextSegment = segments[nextIndex]
        return calculateMaxRawBorrowableMinutes(
            nextLimitMinutes = nextSegment.limitMins,
            borrowPercent = borrowPercent,
            alreadyBorrowedWeightedSec = kotlin.math.round(getBorrowedMinutesFromNext(context) * 120).toInt()
        ).coerceAtLeast(0.0)
    }

    /**
     * 获取物理日期字符串（用于特权每日限次判断）
     * 不使用业务日期归属逻辑，直接返回物理日历日期
     */
    fun getPhysicalDateString(): String {
        return SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(Date())
    }

    fun getPhysicalDateStringAt(timestamp: Long): String {
        return SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(Date(timestamp))
    }

    /**
     * 🟢 [Round B - Bug 3] DND 自然日重置 seam。
     *
     * 维护独立的物理日 SharedPreferences key `dnd_natural_day_str`,与业务日 / 段起始业务日
     * 严格解耦。若当前物理自然日与上次观察不同,则刷新旧自然日的 DND 日状态(隔离 R2 未启用段结余、
     * R3 段内未结算状态的可携性)并返回 true;同一自然日重复观察返回 false(幂等)。
     *
     * 不改写:
     *   - 额度业务日 / 段起始业务日 / record_date_str
     *   - usage_* / borrow_time_* / count_* 账本
     *   - 特权账本 / pending 配置
     */
    private val dndNaturalDayJustRefreshed = AtomicReference<String?>(null)

    fun observeDndNaturalDay(context: Context, nowMillis: Long): Boolean {
        val currentNaturalDay = getPhysicalDateStringAt(nowMillis)
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val lastNaturalDay = prefs.getString(KEY_DND_NATURAL_DAY, null)
        if (lastNaturalDay != null && lastNaturalDay != currentNaturalDay) {
            // 跨过自然日 00:00 边界:刷新旧日 DND 日状态 + 更新 prefs + 设置本次进程内 just-refreshed 标记
            prefs.edit().putString(KEY_DND_NATURAL_DAY, currentNaturalDay).apply()
            dndNaturalDayJustRefreshed.set(currentNaturalDay)
            return true
        }
        if (lastNaturalDay == null) {
            // 首次观察(冷启动 / 新装):写入基线,不视为刷新事件
            prefs.edit().putString(KEY_DND_NATURAL_DAY, currentNaturalDay).apply()
        }
        return false
    }

    /**
     * 本次进程内"刚发生过自然日刷新"的一次性信号。供关键读取路径隔离旧自然日 DND 日状态
     * 与结余可见性(R2/R3)。每次新的跨日刷新事件覆盖;不跨进程持久化。
     */
    fun isDndNaturalDayJustRefreshed(context: Context, nowMillis: Long): Boolean {
        val signal = dndNaturalDayJustRefreshed.get() ?: return false
        return signal == getPhysicalDateStringAt(nowMillis)
    }

    fun getBusinessDateString(context: Context): String {
        return getBusinessDateStringAt(context, System.currentTimeMillis())
    }

    fun getBusinessDateStringAt(context: Context, timestamp: Long): String {
        val cal = Calendar.getInstance()
        cal.timeInMillis = timestamp

        // Stats/quota day = first DND segment start today -> first DND segment start tomorrow.
        // This keeps late-night cross-midnight segments attached to the day they started.
        val currentMins = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        val businessDayStartMins = getBusinessDayStartMinutes(context, cal)
        if (currentMins < businessDayStartMins) {
            cal.add(Calendar.DAY_OF_YEAR, -1)
        }
        return SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(cal.time)
    }

    private fun getBusinessDateStringForDay(context: Context, dayCal: Calendar): String {
        val startMins = getBusinessDayStartMinutes(context, dayCal)
        val startCal = dayCal.clone() as Calendar
        startCal.set(Calendar.HOUR_OF_DAY, startMins / 60)
        startCal.set(Calendar.MINUTE, startMins % 60)
        startCal.set(Calendar.SECOND, 0)
        startCal.set(Calendar.MILLISECOND, 0)
        startCal.add(Calendar.MINUTE, 1)
        return getBusinessDateStringAt(context, startCal.timeInMillis)
    }

    private fun getBusinessDayStartMinutes(context: Context, dayCal: Calendar): Int {
        // [WP-02-T02 / DA-B] v3 反转优先级:偏好设置 refresh time 优先于首启用段
        //   用户偏好是业务日锚点的 source of truth;enabledSegments 仅在 refresh 缺失/无效时 fallback
        val refreshTime = TimeRuleManager.getRefreshTime(context)
        val parts = refreshTime.split(":")
        val refreshH = parts.getOrNull(0)?.toIntOrNull()
        val refreshM = parts.getOrNull(1)?.toIntOrNull()
        if (refreshH != null && refreshM != null && refreshH in 0..23 && refreshM in 0..59) {
            return refreshH * 60 + refreshM
        }

        val dayOfWeek = dayCal.get(Calendar.DAY_OF_WEEK)
        val enabledSegments = TimeRuleManager.getTimeSegments(context)
            .filter { it.isActiveToday(dayOfWeek) }
        if (enabledSegments.isNotEmpty()) {
            return enabledSegments.minOf { it.startHour * 60 + it.startMinute }
        }

        // Final safe fallback: refresh-time default 08:00 (per GlobalParamsActivity:455)
        return 8 * 60
    }

    fun getDebtMinutesToPrevious(context: Context): Int {
        val segments = TimeRuleManager.getTimeSegments(context)
        val currentIndex = TimeRuleManager.getCurrentSegmentIndex(context)
        if (currentIndex <= 0) return 0
        val prevSeg = segments[currentIndex - 1]
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getInt(getBorrowKeyForSegment(context, prevSeg), 0) * 2
    }

    fun getTotalLimitMinutes(context: Context): Int {
        val currentSegment = TimeRuleManager.getCurrentSegment(context)
        return currentSegment?.limitMins ?: 0
    }

    fun addUsedMinutes(context: Context, minutes: Int) {
        val matchingSegment = TimeRuleManager.getCurrentSegment(context) ?: return
        val segmentKey = generateSegmentKey(matchingSegment)
        val currentUsed = getUsedSecondsForSegment(context, matchingSegment)
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putInt(getUsageKeyForSegment(context, matchingSegment), currentUsed + (minutes * 60)).apply()
    }

    private fun checkDayChange(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val lastDate = prefs.getString("record_date_str", "")
        val todayDate = getBusinessDateString(context)

        if (lastDate == todayDate) return  // 业务日未变化，直接返回

        // 业务日真正变化 — 记录详细诊断信息
        val now = System.currentTimeMillis()
        val cal = Calendar.getInstance()
        val boundary = getBusinessDayStartMinutes(context, cal)
        val boundaryStr = "${boundary / 60}:${"%02d".format(boundary % 60)}"
        val timeStr = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(now))
        val calDateStr = SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(cal.time)

        LogExporter.i("CreditManager", "BUSINESS_DAY_CHANGED | from=$lastDate | to=$todayDate | boundary=$boundaryStr | calendarDate=$calDateStr | time=$timeStr")

        // 先更新日期标记，打断 getRemainingMinutes → checkDayChange 的递归回路
        prefs.edit().putString("record_date_str", todayDate).apply()

        // 跨天前记录旧日配额状态
        val oldRemaining = getRemainingMinutes(context)
        val oldCount = getRemainingUsableCountHalfUnits(context)
        LogExporter.i("CreditManager", "BUSINESS_DAY_SETTLEMENT | before=${formatMinutesForLog(oldRemaining)}min/${formatUsableCountHalfUnits(oldCount)}次")

        // 播报即将生效的延迟变更
        logPendingChangesSummary(context)

        TimeRuleManager.applyPendingToActive(context)
        applyPendingAppsToActive(context)

        // 跨天后记录新日配额状态
        val newRemaining = getRemainingMinutes(context)
        val newCount = getRemainingUsableCountHalfUnits(context)
        LogExporter.i("CreditManager", "BUSINESS_DAY_SETTLEMENT | after=${formatMinutesForLog(newRemaining)}min/${formatUsableCountHalfUnits(newCount)}次")
    }

    private fun getUsedSecondsToday(context: Context, segmentKey: String): Int {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getInt(getTodayKey(context, segmentKey), 0)
    }

    // 🚀 按段获取已用秒数，跨天段使用段起始日而非当前业务日
    private fun getUsedSecondsForSegment(context: Context, segment: TimeSegment): Int {
        val key = getUsageKeyForSegment(context, segment)
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getInt(key, 0)
    }

    // 🟢 [Fix] 使用 getBusinessDateString 确保业务日一致性
    // 修复：借用分钟后剩余时间不更新的问题（借用和读取使用不同的日期键）
    private fun getTodayKey(context: Context, segmentKey: String): String {
        val dateStr = getBusinessDateString(context)
        return "usage_${dateStr}_${segmentKey}"
    }

    // 🚀 生成段感知的 usage key：跨天段始终使用段起始日，避免业务日变更导致数据错位
    private fun getUsageKeyForSegment(context: Context, segment: TimeSegment): String {
        val dateStr = getSegmentBusinessDateString(context, segment)
        return "usage_${dateStr}_${generateSegmentKey(segment)}"
    }

    // 🆕 次数 usage key：独立于时间 usage key，避免互相污染
    private fun getCountUsageKeyForSegment(context: Context, segment: TimeSegment): String? {
        val dateStr = getSegmentBusinessDateString(context, segment)
        val suffix = "${dateStr}_${generateSegmentKey(segment)}"
        return migrateCountLedgerKey(context, "count_usage_$suffix", "count_usage_half_units_v2_$suffix")
    }

    // 🚀 跨天感知的业务日期：跨天段在午夜后仍使用段起始日，非跨天段沿用现有逻辑
    private fun getSegmentBusinessDateString(context: Context, segment: TimeSegment): String {
        val cal = Calendar.getInstance()
        val currentMins = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        val startMins = segment.startHour * 60 + segment.startMinute
        val endMins = segment.endHour * 60 + segment.endMinute
        val isCrossDay = endMins <= startMins

        // 🔴 [BugFix] 使用 endMins 而非 startMins 判断是否在午夜后：
        //   currentMins < startMins 在两种情况都成立：(1) 午夜后 01:00 < 22:30 → 需要回退
        //   (2) 段开始前 21:00 < 22:30 → 不应回退（当天日期才是正确的）
        //   currentMins < endMins 则只有(1)成立，因为 21:00(1260) < 08:00(480) 为 false。
        if (isCrossDay && currentMins < endMins) {
            // 跨天段的午夜后部分：当前在段结束前（午夜 00:00~08:00），回退1天到段起始日
            cal.add(Calendar.DAY_OF_YEAR, -1)
        } else if (!isCrossDay) {
            // 普通段：沿用现有业务日逻辑
            val businessDayStartMins = getBusinessDayStartMinutes(context, cal)
            if (currentMins < businessDayStartMins) {
                cal.add(Calendar.DAY_OF_YEAR, -1)
            }
        }
        // 跨天段且当前在段起始时间之后（如23:00）：使用当天日期
        return SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(cal.time)
    }

    private fun getUsageKeyByTimestamp(context: Context, segmentKey: String, timestamp: Long): String {
        val dateStr = getBusinessDateStringAt(context, timestamp)
        return "usage_${dateStr}_${segmentKey}"
    }

    // 🚀 跨天感知的 borrow key
    private fun getBorrowKeyForSegment(context: Context, segment: TimeSegment): String {
        val dateStr = getSegmentBusinessDateString(context, segment)
        return "borrow_time_${dateStr}_${generateSegmentKey(segment)}"
    }

    // 🆕 次数 borrow key
    private fun getCountBorrowKeyForSegment(context: Context, segment: TimeSegment): String? {
        val dateStr = getSegmentBusinessDateString(context, segment)
        val suffix = "${dateStr}_${generateSegmentKey(segment)}"
        return migrateCountLedgerKey(context, "count_borrow_$suffix", "count_borrow_half_units_v2_$suffix")
    }

    private fun migrateCountLedgerKey(context: Context, legacyKey: String, newKey: String): String? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.contains(newKey)) return newKey
        if (prefs.contains(legacyKey)) {
            try {
                val migrated = UsableCountLedgerMigration.migratedHalfUnits(null, prefs.getInt(legacyKey, 0)) ?: return null
                val committed = prefs.edit().putInt(newKey, migrated).commit()
                if (!committed) {
                    LogExporter.w("CreditManager", "[COUNT_MIGRATION] commit failed; legacy retained: $legacyKey")
                    return null
                }
            } catch (error: Exception) {
                LogExporter.w("CreditManager", "[COUNT_MIGRATION] failed; legacy retained: $legacyKey (${error.message})")
                return null
            }
        }
        return newKey
    }

    // [Privilege Stats] Separate key prefix to avoid contaminating normal quota usage_* keys
    private fun getPrivilegeUsageKeyByTimestamp(context: Context, segmentKey: String, timestamp: Long): String {
        val dateStr = getBusinessDateStringAt(context, timestamp)
        return "privilege_usage_${dateStr}_${segmentKey}"
    }

    private fun getTodayPrivilegeUsageKey(context: Context, segmentKey: String): String {
        val dateStr = getBusinessDateString(context)
        return "privilege_usage_${dateStr}_${segmentKey}"
    }

    // ==========================================
    // 🆕 解锁次数追踪（镜像分钟系统）
    // ==========================================

    // 🆕 获取当前段已用解锁次数
    fun getUsedUsableCountHalfUnitsForCurrentSegment(context: Context): Int {
        val seg = TimeRuleManager.getCurrentSegment(context) ?: return 0
        val key = getCountUsageKeyForSegment(context, seg) ?: return 0
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getInt(key, 0)
    }

    // 🆕 获取当前段借用的解锁次数
    fun getBorrowedUsableCountHalfUnitsFromNext(context: Context): Int {
        val currentSegment = TimeRuleManager.getCurrentSegment(context)
        if (currentSegment == null) {
            // Gap period: check previous segment's borrow
            val prevSegment = TimeRuleManager.getPreviousSegment(context) ?: return 0
            val key = getCountBorrowKeyForSegment(context, prevSegment) ?: return 0
            return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getInt(key, 0)
        }
        val key = getCountBorrowKeyForSegment(context, currentSegment) ?: return 0
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getInt(key, 0)
    }

    // 🆕 获取所有前序段结余的解锁次数（镜像 getRemainingMinutes 的滚存逻辑）
    // 🆕 获取所有前序段结余的解锁次数（镜像 getRemainingMinutes 的滚存逻辑）
    fun getAllPreviousSegmentsRemainingUsableCountHalfUnits(context: Context): Int {
        // [TASK-04 / F-01] 关键读取路径前先调 seam
        observeDndNaturalDay(context, System.currentTimeMillis())
        val segments = TimeRuleManager.getTimeSegments(context)
        val currentIndex = TimeRuleManager.getCurrentSegmentIndex(context)
        val targetIndex = if (currentIndex == -1) {
            TimeRuleManager.getNextSegmentIndex(context)
        } else {
            currentIndex
        }
        val firstLogicalIndex = TimeRuleManager.getLogicalFirstSegmentIndex(context)
        if (targetIndex == -1 || targetIndex == firstLogicalIndex) return 0

        // 🔴 [BugFix] 跨天过滤：只累计与目标段同一业务天的前序段
        val targetBizDate = getSegmentBusinessDateString(context, segments[targetIndex])
        var totalRemaining = 0
        var i = firstLogicalIndex
        while (i != targetIndex) {
            val seg = segments[i]
            if (seg.isActiveToday()) {
                // 🔴 [BugFix] 跨天过滤：跳过不属于同一业务天的段
                if (getSegmentBusinessDateString(context, seg) == targetBizDate) {
                    val limit = seg.getEffectiveUsableCountLimitHalfUnits()
                    if (limit > 0) {
                        if (getCountUsageKeyForSegment(context, seg) == null) return 0
                        val used = getCurrentSegmentUsableCountUsedHalfUnits(context, seg)
                        // 不累加借用（借用随当前段消耗）
                        totalRemaining += (limit - used).coerceAtLeast(0)
                    }
                } else {
                    LogExporter.i("CreditManager", "getAllPreviousSegmentsRemainingUsableCountHalfUnits: skip seg=${seg.name} (bizDate=${getSegmentBusinessDateString(context, seg)} != targetBizDate=$targetBizDate)")
                }
            }
            i = (i + 1) % segments.size
            if (i == firstLogicalIndex) break
        }
        return totalRemaining
    }

    /** 检查上一段是否产生过时长结余（即使已被消耗完也返回 true） */
    fun didPreviousSegmentGenerateTimeSurplus(context: Context): Boolean {
        val prevSeg = TimeRuleManager.getPreviousSegment(context) ?: return false
        if (!prevSeg.isActiveToday()) return false
        // 🔴 [BugFix] 跨天段已结束后不算"上一段"（如 08:30 时，22:30-08:00 已结束）
        val prevStart = prevSeg.startHour * 60 + prevSeg.startMinute
        val prevEnd = prevSeg.endHour * 60 + prevSeg.endMinute
        val isCrossDay = prevEnd <= prevStart
        if (isCrossDay) {
            val cal = java.util.Calendar.getInstance()
            val currentMins = cal.get(java.util.Calendar.HOUR_OF_DAY) * 60 + cal.get(java.util.Calendar.MINUTE)
            if (currentMins >= prevEnd && currentMins < prevStart) return false // 跨天段已结束，属于上一个业务日
        }
        // 🔴 [BugFix] 上一段必须与当前段同属一个业务日，否则不算结余
        val currentSeg = TimeRuleManager.getCurrentSegment(context) ?: return false
        if (getSegmentBusinessDateString(context, prevSeg) != getSegmentBusinessDateString(context, currentSeg)) return false
        val usedSeconds = getUsedSecondsForSegment(context, prevSeg)
        return usedSeconds < prevSeg.limitMins * 60
    }

    /** 检查上一段是否产生过次数结余（即使已被消耗完也返回 true） */
    fun didPreviousSegmentGenerateCountSurplus(context: Context): Boolean {
        val prevSeg = TimeRuleManager.getPreviousSegment(context) ?: return false
        if (!prevSeg.isActiveToday()) return false
        // 🔴 [BugFix] 跨天段已结束后不算"上一段"
        val prevStart = prevSeg.startHour * 60 + prevSeg.startMinute
        val prevEnd = prevSeg.endHour * 60 + prevSeg.endMinute
        val isCrossDay = prevEnd <= prevStart
        if (isCrossDay) {
            val cal = java.util.Calendar.getInstance()
            val currentMins = cal.get(java.util.Calendar.HOUR_OF_DAY) * 60 + cal.get(java.util.Calendar.MINUTE)
            if (currentMins >= prevEnd && currentMins < prevStart) return false
        }
        // 🔴 [BugFix] 上一段必须与当前段同属一个业务日，否则不算结余
        val currentSeg = TimeRuleManager.getCurrentSegment(context) ?: return false
        if (getSegmentBusinessDateString(context, prevSeg) != getSegmentBusinessDateString(context, currentSeg)) return false
        val countKey = getCountUsageKeyForSegment(context, prevSeg) ?: return false
        val usedCount = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getInt(countKey, 0)
        return usedCount < prevSeg.getEffectiveUsableCountLimitHalfUnits()
    }

    /** 获取前序段结余总次数额度（不计消耗，仅限额总和） */
    fun getAllPreviousSegmentsTotalUsableCount(context: Context): Int {
        val segments = TimeRuleManager.getTimeSegments(context)
        val currentIndex = TimeRuleManager.getCurrentSegmentIndex(context)
        val targetIndex = if (currentIndex == -1) {
            TimeRuleManager.getNextSegmentIndex(context)
        } else {
            currentIndex
        }
        val firstLogicalIndex = TimeRuleManager.getLogicalFirstSegmentIndex(context)
        if (targetIndex == -1 || targetIndex == firstLogicalIndex) return 0
        val targetBizDate = getSegmentBusinessDateString(context, segments[targetIndex])
        var totalLimit = 0
        var i = firstLogicalIndex
        while (i != targetIndex) {
            val seg = segments[i]
            if (seg.isActiveToday()) {
                if (getSegmentBusinessDateString(context, seg) == targetBizDate) {
                    totalLimit += seg.getEffectiveUsableCountLimitHalfUnits()
                }
            }
            i = (i + 1) % segments.size
            if (i == firstLogicalIndex) break
        }
        return totalLimit
    }

    // 🆕 获取当前段剩余解锁次数（含前序结余 + 借用；空档期返回前序结余）
    fun getRemainingUsableCountHalfUnits(context: Context): Int {
        val seg = TimeRuleManager.getCurrentSegment(context)
        if (seg == null) {
            return getAllPreviousSegmentsRemainingUsableCountHalfUnits(context)
        }
        if (getCountUsageKeyForSegment(context, seg) == null) return 0
        val limit = seg.getEffectiveUsableCountLimitHalfUnits()
        if (limit == 0) return 0 // 禁止临时解锁
        val used = getUsedUsableCountHalfUnitsForCurrentSegment(context)
        val prevRemaining = getAllPreviousSegmentsRemainingUsableCountHalfUnits(context)
        return (limit - used + prevRemaining).coerceAtLeast(0)
    }

    // 🆕 获取指定段已用次数（按段感知 key 读取）
    fun getCurrentSegmentUsableCountUsedHalfUnits(context: Context, segment: TimeSegment): Int {
        val key = getCountUsageKeyForSegment(context, segment) ?: return 0
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getInt(key, 0)
    }

    // 🆕 检查是否可以从下一段借用解锁次数
    fun canBorrowCountHalfUnitsFromNext(context: Context, requestedCount: Int): Boolean {
        val segments = TimeRuleManager.getTimeSegments(context)
        val currentIndex = TimeRuleManager.getCurrentSegmentIndex(context)
        val borrowPercent = TimeRuleManager.getBorrowPercentage(context)

        if (currentIndex == -1) {
            // 🚀 [Gap Borrow] 空档期：无 deficit，直接检查请求量是否在可借上限内
            val nextIdx = TimeRuleManager.getNextSegmentIndex(context)
            if (nextIdx == -1) return false
            val nextSegment = segments[nextIdx]
            val nextLimit = nextSegment.getEffectiveUsableCountLimitHalfUnits()
            if (nextLimit == 0) return false
            val maxBorrowable = calculateMaxRawBorrowableCount(
                nextCountLimit = nextLimit,
                borrowPercent = borrowPercent,
                alreadyBorrowedWeightedCount = getBorrowedUsableCountHalfUnitsFromNext(context) * 2
            )
            return requestedCount <= maxBorrowable
        }

        if (TimeRuleManager.isLastLogicalSegment(context, currentIndex)) return false

        val remaining = getRemainingUsableCountHalfUnits(context)
        if (remaining >= requestedCount) return false // 不需要借

        val deficit = requestedCount - remaining
        val nextIndex = (currentIndex + 1) % segments.size
        val nextSegment = segments[nextIndex]
        val nextLimit = nextSegment.getEffectiveUsableCountLimitHalfUnits()
        if (nextLimit == 0) return false // 下一段禁止临时解锁

        val maxBorrowable = calculateBorrowRepaymentLimitCount(nextLimit, borrowPercent)
        val alreadyBorrowed = getBorrowedUsableCountHalfUnitsFromNext(context) * 2

        return (deficit * 2 + alreadyBorrowed) <= maxBorrowable
    }

    // 🆕 获取最大可借用解锁次数
    fun getMaxBorrowableUsableCountHalfUnits(context: Context): Int {
        val segments = TimeRuleManager.getTimeSegments(context)
        val currentIndex = TimeRuleManager.getCurrentSegmentIndex(context)
        val borrowPercent = TimeRuleManager.getBorrowPercentage(context)

        if (currentIndex == -1) {
            // 🚀 [Gap Borrow] 空档期：返回还能申请的原始次数。
            val nextIdx = TimeRuleManager.getNextSegmentIndex(context)
            if (nextIdx == -1) return 0
            val nextSegment = segments[nextIdx]
            val nextLimit = nextSegment.getEffectiveUsableCountLimitHalfUnits()
            if (nextLimit == 0) return 0
            return calculateMaxRawBorrowableCount(
                nextCountLimit = nextLimit,
                borrowPercent = borrowPercent,
                alreadyBorrowedWeightedCount = getBorrowedUsableCountHalfUnitsFromNext(context) * 2
            ).coerceAtLeast(0)
        }

        if (TimeRuleManager.isLastLogicalSegment(context, currentIndex)) return 0

        val nextIndex = (currentIndex + 1) % segments.size
        val nextSegment = segments[nextIndex]
        val nextLimit = nextSegment.getEffectiveUsableCountLimitHalfUnits()
        if (nextLimit == 0) return 0

        return calculateMaxRawBorrowableCount(
            nextCountLimit = nextLimit,
            borrowPercent = borrowPercent,
            alreadyBorrowedWeightedCount = getBorrowedUsableCountHalfUnitsFromNext(context) * 2
        ).coerceAtLeast(0)
    }

    // === Legacy non-HalfUnits API aliases (for HomeFragment / LockWindowManager) ===
    fun getRemainingUsableCount(context: Context): Int = getRemainingUsableCountHalfUnits(context)

    fun getMaxBorrowableUsableCount(context: Context): Int = getMaxBorrowableUsableCountHalfUnits(context)

    fun getUsedUsableCountForCurrentSegment(context: Context): Int = getUsedUsableCountHalfUnitsForCurrentSegment(context)

    fun getBorrowedUsableCountFromNext(context: Context): Int = getBorrowedUsableCountHalfUnitsFromNext(context)

    fun getAllPreviousSegmentsRemainingUsableCount(context: Context): Int = getAllPreviousSegmentsRemainingUsableCountHalfUnits(context)

    fun getCurrentSegmentUsableCountUsed(context: Context, segment: TimeSegment): Int = getCurrentSegmentUsableCountUsedHalfUnits(context, segment)

    fun canBorrowCountFromNext(context: Context, requestedCount: Int): Boolean = canBorrowCountHalfUnitsFromNext(context, requestedCount)

    fun borrowCountFromNext(context: Context, count: Int) {
        if (count <= 0) return
        val segments = TimeRuleManager.getTimeSegments(context)
        if (segments.isEmpty()) return
        val currentIndex = TimeRuleManager.getCurrentSegmentIndex(context).coerceAtLeast(0)
        val nextIndex = (currentIndex + 1) % segments.size
        val nextSeg = segments[nextIndex]
        val key = getCountBorrowKeyForSegment(context, nextSeg) ?: return
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val current = prefs.getInt(key, 0)
        prefs.edit().putInt(key, current + count).apply()
    }

    fun consumeUsableCount(context: Context) {
        consumeUsableCountWeighted(context, 1)
    }

    fun consumeUsableCountWeighted(context: Context, halfUnits: Int) {
        if (halfUnits <= 0) return
        val segments = TimeRuleManager.getTimeSegments(context)
        if (segments.isEmpty()) return
        val currentSeg = TimeRuleManager.getCurrentSegment(context) ?: return
        val key = getCountUsageKeyForSegment(context, currentSeg) ?: return
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val current = prefs.getInt(key, 0)
        prefs.edit().putInt(key, current + halfUnits).apply()
    }

    internal fun applyTemporaryAccessQuotaPlan(context: Context, plan: TemporaryAccessQuotaPlan): Boolean {
        val segments = TimeRuleManager.getTimeSegments(context)
        val currentIndex = TimeRuleManager.getCurrentSegmentIndex(context)
        if (currentIndex == -1) return false
        if (!plan.isNextStageAffordable) {
            LogExporter.w("CreditManager", "[QUOTA_TRANSACTION_REJECT] next debt exceeds capacity: time=${plan.nextTimeCostSec}/${plan.nextTimeCapacitySec}s count=${formatUsableCountHalfUnits(plan.nextCountCostHalfUnits)}/${formatUsableCountHalfUnits(plan.nextCountCapacityHalfUnits)}")
            return false
        }
        if (TimeRuleManager.isLastLogicalSegment(context, currentIndex) && plan.needsNextStage) return false

        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val editor = prefs.edit()
        val currentSegment = segments[currentIndex]
        val nextSegment = segments[(currentIndex + 1) % segments.size]

        if (plan.currentTimeCostSec > 0) {
            var remainingCurrentCost = plan.currentTimeCostSec
            val currentUsageKey = getUsageKeyForSegment(context, currentSegment)
            val currentUsed = prefs.getInt(currentUsageKey, 0)
            val currentOwnAvailable = (currentSegment.limitMins * 60 - currentUsed).coerceAtLeast(0)
            val currentTake = minOf(remainingCurrentCost, currentOwnAvailable)
            if (currentTake > 0) {
                editor.putInt(currentUsageKey, currentUsed + currentTake)
                remainingCurrentCost -= currentTake
            }
            val firstLogicalIndex = TimeRuleManager.getLogicalFirstSegmentIndex(context)
            val currentBizDate = getSegmentBusinessDateString(context, currentSegment)
            var index = firstLogicalIndex
            while (index != currentIndex && remainingCurrentCost > 0) {
                val segment = segments[index]
                if (segment.isActiveToday() && getSegmentBusinessDateString(context, segment) == currentBizDate) {
                    val usageKey = getUsageKeyForSegment(context, segment)
                    val used = prefs.getInt(usageKey, 0)
                    val available = (segment.limitMins * 60 - used).coerceAtLeast(0)
                    val take = minOf(remainingCurrentCost, available)
                    if (take > 0) {
                        editor.putInt(usageKey, used + take)
                        remainingCurrentCost -= take
                    }
                }
                index = (index + 1) % segments.size
                if (index == firstLogicalIndex) break
            }
            if (remainingCurrentCost > 0) return false
        }
        if (plan.nextTimeCostSec > 0) {
            val nextUsageKey = getUsageKeyForSegment(context, nextSegment)
            val nextUsed = prefs.getInt(nextUsageKey, 0)
            editor.putInt(nextUsageKey, nextUsed + plan.nextTimeCostSec)
            val borrowKey = getBorrowKeyForSegment(context, currentSegment)
            editor.putInt(borrowKey, prefs.getInt(borrowKey, 0) + plan.borrowedTimePrincipalSec)
        }
        if (plan.currentCountCostHalfUnits > 0) {
            var remainingCurrentCount = plan.currentCountCostHalfUnits
            val currentCountKey = getCountUsageKeyForSegment(context, currentSegment) ?: return false
            val currentCountUsed = prefs.getInt(currentCountKey, 0)
            val currentOwnAvailable = (currentSegment.getEffectiveUsableCountLimitHalfUnits() - currentCountUsed).coerceAtLeast(0)
            val currentTake = minOf(remainingCurrentCount, currentOwnAvailable)
            if (currentTake > 0) {
                editor.putInt(currentCountKey, currentCountUsed + currentTake)
                remainingCurrentCount -= currentTake
            }
            val firstLogicalIndex = TimeRuleManager.getLogicalFirstSegmentIndex(context)
            val currentBizDate = getSegmentBusinessDateString(context, currentSegment)
            var index = firstLogicalIndex
            while (index != currentIndex && remainingCurrentCount > 0) {
                val segment = segments[index]
                if (segment.isActiveToday() && getSegmentBusinessDateString(context, segment) == currentBizDate) {
                    val countKey = getCountUsageKeyForSegment(context, segment) ?: return false
                    val used = prefs.getInt(countKey, 0)
                    val available = (segment.getEffectiveUsableCountLimitHalfUnits() - used).coerceAtLeast(0)
                    val take = minOf(remainingCurrentCount, available)
                    if (take > 0) {
                        editor.putInt(countKey, used + take)
                        remainingCurrentCount -= take
                    }
                }
                index = (index + 1) % segments.size
                if (index == firstLogicalIndex) break
            }
            if (remainingCurrentCount > 0) return false
        }
        if (plan.nextCountCostHalfUnits > 0) {
            val nextCountKey = getCountUsageKeyForSegment(context, nextSegment) ?: return false
            val nextCountUsed = prefs.getInt(nextCountKey, 0)
            editor.putInt(nextCountKey, nextCountUsed + plan.nextCountCostHalfUnits)
            val countBorrowKey = getCountBorrowKeyForSegment(context, currentSegment) ?: return false
            editor.putInt(countBorrowKey, prefs.getInt(countBorrowKey, 0) + plan.borrowedCountPrincipalHalfUnits)
        }

        editor.apply()
        AutoBackupManager.triggerDebouncedBackup(context)
        LogExporter.i(
            "CreditManager",
            "[QUOTA_TRANSACTION] required=${plan.totalTimeCostSec}s/${formatUsableCountHalfUnits(plan.totalCountCostHalfUnits)} current=${plan.currentTimeCostSec}s/${formatUsableCountHalfUnits(plan.currentCountCostHalfUnits)} principal=${plan.borrowedTimePrincipalSec}s/${formatUsableCountHalfUnits(plan.borrowedCountPrincipalHalfUnits)} nextDebt=${plan.nextTimeCostSec}s/${formatUsableCountHalfUnits(plan.nextCountCostHalfUnits)} capacity=${plan.nextTimeCapacitySec}s/${formatUsableCountHalfUnits(plan.nextCountCapacityHalfUnits)}"
        )
        return true
    }

    internal data class TemporaryAccessTimeChargeReceipt(
        val currentUsageKey: String?,
        val nextUsageKey: String?,
        val borrowKey: String?,
        val currentChargeSeconds: Int,
        val nextChargeSeconds: Int,
        val borrowChargeSeconds: Int
    )

    internal fun buildTemporaryAccessTimeChargeReceipt(
        context: Context,
        currentChargeSeconds: Int,
        nextChargeSeconds: Int,
        borrowChargeSeconds: Int
    ): TemporaryAccessTimeChargeReceipt {
        val segments = TimeRuleManager.getTimeSegments(context)
        val currentIndex = TimeRuleManager.getCurrentSegmentIndex(context)
        if (currentIndex == -1) {
            return TemporaryAccessTimeChargeReceipt(null, null, null, 0, 0, 0)
        }
        val currentSegment = segments[currentIndex]
        val nextSegment = if (nextChargeSeconds > 0 && segments.isNotEmpty()) {
            segments[(currentIndex + 1) % segments.size]
        } else {
            null
        }
        return TemporaryAccessTimeChargeReceipt(
            currentUsageKey = getUsageKeyForSegment(context, currentSegment),
            nextUsageKey = nextSegment?.let { getUsageKeyForSegment(context, it) },
            borrowKey = if (borrowChargeSeconds > 0) getBorrowKeyForSegment(context, currentSegment) else null,
            currentChargeSeconds = currentChargeSeconds.coerceAtLeast(0),
            nextChargeSeconds = nextChargeSeconds.coerceAtLeast(0),
            borrowChargeSeconds = borrowChargeSeconds.coerceAtLeast(0)
        )
    }

    internal fun refundTemporaryAccessTimeCharge(
        context: Context,
        receipt: TemporaryAccessTimeChargeReceipt,
        refund: TemporaryAccessRefundPlan
    ) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val editor = prefs.edit()
        fun subtract(key: String?, amount: Int) {
            if (key.isNullOrBlank() || amount <= 0) return
            editor.putInt(key, (prefs.getInt(key, 0) - amount).coerceAtLeast(0))
        }
        subtract(receipt.currentUsageKey, refund.currentRefundSeconds)
        subtract(receipt.nextUsageKey, refund.nextRefundSeconds)
        subtract(receipt.borrowKey, refund.borrowRefundSeconds)
        editor.apply()
    }

    private fun migrateFutureCrossDayUsageBackToPreviousBusinessDay(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val nowCal = Calendar.getInstance()
        val currentMins = nowCal.get(Calendar.HOUR_OF_DAY) * 60 + nowCal.get(Calendar.MINUTE)
        val currentDateStr = getBusinessDateString(context)
        val previousDateStr = (nowCal.clone() as Calendar).run {
            add(Calendar.DAY_OF_YEAR, -1)
            SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(time)
        }

        val editor = prefs.edit()
        var changed = false
        TimeRuleManager.getTimeSegments(context).forEach { seg ->
            val startMins = seg.startHour * 60 + seg.startMinute
            val endMins = seg.endHour * 60 + seg.endMinute
            // 🔴 [BugFix] 同步使用 endMins：跨天段午夜后(00:00~endMins)才算"上一业务日"
            val isFutureCrossDaySegment = endMins <= startMins && currentMins < endMins
            if (!isFutureCrossDaySegment) return@forEach

            // 🚀 [Fix] Only migrate if business date and segment date differ.
            // Before business day flip (e.g. 07:41), getBusinessDateString() already
            // returns yesterday, so usage is in the correct place → skip.
            val correctDateStr = getSegmentBusinessDateString(context, seg)
            if (currentDateStr == correctDateStr) return@forEach

            val segKey = generateSegmentKey(seg)
            val currentUsageKey = "usage_${currentDateStr}_${segKey}"
            val previousUsageKey = "usage_${previousDateStr}_${segKey}"
            val currentUsage = prefs.getInt(currentUsageKey, 0)
            if (currentUsage > 0) {
                editor.putInt(previousUsageKey, prefs.getInt(previousUsageKey, 0) + currentUsage)
                editor.remove(currentUsageKey)
                changed = true
            }

            val currentBorrowKey = "borrow_time_${currentDateStr}_${segKey}"
            val previousBorrowKey = "borrow_time_${previousDateStr}_${segKey}"
            val currentBorrow = prefs.getInt(currentBorrowKey, 0)
            if (currentBorrow > 0) {
                editor.putInt(previousBorrowKey, prefs.getInt(previousBorrowKey, 0) + currentBorrow)
                editor.remove(currentBorrowKey)
                changed = true
            }
        }

        if (changed) editor.apply()
    }

    fun getTodayTotalUsedMinutes(context: Context): Int {
        checkDayChange(context)
        migrateFutureCrossDayUsageBackToPreviousBusinessDay(context)
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val segments = TimeRuleManager.getTimeSegments(context)
        var totalUsed = 0
        segments.forEach { seg ->
            val usageKey = getUsageKeyForSegment(context, seg)
            val usedSeconds = prefs.getInt(usageKey, 0)
            totalUsed += (usedSeconds / 60)
            val borrowKey = getBorrowKeyForSegment(context, seg)
            val borrowSeconds = prefs.getInt(borrowKey, 0)
            totalUsed += (borrowSeconds / 60) * 2
        }
        return totalUsed
    }

    /**
     * 🚀 [New] Calculate Total Accumulated Minutes from previous segments
     */
    fun getTotalAccumulatedMinutes(context: Context): Int {
        checkDayChange(context)
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val segments = TimeRuleManager.getTimeSegments(context)
        
        var totalRemaining = 0
        
        val nextSeg = TimeRuleManager.getNextSegment(context)
        val firstLogicalIndex = TimeRuleManager.getLogicalFirstSegmentIndex(context) 
        
        var i = firstLogicalIndex
        var count = 0
        
        while (count < segments.size) {
            val seg = segments[i]
            
            // Should we include this segment?
            // If it is the Next Segment, STOP.
            if (nextSeg != null && seg.startHour == nextSeg.startHour && seg.startMinute == nextSeg.startMinute) {
                break
            }
            
            // 🚀 [Fix] Activation Check: Only count segments that are actually ACTIVE today.
        // Otherwise, inactive segments (e.g. Workday segments on Weekend) contribute their full limit as "unused surplus", which is wrong.
        if (!seg.isActiveToday()) {
            i = (i + 1) % segments.size
            if (i == firstLogicalIndex) break
            count++
            continue
        }
        
        val limit = TimeRuleManager.getMinutesLimitForSegment(context, seg)
            val key = generateSegmentKey(seg)
            val usedKey = getTodayKey(context, key)
            val usedSeconds = prefs.getInt(usedKey, 0)
            
            val remain = (limit - (usedSeconds / 60)).coerceAtLeast(0)
            totalRemaining += remain
            
            i = (i + 1) % segments.size
            if (i == firstLogicalIndex) break
            count++
        }
        
        return totalRemaining
    }

    fun getWeekStats(context: Context, startOfWeek: Calendar): List<DayStats> {
        migrateFutureCrossDayUsageBackToPreviousBusinessDay(context)
        val stats = mutableListOf<DayStats>()
        val calendar = startOfWeek.clone() as Calendar
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val segments = TimeRuleManager.getTimeSegments(context)
        for (i in 0..6) {
            val dateStr = getBusinessDateStringForDay(context, calendar)
            val segmentMap = mutableMapOf<String, Int>()
            segments.forEach { seg ->
                val key = generateSegmentKey(seg)
                val usedSeconds = prefs.getInt("usage_${dateStr}_${key}", 0)
                val borrowKey = "borrow_time_${dateStr}_${key}"
                val borrowSeconds = prefs.getInt(borrowKey, 0)
                val privilegeMinutes = prefs.getInt("privilege_usage_${dateStr}_${key}", 0)
                val totalWithBorrow = (usedSeconds / 60) + ((borrowSeconds / 60) * 2) + privilegeMinutes
                segmentMap[key] = totalWithBorrow
            }
            stats.add(DayStats(date = dateStr, segmentMinutes = segmentMap))
            calendar.add(Calendar.DAY_OF_MONTH, 1)
        }
        return stats
    }

    fun generateSegmentKey(segment: TimeSegment): String {
        val nameKey = segment.name.replace(" ", "").lowercase()
        return if (nameKey.isNotEmpty()) nameKey else "seg_${segment.startHour}_${segment.startMinute}_${segment.endHour}_${segment.endMinute}"
    }

    fun getMonthStats(context: Context, monthCalendar: Calendar): List<DayStats> {
        migrateFutureCrossDayUsageBackToPreviousBusinessDay(context)
        val stats = mutableListOf<DayStats>()
        val calendar = monthCalendar.clone() as Calendar
        calendar.set(Calendar.DAY_OF_MONTH, 1)
        val daysInMonth = calendar.getActualMaximum(Calendar.DAY_OF_MONTH)
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val segments = TimeRuleManager.getTimeSegments(context)
        
        for (day in 1..daysInMonth) {
            calendar.set(Calendar.DAY_OF_MONTH, day)
            val dateStr = getBusinessDateStringForDay(context, calendar)
            val segmentMap = mutableMapOf<String, Int>()
            var totalUsed = 0
            
            segments.forEach { seg ->
                val key = generateSegmentKey(seg)
                val usedSeconds = prefs.getInt("usage_${dateStr}_${key}", 0)
                totalUsed += (usedSeconds / 60)
                val borrowKey = "borrow_time_${dateStr}_${key}"
                val borrowSeconds = prefs.getInt(borrowKey, 0)
                totalUsed += (borrowSeconds / 60) * 2
                // 📊 [Stats] 加入特权休息时长
                totalUsed += prefs.getInt("privilege_usage_${dateStr}_${key}", 0)
            }
            segmentMap["total"] = totalUsed
            stats.add(DayStats(date = dateStr, segmentMinutes = segmentMap))
        }
        return stats
    }

    fun getYearStats(context: Context, yearCalendar: Calendar): List<MonthStats> {
        migrateFutureCrossDayUsageBackToPreviousBusinessDay(context)
        val stats = mutableListOf<MonthStats>()
        val calendar = yearCalendar.clone() as Calendar
        calendar.set(Calendar.MONTH, 0)
        calendar.set(Calendar.DAY_OF_MONTH, 1)
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val segments = TimeRuleManager.getTimeSegments(context)
        
        for (month in 0..11) {
            calendar.set(Calendar.MONTH, month)
            val daysInMonth = calendar.getActualMaximum(Calendar.DAY_OF_MONTH)
            var monthTotalUsage = 0
            var monthTotalCapacity = 0
            for (day in 1..daysInMonth) {
                calendar.set(Calendar.DAY_OF_MONTH, day)
                val dateStr = getBusinessDateStringForDay(context, calendar)
                
                // 🚀 [Capacity] Day-by-Day calculation to respect weekday/weekend rules
                val dailyCap = TimeRuleManager.getDailyCapacity(context, calendar, segments)
                monthTotalCapacity += dailyCap

                segments.forEach { seg ->
                    val key = generateSegmentKey(seg)
                    val usedSeconds = prefs.getInt("usage_${dateStr}_${key}", 0)
                    monthTotalUsage += (usedSeconds / 60)
                    val borrowKey = "borrow_time_${dateStr}_${key}"
                    val borrowSeconds = prefs.getInt(borrowKey, 0)
                    monthTotalUsage += (borrowSeconds / 60) * 2
                    // 📊 [Stats] 加入特权休息时长
                    monthTotalUsage += prefs.getInt("privilege_usage_${dateStr}_${key}", 0)
                }
            }
            stats.add(MonthStats(month = month + 1, totalMinutes = monthTotalUsage, capacityMinutes = monthTotalCapacity))
        }
        return stats
    }


    
    // Apply Unlimited Grant to a Segment
    fun useWeeklyPrivilege(context: Context, segment: TimeSegment): Boolean {
        if (!isWeeklyPrivilegeAvailable(context)) return false
        
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val dateStr = getBusinessDateString(context)
        
        // 🚀 [Find Segment Index] for isSegmentPrivileged compatibility
        val segments = TimeRuleManager.getTimeSegments(context)
        val segIndex = segments.indexOfFirst { it.name == segment.name && it.startHour == segment.startHour } // Robust matching
        if (segIndex == -1) return false // Segment not found
        
        // 1. Mark as Used (Week Level)
        // 2. Mark Segment as Privileged (Day Level, for zero-surplus rule)
        val privilegeKey = "privilege_${dateStr}_segment_$segIndex"
        prefs.edit()
            .putLong("weekly_privilege_last_used", System.currentTimeMillis())
            .putBoolean(privilegeKey, true)
            .apply()
        
        // 3. Grant Unlimited to Target Segment (Current Day)
        val segKey = generateSegmentKey(segment)
        val grantKey = "grant_time_${dateStr}_${segKey}"
        
        // Grant 10000 mins
        val currentGrant = prefs.getInt(grantKey, 0)
        
        prefs.edit()
            .putInt(grantKey, currentGrant + 10000) // +10000 mins
            .apply()

        // 🚀 [Stats] Record the "Bought Out" time as Rest Usage immediately
        // Logic: Rest Time = Time from Now (or Start) until End of Segment.
        // This ensures the charts reflect that the user "Resting" for the remainder.
        val cal = Calendar.getInstance()
        var currentMins = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        
        val startMins = segment.startHour * 60 + segment.startMinute
        var endMins = segment.endHour * 60 + segment.endMinute
        if (endMins <= startMins) endMins += 1440 // Handle cross-day
        
        // Adjust currentMins for cross-day logic if we are in the early morning of next day
        // If endMins > 1440 (ends next day) AND currentMins < startMins (is next day), add 1440
        if (endMins > 1440 && currentMins < segment.startHour * 60) {
            currentMins += 1440
        }

        // Duration to record = End - Max(Now, Start)
        val effectiveStart = kotlin.math.max(currentMins, startMins)
        val minutesToRecord = (endMins - effectiveStart).coerceAtLeast(0)
        
        if (minutesToRecord > 0) {
            val usageKey = getTodayPrivilegeUsageKey(context, segKey)
            val currentUsed = prefs.getInt(usageKey, 0)
            prefs.edit().putInt(usageKey, currentUsed + minutesToRecord).apply()
            LogExporter.i("CreditManager", "Privilege Used: Automatically recorded $minutesToRecord mins as Privilege Stats.")
        }
            
        return true
    }

    // 🚀 [Rule] Unlimited Threshold (User Defined: > 120 mins)


    /**
     * 🚀 [NEW] Flexible Scheduling: Adjust rest time
     * User Logic (Updated):
     * - Early Start (+mins): Reward. SUBTRACT from CURRENT segment's used time (adds to rollover/结余).
     * - Late Start (-mins): Penalty. ADD to NEXT segment's used time (shows as 15/30 remaining).
     */
    fun adjustScheduleRestTime(context: Context, minutes: Int) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val edit = prefs.edit()
        
        if (minutes > 0) {
            // 🎁 Reward (Early Start): Subtract from CURRENT segment's used time
            // This increases the current segment's remaining, contributing to rollover (结余)
            val currentSeg = TimeRuleManager.getCurrentSegment(context) ?: return
            val currentKey = generateSegmentKey(currentSeg)
            val usageKey = getTodayKey(context, currentKey)
            val currentUsed = prefs.getInt(usageKey, 0)
            
            // Subtract usage (can go negative conceptually, but we floor at 0)
            val newUsed = (currentUsed - minutes).coerceAtLeast(0)
            edit.putInt(usageKey, newUsed)
            
            LogExporter.i("CreditManager", "Flexible(Early): Subtracted $minutes from Current($currentKey) usage. OldUsed: $currentUsed, NewUsed: $newUsed")
        } else {
            // ⚠️ Penalty (Late Start): Add to NEXT segment's used time directly
            val nextSeg = TimeRuleManager.getNextSegment(context) ?: return
            val nextKey = generateSegmentKey(nextSeg)
            val usageKey = getTodayKey(context, nextKey)
            val currentUsed = prefs.getInt(usageKey, 0)
            
            val penalty = -minutes // abs value
            edit.putInt(usageKey, currentUsed + penalty)
            
            LogExporter.i("CreditManager", "Flexible(Late): Added $penalty to Next($nextKey) usage. OldUsed: $currentUsed, NewUsed: ${currentUsed + penalty}")
        }
        
        edit.apply()
    }

    fun getCurrentSegmentRemainingMinutes(context: Context): Double {
        checkDayChange(context)
        // [TASK-04 / F-01] 关键读取路径前先调 seam
        observeDndNaturalDay(context, System.currentTimeMillis())
        val currentSegment = TimeRuleManager.getCurrentSegment(context) ?: return 0.0

        // [WP-02-T01 / F-02 removed] v3 撤掉刚跨过自然日 00:00 时把旧日当前段"段内未结算"状态视为未结算的短路

        val usedSeconds = getUsedSecondsForSegment(context, currentSegment)
        val usageKey = getUsageKeyForSegment(context, currentSegment)

        // 💎 [Privilege Check]
        val isPrivileged = isCurrentSegmentPrivileged(context)
        val effectiveLimitSeconds = if (isPrivileged) 9999 * 60 else currentSegment.limitMins * 60

        val remaining = ((effectiveLimitSeconds - usedSeconds).coerceAtLeast(0)) / 60.0
        Log.d("CreditManager", "getCurrentSegmentRemainingMinutes: seg=${currentSegment.name} key=$usageKey limit=${effectiveLimitSeconds}s used=${usedSeconds}s remaining=${remaining}min")
        return remaining
    }

    /**
     * 🟢 [New] 梯次扣费逻辑
     * Priority: 
     * 1. Current Segment (Normal Usage - recorded in current segment stats)
     * 2. Historical Segments (Rollover - recorded in historical segment stats)
     */
    fun consumeMinutes(
        context: Context,
        minutesToConsume: Int,
        transactionId: String = nextQuotaConfigId("CONSUME")
    ) {
        consumeSeconds(context, minutesToConsume * 60, transactionId)
    }

    fun consumeMinutes(
        context: Context,
        minutesToConsume: Double,
        transactionId: String = nextQuotaConfigId("CONSUME")
    ) {
        // 🚀 [Fix] 使用 round 避免截断精度丢失（如 0.0333min*60=1.9998→toInt=1 丢1s）
        consumeSeconds(context, kotlin.math.round(minutesToConsume * 60).toInt(), transactionId)
    }

    private fun consumeSeconds(context: Context, secondsToConsume: Int, transactionId: String = nextQuotaConfigId("CONSUME")) {
        var remaining = secondsToConsume
        val segments = TimeRuleManager.getTimeSegments(context)
        val currentIndex = TimeRuleManager.getCurrentSegmentIndex(context)
        if (currentIndex == -1) return
        val firstLogicalIndex = TimeRuleManager.getLogicalFirstSegmentIndex(context)
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val edit = prefs.edit()

        val currentSeg = segments[currentIndex]
        val currentKey = generateSegmentKey(currentSeg)

        if (isCurrentSegmentPrivileged(context)) {
            Log.d("CreditManager", "consumeSeconds: privileged segment, skip deduction")
            return
        }

        // 🚀 使用 getUsageKeyForSegment 确保跨天段读写 key 一致
        val currentUsageKey = getUsageKeyForSegment(context, currentSeg)
        val currentUsed = prefs.getInt(currentUsageKey, 0)
        val currentLimitSeconds = currentSeg.limitMins * 60

        val currentBorrowed = getBorrowedSecondsForSegment(context, currentSeg)
        val currentAvail = (currentLimitSeconds - currentUsed).coerceAtLeast(0)

        // 🆕 记录消耗前的剩余时间用于变化播报
        val beforeRemainingMins = getRemainingMinutes(context)
        if (DefaultLogDebugConfig.quotaCalculation) {
            LogExporter.i("CreditManager", "[TIME_CONSUME] consumeSeconds: toConsume=${secondsToConsume}s seg=${currentSeg.name} limit=${currentLimitSeconds}s used=${currentUsed}s borrowed=${currentBorrowed}s avail=${currentAvail}s beforeRemaining=${formatMinutesForLog(beforeRemainingMins)}min")
        }

        if (currentAvail > 0) {
            val take = minOf(currentAvail, remaining)
            // 🚀 [Fix] 扣费只增加 used，不减少 borrowKey
            // borrowKey 代表已从下阶段预支的额度（已支付 2x 惩罚），应保留到日切
            // 如果扣减 borrowKey，会导致"已预支"显示归零，且 getRemainingMinutes 失真
            edit.putInt(currentUsageKey, currentUsed + take)
            remaining -= take
            if (DefaultLogDebugConfig.quotaCalculation) {
                LogExporter.i("CreditManager", "[TIME_CONSUME] 当前段消耗 ${take}s: used ${currentUsed}→${currentUsed + take}/${currentLimitSeconds}s, borrowed=${currentBorrowed}s, newAvail=${(currentLimitSeconds - currentUsed - take + currentBorrowed).coerceAtLeast(0)}s")
            }
        }

        if (remaining <= 0) {
            edit.apply()
            val afterRemainingMins = getRemainingMinutes(context)
            if (DefaultLogDebugConfig.quotaCalculation) {
                LogExporter.i("CreditManager", "[TIME_CONSUME] 完成: 全部 ${secondsToConsume}s 从当前段消耗, 剩余时长 ${formatMinutesForLog(beforeRemainingMins)}→${formatMinutesForLog(afterRemainingMins)}min")
            }
            quotaConfigEventBridge.recordRestQuotaChange(
                sessionId = transactionId,
                before = "${formatMinutesForLog(beforeRemainingMins)}min",
                after = "${formatMinutesForLog(afterRemainingMins)}min",
                reason = "CONSUME"
            )
            return
        }

        if (DefaultLogDebugConfig.quotaCalculation) {
            LogExporter.i("CreditManager", "[TIME_CONSUME] ${remaining}s 未消耗完，尝试从历史段(前序结余)扣除")
        }

        var i = firstLogicalIndex
        while (i != currentIndex && remaining > 0) {
            val seg = segments[i]
            val key = generateSegmentKey(seg)

            if (!seg.isActiveToday()) {
                i = (i + 1) % segments.size
                if (i == firstLogicalIndex) break
                continue
            }

            // 🚀 使用 getUsageKeyForSegment 确保跨天段读写 key 一致
            val usageKey = getUsageKeyForSegment(context, seg)
            val used = prefs.getInt(usageKey, 0)
            val avail = (seg.limitMins * 60 - used).coerceAtLeast(0)

            if (avail > 0) {
                val take = minOf(avail, remaining)
                edit.putInt(usageKey, used + take)
                remaining -= take
                if (DefaultLogDebugConfig.quotaCalculation) {
                    LogExporter.i("CreditManager", "[TIME_CONSUME] 历史段(${seg.name})消耗 ${take}s: used ${used}→${used + take}/${seg.limitMins * 60}s (前序结余扣减)")
                }
            }
            i = (i + 1) % segments.size
            if (i == firstLogicalIndex) break
        }

        if (remaining > 0) {
            if (DefaultLogDebugConfig.quotaCalculation) {
                LogExporter.w("CreditManager", "[TIME_CONSUME] ⚠️ ${remaining}s 无法消耗! 所有段已耗尽. secondsToConsume=$secondsToConsume")
            }
        }

        edit.apply()
        val afterRemainingMins = getRemainingMinutes(context)
        if (DefaultLogDebugConfig.quotaCalculation) {
            LogExporter.i("CreditManager", "[TIME_CONSUME] 完成: 总消耗 ${secondsToConsume}s, 剩余时长 ${formatMinutesForLog(beforeRemainingMins)}→${formatMinutesForLog(afterRemainingMins)}min")
        }
        quotaConfigEventBridge.recordRestQuotaChange(
            sessionId = transactionId,
            before = "${formatMinutesForLog(beforeRemainingMins)}min",
            after = "${formatMinutesForLog(afterRemainingMins)}min",
            reason = "CONSUME"
        )
    }

    /**
     * 惩罚扣费：当前额度 1x，借用(未来)额度 multiplier x
     * 🚀 [Fix] 惩罚必须反映在当前额度上——先扣当前 used，不足再借
     *   - 当前额度含已预支（borrowed）
     *   - 扣费只增 used，不减 borrowKey
     *   - 当前不够时：强制扣 used + 借未来 + 设 borrowKey（net avail 不变但 borrow 可见）
     */
    fun consumePenaltySeconds(
        context: Context,
        penaltySec: Int,
        borrowedMultiplier: Int = 2,
        transactionId: String = nextQuotaConfigId("PENALTY")
    ) {
        val segments = TimeRuleManager.getTimeSegments(context)
        val currentIndex = TimeRuleManager.getCurrentSegmentIndex(context)
        if (currentIndex == -1) {
            LogExporter.logEvent("Penalty", "[ABORT] currentIndex==-1 (gap period)")
            return
        }

        if (isCurrentSegmentPrivileged(context)) {
            LogExporter.logEvent("Penalty", "[ABORT] current segment is privileged")
            return
        }

        // ============================================================
        // [Boundary 6] Pre-compute count ledger keys BEFORE any mutation.
        // DA-09 fail-fast contract: if any required key cannot resolve
        // (legacy migration failed / no legacy to migrate from), the
        // function must abort without committing any time ledger writes.
        // The keys are pre-computed here, NOT inside the success branches,
        // so a single validation pass guards all three return paths.
        // ============================================================
        val currentSeg = segments[currentIndex]
        val nextSeg = segments[(currentIndex + 1) % segments.size]
        val currentCountUsageKey = getCountUsageKeyForSegment(context, currentSeg)
        val nextCountUsageKey = getCountUsageKeyForSegment(context, nextSeg)
        val nextCountBorrowKey = getCountBorrowKeyForSegment(context, nextSeg)
        if (currentCountUsageKey == null || nextCountUsageKey == null || nextCountBorrowKey == null) {
            LogExporter.logEvent(
                "Penalty",
                "[ABORT] count ledger key missing: currentUsage=$currentCountUsageKey nextUsage=$nextCountUsageKey nextBorrow=$nextCountBorrowKey"
            )
            return
        }

        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val edit = prefs.edit()

        // 1. 先强制从当前段扣（即使不够也扣，让 used 超过 limit）
        val currentUsageKey = getUsageKeyForSegment(context, currentSeg)
        val currentUsed = prefs.getInt(currentUsageKey, 0)
        val currentLimitSeconds = currentSeg.limitMins * 60
        val currentBorrowKey = getBorrowKeyForSegment(context, currentSeg)
        val currentBorrowed = prefs.getInt(currentBorrowKey, 0)

        // 强行扣除：used 直接 +penaltySec
        edit.putInt(currentUsageKey, currentUsed + penaltySec)
        val newUsed = currentUsed + penaltySec

        // 计算缺口：新 used 超出 limit + borrowed 的部分
        val newAvail = (currentLimitSeconds - newUsed + currentBorrowed).coerceAtLeast(0)
        val deficit = if (newAvail >= 0) 0 else penaltySec  // used(含borrow)不够才借

        if (DefaultLogDebugConfig.quotaCalculation) {
            LogExporter.logEvent("Penalty", "[STEP1] seg=${currentSeg.name} used=$currentUsed→$newUsed borrowed=$currentBorrowed limit=$currentLimitSeconds availBefore=${(currentLimitSeconds - currentUsed + currentBorrowed).coerceAtLeast(0)} availAfter=$newAvail penalty=$penaltySec")
        }

        val actualNeed = penaltySec - (currentLimitSeconds - currentUsed + currentBorrowed).coerceAtLeast(0).coerceAtMost(penaltySec)
        val borrowNeed = actualNeed.coerceAtLeast(0)

        // ============================================================
        // [Boundary 6] Canonical 11-field receipt builder. All three
        // charge branches funnel through here so the shape stays uniform
        // (INVAR-5). remainingCount is computed live from the current
        // count usage half-units (after mutation) so the receipt matches
        // the visible ledger state — not a placeholder "0".
        // ============================================================
        fun emitPenaltyReceipt(
            normalSec: Int,
            borrowedSec: Int,
            borrowedCostSec: Int
        ) {
            val remainingMinFmt = formatMinutesForLog(getRemainingMinutes(context))
            val remainingCountHalfUnits = prefs.getInt(currentCountUsageKey, 0)
            val remainingCountFmt = formatUsableCountHalfUnits(remainingCountHalfUnits.coerceAtLeast(0))
            quotaConfigEventBridge.recordPenalty(
                transactionId = transactionId,
                ruleId = "CONSECUTIVE_LOCK",
                durationSec = penaltySec.toString(),
                reason = "REPEATED_LOCK",
                normalSec = normalSec.toString(),
                borrowedSec = borrowedSec.toString(),
                borrowedCostSec = borrowedCostSec.toString(),
                remainingMin = "${remainingMinFmt}min",
                remainingCount = remainingCountFmt,
                cooldownAddedSec = "0",
                cooldownKind = "NONE"
            )
        }

        // ============================================================
        // [Boundary 6] Atomic count-ledger helper — accumulates ONE visible
        // count (== 2 half-units) to the current segment in the SAME
        // SharedPreferences.Editor as the time-ledger mutations. This
        // mirrors `consumeUsableCountWeighted(context, 2)` semantics
        // without spawning a separate `.apply()`, which would break
        // atomicity with the time ledger writes above.
        // ============================================================
        fun commitCurrentCountUsageOne() {
            val cur = prefs.getInt(currentCountUsageKey, 0)
            edit.putInt(currentCountUsageKey, cur + COUNT_PENALTY_CURRENT_HALF_UNITS)
        }
        // Two visible counts (== 4 half-units) on the next segment —
        // mirrors SPEC §Bug 4 acceptance F (next -2 visible counts).
        fun commitNextCountUsageTwo() {
            val nxt = prefs.getInt(nextCountUsageKey, 0)
            edit.putInt(nextCountUsageKey, nxt + COUNT_PENALTY_NEXT_HALF_UNITS)
        }
        // One visible count borrowed (== 2 half-units) on the next
        // segment's count borrow ledger — mirrors `borrowCountFromNext(
        // context, COUNT_PENALTY_NEXT_BORROW_HALF_UNITS)` semantics
        // without spawning a separate `.apply()`.
        fun commitNextCountBorrowOne() {
            val nxtBorrow = prefs.getInt(nextCountBorrowKey, 0)
            edit.putInt(nextCountBorrowKey, nxtBorrow + COUNT_PENALTY_NEXT_BORROW_HALF_UNITS)
        }

        if (borrowNeed <= 0) {
            // Branch 1: fully covered by current segment. One visible
            // count charge on current only — no future borrow needed.
            commitCurrentCountUsageOne()
            edit.apply()
            if (DefaultLogDebugConfig.quotaCalculation) {
                LogExporter.logEvent("Penalty", "[DONE] fully covered by current segment (used now $newUsed, count usage +${COUNT_PENALTY_CURRENT_HALF_UNITS} half-units)")
            }
            emitPenaltyReceipt(
                normalSec = penaltySec,
                borrowedSec = 0,
                borrowedCostSec = 0
            )
            return
        }

        // 🚀 检查借用上限，惩罚也不能超过 maxBorrowable
        val maxBorrowSecs = (getMaxBorrowableMinutes(context) * 60).toInt()
        var clampedBorrow = borrowNeed.coerceAtMost(maxBorrowSecs)
        if (clampedBorrow < borrowNeed) {
            if (DefaultLogDebugConfig.quotaCalculation) {
                LogExporter.logEvent("Penalty", "[LIMIT] borrowNeed=$borrowNeed clamped to maxBorrowable=${maxBorrowSecs}s")
            }
        }

        // 1.5. 当前段不够 → 先消耗历史段（当前段之前的段）结余，不足再从下一阶段借
        if (clampedBorrow > 0) {
            val firstLogicalIndex = TimeRuleManager.getLogicalFirstSegmentIndex(context)
            var remainingDeficit = clampedBorrow
            var j = firstLogicalIndex
            while (j != currentIndex && remainingDeficit > 0) {
                val pastSeg = segments[j]
                if (!pastSeg.isActiveToday()) {
                    j = (j + 1) % segments.size
                    if (j == firstLogicalIndex) break
                    continue
                }
                val pastUsageKey = getUsageKeyForSegment(context, pastSeg)
                val pastUsed = prefs.getInt(pastUsageKey, 0)
                val pastAvail = (pastSeg.limitMins * 60 - pastUsed).coerceAtLeast(0)
                if (pastAvail > 0) {
                    val take = minOf(pastAvail, remainingDeficit)
                    edit.putInt(pastUsageKey, pastUsed + take)
                    remainingDeficit -= take
                    if (DefaultLogDebugConfig.quotaCalculation) {
                        LogExporter.logEvent("Penalty", "[STEP1.5] consumed ${take}s from past[$j]=${pastSeg.name} (used=$pastUsed→${pastUsed + take}, surplus=${pastAvail - take}s)")
                    }
                }
                j = (j + 1) % segments.size
                if (j == firstLogicalIndex) break
            }
            clampedBorrow = remainingDeficit
            if (clampedBorrow <= 0) {
                // Branch 2: fully covered by historical segments. The
                // current segment still owes ONE visible count for the
                // penalty itself (matches Branch 1's ledger debit on
                // current), but no future borrow is needed.
                commitCurrentCountUsageOne()
                edit.apply()
                if (DefaultLogDebugConfig.quotaCalculation) {
                    LogExporter.logEvent("Penalty", "[DONE] fully covered by historical segments (no borrowing needed, count usage +${COUNT_PENALTY_CURRENT_HALF_UNITS} half-units)")
                }
                emitPenaltyReceipt(
                    normalSec = penaltySec,
                    borrowedSec = 0,
                    borrowedCostSec = 0
                )
                return
            }
        }

        // 2. 借用未来段：缺口 × multiplier（受上限约束）
        val borrowedCost = clampedBorrow * borrowedMultiplier
        var borrowedRemaining = borrowedCost
        var borrowedTotal = 0
        if (DefaultLogDebugConfig.quotaCalculation) {
            LogExporter.logEvent("Penalty", "[STEP2] needBorrow=$clampedBorrow multiplier=$borrowedMultiplier cost=$borrowedCost segCount=${segments.size} curIdx=$currentIndex")
        }
        var i = (currentIndex + 1) % segments.size
        while (i != currentIndex && borrowedRemaining > 0) {
            val seg = segments[i]
            if (!seg.isActiveToday()) {
                i = (i + 1) % segments.size
                continue
            }
            val usageKey = getUsageKeyForSegment(context, seg)
            val used = prefs.getInt(usageKey, 0)
            val avail = (seg.limitMins * 60 - used).coerceAtLeast(0)
            if (avail > 0) {
                val take = minOf(avail, borrowedRemaining)
                edit.putInt(usageKey, used + take)
                borrowedRemaining -= take
                borrowedTotal += take
                if (DefaultLogDebugConfig.quotaCalculation) {
                    LogExporter.logEvent("Penalty", "[STEP2] took ${take}s from[$i]=${seg.name} (used=$used→${used+take})")
                }
            }
            i = (i + 1) % segments.size
        }

        // 3. 记录 borrowKey（惩罚必须可见，但不超过上限）
        val borrowRecord = if (borrowedRemaining > 0) {
            (clampedBorrow * borrowedTotal.toDouble() / borrowedCost.toDouble()).toInt().coerceAtLeast(1)
        } else {
            clampedBorrow
        }
        edit.putInt(currentBorrowKey, currentBorrowed + borrowRecord)
        if (DefaultLogDebugConfig.quotaCalculation) {
            LogExporter.logEvent("Penalty", "[STEP3] borrowKey=$currentBorrowKey +${borrowRecord}s (was $currentBorrowed, now ${currentBorrowed + borrowRecord})")
        }

        // ============================================================
        // [Boundary 6] Branch 3 atomic count-ledger commit:
        //   current count usage += 2 half-units (one visible count)
        //   next    count usage += 4 half-units (two visible counts)
        //   next    count borrow ledger += 2 half-units (one borrowed)
        // All three writes share the same `edit` instance that holds the
        // time-ledger writes above, so the penalty commit is atomic.
        // ============================================================
        commitCurrentCountUsageOne()
        commitNextCountUsageTwo()
        commitNextCountBorrowOne()

        if (DefaultLogDebugConfig.quotaCalculation) {
            LogExporter.logEvent("Penalty", "[DONE] used=$newUsed borrowKey+=${borrowRecord}s chargedFuture=${borrowedTotal}s count[cur+=${COUNT_PENALTY_CURRENT_HALF_UNITS}/next+=${COUNT_PENALTY_NEXT_HALF_UNITS}/nextBorrow+=${COUNT_PENALTY_NEXT_BORROW_HALF_UNITS} half-units]")
        }
        edit.apply()
        // Branch 3: current segment already absorbed penaltySec at step 1;
        // historical take consumed clampedBorrow - borrowedTotal; future
        // borrowedTotal. The normalSec counter reports seconds absorbed by
        // current+historical segments (non-borrow), matching the ledger view.
        emitPenaltyReceipt(
            normalSec = penaltySec - borrowedTotal,
            borrowedSec = borrowedTotal,
            borrowedCostSec = borrowedCost
        )
    }

    /**
     * 🟢 [New] 计算提前结束时的比例返还。
     *
     * 公式：返还比例 = max(75%, 100% - 已用比例 × 25%)
     * 剩余数量 × 返还比例 = 实际返还数量
     *
     * 含义：用越少退越多，用越多退越少（底线 75%）。
     * 用 1%   → 返还 99% 的剩余
     * 用 25%  → 返还 94% 的剩余
     * 用 50%  → 返还 88% 的剩余
     * 用 100% → 返还 75% 的剩余（触底）
     *
     * @param unused 剩余未用的数量（分钟或分值）
     * @param usedRatio 已用比例 [0.0, 1.0]
     * @return 实际返还数量（保留一位小数）
     */
    fun calculateProportionalRefund(unused: Double, usedRatio: Double): Double {
        val clampedRatio = usedRatio.coerceIn(0.0, 1.0)
        val refundRatio = kotlin.math.max(0.75, 1.0 - clampedRatio * 0.25)
        return kotlin.math.round(unused * refundRatio * 10.0) / 10.0
    }

    /**
     * 🟢 [New] 退还分钟数 (用于提前结束休息)
     * Logic: LIFO (Reverse of Consumption)
     * 1. Refund Current Segment Usage
     * 2. Refund Historical Segments
     */
    fun refundMinutes(context: Context, minutesToRefund: Int) {
        refundSeconds(context, minutesToRefund * 60)
    }

    fun refundMinutes(context: Context, minutesToRefund: Double) {
        refundSeconds(context, (minutesToRefund * 60).toInt())
    }

    private fun refundSeconds(context: Context, secondsToRefund: Int) {
        if (secondsToRefund <= 0) return

        var remainingRefund = secondsToRefund
        val segments = TimeRuleManager.getTimeSegments(context)
        val activeIndex = TimeRuleManager.getCurrentSegmentIndex(context)
        val previousIndex = TimeRuleManager.getPreviousSegment(context)?.let(segments::indexOf) ?: -1
        val currentIndex = resolveRefundSegmentIndex(activeIndex, previousIndex)
        if (currentIndex < 0) return
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val edit = prefs.edit()

        // 1. Refund Current Segment Usage
        val currentSeg = segments[currentIndex]
        val currentUsageKey = getUsageKeyForSegment(context, currentSeg)
        val currentUsed = prefs.getInt(currentUsageKey, 0)

        if (currentUsed > 0) {
            val refundAmount = if (currentUsed >= remainingRefund) remainingRefund else currentUsed
            edit.putInt(currentUsageKey, currentUsed - refundAmount)
            remainingRefund -= refundAmount
        }

        // 2. Refund Historical Segments
        var i = currentIndex - 1
        if (i < 0) i = segments.size - 1 

        while (remainingRefund > 0 && i != currentIndex) {
            val seg = segments[i]
            // 🚀 [Fix] Only refund to segments that were active today
            if (!seg.isActiveToday()) {
                // 3. 检查是否在白名单中
        // This code block seems to be misplaced. It looks like app blocking logic
        // and does not belong inside refundMinutes or this specific if-block.
        // It also references 'packageName' which is not defined in this function.
        // Assuming this was intended for a 'shouldBlockApp' function.
        // If this is meant to be part of refund logic, please clarify.
        // As per instruction, inserting it faithfully, but noting potential issue.
        // if (GlobalParamsActivity.isWhitelisted(context, packageName)) {
        //     // Log.v(TAG, "Allow: Whitelisted $packageName")
        //     return false // This return would exit refundMinutes prematurely
        // }

        // // 🚀 [NEW] App Time Window Check (Precision Intercept)
        // // Logic:
        // // 1. If Full Day Rest (No active segments today) -> Exempt (Allow).
        // // 2. Else -> strict check AppWindowManager.isAppAllowed.
        // //    If managed and not in window -> BLOCK even during Rest/Gap.
        
        // if (!TimeRuleManager.isFullDayRest(context)) {
        //     if (!AppWindowManager.isAppAllowed(context, packageName)) {
        //         // Returns false if managed but NOT in window.
        //         // Log.d(TAG, "BLOCK: App Time Window denied for $packageName")
        //         return true // This return would exit refundMinutes prematurely
        //     }
        // }
        
        // // 4. 检查是否在黑名单中
        // if (!GlobalParamsActivity.isBlacklisted(context, packageName)) {
        //     // Log.v(TAG, "Allow: Not Blacklisted $packageName")
        //     return false // This return would exit refundMinutes prematurely
        // }
                i--
                if (i < 0) i = segments.size - 1
                if (i == currentIndex) break
                continue
            }
            
            val usageKey = getUsageKeyForSegment(context, seg)
            val used = prefs.getInt(usageKey, 0)
            
            if (used > 0) {
                val refundAmount = if (used >= remainingRefund) remainingRefund else used
                edit.putInt(usageKey, used - refundAmount)
                remainingRefund -= refundAmount
            }
            
            i--
            if (i < 0) i = segments.size - 1
            if (i == currentIndex) break
        }
        
        edit.apply()
    }
}
