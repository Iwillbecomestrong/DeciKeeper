package com.zhizhi.app.ui.questionbank

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.Editable
import android.text.TextWatcher
import android.util.TypedValue
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.zhizhi.app.R
import com.zhizhi.app.data.questionbank.CustomQuestionBank
import com.zhizhi.app.data.questionbank.ParseResult
import com.zhizhi.app.manager.questionbank.CustomQuestionBankManager
import com.zhizhi.app.manager.questionbank.CustomQuestionBankParser
import com.zhizhi.app.ui.ThemeManager
import java.io.BufferedReader
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

class CustomQuestionBankImportActivity : AppCompatActivity() {

    private lateinit var etBankTitle: EditText
    private lateinit var etContent: EditText
    private lateinit var tvParseSummary: TextView
    private lateinit var tvParseIssues: TextView
    private lateinit var btnSaveBank: Button

    private var currentParseResult: ParseResult? = null

    private val filePickerLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) {
            loadFileFromUri(uri)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.apply(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_custom_question_import)

        etBankTitle = findViewById(R.id.et_bank_title)
        etContent = findViewById(R.id.et_content)
        tvParseSummary = findViewById(R.id.tv_parse_summary)
        tvParseIssues = findViewById(R.id.tv_parse_issues)
        btnSaveBank = findViewById(R.id.btn_save_bank)

        findViewById<Button>(R.id.btn_back).setOnClickListener { finish() }

        // Copy Existing Bank Conversion Prompt
        findViewById<Button>(R.id.btn_copy_conversion_prompt).setOnClickListener {
            copyToClipboard("现有题库转换提示词", CustomQuestionBankManager.getExistingBankConversionPrompt())
            Toast.makeText(this, "🔄 已复制「现有题库转换 Prompt」，发给 AI 即可将原题库一键转换格式", Toast.LENGTH_SHORT).show()
        }

        // Copy Original AI Prompt
        findViewById<Button>(R.id.btn_copy_ai_prompt).setOnClickListener {
            copyToClipboard("AI出题提示词", CustomQuestionBankManager.getAiPrompt())
            Toast.makeText(this, "🤖 已复制 AI 原创出题提示词，可直接发给 ChatGPT/DeepSeek/Claude", Toast.LENGTH_SHORT).show()
        }

        // Copy repair prompt and include the currently pasted content when available.
        findViewById<Button>(R.id.btn_copy_repair_prompt).setOnClickListener {
            val source = etContent.text?.toString().orEmpty()
            copyToClipboard("题库格式修复提示词", CustomQuestionBankManager.getFormatRepairPrompt(source))
            val message = if (source.isBlank()) {
                "🛠 已复制格式修复 Prompt，可把解析失败的题库附在后面发给 AI"
            } else {
                "🛠 已把当前题库附在格式修复 Prompt 后，可直接发给 AI"
            }
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        }

        // Copy Template
        findViewById<Button>(R.id.btn_copy_template).setOnClickListener {
            copyToClipboard("题库模板", CustomQuestionBankManager.getStandardTemplate())
            Toast.makeText(this, "📋 已复制 Markdown / JSON 标准模板到剪贴板", Toast.LENGTH_SHORT).show()
        }

        // Pick File
        findViewById<Button>(R.id.btn_pick_file).setOnClickListener {
            filePickerLauncher.launch("*/*")
        }

        // Paste from clipboard
        findViewById<Button>(R.id.btn_paste_clipboard).setOnClickListener {
            val clip = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val text = clip.primaryClip?.getItemAt(0)?.text?.toString()
            if (!text.isNullOrBlank()) {
                etContent.setText(text)
                Toast.makeText(this, "已粘贴剪贴板内容", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "剪贴板无文本内容", Toast.LENGTH_SHORT).show()
            }
        }

        // Real-time parser validation
        etContent.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                updateParserPreview(s?.toString().orEmpty())
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })

        // Save Bank
        btnSaveBank.setOnClickListener {
            saveBank()
        }
    }

    private fun attrColor(attrId: Int): Int {
        val typedValue = TypedValue()
        theme.resolveAttribute(attrId, typedValue, true)
        return typedValue.data
    }

    private fun copyToClipboard(label: String, text: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText(label, text)
        clipboard.setPrimaryClip(clip)
    }

    private fun loadFileFromUri(uri: Uri) {
        try {
            contentResolver.openInputStream(uri)?.use { inputStream ->
                BufferedReader(InputStreamReader(inputStream, Charsets.UTF_8)).use { reader ->
                    val content = reader.readText()
                    etContent.setText(content)
                }
            }
            // Auto fill title from filename if title is empty
            if (etBankTitle.text.isNullOrBlank()) {
                val fileName = getFileName(uri)
                if (!fileName.isNullOrBlank()) {
                    val cleanName = fileName.substringBeforeLast(".")
                    etBankTitle.setText(cleanName)
                }
            }
            Toast.makeText(this, "文件导入成功", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "读取文件失败：${e.localizedMessage}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun getFileName(uri: Uri): String? {
        var result: String? = null
        if (uri.scheme == "content") {
            val cursor = contentResolver.query(uri, null, null, null, null)
            cursor?.use {
                if (it.moveToFirst()) {
                    val index = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index >= 0) {
                        result = it.getString(index)
                    }
                }
            }
        }
        if (result == null) {
            result = uri.path?.substringAfterLast('/')
        }
        return result
    }

    private fun updateParserPreview(content: String) {
        if (content.isBlank()) {
            tvParseSummary.text = "尚未输入题目内容"
            tvParseSummary.setTextColor(attrColor(R.attr.zzMuted))
            tvParseIssues.visibility = View.GONE
            btnSaveBank.isEnabled = false
            currentParseResult = null
            return
        }

        val result = CustomQuestionBankParser.parseContent(content)
        currentParseResult = result

        if (result.successQuestions.isNotEmpty()) {
            tvParseSummary.text = "✅ 成功识别 ${result.successQuestions.size} 道有效题目"
            tvParseSummary.setTextColor(attrColor(R.attr.zzSuccess))
            btnSaveBank.isEnabled = true
        } else {
            tvParseSummary.text = "⚠️ 未识别出有效题目 (需包含题干、4个选项与答案)"
            tvParseSummary.setTextColor(attrColor(R.attr.zzWarning))
            btnSaveBank.isEnabled = false
        }

        if (result.issues.isNotEmpty()) {
            val issueText = StringBuilder("发现 ${result.issues.size} 个格式警告 (已跳过不符合格式题目)：\n")
            result.issues.take(5).forEach { issue ->
                val lineInfo = if (issue.line > 0) "第 ${issue.line} 行: " else ""
                issueText.append("• ").append(lineInfo).append(issue.message).append("\n")
            }
            if (result.issues.size > 5) {
                issueText.append("... 以及另外 ${result.issues.size - 5} 个警告\n")
            }
            issueText.append("\n若内容来自 AI，可点击上方「格式修复 Prompt」一键复制当前内容并让 AI 只整理格式。")
            tvParseIssues.text = issueText.toString().trimEnd()
            tvParseIssues.visibility = View.VISIBLE
        } else {
            tvParseIssues.visibility = View.GONE
        }
    }

    private fun saveBank() {
        val result = currentParseResult ?: return
        if (result.successQuestions.isEmpty()) return

        var title = etBankTitle.text?.toString()?.trim().orEmpty()
        if (title.isBlank()) {
            val dateStr = SimpleDateFormat("yyyyMMdd_HHmm", Locale.getDefault()).format(Date())
            title = "自定义题库_$dateStr"
        }

        val bankId = UUID.randomUUID().toString()
        val boundQuestions = result.successQuestions.map { q ->
            q.copy(bankId = bankId)
        }

        val bank = CustomQuestionBank(
            id = bankId,
            title = title,
            questions = boundQuestions,
            enabled = true,
            createdAt = System.currentTimeMillis()
        )

        CustomQuestionBankManager.saveBank(this, bank)
        Toast.makeText(this, "成功保存「$title」（共 ${boundQuestions.size} 题）", Toast.LENGTH_SHORT).show()
        setResult(RESULT_OK)
        finish()
    }
}
