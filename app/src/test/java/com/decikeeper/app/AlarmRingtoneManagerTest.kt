package com.decikeeper.app

import android.content.Context
import android.net.Uri
import com.decikeeper.app.manager.AlarmRingtoneManager
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
class AlarmRingtoneManagerTest {

    @Test
    fun getSystemRingtones_hasDefaultAlarmAsFirstItem() {
        val context: Context = RuntimeEnvironment.getApplication()
        val systemRingtones = AlarmRingtoneManager.getSystemRingtones(context)

        assertTrue(systemRingtones.isNotEmpty())
        val first = systemRingtones.first()
        assertEquals(AlarmRingtoneManager.DEFAULT_SYSTEM_ALARM_ID, first.id)
        assertEquals(AlarmRingtoneManager.DEFAULT_SYSTEM_ALARM_TITLE, first.title)
        assertFalse(first.isCustom)
    }

    @Test
    fun getRingtoneTitle_handlesDefaultAndNullProperly() {
        val context: Context = RuntimeEnvironment.getApplication()

        assertEquals("系统默认闹钟", AlarmRingtoneManager.getRingtoneTitle(context, null))
        assertEquals("系统默认闹钟", AlarmRingtoneManager.getRingtoneTitle(context, ""))
        assertEquals("系统默认闹钟", AlarmRingtoneManager.getRingtoneTitle(context, AlarmRingtoneManager.DEFAULT_SYSTEM_ALARM_ID))
    }

    @Test
    fun customRingtone_importAndDeletionLifecycle() {
        val context: Context = RuntimeEnvironment.getApplication()

        // 建立一个临时的测试音频文件
        val tempAudio = File(context.cacheDir, "test_ringtone.mp3")
        tempAudio.writeBytes("mock audio data content".toByteArray())
        val sourceUri = Uri.fromFile(tempAudio)

        val imported = AlarmRingtoneManager.importCustomRingtone(context, sourceUri)
        assertNotNull(imported)
        assertTrue(imported!!.isCustom)
        assertEquals("test_ringtone", imported.title)

        val customList = AlarmRingtoneManager.getCustomRingtones(context)
        assertTrue(customList.any { it.id == imported.id })

        val resolvedTitle = AlarmRingtoneManager.getRingtoneTitle(context, imported.uri)
        assertEquals("test_ringtone", resolvedTitle)

        // 测试删除
        val deleted = AlarmRingtoneManager.deleteCustomRingtone(context, imported.id)
        assertTrue(deleted)

        val afterDeleteList = AlarmRingtoneManager.getCustomRingtones(context)
        assertFalse(afterDeleteList.any { it.id == imported.id })
    }

    @Test
    fun activityLayout_hasTwoColumnSplitStructure() {
        val cwd = File(System.getProperty("user.dir") ?: ".")
        val candidates = listOf(
            File(cwd, "app/src/main/res/layout/activity_alarm_ringtone_picker.xml"),
            File(cwd, "src/main/res/layout/activity_alarm_ringtone_picker.xml")
        )
        val file = candidates.firstOrNull { it.isFile }
        assertNotNull("activity_alarm_ringtone_picker.xml must exist", file)
        val content = file!!.readText()

        assertTrue(content.contains("list_system_ringtones"))
        assertTrue(content.contains("list_custom_ringtones"))
        assertTrue(content.contains("btn_import_custom"))
        assertTrue(content.contains("btn_save_ringtone"))
        assertTrue(content.contains("系统自带"))
        assertTrue(content.contains("自己导入"))
    }
}
