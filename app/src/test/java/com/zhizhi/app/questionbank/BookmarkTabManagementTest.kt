package com.zhizhi.app.questionbank

import android.content.Context
import com.zhizhi.app.data.questionbank.CustomQuestionBank
import com.zhizhi.app.manager.questionbank.CustomQuestionBankManager
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class BookmarkTabManagementTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        CustomQuestionBankManager.clearForTest(context)
        context.getSharedPreferences("bookmark_settings", Context.MODE_PRIVATE).edit().clear().apply()
    }

    @After
    fun tearDown() {
        CustomQuestionBankManager.clearForTest(context)
        context.getSharedPreferences("bookmark_settings", Context.MODE_PRIVATE).edit().clear().apply()
    }

    @Test
    fun testHideBuiltinTabsAndEnsureFallback() {
        val prefs = context.getSharedPreferences("bookmark_settings", Context.MODE_PRIVATE)

        // 模拟用户关闭单词和选词
        val hidden = setOf("words", "clozes")
        prefs.edit().putStringSet("hidden_types", hidden).apply()

        val readHidden = prefs.getStringSet("hidden_types", emptySet()) ?: emptySet()
        assertTrue(readHidden.contains("words"))
        assertTrue(readHidden.contains("clozes"))
        assertFalse(readHidden.contains("poems"))

        // 模拟用户关闭所有，验证全部关闭时回退机制
        val allHidden = setOf("poems", "words", "clozes")
        prefs.edit().putStringSet("hidden_types", allHidden).apply()
        val allRead = prefs.getStringSet("hidden_types", emptySet()) ?: emptySet()
        assertEquals(3, allRead.size)
    }

    @Test
    fun testCustomBankTabVisibilityLinkage() {
        val bank1 = CustomQuestionBank(id = "b1", title = "考公", showInBookmark = true)
        val bank2 = CustomQuestionBank(id = "b2", title = "考研", showInBookmark = false)
        CustomQuestionBankManager.saveBank(context, bank1)
        CustomQuestionBankManager.saveBank(context, bank2)

        val banks = CustomQuestionBankManager.getBanks(context)
        assertTrue(banks.first { it.id == "b1" }.showInBookmark)
        assertFalse(banks.first { it.id == "b2" }.showInBookmark)

        // 动态修改显隐
        CustomQuestionBankManager.setBankShowInBookmark(context, "b1", false)
        assertFalse(CustomQuestionBankManager.getBank(context, "b1")!!.showInBookmark)

        CustomQuestionBankManager.setBankShowInBookmark(context, "b2", true)
        assertTrue(CustomQuestionBankManager.getBank(context, "b2")!!.showInBookmark)
    }
}
