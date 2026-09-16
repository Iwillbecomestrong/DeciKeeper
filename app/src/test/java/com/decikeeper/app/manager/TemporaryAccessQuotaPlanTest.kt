package com.decikeeper.app.manager

import com.decikeeper.app.data.calculateTemporaryAccessQuotaPlan
import com.decikeeper.app.data.calculateTemporaryAccessRefundPlan
import com.decikeeper.app.data.calculateUnusedTemporaryAccessRefund
import com.decikeeper.app.data.resolveRefundSegmentIndex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test






class TemporaryAccessQuotaPlanTest {

    @Test
    fun refundsUnusedTemporaryAccessCharge() {
        assertEquals(1.5, calculateUnusedTemporaryAccessRefund(3.0, 180, 90), 0.001)
    }

    @Test
    fun neverRefundsAfterPlannedEnd() {
        assertEquals(0.0, calculateUnusedTemporaryAccessRefund(3.0, 180, 180), 0.001)
    }

    @Test
    fun rejectsInvalidDurationForRefund() {
        assertEquals(0.0, calculateUnusedTemporaryAccessRefund(3.0, 0, 10), 0.001)
    }

    @Test
    fun refundsEachLedgerComponentByUnusedRatio() {
        val refund = calculateTemporaryAccessRefundPlan(
            currentChargeSeconds = 60,
            nextChargeSeconds = 120,
            borrowChargeSeconds = 120,
            plannedSeconds = 180,
            elapsedSeconds = 90
        )

        assertEquals(30, refund.currentRefundSeconds)
        assertEquals(60, refund.nextRefundSeconds)
        assertEquals(60, refund.borrowRefundSeconds)
        assertEquals(90, refund.refundedQuotaSeconds)
    }

    @Test
    fun gapRefundTargetsPreviousSegment() {
        assertEquals(2, resolveRefundSegmentIndex(activeIndex = -1, previousIndex = 2))
        assertEquals(1, resolveRefundSegmentIndex(activeIndex = 1, previousIndex = 0))
        assertEquals(-1, resolveRefundSegmentIndex(activeIndex = -1, previousIndex = -1))
    }

    @Test
    fun splitsBothTimeAndCountAcrossCurrentAndNextStage() {
        val plan = calculateTemporaryAccessQuotaPlan(
            totalTimeCostSec = 150,
            currentAvailableSec = 30,
            nextAvailableSec = 240,
            totalCountCostHalfUnits = 2,
            currentAvailableCountHalfUnits = 1,
            nextAvailableCountHalfUnits = 2
        )

        assertEquals(150, plan.totalTimeCostSec)
        assertEquals(30, plan.currentTimeCostSec)
        assertEquals(120, plan.borrowedTimePrincipalSec)
        assertEquals(240, plan.nextTimeCostSec)
        assertEquals(2, plan.totalCountCostHalfUnits)
        assertEquals(1, plan.currentCountCostHalfUnits)
        assertEquals(1, plan.borrowedCountPrincipalHalfUnits)
        assertEquals(2, plan.nextCountCostHalfUnits)
        assertTrue(plan.needsNextStage)
        assertTrue(plan.isNextStageAffordable)
    }

    @Test
    fun supportsCountOnlyNextStageDeduction() {
        val plan = calculateTemporaryAccessQuotaPlan(
            totalTimeCostSec = 60,
            currentAvailableSec = 60,
            nextAvailableSec = 0,
            totalCountCostHalfUnits = 2,
            currentAvailableCountHalfUnits = 1,
            nextAvailableCountHalfUnits = 2
        )

        assertEquals(0, plan.nextTimeCostSec)
        assertEquals(2, plan.nextCountCostHalfUnits)
        assertEquals(2, plan.totalCountCostHalfUnits)
        assertEquals(1, plan.borrowedCountPrincipalHalfUnits)
        assertTrue(plan.needsNextStage)
        assertTrue(plan.isNextStageAffordable)
    }

    @Test
    fun supportsTimeOnlyNextStageDeduction() {
        val plan = calculateTemporaryAccessQuotaPlan(
            totalTimeCostSec = 150,
            currentAvailableSec = 30,
            nextAvailableSec = 240,
            totalCountCostHalfUnits = 1,
            currentAvailableCountHalfUnits = 1,
            nextAvailableCountHalfUnits = 0
        )

        assertEquals(240, plan.nextTimeCostSec)
        assertEquals(0, plan.nextCountCostHalfUnits)
        assertTrue(plan.needsNextStage)
        assertTrue(plan.isNextStageAffordable)
    }

    @Test
    fun blocksWhenNextStageCannotCoverAnyDimension() {
        val plan = calculateTemporaryAccessQuotaPlan(
            totalTimeCostSec = 150,
            currentAvailableSec = 30,
            nextAvailableSec = 60,
            totalCountCostHalfUnits = 3,
            currentAvailableCountHalfUnits = 1,
            nextAvailableCountHalfUnits = 1
        )

        assertFalse(plan.isNextStageAffordable)
        assertTrue(plan.hasInsufficientTime)
        assertTrue(plan.hasInsufficientCount)
    }

    @Test
    fun weightedWithoutBorrowKeepsNextStageUntouched() {
        val plan = calculateTemporaryAccessQuotaPlan(
            totalTimeCostSec = 150,
            currentAvailableSec = 600,
            nextAvailableSec = 0,
            totalCountCostHalfUnits = 0,
            currentAvailableCountHalfUnits = 3,
            nextAvailableCountHalfUnits = 0
        )

        assertEquals(150, plan.currentTimeCostSec)
        assertEquals(0, plan.borrowedTimePrincipalSec)
        assertEquals(0, plan.nextTimeCostSec)
        assertEquals(0, plan.currentCountCostHalfUnits)
        assertEquals(0, plan.borrowedCountPrincipalHalfUnits)
        assertEquals(0, plan.nextCountCostHalfUnits)
        assertFalse(plan.needsNextStage)
    }

    @Test
    fun normalBorrowUsesOnlyEachDimensionDeficitAsPrincipal() {
        val plan = calculateTemporaryAccessQuotaPlan(
            totalTimeCostSec = 480,
            currentAvailableSec = 420,
            nextAvailableSec = 1_050,
            totalCountCostHalfUnits = 1,
            currentAvailableCountHalfUnits = 0,
            nextAvailableCountHalfUnits = 3
        )

        assertEquals(480, plan.totalTimeCostSec)
        assertEquals(420, plan.currentTimeCostSec)
        assertEquals(60, plan.borrowedTimePrincipalSec)
        assertEquals(120, plan.nextTimeCostSec)
        assertEquals(1, plan.totalCountCostHalfUnits)
        assertEquals(0, plan.currentCountCostHalfUnits)
        assertEquals(1, plan.borrowedCountPrincipalHalfUnits)
        assertEquals(2, plan.nextCountCostHalfUnits)
    }

    @Test
    fun weightedBorrowDoesNotTurnFullWeightedRequestIntoBorrowPrincipal() {
        val plan = calculateTemporaryAccessQuotaPlan(
            totalTimeCostSec = 840,
            currentAvailableSec = 480,
            nextAvailableSec = 1_050,
            totalCountCostHalfUnits = 2,
            currentAvailableCountHalfUnits = 1,
            nextAvailableCountHalfUnits = 3
        )

        assertEquals(840, plan.totalTimeCostSec)
        assertEquals(360, plan.borrowedTimePrincipalSec)
        assertEquals(720, plan.nextTimeCostSec)
        assertEquals(1, plan.borrowedCountPrincipalHalfUnits)
        assertEquals(2, plan.nextCountCostHalfUnits)
        assertTrue(plan.isNextStageAffordable)
    }

    @Test
    fun debtCannotExceedBorrowPercentageCapacity() {
        val plan = calculateTemporaryAccessQuotaPlan(
            totalTimeCostSec = 840,
            currentAvailableSec = 0,
            nextAvailableSec = 1_050,
            totalCountCostHalfUnits = 2,
            currentAvailableCountHalfUnits = 0,
            nextAvailableCountHalfUnits = 3
        )

        assertEquals(1_680, plan.nextTimeCostSec)
        assertEquals(4, plan.nextCountCostHalfUnits)
        assertFalse(plan.isNextStageAffordable)
        assertTrue(plan.hasInsufficientTime)
        assertTrue(plan.hasInsufficientCount)
    }

    @Test
    fun subSixSecondDeficitDoesNotTriggerNextStageBorrow() {
        // 用户申请 2.4min (144s)，当前可用仅 143s (缺 1s < 6s / 0.1min)
        // 从第一性原理：不足法定货币单位的碎屑由当前阶段完全吸收，不立案借贷，杜绝"借入 0.0 分钟"
        val plan = calculateTemporaryAccessQuotaPlan(
            totalTimeCostSec = 144,
            currentAvailableSec = 143,
            nextAvailableSec = 600,
            totalCountCostHalfUnits = 1,
            currentAvailableCountHalfUnits = 2,
            nextAvailableCountHalfUnits = 2
        )

        assertEquals(144, plan.totalTimeCostSec)
        assertEquals(144, plan.currentTimeCostSec)
        assertEquals(0, plan.borrowedTimePrincipalSec)
        assertEquals(0, plan.nextTimeCostSec)
        assertFalse(plan.needsNextStage)
        assertTrue(plan.isNextStageAffordable)
    }

    @Test
    fun sixSecondOrMoreDeficitTriggersNextStageBorrow() {
        // 缺口 >= 6s (0.1min)，正常立案借贷
        val plan = calculateTemporaryAccessQuotaPlan(
            totalTimeCostSec = 150,
            currentAvailableSec = 144,
            nextAvailableSec = 600,
            totalCountCostHalfUnits = 1,
            currentAvailableCountHalfUnits = 2,
            nextAvailableCountHalfUnits = 2
        )

        assertEquals(150, plan.totalTimeCostSec)
        assertEquals(144, plan.currentTimeCostSec)
        assertEquals(6, plan.borrowedTimePrincipalSec)
        assertEquals(12, plan.nextTimeCostSec)
        assertTrue(plan.needsNextStage)
        assertTrue(plan.isNextStageAffordable)
    }
}
