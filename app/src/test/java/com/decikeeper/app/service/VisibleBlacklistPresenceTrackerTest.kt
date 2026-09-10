package com.decikeeper.app.service

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class VisibleBlacklistPresenceTrackerTest {

    private lateinit var tracker: VisibleBlacklistPresenceTracker
    private val testPkg = "com.tencent.mm"
    private val ACTIVITY_RESUMED = 15
    private val ACTIVITY_PAUSED = 16
    private val ACTIVITY_STOPPED = 23
    private var originalServiceActive = false
    private var originalVisiblePackages: Set<String> = emptySet()

    @Before
    fun setUp() {
        originalServiceActive = CoreService.isServiceActive
        originalVisiblePackages = CoreService.lastVisiblePackagesFromWindows
        CoreService.isServiceActive = true
        CoreService.lastVisiblePackagesFromWindows = setOf(testPkg)
        tracker = VisibleBlacklistPresenceTracker(desktopGraceDurationMs = 3000L)
    }

    @After
    fun tearDown() {
        CoreService.isServiceActive = originalServiceActive
        CoreService.lastVisiblePackagesFromWindows = originalVisiblePackages
    }

    @Test
    fun `test 1 - first presence confirmation transitions to CONFIRMED_VISIBLE and triggers LOCK`() {
        val now = 10000L
        val action = tracker.onPresenceConfirmed(testPkg, now)

        assertEquals(PresenceTrackerAction.LOCK, action)
        assertEquals(PresenceTrackerState.CONFIRMED_VISIBLE, tracker.state)
        assertEquals(testPkg, tracker.currentPackage)
        assertEquals(now, tracker.confirmedAtMs)
    }

    @Test
    fun `test 2 - return to desktop enters grace, then relocks after 3s without close evidence`() {
        val t0 = 10000L
        tracker.onPresenceConfirmed(testPkg, t0)

        val tReturn = 12000L
        tracker.onDesktopReturn(tReturn)
        assertEquals(PresenceTrackerState.DESKTOP_GRACE, tracker.state)
        assertEquals(tReturn + 3000L, tracker.graceUntilMs)

        // During grace period (1 second after return)
        val tDuringGrace = 13000L
        val actionDuringGrace = tracker.evaluate(tDuringGrace)
        assertEquals(PresenceTrackerAction.NONE, actionDuringGrace)
        assertEquals(PresenceTrackerState.DESKTOP_GRACE, tracker.state)

        // Grace expired (3.1 seconds after return) with no closed evidence -> relock
        val tExpired = 15100L
        val actionAfterGrace = tracker.evaluate(tExpired)
        assertEquals(PresenceTrackerAction.LOCK, actionAfterGrace)
        assertEquals(PresenceTrackerState.CONFIRMED_VISIBLE, tracker.state)
    }

    @Test
    fun `test 3 - accessibility window disappearance during grace clears presence session`() {
        val t0 = 10000L
        tracker.onPresenceConfirmed(testPkg, t0)
        tracker.onDesktopReturn(11000L)

        // Windows no longer contain testPkg (requires 3 consecutive complete absences)
        assertEquals(
            PresenceTrackerAction.NONE,
            tracker.onAccessibilitySnapshot(setOf("com.openai.chatgpt"), 12000L)
        )
        assertEquals(PresenceTrackerState.DESKTOP_GRACE, tracker.state)

        assertEquals(
            PresenceTrackerAction.NONE,
            tracker.onAccessibilitySnapshot(setOf("com.openai.chatgpt"), 12500L)
        )
        assertEquals(PresenceTrackerState.DESKTOP_GRACE, tracker.state)

        val action = tracker.onAccessibilitySnapshot(setOf("com.openai.chatgpt"), 13000L)
        assertEquals(PresenceTrackerAction.CLEAR, action)
        assertEquals(PresenceTrackerState.NONE, tracker.state)
        assertEquals(null, tracker.currentPackage)

        // After grace expires, no relock occurs
        val actionAfterGrace = tracker.evaluate(15000L)
        assertEquals(PresenceTrackerAction.NONE, actionAfterGrace)
    }

    @Test
    fun `test 4 - ACTIVITY_STOPPED event during grace clears presence session`() {
        val t0 = 10000L
        tracker.onPresenceConfirmed(testPkg, t0)
        tracker.onDesktopReturn(11000L)

        val action = tracker.onUsageEvent(testPkg, ACTIVITY_STOPPED, 12000L)
        assertEquals(PresenceTrackerAction.CLEAR, action)
        assertEquals(PresenceTrackerState.NONE, tracker.state)
        assertEquals(12000L, tracker.lastStoppedAtMap[testPkg])

        // After grace expires, no relock occurs
        val actionAfterGrace = tracker.evaluate(15000L)
        assertEquals(PresenceTrackerAction.NONE, actionAfterGrace)
    }

    @Test
    fun `test 5 - ACTIVITY_PAUSED does not count as closed`() {
        val t0 = 10000L
        tracker.onPresenceConfirmed(testPkg, t0)
        tracker.onDesktopReturn(11000L)

        val action = tracker.onUsageEvent(testPkg, ACTIVITY_PAUSED, 12000L)
        assertEquals(PresenceTrackerAction.NONE, action)
        assertEquals(PresenceTrackerState.DESKTOP_GRACE, tracker.state)

        // Grace expires -> relocks
        val actionAfterGrace = tracker.evaluate(14500L)
        assertEquals(PresenceTrackerAction.LOCK, actionAfterGrace)
    }

    @Test
    fun `test 6 - residual lastTimeVisible from closing does not recreate presence`() {
        val tStop = 20000L
        tracker.onUsageEvent(testPkg, ACTIVITY_STOPPED, tStop)

        // System flushes lastTimeVisible at the moment of closing (ltv <= lastStoppedAt)
        val residualLtv = 20000L
        assertFalse(
            "lastTimeVisible resulting from STOPPED event must be rejected as residual",
            tracker.isLastTimeVisibleValid(testPkg, residualLtv)
        )
    }

    @Test
    fun `test 7 - STOPPED package remains closed even if UsageStats provides newer lastTimeVisible without reopen event`() {
        val tStop = 20000L
        tracker.onUsageEvent(testPkg, ACTIVITY_STOPPED, tStop)

        // UsageStats flushes newer timestamp (e.g. 25000L), but without MOVE_TO_FOREGROUND or RESUMED
        val newerLtv = 25000L
        assertFalse(
            "lastTimeVisible alone without reopen lifecycle event must be rejected while package is CLOSED",
            tracker.isLastTimeVisibleValid(testPkg, newerLtv)
        )
    }

    @Test
    fun `test 8 - entering IronWill with active small window still locked`() {
        val t0 = 10000L
        tracker.onPresenceConfirmed(testPkg, t0)
        tracker.onDesktopReturn(11000L)

        // Grace expired at 14000L with no closed evidence
        val tEnterIronWill = 15000L
        val action = tracker.evaluate(tEnterIronWill)
        assertEquals(PresenceTrackerAction.LOCK, action)
        assertEquals(PresenceTrackerState.CONFIRMED_VISIBLE, tracker.state)
    }

    @Test
    fun `test 9 - entering IronWill after closed small window results in no lock`() {
        val t0 = 10000L
        tracker.onPresenceConfirmed(testPkg, t0)
        tracker.onDesktopReturn(11000L)

        // Closed via STOPPED at 12000L
        tracker.onUsageEvent(testPkg, ACTIVITY_STOPPED, 12000L)
        assertEquals(PresenceTrackerState.NONE, tracker.state)

        // Entering IronWill at 15000L
        val action = tracker.evaluate(15000L)
        assertEquals(PresenceTrackerAction.NONE, action)
    }

    @Test
    fun `test 10 - historical STOPPED event before confirmedAtMs must not clear new presence session`() {
        val tOldStopped = 5000L
        tracker.onUsageEvent(testPkg, ACTIVITY_STOPPED, tOldStopped)

        val tNewConfirmed = 10000L
        tracker.onPresenceConfirmed(testPkg, tNewConfirmed)
        assertEquals(PresenceTrackerState.CONFIRMED_VISIBLE, tracker.state)

        // Querying UsageEvents returns the old STOPPED event from 30s ago (tOldStopped < tNewConfirmed)
        val action = tracker.onUsageEvent(testPkg, ACTIVITY_STOPPED, tOldStopped)
        assertEquals(PresenceTrackerAction.NONE, action)
        assertEquals(PresenceTrackerState.CONFIRMED_VISIBLE, tracker.state)
        assertEquals(testPkg, tracker.currentPackage)
    }

    @Test
    fun `test 11 - establish presence session on foreground lock then grace relocks after 3s without stopped`() {
        val t0 = 10000L
        // Foreground lock occurs and establishes presence session
        tracker.onPresenceConfirmed(testPkg, t0)

        // User clicks Return Desktop
        val tReturn = 12000L
        tracker.onDesktopReturn(tReturn)
        assertEquals(PresenceTrackerState.DESKTOP_GRACE, tracker.state)

        // 3.1 seconds after return, no STOPPED received
        val tExpired = 15100L
        val action = tracker.evaluate(tExpired)
        assertEquals(PresenceTrackerAction.LOCK, action)
        assertEquals(PresenceTrackerState.CONFIRMED_VISIBLE, tracker.state)
    }

    @Test
    fun `test 12 - presence evaluates and triggers LOCK even if foreground is null`() {
        val t0 = 10000L
        tracker.onPresenceConfirmed(testPkg, t0)
        tracker.onDesktopReturn(11000L)

        // Grace expired at 14500L
        val tExpired = 14500L
        val action = tracker.evaluate(tExpired)
        assertEquals(PresenceTrackerAction.LOCK, action)
    }

    @Test
    fun `test 13 - STOPPED puts package into CLOSED and newer lastTimeVisible cannot revive it without reopen event`() {
        val tStop = 10000L
        tracker.onUsageEvent(testPkg, ACTIVITY_STOPPED, tStop)
        assertTrue("Package must be marked CLOSED after STOPPED", tracker.isPackageClosed(testPkg))

        // System updates lastTimeVisible after STOPPED
        val tLtv = 10500L
        assertFalse("lastTimeVisible must be rejected when package is CLOSED", tracker.isLastTimeVisibleValid(testPkg, tLtv))
    }

    @Test
    fun `test 14 - MOVE_TO_FOREGROUND reopens CLOSED package allowing lastTimeVisible`() {
        val tStop = 10000L
        tracker.onUsageEvent(testPkg, ACTIVITY_STOPPED, tStop)
        assertTrue(tracker.isPackageClosed(testPkg))

        // User genuinely reopens via MOVE_TO_FOREGROUND (event type 1)
        val MOVE_TO_FG = 1
        val tReopen = 20000L
        tracker.onUsageEvent(testPkg, MOVE_TO_FG, tReopen)
        assertFalse("Package must not be CLOSED after MOVE_TO_FOREGROUND", tracker.isPackageClosed(testPkg))

        // Now lastTimeVisible is valid because Accessibility also sees the target window.
        assertTrue(tracker.isLastTimeVisibleValid(testPkg, 20500L))
    }

    @Test
    fun `test 15 - ACTIVITY_RESUMED reopens CLOSED package allowing lastTimeVisible`() {
        val tStop = 10000L
        tracker.onUsageEvent(testPkg, ACTIVITY_STOPPED, tStop)
        assertTrue(tracker.isPackageClosed(testPkg))

        // User genuinely reopens via ACTIVITY_RESUMED (event type 15)
        val tReopen = 20000L
        tracker.onUsageEvent(testPkg, ACTIVITY_RESUMED, tReopen)
        assertFalse("Package must not be CLOSED after ACTIVITY_RESUMED", tracker.isPackageClosed(testPkg))

        assertTrue(tracker.isLastTimeVisibleValid(testPkg, 20500L))
    }

    @Test
    fun `test 16 - MOVE_TO_BACKGROUND does not close package`() {
        val MOVE_TO_BG = 2
        val t0 = 10000L
        tracker.onPresenceConfirmed(testPkg, t0)

        // Small window moves to background but is still visible on screen
        val action = tracker.onUsageEvent(testPkg, MOVE_TO_BG, 11000L)
        assertEquals(PresenceTrackerAction.NONE, action)
        assertFalse("MOVE_TO_BACKGROUND must not mark package as CLOSED", tracker.isPackageClosed(testPkg))
        assertEquals(PresenceTrackerState.CONFIRMED_VISIBLE, tracker.state)
    }

    @Test
    fun `test 17 - ACTIVITY_PAUSED does not close package`() {
        val t0 = 10000L
        tracker.onPresenceConfirmed(testPkg, t0)
        tracker.onDesktopReturn(11000L)

        val action = tracker.onUsageEvent(testPkg, ACTIVITY_PAUSED, 12000L)
        assertEquals(PresenceTrackerAction.NONE, action)
        assertFalse("ACTIVITY_PAUSED must not mark package as CLOSED", tracker.isPackageClosed(testPkg))
        assertEquals(PresenceTrackerState.DESKTOP_GRACE, tracker.state)

        // Grace expires -> relocks
        val actionAfterGrace = tracker.evaluate(14500L)
        assertEquals(PresenceTrackerAction.LOCK, actionAfterGrace)
    }

    @Test
    fun `test 18 - after STOPPED, no relock occurs at 3s, 6s, 10s`() {
        val t0 = 10000L
        tracker.onPresenceConfirmed(testPkg, t0)
        tracker.onDesktopReturn(11000L)

        // Closed at 12000L
        tracker.onUsageEvent(testPkg, ACTIVITY_STOPPED, 12000L)
        assertEquals(PresenceTrackerState.NONE, tracker.state)
        assertTrue(tracker.isPackageClosed(testPkg))

        // Subsequent evaluations at 3s, 6s, 10s after return
        assertEquals(PresenceTrackerAction.NONE, tracker.evaluate(14500L))
        assertEquals(PresenceTrackerAction.NONE, tracker.evaluate(17000L))
        assertEquals(PresenceTrackerAction.NONE, tracker.evaluate(21000L))
    }

    @Test
    fun `test 19 - consecutive UsageStats refreshes after STOPPED are all rejected until MOVE_TO_FOREGROUND`() {
        val tStop = 10000L
        tracker.onUsageEvent(testPkg, ACTIVITY_STOPPED, tStop)

        // Multiple UsageStats polling ticks after close
        assertFalse(tracker.isLastTimeVisibleValid(testPkg, 10100L))
        assertFalse(tracker.isLastTimeVisibleValid(testPkg, 10200L))
        assertFalse(tracker.isLastTimeVisibleValid(testPkg, 11000L))

        // Finally reopened; Accessibility is already confirming the target window in this fixture.
        val MOVE_TO_FG = 1
        tracker.onUsageEvent(testPkg, MOVE_TO_FG, 12000L)
        assertTrue(tracker.isLastTimeVisibleValid(testPkg, 12100L))
    }

    @Test
    fun `test 20 - Accessibility window disappearance sets package to CLOSED tombstone`() {
        val t0 = 10000L
        tracker.onPresenceConfirmed(testPkg, t0)
        tracker.onDesktopReturn(11000L)

        // Window tree no longer contains testPkg (requires 3 consecutive complete absences)
        tracker.onAccessibilitySnapshot(setOf("com.android.launcher"), 12000L)
        tracker.onAccessibilitySnapshot(setOf("com.android.launcher"), 12500L)
        val action = tracker.onAccessibilitySnapshot(setOf("com.android.launcher"), 13000L)
        assertEquals(PresenceTrackerAction.CLEAR, action)
        assertEquals(PresenceTrackerState.NONE, tracker.state)
        assertTrue("Package must be CLOSED after accessibility window disappearance", tracker.isPackageClosed(testPkg))
        assertNull("Accessibility CLEAR must NOT write lastStoppedAtMap", tracker.lastStoppedAtMap[testPkg])

        // Subsequent UsageStats cannot revive it because closedAt is active
        assertFalse(tracker.isLastTimeVisibleValid(testPkg, 13000L))
    }

    @Test
    fun `test 20b - transient absences followed by present resets absence count without clearing`() {
        val t0 = 10000L
        tracker.onPresenceConfirmed(testPkg, t0)
        tracker.onDesktopReturn(11000L)

        // 1st absence
        assertEquals(PresenceTrackerAction.NONE, tracker.onAccessibilitySnapshot(setOf("com.android.launcher"), 12000L))
        assertEquals(1, tracker.consecutiveConfirmedAbsenceCount)

        // 2nd absence
        assertEquals(PresenceTrackerAction.NONE, tracker.onAccessibilitySnapshot(setOf("com.android.launcher"), 12500L))
        assertEquals(2, tracker.consecutiveConfirmedAbsenceCount)

        // Present arrives! Resets absence count to 0
        assertEquals(PresenceTrackerAction.NONE, tracker.onAccessibilitySnapshot(setOf(testPkg, "com.android.launcher"), 13000L))
        assertEquals(0, tracker.consecutiveConfirmedAbsenceCount)
        assertEquals(PresenceTrackerState.DESKTOP_GRACE, tracker.state)

        // Another single absence: only count 1, does NOT clear
        assertEquals(PresenceTrackerAction.NONE, tracker.onAccessibilitySnapshot(setOf("com.android.launcher"), 13500L))
        assertEquals(1, tracker.consecutiveConfirmedAbsenceCount)
        assertEquals(PresenceTrackerState.DESKTOP_GRACE, tracker.state)
    }

    @Test
    fun `test 20c - unconfirmed partial or failed scan does not increment absence count`() {
        val t0 = 10000L
        tracker.onPresenceConfirmed(testPkg, t0)
        tracker.onDesktopReturn(11000L)

        // 1st absence
        tracker.onAccessibilitySnapshot(setOf("com.android.launcher"), 12000L, scanSucceeded = true)
        assertEquals(1, tracker.consecutiveConfirmedAbsenceCount)

        // Failed scan
        tracker.onAccessibilitySnapshot(setOf("com.android.launcher"), 12500L, scanSucceeded = false)
        assertEquals(1, tracker.consecutiveConfirmedAbsenceCount)

        // 2nd absence
        tracker.onAccessibilitySnapshot(setOf("com.android.launcher"), 13000L, scanSucceeded = true)
        assertEquals(2, tracker.consecutiveConfirmedAbsenceCount)
        assertEquals(PresenceTrackerState.DESKTOP_GRACE, tracker.state)
    }

    @Test
    fun `test 21 - without accessibility UsageEvents cannot establish small-window presence`() {
        CoreService.isServiceActive = false
        CoreService.lastVisiblePackagesFromWindows = emptySet()
        val MOVE_TO_FG = 1

        tracker.onUsageEvent(testPkg, MOVE_TO_FG, 5000L)
        assertFalse(tracker.isLastTimeVisibleValid(testPkg, 5100L))

        // Even if a stale Presence session exists from before Accessibility disappeared,
        // return-to-desktop grace expiry must not relock from UsageStats alone.
        tracker.onPresenceConfirmed(testPkg, 6000L)
        tracker.onDesktopReturn(7000L)
        assertEquals(PresenceTrackerAction.NONE, tracker.evaluate(10500L))
    }
}
