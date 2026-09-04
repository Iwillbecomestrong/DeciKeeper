# 贡献指南 (CONTRIBUTING)

感谢你对 IronWill 的关注！本文件是**所有 Pull Request 必须遵守的规范**。

## 1. 项目是什么

IronWill 是一款 Android 自律工具：用户预设勿扰时段与黑名单，到点自动锁机，必须消耗休息额度或完成任务才能解锁。核心逻辑集中在少量 Manager 对象中（见 docs/ARCHITECTURE.md）。

## 2. 开发环境

- JDK 17（与 Gradle 要求一致，见 gradle/wrapper/gradle-wrapper.properties）
- Android SDK：compileSdk 36（见 app/build.gradle.kts）
- 构建：Windows 下 `gradlew.bat`，Unix 下 `./gradlew`（无需 Android Studio）

## 3. 构建与验证（提交 PR 前必须全部通过）

```bash
gradlew :app:compileDebugKotlin
gradlew :app:testDebugUnitTest
gradlew :app:assembleDebug
```

- 全量单测目前约 700+ 个；存在少量**pre-existing 失败**（已在 Issue_Tracker 记录），你的 PR 不得新增失败。
- 涉及锁机/闹钟/前台识别/权限的改动，还必须在**真机**上验证，并在 PR 描述里写明设备型号与验证步骤。
- 安装验证只用 `adb install -r -d`，**禁止 `adb uninstall`**（会抹掉用户数据）。

## 4. 命名规范（强制）

### 4.1 包结构（按层分包，禁止跨层 import）

```text
com.ironwill.app
├── manager/     # 业务规则对象（object Manager）与 UseCase
├── ui/          # Activity / Fragment / Adapter / 布局逻辑
├── protection/  # 无障碍防护、防卸载
├── data/        # 持久化、序列化
└── diag/        # 日志、诊断、自检（不影响业务行为）
```

- UI 类不得包含业务判定；业务判定只放 manager/ 下的对象，并在 UI 中调用。
- 新增预发布清理/重构时，逐步把现有散落文件迁入对应包（见 docs/ARCHITECTURE.md 迁移表）。

### 4.2 类 / 对象命名

| 类型 | 后缀/约定 | 示例 |
|------|-----------|------|
| 业务管理器（单例对象） | 后缀 `Manager` | `CreditManager`、`TimeRuleManager` |
| 纯函数工具对象 | 直接命名或 `Utils` | `SegmentBlacklist`、`UsableCount` |
| 数据类 | 名词，字段小驼峰 | `TimeSegment`、`AnalysisResult` |
| UI 类 | 后缀 Activity / Fragment / Adapter | `RulesFragment`、`TimeSegmentAdapter` |
| 测试类 | 被侧类名 + `Test` | `SegmentBillingTest` |

### 4.3 函数命名

- 动词开头：`saveTimeSegments`、`applyPendingAppsToActive`
- 布尔判定用 `is` / `should` / `can` / `has` 前缀：`shouldBlockApp`、`isInAdaptationPeriod`
- 查询用 `get` 前缀（对象属性访问器可用属性语法）
- 事件回调用 `on` 前缀：`onSelectAll`、`onServiceConnected`

### 4.4 常量 / 资源命名

- 常量：`SCREAMING_SNAKE_CASE`；SharedPreferences key 用 `KEY_` 前缀（`KEY_SEGMENTS_JSON`）；日志 TAG 用 `TAG`。
- 布局：`activity_*.xml` / `fragment_*.xml` / `dialog_*.xml` / `item_*.xml`；控件 id 前缀 `tv_` / `btn_` / `et_` / `switch_` / `card_` / `list_`。
- drawable：`bg_`（背景）、`ic_`（图标）。

### 4.5 测试命名

`方法或场景_条件_期望`，例如：`detachFromGlobal_subset_isAdverse_delayed`、`onlyIncrease_isFavorable_immediate`。

### 4.6 Commit 规范

```text
type(scope): 中文摘要（一行，≤ 72 字符）

type: feat / fix / test / docs / chore / refactor
scope: 模块名，如 segment-blacklist、alarm、anti-uninstall
```

原子提交：一个 commit 只做一件事；RED 测试与实现分开提交。

## 5. 业务红线（违反即拒 PR）

1. **加严立即生效，放松次日生效**：任何放宽限制的改动必须接入现有延迟生效体系（AdverseAnalysis / ConfigPendingManager），不得绕过。
2. **业务逻辑放 Manager/UseCase**，禁止散落在 Activity/Fragment 生命周期回调。
3. **防御性代码不得删除**：Issue_Tracker 中标注的防回归代码，未理解原因前禁止删除/弱化/重写。
4. **前台检测取证契约**：诊断投票/日志不得用于改变黑名单规则、放行行为或锁屏触发。
5. **部署红线**：只 `adb install -r -d`，禁止 `adb uninstall`。
6. 守规路径按钮绿/蓝居左，逃避路径按钮红/黄居右。
7. 不隐藏失败；PR 必须附上真实命令输出与测试结果。

## 6. PR 流程

1. Fork 本仓库，从 `dev` 建功能分支。
2. 先写失败测试复现问题（RED），再最小修复（GREEN），最后跑相关回归。
3. 行为变化类 PR 必须说明：影响模块、生效策略（加严/放松）、真机验证记录。
4. 描述里写清"改了什么/为什么/怎么验证"；中文或英文均可，术语与本文档一致。
5. 维护者会按第 4/5 节规范审查；不合规的 PR 会被要求修改后再审。

## 7. 关键文件地图（改什么先看什么）

| 想改… | 先读… |
|-------|-------|
| 勿扰时段/名单/生效策略 | TimeRuleManager.kt、SegmentBlacklist.kt、AdverseAnalysis.kt |
| 黑名单/额度/特权 | CreditManager.kt |
| 锁屏判定/临时解锁 | MonitorService.kt、LockWindowManager.kt |
| 闹钟 | AlarmScheduler.kt、AlarmChallengeManager.kt |
| 防卸载/无障碍 | protection/ 目录、CoreService.kt |
| 已知坑与防回归 | [HISTORY.md](file:///g:/IronWIl0329/IronWill-OpenSource/HISTORY.md) · [docs/history/issue_tracker.md](file:///g:/IronWIl0329/IronWill-OpenSource/docs/history/issue_tracker.md) |
| 需求与规则 | [SPEC.md](file:///g:/IronWIl0329/IronWill-OpenSource/SPEC.md) · [docs/specs/requirements.md](file:///g:/IronWIl0329/IronWill-OpenSource/docs/specs/requirements.md) |

## 8. 数据资产版权提醒

assets/ 下的词库与题目数据存在版权来源问题（见 docs/DATA_SOURCES.md）。**PR 不得新增来自商业出版物、付费 API 或有道/Google 翻译服务抓取的内容**；新增数据必须注明来源与授权。
