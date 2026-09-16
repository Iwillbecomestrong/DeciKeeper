package com.decikeeper.app.ui

import android.content.Context
import android.content.SharedPreferences
import com.decikeeper.app.manager.AlarmChallengeManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test



/**
 * 选词填空 (cloze) UX 逻辑单元测试。
 *
 * 覆盖：
 * - TC1: getSafeOptionMeaning 去重 (R8)
 * - TC2: bookmarkCloze 单词收藏 bookmarkedWord 字段序列化/反序列化 (R6/R7)
 * - TC3: getBookmarkedClozes 数据完整性（空/非空/字段/排序）
 *
 * 本文件为纯 JVM 测试，不依赖 Robolectric。
 * 通过 [InMemoryPrefs] + [PrefsContext] 提供最小化 SharedPreferences 替身，
 * 使 bookmarkCloze / getBookmarkedClozes 的 JSON 串行化-反串行化路径可测。
 */
class ClozeUxTest {

    private lateinit var prefs: SharedPreferences
    private lateinit var context: Context

    @Before
    fun setUp() {
        // 每个测试用例使用全新的内存 SharedPreferences，保证隔离
        prefs = InMemoryPrefs()
        context = PrefsContext(prefs)
    }

    // ========================================================================
    // TC1: getSafeOptionMeaning 去重 (R8)
    // ========================================================================

    @Test
    fun `getSafeOptionMeaning 正常释义返回原文`() {
        val result = AlarmChallengeManager.getSafeOptionMeaning(
            optionMeanings = listOf("v. 使复杂化", "adj. 简单的", "n. 问题", "adv. 快速地"),
            idx = 0,
            word = "complex"
        )
        assertEquals("v. 使复杂化", result)
    }

    @Test
    fun `getSafeOptionMeaning 空字符串返回无释义`() {
        val result = AlarmChallengeManager.getSafeOptionMeaning(
            optionMeanings = listOf("", "b", "c", "d"),
            idx = 0,
            word = "test"
        )
        assertEquals("（无释义）", result)
    }

    @Test
    fun `getSafeOptionMeaning null字符串返回无释义`() {
        val result = AlarmChallengeManager.getSafeOptionMeaning(
            optionMeanings = listOf("null", "b", "c", "d"),
            idx = 0,
            word = "test"
        )
        assertEquals("（无释义）", result)
    }

    @Test
    fun `getSafeOptionMeaning 与word完全相同返回无释义`() {
        val result = AlarmChallengeManager.getSafeOptionMeaning(
            optionMeanings = listOf("complex", "b", "c", "d"),
            idx = 0,
            word = "complex"
        )
        assertEquals("（无释义）", result)
    }

    @Test
    fun `getSafeOptionMeaning 仅大小写不同也返回无释义`() {
        // R8: ignoreCase = true
        val result = AlarmChallengeManager.getSafeOptionMeaning(
            optionMeanings = listOf("Complex", "b", "c", "d"),
            idx = 0,
            word = "complex"
        )
        assertEquals("（无释义）", result)
    }

    @Test
    fun `getSafeOptionMeaning 索引越界返回无释义`() {
        val result = AlarmChallengeManager.getSafeOptionMeaning(
            optionMeanings = listOf("a", "b"),
            idx = 5,
            word = "word"
        )
        assertEquals("（无释义）", result)
    }

    // 边界：释义包含 word 但不等同（如 "complexity" vs "complex"）不应被去重
    @Test
    fun `getSafeOptionMeaning 释义包含单词但不是完全相等返回原文`() {
        val result = AlarmChallengeManager.getSafeOptionMeaning(
            optionMeanings = listOf("n. 复杂性 complexity", "b", "c", "d"),
            idx = 0,
            word = "complex"
        )
        assertEquals("n. 复杂性 complexity", result)
    }

    // ========================================================================
    // TC2: bookmarkCloze 单词收藏字段 (R6/R7)
    // ========================================================================

    @Test
    fun `bookmarkCloze bw为有效单词 bookmarkedWord正确反序列化`() {
        val cloze = AlarmChallengeManager.ClozeQuestion(
            sentence = "The problem is quite ____ to solve.",
            answer = "complex",
            options = listOf("complex", "simple", "easy", "hard"),
            optionMeanings = listOf("v. 使复杂化", "adj. 简单的", "adj. 容易的", "adj. 困难的"),
            sourceYear = 2020,
            sourceQuestionNo = 1
        )
        AlarmChallengeManager.bookmarkCloze(context, cloze, "complex")

        val list = AlarmChallengeManager.getBookmarkedClozes(context)
        assertEquals(1, list.size)
        assertEquals("complex", list[0].bookmarkedWord)
    }

    @Test
    fun `bookmarkCloze bw为空字符串 bookmarkedWord反序列化为null`() {
        val cloze = AlarmChallengeManager.ClozeQuestion(
            sentence = "The problem is quite ____ to solve.",
            answer = "complex",
            options = listOf("complex", "simple", "easy", "hard"),
            sourceYear = 2020,
            sourceQuestionNo = 2
        )
        AlarmChallengeManager.bookmarkCloze(context, cloze, "")

        val list = AlarmChallengeManager.getBookmarkedClozes(context)
        assertEquals(1, list.size)
        assertNull(list[0].bookmarkedWord)
    }

    @Test
    fun `bookmarkCloze bw为null bookmarkedWord反序列化为null`() {
        val cloze = AlarmChallengeManager.ClozeQuestion(
            sentence = "The problem is quite ____ to solve.",
            answer = "complex",
            options = listOf("complex", "simple", "easy", "hard"),
            sourceYear = 2020,
            sourceQuestionNo = 3
        )
        AlarmChallengeManager.bookmarkCloze(context, cloze, null)

        val list = AlarmChallengeManager.getBookmarkedClozes(context)
        assertEquals(1, list.size)
        assertNull(list[0].bookmarkedWord)
    }

    // ========================================================================
    // TC3: getBookmarkedClozes 数据完整性
    // ========================================================================

    @Test
    fun `getBookmarkedClozes 空数据返回空列表`() {
        val list = AlarmChallengeManager.getBookmarkedClozes(context)
        assertNotNull(list)
        assertTrue(list.isEmpty())
    }

    @Test
    fun `getBookmarkedClozes 正确反序列化全部字段`() {
        val cloze = AlarmChallengeManager.ClozeQuestion(
            sentence = "The test sentence with ____.",
            answer = "answer",
            options = listOf("answer", "optB", "optC", "optD"),
            optionMeanings = listOf("释义A", "释义B", "释义C", "释义D"),
            answerTranslation = "答案释义",
            grammarExplanation = "［篇章分析］这是篇章分析内容。",
            sourceYear = 2021,
            sourceQuestionNo = 5
        )
        AlarmChallengeManager.bookmarkCloze(context, cloze, "answer")

        val list = AlarmChallengeManager.getBookmarkedClozes(context)
        assertEquals(1, list.size)
        val item = list[0]

        // 核心字段
        assertEquals("kaoyan_cloze_2021_05", item.clozeId)
        assertEquals(2021, item.year)
        assertEquals(5, item.questionNo)
        assertEquals("answer", item.answer)
        assertEquals("答案释义", item.answerTranslation)
        assertEquals("answer", item.bookmarkedWord)

        // options 数组
        assertEquals(4, item.options.size)
        assertTrue(item.options.contains("answer"))

        // optionMeanings 经过 getSafeOptionMeaning 处理后均为有效值
        assertEquals(4, item.optionMeanings.size)
        item.optionMeanings.forEach { assertTrue(it.isNotEmpty()) }

        // sentence 经过 cleanClozeSentence 处理
        // 本题无圈号数字，应保持原样
        assertEquals("The test sentence with ____.", item.sentence)

        // excerptTranslation 从 grammarExplanation 的［篇章分析］段提取
        assertTrue(item.excerptTranslation.isNotEmpty())

        // timestamp 在合理范围内
        assertTrue(item.bookmarkedAt > 0L)
    }

    @Test
    fun `getBookmarkedClozes 无释义选项回退为无释义`() {
        // 选项释义为空时，getSafeOptionMeaning 应返回"（无释义）"
        val cloze = AlarmChallengeManager.ClozeQuestion(
            sentence = "Another test ____ here.",
            answer = "word",
            options = listOf("word", "opt2", "opt3", "opt4"),
            optionMeanings = listOf("", "", "", ""), // 全部空
            sourceYear = 2022,
            sourceQuestionNo = 10
        )
        AlarmChallengeManager.bookmarkCloze(context, cloze, null)

        val list = AlarmChallengeManager.getBookmarkedClozes(context)
        assertEquals(1, list.size)
        val item = list[0]
        // 全部释义应为"（无释义）"
        item.optionMeanings.forEach { assertEquals("（无释义）", it) }
    }

    @Test
    fun `getBookmarkedClozes 按年份题号排序`() {
        val c1 = AlarmChallengeManager.ClozeQuestion(
            sentence = "S1 ____.", answer = "a1",
            options = listOf("a1", "b", "c", "d"),
            sourceYear = 2020, sourceQuestionNo = 2
        )
        val c2 = AlarmChallengeManager.ClozeQuestion(
            sentence = "S2 ____.", answer = "a2",
            options = listOf("a2", "b", "c", "d"),
            sourceYear = 2020, sourceQuestionNo = 1
        )
        val c3 = AlarmChallengeManager.ClozeQuestion(
            sentence = "S3 ____.", answer = "a3",
            options = listOf("a3", "b", "c", "d"),
            sourceYear = 2019, sourceQuestionNo = 3
        )
        // 故意以乱序添加
        AlarmChallengeManager.bookmarkCloze(context, c1, null)
        AlarmChallengeManager.bookmarkCloze(context, c2, null)
        AlarmChallengeManager.bookmarkCloze(context, c3, null)

        val list = AlarmChallengeManager.getBookmarkedClozes(context)
        assertEquals(3, list.size)
        // 排序规则: compareBy({ year }, { questionNo })
        // 期望: (2019,3) -> (2020,1) -> (2020,2)
        assertEquals(2019, list[0].year)
        assertEquals(3, list[0].questionNo)
        assertEquals(2020, list[1].year)
        assertEquals(1, list[1].questionNo)
        assertEquals(2020, list[2].year)
        assertEquals(2, list[2].questionNo)
    }

    @Test
    fun `getBookmarkedClozes bw空字符串不同序列化方式一致`() {
        // R7: bw="" 和 bw=null 在序列化(putString("bw", ...))阶段表现不同,
        // 但反序列化(optString("bw","").ifEmpty{null})结果应一致
        val cloze1 = AlarmChallengeManager.ClozeQuestion(
            sentence = "Q1 ____.", answer = "a1",
            options = listOf("a1", "b", "c", "d"),
            sourceYear = 2025, sourceQuestionNo = 1
        )
        val cloze2 = AlarmChallengeManager.ClozeQuestion(
            sentence = "Q2 ____.", answer = "a2",
            options = listOf("a2", "b", "c", "d"),
            sourceYear = 2025, sourceQuestionNo = 2
        )
        AlarmChallengeManager.bookmarkCloze(context, cloze1, "")
        AlarmChallengeManager.bookmarkCloze(context, cloze2, null)

        val list = AlarmChallengeManager.getBookmarkedClozes(context)
        assertEquals(2, list.size)
        // 两者 bookmarkedWord 均应反序列化为 null
        assertNull(list[0].bookmarkedWord)
        assertNull(list[1].bookmarkedWord)
    }
}

// ============================================================================
// 测试替身：纯内存 SharedPreferences + Context，无 Robolectric 依赖
// ============================================================================

/**
 * 最小化 Context 替身，仅覆盖 [getSharedPreferences] 和 [getPackageName]。
 * 其他所有方法均为 android.content.ContextWrapper 的默认行为（stub/null）。
 */
private class PrefsContext(
    private val prefs: SharedPreferences
) : android.content.ContextWrapper(null) {
    override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences = prefs
    override fun getPackageName(): String = "com.decikeeper.app"
}

/**
 * 纯内存 SharedPreferences 实现。
 * 仅覆盖 [getString] 和 [edit]（以及相关 stub），
 * 满足 bookmarkCloze / getBookmarkedClozes / bookmarkWord 的 JSON 读写需求。
 */
private class InMemoryPrefs : SharedPreferences {
    private val store = LinkedHashMap<String, String>()

    override fun getString(key: String?, defValue: String?): String? {
        return if (key != null) store[key] else defValue
    }

    override fun edit(): SharedPreferences.Editor = InMemoryEditor(store)

    // ---- stub ----
    override fun getAll(): MutableMap<String, *> = LinkedHashMap(store)
    override fun contains(key: String?): Boolean = key != null && store.containsKey(key)
    override fun getBoolean(key: String?, defValue: Boolean): Boolean = defValue
    override fun getInt(key: String?, defValue: Int): Int = defValue
    override fun getLong(key: String?, defValue: Long): Long = defValue
    override fun getFloat(key: String?, defValue: Float): Float = defValue
    override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? = defValues
    override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}
    override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}
}

/**
 * 纯内存 SharedPreferences.Editor 实现。
 * [apply] 时批量提交 pending 到 store。
 */
private class InMemoryEditor(
    private val store: MutableMap<String, String>
) : SharedPreferences.Editor {
    private val pending = LinkedHashMap<String, String>(store)

    override fun putString(key: String?, value: String?): SharedPreferences.Editor {
        if (key != null) pending[key] = value ?: ""
        return this
    }

    override fun remove(key: String?): SharedPreferences.Editor {
        if (key != null) pending.remove(key)
        return this
    }

    override fun clear(): SharedPreferences.Editor {
        pending.clear()
        return this
    }

    override fun apply() {
        store.clear()
        store.putAll(pending)
    }

    override fun commit(): Boolean {
        apply()
        return true
    }

    // ---- stub ----
    override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor {
        if (key != null) pending[key] = value.toString(); return this
    }
    override fun putInt(key: String?, value: Int): SharedPreferences.Editor {
        if (key != null) pending[key] = value.toString(); return this
    }
    override fun putLong(key: String?, value: Long): SharedPreferences.Editor {
        if (key != null) pending[key] = value.toString(); return this
    }
    override fun putFloat(key: String?, value: Float): SharedPreferences.Editor {
        if (key != null) pending[key] = value.toString(); return this
    }
    override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor {
        if (key != null) pending[key] = values?.joinToString(",") ?: ""; return this
    }
}
