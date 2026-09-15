package com.decikeeper.app

import android.content.Context
import com.decikeeper.app.manager.AccessibilityControlManager
import com.decikeeper.app.protection.PermissionProtection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PermissionProtectionCoverageTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
    }

    @Test
    fun permissionKeywords_containsAutostartAndAssociatedStartAndOverlay() {
        val keywords = PermissionProtection.PERMISSION_KEYWORDS

        // SC (Simplified Chinese)
        assertTrue("应包含自启动", keywords.contains("自启动"))
        assertTrue("应包含自动启动", keywords.contains("自动启动"))
        assertTrue("应包含后台自启", keywords.contains("后台自启"))
        assertTrue("应包含关联启动", keywords.contains("关联启动"))
        assertTrue("应包含应用关联启动", keywords.contains("应用关联启动"))
        assertTrue("应包含悬浮窗", keywords.contains("悬浮窗"))
        assertTrue("应包含悬浮窗权限", keywords.contains("悬浮窗权限"))
        assertTrue("应包含后台弹窗", keywords.contains("后台弹窗"))
        assertTrue("应包含累计后台弹窗", keywords.contains("累计后台弹窗"))
        assertTrue("应包含管控", keywords.contains("管控"))

        // TC (Traditional Chinese)
        assertTrue("应包含自動啟動", keywords.contains("自動啟動"))
        assertTrue("应包含關聯啟動", keywords.contains("關聯啟動"))
        assertTrue("应包含懸浮視窗", keywords.contains("懸浮視窗"))

        // EN (English)
        assertTrue("应包含Autostart", keywords.contains("Autostart"))
        assertTrue("应包含Auto-start", keywords.contains("Auto-start"))
        assertTrue("应包含Associated start", keywords.contains("Associated start"))
        assertTrue("应包含Display over other apps", keywords.contains("Display over other apps"))
    }

    @Test
    fun protectionStateGate_activeAndPendingMustEnforceProtection() {
        // 1. ACTIVE 状态
        AccessibilityControlManager.setEnabled(context, true)
        val activeState = AccessibilityControlManager.getState(context)
        assertEquals("ACTIVE 状态下应为 ACTIVE", AccessibilityControlManager.State.ACTIVE, activeState)

        // 2. 模拟 PENDING 状态 (通过 ConfigPendingManager 写入次日生效的关闭申请)
        com.decikeeper.app.manager.ConfigPendingManager.submitChange(
            context = context,
            key = "accessibility_pending_disable",
            value = "false",
            strictNextDay = true
        )
        val pendingState = AccessibilityControlManager.getState(context)
        assertEquals("PENDING 状态下应为 PENDING", AccessibilityControlManager.State.PENDING, pendingState)

        // 验证 ACTIVE 与 PENDING 均属于需要保护的状态
        assertTrue(
            activeState == AccessibilityControlManager.State.ACTIVE ||
            activeState == AccessibilityControlManager.State.PENDING
        )
        assertTrue(
            pendingState == AccessibilityControlManager.State.ACTIVE ||
            pendingState == AccessibilityControlManager.State.PENDING
        )
    }

    @Test
    fun protectionStateGate_disabledMustNotEnforce() {
        AccessibilityControlManager.disableImmediatelyForAccessibilityUnavailable(context)
        val state = AccessibilityControlManager.getState(context)
        assertEquals("DISABLED", AccessibilityControlManager.State.DISABLED, state)
        assertFalse(
            state == AccessibilityControlManager.State.ACTIVE ||
            state == AccessibilityControlManager.State.PENDING
        )
    }
}
