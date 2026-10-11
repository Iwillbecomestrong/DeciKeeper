package com.decikeeper.app.logging

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SegmentedLogStoreTest {

    @get:Rule
    val folder = TemporaryFolder()

    private fun record(n: Int, size: Int = 90): String =
        "2026-10-10 10:00:%02d.000 [E] n=$n ".format(n % 60).padEnd(size - 1, 'x') + "\n"

    private fun numbers(store: SegmentedLogStore): List<Int> =
        store.readLines().map { Regex("n=(\\d+)").find(it)!!.groupValues[1].toInt() }

    @Test
    fun appendReadsBackInOrder() {
        val store = SegmentedLogStore(folder.root, "t", segmentBytes = 1_000, maxTotalBytes = 10_000)
        (1..5).forEach { store.append(record(it)) }

        assertEquals(listOf(1, 2, 3, 4, 5), numbers(store))
    }

    @Test
    fun rollsToNewSegmentWhenCurrentIsFull() {
        val store = SegmentedLogStore(folder.root, "t", segmentBytes = 300, maxTotalBytes = 10_000)
        (1..10).forEach { store.append(record(it)) }

        assertTrue("应当分成多个分片", store.segmentCount() > 1)
        assertEquals(10, store.readLines().size)
    }

    @Test
    fun dropsOldestWholeSegmentWhenOverBudget() {
        val store = SegmentedLogStore(folder.root, "t", segmentBytes = 300, maxTotalBytes = 900)
        (1..60).forEach { store.append(record(it)) }

        assertTrue("总大小不得明显超过预算", store.totalBytes() <= 900 + 300)
        val kept = numbers(store)
        assertEquals("最新记录必须保留", 60, kept.last())
        assertFalse("最老记录应已被整片删除", kept.contains(1))
        assertEquals("保留的记录必须连续，不能出现半截数据", (kept.first()..60).toList(), kept)
    }

    @Test
    fun neverRewritesSurvivingSegments() {
        val store = SegmentedLogStore(folder.root, "t", segmentBytes = 300, maxTotalBytes = 900)
        (1..4).forEach { store.append(record(it)) }
        val first = folder.root.listFiles()!!.sortedBy { it.name }.first()
        val before = first.readText()

        (5..8).forEach { store.append(record(it)) }

        if (first.exists()) {
            assertTrue("存活分片内容不得被改写", first.readText().startsWith(before))
        }
    }

    @Test
    fun recordIsNeverSplitAcrossSegments() {
        val store = SegmentedLogStore(folder.root, "t", segmentBytes = 200, maxTotalBytes = 10_000)
        val block = "2026-10-10 10:00:00.000 [E] head\n  body1\n  body2\n"
        repeat(6) { store.append(block) }

        folder.root.listFiles()!!.forEach { file ->
            val lines = file.readLines()
            assertTrue("每个分片必须以记录头开始: ${file.name}", lines.first().startsWith("2026-10-10"))
            assertEquals("每个分片只含完整记录", 0, lines.size % 3)
        }
    }

    @Test
    fun resumesExistingSegmentsAfterRestart() {
        SegmentedLogStore(folder.root, "t", 1_000, 10_000).append(record(1))
        val reopened = SegmentedLogStore(folder.root, "t", 1_000, 10_000)
        reopened.append(record(2))

        assertEquals(2, reopened.readLines().size)
    }

    @Test
    fun differentPrefixesDoNotInterfere() {
        val a = SegmentedLogStore(folder.root, "journal", 1_000, 10_000)
        val b = SegmentedLogStore(File(folder.root.path), "trace", 1_000, 10_000)
        a.append(record(1))
        b.append(record(2))

        assertEquals(1, a.readLines().size)
        assertEquals(1, b.readLines().size)
    }
}
