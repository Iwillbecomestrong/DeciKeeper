package com.decikeeper.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TransientOverlayGenerationContractTest {

    @Test
    fun `classifyTransientOverlayState - idle when no panel and no window`() {
        val decision = LockWindowManager.classifyTransientOverlayState(
            isInputPanelOpen = false,
            hasRestWindow = false,
            windowGeneration = -1,
            currentGeneration = 1,
            openDurationMs = 0L
        )
        assertEquals("IDLE", decision)
    }

    @Test
    fun `classifyTransientOverlayState - active when generation matches and within timeout`() {
        val decision = LockWindowManager.classifyTransientOverlayState(
            isInputPanelOpen = true,
            hasRestWindow = true,
            windowGeneration = 1,
            currentGeneration = 1,
            openDurationMs = 5000L
        )
        assertEquals("ACTIVE", decision)
    }

    @Test
    fun `classifyTransientOverlayState - stale when generation mismatch`() {
        val decision = LockWindowManager.classifyTransientOverlayState(
            isInputPanelOpen = true,
            hasRestWindow = true,
            windowGeneration = 1,
            currentGeneration = 2,
            openDurationMs = 5000L
        )
        assertEquals("STALE_GENERATION", decision)
    }

    @Test
    fun `classifyTransientOverlayState - expired when open duration exceeds timeout`() {
        val decision = LockWindowManager.classifyTransientOverlayState(
            isInputPanelOpen = true,
            hasRestWindow = true,
            windowGeneration = 1,
            currentGeneration = 1,
            openDurationMs = 65_000L,
            maxDurationMs = 60_000L
        )
        assertEquals("TIMEOUT_EXPIRED", decision)
    }

    @Test
    fun `shouldPauseForegroundChecks - auto heals when expired or stale generation`() {
        // 验证超时或代际不匹配时，纯判定能够识别并拒绝 pause
        val isStale = LockWindowManager.isTransientOverlayStale(
            isInputPanelOpen = true,
            windowGeneration = 1,
            currentGeneration = 2,
            openDurationMs = 1000L
        )
        assertTrue("Mismatched generation must be considered stale", isStale)

        val isExpired = LockWindowManager.isTransientOverlayStale(
            isInputPanelOpen = true,
            windowGeneration = 1,
            currentGeneration = 1,
            openDurationMs = 65_000L
        )
        assertTrue("Exceeding max duration must be considered stale", isExpired)

        val isFresh = LockWindowManager.isTransientOverlayStale(
            isInputPanelOpen = true,
            windowGeneration = 1,
            currentGeneration = 1,
            openDurationMs = 5_000L
        )
        assertFalse("Fresh panel with matched generation must not be stale", isFresh)
    }
}
