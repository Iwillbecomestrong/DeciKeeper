# 贡献指南 (CONTRIBUTING)

感谢你关注 **决定守护（DeciKeeper）**。本文件是所有 Pull Request 的基础协作规范。

## 1. 项目是什么

决定守护是一款开源 Android 数字自律工具：用户提前定义时间边界、应用限制和起床流程，软件在需要执行时降低被下一刻冲动立刻推翻的概率。

完整产品规则先读：

- [`../SPEC.md`](../SPEC.md)
- [`../docs/specs/focus.md`](../docs/specs/focus.md)
- [`../docs/specs/alarm.md`](../docs/specs/alarm.md)
- [`../docs/specs/protection.md`](../docs/specs/protection.md)
- [`../docs/specs/data.md`](../docs/specs/data.md)

## 2. 开发环境

- JDK 17（以当前 Gradle 配置为准）
- Android SDK：以 `app/build.gradle.kts` 当前 `compileSdk` 为准
- Windows：`gradlew.bat`
- Unix：`./gradlew`

## 3. 构建与验证（提交 PR 前）

至少执行：

```bash
gradlew :app:compileDebugKotlin
gradlew :app:testDebugUnitTest
gradlew :app:assembleDebug
```

- 不得新增单元测试失败或编译失败。
- 涉及锁机、闹钟、前台识别、无障碍、系统设置页、OEM 行为的改动，还应在真机上验证，并在 PR 描述中记录设备与步骤。
- 安装验证优先使用 `adb install -r -d` 覆盖安装，**不要为了调试随意 `adb uninstall`**，避免清空真实用户数据。

## 4. 命名与分层

### 4.1 包结构

当前官方包名为：

```text
com.decikeeper.app
├── manager/     # 业务规则对象与 UseCase
├── ui/          # Activity / Fragment / View / Adapter
├── protection/  # 无障碍、防卸载、系统入口保护
├── data/        # 数据模型、持久化与日志数据结构
├── service/     # Android Service 容器
└── ...
```

原则：

- 新业务判定优先放到 Manager / 纯 Kotlin seam，不继续堆进 Fragment / Activity 生命周期。
- UI 负责展示状态和发出意图，不复制额度、锁策略、pending 判定。
- 诊断模块只能提供证据，不能反向成为业务放行/拦截的真相源。

### 4.2 类 / 对象命名

| 类型 | 后缀/约定 | 示例 |
|------|-----------|------|
| 业务管理器 | `Manager` | `CreditManager`、`TimeRuleManager` |
| 纯规则/分析 | 语义名 | `SegmentAdverseAnalysis`、`UsableCount` |
| 数据类 | 名词、小驼峰字段 | `TimeSegment`、`AlarmEntry` |
| UI | Activity / Fragment / Adapter / View | `RulesFragment`、`ThemeSummaryView` |
| 测试 | 被测对象/场景 + `Test` | `WeightedRestCountTest` |

### 4.3 函数命名

- 动词开头：`saveTimeSegments`、`applyPendingAppsToActive`
- 布尔判定：`is` / `should` / `can` / `has`
- 查询：`get` 前缀或清晰属性语义
- 事件回调：`on` 前缀

### 4.4 常量 / 资源

- 常量：`SCREAMING_SNAKE_CASE`
- SharedPreferences key：优先 `KEY_` 前缀
- 日志 tag：统一 `TAG`
- 布局：`activity_*.xml` / `fragment_*.xml` / `dialog_*.xml` / `item_*.xml`
- 控件 id：沿用 `tv_` / `btn_` / `et_` / `switch_` / `card_` / `list_` 等现有约定
- drawable：`bg_` / `ic_` 等语义前缀

### 4.5 测试命名

推荐：`场景_条件_期望`，例如：

- `detachFromGlobal_subset_isAdverse_delayed`
- `onlyIncrease_isFavorable_immediate`

### 4.6 Commit

```text
type(scope): 摘要

type: feat / fix / test / docs / chore / refactor
```

尽量保持原子提交；行为 Bug 优先按 RED → GREEN 流程提交可复现测试与实现。

## 5. 业务红线

1. **先读规格再改行为**：修改勿扰、时间窗、额度、出口、闹钟或保护前，先读根 SPEC 和对应 scoped spec。
2. **正常保护期的主原则**：大多数受保护设置遵循 **加严立即、放宽延期**；但适应期、Fresh Segment、恢复 baseline、闹钟防关机即时开关等明确例外必须按对应模块规格处理，禁止把一句口号机械覆盖所有代码。
3. **时间规则策略只控制两把锁**：「宽严有别 / 全部次日」仅作用于勿扰时段和应用时间窗，不能扩展到黑白名单、全局参数或加强约束。
4. **临时解锁状态计费不能简化**：仅在勿扰空档或已经处于休息时不重复消耗休息额度；其他状态按当前额度/冷却/借用规则处理。
5. **休息不解除应用时间窗**；被 App Window 管理的应用需要同时满足两把锁的普通放行条件。
6. **用户侧术语**：统一使用「休息 / 临时解锁 / 每周特权」，不要新增“超级临时”文案。
7. **QR 规则**：闹钟扫码当前是“任意二维码即可通过”，不要擅自改成绑定/指定 QR。
8. **防御性代码不得凭感觉删除**：`docs/history/issue_tracker.md` 中有真实 OEM / 真机原因的防回归逻辑，修改前先理解证据链。
9. **部署红线**：避免 `adb uninstall` 清空用户数据。
10. **不隐藏失败**：PR 描述附真实测试/构建状态，真机未验证时明确写“未真机验证”。

## 6. PR 流程

1. 从最新 `dev` 新建任务分支，避免直接在 `dev` / `main` 上试错。
2. 先确认需求属于当前 SPEC、已确认修改，还是需要先更新 SPEC / PLAN。
3. 可测试的行为 Bug：先写失败测试（RED），再最小修复（GREEN），最后跑回归。
4. PR 描述写清：改了什么、为什么、影响模块、生效规则、怎么验证。
5. 如果 `dev` 在任务期间继续变化，先把最新 `dev` 同步到任务分支并处理冲突，再判断是否可合并。

## 7. 关键文件地图

| 想改… | 先读… |
|-------|-------|
| 勿扰时段 / 应用时间窗 / 生效策略 | [`../docs/specs/focus.md`](../docs/specs/focus.md)、`TimeRuleManager.kt`、`AppWindowManager.kt`、`SegmentAdverseAnalysis.kt` |
| 额度 / 休息 / 临时解锁 / 特权 | [`../docs/specs/focus.md`](../docs/specs/focus.md)、`CreditManager.kt`、`LockWindowManager.kt` |
| 闹钟 | [`../docs/specs/alarm.md`](../docs/specs/alarm.md)、`AlarmScheduler.kt`、`LockAlarmOverlay.kt` |
| 防卸载 / 无障碍 / 隐私空间 / 防关机 | [`../docs/specs/protection.md`](../docs/specs/protection.md)、`protection/`、`CoreService.kt` |
| 数据 / 备份 / business day | [`../docs/specs/data.md`](../docs/specs/data.md)、`SettingsBackupManager.kt`、`ConfigPendingManager.kt` |
| 已知坑与防回归 | [`../HISTORY.md`](../HISTORY.md)、[`../docs/history/issue_tracker.md`](../docs/history/issue_tracker.md) |
| 总产品规则 | [`../SPEC.md`](../SPEC.md) |

## 8. 数据资产版权提醒

`assets/` 与题库数据的许可范围见 [`../docs/DATA_SOURCES.md`](../docs/DATA_SOURCES.md)。

- 不要提交来源不清或无再分发授权的商业题库；
- 不要把第三方付费 API / 翻译服务抓取文本直接打包进开源仓库；
- 新增可分发数据必须说明来源和许可。
