package com.decikeeper.app.manager

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivacyProtectionUiStateTest {

    @Test
    fun vivoDefaultDisabled_isVisible() {
        val visible = PrivacyProtectionManager.resolvePrivacySpaceUiVisibility(
            isVivoOrIqoo = true,
            protectionState = PrivacyProtectionManager.State.DISABLED,
            isEnforcing = false
        )
        assertTrue("vivo devices must display privacy space guard by default", visible)
    }

    @Test
    fun oppoDefaultDisabled_isHidden() {
        val visible = PrivacyProtectionManager.resolvePrivacySpaceUiVisibility(
            isVivoOrIqoo = false,
            protectionState = PrivacyProtectionManager.State.DISABLED,
            isEnforcing = false
        )
        assertFalse("oppo/non-vivo devices should hide privacy space guard when disabled", visible)
    }

    @Test
    fun nonVivoActive_remainsVisibleForDeactivation() {
        val visible = PrivacyProtectionManager.resolvePrivacySpaceUiVisibility(
            isVivoOrIqoo = false,
            protectionState = PrivacyProtectionManager.State.ACTIVE,
            isEnforcing = true
        )
        assertTrue("any device with active privacy space guard must remain visible to allow deactivation", visible)
    }

    @Test
    fun nonVivoPending_remainsVisible() {
        val visible = PrivacyProtectionManager.resolvePrivacySpaceUiVisibility(
            isVivoOrIqoo = false,
            protectionState = PrivacyProtectionManager.State.PENDING,
            isEnforcing = true
        )
        assertTrue("pending disable state must remain visible", visible)
    }
}
