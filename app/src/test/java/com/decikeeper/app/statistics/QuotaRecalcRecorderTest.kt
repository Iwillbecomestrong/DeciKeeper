package com.decikeeper.app.statistics

import android.content.Context
import com.decikeeper.app.data.TimeSegment
import com.decikeeper.app.manager.CreditManager
import com.decikeeper.app.manager.TimeRuleManager
import com.decikeeper.app.manager.statistics.QuotaRecalcRecorder
import com.decikeeper.app.data.statistics.RestUsageRecord
import com.decikeeper.app.manager.statistics.LedgerFilter
import com.decikeeper.app.manager.statistics.RestLedger
import com.decikeeper.app.manager.statistics.RestStatsRecorder
import java.util.Calendar
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class QuotaRecalcRecorderTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        RestStatsRecorder.clearAll(context)
        context.getSharedPreferences("decikeeper_rules", Context.MODE_PRIVATE).edit().clear().commit()
        TimeRuleManager.invalidateSegmentsCache()
        TimeRuleManager.saveRefreshTime(context, "00:00")
    }

    @After
    fun tearDown() {
        RestStatsRecorder.clearAll(context)
    }

    private fun allDay(limitMins: Int) = TimeSegment(
        startHour = 0, startMinute = 0, endHour = 23, endMinute = 59,
        limitMins = limitMins, name = "全天"
    )

    private fun install(segment: TimeSegment) {
        context.getSharedPreferences("decikeeper_rules", Context.MODE_PRIVATE).edit()
            .putString("segments_json", TimeRuleManager.segmentsToJson(listOf(segment)))
            .commit()
        TimeRuleManager.invalidateSegmentsCache()
    }

    private fun assumeAwayFromMidnight() {
        val cal = Calendar.getInstance()
        val nowMin = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        assumeTrue(nowMin in 5..(23 * 60 + 54))
    }

    private fun recalcRecords() =
        RestStatsRecorder.getAllRecords(context).filter { it.type == RestLedger.TYPE_RECALC }

    @Test
    fun recordsTheBalanceChangeCausedByEditingASegment() {
        assumeAwayFromMidnight()
        install(allDay(limitMins = 30))
        val before = CreditManager.getRemainingMinutes(context)

        QuotaRecalcRecorder.around(context) { install(allDay(limitMins = 20)) }

        val after = CreditManager.getRemainingMinutes(context)
        val record = recalcRecords().single()
        assertEquals(RestLedger.TYPE_NAME_RECALC, record.typeName)
        // 成本 = 变动前 − 变动后，结余减少为正
        assertEquals(before - after, record.costMinutes, 0.001)
        assertTrue("额度由 30 降到 20，结余应当减少", record.costMinutes > 0.0)
        assertEquals(after, record.balanceMinutes, 0.001)
        assertEquals(CreditManager.getRemainingUsableCount(context), record.balanceCount)
        assertTrue(record.reason.contains("全天"))
        assertTrue(record.reason.contains("30") && record.reason.contains("20"))
    }

    @Test
    fun raisingTheQuotaIsRecordedAsNegativeCost() {
        assumeAwayFromMidnight()
        install(allDay(limitMins = 20))

        QuotaRecalcRecorder.around(context) { install(allDay(limitMins = 30)) }

        assertTrue("结余增加记为负成本，与额度发放同一符号约定", recalcRecords().single().costMinutes < 0.0)
    }

    @Test
    fun switchingToAllNextDayRecordsTheRollbackToBaseline() {
        assumeAwayFromMidnight()
        CreditManager.markAdaptationEnded(context)
        context.getSharedPreferences("decikeeper_rules_baseline", Context.MODE_PRIVATE).edit()
            .putString("segments_json", TimeRuleManager.segmentsToJson(listOf(allDay(40)))).commit()
        install(allDay(limitMins = 20))

        // 统一延后会把生效中的时段回滚到基线，结余随之变化，但不经过 saveTimeSegments
        TimeRuleManager.recalculatePendingAfterPolicySwitch(
            context, com.decikeeper.app.manager.EffectiveActivationPolicyManager.Policy.ALL_NEXT_DAY
        )

        val record = recalcRecords().single()
        assertTrue(record.reason, record.reason.contains("额度 20 → 40"))
        assertEquals(CreditManager.getRemainingMinutes(context), record.balanceMinutes, 0.001)
    }

    @Test
    fun noRecordWhenNothingChanged() {
        assumeAwayFromMidnight()
        install(allDay(limitMins = 30))

        QuotaRecalcRecorder.around(context) { install(allDay(limitMins = 30)) }

        assertTrue(recalcRecords().isEmpty())
    }

    @Test
    fun noRecordWhileSettlingABusinessDayChange() {
        assumeAwayFromMidnight()
        install(allDay(limitMins = 30))

        // 业务日切换时 pending 并入是额度重新开始，不是重算
        CreditManager.withDayChangeSettlement { QuotaRecalcRecorder.around(context) { install(allDay(limitMins = 20)) } }

        assertTrue(recalcRecords().isEmpty())
    }

    @Test
    fun theActionAlwaysRunsEvenWhenNoSegmentsExist() {
        var ran = false
        QuotaRecalcRecorder.around(context) { ran = true }
        assertTrue(ran)
        assertTrue(recalcRecords().isEmpty())
    }

    @Test
    fun describeChangeNamesTheSegmentsWhoseQuotaChanged() {
        val old = listOf(allDay(30), TimeSegment(startHour = 1, endHour = 2, limitMins = 10, name = "夜间"))
        val new = listOf(allDay(20), TimeSegment(startHour = 1, endHour = 2, limitMins = 10, name = "夜间"))

        val text = QuotaRecalcRecorder.describeChange(old, new)

        assertTrue(text.contains("全天") && text.contains("30") && text.contains("20"))
        assertTrue("没变的时段不写", !text.contains("夜间"))
    }

    @Test
    fun describeChangeWritesTimeRangeAndCountChanges() {
        val old = listOf(allDay(30))
        val new = listOf(allDay(30).copy(endHour = 20, endMinute = 0, usableCountLimitHalfUnits = 4))

        val text = QuotaRecalcRecorder.describeChange(old, new)

        assertTrue(text, text.contains("时间 00:00-23:59 → 00:00-20:00"))
        assertTrue(text, text.contains("可用次数") && text.contains("→ 2 次"))
        assertTrue("额度没变不写额度", !text.contains("额度 30"))
    }

    @Test
    fun describeChangeExplainsWhyTheBalanceCameBack() {
        val stamped = allDay(30).copy(lastModified = 1_000L)
        val cleared = stamped.copy(lastModified = 0L)

        val restored = QuotaRecalcRecorder.describeChange(listOf(stamped), listOf(cleared))
        val penalized = QuotaRecalcRecorder.describeChange(listOf(cleared), listOf(stamped))

        assertTrue(restored, restored.contains("改动标记被清除") && restored.contains("重新计入"))
        assertTrue(penalized, penalized.contains("记为今天改动") && penalized.contains("不再滚存"))
    }

    @Test
    fun describeChangeNamesAddedRemovedSegmentsAndPositionChange() {
        val old = listOf(allDay(30), TimeSegment(startHour = 1, endHour = 2, limitMins = 10, name = "夜间"))
        val new = listOf(allDay(30), TimeSegment(startHour = 3, endHour = 4, limitMins = 15, name = "凌晨"))

        val text = QuotaRecalcRecorder.describeChange(old, new, oldCurrent = "夜间", newCurrent = null)

        assertTrue(text, text.contains("新增「凌晨」03:00-04:00，额度 15 分钟"))
        assertTrue(text, text.contains("删除「夜间」"))
        assertTrue(text, text.contains("此刻所在时段由「夜间」变为空档期"))
    }

    @Test
    fun balanceShiftNamesTheSegmentsWhoseShareChanged() {
        val text = QuotaRecalcRecorder.describeBalanceShift(
            beforeMinutes = 42.1, afterMinutes = 31.0,
            beforeHalfUnits = 21, afterHalfUnits = 13,
            beforeBreakdown = listOf("上午" to 0.0, "下午" to 10.1, "晚上" to 32.0),
            afterBreakdown = listOf("上午" to 0.0, "下午" to 0.0, "晚上" to 31.0)
        )

        assertTrue(text, text.contains("结余 42.1 → 31 分钟") && text.contains("次数 10.5 → 6.5 次"))
        assertTrue(text, text.contains("「下午」10.1 → 0 分钟") && text.contains("「晚上」32 → 31 分钟"))
        assertTrue("没变的段不写", !text.contains("上午"))
        assertTrue("减少不告警", !text.contains("⚠"))
    }

    @Test
    fun balanceShiftFlagsAnIncrease() {
        val text = QuotaRecalcRecorder.describeBalanceShift(
            17.0, 27.1, 2, 2,
            listOf("下午" to 0.0, "晚上" to 17.0), listOf("下午" to 10.1, "晚上" to 17.0)
        )

        assertTrue(text, text.contains("「下午」0 → 10.1 分钟"))
        assertTrue(text, text.contains("⚠ 结余增加"))
    }

    @Test
    fun balanceShiftWithoutBreakdownSaysWhy() {
        val text = QuotaRecalcRecorder.describeBalanceShift(5.0, 3.0, 2, 2, null, listOf("全天" to 3.0))

        assertTrue(text, text.contains("空档期结余只含前序时段滚存"))
    }

    @Test
    fun describeChangeFallsBackToAGenericNoteWhenNothingIsComparable() {
        val old = listOf(allDay(30))

        assertTrue(QuotaRecalcRecorder.describeChange(old, old).contains("时段设置"))
    }

    // ── 一增一减拆成两条 ──────────────────────────────────────────────

    @Test
    fun opposingMinutesAndCountSplitIntoTwoChainedParts() {
        // 时长 19 → 24（增加 5），次数 3 → 2 次（减少 1 次 = 2 half-unit）
        val parts = QuotaRecalcRecorder.splitParts(19.0, 24.0, 6, 4)

        assertEquals(2, parts.size)
        // 第一条只动时长：次数结余还是改动前的
        assertEquals(-5.0, parts[0].costMinutes, 0.0001)
        assertEquals(0, parts[0].costHalfUnits)
        assertEquals(24.0, parts[0].balanceMinutes, 0.0001)
        assertEquals(6, parts[0].balanceHalfUnits)
        // 第二条只动次数
        assertEquals(0.0, parts[1].costMinutes, 0.0001)
        assertEquals(2, parts[1].costHalfUnits)
        assertEquals(24.0, parts[1].balanceMinutes, 0.0001)
        assertEquals(4, parts[1].balanceHalfUnits)
        // 首尾相接：第二条的变动前 = 第一条的变动后
        assertEquals(parts[0].balanceMinutes, parts[1].balanceMinutes + parts[1].costMinutes, 0.0001)
        assertEquals(parts[0].balanceHalfUnits, parts[1].balanceHalfUnits + parts[1].costHalfUnits)
    }

    @Test
    fun theSplitPartsEachLandInOneDirectionOnly() {
        val parts = QuotaRecalcRecorder.splitParts(19.0, 24.0, 6, 4)
        val records = parts.map {
            RestUsageRecord(
                id = "x", timestamp = 1L, dateLabel = "", type = RestLedger.TYPE_RECALC, typeName = "额度重算",
                costMinutes = it.costMinutes, costCount = it.costHalfUnits / 2.0,
                balanceMinutes = it.balanceMinutes, balanceCount = it.balanceHalfUnits
            )
        }

        assertTrue(RestLedger.matches(records[0], LedgerFilter.RECALC_INCREASE))
        assertTrue(!RestLedger.matches(records[0], LedgerFilter.RECALC_DECREASE))
        assertTrue(RestLedger.matches(records[1], LedgerFilter.RECALC_DECREASE))
        assertTrue(!RestLedger.matches(records[1], LedgerFilter.RECALC_INCREASE))
    }

    @Test
    fun sameDirectionOrSingleDimensionStaysOneRecord() {
        // 都减少
        assertEquals(1, QuotaRecalcRecorder.splitParts(20.0, 10.0, 6, 2).size)
        // 都增加
        assertEquals(1, QuotaRecalcRecorder.splitParts(10.0, 20.0, 2, 6).size)
        // 只动次数
        val onlyCount = QuotaRecalcRecorder.splitParts(10.0, 10.0, 0, 8)
        assertEquals(1, onlyCount.size)
        assertEquals(-8, onlyCount.single().costHalfUnits)
    }
}
