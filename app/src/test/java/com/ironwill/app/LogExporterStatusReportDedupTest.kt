package com.ironwill.app

import com.ironwill.app.data.LogExporter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test



/**
 * LogExporter.compactLogsForExport 中"状态播报多行块"去重的单元测试。
 *
 * 复现用户报告: 导出 10 分钟日志, 全部是相同的"状态播报"(重复 N 次同样的额度信息)。
 *
 * 修复策略:
 * - 识别 `原因: ...` 开头的连续行为一块
 * - 同一 fingerprint（去除"当前时间"和"业务日"行后）的连续块只保留首块 + 1 行摘要
 * - traceId 行 (`LOCK-...`) 不被任何块识别逻辑影响
 */
class LogExporterStatusReportDedupTest {

    /** 构造一个完整的状态播报块, 时长/次数/段信息可定制 */
    private fun statusBlock(
        reason: String,
        time: String,
        bizDate: String = "20260706",
        segName: String = "上午",
        segRange: String = "08:30-12:00",
        segLimit: String = "30min, 6次",
        mins: String = "19min",
        counts: String = "2.5次",
        prevMins: String = "0min",
        prevCounts: String = "0次"
    ): List<String> = listOf(
        "原因: $reason",
        "业务日: $bizDate",
        "当前时间: $time",
        "📍 勿扰时段: 活跃中",
        "   当前段: $segName ($segRange)",
        "   限额: 时长 $segLimit",
        "📊 可用额度:",
        "   时长: $mins (前序结余 $prevMins, 已借 0min)",
        "   次数: $counts (前序结余 $prevCounts, 已借 0次)"
    )

    @Test
    fun consecutiveIdenticalStatusBlocks_areCollapsedIntoOneWithDedupSummary() {
        // 3 个连续、内容相同、只有时间戳不同的状态播报块
        val raw = mutableListOf<String>()
        raw += statusBlock("HOME_LAUNCH", "10:43:01")
        raw += ""  // 块间空行
        raw += statusBlock("HOME_LAUNCH", "10:43:14")
        raw += ""
        raw += statusBlock("HOME_LAUNCH", "10:44:00")

        val out = LogExporter.compactLogsForExport(raw)

        // 期望: 只保留 1 个完整块 + 1 行 dedup 摘要
        val blockLines = out.filter { it.startsWith("原因: ") }
        assertEquals("应只保留 1 个块开头", 1, blockLines.size)
        assertTrue("应包含 dedup 摘要", out.any { it.startsWith("[dedup 2 个相同状态播报块") })
    }

    @Test
    fun differentQuotaBlocks_areNotDeduped() {
        // 两个块的额度不同(时长从 19min 变到 17.5min), 应当都保留
        val raw = mutableListOf<String>()
        raw += statusBlock("HOME_LAUNCH", "10:43:01", mins = "19min", counts = "2.5次")
        raw += ""
        raw += statusBlock("HOME_LAUNCH", "10:44:00", mins = "17.5min", counts = "2次")

        val out = LogExporter.compactLogsForExport(raw)

        val blockLines = out.filter { it.startsWith("原因: ") }
        assertEquals("不同额度块不应去重", 2, blockLines.size)
        assertFalse("不应出现 dedup 摘要", out.any { it.startsWith("[dedup ") })
    }

    @Test
    fun nonAdjacentIdenticalBlocks_areNotDeduped() {
        // 块1 和 块3 内容相同, 中间夹了块2(额度不同). 块1 和块3 不相邻, 都不应被合并
        val raw = mutableListOf<String>()
        raw += statusBlock("HOME_LAUNCH", "10:43:01", mins = "19min", counts = "2.5次")
        raw += ""
        raw += statusBlock("HOME_LAUNCH", "10:44:00", mins = "17.5min", counts = "2次")
        raw += ""
        raw += statusBlock("HOME_LAUNCH", "10:45:00", mins = "19min", counts = "2.5次")

        val out = LogExporter.compactLogsForExport(raw)

        val blockLines = out.filter { it.startsWith("原因: ") }
        assertEquals("非相邻同 fp 块不应去重", 3, blockLines.size)
        assertFalse("不应出现 dedup 摘要", out.any { it.startsWith("[dedup ") })
    }

    @Test
    fun singleStatusBlock_isKeptVerbatim_withoutDedupSummary() {
        val raw = statusBlock("HOME_LAUNCH", "10:43:01")

        val out = LogExporter.compactLogsForExport(raw)

        // 单块不应附加 dedup 摘要
        assertFalse("单块不应附加 dedup 摘要", out.any { it.startsWith("[dedup ") })
        // 块内每个非空行都应保留
        for (line in raw) {
            if (line.isNotBlank()) {
                assertTrue("应保留: $line", out.contains(line))
            }
        }
    }

    @Test
    fun traceIdLines_areNeverTreatedAsStatusBlocks() {
        // 混合 traceId 行 + 状态播报块. traceId 行必须原样保留.
        val raw = mutableListOf<String>()
        raw += "07-06 10:43:01.000 LockWindowManager: [LOCK-20260706-104301-1234] detected blacklist app"
        raw += "07-06 10:43:01.500 LockWindowManager: LOCK-20260706-104301-1234 lock screen shown"
        raw += ""
        raw += statusBlock("LOCK_UI", "10:43:14")
        raw += ""
        raw += "07-06 10:43:14.500 LockWindowManager: LOCK-20260706-104301-1234 force reassert after 2s"

        val out = LogExporter.compactLogsForExport(raw)

        // 所有 LOCK- 行必须原样保留
        assertTrue(out.any { it.contains("LOCK-20260706-104301-1234") && it.contains("detected blacklist app") })
        assertTrue(out.any { it.contains("LOCK-20260706-104301-1234") && it.contains("lock screen shown") })
        assertTrue(out.any { it.contains("LOCK-20260706-104301-1234") && it.contains("force reassert") })
        // 状态播报块也要保留(单块, 不带 dedup 摘要)
        assertTrue(out.any { it.startsWith("原因: LOCK_UI") })
        assertFalse(out.any { it.startsWith("[dedup ") })
    }

    @Test
    fun consecutiveIdenticalBlocksWithDedupTimestampRange_includesFirstAndLastTime() {
        // 4 个连续相同块, 首块时间 10:43:01, 末块时间 10:50:00
        val raw = mutableListOf<String>()
        raw += statusBlock("HOME_LAUNCH", "10:43:01")
        raw += ""
        raw += statusBlock("HOME_LAUNCH", "10:45:00")
        raw += ""
        raw += statusBlock("HOME_LAUNCH", "10:48:30")
        raw += ""
        raw += statusBlock("HOME_LAUNCH", "10:50:00")

        val out = LogExporter.compactLogsForExport(raw)
        println("===DEBUG timestamp range test output===")
        out.forEach { println("  | $it") }
        println("===END===")

        val dedupLine = out.firstOrNull { it.startsWith("[dedup ") }
        assertTrue("应出现 dedup 摘要行", dedupLine != null)
        // 摘要应包含 3 (= 4 - 1) 个被合并块的信息
        assertTrue("摘要应包含合并数", dedupLine!!.contains("3 个相同状态播报块"))
        // 摘要应包含首末时间
        assertTrue("摘要应包含首块时间 (got: $dedupLine)", dedupLine.contains("10:43:01"))
        assertTrue("摘要应包含末块时间 (got: $dedupLine)", dedupLine.contains("10:50:00"))
    }

    @Test
    fun emptyInput_returnsEmptyOutput() {
        val out = LogExporter.compactLogsForExport(emptyList())
        assertTrue(out.isEmpty())
    }

    @Test
    fun unrelatedLines_areNotAffectedByStatusBlockDedup() {
        // 没有"原因:"开头的块, 模拟原有 lastOutputLine 去重逻辑:
        // 两条完全相同相邻行, 第二条应被去重
        val raw = listOf(
            "CreditManager: 借出 30s 休息",
            "CreditManager: 借出 30s 休息",  // 完全相同相邻行, 应被去重
            "CreditManager: 借出 60s 休息"   // 不相同, 保留
        )
        val out = LogExporter.compactLogsForExport(raw)
        println("===DEBUG unrelated test output===")
        out.forEach { println("  | $it") }
        println("===END===")

        // 完全相同的相邻行应被去重(原有 lastOutputLine 逻辑)
        assertEquals(2, out.size)
        assertTrue(out[0].contains("借出 30s 休息"))
        assertTrue(out[1].contains("借出 60s 休息"))
    }

    @Test
    fun statusBlocksSeparatedByOtherEvents_areTreatedIndependently() {
        // 块1 → 其他事件 → 块1(同 fp). 但因为中间夹了其他事件, 不再相邻, 都保留
        val raw = mutableListOf<String>()
        raw += statusBlock("HOME_LAUNCH", "10:43:01")
        raw += ""
        raw += "07-06 10:44:00.000 CreditManager: 借出 30s 休息"
        raw += ""
        raw += statusBlock("HOME_LAUNCH", "10:45:00")

        val out = LogExporter.compactLogsForExport(raw)

        val blockLines = out.filter { it.startsWith("原因: ") }
        assertEquals("被其他事件隔开的同 fp 块不合并", 2, blockLines.size)
        assertFalse("不应出现 dedup 摘要", out.any { it.startsWith("[dedup ") })
        // 中间事件也要保留
        assertTrue(out.any { it.contains("借出 30s 休息") })
    }

    @Test
    fun realSampleFromUser_exportedLogHasFarFewerStatusBlocks() {
        // 复现用户提供的样本: 23 个连续状态播报块中, 前 11 个内容相同
        // (前 9 块是"42min/4.5次"段, 中间夹 2 个 LOCK_UI 后又是"42min/4.5次")
        // 修复后期望: 同 fp 的相邻块被合并, 跨段(额度变化)不合并
        val raw = mutableListOf<String>()
        // 段1: 11 个连续 HOME_LAUNCH, 额度相同 (用 %02d 秒数避免 i=10 时产生 "12:510:00" 这种畸形时间)
        repeat(11) { i ->
            if (i > 0) raw += ""
            raw += statusBlock("HOME_LAUNCH", "12:50:%02d".format(i * 5), mins = "42min", counts = "4.5次")
        }
        // 段2: 2 个连续 HOME_LAUNCH, 额度变化到 40.5min/4次
        repeat(2) { i ->
            raw += ""
            raw += statusBlock("HOME_LAUNCH", "12:56:${30 + i * 20}", mins = "40.5min", counts = "4次")
        }

        val out = LogExporter.compactLogsForExport(raw)
        println("===DEBUG real sample test output===")
        out.forEach { println("  | $it") }
        println("===END===")

        // 11 个相邻同 fp 块 → 1 个完整块 + 1 行 dedup 摘要 (dedup 10)
        // 2 个相邻(同段)块 → 1 个完整块 + 1 行 dedup 摘要 (dedup 1)
        val blockLines = out.filter { it.startsWith("原因: ") }
        assertEquals("应只保留 2 个状态播报块开头", 2, blockLines.size)

        val dedupLines = out.filter { it.startsWith("[dedup ") }
        assertEquals("应出现 2 行 dedup 摘要 (got: ${dedupLines.size})", 2, dedupLines.size)
        assertTrue("第 1 段应合并 10 个 (got: ${dedupLines.getOrNull(0)})", dedupLines[0].contains("10 个相同状态播报块"))
        assertTrue("第 2 段应合并 1 个 (got: ${dedupLines.getOrNull(1)})", dedupLines[1].contains("1 个相同状态播报块"))
    }

    @Test
    fun mixedLegacyAndFutureAtomicForegroundRecord_remainsReadableAndWhole() {
        val raw = listOf(
            "07-13 08:30:00.000 CoreService: [DETECT] Blacklist app: pkg=com.ss.android.ugc.aweme",
            "2026-07-13 08:30:00.123 [FG_DECISION] eventId=proc-1 processInstanceId=proc seq=1 reportId=FG-001 transition=桌面→抖音",
            "[1 结果] foreground=抖音 action=LOCK reason=BLACKLIST_SELECTED",
            "[2 状态] allowance=NONE appLock=LOCKED timeWindowLock=LOCKED",
            "[3 证据] UsageEvents=桌面→抖音 UsageStats=桌面→抖音 Accessibility=桌面→抖音/窗口一致",
            "[5 锁屏] requestId=LOCK-001 result=LOCKED duplicate=false countedForPenalty=false"
        )

        val out = LogExporter.compactLogsForExport(raw)

        // R3 Task 7 default-export gate drops the leading `[DETECT]` legacy line;
        // the FG_DECISION normal-event atomic block must survive intact and in order.
        val expected = listOf(
            "2026-07-13 08:30:00.123 [FG_DECISION] eventId=proc-1 processInstanceId=proc seq=1 reportId=FG-001 transition=桌面→抖音",
            "[1 结果] foreground=抖音 action=LOCK reason=BLACKLIST_SELECTED",
            "[2 状态] allowance=NONE appLock=LOCKED timeWindowLock=LOCKED",
            "[3 证据] UsageEvents=桌面→抖音 UsageStats=桌面→抖音 Accessibility=桌面→抖音/窗口一致",
            "[5 锁屏] requestId=LOCK-001 result=LOCKED duplicate=false countedForPenalty=false"
        )
        assertEquals("原子化 normal-event 块必须逐字节保留压缩输出", expected, out)
        assertEquals(1, out.count { it.contains("eventId=proc-1") })
        assertEquals(5, out.dropWhile { !it.contains("eventId=proc-1") }.size)
        assertTrue("应已剥离默认 [DETECT] legacy 行", out.none { it.contains("[DETECT]") })
    }
}
