package com.zhizhi.app.ui

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.zhizhi.app.R
import com.zhizhi.app.manager.AlarmChallengeManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class BookmarkActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_TYPE = "bookmark_type"
        const val TYPE_POEMS = "poems"
        const val TYPE_WORDS = "words"
        const val TYPE_CLOZES = "clozes"
    }

    private data class BookmarkItem(
        val primary: String,
        val secondary: String,
        val phonetic: String?,
        val skipCount: Int,
        val bookmarkedAtMs: Long
    )

    private lateinit var containerList: LinearLayout
    private lateinit var scrollList: ScrollView
    private lateinit var etSearch: EditText
    private var currentType: String = TYPE_POEMS
    private var currentSortBy: String = "skip"
    private var currentTimeRange: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.apply(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_bookmark)

        currentType = intent.getStringExtra(EXTRA_TYPE) ?: TYPE_POEMS
        findViewById<TextView>(R.id.tv_title).text = titleFor(currentType)
        findViewById<Button>(R.id.btn_back).setOnClickListener { finish() }

        containerList = findViewById(R.id.container_list)
        scrollList = findViewById(R.id.scroll_list)
        etSearch = findViewById<EditText>(R.id.et_search).also {
            it.visibility = if (currentType == TYPE_CLOZES) android.view.View.GONE else android.view.View.VISIBLE
        }

        findViewById<Button>(R.id.btn_tab_poems).setOnClickListener { switchTab(TYPE_POEMS) }
        findViewById<Button>(R.id.btn_tab_words).setOnClickListener { switchTab(TYPE_WORDS) }
        findViewById<Button>(R.id.btn_tab_clozes).setOnClickListener { switchTab(TYPE_CLOZES) }
        findViewById<Button>(R.id.btn_sort_skip).setOnClickListener { setSortBy("skip") }
        findViewById<Button>(R.id.btn_sort_time).setOnClickListener { setSortBy("time") }
        findViewById<Button>(R.id.btn_range_all).setOnClickListener { setTimeRange("") }
        findViewById<Button>(R.id.btn_range_7d).setOnClickListener { setTimeRange("7d") }
        findViewById<Button>(R.id.btn_range_30d).setOnClickListener { setTimeRange("30d") }
        findViewById<Button>(R.id.btn_range_90d).setOnClickListener { setTimeRange("90d") }

        refreshTabHighlight()
        renderList("")
        refreshFilterHighlight()

        etSearch.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) { renderList(s?.toString() ?: "") }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })
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
        if (currentSortBy == sortBy || currentType == TYPE_CLOZES) return
        currentSortBy = sortBy
        refreshFilterHighlight()
        renderList(etSearch.text?.toString() ?: "")
    }

    private fun setTimeRange(timeRange: String) {
        if (currentTimeRange == timeRange || currentType == TYPE_CLOZES) return
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

    private fun titleFor(type: String): String = when (type) {
        TYPE_POEMS -> "收藏的诗词"
        TYPE_WORDS -> "收藏的单词"
        TYPE_CLOZES -> "收藏的选词填空"
        else -> "收藏本"
    }

    private fun switchTab(type: String) {
        if (type == currentType) return
        currentType = type
        findViewById<TextView>(R.id.tv_title).text = titleFor(type)
        etSearch.setText("")
        etSearch.visibility = if (type == TYPE_CLOZES) android.view.View.GONE else android.view.View.VISIBLE
        refreshTabHighlight()
        if (type == TYPE_CLOZES) renderClozeList() else renderList("")
    }

    private fun refreshTabHighlight() {
        listOf(
            TYPE_POEMS to R.id.btn_tab_poems,
            TYPE_WORDS to R.id.btn_tab_words,
            TYPE_CLOZES to R.id.btn_tab_clozes
        ).forEach { (type, id) ->
            val btn = findViewById<Button>(id)
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
}
