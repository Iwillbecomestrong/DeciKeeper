package com.decikeeper.app.ui.questionbank

import android.text.Spanned
import android.text.style.SubscriptSpan
import android.text.style.SuperscriptSpan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** 上下标 Spannable 的生成，以及“所有自定义题目显示处都经过格式化”的源码契约。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MathTextFormatterTest {

    @Test
    fun plainTextIsReturnedUnchanged() {
        val text = "今天天气很好"
        assertSame(text, MathTextFormatter.format(text))
    }

    @Test
    fun nullBecomesEmpty() {
        assertEquals("", MathTextFormatter.format(null).toString())
    }

    @Test
    fun limitQuestionGetsSubscriptAndSuperscript() {
        val out = MathTextFormatter.format("极限 lim_{x→∞} (1+2/x)^x 等于。") as Spanned
        assertEquals("极限 limx→∞ (1+2/x)x 等于。", out.toString())

        val sub = out.getSpans(0, out.length, SubscriptSpan::class.java)
        assertEquals(1, sub.size)
        assertEquals("x→∞", out.substring(out.getSpanStart(sub[0]), out.getSpanEnd(sub[0])))

        val sup = out.getSpans(0, out.length, SuperscriptSpan::class.java)
        assertEquals(1, sup.size)
        assertEquals("x", out.substring(out.getSpanStart(sup[0]), out.getSpanEnd(sup[0])))
    }

    @Test
    fun nestedSuperscriptsEachGetASpan() {
        val out = MathTextFormatter.format("e^{-x^2}") as Spanned
        assertEquals("e-x2", out.toString())
        assertEquals(2, out.getSpans(0, out.length, SuperscriptSpan::class.java).size)
    }

    @Test
    fun formulaWithChineseFallsBackToLightweightText() {
        val out = MathTextFormatter.format("求 \$x^2，速度\$ 的值") as Spanned
        assertEquals("求 x2，速度 的值", out.toString())
        assertTrue(out.getSpans(0, out.length, MathFormulaSpan::class.java).isEmpty())
    }

    @Test
    fun delimitedFormulaUsesEngineSpanOrFallsBackWithoutDollarSigns() {
        val out = MathTextFormatter.format("求 \$\\frac{1}{2}\$ 的值")
        // 不论排版引擎在当前环境是否可用，都不得把美元符号留在文字里
        assertTrue(!out.toString().contains("\$"))
        if (out is Spanned && out.getSpans(0, out.length, MathFormulaSpan::class.java).isNotEmpty()) {
            assertEquals("求 ￼ 的值", out.toString())
        } else {
            assertEquals("求 1/2 的值", out.toString())
        }
    }

    @Test
    fun optionPrefixDoesNotBreakFormatting() {
        val out = MathTextFormatter.format("A. e^2") as Spanned
        assertEquals("A. e2", out.toString())
        assertTrue(out.getSpans(0, out.length, SuperscriptSpan::class.java).isNotEmpty())
    }

    @Test
    fun everyCustomQuestionDisplaySiteUsesTheFormatter() {
        val base = generateSequence(File(System.getProperty("user.dir") ?: ".").absoluteFile) { it.parentFile }
            .map { File(it, "app/src/main/java/com/decikeeper/app/ui") }
            .firstOrNull { it.isDirectory } ?: error("cannot find ui source dir")
        fun src(path: String) = File(base, path).readText()

        val overlay = src("LockAlarmOverlay.kt")
        assertTrue(overlay.contains("R.id.tv_custom_stem)?.text = MathTextFormatter.format(q.stem)"))
        assertTrue(overlay.contains("btnA?.text = MathTextFormatter.format("))
        assertTrue(overlay.contains("MathTextFormatter.format(\"💡 解析：\${q.explanation}\")"))

        val review = src("questionbank/CustomQuestionReviewActivity.kt")
        assertTrue(review.contains("MathTextFormatter.format(q.stem)"))
        assertTrue(review.contains("MathTextFormatter.format(\"\$letter. \$optText\$marker\")"))

        val bookmark = src("BookmarkActivity.kt")
        assertTrue(bookmark.contains("MathTextFormatter.format(q.stem)"))
        assertTrue(bookmark.contains("MathTextFormatter.format(optText)"))
    }
}
