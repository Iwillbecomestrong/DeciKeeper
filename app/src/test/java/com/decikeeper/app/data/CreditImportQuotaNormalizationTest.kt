package com.decikeeper.app.data

import com.decikeeper.app.data.TimeSegment
import com.decikeeper.app.manager.CreditManager
import org.junit.Assert.assertEquals
import org.junit.Test

class CreditImportQuotaNormalizationTest {

    private val segment = TimeSegment(
        startHour = 10,
        startMinute = 0,
        endHour = 12,
        endMinute = 0,
        limitMins = 60,
        usableCountLimitHalfUnits = 8
    )

    @Test
    fun completedSegmentHasNoImportedTimeOrCountSurplus() {
        val normalized = CreditManager.normalizeImportedSegmentUsage(
            segment = segment,
            phase = CreditManager.ImportedSegmentPhase.COMPLETED,
            elapsedMillis = 0L,
            importedTimeUsedSeconds = 0,
            importedCountUsedHalfUnits = 0
        )

        assertEquals(60 * 60, normalized.timeUsedSeconds)
        assertEquals(8, normalized.countUsedHalfUnits)
    }

    @Test
    fun currentSegmentUsesRoundedElapsedProgress() {
        val normalized = CreditManager.normalizeImportedSegmentUsage(
            segment = segment,
            phase = CreditManager.ImportedSegmentPhase.CURRENT,
            elapsedMillis = 60 * 60 * 1000L,
            importedTimeUsedSeconds = 0,
            importedCountUsedHalfUnits = 0
        )

        assertEquals(30 * 60, normalized.timeUsedSeconds)
        assertEquals(4, normalized.countUsedHalfUnits)
    }

    @Test
    fun currentSegmentKeepsLargerActualUsageButClampsToItsOwnQuota() {
        val normalized = CreditManager.normalizeImportedSegmentUsage(
            segment = segment,
            phase = CreditManager.ImportedSegmentPhase.CURRENT,
            elapsedMillis = 60 * 60 * 1000L,
            importedTimeUsedSeconds = 2_400,
            importedCountUsedHalfUnits = 10
        )

        assertEquals(2_400, normalized.timeUsedSeconds)
        assertEquals(8, normalized.countUsedHalfUnits)
    }

    @Test
    fun futureSegmentDoesNotImportUsageOrBorrowedQuota() {
        val normalized = CreditManager.normalizeImportedSegmentUsage(
            segment = segment,
            phase = CreditManager.ImportedSegmentPhase.FUTURE,
            elapsedMillis = 0L,
            importedTimeUsedSeconds = 3_600,
            importedCountUsedHalfUnits = 8
        )

        assertEquals(0, normalized.timeUsedSeconds)
        assertEquals(0, normalized.countUsedHalfUnits)
    }
}
