package com.decikeeper.app.manager

import com.decikeeper.app.manager.DevGuardExemptionWindow.Change
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DevGuardExemptionWindowTest {

    private val evening = DevGuardExemptionWindow(20 * 60 + 30, 21 * 60 + 30)

    @Test
    fun containsIncludesStartAndExcludesEnd() {
        assertFalse(evening.contains(20 * 60 + 29))
        assertTrue(evening.contains(20 * 60 + 30))
        assertTrue(evening.contains(21 * 60 + 29))
        assertFalse(evening.contains(21 * 60 + 30))
    }

    @Test
    fun validWindowNeedsStartBeforeEndWithinOneDay() {
        assertTrue(evening.isValid())
        assertFalse(DevGuardExemptionWindow(21 * 60, 21 * 60).isValid())
        assertFalse(DevGuardExemptionWindow(22 * 60, 6 * 60).isValid())
        assertFalse(DevGuardExemptionWindow(-1, 60).isValid())
        assertFalse(DevGuardExemptionWindow(0, 24 * 60 + 1).isValid())
        assertTrue(DevGuardExemptionWindow(0, 24 * 60).isValid())
    }

    @Test
    fun windowInsideOldWindowIsStricter() {
        assertEquals(Change.STRICTER, DevGuardExemptionWindow.compare(evening, DevGuardExemptionWindow(20 * 60 + 40, 21 * 60 + 20)))
        assertEquals(Change.STRICTER, DevGuardExemptionWindow.compare(evening, DevGuardExemptionWindow(20 * 60 + 30, 21 * 60)))
        assertEquals(Change.STRICTER, DevGuardExemptionWindow.compare(evening, DevGuardExemptionWindow(21 * 60, 21 * 60 + 30)))
    }

    @Test
    fun longerWindowIsLooser() {
        assertEquals(Change.LOOSER, DevGuardExemptionWindow.compare(evening, DevGuardExemptionWindow(20 * 60, 21 * 60 + 30)))
        assertEquals(Change.LOOSER, DevGuardExemptionWindow.compare(evening, DevGuardExemptionWindow(20 * 60 + 30, 22 * 60)))
        assertEquals(Change.LOOSER, DevGuardExemptionWindow.compare(evening, DevGuardExemptionWindow(0, 24 * 60)))
    }

    @Test
    fun shiftedWindowIsLooserBecauseItCoversNewTime() {
        assertEquals(Change.LOOSER, DevGuardExemptionWindow.compare(evening, DevGuardExemptionWindow(21 * 60, 22 * 60)))
        assertEquals(Change.LOOSER, DevGuardExemptionWindow.compare(evening, DevGuardExemptionWindow(19 * 60, 20 * 60 + 40)))
    }

    @Test
    fun identicalWindowIsSame() {
        assertEquals(Change.SAME, DevGuardExemptionWindow.compare(evening, DevGuardExemptionWindow(evening.startMinute, evening.endMinute)))
    }

    @Test
    fun checkChangeDirections() {
        assertEquals(Change.SAME, DevGuardExemptionWindow.compareFlag(false, false))
        assertEquals(Change.SAME, DevGuardExemptionWindow.compareFlag(true, true))
        assertEquals(Change.STRICTER, DevGuardExemptionWindow.compareFlag(true, false))
        assertEquals(Change.LOOSER, DevGuardExemptionWindow.compareFlag(false, true))
    }

    @Test
    fun serializeRoundTrip() {
        assertEquals("1230-1290", evening.serialize())
        assertEquals(evening, DevGuardExemptionWindow.parse("1230-1290"))
        assertNull(DevGuardExemptionWindow.parse(null))
        assertNull(DevGuardExemptionWindow.parse("abc"))
        assertNull(DevGuardExemptionWindow.parse("1290-1230"))
    }

    @Test
    fun formatShowsHourAndMinute() {
        assertEquals("20:30–21:30", evening.format())
        assertEquals("00:00–24:00", DevGuardExemptionWindow(0, 24 * 60).format())
    }
}
