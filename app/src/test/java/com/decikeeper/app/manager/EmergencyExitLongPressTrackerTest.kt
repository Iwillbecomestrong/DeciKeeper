package com.decikeeper.app.manager

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 应急退出长按判定：满 3 秒才触发；提前松手、滑出、第二根手指一律取消清零。纯 JVM。 */
class EmergencyExitLongPressTrackerTest {

    private val t = EmergencyExitLongPressTracker()

    @Test
    fun idleHasNoProgressAndNeverTriggers() {
        assertEquals(0f, t.progress(10_000L), 0f)
        assertFalse(t.consumeTrigger(10_000L))
    }

    @Test
    fun triggersOnlyAfterFullThreeSeconds() {
        t.onDown(1_000L)
        assertFalse(t.consumeTrigger(3_999L))
        assertEquals(0.5f, t.progress(2_500L), 0.001f)
        assertTrue(t.consumeTrigger(4_000L))
    }

    @Test
    fun triggersAtMostOncePerPress() {
        t.onDown(0L)
        assertTrue(t.consumeTrigger(3_500L))
        assertFalse(t.consumeTrigger(3_600L))
        assertEquals(0f, t.progress(3_700L), 0f)
    }

    @Test
    fun releaseEarlyCancelsAndResets() {
        t.onDown(0L)
        t.onUpOrCancel()
        assertFalse(t.consumeTrigger(5_000L))
        assertEquals(0f, t.progress(5_000L), 0f)
        // 重新按下需要重新计满 3 秒
        t.onDown(6_000L)
        assertFalse(t.consumeTrigger(8_000L))
        assertTrue(t.consumeTrigger(9_000L))
    }

    @Test
    fun slidingOutsideCancels() {
        t.onDown(0L)
        t.onMove(inside = true)
        assertEquals(0.5f, t.progress(1_500L), 0.001f)
        t.onMove(inside = false)
        assertFalse(t.consumeTrigger(4_000L))
        assertEquals(0f, t.progress(4_000L), 0f)
    }

    @Test
    fun secondPointerCancels() {
        t.onDown(0L)
        t.onSecondPointer()
        assertFalse(t.consumeTrigger(4_000L))
    }

    @Test
    fun progressIsCappedAtOne() {
        t.onDown(0L)
        assertEquals(1f, t.progress(10_000L), 0f)
    }
}
