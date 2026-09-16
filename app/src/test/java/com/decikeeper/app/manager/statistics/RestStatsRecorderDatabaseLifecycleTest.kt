package com.decikeeper.app.manager.statistics

import android.content.Context
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class RestStatsRecorderDatabaseLifecycleTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        RestStatsRecorder.clearAll(context)
    }

    @After
    fun tearDown() {
        RestStatsRecorder.clearAll(context)
    }

    @Test
    fun repeatedDatabaseLifecycleCycles_maintainConsistencyWithoutLeaks() {
        // Execute 100 complete cycles of record -> update -> read -> delete -> clear
        for (i in 1..100) {
            val record = RestStatsRecorder.record(
                context = context,
                type = "NORMAL",
                typeName = "常规休息",
                reason = "cycle_$i",
                requestedMinutes = 10.0,
                actualMinutes = 8.0,
                costMinutes = 8.0,
                costCount = 1.0,
                balanceMinutes = 50.0,
                balanceCount = 3
            )

            val recordsAfterInsert = RestStatsRecorder.getAllRecords(context)
            assertTrue("Records must contain inserted id in cycle $i", recordsAfterInsert.any { it.id == record.id })

            val updatedRecord = record.copy(reason = "updated_cycle_$i", actualMinutes = 10.0)
            val updateSuccess = RestStatsRecorder.updateRecord(context, updatedRecord)
            assertTrue("Update should succeed in cycle $i", updateSuccess)

            val recordsAfterUpdate = RestStatsRecorder.getAllRecords(context)
            val fetched = recordsAfterUpdate.firstOrNull { it.id == record.id }
            assertEquals("updated_cycle_$i", fetched?.reason)
            assertEquals(10.0, fetched?.actualMinutes ?: 0.0, 0.001)

            val deleteSuccess = RestStatsRecorder.deleteRecord(context, record.id)
            assertTrue("Delete should succeed in cycle $i", deleteSuccess)

            val recordsAfterDelete = RestStatsRecorder.getAllRecords(context)
            assertFalse("Records must not contain deleted id in cycle $i", recordsAfterDelete.any { it.id == record.id })

            RestStatsRecorder.clearAll(context)
            assertEquals(0, RestStatsRecorder.getAllRecords(context).size)
        }
    }
}
