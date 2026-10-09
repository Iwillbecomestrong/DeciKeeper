package com.decikeeper.app.manager.statistics

import android.content.Context
import com.decikeeper.app.data.TimeSegment
import com.decikeeper.app.manager.CreditManager
import com.decikeeper.app.manager.TimeRuleManager
import java.util.Calendar

/**
 * 进入新的勿扰时段时，把该时段「领到」的额度记入额度流水（类别：额度发放）。
 *
 * 额度本身是按段配置、读取时推算的，没有真正的"发放"动作；这里只负责在
 * 监控轮询第一次发现处于某个勿扰时段时落一条账，用于和扣减流水对账。
 * 每个业务日内的每个时段只记一次。
 */
object QuotaGrantRecorder {

    private const val PREFS_NAME = "rest_stats_grant_marker"
    private const val KEY_LAST_GRANT = "last_granted_segment"

    /** 同一业务日内同一时段的唯一标识。 */
    internal fun grantKey(businessDate: String, segment: TimeSegment): String =
        "$businessDate|${segment.startHour}:${segment.startMinute}-${segment.endHour}:${segment.endMinute}|${segment.name}"

    /** 当前时刻之前最近一次的时段起点（跨天时段在午夜后取前一天）。 */
    internal fun latestOccurrenceStartMillis(segment: TimeSegment, nowMillis: Long): Long {
        val cal = Calendar.getInstance().apply {
            timeInMillis = nowMillis
            set(Calendar.HOUR_OF_DAY, segment.startHour)
            set(Calendar.MINUTE, segment.startMinute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        if (cal.timeInMillis > nowMillis) cal.add(Calendar.DAY_OF_YEAR, -1)
        return cal.timeInMillis
    }

    @Synchronized
    fun recordIfNewSegment(context: Context, nowMillis: Long = System.currentTimeMillis()) {
        val segment = TimeRuleManager.getCurrentSegment(context) ?: return
        val key = grantKey(CreditManager.getBusinessDateString(context), segment)
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.getString(KEY_LAST_GRANT, null) == key) return
        // 先落标记再写账：写账失败也不会在每次轮询里重复尝试
        prefs.edit().putString(KEY_LAST_GRANT, key).apply()

        val proration = CreditManager.getSegmentProration(context, segment, nowMillis)
        val grantedMinutes = proration.effectiveLimitMins
        val grantedCount = proration.effectiveUsableCountHalfUnits / 2.0
        if (grantedMinutes <= 0.0 && grantedCount <= 0.0) return

        RestStatsRecorder.record(
            context = context,
            type = RestLedger.TYPE_GRANT,
            typeName = RestLedger.TYPE_NAME_GRANT,
            reason = "进入「${segment.name}」时段，领取本时段额度",
            requestedMinutes = 0.0,
            actualMinutes = 0.0,
            // 成本为负 = 额度增加（与"提前退还"同一符号约定）
            costMinutes = -grantedMinutes,
            costCount = -grantedCount,
            balanceMinutes = CreditManager.getRemainingMinutes(context),
            balanceCount = CreditManager.getRemainingUsableCount(context),
            timestamp = latestOccurrenceStartMillis(segment, nowMillis)
        )
    }
}
