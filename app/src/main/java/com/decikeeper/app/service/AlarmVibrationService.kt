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
package com.decikeeper.app.service

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
import android.os.UserManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import androidx.core.app.NotificationCompat
import com.decikeeper.app.data.AndroidLogcatNormalEventSink
import com.decikeeper.app.data.DefaultLogDebugConfig
import com.decikeeper.app.data.DefaultNormalEventRenderer
import com.decikeeper.app.data.LogExporter
import com.decikeeper.app.data.NormalEventLogger
import com.decikeeper.app.data.SystemEventClock
import com.decikeeper.app.manager.AlarmAlertPolicy
import com.decikeeper.app.manager.AlarmCallStatePolicy
import com.decikeeper.app.manager.AlarmProtectionEventBridge
import com.decikeeper.app.manager.AlarmScheduler
import com.decikeeper.app.manager.AlarmStreamVolumeLock
import com.decikeeper.app.manager.AudioManagerStreamVolumeAccess
import com.decikeeper.app.manager.StageAlertPolicy
import com.decikeeper.app.ui.AlarmActivity
import com.decikeeper.app.ui.LockAlarmOverlayController
import com.decikeeper.app.ui.LockWindowManager











class AlarmVibrationService : Service() {

    companion object {
        private const val TAG = "AlarmVibrationService"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "alarm_channel_v3"
        internal const val OVERLAY_RESTORE_DELAY_MS = 300L
        const val EXTRA_ALARM_ID = "alarm_vibration_alarm_id"
        const val EXTRA_PRE_WARM = "alarm_vibration_pre_warm"
        const val EXTRA_DIRECT_BOOT = "alarm_vibration_direct_boot"

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

        const val ACTION_ATTENUATE = "com.decikeeper.app.service.ACTION_ATTENUATE"
        const val ACTION_SNOOZE_SILENCE = "com.decikeeper.app.service.ACTION_SNOOZE_SILENCE"
        const val ACTION_START_STAGE2_CYCLE = "com.decikeeper.app.service.ACTION_START_STAGE2_CYCLE"
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
            if (alarmId < 0 || durationMs <= 0L) return
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
    private var directBootSession = false
    private var ringVolume: Int = 7
    var alertCycleStartTime: Long = 0L
    private var serviceStartTime: Long = 0L
    private var activeChallengeStage: Int = 1
    private var fixedAmplitude = 0
    private var lockedFinalIntensity = 0
    // 铃声播放（MediaPlayer + USAGE_ALARM，走闹钟专属音量通道，不占用与音乐/视频共用的媒体音量）
    private var mediaPlayer: android.media.MediaPlayer? = null
    private var isAttenuated: Boolean = false
    @Volatile private var isSnoozeSilenced: Boolean = false
    // 来电响铃 / 通话中：闹钟铃声与震动让路，通话结束后从当前阶段继续（不重置响铃周期）。
    @Volatile private var isCallPaused: Boolean = false
    private var snoozeRestoreRunnable: Runnable? = null

    private fun applyAttenuation() {
        isAttenuated = true
        currentDisplayVolume = 0
        currentDisplayIntensity = 0
        currentVibrationMode = "attenuated"
        mediaPlayer?.setVolume(0f, 0f)
        vibrator?.cancel()
        syncVolumeLock()
        Log.i(TAG, "Alarm attenuated: volume=0f (muted), vibration=stopped")
    }

    private fun applySnoozeSilence(targetAlarmId: Int, durationMs: Long) {
        if (targetAlarmId < 0 || targetAlarmId != alarmId || durationMs <= 0L) return
        snoozeRestoreRunnable?.let { handler.removeCallbacks(it) }
        isSnoozeSilenced = true
        currentDisplayVolume = 0
        currentDisplayIntensity = 0
        currentVibrationMode = "snooze_silent"
        mediaPlayer?.setVolume(0f, 0f)
        vibrator?.cancel()
        syncVolumeLock()

        snoozeRestoreRunnable = Runnable {
            snoozeRestoreRunnable = null
            val isTest = targetAlarmId <= 0
            val sameSession = if (isTest) {
                com.decikeeper.app.ui.LockWindowManager.isOverlayActive
            } else {
                AlarmScheduler.isAlarmActive(this) && AlarmScheduler.getActiveAlarmId(this) == targetAlarmId
            }
            val stillStageOne = !AlarmScheduler.isFirstStagePassed(this)
            if (isSnoozeSilenced && sameSession && stillStageOne) {
                isSnoozeSilenced = false
                Log.i(TAG, "Silent snooze expired; restoring alarm output for id=$targetAlarmId")
                resetAlertCycle()
                startVibrationImpl()
                syncVolumeLock()
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

    // [音量锁定] 铃声真正出声期间锁定「闹钟通道」音量，用户调小后立刻被还原，
    // 让系统音量键对闹钟铃声"失效"；静音/通话/结束时释放并还原用户原来的闹钟通道音量。
    // 绝不读写媒体音量：它与用户正在播放的音乐、视频共用。
    // app 内 ringVolume (1-10) → setVolume() 仍是闹钟响度的唯一调节手段。
    private var streamVolumeLock: AlarmStreamVolumeLock? = null

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
        if (directBootSession) {
            // Credential-protected alarm configuration is unavailable before first unlock.
            // Use a conservative device-independent vibration policy until USER_PRESENT
            // restores the normal alarm UI/configuration path.
            return StageAlertPolicy(
                alarmMode = AlarmScheduler.AlarmMode.VIBRATE_ONLY,
                vibrationMode = AlarmScheduler.VibrationMode.FIXED,
                fixedVibrationIntensity = 7,
                fixedRingVolume = 1
            )
        }
        val entry = if (alarmId > 0) AlarmScheduler.findAlarm(this, alarmId) else null
        val stage1 = StageAlertPolicy(
            alarmMode = LockWindowManager.testAlarmMode ?: entry?.alarmMode ?: AlarmScheduler.getDefaultAlarmMode(this),
            vibrationMode = LockWindowManager.testVibrationMode ?: entry?.vibrationMode ?: AlarmScheduler.VibrationMode.PROGRESSIVE,
            fixedVibrationIntensity = LockWindowManager.testVibrationIntensity ?: entry?.vibrationIntensity ?: AlarmScheduler.getDefaultVibrationIntensity(this),
            fixedRingVolume = LockWindowManager.testRingVolume ?: entry?.ringVolume ?: AlarmScheduler.getDefaultRingVolume(this),
            progressiveMaxRingVolume = LockWindowManager.testProgressiveMaxRingVolume
                ?: entry?.progressiveMaxRingVolume
                ?: AlarmScheduler.getDefaultProgressiveMaxRingVolume(this),
            progressiveMaxVibrationIntensity = LockWindowManager.testProgressiveMaxIntensity
                ?: entry?.progressiveMaxIntensity
                ?: AlarmScheduler.getDefaultProgressiveMaxIntensity(this)
        )
        if (activeChallengeStage <= 1 && !AlarmScheduler.isFirstStagePassed(this)) {
            return stage1
        }
        val stage2 = if (entry != null) {
            StageAlertPolicy(
                alarmMode = entry.stage2AlarmMode,
                vibrationMode = entry.stage2VibrationMode,
                fixedVibrationIntensity = entry.stage2VibrationIntensity,
                fixedRingVolume = entry.stage2RingVolume,
                progressiveMaxRingVolume = entry.stage2ProgressiveMaxRingVolume,
                progressiveMaxVibrationIntensity = entry.stage2ProgressiveMaxIntensity
            )
        } else LockWindowManager.testStage2AlertPolicy
        val useIndependent = entry?.stage2UseIndependentAlertPolicy ?: (stage2 != null)
        return AlarmAlertPolicy.resolveStagePolicy(stage1, useIndependent, stage2)
    }

    // 周期性地重新触发震动，防止系统闹钟或其他应用取消震动后 IronWill 震动停止
    // 不设超时——闹钟持续响铃直到用户完成挑战（扫码关闭）
    private val vibrationKeepAliveRunnable = object : Runnable {
        override fun run() {
            if (!isSnoozeSilenced && !isAttenuated && !isCallPaused && vibrator != null) {
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
        // 仅在已有处于活跃状态的闹钟会话（如进程被杀重启恢复）时，从 SharedPreferences 恢复 alertCycleStartTime
        val savedStartTime = if (AlarmScheduler.isAlarmActive(this)) {
            AlarmScheduler.getVibrationStartTime(this)
        } else {
            0L
        }
        alertCycleStartTime = if (savedStartTime > 0L) savedStartTime else 0L
        serviceStartTime = if (alertCycleStartTime > 0L) alertCycleStartTime else System.currentTimeMillis()
        // 恢复锁定强度（第一关通过后的半强度值）
        lockedFinalIntensity = AlarmScheduler.getVibrationLockedIntensity(this)
        // 恢复完成后延迟/第一关通过后的最低强度状态，避免服务或进程重启后重新高强度响铃。
        isAttenuated = AlarmScheduler.isAlarmAttenuated(this)
        try {
            createNotificationChannel()
            if (directBootSession) {
                startForegroundWithMinimalNotification()
            } else {
                startForeground(NOTIFICATION_ID, buildNotification())
            }
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
            AlarmScheduler.setAlarmAttenuated(this, false)
            isSnoozeSilenced = false
            resetAlertCycle()
            startVibrationImpl()
            syncVolumeLock()
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
            AlarmScheduler.setAlarmAttenuated(this, true)
            applyAttenuation()
            return START_STICKY
        }
        val requestedAlarmId = intent?.getIntExtra(EXTRA_ALARM_ID, -1) ?: -1
        val directBoot = intent?.getBooleanExtra(EXTRA_DIRECT_BOOT, false) == true ||
            isUserLockedForDirectBoot()
        val newAlarmId = if (requestedAlarmId > 0 || !directBoot) {
            requestedAlarmId
        } else {
            AlarmScheduler.getActiveAlarmId(this)
        }
        val directBootToken = intent?.getStringExtra(AlarmScheduler.EXTRA_SCHEDULE_TOKEN).orEmpty()
            .ifBlank { if (directBoot) AlarmScheduler.getActiveScheduleToken(this) else "" }
        directBootSession = directBoot
        val activateAlarm = {
            if (directBoot && directBootToken.isNotBlank()) {
                AlarmScheduler.setAlarmActiveFromDirectBoot(this, newAlarmId, directBootToken)
            } else if (!directBoot) {
                AlarmScheduler.setAlarmActive(this, newAlarmId)
            }
        }

        // Direct Boot 阶段不能读取凭据保护存储；先使用仅依赖设备保护状态的保守震动策略。
        // USER_PRESENT 后后续启动路径会重新读取完整闹钟配置。
        if (directBootSession) {
            ringtoneUri = null
            alarmMode = AlarmScheduler.AlarmMode.VIBRATE_ONLY
            ringVolume = 1
        } else {
            val entry = if (newAlarmId > 0) AlarmScheduler.findAlarm(this, newAlarmId) else null
            ringtoneUri = LockWindowManager.testRingtoneUri ?: entry?.ringtoneUri ?: AlarmScheduler.getDefaultRingtoneUri(this)
            alarmMode = LockWindowManager.testAlarmMode ?: entry?.alarmMode ?: AlarmScheduler.getDefaultAlarmMode(this)
            ringVolume = LockWindowManager.testRingVolume ?: entry?.ringVolume ?: AlarmScheduler.getDefaultRingVolume(this)
        }
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
            activateAlarm()
            isAttenuated = AlarmScheduler.isAlarmAttenuated(this)
            if (directBootSession) startForegroundWithMinimalNotification() else updateForegroundNotification()
            // [Fix: scan-screen-off] 服务已运行时仍需重新触发震动。
            // 扫码模式下熄屏后系统可能取消震动，亮屏时 AlarmActivity.onResume()
            // 再次调用 start()，此时 vibrator 对象还在但震动已停止，必须显式重启。
            startVibrationImpl()
            // 重新调度保活循环
            handler.removeCallbacks(vibrationKeepAliveRunnable)
            handler.postDelayed(vibrationKeepAliveRunnable, 5000)
            return START_STICKY
        }

        val isExistingActiveSession = AlarmScheduler.isAlarmActive(this) && alertCycleStartTime > 0L
        if (!isExistingActiveSession) {
            resetAlertCycle()
        }
        this.alarmId = newAlarmId
        activateAlarm()
        isAttenuated = AlarmScheduler.isAlarmAttenuated(this)
        if (directBootSession) startForegroundWithMinimalNotification() else updateForegroundNotification()
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
        handler.removeCallbacks(callWatchRunnable)
        handler.postDelayed(callWatchRunnable, 500)
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(vibrationKeepAliveRunnable)
        handler.removeCallbacks(ringDisplayUpdateRunnable)
        handler.removeCallbacks(callWatchRunnable)
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

    private fun isUserLockedForDirectBoot(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false
        val userManager = getSystemService(UserManager::class.java) ?: return false
        return !userManager.isUserUnlocked
    }

    @SuppressLint("MissingPermission")
    private fun startVibrationImpl() {
        if (isSnoozeSilenced || isCallPaused) {
            currentDisplayVolume = 0
            currentDisplayIntensity = 0
            mediaPlayer?.setVolume(0f, 0f)
            vibrator?.cancel()
            return
        }
        if (isAttenuated) {
            currentDisplayIntensity = 0
            currentDisplayVolume = 0
            mediaPlayer?.setVolume(0f, 0f)
            vibrator?.cancel()
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
        // 渐进时按「第 1 档 → 最大震动」铺开的浮点档位换算振幅；界面显示的当前档仍是步号 finalIntensity。
        val amplitude = AlarmAlertPolicy.vibrationAmplitude(AlarmAlertPolicy.vibrationLevelValue(currentPolicy, elapsedMs))
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

    /** 铃声音量等级(1-10，渐进时为浮点档位) → MediaPlayer浮点音量，曲线见 [AlarmAlertPolicy.ringGain] */
    private fun ringVolumeToFloat(level: Float): Float = AlarmAlertPolicy.ringGain(level)

    /** 更新铃声强度显示值 + 实际 MediaPlayer 音量（RING_ONLY 和 VIBRATE_AND_RING 都需要） */
    private fun updateRingVolumeDisplay(policy: StageAlertPolicy, elapsedMs: Long) {
        if (isSnoozeSilenced || isCallPaused || isAttenuated) {
            currentDisplayVolume = 0
            mediaPlayer?.setVolume(0f, 0f)
            syncVolumeLock()
            return
        }
        val volume = AlarmAlertPolicy.ringLevel(policy, elapsedMs)
        currentDisplayVolume = volume.coerceIn(1, 10)
        val mpVol = ringVolumeToFloat(AlarmAlertPolicy.ringLevelValue(policy, elapsedMs))
        mediaPlayer?.setVolume(mpVol, mpVol)
        syncVolumeLock()
        Log.d(TAG, "[RING_VOL] display update: policy=$policy, elapsedMs=$elapsedMs, currentDisplayVolume=$currentDisplayVolume, mpVol=$mpVol")
    }

    /**
     * 启动铃声播放。使用 MediaPlayer + USAGE_ALARM（闹钟专属通道），
     * 与用户的音乐/视频媒体音量完全隔离，不读写媒体音量。
     * 响度由 ringVolume (1-10) 通过 setVolume() 控制，出声期间闹钟通道音量被锁定（见 syncVolumeLock）。
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
            val vol = ringVolumeToFloat(AlarmAlertPolicy.ringLevelValue(policy, elapsedMs))
            mediaPlayer = MediaPlayer().apply {
                setDataSource(this@AlarmVibrationService, uri)
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                isLooping = true
                setVolume(vol, vol)
                prepare()
                start()
            }
            Log.d(TAG, "[RING-DIAG] MediaPlayer created and started, initialVolume=$initialVolume, vol=$vol, uri=$uri")
            syncVolumeLock()
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
     * 锁定「闹钟通道」音量（不是媒体音量），让系统音量键对闹钟铃声"失效"。
     * 每 200ms 检测一次，用户调小后立刻被还原；释放时还原用户原来的闹钟通道音量。
     */
    private var volumeLockRunnable: Runnable? = null

    /** 铃声此刻是否真的在出声：播放器存在，且没有被稍后再响静音 / 衰减 / 通话让路。 */
    private fun isRingAudible(): Boolean =
        mediaPlayer != null && !isSnoozeSilenced && !isAttenuated && !isCallPaused

    /** 出声才锁、不出声就释放并还原；所有改变出声状态的地方都经过这里。 */
    private fun syncVolumeLock() {
        if (isRingAudible()) startVolumeLock() else stopVolumeLock()
    }

    private fun startVolumeLock() {
        if (streamVolumeLock?.isLocked == true) return
        val audioMgr = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val lock = streamVolumeLock ?: AlarmStreamVolumeLock(
            AudioManagerStreamVolumeAccess(audioMgr, AudioManager.STREAM_ALARM)
        ).also { streamVolumeLock = it }
        lock.lock()

        val runnable = object : Runnable {
            override fun run() {
                if (streamVolumeLock?.isLocked != true) return
                streamVolumeLock?.enforce()
                handler.postDelayed(this, 200)
            }
        }
        volumeLockRunnable = runnable
        handler.postDelayed(runnable, 200)
        Log.d(TAG, "[VOL-LOCK] STREAM_ALARM locked")
    }

    private fun stopVolumeLock() {
        val lock = streamVolumeLock ?: return
        if (!lock.isLocked) return
        volumeLockRunnable?.let { handler.removeCallbacks(it) }
        volumeLockRunnable = null
        lock.release()
        Log.d(TAG, "[VOL-LOCK] released, alarm stream volume restored")
    }

    /** 来电响铃 / 通话中暂停闹钟铃声与震动；挂断后从当前阶段恢复（不重置响铃周期）。 */
    private val callWatchRunnable = object : Runnable {
        override fun run() {
            if (!isRunning) return
            val audioMgr = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val inCall = AlarmCallStatePolicy.isCallActive(audioMgr.mode)
            if (inCall != isCallPaused) {
                isCallPaused = inCall
                if (inCall) {
                    Log.i(TAG, "Call detected: pausing alarm ring and vibration")
                    currentDisplayVolume = 0
                    currentDisplayIntensity = 0
                    mediaPlayer?.setVolume(0f, 0f)
                    vibrator?.cancel()
                    syncVolumeLock()
                } else {
                    Log.i(TAG, "Call ended: resuming alarm from current stage")
                    startVibrationImpl()
                    syncVolumeLock()
                }
            }
            handler.postDelayed(this, 500)
        }
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
            .setContentTitle("决定守护")
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
