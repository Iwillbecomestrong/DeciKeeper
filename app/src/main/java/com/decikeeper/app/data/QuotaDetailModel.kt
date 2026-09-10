package com.decikeeper.app.data
internal data class QuotaDetailModel(
    val currentRemaining: Double,
    val surplusRemaining: Double,
    val surplusTotal: Double
)

internal fun calculateQuotaDetail(
    totalRemaining: Double,
    currentLimit: Double,
    carryRemaining: Double,
    carryTotal: Double
): QuotaDetailModel {
    val safeTotal = totalRemaining.coerceAtLeast(0.0)
    val safeCarryTotal = carryTotal.coerceAtLeast(0.0)
    val visibleCarry = minOf(carryRemaining.coerceAtLeast(0.0), safeCarryTotal, safeTotal)
    return QuotaDetailModel(
        currentRemaining = (safeTotal - visibleCarry).coerceIn(0.0, currentLimit.coerceAtLeast(0.0)),
        surplusRemaining = visibleCarry,
        surplusTotal = safeCarryTotal
    )
}

internal fun buildSurplusSnapshotIdentity(businessDate: String, segmentKey: String): String =
    "$businessDate|$segmentKey"
