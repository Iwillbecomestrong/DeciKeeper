package com.zhizhi.app.util

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Thread-safe `java.time`-based formatter for diagnostic timestamps.
 *
 * All nested epoch-millisecond values in diagnostic log lines use ISO dual-display
 * format: `yyyy-MM-dd HH:mm:ss.SSS (raw)` so operators can both read the wall-clock
 * time at a glance and grep the raw epoch for programmatic correlation.
 *
 * - `ms()`: returns ISO string or `"NONE"` for null/<=0
 * - `msWithRaw()`: returns ISO string + raw epoch in parentheses, or `"NONE"` for null/<=0
 */
object DiagnosticTimestampFormatter {

    private val ISO: DateTimeFormatter = DateTimeFormatter
        .ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
        .withZone(ZoneId.systemDefault())

    /**
     * Returns ISO-formatted string for the given epoch-millis value, or `"NONE"` if
     * [value] is null or <= 0L.
     */
    fun ms(value: Long?): String =
        if (value == null || value <= 0L) "NONE"
        else ISO.format(Instant.ofEpochMilli(value))

    /**
     * Returns ISO dual-display: `"yyyy-MM-dd HH:mm:ss.SSS (raw)"` or `"NONE"` if
     * [value] is null or <= 0L.
     */
    fun msWithRaw(value: Long?): String =
        if (value == null || value <= 0L) "NONE"
        else "${ISO.format(Instant.ofEpochMilli(value))} ($value)"
}
