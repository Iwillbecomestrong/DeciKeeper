package com.decikeeper.app.manager

import android.content.Context
import com.decikeeper.app.data.PrivilegeWindow
import java.util.Calendar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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

    @Test
    fun newInputMustStayInsideTheBusinessDay() {
        assertTrue(PrivilegeWindow.isValid("07:00", "23:00", "06:00"))
        assertTrue(PrivilegeWindow.isValid("06:00", "24:00", "06:00"))
        assertFalse("开始早于业务日开始", PrivilegeWindow.isValid("05:59", "23:00", "06:00"))
        assertFalse("相等没有可用时间", PrivilegeWindow.isValid("08:00", "08:00", "06:00"))
        assertFalse("跨午夜不允许", PrivilegeWindow.isValid("22:00", "08:00", "06:00"))
        assertFalse("24:30 不存在", PrivilegeWindow.isValid("07:00", "24:30", "06:00"))
        assertFalse("格式错误", PrivilegeWindow.isValid("7", "23:00", "06:00"))
        assertFalse(PrivilegeWindow.isValid(null, "23:00", "06:00"))
    }

    @Test
    fun defaultStartFollowsBusinessDayStart() {
        assertEquals("07:00" to "23:00", PrivilegeWindow.defaultFor("06:00"))
        assertEquals("08:00" to "23:00", PrivilegeWindow.defaultFor("08:00"))
    }

    @Test
    fun storedStartEarlierThanBusinessDayIsNarrowedNotDropped() {
        // 旧用户存的是 05:00 ~ 22:00，业务日 06:00 开始：开始收窄，结束保留
        assertEquals("06:00" to "22:00", PrivilegeWindow.resolve("05:00", "22:00", "06:00"))
    }

    @Test
    fun stillInvalidStoredWindowFallsBackToDefault() {
        // 旧版跨午夜范围
        assertEquals("07:00" to "23:00", PrivilegeWindow.resolve("22:00", "08:00", "06:00"))
        // 结束早于业务日开始，收窄后开始晚于结束
        assertEquals("07:00" to "23:00", PrivilegeWindow.resolve("01:00", "05:00", "06:00"))
    }

    @Test
    fun gettersUseResolvedWindow() {
        assertEquals("07:00", TimeRuleManager.getPrivilegeWindowStart(context))
        assertEquals("23:00", TimeRuleManager.getPrivilegeWindowEnd(context))

        TimeRuleManager.setPrivilegeWindow(context, "05:00", "22:00")
        assertEquals("06:00", TimeRuleManager.getPrivilegeWindowStart(context))
        assertEquals("22:00", TimeRuleManager.getPrivilegeWindowEnd(context))

        TimeRuleManager.setPrivilegeWindow(context, "22:00", "08:00")
        assertEquals("07:00", TimeRuleManager.getPrivilegeWindowStart(context))
        assertEquals("23:00", TimeRuleManager.getPrivilegeWindowEnd(context))
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
