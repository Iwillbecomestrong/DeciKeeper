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

        assertTrue(help.contains("assets.open(name)"))
        assertTrue(help.contains("\"getting-started.md\""))
        assertTrue(help.contains("\"guide.md\""))
        assertTrue(help.contains("\"permissions.md\""))
        assertFalse(help.contains("fun buildHelpText(): String"))
        assertTrue(gradle.contains("generated/userGuideAssets"))
        assertTrue(gradle.contains("docs/user"))
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
    fun appHelpSupportsInteractiveHyperlinksAndInternalJumps() {
        val help = source("src/main/java/com/decikeeper/app/ui/HelpActivity.kt")
        assertTrue(help.contains("LinkMovementMethod.getInstance()"))
        assertTrue(help.contains("builder.linkResolver"))
        assertTrue(help.contains("scrollToSectionOrKeyword"))
    }

    @Test
    fun canonicalManualKeepsFirstRunPermissionsAndCoolingCopyAligned() {
        // docs/user/ 取代了旧的单文件 docs/使用手册.md，契约改读拆分后的源文档。
        val gettingStarted = source("../docs/user/getting-started.md")
        val guide = source("../docs/user/guide.md")
        val permissions = source("../docs/user/permissions.md")
        val manual = gettingStarted + "\n" + guide
        val allUserDocs = manual + "\n" + permissions

        assertFalse(manual.contains("无障碍、悬浮窗、使用情况访问等必要权限稳定"))
        // 首页「权限设置」引导的实际条目由 HomeFragment 的 addItem() 决定
        // （悬浮窗 / 通知 / 后台耗电管理 / 自启动），文档必须与之一致；
        // 早期手册里的"5 项"是错数字，不得回归。
        assertTrue(gettingStarted.contains("悬浮窗权限"))
        assertTrue(gettingStarted.contains("自启动权限"))
        assertFalse(gettingStarted.contains("5 项基础权限"))
        assertFalse("permissions.md 中亦不得出现 5 项基础权限错数字", permissions.contains("5 项基础权限"))
        assertTrue(gettingStarted.contains("不需要先开启无障碍"))
        assertTrue(gettingStarted.contains("无障碍是决定守护权限等级最高、能力最强的一项权限"))

        assertFalse(manual.contains("可能整体按双倍成本处理"))
        assertTrue(guide.contains("冷却期间再次申请，会按 2 倍扣除应计费额度和次数"))
    }
}
