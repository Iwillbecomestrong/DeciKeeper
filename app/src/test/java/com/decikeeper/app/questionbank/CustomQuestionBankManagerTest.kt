package com.decikeeper.app.questionbank

import android.content.Context
import com.decikeeper.app.data.questionbank.CustomQuestion
import com.decikeeper.app.data.questionbank.CustomQuestionBank
import com.decikeeper.app.manager.questionbank.CustomQuestionBankManager
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class CustomQuestionBankManagerTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        CustomQuestionBankManager.clearForTest(context)
    }

    @After
    fun tearDown() {
        CustomQuestionBankManager.clearForTest(context)
    }

    @Test
    fun testSaveAndGetBanks() {
        val q1 = CustomQuestion(
            id = "q1",
            bankId = "b1",
            stem = "题干1",
            options = listOf("A", "B", "C", "D"),
            answer = "A",
            explanation = "解析1"
        )
        val bank = CustomQuestionBank(
            id = "b1",
            title = "考研题库",
            description = "描述",
            enabled = true,
            questions = listOf(q1)
        )

        CustomQuestionBankManager.saveBank(context, bank)

        val retrieved = CustomQuestionBankManager.getBank(context, "b1")
        assertNotNull(retrieved)
        assertEquals("考研题库", retrieved?.title)
        assertEquals(1, retrieved?.questions?.size)
        assertEquals("题干1", retrieved?.questions?.get(0)?.stem)

        // 重新从磁盘强制加载测试持久化
        val reloadBanks = CustomQuestionBankManager.getBanks(context, forceReload = true)
        assertEquals(1, reloadBanks.size)
        assertEquals("b1", reloadBanks[0].id)
    }

    @Test
    fun testDeleteAndSetEnabled() {
        val bank1 = CustomQuestionBank(id = "b1", title = "题库1", enabled = true)
        val bank2 = CustomQuestionBank(id = "b2", title = "题库2", enabled = true)
        CustomQuestionBankManager.saveBank(context, bank1)
        CustomQuestionBankManager.saveBank(context, bank2)

        assertEquals(2, CustomQuestionBankManager.getBanks(context).size)

        CustomQuestionBankManager.setBankEnabled(context, "b1", false)
        assertFalse(CustomQuestionBankManager.getBank(context, "b1")!!.enabled)

        CustomQuestionBankManager.deleteBank(context, "b2")
        assertEquals(1, CustomQuestionBankManager.getBanks(context).size)
        assertNull(CustomQuestionBankManager.getBank(context, "b2"))
    }

    @Test
    fun testRandomQuestionSelection() {
        // 无题库时抽题为 null
        assertNull(CustomQuestionBankManager.getRandomQuestion(context))

        val q1 = CustomQuestion(id = "q1", bankId = "b1", stem = "题1", options = listOf("1", "2", "3", "4"), answer = "A")
        val q2 = CustomQuestion(id = "q2", bankId = "b1", stem = "题2", options = listOf("1", "2", "3", "4"), answer = "B")
        val bank = CustomQuestionBank(id = "b1", title = "题库1", enabled = false, questions = listOf(q1, q2))
        CustomQuestionBankManager.saveBank(context, bank)

        // enabled == false 时不可被抽取
        assertNull(CustomQuestionBankManager.getRandomQuestion(context))

        // enabled == true 时可被抽取
        CustomQuestionBankManager.setBankEnabled(context, "b1", true)
        val picked = CustomQuestionBankManager.getRandomQuestion(context)
        assertNotNull(picked)
        assertTrue(picked?.id in listOf("q1", "q2"))
    }

    @Test
    fun testRecordAttemptAndMistakes() {
        val q1 = CustomQuestion(id = "q1", bankId = "b1", stem = "题1", options = listOf("1", "2", "3", "4"), answer = "A")
        val bank = CustomQuestionBank(id = "b1", title = "题库1", enabled = true, questions = listOf(q1))
        CustomQuestionBankManager.saveBank(context, bank)

        // 答错
        CustomQuestionBankManager.recordAttempt(context, "b1", "q1", isCorrect = false, pickedOption = "2")
        var mistakes = CustomQuestionBankManager.getAllMistakes(context)
        assertEquals(1, mistakes.size)
        assertEquals(1, mistakes[0].second.wrongCount)
        assertEquals("wrong", mistakes[0].second.lastAttemptStatus)

        // 答对
        CustomQuestionBankManager.recordAttempt(context, "b1", "q1", isCorrect = true, pickedOption = "1")
        val currentQ = CustomQuestionBankManager.getBank(context, "b1")!!.questions[0]
        assertEquals("correct", currentQ.lastAttemptStatus)
        assertEquals(1, currentQ.wrongCount) // 历史错题计数值仍保留

        // 移除错题
        CustomQuestionBankManager.resetMistake(context, "b1", "q1")
        mistakes = CustomQuestionBankManager.getAllMistakes(context)
        assertEquals(0, mistakes.size)
    }

    @Test
    fun testStandardTemplateAndPromptNotEmpty() {
        val template = CustomQuestionBankManager.getStandardTemplate()
        assertTrue(template.contains("唯物辩证法"))
        assertTrue(template.contains("答案: B"))

        val prompt = CustomQuestionBankManager.getAiPrompt()
        assertTrue(prompt.contains("四选一"))
        assertTrue(prompt.contains("Markdown"))
    }

    @Test
    fun testFavoriteQuestionWorkflow() {
        val q1 = CustomQuestion(id = "q1", bankId = "b1", stem = "题1", options = listOf("A", "B"), answer = "A")
        val q2 = CustomQuestion(id = "q2", bankId = "b1", stem = "题2", options = listOf("A", "B"), answer = "B")
        val bank = CustomQuestionBank(id = "b1", title = "数学题库", questions = listOf(q1, q2))
        CustomQuestionBankManager.saveBank(context, bank)

        // 初始无收藏
        assertEquals(0, CustomQuestionBankManager.getAllFavorites(context).size)
        assertEquals(0, CustomQuestionBankManager.getBank(context, "b1")?.favoriteCount)

        // 收藏 q1
        val res1 = CustomQuestionBankManager.toggleFavorite(context, "b1", "q1")
        assertTrue(res1)
        var favorites = CustomQuestionBankManager.getAllFavorites(context)
        assertEquals(1, favorites.size)
        assertEquals("q1", favorites[0].second.id)
        assertTrue(favorites[0].second.isFavorite)
        assertEquals(1, CustomQuestionBankManager.getBank(context, "b1")?.favoriteCount)

        // 取消收藏 q1
        val res2 = CustomQuestionBankManager.toggleFavorite(context, "b1", "q1")
        assertFalse(res2)
        assertEquals(0, CustomQuestionBankManager.getAllFavorites(context).size)
        assertEquals(0, CustomQuestionBankManager.getBank(context, "b1")?.favoriteCount)

        // setFavorite 显式设置
        CustomQuestionBankManager.setFavorite(context, "b1", "q2", true)
        favorites = CustomQuestionBankManager.getAllFavorites(context)
        assertEquals(1, favorites.size)
        assertEquals("q2", favorites[0].second.id)

        // 磁盘重新加载持久化验证
        val reloadedBank = CustomQuestionBankManager.getBanks(context, forceReload = true).first { it.id == "b1" }
        assertEquals(1, reloadedBank.favoriteCount)
        assertTrue(reloadedBank.questions.first { it.id == "q2" }.isFavorite)
    }

    @Test
    fun testDefaultCustomBanksPersistence() {
        val map1 = mapOf("bank_a" to 5, "bank_b" to 3)
        val map2 = mapOf("bank_c" to 2)

        com.decikeeper.app.manager.AlarmScheduler.setDefaultStage1CustomBanks(context, map1)
        com.decikeeper.app.manager.AlarmScheduler.setDefaultStage2CustomBanks(context, map2)

        val retrieved1 = com.decikeeper.app.manager.AlarmScheduler.getDefaultStage1CustomBanks(context)
        val retrieved2 = com.decikeeper.app.manager.AlarmScheduler.getDefaultStage2CustomBanks(context)

        assertEquals(map1, retrieved1)
        assertEquals(map2, retrieved2)
    }

    @Test
    fun testRenameBank() {
        val bank = CustomQuestionBank(id = "b_rename", title = "旧名称", enabled = true)
        CustomQuestionBankManager.saveBank(context, bank)

        // 空名称或纯空格拦截
        assertFalse(CustomQuestionBankManager.renameBank(context, "b_rename", "   "))
        assertEquals("旧名称", CustomQuestionBankManager.getBank(context, "b_rename")?.title)

        // 不存在的题库拦截
        assertFalse(CustomQuestionBankManager.renameBank(context, "b_non_exist", "新名称"))

        // 正常重命名并自动 trim
        assertTrue(CustomQuestionBankManager.renameBank(context, "b_rename", "  考公  "))
        val reloaded = CustomQuestionBankManager.getBanks(context, forceReload = true).first { it.id == "b_rename" }
        assertEquals("考公", reloaded.title)
    }

    @Test
    fun testShowInBookmarkAndFavoritesByBank() {
        val q1 = CustomQuestion(id = "q1", bankId = "b1", stem = "题1", options = listOf("A", "B"), answer = "A", isFavorite = true)
        val q2 = CustomQuestion(id = "q2", bankId = "b1", stem = "题2", options = listOf("A", "B"), answer = "B", isFavorite = false)
        val q3 = CustomQuestion(id = "q3", bankId = "b2", stem = "题3", options = listOf("A", "B"), answer = "A", isFavorite = true)

        val bank1 = CustomQuestionBank(id = "b1", title = "题库1", questions = listOf(q1, q2), showInBookmark = true)
        val bank2 = CustomQuestionBank(id = "b2", title = "题库2", questions = listOf(q3), showInBookmark = true)
        CustomQuestionBankManager.saveBank(context, bank1)
        CustomQuestionBankManager.saveBank(context, bank2)

        // 题库1收藏项仅有 q1
        val b1Favs = CustomQuestionBankManager.getFavoritesByBank(context, "b1")
        assertEquals(1, b1Favs.size)
        assertEquals("q1", b1Favs[0].id)

        // 题库2收藏项仅有 q3
        val b2Favs = CustomQuestionBankManager.getFavoritesByBank(context, "b2")
        assertEquals(1, b2Favs.size)
        assertEquals("q3", b2Favs[0].id)

        // 修改收藏夹显隐开关
        CustomQuestionBankManager.setBankShowInBookmark(context, "b1", false)
        val reloadedBank1 = CustomQuestionBankManager.getBanks(context, forceReload = true).first { it.id == "b1" }
        assertFalse(reloadedBank1.showInBookmark)

        // 存储路径描述检查
        val storagePath = CustomQuestionBankManager.getStoragePathDescription(context)
        assertTrue(storagePath.endsWith("custom_question_banks.json"))
    }
}

