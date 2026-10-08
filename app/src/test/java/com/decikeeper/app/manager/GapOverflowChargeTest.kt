package com.decikeeper.app.manager

import android.content.Context
import com.decikeeper.app.data.TimeSegment
import java.util.Calendar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 空档期临时解锁超出空档期、进入下一勿扰段时的整段计费账本：
 * 先扣前序结余，不足部分直接扣下一勿扰段自己的额度（不算借用）；任一维度不足整笔拒绝且不写账本。
 *
 * Robolectric 跟随真实墙钟，因此段 fixture 以「当前时刻」动态构造：
 * 前序段 [00:00, now-30min)，下一段 [now+10min, 23:59)，当前时刻落在二者之间的空档期。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class GapOverflowChargeTest {

    private lateinit var context: Context

    private val rulesPrefs get() =
        context.getSharedPreferences("decikeeper_rules", Context.MODE_PRIVATE)

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        rulesPrefs.edit().clear().commit()
        context.getSharedPreferences("decikeeper_ledger", Context.MODE_PRIVATE).edit().clear().commit()
        TimeRuleManager.invalidateSegmentsCache()
        TimeRuleManager.saveRefreshTime(context, "00:00")
    }

    private fun nowMinutes(): Int {
        val cal = Calendar.getInstance()
        return cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
    }

    /** 前序段限额 10 分钟 / 6 个 half-unit，下一段限额 20 分钟 / 8 个 half-unit。 */
    private fun seedGapFixture(): Boolean {
        val now = nowMinutes()
        // 前序段需足够长以免被判为「实质无限」(limit > 50% 时长)，且当前时刻要留出空档
        if (now < 150 || now > 23 * 60 + 20) return false
        val prev = TimeSegment(
            startHour = 0, startMinute = 0,
            endHour = (now - 30) / 60, endMinute = (now - 30) % 60,
            limitMins = 10, name = "前序段",
            usableCountLimitHalfUnits = 6
        )
        val next = TimeSegment(
            startHour = (now + 10) / 60, startMinute = (now + 10) % 60,
            endHour = 23, endMinute = 59,
            limitMins = 20, name = "下一段",
            usableCountLimitHalfUnits = 8
        )
        val json = TimeRuleManager.segmentsToJson(listOf(prev, next))
        rulesPrefs.edit().putString("segments_json", json).commit()
        TimeRuleManager.invalidateSegmentsCache()
        return TimeRuleManager.getCurrentSegmentIndex(context) == -1
    }

    @Test
    fun overflowTakesSurplusFirstThenNextSegmentQuota() {
        assumeTrue(seedGapFixture())
        assertEquals(10.0, CreditManager.getAllPreviousSegmentsRemainingMinutes(context), 0.001)

        // 700s：前序结余 600s 全部用完，剩 100s 扣下一段；次数 2 个 half-unit 全从结余扣
        assertTrue(CreditManager.consumeGapOverflowCharge(context, 700, 2))

        assertEquals(0.0, CreditManager.getAllPreviousSegmentsRemainingMinutes(context), 0.001)
        assertEquals(4, CreditManager.getAllPreviousSegmentsRemainingUsableCount(context))
    }

    @Test
    fun overflowWithinSurplus_doesNotTouchNextSegment() {
        assumeTrue(seedGapFixture())

        assertTrue(CreditManager.consumeGapOverflowCharge(context, 300, 1))

        assertEquals(5.0, CreditManager.getAllPreviousSegmentsRemainingMinutes(context), 0.001)
        assertEquals(5, CreditManager.getAllPreviousSegmentsRemainingUsableCount(context))
    }

    @Test
    fun insufficientQuota_rejectsWholeChargeWithoutWriting() {
        assumeTrue(seedGapFixture())

        // 结余 600s + 下一段 1200s = 1800s，申请 1900s 必须整笔拒绝
        assertFalse(CreditManager.consumeGapOverflowCharge(context, 1900, 1))

        assertEquals(10.0, CreditManager.getAllPreviousSegmentsRemainingMinutes(context), 0.001)
        assertEquals(6, CreditManager.getAllPreviousSegmentsRemainingUsableCount(context))
    }
}
