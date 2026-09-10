package com.decikeeper.app

import android.content.SharedPreferences
import com.decikeeper.app.manager.AlarmChallengeManager
import com.decikeeper.app.ui.findWordAtIndex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test




/** Req-1 修正：长按题目任意单词查词 + 星星收藏/取消收藏 测试。 */
class WordLookupAndBookmarkTest {

    private lateinit var prefs: SharedPreferences
    private lateinit var context: android.content.Context

    @Before
    fun setUp() {
        prefs = InMemoryPrefs()
        context = PrefsContext(prefs)
    }

    // ==== findWordAtIndex ====
    @Test
    fun testWordInMiddle() {
        val text = "She faced many difficulties."
        val i = text.indexOf("difficulties") + 5
        assertEquals("difficulties", findWordAtIndex(text, i))
    }

    @Test
    fun testWordAtStart() {
        val text = "people know they can trust"
        assertEquals("people", findWordAtIndex(text, 0))
    }

    @Test
    fun testWordAtEnd() {
        val text = "people trust."
        val start = text.indexOf("trust")
        assertEquals("trust", findWordAtIndex(text, start + 4))
    }

    @Test
    fun testPunctuationReturnsNull() {
        val text = "Hello, world."
        assertNull(findWordAtIndex(text, 5))
        assertNull(findWordAtIndex(text, 12))
    }

    @Test
    fun testSpaceReturnsNull() {
        val text = "hello world"
        assertNull(findWordAtIndex(text, 5))
    }

    @Test
    fun testChineseReturnsNull() {
        val text = "她每天早起背单词"
        assertNull(findWordAtIndex(text, 2))
    }

    @Test
    fun testBlankBracketReturnsNull() {
        val text = "一份[    ]让她"
        assertNull(findWordAtIndex(text, 3))
    }

    @Test
    fun testOutOfBoundsReturnsNull() {
        assertNull(findWordAtIndex("hello", -1))
        assertNull(findWordAtIndex("hello", 5))
    }

    @Test
    fun testEmptyReturnsNull() {
        assertNull(findWordAtIndex("", 0))
    }

    // ==== removeBookmarkedWord ====
    @Test
    fun testRemoveBookmarkedWord() {
        AlarmChallengeManager.bookmarkWord(context, "apple", "苹果", "")
        AlarmChallengeManager.bookmarkWord(context, "banana", "香蕉", "")
        AlarmChallengeManager.removeBookmarkedWord(context, "apple")
        val words = AlarmChallengeManager.getBookmarkedWords(context)
        assertEquals(1, words.size)
        assertEquals("banana", words[0].word)
    }

    @Test
    fun testRemoveIgnoresCase() {
        AlarmChallengeManager.bookmarkWord(context, "Apple", "苹果", "")
        AlarmChallengeManager.removeBookmarkedWord(context, "apple")
        assertTrue(AlarmChallengeManager.getBookmarkedWords(context).isEmpty())
    }

    @Test
    fun testRemoveMissingWordNoop() {
        AlarmChallengeManager.bookmarkWord(context, "pear", "梨", "")
        AlarmChallengeManager.removeBookmarkedWord(context, "grape")
        val words = AlarmChallengeManager.getBookmarkedWords(context)
        assertEquals(1, words.size)
        assertEquals("pear", words[0].word)
    }

    @Test
    fun testRemoveBlankWordNoop() {
        AlarmChallengeManager.removeBookmarkedWord(context, "")
        AlarmChallengeManager.removeBookmarkedWord(context, "   ")
    }

    private class PrefsContext(private val prefs: SharedPreferences)
        : android.content.ContextWrapper(null) {
        override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences = prefs
        override fun getPackageName(): String = "com.decikeeper.app"
    }

    private class InMemoryPrefs : SharedPreferences {
        private val store = LinkedHashMap<String, String>()
        override fun getString(key: String?, defValue: String?): String? = if (key != null) store[key] else defValue
        override fun edit(): SharedPreferences.Editor = InMemoryEditor(store)
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

    private class InMemoryEditor(private val store: MutableMap<String, String>) : SharedPreferences.Editor {
        private val pending = LinkedHashMap<String, String?>()
        override fun putString(key: String?, value: String?): SharedPreferences.Editor { pending[key!!] = value; return this }
        override fun remove(key: String?): SharedPreferences.Editor { pending[key!!] = null; return this }
        override fun clear(): SharedPreferences.Editor { pending.put("__clear__", null); return this }
        override fun commit(): Boolean { apply(); return true }
        override fun apply() {
            if (pending.containsKey("__clear__")) store.clear()
            pending.forEach { (k, v) -> if (v == null) store.remove(k) else store[k] = v }
        }
        override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor = this
        override fun putInt(key: String?, value: Int): SharedPreferences.Editor = this
        override fun putLong(key: String?, value: Long): SharedPreferences.Editor = this
        override fun putFloat(key: String?, value: Float): SharedPreferences.Editor = this
        override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor = this
    }
}