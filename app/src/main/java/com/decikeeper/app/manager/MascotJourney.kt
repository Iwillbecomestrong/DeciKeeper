package com.decikeeper.app.manager

/**
 * 「团子赶路」的进度计算，纯 Kotlin、不依赖 Android。
 *
 * 只关心“一天里的分钟数”：调用方负责把当天生效、且真正受限的时段挑出来（沿用
 * `TimeRuleManager` 对逻辑日、启用状态和免费时段的判断），这里不重复这些规则。
 */
internal object MascotJourney {

    /** 时段结束后，团子在终点欢呼的持续分钟数。 */
    const val ARRIVED_WINDOW_MINUTES = 15

    private const val DAY_MINUTES = 24 * 60

    /** 一天里的起止分钟（0..1439）；`start > end` 表示跨午夜。 */
    data class Segment(val startMinute: Int, val endMinute: Int) {
        val durationMinutes: Int
            get() = if (endMinute > startMinute) endMinute - startMinute else DAY_MINUTES - startMinute + endMinute
    }

    sealed class State {
        /** 没有可用的时段。 */
        object None : State()

        /** 距下一个时段开始 [minutesToStart] 分钟，团子在起点打盹。 */
        data class Waiting(val minutesToStart: Int) : State()

        /** 时段进行中：[progress] 为 0..1，剩余 [minutesLeft] 分钟。 */
        data class Walking(val progress: Float, val minutesLeft: Int) : State()

        /** 时段刚结束 [minutesAgo] 分钟，团子到达终点。 */
        data class Arrived(val minutesAgo: Int) : State()
    }

    /**
     * @param nowMinute 当前时刻，单位分钟，可带小数（秒）。范围 0 ≤ now < 1440。
     * 多个时段重叠时，取结束最晚的那个，让团子走完整段受限时间。
     */
    fun compute(nowMinute: Double, segments: List<Segment>): State {
        if (segments.isEmpty()) return State.None
        val now = nowMinute.coerceIn(0.0, DAY_MINUTES - 0.0001)

        // 1) 进行中
        var walking: Pair<Segment, Double>? = null // 时段 + 已走分钟
        for (seg in segments) {
            val elapsed = elapsedSinceStart(now, seg) ?: continue
            val remaining = seg.durationMinutes - elapsed
            val best = walking
            if (best == null || remaining > best.first.durationMinutes - best.second) {
                walking = seg to elapsed
            }
        }
        if (walking != null) {
            val (seg, elapsed) = walking
            val left = seg.durationMinutes - elapsed
            val progress = (elapsed / seg.durationMinutes).toFloat().coerceIn(0f, 1f)
            return State.Walking(progress, kotlin.math.ceil(left).toInt().coerceAtLeast(1))
        }

        // 2) 刚结束
        var arrived: Int? = null
        for (seg in segments) {
            val since = minutesSince(now, seg.endMinute)
            if (since < ARRIVED_WINDOW_MINUTES && (arrived == null || since < arrived)) arrived = since.toInt()
        }
        if (arrived != null) return State.Arrived(arrived)

        // 3) 等下一个
        var wait = Double.MAX_VALUE
        for (seg in segments) {
            val untilStart = (seg.startMinute - now + DAY_MINUTES) % DAY_MINUTES
            if (untilStart < wait) wait = untilStart
        }
        return State.Waiting(kotlin.math.ceil(wait).toInt().coerceAtLeast(1))
    }

    /** 已从 [seg] 开始走了多少分钟；不在时段内返回 null。 */
    private fun elapsedSinceStart(now: Double, seg: Segment): Double? {
        val elapsed = (now - seg.startMinute + DAY_MINUTES) % DAY_MINUTES
        return if (elapsed < seg.durationMinutes) elapsed else null
    }

    /** 距 [minute] 这个时刻已过去的分钟数（0..1440）。 */
    private fun minutesSince(now: Double, minute: Int): Double = (now - minute + DAY_MINUTES) % DAY_MINUTES

    /** 进度条上的文字说明。 */
    fun caption(state: State): String = when (state) {
        State.None -> ""
        is State.Waiting -> "团子在打盹，${formatMinutes(state.minutesToStart)}后出发"
        is State.Walking -> "团子在赶路，还剩 ${formatMinutes(state.minutesLeft)}"
        is State.Arrived -> "到啦！这一段守住了"
    }

    /** 90 → "1 小时 30 分"，45 → "45 分钟"，120 → "2 小时"。 */
    fun formatMinutes(minutes: Int): String {
        val m = minutes.coerceAtLeast(0)
        val h = m / 60
        val rest = m % 60
        return when {
            h == 0 -> "$m 分钟"
            rest == 0 -> "$h 小时"
            else -> "$h 小时 $rest 分"
        }
    }
}
