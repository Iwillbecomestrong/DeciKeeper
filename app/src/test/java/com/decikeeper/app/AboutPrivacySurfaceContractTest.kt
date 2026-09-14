package com.decikeeper.app

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AboutPrivacySurfaceContractTest {
    private fun read(path: String): String {
        val cwd = File(System.getProperty("user.dir"))
        val parent = cwd.parentFile ?: cwd
        val candidates = listOf(
            File(cwd, path), File(cwd, "app/$path"),
            File(parent, path), File(parent, "app/$path")
        )
        return candidates.firstOrNull { it.isFile }?.readText(Charsets.UTF_8)
            ?: error("Cannot locate $path")
    }

    @Test
    fun aboutSurfaceUsesPlainLanguageAndKeepsRequiredSections() {
        val activity = read("src/main/java/com/decikeeper/app/ui/AboutActivity.kt")
        val legalActivity = read("src/main/java/com/decikeeper/app/ui/LegalDocumentActivity.kt")
        val layout = read("src/main/res/layout/activity_about.xml")
        assertTrue(layout.contains("关于决定守护"))
        assertTrue(layout.contains("知道什么时候停止。"))
        assertTrue(layout.contains("应用信息"))
        assertTrue(layout.contains("功能说明"))
        assertTrue("About 页面功能说明必须阐明决定保护定位", layout.contains("决定守护是一款决定保护软件。"))
        assertTrue("About 页面功能说明必须包含规则保护与挑战闹钟双核", layout.contains("规则保护") && layout.contains("挑战闹钟"))
        assertFalse("About 页面不得将应用狭隘定义为闹钟应用", layout.contains("闹钟应用"))
        assertTrue(activity.contains("内容来源"))
        assertTrue(activity.contains("KaoYan-English"))
        assertTrue("About 页面应用信息卡片必须展示 QQ 反馈群", layout.contains("交流反馈群") && layout.contains("980203768"))
        assertTrue("About 页面必须具备 QQ 群点击复制逻辑", activity.contains("setupQqGroupEntry") && activity.contains("copyToClipboard"))
        assertTrue(legalActivity.contains("Markwon.builder"))
        assertTrue(legalActivity.contains(".setMarkdown"))
        assertFalse("About 页面不得展示旧的技术合规入口", layout.contains("第三方 SDK") || activity.contains("第三方 SDK"))
    }

    @Test
    fun privacyNoticeExplainsLocalStorageAndPermissionPurposes() {
        val notice = read("src/main/res/raw/privacy_notice.txt")
        assertTrue(notice.startsWith("隐私说明及权限说明"))
        assertTrue(notice.contains("不收集任何数据"))
        assertTrue(notice.contains("支持用户主动导出日志和设置"))
        assertTrue(notice.contains("单机软件"))
        assertTrue(notice.contains("保存在用户设备本地"))
        assertTrue(notice.contains("通知权限"))
        assertTrue(notice.contains("睡眠待机优化"))
        assertTrue(notice.contains("部分手机提供"))
        assertFalse(notice.contains("2. 精确闹钟"))
        assertTrue(notice.contains("相机权限"))
        assertTrue(notice.contains("无障碍服务"))
        assertTrue(notice.contains("2. 关联启动"))
        assertTrue(notice.contains("这是无障碍服务的配套系统设置"))
        assertTrue(notice.contains("锁屏显示"))
        assertTrue(notice.contains("3. 锁屏显示"))
        assertTrue(notice.contains("悬浮窗权限"))
        assertTrue(notice.contains("使用情况访问权限"))
        assertFalse(notice.contains("账号注销入口"))
    }

    @Test
    fun currentManifestHasNoNetworkPermissionOrAnalyticsDependency() {
        val manifest = read("src/main/AndroidManifest.xml")
        val gradle = read("app/build.gradle.kts")
        assertFalse(manifest.contains("android.permission.INTERNET"))
        assertFalse(manifest.contains("android.permission.ACCESS_NETWORK_STATE"))
        assertFalse(gradle.contains("firebase"))
        assertFalse(gradle.contains("bugly"))
        assertFalse(gradle.contains("umeng"))
        assertFalse(gradle.contains("admob"))
    }
}
