/**
 * =================================================================================
 * [Context Header]
 * * @file: WeightedRestCountTest.kt
 * * @role: Test
 * * @description: 验证 LockWindowManager.calculateWeightedRestCountImpl 的加权次数公式。
 * *              公式: countCost = round((freeSec × 0 + dndNonCoolSec × 1 + coolOverlapSec × 2) / totalSec)
 * *              化简: = round((dndSec + coolOverlapSec) / totalSec)
 * *              clamp 到 [0, 2]
 * * @responsibilities:
 * 1. 覆盖所有阶段组合（空档/勿扰非冷却/冷却）以及混合场景
 * 2. 覆盖边界（totalSec=0、dndSec=0、四舍五入上下分界）
 * 3. 覆盖 Issue_Tracker 06-24 用户报告的回归点（之前的"按重叠比例"被错算成 0）
 * * @note: 直接测试顶层函数 `calculateWeightedRestCountImpl`，
 * *       而不是 `LockWindowManager.calculateWeightedRestCount`，
 * *       因为后者会触发 `object LockWindowManager` 的类初始化（其中包含 Looper.getMainLooper() 等 Android 调用）。
 * =================================================================================
 */
package com.decikeeper.app

import com.decikeeper.app.ui.calculateQuotaCountFromWeightedSeconds
import com.decikeeper.app.ui.calculateWeightedQuotaSeconds
import com.decikeeper.app.ui.calculateWeightedRestCountImpl
import com.decikeeper.app.ui.quantizeQuotaSecondsToTenthMinute
import org.junit.Assert.*
import org.junit.Test






class WeightedRestCountTest {

    @Test
    fun quantizesEachZoneBeforeApplyingMultiplier() {
        // 68s 显示为 1.1min；必须先量化为 66s 再 ×2，结果严格为 2.2min。
        assertEquals(66, quantizeQuotaSecondsToTenthMinute(68))
        assertEquals(132, calculateWeightedQuotaSeconds(normalDndSec = 0, coolingSec = 68))
    }

    @Test
    fun weightedZoneArithmeticMatchesOneDecimalUi() {
        // 409s→6.8min，71s→1.2min；6.8×1 + 1.2×2 = 9.2min。
        assertEquals(552, calculateWeightedQuotaSeconds(normalDndSec = 409, coolingSec = 71))
    }

    @Test
    fun countCalculationFollowsTieredHalvesAndOrderDoubling() {
        // 1. ≤3分钟 (180s) 基础扣 1 个 halfUnit (0.5次)，整单翻倍扣 2 个 halfUnits (1.0次)
        assertEquals(1, calculateQuotaCountFromWeightedSeconds(weightedCostSec = 180, requestedSec = 180))
        assertEquals(2, calculateQuotaCountFromWeightedSeconds(weightedCostSec = 360, requestedSec = 180))

        // 2. >3分钟 (如 480s) 基础扣 2 个 halfUnits (1.0次)，整单翻倍扣 4 个 halfUnits (2.0次)
        assertEquals(2, calculateQuotaCountFromWeightedSeconds(weightedCostSec = 480, requestedSec = 480))
        assertEquals(4, calculateQuotaCountFromWeightedSeconds(weightedCostSec = 960, requestedSec = 480))
    }

    @Test
    fun testAllInGap_returnsZero() {
        // 全在空档/休息 → 0 次
        val cost = calculateWeightedRestCountImpl(
            freeSec = 100,
            dndSec = 0,
            coolOverlapSec = 0,
            totalSec = 100
        )
        assertEquals("全在空档/休息应扣 0 次", 0, cost)
    }

    @Test
    fun testAllInDndNonCool_returnsOne() {
        // 全在勿扰非冷却 → 1 次
        val cost = calculateWeightedRestCountImpl(
            freeSec = 0,
            dndSec = 100,
            coolOverlapSec = 0,
            totalSec = 100
        )
        assertEquals("全在勿扰非冷却应扣 1 次", 1, cost)
    }

    @Test
    fun testAllInCooling_returnsTwo() {
        // 全在冷却 → 2 次
        val cost = calculateWeightedRestCountImpl(
            freeSec = 0,
            dndSec = 100,
            coolOverlapSec = 100,
            totalSec = 100
        )
        assertEquals("全在冷却应扣 2 次", 2, cost)
    }

    @Test
    fun testHalfDndHalfCool_roundsToTwo() {
        // 50/50 勿扰非冷却 + 冷却 → round(0.5×1 + 0.5×2) = round(1.5) = 2
        val cost = calculateWeightedRestCountImpl(
            freeSec = 0,
            dndSec = 100,
            coolOverlapSec = 50,
            totalSec = 100
        )
        assertEquals("50/50 勿扰/冷却应扣 2 次", 2, cost)
    }

    @Test
    fun testHalfGapHalfCool_roundsToOne() {
        // 50/50 空档 + 冷却 → round(0.5×0 + 0.5×2) = round(1.0) = 1
        // 这是 Issue_Tracker 06-24 用户报告的回归场景
        val cost = calculateWeightedRestCountImpl(
            freeSec = 50,
            dndSec = 50,
            coolOverlapSec = 50,
            totalSec = 100
        )
        assertEquals("50/50 空档/冷却应扣 1 次", 1, cost)
    }

    @Test
    fun testOneThirdEach_roundsToOne() {
        // 1/3 空档 + 1/3 勿扰非冷却 + 1/3 冷却
        // = round((0 + 1 + 2) / 3) = round(1.0) = 1
        val cost = calculateWeightedRestCountImpl(
            freeSec = 33,
            dndSec = 66,
            coolOverlapSec = 33,
            totalSec = 99
        )
        assertEquals("1/3+1/3+1/3 应扣 1 次", 1, cost)
    }

    @Test
    fun testTwoThirdsGapOneThirdCool_roundsToOne() {
        // 2/3 空档 + 1/3 冷却 → round((0×2 + 2×1) / 3) = round(0.667) = 1
        val cost = calculateWeightedRestCountImpl(
            freeSec = 66,
            dndSec = 33,
            coolOverlapSec = 33,
            totalSec = 99
        )
        assertEquals("2/3 空档 + 1/3 冷却应扣 1 次", 1, cost)
    }

    @Test
    fun testOneQuarterCoolThreeQuartersDndNonCool_roundsToOne() {
        // 25% 冷却 + 75% 勿扰非冷却 → round(0.25×2 + 0.75×1) = round(1.25) = 1
        val cost = calculateWeightedRestCountImpl(
            freeSec = 0,
            dndSec = 100,
            coolOverlapSec = 25,
            totalSec = 100
        )
        assertEquals("25/75 冷却/勿扰应扣 1 次", 1, cost)
    }

    @Test
    fun testTotalSecZero_returnsZero() {
        // 边界：总秒数 0 时返回 0（防止除以零）
        val cost = calculateWeightedRestCountImpl(
            freeSec = 0,
            dndSec = 0,
            coolOverlapSec = 0,
            totalSec = 0
        )
        assertEquals("总秒数为 0 应返回 0", 0, cost)
    }

    @Test
    fun testResultClampedToTwo_whenDndExceedsTotal() {
        // 防御性：异常输入（dndSec > totalSec）应被 clamp 到 2
        val cost = calculateWeightedRestCountImpl(
            freeSec = 0,
            dndSec = 200,
            coolOverlapSec = 200,
            totalSec = 100
        )
        assertEquals("异常输入应 clamp 到 2", 2, cost)
    }

    @Test
    fun testResultClampedToZero_whenAllNegative() {
        // 防御性：所有分量 ≤ 0 时返回 0
        val cost = calculateWeightedRestCountImpl(
            freeSec = -10,
            dndSec = -5,
            coolOverlapSec = -20,
            totalSec = 100
        )
        assertEquals("负数防御应返回 0", 0, cost)
    }

    @Test
    fun testRoundsDownWhenBelowHalf() {
        // 40% 冷却 + 60% 勿扰 → round(0.4×2 + 0.6×1) = round(1.4) = 1
        val cost = calculateWeightedRestCountImpl(
            freeSec = 0,
            dndSec = 100,
            coolOverlapSec = 40,
            totalSec = 100
        )
        assertEquals("40/60 冷却/勿扰应扣 1 次（half-up 舍去）", 1, cost)
    }

    @Test
    fun testRoundsUpAtExactlyHalf() {
        // 50% 冷却 + 50% 勿扰 → round(1.5) = 2 (half-up)
        val cost = calculateWeightedRestCountImpl(
            freeSec = 0,
            dndSec = 100,
            coolOverlapSec = 50,
            totalSec = 100
        )
        assertEquals("50/50 冷却/勿扰应扣 2 次（half-up 进位）", 2, cost)
    }

    @Test
    fun testCoolOverlapClampedToDnd() {
        // 防御性：coolOverlapSec > dndSec 应被 clamp 到 dndSec
        val cost = calculateWeightedRestCountImpl(
            freeSec = 0,
            dndSec = 50,
            coolOverlapSec = 100, // 异常：冷却超过 DND
            totalSec = 100
        )
        // 实际: dnd=50, cool(夹到 50) = (50 + 50)/100 = round(1.0) = 1
        assertEquals("coolOverlap > dnd 应 clamp", 1, cost)
    }
}
