package com.decikeeper.app.protection

import android.content.Context
import com.decikeeper.app.manager.AccessibilityControlManager
import com.decikeeper.app.manager.AccessibilityDependencyManager
import com.decikeeper.app.manager.AlarmPowerOffProtectionManager
import com.decikeeper.app.manager.AntiUninstallManager
import com.decikeeper.app.manager.PrivacyProtectionManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AccessibilityDependencyManagerTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("accessibility_control_prefs", Context.MODE_PRIVATE)
            .edit().clear().commit()
        context.getSharedPreferences("anti_uninstall_prefs", Context.MODE_PRIVATE)
            .edit().clear().commit()
        context.getSharedPreferences(PrivacyProtectionManager.PREFS_NAME, Context.MODE_PRIVATE)
            .edit().clear().commit()
        context.getSharedPreferences("alarm_power_off_protection_prefs", Context.MODE_PRIVATE)
            .edit().clear().commit()
        context.getSharedPreferences("pending_config_prefs", Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @Test
    fun systemAccessibilityRevocationClearsAllDependentEnhancements() {
        AccessibilityControlManager.setEnabled(context, true)
        AntiUninstallManager.setProtectionEnabled(context, true)
        PrivacyProtectionManager.setProtectionEnabled(context, true)
        com.decikeeper.app.manager.GuardFloatingWindowManager.setFloatingGuardEnabled(context, true)
        AlarmPowerOffProtectionManager.setEnabled(context, true)

        AccessibilityDependencyManager.reconcile(context, systemAccessibilityEnabled = false)

        assertEquals(AccessibilityControlManager.State.DISABLED, AccessibilityControlManager.getState(context))
        assertEquals(AntiUninstallManager.State.DISABLED, AntiUninstallManager.getProtectionState(context))
        assertEquals(PrivacyProtectionManager.State.DISABLED, PrivacyProtectionManager.getProtectionState(context))
        assertEquals(com.decikeeper.app.manager.GuardFloatingWindowManager.State.DISABLED, com.decikeeper.app.manager.GuardFloatingWindowManager.getProtectionState(context))
        assertFalse(AlarmPowerOffProtectionManager.isEnabled(context))
    }

    @Test
    fun enablingSystemAccessibilityDoesNotRestoreOldEnhancements() {
        AccessibilityControlManager.setEnabled(context, true)
        AntiUninstallManager.setProtectionEnabled(context, true)
        AccessibilityDependencyManager.reconcile(context, systemAccessibilityEnabled = false)

        AccessibilityDependencyManager.reconcile(context, systemAccessibilityEnabled = true)

        assertEquals(AccessibilityControlManager.State.DISABLED, AccessibilityControlManager.getState(context))
        assertEquals(AntiUninstallManager.State.DISABLED, AntiUninstallManager.getProtectionState(context))
    }
}
