package com.decikeeper.app.manager

import android.content.Context
import com.decikeeper.app.data.AnalysisResult
import com.decikeeper.app.data.TimeSegment
import java.util.Calendar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 回归：开启「生效时刻同步」时把勿扰时段结束时间 07:30 → 07:15（放宽，次日生效）。
 * 此前新刷新时刻 07:15 对照仍在生效的旧时段（…–07:30）校验，被入口守卫静默拒绝，
 * 界面提示已同步，实际刷新时刻与待生效项的生效时间都仍是 07:30。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class RefreshTimeSyncOnSegmentShortenTest {

    private lateinit var context: Context
    private val rulesPrefs get() = context.getSharedPreferences("decikeeper_rules", Context.MODE_PRIVATE)
    private val pendingPrefs get() = context.getSharedPreferences("decikeeper_rules_pending", Context.MODE_PRIVATE)
    private val baselinePrefs get() = context.getSharedPreferences("decikeeper_rules_baseline", Context.MODE_PRIVATE)
    private val ledgerPrefs get() = context.getSharedPreferences(CreditManager.PREFS_NAME, Context.MODE_PRIVATE)

    private fun night(endH: Int, endM: Int) = TimeSegment(
        startHour = 22, startMinute = 0, endHour = endH, endMinute = endM,
        limitMins = 30, name = "夜间", isEnabled = true
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
        ledgerPrefs.edit().putString("config_refresh_time", "07:30").commit()
        TimeRuleManager.setRefreshTimeSynced(context, true)
        val base = listOf(night(7, 30))
        rulesPrefs.edit().putString("segments_json", TimeRuleManager.segmentsToJson(base)).commit()
        baselinePrefs.edit().putString("segments_json", TimeRuleManager.segmentsToJson(base)).commit()
        TimeRuleManager.invalidateSegmentsCache()
    }

    @Test
    fun shorteningMorningEndSyncsRefreshTimeAndPendingEffectiveTime() {
        val result = TimeRuleManager.analyzeAndSaveTimeSegments(context, listOf(night(7, 15)))
        assertTrue("expected delayed change, got $result", result is AnalysisResult.Mixed)

        assertEquals("刷新时刻必须同步成 07:15", "07:15", TimeRuleManager.getRefreshTime(context))

        val eff = (result as AnalysisResult.Mixed).effectiveTime
        val cal = Calendar.getInstance().apply { timeInMillis = eff }
        assertEquals("待生效项的生效时刻必须是 07:15（小时）", 7, cal.get(Calendar.HOUR_OF_DAY))
        assertEquals("待生效项的生效时刻必须是 07:15（分钟）", 15, cal.get(Calendar.MINUTE))
        assertEquals(eff, pendingPrefs.getLong("effective_timestamp", 0L))
    }
}
