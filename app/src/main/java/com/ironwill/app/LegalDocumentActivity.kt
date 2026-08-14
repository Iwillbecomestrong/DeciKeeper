/**
 * =================================================================================
 * [Context Header]
 * * @file: LegalDocumentActivity.kt
 * * @role: UI
 * * @description: 统一长文本文档渲染器（用户协议 / 隐私政策 / 7 个法务入口）。
 * * @responsibilities:
 * 1. 通过 Intent extra (doc id) 找到对应 LegalDocument。
 * 2. 从 res/raw 读 UTF-8 文本。
 * 3. 展示标题 + 版本号 + 正文。
 * * @context:
 * - 上游: PrivacyConsentActivity, AboutActivity
 * * @constraints (必读):
 * 1. 文本读取失败必须给用户友好提示，不允许崩溃。
 * 2. 文档 id 找不到时 → finish() 退出。
 * =================================================================================
 */
package com.ironwill.app

import android.os.Bundle
import android.util.Log
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class LegalDocumentActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "LegalDocumentAct"
        const val EXTRA_DOC_ID: String = "extra_legal_doc_id"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_legal_document)

        val doc = LegalDocumentRepository.findById(intent.getStringExtra(EXTRA_DOC_ID))
        if (doc == null) {
            Log.w(TAG, "Unknown doc id: ${intent.getStringExtra(EXTRA_DOC_ID)}")
            Toast.makeText(this, "文档不存在", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        findViewById<TextView>(R.id.tv_legal_title).text = doc.title
        val versionLabel = when (doc.id) {
            LegalDocumentRepository.PRIVACY_POLICY.id,
            "about_privacy_policy" -> "隐私政策 ${LegalDocumentRepository.PRIVACY_POLICY_VERSION}"
            LegalDocumentRepository.USER_AGREEMENT.id,
            "about_user_agreement" -> "用户协议 ${LegalDocumentRepository.USER_AGREEMENT_VERSION}"
            else -> "占位合规文本，待法务 review"
        }
        findViewById<TextView>(R.id.tv_legal_version).text = versionLabel

        val body = try {
            resources.openRawResource(doc.rawRes).bufferedReader(Charsets.UTF_8).use { it.readText() }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read raw resource id=${doc.rawRes}", e)
            "（文档内容加载失败）"
        }
        findViewById<TextView>(R.id.tv_legal_body).text = body
    }
}
