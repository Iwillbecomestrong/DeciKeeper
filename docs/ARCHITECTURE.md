# 架构说明 (ARCHITECTURE)

> 本文描述「知止 (ZhiZhi)」的目标架构与依赖方向。现有代码正在按此逐步重构（refactor/open-source 系列提交），
> 新代码必须遵守，旧代码在改动时顺手迁移。

## 1. 分层与依赖方向

```text
┌─────────────────────────────────────────────┐
│ UI 层 (ui/)                                   │
│ Activity / Fragment / Adapter / Dialog        │
│ 只负责：渲染、收集输入、调用 Manager、弹窗确认 │
└──────────────┬──────────────────────────────┘
               │ 只允许向下调用
┌──────────────▼──────────────────────────────┐
│ 业务层 (manager/)                             │
│ 规则判定、额度账本、生效策略、状态机           │
│ 只负责：纯业务逻辑、持久化、事件发出           │
└──────────────┬──────────────────────────────┘
               │
┌──────────────▼──────────────────────────────┐
│ 系统/数据层 (data/ + Android SDK)             │
│ SharedPreferences、AlarmManager、Accessibility│
└─────────────────────────────────────────────┘
```

**硬性规则**：
- UI 不得直接读写业务 SharedPreferences、不得复制业务判定。
- 业务层不得 import UI 类（Activity/Fragment/View/Adapter）。
- 诊断/日志代码（diag/）不得影响任何业务决策（红线：取证契约）。

## 2. 核心模块职责

| 模块 | 职责 | 关键文件 |
|------|------|----------|
| 勿扰时段 | 时段 CRUD、排序、跨天、业务日、JSON 序列化、生效策略 | TimeRuleManager.kt（将拆分：序列化/边界校验/时段计算） |
| 本时段名单 | 继承/脱钩、有效名单、硬剔除、加严/放松分类 | SegmentBlacklist.kt、classifyListAppsAdverseCore |
| 额度 | 黑名单、扣费、借用、冷却、特权、账本 | CreditManager.kt（将拆分：账本/黑名单/特权） |
| 锁机判定 | 前台证据、勿扰锁+时间窗双锁、billing | MonitorService.kt（判定流水线将抽为纯对象） |
| 锁屏状态机 | 覆盖层生命周期、临时解锁到期重锁、休息面板、挑战 | LockWindowManager.kt（将按职责拆分） |
| 应用时间窗 | 娱乐应用窗口判定与延迟生效 | AppWindowManager.kt |
| 闹钟 | 调度、挑战生成、震动、防关机 | AlarmScheduler.kt 等 alarm* 文件 |
| 防护 | 防卸载、无障碍、隐私空间 | protection/ |
| 日志 | 事件日志、导出、脱敏压缩 | LogExporter.kt、EventLog.kt |

## 3. 核心数据流（勿扰锁判定）

```text
MonitorService 收到前台证据
  → 本时段有效名单 = SegmentBlacklist.effectiveList(当前段)
      （脱钩时段 = 段名单；继承时段 = 全局黑名单；均减去硬剔除）
  → 双锁判定：勿扰锁优先，解锁后再查应用时间窗；特权绕过两把锁
  → 命中 → LockWindowManager.showLockScreen（带证据与代际）
  → 放行且黑名单命中 → billing（SegmentBlacklist.shouldBill）
```

## 4. 持久化三态（勿扰时段/全局参数）

| prefs 文件 | 含义 |
|-----------|------|
| iron_will_rules | 当前生效（Active） |
| iron_will_rules_baseline | 法定基准（08:00 快照） |
| iron_will_rules_pending | 待次日生效变更 |

- 变更性质一律与 **baseline** 比对：加严（有利）立即生效，放松（不利）次日生效。
- 时段名单（listApps）变更走同一套分类：`null`=跟随全局（继承态），非空=独立名单（脱钩态）。

## 5. 迁移路线（refactor/open-source 进行中）

1. 巨型文件按职责拆分，行为不变：TestScenarioManager（移出 main）→ LockWindowManager → TimeRuleManager → MonitorService → CreditManager。
2. Fragment 业务逻辑下沉到 Manager/UseCase。
3. 核心 Manager 由 object 单例改为接口 + 构造注入（UI 依赖接口）。
4. 包结构按 4.1 落地：com.ironwill.app.manager / ui / protection / data / diag。
