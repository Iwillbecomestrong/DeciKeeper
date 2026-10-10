package com.decikeeper.app.service

import com.decikeeper.app.data.EventClock
import com.decikeeper.app.data.ForegroundBusinessAction
import com.decikeeper.app.data.ForegroundDiagnosticCycle
import com.decikeeper.app.data.ForegroundReportAssessmentFactory
import com.decikeeper.app.data.ForegroundReportDecision
import com.decikeeper.app.data.ForegroundReportEmission
import com.decikeeper.app.data.ForegroundReportOutcome
import com.decikeeper.app.data.ForegroundReportTrigger
import com.decikeeper.app.data.ForegroundSourceSnapshot
import com.decikeeper.app.data.ForegroundSourceStatus
import com.decikeeper.app.data.ForegroundVote
import com.decikeeper.app.data.NormalEventDraft
import com.decikeeper.app.util.ForegroundDecisionRetention
import com.decikeeper.app.util.ForegroundNormalEventBridge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ForegroundDecisionRetentionTest {

    private val benignEvents = source(
        "USAGE_EVENTS", ForegroundSourceStatus.UNAVAILABLE, unavailableReason = "NO_RECENT_FOREGROUND_EVENT"
    )
    private val benignStats = source(
        "USAGE_STATS", ForegroundSourceStatus.UNAVAILABLE, unavailableReason = "USAGE_STATS_MANAGER_NULL_OR_EMPTY"
    )
    private val healthyA11y = source("ACCESSIBILITY", ForegroundSourceStatus.AVAILABLE, packageName = "com.x")

    private fun source(
        name: String,
        status: ForegroundSourceStatus,
        packageName: String? = null,
        unavailableReason: String? = null,
        failureClass: String? = null
    ) = ForegroundSourceSnapshot(
        source = name, status = status, packageName = packageName,
        vote = ForegroundVote.ABSTAIN, capturedAtMs = 1_000L,
        queryStartedAtMs = 1_000L, queryFinishedAtMs = 1_001L,
        candidates = emptyList(), selectionReason = null,
        unavailableReason = unavailableReason, failureClass = failureClass
    )

    private fun cycle(
        events: ForegroundSourceSnapshot,
        stats: ForegroundSourceSnapshot,
        a11y: ForegroundSourceSnapshot
    ) = ForegroundDiagnosticCycle(reportId = "FG-T", checkedAtMs = 1_000L).also {
        it.updateUsageEvents(events)
        it.updateUsageStats(stats)
        it.updateAccessibility(a11y)
    }

    private val outcome = ForegroundReportOutcome(
        effectivePackageName = "com.x", effectivePackageBlacklisted = false,
        diagnosticRecommendation = "NONE", businessAction = ForegroundBusinessAction.NO_LOCK,
        businessReason = "NOT_BLACKLISTED", allowanceState = "UNKNOWN",
        guardPassed = null, visibilityPassed = null
    )

    private fun decision(cycle: ForegroundDiagnosticCycle, vararg triggers: ForegroundReportTrigger) =
        ForegroundReportDecision(
            emission = ForegroundReportEmission.FULL,
            triggers = triggers.toList(),
            assessment = ForegroundReportAssessmentFactory.assess(cycle, outcome),
            sourceProblemStartedAtMs = null
        )

    private val quietCycle get() = cycle(benignEvents, benignStats, healthyA11y)

    @Test
    fun quietWindowNoDataFaultIsNotRetained() {
        val cycle = quietCycle

        assertFalse(
            ForegroundDecisionRetention().shouldRetain(
                decision(cycle, ForegroundReportTrigger.SOURCE_FAULT_STARTED), cycle
            )
        )
        assertFalse(
            ForegroundDecisionRetention().shouldRetain(
                decision(cycle, ForegroundReportTrigger.SOURCE_FAULT_CHANGED), cycle
            )
        )
    }

    @Test
    fun accessibilityInactiveIsARealFaultAndRetained() {
        val cycle = cycle(
            benignEvents, benignStats,
            source("ACCESSIBILITY", ForegroundSourceStatus.UNAVAILABLE, unavailableReason = "A11Y_INACTIVE")
        )

        assertTrue(
            ForegroundDecisionRetention().shouldRetain(
                decision(cycle, ForegroundReportTrigger.SOURCE_FAULT_STARTED), cycle
            )
        )
    }

    @Test
    fun failedQueryIsARealFaultAndRetained() {
        val cycle = cycle(
            source("USAGE_EVENTS", ForegroundSourceStatus.FAILED, failureClass = "SecurityException"),
            benignStats, healthyA11y
        )

        assertTrue(
            ForegroundDecisionRetention().shouldRetain(
                decision(cycle, ForegroundReportTrigger.SOURCE_FAULT_STARTED), cycle
            )
        )
    }

    @Test
    fun lockRequestAndMissedLockAreAlwaysRetainedEvenWhenSourcesAreQuiet() {
        val cycle = quietCycle
        val retention = ForegroundDecisionRetention()

        listOf(
            ForegroundReportTrigger.REQUEST_LOCK,
            ForegroundReportTrigger.MISSED_LOCK_STARTED,
            ForegroundReportTrigger.MISSED_LOCK_CHANGED
        ).forEach { trigger ->
            assertTrue(trigger.name, retention.shouldRetain(decision(cycle, trigger), cycle))
        }
    }

    @Test
    fun sourceDisputeIsAlwaysRetained() {
        val cycle = quietCycle

        assertTrue(
            ForegroundDecisionRetention().shouldRetain(
                decision(cycle, ForegroundReportTrigger.SOURCE_DISPUTE_STARTED), cycle
            )
        )
    }

    @Test
    fun recoveryIsRetainedOnlyAfterARealFault() {
        val retention = ForegroundDecisionRetention()
        val benign = quietCycle
        val real = cycle(
            benignEvents, benignStats,
            source("ACCESSIBILITY", ForegroundSourceStatus.UNAVAILABLE, unavailableReason = "A11Y_INACTIVE")
        )
        val healthy = cycle(
            source("USAGE_EVENTS", ForegroundSourceStatus.AVAILABLE, "com.x"),
            source("USAGE_STATS", ForegroundSourceStatus.AVAILABLE, "com.x"),
            healthyA11y
        )

        retention.shouldRetain(decision(benign, ForegroundReportTrigger.SOURCE_FAULT_STARTED), benign)
        assertFalse(
            "正常安静窗口之后的恢复不保留",
            retention.shouldRetain(decision(healthy, ForegroundReportTrigger.SOURCE_RECOVERED), healthy)
        )

        retention.shouldRetain(decision(real, ForegroundReportTrigger.SOURCE_FAULT_STARTED), real)
        assertTrue(
            "真故障之后的恢复保留",
            retention.shouldRetain(decision(healthy, ForegroundReportTrigger.SOURCE_RECOVERED), healthy)
        )
        assertFalse(
            "恢复只保留一次",
            retention.shouldRetain(decision(healthy, ForegroundReportTrigger.SOURCE_RECOVERED), healthy)
        )
    }

    @Test
    fun explicitFullReportWithoutTriggersIsRetained() {
        val cycle = quietCycle

        assertTrue(ForegroundDecisionRetention().shouldRetain(decision(cycle), cycle))
    }

    @Test
    fun bridgeDropsFullReportWhenNotRetainedAndEmitsItOtherwise() {
        val clock = object : EventClock {
            override fun wallNowMs(): Long = 1_000L
            override fun elapsedNowMs(): Long = 1_000L
        }
        val captured = mutableListOf<NormalEventDraft>()
        val bridge = ForegroundNormalEventBridge(clock = clock, emit = { captured += it })
        val cycle = quietCycle
        val decision = decision(cycle, ForegroundReportTrigger.SOURCE_FAULT_STARTED)

        bridge.emitDecision(cycle, outcome, decision, "EVENTS", null, null, null, retainFull = false)
        assertEquals("不保留时不输出", 0, captured.size)

        bridge.emitDecision(cycle, outcome, decision, "EVENTS", null, null, null)
        assertEquals("默认保留", 1, captured.size)
        assertEquals("FG_DECISION", captured.single().type)
    }
}
