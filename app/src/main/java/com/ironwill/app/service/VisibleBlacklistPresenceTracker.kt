package com.ironwill.app.service

enum class PresenceTrackerState {
    NONE,
    CONFIRMED_VISIBLE,
    DESKTOP_GRACE
}

enum class PresenceTrackerAction {
    NONE,
    LOCK,
    CLEAR
}

class VisibleBlacklistPresenceTracker(
    val desktopGraceDurationMs: Long = 3000L
) {
    var state: PresenceTrackerState = PresenceTrackerState.NONE
        private set

    var currentPackage: String? = null
        private set

    var confirmedAtMs: Long = 0L
        private set

    var graceUntilMs: Long = 0L
        private set

    val lastStoppedAtMap = mutableMapOf<String, Long>()

    private val ACTIVITY_STOPPED = 23

    /**
     * 首次 Presence 连续确认
     */
    fun onPresenceConfirmed(pkg: String, nowMs: Long): PresenceTrackerAction {
        currentPackage = pkg
        confirmedAtMs = nowMs
        state = PresenceTrackerState.CONFIRMED_VISIBLE
        graceUntilMs = 0L
        return PresenceTrackerAction.LOCK
    }

    /**
     * 用户点击“回到桌面”或切回桌面，进入 Grace 宽限期
     */
    fun onDesktopReturn(nowMs: Long) {
        if (state == PresenceTrackerState.CONFIRMED_VISIBLE || state == PresenceTrackerState.DESKTOP_GRACE) {
            state = PresenceTrackerState.DESKTOP_GRACE
            graceUntilMs = nowMs + desktopGraceDurationMs
        }
    }

    /**
     * 收到无障碍实时可见窗口树
     */
    fun onAccessibilitySnapshot(visiblePackages: Set<String>, nowMs: Long): PresenceTrackerAction {
        val current = currentPackage ?: return PresenceTrackerAction.NONE
        if (state != PresenceTrackerState.NONE) {
            if (!visiblePackages.contains(current)) {
                // 无障碍明确看到目标小窗已不在屏幕上 -> 强关闭证据
                reset()
                return PresenceTrackerAction.CLEAR
            }
        }
        return PresenceTrackerAction.NONE
    }

    /**
     * 收到 UsageEvent（ACTIVITY_STOPPED / ACTIVITY_RESUMED / ACTIVITY_PAUSED）
     */
    fun onUsageEvent(pkg: String, eventType: Int, eventTimeMs: Long): PresenceTrackerAction {
        if (eventType == ACTIVITY_STOPPED) {
            lastStoppedAtMap[pkg] = eventTimeMs
            // 🛡️ 只有当 STOPPED 属于当前 Session（eventTimeMs > confirmedAtMs）时才允许 CLEAR，避免 30s 历史事件重放杀掉新 Session
            if (currentPackage == pkg && state != PresenceTrackerState.NONE && eventTimeMs > confirmedAtMs) {
                reset()
                return PresenceTrackerAction.CLEAR
            }
        }
        return PresenceTrackerAction.NONE
    }

    fun isPackageClosed(pkg: String): Boolean {
        return false
    }

    /**
     * 检查当前包的 lastTimeVisible 是否合法：
     * 如果 lastTimeVisible <= lastStoppedAt，说明这是关闭小窗时系统刷新的残留时间戳，不能用于发现新 Presence。
     */
    fun isLastTimeVisibleValid(pkg: String, lastTimeVisible: Long): Boolean {
        val lastStopped = lastStoppedAtMap[pkg] ?: 0L
        if (lastStopped > 0L && lastTimeVisible <= lastStopped) {
            return false
        }
        return true
    }

    /**
     * 周期轮询评估：
     * - 如果处于 DESKTOP_GRACE 状态：
     *   - 在 Grace 期内 -> NONE（给用户关闭小窗的机会）
     *   - Grace 到期且未收到 CLOSED 证据 -> 重新锁机！状态转回 CONFIRMED_VISIBLE
     * - 如果处于 CONFIRMED_VISIBLE 状态（例如用户进入了其他应用/IronWill，但小窗仍未关闭） -> LOCK
     */
    fun evaluate(nowMs: Long): PresenceTrackerAction {
        return when (state) {
            PresenceTrackerState.NONE -> PresenceTrackerAction.NONE
            PresenceTrackerState.DESKTOP_GRACE -> {
                if (nowMs < graceUntilMs) {
                    PresenceTrackerAction.NONE
                } else {
                    // Grace 到期且未收到任何关闭证据 -> 依然判定小窗存在，发起锁机！
                    state = PresenceTrackerState.CONFIRMED_VISIBLE
                    PresenceTrackerAction.LOCK
                }
            }
            PresenceTrackerState.CONFIRMED_VISIBLE -> {
                PresenceTrackerAction.LOCK
            }
        }
    }

    fun reset() {
        state = PresenceTrackerState.NONE
        currentPackage = null
        confirmedAtMs = 0L
        graceUntilMs = 0L
    }
}
