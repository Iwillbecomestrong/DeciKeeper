/**
 * =================================================================================
 * [Context Header]
 * * @file: HelpActivity.kt
 * * @role: UI
 * * @description: 用户手册页面，支持 Markdown 渲染、搜索和目录跳转。
 * * @responsibilities:
 * 1. 读取并渲染构建生成的使用手册资源。
 * 2. 提供目录导航（可点击跳转）。
 * 3. 提供实时搜索过滤与结果高亮。
 * * @context:
 * - 上游: HomeFragment / 设置入口
 * - 下游: docs/使用手册.md 构建生成的 asset
 * * @constraints (必读):
 * 1. 文案必须与实际功能保持一致。
 * 2. 目录标题从文案中自动解析。
 * 3. docs/使用手册.md 是唯一完整用户手册来源；App 内不得维护第二份硬编码正文。
 * =================================================================================
 */
package com.zhizhi.app.ui

import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.SpannableString
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewParent
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.drawerlayout.widget.DrawerLayout
import io.noties.markwon.Markwon
import io.noties.markwon.ext.tables.TablePlugin

class HelpActivity : AppCompatActivity() {

    private data class HelpSection(
        val title: String,
        val content: String,
        val level: Int
    )

    private lateinit var contentContainer: LinearLayout
    private lateinit var markwon: Markwon
    private var allSections: List<HelpSection> = emptyList()
    private var searchQuery: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 边缘到边缘布局，手动处理状态栏避让（确保前置摄像头区域正确留白）
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                )
        }

        markwon = Markwon.builder(this)
            .usePlugin(TablePlugin.create(this))
            .build()

        // 解析帮助内容为结构化章节；正文仍保留 Markdown，由 Markwon 负责渲染。
        allSections = parseSections(loadHelpText())
        buildUI()
    }

    // ========== 章节解析 ==========

    private fun loadHelpText(): String =
        assets.open("使用手册.md").bufferedReader(Charsets.UTF_8).use { it.readText() }

    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()

    private fun statusBarHeight(): Int {
        val resId = resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (resId > 0) resources.getDimensionPixelSize(resId) else dp(24)
    }

    private fun parseSections(fullText: String): List<HelpSection> {
        val sections = mutableListOf<HelpSection>()
        val lines = fullText.split("\n")
        var currentTitle = ""
        var currentContent = StringBuilder()
        var currentLevel = 1

        fun flush() {
            val content = currentContent.toString().trim('\n', ' ')
            if (currentTitle.isNotEmpty()) {
                sections.add(HelpSection(currentTitle, content, currentLevel))
            }
        }

        fun startSection(title: String, level: Int) {
            flush()
            currentTitle = title
            currentContent = StringBuilder()
            currentLevel = level
        }

        for (line in lines) {
            val trimmed = line.trim()
            val markdownLevel = trimmed.takeWhile { it == '#' }.length
            val markdownTitle = if (
                markdownLevel in 1..3 &&
                trimmed.length > markdownLevel &&
                trimmed[markdownLevel] == ' '
            ) {
                trimmed.substring(markdownLevel + 1).trim()
            } else {
                null
            }

            val legacyH1 = Regex("^[\u4e00\u4e8c\u4e09\u56db\u4e94\u516d\u4e03\u516b\u4e5d\u5341]+\u3001.+$").find(trimmed)
            val legacyH2 = Regex("^\\d+\\.\\d+\\s.+$").find(trimmed)

            when {
                markdownTitle != null -> {
                    val level = if (Regex("^\\d+\\.\\d+\\s.+$").matches(markdownTitle)) 2 else 1
                    startSection(markdownTitle, level)
                }
                legacyH1 != null -> startSection(trimmed, 1)
                legacyH2 != null -> startSection(trimmed, 2)
                trimmed == "---" -> {
                    // App 已经在章节之间绘制分隔线，不重复显示 Markdown 水平线。
                }
                currentTitle.isNotEmpty() -> {
                    // 保留原始换行、列表缩进和表格语法，不能在这里把 Markdown 拍平成纯文本。
                    if (currentContent.isNotEmpty()) currentContent.append("\n")
                    currentContent.append(line.trimEnd())
                }
            }
        }

        flush()
        return sections
    }

    // ========== UI 构建 ==========

    private fun buildUI() {
        val sbh = statusBarHeight()

        val drawerLayout = DrawerLayout(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        val mainContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(Color.parseColor("#F5F5F5"))
        }

        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setPadding(dp(24), sbh + dp(16), dp(24), dp(16))
            gravity = Gravity.CENTER_VERTICAL
        }

        val btnToc = TextView(this).apply {
            text = "☰"
            textSize = 24f
            setTextColor(Color.parseColor("#00838F"))
            setPadding(dp(12), dp(8), dp(12), dp(8))
            setOnClickListener { drawerLayout.openDrawer(Gravity.START) }
        }
        topBar.addView(btnToc)

        val tvTitle = TextView(this).apply {
            text = "📘 知止 用户手册"
            textSize = 20f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#00838F"))
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f
            ).apply { setMargins(0, 0, 48, 0) }
        }
        topBar.addView(tvTitle)
        mainContent.addView(topBar)

        val searchFrame = FrameLayout(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setPadding(dp(32), dp(12), dp(32), dp(8))
        }
        val editText = EditText(this).apply {
            hint = "🔍 搜索手册内容..."
            textSize = 14f
            setTextColor(Color.parseColor("#333333"))
            setHintTextColor(Color.parseColor("#9E9E9E"))
            setBackgroundResource(android.R.drawable.editbox_background)
            setPadding(dp(36), dp(16), dp(36), dp(16))
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            setOnEditorActionListener { _, action, _ ->
                if (action == EditorInfo.IME_ACTION_SEARCH) {
                    searchQuery = text.toString().trim()
                    filterAndRender()
                    true
                } else {
                    false
                }
            }
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit

                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    searchQuery = s?.toString()?.trim() ?: ""
                    filterAndRender()
                }

                override fun afterTextChanged(s: android.text.Editable?) = Unit
            })
        }
        searchFrame.addView(editText)
        mainContent.addView(searchFrame)

        val scrollView = ScrollView(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            isFillViewport = true
        }

        contentContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setPadding(dp(32), dp(8), dp(32), dp(48))
        }
        scrollView.addView(contentContainer)
        mainContent.addView(scrollView)
        drawerLayout.addView(mainContent)

        val drawerContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = DrawerLayout.LayoutParams(
                dp(280),
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            (layoutParams as DrawerLayout.LayoutParams).gravity = Gravity.START
            setBackgroundColor(Color.parseColor("#FAFAFA"))
            setPadding(dp(24), sbh + dp(24), dp(24), dp(24))
        }

        val drawerTitle = TextView(this).apply {
            text = "📑 目录"
            textSize = 18f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#006064"))
            setPadding(0, dp(24), 0, dp(20))
        }
        drawerContent.addView(drawerTitle)

        val tocScroll = ScrollView(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0
            )
        }
        tocScroll.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            0,
            1f
        )

        val tocList = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        for (section in allSections) {
            val isMajor = section.level == 1
            val indent = if (isMajor) 0 else 32

            val tvItem = TextView(this).apply {
                text = section.title
                textSize = if (isMajor) 15f else 13f
                setTypeface(null, if (isMajor) Typeface.BOLD else Typeface.NORMAL)
                setTextColor(Color.parseColor(if (isMajor) "#006064" else "#00838F"))
                setPadding(dp(indent), dp(10), dp(16), dp(10))
                setOnClickListener {
                    drawerLayout.closeDrawer(Gravity.START)
                    searchQuery = ""
                    filterAndRender()
                    scrollView.post {
                        scrollToSectionInContainer(section.title)
                    }
                }
            }
            tocList.addView(tvItem)
        }
        tocScroll.addView(tocList)
        drawerContent.addView(tocScroll)

        val tvFooter = TextView(this).apply {
            text = "共 ${allSections.size} 个章节"
            textSize = 11f
            setTextColor(Color.parseColor("#BDBDBD"))
            gravity = Gravity.CENTER
            setPadding(0, dp(16), 0, dp(8))
        }
        drawerContent.addView(tvFooter)

        drawerLayout.addView(drawerContent)
        setContentView(drawerLayout)
        filterAndRender()
    }

    /** 在 contentContainer 中滚动到匹配标题的第一个 View。 */
    private fun scrollToSectionInContainer(title: String) {
        for (i in 0 until contentContainer.childCount) {
            val child = contentContainer.getChildAt(i)
            if (child !is TextView) continue

            val text = child.text.toString()
            if (text != title && !text.startsWith(title)) continue

            val parent = child.parent
            if (parent is ScrollView) {
                parent.smoothScrollTo(0, child.top - dp(80))
            } else if (parent is ViewGroup) {
                var p: ViewParent? = parent.parent
                while (p != null) {
                    if (p is ScrollView) {
                        p.smoothScrollTo(0, child.top - dp(80))
                        break
                    }
                    p = p.parent
                }
            }
            return
        }
    }

    // ========== 过滤与渲染 ==========

    private fun filterAndRender() {
        contentContainer.removeAllViews()
        contentContainer.visibility = View.VISIBLE

        val query = searchQuery.lowercase().trim()
        val matched = if (query.isEmpty()) {
            allSections
        } else {
            allSections.filter { section ->
                section.title.lowercase().contains(query) ||
                    section.content.lowercase().contains(query)
            }
        }

        if (query.isNotEmpty()) {
            addSearchResultHeader(contentContainer, "找到 ${matched.size} 个相关章节")
        }

        if (matched.isEmpty() && query.isNotEmpty()) {
            val tvEmpty = TextView(this).apply {
                text = "未找到匹配内容"
                textSize = 16f
                setTextColor(Color.parseColor("#9E9E9E"))
                gravity = Gravity.CENTER
                setPadding(0, dp(80), 0, dp(80))
            }
            contentContainer.addView(tvEmpty)
            return
        }

        for ((index, section) in matched.withIndex()) {
            val highlightColor = Color.parseColor("#FFFFEB3B")
            val tvSectionTitle = if (section.level == 1) {
                buildSectionTitle(section.title, 18f, "#006064", Typeface.BOLD)
            } else {
                buildSectionTitle(section.title, 15f, "#00838F", Typeface.NORMAL)
            }

            if (query.isNotEmpty() && section.title.lowercase().contains(query)) {
                tvSectionTitle.text = highlightedText(tvSectionTitle.text, query, highlightColor, maxMatches = 1)
            }
            contentContainer.addView(tvSectionTitle)

            val tvBody = buildContentText(section.content, query, highlightColor)
            contentContainer.addView(tvBody)

            if (index < matched.size - 1) {
                val divider = View(this).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        1
                    ).apply { setMargins(0, dp(12), 0, dp(12)) }
                    setBackgroundColor(Color.parseColor("#1A000000"))
                }
                contentContainer.addView(divider)
            }
        }
    }

    // ========== 组件构建 ==========

    private fun addSearchResultHeader(container: LinearLayout, text: String) {
        val tv = TextView(this).apply {
            this.text = text
            textSize = 14f
            setTextColor(Color.parseColor("#757575"))
            setPadding(0, 0, 0, dp(16))
        }
        container.addView(tv)
    }

    private fun buildSectionTitle(text: String, size: Float, color: String, style: Int): TextView {
        return TextView(this).apply {
            this.text = text
            textSize = size
            setTypeface(null, style)
            setTextColor(Color.parseColor(color))
            setPadding(0, dp(4), 0, dp(8))
        }
    }

    private fun buildContentText(text: String, query: String, highlightColor: Int): TextView {
        val tv = TextView(this).apply {
            textSize = 15f
            setTextColor(Color.parseColor("#424242"))
            setLineSpacing(8f, 1f)
            setPadding(0, dp(4), 0, dp(4))
        }

        markwon.setMarkdown(tv, text)

        if (query.isNotEmpty()) {
            tv.text = highlightedText(tv.text, query, highlightColor, maxMatches = 5)
        }
        return tv
    }

    private fun highlightedText(
        source: CharSequence,
        query: String,
        highlightColor: Int,
        maxMatches: Int
    ): CharSequence {
        if (query.isEmpty()) return source

        val lower = source.toString().lowercase()
        val spannable = SpannableString(source)
        var searchFrom = 0
        var count = 0
        while (count < maxMatches) {
            val index = lower.indexOf(query, searchFrom)
            if (index < 0) break
            spannable.setSpan(
                BackgroundColorSpan(highlightColor),
                index,
                index + query.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            searchFrom = index + query.length
            count++
        }
        return spannable
    }
}
