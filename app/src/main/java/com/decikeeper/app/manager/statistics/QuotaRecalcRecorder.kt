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
        val segments: List<TimeSegment>
    )

    private fun snapshot(context: Context): Snapshot? = runCatching {
        Snapshot(
            CreditManager.getRemainingMinutes(context),
            CreditManager.getRemainingUsableCount(context),
            TimeRuleManager.getTimeSegments(context)
        )
    }.getOrNull()

    private fun recordDifference(context: Context, before: Snapshot) {
        runCatching {
            val afterMinutes = CreditManager.getRemainingMinutes(context)
            val afterHalfUnits = CreditManager.getRemainingUsableCount(context)
            val deltaMinutes = before.minutes - afterMinutes
            val deltaHalfUnits = before.halfUnits - afterHalfUnits
            if (kotlin.math.abs(deltaMinutes) < MINUTES_EPSILON && deltaHalfUnits == 0) return
            RestStatsRecorder.record(
                context = context,
                type = RestLedger.TYPE_RECALC,
                typeName = RestLedger.TYPE_NAME_RECALC,
                reason = describeChange(before.segments, TimeRuleManager.getTimeSegments(context)),
                // 成本 = 变动前 − 变动后：结余减少为正，增加为负（与额度发放、提前退还同一符号约定）
                costMinutes = deltaMinutes,
                costCount = deltaHalfUnits / 2.0,
                balanceMinutes = afterMinutes,
                balanceCount = afterHalfUnits
            )
        }
    }

    /** 额度有变化的时段逐个列出；只改了起止时间或次数时给通用说明。 */
    internal fun describeChange(old: List<TimeSegment>, new: List<TimeSegment>): String {
        val parts = new.mapNotNull { seg ->
            val previous = old.firstOrNull { it.name == seg.name } ?: return@mapNotNull null
            if (previous.limitMins == seg.limitMins) null
            else "「${seg.name}」额度 ${previous.limitMins} → ${seg.limitMins} 分钟"
        }
        return if (parts.isEmpty()) "时段设置调整后，当前结余重新计算" else "时段设置调整：" + parts.joinToString("；")
    }

    private const val MINUTES_EPSILON = 0.01
}
