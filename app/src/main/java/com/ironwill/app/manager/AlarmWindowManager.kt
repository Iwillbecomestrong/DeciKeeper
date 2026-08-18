/**
 * =================================================================================
 * [Context Header]
 * * @file: AlarmWindowManager.kt
 * * @role: Domain Skill
 * * @description: 闹钟悬浮窗管理器，将闹钟弹窗从 Activity 迁移为悬浮窗，
 * *   从根本上避免多个闹钟频繁启动 Activity 被系统识别为恶意弹窗。
 * * @responsibilities:
 * 1. 封装 LockWindowManager 的闹钟悬浮窗显示逻辑。
 * 2. 管理屏幕唤醒 WakeLock，确保闹钟触发时点亮屏幕。
 * 3. 提供无 Activity 场景的 AlarmOverlayController 实现（扫码时仍需要 Activity）。
 * 4. 多个闹钟同时触发时复用同一个悬浮窗，避免重复创建窗口。
 * * @context:
 * - 上游: AlarmReceiver
 * - 下游: LockWindowManager, AlarmActivity (仅扫码场景)
 * * @constraints (必读):
 * 1. 依赖 SYSTEM_ALERT_WINDOW 权限（已在 AndroidManifest.xml 声明）。
 * 2. 扫码功能因 SurfaceView 限制仍需启动 AlarmActivity。
 * 3. WakeLock 最长持有 10 分钟，避免无限占用。
 * =================================================================================
 */
package com.ironwill.app.manager

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import com.ironwill.app.MyApplication
import com.ironwill.app.data.AndroidLogcatNormalEventSink
import com.ironwill.app.data.DefaultLogDebugConfig
import com.ironwill.app.data.DefaultNormalEventRenderer
import com.ironwill.app.data.LogExporter
import com.ironwill.app.data.NormalEventLogger
import com.ironwill.app.data.SystemEventClock
import com.ironwill.app.service.AlarmChallengeEmergencyGuardService
import com.ironwill.app.service.AlarmVibrationService
import com.ironwill.app.ui.AlarmActivity
import com.ironwill.app.ui.LockWindowManager

object AlarmWindowManager {

    private const val TAG = "AlarmWindowManager"
    private const val WAKE_LOCK_TAG = "IronWill:AlarmWakeLock"
    private const val WAKE_LOCK_TIMEOUT_MS = 10 * 60 * 1000L // 10 分钟
    private const val SCREEN_OFF_RECOVERY_DELAY_MS = 350L

    private var wakeLock: PowerManager.WakeLock? = null
    private var screenReceiverRegistered = false
    private var receiverContext: Context? = null
    private val mainHandler = Handler(Looper.getMainLooper())

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
    private var activeOverlayAlarmId: Int = -1

    private val screenOffReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != Intent.ACTION_SCREEN_OFF) return
            val alarmId = AlarmScheduler.getActiveAlarmId(context)
            if (alarmId < 0 || !AlarmScheduler.isAlarmActive(context)) return

            Log.w(TAG, "SCREEN_OFF_RECOVERY scheduled: alarmId=$alarmId")
            LogExporter.logEvent("AlarmOverlay", "SCREEN_OFF_RECOVERY scheduled id=$alarmId")
            mainHandler.postDelayed({
                if (!AlarmScheduler.isAlarmActive(context) || AlarmScheduler.getActiveAlarmId(context) != alarmId) {
                    return@postDelayed
                }
                refreshScreenWake(context, "SCREEN_OFF_RECOVERY")
                LockWindowManager.showAlarmOverlayIfHidden()
            }, SCREEN_OFF_RECOVERY_DELAY_MS)
        }
    }

    fun show(context: Context, alarmId: Int) {
        Log.i(TAG, "show: alarmId=$alarmId")
        LogExporter.logEvent("AlarmOverlay", "AlarmWindowManager.show id=$alarmId")

        ensureScreenOffRecoveryRegistered(context)
        AlarmChallengeEmergencyGuardService.start(context.applicationContext)
        refreshScreenWake(context, "SHOW")

        val km = context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        @Suppress("DEPRECATION")
        val isLocked = km.isKeyguardLocked()
        if (isLocked) {
            Log.i(TAG, "show: keyguard locked, launching AlarmActivity to overlay lock screen")
            LogExporter.logEvent("AlarmOverlay", "KEYGUARD_LOCKED_FALLBACK_ACTIVITY id=$alarmId")
            fallbackToActivity(context, alarmId)
            if (activeOverlayAlarmId != alarmId) {
                activeOverlayAlarmId = alarmId
                alarmProtectionEventBridge.recordAlarmOverlayShow(alarmId.toString())
            }
            return
        }

        val snoozeMinutes = AlarmScheduler.getSnoozeMinutes(context)

        if (!android.provider.Settings.canDrawOverlays(context)) {
            Log.w(TAG, "show: no overlay permission, falling back to AlarmActivity")
            LogExporter.logEvent("AlarmOverlay", "NO_OVERLAY_PERMISSION_FALLBACK_ACTIVITY id=$alarmId")
            fallbackToActivity(context, alarmId)
            if (activeOverlayAlarmId != alarmId) {
                activeOverlayAlarmId = alarmId
                alarmProtectionEventBridge.recordAlarmOverlayShow(alarmId.toString())
            }
            return
        }

        try {
            LockWindowManager.showAlarmOverlay(context, alarmId, snoozeMinutes, NoActivityAlarmController)
            LockWindowManager.showAlarmOverlayIfHidden()
            LogExporter.logEvent("AlarmOverlay", "WINDOW_MANAGER_SHOW_CALLED id=$alarmId")
            if (activeOverlayAlarmId != alarmId) {
                activeOverlayAlarmId = alarmId
                alarmProtectionEventBridge.recordAlarmOverlayShow(alarmId.toString())
            }
        } catch (e: Exception) {
            Log.e(TAG, "show: overlay failed, falling back to AlarmActivity", e)
            LogExporter.logEvent("AlarmOverlay", "WINDOW_MANAGER_SHOW_FAILED id=$alarmId error=${e.javaClass.simpleName}:${e.message}")
            fallbackToActivity(context, alarmId)
            if (activeOverlayAlarmId != alarmId) {
                activeOverlayAlarmId = alarmId
                alarmProtectionEventBridge.recordAlarmOverlayShow(alarmId.toString())
            }
        }
    }

    fun reenterChallenge(context: Context, alarmId: Int) {
        val activeId = AlarmScheduler.getActiveAlarmId(context)
        if (!AlarmScheduler.isAlarmActive(context) || activeId != alarmId) {
            Log.w(TAG, "CHALLENGE_REENTER_REQUEST rejected: requested=$alarmId active=$activeId")
            LogExporter.logEvent("AlarmOverlay", "CHALLENGE_REENTER_REQUEST rejected requested=$alarmId active=$activeId")
            return
        }

        Log.i(TAG, "CHALLENGE_REENTER_REQUEST id=$alarmId inline=${LockWindowManager.isCurrentOverlayInline()}")
        LogExporter.logEvent("AlarmOverlay", "CHALLENGE_REENTER_REQUEST id=$alarmId inline=${LockWindowManager.isCurrentOverlayInline()}")
        ensureScreenOffRecoveryRegistered(context)
        AlarmChallengeEmergencyGuardService.start(context.applicationContext)
        refreshScreenWake(context, "USER_REENTER")
        AlarmVibrationService.start(context.applicationContext, alarmId)

        if (LockWindowManager.isCurrentOverlayInline()) {
            LogExporter.logEvent("AlarmOverlay", "CHALLENGE_REENTER_ACTIVITY id=$alarmId reason=INLINE")
            fallbackToActivity(context, alarmId)
            return
        }

        show(context.applicationContext, alarmId)
        LogExporter.logEvent("AlarmOverlay", "CHALLENGE_REENTER_REASSERTED id=$alarmId")
    }

    fun refreshScreenWake(context: Context, reason: String) {
        acquireWakeLock(context.applicationContext)
        Log.i(TAG, "SCREEN_KEEP_ALIVE_REFRESH reason=$reason")
        LogExporter.logEvent("AlarmOverlay", "SCREEN_KEEP_ALIVE_REFRESH reason=$reason")
    }

    private fun fallbackToActivity(context: Context, alarmId: Int) {
        val appCtx = context.applicationContext
        val intent = Intent(appCtx, AlarmActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra(AlarmScheduler.EXTRA_ALARM_ID, alarmId)
            putExtra("is_locked_screen", true)
        }
        try {
            appCtx.startActivity(intent)
            LogExporter.logEvent("AlarmOverlay", "START_ACTIVITY_OK id=$alarmId")
        } catch (e: Exception) {
            Log.e(TAG, "fallbackToActivity failed", e)
            LogExporter.logEvent("AlarmOverlay", "START_ACTIVITY_FAILED id=$alarmId error=${e.javaClass.simpleName}:${e.message}")
            releaseWakeLock()
        }
    }

    fun dismiss() {
        Log.i(TAG, "dismiss: releasing wake lock")
        releaseWakeLock()
        unregisterScreenOffRecovery()
        runCatching { AlarmChallengeEmergencyGuardService.stop(MyApplication.instance) }
        if (activeOverlayAlarmId > 0) {
            val dismissedId = activeOverlayAlarmId
            activeOverlayAlarmId = -1
            alarmProtectionEventBridge.recordAlarmOverlayHide(dismissedId.toString(), "DISMISSED")
        }
    }

    private fun ensureScreenOffRecoveryRegistered(context: Context) {
        if (screenReceiverRegistered) return
        val appCtx = context.applicationContext
        try {
            appCtx.registerReceiver(screenOffReceiver, IntentFilter(Intent.ACTION_SCREEN_OFF))
            receiverContext = appCtx
            screenReceiverRegistered = true
            Log.d(TAG, "screen-off recovery receiver registered")
        } catch (e: Exception) {
            Log.w(TAG, "failed to register screen-off recovery receiver", e)
        }
    }

    private fun unregisterScreenOffRecovery() {
        if (!screenReceiverRegistered) return
        try {
            receiverContext?.unregisterReceiver(screenOffReceiver)
        } catch (_: Exception) {
        } finally {
            screenReceiverRegistered = false
            receiverContext = null
            mainHandler.removeCallbacksAndMessages(null)
        }
    }

    private fun acquireWakeLock(context: Context) {
        try {
            if (wakeLock?.isHeld == true) {
                Log.d(TAG, "WakeLock already held, re-acquiring")
                wakeLock?.release()
            }
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(
                PowerManager.FULL_WAKE_LOCK or
                    PowerManager.ACQUIRE_CAUSES_WAKEUP or
                    PowerManager.ON_AFTER_RELEASE,
                WAKE_LOCK_TAG
            ).apply {
                setReferenceCounted(false)
                acquire(WAKE_LOCK_TIMEOUT_MS)
            }
            Log.d(TAG, "WakeLock acquired (timeout=${WAKE_LOCK_TIMEOUT_MS}ms)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to acquire wake lock", e)
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

    private object NoActivityAlarmController : LockWindowManager.AlarmOverlayController {

        override fun launchQrScan() {
            val ctx = MyApplication.instance ?: return
            val activeAlarmId = AlarmScheduler.getActiveAlarmId(ctx)
            if (activeAlarmId < 0) {
                Log.w(TAG, "launchQrScan: no active alarm, aborting")
                return
            }
            LockWindowManager.hideAlarmOverlayForPermission()
            val intent = Intent(ctx, AlarmActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                putExtra(AlarmScheduler.EXTRA_ALARM_ID, activeAlarmId)
                putExtra("request_camera_permission", true)
            }
            ctx.startActivity(intent)
        }

        override fun onAlarmOverlayFinished() {
            dismiss()
        }
    }
}