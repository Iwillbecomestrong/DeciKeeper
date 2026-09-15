package com.decikeeper.app.manager

import android.content.Context
import android.database.Cursor
import android.media.RingtoneManager
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

data class RingtoneItem(
    val id: String,
    val title: String,
    val uri: String,
    val isCustom: Boolean = false
)

object AlarmRingtoneManager {
    private const val TAG = "AlarmRingtoneManager"
    private const val PREFS_NAME = "alarm_custom_ringtones_prefs"
    private const val KEY_CUSTOM_RINGTONES_JSON = "custom_ringtones_json"
    private const val CUSTOM_RINGTONES_DIR = "custom_ringtones"

    const val DEFAULT_SYSTEM_ALARM_ID = "system_default"
    const val DEFAULT_SYSTEM_ALARM_TITLE = "系统默认闹钟"

    /**
     * 获取系统内置铃声列表（以系统默认闹钟为第一项，后面跟随系统的闹钟与铃声）。
     */
    fun getSystemRingtones(context: Context): List<RingtoneItem> {
        val list = mutableListOf<RingtoneItem>()
        val defaultAlarmUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
        val defaultUriStr = defaultAlarmUri?.toString() ?: "content://settings/system/alarm_alert"

        list.add(
            RingtoneItem(
                id = DEFAULT_SYSTEM_ALARM_ID,
                title = DEFAULT_SYSTEM_ALARM_TITLE,
                uri = defaultUriStr,
                isCustom = false
            )
        )

        try {
            val rm = RingtoneManager(context).apply {
                setType(RingtoneManager.TYPE_ALARM or RingtoneManager.TYPE_RINGTONE)
            }
            val cursor: Cursor? = rm.cursor
            if (cursor != null) {
                while (cursor.moveToNext()) {
                    val title = cursor.getString(RingtoneManager.TITLE_COLUMN_INDEX)
                    val uri = rm.getRingtoneUri(cursor.position)?.toString()
                    if (!uri.isNullOrBlank() && list.none { it.uri == uri }) {
                        list.add(
                            RingtoneItem(
                                id = uri,
                                title = if (!title.isNullOrBlank()) title else "未命名铃声",
                                uri = uri,
                                isCustom = false
                            )
                        )
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load system ringtones", e)
        }
        return list
    }

    /**
     * 获取用户导入的自定义铃声列表。
     */
    fun getCustomRingtones(context: Context): List<RingtoneItem> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val jsonStr = prefs.getString(KEY_CUSTOM_RINGTONES_JSON, "[]") ?: "[]"
        val list = mutableListOf<RingtoneItem>()
        var needSave = false
        try {
            val array = JSONArray(jsonStr)
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val id = obj.optString("id")
                val title = obj.optString("title")
                val uri = obj.optString("uri")

                val item = RingtoneItem(id = id, title = title, uri = uri, isCustom = true)
                // 校验内部文件是否存在，如果不存在则自动清理
                val file = getCustomAudioFile(context, item)
                if (file.exists()) {
                    list.add(item)
                } else {
                    needSave = true
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing custom ringtones json", e)
        }

        if (needSave) {
            saveCustomRingtones(context, list)
        }
        return list
    }

    /**
     * 从 Content Uri 导入音频并安全拷贝至内部存储。
     */
    fun importCustomRingtone(context: Context, sourceUri: Uri): RingtoneItem? {
        val originalName = queryFileName(context, sourceUri) ?: "custom_alarm_${System.currentTimeMillis()}.mp3"
        val dir = getCustomRingtonesDir(context)
        if (!dir.exists()) dir.mkdirs()

        val safeName = sanitizeFileName(originalName)
        val targetFile = getUniqueFile(dir, safeName)

        try {
            context.contentResolver.openInputStream(sourceUri)?.use { input ->
                FileOutputStream(targetFile).use { output ->
                    input.copyTo(output)
                }
            } ?: return null
        } catch (e: Exception) {
            Log.e(TAG, "Failed to copy custom ringtone from uri: $sourceUri", e)
            if (targetFile.exists()) targetFile.delete()
            return null
        }

        val displayName = targetFile.nameWithoutExtension.ifBlank { "导入铃声" }
        val targetUri = Uri.fromFile(targetFile).toString()
        val newItem = RingtoneItem(
            id = "custom_${targetFile.name}",
            title = displayName,
            uri = targetUri,
            isCustom = true
        )

        val currentList = getCustomRingtones(context).toMutableList()
        currentList.add(0, newItem) // 最新导入的排在最前
        saveCustomRingtones(context, currentList)
        return newItem
    }

    /**
     * 删除自定义导入的铃声。
     */
    fun deleteCustomRingtone(context: Context, id: String): Boolean {
        val currentList = getCustomRingtones(context).toMutableList()
        val itemToRemove = currentList.firstOrNull { it.id == id } ?: return false

        val file = getCustomAudioFile(context, itemToRemove)
        if (file.exists()) {
            file.delete()
        }

        currentList.removeAll { it.id == id }
        saveCustomRingtones(context, currentList)

        // 若当前默认铃声是被删除的铃声，则重置回 null (使用系统默认)
        val defaultRingtone = AlarmScheduler.getDefaultRingtoneUri(context)
        if (defaultRingtone == itemToRemove.uri) {
            AlarmScheduler.setDefaultRingtoneUri(context, null)
        }
        return true
    }

    /**
     * 根据 URI 获取铃声标题。
     */
    fun getRingtoneTitle(context: Context, uriString: String?): String {
        if (uriString.isNullOrBlank()) {
            return DEFAULT_SYSTEM_ALARM_TITLE
        }

        val defaultAlarmUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)?.toString()
        if (uriString == defaultAlarmUri || uriString == DEFAULT_SYSTEM_ALARM_ID) {
            return DEFAULT_SYSTEM_ALARM_TITLE
        }

        // 先检查自定义铃声
        val customList = getCustomRingtones(context)
        val customMatch = customList.firstOrNull { it.uri == uriString || it.id == uriString }
        if (customMatch != null) {
            return customMatch.title
        }

        // 再检查系统铃声
        val systemList = getSystemRingtones(context)
        val systemMatch = systemList.firstOrNull { it.uri == uriString || it.id == uriString }
        if (systemMatch != null) {
            return systemMatch.title
        }

        // 尝试用系统 Ringtone 标题解析
        try {
            val ringtone = RingtoneManager.getRingtone(context, Uri.parse(uriString))
            val title = ringtone?.getTitle(context)
            if (!title.isNullOrBlank()) {
                return title
            }
        } catch (_: Exception) {}

        return "自定义音频"
    }

    fun getCustomAudioFile(context: Context, item: RingtoneItem): File {
        val fileName = if (item.id.startsWith("custom_")) {
            item.id.removePrefix("custom_")
        } else {
            Uri.parse(item.uri).lastPathSegment?.substringAfterLast('/')?.substringAfterLast('\\') ?: item.id
        }
        val dirFile = File(getCustomRingtonesDir(context), fileName)
        if (dirFile.exists()) return dirFile

        val uriPath = Uri.parse(item.uri).path
        if (uriPath != null) {
            val cleanPath = if (uriPath.length >= 3 && uriPath.startsWith("/") && uriPath[2] == ':') {
                uriPath.substring(1)
            } else {
                uriPath
            }
            val f = File(cleanPath)
            if (f.exists()) return f
        }
        return dirFile
    }

    private fun getCustomRingtonesDir(context: Context): File {
        return File(context.filesDir, CUSTOM_RINGTONES_DIR)
    }

    private fun saveCustomRingtones(context: Context, list: List<RingtoneItem>) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val array = JSONArray()
        for (item in list) {
            val obj = JSONObject().apply {
                put("id", item.id)
                put("title", item.title)
                put("uri", item.uri)
            }
            array.put(obj)
        }
        prefs.edit().putString(KEY_CUSTOM_RINGTONES_JSON, array.toString()).apply()
    }

    private fun queryFileName(context: Context, uri: Uri): String? {
        if (uri.scheme == "content") {
            try {
                context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (nameIndex >= 0) {
                            val name = cursor.getString(nameIndex)
                            if (!name.isNullOrBlank()) {
                                return name.substringAfterLast('/').substringAfterLast('\\')
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to query display name for $uri", e)
            }
        }
        val rawSegment = uri.lastPathSegment ?: uri.path
        return rawSegment?.substringAfterLast('/')?.substringAfterLast('\\')
    }

    private fun sanitizeFileName(name: String): String {
        return name.replace(Regex("[^a-zA-Z0-9._\\-\\u4e00-\\u9fa5]"), "_")
    }

    private fun getUniqueFile(dir: File, baseName: String): File {
        var file = File(dir, baseName)
        if (!file.exists()) return file

        val dotIndex = baseName.lastIndexOf('.')
        val prefix = if (dotIndex > 0) baseName.substring(0, dotIndex) else baseName
        val ext = if (dotIndex > 0) baseName.substring(dotIndex) else ""

        var counter = 1
        while (file.exists()) {
            file = File(dir, "${prefix}_$counter$ext")
            counter++
        }
        return file
    }
}
