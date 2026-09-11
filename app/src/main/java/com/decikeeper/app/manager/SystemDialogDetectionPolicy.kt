package com.decikeeper.app.manager

import android.view.accessibility.AccessibilityEvent

/**
 * 系统弹层识别策略。
 *
 * 闹钟挑战覆盖层需要盖住并拦截「关机菜单」这类系统弹层，否则用户在挑战期间可以直接
 * 关机绕过。识别逻辑原先埋在 CoreService 的 companion 里、无法单测，这里抽成纯函数。
 *
 * 真机背景（vivo V2425A，2026-09-11）：
 * 长按电源键会**先唤起语音助手**（com.vivo.ai.copilot）再弹出关机菜单
 * （com.vivo.systemuiplugin ... VivoShutdownActionDialog）。助手的窗口类是通用的
 * android.widget.FrameLayout / ImageView，无法按类名识别，只能按包名判定。
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

/** vivo 语音助手（Jovi / AI Copilot）。 */
internal fun isVoiceAssistantPackage(pkgName: String?): Boolean =
    pkgName == "com.vivo.ai.copilot"

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

/**
 * 闹钟挑战期间是否需要把该事件当作「抢占窗口层级的系统弹层」处理。
 *
 * 调用点位于 LockWindowManager.isAlarmOverlayShowing 块内，因此只作用于闹钟挑战期间，
 * 不影响普通锁机与日常使用（不阻止用户平时语音拨号或求助）。
 */
internal fun shouldDismissVoiceAssistantOverAlarm(
    eventType: Int,
    pkgName: String?
): Boolean {
    return isSupportedDialogEventType(eventType) && isVoiceAssistantPackage(pkgName)
}
