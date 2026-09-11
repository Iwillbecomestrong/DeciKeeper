# Direct Boot 未来闹钟修复 SPEC

## 目标

在 Android 手机重启后、用户尚未第一次解锁的 Direct Boot 阶段，已启用的未来闹钟仍能在目标时间触发并开始震动。用户解锁后，现有完整闹钟配置、挑战流程、统计和 overlay 恢复为正常行为。

## 必须保持的现有行为

1. `LOCKED_BOOT_COMPLETED` 仍保留现有 active alarm recovery：重启前已经在响的闹钟继续恢复。
2. 普通 `BOOT_COMPLETED`、`USER_PRESENT`、App 内设置闹钟的正常行为不改变。
3. 挑战题目、铃声选择、保护策略、统计业务不复制到 DPS，也不在 Direct Boot 重新实现。
4. 不改原始 `G:\IronWIl0329\IronWill-OpenSource` dirty `dev`，不提交 Git，不卸载 App 或清除用户数据。

## 存储契约

### CE（Credential-protected storage）

继续保存完整 `AlarmEntry`（题目、挑战、铃声、策略等）。未解锁时不得从 Direct Boot 路径读取 `alarms_json` 或任何 CE 闹钟配置。

### DPS（Device-protected storage）

新增单一 bootstrap 存储，至少包含：

- `alarmId`
- `nextTriggerAtMillis`
- `enabled`
- `scheduleToken`

为支持用户持续锁定时的周期闹钟，可增加最小重复信息：`hour`、`minute`、星期 `daysMask`。不得写入挑战、铃声、题库或完整 `AlarmEntry`。

更新契约：

- 设置/启用/正常调度：先保证 CE 中 token 与 DPS bootstrap 表示同一次计划，再提交 AlarmManager 计划；不能出现“AlarmManager 已成功但 DPS 仍为空”的发布顺序。
- 禁用/删除/取消：取消同一 `PendingIntent` identity 后移除该 alarm 的 bootstrap。
- 解锁后：用 CE 完整配置重新调度并校正 DPS；旧版本没有 bootstrap 时，在解锁可读 CE 的安全时机补建，不要求 Direct Boot 猜测完整配置。

## 状态流程

### 写入与重启

```text
设置/启用闹钟
  -> CE 保存完整 AlarmEntry
  -> DPS 保存最小 bootstrap
  -> AlarmManager 注册 MAIN trigger

重启且未解锁
  -> LOCKED_BOOT_COMPLETED
  -> 只读 DPS
  -> 重新注册未来 MAIN trigger
```

### Direct Boot 触发

1. `AlarmReceiver` 必须是 `directBootAware`。
2. 仅处理 MAIN trigger；按 `alarmId + scheduleToken + 时间窗` 与 DPS 校验。
3. token 不匹配、禁用、过期/过早都拒绝，不启动服务。
4. 通过校验后，DPS active 状态以幂等方式写入；同一 token 的重复广播不得重复启动服务。
5. 启动 `AlarmVibrationService` 时带 Direct Boot 标记和 token。
6. Direct Boot service 分支不得调用 `findAlarm()`、`getDefaultAlarmMode()`、`getDefaultRingtoneUri()` 或读取其他 CE 配置；使用固定的保守震动策略即可，不要求锁定态显示完整挑战 UI。
7. 如果 bootstrap 含周期信息，触发后在 DPS 中生成新的 token、计算并注册下一次 MAIN trigger；无周期信息则不伪造下一次计划。

### 用户解锁

`USER_PRESENT` 必须：

1. 重新从 CE 调用现有 `scheduleAll()`/正常调度逻辑；
2. 若 active alarm 仍存在，重新启动普通（非 Direct Boot）service 以加载完整配置；
3. 恢复现有 `AlarmWindowManager` overlay；
4. 不重复创建同一 wake session，不清掉有效 active 状态。

## Application 生命周期要求

如果证据证明 `Application.onCreate()` 在锁定态会先访问 CE 并阻断 Receiver，则只增加最小 locked-user early path：设置必要的进程实例，执行 DPS active recovery，跳过普通 CE 初始化；用户解锁后由现有正常初始化路径接管。禁止把整个 Application 默认存储切换为 DPS。

## PendingIntent 要求

调度创建与取消必须使用完全一致的 `action + requestCode + kind/data URI` identity；可额外取消旧版本无 kind/data 的 identity 以兼容历史计划。不能只因为 `PendingIntent` 对象创建成功就认为旧计划已取消。

## 验收标准

必须新增或更新 JVM/Robolectric 测试覆盖：

1. DPS bootstrap 能在未来时间重新注册 MAIN trigger，并携带 alarmId/token/time。
2. `LOCKED_BOOT_COMPLETED` 入口能触发上述重注册。
3. 锁定用户下匹配 token 的 MAIN trigger 能建立 active 并启动 Direct Boot service seam。
4. token 不匹配、过期/过早、禁用 trigger 被拒绝。
5. 同一 token 重复广播幂等，不重复启动或覆盖 session。
6. active Direct Boot 路径不依赖 CE；解锁后能进入普通配置路径。
7. 周期 bootstrap 触发后能生成并注册下一次计划（如果实现支持周期信息）。
8. kind/data URI 调度后的 `cancelAlarm()` 真正取消计划。

验证命令：

```powershell
$env:ANDROID_HOME = 'I:\AndordStudio'
$env:ANDROID_SDK_ROOT = 'I:\AndordStudio'
.\gradlew.bat :app:testDebugUnitTest --no-daemon
.\gradlew.bat :app:assembleDebug --no-daemon
```

真实设备的锁定重启、OEM 省电策略、精确闹钟权限仍属于交付后的验证边界；代码和 JVM 测试不得宣称已经证明 OEM 行为。

## 非目标

- 不重构 AlarmManager/Guardian/挑战系统。
- 不把完整闹钟数据迁移到 DPS。
- 不在 Direct Boot 阶段强行显示依赖 CE 的完整 `AlarmActivity`。
- 不借机修复无关统计、UI、权限或保护模块问题。
