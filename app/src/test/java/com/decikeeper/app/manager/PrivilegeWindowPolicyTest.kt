package com.decikeeper.app.manager

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivilegeWindowPolicyTest {

    @Test
    fun parsesMinutesAndAllowsTwentyFourOnlyForEnd() {
        assertEquals(480, PrivilegeWindowPolicy.parseMinutes("08:00", allowMidnightEnd = false))
        assertNull(PrivilegeWindowPolicy.parseMinutes("24:00", allowMidnightEnd = false))
        assertEquals(1440, PrivilegeWindowPolicy.parseMinutes("24:00", allowMidnightEnd = true))
        assertNull(PrivilegeWindowPolicy.parseMinutes("24:01", allowMidnightEnd = true))
        assertNull(PrivilegeWindowPolicy.parseMinutes("8", allowMidnightEnd = true))
        assertNull(PrivilegeWindowPolicy.parseMinutes("08:60", allowMidnightEnd = true))
    }

    @Test
    fun validWindowPasses() {
        assertNull(PrivilegeWindowPolicy.validate("08:00", "24:00", "08:00"))
        assertNull(PrivilegeWindowPolicy.validate("09:30", "23:00", "08:00"))
    }

    @Test
    fun startEarlierThanRefreshIsRejectedWithFullReason() {
        val msg = PrivilegeWindowPolicy.validate("07:59", "24:00", "08:00")!!
        assertTrue(msg.contains("不能跨天"))
        assertTrue(msg.contains("08:00"))
        assertTrue(msg.contains("24:00"))
    }

    @Test
    fun endMustBeAfterStartAndNotCrossDay() {
        assertNotNull(PrivilegeWindowPolicy.validate("10:00", "10:00", "08:00"))
        assertNotNull(PrivilegeWindowPolicy.validate("22:00", "06:00", "08:00"))
        assertNotNull(PrivilegeWindowPolicy.validate("10:00", "24:30", "08:00"))
    }

    @Test
    fun malformedInputIsRejected() {
        assertNotNull(PrivilegeWindowPolicy.validate("abc", "24:00", "08:00"))
        assertNotNull(PrivilegeWindowPolicy.validate("08:00", "25:00", "08:00"))
    }

    @Test
    fun effectiveStartIsClampedToRefresh() {
        assertEquals("08:00", PrivilegeWindowPolicy.effectiveStart(null, "08:00"))
        assertEquals("08:00", PrivilegeWindowPolicy.effectiveStart("07:00", "08:00"))
        assertEquals("08:00", PrivilegeWindowPolicy.effectiveStart("08:00", "08:00"))
        assertEquals("09:30", PrivilegeWindowPolicy.effectiveStart("09:30", "08:00"))
    }

    @Test
    fun followsRefreshWhenUnsetOrEqual() {
        assertTrue(PrivilegeWindowPolicy.isFollowingRefresh(null, "08:00"))
        assertTrue(PrivilegeWindowPolicy.isFollowingRefresh("08:00", "08:00"))
        assertFalse(PrivilegeWindowPolicy.isFollowingRefresh("09:00", "08:00"))
    }

    @Test
    fun conflictWhenFixedStartEarlierThanNewRefresh() {
        assertTrue(PrivilegeWindowPolicy.conflictsWithRefresh("06:00", "08:00"))
        assertFalse(PrivilegeWindowPolicy.conflictsWithRefresh("08:00", "08:00"))
        assertFalse(PrivilegeWindowPolicy.conflictsWithRefresh("09:00", "08:00"))
        assertFalse(PrivilegeWindowPolicy.conflictsWithRefresh(null, "08:00"))
    }
}
