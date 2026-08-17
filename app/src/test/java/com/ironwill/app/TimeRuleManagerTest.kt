package com.ironwill.app

import com.ironwill.app.data.SegmentAdverseAnalysis
import com.ironwill.app.data.TimeSegment
import java.util.Calendar
import org.junit.Assert.*
import org.junit.Test




/**
 * =================================================================================
 * [Context Header]
 * * @file: TimeRuleManagerTest.kt
 * * @role: Test
 * * @description: Unit tests for TimeRuleManager logic, specifically "New Rule Exemption".
 * * @responsibilities:
 * 1. Test AnalyzeSingleSegmentChange logic.
 * 2. Verify creation time impact on adversity check.
 * =================================================================================
 */
class TimeRuleManagerTest {

    @Test
    fun testNewRuleDeletionIsImmediate() {
        val now = System.currentTimeMillis()
        
        // Setup: Rule with a recent timestamp (e.g., 5 seconds past "Cycle Start")
        // NOTE: In strict unit test, we can't easily control TimeRuleManager's idea of "Now".
        // But assuming the test runs, any timestamp >= Today 08:00 (or Yesterday 08:00) is "Recent".
        // We simulate a fresh rule created just now.
        
        val recentSeg = TimeSegment(
            startHour = 10, startMinute = 0, endHour = 11, endMinute = 0, limitMins = 30,
            name = "Recent Rule", 
            lastModified = now
        )
        
        // Action: "Delete" it by disabling it (which is normally Adverse)
        val disabledSeg = recentSeg.copy(
            isEnabled = false, 
            lastModified = now
        )
        
        // Analyze change
        val analysis = SegmentAdverseAnalysis.analyzeSingleSegmentChange(recentSeg, disabledSeg)
        
        // Assertion: Currently (Without Fix) this should be TRUE (Adverse)
        // We assert FALSE (Benign) to prove the fix is needed.
        assertFalse("Newly created rule deletion should be immediate (Not Adverse)", analysis.isAnyAdverse())
    }
}
