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
}
