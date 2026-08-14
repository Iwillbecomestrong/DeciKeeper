/**
 * =================================================================================
 * [Context Header]
 * * @file: PermissionManager.kt
 * * @role: Domain Skill
 * * @description: 权限检测与引导流程管理。
 * * @responsibilities:
 * 1. 统一检测关键权限状态。
 * 2. 提供权限引导与状态缓存。
 * * @context:
 * - 上游: HomeFragment, MainActivity
 * - 下游: 系统权限接口
 * * @constraints (必读):
 * 1. 不引入 UI 依赖。
 * 2. 处理权限与版本差异。
 * 3. 返回状态需稳定一致。
 * =================================================================================
 */
package com.ironwill.app

import android.app.AppOpsManager
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.Process
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

/**
 * PermissionManager: 统一权限检查与实时监控
 *
 * 功能：
 * 1. 统一所有权限检查逻辑（消除重复代码）
 * 2. 定期轮询检测权限变化（10秒周期）
 * 3. 与上次状态对比，精准发现"刚丢失"的权限
 * 4. 权限丢失时发送通知+日志记录
 */
object PermissionManager {

    private const val TAG = "PermissionManager"
    // [2026-06-25] 缩短轮询周期：10s → 3s，降低服务丢失到检测的窗口
    // 用户报告："无障碍没拦截，重开都不行，重启后好了"
    // 10 秒窗口足以让用户完成卸载尝试。详见 Issue_AntiUninstallBootGap 尝试 7
    private const val CHECK_INTERVAL_MS = 3_000L // 3秒检查一次
    private const val A11Y_SIGNAL_GRACE_MS = 15_000L
    private const val NOTIFICATION_THROTTLE_MS = 21_600_000L // 6小时通知限频
    private const val NOTIFICATION_CHANNEL_ID = "IronWillPermChannel"
    private const val NOTIFICATION_ID = 3001
    
    // 权限类型枚举
    enum class Permission(val displayName: String) {
        ACCESSIBILITY("无障碍服务"),
        OVERLAY("悬浮窗权限"),
        USAGE_STATS("使用情况访问"),
        BATTERY_OPTIMIZATION("电池优化豁免"),
        NOTIFICATION("通知权限")
    }
    
    // 上次检查的权限状态快照
    private val lastState = mutableMapOf<Permission, Boolean>()
    
    // 防抖计数器：ACCESSIBILITY 需要连续检测到丢失 N 次才判定为真丢失
    private const val A11Y_LOST_CONSECUTIVE_THRESHOLD = 2 // 连续 2 次（约 6 秒）才确认真丢失
    private var a11yLostConsecutiveCount = 0
    
    // 监控回调
    interface OnPermissionChangedListener {
        fun onPermissionLost(permission: Permission)
        fun onPermissionRestored(permission: Permission)
    }
    
    private var listener: OnPermissionChangedListener? = null
    private var handler: Handler? = null
    private var checkRunnable: Runnable? = null
    private var appContext: Context? = null
    private var isMonitoring = false
    
    // ===== 统一权限检查方法 =====
    
    /**
     * 无障碍服务是否已在系统设置中启用
     */
    fun isAccessibilityEnabled(context: Context): Boolean {
        val serviceName = "${context.packageName}/${CoreService::class.java.canonicalName}"
        val enabledServices = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        )
        return enabledServices?.contains(serviceName) == true
    }
    
    /**
     * 无障碍服务是否真正活跃（已连接并能响应事件）
     */
    fun isAccessibilityActive(): Boolean {
        return CoreService.isServiceActive
    }

    /**
     * 无障碍服务健康判定：
     * Enabled=true 且 (Active=true 或 最近收到过无障碍事件) 即视为健康。
     */
    fun isAccessibilityHealthy(context: Context, nowMillis: Long = System.currentTimeMillis()): Boolean {
        val enabled = isAccessibilityEnabled(context)
        val active = isAccessibilityActive()
        val recentSignal = CoreService.hasRecentAccessibilitySignal(nowMillis, A11Y_SIGNAL_GRACE_MS)
        return shouldTreatAccessibilityAsHealthy(enabled, active, recentSignal)
    }

    internal fun shouldTreatAccessibilityAsHealthy(
        isEnabled: Boolean,
        isActive: Boolean,
        hasRecentSignal: Boolean
    ): Boolean {
        return isEnabled && (isActive || hasRecentSignal)
    }
    
    /**
     * 悬浮窗权限
     */
    fun hasOverlayPermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(context)
        } else {
            true
        }
    }
    
    /**
     * 使用情况访问权限
     */
    fun hasUsageStatsPermission(context: Context): Boolean {
        return try {
            val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                appOps.unsafeCheckOpNoThrow(
                    AppOpsManager.OPSTR_GET_USAGE_STATS,
                    Process.myUid(),
                    context.packageName
                )
            } else {
                @Suppress("DEPRECATION")
                appOps.checkOpNoThrow(
                    AppOpsManager.OPSTR_GET_USAGE_STATS,
                    Process.myUid(),
                    context.packageName
                )
            }
            mode == AppOpsManager.MODE_ALLOWED
        } catch (e: Exception) {
            Log.e(TAG, "Error checking usage stats permission", e)
            false
        }
    }
    
    /**
     * 电池优化豁免
     */
    fun isBatteryOptimizationExempt(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            pm.isIgnoringBatteryOptimizations(context.packageName)
        } else {
            true
        }
    }
    
    /**
     * 通知权限
     */
    fun hasNotificationPermission(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) 
                != PackageManager.PERMISSION_GRANTED) {
                return false
            }
        }
        return NotificationManagerCompat.from(context).areNotificationsEnabled()
    }
    
    /**
     * 获取所有权限的当前状态
     */
    fun getAllPermissionStatus(context: Context): Map<Permission, Boolean> {
        return mapOf(
            Permission.ACCESSIBILITY to isAccessibilityHealthy(context),
            Permission.OVERLAY to hasOverlayPermission(context),
            Permission.USAGE_STATS to hasUsageStatsPermission(context),
            Permission.BATTERY_OPTIMIZATION to isBatteryOptimizationExempt(context),
            Permission.NOTIFICATION to hasNotificationPermission(context)
        )
    }
    
    /**
     * 获取所有缺失的权限列表
     */
    fun getMissingPermissions(context: Context): List<Permission> {
        return getAllPermissionStatus(context).filter { !it.value }.keys.toList()
    }
    
    // ===== 定期监控 =====
    
    /**
     * 启动权限监控（在 CoreService 或 A11yGuardService 中调用）
     */
    fun startMonitoring(context: Context, listener: OnPermissionChangedListener? = null) {
        if (isMonitoring) return
        
        appContext = context.applicationContext
        this.listener = listener
        isMonitoring = true
        
        // 初始化快照
        val currentStatus = getAllPermissionStatus(context)
        lastState.clear()
        lastState.putAll(currentStatus)
        
        Log.i(TAG, "[Monitor] Started. Initial state: ${currentStatus.map { "${it.key.displayName}=${if (it.value) "✅" else "❌"}" }}")
        LogExporter.logEvent(TAG, "[Monitor] Started. Missing: ${getMissingPermissions(context).map { it.displayName }}")
        
        // 创建通知渠道
        createNotificationChannel(context)
        
        handler = Handler(Looper.getMainLooper())
        checkRunnable = object : Runnable {
            override fun run() {
                performCheck()
                handler?.postDelayed(this, CHECK_INTERVAL_MS)
            }
        }
        // 首次检查延迟10秒（避免启动时误报）
        handler?.postDelayed(checkRunnable!!, CHECK_INTERVAL_MS)
    }
    
    /**
     * 停止监控
     */
    fun stopMonitoring() {
        isMonitoring = false
        checkRunnable?.let { handler?.removeCallbacks(it) }
        handler = null
        checkRunnable = null
        Log.i(TAG, "[Monitor] Stopped")
    }
    
    /**
     * 核心检查：对比上次状态，发现变化
     */
    private fun performCheck() {
        val ctx = appContext ?: return

        // [2026-06-25] 诊断日志：记录无障碍健康度的三要素，便于排查 flapping 问题
        // 详见 Issue_AntiUninstallBootGap 尝试 7
        val a11yEnabled = isAccessibilityEnabled(ctx)
        val a11yActive = isAccessibilityActive()
        val a11yRecentSignal = CoreService.hasRecentAccessibilitySignal(System.currentTimeMillis(), A11Y_SIGNAL_GRACE_MS)
        Log.v(TAG, "[Diag] a11y enabled=$a11yEnabled active=$a11yActive recentSignal=$a11yRecentSignal")

        val currentStatus = getAllPermissionStatus(ctx)

        val lostPermissions = mutableListOf<Permission>()
        val restoredPermissions = mutableListOf<Permission>()
        
        for ((permission, isGranted) in currentStatus) {
            val wasGranted = lastState[permission]
            
            if (wasGranted == true && !isGranted) {
                // 权限丢失！
                // 🚀 [Anti-Bounce] ACCESSIBILITY 需要连续检测到丢失才确认真丢失
                // 防止服务 flapping 期间瞬时断连误报
                if (permission == Permission.ACCESSIBILITY) {
                    a11yLostConsecutiveCount++
                    if (a11yLostConsecutiveCount < A11Y_LOST_CONSECUTIVE_THRESHOLD) {
                        Log.w(TAG, "⚠️ [Permission Lost (pending confirm)] ${permission.displayName} count=$a11yLostConsecutiveCount/$A11Y_LOST_CONSECUTIVE_THRESHOLD")
                        // 不下发 lostPermissions，继续等待下一次确认
                        continue
                    }
                }
                lostPermissions.add(permission)
                Log.w(TAG, "⚠️ [Permission Lost] ${permission.displayName}")
                LogExporter.logEvent(TAG, "[ALERT] Permission LOST: ${permission.displayName}")
            } else if (wasGranted == false && isGranted) {
                // 权限恢复！
                restoredPermissions.add(permission)
                Log.i(TAG, "✅ [Permission Restored] ${permission.displayName}")
                LogExporter.logEvent(TAG, "[OK] Permission restored: ${permission.displayName}")
                
                // 🚀 无障碍权限恢复时，清除过期的自关授权
                // 防止 flapping 后授权残留导致保护持续跳过
                if (permission == Permission.ACCESSIBILITY) {
                    A11ySelfDisableAuthorization.clear("permission_restored")
                }
            }
            
            // 🚀 [Anti-Bounce] 其他权限状态更新时，重置防抖计数器
            if (permission != Permission.ACCESSIBILITY || isGranted) {
                a11yLostConsecutiveCount = 0
            }
        }
        
        // 更新快照
        lastState.clear()
        lastState.putAll(currentStatus)
        
        // 通知回调
        for (p in lostPermissions) {
            listener?.onPermissionLost(p)
        }
        for (p in restoredPermissions) {
            listener?.onPermissionRestored(p)
        }
        
        // 权限丢失时发送通知
        if (lostPermissions.isNotEmpty()) {
            val monitorPrefs = ctx.getSharedPreferences("permission_monitor", Context.MODE_PRIVATE)
            val everEnabled = monitorPrefs.getBoolean("a11y_ever_enabled", false)

            // 过滤掉无障碍权限，如果它从未被开启过
            val filteredLost = lostPermissions.filterNot { it == Permission.ACCESSIBILITY && !everEnabled }
            val a11yFiltered = lostPermissions.any { it == Permission.ACCESSIBILITY && !everEnabled }

            Log.d(TAG, "[NOTIFY-DIAG][LOST] lostPermissions=${lostPermissions.map { it.displayName }}, " +
                "everEnabled=$everEnabled, a11yFiltered=$a11yFiltered, " +
                "filteredLost=${filteredLost.map { it.displayName }}")

            if (filteredLost.isNotEmpty()) {
                Log.w(TAG, "[NOTIFY-DIAG][LOST] ✅ Calling showPermissionLostNotification for: ${filteredLost.map { it.displayName }}")
                showPermissionLostNotification(ctx, filteredLost)
            } else {
                Log.d(TAG, "[NOTIFY-DIAG][LOST] ❌ All lost perms filtered out, no notification sent")
            }
        } else {
            // 检查是否有持续缺失的权限（每分钟提醒一次）
            val monitorPrefs = ctx.getSharedPreferences("permission_monitor", Context.MODE_PRIVATE)
            val nowA11yEnabled = isAccessibilityEnabled(ctx)
            if (nowA11yEnabled && !monitorPrefs.getBoolean("a11y_ever_enabled", false)) {
                monitorPrefs.edit().putBoolean("a11y_ever_enabled", true).apply()
                Log.d(TAG, "[NOTIFY-DIAG][MISSING] a11y_ever_enabled set to true")
            }
            val everEnabled = monitorPrefs.getBoolean("a11y_ever_enabled", false)
            val missing = currentStatus.filter { !it.value }.keys
                .filterNot { it == Permission.ACCESSIBILITY } // 移除持续提醒无障碍权限的逻辑，只在丢失时提醒一次

            Log.d(TAG, "[NOTIFY-DIAG][MISSING] noLost branch. allMissing=${currentStatus.filter { !it.value }.keys.map { it.displayName }}, " +
                "afterA11yFilter=${missing.map { it.displayName }}")

            if (missing.isNotEmpty()) {
                val lastReminder = monitorPrefs.getLong("last_missing_reminder", 0L)
                val elapsed = System.currentTimeMillis() - lastReminder
                val throttleOk = elapsed > NOTIFICATION_THROTTLE_MS
                Log.d(TAG, "[NOTIFY-DIAG][MISSING] missing=${missing.map { it.displayName }}, " +
                    "lastReminder=${if (lastReminder > 0) "${elapsed / 1000}s ago" else "never"}, " +
                    "throttleOk=$throttleOk")
                if (throttleOk) {
                    Log.w(TAG, "[NOTIFY-DIAG][MISSING] ✅ Calling showPermissionMissingNotification for: ${missing.map { it.displayName }}")
                    showPermissionMissingNotification(ctx, missing.toList())
                    monitorPrefs.edit().putLong("last_missing_reminder", System.currentTimeMillis()).apply()
                } else {
                    Log.d(TAG, "[NOTIFY-DIAG][MISSING] ❌ Throttled, skipping notification")
                }
            } else {
                // 所有权限正常，取消通知
                Log.d(TAG, "[NOTIFY-DIAG][MISSING] All permissions OK, dismissing notification")
                dismissNotification(ctx)
            }
        }
    }
    
    // ===== 通知 =====
    
    private fun createNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = android.app.NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "权限监控",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "权限丢失时立即提醒"
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 300, 200, 300)
            }
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }
    
    /**
     * 权限刚刚丢失的紧急通知
     */
    private fun showPermissionLostNotification(context: Context, lost: List<Permission>) {
        if (!canPostNotification(context)) return

        // 场景与时间限制
        val nowMillis = System.currentTimeMillis()
        val isQuietHours = isQuietHours(nowMillis)
        val isHomeVisible = AppVisibilityTracker.isHomeVisible()
        val isLockScreen = LockWindowManager.isLocked

        val cal = java.util.Calendar.getInstance().apply { timeInMillis = nowMillis }
        val hourStr = cal.get(java.util.Calendar.HOUR_OF_DAY).toString()
        Log.d(TAG, "[NOTIFY-DIAG][LOST-GATE] lost=${lost.map { it.displayName }}, " +
            "hour=$hourStr, isQuietHours=$isQuietHours, isHomeVisible=$isHomeVisible, isLockScreen=$isLockScreen")

        // 6小时通知限频，避免频繁打扰
        val monitorPrefs = context.getSharedPreferences("permission_monitor", Context.MODE_PRIVATE)
        val lastLostNotif = monitorPrefs.getLong("last_lost_notification", 0L)
        val elapsed = nowMillis - lastLostNotif
        if (elapsed < NOTIFICATION_THROTTLE_MS) {
            Log.i(TAG, "[NOTIFY-DIAG][LOST-GATE] ❌ Skip: Throttled (${elapsed / 1000}s ago < ${NOTIFICATION_THROTTLE_MS / 1000}s)")
            return
        }

        if (isQuietHours) {
            Log.i(TAG, "[NOTIFY-DIAG][LOST-GATE] ❌ Skip: Quiet hours (21:00-00:00), hour=$hourStr")
            return
        }

        if (!isHomeVisible && !isLockScreen) {
            Log.i(TAG, "[NOTIFY-DIAG][LOST-GATE] ❌ Skip: Not in Lock Screen or Main UI " +
                "(isHomeVisible=$isHomeVisible, isLockScreen=$isLockScreen)")
            return
        }

        Log.w(TAG, "[NOTIFY-DIAG][LOST-GATE] ✅ All gates passed, posting LOST notification: ${lost.map { it.displayName }}")
        
        val names = lost.joinToString("、") { it.displayName }
        val intent = android.content.Intent(context, MainActivity::class.java).apply {
            flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("show_permission_dialog", true)
        }
        // REQ-5: PendingIntent 在 build 时固定 Activity；MainActivity.onCreate 已有
        // isConsented 防线兜底（未同意 → finishAffinity + 跳 PrivacyConsentActivity）。
        val pendingIntent = android.app.PendingIntent.getActivity(
            context, 0, intent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )
        
        val notification = NotificationCompat.Builder(context, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("⚠️ 防护权限被关闭")
            .setContentText("${names}已丢失，点击修复")
            .setStyle(NotificationCompat.BigTextStyle()
                .bigText("检测到「${names}」被关闭，防护功能可能失效。\n请点击立即恢复权限。"))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setVibrate(longArrayOf(0, 300, 200, 300))
            .build()
        
        try {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
            monitorPrefs.edit().putLong("last_lost_notification", System.currentTimeMillis()).apply()
        } catch (e: SecurityException) {
            Log.e(TAG, "Failed to show notification", e)
        }
    }

    /**
     * 持续缺失的温和提醒
     */
    private fun showPermissionMissingNotification(context: Context, missing: List<Permission>) {
        if (!canPostNotification(context)) return

        // 场景与时间限制
        val nowMillis = System.currentTimeMillis()
        val isQuietHours = isQuietHours(nowMillis)
        val isHomeVisible = AppVisibilityTracker.isHomeVisible()
        val isLockScreen = LockWindowManager.isLocked

        val cal = java.util.Calendar.getInstance().apply { timeInMillis = nowMillis }
        val hourStr = cal.get(java.util.Calendar.HOUR_OF_DAY).toString()
        Log.d(TAG, "[NOTIFY-DIAG][MISSING-GATE] missing=${missing.map { it.displayName }}, " +
            "hour=$hourStr, isQuietHours=$isQuietHours, isHomeVisible=$isHomeVisible, isLockScreen=$isLockScreen")

        if (isQuietHours) {
            Log.i(TAG, "[NOTIFY-DIAG][MISSING-GATE] ❌ Skip: Quiet hours (21:00-00:00), hour=$hourStr")
            return
        }

        if (!isHomeVisible && !isLockScreen) {
            Log.i(TAG, "[NOTIFY-DIAG][MISSING-GATE] ❌ Skip: Not in Lock Screen or Main UI " +
                "(isHomeVisible=$isHomeVisible, isLockScreen=$isLockScreen)")
            return
        }

        Log.w(TAG, "[NOTIFY-DIAG][MISSING-GATE] ✅ All gates passed, posting MISSING notification: ${missing.map { it.displayName }}")
        
        val names = missing.joinToString("、") { it.displayName }
        val intent = android.content.Intent(context, MainActivity::class.java).apply {
            flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("show_permission_dialog", true)
        }
        // REQ-5: PendingIntent 在 build 时固定 Activity；MainActivity.onCreate 已有
        // isConsented 防线兜底（未同意 → finishAffinity + 跳 PrivacyConsentActivity）。
        val pendingIntent = android.app.PendingIntent.getActivity(
            context, 0, intent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )
        
        val notification = NotificationCompat.Builder(context, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("🔧 有权限未开启")
            .setContentText("${names}未开启，点击设置")
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()
        
        try {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
        } catch (e: SecurityException) {
            Log.e(TAG, "Failed to show notification", e)
        }
    }
    
    private fun dismissNotification(context: Context) {
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
    }
    
    private fun canPostNotification(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
                Log.w(TAG, "Skip notify: POST_NOTIFICATIONS not granted")
                return false
            }
        }
        val enabled = NotificationManagerCompat.from(context).areNotificationsEnabled()
        if (!enabled) {
            Log.w(TAG, "Skip notify: notifications disabled")
        }
        return enabled
    }
    
    private fun isQuietHours(nowMillis: Long): Boolean {
        val cal = java.util.Calendar.getInstance().apply { timeInMillis = nowMillis }
        val hour = cal.get(java.util.Calendar.HOUR_OF_DAY)
        return hour in 21..23
    }
}
