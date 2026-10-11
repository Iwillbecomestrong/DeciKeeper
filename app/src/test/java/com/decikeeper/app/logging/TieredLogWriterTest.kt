package com.decikeeper.app.logging

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TieredLogWriterTest {

    @get:Rule
    val folder = TemporaryFolder()

    private class ManualScheduler : LogScheduler {
        val tasks = mutableListOf<Pair<Long, () -> Unit>>()

        override fun schedule(delayMs: Long, task: () -> Unit) {
            tasks += delayMs to task
        }

        fun runAll() {
            val pending = tasks.toList()
            tasks.clear()
            pending.forEach { it.second() }
        }
    }

    private fun stores(): Pair<SegmentedLogStore, SegmentedLogStore> =
        SegmentedLogStore(folder.newFolder("a"), "journal", 10_000, 100_000) to
            SegmentedLogStore(folder.newFolder("b"), "trace", 10_000, 100_000)

    @Test
    fun aRecordSchedulesImmediateFlush() {
        val (a, b) = stores()
        val scheduler = ManualScheduler()
        val writer = TieredLogWriter(a, b, scheduler)

        writer.append(LogTier.A, "2026-10-10 10:00:00.000 [LOCK_RESULT] x=1")

        assertEquals(listOf(0L), scheduler.tasks.map { it.first })
        scheduler.runAll()
        assertEquals(1, a.readLines().size)
        assertEquals(0, b.readLines().size)
    }

    @Test
    fun bRecordsAreBatchedUntilInterval() {
        val (a, b) = stores()
        val scheduler = ManualScheduler()
        val writer = TieredLogWriter(a, b, scheduler, flushIntervalMs = 5_000L)

        repeat(50) { writer.append(LogTier.B, "2026-10-10 10:00:00.000 [FG] n=$it") }

        assertEquals("50 条 B 记录只调度一次延迟写入", listOf(5_000L), scheduler.tasks.map { it.first })
        assertEquals("未到时间不落盘", 0, b.readLines().size)
        scheduler.runAll()
        assertEquals(50, b.readLines().size)
    }

    @Test
    fun explicitFlushWritesBothTiersSynchronously() {
        val (a, b) = stores()
        val writer = TieredLogWriter(a, b, ManualScheduler())

        writer.append(LogTier.A, "2026-10-10 10:00:00.000 [A] 1")
        writer.append(LogTier.B, "2026-10-10 10:00:01.000 [B] 2")
        writer.flush()

        assertEquals(1, a.readLines().size)
        assertEquals(1, b.readLines().size)
    }

    @Test
    fun flushDoesNotDuplicateRecords() {
        val (a, b) = stores()
        val scheduler = ManualScheduler()
        val writer = TieredLogWriter(a, b, scheduler)

        writer.append(LogTier.A, "2026-10-10 10:00:00.000 [A] 1")
        writer.flush()
        scheduler.runAll()
        writer.flush()

        assertEquals(1, a.readLines().size)
    }

    @Test
    fun pendingBufferIsBoundedWhenDiskNeverFlushes() {
        val (a, b) = stores()
        val writer = TieredLogWriter(a, b, ManualScheduler(), maxPendingChars = 2_000)

        repeat(500) { writer.append(LogTier.B, "2026-10-10 10:00:00.000 [FG] n=$it ".padEnd(60, 'x')) }
        writer.flush()

        val lines = b.readLines()
        assertTrue("缓冲必须被限制", lines.size < 500)
        assertTrue("保留的是最新记录", lines.last().contains("n=499"))
        assertTrue("不得留下半截记录", lines.all { it.startsWith("2026-10-10") })
    }

    @Test
    fun diskFailureDoesNotThrowIntoBusinessCode() {
        val blocker = folder.newFile("not-a-dir")
        val broken = SegmentedLogStore(File(blocker, "sub"), "x", 1_000, 10_000)
        val writer = TieredLogWriter(broken, broken, ManualScheduler())

        writer.append(LogTier.A, "2026-10-10 10:00:00.000 [A] 1")
        writer.flush()
    }
}
