package com.decikeeper.app.data

import com.decikeeper.app.data.LogExporter
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test



/**
 * R3 Task 7 deterministic export gate.
 *
 * The fixture must cover every §3 legacy label group, the seven old evidence lines emitted by
 * `logForegroundResolution`, the two heartbeat variants, and four complete normal-event records.
 * After `LogExporter.compactLogsForExport` runs the JVM-only gate must report `legacy_count=0`,
 * `new_count >= 4`, `legacy_ratio <= 0.05`, and the first normal-event atomic block must survive
 * byte-identical.
 */
class LogExportReductionTest {

    @Test
    fun exportedFixtureExcludesEveryApprovedLegacyLabelAndKeepsNormalEventBlocks() {
        val fullAtomicForegroundBlock = listOf(
            "2026-07-13 08:30:00.000 [FG_DECISION] eventId=process-1 processInstanceId=process seq=1 reportId=FG-001 transition=桌面→抖音",
            "[1 结果] foreground=抖音 action=LOCK reason=BLACKLIST_SELECTED",
            "[2 状态] allowance=NONE appLock=LOCKED foregroundGuard=LOCKED",
            "[3 证据] UsageEvents=抖音 UsageStats=抖音 Accessibility=抖音/窗口一致",
            "[4 推断] source_converged=true conflict=false",
            "[5 锁屏] requestId=LOCK-001 result=LOCKED duplicate=false countedForPenalty=false"
        ).joinToString("\n")

        val raw = buildList<String> {
            // ── 1. §3 legacy label group lines (one per group) ──
            // Row 1: CoreService [DETECT]
            add("07-13 08:30:00.100 CoreService: [DETECT] Blacklist app: pkg=com.ss.android.ugc.aweme")
            // Row 2: CoreService [MINI_WINDOW_FALLBACK]
            add("07-13 08:30:00.200 CoreService: [MINI_WINDOW_FALLBACK] size=2 pkgs=[抖音, 微信]")
            // Row 3: MonitorService [EVENT] type=APP_STATE
            add("07-13 08:30:00.250 MonitorService: [EVENT] type=APP_STATE state=FG pkg=抖音")
            // Row 4: FG pkg=/FG_SUMMARY
            add("07-13 08:30:00.300 MonitorService: FG pkg=抖音 state=allow confidence=HIGH summary=true")
            add("07-13 08:30:00.301 MonitorService: FG_SUMMARY selected=抖音 source=EVENTS")
            // Row 5: [EVENT] type=NAVIGATION
            add("07-13 08:30:00.350 MonitorService: [EVENT] type=NAVIGATION from=桌面 to=抖音")
            // Row 6: [FG_BYPASS_DIAG]
            add("07-13 08:30:00.400 MonitorService: [FG_BYPASS_DIAG] skip=true reason=WHITELIST pkg=微信")
            // Row 7: [SHOULD_BLOCK_DIAG]
            add("07-13 08:30:00.450 MonitorService: [SHOULD_BLOCK_DIAG] should=false pkg=微信")
            // Row 8: [EVENT] type=BLOCK_DECISION
            add("07-13 08:30:00.500 MonitorService: [EVENT] type=BLOCK_DECISION block=false pkg=微信")
            // Row 9 + 10: [FG_GUARD_DIAG] (two sites share the same label)
            add("07-13 08:30:00.520 MonitorService: [FG_GUARD_DIAG] candidate=抖音 guard=PASS")
            add("07-13 08:30:00.540 MonitorService: [FG_GUARD_DIAG] candidate=微信 guard=VETO")
            // Row 11: [VIS_CHECK_DIAG]
            add("07-13 08:30:00.600 MonitorService: [VIS_CHECK_DIAG] pkg=抖音 visible=true")
            // Row 12: [EVENT] type=VIS_DECISION
            add("07-13 08:30:00.650 MonitorService: [EVENT] type=VIS_DECISION visible=true")
            // Row 13: legacy seven-line evidence block (Task 2)
            val traceId = "LOCK-20260713-083000-0001"
            add("07-13 08:30:00.700 MonitorService: $traceId 前台结论: 抖音 | 选用来源=EVENTS | 置信度=HIGH | 来源冲突=false | 判断时间=2026-07-13 08:30:00.700")
            add("07-13 08:30:00.700 MonitorService: $traceId Accessibility: 可用")
            add("07-13 08:30:00.700 MonitorService: $traceId UsageEvents: #1 抖音@2026-07-13 08:29:58.000 距判断=2700ms → #2 微信")
            add("07-13 08:30:00.700 MonitorService: $traceId UsageStats: #1 抖音@2026-07-13 08:29:58.500 距判断=2200ms")
            add("07-13 08:30:00.700 MonitorService: $traceId 内部状态: lastPkg=抖音@2026-07-13 08:29:58.000 状态年龄=2700ms | 来源=EVENTS | 不参与决策")
            add("07-13 08:30:00.700 MonitorService: $traceId 判断理由: UsageEvents 记录抖音最新 | 最终选择: EVENTS")
            add("07-13 08:30:00.700 MonitorService: $traceId 锁机上下文: 锁定原因=BLACKLIST | traceId=$traceId | decision=LOCK")
            // Row 14: [锁机]
            add("07-13 08:30:00.750 MonitorService: [锁机] Blacklist app: pkg=com.ss.android.ugc.aweme")
            // Row 15: [RESTORE_REQUEST]
            add("07-13 08:30:00.800 CoreService: [RESTORE_REQUEST] pkg=com.decikeeper.app")
            // Row 16: [AUTO_RELEASE]
            add("07-13 08:30:00.850 MonitorService: [AUTO_RELEASE] Skip: target=com.decikeeper.app")
            // Row 17: LockScreen 解锁
            add("07-13 08:30:00.900 LockWindowManager: LockScreen: 解锁: reason=USER requestId=LOCK-001")
            // Row 18: LockScreen 回到桌面
            add("07-13 08:30:00.950 LockWindowManager: LockScreen: 🏠 回到桌面: pkg=com.ss.android.ugc.aweme")
            // Row 19: TempAccess 临时解锁
            add("07-13 08:30:01.000 LockWindowManager: TempAccess: 临时解锁: pkg=com.ss.android.ugc.aweme")
            // Row 20: [BORROW_UI_DEBUG][tickUI]
            add("07-13 08:30:01.050 LockWindowManager: [BORROW_UI_DEBUG][tickUI] remaining=120 cost=5")
            // Row 21: [TIME_CONSUME]
            add("07-13 08:30:01.100 CreditManager: [TIME_CONSUME] amount=5 reason=LOCK")
            // Row 22: [STEP1]/[DONE] penalty trace (first and last lines)
            add("07-13 08:30:01.150 CreditManager: [STEP1] penalty candidate pkg=抖音")
            add("07-13 08:30:01.200 CreditManager: [STEP2] confirming penalty candidate pkg=抖音")
            add("07-13 08:30:01.250 CreditManager: [STEP3] applying penalty candidate pkg=抖音")
            add("07-13 08:30:01.300 CreditManager: [DONE] penalty applied pkg=抖音 amount=10")
            // Row 23: 每日状态播报
            add("══════ 每日状态播报 ══════")
            add("原因: BLACKLIST_SELECTED")
            add("当前时间: 08:30:01")
            add("业务日: 20260713")
            add("剩余: 120 分钟")
            // Row 24/26: heartbeats (two variants)
            add("07-13 08:30:01.400 AlarmHeartbeatWorker: 💓 心跳(worker) cycle=A")
            add("07-13 08:30:01.500 AlarmGuardianService: 💓 心跳 cycle=G")
            // Row 27/33 + raw lifecycle ring/snooze/dismiss
            add("07-13 08:30:01.600 AlarmWindowManager: ring show=true pkg=抖音")
            add("07-13 08:30:01.700 LockWindowManager: snooze action=60s pkg=抖音")
            // Task 5 service lifecycle raw lines
            add("07-13 08:30:01.800 CoreService: onServiceConnected")
            add("07-13 08:30:01.900 CoreService: onUnbind reason=USER")
            add("07-13 08:30:02.000 CoreService: onRebind")
            // Legacy drop patterns already declared in LogExporter
            add("07-13 08:30:02.050 LockWindowManager: TemporaryAccess: [FINAL_RELOCK_SUCCEEDED] cleared flag")
            add("07-13 08:30:02.060 LockWindowManager: LockScreen: [REASSERT]")

            // ── 2. Four complete normal-event records ──
            addAll(fullAtomicForegroundBlock.lines())
            // Normal-event 2 — LOCK_EXIT
            add("2026-07-13 08:31:00.000 [LOCK_EXIT] eventId=process-2 processInstanceId=process seq=1 reportId=LOCK-002")
            add("[1 结果] exit=RETURN_DESKTOP reason=USER")
            add("[2 状态] allowance=NONE appLock=UNLOCKED")
            add("[3 证据] evidence=EVENTS")
            // Normal-event 3 — QUOTA_CHANGE
            add("2026-07-13 08:32:00.000 [REST_QUOTA_CHANGE] eventId=process-3 processInstanceId=process seq=1 reportId=QUOTA-003")
            add("[1 结果] delta=-5 remaining=115 reason=LOCK")
            add("[2 状态] allowance=NONE")
            // Normal-event 4 — ALARM_TRIGGER
            add("2026-07-13 08:33:00.000 [ALARM_TRIGGER] eventId=process-4 processInstanceId=process seq=1 reportId=ALARM-004")
            add("[1 结果] fired=true pkg=抖音")
        }

        val output = LogExporter.compactLogsForExport(raw)

        // Approved legacy classifier list (default-drop regexes that the gate enforces).
        val legacyClassifiers = listOf(
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
            Regex("""前台结论:"""),
            Regex("""Accessibility:\s+(?:可用|不可用)"""),
            Regex("""UsageEvents:\s+#1"""),
            Regex("""UsageStats:\s+#1"""),
            Regex("""内部状态:"""),
            Regex("""判断理由:"""),
            Regex("""锁机上下文:"""),
            Regex("""\[锁机\]"""),
            Regex("""\[RESTORE_REQUEST\]"""),
            Regex("""\[BORROW_UI_DEBUG\]"""),
            Regex("""\[TIME_CONSUME\]"""),
            Regex("""\[STEP1\]"""),
            Regex("""\[STEP2\]"""),
            Regex("""\[STEP3\]"""),
            Regex("""\[DONE\] penalty applied"""),
            Regex("""══════ 每日状态播报 ══════"""),
            Regex("""💓 心跳"""),
            Regex("""onServiceConnected"""),
            Regex("""onUnbind"""),
            Regex("""onRebind"""),
            Regex("""TemporaryAccess: \[FINAL_RELOCK_SUCCEEDED] cleared flag"""),
            Regex("""LockScreen: \[REASSERT]"""),
            Regex("""LockScreen: 解锁:"""),
            Regex("""LockScreen: 🏠 回到桌面:"""),
            Regex("""TempAccess: 临时解锁:"""),
            Regex("""ring show=true"""),
            Regex("""snooze action="""),
            Regex("""\[AUTO_RELEASE\]""")
        )

        val legacyCount = output.count { line ->
            legacyClassifiers.any { it.containsMatchIn(line) }
        }
        val newCount = output.count { line ->
            Regex("""^\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3} \[[^]]+]\s+eventId=""").containsMatchIn(line)
        }
        val totalNonBlank = output.count { it.isNotBlank() }.coerceAtLeast(1)
        val legacyRatio = legacyCount.toDouble() / totalNonBlank.toDouble()

        println("legacy_count=$legacyCount")
        println("new_count=$newCount")
        println("legacy_ratio=${"%.4f".format(Locale.US, legacyRatio)}")

        assertEquals(0, legacyCount)
        assertTrue(newCount >= 4)
        assertTrue(legacyRatio <= 0.05)
        val outputAsText = output.joinToString("\n")
        assertTrue(outputAsText.contains(fullAtomicForegroundBlock))
    }
}
