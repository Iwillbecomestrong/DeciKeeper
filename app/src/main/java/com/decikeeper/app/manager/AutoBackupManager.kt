/**
 * =================================================================================
 * [Context Header]
 * * @file: AutoBackupManager.kt
 * * @role: Domain Skill
 * * @description: 应用内部私有隐藏快照容灾管理器，仅用于防范运行期数据损坏。
 * * @responsibilities:
 * 1. 30秒防抖或主动触发全量导出系统配置与统计账本（最新 + 上一版双快照）。
 * 2. 仅写入应用私有内部目录 (files/auto_backup/)，不向公共目录自动写入。
 * 3. 内部自动快照不作为用户备份展示，新装或重装不进行静默自动恢复。
 * =================================================================================
 */
package com.decikeeper.app.manager

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.decikeeper.app.data.LogExporter
import java.io.File
import java.util.concurrent.Executors

object AutoBackupManager {

    private const val TAG = "AutoBackupManager"
    const val BACKUP_FILE_NAME = "decikeeper_auto_backup.json"
    const val BACKUP_PREV_FILE_NAME = "decikeeper_auto_backup_prev.json"
    // 旧版（知止 ZhiZhi）快照文件名，仅用于内部快照回退解析兼容
    const val LEGACY_BACKUP_FILE_NAME = "zhizhi_auto_backup.json"
    const val LEGACY_BACKUP_PREV_FILE_NAME = "zhizhi_auto_backup_prev.json"
    const val INTERNAL_BACKUP_DIR = "auto_backup"
    const val DEFAULT_DEBOUNCE_DELAY_MS = 30_000L

    data class BackupResult(
        val internalWritten: Boolean,
        val errorCount: Int
    ) {
        val success: Boolean get() = internalWritten && errorCount == 0
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val backupExecutor = Executors.newSingleThreadExecutor()

    @Volatile
    private var pendingRunnable: Runnable? = null

    /**
     * 触发防抖自动备份（默认 30 秒内聚合一次落盘）。
     */
    fun triggerDebouncedBackup(context: Context, delayMs: Long = DEFAULT_DEBOUNCE_DELAY_MS) {
        val appContext = context.applicationContext
        synchronized(this) {
            pendingRunnable?.let { mainHandler.removeCallbacks(it) }
            val task = Runnable {
                backupExecutor.execute {
                    performBackup(appContext)
                }
            }
            pendingRunnable = task
            mainHandler.postDelayed(task, delayMs)
        }
    }

    /**
     * 立即执行一次自动备份（后台异步执行）。
     */
    fun forceBackupNow(context: Context) {
        val appContext = context.applicationContext
        backupExecutor.execute {
            performBackup(appContext)
        }
    }

    /**
     * 执行内部隐藏快照写入（仅写入内部私有目录 files/auto_backup/）。
     */
    fun performBackup(context: Context): BackupResult {
        var internalWritten = false
        var errorCount = 0
        try {
            val json = SettingsBackupManager.buildBackupJson(context, SettingsBackupManager.BackupCategory.ALL)
            if (json.isBlank()) {
                LogExporter.w(TAG, "[BACKUP] Snapshot was empty; internal backup skipped")
                return BackupResult(internalWritten = false, errorCount = 1)
            }

            try {
                val internalDir = File(context.filesDir, INTERNAL_BACKUP_DIR)
                writeSnapshotToDir(internalDir, json)
                internalWritten = true
                LogExporter.i(TAG, "[BACKUP] Internal auto backup snapshot written successfully")
            } catch (e: Exception) {
                errorCount++
                LogExporter.w(TAG, "[BACKUP] Failed writing to internal backup: ${e.message}")
            }
        } catch (e: Exception) {
            errorCount++
            LogExporter.w(TAG, "[BACKUP] Unexpected failure during auto backup: ${e.message}")
        }
        return BackupResult(internalWritten, errorCount)
    }

    /**
     * 将快照原子写入指定目录，并在写入前将旧文件归档为 _prev.json。
     */
    fun writeSnapshotToDir(dir: File, json: String) {
        if (!dir.exists()) {
            dir.mkdirs()
        }
        val currentFile = File(dir, BACKUP_FILE_NAME)
        val prevFile = File(dir, BACKUP_PREV_FILE_NAME)
        val tmpFile = File(dir, "$BACKUP_FILE_NAME.tmp")

        // 双快照保护：先归档前一份
        if (currentFile.exists() && currentFile.length() > 0) {
            try {
                currentFile.copyTo(prevFile, overwrite = true)
            } catch (e: Exception) {
                LogExporter.w(TAG, "[SNAPSHOT] Failed copying to prev backup: ${e.message}")
            }
        }

        // 写入临时文件后原子重命名
        tmpFile.writeText(json, Charsets.UTF_8)
        if (!tmpFile.renameTo(currentFile)) {
            // 在部分文件系统上跨分区 rename 可能失败，降级为覆盖写与删除
            tmpFile.copyTo(currentFile, overwrite = true)
            tmpFile.delete()
        }
    }

    /**
     * 判断当前应用是否属于空白全新安装。
     */
    fun isFreshInstall(context: Context): Boolean {
        val rulesPrefs = context.getSharedPreferences("decikeeper_rules", Context.MODE_PRIVATE)
        val ledgerPrefs = context.getSharedPreferences("decikeeper_ledger", Context.MODE_PRIVATE)
        val alarmPrefs = context.getSharedPreferences("alarm_prefs", Context.MODE_PRIVATE)
        val hasRules = rulesPrefs.contains("segments_json")
        val hasLedger = ledgerPrefs.contains("first_setup_timestamp_v2") || ledgerPrefs.contains("record_date_str")
        val hasAlarms = alarmPrefs.contains("alarms_json")
        return !hasRules && !hasLedger && !hasAlarms
    }

    /**
     * 从内部快照目录恢复（仅用于主数据损坏故障回退，不向用户弹 Toast）。
     */
    fun restoreInternalSnapshot(context: Context): Boolean {
        val internalDir = File(context.filesDir, INTERNAL_BACKUP_DIR)
        return restoreFromDirectory(context, internalDir)
    }

    /**
     * 从指定目录寻找最新快照或上一版快照并恢复（仅供内部故障回退与测试使用）。
     */
    fun restoreFromDirectory(context: Context, dir: File): Boolean {
        if (!dir.exists() || !dir.isDirectory) return false

        val candidates = listOf(
            File(dir, BACKUP_FILE_NAME),
            File(dir, BACKUP_PREV_FILE_NAME),
            File(dir, LEGACY_BACKUP_FILE_NAME),
            File(dir, LEGACY_BACKUP_PREV_FILE_NAME)
        )

        for (file in candidates) {
            if (file.exists() && file.isFile && file.length() > 0) {
                try {
                    val json = file.readText(Charsets.UTF_8)
                    if (json.isNotBlank()) {
                        val result = SettingsBackupManager.applyBackupJson(
                            context,
                            json,
                            SettingsBackupManager.BackupCategory.ALL
                        )
                        LogExporter.i(
                            TAG,
                            "[RESTORE] Internal snapshot restored from ${file.absolutePath} (${result.fileCount} files, ${result.entryCount} entries)"
                        )
                        return true
                    }
                } catch (e: Exception) {
                    LogExporter.w(TAG, "[RESTORE] Candidate ${file.name} failed: ${e.message}")
                }
            }
        }
        return false
    }
}
