package com.zhizhi.app.data
enum class ForegroundVote { BLACKLIST, NON_BLACKLIST, ABSTAIN }

enum class ForegroundSourceStatus {
    AVAILABLE, INITIALIZING, UNAVAILABLE, FAILED, AMBIGUOUS
}

enum class ForegroundBusinessAction { REQUEST_LOCK, NO_LOCK, NOT_DECIDED }

enum class ForegroundReportEmission { NONE, FULL, CONTINUING_CONFLICT_SUMMARY }

internal data class FullForegroundReference(
    val reportId: String,
    val packageName: String,
    val selectedSource: String,
    val confirmedAtMs: Long
)

/**
 * V2-R2 §4.2 business disposition — what the business decision for this cycle
 * IS, as classified by [ForegroundReportAssessmentFactory.assess]. The factory
 * uses the disposition to decide whether the gate should fall through the V1
 * blacklisted-NO_LOCK short-circuit (legacy bug) or be silent.
 */
enum class ForegroundEnforcementDisposition {
    /** business action = REQUEST_LOCK */
    REQUESTED_LOCK,
    /** business action = NO_LOCK with reason in legitimateBypassReasons */
    LEGITIMATE_BYPASS,
    /** business action = NO_LOCK with reason = LOCK_ALREADY_ATTACHED */
    ALREADY_ENFORCED,
    /** business action = NO_LOCK with reason in notRequiredReasons */
    NOT_REQUIRED,
    /** business action = NO_LOCK but recommendation=LOCK_* with reason NOT in
     *  legitimateBypassReasons / alreadyEnforcedReasons / notRequiredReasons */
    UNEXPLAINED_MISSED_LOCK,
}

/**
 * V2-R2 §4.1 source relation — how the three sources compare for THIS cycle.
 * Independent of the business decision; used to drive conflict / fault triggers.
 */
enum class ForegroundSourceRelation {
    /** Three sources all report the same package (or all null). */
    ALL_AGREE,
    /** Three sources report DIFFERENT packages — at least one BLACKLIST vs one
     *  NON_BLACKLIST or two different non-null packages. */
    DISAGREEMENT,
    /** At least one source is FAILED / UNAVAILABLE / AMBIGUOUS but the
     *  remaining available sources disagree. */
    DEGRADED,
    /** All three sources are UNAVAILABLE / FAILED. */
    ALL_UNAVAILABLE,
}

/**
 * V2-R2 §5.1 trigger catalog. Task 1 only persists REQUEST_LOCK / SOURCE_DISPUTE_*
 * / SOURCE_FAULT_* / SOURCE_RECOVERED; the multi-trigger Decision state machine
 * (Task 2) wires them. Defined here so Task 2 callers can reference them.
 */
enum class ForegroundReportTrigger {
    REQUEST_LOCK,
    MISSED_LOCK_STARTED,
    MISSED_LOCK_CHANGED,
    SOURCE_DISPUTE_STARTED,
    SOURCE_DISPUTE_CHANGED,
    SOURCE_FAULT_STARTED,
    SOURCE_FAULT_CHANGED,
    SOURCE_RECOVERED,
}

/**
 * V2-R2 §4 assessment: a pure value computed by
 * [ForegroundReportAssessmentFactory.assess] from (cycle, outcome). The factory
 * is the single source of truth for disposition + source relation + stable
 * signature. It does NOT call any Android API or mutate gate state.
 */
data class ForegroundReportAssessment(
    val sourceRelation: ForegroundSourceRelation,
    val enforcementDisposition: ForegroundEnforcementDisposition,
    val sourceDisagreementReasons: List<String>,
    val blacklistVotes: Int,
    val nonBlacklistVotes: Int,
    val abstainVotes: Int,
    val isTie: Boolean,
    val hasSourceDispute: Boolean,
    val hasSourceFault: Boolean,
    val stableAllowanceCategory: String,
    val stableSignature: String
)

data class ForegroundCandidate(
    val packageName: String,
    val eventName: String,
    val eventTimeMs: Long,
    val lastTimeUsedMs: Long? = null,
    val lastTimeVisibleMs: Long? = null
)

/** Raw fields from one Accessibility event/window snapshot; null means the source did not supply it. */
data class AccessibilityForegroundSnapshot(
    val eventAtMs: Long?,
    val handledAtMs: Long?,
    val eventType: String?,
    val eventPackageName: String?,
    val snapshotAtMs: Long?,
    val windowSummary: String
)

data class ForegroundSourceSnapshot(
    val source: String,
    val status: ForegroundSourceStatus,
    val packageName: String?,
    val vote: ForegroundVote,
    val capturedAtMs: Long,
    val queryStartedAtMs: Long?,
    val queryFinishedAtMs: Long?,
    val candidates: List<ForegroundCandidate>,
    val selectionReason: String?,
    val unavailableReason: String?,
    val failureClass: String?,
    /** Source-specific previous evidence captured by the producer, when it has history. */
    val previousEvidence: String? = null,
    /** Source-specific current evidence captured by the producer for this cycle. */
    val currentEvidence: String? = null,
    /** Accessibility-specific source facts. Other sources leave these null. */
    val previousAccessibilitySnapshot: AccessibilityForegroundSnapshot? = null,
    val currentAccessibilitySnapshot: AccessibilityForegroundSnapshot? = null
)

data class ForegroundReportOutcome(
    val effectivePackageName: String?,
    val effectivePackageBlacklisted: Boolean?,
    val diagnosticRecommendation: String,
    val businessAction: ForegroundBusinessAction,
    val businessReason: String,
    val allowanceState: String,
    val guardPassed: Boolean?,
    val visibilityPassed: Boolean?
)

class ForegroundDiagnosticCycle(
    val reportId: String,
    val checkedAtMs: Long,
    /** Existing foreground state captured before this cycle performs source resolution. */
    val previousForegroundPackageName: String? = null
) {
    @Volatile var usageEvents: ForegroundSourceSnapshot = placeholder("USAGE_EVENTS")
        private set
    @Volatile var usageStats: ForegroundSourceSnapshot = placeholder("USAGE_STATS")
        private set
    @Volatile var accessibility: ForegroundSourceSnapshot = placeholder("ACCESSIBILITY")
        private set

    @Volatile var guardPassed: Boolean? = null
        private set
    @Volatile var guardReason: String? = null
        private set
    @Volatile var visibilityPassed: Boolean? = null
        private set
    @Volatile var visibilityReason: String? = null
        private set

    private val timeline: ArrayDeque<Pair<Long, String>> = ArrayDeque()

    private fun placeholder(source: String): ForegroundSourceSnapshot = ForegroundSourceSnapshot(
        source = source,
        status = ForegroundSourceStatus.INITIALIZING,
        packageName = null,
        vote = ForegroundVote.ABSTAIN,
        capturedAtMs = checkedAtMs,
        queryStartedAtMs = null,
        queryFinishedAtMs = null,
        candidates = emptyList(),
        selectionReason = null,
        unavailableReason = null,
        failureClass = null
    )

    fun updateUsageEvents(snapshot: ForegroundSourceSnapshot) {
        usageEvents = snapshot
        addTimelinePoint("USAGE_EVENTS=${snapshot.status}/${snapshot.packageName ?: "-"}")
    }

    fun updateUsageStats(snapshot: ForegroundSourceSnapshot) {
        usageStats = snapshot
        addTimelinePoint("USAGE_STATS=${snapshot.status}/${snapshot.packageName ?: "-"}")
    }

    fun updateAccessibility(snapshot: ForegroundSourceSnapshot) {
        accessibility = snapshot
        addTimelinePoint("ACCESSIBILITY=${snapshot.status}/${snapshot.packageName ?: "-"}")
    }

    fun recordGuardResult(passed: Boolean?, reason: String?) {
        guardPassed = passed
        guardReason = reason
        addTimelinePoint("GUARD=${passed}/${reason ?: "-"}")
    }

    fun recordVisibilityResult(passed: Boolean?, reason: String?) {
        visibilityPassed = passed
        visibilityReason = reason
        addTimelinePoint("VIS=${passed}/${reason ?: "-"}")
    }

    fun addTimelinePoint(entry: String) {
        val now = System.currentTimeMillis()
        timeline.addLast(now to entry)
        val cutoff = now - 10_000L
        while (timeline.isNotEmpty() && timeline.first().first < cutoff) {
            timeline.removeFirst()
        }
    }

    fun timelineSnapshot(nowMs: Long): List<Pair<Long, String>> {
        val cutoff = nowMs - 10_000L
        return timeline.filter { it.first >= cutoff }
    }

    fun sourceList(): List<ForegroundSourceSnapshot> = listOf(usageEvents, usageStats, accessibility)
}

/**
 * Pure factory for fault snapshots used by MonitorService fault paths.
 * Kept separate from cycle instance so it can be unit-tested without
 * instantiating the Android Service.
 */
internal object ForegroundFaultSnapshotFactory {
    /**
     * Pure snapshot factory. When the caller has NOT actually invoked a system API
     * (e.g. `usageStatsManager == null`, "no query" branches), it MUST pass
     * `queryStartedAtMs = null` and `queryFinishedAtMs = null` so the snapshot
     * does not fabricate a window range or a phantom "finished at capturedAtMs".
     * When the caller has invoked a system API, both timestamps must be real
     * wall-clock captures from before / after the call.
     */
    fun eventsFailed(
        failureClass: String,
        capturedAtMs: Long = System.currentTimeMillis(),
        queryStartedAtMs: Long? = null,
        queryFinishedAtMs: Long? = null
    ): ForegroundSourceSnapshot =
        ForegroundSourceSnapshot(
            source = "USAGE_EVENTS",
            status = ForegroundSourceStatus.FAILED,
            packageName = null,
            vote = ForegroundVote.ABSTAIN,
            capturedAtMs = capturedAtMs,
            queryStartedAtMs = queryStartedAtMs,
            queryFinishedAtMs = queryFinishedAtMs,
            candidates = emptyList(),
            selectionReason = null,
            unavailableReason = null,
            failureClass = failureClass
        )

    fun usageStatsFailed(
        failureClass: String,
        capturedAtMs: Long = System.currentTimeMillis(),
        queryStartedAtMs: Long? = null,
        queryFinishedAtMs: Long? = null
    ): ForegroundSourceSnapshot =
        ForegroundSourceSnapshot(
            source = "USAGE_STATS",
            status = ForegroundSourceStatus.FAILED,
            packageName = null,
            vote = ForegroundVote.ABSTAIN,
            capturedAtMs = capturedAtMs,
            queryStartedAtMs = queryStartedAtMs,
            queryFinishedAtMs = queryFinishedAtMs,
            candidates = emptyList(),
            selectionReason = null,
            unavailableReason = null,
            failureClass = failureClass
        )

    fun eventsUnavailable(
        capturedAtMs: Long = System.currentTimeMillis(),
        queryStartedAtMs: Long? = null,
        queryFinishedAtMs: Long? = null
    ): ForegroundSourceSnapshot =
        ForegroundSourceSnapshot(
            source = "USAGE_EVENTS",
            status = ForegroundSourceStatus.UNAVAILABLE,
            packageName = null,
            vote = ForegroundVote.ABSTAIN,
            capturedAtMs = capturedAtMs,
            queryStartedAtMs = queryStartedAtMs,
            queryFinishedAtMs = queryFinishedAtMs,
            candidates = emptyList(),
            selectionReason = null,
            unavailableReason = "NO_RECENT_FOREGROUND_EVENT",
            failureClass = null
        )

    fun usageStatsUnavailable(
        capturedAtMs: Long = System.currentTimeMillis(),
        queryStartedAtMs: Long? = null,
        queryFinishedAtMs: Long? = null
    ): ForegroundSourceSnapshot =
        ForegroundSourceSnapshot(
            source = "USAGE_STATS",
            status = ForegroundSourceStatus.UNAVAILABLE,
            packageName = null,
            vote = ForegroundVote.ABSTAIN,
            capturedAtMs = capturedAtMs,
            queryStartedAtMs = queryStartedAtMs,
            queryFinishedAtMs = queryFinishedAtMs,
            candidates = emptyList(),
            selectionReason = null,
            unavailableReason = "USAGE_STATS_MANAGER_NULL_OR_EMPTY",
            failureClass = null
        )
}

private const val CONFLICT_CONTINUATION_INTERVAL_MS = 30_000L

/**
 * Pure mapping from (status, status-check failure class) → ACCESSIBILITY failure
 * class. Extracted so the behaviour is testable without instantiating the
 * Android Service. The status failure class MUST come from the same detection
 * process that produced the status (the Settings + service-connectivity check);
 * window-scan failures are reported through a different field.
 *
 * - FAILED + specific exception class → that exception class
 * - FAILED + no specific exception → "A11Y_STATUS_FAILED" fallback
 * - any non-FAILED status → null (no failure class — a stale value must NOT
 *   leak into a successful or non-existent status)
 */
internal fun resolveAccessibilityFailureClass(
    status: ForegroundSourceStatus,
    statusFailureClass: String?
): String? {
    return if (status == ForegroundSourceStatus.FAILED) {
        statusFailureClass ?: "A11Y_STATUS_FAILED"
    } else {
        null
    }
}

/**
 * V2-R2 §4 pure factory: classify (cycle, outcome) into
 * [ForegroundReportAssessment]. Single source of truth for source relation,
 * enforcement disposition and stable signature. No Android dependency, no
 * mutation of gate state, no logging. Callable from unit tests.
 */
internal object ForegroundReportAssessmentFactory {

    // Reason sets pinned to the exact real literals from V1 production code.
    // See REASON_CATALOG.md §1-§8 + §5.1 table. Updating these sets requires
    // touching REASON_CATALOG.md and the Q1-Q4 plan rules in lockstep.

    private val alreadyEnforcedReasons = setOf("LOCK_ALREADY_ATTACHED")

    private val legitimateBypassReasons = setOf(
        "PWA_BYPASS",
        "FOREGROUND_GUARD_REJECTED",
        "BYPASS_VISIBILITY_REJECTED",
        "SAFE_APP_BYPASS",
        "WEEKLY_PRIVILEGE_ACTIVE",
        "FREE_TIME_BYPASS",
        "REST_OR_APP_WINDOW_ALLOW",
        "TEMPORARY_ACCESS_ACTIVE"
    )

    /**
     * R2-4: active allowance categories — strict white-list of stable tokens
     * produced by [stableAllowanceCategoryOf]. An allowance state is "active"
     * ONLY if its stable category is in this set, OR if the businessReason
     * itself is one of the explicit legitimate bypass reasons above. No
     * substring / contains matching is allowed — that was the R2-4 bug
     * (e.g. "BYPASS_CHECK_FAILED" or "NOT_OK" would have been wrongly
     * classified as active via contains("BYPASS") / contains("OK")).
     *
     * The set MUST stay in lock-step with the known-token branches of
     * [stableAllowanceCategoryOf] (FREE_TIME / WEEKLY_PRIVILEGE / SAFE_APP /
     * TEMP_ACCESS / REST_OR_APP). Other tokens (ALREADY_ENFORCED / ALLOW_NONE
     * / UNKNOWN / OTHER:*) are explicitly NOT in the active set.
     */
    private val activeAllowanceCategories = setOf(
        "TEMP_ACCESS",
        "WEEKLY_PRIVILEGE",
        "FREE_TIME",
        "REST_OR_APP"
    )

    private val notRequiredReasons = setOf(
        "NO_FOREGROUND_RESOLVED",
        "ON_BLACKLIST_HIT_NOT_STRICT"
    )

    fun assess(
        cycle: ForegroundDiagnosticCycle,
        outcome: ForegroundReportOutcome
    ): ForegroundReportAssessment {
        val sources = cycle.sourceList()
        val packages = sources.map { it.packageName }
        val distinctPkgs = packages.toSet()
        val votes = sources.map { it.vote }
        val blacklistVotes = votes.count { it == ForegroundVote.BLACKLIST }
        val nonBlacklistVotes = votes.count { it == ForegroundVote.NON_BLACKLIST }
        val abstainVotes = votes.count { it == ForegroundVote.ABSTAIN }
        val isTie = blacklistVotes > 0 && blacklistVotes == nonBlacklistVotes
        val statuses = sources.map { it.status }
        // R1-1: identify neutral not-sampled sources. These are the default
        // INITIALIZING placeholders for sources that the polling path did NOT
        // actually query this cycle. They are NOT a fault and their null
        // package does NOT participate in package-mismatch detection.
        val neutralNotSampled = sources.map { isNeutralNotSampled(it) }
        // Per-source fault / dispute classification. A source is a fault only
        // when there is a real failure/unavailable signal, or when status is
        // FAILED/UNAVAILABLE; AMBIGUOUS without metadata is a dispute only.
        val anyFailed = statuses.any { it == ForegroundSourceStatus.FAILED }
        val anyUnavailable = statuses.any { it == ForegroundSourceStatus.UNAVAILABLE }
        val anyAmbiguous = statuses.any { it == ForegroundSourceStatus.AMBIGUOUS }
        // R2-1: anyTrueFault now includes non-neutral INITIALIZING sources.
        // A source is a "real fault" when it is FAILED/UNAVAILABLE, OR when it
        // carries failure/unavailable metadata, OR when it is INITIALIZING but
        // has actually been queried (queryStartedAtMs != null) and not finished
        // (queryFinishedAtMs == null) — that is a real in-flight error, not a
        // neutral placeholder.
        val anyTrueFault = sources.any { src ->
            src.status == ForegroundSourceStatus.FAILED ||
                src.status == ForegroundSourceStatus.UNAVAILABLE ||
                !src.failureClass.isNullOrBlank() ||
                !src.unavailableReason.isNullOrBlank() ||
                // Non-neutral INITIALIZING: query started but not finished (or
                // with failure metadata). This is a real initialization error
                // path, not a neutral placeholder.
                (src.status == ForegroundSourceStatus.INITIALIZING &&
                    (src.queryStartedAtMs != null && src.queryFinishedAtMs == null))
        }
        val anyAmbiguousWithoutMetadata = sources.any { src ->
            src.status == ForegroundSourceStatus.AMBIGUOUS &&
                src.failureClass.isNullOrBlank() &&
                src.unavailableReason.isNullOrBlank()
        }
        // R2-1: allUnavailable is "allTrulyUnavailable" — only UNAVAILABLE/FAILED
        // count. AMBIGUOUS without metadata is a dispute, NOT unavailability.
        val allTrulyUnavailable = statuses.all {
            it == ForegroundSourceStatus.UNAVAILABLE ||
                it == ForegroundSourceStatus.FAILED
        }
        // R1-1: usable packages exclude nulls AND neutral-not-sampled sources
        // (their null package is not an opinion).
        val usablePackages = sources.mapIndexedNotNull { i, src ->
            if (neutralNotSampled[i]) null else src.packageName
        }.toSet()
        val packageDisagreement = usablePackages.size > 1

        // R2-1: hasSourceFault + hasSourceDispute computed first as final booleans,
        // then sourceRelation decided exactly once. AMBIGUOUS without metadata is
        // a dispute, never a fault. Mixed BLACKLIST/NON_BLACKLIST votes (any
        // non-zero of each, tie or not) are a dispute.
        val hasSourceFault = anyTrueFault
        val hasSourceDispute = anyAmbiguousWithoutMetadata ||
            packageDisagreement ||
            (blacklistVotes > 0 && nonBlacklistVotes > 0) ||
            isTie

        val sourceRelation = when {
            allTrulyUnavailable -> ForegroundSourceRelation.ALL_UNAVAILABLE
            hasSourceFault -> ForegroundSourceRelation.DEGRADED
            hasSourceDispute -> ForegroundSourceRelation.DISAGREEMENT
            else -> ForegroundSourceRelation.ALL_AGREE
        }

        val disagreementReasons = mutableListOf<String>()
        if (anyTrueFault || anyFailed) disagreementReasons.add("source_failed")
        if (anyUnavailable && !allTrulyUnavailable) disagreementReasons.add("source_unavailable")
        if (anyAmbiguous || anyAmbiguousWithoutMetadata) disagreementReasons.add("source_ambiguous")
        if (packageDisagreement) disagreementReasons.add("package_mismatch")
        if (isTie) disagreementReasons.add("vote_tie")
        if (blacklistVotes > 0 && nonBlacklistVotes > 0) disagreementReasons.add("blacklist_vs_nonblacklist")

        // V2-R2 §4.2 disposition priority.
        val allKnownExplanationSets = alreadyEnforcedReasons +
            legitimateBypassReasons +
            notRequiredReasons
        val stableAllowanceCategoryForActiveCheck = stableAllowanceCategoryOf(outcome)
        val allowanceIsInactive = !isAllowanceActive(outcome, stableAllowanceCategoryForActiveCheck)
        val enforcementDisposition = when {
            outcome.businessAction == ForegroundBusinessAction.REQUEST_LOCK ->
                ForegroundEnforcementDisposition.REQUESTED_LOCK
            // R1-3: NOT_DECIDED is a probe state and must NEVER be classified as
            // UNEXPLAINED_MISSED_LOCK. It is explicitly handled here (ahead of
            // the strict missed-lock branch) so the strict 7-condition check
            // below cannot accidentally promote it.
            outcome.businessAction == ForegroundBusinessAction.NOT_DECIDED ->
                ForegroundEnforcementDisposition.NOT_REQUIRED
            outcome.businessReason in alreadyEnforcedReasons ->
                ForegroundEnforcementDisposition.ALREADY_ENFORCED
            outcome.businessReason in legitimateBypassReasons ->
                ForegroundEnforcementDisposition.LEGITIMATE_BYPASS
            outcome.businessReason in notRequiredReasons ->
                ForegroundEnforcementDisposition.NOT_REQUIRED
            // R1-3: UNEXPLAINED_MISSED_LOCK requires ALL 7 conditions to hold
            // simultaneously: NO_LOCK, blacklisted=true, recommendation LOCK_*,
            // businessReason not in any known bypass set, allowance is INACTIVE,
            // guardPassed=true, visibilityPassed=true. Anything else (e.g. a
            // non-blacklisted package, a rejected guard, an unmapped reason
            // with allowance still active) falls through to NOT_REQUIRED.
            outcome.businessAction == ForegroundBusinessAction.NO_LOCK &&
                outcome.effectivePackageBlacklisted == true &&
                recommendationStartsWithLock(outcome.diagnosticRecommendation) &&
                outcome.businessReason !in allKnownExplanationSets &&
                allowanceIsInactive &&
                outcome.guardPassed == true &&
                outcome.visibilityPassed == true ->
                ForegroundEnforcementDisposition.UNEXPLAINED_MISSED_LOCK
            else ->
                ForegroundEnforcementDisposition.NOT_REQUIRED
        }

        val stableAllowanceCategory = stableAllowanceCategoryOf(outcome)
        val stableSignature = stableSignatureOf(
            outcome = outcome,
            sources = sources,
            votes = votes,
            statuses = statuses,
            packages = packages,
            sourceRelation = sourceRelation,
            enforcementDisposition = enforcementDisposition,
            hasSourceDispute = hasSourceDispute,
            hasSourceFault = hasSourceFault,
            stableAllowanceCategory = stableAllowanceCategory
        )

        return ForegroundReportAssessment(
            sourceRelation = sourceRelation,
            enforcementDisposition = enforcementDisposition,
            sourceDisagreementReasons = disagreementReasons.toList(),
            blacklistVotes = blacklistVotes,
            nonBlacklistVotes = nonBlacklistVotes,
            abstainVotes = abstainVotes,
            isTie = isTie,
            hasSourceDispute = hasSourceDispute,
            hasSourceFault = hasSourceFault,
            stableAllowanceCategory = stableAllowanceCategory,
            stableSignature = stableSignature
        )
    }

    private fun recommendationStartsWithLock(recommendation: String): Boolean =
        recommendation == "LOCK_BY_DIAGNOSTIC_MAJORITY" ||
            recommendation == "LOCK_ON_TIE"

    /**
     * R1-1 neutral not-sampled detector. A source is "neutral not-sampled"
     * when the polling path did NOT actually invoke a system query for it in
     * this cycle: status is the default INITIALIZING, the package is null
     * (no opinion), vote is ABSTAIN, query timestamps are absent and there is
     * no failure/unavailable metadata. Such a source must NOT be treated as a
     * fault and its null package must NOT participate in package-mismatch.
     */
    private fun isNeutralNotSampled(snap: ForegroundSourceSnapshot): Boolean =
        snap.status == ForegroundSourceStatus.INITIALIZING &&
            snap.packageName == null &&
            snap.vote == ForegroundVote.ABSTAIN &&
            snap.queryStartedAtMs == null &&
            snap.queryFinishedAtMs == null &&
            snap.failureClass.isNullOrBlank() &&
            snap.unavailableReason.isNullOrBlank()

    /**
     * R2-4: allowance is "active" iff the stable allowance category produced
     * by [stableAllowanceCategoryOf] is in the strict white-list
     * [activeAllowanceCategories], OR the businessReason itself is one of the
     * explicit [legitimateBypassReasons]. NO substring matching is used —
     * R2-4 explicitly removed the forbidden `contains("BYPASS")` /
     * `contains("OK")` / `contains("ACTIVE")` rules from V1 / R1, which were
     * silently classifying failure states like "BYPASS_CHECK_FAILED" or
     * "NOT_OK" as active allowance and thereby suppressing strict missed
     * locks.
     *
     * Unknown / failure allowance tokens (including the "OTHER:*" fallback)
     * must be classified as inactive — strict missed lock semantics depend
     * on this.
     */
    private fun isAllowanceActive(
        outcome: ForegroundReportOutcome,
        stableAllowanceCategory: String
    ): Boolean {
        if (stableAllowanceCategory in activeAllowanceCategories) return true
        return outcome.businessReason in legitimateBypassReasons
    }

    /**
     * V2-R2 §4.3 stable allowance category: collapse multi-valued allowance
     * state strings (e.g. FREE_TIME_ACTIVE vs FREE_TIME_BYPASS) into a single
     * stable token so the stable signature de-dups regardless of which
     * MonitorService sub-branch fired.
     *
     * R1-2: dynamic countdown literals like "temp=30s" / "temp=29s" must
     * collapse to the same token ("TEMP" or "TEMP_COUNTDOWN"); unknown
     * structured tokens must be sanitized (letters / underscore / dash only,
     * no digits) and prefixed "OTHER:"; raw dynamic strings must NOT leak
     * through.
     */
    private fun stableAllowanceCategoryOf(outcome: ForegroundReportOutcome): String {
        val allowance = outcome.allowanceState.uppercase()
        val reason = outcome.businessReason.uppercase()
        return when {
            outcome.businessReason == "LOCK_ALREADY_ATTACHED" -> "ALREADY_ENFORCED"
            allowance.contains("FREE_TIME") || reason == "FREE_TIME_BYPASS" -> "FREE_TIME"
            allowance.contains("PRIVILEGE") || reason == "WEEKLY_PRIVILEGE_ACTIVE" -> "WEEKLY_PRIVILEGE"
            allowance.contains("SAFE_APP") || reason == "SAFE_APP_BYPASS" -> "SAFE_APP"
            allowance.contains("TEMP_ACCESS") || reason == "TEMPORARY_ACCESS_ACTIVE" -> "TEMP_ACCESS"
            allowance.contains("REST_OR_APP") || reason == "REST_OR_APP_WINDOW_ALLOW" -> "REST_OR_APP"
            allowance.contains("ALLOW_NONE") && outcome.effectivePackageBlacklisted != true -> "ALLOW_NONE_PASS"
            allowance == "UNKNOWN" || outcome.effectivePackageBlacklisted == null -> "UNKNOWN"
            // R1-2: structured "token=NUMunit" or "token:NUMunit" forms
            // (e.g. "temp=30s" from FOREGROUND_GUARD_REJECTED countdown).
            // Extract the stable token, sanitize (letters/underscore/dash only),
            // and return it. Empty after sanitization → fallback "OTHER:".
            //
            // R2-2: ALL unknown / sanitized tokens get the "OTHER:" prefix,
            // including tokens that already sanitize to themselves. This makes
            // the prefix a stable invariant: any token that was not produced by
            // a known mapping branch above carries "OTHER:" — raw token
            // strings (e.g. "FOO") must NEVER leak through.
            else -> {
                val rawToken = outcome.allowanceState.substringBefore('=')
                    .substringBefore(':')
                    .uppercase()
                val sanitized = rawToken.filter { it.isLetter() || it == '_' || it == '-' }
                if (sanitized.isBlank()) "OTHER:UNKNOWN"
                else "OTHER:$sanitized"
            }
        }
    }

    /**
     * V2-R2 §4.3 stable signature: includes the stable fields that drive the
     * disposition decision but explicitly excludes the dynamic query timestamps
     * (capturedAtMs, queryStartedAtMs, queryFinishedAtMs) so two cycles
     * differing only in those timestamps produce the same stableSignature.
     *
     * R1-2: signature MUST also include each source's failureClass /
     * unavailableReason (so fault-cause change fires SOURCE_FAULT_CHANGED),
     * plus guardPassed / visibilityPassed / diagnosticRecommendation /
     * enforcementDisposition / hasSourceDispute / hasSourceFault.
     * Dynamic fields (reportId, query/capture timestamps, candidate event
     * times, countdown seconds, durations) remain excluded.
     *
     * R2-2: stableSignatureOf is a pure serializer — it MUST receive the real
     * assessment fields (sourceRelation, enforcementDisposition, hasSourceDispute,
     * hasSourceFault, stableAllowanceCategory) already computed by assess().
     * It does NOT re-derive them, and it does NOT call back into the
     * disposition / fault / dispute classification helpers. The signature
     * tokens REL/DISP/DSP/FLT must match the assessment fields token-for-token.
     */
    private fun stableSignatureOf(
        outcome: ForegroundReportOutcome,
        sources: List<ForegroundSourceSnapshot>,
        votes: List<ForegroundVote>,
        statuses: List<ForegroundSourceStatus>,
        packages: List<String?>,
        sourceRelation: ForegroundSourceRelation,
        enforcementDisposition: ForegroundEnforcementDisposition,
        hasSourceDispute: Boolean,
        hasSourceFault: Boolean,
        stableAllowanceCategory: String
    ): String = buildString {
        append("REL[")
        append(sourceRelation.name)
        append("]BA[")
        append(outcome.businessAction.name)
        append("]REASON[")
        append(outcome.businessReason)
        append("]EFF[")
        append(outcome.effectivePackageName ?: "none")
        append("]BL[")
        append(outcome.effectivePackageBlacklisted?.toString() ?: "?")
        append("]ALLOW[")
        append(stableAllowanceCategory)
        append("]PKG[")
        append(packages.joinToString(",") { it ?: "none" })
        append("]VOTE[")
        append(votes.joinToString(",") { it.name })
        append("]STAT[")
        append(statuses.joinToString(",") { it.name })
        append("]FAIL[")
        append(sources.joinToString(",") { it.failureClass?.takeIf { f -> f.isNotBlank() } ?: "-" })
        append("]UNAVAIL[")
        append(sources.joinToString(",") { it.unavailableReason?.takeIf { r -> r.isNotBlank() } ?: "-" })
        append("]GUARD[")
        append(outcome.guardPassed?.toString() ?: "?")
        append("]VIS[")
        append(outcome.visibilityPassed?.toString() ?: "?")
        append("]REC[")
        append(outcome.diagnosticRecommendation)
        append("]DISP[")
        append(enforcementDisposition.name)
        append("]DSP[")
        append(hasSourceDispute.toString())
        append("]FLT[")
        append(hasSourceFault.toString())
        append("]")
    }
}

/**
 * V2-R2 §2.2 gate decision: every gate.evaluate(...) call returns this shape so
 * the formatter / caller can decide whether to emit and which lines to surface.
 *
 * - emission: the actual log line we should produce
 * - triggers: ordered list of the rules that fired for this cycle
 * - assessment: the snapshot from [ForegroundReportAssessmentFactory.assess]
 * - sourceProblemStartedAtMs: start of the active source problem (dispute / fault)
 *   for accurate duration in the continuation line; null when no source problem
 *   is currently active. Per V2-R2 §8 Task 2 Step 6, the recovery decision
 *   carries the cleanup target's startedAt so the formatter can surface the
 *   recovery duration even after the gate clears its own field.
 */
data class ForegroundReportDecision(
    val emission: ForegroundReportEmission,
    val triggers: List<ForegroundReportTrigger>,
    val assessment: ForegroundReportAssessment,
    val sourceProblemStartedAtMs: Long?
)

class ForegroundReportGate {
    // V2-R2 §8 Task 2 Step 4: four fields driving the dual state machine.
    // Two independent signatures: lastSourceProblemSignature (dispute/fault)
    // and lastMissedLockSignature (UNEXPLAINED_MISSED_LOCK). They are NOT
    // merged — dispute and missed-lock can co-exist (a missed lock with sources
    // disagreeing fires MISSED_LOCK_STARTED + SOURCE_DISPUTE_STARTED).
    private var lastSourceProblemSignature: String? = null
    private var sourceProblemStartedAtMs: Long? = null
    private var lastSourceSummaryAtMs: Long? = null
    private var lastMissedLockSignature: String? = null

    /** Conflict start time in ms (0 if no active conflict). Used by formatter for true duration. */
    fun lastConflictStartedAtMs(): Long = sourceProblemStartedAtMs ?: 0L

    /** Reset all state — used by tests. */
    fun reset() {
        lastSourceProblemSignature = null
        sourceProblemStartedAtMs = null
        lastSourceSummaryAtMs = null
        lastMissedLockSignature = null
    }

    fun evaluate(
        cycle: ForegroundDiagnosticCycle,
        outcome: ForegroundReportOutcome,
        nowMs: Long
    ): ForegroundReportDecision {
        // V2-R2 §8 Task 2 Step 5: dual-state Decision state machine.
        //
        // Triggers are appended in the fixed order mandated by V2-R2 §5.1:
        //   1) REQUEST_LOCK
        //   2) MISSED_LOCK_STARTED / MISSED_LOCK_CHANGED
        //   3) SOURCE_RECOVERED
        //   4) SOURCE_DISPUTE_STARTED / SOURCE_DISPUTE_CHANGED
        //   5) SOURCE_FAULT_STARTED / SOURCE_FAULT_CHANGED
        //
        // The legacy V1 short-circuit
        //   `if (NO_LOCK && effectivePackageBlacklisted == true) -> FULL`
        // is REMOVED (Task 2 Step 9 grep check). Blacklisted NO_LOCK now flows
        // through the assessment-driven trigger logic and is silent when the
        // disposition is LEGITIMATE_BYPASS / ALREADY_ENFORCED / NOT_REQUIRED
        // AND sources agree. A genuine missed lock fires MISSED_LOCK_STARTED.
        val assessment = ForegroundReportAssessmentFactory.assess(cycle, outcome)

        val hasSourceProblem = assessment.hasSourceDispute || assessment.hasSourceFault
        val previousSourceSignature = lastSourceProblemSignature
        val previousHadSourceProblem = previousSourceSignature != null
        val sourceRecovered = previousHadSourceProblem && !hasSourceProblem
        val sourceChanged = hasSourceProblem &&
            assessment.stableSignature != previousSourceSignature

        val isMissedLock = assessment.enforcementDisposition ==
            ForegroundEnforcementDisposition.UNEXPLAINED_MISSED_LOCK
        val missedChanged = isMissedLock &&
            assessment.stableSignature != lastMissedLockSignature

        val triggers = mutableListOf<ForegroundReportTrigger>()

        // 1) REQUEST_LOCK trigger.
        if (assessment.enforcementDisposition == ForegroundEnforcementDisposition.REQUESTED_LOCK) {
            triggers += ForegroundReportTrigger.REQUEST_LOCK
        }

        // 2) Missed-lock trigger (STARTED on first scene, CHANGED on subsequent scene).
        //    R2-3 option A — MISSED trigger fires whenever the strict 7-condition
        //    missed lock holds, independent of any active source problem. Sources
        //    disagree / fault and a strict missed lock can co-exist: the decision
        //    carries BOTH triggers in the fixed V2-R2 §5.1 order (MISSED first
        //    then SOURCE_*). The Formatter shows the missed-lock anomaly block
        //    iff a MISSED trigger is present (per R2-3 showAnomaly rewrite).
        //
        //    lastMissedLockSignature is tracked independently of the source
        //    problem state, so the missed-lock that persists / resumes after
        //    source recovery fires MISSED_LOCK_CHANGED (not STARTED).
        if (missedChanged) {
            triggers += if (lastMissedLockSignature == null) {
                ForegroundReportTrigger.MISSED_LOCK_STARTED
            } else {
                ForegroundReportTrigger.MISSED_LOCK_CHANGED
            }
        }

        // 3) SOURCE_RECOVERED — appears between MISSED_LOCK_* and SOURCE_DISPUTE_*
        //    in the fixed order so a missed-lock + recovery combo surfaces as
        //    [MISSED_LOCK_STARTED, SOURCE_RECOVERED] (per V2-R2 §5.1).
        if (sourceRecovered) {
            triggers += ForegroundReportTrigger.SOURCE_RECOVERED
        }

        // 4) SOURCE_DISPUTE_STARTED / SOURCE_DISPUTE_CHANGED.
        if (sourceChanged && assessment.hasSourceDispute) {
            triggers += if (!previousHadSourceProblem) {
                ForegroundReportTrigger.SOURCE_DISPUTE_STARTED
            } else {
                ForegroundReportTrigger.SOURCE_DISPUTE_CHANGED
            }
        }

        // 5) SOURCE_FAULT_STARTED / SOURCE_FAULT_CHANGED.
        if (sourceChanged && assessment.hasSourceFault) {
            triggers += if (!previousHadSourceProblem) {
                ForegroundReportTrigger.SOURCE_FAULT_STARTED
            } else {
                ForegroundReportTrigger.SOURCE_FAULT_CHANGED
            }
        }

        // Step 6: state maintenance BEFORE emission, so the continuation
        // timer / source problem fields reflect this cycle.
        //
        // (a) Source-problem bookkeeping.
        val recoveryStartedAtForReturn: Long? = if (sourceRecovered) sourceProblemStartedAtMs else null
        if (hasSourceProblem) {
            // First time we see a source problem → stamp startedAt.
            if (sourceProblemStartedAtMs == null) {
                sourceProblemStartedAtMs = nowMs
            }
            lastSourceProblemSignature = assessment.stableSignature
        } else {
            // No active source problem — clear.
            sourceProblemStartedAtMs = null
            lastSourceProblemSignature = null
            lastSourceSummaryAtMs = null
        }

        // (b) Missed-lock bookkeeping. R2-3 option A: lastMissedLockSignature is
        //     tracked INDEPENDENTLY of the source problem state. When a strict
        //     missed lock holds, the signature is updated. When no missed lock
        //     is present this cycle, the signature is cleared. This way the
        //     missed-lock that persists / resumes after source recovery fires
        //     MISSED_LOCK_CHANGED (not STARTED), per V2-R2 §5.1 fixed order.
        if (isMissedLock) {
            lastMissedLockSignature = assessment.stableSignature
        } else {
            // No missed lock this cycle → reset (independent of source problem).
            lastMissedLockSignature = null
        }

        // Emission: triggers → FULL; otherwise → 30s continuation if a source
        // problem is active; otherwise NONE.
        val emission = when {
            triggers.isNotEmpty() -> {
                if (hasSourceProblem) markSourceSummaryEmitted(nowMs)
                ForegroundReportEmission.FULL
            }
            hasSourceProblem && shouldEmitThirtySecondSummary(nowMs) -> {
                markSourceSummaryEmitted(nowMs)
                ForegroundReportEmission.CONTINUING_CONFLICT_SUMMARY
            }
            else -> ForegroundReportEmission.NONE
        }

        // Step 6 (sourceRecovered): now that we have read sourceProblemStartedAtMs
        // for the return value, clear internal state for subsequent cycles.
        if (sourceRecovered) {
            // State was already cleared by the hasSourceProblem==false branch
            // above; recoveryStartedAtForReturn captured the pre-clear value.
        }

        return ForegroundReportDecision(
            emission = emission,
            triggers = triggers.toList(),
            assessment = assessment,
            sourceProblemStartedAtMs = when {
                hasSourceProblem -> sourceProblemStartedAtMs
                sourceRecovered -> recoveryStartedAtForReturn
                else -> null
            }
        )
    }

    private fun shouldEmitThirtySecondSummary(nowMs: Long): Boolean {
        val last = lastSourceSummaryAtMs ?: return true
        return nowMs - last >= CONFLICT_CONTINUATION_INTERVAL_MS
    }

    private fun markSourceSummaryEmitted(nowMs: Long) {
        lastSourceSummaryAtMs = nowMs
    }
}
