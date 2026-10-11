package com.decikeeper.app.util

import com.decikeeper.app.data.AccessibilityForegroundSnapshot
import com.decikeeper.app.data.DefaultNormalEventRenderer
import com.decikeeper.app.data.EventClock
import com.decikeeper.app.data.ForegroundBusinessAction
import com.decikeeper.app.data.ForegroundCandidate
import com.decikeeper.app.data.ForegroundDiagnosticCycle
import com.decikeeper.app.data.ForegroundEnforcementDisposition
import com.decikeeper.app.data.ForegroundReportAssessment
import com.decikeeper.app.data.ForegroundReportAssessmentFactory
import com.decikeeper.app.data.ForegroundReportDecision
import com.decikeeper.app.data.ForegroundReportEmission
import com.decikeeper.app.data.ForegroundReportOutcome
import com.decikeeper.app.logging.LastForegroundEvidence
import com.decikeeper.app.data.ForegroundReportTrigger
import com.decikeeper.app.data.ForegroundSourceSnapshot
import com.decikeeper.app.data.ForegroundSourceStatus
import com.decikeeper.app.data.ForegroundVote
import com.decikeeper.app.data.FullForegroundReference
import com.decikeeper.app.data.NormalEvent
import com.decikeeper.app.data.NormalEventDraft
import com.decikeeper.app.data.NormalEvents
import java.text.SimpleDateFormat
import java.util.Date
import java.util.LinkedHashMap
import java.util.Locale





















/** Detail level of one atomic, normal foreground decision record. */
enum class ForegroundReportDetail { FULL, COMPACT }

/** A controlled application/launcher transition observed by existing foreground logic. */
data class ForegroundTransition(val from: String, val to: String) {
    fun render(): String = "$from→$to"
}

data class ForegroundResultEvidence(
    val foreground: String,
    val packageName: String,
    val action: String,
    val reason: String
)

data class ForegroundStateEvidence(
    val allowance: String,
    val appLock: String,
    val foregroundGuard: String
)

/** Source records are deliberately pre-rendered by the capture adapter; the formatter never votes. */
data class ForegroundSourceEvidence(
    val label: String,
    val status: String,
    val previous: String,
    val current: String,
    val selected: String
)

data class ForegroundLockEvidence(
    val requestId: String,
    val result: String,
    val duplicate: Boolean,
    val countedForPenalty: Boolean
)

data class ForegroundFreshConfirmation(
    val source: String,
    val confirmedAtMs: Long,
    val foregroundUnchanged: Boolean,
    val selected: Boolean
)

/** Presentation-only snapshot collected after the existing foreground decision is complete. */
data class ForegroundAtomicReport(
    val reportId: String,
    val detail: ForegroundReportDetail,
    val transition: ForegroundTransition?,
    val result: ForegroundResultEvidence,
    val state: ForegroundStateEvidence,
    val sources: List<ForegroundSourceEvidence>,
    val inference: String,
    val lock: ForegroundLockEvidence,
    val evidenceRef: String? = null,
    val freshConfirmation: ForegroundFreshConfirmation? = null,
    val traceId: String? = null,
    /** 日志保留等级（LONG 进永久账本，SHORT 进近期诊断）；null 表示不标注。 */
    val keep: String? = null
)

/** The selected UsageStats record from one completed detection cycle. */
data class ForegroundUsageStatsEvidence(val previous: String, val current: String)

/**
 * Keeps only the last stat that actually won existing UsageStats selection. It deliberately
 * never inspects neighbouring query candidates: they did not participate in the choice.
 */
class ForegroundUsageStatsSnapshotTracker {
    private var lastSelected: String? = null

    fun record(selected: ForegroundCandidate?): ForegroundUsageStatsEvidence {
        val current = selected?.let(::renderUsageStatsCandidate) ?: "NONE"
        val evidence = ForegroundUsageStatsEvidence(lastSelected ?: "NONE", current)
        lastSelected = current
        return evidence
    }

    fun recordSelectedFromCandidates(
        selectedPackageName: String?,
        candidates: List<ForegroundCandidate>
    ): ForegroundUsageStatsEvidence = record(
        candidates.firstOrNull { it.packageName == selectedPackageName }
    )

    private fun renderUsageStatsCandidate(candidate: ForegroundCandidate): String = buildString {
        append(candidate.packageName)
        candidate.lastTimeVisibleMs?.let { append(" lastTimeVisible=").append(it) }
        candidate.lastTimeUsedMs?.let { append(" lastTimeUsed=").append(it) }
        if (candidate.lastTimeVisibleMs == null && candidate.lastTimeUsedMs == null) {
            append(" eventAt=").append(candidate.eventTimeMs)
        }
    }
}

/**
 * Minimal logging state only: it counts equivalent reports suppressed by the existing gate and
 * emits their closing marker when that reported state changes. It has no policy authority.
 */
class ForegroundNormalEventStateTracker {
    private var activeStateKey: String? = null
    private var activePackage: String? = null
    private var activeIsDesktop: Boolean = false
    private var activeStateFields: Map<String, String> = emptyMap()
    private var startedAtMs: Long = 0L
    private var suppressedCount: Long = 0L

    /**
     * [stateFields] 只用于补充 FG_END 的可读字段（动作、原因等），不参与状态键比较，
     * 因此不会改变运行段的划分。
     */
    fun observe(
        stateKey: String,
        packageName: String,
        isDesktop: Boolean,
        hasFullDiagnostic: Boolean,
        nowMs: Long,
        clock: EventClock,
        stateFields: Map<String, String> = emptyMap()
    ): List<NormalEventDraft> {
        val previousKey = activeStateKey
        if (previousKey == null) {
            start(stateKey, packageName, isDesktop, nowMs, stateFields)
            return emptyList()
        }

        if (previousKey == stateKey) {
            if (!hasFullDiagnostic) suppressedCount++
            return emptyList()
        }

        val events = mutableListOf<NormalEventDraft>()
        val previousPackage = requireNotNull(activePackage)
        if (suppressedCount > 0L) {
            events += NormalEvents.create(
                type = "FG_END",
                clock = clock,
                fields = linkedMapOf<String, String>().apply {
                    put("foreground", previousPackage)
                    put("dwellMs", (nowMs - startedAtMs).coerceAtLeast(0L).toString())
                    put("suppressedCount", suppressedCount.toString())
                    putAll(activeStateFields)
                }
            )
        }
        if (!hasFullDiagnostic && activeIsDesktop != isDesktop) {
            events += ForegroundReportFormatter.formatForTransition(
                transition = ForegroundTransition(previousPackage, packageName),
                fullReport = null,
                clock = clock
            )
        }
        start(stateKey, packageName, isDesktop, nowMs, stateFields)
        return events
    }

    private fun start(
        stateKey: String,
        packageName: String,
        isDesktop: Boolean,
        nowMs: Long,
        stateFields: Map<String, String>
    ) {
        activeStateKey = stateKey
        activePackage = packageName
        activeIsDesktop = isDesktop
        activeStateFields = stateFields
        startedAtMs = nowMs
        suppressedCount = 0L
    }
}

/**
 * A request may leave through synchronous guards or an asynchronous window callback. This
 * tracker makes the normal result a one-shot settlement of that one request, not a prediction.
 */
class ForegroundLockResultSettlementTracker {
    private val settledRequestIds = mutableSetOf<String>()

    @Synchronized
    fun settle(
        requestId: String?,
        decisionRef: String?,
        packageName: String,
        result: String,
        reason: String,
        duplicate: Boolean,
        clock: EventClock,
        failureClass: String? = null,
        evidence: String? = null
    ): NormalEventDraft? {
        if (requestId.isNullOrBlank() || decisionRef.isNullOrBlank()) return null
        if (!settledRequestIds.add(requestId)) return null
        return ForegroundReportFormatter.formatLockResultEvent(
            requestId = requestId,
            decisionRef = decisionRef,
            packageName = packageName,
            result = result,
            reason = reason,
            duplicate = duplicate,
            failureClass = failureClass,
            evidence = evidence,
            clock = clock
        )
    }
}

/**
 * Production adapter between already-resolved Monitor facts and the normal-event transport.
 * It has no source-selection or lock-policy authority: [selectedSource], [outcome], and the
 * pre-cycle transition are supplied by Monitor; LockWindowManager only asks it to settle a
 * result that has already happened.
 */
class ForegroundNormalEventBridge(
    private val clock: EventClock,
    private val emit: (NormalEventDraft) -> Unit,
    private val lockSettlements: ForegroundLockResultSettlementTracker = ForegroundLockResultSettlementTracker()
) {
    private var lastFullReference: FullForegroundReference? = null

    fun emitFull(
        cycle: ForegroundDiagnosticCycle,
        outcome: ForegroundReportOutcome,
        selectedSource: String,
        requestId: String?,
        traceId: String?,
        transition: ForegroundTransition?
    ) {
        val decision = ForegroundReportDecision(
            emission = ForegroundReportEmission.FULL,
            triggers = emptyList(),
            assessment = ForegroundReportAssessmentFactory.assess(cycle, outcome),
            sourceProblemStartedAtMs = null
        )
        emitDecision(cycle, outcome, decision, selectedSource, requestId, traceId, transition)
    }

    fun emitDecision(
        cycle: ForegroundDiagnosticCycle,
        outcome: ForegroundReportOutcome,
        decision: ForegroundReportDecision,
        selectedSource: String,
        requestId: String?,
        traceId: String?,
        transition: ForegroundTransition?,
        /**
         * 完整报告的保留等级，由 [ForegroundDecisionRetention] 判定：DROP 丢弃，SHORT/LONG 输出并在
         * 报告头标注 `keep=`。null 表示不做取舍，按原样输出且不标注。
         */
        retention: ForegroundDecisionRetention.Level? = null
    ) {
        if (decision.emission == ForegroundReportEmission.CONTINUING_CONFLICT_SUMMARY) return
        val reference = lastFullReference
        val selected = cycle.sourceList().firstOrNull { isSelected(it.source, selectedSource) }
        val packageName = outcome.effectivePackageName
        val compactEligible = outcome.businessReason == "LOCK_ALREADY_ATTACHED" &&
            packageName != null &&
            reference?.packageName == packageName &&
            cycle.sourceList().all { it.status == ForegroundSourceStatus.AVAILABLE } &&
            !decision.assessment.hasSourceDispute &&
            !decision.assessment.hasSourceFault &&
            selected != null &&
            selected.currentEvidence != null &&
            selected.packageName == packageName

        if (decision.emission == ForegroundReportEmission.NONE && !compactEligible) return
        if (decision.emission == ForegroundReportEmission.FULL && !compactEligible &&
            retention == ForegroundDecisionRetention.Level.DROP
        ) {
            return
        }

        val full = reportFrom(cycle, outcome, selectedSource, requestId, traceId, transition)
        val report = if (compactEligible) {
            full.copy(
                reportId = requireNotNull(reference).reportId,
                detail = ForegroundReportDetail.COMPACT,
                transition = null,
                evidenceRef = reference.reportId,
                freshConfirmation = ForegroundFreshConfirmation(
                    source = selectedSource,
                    confirmedAtMs = requireNotNull(selected).capturedAtMs,
                    foregroundUnchanged = true,
                    selected = true
                )
            )
        } else if (retention != null && retention != ForegroundDecisionRetention.Level.DROP) {
            full.copy(keep = retention.name)
        } else {
            full
        }
        emit(ForegroundReportFormatter.formatAtomic(report, clock))
        if (report.detail == ForegroundReportDetail.FULL && packageName != null && selected != null) {
            lastFullReference = FullForegroundReference(
                reportId = report.reportId,
                packageName = packageName,
                selectedSource = selectedSource,
                confirmedAtMs = selected.capturedAtMs
            )
        }
    }

    fun emitTransition(transition: ForegroundTransition, fullReport: ForegroundAtomicReport? = null) {
        ForegroundReportFormatter.formatForTransition(transition, fullReport, clock).forEach(emit)
    }

    fun settleLock(
        requestId: String?,
        decisionRef: String?,
        packageName: String,
        result: String,
        reason: String,
        duplicate: Boolean,
        failureClass: String? = null
    ) {
        lockSettlements.settle(
            requestId = requestId,
            decisionRef = decisionRef,
            packageName = packageName,
            result = result,
            reason = reason,
            duplicate = duplicate,
            failureClass = failureClass,
            evidence = LastForegroundEvidence.render(),
            clock = clock
        )?.let(emit)
    }

    private fun reportFrom(
        cycle: ForegroundDiagnosticCycle,
        outcome: ForegroundReportOutcome,
        selectedSource: String,
        requestId: String?,
        traceId: String?,
        transition: ForegroundTransition?
    ): ForegroundAtomicReport {
        val action = if (outcome.businessAction == ForegroundBusinessAction.REQUEST_LOCK) "LOCK" else "ALLOW"
        val hasLockRequest = outcome.businessAction == ForegroundBusinessAction.REQUEST_LOCK
        return ForegroundAtomicReport(
            reportId = cycle.reportId,
            detail = ForegroundReportDetail.FULL,
            transition = transition,
            result = ForegroundResultEvidence(
                foreground = outcome.effectivePackageName ?: "NONE",
                packageName = outcome.effectivePackageName ?: "NONE",
                action = action,
                reason = outcome.businessReason
            ),
            state = ForegroundStateEvidence(
                allowance = outcome.allowanceState,
                appLock = if (hasLockRequest) "REQUESTED" else "UNCHANGED",
                foregroundGuard = outcome.guardPassed?.let { if (it) "PASSED" else "REJECTED" } ?: "UNKNOWN"
            ),
            sources = cycle.sourceList().map { sourceEvidence(it, selectedSource) },
            inference = outcome.diagnosticRecommendation,
            lock = ForegroundLockEvidence(
                requestId = if (hasLockRequest) requestId ?: "NONE" else "NONE",
                result = if (hasLockRequest) "REQUESTED" else "NOT_REQUESTED",
                duplicate = false,
                countedForPenalty = false
            ),
            traceId = traceId?.takeIf { it.isNotBlank() }
        )
    }

    private fun sourceEvidence(source: ForegroundSourceSnapshot, selectedSource: String): ForegroundSourceEvidence {
        val accessibilityPrevious = source.previousAccessibilitySnapshot?.let(::renderAccessibilitySnapshot)
        val accessibilityCurrent = source.currentAccessibilitySnapshot?.let(::renderAccessibilitySnapshot)
        return ForegroundSourceEvidence(
            label = when (source.source) {
                "USAGE_EVENTS" -> "UsageEvents"
                "USAGE_STATS" -> "UsageStats"
                "ACCESSIBILITY" -> "Accessibility"
                else -> source.source
            },
            status = normalSourceStatus(source),
            previous = accessibilityPrevious ?: source.previousEvidence
                ?: source.candidates.dropLast(1).lastOrNull()?.let(::renderCandidate) ?: "NONE",
            current = accessibilityCurrent ?: source.currentEvidence
                ?: source.candidates.lastOrNull()?.let(::renderCandidate) ?: source.packageName ?: "NONE",
            selected = if (isSelected(source.source, selectedSource)) "curr" else "not_selected"
        )
    }

    private fun isSelected(source: String, selectedSource: String): Boolean =
        (source == "USAGE_EVENTS" && selectedSource == "EVENTS") ||
            (source == "USAGE_STATS" && selectedSource == "USAGE_STATS") ||
            (source == "ACCESSIBILITY" && selectedSource == "ACCESSIBILITY")

    private fun normalSourceStatus(source: ForegroundSourceSnapshot): String = when (source.status) {
        ForegroundSourceStatus.AVAILABLE -> "AVAILABLE"
        ForegroundSourceStatus.UNAVAILABLE -> if (
            source.source == "ACCESSIBILITY" && source.unavailableReason == "A11Y_INACTIVE"
        ) "NO_PERMISSION" else "NO_DATA"
        ForegroundSourceStatus.INITIALIZING -> "NO_DATA"
        ForegroundSourceStatus.FAILED, ForegroundSourceStatus.AMBIGUOUS -> "ERROR"
    }

    private fun renderCandidate(candidate: ForegroundCandidate): String = buildString {
        append(DiagnosticTimestampFormatter.msWithRaw(candidate.eventTimeMs)).append(' ').append(candidate.eventName).append(' ').append(candidate.packageName)
        candidate.lastTimeVisibleMs?.let { append(" lastTimeVisible=").append(it) }
        candidate.lastTimeUsedMs?.let { append(" lastTimeUsed=").append(it) }
    }

    private fun renderAccessibilitySnapshot(snapshot: AccessibilityForegroundSnapshot): String = buildString {
        append("eventAtMs=").append(DiagnosticTimestampFormatter.msWithRaw(snapshot.eventAtMs))
        append(" handledAtMs=").append(DiagnosticTimestampFormatter.msWithRaw(snapshot.handledAtMs))
        append(" eventType=").append(snapshot.eventType ?: "NONE")
        append(" eventPackageName=").append(snapshot.eventPackageName ?: "NONE")
        append(" snapshotAtMs=").append(DiagnosticTimestampFormatter.msWithRaw(snapshot.snapshotAtMs))
        append(' ').append(snapshot.windowSummary)
    }
}

/**
 * [Diagnostics-only] Foreground diagnostic report formatter.
 *
 * V2-R2 §8 Task 3 contract — the public [format] entry point accepts the new
 * [ForegroundReportDecision] shape. The previous raw-[ForegroundReportEmission]
 * overload is preserved for internal helper / legacy callers but the production
 * path (MonitorService.recordFgDiagnosticOutcome) goes through the decision
 * overload and adds the NONE early-return at the call site.
 *
 * Fixed-field FULL report (one `[前台汇报][line/total]` block, V2-R2 §8 Task 3 Step 4):
 *
 *   FG-yyyyMMdd-HHmmss-NNNN [前台汇报][1/N] reportId=FG-...
 *     reportId / state / effectivePkg / blacklisted / allowance / guard / visibility
 *     UsageEvents(status, pkg, vote, query=...~..., reason, candidates=[...])
 *     UsageStats(status, pkg, vote, query=...~..., reason, candidates=[...])
 *     Accessibility(status, pkg, vote, query=...~..., reason, candidates=[...])
 *     报告触发原因=REQUEST_LOCK[,MISSED_LOCK_STARTED,...]   (V2-R2 §5.1 fixed order)
 *     动作=请求锁屏|未请求锁屏
 *     业务处置=<disposition name>
 *     三源当前判断: UsageEvents=...; UsageStats=...; Accessibility=...
 *     票数=BLACKLIST:N1 NON_BLACKLIST:N2 ABSTAIN:N3
 *     来源关系=<source relation name>
 *     放行状态=<allowance token>
 *     最终原因=<businessReason>
 *     异常=按照当前规则本应锁屏，但未发起锁屏请求   (UNEXPLAINED_MISSED_LOCK only)
 *     投票与动作关系=无法解释的不一致                (UNEXPLAINED_MISSED_LOCK only)
 *     当前锁定状态=锁屏界面已存在                   (ALREADY_ENFORCED only)
 *     最终动作=未重复请求锁屏                       (ALREADY_ENFORCED only)
 *
 * Continuation shape (one `[前台争议持续]` line every 30s while conflict persists):
 *
 *   FG-yyyyMMdd-HHmmss-NNNN [前台争议持续] duration=Ns UsageEvents=... UsageStats=... Accessibility=... blacklistVotes=N nonBlacklistVotes=M action=REQUEST_LOCK
 *
 * 争议明细 (full reports only, V1 prefix preserved):
 *   FG-yyyyMMdd-HHmmss-NNNN [前台争议明细] reason=...
 */
object ForegroundReportFormatter {

    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())

    private const val NORMAL_FOREGROUND_MAX_BYTES = 3 * 1024

    /**
     * Produces one immutable normal-event draft after foreground policy has already selected an
     * outcome. This formatter is intentionally presentation-only: [report] supplies the selected
     * source, result, state, and lock fact rather than asking the diagnostic model to decide again.
     */
    fun formatAtomic(report: ForegroundAtomicReport, clock: EventClock): NormalEventDraft {
        if (report.detail == ForegroundReportDetail.COMPACT) {
            require(!report.evidenceRef.isNullOrBlank()) { "COMPACT foreground reports require evidenceRef" }
            val confirmation = requireNotNull(report.freshConfirmation) {
                "COMPACT foreground reports require fresh confirmation"
            }
            require(confirmation.foregroundUnchanged && confirmation.selected) {
                "COMPACT foreground reports require current-cycle selected confirmation"
            }
        }

        val fields = LinkedHashMap<String, String>().apply {
            put("detail", report.detail.name)
            put("reportId", report.reportId)
            report.keep?.let { put("keep", it) }
            report.traceId?.let { put("traceId", it) }
            report.transition?.let { put("transition", it.render()) }
            if (report.detail == ForegroundReportDetail.COMPACT) {
                put("evidenceRef", requireNotNull(report.evidenceRef))
            }
        }
        val body = when (report.detail) {
            ForegroundReportDetail.FULL -> fullBody(report, compactValues = false)
            ForegroundReportDetail.COMPACT -> compactBody(report)
        }
        return boundedDraft(report, fields, body, clock)
    }

    /** A desktop/app transition without a diagnostic decision is its own single semantic event. */
    fun formatForTransition(
        transition: ForegroundTransition,
        fullReport: ForegroundAtomicReport?,
        clock: EventClock
    ): List<NormalEventDraft> = if (fullReport != null) {
        listOf(formatAtomic(fullReport, clock))
    } else {
        listOf(NormalEvents.create(
            type = "FOREGROUND_TRANSITION",
            clock = clock,
            fields = linkedMapOf("transition" to transition.render())
        ))
    }

    /** The asynchronous lock-window outcome correlated to its already-emitted FG_DECISION. */
    fun formatLockResultEvent(
        requestId: String,
        decisionRef: String,
        packageName: String,
        result: String,
        reason: String,
        duplicate: Boolean,
        clock: EventClock,
        failureClass: String? = null,
        evidence: String? = null
    ): NormalEventDraft = NormalEvents.create(
        type = "LOCK_RESULT",
        clock = clock,
        fields = linkedMapOf<String, String>().apply {
            put("requestId", requestId)
            put("decisionRef", decisionRef)
            put("packageName", packageName)
            put("result", result)
            put("reason", reason)
            put("duplicate", duplicate.toString())
            failureClass?.let { put("failureClass", it) }
            evidence?.let { put("evidence", it) }
        }
    )

    private fun fullBody(report: ForegroundAtomicReport, compactValues: Boolean): List<String> {
        val sourceByLabel = report.sources.associateBy { it.label }
        return buildList {
            add("[1 结果] foreground=${abbreviate(report.result.foreground, compactValues)} packageName=${abbreviate(report.result.packageName, compactValues)} action=${report.result.action} reason=${abbreviate(report.result.reason, compactValues)}")
            add("[2 状态] allowance=${abbreviate(report.state.allowance, compactValues)} appLock=${abbreviate(report.state.appLock, compactValues)} foregroundGuard=${abbreviate(report.state.foregroundGuard, compactValues)}")
            listOf("UsageEvents", "UsageStats", "Accessibility").forEachIndexed { index, label ->
                val source = sourceByLabel[label] ?: ForegroundSourceEvidence(label, "NO_DATA", "NONE", "NONE", "NONE")
                add("[${index + 3} $label] status=${source.status} prev={${abbreviate(source.previous, compactValues)}} curr={${abbreviate(source.current, compactValues)}} selected=${abbreviate(source.selected, compactValues)}")
            }
            add("[6 推理] ${abbreviate(report.inference, compactValues)}")
            add(lockLine(report, compactValues))
        }
    }

    private fun compactBody(report: ForegroundAtomicReport): List<String> {
        val confirmation = requireNotNull(report.freshConfirmation)
        return listOf(
            "[1 结果] foreground=${report.result.foreground} packageName=${report.result.packageName} action=${report.result.action} reason=${report.result.reason}",
            "[2 状态] allowance=${report.state.allowance} appLock=${report.state.appLock} foregroundGuard=${report.state.foregroundGuard}",
            "[3 证据] status=AVAILABLE foregroundUnchanged=${confirmation.foregroundUnchanged} lastConfirmedAt=${timeFormat.format(Date(confirmation.confirmedAtMs))} confirmedBy=${confirmation.source} selected=${confirmation.selected}",
            "[4 推理] ${report.inference}",
            lockLine(report, compactValues = true, lineNumber = 5)
        )
    }

    private fun lockLine(
        report: ForegroundAtomicReport,
        compactValues: Boolean,
        lineNumber: Int = 7
    ): String =
        "[$lineNumber 锁屏] requestId=${abbreviate(report.lock.requestId, compactValues)} result=${report.lock.result} duplicate=${report.lock.duplicate} countedForPenalty=${report.lock.countedForPenalty}"

    /**
     * A complete record must fit logcat without silently cutting bytes. First try the full
     * evidence, then abbreviate only expandable evidence and explicitly identify that operation.
     */
    private fun boundedDraft(
        report: ForegroundAtomicReport,
        originalFields: LinkedHashMap<String, String>,
        originalBody: List<String>,
        clock: EventClock
    ): NormalEventDraft {
        val normal = NormalEvents.create("FG_DECISION", clock, originalFields, originalBody)
        if (renderedBytes(normal) <= NORMAL_FOREGROUND_MAX_BYTES) return normal

        val compressedBody = if (report.detail == ForegroundReportDetail.FULL) {
            fullBody(report, compactValues = true)
        } else {
            compactBody(report).map { abbreviate(it, compactValues = true) }
        }
        val fields = LinkedHashMap(originalFields).apply {
            put("compressed", "true")
            put("omittedFields", "sourceDetails,inference")
        }
        // payloadBytes describes the final rendered payload, including its own field. Iterate to
        // the (short) decimal fixed point instead of reporting the pre-field byte count.
        var draft = NormalEvents.create("FG_DECISION", clock, fields, compressedBody)
        repeat(3) {
            val payloadBytes = renderedBytes(draft).toString()
            if (fields["payloadBytes"] == payloadBytes) return@repeat
            fields["payloadBytes"] = payloadBytes
            draft = NormalEvents.create("FG_DECISION", clock, fields, compressedBody)
        }
        require(renderedBytes(draft) <= NORMAL_FOREGROUND_MAX_BYTES) {
            "Required foreground evidence exceeds normal-event size budget"
        }
        return draft
    }

    private fun renderedBytes(draft: NormalEventDraft): Int = DefaultNormalEventRenderer.render(
        NormalEvent("foreground-size-check", "foreground-size-check", 1L, draft)
    ).toByteArray(Charsets.UTF_8).size

    private fun abbreviate(value: String, compactValues: Boolean): String {
        val normalized = value.replace('\n', ' ').replace('\r', ' ')
        if (!compactValues) return normalized
        return if (normalized.length <= 180) normalized else normalized.take(177) + "..."
    }

    /**
     * Legacy V1 entry point — kept for internal helper callers that still hold a
     * raw emission value. NOT used by the production MonitorService path; that
     * path uses the [ForegroundReportDecision] overload below.
     */
    fun format(
        cycle: ForegroundDiagnosticCycle,
        outcome: ForegroundReportOutcome,
        emission: ForegroundReportEmission,
        nowMs: Long,
        conflictStartedAtMs: Long = 0L
    ): List<String> {
        return when (emission) {
            ForegroundReportEmission.NONE -> emptyList()
            ForegroundReportEmission.FULL -> {
                // Legacy path: caller does not have a Decision. Synthesize a
                // minimal assessment via the Factory so the contract is
                // preserved. Production code (MonitorService) goes through
                // the Decision overload above and does NOT take this branch.
                val assessment = ForegroundReportAssessmentFactory.assess(cycle, outcome)
                formatFull(cycle, outcome, /*triggers*/ emptyList(), nowMs, assessment)
            }
            ForegroundReportEmission.CONTINUING_CONFLICT_SUMMARY ->
                listOf(formatContinuation(cycle, outcome, nowMs, conflictStartedAtMs))
        }
    }

    /**
     * V2-R2 §8 Task 3 Step 3 + R1-4 — main entry point consumes the
     * [ForegroundReportDecision] shape produced by [ForegroundReportGate.evaluate].
     * The Formatter is now strictly a renderer: it does NOT re-invoke
     * [ForegroundReportAssessmentFactory] — all assessment / disposition /
     * sourceRelation / stableAllowanceCategory / sourceProblemStartedAtMs
     * values are read directly from the [decision] object. The NONE decision
     * returns an empty list so callers can short-circuit log emission upstream.
     */
    fun format(
        cycle: ForegroundDiagnosticCycle,
        outcome: ForegroundReportOutcome,
        decision: ForegroundReportDecision,
        nowMs: Long,
        conflictStartedAtMs: Long = 0L
    ): List<String> = when (decision.emission) {
        ForegroundReportEmission.NONE -> emptyList()
        ForegroundReportEmission.FULL ->
            formatFull(cycle, outcome, decision.triggers, nowMs, decision.assessment)
        ForegroundReportEmission.CONTINUING_CONFLICT_SUMMARY ->
            listOf(formatContinuation(
                cycle, outcome, nowMs,
                decision.sourceProblemStartedAtMs ?: conflictStartedAtMs
            ))
    }

    /**
     * V2-R2 §8 Task 3 Step 4 + R1-4 FULL report renderer.
     *
     * R1-4: line/total is computed from the actual block composition
     * (1 header + sources.size source sections + 8 fixed = 12 base lines),
     * not from a hard-coded 11. Optional anomaly (+2) and ALREADY_ENFORCED
     * (+2) blocks extend the denominator accordingly. The [assessment]
     * argument is taken from the Decision; the Formatter does NOT re-call
     * the Factory.
     */
    private fun formatFull(
        cycle: ForegroundDiagnosticCycle,
        outcome: ForegroundReportOutcome,
        triggers: List<ForegroundReportTrigger>,
        nowMs: Long,
        assessment: ForegroundReportAssessment
    ): List<String> {
        val sources = cycle.sourceList()
        val conflictDetail = buildConflictDetail(cycle, outcome, sources)
        val timeline = cycle.timelineSnapshot(nowMs).takeLast(20)
        val disposition = assessment.enforcementDisposition
        val isFault = sources.any {
            it.status == ForegroundSourceStatus.FAILED ||
                it.status == ForegroundSourceStatus.AMBIGUOUS
        }
        val allUnavailable = sources.all { it.status == ForegroundSourceStatus.UNAVAILABLE }
        val isConflict = conflictDetail != null
        val showTimeline = timeline.isNotEmpty() && (isConflict || isFault || allUnavailable)
        // V2-R2 §8 Task 3 Step 4 + Step 5: ONLY UNEXPLAINED_MISSED_LOCK surfaces
        // the explicit anomaly / vote-vs-action mismatch lines, AND only when
        // the Decision actually carries a MISSED trigger. This is the R2-3
        // option-A contract: trigger label and text stay in sync — a Decision
        // whose disposition is UNEXPLAINED but whose triggers list has no
        // MISSED_LOCK_* (e.g. a tampered test fixture) MUST NOT print the
        // anomaly block. Every other disposition MUST NOT print the anomaly.
        val hasMissedTrigger = triggers.any {
            it == ForegroundReportTrigger.MISSED_LOCK_STARTED ||
                it == ForegroundReportTrigger.MISSED_LOCK_CHANGED
        }
        val showAnomaly =
            disposition == ForegroundEnforcementDisposition.UNEXPLAINED_MISSED_LOCK &&
                hasMissedTrigger
        val showAlreadyEnforcedState =
            disposition == ForegroundEnforcementDisposition.ALREADY_ENFORCED
        val showConflictDetail = conflictDetail != null

        // R1-4: base block = 1 (header) + sources.size (3) + 8 fixed =
        //   报告触发原因 + 动作 + 业务处置 + 三源当前判断 + 票数 + 来源关系 + 放行状态 + 最终原因
        // = 12 lines. Optional blocks add 2 (anomaly) / 2 (already-enforced) / 1 (timeline).
        var total = 1 + sources.size + 8
        if (showAnomaly) total += 2 // 异常 + 投票与动作关系
        if (showAlreadyEnforcedState) total += 2 // 当前锁定状态 + 最终动作
        if (showTimeline) total += 1

        val lines = mutableListOf<String>()
        var lineIndex = 1

        val firstLine = buildString {
            append(cycle.reportId)
            append(" [前台汇报][").append(lineIndex).append('/').append(total).append(']')
            append(" reportId=").append(cycle.reportId)
            append(" checkedAt=").append(timeFormat.format(Date(nowMs)))
            val effPkg = outcome.effectivePackageName ?: "none"
            append(" state=").append(effPkg)
            append(" effectivePkg=").append(effPkg)
            append(" blacklisted=").append(outcome.effectivePackageBlacklisted)
            append(" allowance=").append(outcome.allowanceState)
            append(" guard=").append(outcome.guardPassed)
            append(" visibility=").append(outcome.visibilityPassed)
        }
        lines += firstLine
        lineIndex++

        // Per-source sections (sources.size lines, V2-R2 fixed).
        for (src in sources) {
            val srcLine = buildString {
                append(cycle.reportId)
                append(" [前台汇报][").append(lineIndex).append('/').append(total).append(']')
                append(' ').append(displayName(src.source))
                append("(status=").append(src.status.name)
                append(", pkg=").append(src.packageName ?: "none")
                append(", vote=").append(src.vote.name)
                append(", query=")
                append(src.queryStartedAtMs?.let { timeFormat.format(Date(it)) } ?: "-")
                append('~')
                append(src.queryFinishedAtMs?.let { timeFormat.format(Date(it)) } ?: "-")
                append(", reason=").append(src.selectionReason ?: src.unavailableReason ?: src.failureClass ?: "-")
                if (src.candidates.isNotEmpty()) {
                    append(", candidates=[")
                    append(src.candidates.take(3).joinToString(",") { c ->
                        "(${c.packageName},${c.eventName},${c.eventTimeMs}" +
                            (c.lastTimeUsedMs?.let { ",ltu=$it" } ?: "") +
                            (c.lastTimeVisibleMs?.let { ",ltv=$it" } ?: "") +
                            ")"
                    })
                    append(']')
                }
                append(')')
            }
            lines += srcLine
            lineIndex++
        }

        // 报告触发原因 — V2-R2 §5.1 fixed order.
        // Trigger order: REQUEST_LOCK → MISSED_LOCK_* → SOURCE_RECOVERED →
        //   SOURCE_DISPUTE_* → SOURCE_FAULT_*. The list coming in from the gate
        //   is already in this order, so we render it as-is. If triggers is
        //   empty (e.g. legacy / decision-without-trigger path), render "-".
        val triggersLabel = if (triggers.isEmpty()) {
            "-"
        } else {
            triggers.joinToString(",") { it.name }
        }
        val triggerLine = buildString {
            append(cycle.reportId)
            append(" [前台汇报][").append(lineIndex).append('/').append(total).append(']')
            append(" 报告触发原因=").append(triggersLabel)
        }
        lines += triggerLine
        lineIndex++

        // 动作 — V2-R2 §8 Task 3 Step 4 fixed field.
        val actionLabel = if (outcome.businessAction == ForegroundBusinessAction.REQUEST_LOCK) {
            "请求锁屏"
        } else {
            "未请求锁屏"
        }
        val actionLine = buildString {
            append(cycle.reportId)
            append(" [前台汇报][").append(lineIndex).append('/').append(total).append(']')
            append(" 动作=").append(actionLabel)
        }
        lines += actionLine
        lineIndex++

        // 业务处置 — disposition name (from decision.assessment, NOT re-deriving).
        val dispositionLine = buildString {
            append(cycle.reportId)
            append(" [前台汇报][").append(lineIndex).append('/').append(total).append(']')
            append(" 业务处置=").append(disposition.name)
        }
        lines += dispositionLine
        lineIndex++

        // 三源当前判断 — per-source pkg + vote + status in one line.
        val sourcesSummary = sources.joinToString("; ") { src ->
            "${displayName(src.source)}=${src.packageName ?: "none"}/${src.vote.name}/${src.status.name}"
        }
        val sourcesLine = buildString {
            append(cycle.reportId)
            append(" [前台汇报][").append(lineIndex).append('/').append(total).append(']')
            append(" 三源当前判断: ").append(sourcesSummary)
        }
        lines += sourcesLine
        lineIndex++

        // 票数 — V2-R2 §8 Task 3 Step 4 fixed token format.
        val votes = sources.map { it.vote }
        val blacklistVotes = votes.count { it == ForegroundVote.BLACKLIST }
        val nonBlacklistVotes = votes.count { it == ForegroundVote.NON_BLACKLIST }
        val abstainVotes = votes.count { it == ForegroundVote.ABSTAIN }
        val votesLine = buildString {
            append(cycle.reportId)
            append(" [前台汇报][").append(lineIndex).append('/').append(total).append(']')
            append(" 票数=BLACKLIST:").append(blacklistVotes)
            append(" NON_BLACKLIST:").append(nonBlacklistVotes)
            append(" ABSTAIN:").append(abstainVotes)
        }
        lines += votesLine
        lineIndex++

        // 来源关系 — directly from decision.assessment (R1-4: no re-derivation).
        val sourceRelationLine = buildString {
            append(cycle.reportId)
            append(" [前台汇报][").append(lineIndex).append('/').append(total).append(']')
            append(" 来源关系=").append(assessment.sourceRelation.name)
        }
        lines += sourceRelationLine
        lineIndex++

        // 放行状态 — stable allowance category from decision.assessment.
        val allowanceLine = buildString {
            append(cycle.reportId)
            append(" [前台汇报][").append(lineIndex).append('/').append(total).append(']')
            append(" 放行状态=").append(assessment.stableAllowanceCategory)
        }
        lines += allowanceLine
        lineIndex++

        // 最终原因 — the original businessReason literal.
        val finalReasonLine = buildString {
            append(cycle.reportId)
            append(" [前台汇报][").append(lineIndex).append('/').append(total).append(']')
            append(" 最终原因=").append(outcome.businessReason)
        }
        lines += finalReasonLine
        lineIndex++

        if (showTimeline) {
            val timelineLine = buildString {
                append(cycle.reportId)
                append(" [前台汇报][").append(lineIndex).append('/').append(total).append(']')
                append(" timeline(10s): ")
                append(timeline.joinToString(" -> ") { (ts, e) ->
                    "${timeFormat.format(Date(ts))} $e"
                })
            }
            lines += timelineLine
            lineIndex++
        }

        // 异常 + 投票与动作关系 — UNEXPLAINED_MISSED_LOCK only.
        if (showAnomaly) {
            val anomalyLine = buildString {
                append(cycle.reportId)
                append(" [前台汇报][").append(lineIndex).append('/').append(total).append(']')
                append(" 异常=按照当前规则本应锁屏，但未发起锁屏请求")
            }
            lines += anomalyLine
            lineIndex++

            val mismatchLine = buildString {
                append(cycle.reportId)
                append(" [前台汇报][").append(lineIndex).append('/').append(total).append(']')
                append(" 投票与动作关系=无法解释的不一致")
            }
            lines += mismatchLine
            lineIndex++
        }

        // 当前锁定状态 + 最终动作 — ALREADY_ENFORCED only.
        if (showAlreadyEnforcedState) {
            val lockedStateLine = buildString {
                append(cycle.reportId)
                append(" [前台汇报][").append(lineIndex).append('/').append(total).append(']')
                append(" 当前锁定状态=锁屏界面已存在")
            }
            lines += lockedStateLine
            lineIndex++

            val finalActionLine = buildString {
                append(cycle.reportId)
                append(" [前台汇报][").append(lineIndex).append('/').append(total).append(']')
                append(" 最终动作=未重复请求锁屏")
            }
            lines += finalActionLine
            lineIndex++
        }

        if (showConflictDetail) {
            val detailLine = buildString {
                append(cycle.reportId)
                append(" [前台争议明细] ")
                append(conflictDetail)
            }
            lines += detailLine
        }

        return lines
    }

    private fun formatContinuation(
        cycle: ForegroundDiagnosticCycle,
        outcome: ForegroundReportOutcome,
        nowMs: Long,
        conflictStartedAtMs: Long
    ): String {
        val sources = cycle.sourceList()
        val votes = sources.map { it.vote }
        val blacklistVotes = votes.count { it == ForegroundVote.BLACKLIST }
        val nonBlacklistVotes = votes.count { it == ForegroundVote.NON_BLACKLIST }
        // Use gate's lastConflictStartedAtMs (true conflict start), NOT cycle.checkedAtMs
        // (per-poll start). When conflictStartedAtMs==0 (no prior conflict) fall back to
        // cycle.checkedAtMs so the formatter still produces a non-negative duration.
        val startMs = if (conflictStartedAtMs > 0L) conflictStartedAtMs else cycle.checkedAtMs
        return buildString {
            append(cycle.reportId)
            append(" [前台争议持续] ")
            append("duration=").append(((nowMs - startMs) / 1000).coerceAtLeast(0L)).append('s')
            for (src in sources) {
                append(' ').append(src.source).append('=').append(src.packageName ?: "none")
            }
            append(" blacklistVotes=").append(blacklistVotes)
            append(" nonBlacklistVotes=").append(nonBlacklistVotes)
            append(" action=").append(outcome.businessAction.name)
        }
    }

    /** Conflict detail line appended to full reports when sources disagree or fail. */
    private fun buildConflictDetail(
        cycle: ForegroundDiagnosticCycle,
        outcome: ForegroundReportOutcome,
        sources: List<ForegroundSourceSnapshot>
    ): String? {
        val failed = sources.firstOrNull { it.status == ForegroundSourceStatus.FAILED }
        if (failed != null) return "source_failed=${failed.source}:${failed.failureClass ?: failed.status}"

        val ambiguous = sources.firstOrNull { it.status == ForegroundSourceStatus.AMBIGUOUS }
        if (ambiguous != null) return "source_ambiguous=${ambiguous.source}:${ambiguous.unavailableReason ?: "?"}"

        val unavailable = sources.count { it.status == ForegroundSourceStatus.UNAVAILABLE }
        if (unavailable == sources.size) return "all_sources_unavailable"

        val packages = sources.map { it.packageName }
        val distinct = packages.toSet().size
        if (distinct > 1) {
            return "package_mismatch=" + packages.joinToString(",") { it ?: "none" } +
                " effective=${outcome.effectivePackageName ?: "none"}"
        }

        // R1-4: the legacy "diag_vs_business" / "diagnostic_vs_business_mismatch"
        // label is REMOVED per V2-R2 §8 Task 3 Step 5 — disposition-vs-vote
        // mismatch now surfaces only as the explicit UNEXPLAINED_MISSED_LOCK
        // anomaly block inside [前台汇报], never as a free-form [前台争议明细]
        // reason string. computeRecommendation helper removed as a consequence.
        return null
    }

    private fun computeRecommendation(sources: List<ForegroundSourceSnapshot>): String {
        val votes = sources.map { it.vote }
        val blacklistVotes = votes.count { it == ForegroundVote.BLACKLIST }
        val nonBlacklistVotes = votes.count { it == ForegroundVote.NON_BLACKLIST }
        return when {
            blacklistVotes == 0 -> "NO_DIAGNOSTIC_LOCK"
            blacklistVotes > nonBlacklistVotes -> "LOCK_BY_DIAGNOSTIC_MAJORITY"
            else -> "LOCK_ON_TIE"
        }
    }

    private fun displayName(source: String): String = when (source) {
        "USAGE_EVENTS" -> "UsageEvents"
        "USAGE_STATS" -> "UsageStats"
        "ACCESSIBILITY" -> "Accessibility"
        else -> source
    }

    /**
     * Lock-screen result formatter (separate from full report).
     * Returns exactly one line. Pure function for unit testing.
     */
    fun formatLockResult(
        reportId: String,
        packageName: String,
        success: Boolean,
        reused: Boolean,
        requestToDisplayMs: Long,
        failureClass: String? = null
    ): String {
        val result = when {
            !success -> "结果=显示失败"
            reused -> "结果=显示成功"
            else -> "结果=显示成功"
        }
        return buildString {
            append(reportId)
            append(" [锁屏结果] 应用=").append(packageName)
            append("｜").append(result)
            if (reused) append("｜复用已有界面=true")
            append("｜请求到显示=").append(requestToDisplayMs).append("ms")
            if (!success) {
                append("｜原因=").append(failureClass ?: "UNKNOWN")
                append("｜异常=已请求锁屏但锁屏界面未显示")
            }
        }
    }
}
