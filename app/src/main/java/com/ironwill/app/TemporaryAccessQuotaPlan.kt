package com.ironwill.app

internal data class TemporaryAccessQuotaPlan(
    val totalTimeCostSec: Int,
    val currentAvailableSec: Int,
    val currentTimeCostSec: Int,
    val borrowedTimePrincipalSec: Int,
    val nextTimeCostSec: Int,
    val nextTimeCapacitySec: Int,
    val totalCountCostHalfUnits: Int,
    val currentAvailableCountHalfUnits: Int,
    val currentCountCostHalfUnits: Int,
    val borrowedCountPrincipalHalfUnits: Int,
    val nextCountCostHalfUnits: Int,
    val nextCountCapacityHalfUnits: Int
) {
    val needsNextStage: Boolean
        get() = nextTimeCostSec > 0 || nextCountCostHalfUnits > 0

    val isNextStageAffordable: Boolean
        get() = nextTimeCostSec <= nextTimeCapacitySec && nextCountCostHalfUnits <= nextCountCapacityHalfUnits

    val hasInsufficientTime: Boolean
        get() = nextTimeCostSec > nextTimeCapacitySec

    val hasInsufficientCount: Boolean
        get() = nextCountCostHalfUnits > nextCountCapacityHalfUnits
}

internal fun calculateTemporaryAccessQuotaPlan(
    totalTimeCostSec: Int,
    currentAvailableSec: Int,
    nextAvailableSec: Int,
    totalCountCostHalfUnits: Int,
    currentAvailableCountHalfUnits: Int,
    nextAvailableCountHalfUnits: Int
): TemporaryAccessQuotaPlan {
    val safeTotalTime = totalTimeCostSec.coerceAtLeast(0)
    val safeCurrentSec = currentAvailableSec.coerceAtLeast(0)
    val currentTimeCost = minOf(safeTotalTime, safeCurrentSec)
    val borrowedTimePrincipal = (safeTotalTime - currentTimeCost).coerceAtLeast(0)
    val nextTimeCost = borrowedTimePrincipal * 2

    val safeTotalCount = totalCountCostHalfUnits.coerceAtLeast(0)
    val safeCurrentCount = currentAvailableCountHalfUnits.coerceAtLeast(0)
    val currentCountCost = minOf(safeTotalCount, safeCurrentCount)
    val borrowedCountPrincipal = (safeTotalCount - currentCountCost).coerceAtLeast(0)
    val nextCountCost = borrowedCountPrincipal * 2

    return TemporaryAccessQuotaPlan(
        totalTimeCostSec = safeTotalTime,
        currentAvailableSec = safeCurrentSec,
        currentTimeCostSec = currentTimeCost,
        borrowedTimePrincipalSec = borrowedTimePrincipal,
        nextTimeCostSec = nextTimeCost,
        nextTimeCapacitySec = nextAvailableSec.coerceAtLeast(0),
        totalCountCostHalfUnits = safeTotalCount,
        currentAvailableCountHalfUnits = safeCurrentCount,
        currentCountCostHalfUnits = currentCountCost,
        borrowedCountPrincipalHalfUnits = borrowedCountPrincipal,
        nextCountCostHalfUnits = nextCountCost,
        nextCountCapacityHalfUnits = nextAvailableCountHalfUnits.coerceAtLeast(0)
    )
}

internal fun calculateUnusedTemporaryAccessRefund(
    chargedMinutes: Double,
    plannedSeconds: Int,
    elapsedSeconds: Int
): Double {
    if (chargedMinutes <= 0.0 || plannedSeconds <= 0) return 0.0
    val unusedRatio = (plannedSeconds - elapsedSeconds.coerceAtLeast(0))
        .coerceAtLeast(0).toDouble() / plannedSeconds
    return kotlin.math.round(chargedMinutes * unusedRatio * 10.0) / 10.0
}

internal data class TemporaryAccessRefundPlan(
    val currentRefundSeconds: Int,
    val nextRefundSeconds: Int,
    val borrowRefundSeconds: Int
) {
    val refundedQuotaSeconds: Int
        get() = currentRefundSeconds + nextRefundSeconds
}

internal fun calculateTemporaryAccessRefundPlan(
    currentChargeSeconds: Int,
    nextChargeSeconds: Int,
    borrowChargeSeconds: Int,
    plannedSeconds: Int,
    elapsedSeconds: Int
): TemporaryAccessRefundPlan {
    if (plannedSeconds <= 0) return TemporaryAccessRefundPlan(0, 0, 0)
    val unusedRatio = (plannedSeconds - elapsedSeconds.coerceAtLeast(0))
        .coerceAtLeast(0).toDouble() / plannedSeconds
    fun refund(charged: Int): Int = kotlin.math.round(charged.coerceAtLeast(0) * unusedRatio).toInt()
    return TemporaryAccessRefundPlan(
        currentRefundSeconds = refund(currentChargeSeconds),
        nextRefundSeconds = refund(nextChargeSeconds),
        borrowRefundSeconds = refund(borrowChargeSeconds)
    )
}

internal fun resolveRefundSegmentIndex(activeIndex: Int, previousIndex: Int): Int =
    if (activeIndex >= 0) activeIndex else previousIndex
