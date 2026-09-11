/**
 * =================================================================================
 * [Context Header]
 * * @file: WeightedRestCountTest.kt
 * * @role: Test
 * * @description: 验证休息 / 临时解锁的计费口径。
 * *
 * *              **2026-09-11 产品决定：触及冷却期一律整单翻倍，不再按秒分区加权。**
 * *
 * *              次数: coolOverlapSec > 0 → 2；否则 round(dndSec / totalSec) clamp 到 [0, 2]
 * *              时长: coolingSec > 0 → quantize(总秒数) × 2；否则 quantize(勿扰秒数)
 * *
 * *              旧口径（按秒加权）会把"一半在空档、一半在冷却"这类混合场景算成 1 次，
 * *              与用户可见文案「时间与次数整单直接翻倍（×2）」不符，故收敛为平坦倍率。
 * * @responsibilities:
 * 1. 覆盖所有阶段组合（空档/勿扰非冷却/冷却）以及混合场景
 * 2. 覆盖边界（totalSec=0、dndSec=0、负值防御、clamp）
 * 3. 守护"只要触及冷却就翻倍"这条主口径
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
        // 68s 显示为 1.1min；量化为 66s 后整单 ×2，结果严格为 2.2min。
        assertEquals(66, quantizeQuotaSecondsToTenthMinute(68))
        assertEquals(132, calculateWeightedQuotaSeconds(normalDndSec = 0, coolingSec = 68))
    }

    @Test
    fun weightedZoneArithmeticMatchesOneDecimalUi() {
        // 触及冷却即整单翻倍：总量 409+71=480s → 8.0min → ×2 = 16.0min = 960s。
        // 旧口径（6.8×1 + 1.2×2 = 9.2min = 552s）已废弃。
        assertEquals(960, calculateWeightedQuotaSeconds(normalDndSec = 409, coolingSec = 71))
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
    fun testHalfGapHalfCool_stillDoubles() {
        // 50/50 空档 + 冷却 → 触及冷却即整单翻倍 → 2 次（旧口径为 1 次）
        val cost = calculateWeightedRestCountImpl(
            freeSec = 50,
            dndSec = 50,
            coolOverlapSec = 50,
            totalSec = 100
        )
        assertEquals("50/50 空档/冷却应扣 2 次", 2, cost)
    }

    @Test
    fun testOneThirdEach_stillDoubles() {
        // 1/3 空档 + 1/3 勿扰非冷却 + 1/3 冷却 → 触及冷却即整单翻倍 → 2 次（旧口径为 1 次）
        val cost = calculateWeightedRestCountImpl(
            freeSec = 33,
            dndSec = 66,
            coolOverlapSec = 33,
            totalSec = 99
        )
        assertEquals("1/3+1/3+1/3 应扣 2 次", 2, cost)
    }

    @Test
    fun testTwoThirdsGapOneThirdCool_stillDoubles() {
        // 2/3 空档 + 1/3 冷却 → 触及冷却即整单翻倍 → 2 次（旧口径为 1 次）
        val cost = calculateWeightedRestCountImpl(
            freeSec = 66,
            dndSec = 33,
            coolOverlapSec = 33,
            totalSec = 99
        )
        assertEquals("2/3 空档 + 1/3 冷却应扣 2 次", 2, cost)
    }

    @Test
    fun testOneQuarterCoolThreeQuartersDndNonCool_stillDoubles() {
        // 25% 冷却 + 75% 勿扰非冷却 → 触及冷却即整单翻倍 → 2 次（旧口径为 1 次）
        val cost = calculateWeightedRestCountImpl(
            freeSec = 0,
            dndSec = 100,
            coolOverlapSec = 25,
            totalSec = 100
        )
        assertEquals("25/75 冷却/勿扰应扣 2 次", 2, cost)
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
    fun testFortyPercentCool_stillDoubles() {
        // 40% 冷却 + 60% 勿扰 → 触及冷却即整单翻倍 → 2 次（旧口径为 1 次）
        val cost = calculateWeightedRestCountImpl(
            freeSec = 0,
            dndSec = 100,
            coolOverlapSec = 40,
            totalSec = 100
        )
        assertEquals("40/60 冷却/勿扰应扣 2 次", 2, cost)
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
        // 夹到 50 后仍 > 0 → 触及冷却 → 整单翻倍 = 2
        assertEquals("coolOverlap > dnd 应 clamp 后仍翻倍", 2, cost)
    }
}
