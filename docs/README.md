# 决定守护 (DeciKeeper) 文档中心 (docs)

> **定位**：本目录收录「决定守护」项目的产品说明、架构设计、业务规格、用户文档、开源合规与历史工程档案。
> 本文件同时作为进入 `docs/` 的 **Local README Gate**。

---

## 1. 文档分层

```text
README.md
  面向用户：是什么、为什么、有些什么功能

docs/product/system-overview.md
  面向贡献者：为什么这样设计、模块之间什么关系

SPEC.md
  面向开发：系统契约

docs/specs/*.md
  面向实现：具体模块规则

docs/user/*.md
  面向用户：怎么用、权限是什么意思
```

一条详细业务规则只能有一个权威归属；摘要可以重复，但不得维护两套互相竞争的全量规格。

---

## 2. 目录地图

```text
docs/
├── README.md                # 本文件：docs 入口与维护规则
├── product/                 # 产品总览层
│   └── system-overview.md   # 产品定位、模块关系、设计理由、术语规范
├── specs/                   # 当前详细业务规格
│   ├── focus.md             # 时间规则、额度、休息/临时解锁/特权
│   ├── alarm.md             # 起床系统、挑战、双阶段与恢复
│   ├── protection.md        # 无障碍、防卸载、防关权限、隐私空间、防关机
│   ├── data.md              # 业务日、Active/Baseline/Pending、备份与持久化
│   └── statistics.md        # 真实统计、应用分类与图表规范
├── user/                    # 面向最终用户的文档（同时是 App 内置帮助的单一来源）
│   ├── README.md            # 本目录入口与维护规则
│   ├── getting-started.md   # 第一次使用
│   ├── guide.md             # 完整功能指南
│   └── permissions.md       # 权限与隐私说明
├── plans/                   # 当前分任务计划（不维护根总计划）
├── ARCHITECTURE.md          # 代码分层与依赖方向
├── DATA_SOURCES.md          # 数据集来源与版权边界
├── THIRD_PARTY_NOTICES.md   # 第三方开源组件许可证
├── PUBLISHING.md            # 双推、构建签名与发版检查
├── history/                 # 历史演进与踩坑排查记录
├── verification/            # 真机/专项验证记录
├── images/                  # 视觉与图档资源
├── ai/                      # AI 演进与辅助文档
└── archive/                 # 已退役方案 / 旧命名 / 历史工作稿（只归档，不再维护）
```

---

## 3. 核心文档导览

| 文档 | 读者角色 | 核心内容 |
|:---|:---|:---|
| [`../README.md`](../README.md) | 潜在用户 / 来访者 | 产品是什么、有哪些功能。 |
| [`product/system-overview.md`](product/system-overview.md) | 贡献者 / AI | 产品定位、两个核心模块、统一设计原则、术语规范。 |
| [`../SPEC.md`](../SPEC.md) | 产品 / 开发 / AI | 当前产品总规格与跨模块契约。 |
| [`specs/focus.md`](specs/focus.md) | 产品 / 开发 | 勿扰时段、应用时间窗、时间规则生效策略、额度、休息、临时解锁和每周特权。 |
| [`specs/alarm.md`](specs/alarm.md) | 产品 / 开发 | 起床闹钟、任意 QR、双阶段、题库、重启恢复与闹钟可靠性。 |
| [`specs/protection.md`](specs/protection.md) | 产品 / 开发 | 无障碍、防卸载、防关权限、隐私空间、闹钟防关机及 OEM 边界。 |
| [`specs/data.md`](specs/data.md) | 开发 / 维护 | 业务日、Active/Baseline/Pending、本地持久化与备份兼容。 |
| [`specs/statistics.md`](specs/statistics.md) | 产品 / 开发 | 真实数据抽取、游戏聚合分类、自适应图表与色彩规范。 |
| [`user/getting-started.md`](user/getting-started.md) | 终端用户 | 第一次安装、开权限、创建第一条规则。 |
| [`user/guide.md`](user/guide.md) | 终端用户 | 完整功能、额度口径、常见问题。 |
| [`user/permissions.md`](user/permissions.md) | 终端用户 | 权限用途与隐私说明。 |
| [`ARCHITECTURE.md`](ARCHITECTURE.md) | 架构师 / 核心贡献者 | UI → Manager → Data 依赖架构与重构路线。 |
| [`history/issue_tracker.md`](history/issue_tracker.md) | 开发 / 维护 | 真机极端异常、厂商 ROM 排查和防回归记录。 |
| [`DATA_SOURCES.md`](DATA_SOURCES.md) | 合规 / 贡献者 | 内置学习数据来源与版权边界。 |
| [`PUBLISHING.md`](PUBLISHING.md) | 维护 / 发布 | 构建、签名、GitHub/Gitee 与发布检查。 |
| [`archive/`](archive/) | 溯源 | 已退役方案与历史工作稿，不作为当前真相。 |

---

## 4. 维护规则

1. 进入 `docs/` 修改前先读本文件和目标子目录的现有结构。
2. 产品定位与模块关系的唯一归属是 `docs/product/system-overview.md`；当前产品行为先统一到根 `SPEC.md` 与对应 `docs/specs/*.md`，再同步 `docs/user/`。
3. 未确认方案不得写入正式规格，放入 `docs/plans/`；本项目**不维护根总计划**，执行路线按任务拆分，历史交给 `HISTORY.md`。
4. 过期实现细节不在当前规格中永久堆积；有保留价值的迁移到 History，退役方案放入 `docs/archive/`，普通版本差异交给 Git。
5. 文档中不要把默认值写成不可配置的产品铁律，除非代码、测试与产品决策都明确它是固定契约。
6. 历史名称（IronWill / ZhiZhi / 知止 / 承诺保护系统）只允许出现在 `HISTORY.md`、`docs/history/`、`docs/archive/` 与 Git 提交历史中。
7. `docs/user/` 同时是 App 内置帮助的单一来源：改完用户文档要同步 `HelpManualSingleSourceContractTest` 的断言，并重新构建 APK。
