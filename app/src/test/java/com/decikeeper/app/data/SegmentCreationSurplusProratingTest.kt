package com.decikeeper.app.data

import android.content.Context
import com.decikeeper.app.data.TimeSegment
import com.decikeeper.app.data.TimeSegmentJson
import com.decikeeper.app.manager.CreditManager
import com.decikeeper.app.manager.TimeRuleManager
import java.util.Calendar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SegmentCreationSurplusProratingTest {

    private fun newContext(): Context = RuntimeEnvironment.getApplication()

    private fun tsAt(year: Int, month1: Int, day: Int, hour: Int, minute: Int): Long {
        val cal = Calendar.getInstance()
        cal.clear()
        cal.set(year, month1 - 1, day, hour, minute, 0)
        return cal.timeInMillis
    }

    @Test
    fun jsonPersistencePreservesLastModifiedTimestamp() {
        val originalTimestamp = 1773000000000L
        val segment = TimeSegment(
            name = "晚上",
            startHour = 17,
            startMinute = 30,
            endHour = 22,
            endMinute = 0,
            limitMins = 35,
            usableCountLimitHalfUnits = 14,
            lastModified = originalTimestamp
        )

        val json = TimeSegmentJson.segmentsToJson(listOf(segment))
        assertTrue("JSON must contain lastModified key", json.contains("\"lastModified\":1773000000000"))

        val restored = TimeSegmentJson.parseSegmentsFromJson(json).single()
        assertEquals(originalTimestamp, restored.lastModified)
    }

    private fun todayCal(): Calendar = Calendar.getInstance()

    @Test
    fun pastSegmentsCreatedLateDoNotGenerateSurplusCarry() {
        val ctx = newContext()
        val cal = todayCal()
        val y = cal.get(Calendar.YEAR)
        val m = cal.get(Calendar.MONTH) + 1
        val d = cal.get(Calendar.DAY_OF_MONTH)

        // 假定当天，刷新时间 08:00
        TimeRuleManager.saveRefreshTime(ctx, "08:00")
        TimeRuleManager.setRefreshTimeSynced(ctx, false)
        val createTime = tsAt(y, m, d, 18, 0) // 18:00 一键设置

        // 上午: 08:15 - 12:00 (休息 30min, 7次)
        val morningSeg = TimeSegment(
            name = "上午",
            startHour = 8, startMinute = 15,
            endHour = 12, endMinute = 0,
            limitMins = 30, usableCountLimitHalfUnits = 14,
            lastModified = createTime
        )
        // 下午: 12:45 - 16:45 (休息 35min, 7次)
        val afternoonSeg = TimeSegment(
            name = "下午",
            startHour = 12, startMinute = 45,
            endHour = 16, endMinute = 45,
            limitMins = 35, usableCountLimitHalfUnits = 14,
            lastModified = createTime
        )
        // 晚上: 17:30 - 22:00 (休息 35min, 7次)
        val eveningSeg = TimeSegment(
            name = "晚上",
            startHour = 17, startMinute = 30,
            endHour = 22, endMinute = 0,
            limitMins = 35, usableCountLimitHalfUnits = 14,
            lastModified = createTime
        )

        TimeRuleManager.saveTimeSegments(ctx, listOf(morningSeg, afternoonSeg, eveningSeg))

        // 验证上午时段：因为 18:00 创建晚于 12:00 结束，无结余资格，有效额度为 0
        val morningProration = CreditManager.getSegmentProration(ctx, morningSeg, createTime)
        assertFalse("上午时段在 18:00 创建不应具有结余资格", morningProration.isEligibleForSurplus)
        assertEquals(0.0, morningProration.effectiveLimitMins, 0.001)
        assertEquals(0, morningProration.effectiveUsableCountHalfUnits)

        // 验证下午时段：因为 18:00 创建晚于 16:45 结束，无结余资格，有效额度为 0
        val afternoonProration = CreditManager.getSegmentProration(ctx, afternoonSeg, createTime)
        assertFalse("下午时段在 18:00 创建不应具有结余资格", afternoonProration.isEligibleForSurplus)
        assertEquals(0.0, afternoonProration.effectiveLimitMins, 0.001)
        assertEquals(0, afternoonProration.effectiveUsableCountHalfUnits)

        // 验证晚上时段查看前序结余：严格为 0，不再凭空产生 65min / 14次
        val prevMinutes = CreditManager.getAllPreviousSegmentsRemainingMinutes(ctx)
        assertEquals("晚于结束时间创建的前序时段时长结余必须为0", 0.0, prevMinutes, 0.001)

        val prevCountHalfUnits = CreditManager.getAllPreviousSegmentsRemainingUsableCountHalfUnits(ctx)
        assertEquals("晚于结束时间创建的前序时段次数结余必须为0", 0, prevCountHalfUnits)
    }

    @Test
    fun midSegmentCreationProratesQuotaByRemainingTime() {
        val ctx = newContext()
        TimeRuleManager.saveRefreshTime(ctx, "08:00")

        val cal = todayCal()
        val y = cal.get(Calendar.YEAR)
        val m = cal.get(Calendar.MONTH) + 1
        val d = cal.get(Calendar.DAY_OF_MONTH)

        // 晚上: 17:30 - 22:00 (总长 270 分钟，额度 35min, 7次)
        // 用户在 18:00 创建 (走过 30 分钟，剩余 240 分钟，比例 240/270 = 8/9)
        val createTime = tsAt(y, m, d, 18, 0)
        val eveningSeg = TimeSegment(
            name = "晚上",
            startHour = 17, startMinute = 30,
            endHour = 22, endMinute = 0,
            limitMins = 35, usableCountLimitHalfUnits = 14,
            lastModified = createTime
        )

        TimeRuleManager.saveTimeSegments(ctx, listOf(eveningSeg))

        val proration = CreditManager.getSegmentProration(ctx, eveningSeg, createTime)
        assertTrue(proration.isEligibleForSurplus)
        // 35 * (240 / 270) = 31.111... -> 31.1
        assertEquals(31.1, proration.effectiveLimitMins, 0.05)
        // 7次 * (240 / 270) = 6.222... -> 6次 (12 halfUnits)
        assertEquals(12, proration.effectiveUsableCountHalfUnits)
    }

    @Test
    fun lateCreationAppliesMinimumQuotaGuard() {
        val ctx = newContext()
        TimeRuleManager.saveRefreshTime(ctx, "08:00")

        val cal = todayCal()
        val y = cal.get(Calendar.YEAR)
        val m = cal.get(Calendar.MONTH) + 1
        val d = cal.get(Calendar.DAY_OF_MONTH)

        // 晚上: 17:30 - 22:00
        // 用户在 21:59 创建 (仅剩 1 分钟)
        val createTime = tsAt(y, m, d, 21, 59)
        val eveningSeg = TimeSegment(
            name = "晚上",
            startHour = 17, startMinute = 30,
            endHour = 22, endMinute = 0,
            limitMins = 35, usableCountLimitHalfUnits = 14,
            lastModified = createTime
        )

        val proration = CreditManager.getSegmentProration(ctx, eveningSeg, createTime)
        assertTrue(proration.isEligibleForSurplus)
        // 宽松保底：至少 1.0 min, 至少 1 次 (2 halfUnits)
        assertEquals(1.0, proration.effectiveLimitMins, 0.001)
        assertEquals(2, proration.effectiveUsableCountHalfUnits)
    }

    @Test
    fun restoredToFullQuotaOnNextBusinessDay() {
        val ctx = newContext()
        TimeRuleManager.saveRefreshTime(ctx, "08:00")

        val calToday = todayCal()
        val nextDayTime = calToday.timeInMillis

        val calYesterday = todayCal()
        calYesterday.add(Calendar.DAY_OF_MONTH, -1)
        val createTime = calYesterday.timeInMillis

        val morningSeg = TimeSegment(
            name = "上午",
            startHour = 8, startMinute = 15,
            endHour = 12, endMinute = 0,
            limitMins = 30, usableCountLimitHalfUnits = 14,
            lastModified = createTime
        )

        val proration = CreditManager.getSegmentProration(ctx, morningSeg, nextDayTime)

        // 次日已是完整经历日：恢复 100% 额度与结余资格
        assertTrue(proration.isEligibleForSurplus)
        assertEquals(30.0, proration.effectiveLimitMins, 0.001)
        assertEquals(14, proration.effectiveUsableCountHalfUnits)
    }
}
