package com.decikeeper.app.manager

import org.junit.Assert.*
import org.junit.Test

class ReturnHomeBufferPolicyTest {
    private fun activate(policy: ReturnHomeBufferPolicy, start: Long = 1_000L) {
        repeat(5) { policy.onReturnHome(start + it * 4_000L) }
    }

    @Test fun fifthClickCancelsExistingGraceWithoutQuotaInput() {
        val policy = ReturnHomeBufferPolicy()
        repeat(4) { policy.onReturnHome(1_000L + it * 1_000L) }
        assertFalse(policy.isStrict(4_000L))
        assertTrue(policy.hintText(4_000L)!!.contains("取消返回缓冲"))
        policy.beginGrace(4_000L)
        policy.onReturnHome(5_000L)
        assertTrue(policy.isStrict(5_000L))
        assertEquals(0L, policy.beginGrace(5_000L))
    }

    @Test fun expiredCountingWindowStartsOver() {
        val policy = ReturnHomeBufferPolicy()
        repeat(4) { policy.onReturnHome(1_000L + it * 1_000L) }
        policy.onReturnHome(35_000L)
        assertFalse(policy.isStrict(35_000L))
        assertTrue(policy.hintText(35_000L)!!.contains("1/5"))
    }

    @Test fun slidingWindowCatchesBurstStraddlingFirstClickAnchor() {
        val policy = ReturnHomeBufferPolicy()
        policy.onReturnHome(1_000L)
        listOf(30_000L, 31_000L, 31_500L, 32_000L).forEach { policy.onReturnHome(it) }
        assertFalse(policy.isStrict(32_000L))
        policy.onReturnHome(32_500L)
        assertTrue(policy.isStrict(32_500L))
    }

    @Test fun repeatedGraceRequestsShareDeadline() {
        val policy = ReturnHomeBufferPolicy()
        assertEquals(3_000L, policy.beginGrace(1_000L))
        assertEquals(2_000L, policy.beginGrace(2_000L))
        assertEquals(1L, policy.beginGrace(3_999L))
    }

    @Test fun strictSurvivesCountingWindowAndRecoversAfterQuietMinute() {
        val policy = ReturnHomeBufferPolicy()
        activate(policy)
        assertTrue(policy.isStrict(70_000L))
        assertFalse(policy.isStrict(77_000L))
        assertNull(policy.hintText(77_000L))
        policy.onReturnHome(77_000L)
        assertFalse(policy.isStrict(77_000L))
        assertTrue(policy.hintText(77_000L)!!.contains("1/5"))
    }

    @Test fun newRestrictionRestartsQuietMinuteButPollingDoesNot() {
        val policy = ReturnHomeBufferPolicy()
        activate(policy)
        policy.onRestriction(40_000L)
        assertTrue(policy.isStrict(99_999L))
        assertFalse(policy.isStrict(100_000L))
        policy.onRestriction(100_001L)
        assertFalse(policy.isStrict(100_001L))
    }

    @Test fun strictClicksNeverGrantGraceCountOrExtendQuietMinute() {
        val policy = ReturnHomeBufferPolicy()
        activate(policy)
        policy.onReturnHome(70_000L)
        assertEquals(0L, policy.beginGrace(70_000L))
        assertTrue(policy.isStrict(76_999L))
        assertFalse(policy.isStrict(77_000L))
        assertNull(policy.hintText(77_000L))
    }

    @Test fun restartRestoresRemainingStrictTimeAndCountingProgress() {
        val policy = ReturnHomeBufferPolicy()
        activate(policy)
        val restored = ReturnHomeBufferPolicy.restore(policy.snapshot(20_000L), 10_000L, 100_000L)
        assertTrue(restored.isStrict(146_999L))
        assertFalse(restored.isStrict(147_000L))
        val counting = ReturnHomeBufferPolicy()
        repeat(4) { counting.onReturnHome(1_000L + it * 1_000L) }
        val restarted = ReturnHomeBufferPolicy.restore(counting.snapshot(4_000L), 1_000L, 200_000L)
        restarted.onReturnHome(200_000L)
        assertTrue(restarted.isStrict(200_000L))
    }

    @Test fun staleOrBackwardClockRestoreCannotCreatePermanentStrictMode() {
        val policy = ReturnHomeBufferPolicy()
        activate(policy)
        val snapshot = policy.snapshot(20_000L)
        assertFalse(ReturnHomeBufferPolicy.restore(snapshot, 70_000L, 100_000L).isStrict(100_000L))
        val restored = ReturnHomeBufferPolicy.restore(snapshot, -10_000L, 100_000L)
        assertTrue(restored.isStrict(100_000L))
        assertFalse(restored.isStrict(160_000L))
    }
}
