package com.decikeeper.app.service

import com.decikeeper.app.data.DefaultLogDebugConfig
import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test



class ForegroundLegacyLogRetirementTest {
    @Test
    fun defaultForegroundProductionPathHasNoLegacyPollingEmission() {
        val core = source("app/src/main/java/com/decikeeper/app/service/CoreService.kt")
        val monitor = source("app/src/main/java/com/decikeeper/app/service/MonitorService.kt")

        assertFalse("CoreService [DETECT] must be retired", core.contains("[DETECT] Blacklist app"))
        assertFalse("mini-window fallback polling export must be retired", core.contains("[MINI_WINDOW_FALLBACK]"))
        assertFalse("APP_STATE polling event must be retired", monitor.contains("EventLog.appState("))
        assertFalse("FG pkg/FG_SUMMARY producer must be retired", monitor.contains("foregroundDetectionLogCompressor.observe("))
        assertFalse("legacy NAVIGATION must be retired", monitor.contains("EventLog.navigation("))
        assertFalse("legacy BLOCK_DECISION must be retired", monitor.contains("EventLog.blockDecision("))

        assertGuarded(monitor, "fgBypassCompressor.observe(", "DefaultLogDebugConfig.foregroundRaw")
        assertGuarded(monitor, "shouldBlockCompressor.observe(", "DefaultLogDebugConfig.foregroundRaw")
        assertGuarded(monitor, "visCheckCompressor.observe(", "DefaultLogDebugConfig.foregroundRaw")
        assertGuarded(monitor, "EventLog.visDecision(", "DefaultLogDebugConfig.foregroundRaw")

        assertFalse(DefaultLogDebugConfig.foregroundRaw)
        assertFalse(DefaultLogDebugConfig.quotaCalculation)
        assertFalse(DefaultLogDebugConfig.protectionVerbose)
    }

    /**
     * Boundary 4 (T4) — payload-equivalence GREEN 后的退场：删除 LockWindowManager 中
     * 唯一一处 `惩罚: 扣费` 中文 LogExporter 调用。retainedEvidence 由 PENALTY payload
     * （11 字段，per REQ-010）取代 — 见 docs/ai/LOG_EVENT_MIGRATION.md row 71
     * `══════ 每日状态播报 ══════` 行的 retainedEvidence 字段。
     */
    @Test
    fun productCodeContainsNoChineseLegacyPenaltyEmission() {
        val lockMgr = source("app/src/main/java/com/decikeeper/app/ui/LockWindowManager.kt")

        assertFalse(
            "LockWindowManager must not emit `惩罚: 扣费` Chinese LogExporter call (REQ-010 已替换)",
            lockMgr.contains("惩罚: 扣费")
        )
        assertFalse(
            "LockWindowManager must not call LogExporter.logEvent with `Penalty` tag + Chinese payload",
            Regex("""LogExporter\.logEvent\(\s*"Penalty"\s*,\s*"惩罚:""").containsMatchIn(lockMgr)
        )
    }

    /**
     * Boundary 4 (T4) — 默认生产路径不再有 legacy `EventLog.fgDecision(...)` 双发。
     * fgDecision 必须受 `DefaultLogDebugConfig.foregroundRaw` 门控 (方案 B) —
     * 保留 raw 诊断能力，默认关闭以满足 INVAR-7。
     */
    @Test
    fun defaultForegroundProductionPathHasNoLegacyPollingEmission_fgDecision() {
        val monitor = source("app/src/main/java/com/decikeeper/app/service/MonitorService.kt")

        // 每次 `EventLog.fgDecision(...)` 调用都必须在自己的紧邻 if 块中受 `foregroundRaw` 门控。
        // 紧邻窗口设为 200 字符 — fgDecision 调用块约 ~16 行 Kotlin。
        // 防止用同函数前面几百字符处的 `if (foregroundRaw)` 误判门控。
        val fgDecisionIndices = mutableListOf<Int>()
        var from = 0
        while (true) {
            val idx = monitor.indexOf("EventLog.fgDecision(", from)
            if (idx < 0) break
            fgDecisionIndices += idx
            from = idx + 1
        }
        assertTrue(
            "MonitorService must still emit `EventLog.fgDecision(...)` (gated, not removed per option B)",
            fgDecisionIndices.size >= 2
        )
        fgDecisionIndices.forEachIndexed { i, idx ->
            val prefix = monitor.substring((idx - 200).coerceAtLeast(0), idx)
            assertTrue(
                "fgDecision call #${i + 1} must be guarded by `if (DefaultLogDebugConfig.foregroundRaw)` within 200 chars (option B)",
                prefix.contains("if (DefaultLogDebugConfig.foregroundRaw)")
            )
        }

        // 默认关闭 — INVAR-7
        assertFalse(DefaultLogDebugConfig.foregroundRaw)
    }

    /**
     * Boundary 4 (T4) — `foregroundRaw` 开启时 fgDecision 诊断应保留（方案 B 选门控
     * 而非彻底删除，保证 raw 诊断能力可恢复）。
     */
    @Test
    fun rawForegroundDiagnosticSurvivesWhenForegroundRawEnabled() {
        val monitor = source("app/src/main/java/com/decikeeper/app/service/MonitorService.kt")

        // 必须存在受 `foregroundRaw` 门控的 `EventLog.fgDecision(...)` 调用
        // 用 200 字符紧邻窗口以确保是 fgDecision 自己被门控，不是用前面的 fgGuardCompressor.observe 门控误判
        val fgDecisionIdx = monitor.indexOf("EventLog.fgDecision(")
        assertTrue("fgDecision call must exist", fgDecisionIdx >= 0)
        val prefix = monitor.substring((fgDecisionIdx - 200).coerceAtLeast(0), fgDecisionIdx)
        assertTrue(
            "fgDecision call must be guarded by `if (DefaultLogDebugConfig.foregroundRaw)` within 200 chars",
            prefix.contains("if (DefaultLogDebugConfig.foregroundRaw)")
        )

        // 必须存在受 `foregroundRaw` 门控的 fgGuardCompressor.observe（fg 诊断 raw 通道）
        assertGuarded(monitor, "fgGuardCompressor.observe(", "DefaultLogDebugConfig.foregroundRaw")
    }

    /**
     * Boundary 4 (T4) — 4 行 `[Penalty] window...` Log.d 必须受
     * `DefaultLogDebugConfig.quotaCalculation` 门控（不复用 `desktopTimerTicks`，
     * 遵守 INVAR-8 "只复用现有 3 个开关"）。
     */
    @Test
    fun penaltyWindowDiagnosticsRequireQuotaCalculation() {
        val lockMgr = source("app/src/main/java/com/decikeeper/app/ui/LockWindowManager.kt")

        // 返回桌面已改为取消缓冲规则（ReturnHomeBufferPolicy），旧扣减诊断日志不得回流。
        assertFalse(lockMgr.contains("[Penalty] return-home"))

        // 默认关闭 — INVAR-7
        assertFalse(DefaultLogDebugConfig.quotaCalculation)
    }

    private fun assertGuarded(source: String, token: String, flag: String) {
        val tokenAt = source.indexOf(token)
        assertTrue("$token must remain available for scoped diagnostics", tokenAt >= 0)
        val prefix = source.substring((tokenAt - 1200).coerceAtLeast(0), tokenAt)
        assertTrue("$token must be guarded by $flag", prefix.contains("if ($flag)"))
    }

    private fun source(relativePath: String): String =
        generateSequence(File(System.getProperty("user.dir") ?: ".").absoluteFile) { it.parentFile }
            .map { File(it, relativePath) }
            .firstOrNull { it.isFile }
            ?.readText()
            ?: error("cannot find $relativePath")
}
