/**
 * =================================================================================
 * [Context Header]
 * * @file: InstallProtection.kt
 * * @role: Domain Skill
 * * @description: 安装拦截：系统安装器里安装 / 更新决定守护的页面一出现就退回桌面。
 * * @responsibilities:
 * 1. 在拦截强制生效时识别针对本应用的安装 / 更新页。
 * 2. 命中后退回桌面，用户来不及点「继续安装」。
 * * @context:
 * - 上游: ProtectionManager（先于防卸载派发，否则会被它的安装流程豁免放行）
 * - 下游: InstallPageMatcher, InstallGuardManager, ProtectionBlockAction
 * * @constraints (必读):
 * 1. 不引入 UI 依赖。
 * 2. 需快速响应：先按控件 id 直查应用名，不遍历整棵树；未命中再做有上限的兜底扫描。
 * 3. 非安装器包名与未强制生效时零成本返回。
 * =================================================================================
 */
package com.decikeeper.app.protection

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.decikeeper.app.manager.InstallGuardManager
import com.decikeeper.app.protection.InstallPageMatcher.PageNode

class InstallProtection(private val context: Context) {

    companion object {
        const val TAG = "InstallGuard"

        // 内容变化事件很密，兜底扫描限频；窗口切换事件不限频。
        private const val CONTENT_SCAN_INTERVAL_MS = 250L
        private const val MAX_NODES = 300
        private const val MAX_DEPTH = 14

        private var lastContentScanTime = 0L
    }

    fun check(service: AccessibilityService, event: AccessibilityEvent, root: AccessibilityNodeInfo) {
        val pkg = event.packageName?.toString().orEmpty()
        if (!InstallPageMatcher.isInstallerPackage(pkg)) return
        if (!InstallGuardManager.isEnforcing(context)) return

        val now = System.currentTimeMillis()
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> Unit
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                if (now - lastContentScanTime < CONTENT_SCAN_INTERVAL_MS) return
                lastContentScanTime = now
            }
            else -> return
        }

        // 快路径：vivo 风险拦截页的应用名控件，按 id 直查。
        val byId = root.findAccessibilityNodeInfosByViewId("$pkg:${InstallPageMatcher.APP_NAME_VIEW_ID_SUFFIX}")
        if (byId.any { InstallPageMatcher.isProtectedAppName(it.text?.toString()) }) {
            Log.w(TAG, "Install page for this app blocked (by id): pkg=$pkg type=${event.eventType}")
            ProtectionBlockAction.backAndHome(service)
            return
        }

        val nodes = ArrayList<PageNode>()
        collectNodes(root, nodes, 0)
        if (InstallPageMatcher.matches(pkg, nodes)) {
            Log.w(TAG, "Install page for this app blocked (by text): pkg=$pkg type=${event.eventType}")
            ProtectionBlockAction.backAndHome(service)
        }
    }

    private fun collectNodes(node: AccessibilityNodeInfo?, out: MutableList<PageNode>, depth: Int) {
        if (node == null || depth > MAX_DEPTH || out.size >= MAX_NODES) return
        val text = node.text?.toString()
        if (!text.isNullOrBlank()) out.add(PageNode(node.viewIdResourceName, text))
        for (i in 0 until node.childCount) {
            if (out.size >= MAX_NODES) return
            collectNodes(node.getChild(i), out, depth + 1)
        }
    }
}
