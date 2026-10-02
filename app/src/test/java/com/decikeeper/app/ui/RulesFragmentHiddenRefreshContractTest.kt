package com.decikeeper.app.ui

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 回归：MainActivity 用 hide/show 切 Tab，设置页（RulesFragment）切回时 onResume 不触发，
 * 必须在 onHiddenChanged(false) 里刷新「每日生效时间」，否则改完勿扰时段回到设置页仍是旧值。
 */
class RulesFragmentHiddenRefreshContractTest {
    @Test
    fun rulesFragmentRefreshesRefreshTimeWhenShownAgain() {
        val cwd = File(System.getProperty("user.dir") ?: ".")
        val path = "app/src/main/java/com/decikeeper/app/ui/RulesFragment.kt"
        val src = listOf(File(cwd, path), File(cwd, path.removePrefix("app/")), File(cwd.parentFile ?: cwd, path))
            .firstOrNull { it.isFile }?.readText() ?: error("Cannot locate $path")
        val body = src.substringAfter("override fun onHiddenChanged(hidden: Boolean)")
            .substringBefore("override fun onResume()")
        assertTrue(body.contains("updateRefreshTimeUI()"))
        assertTrue(body.contains("updateEditWindowPolicyUI()"))
    }
}
