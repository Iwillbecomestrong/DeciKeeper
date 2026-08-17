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
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.util.Log
import com.ironwill.app.MyApplication
import com.ironwill.app.data.AndroidLogcatNormalEventSink
import com.ironwill.app.data.DefaultLogDebugConfig
import com.ironwill.app.data.DefaultNormalEventRenderer
import com.ironwill.app.data.LogExporter
import com.ironwill.app.data.NormalEventLogger
import com.ironwill.app.data.SystemEventClock
import com.ironwill.app.ui.AlarmActivity
import com.ironwill.app.ui.LockWindowManager










object AlarmWindowManager {

    private const val TAG = "AlarmWindowManager"
    private const val WAKE_LOCK_TAG = "IronWill:AlarmWakeLock"
    private const val WAKE_LOCK_TIMEOUT_MS = 10 * 60 * 1000L // 10 分钟

    private var wakeLock: PowerManager.WakeLock? = null

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
     * 显示闹钟悬浮窗。
     *
     * Expected Input: 闹钟触发，需要显示挑战界面。
     * Expected Output: 点亮屏幕 + 显示悬浮窗挑战界面；如果已有悬浮窗则更新内容。
     *                  无悬浮窗权限或悬浮窗显示失败时，降级启动 AlarmActivity。
     */
    fun show(context: Context, alarmId: Int) {
        Log.i(TAG, "show: alarmId=$alarmId")
        LogExporter.logEvent("AlarmOverlay", "AlarmWindowManager.show id=$alarmId")

        // 先点亮并保持屏幕，再决定走 Activity 还是 WindowManager。
        // 锁屏分支如果不先拿 WakeLock，Activity 可能已启动但用户仍看不到。
        acquireWakeLock(context)

        // [锁屏检测] 系统锁屏正在显示时，Service 覆盖层无法盖过锁屏。
        // 此时启动 AlarmActivity（已配置 setShowWhenLocked(true)），
        // 让挑战界面显示在系统锁屏之上，用户无需解锁即可操作。
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

        // 2. 检查悬浮窗权限，无权限时降级到 AlarmActivity
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

        // 3. 通过 LockWindowManager 显示悬浮窗，失败时降级到 AlarmActivity
        try {
            LockWindowManager.showAlarmOverlay(context, alarmId, snoozeMinutes, NoActivityAlarmController)
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

    /**
     * 关闭闹钟悬浮窗并释放 WakeLock。
     * 由挑战完成或用户暂停时调用。
     */
    fun dismiss() {
        Log.i(TAG, "dismiss: releasing wake lock")
        releaseWakeLock()
        if (activeOverlayAlarmId > 0) {
            val dismissedId = activeOverlayAlarmId
            activeOverlayAlarmId = -1
            alarmProtectionEventBridge.recordAlarmOverlayHide(dismissedId.toString(), "DISMISSED")
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
