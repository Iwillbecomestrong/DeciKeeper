package com.zhizhi.app.ui

import android.os.Bundle
import android.util.Log
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.zhizhi.app.R
import com.zhizhi.app.data.LegalDocumentRepository

class LegalDocumentActivity : AppCompatActivity() {
    companion object {
        private const val TAG = "LegalDocumentAct"
        const val EXTRA_DOC_ID: String = "extra_legal_doc_id"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.apply(this)
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
        findViewById<TextView>(R.id.tv_legal_version).text = when (doc.id) {
            LegalDocumentRepository.PRIVACY_POLICY.id, "about_privacy_policy" -> "隐私政策 ${LegalDocumentRepository.PRIVACY_POLICY_VERSION}"
            LegalDocumentRepository.USER_AGREEMENT.id, "about_user_agreement" -> "用户协议 ${LegalDocumentRepository.USER_AGREEMENT_VERSION}"
            else -> "占位合规文本，待法务 review"
        }
        findViewById<TextView>(R.id.tv_legal_body).text = try {
            resources.openRawResource(doc.rawRes).bufferedReader(Charsets.UTF_8).use { it.readText() }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read raw resource id=${doc.rawRes}", e)
            "（文档内容加载失败）"
        }
    }
}
