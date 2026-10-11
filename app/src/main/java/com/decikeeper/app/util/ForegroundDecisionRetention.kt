package com.decikeeper.app.util

import com.decikeeper.app.data.ForegroundDiagnosticCycle
import com.decikeeper.app.data.ForegroundReportDecision
import com.decikeeper.app.data.ForegroundReportTrigger
import com.decikeeper.app.data.ForegroundSourceSnapshot
import com.decikeeper.app.data.ForegroundSourceStatus

/**
 * 决定一份 8 行 FG_DECISION 完整报告是否值得长期保留。纯日志取舍，没有任何业务决策权。
 *
 * 长期保留（LONG，进永久账本）：请求上锁、漏锁、真正的来源故障及其恢复。
 * 短期保留（SHORT，进近期诊断）：来源之间意见分歧（常在前台切换瞬间出现）。
 * 不保留：前台应用长时间没有切换时，UsageEvents / UsageStats 查不到事件的"正常安静窗口"
 * （`unavailableReason` 为 [BENIGN_UNAVAILABLE_REASONS]）所触发的来源故障，
 * 这类状态在 vivo 上会在 NO_DATA 与 AVAILABLE 间来回翻，每翻一次就是一份完整报告。
 * 其前台判定情况由 5 秒一次的前台采样（最多保留 3 分钟）覆盖。
 *
 * 带状态：记住当前来源问题是否包含真故障，以便决定"恢复"报告是否保留。
 */
class ForegroundDecisionRetention {

    /** 保留等级：DROP 不写；SHORT 进近期诊断（可能每次前台切换都会出现）；LONG 进永久账本。 */
    enum class Level { DROP, SHORT, LONG }

    private var realFaultActive = false

    fun shouldRetain(decision: ForegroundReportDecision, cycle: ForegroundDiagnosticCycle): Boolean =
        assess(decision, cycle) != Level.DROP

    fun assess(decision: ForegroundReportDecision, cycle: ForegroundDiagnosticCycle): Level {
        val triggers = decision.triggers
        // 没有触发原因的完整报告只来自显式调用，按原样长期保留。
        if (triggers.isEmpty()) return Level.LONG

        var level = Level.DROP
        fun raise(candidate: Level) {
            if (candidate.ordinal > level.ordinal) level = candidate
        }

        if (triggers.any { it in LOCK_RELEVANT }) raise(Level.LONG)
        // 来源之间意见分歧常在前台切换的瞬间出现，保留但只进近期诊断。
        if (triggers.any { it in DISPUTE }) raise(Level.SHORT)

        if (triggers.any { it in FAULT }) {
            val realFault = cycle.sourceList().any(::isRealFault)
            realFaultActive = realFault
            if (realFault) raise(Level.LONG)
        }
        if (ForegroundReportTrigger.SOURCE_RECOVERED in triggers) {
            if (realFaultActive) raise(Level.LONG)
            realFaultActive = false
        }
        return level
    }

    companion object {
        private val LOCK_RELEVANT = setOf(
            ForegroundReportTrigger.REQUEST_LOCK,
            ForegroundReportTrigger.MISSED_LOCK_STARTED,
            ForegroundReportTrigger.MISSED_LOCK_CHANGED
        )
        private val DISPUTE = setOf(
            ForegroundReportTrigger.SOURCE_DISPUTE_STARTED,
            ForegroundReportTrigger.SOURCE_DISPUTE_CHANGED
        )
        private val FAULT = setOf(
            ForegroundReportTrigger.SOURCE_FAULT_STARTED,
            ForegroundReportTrigger.SOURCE_FAULT_CHANGED
        )

        /** 30 秒内没有前台事件、或使用统计为空：正常安静，不是故障。 */
        val BENIGN_UNAVAILABLE_REASONS = setOf(
            "NO_RECENT_FOREGROUND_EVENT",
            "USAGE_STATS_MANAGER_NULL_OR_EMPTY"
        )

        /** 真故障：调用失败、带失败类别、无障碍未启用，或查询已发起但未返回。 */
        fun isRealFault(source: ForegroundSourceSnapshot): Boolean = when {
            source.status == ForegroundSourceStatus.FAILED -> true
            !source.failureClass.isNullOrBlank() -> true
            source.status == ForegroundSourceStatus.UNAVAILABLE ->
                source.unavailableReason !in BENIGN_UNAVAILABLE_REASONS
            source.status == ForegroundSourceStatus.INITIALIZING ->
                source.queryStartedAtMs != null && source.queryFinishedAtMs == null
            else -> !source.unavailableReason.isNullOrBlank() &&
                source.unavailableReason !in BENIGN_UNAVAILABLE_REASONS
        }
    }
}
