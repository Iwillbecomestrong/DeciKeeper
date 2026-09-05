package com.zhizhi.app.service

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CoreServiceInterruptLifecycleTest {

    @After
    fun tearDown() {
        CoreService.isServiceActive = false
        CoreService.lastAccessibilitySignalTime = 0L
    }

    @Test
    fun `onInterrupt does not mark a connected accessibility service inactive`() {
        CoreService.isServiceActive = true
        CoreService.lastAccessibilitySignalTime = 12_345L

        CoreService().onInterrupt()

        assertTrue(
            "onInterrupt only interrupts accessibility feedback; it must not mark the service disconnected",
            CoreService.isServiceActive
        )
        assertEquals(
            "onInterrupt must not erase the last real accessibility signal timestamp",
            12_345L,
            CoreService.lastAccessibilitySignalTime
        )
    }
}
