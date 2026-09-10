package com.decikeeper.app

import com.decikeeper.app.data.CountAction
import com.decikeeper.app.data.UsableCount
import com.decikeeper.app.data.formatUsableCountHalfUnits
import org.junit.Assert.*
import org.junit.Test





class UsableCountTest {
    @Test fun actionBases() {
        assertEquals(1, CountAction.TEMPORARY_ACCESS.baseHalfUnits)
        assertEquals(2, CountAction.REST.baseHalfUnits)
    }
    @Test fun automaticRoundsAndHasMinimum() {
        assertEquals(3, UsableCount.automaticHalfUnits(8))
        assertEquals(4, UsableCount.automaticHalfUnits(9))
        assertEquals(2, UsableCount.automaticHalfUnits(3))
    }
    @Test fun cooldownWeightsOnceAndClampsOverlap() {
        assertEquals(1, UsableCount.weightedHalfUnits(1, 100, 0))
        assertEquals(2, UsableCount.weightedHalfUnits(1, 100, 100))
        assertEquals(3, UsableCount.weightedHalfUnits(2, 100, 50))
        assertEquals(1, UsableCount.weightedHalfUnits(1, 100, -10))
        assertEquals(2, UsableCount.weightedHalfUnits(1, 100, 1000))
        assertNull(UsableCount.weightedHalfUnitsOrNull(1, 0, 0))
    }
    @Test fun midpointRoundsUp() {
        assertEquals(3, UsableCount.parseHalfUnits("1.25"))
        assertEquals(2, UsableCount.parseHalfUnits("1.24"))
    }
    @Test fun parsesAutoAndRejectsInvalid() {
        assertEquals(UsableCount.AUTO_HALF_UNITS, UsableCount.parseHalfUnits(" "))
        assertNull(UsableCount.parseHalfUnits("x"))
        assertNull(UsableCount.parseHalfUnits("-0.5"))
    }
    @Test fun formatsWithoutRedundantDecimal() {
        assertEquals("2", formatUsableCountHalfUnits(4))
        assertEquals("2.5", formatUsableCountHalfUnits(5))
        assertEquals("2.5", formatUsableCountHalfUnits(5))
    }
}
