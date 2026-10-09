package com.decikeeper.app.manager.statistics

import com.decikeeper.app.data.statistics.RestUsageRecord

/**
 * 额度流水的类型筛选。
 *
 * 入流（加额度）：[QUOTA_GRANT]、[REFUND]；
 * 出流（减额度）：[NORMAL]、[BORROW]、[COOLING]、[DEDUCTION]；
 * [PRIVILEGE] 是每周特权的独立池子，不参与入流 / 出流对账。
 *
 * 常规 / 借用 / 冷却中针对休息与临时解锁：借用与冷却中可以同时成立，
 * 这样的记录会同时出现在两个筛选里；常规只含两者都不是的。
 */
enum class LedgerFilter(val displayName: String) {
    ALL("全部"),
    QUOTA_GRANT("额度发放"),
    REFUND("提前退还"),
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

    const val TYPE_NAME_GRANT = "额度发放"
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

    /** 入流 = 额度发放 + 提前退还；出流 = 休息 / 临时解锁 / 频繁返回扣减。特权是独立池子，不计入。 */
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
