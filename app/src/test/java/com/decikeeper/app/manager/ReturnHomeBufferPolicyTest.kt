package com.decikeeper.app.manager

import org.junit.Assert.*
import org.junit.Test

class ReturnHomeBufferPolicyTest {
    @Test fun firstReturnGetsThreeSecondGraceWithSharedDeadline() {
        val policy = ReturnHomeBufferPolicy()
        assertEquals(3_000L, policy.beginGrace(1_000L))
        assertEquals(1_000L, policy.beginGrace(3_000L))
        assertEquals(1L, policy.beginGrace(3_999L))
        assertEquals(0L, policy.graceRemainingMs(4_000L))
    }

    @Test fun noNewGraceWithinThirtySecondsOfTheLastOne() {
        val policy = ReturnHomeBufferPolicy()
        policy.beginGrace(1_000L)
        assertEquals(0L, policy.beginGrace(10_000L))
        assertEquals(0L, policy.beginGrace(30_999L))
        assertEquals(3_000L, policy.beginGrace(31_000L))
    }

    @Test fun cooldownIsNeverExtendedByRepeatedRequests() {
        val policy = ReturnHomeBufferPolicy()
        policy.beginGrace(1_000L)
        repeat(10) { policy.beginGrace(7_000L + it * 2_000L) }
        assertEquals(3_000L, policy.beginGrace(31_000L))
    }

    @Test fun hintShowsOnlyDuringCooldownAfterGraceEnds() {
        val policy = ReturnHomeBufferPolicy()
        assertNull(policy.hintText(500L))
        policy.beginGrace(1_000L)
        assertNull("放行中不提示", policy.hintText(3_000L))
        assertTrue(policy.hintText(6_000L)!!.contains("25 秒后重新豁免"))
        assertNull(policy.hintText(31_000L))
    }

    @Test fun restartKeepsRemainingGraceAndCooldown() {
        val policy = ReturnHomeBufferPolicy()
        policy.beginGrace(1_000L)
        val restored = ReturnHomeBufferPolicy.restore(policy.snapshot(2_000L), 500L, 100_000L)
        assertEquals(1_500L, restored.graceRemainingMs(100_000L))
        assertEquals(0L, restored.beginGrace(110_000L))
        assertEquals(3_000L, restored.beginGrace(128_500L))
    }

    @Test fun staleOrBackwardClockRestoreCannotCreatePermanentCooldown() {
        val policy = ReturnHomeBufferPolicy()
        policy.beginGrace(1_000L)
        val snapshot = policy.snapshot(6_000L)
        assertEquals(3_000L, ReturnHomeBufferPolicy.restore(snapshot, 60_000L, 100_000L).beginGrace(100_000L))
        val backward = ReturnHomeBufferPolicy.restore(snapshot, -10_000L, 100_000L)
        assertEquals(0L, backward.beginGrace(100_000L))
        assertEquals(3_000L, backward.beginGrace(130_000L))
    }
}
