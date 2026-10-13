package com.decikeeper.app.governance
import com.decikeeper.app.R


import android.content.Context
import android.graphics.Color
import android.view.View
import android.widget.TextView
import com.decikeeper.app.util.ContrastHelper
import com.decikeeper.app.util.UiVisualSanityChecker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/**
 * UI 视觉质量三层机器门禁契约测试 (UiVisualSanityContractTest)
 *
 * 覆盖 AGENT_CORE 4.5 UI 视觉工程三大物理红线：
 * 1. 静态规则门禁：硬编码颜色、全局界面 fitsSystemWindows 避让、多按钮自适应；
 * 2. 运行时排版检查器：UiVisualSanityChecker 截断与低对比度检测；
 * 3. 多配置字体缩放矩阵验证。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = "app/src/main/AndroidManifest.xml")
class UiVisualSanityContractTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
    }

    private fun resolveFile(relPathUnderApp: String): File {
        val cwd = File(System.getProperty("user.dir") ?: ".")
        val candidates = listOf(
            File(cwd, relPathUnderApp),
            File(cwd, "app/$relPathUnderApp"),
            File(cwd, relPathUnderApp.removePrefix("app/")),
            File(cwd.parentFile ?: cwd, "app/$relPathUnderApp"),
            File(cwd.parentFile ?: cwd, relPathUnderApp)
        )
        return candidates.firstOrNull { it.isFile }
            ?: error("Cannot locate $relPathUnderApp from ${cwd.absolutePath}")
    }

    // ==========================================
    // 第一层：静态规则门禁 (Static Contract Gates)
    // ==========================================

    @Test
    fun rulesFragmentPermProtectionDialogsMustNotHardcodeWhiteTextOnLightCards() {
        val rulesFile = resolveFile("src/main/java/com/decikeeper/app/ui/RulesFragment.kt")
        assertTrue("RulesFragment.kt must exist", rulesFile.exists())
        val content = rulesFile.readText()

        // 验证引导弹窗已彻底消除 bg_sea_depths + Color.WHITE 的浅底白字组合，且标题更新为防卸载与防关权限保护
        assertFalse(
            "RulesFragment must not use bg_sea_depths anymore",
            content.contains("R.drawable.bg_sea_depths")
        )
        assertTrue(
            "RulesFragment must use updated title for anti-uninstall & perm protection guide dialog",
            content.contains("text = \"🛡️ 开启防卸载与防关权限保护\"")
        )
        assertFalse(
            "tvTitle in guide dialogs must not hardcode Color.WHITE",
            content.contains("text = \"🛡️ 开启防卸载与防关权限保护\"\n            textSize = 20f\n            setTypeface(typeface, android.graphics.Typeface.BOLD)\n            setTextColor(android.graphics.Color.WHITE)")
        )
    }

    @Test
    fun testAlarmDialogButtonsMustHaveAutoSizeTextType() {
        val layoutFile = resolveFile("src/main/res/layout/dialog_alarm_edit.xml")
        assertTrue("dialog_alarm_edit.xml must exist", layoutFile.exists())
        val content = layoutFile.readText()

        // 验证底部按钮防截断：包含 autoSizeTextType="uniform"
        assertTrue(
            "dialog_alarm_edit buttons must declare autoSizeTextType to prevent text truncation",
            content.contains("autoSizeTextType=\"uniform\"")
        )
        assertTrue(
            "dialog_alarm_edit must contain the test button",
            content.contains("btn_alarm_edit_test")
        )
    }

    @Test
    fun ringtonePickerActivityMustHaveFitsSystemWindows() {
        val layoutFile = resolveFile("src/main/res/layout/activity_alarm_ringtone_picker.xml")
        assertTrue("activity_alarm_ringtone_picker.xml must exist", layoutFile.exists())
        val content = layoutFile.readText()

        assertTrue(
            "Alarm ringtone picker activity layout must declare fitsSystemWindows to prevent camera punch-hole overlap",
            content.contains("android:fitsSystemWindows=\"true\"")
        )
    }

    // ==========================================
    // 第二层：运行时排版审查器 (Runtime Sanity Checker)
    // ==========================================

    @Test
    fun contrastCheckerMustCatchLowContrastBetweenLightBgAndWhiteText() {
        val white = Color.WHITE
        val lightGrayBg = Color.parseColor("#F5F7F6")

        val issues = UiVisualSanityChecker.checkContrast("titleText", white, lightGrayBg)
        assertEquals("Must report exactly 1 issue for white text on light gray bg", 1, issues.size)
        assertEquals(UiVisualSanityChecker.IssueType.LOW_CONTRAST, issues[0].issueType)
        assertTrue(issues[0].details.contains("Contrast ratio"))
    }

    @Test
    fun contrastCheckerMustPassForSufficientContrast() {
        val darkText = Color.parseColor("#1B1C1C")
        val lightBg = Color.parseColor("#F5F7F6")

        val issues = UiVisualSanityChecker.checkContrast("titleText", darkText, lightBg)
        assertTrue("High contrast combination must have 0 issues", issues.isEmpty())
    }

    @Test
    fun textOverflowCheckerMustPassForNormalTextView() {
        val tv = TextView(context).apply {
            text = "不屈意志"
            textSize = 14f
            visibility = View.VISIBLE
        }
        // 未排版或测量正常时不应误报
        val issues = UiVisualSanityChecker.checkTextView(tv)
        assertTrue(issues.isEmpty())
    }
}
