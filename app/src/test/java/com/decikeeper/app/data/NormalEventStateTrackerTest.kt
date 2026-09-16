package com.decikeeper.app.data

import com.decikeeper.app.manager.DuplicateLockKey
import com.decikeeper.app.manager.InitialStatePolicy
import com.decikeeper.app.manager.NormalEventStateTracker
import com.decikeeper.app.manager.StateKey
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test






/**
 * RED contract for normal event state transitions and caller-classified repeated locks.
 *
 * The tracker deliberately has no knowledge of lock policy: callers provide both the
 * state key and a fully classified duplicate-lock key.  Its only job is to maintain an
 * in-process baseline and aggregate exactly matching repeated displays by monotonic time.
 */
class NormalEventStateTrackerTest {

    @Test
    fun silentBaselineDoesNotEmitUntilTheStateActuallyChanges() {
        val tracker = NormalEventStateTracker()
        val key = StateKey(domain = "protection", entityId = "service", stateName = "running")

        assertFalse(tracker.shouldEmit(key, value = "READY", initialPolicy = InitialStatePolicy.SILENT_BASELINE))
        assertFalse(tracker.shouldEmit(key, value = "READY", initialPolicy = InitialStatePolicy.SILENT_BASELINE))
        assertTrue(tracker.shouldEmit(key, value = "STOPPED", initialPolicy = InitialStatePolicy.SILENT_BASELINE))
        assertFalse(tracker.shouldEmit(key, value = "STOPPED", initialPolicy = InitialStatePolicy.SILENT_BASELINE))
    }

    @Test
    fun emitInitialEmitsOnceThenStaysSilentForAnUnchangedValue() {
        val tracker = NormalEventStateTracker()
        val key = StateKey(domain = "allowance", entityId = "com.example.video", stateName = "state")

        assertTrue(tracker.shouldEmit(key, value = "NONE", initialPolicy = InitialStatePolicy.EMIT_INITIAL))
        assertFalse(tracker.shouldEmit(key, value = "NONE", initialPolicy = InitialStatePolicy.EMIT_INITIAL))
        assertTrue(tracker.shouldEmit(key, value = "TEMP_ACCESS", initialPolicy = InitialStatePolicy.EMIT_INITIAL))
    }

    @Test
    fun stateKeysAreIsolatedByDomainEntityAndStateName() {
        val tracker = NormalEventStateTracker()
        val baseline = StateKey(domain = "protection", entityId = "service-a", stateName = "state")
        val differentDomain = baseline.copy(domain = "service")
        val differentEntity = baseline.copy(entityId = "service-b")
        val differentStateName = baseline.copy(stateName = "permission")

        assertFalse(tracker.shouldEmit(baseline, "READY", InitialStatePolicy.SILENT_BASELINE))
        assertFalse(tracker.shouldEmit(differentDomain, "READY", InitialStatePolicy.SILENT_BASELINE))
        assertFalse(tracker.shouldEmit(differentEntity, "READY", InitialStatePolicy.SILENT_BASELINE))
        assertFalse(tracker.shouldEmit(differentStateName, "READY", InitialStatePolicy.SILENT_BASELINE))
        assertTrue(tracker.shouldEmit(baseline, "STOPPED", InitialStatePolicy.SILENT_BASELINE))
        assertFalse(tracker.shouldEmit(differentDomain, "READY", InitialStatePolicy.SILENT_BASELINE))
        assertFalse(tracker.shouldEmit(differentEntity, "READY", InitialStatePolicy.SILENT_BASELINE))
        assertFalse(tracker.shouldEmit(differentStateName, "READY", InitialStatePolicy.SILENT_BASELINE))
    }

    @Test
    fun concurrentSameInitialUpdateEmitsExactlyOnce() {
        val tracker = NormalEventStateTracker()
        val key = StateKey(domain = "service", entityId = "monitor", stateName = "state")
        val ready = CountDownLatch(2)
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        val results = Collections.synchronizedList(mutableListOf<Boolean>())

        try {
            val futures = (1..2).map {
                executor.submit {
                    ready.countDown()
                    assertTrue(start.await(5, TimeUnit.SECONDS))
                    results += tracker.shouldEmit(key, "RUNNING", InitialStatePolicy.EMIT_INITIAL)
                }
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS))
            start.countDown()
            futures.forEach { it.get(5, TimeUnit.SECONDS) }
        } finally {
            executor.shutdownNow()
        }

        assertEquals(listOf(false, true), results.sorted())
    }

    @Test
    fun testOnlyResetDropsTheInProcessBaselineAndAllowsRebaselining() {
        val tracker = NormalEventStateTracker()
        val key = StateKey(domain = "quota", entityId = "daily", stateName = "remaining")

        assertFalse(tracker.shouldEmit(key, "3", InitialStatePolicy.SILENT_BASELINE))
        assertTrue(tracker.shouldEmit(key, "2", InitialStatePolicy.SILENT_BASELINE))

        tracker.resetForTest()

        assertFalse(tracker.shouldEmit(key, "2", InitialStatePolicy.SILENT_BASELINE))
        assertTrue(tracker.shouldEmit(key, "1", InitialStatePolicy.SILENT_BASELINE))
    }

    @Test
    fun duplicateLocksAggregateOnlyWhenAllFourCallerClassifiedFieldsMatch() {
        val tracker = NormalEventStateTracker()
        val key = DuplicateLockKey(
            originalRequestId = "LOCK-001",
            reason = "BLACKLIST_SELECTED",
            packageName = "com.example.video",
            result = "LOCKED"
        )
        val differentRequest = key.copy(originalRequestId = "LOCK-002")
        val differentReason = key.copy(reason = "TIME_WINDOW")
        val differentPackage = key.copy(packageName = "com.example.other")
        val differentResult = key.copy(result = "REQUESTED")

        tracker.recordDuplicateLock(key, occurredAtElapsedMs = 100L)
        tracker.recordDuplicateLock(key, occurredAtElapsedMs = 140L)
        tracker.recordDuplicateLock(differentRequest, occurredAtElapsedMs = 150L)
        tracker.recordDuplicateLock(differentReason, occurredAtElapsedMs = 160L)
        tracker.recordDuplicateLock(differentPackage, occurredAtElapsedMs = 170L)
        tracker.recordDuplicateLock(differentResult, occurredAtElapsedMs = 180L)

        val summary = tracker.flushDuplicateLock(key, endedAtElapsedMs = 250L)
        assertNotNull(summary)
        assertEquals(key, summary!!.key)
        assertEquals(2, summary.duplicateCount)
        assertEquals(100L, summary.firstElapsedMs)
        assertEquals(140L, summary.lastElapsedMs)
        assertEquals(150L, summary.durationMs)
        assertEquals(1, tracker.flushDuplicateLock(differentRequest, 250L)!!.duplicateCount)
        assertEquals(1, tracker.flushDuplicateLock(differentReason, 250L)!!.duplicateCount)
        assertEquals(1, tracker.flushDuplicateLock(differentPackage, 250L)!!.duplicateCount)
        assertEquals(1, tracker.flushDuplicateLock(differentResult, 250L)!!.duplicateCount)
    }

    @Test
    fun flushingARepeatSequenceIsOneShotAndNeverSynthesizesAnotherSummary() {
        val tracker = NormalEventStateTracker()
        val key = DuplicateLockKey("LOCK-009", "BLACKLIST_SELECTED", "com.example.video", "LOCKED")

        tracker.recordDuplicateLock(key, occurredAtElapsedMs = 1_000L)

        val first = tracker.flushDuplicateLock(key, endedAtElapsedMs = 1_090L)
        assertNotNull(first)
        assertEquals(90L, first!!.durationMs)
        assertNull(tracker.flushDuplicateLock(key, endedAtElapsedMs = 1_200L))
    }
}
