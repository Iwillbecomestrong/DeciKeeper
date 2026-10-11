package com.decikeeper.app.logging

import com.decikeeper.app.data.EventClock
import com.decikeeper.app.util.ForegroundLockResultSettlementTracker
import com.decikeeper.app.util.ForegroundNormalEventStateTracker
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EvidenceAndStartLineTest {

    private val clock = object : EventClock {
        override fun wallNowMs(): Long = 1_000L
        override fun elapsedNowMs(): Long = 1_000L
    }

    @After
    fun tearDown() {
        LastForegroundEvidence.clearForTesting()
    }

    @Test
    fun evidenceIsAbsentBeforeFirstUpdate() {
        assertNull(LastForegroundEvidence.render())
    }

    @Test
    fun evidenceRendersPackageSourceAndReason() {
        LastForegroundEvidence.update("com.ss.android.ugc.aweme", "EVENTS", "EVENTS_BUSINESS_MATCH")

        assertEquals(
            "fg:com.ss.android.ugc.aweme src:EVENTS reason:EVENTS_BUSINESS_MATCH",
            LastForegroundEvidence.render()
        )
    }

    @Test
    fun evidenceNormalizesMissingPackageAndWhitespace() {
        LastForegroundEvidence.update(null, "CACHE", "no foreground resolved")

        assertEquals("fg:NONE src:CACHE reason:no_foreground_resolved", LastForegroundEvidence.render())
    }

    @Test
    fun lockResultCarriesEvidenceFieldWhenProvided() {
        val tracker = ForegroundLockResultSettlementTracker()

        val draft = tracker.settle(
            requestId = "LOCK-1",
            decisionRef = "FG-1",
            packageName = "com.x",
            result = "LOCKED",
            reason = "WINDOW_ATTACHED",
            duplicate = false,
            clock = clock,
            evidence = "fg:com.x src:EVENTS reason:EVENTS_BUSINESS_MATCH"
        )

        assertNotNull(draft)
        assertEquals("fg:com.x src:EVENTS reason:EVENTS_BUSINESS_MATCH", draft!!.fields["evidence"])
    }

    @Test
    fun lockResultOmitsEvidenceFieldWhenAbsent() {
        val draft = ForegroundLockResultSettlementTracker().settle(
            requestId = "LOCK-2",
            decisionRef = "FG-2",
            packageName = "com.x",
            result = "LOCKED",
            reason = "WINDOW_ATTACHED",
            duplicate = false,
            clock = clock
        )

        assertNotNull(draft)
        assertTrue(!draft!!.fields.containsKey("evidence"))
    }

    @Test
    fun foregroundEndCarriesActionReasonAndAllowanceOfTheClosedRun() {
        val tracker = ForegroundNormalEventStateTracker()
        val fields = linkedMapOf("action" to "ALLOW", "reason" to "NO_FOREGROUND_RESOLVED", "allowance" to "UNKNOWN")

        tracker.observe("NONE|ALLOW|X|UNKNOWN", "NONE", false, false, 1_000L, clock, fields)
        tracker.observe("NONE|ALLOW|X|UNKNOWN", "NONE", false, false, 2_000L, clock, fields)
        val closed = tracker.observe(
            "com.x|ALLOW|Y|OK", "com.x", false, false, 9_000L, clock,
            linkedMapOf("action" to "ALLOW", "reason" to "Y", "allowance" to "OK")
        )

        val end = closed.single { it.type == "FG_END" }
        assertEquals("NONE", end.fields["foreground"])
        assertEquals("8000", end.fields["dwellMs"])
        assertEquals("1", end.fields["suppressedCount"])
        assertEquals("ALLOW", end.fields["action"])
        assertEquals("NO_FOREGROUND_RESOLVED", end.fields["reason"])
        assertEquals("UNKNOWN", end.fields["allowance"])
    }

    @Test
    fun foregroundEndWithoutStateFieldsKeepsOriginalShape() {
        val tracker = ForegroundNormalEventStateTracker()

        tracker.observe("a", "com.a", false, false, 1_000L, clock)
        tracker.observe("a", "com.a", false, false, 2_000L, clock)
        val end = tracker.observe("b", "com.b", false, false, 3_000L, clock).single { it.type == "FG_END" }

        assertEquals(listOf("foreground", "dwellMs", "suppressedCount"), end.fields.keys.toList())
    }

    @Test
    fun processStartLineCarriesVersionDeviceAndAndroid() {
        val line = ProcessStartLine.format("0.3.0", "vivo", "V2425A", "16", 36)

        assertEquals("[PROCESS_START] version=0.3.0 device=vivo/V2425A android=16 sdk=36", line)
    }

    @Test
    fun processStartLineToleratesMissingVersionAndSpaces() {
        val line = ProcessStartLine.format(null, "Some Maker", "Model X", "16", 36)

        assertEquals("[PROCESS_START] version=unknown device=Some_Maker/Model_X android=16 sdk=36", line)
    }

    @Test
    fun processStartGoesToPermanentSystemTier() {
        val decision = LegacyLogClassifier.classify(ProcessStartLine.TAG, "[PROCESS_START] version=0.3.0")

        assertEquals(
            LegacyLogDecision.Keep(LogRoute(LogTier.A, LogModule.SYS)),
            decision
        )
    }
}
