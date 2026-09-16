package com.decikeeper.app.manager

import com.decikeeper.app.manager.TimeRuleManager
import org.junit.Assert.*
import org.junit.Test



class DayFormatTest {
    @Test
    fun testFormatDaysVerbose() {
        // Test 1: All days (Explicit 1..7) -> "每天"
        val allDays = listOf(1, 2, 3, 4, 5, 6, 7)
        var result = TimeRuleManager.formatDaysVerbose(allDays)
        assertEquals("每天", result)
        
        // Test 2: Null -> "每天"
        result = TimeRuleManager.formatDaysVerbose(null)
        assertEquals("每天", result)
        
        // Test 3: Empty List -> "每天"
        result = TimeRuleManager.formatDaysVerbose(emptyList())
        assertEquals("每天", result)
        
        // Test 4: Weekdays (Mon-Fri) -> "周一、二、三、四、五"
        val weekdays = listOf(2, 3, 4, 5, 6)
        result = TimeRuleManager.formatDaysVerbose(weekdays)
        val expectedWeekdays = "周一、二、三、四、五"
        assertEquals(expectedWeekdays, result)
        
        // Test 5: Weekend (Sat, Sun) -> "周六、日"
        val weekend = listOf(7, 1)
        result = TimeRuleManager.formatDaysVerbose(weekend)
        assertEquals("周六、日", result)
    }
}
