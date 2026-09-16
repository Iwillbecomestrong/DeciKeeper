package com.decikeeper.app.manager

import com.decikeeper.app.manager.TimeRulesAccessGate
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TimeRulesAccessGateTest {

    @Test
    fun timeRulesEntryRequiresUsageAccess() {
        assertFalse(TimeRulesAccessGate.shouldEnter(false))
        assertTrue(TimeRulesAccessGate.shouldEnter(true))
    }
}
