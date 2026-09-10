/**
 * =================================================================================
 * [Context Header]
 * @file: A11ySelfDisableAuthorization.kt
 * @role: Domain Skill
 * @description: 无障碍自关短期授权管理。
 *  替代 ConfigPendingManager 中历史 a11y_service_self_pending 的"永久生效"语义。
 *  改为带原因+截止时间的临时授权，最长有效 10 秒，到期自动失效。
 * @responsibilities:
 * 1. 管理短期授权状态（reason + expireAt）。
 * 2. 提供 set/clear/isAuthorized 方法，附带完整诊断日志。
 * 3. 服务重连、权限恢复、取消操作时自动清空。
 * @context:
 * - 上游: AccessibilityProtection.check()
 * - 下游: CoreService, PermissionManager
 * @constraints (必读):
 * 1. 不持久化到 SharedPreferences（纯内存状态）。
 * 2. 授权最长 10 秒。
 * 3. 设置时需记录调用栈。
 * =================================================================================
 */
package com.decikeeper.app.manager

import android.util.Log
import com.decikeeper.app.data.LogExporter


/**
 * 无障碍自关短期授权枚举。
 */
enum class SelfDisableReason {
    /** 未授权 */
    NONE,
    /** 用户确认关闭 */
    USER_CONFIRMED,
    /** 应用内部重置 */
    APP_RESET,
    /** 应用卸载 */
    APP_UNINSTALL
}

/**
 * 无障碍自关短期授权管理。
 * 纯内存状态，不持久化。
 */
object A11ySelfDisableAuthorization {

    private const val TAG = "A11ySelfDisableAuth"
    private const val AUTH_DURATION_MS = 10_000L // 10 秒

    @Volatile
    private var currentReason = SelfDisableReason.NONE

    @Volatile
    private var expireAt = 0L

    /**
     * 授权自关操作。
     * @param reason 授权原因
     * @param durationMs 授权有效期（毫秒），默认 10 秒
     */
    fun authorize(reason: SelfDisableReason, durationMs: Long = AUTH_DURATION_MS) {
        val oldReason = currentReason
        val now = System.currentTimeMillis()
        val newExpireAt = now + durationMs

        currentReason = reason
        expireAt = newExpireAt

        Log.w(
            TAG,
            "SET authorize: reason=$reason, oldReason=$oldReason, expireAt=$newExpireAt (${(newExpireAt - now)}ms from now)",
            Throwable("A11ySelfDisableAuthorization.authorize() call stack")
        )
        LogExporter.logEvent(
            TAG,
            "[AUTHORIZE] reason=$reason, oldReason=$oldReason, expireInMs=${(newExpireAt - now)}"
        )
    }

    /**
     * 检查当前是否处于授权状态。
     * @return true 如果授权未过期
     */
    fun isAuthorized(): Boolean {
        if (currentReason == SelfDisableReason.NONE) {
            return false
        }

        val now = System.currentTimeMillis()
        if (now > expireAt) {
            // 已过期，自动清除
            clearInternal("auto_expired (reason=$currentReason, expired=${now - expireAt}ms ago)")
            return false
        }

        return true
    }

    /**
     * 清除授权。
     * @param reason 清除原因描述
     */
    fun clear(reason: String) {
        clearInternal(reason)
    }

    private fun clearInternal(reason: String) {
        val oldReason = currentReason
        val oldExpireAt = expireAt
        val now = System.currentTimeMillis()
        val wasExpired = oldExpireAt > 0 && now > oldExpireAt

        currentReason = SelfDisableReason.NONE
        expireAt = 0L

        Log.i(
            TAG,
            "CLEAR authorize: reason=$reason, oldReason=$oldReason, " +
                    "oldExpireAt=${if (oldExpireAt > 0) "${oldExpireAt - now}ms_ago" else "never"}, " +
                    "wasExpired=$wasExpired"
        )
        LogExporter.logEvent(
            TAG,
            "[CLEAR] reason=$reason, oldReason=$oldReason, wasExpired=$wasExpired"
        )
    }

    /** 获取当前原因（仅供调试日志） */
    fun getCurrentReason(): SelfDisableReason = currentReason

    /** 获取当前剩余有效期毫秒（仅供调试日志） */
    fun getRemainingMs(): Long {
        if (currentReason == SelfDisableReason.NONE || expireAt <= 0L) return 0L
        val remaining = expireAt - System.currentTimeMillis()
        return if (remaining > 0) remaining else 0L
    }
}