package com.zhizhi.app.manager

/**
 * 封装前台证据数据，供 ForegroundEvidencePolicy 判定。
 */
data class ForegroundEvidence(
    val packageName: String,
    val foregroundEventAtMs: Long,
    val backgroundEventAtMs: Long,
    val lastTimeVisibleMs: Long,
    val checkedAtMs: Long
)

/**
 * 前台证据判定结果枚举。
 */
enum class ForegroundEvidenceDecision {
    CONFIRMED,
    NO_FOREGROUND_EVENT,
    BACKGROUND_EVENT_NEWER,
    VISIBLE_DATA_PENDING,
    VISIBLE_NOT_CORRELATED
}

/**
 * 纯策略单例：前台证据因果关联判定。
 * 核心原则：以 fgEventAtMs 为主证据，验证 lastTimeVisibleMs 是否与其因果关联，而非要求 lastTimeVisible 必须贴近 checkedAtMs (now)。
 */
object ForegroundEvidencePolicy {

    /** 允许系统在 UsageEvents 发出前稍微提前记录 lastTimeVisible（或时钟微小抖动）的容差 */
    const val PRE_EVENT_TOLERANCE_MS = 1_000L

    /** 允许未来时钟微小抖动的容差 */
    const val CLOCK_SKEW_TOLERANCE_MS = 1_000L

    /** 前台事件刚发生时，等待系统 UsageStats 刷新落盘的最大宽限期 */
    const val PENDING_GRACE_MS = 1_500L

    /** 前台事件的最大有效期，超过此时间未发起锁机则不能凭历史事件锁定（防止 ROM 丢失 BG 事件） */
    const val MAX_FOREGROUND_EVENT_AGE_MS = 8_000L

    /** 快速复查轮询周期 */
    const val FAST_RECHECK_INTERVAL_MS = 150L

    /**
     * 评估前台证据。
     */
    fun evaluate(evidence: ForegroundEvidence): ForegroundEvidenceDecision {
        val fg = evidence.foregroundEventAtMs
        val bg = evidence.backgroundEventAtMs
        val visible = evidence.lastTimeVisibleMs
        val check = evidence.checkedAtMs

        // 1. 无前台事件
        if (fg <= 0L) {
            return ForegroundEvidenceDecision.NO_FOREGROUND_EVENT
        }

        // 2. 前台事件发生在遥远的未来（时钟异常）
        if (fg > (check + CLOCK_SKEW_TOLERANCE_MS)) {
            return ForegroundEvidenceDecision.VISIBLE_NOT_CORRELATED
        }

        // 3. 前台事件已经过期（超过 8 秒最大有效期）
        if ((check - fg) > MAX_FOREGROUND_EVENT_AGE_MS) {
            return ForegroundEvidenceDecision.VISIBLE_NOT_CORRELATED
        }

        // 4. 存在更新或同时发生的后台事件（说明应用已切出）
        if (bg > 0L && bg >= fg) {
            return ForegroundEvidenceDecision.BACKGROUND_EVENT_NEWER
        }

        // 5. 非对称因果判定：
        //    lastTimeVisible 产生于前台事件发生附近或之后，且不超过当前检查时间+时钟容差
        if (visible >= (fg - PRE_EVENT_TOLERANCE_MS) && visible <= (check + CLOCK_SKEW_TOLERANCE_MS)) {
            return ForegroundEvidenceDecision.CONFIRMED
        }

        // 6. lastTimeVisible 明显早于 fgEventAtMs（可能是旧数据尚未刷新）：
        //    如果本次前台事件发生时间距当前检查时刻仍在宽限期内，等待系统落盘
        if (visible < (fg - PRE_EVENT_TOLERANCE_MS) && (check - fg) <= PENDING_GRACE_MS) {
            return ForegroundEvidenceDecision.VISIBLE_DATA_PENDING
        }

        // 7. 其它情况（超过宽限期依然无法关联到合法的可见性时间戳）
        return ForegroundEvidenceDecision.VISIBLE_NOT_CORRELATED
    }

    /**
     * 是否通过 Foreground Guard。
     */
    fun isGuardPassed(decision: ForegroundEvidenceDecision): Boolean {
        return decision == ForegroundEvidenceDecision.CONFIRMED
    }

    /**
     * 是否需要触发 150ms 快速复查 (Fast Recheck)。
     */
    fun shouldRequestFastRecheck(decision: ForegroundEvidenceDecision): Boolean {
        return decision == ForegroundEvidenceDecision.VISIBLE_DATA_PENDING
    }
}
