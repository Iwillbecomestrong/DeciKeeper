package com.decikeeper.app.ui

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import android.graphics.Typeface
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.cardview.widget.CardView
import com.decikeeper.app.R
import com.decikeeper.app.manager.AlarmChallengeManager
import com.decikeeper.app.manager.questionbank.CustomQuestionBankManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class BookmarkActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_TYPE = "bookmark_type"
        const val TYPE_POEMS = "poems"
        const val TYPE_WORDS = "words"
        const val TYPE_CLOZES = "clozes"
        const val PREFIX_CUSTOM = "custom_"
        private const val PREFS_NAME = "bookmark_settings"
        private const val KEY_HIDDEN_TYPES = "hidden_types"
    }

    private data class TabItem(
        val type: String,
        val label: String,
        val isBuiltIn: Boolean
    )

    private data class BookmarkItem(
        val primary: String,
        val secondary: String,
        val phonetic: String?,
        val skipCount: Int,
        val bookmarkedAtMs: Long
    )

    private lateinit var layoutDynamicTabs: LinearLayout
    private lateinit var containerList: LinearLayout
    private lateinit var scrollList: ScrollView
    private lateinit var etSearch: EditText
    private val tabButtons = mutableMapOf<String, Button>()
    private var currentType: String = TYPE_POEMS
    private var currentSortBy: String = "skip"
    private var currentTimeRange: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.apply(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_bookmark)

        currentType = intent.getStringExtra(EXTRA_TYPE) ?: TYPE_POEMS

        findViewById<Button>(R.id.btn_back).setOnClickListener { finish() }
        findViewById<Button>(R.id.btn_manage_tabs).setOnClickListener { showManageTabsDialog() }

        layoutDynamicTabs = findViewById(R.id.layout_dynamic_tabs)
        containerList = findViewById(R.id.container_list)
        scrollList = findViewById(R.id.scroll_list)
        etSearch = findViewById(R.id.et_search)

        findViewById<Button>(R.id.btn_sort_skip).setOnClickListener { setSortBy("skip") }
        findViewById<Button>(R.id.btn_sort_time).setOnClickListener { setSortBy("time") }
        findViewById<Button>(R.id.btn_range_all).setOnClickListener { setTimeRange("") }
        findViewById<Button>(R.id.btn_range_7d).setOnClickListener { setTimeRange("7d") }
        findViewById<Button>(R.id.btn_range_30d).setOnClickListener { setTimeRange("30d") }
        findViewById<Button>(R.id.btn_range_90d).setOnClickListener { setTimeRange("90d") }

        setupDynamicTabs()

        val visibleTabs = getVisibleTabs()
        if (currentType !in visibleTabs.map { it.type }) {
            currentType = visibleTabs.firstOrNull()?.type ?: TYPE_POEMS
        }

        updateUiForCurrentType()
        renderList("")
        refreshFilterHighlight()

        etSearch.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) { renderList(s?.toString() ?: "") }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })
    }

    override fun onResume() {
        super.onResume()
        setupDynamicTabs()
        val visibleTabs = getVisibleTabs()
        if (currentType !in visibleTabs.map { it.type }) {
            switchTab(visibleTabs.firstOrNull()?.type ?: TYPE_POEMS)
        } else {
            refreshTabHighlight()
        }
    }

    private fun getHiddenTypes(): Set<String> {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        return prefs.getStringSet(KEY_HIDDEN_TYPES, emptySet()) ?: emptySet()
    }

    private fun saveHiddenTypes(hidden: Set<String>) {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        prefs.edit().putStringSet(KEY_HIDDEN_TYPES, hidden).apply()
    }

    private fun getAllAvailableTabs(): List<TabItem> {
        val list = mutableListOf<TabItem>()
        list.add(TabItem(TYPE_POEMS, "诗词", true))
        list.add(TabItem(TYPE_WORDS, "单词", true))
        list.add(TabItem(TYPE_CLOZES, "选词", true))
        val customBanks = CustomQuestionBankManager.getBanks(this)
        for (bank in customBanks) {
            list.add(TabItem("$PREFIX_CUSTOM${bank.id}", bank.title, false))
        }
        return list
    }

    private fun getVisibleTabs(): List<TabItem> {
        val hidden = getHiddenTypes()
        val all = getAllAvailableTabs()
        val customBanksMap = CustomQuestionBankManager.getBanks(this).associateBy { it.id }
        val visible = all.filter { tab ->
            if (tab.isBuiltIn) {
                tab.type !in hidden
            } else {
                val bankId = tab.type.removePrefix(PREFIX_CUSTOM)
                val bank = customBanksMap[bankId]
                (bank?.showInBookmark != false) && (tab.type !in hidden)
            }
        }
        return if (visible.isNotEmpty()) visible else all.take(1)
    }

    private fun setupDynamicTabs() {
        layoutDynamicTabs.removeAllViews()
        tabButtons.clear()
        val visibleTabs = getVisibleTabs()
        val d = density

        for (tab in visibleTabs) {
            val btn = Button(this).apply {
                text = tab.label
                textSize = 12f
                isAllCaps = false
                includeFontPadding = false
                minWidth = (54 * d).toInt()
                minHeight = 0
                setPadding((12 * d).toInt(), 0, (12 * d).toInt(), 0)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.MATCH_PARENT
                ).apply {
                    marginEnd = (6 * d).toInt()
                }
                setOnClickListener { switchTab(tab.type) }
            }
            tabButtons[tab.type] = btn
            layoutDynamicTabs.addView(btn)
        }
        refreshTabHighlight()
    }

    private fun showManageTabsDialog() {
        val allTabs = getAllAvailableTabs()
        val hidden = getHiddenTypes().toMutableSet()
        val customBanksMap = CustomQuestionBankManager.getBanks(this).associateBy { it.id }
        val checkedItems = BooleanArray(allTabs.size) { i ->
            val tab = allTabs[i]
            if (tab.isBuiltIn) {
                tab.type !in hidden
            } else {
                val bankId = tab.type.removePrefix(PREFIX_CUSTOM)
                val bank = customBanksMap[bankId]
                (bank?.showInBookmark != false) && (tab.type !in hidden)
            }
        }
        val names = allTabs.map {
            if (it.isBuiltIn) "${it.label}（内置）" else "${it.label}（题库）"
        }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("⚙️ 收藏册管理")
            .setMultiChoiceItems(names, checkedItems) { _, which, isChecked ->
                checkedItems[which] = isChecked
            }
            .setPositiveButton("保存") { _, _ ->
                val selectedCount = checkedItems.count { it }
                if (selectedCount == 0) {
                    Toast.makeText(this, "请至少保留一个可见收藏册", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val newHidden = mutableSetOf<String>()
                for (i in allTabs.indices) {
                    val tab = allTabs[i]
                    val isChecked = checkedItems[i]
                    if (!isChecked) {
                        newHidden.add(tab.type)
                        if (!tab.isBuiltIn) {
                            val bankId = tab.type.removePrefix(PREFIX_CUSTOM)
                            CustomQuestionBankManager.setBankShowInBookmark(this, bankId, false)
                        }
                    } else {
                        if (!tab.isBuiltIn) {
                            val bankId = tab.type.removePrefix(PREFIX_CUSTOM)
                            CustomQuestionBankManager.setBankShowInBookmark(this, bankId, true)
                        }
                    }
                }
                saveHiddenTypes(newHidden)
                setupDynamicTabs()
                val visible = getVisibleTabs()
                if (currentType !in visible.map { it.type }) {
                    switchTab(visible.first().type)
                } else {
                    refreshTabHighlight()
                }
                Toast.makeText(this, "已更新收藏册显示设置", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun updateUiForCurrentType() {
        findViewById<TextView>(R.id.tv_title).text = titleFor(currentType)
        val isCloze = currentType == TYPE_CLOZES
        val isCustom = currentType.startsWith(PREFIX_CUSTOM)
        val isWordOrPoem = currentType == TYPE_POEMS || currentType == TYPE_WORDS

        etSearch.visibility = if (isCloze) android.view.View.GONE else android.view.View.VISIBLE
        if (isCustom) {
            val bankId = currentType.removePrefix(PREFIX_CUSTOM)
            val bank = CustomQuestionBankManager.getBank(this, bankId)
            etSearch.hint = "搜索${bank?.title ?: ""}收藏…"
        } else {
            etSearch.hint = "搜索收藏…"
        }

        findViewById<View>(R.id.layout_sort_row)?.visibility = if (isWordOrPoem) android.view.View.VISIBLE else android.view.View.GONE
        findViewById<View>(R.id.layout_range_row)?.visibility = if (isWordOrPoem) android.view.View.VISIBLE else android.view.View.GONE
        refreshTabHighlight()
    }

    private fun attrColor(attr: Int): Int {
        val value = TypedValue()
        theme.resolveAttribute(attr, value, true)
        return value.data
    }

    private fun roundedBg(color: Int, radiusDp: Float = 14f): GradientDrawable = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radiusDp * density
    }

    private fun renderList(query: String) {
        if (currentType == TYPE_CLOZES) {
            renderClozeList()
            return
        }
        if (currentType.startsWith(PREFIX_CUSTOM)) {
            val bankId = currentType.removePrefix(PREFIX_CUSTOM)
            renderCustomBankList(bankId, query)
            return
        }
        val items = if (currentType == TYPE_POEMS) {
            AlarmChallengeManager.getBookmarkedPoems(this, query, currentSortBy, currentTimeRange).map {
                BookmarkItem(it.display, it.answer, null, it.skipCount, it.bookmarkedAtMs)
            }
        } else {
            AlarmChallengeManager.getBookmarkedWords(this, query, currentSortBy, currentTimeRange).map {
                BookmarkItem(it.word, it.translation, resolvePhonetic(it.word, it.phonetic), it.skipCount, it.bookmarkedAtMs)
            }
        }
        containerList.removeAllViews()
        if (items.isEmpty()) {
            val msg = when {
                query.isNotEmpty() -> "未找到匹配项"
                currentTimeRange.isNotEmpty() -> "该时间范围内暂无收藏"
                else -> "暂无收藏"
            }
            containerList.addView(TextView(this).apply {
                text = msg
                textSize = 14f
                setTextColor(attrColor(R.attr.zzSubtle))
                gravity = Gravity.CENTER
                setPadding(0, (24 * density).toInt(), 0, 0)
            })
            return
        }
        items.forEach { containerList.addView(buildItemRow(it)) }
    }

    private fun setSortBy(sortBy: String) {
        if (currentSortBy == sortBy || currentType == TYPE_CLOZES || currentType.startsWith(PREFIX_CUSTOM)) return
        currentSortBy = sortBy
        refreshFilterHighlight()
        renderList(etSearch.text?.toString() ?: "")
    }

    private fun setTimeRange(timeRange: String) {
        if (currentTimeRange == timeRange || currentType == TYPE_CLOZES || currentType.startsWith(PREFIX_CUSTOM)) return
        currentTimeRange = timeRange
        refreshFilterHighlight()
        renderList(etSearch.text?.toString() ?: "")
    }

    private fun refreshFilterHighlight() {
        fun apply(btn: Button, active: Boolean) {
            btn.setTextColor(if (active) attrColor(R.attr.zzAccentStrong) else attrColor(R.attr.zzMuted))
            btn.backgroundTintList = ColorStateList.valueOf(
                if (active) attrColor(R.attr.zzAccentSoft) else attrColor(R.attr.zzSurface2)
            )
        }
        apply(findViewById(R.id.btn_sort_skip), currentSortBy == "skip")
        apply(findViewById(R.id.btn_sort_time), currentSortBy == "time")
        apply(findViewById(R.id.btn_range_all), currentTimeRange.isEmpty())
        apply(findViewById(R.id.btn_range_7d), currentTimeRange == "7d")
        apply(findViewById(R.id.btn_range_30d), currentTimeRange == "30d")
        apply(findViewById(R.id.btn_range_90d), currentTimeRange == "90d")
    }

    private fun titleFor(type: String): String = when {
        type == TYPE_POEMS -> "收藏的诗词"
        type == TYPE_WORDS -> "收藏的单词"
        type == TYPE_CLOZES -> "收藏的选词填空"
        type.startsWith(PREFIX_CUSTOM) -> {
            val bankId = type.removePrefix(PREFIX_CUSTOM)
            val bank = CustomQuestionBankManager.getBank(this, bankId)
            "收藏的${bank?.title ?: "题库"}"
        }
        else -> "收藏本"
    }

    private fun switchTab(type: String) {
        if (type == currentType) return
        currentType = type
        etSearch.setText("")
        updateUiForCurrentType()
        if (type == TYPE_CLOZES) renderClozeList() else renderList("")
    }

    private fun refreshTabHighlight() {
        for ((type, btn) in tabButtons) {
            val active = type == currentType
            btn.setTextColor(if (active) attrColor(R.attr.zzAccentStrong) else attrColor(R.attr.zzMuted))
            btn.backgroundTintList = ColorStateList.valueOf(
                if (active) attrColor(R.attr.zzAccentSoft) else attrColor(R.attr.zzSurface2)
            )
        }
    }

    private fun renderClozeList() {
        val clozes = AlarmChallengeManager.getBookmarkedClozes(this)
        containerList.removeAllViews()
        if (clozes.isEmpty()) {
            containerList.addView(TextView(this).apply {
                text = "暂无收藏的选词填空"
                textSize = 14f
                setTextColor(attrColor(R.attr.zzSubtle))
                gravity = Gravity.CENTER
                setPadding(0, (24 * density).toInt(), 0, 0)
            })
            return
        }
        clozes.forEach { containerList.addView(buildClozeRow(it)) }
    }

    private fun buildClozeRow(cloze: AlarmChallengeManager.BookmarkedCloze): LinearLayout {
        val d = density
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedBg(attrColor(R.attr.zzSurface), 17f)
            setPadding((14 * d).toInt(), (12 * d).toInt(), (14 * d).toInt(), (12 * d).toInt())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, (5 * d).toInt(), 0, (5 * d).toInt()) }
            isClickable = true
            isFocusable = true
            setOnClickListener { showClozeDetail(cloze) }
        }

        val headerText = if (cloze.year > 0 && cloze.questionNo > 0) {
            "${cloze.year} 年 · 第 ${cloze.questionNo} 题"
        } else "选词填空"
        row.addView(TextView(this).apply {
            text = headerText
            textSize = 14f
            setTextColor(attrColor(R.attr.zzAccentStrong))
            setTypeface(null, android.graphics.Typeface.BOLD)
        })
        row.addView(TextView(this).apply {
            text = cloze.sentence.ifEmpty { "（题面为空）" }
            textSize = 13f
            setTextColor(attrColor(R.attr.zzText))
            maxLines = 3
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(0, (5 * d).toInt(), 0, 0)
        })
        row.addView(TextView(this).apply {
            text = "答案：${cloze.answer}" + if (cloze.answerTranslation.isNotEmpty()) "  ${cloze.answerTranslation}" else ""
            textSize = 13f
            setTextColor(attrColor(R.attr.zzSuccess))
            setPadding(0, (3 * d).toInt(), 0, 0)
        })
        if (!cloze.bookmarkedWord.isNullOrEmpty()) {
            row.addView(TextView(this).apply {
                text = "收藏词：${cloze.bookmarkedWord}"
                textSize = 12f
                setTextColor(attrColor(R.attr.zzAlt))
                setPadding(0, (3 * d).toInt(), 0, 0)
            })
        }
        return row
    }

    private fun showClozeDetail(cloze: AlarmChallengeManager.BookmarkedCloze) {
        val d = density
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((20 * d).toInt(), (16 * d).toInt(), (20 * d).toInt(), (16 * d).toInt())
        }
        val titleText = if (cloze.year > 0 && cloze.questionNo > 0) {
            "${cloze.year} 年 · 第 ${cloze.questionNo} 题"
        } else "选词填空"
        container.addView(TextView(this).apply {
            text = titleText
            textSize = 16f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(attrColor(R.attr.zzAccentStrong))
        })
        container.addView(TextView(this).apply {
            text = cloze.sentence.ifEmpty { "（题面为空）" }
            textSize = 14f
            setTextColor(attrColor(R.attr.zzText))
            setPadding(0, (8 * d).toInt(), 0, 0)
        })
        container.addView(TextView(this).apply {
            text = "答案：${cloze.answer}" + if (cloze.answerTranslation.isNotEmpty()) "\n${cloze.answerTranslation}" else ""
            textSize = 14f
            setTextColor(attrColor(R.attr.zzSuccess))
            setPadding(0, (8 * d).toInt(), 0, 0)
        })

        if (cloze.options.isNotEmpty()) {
            container.addView(TextView(this).apply {
                text = "选项"
                textSize = 13f
                setTextColor(attrColor(R.attr.zzMuted))
                setTypeface(null, android.graphics.Typeface.BOLD)
                setPadding(0, (10 * d).toInt(), 0, (4 * d).toInt())
            })
            listOf("A", "B", "C", "D").forEachIndexed { idx, label ->
                if (idx < cloze.options.size) {
                    val optWord = cloze.options[idx]
                    val meaning = cloze.optionMeanings.getOrNull(idx).orEmpty()
                    val row = LinearLayout(this).apply {
                        orientation = LinearLayout.HORIZONTAL
                        setPadding(0, (5 * d).toInt(), 0, (5 * d).toInt())
                        setOnLongClickListener {
                            val displayMeaning = meaning.takeIf { it.isNotEmpty() && it != "（无释义）" }
                                ?: AlarmChallengeManager.lookupWordWithLemma(optWord)?.translation
                                ?: "无释义"
                            val dlg = LinearLayout(context).apply {
                                orientation = LinearLayout.VERTICAL
                                setPadding((20 * d).toInt(), (16 * d).toInt(), (20 * d).toInt(), (16 * d).toInt())
                                addView(TextView(context).apply {
                                    text = optWord
                                    textSize = 20f
                                    setTypeface(null, android.graphics.Typeface.BOLD)
                                    setTextColor(attrColor(R.attr.zzAccentStrong))
                                })
                                addView(TextView(context).apply {
                                    text = displayMeaning
                                    textSize = 14f
                                    setTextColor(attrColor(R.attr.zzText))
                                    setPadding(0, (8 * d).toInt(), 0, 0)
                                })
                            }
                            val wordForBookmark = optWord
                            AlertDialog.Builder(context)
                                .setTitle("单词详情")
                                .setView(dlg)
                                .setPositiveButton("关闭", null)
                                .setNeutralButton("收藏单词") { _, _ ->
                                    val q = AlarmChallengeManager.ClozeQuestion(
                                        sentence = cloze.sentence,
                                        answer = cloze.answer,
                                        options = cloze.options,
                                        optionMeanings = cloze.optionMeanings,
                                        answerTranslation = cloze.answerTranslation,
                                        sourceYear = cloze.year,
                                        sourceQuestionNo = cloze.questionNo,
                                        grammarExplanation = cloze.grammarExplanation,
                                        sourceRepo = cloze.sourceRepo,
                                        passageTranslation = cloze.excerptTranslation
                                    )
                                    AlarmChallengeManager.bookmarkCloze(context, q, wordForBookmark)
                                    android.widget.Toast.makeText(context, "已收藏：$wordForBookmark", android.widget.Toast.LENGTH_SHORT).show()
                                }
                                .show()
                            true
                        }
                    }
                    row.addView(TextView(this@BookmarkActivity).apply {
                        text = "$label. $optWord"
                        textSize = 13f
                        setTypeface(null, android.graphics.Typeface.BOLD)
                        setTextColor(attrColor(R.attr.zzText))
                    })
                    if (meaning.isNotEmpty() && meaning != "（无释义）") {
                        row.addView(TextView(this@BookmarkActivity).apply {
                            text = "  $meaning"
                            textSize = 12f
                            setTextColor(attrColor(R.attr.zzMuted))
                            setPadding((8 * d).toInt(), 0, 0, 0)
                        })
                    }
                    container.addView(row)
                }
            }
        }

        if (cloze.excerptTranslation.isNotEmpty()) {
            container.addView(TextView(this).apply {
                text = "节选翻译\n${cloze.excerptTranslation}"
                textSize = 13f
                setTextColor(attrColor(R.attr.zzMuted))
                setPadding(0, (10 * d).toInt(), 0, 0)
            })
        }
        if (!cloze.bookmarkedWord.isNullOrEmpty()) {
            container.addView(TextView(this).apply {
                text = "收藏单词：${cloze.bookmarkedWord}"
                textSize = 13f
                setTextColor(attrColor(R.attr.zzAlt))
                setPadding(0, (8 * d).toInt(), 0, 0)
            })
        }

        val scroll = ScrollView(this).apply { addView(container) }
        AlertDialog.Builder(this)
            .setTitle("选词填空详情")
            .setView(scroll)
            .setPositiveButton("关闭", null)
            .setNegativeButton("删除") { _, _ ->
                AlarmChallengeManager.removeBookmarkedCloze(this, cloze.clozeId, cloze.bookmarkedWord)
                toastAfterRemove(cloze)
                renderClozeList()
            }
            .show()
    }

    private fun toastAfterRemove(cloze: AlarmChallengeManager.BookmarkedCloze) {
        android.widget.Toast.makeText(
            this,
            "已删除：${cloze.year}年${cloze.questionNo}题" +
                if (!cloze.bookmarkedWord.isNullOrEmpty()) "（${cloze.bookmarkedWord}）" else "",
            android.widget.Toast.LENGTH_SHORT
        ).show()
    }

    private val density: Float
        get() = resources.displayMetrics.density

    private fun resolvePhonetic(word: String, stored: String?): String? {
        if (!stored.isNullOrEmpty()) return stored
        val lib = AlarmChallengeManager.lookupWordWithLemma(word) ?: return null
        val ph = lib.us.ifEmpty { lib.uk }
        return if (ph.isNotEmpty()) "/$ph/" else null
    }

    private fun formatBookmarkTime(ms: Long): String {
        if (ms <= 0L) return ""
        return try {
            val date = Date(ms)
            val cal = java.util.Calendar.getInstance().apply { timeInMillis = System.currentTimeMillis() }
            val thisYear = cal.get(java.util.Calendar.YEAR)
            val targetYear = java.util.Calendar.getInstance().apply { timeInMillis = ms }.get(java.util.Calendar.YEAR)
            val pattern = if (targetYear == thisYear) "MM-dd" else "yyyy-MM-dd"
            SimpleDateFormat(pattern, Locale.getDefault()).format(date)
        } catch (_: Exception) { "" }
    }

    private fun buildItemRow(item: BookmarkItem): LinearLayout {
        val d = density
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedBg(attrColor(R.attr.zzSurface), 17f)
            setPadding((14 * d).toInt(), (12 * d).toInt(), (14 * d).toInt(), (12 * d).toInt())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, (5 * d).toInt(), 0, (5 * d).toInt()) }

            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL

                addView(TextView(context).apply {
                    text = item.primary
                    textSize = 16f
                    setTextColor(attrColor(R.attr.zzText))
                    setTypeface(null, android.graphics.Typeface.BOLD)
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                })

                addView(TextView(context).apply {
                    text = "×${item.skipCount}"
                    textSize = 11f
                    setTypeface(null, android.graphics.Typeface.BOLD)
                    gravity = Gravity.CENTER
                    val (fg, bg) = when {
                        item.skipCount >= 5 -> attrColor(R.attr.zzDanger) to attrColor(R.attr.zzDangerSoft)
                        item.skipCount >= 3 -> attrColor(R.attr.zzWarning) to attrColor(R.attr.zzWarningSoft)
                        else -> attrColor(R.attr.zzMuted) to attrColor(R.attr.zzSurface2)
                    }
                    setTextColor(fg)
                    background = roundedBg(bg, 12f)
                    setPadding((10 * d).toInt(), (3 * d).toInt(), (10 * d).toInt(), (3 * d).toInt())
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { marginStart = (8 * d).toInt() }
                })

                if ((currentSortBy == "time" || currentTimeRange.isNotEmpty()) && item.bookmarkedAtMs > 0L) {
                    addView(TextView(context).apply {
                        text = formatBookmarkTime(item.bookmarkedAtMs)
                        textSize = 11f
                        setTextColor(attrColor(R.attr.zzAccentStrong))
                        setTypeface(null, android.graphics.Typeface.BOLD)
                        gravity = Gravity.CENTER
                        background = roundedBg(attrColor(R.attr.zzAccentSoft), 12f)
                        setPadding((10 * d).toInt(), (3 * d).toInt(), (10 * d).toInt(), (3 * d).toInt())
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                        ).apply { marginStart = (8 * d).toInt() }
                    })
                }
            })

            addView(TextView(context).apply {
                text = buildString {
                    append(item.secondary)
                    if (!item.phonetic.isNullOrEmpty()) append("\n${item.phonetic}")
                }
                textSize = 13f
                setTextColor(attrColor(R.attr.zzMuted))
                setPadding(0, (5 * d).toInt(), 0, 0)
            })
        }
    }

    private fun renderCustomBankList(bankId: String, query: String) {
        val bank = CustomQuestionBankManager.getBank(this, bankId)
        val questions = CustomQuestionBankManager.getFavoritesByBank(this, bankId)
        val filtered = if (query.isBlank()) questions else {
            questions.filter { q ->
                q.stem.contains(query, ignoreCase = true) ||
                    q.explanation.contains(query, ignoreCase = true) ||
                    q.options.any { it.contains(query, ignoreCase = true) }
            }
        }
        containerList.removeAllViews()
        val bankTitle = bank?.title ?: "题库"
        if (filtered.isEmpty()) {
            val msg = if (query.isNotEmpty()) "未找到匹配的题目" else "暂无「$bankTitle」收藏题目\n可在闹钟挑战复习或题库回顾中点击「☆ 收藏」"
            containerList.addView(TextView(this).apply {
                text = msg
                textSize = 14f
                setTextColor(attrColor(R.attr.zzSubtle))
                gravity = Gravity.CENTER
                setPadding(0, (24 * density).toInt(), 0, 0)
            })
            return
        }

        val dp = { n: Int -> (n * density).toInt() }
        for (q in filtered) {
            val card = CardView(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(10) }
                radius = dp(12).toFloat()
                cardElevation = 0f
                setCardBackgroundColor(attrColor(R.attr.zzSurface))
            }
            val layout = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), dp(10), dp(12), dp(10))
            }
            val topRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            topRow.addView(TextView(this).apply {
                text = q.stem
                textSize = 13f
                setTypeface(null, Typeface.BOLD)
                setTextColor(attrColor(R.attr.zzText))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            val btnUnfav = TextView(this).apply {
                text = "取消收藏"
                textSize = 11f
                setTextColor(attrColor(R.attr.zzDanger))
                background = roundedBg(attrColor(R.attr.zzDangerSoft), 6f)
                setPadding(dp(6), dp(2), dp(6), dp(2))
                isClickable = true
                setOnClickListener {
                    CustomQuestionBankManager.setFavorite(this@BookmarkActivity, q.bankId, q.id, false)
                    Toast.makeText(this@BookmarkActivity, "已取消收藏", Toast.LENGTH_SHORT).show()
                    renderCustomBankList(bankId, etSearch.text?.toString() ?: "")
                }
            }
            topRow.addView(btnUnfav)
            layout.addView(topRow)

            if (q.options.isNotEmpty()) {
                val optText = q.options.mapIndexed { idx, opt ->
                    val label = ('A' + idx).toString()
                    "$label. $opt"
                }.joinToString("   ")
                layout.addView(TextView(this).apply {
                    text = optText
                    textSize = 12f
                    setTextColor(attrColor(R.attr.zzMuted))
                    setPadding(0, dp(4), 0, 0)
                })
            }

            layout.addView(TextView(this).apply {
                text = "答案: ${q.answer}" + if (q.getAnswerContent().isNotEmpty()) ". ${q.getAnswerContent()}" else ""
                textSize = 12f
                setTextColor(attrColor(R.attr.zzAccent))
                setPadding(0, dp(4), 0, 0)
            })
            if (q.explanation.isNotBlank()) {
                layout.addView(TextView(this).apply {
                    text = "解析: ${q.explanation}"
                    textSize = 11f
                    setTextColor(attrColor(R.attr.zzMuted))
                    setPadding(0, dp(2), 0, 0)
                })
            }
            card.addView(layout)
            containerList.addView(card)
        }
    }
}
