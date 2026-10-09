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
    fun aStaleMarkerFromTheOldVersionDoesNotSuppressTheMissingGrant() {
        assumeAwayFromMidnight()
        installAllDaySegment(limitMins = 30)
        val segment = TimeRuleManager.getCurrentSegment(context)!!
        // 旧版先写标记再写账：写账失败会留下「已处理」的假标记（今天「晚上」那笔就是这样丢的）
        context.getSharedPreferences("rest_stats_grant_marker", Context.MODE_PRIVATE).edit()
            .putString("last_granted_segment", QuotaGrantRecorder.grantKey(com.decikeeper.app.manager.CreditManager.getBusinessDateString(context), segment))
            .commit()

        QuotaGrantRecorder.recordIfNewSegment(context)

        assertEquals("假标记不能挡住补记", 1, RestStatsRecorder.getAllRecords(context).count { it.type == RestLedger.TYPE_GRANT })
    }

    @Test
    fun doesNotDuplicateAGrantThatIsAlreadyInTheLedger() {
        assumeAwayFromMidnight()
        installAllDaySegment(limitMins = 30)
        val segment = TimeRuleManager.getCurrentSegment(context)!!
        RestStatsRecorder.record(
            context = context, type = RestLedger.TYPE_GRANT, typeName = RestLedger.TYPE_NAME_GRANT,
            costMinutes = -30.0, costCount = -3.0, balanceMinutes = 30.0, balanceCount = 6,
            timestamp = QuotaGrantRecorder.latestOccurrenceStartMillis(segment, System.currentTimeMillis())
        )

        // 标记丢了（或换了版本）但账已经在：补记前先查账，不重复记
        QuotaGrantRecorder.recordIfNewSegment(context)
        QuotaGrantRecorder.recordIfNewSegment(context)

        assertEquals(1, RestStatsRecorder.getAllRecords(context).count { it.type == RestLedger.TYPE_GRANT })
    }

    @Test
    fun theMarkerIsOnlyWrittenAfterTheGrantIsInTheLedger() {
        assumeAwayFromMidnight()
        installAllDaySegment(limitMins = 30)
        val prefs = context.getSharedPreferences("rest_stats_grant_marker", Context.MODE_PRIVATE)
        assertTrue(prefs.getString("last_granted_segment_v2", null) == null)

        QuotaGrantRecorder.recordIfNewSegment(context)

        assertEquals(1, RestStatsRecorder.getAllRecords(context).count { it.type == RestLedger.TYPE_GRANT })
        assertTrue("记成功后才有标记", prefs.getString("last_granted_segment_v2", null) != null)
    }

    @Test
    fun doesNothingOutsideAnySegment() {
        // 没有任何时段配置
        QuotaGrantRecorder.recordIfNewSegment(context)
        assertTrue(RestStatsRecorder.getAllRecords(context).isEmpty())
    }
}
