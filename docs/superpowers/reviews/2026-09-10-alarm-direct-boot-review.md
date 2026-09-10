# Direct Boot 闹钟未来触发恢复：首次对抗性审查

**结论：FAIL。必须使用 Sol fallback route。**

审查对象：`codex/alarm-direct-boot` 工作区全部未提交改动（审查期间实现方额外修改了 `AlarmVibrationService.kt`；截至复核共有 6 个已跟踪文件、`AlarmBootstrapStorage.kt`、`AlarmDirectBootRecoveryTest.kt` 及随附计划/交付记录）。本审查未修改实现文件。

## 规格、计划与证据对照

- `SPEC.md:225` 要求闹钟关键状态跨 Direct Boot / DPS 恢复；`HISTORY.md:10-13` 要求 active 状态同步写入 DPS，且 Direct-Boot 服务不得阻断唤醒。
- `task_plan.md` 仍标记在 Phase 2，Phase 3 对抗审查与 Phase 4 交付验证尚未完成；`alarm-direct-boot-deliverable.md` 仍为空。
- 已实际执行：`$env:ANDROID_HOME='I:\AndordStudio'; .\gradlew.bat :app:testDebugUnitTest --tests com.zhizhi.app.AlarmDirectBootRecoveryTest --rerun-tasks`，结果 `BUILD SUCCESSFUL`。该测试只验证 DPS bootstrap 能重新注册一个主 PendingIntent，不能证明锁定用户下的接收、active 状态或前台服务可用。
- 审查期间的并发补丁后，已执行 `:app:compileDebugKotlin`，结果 `BUILD SUCCESSFUL`；这只证明当前源代码可编译，不改变上述行为性结论。

## 阻断问题（按严重度）

### P0 — Direct Boot 触发会先访问 CE，无法可靠建立 active 状态或开始震动

- **位置：** `app/src/main/java/com/zhizhi/app/receiver/AlarmReceiver.kt:410-416`; `app/src/main/java/com/zhizhi/app/manager/AlarmScheduler.kt:1040-1056`; `app/src/main/java/com/zhizhi/app/service/AlarmVibrationService.kt:418-424,453-468`。
- **触发条件：** 设备重启后尚未首次解锁，DPS bootstrap 的未来主闹钟到点。
- **后果：** 审查期间新增的 `setAlarmActiveFromDirectBoot()` 已正确把 active 写入限制到 DPS，这一半修复成立；但服务取得 `directBoot=true` 后，仍在 `AlarmVibrationService.kt:421-424` 无条件读取 CE 中的 `AlarmEntry` 和默认提醒配置，随后在 `resolveCurrentStagePolicy()` 继续读 CE。`EXTRA_DIRECT_BOOT` 仅改变 active 写法，未隔离服务的数据依赖。因而 Direct-Boot-aware 的 manifest 声明仍不能保证实际链路可运行：闹钟可能在锁定状态下未能开始震动/响铃。
- **最小修复建议：** 保留新的 DPS-only active 写入，但为震动服务增加明确的 Direct Boot 分支，使其完全不调用 `findAlarm()` 或任何 CE 默认值；仅依赖 DPS 中足够的提醒策略。若现有 bootstrap 字段不足，扩展为最小且版本化的提醒快照。用户解锁后再一次性合并 CE 状态、统计与覆盖层。

### P1 — 调度 token、系统闹钟和 DPS bootstrap 的发布顺序不是崩溃安全的

- **位置：** `app/src/main/java/com/zhizhi/app/manager/AlarmScheduler.kt:775-783,813-820,912-926`; `app/src/main/java/com/zhizhi/app/manager/AlarmBootstrapStorage.kt:45-65`。
- **触发条件：** 编辑、重排、解锁后重调度或普通续期时，在 CE `apply()`、`setAlarmClock()` / `setExactAndAllowWhileIdle()` 和最后一次 DPS `upsert(...).commit()` 之间发生进程死亡或重启。
- **后果：** `AlarmManager` 已接受新 PendingIntent/token，但 DPS 中的 bootstrap 仍缺失或仍指向旧触发时间/token。锁定态重启后会漏注册新闹钟；若旧条目仍在，则可能按过时的 token/时间触发。该窗口违背本任务的“只凭 DPS 恢复、且不产生幽灵闹钟”目标。
- **最小修复建议：** 把 CE、DPS bootstrap 和 AlarmManager 视为一次发布事务：用同步持久化先落下与待发布 PendingIntent 完全一致的 DPS 快照，再调度；调度失败时同步回滚/删除该快照。至少加入进程中断边界的恢复测试，验证缺失、旧 token 和新 token 三种状态。

### P1 — 通过的回归测试没有覆盖 Direct Boot 行为本身

- **位置：** `app/src/test/java/com/zhizhi/app/AlarmDirectBootRecoveryTest.kt:25-68`。
- **触发条件：** 当前 CI 运行该单测并将其视为 Direct Boot 恢复证据。
- **后果：** 测试使用普通 Robolectric application context，既不设置 `UserManager.isUserUnlocked=false`，也不调用 `BootReceiver` / `AlarmReceiver` 或启动 `AlarmVibrationService`。因此它无法暴露 P0，也未验证 `LOCKED_BOOT_COMPLETED → 触发 → USER_UNLOCKED/BOOT_COMPLETED` 时序、active 状态、token 拒绝和覆盖层恢复。
- **最小修复建议：** 增加可注入的“用户是否已解锁” seam，并至少覆盖：锁定态未来闹钟重注册、锁定态正确 token 触发并写 DPS active、旧 token/过期触发拒绝、解锁后只恢复一次完整 UI/会话。另需真机冷启动测试验证系统级 Direct Boot 与前台服务限制。

### P2 — 取消路径的 PendingIntent identity 与创建路径不一致，删除/禁用后仍可能留下系统闹钟

- **位置：** `app/src/main/java/com/zhizhi/app/manager/AlarmScheduler.kt:959-973,1284-1331`。
- **触发条件：** 用户删除或禁用闹钟，或 `saveAlarms()` 重排后调用 `cancelAlarm()`。
- **后果：** 创建 MAIN/PREWARM/BACKUP PendingIntent 时 identity 含有 `data=ironwill://alarm/<kind>/<id>`；取消时传入 `kind=null`，生成的 Intent 没有该 data URI。PendingIntent identity 不匹配，`AlarmManager.cancel()` 不能保证取消实际已调度项。新加的 `AlarmBootstrapStorage.remove()` 只移除 DPS 快照，不能移除这个残留系统闹钟；在本任务强调的“无重复/无幽灵闹钟”场景中仍是风险。
- **最小修复建议：** 对每一种 `AlarmIntentKind` 以同一 identity 取消；如需兼容历史无 data 的 PendingIntent，额外取消 legacy identity，而不是仅使用 legacy identity。为删除、禁用及重排后的 AlarmManager 队列加入回归断言。

### P3 — 已过期 bootstrap 被跳过但不会清理，诊断状态会长期陈旧

- **位置：** `app/src/main/java/com/zhizhi/app/manager/AlarmScheduler.kt:734-768`; `app/src/main/java/com/zhizhi/app/manager/AlarmBootstrapStorage.kt:45-65`。
- **触发条件：** 设备在闹钟触发前后长时间保持锁定，或闹钟已错过后再次多次锁定态重启。
- **后果：** `nextTriggerAtMillis <= now` 的条目不会重新调度，这是正确的防过期保护；但条目持续保留到某次 CE 调度，日志和 DPS 快照不再反映可用未来闹钟，且没有针对这一状态的测试。
- **最小修复建议：** 在锁定态读取时明确清理已过期且不属于 active session 的条目，或保留带原因/过期时间的有限诊断记录；为“过期条目不调度、不触发、不会阻碍下一次合法 bootstrap”添加测试。

## 已验证的正确点

- `AlarmBootstrapStorage` 明确使用 `createDeviceProtectedStorageContext()`，并对写入使用 `commit()`（`AlarmBootstrapStorage.kt:45-65`），符合 DPS 可用性与同步持久化方向。
- `LOCKED_BOOT_COMPLETED` 分支只调用 `scheduleAllForDirectBoot()` 而不读取完整 `alarms_json`（`BootReceiver.kt:82-90`）；未来条目过滤 `enabled && nextTriggerAtMillis > now`（`AlarmScheduler.kt:737-739`），避免直接重排已过期 bootstrap。
- `AlarmReceiver` 已声明 `android:directBootAware="true"`（`AndroidManifest.xml:272-275`），且锁定态只接受 MAIN action、DPS 条目、匹配 token 及有限时间窗（`AlarmReceiver.kt:87-91,395-408`）。这正确隔离了旧 token 和其他闹钟类型。
- PendingIntent 工厂为 MAIN/PREWARM/BACKUP 等种类配置不同 request code 与 data URI，且使用 `FLAG_UPDATE_CURRENT | FLAG_IMMUTABLE`（`AlarmScheduler.kt:1292-1331`）；重新注册同一 MAIN identity 时可更新 extras，而 token 校验仍可拒绝旧投递。
- 解锁后的 `USER_PRESENT` 分支会重调 CE 闹钟并尝试恢复 active overlay（`BootReceiver.kt:140-146`），设计方向与“锁定态仅响铃、解锁后恢复完整体验”一致；但必须先修复 P0 并用时序测试证明只恢复一次。

## 验收门槛

在 P0、P1 和 P2 修复并完成锁定态端到端验证前，不得宣告本任务完成。Sol fallback route 必须用于后续实现与复审。
