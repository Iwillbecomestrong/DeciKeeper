package com.decikeeper.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class TimeRulesInformationArchitectureContractTest {
    private fun source(path: String): String {
        val cwd = File(System.getProperty("user.dir"))
        val candidates = listOf(
            File(cwd, path),
            File(cwd, "app/$path"),
            File(cwd.parentFile ?: cwd, path),
            File(cwd.parentFile ?: cwd, "app/$path")
        )
        return candidates.firstOrNull { it.isFile }?.readText()
            ?: error("Cannot locate $path from ${cwd.absolutePath}")
    }

    @Test
    fun bottomNavigationUsesTimeRulesName() {
        val nav = source("src/main/res/menu/bottom_nav_menu.xml")

        assertTrue(nav.contains("android:title=\"时间规则\""))
        assertFalse(nav.contains("android:title=\"专注规则\""))
    }

    @Test
    fun activationPolicyLivesOnTimeRulesPageNotSettingsLayout() {
        val timeRules = source("src/main/res/layout/fragment_focus_rules.xml")
        val settings = source("src/main/res/layout/fragment_rules.xml")
        val controller = source("src/main/java/com/decikeeper/app/ui/FocusRulesFragment.kt")

        assertTrue(timeRules.contains("时间规则生效策略"))
        assertTrue(timeRules.contains("@+id/rg_effective_policy"))
        assertTrue(timeRules.contains("@+id/btn_effective_policy_help"))
        assertFalse(settings.contains("@+id/rg_effective_policy"))
        assertFalse(settings.contains("@+id/btn_effective_policy_help"))
        assertTrue(controller.contains("EffectiveActivationPolicyManager"))
        assertTrue(controller.contains("setupEffectivePolicyToggle"))
    }

    @Test
    fun settingsControllerNoLongerOwnsTimeRuleActivationPolicy() {
        val settingsController = source("src/main/java/com/decikeeper/app/ui/RulesFragment.kt")

        assertFalse(settingsController.contains("EffectiveActivationPolicyManager"))
        assertFalse(settingsController.contains("setupEffectivePolicyToggle"))
        assertFalse(settingsController.contains("updateEffectivePolicyUI"))
        assertFalse(settingsController.contains("showEffectivePolicyHelpDialog"))
    }
}
