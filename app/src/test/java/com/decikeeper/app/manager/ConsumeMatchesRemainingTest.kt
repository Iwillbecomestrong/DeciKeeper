package com.decikeeper.app.manager

import android.content.Context
import com.decikeeper.app.data.TimeSegment
import java.util.Calendar
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 扣费必须与展示结余同口径：休息扣多少，`getRemainingMinutes` 就少多少。
 *
 * 回归 "结余 20m，休息 20m，结余却还剩 3m"：扣费历史段时没有套用展示口径的
 * 过滤（无限额段不结余），把额度从展示里已排除的段里扣掉，真正算作结余的段没动。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ConsumeMatchesRemainingTest {

    private lateinit var context: Context

    private val rulesPrefs get() =
        context.getSharedPreferences("decikeeper_rules", Context.MODE_PRIVATE)

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        rulesPrefs.edit().clear().commit()
        context.getSharedPreferences("decikeeper_ledger", Context.MODE_PRIVATE).edit().clear().commit()
        TimeRuleManager.invalidateSegmentsCache()
        // 业务日从 00:00 起，三个时段都落在同一业务日内，不依赖测试运行的钟点
        TimeRuleManager.saveRefreshTime(context, "00:00")
    }

    private fun segment(
        startHour: Int, startMinute: Int, endHour: Int, endMinute: Int, limitMins: Int, name: String
    ) = TimeSegment(
        startHour = startHour,
        startMinute = startMinute,
        endHour = endHour,
        endMinute = endMinute,
        limitMins = limitMins,
        name = name
    )

    @Test
    fun consumingAllRemainingLeavesNothingEvenWhenAFlushedSegmentPrecedes() {
        val cal = Calendar.getInstance()
        val nowMin = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        // 当前时段 00:04-23:59，避开午夜前后几分钟
        assumeTrue(nowMin in 6..(23 * 60 + 55))

        val segments = listOf(
            // 无限额段（限额 > 时长一半）：展示里不向后结余
            segment(0, 0, 0, 2, limitMins = 2, name = "flushed"),
            // 正常结余段：1 分钟额度，未使用
            segment(0, 2, 0, 4, limitMins = 1, name = "carry"),
            // 当前段
            segment(0, 4, 23, 59, limitMins = 10, name = "current")
        )
        rulesPrefs.edit().putString("segments_json", TimeRuleManager.segmentsToJson(segments)).commit()
        TimeRuleManager.invalidateSegmentsCache()

        val before = CreditManager.getRemainingMinutes(context)
        assertEquals("前序结余 1 + 当前段 10", 11.0, before, 0.01)

        CreditManager.consumeMinutes(context, before)

        assertEquals("扣光展示结余后应当为 0", 0.0, CreditManager.getRemainingMinutes(context), 0.01)
    }
}
