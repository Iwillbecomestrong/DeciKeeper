/**
 * =================================================================================
 * [Context Header]
 * * @file: A11yGuardService.kt
 * * @role: Domain Skill
 * * @description: 无障碍守护与恢复流程的服务入口。
 * * @responsibilities:
 * 1. 监控无障碍服务状态与自愈流程。
 * 2. 触发日志与告警反馈。
 * * @context:
 * - 上游: 系统无障碍框架
 * - 下游: LogExporter, PermissionManager
 * * @constraints (必读):
 * 1. 避免阻塞主线程。
 * 2. 必须处理空指针与权限缺失。
 * 3. 不直接承载复杂 UI 逻辑。
 * =================================================================================
 */
package com.decikeeper.app.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.decikeeper.app.R
import com.decikeeper.app.manager.AppVisibilityTracker
import com.decikeeper.app.manager.PermissionManager
import com.decikeeper.app.ui.LockWindowManager
import com.decikeeper.app.ui.MainActivity






/**
 * A11yGuardService: Guard service to keep IronWill alive and monitor Accessibility Service status.
 * 
 * Logic:
 * - Runs as a Foreground Service.
 * - Periodically checks CoreService.isServiceActive.
 * - If inactive, updates notification to warn user (but DOES NOT auto-jump).
 * - If active, shows "Protected" status.
 */
@SuppressLint("NotificationPermission")
class A11yGuardService : Service() {

    companion object {
        private const val WARN_COOLDOWN_MS = 5 * 60 * 60 * 1000L

        internal fun shouldTriggerInactiveWarningTransition(
            wasEnabled: Boolean,
            wasActive: Boolean,
            isEnabled: Boolean,
            isActive: Boolean,
            everEnabled: Boolean
        ): Boolean {
            if (!everEnabled) return false
            val wasHealthy = wasEnabled && wasActive
            val isHealthy = isEnabled && isActive
            return wasHealthy && !isHealthy
        }

        internal fun shouldAllowInactiveWarningInterval(nowMillis: Long, lastWarnMillis: Long): Boolean {
            if (lastWarnMillis <= 0L) return true
            return nowMillis - lastWarnMillis >= WARN_COOLDOWN_MS
        }

        internal fun isQuietHours(nowMillis: Long): Boolean {
            val cal = java.util.Calendar.getInstance().apply { timeInMillis = nowMillis }
            val hour = cal.get(java.util.Calendar.HOUR_OF_DAY)
            return hour >= 21
        }

        internal fun shouldSendInactiveWarningContext(
            nowMillis: Long,
            isHomeVisible: Boolean,
            isLockScreen: Boolean
        ): Boolean {
            if (!isHomeVisible && !isLockScreen) return false
            if (isQuietHours(nowMillis)) return false
            return true
        }

    }

    private val CHANNEL_ID = "DeciKeeperGuardChannel"
    private val NOTIFICATION_ID = 2001
    private val TAG = "A11yGuardService"
    private val PREFS_NAME = "permission_monitor"
    private val KEY_A11Y_EVER_ENABLED = "a11y_ever_enabled"
    private val KEY_A11Y_LAST_ENABLED = "a11y_last_enabled"
    private val KEY_A11Y_LAST_ACTIVE = "a11y_last_active"
    private val KEY_A11Y_INACTIVE_PENDING = "a11y_inactive_pending"
    private val KEY_A11Y_LAST_WARN_TIME = "a11y_last_warn_time"
    private var handler: Handler? = null
    private var checkRunnable: Runnable? = null
    private var lastNotifiedActive: Boolean? = null
    private var lastNotifiedMode: String? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        // [DO NOT DELETE] 关联 Requirements.md：权限只在需要时弹窗
        // Android 13+ 缺少 POST_NOTIFICATIONS 时 startForeground 会抛 SecurityException
        // 此处静默降级，不阻断服务启动。权限状态由 HomeFragment 主页向导提示用户。
        try {
            startForeground(NOTIFICATION_ID, buildNotification(isActive = false, showWarning = false))
        } catch (e: SecurityException) {
            Log.w(TAG, "startForeground failed: POST_NOTIFICATIONS not granted")
        }
        startMonitoring()

        // 🛡️ 启动统一权限监控（10秒周期，精准检测权限丢失）
        PermissionManager.startMonitoring(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onDestroy() {
        super.onDestroy()
        stopMonitoring()
        PermissionManager.stopMonitoring()
    }

    private fun startMonitoring() {
        handler = Handler(Looper.getMainLooper())
        checkRunnable = object : Runnable {
            override fun run() {
                checkServiceStatus()
                handler?.postDelayed(this, 5000) // Check every 5 seconds
            }
        }
        handler?.post(checkRunnable!!)
    }

    private fun stopMonitoring() {
        checkRunnable?.let { handler?.removeCallbacks(it) }
    }

    private fun checkServiceStatus() {
        val isEnabled = PermissionManager.isAccessibilityEnabled(this)
        val isActive = PermissionManager.isAccessibilityActive()
        updateNotification(isEnabled, isActive)
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
    private fun updateNotification(isEnabled: Boolean, isActive: Boolean) {
        if (!canPostNotifications()) return
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        var everEnabled = prefs.getBoolean(KEY_A11Y_EVER_ENABLED, false)
        val lastEnabled = prefs.getBoolean(KEY_A11Y_LAST_ENABLED, isEnabled)
        val lastActive = prefs.getBoolean(KEY_A11Y_LAST_ACTIVE, isActive)
        var pending = prefs.getBoolean(KEY_A11Y_INACTIVE_PENDING, false)
        val lastWarnTime = prefs.getLong(KEY_A11Y_LAST_WARN_TIME, 0L)

        // ---- [NOTIFY-DIAG] 打印完整决策链入参 ----
        Log.d(TAG, "[NOTIFY-DIAG] updateNotification called: " +
            "isEnabled=$isEnabled, isActive=$isActive, " +
            "lastEnabled=$lastEnabled, lastActive=$lastActive, " +
            "everEnabled=$everEnabled, pending=$pending, " +
            "lastWarnTime=${if (lastWarnTime > 0) java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date(lastWarnTime)) else "never"}")

        if (isEnabled && !everEnabled) {
            everEnabled = true
            Log.d(TAG, "[NOTIFY-DIAG] everEnabled set to true (first time A11y enabled)")
        }

        val transitionTriggered = shouldTriggerInactiveWarningTransition(
            wasEnabled = lastEnabled,
            wasActive = lastActive,
            isEnabled = isEnabled,
            isActive = isActive,
            everEnabled = everEnabled
        )
        if (transitionTriggered) {
            pending = true
            val runIdTag = com.decikeeper.app.manager.ProtectionDiagnostics.currentTestRunId?.let { "runId=$it " } ?: ""
            Log.w(TAG, "[A11Y_GUARD_HEALTH] ${runIdTag}Transition detected (healthy->inactive) wasEnabled=$lastEnabled wasActive=$lastActive isEnabled=$isEnabled isActive=$isActive")
            Log.w(TAG, "[NOTIFY-DIAG] Transition detected (healthy→inactive), pending=true. " +
                "wasEnabled=$lastEnabled, wasActive=$lastActive → isEnabled=$isEnabled, isActive=$isActive")
        } else {
            Log.d(TAG, "[NOTIFY-DIAG] No transition. pending stays=$pending " +
                "(everEnabled=$everEnabled, wasHealthy=${lastEnabled && lastActive}, isHealthy=${isEnabled && isActive})")
        }

        prefs.edit()
            .putBoolean(KEY_A11Y_EVER_ENABLED, everEnabled)
            .putBoolean(KEY_A11Y_LAST_ENABLED, isEnabled)
            .putBoolean(KEY_A11Y_LAST_ACTIVE, isActive)
            .putBoolean(KEY_A11Y_INACTIVE_PENDING, pending)
            .apply()

        if (isEnabled && isActive) {
            if (pending) {
                prefs.edit().putBoolean(KEY_A11Y_INACTIVE_PENDING, false).apply()
                Log.d(TAG, "[NOTIFY-DIAG] A11y healthy, cleared stale pending flag")
            }
            val runIdTag = com.decikeeper.app.manager.ProtectionDiagnostics.currentTestRunId?.let { "runId=$it " } ?: ""
            Log.i(TAG, "[A11Y_GUARD_HEALTH] ${runIdTag}Healthy isEnabled=true isActive=true")
            Log.d(TAG, "[NOTIFY-DIAG] Branch: A11y healthy → notifyIfChanged(active)")
            notifyIfChanged(isActive = true, mode = "active", force = false)
            return
        }

        val nowMillis = System.currentTimeMillis()
        val isHome = AppVisibilityTracker.isHomeVisible()
        val isLock = LockWindowManager.isLocked
        val allowContext = shouldSendInactiveWarningContext(
            nowMillis = nowMillis,
            isHomeVisible = isHome,
            isLockScreen = isLock
        )
        val quietHours = isQuietHours(nowMillis)
        val intervalOk = shouldAllowInactiveWarningInterval(nowMillis, lastWarnTime)

        Log.d(TAG, "[NOTIFY-DIAG] Inactive branch: pending=$pending, " +
            "allowContext=$allowContext (isHome=$isHome, isLock=$isLock, quietHours=$quietHours), " +
            "intervalOk=$intervalOk (gap=${if (lastWarnTime > 0) "${(nowMillis - lastWarnTime) / 60_000}min" else "no-prev"})")

        if (pending && allowContext) {
            if (intervalOk) {
                Log.w(TAG, "[NOTIFY-DIAG] ✅ WILL SEND warning notification: all gates passed " +
                    "(pending=true, context=ok, interval=ok)")
                notifyIfChanged(isActive = false, mode = "warning", force = true)
                prefs.edit()
                    .putBoolean(KEY_A11Y_INACTIVE_PENDING, false)
                    .putLong(KEY_A11Y_LAST_WARN_TIME, nowMillis)
                    .apply()
            } else {
                Log.d(TAG, "[NOTIFY-DIAG] ❌ SKIP: interval not met (cooldown 5h). Clearing pending.")
                prefs.edit().putBoolean(KEY_A11Y_INACTIVE_PENDING, false).apply()
            }
        } else {
            if (pending && !allowContext) {
                Log.d(TAG, "[NOTIFY-DIAG] ❌ SKIP: pending=true but context blocked " +
                    "(quietHours=$quietHours, isHome=$isHome, isLock=$isLock)")
            }
            Log.d(TAG, "[NOTIFY-DIAG] Branch: neutral → notifyIfChanged(neutral)")
            notifyIfChanged(isActive = false, mode = "neutral", force = false)
        }
    }

    private fun notifyIfChanged(isActive: Boolean, mode: String, force: Boolean) {
        if (!force && lastNotifiedActive == isActive && lastNotifiedMode == mode) {
            return
        }

        val showWarning = mode == "warning"
        if (Build.VERSION.SDK_INT >= 33) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                return
            }
        }
        
        try {
            val notification = buildNotification(isActive = isActive, showWarning = showWarning)
            NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, notification)
            lastNotifiedActive = isActive
            lastNotifiedMode = mode
        } catch (e: SecurityException) {
            Log.e(TAG, "Failed to post notification: permission denied", e)
        }
    }

    private fun buildNotification(isActive: Boolean, showWarning: Boolean): Notification {
        var title = if (isActive) "决定守护 守护中" else "决定守护 运行中"
        var content = if (isActive) "正在守护您的自律边界" else "决定守护守护服务正在后台运行"
        
        if (!isActive && showWarning) {
            title = "⚠️ 服务已断开"
            content = "无障碍服务未运行，请点击开启"
        }

        // Intent to launch MainActivity or Settings
        // REQ-5: PendingIntent 必须在 build 时确定目标 Activity；不能像同步 startActivity 那样
        // 在 MainActivityEntry.start() 里动态改投。但 MainActivity.onCreate 已有 isConsented
        // 防线作为兜底（未同意时 finishAffinity + 跳 PrivacyConsentActivity），
        // 因此这里保留对 MainActivity 的直引是安全的：通知点击 → 进 MainActivity → 未同意
        // → 立刻被守门员重定向到 PrivacyConsentActivity。
        val intent = if (isActive) {
            Intent(this, MainActivity::class.java)
        } else {
            // If inactive, clicking notification goes to Accessibility Settings or Main
            // User prefers "Scene guidance" in Main. So going to Main is safer.
            Intent(this, MainActivity::class.java)
        }
        val pendingIntent = android.app.PendingIntent.getActivity(
            this, 0, intent,
            android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(content)
            .setSmallIcon(R.mipmap.ic_launcher) // Assuming icon exists
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "决定守护守护服务",
                NotificationManager.IMPORTANCE_LOW
            )
            channel.description = "决定守护后台服务守护监控通知"
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

}
