package com.decikeeper.app.statistics

import com.decikeeper.app.data.statistics.RestUsageRecord
import com.decikeeper.app.manager.statistics.LedgerEntry
import com.decikeeper.app.manager.statistics.LedgerFilter
import com.decikeeper.app.manager.statistics.LedgerSummary
import com.decikeeper.app.manager.statistics.RestLedger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
            listOf("全部", "额度发放", "提前退还", "额度重算", "常规", "借用", "冷却中", "频繁返回扣减", "特权"),
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

    // ── 变动前 / 缺口提示（用 10-09 真机流水的数字）──────────────────────────────

    private val zone = java.time.ZoneId.of("Asia/Shanghai")

    private fun at(hour: Int, minute: Int): Long =
        java.time.ZonedDateTime.of(2026, 10, 9, hour, minute, 0, 0, zone).toInstant().toEpochMilli()

    private fun rest(
        id: String,
        ts: Long,
        cost: Double,
        count: Double,
        afterMinutes: Double,
        afterHalfUnits: Int,
        type: String = "REST",
        advancedMinutes: Double = 0.0,
        advancedCount: Double = 0.0,
        requested: Double = 0.0,
        reason: String = ""
    ) = RestUsageRecord(
        id = id, timestamp = ts, dateLabel = "", type = type, typeName = "", reason = reason,
        requestedMinutes = requested,
        costMinutes = cost, costCount = count,
        balanceMinutes = afterMinutes, balanceCount = afterHalfUnits,
        advancedMinutes = advancedMinutes, advancedCount = advancedCount
    )

    @Test
    fun rowShowsBalanceBeforeUse() {
        val record = rest("r", at(14, 14), cost = 1.0, count = 0.5, afterMinutes = 32.0, afterHalfUnits = 17)
        val row = RestLedger.buildEntries(listOf(record), includeGaps = false, zoneId = zone).single() as LedgerEntry.Row

        assertEquals(33.0, row.beforeMinutes!!, 0.0001)
        assertEquals(9.0, row.beforeCount!!, 0.0001)
    }

    @Test
    fun missingRecordBetweenTwoRowsIsShownAsAGap() {
        // 13:16 变动后 45m / 10 次；14:14 用了 1m / 0.5 次，变动后 32m / 8.5 次 → 两笔之间少了 12m / 1 次
        val newer = rest("14:14", at(14, 14), 1.0, 0.5, 32.0, 17)
        val older = rest("13:16", at(13, 16), 1.1, 0.5, 45.0, 20)

        val entries = RestLedger.buildEntries(listOf(newer, older), includeGaps = true, zoneId = zone)

        assertEquals(3, entries.size)
        val gap = entries[1] as LedgerEntry.Gap
        assertEquals(12.0, gap.missingMinutes, 0.0001)
        assertEquals(1.0, gap.missingCount, 0.0001)
        assertTrue(RestLedger.gapText(gap).contains("账面少了 12m / 1次"))
    }

    @Test
    fun consistentChainHasNoGap() {
        // 12:48 变动后 46.1m / 10.5 次；13:16 用 1.1m / 0.5 次 → 变动后 45m / 10 次，刚好接上
        val newer = rest("13:16", at(13, 16), 1.1, 0.5, 45.0, 20)
        val older = rest("12:48", at(12, 48), 3.0, 0.5, 46.1, 21)

        val entries = RestLedger.buildEntries(listOf(newer, older), includeGaps = true, zoneId = zone)

        assertEquals(2, entries.size)
        assertTrue(entries.all { it is LedgerEntry.Row })
    }

    @Test
    fun grantRowChainsLikeAnyOtherRow() {
        // 12:00 变动后 14.1m / 4 次；12:45 发放 35m / 7 次 → 变动后 49.1m / 11 次；12:48 用 3m / 0.5 次 → 46.1m / 10.5 次
        val use = rest("12:48", at(12, 48), 3.0, 0.5, 46.1, 21)
        val grant = rest("12:45", at(12, 45), -35.0, -7.0, 49.1, 22, type = "GRANT")
        val refund = rest("12:00", at(12, 0), -1.1, 0.0, 14.1, 8, type = "REFUND")

        val entries = RestLedger.buildEntries(listOf(use, grant, refund), includeGaps = true, zoneId = zone)

        assertEquals(3, entries.size)
        assertTrue(entries.all { it is LedgerEntry.Row })
    }

    @Test
    fun legacyBorrowRowsWithoutAdvanceAndPrivilegeDoNotTriggerFalseGaps() {
        // 旧版借用记录没有记提前发放，还原不出变动前，不参与缺口比较
        val borrow = rest("borrow", at(15, 0), cost = 22.0, count = 2.0, afterMinutes = 0.0, afterHalfUnits = 0, type = "REST_BORROW")
        val privilege = rest("priv", at(14, 30), 240.0, 1.0, 99.0, 99, type = "PRIVILEGE")
        val before = rest("before", at(14, 0), 3.0, 0.5, 30.0, 10)

        val entries = RestLedger.buildEntries(listOf(borrow, privilege, before), includeGaps = true, zoneId = zone)

        assertTrue(entries.none { it is LedgerEntry.Gap })
        assertNull((entries[0] as LedgerEntry.Row).beforeMinutes)
        assertNull((entries[1] as LedgerEntry.Row).beforeMinutes)
    }

    // ── 借用 = 提前发放下一段额度再扣除（10 + 15×2 − 40 = 0）────────────────

    /** 11:00 剩 10m / 0.5 次，申请休息 25m（>3 分钟需 1 次）：时长、次数都不够，各预支一半，提前发放 30m / 1 次，总扣 40m / 1.5 次。 */
    private fun borrowRow() = rest(
        "borrow", at(11, 0), cost = 40.0, count = 1.5, afterMinutes = 0.0, afterHalfUnits = 0,
        type = "REST_BORROW", advancedMinutes = 30.0, advancedCount = 1.0, requested = 25.0
    )

    @Test
    fun borrowRowChainsFromBalanceBeforePlusAdvanceMinusCost() {
        val earlier = rest("10:00", at(10, 0), -10.0, -0.5, 10.0, 1, type = "GRANT")
        val entries = RestLedger.buildEntries(listOf(borrowRow(), earlier), includeGaps = true, zoneId = zone)

        val row = entries.filterIsInstance<LedgerEntry.Row>().first { it.record.id == "borrow" }
        assertEquals(10.0, row.beforeMinutes!!, 0.0001)
        assertEquals(0.5, row.beforeCount!!, 0.0001)
        assertTrue("提前发放让借用行接得上，不再冒缺口", entries.none { it is LedgerEntry.Gap })
    }

    @Test
    fun borrowRowCountsAdvanceAsInflowAndFullCostAsOutflowAndMatchesQuotaGrantFilter() {
        val earlier = rest("10:00", at(10, 0), -10.0, -0.5, 10.0, 1, type = "GRANT")
        // 12:00 补发：本段额度 60m，已提前发放 30m，净发放 30m
        val grant = rest("12:00", at(12, 0), -30.0, -2.0, 30.0, 8, type = "GRANT", advancedMinutes = 30.0, advancedCount = 1.0)

        val summary = RestLedger.summarize(listOf(grant, borrowRow(), earlier))

        // 入流 = 10 + 提前发放 30 + 补发 30；出流 = 借用总扣 40
        assertEquals(70.0, summary.inflowMinutes, 0.0001)
        assertEquals(40.0, summary.outflowMinutes, 0.0001)
        assertTrue(RestLedger.matches(borrowRow(), LedgerFilter.QUOTA_GRANT))
        assertTrue(RestLedger.matches(borrowRow(), LedgerFilter.BORROW))
    }

    @Test
    fun reconcileHoldsWhenInflowEqualsOutflowPlusBalance() {
        val summary = LedgerSummary(70.0, 14.0, 40.0, 4.5)

        val ok = RestLedger.reconcile(summary, balanceMinutes = 30.0, balanceHalfUnits = 19)
        assertTrue(ok.isBalanced)
        assertTrue(RestLedger.reconcileText(summary, ok).contains("✓"))

        val off = RestLedger.reconcile(summary, balanceMinutes = 26.0, balanceHalfUnits = 16)
        assertFalse(off.isBalanced)
        assertEquals(4.0, off.diffMinutes, 0.0001)
        assertEquals(1.5, off.diffCount, 0.0001)
        val text = RestLedger.reconcileText(summary, off)
        assertTrue(text.contains("入流 ≠ 出流 + 结余，相差 4m / 1.5次"))
        assertTrue("右边合计写出来，不用带符号的差", text.contains("出流 40m / 4.5次 + 当前结余 26m / 8次 = 66m / 12.5次"))
        assertTrue(text.contains("入流多了"))
        assertFalse(text.contains("差 +"))
    }

    private fun line(record: RestUsageRecord, label: String) =
        RestLedger.detailLines(record).first { it.label == label }

    @Test
    fun borrowDetailMirrorsTheHomePageLayoutWithTimeAndCountSideBySide() {
        // 剩 10m / 0.5 次，申请 25m（需 1 次）：时长、次数都不够
        val lines = RestLedger.detailLines(borrowRow())

        assertEquals("申请休息 25m", lines.first().label)
        with(line(borrowRow(), "本阶段扣除")) { assertEquals("10m", minutes); assertEquals("0.5次", count) }
        with(line(borrowRow(), "本次预支")) { assertEquals("15m", minutes); assertEquals("0.5次", count) }
        with(line(borrowRow(), "借用扣除 (×2)")) { assertEquals("-30m", minutes); assertEquals("-1次", count) }
        with(line(borrowRow(), "借用提前发放")) { assertEquals("+30m", minutes); assertEquals("+1次", count) }
        with(line(borrowRow(), "合计扣除")) { assertEquals("-40m", minutes); assertEquals("-1.5次", count); assertTrue(total) }
    }

    @Test
    fun borrowDetailKeepsTheCountColumnAtZeroWhenOnlyTimeWasBorrowed() {
        // 剩 10m / 4 次，申请 25m（需 1 次）：次数够，只借时长；次数一列写 0次，和主页一样不省掉
        val onlyTime = rest(
            "borrow", at(11, 0), cost = 40.0, count = 1.0, afterMinutes = 0.0, afterHalfUnits = 6,
            type = "REST_BORROW", advancedMinutes = 30.0, requested = 25.0
        )

        with(line(onlyTime, "本阶段扣除")) { assertEquals("10m", minutes); assertEquals("1次", count) }
        with(line(onlyTime, "本次预支")) { assertEquals("15m", minutes); assertEquals("0次", count) }
        with(line(onlyTime, "借用扣除 (×2)")) { assertEquals("-30m", minutes); assertEquals("-0次", count) }
        with(line(onlyTime, "合计扣除")) { assertEquals("-40m", minutes); assertEquals("-1次", count) }
    }

    @Test
    fun grantDetailMirrorsTheHomePageEffectiveQuota() {
        val grant = rest("12:00", at(12, 0), -30.0, -3.0, 30.0, 6, type = "GRANT", advancedMinutes = 30.0, advancedCount = 0.0, reason = "进入「午后」时段，领取本时段额度")

        val lines = RestLedger.detailLines(grant)

        assertEquals("进入「午后」时段，领取本时段额度", lines.first().label)
        with(line(grant, "总配额")) { assertEquals("60m", minutes); assertEquals("3次", count) }
        with(line(grant, "借用扣除 (×2)")) { assertEquals("-30m", minutes); assertEquals("-0次", count) }
        with(line(grant, "实际生效")) { assertEquals("30m", minutes); assertEquals("3次", count); assertTrue(total) }
    }

    @Test
    fun refundDetailExplainsWhatTheNumbersMean() {
        val refund = rest("12:00", at(12, 0), -1.1, 0.0, 14.0, 8, type = "REFUND", requested = 2.0, reason = "提前结束休息返还未用额度")
            .copy(actualMinutes = 0.9)

        assertEquals("2m", line(refund, "申请").minutes)
        assertEquals("0.9m", line(refund, "实际用了").minutes)
        with(line(refund, "退还未用")) { assertEquals("+1.1m", minutes); assertEquals("0次", count) }
    }

    @Test
    fun simpleRecordsHaveNoDetailBecauseTheTitleLineAlreadySaysItAll() {
        val plain = rest("r", at(9, 0), 5.0, 0.5, 20.0, 6, requested = 5.0, reason = "常规申请休息")
        val penalty = rest("p", at(9, 5), 0.5, 0.5, 20.0, 6, type = "PENALTY")

        assertTrue(RestLedger.detailLines(plain).isEmpty())
        assertTrue(RestLedger.detailLines(penalty).isEmpty())
    }

    @Test
    fun coolingRestKeepsADetailBecauseTheDoublingIsNotInTheTitle() {
        val cooling = rest("c", at(9, 0), 10.0, 1.0, 20.0, 6, type = "REST_COOLING", requested = 5.0, reason = "冷却期申请休息（整单×2）")

        assertEquals("申请 5m，冷却中整单 ×2，扣除 −10m", RestLedger.detailText(cooling))
    }

    @Test
    fun gapsAreNotComparedAcrossBusinessDays() {
        val today = rest("today", at(9, 30), 1.0, 0.5, 10.0, 4)
        val yesterday = rest("yesterday", at(23, 30) - 24 * 60 * 60_000L, 1.0, 0.5, 99.0, 40)

        // 业务日 08:00 为界：昨晚 23:30 属于前一个业务日，不与今天 09:30 比较
        val entries = RestLedger.buildEntries(listOf(today, yesterday), includeGaps = true, businessDayStartMinutes = 8 * 60, zoneId = zone)

        assertTrue(entries.none { it is LedgerEntry.Gap })
    }

    @Test
    fun filteredListsNeverShowGaps() {
        val newer = rest("14:14", at(14, 14), 1.0, 0.5, 32.0, 17)
        val older = rest("13:16", at(13, 16), 1.1, 0.5, 45.0, 20)

        val entries = RestLedger.buildEntries(listOf(newer, older), includeGaps = false, zoneId = zone)

        assertTrue(entries.none { it is LedgerEntry.Gap })
    }

    // ── 额度重算（时段被修改后结余变化的出处）──────────────────────────────

    @Test
    fun recalcHasItsOwnFilterAfterRefund() {
        val order = LedgerFilter.entries.toList()
        assertEquals(order.indexOf(LedgerFilter.REFUND) + 1, order.indexOf(LedgerFilter.RECALC))
        assertEquals("额度重算", LedgerFilter.RECALC.displayName)
        assertEquals(setOf(LedgerFilter.RECALC), matching(record("RECALC")))
    }

    @Test
    fun recalcSplitsIntoInflowAndOutflowPerDimension() {
        val summary = RestLedger.summarize(
            listOf(
                // 结余减少 10m，次数增加 1 次：时长走出流，次数走入流
                record("RECALC", costMinutes = 10.0, costCount = -1.0),
                // 结余增加 4m，次数不变
                record("RECALC", costMinutes = -4.0, costCount = 0.0)
            )
        )

        assertEquals(4.0, summary.inflowMinutes, 0.0001)
        assertEquals(1.0, summary.inflowCount, 0.0001)
        assertEquals(10.0, summary.outflowMinutes, 0.0001)
        assertEquals(0.0, summary.outflowCount, 0.0001)
    }

    @Test
    fun recalcChainsLikeAnyOtherBalanceChange() {
        val rest = rest("rest", at(13, 16), 1.1, 0.5, 45.0, 20)
        // 14:00 改时段：结余 45m/10 次 → 33m/9 次（减少 12m、1 次）
        val recalc = rest("recalc", at(14, 0), 12.0, 1.0, 33.0, 18, type = "RECALC")

        val entries = RestLedger.buildEntries(listOf(recalc, rest), includeGaps = true, zoneId = zone)

        val row = entries.filterIsInstance<LedgerEntry.Row>().first { it.record.id == "recalc" }
        assertEquals(45.0, row.beforeMinutes!!, 0.0001)
        assertEquals(10.0, row.beforeCount!!, 0.0001)
        assertTrue("重算记录让链条接上，不再提示缺口", entries.none { it is LedgerEntry.Gap })
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
