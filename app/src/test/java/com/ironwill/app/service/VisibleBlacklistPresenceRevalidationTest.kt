package com.ironwill.app.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VisibleBlacklistPresenceRevalidationTest {

    private val testPkg = "com.ss.android.ugc.aweme"

    @Test
    fun `failed accessibility scan with stale package must not satisfy desktop revalidation`() {
        val tracker = VisibleBlacklistPresenceTracker(desktopGraceDurationMs = 3000L)
        tracker.onPresenceConfirmed(testPkg, 10_000L)
        tracker.onDesktopReturn(11_000L, requireAccessibilityRevalidation = true)

        // CoreService may retain the previous package set after a failed window query.
        // The stale set must not be treated as a fresh observation that keeps locking.
        tracker.onAccessibilitySnapshot(
            visiblePackages = setOf(testPkg, "com.bbk.launcher2"),
            nowMs = 12_000L,
            scanSucceeded = false,
            canConfirmAbsence = true
        )

        assertTrue(tracker.accessibilityRevalidationRequired)
        assertEquals(PresenceTrackerAction.NONE, tracker.evaluate(14_500L))
        assertEquals(PresenceTrackerState.DESKTOP_GRACE, tracker.state)
    }

    @Test
    fun `fresh successful window snapshot keeps a static small window lockable without new lifecycle event`() {
        val tracker = VisibleBlacklistPresenceTracker(desktopGraceDurationMs = 3000L)
        tracker.onPresenceConfirmed(testPkg, 10_000L)
        tracker.onDesktopReturn(11_000L, requireAccessibilityRevalidation = true)

        // No MOVE_TO_FOREGROUND / RESUMED event is needed. A fresh active-window snapshot is enough.
        tracker.onAccessibilitySnapshot(
            visiblePackages = setOf("com.bbk.launcher2", testPkg),
            nowMs = 12_000L,
            scanSucceeded = true,
            canConfirmAbsence = true
        )

        assertFalse(tracker.accessibilityRevalidationRequired)
        assertEquals(PresenceTrackerAction.LOCK, tracker.evaluate(14_500L))
        assertEquals(PresenceTrackerState.CONFIRMED_VISIBLE, tracker.state)
    }

    @Test
    fun `window absence hidden behind IronWill lock must not clear presence`() {
        val tracker = VisibleBlacklistPresenceTracker(desktopGraceDurationMs = 3000L)
        tracker.onPresenceConfirmed(testPkg, 10_000L)

        val action = tracker.onAccessibilitySnapshot(
            visiblePackages = setOf("com.ironwill.app"),
            nowMs = 10_500L,
            scanSucceeded = true,
            canConfirmAbsence = false
        )

        assertEquals(PresenceTrackerAction.NONE, action)
        assertEquals(PresenceTrackerState.CONFIRMED_VISIBLE, tracker.state)
        assertEquals(testPkg, tracker.currentPackage)
    }

    @Test
    fun `usage stats cannot re-establish tracked package while desktop window revalidation is pending`() {
        val tracker = VisibleBlacklistPresenceTracker(desktopGraceDurationMs = 3000L)
        tracker.onPresenceConfirmed(testPkg, 10_000L)
        tracker.onDesktopReturn(11_000L, requireAccessibilityRevalidation = true)

        assertFalse(
            "lastTimeVisible must not bypass a pending accessibility revalidation",
            tracker.isLastTimeVisibleValid(testPkg, 12_000L)
        )

        tracker.onAccessibilitySnapshot(
            visiblePackages = setOf("com.bbk.launcher2", testPkg),
            nowMs = 12_500L,
            scanSucceeded = true,
            canConfirmAbsence = true
        )

        assertTrue(tracker.isLastTimeVisibleValid(testPkg, 12_600L))
    }
}
