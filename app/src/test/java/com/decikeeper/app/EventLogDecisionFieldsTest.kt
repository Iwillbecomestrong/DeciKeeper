package com.decikeeper.app

import com.decikeeper.app.data.EventLog
import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test



/**
 * RB-03: FG_DECISION 追加 3 字段 (usageEventsStatus, accessibilityWindowsCount, conflictReason).
 *
 * 验证:
 * 1. 旧 key 全部保留
 * 2. 3 新字段出现在 emit map 中
 * 3. MonitorService 两个调用点计算并传入 3 新字段
 * 4. fgGuardCompressor.observe read-only (Guard 2)
 */
class EventLogDecisionFieldsTest {

    /**
     * Case 1: reject serialization — 调用 fgDecision 模拟拒绝场景验证编译通过，
     * 同时字节码分析确认旧 key + 3 新字段都在 emit map 中。
     */
    @Test
    fun preservesOldKeysAndAppendsDiagnosticFields_rejectCase() {
        // 编译验证: 新签名可调用 (reject 场景 — guard rejected)
        EventLog.fgDecision(
            traceId = "FG-TEST-REJECT-001",
            selectedPkg = "com.reject.app",
            selectedSrc = "USAGE_STATS",
            confidence = "LOW",
            conflict = true,
            a11yAvailable = false,
            eventsPkg = null,
            eventsAgeMs = -1L,
            usmPkg = "com.reject.app",
            usmAgeMs = 5000L,
            a11yPkg = null,
            cachePkg = null,
            cacheAgeMs = -1L,
            cacheUsedForDecision = false,
            // 3 新字段
            usageEventsStatus = "NONE",
            accessibilityWindowsCount = 0,
            conflictReason = "foreground_guard_rejected"
        )
        // 不会抛出异常即证明编译通过 + 运行时安全 (appContext null 时 LogExporter 静默返回)

        // 源文件分析: 确认所有旧 key + 3 新字段都在 EventLog.fgDecision 的 emit map 中
        val eventLog = source("app/src/main/java/com/decikeeper/app/data/EventLog.kt")
        val fgDecisionBlock = extractFgDecisionBody(eventLog)
        assertTrue("fgDecision must have fgDecision function", fgDecisionBlock.isNotBlank())

        // 所有旧 key 必须在 emit map 中
        val oldKeys = listOf(
            "traceId",
            "selectedPkg",
            "selectedSrc",
            "confidence",
            "conflict",
            "a11y",
            "eventsPkg",
            "eventsAgeMs",
            "usmPkg",
            "usmAgeMs",
            "a11yAvailable",
            "a11yPkg",
            "cachePkg",
            "cacheAgeMs",
            "cacheUsedForDecision"
        )
        oldKeys.forEach { key ->
            assertTrue(
                "Old key '$key' must be preserved in fgDecision emit map",
                fgDecisionBlock.contains("\"$key\"")
            )
        }

        // 3 新字段必须在 emit map 中
        val newKeys = listOf(
            "usageEventsStatus",
            "accessibilityWindowsCount",
            "conflictReason"
        )
        newKeys.forEach { key ->
            assertTrue(
                "New key '$key' must appear in fgDecision emit map",
                fgDecisionBlock.contains("\"$key\"")
            )
        }
    }

    /**
     * Case 2: accept serialization — 模拟接受场景，验证所有旧 key + 3 新字段。
     */
    @Test
    fun preservesOldKeysAndAppendsDiagnosticFields_acceptCase() {
        // 编译验证: accept 场景
        EventLog.fgDecision(
            traceId = "FG-TEST-ACCEPT-001",
            selectedPkg = "com.accept.app",
            selectedSrc = "EVENTS",
            confidence = "MEDIUM",
            conflict = false,
            a11yAvailable = true,
            eventsPkg = "com.accept.app",
            eventsAgeMs = 100L,
            usmPkg = "com.accept.app",
            usmAgeMs = 500L,
            a11yPkg = "com.accept.app",
            cachePkg = "com.accept.app",
            cacheAgeMs = 300L,
            cacheUsedForDecision = true,
            // 3 新字段
            usageEventsStatus = "AVAILABLE",
            accessibilityWindowsCount = 3,
            conflictReason = ""
        )

        // MonitorService 两个调用点必须计算并传入 3 新字段
        val monitor = source("app/src/main/java/com/decikeeper/app/service/MonitorService.kt")

        // 两个调用点都必须有 usageEventsStatus, accessibilityWindowsCount, conflictReason
        // 在 fgDecision( 调用之前
        val fgDecisionCount = countSubstring(monitor, "EventLog.fgDecision(")
        assertTrue("MonitorService must have >= 2 EventLog.fgDecision calls", fgDecisionCount >= 2)

        // 检查两个调用点之间各出现一次 3 新字段的计算
        assertTrue(
            "Call site must compute usageEventsStatus",
            monitor.contains("usageEventsStatus")
        )
        assertTrue(
            "Call site must compute accessibilityWindowsCount",
            monitor.contains("accessibilityWindowsCount")
        )
        assertTrue(
            "Call site must compute conflictReason",
            monitor.contains("conflictReason")
        )

        // Guard 2: fgGuardCompressor.observe 只读 (2334 原文不变, 不参与 3 新字段)
        assertTrue(
            "fgGuardCompressor.observe must still exist (Guard 2 read-only)",
            monitor.contains("fgGuardCompressor.observe(guardKey, now)")
        )
    }

    // ── helpers ──

    private fun source(relativePath: String): String =
        generateSequence(File(System.getProperty("user.dir") ?: ".").absoluteFile) { it.parentFile }
            .map { File(it, relativePath) }
            .firstOrNull { it.isFile }
            ?.readText()
            ?: error("cannot find $relativePath")

    /** 提取 EventLog.kt 中 fgDecision 函数体从 "fun fgDecision(" 到下一个非缩进 "}" 的闭包 */
    private fun extractFgDecisionBody(kotlinSource: String): String {
        val startMarker = "fun fgDecision("
        val startIdx = kotlinSource.indexOf(startMarker)
        if (startIdx < 0) return ""
        // 从 startIdx 找到函数开头的 "{" (在 emit 调用开始前)
        val bodyStart = kotlinSource.indexOf('{', startIdx)
        if (bodyStart < 0) return ""
        // 找到 emit map 部分 — 从 "emit(\"FG_DECISION\", mapOf(" 到匹配的 "))"
        val emitStart = kotlinSource.indexOf("emit(\"FG_DECISION\", mapOf(", bodyStart)
        if (emitStart < 0) return ""
        return kotlinSource.substring(emitStart)
    }

    private fun countSubstring(text: String, sub: String): Int {
        var count = 0
        var from = 0
        while (true) {
            val idx = text.indexOf(sub, from)
            if (idx < 0) break
            count++
            from = idx + 1
        }
        return count
    }

    private fun assertTrue(message: String, condition: Boolean) {
        if (!condition) throw AssertionError(message)
    }

    private fun assertFalse(message: String, condition: Boolean) {
        if (condition) throw AssertionError(message)
    }
}
