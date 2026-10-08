/**
 * =================================================================================
 * [Context Header]
 * * @file: DeveloperOptionsPageMatcher.kt
 * * @role: Domain Skill
 * * @description: 判定当前窗口是否为系统「开发者选项」页（纯 Kotlin，可 JVM 测试）。
 * * @responsibilities:
 * 1. 按包名、类名、工具栏标题、页面条目识别开发者选项页。
 * 2. 区分「页面标题」与「列表里的同名入口行」，避免拦住父页面。
 * * @context:
 * - 上游: DeveloperOptionsProtection
 * - 下游: 无
 * * @constraints (必读):
 * 1. 不依赖 Android API。
 * 2. 严禁以「页面任意位置出现『开发者选项』」判定，设置首页与系统管理页都有同名入口行。
 * 3. 文案比较忽略空白字符；单一文案不是唯一证据。
 * =================================================================================
 */
package com.decikeeper.app.protection

internal object DeveloperOptionsPageMatcher {

    data class PageNode(val viewId: String?, val text: String)

    // 开发者选项在各主流 ROM（含 vivo）都由 com.android.settings 承载；权限管理类包不含该页。
    private const val SETTINGS_PACKAGE = "com.android.settings"

    private val TITLES = setOf("开发者选项", "developeroptions", "開發人員選項", "開發者選項")

    // 开发者选项页独有的条目，真机（vivo V2425A）取自 2026-10-08。页面滚动后标题可能不在可见节点中，
    // 凭其中至少两项仍可识别。
    private val MARKERS = setOf(
        "usb调试", "usbdebugging", "usb偵錯",
        "无线调试", "wirelessdebugging", "無線偵錯",
        "撤消usb调试授权", "撤销usb调试授权", "revokeusbdebuggingauthorizations", "撤銷usb偵錯授權",
        "停用adb授权超时功能", "disableadbauthorizationtimeout",
        "选择调试应用", "selectdebugapp",
        "等待调试程序", "waitfordebugger"
    )
    private const val MIN_MARKERS = 2

    private const val DEVELOPMENT_CLASS_KEYWORD = "DevelopmentSettings"

    fun matches(packageName: String, className: String, nodes: List<PageNode>): Boolean {
        if (packageName != SETTINGS_PACKAGE) return false
        if (className.contains(DEVELOPMENT_CLASS_KEYWORD, ignoreCase = true)) return true

        if (nodes.any { isToolbarId(it.viewId) && normalize(it.text) in TITLES }) return true

        val markersSeen = nodes.mapNotNull { normalize(it.text).takeIf { t -> t in MARKERS } }.toSet()
        return markersSeen.size >= MIN_MARKERS
    }

    /** 工具栏 / ActionBar 标题节点的 id；列表行标题（`:id/title`）不在此列。 */
    fun isToolbarId(viewId: String?): Boolean {
        val id = viewId?.lowercase() ?: return false
        return id.contains("toolbar") || id.contains("action_bar") || id.contains("actionbar")
    }

    private fun normalize(text: String?): String =
        text.orEmpty().filterNot { it.isWhitespace() }.lowercase()
}
