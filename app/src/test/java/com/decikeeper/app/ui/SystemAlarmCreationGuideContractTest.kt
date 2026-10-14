package com.decikeeper.app.ui

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SystemAlarmCreationGuideContractTest {
    private fun source(path: String): String =
        generateSequence(File(System.getProperty("user.dir") ?: ".").absoluteFile) { it.parentFile }
            .map { File(it, "app/src/main/$path") }
            .first { it.isFile }.readText()

    @Test
    fun suggestionIsOnlyAfterSavingFirstRealAlarmAndAfterKeepaliveGuide() {
        val fragment = source("java/com/decikeeper/app/ui/AlarmFragment.kt")
        val save = fragment.substringAfter("R.id.btn_alarm_edit_save).setOnClickListener {")
            .substringBefore("private fun upsertAlarm")
        assertTrue(save.contains("existing == null && alarms.isEmpty()"))
        assertTrue(save.indexOf("upsertAlarm(entry)") < save.indexOf("maybeSuggestSystemAlarm(entry)"))
        assertTrue(save.indexOf("editDialog.dismiss()") < save.indexOf("maybeSuggestSystemAlarm(entry)"))
        assertTrue(save.contains("maybeShowKeepAliveGuideOnFirstActiveAlarm {"))
        assertTrue(save.contains("if (isFirstAlarm && isAdded) maybeSuggestSystemAlarm(entry)"))
        val otherExits = fragment.substringAfter("R.id.btn_alarm_edit_test).setOnClickListener {")
            .substringBefore("R.id.btn_alarm_edit_save).setOnClickListener {")
        assertFalse(otherExits.contains("maybeSuggestSystemAlarm"))
        val suggestion = fragment.substringAfter("private fun maybeSuggestSystemAlarm")
            .substringBefore("private fun addSystemAlarm")
        assertTrue(suggestion.contains("getBoolean(\"system_alarm_suggestion_shown\", false)"))
        assertTrue(suggestion.contains("putBoolean(\"system_alarm_suggestion_shown\", true)"))
        assertTrue(suggestion.contains("setPositiveButton(\"去创建\") { _, _ -> addSystemAlarm(alarm) }"))
        assertTrue(suggestion.contains("setNegativeButton(\"稍后\", null)"))
    }

    @Test
    fun entryExplainsIndependentManagementAndKeepsSystemClockLaunchContract() {
        val fragment = source("java/com/decikeeper/app/ui/AlarmFragment.kt")
        assertFalse(fragment.contains("同步到系统闹钟"))
        assertFalse(fragment.contains("同步失败"))
        val copy = source("res/values/strings_system_alarm.xml")
        assertTrue(copy.contains("一键创建系统闹钟"))
        assertTrue(copy.contains("修改或删除不会联动"))
        assertTrue(copy.contains("确认时间、重复日期和铃声并保存"))
        val launch = fragment.substringAfter("private fun addSystemAlarm")
            .substringBefore("@Suppress(\"unused\")")
        assertTrue(launch.contains("AlarmClock.ACTION_SET_ALARM"))
        assertTrue(launch.contains("AlarmClock.EXTRA_DAYS, daysToSystemFormat(alarm.days)"))
        assertTrue(launch.contains("AlarmClock.EXTRA_SKIP_UI, false"))
        assertTrue(launch.contains("useCase.execute(ctx, plan)"))
    }

    @Test
    fun helpSeparatesReportedVivoIssueFromVolumeBehaviorAndWakeupGuarantees() {
        val copy = source("res/values/strings_system_alarm.xml")
        assertTrue(copy.contains("原因尚未查明"))
        assertTrue(copy.contains("固定模式按所选强度设置"))
        assertTrue(copy.contains("系统闹钟不保证唤醒决定守护"))
        assertTrue(copy.contains("确认系统闹钟已开启振动"))
        val fragment = source("java/com/decikeeper/app/ui/AlarmFragment.kt")
        val help = fragment.substringAfter("private fun systemAlarmCreationHelp()")
            .substringBefore("private fun showSystemBackupHelpDialog")
        assertTrue(help.contains("if (isVivoOrIqoo())"))
        assertTrue(help.contains("R.string.system_alarm_create_help_vivo"))
        assertTrue(fragment.split(".setMessage(systemAlarmCreationHelp())").size == 3)
    }

    @Test
    fun longActionLabelHasItsOwnRowAndAutosizesWithoutSqueezingTime() {
        val layout = source("res/layout/item_alarm.xml")
        val timeRow = layout.substringAfter("@+id/tv_alarm_time").substringBefore("</LinearLayout>")
        assertFalse(timeRow.contains("btn_system_backup_action"))
        val action = layout.substringBefore("@+id/btn_system_backup_action").substringAfterLast("<") +
            layout.substringAfter("@+id/btn_system_backup_action").substringBefore("/>")
        assertTrue(action.contains("androidx.appcompat.widget.AppCompatButton"))
        assertTrue(action.contains("@string/system_alarm_create"))
        assertTrue(action.contains("app:autoSizeTextType=\"uniform\""))
        assertTrue(action.contains("android:layout_weight=\"1\""))
        assertTrue(action.contains("android:layout_height=\"48dp\""))
    }
}
