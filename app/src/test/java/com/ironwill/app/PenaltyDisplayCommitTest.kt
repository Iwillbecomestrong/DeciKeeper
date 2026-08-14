package com.ironwill.app

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Boundary 2 — RED fixture until real-display hit authority lands in `LockWindowManager`.
 *
 * Spec anchors:
 *   - FACT DA-01 — only a successful `targetWM.addView` commits the penalty hit;
 *     deferred / cancelled / reasserted / blocked attaches must NOT increment.
 *   - FACT DA-16-R2 — 5 hits in 30s collapse to exactly one penalty transaction +
 *     one receipt + one consumePenaltySeconds side-effect.
 *   - FACT C-INV-01 — `mPendingPenaltyMessage` is a receipt string only; overlay
 *     click / close paths cannot invoke consumePenaltySeconds.
 *   - INVAR-1 — at most one physical penalty transaction per (DND, window) cycle.
 *   - INVAR-6 — overlay replay is idempotent (renders N times ⇒ 0 extra transactions).
 *   - INVAR-10 — overlay clear must not retroactively charge quota.
 *
 * RED today (Boundary 1 + 1.5 only):
 *   - Production has early `mPenaltyHitCount++` at lines 3417-3495; it increments
 *     BEFORE attach, so deferred / cancelled attaches wrongly bump the hit count.
 *   - The 5/30s threshold can fire multiple transactions per cycle if a debounce
 *     miss + a re-assert path happen to interleave.
 *   - There is no test-facing surface for `recordSuccessfulPenaltyEligibleDisplay(...)`
 *     — this test cannot reference it until Boundary 2 lands.
 *
 * The mirror controller lives in `PenaltyDisplayCommitTestController.kt` and
 * defaults to pre-Boundary-2 semantics (`countOnRequestEntered = true`).
 * Each test asserts the POST-Boundary-2 contract, so they FAIL against the
 * pre-migration mirror today. Boundary 2 flips the mirror to post-migration
 * semantics (or replaces it wholesale via the production seam) so the tests
 * turn GREEN.
 */
class PenaltyDisplayCommitTest {

    /**
     * Deferred / cancelled / blocked attaches must NOT increment the penalty hit
     * count. Only a successful `targetWM.addView` boundary counts.
     *
     * Pre-Boundary-2: the production `showLockScreen` increments at the front
     * for every entry that survives the debounce / grace checks — including
     * the deferred path (line 3586-3607), the `CANCELLED_BEFORE_ATTACH` path
     * (line 3630-3638), and the safe-app bail. The mirror captures that by
     * bumping the hit count from `onRequestEnteredShowLockScreen`. So this
     * test is RED today: 4 simulated bail-before-attach requests wrongly drive
     * the counter (and a 5th real-display request issues an unexpected
     * transaction because the counter is already at 4 from the bail-outs).
     */
    @Test
    fun deferredOrCancelledAttachDoesNotIncrementPenaltyHit() {
        val ctrl = newPenaltyDisplayCommitControllerForTest()

        // Four showLockScreen entries that all bail BEFORE addView (deferred /
        // cancelled / blocked / safe-app bail). Today each of them bumps the
        // hit count. Post-Boundary-2: none of them bumps the hit count.
        repeat(4) { tick ->
            ctrl.onRequestEnteredShowLockScreen(
                nowMs = 1000L + tick * 100L,
                isDndActive = true,
                isScreenOnRedetection = false
            )
        }

        assertEquals(
            "bail-out entries must NOT contribute to the hit count (Boundary-2 contract)",
            0,
            ctrl.currentHitCount()
        )
        assertEquals(
            "no real-display hit yet → no penalty transactions must be issued",
            0,
            ctrl.penaltyTransactionCount()
        )
        assertEquals(
            "no real-display hit yet → consumePenaltySeconds must NOT have been called",
            0,
            ctrl.consumePenaltySecondsInvocationCount()
        )
        assertEquals(
            "no real-display hit yet → no receipt cached for overlay",
            null,
            ctrl.pendingReceipt()
        )
        assertEquals(
            "no real-display hit yet → overlay renders must be 0",
            0,
            ctrl.overlayRenderCount()
        )

        // Now a single real-display attaches. Post-Boundary-2: that one real
        // display contributes ONE hit; the threshold is 5, so no transaction.
        // Pre-Boundary-2: bail-outs already drove the counter to 4, but the
        // pre-migration onRealDisplayAttached is a no-op → no transaction.
        // Either way 0 transactions; the discriminator is [currentHitCount].
        ctrl.onRealDisplayAttached(
            nowMs = 1500L,
            isDndActive = true,
            isScreenOnRedetection = false,
            deductSeconds = 30,
            receiptBuilder = { txId, secs -> "RECEIPT[$txId|${secs}s]" }
        )
        assertEquals(
            "after 1 real display + 4 bail-outs: hitCount must be 1 (Boundary-2)",
            1,
            ctrl.currentHitCount()
        )
        assertEquals(
            "after 1 real display + 4 bail-outs: 0 transactions",
            0,
            ctrl.penaltyTransactionCount()
        )
        assertEquals(
            "after 1 real display + 4 bail-outs: 0 consume calls",
            0,
            ctrl.consumePenaltySecondsInvocationCount()
        )
    }

    /**
     * Five SUCCESSFUL addView boundaries within 30s, DND active, no redetection
     * flag — produce exactly ONE penalty transaction: one transactionId, one
     * consumePenaltySeconds side-effect, one receipt cached for the overlay.
     *
     * Pre-Boundary-2 the threshold block can fire on every hitCount >= 5 cycle
     * if a residue + reset window align. Post-Boundary-2: exactly one
     * transaction per cycle.
     */
    @Test
    fun fifthSuccessfulAttachCreatesExactlyOnePenaltyTransaction() {
        val ctrl = newPenaltyDisplayCommitControllerForTest()

        // 4 real displays inside the window — count climbs but no transaction yet.
        repeat(4) { tick ->
            ctrl.onRealDisplayAttached(
                nowMs = 1000L + tick * 500L,
                isDndActive = true,
                isScreenOnRedetection = false,
                deductSeconds = 30,
                receiptBuilder = { txId, secs -> "RECEIPT[$txId|${secs}s]" }
            )
        }
        assertEquals(
            "after 4 successful attaches: still no transaction",
            0,
            ctrl.penaltyTransactionCount()
        )
        assertEquals(
            "after 4 successful attaches: consumePenaltySeconds not yet invoked",
            0,
            ctrl.consumePenaltySecondsInvocationCount()
        )

        // 5th successful attach → threshold reached → exactly ONE transaction.
        ctrl.onRealDisplayAttached(
            nowMs = 1000L + 4 * 500L,
            isDndActive = true,
            isScreenOnRedetection = false,
            deductSeconds = 30,
            receiptBuilder = { txId, secs -> "RECEIPT[$txId|${secs}s]" }
        )

        assertEquals(
            "5th successful attach must commit exactly ONE transaction",
            1,
            ctrl.penaltyTransactionCount()
        )
        assertEquals(
            "5th successful attach must invoke consumePenaltySeconds exactly once",
            1,
            ctrl.consumePenaltySecondsInvocationCount()
        )
        assertEquals(
            "consumed seconds must equal the penalty deduct constant (30s)",
            30,
            ctrl.totalConsumedSeconds()
        )
        assertEquals(
            "transaction must carry the receipt-bearing transactionId",
            1,
            ctrl.penaltyTransactionIds().size
        )
        assertNotNull(
            "receipt must be cached for overlay rendering",
            ctrl.pendingReceipt()
        )
        assertTrue(
            "receipt must carry the transactionId issued by the commit",
            ctrl.pendingReceipt()!!.contains(ctrl.penaltyTransactionIds().single())
        )

        // 6th & 7th real displays inside the same window: still no extra
        // transaction because the cycle was reset on the 5th hit. We
        // deliberately simulate 2 more real displays to prove the controller
        // does NOT issue double-charge in the next 30s window opened by the
        // threshold reset.
        ctrl.onRealDisplayAttached(
            nowMs = 1000L + 5 * 500L,
            isDndActive = true,
            isScreenOnRedetection = false,
            deductSeconds = 30,
            receiptBuilder = { txId, secs -> "RECEIPT[$txId|${secs}s]" }
        )
        ctrl.onRealDisplayAttached(
            nowMs = 1000L + 6 * 500L,
            isDndActive = true,
            isScreenOnRedetection = false,
            deductSeconds = 30,
            receiptBuilder = { txId, secs -> "RECEIPT[$txId|${secs}s]" }
        )

        assertEquals(
            "post-threshold attaches in the new window must NOT issue additional transactions",
            1,
            ctrl.penaltyTransactionCount()
        )
        assertEquals(
            "consumePenaltySeconds must remain at exactly 1 invocation",
            1,
            ctrl.consumePenaltySecondsInvocationCount()
        )

        // Trace-friendly summary for CI grep.
        println(
            String.format(
                Locale.US,
                "penalty_commit_test transactions=%d consumed=%d txIds=%s receipt=%s",
                ctrl.penaltyTransactionCount(),
                ctrl.totalConsumedSeconds(),
                ctrl.penaltyTransactionIds().joinToString(","),
                ctrl.pendingReceipt()
            )
        )
    }

    /**
     * Overlay click / close paths must NOT invoke a penalty transaction. The
     * overlay only renders the cached receipt string — it cannot trigger
     * `consumePenaltySeconds`. INVAR-10 / DA-01.
     *
     * Pre-Boundary-2 the overlay path itself does NOT call consumePenaltySeconds,
     * but the production code is missing the guard that makes this contract
     * explicit. The mirror exposes `overlayReplayAttempt` to detect a buggy
     * caller; the test asserts that even after N overlay renders + N replay
     * attempts + an overlay clear, NO additional transaction is issued.
     */
    @Test
    fun overlayReplayDoesNotInvokePenaltyTransaction() {
        val ctrl = newPenaltyDisplayCommitControllerForTest()

        // First drive the controller to commit exactly ONE transaction via the
        // 5/30s threshold (real displays only, no request-entry hits).
        repeat(5) { tick ->
            ctrl.onRealDisplayAttached(
                nowMs = 2000L + tick * 400L,
                isDndActive = true,
                isScreenOnRedetection = false,
                deductSeconds = 30,
                receiptBuilder = { txId, secs -> "RECEIPT[$txId|${secs}s]" }
            )
        }
        assertEquals(
            "precondition: one transaction must have been committed",
            1,
            ctrl.penaltyTransactionCount()
        )
        val txIdBefore = ctrl.penaltyTransactionIds().single()
        val consumedBefore = ctrl.totalConsumedSeconds()

        // Render the cached receipt N times → overlay path is read-only.
        repeat(10) {
            val rendered = ctrl.renderOverlayReceipt()
            assertNotNull("receipt must render until cleared", rendered)
            assertTrue(
                "rendered receipt must carry the original transactionId",
                rendered!!.contains(txIdBefore)
            )
        }

        // Simulate an overlay "click close" that ALSO tries to bump the counter
        // (the worst-case regression vector). The mirror records the attempt but
        // the production equivalent must NOT let it reach consumePenaltySeconds.
        ctrl.overlayClear()
        repeat(3) { ctrl.overlayReplayAttempt() }

        assertEquals(
            "overlay click / close must NOT issue a new transaction",
            1,
            ctrl.penaltyTransactionCount()
        )
        assertEquals(
            "overlay click / close must NOT invoke consumePenaltySeconds",
            1,
            ctrl.consumePenaltySecondsInvocationCount()
        )
        assertEquals(
            "overlay click / close must NOT add to totalConsumedSeconds",
            consumedBefore,
            ctrl.totalConsumedSeconds()
        )
        assertEquals(
            "overlay render count must reflect all render calls (10)",
            10,
            ctrl.overlayRenderCount()
        )
        assertEquals(
            "overlay replay attempts must be observable (3) but produce 0 transactions",
            3,
            ctrl.overlayReplayAttemptCount()
        )
        assertNull(
            "after overlay clear, pendingReceipt must be null",
            ctrl.pendingReceipt()
        )
        // Trace-friendly summary.
        println(
            String.format(
                Locale.US,
                "overlay_replay_test transactions=%d consumed=%d renders=%d replayAttempts=%d",
                ctrl.penaltyTransactionCount(),
                ctrl.totalConsumedSeconds(),
                ctrl.overlayRenderCount(),
                ctrl.overlayReplayAttemptCount()
            )
        )
    }

    /**
     * Returns the Boundary-2 production seam. Today this is a pure-JVM mirror
     * in pre-Boundary-2 mode. After Boundary 2 lands, the factory must hand
     * back a surface backed by `LockWindowManager.recordSuccessfulPenaltyEligibleDisplay(...)`.
     */
    private fun newPenaltyDisplayCommitControllerForTest(
        penaltyThreshold: Int = 5,
        penaltyWindowMs: Long = 30_000L
    ): PenaltyDisplayCommitControllerForTest = PenaltyDisplayCommitTestController(
        penaltyThreshold = penaltyThreshold,
        penaltyWindowMs = penaltyWindowMs
    )
}
