package com.decikeeper.app.service

import android.content.Context
import android.util.Log
import com.decikeeper.app.manager.FloatingWindowProtectionManager

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
    val desktopGraceDurationMs: Long = 3000L,
    val requiredConsecutiveAbsenceCount: Int = 3,
    private val protectionContext: Context? = null
) {
    companion object {
        private const val PRESENCE_DIAG_TAG = "PresenceDiag"
    }

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

    var consecutiveConfirmedAbsenceCount: Int = 0
        private set

    var lastConfirmedAbsentAtMs: Long = 0L
        private set

    val lastStoppedAtMap = mutableMapOf<String, Long>()
    val closedAtMap = mutableMapOf<String, Long>()
    val lastOpenedAtMap = mutableMapOf<String, Long>()

    /**
     * 每个包最近一次恢复到前台的 Activity 类名。
     *
     * 应用内部切换页面时，系统先恢复新页面（B RESUMED），随后才停止旧页面（A STOPPED）。
     * 若不区分类名，这条 A STOPPED 会把仍在屏幕上的应用误标成「已关闭」：真机日志里微信
     * 从聊天详情页回到主界面后，小窗还在，却被墓碑挡住长达约 30 秒不重新上锁。
     */
    private val lastResumedClassMap = mutableMapOf<String, String>()

    private val MOVE_TO_FG = 1
    private val MOVE_TO_BG = 2
    private val ACTIVITY_RESUMED = 15
    private val ACTIVITY_PAUSED = 16
    private val ACTIVITY_STOPPED = 23

    private fun logStateChange(
        pkg: String,
        from: PresenceTrackerState,
        to: PresenceTrackerState,
        reason: String
    ) {
        if (from != to) {
            Log.i(
                PRESENCE_DIAG_TAG,
                "[PRESENCE_STATE] pkg=$pkg from=$from to=$to reason=$reason revalidationRequired=$accessibilityRevalidationRequired"
            )
        }
    }

    private fun setRevalidationRequired(value: Boolean, reason: String) {
        if (accessibilityRevalidationRequired != value) {
            accessibilityRevalidationRequired = value
            val pkg = currentPackage ?: "unknown"
            Log.i(
                PRESENCE_DIAG_TAG,
                "[PRESENCE_STATE] pkg=$pkg revalidationRequired=$value reason=$reason"
            )
        }
    }

    private fun logAction(action: PresenceTrackerAction, pkg: String, reason: String) {
        Log.i(
            PRESENCE_DIAG_TAG,
            "[PRESENCE_ACTION] action=$action pkg=$pkg reason=$reason"
        )
    }

    /**
     * Presence 小窗能力属于 Accessibility 增强能力：
     * - 无障碍服务不可用时，UsageStats.lastTimeVisible 不能单独证明小窗仍在屏幕上；
     * - 无障碍可用时，也必须在当前窗口集合中真实看到目标包，才允许把 UsageStats
     *   的时间戳当作“小窗仍存在”的辅助证据。
     *
     * 普通全屏前台应用的 UsageEvents / ForegroundGuard 链路不经过这里，不受影响。
     */
    private fun hasAccessibilityBackedPresence(pkg: String): Boolean {
        if (!CoreService.isServiceActive) return false
        if (protectionContext != null && !FloatingWindowProtectionManager.isEnforcing(protectionContext)) return false
        return CoreService.lastVisiblePackagesFromWindows.contains(pkg)
    }

    /**
     * 首次 Presence 连续确认
     */
    fun onPresenceConfirmed(pkg: String, nowMs: Long): PresenceTrackerAction {
        val oldState = state
        // Presence 确认是“可见性确认”，不是新的真实 lifecycle OPEN 事件。
        // 如果此前已经收到 FG/RESUMED，就保留那个真实 openedAt；否则才以当前时刻作为兜底。
        // 这样同一轮稍后到达、但 eventTime 略早于 confirmedAt 的 STOPPED 仍能正确关闭当前 Session。
        if (!lastOpenedAtMap.containsKey(pkg)) {
            lastOpenedAtMap[pkg] = nowMs
        }
        closedAtMap.remove(pkg)
        currentPackage = pkg
        confirmedAtMs = nowMs
        state = PresenceTrackerState.CONFIRMED_VISIBLE
        graceUntilMs = 0L
        consecutiveConfirmedAbsenceCount = 0
        lastConfirmedAbsentAtMs = 0L
        setRevalidationRequired(false, reason = "PRESENCE_CONFIRMED")
        logStateChange(pkg, oldState, state, reason = "PRESENCE_CONFIRMED")
        logAction(PresenceTrackerAction.LOCK, pkg, reason = "PRESENCE_CONFIRMED")
        return PresenceTrackerAction.LOCK
    }

    /**
     * 用户点击“回到桌面”或切回桌面，进入 Grace 宽限期。
     */
    fun onDesktopReturn(nowMs: Long, sharedGraceUntilMs: Long = nowMs + desktopGraceDurationMs) {
        if (state == PresenceTrackerState.CONFIRMED_VISIBLE || state == PresenceTrackerState.DESKTOP_GRACE) {
            val oldState = state
            val deadline = if (state == PresenceTrackerState.DESKTOP_GRACE) {
                minOf(graceUntilMs, sharedGraceUntilMs)
            } else sharedGraceUntilMs
            state = PresenceTrackerState.DESKTOP_GRACE
            graceUntilMs = deadline
            logStateChange(currentPackage ?: "unknown", oldState, state, reason = "DESKTOP_RETURN")
        }
    }

    /** 共享返回策略可撤销宽限，但不能制造可见性或新的锁屏理由。 */
    fun limitDesktopGrace(sharedGraceUntilMs: Long) {
        if (state == PresenceTrackerState.DESKTOP_GRACE) {
            graceUntilMs = minOf(graceUntilMs, sharedGraceUntilMs)
        }
    }

    /**
     * 基于单一窗口快照的运行时入口。
     */
    fun onAccessibilitySnapshot(snapshot: AccessibilityWindowSnapshot, nowMs: Long): PresenceTrackerAction {
        val current = currentPackage ?: return PresenceTrackerAction.NONE
        if (state == PresenceTrackerState.NONE) return PresenceTrackerAction.NONE

        val observation = AccessibilityWindowPresenceProbe.observe(current, snapshot)
        return processObservation(current, observation, snapshot.packages, nowMs)
    }

    /**
     * 可测试的窗口快照入口。
     */
    fun onAccessibilitySnapshot(
        visiblePackages: Set<String>,
        nowMs: Long,
        scanSucceeded: Boolean = true
    ): PresenceTrackerAction {
        val current = currentPackage ?: return PresenceTrackerAction.NONE
        if (state == PresenceTrackerState.NONE) return PresenceTrackerAction.NONE

        if (!scanSucceeded) {
            return processObservation(
                current,
                AccessibilityWindowPresenceObservation.UNKNOWN,
                visiblePackages,
                nowMs
            )
        }

        val observation = if (visiblePackages.contains(current)) {
            AccessibilityWindowPresenceObservation.PRESENT
        } else {
            AccessibilityWindowPresenceObservation.ABSENT
        }

        return processObservation(current, observation, visiblePackages, nowMs)
    }

    private fun processObservation(
        current: String,
        observation: AccessibilityWindowPresenceObservation,
        visiblePackages: Set<String>,
        nowMs: Long
    ): PresenceTrackerAction {
        return when (observation) {
            AccessibilityWindowPresenceObservation.PRESENT -> {
                consecutiveConfirmedAbsenceCount = 0
                setRevalidationRequired(false, reason = "TARGET_OBSERVED_PRESENT")
                PresenceTrackerAction.NONE
            }
            AccessibilityWindowPresenceObservation.UNKNOWN -> {
                // UNKNOWN: 窗口处于不可观察或异常状态，不能增加 absenceCount，不能清除 Session，不能写 STOPPED
                setRevalidationRequired(true, reason = "A11Y_OBSERVATION_UNKNOWN")
                PresenceTrackerAction.NONE
            }
            AccessibilityWindowPresenceObservation.ABSENT -> {
                consecutiveConfirmedAbsenceCount++
                lastConfirmedAbsentAtMs = nowMs
                Log.i(
                    PRESENCE_DIAG_TAG,
                    "[PRESENCE_ABSENCE_COUNT] pkg=$current count=$consecutiveConfirmedAbsenceCount/$requiredConsecutiveAbsenceCount"
                )

                if (consecutiveConfirmedAbsenceCount >= requiredConsecutiveAbsenceCount) {
                    val oldState = state
                    closedAtMap[current] = nowMs
                    // ⚠️ 关键约束：绝不能写入 lastStoppedAtMap！lastStoppedAtMap 只允许真实 ACTIVITY_STOPPED 事件更新！
                    resetInternal()
                    logStateChange(
                        current,
                        oldState,
                        PresenceTrackerState.NONE,
                        reason = "A11Y_CONFIRMED_ABSENT(count=$consecutiveConfirmedAbsenceCount)"
                    )
                    logAction(
                        PresenceTrackerAction.CLEAR,
                        current,
                        reason = "A11Y_CONFIRMED_ABSENT"
                    )
                    PresenceTrackerAction.CLEAR
                } else {
                    PresenceTrackerAction.NONE
                }
            }
        }
    }

    /**
     * 收到 UsageEvent（MOVE_TO_FG / ACTIVITY_RESUMED / MOVE_TO_BG / ACTIVITY_PAUSED / ACTIVITY_STOPPED）
     */
    fun onUsageEvent(
        pkg: String,
        eventType: Int,
        eventTimeMs: Long,
        className: String? = null
    ): PresenceTrackerAction {
        val cleanClass = className?.takeIf { it.isNotBlank() }
        when (eventType) {
            ACTIVITY_STOPPED -> {
                // 同一应用内「新页面已恢复、旧页面才停止」不是关闭：应用仍在前台，不得写墓碑、不得清 Session。
                // 只有停止的恰好是最近恢复的那个 Activity（或拿不到类名）时才按关闭处理。
                val lastResumed = lastResumedClassMap[pkg]
                if (cleanClass != null && lastResumed != null && lastResumed != cleanClass) {
                    return PresenceTrackerAction.NONE
                }
                // ACTIVITY_STOPPED is authoritative lifecycle evidence that the Activity is no longer visible.
                // A final Accessibility event commonly arrives immediately before STOPPED while a floating
                // window is closing; that recent event must not suppress the close tombstone or revive Presence.
                lastStoppedAtMap[pkg] = eventTimeMs
                closedAtMap[pkg] = eventTimeMs
                // 🛡️ STOPPED 是否属于当前 Session，应以“最后一次真实打开事件”为边界，而不是
                // Presence 的 wall-clock confirmedAt。Presence 可能在同一轮先于 UsageEvents 被确认，
                // 此时合法 STOPPED 的 eventTime 会略早于 confirmedAt，但仍晚于真实 FG/RESUMED。
                val currentOpenedAt = lastOpenedAtMap[pkg] ?: 0L
                val stoppedBelongsToCurrentLifecycle = currentOpenedAt > 0L && eventTimeMs >= currentOpenedAt
                if (currentPackage == pkg && state != PresenceTrackerState.NONE && stoppedBelongsToCurrentLifecycle) {
                    val oldState = state
                    resetInternal()
                    logStateChange(
                        pkg,
                        oldState,
                        PresenceTrackerState.NONE,
                        reason = "USAGE_ACTIVITY_STOPPED(eventTime=$eventTimeMs>=openedAt=$currentOpenedAt)"
                    )
                    logAction(PresenceTrackerAction.CLEAR, pkg, reason = "USAGE_ACTIVITY_STOPPED")
                    return PresenceTrackerAction.CLEAR
                }
            }
            MOVE_TO_FG, ACTIVITY_RESUMED -> {
                if (cleanClass != null) {
                    lastResumedClassMap[pkg] = cleanClass
                }
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

    /** 诊断用：当前处于已关闭墓碑的包（只读快照，不改变任何状态）。 */
    fun closedPackagesSnapshot(): Set<String> =
        closedAtMap.keys.toList().filter { isPackageClosed(it) }.toSet()

    fun isPackageClosed(pkg: String): Boolean {
        val closedAt = closedAtMap[pkg] ?: 0L
        if (closedAt <= 0L) return false
        val openedAt = lastOpenedAtMap[pkg] ?: 0L
        return closedAt >= openedAt
    }

    /**
     * 包是否「物理上仍在屏幕上」：必须出现在无障碍可见窗口集合里，且没有被 ACTIVITY_STOPPED 关闭墓碑标记。
     *
     * 无障碍的事件-快照融合会把最近 3 秒发过事件的包也算进可见集合；小窗关闭时自身的关闭事件
     * 会让它在关闭后继续滞留 3 秒。若只看集合成员资格，回桌面宽限一结束就会把刚关掉的小窗当成
     * 仍然可见，清掉回桌面抑制并重新上锁。ACTIVITY_STOPPED 是权威生命周期证据，必须压过这种滞留。
     * 再次出现 MOVE_TO_FG / ACTIVITY_RESUMED 时墓碑自动失效，重新打开的小窗照常可见。
     */
    fun isPhysicallyVisible(pkg: String, a11yVisiblePackages: Set<String>): Boolean =
        a11yVisiblePackages.contains(pkg) && !isPackageClosed(pkg)

    /**
     * 检查当前包的 lastTimeVisible 是否合法：
     * 1. 小窗 Presence 必须有 Accessibility 当前窗口集合的同包佐证；无障碍不可用时一律拒绝。
     * 2. 如果包当前处于 CLOSED Tombstone 状态（未收到新的 FG/RESUMED 打开事件），则一律拒绝！
     * 3. 如果当前 Presence 因窗口观测 UNKNOWN 处于待复核状态，UsageStats 不能绕过复核重新建立 Session。
     * 4. 如果 lastTimeVisible <= lastStoppedAt，说明这是关闭小窗时系统刷新的残留时间戳，拒绝！
     */
    fun isLastTimeVisibleValid(pkg: String, lastTimeVisible: Long): Boolean {
        if (!hasAccessibilityBackedPresence(pkg)) {
            return false
        }
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
     * - Presence 重锁只属于 Accessibility 增强能力；无障碍不可用时永不根据历史 Presence 重锁；
     * - DESKTOP_GRACE：宽限期结束后，必须仍能在 Accessibility 当前窗口集合中看到目标包才允许重新锁；
     * - CONFIRMED_VISIBLE：正常保持锁定；一旦窗口观测 UNKNOWN，则暂停重复锁，等待下一次明确观测；
     * - 成功观测 PRESENT 可恢复锁定，成功观测 ABSENT 则只清除 Presence Session，不代表撤销锁屏。
     */
    fun evaluate(nowMs: Long): PresenceTrackerAction {
        val current = currentPackage ?: "unknown"
        if (state == PresenceTrackerState.NONE) {
            return PresenceTrackerAction.NONE
        }
        if (!CoreService.isServiceActive) {
            setRevalidationRequired(true, reason = "A11Y_UNAVAILABLE")
            return PresenceTrackerAction.NONE
        }
        if (!hasAccessibilityBackedPresence(current)) {
            return PresenceTrackerAction.NONE
        }

        return when (state) {
            PresenceTrackerState.NONE -> PresenceTrackerAction.NONE
            PresenceTrackerState.DESKTOP_GRACE -> {
                if (nowMs < graceUntilMs || accessibilityRevalidationRequired) {
                    PresenceTrackerAction.NONE
                } else {
                    val oldState = state
                    state = PresenceTrackerState.CONFIRMED_VISIBLE
                    logStateChange(current, oldState, state, reason = "DESKTOP_GRACE_EXPIRED_REVALIDATED")
                    logAction(PresenceTrackerAction.LOCK, current, reason = "DESKTOP_GRACE_EXPIRED_REVALIDATED")
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

    fun reset(reason: String = "MANUAL_RESET") {
        val current = currentPackage ?: "unknown"
        val oldState = state
        resetInternal()
        if (oldState != PresenceTrackerState.NONE) {
            logStateChange(current, oldState, PresenceTrackerState.NONE, reason = reason)
        }
    }

    private fun resetInternal() {
        state = PresenceTrackerState.NONE
        currentPackage = null
        confirmedAtMs = 0L
        graceUntilMs = 0L
        accessibilityRevalidationRequired = false
        consecutiveConfirmedAbsenceCount = 0
        lastConfirmedAbsentAtMs = 0L
    }
}
