package com.decikeeper.app.manager

import com.decikeeper.app.data.formatUsableCountHalfUnits

/**
 * 「频繁返回扣减」的纯 Kotlin 规则与文案：窗口参数、冷却期时长翻倍、锁屏进度提示、扣减回执。
 * 计数状态与账本提交仍由 LockWindowManager / CreditManager 持有；本对象不依赖 Android。
 */
internal object FrequentReturnDeduction {
    const val THRESHOLD = 5
    const val WINDOW_MS = 30_000L
    const val BASE_DEDUCT_SEC = 30
    const val COOL_EXTEND_MS = 30_000L

    /** 休息冷却或临时解锁冷却期间，扣除时长整单翻倍（次数翻倍在 CreditManager 内按 isCooling 处理）。 */
    fun deductSeconds(isAnyCooling: Boolean): Int =
        if (isAnyCooling) BASE_DEDUCT_SEC * 2 else BASE_DEDUCT_SEC

    /** 无命中、窗口已过期或已到阈值（由回执接管）时返回 null。 */
    fun hintText(count: Int, windowStart: Long, now: Long): String? {
        val elapsedMs = now - windowStart
        if (count <= 0 || count >= THRESHOLD || elapsedMs > WINDOW_MS) return null
        val elapsedSec = (elapsedMs.coerceAtLeast(0L) / 1000L).toInt()
        val remainingSec = ((WINDOW_MS - elapsedMs.coerceAtLeast(0L) + 999L) / 1000L).toInt().coerceAtLeast(0)
        return buildString {
            append("⚠️ ${WINDOW_MS / 1000}秒内已返回桌面 $count/$THRESHOLD 次")
            append(" · 距首次 ${elapsedSec}s · 窗口剩余 ${remainingSec}s")
            if (count == THRESHOLD - 1) append("\n再返回 1 次将扣减额度")
        }
    }

    fun receiptText(
        result: PenaltyChargeResult,
        isAnyCooling: Boolean,
        inRestCooling: Boolean,
        inTempCooling: Boolean,
        coolExtendSec: Long,
        remainingTimeText: String,
        remainingCountText: String
    ): String = buildString {
        append("⚠️ ${WINDOW_MS / 1000}秒内返回桌面${THRESHOLD}次，触发频繁返回扣减\n\n")
        append("【本次扣除】\n")
        append("• 扣除时长: ${result.timeDeductSec}s")
        if (isAnyCooling) append(" (冷却中翻倍)")
        append("\n")
        if (result.borrowedTimeSec > 0) {
            if (result.normalTimeSec > 0) {
                append("• 从当前段扣 ${result.normalTimeSec}s(×1)\n")
            }
            append("• 借用下一段 ${result.borrowedTimeSec}s(×2) → 扣${result.borrowedTimeCostSec}s\n")
            append("• 合计消耗额度: ${result.totalTimeQuotaSec}s\n")
        }
        if (result.borrowedCountPrincipalHalfUnits > 0) {
            append("• 扣除次数: 当前扣除 ${formatUsableCountHalfUnits(result.currentCountCostHalfUnits)}次\n")
            append("• 借用下一段次数: ${formatUsableCountHalfUnits(result.nextCountCostHalfUnits)}次(×2)\n")
        } else {
            append("• 扣除次数: ${formatUsableCountHalfUnits(result.currentCountCostHalfUnits)}次")
            if (isAnyCooling) append(" (冷却中翻倍)")
            append("\n")
        }
        if (inRestCooling) append("• 休息冷却期延长: +${coolExtendSec}s\n")
        if (inTempCooling) append("• 临时解锁冷却期延长: +${coolExtendSec}s\n")
        append("\n【当前剩余额度】\n")
        append("• 时长: $remainingTimeText\n")
        append("• 次数: $remainingCountText")
    }
}
