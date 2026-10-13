package com.decikeeper.app.protection

import com.decikeeper.app.protection.DeveloperOptionsPageMatcher.PageNode
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeveloperOptionsPageMatcherTest {
    private val settings = "com.android.settings"
    private val vivoHost = "com.vivo.settings.VivoSubSettings"
    private val toolbarId = "com.android.settings:id/originui_vtoolbar_normal_title_rom14_0"
    private val rowTitleId = "com.android.settings:id/title"

    @Test
    fun `vivo toolbar title with real device node ids is matched`() {
        val nodes = listOf(PageNode(toolbarId, "开发者选项"), PageNode(rowTitleId, "WebView 实现"))
        assertTrue(DeveloperOptionsPageMatcher.matches(settings, vivoHost, nodes))
    }

    @Test
    fun `english and traditional chinese titles are matched`() {
        listOf("Developer options", "開發人員選項", "開發者選項").forEach {
            val nodes = listOf(PageNode("com.android.settings:id/action_bar_title", it))
            assertTrue(it, DeveloperOptionsPageMatcher.matches(settings, "com.android.settings.Settings", nodes))
        }
    }

    @Test
    fun `settings list row named developer options does not block its parent page`() {
        val nodes = listOf(
            PageNode(toolbarId, "系统管理"),
            PageNode(rowTitleId, "开发者选项"),
            PageNode(rowTitleId, "关于手机")
        )
        assertFalse(DeveloperOptionsPageMatcher.matches(settings, vivoHost, nodes))
    }

    @Test
    fun `scrolled page without title is matched by two debugging markers`() {
        val nodes = listOf(
            PageNode(rowTitleId, "撤消 USB 调试授权"),
            PageNode(rowTitleId, "无线调试"),
            PageNode(rowTitleId, "Bug 报告快捷方式")
        )
        assertTrue(DeveloperOptionsPageMatcher.matches(settings, vivoHost, nodes))
    }

    @Test
    fun `markers ignore whitespace and revoke wording`() {
        val nodes = listOf(PageNode(rowTitleId, "USB调试"), PageNode(rowTitleId, "撤销USB调试授权"))
        assertTrue(DeveloperOptionsPageMatcher.matches(settings, vivoHost, nodes))
    }

    @Test
    fun `a single marker is not enough`() {
        val nodes = listOf(PageNode(rowTitleId, "USB 调试"), PageNode(rowTitleId, "蓝牙"))
        assertFalse(DeveloperOptionsPageMatcher.matches(settings, vivoHost, nodes))
    }

    @Test
    fun `development settings class is matched without any nodes`() {
        assertTrue(
            DeveloperOptionsPageMatcher.matches(
                settings, "com.android.settings.development.DevelopmentSettingsDashboardActivity", emptyList()
            )
        )
    }

    @Test
    fun `wireless debugging sub page with real device nodes is matched by its toolbar title`() {
        // vivo V2425A 真机（2026-10-08）：无线调试子页，快捷设置磁贴与直接跳转会落在这里。
        val nodes = listOf(
            PageNode(rowTitleId, "使用“无线调试”"),
            PageNode(rowTitleId, "设备名称"),
            PageNode(rowTitleId, "IP 地址和端口"),
            PageNode(rowTitleId, "使用二维码配对设备"),
            PageNode(rowTitleId, "使用配对码配对设备"),
            PageNode(rowTitleId, "已配对的设备"),
            PageNode(toolbarId, "无线调试")
        )
        assertTrue(DeveloperOptionsPageMatcher.matches(settings, vivoHost, nodes))
    }

    @Test
    fun `wireless debugging titles in english and traditional chinese are matched`() {
        listOf("Wireless debugging", "無線偵錯").forEach {
            val nodes = listOf(PageNode("com.android.settings:id/action_bar_title", it))
            assertTrue(it, DeveloperOptionsPageMatcher.matches(settings, "com.android.settings.Settings", nodes))
        }
    }

    @Test
    fun `a list row named wireless debugging does not block its parent page`() {
        val nodes = listOf(PageNode(toolbarId, "系统管理"), PageNode(rowTitleId, "无线调试"))
        assertFalse(DeveloperOptionsPageMatcher.matches(settings, vivoHost, nodes))
    }

    @Test
    fun `non settings packages never match`() {
        val nodes = listOf(PageNode(toolbarId, "开发者选项"))
        assertFalse(DeveloperOptionsPageMatcher.matches("com.tencent.mm", vivoHost, nodes))
        assertFalse(DeveloperOptionsPageMatcher.matches("com.android.systemui", vivoHost, nodes))
        assertFalse(DeveloperOptionsPageMatcher.matches("com.android.settings.fake", vivoHost, nodes))
    }

    @Test
    fun `toolbar id heuristics accept toolbar and action bar but not list rows`() {
        assertTrue(DeveloperOptionsPageMatcher.isToolbarId(toolbarId))
        assertTrue(DeveloperOptionsPageMatcher.isToolbarId("com.android.settings:id/action_bar_title"))
        assertFalse(DeveloperOptionsPageMatcher.isToolbarId(rowTitleId))
        assertFalse(DeveloperOptionsPageMatcher.isToolbarId(null))
    }
}
