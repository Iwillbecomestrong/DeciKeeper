package com.decikeeper.app.protection

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtectionChainDispatchPolicyTest {

    @Test
    fun `protection chain must not be blocked during numeric typing for settings package`() {
        val allowSettings = CoreServiceProtectionDispatchPolicy.shouldDispatchProtectionEvent(
            isInputPanelOpen = true,
            packageName = "com.android.settings"
        )
        assertTrue("Settings events must always reach ProtectionManager even when typing", allowSettings)
    }

    @Test
    fun `protection chain must dispatch for package installer during typing`() {
        val allowInstaller = CoreServiceProtectionDispatchPolicy.shouldDispatchProtectionEvent(
            isInputPanelOpen = true,
            packageName = "com.google.android.packageinstaller"
        )
        assertTrue("Installer events must always reach ProtectionManager even when typing", allowInstaller)
    }

    @Test
    fun `app blocking can be paused during typing to keep keypad smooth`() {
        val shouldBlockApps = CoreServiceProtectionDispatchPolicy.shouldExecuteAppBlocking(
            isInputPanelOpen = true
        )
        assertFalse("Heavy app blocking scan should pause while typing", shouldBlockApps)

        val shouldBlockWhenIdle = CoreServiceProtectionDispatchPolicy.shouldExecuteAppBlocking(
            isInputPanelOpen = false
        )
        assertTrue("App blocking should proceed normally when panel is closed", shouldBlockWhenIdle)
    }

    @Test
    fun `health evaluation distinguishes between raw signal and dispatched protection`() {
        // 当底层原始信号虽然刷新，但保护链被长时间阻塞时，不能判定为完全健康
        val healthy = CoreServiceProtectionDispatchPolicy.isAccessibilityPipelineHealthy(
            isSystemEnabled = true,
            isServiceConnected = true,
            hasRecentRawSignal = true,
            isPipelineStalled = false
        )
        assertTrue(healthy)

        val stalled = CoreServiceProtectionDispatchPolicy.isAccessibilityPipelineHealthy(
            isSystemEnabled = true,
            isServiceConnected = true,
            hasRecentRawSignal = true,
            isPipelineStalled = true
        )
        assertFalse("Stalled pipeline must never be reported as healthy", stalled)
    }
}
