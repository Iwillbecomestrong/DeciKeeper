/**
 * =================================================================================
 * [Context Header]
 * * @file: DeveloperOptionsProtection.kt
 * * @role: Domain Skill
 * * @description: 开发者选项页拦截（USB 调试 / 无线调试所在页面）。
 * * @responsibilities:
 * 1. 在拦截强制生效时识别开发者选项页。
 * 2. 命中后退回桌面。
 * * @context:
 * - 上游: ProtectionManager
 * - 下游: DeveloperOptionsPageMatcher, DeveloperOptionsGuardManager
 * * @constraints (必读):
 * 1. 不引入 UI 依赖。
 * 2. 需快速响应；非设置包与未强制生效时零成本返回。
 * 3. 只读取设置包页面的节点，不扩大普通应用的内容读取（见 protection.md §2.0）。
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
import com.decikeeper.app.manager.DeveloperOptionsGuardManager
import com.decikeeper.app.protection.DeveloperOptionsPageMatcher.PageNode

class DeveloperOptionsProtection(private val context: Context) {

    companion object {
        const val TAG = "DevOptionsGuard"

        // 同一次拦截后的静默窗口，与 PermissionProtection 同量级。
        internal const val DETECTION_DEBOUNCE_MS = 150L

        // 内容变化事件很密，扫描限频；窗口切换事件不限频。
        private const val CONTENT_SCAN_INTERVAL_MS = 250L

        private const val MAX_NODES = 300
        private const val MAX_DEPTH = 14

        private var lastDetectionTime = 0L
        private var lastContentScanTime = 0L
    }

    fun check(service: AccessibilityService, event: AccessibilityEvent, root: AccessibilityNodeInfo) {
        val pkg = event.packageName?.toString().orEmpty()
        if (pkg != "com.android.settings") return
        if (!DeveloperOptionsGuardManager.isEnforcing(context)) return

        val now = System.currentTimeMillis()
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> Unit
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                if (now - lastContentScanTime < CONTENT_SCAN_INTERVAL_MS) return
                lastContentScanTime = now
            }
            else -> return
        }

        val className = event.className?.toString().orEmpty()
        val nodes = ArrayList<PageNode>()
        collectNodes(root, nodes, 0)

        if (DeveloperOptionsPageMatcher.matches(pkg, className, nodes)) {
            Log.w(TAG, "Developer options page blocked: class=$className type=${event.eventType}")
            handleDetection(service)
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

    private fun handleDetection(service: AccessibilityService) {
        val now = System.currentTimeMillis()
        if (now - lastDetectionTime < DETECTION_DEBOUNCE_MS) return
        lastDetectionTime = now

        service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
        service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
        Handler(Looper.getMainLooper()).postDelayed({
            try {
                service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
            } catch (_: Exception) {}
        }, 100L)
    }
}
