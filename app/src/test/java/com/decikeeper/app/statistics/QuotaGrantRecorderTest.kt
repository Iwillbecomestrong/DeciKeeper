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
    fun doesNothingOutsideAnySegment() {
        // 没有任何时段配置
        QuotaGrantRecorder.recordIfNewSegment(context)
        assertTrue(RestStatsRecorder.getAllRecords(context).isEmpty())
    }
}
