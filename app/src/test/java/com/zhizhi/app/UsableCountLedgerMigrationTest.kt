package com.zhizhi.app

import com.zhizhi.app.data.UsableCountLedgerMigration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test



class UsableCountLedgerMigrationTest {
    @Test fun migratesLegacyWholeCountsOnce() {
        assertEquals(6, UsableCountLedgerMigration.migratedHalfUnits(null, 3))
        assertEquals(5, UsableCountLedgerMigration.migratedHalfUnits(5, 3))
    }
    @Test fun absentValuesStayAbsent() {
        assertNull(UsableCountLedgerMigration.migratedHalfUnits(null, null))
    }
    @Test(expected = ArithmeticException::class)
    fun overflowDoesNotProduceValue() {
        UsableCountLedgerMigration.migratedHalfUnits(null, Int.MAX_VALUE)
    }
    @Test fun failedCommitDoesNotAdoptNewKeyAndCanRetry() {
        val failed = UsableCountLedgerMigration.decide(null, 3, migrationWriteSucceeded = false)
        assertEquals(6, failed.halfUnits)
        assertEquals(false, failed.mayUseNewKey)
        val retried = UsableCountLedgerMigration.decide(null, 3, migrationWriteSucceeded = true)
        assertEquals(true, retried.mayUseNewKey)
    }
}
