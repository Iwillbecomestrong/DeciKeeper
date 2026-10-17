package com.decikeeper.app.manager

/** 显示层输入全部来自现有额度管理器；这里不发放、不扣减、不批准额度。 */
object SnackJarPresentation {
    data class Quota(val remainingMinutes: Double, val totalMinutes: Double,
                     val remainingHalfUnits: Int, val totalHalfUnits: Int)
    data class Parcel(val segmentName: String, val targetMinutes: Int,
                      val targetHalfUnits: Int, val effectiveMillis: Long,
                      val targetSegmentName: String = segmentName)
    data class Snapshot(
        val identity: String,
        val quota: Quota,
        val nextName: String? = null,
        val nextQuota: Quota? = null,
        val borrowedMinutes: Double = 0.0,
        val borrowedHalfUnits: Int = 0,
        val parcels: List<Parcel> = emptyList(),
        val activeLimits: Map<String, Pair<Int, Int>> = emptyMap()
    )
    enum class Effect { NONE, BORROW, DELIVERED, RETURNED, STRICTER }
    enum class Reaction { TAP, SURPRISE }
    enum class AlarmPose { NONE, WAKING, BRUSHING, HAPPY }

    fun timeFraction(quota: Quota): Float {
        if (!quota.remainingMinutes.isFinite() || !quota.totalMinutes.isFinite() || quota.totalMinutes <= 0) return 0f
        return (quota.remainingMinutes / quota.totalMinutes).toFloat().coerceIn(0f, 1f)
    }

    /** 每格一整次，奇数 half-unit 的末格只填半块；大量导入值只画前十二格。 */
    fun cookieFills(remaining: Int, total: Int): List<Float> {
        val available = remaining.coerceAtLeast(0).toLong()
        val count = ((maxOf(available, total.toLong()) + 1) / 2).coerceIn(1, 12).toInt()
        return List(count) { ((available - it * 2L).coerceIn(0, 2) / 2f) }
    }

    fun transition(before: Snapshot?, after: Snapshot, now: Long): Effect {
        if (before == null || before.identity != after.identity) return Effect.NONE
        if (after.borrowedMinutes > before.borrowedMinutes || after.borrowedHalfUnits > before.borrowedHalfUnits) return Effect.BORROW
        val removed = before.parcels.firstOrNull { old -> after.parcels.none { it.segmentName == old.segmentName } }
        if (removed != null) {
            val applied = after.activeLimits[removed.targetSegmentName]
            return if (now >= removed.effectiveMillis && applied != null &&
                applied.first >= removed.targetMinutes && applied.second >= removed.targetHalfUnits
            ) Effect.DELIVERED else Effect.RETURNED
        }
        if (after.quota.totalMinutes < before.quota.totalMinutes || after.quota.totalHalfUnits < before.quota.totalHalfUnits) return Effect.STRICTER
        return Effect.NONE
    }

    class Taps {
        private var last = Long.MIN_VALUE
        private var count = 0
        fun accept(now: Long): Reaction? {
            if (last != Long.MIN_VALUE && now - last < 180) return null
            if (last == Long.MIN_VALUE || now - last > 1800) count = 0
            last = now
            count++
            return if (count % 5 == 0) Reaction.SURPRISE else Reaction.TAP
        }
    }

    fun alarmPose(cooling: Boolean, connecting: Boolean, completed: Boolean): AlarmPose = when {
        cooling -> AlarmPose.WAKING
        completed -> AlarmPose.HAPPY
        connecting -> AlarmPose.BRUSHING
        else -> AlarmPose.NONE
    }
}
