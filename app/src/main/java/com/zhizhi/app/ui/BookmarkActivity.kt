package com.zhizhi.app.ui

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
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

    private data class ClozeListItem(
        val cloze: AlarmChallengeManager.BookmarkedCloze
    )

    private lateinit var containerList: LinearLayout
    private lateinit var scrollList: ScrollView
    private lateinit var etSearch: EditText
    private var currentType: String = TYPE_POEMS
    private var currentSortBy: String = "skip"
    private var currentTimeRange: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_bookmark)

        currentType = intent.getStringExtra(EXTRA_TYPE) ?: TYPE_POEMS

        val titleView = findViewById<TextView>(R.id.tv_title)
        titleView.text = titleFor(currentType)

        findViewById<Button>(R.id.btn_back).setOnClickListener {
            finish()
        }

        containerList = findViewById(R.id.container_list)
        scrollList = findViewById(R.id.scroll_list)
        etSearch = findViewById<EditText>(R.id.et_search).also {
            // cloze tab 不支持搜索（结构化内容，搜索意义不大；C0 简单列出）
            it.visibility = if (currentType == TYPE_CLOZES) android.view.View.GONE else android.view.View.VISIBLE
        }

        // Tab 切换
        findViewById<Button>(R.id.btn_tab_poems).setOnClickListener { switchTab(TYPE_POEMS) }
        findViewById<Button>(R.id.btn_tab_words).setOnClickListener { switchTab(TYPE_WORDS) }
        findViewById<Button>(R.id.btn_tab_clozes).setOnClickListener { switchTab(TYPE_CLOZES) }
        refreshTabHighlight()

        // 排序切换：按跳过次数 / 按收藏时间
        findViewById<Button>(R.id.btn_sort_skip).setOnClickListener { setSortBy("skip") }
        findViewById<Button>(R.id.btn_sort_time).setOnClickListener { setSortBy("time") }

        // 时间过滤：全部 / 近7天 / 近30天 / 近90天
        findViewById<Button>(R.id.btn_range_all).setOnClickListener { setTimeRange("") }
        findViewById<Button>(R.id.btn_range_7d).setOnClickListener { setTimeRange("7d") }
        findViewById<Button>(R.id.btn_range_30d).setOnClickListener { setTimeRange("30d") }
        findViewById<Button>(R.id.btn_range_90d).setOnClickListener { setTimeRange("90d") }

        renderList("")

        // 初始高亮：默认 跳过次数 + 全部
        refreshFilterHighlight()

        etSearch.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                renderList(s?.toString() ?: "")
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })
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
                setTextColor(Color.parseColor("#90A4AE"))
                gravity = Gravity.CENTER
                setPadding(0, (24 * density).toInt(), 0, 0)
            })
            return
        }
        items.forEach { item ->
            containerList.addView(buildItemRow(item))
        }
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

    /** 刷新排序 / 时间过滤按钮的高亮状态（选中=深青底白字，未选中=浅青底深字） */
    private fun refreshFilterHighlight() {
        val activeColor = Color.parseColor("#FFFFFF")
        val activeBg = Color.parseColor("#00838F")
        val inactiveColor = Color.parseColor("#00695C")
        val inactiveBg = Color.parseColor("#E0F2F1")

        val apply = { btn: Button, isActive: Boolean ->
            btn.setTextColor(if (isActive) activeColor else inactiveColor)
            btn.setBackgroundColor(if (isActive) activeBg else inactiveBg)
        }

        apply(findViewById<Button>(R.id.btn_sort_skip), currentSortBy == "skip")
        apply(findViewById<Button>(R.id.btn_sort_time), currentSortBy == "time")
        apply(findViewById<Button>(R.id.btn_range_all), currentTimeRange.isEmpty())
        apply(findViewById<Button>(R.id.btn_range_7d), currentTimeRange == "7d")
        apply(findViewById<Button>(R.id.btn_range_30d), currentTimeRange == "30d")
        apply(findViewById<Button>(R.id.btn_range_90d), currentTimeRange == "90d")
    }

    private fun titleFor(type: String): String = when (type) {
        TYPE_POEMS -> "📜 收藏的诗词"
        TYPE_WORDS -> "📖 收藏的单词"
        TYPE_CLOZES -> "📚 收藏的选词填空"
        else -> "收藏本"
    }

    private fun switchTab(type: String) {
        if (type == currentType) return
        currentType = type
        findViewById<TextView>(R.id.tv_title).text = titleFor(type)
        etSearch.setText("")
        etSearch.visibility = if (type == TYPE_CLOZES) android.view.View.GONE else android.view.View.VISIBLE
        refreshTabHighlight()
        // 直接重新渲染
        if (type == TYPE_CLOZES) {
            renderClozeList()
        } else {
            // 触发一次空查询渲染
            etSearch.setText("")
        }
    }

    private fun refreshTabHighlight() {
        val d = density
        val activeColor = Color.parseColor("#FFFFFF")
        val activeBg = Color.parseColor("#00838F")
        val inactiveColor = Color.parseColor("#004D40")
        val inactiveBg = Color.parseColor("#00000000")
        listOf(
            TYPE_POEMS to R.id.btn_tab_poems,
            TYPE_WORDS to R.id.btn_tab_words,
            TYPE_CLOZES to R.id.btn_tab_clozes
        ).forEach { (t, id) ->
            val btn = findViewById<Button>(id)
            if (t == currentType) {
                btn.setTextColor(activeColor)
                btn.setBackgroundColor(activeBg)
            } else {
                btn.setTextColor(inactiveColor)
                btn.setBackgroundColor(inactiveBg)
            }
        }
    }

    /**
     * C0: 简单列出 cloze 收藏（按 year/questionNo 已由 Manager 排序）。
     * R1 会做按题目紧凑分组（同一真题的不同单词合并展示）。
     */
    private fun renderClozeList() {
        val clozes = AlarmChallengeManager.getBookmarkedClozes(this)
        containerList.removeAllViews()
        if (clozes.isEmpty()) {
            containerList.addView(TextView(this).apply {
                text = "暂无收藏的选词填空"
                textSize = 14f
                setTextColor(Color.parseColor("#90A4AE"))
                gravity = Gravity.CENTER
                setPadding(0, (24 * density).toInt(), 0, 0)
            })
            return
        }
        clozes.forEach { cloze ->
            containerList.addView(buildClozeRow(cloze))
        }
    }

    private fun buildClozeRow(cloze: AlarmChallengeManager.BookmarkedCloze): LinearLayout {
        val d = density
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#FFFFFF"))
            setPadding((12 * d).toInt(), (10 * d).toInt(), (12 * d).toInt(), (10 * d).toInt())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, (4 * d).toInt(), 0, (4 * d).toInt())
            }
            isClickable = true
            isFocusable = true
            setOnClickListener { showClozeDetail(cloze) }
        }

        // 标题: 年份 + 题号
        val headerText = if (cloze.year > 0 && cloze.questionNo > 0) {
            "${cloze.year} 年 第 ${cloze.questionNo} 题"
        } else {
            "选词填空"
        }
        row.addView(TextView(this).apply {
            text = headerText
            textSize = 14f
            setTextColor(Color.parseColor("#00838F"))
            setTypeface(null, android.graphics.Typeface.BOLD)
        })

        // 题面 sentence（已 cleanClozeSentence，可能含 __________）
        row.addView(TextView(this).apply {
            text = cloze.sentence.ifEmpty { "（题面为空）" }
            textSize = 13f
            setTextColor(Color.parseColor("#212121"))
            maxLines = 3
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(0, (4 * d).toInt(), 0, 0)
        })

        // 答案行
        row.addView(TextView(this).apply {
            text = "答案: ${cloze.answer}" +
                if (cloze.answerTranslation.isNotEmpty()) "  ${cloze.answerTranslation}" else ""
            textSize = 13f
            setTextColor(Color.parseColor("#388E3C"))
            setPadding(0, (2 * d).toInt(), 0, 0)
        })

        // 收藏的单词（如有）
        if (!cloze.bookmarkedWord.isNullOrEmpty()) {
            row.addView(TextView(this).apply {
                text = "📌 ${cloze.bookmarkedWord}"
                textSize = 12f
                setTextColor(Color.parseColor("#D32F2F"))
                setPadding(0, (2 * d).toInt(), 0, 0)
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
        // 标题
        val titleText = if (cloze.year > 0 && cloze.questionNo > 0) {
            "${cloze.year} 年 第 ${cloze.questionNo} 题"
        } else "选词填空"
        container.addView(TextView(this).apply {
            text = titleText
            textSize = 16f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(Color.parseColor("#00838F"))
        })
        // 题面
        container.addView(TextView(this).apply {
            text = cloze.sentence.ifEmpty { "（题面为空）" }
            textSize = 14f
            setTextColor(Color.parseColor("#212121"))
            setPadding(0, (8 * d).toInt(), 0, 0)
        })
        // 答案 + 释义
        container.addView(TextView(this).apply {
            text = "答案: ${cloze.answer}" +
                if (cloze.answerTranslation.isNotEmpty()) "\n${cloze.answerTranslation}" else ""
            textSize = 14f
            setTextColor(Color.parseColor("#388E3C"))
            setPadding(0, (8 * d).toInt(), 0, 0)
        })
        // 选项（每行独立，可长按查词+收藏）
        if (cloze.options.isNotEmpty()) {
            val labelText = "选项"
            container.addView(TextView(this).apply {
                text = labelText
                textSize = 14f
                setTextColor(Color.parseColor("#616161"))
                setPadding(0, (8 * d).toInt(), 0, (4 * d).toInt())
            })
            listOf("A", "B", "C", "D").forEachIndexed { idx, label ->
                if (idx < cloze.options.size) {
                    val optWord = cloze.options[idx]
                    val meaning = cloze.optionMeanings.getOrNull(idx).orEmpty()
                    val row = LinearLayout(this).apply {
                        orientation = LinearLayout.HORIZONTAL
                        setPadding(0, (4 * d).toInt(), 0, (4 * d).toInt())
                        // 长按查词+收藏
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
                                    setTextColor(Color.parseColor("#00838F"))
                                })
                                addView(TextView(context).apply {
                                    text = displayMeaning
                                    textSize = 14f
                                    setTextColor(Color.parseColor("#212121"))
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
                                    android.widget.Toast.makeText(context, "已收藏: $wordForBookmark", android.widget.Toast.LENGTH_SHORT).show()
                                }
                                .show()
                            true
                        }
                    }
                    row.addView(TextView(this@BookmarkActivity).apply {
                        text = "$label. $optWord"
                        textSize = 13f
                        setTypeface(null, android.graphics.Typeface.BOLD)
                        setTextColor(Color.parseColor("#424242"))
                    })
                    if (meaning.isNotEmpty() && meaning != "（无释义）") {
                        row.addView(TextView(this@BookmarkActivity).apply {
                            text = "  $meaning"
                            textSize = 12f
                            setTextColor(Color.parseColor("#757575"))
                            setPadding((8 * d).toInt(), 0, 0, 0)
                        })
                    }
                    container.addView(row)
                }
            }
        }
        // 节选翻译
        if (cloze.excerptTranslation.isNotEmpty()) {
            container.addView(TextView(this).apply {
                text = "【节选翻译】\n${cloze.excerptTranslation}"
                textSize = 13f
                setTextColor(Color.parseColor("#555555"))
                setPadding(0, (8 * d).toInt(), 0, 0)
            })
        }
        // 收藏的单词
        if (!cloze.bookmarkedWord.isNullOrEmpty()) {
            container.addView(TextView(this).apply {
                text = "📌 收藏单词: ${cloze.bookmarkedWord}"
                textSize = 13f
                setTextColor(Color.parseColor("#D32F2F"))
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
                ToastAfterRemove(cloze)
                renderClozeList()
            }
            .show()
    }

    private fun ToastAfterRemove(cloze: AlarmChallengeManager.BookmarkedCloze) {
        android.widget.Toast.makeText(
            this,
            "已删除: ${cloze.year}年${cloze.questionNo}题" +
                if (!cloze.bookmarkedWord.isNullOrEmpty()) " (${cloze.bookmarkedWord})" else "",
            android.widget.Toast.LENGTH_SHORT
        ).show()
    }

    private val density: Float
        get() = resources.displayMetrics.density

    /**
     * 格式化收藏时间：同年显示 MM-dd，跨年显示 yyyy-MM-dd。
     * 无收藏时间（旧数据 bookmarkedAtMs=0）返回空串。
     */
    /**
     * Plan-A: 展示层音标兜底。收藏项存储的音标为空时，从本地词库补音标（美式优先，否则英式）；
     * 仍查不到则返回 null（不显示）。只影响展示，不改收藏存储。
     */
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
            val targetCal = java.util.Calendar.getInstance().apply { timeInMillis = ms }
            val targetYear = targetCal.get(java.util.Calendar.YEAR)
            val pattern = if (targetYear == thisYear) "MM-dd" else "yyyy-MM-dd"
            SimpleDateFormat(pattern, Locale.getDefault()).format(date)
        } catch (e: Exception) {
            ""
        }
    }

    private fun buildItemRow(item: BookmarkItem): LinearLayout {
        val d = density
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#FFFFFF"))
            setPadding((12 * d).toInt(), (10 * d).toInt(), (12 * d).toInt(), (10 * d).toInt())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, (4 * d).toInt(), 0, (4 * d).toInt())
            }

            addView(LinearLayout(this.context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL

                addView(TextView(this.context).apply {
                    text = item.primary
                    textSize = 16f
                    setTextColor(Color.parseColor("#212121"))
                    setTypeface(null, android.graphics.Typeface.BOLD)
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                })

                addView(TextView(this.context).apply {
                    text = "×${item.skipCount}"
                    textSize = 12f
                    setTextColor(Color.WHITE)
                    setTypeface(null, android.graphics.Typeface.BOLD)
                    gravity = Gravity.CENTER
                    val bgColor = when {
                        item.skipCount >= 5 -> Color.parseColor("#D32F2F")
                        item.skipCount >= 3 -> Color.parseColor("#F57C00")
                        else -> Color.parseColor("#757575")
                    }
                    background = GradientDrawable().apply {
                        setColor(bgColor)
                        cornerRadius = 12f * d
                    }
                    setPadding((10 * d).toInt(), (2 * d).toInt(), (10 * d).toInt(), (2 * d).toInt())
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        marginStart = (8 * d).toInt()
                    }
                })

                // 按收藏时间排序或时间过滤时，额外展示收藏时间徽章
                if (currentSortBy == "time" || currentTimeRange.isNotEmpty()) {
                    if (item.bookmarkedAtMs > 0L) {
                        addView(TextView(this.context).apply {
                            text = formatBookmarkTime(item.bookmarkedAtMs)
                            textSize = 12f
                            setTextColor(Color.WHITE)
                            setTypeface(null, android.graphics.Typeface.BOLD)
                            gravity = Gravity.CENTER
                            background = GradientDrawable().apply {
                                setColor(Color.parseColor("#00838F"))
                                cornerRadius = 12f * d
                            }
                            setPadding((10 * d).toInt(), (2 * d).toInt(), (10 * d).toInt(), (2 * d).toInt())
                            layoutParams = LinearLayout.LayoutParams(
                                LinearLayout.LayoutParams.WRAP_CONTENT,
                                LinearLayout.LayoutParams.WRAP_CONTENT
                            ).apply {
                                marginStart = (8 * d).toInt()
                            }
                        })
                    }
                }
            })

            addView(TextView(this.context).apply {
                text = buildString {
                    append(item.secondary)
                    if (!item.phonetic.isNullOrEmpty()) append("\n${item.phonetic}")
                }
                textSize = 13f
                setTextColor(Color.parseColor("#616161"))
                setPadding(0, (4 * d).toInt(), 0, 0)
            })
        }
    }
}
