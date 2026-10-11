package com.decikeeper.app.logging

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RepeatCollapserTest {

    @get:Rule
    val folder = TemporaryFolder()

    private fun decision(time: String, seq: Int, pkg: String = "com.tencent.mm", status: String = "NO_DATA") =
        "2026-10-10 $time [FG_DECISION] eventId=aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee-$seq " +
            "processInstanceId=aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee seq=$seq detail=FULL " +
            "reportId=FG-20261010-${time.replace(":", "").take(6)}-00$seq traceId=LOCK-20261010-${time.replace(":", "").take(6)}-000$seq\n" +
            "  [1 结果] foreground=$pkg action=ALLOW reason=ON_BLACKLIST_HIT_NOT_STRICT\n" +
            "  [3 UsageEvents] status=$status prev={2026-10-10 $time (1791624277770) MOVE_TO_BACKGROUND x} dwellMs=${seq * 1000}\n"

    @Test
    fun firstRecordIsWrittenAndIdenticalFollowersAreCollapsed() {
        val collapser = RepeatCollapser()

        val first = collapser.accept(decision("17:24:48.104", 1))
        val second = collapser.accept(decision("17:24:50.900", 2))
        val third = collapser.accept(decision("17:24:53.300", 3))

        assertTrue(first.startsWith("2026-10-10 17:24:48.104 [FG_DECISION]"))
        assertEquals("", second)
        assertEquals("", third)
    }

    @Test
    fun changeInSubstanceStartsNewRecordAndFlushesSummary() {
        val collapser = RepeatCollapser()
        collapser.accept(decision("17:24:48.104", 1))
        collapser.accept(decision("17:24:50.900", 2))
        collapser.accept(decision("17:24:53.300", 3))

        val next = collapser.accept(decision("17:25:00.000", 4, status = "AVAILABLE"))

        val lines = next.lines().filter { it.isNotEmpty() }
        assertTrue(lines.first().contains("[REPEATED] of=[FG_DECISION] n=2 first=2026-10-10 17:24:50.900"))
        assertTrue(lines.first().startsWith("2026-10-10 17:24:53.300"))
        assertTrue("随后是新记录本身", lines[1].startsWith("2026-10-10 17:25:00.000 [FG_DECISION]"))
    }

    @Test
    fun differentPackageIsNeverTreatedAsRepeat() {
        val collapser = RepeatCollapser()
        collapser.accept(decision("17:24:48.104", 1, pkg = "com.tencent.mm"))

        val other = collapser.accept(decision("17:24:50.000", 2, pkg = "com.tencent.mobileqq"))

        assertTrue(other.contains("com.tencent.mobileqq"))
    }

    @Test
    fun drainSummaryWritesPendingCountOnceAndKeepsCollapsingAfterwards() {
        val collapser = RepeatCollapser()
        collapser.accept(decision("17:24:48.104", 1))
        collapser.accept(decision("17:24:50.000", 2))

        val summary = collapser.drainSummary()
        assertTrue(summary.contains("[REPEATED]") && summary.contains("n=1"))
        assertEquals("已取出的汇总不重复输出", "", collapser.drainSummary())
        assertEquals("同一状态继续折叠", "", collapser.accept(decision("17:24:55.000", 3)))
    }

    @Test
    fun noRepeatsMeansNoSummary() {
        val collapser = RepeatCollapser()
        collapser.accept(decision("17:24:48.104", 1))

        assertEquals("", collapser.drainSummary())
    }

    @Test
    fun legacyLinesDifferingOnlyByTimestampAndTraceIdAreCollapsed() {
        val collapser = RepeatCollapser()
        val a = "2026-10-10 17:24:48.104 MonitorService: [EVENT] type=SUSPECT traceId=LOCK-20261010-172448-0001 pkg=com.tencent.mm issue=SHOULD_BLOCK_FALSE durationMs=0\n"
        val b = "2026-10-10 17:24:50.104 MonitorService: [EVENT] type=SUSPECT traceId=LOCK-20261010-172450-0002 pkg=com.tencent.mm issue=SHOULD_BLOCK_FALSE durationMs=0\n"

        assertTrue(collapser.accept(a).isNotEmpty())
        assertEquals("", collapser.accept(b))
    }

    @Test
    fun writerCollapsesRepeatsInDiagnosticTierButNeverInPermanentTier() {
        val a = SegmentedLogStore(folder.newFolder("a"), "journal", 100_000, 1_000_000)
        val b = SegmentedLogStore(folder.newFolder("b"), "trace", 100_000, 1_000_000)
        val writer = TieredLogWriter(a, b, LogScheduler { _, _ -> })

        repeat(20) { writer.append(LogTier.A, decision("17:24:%02d.000".format(10 + it), it + 1)) }
        repeat(20) { writer.append(LogTier.B, decision("17:25:%02d.000".format(10 + it), it + 1)) }
        writer.flush()

        val aRecords = a.readLines().count { it.contains(" [FG_DECISION] eventId=") }
        val bRecords = b.readLines().count { it.contains(" [FG_DECISION] eventId=") }
        assertEquals("A 级不折叠", 20, aRecords)
        assertEquals("B 级只留第一条", 1, bRecords)
        assertTrue("flush 时补写汇总", b.readLines().any { it.contains("[REPEATED]") && it.contains("n=19") })
    }

    @Test
    fun summaryIsNotLostWhenNothingElseIsWritten() {
        val a = SegmentedLogStore(folder.newFolder("a2"), "journal", 100_000, 1_000_000)
        val b = SegmentedLogStore(File(folder.root, "b2"), "trace", 100_000, 1_000_000)
        val writer = TieredLogWriter(a, b, LogScheduler { _, _ -> })

        writer.append(LogTier.B, decision("17:24:48.104", 1))
        writer.append(LogTier.B, decision("17:24:49.104", 2))
        writer.flush()

        assertFalse(b.readLines().none { it.contains("[REPEATED]") })
    }

    @Test
    fun interleavedKindsAreCollapsedIndependently() {
        val collapser = RepeatCollapser()
        fun suspect(time: String) =
            "2026-10-10 $time MonitorService: [EVENT] type=SUSPECT traceId=LOCK-20261010-172448-0001 pkg=com.tencent.mm issue=SHOULD_BLOCK_FALSE\n"

        val written = StringBuilder()
        (0 until 10).forEach {
            written.append(collapser.accept(decision("17:25:%02d.000".format(10 + it), it + 1)))
            written.append(collapser.accept(suspect("17:25:%02d.500".format(10 + it))))
        }
        written.append(collapser.drainSummary())

        val lines = written.toString().lines().filter { it.isNotEmpty() }
        assertEquals("每类只留第一条", 1, lines.count { it.contains(" [FG_DECISION] eventId=") })
        assertEquals(1, lines.count { it.contains("type=SUSPECT traceId=") })
        assertTrue(lines.any { it.contains("[REPEATED] of=[FG_DECISION] n=9") })
        assertTrue(lines.any { it.contains("[REPEATED] of=MonitorService:_[EVENT]_type=SUSPECT n=9") })
    }

    @Test
    fun labelSeparatesStructuredAndLegacyKinds() {
        assertEquals(
            "[FG_DECISION]",
            RepeatCollapser.labelOf("2026-10-10 17:24:48.104 [FG_DECISION] eventId=x seq=1\n")
        )
        assertEquals(
            "MonitorService: [EVENT] type=SUSPECT",
            RepeatCollapser.labelOf("2026-10-10 17:24:48.104 MonitorService: [EVENT] type=SUSPECT traceId=LOCK-1\n")
        )
        assertEquals(
            "MonitorService: [EVENT] type=UNLOCK_RESULT",
            RepeatCollapser.labelOf("2026-10-10 17:24:48.104 MonitorService: [EVENT] type=UNLOCK_RESULT pkg=x\n")
        )
    }
}
