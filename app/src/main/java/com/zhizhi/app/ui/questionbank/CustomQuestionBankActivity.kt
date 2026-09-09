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
import android.widget.EditText
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
            text = "${bank.title} ✏️"
            textSize = 15f
            setTypeface(null, Typeface.BOLD)
            setTextColor(attrColor(R.attr.zzText))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { showRenameDialog(bank) }
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
            setPadding(0, dp(4), 0, dp(6))
        }
        cardLayout.addView(tvStats)

        // 2.5 收藏夹独立 Tab 展示开关
        val rowBookmark = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) }
        }
        val tvBookmarkLabel = TextView(this).apply {
            text = "在收藏夹作为独立题库展示"
            textSize = 12f
            setTextColor(attrColor(R.attr.zzMuted))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val switchBookmark = SwitchCompat(this).apply {
            isChecked = bank.showInBookmark
            setOnCheckedChangeListener { _, isChecked ->
                CustomQuestionBankManager.setBankShowInBookmark(this@CustomQuestionBankActivity, bank.id, isChecked)
                val statusStr = if (isChecked) "已开启在收藏夹中展示该题库" else "已在收藏夹中隐藏该题库"
                Toast.makeText(this@CustomQuestionBankActivity, statusStr, Toast.LENGTH_SHORT).show()
            }
        }
        rowBookmark.addView(tvBookmarkLabel)
        rowBookmark.addView(switchBookmark)
        cardLayout.addView(rowBookmark)

        // 3. Actions Row
        val actionsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val btnRename = Button(this).apply {
            text = "✏️ 重命名"
            textSize = 12f
            setTextColor(attrColor(R.attr.zzText))
            backgroundTintList = ColorStateList.valueOf(attrColor(R.attr.zzSurface2))
            minHeight = 0
            minWidth = 0
            setPadding(dp(12), dp(6), dp(12), dp(6))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = dp(8) }
            setOnClickListener {
                showRenameDialog(bank)
            }
        }
        actionsRow.addView(btnRename)

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

    private fun showRenameDialog(bank: CustomQuestionBank) {
        val dp = (resources.displayMetrics.density).toInt()
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = 18 * dp
            setPadding(pad, pad / 2, pad, pad / 2)
        }
        val input = EditText(this).apply {
            setText(bank.title)
            setSelection(text.length)
            hint = "请输入新题库名称"
            textSize = 14f
            setTextColor(attrColor(R.attr.zzText))
            setBackgroundResource(R.drawable.bg_input_field)
            val p = 10 * dp
            setPadding(p, p, p, p)
        }
        val hintText = TextView(this).apply {
            text = "💡 建议命名为 2 个字（如：考公、四级），在闹钟与提醒界面显示更美观整洁"
            textSize = 11f
            setTextColor(attrColor(R.attr.zzAccent))
            setPadding(0, 6 * dp, 0, 0)
        }
        container.addView(input)
        container.addView(hintText)

        AlertDialog.Builder(this)
            .setTitle("修改题库名称")
            .setView(container)
            .setPositiveButton("保存") { _, _ ->
                val newName = input.text.toString().trim()
                if (newName.isEmpty()) {
                    Toast.makeText(this, "题库名称不能为空", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                if (newName == bank.title) return@setPositiveButton
                val success = CustomQuestionBankManager.renameBank(this, bank.id, newName)
                if (success) {
                    refreshList()
                    Toast.makeText(this, "已重命名为「$newName」", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "重命名失败", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("取消", null)
            .show()
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
