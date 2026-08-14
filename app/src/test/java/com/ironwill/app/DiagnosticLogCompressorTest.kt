/**
 * =================================================================================
 * [Context Header]
 * * @file: DiagnosticLogCompressorTest.kt
 * * @role: Test
 * * @description: 单元测试 DiagnosticLogCompressor 的去重 / SUMMARY / 状态变化逻辑。
 * *              与 Python 端 scripts/logs/filter_diag.py 保持 1:1 行为一致。
 * =================================================================================
 */
package com.ironwill.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticLogCompressorTest {

    @Test
    fun firstObservation_emitsDetail() {
        val c = DiagnosticCompressor<VisCheckStableKey>("VIS_CHECK_DIAG")
        val key = VisCheckStableKey("com.foo", result = false, windowMs = 2000L, a11yWindows = false, lastVisibleRefreshed = false)
        val out = c.observe(key, 1000L)
        assertEquals(1, out.size)
        assertTrue(out[0].contains("[VIS_CHECK_DIAG]"))
        assertTrue(out[0].contains("pkg=com.foo"))
    }

    @Test
    fun identicalKey_emitsNothingUntilInterval() {
        val c = DiagnosticCompressor<VisCheckStableKey>("VIS_CHECK_DIAG", summaryIntervalMs = 1000L)
        val key = VisCheckStableKey("com.foo", result = false, windowMs = 2000L, a11yWindows = false, lastVisibleRefreshed = false)
        c.observe(key, 0L)
        // 10s 内无输出
        val second = c.observe(key, 500L)
        assertEquals(0, second.size)
        val third = c.observe(key, 999L)
        assertEquals(0, third.size)
    }

    @Test
    fun identicalKey_emitsSummaryAfterInterval() {
        val c = DiagnosticCompressor<VisCheckStableKey>("VIS_CHECK_DIAG", summaryIntervalMs = 1000L)
        val key = VisCheckStableKey("com.foo", result = false, windowMs = 2000L, a11yWindows = false, lastVisibleRefreshed = false)
        c.observe(key, 0L)
        c.observe(key, 100L)
        c.observe(key, 200L)
        // 跨越 1000ms 后应输出 SUMMARY
        val out = c.observe(key, 1500L)
        assertEquals(1, out.size)
        assertTrue(out[0].contains("[SUMMARY]"))
        assertTrue(out[0].contains("count="))
        assertTrue(out[0].contains("durationMs="))
    }

    @Test
    fun stateChange_emitsSummaryThenDetail() {
        val c = DiagnosticCompressor<FgBypassStableKey>("FG_BYPASS_DIAG", summaryIntervalMs = 1000L)
        val k1 = FgBypassStableKey(
            pkg = "com.foo", guard = false, fallback = false,
            forceRelock = false, forcePost = false, detached = false,
            passed = false, src = "EVENTS",
            a11y = false, inA11yWindows = false
        )
        val k2 = k1.copy(passed = true) // state change
        c.observe(k1, 0L)
        c.observe(k1, 100L)
        c.observe(k1, 200L)
        // 状态变化：先 SUMMARY (count>1) 然后 DETAIL
        val out = c.observe(k2, 300L)
        assertEquals(2, out.size)
        assertTrue(out[0].contains("[SUMMARY]"))
        assertTrue(out[0].contains("count=3"))
        assertTrue(out[1].contains("[FG_BYPASS_DIAG]"))
    }

    @Test
    fun stateChange_withoutRepeats_emitsDetailOnly() {
        val c = DiagnosticCompressor<ShouldBlockStableKey>("SHOULD_BLOCK_DIAG")
        val k1 = ShouldBlockStableKey("com.foo", false, false, false, false, false, false)
        val k2 = k1.copy(shouldBlock = true)
        c.observe(k1, 0L) // 初次 — DETAIL
        // 立即变化: count=1 → 无 SUMMARY
        val out = c.observe(k2, 100L)
        assertEquals(1, out.size)
        assertTrue(out[0].contains("[SHOULD_BLOCK_DIAG]"))
    }

    @Test
    fun debugMode_emitsEveryDetail() {
        val c = DiagnosticCompressor<VisCheckStableKey>("VIS_CHECK_DIAG")
        c.debug = true
        val key = VisCheckStableKey("com.foo", false, 2000L, false, false)
        val out1 = c.observe(key, 0L)
        val out2 = c.observe(key, 100L)
        val out3 = c.observe(key, 200L)
        assertEquals(1, out1.size)
        assertEquals(1, out2.size)
        assertEquals(1, out3.size)
        // debug 模式不输出 SUMMARY
        assertFalse(out2[0].contains("[SUMMARY]"))
    }

    @Test
    fun flush_emitsPendingSummary() {
        val c = DiagnosticCompressor<VisCheckStableKey>("VIS_CHECK_DIAG", summaryIntervalMs = 999_999L)
        val key = VisCheckStableKey("com.foo", false, 2000L, false, false)
        c.observe(key, 0L)
        c.observe(key, 100L)
        c.observe(key, 200L)
        // 强制 flush
        val out = c.flush(300L)
        assertEquals(1, out.size)
        assertTrue(out[0].contains("[SUMMARY]"))
        assertTrue(out[0].contains("count=3"))
    }

    @Test
    fun flush_emptyState_returnsEmpty() {
        val c = DiagnosticCompressor<VisCheckStableKey>("VIS_CHECK_DIAG")
        assertEquals(0, c.flush(0L).size)
    }

    @Test
    fun stableKey_equalityRespectsAllFields() {
        val a = VisCheckStableKey("com.foo", false, 2000L, false, false)
        val b = VisCheckStableKey("com.foo", false, 2000L, false, false)
        val c = VisCheckStableKey("com.foo", true, 2000L, false, false)
        assertEquals(a, b)
        assertFalse(a == c)
    }
}