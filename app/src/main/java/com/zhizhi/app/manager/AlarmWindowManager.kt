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
package com.zhizhi.app.manager

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import com.zhizhi.app.MyApplication
import com.zhizhi.app.data.AndroidLogcatNormalEventSink
import com.zhizhi.app.data.DefaultLogDebugConfig
import com.zhizhi.app.data.DefaultNormalEventRenderer
import com.zhizhi.app.data.LogExporter
import com.zhizhi.app.data.NormalEventLogger
import com.zhizhi.app.data.SystemEventClock
import com.zhizhi.app.service.AlarmChallengeEmergencyGuardService
import com.zhizhi.app.service.AlarmVibrationService
import com.zhizhi.app.ui.AlarmActivity
import com.zhizhi.app.ui.LockWindowManager

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

    /**
     * 某些 OEM 即使闹钟仍在运行，也可能在挑战过程中发出 SCREEN_OFF。
     * 闹钟 active 时把它视为异常熄屏：短延迟后重新获取亮屏 WakeLock，
     * 并让现有挑战覆盖层重新检查挂载状态。用户完成挑战后立即注销监听。
     */
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

    /**
     * 显示闹钟悬浮窗。
     *
     * Expected Input: 闹钟触发，需要显示挑战界面。
     * Expected Output: 点亮屏幕 + 显示悬浮窗挑战界面；如果已有悬浮窗则更新内容。
     *                  无悬浮窗权限或悬浮窗显示失败时，降级启动 AlarmActivity。
     */
    fun show(context: Context, alarmId: Int, newSession: Boolean = false) {
        Log.i(TAG, "show: alarmId=$alarmId newSession=$newSession")
        LogExporter.logEvent("AlarmOverlay", "AlarmWindowManager.show id=$alarmId newSession=$newSession")

        ensureScreenOffRecoveryRegistered(context)
        AlarmChallengeEmergencyGuardService.start(context.applicationContext)
        // 先点亮并保持屏幕，再决定走 Activity 还是 WindowManager。
        // 锁屏分支如果不先拿 WakeLock，Activity 可能已启动但用户仍看不到。
        refreshScreenWake(context, "SHOW")

        // [锁屏检测] 系统锁屏正在显示时，Service 覆盖层无法盖过锁屏。
        // 此时启动 AlarmActivity（已配置 setShowWhenLocked(true)），
        // 让挑战界面显示在系统锁屏之上，用户无需解锁即可操作。
        val km = context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        @Suppress("DEPRECATION")
        val isLocked = km.isKeyguardLocked()
        if (isLocked) {
            Log.i(TAG, "show: keyguard locked, launching AlarmActivity to overlay lock screen")
            LogExporter.logEvent("AlarmOverlay", "KEYGUARD_LOCKED_FALLBACK_ACTIVITY id=$alarmId")
            fallbackToActivity(context, alarmId, newSession)
            if (activeOverlayAlarmId != alarmId) {
                activeOverlayAlarmId = alarmId
                alarmProtectionEventBridge.recordAlarmOverlayShow(alarmId.toString())
            }
            return
        }

        val snoozeMinutes = AlarmScheduler.getSnoozeMinutes(context)

        // 2. 检查悬浮窗权限，无权限时降级到 AlarmActivity
        if (!android.provider.Settings.canDrawOverlays(context)) {
            Log.w(TAG, "show: no overlay permission, falling back to AlarmActivity")
            LogExporter.logEvent("AlarmOverlay", "NO_OVERLAY_PERMISSION_FALLBACK_ACTIVITY id=$alarmId")
            fallbackToActivity(context, alarmId, newSession)
            if (activeOverlayAlarmId != alarmId) {
                activeOverlayAlarmId = alarmId
                alarmProtectionEventBridge.recordAlarmOverlayShow(alarmId.toString())
            }
            return
        }

        // 3. 通过 LockWindowManager 显示悬浮窗，失败时降级到 AlarmActivity
        try {
            LockWindowManager.showAlarmOverlay(context, alarmId, snoozeMinutes, NoActivityAlarmController, newSession = newSession)
            LockWindowManager.showAlarmOverlayIfHidden()
            LogExporter.logEvent("AlarmOverlay", "WINDOW_MANAGER_SHOW_CALLED id=$alarmId")
            if (activeOverlayAlarmId != alarmId) {
                activeOverlayAlarmId = alarmId
                alarmProtectionEventBridge.recordAlarmOverlayShow(alarmId.toString())
            }
        } catch (e: Exception) {
            Log.e(TAG, "show: overlay failed, falling back to AlarmActivity", e)
            LogExporter.logEvent("AlarmOverlay", "WINDOW_MANAGER_SHOW_FAILED id=$alarmId error=${e.javaClass.simpleName}:${e.message}")
            fallbackToActivity(context, alarmId, newSession)
            if (activeOverlayAlarmId != alarmId) {
                activeOverlayAlarmId = alarmId
                alarmProtectionEventBridge.recordAlarmOverlayShow(alarmId.toString())
            }
        }
    }

    /**
     * 用户手动点击“挑战”时的恢复入口。
     * 只要该闹钟仍是 active，就必须尝试把挑战带回用户面前；
     * “内部认为 overlay 已启动”不能再成为拒绝恢复的理由。
     */
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

        // 如果挑战当前由 AlarmActivity 内嵌承载，最安全的恢复方式是把同一个 Activity 拉回前台，
        // 而不是把已有 View 强制迁移到 WindowManager（那会重建题目/丢失当前进度）。
        if (LockWindowManager.isCurrentOverlayInline()) {
            LogExporter.logEvent("AlarmOverlay", "CHALLENGE_REENTER_ACTIVITY id=$alarmId reason=INLINE")
            fallbackToActivity(context, alarmId)
            return
        }

        // 窗口模式直接复用 show()：它会处理透明/失焦残留、View 被 OEM 移除后的重挂载，
        // 必要时自动降级到 Activity。
        show(context.applicationContext, alarmId)
        LogExporter.logEvent("AlarmOverlay", "CHALLENGE_REENTER_REASSERTED id=$alarmId")
    }

    /** 重新取得亮屏 WakeLock；既用于首次显示，也用于异常熄屏/用户手动恢复。 */
    fun refreshScreenWake(context: Context, reason: String) {
        acquireWakeLock(context.applicationContext)
        Log.i(TAG, "SCREEN_KEEP_ALIVE_REFRESH reason=$reason")
        LogExporter.logEvent("AlarmOverlay", "SCREEN_KEEP_ALIVE_REFRESH reason=$reason")
    }

    private fun fallbackToActivity(context: Context, alarmId: Int, newSession: Boolean = false) {
        val appCtx = context.applicationContext
        val intent = Intent(appCtx, AlarmActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra(AlarmScheduler.EXTRA_ALARM_ID, alarmId)
            putExtra("is_locked_screen", true)
            putExtra("new_alarm_session", newSession)
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

    /**
     * 关闭闹钟悬浮窗并释放 WakeLock。
     * 由挑战完成或用户暂停时调用。
     */
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

    /**
     * 无 Activity 场景的 AlarmOverlayController 实现。
     *
     * 扫码时需要启动 AlarmActivity（SurfaceView 无法在 TYPE_APPLICATION_OVERLAY 中渲染），
     * 其他场景（挑战完成）仅清理本地状态。
     */
    private object NoActivityAlarmController : LockWindowManager.AlarmOverlayController {

        override fun launchQrScan() {
            // 悬浮窗中无 Activity 无法直接请求权限，启动 AlarmActivity 处理扫码
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
            // 悬浮窗关闭时释放 WakeLock
            dismiss()
        }
    }
}
