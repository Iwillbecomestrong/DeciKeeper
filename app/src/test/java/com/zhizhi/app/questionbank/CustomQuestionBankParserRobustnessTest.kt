package com.zhizhi.app.questionbank

import com.zhizhi.app.manager.questionbank.CustomQuestionBankParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CustomQuestionBankParserRobustnessTest {

    @Test
    fun parsesMiddleDotBulletsFromAiOutput() {
        val text = """
            20. 根据我国刑法，已满多少周岁的人犯罪应当负完全刑事责任？
            · A. 14周岁
            · B. 16周岁
            · C. 18周岁
            · D. 12周岁
            答案: B
            解析: 刑法规定，已满十六周岁的人犯罪，应当负刑事责任。
        """.trimIndent()

        val result = CustomQuestionBankParser.parseContent(text)

        assertEquals(1, result.totalParsed)
        assertFalse(result.hasIssues)
        assertEquals(listOf("14周岁", "16周岁", "18周岁", "12周岁"), result.successQuestions.single().options)
        assertEquals("B", result.successQuestions.single().answer)
    }

    @Test
    fun parsesCommonAiBulletVariantsAndFullWidthLetters() {
        val text = """
            1. 哪个选项是正确的？
            • Ａ．选项甲
            ● Ｂ、选项乙
            ▪ Ｃ）选项丙
            ◦ Ｄ：选项丁
            正确答案：Ｃ
            解析：测试常见 AI 排版符号。
        """.trimIndent()

        val result = CustomQuestionBankParser.parseContent(text)

        assertEquals(1, result.totalParsed)
        assertFalse(result.hasIssues)
        assertEquals(listOf("选项甲", "选项乙", "选项丙", "选项丁"), result.successQuestions.single().options)
        assertEquals("C", result.successQuestions.single().answer)
    }

    @Test
    fun parsesMarkdownBoldOptionLabelsAndIgnoresCodeFences() {
        val text = """
            ```markdown
            ## 1. 测试题
            **A.** 甲
            **B.** 乙
            **C.** 丙
            **D.** 丁
            参考答案: D
            解析: 测试 Markdown 强调格式。
            ```
        """.trimIndent()

        val result = CustomQuestionBankParser.parseContent(text)

        assertEquals(1, result.totalParsed)
        assertFalse(result.hasIssues)
        assertEquals("测试题", result.successQuestions.single().stem)
        assertEquals(listOf("甲", "乙", "丙", "丁"), result.successQuestions.single().options)
        assertEquals("D", result.successQuestions.single().answer)
    }

    @Test
    fun stillRejectsActuallyMissingOptionInsteadOfGuessing() {
        val text = """
            1. 这道题确实缺少 D 选项
            • A. 甲
            • B. 乙
            • C. 丙
            答案: B
        """.trimIndent()

        val result = CustomQuestionBankParser.parseContent(text)

        assertEquals(0, result.totalParsed)
        assertTrue(result.hasIssues)
        assertTrue(result.issues.single().message.contains("选项不足4个"))
    }
}
