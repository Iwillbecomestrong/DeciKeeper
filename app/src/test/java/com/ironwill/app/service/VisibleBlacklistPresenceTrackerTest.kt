package com.ironwill.app.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class VisibleBlacklistPresenceTrackerTest {

    private lateinit var tracker: VisibleBlacklistPresenceTracker
    private val testPkg = "com.tencent.mm"
    private val ACTIVITY_RESUMED = 15
    private val ACTIVITY_PAUSED = 16
    private val ACTIVITY_STOPPED = 23

    @Before
    fun setUp() {
        tracker = VisibleBlacklistPresenceTracker(desktopGraceDurationMs = 3000L)
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

        // Windows no longer contain testPkg
        val action = tracker.onAccessibilitySnapshot(setOf("com.openai.chatgpt"), 12000L)
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
    fun `test 7 - real reopen with newer lastTimeVisible allows presence detection`() {
        val tStop = 20000L
        tracker.onUsageEvent(testPkg, ACTIVITY_STOPPED, tStop)

        // User genuinely reopens the app later (ltv > lastStoppedAt)
        val newLtv = 25000L
        assertTrue(
            "Newer lastTimeVisible after stopped timestamp must be accepted",
            tracker.isLastTimeVisibleValid(testPkg, newLtv)
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
}
