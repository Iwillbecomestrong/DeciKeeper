/**
 * =================================================================================
 * [Context Header]
 * * @file: BootUnlockTrigger.kt
 * * @role: Domain Skill
 * * @description: 进程出生于 Direct Boot 时，动态监听用户解锁并立即补挂开机 FastLock。
 * * @responsibilities:
 * 1. 在未解锁的进程中动态注册 USER_UNLOCKED / USER_PRESENT（USER_UNLOCKED 仅投递给动态接收器）。
 * 2. 解锁信号到达后转交 StartupProtectionManager.triggerBootFastLock（CAS 保证只挂一次）。
 * * @context:
 * - 上游: MyApplication（Direct Boot 分支）, CoreService.onServiceConnected
 * - 下游: StartupProtectionManager
 * * @constraints (必读):
 * 1. 注册与回调路径禁止访问任何 CE 存储；解锁判定统一走 DirectBootState。
 * 2. 先注册再复查解锁状态，避免注册与解锁之间漏掉广播。
 * =================================================================================
 */
package com.decikeeper.app.manager

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.VisibleForTesting
import androidx.core.content.ContextCompat
import com.decikeeper.app.util.DirectBootState
import java.util.concurrent.atomic.AtomicBoolean

object BootUnlockTrigger {
    private const val TAG = "DeciKeeper"
    private val registered = AtomicBoolean(false)
    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile private var receiver: BroadcastReceiver? = null

    /** 仅在用户尚未解锁时注册；已解锁或已注册则无操作。 */
    fun armIfLocked(context: Context) {
        val app = context.applicationContext ?: context
        if (DirectBootState.isUserUnlocked(app)) return
        if (!registered.compareAndSet(false, true)) {
            StartupProtectionManager.preAttachBootOverlayIfLocked(app)
            return
        }
        StartupProtectionManager.markUnlockTriggerArmed()
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                onUnlockSignal(app, intent.action ?: "unknown")
            }
        }
        receiver = r
        try {
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_USER_UNLOCKED)
                addAction(Intent.ACTION_USER_PRESENT)
            }
            ContextCompat.registerReceiver(app, r, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
            Log.i(TAG, "[BootUnlock] armed: waiting for USER_UNLOCKED / USER_PRESENT")
        } catch (e: Exception) {
            registered.set(false)
            receiver = null
            Log.e(TAG, "[BootUnlock] register failed", e)
            return
        }
        // 先注册再复查：注册期间若恰好解锁，补一次手动触发。
        if (DirectBootState.isUserUnlocked(app)) {
            mainHandler.post { onUnlockSignal(app, "post_register_recheck") }
        }
        // 接收器注册完成后再预挂遮罩（addView 可能耗时数百毫秒，不能挡住解锁通道）。
        // 窗口位于 keyguard 之下，解锁瞬间即就位；失败不影响解锁通道。
        StartupProtectionManager.preAttachBootOverlayIfLocked(app)
    }

    @VisibleForTesting
    internal fun onUnlockSignal(app: Context, channel: String) {
        if (!DirectBootState.isUserUnlocked(app)) return
        try {
            StartupProtectionManager.triggerBootFastLock(app, "unlock:$channel")
        } catch (e: Exception) {
            Log.e(TAG, "[BootUnlock] triggerBootFastLock failed channel=$channel", e)
        }
        // USER_UNLOCKED 之后 USER_PRESENT 仍会补一次 reassert；PRESENT 到达后即可注销。
        if (channel == Intent.ACTION_USER_PRESENT) unregister(app)
    }

    private fun unregister(app: Context) {
        val r = receiver ?: return
        receiver = null
        registered.set(false)
        try {
            app.unregisterReceiver(r)
        } catch (_: Exception) {
        }
    }

    @VisibleForTesting
    internal fun resetForTest(app: Context) {
        unregister(app)
    }
}
