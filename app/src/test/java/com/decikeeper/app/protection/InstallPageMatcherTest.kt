package com.decikeeper.app.protection

import com.decikeeper.app.protection.InstallPageMatcher.PageNode
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InstallPageMatcherTest {
    private val installer = "com.android.packageinstaller"
    private val appNameId = "com.android.packageinstaller:id/tv_app_name"
    private val riskTitleId = "com.android.packageinstaller:id/tv_risk_title"

    // vivo V2425A 真机（2026-10-08）：打开决定守护的 APK 先出现的风险拦截页。
    private fun vivoInterceptPageFor(appName: String) = listOf(
        PageNode(appNameId, appName),
        PageNode("com.android.packageinstaller:id/tv_app_size", "14.50 MB"),
        PageNode("com.android.packageinstaller:id/tv_app_version", "0.3.0"),
        PageNode("com.android.packageinstaller:id/tv_caller", "来自“文件管理”"),
        PageNode(riskTitleId, "无法识别应用核准（备案）情况"),
        PageNode("android:id/button1", "继续安装"),
        PageNode("com.android.packageinstaller:id/ok_button", "查找类似应用")
    )

    @Test
    fun `vivo risk intercept page for this app is matched by its app name node`() {
        assertTrue(InstallPageMatcher.matches(installer, vivoInterceptPageFor("决定守护")))
    }

    @Test
    fun `the same page for another app is not matched`() {
        assertFalse(InstallPageMatcher.matches(installer, vivoInterceptPageFor("微信")))
        assertFalse(InstallPageMatcher.matches(installer, vivoInterceptPageFor("某某游戏")))
    }

    @Test
    fun `old and english names of this app are matched and whitespace is ignored`() {
        listOf("DeciKeeper", "Deci Keeper", "IronWill", "Iron Will", "决定 守护").forEach {
            assertTrue(it, InstallPageMatcher.matches(installer, vivoInterceptPageFor(it)))
        }
    }

    @Test
    fun `a standard install confirmation page without the vivo ids is matched through the fallback`() {
        val nodes = listOf(
            PageNode(null, "要更新“决定守护”吗？"),
            PageNode(null, "此应用将获得新的权限"),
            PageNode("android:id/button1", "更新"),
            PageNode("android:id/button2", "取消")
        )
        assertTrue(InstallPageMatcher.matches(installer, nodes))
    }

    @Test
    fun `an uninstall confirmation mentioning this app is left to anti uninstall`() {
        val nodes = listOf(
            PageNode(null, "要卸载“决定守护”吗？"),
            PageNode("android:id/button1", "卸载"),
            PageNode("android:id/button2", "取消")
        )
        assertFalse(InstallPageMatcher.matches(installer, nodes))
    }

    @Test
    fun `an installer page that never mentions this app is not matched even when it is huge`() {
        val nodes = (1..400).map { PageNode(null, "权限条目 $it") } + PageNode("android:id/button1", "安装")
        assertFalse(InstallPageMatcher.matches(installer, nodes))
    }

    @Test
    fun `non installer packages never match`() {
        assertFalse(InstallPageMatcher.matches("com.tencent.mm", vivoInterceptPageFor("决定守护")))
        assertFalse(InstallPageMatcher.matches("com.android.settings", vivoInterceptPageFor("决定守护")))
        assertFalse(InstallPageMatcher.matches("com.android.packageinstaller.fake", vivoInterceptPageFor("决定守护")))
    }

    @Test
    fun `oem installer package names are recognised`() {
        listOf("com.android.packageinstaller", "com.google.android.packageinstaller", "com.oplus.packageinstaller")
            .forEach { assertTrue(it, InstallPageMatcher.matches(it, vivoInterceptPageFor("决定守护"))) }
    }
}
