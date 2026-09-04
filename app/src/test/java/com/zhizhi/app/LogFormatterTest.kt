package com.zhizhi.app

import com.zhizhi.app.util.DiagnosticTimestampFormatter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test



/**
 * Contract tests for [DiagnosticTimestampFormatter].
 *
 * V2-R2 §8 Task 3 step 1: all nested epoch millisecond timestamps must use
 * ISO dual-display format (ISO `yyyy-MM-dd HH:mm:ss.SSS` + raw epoch in parens).
 * The old raw-only contract (plain epoch number) fails the readability requirement.
 */
class LogFormatterTest {

    @Test
    fun formatsEpochWithMillisecondsAndRetainsRawValue() {
        // fixed epoch
        val epochMs = 1_784_822_418_513L
        val formatted = DiagnosticTimestampFormatter.msWithRaw(epochMs)
        assertTrue("Must contain ISO date component", formatted.contains('-'))
        assertTrue("Must contain ISO time component", formatted.contains(':'))
        assertTrue("Must contain millisecond component", formatted.contains('.'))
        assertTrue("Must retain raw epoch value in parens", formatted.contains("($epochMs)"))

        // null → NONE
        assertEquals("NONE", DiagnosticTimestampFormatter.msWithRaw(null))

        // zero → NONE
        assertEquals("NONE", DiagnosticTimestampFormatter.msWithRaw(0L))
    }

    @Test
    fun msWithRawContainsEpochInsideParentheses() {
        val epochMs = 42_000L
        val formatted = DiagnosticTimestampFormatter.msWithRaw(epochMs)
        // The raw value must appear in parentheses at the end
        assertTrue("Raw value must be in trailing parens", formatted.endsWith(" ($epochMs)"))
    }

    @Test
    fun oldRawOnlyFormatFailsReadabilityContract() {
        val epochMs = 1_784_822_418_513L
        val oldRaw = epochMs.toString()

        // Old raw-only contract: just a number, no date separators, no spaces, no colons
        assertFalse("Raw epoch must not contain ISO date separator '-'",
            oldRaw.contains('-'))
        assertFalse("Raw epoch must not contain ISO time separator ':'",
            oldRaw.contains(':'))
        assertFalse("Raw epoch must not be human-readable",
            oldRaw.contains(' ') || oldRaw.contains('.'))
    }

    @Test
    fun negativeValuesMapToNone() {
        assertEquals("NONE", DiagnosticTimestampFormatter.msWithRaw(-1L))
        assertEquals("NONE", DiagnosticTimestampFormatter.msWithRaw(-1000L))
    }
}
