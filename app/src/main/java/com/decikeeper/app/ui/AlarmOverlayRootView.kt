package com.decikeeper.app.ui

import android.content.Context
import android.util.AttributeSet
import android.view.KeyEvent
import android.widget.FrameLayout

/**
 * 闹钟响铃界面顶层视图容器。
 *
 * 覆写 [dispatchKeyEvent] 确保在任何子控件获取焦点或系统拦截音量键之前，
 * 优先捕获音量加/音量减/静音键，并触发稍后再响（Snooze）。
 */
class AlarmOverlayRootView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_VOLUME_UP ||
            event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN ||
            event.keyCode == KeyEvent.KEYCODE_VOLUME_MUTE
        ) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                LockAlarmOverlayController.requestSnoozeFromHardware("VOLUME_KEY")
            }
            return true
        }
        return super.dispatchKeyEvent(event)
    }
}
