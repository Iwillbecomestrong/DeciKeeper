package com.ironwill.app

import com.ironwill.app.manager.LockScreenRequestOutcome

/**
 * Boundary 1 — pure-JVM mirror of the production expiry state machine.
 *
 * The controller enforces the same invariants as the production
 * `LockWindowManager` state machine but lives in the test source set so the
 * algorithm can be exercised without booting Android services.
 *
 * Invariants (mirrors REQ-004 / REQ-005 / INVAR-5):
 *  - The first call for an `(operationId, sessionId)` pair records exactly one
 *    physical lock attempt and returns [LockScreenRequestOutcome.DEFERRED_ACCEPTED].
 *  - Subsequent calls observe FINISHED and return
 *    [LockScreenRequestOutcome.ALREADY_HANDLED] without incrementing the
 *    physical-lock counter.
 *  - Once the cumulative call count for an operationId reaches
 *    `stormThreshold` (default 5, including the initial call), the controller
 *    transitions to FINISHED and emits exactly one [LOCK_RETRY_STORM] event.
 */
internal class ExpiryStormController(
    private val stormThreshold: Int = 5,
    private val stormWindowMs: Long = 60_000L
) : ExpiryControllerForTest {

    private val lock = Any()
    private val states = mutableMapOf<String, TempAccessExpiryState>()
    private val physicalLockBySession = mutableMapOf<String, Int>()
    private val physicalLockByOperation = mutableMapOf<String, Int>()
    private val finishedTransitions = mutableMapOf<String, Int>()
    private val callCountByOperation = mutableMapOf<String, Int>()
    private val events = mutableListOf<ExpiryEvent>()
    private val stormEmittedForOperation = mutableSetOf<String>()

    override fun attemptTemporaryAccessExpiryRelock(
        operationId: TempAccessExpiryOperationId,
        sessionId: TempAccessSessionId,
        targetPkg: String
    ): LockScreenRequestOutcome = synchronized(lock) {
        val opKey = operationId.value
        val sessionKey = sessionId.value
        val callsSoFar = callCountByOperation.getOrDefault(opKey, 0)
        callCountByOperation[opKey] = callsSoFar + 1

        val stateBefore = states[opKey] ?: TempAccessExpiryState.ACTIVE
        // Already finalized → short-circuit.
        if (stateBefore == TempAccessExpiryState.FINISHED) {
            return@synchronized LockScreenRequestOutcome.ALREADY_HANDLED
        }
        // Threshold reached → finalize, emit single storm event, short-circuit.
        // `stormThreshold` is the count of re-entries after the initial acceptance; the
        // storm fires when re-entries reach the threshold (so the storm-triggering call
        // is itself one of the suppressed ticks).
        if (callsSoFar >= stormThreshold && opKey !in stormEmittedForOperation) {
            stormEmittedForOperation.add(opKey)
            states[opKey] = TempAccessExpiryState.FINISHED
            finishedTransitions.merge(opKey, 1, Int::plus)
            // Suppressed count = re-entries (calls after the initial physical lock).
            val suppressed = callsSoFar
            events.add(
                ExpiryEvent(
                    tag = "LOCK_RETRY_STORM",
                    fields = mapOf(
                        "operationId" to opKey,
                        "sessionId" to sessionKey,
                        "suppressedCount" to suppressed.toString(),
                        "result" to "FORCED_FINISHED"
                    )
                )
            )
            return@synchronized LockScreenRequestOutcome.ALREADY_HANDLED
        }
        // First call → record the physical lock and accept.
        if (callsSoFar == 0) {
            states[opKey] = TempAccessExpiryState.EXPIRY_HANDLING
            physicalLockBySession.merge(sessionKey, 1, Int::plus)
            physicalLockByOperation.merge(opKey, 1, Int::plus)
            return@synchronized LockScreenRequestOutcome.DEFERRED_ACCEPTED
        }
        // Subsequent calls within the threshold → short-circuit but don't finalize yet.
        return@synchronized LockScreenRequestOutcome.ALREADY_HANDLED
    }

    override fun physicalLockAttemptCountFor(sessionId: TempAccessSessionId): Int =
        synchronized(lock) { physicalLockBySession[sessionId.value] ?: 0 }

    override fun physicalLockAttemptCountForOperation(
        operationId: TempAccessExpiryOperationId
    ): Int = synchronized(lock) { physicalLockByOperation[operationId.value] ?: 0 }

    override fun finishedTransitionCountFor(
        operationId: TempAccessExpiryOperationId
    ): Int = synchronized(lock) { finishedTransitions[operationId.value] ?: 0 }

    override fun stateOf(operationId: TempAccessExpiryOperationId): TempAccessExpiryState =
        synchronized(lock) { states[operationId.value] ?: TempAccessExpiryState.ACTIVE }

    override fun recordedEvents(): List<ExpiryEvent> = synchronized(lock) { events.toList() }
}
