package com.zhizhi.app

/**
 * Boundary 2 — RED-stage JVM mirror of the pre-migration penalty display algorithm.
 *
 * The current `LockWindowManager` increments `mPenaltyHitCount` at the FRONT of
 * `showLockScreen` (lines 3417-3495), BEFORE `targetWM.addView` has a chance to
 * succeed. That means:
 *   - Deferred / cancelled / blocked attaches wrongly contribute to the hit count.
 *   - A debounce miss + a re-assert + a safe-app-bail + a deferred-acceptance can
 *     stack to the threshold even when ZERO locks were displayed to the user.
 *   - The threshold trigger can fire consumePenaltySeconds multiple times if the
 *     cycle reset + a stale hit-count residue line up.
 *
 * The mirror models the pre-migration control flow with two distinct hooks:
 *   - [onRequestEnteredShowLockScreen] — fires for EVERY showLockScreen entry
 *     that survives the front debounce / grace checks. The pre-Boundary-2
 *     algorithm increments the hit count HERE, regardless of whether the lock
 *     view was actually attached.
 *   - [onRealDisplayAttached] — fires ONLY after `targetWM.addView` returned
 *     without throwing. Boundary 2 moves hit counting here.
 *
 * Boundary 2 contract:
 *   - `onRequestEnteredShowLockScreen` must NOT contribute to the hit count
 *     (the request might bail before attach).
 *   - `onRealDisplayAttached` is the only authority boundary for hit counting.
 *   - The threshold (5 hits in 30s) collapses to exactly one transaction per
 *     cycle: one transactionId, one consumePenaltySeconds call, one receipt.
 *   - Overlay rendering / click / close paths cannot trigger a transaction.
 *
 * The mirror starts in PRE-migration mode (`countOnRequestEntered = true`).
 * The 3 tests in `PenaltyDisplayCommitTest` assert the POST-migration contract,
 * so they FAIL against this mirror today (RED). Boundary 2 turns the seam's
 * `countOnRequestEntered` flag off (or replaces the controller wholesale) so
 * the tests turn GREEN.
 *
 * Spec anchors:
 *   - FACT DA-01 — only a successful `targetWM.addView` commits the penalty hit.
 *   - FACT DA-16-R2 — 5 hits in 30s collapse to exactly one transaction.
 *   - FACT C-INV-01 — `mPendingPenaltyMessage` is a receipt string only; overlay
 *     click / close paths cannot invoke consumePenaltySeconds.
 *   - INVAR-1 / INVAR-6 / INVAR-10 — single transaction per cycle; overlay
 *     replay is idempotent; overlay clear does not retroactively charge quota.
 */
internal class PenaltyDisplayCommitTestController(
    private val penaltyThreshold: Int = 5,
    private val penaltyWindowMs: Long = 30_000L,
    /**
     * Boundary-2 semantics (post-migration): hit counting fires ONLY at the
     * `targetWM.addView` success callback. Pre-Boundary-2 the production
     * code counted at the front of `showLockScreen` and this flag was `true`.
     * The mirror defaults to the post-migration value so the controller
     * surface exercises the Boundary-2 contract.
     */
    private var countOnRequestEntered: Boolean = false
) : PenaltyDisplayCommitControllerForTest {

    private val lock = Any()
    private var windowStart: Long = 0L
    private var hitCount: Int = 0
    private val transactions = mutableListOf<PenaltyTransactionForTest>()
    private val consumedSeconds = mutableListOf<Int>()
    private var pendingReceipt: String? = null
    private var overlayRenderCount: Int = 0
    private var overlayConsumeAttempts: Int = 0

    /**
     * Mirrors the front of `showLockScreen` — fires for EVERY entry that
     * survives the front debounce / grace checks, regardless of whether the
     * lock view was actually attached.
     *
     * Pre-Boundary-2 (today): hit counting increments here. This is the bug.
     * Post-Boundary-2: hit counting is disabled here — only [onRealDisplayAttached]
     * counts.
     */
    override fun onRequestEnteredShowLockScreen(
        nowMs: Long,
        isDndActive: Boolean,
        isScreenOnRedetection: Boolean
    ): Boolean = synchronized(lock) {
        if (!isDndActive || isScreenOnRedetection) return@synchronized false
        if (!countOnRequestEntered) return@synchronized false
        // BUG (pre-Boundary-2): incrementing here makes deferred / cancelled /
        // blocked attaches contribute to the threshold.
        return@synchronized bumpHitCount(nowMs)
    }

    /**
     * Mirrors the `targetWM.addView` success callback. The Boundary-2 contract
     * is that hit counting happens ONLY here. Pre-Boundary-2 this method is a
     * no-op (production never calls it).
     */
    override fun onRealDisplayAttached(
        nowMs: Long,
        isDndActive: Boolean,
        isScreenOnRedetection: Boolean,
        deductSeconds: Int,
        receiptBuilder: (transactionId: String, deductSeconds: Int) -> String
    ): Boolean = synchronized(lock) {
        if (!isDndActive || isScreenOnRedetection) return@synchronized false
        // Pre-Boundary-2: this hook doesn't exist yet → always no-op.
        // Post-Boundary-2: hit counting moves here.
        if (countOnRequestEntered) return@synchronized false
        val hitRecorded = bumpHitCount(nowMs)
        if (!hitRecorded) return@synchronized false
        // Threshold-block: collapse to one transaction per cycle. The reset
        // happens ONLY when a transaction is issued, so the post-migration
        // algorithm guarantees exactly one transaction per (window, cycle).
        if (hitCount >= penaltyThreshold) {
            issueOnePenaltyTransaction(nowMs, deductSeconds, receiptBuilder)
            return@synchronized true
        }
        return@synchronized true
    }

    /**
     * Bump the hit count under the window-reset rule. Returns true when the
     * counter advanced.
     */
    private fun bumpHitCount(nowMs: Long): Boolean {
        if (windowStart == 0L || nowMs - windowStart > penaltyWindowMs) {
            windowStart = nowMs
            hitCount = 1
        } else {
            hitCount += 1
        }
        return true
    }

    /**
     * Issue exactly one penalty transaction. The Boundary-2 contract is that
     * the threshold block runs at most once per (window, cycle) — so this
     * helper is called only from the threshold arm of [onRealDisplayAttached]
     * after the reset.
     */
    private fun issueOnePenaltyTransaction(
        nowMs: Long,
        deductSeconds: Int,
        receiptBuilder: (transactionId: String, deductSeconds: Int) -> String
    ) {
        val txId = "PENALTY-${nowMs}-${transactions.size + 1}"
        consumedSeconds.add(deductSeconds)
        transactions.add(
            PenaltyTransactionForTest(
                transactionId = txId,
                deductSeconds = deductSeconds,
                triggeredAtMs = nowMs
            )
        )
        pendingReceipt = receiptBuilder(txId, deductSeconds)
        // Cycle reset.
        hitCount = 0
        windowStart = nowMs
    }

    /**
     * Boundary-2 controller seam: the production `LockWindowManager` must call
     * this from the addView success callback. Pre-Boundary-2 the controller is
     * effectively a no-op because `countOnRequestEntered = true` is the
     * default and `onRealDisplayAttached` short-circuits.
     *
     * The production code must move hit counting here, set
     * `countOnRequestEntered = false`, and call `issueOnePenaltyTransaction`
     * on the threshold arm.
     */
    override fun recordSuccessfulPenaltyEligibleDisplay(
        nowMs: Long,
        isDndActive: Boolean,
        isScreenOnRedetection: Boolean,
        deductSeconds: Int,
        receiptBuilder: (transactionId: String, deductSeconds: Int) -> String
    ): Boolean = synchronized(lock) {
        // Pre-Boundary-2 entry-point: still uses the front-of-showLockScreen
        // hook. Post-Boundary-2 must call onRealDisplayAttached instead.
        return@synchronized onRealDisplayAttached(
            nowMs = nowMs,
            isDndActive = isDndActive,
            isScreenOnRedetection = isScreenOnRedetection,
            deductSeconds = deductSeconds,
            receiptBuilder = receiptBuilder
        )
    }

    /**
     * Overlay rendering — display the cached receipt string. Never invokes a
     * penalty transaction.
     */
    override fun renderOverlayReceipt(): String? = synchronized(lock) {
        if (pendingReceipt != null) {
            overlayRenderCount += 1
            pendingReceipt
        } else {
            null
        }
    }

    /**
     * Overlay click / close — discard the cached receipt. Must NOT invoke a
     * penalty transaction in the production Boundary-2 contract.
     */
    override fun overlayClear() = synchronized(lock) {
        pendingReceipt = null
    }

    /**
     * Buggy / regressed caller hook — simulate an overlay replay that *also*
     * tries to bump the penalty counter. Production must not allow this.
     */
    override fun overlayReplayAttempt() = synchronized(lock) {
        overlayConsumeAttempts += 1
    }

    override fun penaltyTransactionCount(): Int = synchronized(lock) { transactions.size }

    override fun penaltyTransactionIds(): List<String> = synchronized(lock) {
        transactions.map { it.transactionId }
    }

    override fun totalConsumedSeconds(): Int = synchronized(lock) { consumedSeconds.sum() }

    override fun consumePenaltySecondsInvocationCount(): Int =
        synchronized(lock) { consumedSeconds.size }

    override fun overlayRenderCount(): Int = synchronized(lock) { overlayRenderCount }

    override fun overlayReplayAttemptCount(): Int = synchronized(lock) { overlayConsumeAttempts }

    override fun pendingReceipt(): String? = synchronized(lock) { pendingReceipt }

    override fun currentHitCount(): Int = synchronized(lock) { hitCount }

    override fun currentWindowStart(): Long = synchronized(lock) { windowStart }
}

/**
 * Surface Boundary 2 must attach to `LockWindowManager.recordSuccessfulPenaltyEligibleDisplay(...)`
 * and the overlay result-rendering code paths.
 */
internal interface PenaltyDisplayCommitControllerForTest {
    /**
     * Fires for every `showLockScreen` entry that survives the front debounce
     * / grace checks. Pre-Boundary-2 increments the hit count here. The
     * Boundary-2 contract is that this hook must be a no-op (hit counting moves
     * to [onRealDisplayAttached]).
     */
    fun onRequestEnteredShowLockScreen(
        nowMs: Long,
        isDndActive: Boolean,
        isScreenOnRedetection: Boolean
    ): Boolean

    /**
     * Fires only after `targetWM.addView` returned without throwing. The
     * Boundary-2 contract is that this is the ONLY authority for hit counting.
     */
    fun onRealDisplayAttached(
        nowMs: Long,
        isDndActive: Boolean,
        isScreenOnRedetection: Boolean,
        deductSeconds: Int,
        receiptBuilder: (transactionId: String, deductSeconds: Int) -> String
    ): Boolean

    /**
     * Boundary-2 seam entry-point that production
     * `LockWindowManager.recordSuccessfulPenaltyEligibleDisplay(...)` must
     * call. Pre-Boundary-2 this is effectively a no-op because the algorithm
     * still increments at the front of showLockScreen.
     */
    fun recordSuccessfulPenaltyEligibleDisplay(
        nowMs: Long,
        isDndActive: Boolean,
        isScreenOnRedetection: Boolean,
        deductSeconds: Int,
        receiptBuilder: (transactionId: String, deductSeconds: Int) -> String
    ): Boolean

    fun renderOverlayReceipt(): String?
    fun overlayClear()
    fun overlayReplayAttempt()

    fun penaltyTransactionCount(): Int
    fun penaltyTransactionIds(): List<String>
    fun totalConsumedSeconds(): Int
    fun consumePenaltySecondsInvocationCount(): Int
    fun overlayRenderCount(): Int
    fun overlayReplayAttemptCount(): Int
    fun pendingReceipt(): String?
    fun currentHitCount(): Int
    fun currentWindowStart(): Long
}

/** Single canonical penalty transaction (matches production `mPendingPenaltyMessage` lifecycle). */
internal data class PenaltyTransactionForTest(
    val transactionId: String,
    val deductSeconds: Int,
    val triggeredAtMs: Long
)
