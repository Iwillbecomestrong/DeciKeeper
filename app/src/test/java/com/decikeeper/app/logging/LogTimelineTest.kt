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

    private fun records(lines: List<String>): List<String> = lines.filterNot { it.startsWith("#") }

    @Test
    fun allPermanentRecordsComeBeforeAllDiagnosticRecords() {
        val a = listOf(
            "2026-10-10 09:12:40.000 [LOCK_RESULT] result=LOCKED",
            "2026-10-10 09:14:15.000 [LOCK_RESULT] result=LOCKED reason=EXPIRY_RELOCK"
        )
        val b = listOf("2026-10-10 09:12:38.000 fg=com.x src=EVENTS n=3")

        val export = LogTimeline.merge(a, b)

        assertEquals(
            listOf(
                "A 2026-10-10 09:12:40.000 [LOCK_RESULT] result=LOCKED",
                "A 2026-10-10 09:14:15.000 [LOCK_RESULT] result=LOCKED reason=EXPIRY_RELOCK",
                "B 2026-10-10 09:12:38.000 fg=com.x src=EVENTS n=3"
            ),
            records(export.lines)
        )
    }

    @Test
    fun sectionTitlesSeparateTheTwoPartsAndCountRecords() {
        val export = LogTimeline.merge(
            listOf("2026-10-10 09:00:00.000 [A]"),
            listOf("2026-10-10 09:00:01.000 [B1]", "2026-10-10 09:00:02.000 [B2]")
        )

        val titles = export.lines.filter { it.startsWith("#") }
        assertEquals(2, titles.size)
        assertTrue(titles[0].contains("A 永久账本") && titles[0].contains("1 条"))
        assertTrue(titles[1].contains("B 近期诊断") && titles[1].contains("2 条"))
        assertTrue("A 分区标题在 B 分区标题之前", export.lines.indexOf(titles[0]) < export.lines.indexOf(titles[1]))
    }

    @Test
    fun eachSectionIsSortedByTime() {
        val export = LogTimeline.merge(
            listOf("2026-10-10 10:00:00.000 [LATE]", "2026-10-10 08:00:00.000 [EARLY]"),
            listOf("2026-10-10 12:00:00.000 [B-LATE]", "2026-10-10 11:00:00.000 [B-EARLY]")
        )

        assertEquals(
            listOf(
                "A 2026-10-10 08:00:00.000 [EARLY]",
                "A 2026-10-10 10:00:00.000 [LATE]",
                "B 2026-10-10 11:00:00.000 [B-EARLY]",
                "B 2026-10-10 12:00:00.000 [B-LATE]"
            ),
            records(export.lines)
        )
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
            records(export.lines)
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

        assertEquals(listOf("B 2026-10-10 11:00:00.000 [NEW]", "  body-of-new"), records(export.lines))
    }

    @Test
    fun orphanFragmentBeforeFirstHeaderIsDropped() {
        val export = LogTimeline.merge(emptyList(), listOf("half a record", "2026-10-10 09:00:00.000 [OK]"))

        assertEquals(listOf("B 2026-10-10 09:00:00.000 [OK]"), records(export.lines))
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
