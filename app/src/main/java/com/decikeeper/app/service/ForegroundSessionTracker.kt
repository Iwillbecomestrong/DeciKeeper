package com.decikeeper.app.service

/**
 * 业务服务层：未关闭前台会话跟踪器 (ForegroundSessionTracker)。
 *
 * 职责与约束：
 * 1. 负责维护应用在前台的持续会话状态（“曾经明确进入前台，且尚未收到离开证据”）。
 * 2. 纯内存状态机，**禁止跨进程持久化**（进程重启后必须重新通过真实前台事件建立 Session，防止旧缓存误锁）。
 * 3. 消费 UsageEvents（MOVE_TO_FG / ACTIVITY_RESUMED / MOVE_TO_BG / ACTIVITY_PAUSED / ACTIVITY_STOPPED）
 *    及系统级离开信号（屏幕关闭、锁屏等）。
 * 4. 维护单调递增的失效时间水位（mLastInvalidationAtMs），防止息屏/锁屏后被 30s 扫描窗口中的陈旧事件重放重新开启 Session。
 * 5. 严格过滤系统 UI、通知栏及输入法等瞬时干扰，只有第三方应用或桌面切入才视为有效切换。
 * 6. 线程安全保证：所有状态变更均在锁保护下执行。
 */
class ForegroundSessionTracker(
    private val isNoisePackagePredicate: (String) -> Boolean = ::defaultIsNoisePackage
) {
    var onSessionClosed: ((String) -> Unit)? = null

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

        private fun logDebug(msg: String) {
            try {
                android.util.Log.d("ForegroundSession", msg)
            } catch (_: Throwable) {
                // 忽略非 Android JVM 单元测试环境异常
            }
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

    @get:Synchronized
    @set:Synchronized
    var currentSession: Session? = null
        private set

    @Synchronized
    fun currentSessionSnapshot(): Session? = currentSession

    // 🛡️ 单调递增的失效时间水位（解决 Finding 2：防止锁屏/息屏后旧事件重放重新开启 Session）
    private var mLastInvalidationAtMs: Long = 0L

    // 活跃的前台 Activity 集合 (同 package 内可能存在多个 Activity，例如 A 启动 B 时 A.paused -> B.resumed -> A.stopped)
    private val mResumedActivities = mutableSetOf<String>()

    /**
     * 消费来自 UsageEvents 的生命周期事件。
     *
     * @param packageName 应用包名
     * @param eventType UsageEvents.Event 事件类型
     * @param eventTimeMs 事件时间戳
     * @param className 可选的 Activity 类名。提供时将实现 Activity 级别的生命周期跟踪。
     */
    @Synchronized
    fun onUsageEvent(
        packageName: String,
        eventType: Int,
        eventTimeMs: Long,
        className: String? = null
    ) {
        if (packageName.isBlank()) return
        if (isNoisePackagePredicate(packageName)) {
            logDebug("Ignored noise package: $packageName (isNoisePackage=true)")
            return
        }

        // 🛡️ [Session 失效时间水位门禁 (Finding 2)]
        // 息屏、Keyguard 锁屏或显式重置后，任何发生在该时间戳及之前的历史事件（如 30s UsageEvents 扫描出的旧事件）
        // 严禁重新打开或修改 Session
        if (eventTimeMs <= mLastInvalidationAtMs) {
            return
        }

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
                        logDebug("Opened active session for $packageName at $eventTimeMs")
                    } else {
                        // 同一应用持续前台（内部 Activity 切换，如 Activity A -> Activity B）
                        cleanClass?.let { mResumedActivities.add(it) }
                    }
                } else {
                    // 其它应用（如第三方 App 或桌面 Launcher）进入前台，替换当前 Session
                    mResumedActivities.clear()
                    cleanClass?.let { mResumedActivities.add(it) }
                    val prevPkg = existing?.packageName
                    val wasOpen = existing?.closed == false
                    currentSession = Session(
                        packageName = packageName,
                        openedAtMs = eventTimeMs,
                        closed = false,
                        source = "USAGE_EVENTS"
                    )
                    if (prevPkg != null && prevPkg != packageName) {
                        logDebug("Session replaced by $packageName, closed session for $prevPkg")
                        if (wasOpen) {
                            onSessionClosed?.invoke(prevPkg)
                        }
                    } else {
                        logDebug("Opened active session for $packageName at $eventTimeMs")
                    }
                }
            }

            MOVE_TO_BG -> {
                val existing = currentSession
                if (existing != null && existing.packageName == packageName && !existing.closed) {
                    if (eventTimeMs >= existing.openedAtMs) {
                        mResumedActivities.clear()
                        currentSession = existing.copy(
                            closed = true,
                            closedAtMs = eventTimeMs
                        )
                        logDebug("Closed session for $packageName on MOVE_TO_BG at $eventTimeMs")
                        onSessionClosed?.invoke(packageName)
                    }
                }
            }

            ACTIVITY_STOPPED -> {
                val existing = currentSession
                if (existing != null && existing.packageName == packageName && !existing.closed) {
                    if (eventTimeMs >= existing.openedAtMs) {
                        if (cleanClass != null) {
                            mResumedActivities.remove(cleanClass)
                            if (mResumedActivities.isEmpty()) {
                                currentSession = existing.copy(
                                    closed = true,
                                    closedAtMs = eventTimeMs
                                )
                                logDebug("Closed session for $packageName on ACTIVITY_STOPPED at $eventTimeMs")
                                onSessionClosed?.invoke(packageName)
                            }
                        } else {
                            mResumedActivities.clear()
                            currentSession = existing.copy(
                                closed = true,
                                closedAtMs = eventTimeMs
                            )
                            logDebug("Closed session for $packageName on ACTIVITY_STOPPED at $eventTimeMs")
                            onSessionClosed?.invoke(packageName)
                        }
                    }
                }
            }

            ACTIVITY_PAUSED -> {
                val existing = currentSession
                if (existing != null && existing.packageName == packageName && !existing.closed) {
                    if (eventTimeMs >= existing.openedAtMs) {
                        if (cleanClass != null) {
                            mResumedActivities.remove(cleanClass)
                            // 🛡️ [Finding 1 修复] 若该 package 已无其它处于 resumed 的 Activity，必须标记 Session 关闭；
                            // 若是 A.paused -> B.resumed，B.resumed 会紧接着重新激活并维持该 package 的 Session
                            if (mResumedActivities.isEmpty()) {
                                currentSession = existing.copy(
                                    closed = true,
                                    closedAtMs = eventTimeMs
                                )
                                logDebug("Closed session for $packageName on ACTIVITY_PAUSED at $eventTimeMs")
                                onSessionClosed?.invoke(packageName)
                            }
                        } else {
                            mResumedActivities.clear()
                            currentSession = existing.copy(
                                closed = true,
                                closedAtMs = eventTimeMs
                            )
                            logDebug("Closed session for $packageName on ACTIVITY_PAUSED at $eventTimeMs")
                            onSessionClosed?.invoke(packageName)
                        }
                    }
                }
            }
        }
    }

    /**
     * 屏幕关闭：明确反证，关闭当前活跃 Session 并推进失效时间水位。
     */
    @Synchronized
    fun onScreenOff(nowMs: Long) {
        mLastInvalidationAtMs = maxOf(mLastInvalidationAtMs, nowMs)
        mResumedActivities.clear()
        val existing = currentSession ?: return
        if (!existing.closed) {
            currentSession = existing.copy(closed = true, closedAtMs = nowMs)
            logDebug("onScreenOff: closed session at $nowMs")
            onSessionClosed?.invoke(existing.packageName)
        }
    }

    /**
     * Keyguard 锁屏：明确反证，关闭当前活跃 Session 并推进失效时间水位。
     */
    @Synchronized
    fun onKeyguardLocked(nowMs: Long) {
        mLastInvalidationAtMs = maxOf(mLastInvalidationAtMs, nowMs)
        mResumedActivities.clear()
        val existing = currentSession ?: return
        if (!existing.closed) {
            currentSession = existing.copy(closed = true, closedAtMs = nowMs)
            logDebug("onKeyguardLocked: closed session at $nowMs")
            onSessionClosed?.invoke(existing.packageName)
        }
    }

    /**
     * 判断指定应用是否具有未关闭的活跃前台 Session。
     */
    @Synchronized
    fun isSessionActiveFor(packageName: String): Boolean {
        if (packageName.isBlank()) return false
        val session = currentSession ?: return false
        return session.packageName == packageName && !session.closed
    }

    /**
     * 重置状态机（服务销毁或手动重置）。
     */
    @Synchronized
    fun reset() {
        mLastInvalidationAtMs = System.currentTimeMillis()
        mResumedActivities.clear()
        val prev = currentSession?.takeIf { !it.closed }?.packageName
        currentSession = null
        prev?.let { onSessionClosed?.invoke(it) }
    }
}

/**
 * 业务边沿消费状态机（解决 Finding 1：解耦业务受阻检测与锁屏实际交付成功）
 */
enum class BusinessBlockState {
    /** 业务处于允许范围（如非时段内、在Gap时段、白名单等） */
    ALLOWED,
    /** 业务刚从 ALLOW 跨入 BLOCK，但锁屏尚未确认完成交付（待执行或重试中） */
    BLOCK_PENDING,
    /** 锁屏已确认成功交付展示，边沿已消费，处于持续稳态 BLOCK */
    BLOCK_CONSUMED
}

/**
 * 规则边界跃迁即时锁定判定输入参数（纯逻辑数据对象，不依赖 Android Framework）
 */
data class RuleBoundaryLockDecisionInput(
    val isBlockedNow: Boolean,
    val isBusinessAllowToBlock: Boolean,
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
     * 4. 必须发生明确的状态边沿跃迁：isBusinessAllowToBlock == true（边沿唯一真相）；
     * 5. 若已处于持续 BLOCK 稳态（!isBusinessAllowToBlock），则坚决不予重复边界锁定，防止无限重锁与守卫穿透。
     */
    fun shouldAllowBoundaryLock(input: RuleBoundaryLockDecisionInput): Boolean {
        if (!input.isBlockedNow) return false
        if (!input.hasActiveForegroundSession) return false
        if (input.isKeyguardLocked) return false
        if (!input.isScreenInteractive) return false

        // 🛡️ [Finding 4 & 5 修复] 严格要求 isBusinessAllowToBlock == true 作为边沿跃迁的唯一准则
        return input.isBusinessAllowToBlock
    }
}

/**
 * 边界候选判定输入参数（纯逻辑数据对象，不依赖 Android Framework）
 */
data class BoundaryCandidateInput(
    val resolvedPackageName: String?,
    val isCarrierPackage: Boolean,
    val isRecentAppsOrLauncher: Boolean,
    val isLockShowing: Boolean,
    val isInDesktopReturnGrace: Boolean,
    val homeClickPendingPackage: String?,
    val activeSessionPackage: String?,
    val isSessionClosed: Boolean,
    val isSessionNoiseOrLauncherOrSelf: Boolean,
    val isBusinessBlockedNow: Boolean,
    val previousBusinessBlockState: BusinessBlockState? = null,
    val isKeyguardLocked: Boolean,
    val isScreenInteractive: Boolean
)

/**
 * 边界候选判定裁决结果
 */
data class BoundaryCandidateResult(
    val shouldLockCandidate: Boolean,
    val candidatePackage: String? = null,
    val updatedBusinessBlockState: BusinessBlockState? = null,
    val reason: String
)

/**
 * 规则边界候选抢占协调器 (BoundaryCandidateCoordinator)
 *
 * 核心契约：
 * 1. 当常规解析器为 null（静止无事件）、Carrier（如 com.vivo.upslide / vivo launcher）或桌面/最近任务时，
 *    进入候选抢占逻辑，防止静止应用在规则边沿漏锁；
 * 2. 无论 Session 应用当前为 ALLOW 还是 BLOCK，均持续记录业务状态（BusinessBlockState 状态机），
 *    保证从 ALLOW -> BLOCK 跃迁时可被精确捕获；
 * 3. 门禁裁决委托给 RuleBoundaryLockGate，杜绝稳态重复锁定。
 */
object BoundaryCandidateCoordinator {
    fun evaluate(input: BoundaryCandidateInput): BoundaryCandidateResult {
        if (input.isLockShowing) {
            return BoundaryCandidateResult(shouldLockCandidate = false, reason = "LOCK_ALREADY_SHOWING")
        }

        val sessionPkg = input.activeSessionPackage
        if (sessionPkg.isNullOrBlank()) {
            return BoundaryCandidateResult(shouldLockCandidate = false, reason = "NO_ACTIVE_SESSION")
        }
        if (input.isSessionClosed) {
            return BoundaryCandidateResult(shouldLockCandidate = false, reason = "SESSION_CLOSED")
        }
        if (input.isSessionNoiseOrLauncherOrSelf) {
            return BoundaryCandidateResult(shouldLockCandidate = false, reason = "SESSION_PACKAGE_EXEMPT")
        }
        if (input.homeClickPendingPackage != null && input.homeClickPendingPackage == sessionPkg) {
            return BoundaryCandidateResult(shouldLockCandidate = false, reason = "HOME_CLICK_PENDING_SUPPRESSION")
        }

        // 常规解析器为 null、Carrier（如 com.vivo.upslide / vivo launcher）或桌面/最近任务时，进入抢占候选判定
        val qualifiesForCandidatePreemption = input.resolvedPackageName == null ||
            input.isCarrierPackage ||
            input.isRecentAppsOrLauncher
        if (!qualifiesForCandidatePreemption) {
            return BoundaryCandidateResult(shouldLockCandidate = false, reason = "RESOLVED_PACKAGE_NON_CARRIER")
        }

        val previousState = input.previousBusinessBlockState ?: BusinessBlockState.ALLOWED

        if (!input.isBusinessBlockedNow) {
            return BoundaryCandidateResult(
                shouldLockCandidate = false,
                candidatePackage = sessionPkg,
                updatedBusinessBlockState = BusinessBlockState.ALLOWED,
                reason = "SESSION_BUSINESS_ALLOWED"
            )
        }

        // 🛡️ [Finding 1 修复] 边沿跃迁判定：从 ALLOWED 或尚未完成交付的 BLOCK_PENDING 跨入 BLOCK，均构成有效边沿；
        // 只有已成功交付锁屏的 BLOCK_CONSUMED 状态才属于稳态
        val isBusinessAllowToBlock = (previousState == BusinessBlockState.ALLOWED || previousState == BusinessBlockState.BLOCK_PENDING)

        // 🛡️ [Finding 2 修复] 用户明确回桌面已有 homeClickPendingPackage 与 Session 关闭反证；
        // 若第三方受限 App Session 持续活跃未关闭，且当前恰好发生 ALLOW -> BLOCK 业务边沿跃迁，
        // 绝不能被上一周期由 resolver 误报 Launcher 留下的残余 generic Desktop Grace 拦截！
        if (input.isInDesktopReturnGrace && !isBusinessAllowToBlock) {
            return BoundaryCandidateResult(shouldLockCandidate = false, reason = "DESKTOP_RETURN_GRACE")
        }

        val allowByBoundary = RuleBoundaryLockGate.shouldAllowBoundaryLock(
            RuleBoundaryLockDecisionInput(
                isBlockedNow = true,
                isBusinessAllowToBlock = isBusinessAllowToBlock,
                hasActiveForegroundSession = true,
                isKeyguardLocked = input.isKeyguardLocked,
                isScreenInteractive = input.isScreenInteractive
            )
        )

        return if (allowByBoundary) {
            BoundaryCandidateResult(
                shouldLockCandidate = true,
                candidatePackage = sessionPkg,
                updatedBusinessBlockState = BusinessBlockState.BLOCK_PENDING,
                reason = "BOUNDARY_LOCK_TRIGGERED"
            )
        } else {
            BoundaryCandidateResult(
                shouldLockCandidate = false,
                candidatePackage = sessionPkg,
                updatedBusinessBlockState = BusinessBlockState.BLOCK_CONSUMED,
                reason = "BOUNDARY_GATE_REJECTED"
            )
        }
    }
}
