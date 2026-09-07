package com.zhizhi.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class HelpManualSingleSourceContractTest {
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
    fun appHelpUsesGeneratedManualAssetInsteadOfHardcodedSecondCopy() {
        val help = source("src/main/java/com/zhizhi/app/ui/HelpActivity.kt")
        val gradle = source("build.gradle.kts")

        assertTrue(help.contains("assets.open(\"使用手册.md\")"))
        assertFalse(help.contains("fun buildHelpText(): String"))
        assertTrue(gradle.contains("generated/userGuideAssets"))
        assertTrue(gradle.contains("docs/使用手册.md"))
    }
}
