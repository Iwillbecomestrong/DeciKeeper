package com.ironwill.app

import com.ironwill.app.manager.AlarmChallengeManager
import org.junit.Assert.assertEquals
import org.junit.Test



/**
 * Cloze 选词填空核心纯函数单元测试。
 *
 * 本文件覆盖 ClozeUxTest.kt 尚未覆盖的纯逻辑函数：
 * - getSafeOptionMeaning：释义前缀去重边界（R8 补充）
 * - cleanClozeSentence：圈号序号清理
 * - extractExcerptTranslation：篇章分析/答案精解提取
 *
 * 纯 JVM 测试，无 Robolectric 依赖，不涉及 Context/assets 加载。
 */
class ClozeManagerTest {

    // ========================================================================
    // getSafeOptionMeaning — 去前缀边界（ClozeUxTest 已覆盖基础/空/null/相等/越界，此处补充去前缀）
    // ========================================================================

    @Test
    fun `getSafeOptionMeaning 释义以单词开头后跟中文去除英文前缀`() {
        // R8: raw = "complex复杂的", word = "complex"
        // raw.startsWith(word, ignoreCase=true) → remainder = "复杂的" → 返回 "复杂的"
        val result = AlarmChallengeManager.getSafeOptionMeaning(
            optionMeanings = listOf("complex复杂的", "b", "c", "d"),
            idx = 0,
            word = "complex"
        )
        assertEquals("复杂的", result)
    }

    @Test
    fun `getSafeOptionMeaning 释义以单词开头大小写不一致仍去除前缀`() {
        // R8: ignoreCase = true 应用于 startsWith
        val result = AlarmChallengeManager.getSafeOptionMeaning(
            optionMeanings = listOf("COMPLEX复杂的", "b", "c", "d"),
            idx = 0,
            word = "complex"
        )
        assertEquals("复杂的", result)
    }

    @Test
    fun `getSafeOptionMeaning 释义前缀之后仅有空白返回原文`() {
        // raw = "complex  ", word = "complex"
        // startsWith → true, remainder after trim = "" → 返回 raw
        val result = AlarmChallengeManager.getSafeOptionMeaning(
            optionMeanings = listOf("complex  ", "b", "c", "d"),
            idx = 0,
            word = "complex"
        )
        assertEquals("complex  ", result)
    }

    // ========================================================================
    // cleanClozeSentence — 去圈号序号
    // ========================================================================

    @Test
    fun `cleanClozeSentence 去除圈号序号`() {
        val result = AlarmChallengeManager.cleanClozeSentence("①Hello ②world")
        assertEquals("Hello world", result)
    }

    @Test
    fun `cleanClozeSentence 无序号文本保持不变`() {
        assertEquals("Hello world", AlarmChallengeManager.cleanClozeSentence("Hello world"))
    }

    @Test
    fun `cleanClozeSentence 全圈号变空`() {
        assertEquals("", AlarmChallengeManager.cleanClozeSentence("①②③④⑤⑥⑦⑧⑨⑩"))
    }

    @Test
    fun `cleanClozeSentence 空字符串`() {
        assertEquals("", AlarmChallengeManager.cleanClozeSentence(""))
    }

    @Test
    fun `cleanClozeSentence 混合圈号和普通标点数字`() {
        // 圈号删除，普通标点数字保留
        val result = AlarmChallengeManager.cleanClozeSentence("①Step 1: ②Step 2.")
        assertEquals("Step 1: Step 2.", result)
    }

    @Test
    fun `cleanClozeSentence 圈号在词中不误删`() {
        // 只删除圈号数字，不碰普通文本
        val result = AlarmChallengeManager.cleanClozeSentence("text①text")
        assertEquals("texttext", result)
    }

    // ========================================================================
    // extractExcerptTranslation — 篇章分析提取
    // ========================================================================

    @Test
    fun `extractExcerptTranslation 包含篇章分析返回该段内容`() {
        val input = "［篇章分析］本文讨论了环境保护的重要性。［答案精解］1. 答案是A。"
        val result = AlarmChallengeManager.extractExcerptTranslation(input)
        assertEquals("本文讨论了环境保护的重要性。", result)
    }

    @Test
    fun `extractExcerptTranslation 无篇章分析也无答案精解返回空`() {
        assertEquals("", AlarmChallengeManager.extractExcerptTranslation("普通文本，没有任何标记。"))
    }

    @Test
    fun `extractExcerptTranslation 空字符串返回空`() {
        assertEquals("", AlarmChallengeManager.extractExcerptTranslation(""))
    }

    @Test
    fun `extractExcerptTranslation 仅答案精解长文本截断并加省略号`() {
        // 首行 > 20 字：取前 200 字 + "…"
        val longLine = "A".repeat(50)
        val input = "［答案精解］1. $longLine"
        val result = AlarmChallengeManager.extractExcerptTranslation(input)
        assertEquals(longLine.take(200) + "…", result)
    }

    @Test
    fun `extractExcerptTranslation 仅答案精解短文本原样返回`() {
        // 首行 <= 20 字：直接返回
        val input = "［答案精解］1. 简短的答案。"
        val result = AlarmChallengeManager.extractExcerptTranslation(input)
        assertEquals("简短的答案。", result)
    }

    @Test
    fun `extractExcerptTranslation 篇章分析段跨越多行合并返回`() {
        // DOT_MATCHES_ALL 确保跨行匹配
        val input = "［篇章分析］第一行内容。\n第二行内容。［答案精解］1. 答案。"
        val result = AlarmChallengeManager.extractExcerptTranslation(input)
        assertEquals("第一行内容。\n第二行内容。", result)
    }
}
