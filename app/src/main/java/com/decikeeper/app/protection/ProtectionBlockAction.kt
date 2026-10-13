package com.decikeeper.app.protection

import android.accessibilityservice.AccessibilityService
import android.os.Handler
import android.os.Looper

/**
 * 拦截动作：BACK + HOME，100ms 后再补一次 HOME，与 [PermissionProtection] 的拦截动作一致。
 * 带 150ms 静默窗口，防止同一次拦截被多个事件重复触发。
 */
internal object ProtectionBlockAction {

    internal const val DEBOUNCE_MS = 150L

    private var lastBlockTime = 0L

    fun backAndHome(service: AccessibilityService) {
        val now = System.currentTimeMillis()
        if (now - lastBlockTime < DEBOUNCE_MS) return
        lastBlockTime = now

        service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
        service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
        Handler(Looper.getMainLooper()).postDelayed({
            try {
                service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
            } catch (_: Exception) {}
        }, 100L)
    }
}
