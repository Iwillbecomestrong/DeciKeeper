package com.decikeeper.app.ui

import android.content.Context
import android.view.KeyEvent
import com.decikeeper.app.ui.AlarmOverlayRootView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

        // 四个出口：取消 / 存为默认 / 测试 / 保存；「同时设为默认」勾选框已移除（多余）
        assertFalse(content.contains("cb_also_save_default"))
        assertFalse(content.contains("同时设为默认设置"))
        assertFalse(content.contains("只存为默认"))
        assertTrue(content.contains("bg_zz_pill"))

        val expected = linkedMapOf(
            "btn_alarm_edit_cancel" to "取消",
            "btn_alarm_edit_save_default" to "存为默认",
            "btn_alarm_edit_test" to "测试",
            "btn_alarm_edit_save" to "保存"
        )
        expected.forEach { (id, label) ->
            val tag = content.substringAfter("android:id=\"@+id/$id\"").substringBefore("/>")
            assertTrue("$id label", tag.contains("android:text=\"$label\""))
            // 2×2 网格：所有按钮等宽（同权重）等高，且全部自动缩字，保证文字完整显示
            assertTrue("$id equal width weight", tag.contains("android:layout_weight=\"1\""))
            assertTrue("$id equal height", tag.contains("android:layout_height=\"44dp\""))
            assertTrue("$id autosize", tag.contains("app:autoSizeTextType=\"uniform\""))
        }
        assertFalse("no unequal weights in the button grid", content.contains("android:layout_weight=\"1.6\""))

        // 顺序：第一行 取消 | 存为默认，第二行 测试 | 保存
        val order = expected.keys.map { content.indexOf("android:id=\"@+id/$it\"") }
        assertEquals(order.sorted(), order)
    }

    @Test
    fun unifiedAlarmDialog_saveDefaultKeepsDialogOpenAndSaveLabelIsFixed() {
        val cwd = File(System.getProperty("user.dir") ?: ".")
        val file = listOf(
            File(cwd, "app/src/main/java/com/decikeeper/app/ui/AlarmFragment.kt"),
            File(cwd, "src/main/java/com/decikeeper/app/ui/AlarmFragment.kt")
        ).firstOrNull { it.isFile }
        assertNotNull("AlarmFragment.kt must exist", file)
        val src = file!!.readText()

        assertFalse(src.contains("cbAlsoDefault"))
        assertTrue(src.contains("R.id.btn_alarm_edit_save).text = \"保存\""))

        val handler = src.substringAfter("R.id.btn_alarm_edit_save_default).setOnClickListener {")
            .substringBefore("R.id.btn_alarm_edit_save).setOnClickListener {")
        assertTrue(handler.contains("saveAsDefault(f)"))
        assertFalse("save-as-default must keep the dialog open", handler.contains("editDialog.dismiss()"))
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
