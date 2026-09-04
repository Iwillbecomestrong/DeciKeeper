package com.zhizhi.app.manager
/**
 * Defines whether a process-local state baseline should be emitted when it is first observed.
 * The caller owns this decision because the tracker deliberately has no domain policy.
 */
enum class InitialStatePolicy {
    SILENT_BASELINE,
    EMIT_INITIAL
}

/** A process-local state identity; each distinct domain/entity/state name has its own baseline. */
data class StateKey(
    val domain: String,
    val entityId: String,
    val stateName: String
)

/**
 * Identity supplied by the lock domain for already-classified repeated lock displays.
 * This tracker does not derive duplicate status from any of these fields.
 */
data class DuplicateLockKey(
    val originalRequestId: String,
    val reason: String,
    val packageName: String?,
    val result: String
)

data class DuplicateLockSummary(
    val key: DuplicateLockKey,
    val duplicateCount: Int,
    val firstElapsedMs: Long,
    val lastElapsedMs: Long,
    val durationMs: Long
)

/**
 * Process-memory-only event state and duplicate-lock aggregation.
 *
 * State updates and repeat-lock updates share one lock so every check-and-update is atomic.
 * The tracker only returns data; callers decide if and when to create normal events.
 */
class NormalEventStateTracker {
    private val lock = Any()
    private val lastValues = mutableMapOf<StateKey, String>()
    private val duplicateLocks = mutableMapOf<DuplicateLockKey, DuplicateLockAccumulator>()

    fun shouldEmit(
        key: StateKey,
        value: String,
        initialPolicy: InitialStatePolicy
    ): Boolean = synchronized(lock) {
        val previousValue = lastValues.put(key, value)
        when {
            previousValue == null -> initialPolicy == InitialStatePolicy.EMIT_INITIAL
            else -> previousValue != value
        }
    }

    /** Records one caller-classified repeated display for the exact supplied key. */
    fun recordDuplicateLock(key: DuplicateLockKey, occurredAtElapsedMs: Long) {
        synchronized(lock) {
            val existing = duplicateLocks[key]
            if (existing == null) {
                duplicateLocks[key] = DuplicateLockAccumulator(
                    duplicateCount = 1,
                    firstElapsedMs = occurredAtElapsedMs,
                    lastElapsedMs = occurredAtElapsedMs
                )
            } else {
                existing.duplicateCount += 1
                existing.lastElapsedMs = maxOf(existing.lastElapsedMs, occurredAtElapsedMs)
            }
        }
    }

    /**
     * Returns and clears the current aggregate for [key]. This deliberately emits no event.
     * Duration is based on monotonic elapsed time and never goes below zero.
     */
    fun flushDuplicateLock(
        key: DuplicateLockKey,
        endedAtElapsedMs: Long
    ): DuplicateLockSummary? = synchronized(lock) {
        val accumulator = duplicateLocks.remove(key) ?: return@synchronized null
        val endedAt = maxOf(endedAtElapsedMs, accumulator.lastElapsedMs)
        DuplicateLockSummary(
            key = key,
            duplicateCount = accumulator.duplicateCount,
            firstElapsedMs = accumulator.firstElapsedMs,
            lastElapsedMs = accumulator.lastElapsedMs,
            durationMs = endedAt - accumulator.firstElapsedMs
        )
    }

    /** Visible to module tests only; production creates a fresh tracker on process restart. */
    internal fun resetForTest() {
        synchronized(lock) {
            lastValues.clear()
            duplicateLocks.clear()
        }
    }

    private data class DuplicateLockAccumulator(
        var duplicateCount: Int,
        val firstElapsedMs: Long,
        var lastElapsedMs: Long
    )
}
