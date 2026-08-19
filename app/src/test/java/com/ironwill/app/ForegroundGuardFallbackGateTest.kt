package com.ironwill.app

import com.ironwill.app.service.ForegroundGuardFallbackGate
import com.ironwill.app.service.isSustainedForegroundFallbackEligible
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ForegroundGuardFallbackGateTest {

    @Test
    fun `cache foreground continuity becomes eligible without a fresh entry event`() {
        assertTrue(
            isSustainedForegroundFallbackEligible(
                evidenceSource = "CACHE",
                selectedPackage = "com.tencent.mm",
                cachedPackage = "com.tencent.mm",
                lastForegroundEventTs = 1_000L,
                lastBackgroundEventTs = 900L
            )
        )
    }

    @Test
    fun `history foreground continuity is also eligible`() {
        assertTrue(
            isSustainedForegroundFallbackEligible(
                evidenceSource = "HISTORY",
                selectedPackage = "com.tencent.mm",
                cachedPackage = "com.tencent.mm",
                lastForegroundEventTs = 1_000L,
                lastBackgroundEventTs = 0L
            )
        )
    }

    @Test
    fun `usage stats never qualifies for sustained fallback`() {
        assertFalse(
            isSustainedForegroundFallbackEligible(
                evidenceSource = "USAGE_STATS",
                selectedPackage = "com.luna.music",
                cachedPackage = "com.luna.music",
                lastForegroundEventTs = 1_000L,
                lastBackgroundEventTs = 900L
            )
        )
    }

    @Test
    fun `a newer background event cancels sustained foreground eligibility`() {
        assertFalse(
            isSustainedForegroundFallbackEligible(
                evidenceSource = "CACHE",
                selectedPackage = "com.tencent.mm",
                cachedPackage = "com.tencent.mm",
                lastForegroundEventTs = 1_000L,
                lastBackgroundEventTs = 1_100L
            )
        )
    }

    @Test
    fun `fallback does not force before sixty seconds`() {
        val gate = ForegroundGuardFallbackGate(timeoutMs = 60_000L)

        assertFalse(gate.shouldForce("com.tencent.mm", nowMs = 0L, eligible = true, guardPassed = false))
        assertFalse(gate.shouldForce("com.tencent.mm", nowMs = 59_999L, eligible = true, guardPassed = false))
    }

    @Test
    fun `fallback forces at sixty seconds`() {
        val gate = ForegroundGuardFallbackGate(timeoutMs = 60_000L)

        assertFalse(gate.shouldForce("com.tencent.mm", nowMs = 10_000L, eligible = true, guardPassed = false))
        assertTrue(gate.shouldForce("com.tencent.mm", nowMs = 70_000L, eligible = true, guardPassed = false))
    }

    @Test
    fun `package change restarts the fallback timer`() {
        val gate = ForegroundGuardFallbackGate(timeoutMs = 60_000L)

        assertFalse(gate.shouldForce("com.tencent.mm", nowMs = 0L, eligible = true, guardPassed = false))
        assertFalse(gate.shouldForce("com.example.other", nowMs = 59_000L, eligible = true, guardPassed = false))
        assertFalse(gate.shouldForce("com.example.other", nowMs = 60_001L, eligible = true, guardPassed = false))
    }

    @Test
    fun `normal guard success resets pending fallback`() {
        val gate = ForegroundGuardFallbackGate(timeoutMs = 60_000L)

        assertFalse(gate.shouldForce("com.tencent.mm", nowMs = 0L, eligible = true, guardPassed = false))
        assertFalse(gate.shouldForce("com.tencent.mm", nowMs = 30_000L, eligible = true, guardPassed = true))
        assertFalse(gate.shouldForce("com.tencent.mm", nowMs = 70_000L, eligible = true, guardPassed = false))
    }
}
