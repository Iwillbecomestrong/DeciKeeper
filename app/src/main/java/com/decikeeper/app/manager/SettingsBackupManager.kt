/**
 * =================================================================================
 * [Context Header]
 * * @file: SettingsBackupManager.kt
 * * @role: Domain Skill
 * * @description: 统一导出与导入 IronWill 的本地设置快照，支持按分类选择性导入导出。
 * * @responsibilities:
 * 1. 将 SharedPreferences 配置序列化为可保存的 JSON 文件。
 * 2. 从用户选择的备份文件恢复设置，并刷新相关 Manager 缓存。
 * 3. 支持按数据分类（勿扰时段/应用时间窗/黑名单/全局参数/统计数据）筛选。
 * * @context:
 * - 上游: RulesFragment / TestScenarioManager
 * - 下游: SharedPreferences, ContentResolver, TimeRuleManager, AppWindowManager, CreditManager
 * * @constraints (必读):
 * 1. 禁止引入 android.view，保持 UI 无关。
 * 2. 文件 I/O 需由调用方放到后台线程执行。
 * 3. 必须校验备份类型与字段类型，避免损坏当前配置。
 * =================================================================================
 */
package com.decikeeper.app.manager

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import com.decikeeper.app.data.BlacklistStore
import com.decikeeper.app.data.LogExporter
import com.decikeeper.app.data.SegmentAdverseAnalysis
import com.decikeeper.app.data.statistics.AlarmWakeRecord
import com.decikeeper.app.data.statistics.RestUsageRecord
import com.decikeeper.app.manager.questionbank.CustomQuestionBankManager
import com.decikeeper.app.manager.statistics.AlarmWakeStatsRecorder
import com.decikeeper.app.manager.statistics.RestStatsRecorder
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.LinkedHashSet
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject


object SettingsBackupManager {
    private const val BACKUP_TYPE = "decikeeper_settings_backup"
    private const val LEGACY_BACKUP_TYPE = "ironwill_settings_backup"
    private const val LEGACY_ZHIZHI_BACKUP_TYPE = "zhizhi_settings_backup"
    private const val SCHEMA_VERSION = 2
    private const val ALARM_WAKE_RECORDS_KEY = "alarmWakeRecords"
    private const val ALARM_WAKE_RECORDS_VERSION = 1
    private const val REST_RECORDS_KEY = "restUsageRecords"
    private const val REST_RECORDS_VERSION = 1

    // ── 分类定义 ──────────────────────────────────────────────
    enum class BackupCategory(val id: String, val displayName: String) {
        DND_SEGMENTS("dnd_segments", "勿扰时段"),
        APP_WINDOWS("app_windows", "应用时间窗"),
        BLACKLIST("blacklist", "黑名单"),
        ALARM("alarm", "闹钟"),
        GLOBAL_PARAMS("global_params", "规则参数"),
        LEDGER("ledger", "统计数据");

        companion object {
            val ALL = values().toList()

            fun fromId(id: String): BackupCategory? =
                entries.find { it.id == id }
        }
    }

    // ── 每个分类拥有的完整文件（整个 pref 文件归该分类）───────
    private val CATEGORY_EXCLUSIVE_FILES: Map<BackupCategory, List<String>> = mapOf(
        BackupCategory.DND_SEGMENTS to listOf(
            "decikeeper_rules_pending", "decikeeper_rules_baseline"
        ),
        BackupCategory.APP_WINDOWS to listOf(
            "app_window_rules", "app_window_pending", "app_window_baseline"
        ),
        BackupCategory.BLACKLIST to listOf(
            "decikeeper_pending_apps"
        ),
        BackupCategory.ALARM to emptyList(), // 闹钟数据在共享文件中管理
        BackupCategory.GLOBAL_PARAMS to listOf(
            "pending_config_prefs"
        ),
        BackupCategory.LEDGER to listOf(
            "rule_protection_ledger",
            "alarm_stats_ledger",
            "rest_stats_ledger",
            "statistics_prefs",
            "statistics_retention_prefs"
        )
    )

    @androidx.annotation.VisibleForTesting
    internal fun getExclusiveFilesForCategory(category: BackupCategory): List<String> =
        CATEGORY_EXCLUSIVE_FILES[category].orEmpty()

    @androidx.annotation.VisibleForTesting
    internal fun getSharedKeysForCategory(category: BackupCategory, fileName: String): List<String> =
        CATEGORY_SHARED_FILE_KEYS[category]?.get(fileName).orEmpty()

    // ── 共享文件中的按分类 key 过滤 ──────────────────────────
    // key 如果以 * 结尾，按前缀匹配；否则精确匹配
    private val CATEGORY_SHARED_FILE_KEYS: Map<BackupCategory, Map<String, List<String>>> = mapOf(
        BackupCategory.DND_SEGMENTS to mapOf(
            "decikeeper_rules" to listOf("segments_json")
        ),
        BackupCategory.BLACKLIST to mapOf(
            "decikeeper_ledger" to listOf("blacklist_apps_list", "blacklist_mode", "user_removed_apps")
        ),
        BackupCategory.ALARM to mapOf(
            "alarm_prefs" to listOf(
                "alarms_json", "alarm_next_id",
                "snooze_minutes", "max_snooze_count",
                "default_vibration_intensity",
                "default_math_count", "default_poem_count", "default_word_count",
                "default_unlock_mode",
                "default_stage1_types", "default_stage2_types"
            ),
            "alarm_bookmarks" to listOf(
                "bookmarked_poems", "bookmarked_words", "bookmarked_clozes"
            )
        ),
        BackupCategory.GLOBAL_PARAMS to mapOf(
            "decikeeper_rules" to listOf(
                "cool_down_rest_seconds", "cool_down_gap_seconds",
                "borrow_percentage", "notify_advance_minutes",
                "notify_entry_near_minutes", "temp_restricted_access_max_seconds",
                "privilege_window_start", "privilege_window_end"
            ),
            "decikeeper_ledger" to listOf(
                "config_refresh_time", "strict_penalty_duration",
                "weekly_privilege_count_limit_v2", "weekly_privilege_duration_limit_v2",
                "last_weekly_limit_apply_time_v2",
                "weekly_privilege_count_limit_pending",
                "weekly_privilege_duration_limit_pending",
                "weekly_privilege_limit_effective_time"
            )
        ),
        BackupCategory.LEDGER to mapOf(
            "decikeeper_ledger" to listOf(
                "record_date_str",
                "free_time_end", "cool_down_end_timestamp",
                "is_privilege_mode",
                "privilege_session_start", "privilege_session_duration",
                "privilege_session_prededucted", "privilege_session_charged_minutes",
                "usage_*", "borrow_time_*", "privilege_usage_*", "exit_*",
                "count_*",
                "privilege_*_segment_*",
                "weekly_privilege_count_*", "weekly_privilege_duration_used_*",
                "weekly_privilege_last_used_day", "weekly_privilege_last_used"
            )
        ),
        BackupCategory.APP_WINDOWS to emptyMap()
    )

    // ── 永远不导出的系统 key ─────────────────────────────────
    private val EXCLUDED_KEYS = setOf(
        "install_time",                     // decikeeper_rules
        "first_setup_timestamp_v2",          // decikeeper_ledger
        "has_manually_ended_adaptation",      // decikeeper_ledger
        "auto_start_confirmed",               // decikeeper_ledger 权限确认
        "auto_start_confirmed_at",            // decikeeper_ledger
        "bg_popup_confirmed",                 // decikeeper_ledger 权限确认
        "bg_popup_confirmed_at",              // decikeeper_ledger
        "assoc_start_confirmed",              // decikeeper_ledger 权限确认
        "assoc_start_confirmed_at",           // decikeeper_ledger
        "bg_battery_confirmed",               // decikeeper_ledger 后台高耗电确认
        "bg_battery_confirmed_at",            // decikeeper_ledger
        "bg_battery_permission_confirmed",   // decikeeper_ledger
        "recents_lock_confirmed",             // decikeeper_ledger 多任务卡片加锁
        "recents_lock_confirmed_at",          // decikeeper_ledger
        "sleep_standby_confirmed",            // alarm_prefs 睡眠待机优化
        "vivo_sleep_standby_confirmed",       // alarm_prefs
        "lock_screen_display_confirmed",      // alarm_prefs
        "manual_permission_last_ask_auto_start",
        "manual_permission_last_ask_bg_popup",
        "manual_permission_last_ask_assoc_start",
        "manual_permission_last_ask_bg_battery",
        "manual_permission_prompt_primed",
        "manual_permission_settings_visit_at_auto_start",
        "manual_permission_settings_visit_at_bg_popup",
        "manual_permission_settings_visit_at_assoc_start",
        "manual_permission_settings_visit_at_bg_battery"
    )

    private val CORE_PREF_NAMES = listOf(
        "decikeeper_rules",
        "decikeeper_rules_pending",
        "decikeeper_rules_baseline",
        "decikeeper_probation",
        "decikeeper_ledger",
        "decikeeper_pending_apps",
        "decikeeper_changelog",
        "pending_config_prefs",
        "app_window_rules",
        "app_window_pending",
        "app_window_baseline",
        "app_window_promotion",
        "effective_activation_policy",
        "anti_uninstall_prefs",
        "alarm_prefs",
        "alarm_bookmarks",
        "rule_protection_ledger",
        "alarm_stats_ledger",
        "statistics_prefs",
        "statistics_retention_prefs"
    )

    private val deviceProtectedMirrorNames = setOf(
        "decikeeper_rules",
        "decikeeper_rules_pending",
        "decikeeper_rules_baseline",
        "decikeeper_ledger",
        "app_window_rules",
        "app_window_pending",
        "app_window_baseline"
    )

    private fun getDeviceProtectedPrefs(context: Context, name: String): SharedPreferences? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                context.createDeviceProtectedStorageContext()?.getSharedPreferences(name, Context.MODE_PRIVATE)
            } else null
        } catch (_: Throwable) {
            null
        }
    }

    private fun writeDeviceProtectedMirror(context: Context, targetName: String, values: Map<String, Any?>) {
        if (!deviceProtectedMirrorNames.contains(targetName)) return
        getDeviceProtectedPrefs(context, targetName)?.let { dpPrefs ->
            writePrefs(dpPrefs, values)
        }
    }

    private fun clearDeviceProtectedMirror(context: Context, targetName: String) {
        if (!deviceProtectedMirrorNames.contains(targetName)) return
        getDeviceProtectedPrefs(context, targetName)?.edit()?.clear()?.apply()
    }

    data class OperationResult(
        val fileCount: Int,
        val entryCount: Int
    )

    fun createSuggestedFileName(nowMillis: Long = System.currentTimeMillis()): String {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(nowMillis))
        return "DeciKeeper_Settings_$stamp.json"
    }

    /** 判断是否属于当前业务日/运行时的瞬态使用量键，禁止导出和覆盖今日实时消耗 */
    internal fun isTransientTodayLedgerKey(key: String, todayDateStr: String): Boolean {
        if (key in setOf(
                "free_time_end",
                "cool_down_end_timestamp",
                "is_privilege_mode",
                "record_date_str"
            ) || key.startsWith("privilege_session_")
        ) {
            return true
        }
        if (todayDateStr.isNotEmpty()) {
            if (key.contains("_${todayDateStr}_") || key.endsWith("_${todayDateStr}")) {
                return true
            }
        }
        return false
    }

    // ── 导出 ─────────────────────────────────────────────────

    /** 导出全部类别（向后兼容） */
    fun exportToUri(context: Context, uri: Uri): OperationResult =
        exportToUri(context, uri, BackupCategory.ALL)

    /** 按指定类别导出 */
    fun exportToUri(context: Context, uri: Uri, categories: List<BackupCategory>): OperationResult {
        val json = buildBackupJson(context, categories)
        context.contentResolver.openOutputStream(uri, "wt")?.use { stream ->
            stream.write(json.toByteArray(Charsets.UTF_8))
        } ?: error("无法打开导出文件")
        return inspectBackupJson(json)
    }

    /** 按指定类别构建备份 JSON（向后兼容：无参数时默认全部） */
    fun buildBackupJson(context: Context, categories: List<BackupCategory> = BackupCategory.ALL): String {
        // 收集选定分类涉及的所有 pref 文件名及其 key 允许集
        val allowedFiles = mutableMapOf<String, Set<String>?>() // null = 全部 key
        val allSelected = categories.size == BackupCategory.entries.size

        categories.forEach { cat ->
            // 独占文件
            CATEGORY_EXCLUSIVE_FILES[cat]?.forEach { name ->
                if (allSelected) allowedFiles[name] = null
                else allowedFiles.put(name, null) // 整个文件
            }
            // 共享文件的 key 过滤
            CATEGORY_SHARED_FILE_KEYS[cat]?.forEach { (name, keys) ->
                if (allSelected) {
                    allowedFiles.put(name, null)
                } else {
                    val existing = allowedFiles[name] ?: emptySet()
                    allowedFiles[name] = existing + keys.toSet()
                }
            }
        }

        // 如果全选，直接导出全部 pref 文件（保持原有行为）
        val names = if (allSelected) {
            discoverPreferenceNames(context)
        } else {
            allowedFiles.keys.toList()
        }

        val files = JSONArray()
        var entryCount = 0

        names.forEach { name ->
            val allValues = readMergedPreferenceValues(context, name)
            val allowed = allowedFiles[name]  // null 表示全部 key
            val values = (if (allowed == null) {
                allValues.filter { (key, _) -> key !in EXCLUDED_KEYS }
            } else {
                filterKeys(allValues, allowed)
            }).let { map ->
                if (name == "decikeeper_ledger") {
                    val effectiveTime = (map["weekly_privilege_limit_effective_time"] as? Long) ?: 0L
                    val isAdaptation = TimeRuleManager.isInAdaptationPeriod(context)
                    val isExpired = effectiveTime in 1..System.currentTimeMillis()
                    val filteredWeekly = if (isAdaptation || isExpired) {
                        map.filterKeys { it !in setOf(
                            "weekly_privilege_count_limit_pending",
                            "weekly_privilege_duration_limit_pending",
                            "weekly_privilege_limit_effective_time"
                        ) }
                    } else map
                    val todayStr = CreditManager.getBusinessDateString(context)
                    filteredWeekly.filterKeys { !isTransientTodayLedgerKey(it, todayStr) }
                } else map
            }
            if (values.isEmpty()) return@forEach

            val entries = encodeEntries(values)
            entryCount += entries.length()

            files.put(JSONObject().apply {
                put("name", name)
                put("entries", entries)
            })
        }

        val alarmWakeRecords = if (categories.contains(BackupCategory.LEDGER)) {
            JSONArray().also { array ->
                AlarmWakeStatsRecorder.exportRecords(context).forEach { record ->
                    array.put(record.toBackupJson())
                }
            }
        } else {
            null
        }
        val restRecords = if (categories.contains(BackupCategory.LEDGER)) {
            JSONArray().also { array ->
                RestStatsRecorder.getAllRecords(context).forEach { record ->
                    array.put(record.toBackupJson())
                }
            }
        } else {
            null
        }

        return JSONObject().apply {
            put("type", BACKUP_TYPE)
            put("schemaVersion", SCHEMA_VERSION)
            put("appPackage", context.packageName)
            put("exportedAt", System.currentTimeMillis())
            put("fileCount", files.length())
            put("entryCount", entryCount)
            put("preferenceFiles", files)
            // 记录导出了哪些分类（用于导入时默认选中）
            put("exportCategories", JSONArray(categories.map { it.id }))
            if (alarmWakeRecords != null) {
                put("alarmWakeRecordsVersion", ALARM_WAKE_RECORDS_VERSION)
                put(ALARM_WAKE_RECORDS_KEY, alarmWakeRecords)
                put("alarmWakeRecordCount", alarmWakeRecords.length())
            }
            if (restRecords != null) {
                put("restUsageRecordsVersion", REST_RECORDS_VERSION)
                put(REST_RECORDS_KEY, restRecords)
                put("restUsageRecordCount", restRecords.length())
            }
            if (categories.contains(BackupCategory.ALARM)) {
                val banksJson = CustomQuestionBankManager.exportToJson(context)
                if (!banksJson.isNullOrBlank()) {
                    put("customQuestionBanks", banksJson)
                }
            }
        }.toString(2)
    }

    /** 按 key 允许集过滤 map（支持 * 前缀匹配） */
    private fun filterKeys(values: Map<String, *>, allowed: Set<String>): Map<String, Any?> {
        val exactKeys = allowed.filter { !it.endsWith("*") }.toSet()
        val prefixes = allowed.filter { it.endsWith("*") }.map { it.removeSuffix("*") }
        return values.filter { (key, _) ->
            !EXCLUDED_KEYS.contains(key) && (
                exactKeys.contains(key) || prefixes.any { key.startsWith(it) }
            )
        }
    }

    // ── 导入 ─────────────────────────────────────────────────

    /** 解析备份文件，返回其中包含的分类列表 */
    fun getCategoriesInBackup(json: String): List<BackupCategory> {
        return try {
            val root = JSONObject(json)
            val arr = root.optJSONArray("exportCategories")
            if (arr != null) {
                (0 until arr.length()).mapNotNull { i ->
                    BackupCategory.fromId(arr.optString(i, ""))
                }
            } else {
                // 旧版备份无分类信息 → 按文件名推断
                inferCategoriesFromFiles(root, root.optJSONArray("preferenceFiles"))
            }
        } catch (e: Exception) {
            BackupCategory.ALL
        }
    }

    private fun inferCategoriesFromFiles(root: JSONObject, files: JSONArray?): List<BackupCategory> {
        if (files == null) return BackupCategory.ALL
        val names = (0 until files.length()).mapNotNull { i ->
            files.optJSONObject(i)?.optString("name", null)?.let { mapLegacyPrefName(it) }
        }.toSet()
        val hasCustomBanks = root.has("customQuestionBanks")
        return BackupCategory.entries.filter { cat ->
            if (cat == BackupCategory.ALARM && hasCustomBanks) return@filter true
            val exclusive = CATEGORY_EXCLUSIVE_FILES[cat].orEmpty()
            val shared = CATEGORY_SHARED_FILE_KEYS[cat].orEmpty().keys
            (exclusive + shared).any { it in names }
        }
    }

    /** 全部类别导入（向后兼容） */
    fun importFromUri(context: Context, uri: Uri): OperationResult =
        importFromUri(context, uri, BackupCategory.ALL)

    /** 按指定类别导入 */
    fun importFromUri(context: Context, uri: Uri, categories: List<BackupCategory>): OperationResult {
        val json = context.contentResolver.openInputStream(uri)?.use { stream ->
            stream.bufferedReader(Charsets.UTF_8).readText()
        } ?: error("无法读取导入文件")
        return applyBackupJson(context, json, categories)
    }

    /** 全部类别应用（向后兼容） */
    fun applyBackupJson(context: Context, json: String): OperationResult =
        applyBackupJson(context, json, BackupCategory.ALL)

    /** 按指定类别应用备份 */
    fun applyBackupJson(context: Context, json: String, categories: List<BackupCategory>): OperationResult {
        val root = parseAndValidateRoot(json)
        val exportedAt = root.optLong("exportedAt", 0L)
        val files = root.getJSONArray("preferenceFiles")
        // 先完整校验文件名和每个编码值，再开始写入，避免损坏备份只恢复一半。
        validatePreferenceEntries(files)
        val isAdaptation = TimeRuleManager.isInAdaptationPeriod(context)
        val writtenTargets = mutableSetOf<String>()
        var fileCount = 0
        var entryCount = 0

        // 收集选定分类涉及的 pref 文件名及其允许的 keys（null 代表该文件为独占，允许全部 key）
        val allowedFileKeys = mutableMapOf<String, Set<String>?>()
        val allSelected = categories.size == BackupCategory.entries.size

        categories.forEach { cat ->
            CATEGORY_EXCLUSIVE_FILES[cat].orEmpty().forEach { allowedFileKeys[it] = null }
            CATEGORY_SHARED_FILE_KEYS[cat].orEmpty().forEach { (name, keys) ->
                if (allowedFileKeys[name] == null && allowedFileKeys.containsKey(name)) {
                    // 已经是独占文件，保持 null
                } else {
                    val existing = allowedFileKeys[name] ?: emptySet()
                    allowedFileKeys[name] = existing + keys.toSet()
                }
            }
        }

        // 构建备份中所有文件条目的索引（用于 active-pending 配对检测）
        val fileEntryMap = mutableMapOf<String, JSONObject>()
        for (i in 0 until files.length()) {
            try {
                val file = files.getJSONObject(i)
                val n = mapLegacyPrefName(file.getString("name"))
                validatePreferenceName(n)
                fileEntryMap[n] = file
            } catch (_: Exception) { continue }
        }

        // active → pending 配对映射
        val activePendingPairs = mapOf(
            "decikeeper_rules" to "decikeeper_rules_pending",
            "app_window_rules" to "app_window_pending"
        )

        val processedNames = mutableSetOf<String>()
        val importTime = System.currentTimeMillis()

        // ── 处理 active-pending 配对 ──
        for ((activeName, pendingName) in activePendingPairs) {
            val activeFile = fileEntryMap[activeName] ?: continue
            val pendingFile = fileEntryMap[pendingName]
            if (!allowedFileKeys.containsKey(activeName) && !allowedFileKeys.containsKey(pendingName)) continue

            processedNames.add(activeName)
            processedNames.add(pendingName)

            if (isAdaptation) {
                // 适应期：直接写入 active，不处理延迟
                val rawValues = try { decodeEntries(activeFile.getJSONObject("entries")) } catch (_: Exception) { continue }
                val values = if (!allSelected && allowedFileKeys[activeName] != null) {
                    filterKeys(rawValues, allowedFileKeys[activeName]!!)
                } else {
                    rawValues
                }
                if (values.isEmpty()) continue
                if (writtenTargets.add(activeName)) {
                    writePrefs(context.getSharedPreferences(activeName, Context.MODE_PRIVATE), values)
                    writeDeviceProtectedMirror(context, activeName, values)
                    fileCount++; entryCount += values.size
                }
                continue
            }

            val rawActiveValues = try { decodeEntries(activeFile.getJSONObject("entries")) } catch (_: Exception) { continue }
            val activeValues = if (!allSelected && allowedFileKeys[activeName] != null) {
                filterKeys(rawActiveValues, allowedFileKeys[activeName]!!)
            } else {
                rawActiveValues
            }

            if (pendingFile != null) {
                // 备份中同时有 active 和 pending → 时间感知导入
                val pendingValues = try { decodeEntries(pendingFile.getJSONObject("entries")) } catch (_: Exception) { null }
                val effectiveTs = pendingValues?.get("effective_timestamp") as? Long ?: 0L
                val hasPendingFlag = pendingValues?.get("has_pending_changes") as? Boolean ?: false
                val isStaleBeforeExport = exportedAt > 0L && effectiveTs <= exportedAt

                if (hasPendingFlag && effectiveTs > 0 && importTime >= effectiveTs && !isStaleBeforeExport) {
                    // 导入时已过生效时间 → pending 直接变成 active
                    val targetValues = pendingValues!!.filterKeys {
                        it !in setOf("has_pending_changes", "effective_timestamp", "has_pending_apps", "has_pending_segments", "effective_time")
                    }
                    if (writtenTargets.add(activeName)) {
                        val normalizedValues = normalizeEntryKeysForTarget(activeName, targetValues)
                        writePrefs(context.getSharedPreferences(activeName, Context.MODE_PRIVATE), normalizedValues)
                        writeDeviceProtectedMirror(context, activeName, normalizedValues)
                        fileCount++; entryCount += normalizedValues.size
                    }
                    // 清除 pending 文件（已生效）
                    if (writtenTargets.add(pendingName)) {
                        context.getSharedPreferences(pendingName, Context.MODE_PRIVATE).edit().clear().apply()
                        clearDeviceProtectedMirror(context, pendingName)
                    }
                    LogExporter.logEvent("Import", "[PAIR] $activeName: pending applied as active (effectiveTs=$effectiveTs <= now=$importTime)")
                } else if (hasPendingFlag && !isStaleBeforeExport) {
                    // 导入时未过生效时间 → 保留 active + pending 两层
                    if (writtenTargets.add(activeName)) {
                        val normalizedActive = normalizeEntryKeysForTarget(activeName, activeValues)
                        writePrefs(context.getSharedPreferences(activeName, Context.MODE_PRIVATE), normalizedActive)
                        writeDeviceProtectedMirror(context, activeName, normalizedActive)
                        fileCount++; entryCount += normalizedActive.size
                    }
                    if (writtenTargets.add(pendingName)) {
                        val normalizedPending = normalizeEntryKeysForTarget(pendingName, pendingValues!!)
                        writePrefs(context.getSharedPreferences(pendingName, Context.MODE_PRIVATE), normalizedPending)
                        writeDeviceProtectedMirror(context, pendingName, normalizedPending)
                        fileCount++; entryCount += normalizedPending.size
                    }
                    LogExporter.logEvent("Import", "[PAIR] $activeName: pending preserved (effectiveTs=$effectiveTs > now=$importTime)")
                } else {
                    // 无明显 pending → 根据生效策略区分宽严有别
                    val res = importPairActive(context, activeName, pendingName, activeValues, writtenTargets)
                    fileCount += res.fileCount
                    entryCount += res.entryCount
                }
            } else {
                // 备份中只有 active，没有 pending → 根据生效策略区分宽严有别
                val res = importPairActive(context, activeName, pendingName, activeValues, writtenTargets)
                fileCount += res.fileCount
                entryCount += res.entryCount
            }
        }

        // ── 处理剩余文件（非 active-pending 配对）──
        for (name in fileEntryMap.keys) {
            if (name in processedNames) continue
            if (!allowedFileKeys.containsKey(name)) continue

            val file = fileEntryMap[name]!!
            if (name == "decikeeper_ledger") {
                val rawDecoded = try { decodeEntries(file.getJSONObject("entries")) } catch (_: Exception) { continue }
                val res = importDecikeeperLedger(
                    context = context,
                    rawDecoded = rawDecoded,
                    allowedKeys = allowedFileKeys[name],
                    allSelected = allSelected,
                    isAdaptation = isAdaptation,
                    exportedAt = exportedAt,
                    writtenTargets = writtenTargets
                )
                fileCount += res.fileCount
                entryCount += res.entryCount
                continue
            }

            val targetName = if (isAdaptation) name
            else resolveDelayedTargetName(name) ?: continue

            if (!writtenTargets.add(targetName)) continue

            val rawDecoded = try { decodeEntries(file.getJSONObject("entries")) } catch (_: Exception) { continue }
            val rawValues = (if (!allSelected && allowedFileKeys[name] != null) {
                filterKeys(rawDecoded, allowedFileKeys[name]!!)
            } else {
                rawDecoded
            }).filterKeys { it !in EXCLUDED_KEYS }
            if (rawValues.isEmpty()) continue

            fileCount++
            entryCount += rawValues.size

            val normalizedValues = normalizeEntryKeysForTarget(targetName, rawValues)
            writePrefs(context.getSharedPreferences(targetName, Context.MODE_PRIVATE), normalizedValues)
            writeDeviceProtectedMirror(context, targetName, normalizedValues)

            if (!isAdaptation) {
                setPendingActivationFlags(context, targetName)
            }
        }

        if (categories.contains(BackupCategory.ALARM)) {
            val banksJson = root.optString("customQuestionBanks", null)
            if (!banksJson.isNullOrBlank()) {
                CustomQuestionBankManager.importFromJson(context, banksJson)
            }
        }

        if (categories.contains(BackupCategory.LEDGER)) {
            if (root.has(ALARM_WAKE_RECORDS_KEY)) {
                // 新版备份显式携带 SQLite 明细；空数组也代表用户明确备份了空历史。
                val records = decodeAlarmWakeRecords(root.getJSONArray(ALARM_WAKE_RECORDS_KEY))
                AlarmWakeStatsRecorder.replaceAllRecords(context, records)
            } else {
                // 旧备份只有 SharedPreferences：prefs 写入完成后补做一次兼容迁移。
                AlarmWakeStatsRecorder.importLegacyBackupRecords(context)
            }
            if (root.has(REST_RECORDS_KEY)) {
                RestStatsRecorder.mergeRecords(context, decodeRestRecords(root.getJSONArray(REST_RECORDS_KEY)))
            }
        }

        invalidateRuntimeCaches()
        if (isAdaptation) {
            CreditManager.refreshAfterImport(
                context,
                exportedAt,
                normalizeDndUsage = categories.contains(BackupCategory.DND_SEGMENTS)
            )
        }
        return OperationResult(fileCount, entryCount)
    }

    private fun validatePreferenceEntries(files: JSONArray) {
        for (i in 0 until files.length()) {
            val file = files.getJSONObject(i)
            validatePreferenceName(file.getString("name"))
            decodeEntries(file.getJSONObject("entries"))
        }
    }

    private fun AlarmWakeRecord.toBackupJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("dateLabel", dateLabel)
        put("alarmMinuteOfDay", alarmMinuteOfDay)
        put("firstStageCompletedMinute", firstStageCompletedMinute)
        put("firstStageDurationMinutes", firstStageDurationMinutes)
        put("totalFlowDurationMinutes", totalFlowDurationMinutes)
        put("completedAt", completedAt)
        put("alarmId", alarmId)
    }

    private fun RestUsageRecord.toBackupJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("timestamp", timestamp)
        put("dateLabel", dateLabel)
        put("type", type)
        put("typeName", typeName)
        put("reason", reason)
        put("requestedMinutes", requestedMinutes)
        put("actualMinutes", actualMinutes)
        put("costMinutes", costMinutes)
        put("costCount", costCount)
        put("balanceMinutes", balanceMinutes)
        put("balanceCount", balanceCount)
    }

    private fun decodeAlarmWakeRecords(array: JSONArray): List<AlarmWakeRecord> {
        val records = ArrayList<AlarmWakeRecord>(array.length())
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i)
                ?: throw IllegalArgumentException("起床历史备份第 ${i + 1} 条不是对象")
            val id = obj.optString("id", "").trim()
            if (id.isEmpty()) {
                throw IllegalArgumentException("起床历史备份第 ${i + 1} 条缺少 id")
            }
            records += AlarmWakeRecord(
                id = id,
                dateLabel = obj.optString("dateLabel", ""),
                alarmMinuteOfDay = obj.optInt("alarmMinuteOfDay", 0),
                firstStageCompletedMinute = obj.optInt("firstStageCompletedMinute", 0),
                firstStageDurationMinutes = obj.optInt("firstStageDurationMinutes", 0),
                totalFlowDurationMinutes = obj.optInt("totalFlowDurationMinutes", 0),
                completedAt = obj.optLong("completedAt", 0L),
                alarmId = obj.optInt("alarmId", 0)
            )
        }
        return records
    }

    private fun decodeRestRecords(array: JSONArray): List<RestUsageRecord> {
        val records = ArrayList<RestUsageRecord>(array.length())
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i)
                ?: throw IllegalArgumentException("休息流水备份第 ${i + 1} 条不是对象")
            val id = obj.optString("id", "").trim()
            if (id.isEmpty()) {
                throw IllegalArgumentException("休息流水备份第 ${i + 1} 条缺少 id")
            }
            records += RestUsageRecord(
                id = id,
                timestamp = obj.optLong("timestamp", 0L),
                dateLabel = obj.optString("dateLabel", ""),
                type = obj.optString("type", "NORMAL"),
                typeName = obj.optString("typeName", "休息"),
                reason = obj.optString("reason", ""),
                requestedMinutes = obj.optDouble("requestedMinutes", 0.0),
                actualMinutes = obj.optDouble("actualMinutes", 0.0),
                costMinutes = obj.optDouble("costMinutes", 0.0),
                costCount = obj.optDouble("costCount", 0.0),
                balanceMinutes = obj.optDouble("balanceMinutes", 0.0),
                balanceCount = obj.optInt("balanceCount", 0)
            )
        }
        return records
    }

    fun inspectBackupJson(json: String): OperationResult {
        val root = parseAndValidateRoot(json)
        val files = root.getJSONArray("preferenceFiles")
        var entryCount = 0
        for (i in 0 until files.length()) {
            val file = files.getJSONObject(i)
            validatePreferenceName(file.getString("name"))
            entryCount += file.getJSONObject("entries").length()
        }
        return OperationResult(files.length(), entryCount)
    }

    // ── 非适应期导入路由 ─────────────────────────────────────

    private fun resolveDelayedTargetName(originalName: String): String? {
        return when (originalName) {
            "decikeeper_rules" -> "decikeeper_rules_pending"
            "app_window_rules" -> "app_window_pending"
            "pending_config_prefs" -> "pending_config_prefs"
            "decikeeper_pending_apps" -> "decikeeper_pending_apps"
            "decikeeper_rules_pending" -> null
            "app_window_pending" -> null
            "decikeeper_rules_baseline" -> null
            "app_window_baseline" -> null
            "decikeeper_ledger" -> null
            "decikeeper_changelog" -> null
            "decikeeper_probation" -> null
            "app_window_promotion" -> null
            "rule_protection_ledger", "alarm_stats_ledger", "statistics_prefs",
            "effective_activation_policy", "anti_uninstall_prefs",
            "alarm_prefs", "alarm_bookmarks" -> originalName
            else -> originalName
        }
    }

    /**
     * 将旧版备份中的 prefs 键名映射为当前 DeciKeeper 键名（导入即迁移）。
     * 历史品牌名：知止 (ZhiZhi) 沿用 iron_will_* 系列；IronWill 时代同为 iron_will_*。
     */
    private fun mapLegacyPrefName(name: String): String = when (name) {
        "iron_will_rules" -> "decikeeper_rules"
        "iron_will_rules_pending" -> "decikeeper_rules_pending"
        "iron_will_rules_baseline" -> "decikeeper_rules_baseline"
        "iron_will_probation" -> "decikeeper_probation"
        "iron_will_ledger" -> "decikeeper_ledger"
        "iron_will_pending_apps" -> "decikeeper_pending_apps"
        "iron_will_changelog" -> "decikeeper_changelog"
        "iron_will_oem_guide" -> "decikeeper_oem_guide"
        "iron_will_permissions" -> "decikeeper_permissions"
        "iron_will_promotion" -> "decikeeper_promotion"
        "iron_will_startup_state" -> "decikeeper_startup_state"
        "iron_will_lock_window_state" -> "decikeeper_lock_window_state"
        "iron_will_rules_log" -> "decikeeper_rules_log"
        else -> name
    }

    private fun normalizeEntryKeysForTarget(targetName: String, values: Map<String, Any?>): Map<String, Any?> {
        if (targetName == "app_window_pending") {
            val result = values.toMutableMap()
            if (result.containsKey("rules_json") && !result.containsKey("pending_rules_json")) {
                result["pending_rules_json"] = result["rules_json"]
            }
            return result
        } else if (targetName == "app_window_rules") {
            val result = values.toMutableMap()
            if (result.containsKey("pending_rules_json") && !result.containsKey("rules_json")) {
                result["rules_json"] = result["pending_rules_json"]
            }
            return result
        }
        return values
    }

    private fun importPairActive(
        context: Context,
        activeName: String,
        pendingName: String,
        activeValues: Map<String, Any?>,
        writtenTargets: MutableSet<String>
    ): OperationResult {
        if (!writtenTargets.add(activeName)) return OperationResult(0, 0)

        val policy = EffectiveActivationPolicyManager.getPolicy(context)
        val isFavorable = if (policy == EffectiveActivationPolicyManager.Policy.ALL_NEXT_DAY) {
            false
        } else {
            when (activeName) {
                "app_window_rules" -> isAppWindowImportPurelyFavorable(context, activeValues)
                "decikeeper_rules" -> isDecikeeperRulesImportPurelyFavorable(context, activeValues)
                else -> false
            }
        }

        if (isFavorable) {
            val cleanValues = activeValues.filterKeys {
                it !in setOf("has_pending_changes", "effective_timestamp", "has_pending_apps", "has_pending_segments", "effective_time")
            }
            val normalizedValues = normalizeEntryKeysForTarget(activeName, cleanValues)
            writePrefs(context.getSharedPreferences(activeName, Context.MODE_PRIVATE), normalizedValues)
            writeDeviceProtectedMirror(context, activeName, normalizedValues)

            // 清理并标记 pendingName，防止幽灵 pending
            if (writtenTargets.add(pendingName)) {
                context.getSharedPreferences(pendingName, Context.MODE_PRIVATE).edit().clear().apply()
                clearDeviceProtectedMirror(context, pendingName)
            }

            when (activeName) {
                "app_window_rules" -> {
                    AppWindowManager.invalidateCache()
                    if (AppWindowManager.getBaselineRules(context).isEmpty()) {
                        AppWindowManager.saveBaseline(context)
                    }
                    ChangeLogManager.addEntry(context, "app_window_rules", "导入应用时间窗规则（加严规则已立即生效）", isAdverse = false)
                }
                "decikeeper_rules" -> {
                    TimeRuleManager.invalidateSegmentsCache()
                    if (TimeRuleManager.getBaselineSegments(context).isNullOrEmpty()) {
                        TimeRuleManager.saveBaselineSnapshot(context)
                    }
                    ChangeLogManager.addEntry(context, "decikeeper_rules", "导入时段与规则参数（加严规则已立即生效）", isAdverse = false)
                }
            }
            LogExporter.logEvent("Import", "[PAIR] $activeName: purely favorable import applied immediately as active")
            return OperationResult(1, normalizedValues.size)
        } else {
            val targetName = resolveDelayedTargetName(activeName) ?: return OperationResult(0, 0)
            if (!writtenTargets.add(targetName)) return OperationResult(0, 0)

            val normalizedValues = normalizeEntryKeysForTarget(targetName, activeValues)
            writePrefs(context.getSharedPreferences(targetName, Context.MODE_PRIVATE), normalizedValues)
            writeDeviceProtectedMirror(context, targetName, normalizedValues)
            setPendingActivationFlags(context, targetName)
            LogExporter.logEvent("Import", "[PAIR] $activeName: imported to $targetName (delayed)")
            return OperationResult(1, normalizedValues.size)
        }
    }

    private fun importDecikeeperLedger(
        context: Context,
        rawDecoded: Map<String, Any?>,
        allowedKeys: Set<String>?,
        allSelected: Boolean,
        isAdaptation: Boolean,
        exportedAt: Long,
        writtenTargets: MutableSet<String>
    ): OperationResult {
        val targetName = "decikeeper_ledger"
        if (!writtenTargets.add(targetName)) return OperationResult(0, 0)

        val rawValues = if (!allSelected && allowedKeys != null) {
            filterKeys(rawDecoded, allowedKeys)
        } else {
            rawDecoded
        }
        if (rawValues.isEmpty()) return OperationResult(0, 0)

        val localPrefs = context.getSharedPreferences(targetName, Context.MODE_PRIVATE)
        val todayStr = CreditManager.getBusinessDateString(context)
        val finalValues = mutableMapOf<String, Any?>()

        // 1. 周特权上限规则参数（weekly_privilege_count_limit_v2 / weekly_privilege_duration_limit_v2）
        val importedWeeklyCount = (rawValues["weekly_privilege_count_limit_v2"] as? Number)?.toInt()
        val importedWeeklyDuration = (rawValues["weekly_privilege_duration_limit_v2"] as? Number)?.toInt()

        if (importedWeeklyCount != null || importedWeeklyDuration != null) {
            if (isAdaptation) {
                // 适应期内直接生效
                if (importedWeeklyCount != null) finalValues["weekly_privilege_count_limit_v2"] = importedWeeklyCount
                if (importedWeeklyDuration != null) finalValues["weekly_privilege_duration_limit_v2"] = importedWeeklyDuration
            } else {
                // 非适应期：对比基准（或当前值），有利（调严/调小）立即生效，不利（调宽/调大）次周生效（下周一）
                val basPrefs = context.getSharedPreferences("decikeeper_rules_baseline", Context.MODE_PRIVATE)
                val basCount = if (basPrefs.contains("weekly_privilege_limit")) {
                    basPrefs.getInt("weekly_privilege_limit", 1)
                } else {
                    CreditManager.getWeeklyPrivilegeLimit(context)
                }
                val basDuration = if (basPrefs.contains("weekly_privilege_duration_limit")) {
                    basPrefs.getInt("weekly_privilege_duration_limit", 480)
                } else {
                    CreditManager.getWeeklyPrivilegeDurationLimit(context)
                }

                val targetCount = importedWeeklyCount ?: basCount
                val targetDuration = importedWeeklyDuration ?: basDuration
                val isAdverse = targetCount > basCount || targetDuration > basDuration

                if (isAdverse) {
                    // 不利修改：挂起至下周一，绝不放宽当前活跃上限
                    val nextMonday = ConfigPendingManager.calculateNextMondayEffectiveTime(context, System.currentTimeMillis())
                    CreditManager.setPendingWeeklyPrivilegeLimit(context, targetCount, targetDuration, nextMonday)
                } else {
                    // 有利修改（更严或恢复基准）：立即生效，清空 pending
                    finalValues["weekly_privilege_count_limit_v2"] = targetCount
                    finalValues["weekly_privilege_duration_limit_v2"] = targetDuration
                    CreditManager.clearPendingWeeklyPrivilegeLimit(context)
                }
            }
        }

        // 2. 遍历其余键
        for ((key, value) in rawValues) {
            if (key in EXCLUDED_KEYS) {
                continue // 严禁导入任何权限确认与设备特有键
            }
            if (key == "weekly_privilege_count_limit_v2" || key == "weekly_privilege_duration_limit_v2") {
                continue
            }
            if (isTransientTodayLedgerKey(key, todayStr)) {
                continue // 排除今日瞬态账本
            }
            if (key in setOf(
                    "weekly_privilege_count_limit_pending",
                    "weekly_privilege_duration_limit_pending",
                    "weekly_privilege_limit_effective_time"
                )
            ) {
                val effectiveTime = (rawValues["weekly_privilege_limit_effective_time"] as? Long) ?: 0L
                val isStale = isAdaptation || (exportedAt > 0L && effectiveTime in 1..exportedAt)
                if (isStale) continue
            }

            // 高水位防钻空子合并：特权已用次数、时长、上次使用天及时间戳
            if (key.startsWith("weekly_privilege_count_")) {
                val localCount = localPrefs.getInt(key, 0)
                val backupCount = (value as? Number)?.toInt() ?: 0
                finalValues[key] = maxOf(localCount, backupCount)
            } else if (key.startsWith("weekly_privilege_duration_used_")) {
                val localDur = localPrefs.getInt(key, 0)
                val backupDur = (value as? Number)?.toInt() ?: 0
                finalValues[key] = maxOf(localDur, backupDur)
            } else if (key == "weekly_privilege_last_used_day") {
                val localDay = localPrefs.getString(key, null)
                val backupDay = value as? String
                finalValues[key] = when {
                    localDay != null && backupDay != null -> maxOf(localDay, backupDay)
                    localDay != null -> localDay
                    else -> backupDay
                }
            } else if (key == "weekly_privilege_last_used") {
                val localTs = localPrefs.getLong(key, 0L)
                val backupTs = (value as? Number)?.toLong() ?: 0L
                finalValues[key] = maxOf(localTs, backupTs)
            } else {
                finalValues[key] = value
            }
        }

        // 黑名单分类同步逻辑（保留已有逻辑）
        val importedBlacklist = (finalValues["blacklist_apps_list"] as? Set<*>)?.filterIsInstance<String>()?.toSet()
        if (importedBlacklist != null) {
            val categoryPkgs = mutableSetOf<String>()
            for (cat in listOf("comm", "video", "game", "browser")) {
                categoryPkgs.addAll(CreditManager.getBlacklistCategory(cat))
            }
            val removedFromCategory = categoryPkgs.subtract(importedBlacklist)
            if (removedFromCategory.isNotEmpty()) {
                BlacklistStore.addUserRemovedApps(context, removedFromCategory)
            }
        }

        if (finalValues.isNotEmpty()) {
            val normalizedValues = normalizeEntryKeysForTarget(targetName, finalValues)
            writePrefs(localPrefs, normalizedValues)
            writeDeviceProtectedMirror(context, targetName, normalizedValues)
        }

        return OperationResult(1, finalValues.size)
    }

    internal fun isAppWindowImportPurelyFavorable(context: Context, activeValues: Map<String, Any?>): Boolean {
        val json = (activeValues["rules_json"] as? String)
            ?: (activeValues["pending_rules_json"] as? String)
            ?: return true
        val candidateRules = AppWindowManager.parseRulesFromJsonForPublic(json)
        return !AppWindowManager.isCandidateRulesAdverseAgainstBaseline(context, candidateRules)
    }

    internal fun isDecikeeperRulesImportPurelyFavorable(context: Context, activeValues: Map<String, Any?>): Boolean {
        // 1. 检查时段 segments_json
        val segmentsJson = activeValues["segments_json"] as? String
        if (!segmentsJson.isNullOrBlank()) {
            val candidateSegments = TimeRuleManager.parseSegmentsFromJson(segmentsJson)
            if (SegmentAdverseAnalysis.isAdverseRelativeToBaseline(context, candidateSegments)) {
                return false
            }
        }

        // 2. 检查全局参数
        val baselinePrefs = context.getSharedPreferences("decikeeper_rules_baseline", Context.MODE_PRIVATE)

        (activeValues["cool_down_rest_seconds"] as? Number)?.toInt()?.let { newRest ->
            val baseRest = if (baselinePrefs.contains("cool_down_rest_seconds")) {
                baselinePrefs.getInt("cool_down_rest_seconds", 120)
            } else {
                TimeRuleManager.getCoolDownRestSeconds(context)
            }
            if (newRest > baseRest) return false
        }

        (activeValues["cool_down_gap_seconds"] as? Number)?.toInt()?.let { newGap ->
            val baseGap = if (baselinePrefs.contains("cool_down_gap_seconds")) {
                baselinePrefs.getInt("cool_down_gap_seconds", 120)
            } else {
                TimeRuleManager.getCoolDownGapSeconds(context)
            }
            if (newGap < baseGap) return false
        }

        (activeValues["borrow_percentage"] as? Number)?.toInt()?.let { newBorrow ->
            val baseBorrow = if (baselinePrefs.contains("borrow_percentage")) {
                baselinePrefs.getInt("borrow_percentage", 50)
            } else {
                TimeRuleManager.getBorrowPercentage(context)
            }
            if (newBorrow > baseBorrow) return false
        }

        (activeValues[TimeRuleManager.KEY_TEMP_RESTRICTED_ACCESS_MAX_SECONDS] as? Number)?.toInt()?.let { newTemp ->
            val baseTemp = if (baselinePrefs.contains(TimeRuleManager.KEY_TEMP_RESTRICTED_ACCESS_MAX_SECONDS)) {
                baselinePrefs.getInt(TimeRuleManager.KEY_TEMP_RESTRICTED_ACCESS_MAX_SECONDS, 60)
            } else {
                TimeRuleManager.getTemporaryRestrictedAccessMaxSeconds(context)
            }
            if (newTemp > baseTemp) return false
        }

        val newRefresh = (activeValues["config_refresh_time"] ?: activeValues["refresh_time"]) as? String
        if (newRefresh != null) {
            val curRefresh = TimeRuleManager.getRefreshTime(context)
            if (newRefresh != curRefresh) return false
        }

        return true
    }

    private fun setPendingActivationFlags(context: Context, targetName: String) {
        val effectiveTime = ConfigPendingManager.calculateNextEffectiveTime(
            context, System.currentTimeMillis()
        )
        when (targetName) {
            "decikeeper_rules_pending" -> {
                context.getSharedPreferences(targetName, Context.MODE_PRIVATE)
                    .edit()
                    .putBoolean("has_pending_changes", true)
                    .putLong("effective_timestamp", effectiveTime)
                    .apply()
                ChangeLogManager.addEntry(
                    context,
                    "decikeeper_rules",
                    "导入时段与规则参数（将在刷新时刻生效）",
                    isAdverse = true
                )
            }
            "app_window_pending" -> {
                val prefs = context.getSharedPreferences(targetName, Context.MODE_PRIVATE)
                val json = prefs.getString("pending_rules_json", null) ?: prefs.getString("rules_json", null)
                val hasRealRules = !json.isNullOrBlank() && json != "[]"
                if (hasRealRules) {
                    prefs.edit()
                        .putBoolean("has_pending_changes", true)
                        .putLong("effective_timestamp", effectiveTime)
                        .apply()
                    ChangeLogManager.addEntry(
                        context,
                        "app_window_rules",
                        "导入应用时间窗规则（将在刷新时刻生效）",
                        isAdverse = true
                    )
                } else {
                    prefs.edit().clear().apply()
                }
            }
            "decikeeper_pending_apps" -> {
                context.getSharedPreferences(targetName, Context.MODE_PRIVATE)
                    .edit()
                    .putBoolean("has_pending_apps", true)
                    .putLong("effective_timestamp", effectiveTime)
                    .apply()
            }
            "pending_config_prefs" -> {
                val prefs = context.getSharedPreferences(targetName, Context.MODE_PRIVATE)
                val editor = prefs.edit()
                val suffix = "_effective_time"
                prefs.all.keys.forEach { key ->
                    if (key.endsWith(suffix)) {
                        editor.putLong(key, effectiveTime)
                    }
                }
                editor.apply()
            }
        }
    }

    // ── JSON 解析与序列化 ────────────────────────────────────

    private fun parseAndValidateRoot(json: String): JSONObject {
        val root = JSONObject(json)
        val type = root.optString("type")
        require(type == BACKUP_TYPE || type == LEGACY_BACKUP_TYPE || type == LEGACY_ZHIZHI_BACKUP_TYPE) { "这不是决定守护设置备份文件" }
        val version = root.optInt("schemaVersion", 0)
        require(version in 1..SCHEMA_VERSION) { "备份版本不兼容 (v$version, 当前支持 v1-v$SCHEMA_VERSION)" }
        require(root.has("preferenceFiles")) { "备份文件缺少 preferenceFiles" }
        return root
    }

    private fun discoverPreferenceNames(context: Context): List<String> {
        val names = LinkedHashSet<String>()
        names.addAll(CORE_PREF_NAMES)
        context.filesDir.parentFile?.let { dataDir ->
            collectPreferenceFileNames(File(dataDir, "shared_prefs"), names)
        }
        collectPreferenceFileNames(File(context.filesDir, "shared_prefs"), names)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val dpContext = context.createDeviceProtectedStorageContext()
            dpContext.filesDir.parentFile?.let { dataDir ->
                collectPreferenceFileNames(File(dataDir, "shared_prefs"), names)
            }
            collectPreferenceFileNames(File(dpContext.filesDir, "shared_prefs"), names)
        }
        return names.filter { isValidPreferenceName(it) }
    }

    private fun collectPreferenceFileNames(dir: File, out: MutableSet<String>) {
        dir.listFiles()
            ?.filter { it.isFile && it.name.endsWith(".xml") }
            ?.mapTo(out) { it.name.removeSuffix(".xml") }
    }

    private fun readMergedPreferenceValues(context: Context, name: String): Map<String, *> {
        val merged = linkedMapOf<String, Any?>()
        getDeviceProtectedPrefs(context, name)?.let { merged.putAll(it.all) }
        merged.putAll(context.getSharedPreferences(name, Context.MODE_PRIVATE).all)
        return merged
    }

    private fun validatePreferenceName(name: String) {
        require(isValidPreferenceName(name)) { "备份中包含非法设置文件名: $name" }
    }

    private fun isValidPreferenceName(name: String): Boolean {
        return name.isNotBlank() && name.matches(Regex("[A-Za-z0-9_.-]+"))
    }

    private fun encodeEntries(values: Map<String, *>): JSONObject {
        val entries = JSONObject()
        values.toSortedMap().forEach { (key, value) ->
            entries.put(key, encodeValue(value))
        }
        return entries
    }

    private fun encodeValue(value: Any?): JSONObject {
        return JSONObject().apply {
            when (value) {
                is String -> { put("type", "String"); put("value", value) }
                is Int -> { put("type", "Int"); put("value", value) }
                is Long -> { put("type", "Long"); put("value", value) }
                is Float -> { put("type", "Float"); put("value", value.toDouble()) }
                is Boolean -> { put("type", "Boolean"); put("value", value) }
                is Set<*> -> {
                    put("type", "StringSet")
                    put("value", JSONArray(value.filterIsInstance<String>().sorted()))
                }
                null -> { put("type", "Null"); put("value", JSONObject.NULL) }
                else -> error("不支持的设置类型: ${value.javaClass.name}")
            }
        }
    }

    private fun decodeEntries(entries: JSONObject): Map<String, Any?> {
        val values = linkedMapOf<String, Any?>()
        entries.keys().forEach { key ->
            values[key] = decodeValue(entries.getJSONObject(key))
        }
        return values
    }

    private fun decodeValue(encoded: JSONObject): Any? {
        return when (encoded.getString("type")) {
            "String" -> encoded.getString("value")
            "Int" -> encoded.getInt("value")
            "Long" -> encoded.getLong("value")
            "Float" -> encoded.getDouble("value").toFloat()
            "Boolean" -> encoded.getBoolean("value")
            "StringSet" -> {
                val array = encoded.getJSONArray("value")
                (0 until array.length()).map { array.getString(it) }.toSet()
            }
            "Null" -> null
            else -> error("备份中存在未知字段类型: ${encoded.getString("type")}")
        }
    }

    /**
     * 将导入的 key-value 合并写入目标 SharedPreferences。
     * 不 clear() 整个文件，避免删除备份中不存在的 key（如权限确认标记等 EXCLUDED_KEYS）。
     */
    private fun writePrefs(prefs: SharedPreferences, values: Map<String, Any?>) {
        val editor = prefs.edit()
        values.forEach { (key, value) ->
            when (value) {
                is String -> editor.putString(key, value)
                is Int -> editor.putInt(key, value)
                is Long -> editor.putLong(key, value)
                is Float -> editor.putFloat(key, value)
                is Boolean -> editor.putBoolean(key, value)
                is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toSet())
                null -> editor.remove(key)
                else -> error("无法写入设置类型: ${value.javaClass.name}")
            }
        }
        require(editor.commit()) { "写入设置失败: ${prefs.javaClass.simpleName}" }
    }

    private fun invalidateRuntimeCaches() {
        TimeRuleManager.invalidateCache()
        AppWindowManager.invalidateCache()
        CreditManager.invalidateCache()
    }
}
