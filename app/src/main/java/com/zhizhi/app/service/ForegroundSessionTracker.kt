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
         * 避免下拉通知栏（SystemUI）、系统导航手势（如 Vivo upslide/systemuiplugin）或弹出输入法误杀当前正在前台运行的应用 Session。
         * 注意：Launcher 桌面绝不是噪声，必须替换并关闭当前 Session。
         */
        fun defaultIsNoisePackage(packageName: String): Boolean {
            if (packageName.isBlank()) return true
            val lower = packageName.lowercase()
            // 桌面 Launcher 绝不能作为噪声过滤，它代表用户回到桌面
            if (lower.contains("launcher") || lower.contains("recents")) return false
            if (lower.contains("systemui") || lower == "com.vivo.upslide") return true
            if (lower.contains(".inputmethod") || lower.contains(".ime") || lower.contains("inputmethod")) return true
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

    // 活跃的前台 Activity 集合 (同 package 内可能存在多个 Activity，例如 A 启动 B 时 A.paused -> B.resumed -> A.stopped)
    private val mResumedActivities = mutableSetOf<String>()

    /**
     * 消费来自 UsageEvents 的生命周期事件。
     *
     * @param packageName 应用包名
     * @param eventType UsageEvents.Event 事件类型
     * @param eventTimeMs 事件时间戳
     * @param className 可选的 Activity 类名。提供时将实现 Activity 级别的生命周期跟踪，
     *                  防止 A -> B 页面切换时 A.STOPPED 误关闭正在前台的 B 页面。
     */
    fun onUsageEvent(
        packageName: String,
        eventType: Int,
        eventTimeMs: Long,
        className: String? = null
    ) {
        if (packageName.isBlank()) return
        if (isNoisePackagePredicate(packageName)) return

        val cleanClass = className?.takeIf { it.isNotBlank() }

        when (eventType) {
            MOVE_TO_FG, ACTIVITY_RESUMED -> {
                val existing = currentSession
                if (existing != null && existing.packageName == packageName) {
                    if (existing.closed) {
                        // 该应用此前关闭/退到后台，现在重新进入前台，开启新活跃 Session
                        mResumedActivities.clear()
                        cleanClass?.let { mResumedActivities.add(it) }
                        currentSession = Session(
                            packageName = packageName,
                            openedAtMs = eventTimeMs,
                            closed = false,
                            source = "USAGE_EVENTS"
                        )
                    } else {
                        // 同一应用持续前台（内部 Activity 切换，如 Activity A -> Activity B）
                        cleanClass?.let { mResumedActivities.add(it) }
                    }
                } else {
                    // 其它应用（如第三方 App 或桌面 Launcher）进入前台，替换当前 Session
                    mResumedActivities.clear()
                    cleanClass?.let { mResumedActivities.add(it) }
                    currentSession = Session(
                        packageName = packageName,
                        openedAtMs = eventTimeMs,
                        closed = false,
                        source = "USAGE_EVENTS"
                    )
                }
            }

            MOVE_TO_BG -> {
                val existing = currentSession
                if (existing != null && existing.packageName == packageName) {
                    if (eventTimeMs >= existing.openedAtMs) {
                        mResumedActivities.clear()
                        currentSession = existing.copy(
                            closed = true,
                            closedAtMs = eventTimeMs
                        )
                    }
                }
            }

            ACTIVITY_STOPPED -> {
                val existing = currentSession
                if (existing != null && existing.packageName == packageName && !existing.closed) {
                    if (eventTimeMs >= existing.openedAtMs) {
                        if (cleanClass != null) {
                            mResumedActivities.remove(cleanClass)
                            // 关键防回归：若当前 package 仍有其它已 resumed 的 Activity（如 A -> B 切换中迟到的 A stopped），
                            // 绝不能误关闭整个 package Session
                            if (mResumedActivities.isEmpty()) {
                                currentSession = existing.copy(
                                    closed = true,
                                    closedAtMs = eventTimeMs
                                )
                            }
                        } else {
                            mResumedActivities.clear()
                            currentSession = existing.copy(
                                closed = true,
                                closedAtMs = eventTimeMs
                            )
                        }
                    }
                }
            }

            ACTIVITY_PAUSED -> {
                val existing = currentSession
                if (existing != null && existing.packageName == packageName && !existing.closed) {
                    if (eventTimeMs >= existing.openedAtMs) {
                        // 若未提供 className，或者无任何 Activity 跟踪时，按 package 级反证关闭 Session
                        if (cleanClass == null && mResumedActivities.isEmpty()) {
                            currentSession = existing.copy(
                                closed = true,
                                closedAtMs = eventTimeMs
                            )
                        }
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
        mResumedActivities.clear()
        if (!existing.closed) {
            currentSession = existing.copy(closed = true, closedAtMs = nowMs)
        }
    }

    /**
     * Keyguard 锁屏：明确反证，关闭当前活跃 Session。
     */
    fun onKeyguardLocked(nowMs: Long) {
        val existing = currentSession ?: return
        mResumedActivities.clear()
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
        mResumedActivities.clear()
        currentSession = null
    }
}

/**
 * 规则边界跃迁即时锁定判定输入参数（纯逻辑数据对象，不依赖 Android Framework）
 */
data class RuleBoundaryLockDecisionInput(
    val isBlockedNow: Boolean,
    val wasPreviouslyBlocked: Boolean = true,
    val isBusinessAllowToBlock: Boolean = false,
    val leftGap: Boolean = false,
    val endedRest: Boolean = false,
    val forceBoundaryCheck: Boolean = false,
    val hasActiveForegroundSession: Boolean,
    val isKeyguardLocked: Boolean,
    val isScreenInteractive: Boolean
)

/**
 * 规则边界跃迁即时锁定门禁逻辑
 */
object RuleBoundaryLockGate {
    /**
     * 判断当前是否处于规则从 ALLOW -> BLOCK 的边沿跃迁（Edge Transition），且具备即时触发锁定的资格。
     *
     * 判定准则：
     * 1. 当前时刻该应用必须处于 BLOCK 范围（isBlockedNow == true）；
     * 2. 必须存在未关闭的活跃前台 Session（hasActiveForegroundSession == true）；
     * 3. 屏幕必须处于交互状态且未处于锁屏状态（!isKeyguardLocked && isScreenInteractive）；
     * 4. 必须发生明确的状态边沿跃迁：
     *    - 显式业务状态从 ALLOW 跨入 BLOCK（isBusinessAllowToBlock == true，解耦前台守卫结果）；
     *    - 或先前未被阻塞（!wasPreviouslyBlocked，如时间窗关闭或刚被规则收紧）；
     *    - 或刚刚离开 Gap 自由时段进入受阻时段（leftGap == true）；
     *    - 或刚刚结束 Rest 休息进入工作时段（endedRest == true）；
     *    - 或由外部信号激活了单次强制边界检测（forceBoundaryCheck == true）。
     * 5. 若已处于持续 BLOCK 稳态（!isBusinessAllowToBlock && wasPreviouslyBlocked && !leftGap && !endedRest && !forceBoundaryCheck），
     *    则坚决不予重复边界锁定，防止无限重锁与守卫穿透。
     */
    fun shouldAllowBoundaryLock(input: RuleBoundaryLockDecisionInput): Boolean {
        if (!input.isBlockedNow) return false
        if (!input.hasActiveForegroundSession) return false
        if (input.isKeyguardLocked) return false
        if (!input.isScreenInteractive) return false

        val isEdgeTransition = input.isBusinessAllowToBlock ||
            !input.wasPreviouslyBlocked ||
            input.leftGap ||
            input.endedRest ||
            input.forceBoundaryCheck
        return isEdgeTransition
    }
}

