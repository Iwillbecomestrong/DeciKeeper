# 知止 (ZhiZhi) 文档中心 (docs)

> **定位**：本目录收录「知止」项目的架构设计、业务规格、使用指南、开源合规、专项计划与历史工程档案。
> 本文件同时作为进入 `docs/` 的 **Local README Gate**。

---

## 1. 文档架构地图

```text
docs/
├── ARCHITECTURE.md          # 核心系统架构设计、依赖流向与重构路线图
├── DATA_SOURCES.md          # 数据集来源、开源版权清洗合规与剥离说明
├── THIRD_PARTY_NOTICES.md   # 第三方开源组件与库许可证声明
├── PUBLISHING.md            # Gitee/GitHub 双推、构建签名与发版检查清单
├── 使用手册.md               # 面向最终用户的完整操作与使用说明
├── specs/                   # 当前详细业务规格
│   ├── focus.md             # 时间规则、额度、休息/临时解锁/特权
│   ├── alarm.md             # 起床闹钟、挑战、双阶段与恢复
│   ├── protection.md        # 无障碍、防卸载、防关权限、隐私空间、防关机
│   └── data.md              # 业务日、Active/Baseline/Pending、备份与持久化
├── plans/                   # 专项实施/设计计划，不作为当前产品真相
│   └── home-ui.md           # 首页与锁屏 UI 候选、数据口径和验收清单
├── history/                 # 历史演进与踩坑排查记录
│   ├── issue_tracker.md     # 完整历史缺陷与排查记录
│   └── fixtures/            # 历史排查转储证据与样本
├── verification/            # 真机/专项验证记录
├── images/                  # 视觉与图档资源
│   └── screenshots/         # 核心界面预览与真机截图
├── content/                 # B 站首发内容稿件（见 content/README.md）
└── ai/                      # AI 演进与辅助文档
```

---

## 2. 文档职责

项目不再维护一份巨型 `requirements.md` 与根 `SPEC.md` 相互竞争。

```text
SPEC.md
  ↓ 总原则 / 跨模块契约

docs/specs/*.md
  ↓ 各模块详细产品规格

docs/使用手册.md
  ↓ 面向普通用户的解释与操作

App Help
  ↓ 构建时读取同一份使用手册资源
```

- **根 `SPEC.md`**：产品定位、跨模块原则、模块地图、稳定验收契约。
- **`docs/specs/`**：当前详细业务规则，每条详细规则只指定一个权威归属文件。
- **`PLAN.md` / `docs/plans/`**：尚未实现、待选型或专项实施步骤。
- **`HISTORY.md` / `docs/history/`**：已经发生的决策、缺陷、排查与历史背景。
- **`docs/使用手册.md`**：唯一完整的面向用户说明，不重新定义另一套业务规则。

---

## 3. 核心文档导览

| 文档 | 读者角色 | 核心内容 |
|:---|:---|:---|
| [`../SPEC.md`](../SPEC.md) | 产品 / 开发 / AI | 当前产品总规格与跨模块契约。 |
| [`specs/focus.md`](specs/focus.md) | 产品 / 开发 | 勿扰时段、应用时间窗、时间规则生效策略、额度、休息、临时解锁和每周特权。 |
| [`specs/alarm.md`](specs/alarm.md) | 产品 / 开发 | 起床闹钟、任意 QR、双阶段、题库、重启恢复与闹钟可靠性。 |
| [`specs/protection.md`](specs/protection.md) | 产品 / 开发 | 无障碍、防卸载、防关权限、隐私空间、闹钟防关机及 OEM 边界。 |
| [`specs/data.md`](specs/data.md) | 开发 / 维护 | 业务日、Active/Baseline/Pending、本地持久化与备份兼容。 |
| [`ARCHITECTURE.md`](ARCHITECTURE.md) | 架构师 / 核心贡献者 | UI → Manager → Data 依赖架构与重构路线。 |
| [`history/issue_tracker.md`](history/issue_tracker.md) | 开发 / 维护 | 真机极端异常、厂商 ROM 排查和防回归记录。 |
| [`DATA_SOURCES.md`](DATA_SOURCES.md) | 合规 / 贡献者 | 内置学习数据来源与版权边界。 |
| [`PUBLISHING.md`](PUBLISHING.md) | 维护 / 发布 | 构建、签名、GitHub/Gitee 与发布检查。 |
| [`使用手册.md`](使用手册.md) | 终端用户 | 完整上手、配置、权限与实际使用说明。 |
| [`plans/home-ui.md`](plans/home-ui.md) | UI / 开发 | 首页与锁屏候选、数据来源和后续验收。 |

---

## 4. 维护规则

1. 进入 `docs/` 修改前先读本文件和目标子目录的现有结构。
2. 当前产品行为先统一到根 `SPEC.md` 与对应 `docs/specs/*.md`，再同步使用手册和 App Help。
3. 未确认方案不得写入正式规格；放入 `PLAN.md` 或 `docs/plans/`。
4. 过期实现细节不在当前规格中永久堆积；有保留价值的迁移到 History，普通版本差异交给 Git。
5. 文档中不要把默认值写成不可配置的产品铁律，除非代码、测试与产品决策都明确它是固定契约。
