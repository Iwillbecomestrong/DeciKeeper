package com.decikeeper.app.ui

import android.content.Context
import android.view.KeyEvent
import com.decikeeper.app.ui.AlarmOverlayRootView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File

@RunWith(RobolectricTestRunner::class)
class AlarmUiAndHardwareSnoozeTest {

    @Test
    fun alarmOverlayRootView_interceptsVolumeKeys() {
        val context: Context = RuntimeEnvironment.getApplication()
        val rootView = AlarmOverlayRootView(context)

        // 音量加、音量减、静音键在 dispatchKeyEvent 阶段被拦截并返回 true
        val volUpEvent = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_VOLUME_UP)
        val handledUp = rootView.dispatchKeyEvent(volUpEvent)
        assertTrue("Volume Up must be handled by AlarmOverlayRootView", handledUp)

        val volDownEvent = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_VOLUME_DOWN)
        val handledDown = rootView.dispatchKeyEvent(volDownEvent)
        assertTrue("Volume Down must be handled by AlarmOverlayRootView", handledDown)

        val volMuteEvent = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_VOLUME_MUTE)
        val handledMute = rootView.dispatchKeyEvent(volMuteEvent)
        assertTrue("Volume Mute must be handled by AlarmOverlayRootView", handledMute)
    }

    @Test
    fun unifiedAlarmDialog_hasAllSaveExitsWithRoundedPills() {
        val cwd = File(System.getProperty("user.dir") ?: ".")
        val candidates = listOf(
            File(cwd, "app/src/main/res/layout/dialog_alarm_edit.xml"),
            File(cwd, "src/main/res/layout/dialog_alarm_edit.xml")
        )
        val file = candidates.firstOrNull { it.isFile }
        assertNotNull("dialog_alarm_edit.xml must exist", file)
        val content = file!!.readText()

        assertTrue(content.contains("btn_alarm_edit_cancel"))
        assertTrue(content.contains("btn_alarm_edit_test"))
        assertTrue(content.contains("btn_alarm_edit_save_default"))
        assertTrue(content.contains("btn_alarm_edit_save"))
        assertTrue(content.contains("cb_also_save_default"))
        assertTrue(content.contains("bg_zz_pill"))
        assertTrue(content.contains("取消"))
        assertTrue(content.contains("测试"))
        assertTrue(content.contains("只存为默认"))
        assertTrue(content.contains("同时设为默认设置"))
    }

    @Test
    fun windowAlarmOverlay_hasRoundedScanButtonAndOverlayRootView() {
        val cwd = File(System.getProperty("user.dir") ?: ".")
        val candidates = listOf(
            File(cwd, "app/src/main/res/layout/window_alarm_overlay.xml"),
            File(cwd, "src/main/res/layout/window_alarm_overlay.xml")
        )
        val file = candidates.firstOrNull { it.isFile }
        assertNotNull("window_alarm_overlay.xml must exist", file)
        val content = file!!.readText()

        assertTrue(content.contains("com.decikeeper.app.ui.AlarmOverlayRootView"))
        assertTrue(content.contains("btn_alarm_scan"))
        assertTrue(content.contains("@drawable/bg_zz_pill"))
    }
}
