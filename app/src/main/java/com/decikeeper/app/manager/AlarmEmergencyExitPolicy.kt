package com.decikeeper.app.manager

import java.util.Calendar
import java.util.TimeZone

/**
 * 闹钟挑战「应急退出」的纯 Kotlin 规则：每月可用次数配置、加严立即/放宽延期/当日可恢复基准、
 * 业务月额度。不依赖 Android；持久化与 UI 由 [AlarmEmergencyExitStore] 与覆盖层负责。
 */
internal object AlarmEmergencyExitPolicy {
    const val MIN_MONTHLY = 0
    const val MAX_MONTHLY = 3
    const val DEFAULT_MONTHLY = 1

    /**
     * @property active 当前生效的每月次数
     * @property baseline 业务日刷新时刻快照的稳定基准；同一业务日内调小后可恢复到它
     * @property pendingValue 放宽（调大）后等待下个自然月刷新时刻生效的值
     * @property monthKey / [monthStartConfig] / [usedThisMonth] 当月额度账本
     */
    data class State(
        val active: Int = DEFAULT_MONTHLY,
        val baseline: Int = DEFAULT_MONTHLY,
        val baselineDay: String = "",
        val pendingValue: Int? = null,
        val pendingEffectiveAt: Long = 0L,
        val monthKey: String = "",
        val monthStartConfig: Int = DEFAULT_MONTHLY,
        val usedThisMonth: Int = 0
    )

    fun clamp(value: Int): Int = value.coerceIn(MIN_MONTHLY, MAX_MONTHLY)

    /** 业务日 `yyyyMMdd` → 业务月 `yyyyMM`；月界因此跟随刷新时刻而非日历 00:00。 */
    fun monthKey(businessDate: String): String = businessDate.take(6)

    /**
     * 下一个自然月（业务月）1 日的刷新时刻。刷新时刻前的时间仍属上一个业务日，
     * 因此 1 日 05:00（刷新 08:00）的下一个生效点是当天 08:00。
     */
    fun nextMonthEffectiveTime(now: Long, refreshHour: Int, refreshMinute: Int, zone: TimeZone = TimeZone.getDefault()): Long {
        val business = Calendar.getInstance(zone).apply {
            timeInMillis = now
            add(Calendar.MINUTE, -(refreshHour * 60 + refreshMinute))
        }
        return Calendar.getInstance(zone).apply {
            clear()
            set(business.get(Calendar.YEAR), business.get(Calendar.MONTH), 1, refreshHour, refreshMinute, 0)
            add(Calendar.MONTH, 1)
        }.timeInMillis
    }

    /** 读取前先对齐时间：到期的待生效落地 → 新业务日刷新基准 → 新业务月重置额度。 */
    fun rollover(state: State, businessDate: String, now: Long): State {
        var s = state
        val pending = s.pendingValue
        if (pending != null && now >= s.pendingEffectiveAt) {
            s = s.copy(active = clamp(pending), baseline = clamp(pending), pendingValue = null, pendingEffectiveAt = 0L)
        }
        if (s.baselineDay != businessDate) {
            s = s.copy(baseline = s.active, baselineDay = businessDate)
        }
        val key = monthKey(businessDate)
        if (s.monthKey != key) {
            s = s.copy(monthKey = key, monthStartConfig = s.active, usedThisMonth = 0)
        }
        return s
    }

    /** 调小或不超过基准立即生效；超过基准登记待生效；适应期放宽也立即生效。假定 [state] 已 rollover。 */
    fun requestChange(
        state: State,
        requested: Int,
        now: Long,
        inAdaptation: Boolean,
        nextEffectiveAt: Long
    ): State {
        val v = clamp(requested)
        if (inAdaptation) {
            return state.copy(
                active = v, baseline = v, monthStartConfig = v,
                pendingValue = null, pendingEffectiveAt = 0L
            )
        }
        if (v <= state.baseline) {
            return state.copy(active = v, pendingValue = null, pendingEffectiveAt = 0L)
        }
        val keepEffective = state.pendingValue == v && state.pendingEffectiveAt > 0L
        return state.copy(
            pendingValue = v,
            pendingEffectiveAt = if (keepEffective) state.pendingEffectiveAt else nextEffectiveAt
        )
    }

    /** 当月可用 = min(生效中配置, 月初配置) − 已用，下限 0。调大不增加当月额度，调小立即压低。 */
    fun remaining(state: State): Int =
        (minOf(state.active, state.monthStartConfig) - state.usedThisMonth).coerceAtLeast(0)

    /**
     * 导入备份时与本机状态合并（两边都应已 rollover 到当前业务月）：
     * - 每月次数沿用「加严立即、放宽延期」，直接走 [requestChange]；备份里尚未到期的待生效值同样带过来；
     * - 已用次数只增不减：同一业务月取较大值，备份来自其它月份则不计入，
     *   避免用「重装 + 导入旧备份」把当月额度刷回来。
     */
    fun mergeFromBackup(
        local: State,
        backup: State,
        now: Long,
        inAdaptation: Boolean,
        nextEffectiveAt: Long
    ): State {
        var merged = requestChange(local, backup.active, now, inAdaptation, nextEffectiveAt)
        // 备份里尚未到期的待生效值也要带过来：已到期的在 rollover 时已并入 active。
        val pending = backup.pendingValue
        if (pending != null && backup.pendingEffectiveAt > now) {
            merged = requestChange(merged, pending, now, inAdaptation, nextEffectiveAt)
        }
        val used = if (local.monthKey == backup.monthKey) {
            maxOf(local.usedThisMonth, backup.usedThisMonth)
        } else {
            local.usedThisMonth
        }
        return merged.copy(usedThisMonth = used)
    }

    /** 额度可用时扣 1 次，否则返回 null。 */
    fun consume(state: State): State? =
        if (remaining(state) > 0) state.copy(usedThisMonth = state.usedThisMonth + 1) else null
}

/**
 * 应急退出入口的长按判定：持续按住 [holdMs] 才触发；提前松手、手指滑出入口、
 * 出现第二根手指一律取消并清零。触发一次后自动复位，同一次按压不会重复触发。
 */
internal class EmergencyExitLongPressTracker(private val holdMs: Long = HOLD_MS) {
    companion object {
        const val HOLD_MS = 3_000L
    }

    private var downAt = -1L

    val isPressing: Boolean get() = downAt >= 0L

    fun onDown(now: Long) {
        downAt = now
    }

    fun onMove(inside: Boolean) {
        if (!inside) reset()
    }

    fun onSecondPointer() = reset()

    fun onUpOrCancel() = reset()

    fun progress(now: Long): Float =
        if (downAt < 0L) 0f else ((now - downAt).toFloat() / holdMs).coerceIn(0f, 1f)

    fun consumeTrigger(now: Long): Boolean {
        if (downAt >= 0L && now - downAt >= holdMs) {
            reset()
            return true
        }
        return false
    }

    private fun reset() {
        downAt = -1L
    }
}
