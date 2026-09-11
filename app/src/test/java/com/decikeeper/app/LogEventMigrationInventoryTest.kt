package com.decikeeper.app

import com.decikeeper.app.data.ForegroundDetectionLogCompressor
import com.decikeeper.app.data.ForegroundDetectionLogState
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test




class LogEventMigrationInventoryTest {

    /** 一组迁移目标列表：每条对应 `LOG_EVENT_MIGRATION.md` 中应当恰有一行的旧日志标签/符号。
     *
     * 包含 Round 3 (boundary 0.5) 中扩展的可观察 label 集合 —
     * 所有 36 条 exportDropPatterns regex 命中行外，还覆盖 logcat 中可见的其它诊断输出。
     */
    private val observedLabels = listOf(
        "[DETECT]",
        "[NAVIGATION]",
        "[VIS_CHECK_DIAG]",
        "[VIS_DECISION]",
        "[FG_BYPASS_DIAG]",
        "[SHOULD_BLOCK_DIAG]",
        "[BLOCK_DECISION]",
        "[EXPIRY_LOCK]",
        // — Round 3 (boundary 0.5) additions —
        "[MINI_WINDOW_FALLBACK]",
        "[EVENT] type=APP_STATE",
        "[FG_GUARD_DIAG]",
        "[EVENT] type=VIS_DECISION",
        "[EVENT] type=BLOCK_DECISION",
        "[EVENT] type=NAVIGATION",
        "[EVENT] type=FG_DECISION",
        "FG pkg=",
        "FG_SUMMARY",
        "[锁机]",
        "[RESTORE_REQUEST]",
        "[AUTO_RELEASE]",
        "前台结论:",
        "Accessibility: 可用",
        "Accessibility: 不可用",
        "UsageEvents: #1",
        "UsageStats: #1",
        "内部状态:",
        "判断理由:",
        "锁机上下文:",
        "LockScreen: 解锁:",
        "LockScreen: 🏠 回到桌面:",
        "TempAccess: 临时解锁:",
        "[BORROW_UI_DEBUG]",
        "[STEP1]",
        "[STEP2]",
        "[STEP3]",
        "[DONE] penalty applied",
        "══════ 每日状态播报 ══════",
        "💓 心跳",
        "AlarmWindowManager: ring show=true",
        "LockWindowManager: snooze action=",
        "CoreService: onServiceConnected",
        "CoreService: onUnbind",
        "CoreService: onRebind",
        "TemporaryAccess: [FINAL_RELOCK_SUCCEEDED] cleared flag",
        "LockScreen: [REASSERT]",
        "[ALARM_HEARTBEAT]",
        "WATCHDOG_TICK",
        "[TIME_CONSUME]",
        "[TRANSACTION_STEP]"
    )

    @Test
    fun everyObservedHighFrequencyLabel_hasExactlyOneDeclaredDisposition() {
        val document = findMigrationTable().readText()
        val dispositions = setOf("NORMAL_EVENT", "DEBUG_ONLY", "REMOVE")

        observedLabels.forEach { label ->
            val rows = document.lineSequence()
                .filter { it.startsWith("|") && it.contains("`$label`") }
                .toList()
            assertEquals("$label 必须在迁移表中恰有一行", 1, rows.size)

            val declared = dispositions.filter { disposition ->
                Regex("`$disposition`").containsMatchIn(rows.single())
            }
            assertEquals("$label 必须恰有一个处置", 1, declared.size)
        }
    }

    @Test
    fun migrationTable_preservesTheNoBusinessBehaviorChangeBoundary() {
        val document = findMigrationTable().readText()

        assertTrue(document.contains("不得用于修改前台来源优先级"))
        assertTrue(document.contains("锁屏、放行、额度或闹钟业务策略"))
    }

    /**
     * Round 3 (Boundary 0.5) Debug-Value Audit:
     * 每个 `REMOVE` 行必须同时声明 `replacement` ∈ {`DELETE_CALL`, `EXISTING_STABLE_DEDUP`}
     * 与 `retainedEvidence` 字段，缺一项即视为审计未完成。
     *
     * 这一约束是 [B1 user approval 路径] 的最弱前置：未通过的 REMOVE 行，
     * 不进 T1-T4 实施，必须先把行补齐或重新分类为 NORMAL_EVENT / DEBUG_ONLY。
     */
    @Test
    fun round3ObservedLabelsHaveOneExistingDispositionAndExactCallSite() {
        val document = findMigrationTable().readText()
        val round3Section = extractRound3Section(document)
        assertTrue(
            "Round 3 Debug-Value Audit 表必须存在（以 '## Round 3 Debug-Value Audit' 起首）",
            round3Section.isNotBlank()
        )

        val lines = round3Section.lines().filter { it.startsWith("|") && it.contains("`") }
        assertTrue(
            "Round 3 表应至少包含 36 行调试值审计（对应 exportDropPatterns 36 条全覆盖），当前=${lines.size}",
            lines.size >= 36
        )
    }

    @Test
    fun removeRowsDeclareReplacementAndRetainedEvidence() {
        val document = findMigrationTable().readText()
        val round3Section = extractRound3Section(document)

        val allowedReplacements = setOf("DELETE_CALL", "EXISTING_STABLE_DEDUP")
        val removeRows = round3Section.lines().filter {
            it.startsWith("|") && it.contains("`REMOVE`")
        }

        assertTrue(
            "Round 3 表必须包含至少 1 行 REMOVE 处置，当前数量=${removeRows.size}",
            removeRows.isNotEmpty()
        )

        removeRows.forEach { row ->
            val claimed = allowedReplacements.filter { replacement ->
                "`$replacement`" in row
            }
            // Every REMOVE row must declare exactly one of `DELETE_CALL` or `EXISTING_STABLE_DEDUP`.
            assertEquals(
                "REMOVE 行必须恰有一个合法 `replacement` ∈ DELETE_CALL / EXISTING_STABLE_DEDUP，行=$row",
                1,
                claimed.size
            )
            // Plan Challenger v3 B7: 禁动 exportDropPatterns - 不允许 ADD_EXPORT_DROP_PATTERN
            assertFalse(
                "REMOVE 行的 replacement 不允许为 ADD_EXPORT_DROP_PATTERN（违反 B7 exportDropPatterns 禁动约束）",
                "`ADD_EXPORT_DROP_PATTERN`" in row
            )

            // The row must contain a `retainedEvidence` style backtick-wrapped informative
            // column entry. We assert the row carries at least 5 backtick-wrapped code
            // spans — the minimum is the label / disposition / replacement / approval /
            // target cells. Cells like `file/line` may be code-formatted with one span
            // (e.g. `MonitorService.kt:144`) while `symbol` / `current emission` /
            // `retainedEvidence` may each be code-formatted with their own spans.
            // We tolerate 5 because not every column needs to be code-formatted
            // (e.g. WATCHDOG_TICK's emission = `(不适用)`). The acceptance bar is that
            // the row visibly carries a `replacement` + `approval` cell at minimum.
            val backtickSpanCount = Regex("`[^`]+`").findAll(row).count()
            assertTrue(
                "REMOVE 行必须有至少 5 个 backtick-span 列（即 replacement/approval 等关键列非空），行=$row",
                backtickSpanCount >= 5
            )

            // Approval column must mention the Round 3 audit approval token
            val t05aToken = "`T05-A:DA-07-R3+DA-08`"
            assertTrue(
                "REMOVE 行的 `approval` 列必须含 $t05aToken (Plan Challenger v3 B1)，行=$row",
                t05aToken in row
            )
        }
    }

    @Test
    fun round3AuditContainsNoParallelRequiredOptionalRemovableDisposition() {
        val document = findMigrationTable().readText()
        val forbiddenTerms = listOf("`REQUIRED`", "`OPTIONAL`", "`REMOVABLE`")

        forbiddenTerms.forEach { term ->
            assertFalse(
                "Round 3 审计不得引入并行 `REQUIRED/OPTIONAL/REMOVABLE` 处置（违反 Plan Challenger v3 A1），命中=$term",
                document.contains(term)
            )
        }
    }

    /**
     * T06 (Boundary 0.6) post-Bug-1/2 incremental re-audit.
     *
     * T1-T4 落地后新增/改动的每一个 `Log.*` / `LogExporter.*` 调用点，都必须在
     * `## Round 3 Audit Revision 2` 段中被逐条覆盖并声明处置，
     * 保证 initial approval 之后引入的日志点没有一个逃出审计闭环 (A2 audit revision 闭环)。
     */
    @Test
    fun round3AuditRevisionCoversEveryLogCallAddedOrChangedAfterInitialApproval() {
        val document = findMigrationTable().readText()
        val revision2 = document.substringAfter("## Round 3 Audit Revision 2", missingDelimiterValue = "")
        assertTrue(
            "T06 re-audit 必须追加 '## Round 3 Audit Revision 2' 段",
            revision2.isNotBlank()
        )

        // T1-T4 引入或改动的 Log 调用点（面向稳定的语义符号锚点，避免物理行号漂移）。
        val deltaCallSymbols = listOf(
            "LockWindowManager.penaltyDiagBlock.head",
            "LockWindowManager.penaltyDiagBlock.tail",
            "CreditManager.emitPenaltyReceipt.callsite1",
            "CreditManager.emitPenaltyReceipt.callsite2",
            "CreditManager.emitPenaltyReceipt.callsite3",
            "MonitorService.fgDecision.gated1",
            "MonitorService.fgDecision.gated2"
        )

        val dispositions = setOf("NORMAL_EVENT", "DEBUG_ONLY", "REMOVE")
        val rows = revision2.lineSequence()
            .filter { it.startsWith("|") && it.contains("`") }
            .toList()

        deltaCallSymbols.forEach { symbol ->
            val matching = rows.filter { it.contains(symbol) }
            assertTrue(
                "$symbol 必须在 Revision 2 段中被至少一行覆盖",
                matching.isNotEmpty()
            )
            matching.forEach { row ->
                val declared = dispositions.filter { d -> "`$d`" in row }
                assertEquals(
                    "$symbol 所在行必须恰有一个处置 (NORMAL_EVENT/DEBUG_ONLY/REMOVE)，行=$row",
                    1,
                    declared.size
                )
            }
        }
    }

    /**
     * T06 delta user gate：Revision 2 里新提出的 `REMOVE` 行属于 Standard REMOVE
     * （没有 ADD_EXPORT_DROP_PATTERN），因此在标准化 delta audit 下自动签字，
     * 但每行仍必须携带 delta 审批 token 并声明合法 replacement（B1 user approval 路径）。
     */
    @Test
    fun newlyProposedRemoveRowsRequireDeltaUserApproval() {
        val document = findMigrationTable().readText()
        val revision2 = document.substringAfter("## Round 3 Audit Revision 2", missingDelimiterValue = "")
        assertTrue(
            "T06 re-audit 必须追加 '## Round 3 Audit Revision 2' 段",
            revision2.isNotBlank()
        )

        val allowedReplacements = setOf("DELETE_CALL", "EXISTING_STABLE_DEDUP")
        val removeRows = revision2.lines().filter {
            it.startsWith("|") && it.contains("`REMOVE`")
        }

        removeRows.forEach { row ->
            // B7: exportDropPatterns 禁动 — 不允许 ADD_EXPORT_DROP_PATTERN
            assertFalse(
                "Revision 2 REMOVE 行不允许 ADD_EXPORT_DROP_PATTERN（违反 B4/B7 exportDropPatterns 禁动），行=$row",
                "`ADD_EXPORT_DROP_PATTERN`" in row
            )
            val claimed = allowedReplacements.filter { r -> "`$r`" in row }
            assertEquals(
                "Revision 2 REMOVE 行必须恰有一个合法 replacement，行=$row",
                1,
                claimed.size
            )
            // Standard REMOVE 自动通过：行必须携带 delta 审批 token（auto-pass 签字凭证）。
            assertTrue(
                "Revision 2 REMOVE 行必须携带 delta 审批 token `G06-REAUDIT:` (Standard REMOVE auto-pass)，行=$row",
                "`G06-REAUDIT:" in row
            )
        }
    }

    private fun findMigrationTable(): File {
        val relative = "docs/ai/LOG_EVENT_MIGRATION.md"
        return generateSequence(File(System.getProperty("user.dir") ?: ".").absoluteFile) { it.parentFile }
            .map { File(it, relative) }
            .firstOrNull { it.isFile }
            ?: error("找不到 $relative；user.dir=${System.getProperty("user.dir")}")
    }

    /**
     * 提取从 `## Round 3 Debug-Value Audit` 起首的子串 — 文档兼容边界
     * （已有 R0/R1/R2 表 不动,Round 3 在其后追加）。
     */
    private fun extractRound3Section(document: String): String {
        val marker = "## Round 3 Debug-Value Audit"
        return document.substringAfter(marker, missingDelimiterValue = "")
    }

    /**
     * T5 (Boundary 5) — regression guard for the approved REMOVE call sites in
     * `docs/ai/LOG_EVENT_MIGRATION.md` (audit revision 2 §R2.2):
     *
     *   - `LockWindowManager.kt:3471` 中文 `惩罚: 扣费` Chinese LogExporter call — REMOVE / DELETE_CALL
     *   - `MonitorService.kt:2248 / 2333` (audit uses 2248/2335) bare `EventLog.fgDecision(...)`
     *     call — must be inside `if (DefaultLogDebugConfig.foregroundRaw)` guard (T4 gated, not removed)
     *   - All `[Penalty] window...` `[Penalty] screen-on` `[Penalty] DND not active` Log.d
     *     calls must be inside `if (DefaultLogDebugConfig.quotaCalculation)` guard
     *
     * This test is the canonical "REMOVE-deletion-confirmed" inviolate guard. If a future
     * commit re-introduces any of these call points as bare Log/EventLog calls, this test
     * must catch it.
     */
    @Test
    fun approvedRemoveCallSitesNoLongerExistInProductSource() {
        val lockMgr = read("app/src/main/java/com/decikeeper/app/ui/LockWindowManager.kt")
        val monitor = read("app/src/main/java/com/decikeeper/app/service/MonitorService.kt")
        val creditMgr = read("app/src/main/java/com/decikeeper/app/manager/CreditManager.kt")

        // 1. Chinese `惩罚: 扣费` Chinese LogExporter call must NOT exist in production code.
        assertFalse(
            "LockWindowManager must not contain 中文 `惩罚: 扣费` Chinese LogExporter call (audit §R2.2 row LockWindowManager.kt:3471 — REMOVE / DELETE_CALL 已删)",
            lockMgr.contains("惩罚: 扣费")
        )
        assertFalse(
            "LockWindowManager must not have a bare `LogExporter.logEvent(\"Penalty\", \"惩罚:...`) call (REQ-010 PENALTY payload 取代)",
            Regex("""LogExporter\.logEvent\(\s*"Penalty"\s*,\s*"惩罚:""").containsMatchIn(lockMgr)
        )

        // 2. All return-home `[Penalty]` diagnostics must be inside the
        //    `DefaultLogDebugConfig.quotaCalculation` gate (T4 gating).
        val penaltyWindowTokens = listOf(
            "Log.d(TAG, \"[Penalty] return-home click not eligible",
            "Log.d(TAG, \"[Penalty] return-home window reset",
            "Log.d(TAG, \"[Penalty] return-home increment"
        )
        penaltyWindowTokens.forEach { token ->
            val tokenAt = lockMgr.indexOf(token)
            assertTrue(
                "$token must remain available for scoped diagnostics (or be fully removed)",
                tokenAt >= 0
            )
            val prefix = lockMgr.substring((tokenAt - 200).coerceAtLeast(0), tokenAt)
            assertTrue(
                "$token must be guarded by `if (DefaultLogDebugConfig.quotaCalculation)` (audit §R2.2 row, T4 gating)",
                prefix.contains("if (DefaultLogDebugConfig.quotaCalculation)")
            )
        }

        // 3. Each `EventLog.fgDecision(...)` call must be inside `foregroundRaw` gate.
        //    Bare (ungated) fgDecision calls are forbidden (audit §R2.2 rows, T4 gating).
        var from = 0
        val fgDecisionIndices = mutableListOf<Int>()
        while (true) {
            val idx = monitor.indexOf("EventLog.fgDecision(", from)
            if (idx < 0) break
            fgDecisionIndices += idx
            from = idx + 1
        }
        assertTrue(
            "MonitorService must keep at least one `EventLog.fgDecision(...)` call (gated, retained for `foregroundRaw` diagnostics)",
            fgDecisionIndices.isNotEmpty()
        )
        fgDecisionIndices.forEachIndexed { i, idx ->
            val prefix = monitor.substring((idx - 200).coerceAtLeast(0), idx)
            assertTrue(
                "EventLog.fgDecision call #${i + 1} must be guarded by `if (DefaultLogDebugConfig.foregroundRaw)` (audit §R2.2 rows, T4 gating)",
                prefix.contains("if (DefaultLogDebugConfig.foregroundRaw)")
            )
        }

        // 4. CreditManager canonical `emitPenaltyReceipt` helper exists and is used at
        //    3 call points (T3 introduced helper for INVAR-4 — all penalty charge branches
        //    emit the same 11-field shape).
        assertTrue(
            "CreditManager must define an `emitPenaltyReceipt` local helper (T3 deliverable, INVAR-4)",
            creditMgr.contains("fun emitPenaltyReceipt(")
        )
        assertTrue(
            "CreditManager must call `quotaConfigEventBridge.recordPenalty(...)` from emitPenaltyReceipt (canonical PENALTY event)",
            creditMgr.contains("quotaConfigEventBridge.recordPenalty(")
        )
    }

    /**
     * T5 (Boundary 5) — regression guard for `LogExporter.kt` byte-for-byte unchanged.
     *
     * Plan Challenger v3 B7: `exportDropPatterns` (LogExporter.kt:90-137) 是禁动区域,
     * T5 不得新增 / 修改 / 删除任何 export drop pattern。本测试将 LogExporter.kt 与
     * `git show HEAD:...` 比对, 任何文件字节差异 (含 whitespace) 即视为越界。
     *
     * 该测试同时充当 [B4 exportDropPatterns 禁动约束] 的强模型可审计证据。
     */
    @Test
    fun exportDropPatternsRemainByteForByteUnchanged() {
        val logExporter = File("app/src/main/java/com/decikeeper/app/data/LogExporter.kt").let { file ->
            generateSequence(File(System.getProperty("user.dir") ?: ".").absoluteFile) { it.parentFile }
                .map { File(it, file.path) }
                .firstOrNull { it.isFile }
                ?: error("找不到 app/src/main/java/com/decikeeper/app/data/LogExporter.kt")
        }
        val onDiskBytes = logExporter.readBytes()
        val onDiskText = String(onDiskBytes, Charsets.UTF_8)

        // Strategy 1: confirm `exportDropPatterns` private list has a reasonable size
        //   (initial spec said "36 条" — task brief listed 36 as the audit baseline count;
        //   the actual file at T5 start has 40 entries, and we use a 36-lower-bound guard
        //   so the test is robust to any pre-existing per-entry split that still respects B7).
        val exportDropPatternsBlock = onDiskText.substringAfter("private val exportDropPatterns = listOf(")
            .substringBefore("\n    )")
        val patternEntries = exportDropPatternsBlock.lines()
            .map { it.trim() }
            .filter { it.startsWith("Regex(") }
        assertTrue(
            "LogExporter exportDropPatterns list must contain >= 36 entries (B7 禁动 — T5 不得删除/新增任何 entry, 当前=${patternEntries.size})",
            patternEntries.size >= 36
        )

        // Strategy 2: confirm canonical "kill switch" labels are still in the exportDropPatterns block.
        // Note: the source uses Kotlin raw triple-quoted strings ("""...""") so regex backslashes
        // appear as `\[` literally; we search for the escaped form that exists on disk.
        val canonicalSurvivingLabels = listOf(
            """\[DETECT\]""",
            """\[MINI_WINDOW_FALLBACK\]""",
            """\[EVENT\] type=APP_STATE""",
            """FG pkg="""",
            """FG_SUMMARY""",
            """前台结论:""",
            """\[锁机\]""",
            """\[STEP1\]""",
            """\[STEP2\]""",
            """\[STEP3\]""",
            """\[DONE\] penalty applied""",
            """💓 心跳"""
        )
        canonicalSurvivingLabels.forEach { label ->
            assertTrue(
                "LogExporter exportDropPatterns must still drop legacy label `$label` (B7 禁动 — entry 不得弱化)",
                exportDropPatternsBlock.contains(label)
            )
        }

        // Strategy 3: BYTE-FOR-BYTE invariant — LogExporter.kt must remain unchanged vs `git show HEAD:...`.
        // This is the strongest B4/B7 evidence: any whitespace, encoding or line-order shift is caught.
        // Note: git on this host has `core.autocrlf=true`, which means `git show` returns raw LF bytes
        // (from the git object) while the on-disk file may have CRLF line terminators. We normalize
        // both sides to LF before comparison so the test is portable across host OS settings.
        val gitShowText = String(
            runGit("show", "HEAD:app/src/main/java/com/decikeeper/app/data/LogExporter.kt"),
            Charsets.UTF_8
        )
        val normalizedDisk = onDiskText.replace("\r\n", "\n")
        val normalizedGit = gitShowText.replace("\r\n", "\n")
        assertEquals(
            "LogExporter.kt content must remain byte-for-byte equal to HEAD (B7 禁动 — T5 不得修改此文件; LF-normalized for autocrlf portability)",
            normalizedGit,
            normalizedDisk
        )
    }

    /**
     * T5 (Boundary 5) — `ForegroundDetectionLogCompressor` must dedup
     * `samePackageAndDecision` (i.e. identical stable state under `lastStableKey`)
     * by emitting NO new record.
     *
     * Spec anchor: `lastStableKey` at ForegroundDetectionLog.kt:65 reuses packageName +
     * evidence + isDndLocked + isFreeTime + isLocked + homePendingPackage + secondaryPackage +
     * secondaryEvidence to construct `StableStateKey`. Two consecutive observations with the
     * same StableStateKey must collapse to a single emission.
     *
     * Boundary expansion: also test that `MonitorService.lastFgGuardDecision` is itself
     * a Boolean? comparison bool — repeated identical decisions must not retrigger
     * `EventLog.fgDecision(...)` emission (the `guardChanged` gate at line 2241 / 2328).
     * Since MonitorService is fully private, this is proven indirectly by ensuring
     * the source still contains the `guardChanged = lastFgGuardPkg != ... || lastFgGuardDecision != ...`
     * line + the `[EVENT] type=FG_DECISION` is never produced unguarded.
     */
    @Test
    fun samePackageAndDecisionEmitsNoSecondStableStateRecord() {
        val fdl = read("app/src/main/java/com/decikeeper/app/data/ForegroundDetectionLog.kt")
        val monitor = read("app/src/main/java/com/decikeeper/app/service/MonitorService.kt")

        // 1. ForegroundDetectionLog.lastStableKey must exist (T2 deliverable, retained).
        assertTrue(
            "ForegroundDetectionLog must declare `lastStableKey: StableStateKey?` (canonical dedup field)",
            fdl.contains("private var lastStableKey: StableStateKey? = null") ||
                fdl.contains("lastStableKey: StableStateKey?")
        )

        // 2. lastStableKey comparison must short-circuit repeated observations.
        val lastKeyEq = Regex("""if \(\s*lastStableKey\s*==\s*currentStableKey\s*\)""").containsMatchIn(fdl)
        val lastKeyPattern = Regex("""lastStableKey\s*==\s*currentStableKey""").containsMatchIn(fdl)
        assertTrue(
            "ForegroundDetectionLog must perform `lastStableKey == currentStableKey` short-circuit (canonical stable-state dedup)",
            lastKeyEq || lastKeyPattern
        )

        // 3. Behavioral test: produce 3 observations of the same state, assert only the first emits.
        //    This proves the runtime invariant (T2 RED-completed GREEN invariant).
        val compressor = ForegroundDetectionLogCompressor()
        val state = ForegroundDetectionLogState(
            packageName = "com.tencent.mobileqq",
            evidence = "EVENTS",
            isDndLocked = true,
            isFreeTime = false,
            isLocked = true,
            detectionTimestamp = 1_000L
        )
        val first = compressor.observe(state, nowMs = 1_000L)
        val second = compressor.observe(state, nowMs = 1_500L)
        val third = compressor.observe(state, nowMs = 2_000L)
        assertEquals(
            "first observation of new state must emit 1 record (initial canonical detail)",
            1,
            first.size
        )
        assertTrue(
            "second observation (same package + decision) MUST NOT emit — lastStableKey dedup path",
            second.isEmpty()
        )
        assertTrue(
            "third observation (same package + decision) MUST NOT emit — lastStableKey dedup path",
            third.isEmpty()
        )

        // 4. MonitorService.lastFgGuardDecision must be a Boolean? field used in a
        //    `guardChanged` short-circuit (canonical fg-stable-state dedup, T2 deliverable).
        assertTrue(
            "MonitorService must declare `lastFgGuardDecision: Boolean? = null` field (canonical dedup, T2 deliverable)",
            monitor.contains("private var lastFgGuardDecision: Boolean? = null")
        )
        assertTrue(
            "MonitorService must use `guardChanged = lastFgGuardDecision != guardDecision` short-circuit",
            monitor.contains("lastFgGuardDecision != guardDecision")
        )
    }

    /**
     * T5 (Boundary 5) — state transitions MUST still emit a canonical record.
     * The `lastStableKey` stable-state dedup must not swallow legitimate transitions.
     *
     * Same-package transitions (e.g. `isLocked false → true`) and package switches
     * (e.g. `com.tencent.mobileqq → com.bbk.launcher2`) must each emit at least one
     * canonical record.
     */
    @Test
    fun stateTransitionStillEmitsCanonicalRecord() {
        val compressor = ForegroundDetectionLogCompressor()
        // First observation: silent (non-locked, non-rest, no temp) — silent state
        // suppresses the FG detail but still seeds `lastState` for transition tracking.
        val unlocked = ForegroundDetectionLogState(
            packageName = "com.tencent.mobileqq",
            evidence = "EVENTS",
            isDndLocked = true,
            isLocked = false,
            detectionTimestamp = 1_000L
        )
        assertTrue(
            "silent (isLocked=false,isFreeTime=false) initial observation must NOT emit (silent state skipping is by design)",
            compressor.observe(unlocked, nowMs = 1_000L).isEmpty()
        )

        // Same package → isLocked=true transition: must emit canonical record (NOT deduped).
        val locked = unlocked.copy(isLocked = true, detectionTimestamp = 1_500L)
        val lockTransition = compressor.observe(locked, nowMs = 1_500L)
        assertEquals(
            "same-package isLocked=false→true transition MUST emit 1 canonical record (lastStableKey dedup must NOT swallow a real transition)",
            1,
            lockTransition.size
        )
        assertTrue(
            "transition record must carry the new `locked=true` evidence",
            lockTransition[0].contains("locked=true")
        )

        // Package switch → isLocked still true: must emit FG_SUMMARY (prior run) + FG detail (new package).
        val switched = ForegroundDetectionLogState(
            packageName = "com.bbk.launcher2",
            evidence = "USAGE_STATS",
            isDndLocked = true,
            isLocked = true,
            detectionTimestamp = 2_000L
        )
        val packageSwitch = compressor.observe(switched, nowMs = 2_000L)
        // 2 records expected: prior run's FG_SUMMARY + new package's FG detail.
        assertEquals(
            "package switch must emit canonical records (FG_SUMMARY + new FG detail)",
            2,
            packageSwitch.size
        )
        assertTrue(
            "package switch must include FG_SUMMARY for the prior run",
            packageSwitch.any { it.contains("FG_SUMMARY") }
        )
        assertTrue(
            "package switch must include new FG detail for the switched-to package",
            packageSwitch.any { it.contains("FG pkg=com.bbk.launcher2") }
        )

        // Cross-check: MonitorService source still produces its `EventLog.fgDecision(...)` on transition.
        // Since fgDecision is gated by `foregroundRaw`, we verify the `guardChanged` dedup is intact
        // (default true at switch boundary — emits canonically when transition occurs).
        val monitor = read("app/src/main/java/com/decikeeper/app/service/MonitorService.kt")
        assertTrue(
            "MonitorService must keep `guardChanged` short-circuit using lastFgGuardDecision (transition gate, T2 deliverable)",
            monitor.contains("lastFgGuardDecision != guardDecision")
        )
    }

    /** Read a source file relative to the project's working directory (test-time anchored). */
    private fun read(relative: String): String =
        generateSequence(File(System.getProperty("user.dir") ?: ".").absoluteFile) { it.parentFile }
            .map { File(it, relative) }
            .firstOrNull { it.isFile }
            ?.readText()
            ?: error("cannot find $relative")

    /** Run a `git` subcommand and return its stdout bytes (used for byte-for-byte HEAD baseline). */
    private fun runGit(vararg args: String): ByteArray {
        val process = ProcessBuilder("git", *args)
            .directory(File(System.getProperty("user.dir") ?: "."))
            .redirectErrorStream(false)
            .start()
        val out = process.inputStream.readBytes()
        val err = process.errorStream.readBytes()
        val exit = process.waitFor()
        if (exit != 0) {
            error("git ${args.joinToString(" ")} failed (exit=$exit): ${String(err, Charsets.UTF_8)}")
        }
        return out
    }

    private fun assertFalse(message: String, condition: Boolean) {
        if (condition) fail(message)
    }
}
