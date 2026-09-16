package com.decikeeper.app.manager

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AntiUninstallDeviceAdminUiStateTest {

    @Test
    fun vivoDefaultDisabledAndInactive_isHidden() {
        val state = AntiUninstallManager.resolveDeviceAdminUiState(
            isSupportedBrand = false,
            protectionState = AntiUninstallManager.State.DISABLED,
            isSystemAdminActive = false
        )

        assertFalse(state.visible)
        assertFalse(state.checked)
        assertEquals(AntiUninstallManager.DeviceAdminUiStatus.HIDDEN, state.status)
    }

    @Test
    fun vivoAlreadyActivated_isVisibleWithExitState() {
        val state = AntiUninstallManager.resolveDeviceAdminUiState(
            isSupportedBrand = false,
            protectionState = AntiUninstallManager.State.DISABLED,
            isSystemAdminActive = true
        )

        assertTrue(state.visible)
        assertFalse(state.checked)
        assertEquals(AntiUninstallManager.DeviceAdminUiStatus.NEEDS_DEACTIVATION, state.status)
    }

    @Test
    fun oppoDefaultDisabledAndInactive_isVisible() {
        val state = AntiUninstallManager.resolveDeviceAdminUiState(
            isSupportedBrand = true,
            protectionState = AntiUninstallManager.State.DISABLED,
            isSystemAdminActive = false
        )

        assertTrue(state.visible)
        assertFalse(state.checked)
        assertEquals(AntiUninstallManager.DeviceAdminUiStatus.DISABLED, state.status)
    }

    @Test
    fun pendingAndActiveAdmin_remainsCheckedAndProtected() {
        val state = AntiUninstallManager.resolveDeviceAdminUiState(
            isSupportedBrand = true,
            protectionState = AntiUninstallManager.State.PENDING,
            isSystemAdminActive = true
        )

        assertTrue(state.visible)
        assertTrue(state.checked)
        assertEquals(AntiUninstallManager.DeviceAdminUiStatus.PENDING, state.status)
    }

    @Test
    fun enabledWithoutSystemActivation_isNotReportedAsActive() {
        val state = AntiUninstallManager.resolveDeviceAdminUiState(
            isSupportedBrand = true,
            protectionState = AntiUninstallManager.State.ACTIVE,
            isSystemAdminActive = false
        )

        assertTrue(state.visible)
        assertFalse(state.checked)
        assertEquals(AntiUninstallManager.DeviceAdminUiStatus.NEEDS_ACTIVATION, state.status)
    }

    @Test
    fun vivoWithOtherProtectionActiveButDeviceAdminInactive_mustBeHidden() {
        // 核心回归防护：用户开启了「防卸载与防关权限保护」（protectionState == ACTIVE），
        // 但在 vivo/iQOO 等未适配机型上，若系统设备管理器未激活，设备管理器行绝不能被误触发显示！
        val state = AntiUninstallManager.resolveDeviceAdminUiState(
            isSupportedBrand = false,
            protectionState = AntiUninstallManager.State.ACTIVE,
            isSystemAdminActive = false
        )

        assertFalse("vivo 开启其他保护时，设备管理器未激活必须坚决隐藏", state.visible)
        assertFalse(state.checked)
        assertEquals(AntiUninstallManager.DeviceAdminUiStatus.HIDDEN, state.status)
    }
}
