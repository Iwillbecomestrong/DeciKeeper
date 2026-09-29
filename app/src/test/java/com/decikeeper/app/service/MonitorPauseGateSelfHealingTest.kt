package com.decikeeper.app.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MonitorPauseGateSelfHealingTest {

    @Test
    fun `reconcileStaleLockedPackage - clears package when lock is not showing`() {
        val result = MonitorService.reconcileStaleLockedPackage(
            currentLockedPkg = "com.tencent.mm",
            isLockShowing = false
        )
        assertNull("When lock is not showing, stale locked package must be cleared to null", result)
    }

    @Test
    fun `reconcileStaleLockedPackage - retains package when lock is showing`() {
        val result = MonitorService.reconcileStaleLockedPackage(
            currentLockedPkg = "com.tencent.mm",
            isLockShowing = true
        )
        assertEquals("com.tencent.mm", result)
    }

    @Test
    fun `isOrphanInputPanelOpen - detects orphan input panel when lock is not showing`() {
        val isOrphan = MonitorService.isOrphanInputPanelOpen(
            isLockShowing = false,
            isInputPanelOpen = true
        )
        assertTrue("Input panel open without lock showing is an orphan state", isOrphan)

        val isLegit = MonitorService.isOrphanInputPanelOpen(
            isLockShowing = true,
            isInputPanelOpen = true
        )
        assertFalse("Input panel open while lock is showing is legitimate", isLegit)
    }

    @Test
    fun `vivo card is treated as noise package in ForegroundSessionTracker`() {
        assertTrue(
            "com.vivo.card must be recognized as carrier noise package",
            ForegroundSessionTracker.defaultIsNoisePackage("com.vivo.card")
        )
        assertTrue(
            "com.vivo.upslide must be recognized as carrier noise package",
            ForegroundSessionTracker.defaultIsNoisePackage("com.vivo.upslide")
        )
    }
}
