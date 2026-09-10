/**
 * =================================================================================
 * [Context Header]
 * * @file: PackageInstallReceiver.kt
 * * @role: Domain Skill
 * * @description: 监听新 App 安装/更新事件，自动将黑名单四类 App 加入屏蔽列表。
 * * @responsibilities:
 * 1. 接收 ACTION_PACKAGE_ADDED / ACTION_PACKAGE_REPLACED 广播。
 * 2. 提取包名，委托 CreditManager.tryAutoBlacklistNewPackage() 处理。
 * * @context:
 * - 上游: 系统 PackageManager 广播
 * - 下游: CreditManager.tryAutoBlacklistNewPackage()
 * * @constraints (必读):
 * 1. 不得引入 UI 依赖。
 * 2. 必须在 Direct Boot 模式下也可工作（directBootAware=true）。
 * 3. 仅写 SharedPreferences，不启动 Service/Activity。
 * 4. Auto-add 的 App 不在 baseline 中，用户在下次 refresh time 前可直接移除（immediate revert）。
 * =================================================================================
 */
package com.decikeeper.app.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.decikeeper.app.data.LogExporter
import com.decikeeper.app.manager.CreditManager



class PackageInstallReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "PackageInstallReceiver"
    }

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent == null) return

        val action = intent.action
        if (action != Intent.ACTION_PACKAGE_ADDED && action != Intent.ACTION_PACKAGE_REPLACED) {
            return
        }

        // 从 package:<pkgName> URI 中提取包名
        val pkg = intent.data?.schemeSpecificPart
        if (pkg.isNullOrEmpty()) {
            Log.w(TAG, "Received $action with null/empty package name")
            return
        }

        Log.d(TAG, "Received $action for package: $pkg")

        try {
            CreditManager.tryAutoBlacklistNewPackage(context, pkg)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to auto-add $pkg to blacklist", e)
            LogExporter.logEvent(TAG, "AUTO_ADD_FAILED: $pkg error=${e.javaClass.simpleName}:${e.message}")
        }
    }
}
