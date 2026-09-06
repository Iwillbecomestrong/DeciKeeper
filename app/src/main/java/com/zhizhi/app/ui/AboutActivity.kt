package com.zhizhi.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.res.ColorStateList
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.zhizhi.app.R
import com.zhizhi.app.data.LegalDocument
import com.zhizhi.app.data.LegalDocumentRepository

class AboutActivity : AppCompatActivity() {
    private val clozeSourceTitle = "选词填空真题来源"
    private val clozeSourceDesc = "本应用的选词填空题目来自以下开源仓库："
    private val clozeSourceUrl = "https://github.com/m2kar/KaoYan-English"
    private val clozeSourceAuthor = "m2kar"
    private val clozeSourceLicense = "仓库自带 license（具体见仓库 LICENSE 文件）"

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.apply(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_about)

        val container = findViewById<LinearLayout>(R.id.container_about_entries)
        val density = resources.displayMetrics.density
        LegalDocumentRepository.ABOUT_ENTRIES.forEach { container.addView(buildEntryView(it, density)) }
        addClozeSourceSection(container, density)
        findViewById<TextView>(R.id.tv_about_app_version).text = try {
            val info = packageManager.getPackageInfo(packageName, 0)
            "知止 v${info.versionName ?: "?"} (build ${readLongVersionCodeCompat(info)})"
        } catch (_: Exception) { "知止" }
    }

    private fun attrColor(attr: Int): Int {
        val value = TypedValue()
        theme.resolveAttribute(attr, value, true)
        return value.data
    }

    private fun addClozeSourceSection(parent: LinearLayout, density: Float) {
        val section = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((4 * density).toInt(), (20 * density).toInt(), (4 * density).toInt(), (8 * density).toInt())
        }
        section.addView(TextView(this).apply {
            text = "数据来源"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(attrColor(R.attr.zzMuted))
            setPadding((4 * density).toInt(), 0, 0, (8 * density).toInt())
        })
        section.addView(TextView(this).apply {
            text = clozeSourceTitle
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setTextColor(attrColor(R.attr.zzText))
            setPadding((4 * density).toInt(), 0, 0, (3 * density).toInt())
        })
        section.addView(TextView(this).apply {
            text = clozeSourceDesc
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(attrColor(R.attr.zzMuted))
            setPadding((4 * density).toInt(), 0, 0, (8 * density).toInt())
        })
        section.addView(TextView(this).apply {
            text = clozeSourceUrl
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(attrColor(R.attr.zzAccentStrong))
            setBackgroundResource(R.drawable.bg_zz_card)
            backgroundTintList = ColorStateList.valueOf(attrColor(R.attr.zzSurface))
            setPadding((14 * density).toInt(), (13 * density).toInt(), (14 * density).toInt(), (13 * density).toInt())
            isClickable = true
            isFocusable = true
            setOnClickListener { onClozeSourceClicked() }
            setOnLongClickListener { copyClozeSourceUrl(); true }
        })
        section.addView(TextView(this).apply {
            text = "作者：$clozeSourceAuthor  ·  $clozeSourceLicense"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            setTextColor(attrColor(R.attr.zzSubtle))
            setPadding((4 * density).toInt(), (7 * density).toInt(), 0, 0)
        })
        parent.addView(section)
    }

    private fun onClozeSourceClicked() {
        copyClozeSourceUrl()
        Toast.makeText(this, "链接已复制，可粘贴到浏览器打开", Toast.LENGTH_LONG).show()
    }

    private fun copyClozeSourceUrl() {
        try {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("Cloze Source", clozeSourceUrl))
        } catch (e: Exception) {
            Log.e("AboutActivity", "复制链接失败", e)
        }
    }

    private fun buildEntryView(doc: LegalDocument, density: Float): View = TextView(this).apply {
        text = "${doc.title}   ›"
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        setTextColor(attrColor(R.attr.zzText))
        setBackgroundResource(R.drawable.bg_zz_card)
        backgroundTintList = ColorStateList.valueOf(attrColor(R.attr.zzSurface))
        setPadding((16 * density).toInt(), (15 * density).toInt(), (16 * density).toInt(), (15 * density).toInt())
        gravity = Gravity.CENTER_VERTICAL
        isClickable = true
        isFocusable = true
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = (8 * density).toInt()
        }
        setOnClickListener {
            startActivity(Intent(this@AboutActivity, LegalDocumentActivity::class.java).putExtra(LegalDocumentActivity.EXTRA_DOC_ID, doc.id))
        }
    }

    fun readLongVersionCodeCompat(info: PackageInfo): Long =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode
        else @Suppress("DEPRECATION") info.versionCode.toLong()
}
