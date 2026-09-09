package com.zhizhi.app.data.questionbank

import java.util.UUID

/**
 * 自定义题库单道题目模型。
 * 遵循通用四选一单选规格：题干/短文 + 4个选项 + 答案 + 解析/回顾短文。
 */
data class CustomQuestion(
    val id: String = UUID.randomUUID().toString(),
    val bankId: String = "",
    val stem: String,                       // 题干内容或背景短文
    val options: List<String>,              // 4个选项文本（不带 A. B. 等前缀）
    val answer: String,                     // 标准答案选项标识，大写字母 "A", "B", "C", "D"
    val explanation: String = "",           // 详细解析或完整背景回顾（仅在关闹钟后的复习回顾页展示）
    val hint: String = "",                  // 提示文本（可选，仅当用户显式输入「提示:」时存在，绝不能把答案当成提示）
    val wrongCount: Int = 0,                // 累计答错次数
    val lastAttemptStatus: String? = null,  // 最近一次作答状态: "correct", "wrong", "skipped"
    val lastAttemptAt: Long = 0L,           // 最近一次作答时间戳
    val isFavorite: Boolean = false         // 是否已加入收藏
) {
    /** 是否为多选题 */
    val isMultipleChoice: Boolean get() = answer.trim().length > 1

    /** 获取正确答案对应的选项内容文本 */
    fun getAnswerContent(): String {
        val indices = answer.trim().uppercase().mapNotNull { ch ->
            when (ch) {
                'A' -> 0
                'B' -> 1
                'C' -> 2
                'D' -> 3
                else -> null
            }
        }
        if (indices.isEmpty()) return ""
        return indices.mapNotNull { options.getOrNull(it) }.joinToString("；")
    }
}

/**
 * 自定义题库实体。
 */
data class CustomQuestionBank(
    val id: String = UUID.randomUUID().toString(),
    val title: String,                      // 题库名称，如“考研政治近代史1000题”
    val description: String = "",           // 题库简介或备注
    val enabled: Boolean = true,            // 是否启用参与闹钟随机出题
    val showInBookmark: Boolean = true,     // 是否在收藏夹中生成独立 Tab
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val questions: List<CustomQuestion> = emptyList()
) {
    /** 统计错题数量（做错至少1次的题目） */
    val mistakeCount: Int get() = questions.count { it.wrongCount > 0 }

    /** 统计收藏题目数量 */
    val favoriteCount: Int get() = questions.count { it.isFavorite }
}

/**
 * 单条解析告警信息，指示具体行号与原因。
 */
data class ParseIssue(
    val line: Int,
    val message: String
)

/**
 * 文本/文件解析结果封装。
 */
data class ParseResult(
    val successQuestions: List<CustomQuestion>,
    val issues: List<ParseIssue> = emptyList()
) {
    val totalParsed: Int get() = successQuestions.size
    val hasIssues: Boolean get() = issues.isNotEmpty()
}
