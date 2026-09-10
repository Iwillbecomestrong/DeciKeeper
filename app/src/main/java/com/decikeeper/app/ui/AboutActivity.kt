package com.decikeeper.app.ui

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
import com.decikeeper.app.R
import com.decikeeper.app.data.LegalDocument
import com.decikeeper.app.data.LegalDocumentRepository

class AboutActivity : AppCompatActivity() {
    private val clozeSourceTitle = "KaoYan-English"
    private val clozeSourceDesc = "部分挑战题目来源于公开开源项目。"
    private val clozeSourceUrl = "https://github.com/m2kar/KaoYan-English"
    private val clozeSourceAuthor = "m2kar"
    private val clozeSourceLicense = "MIT License（以仓库 LICENSE 文件为准）"

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.apply(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_about)

        val container = findViewById<LinearLayout>(R.id.container_about_entries)
        val density = resources.displayMetrics.density
        LegalDocumentRepository.ABOUT_ENTRIES.forEach { container.addView(buildEntryView(it, density)) }
        addClozeSourceSection(container, density)
        val versionName = try {
            val info = packageManager.getPackageInfo(packageName, 0)
            info.versionName ?: "?"
        } catch (_: Exception) { "?" }
        findViewById<TextView>(R.id.tv_about_version_value).text = "v$versionName"
        findViewById<TextView>(R.id.tv_about_app_version).text = "决定守护 v$versionName"
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
            text = "内容来源"
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
            text = "作者：$clozeSourceAuthor\n许可证：$clozeSourceLicense"
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
