/**
 * =================================================================================
 * [Context Header]
 * * @file: InstallPageMatcher.kt
 * * @role: Domain Skill
 * * @description: 判定当前窗口是否为系统安装器里「安装 / 更新决定守护」的页面（纯 Kotlin，可 JVM 测试）。
 * * @responsibilities:
 * 1. 按安装器包名与页面上的应用名识别针对本应用的安装 / 更新页。
 * 2. 不拦其他应用的安装，不接卸载（卸载归防卸载保护）。
 * * @context:
 * - 上游: InstallProtection
 * - 下游: AntiUninstallProtection 的名称与关键词表
 * * @constraints (必读):
 * 1. 不依赖 Android API。
 * 2. 命中必须以「页面明确出现本应用名」为前提；严禁因为是安装器页就拦。
 * 3. 扫描超预算且没见到应用名时必须放行（fail-open），历史上 fail-closed 误挡过合法安装（24897a40）。
 * 4. 带卸载关键词的页面一律不命中，留给防卸载。
 * =================================================================================
 */
package com.decikeeper.app.protection

internal object InstallPageMatcher {

    data class PageNode(val viewId: String?, val text: String)

    /** vivo 风险拦截页上的应用名控件，真机取自 V2425A（2026-10-08）。 */
    internal const val APP_NAME_VIEW_ID_SUFFIX = "id/tv_app_name"

    private val PROTECTED_NAMES = AntiUninstallProtection.PROTECTED_APP_NAMES.map { normalize(it) }.toSet()

    // 兜底扫描按「包含」匹配，过短的名称（如「意引」）太容易误撞，只用足够长的。
    private val FALLBACK_NAMES = PROTECTED_NAMES.filter { it.length >= 4 }

    private val INSTALL_KEYWORDS = AntiUninstallProtection.INSTALL_KEYWORDS.map { normalize(it) }
    private val UNINSTALL_KEYWORDS = AntiUninstallProtection.UNINSTALL_KEYWORDS.map { normalize(it) }

    fun isInstallerPackage(packageName: String): Boolean = packageName.endsWith(".packageinstaller")

    fun isProtectedAppName(text: String?): Boolean = normalize(text) in PROTECTED_NAMES

    fun matches(packageName: String, nodes: List<PageNode>): Boolean {
        if (!isInstallerPackage(packageName)) return false

        // 主信号：应用名控件的文字就是本应用名。
        if (nodes.any { it.viewId?.endsWith(APP_NAME_VIEW_ID_SUFFIX) == true && isProtectedAppName(it.text) }) return true

        // 兜底：标准安装 / 更新确认页没有 vivo 的控件 id，要同时满足三条。
        val texts = nodes.map { normalize(it.text) }
        if (texts.none { t -> FALLBACK_NAMES.any { t.contains(it) } }) return false
        if (texts.any { t -> UNINSTALL_KEYWORDS.any { t.contains(it) } }) return false
        return texts.any { t -> INSTALL_KEYWORDS.any { t.contains(it) } }
    }

    private fun normalize(text: String?): String =
        text.orEmpty().filterNot { it.isWhitespace() }.lowercase()
}
