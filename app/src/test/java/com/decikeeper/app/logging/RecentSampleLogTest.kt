package com.decikeeper.app.logging

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RecentSampleLogTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val base = java.time.LocalDateTime.of(2026, 10, 10, 17, 0, 0)
        .atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()

    private fun log(window: Long = RecentSampleLog.DEFAULT_WINDOW_MS) =
        RecentSampleLog(SegmentedLogStore(folder.newFolder(), "sample", 8 * 1024, 16 * 1024), window)

    @Test
    fun samplerAllowsOneSampleEveryInterval() {
        val sampler = IntervalSampler(5_000L)

        assertTrue(sampler.tryAcquire(base))
        assertFalse(sampler.tryAcquire(base + 1_000))
        assertFalse(sampler.tryAcquire(base + 4_999))
        assertTrue(sampler.tryAcquire(base + 5_000))
        assertFalse(sampler.tryAcquire(base + 9_999))
        assertTrue(sampler.tryAcquire(base + 10_000))
    }

    @Test
    fun keepsOnlyTheLastThreeMinutesRelativeToTheNewestSample() {
        val samples = log()
        (0..60).forEach { samples.append(base + it * 5_000L, "[FG_SAMPLE] fg=com.x n=$it") }

        val lines = samples.readRecentLines()

        assertEquals("3 分钟 = 36 个间隔，含两端共 37 条", 37, lines.size)
        assertTrue(lines.first().contains("n=24"))
        assertTrue(lines.last().contains("n=60"))
    }

    @Test
    fun windowIsRelativeToNewestSoAnExportAfterProcessDeathStillShowsTheLastThreeMinutes() {
        val samples = log()
        (0..10).forEach { samples.append(base + it * 5_000L, "[FG_SAMPLE] n=$it") }

        // 导出发生在很久以后，但窗口以最新一条为准，不是以导出时刻为准。
        assertEquals(11, samples.readRecentLines().size)
    }

    @Test
    fun emptyLogReturnsNothing() {
        assertTrue(log().readRecentLines().isEmpty())
    }

    @Test
    fun physicalStorageStaysSmallEvenAfterManyHours() {
        val store = SegmentedLogStore(folder.newFolder(), "sample", 8 * 1024, 16 * 1024)
        val samples = RecentSampleLog(store)

        repeat(3_000) { samples.append(base + it * 5_000L, "[FG_SAMPLE] fg=com.tencent.mm src=EVENTS action=NO_LOCK reason=NOT_BLACKLISTED allowance=UNKNOWN") }

        assertTrue("总大小受限: ${store.totalBytes()}", store.totalBytes() <= 16 * 1024 + 8 * 1024)
        assertTrue(samples.readRecentLines().size in 30..40)
    }

    @Test
    fun sampleLinesAreSingleLineRecordsWithTimestampHeader() {
        val samples = log()
        samples.append(base, "[FG_SAMPLE] fg=com.x")

        val line = samples.readRecentLines().single()
        assertTrue(LogTime.parseHeader(line) == base)
        assertTrue(line.endsWith("[FG_SAMPLE] fg=com.x"))
    }
}
