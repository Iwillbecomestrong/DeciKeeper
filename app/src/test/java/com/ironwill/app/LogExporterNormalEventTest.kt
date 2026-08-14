package com.ironwill.app

import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LogExporterNormalEventTest {

    @Test
    fun rangeFilteringKeepsOnlyInRangeFullDateNormalEventBlocks() {
        val insideAt = parse("2026-07-13 08:30:00.000")
        val outsideAt = parse("2026-07-13 08:20:00.000")
        val lines = listOf(
            "2026-07-13 08:20:00.000 [FG_DECISION] eventId=process-1 processInstanceId=process seq=1",
            "[1 result] must-not-leak",
            "2026-07-13 08:30:00.000 [FG_DECISION] eventId=process-2 processInstanceId=process seq=2",
            "[1 result] keep-with-header"
        )

        val filtered = LogExporter.filterInternalLogLinesInRange(
            lines = lines,
            startTimeMillis = insideAt,
            endTimeMillis = insideAt
        )

        assertEquals(
            listOf(
                "2026-07-13 08:30:00.000 [FG_DECISION] eventId=process-2 processInstanceId=process seq=2",
                "[1 result] keep-with-header"
            ),
            filtered
        )
        assertFalse(filtered.any { it.contains("must-not-leak") })
        assertTrue(outsideAt < insideAt)
    }

    @Test
    fun sharedWriterKeepsNormalBlocksAndLegacyEntriesNonInterleaved() {
        val file = File.createTempFile("ironwill-normal-event", ".log")
        val writer = InternalLogFileWriter(Long.MAX_VALUE) { throw AssertionError("truncate not expected") }
        val count = 24
        val ready = CountDownLatch(count)
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(count)

        try {
            val futures = (0 until count).map { index ->
                executor.submit {
                    ready.countDown()
                    assertTrue(start.await(5, TimeUnit.SECONDS))
                    if (index % 2 == 0) {
                        writer.append(file, "NORMAL-$index\nnormal-body-$index\n")
                    } else {
                        writer.append(file, "LEGACY-$index\n")
                    }
                }
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS))
            start.countDown()
            futures.forEach { it.get(5, TimeUnit.SECONDS) }
        } finally {
            executor.shutdownNow()
        }

        val content = file.readText()
        try {
            (0 until count step 2).forEach { index ->
                assertTrue(content.contains("NORMAL-$index\nnormal-body-$index\n"))
            }
            (1 until count step 2).forEach { index ->
                assertTrue(content.contains("LEGACY-$index\n"))
            }
        } finally {
            file.delete()
        }
    }

    @Test
    fun truncationStartsAtNextHeaderInsteadOfKeepingAnOrphanNormalEventBody() {
        val file = File.createTempFile("ironwill-normal-event-truncate", ".log")
        file.writeText(
            listOf(
                "07-13 07:59:00.000 Legacy: before-old-event",
                "2026-07-13 08:00:00.000 [FG_DECISION] eventId=process-1 processInstanceId=process seq=1",
                "[1 result] old-result",
                "[2 evidence] old-evidence",
                "[3 inference] orphan-at-midpoint",
                "[4 lock] old-lock",
                "2026-07-13 08:01:00.000 [FG_DECISION] eventId=process-2 processInstanceId=process seq=2",
                "[1 result] retained-complete-block"
            ).joinToString("\n", postfix = "\n")
        )
        val writer = InternalLogFileWriter(1L, LogExporter::truncateLogFile)

        try {
            writer.append(file, "07-13 08:02:00.000 Legacy: appended-after-truncate\n")

            val content = file.readText()
            assertFalse(content.contains("orphan-at-midpoint"))
            assertFalse(content.contains("old-result"))
            assertTrue(
                content.contains(
                    "2026-07-13 08:01:00.000 [FG_DECISION] eventId=process-2 processInstanceId=process seq=2\n" +
                        "[1 result] retained-complete-block"
                )
            )
            assertTrue(content.contains("07-13 08:02:00.000 Legacy: appended-after-truncate"))
        } finally {
            file.delete()
        }
    }

    private fun parse(value: String): Long =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).parse(value)!!.time
}
