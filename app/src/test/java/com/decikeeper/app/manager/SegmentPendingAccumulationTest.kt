package com.decikeeper.app.manager

import android.content.Context
import com.decikeeper.app.data.TimeSegment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 勿扰时段的延迟生效：每次保存都拿「想要的完整状态」和基准逐项对比。
 * 加严的项立即生效，放宽的项各自挂 pending，保存别的项不能把已有的 pending 冲掉，改回基准的项清掉它的 pending。
 *
 * 编辑界面拿 pending（没有就拿生效）当底稿、整份列表交给 analyzeAndSaveTimeSegments，所以这里也这样喂。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SegmentPendingAccumulationTest {

    private lateinit var context: Context

    private val rulesPrefs get() = context.getSharedPreferences("decikeeper_rules", Context.MODE_PRIVATE)
    private val pendingPrefs get() = context.getSharedPreferences("decikeeper_rules_pending", Context.MODE_PRIVATE)
    private val baselinePrefs get() = context.getSharedPreferences("decikeeper_rules_baseline", Context.MODE_PRIVATE)
    private val ledgerPrefs get() = context.getSharedPreferences(CreditManager.PREFS_NAME, Context.MODE_PRIVATE)

    private val baseline = listOf(
        TimeSegment(9, 0, 12, 0, limitMins = 30, name = "上午", usableCountLimitHalfUnits = 14),
        TimeSegment(13, 0, 17, 0, limitMins = 35, name = "下午", usableCountLimitHalfUnits = 14),
        TimeSegment(18, 0, 22, 0, limitMins = 35, name = "晚上", usableCountLimitHalfUnits = 14)
    )

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        rulesPrefs.edit().clear().commit()
        pendingPrefs.edit().clear().commit()
        baselinePrefs.edit().clear().commit()
        ledgerPrefs.edit().clear().commit()
        CreditManager.invalidateCache()
        TimeRuleManager.invalidateSegmentsCache()
        ledgerPrefs.edit().putBoolean("has_manually_ended_adaptation", true).commit()
        TimeRuleManager.saveRefreshTime(context, "06:00")
        rulesPrefs.edit().putString("segments_json", TimeRuleManager.segmentsToJson(baseline)).commit()
        baselinePrefs.edit().putString("segments_json", TimeRuleManager.segmentsToJson(baseline))
            .putInt("cool_down_gap_seconds", TimeRuleManager.getCoolDownGapSeconds(context))
            .putInt("cool_down_rest_seconds", TimeRuleManager.getCoolDownRestSeconds(context))
            .putInt("borrow_percentage", TimeRuleManager.getBorrowPercentage(context))
            .putInt("notify_advance_minutes", TimeRuleManager.getNotifyAdvanceMinutes(context))
            .putInt("notify_entry_near_minutes", TimeRuleManager.getNotifyEntryNearMinutes(context))
            .putString("config_refresh_time", "06:00")
            .commit()
        TimeRuleManager.invalidateSegmentsCache()
    }

    /** 编辑界面的底稿：有 pending 用 pending，否则用生效。 */
    private fun editorDraft(): List<TimeSegment> =
        TimeRuleManager.getPendingTimeSegments(context) ?: TimeRuleManager.getTimeSegments(context)

    private fun active(name: String): TimeSegment {
        TimeRuleManager.invalidateSegmentsCache()
        return TimeRuleManager.getTimeSegments(context).first { it.name == name }
    }

    private fun pending(name: String): TimeSegment? =
        TimeRuleManager.getPendingTimeSegments(context)?.firstOrNull { it.name == name }

    private fun save(edit: (List<TimeSegment>) -> List<TimeSegment>) {
        TimeRuleManager.analyzeAndSaveTimeSegments(context, edit(editorDraft()))
        TimeRuleManager.invalidateSegmentsCache()
    }

    @Test
    fun tightenGoesLiveAndLoosenGoesPendingInTheSameSave() {
        save { segs ->
            segs.map {
                val tightened = it.copy(usableCountLimitHalfUnits = 6)          // 次数 14 → 6：加严
                if (it.name == "晚上") tightened.copy(endHour = 21, endMinute = 30) else tightened // 晚上缩到 21:30：放宽
            }
        }

        assertEquals("次数加严应立即生效", 6, active("上午").usableCountLimitHalfUnits)
        assertEquals(6, active("晚上").usableCountLimitHalfUnits)
        assertEquals("放宽的结束时间不能立即生效", 22, active("晚上").endHour)
        assertEquals("放宽挂 pending", 21, pending("晚上")!!.endHour)
        assertEquals(30, pending("晚上")!!.endMinute)
    }

    @Test
    fun anotherLoosenKeepsTheEarlierPending() {
        save { segs -> segs.map { if (it.name == "晚上") it.copy(endHour = 21, endMinute = 30) else it } } // 第一项放宽
        assertNotNull(pending("晚上"))

        save { segs -> segs.map { if (it.name == "上午") it.copy(startHour = 9, startMinute = 30) else it } } // 第二项放宽

        assertEquals("先前的 pending 不能丢", 21, pending("晚上")!!.endHour)
        assertEquals(30, pending("上午")!!.startMinute)
        assertEquals("放宽都不立即生效", 0, active("上午").startMinute)
        assertEquals(22, active("晚上").endHour)
    }

    @Test
    fun aTightenAfterALoosenKeepsThePendingAndGoesLive() {
        save { segs -> segs.map { if (it.name == "晚上") it.copy(endHour = 21, endMinute = 30) else it } }

        save { segs -> segs.map { if (it.name == "下午") it.copy(usableCountLimitHalfUnits = 6) else it } }

        assertEquals("加严立即生效", 6, active("下午").usableCountLimitHalfUnits)
        assertEquals("先前放宽的 pending 仍在", 21, pending("晚上")!!.endHour)
        assertEquals(22, active("晚上").endHour)
    }

    @Test
    fun restoringOneItemToBaselineDropsOnlyThatPending() {
        save { segs ->
            segs.map {
                when (it.name) {
                    "晚上" -> it.copy(endHour = 21, endMinute = 30)
                    "上午" -> it.copy(startHour = 9, startMinute = 30)
                    else -> it
                }
            }
        }

        save { segs -> segs.map { if (it.name == "晚上") it.copy(endHour = 22, endMinute = 0) else it } } // 晚上改回基准

        assertEquals("改回基准的项清掉 pending", 22, (pending("晚上") ?: active("晚上")).endHour)
        assertEquals("别的 pending 保留", 30, pending("上午")!!.startMinute)
    }

    @Test
    fun savingAnUnchangedListNeverCreatesPending() {
        save { it }

        assertNull(TimeRuleManager.getPendingTimeSegments(context))
    }

    private fun saveParams(
        gap: Int = TimeRuleManager.getCoolDownGapSeconds(context),
        rest: Int = TimeRuleManager.getCoolDownRestSeconds(context)
    ) = TimeRuleManager.analyzeAndSaveGlobalParams(
        context,
        newRest = rest, newGap = gap,
        newLockDuration = TimeRuleManager.getStrictPenaltyDurationSeconds(context),
        newBorrow = TimeRuleManager.getBorrowPercentage(context),
        newNotifyFar = TimeRuleManager.getNotifyAdvanceMinutes(context),
        newNotifyNear = TimeRuleManager.getNotifyEntryNearMinutes(context),
        newWeeklyCount = CreditManager.getWeeklyPrivilegeLimit(context),
        newWeeklyDuration = CreditManager.getWeeklyPrivilegeDurationLimit(context),
        newRefreshTime = TimeRuleManager.getRefreshTime(context)
    )

    @Test
    fun loosenCountsGoPendingAndTheTightActiveCountStays() {
        // 次数 14 → 20 是放宽：今天仍按 14，20 进 pending
        save { segs -> segs.map { if (it.name == "上午") it.copy(usableCountLimitHalfUnits = 20) else it } }

        assertEquals("放宽的次数不能立即生效", 14, active("上午").usableCountLimitHalfUnits)
        assertEquals(20, pending("上午")!!.usableCountLimitHalfUnits)
    }

    @Test
    fun changingARuleParameterKeepsTheSegmentPending() {
        save { segs -> segs.map { if (it.name == "晚上") it.copy(endHour = 21, endMinute = 30) else it } }
        assertNotNull(pending("晚上"))

        saveParams(gap = TimeRuleManager.getCoolDownGapSeconds(context) + 30) // 空档冷却加长：加严（立即）

        assertNotNull("改规则参数不能把时段的 pending 冲掉", pending("晚上"))
        assertEquals(21, pending("晚上")!!.endHour)
        assertEquals(22, active("晚上").endHour)
    }

    @Test
    fun loosenAParameterAfterASegmentLoosenKeepsBothPending() {
        save { segs -> segs.map { if (it.name == "晚上") it.copy(endHour = 21, endMinute = 30) else it } }
        val gapBefore = TimeRuleManager.getCoolDownGapSeconds(context)

        saveParams(gap = (gapBefore - 30).coerceAtLeast(0)) // 空档冷却缩短：放宽（pending）

        assertEquals("参数放宽不立即生效", gapBefore, TimeRuleManager.getCoolDownGapSeconds(context))
        assertNotNull("时段 pending 仍在", pending("晚上"))
        assertEquals(21, pending("晚上")!!.endHour)
    }
}
