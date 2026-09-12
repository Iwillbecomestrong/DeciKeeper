# 防卡死对抗性审查与加固审计报告

## 1. 审计结论

本专项基于 `dev@0c38993` 创建分支 `audit/anti-deadlock-hardening`，完成了闹钟覆盖层、锁窗口、无障碍防卸载和线程调度链路的源码审查、对抗复核、JVM 契约测试与最小加固。

当前结论：**代码级防卡死加固 PASS；真机 OEM 验收仍为待执行项。**

本次没有改变额度、冷却公式、时间窗、题库导入、统计模块或核心阻断强度。现有 issue tracker 中的代际校验、反卸载优先级、闹钟开孔不透明挡板、扫码恢复与窗口模式升级路径均保留。

## 2. 审查方法与证据边界

- Reviewer 视角：沿生产调用链追踪 `CoreService.onAccessibilityEventInternal`、`ProtectionManager.onAccessibilityEvent`、`AntiUninstallProtection.check`、`AlarmActivity` 恢复入口以及 `LockWindowManager`/`LockAlarmOverlay` 的 WindowManager 状态迁移。
- Challenger 视角：逐项质询“加固是否会削弱保护”“超限是否会绕过”“OEM 豁免是否被误伤”“失败后是否留下游离 View”“是否把同步耗时工作移到错误线程”。
- 双子代理调度接口本轮返回参数错误，未能启动独立 Codex 子任务；因此在同一工作树上执行了独立 Reviewer/Challenger 两轮证据复核，并将该事实保留在本报告中。
- JVM/源文本契约可以证明状态机和代码契约；无法替代 Vivo、OPPO、Samsung 等真机 WindowManager token、息屏唤醒、关机菜单和无障碍服务重连验证。

## 3. 确认风险与因果链

### R1：无障碍树扫描缺少总节点数/耗时熔断（P1，已修复）

调用链：

`CoreService.onAccessibilityEventInternal` → `ProtectionManager.onAccessibilityEvent` → `AntiUninstallProtection.check` → `detectUninstallAttemptSample` → `collectAllText`。

原问题：原扫描只有递归深度、子节点数和字符串长度限制。OEM 若返回异常宽树或深树，仍可能在无障碍服务主线程上访问大量节点，形成 ANR/输入卡顿风险；`scanStartTime` 只记录日志，没有作为熔断条件。

加固：`AntiUninstallProtection.kt:1605` 的 `NodeTraversalBudget` 增加每次扫描最大 256 节点、40ms 时间预算，并由 `collectAllText`、`collectPrivateSpacePageHints`、`hasStaticAppNameMention` 共享预算。受监控设置/安装器/商店包的全量扫描在预算耗尽时返回 `[TRAVERSAL_LIMIT_EXCEEDED]`，进入既有阻断链路而非放弃检测。

### R2：防卸载退栈动作在部分路径可能连发（P1，已修复）

调用链：

`AntiUninstallProtection.check` → `handleDetection` → `performGlobalAction(BACK/HOME)` 及延时动作。

原问题：已有 launcher 签名抑制和部分内容事件去抖，但设置/商店等路径没有统一动作发射门禁；OEM 连续窗口事件可能堆积 BACK/HOME，造成返回栈轰炸和主线程调度压力。

加固：`AntiUninstallProtection.kt:1401` 增加统一 500ms 动作防抖。它只抑制重复全局动作，不改变 `detected`、热窗口、反卸载优先级或核心阻断逻辑；首次动作仍照常执行，后续事件不会形成动作风暴。

### R3：闹钟覆盖层恢复路径存在裸 remove/add 游离态（P1，已修复）

调用链：

`AlarmActivity`/`AlarmWindowManager`/`AlarmVibrationService` → `showAlarmOverlayIfHidden` → `WindowManager.removeView/addView`。

原问题：目标 WindowManager 添加失败时，View 可能已被移除且状态标记仍显示已挂载，导致扫码恢复后白屏、震动持续、无法退出。

加固：`LockAlarmOverlay.kt:2828` 的隐藏恢复路径以及初次窗口挂载路径统一使用既有 `attachAlarmViewAtomically`；失败结果区分 `SWITCHED`、`ROLLED_BACK`、`DETACHED`，并保留 `OVERLAY_ATTACH_FAILED`、`OVERLAY_ATTACH_ROLLED_BACK`、`OVERLAY_DETACHED_NEEDS_REBUILD` 证据。

### R4：锁窗口跨 WindowManager 重挂载失败后缺少回滚（P1，已修复）

调用链：

`LockWindowManager.showLockScreen` 重复命中 → `reassertAttachedLockVisibility` → 从当前 WindowManager 切到目标 WindowManager。

原问题：旧路径先 `removeView` 再 `addView`；目标 token/OEM 状态异常时，锁 View 可能游离，状态仍在或保护层短暂消失。

加固：`LockWindowManager.kt:5819` 在跨窗口迁移失败时立即使用原参数回挂当前 WindowManager；回滚成功记录 `REASSERT_ROLLED_BACK` 并保持锁状态，只有回滚也失败才进入既有 `resetLockState` 自愈路径。

## 4. Challenger 对抗结论

| 质询 | 结论 |
|---|---|
| 扫描预算会不会变成绕过窗口？ | 不接受静默截断；受监控包预算耗尽 fail-closed，继续进入原阻断链路。 |
| 会不会误伤无障碍服务列表？ | `detectUninstallAttemptSample` 先合并事件文本并执行 `shouldSkipAccessibilitySettings`，再执行预算耗尽判定；既有列表页豁免优先保留。 |
| 防抖是否把保护降级成软提醒？ | 否。防抖只阻止重复 BACK/HOME；首次拦截仍执行，热窗口与反卸载判定未移除。 |
| 闹钟恢复失败是否留下幽灵 View？ | 原子挂载结果显式分支；目标失败时回滚，双失败标记 DETACHED 并等待现有收殓/重建路径。 |
| 代际校验/OEM 证据是否被删？ | 未删。`mAttachedLockGeneration`、anti-uninstall 优先级、闹钟开孔和系统对话框升级路径均保留。 |
| 是否新增主线程同步 IO 或锁嵌套？ | 未新增 `synchronized`；MonitorService 的监控循环仍在后台 HandlerThread，`LockWindowManager.updateStatus` 仍把 UI 更新投递至主线程。 |

## 5. 测试映射

新增：`app/src/test/java/com/decikeeper/app/AntiDeadlockHardeningContractTest.kt`

- 退栈防抖：首次动作、阈值内抑制、阈值边界放行；
- 无障碍扫描：节点数/耗时预算、递归共享预算、超限 fail-closed、列表豁免优先；
- CoreService 节点标识扫描：最大深度、节点数和耗时熔断契约；
- 闹钟覆盖层隐藏恢复：必须使用原子挂载，禁止回退到裸 `targetWM.addView`；
- 锁窗口重挂载：必须存在回滚状态与 `REASSERT_ROLLED_BACK` 证据。

既有相关契约同步通过：`AlarmOverlayAttachContractTest`、`AlarmOverlayAttachStateTest`、`AlarmScanCutoutContractTest`、`AntiUninstallProtectionGateTest`、`CoreServiceInterruptLifecycleTest`。

## 6. 验收状态

- [x] 分支基线确认：`audit/anti-deadlock-hardening`，起点 `dev@0c38993`；
- [x] 目标/新增 JVM 契约测试通过；
- [x] 全量 `./gradlew.bat :app:testDebugUnitTest`：`BUILD SUCCESSFUL`，全量 JVM 门禁通过。为消除测试夹具自身的业务日漂移，`ConsumePenaltySecondsEpisodeTest` 保留 `config_refresh_time` 后再清账本，并清理跨测试的 pending/baseline 状态；历史结余用三段 `history → current → next` 夹具验证。未修改 `CreditManager` 或额度/时间规则；
- [x] `./gradlew.bat :app:assembleDebug`：`BUILD SUCCESSFUL`；
- [ ] OEM 真机验证：Vivo/OPPO/Samsung 的息屏唤醒、扫码恢复、WindowToken 失效重建、无障碍重连、系统关机菜单；
- [x] 最终 AOCI 维护检查已调用；AOCI 因仓库既有 code drift/orphan/stale/unbaselined（188 findings）阻断，未对索引或源码做越界清理。

Gradle 当前会报告既有 configuration-cache serialization 警告，但不影响测试结果或 Debug 构建；本次全量 JVM 门禁已通过。

## 7. 交付边界

本报告不把 JVM 绿色等同于真机完成。合并前仍应在至少一台 Vivo 真机和一台非 Vivo OEM 上执行真实窗口/无障碍生命周期回归；若真机发现 token 或系统对话框差异，只在对应证据路径上追加最小契约，不修改业务规则。
