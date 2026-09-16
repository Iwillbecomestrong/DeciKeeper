package com.decikeeper.app.manager

import com.decikeeper.app.data.TimeSegment
import com.decikeeper.app.manager.CreditManager
import com.decikeeper.app.manager.TimeRuleManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QuotaCarryForwardPolicyTest {
    @Test
    fun aNewOrImportedSegmentIsStampedAtSaveTime() {
        val created = TimeRuleManager.markNewSegments(
            inputSegments = listOf(TimeSegment(8, 15, 12, 0, 30, "上午")),
            beforeSegments = emptyList(),
            changeTimestamp = 2_000L
        ).single()
        val existing = TimeSegment(8, 15, 12, 0, 30, "上午", lastModified = 1_000L)
        val preserved = TimeRuleManager.markNewSegments(
            inputSegments = listOf(existing),
            beforeSegments = listOf(existing),
            changeTimestamp = 2_000L
        ).single()

        assertEquals(2_000L, created.lastModified)
        assertEquals(1_000L, preserved.lastModified)
    }

    @Test
    fun aSegmentChangedAfterItsOccurrenceStartedCannotGenerateSurplus() {
        assertFalse(CreditManager.isSegmentEligibleForSurplus(2_000L, 1_000L))
        assertTrue(CreditManager.isSegmentEligibleForSurplus(1_000L, 2_000L))
        assertTrue(CreditManager.isSegmentEligibleForSurplus(0L, 1_000L))
    }
}
