/**
 * =================================================================================
 * [Context Header]
 * * @file: PermissionProtection.kt
 * * @role: Domain Skill
 * * @description: 权限页面拦截与保护逻辑。
 * * @responsibilities:
 * 1. 识别权限管理入口。
 * 2. 触发保护拦截与回退。
 * * @context:
 * - 上游: ProtectionManager
 * - 下游: LogExporter, LockWindowManager
 * * @constraints (必读):
 * 1. 不引入 UI 依赖。
 * 2. 需快速响应。
 * 3. 空值安全。
 * =================================================================================
 */
package com.ironwill.app.protection

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class PermissionProtection(private val context: Context) {

    companion object {
        const val TAG = "PermProtect"
        const val WARNING_DIALOG_ENABLED = false
        private var lastDetectionTime = 0L

        // Settings & permission manager packages — only intercept under these
        private val SETTINGS_PACKAGES = setOf(
            "com.android.settings",
            "com.android.permissioncontroller",
            "com.google.android.permissioncontroller",
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

        // Safe context keywords — skip if page is search history, not permission management
        private val SAFE_SEARCH_KEYWORDS = listOf(
            "最近搜索", "搜索历史", "历史记录", "清除历史", "Search history", "清除",
            "最近搜尋", "搜尋紀錄", "歷史記錄", "清除紀錄",
            "搜索", "Search", "搜尋"
        )
        
        // 🌐 [Multi-Language] Combined Permission Keywords
        val PERMISSION_KEYWORDS = setOf(
            // SC - 悬浮窗/Overlay
            "悬浮窗", "悬浮窗权限", "悬浮窗管理", "允许悬浮窗", "显示在其他应用上层", "在其他应用上层显示",
            // SC - 其他权限
            "应用使用情况", "使用情况访问", "自启动", "后台启动", "电池优化", "省电策略",
            // TC - 悬浮窗/Overlay
            "懸浮視窗", "懸浮窗權限", "懸浮窗管理", "允許懸浮窗", "顯示在其他應用上層", "顯示在其他應用程式上層",
            // TC - 其他权限
            "應用程式使用情況", "使用狀況存取權", "自動啟動", "背景啟動", "電池優化", "省電策略",
            // EN - Overlay
            "Display over other apps", "Appear on top", "Draw over", "Overlay",
            // EN - Other permissions
            "Usage access", "Usage data access",
            "Autostart", "Auto-start", "Background start", "Start in background", "Battery optimization", "Battery saver",
            // JP (Partial)
            "他のアプリの上に重ねて表示", "使用状況へのアクセス", "自動起動", "バックグラウンド起動", "電池の最適化", "バッテリーセーバー"
        )
        
        // 🌐 [Multi-Language] Revoke/Close/Deny Keywords
        val REVOKE_KEYWORDS = setOf(
            // SC
            "关闭", "拒绝", "不允许", "禁止", "限制",
            // TC
            "關閉", "拒絕", "不允許", "禁止", "限制",
            // EN
            "Turn off", "Deny", "Don't allow", "Restrict", "Not allowed", "Disable",
            // JP
            "オフ", "許可しない", "拒否", "制限"
        )
    }

    fun check(service: AccessibilityService, event: AccessibilityEvent, root: AccessibilityNodeInfo) {
        // 🚫 [Gate] 仅防关权限保护 ACTIVE 或 PENDING 时才拦截
        // PENDING 表示"已申请关闭，次日生效"，生效前仍需保护
        val permState = com.ironwill.app.AccessibilityControlManager.getState(context)
        if (permState != com.ironwill.app.AccessibilityControlManager.State.ACTIVE &&
            permState != com.ironwill.app.AccessibilityControlManager.State.PENDING) {
            return
        }

        // 🚫 [Package Filter] Only intercept in settings/permission manager packages
        val pkg = event.packageName?.toString() ?: ""
        if (pkg == "com.android.systemui") return
        val isSettingsPkg = SETTINGS_PACKAGES.any { pkg.startsWith(it) }
        if (!isSettingsPkg) return

        val eventText = event.text?.toString() ?: ""
        val eventClassName = event.className?.toString() ?: ""

        // 🚀 [Class-Based Detection] Check FIRST — known permission page classes like
        // FloatWindowManagerActivity always manage permissions regardless of current content
        val isPermissionPageClass = eventClassName.contains("FloatWindow", ignoreCase = true) ||
                                    eventClassName.contains("SoftPermission", ignoreCase = true) ||
                                    eventClassName.contains("Permission", ignoreCase = true) ||
                                    eventClassName.contains("Overlay", ignoreCase = true)

        // 🚀 [Quick Path] Check event.text for IronWill first — skip full tree scan if found
        val hasAppNameInEvent = com.ironwill.app.protection.AntiUninstallProtection.PROTECTED_APP_NAMES.any {
            eventText.contains(it, ignoreCase = true)
        }

        val allText = StringBuilder()
        var hasAppName = hasAppNameInEvent

        if (hasAppNameInEvent) {
            // IronWill already in event text, skip collectAllText entirely
            Log.v(TAG, "[QuickPath] IronWill found in event text, skip full tree scan")
            allText.append(eventText)
            allText.append(" ").append(eventClassName)
        } else if (isPermissionPageClass) {
            // 🚀 [Targeted Search] Known permission page — use targeted deep search for IronWill
            // Much faster than collectAllText: stops immediately on first match
            // Uses wider limits (depth=20, children=50) to reach RecyclerView items
            hasAppName = hasProtectedAppName(root)
            if (hasAppName) {
                Log.v(TAG, "[TargetedScan] IronWill found via targeted search in permission page class=$eventClassName")
                // Only collect full text after confirming IronWill presence
                collectAllText(root, allText)
            }
        } else {
            // Standard full tree scan with performance limits
            collectAllText(root, allText)
            hasAppName = com.ironwill.app.protection.AntiUninstallProtection.PROTECTED_APP_NAMES.any {
                allText.contains(it, ignoreCase = true)
            }
        }

        if (!hasAppName) return

        val content = allText.toString()
        val hasPermissionKeyword = PERMISSION_KEYWORDS.any { content.contains(it, ignoreCase = true) }

        if (hasPermissionKeyword || isPermissionPageClass) {
            // 🚫 [Safe Context] Only skip for unknown page types — known permission page
            // classes (FloatWindow/SoftPermission/Permission/Overlay) are NEVER safe to skip
            if (!isPermissionPageClass && SAFE_SEARCH_KEYWORDS.any { content.contains(it, ignoreCase = true) }) {
                Log.v(TAG, "[Skip] Search history context detected, not a permission page")
                return
            }
            Log.w(TAG, "!!! Permission Revocation Attempt Detected: $content !!!")
            handleDetection(service)
        }
    }

    private fun handleDetection(service: AccessibilityService) {
        val now = System.currentTimeMillis()
        if (now - lastDetectionTime < 500L) return
        lastDetectionTime = now

        service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
        service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
        if (!WARNING_DIALOG_ENABLED) {
            com.ironwill.app.LogExporter.logEvent(TAG, "[Dialog] Suppressed: permission warning popup disabled")
        }
    }

    /**
     * 🚀 [Targeted Search] Fast presence check for protected app names in the accessibility tree.
     * Stops immediately on first match — much faster than collectAllText for deep RecyclerViews.
     * Uses wider limits (depth=20, children=50) since it returns early on match.
     */
    private fun hasProtectedAppName(node: AccessibilityNodeInfo?, depth: Int = 0): Boolean {
        if (node == null || depth > 20) return false

        val text = node.text?.toString() ?: ""
        val desc = node.contentDescription?.toString() ?: ""
        val combined = text + desc
        if (com.ironwill.app.protection.AntiUninstallProtection.PROTECTED_APP_NAMES.any {
            combined.contains(it, ignoreCase = true)
        }) {
            return true
        }

        val count = kotlin.math.min(node.childCount, 50)
        for (i in 0 until count) {
            val child = node.getChild(i)
            if (child != null) {
                if (hasProtectedAppName(child, depth + 1)) {
                    child.recycle()
                    return true
                }
                child.recycle()
            }
        }
        return false
    }

    private fun collectAllText(node: AccessibilityNodeInfo?, sb: StringBuilder, depth: Int = 0) {
        if (node == null) return
        // Performance limits: don't traverse too deep or collect too much text
        // depth=10, content=5000, children=30 covers RecyclerView lists (e.g. FloatWindowManagerActivity)
        if (depth > 10) return
        if (sb.length > 5000) return
        if (node.isEditable || node.className == "android.widget.EditText") return

        if (!node.text.isNullOrEmpty()) sb.append(node.text).append(" ")
        if (!node.contentDescription.isNullOrEmpty()) sb.append(node.contentDescription).append(" ")

        val count = kotlin.math.min(node.childCount, 30)
        for (i in 0 until count) {
            val child = node.getChild(i)
            if (child != null) {
                collectAllText(child, sb, depth + 1)
                child.recycle()
                if (sb.length > 5000) break
            }
        }
    }
}
