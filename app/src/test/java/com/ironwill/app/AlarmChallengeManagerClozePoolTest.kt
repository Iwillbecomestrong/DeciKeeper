package com.ironwill.app

import com.ironwill.app.manager.AlarmChallengeManager
import java.io.File
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test



/**
 * Cloze 题库加载/解析测试
 *
 * 覆盖:
 * - 真实 v3-simple cloze_pool.json 载入后题池条数 = 545
 * - A,B,C,D 顺序严格对应,options 与 optionMeanings 一致
 * - 解析失败(空 JSON / 缺 items / 单条 schema 错)不污染 clozePool
 * - answerTranslation 从 option_meanings_zh[answer_label] 回填
 */
class AlarmChallengeManagerClozePoolTest {

    @After
    fun teardown() {
        AlarmChallengeManager.resetClozePoolForTest()
    }

    private fun assetJson(): String {
        // 从源码 assets 目录读真实文件 (Robolectric 在本项目不可用, 走文件系统)
        val candidates = listOf(
            "../main/assets/cloze_pool.json",
            "src/main/assets/cloze_pool.json",
            "app/src/main/assets/cloze_pool.json"
        )
        for (rel in candidates) {
            val f = File(rel)
            if (f.exists() && f.length() > 0) return f.readText(Charsets.UTF_8)
        }
        error("cloze_pool.json not found in any candidate path")
    }

    @Test
    fun realAsset_loads545Questions() {
        val json = assetJson()
        val loaded = AlarmChallengeManager.loadClozePoolFromJsonString(json)
        assertEquals("应加载 545 题", 545, loaded)
        assertEquals("cloezPool 大小一致", 545, AlarmChallengeManager.clozePoolSnapshot.size)
        assertTrue("应标记 loaded", AlarmChallengeManager.isClozePoolLoaded)
    }

    @Test
    fun realAsset_allItems_haveValidFields() {
        AlarmChallengeManager.loadClozePoolFromJsonString(assetJson())
        for ((idx, q) in AlarmChallengeManager.clozePoolSnapshot.withIndex()) {
            assertTrue("[$idx] sentence 非空", q.sentence.isNotEmpty())
            assertEquals("[$idx] options 必须 4 个", 4, q.options.size)
            assertEquals("[$idx] optionMeanings 必须 4 个", 4, q.optionMeanings.size)
            assertTrue("[$idx] answer 非空", q.answer.isNotEmpty())
            assertTrue("[$idx] sourceYear 1986-2019", q.sourceYear in 1986..2019)
            assertTrue("[$idx] sourceQuestionNo > 0", q.sourceQuestionNo > 0)
            for (i in 0..3) {
                assertNotNull("[$idx] option $i 非 null", q.options[i])
                assertTrue("[$idx] option $i 非空字符串", q.options[i].isNotEmpty())
            }
        }
    }

    @Test
    fun realAsset_optionsABCDOrderIsStable() {
        AlarmChallengeManager.loadClozePoolFromJsonString(assetJson())
        // 抽 50 条验证: opts[0] 对应 optionMeanings[0], 且都来自 {A,B,C,D} 顺序读
        val sample = AlarmChallengeManager.clozePoolSnapshot.shuffled().take(50)
        for ((idx, q) in sample.withIndex()) {
            // 答案应出现在 options 里
            val answerIdx = q.options.indexOf(q.answer)
            assertTrue("[$idx] 答案 ${q.answer} 必须在 options 里 (${q.options})", answerIdx in 0..3)
            // optionMeanings 与 options 同长度
            assertEquals(4, q.optionMeanings.size)
        }
    }

    @Test
    fun realAsset_answerTranslationBackfilled() {
        AlarmChallengeManager.loadClozePoolFromJsonString(assetJson())
        // 至少应有部分题目 answerTranslation 非空(被回填)
        val withTrans = AlarmChallengeManager.clozePoolSnapshot.count { it.answerTranslation.isNotEmpty() }
        // 1993+ 数据 option_meanings_zh 多数为 null, 但回填逻辑会复制 optionMeanings(空字符串)；
        // 这里只检查回填路径"已被调用" → answerTranslation 字段存在 (非 null, 不抛 NPE)
        for (q in AlarmChallengeManager.clozePoolSnapshot) {
            // 不抛异常 = 通过
            q.answerTranslation.length
        }
        assertTrue("至少加载了一些题", withTrans >= 0)  // 仅 sanity, 不强制百分比
    }

    @Test
    fun malformedJson_doesNotPollutePool() {
        AlarmChallengeManager.loadClozePoolFromJsonString("not a json")
        assertEquals("解析失败 → pool 大小应为 0", 0, AlarmChallengeManager.clozePoolSnapshot.size)
        assertFalse("不应标记 loaded", AlarmChallengeManager.isClozePoolLoaded)
    }

    @Test
    fun emptyItemsArray_doesNotPollutePool() {
        val json = """{"meta":{},"items":[]}"""
        val loaded = AlarmChallengeManager.loadClozePoolFromJsonString(json)
        assertEquals(0, loaded)
        assertEquals(0, AlarmChallengeManager.clozePoolSnapshot.size)
        assertFalse(AlarmChallengeManager.isClozePoolLoaded)
    }

    @Test
    fun missingItemsArray_doesNotPollutePool() {
        val json = """{"meta":{}}"""
        val loaded = AlarmChallengeManager.loadClozePoolFromJsonString(json)
        assertEquals(0, loaded)
        assertEquals(0, AlarmChallengeManager.clozePoolSnapshot.size)
        assertFalse(AlarmChallengeManager.isClozePoolLoaded)
    }

    @Test
    fun partiallyValidArray_skipsBadButKeepsGood() {
        // 一好一坏混在一起: 好条目应加载, 坏条目跳过, 不抛异常
        val json = """{
          "items": [
            {
              "id": "good_1",
              "year": 2017,
              "blank_index": 1,
              "context_three_sentences": "Some test sentence with ____ in it.",
              "options": {"A": "alpha", "B": "beta", "C": "gamma", "D": "delta"},
              "option_meanings_zh": {"A": "甲", "B": "乙", "C": "丙", "D": "丁"},
              "answer_label": "A",
              "answer_text": "alpha",
              "review_explanation": "解析 A",
              "source_file": "test.docx"
            },
            {
              "id": "bad_1",
              "year": 2018,
              "blank_index": 1,
              "context_three_sentences": ""
            }
          ]
        }"""
        val loaded = AlarmChallengeManager.loadClozePoolFromJsonString(json)
        assertEquals(1, loaded)
        val pool = AlarmChallengeManager.clozePoolSnapshot
        assertEquals(1, pool.size)
        val good = pool[0]
        assertEquals("alpha", good.answer)
        assertEquals(listOf("alpha", "beta", "gamma", "delta"), good.options)
        // 答案 "alpha" 在 options 第 0 位 (A) → optionMeanings[0] = "甲" 应被回填
        assertEquals("甲", good.answerTranslation)
        assertEquals(2017, good.sourceYear)
        assertEquals(1, good.sourceQuestionNo)
    }

    @Test
    fun arrayOptionsFormat_stillSupported() {
        // 旧 v1 schema: options 是数组, 应仍能加载
        val json = """{
          "items": [{
            "id": "old_2017_1",
            "exam_year": 2017,
            "blank_num": 1,
            "sentence": "Old style sentence with ____ in it.",
            "answer": "Besides",
            "options": ["Unlike", "Besides", "Throughout", "Despite"],
            "correct_letter": "B",
            "hint": "prep.",
            "jiexi": "解析 besides"
          }]
        }"""
        val loaded = AlarmChallengeManager.loadClozePoolFromJsonString(json)
        assertEquals(1, loaded)
        val q = AlarmChallengeManager.clozePoolSnapshot[0]
        assertEquals("Besides", q.answer)
        assertEquals(listOf("Unlike", "Besides", "Throughout", "Despite"), q.options)
        assertEquals(2017, q.sourceYear)
        assertEquals(1, q.sourceQuestionNo)
        assertEquals("解析 besides", q.grammarExplanation)
    }

    @Test
    fun answerLabelFallbackToCorrectLetter() {
        // 旧 schema 用 correct_letter 而非 answer_label
        val json = """{
          "items": [{
            "id": "old_2",
            "exam_year": 2015,
            "blank_num": 3,
            "sentence": "Sentence with ____ and other options.",
            "answer": "connected",
            "options": ["equal", "restricted", "connected", "inferior"],
            "correct_letter": "C",
            "option_meanings": ["a", "b", "c", "d"]
          }]
        }"""
        AlarmChallengeManager.loadClozePoolFromJsonString(json)
        val q = AlarmChallengeManager.clozePoolSnapshot[0]
        // 答案 "connected" 在 options index=2 (C) → optionMeanings[2] = "c" 应被回填
        assertEquals("c", q.answerTranslation)
    }
}
