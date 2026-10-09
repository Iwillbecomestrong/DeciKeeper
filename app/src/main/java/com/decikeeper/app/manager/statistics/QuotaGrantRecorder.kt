package com.decikeeper.app.manager.statistics

import android.content.Context
import com.decikeeper.app.data.LogExporter
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
 *
 * 上一段借用时会提前发放本段的一部分额度（借用扣除 ×2，见 [notePendingAdvance]）；
 * 这里只补发剩下的，并把已提前发放的部分写进记录，合计才等于本段额度。
 */
object QuotaGrantRecorder {

    private const val PREFS_NAME = "rest_stats_grant_marker"
    /** 旧键 `last_granted_segment` 作废：旧版先写标记再写账，写账失败会留下「已处理」的假标记，该时段永远补不上。 */
    private const val KEY_LAST_GRANT = "last_granted_segment_v2"
    private const val KEY_ADVANCE_PREFIX = "advance|"

    /** 监控在熄屏时暂停，发现略有延迟是正常的；超过这个时长才算「补记」。 */
    private const val LATE_DETECTION_MS = 2 * 60_000L

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

    /**
     * 借用时，把提前发放给 [segment]（下一段）的额度记下来，等该段发放时扣掉。
     * 同一时段多次借用累加；只保留当前业务日的记录。
     *
     * @param minutes 提前发放的时长（分钟）
     * @param count 提前发放的次数（单位「次」）
     */
    @Synchronized
    fun notePendingAdvance(context: Context, segment: TimeSegment, minutes: Double, count: Double) {
        if (minutes <= 0.0 && count <= 0.0) return
        val businessDate = CreditManager.getBusinessDateString(context)
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val key = KEY_ADVANCE_PREFIX + grantKey(businessDate, segment)
        val (oldMinutes, oldCount) = readAdvance(prefs.getString(key, null))
        val editor = prefs.edit()
        prefs.all.keys
            .filter { it.startsWith(KEY_ADVANCE_PREFIX) && !it.startsWith("$KEY_ADVANCE_PREFIX$businessDate|") }
            .forEach { editor.remove(it) }
        editor.putString(key, "${oldMinutes + minutes},${oldCount + count}").apply()
    }

    private fun readAdvance(raw: String?): Pair<Double, Double> {
        val parts = raw?.split(",").orEmpty()
        return (parts.getOrNull(0)?.toDoubleOrNull() ?: 0.0) to (parts.getOrNull(1)?.toDoubleOrNull() ?: 0.0)
    }

    /** 连续失败时不要每次轮询都重试并刷日志。 */
    private const val RETRY_AFTER_FAILURE_MS = 60_000L
    @Volatile
    private var lastFailureMs = 0L

    /**
     * 进入新的勿扰时段时记一笔额度发放。
     *
     * 标记**只在账确实写进流水之后**才落（失败不落，下次轮询重试，最多每分钟一次）；
     * 写之前先查流水里是否已有同一时段的发放（丢了标记、换了版本都不会重复记）。
     * 失败和跳过都写日志，不再静默吞掉。
     */
    @Synchronized
    fun recordIfNewSegment(context: Context, nowMillis: Long = System.currentTimeMillis()) {
        val segment = TimeRuleManager.getCurrentSegment(context) ?: return
        val key = grantKey(CreditManager.getBusinessDateString(context), segment)
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.getString(KEY_LAST_GRANT, null) == key) return
        if (lastFailureMs != 0L && nowMillis - lastFailureMs < RETRY_AFTER_FAILURE_MS) return
        try {
            recordGrant(context, segment, key, prefs, nowMillis)
            lastFailureMs = 0L
        } catch (e: Exception) {
            lastFailureMs = nowMillis
            LogExporter.w("QuotaGrant", "额度发放记账失败（下次重试）| key=$key | ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun recordGrant(
        context: Context,
        segment: TimeSegment,
        key: String,
        prefs: android.content.SharedPreferences,
        nowMillis: Long
    ) {
        val startMillis = latestOccurrenceStartMillis(segment, nowMillis)
        val existing = RestStatsRecorder.getAllRecords(context)

        // 账已经在：只补标记
        if (existing.any { it.type == RestLedger.TYPE_GRANT && it.timestamp == startMillis }) {
            prefs.edit().putString(KEY_LAST_GRANT, key).apply()
            return
        }

        val proration = CreditManager.getSegmentProration(context, segment, nowMillis)
        val grantedMinutes = proration.effectiveLimitMins
        val grantedHalfUnits = proration.effectiveUsableCountHalfUnits
        if (grantedMinutes <= 0.0 && grantedHalfUnits <= 0) {
            LogExporter.i("QuotaGrant", "额度发放跳过：本段额度为 0 | key=$key")
            prefs.edit().putString(KEY_LAST_GRANT, key).apply()
            return
        }

        // 上一段借用时已经提前发放的部分：这里只补发剩下的（提前发放不会超过本段额度）
        val advanceKey = KEY_ADVANCE_PREFIX + key
        val (pendingMinutes, pendingCount) = readAdvance(prefs.getString(advanceKey, null))
        val advancedMinutes = minOf(pendingMinutes, grantedMinutes)
        val advancedHalfUnits = minOf(kotlin.math.round(pendingCount * 2).toInt(), grantedHalfUnits)
        val netMinutes = grantedMinutes - advancedMinutes
        val netHalfUnits = grantedHalfUnits - advancedHalfUnits

        var balanceMinutes = CreditManager.getRemainingMinutes(context)
        var balanceCount = CreditManager.getRemainingUsableCount(context)
        var reason = "进入「${segment.name}」时段，领取本时段额度"

        if (nowMillis - startMillis > LATE_DETECTION_MS) {
            // 补记：时段早已开始（如应用刚更新、进程被杀后重启），现在的结余已经包含了之后的使用，
            // 不能当作「发放后」的结余。按「上一笔流水的变动后 + 本次净发放」估算，让流水连得上。
            val dayStartMinutes = TimeRuleManager.getRefreshTime(context).split(":")
                .let { (it.getOrNull(0)?.toIntOrNull() ?: 8) * 60 + (it.getOrNull(1)?.toIntOrNull() ?: 0) }
            val previous = existing.firstOrNull {
                it.type != RestLedger.TYPE_PRIVILEGE && it.timestamp <= startMillis
            }?.takeIf {
                RestLedger.businessDayOf(it.timestamp, dayStartMinutes) == RestLedger.businessDayOf(startMillis, dayStartMinutes)
            }
            if (previous != null) {
                balanceMinutes = previous.balanceMinutes + netMinutes
                balanceCount = previous.balanceCount + netHalfUnits
            }
            reason += "（补记：时段开始后才检测到，结余按上一笔流水估算）"
        }

        val written = RestStatsRecorder.record(
            context = context,
            type = RestLedger.TYPE_GRANT,
            typeName = RestLedger.TYPE_NAME_GRANT,
            reason = reason,
            requestedMinutes = 0.0,
            actualMinutes = 0.0,
            // 成本为负 = 额度增加（与"提前退还"同一符号约定）；净发放 = 本段额度 − 已提前发放
            costMinutes = -netMinutes,
            costCount = -(netHalfUnits / 2.0),
            balanceMinutes = balanceMinutes,
            balanceCount = balanceCount,
            timestamp = startMillis,
            advancedMinutes = advancedMinutes,
            advancedCount = advancedHalfUnits / 2.0
        )
        // 写进库里才算数（RestStatsRecorder 写库失败会静默落到备用存储，列表里看不到）
        check(RestStatsRecorder.getAllRecords(context).any { it.id == written.id }) { "发放记录没有写进流水库" }

        if (prefs.contains(advanceKey)) prefs.edit().remove(advanceKey).apply()
        prefs.edit().putString(KEY_LAST_GRANT, key).apply()
    }
}
