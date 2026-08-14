package com.ironwill.app

import java.math.BigDecimal
import java.math.RoundingMode

internal enum class CountAction(val baseHalfUnits: Int) {
    TEMPORARY_ACCESS(1), REST(2)
}

internal object UsableCount {
    const val AUTO_HALF_UNITS = -1

    fun parseHalfUnits(text: String): Int? {
        if (text.isBlank()) return AUTO_HALF_UNITS
        val value = text.trim().toBigDecimalOrNull() ?: return null
        if (value.signum() < 0) return null
        return value.multiply(BigDecimal(2)).setScale(0, RoundingMode.HALF_UP).toInt()
    }

    fun automaticHalfUnits(limitMins: Int): Int =
        BigDecimal(limitMins.coerceAtLeast(0)).divide(BigDecimal(5), 8, RoundingMode.HALF_UP)
            .multiply(BigDecimal(2)).setScale(0, RoundingMode.HALF_UP).toInt().coerceAtLeast(2)

    fun weightedHalfUnitsOrNull(baseHalfUnits: Int, totalDuration: Long, cooldownOverlap: Long): Int? {
        if (totalDuration <= 0 || baseHalfUnits < 0) return null
        val overlap = cooldownOverlap.coerceIn(0, totalDuration)
        val numerator = baseHalfUnits.toLong() * (totalDuration + overlap)
        return ((numerator * 2 + totalDuration) / (2 * totalDuration)).toInt()
    }

    fun weightedHalfUnits(baseHalfUnits: Int, totalDuration: Long, cooldownOverlap: Long): Int =
        weightedHalfUnitsOrNull(baseHalfUnits, totalDuration, cooldownOverlap) ?: 0

    fun formatHalfUnits(halfUnits: Int): String =
        if (halfUnits % 2 == 0) (halfUnits / 2).toString() else "${halfUnits / 2}.5"
}

internal fun formatUsableCountHalfUnits(halfUnits: Int): String =
    UsableCount.formatHalfUnits(halfUnits)
