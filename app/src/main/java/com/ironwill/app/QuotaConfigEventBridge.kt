package com.ironwill.app

/**
 * Quota and configuration outcome bridge. The bridge holds no authority over quota math,
 * grant/reject decisions, rule persistence, or cooldown timing. Production callers hand a
 * completed fact (config persisted, borrow applied, carryover produced, penalty charged,
 * zero-duration approval), and the bridge emits one NormalEventDraft so export and logcat
 * share the same record.
 *
 * Emitted types follow the task-5 contract of the event-driven log noise reduction plan:
 *   CONFIG_CHANGE, RULE_EFFECTIVE, BORROW, REST_QUOTA_CHANGE, REST_COUNT_CHANGE,
 *   CARRYOVER_CREATED, CARRYOVER_USED, PENALTY, ALLOW_START (zero-duration variant).
 */
class QuotaConfigEventBridge(
    private val clock: EventClock,
    private val emit: (NormalEventDraft) -> Unit,
    private val debugConfig: LogDebugConfig
) {

    fun recordConfigChange(
        configId: String,
        scope: String,
        action: String,
        oldValue: String,
        newValue: String,
        effectiveAt: Long,
        apply: String,
        packageName: String? = null,
        ruleId: String? = null
    ) {
        val fields = LinkedHashMap<String, String>()
        fields["configId"] = configId
        fields["scope"] = scope
        fields["action"] = action
        fields["old"] = oldValue
        fields["new"] = newValue
        fields["effectiveAt"] = effectiveAt.toString()
        fields["apply"] = apply
        packageName?.let { fields["packageName"] = it }
        ruleId?.let { fields["ruleId"] = it }
        emit(
            NormalEvents.create(
                type = "CONFIG_CHANGE",
                clock = clock,
                fields = fields
            )
        )
    }

    fun recordRuleEffective(
        configId: String,
        batchId: String,
        scope: String,
        effectiveAt: Long,
        oldValue: String,
        newValue: String,
        result: String
    ) {
        val fields = LinkedHashMap<String, String>()
        fields["configId"] = configId
        fields["batchId"] = batchId
        fields["scope"] = scope
        fields["effectiveAt"] = effectiveAt.toString()
        fields["old"] = oldValue
        fields["new"] = newValue
        fields["result"] = result
        emit(
            NormalEvents.create(
                type = "RULE_EFFECTIVE",
                clock = clock,
                fields = fields
            )
        )
    }

    fun recordBorrow(
        transactionId: String,
        kind: String,
        fromSegment: String,
        toSegment: String,
        received: String,
        multiplier: String,
        charged: String,
        fromBalanceBefore: String,
        fromBalanceAfter: String,
        reason: String,
        result: String
    ) {
        val fields = LinkedHashMap<String, String>()
        fields["transactionId"] = transactionId
        fields["kind"] = kind
        fields["fromSegment"] = fromSegment
        fields["toSegment"] = toSegment
        fields["received"] = received
        fields["multiplier"] = multiplier
        fields["charged"] = charged
        fields["fromBalanceBefore"] = fromBalanceBefore
        fields["fromBalanceAfter"] = fromBalanceAfter
        fields["reason"] = reason
        fields["result"] = result
        emit(
            NormalEvents.create(
                type = "BORROW",
                clock = clock,
                fields = fields
            )
        )
    }

    fun recordRestQuotaChange(
        sessionId: String,
        before: String,
        after: String,
        reason: String
    ) {
        emit(
            NormalEvents.create(
                type = "REST_QUOTA_CHANGE",
                clock = clock,
                fields = linkedMapOf(
                    "sessionId" to sessionId,
                    "before" to before,
                    "after" to after,
                    "reason" to reason
                )
            )
        )
    }

    fun recordRestCountChange(
        sessionId: String,
        before: String,
        after: String,
        reason: String
    ) {
        emit(
            NormalEvents.create(
                type = "REST_COUNT_CHANGE",
                clock = clock,
                fields = linkedMapOf(
                    "sessionId" to sessionId,
                    "before" to before,
                    "after" to after,
                    "reason" to reason
                )
            )
        )
    }

    fun recordCarryoverCreated(
        transactionId: String,
        fromSegment: String,
        amount: String,
        kind: String
    ) {
        emit(
            NormalEvents.create(
                type = "CARRYOVER_CREATED",
                clock = clock,
                fields = linkedMapOf(
                    "transactionId" to transactionId,
                    "fromSegment" to fromSegment,
                    "amount" to amount,
                    "kind" to kind
                )
            )
        )
    }

    fun recordCarryoverUsed(
        transactionId: String,
        fromSegment: String,
        amount: String,
        kind: String,
        consumedBy: String
    ) {
        emit(
            NormalEvents.create(
                type = "CARRYOVER_USED",
                clock = clock,
                fields = linkedMapOf(
                    "transactionId" to transactionId,
                    "fromSegment" to fromSegment,
                    "amount" to amount,
                    "kind" to kind,
                    "consumedBy" to consumedBy
                )
            )
        )
    }

    fun recordPenalty(
        transactionId: String,
        ruleId: String,
        durationSec: String,
        reason: String,
        normalSec: String = "0",
        borrowedSec: String = "0",
        borrowedCostSec: String = "0",
        remainingMin: String = "0min",
        remainingCount: String = "0",
        cooldownAddedSec: String = "0",
        cooldownKind: String = "NONE"
    ) {
        emit(
            NormalEvents.create(
                type = "PENALTY",
                clock = clock,
                fields = linkedMapOf(
                    "transactionId" to transactionId,
                    "ruleId" to ruleId,
                    "durationSec" to durationSec,
                    "reason" to reason,
                    "normalSec" to normalSec,
                    "borrowedSec" to borrowedSec,
                    "borrowedCostSec" to borrowedCostSec,
                    "remainingMin" to remainingMin,
                    "remainingCount" to remainingCount,
                    "cooldownAddedSec" to cooldownAddedSec,
                    "cooldownKind" to cooldownKind
                )
            )
        )
    }

    /**
     * Approval outcomes with zero cost are facts that must remain visible. Logging
     * them as ALLOW_START keeps export grammar consistent with the lifecycle bridge.
     */
    fun recordZeroDurationAllowedTransaction(
        transactionId: String,
        restCost: String
    ) {
        emit(
            NormalEvents.create(
                type = "ALLOW_START",
                clock = clock,
                fields = linkedMapOf(
                    "transactionId" to transactionId,
                    "restCost" to restCost
                )
            )
        )
    }
}
