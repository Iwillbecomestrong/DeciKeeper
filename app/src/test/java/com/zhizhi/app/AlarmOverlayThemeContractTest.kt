package com.zhizhi.app

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 响铃界面主题与温和文案契约测试：
 * 1. 确保 window_alarm_overlay.xml 与 activity_alarm_scan.xml 全面使用四季主题属性 (?attr/zzPageBg, ?attr/zzSurface 等)，不再硬编码暗黑值 (#0B1114 等)。
 * 2. 确保 AlarmActivity 调用 ThemeManager.apply 注入当季主题。
 * 3. 确保 LockAlarmOverlay 使用 ContextThemeWrapper 包裹主题，动态取色。
 * 4. 确保文案友好，彻底移除任何说教/贬低性词汇（如“沉迷”），使用温和生活化文案（“刷牙、洗脸”）。
 */
class AlarmOverlayThemeContractTest {

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
    fun alarmOverlayLayoutMustUseThemeAttributesAndAvoidHardcodedDarkBg() {
        val layout = source("src/main/res/layout/window_alarm_overlay.xml")

        // 根背景必须使用 ?attr/zzPageBg
        assertTrue(
            "window_alarm_overlay.xml root should use ?attr/zzPageBg",
            layout.contains("android:background=\"?attr/zzPageBg\"")
        )

        // 严禁旧版硬编码黑背景
        assertFalse(
            "window_alarm_overlay.xml must not contain hardcoded #0B1114 background",
            layout.contains("#0B1114")
        )
        assertFalse(
            "window_alarm_overlay.xml must not contain hardcoded #102A2F card background",
            layout.contains("#102A2F")
        )
    }

    @Test
    fun alarmScanActivityLayoutMustUseThemeAttributesAndAvoidHardcodedDarkBg() {
        val scanLayout = source("src/main/res/layout/activity_alarm_scan.xml")

        assertTrue(
            "activity_alarm_scan.xml root should use ?attr/zzPageBg",
            scanLayout.contains("android:background=\"?attr/zzPageBg\"")
        )
        assertFalse(
            "activity_alarm_scan.xml must not contain hardcoded #0B1114 background",
            scanLayout.contains("#0B1114")
        )
    }

    @Test
    fun alarmActivityMustApplyThemeManager() {
        val activity = source("src/main/java/com/zhizhi/app/ui/AlarmActivity.kt")
        assertTrue(
            "AlarmActivity onCreate must apply ThemeManager",
            activity.contains("ThemeManager.apply(this)")
        )
    }

    @Test
    fun alarmThemeStyleMustReferencePageBg() {
        val themes = source("src/main/res/values/themes.xml")
        val alarmStyle = themes.substringAfter("<style name=\"Theme.ZhiZhi.Alarm\"")
            .substringBefore("</style>")

        assertTrue(
            "Theme.ZhiZhi.Alarm windowBackground should reference ?attr/zzPageBg",
            alarmStyle.contains("<item name=\"android:windowBackground\">?attr/zzPageBg</item>")
        )
        assertTrue(
            "Theme.ZhiZhi.Alarm statusBarColor should reference ?attr/zzPageBg",
            alarmStyle.contains("<item name=\"android:statusBarColor\">?attr/zzPageBg</item>")
        )
    }

    @Test
    fun lockAlarmOverlayMustWrapThemeAndUseFriendlyCopy() {
        val overlay = source("src/main/java/com/zhizhi/app/ui/LockAlarmOverlay.kt")

        // 悬浮窗必须使用 ContextThemeWrapper 包裹四季主题资源
        assertTrue(
            "LockAlarmOverlay must resolve season theme with ThemeManager.themeRes",
            overlay.contains("ThemeManager.themeRes(ctx)")
        )
        assertTrue(
            "LockAlarmOverlay must wrap layout inflater context with ContextThemeWrapper",
            overlay.contains("ContextThemeWrapper(ctx, themeRes)") ||
                overlay.contains("ContextThemeWrapper(ctx, ThemeManager.themeRes(ctx))")
        )

        // 必须包含动态主题着色逻辑
        assertTrue(
            "LockAlarmOverlay must have applyThemeToAlarmView method",
            overlay.contains("applyThemeToAlarmView")
        )

        // 文案契约：严禁说教、贬低词汇（如“沉迷”）
        assertFalse(
            "LockAlarmOverlay must not contain preachy word '沉迷'",
            overlay.contains("沉迷")
        )

        // 文案契约：必须使用生活化温和文案（如刷牙洗脸）
        assertTrue(
            "LockAlarmOverlay cooldown prompt must guide user to wash up gently",
            overlay.contains("刷牙") && overlay.contains("洗脸")
        )
    }
}
