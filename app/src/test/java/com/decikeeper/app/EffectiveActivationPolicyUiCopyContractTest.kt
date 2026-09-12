package com.decikeeper.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class EffectiveActivationPolicyUiCopyContractTest {
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
    fun timeRulesHelpUsesCanonicalPolicyDescriptions() {
        val layout = source("src/main/res/layout/activity_global_params_new.xml")
        val focusRules = source("src/main/java/com/decikeeper/app/ui/FocusRulesFragment.kt")

        assertTrue(layout.contains("「宽严有别」（推荐）"))
        assertTrue(layout.contains("「统一延后」"))
        assertTrue(layout.contains("控制勿扰时段、应用时间窗、规则参数与全局黑名单的生效方式。"))
        assertFalse(layout.contains("控制时间规则、应用时间窗"))
        assertFalse(focusRules.contains("EffectiveActivationPolicyManager"))
        assertFalse(layout.contains("添加/删除/减少限制 → 立即生效"))
        assertFalse(layout.contains("增加限制 → 次日 00:00 生效"))
    }
}
