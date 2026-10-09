package com.decikeeper.app.statistics

import android.content.Context
import com.decikeeper.app.data.TimeSegment
import com.decikeeper.app.manager.TimeRuleManager
import com.decikeeper.app.manager.statistics.QuotaGrantRecorder
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
class QuotaGrantRecorderTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        RestStatsRecorder.clearAll(context)
        context.getSharedPreferences("decikeeper_rules", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("rest_stats_grant_marker", Context.MODE_PRIVATE).edit().clear().commit()
        TimeRuleManager.invalidateSegmentsCache()
        TimeRuleManager.saveRefreshTime(context, "00:00")
    }

    @After
    fun tearDown() {
        RestStatsRecorder.clearAll(context)
    }

    private fun installAllDaySegment(limitMins: Int) {
        val segment = TimeSegment(
            startHour = 0, startMinute = 0, endHour = 23, endMinute = 59,
            limitMins = limitMins, name = "全天"
        )
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

    @Test
    fun recordsOneGrantPerSegmentOccurrence() {
        assumeAwayFromMidnight()
        installAllDaySegment(limitMins = 30)

        QuotaGrantRecorder.recordIfNewSegment(context)
        QuotaGrantRecorder.recordIfNewSegment(context)

        val grants = RestStatsRecorder.getAllRecords(context).filter { it.type == RestLedger.TYPE_GRANT }
        assertEquals("同一时段同一业务日只记一次", 1, grants.size)
        val grant = grants.single()
        assertEquals(RestLedger.TYPE_NAME_GRANT, grant.typeName)
        // 成本为负 = 额度增加
        assertEquals(-30.0, grant.costMinutes, 0.001)
        assertTrue(grant.costCount < 0.0)
        assertTrue("发放时间取时段起点，不晚于现在", grant.timestamp <= System.currentTimeMillis())
    }

    @Test
    fun lateDetectedGrantChainsFromThePreviousRecordInsteadOfUsingTheCurrentBalance() {
        assumeAwayFromMidnight()
        installAllDaySegment(limitMins = 30)
        // 业务日从 00:00 起；上一笔流水正好落在时段开始时刻：变动后 14.1m / 4 次（8 个 half-unit）
        val startMillis = QuotaGrantRecorder.latestOccurrenceStartMillis(
            TimeRuleManager.getCurrentSegment(context)!!, System.currentTimeMillis()
        )
        RestStatsRecorder.record(
            context = context, type = RestLedger.TYPE_REST, typeName = "常规休息",
            costMinutes = 1.0, costCount = 0.5, balanceMinutes = 14.1, balanceCount = 8,
            timestamp = startMillis
        )

        // 现在离时段开始已经超过 2 分钟（assumeAwayFromMidnight 保证），按「补记」处理
        QuotaGrantRecorder.recordIfNewSegment(context)

        val grant = RestStatsRecorder.getAllRecords(context).single { it.type == RestLedger.TYPE_GRANT }
        val grantedHalfUnits = (-grant.costCount * 2).toInt()
        assertEquals(14.1 + 30.0, grant.balanceMinutes, 0.001)
        assertEquals(8 + grantedHalfUnits, grant.balanceCount)
        assertTrue(grant.reason.contains("补记"))
    }

    @Test
    fun grantOnlyPaysTheRemainderOfWhatWasAdvancedByABorrow() {
        assumeAwayFromMidnight()
        installAllDaySegment(limitMins = 30)
        val segment = TimeRuleManager.getCurrentSegment(context)!!
        // 上一段借用时提前发放了本段 10m / 1 次
        QuotaGrantRecorder.notePendingAdvance(context, segment, minutes = 10.0, count = 1.0)

        QuotaGrantRecorder.recordIfNewSegment(context)

        val grant = RestStatsRecorder.getAllRecords(context).single { it.type == RestLedger.TYPE_GRANT }
        assertEquals("净发放 = 本段额度 30m − 已提前发放 10m", -20.0, grant.costMinutes, 0.001)
        assertEquals(10.0, grant.advancedMinutes, 0.001)
        assertEquals(1.0, grant.advancedCount, 0.001)
        assertTrue("次数净发放也扣掉了已提前发放的 1 次", -grant.costCount + grant.advancedCount > 0.0)
    }

    @Test
    fun theAdvanceIsConsumedByTheFirstGrantOnly() {
        assumeAwayFromMidnight()
        installAllDaySegment(limitMins = 30)
        val segment = TimeRuleManager.getCurrentSegment(context)!!
        QuotaGrantRecorder.notePendingAdvance(context, segment, minutes = 10.0, count = 0.0)
        QuotaGrantRecorder.notePendingAdvance(context, segment, minutes = 5.0, count = 0.0)

        QuotaGrantRecorder.recordIfNewSegment(context)

        // 同一时段多次借用累加
        val grant = RestStatsRecorder.getAllRecords(context).single { it.type == RestLedger.TYPE_GRANT }
        assertEquals(15.0, grant.advancedMinutes, 0.001)
        assertEquals(-15.0, grant.costMinutes, 0.001)
    }

    @Test
    fun advanceLargerThanTheGrantNeverMakesTheGrantNegative() {
        assumeAwayFromMidnight()
        installAllDaySegment(limitMins = 30)
        val segment = TimeRuleManager.getCurrentSegment(context)!!
        QuotaGrantRecorder.notePendingAdvance(context, segment, minutes = 99.0, count = 0.0)

        QuotaGrantRecorder.recordIfNewSegment(context)

        val grant = RestStatsRecorder.getAllRecords(context).single { it.type == RestLedger.TYPE_GRANT }
        assertEquals(0.0, grant.costMinutes, 0.001)
        assertEquals(30.0, grant.advancedMinutes, 0.001)
    }

    @Test
    fun doesNothingOutsideAnySegment() {
        // 没有任何时段配置
        QuotaGrantRecorder.recordIfNewSegment(context)
        assertTrue(RestStatsRecorder.getAllRecords(context).isEmpty())
    }
}
