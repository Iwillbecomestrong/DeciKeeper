package com.zhizhi.app.manager

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/** Direct Boot 阶段读取的闹钟最小调度状态；完整 AlarmEntry 仍在凭据加密存储。 */
object AlarmBootstrapStorage {

    private const val PREFS_NAME = "alarm_bootstrap_prefs"
    private const val KEY_ENTRIES_JSON = "entries_json"

    /** Test seam for simulating a synchronous DPS write failure. */
    internal var commitResultForTest: (() -> Boolean)? = null

    /** Test seam for simulating a rejected batch removal containing a specific alarm. */
    internal var batchRemovalFailureForTest: ((Set<Int>) -> Boolean)? = null

    data class Entry(
        val alarmId: Int,
        val nextTriggerAtMillis: Long,
        val enabled: Boolean,
        val scheduleToken: String,
        val hour: Int = -1,
        val minute: Int = -1,
        val daysMask: Int = 0
    )

    fun read(context: Context): List<Entry> {
        val raw = prefs(context).getString(KEY_ENTRIES_JSON, "[]") ?: "[]"
        return try {
            val json = JSONArray(raw)
            buildList {
                for (index in 0 until json.length()) {
                    val item = json.optJSONObject(index) ?: continue
                    val alarmId = item.optInt("alarmId", -1)
                    val triggerAt = item.optLong("nextTriggerAtMillis", 0L)
                    val token = item.optString("scheduleToken", "")
                    if (alarmId > 0 && triggerAt > 0L && token.isNotBlank()) {
                        add(
                            Entry(
                                alarmId,
                                triggerAt,
                                item.optBoolean("enabled", false),
                                token,
                                item.optInt("hour", -1),
                                item.optInt("minute", -1),
                                item.optInt("daysMask", 0)
                            )
                        )
                    }
                }
            }
        } catch (e: Exception) {
            Log.w("AlarmBootstrapStorage", "Failed to read bootstrap state: ${e.message}")
            emptyList()
        }
    }

    fun find(context: Context, alarmId: Int): Entry? = read(context).firstOrNull { it.alarmId == alarmId }

    fun replace(context: Context, entries: List<Entry>): Boolean {
        val json = JSONArray()
        entries.distinctBy { it.alarmId }
            .filter { it.alarmId > 0 && it.nextTriggerAtMillis > 0L && it.scheduleToken.isNotBlank() }
            .forEach { entry ->
                json.put(
                    JSONObject()
                        .put("alarmId", entry.alarmId)
                        .put("nextTriggerAtMillis", entry.nextTriggerAtMillis)
                        .put("enabled", entry.enabled)
                        .put("scheduleToken", entry.scheduleToken)
                        .put("hour", entry.hour)
                        .put("minute", entry.minute)
                        .put("daysMask", entry.daysMask)
                )
            }
        val committed = commitResultForTest?.invoke()
            ?: prefs(context).edit().putString(KEY_ENTRIES_JSON, json.toString()).commit()
        if (!committed) {
            Log.w("AlarmBootstrapStorage", "Failed to commit device-protected bootstrap state")
        }
        return committed
    }

    fun upsert(context: Context, entry: Entry): Boolean =
        replace(context, read(context).filterNot { it.alarmId == entry.alarmId } + entry)

    fun remove(context: Context, alarmId: Int): Boolean =
        replace(context, read(context).filterNot { it.alarmId == alarmId })

    /** Atomically removes every requested bootstrap entry with one DPS commit. */
    fun removeAll(context: Context, alarmIds: Set<Int>): Boolean {
        if (alarmIds.isEmpty()) return true
        if (batchRemovalFailureForTest?.invoke(alarmIds) == true) {
            Log.w("AlarmBootstrapStorage", "Rejected batch bootstrap removal for test")
            return false
        }
        return replace(context, read(context).filterNot { it.alarmId in alarmIds })
    }

    private fun prefs(context: Context) = context.createDeviceProtectedStorageContext()
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
