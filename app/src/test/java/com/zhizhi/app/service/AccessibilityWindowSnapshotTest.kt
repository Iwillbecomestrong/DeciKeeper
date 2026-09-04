package com.zhizhi.app.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AccessibilityWindowSnapshotTest {

    private lateinit var tracker: VisibleBlacklistPresenceTracker
    private val testPkg = "com.ss.android.ugc.aweme"

    @Before
    fun setUp() {
        tracker = VisibleBlacklistPresenceTracker(desktopGraceDurationMs = 3000L, requiredConsecutiveAbsenceCount = 3)
    }

    @Test
    fun `FAILED snapshot does not count as presence or absence`() {
        val t0 = 10_000L
        tracker.onPresenceConfirmed(testPkg, t0)
        tracker.onDesktopReturn(11_000L)

        val failedSnapshot = AccessibilityWindowSnapshot(
            capturedAtMs = 12_000L,
            status = AccessibilityWindowSnapshotStatus.FAILED,
            packages = emptySet(),
            unresolvedWindowCount = 0,
            totalWindowCount = 0,
            failureClass = "WindowsNull"
        )

        val action = tracker.onAccessibilitySnapshot(failedSnapshot, 12_000L)
        assertEquals(PresenceTrackerAction.NONE, action)
        assertEquals(0, tracker.consecutiveConfirmedAbsenceCount)
        assertEquals(PresenceTrackerState.DESKTOP_GRACE, tracker.state)
        assertTrue(tracker.accessibilityRevalidationRequired)
    }

    @Test
    fun `PARTIAL snapshot does not count as absence`() {
        val t0 = 10_000L
        tracker.onPresenceConfirmed(testPkg, t0)
        tracker.onDesktopReturn(11_000L)

        val partialSnapshot = AccessibilityWindowSnapshot(
            capturedAtMs = 12_000L,
            status = AccessibilityWindowSnapshotStatus.PARTIAL,
            packages = setOf("com.android.systemui"),
            unresolvedWindowCount = 1,
            totalWindowCount = 2,
            failureClass = null
        )

        val action = tracker.onAccessibilitySnapshot(partialSnapshot, 12_000L)
        assertEquals(PresenceTrackerAction.NONE, action)
        assertEquals(0, tracker.consecutiveConfirmedAbsenceCount)
        assertEquals(PresenceTrackerState.DESKTOP_GRACE, tracker.state)
        assertTrue(tracker.accessibilityRevalidationRequired)
    }

    @Test
    fun `COMPLETE snapshot with target confirms PRESENT and resets absence count`() {
        val t0 = 10_000L
        tracker.onPresenceConfirmed(testPkg, t0)
        tracker.onDesktopReturn(11_000L)

        // 1 absence
        val absentSnapshot = AccessibilityWindowSnapshot(
            capturedAtMs = 12_000L,
            status = AccessibilityWindowSnapshotStatus.COMPLETE,
            packages = setOf("com.bbk.launcher2", "com.android.systemui"),
            unresolvedWindowCount = 0,
            totalWindowCount = 2,
            failureClass = null
        )
        tracker.onAccessibilitySnapshot(absentSnapshot, 12_000L)
        assertEquals(1, tracker.consecutiveConfirmedAbsenceCount)

        // Present snapshot
        val presentSnapshot = AccessibilityWindowSnapshot(
            capturedAtMs = 12_500L,
            status = AccessibilityWindowSnapshotStatus.COMPLETE,
            packages = setOf(testPkg, "com.bbk.launcher2", "com.android.systemui"),
            unresolvedWindowCount = 0,
            totalWindowCount = 3,
            failureClass = null
        )
        val action = tracker.onAccessibilitySnapshot(presentSnapshot, 12_500L)
        assertEquals(PresenceTrackerAction.NONE, action)
        assertEquals(0, tracker.consecutiveConfirmedAbsenceCount)
        assertFalse(tracker.accessibilityRevalidationRequired)
    }

    @Test
    fun `COMPLETE snapshot without target requires 3 counts to CLEAR and never touches lastStoppedAtMap`() {
        val t0 = 10_000L
        tracker.onPresenceConfirmed(testPkg, t0)
        tracker.onDesktopReturn(11_000L)

        val absentSnapshot = AccessibilityWindowSnapshot(
            capturedAtMs = 12_000L,
            status = AccessibilityWindowSnapshotStatus.COMPLETE,
            packages = setOf("com.bbk.launcher2", "com.android.systemui"),
            unresolvedWindowCount = 0,
            totalWindowCount = 2,
            failureClass = null
        )

        // Count 1
        assertEquals(PresenceTrackerAction.NONE, tracker.onAccessibilitySnapshot(absentSnapshot, 12_000L))
        assertEquals(1, tracker.consecutiveConfirmedAbsenceCount)
        assertEquals(PresenceTrackerState.DESKTOP_GRACE, tracker.state)

        // Count 2
        assertEquals(PresenceTrackerAction.NONE, tracker.onAccessibilitySnapshot(absentSnapshot, 12_500L))
        assertEquals(2, tracker.consecutiveConfirmedAbsenceCount)
        assertEquals(PresenceTrackerState.DESKTOP_GRACE, tracker.state)

        // Count 3 -> CLEAR
        val action = tracker.onAccessibilitySnapshot(absentSnapshot, 13_000L)
        assertEquals(PresenceTrackerAction.CLEAR, action)
        assertEquals(PresenceTrackerState.NONE, tracker.state)
        assertNull(tracker.currentPackage)
        assertTrue(tracker.isPackageClosed(testPkg))
        assertNull("A11y CLEAR must NEVER forge lastStoppedAt", tracker.lastStoppedAtMap[testPkg])
    }
}
