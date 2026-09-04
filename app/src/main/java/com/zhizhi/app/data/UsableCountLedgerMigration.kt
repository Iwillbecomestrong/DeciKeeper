package com.zhizhi.app.data
internal object UsableCountLedgerMigration {
    data class Decision(val halfUnits: Int?, val mayUseNewKey: Boolean)

    fun migratedHalfUnits(newValue: Int?, legacyWholeCount: Int?): Int? =
        newValue ?: legacyWholeCount?.let { Math.multiplyExact(it, 2) }

    fun decide(newValue: Int?, legacyWholeCount: Int?, migrationWriteSucceeded: Boolean): Decision {
        if (newValue != null) return Decision(newValue, true)
        if (legacyWholeCount == null) return Decision(null, true)
        val migrated = Math.multiplyExact(legacyWholeCount, 2)
        return Decision(migrated, migrationWriteSucceeded)
    }
}
