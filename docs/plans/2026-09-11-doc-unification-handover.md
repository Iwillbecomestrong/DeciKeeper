# 交接：决定守护文档统一与漂移收敛

> ⚠️ **本文写于工作的中段，部分内容已过时。请优先阅读
> [`2026-09-11-session-master-handover.md`](2026-09-11-session-master-handover.md)（总交接）。**
>
> 本文之后又完成的工作（详见总交接 §2）：术语统一为「临时解锁」、删除按秒加权死代码 174 行、
> `protection.md` 新增 §7「小窗可见性增强」整节、App 内法律文档（用户协议 / 隐私政策 / 同族文件）修正。
> 本文 §5 列出的剩余漂移清单**仍然有效**，可直接使用。

> **给接手的 AI**：本文自包含。请先读完再动手。这项任务**只完成了一部分**——
> 审计已完成（87 处漂移），但真正修掉的只有与「冷却/借用口径」和「第二阶段」相关的几处。

---

## 1. 任务是什么

把仓库里**过时、互相不一致**的文档收敛到与当前代码一致。原话来自用户：

> 「现在很多文档都是旧的，不统一」
> 「只有 README、SPEC、隐私说明及权限说明那个文档是最新的可能」

工作分两层：
1. **结构层**（已完成）：建立文档分层，退役根 PLAN.md，用户手册拆分为 docs/user/。
2. **内容层**（大部分未完成）：逐份文档对照代码修正漂移。

---

## 2. 分支与环境

| 项 | 值 |
| --- | --- |
| **工作 worktree** | `<worktree-docs>` |
| 分支 | `docs/product-refactor`（已 rebase 到最新 dev） |
| 基线 | `dev` = `fde3c93`（落后 0） |
| 备份标签 | `backup/docs-product-refactor`（rebase 前的原始 HEAD） |
| 主仓库 | `<main-repo>`（dev，工作区干净，**不要动**） |
| 另一 worktree | `<worktree-alarm-overlay>`（分支 `fix/alarm-overlay-detach`，闹钟白屏 bug，见另一份交接） |

### 构建与测试

```bash
cp ../IronWill-OpenSource/local.properties .   # worktree 里没有，需复制
./gradlew.bat --console=plain :app:testDebugUnitTest :app:assembleDebug
# 当前基线：1201 例 / 0 失败 / 11 跳过
```

---

## 3. 关键上下文：dev 已经跑到哪里了

**这一点必须理解，否则会重复劳动或改错基线。**

dev 最新提交是 `fde3c93`（"feat(focus): 实现双锁系统收敛、到期决策解析器与浮窗保护"），
67 个文件、+5464/−1308，包含四大模块的新功能：

1. **到期决策解析器**：新增 `manager/expiry/` 包（`ExpiryResolver` / `ExpiryCoordinator` / `VisibleContainerResolver`），
   解决"休息或特权到期后无条件硬锁屏、把正常退桌面和白名单应用也误锁"的问题。
2. **双锁出口正交化**：纯勿扰锁屏彻底移除「临时解锁」入口；时间窗/双锁锁屏不提供「申请休息」。
3. **浮窗防护**：新增 `FloatingWindowProtectionManager`，防画中画/小窗绕过黑名单。
4. **首页新组件**：`TodayAlarmView` 今日闹钟卡片。

**它对文档做了什么**：重写了 `docs/specs/focus.md`(+121/−72)、`docs/specs/protection.md`(+59)、`SPEC.md`(+24/−15)。

**这带来的两个后果**：
- 早期基于 `f8a1ae0` 的 `focus.md` 与 `protection.md` 审计结论**已作废**，需要基于当前代码重做（本分支已派出重审）。
- `fde3c93` **没有**修好"冷却与借用"的概念矛盾：它把休息/临时解锁改成整单倍率，
  但借用的 `zoneWeighted` 仍在，且 `focus.md` 里"冷却 ×2 与借用是两个不同概念、不得混为一谈"这句仍在。
  **本分支已修掉这部分**（见下）。

---

## 4. 本分支已完成的部分

最新提交（`git log --oneline dev..HEAD`，共 9 个）：

| 提交 | 内容 |
| --- | --- |
| `e18d966` | 文档分层重构：新增 `docs/product/system-overview.md`；退役根 `PLAN.md`/`task_plan.md`；
| | 用户手册拆为 `docs/user/{getting-started,guide,permissions}.md`；旧计划与散落文件移入 `docs/archive/` |
| `2897b4b` | GitHub 仓库更名 DeciKeeper，同步远端与链接 |
| `58bd029` | 修复源码乱码 105 行；移除残留 DeviceAdmin；纠正「首页 5 项权限」错数字 |
| `db11519` / `ad31699` / `24cc111` | 闹钟白屏死锁的真机复现记录与根因更正 |
| `09d566c` → `277f20d` | 冷却倍率收敛为平坦倍率，删除借用按秒加权（见下） |
| `398b2ca` | rebase 到 `fde3c93` 后的冲突解错修正 |
| `7624b83` | 统一冷却/借用与第二阶段口径的文档表述 |

### 4.1 冷却/借用口径收敛（产品决定，已落地）

用户裁定：**冷却一律整单倍率，不做按秒分区加权**。

| 场景 | 倍率 |
| --- | --- |
| NORMAL 申请（休息/临时解锁）触及冷却期 | 时长与次数**整单 ×2** |
| FREE_TIME / REST | 仍免费，不受冷却影响 |
| 借用触及冷却期 | **整单 ×4**（借用 ×2 × 冷却 ×2，借 1 扣 4） |
| 借用未触及冷却 | 整单 ×2（借一还二） |

代码改动：
- `LockWindowManager.calculateWeightedRestCountImpl`：触及冷却 → 2；否则保留 `round(勿扰占比)`
- `LockWindowManager.calculateWeightedQuotaSeconds`：触及冷却 → 整单 ×2
- `CreditManager.calculateWeightedBorrowSeconds`：**删除 `zoneWeighted`**，改为触及冷却 → 整单 ×4、否则 ×2；空档秒不计费
- 测试：`WeightedRestCountTest` 更新 7 条期望；**新增 `BorrowPenaltyFormulaTest`（5 例）——该函数此前零覆盖**

文档改动：`focus.md` / `guide.md` / `SPEC.md` 补"借 1 扣 4"；`HISTORY.md` 新增 `DECISION-29`。

### 4.2 第二阶段为空（产品决定，已落地）

代码是对的（保存与开始测试都拦截空值），错的是文档：
- `AGENT_CORE.md` §3.4「第二阶段任务允许为空」→ 改为「每一关都必须至少选择一种挑战」
- `docs/user/guide.md`「第二关的题型…也可以留空」→ 改为同上

### 4.3 其它已修

- 源码乱码：`CoreService.kt` / `AntiUninstallProtection.kt` 共 119 行 GBK→UTF-8 还原（47 行各有 1 字符永久丢失，按上下文补全）
- 移除残留 `DeviceAdmin` receiver 与 `res/xml/device_admin.xml`（从不触发却声明 force-lock 高权限）
- 「首页 5 项基础权限」纠正为实际 4 项，**并修正了那个把错数字钉死的测试**

---

## 5. 未完成的部分（**主体工作在这里**）

完整清单见 `docs/plans/2026-09-11-doc-drift-audit.md`，每条带 `文件:行号` 级证据。
当初共查出 **87 处已确认漂移**，修掉的只是与冷却/借用、第二阶段相关的一小部分。

### 5.1 需要**重审**的（旧结论作废）

这两份被 `fde3c93` 重写过，基于 `f8a1ae0` 的审计结论不可信，必须基于当前代码重做：

| 文档 | 旧审计结论 | 状态 |
| --- | --- | --- |
| `docs/specs/focus.md` | 2 / 8 核对点漂移（"最健康的一份"） | **作废，需重审** |
| `docs/specs/protection.md` | 8 / 97 | **作废，需重审** |

> 本分支在交接时已派出两个只读审计单元重跑这两份，结果回来后请合并进 `doc-drift-audit.md`。

### 5.2 不受 `fde3c93` 影响、清单可直接用的

| 文档 | 漂移数 | 说明 |
| --- | ---: | --- |
| `docs/ARCHITECTURE.md` | **15 / 33** | 最严重。三条"硬性规则"当前代码全部不成立（`manager/` 有 8 处真实 UI 依赖、`ui/` 有 20 处直接读写业务 prefs、`diag/` 包**不存在**）；"重构进行中"无任何进度载体 |
| `docs/ai/LOG_EVENT_MIGRATION.md` | **12 / 21** | 行号锚点大面积失效，多处行号已超出文件总行数（如 CreditManager 总行 3744，文档写 3804） |
| `docs/specs/statistics.md` | 8 / 29 | 多为"功能已实现但入口被隐藏"（应用/规则变动 tab 永久 `gone`、`StatsFilterActivity` 入口 gone、`ContrastHelper.getOptimalTextColor` 生产零调用） |
| `docs/specs/alarm.md` | 8 / 52 | 含 [高]：文档称"顶底适配 WindowInsets"，实际 `LockAlarmOverlay` 全文对 `WindowInsets` 命中为 0 |
| `docs/PUBLISHING.md` | 7 / 15 | 称"Gitee 尚未创建"，实际 git remote 早有 gitee 且可推送 |
| `docs/specs/data.md` | 5 / 47 | 业务日锚点、备份协议缺失 AutoBackupManager 通道 |
| `CHANGELOG.md` | 4 / 9 | **代码反向引用了一条不存在的条目**；329 个提交未收录 |
| `docs/DATA_SOURCES.md` | 4 / 17 | 考研词汇.txt 标 48KB 实为 43,006B，且同文档内自相矛盾 |
| `docs/THIRD_PARTY_NOTICES.md` | 2 / 12 | 三个库版本硬编码在模块脚本而非版本目录；漏记 androidTest 依赖 |

### 5.3 审计顺带捞出、**尚未处理**的代码实体缺陷

这些不是文档问题，但都在 `doc-drift-audit.md` §2 有记录：

1. **`app/src/main/assets/duilian.json` 是非法 JSON**（第 201 行缺逗号），无任何代码引用，**却被打进 APK（65,853 字节坏数据）**。合法的那份是 `duilian_single.json`——说明当年清洗过一份，坏的没删。
2. **`AlarmFragment.kt` 的「保存为默认设置」路径会静默回填**：用户显式保存"第二阶段无任务"会被替换成 4 种题型。与"不允许为空"的口径及 `alarm.md` §265「不得被默认值逻辑偷偷重新填回」冲突。
3. **`AlarmPowerOffProtectionManager.isEnabled()` 无运行时消费点**：拦截只由 `LockWindowManager.isAlarmOverlayShowing` 触发，开关值只被设置页读取用于渲染。**即关掉开关也不改变拦截行为。**

### 5.4 假契约测试（守着文档但守不住）

这一类最危险，因为会让"有测试守护"变成假信号：

- `LogEventMigrationInventoryTest`：只断言文档中**含有**某些字符串，**从不与源码行号比对**（见其 `:209-216`）。这就是 `LOG_EVENT_MIGRATION.md` 12 处行号全错、测试却依然通过的原因。
- `AppUsageCalculationTest.kt:44-50`：24 小时截断断言**自行 coerce 本地字面量**，未调用生产代码。
- `BusinessDayChangeTest.kt:200-239`：是测试内自写的**镜像函数**，非生产绑定契约。
- `ARCHITECTURE.md` 的三条硬性规则：**全测试树 0 条契约测试**。

**建议的处理顺序**（成本最低、收益最大）：
1. **先删行号，改引用符号名**——文档里不写行号，那 12 处错误就根本不会存在。
2. **再写"引用存在性"测试**：从文档抽出反引号包裹的符号名/文件路径，断言它们在源码中真实存在。
3. 行号校验测试**只当一次性体检工具**用，清完就把行号从文档里删掉——长期开着会天天变红，最后被忽略。

---

## 6. 工作流程与踩过的坑

### 6.1 提交规范

- 提交信息用中文，格式为 `type(scope): 简述`
- 一个提交只做一件可说明的事
- **不要**创建 `SPEC_v2.md` / `PLAN_final.md` 之类平行真相文件
- 根目录**不维护总计划**；未确认方案放 `docs/plans/`，完成的历史进 `HISTORY.md`

### 6.2 踩过的坑

1. **反引号会截断 TS 模板字符串**——写脚本批量改文档时，若用模板字符串包裹含反引号的内容会报语法错误，用占位符替换。
2. **`adb logcat -f <path>` 在本机被沙箱拒绝**（报 Read-only file system），必须用管道 `adb logcat | Out-File -Append`。
3. **`adb install` 走无线会永久挂起**且装不上，改用 `adb push` + `pm install -r -d`。
4. **不要 `adb logcat -c`**：会冲掉关键证据（曾因此丢掉扫码成功那几秒的日志）。
5. **rebase 后必须复跑全量测试**：本分支 rebase 到 `fde3c93` 时，一处冲突解错（把 `fde3c93` 刻意删除的防卸载自动开启代码加了回来），靠 `AntiUninstallStartupProtectionContractTest` 才抓出来。
6. **git 自动合并不会发现语义冲突**：rebase 时双方各加了一条 `DECISION-26`，文本不重叠所以 git 不报冲突，需要人工核对编号。

---

## 7. 红线（必读 AGENT_CORE.md）

1. **不得 `adb uninstall`**，会清空用户真实规则与题库数据。
2. **不得删除** `docs/history/issue_tracker.md` 中带真机根因的防御性逻辑（AGENT_CORE §3.5）。特别注意 **05-27 行**记录的"移除覆盖层以让用户使用相机"取舍。
3. **不得把"前台检测/时间拦截/防卸载全部单点依赖无障碍"写成事实**（AGENT_CORE §4.1）。
4. **不得把扫描二维码写成"必须扫描指定/绑定二维码"**；不得把第二阶段间隔写成固定 3/5 分钟。
5. 历史名称 `IronWill` / `ZhiZhi` / `知止` / `承诺保护系统` 只允许出现在 `HISTORY.md`、`docs/history/`、`docs/archive/` 与 Git 历史中。（但 `zhizhi_settings_backup` / `iron_will_*` 这类**数据兼容标识**是有意保留的，不要动。）

---

## 8. 相关文件索引

| 文件 | 用途 |
| --- | --- |
| `docs/plans/2026-09-11-doc-drift-audit.md` | **漂移审计报告**（87 条，带证据）。注意其中 focus.md / protection.md 两节已作废 |
| `docs/plans/2026-09-11-alarm-whitescreen-handover.md` | 闹钟白屏死锁的交接（另一条独立工作流） |
| `docs/product/system-overview.md` | 产品总览层（本分支新增，回答"为什么这样设计"） |
| `docs/user/` | 面向用户的文档（getting-started / guide / permissions） |
| `docs/README.md` | docs 入口与文档分层约定 |
| `AGENT_CORE.md` | AI 协作协议与业务红线（必读） |

### 日志文件（仓库外，未入库）

| 文件 | 内容 |
| --- | --- |
| `~/.alarm-verify2.log` | 闹钟白屏相关的最新真机日志 |

