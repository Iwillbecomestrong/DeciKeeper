package com.decikeeper.app

import com.decikeeper.app.data.AccessibilityForegroundSnapshot
import com.decikeeper.app.data.DefaultNormalEventRenderer
import com.decikeeper.app.data.EventClock
import com.decikeeper.app.data.ForegroundBusinessAction
import com.decikeeper.app.data.ForegroundCandidate
import com.decikeeper.app.data.ForegroundDiagnosticCycle
import com.decikeeper.app.data.ForegroundReportOutcome
import com.decikeeper.app.data.ForegroundSourceSnapshot
import com.decikeeper.app.data.ForegroundSourceStatus
import com.decikeeper.app.data.ForegroundVote
import com.decikeeper.app.data.NormalEvent
import com.decikeeper.app.data.NormalEventDraft
import com.decikeeper.app.util.ForegroundAtomicReport
import com.decikeeper.app.util.ForegroundFreshConfirmation
import com.decikeeper.app.util.ForegroundLockEvidence
import com.decikeeper.app.util.ForegroundLockResultSettlementTracker
import com.decikeeper.app.util.ForegroundNormalEventBridge
import com.decikeeper.app.util.ForegroundNormalEventStateTracker
import com.decikeeper.app.util.ForegroundReportDetail
import com.decikeeper.app.util.ForegroundReportFormatter
import com.decikeeper.app.util.ForegroundResultEvidence
import com.decikeeper.app.util.ForegroundSourceEvidence
import com.decikeeper.app.util.ForegroundStateEvidence
import com.decikeeper.app.util.ForegroundTransition
import com.decikeeper.app.util.ForegroundUsageStatsSnapshotTracker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test



























/**
 * Contract tests for the normal, atomic foreground-event renderer.
 *
 * These tests deliberately use a pure presentation input: the production bridge may map the
 * existing diagnostic cycle into it, but the renderer must not re-run foreground selection or
 * make any lock/allowance decision of its own.
 */
class ForegroundNormalEventTest {

    private val clock = FixedEventClock(wallMs = 1_720_000_000_000L, elapsedMs = 42_000L)

    @Test
    fun fullDecisionIsOneAtomicEventWithResultStateAllSourceEvidenceInferenceAndLockResult() {
        val draft = ForegroundReportFormatter.formatAtomic(fullReport(), clock)
        val text = render(draft)

        assertEquals("FG_DECISION", draft.type)
        assertTrue(text.contains("detail=FULL"))
        assertTrue(text.contains("reportId=FG-001"))
        assertTrue(text.contains("transition=桌面→抖音"))
        assertTrue(text.contains("[1 结果] foreground=抖音 packageName=com.ss.android.ugc.aweme action=LOCK reason=BLACKLIST_SELECTED"))
        assertTrue(text.contains("[2 状态] allowance=NONE appLock=LOCKED foregroundGuard=LOCKED"))
        assertTrue(text.contains("[3 UsageEvents] status=AVAILABLE prev={08:29:57 PAUSED 桌面} curr={08:29:59 RESUMED 抖音} selected=curr"))
        assertTrue(text.contains("[4 UsageStats] status=AVAILABLE prev={桌面 lastTimeVisible=08:29:57.900} curr={抖音 lastTimeVisible=08:29:59.800} selected=curr"))
        assertTrue(text.contains("[5 Accessibility] status=AVAILABLE prev={08:29:57 WINDOW 桌面 windows=一致} curr={08:29:59 WINDOW 抖音 windows=一致} selected=curr"))
        assertTrue(text.contains("[6 推理] 三源指向抖音→黑名单→无放行→锁屏"))
        assertTrue(text.contains("[7 锁屏] requestId=LOCK-001 result=LOCKED duplicate=false countedForPenalty=false"))
        // One draft means the export/logcat bridge receives one indivisible multiline record.
        assertEquals(7, draft.bodyLines.size)
    }

    @Test
    fun compactDecisionRequiresEarlierFullReferenceAndFreshConfirmationFromCurrentCycle() {
        val base = fullReport().copy(
            reportId = "FG-003",
            detail = ForegroundReportDetail.COMPACT,
            transition = null,
            result = ForegroundResultEvidence(
                foreground = "抖音",
                packageName = "com.ss.android.ugc.aweme",
                action = "LOCK",
                reason = "TEMP_ACCESS_EXPIRED"
            ),
            state = ForegroundStateEvidence(
                allowance = "TEMP_ACCESS→NONE",
                appLock = "LOCKED",
                foregroundGuard = "LOCKED"
            ),
            inference = "临时解锁到期→抖音仍可见→恢复锁屏",
            lock = ForegroundLockEvidence("LOCK-003", "LOCKED", duplicate = false, countedForPenalty = false),
            sources = emptyList()
        )

        assertIllegalArgument("missing evidenceRef must reject COMPACT") {
            ForegroundReportFormatter.formatAtomic(base, clock)
        }
        assertIllegalArgument("a reference without a fresh confirmation must reject COMPACT") {
            ForegroundReportFormatter.formatAtomic(base.copy(evidenceRef = "FG-002"), clock)
        }

        val legal = ForegroundReportFormatter.formatAtomic(
            base.copy(
                evidenceRef = "FG-002",
                freshConfirmation = ForegroundFreshConfirmation(
                    source = "Accessibility",
                    confirmedAtMs = 1_720_000_075_900L,
                    foregroundUnchanged = true,
                    selected = true
                )
            ),
            clock
        )
        val text = render(legal)
        assertEquals("FG_DECISION", legal.type)
        assertTrue(text.contains("detail=COMPACT"))
        assertTrue(text.contains("evidenceRef=FG-002"))
        assertTrue(text.contains("[3 证据] status=AVAILABLE foregroundUnchanged=true"))
        assertTrue(text.contains("lastConfirmedAt="))
        assertTrue(text.contains("confirmedBy=Accessibility selected=true"))
        assertEquals(5, legal.bodyLines.size)
    }

    @Test
    fun oversizeForegroundDecisionKeepsRequiredEvidenceAndDeclaresCompressionInsteadOfTruncatingSilently() {
        val noisy = "候选窗口属性=" + "x".repeat(8_000)
        val oversized = fullReport().copy(
            sources = fullReport().sources.map { it.copy(current = it.current + " $noisy") },
            inference = fullReport().inference + " $noisy"
        )

        val draft = ForegroundReportFormatter.formatAtomic(oversized, clock)
        val text = render(draft)

        assertTrue("normal foreground event must remain within the 3 KiB UTF-8 budget", text.toByteArray(Charsets.UTF_8).size <= 3 * 1024)
        assertTrue(text.contains("compressed=true"))
        assertTrue(text.contains("payloadBytes="))
        assertTrue(text.contains("omittedFields="))
        assertTrue(text.contains("[1 结果]"))
        assertTrue(text.contains("[2 状态]"))
        assertTrue(text.contains("[3 UsageEvents] status=AVAILABLE"))
        assertTrue(text.contains("[4 UsageStats] status=AVAILABLE"))
        assertTrue(text.contains("[5 Accessibility] status=AVAILABLE"))
        assertTrue(text.contains("[7 锁屏] requestId=LOCK-001 result=LOCKED"))
    }

    @Test
    fun controlledAppDesktopTransitionUsesExactlyOneTransitionEventOnlyWhenNoFullReportIsNeeded() {
        val transition = ForegroundTransition(from = "抖音", to = "桌面")

        val transitionOnly = ForegroundReportFormatter.formatForTransition(
            transition = transition,
            fullReport = null,
            clock = clock
        )
        assertEquals(listOf("FOREGROUND_TRANSITION"), transitionOnly.map { it.type })
        assertTrue(render(transitionOnly.single()).contains("transition=抖音→桌面"))

        val full = ForegroundReportFormatter.formatForTransition(
            transition = transition,
            fullReport = fullReport().copy(transition = transition),
            clock = clock
        )
        assertEquals("a full diagnostic must replace, not accompany, the transition event", listOf("FG_DECISION"), full.map { it.type })
    }

    @Test
    fun usageStatsPreviousEvidenceComesFromThePriorSelectedSnapshotNotAnotherCandidateInTheSameQuery() {
        val tracker = ForegroundUsageStatsSnapshotTracker()
        val oldSelection = ForegroundCandidate("桌面", "USAGE_STAT", 10L, lastTimeVisibleMs = 10L)
        val first = tracker.record(oldSelection)
        assertEquals("NONE", first.previous)
        assertTrue(first.current.contains("桌面"))

        val selected = ForegroundCandidate("抖音", "USAGE_STAT", 30L, lastTimeVisibleMs = 30L)
        val unrelatedCandidate = ForegroundCandidate("设置", "USAGE_STAT", 29L, lastTimeVisibleMs = 29L)
        // Another row from this query must not become prev merely because it was returned by
        // UsageStats; the production adapter explicitly selects the actual winning package.
        val second = tracker.recordSelectedFromCandidates(
            selectedPackageName = "抖音",
            candidates = listOf(unrelatedCandidate, selected)
        )
        assertTrue(second.previous.contains("桌面"))
        assertTrue(second.current.contains("抖音"))
        assertTrue(!second.previous.contains(unrelatedCandidate.packageName))
    }

    @Test
    fun stateTrackerEmitsTransitionWithoutFullAndEndsSuppressedEquivalentStateOnce() {
        val tracker = ForegroundNormalEventStateTracker()
        val first = tracker.observe(
            stateKey = "抖音|ALLOW|TEMP_ACCESS_ACTIVE",
            packageName = "抖音",
            isDesktop = false,
            hasFullDiagnostic = true,
            nowMs = 1_000L,
            clock = clock
        )
        assertTrue(first.isEmpty())
        tracker.observe(
            stateKey = "抖音|ALLOW|TEMP_ACCESS_ACTIVE",
            packageName = "抖音",
            isDesktop = false,
            hasFullDiagnostic = false,
            nowMs = 2_000L,
            clock = clock
        )
        val desktop = tracker.observe(
            stateKey = "桌面|ALLOW|SAFE_APP",
            packageName = "桌面",
            isDesktop = true,
            hasFullDiagnostic = false,
            nowMs = 4_000L,
            clock = clock
        )
        assertEquals(listOf("FG_END", "FOREGROUND_TRANSITION"), desktop.map { it.type })
        assertTrue(render(desktop[0]).contains("dwellMs=3000"))
        assertTrue(render(desktop[0]).contains("suppressedCount=1"))
        assertTrue(render(desktop[1]).contains("transition=抖音→桌面"))
    }

    @Test
    fun lockResultNormalEventCorrelatesTheEarlierRequestedForegroundDecision() {
        val draft = ForegroundReportFormatter.formatLockResultEvent(
            requestId = "LOCK-001",
            decisionRef = "FG-001",
            packageName = "com.ss.android.ugc.aweme",
            result = "LOCKED",
            reason = "WINDOW_ATTACHED",
            duplicate = false,
            clock = clock
        )
        val text = render(draft)
        assertEquals("LOCK_RESULT", draft.type)
        assertTrue(text.contains("requestId=LOCK-001"))
        assertTrue(text.contains("decisionRef=FG-001"))
        assertTrue(text.contains("result=LOCKED"))
    }

    @Test
    fun lockRequestEarlyReturnSettlesExactlyOnceWithoutClaimingLocked() {
        val tracker = ForegroundLockResultSettlementTracker()
        val early = tracker.settle(
            requestId = "LOCK-EARLY",
            decisionRef = "FG-EARLY",
            packageName = "com.ss.android.ugc.aweme",
            result = "NOT_DISPATCHED",
            reason = "CONTEXT_UNAVAILABLE",
            duplicate = false,
            clock = clock
        )
        val duplicate = tracker.settle(
            requestId = "LOCK-EARLY",
            decisionRef = "FG-EARLY",
            packageName = "com.ss.android.ugc.aweme",
            result = "LOCKED",
            reason = "SHOULD_NOT_REPLACE",
            duplicate = false,
            clock = clock
        )
        assertTrue(early != null)
        assertEquals(null, duplicate)
        val text = render(requireNotNull(early))
        assertTrue(text.contains("result=NOT_DISPATCHED"))
        assertTrue(text.contains("reason=CONTEXT_UNAVAILABLE"))
    }

    @Test
    fun lockRequestSuccessSettlesExactlyOnceAsLocked() {
        val tracker = ForegroundLockResultSettlementTracker()
        val success = tracker.settle(
            requestId = "LOCK-SUCCESS",
            decisionRef = "FG-SUCCESS",
            packageName = "com.ss.android.ugc.aweme",
            result = "LOCKED",
            reason = "WINDOW_ATTACHED",
            duplicate = false,
            clock = clock
        )
        assertEquals("LOCK_RESULT", requireNotNull(success).type)
        assertTrue(render(success).contains("result=LOCKED"))
        assertTrue(render(success).contains("reason=WINDOW_ATTACHED"))
    }

    @Test
    fun lockRequestFailureSettlesExactlyOnceWithFailureReason() {
        val tracker = ForegroundLockResultSettlementTracker()
        val failure = tracker.settle(
            requestId = "LOCK-FAIL",
            decisionRef = "FG-FAIL",
            packageName = "com.ss.android.ugc.aweme",
            result = "FAILED",
            reason = "ADD_VIEW_FAILED",
            duplicate = false,
            failureClass = "BadTokenException",
            clock = clock
        )
        val duplicate = tracker.settle(
            requestId = "LOCK-FAIL",
            decisionRef = "FG-FAIL",
            packageName = "com.ss.android.ugc.aweme",
            result = "FAILED",
            reason = "SECOND_FAILURE",
            duplicate = false,
            clock = clock
        )
        assertTrue(failure != null)
        assertEquals(null, duplicate)
        val text = render(requireNotNull(failure))
        assertTrue(text.contains("result=FAILED"))
        assertTrue(text.contains("reason=ADD_VIEW_FAILED"))
        assertTrue(text.contains("failureClass=BadTokenException"))
    }

    @Test
    fun defaultLockResultExportWritesOnlyTheNormalRecordNotTheLegacyDiagnosticLine() {
        val source = findLockWindowManagerSource().readText()
        assertTrue(source.contains("foregroundLockNormalEventLogger::emit"))
        assertTrue(
            "默认路径不得把同一前台锁结果再写成旧 [锁屏结果] 导出行",
            !source.contains("LogExporter.logEvent(\"LockWindowManager\", line)")
        )
        assertTrue(
            "实际窗口附着前不得默认导出旧的 LockScreen 锁定结果摘要",
            !source.contains("\"LockScreen\",\n                \"锁定结果:")
        )
    }

    @Test
    fun productionBridgePreservesAccessibilitySnapshotSelectionTransitionAndSingleLockSettlement() {
        val captured = mutableListOf<NormalEventDraft>()
        val bridge = ForegroundNormalEventBridge(clock = clock, emit = { draft -> captured.add(draft) })
        val cycle = ForegroundDiagnosticCycle(reportId = "FG-BRIDGE", checkedAtMs = 1_000L)
        cycle.updateUsageEvents(
            ForegroundSourceSnapshot(
                source = "USAGE_EVENTS", status = ForegroundSourceStatus.AVAILABLE,
                packageName = "抖音", vote = ForegroundVote.BLACKLIST,
                capturedAtMs = 1_020L, queryStartedAtMs = 1_010L, queryFinishedAtMs = 1_020L,
                candidates = listOf(ForegroundCandidate("抖音", "RESUMED", 1_019L)),
                selectionReason = "must_not_be_rendered_as_selected", unavailableReason = null,
                failureClass = null, previousEvidence = "桌面", currentEvidence = "抖音"
            )
        )
        cycle.updateUsageStats(
            ForegroundSourceSnapshot(
                source = "USAGE_STATS", status = ForegroundSourceStatus.AVAILABLE,
                packageName = "抖音", vote = ForegroundVote.BLACKLIST,
                capturedAtMs = 1_020L, queryStartedAtMs = 1_010L, queryFinishedAtMs = 1_020L,
                candidates = emptyList(), selectionReason = "other_selection_reason", unavailableReason = null,
                failureClass = null, previousEvidence = "桌面", currentEvidence = "抖音"
            )
        )
        cycle.updateAccessibility(
            ForegroundSourceSnapshot(
                source = "ACCESSIBILITY", status = ForegroundSourceStatus.AVAILABLE,
                packageName = "抖音", vote = ForegroundVote.BLACKLIST,
                capturedAtMs = 1_025L, queryStartedAtMs = null, queryFinishedAtMs = null,
                candidates = emptyList(), selectionReason = "active_window", unavailableReason = null,
                failureClass = null,
                previousAccessibilitySnapshot = AccessibilityForegroundSnapshot(
                    eventAtMs = 900L, handledAtMs = 905L, eventType = "TYPE_WINDOWS_CHANGED",
                    eventPackageName = "桌面", snapshotAtMs = 906L, windowSummary = "windows=一致"
                ),
                currentAccessibilitySnapshot = AccessibilityForegroundSnapshot(
                    eventAtMs = 1_021L, handledAtMs = 1_025L, eventType = "TYPE_WINDOWS_CHANGED",
                    eventPackageName = "抖音", snapshotAtMs = 1_024L, windowSummary = "windows=一致"
                )
            )
        )
        val outcome = ForegroundReportOutcome(
            effectivePackageName = "抖音", effectivePackageBlacklisted = true,
            diagnosticRecommendation = "黑名单→无放行→锁屏",
            businessAction = ForegroundBusinessAction.REQUEST_LOCK,
            businessReason = "BLACKLIST_SELECTED", allowanceState = "NONE",
            guardPassed = false, visibilityPassed = true
        )

        bridge.emitFull(
            cycle = cycle, outcome = outcome, selectedSource = "EVENTS", requestId = "LOCK-BRIDGE",
            traceId = "LOCK-BRIDGE", transition = ForegroundTransition("桌面", "抖音")
        )
        bridge.emitTransition(ForegroundTransition("抖音", "桌面"), fullReport = null)
        bridge.settleLock(
            requestId = "LOCK-BRIDGE", decisionRef = "FG-BRIDGE", packageName = "抖音",
            result = "LOCKED", reason = "WINDOW_ATTACHED", duplicate = false
        )
        bridge.settleLock(
            requestId = "LOCK-BRIDGE", decisionRef = "FG-BRIDGE", packageName = "抖音",
            result = "FAILED", reason = "MUST_NOT_DUPLICATE", duplicate = false
        )

        assertEquals(listOf("FG_DECISION", "FOREGROUND_TRANSITION", "LOCK_RESULT"), captured.map { it.type })
        val full = render(captured[0])
        assertTrue(full.contains("transition=桌面→抖音"))
        assertTrue(full.contains("[3 UsageEvents] status=AVAILABLE prev={桌面} curr={抖音} selected=curr"))
        assertTrue(full.contains("[4 UsageStats] status=AVAILABLE prev={桌面} curr={抖音} selected=not_selected"))
        assertTrue("eventAtMs=... (900) must appear in dual-display format",
            full.contains("eventAtMs=") && full.contains("(900)"))
        assertTrue("handledAtMs=... (905) must appear in dual-display format",
            full.contains("handledAtMs=") && full.contains("(905)"))
        assertTrue(full.contains("eventType=TYPE_WINDOWS_CHANGED eventPackageName=桌面"))
        assertTrue("snapshotAtMs=... (906) must appear in dual-display format",
            full.contains("snapshotAtMs=") && full.contains("(906)"))
        assertTrue(full.contains("windows=一致"))
        assertTrue("eventAtMs=... (1021) must appear in dual-display format",
            full.contains("eventAtMs=") && full.contains("(1021)"))
        assertTrue("handledAtMs=... (1025) must appear in dual-display format",
            full.contains("handledAtMs=") && full.contains("(1025)"))
        assertTrue(full.contains("eventType=TYPE_WINDOWS_CHANGED eventPackageName=抖音"))
        assertTrue("snapshotAtMs=... (1024) must appear in dual-display format",
            full.contains("snapshotAtMs=") && full.contains("(1024)"))
        assertTrue(full.contains("windows=一致"))
        assertTrue(render(captured[1]).contains("transition=抖音→桌面"))
        assertTrue(render(captured[2]).contains("result=LOCKED"))
    }

    private fun fullReport(): ForegroundAtomicReport = ForegroundAtomicReport(
        reportId = "FG-001",
        detail = ForegroundReportDetail.FULL,
        transition = ForegroundTransition(from = "桌面", to = "抖音"),
        result = ForegroundResultEvidence(
            foreground = "抖音",
            packageName = "com.ss.android.ugc.aweme",
            action = "LOCK",
            reason = "BLACKLIST_SELECTED"
        ),
        state = ForegroundStateEvidence(
            allowance = "NONE",
            appLock = "LOCKED",
            foregroundGuard = "LOCKED"
        ),
        sources = listOf(
            ForegroundSourceEvidence(
                label = "UsageEvents",
                status = "AVAILABLE",
                previous = "08:29:57 PAUSED 桌面",
                current = "08:29:59 RESUMED 抖音",
                selected = "curr"
            ),
            ForegroundSourceEvidence(
                label = "UsageStats",
                status = "AVAILABLE",
                previous = "桌面 lastTimeVisible=08:29:57.900",
                current = "抖音 lastTimeVisible=08:29:59.800",
                selected = "curr"
            ),
            ForegroundSourceEvidence(
                label = "Accessibility",
                status = "AVAILABLE",
                previous = "08:29:57 WINDOW 桌面 windows=一致",
                current = "08:29:59 WINDOW 抖音 windows=一致",
                selected = "curr"
            )
        ),
        inference = "三源指向抖音→黑名单→无放行→锁屏",
        lock = ForegroundLockEvidence(
            requestId = "LOCK-001",
            result = "LOCKED",
            duplicate = false,
            countedForPenalty = false
        )
    )

    private fun render(draft: NormalEventDraft): String = DefaultNormalEventRenderer.render(
        NormalEvent(
            eventId = "test-event",
            processInstanceId = "test-process",
            sequence = 1L,
            draft = draft
        )
    )

    private fun assertIllegalArgument(message: String, action: () -> Unit) {
        try {
            action()
        } catch (_: IllegalArgumentException) {
            return
        }
        throw AssertionError(message)
    }

    private fun findLockWindowManagerSource(): java.io.File {
        val relative = "app/src/main/java/com/decikeeper/app/ui/LockWindowManager.kt"
        return generateSequence(java.io.File(System.getProperty("user.dir") ?: ".").absoluteFile) { it.parentFile }
            .map { java.io.File(it, relative) }
            .firstOrNull { it.isFile }
            ?: error("找不到 $relative；user.dir=${System.getProperty("user.dir")}")
    }

    private class FixedEventClock(
        private val wallMs: Long,
        private val elapsedMs: Long
    ) : EventClock {
        override fun wallNowMs(): Long = wallMs

        override fun elapsedNowMs(): Long = elapsedMs
    }
}
