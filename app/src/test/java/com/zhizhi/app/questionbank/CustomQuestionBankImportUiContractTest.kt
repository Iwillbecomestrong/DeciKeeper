package com.zhizhi.app.questionbank

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CustomQuestionBankImportUiContractTest {
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
    fun importScreenExposesFormatRepairPromptForCurrentContent() {
        val layout = source("src/main/res/layout/activity_custom_question_import.xml")
        val activity = source("src/main/java/com/zhizhi/app/ui/questionbank/CustomQuestionBankImportActivity.kt")

        assertTrue(layout.contains("@+id/btn_copy_repair_prompt"))
        assertTrue(layout.contains("格式修复 Prompt"))
        assertTrue(activity.contains("R.id.btn_copy_repair_prompt"))
        assertTrue(activity.contains("CustomQuestionBankManager.getFormatRepairPrompt(source)"))
        assertTrue(activity.contains("etContent.text"))
    }
}
