package com.decikeeper.app.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ForegroundDiagnosticTrackerTest {

    @Test
    fun testUsageStatsDiagnosticSummaryEmpty() {
        val summary = ForegroundDiagnosticTracker.analyzeUsageStatsEntries(
            now = 100000L,
            rawEntries = emptyList(),
            recentActiveEntries = emptyList(),
            selectedPkg = null,
            carrierNoisePackages = setOf("com.vivo.upslide"),
            isLauncher = { false }
        )

        assertEquals(0, summary.rawCount)
        assertEquals(0, summary.recentActiveCount)
        assertEquals("NONE", summary.selectedPkg)
        assertFalse(summary.claudePresentInRaw)
        assertFalse(summary.claudeAccepted)
        assertEquals("QUERY_EMPTY", summary.claudeRejectReason)
    }

    @Test
    fun testUsageStatsDiagnosticSummaryClaudeStale() {
        val now = 200_000L
        val claudeEntry = ForegroundDiagnosticTracker.UsageStatEntry(
            packageName = "com.anthropic.claude",
            lastTimeUsed = now - 50_000L,
            lastTimeVisible = now - 50_000L,
            totalTimeInForeground = 120_000L
        )

        val summary = ForegroundDiagnosticTracker.analyzeUsageStatsEntries(
            now = now,
            rawEntries = listOf(claudeEntry),
            recentActiveEntries = emptyList(),
            selectedPkg = null,
            carrierNoisePackages = setOf("com.vivo.upslide"),
            isLauncher = { false }
        )

        assertEquals(1, summary.rawCount)
        assertEquals(0, summary.recentActiveCount)
        assertTrue(summary.claudePresentInRaw)
        assertFalse(summary.claudeAccepted)
        assertEquals("LAST_VISIBLE_STALE", summary.claudeRejectReason)
    }

    @Test
    fun testUsageStatsDiagnosticSummaryClaudeAccepted() {
        val now = 200_000L
        val claudeEntry = ForegroundDiagnosticTracker.UsageStatEntry(
            packageName = "com.anthropic.claude",
            lastTimeUsed = now - 5_000L,
            lastTimeVisible = now - 5_000L,
            totalTimeInForeground = 120_000L
        )

        val summary = ForegroundDiagnosticTracker.analyzeUsageStatsEntries(
            now = now,
            rawEntries = listOf(claudeEntry),
            recentActiveEntries = listOf(claudeEntry),
            selectedPkg = "com.anthropic.claude",
            carrierNoisePackages = setOf("com.vivo.upslide"),
            isLauncher = { false }
        )

        assertEquals(1, summary.rawCount)
        assertEquals(1, summary.recentActiveCount)
        assertEquals("com.anthropic.claude", summary.selectedPkg)
        assertTrue(summary.claudePresentInRaw)
        assertTrue(summary.claudeAccepted)
        assertEquals("NONE", summary.claudeRejectReason)
    }

    @Test
    fun testUsageStatsDiagnosticSummaryCarrierNoiseReject() {
        val now = 200_000L
        val carrierEntry = ForegroundDiagnosticTracker.UsageStatEntry(
            packageName = "com.vivo.upslide",
            lastTimeUsed = now - 2_000L,
            lastTimeVisible = now - 2_000L,
            totalTimeInForeground = 50_000L
        )

        val summary = ForegroundDiagnosticTracker.analyzeUsageStatsEntries(
            now = now,
            rawEntries = listOf(carrierEntry),
            recentActiveEntries = listOf(carrierEntry),
            selectedPkg = null,
            carrierNoisePackages = setOf("com.vivo.upslide"),
            isLauncher = { false }
        )

        assertEquals(1, summary.rawCount)
        assertEquals(1, summary.recentActiveCount)
        assertEquals("NONE", summary.selectedPkg)
        assertFalse(summary.claudePresentInRaw)
    }
}
