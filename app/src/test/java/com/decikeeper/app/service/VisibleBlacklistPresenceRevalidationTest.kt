package com.decikeeper.app.service

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class VisibleBlacklistPresenceRevalidationTest {

    private val testPkg = "com.ss.android.ugc.aweme"
    private var originalServiceActive = false
    private var originalVisiblePackages: Set<String> = emptySet()

    @Before
    fun setUpAccessibilityFixture() {
        originalServiceActive = CoreService.isServiceActive
        originalVisiblePackages = CoreService.lastVisiblePackagesFromWindows
        CoreService.isServiceActive = true
        CoreService.lastVisiblePackagesFromWindows = setOf(testPkg, "com.bbk.launcher2")
    }

    @After
    fun restoreAccessibilityFixture() {
        CoreService.isServiceActive = originalServiceActive
        CoreService.lastVisiblePackagesFromWindows = originalVisiblePackages
    }

    @Test
    fun `failed accessibility scan with stale package blocks relock until a fresh observation`() {
        val tracker = VisibleBlacklistPresenceTracker(desktopGraceDurationMs = 3000L)
        tracker.onPresenceConfirmed(testPkg, 10_000L)
        tracker.onDesktopReturn(11_000L)

        // CoreService may retain the previous package set after a failed window query.
        // The stale set must not be treated as a fresh observation that keeps locking.
        tracker.onAccessibilitySnapshot(
            visiblePackages = setOf(testPkg, "com.bbk.launcher2"),
            nowMs = 12_000L,
            scanSucceeded = false
        )

        assertTrue(tracker.accessibilityRevalidationRequired)
        assertEquals(PresenceTrackerAction.NONE, tracker.evaluate(14_500L))
        assertEquals(PresenceTrackerState.DESKTOP_GRACE, tracker.state)
    }

    @Test
    fun `fresh successful window snapshot keeps a static small window lockable without new lifecycle event`() {
        val tracker = VisibleBlacklistPresenceTracker(desktopGraceDurationMs = 3000L)
        tracker.onPresenceConfirmed(testPkg, 10_000L)
        tracker.onDesktopReturn(11_000L)

        // First make the session uncertain exactly like a failed AccessibilityService.windows query.
        tracker.onAccessibilitySnapshot(
            visiblePackages = setOf(testPkg, "com.bbk.launcher2"),
            nowMs = 11_500L,
            scanSucceeded = false
        )
        assertTrue(tracker.accessibilityRevalidationRequired)

        // No MOVE_TO_FOREGROUND / RESUMED event is needed. A fresh window snapshot is enough.
        tracker.onAccessibilitySnapshot(
            visiblePackages = setOf("com.bbk.launcher2", testPkg),
            nowMs = 12_000L,
            scanSucceeded = true
        )
        CoreService.lastVisiblePackagesFromWindows = setOf("com.bbk.launcher2", testPkg)

        assertFalse(tracker.accessibilityRevalidationRequired)
        assertEquals(PresenceTrackerAction.LOCK, tracker.evaluate(14_500L))
        assertEquals(PresenceTrackerState.CONFIRMED_VISIBLE, tracker.state)
    }

    @Test
    fun `usage stats cannot revive tracked package while accessibility revalidation is pending`() {
        val tracker = VisibleBlacklistPresenceTracker(desktopGraceDurationMs = 3000L)
        tracker.onPresenceConfirmed(testPkg, 10_000L)
        tracker.onDesktopReturn(11_000L)
        tracker.onAccessibilitySnapshot(
            visiblePackages = setOf(testPkg, "com.bbk.launcher2"),
            nowMs = 11_500L,
            scanSucceeded = false
        )

        assertFalse(
            "lastTimeVisible must not bypass a pending accessibility revalidation",
            tracker.isLastTimeVisibleValid(testPkg, 12_000L)
        )

        tracker.onAccessibilitySnapshot(
            visiblePackages = setOf("com.bbk.launcher2", testPkg),
            nowMs = 12_500L,
            scanSucceeded = true
        )
        CoreService.lastVisiblePackagesFromWindows = setOf("com.bbk.launcher2", testPkg)

        assertTrue(tracker.isLastTimeVisibleValid(testPkg, 12_600L))
    }

    @Test
    fun `successful absence after failed scan clears stale presence instead of relocking`() {
        val tracker = VisibleBlacklistPresenceTracker(desktopGraceDurationMs = 3000L)
        tracker.onPresenceConfirmed(testPkg, 10_000L)
        tracker.onDesktopReturn(11_000L)
        tracker.onAccessibilitySnapshot(
            visiblePackages = setOf(testPkg, "com.bbk.launcher2"),
            nowMs = 11_500L,
            scanSucceeded = false
        )

        tracker.onAccessibilitySnapshot(setOf("com.bbk.launcher2"), 12_000L, true)
        tracker.onAccessibilitySnapshot(setOf("com.bbk.launcher2"), 12_500L, true)
        val clearAction = tracker.onAccessibilitySnapshot(
            visiblePackages = setOf("com.bbk.launcher2"),
            nowMs = 13_000L,
            scanSucceeded = true
        )

        assertEquals(PresenceTrackerAction.CLEAR, clearAction)
        assertEquals(PresenceTrackerState.NONE, tracker.state)
        assertEquals(PresenceTrackerAction.NONE, tracker.evaluate(14_500L))
    }

    @Test
    fun `delayed stopped event after a real reopen clears a later usage presence confirmation`() {
        val tracker = VisibleBlacklistPresenceTracker(desktopGraceDurationMs = 3000L)
        val activityResumed = 15
        val activityStopped = 23

        // Real lifecycle evidence says the package opened at 10s.
        tracker.onUsageEvent(testPkg, activityResumed, 10_000L)

        // A UsageStats presence scan runs first in the polling cycle and confirms at wall-clock 12.5s.
        assertEquals(
            PresenceTrackerAction.LOCK,
            tracker.onPresenceConfirmed(testPkg, 12_500L)
        )

        // The same cycle then consumes the real STOPPED event timestamped 12.0s.
        // Even though the event timestamp is slightly earlier than confirmedAtMs, it is newer
        // than the last real reopen and therefore belongs to this lifecycle session.
        val clearAction = tracker.onUsageEvent(testPkg, activityStopped, 12_000L)

        assertEquals(PresenceTrackerAction.CLEAR, clearAction)
        assertEquals(PresenceTrackerState.NONE, tracker.state)
        assertTrue(tracker.isPackageClosed(testPkg))
    }
}
