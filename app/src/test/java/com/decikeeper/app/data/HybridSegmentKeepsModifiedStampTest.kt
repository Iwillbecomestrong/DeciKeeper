package com.decikeeper.app.data

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 混合保存（部分改动延后）重建时段时必须保留「今天改过」标记（lastModified）：
 * 它决定已过时段不再滚存、进行中时段按剩余时间折算。丢掉它会让此前因修改扣掉的结余又加回来
 * （真机 10-09 19:49 额度重算 +10.1 分钟）。
 */
class HybridSegmentKeepsModifiedStampTest {

    private fun segment(limit: Int, lastModified: Long) = TimeSegment(
        startHour = 12, startMinute = 45, endHour = 17, endMinute = 0,
        limitMins = limit, name = "下午", lastModified = lastModified
    )

    @Test
    fun hybridSegmentKeepsTheNewSegmentsModifiedStamp() {
        val baseline = segment(limit = 35, lastModified = 1_000L)
        val edited = segment(limit = 30, lastModified = 5_000L)

        val hybrid = SegmentAdverseAnalysis.createHybridSegment(baseline, edited, SegmentAnalysis())

        assertEquals(5_000L, hybrid.lastModified)
        assertEquals(30, hybrid.limitMins)
    }

    @Test
    fun hybridSegmentOfAnUnmodifiedSegmentStaysUnstamped() {
        val baseline = segment(limit = 35, lastModified = 0L)
        val edited = segment(limit = 30, lastModified = 0L)

        assertEquals(0L, SegmentAdverseAnalysis.createHybridSegment(baseline, edited, SegmentAnalysis()).lastModified)
    }
}
