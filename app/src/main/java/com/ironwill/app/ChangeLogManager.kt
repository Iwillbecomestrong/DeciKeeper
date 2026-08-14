/**
 * ChangeLogManager.kt
 * 管理改动记录的增删查改，支持自动隐藏逻辑：
 * - 不利改动：生效后隐藏
 * - 有利改动：24小时后隐藏
 */
/**
 * =================================================================================
 * [Context Header]
 * * @file: ChangeLogManager.kt
 * * @role: Domain Skill
 * * @description: 规则变更记录的管理与持久化。
 * * @responsibilities:
 * 1. 记录并查询规则变更日志。
 * 2. 支持 UI 展示的摘要生成。
 * * @context:
 * - 上游: TimeRuleManager, AppWindowManager
 * - 下游: SharedPreferences
 * * @constraints (必读):
 * 1. 不引入 UI 依赖。
 * 2. 保持读写一致性与空值安全。
 * 3. 按需求文档输出可读描述。
 * =================================================================================
 */
package com.ironwill.app

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.util.Date

object ChangeLogManager {

    private const val PREFS_NAME = "iron_will_changelog"
    private const val KEY_ENTRIES = "changelog_entries"
    private const val HIDE_FAVORABLE_AFTER_MS = 24 * 60 * 60 * 1000L // 24 hours
    private const val WEEKLY_PRIVILEGE_TAG_PREFIX = "weekly_privilege"
    
    private fun isWeeklyPrivilegeTag(tag: String?): Boolean {
        return tag?.startsWith(WEEKLY_PRIVILEGE_TAG_PREFIX) == true
    }

    data class ChangeLogEntry(
        val id: String,           // Unique ID (timestamp + random)
        val category: String,     // "global_params", "segments", "blacklist"
        val description: String,  // Human-readable description
        val timestamp: Long,      // When the change was made
        val isAdverse: Boolean,   // True = adverse (delayed), False = favorable (immediate)
        var isApplied: Boolean,   // For adverse: set to true when applied
        val tag: String? = null   // 🚀 [New] Deduplication Tag (e.g. "param_weekly_limit")
    )
    
    /**
     * Add a new change log entry
     */
    fun addEntry(context: Context, category: String, description: String, isAdverse: Boolean, tag: String? = null) {
        val entries = getAllEntries(context).toMutableList()
        
        // 🚀 [Fix] Deduplication: If tag exists, remove OLD PENDING entries with same tag
        if (tag != null) {
            entries.removeAll { it.category == category && it.tag == tag && !it.isApplied }
        }
        
        val entry = ChangeLogEntry(
            id = "${System.currentTimeMillis()}_${(Math.random() * 1000).toInt()}",
            category = category,
            description = description,
            timestamp = System.currentTimeMillis(),
            isAdverse = isAdverse,
            isApplied = false,
            tag = tag
        )
        
        entries.add(entry)
        saveEntries(context, entries)
    }
    
    /**
     * Add multiple entries at once
     * List<Triple<Description, IsAdverse, Tag?>>
     */
    fun addEntries(context: Context, category: String, items: List<Triple<String, Boolean, String?>>) {
        val entries = getAllEntries(context).toMutableList()
        
        items.forEach { (desc, isAdverse, tag) ->
            // 🚀 [Fix] Deduplication
            if (tag != null) {
                entries.removeAll { it.category == category && it.tag == tag && !it.isApplied }
            }
        
            entries.add(ChangeLogEntry(
                id = "${System.currentTimeMillis()}_${(Math.random() * 1000).toInt()}",
                category = category,
                description = desc,
                timestamp = System.currentTimeMillis(),
                isAdverse = isAdverse,
                isApplied = false,
                tag = tag
            ))
        }
        
        saveEntries(context, entries)
    }
    
    /**
     * Get entries that should be visible according to auto-hide rules
     * - Adverse changes: Hide only when isApplied == true (after taking effect)
     * - Favorable changes: Hide after 24 hours
     */
    /**
     * Get entries that should be visible according to Delayed Effect Table rules
     * - Adverse changes: Show ONLY when isApplied == false (Pending)
     * - Favorable changes: Do NOT show (Immediate effect)
     */
    fun getVisibleEntries(context: Context): List<ChangeLogEntry> {
        val now = System.currentTimeMillis()
        return getAllEntries(context).filter { entry ->
            if (entry.isAdverse) {
                // Adverse: Show only if NOT applied (Pending)
                !entry.isApplied
            } else {
                // Favorable: Immediate effect -> Do not show in "Delayed Effect Table"
                false
            }
        }
    }
    
    /**
     * Get visible entries for a specific category
     */
    fun getVisibleEntriesForCategory(context: Context, category: String): List<ChangeLogEntry> {
        return getVisibleEntries(context).filter { it.category == category }
    }

    /**
     * Get all entries for a specific tag (e.g. specific rule ID)
     * Used for history checking (Fresh vs Mature rules)
     */
    fun getEntriesForTag(context: Context, tag: String): List<ChangeLogEntry> {
        return getAllEntries(context).filter { it.tag == tag }
    }

    
    /**
     * Mark all adverse entries in a category as applied (when pending changes take effect)
     */
    fun markCategoryAsApplied(context: Context, category: String) {
        val entries = getAllEntries(context).map { entry ->
            if (entry.category == category && entry.isAdverse) {
                entry.copy(isApplied = true)
            } else {
                entry
            }
        }
        saveEntries(context, entries)
    }

    /**
     * Mark all adverse entries in a category as applied, EXCEPT those with a specific tag.
     * Used to preserve weekly privilege entries when daily pending changes are applied.
     */
    fun markCategoryAsAppliedExcludingTag(context: Context, category: String, excludeTag: String) {
        val entries = getAllEntries(context).map { entry ->
            val shouldExclude = if (excludeTag == WEEKLY_PRIVILEGE_TAG_PREFIX) {
                isWeeklyPrivilegeTag(entry.tag)
            } else {
                entry.tag == excludeTag
            }
            if (entry.category == category && entry.isAdverse && !shouldExclude) {
                entry.copy(isApplied = true)
            } else {
                entry
            }
        }
        saveEntries(context, entries)
    }
    
    /**
     * Remove entries containing specific text (e.g. for deleted segments)
     */
    fun removeEntriesContaining(context: Context, category: String, keyword: String) {
        val entries = getAllEntries(context).filter { 
             !(it.category == category && it.description.contains(keyword))
        }
        saveEntries(context, entries)
    }
    
    /**
     * Clear all entries for a category (e.g., when user cancels all pending changes)
     * 🚀 [Fix] User request: DO NOT CLEAR - ChangeLog is permanent
     */
    fun clearCategory(context: Context, category: String) {
        // Intentionally empty - ChangeLog is permanent
        // val entries = getAllEntries(context).filter { it.category != category }
        // saveEntries(context, entries)
    }

    /**
     * Remove the last added entry for a specific category (Undo Logic)
     */
    fun removeLastEntry(context: Context, category: String) {
        val entries = getAllEntries(context).toMutableList()
        val lastIndex = entries.indexOfLast { it.category == category }
        if (lastIndex != -1) {
            entries.removeAt(lastIndex)
            saveEntries(context, entries)
        }
    }
    
    /**
     * Remove specific pending entries by tag (e.g. when restoring a config)
     */
    fun removePendingEntriesByTag(context: Context, category: String, tag: String) {
        val entries = getAllEntries(context).filter { 
             !(it.category == category && it.tag == tag && !it.isApplied)
        }
        saveEntries(context, entries)
    }

    /**
     * Clear all pending (unapplied) entries for a category
     */
    fun clearPendingLogsForCategory(context: Context, category: String) {
        val entries = getAllEntries(context).filter {
            !(it.category == category && !it.isApplied)
        }
        saveEntries(context, entries)
    }
    
    // Alias for consistency
    fun removePendingEntriesByCategory(context: Context, category: String) {
        clearPendingLogsForCategory(context, category)
    }
    
    /**
     * Clear old entries (maintenance - call periodically)
     * 🚀 [Fix] 7 days retention, but keep all adverse entries until applied
     */
    fun clearOldEntries(context: Context) {
        val cutoff = System.currentTimeMillis() - (7 * 24 * 60 * 60 * 1000L) // 7 days
        val entries = getAllEntries(context).filter { entry ->
            entry.timestamp > cutoff || (entry.isAdverse && !entry.isApplied)
        }
        saveEntries(context, entries)
    }
    
    /**
     * Get formatted string for display in UI
     * 🚀 [Fix] Group by date and show all entries
     */
    fun getFormattedLogForCategory(context: Context, category: String): String {
        val entries = getVisibleEntriesForCategory(context, category)
        val sb = StringBuilder()
        val dateFormat = java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault())
        
        // Group by adverse/favorable first, then by date
        val adverse = entries.filter { it.isAdverse }.sortedByDescending { it.timestamp }
        val favorable = entries.filter { !it.isAdverse }.sortedByDescending { it.timestamp }
        
        if (favorable.isNotEmpty()) {
            sb.append("✅ 已生效 (有利改动):\n")
            favorable.forEach { 
                val time = dateFormat.format(java.util.Date(it.timestamp))
                sb.append("• [$time] ${it.description}\n") 
            }
        }
        
        if (adverse.isNotEmpty()) {
            if (sb.isNotEmpty()) sb.append("\n")
            
            // 🚀 [Feature] Split Standard vs Weekly Privilege (Different Effective Times)
            val weeklyParams = adverse.filter { isWeeklyPrivilegeTag(it.tag) }
            val standardParams = adverse.filter { !isWeeklyPrivilegeTag(it.tag) }
            
            // 🚀 [Fix] Fetch stored effective_timestamp based on category
            var storedEffTime = 0L
            if (category == "app_window_rules") {
                val appWindowPending = context.getSharedPreferences("app_window_pending", Context.MODE_PRIVATE)
                storedEffTime = appWindowPending.getLong("effective_timestamp", 0L)
            } else {
                 val pendingPrefs = context.getSharedPreferences("iron_will_rules_pending", Context.MODE_PRIVATE)
                 storedEffTime = pendingPrefs.getLong("effective_timestamp", 0L)
            }
            
            if (standardParams.isNotEmpty()) {
                standardParams.forEach { 
                    sb.append("• ${it.description}\n") 
                }
                // Add Standard Effective Time
                val effTime = if (storedEffTime > 0) storedEffTime else ConfigPendingManager.calculateNextEffectiveTime(context)
                val effTimeStr = dateFormat.format(Date(effTime))
                sb.append("📅 生效时间: $effTimeStr\n")
            }
            
            if (weeklyParams.isNotEmpty()) {
                if (standardParams.isNotEmpty()) sb.append("\n") // Spacer
                
                weeklyParams.forEach { 
                    sb.append("• ${it.description}\n") 
                }
                // Add Weekly Effective Time
                // 🚀 [Fix] Always use Calculated Next Monday for Weekly Privilege.
                // Do NOT use storedEffTime because it might track Daily Changes (Tomorrow), causing confusion.
                // Weekly Limit always applies on Next Monday regardless of other pending changes.
                val effTime = ConfigPendingManager.calculateNextMondayEffectiveTime(context, System.currentTimeMillis())
                val effTimeStr = dateFormat.format(Date(effTime))
                sb.append("📅 生效时间: $effTimeStr\n")
            }
        }
        
        return sb.toString().trim()
    }
    
    // ==========================================
    // 🚀 [Fix] State Synchronization (Zombie Cleanup)
    // ==========================================
    fun syncLogStatusWithPendingState(context: Context) {
        // 1. Check Global Params & Segments
        val pendingRules = context.getSharedPreferences("iron_will_rules_pending", Context.MODE_PRIVATE)
        val hasPendingRules = pendingRules.getBoolean("has_pending_changes", false)
        
        if (!hasPendingRules) {
             // If no pending rules exist, then ANY "Pending" logs for these categories are Zombies (already applied but not marked)
             // 🚀 [Fix] Special handling for 'global_params': distinct cleanup for Standard vs Weekly
             // Standard Rule: "global_params" && tag != "weekly_privilege" -> Mark Applied
             // Weekly Rule: "global_params" && tag == "weekly_privilege" -> Ignore (Handled by step 3)
             
             val entries = getAllEntries(context).map { entry ->
                if (entry.category == "global_params" && entry.isAdverse && !entry.isApplied) {
                    if (isWeeklyPrivilegeTag(entry.tag)) {
                        // Keep it pending (It waits for next week)
                        entry 
                    } else {
                        // Mark standard daily params as applied
                        entry.copy(isApplied = true)
                    }
                } else if (entry.category == "segments" && entry.isAdverse && !entry.isApplied) {
                     // specific segment logic if needed, currently just apply all segments
                     entry.copy(isApplied = true)
                } else {
                    entry
                }
             }
             saveEntries(context, entries)
        }
        
        // 2. Check Blacklist Apps
        val pendingApps = context.getSharedPreferences("iron_will_pending_apps", Context.MODE_PRIVATE)
        val hasPendingApps = pendingApps.getBoolean("has_pending_apps", false)
        
        if (!hasPendingApps) {
             markCategoryAsApplied(context, "blacklist")
        }

        // 3. Check App Window Rules (New)
        val pendingWindows = context.getSharedPreferences("app_window_pending", Context.MODE_PRIVATE)
        val hasPendingWindows = pendingWindows.getBoolean("has_pending_changes", false)
        
        if (!hasPendingWindows) {
             markCategoryAsApplied(context, "app_window_rules")
        }
        
        // 4. Weekly Privilege
        // Weekly privilege is a bit special as it lives in main prefs but has a specific pending key
        val hasPendingWeekly = CreditManager.hasPendingWeeklyPrivilegeLimit(context)
        
        if (!hasPendingWeekly) {
             // Mark all "weekly_privilege" tagged entries as applied
             // We need to implement filtering by tag or just check "global_params" with description?
             // Since "weekly_privilege" is logged under "global_params" with a TAG.
             val entries = getAllEntries(context).map { entry ->
                 if (isWeeklyPrivilegeTag(entry.tag) && entry.isAdverse && !entry.isApplied) {
                     entry.copy(isApplied = true)
                 } else {
                     entry
                 }
             }
             saveEntries(context, entries)
        }
    }
    
    // 🚀 [Fix] Rescue Stuck Rules (e.g. if initialization skipped them)
    fun applyStuckPendingRules(context: Context) {
        // 1. Weekly Privilege
        // Check if we have any "weekly_privilege" entries that are NOT applied, but Effective Time has passed.
        val entries = getAllEntries(context)
        val stuckWeekly = entries.find { isWeeklyPrivilegeTag(it.tag) && it.isAdverse && !it.isApplied }
        
        if (stuckWeekly != null) {
            // Calculate effective time logic (Next Monday relative to timestamp)
            val timestamp = stuckWeekly.timestamp
            val nextMonday = ConfigPendingManager.calculateNextMondayEffectiveTime(context, timestamp)
            val now = System.currentTimeMillis()
            
            // If overdue (> effective time) AND pending key exists
            if (now > nextMonday && CreditManager.hasPendingWeeklyPrivilegeLimit(context)) {
                android.util.Log.w("ChangeLogManager", "⚠️ Found Stuck Weekly Rule! Force Applying...")
                CreditManager.forceApplyPendingWeeklyLimit(context)
                
                // Mark log as applied
                val updatedEntries = entries.map { 
                     if (isWeeklyPrivilegeTag(it.tag) && !it.isApplied) it.copy(isApplied = true) else it 
                }
                saveEntries(context, updatedEntries)
            }
        }
    }

    // ==========================================
    // Internal Helpers
    // ==========================================
    
    private fun getAllEntries(context: Context): List<ChangeLogEntry> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val json = prefs.getString(KEY_ENTRIES, null) ?: return emptyList()

        return try {
            val array = JSONArray(json)
            val entries = mutableListOf<ChangeLogEntry>()
            for (i in 0 until array.length()) {
                try {
                    val obj = array.getJSONObject(i)
                    entries.add(ChangeLogEntry(
                        id = obj.optString("id", ""),
                        category = obj.optString("category", ""),
                        description = obj.optString("description", ""),
                        timestamp = obj.optLong("timestamp", 0L),
                        isAdverse = obj.optBoolean("isAdverse", false),
                        isApplied = obj.optBoolean("isApplied", false),
                        tag = obj.optString("tag", "").let { if (it.isBlank() || it == "null") null else it }
                    ))
                } catch (e: Exception) {
                    Log.w("ChangeLogManager", "Skipping malformed entry at index $i: ${e.message}")
                }
            }
            entries
        } catch (e: Exception) {
            Log.e("ChangeLogManager", "Failed to parse change log JSON", e)
            emptyList()
        }
    }
    
    private fun saveEntries(context: Context, entries: List<ChangeLogEntry>) {
        val array = JSONArray()
        entries.forEach { entry ->
            val obj = JSONObject().apply {
                put("id", entry.id)
                put("category", entry.category)
                put("description", entry.description)
                put("timestamp", entry.timestamp)
                put("isAdverse", entry.isAdverse)
                put("isApplied", entry.isApplied)
                put("tag", entry.tag)
            }
            array.put(obj)
        }
        
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putString(KEY_ENTRIES, array.toString())
            .apply()
    }
}
