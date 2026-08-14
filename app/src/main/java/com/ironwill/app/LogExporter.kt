/**
 * =================================================================================
 * [Context Header]
 * * @file: LogExporter.kt
 * * @role: Data
 * * @description: 日志导出与分享工具。
 * * @responsibilities:
 * 1. 聚合并导出应用日志。
 * 2. 支持快捷发送与筛选。
 * * @context:
 * - 上游: HelpActivity, RulesFragment
 * - 下游: 文件系统、分享 Intent
 * * @constraints (必读):
 * 1. 避免主线程阻塞。
 * 2. 处理权限与 I/O 异常。
 * 3. 不包含业务判定逻辑。
 * =================================================================================
 */
package com.ironwill.app

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** One shared append boundary for legacy and normal event records. */
internal class InternalLogFileWriter(
    private val maxLogFileSize: Long,
    private val truncateFile: (File) -> Unit
) {
    private val writeLock = Any()

    fun append(logFile: File, entry: String) {
        synchronized(writeLock) {
            if (logFile.exists() && logFile.length() > maxLogFileSize) {
                truncateFile(logFile)
            }
            FileWriter(logFile, true).use { writer -> writer.write(entry) }
        }
    }
}

object LogExporter {

    private const val TAG = "LogExporter"

    // 开源版占位收件人（RFC 2606 保留域名，不会误发）：fork 后改为你的反馈邮箱。
    const val DEFAULT_RECIPIENT = "support@example.com"
    private const val INTERNAL_LOG_FILENAME = "ironwill_event_log.txt"
    private const val MAX_LOG_FILE_SIZE = 10 * 1024 * 1024L // 10MB, 增加日志容量以保存更长时间记录
    private val NORMAL_EVENT_HEADER = Regex(
        "^(\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\\.\\d{3}) \\[[^]]+] eventId=\\S+"
    )
    private val LEGACY_EVENT_HEADER = Regex("^\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\\.\\d{3}\\s+")

    /** 应用 Context 引用，由 MyApplication.onCreate() 设置 */
    @Volatile
    private var appContext: Context? = null

    private val internalLogFileWriter = InternalLogFileWriter(MAX_LOG_FILE_SIZE, ::truncateLogFile)
    
    fun init(context: Context) {
        appContext = context.applicationContext
    }

    /**
     * Creates the export side of the normal-event fan-out. The logger supplies the exact
     * [RenderedNormalEvent] that the logcat sink receives; this sink must not reconstruct text.
     */
    fun normalEventSink(
        writer: NormalEventExportWriter = NormalEventExportWriter { record -> persistNormalEvent(record) }
    ): NormalEventSink = NormalEventSink { record -> writer.append(record) }

    // ==================== 精简日志导出 ====================

    /** 默认删除的高频/调试日志模式（导出时兜底过滤）。
     *
     * The list covers every approved legacy classifier retired by R3 Tasks 1–6:
     * foreground polling/raw logs (Task 1), the seven-line evidence block and `[锁机]`
     * (Task 2), lock/temp lifecycle duplicates (Task 3), quota UI/calc traces
     * (Task 4), alarm/protection raw lifecycles (Task 5), and the two heartbeat
     * variants (Task 5). The matching is applied **before** trace-ID keep matching
     * so a legacy line that happens to carry a `LOCK-…` token still gets dropped —
     * the explicit classifier always wins.
     */
    private val exportDropPatterns = listOf(
        // Task 1 — foreground polling/raw retired sites
        Regex("""\[DETECT\]"""),
        Regex("""\[MINI_WINDOW_FALLBACK\]"""),
        Regex("""\[EVENT\] type=APP_STATE"""),
        Regex("""FG pkg="""),
        Regex("""FG_SUMMARY"""),
        Regex("""\[EVENT\] type=NAVIGATION"""),
        Regex("""\[FG_BYPASS_DIAG\]"""),
        Regex("""\[SHOULD_BLOCK_DIAG\]"""),
        Regex("""\[EVENT\] type=BLOCK_DECISION"""),
        Regex("""\[FG_GUARD_DIAG\]"""),
        Regex("""\[VIS_CHECK_DIAG\]"""),
        Regex("""\[EVENT\] type=VIS_DECISION"""),
        // Task 2 — MonitorService seven-line evidence block + `[锁机]` + `[RESTORE_REQUEST]`
        Regex("""前台结论:"""),
        Regex("""^[^|]*Accessibility:\s+(?:可用|不可用)"""),
        Regex("""UsageEvents:\s+#1"""),
        Regex("""UsageStats:\s+#1"""),
        Regex("""内部状态:"""),
        Regex("""判断理由:"""),
        Regex("""锁机上下文:"""),
        Regex("""\[锁机\]"""),
        Regex("""\[RESTORE_REQUEST\]"""),
        Regex("""\[AUTO_RELEASE\]"""),
        // Task 3 — lock/temp lifecycle duplicates
        Regex("""LockScreen: 解锁:"""),
        Regex("""LockScreen: 🏠 回到桌面:"""),
        Regex("""TempAccess: 临时解锁:"""),
        // Task 4 — quota UI ticks + consumption/penalty step traces + daily status header
        Regex("""\[BORROW_UI_DEBUG\]"""),
        Regex("""\[TIME_CONSUME\]"""),
        Regex("""\[STEP1\]"""),
        Regex("""\[STEP2\]"""),
        Regex("""\[STEP3\]"""),
        Regex("""\[DONE\] penalty applied"""),
        Regex("""══════ 每日状态播报 ══════"""),
        // Task 5 — alarm/protection raw lifecycle + heartbeats + service lifecycle
        Regex("""💓 心跳"""),
        Regex("""AlarmWindowManager: ring show=true"""),
        Regex("""LockWindowManager: snooze action=\S+"""),
        Regex("""CoreService: onServiceConnected"""),
        Regex("""CoreService: onUnbind"""),
        Regex("""CoreService: onRebind"""),
        // Pre-existing Task 1/3 drops retained for completeness
        Regex("""TemporaryAccess: \[FINAL_RELOCK_SUCCEEDED] cleared flag"""),
        Regex("""LockScreen: \[REASSERT]""")
    )

    /** 正向保留模式：包含 traceId 前缀（如 `LOCK-`）的行强制保留 */
    private val exportKeepPatterns = listOf(
        Regex("""\bLOCK-\d{8}-\d{6}-\d{4}\b"""),
        Regex("""\bFG-\d{8}-\d{6}-\d{4}\b""")
    )

    /** 状态播报块起始标志：CreditManager.logDailyStatus 输出的每块都以这一行开始 */
    private val statusReportBlockStart = Regex("""^原因:\s+\S+""")

    /** 状态播报块内会被每秒刷新、需要排除在指纹外的行 */
    private val statusReportTimestampLine = Regex("""^当前时间:\s+\d{2}:\d{2}:\d{2}""")

    /** 状态播报块内 `业务日` 行（跨日才变化，但单次导出周期内通常不变，归一化掉便于跨天比对） */
    private val statusReportBizDateLine = Regex("""^业务日:\s+\d{8}""")

    /** 把持续变化的秒数归一化，避免倒计时每变化一秒就导出一行 */
    private fun normalizeForegroundState(line: String): String {
        return line
            .substringAfter("MonitorService: ")
            .replace(Regex("""rest=true\(\d+s\)"""), "rest=true")
            .replace(Regex("""temp=\d+s"""), "temp=true")
            .replace(Regex("""cool=\d+s"""), "cool=true")
            .replace(Regex("""tempCool=\d+s"""), "tempCool=true")
            .replace(Regex("""\s+\|\s+evidence=[^|]+"""), "")
            .trim()
    }

    /**
     * 计算状态播报块的 fingerprint：去掉"当前时间"和"业务日"行后剩余内容的 hashCode。
     * 同一额度在 1 秒级时间窗口内视为相同事件。
     */
    private fun statusReportFingerprint(blockLines: List<String>): Int {
        val canonical = buildString {
            for (line in blockLines) {
                val trimmed = line.trim()
                if (trimmed.isEmpty()) continue
                if (statusReportTimestampLine.containsMatchIn(trimmed)) continue
                if (statusReportBizDateLine.containsMatchIn(trimmed)) continue
                append(trimmed).append('\n')
            }
        }
        return canonical.hashCode()
    }

    /**
     * 抓取一行内的时间戳（MM-dd HH:mm:ss.SSS 或 HH:mm:ss），用于在 dedup 摘要中标注时间范围。
     * 找不到时返回 null。
     */
    private fun extractTimestampFromLine(line: String): String? {
        val m1 = Regex("""\b(\d{2}-\d{2}\s+\d{2}:\d{2}:\d{2}\.\d{3})\b""").find(line)
        if (m1 != null) return m1.groupValues[1]
        val m2 = Regex("""\b(\d{2}:\d{2}:\d{2})\b""").find(line)
        return m2?.groupValues?.get(1)
    }

    /**
     * 在状态播报块内查找时间戳：先尝试"MM-dd HH:mm:ss.SSS"（LOG 文件头格式），
     * 再退到"HH:mm:ss"（块内"当前时间:"行）。块内没有则返回 null。
     */
    private fun extractBlockTimestamp(blockLines: List<String>): String? {
        for (line in blockLines) {
            extractTimestampFromLine(line)?.let { return it }
        }
        return null
    }

    /**
     * 状态播报多行块 dedup：
     * - 识别 `原因: ...` 开头的连续行为一块（块内可能含空行）
     * - 同一 fingerprint 的连续块只保留首块 + 一行摘要
     * - traceId 行 (`LOCK-...`) 不会出现在"原因:"块内，由其他 TAG 发出，不走"原因:"格式
     */
    private fun dedupStatusReportBlocks(lines: List<String>): List<String> {
        // 1. 找出所有"原因:"块的 [start, endExclusive) 区间
        val blockRanges = mutableListOf<IntRange>()
        var idx = 0
        while (idx < lines.size) {
            if (statusReportBlockStart.containsMatchIn(lines[idx].trim())) {
                var end = idx + 1
                while (end < lines.size && !statusReportBlockStart.containsMatchIn(lines[end].trim())) {
                    end++
                }
                blockRanges += idx until end
                idx = end
            } else {
                idx++
            }
        }
        if (blockRanges.isEmpty()) return lines

        // 2. 每块计算 fingerprint + 首末时间戳
        data class BlockMeta(val range: IntRange, val fp: Int, val firstTs: String?, val lastTs: String?)
        val metas = blockRanges.map { r ->
            val slice = lines.subList(r.first, r.last + 1)
            BlockMeta(
                range = r,
                fp = statusReportFingerprint(slice),
                firstTs = extractBlockTimestamp(slice),
                lastTs = extractBlockTimestamp(slice)
            )
        }

        // 3. 把相邻且 fp 相同的连续块合并为同一个 group（首个 group 块记 first，最后一个记 last）
        data class Group(val first: BlockMeta, val last: BlockMeta, val count: Int)
        val groups = mutableListOf<Group>()
        for (m in metas) {
            val lastGroup = groups.lastOrNull()
            if (lastGroup != null && lastGroup.first.fp == m.fp) {
                groups[groups.size - 1] = lastGroup.copy(last = m, count = lastGroup.count + 1)
            } else {
                groups += Group(first = m, last = m, count = 1)
            }
        }
        // 没有任何可合并的块，原样返回
        if (groups.all { it.count == 1 }) return lines

        // 4. 写出：group.count==1 的块按原样写出；count>1 的只写首块 + 摘要行
        val result = mutableListOf<String>()
        var cursor = 0
        for ((bIdx, m) in metas.withIndex()) {
            while (cursor < m.range.first) {
                result += lines[cursor]
                cursor++
            }
            val group = groups.firstOrNull { it.first.range == m.range }
            if (group != null && group.count > 1) {
                // group 的首块：原样写出整块 + 摘要行
                for (k in m.range) {
                    result += lines[k]
                    cursor = k + 1
                }
                val tsLabel = when {
                    group.first.firstTs != null && group.last.lastTs != null && group.first.firstTs != group.last.lastTs ->
                        " @ ${group.first.firstTs}~${group.last.lastTs}"
                    group.first.firstTs != null -> " @ ${group.first.firstTs}"
                    else -> ""
                }
                result += "[dedup ${group.count - 1} 个相同状态播报块$tsLabel]"
            } else {
                // 被合并掉的中间/末块：跳过
                cursor = m.range.last + 1
            }
        }
        // 追加尾部非块行
        while (cursor < lines.size) {
            result += lines[cursor]
            cursor++
        }
        return result
    }

    /**
     * 精简日志：过滤高频调试日志、归一化倒计时、去重连续相同行 + 合并状态播报多行块
     */
    fun compactLogsForExport(rawLines: List<String>): List<String> {
        val firstPass = mutableListOf<String>()
        var lastForegroundState: String? = null
        var lastOutputLine: String? = null

        for (line in rawLines) {
            if (exportDropPatterns.any { it.containsMatchIn(line) }) {
                continue
            }

            // traceId 行强制保留（即使与上一行重复）
            if (exportKeepPatterns.any { it.containsMatchIn(line) }) {
                firstPass += line
                lastOutputLine = line
                continue
            }

            if ("MonitorService: FG pkg=" in line) {
                val currentState = normalizeForegroundState(line)
                if (currentState == lastForegroundState) {
                    continue
                }
                lastForegroundState = currentState
            }

            // [SUMMARY] / [EVENT] 行始终保留，不参与连续行去重
            val isEventOrSummary = line.contains("[SUMMARY]") || line.contains("[EVENT]")
            if (!isEventOrSummary && line == lastOutputLine) {
                continue
            }

            firstPass += line
            if (!isEventOrSummary) {
                lastOutputLine = line
            }
        }

        // 第二阶段：合并相邻的"状态播报"多行块（同一额度短时间内多次触发）
        return dedupStatusReportBlocks(firstPass)
    }

    /** 判断崩溃日志内容是否为空或无有效条目 */
    private fun isEmptyCrashContent(content: String): Boolean {
        val trimmed = content.trim()
        return trimmed.isEmpty() ||
            trimmed.contains("No crash entries within the specified time range") ||
            trimmed.contains("No crash log file found")
    }
    
    val LOG_OPTIONS = arrayOf("30秒", "1分钟", "5分钟", "30分钟", "1小时", "3小时", "12小时", "自定义时长...", "自定义时间范围...")
    val LOG_DURATIONS = longArrayOf(
        30 * 1000L,
        60 * 1000L,
        5 * 60 * 1000L,
        30 * 60 * 1000L,
        60 * 60 * 1000L,
        3 * 60 * 60 * 1000L,
        12 * 60 * 60 * 1000L,
        -1L, // 自定义时长，由用户输入
        -2L  // 自定义时间范围，由用户选择起点和终点
    )

    fun exportAndShare(context: Context) {
        exportAndShareRecent(context, null, DEFAULT_RECIPIENT, "IronWill 日志")
    }

    fun exportAndShareRecent(
        context: Context,
        durationMillis: Long?,
        recipient: String = DEFAULT_RECIPIENT,
        subject: String = "IronWill 日志",
        body: String = "自动导出日志，请查收。"
    ) {
        try {
            val logFile = File(context.getExternalFilesDir(null), "app_log.txt")
            if (logFile.exists()) {
                logFile.delete()
            }
            logFile.createNewFile()

            val writer = FileWriter(logFile)

            // 1. Write Crash Log History (if exists), with time filtering
            val crashLogFile = File(context.filesDir, "crash_log.txt")
            if (crashLogFile.exists()) {
                val crashContent = if (durationMillis != null && durationMillis > 0) {
                    readCrashLogFiltered(durationMillis)
                } else {
                    crashLogFile.readText()
                }
                if (!isEmptyCrashContent(crashContent)) {
                    writer.write("=== CRASH HISTORY ===\n")
                    writer.write(crashContent)
                    writer.write("=== END CRASH HISTORY ===\n\n")
                }
            }
            
            // 2. Write IronWill Internal Event Log (Persisted to disk, survives Force Stop)
            val rawInternalLog = if (durationMillis != null && durationMillis > 0) {
                readInternalLogFiltered(durationMillis)
            } else {
                readInternalLog()
            }
            val internalLog = compactLogsForExport(rawInternalLog.lines()).joinToString("\n") + "\n"
            val lineCount = internalLog.lines().filter { it.isNotBlank() }.size
            val timeRangeLabel = if (durationMillis != null && durationMillis > 0) {
                " (最近 ${durationMillis / 1000}s)"
            } else {
                ""
            }
            writer.write("=== IRONWILL INTERNAL LOG ($lineCount lines$timeRangeLabel) ===\n")
            writer.write(internalLog)
            writer.write("=== END INTERNAL LOG ===\n")
            writer.flush()
            writer.close()
            
            Log.d(TAG, "Log exported to ${logFile.absolutePath}")
            shareFile(context, logFile, recipient, subject, body)

        } catch (e: Exception) {
            Log.e(TAG, "Failed to export logs", e)
            android.widget.Toast.makeText(context, "日志导出失败: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * 按自定义时间范围导出并分享日志
     * @param startTimeMillis 起点时间（毫秒）
     * @param endTimeMillis 终点时间（毫秒）
     */
    fun exportAndShareTimeRange(
        context: Context,
        startTimeMillis: Long,
        endTimeMillis: Long,
        recipient: String = DEFAULT_RECIPIENT,
        subject: String = "IronWill 日志",
        body: String = "自动导出日志，请查收。"
    ) {
        try {
            val logFile = File(context.getExternalFilesDir(null), "app_log.txt")
            if (logFile.exists()) {
                logFile.delete()
            }
            logFile.createNewFile()

            val sdf = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
            val startLabel = sdf.format(Date(startTimeMillis))
            val endLabel = sdf.format(Date(endTimeMillis))
            val durationSec = (endTimeMillis - startTimeMillis) / 1000

            val writer = FileWriter(logFile)

            // 1. Write Crash Log History (if exists), with time range filtering
            val crashLogFile = File(context.filesDir, "crash_log.txt")
            if (crashLogFile.exists()) {
                val crashContent = readCrashLogFilteredInRange(startTimeMillis, endTimeMillis)
                if (!isEmptyCrashContent(crashContent)) {
                    writer.write("=== CRASH HISTORY ===\n")
                    writer.write(crashContent)
                    writer.write("=== END CRASH HISTORY ===\n\n")
                }
            }

            // 2. Write IronWill Internal Event Log with time range filtering
            val rawInternalLog = readInternalLogFilteredInRange(startTimeMillis, endTimeMillis)
            val internalLog = compactLogsForExport(rawInternalLog.lines()).joinToString("\n") + "\n"
            val lineCount = internalLog.lines().filter { it.isNotBlank() }.size
            writer.write("=== IRONWILL INTERNAL LOG ($lineCount lines, $startLabel ~ $endLabel, ${durationSec}s) ===\n")
            writer.write(internalLog)
            writer.write("=== END INTERNAL LOG ===\n")
            writer.flush()
            writer.close()

            Log.d(TAG, "Log exported to ${logFile.absolutePath}")
            shareFile(context, logFile, recipient, subject, body)

        } catch (e: Exception) {
            Log.e(TAG, "Failed to export logs", e)
            android.widget.Toast.makeText(context, "日志导出失败: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    fun showSendLogOptions(context: Context, title: String, message: String? = null, recipient: String = DEFAULT_RECIPIENT) {
        val builder = androidx.appcompat.app.AlertDialog.Builder(context)
            .setTitle(title)
            .setItems(LOG_OPTIONS) { _, which ->
                when (which) {
                    LOG_OPTIONS.size - 2 -> {
                        // "自定义时长..." 选项
                        showCustomDurationDialog(context, recipient)
                    }
                    LOG_OPTIONS.size - 1 -> {
                        // "自定义时间范围..." 选项
                        showCustomTimeRangeDialog(context, recipient)
                    }
                    else -> {
                        val durationMillis = LOG_DURATIONS[which]
                        val durationLabel = LOG_OPTIONS[which]
                        val subject = "IronWill 无障碍日志 (最近$durationLabel)"
                        exportAndShareRecent(context, durationMillis, recipient, subject)
                    }
                }
            }
            .setNegativeButton("取消", null)

        builder.show()
    }

    /**
     * 弹出自定义时长对话框：先选单位（分钟/小时），再输入数字
     */
    private fun showCustomDurationDialog(context: Context, recipient: String) {
        val units = arrayOf("分钟", "小时")
        val unitValues = longArrayOf(60 * 1000L, 60 * 60 * 1000L) // 分钟/小时的毫秒值

        // 第一步：选择单位
        androidx.appcompat.app.AlertDialog.Builder(context)
            .setTitle("选择时间单位")
            .setItems(units) { _, unitIndex ->
                val unitLabel = units[unitIndex]
                val unitMs = unitValues[unitIndex]
                val maxValue = if (unitIndex == 0) 1440 else 72

                // 第二步：输入数字
                val input = android.widget.EditText(context).apply {
                    hint = "输入 1~$maxValue"
                    inputType = android.text.InputType.TYPE_CLASS_NUMBER
                }
                androidx.appcompat.app.AlertDialog.Builder(context)
                    .setTitle("自定义时长 ($unitLabel)")
                    .setView(input)
                    .setPositiveButton("确定") { _, _ ->
                        val text = input.text.toString().trim()
                        val value = text.toIntOrNull()
                        if (value != null && value in 1..maxValue) {
                            val customDurationMs = value * unitMs
                            val subject = "IronWill 无障碍日志 (最近${value}$unitLabel)"
                            exportAndShareRecent(context, customDurationMs, recipient, subject)
                        } else {
                            android.widget.Toast.makeText(context, "请输入 1~$maxValue 的整数", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    }
                    .setNegativeButton("取消", null)
                    .show()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /**
     * 弹出自定义时间范围对话框：选择起点时间和终点时间
     */
    private fun showCustomTimeRangeDialog(context: Context, recipient: String) {
        // 默认起点：1小时前
        val startTime = java.util.Calendar.getInstance().apply { add(java.util.Calendar.HOUR_OF_DAY, -1) }
        // 默认终点：当前时间
        val endTime = java.util.Calendar.getInstance()

        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

        // 用数组包装以便在 lambda 中修改
        val state = arrayOf(startTime.clone() as java.util.Calendar, endTime.clone() as java.util.Calendar)

        fun formatRange(): String {
            return "起点: ${sdf.format(state[0].time)}\n终点: ${sdf.format(state[1].time)}"
        }

        val container = android.widget.LinearLayout(context).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(60, 40, 60, 20)
        }

        val tvRange = android.widget.TextView(context).apply {
            text = formatRange()
            textSize = 15f
            setPadding(0, 0, 0, 30)
        }
        container.addView(tvRange)

        val btnStart = android.widget.Button(context).apply { text = "选择起点时间" }
        val btnEnd = android.widget.Button(context).apply { text = "选择终点时间" }
        container.addView(btnStart)
        container.addView(btnEnd)

        fun pickDateTime(targetIndex: Int, title: String, onDone: () -> Unit) {
            val target = state[targetIndex]
            android.app.DatePickerDialog(context, { _, year, month, day ->
                android.app.TimePickerDialog(context, { _, hour, minute ->
                    target.set(year, month, day, hour, minute, 0)
                    target.set(java.util.Calendar.MILLISECOND, 0)
                    tvRange.text = formatRange()
                    onDone()
                }, target.get(java.util.Calendar.HOUR_OF_DAY), target.get(java.util.Calendar.MINUTE), true).apply {
                    setTitle("$title - 选择时间")
                    show()
                }
            }, target.get(java.util.Calendar.YEAR), target.get(java.util.Calendar.MONTH), target.get(java.util.Calendar.DAY_OF_MONTH)).apply {
                setTitle("$title - 选择日期")
                show()
            }
        }

        btnStart.setOnClickListener {
            pickDateTime(0, "起点") {}
        }
        btnEnd.setOnClickListener {
            pickDateTime(1, "终点") {}
        }

        androidx.appcompat.app.AlertDialog.Builder(context)
            .setTitle("自定义时间范围")
            .setView(container)
            .setPositiveButton("导出") { _, _ ->
                val startMs = state[0].timeInMillis
                val endMs = state[1].timeInMillis
                if (endMs <= startMs) {
                    android.widget.Toast.makeText(context, "终点必须晚于起点", android.widget.Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val startLabel = sdf.format(state[0].time)
                val endLabel = sdf.format(state[1].time)
                val subject = "IronWill 日志 ($startLabel ~ $endLabel)"
                exportAndShareTimeRange(context, startMs, endMs, recipient, subject)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    fun showQuickSendLogOptions(context: Context, title: String, message: String? = null, recipient: String = DEFAULT_RECIPIENT) {
        // Redirect to the main options method to ensure consistency (7 options)
        showSendLogOptions(context, title, message, recipient)
    }

    fun buildSinceTimeString(nowMillis: Long, durationMillis: Long): String {
        val start = nowMillis - durationMillis
        val sdf = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
        return sdf.format(Date(start))
    }

    /**
     * 磁盘持久化日志 - 始终记录 IronWill 关键事件
     * 写入 filesDir/ironwill_event_log.txt，进程被 Force Stop 后也不会丢失
     */
    
    // 快捷方法：i=info, w=warning, d=debug（统一委托 logEvent）
    fun i(tag: String, message: String) = logEvent(tag, message)
    fun w(tag: String, message: String) = logEvent(tag, message)
    fun d(tag: String, message: String) = logEvent(tag, message)

    fun logEvent(tag: String, message: String) {
        try {
            val ctx = appContext ?: return
            val timestamp = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
            val entry = "$timestamp $tag: $message\n"
            val logFile = File(ctx.filesDir, INTERNAL_LOG_FILENAME)
            
            internalLogFileWriter.append(logFile, entry)
        } catch (e: Exception) {
            // 日志写入失败不应影响主逻辑
            Log.e(TAG, "logEvent write failed: ${e.message}")
        }
    }

    /**
     * Persists a normal event in one write. Its renderer has already supplied the full-date
     * header and any atomic body block, so adding another timestamp or tag would duplicate facts.
     */
    private fun persistNormalEvent(record: RenderedNormalEvent) {
        try {
            val ctx = appContext ?: return
            val logFile = File(ctx.filesDir, INTERNAL_LOG_FILENAME)

            val entry = if (record.text.endsWith('\n')) record.text else "${record.text}\n"
            internalLogFileWriter.append(logFile, entry)
        } catch (e: Exception) {
            Log.e(TAG, "normal event write failed: ${e.message}")
        }
    }
    
    /** 读取磁盘上的内部日志内容 */
    private fun readInternalLog(): String {
        val ctx = appContext ?: return "[WARN] LogExporter not initialized (appContext is null)\n"
        val logFile = File(ctx.filesDir, INTERNAL_LOG_FILENAME)
        return if (logFile.exists()) {
            try {
                logFile.readText()
            } catch (e: Exception) {
                "[ERROR] Failed to read internal log: ${e.message}\n"
            }
        } else {
            "[INFO] No internal log file found (first run?)\n"
        }
    }

    /**
     * Filters complete internal-log records. A normal-event header owns every following body line
     * until the next header, so an out-of-range atomic block cannot leak through as continuations.
     */
    internal fun filterInternalLogLinesInRange(
        lines: List<String>,
        startTimeMillis: Long,
        endTimeMillis: Long
    ): List<String> {
        var includeCurrentRecord = false
        return buildList {
            lines.forEach { line ->
                parseInternalLogHeaderTime(line, startTimeMillis)?.let { timestamp ->
                    includeCurrentRecord = timestamp in startTimeMillis..endTimeMillis
                }
                if (includeCurrentRecord) {
                    add(line)
                }
            }
        }
    }

    private fun parseInternalLogHeaderTime(line: String, referenceTimeMillis: Long): Long? {
        val normalMatch = NORMAL_EVENT_HEADER.find(line)
        if (normalMatch != null) {
            return parseTimestamp(normalMatch.groupValues[1], "yyyy-MM-dd HH:mm:ss.SSS")
        }
        if (!LEGACY_EVENT_HEADER.containsMatchIn(line)) {
            return null
        }
        return parseTimestamp(line.substring(0, 18), "MM-dd HH:mm:ss.SSS")
            ?.let { parsed -> calculateLogTime(Date(parsed), referenceTimeMillis) }
    }

    private fun parseTimestamp(value: String, pattern: String): Long? = try {
        SimpleDateFormat(pattern, Locale.US).apply { isLenient = false }.parse(value)?.time
    } catch (_: Exception) {
        null
    }
    
    /** 
     * 按时间过滤读取内部日志 - 只返回指定时间范围内的日志
     * 日志格式: "MM-dd HH:mm:ss.SSS TAG: message"
     * 
     * 策略：从文件末尾倒序读取，保留最近 N 毫秒的日志
     */
    private fun readInternalLogFiltered(durationMillis: Long): String {
        val ctx = appContext ?: return "[WARN] LogExporter not initialized (appContext is null)\n"
        val logFile = File(ctx.filesDir, INTERNAL_LOG_FILENAME)
        if (!logFile.exists()) {
            return "[INFO] No internal log file found (first run?)\n"
        }
        
        try {
            val allLines = logFile.readLines()
            val cutoffTime = System.currentTimeMillis() - durationMillis
            val filteredLines = filterInternalLogLinesInRange(allLines, cutoffTime, Long.MAX_VALUE)
            val totalLines = allLines.size
            
            val keptLines = filteredLines.size
            
            if (keptLines == 0) {
                return "[INFO] No log entries within the specified time range (total $totalLines lines)\n"
            }
            
            val result = StringBuilder()
            result.append("[INFO] Filtered: kept $keptLines of $totalLines lines\n")
            filteredLines.forEach { result.append(it).append("\n") }
            return result.toString()
            
        } catch (e: Exception) {
            return "[ERROR] Failed to read/filter internal log: ${e.message}\n"
        }
    }

    /**
     * 按时间过滤读取崩溃日志 - 只返回指定时间范围内的 crash 条目
     *
     * crash 日志格式（由 CrashRecorder 写入）：
     *   --- CRASH DETECTED AT yyyy-MM-dd HH:mm:ss ---\n
     *   Thread: ...\n
     *   stack trace...\n
     *   ------------------------------------
     */
    private fun readCrashLogFiltered(durationMillis: Long): String {
        val ctx = appContext ?: return "[WARN] LogExporter not initialized (appContext is null)\n"
        val crashLogFile = File(ctx.filesDir, "crash_log.txt")
        if (!crashLogFile.exists()) {
            return "[INFO] No crash log file found.\n"
        }

        try {
            val cutoffTime = System.currentTimeMillis() - durationMillis
            val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
            val separator = "------------------------------------"
            val fullText = crashLogFile.readText()

            // 按分隔符拆分各个 crash 条目
            val entries = fullText.split(separator)
            val keptEntries = mutableListOf<String>()
            var totalEntries = 0

            for (entry in entries) {
                val trimmed = entry.trim()
                if (trimmed.isEmpty()) continue
                totalEntries++

                // 提取时间戳： "--- CRASH DETECTED AT yyyy-MM-dd HH:mm:ss ---"
                val timestampMatch = Regex("--- CRASH DETECTED AT (\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}) ---")
                    .find(trimmed)
                val entryTime = timestampMatch?.groupValues?.get(1)?.let { ts ->
                    try {
                        sdf.parse(ts)?.time
                    } catch (e: Exception) {
                        null
                    }
                }

                if (entryTime != null && entryTime >= cutoffTime) {
                    keptEntries.add(trimmed)
                }
            }

            if (keptEntries.isEmpty()) {
                return "[INFO] No crash entries within the specified time range (total $totalEntries entries)\n"
            }

            val result = StringBuilder()
            result.append("[INFO] Filtered: kept ${keptEntries.size} of $totalEntries crash entries\n")
            keptEntries.forEachIndexed { index, entry ->
                result.append(entry).append("\n")
                if (index < keptEntries.size - 1) {
                    result.append(separator).append("\n")
                }
            }
            result.append("\n")
            return result.toString()

        } catch (e: Exception) {
            return "[ERROR] Failed to read/filter crash log: ${e.message}\n"
        }
    }

    /**
     * 按时间范围过滤读取内部日志 - 只返回 [startTimeMillis, endTimeMillis] 区间内的日志
     */
    private fun readInternalLogFilteredInRange(startTimeMillis: Long, endTimeMillis: Long): String {
        val ctx = appContext ?: return "[WARN] LogExporter not initialized (appContext is null)\n"
        val logFile = File(ctx.filesDir, INTERNAL_LOG_FILENAME)
        if (!logFile.exists()) {
            return "[INFO] No internal log file found (first run?)\n"
        }

        try {
            val allLines = logFile.readLines()
            val filteredLines = filterInternalLogLinesInRange(allLines, startTimeMillis, endTimeMillis)
            val totalLines = allLines.size

            val keptLines = filteredLines.size
            if (keptLines == 0) {
                return "[INFO] No log entries within the specified time range (total $totalLines lines)\n"
            }

            val result = StringBuilder()
            result.append("[INFO] Filtered: kept $keptLines of $totalLines lines\n")
            filteredLines.forEach { result.append(it).append("\n") }
            return result.toString()

        } catch (e: Exception) {
            return "[ERROR] Failed to read/filter internal log: ${e.message}\n"
        }
    }

    /**
     * 按时间范围过滤读取崩溃日志 - 只返回 [startTimeMillis, endTimeMillis] 区间内的 crash 条目
     */
    private fun readCrashLogFilteredInRange(startTimeMillis: Long, endTimeMillis: Long): String {
        val ctx = appContext ?: return "[WARN] LogExporter not initialized (appContext is null)\n"
        val crashLogFile = File(ctx.filesDir, "crash_log.txt")
        if (!crashLogFile.exists()) {
            return "[INFO] No crash log file found.\n"
        }

        try {
            val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
            val separator = "------------------------------------"
            val fullText = crashLogFile.readText()

            val entries = fullText.split(separator)
            val keptEntries = mutableListOf<String>()
            var totalEntries = 0

            for (entry in entries) {
                val trimmed = entry.trim()
                if (trimmed.isEmpty()) continue
                totalEntries++

                val timestampMatch = Regex("--- CRASH DETECTED AT (\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}) ---")
                    .find(trimmed)
                val entryTime = timestampMatch?.groupValues?.get(1)?.let { ts ->
                    try { sdf.parse(ts)?.time } catch (e: Exception) { null }
                }

                if (entryTime != null && entryTime in startTimeMillis..endTimeMillis) {
                    keptEntries.add(trimmed)
                }
            }

            if (keptEntries.isEmpty()) {
                return "[INFO] No crash entries within the specified time range (total $totalEntries entries)\n"
            }

            val result = StringBuilder()
            result.append("[INFO] Filtered: kept ${keptEntries.size} of $totalEntries crash entries\n")
            keptEntries.forEachIndexed { index, entry ->
                result.append(entry).append("\n")
                if (index < keptEntries.size - 1) {
                    result.append(separator).append("\n")
                }
            }
            result.append("\n")
            return result.toString()

        } catch (e: Exception) {
            return "[ERROR] Failed to read/filter crash log: ${e.message}\n"
        }
    }

    /**
     * 计算日志时间戳的绝对毫秒值
     *
     * 策略：根据 cutoffTime 推断日志的年份
     * - 如果日志的月日 > cutoffTime 的月日，说明日志是去年的
     * - 否则日志是今年的
     */
    private fun calculateLogTime(parsedDate: Date, cutoffTime: Long): Long {
        val cutoffCalendar = java.util.Calendar.getInstance()
        cutoffCalendar.timeInMillis = cutoffTime
        val cutoffYear = cutoffCalendar.get(java.util.Calendar.YEAR)
        val cutoffDayOfYear = cutoffCalendar.get(java.util.Calendar.DAY_OF_YEAR)

        val logCalendar = java.util.Calendar.getInstance()
        logCalendar.time = parsedDate
        val logDayOfYear = logCalendar.get(java.util.Calendar.DAY_OF_YEAR)

        logCalendar.set(java.util.Calendar.YEAR, if (logDayOfYear > cutoffDayOfYear + 90) {
            cutoffYear - 1
        } else {
            cutoffYear
        })

        return logCalendar.timeInMillis
    }

    /**
     * 截断日志文件，保留中点之后的完整记录。多行正常事件只允许从其 header 开始保留，
     * 因此中点落在 body 中时会安全丢弃该事件的剩余行。
     */
    internal fun truncateLogFile(file: File) {
        try {
            val lines = file.readLines()
            val nominalKeepFrom = lines.size / 2
            val keepFrom = (nominalKeepFrom until lines.size)
                .firstOrNull { index -> isInternalLogRecordHeader(lines[index]) }
                ?: lines.size
            val kept = lines.subList(keepFrom, lines.size)
            file.writeText("[LOG TRUNCATED - kept ${kept.size} of ${lines.size} lines]\n")
            file.appendText(kept.joinToString("\n"))
            file.appendText("\n")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to truncate log file: ${e.message}")
        }
    }

    private fun isInternalLogRecordHeader(line: String): Boolean =
        NORMAL_EVENT_HEADER.containsMatchIn(line) || LEGACY_EVENT_HEADER.containsMatchIn(line)

    private fun shareFile(context: Context, file: File, recipient: String, subject: String, body: String) {
        try {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_EMAIL, arrayOf(recipient))
                putExtra(Intent.EXTRA_SUBJECT, subject)
                // 只传文件，不传 EXTRA_TEXT，否则豆包/企业微信等 App 会显示文字而不是文件
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            if (context !is android.app.Activity) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(Intent.createChooser(intent, "发送日志到邮箱"))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to share file", e)
            android.widget.Toast.makeText(context, "分享失败: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
        }
    }
}
