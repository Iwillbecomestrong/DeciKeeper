# IronWill Codebase Overview

> 使用约定：AI 与开发者在修改代码前，先阅读本索引文档。

## 1) 分层结构（按功能细分）

### UI

- Activities: `MainActivity`, `AppSelectionActivity`, `GlobalParamsActivity`, `SchedulePreviewActivity`, `AppWindowPreviewActivity`, `HelpActivity`
- Fragments: `HomeFragment`, `RulesFragment`, `StatisticsFragment`, `TimeConfigFragment`, `AppWindowFragment`
- Adapters: `TimeSegmentAdapter`, `AppListAdapter`, `AppSelectionAdapter`, `AppWindowAdapter`
- Views: `TypewriterTextView`

### Domain (Managers / Services / Receivers)

- Managers: `CreditManager`, `TimeRuleManager`, `AppWindowManager`, `AntiUninstallManager`, `PrivacyProtectionManager`, `PermissionManager`, `ProbationManager`, `ReminderManager`, `ChangeLogManager`, `ConfigPendingManager`
- Services: `CoreService`, `MonitorService`, `FastLockService`, `A11yGuardService`, `LockWindowManager` (UI Overlay Service Helper)
- Receivers: `BootReceiver`, `DeviceAdmin`

### Protection 子域（Domain::Protection）

- `protection/ProtectionManager`
- `protection/AccessibilityProtection`
- `protection/AntiUninstallProtection`
- `protection/PermissionProtection`

### Data

- `CrashRecorder`
- `LogExporter`

### Test

> TestScenarioManager / TestScenarioRunner 已迁入 app/src/test 源集（不在 APK 内）。
> 自检套件请在开发环境通过单元测试运行，见 CONTRIBUTING.md。

## 2) 统一文件头规范

所有 Kotlin/Java 文件必须包含标准 KDoc 头（Context Header），用于说明角色、职责与约束。

## 3) 变更同步要求

- 新增/移动文件：必须更新本索引。
- 逻辑调整：若涉及用户可感知行为，需同步 `Requirements Document.md` 与 `HelpActivity` 文案。
