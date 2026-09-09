package com.zhizhi.app

/**
 * JVM mirror for the repeated-return-home penalty contract.
 *
 * The legacy interface names are kept temporarily because older regression tests in this
 * repository call them, but their authority is intentionally changed:
 * - onRequestEnteredShowLockScreen = explicit RETURN_HOME_BUTTON user action in this mirror.
 * - onRealDisplayAttached = system overlay attach/re-attach; it must never count.
 */
internal class PenaltyDisplayCommitTestController(
    private val penaltyThreshold: Int = 5,
    private val penaltyWindowMs: Long = 30_000L
) : PenaltyDisplayCommitControllerForTest {

    private val lock = Any()
    private var windowStart: Long = 0L
    private var hitCount: Int = 0
    private val transactions = mutableListOf<PenaltyTransactionForTest>()
    private val consumedSeconds = mutableListOf<Int>()
    private var pendingReceipt: String? = null
    private var overlayRenderCount: Int = 0
    private var overlayConsumeAttempts: Int = 0

    /** Explicit user action seam: one eligible tap on “回到桌面”. */
    override fun onRequestEnteredShowLockScreen(
        nowMs: Long,
        isDndActive: Boolean,
        isScreenOnRedetection: Boolean
    ): Boolean = synchronized(lock) {
        if (!isDndActive) return@synchronized false
        // Screen-on redetection is irrelevant here: a physical Home-button tap is authoritative.
        bumpHitCount(nowMs)
        if (hitCount >= penaltyThreshold) {
            issueOnePenaltyTransaction(
                nowMs = nowMs,
                deductSeconds = 30,
                receiptBuilder = { txId, secs -> "RECEIPT[$txId|${secs}s]" }
            )
        }
        true
    }

    /** System action seam: overlay attachment/re-attachment must not count. */
    override fun onRealDisplayAttached(
        nowMs: Long,
        isDndActive: Boolean,
        isScreenOnRedetection: Boolean,
        deductSeconds: Int,
        receiptBuilder: (transactionId: String, deductSeconds: Int) -> String
    ): Boolean = false

    /** Legacy alias retained only so older source references compile; it is no longer an authority. */
    override fun recordSuccessfulPenaltyEligibleDisplay(
        nowMs: Long,
        isDndActive: Boolean,
        isScreenOnRedetection: Boolean,
        deductSeconds: Int,
        receiptBuilder: (transactionId: String, deductSeconds: Int) -> String
    ): Boolean = false

    private fun bumpHitCount(nowMs: Long) {
        if (windowStart == 0L || nowMs - windowStart > penaltyWindowMs) {
            windowStart = nowMs
            hitCount = 1
        } else {
            hitCount += 1
        }
    }

    private fun issueOnePenaltyTransaction(
        nowMs: Long,
        deductSeconds: Int,
        receiptBuilder: (transactionId: String, deductSeconds: Int) -> String
    ) {
        val txId = "PENALTY-${nowMs}-${transactions.size + 1}"
        consumedSeconds.add(deductSeconds)
        transactions.add(PenaltyTransactionForTest(txId, deductSeconds, nowMs))
        pendingReceipt = receiptBuilder(txId, deductSeconds)
        hitCount = 0
        windowStart = nowMs
    }

    override fun renderOverlayReceipt(): String? = synchronized(lock) {
        pendingReceipt?.also { overlayRenderCount += 1 }
    }

    override fun overlayClear() = synchronized(lock) { pendingReceipt = null }
    override fun overlayReplayAttempt() = synchronized(lock) { overlayConsumeAttempts += 1 }
    override fun penaltyTransactionCount(): Int = synchronized(lock) { transactions.size }
    override fun penaltyTransactionIds(): List<String> = synchronized(lock) { transactions.map { it.transactionId } }
    override fun totalConsumedSeconds(): Int = synchronized(lock) { consumedSeconds.sum() }
    override fun consumePenaltySecondsInvocationCount(): Int = synchronized(lock) { consumedSeconds.size }
    override fun overlayRenderCount(): Int = synchronized(lock) { overlayRenderCount }
    override fun overlayReplayAttemptCount(): Int = synchronized(lock) { overlayConsumeAttempts }
    override fun pendingReceipt(): String? = synchronized(lock) { pendingReceipt }
    override fun currentHitCount(): Int = synchronized(lock) { hitCount }
    override fun currentWindowStart(): Long = synchronized(lock) { windowStart }
}

internal interface PenaltyDisplayCommitControllerForTest {
    fun onRequestEnteredShowLockScreen(
        nowMs: Long,
        isDndActive: Boolean,
        isScreenOnRedetection: Boolean
    ): Boolean

    fun onRealDisplayAttached(
        nowMs: Long,
        isDndActive: Boolean,
        isScreenOnRedetection: Boolean,
        deductSeconds: Int,
        receiptBuilder: (transactionId: String, deductSeconds: Int) -> String
    ): Boolean

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

internal data class PenaltyTransactionForTest(
    val transactionId: String,
    val deductSeconds: Int,
    val triggeredAtMs: Long
)

internal fun newPenaltyDisplayCommitControllerForTest(): PenaltyDisplayCommitControllerForTest =
    PenaltyDisplayCommitTestController()
