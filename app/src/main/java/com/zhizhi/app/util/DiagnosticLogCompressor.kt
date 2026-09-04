/**
 * =================================================================================
 * [Context Header]
 * * @file: DiagnosticLogCompressor.kt
 * * @role: Utility
 * * @description: 诊断日志压缩器 — 连续相同状态去重，变化时输出 [EVENT]，定期间隔输出 [SUMMARY]。
 * * @responsibilities:
 * 1. 对 VIS_CHECK_DIAG / FG_BYPASS_DIAG / FG_GUARD_DIAG / SHOULD_BLOCK_DIAG 做去重压缩。
 * 2. 连续相同 StableKey 只累积计数不写日志。
 * 3. StableKey 变化时输出 [SUMMARY] 摘要行。
 * 4. DEBUG 模式可保留原始高频诊断。
 * * @context:
 * - 上游: MonitorService.checkForegroundApp(), isAppActuallyVisible(), shouldEnforceLockForCurrentTop()
 * - 下游: LogExporter.logEvent()
 * * @constraints:
 * 1. 不修改任何业务判断逻辑。
 * 2. 每 10 秒最多输出一次 SUMMARY。
 * 3. 状态翻转（如 result/decision 字段变化）时立即输出。
 * =================================================================================
 */
package com.zhizhi.app.util
// ═══════════════════════════════════════════════════
// StableKey 数据类
// ═══════════════════════════════════════════════════

data class VisCheckStableKey(
    val pkg: String,
    val result: Boolean,
    val windowMs: Long,
    val a11yWindows: Boolean,
    val lastVisibleRefreshed: Boolean
)

data class FgBypassStableKey(
    val pkg: String,
    val guard: Boolean,
    val fallback: Boolean,
    val forceRelock: Boolean,
    val forcePost: Boolean,
    val detached: Boolean,
    val passed: Boolean,
    val src: String,
    val a11y: Boolean,
    val inA11yWindows: Boolean
)

data class FgGuardStableKey(
    val pkg: String,
    val src: String,
    val hasRecentFg: Boolean,
    val pkgInA11y: Boolean,
    val decision: Boolean,  // true=通过守卫, false=被拒
    val a11yService: Boolean
)

data class ShouldBlockStableKey(
    val pkg: String,
    val isBlacklisted: Boolean,
    val isDndLocked: Boolean,
    val isFreeTime: Boolean,
    val shouldBlock: Boolean,
    val isManaged: Boolean,
    val isAllowed: Boolean
)

data class AppStateStableKey(
    val mode: String,
    val a11y: Boolean,
    val isDndLocked: Boolean,
    val isFreeTime: Boolean,
    val isPrivilege: Boolean,
    val lockedPkg: String?,
    val lastPkg: String?,
    val lastSrc: String?
)

// ═══════════════════════════════════════════════════
// 通用压缩器
// ═══════════════════════════════════════════════════

/**
 * 诊断日志压缩器。
 * @param diagType 诊断类型标签（如 "VIS_CHECK_DIAG"），用于 SUMMARY 行。
 * @param summaryIntervalMs SUMMARY 最小间隔（默认 10000ms）。
 */
class DiagnosticCompressor<T : Any>(
    private val diagType: String,
    private val summaryIntervalMs: Long = 10_000L
) {
    /** 设为 true 时每条诊断都写日志（不压缩）。默认 false。 */
    var debug: Boolean = false

    private var lastKey: T? = null
    private var repeatCount: Int = 0
    private var firstAtMs: Long = 0L
    private var lastAtMs: Long = 0L
    private var lastSummaryAtMs: Long = 0L

    /**
     * 观察一次诊断事件。
     * @param key 当前事件的 StableKey。
     * @param nowMs 当前时间戳。
     * @return 需要输出的日志行列表（可能为空、一条 DETAIL 或一条 DETAIL + SUMMARY）。
     */
    fun observe(key: T, nowMs: Long): List<String> {
        if (debug) {
            return listOf(buildDetail(key, nowMs))
        }

        val previous = lastKey
        if (previous == null) {
            // 初次
            lastKey = key
            repeatCount = 1
            firstAtMs = nowMs
            lastAtMs = nowMs
            lastSummaryAtMs = nowMs
            return listOf(buildDetail(key, nowMs))
        }

        if (previous == key) {
            // 连续相同
            repeatCount++
            lastAtMs = nowMs

            // 每 10s 最多输出一次 SUMMARY
            if (nowMs - lastSummaryAtMs >= summaryIntervalMs) {
                lastSummaryAtMs = nowMs
                val summary = buildSummary(key, repeatCount, firstAtMs, lastAtMs)
                // 重置计数窗口
                repeatCount = 0
                firstAtMs = nowMs
                return listOf(summary)
            }
            return emptyList()
        }

        // 状态变化
        val output = mutableListOf<String>()

        // 先输出上一状态的 SUMMARY（如果重复过）
        if (repeatCount > 1) {
            output += buildSummary(previous, repeatCount, firstAtMs, lastAtMs)
        }

        // 复位并输出新状态的 DETAIL
        lastKey = key
        repeatCount = 1
        firstAtMs = nowMs
        lastAtMs = nowMs
        lastSummaryAtMs = nowMs
        output += buildDetail(key, nowMs)
        return output
    }

    private fun buildDetail(key: T, nowMs: Long): String {
        val sdf = java.text.SimpleDateFormat("MM-dd HH:mm:ss.SSS", java.util.Locale.getDefault())
        return "[${diagType}] ${formatKey(key)} @${sdf.format(java.util.Date(nowMs))}"
    }

    private fun buildSummary(key: T, count: Int, firstMs: Long, lastMs: Long): String {
        val durationMs = (lastMs - firstMs).coerceAtLeast(0L)
        val timeSdf = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
        val firstTime = timeSdf.format(java.util.Date(firstMs))
        val lastTime = timeSdf.format(java.util.Date(lastMs))
        return "[SUMMARY] type=$diagType time=$firstTime-$lastTime count=$count durationMs=$durationMs ${formatKey(key)}"
    }

    private fun formatKey(key: T): String {
        return when (key) {
            is VisCheckStableKey ->
                "pkg=${key.pkg} result=${key.result} windowMs=${key.windowMs} a11yWindows=${key.a11yWindows} lastVisibleRefreshed=${key.lastVisibleRefreshed}"
            is FgBypassStableKey ->
                "pkg=${key.pkg} guard=${key.guard} fallback=${key.fallback} forceRelock=${key.forceRelock} forcePost=${key.forcePost} detached=${key.detached} passed=${key.passed} src=${key.src} a11y=${key.a11y} inA11yWindows=${key.inA11yWindows}"
            is FgGuardStableKey ->
                "pkg=${key.pkg} src=${key.src} hasRecentFg=${key.hasRecentFg} pkgInA11y=${key.pkgInA11y} decision=${key.decision} a11yService=${key.a11yService}"
            is ShouldBlockStableKey ->
                "pkg=${key.pkg} isBlacklisted=${key.isBlacklisted} isDndLocked=${key.isDndLocked} isFreeTime=${key.isFreeTime} shouldBlock=${key.shouldBlock} isManaged=${key.isManaged} isAllowed=${key.isAllowed}"
            is AppStateStableKey ->
                "mode=${key.mode} a11y=${key.a11y} isDndLocked=${key.isDndLocked} isFreeTime=${key.isFreeTime} isPrivilege=${key.isPrivilege} lockedPkg=${key.lockedPkg} lastPkg=${key.lastPkg} lastSrc=${key.lastSrc}"
            else -> key.toString()
        }
    }

    /** 刷新：如果有未输出的累积，强制输出 SUMMARY */
    fun flush(nowMs: Long): List<String> {
        val key = lastKey ?: return emptyList()
        if (repeatCount > 1) {
            val summary = buildSummary(key, repeatCount, firstAtMs, lastAtMs)
            repeatCount = 0
            firstAtMs = nowMs
            lastAtMs = nowMs
            return listOf(summary)
        }
        return emptyList()
    }
}
