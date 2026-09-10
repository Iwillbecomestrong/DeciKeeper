package com.decikeeper.app

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
        val help = source("src/main/java/com/decikeeper/app/ui/HelpActivity.kt")
        val gradle = source("build.gradle.kts")

        assertTrue(help.contains("assets.open(\"使用手册.md\")"))
        assertFalse(help.contains("fun buildHelpText(): String"))
        assertTrue(gradle.contains("generated/userGuideAssets"))
        assertTrue(gradle.contains("docs/使用手册.md"))
    }

    @Test
    fun appHelpParsesMarkdownHeadingsFromCanonicalManual() {
        val help = source("src/main/java/com/decikeeper/app/ui/HelpActivity.kt")

        assertTrue(help.contains("markdownLevel = trimmed.takeWhile { it == '#' }.length"))
        assertTrue(help.contains("markdownTitle"))
    }

    @Test
    fun appHelpRendersMarkdownInsteadOfShowingRawMarkers() {
        val help = source("src/main/java/com/decikeeper/app/ui/HelpActivity.kt")
        val gradle = source("build.gradle.kts")

        assertTrue(gradle.contains("io.noties.markwon:core"))
        assertTrue(gradle.contains("io.noties.markwon:ext-tables"))
        assertTrue(help.contains("Markwon.builder"))
        assertTrue(help.contains("TablePlugin.create"))
        assertTrue(help.contains("markwon.setMarkdown"))
    }

    @Test
    fun canonicalManualKeepsFirstRunPermissionsAndCoolingCopyAligned() {
        val manual = source("../docs/使用手册.md")

        assertFalse(manual.contains("无障碍、悬浮窗、使用情况访问等必要权限稳定"))
        assertTrue(manual.contains("首页的 5 项基础权限"))
        assertTrue(manual.contains("不需要先开启无障碍"))
        assertTrue(manual.contains("无障碍是决定守护权限等级最高、能力最强的一项权限"))

        assertFalse(manual.contains("可能整体按双倍成本处理"))
        assertTrue(manual.contains("冷却期间再次申请，会按 2 倍扣除应计费额度和次数"))
    }
}
