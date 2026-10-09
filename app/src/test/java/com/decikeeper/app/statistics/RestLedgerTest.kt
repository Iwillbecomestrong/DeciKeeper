package com.decikeeper.app.statistics

import com.decikeeper.app.data.statistics.RestUsageRecord
import com.decikeeper.app.manager.statistics.LedgerFilter
import com.decikeeper.app.manager.statistics.RestLedger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RestLedgerTest {

    private fun record(
        type: String,
        typeName: String = "",
        requested: Double = 0.0,
        costMinutes: Double = 0.0,
        costCount: Double = 0.0,
        balanceCount: Int = 0
    ) = RestUsageRecord(
        id = "id_$type",
        timestamp = 1L,
        dateLabel = "2026-10-08 23:00",
        type = type,
        typeName = typeName,
        requestedMinutes = requested,
        costMinutes = costMinutes,
        costCount = costCount,
        balanceCount = balanceCount
    )

    private fun matching(record: RestUsageRecord): Set<LedgerFilter> =
        LedgerFilter.entries.filter { it != LedgerFilter.ALL && RestLedger.matches(record, it) }.toSet()

    @Test
    fun typesComposeFromKindAndFlags() {
        assertEquals("REST", RestLedger.restType(cooling = false, borrowed = false))
        assertEquals("REST_BORROW", RestLedger.restType(cooling = false, borrowed = true))
        assertEquals("REST_COOLING", RestLedger.restType(cooling = true, borrowed = false))
        assertEquals("REST_BORROW_COOLING", RestLedger.restType(cooling = true, borrowed = true))
        assertEquals("TEMP_BORROW", RestLedger.tempType(cooling = false, borrowed = true))
        assertEquals("借用休息", RestLedger.restTypeName(cooling = false, borrowed = true))
        assertEquals("冷却中借用临时解锁", RestLedger.tempTypeName(cooling = true, borrowed = true))
    }

    @Test
    fun restAndTempMergeIntoNormalBorrowCooling() {
        assertEquals(setOf(LedgerFilter.NORMAL), matching(record("REST")))
        assertEquals(setOf(LedgerFilter.NORMAL), matching(record("TEMP")))
        assertEquals(setOf(LedgerFilter.BORROW), matching(record("REST_BORROW")))
        assertEquals(setOf(LedgerFilter.BORROW), matching(record("TEMP_BORROW")))
        assertEquals(setOf(LedgerFilter.COOLING), matching(record("REST_COOLING")))
        assertEquals(setOf(LedgerFilter.COOLING), matching(record("TEMP_COOLING")))
    }

    @Test
    fun borrowAndCoolingCanBothApply() {
        assertEquals(
            setOf(LedgerFilter.BORROW, LedgerFilter.COOLING),
            matching(record("REST_BORROW_COOLING"))
        )
    }

    @Test
    fun otherKindsHaveTheirOwnFilter() {
        assertEquals(setOf(LedgerFilter.QUOTA_GRANT), matching(record("GRANT")))
        assertEquals(setOf(LedgerFilter.REFUND), matching(record("REFUND")))
        assertEquals(setOf(LedgerFilter.DEDUCTION), matching(record("PENALTY")))
        assertEquals(setOf(LedgerFilter.PRIVILEGE), matching(record("PRIVILEGE")))
    }

    @Test
    fun legacyRestRecordsDetectCoolingByDoubledCost() {
        val normal = record("NORMAL", requested = 10.0, costMinutes = 10.0)
        val cooling = record("NORMAL", requested = 10.0, costMinutes = 20.0)
        val borrow = record("BORROW", requested = 10.0, costMinutes = 10.0)
        val coolingBorrow = record("BORROW", requested = 10.0, costMinutes = 20.0)
        val gapRest = record("NORMAL", typeName = "空档期休息", requested = 10.0, costMinutes = 0.0)

        assertEquals(setOf(LedgerFilter.NORMAL), matching(normal))
        assertEquals(setOf(LedgerFilter.COOLING), matching(cooling))
        assertEquals(setOf(LedgerFilter.BORROW), matching(borrow))
        assertEquals(setOf(LedgerFilter.BORROW, LedgerFilter.COOLING), matching(coolingBorrow))
        assertEquals(setOf(LedgerFilter.NORMAL), matching(gapRest))
    }

    @Test
    fun filterKeepsOnlyMatchingRecords() {
        val rest = record("REST")
        val grant = record("GRANT")
        val all = listOf(rest, grant)

        assertEquals(all, RestLedger.filter(all, LedgerFilter.ALL))
        assertEquals(listOf(grant), RestLedger.filter(all, LedgerFilter.QUOTA_GRANT))
        assertTrue(RestLedger.filter(all, LedgerFilter.PRIVILEGE).isEmpty())
        assertFalse(RestLedger.matches(rest, LedgerFilter.COOLING))
    }

    @Test
    fun filterOrderPutsInflowFirstThenOutflowThenPrivilege() {
        assertEquals(
            listOf("全部", "额度发放", "提前退还", "常规", "借用", "冷却中", "频繁返回扣减", "特权"),
            LedgerFilter.entries.map { it.displayName }
        )
    }

    @Test
    fun legacyRestCountWasStoredAsHalfUnits() {
        // 旧版：冷却中休息扣 2 个 half-unit，却被当成「2 次」展示
        assertEquals(1.0, RestLedger.costCountInTimes(record("NORMAL", costCount = 2.0)), 0.0001)
        assertEquals(0.5, RestLedger.costCountInTimes(record("BORROW", costCount = 1.0)), 0.0001)
    }

    @Test
    fun currentRecordsStoreRealTimes() {
        assertEquals(0.5, RestLedger.costCountInTimes(record("REST", costCount = 0.5)), 0.0001)
        assertEquals(1.0, RestLedger.costCountInTimes(record("PRIVILEGE", costCount = 1.0)), 0.0001)
        assertEquals(-1.5, RestLedger.costCountInTimes(record("GRANT", costCount = -1.5)), 0.0001)
    }

    @Test
    fun balanceCountIsHalfUnitsConvertedToTimes() {
        assertEquals(1.5, RestLedger.balanceCountInTimes(record("REST", balanceCount = 3)), 0.0001)
        assertEquals(0.0, RestLedger.balanceCountInTimes(record("REST", balanceCount = 0)), 0.0001)
    }

    @Test
    fun summaryCountsGrantAndRefundAsInflowAndExcludesPrivilege() {
        val summary = RestLedger.summarize(
            listOf(
                record("GRANT", costMinutes = -60.0, costCount = -3.0),
                record("REFUND", costMinutes = -1.0),
                record("REST", costMinutes = 22.0, costCount = 1.0),
                record("REST_BORROW", costMinutes = 22.0, costCount = 2.0),
                record("PENALTY", costMinutes = 0.5, costCount = 0.5),
                record("PRIVILEGE", costMinutes = 480.0, costCount = 1.0)
            )
        )

        assertEquals(61.0, summary.inflowMinutes, 0.0001)
        assertEquals(3.0, summary.inflowCount, 0.0001)
        assertEquals(44.5, summary.outflowMinutes, 0.0001)
        assertEquals(3.5, summary.outflowCount, 0.0001)
        // 次数出流 3.5 > 入流 3，说明记账有缺口
        assertTrue(summary.inflowShort)
    }

    @Test
    fun summaryIsNotShortWhenInflowCoversOutflow() {
        val summary = RestLedger.summarize(
            listOf(
                record("GRANT", costMinutes = -30.0, costCount = -2.0),
                record("REST", costMinutes = 30.0, costCount = 2.0)
            )
        )
        assertFalse(summary.inflowShort)
    }

    @Test
    fun borrowNoteOnlyMentionsDimensionsThatWereBorrowed() {
        // 只借了次数，时长够用：不写“借时长”
        assertEquals(
            "借次数 1 次，下一段预扣 2 次",
            RestLedger.borrowNote(0, 0, 2, 4)
        )
        assertEquals(
            "借时长 1 分钟，下一段预扣 2 分钟",
            RestLedger.borrowNote(60, 120, 0, 0)
        )
        assertEquals(
            "借时长 1 分钟，下一段预扣 2 分钟；借次数 0.5 次，下一段预扣 1 次",
            RestLedger.borrowNote(60, 120, 1, 2)
        )
    }

    @Test
    fun formattingNeverTruncatesFractions() {
        assertEquals("20", RestLedger.formatAmount(20.0))
        assertEquals("20.9", RestLedger.formatAmount(20.9))
        assertEquals("0.5", RestLedger.formatAmount(0.5))
        assertEquals("+11m", RestLedger.formatDelta(11.0, "m"))
        assertEquals("-0.5次", RestLedger.formatDelta(-0.5, "次"))
        assertEquals("0m", RestLedger.formatDelta(0.0, "m"))
    }
}
