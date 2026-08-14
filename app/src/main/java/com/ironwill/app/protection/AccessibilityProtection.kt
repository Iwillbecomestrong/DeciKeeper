/**
 * =================================================================================
 * [Context Header]
 * * @file: AccessibilityProtection.kt
 * * @role: Domain Skill
 * * @description: 无障碍权限保护逻辑。
 * * @responsibilities:
 * 1. 监测无障碍权限变化。
 * 2. 触发保护与恢复流程。
 * * @context:
 * - 上游: ProtectionManager
 * - 下游: PermissionManager, LogExporter
 * * @constraints (必读):
 * 1. 不引入 UI 依赖。
 * 2. 需处理系统版本差异。
 * 3. 空值安全。
 * =================================================================================
 */
package com.ironwill.app.protection

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.ironwill.app.A11ySelfDisableAuthorization
import com.ironwill.app.AccessibilityControlManager
import com.ironwill.app.LogExporter

class AccessibilityProtection(private val context: Context) {

    companion object {
        const val TAG = "A11yProtect"
        const val WARNING_DIALOG_ENABLED = false
        
        // Strategy 0: Absolute Precision (Golden Rule)
        // Only the IronWill accessibility detail page contains this specific description.
        private const val UNIQUE_DESCRIPTION = "用于保护您的自律状态"
        private const val STARTUP_GRACE_MS = 12_000L
        private const val MANUAL_SETTINGS_LAUNCH_WINDOW_MS = 120_000L
        
        private val STOP_KEYWORDS = listOf(
            "停止服务", "关闭服务", "Stop service", "Turn off service", 
            "停止 IronWill", "Stop IronWill", "Turn off IronWill",
            "关闭 IronWill", "关闭IronWill", "Close IronWill"
        )
        private val RELEVANT_PACKAGES = setOf(
            "com.android.settings",
            "com.android.permissioncontroller",
            "com.google.android.permissioncontroller",
            "com.vivo.xspace",
            "com.vivo.privacylauncher",
            "com.vivo.permissionmanager",
            "com.vivo.imanager",
            "com.iqoo.secure",
            "com.miui.securitycenter",
            "com.huawei.systemmanager",
            "com.coloros.safecenter",
            "com.oplus.safecenter",
            "com.samsung.android.sm",
            "com.oneplus.security"
        )
        private val ACCESSIBILITY_LIST_MARKERS = listOf(
            "已下载的服务", "已下载的应用", "Downloaded services", "Downloaded apps",
            "已下載的服務", "已下載的應用程式", "Services téléchargés", "Heruntergeladene Dienste"
        )
        private val ACCESSIBILITY_PAGE_MARKERS = listOf(
            "无障碍", "辅助功能", "Accessibility", "Accessibilité", "Bedienungshilfen",
            "ユーザー補助", "접근성", "Accesibilidad", "Специальные возможности"
        )
        private var lastDetectionTime = 0L
        private var lastServiceConnectedTime = 0L
        private var lastManualSettingsLaunchTime = 0L

        fun isRelevantPackage(packageName: String): Boolean {
            return RELEVANT_PACKAGES.any { packageName.startsWith(it) }
        }

        fun markManualSettingsLaunch(now: Long = System.currentTimeMillis()) {
            lastManualSettingsLaunchTime = now
        }

        fun markServiceConnected(now: Long = System.currentTimeMillis()) {
            lastServiceConnectedTime = now
            // [2026-06-25] 诊断日志：每次 onRebind/onServiceConnected 都会调用，可能掩盖 flapping
            // 详见 Issue_AntiUninstallBootGap 尝试 7
            Log.d("IronWill_Debug", "[A11yProt] markServiceConnected now=$now")
        }

        fun shouldReturnToAppAfterConnect(now: Long = System.currentTimeMillis()): Boolean {
            val launchedRecently = now - lastManualSettingsLaunchTime in 0..MANUAL_SETTINGS_LAUNCH_WINDOW_MS
            if (launchedRecently) {
                lastManualSettingsLaunchTime = 0L
            }
            return launchedRecently
        }

        fun isInStartupGrace(now: Long = System.currentTimeMillis()): Boolean {
            return now - lastServiceConnectedTime in 0..STARTUP_GRACE_MS
        }

        fun isIronWillAccessibilityDetailContent(content: String): Boolean {
            // [Detail Page Identification - Golden Rule]
            // Only match by UNIQUE_DESCRIPTION — the text that IronWill registers as its
            // accessibility service description. This text ONLY appears on the IronWill-specific
            // accessibility detail/toggle page, NEVER on the service list page.
            //
            // ⚠️ IMPORTANT: Do NOT add STOP_KEYWORDS fallback here! The list page's app toggle
            // buttons contain "关闭服务" etc. which would cause the list page to be incorrectly
            // identified as the detail page, preventing other apps from requesting accessibility.
            if (content.contains(UNIQUE_DESCRIPTION, ignoreCase = true)) return true
            return false
        }

        fun isAccessibilityServiceListContent(content: String): Boolean {
            val hasA11yContext = ACCESSIBILITY_PAGE_MARKERS.any { content.contains(it, ignoreCase = true) }
            if (!hasA11yContext) return false
            val hasProtectedApp = AntiUninstallProtection.PROTECTED_APP_NAMES.any {
                content.contains(it, ignoreCase = true)
            } || content.contains("com.ironwill", ignoreCase = true)
            if (!hasProtectedApp) return false
            // If UNIQUE_DESCRIPTION is found, this is the detail page, not a list page
            if (isIronWillAccessibilityDetailContent(content)) return false
            // Has a11y context + protected app + NOT detail page → it's a list page
            // Note: we intentionally do NOT require ACCESSIBILITY_LIST_MARKERS here because
            // OEMs like Vivo may not include those markers in the accessibility list page content.
            return true
        }

        fun isHighRiskStartupContent(content: String): Boolean {
            val hasProtectedApp = AntiUninstallProtection.PROTECTED_APP_NAMES.any {
                content.contains(it, ignoreCase = true)
            } || content.contains("com.ironwill", ignoreCase = true)
            if (hasProtectedApp) return true
            return STOP_KEYWORDS.any { content.contains(it, ignoreCase = true) } ||
                AntiUninstallProtection.UNINSTALL_KEYWORDS.any { content.contains(it, ignoreCase = true) } ||
                content.contains("强行停止", ignoreCase = true) ||
                content.contains("强制停止", ignoreCase = true) ||
                content.contains("Force stop", ignoreCase = true)
        }
    }

    fun check(service: AccessibilityService, event: AccessibilityEvent, root: AccessibilityNodeInfo) {
        // 🚫 [Self-Protection Gate] 使用短期授权判断是否跳过保护
        // 不再读取 ConfigPendingManager 中历史的 a11y_service_self_pending 持久化 key，
        // 改为纯内存 A11ySelfDisableAuthorization，最长 10 秒自动过期。
        // 状态变化日志（SET/CLEAR/expired）由 A11ySelfDisableAuthorization 自身输出；
        // 这里不再每次 skip 都打日志，避免 hot path 重复 spam。
        if (A11ySelfDisableAuthorization.isAuthorized()) {
            return
        }

        // 🚫 [Self-Protection] 当弹窗显示或待显示时，暂停所有检测
        // 防止误触发BACK操作关闭我们自己的弹窗
        if (AntiUninstallProtection.isDialogShowing || AntiUninstallProtection.hasPendingDialog) {
            return
        }
        
        // 🚫 [Safe Package Filter] Skip safe apps (browsers, share sheets, etc.)
        val currentPackage = event.packageName?.toString() ?: ""
        val isSafePackage = com.ironwill.app.protection.AntiUninstallProtection.SAFE_PACKAGES_KEYWORDS.any { 
            currentPackage.contains(it, ignoreCase = true) 
        }
        if (isSafePackage || currentPackage == service.packageName) {
            return
        }

        if (!isRelevantPackage(currentPackage)) {
            Log.v(TAG, "[Skip] A11y protection: non-settings pkg=$currentPackage")
            return
        }
        
        val allText = StringBuilder()
        collectAllText(root, allText)
        val content = allText.toString()

        if (isAccessibilityServiceListContent(content)) {
            LogExporter.logEvent(TAG, "[Skip] A11y service list page: pkg=$currentPackage cls=${event.className}")
            return
        }

        if (isInStartupGrace() && !isHighRiskStartupContent(content)) {
            LogExporter.logEvent(TAG, "[Skip] A11y protection startup grace: pkg=$currentPackage cls=${event.className}")
            return
        }
        
        // Log.v(TAG, "AccessibilityProtection Check: $content")

        // 1. Golden Rule: Unique Description (Safe)
        if (content.contains(UNIQUE_DESCRIPTION)) {
            Log.w(TAG, "!!! Precision Trigger: Detected IronWill Detail Page via Description !!!")
            LogExporter.logEvent(TAG, "[BLOCK] A11y detail page detected: pkg=$currentPackage cls=${event.className}")
            handleDetection(service)
            return
        }
        
        // 2. Stop Keywords Check (Requires App Name Context)
        val hasAppName = com.ironwill.app.protection.AntiUninstallProtection.PROTECTED_APP_NAMES.any { 
            content.contains(it, ignoreCase = true) 
        }
        
        if (hasAppName && STOP_KEYWORDS.any { content.contains(it, ignoreCase = true) }) {
             Log.w(TAG, "!!! Detected Stop Service Attempt (Context: IronWill) !!!")
               LogExporter.logEvent(TAG, "[BLOCK] Stop service detected: pkg=$currentPackage cls=${event.className}")
             handleDetection(service)
             return
        }
    }

    private fun handleDetection(service: AccessibilityService) {
        // 🚀 [Rate Limit] Prevent spamming Global Actions/Dialogs (Throttled 500ms)
        val now = System.currentTimeMillis()
        if (now - lastDetectionTime < 500) {
             Log.v(TAG, "Rate Limit: Ignoring detection (Throttled)")
             return
        }
        lastDetectionTime = now

        service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
        service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
        if (!WARNING_DIALOG_ENABLED) {
            LogExporter.logEvent(TAG, "[Dialog] Suppressed: accessibility warning popup disabled")
        }
    }
    
    private fun collectAllText(node: AccessibilityNodeInfo?, sb: StringBuilder) {
        if (node == null) return
        if (node.isEditable || node.className == "android.widget.EditText") return
        
        if (!node.text.isNullOrEmpty()) sb.append(node.text).append(" ")
        if (!node.contentDescription.isNullOrEmpty()) sb.append(node.contentDescription).append(" ")
        
        val count = node.childCount
        for (i in 0 until count) {
            val child = node.getChild(i)
            if (child != null) {
                collectAllText(child, sb)
                child.recycle()
            }
        }
    }
}
