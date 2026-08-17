package com.ironwill.app

import com.ironwill.app.data.ForegroundDetectionLogCompressor
import com.ironwill.app.data.ForegroundDetectionLogState
import com.ironwill.app.data.buildForegroundDetectionLogDetail
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test





class ForegroundDetectionLogTest {

    @Test
    fun detailContainsDecisionContext() {
        val detail = buildForegroundDetectionLogDetail(
            ForegroundDetectionLogState(
                packageName = "com.tencent.mobileqq",
                evidence = "EVENTS",
                isDndLocked = true,
                isFreeTime = false,
                freeTimeRemainSeconds = 0,
                temporaryAccessRemainSeconds = 0,
                temporaryCooldownRemainSeconds = 240,
                restCooldownRemainSeconds = 30,
                remainingMinutes = 1.5,
                remainingUsableCount = 2,
                isLocked = false,
                homePendingPackage = "com.tencent.mobileqq",
                detectionTimestamp = 1_234L,
                secondaryPackage = "com.bbk.launcher2",
                secondaryEvidence = "USAGE_STATS"
            )
        )

        // 精简格式：只输出关键字段，省略稳定默认值
        assertTrue(detail.contains("pkg=com.tencent.mobileqq"))
        assertTrue(detail.contains("src=EVENTS"))
        assertTrue(detail.contains("alt=com.bbk.launcher2(USAGE_STATS)"))
        assertTrue(detail.contains("ts="))
        assertTrue(detail.contains("(1234)"))
        assertTrue(detail.contains("quota=1.5min/2次"))
        assertTrue(detail.contains("tempCool=240s"))
        assertTrue(detail.contains("cool=30s"))
        assertTrue(detail.contains("home=com.tencent.mobileqq"))
        // 稳定默认值不输出
        assertFalse(detail.contains("dndLocked"))
        assertFalse(detail.contains("tempAccess=0s"))
        assertFalse(detail.contains("locked="))
    }

    @Test
    fun compressorIgnoresDetectionTimestampWhenCompressing() {
        val compressor = ForegroundDetectionLogCompressor()
        val state = ForegroundDetectionLogState(
            packageName = "com.tencent.mobileqq",
            isLocked = true,
            detectionTimestamp = 1_000L
        )

        val first = compressor.observe(state, nowMs = 1_000L)
        val repeated = compressor.observe(state.copy(detectionTimestamp = 1_500L), nowMs = 1_500L)
        val switched = compressor.observe(
            state.copy(packageName = "com.bbk.launcher2", detectionTimestamp = 2_000L),
            nowMs = 2_000L
        )

        assertEquals(1, first.size)
        assertEquals(0, repeated.size)
        assertEquals(2, switched.size)
        assertTrue(switched[0].contains("count=2"))
    }

    @Test
    fun silentChangesDoNotEmitSummaryWhenEnteringLockedState() {
        val compressor = ForegroundDetectionLogCompressor()
        val silent = ForegroundDetectionLogState(packageName = "com.tencent.mobileqq")

        assertTrue(compressor.observe(silent, nowMs = 1_000L).isEmpty())
        assertTrue(
            compressor.observe(silent.copy(remainingMinutes = 2.0), nowMs = 1_500L).isEmpty()
        )
        val locked = compressor.observe(
            silent.copy(remainingMinutes = 2.0, isLocked = true),
            nowMs = 2_000L
        )

        assertEquals(1, locked.size)
        assertTrue(locked[0].startsWith("FG pkg="))
        assertFalse(locked.any { it.startsWith("FG_SUMMARY") })
    }

    @Test
    fun compressorEmitsFirstAndSummaryForRepeatedSameState() {
        val compressor = ForegroundDetectionLogCompressor()
        val state = ForegroundDetectionLogState(
            packageName = "com.tencent.mobileqq",
            isLocked = true
        )

        val first = compressor.observe(state, nowMs = 1_000L)
        val second = compressor.observe(state, nowMs = 1_500L)
        val third = compressor.observe(state, nowMs = 2_000L)
        val switched = compressor.observe(
            ForegroundDetectionLogState(
                packageName = "com.bbk.launcher2",
                isLocked = true
            ),
            nowMs = 2_500L
        )

        assertEquals(1, first.size)
        assertEquals(0, second.size)
        assertEquals(0, third.size)
        assertEquals(2, switched.size)
        assertTrue(switched[0].contains("FG_SUMMARY pkg=com.tencent.mobileqq"))
        assertTrue(switched[0].contains("count=3"))
        assertTrue(switched[1].contains("FG pkg=com.bbk.launcher2"))
    }

    @Test
    fun compressorEmitsNewDetailWhenStateChangesForSamePackage() {
        val compressor = ForegroundDetectionLogCompressor()
        val unlocked = ForegroundDetectionLogState(
            packageName = "com.tencent.mobileqq",
            isLocked = false
        )
        val locked = unlocked.copy(isLocked = true)

        compressor.observe(unlocked, nowMs = 1_000L)
        val changed = compressor.observe(locked, nowMs = 1_500L)

        assertEquals(1, changed.size)
        assertTrue(changed[0].contains("locked=true"))
    }
}
