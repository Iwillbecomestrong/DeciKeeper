package com.decikeeper.app.statistics

import android.content.Context
import com.decikeeper.app.data.TimeSegment
import com.decikeeper.app.manager.CreditManager
import com.decikeeper.app.manager.TimeRuleManager
import com.decikeeper.app.manager.statistics.QuotaRecalcRecorder
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
    fun describeChangeFallsBackToAGenericNoteWhenOnlyTimesOrCountsChanged() {
        val old = listOf(allDay(30))
        val new = listOf(allDay(30).copy(endHour = 20))

        assertTrue(QuotaRecalcRecorder.describeChange(old, new).contains("时段设置"))
    }
}
