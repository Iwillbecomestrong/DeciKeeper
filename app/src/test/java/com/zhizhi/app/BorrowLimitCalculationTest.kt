package com.zhizhi.app

import com.zhizhi.app.util.calculateBorrowRepaymentLimitCount
import com.zhizhi.app.util.calculateBorrowRepaymentLimitSeconds
import com.zhizhi.app.util.calculateMaxRawBorrowableCount
import com.zhizhi.app.util.calculateMaxRawBorrowableMinutes
import com.zhizhi.app.util.canOccupyBorrowRepaymentCapacity
import com.zhizhi.app.util.canOccupyBorrowRepaymentCount
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

        assertEquals(3, limitCount)
        assertTrue(canOccupyBorrowRepaymentCount(limitCount, alreadyBorrowedWeightedCount = 0, requestedWeightedCount = 2))
        assertFalse(canOccupyBorrowRepaymentCount(limitCount, alreadyBorrowedWeightedCount = 2, requestedWeightedCount = 2))
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
