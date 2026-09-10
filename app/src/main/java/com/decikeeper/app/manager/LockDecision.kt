/**
 * =================================================================================
 * [Context Header]
 * * @file: LockDecision.kt
 * * @role: Domain Skill
 * * @description: 锁机双锁判定纯策略对象。
 * * @responsibilities:
 * 1. 勿扰锁优先拦截，勿扰解锁后再查应用时间窗，特权绕过两把锁。
 * * @context:
 * - 上游: MonitorService.checkForegroundApp()
 * - 下游: Boolean 判定结果（决定是否触发锁屏）
 * * @constraints (必读):
 * 1. 纯函数：输入显式参数，返回 Boolean；不依赖 Service 生命周期/字段。
 * 2. 判定顺序、短路行为、日志字符串必须与 MonitorService 原实现逐字一致。
 * =================================================================================
 */
package com.decikeeper.app.manager

import android.util.Log

/**
 * 双锁判定纯策略，从 MonitorService 原 shouldBlock 内联 when 提取。
 *
 * 语义（与 MonitorService 原实现逐字一致）：
 *   - isBlacklisted=true 时：isSafe / isPrivilege 绕过；否则 isDndLocked 直接拦截；
 *     勿扰解锁后再按应用时间窗 isAppWindowBlocked 判定。
 *   - isBlacklisted=false 时：segmentDecoupled 且受 AppWindowManager 管控 → 按时间窗判定；
 *     否则放行。
 *
 * 注意：isManaged 以惰性 lambda 传入，原样保留原实现中
 * `segmentDecoupled && AppWindowManager.isManaged(...)` 的短路求值顺序
 * （仅在 isBlacklisted=false 且 segmentDecoupled=true 时才调用 isManaged）。
 */
object LockDecisionPolicy {

    // 保持原 MonitorService 的 logcat TAG，确保日志输出逐字不变。
    private const val TAG = "MonitorService"

    fun shouldBlock(
        isBlacklisted: Boolean,
        isSafe: Boolean,
        isPrivilege: Boolean,
        isDndLocked: Boolean,
        isAppWindowBlocked: Boolean,
        segmentDecoupled: Boolean,
        isManaged: () -> Boolean,
        effectivePackageName: String,
        isTemporaryAccess: Boolean = false
    ): Boolean {
        if (isSafe || isPrivilege || isTemporaryAccess) {
            return false
        }
        return when {
            isBlacklisted -> {
                if (isDndLocked) {
                    // 勿扰锁未解开：即使时间窗开放，也必须拦截
                    Log.d(TAG, "[shouldBlock] Blocked by DND: pkg=$effectivePackageName")
                    true
                } else {
                    if (isAppWindowBlocked) {
                        Log.d(TAG, "[shouldBlock] Blocked by AppWindow: pkg=$effectivePackageName, dndUnlocked=true")
                    }
                    isAppWindowBlocked
                }
            }
            segmentDecoupled && isManaged() -> isAppWindowBlocked
            else -> false
        }
    }
}