package com.decikeeper.app.manager

import com.decikeeper.app.ui.formatTemporaryAccessSecondsAsMinutes
import com.decikeeper.app.ui.isTemporaryAccessMinuteInputValid
import com.decikeeper.app.ui.temporaryAccessMinutesToSeconds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test





class TemporaryAccessMinuteInputTest {

    @Test
    fun minuteInputAcceptsOneDecimalWithinHardLimit() {
        assertTrue(isTemporaryAccessMinuteInputValid("0.1", 3.0))
        assertTrue(isTemporaryAccessMinuteInputValid("1.5", 3.0))
        assertTrue(isTemporaryAccessMinuteInputValid("3.0", 3.0))
        assertTrue(isTemporaryAccessMinuteInputValid("3", 3.0))
    }

    @Test
    fun minuteInputRejectsTooPreciseOrAboveLimit() {
        assertFalse(isTemporaryAccessMinuteInputValid(null, 3.0))
        assertFalse(isTemporaryAccessMinuteInputValid("", 3.0))
        assertFalse(isTemporaryAccessMinuteInputValid("0.0", 3.0))
        assertFalse(isTemporaryAccessMinuteInputValid("0.09", 3.0))
        assertFalse(isTemporaryAccessMinuteInputValid("1.25", 3.0))
        assertFalse(isTemporaryAccessMinuteInputValid("3.1", 3.0))
    }

    @Test
    fun minuteInputConvertsToRoundedSeconds() {
        assertEquals(6, temporaryAccessMinutesToSeconds(0.1))
        assertEquals(90, temporaryAccessMinutesToSeconds(1.5))
        assertEquals(180, temporaryAccessMinutesToSeconds(3.0))
    }

    @Test
    fun secondsAreDisplayedAsMinutesWithOneDecimalWhenNeeded() {
        assertEquals("0.5", formatTemporaryAccessSecondsAsMinutes(30))
        assertEquals("1.5", formatTemporaryAccessSecondsAsMinutes(90))
        assertEquals("3", formatTemporaryAccessSecondsAsMinutes(180))
    }
}
