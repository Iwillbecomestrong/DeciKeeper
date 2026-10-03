package com.decikeeper.app.ui

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 应急退出界面契约：只能真实触摸长按触发、不算完成挑战、先扣额度再结束、确认按钮防截断。 */
class AlarmEmergencyExitUiContractTest {

    private fun source(path: String): String =
        generateSequence(File(System.getProperty("user.dir") ?: ".").absoluteFile) { it.parentFile }
            .map { File(it, path) }
            .firstOrNull { it.isFile }
            ?.readText()
            ?: error("cannot find $path")

    private val overlay get() = source("app/src/main/java/com/decikeeper/app/ui/LockAlarmOverlay.kt")
    private val layout get() = source("app/src/main/res/layout/window_alarm_overlay.xml")

    private fun emergencyBlock(): String {
        val start = overlay.indexOf("// ---- 应急退出")
        val end = overlay.indexOf("private fun handleAlarmAction", start)
        assertTrue("emergency exit block must exist", start >= 0 && end > start)
        return overlay.substring(start, end)
    }

    @Test
    fun entryIsTouchOnlyWithoutClickOrKeyPaths() {
        val block = emergencyBlock()
        assertTrue(block.contains("entry.setOnTouchListener"))
        assertFalse("entry must not bind onClick", block.contains("entry.setOnClickListener"))
        assertFalse("hardware keys must not trigger the exit", block.contains("KeyEvent"))
        assertTrue(block.contains("MotionEvent.ACTION_POINTER_DOWN"))
        assertTrue(block.contains("emergencyExitTracker.consumeTrigger"))

        val entryXml = layout.substringAfter("android:id=\"@+id/tv_alarm_emergency_exit\"").substringBefore("/>")
        assertFalse(entryXml.contains("android:onClick"))
        assertFalse(entryXml.contains("android:clickable=\"true\""))
    }

    @Test
    fun exitIsNotCompletionAndChargesQuotaBeforeDismiss() {
        val block = emergencyBlock()
        assertFalse(block.contains("onAnyChallengePassed"))
        assertFalse(block.contains("showCompletedChallengeState"))
        assertTrue(block.contains("alarmProtectionEventBridge.recordAlarmDismiss(alarmId.toString(), \"EMERGENCY_EXIT\")"))

        val perform = block.substring(block.indexOf("private fun performEmergencyExit"))
        val consumeAt = perform.indexOf("AlarmEmergencyExitStore.consumeOne")
        val dismissAt = perform.indexOf("dismissAlarmOverlay(")
        assertTrue(consumeAt in 0 until dismissAt)
        // 测试闹钟（alarmId<=0）不消耗本月次数
        assertTrue(perform.contains("val isTest = alarmId <= 0"))
        assertTrue(perform.contains("if (!isTest && !AlarmEmergencyExitStore.consumeOne"))
    }

    @Test
    fun entryOnlyVisibleWhileChallengeIsStillActive() {
        assertTrue(
            overlay.contains("layoutActions?.rootView?.findViewById<View>(R.id.layout_alarm_emergency_exit)?.visibility =")
        )
        assertTrue(overlay.contains("if (forceHide) View.VISIBLE else View.GONE"))
    }

    @Test
    fun confirmPanelIsInWindowAndButtonsAutoSize() {
        assertTrue(layout.contains("android:id=\"@+id/layout_alarm_emergency_confirm\""))
        // 窗口内面板，不使用系统 Dialog（可能被覆盖层/系统层遮挡）
        assertFalse(emergencyBlock().contains("AlertDialog"))
        listOf("btn_alarm_emergency_cancel", "btn_alarm_emergency_confirm").forEach { id ->
            val tag = layout.substringAfter("android:id=\"@+id/$id\"").substringBefore("/>")
            assertTrue("$id must autosize", tag.contains("app:autoSizeTextType=\"uniform\""))
        }
        assertTrue(layout.contains("androidx.appcompat.widget.AppCompatButton"))
    }

    @Test
    fun settingsRouteThroughStoreSoPolicyDecidesEffectiveTime() {
        val fragment = source("app/src/main/java/com/decikeeper/app/ui/AlarmFragment.kt")
        assertTrue(fragment.contains("AlarmEmergencyExitStore.requestMonthlyLimit("))
        assertFalse("UI must not decide effective time itself", fragment.contains("AlarmEmergencyExitPolicy.requestChange"))
    }
}
