package com.decikeeper.app.manager.statistics

import android.content.Context
import com.decikeeper.app.data.TimeSegment
import com.decikeeper.app.manager.CreditManager
import com.decikeeper.app.manager.TimeRuleManager

/**
 * 时段被修改后，当前结余会随之变化（额度上限、时段起止、修改折算），但没有任何扣减动作。
 * 这里在写入时段前后各取一次结余，有变化才落一条「额度重算」流水，让账面缺口有出处。
 *
 * 业务日切换时 pending 并入 active 是额度重新开始，不是重算，不记。
 */
object QuotaRecalcRecorder {

    private val running = ThreadLocal.withInitial { false }

    /** 执行 [action]（会改写时段），并把结余的变化记成一条额度重算。[action] 一定会执行。 */
    fun <T> around(context: Context, action: () -> T): T {
        if (running.get() == true || CreditManager.isSettlingDayChange) return action()
        running.set(true)
        try {
            val snapshot = snapshot(context)
            val result = action()
            if (snapshot != null) recordDifference(context, snapshot)
            return result
        } finally {
            running.set(false)
        }
    }

    private class Snapshot(
        val minutes: Double,
        val halfUnits: Int,
        val segments: List<TimeSegment>,
        /** 此刻所在时段的名字；空档期为 null */
        val currentName: String?,
        /** 结余的逐段构成；空档期为 null */
        val breakdown: List<Pair<String, Double>>?
    )

    private fun currentSegmentName(context: Context): String? {
        val index = TimeRuleManager.getCurrentSegmentIndex(context)
        return TimeRuleManager.getTimeSegments(context).getOrNull(index)?.name
    }

    private fun snapshot(context: Context): Snapshot? = runCatching {
        // 改动前先把今天各时段的额度落账，之后只记差额
        CreditManager.materializeEntitlements(context)
        Snapshot(
            CreditManager.getRemainingMinutes(context),
            CreditManager.getRemainingUsableCount(context),
            TimeRuleManager.getTimeSegments(context),
            currentSegmentName(context),
            CreditManager.getRemainingMinutesBreakdown(context)
        )
    }.getOrNull()

    private fun recordDifference(context: Context, before: Snapshot) {
        runCatching {
            CreditManager.applyEntitlementDelta(context, before.segments, TimeRuleManager.getTimeSegments(context))
            val afterMinutes = CreditManager.getRemainingMinutes(context)
            val afterHalfUnits = CreditManager.getRemainingUsableCount(context)
            val deltaMinutes = before.minutes - afterMinutes
            val deltaHalfUnits = before.halfUnits - afterHalfUnits
            if (kotlin.math.abs(deltaMinutes) < MINUTES_EPSILON && deltaHalfUnits == 0) return
            RestStatsRecorder.record(
                context = context,
                type = RestLedger.TYPE_RECALC,
                typeName = RestLedger.TYPE_NAME_RECALC,
                reason = describeChange(
                    before.segments,
                    TimeRuleManager.getTimeSegments(context),
                    before.currentName,
                    currentSegmentName(context)
                ) + describeBalanceShift(
                    before.minutes, afterMinutes,
                    before.halfUnits, afterHalfUnits,
                    before.breakdown, CreditManager.getRemainingMinutesBreakdown(context)
                ),
                // 成本 = 变动前 − 变动后：结余减少为正，增加为负（与额度发放、提前退还同一符号约定）
                costMinutes = deltaMinutes,
                costCount = deltaHalfUnits / 2.0,
                balanceMinutes = afterMinutes,
                balanceCount = afterHalfUnits
            )
        }
    }

    /**
     * 逐个时段写明改了什么：额度、起止时间、可用次数、启用状态、新增 / 删除，
     * 以及「改动标记」（`lastModified`）的变化——它决定已过的时段是否还滚存未用额度、进行中的时段是否按剩余时间折算。
     * 所在时段变了（比如进入或离开空档期）也写出来，因为当前结余只算所在时段及之前的额度。
     * 什么都对不上时才给通用说明。
     *
     * @param oldCurrent / newCurrent 修改前后所在时段的名字，空档期为 null
     */
    internal fun describeChange(
        old: List<TimeSegment>,
        new: List<TimeSegment>,
        oldCurrent: String? = null,
        newCurrent: String? = null
    ): String {
        val parts = mutableListOf<String>()
        for (seg in new) {
            val previous = old.firstOrNull { it.name == seg.name }
            if (previous == null) {
                parts += "新增「${seg.name}」${seg.formatTimeRange()}，额度 ${seg.limitMins} 分钟"
                continue
            }
            val details = mutableListOf<String>()
            if (previous.limitMins != seg.limitMins) details += "额度 ${previous.limitMins} → ${seg.limitMins} 分钟"
            if (previous.formatTimeRange() != seg.formatTimeRange()) {
                details += "时间 ${previous.formatTimeRange()} → ${seg.formatTimeRange()}"
            }
            val oldCount = previous.getEffectiveUsableCountLimitHalfUnits()
            val newCount = seg.getEffectiveUsableCountLimitHalfUnits()
            if (oldCount != newCount) details += "可用次数 ${formatTimes(oldCount)} → ${formatTimes(newCount)} 次"
            if (previous.isEnabled != seg.isEnabled) details += if (seg.isEnabled) "启用" else "停用"
            if (previous.lastModified <= 0L && seg.lastModified > 0L) {
                details += "记为今天改动（已过的时段不再滚存未用额度，进行中的按剩余时间折算）"
            } else if (previous.lastModified > 0L && seg.lastModified <= 0L) {
                details += "改动标记被清除（未用额度重新计入）"
            } else if (previous.lastModified != seg.lastModified) {
                details += "改动时间更新"
            }
            if (details.isNotEmpty()) parts += "「${seg.name}」" + details.joinToString("，")
        }
        for (seg in old) {
            if (new.none { it.name == seg.name }) parts += "删除「${seg.name}」${seg.formatTimeRange()}"
        }
        if (oldCurrent != newCurrent) {
            parts += "此刻所在时段由${positionName(oldCurrent)}变为${positionName(newCurrent)}"
        }
        return if (parts.isEmpty()) "时段设置调整后，当前结余重新计算" else "时段设置调整：" + parts.joinToString("；")
    }

    /**
     * 结余前后的数字，以及逐段是哪一段变的（时长；次数只写合计）。结余增加时明确标出：
     * 时段改动立即生效的都是有利修改，按规则只会让额度减少，增加说明有标记 / 折算被撤销，需要核对。
     */
    internal fun describeBalanceShift(
        beforeMinutes: Double,
        afterMinutes: Double,
        beforeHalfUnits: Int,
        afterHalfUnits: Int,
        beforeBreakdown: List<Pair<String, Double>>?,
        afterBreakdown: List<Pair<String, Double>>?
    ): String {
        val sb = StringBuilder("。结余 ${fmt(beforeMinutes)} → ${fmt(afterMinutes)} 分钟，")
        sb.append("次数 ${formatTimes(beforeHalfUnits)} → ${formatTimes(afterHalfUnits)} 次")
        if (beforeBreakdown != null && afterBreakdown != null) {
            val b = beforeBreakdown.groupBy({ it.first }, { it.second }).mapValues { it.value.sum() }
            val a = afterBreakdown.groupBy({ it.first }, { it.second }).mapValues { it.value.sum() }
            val lines = (b.keys + a.keys).distinct().mapNotNull { name ->
                val x = b[name] ?: 0.0
                val y = a[name] ?: 0.0
                if (kotlin.math.abs(x - y) < MINUTES_EPSILON) null else "「$name」${fmt(x)} → ${fmt(y)} 分钟"
            }
            if (lines.isNotEmpty()) sb.append("；时长变化来自：").append(lines.joinToString("、"))
        } else {
            sb.append("；空档期结余只含前序时段滚存，不逐段列出")
        }
        if (afterMinutes > beforeMinutes + MINUTES_EPSILON || afterHalfUnits > beforeHalfUnits) {
            sb.append("。⚠ 结余增加：立即生效的时段改动按规则只会减少额度，请核对是否有改动标记 / 折算被撤销")
        }
        return sb.toString()
    }

    private fun fmt(value: Double): String =
        if (kotlin.math.abs(value - Math.rint(value)) < 0.05) Math.rint(value).toLong().toString()
        else String.format(java.util.Locale.US, "%.1f", value)

    private fun positionName(name: String?) = if (name == null) "空档期" else "「$name」"

    private fun formatTimes(halfUnits: Int): String =
        if (halfUnits % 2 == 0) (halfUnits / 2).toString() else (halfUnits / 2.0).toString()

    private const val MINUTES_EPSILON = 0.01
}
