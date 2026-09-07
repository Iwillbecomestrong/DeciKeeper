package com.zhizhi.app.ui.questionbank

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.cardview.widget.CardView
import com.zhizhi.app.R
import com.zhizhi.app.data.questionbank.CustomQuestionBank
import com.zhizhi.app.manager.questionbank.CustomQuestionBankManager
import com.zhizhi.app.ui.ThemeManager

class CustomQuestionBankActivity : AppCompatActivity() {

    private lateinit var tvStatBankCount: TextView
    private lateinit var tvStatQuestionCount: TextView
    private lateinit var tvStatMistakeCount: TextView
    private lateinit var layoutEmpty: LinearLayout
    private lateinit var containerBanks: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.apply(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_custom_question_bank)

        tvStatBankCount = findViewById(R.id.tv_stat_bank_count)
        tvStatQuestionCount = findViewById(R.id.tv_stat_question_count)
        tvStatMistakeCount = findViewById(R.id.tv_stat_mistake_count)
        layoutEmpty = findViewById(R.id.layout_empty)
        containerBanks = findViewById(R.id.container_banks)

        findViewById<Button>(R.id.btn_back).setOnClickListener { finish() }

        val startImport = {
            startActivity(Intent(this, CustomQuestionBankImportActivity::class.java))
        }
        findViewById<Button>(R.id.btn_top_import).setOnClickListener { startImport() }
        findViewById<Button>(R.id.btn_empty_import).setOnClickListener { startImport() }

        findViewById<Button>(R.id.btn_review_mistakes).setOnClickListener {
            val intent = Intent(this, CustomQuestionReviewActivity::class.java).apply {
                putExtra(CustomQuestionReviewActivity.EXTRA_ONLY_MISTAKES, true)
            }
            startActivity(intent)
        }

        findViewById<Button>(R.id.btn_all_questions_review).setOnClickListener {
            val intent = Intent(this, CustomQuestionReviewActivity::class.java).apply {
                putExtra(CustomQuestionReviewActivity.EXTRA_ONLY_MISTAKES, false)
            }
            startActivity(intent)
        }
    }

    override fun onResume() {
        super.onResume()
        refreshList()
    }

    private fun attrColor(attrId: Int): Int {
        val typedValue = TypedValue()
        theme.resolveAttribute(attrId, typedValue, true)
        return typedValue.data
    }

    private fun refreshList() {
        val banks = CustomQuestionBankManager.getBanks(this)
        containerBanks.removeAllViews()

        var totalQuestions = 0
        var totalMistakes = 0
        banks.forEach { bank ->
            totalQuestions += bank.questions.size
            totalMistakes += bank.mistakeCount
        }

        tvStatBankCount.text = banks.size.toString()
        tvStatQuestionCount.text = totalQuestions.toString()
        tvStatMistakeCount.text = totalMistakes.toString()

        if (banks.isEmpty()) {
            layoutEmpty.visibility = View.VISIBLE
            return
        }
        layoutEmpty.visibility = View.GONE

        val density = resources.displayMetrics.density
        val dp = { n: Int -> (n * density).toInt() }

        banks.forEach { bank ->
            containerBanks.addView(buildBankCard(bank, dp))
        }
    }

    private fun buildBankCard(bank: CustomQuestionBank, dp: (Int) -> Int): View {
        val card = CardView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(12) }
            radius = dp(16).toFloat()
            cardElevation = 0f
            setCardBackgroundColor(attrColor(R.attr.zzSurface))
        }

        val cardLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }

        // 1. Header: Title + Switch
        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val tvTitle = TextView(this).apply {
            text = bank.title
            textSize = 15f
            setTypeface(null, Typeface.BOLD)
            setTextColor(attrColor(R.attr.zzText))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        headerRow.addView(tvTitle)

        val switchEnable = SwitchCompat(this).apply {
            isChecked = bank.enabled
            setOnCheckedChangeListener { _, isChecked ->
                CustomQuestionBankManager.setBankEnabled(this@CustomQuestionBankActivity, bank.id, isChecked)
                val statusStr = if (isChecked) "已开启参与闹钟" else "已关闭参与闹钟"
                Toast.makeText(this@CustomQuestionBankActivity, "${bank.title} $statusStr", Toast.LENGTH_SHORT).show()
            }
        }
        headerRow.addView(switchEnable)
        cardLayout.addView(headerRow)

        // 2. Stats
        val mistakeCount = bank.mistakeCount
        val tvStats = TextView(this).apply {
            text = "共 ${bank.questions.size} 道题目 · 错题 $mistakeCount 道"
            textSize = 12f
            setTextColor(attrColor(R.attr.zzMuted))
            setPadding(0, dp(4), 0, dp(8))
        }
        cardLayout.addView(tvStats)

        // 3. Actions Row
        val actionsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val btnReview = Button(this).apply {
            text = "📖 浏览题目"
            textSize = 12f
            setTextColor(attrColor(R.attr.zzText))
            backgroundTintList = ColorStateList.valueOf(attrColor(R.attr.zzSurface2))
            minHeight = 0
            minWidth = 0
            setPadding(dp(12), dp(6), dp(12), dp(6))
            setOnClickListener {
                val intent = Intent(this@CustomQuestionBankActivity, CustomQuestionReviewActivity::class.java).apply {
                    putExtra(CustomQuestionReviewActivity.EXTRA_BANK_ID, bank.id)
                    putExtra(CustomQuestionReviewActivity.EXTRA_ONLY_MISTAKES, false)
                }
                startActivity(intent)
            }
        }
        actionsRow.addView(btnReview)

        val btnDelete = Button(this).apply {
            text = "🗑️ 删除"
            textSize = 12f
            setTextColor(attrColor(R.attr.zzDanger))
            backgroundTintList = ColorStateList.valueOf(attrColor(R.attr.zzSurface2))
            minHeight = 0
            minWidth = 0
            setPadding(dp(12), dp(6), dp(12), dp(6))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = dp(8) }
            setOnClickListener {
                showDeleteConfirmDialog(bank)
            }
        }
        actionsRow.addView(btnDelete)

        cardLayout.addView(actionsRow)
        card.addView(cardLayout)
        return card
    }

    private fun showDeleteConfirmDialog(bank: CustomQuestionBank) {
        AlertDialog.Builder(this)
            .setTitle("确认删除题库")
            .setMessage("确定要删除题库「${bank.title}」（共 ${bank.questions.size} 题）吗？删除后将无法恢复。")
            .setPositiveButton("删除") { _, _ ->
                CustomQuestionBankManager.deleteBank(this, bank.id)
                refreshList()
                Toast.makeText(this, "题库已删除", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }
}
