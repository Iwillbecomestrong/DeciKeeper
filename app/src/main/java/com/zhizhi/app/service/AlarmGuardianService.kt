package com.zhizhi.app.service

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import com.zhizhi.app.data.AndroidLogcatNormalEventSink
import com.zhizhi.app.data.DefaultLogDebugConfig
import com.zhizhi.app.data.DefaultNormalEventRenderer
import com.zhizhi.app.data.LogExporter
import com.zhizhi.app.data.NormalEventLogger
import com.zhizhi.app.data.SystemEventClock
import com.zhizhi.app.manager.AlarmHeartbeatScheduler
import com.zhizhi.app.manager.AlarmProtectionEventBridge
import com.zhizhi.app.manager.AlarmScheduler
import com.zhizhi.app.manager.AlarmWindowManager
import com.zhizhi.app.ui.MainActivity
import com.zhizhi.app.worker.AlarmHeartbeatWorker
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale













/**
 * =================================================================================
 * [Context Header]
 * * @file: AlarmGuardianService.kt
 * * @role: Domain Skill
 * * @description: 前台守护服务 — 多层 AlarmManager 警戒入口 + Handler/WakeLock 精准触发。
 * * @responsibilities:
 * 1. 有启用闹钟时自动启动前台服务，24h 值守。
 * 2. 轻量模式：Handler 周期性自检 + 渐进式心跳，不持 WakeLock。
 * 3. 警戒入口：3 层 staggered AlarmManager（提前 90/60/30min），任一到达即进入警戒模式。
 *    vivo Doze 可能延迟所有 alarm，多层冗余确保至少一层在闹钟前到达。
 * 4. 警戒模式：持 Partial WakeLock + Handler 5s 精度计时 → 准时触发。
 * 5. 到时直接调用 AlarmVibrationService + AlarmWindowManager 触发闹钟。
 * * @context:
 * - 上游: AlarmScheduler.scheduleAll() / cancelAll() / clearAlarmActive()
 * - 下游: AlarmVibrationService, AlarmWindowManager
 * * @constraints (必读):
 * 1. 警戒入口使用 AlarmManager（会被 vivo 延迟），但多层冗余 + 90min 提前量吸收延迟。
 * 2. 最终的精准触发由 Handler+WakeLock 完成，不依赖 AlarmManager 精度。
 * 3. 现有的 setAlarmClock / Recovery / Backup 全部保留作为最后兜底。
 * 4. 绝不显示弹窗或声音通知——前台通知静默无声。
 * =================================================================================
 */
class AlarmGuardianService : Service() {

    companion object {
        private const val TAG = "AlarmGuardian"
        private const val NOTIFICATION_ID = 2001
        private const val CHANNEL_ID = "guardian_channel"

        /** 警戒入口提前量：90 分钟（vivo Doze 实测延迟可达 60+min，需充分缓冲） */
        private const val VIGILANT_ADVANCE_MS = 90 * 60 * 1000L
        /** 警戒入口层：3 层 staggered，间隔 30min，最外层 90min 前 */
        private val VIGILANT_ENTRY_LAYERS = longArrayOf(
            90 * 60 * 1000L,  // 层1: T-90min
            60 * 60 * 1000L,  // 层2: T-60min
            30 * 60 * 1000L   // 层3: T-30min
        )
        /** 轻量自检间隔：5 分钟（白天 Doze 未激活时使用） */
        private const val TICK_LIGHT_MS = 5 * 60 * 1000L
        /** 警戒自检间隔：5 秒（精确到秒级，WakeLock 保障） */
        private const val TICK_VIGILANT_MS = 5 * 1000L
        /** 同一闹钟防抖：2 秒内不重复触发 */
        private const val TRIGGER_THROTTLE_MS = 2000L

        /** 警戒入口 PendingIntent request code 基础值 */
        private const val VIGILANT_ENTRY_RC_BASE = 9000

        /** AlarmManager 高频心跳拉起 service 的 action */
        const val ACTION_HEARTBEAT = "com.zhizhi.app.ACTION_HEARTBEAT_GUARDIAN"
        /** heartbeat intent 携带的下次 alarm 触发时间 */
        const val EXTRA_NEXT_ALARM_TIME = "next_alarm_time"

        @Volatile
        private var instance: AlarmGuardianService? = null

        /**
         * 有启用闹钟→启动 Guardian，已在运行→刷新目标时间，无启用闹钟→停止。
         * 幂等：可被 scheduleAll() 在任意场景重复调用。
         */
        fun startIfNeeded(context: Context) {
            val hasEnabled = AlarmScheduler.getAlarms(context).any { it.enabled }

            if (!hasEnabled) {
                stopIfNoAlarms(context)
                return
            }

            val running = instance
            if (running != null) {
                // 已在运行：刷新目标闹钟时间（用户可能改了闹钟）
                running.handler.post { running.refreshNextAlarm() }
                Log.d(TAG, "startIfNeeded: already running, refreshing next alarm")
                return
            }

            val intent = Intent(context, AlarmGuardianService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
                Log.i(TAG, "startIfNeeded: starting Guardian service")
            } catch (e: Exception) {
                Log.e(TAG, "startIfNeeded: failed to start service: ${e.message}")
            }
        }

        /**
         * 无启用闹钟→停止服务。
         */
        fun stopIfNoAlarms(context: Context) {
            val hasEnabled = AlarmScheduler.getAlarms(context).any { it.enabled }
            if (!hasEnabled) {
                instance?.stopSelf()
                Log.i(TAG, "stopIfNoAlarms: no enabled alarms, stopping Guardian")
            }
        }

        /**
         * 闹钟响后用户解锁→Guardian 回到轻量模式，刷新到下一次触发时间。
         */
        fun onAlarmDismissed() {
            instance?.handler?.post {
                instance?.onAlarmDismissedInternal()
            }
        }

        /** 查询当前是否处于警戒模式 */
        fun isVigilant(): Boolean = instance?.guardianMode == Mode.VIGILANT

        /** 查询 Guardian 是否已触发指定闹钟（用于 AlarmReceiver 去重） */
        fun hasTriggered(alarmId: Int): Boolean {
            val inst = instance ?: return false
            return inst.triggeredAlarmId == alarmId &&
                    System.currentTimeMillis() - inst.lastTriggerTime < TRIGGER_THROTTLE_MS
        }
    }

    enum class Mode { LIGHT, VIGILANT }

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

    // ----- 实例状态 -----
    private var alarmId: Int = -1
    private var nextAlarmTime: Long = 0L
    private var nextAlarmScheduleToken: String = ""
    private var guardianMode: Mode = Mode.LIGHT
    private var wakeLock: PowerManager.WakeLock? = null
    private var lastTriggerTime: Long = 0L
    private var triggeredAlarmId: Int = -1
    private var lastHeartbeatTime: Long = 0L
    private val handler = Handler(Looper.getMainLooper())
    private val tickRunnable = Runnable { tick() }

    override fun onCreate() {
        super.onCreate()
        instance = this
        Log.i(TAG, "Guardian service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 警戒入口 alarm 触发 → 直接进入警戒模式
        if (intent?.getBooleanExtra("enter_vigilant", false) == true) {
            Log.i(TAG, "Guardian: vigilant entry alarm fired, entering vigilant mode")
            LogExporter.logEvent("AlarmGuardian", "VIGILANT_ENTRY_ALARM alarmId=$alarmId at ${formatTime(System.currentTimeMillis())}")
            enterVigilant()
            scheduleTick(TICK_VIGILANT_MS)
            return START_STICKY
        }

        // AlarmManager 高频心跳拉起：更新状态 + 续期下次心跳
        if (intent?.action == ACTION_HEARTBEAT) {
            val now = System.currentTimeMillis()
            lastHeartbeatTime = now
            Log.i(TAG, "Guardian: heartbeat alarm fired, refreshing next heartbeat")
            createNotificationChannel()
            startForeground(NOTIFICATION_ID, buildNotification())
            refreshNextAlarm()
            handler.post(tickRunnable)
            return START_STICKY
        }

        Log.i(TAG, "Guardian service started")
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
        refreshNextAlarm()
        handler.post(tickRunnable)
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        cancelVigilantEntry()
        cancelAllHeartbeats()
        releaseWakeLock()
        instance = null
        Log.i(TAG, "Guardian service destroyed — AlarmManager backups remain as fallback")
        super.onDestroy()
    }

    // ----- 核心循环 -----

    private fun tick() {
        val now = System.currentTimeMillis()

        // 闹钟已触发，等待用户解锁
        if (AlarmScheduler.isAlarmActive(this)) {
            scheduleTick(TICK_VIGILANT_MS)
            return
        }

        // 无有效目标 → 回到轻量模式
        if (nextAlarmTime <= 0 || alarmId < 0) {
            refreshNextAlarm()
            if (nextAlarmTime <= 0) {
                Log.w(TAG, "tick: no valid next alarm, retrying in light interval")
                scheduleTick(TICK_LIGHT_MS)
                return
            }
        }

        // 时间已到 → 触发
        if (now >= nextAlarmTime) {
            triggerAlarm()
            scheduleTick(TICK_VIGILANT_MS)
            return
        }

        // 渐进式心跳：距离越近，日志越密
        heartbeat()

        // 警戒区判断
        val timeUntilAlarm = nextAlarmTime - now
        if (timeUntilAlarm <= VIGILANT_ADVANCE_MS) {
            enterVigilant()
            scheduleTick(TICK_VIGILANT_MS)
        } else {
            enterLight()
            scheduleTick(TICK_LIGHT_MS)
        }
    }

    /**
     * 渐进式心跳：距离闹钟越近，事件日志越密集。
     * 确保在任何阶段都能从导出日志中确认 Guardian 存活状态。
     */
    private fun heartbeat() {
        val now = System.currentTimeMillis()
        val timeUntil = nextAlarmTime - now
        val interval = when {
            timeUntil > 60 * 60 * 1000 -> 60 * 60 * 1000L  // >60min: 每小时一条
            timeUntil > 30 * 60 * 1000 -> 15 * 60 * 1000L  // 30-60min: 每15分钟
            timeUntil > 10 * 60 * 1000 -> 5 * 60 * 1000L   // 10-30min: 每5分钟
            timeUntil > 3 * 60 * 1000  -> 1 * 60 * 1000L   // 3-10min: 每分钟
            timeUntil > 1 * 60 * 1000  -> 30 * 1000L        // 1-3min: 每30秒
            else                       -> 10 * 1000L         // <1min: 每10秒
        }
        if (now - lastHeartbeatTime >= interval) {
            lastHeartbeatTime = now
            if (DefaultLogDebugConfig.protectionVerbose) {
                // 🐛 [修复] 距离显示从单分钟数改为 XhYm 格式,避免跨日时心算错误
                // 例: 距闹钟 13h18min,显示 "13h18min"；"明天"标注只在 Notification 里挂在闹钟时间上
                val remainText = formatDuration(timeUntil)
                LogExporter.i("AlarmGuardian", "💓 心跳 | mode=$guardianMode | alarmId=$alarmId | $remainText")
            }
        }
    }

    private fun scheduleTick(intervalMs: Long) {
        handler.removeCallbacks(tickRunnable)
        handler.postDelayed(tickRunnable, intervalMs)
    }

    // ----- 模式切换 -----

    private fun enterLight() {
        if (guardianMode == Mode.LIGHT) return
        guardianMode = Mode.LIGHT
        releaseWakeLock()
        updateNotification()
        Log.i(TAG, "Guardian: light mode (next alarm: ${formatTime(nextAlarmTime)})")
        LogExporter.logEvent("AlarmGuardian", "MODE_LIGHT alarmId=$alarmId next=${formatTime(nextAlarmTime)}")
    }

    private fun enterVigilant() {
        if (guardianMode == Mode.VIGILANT) return
        guardianMode = Mode.VIGILANT
        acquireWakeLock()
        cancelVigilantEntry() // 已进入警戒，取消剩余警戒入口 alarm
        updateNotification()
        Log.i(TAG, "Guardian: VIGILANT mode (alarm in ${(nextAlarmTime - System.currentTimeMillis()) / 1000}s)")
        LogExporter.logEvent("AlarmGuardian", "VIGILANT_MODE alarmId=$alarmId at ${formatTime(nextAlarmTime)}")
    }

    // ----- 闹钟触发 -----

    private fun triggerAlarm() {
        val now = System.currentTimeMillis()

        // 防抖：同一闹钟 2s 内不重复触发
        if (alarmId == triggeredAlarmId && now - lastTriggerTime < TRIGGER_THROTTLE_MS) {
            Log.w(TAG, "triggerAlarm throttled: alarmId=$alarmId, last=${now - lastTriggerTime}ms ago")
            return
        }
        lastTriggerTime = now
        triggeredAlarmId = alarmId

        val scheduledStr = formatTime(nextAlarmTime)
        val actualStr = formatTime(now)
        val delayS = (now - nextAlarmTime) / 1000
        Log.i(TAG, "🎯 Guardian triggering alarm: alarmId=$alarmId, scheduled=$scheduledStr, actual=$actualStr, delay=${delayS}s")
        LogExporter.i("Alarm", "🎯 Guardian触发 | id=$alarmId | scheduled=$scheduledStr | actual=$actualStr | delay=${delayS}s")
        alarmProtectionEventBridge.recordAlarmWatchdog(action = "GUARDIAN_RECOVERY")
        alarmProtectionEventBridge.recordAlarmRecovery(
            alarmId = alarmId.toString(),
            recoveryAction = "GUARDIAN_TRIGGER"
        )

        // Receiver 与 Guardian 可能同时观察到同一次 occurrence；仅在调度令牌变化时
        // 创建新 session，避免第二个触发源清空当前题目/完成后延迟。
        // 使用 Guardian 锁定目标时保存的 occurrence token；不能在 Receiver 已经
        // 调度下一次后重新读取 AlarmEntry，否则同一次响铃会被误判成新 session。
        val scheduleToken = nextAlarmScheduleToken
        val newSession = !AlarmScheduler.isSameActiveOccurrence(this, alarmId, scheduleToken)
        AlarmScheduler.setAlarmActive(this, alarmId, newSession = newSession)
        LogExporter.logEvent("AlarmRecovery", "Guardian setAlarmActive(id=$alarmId, newSession=$newSession)")

        // 取消现有 Recovery 闹钟（Guardian 已在值守）
        AlarmScheduler.cancelRecoveryAlarm(this)

        // 启动震动服务
        AlarmVibrationService.start(this, alarmId)

        // 显示闹钟悬浮窗
        AlarmWindowManager.show(this, alarmId, newSession = newSession)

        // 调度下一次（循环闹钟的明天）
        val alarm = AlarmScheduler.findAlarm(this, alarmId)
        if (alarm != null && alarm.enabled) {
            AlarmScheduler.scheduleNext(this, alarm)
        }
    }

    // ----- 闹钟解除 -----

    private fun onAlarmDismissedInternal() {
        guardianMode = Mode.LIGHT
        releaseWakeLock()
        refreshNextAlarm()
        updateNotification()
        scheduleTick(TICK_LIGHT_MS)
        Log.i(TAG, "Guardian: alarm dismissed, next alarm: ${if (nextAlarmTime > 0) formatTime(nextAlarmTime) else "none"}")
        LogExporter.logEvent("AlarmGuardian", "ALARM_DISMISSED nextAlarmId=$alarmId nextTime=${if (nextAlarmTime > 0) formatTime(nextAlarmTime) else "none"}")
    }

    // ----- 目标刷新 -----

    /**
     * 遍历所有启用闹钟，使用 calculateNextTrigger() 计算下次触发时间，取最近的一个。
     * 同时更新警戒入口 AlarmManager alarm + WorkManager 远距心跳 + AlarmManager 近距心跳。
     */
    private fun refreshNextAlarm() {
        val alarms = AlarmScheduler.getAlarms(this).filter { it.enabled }
        if (alarms.isEmpty()) {
            alarmId = -1
            nextAlarmTime = 0L
            nextAlarmScheduleToken = ""
            cancelVigilantEntry()
            cancelAllHeartbeats()
            Log.d(TAG, "refreshNextAlarm: no enabled alarms")
            return
        }

        var bestId = -1
        var bestTime = Long.MAX_VALUE
        var bestToken = ""
        for (alarm in alarms) {
            val triggerTime = AlarmScheduler.getNextTriggerTime(alarm)
            if (triggerTime in 1 until bestTime) {
                bestTime = triggerTime
                bestId = alarm.id
                bestToken = alarm.scheduleToken
            }
        }

        if (bestId > 0) {
            // 目标变了，先取消旧 alarmId 的心跳
            if (alarmId > 0 && alarmId != bestId) {
                cancelHeartbeatsFor(alarmId)
            }
            alarmId = bestId
            nextAlarmTime = bestTime
            nextAlarmScheduleToken = bestToken
            Log.i(TAG, "refreshNextAlarm: alarmId=$alarmId at ${formatTime(nextAlarmTime)}")
            LogExporter.logEvent("AlarmGuardian", "TARGET_SET alarmId=$alarmId at ${formatTime(nextAlarmTime)}")
            // 重新调度警戒入口 alarm
            cancelVigilantEntry()
            scheduleVigilantEntry()
            // 调度远距 WorkManager 心跳 + 近距 AlarmManager 心跳
            scheduleHeartbeats()
        } else {
            alarmId = -1
            nextAlarmTime = 0L
            nextAlarmScheduleToken = ""
            cancelVigilantEntry()
            cancelAllHeartbeats()
            Log.w(TAG, "refreshNextAlarm: no upcoming trigger found")
        }
    }

    // ----- 心跳调度（远距 WorkManager + 近距 AlarmManager） -----

    /**
     * 根据 timeUntil 调度双轨心跳：
     * - 远距 (>30min)：WorkManager 每 15min 一次，免疫 Vivo AppStandby
     * - 近距 (≤30min)：AlarmManager 按距离动态 1min / 30s / 10s
     */
    private fun scheduleHeartbeats() {
        if (alarmId <= 0 || nextAlarmTime <= 0) return
        val timeUntil = nextAlarmTime - System.currentTimeMillis()
        if (timeUntil <= 0) return

        // 先取消旧的（同一 alarmId 重新调度）
        AlarmHeartbeatScheduler.cancel(this, alarmId)
        AlarmHeartbeatWorker.cancel(this, alarmId)

        // 远距 Worker（>30min 时生效）；≤30min 时 schedule() 内部自动跳过
        AlarmHeartbeatWorker.schedule(this, alarmId, nextAlarmTime)

        // 近距 AlarmManager 心跳（远距时 scheduleNext 内部自动跳过）
        AlarmHeartbeatScheduler.scheduleNext(this, alarmId, nextAlarmTime)
    }

    /** 取消所有心跳（alarm + worker），用于 onAlarmDismissedInternal / onDestroy / target 清空时 */
    private fun cancelAllHeartbeats() {
        if (alarmId > 0) cancelHeartbeatsFor(alarmId)
    }

    private fun cancelHeartbeatsFor(id: Int) {
        AlarmHeartbeatScheduler.cancel(this, id)
        AlarmHeartbeatWorker.cancel(this, id)
    }

    // ----- 警戒入口 AlarmManager（多层 staggered） -----

    /**
     * 调度 3 层警戒入口 alarm：T-90min / T-60min / T-30min。
     * vivo Doze 可能延迟所有 alarm，但多层冗余 + 大提前量确保至少一层在闹钟前到达。
     * 使用 setExactAndAllowWhileIdle (RTC_WAKEUP) —— 任何一层到达即进入警戒模式（持 WakeLock）。
     */
    private fun scheduleVigilantEntry() {
        if (nextAlarmTime <= 0) return
        val alarmManager = getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return

        for ((i, advanceMs) in VIGILANT_ENTRY_LAYERS.withIndex()) {
            val entryTime = nextAlarmTime - advanceMs
            if (entryTime <= System.currentTimeMillis()) {
                // 已过这一层的时间点 → 应直接进入警戒（下面 tick 会处理）
                Log.d(TAG, "scheduleVigilantEntry: layer${i + 1} (T-${advanceMs / 60000}min) already passed")
                continue
            }

            val pi = PendingIntent.getService(
                this,
                VIGILANT_ENTRY_RC_BASE + i,
                Intent(this, AlarmGuardianService::class.java).apply {
                    putExtra("enter_vigilant", true)
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            try {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, entryTime, pi)
                Log.i(TAG, "scheduleVigilantEntry: layer${i + 1} at ${formatTime(entryTime)} (T-${advanceMs / 60000}min)")
            } catch (e: SecurityException) {
                Log.w(TAG, "scheduleVigilantEntry layer${i + 1}: no SCHEDULE_EXACT_ALARM permission")
            } catch (e: Exception) {
                Log.w(TAG, "scheduleVigilantEntry layer${i + 1} failed: ${e.message}")
            }
        }
    }

    /**
     * 取消所有警戒入口 alarm（已进入警戒或闹钟已触发后不再需要）。
     */
    private fun cancelVigilantEntry() {
        val alarmManager = getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return

        for (i in VIGILANT_ENTRY_LAYERS.indices) {
            try {
                val pi = PendingIntent.getService(
                    this,
                    VIGILANT_ENTRY_RC_BASE + i,
                    Intent(this, AlarmGuardianService::class.java).apply {
                        putExtra("enter_vigilant", true)
                    },
                    PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
                )
                if (pi != null) {
                    alarmManager.cancel(pi)
                    pi.cancel()
                }
            } catch (e: Exception) {
                Log.w(TAG, "cancelVigilantEntry layer${i + 1}: ${e.message}")
            }
        }
    }

    // ----- WakeLock 管理 -----

    private fun acquireWakeLock() {
        if (wakeLock != null) return
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "IronWill:AlarmGuardian")
            wakeLock?.acquire(2 * 60 * 60 * 1000L) // 2 小时超时保底（兼容大提前量）
            Log.d(TAG, "WakeLock acquired (2h timeout)")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to acquire WakeLock: ${e.message}")
        }
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
                Log.d(TAG, "WakeLock released")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to release WakeLock: ${e.message}")
        }
        wakeLock = null
    }

    // ----- 通知管理 -----

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "夜间守护",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "闹钟守护服务状态"
                setShowBadge(false)
            }
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        // REQ-5: PendingIntent 在 build 时固定 Activity，不能像同步 startActivity 那样在
        // MainActivityEntry.start() 里动态改投。MainActivity.onCreate 已有 isConsented
        // 防线兜底：未同意 → finishAffinity + 跳 PrivacyConsentActivity。
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val modeLabel = if (guardianMode == Mode.VIGILANT) "🟠 警戒中" else "🔵 轻量值守"

        // 🚀 闹钟时间 + 距离（带"今天/明天"标注，避免跨日心算错）
        val timeText: String
        if (nextAlarmTime > 0) {
            val alarmStr = formatTime(nextAlarmTime)
            val remainMs = nextAlarmTime - System.currentTimeMillis()
            val remainText = formatDuration(remainMs)
            val dayLabel = formatDayLabel(nextAlarmTime, System.currentTimeMillis())
            timeText = "⏰ $dayLabel $alarmStr（$remainText）"
        } else {
            timeText = "无闹钟"
        }

        val contentText = "闹钟守护 | $timeText | $modeLabel"

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("知止 守护中")
                .setContentText(contentText)
                .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                .setOngoing(true)
                .setContentIntent(pendingIntent)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle("知止 守护中")
                .setContentText(contentText)
                .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                .setOngoing(true)
                .setContentIntent(pendingIntent)
                .build()
        }
    }

    private fun updateNotification() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildNotification())
    }

    // ----- 工具方法 -----

    private fun formatTime(epochMs: Long): String {
        return SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(epochMs))
    }

    /**
     * 格式化时长为 XhYm 格式，避免单分钟数被误读。
     * - < 1min: "Ns"
     * - < 1h:  "Nmin"
     * - < 1d:  "NhMmin"
     * - ≥ 1d:  "NdNhMmin"
     */
    private fun formatDuration(durationMs: Long): String {
        if (durationMs <= 0) return "已到"
        val totalMin = durationMs / 60_000L
        if (totalMin < 1) return "${durationMs / 1000}s"
        val days = totalMin / (24 * 60)
        val hours = (totalMin % (24 * 60)) / 60
        val mins = totalMin % 60
        return when {
            days > 0 -> "${days}d${hours}h${mins}min"
            hours > 0 -> "${hours}h${mins}min"
            else -> "${mins}min"
        }
    }

    /**
     * 标注目标是"今天/明天/后天/N天后"——避免跨日时心算错误。
     * 例: alarm=明天 07:00、now=今天 17:42 → "明天"
     */
    private fun formatDayLabel(targetMs: Long, nowMs: Long): String {
        if (targetMs <= 0) return "无"
        val targetCal = Calendar.getInstance().apply { timeInMillis = targetMs }
        val nowCal = Calendar.getInstance().apply { timeInMillis = nowMs }
        val targetDay = targetCal.get(Calendar.DAY_OF_YEAR)
        val nowDay = nowCal.get(Calendar.DAY_OF_YEAR)
        val targetYear = targetCal.get(Calendar.YEAR)
        val nowYear = nowCal.get(Calendar.YEAR)
        // 同年同日 → 今天
        if (targetYear == nowYear && targetDay == nowDay) return "今天"
        // 跨年需要单独处理（Dec 31 → Jan 1）
        val dayDiff = if (targetYear == nowYear) {
            targetDay - nowDay
        } else {
            // 跨年: 简化处理当作"明天"区间
            1
        }
        return when (dayDiff) {
            1 -> "明天"
            2 -> "后天"
            in 3..7 -> "${dayDiff}天后"
            else -> "${formatTime(targetMs)}"  // 超过 7 天直接显示具体时间
        }
    }
}
