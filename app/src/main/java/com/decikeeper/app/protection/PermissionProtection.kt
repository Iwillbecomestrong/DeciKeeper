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
package com.decikeeper.app.protection

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.decikeeper.app.data.LogExporter
import com.decikeeper.app.manager.AccessibilityControlManager




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
            "com.vivo.safecenter",
            "com.vivo.abe",
            "com.iqoo.secure",
            "com.miui.securitycenter",
            "com.huawei.systemmanager",
            "com.hihonor.systemmanager",
            "com.coloros.safecenter",
            "com.oplus.safecenter",
            "com.coloros.phonemanager",
            "com.oplus.phonemanager",
            "com.coloros.securitypermission",
            "com.oplus.securitypermission",
            "com.coloros.appmanager",
            "com.oplus.appdetail",
            "com.samsung.android.sm",
            "com.samsung.android.sm_cn",
            "com.oneplus.security"
        )

        // Safe context keywords — skip if page is search history, not permission management
        private val SAFE_SEARCH_KEYWORDS = listOf(
            "最近搜索", "搜索历史", "历史记录", "清除历史", "Search history", "清除历史记录",
            "最近搜尋", "搜尋紀錄", "歷史記錄", "清除紀錄"
        )
        
        // 🌐 [Multi-Language] Combined Permission Keywords (Overlay, Autostart, Associated Launch, Usage Access etc.)
        val PERMISSION_KEYWORDS = setOf(
            // SC - 悬浮窗/Overlay
            "悬浮窗", "悬浮窗权限", "悬浮窗管理", "允许悬浮窗", "显示在其他应用上层", "在其他应用上层显示",
            // SC - 自启动/关联启动/其他权限（含 vivo/oppo/xiaomi 专属）
            "应用使用情况", "使用情况访问", "自启动", "自动启动", "后台自启", "后台启动", "关联启动", "应用关联启动", "关联唤醒", "后台高耗电", "允许后台高耗电", "电池优化", "省电策略", "单项权限", "应用权限",
            "后台弹窗", "累计后台弹窗", "近7日后台弹窗记录", "管控",
            // TC - 悬浮窗/Overlay
            "懸浮視窗", "懸浮窗權限", "懸浮窗管理", "允許懸浮窗", "顯示在其他應用上層", "顯示在其他應用程式上層",
            // TC - 自启动/关联启动/其他权限
            "應用程式使用情況", "使用狀況存取權", "自動啟動", "背景啟動", "關聯啟動", "關聯喚醒", "背景高耗電", "允許背景高耗電", "電池優化", "省電策略", "單項權限", "應用程式權限",
            "背景彈窗", "累計背景彈窗", "近7日背景彈窗記錄", "管控",
            // EN - Overlay
            "Display over other apps", "Appear on top", "Draw over", "Overlay",
            // EN - Autostart & Permissions
            "Usage access", "Usage data access",
            "Autostart", "Auto-start", "Background start", "Start in background", "Associated start", "Associated startup", "Battery optimization", "Battery saver",
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
        val permState = com.decikeeper.app.manager.AccessibilityControlManager.getState(context)
        if (permState != com.decikeeper.app.manager.AccessibilityControlManager.State.ACTIVE &&
            permState != com.decikeeper.app.manager.AccessibilityControlManager.State.PENDING) {
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
        // FloatWindowManagerActivity, PurviewActivity (vivo), SoftPermissionDetailActivity, AutoStartManagementActivity always manage permissions
        val isPermissionPageClass = eventClassName.contains("FloatWindow", ignoreCase = true) ||
                                    eventClassName.contains("SoftPermission", ignoreCase = true) ||
                                    eventClassName.contains("Permission", ignoreCase = true) ||
                                    eventClassName.contains("Overlay", ignoreCase = true) ||
                                    eventClassName.contains("Purview", ignoreCase = true) ||
                                    eventClassName.contains("BgStartUp", ignoreCase = true) ||
                                    eventClassName.contains("AutoStart", ignoreCase = true) ||
                                    eventClassName.contains("AutoRun", ignoreCase = true) ||
                                    eventClassName.contains("AssociatedStart", ignoreCase = true) ||
                                    eventClassName.contains("Startup", ignoreCase = true) ||
                                    eventClassName.contains("PopUp", ignoreCase = true) ||
                                    eventClassName.contains("PopWindow", ignoreCase = true) ||
                                    eventClassName.contains("BackgroundPop", ignoreCase = true) ||
                                    eventClassName.contains("BackgroundRecord", ignoreCase = true) ||
                                    eventClassName.contains("AppForbidden", ignoreCase = true) ||
                                    eventClassName.contains("Forbidden", ignoreCase = true) ||
                                    eventClassName.contains("SafeCenter", ignoreCase = true) ||
                                    eventClassName.contains("SoftList", ignoreCase = true)

        // 🚀 [Quick Path] Check event.text for app identity first — skip full tree scan if found
        val hasAppNameInEvent = com.decikeeper.app.protection.AntiUninstallProtection.PROTECTED_APP_NAMES.any {
            eventText.contains(it, ignoreCase = true)
        } || eventText.contains(context.packageName, ignoreCase = true)
          || eventText.contains("com.decikeeper.app", ignoreCase = true)
          || eventText.contains("com.ironwill", ignoreCase = true)

        val allText = StringBuilder()
        var hasAppName = hasAppNameInEvent

        if (hasAppNameInEvent) {
            // App identity already in event text, skip collectAllText entirely
            Log.v(TAG, "[QuickPath] App identity found in event text, skip full tree scan")
            allText.append(eventText)
            allText.append(" ").append(eventClassName)
        } else if (isPermissionPageClass) {
            // 🚀 [Targeted Search] Known permission page — use targeted deep search for app identity
            // Much faster than collectAllText: stops immediately on first match
            // Uses wider limits (depth=20, children=50) to reach RecyclerView items
            hasAppName = hasProtectedAppName(root)
            if (hasAppName) {
                Log.v(TAG, "[TargetedScan] App identity found via targeted search in permission page class=$eventClassName")
                // Only collect full text after confirming app presence
                collectAllText(root, allText)
            }
        } else {
            // Standard full tree scan with performance limits
            collectAllText(root, allText)
            hasAppName = com.decikeeper.app.protection.AntiUninstallProtection.PROTECTED_APP_NAMES.any {
                allText.contains(it, ignoreCase = true)
            } || allText.contains(context.packageName, ignoreCase = true)
              || allText.contains("com.decikeeper.app", ignoreCase = true)
              || allText.contains("com.ironwill", ignoreCase = true)
        }

        if (!hasAppName) return

        val content = allText.toString()
        val hasPermissionKeyword = PERMISSION_KEYWORDS.any { content.contains(it, ignoreCase = true) }

        if (hasPermissionKeyword || isPermissionPageClass) {
            // 🚫 [Safe Context] Only skip for search history pages — explicit permission keyword matches
            // or permission page classes are NEVER safe to skip for a generic "search" string in top search bar
            if (!hasPermissionKeyword && !isPermissionPageClass && SAFE_SEARCH_KEYWORDS.any { content.contains(it, ignoreCase = true) }) {
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
        Handler(Looper.getMainLooper()).postDelayed({
            try {
                service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
            } catch (_: Exception) {}
        }, 100L)
        if (!WARNING_DIALOG_ENABLED) {
            com.decikeeper.app.data.LogExporter.logEvent(TAG, "[Dialog] Suppressed: permission warning popup disabled")
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
        if (com.decikeeper.app.protection.AntiUninstallProtection.PROTECTED_APP_NAMES.any {
            combined.contains(it, ignoreCase = true)
        } || combined.contains(context.packageName, ignoreCase = true)
          || combined.contains("com.decikeeper.app", ignoreCase = true)
          || combined.contains("com.ironwill", ignoreCase = true)) {
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
        // Performance limits: depth=20, content=8000, children=50 covers deep OEM lists (e.g. vivo PurviewActivity at depth 15)
        if (depth > 20) return
        if (sb.length > 8000) return
        if (node.isEditable || node.className == "android.widget.EditText") return

        if (!node.text.isNullOrEmpty()) sb.append(node.text).append(" ")
        if (!node.contentDescription.isNullOrEmpty()) sb.append(node.contentDescription).append(" ")

        val count = kotlin.math.min(node.childCount, 50)
        for (i in 0 until count) {
            val child = node.getChild(i)
            if (child != null) {
                collectAllText(child, sb, depth + 1)
                child.recycle()
                if (sb.length > 8000) break
            }
        }
    }
}
