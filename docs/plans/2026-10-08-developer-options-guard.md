# 开发者选项拦截

> 状态：**规则已与用户确认，真机识别证据已取得；尚未实现**。本文不是产品真相；实现并真机验证后，规则回写 `docs/specs/protection.md` §13，决策写入 `HISTORY.md`，再把本文归档到 `docs/archive/plans/`。

## 1. 目标

用户冲动时最省力的绕过路径之一是：进「设置 → 开发者选项」打开 USB 调试或无线调试，再用电脑 `adb` 关闭无障碍、卸载或强停决定守护。该功能在无障碍可用时拦截「开发者选项」页面，使这条路径的即时绕过成本升高。

不承诺绝对防绕过（`protection.md` §9.1）：已经建立的 adb 连接、`adb shell` 改设置、安全模式、恢复出厂均不在本功能范围内。

## 2. 范围边界

**做**：
- 新增独立保护开关「开发者选项拦截」，依赖无障碍服务。
- 检测到前台是开发者选项页面时执行 BACK + HOME（与 `PermissionProtection.handleDetection` 同一动作）。
- 严立宽延三态：`DISABLED` / `ACTIVE` / `PENDING`，开启立即生效，关闭走严格次日延迟，基准为关或适应期内立即关闭。
- 无障碍不可用时立即关闭并清空 pending，跟随无障碍依赖清理链。

**不做（第一版）**：
- 不自动关闭已开启的 USB 调试 / 无线调试（需 `WRITE_SECURE_SETTINGS`，只能由用户 `adb shell pm grant` 授权，留作第二版可选增强）。
- 不处理「保护开启前已连上的 adb 会话」。
- 不识别无线调试子页、快捷设置「无线调试」磁贴、直接跳转 Intent 的独立落点（它们被开发者选项页拦截间接覆盖的部分除外；真机核验后再决定是否补）。
- 不拦「关于手机」连点版本号：开发者选项页被拦后它没有额外用处。
- 不处理安全模式、多用户 / 访客 / 应用分身、工程模式拨号码、恢复出厂。这些是已知盲区，只在规格 §13 的能力边界中记录。

## 3. 规则（已确认）

### 3.1 状态机
- **开启（有利）**：立即生效，清除旧 pending。
- **关闭（不利）**：默认走 `ConfigPendingManager` 的 `strictNextDay`；以下情形立即关闭：
  1. 处于适应期；
  2. baseline 中本项本来就是关闭的（恢复基准）。
- **PENDING 期间仍强制拦截**（`isEnforcing()` 为真）。
- 到期后下次读状态时落地为 `DISABLED`。
- 无障碍不可用：立即关闭、清空 pending。
- 开启前必须无障碍服务可用，否则 Toast 提示并不开启（与小窗可见性增强一致）。

### 3.2 识别
识别入口在无障碍窗口变化事件中，仅当 `isEnforcing()` 为真时执行。必须同时满足：

1. 包名属于设置类包（`com.android.settings` 及现有 `PermissionProtection.SETTINGS_PACKAGES`）；
2. 命中下列任一信号（文案比较时去掉所有空白字符，兼容「USB 调试」「USB调试」、「撤销」「撤消」）：
   - **标题信号**：页面**工具栏标题节点**的文字等于「开发者选项」或 `Developer options`；
   - **内容信号**：页面同时出现「USB调试」与「无线调试」两个条目；
   - **类名信号**：窗口类名含 `DevelopmentSettings`（AOSP 路径）。

**防误伤红线**：不得用「页面任意位置出现『开发者选项』文字」判定。设置首页 / 系统管理列表里有一行「开发者选项」入口，若按全文匹配会把其父页面也拦住，用户将无法使用设置的其他功能。

### 3.3 真机证据（vivo V2425A，2026-10-08 取得）

| 项 | 值 |
| --- | --- |
| 承载 Activity | `com.android.settings/com.vivo.settings.VivoSubSettings`，是通用二级设置容器，**不能单靠类名** |
| 标题节点 | `com.android.settings:id/originui_vtoolbar_normal_title_rom14_0`，文字「开发者选项」 |
| 列表项标题节点 | `com.android.settings:id/title`；已见「USB 调试」「无线调试」「撤消 USB 调试授权」「停用 adb 授权超时功能」 |
| 文案差异 | 真机为带空格的「USB 调试」「撤消 USB 调试授权」 |

未取得：无线调试子页的标题与类名（第一版不需要）。

### 3.4 与既有规则的关系
- 内容读取边界（`protection.md` §2.0、HISTORY DECISION-38）不扩大：设置类包本就在允许名单内，本功能不新增普通应用的内容读取。
- 取证不反向控制业务：诊断日志不得作为拦截判定输入（`protection.md` §9.6）。

## 4. 实现落点（计划，动手前需再核对）

| 项 | 做法 |
| --- | --- |
| 状态管理 | 新增 `DeveloperOptionsGuardManager`，照搬 `FloatingWindowProtectionManager` 的三态与 `ConfigPendingManager` 用法；prefs 独立命名 |
| 基准 | `TimeRuleManager` 新增 `getBaselineDeveloperOptionsGuardState`，并在基准快照写入处（与 `is_floating_window_protection_enabled` 同处，约 `TimeRuleManager.kt:2221`）一并写入 |
| 识别 | 纯函数 `DeveloperOptionsPageMatcher`（输入：包名、类名、标题文字、条目文字集合；输出：是否命中），可 JVM 测试；节点读取在 `ProtectionManager` / 新增 `DeveloperOptionsProtection` 中 |
| 拦截 | 新增 `DeveloperOptionsProtection`，在 `ProtectionManager.onAccessibilityEvent` 派发；动作参照 `PermissionProtection.handleDetection` |
| 无障碍依赖清理 | 接入 `AccessibilityDependencyManager` 的清理链，调用 `disableImmediatelyForAccessibilityUnavailable` 同款接口 |
| 设置页 | `RulesFragment` 加开关与状态文案；`docs/specs/protection.md` §12.7 的「四项增强」改为五项并同步 `RulesSettingsUiContractTest` |
| 诊断 | `ProtectionDiagnostics` 输出新管理器状态 |
| 文档 | `protection.md` §13、`docs/user/permissions.md`、`HISTORY.md` 决策、`.github/SECURITY.md` 第 54 行措辞 |

## 5. 验收标准

**JVM 测试**：
- 管理器：初始 `DISABLED`；开启后立即 `ACTIVE` 且 `isEnforcing`；基准为开启时关闭进入 `PENDING` 且仍强制；pending 到期落地 `DISABLED`；基准为关或适应期内关闭立即生效；无障碍不可用立即清空。
- 匹配器：标题带空格 / 不带空格命中；英文标题命中；设置首页含「开发者选项」入口行**不命中**；仅有「USB 调试」单项不命中内容信号；普通第三方包名不命中；`isEnforcing` 为假时不拦截。
- 设置页契约：开关位置、状态文案、无障碍关闭时不能开启。

**真机验证（vivo V2425A）**：
1. 开启后，从「设置 → 系统管理 → 开发者选项」进入会被退回桌面；
2. 设置搜索「USB 调试」「开发者选项」进入同样被拦；
3. 设置首页和系统管理页本身**不被误拦**；
4. 关闭保护（基准为空时）立即恢复访问；基准为开启时显示「待关闭（明日生效）」，期间仍拦；
5. 关闭无障碍后保护被立即清空，不残留 pending；
6. 快捷设置磁贴、直接跳转入口的表现记录在案。

## 6. 开发期约定

用户在稳定后才开启本功能。开发期自己的无线 adb 部署流程不受影响；若开启后需要进开发者选项，走基准为空的立即关闭，或次日生效。

## 7. 涉及文件（预估）

- 新增：`manager/DeveloperOptionsGuardManager.kt`、`protection/DeveloperOptionsPageMatcher.kt`、`protection/DeveloperOptionsProtection.kt`，及对应测试。
- 修改：`TimeRuleManager.kt`、`ProtectionManager.kt`、`RulesFragment.kt`、`ProtectionDiagnostics.kt`、`AccessibilityDependencyManager`、设置页布局与字符串。
- 文档：`docs/specs/protection.md`、`docs/user/permissions.md`、`HISTORY.md`、`.github/SECURITY.md`。
