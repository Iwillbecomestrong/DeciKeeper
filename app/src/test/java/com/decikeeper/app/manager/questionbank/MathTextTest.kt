package com.decikeeper.app.manager.questionbank

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 题目数学写法的解析：上下标、常用命令与符号、以及不该转换的写法保持原样。纯 JVM。 */
class MathTextTest {

    private fun fmt(s: String) = MathText.debugString(MathText.parse(s))

    // ── 来自真机截图的写法 ──

    @Test
    fun limitWithBracedSubscriptAndPlainSuperscript() {
        assertEquals("极限 lim_[x→∞] (1+2/x)^[x] 等于。", fmt("极限 lim_{x→∞} (1+2/x)^x 等于。"))
    }

    @Test
    fun optionsWithSimpleExponent() {
        assertEquals("e^[2]", fmt("e^2"))
        assertEquals("e", fmt("e"))
        assertEquals("2", fmt("2"))
    }

    // ── 上下标 ──

    @Test
    fun bracedAndNestedScripts() {
        assertEquals("e^[-x^[2]]", fmt("e^{-x^2}"))
        assertEquals("x_[n+1]", fmt("x_{n+1}"))
    }

    @Test
    fun subscriptThenSuperscript() {
        assertEquals("C_[n]^[m]", fmt("C_n^m"))
    }

    @Test
    fun multiDigitAndSignedExponents() {
        assertEquals("10^[10]", fmt("10^10"))
        assertEquals("2^[-1]", fmt("2^-1"))
    }

    @Test
    fun parenthesisExponentLosesItsParentheses() {
        assertEquals("e^[-x]", fmt("e^(-x)"))
        assertEquals("a^[b+c]", fmt("a^(b+c)"))
    }

    @Test
    fun superscriptWithCommand() {
        assertEquals("30^[°]", fmt("30^\\circ".replace("\\circ", "\\degree")))
    }

    @Test
    fun scriptsFollowCjkText() {
        assertEquals("求 x^[2] 的导数", fmt("求 x^2 的导数"))
    }

    // ── 命令与符号 ──

    @Test
    fun fractionsSimpleAndComplex() {
        assertEquals("1/2", fmt("\\frac{1}{2}"))
        assertEquals("(x+1)/(x-1)", fmt("\\frac{x+1}{x-1}"))
        assertEquals("(a^[2]+b)/2", fmt("\\frac{a^2+b}{2}"))
    }

    @Test
    fun squareRoots() {
        assertEquals("√2", fmt("\\sqrt{2}"))
        assertEquals("√(x^[2]+1)", fmt("\\sqrt{x^2+1}"))
        assertEquals("^[3]√x", fmt("\\sqrt[3]{x}"))
    }

    @Test
    fun greekAndOperators() {
        assertEquals("α + β ≤ γ", fmt("\\alpha + \\beta \\leq \\gamma"))
        assertEquals("x → ∞", fmt("x \\to \\infty"))
        assertEquals("a × b ≠ c", fmt("a \\times b \\neq c"))
    }

    @Test
    fun integralWithLimits() {
        assertEquals("∫_[0]^[1] x dx", fmt("\\int_0^1 x\\,dx").replace("  ", " "))
    }

    @Test
    fun sumWithLimits() {
        assertEquals("∑_[i=1]^[n] i", fmt("\\sum_{i=1}^{n} i"))
    }

    @Test
    fun functionNamesLoseTheirBackslash() {
        assertEquals("sin x + ln x", fmt("\\sin x + \\ln x"))
        assertEquals("lim_[x→ 0] sin x/x", fmt("\\lim_{x\\to 0} \\sin x/x"))
    }

    @Test
    fun dollarDelimitersAreStripped() {
        assertEquals("1/2", fmt("$\\frac{1}{2}$"))
        assertEquals("求 x^[2]", fmt("求 \$x^2\$"))
        assertEquals("x^[2]", fmt("\\(x^2\\)"))
    }

    @Test
    fun leftRightAndTextWrappersKeepOnlyContent() {
        assertEquals("(x+1)", fmt("\\left(x+1\\right)"))
        assertEquals("速度 v", fmt("\\text{速度} v"))
    }

    @Test
    fun unknownCommandKeepsItsName() {
        assertEquals("foo", fmt("\\foo"))
    }

    // ── 不该转换的写法 ──

    @Test
    fun plainTextWithoutMathIsUntouched() {
        assertFalse(MathText.needsFormatting("今天天气很好"))
        assertEquals("今天天气很好", fmt("今天天气很好"))
    }

    @Test
    fun snakeCaseIdentifiersStayIntact() {
        assertEquals("my_file_name", fmt("my_file_name"))
        assertEquals("int max_value = 3;", fmt("int max_value = 3;"))
    }

    @Test
    fun blanksStayIntact() {
        assertEquals("答案是____", fmt("答案是____"))
        assertEquals("x____y", fmt("x____y"))
    }

    @Test
    fun caretWithoutBaseStaysIntact() {
        assertEquals("^2", fmt("^2"))
        assertEquals("a ^ b", fmt("a ^ b"))
    }

    @Test
    fun bracesInPlainTextStayIntact() {
        assertEquals("集合{1,2,3}", fmt("集合{1,2,3}"))
    }

    @Test
    fun lonelyDollarIsKept() {
        assertEquals("价格 \$5", fmt("价格 \$5"))
    }

    @Test
    fun emptyAndNullAreSafe() {
        assertFalse(MathText.needsFormatting(null))
        assertFalse(MathText.needsFormatting(""))
        assertTrue(MathText.needsFormatting("x^2"))
        assertEquals("", fmt(""))
    }

    // ── 公式段落切分 ──

    private fun segs(s: String) = MathText.splitFormulas(s).map {
        when (it) {
            is MathText.Segment.Text -> "T:" + it.text
            is MathText.Segment.Formula -> "F:" + it.latex
        }
    }

    @Test
    fun splitInlineDollarFormula() {
        assertEquals(listOf("T:求 ", "F:\\frac{1}{2}", "T: 的值"), segs("求 \$\\frac{1}{2}\$ 的值"))
    }

    @Test
    fun splitDoubleDollarAndParens() {
        assertEquals(listOf("F:x^2"), segs("\$\$x^2\$\$"))
        assertEquals(listOf("T:设 ", "F:a_n", "T: 为"), segs("设 \\(a_n\\) 为"))
        assertEquals(listOf("F:\\sum_{i=1}^n i"), segs("\\[\\sum_{i=1}^n i\\]"))
    }

    @Test
    fun splitSeveralFormulasInOneLine() {
        assertEquals(listOf("F:a", "T: 与 ", "F:b"), segs("\$a\$ 与 \$b\$"))
    }

    @Test
    fun currencyAndLonelyDollarsStayText() {
        assertEquals(listOf("T:价格 \$5 和 \$6"), segs("价格 \$5 和 \$6"))
        assertEquals(listOf("T:只有一个 \$ 符号"), segs("只有一个 \$ 符号"))
    }

    @Test
    fun escapedDollarIsNotADelimiter() {
        assertEquals(listOf("T:\\\$5 和 \\\$6"), segs("\\\$5 和 \\\$6"))
    }

    @Test
    fun textWithoutDelimitersIsASingleTextSegment() {
        assertEquals(listOf("T:e^2"), segs("e^2"))
    }

    @Test
    fun formulasWithChineseAreNotEngineEligible() {
        assertTrue(MathText.isEngineEligible("\\frac{1}{2}"))
        assertFalse(MathText.isEngineEligible("\\text{速度}"))
        assertFalse(MathText.isEngineEligible("x，y"))
    }
}
