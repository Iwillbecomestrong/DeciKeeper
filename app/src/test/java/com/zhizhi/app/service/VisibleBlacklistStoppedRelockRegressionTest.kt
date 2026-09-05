package com.zhizhi.app.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VisibleBlacklistStoppedRelockRegressionTest {

    private val testPkg = "com.github.metacubex.clash.meta"
    private val ACTIVITY_STOPPED = 23

    @Test
    fun `recent accessibility close event must not suppress stopped clear and allow relock`() {
        val tracker = VisibleBlacklistPresenceTracker(desktopGraceDurationMs = 3000L)
        val confirmedAt = 10_000L
        val desktopAt = 11_000L
        val closingA11yAt = 11_900L
        val stoppedAt = 12_000L

        tracker.onPresenceConfirmed(testPkg, confirmedAt)
        tracker.onDesktopReturn(desktopAt)

        // Real-device regression: closing the floating window emits a final Accessibility event
        // immediately before UsageEvents reports ACTIVITY_STOPPED. The close event is evidence of
        // recent activity, not evidence that the window is still visible.
        CoreService.recordDiagnosticsEligibleEvent(
            packageName = testPkg,
            eventType = "TYPE_WINDOW_STATE_CHANGED",
            atMs = closingA11yAt
        )

        val stopAction = tracker.onUsageEvent(testPkg, ACTIVITY_STOPPED, stoppedAt)

        assertEquals(PresenceTrackerAction.CLEAR, stopAction)
        assertEquals(PresenceTrackerState.NONE, tracker.state)
        assertTrue(tracker.isPackageClosed(testPkg))
        assertEquals(stoppedAt, tracker.lastStoppedAtMap[testPkg])

        // Once STOPPED is accepted, the desktop grace expiry must never revive the closed app.
        assertEquals(PresenceTrackerAction.NONE, tracker.evaluate(15_100L))
        assertEquals(PresenceTrackerAction.NONE, tracker.evaluate(18_000L))
        assertEquals(PresenceTrackerAction.NONE, tracker.evaluate(22_000L))
    }
}
