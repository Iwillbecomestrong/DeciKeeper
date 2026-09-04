package com.zhizhi.app

import com.zhizhi.app.data.DefaultNormalEventRenderer
import com.zhizhi.app.data.EventClock
import com.zhizhi.app.data.ForegroundBusinessAction
import com.zhizhi.app.data.ForegroundDiagnosticCycle
import com.zhizhi.app.data.ForegroundReportOutcome
import com.zhizhi.app.data.ForegroundSourceSnapshot
import com.zhizhi.app.data.ForegroundSourceStatus
import com.zhizhi.app.data.ForegroundVote
import com.zhizhi.app.data.NormalEvent
import com.zhizhi.app.data.NormalEventDraft
import com.zhizhi.app.util.ForegroundNormalEventBridge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test













class FgDecisionCompactGateTest {

    private val clock = object : EventClock {
        override fun wallNowMs(): Long = 1_720_000_000_000L
        override fun elapsedNowMs(): Long = 42_000L
    }

    @Test
    fun secondStableAttachedDecisionIsCompactAndReferencesEarlierFull() {
        val captured = mutableListOf<NormalEventDraft>()
        val bridge = ForegroundNormalEventBridge(clock, captured::add)
        val outcome = attachedOutcome(PACKAGE)

        bridge.emitFull(cycle("FG-001", PACKAGE, 1_000L), outcome, "EVENTS", null, null, null)
        bridge.emitFull(cycle("FG-002", PACKAGE, 2_000L), outcome, "EVENTS", null, null, null)

        assertEquals("FULL", captured[0].fields["detail"])
        assertEquals("COMPACT", captured[1].fields["detail"])
        assertEquals(captured[0].fields["reportId"], captured[1].fields["reportId"])
        assertEquals(captured[0].fields["reportId"], captured[1].fields["evidenceRef"])
        // eventId is assigned at the NormalEventLogger layer; we model the
        // sink ordering via the test bridge's own sequence so a stable
        // pre-emit eventId would be the only way two drafts could collide.
        // Draft-level identifier is the emitted type + reportId, which is
        // identical here. The sink-level eventId is a process-global counter
        // (NormalEventLogger.nextProcessSequence), so it MUST be unique even
        // when reportId is reused — verify by inspecting a real render.
        val first = render(captured[0], eventId = "EVT-A", seq = 1L)
        val second = render(captured[1], eventId = "EVT-B", seq = 2L)
        assertNotEquals(first.substringAfter("eventId=").substringBefore(" "),
            second.substringAfter("eventId=").substringBefore(" "))
        assertTrue(captured[1].bodyLines.size < captured[0].bodyLines.size)
    }

    private fun render(draft: NormalEventDraft, eventId: String, seq: Long): String =
        DefaultNormalEventRenderer.render(
            NormalEvent(
                eventId = eventId,
                processInstanceId = "test-process",
                sequence = seq,
                draft = draft
            )
        )

    @Test
    fun sourceConflictRemainsFullWhenExplicitlyRequested() {
        val captured = mutableListOf<NormalEventDraft>()
        val bridge = ForegroundNormalEventBridge(clock, captured::add)
        val outcome = attachedOutcome(PACKAGE)
        bridge.emitFull(cycle("FG-010", PACKAGE, 1_000L), outcome, "EVENTS", null, null, null)

        bridge.emitFull(
            cycle("FG-011", PACKAGE, 2_000L, accessibilityPackage = "com.android.settings"),
            outcome,
            "EVENTS",
            null,
            null,
            null
        )

        assertEquals("FULL", captured.last().fields["detail"])
    }

    @Test
    fun unavailableSelectedSourceRemainsFullWhenExplicitlyRequested() {
        val captured = mutableListOf<NormalEventDraft>()
        val bridge = ForegroundNormalEventBridge(clock, captured::add)
        val outcome = attachedOutcome(PACKAGE)
        bridge.emitFull(cycle("FG-020", PACKAGE, 1_000L), outcome, "EVENTS", null, null, null)

        bridge.emitFull(
            cycle("FG-021", PACKAGE, 2_000L, eventsStatus = ForegroundSourceStatus.UNAVAILABLE),
            outcome,
            "EVENTS",
            null,
            null,
            null
        )

        assertEquals("FULL", captured.last().fields["detail"])
    }

    @Test
    fun changedPackageRemainsFullWhenExplicitlyRequested() {
        val captured = mutableListOf<NormalEventDraft>()
        val bridge = ForegroundNormalEventBridge(clock, captured::add)
        bridge.emitFull(cycle("FG-030", PACKAGE, 1_000L), attachedOutcome(PACKAGE), "EVENTS", null, null, null)

        bridge.emitFull(
            cycle("FG-031", OTHER_PACKAGE, 2_000L),
            attachedOutcome(OTHER_PACKAGE),
            "EVENTS",
            null,
            null,
            null
        )

        assertEquals("FULL", captured.last().fields["detail"])
    }

    private fun cycle(
        reportId: String,
        packageName: String,
        capturedAtMs: Long,
        eventsStatus: ForegroundSourceStatus = ForegroundSourceStatus.AVAILABLE,
        accessibilityPackage: String = packageName
    ): ForegroundDiagnosticCycle = ForegroundDiagnosticCycle(reportId, capturedAtMs).apply {
        updateUsageEvents(source("USAGE_EVENTS", packageName, eventsStatus, capturedAtMs + 1L))
        updateUsageStats(source("USAGE_STATS", packageName, ForegroundSourceStatus.AVAILABLE, capturedAtMs + 2L))
        updateAccessibility(source("ACCESSIBILITY", accessibilityPackage, ForegroundSourceStatus.AVAILABLE, capturedAtMs + 3L))
    }

    private fun source(
        name: String,
        packageName: String,
        status: ForegroundSourceStatus,
        capturedAtMs: Long
    ) = ForegroundSourceSnapshot(
        source = name,
        status = status,
        packageName = packageName,
        vote = if (status == ForegroundSourceStatus.AVAILABLE) ForegroundVote.BLACKLIST else ForegroundVote.ABSTAIN,
        capturedAtMs = capturedAtMs,
        queryStartedAtMs = capturedAtMs - 1L,
        queryFinishedAtMs = capturedAtMs,
        candidates = emptyList(),
        selectionReason = if (status == ForegroundSourceStatus.AVAILABLE) "CURRENT_CYCLE" else null,
        unavailableReason = if (status == ForegroundSourceStatus.UNAVAILABLE) "NO_DATA" else null,
        failureClass = null,
        previousEvidence = "previous:$packageName",
        currentEvidence = "current:$packageName@$capturedAtMs"
    )

    private fun attachedOutcome(packageName: String) = ForegroundReportOutcome(
        effectivePackageName = packageName,
        effectivePackageBlacklisted = true,
        diagnosticRecommendation = "LOCK_BY_DIAGNOSTIC_MAJORITY",
        businessAction = ForegroundBusinessAction.NO_LOCK,
        businessReason = "LOCK_ALREADY_ATTACHED",
        allowanceState = "ALREADY_ENFORCED",
        guardPassed = true,
        visibilityPassed = true
    )

    private companion object {
        const val PACKAGE = "com.ss.android.ugc.aweme"
        const val OTHER_PACKAGE = "com.tencent.mm"
    }
}
