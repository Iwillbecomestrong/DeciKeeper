# 2026-09-30 无障碍服务防关前置与生命周期解耦计划

> **Local README Gate**：本文件记录「无障碍服务防关前置与生命周期解耦」的执行路线与验收证据。

---

## 1. 目标与背景

### 背景痛点
1. **覆盖安装/异常解绑导致长期配置误清空**：当系统更新 APK 或杀后台时，无障碍服务触发瞬态 `onUnbind -> reconnect`。旧逻辑在 `AccessibilityDependencyManager.reconcile(false)` 时直接调用各组件的 `disableImmediatelyForAccessibilityUnavailable`，粗暴将用户的长期持久化配置（`desiredEnabled`）重写为 `false`，绕过了 6 秒防抖与基准保护。
2. **职责倒置与耦合**：关闭无障碍服务的拦截保护（`AccessibilityProtection`）被绑定在「防卸载与防关权限保护」（`AntiUninstallManager`）中。用户若仅开启无障碍服务，系统设置中的无障碍服务关闭开关处于完全裸奔状态。
3. **基准认知漂移**：系统异常断开后，如果基准原本为开，重连后应保持开启且关闭必须受严格次日延迟约束，不能因为短暂掉线而被视为“未开启”而允许立即关闭或直接丢失。

### 核心目标
1. **开启无障碍即开启防关保护**：无障碍服务开启后，独立激活对系统无障碍设置详情页的拦截；「防卸载与防关权限保护」仅管辖应用详情页（强行停止/卸载）与其他系统权限，彻底解耦无障碍界面管辖。
2. **意图持久保留 + 运行态挂起/自愈**：彻底废除掉线时将 `desiredEnabled` 改写为 `false` 的行为。掉线时各模块仅运行态挂起（`SUSPENDED`），重连后自动恢复 `ACTIVE`。
3. **严格基准与次日生效闭环**：
   - 关闭无障碍必须在 App 内发起，系统设置页一律拦截；
   - 若处于适应期或历史基准为未开启：App 内允许立即关闭，并临时授权放行跳转系统设置；
   - 若不在适应期且历史基准为已开启：App 内必须走次日延迟流程，待次日生效到期后方可放行关闭系统开关；
   - 关闭无障碍时联动提示并一并申请关闭依赖它的 5 项保护功能。

---

## 2. 涉及文件清单

| 模块 / 文件 | 预期改动职责 |
| :--- | :--- |
| `app/src/main/java/com/decikeeper/app/manager/AccessibilityControlManager.kt` | 核心状态机管理：管理无障碍服务的 desiredEnabled 与 PENDING_DISABLE 次日生效；废除掉线时擦除 desiredEnabled 的逻辑，改为运行态感知。 |
| `app/src/main/java/com/decikeeper/app/protection/AccessibilityProtection.kt` | 拦截门禁解耦：直接依据 `AccessibilityControlManager` 的状态进行系统设置详情页拦截，解耦 `AntiUninstallManager`。 |
| `app/src/main/java/com/decikeeper/app/manager/AccessibilityDependencyManager.kt` | 依赖对齐调整：废除对各个 Manager 的强制持久化清空，改为状态广播或运行态暂停/恢复协调。 |
| `app/src/main/java/com/decikeeper/app/ui/RulesFragment.kt` | 设置页交互与文案对齐：无障碍 Switch 绑定完整次日延迟/立即关闭状态，弹窗联动提交依赖保护；展示「防关保护生效中」。 |
| `app/src/main/java/com/decikeeper/app/protection/AntiUninstallProtection.kt` & `PermissionProtection.kt` | 移除对无障碍设置页的交叉拦截，专注自身职责。 |
| `docs/specs/protection.md` | 更新保护机制规格，固化单真理源。 |
| `app/src/test/java/...` | 补充完整 TDD 单元测试覆盖。 |

---

## 3. TDD 测试规划

1. **`AccessibilityControlManagerTest.kt`**：
   - 测试开启无障碍时 `desiredEnabled` 为 true，状态为 `ACTIVE`；
   - 测试在适应期或无基准时，调用 `setEnabled(false)` 能够立即置为 `DISABLED`；
   - 测试在有基准且非适应期时，调用 `setEnabled(false)` 提交次日延迟 `PENDING`；
   - 测试无障碍服务掉线时，持久化 `desiredEnabled` 绝不被抹除为 false。
2. **`AccessibilityDependencyManagerTest.kt`**：
   - 验证无障碍离线时，不再将 `GuardFloatingWindowManager`、`AntiUninstallManager` 等的持久化配置改写为 false。
3. **`AccessibilityProtectionTest.kt`**：
   - 验证只要 `AccessibilityControlManager.getState()` 为 `ACTIVE` 或 `PENDING`，即执行拦截；与 `AntiUninstallManager` 开关无关。

---

## 4. 实施阶段

- [ ] **Phase 1: 规格与单元测试先行 (SPEC & Tests)**：编写/更新测试用例，明确红绿契约。
- [ ] **Phase 2: 核心 Manager 改造 (Domain)**：重构 `AccessibilityControlManager` 与 `AccessibilityDependencyManager`。
- [ ] **Phase 3: 拦截层解耦 (Protection)**：重构 `AccessibilityProtection` 门禁与放行机制。
- [ ] **Phase 4: UI 与交互联动 (UI)**：调整 `RulesFragment` 中无障碍 Switch 的交互与联动。
- [ ] **Phase 5: 全量测试验证与真机验证**：运行全量单元测试与覆盖安装回归。
