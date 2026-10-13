package com.decikeeper.app.manager

import com.decikeeper.app.util.calculateBorrowRepaymentLimitCount
import com.decikeeper.app.util.calculateBorrowRepaymentLimitSeconds
import com.decikeeper.app.util.calculateMaxRawBorrowableCount
import com.decikeeper.app.util.calculateMaxRawBorrowableMinutes
import com.decikeeper.app.util.canOccupyBorrowRepaymentCapacity
import com.decikeeper.app.util.canOccupyBorrowRepaymentCount
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test








class BorrowLimitCalculationTest {

    @Test
    fun durationBorrowLimitUsesWeightedRepaymentCapacity() {
        val limitSec = calculateBorrowRepaymentLimitSeconds(
            nextLimitMinutes = 30,
            borrowPercent = 50
        )

        assertEquals(15 * 60, limitSec)
        assertTrue(canOccupyBorrowRepaymentCapacity(limitSec, alreadyBorrowedWeightedSec = 0, requestedWeightedSec = 15 * 60))
        assertFalse(canOccupyBorrowRepaymentCapacity(limitSec, alreadyBorrowedWeightedSec = 15 * 60, requestedWeightedSec = 60))
    }

    @Test
    fun rawBorrowMinutesAreDerivedFromRemainingWeightedCapacity() {
        val rawMinutes = calculateMaxRawBorrowableMinutes(
            nextLimitMinutes = 30,
            borrowPercent = 50,
            alreadyBorrowedWeightedSec = 0,
            perRawSecondWeight = 2
        )

        assertEquals(7.5, rawMinutes, 0.0001)
    }

    @Test
    fun countBorrowLimitUsesOccupiedRepaymentCount() {
        val limitCount = calculateBorrowRepaymentLimitCount(
            nextCountLimit = 6,
            borrowPercent = 50
        )

        // 6 半次(=3次) * 50% = 1.5次 -> 向下取整到整数次 = 1次 = 2 半次
        assertEquals(2, limitCount)
        assertTrue(canOccupyBorrowRepaymentCount(limitCount, alreadyBorrowedWeightedCount = 0, requestedWeightedCount = 2))
        assertFalse(canOccupyBorrowRepaymentCount(limitCount, alreadyBorrowedWeightedCount = 2, requestedWeightedCount = 2))
    }

    @Test
    fun repaymentCountIsFlooredToWholeCounts() {
        // 3次 * 50% = 1.5次 -> 1次
        assertEquals(2, calculateBorrowRepaymentLimitCount(nextCountLimit = 6, borrowPercent = 50))
        // 3.5次 * 50% = 1.75次 -> 1次
        assertEquals(2, calculateBorrowRepaymentLimitCount(nextCountLimit = 7, borrowPercent = 50))
        // 3次 * 100% = 3次
        assertEquals(6, calculateBorrowRepaymentLimitCount(nextCountLimit = 6, borrowPercent = 100))
        // 1次 * 50% = 0.5次 -> 0
        assertEquals(0, calculateBorrowRepaymentLimitCount(nextCountLimit = 2, borrowPercent = 50))
        assertEquals(0, calculateBorrowRepaymentLimitCount(nextCountLimit = 6, borrowPercent = 0))
    }

    @Test
    fun minimumRawBorrowedPrincipalIsHalfCount() {
        // 下阶段3次、50%：还款额度 1次，借一还二 -> 最多借 0.5次(=1 半次)
        assertEquals(
            1,
            calculateMaxRawBorrowableCount(nextCountLimit = 6, borrowPercent = 50, alreadyBorrowedWeightedCount = 0)
        )
        // 下阶段3.5次、50%：同样只有 0.5次
        assertEquals(
            1,
            calculateMaxRawBorrowableCount(nextCountLimit = 7, borrowPercent = 50, alreadyBorrowedWeightedCount = 0)
        )
        // 下阶段3次、100%：还款 3次 -> 本金 1.5次(=3 半次)
        assertEquals(
            3,
            calculateMaxRawBorrowableCount(nextCountLimit = 6, borrowPercent = 100, alreadyBorrowedWeightedCount = 0)
        )
    }

    @Test
    fun rawBorrowCountsAreDerivedFromRemainingWeightedCountCapacity() {
        val rawCount = calculateMaxRawBorrowableCount(
            nextCountLimit = 6,
            borrowPercent = 50,
            alreadyBorrowedWeightedCount = 0,
            perRawCountWeight = 2
        )

        assertEquals(1, rawCount)
    }

    @Test
    fun fourNextCountsAtFiftyPercentAllowOnlyOneRawBorrowedCount() {
        val rawCount = calculateMaxRawBorrowableCount(
            nextCountLimit = 4,
            borrowPercent = 50,
            alreadyBorrowedWeightedCount = 0
        )

        assertEquals(1, rawCount)
    }
}
