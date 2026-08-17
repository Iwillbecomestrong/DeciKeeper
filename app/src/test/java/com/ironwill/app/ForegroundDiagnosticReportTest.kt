package com.ironwill.app

import com.ironwill.app.data.ForegroundBusinessAction
import com.ironwill.app.data.ForegroundCandidate
import com.ironwill.app.data.ForegroundDiagnosticCycle
import com.ironwill.app.data.ForegroundEnforcementDisposition
import com.ironwill.app.data.ForegroundFaultSnapshotFactory
import com.ironwill.app.data.ForegroundReportAssessmentFactory
import com.ironwill.app.data.ForegroundReportDecision
import com.ironwill.app.data.ForegroundReportEmission
import com.ironwill.app.data.ForegroundReportGate
import com.ironwill.app.data.ForegroundReportOutcome
import com.ironwill.app.data.ForegroundReportTrigger
import com.ironwill.app.data.ForegroundSourceRelation
import com.ironwill.app.data.ForegroundSourceSnapshot
import com.ironwill.app.data.ForegroundSourceStatus
import com.ironwill.app.data.ForegroundVote
import com.ironwill.app.data.resolveAccessibilityFailureClass
import com.ironwill.app.service.CoreService
import com.ironwill.app.util.ForegroundReportFormatter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test




















class ForegroundDiagnosticReportTest {

    private val fixedNow: Long = 1725000000000L

    private fun eventsSnapshot(
        packageName: String? = "com.ss.android.ugc.aweme",
        status: ForegroundSourceStatus = ForegroundSourceStatus.AVAILABLE,
        vote: ForegroundVote = if (packageName == "com.ss.android.ugc.aweme") ForegroundVote.BLACKLIST else ForegroundVote.NON_BLACKLIST
    ): ForegroundSourceSnapshot = ForegroundSourceSnapshot(
        source = "USAGE_EVENTS",
        status = status,
        packageName = packageName,
        vote = vote,
        capturedAtMs = fixedNow,
        queryStartedAtMs = fixedNow - 5L,
        queryFinishedAtMs = fixedNow - 4L,
        candidates = emptyList(),
        selectionReason = "newest_resume",
        unavailableReason = null,
        failureClass = null
    )

    private fun usageStatsSnapshot(
        packageName: String? = "com.ss.android.ugc.aweme",
        status: ForegroundSourceStatus = ForegroundSourceStatus.AVAILABLE,
        vote: ForegroundVote = if (packageName == "com.ss.android.ugc.aweme") ForegroundVote.BLACKLIST else ForegroundVote.NON_BLACKLIST
    ): ForegroundSourceSnapshot = ForegroundSourceSnapshot(
        source = "USAGE_STATS",
        status = status,
        packageName = packageName,
        vote = vote,
        capturedAtMs = fixedNow,
        queryStartedAtMs = fixedNow - 3L,
        queryFinishedAtMs = fixedNow - 2L,
        candidates = emptyList(),
        selectionReason = "lastTimeUsed",
        unavailableReason = null,
        failureClass = null
    )

    private fun accessibilitySnapshot(
        packageName: String? = "com.ss.android.ugc.aweme",
        status: ForegroundSourceStatus = ForegroundSourceStatus.AVAILABLE,
        vote: ForegroundVote = if (status == ForegroundSourceStatus.AMBIGUOUS) ForegroundVote.ABSTAIN
        else if (status == ForegroundSourceStatus.UNAVAILABLE || status == ForegroundSourceStatus.FAILED) ForegroundVote.ABSTAIN
        else if (packageName == "com.ss.android.ugc.aweme") ForegroundVote.BLACKLIST
        else ForegroundVote.NON_BLACKLIST
    ): ForegroundSourceSnapshot = ForegroundSourceSnapshot(
        source = "ACCESSIBILITY",
        status = status,
        packageName = packageName,
        vote = vote,
        capturedAtMs = fixedNow,
        queryStartedAtMs = fixedNow - 1L,
        queryFinishedAtMs = fixedNow,
        candidates = emptyList(),
        selectionReason = "active_window",
        unavailableReason = null,
        failureClass = null
    )

    private fun newCycle(
        events: ForegroundSourceSnapshot = eventsSnapshot(),
        usageStats: ForegroundSourceSnapshot = usageStatsSnapshot(),
        accessibility: ForegroundSourceSnapshot = accessibilitySnapshot()
    ): ForegroundDiagnosticCycle {
        val cycle = ForegroundDiagnosticCycle(
            reportId = "FG-20240801-120000-0001",
            checkedAtMs = fixedNow
        )
        cycle.updateUsageEvents(events)
        cycle.updateUsageStats(usageStats)
        cycle.updateAccessibility(accessibility)
        return cycle
    }

    @Test
    fun requestLockAlwaysEmitsFullReport() {
        val gate = ForegroundReportGate()
        val cycle = newCycle()
        val outcome = ForegroundReportOutcome(
            effectivePackageName = "com.ss.android.ugc.aweme",
            effectivePackageBlacklisted = true,
            diagnosticRecommendation = "LOCK_BY_DIAGNOSTIC_MAJORITY",
            businessAction = ForegroundBusinessAction.REQUEST_LOCK,
            businessReason = "BLACKLIST_DND_LOCKED",
            allowanceState = "ALLOW_NONE",
            guardPassed = true,
            visibilityPassed = true
        )

        val emission = gate.evaluate(cycle, outcome, fixedNow).emission
        assertEquals(ForegroundReportEmission.FULL, emission)
    }

    @Test
    fun allSourcesUnavailableEmitsFullReport() {
        val gate = ForegroundReportGate()
        val cycle = newCycle(
            events = eventsSnapshot(packageName = null, status = ForegroundSourceStatus.UNAVAILABLE),
            usageStats = usageStatsSnapshot(packageName = null, status = ForegroundSourceStatus.UNAVAILABLE),
            accessibility = accessibilitySnapshot(packageName = null, status = ForegroundSourceStatus.UNAVAILABLE)
        )
        val outcome = ForegroundReportOutcome(
            effectivePackageName = null,
            effectivePackageBlacklisted = null,
            diagnosticRecommendation = "NO_DIAGNOSTIC_LOCK",
            businessAction = ForegroundBusinessAction.NO_LOCK,
            businessReason = "NO_FOREGROUND_RESOLVED",
            allowanceState = "UNKNOWN",
            guardPassed = null,
            visibilityPassed = null
        )
        val emission = gate.evaluate(cycle, outcome, fixedNow).emission
        assertEquals(ForegroundReportEmission.FULL, emission)
    }

    @Test
    fun firstConflictThenIdenticalConflictThenThirtySecondSummary() {
        val gate = ForegroundReportGate()
        val cycle1 = newCycle(
            events = eventsSnapshot(packageName = "com.ss.android.ugc.aweme"),
            usageStats = usageStatsSnapshot(packageName = "com.android.launcher"),
            accessibility = accessibilitySnapshot(packageName = "com.ss.android.ugc.aweme")
        )
        val outcome = ForegroundReportOutcome(
            effectivePackageName = "com.ss.android.ugc.aweme",
            effectivePackageBlacklisted = true,
            diagnosticRecommendation = "LOCK_BY_DIAGNOSTIC_MAJORITY",
            businessAction = ForegroundBusinessAction.NOT_DECIDED,
            businessReason = "INTERNAL_DIAG_ONLY",
            allowanceState = "ALLOW_NONE",
            guardPassed = null,
            visibilityPassed = null
        )
        val first = gate.evaluate(cycle1, outcome, fixedNow)
        assertEquals(ForegroundReportEmission.FULL, first.emission)

        val cycle2 = newCycle(
            events = eventsSnapshot(packageName = "com.ss.android.ugc.aweme"),
            usageStats = usageStatsSnapshot(packageName = "com.android.launcher"),
            accessibility = accessibilitySnapshot(packageName = "com.ss.android.ugc.aweme")
        )
        val second = gate.evaluate(cycle2, outcome, fixedNow + 5_000L).emission
        assertEquals(ForegroundReportEmission.NONE, second)

        val cycle3 = newCycle(
            events = eventsSnapshot(packageName = "com.ss.android.ugc.aweme"),
            usageStats = usageStatsSnapshot(packageName = "com.android.launcher"),
            accessibility = accessibilitySnapshot(packageName = "com.ss.android.ugc.aweme")
        )
        val third = gate.evaluate(cycle3, outcome, fixedNow + 30_000L).emission
        assertEquals(ForegroundReportEmission.CONTINUING_CONFLICT_SUMMARY, third)
    }

    @Test
    fun conflictChangeAndConflictRecoveryEachEmitFullReport() {
        val gate = ForegroundReportGate()
        // First introduce a conflict so the gate has a baseline
        val cycle0 = newCycle(
            events = eventsSnapshot(packageName = "com.ss.android.ugc.aweme"),
            usageStats = usageStatsSnapshot(packageName = "com.android.launcher"),
            accessibility = accessibilitySnapshot(packageName = "com.ss.android.ugc.aweme")
        )
        val outcome = ForegroundReportOutcome(
            effectivePackageName = "com.ss.android.ugc.aweme",
            effectivePackageBlacklisted = true,
            diagnosticRecommendation = "LOCK_BY_DIAGNOSTIC_MAJORITY",
            businessAction = ForegroundBusinessAction.NOT_DECIDED,
            businessReason = "INTERNAL_DIAG_ONLY",
            allowanceState = "ALLOW_NONE",
            guardPassed = null,
            visibilityPassed = null
        )
        val setup = gate.evaluate(cycle0, outcome, fixedNow).emission
        assertEquals(ForegroundReportEmission.FULL, setup)

        // Conflict signature changes (different mismatch pattern)
        val cycle1 = newCycle(
            events = eventsSnapshot(packageName = "com.android.launcher"),
            usageStats = usageStatsSnapshot(packageName = "com.android.launcher"),
            accessibility = accessibilitySnapshot(packageName = "com.ss.android.ugc.aweme")
        )
        val changed = gate.evaluate(cycle1, outcome, fixedNow + 1_000L).emission
        assertEquals(ForegroundReportEmission.FULL, changed)

        // Conflict resolves to agreement
        val cycle2 = newCycle(
            events = eventsSnapshot(packageName = "com.ss.android.ugc.aweme"),
            usageStats = usageStatsSnapshot(packageName = "com.ss.android.ugc.aweme"),
            accessibility = accessibilitySnapshot(packageName = "com.ss.android.ugc.aweme")
        )
        val recovery = gate.evaluate(cycle2, outcome, fixedNow + 2_000L).emission
        assertEquals(ForegroundReportEmission.FULL, recovery)
    }

    @Test
    fun diagnosticTieDoesNotRewriteBusinessNoLockAction() {
        val gate = ForegroundReportGate()
        val cycle = newCycle(
            events = eventsSnapshot(packageName = "com.ss.android.ugc.aweme", vote = ForegroundVote.BLACKLIST),
            usageStats = usageStatsSnapshot(packageName = "com.android.launcher", vote = ForegroundVote.NON_BLACKLIST),
            accessibility = accessibilitySnapshot(packageName = null, vote = ForegroundVote.ABSTAIN)
        )
        val outcome = ForegroundReportOutcome(
            effectivePackageName = null,
            effectivePackageBlacklisted = null,
            diagnosticRecommendation = "LOCK_ON_TIE",
            businessAction = ForegroundBusinessAction.NO_LOCK,
            businessReason = "ALLOWANCE_FREE_TIME",
            allowanceState = "FREE_TIME_ACTIVE",
            guardPassed = null,
            visibilityPassed = null
        )
        val emission = gate.evaluate(cycle, outcome, fixedNow).emission
        assertEquals(ForegroundReportEmission.FULL, emission)
        assertEquals(ForegroundBusinessAction.NO_LOCK, outcome.businessAction)
    }

    @Test
    fun formatterIncludesAllSourcesVotesCountsAndFinalReason() {
        val gate = ForegroundReportGate()
        val cycle = newCycle(
            events = eventsSnapshot(packageName = "com.ss.android.ugc.aweme", vote = ForegroundVote.BLACKLIST),
            usageStats = usageStatsSnapshot(packageName = "com.ss.android.ugc.aweme", vote = ForegroundVote.BLACKLIST),
            accessibility = accessibilitySnapshot(packageName = "com.android.launcher", vote = ForegroundVote.NON_BLACKLIST)
        )
        val outcome = ForegroundReportOutcome(
            effectivePackageName = "com.ss.android.ugc.aweme",
            effectivePackageBlacklisted = true,
            diagnosticRecommendation = "LOCK_BY_DIAGNOSTIC_MAJORITY",
            businessAction = ForegroundBusinessAction.REQUEST_LOCK,
            businessReason = "BLACKLIST_DND_LOCKED",
            allowanceState = "ALLOW_NONE",
            guardPassed = true,
            visibilityPassed = true
        )
        val decision = gate.evaluate(cycle, outcome, fixedNow)
        val formatted = ForegroundReportFormatter.format(cycle, outcome, decision, fixedNow)
        // V2-R2 §8 Task 3 Step 4 fixed-field contract:
        // header + 3 source sections + 报告触发原因 + 动作 + 业务处置 +
        // 三源当前判断 + 票数 + 来源关系 + 放行状态 + 最终原因 = 11 base lines.
        assertTrue("multi-line expected, got ${formatted.size}", formatted.size >= 11)
        val joined = formatted.joinToString("\n")
        assertTrue(joined.contains("FG-20240801-120000-0001"))
        assertTrue(joined.contains("[前台汇报]"))
        assertTrue(joined.contains("UsageEvents"))
        assertTrue(joined.contains("UsageStats"))
        assertTrue(joined.contains("Accessibility"))
        // V2-R2 §8 Task 3 Step 4 fixed-field labels:
        assertTrue(joined.contains("报告触发原因=REQUEST_LOCK"))
        assertTrue(joined.contains("动作=请求锁屏"))
        assertTrue(joined.contains("业务处置=REQUESTED_LOCK"))
        assertTrue(joined.contains("票数=BLACKLIST:2"))
        assertTrue(joined.contains("NON_BLACKLIST:1"))
        assertTrue(joined.contains("ABSTAIN:0"))
        assertTrue(joined.contains("来源关系=DISAGREEMENT"))
        assertTrue(joined.contains("放行状态="))
        assertTrue(joined.contains("最终原因=BLACKLIST_DND_LOCKED"))
        // every main report line begins with FG id and [前台汇报][line/total]
        // conflict detail line uses [前台争议明细] separately
        for (line in formatted) {
            assertTrue("line missing FG id: $line", line.contains("FG-20240801-120000-0001"))
            if (!line.contains("[前台争议明细]")) {
                assertTrue("line missing [前台汇报]: $line", line.contains("[前台汇报]"))
            }
        }
    }

    @Test
    fun formatterEmitsMultiLineWithLineAndTotalHeader() {
        val gate = ForegroundReportGate()
        val cycle = newCycle()
        val outcome = ForegroundReportOutcome(
            effectivePackageName = "com.ss.android.ugc.aweme",
            effectivePackageBlacklisted = true,
            diagnosticRecommendation = "LOCK_BY_DIAGNOSTIC_MAJORITY",
            businessAction = ForegroundBusinessAction.REQUEST_LOCK,
            businessReason = "BLACKLIST_DND_LOCKED",
            allowanceState = "ALLOW_NONE",
            guardPassed = true,
            visibilityPassed = true
        )
        val emission = gate.evaluate(cycle, outcome, fixedNow).emission
        val formatted = ForegroundReportFormatter.format(cycle, outcome, emission, fixedNow)
        // header line is first and contains [1/N]
        val header = formatted.first()
        assertTrue(header.contains("[前台汇报][1/"))
        // find a source line — should contain [2/N], [3/N], [4/N] etc.
        val sourceLine = formatted.first { it.contains("UsageEvents(") }
        assertTrue(sourceLine.contains("[前台汇报][2/"))
    }

    @Test
    fun formatterEmitsConflictDetailForPackageMismatch() {
        val gate = ForegroundReportGate()
        val cycle = newCycle(
            events = eventsSnapshot(packageName = "com.ss.android.ugc.aweme"),
            usageStats = usageStatsSnapshot(packageName = "com.android.launcher"),
            accessibility = accessibilitySnapshot(packageName = "com.ss.android.ugc.aweme")
        )
        val outcome = ForegroundReportOutcome(
            effectivePackageName = "com.ss.android.ugc.aweme",
            effectivePackageBlacklisted = true,
            diagnosticRecommendation = "LOCK_BY_DIAGNOSTIC_MAJORITY",
            businessAction = ForegroundBusinessAction.REQUEST_LOCK,
            businessReason = "BLACKLIST_DND_LOCKED",
            allowanceState = "ALLOW_NONE",
            guardPassed = null,
            visibilityPassed = null
        )
        val emission = gate.evaluate(cycle, outcome, fixedNow).emission
        val formatted = ForegroundReportFormatter.format(cycle, outcome, emission, fixedNow)
        val joined = formatted.joinToString("\n")
        assertTrue("conflict detail expected: $joined", joined.contains("[前台争议明细]"))
        assertTrue(joined.contains("package_mismatch"))
    }

    @Test
    fun formatterEmitsContinuationSingleLine() {
        val gate = ForegroundReportGate()
        val cycle = newCycle(
            events = eventsSnapshot(packageName = "com.ss.android.ugc.aweme"),
            usageStats = usageStatsSnapshot(packageName = "com.android.launcher"),
            accessibility = accessibilitySnapshot(packageName = "com.ss.android.ugc.aweme")
        )
        val outcome = ForegroundReportOutcome(
            effectivePackageName = "com.ss.android.ugc.aweme",
            effectivePackageBlacklisted = true,
            diagnosticRecommendation = "LOCK_BY_DIAGNOSTIC_MAJORITY",
            businessAction = ForegroundBusinessAction.NOT_DECIDED,
            businessReason = "INTERNAL_DIAG_ONLY",
            allowanceState = "ALLOW_NONE",
            guardPassed = null,
            visibilityPassed = null
        )
        // Trigger conflict first
        gate.evaluate(cycle, outcome, fixedNow)
        // 30s later still conflict -> CONTINUING
        val continuationCycle = newCycle(
            events = eventsSnapshot(packageName = "com.ss.android.ugc.aweme"),
            usageStats = usageStatsSnapshot(packageName = "com.android.launcher"),
            accessibility = accessibilitySnapshot(packageName = "com.ss.android.ugc.aweme")
        )
        val continuationOutcome = outcome
        val emission = gate.evaluate(continuationCycle, continuationOutcome, fixedNow + 30_000L).emission
        assertEquals(ForegroundReportEmission.CONTINUING_CONFLICT_SUMMARY, emission)
        val formatted = ForegroundReportFormatter.format(continuationCycle, continuationOutcome, emission, fixedNow + 30_000L)
        assertEquals(1, formatted.size)
        val line = formatted.first()
        assertTrue(line.contains("[前台争议持续]"))
        assertTrue(line.contains("duration="))
        assertTrue(line.contains("blacklistVotes="))
        assertTrue(line.contains("USAGE_EVENTS="))
        assertTrue(line.contains("USAGE_STATS="))
        assertTrue(line.contains("ACCESSIBILITY="))
    }

    @Test
    fun lockResultSuccess() {
        val line = ForegroundReportFormatter.formatLockResult(
            reportId = "FG-20240801-120000-0001",
            packageName = "com.ss.android.ugc.aweme",
            success = true,
            reused = false,
            requestToDisplayMs = 123L
        )
        assertTrue(line.startsWith("FG-20240801-120000-0001"))
        assertTrue(line.contains("[锁屏结果]"))
        assertTrue(line.contains("应用=com.ss.android.ugc.aweme"))
        assertTrue(line.contains("结果=显示成功"))
        assertTrue(line.contains("请求到显示=123ms"))
    }

    @Test
    fun lockResultReuse() {
        val line = ForegroundReportFormatter.formatLockResult(
            reportId = "FG-20240801-120000-0001",
            packageName = "com.ss.android.ugc.aweme",
            success = true,
            reused = true,
            requestToDisplayMs = 0L
        )
        assertTrue(line.contains("复用已有界面=true"))
        assertTrue(line.contains("请求到显示=0ms"))
        assertTrue(line.contains("结果=显示成功"))
    }

    @Test
    fun lockResultFailure() {
        val line = ForegroundReportFormatter.formatLockResult(
            reportId = "FG-20240801-120000-0001",
            packageName = "com.ss.android.ugc.aweme",
            success = false,
            reused = false,
            requestToDisplayMs = 50L,
            failureClass = "WindowManager.BadTokenException"
        )
        assertTrue(line.contains("结果=显示失败"))
        assertTrue(line.contains("原因=WindowManager.BadTokenException"))
        assertTrue(line.contains("异常=已请求锁屏但锁屏界面未显示"))
    }

    @Test
    fun fgExportRegexKeepsFullReports() {
        val gate = ForegroundReportGate()
        val cycle = newCycle()
        val outcome = ForegroundReportOutcome(
            effectivePackageName = "com.ss.android.ugc.aweme",
            effectivePackageBlacklisted = true,
            diagnosticRecommendation = "LOCK_BY_DIAGNOSTIC_MAJORITY",
            businessAction = ForegroundBusinessAction.REQUEST_LOCK,
            businessReason = "BLACKLIST_DND_LOCKED",
            allowanceState = "ALLOW_NONE",
            guardPassed = true,
            visibilityPassed = true
        )
        val emission = gate.evaluate(cycle, outcome, fixedNow).emission
        val formatted = ForegroundReportFormatter.format(cycle, outcome, emission, fixedNow)
        val fgRegex = Regex("""\bFG-\d{8}-\d{6}-\d{4}\b""")
        for (line in formatted) {
            assertTrue("FG id must match export regex: $line", fgRegex.containsMatchIn(line))
        }
    }

    @Test
    fun allSourcesUnavailableFormatterEmitsUnavailabilityAndConflictDetail() {
        val gate = ForegroundReportGate()
        val cycle = newCycle(
            events = eventsSnapshot(packageName = null, status = ForegroundSourceStatus.UNAVAILABLE),
            usageStats = usageStatsSnapshot(packageName = null, status = ForegroundSourceStatus.UNAVAILABLE),
            accessibility = accessibilitySnapshot(packageName = null, status = ForegroundSourceStatus.UNAVAILABLE)
        )
        val outcome = ForegroundReportOutcome(
            effectivePackageName = null,
            effectivePackageBlacklisted = null,
            diagnosticRecommendation = "NO_DIAGNOSTIC_LOCK",
            businessAction = ForegroundBusinessAction.NO_LOCK,
            businessReason = "NO_FOREGROUND_RESOLVED",
            allowanceState = "UNKNOWN",
            guardPassed = null,
            visibilityPassed = null
        )
        val emission = gate.evaluate(cycle, outcome, fixedNow).emission
        val formatted = ForegroundReportFormatter.format(cycle, outcome, emission, fixedNow)
        val joined = formatted.joinToString("\n")
        assertTrue(joined.contains("UsageEvents"))
        assertTrue(joined.contains("UsageStats"))
        assertTrue(joined.contains("Accessibility"))
        assertTrue(joined.contains("UNAVAILABLE"))
        assertTrue(joined.contains("[前台争议明细]"))
        assertTrue(joined.contains("all_sources_unavailable"))
    }

    @Test
    fun failedUsageEventsSourceIsFailedAndEmitsConflictDetail() {
        val gate = ForegroundReportGate()
        val cycle = newCycle(
            events = eventsSnapshot(packageName = null, status = ForegroundSourceStatus.FAILED),
            usageStats = usageStatsSnapshot(packageName = "com.android.launcher"),
            accessibility = accessibilitySnapshot(packageName = "com.android.launcher")
        )
        val outcome = ForegroundReportOutcome(
            effectivePackageName = null,
            effectivePackageBlacklisted = null,
            diagnosticRecommendation = "NO_DIAGNOSTIC_LOCK",
            businessAction = ForegroundBusinessAction.NO_LOCK,
            businessReason = "EVENTS_QUERY_FAILED",
            allowanceState = "UNKNOWN",
            guardPassed = null,
            visibilityPassed = null
        )
        val emission = gate.evaluate(cycle, outcome, fixedNow).emission
        assertEquals(ForegroundReportEmission.FULL, emission)
        val formatted = ForegroundReportFormatter.format(cycle, outcome, emission, fixedNow)
        val joined = formatted.joinToString("\n")
        assertTrue(joined.contains("[前台争议明细]"))
        assertTrue(joined.contains("source_failed=USAGE_EVENTS"))
    }

    @Test
    fun ambiguousAccessibilityWithTwoVisiblePackagesAbstains() {
        val gate = ForegroundReportGate()
        val cycle = newCycle(
            events = eventsSnapshot(packageName = "com.ss.android.ugc.aweme"),
            usageStats = usageStatsSnapshot(packageName = "com.ss.android.ugc.aweme"),
            accessibility = accessibilitySnapshot(
                packageName = null,
                status = ForegroundSourceStatus.AMBIGUOUS
            )
        )
        val outcome = ForegroundReportOutcome(
            effectivePackageName = "com.ss.android.ugc.aweme",
            effectivePackageBlacklisted = true,
            diagnosticRecommendation = "LOCK_BY_DIAGNOSTIC_MAJORITY",
            businessAction = ForegroundBusinessAction.REQUEST_LOCK,
            businessReason = "BLACKLIST_DND_LOCKED",
            allowanceState = "ALLOW_NONE",
            guardPassed = true,
            visibilityPassed = true
        )
        val emission = gate.evaluate(cycle, outcome, fixedNow).emission
        assertEquals(ForegroundReportEmission.FULL, emission)
        // Accessibility vote must be ABSTAIN when AMBIGUOUS (no auto NON_BLACKLIST)
        assertEquals(ForegroundVote.ABSTAIN, cycle.accessibility.vote)
    }

    @Test
    fun enabledButDisconnectedAccessibilityIsAmbiguousAndEmitsFullReport() {
        val gate = ForegroundReportGate()
        val cycle = newCycle(
            events = eventsSnapshot(packageName = "com.ss.android.ugc.aweme"),
            usageStats = usageStatsSnapshot(packageName = "com.ss.android.ugc.aweme"),
            accessibility = accessibilitySnapshot(
                packageName = null,
                status = ForegroundSourceStatus.AMBIGUOUS
            )
        )
        val outcome = ForegroundReportOutcome(
            effectivePackageName = "com.ss.android.ugc.aweme",
            effectivePackageBlacklisted = true,
            diagnosticRecommendation = "LOCK_BY_DIAGNOSTIC_MAJORITY",
            businessAction = ForegroundBusinessAction.REQUEST_LOCK,
            businessReason = "BLACKLIST_DND_LOCKED",
            allowanceState = "ALLOW_NONE",
            guardPassed = true,
            visibilityPassed = true
        )
        val emission = gate.evaluate(cycle, outcome, fixedNow).emission
        assertEquals(ForegroundReportEmission.FULL, emission)
        assertEquals(ForegroundSourceStatus.AMBIGUOUS, cycle.accessibility.status)
    }

    // ──────────────── Fault-snapshot factory tests (R3) ────────────────
    // These prove that the production fault paths in MonitorService push FAILED /
    // UNAVAILABLE snapshots so the ForegroundReportGate can detect "all sources
    // unavailable" as a fault (not just INITIALIZING).

    @Test
    fun faultFactoryEventsFailedHasFailedStatusAndClass() {
        val snap = ForegroundFaultSnapshotFactory.eventsFailed("USAGE_STATS_MANAGER_NULL")
        assertEquals(ForegroundSourceStatus.FAILED, snap.status)
        assertEquals("USAGE_STATS_MANAGER_NULL", snap.failureClass)
        assertEquals(ForegroundVote.ABSTAIN, snap.vote)
        assertEquals(null, snap.packageName)
    }

    @Test
    fun faultFactoryUsageStatsFailedHasFailedStatusAndClass() {
        val snap = ForegroundFaultSnapshotFactory.usageStatsFailed("QUERY_USAGE_STATS_FAILED")
        assertEquals(ForegroundSourceStatus.FAILED, snap.status)
        assertEquals("QUERY_USAGE_STATS_FAILED", snap.failureClass)
        assertEquals(ForegroundVote.ABSTAIN, snap.vote)
    }

    @Test
    fun faultFactoryEventsUnavailableHasUnavailableStatus() {
        val snap = ForegroundFaultSnapshotFactory.eventsUnavailable()
        assertEquals(ForegroundSourceStatus.UNAVAILABLE, snap.status)
        assertEquals("NO_RECENT_FOREGROUND_EVENT", snap.unavailableReason)
        assertEquals(ForegroundVote.ABSTAIN, snap.vote)
    }

    @Test
    fun faultFactoryUsageStatsUnavailableHasUnavailableStatus() {
        val snap = ForegroundFaultSnapshotFactory.usageStatsUnavailable()
        assertEquals(ForegroundSourceStatus.UNAVAILABLE, snap.status)
        assertEquals("USAGE_STATS_MANAGER_NULL_OR_EMPTY", snap.unavailableReason)
        assertEquals(ForegroundVote.ABSTAIN, snap.vote)
    }

    @Test
    fun cycleWithAllSourcesFailedEmitsFullReport() {
        // Simulate the post-fault state: MonitorService fault paths pushed FAILED
        // snapshots; cycle now has both USAGE_EVENTS and USAGE_STATS as FAILED.
        val cycle = ForegroundDiagnosticCycle(
            reportId = "FG-20240801-120000-0099",
            checkedAtMs = fixedNow
        )
        cycle.updateUsageEvents(ForegroundFaultSnapshotFactory.eventsFailed("USAGE_STATS_MANAGER_NULL", fixedNow))
        cycle.updateUsageStats(ForegroundFaultSnapshotFactory.usageStatsFailed("USAGE_STATS_MANAGER_NULL", fixedNow))
        cycle.updateAccessibility(
            ForegroundSourceSnapshot(
                source = "ACCESSIBILITY",
                status = ForegroundSourceStatus.AVAILABLE,
                packageName = null,
                vote = ForegroundVote.ABSTAIN,
                capturedAtMs = fixedNow,
                queryStartedAtMs = fixedNow - 1L,
                queryFinishedAtMs = fixedNow,
                candidates = emptyList(),
                selectionReason = "no-eligible-event",
                unavailableReason = null,
                failureClass = null
            )
        )
        val gate = ForegroundReportGate()
        val outcome = ForegroundReportOutcome(
            effectivePackageName = null,
            effectivePackageBlacklisted = null,
            diagnosticRecommendation = "NO_DIAGNOSTIC_LOCK",
            businessAction = ForegroundBusinessAction.NO_LOCK,
            businessReason = "NO_FOREGROUND_RESOLVED",
            allowanceState = "UNKNOWN",
            guardPassed = null,
            visibilityPassed = null
        )
        val emission = gate.evaluate(cycle, outcome, fixedNow).emission
        assertEquals(ForegroundReportEmission.FULL, emission)
        val formatted = ForegroundReportFormatter.format(cycle, outcome, emission, fixedNow)
        val joined = formatted.joinToString("\n")
        assertTrue("source_failed detail expected: $joined", joined.contains("[前台争议明细]"))
        assertTrue("EVENTS failed class expected: $joined", joined.contains("source_failed=USAGE_EVENTS"))
        assertTrue("USAGE_STATS failed class expected: $joined", joined.contains("USAGE_STATS_MANAGER_NULL"))
    }

    @Test
    fun cycleWithAllSourcesUnavailableEmitsFullReport() {
        val cycle = ForegroundDiagnosticCycle(
            reportId = "FG-20240801-120000-0100",
            checkedAtMs = fixedNow
        )
        cycle.updateUsageEvents(ForegroundFaultSnapshotFactory.eventsUnavailable(fixedNow))
        cycle.updateUsageStats(ForegroundFaultSnapshotFactory.usageStatsUnavailable(fixedNow))
        cycle.updateAccessibility(
            ForegroundSourceSnapshot(
                source = "ACCESSIBILITY",
                status = ForegroundSourceStatus.UNAVAILABLE,
                packageName = null,
                vote = ForegroundVote.ABSTAIN,
                capturedAtMs = fixedNow,
                queryStartedAtMs = fixedNow - 1L,
                queryFinishedAtMs = fixedNow,
                candidates = emptyList(),
                selectionReason = null,
                unavailableReason = "A11Y_INACTIVE",
                failureClass = null
            )
        )
        val gate = ForegroundReportGate()
        val outcome = ForegroundReportOutcome(
            effectivePackageName = null,
            effectivePackageBlacklisted = null,
            diagnosticRecommendation = "NO_DIAGNOSTIC_LOCK",
            businessAction = ForegroundBusinessAction.NO_LOCK,
            businessReason = "NO_FOREGROUND_RESOLVED",
            allowanceState = "UNKNOWN",
            guardPassed = null,
            visibilityPassed = null
        )
        val emission = gate.evaluate(cycle, outcome, fixedNow).emission
        assertEquals(ForegroundReportEmission.FULL, emission)
        val formatted = ForegroundReportFormatter.format(cycle, outcome, emission, fixedNow)
        val joined = formatted.joinToString("\n")
        assertTrue("all_sources_unavailable detail expected: $joined",
            joined.contains("[前台争议明细]") && joined.contains("all_sources_unavailable"))
    }

    // ──────────────── Web-review regression tests ────────────────

    @Test
    fun conflictSignatureDetectsVoteChangeEvenWhenPackageUnchanged() {
        val gate = ForegroundReportGate()
        // Round 1: introduce a real conflict by mixing packages (USAGE_STATS disagrees).
        val cycle1 = newCycle(
            events = eventsSnapshot(packageName = "com.ss.android.ugc.aweme", vote = ForegroundVote.BLACKLIST),
            usageStats = usageStatsSnapshot(packageName = "com.android.launcher", vote = ForegroundVote.NON_BLACKLIST),
            accessibility = accessibilitySnapshot(packageName = "com.ss.android.ugc.aweme", vote = ForegroundVote.BLACKLIST)
        )
        val outcome = ForegroundReportOutcome(
            effectivePackageName = "com.ss.android.ugc.aweme",
            effectivePackageBlacklisted = true,
            diagnosticRecommendation = "LOCK_BY_DIAGNOSTIC_MAJORITY",
            businessAction = ForegroundBusinessAction.NOT_DECIDED,
            businessReason = "INTERNAL_DIAG_ONLY",
            allowanceState = "ALLOW_NONE",
            guardPassed = null,
            visibilityPassed = null
        )
        gate.evaluate(cycle1, outcome, fixedNow) // first conflict

        // Round 2: same package mismatch pattern, but USAGE_EVENTS flips to
        // NON_BLACKLIST. The VOTE[] field of the signature changes so FULL must fire.
        val cycle2 = newCycle(
            events = eventsSnapshot(packageName = "com.ss.android.ugc.aweme", vote = ForegroundVote.NON_BLACKLIST),
            usageStats = usageStatsSnapshot(packageName = "com.android.launcher", vote = ForegroundVote.NON_BLACKLIST),
            accessibility = accessibilitySnapshot(packageName = "com.ss.android.ugc.aweme", vote = ForegroundVote.BLACKLIST)
        )
        val emission = gate.evaluate(cycle2, outcome, fixedNow + 5_000L).emission
        assertEquals(
            "vote flip should fire FULL even when packageName mismatch pattern unchanged",
            ForegroundReportEmission.FULL,
            emission
        )
    }

    @Test
    fun conflictSignatureDetectsBusinessReasonChangeEvenWhenVotesMatch() {
        val gate = ForegroundReportGate()
        // Round 1: blacklisted NO_LOCK with TEMPORARY_ACCESS_ACTIVE.
        val cycle1 = newCycle(
            events = eventsSnapshot(packageName = "com.ss.android.ugc.aweme", vote = ForegroundVote.BLACKLIST),
            usageStats = usageStatsSnapshot(packageName = "com.ss.android.ugc.aweme", vote = ForegroundVote.BLACKLIST),
            accessibility = accessibilitySnapshot(packageName = "com.ss.android.ugc.aweme", vote = ForegroundVote.BLACKLIST)
        )
        val outcomeTemp = ForegroundReportOutcome(
            effectivePackageName = "com.ss.android.ugc.aweme",
            effectivePackageBlacklisted = true,
            diagnosticRecommendation = "LOCK_BY_DIAGNOSTIC_MAJORITY",
            businessAction = ForegroundBusinessAction.NO_LOCK,
            businessReason = "TEMPORARY_ACCESS_ACTIVE",
            allowanceState = "TEMP_ACCESS_ACTIVE",
            guardPassed = null,
            visibilityPassed = null
        )
        gate.evaluate(cycle1, outcomeTemp, fixedNow)

        // Round 2: same votes / package, but business reason flipped to FREE_TIME_BYPASS.
        // V2-R2 §4.2 / §8 Task 2 design: LEGITIMATE_BYPASS + sources-all-agree is silent
        // (no source problem, no missed-lock). The change in businessReason does NOT
        // alone fire FULL when the disposition stays in the legitimate-bypass bucket.
        val cycle2 = newCycle(
            events = eventsSnapshot(packageName = "com.ss.android.ugc.aweme", vote = ForegroundVote.BLACKLIST),
            usageStats = usageStatsSnapshot(packageName = "com.ss.android.ugc.aweme", vote = ForegroundVote.BLACKLIST),
            accessibility = accessibilitySnapshot(packageName = "com.ss.android.ugc.aweme", vote = ForegroundVote.BLACKLIST)
        )
        val outcomeFree = outcomeTemp.copy(
            businessReason = "FREE_TIME_BYPASS",
            allowanceState = "FREE_TIME_ACTIVE"
        )
        val emission = gate.evaluate(cycle2, outcomeFree, fixedNow + 5_000L).emission
        assertEquals(
            "all-agree + LEGITIMATE_BYPASS must remain silent even when businessReason flips",
            ForegroundReportEmission.NONE,
            emission
        )
    }

    @Test
    fun blacklistedNoLockWithoutConflictClearsConflictState() {
        val gate = ForegroundReportGate()
        // Round 1: introduce a conflict first (package mismatch).
        val cycleConflict = newCycle(
            events = eventsSnapshot(packageName = "com.ss.android.ugc.aweme"),
            usageStats = usageStatsSnapshot(packageName = "com.android.launcher"),
            accessibility = accessibilitySnapshot(packageName = "com.ss.android.ugc.aweme")
        )
        val outcomeConflict = ForegroundReportOutcome(
            effectivePackageName = "com.ss.android.ugc.aweme",
            effectivePackageBlacklisted = false,
            diagnosticRecommendation = "LOCK_BY_DIAGNOSTIC_MAJORITY",
            businessAction = ForegroundBusinessAction.NOT_DECIDED,
            businessReason = "INTERNAL_DIAG_ONLY",
            allowanceState = "ALLOW_NONE",
            guardPassed = null,
            visibilityPassed = null
        )
        val first = gate.evaluate(cycleConflict, outcomeConflict, fixedNow).emission
        assertEquals(ForegroundReportEmission.FULL, first)
        assertTrue("conflict state recorded", gate.lastConflictStartedAtMs() > 0L)

        // Round 2: source disagreement RESOLVES into agreement (all three sources
        // now report the same non-blacklisted package) and the business action is
        // still NO_LOCK but with a legitimate bypass reason. Per V2-R2 §4.1 the
        // source-relation transitions from DISAGREEMENT to ALL_AGREE; combined
        // with a LEGITIMATE_BYPASS disposition, the conflict state must be
        // cleared (no fake "recovery" full report).
        val cycleAgree = newCycle(
            events = eventsSnapshot(packageName = "com.android.launcher", vote = ForegroundVote.NON_BLACKLIST),
            usageStats = usageStatsSnapshot(packageName = "com.android.launcher", vote = ForegroundVote.NON_BLACKLIST),
            accessibility = accessibilitySnapshot(packageName = "com.android.launcher", vote = ForegroundVote.NON_BLACKLIST)
        )
        val outcomeLegitBypass = ForegroundReportOutcome(
            effectivePackageName = "com.android.launcher",
            effectivePackageBlacklisted = true,
            diagnosticRecommendation = "NO_DIAGNOSTIC_LOCK",
            businessAction = ForegroundBusinessAction.NO_LOCK,
            businessReason = "SAFE_APP_BYPASS",
            allowanceState = "SAFE_APP_BYPASS",
            guardPassed = null,
            visibilityPassed = null
        )
        gate.evaluate(cycleAgree, outcomeLegitBypass, fixedNow + 5_000L)
        assertEquals(
            "conflict started must be cleared after LEGITIMATE_BYPASS resolves the source disagreement",
            0L,
            gate.lastConflictStartedAtMs()
        )
    }

    // ──────────────── V2-R2 §8 Task 1 Step 1: legitimate silence tests ────────────────
    // Each of these represents a case where the three sources agree AND the
    // business action is a legitimate no-lock. V1 incorrectly emitted FULL for
    // every blacklisted NO_LOCK with all sources agreeing (the legacy
    // blacklistedNoLockAlwaysEmitsFullReport gate path). V2 must emit NONE.

    private fun agreedCycle(): ForegroundDiagnosticCycle = newCycle(
        events = eventsSnapshot(packageName = "com.ss.android.ugc.aweme", vote = ForegroundVote.BLACKLIST),
        usageStats = usageStatsSnapshot(packageName = "com.ss.android.ugc.aweme", vote = ForegroundVote.BLACKLIST),
        accessibility = accessibilitySnapshot(packageName = "com.ss.android.ugc.aweme", vote = ForegroundVote.BLACKLIST)
    )

    private fun noForegroundCycle(): ForegroundDiagnosticCycle = newCycle(
        events = eventsSnapshot(packageName = null, status = ForegroundSourceStatus.AVAILABLE, vote = ForegroundVote.ABSTAIN),
        usageStats = usageStatsSnapshot(packageName = null, status = ForegroundSourceStatus.AVAILABLE, vote = ForegroundVote.ABSTAIN),
        accessibility = accessibilitySnapshot(packageName = null, status = ForegroundSourceStatus.AVAILABLE, vote = ForegroundVote.ABSTAIN)
    )

    @Test
    fun lockAlreadyAttachedWithAllSourcesAgreeEmitsNothing() {
        val gate = ForegroundReportGate()
        val cycle = agreedCycle()
        val outcome = ForegroundReportOutcome(
            effectivePackageName = "com.ss.android.ugc.aweme",
            effectivePackageBlacklisted = true,
            diagnosticRecommendation = "LOCK_BY_DIAGNOSTIC_MAJORITY",
            businessAction = ForegroundBusinessAction.NO_LOCK,
            businessReason = "LOCK_ALREADY_ATTACHED",
            allowanceState = "LOCK_ATTACHED",
            guardPassed = true,
            visibilityPassed = true
        )
        assertEquals(
            ForegroundReportEmission.NONE,
            gate.evaluate(cycle, outcome, fixedNow).emission
        )
    }

    @Test
    fun foregroundGuardRejectionWithAllSourcesAgreeEmitsNothing() {
        val gate = ForegroundReportGate()
        val cycle = agreedCycle()
        val outcome = ForegroundReportOutcome(
            effectivePackageName = "com.ss.android.ugc.aweme",
            effectivePackageBlacklisted = true,
            diagnosticRecommendation = "LOCK_BY_DIAGNOSTIC_MAJORITY",
            businessAction = ForegroundBusinessAction.NO_LOCK,
            businessReason = "FOREGROUND_GUARD_REJECTED",
            allowanceState = "ALLOW_NONE",
            guardPassed = false,
            visibilityPassed = null
        )
        assertEquals(
            ForegroundReportEmission.NONE,
            gate.evaluate(cycle, outcome, fixedNow).emission
        )
    }

    @Test
    fun bypassVisibilityRejectedWithAllSourcesAgreeEmitsNothing() {
        val gate = ForegroundReportGate()
        val cycle = agreedCycle()
        val outcome = ForegroundReportOutcome(
            effectivePackageName = "com.ss.android.ugc.aweme",
            effectivePackageBlacklisted = true,
            diagnosticRecommendation = "LOCK_BY_DIAGNOSTIC_MAJORITY",
            businessAction = ForegroundBusinessAction.NO_LOCK,
            businessReason = "BYPASS_VISIBILITY_REJECTED",
            allowanceState = "ALLOW_NONE",
            guardPassed = true,
            visibilityPassed = false
        )
        assertEquals(
            ForegroundReportEmission.NONE,
            gate.evaluate(cycle, outcome, fixedNow).emission
        )
    }

    @Test
    fun pwaBypassWithAllSourcesAgreeEmitsNothing() {
        val gate = ForegroundReportGate()
        val cycle = agreedCycle()
        val outcome = ForegroundReportOutcome(
            effectivePackageName = "com.ss.android.ugc.aweme",
            effectivePackageBlacklisted = true,
            diagnosticRecommendation = "LOCK_BY_DIAGNOSTIC_MAJORITY",
            businessAction = ForegroundBusinessAction.NO_LOCK,
            businessReason = "PWA_BYPASS",
            allowanceState = "PWA_BYPASS",
            guardPassed = true,
            visibilityPassed = null
        )
        assertEquals(
            ForegroundReportEmission.NONE,
            gate.evaluate(cycle, outcome, fixedNow).emission
        )
    }

    @Test
    fun safeAppWithAllSourcesAgreeEmitsNothing() {
        val gate = ForegroundReportGate()
        val cycle = agreedCycle()
        val outcome = ForegroundReportOutcome(
            effectivePackageName = "com.ss.android.ugc.aweme",
            effectivePackageBlacklisted = true,
            diagnosticRecommendation = "LOCK_BY_DIAGNOSTIC_MAJORITY",
            businessAction = ForegroundBusinessAction.NO_LOCK,
            businessReason = "SAFE_APP_BYPASS",
            allowanceState = "SAFE_APP_BYPASS",
            guardPassed = true,
            visibilityPassed = null
        )
        assertEquals(
            ForegroundReportEmission.NONE,
            gate.evaluate(cycle, outcome, fixedNow).emission
        )
    }

    @Test
    fun weeklyPrivilegeWithAllSourcesAgreeEmitsNothing() {
        val gate = ForegroundReportGate()
        val cycle = agreedCycle()
        val outcome = ForegroundReportOutcome(
            effectivePackageName = "com.ss.android.ugc.aweme",
            effectivePackageBlacklisted = true,
            diagnosticRecommendation = "LOCK_BY_DIAGNOSTIC_MAJORITY",
            businessAction = ForegroundBusinessAction.NO_LOCK,
            businessReason = "WEEKLY_PRIVILEGE_ACTIVE",
            allowanceState = "WEEKLY_PRIVILEGE_ACTIVE",
            guardPassed = true,
            visibilityPassed = null
        )
        assertEquals(
            ForegroundReportEmission.NONE,
            gate.evaluate(cycle, outcome, fixedNow).emission
        )
    }

    @Test
    fun freeTimeWithAllSourcesAgreeEmitsNothing() {
        val gate = ForegroundReportGate()
        val cycle = agreedCycle()
        val outcome = ForegroundReportOutcome(
            effectivePackageName = "com.ss.android.ugc.aweme",
            effectivePackageBlacklisted = true,
            diagnosticRecommendation = "LOCK_BY_DIAGNOSTIC_MAJORITY",
            businessAction = ForegroundBusinessAction.NO_LOCK,
            businessReason = "FREE_TIME_BYPASS",
            allowanceState = "FREE_TIME_ACTIVE",
            guardPassed = true,
            visibilityPassed = null
        )
        assertEquals(
            ForegroundReportEmission.NONE,
            gate.evaluate(cycle, outcome, fixedNow).emission
        )
    }

    @Test
    fun restOrAppWithAllSourcesAgreeEmitsNothing() {
        val gate = ForegroundReportGate()
        val cycle = agreedCycle()
        val outcome = ForegroundReportOutcome(
            effectivePackageName = "com.ss.android.ugc.aweme",
            effectivePackageBlacklisted = true,
            diagnosticRecommendation = "LOCK_BY_DIAGNOSTIC_MAJORITY",
            businessAction = ForegroundBusinessAction.NO_LOCK,
            businessReason = "REST_OR_APP_WINDOW_ALLOW",
            allowanceState = "ALLOW_OK",
            guardPassed = true,
            visibilityPassed = null
        )
        assertEquals(
            ForegroundReportEmission.NONE,
            gate.evaluate(cycle, outcome, fixedNow).emission
        )
    }

    @Test
    fun temporaryWithAllSourcesAgreeEmitsNothing() {
        val gate = ForegroundReportGate()
        val cycle = agreedCycle()
        val outcome = ForegroundReportOutcome(
            effectivePackageName = "com.ss.android.ugc.aweme",
            effectivePackageBlacklisted = true,
            diagnosticRecommendation = "LOCK_BY_DIAGNOSTIC_MAJORITY",
            businessAction = ForegroundBusinessAction.NO_LOCK,
            businessReason = "TEMPORARY_ACCESS_ACTIVE",
            allowanceState = "TEMP_ACCESS_ACTIVE",
            guardPassed = true,
            visibilityPassed = null
        )
        assertEquals(
            ForegroundReportEmission.NONE,
            gate.evaluate(cycle, outcome, fixedNow).emission
        )
    }

    @Test
    fun noForegroundResolvedAllAgreeEmitsNothing() {
        val gate = ForegroundReportGate()
        val cycle = noForegroundCycle()
        val outcome = ForegroundReportOutcome(
            effectivePackageName = null,
            effectivePackageBlacklisted = null,
            diagnosticRecommendation = "NO_DIAGNOSTIC_LOCK",
            businessAction = ForegroundBusinessAction.NO_LOCK,
            businessReason = "NO_FOREGROUND_RESOLVED",
            allowanceState = "UNKNOWN",
            guardPassed = null,
            visibilityPassed = null
        )
        assertEquals(
            ForegroundReportEmission.NONE,
            gate.evaluate(cycle, outcome, fixedNow).emission
        )
    }

    @Test
    fun onBlacklistHitNotStrictAllAgreeEmitsNothing() {
        val gate = ForegroundReportGate()
        val cycle = agreedCycle()
        val outcome = ForegroundReportOutcome(
            effectivePackageName = "com.ss.android.ugc.aweme",
            effectivePackageBlacklisted = true,
            diagnosticRecommendation = "LOCK_BY_DIAGNOSTIC_MAJORITY",
            businessAction = ForegroundBusinessAction.NO_LOCK,
            businessReason = "ON_BLACKLIST_HIT_NOT_STRICT",
            allowanceState = "ALLOW_OK",
            guardPassed = true,
            visibilityPassed = null
        )
        assertEquals(
            ForegroundReportEmission.NONE,
            gate.evaluate(cycle, outcome, fixedNow).emission
        )
    }

    @Test
    fun routineRequestLockDoesNotEmitTimeline() {
        val gate = ForegroundReportGate()
        // Routine REQUEST_LOCK on a non-faulty, non-conflict, blacklisted cycle.
        val cycle = newCycle(
            events = eventsSnapshot(packageName = "com.ss.android.ugc.aweme", vote = ForegroundVote.BLACKLIST),
            usageStats = usageStatsSnapshot(packageName = "com.ss.android.ugc.aweme", vote = ForegroundVote.BLACKLIST),
            accessibility = accessibilitySnapshot(packageName = "com.ss.android.ugc.aweme", vote = ForegroundVote.BLACKLIST)
        )
        val outcome = ForegroundReportOutcome(
            effectivePackageName = "com.ss.android.ugc.aweme",
            effectivePackageBlacklisted = true,
            diagnosticRecommendation = "LOCK_BY_DIAGNOSTIC_MAJORITY",
            businessAction = ForegroundBusinessAction.REQUEST_LOCK,
            businessReason = "BLACKLIST_DND_LOCKED",
            allowanceState = "ALLOW_NONE",
            guardPassed = true,
            visibilityPassed = true
        )
        val emission = gate.evaluate(cycle, outcome, fixedNow).emission
        assertEquals(ForegroundReportEmission.FULL, emission)
        val formatted = ForegroundReportFormatter.format(cycle, outcome, emission, fixedNow)
        val joined = formatted.joinToString("\n")
        assertFalse(
            "routine REQUEST_LOCK should NOT include timeline line, got: $joined",
            joined.contains("timeline(10s):")
        )
    }

    @Test
    fun faultReportEmitsTimeline() {
        val gate = ForegroundReportGate()
        // All sources FAILED → fault path must include timeline.
        val cycle = ForegroundDiagnosticCycle(
            reportId = "FG-20240801-120000-0200",
            checkedAtMs = fixedNow
        )
        cycle.updateUsageEvents(ForegroundFaultSnapshotFactory.eventsFailed("QUERY_EVENTS_FAILED", fixedNow))
        cycle.updateUsageStats(ForegroundFaultSnapshotFactory.usageStatsFailed("QUERY_USAGE_STATS_FAILED", fixedNow))
        cycle.updateAccessibility(
            ForegroundSourceSnapshot(
                source = "ACCESSIBILITY",
                status = ForegroundSourceStatus.AVAILABLE,
                packageName = null,
                vote = ForegroundVote.ABSTAIN,
                capturedAtMs = fixedNow,
                queryStartedAtMs = null,
                queryFinishedAtMs = fixedNow,
                candidates = emptyList(),
                selectionReason = null,
                unavailableReason = null,
                failureClass = null
            )
        )
        val outcome = ForegroundReportOutcome(
            effectivePackageName = null,
            effectivePackageBlacklisted = null,
            diagnosticRecommendation = "NO_DIAGNOSTIC_LOCK",
            businessAction = ForegroundBusinessAction.NO_LOCK,
            businessReason = "NO_FOREGROUND_RESOLVED",
            allowanceState = "UNKNOWN",
            guardPassed = null,
            visibilityPassed = null
        )
        val emission = gate.evaluate(cycle, outcome, fixedNow).emission
        assertEquals(ForegroundReportEmission.FULL, emission)
        val formatted = ForegroundReportFormatter.format(cycle, outcome, emission, fixedNow)
        val joined = formatted.joinToString("\n")
        assertTrue("fault report must include timeline: $joined", joined.contains("timeline(10s):"))
    }

    // ──────────────── R6 regression tests ────────────────
    // 1. Factory without explicit query timestamps must produce nulls, not fabricated values.
    @Test
    fun faultFactoryWithoutQueryHasNullQueryTimestamps() {
        val snap = ForegroundFaultSnapshotFactory.eventsFailed(
            failureClass = "USAGE_STATS_MANAGER_NULL",
            capturedAtMs = fixedNow
        )
        assertNull(
            "no query → queryStartedAtMs must be null, not capturedAtMs - 30s",
            snap.queryStartedAtMs
        )
        assertNull(
            "no query → queryFinishedAtMs must be null, not capturedAtMs",
            snap.queryFinishedAtMs
        )

        val snap2 = ForegroundFaultSnapshotFactory.usageStatsFailed(
            failureClass = "USAGE_STATS_MANAGER_NULL",
            capturedAtMs = fixedNow
        )
        assertNull(snap2.queryStartedAtMs)
        assertNull(snap2.queryFinishedAtMs)
    }

    // 2. Factory preserves real query timestamps when caller passes them.
    @Test
    fun faultFactoryPreservesRealQueryTimestamps() {
        val realStart = fixedNow - 1_500L
        val realEnd = fixedNow - 1_000L
        val snap = ForegroundFaultSnapshotFactory.eventsFailed(
            failureClass = "SecurityException",
            capturedAtMs = realEnd,
            queryStartedAtMs = realStart,
            queryFinishedAtMs = realEnd
        )
        assertEquals(realStart, snap.queryStartedAtMs)
        assertEquals(realEnd, snap.queryFinishedAtMs)
        assertEquals("SecurityException", snap.failureClass)
        assertEquals(ForegroundSourceStatus.FAILED, snap.status)
    }

    // 3. Accessibility status failure class is separate from window-scan failure class.
    // Behavior test: recordDiagnosticsWindowSnapshot(success=true) clears any prior
    // window failure class; recordDiagnosticsWindowSnapshot(success=false) records the
    // new failure class. These verify the real production wiring.
    @Test
    fun successfulWindowSnapshotClearsPreviousFailureClass() {
        val original = CoreService.diagnosticsLastWindowSnapshotFailureClass
        try {
            CoreService.diagnosticsLastWindowSnapshotFailureClass = "SecurityException"
            CoreService.recordDiagnosticsWindowSnapshot(success = true, failureClass = null)
            assertNull(
                "successful window scan must clear prior failure class",
                CoreService.diagnosticsLastWindowSnapshotFailureClass
            )
        } finally {
            CoreService.diagnosticsLastWindowSnapshotFailureClass = original
        }
    }

    @Test
    fun failedWindowSnapshotRecordsFailureClass() {
        val original = CoreService.diagnosticsLastWindowSnapshotFailureClass
        try {
            CoreService.recordDiagnosticsWindowSnapshot(
                success = false,
                failureClass = "SecurityException"
            )
            assertEquals(
                "SecurityException",
                CoreService.diagnosticsLastWindowSnapshotFailureClass
            )
        } finally {
            CoreService.diagnosticsLastWindowSnapshotFailureClass = original
        }
    }

    @Test
    fun windowSnapshotFailureClassDoesNotLeakIntoAccessibilityStatusFailureClass() {
        // Window-scan failure must not contaminate the status-check failure class,
        // since they are tracked independently in CoreService.
        val originalWindow = CoreService.diagnosticsLastWindowSnapshotFailureClass
        val originalStatus = CoreService.diagnosticsAccessibilityStatusFailureClass
        try {
            CoreService.diagnosticsAccessibilityStatusFailureClass = null
            CoreService.recordDiagnosticsWindowSnapshot(
                success = false,
                failureClass = "SecurityException"
            )
            assertEquals(
                "SecurityException",
                CoreService.diagnosticsLastWindowSnapshotFailureClass
            )
            assertNull(
                "window-scan failure must NOT leak into the status-check failure class",
                CoreService.diagnosticsAccessibilityStatusFailureClass
            )
        } finally {
            CoreService.diagnosticsLastWindowSnapshotFailureClass = originalWindow
            CoreService.diagnosticsAccessibilityStatusFailureClass = originalStatus
        }
    }

    // 4. resolveAccessibilityFailureClass: the actual production mapping logic.
    @Test
    fun accessibilityFailureUsesStatusCheckFailureClass() {
        assertEquals(
            "SecurityException",
            resolveAccessibilityFailureClass(
                ForegroundSourceStatus.FAILED,
                "SecurityException"
            )
        )
    }

    @Test
    fun accessibilityFailureDoesNotLeakIntoSuccessfulStatus() {
        // A stale status-check failure class must NOT appear in a successful snapshot.
        assertNull(
            resolveAccessibilityFailureClass(
                ForegroundSourceStatus.AVAILABLE,
                "OldFailure"
            )
        )
        assertNull(
            resolveAccessibilityFailureClass(
                ForegroundSourceStatus.AMBIGUOUS,
                "OldFailure"
            )
        )
        assertNull(
            resolveAccessibilityFailureClass(
                ForegroundSourceStatus.UNAVAILABLE,
                "OldFailure"
            )
        )
    }

    @Test
    fun failedAccessibilityWithoutSpecificExceptionUsesFallback() {
        assertEquals(
            "A11Y_STATUS_FAILED",
            resolveAccessibilityFailureClass(
                ForegroundSourceStatus.FAILED,
                null
            )
        )
    }

    // 4. FaultFactory stays pure: same args → same snapshot, no Android dependency.
    @Test
    fun faultFactoryIsPureAndDeterministic() {
        val a = ForegroundFaultSnapshotFactory.usageStatsFailed(
            failureClass = "X",
            capturedAtMs = 1_000L,
            queryStartedAtMs = 500L,
            queryFinishedAtMs = 999L
        )
        val b = ForegroundFaultSnapshotFactory.usageStatsFailed(
            failureClass = "X",
            capturedAtMs = 1_000L,
            queryStartedAtMs = 500L,
            queryFinishedAtMs = 999L
        )
        assertEquals(a.status, b.status)
        assertEquals(a.failureClass, b.failureClass)
        assertEquals(a.queryStartedAtMs, b.queryStartedAtMs)
        assertEquals(a.queryFinishedAtMs, b.queryFinishedAtMs)
        assertEquals(a.capturedAtMs, b.capturedAtMs)
    }

    // ──────────────── V2-R2 §8 Task 1 Step 4: assessment RED tests ────────────────
    // These tests reference ForegroundReportAssessmentFactory / new enums + data
    // class that DO NOT EXIST in production code yet. Compilation must fail.
    // Each test pins one classification rule from V2-R2 §4.2 (disposition) or
    // §4.1 (source relation) or §4.3 (stable signature).

    @Test
    fun requestLockClassifiesAsRequestedLock() {
        val cycle = newCycle()
        val outcome = ForegroundReportOutcome(
            effectivePackageName = "com.ss.android.ugc.aweme",
            effectivePackageBlacklisted = true,
            diagnosticRecommendation = "LOCK_BY_DIAGNOSTIC_MAJORITY",
            businessAction = ForegroundBusinessAction.REQUEST_LOCK,
            businessReason = "BLACKLIST_DND_LOCKED",
            allowanceState = "ALLOW_NONE",
            guardPassed = true,
            visibilityPassed = true
        )
        val a = ForegroundReportAssessmentFactory.assess(cycle, outcome)
        assertEquals(ForegroundEnforcementDisposition.REQUESTED_LOCK, a.enforcementDisposition)
    }

    @Test
    fun blockedAppLockRequestClassifiesAsRequestedLock() {
        // Q4: BLOCKED_APP_LOCK + action REQUEST_LOCK → REQUESTED_LOCK regardless of
        // effectivePackageBlacklisted == false.
        val cycle = newCycle(
            events = eventsSnapshot(packageName = "com.bbk.launcher2", vote = ForegroundVote.NON_BLACKLIST),
            usageStats = usageStatsSnapshot(packageName = "com.bbk.launcher2", vote = ForegroundVote.NON_BLACKLIST),
            accessibility = accessibilitySnapshot(packageName = "com.bbk.launcher2", vote = ForegroundVote.NON_BLACKLIST)
        )
        val outcome = ForegroundReportOutcome(
            effectivePackageName = "com.bbk.launcher2",
            effectivePackageBlacklisted = false,
            diagnosticRecommendation = "NO_DIAGNOSTIC_LOCK",
            businessAction = ForegroundBusinessAction.REQUEST_LOCK,
            businessReason = "BLOCKED_APP_LOCK",
            allowanceState = "ALLOW_NONE",
            guardPassed = true,
            visibilityPassed = true
        )
        val a = ForegroundReportAssessmentFactory.assess(cycle, outcome)
        assertEquals(ForegroundEnforcementDisposition.REQUESTED_LOCK, a.enforcementDisposition)
    }

    @Test
    fun lockAlreadyAttachedClassifiesAsAlreadyEnforced() {
        val cycle = agreedCycle()
        val outcome = ForegroundReportOutcome(
            effectivePackageName = "com.ss.android.ugc.aweme",
            effectivePackageBlacklisted = true,
            diagnosticRecommendation = "LOCK_BY_DIAGNOSTIC_MAJORITY",
            businessAction = ForegroundBusinessAction.NO_LOCK,
            businessReason = "LOCK_ALREADY_ATTACHED",
            allowanceState = "LOCK_ATTACHED",
            guardPassed = true,
            visibilityPassed = true
        )
        val a = ForegroundReportAssessmentFactory.assess(cycle, outcome)
        assertEquals(ForegroundEnforcementDisposition.ALREADY_ENFORCED, a.enforcementDisposition)
    }

    @Test
    fun bypassVisibilityRejectedClassifiesAsLegitimateBypass() {
        val cycle = agreedCycle()
        val outcome = ForegroundReportOutcome(
            effectivePackageName = "com.ss.android.ugc.aweme",
            effectivePackageBlacklisted = true,
            diagnosticRecommendation = "LOCK_BY_DIAGNOSTIC_MAJORITY",
            businessAction = ForegroundBusinessAction.NO_LOCK,
            businessReason = "BYPASS_VISIBILITY_REJECTED",
            allowanceState = "ALLOW_NONE",
            guardPassed = true,
            visibilityPassed = false
        )
        val a = ForegroundReportAssessmentFactory.assess(cycle, outcome)
        assertEquals(ForegroundEnforcementDisposition.LEGITIMATE_BYPASS, a.enforcementDisposition)
    }

    @Test
    fun noForegroundResolvedClassifiesAsNotRequired() {
        val cycle = noForegroundCycle()
        val outcome = ForegroundReportOutcome(
            effectivePackageName = null,
            effectivePackageBlacklisted = null,
            diagnosticRecommendation = "NO_DIAGNOSTIC_LOCK",
            businessAction = ForegroundBusinessAction.NO_LOCK,
            businessReason = "NO_FOREGROUND_RESOLVED",
            allowanceState = "UNKNOWN",
            guardPassed = null,
            visibilityPassed = null
        )
        val a = ForegroundReportAssessmentFactory.assess(cycle, outcome)
        assertEquals(ForegroundEnforcementDisposition.NOT_REQUIRED, a.enforcementDisposition)
    }

    @Test
    fun onBlacklistHitNotStrictClassifiesAsNotRequired() {
        // Q2: ON_BLACKLIST_HIT_NOT_STRICT is an explicit business policy
        // decision (not a missed lock). NOT_REQUIRED, NOT UNEXPLAINED_MISSED_LOCK.
        val cycle = agreedCycle()
        val outcome = ForegroundReportOutcome(
            effectivePackageName = "com.ss.android.ugc.aweme",
            effectivePackageBlacklisted = true,
            diagnosticRecommendation = "LOCK_BY_DIAGNOSTIC_MAJORITY",
            businessAction = ForegroundBusinessAction.NO_LOCK,
            businessReason = "ON_BLACKLIST_HIT_NOT_STRICT",
            allowanceState = "ALLOW_OK",
            guardPassed = true,
            visibilityPassed = null
        )
        val a = ForegroundReportAssessmentFactory.assess(cycle, outcome)
        assertEquals(ForegroundEnforcementDisposition.NOT_REQUIRED, a.enforcementDisposition)
    }

    @Test
    fun unrecognizedNoLockReasonAfterLockRecommendationClassifiesAsUnexplainedMissedLock() {
        // R1-3 strict conditions: businessAction=NO_LOCK,
        // effectivePackageBlacklisted=true, recommendation=LOCK_*,
        // businessReason not in any known bypass set, allowanceIsInactive,
        // guardPassed=true, visibilityPassed=true. Use businessReason =
        // "INTERNAL_DIAG_ONLY" which is not in any of the three sets.
        val cycle = agreedCycle()
        val outcome = ForegroundReportOutcome(
            effectivePackageName = "com.ss.android.ugc.aweme",
            effectivePackageBlacklisted = true,
            diagnosticRecommendation = "LOCK_BY_DIAGNOSTIC_MAJORITY",
            businessAction = ForegroundBusinessAction.NO_LOCK,
            businessReason = "INTERNAL_DIAG_ONLY",
            allowanceState = "ALLOW_NONE",
            guardPassed = true,
            visibilityPassed = true
        )
        val a = ForegroundReportAssessmentFactory.assess(cycle, outcome)
        assertEquals(ForegroundEnforcementDisposition.UNEXPLAINED_MISSED_LOCK, a.enforcementDisposition)
    }

    @Test
    fun notDecidedNeverClassifiesAsMissedLock() {
        // NOT_DECIDED is a probe state used by V1 conflict tests; it must NOT
        // ever surface as UNEXPLAINED_MISSED_LOCK regardless of recommendation.
        val cycle = newCycle(
            events = eventsSnapshot(packageName = "com.ss.android.ugc.aweme"),
            usageStats = usageStatsSnapshot(packageName = "com.android.launcher"),
            accessibility = accessibilitySnapshot(packageName = "com.ss.android.ugc.aweme")
        )
        val outcome = ForegroundReportOutcome(
            effectivePackageName = "com.ss.android.ugc.aweme",
            effectivePackageBlacklisted = true,
            diagnosticRecommendation = "LOCK_BY_DIAGNOSTIC_MAJORITY",
            businessAction = ForegroundBusinessAction.NOT_DECIDED,
            businessReason = "INTERNAL_DIAG_ONLY",
            allowanceState = "ALLOW_NONE",
            guardPassed = null,
            visibilityPassed = null
        )
        val a = ForegroundReportAssessmentFactory.assess(cycle, outcome)
        assertEquals(ForegroundEnforcementDisposition.NOT_REQUIRED, a.enforcementDisposition)
    }

    @Test
    fun allUnavailableIsFaultAndNotBusinessMissedLock() {
        // Three sources UNAVAILABLE → sourceRelation=ALL_UNAVAILABLE; business
        // action=NO_LOCK with reason=NO_FOREGROUND_RESOLVED must still classify
        // as NOT_REQUIRED on the business side (we never asked to lock).
        val cycle = newCycle(
            events = eventsSnapshot(packageName = null, status = ForegroundSourceStatus.UNAVAILABLE),
            usageStats = usageStatsSnapshot(packageName = null, status = ForegroundSourceStatus.UNAVAILABLE),
            accessibility = accessibilitySnapshot(packageName = null, status = ForegroundSourceStatus.UNAVAILABLE)
        )
        val outcome = ForegroundReportOutcome(
            effectivePackageName = null,
            effectivePackageBlacklisted = null,
            diagnosticRecommendation = "NO_DIAGNOSTIC_LOCK",
            businessAction = ForegroundBusinessAction.NO_LOCK,
            businessReason = "NO_FOREGROUND_RESOLVED",
            allowanceState = "UNKNOWN",
            guardPassed = null,
            visibilityPassed = null
        )
        val a = ForegroundReportAssessmentFactory.assess(cycle, outcome)
        assertEquals(ForegroundSourceRelation.ALL_UNAVAILABLE, a.sourceRelation)
        assertTrue("source fault must be flagged", a.hasSourceFault)
        assertEquals(ForegroundEnforcementDisposition.NOT_REQUIRED, a.enforcementDisposition)
    }

    @Test
    fun degradedAndDisagreementCanCoexist() {
        // One source FAILED, the other two disagree → sourceRelation must be
        // DEGRADED (because at least one is FAILED/UNAVAILABLE), not just
        // DISAGREEMENT. The disagreeing sources must still be detected as a
        // dispute.
        val cycle = newCycle(
            events = eventsSnapshot(packageName = null, status = ForegroundSourceStatus.FAILED),
            usageStats = usageStatsSnapshot(packageName = "com.android.launcher", vote = ForegroundVote.NON_BLACKLIST),
            accessibility = accessibilitySnapshot(packageName = "com.ss.android.ugc.aweme", vote = ForegroundVote.BLACKLIST)
        )
        val outcome = ForegroundReportOutcome(
            effectivePackageName = null,
            effectivePackageBlacklisted = null,
            diagnosticRecommendation = "NO_DIAGNOSTIC_LOCK",
            businessAction = ForegroundBusinessAction.NO_LOCK,
            businessReason = "EVENTS_QUERY_FAILED",
            allowanceState = "UNKNOWN",
            guardPassed = null,
            visibilityPassed = null
        )
        val a = ForegroundReportAssessmentFactory.assess(cycle, outcome)
        assertEquals(ForegroundSourceRelation.DEGRADED, a.sourceRelation)
        assertTrue("dispute present alongside degradation", a.hasSourceDispute)
        assertTrue("degradation is a fault", a.hasSourceFault)
    }

    @Test
    fun allAgreeHasNoSourceProblem() {
        val cycle = agreedCycle()
        val outcome = ForegroundReportOutcome(
            effectivePackageName = "com.ss.android.ugc.aweme",
            effectivePackageBlacklisted = true,
            diagnosticRecommendation = "LOCK_BY_DIAGNOSTIC_MAJORITY",
            businessAction = ForegroundBusinessAction.REQUEST_LOCK,
            businessReason = "BLACKLIST_DND_LOCKED",
            allowanceState = "ALLOW_NONE",
            guardPassed = true,
            visibilityPassed = true
        )
        val a = ForegroundReportAssessmentFactory.assess(cycle, outcome)
        assertEquals(ForegroundSourceRelation.ALL_AGREE, a.sourceRelation)
        assertFalse("all-agree has no dispute", a.hasSourceDispute)
        assertFalse("all-agree has no fault", a.hasSourceFault)
    }

    @Test
    fun dynamicFieldsDoNotChangeStableSignature() {
        // The stable signature must NOT include capturedAtMs, queryStartedAtMs,
        // queryFinishedAtMs (per V2-R2 §4.3). Two cycles differing only in these
        // dynamic timestamps must produce the same stableSignature.
        val outcome = ForegroundReportOutcome(
            effectivePackageName = "com.ss.android.ugc.aweme",
            effectivePackageBlacklisted = true,
            diagnosticRecommendation = "LOCK_BY_DIAGNOSTIC_MAJORITY",
            businessAction = ForegroundBusinessAction.REQUEST_LOCK,
            businessReason = "BLACKLIST_DND_LOCKED",
            allowanceState = "ALLOW_NONE",
            guardPassed = true,
            visibilityPassed = true
        )
        val baseEvents = eventsSnapshot(
            packageName = "com.ss.android.ugc.aweme",
            vote = ForegroundVote.BLACKLIST
        ).copy(capturedAtMs = fixedNow, queryStartedAtMs = fixedNow - 5L, queryFinishedAtMs = fixedNow - 4L)
        val eventsT1 = baseEvents.copy(capturedAtMs = fixedNow + 1_000L, queryStartedAtMs = fixedNow + 995L, queryFinishedAtMs = fixedNow + 996L)
        val cycle1 = ForegroundDiagnosticCycle(reportId = "FG-A", checkedAtMs = fixedNow)
        cycle1.updateUsageEvents(baseEvents)
        cycle1.updateUsageStats(usageStatsSnapshot(packageName = "com.ss.android.ugc.aweme", vote = ForegroundVote.BLACKLIST))
        cycle1.updateAccessibility(accessibilitySnapshot(packageName = "com.ss.android.ugc.aweme", vote = ForegroundVote.BLACKLIST))
        val cycle2 = ForegroundDiagnosticCycle(reportId = "FG-A", checkedAtMs = fixedNow)
        cycle2.updateUsageEvents(eventsT1)
        cycle2.updateUsageStats(usageStatsSnapshot(packageName = "com.ss.android.ugc.aweme", vote = ForegroundVote.BLACKLIST))
        cycle2.updateAccessibility(accessibilitySnapshot(packageName = "com.ss.android.ugc.aweme", vote = ForegroundVote.BLACKLIST))
        val a1 = ForegroundReportAssessmentFactory.assess(cycle1, outcome)
        val a2 = ForegroundReportAssessmentFactory.assess(cycle2, outcome)
        assertEquals(
            "stableSignature must not change when only dynamic timestamps differ",
            a1.stableSignature, a2.stableSignature
        )
    }

    @Test
    fun stableFieldChangesDoChangeStableSignature() {
        // A change to the stable fields (effectivePackageName, businessReason,
        // etc.) MUST change the stable signature.
        val cycle1 = agreedCycle()
        val cycle2 = agreedCycle()
        val outcome1 = ForegroundReportOutcome(
            effectivePackageName = "com.ss.android.ugc.aweme",
            effectivePackageBlacklisted = true,
            diagnosticRecommendation = "LOCK_BY_DIAGNOSTIC_MAJORITY",
            businessAction = ForegroundBusinessAction.REQUEST_LOCK,
            businessReason = "BLACKLIST_DND_LOCKED",
            allowanceState = "ALLOW_NONE",
            guardPassed = true,
            visibilityPassed = true
        )
        val outcome2 = outcome1.copy(businessReason = "BLOCKED_APP_LOCK")
        val a1 = ForegroundReportAssessmentFactory.assess(cycle1, outcome1)
        val a2 = ForegroundReportAssessmentFactory.assess(cycle2, outcome2)
        assertTrue(
            "businessReason change must change stableSignature: '${a1.stableSignature}' vs '${a2.stableSignature}'",
            a1.stableSignature != a2.stableSignature
        )
    }

    // ──────────────── V2-R2 §8 Task 2 Step 1: Gate RED tests ────────────────
    // Each test asserts on the new ForegroundReportDecision shape (emission,
    // triggers, assessment, sourceProblemStartedAtMs). Tests fail RED because:
    //   1. ForegroundReportDecision type does not exist (compile error)
    //   2. evaluate() still returns ForegroundReportEmission, not Decision
    //   3. The state machine in evaluate() does not compute triggers yet.
    //
    // After Step 3+ (Decision type + new state machine) these become GREEN.

    private fun requestLockOutcome(
        packageName: String = "com.ss.android.ugc.aweme",
        blacklisted: Boolean = true,
        recommendation: String = "LOCK_BY_DIAGNOSTIC_MAJORITY",
        reason: String = "BLACKLIST_DND_LOCKED"
    ) = ForegroundReportOutcome(
        effectivePackageName = packageName,
        effectivePackageBlacklisted = blacklisted,
        diagnosticRecommendation = recommendation,
        businessAction = ForegroundBusinessAction.REQUEST_LOCK,
        businessReason = reason,
        allowanceState = "ALLOW_NONE",
        guardPassed = true,
        visibilityPassed = true
    )

    @Test
    fun requestLockAlwaysEmitsFullDecision() {
        val gate = ForegroundReportGate()
        val cycle = agreedCycle()
        val decision = gate.evaluate(cycle, requestLockOutcome(), fixedNow)
        assertEquals(ForegroundReportEmission.FULL, decision.emission)
        assertEquals(listOf(ForegroundReportTrigger.REQUEST_LOCK), decision.triggers)
    }

    @Test
    fun identicalRequestLockStillEmitsFullDecision() {
        val gate = ForegroundReportGate()
        val cycle = agreedCycle()
        gate.evaluate(cycle, requestLockOutcome(), fixedNow)
        val decision2 = gate.evaluate(cycle, requestLockOutcome(), fixedNow + 5_000L)
        assertEquals(ForegroundReportEmission.FULL, decision2.emission)
        assertEquals(listOf(ForegroundReportTrigger.REQUEST_LOCK), decision2.triggers)
    }

    @Test
    fun unexplainedMissedLockFirstSceneEmitsFull() {
        val gate = ForegroundReportGate()
        val cycle = agreedCycle()
        // businessReason "INTERNAL_DIAG_ONLY" is not in any of the three disposition
        // reason sets → UNEXPLAINED_MISSED_LOCK (per V2-R2 §4.2 + R1-3 strict 7 conditions).
        val outcome = ForegroundReportOutcome(
            effectivePackageName = "com.ss.android.ugc.aweme",
            effectivePackageBlacklisted = true,
            diagnosticRecommendation = "LOCK_BY_DIAGNOSTIC_MAJORITY",
            businessAction = ForegroundBusinessAction.NO_LOCK,
            businessReason = "INTERNAL_DIAG_ONLY",
            allowanceState = "ALLOW_NONE",
            guardPassed = true,
            visibilityPassed = true
        )
        val decision = gate.evaluate(cycle, outcome, fixedNow)
        assertEquals(ForegroundReportEmission.FULL, decision.emission)
        assertEquals(listOf(ForegroundReportTrigger.MISSED_LOCK_STARTED), decision.triggers)
    }

    @Test
    fun identicalUnexplainedMissedLockIsSuppressed() {
        val gate = ForegroundReportGate()
        val cycle = agreedCycle()
        val outcome = ForegroundReportOutcome(
            effectivePackageName = "com.ss.android.ugc.aweme",
            effectivePackageBlacklisted = true,
            diagnosticRecommendation = "LOCK_BY_DIAGNOSTIC_MAJORITY",
            businessAction = ForegroundBusinessAction.NO_LOCK,
            businessReason = "INTERNAL_DIAG_ONLY",
            allowanceState = "ALLOW_NONE",
            guardPassed = true,
            visibilityPassed = true
        )
        val first = gate.evaluate(cycle, outcome, fixedNow)
        assertEquals(ForegroundReportEmission.FULL, first.emission)

        val second = gate.evaluate(cycle, outcome, fixedNow + 5_000L)
        assertEquals(ForegroundReportEmission.NONE, second.emission)
        assertTrue("identical missed-lock must NOT trigger", second.triggers.isEmpty())
    }

    @Test
    fun unexplainedMissedLockStableFieldChangeEmitsFull() {
        val gate = ForegroundReportGate()
        val cycle = agreedCycle()
        val outcome = ForegroundReportOutcome(
            effectivePackageName = "com.ss.android.ugc.aweme",
            effectivePackageBlacklisted = true,
            diagnosticRecommendation = "LOCK_BY_DIAGNOSTIC_MAJORITY",
            businessAction = ForegroundBusinessAction.NO_LOCK,
            businessReason = "INTERNAL_DIAG_ONLY",
            allowanceState = "ALLOW_NONE",
            guardPassed = true,
            visibilityPassed = true
        )
        gate.evaluate(cycle, outcome, fixedNow)

        // Stable signature changes: businessReason flips to a different
        // unmapped literal. Per V2-R2 §8 Task 2 Step 5, the missed-lock state
        // must emit MISSED_LOCK_CHANGED (not STARTED) for the new signature.
        // allowance stays INACTIVE (ALLOW_NONE) so R1-3 strict 7 conditions
        // still hold.
        val outcomeChanged = outcome.copy(businessReason = "DIAG_INTERNAL_VARIANT_B")
        val second = gate.evaluate(cycle, outcomeChanged, fixedNow + 5_000L)
        assertEquals(ForegroundReportEmission.FULL, second.emission)
        assertEquals(listOf(ForegroundReportTrigger.MISSED_LOCK_CHANGED), second.triggers)
    }

    @Test
    fun sourceDisputeFirstSceneThenThirtySecondSummary() {
        val gate = ForegroundReportGate()
        // Package disagreement across sources → sourceDispute=true.
        val cycle1 = newCycle(
            events = eventsSnapshot(packageName = "com.ss.android.ugc.aweme", vote = ForegroundVote.BLACKLIST),
            usageStats = usageStatsSnapshot(packageName = "com.android.launcher", vote = ForegroundVote.NON_BLACKLIST),
            accessibility = accessibilitySnapshot(packageName = "com.ss.android.ugc.aweme", vote = ForegroundVote.BLACKLIST)
        )
        val outcome = ForegroundReportOutcome(
            effectivePackageName = "com.ss.android.ugc.aweme",
            effectivePackageBlacklisted = true,
            diagnosticRecommendation = "LOCK_BY_DIAGNOSTIC_MAJORITY",
            businessAction = ForegroundBusinessAction.NO_LOCK,
            businessReason = "INTERNAL_DIAG_ONLY",
            allowanceState = "ALLOW_NONE",
            guardPassed = null,
            visibilityPassed = null
        )
        val first = gate.evaluate(cycle1, outcome, fixedNow)
        assertEquals(ForegroundReportEmission.FULL, first.emission)
        assertEquals(listOf(ForegroundReportTrigger.SOURCE_DISPUTE_STARTED), first.triggers)

        // Identical dispute 5s later → NONE (within 30s window).
        val cycle2 = newCycle(
            events = eventsSnapshot(packageName = "com.ss.android.ugc.aweme", vote = ForegroundVote.BLACKLIST),
            usageStats = usageStatsSnapshot(packageName = "com.android.launcher", vote = ForegroundVote.NON_BLACKLIST),
            accessibility = accessibilitySnapshot(packageName = "com.ss.android.ugc.aweme", vote = ForegroundVote.BLACKLIST)
        )
        val middle = gate.evaluate(cycle2, outcome, fixedNow + 5_000L)
        assertEquals(ForegroundReportEmission.NONE, middle.emission)
        assertTrue("continuation within 30s should not trigger", middle.triggers.isEmpty())

        // 30s after first emission → CONTINUING_CONFLICT_SUMMARY.
        val cycle3 = newCycle(
            events = eventsSnapshot(packageName = "com.ss.android.ugc.aweme", vote = ForegroundVote.BLACKLIST),
            usageStats = usageStatsSnapshot(packageName = "com.android.launcher", vote = ForegroundVote.NON_BLACKLIST),
            accessibility = accessibilitySnapshot(packageName = "com.ss.android.ugc.aweme", vote = ForegroundVote.BLACKLIST)
        )
        val atThirty = gate.evaluate(cycle3, outcome, fixedNow + 30_000L)
        assertEquals(ForegroundReportEmission.CONTINUING_CONFLICT_SUMMARY, atThirty.emission)
        assertTrue("30s summary carries no new trigger", atThirty.triggers.isEmpty())
    }

    @Test
    fun sourceFaultFirstSceneThenThirtySecondSummary() {
        val gate = ForegroundReportGate()
        // All sources FAILED → sourceFault=true, sourceDispute=false.
        val cycle1 = ForegroundDiagnosticCycle(
            reportId = "FG-20240801-120000-0300",
            checkedAtMs = fixedNow
        )
        cycle1.updateUsageEvents(ForegroundFaultSnapshotFactory.eventsFailed("QUERY_EVENTS_FAILED", fixedNow))
        cycle1.updateUsageStats(ForegroundFaultSnapshotFactory.usageStatsFailed("QUERY_USAGE_STATS_FAILED", fixedNow))
        cycle1.updateAccessibility(
            ForegroundSourceSnapshot(
                source = "ACCESSIBILITY",
                status = ForegroundSourceStatus.FAILED,
                packageName = null,
                vote = ForegroundVote.ABSTAIN,
                capturedAtMs = fixedNow,
                queryStartedAtMs = fixedNow - 1L,
                queryFinishedAtMs = fixedNow,
                candidates = emptyList(),
                selectionReason = null,
                unavailableReason = null,
                failureClass = "A11Y_STATUS_FAILED"
            )
        )
        val outcome = ForegroundReportOutcome(
            effectivePackageName = null,
            effectivePackageBlacklisted = null,
            diagnosticRecommendation = "NO_DIAGNOSTIC_LOCK",
            businessAction = ForegroundBusinessAction.NO_LOCK,
            businessReason = "NO_FOREGROUND_RESOLVED",
            allowanceState = "UNKNOWN",
            guardPassed = null,
            visibilityPassed = null
        )
        val first = gate.evaluate(cycle1, outcome, fixedNow)
        assertEquals(ForegroundReportEmission.FULL, first.emission)
        assertEquals(listOf(ForegroundReportTrigger.SOURCE_FAULT_STARTED), first.triggers)

        // Identical fault 5s later → NONE.
        val cycle2 = cycle1
        val middle = gate.evaluate(cycle2, outcome, fixedNow + 5_000L)
        assertEquals(ForegroundReportEmission.NONE, middle.emission)
        assertTrue(middle.triggers.isEmpty())

        // 30s after first emission → CONTINUING_CONFLICT_SUMMARY.
        val cycle3 = cycle1
        val atThirty = gate.evaluate(cycle3, outcome, fixedNow + 30_000L)
        assertEquals(ForegroundReportEmission.CONTINUING_CONFLICT_SUMMARY, atThirty.emission)
        assertTrue(atThirty.triggers.isEmpty())
    }

    @Test
    fun sourceProblemChangeResetsSummaryClock() {
        val gate = ForegroundReportGate()
        val outcome = ForegroundReportOutcome(
            effectivePackageName = "com.ss.android.ugc.aweme",
            effectivePackageBlacklisted = true,
            diagnosticRecommendation = "LOCK_BY_DIAGNOSTIC_MAJORITY",
            businessAction = ForegroundBusinessAction.NO_LOCK,
            businessReason = "INTERNAL_DIAG_ONLY",
            allowanceState = "ALLOW_NONE",
            guardPassed = null,
            visibilityPassed = null
        )
        // Round 1: package disagreement → dispute.
        val cycle1 = newCycle(
            events = eventsSnapshot(packageName = "com.ss.android.ugc.aweme", vote = ForegroundVote.BLACKLIST),
            usageStats = usageStatsSnapshot(packageName = "com.android.launcher", vote = ForegroundVote.NON_BLACKLIST),
            accessibility = accessibilitySnapshot(packageName = "com.ss.android.ugc.aweme", vote = ForegroundVote.BLACKLIST)
        )
        val first = gate.evaluate(cycle1, outcome, fixedNow)
        assertEquals(ForegroundReportEmission.FULL, first.emission)
        assertEquals(listOf(ForegroundReportTrigger.SOURCE_DISPUTE_STARTED), first.triggers)

        // Round 2: dispute changes (different disagreement) 5s later.
        val cycle2 = newCycle(
            events = eventsSnapshot(packageName = "com.android.launcher", vote = ForegroundVote.NON_BLACKLIST),
            usageStats = usageStatsSnapshot(packageName = "com.android.launcher", vote = ForegroundVote.NON_BLACKLIST),
            accessibility = accessibilitySnapshot(packageName = "com.ss.android.ugc.aweme", vote = ForegroundVote.BLACKLIST)
        )
        val changed = gate.evaluate(cycle2, outcome, fixedNow + 5_000L)
        assertEquals(ForegroundReportEmission.FULL, changed.emission)
        assertEquals(listOf(ForegroundReportTrigger.SOURCE_DISPUTE_CHANGED), changed.triggers)

        // Round 3: identical again 5s later — clock was reset, so still NONE.
        val cycle3 = newCycle(
            events = eventsSnapshot(packageName = "com.android.launcher", vote = ForegroundVote.NON_BLACKLIST),
            usageStats = usageStatsSnapshot(packageName = "com.android.launcher", vote = ForegroundVote.NON_BLACKLIST),
            accessibility = accessibilitySnapshot(packageName = "com.ss.android.ugc.aweme", vote = ForegroundVote.BLACKLIST)
        )
        val afterChange = gate.evaluate(cycle3, outcome, fixedNow + 10_000L)
        assertEquals(ForegroundReportEmission.NONE, afterChange.emission)
        assertTrue(afterChange.triggers.isEmpty())
    }

    @Test
    fun sourceRecoveryEmitsOneFullDecision() {
        val gate = ForegroundReportGate()
        val outcome = ForegroundReportOutcome(
            effectivePackageName = "com.ss.android.ugc.aweme",
            effectivePackageBlacklisted = true,
            diagnosticRecommendation = "LOCK_BY_DIAGNOSTIC_MAJORITY",
            businessAction = ForegroundBusinessAction.NO_LOCK,
            businessReason = "INTERNAL_DIAG_ONLY",
            allowanceState = "ALLOW_NONE",
            guardPassed = true,
            visibilityPassed = true
        )
        // Round 1: dispute.
        val cycle1 = newCycle(
            events = eventsSnapshot(packageName = "com.ss.android.ugc.aweme", vote = ForegroundVote.BLACKLIST),
            usageStats = usageStatsSnapshot(packageName = "com.android.launcher", vote = ForegroundVote.NON_BLACKLIST),
            accessibility = accessibilitySnapshot(packageName = "com.ss.android.ugc.aweme", vote = ForegroundVote.BLACKLIST)
        )
        gate.evaluate(cycle1, outcome, fixedNow)

        // Round 2: dispute resolves (sources agree). Sources recover AND business
        // still misses the lock → MISSED_LOCK_CHANGED + SOURCE_RECOVERED co-exist
        // in one decision (per V2-R2 §5.1 fixed order, R2-3 option A: lastMissedLockSignature
        // is tracked independently of source problem state, so the recovery cycle
        // sees the previously-recorded missed-lock signature and fires CHANGED).
        val cycle2 = agreedCycle()
        val recovery = gate.evaluate(cycle2, outcome, fixedNow + 5_000L)
        assertEquals(ForegroundReportEmission.FULL, recovery.emission)
        assertEquals(
            listOf(
                ForegroundReportTrigger.MISSED_LOCK_CHANGED,
                ForegroundReportTrigger.SOURCE_RECOVERED
            ),
            recovery.triggers
        )
    }

    @Test
    fun requestLockAndSourceRecoveryProduceOneFullDecision() {
        val gate = ForegroundReportGate()
        val outcome = ForegroundReportOutcome(
            effectivePackageName = "com.ss.android.ugc.aweme",
            effectivePackageBlacklisted = true,
            diagnosticRecommendation = "LOCK_BY_DIAGNOSTIC_MAJORITY",
            businessAction = ForegroundBusinessAction.NO_LOCK,
            businessReason = "INTERNAL_DIAG_ONLY",
            allowanceState = "ALLOW_NONE",
            guardPassed = null,
            visibilityPassed = null
        )
        // Round 1: dispute.
        val cycle1 = newCycle(
            events = eventsSnapshot(packageName = "com.ss.android.ugc.aweme", vote = ForegroundVote.BLACKLIST),
            usageStats = usageStatsSnapshot(packageName = "com.android.launcher", vote = ForegroundVote.NON_BLACKLIST),
            accessibility = accessibilitySnapshot(packageName = "com.ss.android.ugc.aweme", vote = ForegroundVote.BLACKLIST)
        )
        gate.evaluate(cycle1, outcome, fixedNow)

        // Round 2: REQUEST_LOCK with all sources now agreeing → recovery + request-lock
        // in ONE decision.
        val cycle2 = agreedCycle()
        val decision = gate.evaluate(cycle2, requestLockOutcome(), fixedNow + 5_000L)
        assertEquals(ForegroundReportEmission.FULL, decision.emission)
        assertEquals(
            listOf(
                ForegroundReportTrigger.REQUEST_LOCK,
                ForegroundReportTrigger.SOURCE_RECOVERED
            ),
            decision.triggers
        )
    }

    @Test
    fun missedLockAndSourceRecoveryProduceOneFullDecision() {
        val gate = ForegroundReportGate()
        val outcome = ForegroundReportOutcome(
            effectivePackageName = "com.ss.android.ugc.aweme",
            effectivePackageBlacklisted = true,
            diagnosticRecommendation = "LOCK_BY_DIAGNOSTIC_MAJORITY",
            businessAction = ForegroundBusinessAction.NO_LOCK,
            businessReason = "INTERNAL_DIAG_ONLY",
            allowanceState = "ALLOW_NONE",
            guardPassed = true,
            visibilityPassed = true
        )
        // Round 1: dispute.
        val cycle1 = newCycle(
            events = eventsSnapshot(packageName = "com.ss.android.ugc.aweme", vote = ForegroundVote.BLACKLIST),
            usageStats = usageStatsSnapshot(packageName = "com.android.launcher", vote = ForegroundVote.NON_BLACKLIST),
            accessibility = accessibilitySnapshot(packageName = "com.ss.android.ugc.aweme", vote = ForegroundVote.BLACKLIST)
        )
        gate.evaluate(cycle1, outcome, fixedNow)

        // Round 2: sources agree but business still misses the lock. R2-3 option A
        // (V2-R2 §8 Task 2 Step 5): lastMissedLockSignature is tracked INDEPENDENTLY
        // of the source problem state. Round 1's source-problem-active missed lock
        // was held in abeyance (signature not stamped), so on recovery the missed
        // lock fires CHANGED (signature differs from null). Fixed V2-R2 §5.1
        // order: [MISSED_LOCK_CHANGED, SOURCE_RECOVERED].
        val cycle2 = agreedCycle()
        val decision = gate.evaluate(cycle2, outcome, fixedNow + 5_000L)
        assertEquals(ForegroundReportEmission.FULL, decision.emission)
        assertEquals(
            listOf(
                ForegroundReportTrigger.MISSED_LOCK_CHANGED,
                ForegroundReportTrigger.SOURCE_RECOVERED
            ),
            decision.triggers
        )
    }

    @Test
    fun legitimateBypassWithSourceDisputeReportsOnlySourceTrigger() {
        val gate = ForegroundReportGate()
        // 3 sources disagree → sourceDispute=true; business reason = PWA_BYPASS
        // → LEGITIMATE_BYPASS. Only SOURCE_DISPUTE_STARTED should fire (no missed-lock,
        // no recovery) because disposition is a known legitimate bypass.
        val cycle = newCycle(
            events = eventsSnapshot(packageName = "com.ss.android.ugc.aweme", vote = ForegroundVote.BLACKLIST),
            usageStats = usageStatsSnapshot(packageName = "com.android.launcher", vote = ForegroundVote.NON_BLACKLIST),
            accessibility = accessibilitySnapshot(packageName = "com.ss.android.ugc.aweme", vote = ForegroundVote.BLACKLIST)
        )
        val outcome = ForegroundReportOutcome(
            effectivePackageName = "com.ss.android.ugc.aweme",
            effectivePackageBlacklisted = true,
            diagnosticRecommendation = "LOCK_BY_DIAGNOSTIC_MAJORITY",
            businessAction = ForegroundBusinessAction.NO_LOCK,
            businessReason = "PWA_BYPASS",
            allowanceState = "PWA_BYPASS",
            guardPassed = true,
            visibilityPassed = null
        )
        val decision = gate.evaluate(cycle, outcome, fixedNow)
        assertEquals(ForegroundReportEmission.FULL, decision.emission)
        assertEquals(listOf(ForegroundReportTrigger.SOURCE_DISPUTE_STARTED), decision.triggers)
    }

    @Test
    fun alreadyEnforcedWithSourceFaultReportsOnlyFaultTrigger() {
        val gate = ForegroundReportGate()
        // All sources FAILED → sourceFault=true; reason = LOCK_ALREADY_ATTACHED →
        // ALREADY_ENFORCED. Only SOURCE_FAULT_STARTED should fire.
        val cycle = ForegroundDiagnosticCycle(
            reportId = "FG-20240801-120000-0400",
            checkedAtMs = fixedNow
        )
        cycle.updateUsageEvents(ForegroundFaultSnapshotFactory.eventsFailed("QUERY_EVENTS_FAILED", fixedNow))
        cycle.updateUsageStats(ForegroundFaultSnapshotFactory.usageStatsFailed("QUERY_USAGE_STATS_FAILED", fixedNow))
        cycle.updateAccessibility(
            ForegroundSourceSnapshot(
                source = "ACCESSIBILITY",
                status = ForegroundSourceStatus.FAILED,
                packageName = null,
                vote = ForegroundVote.ABSTAIN,
                capturedAtMs = fixedNow,
                queryStartedAtMs = fixedNow - 1L,
                queryFinishedAtMs = fixedNow,
                candidates = emptyList(),
                selectionReason = null,
                unavailableReason = null,
                failureClass = "A11Y_STATUS_FAILED"
            )
        )
        val outcome = ForegroundReportOutcome(
            effectivePackageName = "com.ss.android.ugc.aweme",
            effectivePackageBlacklisted = true,
            diagnosticRecommendation = "NO_DIAGNOSTIC_LOCK",
            businessAction = ForegroundBusinessAction.NO_LOCK,
            businessReason = "LOCK_ALREADY_ATTACHED",
            allowanceState = "LOCK_ATTACHED",
            guardPassed = true,
            visibilityPassed = true
        )
        val decision = gate.evaluate(cycle, outcome, fixedNow)
        assertEquals(ForegroundReportEmission.FULL, decision.emission)
        assertEquals(listOf(ForegroundReportTrigger.SOURCE_FAULT_STARTED), decision.triggers)
    }

    @Test
    fun resetClearsAllNewGateState() {
        val gate = ForegroundReportGate()
        val outcome = ForegroundReportOutcome(
            effectivePackageName = "com.ss.android.ugc.aweme",
            effectivePackageBlacklisted = true,
            diagnosticRecommendation = "LOCK_BY_DIAGNOSTIC_MAJORITY",
            businessAction = ForegroundBusinessAction.NO_LOCK,
            businessReason = "INTERNAL_DIAG_ONLY",
            allowanceState = "ALLOW_NONE",
            guardPassed = null,
            visibilityPassed = null
        )
        val cycle1 = newCycle(
            events = eventsSnapshot(packageName = "com.ss.android.ugc.aweme", vote = ForegroundVote.BLACKLIST),
            usageStats = usageStatsSnapshot(packageName = "com.android.launcher", vote = ForegroundVote.NON_BLACKLIST),
            accessibility = accessibilitySnapshot(packageName = "com.ss.android.ugc.aweme", vote = ForegroundVote.BLACKLIST)
        )
        gate.evaluate(cycle1, outcome, fixedNow)

        gate.reset()

        // After reset, identical dispute must START again (not be suppressed or change).
        val decision = gate.evaluate(cycle1, outcome, fixedNow + 5_000L)
        assertEquals(ForegroundReportEmission.FULL, decision.emission)
        assertEquals(listOf(ForegroundReportTrigger.SOURCE_DISPUTE_STARTED), decision.triggers)
        assertTrue(
            "sourceProblemStartedAtMs must be set after reset + first dispute",
            decision.sourceProblemStartedAtMs != null
        )
    }

    // ──────────────── V2-R2 §8 Task 3 Step 1: Formatter disposition-aware wiring RED tests ────────────────
    //
    // These tests exercise the new Decision-aware Formatter contract:
    //   * the formatter main overload accepts ForegroundReportDecision (not raw emission)
    //   * triggers are rendered in fixed V2-R2 §5.1 order
    //   * UNEXPLAINED_MISSED_LOCK is the ONLY disposition that emits the
    //     "diagnostic_vs_business_mismatch" / "本应锁屏但未发起锁屏请求" anomaly text
    //   * ALREADY_ENFORCED prints current-lock-state / not-repeat fields
    //   * SOURCE_RECOVERED trigger renders recoveryTrigger label
    //   * CONTINUING_CONFLICT_SUMMARY stays EXACTLY one line
    //   * NONE decision formats to empty list

    /** Helper: build a decision whose emission == FULL with the given triggers. */
    private fun fullDecision(
        cycle: ForegroundDiagnosticCycle,
        outcome: ForegroundReportOutcome,
        triggers: List<ForegroundReportTrigger>
    ): ForegroundReportDecision {
        val gate = ForegroundReportGate()
        return gate.evaluate(cycle, outcome, fixedNow).copy(triggers = triggers)
    }

    @Test
    fun requestLockReportListsActionVotesAndReason() {
        val cycle = agreedCycle()
        val outcome = requestLockOutcome()
        val decision = fullDecision(
            cycle, outcome,
            listOf(ForegroundReportTrigger.REQUEST_LOCK)
        )
        val lines = ForegroundReportFormatter.format(cycle, outcome, decision, fixedNow)
        val joined = lines.joinToString("\n")
        // 动作=请求锁屏
        assertTrue("must list action=请求锁屏", joined.contains("动作=请求锁屏"))
        // 报告触发原因=REQUEST_LOCK (V2-R2 §5.1 trigger token)
        assertTrue("must list trigger reason", joined.contains("报告触发原因=REQUEST_LOCK"))
        // 票数 BLACKLIST/NON_BLACKLIST/ABSTAIN
        assertTrue(joined.contains("票数=BLACKLIST:3"))
        assertTrue(joined.contains("NON_BLACKLIST:0"))
        assertTrue(joined.contains("ABSTAIN:0"))
        // 业务处置
        assertTrue(joined.contains("业务处置=REQUESTED_LOCK"))
        // 最终原因
        assertTrue(joined.contains("最终原因=BLACKLIST_DND_LOCKED"))
        // 来源关系
        assertTrue(joined.contains("来源关系=ALL_AGREE"))
        // 放行状态
        assertTrue(joined.contains("放行状态="))
        // V2-R2 §8 Task 3 Step 5: this is NOT UNEXPLAINED_MISSED_LOCK, so the
        // legacy anomaly line MUST NOT appear.
        assertFalse("legacy anomaly forbidden for REQUEST_LOCK", joined.contains("diagnostic_vs_business_mismatch"))
        assertFalse("legacy '应该锁却没锁' forbidden for REQUEST_LOCK", joined.contains("应该锁却没锁"))
        assertFalse("'本应锁屏但未发起锁屏请求' forbidden for REQUEST_LOCK",
            joined.contains("本应锁屏但未发起锁屏请求"))
    }

    @Test
    fun missedLockReportUsesExplicitAnomalyText() {
        val cycle = agreedCycle()
        val outcome = ForegroundReportOutcome(
            effectivePackageName = "com.ss.android.ugc.aweme",
            effectivePackageBlacklisted = true,
            diagnosticRecommendation = "LOCK_BY_DIAGNOSTIC_MAJORITY",
            businessAction = ForegroundBusinessAction.NO_LOCK,
            businessReason = "INTERNAL_DIAG_ONLY",
            allowanceState = "ALLOW_NONE",
            guardPassed = true,
            visibilityPassed = true
        )
        val decision = fullDecision(
            cycle, outcome,
            listOf(ForegroundReportTrigger.MISSED_LOCK_STARTED)
        )
        val lines = ForegroundReportFormatter.format(cycle, outcome, decision, fixedNow)
        val joined = lines.joinToString("\n")
        // 业务处置=UNEXPLAINED_MISSED_LOCK
        assertTrue("must surface UNEXPLAINED_MISSED_LOCK disposition",
            joined.contains("业务处置=UNEXPLAINED_MISSED_LOCK"))
        // 异常=按照当前规则本应锁屏，但未发起锁屏请求
        assertTrue("must emit explicit anomaly text for missed-lock",
            joined.contains("异常=按照当前规则本应锁屏，但未发起锁屏请求"))
        // 投票与动作关系=无法解释的不一致
        assertTrue("must surface 无法解释的不一致",
            joined.contains("投票与动作关系=无法解释的不一致"))
        // Legacy "diagnostic_vs_business_mismatch" was removed; this label
        // must NOT leak through to the final output for missed-lock.
        assertFalse("legacy diagnostic_vs_business_mismatch must NOT appear",
            joined.contains("anomaly=diagnostic_vs_business_mismatch"))
    }

    @Test
    fun legitimateBypassWithDisputeDoesNotPrintMissedLock() {
        val cycle = newCycle(
            events = eventsSnapshot(packageName = "com.ss.android.ugc.aweme", vote = ForegroundVote.BLACKLIST),
            usageStats = usageStatsSnapshot(packageName = "com.android.launcher", vote = ForegroundVote.NON_BLACKLIST),
            accessibility = accessibilitySnapshot(packageName = "com.ss.android.ugc.aweme", vote = ForegroundVote.BLACKLIST)
        )
        val outcome = ForegroundReportOutcome(
            effectivePackageName = "com.ss.android.ugc.aweme",
            effectivePackageBlacklisted = true,
            diagnosticRecommendation = "LOCK_BY_DIAGNOSTIC_MAJORITY",
            businessAction = ForegroundBusinessAction.NO_LOCK,
            businessReason = "FOREGROUND_GUARD_REJECTED", // LEGITIMATE_BYPASS
            allowanceState = "ALLOW_OK",
            guardPassed = false,
            visibilityPassed = null
        )
        val decision = fullDecision(
            cycle, outcome,
            listOf(ForegroundReportTrigger.SOURCE_DISPUTE_STARTED)
        )
        val lines = ForegroundReportFormatter.format(cycle, outcome, decision, fixedNow)
        val joined = lines.joinToString("\n")
        // 业务处置=LEGITIMATE_BYPASS
        assertTrue("must surface LEGITIMATE_BYPASS disposition",
            joined.contains("业务处置=LEGITIMATE_BYPASS"))
        // V2-R2 §8 Task 3 Step 4: legacy '应该锁却没锁' / 'diagnostic_vs_business_mismatch'
        // / '本应锁屏但未发起锁屏请求' must NOT appear for non-missed-lock dispositions.
        assertFalse("legacy '应该锁却没锁' forbidden for LEGITIMATE_BYPASS",
            joined.contains("应该锁却没锁"))
        assertFalse("legacy diagnostic_vs_business_mismatch forbidden for LEGITIMATE_BYPASS",
            joined.contains("diagnostic_vs_business_mismatch"))
        assertFalse("'本应锁屏但未发起锁屏请求' forbidden for LEGITIMATE_BYPASS",
            joined.contains("本应锁屏但未发起锁屏请求"))
        assertFalse("'无法解释的不一致' forbidden for LEGITIMATE_BYPASS",
            joined.contains("无法解释的不一致"))
    }

    @Test
    fun alreadyEnforcedWithFaultPrintsCurrentLockState() {
        val cycle = newCycle(
            events = ForegroundFaultSnapshotFactory.eventsFailed("QUERY_EVENTS_FAILED", fixedNow),
            usageStats = usageStatsSnapshot(packageName = "com.ss.android.ugc.aweme"),
            accessibility = accessibilitySnapshot(packageName = "com.ss.android.ugc.aweme")
        )
        val outcome = ForegroundReportOutcome(
            effectivePackageName = "com.ss.android.ugc.aweme",
            effectivePackageBlacklisted = true,
            diagnosticRecommendation = "LOCK_BY_DIAGNOSTIC_MAJORITY",
            businessAction = ForegroundBusinessAction.NO_LOCK,
            businessReason = "LOCK_ALREADY_ATTACHED", // ALREADY_ENFORCED
            allowanceState = "LOCK_ATTACHED",
            guardPassed = true,
            visibilityPassed = true
        )
        val decision = fullDecision(
            cycle, outcome,
            listOf(ForegroundReportTrigger.SOURCE_FAULT_STARTED)
        )
        val lines = ForegroundReportFormatter.format(cycle, outcome, decision, fixedNow)
        val joined = lines.joinToString("\n")
        // 业务处置=ALREADY_ENFORCED
        assertTrue("must surface ALREADY_ENFORCED disposition",
            joined.contains("业务处置=ALREADY_ENFORCED"))
        // 当前锁定状态=锁屏界面已存在
        assertTrue("must emit 当前锁定状态=锁屏界面已存在",
            joined.contains("当前锁定状态=锁屏界面已存在"))
        // 最终动作=未重复请求锁屏
        assertTrue("must emit 最终动作=未重复请求锁屏",
            joined.contains("最终动作=未重复请求锁屏"))
        // Forbidden for non-missed-lock
        assertFalse("legacy '应该锁却没锁' forbidden for ALREADY_ENFORCED",
            joined.contains("应该锁却没锁"))
        assertFalse("legacy diagnostic_vs_business_mismatch forbidden for ALREADY_ENFORCED",
            joined.contains("diagnostic_vs_business_mismatch"))
        assertFalse("'本应锁屏但未发起锁屏请求' forbidden for ALREADY_ENFORCED",
            joined.contains("本应锁屏但未发起锁屏请求"))
    }

    @Test
    fun sourceRecoveryReportUsesRecoveryTrigger() {
        val cycle = agreedCycle()
        val outcome = ForegroundReportOutcome(
            effectivePackageName = "com.ss.android.ugc.aweme",
            effectivePackageBlacklisted = true,
            diagnosticRecommendation = "LOCK_BY_DIAGNOSTIC_MAJORITY",
            businessAction = ForegroundBusinessAction.NO_LOCK,
            businessReason = "INTERNAL_DIAG_ONLY",
            allowanceState = "ALLOW_NONE",
            guardPassed = true,
            visibilityPassed = true
        )
        val decision = fullDecision(
            cycle, outcome,
            listOf(ForegroundReportTrigger.SOURCE_RECOVERED)
        )
        val lines = ForegroundReportFormatter.format(cycle, outcome, decision, fixedNow)
        val joined = lines.joinToString("\n")
        // V2-R2 §8 Task 3 Step 4: recovery trigger renders as 报告触发原因=SOURCE_RECOVERED
        assertTrue("must list recovery trigger label",
            joined.contains("报告触发原因=SOURCE_RECOVERED"))
        // recovery-only report should NOT print mismatch anomaly
        assertFalse("recovery must not print mismatch", joined.contains("diagnostic_vs_business_mismatch"))
        assertFalse("recovery must not print 应该锁却没锁", joined.contains("应该锁却没锁"))
    }

    @Test
    fun combinedTriggersAreRenderedInFixedOrder() {
        val cycle = newCycle(
            events = eventsSnapshot(packageName = "com.ss.android.ugc.aweme", vote = ForegroundVote.BLACKLIST),
            usageStats = usageStatsSnapshot(packageName = "com.android.launcher", vote = ForegroundVote.NON_BLACKLIST),
            accessibility = accessibilitySnapshot(packageName = "com.ss.android.ugc.aweme", vote = ForegroundVote.BLACKLIST)
        )
        val outcome = ForegroundReportOutcome(
            effectivePackageName = "com.ss.android.ugc.aweme",
            effectivePackageBlacklisted = true,
            diagnosticRecommendation = "LOCK_BY_DIAGNOSTIC_MAJORITY",
            businessAction = ForegroundBusinessAction.NO_LOCK,
            businessReason = "INTERNAL_DIAG_ONLY",
            allowanceState = "ALLOW_NONE",
            guardPassed = null,
            visibilityPassed = null
        )
        // V2-R2 §5.1 fixed order:
        //   MISSED_LOCK_STARTED → SOURCE_RECOVERED → SOURCE_DISPUTE_STARTED → SOURCE_FAULT_STARTED
        val decision = fullDecision(
            cycle, outcome,
            listOf(
                ForegroundReportTrigger.MISSED_LOCK_STARTED,
                ForegroundReportTrigger.SOURCE_RECOVERED,
                ForegroundReportTrigger.SOURCE_DISPUTE_STARTED
            )
        )
        val lines = ForegroundReportFormatter.format(cycle, outcome, decision, fixedNow)
        val triggerLine = lines.first { it.contains("报告触发原因=") }
        val triggersJoined = triggerLine.substringAfter("报告触发原因=")
        val missedIdx = triggersJoined.indexOf("MISSED_LOCK_STARTED")
        val recoveredIdx = triggersJoined.indexOf("SOURCE_RECOVERED")
        val disputeIdx = triggersJoined.indexOf("SOURCE_DISPUTE_STARTED")
        assertTrue("missed-lock trigger missing: $triggerLine", missedIdx >= 0)
        assertTrue("recovery trigger missing: $triggerLine", recoveredIdx >= 0)
        assertTrue("dispute trigger missing: $triggerLine", disputeIdx >= 0)
        assertTrue("missed-lock must come before recovery (V2-R2 §5.1)",
            missedIdx < recoveredIdx)
        assertTrue("recovery must come before dispute (V2-R2 §5.1)",
            recoveredIdx < disputeIdx)
    }

    @Test
    fun continuingSourceProblemSummaryIsExactlyOneLine() {
        val cycle = newCycle(
            events = eventsSnapshot(packageName = "com.ss.android.ugc.aweme", vote = ForegroundVote.BLACKLIST),
            usageStats = usageStatsSnapshot(packageName = "com.android.launcher", vote = ForegroundVote.NON_BLACKLIST),
            accessibility = accessibilitySnapshot(packageName = "com.ss.android.ugc.aweme", vote = ForegroundVote.BLACKLIST)
        )
        val outcome = ForegroundReportOutcome(
            effectivePackageName = "com.ss.android.ugc.aweme",
            effectivePackageBlacklisted = true,
            diagnosticRecommendation = "LOCK_BY_DIAGNOSTIC_MAJORITY",
            businessAction = ForegroundBusinessAction.NO_LOCK,
            businessReason = "INTERNAL_DIAG_ONLY",
            allowanceState = "ALLOW_NONE",
            guardPassed = null,
            visibilityPassed = null
        )
        // First trigger the dispute so gate has a baseline
        val gate = ForegroundReportGate()
        gate.evaluate(cycle, outcome, fixedNow)
        // 30s later, identical dispute → CONTINUING_CONFLICT_SUMMARY
        val decision = gate.evaluate(cycle, outcome, fixedNow + 30_000L)
        assertEquals(ForegroundReportEmission.CONTINUING_CONFLICT_SUMMARY, decision.emission)
        val lines = ForegroundReportFormatter.format(
            cycle, outcome, decision, fixedNow + 30_000L,
            conflictStartedAtMs = fixedNow
        )
        assertEquals("continuation must be EXACTLY one line (V2-R2 §8 Task 3 Step 6)", 1, lines.size)
        val line = lines.first()
        // V2-R2 §8 Task 3 Step 6: must keep the [前台争议持续] prefix
        assertTrue("continuation must keep [前台争议持续] prefix", line.contains("[前台争议持续]"))
        assertTrue(line.contains("duration="))
        assertTrue(line.contains("blacklistVotes="))
    }

    // ──────────────── R1-1 RED tests (中性未采样 + usable package mismatch) ────────────────
    // Per R1 review FINAL_REVIEW.md §R1-1: a source that has not actually been
    // queried (INITIALIZING + null + ABSTAIN + no query timestamps + no failure
    // metadata) must be treated as a neutral placeholder, not as a fault or
    // package disagreement.

    /** Build a UsageStats-like snapshot that has not been queried this cycle. */
    private fun unqueriedUsageStatsSnapshot(): ForegroundSourceSnapshot =
        ForegroundSourceSnapshot(
            source = "USAGE_STATS",
            status = ForegroundSourceStatus.INITIALIZING,
            packageName = null,
            vote = ForegroundVote.ABSTAIN,
            capturedAtMs = fixedNow,
            queryStartedAtMs = null,
            queryFinishedAtMs = null,
            candidates = emptyList(),
            selectionReason = null,
            unavailableReason = null,
            failureClass = null
        )

    @Test
    fun lockAlreadyAttachedWithUnqueriedUsageStatsEmitsNothing() {
        val gate = ForegroundReportGate()
        val cycle = ForegroundDiagnosticCycle(
            reportId = "FG-20240801-120000-1101",
            checkedAtMs = fixedNow
        )
        cycle.updateUsageEvents(eventsSnapshot())
        cycle.updateUsageStats(unqueriedUsageStatsSnapshot())
        cycle.updateAccessibility(accessibilitySnapshot())
        val outcome = ForegroundReportOutcome(
            effectivePackageName = "com.ss.android.ugc.aweme",
            effectivePackageBlacklisted = true,
            diagnosticRecommendation = "LOCK_BY_DIAGNOSTIC_MAJORITY",
            businessAction = ForegroundBusinessAction.NO_LOCK,
            businessReason = "LOCK_ALREADY_ATTACHED",
            allowanceState = "LOCK_ATTACHED",
            guardPassed = true,
            visibilityPassed = true
        )
        val decision = gate.evaluate(cycle, outcome, fixedNow)
        assertEquals(ForegroundReportEmission.NONE, decision.emission)
        assertTrue("LOCK_ALREADY_ATTACHED + neutral not-sampled must be silent, got triggers=${decision.triggers}",
            decision.triggers.isEmpty())
        assertFalse("hasSourceDispute must be false for neutral not-sampled source",
            decision.assessment.hasSourceDispute)
        assertFalse("hasSourceFault must be false for neutral not-sampled source",
            decision.assessment.hasSourceFault)
        assertEquals(ForegroundSourceRelation.ALL_AGREE, decision.assessment.sourceRelation)
    }

    @Test
    fun initializingSourceWithoutQueryIsNeutral() {
        val gate = ForegroundReportGate()
        val cycle = agreedCycle()
        // Override usageStats to a truly unqueried snapshot.
        cycle.updateUsageStats(unqueriedUsageStatsSnapshot())
        val outcome = ForegroundReportOutcome(
            effectivePackageName = "com.ss.android.ugc.aweme",
            effectivePackageBlacklisted = true,
            diagnosticRecommendation = "LOCK_BY_DIAGNOSTIC_MAJORITY",
            businessAction = ForegroundBusinessAction.REQUEST_LOCK,
            businessReason = "BLACKLIST_DND_LOCKED",
            allowanceState = "ALLOW_NONE",
            guardPassed = true,
            visibilityPassed = true
        )
        val decision = gate.evaluate(cycle, outcome, fixedNow)
        // The neutral not-sampled UsageStats must NOT count as a fault, so
        // the only trigger should be REQUEST_LOCK (not a SOURCE_FAULT_*).
        assertEquals(ForegroundReportEmission.FULL, decision.emission)
        assertEquals(listOf(ForegroundReportTrigger.REQUEST_LOCK), decision.triggers)
        assertFalse("neutral not-sampled must NOT be a fault", decision.assessment.hasSourceFault)
    }

    @Test
    fun availableNullAbstainDoesNotCreatePackageMismatch() {
        val gate = ForegroundReportGate()
        // All three sources AVAILABLE, two of them point to a blacklisted package,
        // one is AVAILABLE but abstains (no package).
        // R2-1: explicit ABSTAIN vote via raw snapshot so the mixed-vote
        // condition (blacklistVotes > 0 && nonBlacklistVotes > 0) does NOT
        // fire — the test name and intent is "abstain", not "non-blacklist".
        val cycle = newCycle(
            events = eventsSnapshot(packageName = "com.ss.android.ugc.aweme"),
            usageStats = usageStatsSnapshot(packageName = "com.ss.android.ugc.aweme"),
            accessibility = ForegroundSourceSnapshot(
                source = "ACCESSIBILITY",
                status = ForegroundSourceStatus.AVAILABLE,
                packageName = null,
                vote = ForegroundVote.ABSTAIN,
                capturedAtMs = fixedNow,
                queryStartedAtMs = fixedNow - 1L,
                queryFinishedAtMs = fixedNow,
                candidates = emptyList(),
                selectionReason = "active_window",
                unavailableReason = null,
                failureClass = null
            )
        )
        val outcome = ForegroundReportOutcome(
            effectivePackageName = "com.ss.android.ugc.aweme",
            effectivePackageBlacklisted = true,
            diagnosticRecommendation = "LOCK_BY_DIAGNOSTIC_MAJORITY",
            businessAction = ForegroundBusinessAction.REQUEST_LOCK,
            businessReason = "BLACKLIST_DND_LOCKED",
            allowanceState = "ALLOW_NONE",
            guardPassed = true,
            visibilityPassed = true
        )
        val decision = gate.evaluate(cycle, outcome, fixedNow)
        // null + non-null must not be treated as package mismatch.
        assertFalse("AVAILABLE+null+ABSTAIN must NOT create package dispute",
            decision.assessment.hasSourceDispute)
        assertEquals(ForegroundSourceRelation.ALL_AGREE, decision.assessment.sourceRelation)
    }

    @Test
    fun ambiguousSourceIsDisputeNotFaultWithoutFailureMetadata() {
        val gate = ForegroundReportGate()
        // One source AMBIGUOUS with NO failure/unavailable metadata.
        val cycle = newCycle(
            events = eventsSnapshot(),
            usageStats = usageStatsSnapshot(),
            accessibility = accessibilitySnapshot(
                packageName = null,
                status = ForegroundSourceStatus.AMBIGUOUS
            )
        )
        val outcome = ForegroundReportOutcome(
            effectivePackageName = "com.ss.android.ugc.aweme",
            effectivePackageBlacklisted = true,
            diagnosticRecommendation = "LOCK_BY_DIAGNOSTIC_MAJORITY",
            businessAction = ForegroundBusinessAction.REQUEST_LOCK,
            businessReason = "BLACKLIST_DND_LOCKED",
            allowanceState = "ALLOW_NONE",
            guardPassed = true,
            visibilityPassed = true
        )
        val decision = gate.evaluate(cycle, outcome, fixedNow)
        // AMBIGUOUS without metadata is a dispute but NOT a fault.
        assertTrue("AMBIGUOUS without metadata must still flag dispute", decision.assessment.hasSourceDispute)
        assertFalse("AMBIGUOUS without failure/unavailable metadata must NOT be a fault",
            decision.assessment.hasSourceFault)
    }

    @Test
    fun unavailableSourceIsFault() {
        val gate = ForegroundReportGate()
        val cycle = newCycle(
            events = eventsSnapshot(),
            usageStats = usageStatsSnapshot(),
            accessibility = accessibilitySnapshot(
                packageName = null,
                status = ForegroundSourceStatus.UNAVAILABLE
            )
        )
        val outcome = ForegroundReportOutcome(
            effectivePackageName = "com.ss.android.ugc.aweme",
            effectivePackageBlacklisted = true,
            diagnosticRecommendation = "LOCK_BY_DIAGNOSTIC_MAJORITY",
            businessAction = ForegroundBusinessAction.REQUEST_LOCK,
            businessReason = "BLACKLIST_DND_LOCKED",
            allowanceState = "ALLOW_NONE",
            guardPassed = true,
            visibilityPassed = true
        )
        val decision = gate.evaluate(cycle, outcome, fixedNow)
        assertTrue("UNAVAILABLE source must be a fault", decision.assessment.hasSourceFault)
    }

    @Test
    fun failureMetadataMakesAvailableSourceFault() {
        val gate = ForegroundReportGate()
        // A source in AVAILABLE status but with a real failureClass must still
        // be classified as a fault.
        val eventsWithFailure = ForegroundSourceSnapshot(
            source = "USAGE_EVENTS",
            status = ForegroundSourceStatus.AVAILABLE,
            packageName = "com.ss.android.ugc.aweme",
            vote = ForegroundVote.BLACKLIST,
            capturedAtMs = fixedNow,
            queryStartedAtMs = fixedNow - 5L,
            queryFinishedAtMs = fixedNow - 4L,
            candidates = emptyList(),
            selectionReason = "newest_resume",
            unavailableReason = null,
            failureClass = "USAGE_EVENTS_STALE_RESULT"
        )
        val cycle = newCycle(events = eventsWithFailure)
        val outcome = ForegroundReportOutcome(
            effectivePackageName = "com.ss.android.ugc.aweme",
            effectivePackageBlacklisted = true,
            diagnosticRecommendation = "LOCK_BY_DIAGNOSTIC_MAJORITY",
            businessAction = ForegroundBusinessAction.REQUEST_LOCK,
            businessReason = "BLACKLIST_DND_LOCKED",
            allowanceState = "ALLOW_NONE",
            guardPassed = true,
            visibilityPassed = true
        )
        val decision = gate.evaluate(cycle, outcome, fixedNow)
        assertTrue("non-null failureClass must be a fault", decision.assessment.hasSourceFault)
    }

    @Test
    fun lockAlreadyAttachedRealLogShapeRemainsSilentAcrossRepeatedPolls() {
        val gate = ForegroundReportGate()
        // Real log shape: UsageEvents=AVAILABLE/aweme/BLACKLIST, UsageStats=INITIALIZING/null/ABSTAIN
        // (no query, no failure), Accessibility=AVAILABLE/aweme/BLACKLIST.
        var fullCount = 0
        var continuingCount = 0
        for (i in 0 until 22) {
            val cycle = ForegroundDiagnosticCycle(
                reportId = "FG-20240801-120000-110$i",
                checkedAtMs = fixedNow + i * 1_000L
            )
            cycle.updateUsageEvents(eventsSnapshot())
            cycle.updateUsageStats(unqueriedUsageStatsSnapshot())
            cycle.updateAccessibility(accessibilitySnapshot())
            val outcome = ForegroundReportOutcome(
                effectivePackageName = "com.ss.android.ugc.aweme",
                effectivePackageBlacklisted = true,
                diagnosticRecommendation = "LOCK_BY_DIAGNOSTIC_MAJORITY",
                businessAction = ForegroundBusinessAction.NO_LOCK,
                businessReason = "LOCK_ALREADY_ATTACHED",
                allowanceState = "LOCK_ATTACHED",
                guardPassed = true,
                visibilityPassed = true
            )
            val emission = gate.evaluate(cycle, outcome, fixedNow + i * 1_000L).emission
            when (emission) {
                ForegroundReportEmission.FULL -> fullCount++
                ForegroundReportEmission.CONTINUING_CONFLICT_SUMMARY -> continuingCount++
                ForegroundReportEmission.NONE -> { /* expected */ }
            }
        }
        assertEquals("22 identical LOCK_ALREADY_ATTACHED polls must produce 0 FULL", 0, fullCount)
        assertEquals("22 identical LOCK_ALREADY_ATTACHED polls must produce 0 CONTINUING", 0, continuingCount)
    }

    // ──────────────── R1-2 RED tests (stableAllowance + stableSignature fields) ────────────────
    // Per R1 review FINAL_REVIEW.md §R1-2: stableAllowanceCategoryOf must not
    // return raw allowance tokens (temp=30s vs temp=29s must collapse), and
    // stableSignature must include failureClass / unavailableReason /
    // guardPassed / visibilityPassed / diagnosticRecommendation /
    // enforcementDisposition / hasSourceDispute / hasSourceFault.

    @Test
    fun foregroundGuardCountdownDoesNotChangeStableSignature() {
        val cycle1 = agreedCycle()
        val cycle2 = agreedCycle()
        val outcome1 = ForegroundReportOutcome(
            effectivePackageName = "com.ss.android.ugc.aweme",
            effectivePackageBlacklisted = true,
            diagnosticRecommendation = "LOCK_BY_DIAGNOSTIC_MAJORITY",
            businessAction = ForegroundBusinessAction.NO_LOCK,
            businessReason = "FOREGROUND_GUARD_REJECTED",
            allowanceState = "temp=30s",
            guardPassed = false,
            visibilityPassed = null
        )
        val outcome2 = outcome1.copy(allowanceState = "temp=29s")
        val a1 = ForegroundReportAssessmentFactory.assess(cycle1, outcome1)
        val a2 = ForegroundReportAssessmentFactory.assess(cycle2, outcome2)
        assertEquals("stableAllowanceCategory must collapse dynamic countdown seconds",
            a1.stableAllowanceCategory, a2.stableAllowanceCategory)
        assertEquals("stableSignature must NOT change with countdown seconds: '${a1.stableSignature}' vs '${a2.stableSignature}'",
            a1.stableSignature, a2.stableSignature)
    }

    @Test
    fun failureClassChangeChangesStableSignature() {
        val cycle1 = newCycle(
            events = ForegroundSourceSnapshot(
                source = "USAGE_EVENTS", status = ForegroundSourceStatus.AVAILABLE,
                packageName = "com.ss.android.ugc.aweme", vote = ForegroundVote.BLACKLIST,
                capturedAtMs = fixedNow, queryStartedAtMs = fixedNow - 5L, queryFinishedAtMs = fixedNow - 4L,
                candidates = emptyList(), selectionReason = "newest_resume",
                unavailableReason = null, failureClass = "EVENTS_TIMEOUT"
            )
        )
        val cycle2 = newCycle(
            events = ForegroundSourceSnapshot(
                source = "USAGE_EVENTS", status = ForegroundSourceStatus.AVAILABLE,
                packageName = "com.ss.android.ugc.aweme", vote = ForegroundVote.BLACKLIST,
                capturedAtMs = fixedNow, queryStartedAtMs = fixedNow - 5L, queryFinishedAtMs = fixedNow - 4L,
                candidates = emptyList(), selectionReason = "newest_resume",
                unavailableReason = null, failureClass = "EVENTS_SECURITY"
            )
        )
        val outcome = ForegroundReportOutcome(
            effectivePackageName = "com.ss.android.ugc.aweme",
            effectivePackageBlacklisted = true,
            diagnosticRecommendation = "LOCK_BY_DIAGNOSTIC_MAJORITY",
            businessAction = ForegroundBusinessAction.REQUEST_LOCK,
            businessReason = "BLACKLIST_DND_LOCKED",
            allowanceState = "ALLOW_NONE",
            guardPassed = true,
            visibilityPassed = true
        )
        val a1 = ForegroundReportAssessmentFactory.assess(cycle1, outcome)
        val a2 = ForegroundReportAssessmentFactory.assess(cycle2, outcome)
        assertTrue("failureClass change must change stableSignature: '${a1.stableSignature}' vs '${a2.stableSignature}'",
            a1.stableSignature != a2.stableSignature)
    }

    @Test
    fun unavailableReasonChangeChangesStableSignature() {
        fun snap(reason: String?): ForegroundSourceSnapshot = ForegroundSourceSnapshot(
            source = "USAGE_STATS", status = ForegroundSourceStatus.UNAVAILABLE,
            packageName = null, vote = ForegroundVote.ABSTAIN,
            capturedAtMs = fixedNow, queryStartedAtMs = fixedNow - 3L, queryFinishedAtMs = fixedNow - 2L,
            candidates = emptyList(), selectionReason = null,
            unavailableReason = reason, failureClass = null
        )
        val cycle1 = newCycle(usageStats = snap("USAGE_STATS_MANAGER_NULL"))
        val cycle2 = newCycle(usageStats = snap("USAGE_STATS_EMPTY"))
        val outcome = ForegroundReportOutcome(
            effectivePackageName = null, effectivePackageBlacklisted = null,
            diagnosticRecommendation = "NO_DIAGNOSTIC_LOCK",
            businessAction = ForegroundBusinessAction.NO_LOCK,
            businessReason = "NO_FOREGROUND_RESOLVED",
            allowanceState = "UNKNOWN",
            guardPassed = null, visibilityPassed = null
        )
        val a1 = ForegroundReportAssessmentFactory.assess(cycle1, outcome)
        val a2 = ForegroundReportAssessmentFactory.assess(cycle2, outcome)
        assertTrue("unavailableReason change must change stableSignature: '${a1.stableSignature}' vs '${a2.stableSignature}'",
            a1.stableSignature != a2.stableSignature)
    }

    @Test
    fun guardResultChangeChangesStableSignature() {
        val cycle1 = agreedCycle()
        val cycle2 = agreedCycle()
        val outcome1 = ForegroundReportOutcome(
            effectivePackageName = "com.ss.android.ugc.aweme",
            effectivePackageBlacklisted = true,
            diagnosticRecommendation = "LOCK_BY_DIAGNOSTIC_MAJORITY",
            businessAction = ForegroundBusinessAction.NO_LOCK,
            businessReason = "INTERNAL_DIAG_ONLY",
            allowanceState = "ALLOW_NONE",
            guardPassed = true,
            visibilityPassed = true
        )
        val outcome2 = outcome1.copy(guardPassed = false)
        val a1 = ForegroundReportAssessmentFactory.assess(cycle1, outcome1)
        val a2 = ForegroundReportAssessmentFactory.assess(cycle2, outcome2)
        assertTrue("guardPassed change must change stableSignature: '${a1.stableSignature}' vs '${a2.stableSignature}'",
            a1.stableSignature != a2.stableSignature)
    }

    @Test
    fun visibilityResultChangeChangesStableSignature() {
        val cycle1 = agreedCycle()
        val cycle2 = agreedCycle()
        val outcome1 = ForegroundReportOutcome(
            effectivePackageName = "com.ss.android.ugc.aweme",
            effectivePackageBlacklisted = true,
            diagnosticRecommendation = "LOCK_BY_DIAGNOSTIC_MAJORITY",
            businessAction = ForegroundBusinessAction.REQUEST_LOCK,
            businessReason = "BLACKLIST_DND_LOCKED",
            allowanceState = "ALLOW_NONE",
            guardPassed = true,
            visibilityPassed = true
        )
        val outcome2 = outcome1.copy(visibilityPassed = false)
        val a1 = ForegroundReportAssessmentFactory.assess(cycle1, outcome1)
        val a2 = ForegroundReportAssessmentFactory.assess(cycle2, outcome2)
        assertTrue("visibilityPassed change must change stableSignature: '${a1.stableSignature}' vs '${a2.stableSignature}'",
            a1.stableSignature != a2.stableSignature)
    }

    @Test
    fun diagnosticRecommendationChangeChangesStableSignature() {
        val cycle1 = agreedCycle()
        val cycle2 = agreedCycle()
        val outcome1 = ForegroundReportOutcome(
            effectivePackageName = "com.ss.android.ugc.aweme",
            effectivePackageBlacklisted = true,
            diagnosticRecommendation = "LOCK_BY_DIAGNOSTIC_MAJORITY",
            businessAction = ForegroundBusinessAction.NO_LOCK,
            businessReason = "INTERNAL_DIAG_ONLY",
            allowanceState = "ALLOW_NONE",
            guardPassed = true,
            visibilityPassed = true
        )
        val outcome2 = outcome1.copy(diagnosticRecommendation = "LOCK_ON_TIE")
        val a1 = ForegroundReportAssessmentFactory.assess(cycle1, outcome1)
        val a2 = ForegroundReportAssessmentFactory.assess(cycle2, outcome2)
        assertTrue("diagnosticRecommendation change must change stableSignature: '${a1.stableSignature}' vs '${a2.stableSignature}'",
            a1.stableSignature != a2.stableSignature)
    }

    @Test
    fun dynamicQueryAndCandidateTimesDoNotChangeStableSignature() {
        // Same outcome; differ only in source query timestamps and candidate event times.
        val base = ForegroundSourceSnapshot(
            source = "USAGE_EVENTS", status = ForegroundSourceStatus.AVAILABLE,
            packageName = "com.ss.android.ugc.aweme", vote = ForegroundVote.BLACKLIST,
            capturedAtMs = fixedNow, queryStartedAtMs = fixedNow - 5L, queryFinishedAtMs = fixedNow - 4L,
            candidates = listOf(
                ForegroundCandidate(
                    packageName = "com.ss.android.ugc.aweme",
                    eventName = "RESUME", eventTimeMs = fixedNow - 100L,
                    lastTimeUsedMs = fixedNow - 200L, lastTimeVisibleMs = fixedNow - 150L
                )
            ),
            selectionReason = "newest_resume", unavailableReason = null, failureClass = null
        )
        val t1 = base.copy(
            capturedAtMs = fixedNow + 1_000L,
            queryStartedAtMs = fixedNow + 995L,
            queryFinishedAtMs = fixedNow + 996L,
            candidates = listOf(
                ForegroundCandidate(
                    packageName = "com.ss.android.ugc.aweme",
                    eventName = "RESUME", eventTimeMs = fixedNow + 900L,
                    lastTimeUsedMs = fixedNow + 800L, lastTimeVisibleMs = fixedNow + 850L
                )
            )
        )
        val cycle1 = ForegroundDiagnosticCycle(reportId = "FG-X", checkedAtMs = fixedNow)
        cycle1.updateUsageEvents(base)
        cycle1.updateUsageStats(usageStatsSnapshot(packageName = "com.ss.android.ugc.aweme"))
        cycle1.updateAccessibility(accessibilitySnapshot(packageName = "com.ss.android.ugc.aweme"))
        val cycle2 = ForegroundDiagnosticCycle(reportId = "FG-X", checkedAtMs = fixedNow)
        cycle2.updateUsageEvents(t1)
        cycle2.updateUsageStats(usageStatsSnapshot(packageName = "com.ss.android.ugc.aweme"))
        cycle2.updateAccessibility(accessibilitySnapshot(packageName = "com.ss.android.ugc.aweme"))
        val outcome = ForegroundReportOutcome(
            effectivePackageName = "com.ss.android.ugc.aweme",
            effectivePackageBlacklisted = true,
            diagnosticRecommendation = "LOCK_BY_DIAGNOSTIC_MAJORITY",
            businessAction = ForegroundBusinessAction.REQUEST_LOCK,
            businessReason = "BLACKLIST_DND_LOCKED",
            allowanceState = "ALLOW_NONE",
            guardPassed = true,
            visibilityPassed = true
        )
        val a1 = ForegroundReportAssessmentFactory.assess(cycle1, outcome)
        val a2 = ForegroundReportAssessmentFactory.assess(cycle2, outcome)
        assertEquals("dynamic query / candidate timestamps must NOT change stableSignature: '${a1.stableSignature}' vs '${a2.stableSignature}'",
            a1.stableSignature, a2.stableSignature)
    }

    // ──────────────── R1-3 RED tests (UNEXPLAINED_MISSED_LOCK strict conditions) ────────────────
    // Per R1 review FINAL_REVIEW.md §R1-3: UNEXPLAINED_MISSED_LOCK must require
    // ALL 7 conditions (NO_LOCK, blacklisted=true, recommendation=LOCK_*,
    // businessReason not in known sets, allowanceIsInactive, guardPassed=true,
    // visibilityPassed=true). NOT_DECIDED must never become missed lock.

    @Test
    fun nonBlacklistedUnknownNoLockIsNotMissedLock() {
        val cycle = agreedCycle()
        val outcome = ForegroundReportOutcome(
            effectivePackageName = "com.bbk.launcher2",
            effectivePackageBlacklisted = false,
            diagnosticRecommendation = "LOCK_BY_DIAGNOSTIC_MAJORITY",
            businessAction = ForegroundBusinessAction.NO_LOCK,
            businessReason = "UNMAPPED_NO_LOCK_REASON",
            allowanceState = "ALLOW_NONE",
            guardPassed = true,
            visibilityPassed = true
        )
        val decision = ForegroundReportGate().evaluate(cycle, outcome, fixedNow)
        assertEquals(ForegroundEnforcementDisposition.NOT_REQUIRED, decision.assessment.enforcementDisposition)
        assertTrue("non-blacklisted NO_LOCK must not fire MISSED trigger: ${decision.triggers}",
            decision.triggers.none { it == ForegroundReportTrigger.MISSED_LOCK_STARTED || it == ForegroundReportTrigger.MISSED_LOCK_CHANGED })
    }

    @Test
    fun guardRejectedUnknownReasonIsNotMissedLock() {
        val cycle = agreedCycle()
        val outcome = ForegroundReportOutcome(
            effectivePackageName = "com.ss.android.ugc.aweme",
            effectivePackageBlacklisted = true,
            diagnosticRecommendation = "LOCK_BY_DIAGNOSTIC_MAJORITY",
            businessAction = ForegroundBusinessAction.NO_LOCK,
            businessReason = "UNMAPPED_NO_LOCK_REASON",
            allowanceState = "ALLOW_NONE",
            guardPassed = false,
            visibilityPassed = true
        )
        val decision = ForegroundReportGate().evaluate(cycle, outcome, fixedNow)
        assertEquals(ForegroundEnforcementDisposition.NOT_REQUIRED, decision.assessment.enforcementDisposition)
        assertTrue("guardPassed=false must suppress MISSED trigger: ${decision.triggers}",
            decision.triggers.none { it == ForegroundReportTrigger.MISSED_LOCK_STARTED || it == ForegroundReportTrigger.MISSED_LOCK_CHANGED })
    }

    @Test
    fun visibilityRejectedUnknownReasonIsNotMissedLock() {
        val cycle = agreedCycle()
        val outcome = ForegroundReportOutcome(
            effectivePackageName = "com.ss.android.ugc.aweme",
            effectivePackageBlacklisted = true,
            diagnosticRecommendation = "LOCK_BY_DIAGNOSTIC_MAJORITY",
            businessAction = ForegroundBusinessAction.NO_LOCK,
            businessReason = "UNMAPPED_NO_LOCK_REASON",
            allowanceState = "ALLOW_NONE",
            guardPassed = true,
            visibilityPassed = false
        )
        val decision = ForegroundReportGate().evaluate(cycle, outcome, fixedNow)
        assertEquals(ForegroundEnforcementDisposition.NOT_REQUIRED, decision.assessment.enforcementDisposition)
        assertTrue("visibilityPassed=false must suppress MISSED trigger: ${decision.triggers}",
            decision.triggers.none { it == ForegroundReportTrigger.MISSED_LOCK_STARTED || it == ForegroundReportTrigger.MISSED_LOCK_CHANGED })
    }

    @Test
    fun strictUnknownNoLockWithAllGuardsPassedIsMissedLock() {
        val cycle = agreedCycle()
        val outcome = ForegroundReportOutcome(
            effectivePackageName = "com.ss.android.ugc.aweme",
            effectivePackageBlacklisted = true,
            diagnosticRecommendation = "LOCK_BY_DIAGNOSTIC_MAJORITY",
            businessAction = ForegroundBusinessAction.NO_LOCK,
            businessReason = "UNMAPPED_NO_LOCK_REASON",
            allowanceState = "ALLOW_NONE",
            guardPassed = true,
            visibilityPassed = true
        )
        val decision = ForegroundReportGate().evaluate(cycle, outcome, fixedNow)
        assertEquals(ForegroundEnforcementDisposition.UNEXPLAINED_MISSED_LOCK, decision.assessment.enforcementDisposition)
        assertEquals(ForegroundReportEmission.FULL, decision.emission)
        assertTrue("MISSED_LOCK_STARTED must fire when all 7 conditions hold: ${decision.triggers}",
            decision.triggers.contains(ForegroundReportTrigger.MISSED_LOCK_STARTED))
    }

    @Test
    fun sourceProblemAndMissedLockHaveConsistentTriggersAndFormatterText() {
        val gate = ForegroundReportGate()
        // Round 1: introduce a source dispute first.
        val cycle1 = newCycle(
            events = eventsSnapshot(packageName = "com.ss.android.ugc.aweme"),
            usageStats = usageStatsSnapshot(packageName = "com.android.launcher"),
            accessibility = accessibilitySnapshot(packageName = "com.ss.android.ugc.aweme")
        )
        val outcome = ForegroundReportOutcome(
            effectivePackageName = "com.ss.android.ugc.aweme",
            effectivePackageBlacklisted = true,
            diagnosticRecommendation = "LOCK_BY_DIAGNOSTIC_MAJORITY",
            businessAction = ForegroundBusinessAction.NO_LOCK,
            businessReason = "UNMAPPED_NO_LOCK_REASON",
            allowanceState = "ALLOW_NONE",
            guardPassed = true,
            visibilityPassed = true
        )
        gate.evaluate(cycle1, outcome, fixedNow)

        // Round 2: sources recover (agreement) but business reason is still
        // the unmapped one with all 7 conditions. R2-3 option A: round 1's
        // missed-lock signature was stamped on the dispute cycle (lastMissedLockSignature
        // is tracked independently of source problem state); round 2's signature
        // differs (sources now agree), so the missed lock fires CHANGED.
        // Decision must include both MISSED_LOCK_CHANGED and SOURCE_RECOVERED
        // (per V2-R2 §5.1 fixed order) and the formatter must show both:
        // no orphan anomaly text without MISSED trigger, no orphan MISSED
        // trigger without anomaly text.
        val cycle2 = agreedCycle()
        val decision = gate.evaluate(cycle2, outcome, fixedNow + 5_000L)
        assertEquals(
            "MISSED_LOCK_CHANGED + SOURCE_RECOVERED in V2-R2 §5.1 fixed order, got=${decision.triggers}",
            listOf(
                ForegroundReportTrigger.MISSED_LOCK_CHANGED,
                ForegroundReportTrigger.SOURCE_RECOVERED
            ),
            decision.triggers
        )
        assertEquals(ForegroundEnforcementDisposition.UNEXPLAINED_MISSED_LOCK,
            decision.assessment.enforcementDisposition)

        val lines = ForegroundReportFormatter.format(cycle2, outcome, decision, fixedNow + 5_000L)
        val joined = lines.joinToString("\n")
        // Formatter must surface both the explicit anomaly text AND the
        // trigger label for MISSED_LOCK_CHANGED (not STARTED — lastMissedLockSignature
        // was stamped in round 1).
        assertTrue("trigger label must appear: $joined", joined.contains("MISSED_LOCK_CHANGED"))
        assertTrue("anomaly text must appear: $joined",
            joined.contains("异常=按照当前规则本应锁屏，但未发起锁屏请求"))
    }

    // ──────────────── R1-4 RED tests (Formatter single source of truth + line numbers) ────────────────
    // Per R1 review FINAL_REVIEW.md §R1-4: Formatter must use the
    // ForegroundReportDecision fields directly (not re-call Factory), and the
    // [前台汇报][line/total] block must number 1..12 with denominator 12 (not 11).

    @Test
    fun fullReportLineNumbersMatchActualBlockSize() {
        val gate = ForegroundReportGate()
        val cycle = agreedCycle()
        val outcome = ForegroundReportOutcome(
            effectivePackageName = "com.ss.android.ugc.aweme",
            effectivePackageBlacklisted = true,
            diagnosticRecommendation = "LOCK_BY_DIAGNOSTIC_MAJORITY",
            businessAction = ForegroundBusinessAction.REQUEST_LOCK,
            businessReason = "BLACKLIST_DND_LOCKED",
            allowanceState = "ALLOW_NONE",
            guardPassed = true,
            visibilityPassed = true
        )
        val decision = gate.evaluate(cycle, outcome, fixedNow)
        assertEquals(ForegroundReportEmission.FULL, decision.emission)
        val lines = ForegroundReportFormatter.format(cycle, outcome, decision, fixedNow)
        // Filter to main [前台汇报] lines (exclude [前台争议明细] and any optional).
        val mainLines = lines.filter { it.contains("[前台汇报]") }
        assertEquals("base FULL block must have 12 [前台汇报] lines (1 + 3 sources + 8 fixed), got=${mainLines.size}",
            12, mainLines.size)
        // Each line's [i/total] must be self-consistent: total=12 for every line.
        val pattern = Regex("""\[(\d+)/(\d+)]""")
        for (line in mainLines) {
            val m = pattern.find(line) ?: error("missing [i/total] in line: $line")
            val (idx, total) = m.destructured
            assertEquals("denominator must be 12 for every [前台汇报] line, got=[$idx/$total] in: $line",
                "12", total)
        }
        // Index sequence 1..12, no gaps.
        val indices = mainLines.map { pattern.find(it)!!.destructured.component1().toInt() }
        assertEquals((1..12).toList(), indices)
        // Last line must be [12/12], not [12/11].
        val last = mainLines.last()
        assertTrue("last [前台汇报] line must be [12/12], got: $last", last.contains("[12/12]"))
    }

    @Test
    fun formatterUsesDecisionAssessmentWithoutReassessment() {
        val gate = ForegroundReportGate()
        val cycle = agreedCycle()
        val outcome = ForegroundReportOutcome(
            effectivePackageName = "com.ss.android.ugc.aweme",
            effectivePackageBlacklisted = true,
            diagnosticRecommendation = "LOCK_BY_DIAGNOSTIC_MAJORITY",
            businessAction = ForegroundBusinessAction.REQUEST_LOCK,
            businessReason = "BLACKLIST_DND_LOCKED",
            allowanceState = "ALLOW_NONE",
            guardPassed = true,
            visibilityPassed = true
        )
        val realDecision = gate.evaluate(cycle, outcome, fixedNow)
        // Build a tampered decision whose assessment points to a fake disposition
        // and relation that the real Factory would never produce. The Formatter
        // must surface the tampered values (proving it consumes decision.* and
        // does NOT re-call Factory).
        val tamperedAssessment = realDecision.assessment.copy(
            sourceRelation = ForegroundSourceRelation.ALL_UNAVAILABLE,
            enforcementDisposition = ForegroundEnforcementDisposition.ALREADY_ENFORCED
        )
        val tampered = realDecision.copy(assessment = tamperedAssessment)
        val lines = ForegroundReportFormatter.format(cycle, outcome, tampered, fixedNow)
        val joined = lines.joinToString("\n")
        assertTrue("Formatter must surface decision's tampered sourceRelation=ALL_UNAVAILABLE, got: $joined",
            joined.contains("来源关系=ALL_UNAVAILABLE"))
        assertTrue("Formatter must surface decision's tampered disposition=ALREADY_ENFORCED, got: $joined",
            joined.contains("业务处置=ALREADY_ENFORCED"))
    }

    @Test
    fun recoveryFormatterUsesDecisionStartedAtAfterGateStateCleared() {
        val gate = ForegroundReportGate()
        val outcome = ForegroundReportOutcome(
            effectivePackageName = "com.ss.android.ugc.aweme",
            effectivePackageBlacklisted = true,
            diagnosticRecommendation = "LOCK_BY_DIAGNOSTIC_MAJORITY",
            businessAction = ForegroundBusinessAction.NO_LOCK,
            businessReason = "INTERNAL_DIAG_ONLY",
            allowanceState = "ALLOW_NONE",
            guardPassed = true,
            visibilityPassed = true
        )
        // Round 1: introduce a source dispute.
        val cycle1 = newCycle(
            events = eventsSnapshot(packageName = "com.ss.android.ugc.aweme", vote = ForegroundVote.BLACKLIST),
            usageStats = usageStatsSnapshot(packageName = "com.android.launcher", vote = ForegroundVote.NON_BLACKLIST),
            accessibility = accessibilitySnapshot(packageName = "com.ss.android.ugc.aweme", vote = ForegroundVote.BLACKLIST)
        )
        val started = gate.evaluate(cycle1, outcome, fixedNow)
        val conflictStartedAt = started.sourceProblemStartedAtMs
        assertTrue("sourceProblemStartedAtMs must be set on the dispute decision", conflictStartedAt != null)

        // Round 2: sources recover. decision.sourceProblemStartedAtMs must carry
        // the original startedAt even though the gate's internal state is cleared.
        val cycle2 = agreedCycle()
        val recovery = gate.evaluate(cycle2, outcome, fixedNow + 5_000L)
        assertEquals(ForegroundReportEmission.FULL, recovery.emission)
        assertEquals(conflictStartedAt, recovery.sourceProblemStartedAtMs)

        val lines = ForegroundReportFormatter.format(cycle2, outcome, recovery, fixedNow + 5_000L)
        val joined = lines.joinToString("\n")
        // R2-3 option A: lastMissedLockSignature is tracked independently of
        // source problem state, so round 1's missed-lock stamp survives into
        // round 2. Round 2's stableSignature differs (sources now agree),
        // so the missed lock fires CHANGED, not STARTED. Decision triggers
        // must be the V2-R2 §5.1 fixed order: [MISSED_LOCK_CHANGED,
        // SOURCE_RECOVERED]. The full block is 12 base + 2 anomaly
        // (UNEXPLAINED_MISSED_LOCK) = 14. The formatter must not need
        // gate.lastConflictStartedAtMs().
        //
        // Precision assertion (R2_NOTES §R2-3 §6): exact trigger order, not
        // unordered contains — same shape as
        // [recoveryAfterTrackedMissedLockUsesConsistentChangedAndRecoveredOrder].
        assertEquals(
            "recovery decision must carry MISSED_LOCK_CHANGED + SOURCE_RECOVERED in V2-R2 §5.1 fixed order, got=${recovery.triggers}",
            listOf(
                ForegroundReportTrigger.MISSED_LOCK_CHANGED,
                ForegroundReportTrigger.SOURCE_RECOVERED
            ),
            recovery.triggers
        )
        assertTrue("MISSED_LOCK_CHANGED trigger label must appear in formatter output: $joined",
            joined.contains("MISSED_LOCK_CHANGED"))
        assertTrue("SOURCE_RECOVERED trigger label must appear in formatter output: $joined",
            joined.contains("SOURCE_RECOVERED"))
        val mainLines = lines.filter { it.contains("[前台汇报]") }
        assertTrue("base + anomaly block must end with [14/14], got last: ${mainLines.lastOrNull()}",
            mainLines.lastOrNull()?.contains("[14/14]") == true)
    }

    @Test
    fun anomalyReportLineNumbersMatchActualBlockSize() {
        val gate = ForegroundReportGate()
        val cycle = agreedCycle()
        val outcome = ForegroundReportOutcome(
            effectivePackageName = "com.ss.android.ugc.aweme",
            effectivePackageBlacklisted = true,
            diagnosticRecommendation = "LOCK_BY_DIAGNOSTIC_MAJORITY",
            businessAction = ForegroundBusinessAction.NO_LOCK,
            businessReason = "INTERNAL_DIAG_ONLY",
            allowanceState = "ALLOW_NONE",
            guardPassed = true,
            visibilityPassed = true
        )
        val decision = gate.evaluate(cycle, outcome, fixedNow)
        val lines = ForegroundReportFormatter.format(cycle, outcome, decision, fixedNow)
        val mainLines = lines.filter { it.contains("[前台汇报]") }
        // UNEXPLAINED_MISSED_LOCK adds 2 anomaly lines → 12 + 2 = 14 base lines.
        assertEquals("anomaly FULL block must have 14 [前台汇报] lines (12 + 2 anomaly), got=${mainLines.size}",
            14, mainLines.size)
        val pattern = Regex("""\[(\d+)/(\d+)]""")
        for (line in mainLines) {
            val m = pattern.find(line) ?: error("missing [i/total] in line: $line")
            val total = m.destructured.component2()
            assertEquals("denominator must be 14 in anomaly report, got=[$total] in: $line", "14", total)
        }
        val last = mainLines.last()
        assertTrue("last line must be [14/14], got: $last", last.contains("[14/14]"))
    }

    @Test
    fun alreadyEnforcedReportLineNumbersMatchActualBlockSize() {
        val gate = ForegroundReportGate()
        val cycle = agreedCycle()
        val outcome = ForegroundReportOutcome(
            effectivePackageName = "com.ss.android.ugc.aweme",
            effectivePackageBlacklisted = true,
            diagnosticRecommendation = "LOCK_BY_DIAGNOSTIC_MAJORITY",
            businessAction = ForegroundBusinessAction.NO_LOCK,
            businessReason = "LOCK_ALREADY_ATTACHED",
            allowanceState = "LOCK_ATTACHED",
            guardPassed = true,
            visibilityPassed = true
        )
        // Force an ALREADY_ENFORCED FULL by combining a source dispute that
        // would otherwise keep things silent — use a cycle that introduces a
        // real source dispute, then format with a tampered disposition.
        val decision = gate.evaluate(cycle, outcome, fixedNow)
        // Construct an ALREADY_ENFORCED FULL decision via copy().
        val forcedDecision = decision.copy(
            emission = ForegroundReportEmission.FULL,
            assessment = decision.assessment.copy(
                enforcementDisposition = ForegroundEnforcementDisposition.ALREADY_ENFORCED
            )
        )
        val lines = ForegroundReportFormatter.format(cycle, outcome, forcedDecision, fixedNow)
        val mainLines = lines.filter { it.contains("[前台汇报]") }
        // ALREADY_ENFORCED adds 2 (当前锁定状态 + 最终动作) → 12 + 2 = 14 lines.
        assertEquals("ALREADY_ENFORCED FULL block must have 14 [前台汇报] lines, got=${mainLines.size}",
            14, mainLines.size)
        val pattern = Regex("""\[(\d+)/(\d+)]""")
        for (line in mainLines) {
            val m = pattern.find(line) ?: error("missing [i/total] in line: $line")
            val total = m.destructured.component2()
            assertEquals("denominator must be 14 in ALREADY_ENFORCED report, got=[$total] in: $line", "14", total)
        }
        assertTrue("last line must be [14/14], got: ${mainLines.last()}", mainLines.last().contains("[14/14]"))
    }

    @Test
    fun noneDecisionFormatsToEmptyList() {
        val cycle = agreedCycle()
        // LOCK_ALREADY_ATTACHED + all sources agreeing → V2-R2 silent (NONE).
        val outcome = ForegroundReportOutcome(
            effectivePackageName = "com.ss.android.ugc.aweme",
            effectivePackageBlacklisted = true,
            diagnosticRecommendation = "LOCK_BY_DIAGNOSTIC_MAJORITY",
            businessAction = ForegroundBusinessAction.NO_LOCK,
            businessReason = "LOCK_ALREADY_ATTACHED",
            allowanceState = "LOCK_ATTACHED",
            guardPassed = true,
            visibilityPassed = true
        )
        val gate = ForegroundReportGate()
        val decision = gate.evaluate(cycle, outcome, fixedNow)
        assertEquals(ForegroundReportEmission.NONE, decision.emission)
        val lines = ForegroundReportFormatter.format(cycle, outcome, decision, fixedNow)
        assertTrue("NONE decision must format to empty list (V2-R2 §8 Task 3)",
            lines.isEmpty())
    }

    // ──────────────── R2-1 RED tests (来源关系修正) ────────────────
    // Per FINAL_REVIEW_R2.md §R2-1: AMBIGUOUS without failure/unavailable metadata
    // is a dispute, NOT a fault. allUnavailable must exclude AMBIGUOUS without
    // metadata. Mixed blacklist vs non-blacklist votes (1:2 / 2:1) is a dispute.
    // Non-neutral INITIALIZING (query started but not finished, or with failure
    // metadata) is a fault, not a silent ALL_AGREE.

    @Test
    fun ambiguousSourceRelationIsDisagreementNotDegraded() {
        // R2-1 反例 A: 2 AVAILABLE 同 package + 1 AMBIGUOUS 无 metadata
        // → hasSourceDispute=true, hasSourceFault=false, sourceRelation=DISAGREEMENT
        val cycle = newCycle(
            events = eventsSnapshot(packageName = "com.ss.android.ugc.aweme"),
            usageStats = usageStatsSnapshot(packageName = "com.ss.android.ugc.aweme"),
            accessibility = accessibilitySnapshot(
                packageName = null,
                status = ForegroundSourceStatus.AMBIGUOUS
            )
        )
        val outcome = ForegroundReportOutcome(
            effectivePackageName = "com.ss.android.ugc.aweme",
            effectivePackageBlacklisted = true,
            diagnosticRecommendation = "LOCK_BY_DIAGNOSTIC_MAJORITY",
            businessAction = ForegroundBusinessAction.REQUEST_LOCK,
            businessReason = "BLACKLIST_DND_LOCKED",
            allowanceState = "ALLOW_NONE",
            guardPassed = true,
            visibilityPassed = true
        )
        val a = ForegroundReportAssessmentFactory.assess(cycle, outcome)
        assertTrue("AMBIGUOUS without metadata must be a dispute", a.hasSourceDispute)
        assertFalse("AMBIGUOUS without metadata must NOT be a fault", a.hasSourceFault)
        assertEquals(
            "AMBIGUOUS without metadata must be DISAGREEMENT, not DEGRADED",
            ForegroundSourceRelation.DISAGREEMENT,
            a.sourceRelation
        )
    }

    @Test
    fun allAmbiguousWithoutFailureIsDisagreementNotAllUnavailable() {
        // R2-1 反例 B: 3 AMBIGUOUS 无 metadata
        // → hasSourceFault=false, hasSourceDispute=true, sourceRelation=DISAGREEMENT
        // (NOT ALL_UNAVAILABLE)
        val cycle = newCycle(
            events = eventsSnapshot(
                packageName = null,
                status = ForegroundSourceStatus.AMBIGUOUS
            ),
            usageStats = usageStatsSnapshot(
                packageName = null,
                status = ForegroundSourceStatus.AMBIGUOUS
            ),
            accessibility = accessibilitySnapshot(
                packageName = null,
                status = ForegroundSourceStatus.AMBIGUOUS
            )
        )
        val outcome = ForegroundReportOutcome(
            effectivePackageName = null,
            effectivePackageBlacklisted = null,
            diagnosticRecommendation = "NO_DIAGNOSTIC_LOCK",
            businessAction = ForegroundBusinessAction.NO_LOCK,
            businessReason = "NO_FOREGROUND_RESOLVED",
            allowanceState = "UNKNOWN",
            guardPassed = null,
            visibilityPassed = null
        )
        val a = ForegroundReportAssessmentFactory.assess(cycle, outcome)
        assertFalse("all-AMBIGUOUS without metadata must NOT be a fault", a.hasSourceFault)
        assertTrue("all-AMBIGUOUS without metadata must be a dispute", a.hasSourceDispute)
        assertEquals(
            "all-AMBIGUOUS without metadata must be DISAGREEMENT, not ALL_UNAVAILABLE",
            ForegroundSourceRelation.DISAGREEMENT,
            a.sourceRelation
        )
    }

    @Test
    fun mixedBlacklistAndNonBlacklistVotesAreDisputeEvenWhenNotTie() {
        // R2-1 反例 C: 三源同 package, 1 BLACKLIST + 2 NON_BLACKLIST
        // → blacklistVotes=1, nonBlacklistVotes=2, isTie=false
        // → hasSourceDispute=true (mixed votes, not just tie)
        val cycle = newCycle(
            events = eventsSnapshot(
                packageName = "com.ss.android.ugc.aweme",
                vote = ForegroundVote.BLACKLIST
            ),
            usageStats = usageStatsSnapshot(
                packageName = "com.ss.android.ugc.aweme",
                vote = ForegroundVote.NON_BLACKLIST
            ),
            accessibility = accessibilitySnapshot(
                packageName = "com.ss.android.ugc.aweme",
                vote = ForegroundVote.NON_BLACKLIST
            )
        )
        val outcome = ForegroundReportOutcome(
            effectivePackageName = "com.ss.android.ugc.aweme",
            effectivePackageBlacklisted = true,
            diagnosticRecommendation = "LOCK_BY_DIAGNOSTIC_MAJORITY",
            businessAction = ForegroundBusinessAction.REQUEST_LOCK,
            businessReason = "BLACKLIST_DND_LOCKED",
            allowanceState = "ALLOW_NONE",
            guardPassed = true,
            visibilityPassed = true
        )
        val a = ForegroundReportAssessmentFactory.assess(cycle, outcome)
        assertEquals(1, a.blacklistVotes)
        assertEquals(2, a.nonBlacklistVotes)
        assertFalse("1:2 is NOT a tie", a.isTie)
        assertTrue(
            "mixed BLACKLIST+NON_BLACKLIST votes (1:2) must be a dispute, got hasSourceDispute=${a.hasSourceDispute}",
            a.hasSourceDispute
        )
    }

    @Test
    fun initializingQueryStartedButNotFinishedIsNotNeutral() {
        // R2-1 反例 D: INITIALIZING + queryStartedAtMs != null + queryFinishedAtMs == null
        // → 必须归 fault/dispute, 不能是 neutral (ALL_AGREE 静默)
        val initializing = ForegroundSourceSnapshot(
            source = "USAGE_STATS",
            status = ForegroundSourceStatus.INITIALIZING,
            packageName = null,
            vote = ForegroundVote.ABSTAIN,
            capturedAtMs = fixedNow,
            queryStartedAtMs = fixedNow - 5L,
            queryFinishedAtMs = null,
            candidates = emptyList(),
            selectionReason = null,
            unavailableReason = null,
            failureClass = null
        )
        val cycle = newCycle(usageStats = initializing)
        val outcome = ForegroundReportOutcome(
            effectivePackageName = "com.ss.android.ugc.aweme",
            effectivePackageBlacklisted = true,
            diagnosticRecommendation = "LOCK_BY_DIAGNOSTIC_MAJORITY",
            businessAction = ForegroundBusinessAction.REQUEST_LOCK,
            businessReason = "BLACKLIST_DND_LOCKED",
            allowanceState = "ALLOW_NONE",
            guardPassed = true,
            visibilityPassed = true
        )
        val a = ForegroundReportAssessmentFactory.assess(cycle, outcome)
        // 非中立 INITIALIZING: query 已开始但未完成 → 必须 NOT all-agree
        // 必须归 fault (因为 queryStartedAtMs 存在 = 真实查询过程中异常)
        assertTrue(
            "INITIALIZING with queryStartedAtMs but no queryFinishedAtMs must NOT be a silent neutral, " +
                "got hasSourceFault=${a.hasSourceFault} hasSourceDispute=${a.hasSourceDispute} " +
                "sourceRelation=${a.sourceRelation}",
            a.hasSourceFault || a.hasSourceDispute
        )
        assertFalse(
            "non-neutral INITIALIZING must NOT silently classify as ALL_AGREE",
            a.sourceRelation == ForegroundSourceRelation.ALL_AGREE &&
                !a.hasSourceFault && !a.hasSourceDispute
        )
    }

    // ──────────────── R2-2 RED tests (签名真实化) ────────────────
    // Per FINAL_REVIEW_R2.md §R2-2: stableSignatureOf MUST use the real
    // assessment fields (enforcementDisposition / hasSourceDispute / hasSourceFault)
    // computed by assess(), not re-derive them. Unknown allowance tokens must
    // be prefixed "OTHER:" (not returned raw).

    @Test
    fun stableSignatureUsesActualDispositionWhenAllowanceIsActive() {
        // R2-2 反例 A: strict missed-lock 全部条件满足，但 allowance 是已知 active
        // (FREE_TIME_ACTIVE) → 真正 disposition 是 NOT_REQUIRED, signature DISP 也必须是 NOT_REQUIRED
        // 不能再用 helper 算出 UNEXPLAINED_MISSED_LOCK
        val cycle = agreedCycle()
        val outcome = ForegroundReportOutcome(
            effectivePackageName = "com.ss.android.ugc.aweme",
            effectivePackageBlacklisted = true,
            diagnosticRecommendation = "LOCK_BY_DIAGNOSTIC_MAJORITY",
            businessAction = ForegroundBusinessAction.NO_LOCK,
            businessReason = "UNMAPPED_NO_LOCK_REASON",
            allowanceState = "FREE_TIME_ACTIVE",
            guardPassed = true,
            visibilityPassed = true
        )
        val a = ForegroundReportAssessmentFactory.assess(cycle, outcome)
        // 主 assessment 应该 NOT 是 UNEXPLAINED_MISSED_LOCK (allowance 已知 active)
        assertEquals(
            "FREE_TIME_ACTIVE is a known active allowance → NOT_REQUIRED, not UNEXPLAINED_MISSED_LOCK",
            ForegroundEnforcementDisposition.NOT_REQUIRED,
            a.enforcementDisposition
        )
        // signature DISP token 必须与 assessment.enforcementDisposition.name 完全一致
        assertTrue(
            "stableSignature must carry DISP[NOT_REQUIRED] matching assessment, got: ${a.stableSignature}",
            a.stableSignature.contains("DISP[NOT_REQUIRED]")
        )
        assertFalse(
            "stableSignature must NOT carry DISP[UNEXPLAINED_MISSED_LOCK] when allowance is active: ${a.stableSignature}",
            a.stableSignature.contains("DISP[UNEXPLAINED_MISSED_LOCK]")
        )
    }

    @Test
    fun pureSourceFaultSignatureDoesNotClaimSourceDispute() {
        // R2-2 反例 B: 仅 UNAVAILABLE, 同 package, 无 vote/AMBIGUOUS 争议
        // → hasSourceDispute=false, signature 没有 DSP[true] token
        val cycle = newCycle(
            events = ForegroundSourceSnapshot(
                source = "USAGE_EVENTS",
                status = ForegroundSourceStatus.UNAVAILABLE,
                packageName = "com.ss.android.ugc.aweme",
                vote = ForegroundVote.ABSTAIN,
                capturedAtMs = fixedNow,
                queryStartedAtMs = fixedNow - 5L,
                queryFinishedAtMs = fixedNow - 4L,
                candidates = emptyList(),
                selectionReason = null,
                unavailableReason = "NO_RECENT_FOREGROUND_EVENT",
                failureClass = null
            ),
            usageStats = ForegroundSourceSnapshot(
                source = "USAGE_STATS",
                status = ForegroundSourceStatus.UNAVAILABLE,
                packageName = "com.ss.android.ugc.aweme",
                vote = ForegroundVote.ABSTAIN,
                capturedAtMs = fixedNow,
                queryStartedAtMs = fixedNow - 3L,
                queryFinishedAtMs = fixedNow - 2L,
                candidates = emptyList(),
                selectionReason = null,
                unavailableReason = "USAGE_STATS_MANAGER_NULL_OR_EMPTY",
                failureClass = null
            ),
            accessibility = ForegroundSourceSnapshot(
                source = "ACCESSIBILITY",
                status = ForegroundSourceStatus.UNAVAILABLE,
                packageName = "com.ss.android.ugc.aweme",
                vote = ForegroundVote.ABSTAIN,
                capturedAtMs = fixedNow,
                queryStartedAtMs = fixedNow - 1L,
                queryFinishedAtMs = fixedNow,
                candidates = emptyList(),
                selectionReason = null,
                unavailableReason = "A11Y_INACTIVE",
                failureClass = null
            )
        )
        val outcome = ForegroundReportOutcome(
            effectivePackageName = "com.ss.android.ugc.aweme",
            effectivePackageBlacklisted = true,
            diagnosticRecommendation = "LOCK_BY_DIAGNOSTIC_MAJORITY",
            businessAction = ForegroundBusinessAction.REQUEST_LOCK,
            businessReason = "BLACKLIST_DND_LOCKED",
            allowanceState = "ALLOW_NONE",
            guardPassed = true,
            visibilityPassed = true
        )
        val a = ForegroundReportAssessmentFactory.assess(cycle, outcome)
        // 同 package, 仅 UNAVAILABLE → 纯 fault, 无 dispute
        assertFalse(
            "pure UNAVAILABLE fault with no package/vote dispute must NOT be a dispute, got hasSourceDispute=${a.hasSourceDispute}",
            a.hasSourceDispute
        )
        assertTrue("pure UNAVAILABLE fault must be flagged", a.hasSourceFault)
        // signature 没有 DSP[true] token
        assertFalse(
            "stableSignature must NOT contain DSP[true] for pure fault, got: ${a.stableSignature}",
            a.stableSignature.contains("DSP[true]")
        )
    }

    @Test
    fun unknownAllowanceTokenUsesOtherPrefix() {
        // R2-2 反例 C: stableAllowanceCategoryOf 对未知 token 必须以 "OTHER:" 开头
        val outcome = ForegroundReportOutcome(
            effectivePackageName = "com.ss.android.ugc.aweme",
            effectivePackageBlacklisted = true,
            diagnosticRecommendation = "LOCK_BY_DIAGNOSTIC_MAJORITY",
            businessAction = ForegroundBusinessAction.REQUEST_LOCK,
            businessReason = "BLACKLIST_DND_LOCKED",
            allowanceState = "FOO",
            guardPassed = true,
            visibilityPassed = true
        )
        val cycle = agreedCycle()
        val a = ForegroundReportAssessmentFactory.assess(cycle, outcome)
        // 未知 token "FOO" 必须以 "OTHER:" 开头
        assertTrue(
            "unknown allowance token must be prefixed with 'OTHER:', got: '${a.stableAllowanceCategory}'",
            a.stableAllowanceCategory.startsWith("OTHER:")
        )
        assertEquals(
            "sanitized FOO must produce OTHER:FOO",
            "OTHER:FOO",
            a.stableAllowanceCategory
        )
    }

    // ──────────────── R2-3 RED tests (Gate + Formatter option A 严格实施) ────────────────
    // Per FINAL_REVIEW_R2.md §R2-3: Gate must NOT suppress MISSED trigger when
    // source problem is active (no !hasSourceProblem guard). Formatter must
    // only show anomaly when triggers contain MISSED_LOCK_*. The recovery
    // cycle must use MISSED_LOCK_CHANGED + SOURCE_RECOVERED in fixed order.

    @Test
    fun activeSourceProblemAndStrictMissedLockEmitBothTriggersInOrder() {
        // R2-3 反例 A: 来源 dispute + 严格漏锁同时成立
        // → triggers == [MISSED_LOCK_STARTED, SOURCE_DISPUTE_STARTED], emission=FULL
        val gate = ForegroundReportGate()
        // 三源 package 争议 (源 dispute)
        val cycle = newCycle(
            events = eventsSnapshot(
                packageName = "com.ss.android.ugc.aweme",
                vote = ForegroundVote.BLACKLIST
            ),
            usageStats = usageStatsSnapshot(
                packageName = "com.android.launcher",
                vote = ForegroundVote.NON_BLACKLIST
            ),
            accessibility = accessibilitySnapshot(
                packageName = "com.ss.android.ugc.aweme",
                vote = ForegroundVote.BLACKLIST
            )
        )
        // outcome 同时满足严格漏锁 7 条件
        val outcome = ForegroundReportOutcome(
            effectivePackageName = "com.ss.android.ugc.aweme",
            effectivePackageBlacklisted = true,
            diagnosticRecommendation = "LOCK_BY_DIAGNOSTIC_MAJORITY",
            businessAction = ForegroundBusinessAction.NO_LOCK,
            businessReason = "UNMAPPED_NO_LOCK_REASON",
            allowanceState = "ALLOW_NONE",
            guardPassed = true,
            visibilityPassed = true
        )
        val decision = gate.evaluate(cycle, outcome, fixedNow)
        assertEquals(
            "active source problem + strict missed lock must emit both triggers in fixed V2-R2 §5.1 order, got=${decision.triggers}",
            listOf(
                ForegroundReportTrigger.MISSED_LOCK_STARTED,
                ForegroundReportTrigger.SOURCE_DISPUTE_STARTED
            ),
            decision.triggers
        )
        assertEquals(ForegroundReportEmission.FULL, decision.emission)
    }

    @Test
    fun sourceOnlyDecisionDoesNotRenderMissedLockAnomaly() {
        // R2-3 反例 B: Decision disposition=UNEXPLAINED_MISSED_LOCK 但 triggers 仅 SOURCE
        // → Formatter 输出不含 missed anomaly 文案
        val cycle = newCycle(
            events = eventsSnapshot(
                packageName = "com.ss.android.ugc.aweme",
                vote = ForegroundVote.BLACKLIST
            ),
            usageStats = usageStatsSnapshot(
                packageName = "com.android.launcher",
                vote = ForegroundVote.NON_BLACKLIST
            ),
            accessibility = accessibilitySnapshot(
                packageName = "com.ss.android.ugc.aweme",
                vote = ForegroundVote.BLACKLIST
            )
        )
        val outcome = ForegroundReportOutcome(
            effectivePackageName = "com.ss.android.ugc.aweme",
            effectivePackageBlacklisted = true,
            diagnosticRecommendation = "LOCK_BY_DIAGNOSTIC_MAJORITY",
            businessAction = ForegroundBusinessAction.NO_LOCK,
            businessReason = "UNMAPPED_NO_LOCK_REASON",
            allowanceState = "ALLOW_NONE",
            guardPassed = true,
            visibilityPassed = true
        )
        // 手工构造一个 decision: disposition=UNEXPLAINED_MISSED_LOCK 但 triggers 仅 SOURCE
        val realDecision = ForegroundReportGate().evaluate(cycle, outcome, fixedNow)
        val sourceOnlyDecision = realDecision.copy(
            assessment = realDecision.assessment.copy(
                enforcementDisposition = ForegroundEnforcementDisposition.UNEXPLAINED_MISSED_LOCK
            ),
            triggers = listOf(ForegroundReportTrigger.SOURCE_DISPUTE_STARTED)
        )
        val lines = ForegroundReportFormatter.format(cycle, outcome, sourceOnlyDecision, fixedNow)
        val joined = lines.joinToString("\n")
        // 关键: 没有 MISSED trigger, formatter 不得打印 missed anomaly 文案
        assertFalse(
            "Formatter must NOT render missed-lock anomaly when no MISSED trigger is present, got: $joined",
            joined.contains("异常=按照当前规则本应锁屏，但未发起锁屏请求")
        )
        assertFalse(
            "Formatter must NOT render '投票与动作关系=无法解释的不一致' when no MISSED trigger, got: $joined",
            joined.contains("投票与动作关系=无法解释的不一致")
        )
    }

    @Test
    fun recoveryAfterTrackedMissedLockUsesConsistentChangedAndRecoveredOrder() {
        // R2-3 反例 C: 连续 2 轮, 第一轮 source problem + missed → 第二轮 sources recover
        // → 第二轮 trigger 顺序含 MISSED_LOCK_CHANGED + SOURCE_RECOVERED
        val gate = ForegroundReportGate()
        val outcome = ForegroundReportOutcome(
            effectivePackageName = "com.ss.android.ugc.aweme",
            effectivePackageBlacklisted = true,
            diagnosticRecommendation = "LOCK_BY_DIAGNOSTIC_MAJORITY",
            businessAction = ForegroundBusinessAction.NO_LOCK,
            businessReason = "INTERNAL_DIAG_ONLY",
            allowanceState = "ALLOW_NONE",
            guardPassed = true,
            visibilityPassed = true
        )
        // 第一轮: source dispute + 严格 missed lock (同时成立)
        val cycle1 = newCycle(
            events = eventsSnapshot(
                packageName = "com.ss.android.ugc.aweme",
                vote = ForegroundVote.BLACKLIST
            ),
            usageStats = usageStatsSnapshot(
                packageName = "com.android.launcher",
                vote = ForegroundVote.NON_BLACKLIST
            ),
            accessibility = accessibilitySnapshot(
                packageName = "com.ss.android.ugc.aweme",
                vote = ForegroundReportTrigger.SOURCE_RECOVERED.let { ForegroundVote.BLACKLIST }
            )
        )
        val first = gate.evaluate(cycle1, outcome, fixedNow)
        // 第一轮: MISSED_LOCK_STARTED + SOURCE_DISPUTE_STARTED (option A, no suppression)
        assertTrue(
            "first round must include MISSED_LOCK_STARTED, got=${first.triggers}",
            first.triggers.contains(ForegroundReportTrigger.MISSED_LOCK_STARTED)
        )
        assertTrue(
            "first round must include SOURCE_DISPUTE_STARTED, got=${first.triggers}",
            first.triggers.contains(ForegroundReportTrigger.SOURCE_DISPUTE_STARTED)
        )

        // 第二轮: sources recover (all-agree), business reason 仍是未映射, 严格 missed lock
        // MISSED_LOCK signature 已记录 → 第二轮使用 MISSED_LOCK_CHANGED
        val cycle2 = agreedCycle()
        val second = gate.evaluate(cycle2, outcome, fixedNow + 5_000L)
        // 第二轮: SOURCE_RECOVERED + MISSED_LOCK_CHANGED (option A, fixed V2-R2 §5.1 order)
        assertTrue(
            "recovery round must include SOURCE_RECOVERED, got=${second.triggers}",
            second.triggers.contains(ForegroundReportTrigger.SOURCE_RECOVERED)
        )
        assertTrue(
            "recovery round must include MISSED_LOCK_CHANGED (lastMissedLockSignature was tracked), got=${second.triggers}",
            second.triggers.contains(ForegroundReportTrigger.MISSED_LOCK_CHANGED)
        )
        // 顺序: MISSED_LOCK_CHANGED 必须在 SOURCE_RECOVERED 之前 (V2-R2 §5.1)
        val missedIdx = second.triggers.indexOf(ForegroundReportTrigger.MISSED_LOCK_CHANGED)
        val recoveredIdx = second.triggers.indexOf(ForegroundReportTrigger.SOURCE_RECOVERED)
        assertTrue(
            "MISSED_LOCK_CHANGED must come before SOURCE_RECOVERED (V2-R2 §5.1 fixed order), got=${second.triggers}",
            missedIdx < recoveredIdx
        )
    }

    // ──────────────── R2-4 RED tests (Allowance 精确化 - 删 contains 模糊规则) ────────────────
    // Per FINAL_REVIEW_R2.md §R2-4: isAllowanceActive(...) used forbidden substring
    // matching (contains("BYPASS") / contains("OK")). R2-4 introduces the strict
    // white-list activeAllowanceCategories and asserts that unknown/failure strings
    // must NOT silently suppress a strict missed lock.

    @Test
    fun unknownAllowanceContainingBypassDoesNotSuppressStrictMissedLock() {
        // R2-4 反例 A: allowanceState="BYPASS_CHECK_FAILED" 含 "BYPASS" 子串;
        // businessReason="UNMAPPED_NO_LOCK_REASON" 不在 legitimateBypassReasons.
        // 旧实现用 contains("BYPASS") 误判为 active allowance → NOT_REQUIRED.
        // 新实现必须用白名单 token → 不在白名单 → INACTIVE → 严格漏锁成立.
        val cycle = agreedCycle()
        val outcome = ForegroundReportOutcome(
            effectivePackageName = "com.ss.android.ugc.aweme",
            effectivePackageBlacklisted = true,
            diagnosticRecommendation = "LOCK_BY_DIAGNOSTIC_MAJORITY",
            businessAction = ForegroundBusinessAction.NO_LOCK,
            businessReason = "UNMAPPED_NO_LOCK_REASON",
            allowanceState = "BYPASS_CHECK_FAILED",
            guardPassed = true,
            visibilityPassed = true
        )
        val assessment = ForegroundReportAssessmentFactory.assess(cycle, outcome)
        assertEquals(
            "BYPASS_CHECK_FAILED must NOT silently suppress a strict missed lock; got=${assessment.enforcementDisposition}",
            ForegroundEnforcementDisposition.UNEXPLAINED_MISSED_LOCK,
            assessment.enforcementDisposition
        )
    }

    @Test
    fun notOkAllowanceIsNotActive() {
        // R2-4 反例 B: allowanceState="NOT_OK" 含 "OK" 子串; businessReason 未知.
        // 旧实现用 contains("OK") 误判为 active. 新实现必须判为 inactive.
        val cycle = agreedCycle()
        val outcome = ForegroundReportOutcome(
            effectivePackageName = "com.ss.android.ugc.aweme",
            effectivePackageBlacklisted = true,
            diagnosticRecommendation = "LOCK_BY_DIAGNOSTIC_MAJORITY",
            businessAction = ForegroundBusinessAction.NO_LOCK,
            businessReason = "UNMAPPED_NO_LOCK_REASON",
            allowanceState = "NOT_OK",
            guardPassed = true,
            visibilityPassed = true
        )
        val assessment = ForegroundReportAssessmentFactory.assess(cycle, outcome)
        assertEquals(
            "NOT_OK must NOT be classified as active allowance (no contains('OK') shortcut), got=${assessment.enforcementDisposition}",
            ForegroundEnforcementDisposition.UNEXPLAINED_MISSED_LOCK,
            assessment.enforcementDisposition
        )
    }

    @Test
    fun knownTemporaryAllowanceRemainsLegitimateBypass() {
        // R2-4 正例: allowanceState 含 "TEMP_ACCESS_ACTIVE" 已知 token;
        // businessReason="TEMPORARY_ACCESS_ACTIVE" 是 legitimateBypassReason.
        // 已知 legitimate allowance 必须仍归 LEGITIMATE_BYPASS (不能回归误报 strict missed lock).
        val cycle = agreedCycle()
        val outcome = ForegroundReportOutcome(
            effectivePackageName = "com.ss.android.ugc.aweme",
            effectivePackageBlacklisted = true,
            diagnosticRecommendation = "LOCK_BY_DIAGNOSTIC_MAJORITY",
            businessAction = ForegroundBusinessAction.NO_LOCK,
            businessReason = "TEMPORARY_ACCESS_ACTIVE",
            allowanceState = "TEMP_ACCESS_ACTIVE",
            guardPassed = true,
            visibilityPassed = true
        )
        val assessment = ForegroundReportAssessmentFactory.assess(cycle, outcome)
        assertEquals(
            "known TEMPORARY allowance must remain LEGITIMATE_BYPASS, got=${assessment.enforcementDisposition}",
            ForegroundEnforcementDisposition.LEGITIMATE_BYPASS,
            assessment.enforcementDisposition
        )
        // stableAllowanceCategory 必须是白名单 token "TEMP_ACCESS"
        assertEquals(
            "stableAllowanceCategory must be TEMP_ACCESS, got=${assessment.stableAllowanceCategory}",
            "TEMP_ACCESS",
            assessment.stableAllowanceCategory
        )
    }
}
