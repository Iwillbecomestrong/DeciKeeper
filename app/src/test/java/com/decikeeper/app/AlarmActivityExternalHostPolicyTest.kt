package com.decikeeper.app

import com.decikeeper.app.ui.shouldFinishExternalAlarmHost
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AlarmActivityExternalHostPolicyTest {
    @Test
    fun external_overlay_host_finishes_only_when_no_scan_is_in_progress() {
        assertTrue(
            shouldFinishExternalAlarmHost(
                isScanMode = false,
                isLockedScreenMode = false,
                isLaunchingScanner = false
            )
        )
        assertFalse(
            shouldFinishExternalAlarmHost(
                isScanMode = true,
                isLockedScreenMode = false,
                isLaunchingScanner = false
            )
        )
        assertFalse(
            shouldFinishExternalAlarmHost(
                isScanMode = false,
                isLockedScreenMode = true,
                isLaunchingScanner = false
            )
        )
        assertFalse(
            shouldFinishExternalAlarmHost(
                isScanMode = false,
                isLockedScreenMode = false,
                isLaunchingScanner = true
            )
        )
    }
}
