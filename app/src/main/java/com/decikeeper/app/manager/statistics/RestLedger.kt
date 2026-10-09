package com.decikeeper.app.manager.statistics

import com.decikeeper.app.data.statistics.RestUsageRecord

/**
 * 额度流水的类型筛选。
 *
 * 入流（加额度）：[QUOTA_GRANT]、[REFUND]；
 * 出流（减额度）：[NORMAL]、[BORROW]、[COOLING]、[DEDUCTION]；
 * [RECALC]（时段被修改后结余的变化）按时长、次数各自的正负归入入流或出流；
 * [PRIVILEGE] 是每周特权的独立池子，不参与入流 / 出流对账。
 *
 * 常规 / 借用 / 冷却中针对休息与临时解锁：借用与冷却中可以同时成立，
 * 这样的记录会同时出现在两个筛选里；常规只含两者都不是的。
 */
enum class LedgerFilter(val displayName: String) {
    ALL("全部"),
    QUOTA_GRANT("额度发放"),
    REFUND("提前退还"),
    RECALC("额度重算"),
    NORMAL("常规"),
    BORROW("借用"),
    COOLING("冷却中"),
    DEDUCTION("频繁返回扣减"),
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

/** 流水列表的一项：一笔记录（带「使用前」），或相邻两笔之间接不上的缺口。 */
sealed interface LedgerEntry {
    /**
     * @param beforeMinutes 变动前结余 = 变动后结余 + 变动量；借用记录的变动量含下一段预扣，
     * 无法还原当前段的使用前，为 null（特权是独立池子，同样为 null）。
     */
    data class Row(
        val record: RestUsageRecord,
        val beforeMinutes: Double?,
        val beforeCount: Double?
    ) : LedgerEntry

    /**
     * 上一笔「变动后」与下一笔「使用前」对不上。正数 = 账面少了（有出口没记），负数 = 账面多了（有入流没记）。
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

    /** 借用明细：只写真正借了的维度（只借次数就不写时长）。 */
    fun borrowNote(
        borrowedTimePrincipalSec: Int,
        nextTimeCostSec: Int,
        borrowedCountPrincipalHalfUnits: Int,
        nextCountCostHalfUnits: Int
    ): String {
        val parts = mutableListOf<String>()
        if (borrowedTimePrincipalSec > 0) {
            parts += "借时长 ${formatAmount(borrowedTimePrincipalSec / 60.0)} 分钟，下一段预扣 ${formatAmount(nextTimeCostSec / 60.0)} 分钟"
        }
        if (borrowedCountPrincipalHalfUnits > 0) {
            parts += "借次数 ${formatAmount(borrowedCountPrincipalHalfUnits / 2.0)} 次，下一段预扣 ${formatAmount(nextCountCostHalfUnits / 2.0)} 次"
        }
        return parts.joinToString("；")
    }

    fun isRestLike(record: RestUsageRecord): Boolean =
        record.type == LEGACY_REST || record.type == LEGACY_BORROW ||
            record.type.startsWith(TYPE_REST) || record.type.startsWith(TYPE_TEMP)

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
        LedgerFilter.QUOTA_GRANT -> record.type == TYPE_GRANT
        LedgerFilter.REFUND -> record.type == TYPE_REFUND
        LedgerFilter.RECALC -> record.type == TYPE_RECALC
        LedgerFilter.DEDUCTION -> record.type == TYPE_PENALTY
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

    /** 这条记录是否参与「变动后 → 下一笔使用前」的连续性核对（特权不动休息额度，不参与）。 */
    private fun isChained(record: RestUsageRecord): Boolean = record.type != TYPE_PRIVILEGE

    private fun beforeOf(record: RestUsageRecord): Pair<Double, Double>? {
        if (!isChained(record)) return null
        // 借用记录的变动量含下一段预扣，不等于当前结余的变化，还原不出使用前
        if (isBorrow(record)) return null
        return (record.balanceMinutes + record.costMinutes) to
            (balanceCountInTimes(record) + costCountInTimes(record))
    }

    /**
     * 把流水（最新在前）整理成列表项：每笔带「使用前」，并在相邻两笔接不上时插入缺口提示。
     *
     * 缺口 = 较早一笔的「变动后」 − 较新一笔的「使用前」，说明这两笔之间有变动没进流水
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

            // 与「较早的、参与核对的」下一笔比较：这一笔的使用前，应等于它的变动后
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

    /** 入流 = 额度发放 + 提前退还 + 重算增加；出流 = 休息 / 临时解锁 / 频繁返回扣减 + 重算减少。特权是独立池子，不计入。 */
    fun summarize(records: List<RestUsageRecord>): LedgerSummary {
        var inMinutes = 0.0
        var inCount = 0.0
        var outMinutes = 0.0
        var outCount = 0.0
        records.forEach { record ->
            when {
                record.type == TYPE_GRANT || record.type == TYPE_REFUND -> {
                    inMinutes += -record.costMinutes
                    inCount += -costCountInTimes(record)
                }
                isRestLike(record) || record.type == TYPE_PENALTY -> {
                    outMinutes += record.costMinutes
                    outCount += costCountInTimes(record)
                }
                record.type == TYPE_RECALC -> {
                    // 重算的时长与次数可能一增一减，各自按正负归入入流 / 出流
                    if (record.costMinutes >= 0.0) outMinutes += record.costMinutes else inMinutes += -record.costMinutes
                    if (record.costCount >= 0.0) outCount += record.costCount else inCount += -record.costCount
                }
            }
        }
        return LedgerSummary(inMinutes, inCount, outMinutes, outCount)
    }

    fun summaryText(summary: LedgerSummary): String {
        val base = "入流 ${formatAmount(summary.inflowMinutes)}m / ${formatAmount(summary.inflowCount)}次" +
            " · 出流 ${formatAmount(summary.outflowMinutes)}m / ${formatAmount(summary.outflowCount)}次"
        return if (summary.inflowShort) "$base\n入流小于出流，记录可能不全（特权不计入）" else "$base（特权不计入）"
    }

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
