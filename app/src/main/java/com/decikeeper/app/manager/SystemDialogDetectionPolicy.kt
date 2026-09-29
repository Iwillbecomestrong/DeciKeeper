package com.decikeeper.app.manager

import android.view.accessibility.AccessibilityEvent

/**
 * 系统弹层识别策略。
 *
 * 闹钟挑战覆盖层需要盖住并拦截「关机菜单」这类系统弹层，否则用户在挑战期间可以直接
 * 关机绕过。识别逻辑原先埋在 CoreService 的 companion 里、无法单测，这里抽成纯函数。
 *
 * 真机背景（vivo V2425A，2026-09-11）：
 * 长按电源键可能先唤起语音助手（com.vivo.ai.copilot），随后才弹出真正的关机菜单
 * （com.vivo.systemuiplugin ... VivoShutdownActionDialog）。
 *
 * 这里有意只识别真正的关机/重启菜单。语音助手本身不具备关机能力，也不应该触发
 * 闹钟覆盖层的跨 WindowManager 重挂载或 GLOBAL_ACTION_BACK。
 */

/** 承载关机菜单 / 音量面板等系统对话框的包。 */
internal fun isSystemDialogPackage(pkgName: String?): Boolean {
    return pkgName == "android" ||
        pkgName == "com.vivo.systemuiplugin" ||
        pkgName == "com.android.systemui"
}

/** 关机菜单的窗口类名特征（AOSP GlobalActions / Vivo ShutdownActionDialog 等）。 */
internal fun isShutdownDialogClass(className: String?): Boolean {
    val cls = className ?: return false
    return cls.contains("GlobalActions", ignoreCase = true) ||
        cls.contains("ShutdownThread", ignoreCase = true) ||
        cls.contains("PowerOff", ignoreCase = true) ||
        cls.contains("ShutdownAction", ignoreCase = true) ||
        cls.contains("ShutdownDialog", ignoreCase = true)
}

internal fun isSupportedDialogEventType(eventType: Int): Boolean {
    return eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
        eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED ||
        eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED
}

internal fun shouldHandleShutdownDialogEvent(
    eventType: Int,
    pkgName: String?,
    className: String?
): Boolean {
    return isSupportedDialogEventType(eventType) &&
        isSystemDialogPackage(pkgName) &&
        isShutdownDialogClass(className)
}
