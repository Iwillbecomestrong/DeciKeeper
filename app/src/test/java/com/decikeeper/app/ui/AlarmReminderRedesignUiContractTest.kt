package com.decikeeper.app.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AlarmReminderRedesignUiContractTest {
    private fun source(path: String): String {
        val cwd = File(System.getProperty("user.dir"))
        val candidates = listOf(
            File(cwd, path),
            File(cwd, "app/$path"),
            File(cwd.parentFile ?: cwd, path),
            File(cwd.parentFile ?: cwd, "app/$path")
        )
        return candidates.firstOrNull { it.isFile }?.readText()
            ?: error("Cannot locate $path from ${cwd.absolutePath}")
    }

    @Test
    fun testAlarmEditAndDefaultUiConformsToSpec() {
        val fragment = source("src/main/java/com/decikeeper/app/ui/AlarmFragment.kt")
        val editXml = source("src/main/res/layout/dialog_alarm_edit.xml")
        val reminderView = source("src/main/java/com/decikeeper/app/ui/AlarmReminderSettingsView.kt")

        // 1. 渐进文案契约：从最低强度开始，每 30 秒提高 1 级，直到最高强度
        assertTrue(
            "reminder settings view must contain progressive 30-second ramp copy",
            reminderView.contains("从最低强度开始，每 30 秒提高 1 级，直到最高强度")
        )

        // 2. 移除旧的 5 分钟突破 / autoEscalate 文案
        assertFalse(
            "AlarmFragment must not contain legacy 5-minute auto escalate copy",
            fragment.contains("5分钟后自动升级到最大")
        )

        // 3. 第二关独立提醒设置
        assertTrue(
            "AlarmFragment must expose stage 2 independent alert policy checkbox",
            fragment.contains("第二关使用独立提醒设置")
        )

        // 4. 完成后延迟契约
        assertTrue(
            "dialog_alarm_edit.xml must use 完成后延迟",
            editXml.contains("完成后延迟")
        )
    }

    @Test
    fun testReminderSectionAttachesByIdNotByChildPosition() {
        val fragment = source("src/main/java/com/decikeeper/app/ui/AlarmFragment.kt")
        val editXml = source("src/main/res/layout/dialog_alarm_edit.xml")

        // 提醒设置卡片必须按 id 挂到弹窗滚动区；按子节点位置找（弹窗顶部有标题）会得到 null，
        // 再被 ?. 悄悄跳过，整块提醒设置就消失。
        assertTrue(
            "dialog_alarm_edit.xml must expose layout_alarm_edit_content for the reminder section",
            editXml.contains("@+id/layout_alarm_edit_content")
        )
        assertTrue(
            "AlarmFragment must find the content container by id",
            fragment.contains("findViewById<LinearLayout>(R.id.layout_alarm_edit_content)")
        )
        assertFalse(
            "AlarmFragment must not locate the content container by child position",
            fragment.contains("getChildAt(0) as? ScrollView")
        )
        assertFalse(
            "reminder section must not be added through a silent null-safe call",
            fragment.contains("innerLayout?.addView(editReminderSection)")
        )
    }
}
