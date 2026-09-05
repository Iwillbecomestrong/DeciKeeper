/**
 * =================================================================================
 * [Context Header]
 * * @file: PrivacyConsentActivity.kt
 * * @role: UI
 * * @description: 首次启动协议 + 隐私政策同意页。
 * * @responsibilities:
 * 1. 作为 launcher 拦截所有冷启动，未同意时拦截进入 MainActivity。
 * 2. 同步读 SharedPreferences：已同意 → 直接 finish 跳 MainActivity（避免重复弹窗）。
 * 3. 同意 → 写状态 → 跳 MainActivity；不同意 → finishAffinity() 退出。
 * * @context:
 * - 上游: 启动器 (AndroidManifest.xml 的 LAUNCHER)
 * - 下游: PrivacyConsentManager, MainActivity, LegalDocumentActivity
 * * @constraints (必读):
 * 1. 同步读 SharedPreferences 必须在 setContentView 之前完成，避免闪屏。
 * 2. 不要在这里启动任何可能涉及个人信息的 SDK（无第三方 SDK，但 gate 必须存在）。
 * 3. 不同意分支必须调用 finishAffinity() 而非 finish()，避免被回退栈带回。
 * =================================================================================
 */
package com.zhizhi.app.ui

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.zhizhi.app.R
import com.zhizhi.app.data.LegalDocument
import com.zhizhi.app.data.LegalDocumentRepository
import com.zhizhi.app.manager.PrivacyConsentManager





class PrivacyConsentActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "PrivacyConsentAct"

        /**
         * 由 MainActivityEntry.redirectToConsent() 写入。值为 true 时表示有
         * "待回放"的 baseIntent extras 在 PendingMainActivityExtras 里，
         * 同意后必须回放。
         */
        const val EXTRA_HAS_PENDING_EXTRAS: String = "ironwill.consent.has_pending_extras"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 🔒 同步读取同意状态。
        // 必须在 setContentView 之前，避免在"已同意用户"上闪一帧空白。
        if (PrivacyConsentManager.isConsented(this)) {
            Log.d(TAG, "Already consented, jumping to MainActivity")
            goToMainAndFinish()
            return
        }

        setContentView(R.layout.activity_privacy_consent)

        // 同意 / 不同意 按钮
        findViewById<Button>(R.id.btn_consent_accept).setOnClickListener {
            onAcceptClicked()
        }
        findViewById<Button>(R.id.btn_consent_decline).setOnClickListener {
            onDeclineClicked()
        }

        // 内嵌跳转入口
        findViewById<TextView>(R.id.tv_open_user_agreement).setOnClickListener {
            openLegal(LegalDocumentRepository.USER_AGREEMENT)
        }
        findViewById<TextView>(R.id.tv_open_privacy_policy).setOnClickListener {
            openLegal(LegalDocumentRepository.PRIVACY_POLICY)
        }
    }

    private fun onAcceptClicked() {
        val committed = PrivacyConsentManager.markConsented(
            this,
            LegalDocumentRepository.PRIVACY_POLICY_VERSION
        )
        if (!committed) {
            // commit 失败（磁盘满/文件锁/SELinux 等），绝不能让用户进主页 —
            // 否则下次冷启动仍弹同意页，或闸门逻辑被绕过。
            Log.e(TAG, "onAcceptClicked: commit failed, blocking entry to MainActivity")
            Toast.makeText(
                this,
                "无法保存同意状态，请重试或检查存储空间",
                Toast.LENGTH_LONG
            ).show()
            return
        }
        Log.i(TAG, "User accepted privacy policy " + LegalDocumentRepository.PRIVACY_POLICY_VERSION)
        goToMainAndFinish()
    }

    private fun onDeclineClicked() {
        // 不写状态、保留"未同意"。下次启动还会再弹。
        Toast.makeText(this, "已退出知止", Toast.LENGTH_SHORT).show()
        Log.i(TAG, "User declined, finishing app")
        finishAffinity()
    }

    private fun goToMainAndFinish() {
        // REQ-5 Round 3 Fix #2: 若上游 MainActivityEntry 把原始 MainActivity
        // Intent 的 extras 暂存到 PendingMainActivityExtras，回放它们，避免
        // "同意页跳裸 MainActivity" 把 MonitorService 的 show_permission_dialog
        // 这类 extras 吞掉。
        val pendingIntent = PendingMainActivityExtras.consume(this)
        val intent: Intent = if (pendingIntent != null) {
            // 在回放的 intent 之上叠加"清栈"语义，确保隐私页和 MainActivity
            // 不在同一个任务栈里残留返回栈。
            pendingIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        } else {
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }
        startActivity(intent)
        finish()
    }

    private fun openLegal(doc: LegalDocument) {
        val intent = Intent(this, LegalDocumentActivity::class.java)
        intent.putExtra(LegalDocumentActivity.EXTRA_DOC_ID, doc.id)
        startActivity(intent)
    }
}
