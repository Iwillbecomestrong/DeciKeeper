/**
 * =================================================================================
 * [Context Header]
 * @file: AlarmVibrationService.kt
 * @role: Domain Skill
 * @description: Foreground service managing alarm vibration independently of UI lifecycle.
 * @responsibilities:
 * 1. 独立管理闹钟震动，不受 Activity/Overlay 生命周期影响。
 * 2. 熄屏后自动重新触发震动（解决 Vivo 等 OEM 熄屏切断震动问题）。
 * 3. 通过 WakeLock 保持 CPU 唤醒。
 * @context:
 * - 上游: LockWindowManager (start/stop)
 * - 下游: Vibrator, PowerManager
 * =================================================================================
 */
package com.zhizhi.app.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import androidx.core.app.NotificationCompat
import com.zhizhi.app.data.AndroidLogcatNormalEventSink
import com.zhizhi.app.data.DefaultLogDebugConfig
import com.zhizhi.app.data.DefaultNormalEventRenderer
import com.zhizhi.app.data.LogExporter
import com.zhizhi.app.data.NormalEventLogger
import com.zhizhi.app.data.SystemEventClock
import com.zhizhi.app.manager.AlarmAlertPolicy
import com.zhizhi.app.manager.AlarmProtectionEventBridge
import com.zhizhi.app.manager.AlarmScheduler
import com.zhizhi.app.manager.StageAlertPolicy
import com.zhizhi.app.ui.AlarmActivity
import com.zhizhi.app.ui.LockAlarmOverlayController
import com.zhizhi.app.ui.LockWindowManager











class AlarmVibrationService : Service() {

    companion object {
        private const val TAG = "AlarmVibrationService"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "alarm_channel_v3"
        internal const val OVERLAY_RESTORE_DELAY_MS = 300L
        const val EXTRA_ALARM_ID = "alarm_vibration_alarm_id"
    const val EXTRA_PRE_WARM = "alarm_vibration_pre_warm"

        /** 当前有效震动强度（1-10），供 UI 层读取显示 */
        @Volatile
        var currentDisplayIntensity: Int = 0
        @Volatile
        var currentDisplayVolume: Int = 0

        /** 当前震动模式，供 UI 层读取显示 */
        @Volatile
        var currentVibrationMode: String = "fixed"
        @Volatile
        var isRunning = false

        /** 当前铃声模式，供 UI 层读取显示（VIBRATE_AND_RING / VIBRATE_ONLY / RING_ONLY） */
        @Volatile
        var currentAlarmMode: String = "VIBRATE_AND_RING"


        internal fun shouldRestoreOverlayForAction(action: String?): Boolean {
            return action == Intent.ACTION_SCREEN_ON || action == Intent.ACTION_USER_PRESENT
        }

        @JvmStatic
        fun start(context: Context, alarmId: Int) {
            val intent = Intent(context, AlarmVibrationService::class.java).apply {
                putExtra(EXTRA_ALARM_ID, alarmId)
            }
            context.startForegroundService(intent)
        }

        @JvmStatic
        fun startPreWarm(context: Context, alarmId: Int) {
            val intent = Intent(context, AlarmVibrationService::class.java).apply {
                putExtra(EXTRA_ALARM_ID, alarmId)
                putExtra(EXTRA_PRE_WARM, true)
            }
            context.startForegroundService(intent)
        }

        const val ACTION_ATTENUATE = "com.zhizhi.app.service.ACTION_ATTENUATE"
        const val ACTION_SNOOZE_SILENCE = "com.zhizhi.app.service.ACTION_SNOOZE_SILENCE"
        const val ACTION_START_STAGE2_CYCLE = "com.zhizhi.app.service.ACTION_START_STAGE2_CYCLE"
        private const val EXTRA_SNOOZE_DURATION_MS = "alarm_snooze_duration_ms"

        @JvmStatic
        fun startStage2AlertCycle(context: Context, alarmId: Int) {
            if (alarmId <= 0) return
            try {
                val intent = Intent(context, AlarmVibrationService::class.java).apply {
                    action = ACTION_START_STAGE2_CYCLE
                    putExtra(EXTRA_ALARM_ID, alarmId)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
                Log.i(TAG, "Sent ACTION_START_STAGE2_CYCLE to AlarmVibrationService for alarmId=$alarmId")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start stage 2 alert cycle: ${e.message}")
            }
        }

        @JvmStatic
        fun silenceForSnooze(context: Context, alarmId: Int, durationMs: Long) {
            if (alarmId <= 0 || durationMs <= 0L) return
            try {
                val intent = Intent(context, AlarmVibrationService::class.java).apply {
                    action = ACTION_SNOOZE_SILENCE
                    putExtra(EXTRA_ALARM_ID, alarmId)
                    putExtra(EXTRA_SNOOZE_DURATION_MS, durationMs)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent)
                else context.startService(intent)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to silence alarm for snooze: ${e.message}")
            }
        }

        @JvmStatic
        fun attenuate(context: Context) {
            try {
                val intent = Intent(context, AlarmVibrationService::class.java).apply {
                    action = ACTION_ATTENUATE
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
                Log.i(TAG, "Sent ACTION_ATTENUATE to AlarmVibrationService")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to attenuate alarm vibration service: ${e.message}")
            }
        }

        @JvmStatic
        fun stop(context: Context) {
            context.stopService(Intent(context, AlarmVibrationService::class.java))
        }
    }

    private var vibrator: Vibrator? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val handler = Handler(Looper.getMainLooper())
    private var alarmId: Int = -1
    private var ringtoneUri: String? = null
    private var alarmMode: AlarmScheduler.AlarmMode = AlarmScheduler.AlarmMode.VIBRATE_AND_RING
    private var ringVolume: Int = 7
    var alertCycleStartTime: Long = 0L
    private var serviceStartTime: Long = 0L
    private var activeChallengeStage: Int = 1
    private var fixedAmplitude = 0
    private var lockedFinalIntensity = 0
    // 铃声播放（使用 MediaPlayer + USAGE_MEDIA，音量完全由 app 控制）
    private var mediaPlayer: android.media.MediaPlayer? = null
    private var isAttenuated: Boolean = false
    @Volatile private var isSnoozeSilenced: Boolean = false
    private var snoozeRestoreRunnable: Runnable? = null

    private fun applyAttenuation() {
        isAttenuated = true
        currentDisplayVolume = 1
        currentDisplayIntensity = 1
        currentVibrationMode = "attenuated"
        val mpVol = 0.02f
        mediaPlayer?.setVolume(mpVol, mpVol)

        if (alarmMode != AlarmScheduler.AlarmMode.RING_ONLY) {
            if (vibrator == null) {
                vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }
            vibrator?.cancel()
            val audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .build()
            val pattern = longArrayOf(0, 300, 2700)
            val amplitudes = intArrayOf(0, 20, 0)
            val effect = VibrationEffect.createWaveform(pattern, amplitudes, 0)
            vibrator?.vibrate(effect, audioAttributes)
        }
        Log.i(TAG, "Alarm attenuated: volume=0.02f, vibration=mild 20/255 pulse")
    }

    private fun applySnoozeSilence(targetAlarmId: Int, durationMs: Long) {
        if (targetAlarmId <= 0 || targetAlarmId != alarmId || durationMs <= 0L) return
        snoozeRestoreRunnable?.let { handler.removeCallbacks(it) }
        isSnoozeSilenced = true
        currentDisplayVolume = 0
        currentDisplayIntensity = 0
        currentVibrationMode = "snooze_silent"
        mediaPlayer?.setVolume(0f, 0f)
        vibrator?.cancel()

        snoozeRestoreRunnable = Runnable {
            snoozeRestoreRunnable = null
            val sameSession = AlarmScheduler.isAlarmActive(this) && AlarmScheduler.getActiveAlarmId(this) == targetAlarmId
            val stillStageOne = !AlarmScheduler.isFirstStagePassed(this)
            if (isSnoozeSilenced && sameSession && stillStageOne) {
                isSnoozeSilenced = false
                Log.i(TAG, "Silent snooze expired; restoring alarm output for id=$targetAlarmId")
                resetAlertCycle()
                startVibrationImpl()
            } else {
                isSnoozeSilenced = false
                Log.i(TAG, "Silent snooze restore skipped: sameSession=$sameSession stillStageOne=$stillStageOne")
            }
        }
        handler.postDelayed(snoozeRestoreRunnable!!, durationMs)
        Log.i(TAG, "Alarm output silenced for snooze: id=$targetAlarmId durationMs=$durationMs challengePreserved=true")
    }

    // Event-driven logging bridge (task-6). 震动服务对桥决策无权威——
    // 仅在铃声"实际开始/停止播放"这个已完成事实发生时各记录一次事件。
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
    /** 铃声实际开始播放的时间戳；0 表示当前会话未响铃。用于 RING_END 的 durationMs 与防重复发射 */
    private var ringStartedAtMs: Long = 0L

    // [音量锁定] 播放铃声时锁定 STREAM_MUSIC 到最大值，
    // 用户按音量键后立刻被还原，让音量键对闹钟铃声"失效"。
    // app 内 ringVolume (1-10) → setVolume(0.1~1.0) 成为唯一音量控制。
    private var volumeLocked = false

    private val screenOffReceiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    // AlarmActivity keeps the screen on while the challenge is active, so a SCREEN_OFF here
                    // is our best app-visible signal for a short power-key press. Use the same one-shot snooze
                    // path as the UI/volume keys. If it cannot be consumed, keep the original OEM recovery.
                    val snoozed = LockAlarmOverlayController.requestSnoozeFromHardware("POWER_KEY")
                    if (snoozed) {
                        Log.i(TAG, "Screen off consumed as one-shot silent snooze")
                    } else {
                        Log.d(TAG, "Screen off detected, scheduling vibration retries")
                        handler.postDelayed({ startVibrationImpl() }, 300)
                        handler.postDelayed({ startVibrationImpl() }, 800)
                        handler.postDelayed({ startVibrationImpl() }, 1500)
                    }
                }
                Intent.ACTION_SCREEN_ON, Intent.ACTION_USER_PRESENT -> {
                    if (alarmId >= 0) {
                        Log.d(TAG, "${intent.action} detected, restoring alarm overlay in ${OVERLAY_RESTORE_DELAY_MS}ms")
                        handler.postDelayed({
                            LockWindowManager.showAlarmOverlayIfHidden()
                        }, OVERLAY_RESTORE_DELAY_MS)
                    }
                }
            }
        }
    }

    internal fun resetAlertCycle(now: Long = System.currentTimeMillis()) {
        alertCycleStartTime = now
        AlarmScheduler.saveVibrationStartTime(this, now)
        fixedAmplitude = 0
        lockedFinalIntensity = 0
    }

    private fun resolveCurrentStagePolicy(): StageAlertPolicy {
        val entry = if (alarmId > 0) AlarmScheduler.findAlarm(this, alarmId) else null
        val stage1 = StageAlertPolicy(
            alarmMode = LockWindowManager.testAlarmMode ?: entry?.alarmMode ?: AlarmScheduler.getDefaultAlarmMode(this),
            vibrationMode = LockWindowManager.testVibrationMode ?: entry?.vibrationMode ?: AlarmScheduler.VibrationMode.PROGRESSIVE,
            fixedVibrationIntensity = LockWindowManager.testVibrationIntensity ?: entry?.vibrationIntensity ?: AlarmScheduler.getDefaultVibrationIntensity(this),
            fixedRingVolume = LockWindowManager.testRingVolume ?: entry?.ringVolume ?: AlarmScheduler.getDefaultRingVolume(this)
        )
        if (activeChallengeStage <= 1 && !AlarmScheduler.isFirstStagePassed(this)) {
            return stage1
        }
        val stage2 = if (entry != null) {
            StageAlertPolicy(
                alarmMode = entry.stage2AlarmMode,
                vibrationMode = entry.stage2VibrationMode,
                fixedVibrationIntensity = entry.stage2VibrationIntensity,
                fixedRingVolume = entry.stage2RingVolume
            )
        } else null
        val useIndependent = entry?.stage2UseIndependentAlertPolicy ?: false
        return AlarmAlertPolicy.resolveStagePolicy(stage1, useIndependent, stage2)
    }

    // 周期性地重新触发震动，防止系统闹钟或其他应用取消震动后 IronWill 震动停止
    // 不设超时——闹钟持续响铃直到用户完成挑战（扫码关闭）
    private val vibrationKeepAliveRunnable = object : Runnable {
        override fun run() {
            if (!isSnoozeSilenced && vibrator != null) {
                Log.d(TAG, "Vibration keep-alive: re-triggering")
                startVibrationImpl()
            }
            handler.postDelayed(this, 3000)
        }
    }

    /** 铃声音量显示更新定时器，每秒刷新一次，保证渐进音量显示平滑 */
    private val ringDisplayUpdateRunnable = object : Runnable {
        override fun run() {
            if (!isRunning) return
            val currentPolicy = resolveCurrentStagePolicy()
            val shouldRing = currentPolicy.alarmMode != AlarmScheduler.AlarmMode.VIBRATE_ONLY
            if (shouldRing) {
                val elapsedMs = (System.currentTimeMillis() - alertCycleStartTime).coerceAtLeast(0L)
                updateRingVolumeDisplay(currentPolicy, elapsedMs)
            }
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate() {
        super.onCreate()
        // 从 SharedPreferences 恢复 alertCycleStartTime
        val savedStartTime = AlarmScheduler.getVibrationStartTime(this)
        alertCycleStartTime = if (savedStartTime > 0) savedStartTime else System.currentTimeMillis()
        if (savedStartTime == 0L) {
            AlarmScheduler.saveVibrationStartTime(this, alertCycleStartTime)
        }
        serviceStartTime = alertCycleStartTime
        // 恢复锁定强度（第一关通过后的半强度值）
        lockedFinalIntensity = AlarmScheduler.getVibrationLockedIntensity(this)
        try {
            createNotificationChannel()
            startForeground(NOTIFICATION_ID, buildNotification())
        } catch (e: Exception) {
            // Direct Boot 模式下通知可能不可用，不影响核心震动功能
            Log.w(TAG, "Failed to start foreground, but vibration will continue: ${e.message}")
        }
        try {
            registerReceiver(
                screenOffReceiver,
                IntentFilter().apply {
                    addAction(Intent.ACTION_SCREEN_OFF)
                    addAction(Intent.ACTION_SCREEN_ON)
                    addAction(Intent.ACTION_USER_PRESENT)
                }
            )
        } catch (e: Exception) {
            Log.w("AlarmVibrationService", "Failed to register screen state receiver", e)
        }
        isRunning = true
        Log.d(TAG, "Service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_START_STAGE2_CYCLE) {
            val targetAlarmId = intent.getIntExtra(EXTRA_ALARM_ID, -1)
            if (targetAlarmId > 0) this.alarmId = targetAlarmId
            activeChallengeStage = 2
            isAttenuated = false
            isSnoozeSilenced = false
            resetAlertCycle()
            startVibrationImpl()
            return START_STICKY
        }
        if (intent?.action == ACTION_SNOOZE_SILENCE) {
            val targetAlarmId = intent.getIntExtra(EXTRA_ALARM_ID, -1)
            val durationMs = intent.getLongExtra(EXTRA_SNOOZE_DURATION_MS, 0L)
            applySnoozeSilence(targetAlarmId, durationMs)
            return START_STICKY
        }
        if (intent?.action == ACTION_ATTENUATE) {
            Log.i(TAG, "Received ACTION_ATTENUATE in onStartCommand")
            snoozeRestoreRunnable?.let { handler.removeCallbacks(it) }
            snoozeRestoreRunnable = null
            isSnoozeSilenced = false
            applyAttenuation()
            return START_STICKY
        }
        val newAlarmId = intent?.getIntExtra(EXTRA_ALARM_ID, -1) ?: -1

        // 读取铃声设置
        val entry = if (newAlarmId > 0) AlarmScheduler.findAlarm(this, newAlarmId) else null
        ringtoneUri = LockWindowManager.testRingtoneUri ?: entry?.ringtoneUri ?: AlarmScheduler.getDefaultRingtoneUri(this)
        alarmMode = LockWindowManager.testAlarmMode ?: entry?.alarmMode ?: AlarmScheduler.getDefaultAlarmMode(this)
        ringVolume = LockWindowManager.testRingVolume ?: entry?.ringVolume ?: AlarmScheduler.getDefaultRingVolume(this)
        currentAlarmMode = alarmMode.name
        Log.d(TAG, "[RING-DIAG] onStartCommand: newAlarmId=$newAlarmId, alarmMode=$alarmMode, ringtoneUri=$ringtoneUri, ringVolume=$ringVolume, testMode=${LockWindowManager.testAlarmMode}, testUri=${LockWindowManager.testRingtoneUri}")

        // [Pre-Warm] 预热水模式：只保活不震动。前台服务 + WakeLock 防止 Vivo 杀进程，
        // 确保主闹钟准时到达。主闹钟到达后再次调用 start() 时进入正常震动。
        // 🛑 [Fix: 提前响] PreWarm 不能复用 updateForegroundNotification():它带 setFullScreenIntent + PRIORITY_MAX,
        // Vivo 会把它当闹钟类通知,提前 3 分钟就开始响+震动。改用极简通知(无 fullScreenIntent, PRIORITY_MIN)。
        if (intent?.getBooleanExtra(EXTRA_PRE_WARM, false) == true) {
            if (vibrator == null) {
                this.alarmId = newAlarmId
                acquireWakeLock()
                startForegroundWithMinimalNotification()
                LogExporter.i("Alarm", "🔥 预热保活 | id=$newAlarmId | mode=MIN (无 fullScreenIntent, 避免 Vivo 提前响)")
            }
            return START_STICKY
        }
        // [防御性代码] 如果服务已在运行且正在震动，只需更新 alarmId，
        // 避免重复创建前台服务通知被系统识别为异常频繁启动。
        if (vibrator != null && this.alarmId >= 0) {
            Log.i(TAG, "Service already running, updating alarmId: ${this.alarmId} -> $newAlarmId")
            this.alarmId = newAlarmId
            AlarmScheduler.setAlarmActive(this, alarmId)
            updateForegroundNotification()
            // [Fix: scan-screen-off] 服务已运行时仍需重新触发震动。
            // 扫码模式下熄屏后系统可能取消震动，亮屏时 AlarmActivity.onResume()
            // 再次调用 start()，此时 vibrator 对象还在但震动已停止，必须显式重启。
            startVibrationImpl()
            // 重新调度保活循环
            handler.removeCallbacks(vibrationKeepAliveRunnable)
            handler.postDelayed(vibrationKeepAliveRunnable, 5000)
            return START_STICKY
        }

        this.alarmId = newAlarmId
        AlarmScheduler.setAlarmActive(this, alarmId)
        updateForegroundNotification()
        fixedAmplitude = 0
        // [Fix: process restart] 不从 SharedPreferences 恢复的 lockedFinalIntensity，
        // 已在 onCreate() 中恢复。此处不清零以维持进程重启后的正确锁定强度。
        Log.i(TAG, "Vibration started, setAlarmActive(alarmId=$alarmId, lockedIntensity=$lockedFinalIntensity)")
        startVibrationImpl()
        // 启动震动保活循环，防止系统闹钟取消震动后 IronWill 震动停止
        handler.removeCallbacks(vibrationKeepAliveRunnable)
        handler.postDelayed(vibrationKeepAliveRunnable, 5000)
        // 启动铃声音量显示更新（1秒刷新，保证渐进音量显示平滑）
        handler.removeCallbacks(ringDisplayUpdateRunnable)
        handler.postDelayed(ringDisplayUpdateRunnable, 1000)
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(vibrationKeepAliveRunnable)
        handler.removeCallbacks(ringDisplayUpdateRunnable)
        snoozeRestoreRunnable?.let { handler.removeCallbacks(it) }
        snoozeRestoreRunnable = null
        val activeAlarmId = AlarmScheduler.getActiveAlarmId(this)
        Log.i(
            TAG,
            "Vibration service destroying: serviceAlarmId=$alarmId, activeAlarmId=$activeAlarmId; business session state unchanged"
        )
        LogExporter.logEvent(
            "AlarmRecovery",
            "VIBRATION_SERVICE_DESTROY serviceAlarmId=$alarmId activeAlarmId=$activeAlarmId preserveSession=true"
        )
        stopRingtone()
        stopVibration()
        try {
            unregisterReceiver(screenOffReceiver)
        } catch (_: Exception) {
        }
        isRunning = false
        Log.d(TAG, "Service destroyed")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    @SuppressLint("MissingPermission")
    private fun startVibrationImpl() {
        if (isSnoozeSilenced) {
            currentDisplayVolume = 0
            currentDisplayIntensity = 0
            mediaPlayer?.setVolume(0f, 0f)
            vibrator?.cancel()
            return
        }
        if (isAttenuated) {
            currentDisplayIntensity = 1
            currentDisplayVolume = 1
            val mpVol = 0.02f
            mediaPlayer?.setVolume(mpVol, mpVol)
            if (alarmMode != AlarmScheduler.AlarmMode.RING_ONLY) {
                if (vibrator == null) {
                    vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                }
                val audioAttributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .build()
                val pattern = longArrayOf(0, 300, 2700)
                val amplitudes = intArrayOf(0, 20, 0)
                val effect = VibrationEffect.createWaveform(pattern, amplitudes, 0)
                vibrator?.vibrate(effect, audioAttributes)
            }
            return
        }

        val elapsedMs = (System.currentTimeMillis() - alertCycleStartTime).coerceAtLeast(0L)
        val currentPolicy = resolveCurrentStagePolicy()
        alarmMode = currentPolicy.alarmMode
        currentAlarmMode = alarmMode.name

        // --- 铃声（RING_ONLY 和 VIBRATE_AND_RING）---
        val shouldRing = currentPolicy.alarmMode != AlarmScheduler.AlarmMode.VIBRATE_ONLY
        Log.d(TAG, "[RING-DIAG] startVibrationImpl: alarmMode=$alarmMode, shouldRing=$shouldRing, ringtoneUri=$ringtoneUri")
        if (shouldRing) {
            startRingtone(currentPolicy, elapsedMs)
        }

        // --- 震动（VIBRATE_ONLY 和 VIBRATE_AND_RING；RING_ONLY 跳过）---
        if (currentPolicy.alarmMode == AlarmScheduler.AlarmMode.RING_ONLY) return
        if (vibrator == null) {
            vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
        acquireWakeLock()

        val finalIntensity = AlarmAlertPolicy.vibrationLevel(currentPolicy, elapsedMs)
        val amplitude = (finalIntensity * 25.5).toInt().coerceIn(1, 255)
        val stableAmplitude = amplitude

        val audioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .build()

        val pattern = longArrayOf(0, 1000, 1000)
        val amplitudes = intArrayOf(0, stableAmplitude, 0)
        val effect = VibrationEffect.createWaveform(pattern, amplitudes, 0)
        vibrator?.vibrate(effect, audioAttributes)

        // 更新震动显示值供 UI 层读取
        currentDisplayIntensity = finalIntensity
        currentVibrationMode = if (currentPolicy.vibrationMode == AlarmScheduler.VibrationMode.PROGRESSIVE) "progressive" else "fixed"

        Log.d(TAG, "Alarm vibration started/restarted (policy=$currentPolicy, intensity=$finalIntensity, amplitude=$stableAmplitude)")
    }

    /** 铃声音量等级(1-10) → MediaPlayer浮点音量，使用三次方曲线拉开低强度差距 */
    private fun ringVolumeToFloat(level: Int): Float {
        val v = level.coerceIn(1, 10) / 10f
        return v * v * v  // 三次方：1→0.001, 2→0.008, 5→0.125, 7→0.343, 10→1.0
    }

    /** 更新铃声强度显示值 + 实际 MediaPlayer 音量（RING_ONLY 和 VIBRATE_AND_RING 都需要） */
    private fun updateRingVolumeDisplay(policy: StageAlertPolicy, elapsedMs: Long) {
        if (isSnoozeSilenced) {
            currentDisplayVolume = 0
            mediaPlayer?.setVolume(0f, 0f)
            return
        }
        if (isAttenuated) {
            currentDisplayVolume = 1
            val mpVol = 0.02f
            mediaPlayer?.setVolume(mpVol, mpVol)
            return
        }
        val volume = AlarmAlertPolicy.ringLevel(policy, elapsedMs)
        currentDisplayVolume = volume.coerceIn(1, 10)
        val mpVol = ringVolumeToFloat(currentDisplayVolume)
        mediaPlayer?.setVolume(mpVol, mpVol)
        Log.d(TAG, "[RING_VOL] display update: policy=$policy, elapsedMs=$elapsedMs, currentDisplayVolume=$currentDisplayVolume, mpVol=$mpVol")
    }

    /**
     * 启动铃声播放。使用 MediaPlayer + USAGE_MEDIA（STREAM_MUSIC），
     * 与系统闹钟音量完全隔离。音量由 ringVolume (1-10) 通过 setVolume() 直接控制，
     * 系统音量键只影响媒体音量，不影响 app 闹钟音量。
     */
    @SuppressLint("MissingPermission")
    private fun startRingtone(policy: StageAlertPolicy, elapsedMs: Long) {
        val alreadyPlaying = mediaPlayer?.isPlaying == true
        Log.d(TAG, "[RING-DIAG] startRingtone called, ringtoneUri=$ringtoneUri, alarmMode=$alarmMode, alreadyPlaying=$alreadyPlaying, mediaPlayerObj=${mediaPlayer != null}")

        // 已经在播放 → 音量由 updateRingVolumeDisplay() 每秒渐进控制，不在此处覆盖
        if (alreadyPlaying) {
            Log.d(TAG, "[RING-DIAG] MediaPlayer already playing, volume managed by ringDisplayUpdateRunnable")
            return
        }

        // 解析铃声 URI（优先用户自选，fallback 到系统默认闹钟铃声）
        val isCustom = !ringtoneUri.isNullOrBlank()
        val uri = if (isCustom) {
            Log.d(TAG, "[RING-DIAG] Using CUSTOM ringtone: $ringtoneUri")
            android.net.Uri.parse(ringtoneUri)
        } else {
            val defaultUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            Log.d(TAG, "[RING-DIAG] No custom ringtone set, using SYSTEM DEFAULT: $defaultUri")
            defaultUri
        }
        if (uri == null) {
            Log.w(TAG, "[RING-DIAG] No ringtone URI available, skipping")
            return
        }

        // 停止并释放旧的 MediaPlayer（如果存在）
        releaseMediaPlayer()

        try {
            val initialVolume = AlarmAlertPolicy.ringLevel(policy, elapsedMs)
            val vol = ringVolumeToFloat(initialVolume)
            mediaPlayer = MediaPlayer().apply {
                setDataSource(this@AlarmVibrationService, uri)
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                isLooping = true
                setVolume(vol, vol)
                prepare()
                start()
            }
            Log.d(TAG, "[RING-DIAG] MediaPlayer created and started, initialVolume=$initialVolume, vol=$vol, uri=$uri")
            startVolumeLock()
            // 铃声实际开始播放: 发射一次 ALARM_RING_START。startRingtone() 在
            // alreadyPlaying 时提前返回, 所以同一播放会话内不会重复发射;
            // AlarmReceiver 触发时的 RING_START 是"闹钟触发了"这一事实,
            // 此处是"铃声真的响了"这一事实, 两者语义不同, 均为契约要求。
            if (ringStartedAtMs == 0L) {
                ringStartedAtMs = System.currentTimeMillis()
                alarmProtectionEventBridge.recordAlarmRingStart(alarmId = alarmId.toString())
            }
        } catch (e: Exception) {
            Log.e(TAG, "[RING-DIAG] Failed to start MediaPlayer: ${e.message}", e)
            releaseMediaPlayer()
        }
    }

    /** 安全释放 MediaPlayer 资源 */
    private fun releaseMediaPlayer() {
        mediaPlayer?.let { mp ->
            try {
                if (mp.isPlaying) mp.stop()
            } catch (_: Exception) {}
            try {
                mp.reset()
            } catch (_: Exception) {}
            try {
                mp.release()
            } catch (_: Exception) {}
        }
        mediaPlayer = null
    }

    /**
     * 停止铃声并释放 MediaPlayer 资源。
     */
    private fun stopRingtone() {
        Log.d(TAG, "[RING-DIAG] stopRingtone called, wasPlaying=${mediaPlayer?.isPlaying}")
        // 铃声物理停止: 发射一次 ALARM_RING_END。reason 采用服务视角的分类
        // (服务停止), 用户动作层面的 DISMISS/SNOOZE/扫码完成已由锁屏覆盖层簇
        // 单独发射 ALARM_DISMISS/ALARM_SNOOZE, 此处不做跨组件推断。
        if (ringStartedAtMs > 0L) {
            alarmProtectionEventBridge.recordAlarmRingEnd(
                alarmId = alarmId.toString(),
                reason = "SERVICE_STOPPED",
                durationMs = (System.currentTimeMillis() - ringStartedAtMs).toString()
            )
            ringStartedAtMs = 0L
        }
        releaseMediaPlayer()
        stopVolumeLock()
    }

    /**
     * 锁定 STREAM_MUSIC 到最大值，让音量键对闹钟铃声"失效"。
     * 每 200ms 检测一次，用户按音量键后立刻被还原，
     * MediaPlayer.setVolume() 成为唯一音量控制。
     */
    private var volumeLockRunnable: Runnable? = null
    private var volumeLockMaxVol: Int = 0

    private fun startVolumeLock() {
        if (volumeLocked) return
        val audioMgr = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val maxVol = audioMgr.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        // 锁定到80%而非100%，给MediaPlayer.setVolume()留出足够的动态范围
        // 否则在最大系统音量上，即使0.008的MediaPlayer音量通过扬声器出来仍然很大声
        volumeLockMaxVol = (maxVol * 0.8f).toInt().coerceAtLeast(1)
        audioMgr.setStreamVolume(AudioManager.STREAM_MUSIC, volumeLockMaxVol, 0)
        volumeLocked = true

        volumeLockRunnable = object : Runnable {
            override fun run() {
                if (!volumeLocked) return
                val cur = audioMgr.getStreamVolume(AudioManager.STREAM_MUSIC)
                if (cur != volumeLockMaxVol) {
                    Log.d(TAG, "[VOL-LOCK] volume changed $cur → $volumeLockMaxVol, restoring")
                    audioMgr.setStreamVolume(AudioManager.STREAM_MUSIC, volumeLockMaxVol, 0)
                }
                handler.postDelayed(this, 200)
            }
        }
        handler.postDelayed(volumeLockRunnable!!, 200)
        Log.d(TAG, "[VOL-LOCK] STREAM_MUSIC locked to 80% ($volumeLockMaxVol / $maxVol)")
    }

    private fun stopVolumeLock() {
        if (!volumeLocked) return
        volumeLocked = false
        volumeLockRunnable?.let { handler.removeCallbacks(it) }
        volumeLockRunnable = null
        Log.d(TAG, "[VOL-LOCK] released")
    }

    private fun stopVibration() {
        vibrator?.cancel()
        vibrator = null
        releaseWakeLock()
    }

    @Suppress("DEPRECATION")
    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP or PowerManager.ON_AFTER_RELEASE,
            "IronWill:AlarmVibrationService"
        ).apply {
            setReferenceCounted(false)
            acquire(30 * 60 * 1000L) // 30 minutes max
        }
    }

    private fun releaseWakeLock() {
        try {
            wakeLock?.let {
                if (it.isHeld) it.release()
            }
        } catch (_: Exception) {
        } finally {
            wakeLock = null
        }
    }

    private fun createNotificationChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            "闹钟",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "闹钟触发时显示挑战界面"
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            enableVibration(false)
            // 铃声由 MediaPlayer 独立控制，通知 channel 永远不设声音
            setSound(null, null)
        }
        nm.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val intent = Intent(this, AlarmActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra(AlarmScheduler.EXTRA_ALARM_ID, alarmId)
            putExtra("is_locked_screen", true)
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        val fullScreenIntent = PendingIntent.getActivity(
            this,
            4100 + alarmId.coerceAtLeast(0),
            intent,
            flags
        )
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("闹钟震动中")
            .setContentText("完成挑战后关闭")
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)  // 前台服务通知，不用 CATEGORY_ALARM 避免 Vivo 自动播放系统闹钟声
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(fullScreenIntent)
            .setFullScreenIntent(fullScreenIntent, true)
            .setSilent(true)  // 铃声由 MediaPlayer 独立控制，通知必须静默（Vivo 等 OEM 会忽略 setSound(null)）

        // 根据铃声模式设置 vibration（铃声由 MediaPlayer 独立控制，通知不设声音）
        when (alarmMode) {
            AlarmScheduler.AlarmMode.RING_ONLY -> {
                builder.setSound(null)
                builder.setVibrate(longArrayOf(0))
            }
            AlarmScheduler.AlarmMode.VIBRATE_ONLY -> {
                builder.setSound(null)
            }
            AlarmScheduler.AlarmMode.VIBRATE_AND_RING -> {
                builder.setSound(null)
            }
        }
        return builder.build()
    }

    private fun updateForegroundNotification() {
        try {
            createNotificationChannel()
            startForeground(NOTIFICATION_ID, buildNotification())
            LogExporter.logEvent("AlarmOverlay", "FULL_SCREEN_NOTIFICATION_UPDATED id=$alarmId")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to update full-screen notification: ${e.message}")
            LogExporter.logEvent("AlarmOverlay", "FULL_SCREEN_NOTIFICATION_FAILED id=$alarmId error=${e.javaClass.simpleName}:${e.message}")
        }
    }

    /**
     * 🛑 [Fix: 提前响] PreWarm 用的极简前台服务通知。
     *
     * 与 buildNotification() 的关键差异:
     * - 不调用 setFullScreenIntent:Vivo 把带此 flag 的高优先级通知当闹钟,提前响+震动
     * - PRIORITY_MIN:不会弹横幅、不会进头部
     * - 无 setCategory:避免任何 OEM 触发"闹钟类通知"特殊行为
     *
     * PreWarm 的唯一目的是保活,不需要任何 UI 暴露给用户。
     */
    private fun buildMinimalKeepAliveNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("知止")
            .setContentText("闹钟预热中...")
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setSilent(true)
            .build()
    }

    private fun startForegroundWithMinimalNotification() {
        try {
            createNotificationChannel()
            // 用 NOTIFICATION_ID 同一个槽位:主闹钟到达时 updateForegroundNotification() 会覆盖它,
            // 不需要在主闹钟路径里再清旧通知。
            startForeground(NOTIFICATION_ID, buildMinimalKeepAliveNotification())
        } catch (e: Exception) {
            Log.w(TAG, "Failed to start foreground with minimal notification: ${e.message}")
        }
    }
}