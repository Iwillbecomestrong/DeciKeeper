/**
 * =================================================================================
 * [Context Header]
 * * @file: AutoBackupManager.kt
 * * @role: Domain Skill
 * * @description: 自动外部与内部双写备份管理器，在重装后提供静默自动恢复功能。
 * * @responsibilities:
 * 1. 30秒防抖或定时全量导出系统配置与统计账本（最新 + 上一版双快照）。
 * 2. 公共文档目录 (Documents/ZhiZhi/) 与内部私有目录双写保存，避免卸载被抹除。
 * 3. 新装/重装首次启动时，静默检测外部备份并自动恢复，提示轻量 Toast。
 * =================================================================================
 */
package com.zhizhi.app.manager

import android.content.Context
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.zhizhi.app.data.LogExporter
import java.io.File
import java.util.concurrent.Executors

object AutoBackupManager {

    private const val TAG = "AutoBackupManager"
    const val BACKUP_DIR_NAME = "ZhiZhi"
    const val BACKUP_FILE_NAME = "zhizhi_auto_backup.json"
    const val BACKUP_PREV_FILE_NAME = "zhizhi_auto_backup_prev.json"
    const val INTERNAL_BACKUP_DIR = "auto_backup"
    const val DEFAULT_DEBOUNCE_DELAY_MS = 30_000L

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
     * 同步或异步执行备份写入（公共目录 + 内部私有备份目录双写）。
     */
    fun performBackup(context: Context) {
        try {
            val json = SettingsBackupManager.buildBackupJson(context, SettingsBackupManager.BackupCategory.ALL)
            if (json.isBlank()) return

            // 1. 公共文档目录 (Documents/ZhiZhi/)
            try {
                val publicDocDir = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS),
                    BACKUP_DIR_NAME
                )
                writeSnapshotToDir(publicDocDir, json)
            } catch (e: Exception) {
                LogExporter.w(TAG, "[BACKUP] Failed writing to public documents: ${e.message}")
            }

            // 2. 应用内部私有备份目录 (files/auto_backup/)
            try {
                val internalDir = File(context.filesDir, INTERNAL_BACKUP_DIR)
                writeSnapshotToDir(internalDir, json)
            } catch (e: Exception) {
                LogExporter.w(TAG, "[BACKUP] Failed writing to internal backup: ${e.message}")
            }

            LogExporter.i(TAG, "[BACKUP] Auto backup snapshot completed successfully")
        } catch (e: Exception) {
            LogExporter.w(TAG, "[BACKUP] Unexpected failure during auto backup: ${e.message}")
        }
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
     * 判断当前应用是否属于空白全新安装或卸载后重装。
     */
    fun isFreshInstall(context: Context): Boolean {
        val rulesPrefs = context.getSharedPreferences("iron_will_rules", Context.MODE_PRIVATE)
        val ledgerPrefs = context.getSharedPreferences("iron_will_ledger", Context.MODE_PRIVATE)
        val alarmPrefs = context.getSharedPreferences("alarm_prefs", Context.MODE_PRIVATE)
        val hasRules = rulesPrefs.contains("segments_json")
        val hasLedger = ledgerPrefs.contains("first_setup_timestamp_v2") || ledgerPrefs.contains("record_date_str")
        val hasAlarms = alarmPrefs.contains("alarms_json")
        return !hasRules && !hasLedger && !hasAlarms
    }

    /**
     * 重装首次启动时检测外部备份并静默自动恢复。
     * @return 是否成功恢复
     */
    fun checkAndRestoreIfNeeded(context: Context): Boolean {
        if (!isFreshInstall(context)) {
            return false
        }

        val candidateDirs = listOfNotNull(
            try {
                File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS),
                    BACKUP_DIR_NAME
                )
            } catch (_: Exception) { null },
            File(context.filesDir, INTERNAL_BACKUP_DIR)
        )

        for (dir in candidateDirs) {
            val restored = restoreFromDirectory(context, dir)
            if (restored) {
                return true
            }
        }

        return false
    }

    /**
     * 从指定目录寻找最新备份或上一版备份并恢复。
     */
    fun restoreFromDirectory(context: Context, dir: File): Boolean {
        if (!dir.exists() || !dir.isDirectory) return false

        val candidates = listOf(
            File(dir, BACKUP_FILE_NAME),
            File(dir, BACKUP_PREV_FILE_NAME)
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
                            "[RESTORE] Silently restored from ${file.absolutePath} (${result.fileCount} files, ${result.entryCount} entries)"
                        )
                        mainHandler.post {
                            try {
                                Toast.makeText(context, "已自动恢复历史统计与自控配置", Toast.LENGTH_LONG).show()
                            } catch (_: Exception) {}
                        }
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
