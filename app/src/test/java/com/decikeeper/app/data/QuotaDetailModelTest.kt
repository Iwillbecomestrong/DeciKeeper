package com.decikeeper.app.data

import com.decikeeper.app.data.buildSurplusSnapshotIdentity
import com.decikeeper.app.data.calculateQuotaDetail
import org.junit.Assert.assertEquals
import org.junit.Test




class QuotaDetailModelTest {
    @Test
    fun usesActualCarryInAsSurplusDenominator() {
        val model = calculateQuotaDetail(
            totalRemaining = 42.0,
            currentLimit = 35.0,
            carryRemaining = 7.0,
            carryTotal = 7.0
        )

        assertEquals(35.0, model.currentRemaining, 0.001)
        assertEquals(7.0, model.surplusRemaining, 0.001)
        assertEquals(7.0, model.surplusTotal, 0.001)
    }

    @Test
    fun keepsCarryInDenominatorAfterPartialConsumption() {
        val model = calculateQuotaDetail(5.0, 35.0, 5.0, 7.0)

        assertEquals(0.0, model.currentRemaining, 0.001)
        assertEquals(5.0, model.surplusRemaining, 0.001)
        assertEquals(7.0, model.surplusTotal, 0.001)
    }

    @Test
    fun clampsInconsistentInputs() {
        val model = calculateQuotaDetail(2.0, 35.0, 8.0, 7.0)

        assertEquals(0.0, model.currentRemaining, 0.001)
        assertEquals(2.0, model.surplusRemaining, 0.001)
        assertEquals(7.0, model.surplusTotal, 0.001)
    }

    @Test
    fun snapshotIdentityChangesAcrossBusinessDayOrSegment() {
        assertEquals("20260627|afternoon", buildSurplusSnapshotIdentity("20260627", "afternoon"))
        assertEquals("20260628|afternoon", buildSurplusSnapshotIdentity("20260628", "afternoon"))
    }
}
