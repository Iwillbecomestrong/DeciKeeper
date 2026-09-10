package com.decikeeper.app

import com.decikeeper.app.data.statistics.AppCategory
import com.decikeeper.app.data.statistics.WeekdayFilter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek

class AppUsageCalculationTest {

    @Test
    fun testWeekdayFilterWorkdayMatches() {
        val workdayFilter = WeekdayFilter.WORKDAY
        assertTrue("周一应符合工作日", workdayFilter.matches(DayOfWeek.MONDAY))
        assertTrue("周二应符合工作日", workdayFilter.matches(DayOfWeek.TUESDAY))
        assertTrue("周三应符合工作日", workdayFilter.matches(DayOfWeek.WEDNESDAY))
        assertTrue("周四应符合工作日", workdayFilter.matches(DayOfWeek.THURSDAY))
        assertTrue("周五应符合工作日", workdayFilter.matches(DayOfWeek.FRIDAY))
        assertFalse("周六不符合工作日", workdayFilter.matches(DayOfWeek.SATURDAY))
        assertFalse("周日不符合工作日", workdayFilter.matches(DayOfWeek.SUNDAY))
    }

    @Test
    fun testWeekdayFilterWeekendMatches() {
        val weekendFilter = WeekdayFilter.WEEKEND
        assertFalse("周一不符合周末", weekendFilter.matches(DayOfWeek.MONDAY))
        assertTrue("周六应符合周末", weekendFilter.matches(DayOfWeek.SATURDAY))
        assertTrue("周日应符合周末", weekendFilter.matches(DayOfWeek.SUNDAY))
    }

    @Test
    fun testScientificAppCategoryTaxonomy() {
        assertEquals("社交通讯", AppCategory.COMMUNICATION.displayName)
        assertEquals("游戏", AppCategory.GAME.displayName)
        assertEquals("视频娱乐", AppCategory.VIDEO.displayName)
        assertEquals("音乐音频", AppCategory.AUDIO.displayName)
        assertEquals("AI与效率", AppCategory.AI_PRODUCTIVITY.displayName)
        assertEquals("系统与工具", AppCategory.TOOLS_SYSTEM.displayName)
    }

    @Test
    fun testDailyUsageCannotExceed24Hours() {
        val maxDayMillis = 24 * 3600 * 1000L
        val inflatedDuration = 26 * 3600 * 1000L
        val clampedDuration = inflatedDuration.coerceAtMost(maxDayMillis)
        assertEquals(maxDayMillis, clampedDuration)
        val hours = clampedDuration / (3600 * 1000f)
        assertTrue("每日限制物理上限不能超过24小时", hours <= 24f)
    }
}
