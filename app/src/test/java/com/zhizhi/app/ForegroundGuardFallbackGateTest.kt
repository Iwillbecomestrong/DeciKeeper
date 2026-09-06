package com.zhizhi.app

import com.zhizhi.app.service.ForegroundGuardFallbackGate
import com.zhizhi.app.service.isSustainedForegroundFallbackEligible
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
                lastBackgroundEventTs = 900L,
                hasActiveForegroundSession = true
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
                lastBackgroundEventTs = 0L,
                hasActiveForegroundSession = true
            )
        )
    }

    @Test
    fun `fresh tracker with history evidence but no active session never forces fallback after 60s`() {
        val eligible = isSustainedForegroundFallbackEligible(
            evidenceSource = "HISTORY",
            selectedPackage = "com.tencent.mm",
            cachedPackage = "com.tencent.mm",
            lastForegroundEventTs = 1_000L,
            lastBackgroundEventTs = 0L,
            hasActiveForegroundSession = false
        )
        assertFalse("未建立 active session 时历史事件不得具有保底资格", eligible)

        val gate = ForegroundGuardFallbackGate(timeoutMs = 60_000L)
        assertFalse(gate.shouldForce("com.tencent.mm", nowMs = 0L, eligible = eligible, guardPassed = false))
        assertFalse(gate.shouldForce("com.tencent.mm", nowMs = 70_000L, eligible = eligible, guardPassed = false))
    }

    @Test
    fun `usage stats never qualifies for sustained fallback without active session`() {
        assertFalse(
            isSustainedForegroundFallbackEligible(
                evidenceSource = "USAGE_STATS",
                selectedPackage = "com.luna.music",
                cachedPackage = "com.luna.music",
                lastForegroundEventTs = 1_000L,
                lastBackgroundEventTs = 900L,
                hasActiveForegroundSession = false
            )
        )
    }

    @Test
    fun `usage stats qualifies for sustained fallback when active foreground session exists`() {
        assertTrue(
            isSustainedForegroundFallbackEligible(
                evidenceSource = "USAGE_STATS",
                selectedPackage = "com.tencent.mm",
                cachedPackage = "com.tencent.mm",
                lastForegroundEventTs = 1_000L,
                lastBackgroundEventTs = 0L,
                hasActiveForegroundSession = true
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
                lastBackgroundEventTs = 1_100L,
                hasActiveForegroundSession = true
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

    @Test
    fun `explicit reset cancels pending fallback timer across sessions`() {
        val gate = ForegroundGuardFallbackGate(timeoutMs = 60_000L)

        // App 在 0s 进入 pending
        assertFalse(gate.shouldForce("com.tencent.mm", nowMs = 0L, eligible = true, guardPassed = false))

        // 用户回桌面、息屏或锁屏，触发 explicit reset
        gate.reset()

        // 70s 后用户再次进入该应用，因之前已被 reset，本次必须重新开始计时，绝不能立即 force
        assertFalse("显式 reset 后再次进入必须重新累计 60s，绝不能继承之前的计时", gate.shouldForce("com.tencent.mm", nowMs = 70_000L, eligible = true, guardPassed = false))
        assertFalse(gate.shouldForce("com.tencent.mm", nowMs = 129_999L, eligible = true, guardPassed = false))
        assertTrue(gate.shouldForce("com.tencent.mm", nowMs = 130_000L, eligible = true, guardPassed = false))
    }
}
