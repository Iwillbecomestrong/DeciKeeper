
package com.zhizhi.app

import org.junit.Assert.*
import org.junit.Test


class AntiUninstallLogicTest {

    val UNINSTALL_KEYWORDS = setOf(
        "卸载", "删除应用", "移除", "强行停止", "停用",
        "Uninstall", "Force stop", "Disable"
    )

    @Test
    fun testVivoLauncherCaseSensitivityRisk() {
        val simulatedScreenText = "Do you want to uninstall this app?"
        
        // 旧逻辑：直接 contains，区分大小写
        // "Uninstall" vs "uninstall" -> False
        // 但这里 simulatedScreenText 用的是 "uninstall"
        
        val hasUninstallCaseSensitive = UNINSTALL_KEYWORDS.any { simulatedScreenText.contains(it) }
        
        // 期望：如果是 "uninstall"，hasUninstallCaseSensitive 应该是 false (因为集合里是 "Uninstall")
        // 如果是 "Uninstall"，应该是 true
        
        // 让我们看看实际效果
        println("Case Sensitive Check ('uninstall' vs 'Uninstall'): $hasUninstallCaseSensitive") 

        val hasUninstallIgnoreCase = UNINSTALL_KEYWORDS.any { simulatedScreenText.contains(it, ignoreCase = true) }
        println("Ignore Case Check: $hasUninstallIgnoreCase")
        
        assertTrue("IgnoreCase should match", hasUninstallIgnoreCase)
    }
}
