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
        val defaultXml = source("src/main/res/layout/dialog_alarm_default_settings.xml")

        // 1. 渐进文案契约：从最低强度开始，每 30 秒提高 1 级，直到最高强度
        assertTrue(
            "AlarmFragment must contain progressive 30-second ramp copy",
            fragment.contains("从最低强度开始，每 30 秒提高 1 级，直到最高强度")
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
        assertTrue(
            "dialog_alarm_default_settings.xml must use 完成后延迟",
            defaultXml.contains("完成后延迟")
        )
    }
}
