# 架构说明 (ARCHITECTURE)

> 本文描述「决定守护 (DeciKeeper)」的目标架构与依赖方向。现有代码正在按此逐步重构，
> 新代码必须遵守，旧代码在改动时顺手迁移。
>
> 本文描述**当前生效的架构约束**、现状偏差与长期依赖方向。§5 列出的拆分步骤是长期方向，**当前没有承载它的计划文件**；需要推进时先在 [`docs/plans/`](plans/) 下建立当次任务计划。

## 1. 分层与依赖方向

```text
┌─────────────────────────────────────────────┐
│ UI 层 (ui/)                                   │
│ Activity / Fragment / Adapter / Dialog        │
│ 目标职责：渲染、收集输入、调用 Manager、弹窗确认 │
└──────────────┬──────────────────────────────┘
               │ 只允许向下调用（目标方向）
┌──────────────▼──────────────────────────────┐
│ 业务层 (manager/)                             │
│ 规则判定、额度账本、生效策略、到期决策解析     │
│ 目标职责：纯业务逻辑、持久化、事件发出           │
└──────────────┬──────────────────────────────┘
               │
┌──────────────▼──────────────────────────────┐
│ 系统/数据层 (data/ + util/ + Android SDK)   │
│ SharedPreferences、AlarmManager、Accessibility│
└─────────────────────────────────────────────┘
```

### 1.1 架构原则与当前代码现状

| 层次/规则 | 目标架构规范 | 当前代码现状（演进过渡态） |
|---|---|---|
| **UI 读写 Prefs** | UI 不得直接读写业务 SharedPreferences、不得复制业务判定 | `ui/` 下仍有部分代码直接读写业务 prefs（如 `HomeFragment` 读写额度账本 `CreditManager.PREFS_NAME`，`GlobalParamsActivity` 直读 `"decikeeper_rules_pending"` 等 20 处），新代码严禁新增直读，旧逻辑逐步下沉到 Manager/UseCase |
| **业务层反向依赖** | 业务层不得 import UI 具体类（Activity/Fragment/View/Adapter） | `manager/` 存在历史直接跳转 UI（如 `AlarmScheduler` 启动 `AlarmActivity`，`PermissionManager` 调起权限页等 8 处），`service/` 另有部分直接启动 UI 调用；长期需通过路由或回调接口解耦 |
| **数据层边界** | `data/` 层作为底层纯数据与存储，不依赖上层业务 | `data/` 存在部分混合业务对象（如 `SegmentBlacklist.kt` import 了 `manager`，`BlacklistStore.kt` 依赖了 `service` 等），属于业务数据混合态 |
| **诊断与日志隔离** | 诊断/日志代码不得影响任何业务决策（红线：取证契约） | **全仓无独立 `diag/` 包**；诊断与事件日志实际分布在 `data/`（如 `EventLog`、`ForegroundDetectionLog`、`ForegroundDiagnosticReport`、`CrashRecorder`）与 `util/`（如 `DiagnosticLogCompressor`），此红线严格成立 |
| **顶层包结构** | 清晰的模块化目录划分 | 实际顶层包为：`data` / `manager`（含 `manager/expiry/`）/ `protection` / `receiver` / `service` / `ui` / `util` / `worker`（无 `diag/`） |

## 2. 核心模块职责

| 模块 | 职责 | 关键文件与实现状态 |
|------|------|----------|
| 勿扰时段 | 时段 CRUD、排序、跨天、业务日、生效策略 | `TimeRuleManager.kt`（序列化已拆至 `data/TimeSegmentJson.kt`；边界校验与时段计算仍在内） |
| 本时段名单 | 继承/脱钩、有效名单判定、硬剔除、加严/放松分类 | `SegmentBlacklist.kt`、`SegmentAdverseAnalysis.kt`（注：`effectiveList` 供分析统计使用，运行时判定使用 `isInEffectiveList`/`isSubjectToBlock`） |
| 额度账本 | 黑名单、扣费、借用、冷却、特权、账本 | `CreditManager.kt`（已部分拆出 `data/UsableCount.kt`、`UsableCountLedgerMigration.kt`、`TemporaryAccessQuotaPlan.kt` 等） |
| 锁机判定 | 前台证据、勿扰锁+时间窗双锁判定、billing | 核心判定流水线**已抽取为纯对象**：`manager/LockDecision.kt`（由 `LockDecisionPolicyTest.kt` 守护），由 `MonitorService.kt` 驱动 |
| 到期决策解析 | 休息/特权到期重锁、前台可见容器防误锁 | `manager/expiry/`（`ExpiryResolver.kt`、`ExpiryCoordinator.kt`、`VisibleContainerResolver.kt`） |
| 锁屏状态机 | 覆盖层生命周期、临时解锁到期重锁、休息面板、挑战 | **`ui/LockWindowManager.kt`**（位于 `ui/` 包的 6500+ 行核心业务状态机，被 manager/、service/ 依赖，后续按职责拆分） |
| 应用时间窗 | 娱乐应用窗口判定与延迟生效 | `AppWindowManager.kt` |
| 闹钟系统 | 调度、挑战生成、震动、防关机拦截 | `AlarmScheduler.kt`、`AlarmWindowManager.kt` 等 `alarm*` 文件 |
| 防护系统 | 防卸载、无障碍保活、小窗/画中画防护 | `protection/`、`FloatingWindowProtectionManager.kt` |
| 日志系统 | 结构化事件日志、导出脱敏压缩 | `data/LogExporter.kt`、`data/EventLog.kt`、`util/DiagnosticLogCompressor.kt` |

## 3. 核心数据流（勿扰锁判定）

```text
MonitorService 收到前台证据
  → 本时段有效名单校验：SegmentBlacklist.isInEffectiveList / isSubjectToBlock
      （脱钩时段 = 独立段名单；继承时段 = 全局黑名单；均减去硬剔除）
  → 双锁与豁免判定（LockDecision.kt）：
      勿扰锁优先，解锁后再查应用时间窗；
      豁免条件：安全应用 || 特权 || 临时解锁（isSafe || isPrivilege || isTemporaryAccess）均绕过双锁
  → 命中锁屏 → LockWindowManager.showLockScreen（带证据与代际）
  → 放行且黑名单命中 → billing（SegmentBlacklist.shouldBill）
```

## 4. 持久化三态（勿扰时段/全局参数）

| prefs 文件 | 含义 |
|-----------|------|
| decikeeper_rules | 当前生效（Active） |
| decikeeper_rules_baseline | 法定基准（快照时点由 `config_refresh_time` 配置，默认 08:00） |
| decikeeper_rules_pending | 待次日生效变更 |

- **比对机制**：
  - 非适应期：变更性质与 **baseline** 比对，加严（有利）立即生效，放松（不利）次日生效。
  - 适应期：整段绕过 baseline 延迟机制，直接 `saveBaselineSnapshot` 并立即生效。
- **时段名单状态**：`listApps` 变更走同一套分类：`null`=跟随全局（继承态），非空=独立名单（脱钩态）。

## 5. 长期收敛方向（未登记为任务计划）

1. **巨型文件按职责拆分**：`LockWindowManager.kt`（位于 `ui/`，需解耦业务状态机与 WindowManager 视图渲染）→ `TimeRuleManager.kt` → `MonitorService.kt` → `CreditManager.kt`。
2. **Fragment 业务逻辑下沉**：清理 `ui/` 层对业务 Prefs 的直接操作，下沉到对应 Manager 或 UseCase。
3. **解耦业务层对 UI 的直接引用**：Manager/Service 不再直接调用 `Activity::class.java`，改为通过应用路由、系统广播或观察者解耦。
4. **单例与依赖注入演进**：核心 Manager 逐步由全局 `object` 单例向可测的接口/依赖注入演进。
5. **真实包结构治理**：以真实包 `manager` / `ui` / `protection` / `data` / `service` / `util` / `receiver` / `worker` 为基准持续清理反向依赖。

