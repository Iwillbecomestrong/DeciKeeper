package com.zhizhi.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import com.zhizhi.app.data.AndroidLogcatNormalEventSink
import com.zhizhi.app.data.DefaultLogDebugConfig
import com.zhizhi.app.data.DefaultNormalEventRenderer
import com.zhizhi.app.data.LogExporter
import com.zhizhi.app.data.NormalEventLogger
import com.zhizhi.app.data.SystemEventClock
import com.zhizhi.app.manager.AlarmProtectionEventBridge
import com.zhizhi.app.manager.AlarmScheduler









class AlarmWatchdogService : Service() {

    // Event-driven logging bridge (task-6). The watchdog has no authority over the
    // bridge decision — it only records the completed recovery action. The idle
    // heartbeat itself emits no event and the verbose `Log.d` print is now gated
    // behind `protectionVerbose` so default logcat/export stay silent.
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

    companion object {
        private const val TAG = "AlarmWatchdog"
        private const val CHANNEL_ID = "alarm_watchdog_channel"
        private const val NOTIFICATION_ID = 9999
        private const val CHECK_INTERVAL_MS = 10_000L

        const val EXTRA_ALARM_ID = "alarm_id"
        const val EXTRA_TRIGGER_AT = "trigger_at"

        fun start(context: Context, alarmId: Int, triggerAt: Long) {
            val intent = Intent(context, AlarmWatchdogService::class.java).apply {
                putExtra(EXTRA_ALARM_ID, alarmId)
                putExtra(EXTRA_TRIGGER_AT, triggerAt)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, AlarmWatchdogService::class.java))
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private var alarmId = -1
    private var triggerAt = 0L
    private var hasTriggered = false

    private val checkRunnable = object : Runnable {
        override fun run() {
            if (hasTriggered) return

            val now = System.currentTimeMillis()
            val remaining = triggerAt - now

            if (remaining <= 0) {
                triggerAlarm()
                return
            }

            // Task-6: idle heartbeat must not appear in default export/logcat. Only the
            // optional verbose flag keeps the loop observable in ad-hoc debugging. The
            // bridge is intentionally silent for repeated stable watchdog checks: it
            // emits only on actual recovery (triggerAlarm()).
            if (DefaultLogDebugConfig.protectionVerbose) {
                Log.d(TAG, "Watchdog check: alarmId=$alarmId, remaining=${remaining}ms")
            }
            handler.postDelayed(this, CHECK_INTERVAL_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        alarmId = intent?.getIntExtra(EXTRA_ALARM_ID, -1) ?: -1
        triggerAt = intent?.getLongExtra(EXTRA_TRIGGER_AT, 0L) ?: 0L

        if (alarmId <= 0 || triggerAt <= 0) {
            Log.w(TAG, "Invalid params: alarmId=$alarmId, triggerAt=$triggerAt")
            stopSelf()
            return START_NOT_STICKY
        }

        val activeId = AlarmScheduler.getActiveAlarmId(this)
        if (activeId == alarmId) {
            Log.i(TAG, "Alarm $alarmId already active, stopping watchdog")
            stopSelf()
            return START_NOT_STICKY
        }

        val notification = createNotification()
        startForeground(NOTIFICATION_ID, notification)

        val triggerDate = java.text.SimpleDateFormat("HH:mm:ss").format(java.util.Date(triggerAt))
        Log.i(TAG, "Watchdog started: alarmId=$alarmId, triggerAt=$triggerDate")

        hasTriggered = false
        handler.removeCallbacks(checkRunnable)
        handler.post(checkRunnable)

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        handler.removeCallbacks(checkRunnable)
        super.onDestroy()
    }

    private fun triggerAlarm() {
        hasTriggered = true
        Log.w(TAG, "Watchdog triggered alarm $alarmId manually!")
        // Event-driven logging: actual watchdog recovery action — one ALARM_WATCHDOG + one
        // ALARM_RECOVERY draft.
        alarmProtectionEventBridge.recordAlarmWatchdog(action = "MANUAL_RECOVERY")
        alarmProtectionEventBridge.recordAlarmRecovery(
            alarmId = alarmId.toString(),
            recoveryAction = "WATCHDOG_MANUAL"
        )
        AlarmScheduler.triggerAlarmFromWorkManager(this, alarmId)
        stopSelf()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "闹钟守卫服务",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "确保闹钟准时响起"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("闹钟守卫运行中")
                .setContentText("正在监控闹钟触发时间")
                .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                .build()
        } else {
            Notification.Builder(this)
                .setContentTitle("闹钟守卫运行中")
                .setContentText("正在监控闹钟触发时间")
                .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                .build()
        }
    }
}
