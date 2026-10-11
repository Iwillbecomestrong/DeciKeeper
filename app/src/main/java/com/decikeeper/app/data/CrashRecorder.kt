/**
 * =================================================================================
 * [Context Header]
 * * @file: CrashRecorder.kt
 * * @role: Data
 * * @description: 崩溃日志的捕获与持久化。
 * * @responsibilities:
 * 1. 记录关键异常信息。
 * 2. 提供导出与上报支持。
 * * @context:
 * - 上游: 全局异常处理
 * - 下游: 文件系统
 * * @constraints (必读):
 * 1. 需确保 I/O 安全与性能。
 * 2. 不引入 UI 依赖。
 * 3. 处理空值与异常。
 * =================================================================================
 */
package com.decikeeper.app.data

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object CrashRecorder : Thread.UncaughtExceptionHandler {

    private const val TAG = "CrashRecorder"
    private var defaultHandler: Thread.UncaughtExceptionHandler? = null
    private var logFile: File? = null

    fun init(context: Context) {
        if (defaultHandler != null) return // Already initialized

        defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler(this)
        
        logFile = File(context.filesDir, "crash_log.txt")
        Log.i(TAG, "CrashRecorder initialized. Log path: ${logFile?.absolutePath}")
    }

    override fun uncaughtException(t: Thread, e: Throwable) {
        try {
            saveCrashInfo(t, e)
            // 崩溃前把内存中尚未落盘的日志写出，保留崩溃之前的现场。
            LogExporter.flush()
        } catch (ex: Exception) {
            Log.e(TAG, "Failed to record crash", ex)
        } finally {
            // Hand over to default handler (app will crash/close)
            defaultHandler?.uncaughtException(t, e)
        }
    }

    private fun saveCrashInfo(t: Thread, e: Throwable) {
        val file = logFile ?: return
        
        try {
            val sw = StringWriter()
            val pw = PrintWriter(sw)
            e.printStackTrace(pw)
            val stackTrace = sw.toString()
            
            val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
            val header = "\n\n--- CRASH DETECTED AT $timestamp ---\n"
            val threadInfo = "Thread: ${t.name} (ID: ${t.id})\n"
            val content = header + threadInfo + stackTrace + "\n------------------------------------\n"

            // Append to file
            FileWriter(file, true).use { fw ->
                fw.write(content)
            }
            
            Log.e(TAG, "Crash saved to file.")
        } catch (ex: Exception) {
            Log.e(TAG, "Error writing crash log", ex)
        }
    }
}
