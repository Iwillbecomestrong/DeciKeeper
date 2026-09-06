package com.zhizhi.app.service

/**
 * 业务服务层：未关闭前台会话跟踪器 (ForegroundSessionTracker)。
 *
 * 职责与约束：
 * 1. 负责维护应用在前台的持续会话状态（“曾经明确进入前台，且尚未收到离开证据”）。
 * 2. 纯内存状态机，**禁止跨进程持久化**（进程重启后必须重新通过真实前台事件建立 Session，防止旧缓存误锁）。
 * 3. 消费 UsageEvents（MOVE_TO_FG / ACTIVITY_RESUMED / MOVE_TO_BG / ACTIVITY_PAUSED / ACTIVITY_STOPPED）
 *    及系统级离开信号（屏幕关闭、锁屏等）。
 * 4. 严格过滤系统 UI、通知栏及输入法等瞬时干扰，只有第三方应用或桌面切入才视为有效切换。
 */
class ForegroundSessionTracker(
    private val isNoisePackagePredicate: (String) -> Boolean = ::defaultIsNoisePackage
) {
    companion object {
        const val MOVE_TO_FG = 1
        const val MOVE_TO_BG = 2
        const val ACTIVITY_RESUMED = 15
        const val ACTIVITY_PAUSED = 16
        const val ACTIVITY_STOPPED = 23

        /**
         * 默认系统噪声应用过滤。
         * 避免下拉通知栏（SystemUI）或弹出输入法误杀当前正在前台运行的应用 Session。
         */
        fun defaultIsNoisePackage(packageName: String): Boolean {
            if (packageName.isBlank()) return true
            if (packageName == "com.android.systemui") return true
            if (packageName.contains(".inputmethod") || packageName.contains(".ime")) return true
            return false
        }
    }

    /**
     * 前台会话数据结构。
     */
    data class Session(
        val packageName: String,
        val openedAtMs: Long,
        val closed: Boolean = false,
        val closedAtMs: Long = 0L,
        val source: String = "USAGE_EVENTS"
    )

    var currentSession: Session? = null
        private set

    /**
     * 消费来自 UsageEvents 的生命周期事件。
     */
    fun onUsageEvent(packageName: String, eventType: Int, eventTimeMs: Long) {
        if (packageName.isBlank()) return
        if (isNoisePackagePredicate(packageName)) return

        when (eventType) {
            MOVE_TO_FG, ACTIVITY_RESUMED -> {
                val existing = currentSession
                if (existing != null && existing.packageName == packageName) {
                    if (existing.closed) {
                        // 该应用此前关闭/退到后台，现在重新进入前台，开启新活跃 Session
                        currentSession = Session(
                            packageName = packageName,
                            openedAtMs = eventTimeMs,
                            closed = false,
                            source = "USAGE_EVENTS"
                        )
                    } else {
                        // 同一应用持续前台（重复 RESUMED），保留原有 openedAtMs
                        // 若事件时间晚于 openedAtMs，不缩短已打开时间
                    }
                } else {
                    // 其它应用（如第三方 App 或桌面 Launcher）进入前台，替换当前 Session
                    currentSession = Session(
                        packageName = packageName,
                        openedAtMs = eventTimeMs,
                        closed = false,
                        source = "USAGE_EVENTS"
                    )
                }
            }

            MOVE_TO_BG, ACTIVITY_PAUSED, ACTIVITY_STOPPED -> {
                val existing = currentSession
                if (existing != null && existing.packageName == packageName) {
                    // 仅当反证事件发生在 Session 开启之后或同时，才关闭当前 Session
                    if (eventTimeMs >= existing.openedAtMs) {
                        currentSession = existing.copy(
                            closed = true,
                            closedAtMs = eventTimeMs
                        )
                    }
                }
            }
        }
    }

    /**
     * 屏幕关闭：明确反证，关闭当前活跃 Session。
     */
    fun onScreenOff(nowMs: Long) {
        val existing = currentSession ?: return
        if (!existing.closed) {
            currentSession = existing.copy(closed = true, closedAtMs = nowMs)
        }
    }

    /**
     * Keyguard 锁屏：明确反证，关闭当前活跃 Session。
     */
    fun onKeyguardLocked(nowMs: Long) {
        val existing = currentSession ?: return
        if (!existing.closed) {
            currentSession = existing.copy(closed = true, closedAtMs = nowMs)
        }
    }

    /**
     * 判断指定应用是否具有未关闭的活跃前台 Session。
     */
    fun isSessionActiveFor(packageName: String): Boolean {
        if (packageName.isBlank()) return false
        val session = currentSession ?: return false
        return session.packageName == packageName && !session.closed
    }

    /**
     * 重置状态机（服务销毁或手动重置）。
     */
    fun reset() {
        currentSession = null
    }
}
