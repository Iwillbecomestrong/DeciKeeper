package com.decikeeper.app.statistics

import android.content.Context
import com.decikeeper.app.data.TimeSegment
import com.decikeeper.app.manager.CreditManager
import com.decikeeper.app.manager.TimeRuleManager
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

/**
 * 时段「今日额度」按业务日存账：改严只记差额，已过的时段照常滚存；
 * 不再靠 lastModified 记号折算，也不会因为某次保存丢了记号而把额度加回来。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class DailySegmentQuotaLedgerTest {

    private lateinit var context: Context
    private var splitMinute = 0

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        RestStatsRecorder.clearAll(context)
        for (name in listOf("decikeeper_rules", "decikeeper_rules_baseline", "decikeeper_rules_pending", "decikeeper_ledger")) {
            context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit()
        }
        TimeRuleManager.invalidateSegmentsCache()
        TimeRuleManager.saveRefreshTime(context, "00:00")
        CreditManager.markAdaptationEnded(context)

        val cal = Calendar.getInstance()
        val nowMin = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        assumeTrue(nowMin in 30..(23 * 60 + 50))
        splitMinute = nowMin - 10
    }

    @After
    fun tearDown() {
        RestStatsRecorder.clearAll(context)
    }

    /** 已过去的前段 + 正在进行的当前段 */
    private fun segments(pastLimit: Int, currentLimit: Int, stamp: Long = 0L) = listOf(
        TimeSegment(
            startHour = 0, startMinute = 0, endHour = splitMinute / 60, endMinute = splitMinute % 60,
            limitMins = pastLimit, name = "前段", lastModified = stamp
        ),
        TimeSegment(
            startHour = splitMinute / 60, startMinute = splitMinute % 60, endHour = 23, endMinute = 59,
            limitMins = currentLimit, name = "当前段", lastModified = 0L
        )
    )

    private fun install(list: List<TimeSegment>) {
        val json = TimeRuleManager.segmentsToJson(list)
        context.getSharedPreferences("decikeeper_rules", Context.MODE_PRIVATE).edit().putString("segments_json", json).commit()
        context.getSharedPreferences("decikeeper_rules_baseline", Context.MODE_PRIVATE).edit().putString("segments_json", json).commit()
        TimeRuleManager.invalidateSegmentsCache()
    }

    private fun recalcRecords() =
        RestStatsRecorder.getAllRecords(context).filter { it.type == RestLedger.TYPE_RECALC }

    @Test
    fun tighteningAPastSegmentOnlyRemovesTheDifferenceFromTheCarry() {
        install(segments(pastLimit = 30, currentLimit = 20))
        assertEquals(50.0, CreditManager.getRemainingMinutes(context), 0.01)

        // 界面编辑会给被编辑的时段打上「现在」的记号；改严已过的前段 30 → 25
        TimeRuleManager.analyzeAndSaveTimeSegments(context, segments(25, 20, stamp = System.currentTimeMillis()))

        assertEquals("只少差额 5，滚存不清零", 45.0, CreditManager.getRemainingMinutes(context), 0.01)
        val record = recalcRecords().single()
        assertEquals(5.0, record.costMinutes, 0.01)
        assertTrue(record.reason, !record.reason.contains("⚠"))
    }

    @Test
    fun aLaterMixedSaveDoesNotGiveTheTightenedAmountBack() {
        install(segments(pastLimit = 30, currentLimit = 20))
        CreditManager.getRemainingMinutes(context)
        TimeRuleManager.analyzeAndSaveTimeSegments(context, segments(25, 20, stamp = System.currentTimeMillis()))
        val afterTighten = CreditManager.getRemainingMinutes(context)

        // 再保存一次：当前段放宽到 25（不利，进 pending），前段保持改严后的 25
        TimeRuleManager.analyzeAndSaveTimeSegments(context, segments(25, 25, stamp = System.currentTimeMillis()))

        assertEquals("混合保存不改变今天的结余", afterTighten, CreditManager.getRemainingMinutes(context), 0.01)
        assertTrue(
            "不应出现结余增加的额度重算",
            recalcRecords().none { it.costMinutes < -0.01 }
        )
    }

    @Test
    fun enablingASegmentThatAlreadyEndedGivesZeroQuota() {
        val disabledPast = segments(30, 20).let { list -> listOf(list[0].copy(isEnabled = false, enabledStateBaseline = false), list[1]) }
        install(disabledPast)
        assertEquals("停用的前段不计入", 20.0, CreditManager.getRemainingMinutes(context), 0.01)

        TimeRuleManager.analyzeAndSaveTimeSegments(context, segments(30, 20))

        assertEquals("前段已结束，启用等同新建：额度 0，不凭空产生滚存", 20.0, CreditManager.getRemainingMinutes(context), 0.01)
    }

    @Test
    fun enablingASegmentInProgressProratesByTheRemainingTimeAndCarries() {
        val cal = Calendar.getInstance()
        val nowMin = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        assumeTrue(nowMin <= 23 * 60 + 20)
        val endMin = nowMin + 30
        fun inProgress(enabled: Boolean) = listOf(
            TimeSegment(
                startHour = 0, startMinute = 0, endHour = endMin / 60, endMinute = endMin % 60,
                limitMins = 30, name = "进行中段", isEnabled = enabled, enabledStateBaseline = enabled
            ),
            TimeSegment(
                startHour = endMin / 60, startMinute = endMin % 60, endHour = 23, endMinute = 59,
                limitMins = 20, name = "后段"
            )
        )
        install(inProgress(enabled = false))

        TimeRuleManager.analyzeAndSaveTimeSegments(context, inProgress(enabled = true))

        // 启用时刻到结束还剩 30 分钟，整段 endMin 分钟：额度 30 × 30 / endMin
        val expected = 30.0 * 30.0 / endMin
        assertEquals(expected, CreditManager.getRemainingMinutes(context), 0.3)
        assertTrue(recalcRecords().single().reason.contains("启用"))
    }

    @Test
    fun usageBeyondTheNewQuotaIsNotClawedBackAndNeverGoesNegative() {
        install(segments(pastLimit = 30, currentLimit = 20))
        CreditManager.getRemainingMinutes(context)
        // 扣费先吃当前段 20，再吃前段 8
        CreditManager.consumeMinutes(context, 28.0)
        assertEquals("前段还剩 22", 22.0, CreditManager.getRemainingMinutes(context), 0.01)

        // 前段已用 8，改严到 5：超出的 3 不追扣，前段剩余取 0
        TimeRuleManager.analyzeAndSaveTimeSegments(context, segments(5, 20))

        val remaining = CreditManager.getRemainingMinutes(context)
        assertTrue("结余不会为负：$remaining", remaining >= 0.0)
        assertEquals("前段剩余取 0，当前段已用完", 0.0, remaining, 0.01)
    }
}
