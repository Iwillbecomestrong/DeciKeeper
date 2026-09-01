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
package com.ironwill.app.manager

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import com.ironwill.app.data.BlacklistStore
import com.ironwill.app.data.LogExporter
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.LinkedHashSet
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject


object SettingsBackupManager {
    private const val BACKUP_TYPE = "ironwill_settings_backup"
    private const val SCHEMA_VERSION = 2

    // ── 分类定义 ──────────────────────────────────────────────
    enum class BackupCategory(val id: String, val displayName: String) {
        DND_SEGMENTS("dnd_segments", "勿扰时段"),
        APP_WINDOWS("app_windows", "应用时间窗"),
        BLACKLIST("blacklist", "黑名单"),
        ALARM("alarm", "闹钟"),
        GLOBAL_PARAMS("global_params", "全局参数"),
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
            "iron_will_rules_pending", "iron_will_rules_baseline"
        ),
        BackupCategory.APP_WINDOWS to listOf(
            "app_window_rules", "app_window_pending", "app_window_baseline"
        ),
        BackupCategory.BLACKLIST to listOf(
            "iron_will_pending_apps"
        ),
        BackupCategory.ALARM to emptyList(), // 闹钟数据在共享文件中管理
        BackupCategory.GLOBAL_PARAMS to listOf(
            "pending_config_prefs"
        ),
        BackupCategory.LEDGER to emptyList() // 统计数据只含 iron_will_ledger 的部分 key
    )

    // ── 共享文件中的按分类 key 过滤 ──────────────────────────
    // key 如果以 * 结尾，按前缀匹配；否则精确匹配
    private val CATEGORY_SHARED_FILE_KEYS: Map<BackupCategory, Map<String, List<String>>> = mapOf(
        BackupCategory.DND_SEGMENTS to mapOf(
            "iron_will_rules" to listOf("segments_json")
        ),
        BackupCategory.BLACKLIST to mapOf(
            "iron_will_ledger" to listOf("blacklist_apps_list", "blacklist_mode", "user_removed_apps")
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
                "bookmarked_poems", "bookmarked_words"
            )
        ),
        BackupCategory.GLOBAL_PARAMS to mapOf(
            "iron_will_rules" to listOf(
                "cool_down_rest_seconds", "cool_down_gap_seconds",
                "borrow_percentage", "notify_advance_minutes",
                "notify_entry_near_minutes", "temp_restricted_access_max_seconds",
                "privilege_window_start", "privilege_window_end",
                "is_privilege_window_synced"
            ),
            "iron_will_ledger" to listOf(
                "config_refresh_time", "strict_penalty_duration",
                "weekly_privilege_count_limit_v2", "weekly_privilege_duration_limit_v2",
                "weekly_privilege_last_used_day", "weekly_privilege_last_used",
                "last_weekly_limit_apply_time_v2",
                "weekly_privilege_count_limit_pending",
                "weekly_privilege_duration_limit_pending",
                "weekly_privilege_limit_effective_time"
            )
        ),
        BackupCategory.LEDGER to mapOf(
            "iron_will_ledger" to listOf(
                "record_date_str",
                "free_time_end", "cool_down_end_timestamp",
                "is_privilege_mode",
                "privilege_session_start", "privilege_session_duration",
                "privilege_session_prededucted", "privilege_session_charged_minutes",
                "usage_*", "borrow_time_*", "privilege_usage_*",
                "privilege_*_segment_*",
                "weekly_privilege_count_*", "weekly_privilege_duration_used_*"
            )
        ),
        BackupCategory.APP_WINDOWS to emptyMap()
    )

    // ── 永远不导出的系统 key ─────────────────────────────────
    private val EXCLUDED_KEYS = setOf(
        "install_time",                     // iron_will_rules
        "first_setup_timestamp_v2",          // iron_will_ledger
        "has_manually_ended_adaptation",      // iron_will_ledger
        "auto_start_confirmed",               // iron_will_ledger 权限确认
        "auto_start_confirmed_at",            // iron_will_ledger
        "bg_popup_confirmed",                 // iron_will_ledger 权限确认
        "bg_popup_confirmed_at",              // iron_will_ledger
        "assoc_start_confirmed",              // iron_will_ledger 权限确认
        "assoc_start_confirmed_at",           // iron_will_ledger
        "manual_permission_last_ask_auto_start",
        "manual_permission_last_ask_bg_popup",
        "manual_permission_last_ask_assoc_start",
        "manual_permission_prompt_primed",
        "manual_permission_settings_visit_at_auto_start",
        "manual_permission_settings_visit_at_bg_popup",
        "manual_permission_settings_visit_at_assoc_start"
    )

    private val CORE_PREF_NAMES = listOf(
        "iron_will_rules",
        "iron_will_rules_pending",
        "iron_will_rules_baseline",
        "iron_will_probation",
        "iron_will_ledger",
        "iron_will_pending_apps",
        "iron_will_changelog",
        "pending_config_prefs",
        "app_window_rules",
        "app_window_pending",
        "app_window_baseline",
        "app_window_promotion",
        "effective_activation_policy",
        "anti_uninstall_prefs",
        "alarm_prefs",
        "alarm_bookmarks"
    )

    private val deviceProtectedMirrorNames = setOf(
        "iron_will_rules",
        "iron_will_rules_pending",
        "iron_will_rules_baseline",
        "iron_will_ledger",
        "app_window_rules",
        "app_window_pending",
        "app_window_baseline"
    )

    data class OperationResult(
        val fileCount: Int,
        val entryCount: Int
    )

    fun createSuggestedFileName(nowMillis: Long = System.currentTimeMillis()): String {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(nowMillis))
        return "IronWill_Settings_$stamp.json"
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
                if (name == "iron_will_ledger") {
                    val effectiveTime = (map["weekly_privilege_limit_effective_time"] as? Long) ?: 0L
                    val isAdaptation = TimeRuleManager.isInAdaptationPeriod(context)
                    val isExpired = effectiveTime in 1..System.currentTimeMillis()
                    if (isAdaptation || isExpired) {
                        map.filterKeys { it !in setOf(
                            "weekly_privilege_count_limit_pending",
                            "weekly_privilege_duration_limit_pending",
                            "weekly_privilege_limit_effective_time"
                        ) }
                    } else map
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
                inferCategoriesFromFiles(root.optJSONArray("preferenceFiles"))
            }
        } catch (e: Exception) {
            BackupCategory.ALL
        }
    }

    private fun inferCategoriesFromFiles(files: JSONArray?): List<BackupCategory> {
        if (files == null) return BackupCategory.ALL
        val names = (0 until files.length()).mapNotNull { i ->
            files.optJSONObject(i)?.optString("name", null)
        }.toSet()
        return BackupCategory.entries.filter { cat ->
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
        val isAdaptation = TimeRuleManager.isInAdaptationPeriod(context)
        val writtenTargets = mutableSetOf<String>()
        var fileCount = 0
        var entryCount = 0

        // 收集选定分类涉及的 pref 文件名
        val allowedFiles = mutableSetOf<String>()
        categories.forEach { cat ->
            CATEGORY_EXCLUSIVE_FILES[cat].orEmpty().forEach { allowedFiles.add(it) }
            CATEGORY_SHARED_FILE_KEYS[cat].orEmpty().keys.forEach { allowedFiles.add(it) }
        }

        // 构建备份中所有文件条目的索引（用于 active-pending 配对检测）
        val fileEntryMap = mutableMapOf<String, JSONObject>()
        for (i in 0 until files.length()) {
            try {
                val file = files.getJSONObject(i)
                val n = file.getString("name")
                validatePreferenceName(n)
                fileEntryMap[n] = file
            } catch (_: Exception) { continue }
        }

        // active → pending 配对映射
        val activePendingPairs = mapOf(
            "iron_will_rules" to "iron_will_rules_pending",
            "app_window_rules" to "app_window_pending"
        )

        val processedNames = mutableSetOf<String>()
        val importTime = System.currentTimeMillis()

        // ── 处理 active-pending 配对 ──
        for ((activeName, pendingName) in activePendingPairs) {
            val activeFile = fileEntryMap[activeName] ?: continue
            val pendingFile = fileEntryMap[pendingName]
            if (!allowedFiles.contains(activeName) && !allowedFiles.contains(pendingName)) continue

            processedNames.add(activeName)
            processedNames.add(pendingName)

            if (isAdaptation) {
                // 适应期：直接写入 active，不处理延迟
                val values = try { decodeEntries(activeFile.getJSONObject("entries")) } catch (_: Exception) { continue }
                if (writtenTargets.add(activeName)) {
                    writePrefs(context.getSharedPreferences(activeName, Context.MODE_PRIVATE), values)
                    if (deviceProtectedMirrorNames.contains(activeName)) {
                        writePrefs(context.createDeviceProtectedStorageContext().getSharedPreferences(activeName, Context.MODE_PRIVATE), values)
                    }
                    fileCount++; entryCount += values.size
                }
                continue
            }

            val activeValues = try { decodeEntries(activeFile.getJSONObject("entries")) } catch (_: Exception) { continue }

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
                        writePrefs(context.getSharedPreferences(activeName, Context.MODE_PRIVATE), targetValues)
                        if (deviceProtectedMirrorNames.contains(activeName)) {
                            writePrefs(context.createDeviceProtectedStorageContext().getSharedPreferences(activeName, Context.MODE_PRIVATE), targetValues)
                        }
                        fileCount++; entryCount += targetValues.size
                    }
                    // 清除 pending 文件（已生效）
                    if (writtenTargets.add(pendingName)) {
                        context.getSharedPreferences(pendingName, Context.MODE_PRIVATE).edit().clear().apply()
                        if (deviceProtectedMirrorNames.contains(pendingName)) {
                            context.createDeviceProtectedStorageContext().getSharedPreferences(pendingName, Context.MODE_PRIVATE).edit().clear().apply()
                        }
                    }
                    LogExporter.logEvent("Import", "[PAIR] $activeName: pending applied as active (effectiveTs=$effectiveTs <= now=$importTime)")
                } else if (hasPendingFlag && !isStaleBeforeExport) {
                    // 导入时未过生效时间 → 保留 active + pending 两层
                    if (writtenTargets.add(activeName)) {
                        writePrefs(context.getSharedPreferences(activeName, Context.MODE_PRIVATE), activeValues)
                        if (deviceProtectedMirrorNames.contains(activeName)) {
                            writePrefs(context.createDeviceProtectedStorageContext().getSharedPreferences(activeName, Context.MODE_PRIVATE), activeValues)
                        }
                        fileCount++; entryCount += activeValues.size
                    }
                    if (writtenTargets.add(pendingName)) {
                        writePrefs(context.getSharedPreferences(pendingName, Context.MODE_PRIVATE), pendingValues!!)
                        if (deviceProtectedMirrorNames.contains(pendingName)) {
                            writePrefs(context.createDeviceProtectedStorageContext().getSharedPreferences(pendingName, Context.MODE_PRIVATE), pendingValues)
                        }
                        fileCount++; entryCount += pendingValues.size
                    }
                    LogExporter.logEvent("Import", "[PAIR] $activeName: pending preserved (effectiveTs=$effectiveTs > now=$importTime)")
                } else {
                    // 无明显 pending → 按普通文件处理
                    if (writtenTargets.add(activeName)) {
                        val targetName = resolveDelayedTargetName(activeName) ?: continue
                        writePrefs(context.getSharedPreferences(targetName, Context.MODE_PRIVATE), activeValues)
                        if (deviceProtectedMirrorNames.contains(targetName)) {
                            writePrefs(context.createDeviceProtectedStorageContext().getSharedPreferences(targetName, Context.MODE_PRIVATE), activeValues)
                        }
                        fileCount++; entryCount += activeValues.size
                        setPendingActivationFlags(context, targetName)
                    }
                }
            } else {
                // 备份中只有 active，没有 pending → 旧行为
                if (writtenTargets.add(activeName)) {
                    val targetName = resolveDelayedTargetName(activeName) ?: continue
                    writePrefs(context.getSharedPreferences(targetName, Context.MODE_PRIVATE), activeValues)
                    if (deviceProtectedMirrorNames.contains(targetName)) {
                        writePrefs(context.createDeviceProtectedStorageContext().getSharedPreferences(targetName, Context.MODE_PRIVATE), activeValues)
                    }
                    fileCount++; entryCount += activeValues.size
                    setPendingActivationFlags(context, targetName)
                }
            }
        }

        // ── 处理剩余文件（非 active-pending 配对）──
        for (name in fileEntryMap.keys) {
            if (name in processedNames) continue
            if (!allowedFiles.contains(name)) continue

            val file = fileEntryMap[name]!!
            val targetName = if (isAdaptation) name
            else resolveDelayedTargetName(name) ?: continue

            if (!writtenTargets.add(targetName)) continue

            val rawValues = try { decodeEntries(file.getJSONObject("entries")) } catch (_: Exception) { continue }
            val values = if (targetName == "iron_will_ledger") {
                val effectiveTime = (rawValues["weekly_privilege_limit_effective_time"] as? Long) ?: 0L
                val isStale = isAdaptation || (exportedAt > 0L && effectiveTime in 1..exportedAt)
                val filtered = if (isStale) {
                    rawValues.filterKeys { it !in setOf(
                        "weekly_privilege_count_limit_pending",
                        "weekly_privilege_duration_limit_pending",
                        "weekly_privilege_limit_effective_time"
                    ) }
                } else rawValues

                // 确保用户在备份中没有包含的分类App（如用户从黑名单移除了Google/Chrome），同步到 user_removed_apps，
                // 防止导入后被 MonitorService 的 periodicAutoBlacklistScan 重新自动扫入黑名单！
                val importedBlacklist = (filtered["blacklist_apps_list"] as? Set<*>)?.filterIsInstance<String>()?.toSet()
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
                filtered
            } else rawValues

            fileCount++
            entryCount += values.size

            writePrefs(context.getSharedPreferences(targetName, Context.MODE_PRIVATE), values)
            if (deviceProtectedMirrorNames.contains(targetName)) {
                writePrefs(
                    context.createDeviceProtectedStorageContext()
                        .getSharedPreferences(targetName, Context.MODE_PRIVATE),
                    values
                )
            }

            if (!isAdaptation) {
                setPendingActivationFlags(context, targetName)
            }
        }

        invalidateRuntimeCaches()
        if (isAdaptation) {
            CreditManager.refreshAfterImport(context, exportedAt)
        }
        return OperationResult(fileCount, entryCount)
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
            "iron_will_rules" -> "iron_will_rules_pending"
            "app_window_rules" -> "app_window_pending"
            "pending_config_prefs" -> "pending_config_prefs"
            "iron_will_pending_apps" -> "iron_will_pending_apps"
            "iron_will_rules_pending" -> null
            "app_window_pending" -> null
            "iron_will_rules_baseline" -> null
            "app_window_baseline" -> null
            "iron_will_ledger" -> null
            "iron_will_changelog" -> null
            "iron_will_probation" -> null
            "app_window_promotion" -> null
            "effective_activation_policy", "anti_uninstall_prefs",
            "alarm_prefs", "alarm_bookmarks" -> originalName
            else -> originalName
        }
    }

    private fun setPendingActivationFlags(context: Context, targetName: String) {
        val effectiveTime = ConfigPendingManager.calculateNextEffectiveTime(
            context, System.currentTimeMillis()
        )
        when (targetName) {
            "iron_will_rules_pending" -> {
                context.getSharedPreferences(targetName, Context.MODE_PRIVATE)
                    .edit()
                    .putBoolean("has_pending_changes", true)
                    .putLong("effective_timestamp", effectiveTime)
                    .apply()
            }
            "app_window_pending" -> {
                context.getSharedPreferences(targetName, Context.MODE_PRIVATE)
                    .edit()
                    .putBoolean("has_pending_changes", true)
                    .putLong("effective_timestamp", effectiveTime)
                    .apply()
            }
            "iron_will_pending_apps" -> {
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
        require(root.optString("type") == BACKUP_TYPE) { "这不是 IronWill 设置备份文件" }
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val deviceProtectedPrefs = context.createDeviceProtectedStorageContext()
                .getSharedPreferences(name, Context.MODE_PRIVATE)
            merged.putAll(deviceProtectedPrefs.all)
        }
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
