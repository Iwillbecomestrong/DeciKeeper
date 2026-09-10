# 加强约束设置页与前置权限 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将设置页的无障碍前置项、四项独立加强功能、闹钟锁屏显示引导和防卸载初始化界面门禁收敛为与已确认规格一致的可验证实现。

**Architecture:** 保留现有 `RulesFragment → Manager → Android service/data` 依赖方向。设置页只负责展示状态、发出开关意图和启动系统设置；前置条件、`ACTIVE/PENDING/DISABLED` 语义和初始化界面门禁由现有 Manager/Service 以及纯 Kotlin 判定承担。优先复用当前状态机和权限检查，不新增重复的持久化开关。

**Tech Stack:** Kotlin、Android View/XML、Material Components、SharedPreferences、Robolectric/JVM 单元测试、Gradle Debug 构建。

**Spec:** `docs/specs/protection.md` 第 11 节“设置页与前置权限交互”

**Status:** 已完成代码实现与 JVM/源码契约验证；未自动提交 Git，待用户确认后再决定是否分阶段提交。真机跨品牌验证仍待执行。

## Global Constraints

- 设置页仍是底部五项导航中的第五项，必须保留主题、黑白名单、全局参数、导出配置、导入配置和关于我们等现有入口。
- “无障碍服务”是系统权限前置项，不属于“加强约束功能”中的一个增强开关。
- 四项增强按“小窗可见性识别与防关权限保护、隐私空间保护、防卸载增强、闹钟防关机保护”的顺序展示，并分别可选。
- 防卸载在无障碍服务未就绪时不能开启；关闭无障碍不能绕过防卸载关闭顺序。
- 闹钟防关机保护开启和关闭均立即生效；缺少无障碍、悬浮窗、通知或锁屏显示/全屏通知前置条件时，开关必须保持关闭。
- 防卸载 `PENDING`（“待关闭，明日生效”）期间仍继续保护，且防卸载初始化界面仍允许显示；只有 `DISABLED` 才禁止显示。
- 不新增“查看日志”；“导出日志”和“隐藏决定守护在多任务界面”必须保持独立区块。
- 面向用户的文案只使用“小窗”，不使用“ROM 小窗”或“PIP”。
- 不承诺所有 Android/ROM 绝对不可绕过；系统设置页、无障碍、小窗、隐私空间、电源菜单相关修改必须保留真机验证。
- 当前工作树已有用户未提交修改，执行前记录基线并只修改本计划列出的文件，不使用 `git reset --hard`、`git clean` 或覆盖无关修改。

---

### Task 1: 固化保护设置的纯状态判定与防卸载初始化门禁

**Files:**
- Modify: `app/src/main/java/com/decikeeper/app/manager/AntiUninstallManager.kt`
- Modify: `app/src/main/java/com/decikeeper/app/ui/RulesFragment.kt:71-193, 488-900, 2027-2165`
- Test: `app/src/test/java/com/decikeeper/app/A11yProtectionDependencyRulesTest.kt`
- Test: `app/src/test/java/com/decikeeper/app/AntiUninstallInitializationGateTest.kt` (create)

**Interfaces:**
- Consumes: `AntiUninstallManager.State`、`AccessibilityControlManager.State`、现有 `RulesFragment.isA11yServiceReady()` 和协同关闭处理。
- Produces: `AntiUninstallManager.shouldShowInitialization(state: State): Boolean`，供服务和 UI 共同使用。

- [x] **Step 1: Write the failing tests**

```kotlin
@Test
fun initializationAllowedOnlyWhenAntiUninstallIsActiveOrPending() {
    assertTrue(AntiUninstallManager.shouldShowInitialization(AntiUninstallManager.State.ACTIVE))
    assertTrue(AntiUninstallManager.shouldShowInitialization(AntiUninstallManager.State.PENDING))
    assertFalse(AntiUninstallManager.shouldShowInitialization(AntiUninstallManager.State.DISABLED))
}
```

扩展 `A11yProtectionDependencyRulesTest`：无障碍关闭或处于待关闭时，依赖功能不能开启；无障碍正常开启时，防卸载仍可单独开关。

- [x] **Step 2: Run the focused tests and verify the new gate fails**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.decikeeper.app.A11yProtectionDependencyRulesTest" --tests "com.decikeeper.app.AntiUninstallInitializationGateTest"
```

Expected: 新增 helper 尚不存在或断言失败。

- [x] **Step 3: Implement the minimal state helper**

在 `AntiUninstallManager` 中加入：

```kotlin
internal fun shouldShowInitialization(state: State): Boolean =
    state == State.ACTIVE || state == State.PENDING
```

设置页继续从 Manager 读取状态；`PENDING` 时开关显示未选中、状态显示“待关闭（明日生效）”，但执行语义保持保护中。

- [x] **Step 4: Run the focused tests again**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.decikeeper.app.A11yProtectionDependencyRulesTest" --tests "com.decikeeper.app.AntiUninstallInitializationGateTest"
git add app/src/main/java/com/decikeeper/app/manager/AntiUninstallManager.kt app/src/main/java/com/decikeeper/app/ui/RulesFragment.kt app/src/test/java/com/decikeeper/app/A11yProtectionDependencyRulesTest.kt app/src/test/java/com/decikeeper/app/AntiUninstallInitializationGateTest.kt
# 本次不自动提交；待用户确认后再按阶段提交
```

Expected: 测试通过。

### Task 2: 重排设置页 UI 与行级帮助入口

**Files:**
- Modify: `app/src/main/res/layout/fragment_rules.xml:96-185`
- Modify: `app/src/main/java/com/decikeeper/app/ui/RulesFragment.kt:291-353, 2027-2320`
- Modify: `app/src/main/res/values/strings.xml`
- Test: `app/src/test/java/com/decikeeper/app/RulesSettingsUiContractTest.kt` (create)

**Interfaces:**
- Consumes: Task 1 的状态 helper、现有 view ID 和 Manager API。
- Produces: 设置页第五导航页的批准信息架构、问号帮助和独立开关。

- [x] **Step 1: Write the failing layout contract test**

读取 `fragment_rules.xml`，断言无障碍服务出现在加强约束功能之前，四个名称顺序正确，不含“查看日志”，并断言每个保护行的帮助控件位于对应 Switch 前；同时断言多任务隐藏和导出日志不在同一个 CardView。

- [x] **Step 2: Run the contract test**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.decikeeper.app.RulesSettingsUiContractTest"
```

Expected: 当前布局因旧名称、旧分组和帮助控件不足而失败。

- [x] **Step 3: Update `fragment_rules.xml`**

1. 保留主题、黑白名单、全局参数、导出/导入配置和关于我们。
2. 将 `switch_accessibility_service` 独立成加强约束卡片之前的前置服务卡。
3. 加强约束卡只保留以下四行，顺序不可变：
   - 小窗可见性识别与防关权限保护（复用 `switch_accessibility` 的既有业务状态）
   - 隐私空间保护
   - 防卸载增强
   - 闹钟防关机保护
4. 每行在 Switch 左侧放置可访问、触控区域至少 44dp 的 `?` 控件，并使用现有主题 Token。
5. 将多任务隐藏和导出日志拆成两个独立卡片；不创建查看日志。

- [x] **Step 4: Add help wiring and exact copy**

在 `RulesFragment` 中集中注册帮助点击并复用一个本地说明弹窗。文案必须覆盖：

```text
无障碍服务：这是下方增强功能的前置服务；关闭时需先处理防卸载关闭流程。
小窗可见性识别与防关权限保护：增强小窗可见性识别，并保护已识别的关键权限页面。
隐私空间保护：部分设备存在独立空间添加应用入口，此项是兼容性保护，不覆盖所有品牌。
防卸载增强：降低直接卸载、强行停止或关闭关键服务的即时绕过概率；语言切换或服务重连可能短暂初始化/预热。
闹钟防关机保护：仅闹钟挑战期间工作，需要无障碍、悬浮窗、通知和锁屏显示/全屏通知。
```

文案写入 `strings.xml`，不在 UI 代码中保留另一套冲突文案。

- [x] **Step 5: Preserve independent switch semantics and verify**

保留四个独立 listener。组合名称只代表现有无障碍权限保护与小窗识别能力的产品归类，不新增第二个小窗偏好，也不改变 `VisibleBlacklistPresenceTracker` 的会话语义。无障碍关闭仍使用现有协同关闭弹窗，先提交防卸载关闭申请，再提交无障碍待关闭。

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.decikeeper.app.RulesSettingsUiContractTest" --tests "com.decikeeper.app.A11yProtectionDependencyRulesTest" --tests "com.decikeeper.app.PrivacySpaceGuardRulesTest"
git add app/src/main/res/layout/fragment_rules.xml app/src/main/java/com/decikeeper/app/ui/RulesFragment.kt app/src/main/res/values/strings.xml app/src/test/java/com/decikeeper/app/RulesSettingsUiContractTest.kt
# 本次不自动提交；待用户确认后再按阶段提交
```

Expected: 所选测试通过。

### Task 3: 完整实现闹钟防关机的前置权限检查与锁屏显示引导

**Files:**
- Modify: `app/src/main/java/com/decikeeper/app/manager/AlarmPowerOffProtectionManager.kt:29-90`
- Modify: `app/src/main/java/com/decikeeper/app/ui/RulesFragment.kt:1170-1350, 2144-2165`
- Modify: `app/src/main/res/values/strings.xml`
- Test: `app/src/test/java/com/decikeeper/app/AlarmPowerOffProtectionManagerTest.kt` (create)
- Test: `app/src/test/java/com/decikeeper/app/AlarmOverlayPermissionFlowContractTest.kt`

**Interfaces:**
- Consumes: 无障碍、悬浮窗、通知权限/通知总开关和 Android 14 全屏通知状态。
- Produces: 一个完整、可测试的 prerequisite snapshot；只有全部满足才能持久化 `enabled=true`。

- [x] **Step 1: Write failing tests**

测试缺少锁屏显示/全屏通知、缺少悬浮窗或缺少通知时 `allReady == false`，并要求缺失列表含对应用户文案；源码合同测试要求 `canEnable(context)` 发生在 `setEnabled(context, true)` 之前。

- [x] **Step 2: Run the tests before implementation**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.decikeeper.app.AlarmPowerOffProtectionManagerTest" --tests "com.decikeeper.app.AlarmOverlayPermissionFlowContractTest"
```

Expected: 当前 UI 的本地状态只检查无障碍和锁屏显示，暴露出可能绕过 Manager 完整检查的缺口。

- [x] **Step 3: Make the Manager the single prerequisite source**

保留现有 `getMissingPrerequisites(context)` 兼容 API，并让它由以下快照派生：

```kotlin
data class PrerequisiteStatus(
    val a11yReady: Boolean,
    val overlayReady: Boolean,
    val notificationPermissionReady: Boolean,
    val notificationsEnabled: Boolean,
    val fullScreenIntentReady: Boolean
) {
    val allReady: Boolean
        get() = a11yReady && overlayReady && notificationPermissionReady &&
            notificationsEnabled && fullScreenIntentReady
}
```

“锁屏显示/全屏通知”作为用户文案；不改变现有 Android 版本判断。

- [x] **Step 4: Make the switch transactional and verify**

开启时先检查无障碍，再读取完整快照；不完整时开关恢复关闭并打开引导。引导提供“去开启锁屏显示”，跳转 `Settings.ACTION_APP_NOTIFICATION_SETTINGS`；返回设置页后重新检查。只有 `canEnable(context) == true` 才调用 `setEnabled(context, true)`；关闭保持立即生效。

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.decikeeper.app.AlarmPowerOffProtectionManagerTest" --tests "com.decikeeper.app.AlarmOverlayPermissionFlowContractTest" --tests "com.decikeeper.app.AlarmOverlayRequirementTest"
git add app/src/main/java/com/decikeeper/app/manager/AlarmPowerOffProtectionManager.kt app/src/main/java/com/decikeeper/app/ui/RulesFragment.kt app/src/main/res/values/strings.xml app/src/test/java/com/decikeeper/app/AlarmPowerOffProtectionManagerTest.kt app/src/test/java/com/decikeeper/app/AlarmOverlayPermissionFlowContractTest.kt
# 本次不自动提交；待用户确认后再按阶段提交
```

Expected: 缺少锁屏显示时绝不显示为已开启。

### Task 4: 将防卸载初始化/预热界面严格绑定到防卸载状态

**Files:**
- Modify: `app/src/main/java/com/decikeeper/app/service/CoreService.kt:768-825`
- Modify: `app/src/main/java/com/decikeeper/app/service/FastLockService.kt:468-620`
- Modify: `app/src/main/java/com/decikeeper/app/manager/StartupProtectionManager.kt:65-125`
- Modify: `app/src/main/java/com/decikeeper/app/ui/LockWindowManager.kt:1131-1225, 6529-6545`
- Modify: `app/src/main/java/com/decikeeper/app/ui/LockAlarmOverlay.kt:3270-3290`
- Test: `app/src/test/java/com/decikeeper/app/AntiUninstallInitializationGateTest.kt`
- Test: `app/src/test/java/com/decikeeper/app/ConfigChangeProtectionContractTest.kt` (create)

**Interfaces:**
- Consumes: Task 1 的 `shouldShowInitialization`、防卸载状态、服务生命周期和现有 FastLock/overlay API。
- Produces: `ACTIVE/PENDING` 允许初始化/预热，`DISABLED` 从所有相关入口禁止初始化界面。

- [x] **Step 1: Write failing call-chain contracts**

对语言切换和闹钟结束路径做源码合同，要求在显示或延长初始化保护前查询防卸载状态；加入 `ACTIVE/PENDING/DISABLED` 真值表测试。

- [x] **Step 2: Run the contracts**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.decikeeper.app.AntiUninstallInitializationGateTest" --tests "com.decikeeper.app.ConfigChangeProtectionContractTest"
```

Expected: 当前配置变化和闹钟结束路径可能仅依据无障碍/预热状态启动初始化保护。

- [x] **Step 3: Gate the locale-change path**

在 `CoreService.onConfigurationChanged` 保留诊断日志和 `ProtectionManager.resetCache(this)`，但在 `startFastLock`、无悬浮窗 fallback 或初始化覆盖层之前加入：

```kotlin
if (!AntiUninstallManager.shouldShowInitialization(
        AntiUninstallManager.getProtectionState(this)
    )
) {
    ProtectionManager.resetCache(this)
    return
}
```

- [x] **Step 4: Gate all physical surfaces and verify**

在 `LockWindowManager.showSystemInitOverlay` 增加最终门禁；闹钟结束桥接额外要求防卸载为 `ACTIVE/PENDING`；`FastLockService.shouldKeepStartupProtection` 同时检查该状态，避免已关闭功能继续延长已有窗口。保留服务重连代际、PENDING 继续保护和现有安全 fallback。

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.decikeeper.app.AntiUninstallInitializationGateTest" --tests "com.decikeeper.app.ConfigChangeProtectionContractTest" --tests "com.decikeeper.app.A11yProtectionDependencyRulesTest" --tests "com.decikeeper.app.AntiUninstallForceStopDialogTest"
git add app/src/main/java/com/decikeeper/app/service/CoreService.kt app/src/main/java/com/decikeeper/app/service/FastLockService.kt app/src/main/java/com/decikeeper/app/ui/LockWindowManager.kt app/src/main/java/com/decikeeper/app/ui/LockAlarmOverlay.kt app/src/test/java/com/decikeeper/app/AntiUninstallInitializationGateTest.kt app/src/test/java/com/decikeeper/app/ConfigChangeProtectionContractTest.kt
# 本次不自动提交；待用户确认后再按阶段提交
```

Expected: DISABLED 不显示，ACTIVE/PENDING 仍显示。

### Task 5: 全量收敛、主题与真机验收

**Files:**
- Verify: Tasks 1–4 的所有变更
- Verify: `docs/specs/protection.md`、`SPEC.md`、`PLAN.md`、`HISTORY.md` 是否产生漂移

- [x] **Step 1: Run the focused suite**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.decikeeper.app.A11yProtectionDependencyRulesTest" --tests "com.decikeeper.app.PrivacySpaceGuardRulesTest" --tests "com.decikeeper.app.PrivacySpaceGuardEnforcementTest" --tests "com.decikeeper.app.AntiUninstallForceStopDialogTest" --tests "com.decikeeper.app.AlarmPowerOffProtectionManagerTest" --tests "com.decikeeper.app.AlarmOverlayPermissionFlowContractTest" --tests "com.decikeeper.app.AlarmOverlayRequirementTest" --tests "com.decikeeper.app.RulesSettingsUiContractTest" --tests "com.decikeeper.app.AntiUninstallInitializationGateTest" --tests "com.decikeeper.app.ConfigChangeProtectionContractTest"
```

- [x] **Step 2: Build Debug**

```powershell
.\gradlew.bat :app:assembleDebug
```

Expected: 生成 `app/build/outputs/apk/debug/app-debug.apk`。

- [x] **Step 3: Review the settings UI**

检查第五导航页：原有入口完整、无障碍独立置顶、四项增强顺序正确、问号在开关左侧且可点击、长中文不裁切、日志与多任务隐藏分卡、无“查看日志”、四季主题 Token 正常联动。

- [ ] **Step 4: Perform targeted device verification**

验证无障碍关闭时所有依赖开关、锁屏显示缺失时闹钟保护、防卸载关闭顺序、PENDING 初始化界面，以及 DISABLED 时语言切换/闹钟结束/其他增强不触发初始化界面。至少在当前测试 ROM 上记录结果；不同品牌差异只作为兼容性证据，不扩大产品承诺。

- [x] **Step 5: Review final diff**

```powershell
git diff --check
git status --short
git diff --stat
```

保留无关脏文件，不清理、不覆盖、不自动提交用户已有修改。

## Self-review checklist

- Spec coverage: Tasks 1–4 覆盖 UI 层级、独立开关、无障碍依赖/关闭顺序、锁屏显示引导、帮助弹窗、日志分区和初始化门禁。
- No placeholders: 每个任务均指定文件、状态、测试、命令和预期结果。
- Type consistency: 跨任务接口只有 `AntiUninstallManager.shouldShowInitialization(state: State): Boolean`；闹钟 prerequisite snapshot 在 Task 3 创建并由 `RulesFragment` 消费。
- Scope control: 不新增查看日志、隐私空间普适性承诺、Device Admin 方案或独立小窗偏好，不触碰无关统计/时间规则。
