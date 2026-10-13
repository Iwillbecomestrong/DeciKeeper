package com.decikeeper.app.manager

import android.content.Context

/**
 * 防卸载"是否需要显示初始化界面"的 Direct Boot 镜像（Device Protected Storage）。
 *
 * 真值在 CE 存储里，用户解锁前读不到；锁屏期预挂遮罩只能依赖这个镜像。
 * 由 [AntiUninstallManager.getProtectionState] 在每次计算后同步；镜像缺失时视为 false（不预挂）。
 */
object StartupAntiUninstallCache {
    private const val PREFS_NAME = "decikeeper_startup_state"
    private const val KEY = "anti_uninstall_init_required"

    fun sync(context: Context, required: Boolean) {
        try {
            val prefs = context.createDeviceProtectedStorageContext()
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            if (!prefs.contains(KEY) || prefs.getBoolean(KEY, false) != required) {
                prefs.edit().putBoolean(KEY, required).apply()
            }
        } catch (_: Throwable) {
        }
    }

    fun isInitRequired(context: Context): Boolean = try {
        context.createDeviceProtectedStorageContext()
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY, false)
    } catch (_: Throwable) {
        false
    }
}
