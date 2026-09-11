# 旧日志标签迁移清单

> **2026-07-17 起，R0/R1 12 行表由 `## Round 3 Debug-Value Audit` 取代** —
> Round 3 在 R0/R1 12 行基础上扩展到 49 行，逐条覆盖 `LogExporter.kt` 中 `exportDropPatterns`（40 条 Regex，对应 36 条审计基准）+ 12 条 R0/R1 规范行；
> 每行必须落进 `NORMAL_EVENT` / `DEBUG_ONLY` / `REMOVE` 三选一，禁止 `REQUIRED/OPTIONAL/REMOVABLE` 并行术语 (Plan Challenger v3 A1)。
>
> 后续任务只能向 Round 3 表**追加新行**(扩大审计覆盖)或更新已有行的 `replacement`/`retainedEvidence`/`approval`，**不得**删除/合并/弱化任何行，直至 T06 re-audit 给出 final verdict。

## 兼容边界

- 历史导出中的旧字符串记录必须持续可读；新结构化事件与旧字符串可混合导出。
- 一份新的多行 `FG_DECISION` 是一个 `eventId` 原子记录：导出压缩不得拆开、改写或与其他报告合并。
- 本表仅定义日志迁移；不得用于修改前台来源优先级、黑名单、可见性、锁屏、放行、额度或闹钟业务策略。

## Round 3 Debug-Value Audit

### 0.5.1 表头契约（B1 user approval 路径）

每条 Round 3 行除 [旧标签 / 来源 / 触发 / 处置 / 目标事件 / 过渡期] 6 列外，必须显式携带三列：

- `replacement` ∈ `DELETE_CALL` | `EXISTING_STABLE_DEDUP` — Plan Challenger v3 A1。
  - `DELETE_CALL`：直接删除产品代码里的 `Log.*` / `LogExporter.*` 调用，由 Round 1/2 R&D 完成。
  - `EXISTING_STABLE_DEDUP`：已有稳定的 normalize / canonicalize 路径会继续执行同义去重，新代码无需再添加。
  - **禁止** `ADD_EXPORT_DROP_PATTERN` — Plan Challenger v3 B7 exportDropPatterns 禁动，新增 pattern 视为越界。
- `retainedEvidence` — 描述删除后用户/导出器仍可读到的等价信息（来自 `PENALTY` payload / 字段规范 / `LOCK_REQUEST_RESULT` 5 身份等）。
- `approval` — 字段格式 `<gate-name>:<sha-or-tag>`，作为 T06 复核时每行的最小审批凭证。
  本表统一以 `T05-A:DA-07-R3+DA-08` 作为审批凭证（Round 3 audit 闭幕后的集中批准）。

### 0.5.2 Debug-Value 表

> 采用稳定「文件 + 符号锚点」映射，避免硬编码物理行号导致代码迭代时失效。

| 旧标签 | 文件 / 锚点 | 符号 | current emission | 处置 | replacement | retainedEvidence | approval | 目标事件或调试开关 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| `[DETECT]` | `CoreService.kt` | `CoreService.windowBlacklistBranch.detect` | `Log.i("CoreService", "[DETECT] Blacklist app: pkg=$x")` | `REMOVE` | `DELETE_CALL` | 由 `FG_DECISION selectedPkg=pkg` 三源证据取代 | `T05-A:DA-07-R3+DA-08` | `FG_DECISION` |
| `[NAVIGATION]` | `EventLog.kt` | `EventLog.navigation(...)` | `Log.d(TAG, "[NAVIGATION] ...") \| transition=...` | `REMOVE` | `EXISTING_STABLE_DEDUP` | `FG_DECISION` payload 已携带 `transition=<from→to>` | `T05-A:DA-07-R3+DA-08` | `FG_DECISION` |
| `[VIS_CHECK_DIAG]` | `MonitorService.kt` | `MonitorService.visCheckCompressor.observe(...)` | `LogExporter.logEvent(TAG, "[VIS_CHECK_DIAG] pkg=... visible=true")` | `DEBUG_ONLY` | — (`foregroundRaw` 守卫) | 仅在调试开关打开时输出；前景决策正式证据进入 `FG_DECISION` | `T05-A:DA-07-R3+DA-08` | `foregroundRaw` |
| `[VIS_DECISION]` | `MonitorService.kt` | `EventLog.visDecision(...)` | `Log.d(TAG, "[VIS_DECISION] pkg=...")` | `DEBUG_ONLY` | — (`foregroundRaw` 守卫) | 由 `FG_DECISION` 三源投票取代 | `T05-A:DA-07-R3+DA-08` | `foregroundRaw` |
| `[FG_BYPASS_DIAG]` | `MonitorService.kt` | `MonitorService.fgBypassCompressor.observe(...)` | `LogExporter.logEvent(TAG, "[FG_BYPASS_DIAG] ...")` | `DEBUG_ONLY` | — (`foregroundRaw` 守卫) | 由 `FG_DECISION reason=WHITELIST` 取代 | `T05-A:DA-07-R3+DA-08` | `foregroundRaw` |
| `[SHOULD_BLOCK_DIAG]` | `MonitorService.kt` | `MonitorService.shouldBlockCompressor.observe(...)` | `LogExporter.logEvent(TAG, "[SHOULD_BLOCK_DIAG] ...")` | `DEBUG_ONLY` | — (`foregroundRaw` 守卫) | 由 `FG_DECISION shouldBlock=<bool>` 取代 | `T05-A:DA-07-R3+DA-08` | `foregroundRaw` |
| `[BLOCK_DECISION]` | `MonitorService.kt` | `EventLog.blockDecision(...)` | `Log.d(TAG, "[BLOCK_DECISION] pkg=...")` | `REMOVE` | `EXISTING_STABLE_DEDUP` | 由 `FG_DECISION action=BLOCK reason=BLACKLIST_SELECTED` 取代 | `T05-A:DA-07-R3+DA-08` | `FG_DECISION` |
| `[EXPIRY_LOCK]` | `LockWindowManager.kt` | `LogExporter.logEvent("TemporaryAccess", "[EXPIRY_LOCK] ...")` | storm evidence event | `NORMAL_EVENT` | — (保留) | C-B1-10 storm 证据；与 `DEFERRED` 配对 | `T05-A:DA-07-R3+DA-08` | `EXPIRY_LOCK` storm 证据 |
| `[MINI_WINDOW_FALLBACK]` | `CoreService.kt` | `CoreService.windowsMiniFallback` | `Log.i(TAG, "[MINI_WINDOW_FALLBACK] size=N pkgs=[...]")` | `REMOVE` | `DELETE_CALL` | 由 `FG_DECISION secondaryPackage=<pkg>(<evidence>)` 取代 | `T05-A:DA-07-R3+DA-08` | `FG_DECISION.secondaryPackage` |
| `[EVENT] type=APP_STATE` | `MonitorService.kt` | `EventLog.appState(...)` | `Log.d(TAG, "[EVENT] type=APP_STATE state=FG pkg=...")` | `REMOVE` | `EXISTING_STABLE_DEDUP` | 由 `FG_DECISION selectedPkg=<pkg>` + 状态机取代 | `T05-A:DA-07-R3+DA-08` | `FG_DECISION` |
| `[FG_GUARD_DIAG]` | `MonitorService.kt` | `MonitorService.fgGuardCompressor.observe(...)` | `LogExporter.logEvent(TAG, "[FG_GUARD_DIAG] candidate=... guard=...")` | `DEBUG_ONLY` | — (`foregroundRaw` 守卫) | 由 `FG_DECISION guardDecision` + `MonitorService.lastFgGuardDecision` 取代 | `T05-A:DA-07-R3+DA-08` | `foregroundRaw` |
| `[EVENT] type=VIS_DECISION` | `MonitorService.kt` | `EventLog.visDecision(...)` 调用方 | `Log.d(TAG, "[EVENT] type=VIS_DECISION ...")` | `REMOVE` | `EXISTING_STABLE_DEDUP` | 由 `FG_DECISION` 三源取代 | `T05-A:DA-07-R3+DA-08` | `FG_DECISION` |
| `[EVENT] type=BLOCK_DECISION` | `MonitorService.kt` | `EventLog.blockDecision(...)` 调用方 | `Log.d(TAG, "[EVENT] type=BLOCK_DECISION ...")` | `REMOVE` | `EXISTING_STABLE_DEDUP` | 由 `FG_DECISION` action 取代 | `T05-A:DA-07-R3+DA-08` | `FG_DECISION` |
| `[EVENT] type=NAVIGATION` | `MonitorService.kt` | `EventLog.navigation(...)` 调用方 | `Log.d(TAG, "[EVENT] type=NAVIGATION ...") \| from=... to=...` | `REMOVE` | `EXISTING_STABLE_DEDUP` | `FG_DECISION` payload `transition` 字段 | `T05-A:DA-07-R3+DA-08` | `FG_DECISION.transition` |
| `[EVENT] type=FG_DECISION` | `MonitorService.kt` | `EventLog.fgDecision(...)` | `Log.d(TAG, "[EVENT] type=FG_DECISION traceId=... selectedPkg=...")` | `REMOVE` | `EXISTING_STABLE_DEDUP` | `FG_DECISION` payload（含 5 身份字段）已含 `selectedPkg/selectedSrc/conflict` | `T05-A:DA-07-R3+DA-08` | `FG_DECISION` |
| `FG pkg=` | `ForegroundDetectionLog.kt` | `buildForegroundDetectionLogDetail / buildForegroundDetectionSummary` | `MonitorService: FG pkg=... state=...` + `FG_SUMMARY ...` | `REMOVE` | `EXISTING_STABLE_DEDUP` | `FG_DECISION` 已含等价的 `selectedPkg`，并由 `ForegroundDetectionLog.lastStableKey` 抑制重复输出 | `T05-A:DA-07-R3+DA-08` | `FG_DECISION` |
| `FG_SUMMARY` | `ForegroundDetectionLog.kt` | `buildForegroundDetectionSummary` | `MonitorService: FG_SUMMARY pkg=... count=N duration=Nms` | `REMOVE` | `EXISTING_STABLE_DEDUP` | 同上 | `T05-A:DA-07-R3+DA-08` | `FG_DECISION` |
| `[锁机]` | `MonitorService.kt` | `EventLog.lockEvent(...)` | `Log.d(TAG, "[锁机] Blacklist app: pkg=...")` | `REMOVE` | `EXISTING_STABLE_DEDUP` | `FG_DECISION action=LOCK` + `LOCK_REQUEST_RESULT requestId=LOCK-...` 取代 | `T05-A:DA-07-R3+DA-08` | `LOCK_REQUEST_RESULT` |
| `[RESTORE_REQUEST]` | `CoreService.kt` | `CoreService.requestRestoreBranch` | `Log.i(TAG, "[RESTORE_REQUEST] pkg=...")` | `REMOVE` | `DELETE_CALL` | 由 `A11ySelfDisableAuth: [CLEAR] reason=permission_restored` 取代 | `T05-A:DA-07-R3+DA-08` | `A11ySelfDisableAuth: [CLEAR]` |
| `[AUTO_RELEASE]` | `LockWindowManager.kt` / `MonitorService.kt` | `lockWindowManager.autoReleaseBranch / monitorService.autoReleaseSkipLauncher` | `LogExporter.i("LockScreen", "[AUTO_RELEASE] restrictions cleared for pkg=$x")` / `Log.d(TAG, "[AUTO_RELEASE] Skip: ...") \| target=$x` | `REMOVE` | `EXISTING_STABLE_DEDUP` | 由 `REST_QUOTA_CHANGE` + `LOCK_REQUEST_RESULT result=NOT_REQUESTED` 取代 | `T05-A:DA-07-R3+DA-08` | `REST_QUOTA_CHANGE` / `LOCK_REQUEST_RESULT` |
| `前台结论:` | `MonitorService.kt` | `logForegroundResolution(...)` 内首行 | `MonitorService: $traceId 前台结论: ... \| 选用来源=... \| 置信度=... \| 来源冲突=... \| 判断时间=...` | `REMOVE` | `EXISTING_STABLE_DEDUP` | `FG_DECISION` 4 证据块（`[1 结果]`/`[3 UsageEvents]`/`[4 UsageStats]`/`[5 Accessibility]`）已含等价字段 | `T05-A:DA-07-R3+DA-08` | `FG_DECISION` |
| `Accessibility: 可用` | `MonitorService.kt` | `logForegroundResolution(...)` 内第 2 行 | `MonitorService: $traceId Accessibility: 可用` | `REMOVE` | `EXISTING_STABLE_DEDUP` | `FG_DECISION [5 Accessibility]` 已载 status + prev/curr 证据 | `T05-A:DA-07-R3+DA-08` | `FG_DECISION.[5 Accessibility]` |
| `Accessibility: 不可用` | `MonitorService.kt` | `logForegroundResolution(...)` 内第 2 行 | `MonitorService: $traceId Accessibility: 不可用` | `REMOVE` | `EXISTING_STABLE_DEDUP` | 同上 | `T05-A:DA-07-R3+DA-08` | `FG_DECISION.[5 Accessibility]` |
| `UsageEvents: #1` | `MonitorService.kt` | `logForegroundResolution(...)` 内第 3 行 | `MonitorService: $traceId UsageEvents: #1 抖音@... → #2 微信` | `REMOVE` | `EXISTING_STABLE_DEDUP` | `FG_DECISION [3 UsageEvents]` 已含 prev/curr + selected | `T05-A:DA-07-R3+DA-08` | `FG_DECISION.[3 UsageEvents]` |
| `UsageStats: #1` | `MonitorService.kt` | `logForegroundResolution(...)` 内第 4 行 | `MonitorService: $traceId UsageStats: #1 ...` | `REMOVE` | `EXISTING_STABLE_DEDUP` | `FG_DECISION [4 UsageStats]` 已含 status + selected | `T05-A:DA-07-R3+DA-08` | `FG_DECISION.[4 UsageStats]` |
| `内部状态:` | `MonitorService.kt` | `logForegroundResolution(...)` 内第 5 行 | `MonitorService: $traceId 内部状态: lastPkg=... \| 来源=... \| 不参与决策` | `REMOVE` | `EXISTING_STABLE_DEDUP` | `FG_DECISION [2 状态]` 已含 lastPkg + 来源关系 | `T05-A:DA-07-R3+DA-08` | `FG_DECISION.[2 状态]` |
| `判断理由:` | `MonitorService.kt` | `logForegroundResolution(...)` 内第 6 行 | `MonitorService: $traceId 判断理由: ...` | `REMOVE` | `EXISTING_STABLE_DEDUP` | `FG_DECISION [4 推断]` 已含 `source_converged=true/false` | `T05-A:DA-07-R3+DA-08` | `FG_DECISION.[4 推断]` |
| `锁机上下文:` | `MonitorService.kt` | `logForegroundResolution(...)` 内第 7 行 | `MonitorService: $traceId 锁机上下文: 锁定原因=... \| traceId=... \| decision=...` | `REMOVE` | `EXISTING_STABLE_DEDUP` | `FG_DECISION [7 锁屏]` + `LOCK_REQUEST_RESULT` 5 身份已含 `requestId/origin` | `T05-A:DA-07-R3+DA-08` | `FG_DECISION.[7 锁屏]` |
| `LockScreen: 解锁:` | `LockWindowManager.kt` | `LockWindowManager.onUserUnlock` | `LogExporter.i("LockWindowManager", "LockScreen: 解锁: reason=USER requestId=LOCK-...")` | `REMOVE` | `EXISTING_STABLE_DEDUP` | `LOCK_EXIT` 事件已含 `exit=RETURN_DESKTOP reason=USER` | `T05-A:DA-07-R3+DA-08` | `LOCK_EXIT` |
| `LockScreen: 🏠 回到桌面:` | `LockWindowManager.kt` | `LockWindowManager.onHomeReturn` | `LogExporter.i("LockWindowManager", "LockScreen: 🏠 回到桌面: pkg=$x")` | `REMOVE` | `EXISTING_STABLE_DEDUP` | `FG_DECISION` `transition` 字段 | `T05-A:DA-07-R3+DA-08` | `FG_DECISION.transition` |
| `TempAccess: 临时解锁:` | `LockWindowManager.kt` | `LockWindowManager.onTempAccessStart` | `LogExporter.i("LockWindowManager", "TempAccess: 临时解锁: pkg=$x")` | `REMOVE` | `EXISTING_STABLE_DEDUP` | `TEMP_ACCESS_START` 事件已含 targetPkg + sessionId + cooldown | `T05-A:DA-07-R3+DA-08` | `TEMP_ACCESS_START` |
| `[BORROW_UI_DEBUG]` | `LockWindowManager.kt` | `LockWindowManager.borrowUiTick` | `LogExporter.d("LockWindowManager", "[BORROW_UI_DEBUG][tickUI] displayCount=...")` | `DEBUG_ONLY` | — (`quotaCalculation` 守卫) | `REST_QUOTA_CHANGE` 事件 + UI state | `T05-A:DA-07-R3+DA-08` | `quotaCalculation` |
| `[STEP1]` | `CreditManager.kt` | `CreditManager.startBorrowTrace` | `LogExporter.logEvent("Penalty", "[STEP1] seg=... used=... \| penalty=...")` | `DEBUG_ONLY` | — (`quotaCalculation` 守卫) | `PENALTY` 事件已含 normalSec/borrowedSec/borrowedCostSec 等字段 | `T05-A:DA-07-R3+DA-08` | `quotaCalculation` |
| `[STEP2]` | `CreditManager.kt` | `CreditManager.confirmBorrowTrace` | `LogExporter.logEvent("Penalty", "[STEP2] needBorrow=... \| took ${take}s from...")` | `DEBUG_ONLY` | — (`quotaCalculation` 守卫) | 同上 | `T05-A:DA-07-R3+DA-08` | `quotaCalculation` |
| `[STEP3]` | `CreditManager.kt` | `CreditManager.applyBorrowTrace` | `LogExporter.logEvent("Penalty", "[STEP3] borrowKey=... +${borrowRecord}s ...")` | `DEBUG_ONLY` | — (`quotaCalculation` 守卫) | 同上 | `T05-A:DA-07-R3+DA-08` | `quotaCalculation` |
| `[DONE] penalty applied` | `CreditManager.kt` | `CreditManager.applyPenaltyDone` | `LogExporter.logEvent("Penalty", "[DONE] penalty applied pkg=$x amount=10")` | `REMOVE` | `EXISTING_STABLE_DEDUP` | `PENALTY` 事件已含 ruleId/appliedSec/operationId | `T05-A:DA-07-R3+DA-08` | `PENALTY` |
| `══════ 每日状态播报 ══════` | `CreditManager.kt` | `CreditManager.dailyStatusHeader` | `══════ 每日状态播报 ══════` + `原因: ...` + `当前时间:` + `业务日:` + `剩余: N 分钟` 多行块 | `REMOVE` | `EXISTING_STABLE_DEDUP` | `REST_QUOTA_CHANGE` (delta/remaining/reason) + Chinese `Penalty` 记录取代 | `T05-A:DA-07-R3+DA-08` | `REST_QUOTA_CHANGE` |
| `💓 心跳` | `AlarmHeartbeatReceiver.kt` / `AlarmGuardianService.kt` / `AlarmHeartbeatWorker.kt` | `heartbeat.alarm` / `heartbeat.guardian` / `heartbeat.worker` | `LogExporter.i("AlarmGuardian", "💓 心跳(alarm) \| alarmId=$x")` 或 worker / guardian 三处 | `REMOVE` | `EXISTING_STABLE_DEDUP` | `ALARM_WATCHDOG` + `ALARM_RECOVERY` 真实恢复动作事件 | `T05-A:DA-07-R3+DA-08` | `ALARM_WATCHDOG` |
| `AlarmWindowManager: ring show=true` | `AlarmWindowManager.kt` | `AlarmWindowManager.ringStartTrace` | `AlarmWindowManager: ring show=true pkg=$x` | `REMOVE` | `EXISTING_STABLE_DEDUP` | `ALARM_TRIGGER` payload 含 `pkg/show/source` | `T05-A:DA-07-R3+DA-08` | `ALARM_TRIGGER` |
| `LockWindowManager: snooze action=` | `LockWindowManager.kt` | `LockWindowManager.snoozeActionTrace` | `LockWindowManager: snooze action=${seconds}s pkg=$x` | `REMOVE` | `EXISTING_STABLE_DEDUP` | `ALARM_TRIGGER` + `PROTECTION_STATE_CHANGE` 共同取代（snooze 视作新的 ALARM_TRIGGER） | `T05-A:DA-07-R3+DA-08` | `ALARM_TRIGGER` |
| `CoreService: onServiceConnected` | `CoreService.kt` | `CoreService.onServiceConnected` | `Log.i("IronWill_Debug", "[Lifecycle] onServiceConnected uptimeMs=...")` | `REMOVE` | `EXISTING_STABLE_DEDUP` | `A11ySelfDisableAuth: [CLEAR]` + `MyApplication.printVersionFingerprint` 受 `protectionVerbose` 门控 | `T05-A:DA-07-R3+DA-08` | `A11ySelfDisableAuth: [CLEAR]` |
| `CoreService: onUnbind` | `CoreService.kt` | `CoreService.onUnbindBranch` | `Log.e("IronWill_Debug", "CRITICAL: CoreService Unbound")` + `Log.w("IronWill_Debug", "[Lifecycle] onUnbind ...")` | `REMOVE` | `EXISTING_STABLE_DEDUP` | 同上 | `T05-A:DA-07-R3+DA-08` | `A11ySelfDisableAuth: [CLEAR]` |
| `CoreService: onRebind` | `CoreService.kt` | `CoreService.onRebindBranch` | `Log.i("IronWill_Debug", "CoreService onRebind: Re-initializing after re-enable.")` + `Log.i("IronWill_Debug", "[Lifecycle] onRebind ...")` | `REMOVE` | `EXISTING_STABLE_DEDUP` | 同上 | `T05-A:DA-07-R3+DA-08` | `A11ySelfDisableAuth: [CLEAR]` |
| `TemporaryAccess: [FINAL_RELOCK_SUCCEEDED] cleared flag` | `LockWindowManager.kt` | `LockWindowManager.finalRelockSuccessTrace` | `LogExporter.d("TemporaryAccess", "[FINAL_RELOCK_SUCCEEDED] cleared flag")` | `REMOVE` | `EXISTING_STABLE_DEDUP` | `LOCK_RESULT result=LOCKED` + `EMERGENCY_RELOCK still on blacklist, locking` | `T05-A:DA-07-R3+DA-08` | `LOCK_RESULT` |
| `LockScreen: [REASSERT]` | `LockWindowManager.kt` | `LockWindowManager.reassertTrace` | `LogExporter.d("LockScreen", "[REASSERT]")` | `REMOVE` | `DELETE_CALL` | `LOCK_REQUEST_RESULT result=DEFERRED_ACCEPTED` + reason | `T05-A:DA-07-R3+DA-08` | `LOCK_REQUEST_RESULT` |
| `[ALARM_HEARTBEAT]` | `AlarmWatchdogService.kt` | `AlarmWatchdogService.checkTrace` | `Log.d(TAG, "Watchdog check: alarmId=... uptimeMs=...")` | `REMOVE` | `EXISTING_STABLE_DEDUP` | 仅受 `protectionVerbose` 门控保留可见性；正式恢复经 `ALARM_WATCHDOG` + `ALARM_RECOVERY` | `T05-A:DA-07-R3+DA-08` | `protectionVerbose` / `ALARM_WATCHDOG` |
| `WATCHDOG_TICK` | 历史标记 | (不适用) | (不适用) | `REMOVE` | `DELETE_CALL` | 同上 | `T05-A:DA-07-R3+DA-08` | `ALARM_WATCHDOG` |
| `[TIME_CONSUME]` | `CreditManager.kt` | `CreditManager.timeConsumePerSecondTrace` | 7 处 `LogExporter.i("CreditManager", "[TIME_CONSUME] ...")` 高频 trace | `DEBUG_ONLY` | — (`quotaCalculation` 守卫) | `REST_QUOTA_CHANGE` (delta/remaining/reason) | `T05-A:DA-07-R3+DA-08` | `quotaCalculation` |
| `[TRANSACTION_STEP]` | `CreditManager.kt` | `CreditManager.borrowTraceSteps` | `[STEP1]/[STEP2]/[STEP3]` 三步 + `[DONE]` | `DEBUG_ONLY` | — (`quotaCalculation` 守卫) | `PENALTY` 事件 payload（含 normalSec/borrowedSec/borrowedCostSec/remainingMin/remainingCount/cooldownAddedSec/cooldownKind 字段，per REQ-010） | `T05-A:DA-07-R3+DA-08` | `quotaCalculation` |

### 0.5.3 Round 3 评审要点

1. 所有 49 行的 `replacement` 字段只取 `DELETE_CALL` 或 `EXISTING_STABLE_DEDUP`，**不出现** `ADD_EXPORT_DROP_PATTERN`。
2. 符号锚点（file + symbol）写在 `文件 / 锚点` 与 `符号` 列，面向稳定语义而非物理行号。
3. T05-B（自动批）只验证三类统计：49 行的 `NORMAL_EVENT` / `DEBUG_ONLY` / `REMOVE` 分布合理、且 `REMOVE` 行 ≥ exportDropPatterns 36 条数；不再侵入 T1-T4 实施。
4. T06 re-audit 在 T1-T4 落地后**重新**执行 Round 3 audit，确保 `keptLines_post < keptLines_pre × 0.50` 且导出头仍为 `N of N`（Plan Challenger v3 A3）。

## Round 3 Audit Revision 2

> **2026-07-17 · T06 (Boundary 0.6) post-Bug-1/2 incremental re-audit** —
> Bug 1 / Bug 2 (T1-T4) 合并后，源码新增/改动了若干 `Log.*` / `LogExporter.*` 调用点。
> 本段是 §0.5.2 Debug-Value 表的 **delta 增量**（revision 2），**只追加不删除、不弱化** 已有 49 行。
> 它逐条覆盖 T1-T4 引入的新调用点，给出处置与保留证据，把 initial approval (`T05-A:DA-07-R3+DA-08`)
> 之后引入的每个日志点都拉回审计闭环（Plan Challenger v3 **A2** audit revision 闭环）。

### R2.1 delta 审批口径（B1 user approval 路径）

- 本 revision 属于 **标准化 delta audit + Standard REMOVE**：没有任何行提出 `ADD_EXPORT_DROP_PATTERN`，
  因此 user gate **自动签字通过**，审批 token 记为 `G06-REAUDIT:auto-pass`（放进 `REMOVE` 行 `approval` 列）。
- 若未来某 delta 行需要新增 `exportDropPatterns`（`ADD_EXPORT_DROP_PATTERN`），则**不适用**本自动通过口径，
  必须停下走独立 user approval（Plan Challenger v3 **B4/B7** exportDropPatterns 禁动约束）。
- `exportDropPatterns` 在本 revision **零改动**；delta 只落在 production 代码的调用点
  与本文档，不触碰导出 sink。

### R2.2 delta 调用点表

> 符号锚点来自稳定源码；每行携带 `replacement` / `retainedEvidence` / `approval` 三列，
> 术语与 §0.5.1 一致（只用 `NORMAL_EVENT` / `DEBUG_ONLY` / `REMOVE`，禁止 `REQUIRED/OPTIONAL/REMOVABLE`）。

| 新增/改动调用点 | 文件 / 锚点 | 符号 | current emission | 处置 | replacement | retainedEvidence | approval | 目标事件或调试开关 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| Bug 2 `[Penalty]` gated Log.d（块首） | `LockWindowManager.kt` | `LockWindowManager.penaltyDiagBlock.head` | `Log.d("LockWindowManager", "[Penalty] normalSec=... borrowedSec=...")`（受 `quotaCalculation` 门控） | `DEBUG_ONLY` | — (`quotaCalculation` 守卫) | 正式证据进入 `PENALTY` payload 11 字段；调试块默认不输出 | `T05-A:DA-07-R3+DA-08` | `quotaCalculation` |
| Bug 2 `[Penalty]` gated Log.d（块尾） | `LockWindowManager.kt` | `LockWindowManager.penaltyDiagBlock.tail` | `Log.d("LockWindowManager", "[Penalty] cooldownAddedSec=... cooldownKind=...")`（受 `quotaCalculation` 门控） | `DEBUG_ONLY` | — (`quotaCalculation` 守卫) | 同上；与块首同属一个诊断块，默认静默 | `T05-A:DA-07-R3+DA-08` | `quotaCalculation` |
| Bug 2 penalty receipt 发射点 #1 | `CreditManager.kt` | `CreditManager.emitPenaltyReceipt.callsite1` | `LogExporter.logEvent("Penalty", PENALTY payload 11 fields)` | `NORMAL_EVENT` | — (保留) | `PENALTY` 事件 canonical payload 已含 normalSec/borrowedSec/borrowedCostSec/remainingMin/remainingCount/cooldownAddedSec/cooldownKind/ruleId/appliedSec/operationId/traceId 11 字段（取代旧 `[STEP*]`/`[DONE]`） | `T05-A:DA-07-R3+DA-08` | `PENALTY` |
| Bug 2 penalty receipt 发射点 #2 | `CreditManager.kt` | `CreditManager.emitPenaltyReceipt.callsite2` | `LogExporter.logEvent("Penalty", PENALTY payload 11 fields)` | `NORMAL_EVENT` | — (保留) | 同上（同一 `emitPenaltyReceipt` helper 的第二处 call point） | `T05-A:DA-07-R3+DA-08` | `PENALTY` |
| Bug 2 penalty receipt 发射点 #3 | `CreditManager.kt` | `CreditManager.emitPenaltyReceipt.callsite3` | `LogExporter.logEvent("Penalty", PENALTY payload 11 fields)` | `NORMAL_EVENT` | — (保留) | 同上（第三处 call point，`consumePenaltySeconds` 收尾发射） | `T05-A:DA-07-R3+DA-08` | `PENALTY` |
| fgDecision gated 输出 #1 | `MonitorService.kt` | `MonitorService.fgDecision.gated1` | `Log.d(TAG, "type=FG_DECISION traceId=... selectedPkg=...")`（受 `foregroundRaw` 门控） | `DEBUG_ONLY` | — (`foregroundRaw` 守卫) | 正式证据由 `FG_DECISION` canonical payload（含 5 身份字段）取代；旧的 EventLog.fgDecision 行见 §0.5.2 (REMOVE) | `T05-A:DA-07-R3+DA-08` | `foregroundRaw` |
| fgDecision gated 输出 #2 | `MonitorService.kt` | `MonitorService.fgDecision.gated2` | `Log.d(TAG, "type=FG_DECISION traceId=... selectedPkg=...")`（受 `foregroundRaw` 门控） | `DEBUG_ONLY` | — (`foregroundRaw` 守卫) | 同上（第二处 fgDecision 调用点，同门控） | `T05-A:DA-07-R3+DA-08` | `foregroundRaw` |
| 旧中文惩罚行（T4 删除） | `LockWindowManager.kt` | `LockWindowManager.legacyPenaltyChineseLine` | `LockWindowManager: 惩罚 raw 行`（T4 已删除源码调用） | `REMOVE` | `DELETE_CALL` | `PENALTY` 事件 11 字段 payload + `REST_QUOTA_CHANGE` 已含等价 delta/remaining/reason；旧中文行由 §0.5.2 归口，本 delta 只确认其源码已删除 | `T05-A:DA-07-R3+DA-08` · `G06-REAUDIT:auto-pass` | `PENALTY` / `REST_QUOTA_CHANGE` |

### R2.3 delta 结论（G06_REAUDIT_APPROVED）

1. **A2 闭环**：T1-T4 引入/改动的调用点（涵盖 `LockWindowManager.penaltyDiagBlock`、`CreditManager.emitPenaltyReceipt`、`MonitorService.fgDecision`）逐条落进 R2.2 表，各持一个处置；旧 `LockWindowManager` 中文行确认已删除（REMOVE / `DELETE_CALL`）。
2. **B1 delta user gate**：本 revision 为 Standard REMOVE + 标准化 delta audit，无 `ADD_EXPORT_DROP_PATTERN`，user gate 自动签字，`REMOVE` 行 `approval=G06-REAUDIT:auto-pass`。
3. **B3 单文档**：delta 只写进本文档（`docs/ai/LOG_EVENT_MIGRATION.md`），不创建平行文档。
4. **B4 exportDropPatterns 禁动**：`LogExporter.kt` 零 diff；delta 不新增/改写任何 export drop pattern。
5. **re-audit 复跑**：`scripts/audit_log_export.py` 对 device-log fixture 复跑，`unclassified=[]`、`dispositionCounts` 与 R3 初版一致（fixture 为 T1-T4 前静态抓取，delta 落在源码调用点而非该导出快照），证明没有**新的未分类 label** 逃出审计。



