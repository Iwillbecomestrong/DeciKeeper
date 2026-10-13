/**
 * =================================================================================
 * [Context Header]
 * * @file: DateTimePageMatcher.kt
 * * @role: Domain Skill
 * * @description: 判定当前窗口是否为系统「日期与时间」页（纯 Kotlin，可 JVM 测试）。
 * * @responsibilities:
 * 1. 按包名、类名、工具栏标题识别日期与时间页。
 * 2. 区分「页面标题」与「列表里的同名入口行」，避免拦住父页面。
 * * @context:
 * - 上游: DateTimeProtection
 * - 下游: DeveloperOptionsPageMatcher（复用工具栏节点判断）
 * * @constraints (必读):
 * 1. 不依赖 Android API。
 * 2. 严禁以「页面任意位置出现『日期与时间』」判定，设置首页与系统管理页都有同名入口行。
 * 3. 文案比较忽略空白字符。
 * =================================================================================
 */
package com.decikeeper.app.protection

import com.decikeeper.app.protection.DeveloperOptionsPageMatcher.PageNode

internal object DateTimePageMatcher {

    // 日期与时间页由 com.android.settings 承载。
    private const val SETTINGS_PACKAGE = "com.android.settings"

    // vivo V2425A 真机取证 2026-10-11：工具栏标题「日期与时间」。其余为各语言 / AOSP 常见写法。
    private val TITLES = setOf(
        "日期与时间", "日期和时间", "日期與時間", "日期和時間",
        "dateandtime", "date&time", "datetime"
    )

    // AOSP：com.android.settings.Settings$DateTimeSettingsActivity。
    private const val DATE_TIME_CLASS_KEYWORD = "DateTimeSettings"

    fun matches(packageName: String, className: String, nodes: List<PageNode>): Boolean {
        if (packageName != SETTINGS_PACKAGE) return false
        if (className.contains(DATE_TIME_CLASS_KEYWORD, ignoreCase = true)) return true
        return nodes.any { DeveloperOptionsPageMatcher.isToolbarId(it.viewId) && normalize(it.text) in TITLES }
    }

    private fun normalize(text: String?): String =
        text.orEmpty().filterNot { it.isWhitespace() }.lowercase()
}
