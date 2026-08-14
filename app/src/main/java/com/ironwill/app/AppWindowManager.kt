/**
 * =================================================================================
 * [Context Header]
 * * @file: AppWindowManager.kt
 * * @role: Domain Skill
 * * @description: 管理应用时间窗规则的存储、判定与变更流程。
 * * @responsibilities:
 * 1. 读取/写入应用时间窗规则与待生效规则。
 * 2. 提供时间窗允许性判定与展示摘要。
 * * @context:
 * - 上游: MonitorService, LockWindowManager, AppWindowFragment
 * - 下游: SharedPreferences, ChangeLogManager
 * * @constraints (必读):
 * 1. 禁止引入 android.view 相关 UI 依赖。
 * 2. 判定需处理跨天与起止相同(全天)场景。
 * 3. 必须保证空列表与空白天数的安全默认值。
 * =================================================================================
 */
package com.ironwill.app

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Manages "App Time Windows" (Precision Intercept).
 * Rule: App is allowed ONLY if (CurrentTime in Window) AND (State is Rest OR Gap).
 */
object AppWindowManager {
    private const val TAG = "AppWindowManager"
    private const val PREFS_NAME = "app_window_rules"
    private const val KEY_RULES_JSON = "rules_json"
    private const val CATEGORY_LOG = "app_window_rules"
    
    data class AppWindowRule(
        val id: String = UUID.randomUUID().toString(),
        var name: String,
        var startTime: Int, // HH * 60 + MM
        var endTime: Int,
        var packageNames: MutableSet<String> = mutableSetOf(),
        var isEnabled: Boolean = true,
        var daysOfWeek: Set<Int> = (1..7).toSet()
        // 🚀 [Refactor] Removed 'lastModified'. Baseline Evolution uses Global Timer.
    ) {
        fun getSummary(): String {
            val start = String.format("%02d:%02d", startTime / 60, startTime % 60)
            val end = String.format("%02d:%02d", endTime / 60, endTime % 60)
            return "$start - $end"
        }
    }

    private const val PREFS_PENDING = "app_window_pending"
    private const val KEY_PENDING_RULES_JSON = "pending_rules_json"
    private const val KEY_PARTIAL_DELAY_JSON = "partial_delay_json"
    
    // 🚀 [NEW] Baseline Mechanism
    private const val PREFS_BASELINE = "app_window_baseline"
    private const val KEY_BASELINE_RULES_JSON = "baseline_rules_json"

    // Legacy promotion prefs are kept only to absorb old pre-update state.
    private const val PREFS_PROMOTION = "app_window_promotion"
    private const val KEY_LAST_FAVORABLE_TS = "last_favorable_ts"

    private var rulesCache: MutableList<AppWindowRule> = mutableListOf()
    private var isCacheLoaded = false

    // 🚀 [Undo] Undo Stack (Max 15)
    private val undoStack = java.util.LinkedList<String>() // Stores JSON strings of Pending Rules
    private const val MAX_UNDO_STEPS = 15

    // --- Serialization Helpers ---
    private fun ruleToJson(rule: AppWindowRule): String {
        val obj = JSONObject()
        obj.put("id", rule.id)
        obj.put("name", rule.name)
        obj.put("startTime", rule.startTime)
        obj.put("endTime", rule.endTime)
        
        val pkgs = JSONArray()
        rule.packageNames.forEach { pkgs.put(it) }
        obj.put("packageNames", pkgs)
        
        obj.put("isEnabled", rule.isEnabled)
        
        val days = JSONArray()
        rule.daysOfWeek.forEach { days.put(it) }
        obj.put("daysOfWeek", days)
        
        return obj.toString()
    }

    private fun ruleFromJson(json: String): AppWindowRule? {
        try {
            val obj = JSONObject(json)
            val id = obj.optString("id")
            if (id.isEmpty()) return null
            
            val rule = AppWindowRule(
                id = id,
                name = obj.optString("name", "Unnamed"),
                startTime = obj.optInt("startTime", 0),
                endTime = obj.optInt("endTime", 0),
                isEnabled = obj.optBoolean("isEnabled", true)
            )
            
            val pkgs = obj.optJSONArray("packageNames")
            if (pkgs != null) {
                for (i in 0 until pkgs.length()) {
                    rule.packageNames.add(pkgs.getString(i))
                }
            }
            
            val days = obj.optJSONArray("daysOfWeek")
            val daySet = mutableSetOf<Int>()
            if (days != null) {
                for (i in 0 until days.length()) {
                    daySet.add(days.getInt(i))
                }
            } else {
                daySet.addAll(1..7)
            }
            rule.daysOfWeek = daySet
            
            return rule
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing rule JSON", e)
            return null
        }
    }

    private fun rulesToJson(list: List<AppWindowRule>): JSONArray {
        val array = JSONArray()
        list.forEach { array.put(JSONObject(ruleToJson(it))) }
        return array
    }

    private fun parseRulesFromJson(json: String): MutableList<AppWindowRule> {
        val list = mutableListOf<AppWindowRule>()
        try {
            val array = JSONArray(json)
            for (i in 0 until array.length()) {
                val rule = ruleFromJson(array.getJSONObject(i).toString())
                if (rule != null) list.add(rule)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing rules list", e)
        }
        return list
    }

    private fun ensureLoaded(context: Context) {
        if (isCacheLoaded) return
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val json = prefs.getString(KEY_RULES_JSON, "[]")
        rulesCache = parseRulesFromJson(json ?: "[]")
        isCacheLoaded = true
    }

    private fun saveToPrefs(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_RULES_JSON, rulesToJson(rulesCache).toString()).apply()
    }


    // Promote any legacy favorable Active state immediately.
    fun promoteMatureBaseline(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_PROMOTION, Context.MODE_PRIVATE)
        val ts = prefs.getLong(KEY_LAST_FAVORABLE_TS, 0L)

        if (ts > 0) {
            Log.i(TAG, "Cleared legacy favorable baseline promotion marker.")
            prefs.edit().remove(KEY_LAST_FAVORABLE_TS).apply()
        }
    }

    private fun updateBaselineAfterImmediateChange(context: Context) {
        // Only clear legacy promotion marker, do NOT write baseline.
        // Baseline only refreshes cross-business-day via ensureFreshBaseline.
        context.getSharedPreferences(PREFS_PROMOTION, Context.MODE_PRIVATE)
            .edit().remove(KEY_LAST_FAVORABLE_TS).apply()
    }

    private fun clearLegacyPromotionMarker(context: Context) {
        context.getSharedPreferences(PREFS_PROMOTION, Context.MODE_PRIVATE)
            .edit().remove(KEY_LAST_FAVORABLE_TS).apply()
    }

    private fun pushUndoSnapshot(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_PENDING, Context.MODE_PRIVATE)
        val json = prefs.getString(KEY_PENDING_RULES_JSON, "[]")
        
        if (undoStack.size >= MAX_UNDO_STEPS) {
            undoStack.removeFirst()
        }
        undoStack.addLast(json ?: "[]")
    }

    fun undoLastAction(context: Context): Boolean {
        if (undoStack.isEmpty()) return false
        
        val lastJson = undoStack.removeLast()
        val prefs = context.getSharedPreferences(PREFS_PENDING, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_PENDING_RULES_JSON, lastJson).apply()
        
        val pending = parseRulesFromJson(lastJson ?: "[]")
        if (isSameAsActive(pending) && !hasPartialDelayedEntries(context)) {
             clearAllPendingChanges(context) 
        } else {
             // Try to remove last log entry
             ChangeLogManager.removeLastEntry(context, CATEGORY_LOG)
        }
        return true
    }

    fun undoRuleChange(context: Context, ruleId: String) {
        ensureLoaded(context)
        val pending = getPendingRules(context) ?: return
        val activeRule = rulesCache.find { it.id == ruleId }
        
        // Push snapshot for global undo
        pushUndoSnapshot(context)

        if (activeRule != null) {
            // Revert to Active (Handles Undo Delete & Undo Modify)
            updateList(pending, pending.find { it.id == ruleId }, activeRule)
        } else {
            // Undo Addition (Remove from pending)
            updateList(pending, pending.find { it.id == ruleId }, null)
        }
        
        if (isSameAsActive(pending) && !hasPartialDelayedEntries(context)) {
            clearAllPendingChanges(context)
        } else {
            saveToPendingPrefs(context, pending)
            markPendingFlag(context) // Update flag timestamp
            // Undo Log
            ChangeLogManager.removePendingEntriesByTag(context, CATEGORY_LOG, ruleId)
        }
    }


    fun getRules(context: Context): MutableList<AppWindowRule> {
        ensureLoaded(context)
        return rulesCache
    }

    fun getPendingRules(context: Context): MutableList<AppWindowRule>? {
        val prefs = context.getSharedPreferences(PREFS_PENDING, Context.MODE_PRIVATE)
        val json = prefs.getString(KEY_PENDING_RULES_JSON, null) ?: return null
        return parseRulesFromJson(json)
    }

    private fun normalizeAppWindowDays(days: Set<Int>): Set<Int> {
        val cleaned = days.filter { it in 1..7 }.toSet()
        return if (cleaned.isEmpty()) (1..7).toSet() else cleaned
    }

    /**
     * 🚀 [Per-App + Per-Day Split]
     * Split one rule into immediate/deferred parts by (package, day).
     * Day index in AppWindow module: 1=周一 ... 7=周日.
     */
    fun splitRuleByAppAndDayForMixedActivation(
        newRule: AppWindowRule,
        existingRules: List<AppWindowRule>
    ): AppDayMixedSplit {
        val targetDays = normalizeAppWindowDays(newRule.daysOfWeek)
        val immediate = mutableMapOf<String, MutableSet<Int>>()
        val delayed = mutableMapOf<String, MutableSet<Int>>()

        newRule.packageNames.forEach { pkg ->
            targetDays.forEach { day ->
                val hasWindowOnDay = existingRules.any { old ->
                    old.id != newRule.id &&
                        old.isEnabled &&
                        old.packageNames.contains(pkg) &&
                        normalizeAppWindowDays(old.daysOfWeek).contains(day)
                }
                if (hasWindowOnDay) {
                    delayed.getOrPut(pkg) { mutableSetOf() }.add(day)
                } else {
                    immediate.getOrPut(pkg) { mutableSetOf() }.add(day)
                }
            }
        }

        return AppDayMixedSplit(
            immediateByApp = immediate.mapValues { it.value.toSet() },
            delayedByApp = delayed.mapValues { it.value.toSet() }
        )
    }

    /**
     * 🚀 [Deletion Mixed Mode] 删除规则时按 (应用, 日期) 拆分
     * 与 addition 相反：仍有其他窗口 → immediate（无放松），无其他窗口 → delayed（放松需延迟）
     */
    fun splitDeletionByAppAndDay(
        oldRule: AppWindowRule,
        existingRules: List<AppWindowRule>
    ): AppDayMixedSplit {
        val targetDays = normalizeAppWindowDays(oldRule.daysOfWeek)
        val immediate = mutableMapOf<String, MutableSet<Int>>()
        val delayed = mutableMapOf<String, MutableSet<Int>>()

        oldRule.packageNames.forEach { pkg ->
            targetDays.forEach { day ->
                val hasOtherWindow = existingRules.any { other ->
                    other.id != oldRule.id &&
                        other.isEnabled &&
                        other.packageNames.contains(pkg) &&
                        normalizeAppWindowDays(other.daysOfWeek).contains(day)
                }
                if (hasOtherWindow) {
                    // 仍有其他窗口覆盖 → 删除不造成放松 → 立即生效
                    immediate.getOrPut(pkg) { mutableSetOf() }.add(day)
                } else {
                    // 无其他窗口 → 删除后该应用在该日不再受限 → 延迟生效
                    delayed.getOrPut(pkg) { mutableSetOf() }.add(day)
                }
            }
        }

        return AppDayMixedSplit(
            immediateByApp = immediate.mapValues { it.value.toSet() },
            delayedByApp = delayed.mapValues { it.value.toSet() }
        )
    }

    private fun loadPartialDelayMap(context: Context): MutableMap<String, MutableMap<String, MutableSet<Int>>> {
        val prefs = context.getSharedPreferences(PREFS_PENDING, Context.MODE_PRIVATE)
        val json = prefs.getString(KEY_PARTIAL_DELAY_JSON, null) ?: return mutableMapOf()
        val result = mutableMapOf<String, MutableMap<String, MutableSet<Int>>>()

        try {
            val root = JSONObject(json)
            root.keys().forEach { ruleId ->
                val appObj = root.optJSONObject(ruleId) ?: return@forEach
                val appMap = mutableMapOf<String, MutableSet<Int>>()
                appObj.keys().forEach { pkg ->
                    val daysArray = appObj.optJSONArray(pkg) ?: JSONArray()
                    val daySet = mutableSetOf<Int>()
                    for (i in 0 until daysArray.length()) {
                        val day = daysArray.optInt(i, -1)
                        if (day in 1..7) daySet.add(day)
                    }
                    if (daySet.isNotEmpty()) appMap[pkg] = daySet
                }
                if (appMap.isNotEmpty()) result[ruleId] = appMap
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse partial delay map", e)
        }

        return result
    }

    private fun hasPartialDelayedEntries(context: Context): Boolean {
        return loadPartialDelayMap(context).isNotEmpty()
    }

    private fun savePartialDelayMap(context: Context, map: Map<String, Map<String, Set<Int>>>) {
        val root = JSONObject()
        map.forEach { (ruleId, appMap) ->
            val appObj = JSONObject()
            appMap.forEach { (pkg, days) ->
                val arr = JSONArray()
                days.filter { it in 1..7 }.sorted().forEach { arr.put(it) }
                if (arr.length() > 0) appObj.put(pkg, arr)
            }
            if (appObj.length() > 0) root.put(ruleId, appObj)
        }

        context.getSharedPreferences(PREFS_PENDING, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_PARTIAL_DELAY_JSON, if (root.length() == 0) null else root.toString())
            .apply()
    }

    private fun setPartialDelayedDaysForRule(context: Context, ruleId: String, delayedByApp: Map<String, Set<Int>>) {
        val map = loadPartialDelayMap(context)
        val cleaned = delayedByApp
            .mapValues { (_, days) -> days.filter { it in 1..7 }.toSet() }
            .filterValues { it.isNotEmpty() }

        if (cleaned.isEmpty()) {
            map.remove(ruleId)
        } else {
            map[ruleId] = cleaned.mapValues { it.value.toMutableSet() }.toMutableMap()
        }

        savePartialDelayMap(
            context,
            map.mapValues { (_, appMap) -> appMap.mapValues { it.value.toSet() } }
        )
    }

    private fun clearPartialDelayedDaysForRule(context: Context, ruleId: String) {
        val map = loadPartialDelayMap(context)
        if (map.remove(ruleId) != null) {
            savePartialDelayMap(
                context,
                map.mapValues { (_, appMap) -> appMap.mapValues { it.value.toSet() } }
            )
        }
    }

    /**
     * 立即生效分支下，是否应保留“部分延迟生效映射”。
     *
     * 规则：
     * - 仅当该规则在 Pending 中仍有条目时保留；
     * - 删除规则/无 Pending 目标条目时清除映射。
     */
    internal fun shouldRetainPartialDelayMaskAfterImmediateSync(
        targetRuleId: String,
        newRule: AppWindowRule?,
        pendingRules: List<AppWindowRule>?
    ): Boolean {
        if (newRule == null) return false
        if (pendingRules.isNullOrEmpty()) return false
        return pendingRules.any { it.id == targetRuleId }
    }

    private fun isPartiallyDelayedOnDay(context: Context, ruleId: String, packageName: String, day: Int): Boolean {
        if (day !in 1..7) return false
        val map = loadPartialDelayMap(context)
        val delayedDays = map[ruleId]?.get(packageName) ?: return false
        return delayedDays.contains(day)
    }

    private fun isRulePackageDayEffective(
        context: Context,
        rule: AppWindowRule,
        packageName: String,
        day: Int
    ): Boolean {
        if (!rule.isEnabled) return false
        if (!rule.packageNames.contains(packageName)) return false
        if (!normalizeAppWindowDays(rule.daysOfWeek).contains(day)) return false
        return !isPartiallyDelayedOnDay(context, rule.id, packageName, day)
    }

    /**
     * 供预览/UI层调用：按“规则 + 应用 + 日期”判断是否真正生效（已考虑部分延迟生效映射）。
     */
    fun isPackageEffectiveOnDay(
        context: Context,
        rule: AppWindowRule,
        packageName: String,
        day: Int
    ): Boolean {
        return isRulePackageDayEffective(context, rule, packageName, day)
    }

    /**
     * 供预览/UI层调用：按“规则 + 日期”判断是否真正生效。
     * 规则中只要有任一受控应用在该日有效，则该规则在该日应显示。
     */
    fun isRuleEffectiveOnDay(
        context: Context,
        rule: AppWindowRule,
        day: Int
    ): Boolean {
        if (!rule.isEnabled) return false
        return rule.packageNames.any { pkg ->
            isRulePackageDayEffective(context, rule, pkg, day)
        }
    }

    private fun formatAppDayMatrix(context: Context, appDays: Map<String, Set<Int>>): String {
        if (appDays.isEmpty()) return "无"
        return appDays
            .filterValues { it.isNotEmpty() }
            .toList()
            .sortedBy { it.first }
            .joinToString("；") { (pkg, days) ->
                "${getAppName(context, pkg)}(${formatDays(days)})"
            }
    }

    /**
     * 🚀 [Refactor] 按天检查指定应用是否在基准规则中有时间窗
     * 
     * 逻辑说明：
     * - 只检查**基准规则**（当前立即生效基准）
     * - 🚀 [新增] 支持按天判定：如果新规则的生效天与现有规则完全不重叠，视为首个窗口
     * 
     * @param newRuleDays 新规则的生效天数，用于检查是否与现有规则有重叠
     * @return true 如果在新规则生效的任一天，该应用已有其他时间窗
     */
    fun hasOtherTimeWindow(
        context: Context, 
        packageName: String, 
        excludeRuleId: String?,
        newRuleDays: Set<Int>? = null
    ): Boolean {
        ensureLoaded(context)
        // 🚀 [Fix] 使用 Active Rules 进行检查，确保"追加窗口"的判定基于当前生效状态
        // 修正逻辑：如果当前已有窗口（即便是刚加的），再加规则应视为 Adverse
        val rules = getRules(context)
        
        // 获取包含该应用的其他规则
        val matchingRules = rules.filter {
            it.id != excludeRuleId && it.isEnabled && it.packageNames.contains(packageName)
        }
        
        if (matchingRules.isEmpty()) return false
        
        // 如果没有指定新规则的生效天数，回退到旧逻辑（全局检查）
        if (newRuleDays == null || newRuleDays.isEmpty()) {
            return matchingRules.any { rule ->
                normalizeAppWindowDays(rule.daysOfWeek).any { day ->
                    isRulePackageDayEffective(context, rule, packageName, day)
                }
            }
        }
        
        // 🚀 按天判定：检查新规则生效的每一天是否与现有规则的生效天重叠
        for (day in newRuleDays) {
            val hasWindowOnDay = matchingRules.any { rule ->
                isRulePackageDayEffective(context, rule, packageName, day)
            }
            if (hasWindowOnDay) {
                Log.d(TAG, "hasOtherTimeWindow: $packageName has window on day $day")
                return true // 在这一天已有其他窗口
            }
        }
        
        Log.d(TAG, "hasOtherTimeWindow: $packageName no overlap on days $newRuleDays")
        return false // 生效天不重叠，视为首个时间窗
    }

    fun hasPendingChanges(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_PENDING, Context.MODE_PRIVATE).getBoolean("has_pending_changes", false)
    }

    /**
     * 🚀 [Helper] 检查应用是否被任何规则管控（无论当前时间是否在窗口内）
     * 用于 CreditManager.shouldBlockApp 判断是否需要进一步处理
     */
    fun isManaged(context: Context, packageName: String): Boolean {
        ensureLoaded(context)
        return rulesCache.any { it.isEnabled && it.packageNames.contains(packageName) }
    }

    /**
     * 🚀 [Helper] 检查应用当前时间是否在其时间窗内（允许使用）
     * 用于 LockWindowManager 显示正确的锁屏提示
     */
    fun isAppAllowed(context: Context, packageName: String): Boolean {
        ensureLoaded(context)
        val now = java.util.Calendar.getInstance()
        val currentMinute = now.get(java.util.Calendar.HOUR_OF_DAY) * 60 + now.get(java.util.Calendar.MINUTE)
        val dayOfWeek = now.get(java.util.Calendar.DAY_OF_WEEK) // 1=Sunday, 7=Saturday
        
        // Map to our 1-7 = Monday-Sunday format
        val dayIdx = if (dayOfWeek == java.util.Calendar.SUNDAY) 7 else dayOfWeek - 1

        val relevantRules = rulesCache.filter { it.isEnabled && it.packageNames.contains(packageName) }
        if (relevantRules.isEmpty()) return true

        val todayRules = relevantRules.filter { rule ->
            isRulePackageDayEffective(context, rule, packageName, dayIdx)
        }
        // 🚀 [Fix] No window for today -> Full day allowed
        if (todayRules.isEmpty()) return true

        // 🚀 [Fix] 起止相同视为全天开放（防止被误判为仅 1 分钟可用）
        if (todayRules.any { it.startTime == it.endTime }) return true

        // 多个重叠时间窗取并集（OR）：在任一规则的时间窗内即允许
        return todayRules.any { rule ->
            isMinuteInWindow(currentMinute, rule.startTime, rule.endTime)
        }
    }

    /**
     * 🚀 [Helper] 清空规则缓存（规则变更后调用）
     */
    fun invalidateCache() {
        isCacheLoaded = false
        rulesCache.clear()
    }

    fun clearAllPendingChanges(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_PENDING, Context.MODE_PRIVATE)
        prefs.edit().clear().apply()
        ChangeLogManager.clearPendingLogsForCategory(context, CATEGORY_LOG)
    }

    fun recalculatePendingAfterPolicySwitch(
        context: Context,
        policy: EffectiveActivationPolicyManager.Policy
    ) {
        val isAdaptation = CreditManager.isInAdaptationPeriod(context)
        if (isAdaptation) return

        // Absorb any legacy favorable state before reconciling policy.
        promoteMatureBaseline(context)
        ensureLoaded(context)

        val baselineRules = getBaselineRules(context)
        if (baselineRules.isEmpty()) return

        val activeSnapshot = rulesCache.map { it.copy() }
        val hasActiveDelta = !areRuleListsEquivalent(activeSnapshot, baselineRules)

        when (policy) {
            EffectiveActivationPolicyManager.Policy.ALL_NEXT_DAY -> {
                // [Fix: data loss on strategy switch] 检查已有 Pending 数据防止丢失
                val existingPending = getPendingRules(context)
                val hasExistingPending = existingPending != null && !areRuleListsEquivalent(existingPending, baselineRules)
                if (!hasExistingPending && !hasActiveDelta) return

                if (!hasExistingPending) {
                    // Force delayed mode: rollback active to baseline, stage active snapshot as pending.
                    rulesCache = baselineRules.map { it.copy() }.toMutableList()
                    saveToPrefs(context)

                    saveToPendingPrefs(context, activeSnapshot.map { it.copy() }.toMutableList())
                    markPendingFlag(context)
                }
                // else: hasExistingPending → Pending 已存在且 active 已等于 baseline，无需回滚

                ChangeLogManager.removePendingEntriesByCategory(context, CATEGORY_LOG)
                val diffTarget = if (hasExistingPending) existingPending!! else activeSnapshot
                val diffMessages = buildRuleSetDiffMessages(context, baselineRules, diffTarget)
                if (diffMessages.isNotEmpty()) {
                    diffMessages.forEachIndexed { index, (msg, tag) ->
                        ChangeLogManager.addEntry(context, CATEGORY_LOG, msg, true,
                            tag = tag ?: "policy_switch_recalc_$index")
                    }
                } else {
                    ChangeLogManager.addEntry(context, CATEGORY_LOG,
                        "策略切换：检测到当前应用时间窗修改，已转入次日生效。", true,
                        tag = "policy_switch_recalc")
                }
            }

            EffectiveActivationPolicyManager.Policy.FAVORABLE_IMMEDIATE -> {
                val pendingRules = getPendingRules(context) ?: return

                if (areRuleListsEquivalent(pendingRules, activeSnapshot) && !hasPartialDelayedEntries(context)) {
                    clearAllPendingChanges(context)
                    return
                }

                val isPendingAdverse = isRuleSetAdverseAgainstBaseline(context, baselineRules, pendingRules)
                if (isPendingAdverse) return

                // Back to default mode: favorable/restored pending should become immediate.
                val prevActive = activeSnapshot
                rulesCache = pendingRules.map { it.copy() }.toMutableList()
                saveToPrefs(context)
                updateBaselineAfterImmediateChange(context)
                clearAllPendingChanges(context)

                val diffMessages = buildRuleSetDiffMessages(context, prevActive, pendingRules)
                if (diffMessages.isNotEmpty()) {
                    diffMessages.forEachIndexed { index, (msg, tag) ->
                        ChangeLogManager.addEntry(context, CATEGORY_LOG, msg, false,
                            tag = tag ?: "policy_switch_imm_$index")
                    }
                } else {
                    ChangeLogManager.addEntry(context, CATEGORY_LOG,
                        "策略切换：待生效的有利/恢复应用时间窗修改已立即生效。", false,
                        tag = "policy_switch_recalc")
                }
            }
        }
    }

    /**
     * Build human-readable diff messages comparing two rule sets.
     * Returns list of (description, tag) pairs.
     */
    private fun buildRuleSetDiffMessages(context: Context, oldRules: List<AppWindowRule>, newRules: List<AppWindowRule>): List<Pair<String, String?>> {
        val messages = mutableListOf<Pair<String, String?>>()
        val oldById = oldRules.associateBy { it.id }
        val newById = newRules.associateBy { it.id }

        for (newRule in newRules) {
            val oldRule = oldById[newRule.id]
            if (oldRule == null) {
                messages.add(generateChangeDescription(context, null, newRule) to newRule.id)
            } else if (!isRuleContentSame(oldRule, newRule)) {
                messages.add(generateChangeDescription(context, oldRule, newRule) to newRule.id)
            }
        }
        for (oldRule in oldRules) {
            if (!newById.containsKey(oldRule.id)) {
                messages.add(generateChangeDescription(context, oldRule, null) to oldRule.id)
            }
        }
        return messages
    }

    fun addRule(context: Context, rule: AppWindowRule) {
        ensureLoaded(context)
        
        val existingRule = findRuleByName(context, rule.name)
        if (existingRule != null) {
            Log.i(TAG, "addRule: Name '${rule.name}' exists. Redirecting to Update.")
            val mergedRule = rule.copy(id = existingRule.id)
            updateRule(context, mergedRule)
            return
        }

        // 🚀 [User Rule] Case 2: Key Definition Match (Same Start/End Time)
        // If a rule exists with SAME time, treat as 'Same Rule' modification.
        val timeMatchRule = rulesCache.find { it.startTime == rule.startTime && it.endTime == rule.endTime }
        if (timeMatchRule != null) {
            Log.i(TAG, "addRule: Time Range matches '${timeMatchRule.name}'. Treating as Update.")
            val mergedRule = rule.copy(id = timeMatchRule.id)
            // Note: Caller might have changed name, so we keep New Name? 
            // Usually Key match implies identity. Let's assume we update the existing rule's content.
            updateRule(context, mergedRule)
            return
        }

        if (checkConflict(context, rule)) {
             Log.w(TAG, "Overlap detected for rule ${rule.name}. Allowing as Union.")
        }
        
        applyChange(context, null, rule)
    }

    private fun checkConflict(context: Context, rule: AppWindowRule): Boolean {
        // Simple overlap check against cached rules
        return rulesCache.any { it.id != rule.id && isTimeOverlap(it, rule) }
    }

    private fun areRuleListsEquivalent(a: List<AppWindowRule>, b: List<AppWindowRule>): Boolean {
        if (a.size != b.size) return false
        val mapA = a.associateBy { it.id }
        val mapB = b.associateBy { it.id }
        if (mapA.keys != mapB.keys) return false
        return mapA.keys.all { id ->
            val ra = mapA[id]!!
            val rb = mapB[id]!!
            isRuleContentSame(ra, rb)
        }
    }

    private fun isRuleSetAdverseAgainstBaseline(
        context: Context,
        baselineRules: List<AppWindowRule>,
        candidateRules: List<AppWindowRule>
    ): Boolean {
        val baselineMap = baselineRules.associateBy { it.id }
        val candidateMap = candidateRules.associateBy { it.id }

        for ((id, baselineRule) in baselineMap) {
            val candidateRule = candidateMap[id]
            if (analyzeChange(context, baselineRule, candidateRule).isOverallAdverse) {
                return true
            }
        }

        val pureAdditions = candidateRules.filter { !baselineMap.containsKey(it.id) }
        for (added in pureAdditions) {
            val split = splitRuleByAppAndDayForMixedActivation(added, baselineRules)
            if (split.hasDelayedPart) {
                return true
            }
        }

        return false
    }


    private fun findRuleByName(context: Context, name: String): AppWindowRule? {
        val pending = getPendingRules(context)
        val pendingMatch = pending?.find { it.name == name }
        if (pendingMatch != null) return pendingMatch
        
        return rulesCache.find { it.name == name }
    }
    
    fun updateRule(context: Context, rule: AppWindowRule) {
        ensureLoaded(context)
        if (checkConflict(context, rule)) {
             Log.w(TAG, "Overlap detected for rule ${rule.name}. Allowing as Union.")
        }
        val activeRule = rulesCache.find { it.id == rule.id }
        // 🚀 如果已有待生效变更，用 pending 作为 oldRule（保证变更累加而非覆盖）
        val pendingRules = getPendingRules(context)
        val pendingRule = pendingRules?.find { it.id == rule.id }
        val oldRule = if (pendingRule != null && activeRule != null && !isRuleContentSame(activeRule, pendingRule)) {
            Log.i(TAG, "updateRule: Using pending as oldRule for accumulation")
            pendingRule
        } else {
            activeRule
        }

        if (activeRule != null && isRuleContentSame(activeRule, rule)) {
             val pendingRules = getPendingRules(context)
             val pendingRule = pendingRules?.find { it.id == rule.id }
             
             if (pendingRule != null && !isRuleContentSame(pendingRule, rule)) {
                 Log.i(TAG, "updateRule: Content matches Active, but differs from Pending. Proceeding to restore.")
                 // Fall through
             } else {
                 if (oldRule?.name != rule.name) {
                     Log.i(TAG, "updateRule: Only name changed.")
                     applyChange(context, oldRule, rule)
                     return
                 }
                 Log.i(TAG, "updateRule: Content identical, skipping.")
                 return
             }
        }
        
        applyChange(context, oldRule, rule)
    }
    
    fun deleteRule(context: Context, ruleId: String) {
        ensureLoaded(context)
        val oldRule = rulesCache.find { it.id == ruleId }
        applyChange(context, oldRule, null)
    }
    
    // 🚀 [Refactored] Integrated Auto-Promotion and Baseline-Based Analysis
    private fun applyChange(context: Context, oldRule: AppWindowRule?, newRule: AppWindowRule?) {
        // 0. Ensure baseline is fresh for today's business day before comparison
        ensureFreshBaseline(context)

        // 1. Absorb legacy promotion marker first.
        promoteMatureBaseline(context)
        ensureLoaded(context)
        val activeSnapshot = rulesCache.map { it.copy() }.toMutableList()

        // 2. Identify Baseline Rule for Comparison
        val targetId = oldRule?.id ?: newRule?.id ?: ""

        // 🚀 自动清理已卸载的应用（弹窗只记录当次修改）
        val oldRule = oldRule?.let { cleanUninstalledPackages(context, it) } ?: oldRule
        val newRule = newRule?.let { cleanUninstalledPackages(context, it) } ?: newRule

        val baselineRule = getBaselineRule(context, targetId)
        
        // 🚀 [Fix-1] 适应期内所有变更立即生效，不走延迟逻辑
        val isAdaptation = CreditManager.isInAdaptationPeriod(context)
        val forceAllNextDay =
            !isAdaptation &&
                EffectiveActivationPolicyManager.getPolicy(context) == EffectiveActivationPolicyManager.Policy.ALL_NEXT_DAY
        
        // 3. Analyze Change vs BASELINE (not Active!)
        // Key Insight: 基准为空 = 最不利状态，任何变更都按不利处理，进入延迟生效
        // If New matches Baseline -> Restored (Immediate)
        // If New is Favorable vs Baseline -> Immediate  
        // If New is Adverse vs Baseline -> Delayed
        
        var isImmediate = false
        var mixedSplit: AppDayMixedSplit? = null
        var isMixedAddition = false
        var isMixedDeletion = false
        
        if (forceAllNextDay) {
            Log.i(TAG, "applyChange: Policy=ALL_NEXT_DAY. Force delayed path for app windows.")
            isImmediate = false
        } else if (isAdaptation) {
            // 🚀 适应期：所有变更立即生效
            Log.i(TAG, "applyChange: Adaptation Period. All changes immediate.")
            isImmediate = true
        } else if (oldRule == null && newRule != null) {
            // 🚀 [Fix] 新增规则：按"应用×日期"拆分有利/不利，基于 BASELINE 而非 Active 判定
            // 基准为空 → 加任何窗口都是有利（立即生效）；基准已有窗口 → 追加窗口是不利（延迟生效）
            val baselineSnapshot = getBaselineRules(context)
            mixedSplit = splitRuleByAppAndDayForMixedActivation(newRule, baselineSnapshot)
            when {
                mixedSplit.hasDelayedPart && mixedSplit.hasImmediatePart -> {
                    isImmediate = false
                    isMixedAddition = true
                    Log.i(TAG, "applyChange: Addition has mixed app-day result. Partial immediate + partial delayed.")
                }
                !mixedSplit.hasDelayedPart && mixedSplit.hasImmediatePart -> {
                    isImmediate = true
                    Log.i(TAG, "applyChange: Addition all favorable by app-day. Immediate.")
                }
                else -> {
                    isImmediate = false
                    Log.i(TAG, "applyChange: Addition all adverse by app-day. Delayed.")
                }
            }
        } else if (baselineRule == null && oldRule != null && newRule == null) {
            // 🚀 [Fresh Rule Exemption]
            // 规则不在 Baseline 中时，删除视为“恢复基准（无规则）”并立即生效。
            Log.i(TAG, "applyChange: Baseline missing for deletion. Treat as fresh-rule restore => immediate.")
            isImmediate = true
        } else if (baselineRule == null) {
            // 🚀 [Fix] 基准缺失时：
            // - 新增规则已在上方分支按“应用×日期”处理
            // - 已存在规则的修改/删除应回退到 Active 对比，避免“弹窗立即生效但执行层延迟”不一致
            val removedApps = if (oldRule != null && newRule != null) {
                oldRule.packageNames - newRule.packageNames
            } else {
                emptySet()
            }
            if (removedApps.isNotEmpty()) {
                // 新建规则中移除应用：按“恢复基准（该应用在基准无窗口）”立即生效
                Log.i(TAG, "applyChange: Baseline missing + app removal on fresh rule => immediate restore.")
                isImmediate = true
            } else {
                val fallbackAnalysis = analyzeChange(context, oldRule, newRule)
                if (!fallbackAnalysis.isOverallAdverse) {
                    Log.i(TAG, "applyChange: Baseline missing. Fallback to Active analysis => favorable/benign, immediate.")
                    isImmediate = true
                } else {
                    Log.i(TAG, "applyChange: Baseline missing. Fallback to Active analysis => adverse, delayed.")
                    isImmediate = false
                }
            }
        } else if (newRule != null && isRuleContentSame(baselineRule, newRule)) {
            // Check for Restoration first
            Log.i(TAG, "applyChange: Restored to Baseline. Immediate.")
            isImmediate = true
        } else if (oldRule != null && newRule == null && baselineRule != null) {
            // 🚀 [Fix] 删除规则：按"应用×日期"拆分，支持混合生效
            val deletionSplit = splitDeletionByAppAndDay(oldRule, getBaselineRules(context))
            when {
                deletionSplit.hasDelayedPart && deletionSplit.hasImmediatePart -> {
                    isImmediate = false
                    isMixedDeletion = true
                    mixedSplit = deletionSplit
                    Log.i(TAG, "applyChange: Deletion has mixed app-day result. Partial immediate + partial delayed.")
                }
                !deletionSplit.hasDelayedPart -> {
                    isImmediate = true
                    Log.i(TAG, "applyChange: Deletion all favorable by app-day. Immediate.")
                }
                else -> {
                    isImmediate = false
                    Log.i(TAG, "applyChange: Deletion all adverse by app-day. Delayed.")
                }
            }
        } else {
            // 🚀 [Fix-2] Analyze vs BASELINE Rule, not Active Rule
            val analysis = analyzeChange(context, baselineRule, newRule)
            
            if (!analysis.isOverallAdverse) {
                Log.i(TAG, "applyChange: Favorable/Benign vs Baseline. Immediate.")
                isImmediate = true
            } else {
                Log.i(TAG, "applyChange: Adverse vs Baseline. Delayed.")
                isImmediate = false
            }
        }

        // 🚀 [Analysis for Logging] - User wants to see "Modified X", "Deleted Y"
        // Log generation uses "Old Active vs New" to describe the ACTION (e.g. "You deleted X").
        var logDescription = generateChangeDescription(context, oldRule, newRule)

        if (isImmediate) {
             // ✅ Apply to Active
             updateList(rulesCache, oldRule, newRule)
             saveToPrefs(context)
             
             // Update Timeline
             updateBaselineAfterImmediateChange(context)
             
             // Sync Pending: Clear specific pending logs/entries if matching?
             val pending = getPendingRules(context)
             if (pending != null) {
                 val pendingOld = pending.find { it.id == targetId }
                 updateList(pending, pendingOld, newRule)
                 ChangeLogManager.removePendingEntriesByTag(context, CATEGORY_LOG, targetId)

                 val retainPartialMask = shouldRetainPartialDelayMaskAfterImmediateSync(
                     targetRuleId = targetId,
                     newRule = newRule,
                     pendingRules = pending
                 )
                 if (!retainPartialMask) {
                     clearPartialDelayedDaysForRule(context, targetId)
                 } else if (newRule != null) {
                     val delayedByApp = loadPartialDelayMap(context)[targetId]
                         ?.mapValues { it.value.toSet() }
                         .orEmpty()
                     if (delayedByApp.isNotEmpty()) {
                         val delayedText = formatAppDayMatrix(context, delayedByApp)
                         val refreshedLog = "[待生效-部分延迟] ${newRule.name}\n  - 延迟生效: $delayedText"
                         ChangeLogManager.addEntry(context, CATEGORY_LOG, refreshedLog, true, tag = targetId)
                     }
                 }
                 
                 if (isSameAsActive(pending) && !hasPartialDelayedEntries(context)) {
                     clearAllPendingChanges(context)
                 } else {
                     saveToPendingPrefs(context, pending)
                 }
             } else {
                 clearPartialDelayedDaysForRule(context, targetId)
             }

        } else {
             // ❌ Delayed (Pending)
             if (isMixedDeletion && oldRule != null && mixedSplit != null) {
                 // 🚀 [Fix] 删除混合生效：立即删除有利部分（仍有其他窗口的应用×天），不利部分延迟
                 val adversePkgs = mixedSplit.delayedByApp.keys.toSet()
                 val favorablePkgs = mixedSplit.immediateByApp.keys.toSet()
                 val keptRule = oldRule.copy(packageNames = adversePkgs.toMutableSet())
                 if (adversePkgs.isNotEmpty()) {
                     // 保留不利应用在 active 中（延迟删除）
                     updateList(rulesCache, oldRule, keptRule)
                 } else {
                     // 全部有利 → 直接删除
                     updateList(rulesCache, oldRule, null)
                 }
                 saveToPrefs(context)
                 clearLegacyPromotionMarker(context)

                 val immediateText = formatAppDayMatrix(context, mixedSplit.immediateByApp)
                 if (immediateText != "无") {
                     val immediateLog = "[删除-部分立即] ${oldRule.name}\n  - 立即删除: $immediateText"
                     ChangeLogManager.addEntry(context, CATEGORY_LOG, immediateLog, false, tag = "${targetId}_imm")
                 }

                 val delayedText = formatAppDayMatrix(context, mixedSplit.delayedByApp)
                 logDescription = "[删除-部分延迟] ${oldRule.name}\n  - 延迟删除: $delayedText"

                 // 在 pending 中写入完全删除
                 pushUndoSnapshot(context)
                 val pending = getPendingRules(context) ?: activeSnapshot.map { it.copy() }.toMutableList()
                 val pendingOld = pending.find { it.id == targetId }
                 updateList(pending, pendingOld, null)
                 saveToPendingPrefs(context, pending)
                 markPendingFlag(context)

                 val effTs = ConfigPendingManager.calculateNextEffectiveTime(context, System.currentTimeMillis(), true)
                 val effDateStr = if (effTs > 0L) {
                     SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(effTs))
                 } else {
                     "次日生效"
                 }
                 ChangeLogManager.addEntry(context, CATEGORY_LOG, "$logDescription\n  - 生效日: $effDateStr", true, tag = targetId)
                 Log.i(TAG, "applyChange: Mixed deletion. Favorable removed now, adverse delayed. logDescription=$logDescription")
                 return  // Early return — handled inline
             } else if (isMixedAddition && newRule != null && mixedSplit != null) {
                 // Mixed mode: apply whole rule now, but mask adverse app-day pairs until pending is applied.
                 updateList(rulesCache, oldRule, newRule)
                 saveToPrefs(context)
                 clearLegacyPromotionMarker(context)

                 setPartialDelayedDaysForRule(context, targetId, mixedSplit.delayedByApp)

                 val immediateText = formatAppDayMatrix(context, mixedSplit.immediateByApp)
                 if (immediateText != "无") {
                     val immediateLog = "[新增-部分立即] ${newRule.name}\n  - 立即生效: $immediateText"
                     ChangeLogManager.addEntry(context, CATEGORY_LOG, immediateLog, false, tag = "${targetId}_imm")
                 }

                 val delayedText = formatAppDayMatrix(context, mixedSplit.delayedByApp)
                 logDescription = "[新增-部分延迟] ${newRule.name}\n  - 延迟生效: $delayedText"
             } else {
                 clearPartialDelayedDaysForRule(context, targetId)
             }

             Log.i(TAG, "applyChange: Writing to pending. Description: $logDescription")
             pushUndoSnapshot(context)
             val pending = getPendingRules(context) ?: activeSnapshot.map { it.copy() }.toMutableList() // Copy active if uninit
             
             val pendingOld = pending.find { it.id == targetId }
             
             // 🚀 [Smart Merge]
             val pendingRuleToSave = if (oldRule != null && newRule != null && !isImmediate) {
                 // Logic for mixed updates merging
                 val addedApps = newRule.packageNames - oldRule.packageNames
                 if (addedApps.isNotEmpty() && pendingOld != null) {
                     newRule.copy(packageNames = (pendingOld.packageNames + addedApps).toMutableSet())
                 } else {
                     newRule
                 }
             } else {
                 newRule
             }

             updateList(pending, pendingOld, pendingRuleToSave)
             saveToPendingPrefs(context, pending)
             markPendingFlag(context)
             
             // 🚀 [Fix-3] 确保延迟生效记录一定写入 ChangeLog
             val effTs = ConfigPendingManager.calculateNextEffectiveTime(context, System.currentTimeMillis(), true)
             val effDateStr = if (effTs > 0L) {
                 SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(effTs))
             } else {
                 "次日生效"
             }
             ChangeLogManager.addEntry(context, CATEGORY_LOG, "$logDescription\n  - 生效日: $effDateStr", true, tag = targetId)
             Log.i(TAG, "applyChange: Pending entry logged to ChangeLogManager. tag=$targetId")
        }
    }
    
    private fun generateChangeDescription(context: Context, oldRule: AppWindowRule?, newRule: AppWindowRule?): String {
        if (oldRule != null && newRule == null) {
            return "[删除] ${oldRule.name}"
        }
        if (oldRule == null && newRule != null) {
            val sb = StringBuilder("[新增] ${newRule.name}")
            sb.append("\n  - 时间: ${formatTimeRange(newRule)}")
            sb.append("\n  - 日期: ${formatDays(newRule.daysOfWeek)}")
            sb.append("\n  - 应用: ${formatAppList(context, newRule.packageNames)}")
            return sb.toString()
        }
        if (oldRule != null && newRule != null) {
            val details = mutableListOf<String>()
            if (oldRule.name != newRule.name) {
                details.add("名称: ${oldRule.name} -> ${newRule.name}")
            }
            if (oldRule.startTime != newRule.startTime || oldRule.endTime != newRule.endTime) {
                details.add("时间: ${formatTimeRange(oldRule)} -> ${formatTimeRange(newRule)}")
            }
            if (oldRule.daysOfWeek != newRule.daysOfWeek) {
                details.add("日期: ${formatDays(oldRule.daysOfWeek)} -> ${formatDays(newRule.daysOfWeek)}")
            }
            if (oldRule.isEnabled != newRule.isEnabled) {
                details.add("状态: ${if (oldRule.isEnabled) "开" else "关"} -> ${if (newRule.isEnabled) "开" else "关"}")
            }
            val addedApps = newRule.packageNames - oldRule.packageNames
            val removedApps = oldRule.packageNames - newRule.packageNames
            if (addedApps.isNotEmpty()) {
                details.add("新增应用: ${formatAppList(context, addedApps)}")
            }
            if (removedApps.isNotEmpty()) {
                details.add("移除应用: ${formatAppList(context, removedApps)}")
            }
            if (details.isEmpty()) return "[修改] ${newRule.name} (无变化)"
            return "[修改] ${newRule.name}\n" + details.joinToString("\n") { "  - $it" }
        }
        return "❓ 未知变更"
    }

    fun formatTimeRange(rule: AppWindowRule): String {
        val start = String.format("%02d:%02d", rule.startTime / 60, rule.startTime % 60)
        val end = String.format("%02d:%02d", rule.endTime / 60, rule.endTime % 60)
        return "$start-$end"
    }

    private fun formatAppList(context: Context, packages: Set<String>, maxCount: Int = 4): String {
        if (packages.isEmpty()) return "无"
        val names = packages.map { getAppName(context, it) }.sorted()
        return if (names.size <= maxCount) {
            names.joinToString("、")
        } else {
            names.take(maxCount).joinToString("、") + " 等${names.size}个"
        }
    }

    private fun formatDaysShort(days: Set<Int>): String {
        if (days.size == 7) return "每天"
        if (days.isEmpty()) return "无"
        val map = mapOf(1 to "日", 2 to "一", 3 to "二", 4 to "三", 5 to "四", 6 to "五", 7 to "六")
        return "周" + days.sorted().joinToString("") { map[it] ?: "" }
    }

    private fun formatDays(days: Set<Int>): String {
        if (days.size == 7) return "每天"
        if (days.isEmpty()) return "从不"
        val map = mapOf(1 to "周一", 2 to "周二", 3 to "周三", 4 to "周四", 5 to "周五", 6 to "周六", 7 to "周日")
        return days.sorted().joinToString(",") { map[it] ?: "" }
    }
    
    private fun getAppName(context: Context, packageName: String): String {
        return try {
            val pm = context.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
        } catch (e: Exception) {
            packageName.substringAfterLast(".")
        }
    }

    /** 将包名列表转为应用名列表（逗号分隔），用于 UI 展示 */
    private fun formatAppNames(context: Context, packages: Collection<String>): String {
        return packages.map { getAppName(context, it) }.joinToString("、")
    }

    private fun updateList(list: MutableList<AppWindowRule>, oldItem: AppWindowRule?, newItem: AppWindowRule?) {
        if (oldItem != null) {
            list.removeIf { it.id == oldItem.id }
        }
        if (newItem != null) {
            list.add(newItem)
        }
    }

    private fun saveToPendingPrefs(context: Context, list: MutableList<AppWindowRule>) {
        val prefs = context.getSharedPreferences(PREFS_PENDING, Context.MODE_PRIVATE)
        val array = rulesToJson(list)
        prefs.edit().putString(KEY_PENDING_RULES_JSON, array.toString()).apply()
    }
    
    private fun markPendingFlag(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_PENDING, Context.MODE_PRIVATE)
        val effectiveTime = ConfigPendingManager.calculateNextEffectiveTime(context, System.currentTimeMillis())
        prefs.edit().putBoolean("has_pending_changes", true).putLong("effective_timestamp", effectiveTime).apply()
    }

    fun applyPendingToActive(context: Context, force: Boolean = false) {
        val pendingPrefs = context.getSharedPreferences(PREFS_PENDING, Context.MODE_PRIVATE)
        if (!pendingPrefs.getBoolean("has_pending_changes", false)) return

        val effectiveTime = pendingPrefs.getLong("effective_timestamp", 0L)
        if (!force && System.currentTimeMillis() < effectiveTime) return
        
        val json = pendingPrefs.getString(KEY_PENDING_RULES_JSON, null) ?: return
        val activePrefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        activePrefs.edit().putString(KEY_RULES_JSON, json).apply()
        
        pendingPrefs.edit().clear().apply()
        isCacheLoaded = false
        ensureLoaded(context)
        saveBaseline(context)
        Log.i(TAG, "Applied Pending App Window Rules")
        ChangeLogManager.syncLogStatusWithPendingState(context)
    }

    fun canModifyRule(context: Context, oldRule: AppWindowRule?, newRule: AppWindowRule?): Boolean {
        // 🚀 [Simplification] All modifications are allowed.
        // The system will categorize them as Immediate (Favorable) or Delayed (Adverse).
        // No blocking UI.
        return true
    }
    
    // Check if inner rule's time range is fully contained within outer rule's time range
    private fun isTimeRangeSubset(inner: AppWindowRule, outer: AppWindowRule): Boolean {
        val innerDur = getDuration(inner.startTime, inner.endTime)
        val outerDur = getDuration(outer.startTime, outer.endTime)
        if (innerDur > outerDur) return false 
        
        val innerStart = inner.startTime
        for (i in 0 until innerDur) {
            val m = (innerStart + i) % 1440
            if (!isMinuteInWindow(m, outer.startTime, outer.endTime)) return false
        }
        return true
    }
    
    private fun isMinuteInWindow(m: Int, start: Int, end: Int): Boolean {
        if (start == end) return true // 起止相同视为全天开放
        return if (start <= end) m in start..end else m >= start || m <= end
    }
    
    private fun getDuration(start: Int, end: Int): Int {
        if (start == end) return 1440
        return if (end >= start) end - start else (1440 - start) + end
    }

    // 🚀 Conflict Detection Helpers (Simplified/Retained for Usage Check)
    private fun isTimeOverlap(r1: AppWindowRule, r2: AppWindowRule): Boolean {
         val d1 = getDuration(r1.startTime, r1.endTime)
         val start1 = r1.startTime
         for (i in 0 until d1) {
             val m1 = (start1 + i) % 1440
             if (isMinuteInWindow(m1, r2.startTime, r2.endTime)) return true
         }
         return false
    }

    // 🚀 [Analysis] Detailed Change Analysis
    enum class ChangeType { FAVORABLE, ADVERSE, RESTORED, INFO }
    data class ChangeDetail(val text: String, val type: ChangeType)
    data class ChangeAnalysis(val isOverallAdverse: Boolean, val details: List<ChangeDetail>)
    data class AppDayMixedSplit(
        val immediateByApp: Map<String, Set<Int>>,
        val delayedByApp: Map<String, Set<Int>>
    ) {
        val hasImmediatePart: Boolean get() = immediateByApp.values.any { it.isNotEmpty() }
        val hasDelayedPart: Boolean get() = delayedByApp.values.any { it.isNotEmpty() }
    }

    /**
     * 供 UI 保存弹窗使用：与 applyChange 的关键豁免保持一致，避免“弹窗说不利、执行却立即生效”。
     */
    fun analyzeChangeForDisplay(
        context: Context,
        baselineRule: AppWindowRule?,
        oldRule: AppWindowRule?,
        newRule: AppWindowRule?
    ): ChangeAnalysis {
        if (baselineRule == null && oldRule != null && newRule == null) {
            return ChangeAnalysis(
                isOverallAdverse = false,
                details = listOf(ChangeDetail("删除规则 (新鲜规则恢复基准)", ChangeType.RESTORED))
            )
        }

        if (baselineRule == null && oldRule != null && newRule != null) {
            val removedApps = oldRule.packageNames - newRule.packageNames
            if (removedApps.isNotEmpty()) {
                return ChangeAnalysis(
                    isOverallAdverse = false,
                    details = listOf(ChangeDetail("移除应用 (新鲜规则恢复基准)", ChangeType.RESTORED))
                )
            }
        }

        return analyzeChange(context, baselineRule ?: oldRule, newRule)
    }

    fun analyzeChange(context: Context, oldRule: AppWindowRule?, newRule: AppWindowRule?): ChangeAnalysis {
        val details = mutableListOf<ChangeDetail>()
        
        // 1. Deletion
        if (newRule == null) {
            if (oldRule == null) return ChangeAnalysis(false, emptyList())
            
            // Analyze Deletion vs OLD Rule (Baseline or Active)
            val favorableApps = mutableListOf<String>()
            val adverseApps = mutableListOf<String>()
            
            oldRule.packageNames.forEach { pkg ->
                // 🚀 删除时按天判定：检查该应用在老规则生效的天数内是否还有其他窗口
                if (hasOtherTimeWindow(context, pkg, oldRule.id, oldRule.daysOfWeek)) {
                    favorableApps.add(pkg)
                } else {
                    adverseApps.add(pkg)
                }
            }

            if (favorableApps.isNotEmpty()) details.add(ChangeDetail("立即删除 (仍有窗口): ${formatAppNames(context, favorableApps)}", ChangeType.FAVORABLE))
            if (adverseApps.isNotEmpty()) details.add(ChangeDetail("延迟删除 (最后窗口): ${formatAppNames(context, adverseApps)}", ChangeType.ADVERSE))

            return ChangeAnalysis(adverseApps.isNotEmpty(), details)
        }

        // 2. Addition
        if (oldRule == null) {
            val split = splitRuleByAppAndDayForMixedActivation(newRule, getRules(context))
            val immediateByApp = split.immediateByApp
            val delayedByApp = split.delayedByApp

            val favorableOnlyApps = immediateByApp.keys.filter { pkg ->
                immediateByApp[pkg].orEmpty().isNotEmpty() && delayedByApp[pkg].orEmpty().isEmpty()
            }
            val adverseOnlyApps = delayedByApp.keys.filter { pkg ->
                delayedByApp[pkg].orEmpty().isNotEmpty() && immediateByApp[pkg].orEmpty().isEmpty()
            }
            val mixedApps = newRule.packageNames.filter { pkg ->
                immediateByApp[pkg].orEmpty().isNotEmpty() && delayedByApp[pkg].orEmpty().isNotEmpty()
            }

            if (favorableOnlyApps.isNotEmpty()) {
                details.add(ChangeDetail("新增规则 (首个窗口)", ChangeType.FAVORABLE))
            }
            if (adverseOnlyApps.isNotEmpty()) {
                details.add(ChangeDetail("新增规则 (追加窗口)", ChangeType.ADVERSE))
            }
            if (mixedApps.isNotEmpty()) {
                details.add(ChangeDetail("新增规则 (按天混合生效)", ChangeType.INFO))
            }

            return ChangeAnalysis(split.hasDelayedPart, details)
        }

        // 3. Modification
        var isOverallAdverse = false

        // Enable/Disable
        if (oldRule.isEnabled != newRule.isEnabled) {
            if (newRule.isEnabled) details.add(ChangeDetail("启用规则", ChangeType.FAVORABLE))
            else {
                details.add(ChangeDetail("停用规则", ChangeType.ADVERSE))
                isOverallAdverse = true
            }
        }

        // Apps
        val addedApps = newRule.packageNames - oldRule.packageNames
        if (addedApps.isNotEmpty()) {
            // 🚀 新增应用按天判定
            val favorable = addedApps.filter { !hasOtherTimeWindow(context, it, newRule.id, newRule.daysOfWeek) }
            val adverse = addedApps.filter { hasOtherTimeWindow(context, it, newRule.id, newRule.daysOfWeek) }
            if (adverse.isNotEmpty()) {
                details.add(ChangeDetail("新增 (追加窗口 → 延迟): ${formatAppNames(context, adverse)}", ChangeType.ADVERSE))
                isOverallAdverse = true
            }
            if (favorable.isNotEmpty()) {
                details.add(ChangeDetail("新增 (首个窗口 → 立即): ${formatAppNames(context, favorable)}", ChangeType.FAVORABLE))
            }
        }
        val removedApps = oldRule.packageNames - newRule.packageNames
        if (removedApps.isNotEmpty()) {
            // 🚀 移除应用按天判定：检查在老规则生效天内该应用是否还有其他窗口
            val favorable = removedApps.filter { hasOtherTimeWindow(context, it, oldRule.id, oldRule.daysOfWeek) }
            val adverse = removedApps.filter { !hasOtherTimeWindow(context, it, oldRule.id, oldRule.daysOfWeek) }
            if (adverse.isNotEmpty()) {
                details.add(ChangeDetail("移除 (最后窗口 → 延迟): ${formatAppNames(context, adverse)}", ChangeType.ADVERSE))
                isOverallAdverse = true
            }
            if (favorable.isNotEmpty()) {
                details.add(ChangeDetail("移除 (仍有窗口 → 立即): ${formatAppNames(context, favorable)}", ChangeType.FAVORABLE))
            }
        }

        // Time
        if (oldRule.startTime != newRule.startTime || oldRule.endTime != newRule.endTime) {
            if (isTimeRangeSubset(newRule, oldRule)) {
                details.add(ChangeDetail("缩减时间窗", ChangeType.FAVORABLE))
            } else {
                details.add(ChangeDetail("延长或平移时间窗", ChangeType.ADVERSE))
                isOverallAdverse = true
            }
        }

        // Days
        if (oldRule.daysOfWeek != newRule.daysOfWeek) {
             if (newRule.daysOfWeek.containsAll(oldRule.daysOfWeek)) {
                  val addedDays = newRule.daysOfWeek - oldRule.daysOfWeek
                  details.add(ChangeDetail("增加生效日期: ${formatDaysShort(addedDays)}", ChangeType.FAVORABLE))
             } else {
                  // 🚀 按天判定：减少日期时检查该应用在被移除的日期是否仍有其他规则兜底
                  val removedDays = oldRule.daysOfWeek - newRule.daysOfWeek
                  val appsWithNoFallback = oldRule.packageNames.filter { pkg ->
                      removedDays.any { day ->
                          !hasOtherTimeWindow(context, pkg, oldRule.id, setOf(day))
                      }
                  }
                  val appsWithFallback = oldRule.packageNames - appsWithNoFallback.toSet()
                  if (appsWithNoFallback.isEmpty()) {
                      details.add(ChangeDetail("减少日期 (仍有兜底 → 立即): ${formatDaysShort(removedDays)}", ChangeType.FAVORABLE))
                  } else {
                      if (appsWithFallback.isNotEmpty()) {
                          details.add(ChangeDetail("减少日期 (有兜底 → 立即): ${formatDaysShort(removedDays)} / ${formatAppNames(context, appsWithFallback)}", ChangeType.FAVORABLE))
                      }
                      details.add(ChangeDetail("减少日期 (无兜底 → 延迟): ${formatDaysShort(removedDays)} / ${formatAppNames(context, appsWithNoFallback)}", ChangeType.ADVERSE))
                      isOverallAdverse = true
                  }
             }
        }
        
        if (details.isEmpty()) details.add(ChangeDetail("未检测到变更", ChangeType.INFO))
        
        return ChangeAnalysis(isOverallAdverse, details)
    }

    // 🚀 Lock Screen Info
    fun getAllowedWindowsSummary(context: Context, packageName: String): String {
        ensureLoaded(context)
        val relevantRules = rulesCache.filter { it.isEnabled && it.packageNames.contains(packageName) }
        
        if (relevantRules.isEmpty()) return ""
        
        val cal = java.util.Calendar.getInstance()
        val sysDay = cal.get(java.util.Calendar.DAY_OF_WEEK)
        val modelDay = if (sysDay == java.util.Calendar.SUNDAY) 7 else sysDay - 1
        
        val sb = StringBuilder()
        var hasMatch = false
        val sortedRules = relevantRules.sortedBy { it.startTime }
        
        for (rule in sortedRules) {
            if (isRulePackageDayEffective(context, rule, packageName, modelDay)) {
                hasMatch = true
                val rangeText = if (rule.startTime == rule.endTime) {
                    "全天"
                } else {
                    val start = String.format("%02d:%02d", rule.startTime / 60, rule.startTime % 60)
                    val end = String.format("%02d:%02d", rule.endTime / 60, rule.endTime % 60)
                    "$start-$end"
                }
                if (sb.isNotEmpty()) sb.append(", ")
                sb.append(rangeText)
            }
        }
        
        if (!hasMatch) return ""
        return sb.toString()
    }

    fun getConfiguredWindowsSummary(context: Context, packageName: String): String {
        ensureLoaded(context)
        val relevantRules = rulesCache
            .filter { it.isEnabled && it.packageNames.contains(packageName) }
            .sortedBy { it.startTime }

        if (relevantRules.isEmpty()) return ""

        return relevantRules.joinToString(", ") { rule ->
            if (rule.startTime == rule.endTime) {
                "全天"
            } else {
                val start = String.format("%02d:%02d", rule.startTime / 60, rule.startTime % 60)
                val end = String.format("%02d:%02d", rule.endTime / 60, rule.endTime % 60)
                "$start-$end"
            }
        }
    }
    
    private fun isSameAsActive(pending: List<AppWindowRule>): Boolean {
        if (pending.size != rulesCache.size) return false
        val activeMap = rulesCache.associateBy { it.id }
        return pending.all { p -> 
            val active = activeMap[p.id]
            active != null && 
            active.name == p.name &&
            active.startTime == p.startTime &&
            active.endTime == p.endTime &&
            active.packageNames == p.packageNames &&
            active.isEnabled == p.isEnabled &&
            active.daysOfWeek == p.daysOfWeek
        }
    }
    
    // 🚀 Baseline Logic (Retained & used for Evolution)
    fun saveBaseline(context: Context) {
        ensureLoaded(context)
        val prefs = context.getSharedPreferences(PREFS_BASELINE, Context.MODE_PRIVATE)
        prefs.edit()
            .putString(KEY_BASELINE_RULES_JSON, rulesToJson(rulesCache).toString())
            .putLong("snapshot_time", System.currentTimeMillis())
            .apply()
    }

    fun ensureFreshBaseline(context: Context) {
        val baselinePrefs = context.getSharedPreferences(PREFS_BASELINE, Context.MODE_PRIVATE)
        val hasBaseline = baselinePrefs.contains(KEY_BASELINE_RULES_JSON)
        val lastSnapshot = baselinePrefs.getLong("snapshot_time", 0L)
        val startOfBusinessDay = TimeRuleManager.getStartOfBusinessDay(context)

        if (!hasBaseline) {
            // No baseline ever created — snapshot current active as baseline now
            applyPendingToActive(context)
            saveBaseline(context)
            Log.i(TAG, "ensureFreshBaseline: First baseline created from current active")
            return
        }

        if (lastSnapshot == 0L) {
            // Old-format baseline (no snapshot_time). Preserve its content but stamp it
            // as if it was created at the start of today's business day, so that
            // within today it won't be overwritten. Tomorrow it will refresh normally.
            baselinePrefs.edit().putLong("snapshot_time", startOfBusinessDay).apply()
            Log.i(TAG, "ensureFreshBaseline: Stamped legacy baseline with today's business day start")
            return
        }

        if (lastSnapshot < startOfBusinessDay) {
            // Cross-day: apply pending, then refresh baseline from current active
            applyPendingToActive(context)
            saveBaseline(context)
            Log.i(TAG, "ensureFreshBaseline: Baseline refreshed for new business day")
        }
    }
    
    private fun getBaselineRules(context: Context): List<AppWindowRule> {
        val prefs = context.getSharedPreferences(PREFS_BASELINE, Context.MODE_PRIVATE)
        val json = prefs.getString(KEY_BASELINE_RULES_JSON, null) ?: return emptyList()
        return parseRulesFromJson(json)
    }
    
    fun getBaselineRule(context: Context, ruleId: String): AppWindowRule? {
        return getBaselineRules(context).find { it.id == ruleId }
    }
    
    private fun isRestoredToBaseline(context: Context, newRule: AppWindowRule?): Boolean {
        if (newRule == null) return false
        val baselineRules = getBaselineRules(context)
        val baselineRule = baselineRules.find { it.id == newRule.id }
        return if (baselineRule != null) isRuleContentSame(baselineRule, newRule) else false
    }

    fun isRuleContentSame(r1: AppWindowRule, r2: AppWindowRule): Boolean {
        return r1.startTime == r2.startTime &&
               r1.endTime == r2.endTime &&
               r1.isEnabled == r2.isEnabled &&
               r1.packageNames == r2.packageNames &&
               r1.daysOfWeek == r2.daysOfWeek &&
               r1.name == r2.name
    }
    
    // Status Wrappers for UI
    enum class RuleStatus {
        ACTIVE,
        PENDING_MODIFICATION,
        PENDING_DELETION,
        PENDING_ADDITION
    }

    data class AppWindowRuleWithStatus(
        val rule: AppWindowRule,
        val status: RuleStatus,
        val activeRule: AppWindowRule? = null,
        val baselineRule: AppWindowRule? = null
    )

    fun getDisplayRules(context: Context): List<AppWindowRuleWithStatus> {
        ensureLoaded(context)
        val pending = getPendingRules(context)
        val result = mutableListOf<AppWindowRuleWithStatus>()
        val baselineRules = getBaselineRules(context)
        val baselineMap = baselineRules.associateBy { it.id }

        if (pending == null) {
            rulesCache.forEach { rule ->
                val baseline = baselineMap[rule.id]
                result.add(AppWindowRuleWithStatus(rule, RuleStatus.ACTIVE, baselineRule = baseline))
            }
            return result
        }

        val pendingMap = pending.associateBy { it.id }
        val activeMap = rulesCache.associateBy { it.id }
        
        rulesCache.forEach { activeRule ->
            val pendingRule = pendingMap[activeRule.id]
            val baseline = baselineMap[activeRule.id]
            
            if (pendingRule == null) {
                result.add(AppWindowRuleWithStatus(activeRule, RuleStatus.PENDING_DELETION, baselineRule = baseline))
            } else if (pendingRule != activeRule) {
                result.add(AppWindowRuleWithStatus(pendingRule, RuleStatus.PENDING_MODIFICATION, activeRule, baseline))
            } else {
                result.add(AppWindowRuleWithStatus(activeRule, RuleStatus.ACTIVE, baselineRule = baseline))
            }
        }
        
        pending.forEach { pendingRule ->
            if (!activeMap.containsKey(pendingRule.id)) {
                result.add(AppWindowRuleWithStatus(pendingRule, RuleStatus.PENDING_ADDITION))
            }
        }
        
        // 🚀 过滤掉已卸载的应用（空窗口仍保留显示，方便用户加回应用）
        return result
            .map { status ->
                val installedPkgs = status.rule.packageNames.filter { isPackageInstalled(context, it) }
                if (installedPkgs.toSet() == status.rule.packageNames) {
                    status
                } else {
                    status.copy(rule = status.rule.copy(packageNames = installedPkgs.toMutableSet()))
                }
            }
            .sortedWith(compareBy<AppWindowRuleWithStatus> { it.rule.endTime }.thenBy { it.rule.startTime })
    }

    private fun isPackageInstalled(context: Context, pkg: String): Boolean {
        return try {
            context.packageManager.getApplicationInfo(pkg, 0)
            true
        } catch (e: Exception) {
            false
        }
    }

    /** 清理规则中已卸载的应用包名，返回清理后的规则（若未变化则返回原对象） */
    private fun cleanUninstalledPackages(context: Context, rule: AppWindowRule): AppWindowRule? {
        val installed = rule.packageNames.filter { isPackageInstalled(context, it) }
        if (installed.size == rule.packageNames.size) return null // 无变化
        return rule.copy(packageNames = installed.toMutableSet())
    }

    fun parseRulesFromJsonForPublic(json: String): MutableList<AppWindowRule> {
        return parseRulesFromJson(json)
    }
    
    fun rulesToJsonForPublic(list: List<AppWindowRule>): String {
        return rulesToJson(list).toString()
    }
}
