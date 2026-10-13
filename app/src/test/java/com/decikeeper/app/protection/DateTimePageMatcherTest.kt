package com.decikeeper.app.protection

import com.decikeeper.app.protection.DeveloperOptionsPageMatcher.PageNode
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DateTimePageMatcherTest {
    private val settings = "com.android.settings"
    private val vivoHost = "com.vivo.settings.VivoSubSettings"
    private val toolbarId = "com.android.settings:id/originui_vtoolbar_normal_title_rom14_0"
    private val rowTitleId = "com.android.settings:id/title"

    @Test
    fun `vivo date and time page with real device nodes is matched`() {
        // vivo V2425A 真机（2026-10-11）：日期与时间页。
        val nodes = listOf(
            PageNode(rowTitleId, "自动设置时间"),
            PageNode(rowTitleId, "日期"),
            PageNode(rowTitleId, "时间"),
            PageNode(rowTitleId, "自动设置时区"),
            PageNode(rowTitleId, "使用 24 小时制"),
            PageNode(toolbarId, "日期与时间")
        )
        assertTrue(DateTimePageMatcher.matches(settings, vivoHost, nodes))
    }

    @Test
    fun `english and traditional chinese titles are matched`() {
        listOf("Date & time", "Date and time", "日期和時間", "日期與時間", "日期和时间").forEach {
            val nodes = listOf(PageNode("com.android.settings:id/action_bar_title", it))
            assertTrue(it, DateTimePageMatcher.matches(settings, "com.android.settings.Settings", nodes))
        }
    }

    @Test
    fun `title comparison ignores whitespace`() {
        val nodes = listOf(PageNode(toolbarId, " 日期 与 时间 "))
        assertTrue(DateTimePageMatcher.matches(settings, vivoHost, nodes))
    }

    @Test
    fun `settings list row named date and time does not block its parent page`() {
        val nodes = listOf(
            PageNode(toolbarId, "系统管理"),
            PageNode(rowTitleId, "日期与时间"),
            PageNode(rowTitleId, "开发者选项")
        )
        assertFalse(DateTimePageMatcher.matches(settings, vivoHost, nodes))
    }

    @Test
    fun `date and time rows alone without the page title are not enough`() {
        val nodes = listOf(PageNode(rowTitleId, "日期"), PageNode(rowTitleId, "时间"))
        assertFalse(DateTimePageMatcher.matches(settings, vivoHost, nodes))
    }

    @Test
    fun `other settings sub pages do not match`() {
        val nodes = listOf(PageNode(toolbarId, "电池"), PageNode(rowTitleId, "时间"))
        assertFalse(DateTimePageMatcher.matches(settings, vivoHost, nodes))
        assertFalse(DateTimePageMatcher.matches(settings, vivoHost, emptyList()))
    }

    @Test
    fun `aosp date time settings class is matched without any nodes`() {
        assertTrue(
            DateTimePageMatcher.matches(
                settings, "com.android.settings.Settings\$DateTimeSettingsActivity", emptyList()
            )
        )
    }

    @Test
    fun `non settings packages never match`() {
        val nodes = listOf(PageNode(toolbarId, "日期与时间"))
        assertFalse(DateTimePageMatcher.matches("com.tencent.mm", vivoHost, nodes))
        assertFalse(DateTimePageMatcher.matches("com.android.systemui", vivoHost, nodes))
        assertFalse(DateTimePageMatcher.matches("com.android.settings.fake", vivoHost, nodes))
    }
}
