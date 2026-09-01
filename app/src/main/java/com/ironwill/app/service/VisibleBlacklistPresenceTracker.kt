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

    /**
     * true 表示最近一次主动 Accessibility 窗口观测处于 UNKNOWN，当前 Presence 只能视为
     * “历史上确认过，但现在尚未重新验证”。重新拿到 PRESENT/ABSENT 之前，禁止依赖旧 Presence
     * 或 UsageStats.lastTimeVisible 继续触发重复锁屏。
     */
    var accessibilityRevalidationRequired: Boolean = false
        private set

    val lastStoppedAtMap = mutableMapOf<String, Long>()
    val closedAtMap = mutableMapOf<String, Long>()
    val lastOpenedAtMap = mutableMapOf<String, Long>()

    private val MOVE_TO_FG = 1
    private val MOVE_TO_BG = 2
    private val ACTIVITY_RESUMED = 15
    private val ACTIVITY_PAUSED = 16
    private val ACTIVITY_STOPPED = 23

    /**
     * 首次 Presence 连续确认
     */
    fun onPresenceConfirmed(pkg: String, nowMs: Long): PresenceTrackerAction {
        lastOpenedAtMap[pkg] = nowMs
        closedAtMap.remove(pkg)
        currentPackage = pkg
        confirmedAtMs = nowMs
        state = PresenceTrackerState.CONFIRMED_VISIBLE
        graceUntilMs = 0L
        accessibilityRevalidationRequired = false
        return PresenceTrackerAction.LOCK
    }

    /**
     * 用户点击“回到桌面”或切回桌面，进入 Grace 宽限期。
     *
     * 如果此前窗口观测处于 UNKNOWN，这里不能把它恢复成“已确认可见”；Grace 到期后必须等到
     * 新的 Accessibility 窗口观测才能重新锁。
     */
    fun onDesktopReturn(nowMs: Long) {
        if (state == PresenceTrackerState.CONFIRMED_VISIBLE || state == PresenceTrackerState.DESKTOP_GRACE) {
            state = PresenceTrackerState.DESKTOP_GRACE
            graceUntilMs = nowMs + desktopGraceDurationMs
        }
    }

    /**
     * MonitorService 的运行时入口。
     *
     * CoreService.lastVisiblePackagesFromWindows 在窗口查询异常时会保留上一次成功结果，不能作为
     * “本轮仍然看到小窗”的充分证据。因此只要 AccessibilityService 正在运行，就主动重新读取
     * service.windows，并把结果严格区分成 PRESENT / ABSENT / UNKNOWN：
     * - PRESENT：明确重新看到目标窗口，可以继续维持 Presence；
     * - ABSENT：在可观察状态下完整扫描且目标消失，作为关闭证据；
     * - UNKNOWN：熄屏、锁屏、IronWill 自身锁层遮挡、窗口树不完整或查询异常，不作存在/消失推断。
     *
     * JVM 单测或无 AccessibilityService 场景继续把显式传入的 Set 当作成功合成快照，保持原有
     * 状态机测试和非无障碍降级路径兼容。
     */
    fun onAccessibilitySnapshot(visiblePackages: Set<String>, nowMs: Long): PresenceTrackerAction {
        val current = currentPackage ?: return PresenceTrackerAction.NONE
        if (state == PresenceTrackerState.NONE) return PresenceTrackerAction.NONE

        if (!CoreService.isServiceActive) {
            return onAccessibilitySnapshot(
                visiblePackages = visiblePackages,
                nowMs = nowMs,
                scanSucceeded = true
            )
        }

        return when (AccessibilityWindowPresenceProbe.observe(current)) {
            AccessibilityWindowPresenceObservation.PRESENT -> onAccessibilitySnapshot(
                visiblePackages = setOf(current),
                nowMs = nowMs,
                scanSucceeded = true
            )
            AccessibilityWindowPresenceObservation.ABSENT -> onAccessibilitySnapshot(
                visiblePackages = emptySet(),
                nowMs = nowMs,
                scanSucceeded = true
            )
            AccessibilityWindowPresenceObservation.UNKNOWN -> onAccessibilitySnapshot(
                visiblePackages = visiblePackages,
                nowMs = nowMs,
                scanSucceeded = false
            )
        }
    }

    /**
     * 可测试的窗口快照入口。
     *
     * scanSucceeded=false 时 visiblePackages 可能只是上一次成功扫描遗留的数据，这种数据既不能
     * 证明窗口仍存在，也不能证明窗口已经消失。
     */
    fun onAccessibilitySnapshot(
        visiblePackages: Set<String>,
        nowMs: Long,
        scanSucceeded: Boolean
    ): PresenceTrackerAction {
        val current = currentPackage ?: return PresenceTrackerAction.NONE
        if (state == PresenceTrackerState.NONE) return PresenceTrackerAction.NONE

        if (!scanSucceeded) {
            accessibilityRevalidationRequired = true
            return PresenceTrackerAction.NONE
        }

        if (visiblePackages.contains(current)) {
            // 新鲜成功快照重新看到了静止小窗：无需任何新的 RESUMED / FOREGROUND 事件即可续证。
            accessibilityRevalidationRequired = false
            return PresenceTrackerAction.NONE
        }

        // 强关闭证据：成功且可观察的 A11y 窗口快照中目标包消失 -> CLOSED Tombstone + 清除 Session。
        closedAtMap[current] = nowMs
        lastStoppedAtMap[current] = nowMs
        reset()
        return PresenceTrackerAction.CLEAR
    }

    /**
     * 收到 UsageEvent（MOVE_TO_FG / ACTIVITY_RESUMED / MOVE_TO_BG / ACTIVITY_PAUSED / ACTIVITY_STOPPED）
     */
    fun onUsageEvent(pkg: String, eventType: Int, eventTimeMs: Long): PresenceTrackerAction {
        when (eventType) {
            ACTIVITY_STOPPED -> {
                lastStoppedAtMap[pkg] = eventTimeMs
                closedAtMap[pkg] = eventTimeMs
                // 🛡️ 只有当 STOPPED 属于当前 Session（eventTimeMs > confirmedAtMs）时才允许 CLEAR，避免 30s 历史事件重放杀掉新 Session
                if (currentPackage == pkg && state != PresenceTrackerState.NONE && eventTimeMs > confirmedAtMs) {
                    reset()
                    return PresenceTrackerAction.CLEAR
                }
            }
            MOVE_TO_FG, ACTIVITY_RESUMED -> {
                val currentClosedAt = closedAtMap[pkg] ?: 0L
                if (eventTimeMs > currentClosedAt) {
                    lastOpenedAtMap[pkg] = eventTimeMs
                    closedAtMap.remove(pkg)
                }
            }
            MOVE_TO_BG, ACTIVITY_PAUSED -> {
                // 小窗处于后台或非焦点状态，但仍然可见，不做 CLOSED 转换
            }
        }
        return PresenceTrackerAction.NONE
    }

    fun isPackageClosed(pkg: String): Boolean {
        val closedAt = closedAtMap[pkg] ?: 0L
        if (closedAt <= 0L) return false
        val openedAt = lastOpenedAtMap[pkg] ?: 0L
        return closedAt >= openedAt
    }

    /**
     * 检查当前包的 lastTimeVisible 是否合法：
     * 1. 如果包当前处于 CLOSED Tombstone 状态（未收到新的 FG/RESUMED 打开事件），则一律拒绝！
     * 2. 如果当前 Presence 因窗口观测 UNKNOWN 处于待复核状态，UsageStats 不能绕过复核重新建立 Session。
     * 3. 如果 lastTimeVisible <= lastStoppedAt，说明这是关闭小窗时系统刷新的残留时间戳，拒绝！
     */
    fun isLastTimeVisibleValid(pkg: String, lastTimeVisible: Long): Boolean {
        if (isPackageClosed(pkg)) {
            return false
        }
        if (currentPackage == pkg && accessibilityRevalidationRequired) {
            return false
        }
        val lastStopped = lastStoppedAtMap[pkg] ?: 0L
        if (lastStopped > 0L && lastTimeVisible <= lastStopped) {
            return false
        }
        return true
    }

    /**
     * 周期轮询评估：
     * - DESKTOP_GRACE：宽限期结束后，只有 Presence 仍处于已验证状态才允许重新锁；
     * - CONFIRMED_VISIBLE：正常保持锁定；一旦窗口观测 UNKNOWN，则暂停重复锁，等待下一次明确观测；
     * - 成功观测 PRESENT 可恢复锁定，成功观测 ABSENT 则直接清除 Session。
     */
    fun evaluate(nowMs: Long): PresenceTrackerAction {
        return when (state) {
            PresenceTrackerState.NONE -> PresenceTrackerAction.NONE
            PresenceTrackerState.DESKTOP_GRACE -> {
                if (nowMs < graceUntilMs || accessibilityRevalidationRequired) {
                    PresenceTrackerAction.NONE
                } else {
                    state = PresenceTrackerState.CONFIRMED_VISIBLE
                    PresenceTrackerAction.LOCK
                }
            }
            PresenceTrackerState.CONFIRMED_VISIBLE -> {
                if (accessibilityRevalidationRequired) {
                    PresenceTrackerAction.NONE
                } else {
                    PresenceTrackerAction.LOCK
                }
            }
        }
    }

    fun reset() {
        state = PresenceTrackerState.NONE
        currentPackage = null
        confirmedAtMs = 0L
        graceUntilMs = 0L
        accessibilityRevalidationRequired = false
    }
}
