package com.zhizhi.app.data

import com.zhizhi.app.util.DiagnosticTimestampFormatter
internal data class ForegroundDetectionLogState(
    val packageName: String,
    val evidence: String = "UNKNOWN",
    val isDndLocked: Boolean = false,
    val isFreeTime: Boolean = false,
    val freeTimeRemainSeconds: Long = 0,
    val temporaryAccessRemainSeconds: Long = 0,
    val temporaryCooldownRemainSeconds: Long = 0,
    val restCooldownRemainSeconds: Long = 0,
    val remainingMinutes: Double = 0.0,
    val remainingUsableCount: Int = 0,
    val isLocked: Boolean = false,
    val homePendingPackage: String? = null,
    val detectionTimestamp: Long = 0L,
    val secondaryPackage: String? = null,
    val secondaryEvidence: String? = null
)

/**
 * 前台检测日志 — 原始数据：来源 + 主/候选应用 + 时间戳
 */
internal fun buildForegroundDetectionLogDetail(
    state: ForegroundDetectionLogState,
    previous: ForegroundDetectionLogState? = null
): String {
    return buildString {
        append("FG pkg=${state.packageName}")
        append(" | src=${state.evidence}")
        if (state.secondaryPackage != null) {
            append(" | alt=${state.secondaryPackage}(${state.secondaryEvidence ?: "?"})")
        }
        append(" | ts=${DiagnosticTimestampFormatter.msWithRaw(state.detectionTimestamp)}")
        append(" | quota=${formatOneDecimalForForegroundLog(state.remainingMinutes)}min/${state.remainingUsableCount}次")
        if (state.isFreeTime) append(" | rest=true(${state.freeTimeRemainSeconds}s)")
        if (state.isLocked) append(" | locked=true")
        if (state.temporaryAccessRemainSeconds > 0) {
            append(" | temp=${state.temporaryAccessRemainSeconds}s")
        }
        if (state.restCooldownRemainSeconds > 0) {
            append(" | cool=${state.restCooldownRemainSeconds}s")
        }
        if (state.temporaryCooldownRemainSeconds > 0) {
            append(" | tempCool=${state.temporaryCooldownRemainSeconds}s")
        }
        if (state.homePendingPackage != null) {
            append(" | home=${state.homePendingPackage}")
        }
    }
}

private fun buildForegroundDetectionSummary(
    state: ForegroundDetectionLogState,
    count: Int,
    firstAtMs: Long,
    lastAtMs: Long
): String {
    val durationMs = (lastAtMs - firstAtMs).coerceAtLeast(0L)
    return "FG_SUMMARY pkg=${state.packageName} | count=$count | duration=${durationMs}ms | quota=${formatOneDecimalForForegroundLog(state.remainingMinutes)}min/${state.remainingUsableCount}次"
}

internal class ForegroundDetectionLogCompressor {
    private var lastState: ForegroundDetectionLogState? = null
    private var lastStableKey: StableStateKey? = null
    private var repeatCount: Int = 0
    private var firstAtMs: Long = 0L
    private var lastAtMs: Long = 0L
    private var currentRunWasEmitted: Boolean = false
    private var lastSignificantState: SignificantState = SignificantState.IDLE

    private enum class SignificantState {
        IDLE, LOCKED, REST, TEMP_ACCESS
    }

    private data class StableStateKey(
        val packageName: String,
        val evidence: String,
        val isDndLocked: Boolean,
        val isFreeTime: Boolean,
        val isLocked: Boolean,
        val homePendingPackage: String?,
        val secondaryPackage: String?,
        val secondaryEvidence: String?
    )

    private fun stableKey(state: ForegroundDetectionLogState) = StableStateKey(
        packageName = state.packageName,
        evidence = state.evidence,
        isDndLocked = state.isDndLocked,
        isFreeTime = state.isFreeTime,
        isLocked = state.isLocked,
        homePendingPackage = state.homePendingPackage,
        secondaryPackage = state.secondaryPackage,
        secondaryEvidence = state.secondaryEvidence
    )

    /** 判断是否属于"静默"状态：未锁屏、不在休息、不在临时解锁期间 */
    private fun isSilentState(s: ForegroundDetectionLogState): Boolean {
        return !s.isLocked && !s.isFreeTime && s.temporaryAccessRemainSeconds <= 0
    }

    /** 判断状态是否发生了关键转变（需要打印） */
    private fun isSignificantTransition(prev: ForegroundDetectionLogState, curr: ForegroundDetectionLogState): Boolean {
        if (prev.isLocked != curr.isLocked) return true
        if (prev.isFreeTime != curr.isFreeTime) return true
        if ((prev.temporaryAccessRemainSeconds > 0) != (curr.temporaryAccessRemainSeconds > 0)) return true
        return false
    }

    fun observe(state: ForegroundDetectionLogState, nowMs: Long): List<String> {
        val previous = lastState
        if (previous == null) {
            lastState = state
            lastStableKey = stableKey(state)
            repeatCount = 1
            firstAtMs = nowMs
            lastAtMs = nowMs
            lastSignificantState = if (state.isLocked) SignificantState.LOCKED
                else if (state.isFreeTime) SignificantState.REST
                else if (state.temporaryAccessRemainSeconds > 0) SignificantState.TEMP_ACCESS
                else SignificantState.IDLE
            currentRunWasEmitted = !isSilentState(state)
            return if (currentRunWasEmitted) listOf(buildForegroundDetectionLogDetail(state)) else emptyList()
        }

        val currentStableKey = stableKey(state)
        if (lastStableKey == currentStableKey) {
            lastState = state
            repeatCount += 1
            lastAtMs = nowMs
            return emptyList()
        }

        val previousSummary = buildForegroundDetectionSummary(
            previous,
            repeatCount,
            firstAtMs,
            lastAtMs
        )
        val previousRunWasEmitted = currentRunWasEmitted

        lastState = state
        lastStableKey = currentStableKey
        repeatCount = 1
        firstAtMs = nowMs
        lastAtMs = nowMs

        val transition = isSignificantTransition(previous, state)
        val newSigState = if (state.isLocked) SignificantState.LOCKED
            else if (state.isFreeTime) SignificantState.REST
            else if (state.temporaryAccessRemainSeconds > 0) SignificantState.TEMP_ACCESS
            else SignificantState.IDLE
        val stateChanged = newSigState != lastSignificantState
        lastSignificantState = newSigState

        if (!transition && !stateChanged && isSilentState(state)) {
            currentRunWasEmitted = false
            return emptyList()
        }

        val output = mutableListOf<String>()
        if (previousRunWasEmitted) output += previousSummary
        output += buildForegroundDetectionLogDetail(state, previous)
        currentRunWasEmitted = true
        return output
    }
}

private fun formatOneDecimalForForegroundLog(value: Double): String {
    return if (kotlin.math.abs(value - value.toLong()) < 0.0001) {
        value.toLong().toString()
    } else {
        "%.1f".format(java.util.Locale.US, value)
    }
}
