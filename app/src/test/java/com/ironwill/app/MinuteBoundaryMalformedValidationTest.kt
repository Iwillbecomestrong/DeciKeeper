package com.ironwill.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * WP-03-T01 REVISE — `MinuteBoundary` malformed / out-of-range validation
 * (Luna REVISE 修补).
 *
 * Verdict sealed class hierarchy (v3 REVISE):
 *   - `BoundaryResult.Legal`
 *   - `BoundaryResult.Rejected(reason)` — reasons:
 *       * REASON_REFRESH_STRICTLY_INSIDE_SEGMENT          (existing)
 *       * REASON_CANDIDATE_STRICTLY_INSIDE_SEGMENT        (existing)
 *       * REASON_MALFORMED_OUT_OF_RANGE                   (new)
 *       * REASON_MALFORMED_ZERO_LENGTH_SEGMENT            (new)
 *
 *   The new reasons use the stable lowercase-ASCII prefix
 *   "malformed_"; the precise token values are part of the WP-03-T01
 *   REVISE patch contract and may be tightened later.
 *
 * Validator semantics (v3 SPEC §3.5/§3.6 + REVISE):
 *   - start < 0 / end > 1440 / refresh < 0 / refresh > 1440 → Malformed
 *     (out-of-range). Note: end == 1440 is the upper boundary and is
 *     considered legal because the minute-of-day upper-bound is exclusive
 *     for end in some legacy callers; we treat [0, 1440] as inclusive on
 *     start/refresh and (0, 1440] as inclusive on end (i.e. minute 1440
 *     represents 24:00, valid as endpoint for a [start, 1440) segment).
 *   - start >= end (with both in valid range, includes zero-length start==end)
 *     → Malformed (zero-length / inverted segment, even when within
 *     [0, 1440] range).
 *   - legal range + `start < refresh && refresh < end` → REJECTED (existing
 *     behavior).
 *   - legal range + no strict-crossing → Legal.
 *
 * The cases below exercise these branches; they are RED until the WP-03-T01
 * REVISE patch adds the malformed validation.
 */
class MinuteBoundaryMalformedValidationTest {

    // ────────────────────────────────────────────────────────────
    //  #1 isSegmentLegal — out-of-range / malformed inputs
    // ────────────────────────────────────────────────────────────

    @Test
    fun isSegmentLegal_rejectsStartNegative() {
        val r = MinuteBoundary.isSegmentLegal(start = -1, end = 60, refresh = 360)
        assertRejectedMalformed(
            r,
            "start=-1 (< 0) 越界应被 REJECTED_MALFORMED"
        )
    }

    @Test
    fun isSegmentLegal_rejectsEndAboveOneDay() {
        val r = MinuteBoundary.isSegmentLegal(start = 0, end = 1441, refresh = 360)
        assertRejectedMalformed(
            r,
            "end=1441 (> 1440) 越界应被 REJECTED_MALFORMED"
        )
    }

    @Test
    fun isSegmentLegal_rejectsRefreshNegative() {
        val r = MinuteBoundary.isSegmentLegal(start = 0, end = 120, refresh = -1)
        assertRejectedMalformed(
            r,
            "refresh=-1 (< 0) 越界应被 REJECTED_MALFORMED"
        )
    }

    @Test
    fun isSegmentLegal_rejectsRefreshAboveOneDay() {
        val r = MinuteBoundary.isSegmentLegal(start = 0, end = 120, refresh = 1441)
        assertRejectedMalformed(
            r,
            "refresh=1441 (> 1440) 越界应被 REJECTED_MALFORMED"
        )
    }

    @Test
    fun isSegmentLegal_rejectsRefreshExactly1441AsMalformed() {
        // 边界值 refresh=1440 (== 24:00) 在本设计里视为合法 (minute-of-day
        // 闭合区间);但 refresh=1441 越界,应 Malformed。
        val r = MinuteBoundary.isSegmentLegal(start = 0, end = 120, refresh = 1441)
        assertRejectedMalformed(
            r,
            "refresh=1441 越界应 Malformed"
        )
    }

    @Test
    fun isSegmentLegal_acceptsRefreshAtUpperBound1440() {
        // refresh=1440 (== 24:00) 是 minute-of-day 闭合上界,合法。
        // 一个 [0, 60) 的段不与 refresh=1440 形成 strict crossing。
        val r = MinuteBoundary.isSegmentLegal(start = 0, end = 60, refresh = 1440)
        assertLegal(
            r,
            "refresh=1440 (合法闭合上界) + 段 [0, 60) → Legal"
        )
    }

    @Test
    fun isSegmentLegal_rejectsZeroLengthSegment() {
        // start == end,0 长度段,Malformed。
        val r = MinuteBoundary.isSegmentLegal(start = 720, end = 720, refresh = 360)
        assertRejectedMalformed(
            r,
            "start==end=720 (0 长度段) 应 Malformed"
        )
    }

    @Test
    fun isSegmentLegal_rejectsInvertedSegment() {
        // start > end,反向段。IronWill 用 startMins/endMins ∈ [0, 1440) 表达,
        // start > end 既不是合法 non-cross-midnight 也不是合法 cross-midnight
        // (cross-midnight 要求 endMins <= startMins 但同时 end < 1440 且
        // start > 0 等额外约束;此处 start > end + start > end 仍属 Malformed
        // 兜底)。
        val r = MinuteBoundary.isSegmentLegal(start = 120, end = 60, refresh = 90)
        assertRejectedMalformed(
            r,
            "start=120 > end=60 (反向) 应 Malformed"
        )
    }

    // ────────────────────────────────────────────────────────────
    //  #2 isSegmentLegal — valid cases still work after patch
    // ────────────────────────────────────────────────────────────

    @Test
    fun isSegmentLegal_acceptsValidNonCrossingSegment() {
        // 08:00-22:00 不跨 refresh=06:00,合法。
        val r = MinuteBoundary.isSegmentLegal(start = 480, end = 1320, refresh = 360)
        assertLegal(
            r,
            "段 [480, 1320) ⊂ [06:00, 24:00) 不跨 refresh,应 Legal"
        )
    }

    @Test
    fun isSegmentLegal_acceptsStartEqualsRefresh() {
        // start == refresh,端点重合,合法。
        val r = MinuteBoundary.isSegmentLegal(start = 360, end = 1320, refresh = 360)
        assertLegal(
            r,
            "start==refresh=360 段 [360, 1320) 端点重合,应 Legal"
        )
    }

    @Test
    fun isSegmentLegal_acceptsEndEqualsRefresh() {
        // end == refresh,端点重合,合法。
        val r = MinuteBoundary.isSegmentLegal(start = 0, end = 360, refresh = 360)
        assertLegal(
            r,
            "end==refresh=360 段 [0, 360) 端点重合,应 Legal"
        )
    }

    @Test
    fun isSegmentLegal_rejectsValidStrictCrossing() {
        // 段 [300, 1320) 严格内含 refresh=360,合法范围内 strict-crossing。
        val r = MinuteBoundary.isSegmentLegal(start = 300, end = 1320, refresh = 360)
        assertTrue(
            "段 [300, 1320) 内含 refresh=360 应被 REJECTED (crossing reason)",
            r is BoundaryResult.Rejected &&
                !r.reason.startsWith("malformed_")
        )
    }

    @Test
    fun isSegmentLegal_acceptsCrossMidnightSegmentIsRejectedAsMalformed() {
        // 跨午夜段 [1320, 120) (= 22:00 - 02:00) 在 REVISE 设计下被视为
        // 反向段(start > end),应 Malformed(zero-length/inverted)而不是 Legal。
        // 真正的跨午夜段表达应该 start < end,refresh 物理位置由
        // isRefreshLegal 的 wrap-around 检测。
        val r = MinuteBoundary.isSegmentLegal(start = 1320, end = 120, refresh = 360)
        assertRejectedMalformed(
            r,
            "跨午夜段 [1320, 120) (start > end) 在 REVISE 下应 Malformed"
        )
    }

    // ────────────────────────────────────────────────────────────
    //  #3 isRefreshLegal — out-of-range / malformed inputs
    // ────────────────────────────────────────────────────────────

    @Test
    fun isRefreshLegal_rejectsCandidateNegative() {
        val seg = TimeSegment(
            startHour = 8, startMinute = 0,
            endHour = 22, endMinute = 0,
            limitMins = 60, name = "8-22 段"
        )
        val r = MinuteBoundary.isRefreshLegal(candidate = -1, currentSegments = listOf(seg))
        assertRejectedMalformed(
            r,
            "candidate=-1 越界应 Malformed"
        )
    }

    @Test
    fun isRefreshLegal_rejectsCandidateAboveOneDay() {
        val seg = TimeSegment(
            startHour = 8, startMinute = 0,
            endHour = 22, endMinute = 0,
            limitMins = 60, name = "8-22 段"
        )
        val r = MinuteBoundary.isRefreshLegal(candidate = 1441, currentSegments = listOf(seg))
        assertRejectedMalformed(
            r,
            "candidate=1441 越界应 Malformed"
        )
    }

    @Test
    fun isRefreshLegal_rejectsMalformedSegmentInputs() {
        // 段 startHour=-1 (越界) 本身是 Malformed。
        val badSeg = TimeSegment(
            startHour = -1, startMinute = 0,
            endHour = 22, endMinute = 0,
            limitMins = 60, name = "bad segment start=-1"
        )
        val r = MinuteBoundary.isRefreshLegal(candidate = 360, currentSegments = listOf(badSeg))
        assertRejectedMalformed(
            r,
            "currentSegments 包含越界 startHour 应被 Malformed 兜底"
        )
    }

    @Test
    fun isRefreshLegal_acceptsEmptySegmentList() {
        // 现有约定:空 segments → Legal (没有可切坏的段)。
        val r = MinuteBoundary.isRefreshLegal(candidate = 360, currentSegments = emptyList())
        assertLegal(
            r,
            "空 segments 列表 → Legal"
        )
    }

    @Test
    fun isRefreshLegal_acceptsCandidateInGap() {
        // refresh=12:00 (720) 落在 8-10 (480-600) 与 14-22 (840-1320) gap。
        val a = TimeSegment(
            startHour = 8, startMinute = 0, endHour = 10, endMinute = 0,
            limitMins = 30, name = "morning"
        )
        val b = TimeSegment(
            startHour = 14, startMinute = 0, endHour = 22, endMinute = 0,
            limitMins = 60, name = "evening"
        )
        val r = MinuteBoundary.isRefreshLegal(candidate = 720, currentSegments = listOf(a, b))
        assertLegal(
            r,
            "refresh=720 落在 gap → Legal"
        )
    }

    @Test
    fun isRefreshLegal_rejectsCandidateStrictlyInsideSegment() {
        val seg = TimeSegment(
            startHour = 8, startMinute = 0, endHour = 22, endMinute = 0,
            limitMins = 60, name = "8-22 段"
        )
        val r = MinuteBoundary.isRefreshLegal(candidate = 720, currentSegments = listOf(seg))
        assertTrue(
            "refresh=720 严格内含段 [480, 1320) 应被 REJECTED (crossing reason)",
            r is BoundaryResult.Rejected &&
                !r.reason.startsWith("malformed_")
        )
    }

    // ────────────────────────────────────────────────────────────
    //  helpers
    // ────────────────────────────────────────────────────────────

    private fun assertLegal(r: BoundaryResult, msg: String) {
        if (r !is BoundaryResult.Legal) {
            fail("$msg; expected Legal, got Rejected(${if (r is BoundaryResult.Rejected) r.reason else "?"})")
        }
    }

    private fun assertRejectedMalformed(r: BoundaryResult, msg: String) {
        if (r !is BoundaryResult.Rejected) {
            fail("$msg; expected Rejected(Malformed*), got Legal")
            return
        }
        val reason = r.reason
        val malformed = reason == "malformed_out_of_range" ||
            reason == "malformed_zero_length_segment" ||
            reason.startsWith("malformed_")
        if (!malformed) {
            fail("$msg; expected Malformed reason (malformed_*), got reason=$reason")
        }
    }
}