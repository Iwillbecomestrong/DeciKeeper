package com.ironwill.app.service

enum class PresenceTrackerState {
    NONE,
    CONFIRMED_VISIBLE,
    DESKTOP_GRACE
}

enum class PresenceTrackerAction {
    NONE,
    LOCK,
    CLEAR
}

class VisibleBlacklistPresenceTracker(
    val desktopGraceDurationMs: Long = 3000L
) {
    var state: PresenceTrackerState = PresenceTrackerState.NONE
        private set

    var currentPackage: String? = null
        private set

    var confirmedAtMs: Long = 0L
        private set

    var graceUntilMs: Long = 0L
        private set

    val lastStoppedAtMap = mutableMapOf<String, Long>()

    // TDD Red Phase: Stub implementations returning default
    fun onPresenceConfirmed(pkg: String, nowMs: Long): PresenceTrackerAction {
        return PresenceTrackerAction.NONE
    }

    fun onDesktopReturn(nowMs: Long) {
    }

    fun onAccessibilitySnapshot(visiblePackages: Set<String>, nowMs: Long): PresenceTrackerAction {
        return PresenceTrackerAction.NONE
    }

    fun onUsageEvent(pkg: String, eventType: Int, eventTimeMs: Long): PresenceTrackerAction {
        return PresenceTrackerAction.NONE
    }

    fun isLastTimeVisibleValid(pkg: String, lastTimeVisible: Long): Boolean {
        return true
    }

    fun evaluate(nowMs: Long): PresenceTrackerAction {
        return PresenceTrackerAction.NONE
    }

    fun reset() {
        state = PresenceTrackerState.NONE
        currentPackage = null
        confirmedAtMs = 0L
        graceUntilMs = 0L
    }
}
