package com.zhizhi.app

import com.zhizhi.app.manager.AlarmChallengeManager
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test



/**
 * v3.1 复习回顾翻译 — 纯函数 + parseOneClozeItem 单测
 *
 * TDD 先行: 本文件先于实现写。`cleanTranslation` / `splitExcerptBySentence` 在
 * AlarmChallengeManager.kt 暂未实现,跑测试应先 RED 后 GREEN。
 *
 * 覆盖范围:
 * - cleanTranslation 各种输入(异常换行/多空格/首尾空白/全角半角)
 * - splitExcerptBySentence 3 句对应拆分
 * - parseOneClozeItem 读 passage.zh 兜底 (Bug-3 根因修复)
 */
class ReviewTranslationTest {

    @After
    fun teardown() {
        AlarmChallengeManager.resetClozePoolForTest()
    }

    // ========================================================================
    // cleanTranslation — 清洗翻译字符串
    // ========================================================================

    @Test
    fun `cleanTranslation 异常换行替换为空格`() {
        // Bug-3: "百分\n4\n分" → "百分 4 分"
        assertEquals("百分 4 分", AlarmChallengeManager.cleanTranslation("百分\n4\n分"))
    }

    @Test
    fun `cleanTranslation 多个连续空格合并为一个`() {
        assertEquals("Multiple spaces", AlarmChallengeManager.cleanTranslation("Multiple    spaces"))
    }

    @Test
    fun `cleanTranslation 首尾空白去掉`() {
        assertEquals("leading and trailing", AlarmChallengeManager.cleanTranslation("  leading and trailing  "))
    }

    @Test
    fun `cleanTranslation 空字符串返回空`() {
        assertEquals("", AlarmChallengeManager.cleanTranslation(""))
    }

    @Test
    fun `cleanTranslation 正常文本不变`() {
        assertEquals("Normal text", AlarmChallengeManager.cleanTranslation("Normal text"))
    }

    @Test
    fun `cleanTranslation 末尾换行清掉`() {
        assertEquals("百分 4 分", AlarmChallengeManager.cleanTranslation("百分\n4\n分\n"))
    }

    @Test
    fun `cleanTranslation 多行合并为单行`() {
        assertEquals("Line1 Line2 Line3", AlarmChallengeManager.cleanTranslation("Line1\nLine2\nLine3"))
    }

    @Test
    fun `cleanTranslation Tab 也清洗`() {
        // 防御: 制表符也算空白异常
        assertEquals("a b", AlarmChallengeManager.cleanTranslation("a\tb"))
    }

    // ========================================================================
    // splitExcerptBySentence — 把节选翻译按 3 句拆分
    // ========================================================================

    @Test
    fun `splitExcerptBySentence 英文3句 + 中文3句 一一对应`() {
        val english = "First sentence here. Second sentence here. Third sentence here."
        val chinese = "第一句话。 第二句话。 第三句话。"
        val result = AlarmChallengeManager.splitExcerptBySentence(english, chinese)
        assertEquals(3, result.size)
        assertEquals("First sentence here.", result[0].first)
        assertEquals("第一句话。", result[0].second)
        assertEquals("Second sentence here.", result[1].first)
        assertEquals("第二句话。", result[1].second)
        assertEquals("Third sentence here.", result[2].first)
        assertEquals("第三句话。", result[2].second)
    }

    @Test
    fun `splitExcerptBySentence 问号也算句边界`() {
        val english = "Are you OK? Yes, I am. Bye."
        val chinese = "你还好吗？ 是的。 再见。"
        val result = AlarmChallengeManager.splitExcerptBySentence(english, chinese)
        assertEquals(3, result.size)
        assertEquals("Are you OK?", result[0].first)
        assertEquals("你还好吗？", result[0].second)
    }

    @Test
    fun `splitExcerptBySentence 感叹号也算句边界`() {
        val english = "Wow! That's great! Indeed."
        val chinese = "哇！ 太好了！ 确实。"
        val result = AlarmChallengeManager.splitExcerptBySentence(english, chinese)
        assertEquals(3, result.size)
        assertEquals("Wow!", result[0].first)
        assertEquals("哇！", result[0].second)
    }

    @Test
    fun `splitExcerptBySentence 中英文都只有 1 句`() {
        val english = "Only one sentence."
        val chinese = "只有一句话。"
        val result = AlarmChallengeManager.splitExcerptBySentence(english, chinese)
        assertEquals(1, result.size)
        assertEquals("Only one sentence.", result[0].first)
        assertEquals("只有一句话。", result[0].second)
    }

    @Test
    fun `splitExcerptBySentence 中英文数量不匹配时按较少者返回`() {
        val english = "First. Second. Third."
        val chinese = "只有一句。"
        val result = AlarmChallengeManager.splitExcerptBySentence(english, chinese)
        // 英文 3 句,中文 1 句 → 防御:只返回 1 对(以较少者为准)
        assertEquals(1, result.size)
    }

    @Test
    fun `splitExcerptBySentence 空输入返回空列表`() {
        val result = AlarmChallengeManager.splitExcerptBySentence("", "")
        assertTrue(result.isEmpty())
    }

    // ========================================================================
    // parseOneClozeItem 读 passage.zh 兜底 — Bug-3 根因修复
    // ========================================================================

    @Test
    fun `parseOneClozeItem passage zh 兜底 passageTranslation 字段`() {
        // JSON 有 passage.zh 但没有 passageTranslation 字段
        val obj = JSONObject("""
            {
              "context_three_sentences": "Test sentence with ____ blank.",
              "options": {"A": "alpha", "B": "beta", "C": "gamma", "D": "delta"},
              "option_meanings_zh": {"A": "甲", "B": "乙", "C": "丙", "D": "丁"},
              "answer_text": "alpha",
              "year": 1986,
              "blank_index": 1,
              "passage": {"zh": "测试句子带空格。"}
            }
        """.trimIndent())

        val json = """{"items":[${obj}]}"""
        AlarmChallengeManager.loadClozePoolFromJsonString(json)
        val q = AlarmChallengeManager.clozePoolSnapshot.firstOrNull()
        assertNotNull("应加载 1 题", q)
        assertEquals("passage.zh 兜底成功", "测试句子带空格。", q!!.passageTranslation)
    }

    @Test
    fun `parseOneClozeItem 优先用 passageTranslation 顶层字段`() {
        // 有 passageTranslation 顶层字段,优先用
        val obj = JSONObject("""
            {
              "context_three_sentences": "Test sentence.",
              "options": {"A": "a", "B": "b", "C": "c", "D": "d"},
              "option_meanings_zh": {"A": "甲", "B": "乙", "C": "丙", "D": "丁"},
              "answer_text": "a",
              "passageTranslation": "顶层翻译",
              "passage": {"zh": "兜底翻译"}
            }
        """.trimIndent())

        val json = """{"items":[${obj}]}"""
        AlarmChallengeManager.loadClozePoolFromJsonString(json)
        val q = AlarmChallengeManager.clozePoolSnapshot.firstOrNull()!!
        assertEquals("顶层 passageTranslation 优先", "顶层翻译", q.passageTranslation)
    }

    @Test
    fun `parseOneClozeItem passage_translation 下划线版本也支持`() {
        val obj = JSONObject("""
            {
              "context_three_sentences": "Test sentence.",
              "options": {"A": "a", "B": "b", "C": "c", "D": "d"},
              "option_meanings_zh": {"A": "甲", "B": "乙", "C": "丙", "D": "丁"},
              "answer_text": "a",
              "passage_translation": "下划线版本"
            }
        """.trimIndent())

        val json = """{"items":[${obj}]}"""
        AlarmChallengeManager.loadClozePoolFromJsonString(json)
        val q = AlarmChallengeManager.clozePoolSnapshot.firstOrNull()!!
        assertEquals("下划线版本", q.passageTranslation)
    }

    @Test
    fun `parseOneClozeItem passage 为空时 passageTranslation 留空`() {
        val obj = JSONObject("""
            {
              "context_three_sentences": "Test sentence.",
              "options": {"A": "a", "B": "b", "C": "c", "D": "d"},
              "option_meanings_zh": {"A": "甲", "B": "乙", "C": "丙", "D": "丁"},
              "answer_text": "a",
              "passage": {}
            }
        """.trimIndent())

        val json = """{"items":[${obj}]}"""
        AlarmChallengeManager.loadClozePoolFromJsonString(json)
        val q = AlarmChallengeManager.clozePoolSnapshot.firstOrNull()!!
        assertEquals("", q.passageTranslation)
    }
}
