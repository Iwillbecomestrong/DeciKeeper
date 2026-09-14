# Direct Boot 闹钟修复交接

## 当前工作区

- Worktree: `<worktree-root>/alarm-direct-boot`
- Branch: `codex/alarm-direct-boot`
- Base: committed `dev` snapshot `975c4ba`
- 原始主仓库工作区的 dirty `dev` 不得修改、清理或合并。

## 已有实现

当前 worktree 已包含一轮初步实现：

- 新增 `AlarmBootstrapStorage.kt`，用 Device Protected Storage 保存 `alarmId / nextTriggerAtMillis / enabled / scheduleToken`。
- `AlarmScheduler.scheduleAllForDirectBoot()` 在 `LOCKED_BOOT_COMPLETED` 只读取 bootstrap 并重注册 MAIN trigger。
- `AlarmReceiver` 增加 Direct Boot token、时间窗校验，并启动 direct-boot vibration service。
- `AlarmVibrationService` 与 `AlarmReceiver` 的 Manifest directBootAware 已配置。
- `BootReceiver` 保留 active alarm recovery，并在 USER_PRESENT 后恢复 overlay。
- 新增 `AlarmDirectBootRecoveryTest.kt`。
- `dialog_alarm_default_settings.xml` 删除了 committed dev 中导致所有 Gradle 任务无法解析的两个前置空行；这是验证所需的最小基线修复，交付时必须单独披露。

## 两轮审查已发现、必须闭环处理的问题

### P0

1. Direct Boot 进程会先执行 `MyApplication.onCreate()`；必须确认并修复其 CE 初始化不会在锁定态阻断 `BootReceiver`/`AlarmReceiver`。若确有阻断，增加最小的 locked-user early return，仅保留 DPS 安全路径。
2. active alarm recovery 启动 `AlarmVibrationService` 时必须传递 Direct Boot 标记和可用 token；服务重启/`START_STICKY` 也不能在锁定态回退到 `findAlarm()` 或 default CE 配置读取。

### P1

1. 正常 schedule、fallback、取消/禁用/删除路径要维护 bootstrap；写入顺序不能留下 AlarmManager 已成功但 DPS 未写入的窗口。
2. 如支持周期闹钟，Direct Boot 触发后必须推进下一次最小重复状态；不能只消费一次 `nextTriggerAtMillis` 后永久丢失后续周期。
3. Direct Boot active 状态与解锁后的 wake-session/恢复计数语义要闭合；不得破坏现有 challenge 统计。
4. Android 12+ 无 exact-alarm 权限时，Direct Boot/fallback 必须使用真正的非精确 API，不要再次调用 exact API 后静默失败。
5. 锁定态服务不得构造或依赖需要 CE 的完整 Activity/挑战配置；USER_PRESENT 后要显式重新加载完整配置并恢复 overlay。
6. 测试至少覆盖 DPS 重注册、LOCKED_BOOT_COMPLETED、locked receiver 的成功与 token/time 拒绝、active 不读 CE、重复广播幂等、解锁恢复及周期调度 seam。

### P2

- 新版 kind/data URI 的 PendingIntent 取消 identity 必须与创建 identity 一致，同时兼容旧 identity。
- 完成后填写 `task_plan.md`、`notes.md`、`alarm-direct-boot-deliverable.md`，不要留下占位完成状态。

## 实现代理工作要求

- 直接在本 worktree 修改，不新建第二个实现 worktree，不提交 Git commit。
- 先阅读仓库入口规范与本交接文档，再修改；保持现有业务逻辑最小变更。
- 先补/更新失败测试，再实现；完成后运行：

```powershell
$env:ANDROID_HOME = 'I:\AndordStudio'
$env:ANDROID_SDK_ROOT = 'I:\AndordStudio'
.\gradlew.bat :app:testDebugUnitTest --no-daemon
.\gradlew.bat :app:assembleDebug --no-daemon
```

- 最终回复必须列出改动文件、测试结果、剩余真实设备验证边界；不要自行提交或推送。
