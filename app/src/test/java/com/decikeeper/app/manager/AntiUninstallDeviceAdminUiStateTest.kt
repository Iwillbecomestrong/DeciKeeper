package com.decikeeper.app.manager

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AntiUninstallDeviceAdminUiStateTest {

    @Test
    fun vivoDefaultDisabledAndInactive_isHidden() {
        val state = AntiUninstallManager.resolveDeviceAdminUiState(
            isVivoOrIqoo = true,
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
            isVivoOrIqoo = true,
            protectionState = AntiUninstallManager.State.DISABLED,
            isSystemAdminActive = true
        )

        assertTrue(state.visible)
        assertFalse(state.checked)
        assertEquals(AntiUninstallManager.DeviceAdminUiStatus.NEEDS_DEACTIVATION, state.status)
    }

    @Test
    fun pendingAndActiveAdmin_remainsCheckedAndProtected() {
        val state = AntiUninstallManager.resolveDeviceAdminUiState(
            isVivoOrIqoo = false,
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
            isVivoOrIqoo = false,
            protectionState = AntiUninstallManager.State.ACTIVE,
            isSystemAdminActive = false
        )

        assertTrue(state.visible)
        assertFalse(state.checked)
        assertEquals(AntiUninstallManager.DeviceAdminUiStatus.NEEDS_ACTIVATION, state.status)
    }
}
