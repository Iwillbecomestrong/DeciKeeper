package com.decikeeper.app.manager

import android.content.Context
import java.util.Calendar
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PrivilegeWindowTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("decikeeper_rules", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("decikeeper_ledger", Context.MODE_PRIVATE).edit().clear().commit()
        TimeRuleManager.invalidateSegmentsCache()
        // 业务日开始（生效时间）固定为 06:00
        TimeRuleManager.saveRefreshTime(context, "06:00")
    }

    private fun at(hour: Int, minute: Int, second: Int = 0): Long =
        Calendar.getInstance().apply {
            set(2026, Calendar.OCTOBER, 6, hour, minute, second)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

    private val prefs get() = context.getSharedPreferences("decikeeper_rules", Context.MODE_PRIVATE)

    @Test
    fun unsetWindowDefaultsToSevenThroughTwentyThree() {
        assertEquals("未设置：开始默认 07:00", "07:00", TimeRuleManager.getPrivilegeWindowStart(context))
        assertEquals("未设置：结束默认 23:00", "23:00", TimeRuleManager.getPrivilegeWindowEnd(context))
    }

    @Test
    fun storedWindowIsReadAsIsAndNeverRewritten() {
        // 用户已存的值更新后不能被改：开始早于生效时间只是「读出来」按生效时间算，存的值原样保留
        TimeRuleManager.setPrivilegeWindow(context, "05:00", "22:00")
        assertEquals("06:00", TimeRuleManager.getPrivilegeWindowStart(context))
        assertEquals("22:00", TimeRuleManager.getPrivilegeWindowEnd(context))
        assertEquals("05:00", prefs.getString("privilege_window_start", null))
        assertEquals("22:00", prefs.getString("privilege_window_end", null))

        // 用户存的 07:00 ~ 23:00 保持不变（不会被默认的 24:00 覆盖）
        TimeRuleManager.setPrivilegeWindow(context, "07:00", "23:00")
        assertEquals("07:00", TimeRuleManager.getPrivilegeWindowStart(context))
        assertEquals("23:00", TimeRuleManager.getPrivilegeWindowEnd(context))
    }

    @Test
    fun legacyCrossMidnightWindowIsKeptAsStored() {
        TimeRuleManager.setPrivilegeWindow(context, "22:00", "08:00")
        assertEquals("22:00", TimeRuleManager.getPrivilegeWindowStart(context))
        assertEquals("08:00", TimeRuleManager.getPrivilegeWindowEnd(context))
        assertEquals("22:00", prefs.getString("privilege_window_start", null))
        assertEquals("08:00", prefs.getString("privilege_window_end", null))
    }

    @Test
    fun legacyCrossMidnightWindowEndsAtTwentyFourHundredOfTheDay() {
        TimeRuleManager.setPrivilegeWindow(context, "22:00", "08:00")
        val end = TimeRuleManager.getPrivilegeWindowEndMillis(context, at(23, 0))
        assertEquals(at(0, 0) + 24 * 60 * 60_000L, end)
    }

    @Test
    fun windowEndMillisHandlesTwentyFourHundred() {
        TimeRuleManager.setPrivilegeWindow(context, "07:00", "24:00")
        val end = TimeRuleManager.getPrivilegeWindowEndMillis(context, at(10, 0))
        assertEquals(at(0, 0) + 24 * 60 * 60_000L, end)
    }

    @Test
    fun requestIsTruncatedAtWindowEndAtApplicationTime() {
        TimeRuleManager.setPrivilegeWindow(context, "07:00", "23:00")
        CreditManager.setWeeklyPrivilegeLimit(context, newCountLimit = 3, newDurationLimit = 600)

        assertEquals("窗口内、时长够：按申请", 60, CreditManager.calculateWeeklyPrivilegeEffectiveMinutes(context, 60, at(10, 0)))
        assertEquals("22:30 申请 120 分钟，截断到 23:00", 30, CreditManager.calculateWeeklyPrivilegeEffectiveMinutes(context, 120, at(22, 30)))
        assertEquals("窗口外不能申请", 0, CreditManager.calculateWeeklyPrivilegeEffectiveMinutes(context, 60, at(6, 30)))
        assertEquals("晚于结束不能申请", 0, CreditManager.calculateWeeklyPrivilegeEffectiveMinutes(context, 60, at(23, 30)))
    }

    @Test
    fun windowEndingAtMidnightTruncatesAtMidnight() {
        TimeRuleManager.setPrivilegeWindow(context, "06:00", "24:00")
        CreditManager.setWeeklyPrivilegeLimit(context, newCountLimit = 3, newDurationLimit = 600)

        assertEquals(10, CreditManager.calculateWeeklyPrivilegeEffectiveMinutes(context, 30, at(23, 50)))
    }
}
