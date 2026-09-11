package com.decikeeper.app

import android.view.accessibility.AccessibilityEvent
import com.decikeeper.app.manager.isShutdownDialogClass
import com.decikeeper.app.manager.isSystemDialogPackage
import com.decikeeper.app.manager.isVoiceAssistantPackage
import com.decikeeper.app.manager.shouldDismissVoiceAssistantOverAlarm
import com.decikeeper.app.manager.shouldHandleShutdownDialogEvent
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 系统弹层识别策略契约。
 *
 * 类名与包名取自 2026-09-11 vivo V2425A 真机日志（长按电源键 → 关机菜单 / 语音助手），
 * 不是构造出来的示例值。此前该识别逻辑埋在 CoreService 的 companion 里、无任何测试守护。
 */
class SystemDialogDetectionPolicyTest {

    /** 真机日志原文：com.vivo.systemuiplugin.systemui.statusbar.globalactions.VivoShutdownActionDialog */
    private val vivoShutdownDialogClass =
        "com.vivo.systemuiplugin.systemui.statusbar.globalactions.VivoShutdownActionDialog"

    @Test
    fun systemDialogPackagesAreRecognized() {
        assertTrue(isSystemDialogPackage("android"))
        assertTrue(isSystemDialogPackage("com.vivo.systemuiplugin"))
        assertTrue(isSystemDialogPackage("com.android.systemui"))
    }

    @Test
    fun arbitraryPackageIsNotASystemDialogPackage() {
        assertFalse(isSystemDialogPackage("com.tencent.mm"))
        assertFalse(isSystemDialogPackage(null))
    }

    @Test
    fun shutdownDialogClassMatchesRealDeviceClassName() {
        assertTrue(isShutdownDialogClass(vivoShutdownDialogClass))
        assertTrue(isShutdownDialogClass("com.android.internal.globalactions.GlobalActionsDialog"))
    }

    @Test
    fun genericFrameworkClassesAreNotShutdownDialogs() {
        // 语音助手的窗口类是通用的 FrameLayout / ImageView，不能靠类名识别
        assertFalse(isShutdownDialogClass("android.widget.FrameLayout"))
        assertFalse(isShutdownDialogClass("android.widget.ImageView"))
        assertFalse(isShutdownDialogClass(null))
    }

    @Test
    fun shutdownDialogEventRequiresAllThreeConditions() {
        assertTrue(
            shouldHandleShutdownDialogEvent(
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
                "com.vivo.systemuiplugin",
                vivoShutdownDialogClass
            )
        )
        // 包名对但类名不对 → 不认
        assertFalse(
            shouldHandleShutdownDialogEvent(
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
                "com.vivo.systemuiplugin",
                "android.widget.FrameLayout"
            )
        )
        // 类名对但包名不对 → 不认
        assertFalse(
            shouldHandleShutdownDialogEvent(
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
                "com.tencent.mm",
                vivoShutdownDialogClass
            )
        )
        // 事件类型不支持 → 不认
        assertFalse(
            shouldHandleShutdownDialogEvent(
                AccessibilityEvent.TYPE_VIEW_CLICKED,
                "com.vivo.systemuiplugin",
                vivoShutdownDialogClass
            )
        )
    }

    @Test
    fun voiceAssistantPackageIsRecognized() {
        assertTrue(isVoiceAssistantPackage("com.vivo.ai.copilot"))
        assertFalse(isVoiceAssistantPackage("com.vivo.systemuiplugin"))
        assertFalse(isVoiceAssistantPackage(null))
    }

    @Test
    fun voiceAssistantIsNotTreatedAsShutdownDialog() {
        // 记录当前分工：助手走单独的判定分支，因为它的窗口类名无法识别
        assertFalse(isSystemDialogPackage("com.vivo.ai.copilot"))
        assertFalse(
            shouldHandleShutdownDialogEvent(
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
                "com.vivo.ai.copilot",
                "android.widget.FrameLayout"
            )
        )
    }

    @Test
    fun voiceAssistantEventsAreDismissedOverAlarm() {
        // 真机日志中助手会连续吐出 WINDOW_STATE_CHANGED 与 WINDOW_CONTENT_CHANGED
        assertTrue(
            shouldDismissVoiceAssistantOverAlarm(
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, "com.vivo.ai.copilot"
            )
        )
        assertTrue(
            shouldDismissVoiceAssistantOverAlarm(
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED, "com.vivo.ai.copilot"
            )
        )
    }

    @Test
    fun voiceAssistantDismissalIsScopedToAssistantOnly() {
        // 关机菜单不走进这个分支（它有自己的判定），其他包更不应该被 BACK
        assertFalse(
            shouldDismissVoiceAssistantOverAlarm(
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, "com.vivo.systemuiplugin"
            )
        )
        assertFalse(
            shouldDismissVoiceAssistantOverAlarm(
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, "com.tencent.mm"
            )
        )
        assertFalse(
            shouldDismissVoiceAssistantOverAlarm(
                AccessibilityEvent.TYPE_VIEW_CLICKED, "com.vivo.ai.copilot"
            )
        )
    }
}
