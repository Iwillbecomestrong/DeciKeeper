/**
 * =================================================================================
 * [Context Header]
 * * @file: ProbationManager.kt
 * * @role: Domain Skill
 * * @description: 旧延迟基准数据兼容清理。
 * * @responsibilities:
 * 1. 清理旧版本遗留的延迟基准状态。
 * 2. 保持旧调用点兼容。
 * * @context:
 * - 上游: TimeRuleManager, AppWindowManager
 * - 下游: SharedPreferences
 * * @constraints (必读):
 * 1. 不引入 UI 依赖。
 * 2. 时间计算需稳定一致。
 * 3. 空值安全。
 * =================================================================================
 */
package com.decikeeper.app.manager

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.decikeeper.app.service.MonitorService
import org.json.JSONArray
import org.json.JSONObject


/**
 * Legacy compatibility for the removed delayed-baseline window.
 * Favorable changes now update their baseline at the save site immediately.
 */
object ProbationManager {

    private const val PREFS_PROBATION = "decikeeper_probation"
    private const val KEY_ITEMS = "probation_items"

    data class ProbationItem(
        val key: String,           // Preference Key (e.g., "cool_down_rest_seconds")
        val currentValue: Any,     // New Value (Currently Active)
        val originalValue: Any,    // Baseline Value (To revert to)
        val type: String,          // "int", "long", "string", "boolean"
        val timestamp: Long        // When the change happened
    )

    /**
     * Favorable changes no longer enter a delayed-baseline window.
     * Remove matching legacy entries so old UI state cannot linger.
     */
    fun addProbationaryChange(context: Context, key: String, currentValue: Any, originalValue: Any?) {
        val items = getItems(context).toMutableList()
        if (items.removeAll { it.key == key }) {
            saveItems(context, items)
            Log.i("ProbationManager", "Removed legacy probation for $key; favorable changes are immediate baseline.")
        }
    }

    /**
     * Delayed baseline promotion is removed. Clear legacy entries without changing Active state.
     */
    fun checkAndPromote(context: Context): List<String> {
        if (getItems(context).isNotEmpty()) {
            context.getSharedPreferences(PREFS_PROBATION, Context.MODE_PRIVATE)
                .edit().remove(KEY_ITEMS).apply()
            Log.i("ProbationManager", "Cleared legacy probation entries.")
        }
        return emptyList()
    }

    /**
     * 撤销修改 (Undo) -> 回滚 Active Prefs 到 Original Value
     */
    fun undoChange(context: Context, key: String): Boolean {
        val items = getItems(context).toMutableList()
        val item = items.find { it.key == key } ?: return false

        // 1. Revert Active Prefs
        // Determine which file to write to. Global params are in PREFS_NAME ("decikeeper_rules") or ledger?
        // Most global params are in "decikeeper_rules" (managed by TimeRuleManager)
        // EXCEPT: strict_penalty_duration, config_refresh_time are in "decikeeper_ledger" (CreditManager)
        // We need to know where the Item belongs.
        // Heuristic: Check keys.
        val mainPrefs = context.getSharedPreferences("decikeeper_rules", Context.MODE_PRIVATE)
        val ledgerPrefs = context.getSharedPreferences("decikeeper_ledger", Context.MODE_PRIVATE)
        val privacyPrefs = context.getSharedPreferences(PrivacyProtectionManager.PREFS_NAME, Context.MODE_PRIVATE)
        
        var targetEditor: SharedPreferences.Editor? = null

        if (key in listOf("strict_penalty_duration", "config_refresh_time", "weekly_privilege_limit")) {
            targetEditor = ledgerPrefs.edit()
        } else if (key == PrivacyProtectionManager.KEY_IS_ENABLED) {
            targetEditor = privacyPrefs.edit()
        } else {
            targetEditor = mainPrefs.edit()
        }

        when (item.type) {
            "int" -> targetEditor.putInt(key, item.originalValue as Int)
            "long" -> targetEditor.putLong(key, item.originalValue as Long)
            "string" -> targetEditor.putString(key, item.originalValue as String)
            "boolean" -> targetEditor.putBoolean(key, item.originalValue as Boolean)
        }
        targetEditor.apply()

        // 2. Remove from Probation (It is no longer a "New Value waiting for promotion")
        // It's effectively "Restored to Baseline".
        items.remove(item)
        saveItems(context, items)
        
        Log.i("ProbationManager", "Undo Change: $key reverted to ${item.originalValue}")
        
        // 3. Trigger Service Refresh
        val intent = android.content.Intent(context, MonitorService::class.java)
        intent.action = MonitorService.ACTION_REFRESH_RULES
        context.startService(intent)
        
        return true
    }
    
    fun getItems(context: Context): List<ProbationItem> {
        val json = context.getSharedPreferences(PREFS_PROBATION, Context.MODE_PRIVATE).getString(KEY_ITEMS, "[]")
        return try {
            val array = JSONArray(json)
            val list = mutableListOf<ProbationItem>()
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val type = obj.getString("type")
                val key = obj.getString("key")
                val ts = obj.getLong("timestamp")
                
                val current = getValue(obj, "currentValue", type)
                val original = getValue(obj, "originalValue", type)
                
                if (current != null && original != null) {
                    list.add(ProbationItem(key, current, original, type, ts))
                }
            }
            list
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun getValue(obj: JSONObject, field: String, type: String): Any? {
        return try {
            if (obj.isNull(field)) return null
            when (type) {
                "int" -> obj.getInt(field)
                "long" -> obj.getLong(field)
                "string" -> obj.getString(field)
                "boolean" -> obj.getBoolean(field)
                else -> obj.getString(field)
            }
        } catch (e: Exception) { null }
    }

    private fun saveItems(context: Context, items: List<ProbationItem>) {
        val array = JSONArray()
        items.forEach { item ->
            val obj = JSONObject()
            obj.put("key", item.key)
            obj.put("type", item.type)
            obj.put("timestamp", item.timestamp)
            obj.put("currentValue", item.currentValue)
            obj.put("originalValue", item.originalValue)
            array.put(obj)
        }
        context.getSharedPreferences(PREFS_PROBATION, Context.MODE_PRIVATE)
            .edit().putString(KEY_ITEMS, array.toString()).apply()
    }
    
    // 基础信息获取，用于UI展示
    fun getFormattedExpiry(timestamp: Long): String {
        return "已立即生效"
    }
}
