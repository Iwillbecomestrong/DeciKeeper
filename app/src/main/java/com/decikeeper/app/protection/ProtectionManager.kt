/**
 * =================================================================================
 * [Context Header]
 * * @file: ProtectionManager.kt
 * * @role: Domain Skill
 * * @description: 统一权限与防护策略调度器。
 * * @responsibilities:
 * 1. 协调各保护模块的启停。
 * 2. 统一暴露保护状态。
 * * @context:
 * - 上游: MonitorService, CoreService
 * - 下游: AccessibilityProtection, PermissionProtection, AntiUninstallProtection
 * * @constraints (必读):
 * 1. 不引入 UI 依赖。
 * 2. 需保证状态一致性。
 * 3. 空值安全。
 * =================================================================================
 */
package com.decikeeper.app.protection

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo


/**
 * Manager to handle various protection mechanisms:
 * 1. Anti-Uninstall (Prevent uninstallation of IronWill)
 * 2. Accessibility Protection (Prevent disabling service)
 * 3. Permission Protection (Prevent revoking critical permissions)
 */
object ProtectionManager {

    const val DEBUG = false

    private var antiUninstallProtection: AntiUninstallProtection? = null
    private var accessibilityProtection: AccessibilityProtection? = null
    private var permissionProtection: PermissionProtection? = null

    fun init(context: Context) {
        antiUninstallProtection = AntiUninstallProtection(context)
        accessibilityProtection = AccessibilityProtection(context)
        permissionProtection = PermissionProtection(context)
    }


    fun onAccessibilityEvent(service: AccessibilityService, event: AccessibilityEvent, root: AccessibilityNodeInfo?) {
        // 🚀 [Identity Check] Explicitly ignore self-events to prevent "Self-Locking"
        if (event.packageName == "com.decikeeper.app") {
            return
        }

        if (root == null) {
            // Downgrade to VERBOSE to avoid spam during window transitions
            if (DEBUG) {
                 android.util.Log.v(com.decikeeper.app.protection.AccessibilityProtection.TAG, "!!! ProtectionManager: root is null, Pkg: ${event.packageName}")
            }
            return
        }

        if (DEBUG) {
            android.util.Log.d(com.decikeeper.app.protection.AccessibilityProtection.TAG, "[PM] Event: Type=${event.eventType} Pkg=${event.packageName}")
        }

        // 0. Permission Protection 先行：自启动/关联启动/悬浮窗页面的拦截对延迟最敏感，
        //    它对非设置类包名几乎零成本返回；放在 AntiUninstall 的整树文本扫描之前，避免被拖慢。
        try {
            permissionProtection?.check(service, event, root)
        } catch (e: Exception) {
            android.util.Log.e(com.decikeeper.app.protection.PermissionProtection.TAG, "PermissionProtection Error", e)
        }

        // 1. Accessibility Protection (Highest Priority - prevent disabling self)
        try {
            accessibilityProtection?.check(service, event, root)
        } catch (e: Exception) {
            android.util.Log.e(com.decikeeper.app.protection.AccessibilityProtection.TAG, "AccessibilityProtection Error", e)
        }

        // 2. Anti-Uninstall Protection
        try {
            antiUninstallProtection?.check(service, event, root)
        } catch (e: Exception) {
            android.util.Log.e(com.decikeeper.app.protection.AntiUninstallProtection.TAG, "AntiUninstallProtection Error", e)
        }

    }
    
    /**
     * 🚀 [Warmup] 预创建防卸载对话框
     */
    fun warmupAntiUninstallDialog(context: Context) {
    }

    /**
     * 🚀 [Config Change] Handle locale switch
     */
    fun resetCache(context: Context) {
        antiUninstallProtection?.resetDialogCache(context)
    }
}
