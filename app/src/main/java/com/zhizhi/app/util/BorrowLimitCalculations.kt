package com.zhizhi.app.util
internal fun calculateBorrowRepaymentLimitSeconds(
    nextLimitMinutes: Int,
    borrowPercent: Int
): Int {
    val safeLimitSeconds = nextLimitMinutes.coerceAtLeast(0) * 60
    val safePercent = borrowPercent.coerceIn(0, 100)
    return kotlin.math.floor(safeLimitSeconds * (safePercent / 100.0) + 0.5).toInt()
}

internal fun canOccupyBorrowRepaymentCapacity(
    repaymentLimitSec: Int,
    alreadyBorrowedWeightedSec: Int,
    requestedWeightedSec: Int
): Boolean {
    if (repaymentLimitSec <= 0 || requestedWeightedSec <= 0) return false
    return alreadyBorrowedWeightedSec.coerceAtLeast(0) + requestedWeightedSec <= repaymentLimitSec
}

internal fun calculateMaxRawBorrowableMinutes(
    nextLimitMinutes: Int,
    borrowPercent: Int,
    alreadyBorrowedWeightedSec: Int,
    perRawSecondWeight: Int = 2
): Double {
    val limitSec = calculateBorrowRepaymentLimitSeconds(nextLimitMinutes, borrowPercent)
    val remainingWeightedSec = (limitSec - alreadyBorrowedWeightedSec.coerceAtLeast(0)).coerceAtLeast(0)
    val safeWeight = perRawSecondWeight.coerceAtLeast(1)
    return remainingWeightedSec / safeWeight / 60.0
}

internal fun calculateBorrowRepaymentLimitCount(
    nextCountLimit: Int,
    borrowPercent: Int
): Int {
    val safePercent = borrowPercent.coerceIn(0, 100)
    return kotlin.math.floor(nextCountLimit.coerceAtLeast(0) * (safePercent / 100.0) + 0.5).toInt()
}

internal fun canOccupyBorrowRepaymentCount(
    repaymentLimitCount: Int,
    alreadyBorrowedWeightedCount: Int,
    requestedWeightedCount: Int
): Boolean {
    if (repaymentLimitCount <= 0 || requestedWeightedCount <= 0) return false
    return alreadyBorrowedWeightedCount.coerceAtLeast(0) + requestedWeightedCount <= repaymentLimitCount
}

internal fun calculateMaxRawBorrowableCount(
    nextCountLimit: Int,
    borrowPercent: Int,
    alreadyBorrowedWeightedCount: Int,
    perRawCountWeight: Int = 2
): Int {
    val limitCount = calculateBorrowRepaymentLimitCount(nextCountLimit, borrowPercent)
    val remainingWeightedCount = (limitCount - alreadyBorrowedWeightedCount.coerceAtLeast(0)).coerceAtLeast(0)
    return remainingWeightedCount / perRawCountWeight.coerceAtLeast(1)
}
