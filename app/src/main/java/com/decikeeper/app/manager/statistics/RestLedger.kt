package com.decikeeper.app.manager.statistics

import com.decikeeper.app.data.statistics.RestUsageRecord

/**
 * 额度流水的类型筛选。
 *
 * 先按去向分：[INFLOW] 入流（额度增加）、[OUTFLOW] 出流（额度减少），再按类型细分。
 * 入流 = [QUOTA_GRANT]、[REFUND]、[RECALC_INCREASE]；
 * 出流 = 休息 / 临时解锁（[NORMAL]、[BORROW]、[COOLING]）、历史「频繁返回扣减」记录（只读）、[RECALC_DECREASE]，
 * 只有真的减少了额度才算（空档期 / 休息中的免费操作不动额度，不算出流）。
 * 「额度重算」按结余的实际变化划分：时长或次数增加归 [RECALC_INCREASE]，减少归 [RECALC_DECREASE]，
 * 一增一减的重算两边都能筛到，列表里按筛选的方向只显示对应那一项的变动（见 [displayedDelta]）。
 * 借用记录是出流：借用时提前发放的下一段额度只在顶部汇总里记入流（为了对账），不单独出现在入流列表里。
 * [PRIVILEGE] 是每周特权的独立池子，不参与入流 / 出流对账。
 *
 * 常规 / 借用 / 冷却中针对休息与临时解锁：借用与冷却中可以同时成立，
 * 这样的记录会同时出现在两个筛选里；常规只含两者都不是的。
 */
enum class LedgerFilter(val displayName: String) {
    ALL("全部"),
    INFLOW("入流（额度增加）"),
    OUTFLOW("出流（额度减少）"),
    QUOTA_GRANT("额度发放"),
    REFUND("提前退还"),
    RECALC_INCREASE("重算增加"),
    NORMAL("常规"),
    BORROW("借用"),
    COOLING("冷却中"),
    RECALC_DECREASE("重算减少"),
    PRIVILEGE("特权")
}

/** 一段范围内的入流 / 出流汇总（特权不计入）。 */
data class LedgerSummary(
    val inflowMinutes: Double,
    val inflowCount: Double,
    val outflowMinutes: Double,
    val outflowCount: Double
) {
    /** 入流理应不小于出流；小于说明记录不全或记账有误。 */
    val inflowShort: Boolean
        get() = inflowMinutes + EPSILON < outflowMinutes || inflowCount + EPSILON < outflowCount

    private companion object {
        const val EPSILON = 0.05
    }
}

/** 流水列表的一项：一笔记录（带「变动前」），或相邻两笔之间接不上的缺口。 */
sealed interface LedgerEntry {
    /**
     * @param beforeMinutes 变动前结余 = 变动后结余 + 变动量；借用记录的变动量含下一段预扣，
     * 无法还原当前段的变动前，为 null（特权是独立池子，同样为 null）。
     */
    data class Row(
        val record: RestUsageRecord,
        val beforeMinutes: Double?,
        val beforeCount: Double?
    ) : LedgerEntry

    /**
     * 上一笔「变动后」与下一笔「变动前」对不上。正数 = 账面少了（有出口没记），负数 = 账面多了（有入流没记）。
     */
    data class Gap(val missingMinutes: Double, val missingCount: Double) : LedgerEntry
}

/**
 * 额度流水的口径单一出口：类型归类、次数单位、数值格式化。
 *
 * 次数的存储口径：
 * - 新记录（[TYPE_REST] / [TYPE_TEMP] 起的类型）与特权、扣减、退还：`costCount` 已是「次」；
 * - 旧版休息记录（`NORMAL` / `BORROW`）误把 half-unit 当「次」存入，读取时换算回来；
 * - `balanceCount` 全部来自 `CreditManager.getRemainingUsableCount`，恒为 half-unit。
 *
 * 借用记录的 `costMinutes` / `costCount` 记该笔造成的总扣减（当前段扣 + 下一段预扣），
 * 这样入流 ≥ 出流的对账才成立；「变动后结余」只反映当前可用额度。
 */
object RestLedger {
    const val TYPE_REST = "REST"
    const val TYPE_TEMP = "TEMP"
    const val TYPE_GRANT = "GRANT"
    const val TYPE_PENALTY = "PENALTY"
    const val TYPE_REFUND = "REFUND"
    const val TYPE_PRIVILEGE = "PRIVILEGE"
    const val TYPE_RECALC = "RECALC"

    const val TYPE_NAME_GRANT = "额度发放"
    const val TYPE_NAME_RECALC = "额度重算"
    const val TYPE_NAME_PENALTY = "频繁返回扣减"

    private const val BORROW_SUFFIX = "_BORROW"
    private const val COOLING_SUFFIX = "_COOLING"
    private const val LEGACY_REST = "NORMAL"
    private const val LEGACY_BORROW = "BORROW"

    /** 旧版冷却整单 ×2，成本不低于申请时长的 1.9 倍即视为冷却中申请。 */
    private const val LEGACY_COOLING_COST_RATIO = 1.9

    fun restType(cooling: Boolean, borrowed: Boolean): String = compose(TYPE_REST, cooling, borrowed)

    fun tempType(cooling: Boolean, borrowed: Boolean): String = compose(TYPE_TEMP, cooling, borrowed)

    fun restTypeName(cooling: Boolean, borrowed: Boolean): String =
        typeName("休息", "常规休息", cooling, borrowed)

    fun tempTypeName(cooling: Boolean, borrowed: Boolean): String =
        typeName("临时解锁", "临时解锁", cooling, borrowed)

    private fun compose(base: String, cooling: Boolean, borrowed: Boolean): String =
        base + (if (borrowed) BORROW_SUFFIX else "") + (if (cooling) COOLING_SUFFIX else "")

    private fun typeName(label: String, plain: String, cooling: Boolean, borrowed: Boolean): String = when {
        cooling && borrowed -> "冷却中借用$label"
        cooling -> "冷却中$label"
        borrowed -> "借用$label"
        else -> plain
    }

    fun isRestLike(record: RestUsageRecord): Boolean =
        record.type == LEGACY_REST || record.type == LEGACY_BORROW ||
            record.type.startsWith(TYPE_REST) || record.type.startsWith(TYPE_TEMP)

    /** 借用记录：借用时提前发放了下一段的额度（额度发放记录上的同名字段是「已被提前发放的部分」，不算）。 */
    fun hasAdvance(record: RestUsageRecord): Boolean =
        isRestLike(record) && (record.advancedMinutes > 0.0 || record.advancedCount > 0.0)

    fun isBorrow(record: RestUsageRecord): Boolean =
        record.type == LEGACY_BORROW || record.type.contains(BORROW_SUFFIX)

    fun isCooling(record: RestUsageRecord): Boolean = when (record.type) {
        LEGACY_REST, LEGACY_BORROW ->
            record.requestedMinutes > 0.0 &&
                record.costMinutes >= record.requestedMinutes * LEGACY_COOLING_COST_RATIO
        else -> record.type.endsWith(COOLING_SUFFIX)
    }

    fun matches(record: RestUsageRecord, filter: LedgerFilter): Boolean = when (filter) {
        LedgerFilter.ALL -> true
        LedgerFilter.INFLOW -> record.type == TYPE_GRANT || record.type == TYPE_REFUND ||
            (record.type == TYPE_RECALC && flowOf(record).let { it.inMinutes > FLOW_EPSILON || it.inCount > FLOW_EPSILON })
        LedgerFilter.OUTFLOW -> flowOf(record).let { it.outMinutes > FLOW_EPSILON || it.outCount > FLOW_EPSILON }
        LedgerFilter.QUOTA_GRANT -> record.type == TYPE_GRANT
        LedgerFilter.REFUND -> record.type == TYPE_REFUND
        LedgerFilter.RECALC_INCREASE -> record.type == TYPE_RECALC &&
            (record.costMinutes < -FLOW_EPSILON || costCountInTimes(record) < -FLOW_EPSILON)
        LedgerFilter.RECALC_DECREASE -> record.type == TYPE_RECALC &&
            (record.costMinutes > FLOW_EPSILON || costCountInTimes(record) > FLOW_EPSILON)
        LedgerFilter.PRIVILEGE -> record.type == TYPE_PRIVILEGE
        LedgerFilter.NORMAL -> isRestLike(record) && !isBorrow(record) && !isCooling(record)
        LedgerFilter.BORROW -> isRestLike(record) && isBorrow(record)
        LedgerFilter.COOLING -> isRestLike(record) && isCooling(record)
    }

    fun filter(records: List<RestUsageRecord>, filter: LedgerFilter): List<RestUsageRecord> =
        if (filter == LedgerFilter.ALL) records else records.filter { matches(it, filter) }

    /** 本条流水扣减（正）/ 返还、发放（负）的次数，单位「次」。 */
    fun costCountInTimes(record: RestUsageRecord): Double =
        if (record.type == LEGACY_REST || record.type == LEGACY_BORROW) record.costCount / 2.0 else record.costCount

    /** 变动后结余次数，单位「次」。 */
    fun balanceCountInTimes(record: RestUsageRecord): Double = record.balanceCount / 2.0

    private const val GAP_EPSILON_MINUTES = 0.05
    private const val GAP_EPSILON_COUNT = 0.01

    /** 这条记录是否参与「变动后 → 下一笔变动前」的连续性核对（特权不动休息额度，不参与）。 */
    private fun isChained(record: RestUsageRecord): Boolean = record.type != TYPE_PRIVILEGE

    private fun beforeOf(record: RestUsageRecord): Pair<Double, Double>? {
        if (!isChained(record)) return null
        // 旧版借用记录的变动量含下一段预扣却没记提前发放，还原不出变动前
        if (isBorrow(record) && !hasAdvance(record)) return null
        // 变动前 + 提前发放 − 扣除 = 变动后（提前发放只有借用记录有）
        val advance = if (hasAdvance(record)) record.advancedMinutes else 0.0
        val advanceCount = if (hasAdvance(record)) record.advancedCount else 0.0
        return (record.balanceMinutes + record.costMinutes - advance) to
            (balanceCountInTimes(record) + costCountInTimes(record) - advanceCount)
    }

    /**
     * 把流水（最新在前）整理成列表项：每笔带「变动前」，并在相邻两笔接不上时插入缺口提示。
     *
     * 缺口 = 较早一笔的「变动后」 − 较新一笔的「变动前」，说明这两笔之间有变动没进流水
     * （或有一笔算错了）。跨业务日不比较（额度每个业务日重新开始）；借用、特权不参与比较。
     *
     * @param businessDayStartMinutes 业务日分界（距 00:00 的分钟数，生效时间）
     */
    fun buildEntries(
        records: List<RestUsageRecord>,
        includeGaps: Boolean,
        businessDayStartMinutes: Int = 0,
        zoneId: java.time.ZoneId = java.time.ZoneId.systemDefault()
    ): List<LedgerEntry> {
        fun businessDay(ts: Long) = businessDayOf(ts, businessDayStartMinutes, zoneId)

        val result = ArrayList<LedgerEntry>(records.size * 2)
        records.forEachIndexed { index, record ->
            val before = beforeOf(record)
            result += LedgerEntry.Row(record, before?.first, before?.second)
            if (!includeGaps) return@forEachIndexed

            // 与「较早的、参与核对的」下一笔比较：这一笔的变动前，应等于它的变动后
            if (before == null) return@forEachIndexed
            var olderIndex = index + 1
            while (olderIndex < records.size && !isChained(records[olderIndex])) olderIndex++
            val older = records.getOrNull(olderIndex) ?: return@forEachIndexed
            if (businessDay(older.timestamp) != businessDay(record.timestamp)) return@forEachIndexed
            val missingMinutes = older.balanceMinutes - before.first
            val missingCount = balanceCountInTimes(older) - before.second
            if (kotlin.math.abs(missingMinutes) >= GAP_EPSILON_MINUTES || kotlin.math.abs(missingCount) >= GAP_EPSILON_COUNT) {
                result += LedgerEntry.Gap(missingMinutes, missingCount)
            }
        }
        return result
    }

    /** 时间戳所属的业务日：分界之前仍算前一天。 */
    fun businessDayOf(
        timestamp: Long,
        businessDayStartMinutes: Int,
        zoneId: java.time.ZoneId = java.time.ZoneId.systemDefault()
    ): java.time.LocalDate = java.time.Instant.ofEpochMilli(timestamp).atZone(zoneId)
        .minusMinutes(businessDayStartMinutes.toLong()).toLocalDate()

    /** 缺口提示文案。 */
    fun gapText(gap: LedgerEntry.Gap): String {
        val amount = "${formatAmount(kotlin.math.abs(gap.missingMinutes))}m / ${formatAmount(kotlin.math.abs(gap.missingCount))}次"
        return if (gap.missingMinutes >= 0 && gap.missingCount >= 0) {
            "⚠ 这两笔之间账面少了 $amount：这段时间有额度变动没进流水（也可能是上一笔结余算错）"
        } else if (gap.missingMinutes <= 0 && gap.missingCount <= 0) {
            "⚠ 这两笔之间账面多了 $amount：有额度入账没进流水（也可能是上一笔结余算错）"
        } else {
            "⚠ 这两笔之间账面对不上（时长 ${formatDelta(-gap.missingMinutes, "m")}，次数 ${formatDelta(-gap.missingCount, "次")}）"
        }
    }

    private const val FLOW_EPSILON = 0.0001

    /** 一条流水的入流 / 出流（时长分钟、次数「次」），汇总与筛选共用，保证两边口径一致。 */
    private class Flow(val inMinutes: Double, val inCount: Double, val outMinutes: Double, val outCount: Double)

    private val NO_FLOW = Flow(0.0, 0.0, 0.0, 0.0)

    private fun flowOf(record: RestUsageRecord): Flow = when {
        record.type == TYPE_GRANT || record.type == TYPE_REFUND ->
            Flow(-record.costMinutes, -costCountInTimes(record), 0.0, 0.0)
        // 借用时提前发放的下一段额度是入流；下一段发放记录里只记净发放，合计才等于该段额度
        isRestLike(record) || record.type == TYPE_PENALTY -> Flow(
            if (hasAdvance(record)) record.advancedMinutes else 0.0,
            if (hasAdvance(record)) record.advancedCount else 0.0,
            record.costMinutes,
            costCountInTimes(record)
        )
        // 重算的时长与次数可能一增一减，各自按正负归入入流 / 出流
        record.type == TYPE_RECALC -> {
            val count = costCountInTimes(record)
            Flow(
                if (record.costMinutes < 0.0) -record.costMinutes else 0.0,
                if (count < 0.0) -count else 0.0,
                if (record.costMinutes >= 0.0) record.costMinutes else 0.0,
                if (count >= 0.0) count else 0.0
            )
        }
        else -> NO_FLOW
    }

    /**
     * 列表里这一笔「额度变化」该显示多少：(时长分钟, 次数「次」)，增加为正、减少为负。
     * 一增一减的重算，在入流类筛选下只显示增加的那一项，出流类筛选下只显示减少的那一项，
     * 另一项记 0；其余记录、其余筛选照实显示。
     */
    fun displayedDelta(record: RestUsageRecord, filter: LedgerFilter): Pair<Double, Double> {
        val minutes = -record.costMinutes
        val count = -costCountInTimes(record)
        if (record.type != TYPE_RECALC) return minutes to count
        return when (filter) {
            LedgerFilter.INFLOW, LedgerFilter.RECALC_INCREASE ->
                minutes.coerceAtLeast(0.0) to count.coerceAtLeast(0.0)
            LedgerFilter.OUTFLOW, LedgerFilter.RECALC_DECREASE ->
                minutes.coerceAtMost(0.0) to count.coerceAtMost(0.0)
            else -> minutes to count
        }
    }

    /** 类型弹窗左列（入流）下的细分类型。 */
    val inflowTypeFilters = listOf(LedgerFilter.QUOTA_GRANT, LedgerFilter.REFUND, LedgerFilter.RECALC_INCREASE)

    /** 类型弹窗右列（出流）下的细分类型。 */
    val outflowTypeFilters = listOf(
        LedgerFilter.NORMAL, LedgerFilter.BORROW, LedgerFilter.COOLING,
        LedgerFilter.RECALC_DECREASE
    )

    /** 分栏对照用：入流在左、出流在右；两边都不动额度的（免费休息、特权等）只计数。一增一减的重算两栏都有。 */
    class FlowColumns(val inflow: List<RestUsageRecord>, val outflow: List<RestUsageRecord>, val neither: Int)

    fun splitByFlow(records: List<RestUsageRecord>): FlowColumns {
        val inflow = records.filter { matches(it, LedgerFilter.INFLOW) }
        val outflow = records.filter { matches(it, LedgerFilter.OUTFLOW) }
        val neither = records.count { !matches(it, LedgerFilter.INFLOW) && !matches(it, LedgerFilter.OUTFLOW) }
        return FlowColumns(inflow, outflow, neither)
    }

    /** 一栏里各条「列表上显示的变动」的合计（取绝对值）：(时长分钟, 次数)。与栏里逐条显示的数对得上。 */
    fun columnTotal(records: List<RestUsageRecord>, direction: LedgerFilter): Pair<Double, Double> {
        var minutes = 0.0
        var count = 0.0
        records.forEach {
            val (m, c) = displayedDelta(it, direction)
            minutes += kotlin.math.abs(m)
            count += kotlin.math.abs(c)
        }
        return minutes to count
    }

    /** 借用时提前发放的下一段额度合计（只计入顶部对账的入流，不在入流栏里逐条列出）。 */
    fun advanceTotal(records: List<RestUsageRecord>): Pair<Double, Double> =
        records.filter { hasAdvance(it) }.let { list ->
            list.sumOf { it.advancedMinutes } to list.sumOf { it.advancedCount }
        }

    /** 分栏卡片上的金额：只写不为 0 的一项（都为 0 写 0m）；入流带 +，出流带 −。 */
    fun flowAmountText(minutes: Double, count: Double): String {
        val parts = mutableListOf<String>()
        if (kotlin.math.abs(minutes) >= FLOW_EPSILON) parts += formatDelta(minutes, "m")
        if (kotlin.math.abs(count) >= FLOW_EPSILON) parts += formatDelta(count, "次")
        return if (parts.isEmpty()) "0m" else parts.joinToString(" / ")
    }

    /** 入流 = 额度发放 + 提前退还 + 重算增加；出流 = 休息 / 临时解锁 / 频繁返回扣减 + 重算减少。特权是独立池子，不计入。 */
    fun summarize(records: List<RestUsageRecord>): LedgerSummary {
        var inMinutes = 0.0
        var inCount = 0.0
        var outMinutes = 0.0
        var outCount = 0.0
        records.forEach { record ->
            val flow = flowOf(record)
            inMinutes += flow.inMinutes
            inCount += flow.inCount
            outMinutes += flow.outMinutes
            outCount += flow.outCount
        }
        return LedgerSummary(inMinutes, inCount, outMinutes, outCount)
    }

    fun summaryText(summary: LedgerSummary): String {
        val base = "入流 ${formatAmount(summary.inflowMinutes)}m / ${formatAmount(summary.inflowCount)}次" +
            " · 出流 ${formatAmount(summary.outflowMinutes)}m / ${formatAmount(summary.outflowCount)}次"
        return if (summary.inflowShort) "$base\n入流小于出流，记录可能不全（特权不计入）" else "$base（特权不计入）"
    }

    /** 「今天」范围内的核对：入流 = 出流 + 当前结余（额度每个业务日重新开始，所以只在单个业务日里成立）。 */
    data class LedgerReconcile(
        val balanceMinutes: Double,
        val balanceCount: Double,
        val diffMinutes: Double,
        val diffCount: Double
    ) {
        val isBalanced: Boolean
            get() = kotlin.math.abs(diffMinutes) < GAP_EPSILON_MINUTES && kotlin.math.abs(diffCount) < GAP_EPSILON_COUNT
    }

    fun reconcile(summary: LedgerSummary, balanceMinutes: Double, balanceHalfUnits: Int): LedgerReconcile {
        val balanceCount = balanceHalfUnits / 2.0
        return LedgerReconcile(
            balanceMinutes = balanceMinutes,
            balanceCount = balanceCount,
            diffMinutes = summary.inflowMinutes - summary.outflowMinutes - balanceMinutes,
            diffCount = summary.inflowCount - summary.outflowCount - balanceCount
        )
    }

    fun reconcileText(summary: LedgerSummary, reconcile: LedgerReconcile): String {
        fun pair(minutes: Double, count: Double) = "${formatAmount(minutes)}m / ${formatAmount(count)}次"
        val inflow = pair(summary.inflowMinutes, summary.inflowCount)
        val outflow = pair(summary.outflowMinutes, summary.outflowCount)
        val balance = pair(reconcile.balanceMinutes, reconcile.balanceCount)
        if (reconcile.isBalanced) {
            return "入流 $inflow = 出流 $outflow + 当前结余 $balance ✓（特权不计入）"
        }
        // 右边合计写出来，再说两边差多少，不用带符号的「差」（读着像「再加上就相等」）
        val rightMinutes = summary.outflowMinutes + reconcile.balanceMinutes
        val rightCount = summary.outflowCount + reconcile.balanceCount
        val gap = pair(kotlin.math.abs(reconcile.diffMinutes), kotlin.math.abs(reconcile.diffCount))
        val hasMore = reconcile.diffMinutes > GAP_EPSILON_MINUTES || reconcile.diffCount > GAP_EPSILON_COUNT
        val hasLess = reconcile.diffMinutes < -GAP_EPSILON_MINUTES || reconcile.diffCount < -GAP_EPSILON_COUNT
        val hint = when {
            hasMore && hasLess -> "时长和次数的偏差方向不同，下面的红字缺口提示可以帮你定位"
            hasMore -> "入流多了：可能有额度去向没记进流水"
            else -> "入流少了：可能有入流没记进流水"
        }
        return "入流 $inflow\n" +
            "出流 $outflow + 当前结余 $balance = ${pair(rightMinutes, rightCount)}\n" +
            "入流 ≠ 出流 + 结余，相差 $gap，$hint（特权不计入）"
    }

    /**
     * 详情里的一行。版式照主页：时长与次数左右并排、同等重要（没有的一项也写 `0次`）。
     * [minutes] 与 [count] 都为空时是整行文字（标题或说明）。
     */
    data class DetailLine(
        val label: String,
        val minutes: String = "",
        val count: String = "",
        /** 合计 / 结论行，加粗并与上面分隔 */
        val total: Boolean = false,
        /** 说明小字 */
        val muted: Boolean = false
    ) {
        val isFullRow: Boolean get() = minutes.isEmpty() && count.isEmpty()
    }

    /**
     * 展开后的详情：只写标题行（时间、额度变化、变动前、变动后）看不出来的信息。
     * 返回空 = 没有额外信息，卡片不可展开（简单的扣减不需要再念一遍标题）。
     *
     * 用词与主页、借用确认弹窗一致：本次预支、借用扣除 (×2)、实际生效。
     */
    fun detailLines(record: RestUsageRecord): List<DetailLine> {
        fun m(value: Double) = "${formatAmount(value)}m"
        fun times(value: Double) = "${formatAmount(value)}次"
        val lines = mutableListOf<DetailLine>()
        val costM = record.costMinutes
        val costC = costCountInTimes(record)
        val advM = record.advancedMinutes
        val advC = record.advancedCount

        when {
            record.type == TYPE_PRIVILEGE -> {
                if (record.reason.isNotBlank()) lines += DetailLine(record.reason)
                lines += DetailLine("特权时长", minutes = m(costM), total = true)
                lines += DetailLine("特权是独立池子，不占用休息额度", muted = true)
            }
            record.type == TYPE_REFUND -> {
                lines += DetailLine(record.reason.ifBlank { "提前结束，退还未用额度" })
                if (record.requestedMinutes > 0.0) {
                    lines += DetailLine("申请", minutes = m(record.requestedMinutes))
                    lines += DetailLine("实际用了", minutes = m(record.actualMinutes))
                }
                lines += DetailLine("退还未用", minutes = "+${m(-costM)}", count = if (costC == 0.0) "0次" else formatDelta(-costC, "次"), total = true)
            }
            record.type == TYPE_GRANT -> {
                if (record.reason.isNotBlank()) lines += DetailLine(record.reason)
                if (advM > 0.0 || advC > 0.0) {
                    val netCount = -costC
                    lines += DetailLine("总配额", minutes = m(-costM + advM), count = times(netCount + advC))
                    lines += DetailLine("借用扣除 (×2)", minutes = "-${m(advM)}", count = "-${times(advC)}")
                    lines += DetailLine("实际生效", minutes = m(-costM), count = times(netCount), total = true)
                }
            }
            hasAdvance(record) -> {
                val label = if (record.type.startsWith(TYPE_TEMP)) "临时解锁" else "休息"
                lines += DetailLine("申请$label ${m(record.requestedMinutes)}" + if (isCooling(record)) "（冷却中，整单 ×2）" else "")
                lines += DetailLine("本阶段扣除", minutes = m(costM - advM), count = times(costC - advC))
                lines += DetailLine("本次预支", minutes = m(advM / 2.0), count = times(advC / 2.0))
                lines += DetailLine("借用扣除 (×2)", minutes = "-${m(advM)}", count = "-${times(advC)}")
                lines += DetailLine("借用提前发放", minutes = "+${m(advM)}", count = "+${times(advC)}")
                lines += DetailLine("合计扣除", minutes = "-${m(costM)}", count = "-${times(costC)}", total = true)
                lines += DetailLine("提前发放 = 提前领取下一段额度，用来支付借用扣除", muted = true)
            }
            else -> {
                // 标题行已经说了的（普通扣减的数额）不重复；只留标题看不出来的
                if (record.reason.isNotBlank() && record.reason !in BOILERPLATE_REASONS) lines += DetailLine(record.reason)
                if (record.requestedMinutes > 0.0 && isCooling(record)) {
                    lines += DetailLine("申请 ${m(record.requestedMinutes)}，冷却中整单 ×2，扣除 −${m(costM)}")
                } else if (record.requestedMinutes > 0.0 && kotlin.math.abs(record.requestedMinutes - costM) > 0.05) {
                    lines += DetailLine("申请 ${m(record.requestedMinutes)}，扣除 −${m(costM)}")
                }
            }
        }
        return lines
    }

    /** 纯文本形式（测试与无障碍朗读用）：每行「标签 时长 次数」。 */
    fun detailText(record: RestUsageRecord): String = detailLines(record).joinToString("\n") { line ->
        listOf(line.label, line.minutes, line.count).filter { it.isNotEmpty() }.joinToString(" ")
    }

    /** 普通休息 / 临时解锁的固定原因：标题行的类型标签已经说明，不再展开重复。 */
    private val BOILERPLATE_REASONS = setOf("常规申请休息", "申请临时解锁", "冷却期申请休息（整单×2）", "冷却期申请临时解锁（整单×2）")

    /** 特权是独立池子，数量标签不叫「额度」，避免和休息额度混淆。 */
    fun poolLabel(record: RestUsageRecord): String =
        if (record.type == TYPE_PRIVILEGE) "特权池" else "额度"

    /** 整数不带小数点，否则保留 1 位；绝不截断（20.9 不能显示成 20）。 */
    fun formatAmount(value: Double): String {
        val rounded = kotlin.math.round(value * 10.0) / 10.0
        return if (rounded % 1.0 == 0.0) rounded.toLong().toString() else "%.1f".format(rounded)
    }

    /** 带符号的变动量，正数前加 `+`。 */
    fun formatDelta(value: Double, unit: String): String {
        val rounded = kotlin.math.round(value * 10.0) / 10.0
        val amount = formatAmount(rounded)
        return when {
            rounded > 0 -> "+$amount$unit"
            rounded < 0 -> "$amount$unit"
            else -> "0$unit"
        }
    }
}
