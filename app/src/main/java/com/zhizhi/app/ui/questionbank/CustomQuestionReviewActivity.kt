package com.zhizhi.app.ui.questionbank

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.cardview.widget.CardView
import com.zhizhi.app.R
import com.zhizhi.app.data.questionbank.CustomQuestion
import com.zhizhi.app.manager.questionbank.CustomQuestionBankManager
import com.zhizhi.app.ui.ThemeManager

class CustomQuestionReviewActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_BANK_ID = "extra_bank_id"
        const val EXTRA_ONLY_MISTAKES = "extra_only_mistakes"
    }

    private var targetBankId: String? = null
    private var onlyMistakes: Boolean = false

    private lateinit var tvTitle: TextView
    private lateinit var btnTabAll: Button
    private lateinit var btnTabMistakes: Button
    private lateinit var tvCountSummary: TextView
    private lateinit var layoutEmpty: LinearLayout
    private lateinit var tvEmptyText: TextView
    private lateinit var containerQuestions: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.apply(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_custom_question_review)

        targetBankId = intent.getStringExtra(EXTRA_BANK_ID)
        onlyMistakes = intent.getBooleanExtra(EXTRA_ONLY_MISTAKES, false)

        tvTitle = findViewById(R.id.tv_title)
        btnTabAll = findViewById(R.id.btn_tab_all)
        btnTabMistakes = findViewById(R.id.btn_tab_mistakes)
        tvCountSummary = findViewById(R.id.tv_count_summary)
        layoutEmpty = findViewById(R.id.layout_empty)
        tvEmptyText = findViewById(R.id.tv_empty_text)
        containerQuestions = findViewById(R.id.container_questions)

        findViewById<Button>(R.id.btn_back).setOnClickListener { finish() }

        // Setup title
        if (!targetBankId.isNullOrBlank()) {
            val bank = CustomQuestionBankManager.getBanks(this).find { it.id == targetBankId }
            tvTitle.text = bank?.title ?: "题库回顾"
        } else {
            tvTitle.text = if (onlyMistakes) "错题回顾" else "全部题库回顾"
        }

        btnTabAll.setOnClickListener {
            onlyMistakes = false
            updateTabStyle()
            renderList()
        }

        btnTabMistakes.setOnClickListener {
            onlyMistakes = true
            updateTabStyle()
            renderList()
        }

        updateTabStyle()
        renderList()
    }

    private fun attrColor(attrId: Int): Int {
        val typedValue = TypedValue()
        theme.resolveAttribute(attrId, typedValue, true)
        return typedValue.data
    }

    private fun updateTabStyle() {
        if (!onlyMistakes) {
            btnTabAll.backgroundTintList = ColorStateList.valueOf(attrColor(R.attr.zzAccent))
            btnTabAll.setTextColor(Color.WHITE)
            btnTabAll.paint.isFakeBoldText = true

            btnTabMistakes.backgroundTintList = ColorStateList.valueOf(attrColor(R.attr.zzSurface2))
            btnTabMistakes.setTextColor(attrColor(R.attr.zzText))
            btnTabMistakes.paint.isFakeBoldText = true
        } else {
            btnTabMistakes.backgroundTintList = ColorStateList.valueOf(attrColor(R.attr.zzAccent))
            btnTabMistakes.setTextColor(Color.WHITE)
            btnTabMistakes.paint.isFakeBoldText = true

            btnTabAll.backgroundTintList = ColorStateList.valueOf(attrColor(R.attr.zzSurface2))
            btnTabAll.setTextColor(attrColor(R.attr.zzText))
            btnTabAll.paint.isFakeBoldText = true
        }
    }

    private fun renderList() {
        val banks = CustomQuestionBankManager.getBanks(this)
        val questions = mutableListOf<CustomQuestion>()

        if (!targetBankId.isNullOrBlank()) {
            val target = banks.find { it.id == targetBankId }
            if (target != null) {
                questions.addAll(target.questions)
            }
        } else {
            // All enabled banks (or all banks if none enabled)
            val activeBanks = banks.filter { it.enabled }.ifEmpty { banks }
            activeBanks.forEach { questions.addAll(it.questions) }
        }

        val filtered = if (onlyMistakes) {
            questions.filter { it.wrongCount > 0 }
        } else {
            questions
        }

        containerQuestions.removeAllViews()
        tvCountSummary.text = "共 ${filtered.size} 道题目"

        if (filtered.isEmpty()) {
            layoutEmpty.visibility = View.VISIBLE
            tvEmptyText.text = if (onlyMistakes) "🎉 太棒了，当前没有错题！" else "暂无题目数据"
            return
        }
        layoutEmpty.visibility = View.GONE

        val density = resources.displayMetrics.density
        val dp = { n: Int -> (n * density).toInt() }

        filtered.forEachIndexed { index, question ->
            containerQuestions.addView(buildQuestionCard(index + 1, question, dp))
        }
    }

    private fun buildQuestionCard(index: Int, q: CustomQuestion, dp: (Int) -> Int): View {
        val card = CardView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(12) }
            radius = dp(14).toFloat()
            cardElevation = 0f
            setCardBackgroundColor(attrColor(R.attr.zzSurface))
        }

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }

        // 1. Header: Index + Mistake badge
        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val tvHeader = TextView(this).apply {
            text = "第 $index 题"
            textSize = 13f
            setTypeface(null, Typeface.BOLD)
            setTextColor(attrColor(R.attr.zzText))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        headerRow.addView(tvHeader)

        if (q.wrongCount > 0) {
            val tvMistakeBadge = TextView(this).apply {
                text = "错 ${q.wrongCount} 次"
                textSize = 11f
                setTypeface(null, Typeface.BOLD)
                setTextColor(attrColor(R.attr.zzDanger))
                background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(attrColor(R.attr.zzDangerSoft))
                    cornerRadius = dp(6).toFloat()
                }
                setPadding(dp(6), dp(2), dp(6), dp(2))
            }
            headerRow.addView(tvMistakeBadge)
        }
        layout.addView(headerRow)

        // Divider
        layout.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)).apply {
                setMargins(0, dp(6), 0, dp(6))
            }
            setBackgroundColor(attrColor(R.attr.zzLine))
        })

        // 2. Stem
        val tvStem = TextView(this).apply {
            text = q.stem
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            setTextColor(attrColor(R.attr.zzText))
            setLineSpacing(0f, 1.2f)
            setPadding(0, 0, 0, dp(8))
        }
        layout.addView(tvStem)

        // 3. Options
        val letters = listOf("A", "B", "C", "D")
        q.options.forEachIndexed { optIdx, optText ->
            val letter = letters.getOrNull(optIdx) ?: "A"
            val isCorrect = letter.equals(q.answer, ignoreCase = true)
            val marker = if (isCorrect) "  ✓" else ""
            val optColor = if (isCorrect) attrColor(R.attr.zzSuccess) else attrColor(R.attr.zzMuted)

            val tvOption = TextView(this).apply {
                text = "$letter. $optText$marker"
                textSize = 13f
                setTypeface(null, if (isCorrect) Typeface.BOLD else Typeface.NORMAL)
                setTextColor(optColor)
                setPadding(dp(6), dp(2), 0, dp(2))
            }
            layout.addView(tvOption)
        }

        // 4. Answer & Explanation
        val tvAnswer = TextView(this).apply {
            text = "✏️ 正确答案：选项 ${q.answer}"
            textSize = 13f
            setTypeface(null, Typeface.BOLD)
            setTextColor(attrColor(R.attr.zzSuccess))
            setPadding(0, dp(6), 0, dp(2))
        }
        layout.addView(tvAnswer)

        if (q.explanation.isNotBlank()) {
            val tvExplanation = TextView(this).apply {
                text = "💡 解析：${q.explanation}"
                textSize = 12f
                setTextColor(attrColor(R.attr.zzMuted))
                setLineSpacing(0f, 1.2f)
                setPadding(0, dp(2), 0, dp(4))
            }
            layout.addView(tvExplanation)
        }

        // 5. Clear Mistake Action
        if (q.wrongCount > 0) {
            val btnClearMistake = Button(this).apply {
                text = "移出错题本"
                textSize = 12f
                setTextColor(attrColor(R.attr.zzText))
                backgroundTintList = ColorStateList.valueOf(attrColor(R.attr.zzSurface2))
                minHeight = 0
                minWidth = 0
                setPadding(dp(10), dp(4), dp(10), dp(4))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    gravity = Gravity.END
                    topMargin = dp(6)
                }
                setOnClickListener {
                    CustomQuestionBankManager.resetMistake(this@CustomQuestionReviewActivity, q.bankId, q.id)
                    Toast.makeText(this@CustomQuestionReviewActivity, "已移出错题本", Toast.LENGTH_SHORT).show()
                    renderList()
                }
            }
            layout.addView(btnClearMistake)
        }

        card.addView(layout)
        return card
    }
}
