package com.decikeeper.app.manager

import android.content.Context
import com.decikeeper.app.data.TimeSegment
import java.util.Calendar
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** 特权统计整段计入（勿扰时段内与空档期内的分钟都进统计），按业务日归属。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PrivilegeUsageRecordingTest {

    private lateinit var context: Context

    private val ledgerPrefs get() =
        context.getSharedPreferences("decikeeper_ledger", Context.MODE_PRIVATE)
    private val rulesPrefs get() =
        context.getSharedPreferences("decikeeper_rules", Context.MODE_PRIVATE)

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        rulesPrefs.edit().clear().commit()
        ledgerPrefs.edit().clear().commit()
        TimeRuleManager.invalidateSegmentsCache()
        // 业务日从 08:00 开始
        TimeRuleManager.saveRefreshTime(context, "08:00")
    }

    private fun at(day: Int, hour: Int, minute: Int): Long =
        Calendar.getInstance().apply {
            set(2026, Calendar.OCTOBER, day, hour, minute, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

    private fun privilegeMinutes(dateStr: String): Int =
        ledgerPrefs.all.filterKeys { it.startsWith("privilege_usage_${dateStr}_") }
            .values.sumOf { (it as? Int) ?: 0 }

    @Test
    fun wholeSessionIsCountedIncludingGapMinutes() {
        val dnd = TimeSegment(startHour = 10, startMinute = 0, endHour = 11, endMinute = 0, limitMins = 30, name = "dnd")
        rulesPrefs.edit().putString("segments_json", TimeRuleManager.segmentsToJson(listOf(dnd))).commit()
        TimeRuleManager.invalidateSegmentsCache()

        // 09:30 ~ 12:00：空档 30 + 勿扰 60 + 空档 60 = 150 分钟
        CreditManager.recordPrivilegeRestUsage(context, at(6, 9, 30), at(6, 12, 0))

        assertEquals("整段都计入", 150, privilegeMinutes("20261006"))
        assertEquals("勿扰时段内的分钟进各自 key", 60, ledgerPrefs.getInt("privilege_usage_20261006_dnd", 0))
        assertEquals(30, ledgerPrefs.getInt("privilege_usage_20261006_seg_9_gap", 0))
        assertEquals(60, ledgerPrefs.getInt("privilege_usage_20261006_seg_11_gap", 0))
    }

    @Test
    fun minutesBeforeBusinessDayStartBelongToThePreviousBusinessDay() {
        // 07:30 ~ 08:30：业务日从 08:00 开始，前 30 分钟归前一业务日（旧版没有“特权不早于业务日开始”的约束时会出现）
        CreditManager.recordPrivilegeRestUsage(context, at(7, 7, 30), at(7, 8, 30))

        assertEquals(30, privilegeMinutes("20261006"))
        assertEquals(30, privilegeMinutes("20261007"))
    }

    @Test
    fun sessionInsideTheBusinessDayStaysOnOneDay() {
        CreditManager.recordPrivilegeRestUsage(context, at(7, 10, 16), at(7, 14, 16))

        assertEquals(240, privilegeMinutes("20261007"))
        assertEquals(0, privilegeMinutes("20261006"))
    }
}
