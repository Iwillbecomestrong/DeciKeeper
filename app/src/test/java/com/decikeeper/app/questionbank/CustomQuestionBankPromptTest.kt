package com.decikeeper.app.questionbank

import com.decikeeper.app.manager.questionbank.CustomQuestionBankManager
import org.junit.Assert.assertTrue
import org.junit.Test

class CustomQuestionBankPromptTest {

    @Test
    fun formatRepairPromptPreservesQuestionMeaningAndEnforcesStableMarkers() {
        // Stable contract: repair formatting without silently rewriting question semantics.
        val source = """
            20. 测试题
            · A. 甲
            · B. 乙
            · C. 丙
            · D. 丁
            答案: B
        """.trimIndent()

        val prompt = CustomQuestionBankManager.getFormatRepairPrompt(source)

        assertTrue(prompt.contains("只做格式整理"))
        assertTrue(prompt.contains("不得改变已有正确答案"))
        assertTrue(prompt.contains("- A. 选项A"))
        assertTrue(prompt.contains("禁止使用 `•`、`·`、`●`"))
        assertTrue(prompt.contains("[格式无法修复]"))
        assertTrue(prompt.contains(source))
    }

    @Test
    fun generationAndConversionPromptsTellAiHowToWriteFormulas() {
        listOf(
            CustomQuestionBankManager.getAiPrompt(),
            CustomQuestionBankManager.getExistingBankConversionPrompt()
        ).forEach { prompt ->
            assertTrue(prompt.contains("公式写法"))
            assertTrue(prompt.contains("e^{-x^2}"))
            assertTrue(prompt.contains("\\frac{分子}{分母}"))
            assertTrue(prompt.contains("\\lim_{x\\to\\infty}"))
            assertTrue(prompt.contains("不要使用美元符号包裹"))
            assertTrue(prompt.contains("看图或看表"))
        }
    }

    @Test
    fun conversionPromptKeepsSourcePlaceholderAfterFormulaRules() {
        val prompt = CustomQuestionBankManager.getExistingBankConversionPrompt()
        assertTrue(prompt.indexOf("公式写法") < prompt.indexOf("[请在此粘贴你的原题库文本]"))
    }

    @Test
    fun formatRepairPromptKeepsFormulasVerbatim() {
        val prompt = CustomQuestionBankManager.getFormatRepairPrompt("x")
        assertTrue(prompt.contains("公式写法"))
        assertTrue(prompt.contains("一律原样保留"))
    }

    @Test
    fun questionsWrittenInPromptNotationSurviveImportVerbatim() {
        val md = """
            ## 1. 极限 \lim_{x\to\infty} (1+\frac{2}{x})^x 等于
            - A. e^{2}
            - B. e
            - C. \sqrt{e}
            - D. H_2O
            答案: A
            解析: 令 t=x/2，则原式 = e^{2}。
        """.trimIndent()

        val result = com.decikeeper.app.manager.questionbank.CustomQuestionBankParser.parse(md)

        assertTrue(result.issues.isEmpty())
        val q = result.successQuestions.single()
        assertTrue(q.stem.contains("\\lim_{x\\to\\infty} (1+\\frac{2}{x})^x"))
        assertTrue(q.options == listOf("e^{2}", "e", "\\sqrt{e}", "H_2O"))
        assertTrue(q.explanation.contains("e^{2}"))
    }
}
