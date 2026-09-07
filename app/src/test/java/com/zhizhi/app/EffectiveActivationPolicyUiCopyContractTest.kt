package com.zhizhi.app

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
    fun rulesHelpMatchesFavorableImmediateDirection() {
        val rules = source("src/main/java/com/zhizhi/app/ui/RulesFragment.kt")

        assertTrue(rules.contains("加严限制 → 立即生效"))
        assertTrue(rules.contains("放宽限制 → 下一刷新时刻生效"))
        assertFalse(rules.contains("添加/删除/减少限制 → 立即生效"))
        assertFalse(rules.contains("增加限制 → 次日 00:00 生效"))
    }
}
