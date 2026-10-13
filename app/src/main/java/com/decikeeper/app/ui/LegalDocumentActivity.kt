package com.decikeeper.app.ui

import android.os.Bundle
import android.util.Log
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.decikeeper.app.R
import com.decikeeper.app.data.LegalDocumentRepository
import io.noties.markwon.Markwon
import io.noties.markwon.ext.tables.TablePlugin

class LegalDocumentActivity : AppCompatActivity() {
    companion object {
        private const val TAG = "LegalDocumentAct"
        const val EXTRA_DOC_ID: String = "extra_legal_doc_id"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.apply(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_legal_document)
        findViewById<android.widget.ImageView>(R.id.btn_legal_back)?.setOnClickListener {
            finish()
        }
        val doc = LegalDocumentRepository.findById(intent.getStringExtra(EXTRA_DOC_ID))
        if (doc == null) {
            Log.w(TAG, "Unknown doc id: ${intent.getStringExtra(EXTRA_DOC_ID)}")
            Toast.makeText(this, "文档不存在", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        findViewById<TextView>(R.id.tv_legal_title).text = doc.title
        findViewById<TextView>(R.id.tv_legal_version).text = when (doc.id) {
            LegalDocumentRepository.PRIVACY_POLICY.id, "about_privacy_policy" -> "隐私政策 ${LegalDocumentRepository.PRIVACY_POLICY_VERSION}"
            LegalDocumentRepository.USER_AGREEMENT.id, "about_user_agreement" -> "用户协议 ${LegalDocumentRepository.USER_AGREEMENT_VERSION}"
            "about_privacy_notice", "about_open_source_licenses", "about_feedback_email" ->
                "决定守护 v0.4.1 单机内测版"
            else -> "决定守护 v0.4.1"
        }
        val body = try {
            resources.openRawResource(doc.rawRes).bufferedReader(Charsets.UTF_8).use { it.readText() }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read raw resource id=${doc.rawRes}", e)
            "（文档内容加载失败）"
        }
        Markwon.builder(this)
            .usePlugin(TablePlugin.create(this))
            .build()
            .setMarkdown(findViewById(R.id.tv_legal_body), body)
    }
}
