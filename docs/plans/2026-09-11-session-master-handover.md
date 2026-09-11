# 总交接：决定守护仓库现状与后续工作

> **给接手的 AI**：本文自包含。请先读完 §1、§4、§5 再动手。
> 本会话的主战场是 **文档体系重构与漂移收敛**（分支 `docs/product-refactor`，14 个提交，**尚未合并**），
> 另有一条闹钟 bug 线已交接给其他 agent（**正在被修改，不要碰**）。

---

## 1. 环境总览（先看这张表）

| worktree | 分支 | HEAD | 状态 | 能不能动 |
| --- | --- | --- | --- | --- |
| `G:/IronWIl0329/IronWill-OpenSource` | `dev` | `fde3c93` | 干净 | **不要动**（主仓库） |
| `G:/IronWIl0329/IronWill-docs` | `docs/product-refactor` | `e247731` | 干净 | ✅ **本会话主战场** |
| `G:/IronWIl0329/IronWill-alarm-overlay` | `fix/alarm-overlay-detach` | `eefa178` | **有未提交改动** | ❌ **另一个 AI 正在做，不要碰** |
| `G:/IronWIl0329/IronWill-time-rule-edit-window` | `feat/time-rule-edit-window` | `23c821a` | — | ❓ 本会话不了解 |
| `C:/Users/23248/.codex/worktrees/*` | `codex/*` 等 | — | — | 历史分支 |
| `G:/IronWIl0329/IronWill-decikeeper` / `-return-home-penalty` | `ai/*` | — | — | 历史分支 |

**关键事实**：`dev` 已经走到 `fde3c93`（"实现双锁系统收敛、到期决策解析器与浮窗保护"，67 文件 +5464/−1308），
本会话的文档分支**已 rebase 到它之上**，落后 0。

---

## 2. 本会话完成的工作（分支 `docs/product-refactor`，17 个提交）

### 2.1 文档结构重构

- 新增 `docs/product/system-overview.md`（产品总览层，回答"为什么这样设计"）
- **退役根 `PLAN.md` 与 `task_plan.md`**：不再维护总计划，改为 `docs/plans/` 分任务计划 + `HISTORY.md`
- `docs/使用手册.md` 拆为 `docs/user/{getting-started,guide,permissions}.md`，原文件归档
- **App 内置帮助改由 `docs/user/` 单源生成**（原为 `docs/使用手册.md`），构建任务改为 `Sync` 以免残留旧副本
- 旧计划、旧命名、散落文件统一移入 `docs/archive/`；`docs/README.md` 重写为文档分层地图
- 各新目录补 Local README Gate

### 2.2 冷却/借用口径收敛（**产品决定，已落地并同步文档**）

| 场景 | 倍率 |
| --- | --- |
| NORMAL 申请（休息 / 临时解锁）触及冷却期 | 时长与次数**整单 ×2** |
| FREE_TIME / REST | 仍免费，不受冷却影响 |
| 借用触及冷却期 | **整单 ×4**（借用 ×2 × 冷却 ×2，借 1 扣 4） |
| 借用未触及冷却 | 整单 ×2（借一还二） |
| ~~按秒分区加权~~ | **已删除** |

代码：`calculateWeightedRestCountImpl` / `calculateWeightedQuotaSeconds` / `calculateWeightedBorrowSeconds`（删除 `zoneWeighted`）。
测试：`WeightedRestCountTest` 更新 7 条期望；**新增 `BorrowPenaltyFormulaTest`（5 例）——该函数此前零覆盖**。
文档：`focus.md` / `guide.md` / `SPEC.md` 补"借 1 扣 4"；`HISTORY.md` 新增 `DECISION-29`。

### 2.3 术语与文案统一

- **用户可见文案统一为「临时解锁」**：`focus.md` 早有规定禁用「临时放行」，但生产文案一直在用，
  而原契约测试只拦「超级临时」「超级临时放行」。已替换 `LockWindowManager.kt`(11)、
  `activity_global_params_new.xml`(1)、`LockScreenPresentationPolicy.kt`(1)、`MonitorService.kt`(1)，
  并**补上 `assertFalse(contains("临时放行"))`**使其不能再溜回来
- **第二阶段不允许为空**：`AGENT_CORE.md` §3.4 原文"允许为空"已过期（代码在保存与开始测试时均拦截），已改为"每一关都必须至少选择一种挑战"；
  `guide.md`「第二关…也可以留空」同步更正
- **GitHub 仓库更名 DeciKeeper**：`git remote github` 与 README/文档链接同步（Gitee 侧经 SSH 探测仍只有 `zhizhi`）

### 2.4 代码缺陷修复

- **源码乱码 119 行**：`CoreService.kt` / `AntiUninstallProtection.kt` 的 GBK 乱码用反向解码还原
  （47 行各有 1 字符永久丢失，按上下文补全）。其中 `AntiUninstallProtection.kt` 的 `.setTitle("导出日志 (防卸载)")`
  **是用户可见弹窗标题**——乱码不只影响日志
- **移除残留 DeviceAdmin**：`receiver/DeviceAdmin.kt` + `res/xml/device_admin.xml` 删除，Manifest receiver 与
  `AntiUninstallManager.isDeviceAdminActive()` 一并清除（从不触发却声明 `force-lock` 高权限）
- **「首页 5 项基础权限」纠错**：实际只有 4 项（悬浮窗/通知/后台耗电管理/自启动），
  并**修正了那个把错数字钉死的测试**（原测试断言必须含"5 项基础权限"）
- **删除按秒加权死代码 −174 行**：`showRestCoolingTempAccessDialog` 与 `showCoolingConfirmDialog`
  内部仍是旧分区加权且全仓无调用点，与"已收敛为平坦倍率"的规格相反，易被误接线回来

### 2.5 App 内法律文档修正（只动产品事实，法律条款未动）

- `user_agreement.txt`：**「每周特权」与「临时解锁」不再混用**（原表述正是 `focus.md` §12.1 禁止的"特权进入临时解锁流程"）；
  「起床解题闹钟」→「挑战闹钟」；定位改"决定保护软件"；删死链接
- `privacy_policy.txt`：消除"§八 声明无账号体系"与"§七 让人去账号注销入口"的**自相矛盾**；3 处死链接改正；「核心自律功能」→「核心决定保护功能」
- 同族文件：`third_party_sdk_list.txt`（死链接）、`personal_info_list.txt`（3 处"自律"）、`contact_and_complaint.txt`（临时放行）
- **保留占位符 `[待法务填写 vX.Y.Z]`**——这两份是待法务 review 的占位文本

### 2.6 审计与重审

- 产出 `docs/plans/2026-09-11-doc-drift-audit.md`：**89 处已确认漂移**，每条带 `文件:行号` 证据
- `focus.md` 与 `protection.md` 因被 `fde3c93` 重写而**重审**（旧结论作废）
- `protection.md` 的 7 处漂移**已全部修完**（含新增 §7「小窗可见性增强」整节），原 §7–§11 顺延为 §8–§12
- `focus.md` 的 5 处中 2 处已修，3 处待决策（见 §3.1）

### 2.7 架构真实性与去行号化收敛（已完成，提交 `7c4b49b`）

- **`docs/ARCHITECTURE.md` 15 处漂移清零**：
  - 建立「目标架构规范」vs「当前代码现状（演进过渡态）」明确对照表，承认 `ui/` 直读 Prefs 与 `manager/` 引用 UI 的历史过渡态；
  - 彻底清理虚构的 `diag/` 包，明确诊断与事件日志分布在 `data/` 与 `util/`；
  - 纠正 `ui/LockWindowManager.kt` 属于 `ui/` 包巨型业务状态机、判定流水线已抽为 `manager/LockDecision.kt`、补充 `manager/expiry/` 到期决策解析器。
- **`docs/ai/LOG_EVENT_MIGRATION.md` 12 处行号漂移清零**：
  - 彻底去除脆弱的物理绝对行号，全面升级为「文件 + 符号锚点」映射；
  - 校准 `LogExporter.kt` 40 条 Regex 覆盖 36 条审计基准与 49 行数据行；
  - **`LogEventMigrationInventoryTest.kt` 契约升级**：剔除已失效硬编码行号字符串断言，升级为断言稳定符号锚点与生产源码门控存在性。

### 2.8 业务规格与外围文档事实校准（已完成，提交 `868c59d`）

- **`docs/specs/statistics.md` 8 处清零**：标记「应用」与「规则变动」Tab 及筛选入口当前在 UI 处于隐藏状态（`gone`），说明底层计算就绪；纠正单日桶截断口径与高对比度文字规则。
- **`docs/specs/alarm.md` 8 处清零**：纠正 `LockAlarmOverlay` 实体不透明背景（`?attr/zzPageBg`，严禁透明）、内边距安全留白（`24dp`）；限定自定义题库提示规范；纠正内置三类 AI 提示词。
- **`docs/specs/data.md` 5 处清零**：确立业务日以用户配置时点（`config_refresh_time`）为首要真理源（v3 反转）；补全 `decikeeper`/`zhizhi`/`ironwill` 三重备份格式兼容；补充 `AutoBackupManager` 自动双写通道；锁定隐私授权不参与备份。
- **外围文档统一**：`PUBLISHING.md`（更新 Gitee/GitHub 远端连通性与 1200+ 测试基线）、`DATA_SOURCES.md`（修复表格与文件大小矛盾）、`THIRD_PARTY_NOTICES.md`（注明模块脚本硬编码依赖与完整测试依赖）、`CHANGELOG.md`（补齐 0.2.0 特权截断条目、移除互斥的知止更名草稿、收录到期决策与平坦倍率等关键变更）。

### 2.9 冗余坏资产清理（已完成，提交 `e247731`）

- **彻底删除 `app/src/main/assets/duilian.json`**：该文件第 201 行缺逗号非法且全仓零引用，移除非法数据使每个构建出的 APK 减少 65,853 字节坏数据，合法数据保留 `duilian_single.json`。

---

## 3. 未完成的工作

### 3.1 文档漂移的剩余部分（主体已收敛）

| 文档 | 状态 | 说明 |
| --- | :---: | --- |
| `docs/ARCHITECTURE.md` | ✅ **已全部修完** | 15 处漂移已清空，建立原则 vs 现状对比表，去除虚构 diag/ |
| `docs/ai/LOG_EVENT_MIGRATION.md` | ✅ **已全部修完** | 12 处行号漂移已清空，符号锚点绑定，契约测试同步升级 |
| `docs/specs/protection.md` | ✅ **已全部修完** | 7 处漂移已清空，补全小窗可见性增强章节 |
| `docs/specs/statistics.md` | ✅ **已全部修完** | 8 处漂移已清空，标记隐藏分区与入口，纠正单桶时长与对比度 |
| `docs/specs/alarm.md` | ✅ **已全部修完** | 8 处漂移已清空，纠正悬浮窗安全内边距、实体背景与 AI 提示词 |
| `docs/specs/data.md` | ✅ **已全部修完** | 5 处漂移已清空，业务日优先级、三重备份与 AutoBackup 通道已记录 |
| `docs/PUBLISHING.md` | ✅ **已全部修完** | 7 处漂移已清空，更新 Gitee/GitHub 远端与 1200+ 单元测试基线 |
| `docs/DATA_SOURCES.md` | ✅ **已全部修完** | 4 处漂移已清空，修复表格排版与体积矛盾 |
| `docs/THIRD_PARTY_NOTICES.md` | ✅ **已全部修完** | 2 处漂移已清空，注明脚本依赖与测试依赖 |
| `CHANGELOG.md` | ✅ **已全部修完** | 4 处漂移已清空，补齐特权截断、移除互斥草稿、收录最新成果 |
| `docs/user/` | 基础一致 | getting-started（4项权限已纠正）、guide（第二关必填/平坦倍率已纠正） |

### 3.2 剩余代码实体缺陷（需产品决策）

1. **`AlarmFragment.kt` 的「保存为默认设置」路径会静默回填**：用户显式保存"第二阶段无任务"会被替换成 4 种题型，
   与"不允许为空"的口径及 `alarm.md` §265「不得被默认值逻辑偷偷重新填回」冲突
2. **「闹钟防关机」开关是假按钮**（2026-09-11 二次验证，当前代码仍成立）

   `AlarmPowerOffProtectionManager.isEnabled()` 在**强制路径上零消费点**：全部 11 处引用都在 `RulesFragment.kt`
   （渲染开关 / 开启关闭流程 / 前置条件检查），另加 `AccessibilityDependencyManager.kt:18` 的写入侧清理。

   真正拦截关机菜单的是 `CoreService.kt:890` 的 `if (LockWindowManager.isAlarmOverlayShowing)` ——
   **只判断覆盖层在不在，完全不读开关值**。因此：开关开着，挑战期间关机菜单被 BACK 掉；
   **开关关掉，拦截行为完全不变**。

   **修法需产品决策**：(a) 让 `isEnabled()` 参与门禁，开关真正生效；或 (b) 去掉这个开关（它本就不可配置）。

   > 历史：用户曾在真机上验证过，当时观察到"关机菜单不再出现"——那是覆盖层置顶的效果，与开关无关。

### 3.3 假契约测试（后续治理建议）

- `LogEventMigrationInventoryTest`：✅ **已在本次会话彻底重构**，从硬编码行号字符串断言升级为符号存在性断言
- `AppUsageCalculationTest.kt:44-50`：24 小时截断断言自行 coerce 本地字面量，未调用生产代码
- `BusinessDayChangeTest.kt:200-239`：是测试内自写的镜像函数
- `ARCHITECTURE.md` 的分层规则：目前尚未编写 ArchUnit 或静态反射契约测试

### 3.4 未做的核对

About 页实际展示的入口与 `SPEC.md` §8.1 是否完全一致——只确认了 `about_section_title` 已是
「关于决定守护」，后续可逐条核对实际入口清单。


---

## 4. 关键环境事实与踩过的坑

1. **构建**：worktree 里没有 `local.properties`，需从主仓库复制：
   `cp ../IronWill-OpenSource/local.properties .`，然后 `./gradlew.bat --console=plain :app:testDebugUnitTest :app:assembleDebug`
   （当前基线 **1201 例 / 0 失败 / 11 跳过**）
2. **无线 adb 安装**：`adb install` 走无线会**永久挂起且装不上**，改用 `adb push` + `pm install -r -d`；
   **必须核对 `lastUpdateTime`**，否则不知道装没装上
3. **`adb logcat -f <path>` 被沙箱拒绝**（报 Read-only file system），必须用管道 `adb logcat | Out-File -Append`
4. **不要 `adb logcat -c`**：会冲掉关键证据
5. **反引号会截断 TS 模板字符串**：写脚本批量改文档时，用占位符替换反引号；同理注意全角逗号不能落在字符串外
6. **rebase 后必须复跑全量测试**：本会话 rebase 到 `fde3c93` 时，一处冲突解错
   （把 `fde3c93` 刻意删除的"无障碍连接时自动开启防卸载"代码加了回来），靠 `AntiUninstallStartupProtectionContractTest` 才抓出来
7. **git 自动合并不会发现语义冲突**：rebase 时双方各加了一条 `DECISION-26`，文本不重叠所以 git 不报冲突，需人工核对编号
8. **真机验证**：vivo V2425A；复现闹钟问题**必须开启无障碍**（拦截逻辑在 `CoreService.onAccessibilityEventInternal`，无障碍关闭时收不到事件）

---

## 5. 红线（必读 `AGENT_CORE.md`）

1. **不得 `adb uninstall`**，会清空用户真实规则与题库数据（§3.7）
2. **不得删除** `docs/history/issue_tracker.md` 中带真机根因的防御性逻辑（§3.5）。特别注意 **05-27 行**记录的"移除覆盖层以让用户使用相机"取舍
3. **不得把"前台检测/时间拦截/防卸载全部单点依赖无障碍"写成事实**（§4.1）
4. **不得把扫码写成"必须扫描指定/绑定二维码"**；不得把第二阶段间隔写成固定 3/5 分钟（§3.4）
5. **历史名称** `IronWill` / `ZhiZhi` / `知止` / `承诺保护系统` 只允许出现在 `HISTORY.md`、
   `docs/history/`、`docs/archive/` 与 Git 历史中。（`zhizhi_settings_backup` / `iron_will_*` 是**数据兼容标识**，不要动）
6. **不要创建** `SPEC_v2.md` / `PLAN_final.md` 之类平行真相文件；根目录**不维护总计划**
7. **不要碰** `IronWill-alarm-overlay`（另一个 AI 正在改）

---

## 6. 文件索引

| 文件 | 用途 |
| --- | --- |
| `docs/plans/2026-09-11-doc-drift-audit.md` | **漂移审计报告**（89 条，带证据）；focus/protection 两节为重审后版本 |
| `docs/plans/2026-09-11-doc-unification-handover.md` | 文档统一专项交接（更细，含逐文档清单） |
| `docs/plans/2026-09-11-alarm-whitescreen-handover.md` | 闹钟白屏专项交接（**那条线另有 agent 在做**） |
| `docs/product/system-overview.md` | 产品总览层（本会话新增） |
| `docs/user/` | 面向用户的三份文档 + README |
| `docs/README.md` | docs 入口与文档分层约定 |
| `AGENT_CORE.md` | AI 协作协议与业务红线（**必读**） |

### 日志文件（仓库外，未入库）

| 文件 | 内容 |
| --- | --- |
| `G:/IronWIl0329/.alarm-repro.log` / `.alarm-verify.log` / `.alarm-verify2.log` | 闹钟白屏的三次真机复现日志 |

### 备份标签

`backup/docs-product-refactor` —— rebase 前的原始 HEAD，出问题时可回退。
