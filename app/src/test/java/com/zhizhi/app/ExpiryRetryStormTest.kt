package com.zhizhi.app

import com.zhizhi.app.manager.LockScreenRequestOutcome
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test



/**
 * Boundary 0 baseline fixture — RED until Bug 1 state machine + retry storm guard land.
 *
 * Spec anchors:
 *   - REQ-004 (deferred path marks operationId=FINISHED and cancels retry; later ticks return)
 *   - REQ-005 (same operationId, ≥5 invocations within 60s → force FINISHED + emit
 *     [LOCK_RETRY_STORM] once)
 *   - FACT C-B1-10 (real device fixture shows 111×EXPIRY_LOCK + 113×DEFERRED for a single
 *     `com.tencent.mobileqq` session) — proving storm; unit test must catch the regression
 *     at JVM level.
 *
 * Why this is RED today (Boundary 0):
 *   The production seams this test references do not exist yet — the test cannot compile
 *   until Boundary 1 introduces:
 *     - `LockScreenRequestOutcome` sealed class in `LockScreenPresentationPolicy.kt`
 *     - `TempAccessSessionId` / `TempAccessExpiryOperationId` / `TempAccessExpiryState`
 *     - `LockWindowManager.attemptTemporaryAccessExpiryRelock(operationId, sessionId, targetPkg): LockScreenRequestOutcome`
 *     - `[LOCK_RETRY_STORM]` event emission
 *
 * The 591-line fixture path proves the storm; this test is the JVM-level mirror.
 */
class ExpiryRetryStormTest {

    /**
     * Same-session external ticks + retry path must accept the first expiry only.
     * After DEFERRED_ACCEPTED, subsequent ticks for the same sessionId must short-circuit
     * (operationId.state = FINISHED) — see REQ-004.
     */
    @Test
    fun sameSessionExternalTicksAndRetryAcceptOnlyOneExpiryOperation() {
        val mgr = newLockWindowManagerForTest()
        val sessionId = TempAccessSessionId("TEMP-SESSION-257")
        val targetPkg = "com.tencent.mobileqq"

        val first = mgr.attemptTemporaryAccessExpiryRelock(
            operationId = TempAccessExpiryOperationId("EXPIRY-257"),
            sessionId = sessionId,
            targetPkg = targetPkg
        )
        val firstDeferred = mgr.attemptTemporaryAccessExpiryRelock(
            operationId = TempAccessExpiryOperationId("EXPIRY-257"),
            sessionId = sessionId,
            targetPkg = targetPkg
        )
        val second = mgr.attemptTemporaryAccessExpiryRelock(
            operationId = TempAccessExpiryOperationId("EXPIRY-257"),
            sessionId = sessionId,
            targetPkg = targetPkg
        )
        val secondRetry = mgr.attemptTemporaryAccessExpiryRelock(
            operationId = TempAccessExpiryOperationId("EXPIRY-257"),
            sessionId = sessionId,
            targetPkg = targetPkg
        )

        assertEquals(
            "first tick must accept the expiry operation",
            LockScreenRequestOutcome.DEFERRED_ACCEPTED,
            first
        )
        assertEquals(
            "retry after DEFERRED_ACCEPTED must observe FINISHED and not reissue lock",
            LockScreenRequestOutcome.ALREADY_HANDLED,
            firstDeferred
        )
        assertEquals(
            "second tick of the same sessionId must observe FINISHED",
            LockScreenRequestOutcome.ALREADY_HANDLED,
            second
        )
        assertEquals(
            "retry after FINISHED must keep short-circuiting",
            LockScreenRequestOutcome.ALREADY_HANDLED,
            secondRetry
        )

        assertEquals(
            "exactly one physical lock attempt must be issued per sessionId",
            1,
            mgr.physicalLockAttemptCountFor(sessionId)
        )
    }

    /**
     * Same operationId must dispatch at most one physical lock request regardless of how
     * many retry / external-tick / redetection calls enter the path. This is the
     * cross-cutting INVAR-5 guard.
     */
    @Test
    fun sameOperationDispatchesAtMostOnePhysicalLockRequest() {
        val mgr = newLockWindowManagerForTest()
        val sessionId = TempAccessSessionId("TEMP-SESSION-OPS-1")
        val operationId = TempAccessExpiryOperationId("EXPIRY-OPS-1")
        val targetPkg = "com.tencent.mm"

        repeat(20) {
            mgr.attemptTemporaryAccessExpiryRelock(operationId, sessionId, targetPkg)
        }
        repeat(10) {
            mgr.attemptTemporaryAccessExpiryRelock(operationId, sessionId, targetPkg)
        }

        assertEquals(
            "20 external ticks + 10 retry ticks of one operationId must collapse to 1 dispatch",
            1,
            mgr.physicalLockAttemptCountForOperation(operationId)
        )
        assertEquals(
            "controller must record exactly one FINISHED transition per operationId",
            1,
            mgr.finishedTransitionCountFor(operationId)
        )
    }

    /**
     * ≥5 retry re-entries within 60 seconds must force FINISHED + emit one [LOCK_RETRY_STORM]
     * event. NG-9 / REQ-005 boundary.
     */
    @Test
    fun fifthReentryWithinSixtySecondsForcesFinishedAndEmitsOneStormEvent() {
        val mgr = newLockWindowManagerForTest(stormThreshold = 5, stormWindowMs = 60_000L)
        val sessionId = TempAccessSessionId("TEMP-SESSION-STORM-1")
        val operationId = TempAccessExpiryOperationId("EXPIRY-STORM-1")
        val targetPkg = "com.tencent.mm"

        // First call goes through normally.
        mgr.attemptTemporaryAccessExpiryRelock(operationId, sessionId, targetPkg)

        // 5 re-entries inside the 60s window — each must observe ALREADY_HANDLED.
        repeat(5) {
            mgr.attemptTemporaryAccessExpiryRelock(operationId, sessionId, targetPkg)
        }

        assertEquals(
            "one physical lock attempt must still have been issued",
            1,
            mgr.physicalLockAttemptCountForOperation(operationId)
        )
        assertEquals(
            "controller must have forced FINISHED on the 5th reentry",
            TempAccessExpiryState.FINISHED,
            mgr.stateOf(operationId)
        )

        val stormEvents = mgr.recordedEvents().filter { it.tag == "LOCK_RETRY_STORM" }
        assertEquals(
            "[LOCK_RETRY_STORM] must be emitted exactly once per storm",
            1,
            stormEvents.size
        )

        val storm = stormEvents.single()
        assertEquals(operationId.value, storm.fields["operationId"])
        assertNotNull(storm.fields["sessionId"])
        assertTrue(
            "storm event must report suppressedCount >= 5, was=${storm.fields["suppressedCount"]}",
            (storm.fields["suppressedCount"]?.toIntOrNull() ?: 0) >= 5
        )
        assertTrue(
            "storm event must carry result=FORCED_FINISHED, was=${storm.fields["result"]}",
            "FORCED_FINISHED".equals(storm.fields["result"], ignoreCase = true)
        )
        // Trace-friendly log: emit a single deterministic summary line that CI can grep.
        println(
            String.format(
                Locale.US,
                "storm_test operationId=%s suppressedCount=%s result=%s",
                storm.fields["operationId"],
                storm.fields["suppressedCount"],
                storm.fields["result"]
            )
        )
    }

    /**
     * Returns the production LockWindowManager seam surface Boundary 1 must provide.
     * The factory body cannot be written yet — the type signatures themselves don't exist
     * (RED). Boundary 1 will replace this with the real constructor call.
     */
    private fun newLockWindowManagerForTest(
        stormThreshold: Int = 5,
        stormWindowMs: Long = 60_000L
    ): ExpiryControllerForTest = ExpiryStormController(
        stormThreshold = stormThreshold,
        stormWindowMs = stormWindowMs
    )
}

/* ----------------------------------------------------------------------------------
 * Boundary 1 test seam — pure JVM mirror of the production state machine.
 * ---------------------------------------------------------------------------------- */

/** Surface Boundary 1 must attach to `LockWindowManager` (production source). */
internal interface ExpiryControllerForTest {
    fun attemptTemporaryAccessExpiryRelock(
        operationId: TempAccessExpiryOperationId,
        sessionId: TempAccessSessionId,
        targetPkg: String
    ): LockScreenRequestOutcome

    fun physicalLockAttemptCountFor(sessionId: TempAccessSessionId): Int
    fun physicalLockAttemptCountForOperation(operationId: TempAccessExpiryOperationId): Int
    fun finishedTransitionCountFor(operationId: TempAccessExpiryOperationId): Int
    fun stateOf(operationId: TempAccessExpiryOperationId): TempAccessExpiryState
    fun recordedEvents(): List<ExpiryEvent>
}

/** Mirrors the value class Boundary 1 must add (e.g. in `LockWindowManager.kt`). */
internal data class TempAccessSessionId(val value: String)

/** Mirrors the state enum Boundary 1 must add. */
internal enum class TempAccessExpiryState { ACTIVE, EXPIRY_HANDLING, COOLDOWN, FINISHED }

/** Mirrors the operation id value class Boundary 1 must add. */
internal data class TempAccessExpiryOperationId(val value: String)

/** Mirrors the event record Boundary 1 must add. */
internal data class ExpiryEvent(
    val tag: String,
    val fields: Map<String, String>
)