package com.decikeeper.app.manager

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 频繁返回扣减的纯 Kotlin 规则：时长翻倍、窗口进度提示、回执内容。 */
class FrequentReturnDeductionTest {

    @Test
    fun deductSecondsDoublesDuringAnyCooling() {
        assertEquals(30, FrequentReturnDeduction.deductSeconds(isAnyCooling = false))
        assertEquals(60, FrequentReturnDeduction.deductSeconds(isAnyCooling = true))
    }

    @Test
    fun hintHiddenWithoutHitsOrAfterWindowExpired() {
        assertNull(FrequentReturnDeduction.hintText(count = 0, windowStart = 1_000L, now = 2_000L))
        assertNull(
            FrequentReturnDeduction.hintText(
                count = 2,
                windowStart = 1_000L,
                now = 1_000L + FrequentReturnDeduction.WINDOW_MS + 1
            )
        )
    }

    @Test
    fun hintShowsCountElapsedAndWindowRemaining() {
        val hint = FrequentReturnDeduction.hintText(count = 2, windowStart = 10_000L, now = 22_400L)
        assertNotNull(hint)
        assertTrue(hint!!, hint.contains("已返回桌面 2/5 次"))
        assertFalse(hint, hint.contains("距首次"))
        // 30s 窗口，已过 12.4s，剩余向上取整为 18s
        assertTrue(hint, hint.contains("窗口剩余 18s"))
        assertFalse(hint, hint.contains("再返回"))
    }

    @Test
    fun hintWarnsBeforeTheDeductingReturn() {
        val hint = FrequentReturnDeduction.hintText(count = 4, windowStart = 0L, now = 20_000L)!!
        assertTrue(hint, hint.contains("已返回桌面 4/5 次"))
        assertTrue(hint, hint.contains("再返回 1 次将扣减额度"))
    }

    @Test
    fun hintHiddenOnceThresholdReached() {
        assertNull(FrequentReturnDeduction.hintText(count = 5, windowStart = 0L, now = 1_000L))
    }

    @Test
    fun hintAtWindowBoundaryStillInsideWindow() {
        val hint = FrequentReturnDeduction.hintText(
            count = 1,
            windowStart = 0L,
            now = FrequentReturnDeduction.WINDOW_MS
        )!!
        assertFalse(hint, hint.contains("距首次"))
        assertTrue(hint, hint.contains("窗口剩余 0s"))
    }

    private fun receipt(
        result: PenaltyChargeResult,
        isAnyCooling: Boolean = false,
        inCooling: Boolean = false,
        inTempCooling: Boolean = false
    ): String = FrequentReturnDeduction.receiptText(
        result = result,
        isAnyCooling = isAnyCooling,
        inRestCooling = inCooling,
        inTempCooling = inTempCooling,
        coolExtendSec = 30L,
        remainingTimeText = "12.5min",
        remainingCountText = "3.5次"
    )

    @Test
    fun receiptUsesNewNameAndNeverPenaltyWording() {
        val text = receipt(PenaltyChargeResult(timeDeductSec = 30, normalTimeSec = 30, currentCountCostHalfUnits = 1))
        assertTrue(text, text.contains("30秒内返回桌面5次，触发频繁返回扣减"))
        assertFalse(text, text.contains("惩罚"))
    }

    @Test
    fun receiptShowsRemainingQuotaDistinctFromDeduction() {
        val text = receipt(PenaltyChargeResult(timeDeductSec = 30, normalTimeSec = 30, currentCountCostHalfUnits = 1))
        assertTrue(text, text.contains("扣除时长: 30s"))
        assertTrue(text, text.contains("扣除次数: 0.5次"))
        assertTrue(text, text.contains("当前剩余额度"))
        assertTrue(text, text.contains("时长: 12.5min"))
        assertTrue(text, text.contains("次数: 3.5次"))
        // 旧回执把「本次从当前段扣掉的秒数」误标为「当前额度」
        assertFalse(text, text.contains("• 当前额度:"))
    }

    @Test
    fun receiptMarksDoubledDeductionInCooling() {
        val text = receipt(
            PenaltyChargeResult(timeDeductSec = 60, normalTimeSec = 60, currentCountCostHalfUnits = 2),
            isAnyCooling = true,
            inCooling = true,
            inTempCooling = true
        )
        assertTrue(text, text.contains("扣除时长: 60s (冷却中翻倍)"))
        assertTrue(text, text.contains("扣除次数: 1次 (冷却中翻倍)"))
        assertTrue(text, text.contains("休息冷却期延长: +30s"))
        assertTrue(text, text.contains("临时解锁冷却期延长: +30s"))
    }

    @Test
    fun receiptKeepsBorrowBreakdown() {
        val text = receipt(
            PenaltyChargeResult(
                timeDeductSec = 30,
                normalTimeSec = 10,
                borrowedTimeSec = 40,
                borrowedTimeCostSec = 40,
                totalTimeQuotaSec = 50,
                currentCountCostHalfUnits = 0,
                borrowedCountPrincipalHalfUnits = 1,
                nextCountCostHalfUnits = 2
            )
        )
        assertTrue(text, text.contains("从当前段扣 10s(×1)"))
        assertTrue(text, text.contains("借用下一段 40s(×2) → 扣40s"))
        assertTrue(text, text.contains("借用下一段次数: 1次(×2)"))
    }
}
