/**
 * =================================================================================
 * [Context Header]
 * * @file: DirectBootState.kt
 * * @role: Utility
 * * @description: Direct Boot（用户首次解锁前）状态判定的唯一入口。
 * * @responsibilities:
 * 1. 判断 CE（Credential Encrypted）存储当前是否可用。
 * * @context:
 * - 上游: ThemeManager, StartupProtectionManager, BootReceiver, CoreService
 * * @constraints (必读):
 * 1. 任何异常或拿不到 UserManager 都按"未解锁"处理，宁可不读 CE，也不能崩溃。
 * 2. 禁止缓存"已解锁"结果，避免跨用例/跨进程状态污染。
 * 3. 本函数自身不得访问任何 SharedPreferences / Settings。
 * =================================================================================
 */
package com.decikeeper.app.util

import android.content.Context
import android.os.Build
import android.os.UserManager

object DirectBootState {
    /** 用户是否已解锁（CE 可用）。未解锁时严禁访问 CE SharedPreferences。 */
    fun isUserUnlocked(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return true
        return try {
            context.getSystemService(UserManager::class.java)?.isUserUnlocked ?: false
        } catch (_: Throwable) {
            false
        }
    }
}
