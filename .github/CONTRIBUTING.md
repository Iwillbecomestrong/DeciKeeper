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
- Unix / macOS：`./gradlew`

## 3. 构建与验证（提交 PR 前）

常用构建命令：

```bash
# Windows
gradlew.bat :app:compileDebugKotlin
gradlew.bat :app:testDebugUnitTest
gradlew.bat :app:assembleDebug

# Linux / macOS
./gradlew :app:compileDebugKotlin
./gradlew :app:testDebugUnitTest
./gradlew :app:assembleDebug
```

Debug APK 生成路径：
```text
app/build/outputs/apk/debug/app-debug.apk
```

- **单测红线**：提交前必须确保全量单元测试（1300+）全部通过，不得新增单元测试失败或编译告警。
- **真机验证要求**：涉及锁机、闹钟唤醒、前台包名识别、无障碍、系统设置页拦截、OEM 保活行为的改动，必须在真机上验证，并在 PR 描述中记录设备型号（如 vivo OriginOS、小米 HyperOS 等）与复现步骤。
- **覆盖安装红线**：真机安装验证优先使用：
  ```bash
  adb install -r -d app/build/outputs/apk/debug/app-debug.apk
  ```
  **严禁为了普通调试随意 `adb uninstall`**，否则会清空本地全部规则、已用额度流水和真实配置数据。

## 4. 软件界面与文档源码对应表（在哪里看 / 在哪里改）

为了避免“看到 App 里的文案或页面，但不知道在仓库哪里维护”的困惑，以下是软件界面与源码的精准映射关系：

| 软件界面入口 | 驱动组件 | 对应文档 / 资源源码 | 打包与生效机制 |
| :--- | :--- | :--- | :--- |
| **设置 → 使用说明** | `HelpActivity.kt` | 1. [快速上手](../docs/user/getting-started.md)<br/>2. [功能指南](../docs/user/guide.md)<br/>3. [权限与隐私说明](../docs/user/permissions.md) | 构建时 Gradle 任务 `syncUserGuideAsset` 将这 3 份 Markdown 自动同步进 APK assets，App 内通过 Markwon 原生渲染（支持内部锚点平滑定位）。修改 Markdown 后需重新构建 APK 生效。 |
| **设置 → 关于我们** | `AboutActivity.kt`<br/>`LegalDocumentRepository.kt` | 1. 隐私及权限：`app/src/main/res/raw/privacy_notice.txt`<br/>2. 开源许可：`app/src/main/res/raw/open_source_licenses.txt`<br/>3. 反馈邮箱：`app/src/main/res/raw/feedback_email.txt`<br/>4. 题库来源：硬编码在 `AboutActivity.kt` | 编译期打包至 `res/raw`，点击列表项进入 `LegalDocumentActivity` 显示。 |
| **首屏隐私协议弹窗** | `PrivacyConsentActivity.kt` | 1. 用户协议：`app/src/main/res/raw/user_agreement.txt`<br/>2. 隐私政策：`app/src/main/res/raw/privacy_policy.txt` | 仅首屏首次启动或重置同意时弹出，不作为内测 About 页直接入口。 |
| **系统规则与底层契约** | 系统底层引擎 | 1. 总契约：[SPEC.md](../SPEC.md)<br/>2. 详细模块规格：[docs/specs/](../docs/specs/) | 面向开发者的架构、时钟判定契约与业务红线规范。 |

## 5. 命名与分层

### 5.1 包结构

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

### 5.2 类 / 对象命名

| 类型 | 后缀/约定 | 示例 |
|------|-----------|------|
| 业务管理器 | `Manager` | `CreditManager`、`TimeRuleManager` |
| 纯规则/分析 | 语义名 | `SegmentAdverseAnalysis`、`UsableCount` |
| 数据类 | 名词、小驼峰字段 | `TimeSegment`、`AlarmEntry` |
| UI | Activity / Fragment / Adapter / View | `RulesFragment`、`ThemeSummaryView` |
| 测试 | 被测对象/场景 + `Test` | `WeightedRestCountTest` |

### 5.3 函数命名

- 动词开头：`saveTimeSegments`、`applyPendingAppsToActive`
- 布尔判定：`is` / `should` / `can` / `has`
- 查询：`get` 前缀或清晰属性语义
- 事件回调：`on` 前缀

### 5.4 常量 / 资源

- 常量：`SCREAMING_SNAKE_CASE`
- SharedPreferences key：优先 `KEY_` 前缀
- 日志 tag：统一 `TAG`
- 布局：`activity_*.xml` / `fragment_*.xml` / `dialog_*.xml` / `item_*.xml`
- 控件 id：沿用 `tv_` / `btn_` / `et_` / `switch_` / `card_` / `list_` 等现有约定
- drawable：`bg_` / `ic_` 等语义前缀

### 5.5 测试命名

推荐：`场景_条件_期望`，例如：

- `detachFromGlobal_subset_isAdverse_delayed`
- `onlyIncrease_isFavorable_immediate`

### 5.6 Commit

```text
type(scope): 摘要

type: feat / fix / test / docs / chore / refactor
```

尽量保持原子提交；行为 Bug 优先按 RED → GREEN 流程提交可复现测试与实现。

## 6. 业务红线

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

## 7. PR 流程

1. 从最新 `dev` 新建任务分支，避免直接在 `dev` / `main` 上试错。
2. 先确认需求属于当前 SPEC、已确认修改，还是需要先更新 SPEC / PLAN。
3. 可测试的行为 Bug：先写失败测试（RED），再最小修复（GREEN），最后跑回归。
4. PR 描述写清：改了什么、为什么、影响模块、生效规则、怎么验证。
5. 如果 `dev` 在任务期间继续变化，先把最新 `dev` 同步到任务分支并处理冲突，再判断是否可合并。

## 8. 关键文件地图

| 想改… | 先读… |
|-------|-------|
| 勿扰时段 / 应用时间窗 / 生效策略 | [`../docs/specs/focus.md`](../docs/specs/focus.md)、`TimeRuleManager.kt`、`AppWindowManager.kt`、`SegmentAdverseAnalysis.kt` |
| 额度 / 休息 / 临时解锁 / 特权 | [`../docs/specs/focus.md`](../docs/specs/focus.md)、`CreditManager.kt`、`LockWindowManager.kt` |
| 闹钟 | [`../docs/specs/alarm.md`](../docs/specs/alarm.md)、`AlarmScheduler.kt`、`LockAlarmOverlay.kt` |
| 防卸载 / 无障碍 / 隐私空间 / 防关机 | [`../docs/specs/protection.md`](../docs/specs/protection.md)、`protection/`、`CoreService.kt` |
| 数据 / 备份 / business day | [`../docs/specs/data.md`](../docs/specs/data.md)、`SettingsBackupManager.kt`、`ConfigPendingManager.kt` |
| 已知坑与防回归 | [`../HISTORY.md`](../HISTORY.md)、[`../docs/history/issue_tracker.md`](../docs/history/issue_tracker.md) |
| 总产品规则 | [`../SPEC.md`](../SPEC.md) |

## 9. 完整项目文档地图

| 文档 | 用途 | 读者 |
| --- | --- | --- |
| [产品总览](../docs/product/system-overview.md) | 这是什么、为什么这样设计、模块之间什么关系 | 贡献者 / 架构师 |
| [SPEC.md](../SPEC.md) | 当前产品总规格与跨模块契约 | 开发 / 实现者 |
| [docs/specs/](../docs/specs/) | 各模块的具体规则怎么实现（focus / alarm / protection / data） | 模块维护者 |
| [docs/user/](../docs/user/) | 快速上手、完整功能、权限与隐私（驱动 App 内使用说明） | 最终用户 / QA |
| [docs/plans/](../docs/plans/) | 当前分任务计划（本项目不再维护根总计划） | 任务协同者 |
| [HISTORY.md](../HISTORY.md) | 关键历史决策与防回归经验 | 排查定位者 |
| [docs/archive/](../docs/archive/) | 已退役方案、旧命名与历史工作稿 | 历史溯源 |

## 10. 数据资产版权提醒

`assets/` 与题库数据的许可范围见 [`../docs/DATA_SOURCES.md`](../docs/DATA_SOURCES.md)。

- 不要提交来源不清或无再分发授权的商业题库；
- 不要把第三方付费 API / 翻译服务抓取文本直接打包进开源仓库；
- 新增可分发数据必须说明来源和许可。
