package com.zhizhi.app.questionbank

import com.zhizhi.app.manager.questionbank.CustomQuestionBankParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CustomQuestionBankParserTest {

    @Test
    fun testParseStandardMarkdown() {
        val markdown = """
            ## 1. 唯物辩证法的实质和核心是？
            - A. 质量互变规律
            - B. 对立统一规律
            - C. 否定之否定规律
            - D. 联系与发展的观点
            答案: B
            解析: 对立统一规律揭示了普遍联系的根本内容和变化发展的内在动力。

            ## 2. 认识的本质是？
            - A. 主体对客体的直观反映
            - B. 主体对客体的能动反映
            - C. 主体头脑中固有的
            - D. 客体对主体的决定
            答案: B
            解析: 辩证唯物主义认识论认为，认识是主体在实践基础上对客体的能动反映。
        """.trimIndent()

        val result = CustomQuestionBankParser.parse(markdown, "bank_1")
        assertEquals(2, result.totalParsed)
        assertFalse(result.hasIssues)

        val q1 = result.successQuestions[0]
        assertEquals("唯物辩证法的实质和核心是？", q1.stem)
        assertEquals(4, q1.options.size)
        assertEquals("质量互变规律", q1.options[0])
        assertEquals("对立统一规律", q1.options[1])
        assertEquals("否定之否定规律", q1.options[2])
        assertEquals("联系与发展的观点", q1.options[3])
        assertEquals("B", q1.answer)
        assertEquals("对立统一规律", q1.getAnswerContent())
        assertTrue(q1.explanation.contains("对立统一规律揭示了"))

        val q2 = result.successQuestions[1]
        assertEquals("认识的本质是？", q2.stem)
        assertEquals("B", q2.answer)
    }

    @Test
    fun testParseVariousOptionAndAnswerFormats() {
        val markdown = """
            1、中国共产党成立的时间是？
            A、1919年
            B、1921年
            C、1949年
            D、1978年
            【答案】：B
            【解析】：中国共产党第一次全国代表大会于1921年7月23日在上海召开。

            2. 计算机网络中 HTTP 协议的默认端口是？
            A. 21
            B. 22
            C. 80
            D. 443
            Answer: C
            Explanation: HTTP默认80，HTTPS默认443。
        """.trimIndent()

        val result = CustomQuestionBankParser.parse(markdown)
        assertEquals(2, result.totalParsed)
        assertFalse(result.hasIssues)

        val q1 = result.successQuestions[0]
        assertEquals("B", q1.answer)
        assertEquals("1921年", q1.getAnswerContent())

        val q2 = result.successQuestions[1]
        assertEquals("C", q2.answer)
        assertEquals("80", q2.getAnswerContent())
    }

    @Test
    fun testMultiLineStemAndExplanation() {
        val markdown = """
            ## 1. 请阅读以下背景材料并作答：
            2026年，人工智能与自动化工具在各行各业深度普及。
            在这一背景下，人的自律与专注力显得尤为珍贵。
            上述材料主要强调了什么？
            A. 科技取代人类思考
            B. 在技术变革中主体自觉与专注的重要性
            C. 应当完全拒绝使用人工智能
            D. 经济发展的唯一引擎是自律
            答案: B
            解析:
            本题考查主体自觉能动性。
            在外部条件高度便利时，主观专注能力与内在意志是决定成效的核心要素。
        """.trimIndent()

        val result = CustomQuestionBankParser.parse(markdown)
        assertEquals(1, result.totalParsed)
        val q = result.successQuestions[0]
        assertTrue(q.stem.contains("请阅读以下背景材料并作答："))
        assertTrue(q.stem.contains("在这一背景下，人的自律与专注力显得尤为珍贵。"))
        assertEquals("B", q.answer)
        assertTrue(q.explanation.contains("本题考查主体自觉能动性。"))
        assertTrue(q.explanation.contains("主观专注能力与内在意志"))
    }

    @Test
    fun testFaultToleranceForMissingOptionsOrAnswer() {
        val markdown = """
            ## 1. 正常题目
            A. 选项1
            B. 选项2
            C. 选项3
            D. 选项4
            答案: A
            解析: 正常

            ## 2. 残缺选项题目（只有3个选项）
            A. 选项A
            B. 选项B
            C. 选项C
            答案: B
            解析: 缺少D选项

            ## 3. 缺少答案题目
            A. 选项A
            B. 选项B
            C. 选项C
            D. 选项D
            解析: 忘记写答案了

            ## 4. 正常题目2
            A. 选项1
            B. 选项2
            C. 选项3
            D. 选项4
            答案: D
            解析: 依然能成功解析
        """.trimIndent()

        val result = CustomQuestionBankParser.parse(markdown)
        // 题目1和题目4成功，题目2和题目3失败并记录 issue
        assertEquals(2, result.totalParsed)
        assertTrue(result.hasIssues)
        assertEquals(2, result.issues.size)

        val issue1 = result.issues[0]
        assertTrue(issue1.message.contains("选项不足4个"))

        val issue2 = result.issues[1]
        assertTrue(issue2.message.contains("未找到有效答案"))

        assertEquals("正常题目", result.successQuestions[0].stem)
        assertEquals("正常题目2", result.successQuestions[1].stem)
    }

    @Test
    fun testEmptyInput() {
        val result = CustomQuestionBankParser.parse("   \n\n  ")
        assertEquals(0, result.totalParsed)
        assertFalse(result.hasIssues)
    }

    @Test
    fun testParseMarkdownWithHint() {
        val markdown = """
            ## 1. 计算机中用于存储正在执行程序的内存是？
            A. ROM
            B. RAM
            C. 硬盘
            D. 光盘
            答案: B
            提示: 随机存取存储器，断电后数据丢失
            解析: RAM (Random Access Memory) 是随机存取存储器，断电后内容消失。
        """.trimIndent()

        val result = CustomQuestionBankParser.parseContent(markdown, "bank_hint")
        assertEquals(1, result.totalParsed)
        val q = result.successQuestions[0]
        assertEquals("B", q.answer)
        assertEquals("随机存取存储器，断电后数据丢失", q.hint)
        assertTrue(q.explanation.contains("Random Access Memory"))
    }

    @Test
    fun testParseJsonFormat() {
        val json = """
            [
              {
                "stem": "操作系统的主要功能包括什么？",
                "options": ["仅管理硬件", "管理计算机硬件与软件资源", "仅运行应用程序", "仅提供图形界面"],
                "answer": "B",
                "hint": "资源调配与软硬件中介",
                "explanation": "操作系统是管理计算机硬件与软件资源的系统软件。"
              },
              {
                "stem": "TCP 协议在建立连接时需要几次握手？",
                "options": ["1次", "2次", "3次", "4次"],
                "answer": "C"
              }
            ]
        """.trimIndent()

        val result = CustomQuestionBankParser.parseContent(json, "bank_json")
        assertEquals(2, result.totalParsed)
        assertFalse(result.hasIssues)

        val q1 = result.successQuestions[0]
        assertEquals("操作系统的主要功能包括什么？", q1.stem)
        assertEquals(4, q1.options.size)
        assertEquals("B", q1.answer)
        assertEquals("资源调配与软硬件中介", q1.hint)
        assertTrue(q1.explanation.contains("系统软件"))

        val q2 = result.successQuestions[1]
        assertEquals("C", q2.answer)
        assertEquals("", q2.hint)
    }
}
