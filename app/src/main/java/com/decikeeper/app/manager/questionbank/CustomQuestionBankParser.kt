package com.decikeeper.app.manager.questionbank

import com.decikeeper.app.data.questionbank.CustomQuestion
import com.decikeeper.app.data.questionbank.ParseIssue
import com.decikeeper.app.data.questionbank.ParseResult
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * 纯 Kotlin 实现的多格式（Markdown / 纯文本 / JSON）题库解析引擎。
 *
 * 容错特性：
 * 1. 标题支持 `## `、`### `、`1. `、`1、`、`【第1题】` 等多种前缀，或 `---` 分割线。
 * 2. 选项支持 `A.`、`A、`、`A:`、`A：`、`A)`、`A）`、`- A.`、`· A.`、`• A.`、`**A.**`、`[A]`、`(A)`、`【A】` 等前缀（不区分大小写，并兼容全角 A-D）。
 * 3. 答案支持 `答案:`、`正确答案:`、`参考答案:`、`【答案】`、`Answer:`、`Key:` 等前缀，自动提取 A/B/C/D。
 * 4. 解析支持 `解析:`、`解析：`、`【解析】`、`Explanation:` 等，支持多行长文。
 * 5. 提示支持 `提示:`、`提示：`、`【提示】`、`Hint:` 等（选填，绝不输出答案）。
 * 6. 自动忽略 AI 常见的 Markdown 代码块围栏，并兼容 NBSP / 全角空格。
 * 7. 原生支持标准 JSON 文件或 JSON 数组文本解析。
 */
object CustomQuestionBankParser {

    private val OPTION_REGEX = Regex(
        """^\s*(?:>\s*)?(?:[-*+]\s+|[•·●▪◦‣]\s*)?(?:\*\*|__)?\s*(?:(?:\(|\[|【|（)([A-Da-dＡ-Ｄａ-ｄ])(?:\)|\]|】|）)|([A-Da-dＡ-Ｄａ-ｄ])[\.．、:：\)）\s])\s*(?:\*\*|__)?\s*(.+?)\s*(?:\*\*|__)?\s*$"""
    )
    private val ANSWER_REGEX = Regex(
        """^\s*(?:[-*+]\s+|[•·●▪◦‣]\s*)?(?:【?(?:答案|正确答案|参考答案)】?|Answer|Key)\s*(?:[:：]|是)?\s*([A-Da-dＡ-Ｄａ-ｄ\s,，、/]+.*)""",
        RegexOption.IGNORE_CASE
    )
    private val EXPLANATION_HEADER_REGEX = Regex(
        """^\s*(?:[-*+]\s+|[•·●▪◦‣]\s*)?(?:【?(?:解析|详解|分析)】?|Explanation|Analysis)\s*[:：]?(.*)""",
        RegexOption.IGNORE_CASE
    )
    private val HINT_HEADER_REGEX = Regex(
        """^\s*(?:[-*+]\s+|[•·●▪◦‣]\s*)?(?:【?(?:提示|线索|Hint)】?)\s*[:：]?(.*)""",
        RegexOption.IGNORE_CASE
    )
    private val QUESTION_PREFIX_REGEX = Regex(
        """^(?:#{1,6}\s+|(?:【第?\s*\d+\s*(?:题|道)?】|第\s*\d+\s*(?:题|道)[:：、\.]?|\d+[\.、\)）]|Q\d+[:：\.\s]?)\s*)""",
        RegexOption.IGNORE_CASE
    )
    private val DIVIDER_REGEX = Regex("""^\s*(?:---+|\*\*\*+|===+)\s*$""")
    private val CODE_FENCE_REGEX = Regex("""^\s*(?:```+|~~~+)(?:[A-Za-z0-9_-]+)?\s*$""")

    private fun normalizeInputLine(line: String): String = line
        .replace('\u00A0', ' ')
        .replace('\u3000', ' ')
        .trim()

    fun normalizeChoiceLetter(letter: String): String {
        if (letter.isEmpty()) return letter
        val ch = letter[0]
        val normalized = when (ch) {
            'Ａ' -> 'A'
            'Ｂ' -> 'B'
            'Ｃ' -> 'C'
            'Ｄ' -> 'D'
            'ａ' -> 'a'
            'ｂ' -> 'b'
            'ｃ' -> 'c'
            'ｄ' -> 'd'
            else -> ch
        }
        return normalized.uppercaseChar().toString()
    }

    /**
     * 规范化答案（支持单选及多选组合，去重并排序），例如：
     * "B" -> "B", "A, C" -> "AC", "B、A" -> "AB", "BCD" -> "BCD"
     */
    fun normalizeAnswer(raw: String): String {
        val set = mutableSetOf<Char>()
        for (ch in raw) {
            val norm = when (ch) {
                'Ａ', 'ａ', 'A', 'a' -> 'A'
                'Ｂ', 'ｂ', 'B', 'b' -> 'B'
                'Ｃ', 'ｃ', 'C', 'c' -> 'C'
                'Ｄ', 'ｄ', 'D', 'd' -> 'D'
                else -> null
            }
            if (norm != null) set.add(norm)
        }
        return set.sorted().joinToString("")
    }

    private fun cleanStem(line: String): String {
        val noHash = line.replace(Regex("""^\s*#{1,6}\s*"""), "").trim()
        return noHash.replace(
            Regex("""^(?:【第?\s*\d+\s*(?:题|道)?】|第\s*\d+\s*(?:题|道)[:：、\.]?|\d+[\.、\)）]|Q\d+[:：\.\s]?)\s*"""),
            ""
        ).trim()
    }

    /**
     * 智能入口：自动识别 JSON 或 Markdown / 纯文本并执行解析。
     */
    fun parseContent(text: String, bankId: String = ""): ParseResult {
        val trimmed = text.trim()
        if (trimmed.startsWith("[") || (trimmed.startsWith("{") && trimmed.contains("\"questions\""))) {
            try {
                val jsonResult = parseJson(trimmed, bankId)
                if (jsonResult.successQuestions.isNotEmpty()) {
                    return jsonResult
                }
            } catch (_: Exception) {
                // 回退到 Markdown 解析
            }
        }
        return parse(text, bankId)
    }

    /**
     * 解析标准 JSON 格式题库。
     */
    fun parseJson(jsonText: String, bankId: String = ""): ParseResult {
        if (jsonText.isBlank()) return ParseResult(emptyList(), emptyList())
        val questions = mutableListOf<CustomQuestion>()
        val issues = mutableListOf<ParseIssue>()

        try {
            val jsonArray = if (jsonText.trim().startsWith("[")) {
                JSONArray(jsonText.trim())
            } else {
                val rootObj = JSONObject(jsonText.trim())
                rootObj.optJSONArray("questions") ?: JSONArray()
            }

            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.optJSONObject(i)
                if (obj == null) {
                    issues.add(ParseIssue(i + 1, "第 ${i + 1} 项非有效 JSON 对象"))
                    continue
                }
                val stem = (obj.optString("stem").ifEmpty { obj.optString("question").ifEmpty { obj.optString("title") } }).trim()
                val rawAnswer = (obj.optString("answer").ifEmpty { obj.optString("key") }).trim()
                val answer = normalizeAnswer(rawAnswer)
                val explanation = (obj.optString("explanation").ifEmpty { obj.optString("analysis") }).trim()
                val hint = (obj.optString("hint").ifEmpty { obj.optString("clue") }).trim()

                val options = mutableListOf<String>()
                val optArr = obj.optJSONArray("options")
                if (optArr != null) {
                    for (j in 0 until optArr.length()) {
                        val optText = optArr.optString(j, "").trim()
                        val cleaned = optText.replace(Regex("""^[A-Da-dＡ-Ｄａ-ｄ][\.．、:：\)）\s]\s*"""), "")
                        options.add(cleaned)
                    }
                } else {
                    val optObj = obj.optJSONObject("options") ?: obj
                    listOf("A", "B", "C", "D").forEach { k ->
                        val optText = optObj.optString(k, "").ifEmpty { optObj.optString(k.lowercase(), "") }.trim()
                        if (optText.isNotEmpty()) options.add(optText)
                    }
                }

                if (stem.isBlank()) {
                    issues.add(ParseIssue(i + 1, "第 ${i + 1} 题缺少题干内容 (stem)"))
                } else if (options.size < 3) {
                    issues.add(ParseIssue(i + 1, "第 ${i + 1} 题选项不足 3 个（当前 ${options.size} 个）"))
                } else if (answer.isBlank()) {
                    issues.add(ParseIssue(i + 1, "第 ${i + 1} 题缺少有效答案（须为 A/B/C/D 或多选组合）"))
                } else if (options.size == 3 && answer.contains("D")) {
                    issues.add(ParseIssue(i + 1, "第 ${i + 1} 题仅有 3 个选项，但答案包含了 D"))
                } else {
                    questions.add(
                        CustomQuestion(
                            id = UUID.randomUUID().toString(),
                            bankId = bankId,
                            stem = stem,
                            options = options.take(4),
                            answer = answer,
                            explanation = explanation,
                            hint = hint
                        )
                    )
                }
            }
        } catch (e: Exception) {
            issues.add(ParseIssue(1, "JSON 解析错误: ${e.message}"))
        }

        return ParseResult(questions, issues)
    }

    /**
     * 解析 Markdown 或纯文本题库。
     */
    fun parse(text: String, bankId: String = ""): ParseResult {
        if (text.isBlank()) {
            return ParseResult(emptyList(), emptyList())
        }

        val lines = text.lines()
        val questions = mutableListOf<CustomQuestion>()
        val issues = mutableListOf<ParseIssue>()

        var currentStartLine = 1
        var currentStemLines = mutableListOf<String>()
        var currentOptions = mutableMapOf<String, String>()
        var currentAnswer: String? = null
        var currentExplanationLines = mutableListOf<String>()
        var currentHintLines = mutableListOf<String>()
        var inExplanation = false
        var inHint = false
        var hasActiveQuestion = false

        fun commitCurrentQuestion() {
            if (!hasActiveQuestion) return

            val stem = currentStemLines.joinToString("\n").trim()
            val answer = currentAnswer
            val explanation = currentExplanationLines.joinToString("\n").trim()
            val hint = currentHintLines.joinToString("\n").trim()

            if (stem.isBlank()) {
                issues.add(ParseIssue(currentStartLine, "题目缺少题干内容"))
            } else {
                val opts = listOfNotNull(
                    currentOptions["A"],
                    currentOptions["B"],
                    currentOptions["C"],
                    currentOptions["D"]
                )
                when {
                    opts.size < 3 -> {
                        val missing = listOf("A", "B", "C").filterNot { currentOptions.containsKey(it) }
                        issues.add(
                            ParseIssue(
                                currentStartLine,
                                "选项不足3个（缺少: ${missing.joinToString(", ")}，目前找到 ${opts.size} 个选项）"
                            )
                        )
                    }
                    answer.isNullOrBlank() -> {
                        issues.add(
                            ParseIssue(
                                currentStartLine,
                                "未找到有效答案（支持格式：答案: A/B/C/D 或多选组合如 AB）"
                            )
                        )
                    }
                    opts.size == 3 && answer.contains("D") -> {
                        issues.add(
                            ParseIssue(
                                currentStartLine,
                                "题目仅有3个选项(A/B/C)，但答案包含了选项 D"
                            )
                        )
                    }
                    else -> {
                        questions.add(
                            CustomQuestion(
                                id = UUID.randomUUID().toString(),
                                bankId = bankId,
                                stem = stem,
                                options = opts.take(4),
                                answer = answer,
                                explanation = explanation,
                                hint = hint
                            )
                        )
                    }
                }
            }

            currentStemLines = mutableListOf()
            currentOptions = mutableMapOf()
            currentAnswer = null
            currentExplanationLines = mutableListOf()
            currentHintLines = mutableListOf()
            inExplanation = false
            inHint = false
            hasActiveQuestion = false
        }

        for (i in lines.indices) {
            val lineNo = i + 1
            val trimmed = normalizeInputLine(lines[i])

            if (trimmed.isEmpty()) {
                if (inExplanation && currentExplanationLines.isNotEmpty()) {
                    currentExplanationLines.add("")
                } else if (inHint && currentHintLines.isNotEmpty()) {
                    currentHintLines.add("")
                }
                continue
            }

            // AI 经常无视 Prompt 包一层 ```markdown / ```，结构上直接忽略即可。
            if (CODE_FENCE_REGEX.matches(trimmed)) {
                continue
            }

            // 分割线支持 --- / *** / ===
            if (DIVIDER_REGEX.matches(trimmed)) {
                commitCurrentQuestion()
                continue
            }

            // 1. 优先检查答案
            val ansMatch = ANSWER_REGEX.find(trimmed)
            if (ansMatch != null) {
                hasActiveQuestion = true
                inExplanation = false
                inHint = false
                currentAnswer = normalizeAnswer(ansMatch.groupValues[1])
                continue
            }

            // 2. 检查解析头
            val expMatch = EXPLANATION_HEADER_REGEX.find(trimmed)
            if (expMatch != null) {
                hasActiveQuestion = true
                inExplanation = true
                inHint = false
                val firstLineExp = expMatch.groupValues[1].trim()
                if (firstLineExp.isNotEmpty()) {
                    currentExplanationLines.add(firstLineExp)
                }
                continue
            }

            // 3. 检查提示头
            val hintMatch = HINT_HEADER_REGEX.find(trimmed)
            if (hintMatch != null) {
                hasActiveQuestion = true
                inHint = true
                inExplanation = false
                val firstLineHint = hintMatch.groupValues[1].trim()
                if (firstLineHint.isNotEmpty()) {
                    currentHintLines.add(firstLineHint)
                }
                continue
            }

            // 4. 检查是否为新题目开始
            val hasQPrefix = QUESTION_PREFIX_REGEX.containsMatchIn(trimmed)
            val isExplicitNewHeader = trimmed.startsWith("#") ||
                (hasActiveQuestion && (currentAnswer != null || currentOptions.size >= 3) && hasQPrefix)

            if (isExplicitNewHeader) {
                commitCurrentQuestion()
                currentStartLine = lineNo
                hasActiveQuestion = true
                currentStemLines.add(cleanStem(trimmed))
                continue
            }

            // 5. 检查选项
            val optMatch = OPTION_REGEX.find(trimmed)
            if (optMatch != null && ((!inExplanation && !inHint) || currentOptions.size < 4)) {
                hasActiveQuestion = true
                inExplanation = false
                inHint = false
                val rawLetter = optMatch.groupValues[1].ifEmpty { optMatch.groupValues[2] }
                val letter = normalizeChoiceLetter(rawLetter)
                val content = optMatch.groupValues[3].trim()
                currentOptions[letter] = content
                continue
            }

            // 6. 普通内容行
            if (inExplanation) {
                currentExplanationLines.add(trimmed)
            } else if (inHint) {
                currentHintLines.add(trimmed)
            } else if (currentOptions.isEmpty() && currentAnswer == null) {
                if (!hasActiveQuestion) {
                    currentStartLine = lineNo
                    hasActiveQuestion = true
                    currentStemLines.add(cleanStem(trimmed))
                } else {
                    currentStemLines.add(trimmed)
                }
            }
        }

        commitCurrentQuestion()
        return ParseResult(questions, issues)
    }
}
