package com.zhizhi.app

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Contract for the repeated-return-home penalty.
 *
 * Product semantics:
 * - The counter represents explicit taps on the lock screen's "回到桌面" button.
 * - Merely attaching/re-attaching the lock overlay must never increment the counter.
 * - Choosing a legal exit (rest / temporary access / weekly privilege) does not count as
 *   a return-home attempt because no return-home button tap occurred for that round.
 * - Five eligible return-home taps in the 30s window create exactly one penalty transaction.
 *
 * This test deliberately maps the old controller seams to the new contract for the RED step:
 * onRequestEnteredShowLockScreen is used as the only existing user-action-like hook, while
 * onRealDisplayAttached represents the old production authority that must stop counting.
 */
class PenaltyDisplayCommitTest {

    private fun PenaltyDisplayCommitControllerForTest.returnHomeClick(
        nowMs: Long,
        isDndActive: Boolean = true
    ) {
        onRequestEnteredShowLockScreen(
            nowMs = nowMs,
            isDndActive = isDndActive,
            isScreenOnRedetection = false
        )
    }

    @Test
    fun successfulLockAttachWithoutReturnHomeClickDoesNotIncrement() {
        val ctrl = newPenaltyDisplayCommitControllerForTest()

        repeat(7) { tick ->
            ctrl.onRealDisplayAttached(
                nowMs = 1000L + tick * 300L,
                isDndActive = true,
                isScreenOnRedetection = false,
                deductSeconds = 30,
                receiptBuilder = { txId, secs -> "RECEIPT[$txId|${secs}s]" }
            )
        }

        assertEquals("overlay attaches are system actions, not user return-home attempts", 0, ctrl.currentHitCount())
        assertEquals("overlay attaches alone must never create a penalty transaction", 0, ctrl.penaltyTransactionCount())
        assertEquals(0, ctrl.consumePenaltySecondsInvocationCount())
    }

    @Test
    fun returnHomeClickIsTheOnlyHitAuthority() {
        val ctrl = newPenaltyDisplayCommitControllerForTest()

        ctrl.returnHomeClick(nowMs = 1000L)

        assertEquals("one explicit return-home click must count exactly once", 1, ctrl.currentHitCount())
        assertEquals(0, ctrl.penaltyTransactionCount())
    }

    @Test
    fun legalExitRoundDoesNotCreateAHit() {
        val ctrl = newPenaltyDisplayCommitControllerForTest()

        // A lock may be displayed, then the user chooses rest/temp/privilege instead of Home.
        // No returnHomeClick() call is made for that round.
        ctrl.onRealDisplayAttached(
            nowMs = 1000L,
            isDndActive = true,
            isScreenOnRedetection = false,
            deductSeconds = 30,
            receiptBuilder = { txId, secs -> "RECEIPT[$txId|${secs}s]" }
        )

        assertEquals(0, ctrl.currentHitCount())
        assertEquals(0, ctrl.penaltyTransactionCount())
    }

    @Test
    fun fifthReturnHomeClickCreatesExactlyOnePenaltyTransaction() {
        val ctrl = newPenaltyDisplayCommitControllerForTest()

        repeat(5) { tick ->
            ctrl.returnHomeClick(nowMs = 1000L + tick * 500L)
        }

        assertEquals("5 return-home clicks in 30s must commit one penalty", 1, ctrl.penaltyTransactionCount())
        assertEquals(1, ctrl.consumePenaltySecondsInvocationCount())
        assertEquals(30, ctrl.totalConsumedSeconds())
        assertEquals("counter resets after threshold transaction", 0, ctrl.currentHitCount())
    }

    @Test
    fun nonDndReturnHomeClickDoesNotCount() {
        val ctrl = newPenaltyDisplayCommitControllerForTest()

        ctrl.returnHomeClick(nowMs = 1000L, isDndActive = false)

        assertEquals(0, ctrl.currentHitCount())
        assertEquals(0, ctrl.penaltyTransactionCount())
    }
}
