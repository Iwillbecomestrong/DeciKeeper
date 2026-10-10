package com.decikeeper.app.manager

import android.content.Context
import com.decikeeper.app.data.AnalysisResult
import com.decikeeper.app.data.TimeSegment
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** 次数上限从「手填 3 次」改成留空（自动算出同样的 3 次）：有效值没变，不能产生待生效。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class BlankCountLimitSaveTest {

    private lateinit var context: Context
    private val rulesPrefs get() = context.getSharedPreferences("decikeeper_rules", Context.MODE_PRIVATE)
    private val pendingPrefs get() = context.getSharedPreferences("decikeeper_rules_pending", Context.MODE_PRIVATE)
    private val baselinePrefs get() = context.getSharedPreferences("decikeeper_rules_baseline", Context.MODE_PRIVATE)
    private val ledgerPrefs get() = context.getSharedPreferences(CreditManager.PREFS_NAME, Context.MODE_PRIVATE)

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
    }

    private fun seg(countHalfUnits: Int, listApps: List<String>? = null) = TimeSegment(
        startHour = 9, startMinute = 0, endHour = 12, endMinute = 0,
        limitMins = 30, name = "上午", usableCountLimitHalfUnits = countHalfUnits, listApps = listApps
    )

    private fun seed(s: TimeSegment) {
        val json = TimeRuleManager.segmentsToJson(listOf(s))
        rulesPrefs.edit().putString("segments_json", json).commit()
        baselinePrefs.edit().putString("segments_json", json).commit()
        TimeRuleManager.invalidateSegmentsCache()
    }

    @Test
    fun blankingAnExplicitCountThatEqualsTheAutoValueLeavesNothingPending() {
        seed(seg(6)) // 手填 3 次（30 分钟自动 = 3 次）
        val result = TimeRuleManager.analyzeAndSaveTimeSegments(context, listOf(seg(-1)))

        assertFalse("有效次数没变不该进待生效: $result", result is AnalysisResult.Mixed)
        assertFalse(TimeRuleManager.hasPendingRules(context))
        assertNull(pendingPrefs.getString("segments_json", null))
    }
}
