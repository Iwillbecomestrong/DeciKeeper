/**
 * =================================================================================
 * [Context Header]
 * * @file: MyApplication.kt
 * * @role: Domain Skill
 * * @description: 应用级初始化入口。
 * * @responsibilities:
 * 1. 初始化全局配置与日志系统。
 * 2. 注册必要的系统回调。
 * * @context:
 * - 上游: Android Application
 * - 下游: CrashRecorder, LogExporter
 * * @constraints (必读):
 * 1. 初始化需轻量。
 * 2. 避免主线程耗时。
 * 3. 处理异常与空值。
 * =================================================================================
 */
package com.decikeeper.app

import android.app.Application
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.UserManager
import android.util.Log
import com.decikeeper.app.data.CrashRecorder
import com.decikeeper.app.data.LogExporter
import com.decikeeper.app.manager.AlarmScheduler
import com.decikeeper.app.manager.AutoBackupManager
import com.decikeeper.app.manager.CreditManager
import com.decikeeper.app.manager.PrivacyConsentManager
import com.decikeeper.app.manager.RecentTasksHideManager
import com.decikeeper.app.manager.StartupA11yCache
import com.decikeeper.app.manager.StartupProtectionManager
import com.decikeeper.app.manager.TimeRuleManager
import com.decikeeper.app.service.AlarmVibrationService
import com.decikeeper.app.service.CoreService













class MyApplication : Application() {

    companion object {
        lateinit var instance: MyApplication
            private set

        /** Test seam for the locked onCreate recovery path. */
        internal var directBootRecoveryStarter: ((android.content.Intent) -> Unit)? = null

        fun shouldStartFastLock(
            isInSegment: Boolean,
            isAdaptation: Boolean,
            hasOverlay: Boolean,
            isA11yEnabled: Boolean,
            isA11yActive: Boolean
        ): Boolean {
            return StartupProtectionManager.shouldStartFastLock(
                isInSegment = isInSegment,
                isAdaptation = isAdaptation,
                hasOverlay = hasOverlay,
                isA11yEnabled = isA11yEnabled
            )
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this

        val runIdTag = com.decikeeper.app.manager.ProtectionDiagnostics.currentTestRunId?.let { "runId=$it " } ?: ""
        Log.i("MyApplication", "[APP_ON_CREATE] ${runIdTag}processInstanceId=${com.decikeeper.app.manager.ProtectionDiagnostics.processInstanceId} uptime=${android.os.SystemClock.uptimeMillis()}")

        // Direct Boot 进程的 Application 也会先执行；此时禁止任何 CE 初始化。
        if (isUserLockedForDirectBoot()) {
            checkAndResumeAlarmIfNeeded(directBoot = true)
            // 本进程出生在 Direct Boot：Application.onCreate 解锁后不会再跑，
            // 动态注册 USER_UNLOCKED / USER_PRESENT（manifest 收不到 USER_UNLOCKED），解锁瞬间补挂 FastLock。
            com.decikeeper.app.manager.BootUnlockTrigger.armIfLocked(this)
            return
        }

        // 每日基准必须先于任何可能改动保护开关的入口拍好；幂等，同一业务日只会真正写一次。
        try {
            com.decikeeper.app.manager.DailyBaselineManager.ensureTodaySnapshot(this)
        } catch (e: Exception) {
            Log.w("MyApplication", "ensureTodaySnapshot failed (non-fatal)", e)
        }

        com.decikeeper.app.manager.ProtectionDiagnostics.dumpSnapshot(this, trigger = "MyApplication.onCreate")

        // 🔖 版本指纹 + 新装心跳
        printVersionFingerprint()
        startFreshInstallHeartbeat()

        CoreService.resetAntiUninstallWarmupForProcess()

        // DPS 缓存快速判断：上次关机前无障碍是否开启。
        // 新安装 / 从未开过无障碍 → false，跳过 FastLock，不闪屏。
        val startupCacheReadAt = System.currentTimeMillis()
        val a11yWasEnabled = StartupA11yCache.isA11yWasEnabledForStartup(this)
        Log.d("DeciKeeper", "MyApplication: startup a11y DPS cache=$a11yWasEnabled readAt=$startupCacheReadAt")
        if (a11yWasEnabled) {
            // 统一 CAS 入口：直接 addView 覆盖层（毫秒级出现）+ 启动 Service 负责前台通知、超时管理、handoff
            StartupProtectionManager.triggerBootFastLock(this, "app_on_create")
            Log.d("DeciKeeper", "MyApplication: Early boot FastLock overlay attached + service requested")
            // [2026-06-25] 诊断日志：记录 MyApplication 路径是否触发 FastLock
            // 用于排查"初始化界面刷新两次"现象
            // 详见 Issue_AntiUninstallBootGap 尝试 8
            Log.i("DeciKeeper", "[MyApp] earlyFastLock path: a11yWasEnabled=$a11yWasEnabled")
        }

        // ⏰ 闹钟震动恢复：FastLock 已异步拉起，互不依赖
        checkAndResumeAlarmIfNeeded()

        finishApplicationInit()
    }

    // ──── 版本指纹 · 新装心跳 ────

    /** 获取当前 APK 版本名（如 "1.0.0"） */
    private fun getAppVersionName(): String = try {
        packageManager.getPackageInfo(packageName, 0).versionName ?: "?"
    } catch (_: Exception) { "?" }

    /** 获取当前 APK 版本号（如 1） */
    @Suppress("DEPRECATION")
    private fun getAppVersionCode(): Int = try {
        @Suppress("DEPRECATION")
        packageManager.getPackageInfo(packageName, 0).versionCode
    } catch (_: Exception) { 0 }

    /**
     * 打印独特版本指纹到日志，搜索即可确认当前运行的 APK 版本
     *
     * 日志示例（一行，搜索 "▓▓▓▓▓" 快速定位）：
     *   ▓▓▓▓▓ DeciKeeper v1.0.0 ｜ build 1 ｜ 2026-06-14T11:08:20 ｜
     *   新装: YES(2min ago) ｜ 累计启动: 3次 ▓▓▓▓▓
     */
    private fun printVersionFingerprint() {
        val prefs = getSharedPreferences("version_fingerprint", MODE_PRIVATE)
        val firstRunTime = prefs.getLong("first_run_ms", 0L)
        val now = System.currentTimeMillis()
        val launchCount = prefs.getInt("launch_count", 0) + 1
        prefs.edit().putInt("launch_count", launchCount).apply()

        if (firstRunTime == 0L) {
            prefs.edit().putLong("first_run_ms", now).apply()
        }

        val version = getAppVersionName()
        val code = getAppVersionCode()
        val timeStr = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US).format(java.util.Date(now))
        val freshLabel = if (firstRunTime == 0L) "YES (本次)" else {
            val elapsed = now - firstRunTime
            when {
                elapsed < 60_000 -> "YES (${elapsed / 1000}s ago)"
                elapsed < 3_600_000 -> "YES (${elapsed / 60_000}min ago)"
                else -> "NO"
            }
        }
        Log.i("DeciKeeper", "▓▓▓▓▓ DeciKeeper v$version ｜ build $code ｜ $timeStr ｜ 新装: $freshLabel ｜ 累计启动: ${launchCount}次 ▓▓▓▓▓")
    }

    /**
     * 新装心跳 — 仅当 [firstRunMs < 1h] 才触发，共 ≤7 条日志
     *
     * 时间点: t=0(已打印) → 30s → 2min → 5min → 10min → 30min → 60min
     * 搜 "[HB]" 可快速定位心跳日志，确认安装后应用持续在运行。
     */
    private fun startFreshInstallHeartbeat() {
        val prefs = getSharedPreferences("version_fingerprint", MODE_PRIVATE)
        val firstRunMs = prefs.getLong("first_run_ms", 0L)
        if (firstRunMs == 0L) return // 理论上不会走到这里

        val now = System.currentTimeMillis()
        val elapsed = now - firstRunMs
        if (elapsed >= 3_600_000L) return // 超过 1 小时，不发心跳

        val version = getAppVersionName()
        val handler = Handler(Looper.getMainLooper())

        // 按距离 firstRunMs 的偏移量（ms）调度
        val checkpoints = listOf(30_000L, 120_000L, 300_000L, 600_000L, 1_800_000L, 3_600_000L)
        for (offsetMs in checkpoints) {
            if (elapsed >= offsetMs) continue      // 已过该时间点，跳过
            val delayMs = offsetMs - elapsed
            val label = when (offsetMs) {
                30_000L    -> "30s"
                120_000L   -> "2min"
                300_000L   -> "5min"
                600_000L   -> "10min"
                1_800_000L -> "30min"
                3_600_000L -> "60min(end)"
                else       -> "${offsetMs / 1000}s"
            }
            handler.postDelayed({
                Log.i("DeciKeeper", "[HB] $label ｜ DeciKeeper v$version running OK ｜ uptime=${(System.currentTimeMillis() - firstRunMs) / 1000}s")
            }, delayMs)
        }
    }

    /**
     * 检测是否在闹钟震动期间被强制重启，若是则立即启动震动服务。
     * 放在 Application.onCreate() 而非 Receiver 中，确保在进程创建的最早时刻执行。
     */
    private fun checkAndResumeAlarmIfNeeded(directBoot: Boolean = false) {
        try {
            if (AlarmScheduler.isAlarmActive(this)) {
                val alarmId = AlarmScheduler.getActiveAlarmId(this)
                Log.i("DeciKeeper", "MyApplication: Alarm was active at shutdown (id=$alarmId), starting vibration service NOW")
                val vibrateIntent = android.content.Intent(this, AlarmVibrationService::class.java).apply {
                    putExtra(AlarmVibrationService.EXTRA_ALARM_ID, alarmId)
                    putExtra(AlarmVibrationService.EXTRA_DIRECT_BOOT, directBoot)
                    if (directBoot) {
                        putExtra(AlarmScheduler.EXTRA_SCHEDULE_TOKEN, AlarmScheduler.getActiveScheduleToken(this@MyApplication))
                    }
                }
                directBootRecoveryStarter?.invoke(vibrateIntent) ?: run {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        startForegroundService(vibrateIntent)
                    } else {
                        startService(vibrateIntent)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("DeciKeeper", "MyApplication: Failed to resume alarm vibration", e)
        }
    }

    private fun isUserLockedForDirectBoot(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false
        val userManager = getSystemService(UserManager::class.java) ?: return false
        return !userManager.isUserUnlocked
    }

    private fun finishApplicationInit() {
        // 🚀 [Crash Capture] Initialize Global Crash Recorder FIRST
        CrashRecorder.init(this)

        // 🚀 用户可控"最近任务隐藏"生命周期注册(默认 OFF,幂等)
        RecentTasksHideManager.register(this)

        // 🚀 常驻守护小浮窗（自律小盾牌）启动检查（若用户已开启且具备权限则自动恢复挂载）
        if (com.decikeeper.app.manager.GuardFloatingWindowManager.isFloatingGuardEnabled(this)) {
            com.decikeeper.app.manager.GuardFloatingWindowManager.show(this)
        }

        // 🚀 [Log] Initialize LogExporter with app context for disk-persistent logging
        LogExporter.init(this)

        // 🚀 启动时清理无障碍保护模块历史脏 key
        // 历史版本曾在 ConfigPendingManager 中写入 "a11y_service_self_pending"
        // 作为永久生效的"自关授权"标记。新版本改为纯内存短期授权，旧 key 必须清理。
        cleanupLegacyA11ySelfPendingKey()
        cleanupLegacyNotificationChannels()

        // REQ-5 合规 gate: 未来可能接入的统计/广告/推送/账号等"会收集个人信息的 SDK"
        // 全部收口在此；未同意时直接跳过并打审计日志。
        initializePostConsentComponentsIfNeeded()

        Log.d("DeciKeeper", "MyApplication: onCreate called. Checking lock status...")
        checkAndFastLock()
    }

    /**
     * REQ-5 合规 gate — 在用户同意《用户协议》和《隐私政策》之前，
     * 任何"会收集个人信息的 SDK"都不应被初始化。
     *
     * 当前 决定守护实际不接入任何第三方 SDK（无统计/广告/推送/账号）；
     * 该方法存在仅作为合规审计锚点 — 后续若引入第三方 SDK，统一在此分支内
     * 添加，**并必须配合 SharedPreferences 同意状态 gate**。
     *
     * 严禁：
     * 1. 在未同意时初始化任何统计/广告/推送/账号 SDK；
     * 2. 顺手重构既有 FastLock/Alarm 启动链（README §三.1 红线）。
     */
    fun initializePostConsentComponentsIfNeeded() {
        try {
            val consented = PrivacyConsentManager.isConsented(this)
            if (!PrivacyConsentManager.shouldInitializePostConsentComponents(consented)) {
                Log.i(
                    "DeciKeeper",
                    "MyApplication: post-consent init skipped (user has not accepted privacy policy yet)"
                )
                return
            }
            // 同意后才允许进入的"会收集个人信息的 SDK"初始化分支（占位）。
            // 当前项目无第三方 SDK，该分支为空实现 — 保留此结构作为后续扩展点。
            Log.i("DeciKeeper", "MyApplication: post-consent init OK (no third-party SDKs currently registered)")
        } catch (e: Exception) {
            // 任何异常都不应该阻止应用启动；记录后继续。
            Log.e("DeciKeeper", "MyApplication: post-consent init check failed", e)
        }
    }

    /**
     * 清理历史遗留的 a11y_service_self_pending key。
     * 旧版本在 ConfigPendingManager 的 pending_config_prefs 中保存了此 key，
     * 设为 "true" + 第二天 08:00 生效时间。新版本改为 A11ySelfDisableAuthorization
     * 纯内存短期授权，旧 key 必须清除以避免保护模块误读。
     */
    private fun cleanupLegacyA11ySelfPendingKey() {
        try {
            val prefs = getSharedPreferences("pending_config_prefs", MODE_PRIVATE)
            val oldValue = prefs.getString("a11y_service_self_pending", null)
            val oldEffectiveTime = prefs.getLong("a11y_service_self_pending_effective_time", 0L)
            if (oldValue != null || oldEffectiveTime != 0L) {
                prefs.edit()
                    .remove("a11y_service_self_pending")
                    .remove("a11y_service_self_pending_effective_time")
                    .apply()
                Log.w("DeciKeeper", "Cleanup legacy a11y_service_self_pending: value=$oldValue effectiveTime=$oldEffectiveTime")
                LogExporter.logEvent(
                    "DeciKeeper",
                    "[Cleanup] legacy a11y_service_self_pending: value=$oldValue effectiveTime=$oldEffectiveTime"
                )
            }
        } catch (e: Exception) {
            Log.e("DeciKeeper", "Failed to cleanup legacy a11y_service_self_pending", e)
        }
    }

    private fun cleanupLegacyNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
                val legacyChannels = listOf(
                    "ironwill_monitor_channel",
                    "IronWillGuardChannel",
                    "IronWillPermChannel",
                    "ironwill_fast_lock"
                )
                for (ch in legacyChannels) {
                    nm.deleteNotificationChannel(ch)
                }
            } catch (e: Exception) {
                Log.e("DeciKeeper", "Failed to delete legacy notification channels", e)
            }
        }
    }

    private fun checkAndFastLock() {
        try {
            // DPS 缓存路径已在 onCreate() 最前面拉起 FastLock，此处不再重复启动。
            if (StartupProtectionManager.earlyBootFastLockStarted) {
                Log.d("DeciKeeper", "MyApplication: FastLock already started via DPS cache, skipping checkAndFastLock.")
                return
            }

            val userManager = getSystemService(Context.USER_SERVICE) as android.os.UserManager
            if (Build.VERSION.SDK_INT >= 24 && !userManager.isUserUnlocked) {
                 Log.w("DeciKeeper", "User locked, skipping SharedPreferences check.")
                 return
            }

            // 1. 检查是否在"自由时间"（临时解锁）
            val freeTimeEnd = CreditManager.getFreeTimeEnd(this)
            if (System.currentTimeMillis() < freeTimeEnd) {
                Log.d("DeciKeeper", "MyApplication: In free time. No fast lock.")
                return
            }

            // 2. 检查是否在监控时段
            val currentSeg = TimeRuleManager.getCurrentSegment(this)

            var shouldLock = false
            if (currentSeg != null) {
                // 在设定时段内 -> 锁
                shouldLock = true
            } else {
                // 在空档期 -> 不锁 (Global Uninstall Protection handles safety)
                shouldLock = false
            }

            if (shouldLock) {
                val isAdaptation = CreditManager.isInAdaptationPeriod(this)
                val isA11yEnabled = StartupA11yCache.isA11yWasEnabledForStartup(this)
                val isA11yActive = CoreService.isServiceActive
                val hasOverlay = com.decikeeper.app.manager.PermissionManager.hasOverlayPermission(this)

                if (shouldStartFastLock(shouldLock, isAdaptation, hasOverlay, isA11yEnabled, isA11yActive)) {
                    Log.d("DeciKeeper", "MyApplication: LOCKED! Starting FastLockService (fallback).")
                    StartupProtectionManager.startFastLock(this)
                    StartupProtectionManager.earlyBootFastLockStarted = true
                } else {
                    Log.d("DeciKeeper", "MyApplication: Lock condition met but skipped (Adaptation:$isAdaptation, A11yEnabled:$isA11yEnabled, A11yActive:$isA11yActive, Overlay:$hasOverlay)")
                }
            } else {
                Log.d("DeciKeeper", "MyApplication: Unlocked state. No action.")
            }
        } catch (e: Exception) {
            Log.e("DeciKeeper", "MyApplication: Error during fast lock check", e)
        }
    }
}
