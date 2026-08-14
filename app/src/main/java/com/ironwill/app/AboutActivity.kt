/**
 * =================================================================================
 * [Context Header]
 * * @file: AboutActivity.kt
 * * @role: UI
 * * @description: "设置 → 关于我们" — 7 个法务/合规入口 + 1 个外部数据源（考研真题）。
 * * @responsibilities:
 * 1. 渲染 LegalDocumentRepository.ABOUT_ENTRIES 中 7 条目。
 * 2. 渲染 Cloze 数据源仓库入口（独立区块，不污染法务契约）。
 * 3. 点击条目跳 LegalDocumentActivity；点击数据源链接尝试复制 + 提示。
 * 4. 底部显示当前 App 版本号（来自 PackageManager），便于人工核验。
 * * @context:
 * - 上游: RulesFragment
 * * @constraints (必读):
 * 1. 顺序由 LegalDocumentRepository 控制，禁止 UI 层再排序。
 * 2. 不要在这里跑长任务 / 联网。
 * 3. 数据源入口与法务入口解耦，不混用同一渲染器。
 * =================================================================================
 */
package com.ironwill.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.graphics.Color
import android.net.Uri
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
import androidx.core.content.ContextCompat

class AboutActivity : AppCompatActivity() {

    // Cloze 选词填空真题来源（与法务 7 条目独立）
    private val clozeSourceTitle = "📚 选词填空真题来源"
    private val clozeSourceDesc = "本应用的选词填空题目来自以下开源仓库："
    private val clozeSourceUrl = "https://github.com/m2kar/KaoYan-English"
    private val clozeSourceAuthor = "m2kar"
    private val clozeSourceLicense = "仓库自带 license（具体见仓库 LICENSE 文件）"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_about)

        val container = findViewById<LinearLayout>(R.id.container_about_entries)
        val density = resources.displayMetrics.density

        for (doc in LegalDocumentRepository.ABOUT_ENTRIES) {
            container.addView(buildEntryView(doc, density))
        }

        // 新增：Cloze 数据源独立区块（位于法务条目下方、版本号上方）
        addClozeSourceSection(container, density)

        findViewById<TextView>(R.id.tv_about_app_version).text =
            try {
                val info = packageManager.getPackageInfo(packageName, 0)
                // REQ-5 Round 3 Fix #3: longVersionCode 自 API 28 引入，
                // minSdk=26 在 8.0/8.1 上会 NoSuchFieldError，必须走 SDK 分支。
                val longVersionCode = readLongVersionCodeCompat(info)
                "IronWill v${info.versionName ?: "?"} (build $longVersionCode)"
            } catch (e: Exception) {
                "IronWill"
            }
    }

    /**
     * 渲染 Cloze 数据源区块
     * - 独立于法务条目（避免污染 7 条目固定顺序契约）
     * - 点击行为：优先复制链接并提示，Toast 提示可去浏览器打开
     */
    private fun addClozeSourceSection(parent: LinearLayout, density: Float) {
        val section = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                (16 * density).toInt(),
                (24 * density).toInt(),
                (16 * density).toInt(),
                (8 * density).toInt()
            )
        }

        // 分隔标题
        section.addView(TextView(this).apply {
            text = "── 数据来源 ──"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(Color.parseColor("#9E9E9E"))
            gravity = Gravity.CENTER
            setPadding(0, (8 * density).toInt(), 0, (8 * density).toInt())
        })

        // 标题 + 描述
        section.addView(TextView(this).apply {
            text = clozeSourceTitle
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setTextColor(Color.parseColor("#E0E0E0"))
            setPadding(0, 0, 0, (4 * density).toInt())
        })
        section.addView(TextView(this).apply {
            text = clozeSourceDesc
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(Color.parseColor("#9E9E9E"))
            setPadding(0, 0, 0, (8 * density).toInt())
        })

        // 链接行（可点击）
        val linkRow = TextView(this).apply {
            text = "🔗 $clozeSourceUrl"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(Color.parseColor("#64B5F6"))
            setPadding(
                (12 * density).toInt(),
                (10 * density).toInt(),
                (12 * density).toInt(),
                (10 * density).toInt()
            )
            setBackgroundResource(R.drawable.bg_sea_bubble)
            isClickable = true
            isFocusable = true
            setOnClickListener { onClozeSourceClicked() }
            setOnLongClickListener {
                copyClozeSourceUrl()
                true
            }
        }
        section.addView(linkRow)

        // 作者 + license 提示
        section.addView(TextView(this).apply {
            text = "作者：$clozeSourceAuthor  |  $clozeSourceLicense"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setTextColor(Color.parseColor("#9E9E9E"))
            setPadding(0, (8 * density).toInt(), 0, 0)
        })

        parent.addView(section)
    }

    /** 点击 Cloze 源链接：复制并提示用户可去浏览器打开 */
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

    private fun buildEntryView(doc: LegalDocument, density: Float): View {
        val row = TextView(this).apply {
            text = "▶ ${doc.title}"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setTextColor(ContextCompat.getColor(this@AboutActivity, R.color.gentle_text_warm))
            setBackgroundResource(R.drawable.bg_sea_bubble)
            setPadding(
                (16 * density).toInt(),
                (16 * density).toInt(),
                (16 * density).toInt(),
                (16 * density).toInt()
            )
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            isFocusable = true
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.bottomMargin = (8 * density).toInt()
            layoutParams = lp
            setOnClickListener {
                val intent = Intent(this@AboutActivity, LegalDocumentActivity::class.java)
                intent.putExtra(LegalDocumentActivity.EXTRA_DOC_ID, doc.id)
                startActivity(intent)
            }
        }
        return row
    }

    /**
     * REQ-5 Round 3 Fix #3:
     * `PackageInfo.longVersionCode` 自 API 28 (Android P) 引入；项目 minSdk=26，
     * 因此在 API 26 / 27 上必须回退到旧的 `versionCode: Int` 字段。统一返回 Long
     * 让下游 UI 字符串拼装不再做类型分支。
     *
     * 注意：本方法用 `fun` 而非 `internal fun`，因为 Kotlin internal 在 JVM 上
     * 会做名字混淆（`readLongVersionCodeCompat$app_debug`），导致 JVM 单测里
     * getDeclaredMethod 找不到。这里方法本身无副作用，仅按 SDK 版本返回 Long，
     * 暴露为 public 对外契约没有风险（PackageInfo 是入参，不持有任何状态）。
     */
    fun readLongVersionCodeCompat(info: PackageInfo): Long {
        // TODO(round-4 MAJOR-5): 当前 emulator 不可用,本方法在 API 26/27
        // (Android 8.0 / 8.1) 上的 @Suppress("DEPRECATION") versionCode.toLong()
        // 分支未在真机走过。仓库无 Robolectric, JVM 单测只能锁方法签名。
        // 待 emulator-tester 在 API 26 / API 27 设备上跑:
        // 点「关于我们」→ 期望底部版本号行不报 NoSuchFieldError、不抛 CrashHandler。
        Log.w(
            "AboutActivity",
            "TODO(round-4 MAJOR-5): needs emulator/real device verification " +
                "for readLongVersionCodeCompat on API 26/27 (BLOCKED_NEEDS_REAL_DEVICE)"
        )
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            info.versionCode.toLong()
        }
    }
}
