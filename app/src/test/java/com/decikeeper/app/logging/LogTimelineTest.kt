package com.decikeeper.app.logging

import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LogTimelineTest {

    private fun ms(text: String): Long =
        LocalDateTime.parse(text.replace(' ', 'T')).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    @Test
    fun mergesByTimeWithTierMarkers() {
        val a = listOf(
            "2026-10-10 09:12:40.000 [LOCK_RESULT] result=LOCKED",
            "2026-10-10 09:14:15.000 [LOCK_RESULT] result=LOCKED reason=EXPIRY_RELOCK"
        )
        val b = listOf("2026-10-10 09:12:38.000 fg=com.x src=EVENTS n=3")

        val export = LogTimeline.merge(a, b)

        assertEquals(
            listOf(
                "B 2026-10-10 09:12:38.000 fg=com.x src=EVENTS n=3",
                "A 2026-10-10 09:12:40.000 [LOCK_RESULT] result=LOCKED",
                "A 2026-10-10 09:14:15.000 [LOCK_RESULT] result=LOCKED reason=EXPIRY_RELOCK"
            ),
            export.lines
        )
    }

    @Test
    fun equalTimestampPutsAFirst() {
        val export = LogTimeline.merge(
            listOf("2026-10-10 09:00:00.000 [A]"),
            listOf("2026-10-10 09:00:00.000 [B]")
        )

        assertEquals(listOf("A 2026-10-10 09:00:00.000 [A]", "B 2026-10-10 09:00:00.000 [B]"), export.lines)
    }

    @Test
    fun continuationLinesStayAttachedAndAreIndented() {
        val b = listOf(
            "2026-10-10 09:00:00.000 [FG_DECISION] detail=FULL",
            "[1 结果] foreground=com.x action=ALLOW",
            "[2 状态] allowance=UNKNOWN"
        )

        val export = LogTimeline.merge(emptyList(), b)

        assertEquals(
            listOf(
                "B 2026-10-10 09:00:00.000 [FG_DECISION] detail=FULL",
                "  [1 结果] foreground=com.x action=ALLOW",
                "  [2 状态] allowance=UNKNOWN"
            ),
            export.lines
        )
        assertEquals("只有记录首行以 B 开头", 1, export.lines.count { it.startsWith("B") })
    }

    @Test
    fun rangeFilterKeepsOrDropsWholeBlocks() {
        val b = listOf(
            "2026-10-10 09:00:00.000 [OLD]",
            "  body-of-old",
            "2026-10-10 11:00:00.000 [NEW]",
            "body-of-new"
        )

        val export = LogTimeline.merge(
            emptyList(), b,
            startMs = ms("2026-10-10 10:00:00"), endMs = ms("2026-10-10 12:00:00")
        )

        assertEquals(listOf("B 2026-10-10 11:00:00.000 [NEW]", "  body-of-new"), export.lines)
    }

    @Test
    fun orphanFragmentBeforeFirstHeaderIsDropped() {
        val export = LogTimeline.merge(emptyList(), listOf("half a record", "2026-10-10 09:00:00.000 [OK]"))

        assertEquals(listOf("B 2026-10-10 09:00:00.000 [OK]"), export.lines)
    }

    @Test
    fun headerReportsFullRangeOfEachTier() {
        val export = LogTimeline.merge(
            listOf("2026-10-07 08:00:00.000 [A1]", "2026-10-10 12:00:00.000 [A2]"),
            listOf("2026-10-10 10:00:00.000 [B1]"),
            startMs = ms("2026-10-10 11:00:00")
        )

        val header = LogTimeline.headerLines(export)
        assertTrue(header[0].contains("A=永久账本") && header[0].contains("B=近期诊断"))
        assertTrue(header[1].contains("2026-10-07 08:00:00.000 ~ 2026-10-10 12:00:00.000"))
        assertTrue(header[1].contains("2 条"))
        assertTrue(header[2].contains("2026-10-10 10:00:00.000"))
    }

    @Test
    fun emptyTierReportsNoRecords() {
        val export = LogTimeline.merge(emptyList(), emptyList())

        assertNull(export.aRange)
        assertTrue(LogTimeline.headerLines(export)[1].contains("无记录"))
    }
}
