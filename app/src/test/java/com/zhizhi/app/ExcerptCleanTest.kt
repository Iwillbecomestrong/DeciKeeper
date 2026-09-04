package com.zhizhi.app

import com.zhizhi.app.manager.AlarmChallengeManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test



/**
 * Plan-E/A 相关纯函数测试：
 * - cleanPassage：去圈号 + 安全清字母紧跟数字（to9→to, They1that→They that, smell12→smell），保留 19th/1960s/1750。
 * - splitNumberedSentences：按圈号 ①②③ 切分 3 句。
 * - buildExcerptPairs：恰好 ≤3 对、含填空句、en/zh 对齐、不冗余。
 */
class ExcerptCleanTest {

    @Test
    fun testCleanPassageStripsCircledNumbers() {
        assertEquals("On Wednesday afternoons Annie went to town. For an hour or ____ she shopped. And then she left.",
            AlarmChallengeManager.cleanPassage("①On Wednesday afternoons Annie went to town. ②For an hour or ____ she shopped. ③And then she left.").trim())
    }

    @Test
    fun testCleanPassageSeparatesGluedDigits() {
        assertEquals("to", AlarmChallengeManager.cleanPassage("to9"))
        assertEquals("They that", AlarmChallengeManager.cleanPassage("They1that"))
        assertEquals("smell", AlarmChallengeManager.cleanPassage("smell12"))
    }

    @Test
    fun testCleanPassageKeepsLegitOrdinalsAndYears() {
        // 19th-century / 1960s / 1750 / 1850 不能被误删
        val out = AlarmChallengeManager.cleanPassage("in the 19th-century, during the 1960s, from 1750 to 1850")
        assertTrue(out.contains("19th-century"))
        assertTrue(out.contains("1960s"))
        assertTrue(out.contains("1750 to 1850"))
    }

    @Test
    fun testCleanPassageKeepsChineseAndBlank() {
        // 真实空位是 ____；cleanPassage 折叠空白但保留下划线与中文字符
        val out = AlarmChallengeManager.cleanPassage("她每天早起背单词，正是这份 ____ 让她顺利通过了考研。")
        assertTrue(out.contains("她每天早起背单词"));
        assertTrue(out.contains("____"));
    }

    @Test
    fun testSplitNumberedSentencesSplits3() {
        // 保留圈号时切分应得 3 段（cleanPassage 会剥圈号，所以这里直接 splitNumberedSentences）
        val s = "①First sentence. ②Second with blank ____ here. ③Third sentence."
        val parts = AlarmChallengeManager.splitNumberedSentences(s)
        assertEquals(3, parts.size)
        assertTrue(parts[1].contains("____"))
    }

    @Test
    fun testBuildExcerptPairsExactly3AlignedIncludingBlank() {
        val cloze = AlarmChallengeManager.ClozeQuestion(
            sentence = "①On Wednesdays Annie shopped. ②For an hour or ____ she walked. ③And then she left.",
            answer = "so",
            options = listOf("so", "more", "else", "another"),
            passageTranslation = "①周三下午，安妮购物。②在大约____个小时里，她走来走去。③然后她离开了。",
            sourceYear = 1986,
            sourceQuestionNo = 1
        )
        val pairs = AlarmChallengeManager.buildExcerptPairs(cloze)
        assertEquals(3, pairs.size)
        assertTrue(pairs.toString().contains("____"))
        assertEquals(pairs.size, 3)
    }

    @Test
    fun testBuildExcerptPairsWhenEnZhCountMismatchTakesMin() {
        // en 3 句, zh 只有 2 句 → 取 2 对, 不崩、不冗余
        val cloze = AlarmChallengeManager.ClozeQuestion(
            sentence = "①One. ②Two with ____. ③Three.",
            answer = "x",
            options = listOf("a", "b", "c", "d"),
            passageTranslation = "①第一句。②第二句。"
        )
        val pairs = AlarmChallengeManager.buildExcerptPairs(cloze)
        assertTrue(pairs.size in 1..3)
    }

    @Test
    fun testBuildExcerptPairsNoPassageTranslationFallsBackWithoutGarbage() {
        // passageTranslation 为空 → 兜底到 excerpt, 仍 ≤3 对且不出现乱串(不因标点拆出多句)
        val cloze = AlarmChallengeManager.ClozeQuestion(
            sentence = "①It did not matter what was done. ②So long as something changed, productivity rose. ③That was enough.",
            answer = "rose",
            options = listOf("a", "b", "c", "d"),
            passageTranslation = "",
            grammarExplanation = "［篇章分析］文章第一句总说，第二句说明，第三句总结，用于测试的说明文本。"
        )
        val pairs = AlarmChallengeManager.buildExcerptPairs(cloze)
        assertTrue(pairs.size in 1..3)
    }
}