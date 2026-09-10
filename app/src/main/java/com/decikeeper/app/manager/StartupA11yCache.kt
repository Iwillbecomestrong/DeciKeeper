package com.decikeeper.app.manager

import android.content.Context
import android.content.SharedPreferences
import android.util.Log

/**
 * 启动阶段无障碍状态缓存，支持 Direct Boot (Device Protected Storage) 双写。
 *
 * 重启后进程可能在用户解锁前就已创建（LOCKED_BOOT_COMPLETED），
 * 此时凭据加密存储不可用，必须从 DPS 读取上次关机前的无障碍开关缓存，
 * 才能在不查询 Settings.Secure 的前提下快速决定是否拉起 FastLock。
 */
object StartupA11yCache {
    private const val TAG = "StartupA11yCache"
    private const val PREFS_NAME = "decikeeper_startup_state"
    private const val KEY_A11Y_WAS_ENABLED = "a11y_was_enabled"

    /**
     * 写入普通 SharedPreferences + Device Protected Storage 双写。
     * 两边都用 commit() 同步落盘，防止强制关机丢失。
     */
    fun setA11yWasEnabled(context: Context, enabled: Boolean) {
        // 普通 SharedPreferences 在 CE 未就绪时会抛 IllegalStateException。
        // Direct Boot 模式下 CoreService 可能在用户解锁前就收到 onServiceConnected。
        var normalOk = false
        try {
            normalOk = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_A11Y_WAS_ENABLED, enabled)
                .commit()
        } catch (_: IllegalStateException) {
            Log.d(TAG, "setA11yWasEnabled: CE not available, normal prefs write skipped")
        }

        val dpsContext = try {
            context.createDeviceProtectedStorageContext()
        } catch (e: Exception) {
            null
        }
        if (dpsContext != null) {
            val dpsOk = dpsContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_A11Y_WAS_ENABLED, enabled)
                .commit()
            Log.d(TAG, "setA11yWasEnabled=$enabled normalCommit=$normalOk dpsCommit=$dpsOk")
        } else {
            Log.d(TAG, "setA11yWasEnabled=$enabled normalCommit=$normalOk dpsCommit=null (DPS not available)")
        }
    }

    /**
     * 启动阶段快速判断：只读 DPS，默认 false。
     *
     * 新安装 / 从未开过无障碍 → false，FastLock 不启动，不闪屏。
     */
    fun isA11yWasEnabledForStartup(context: Context): Boolean {
        val dpsContext = try {
            context.createDeviceProtectedStorageContext()
        } catch (e: Exception) {
            null
        }
        return dpsContext?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            ?.getBoolean(KEY_A11Y_WAS_ENABLED, false) ?: false
    }
}
