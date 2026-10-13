# 日志系统重新设计

> 状态：**方案已与用户讨论并认可，待用户批准后实现；尚未改任何代码**。本文不是产品真相；确认后再回写 `SPEC.md` / `HISTORY.md`。
> 分支：`feat/log-system-redesign`（基于 `dev` 9e19176）。
> 产品名：决定守护 DeciKeeper（包名 `com.decikeeper.app`）。

## 1. 背景与证据

用户反馈：最新日志太多，会冲掉前面的日志；早上的日志一条都没了，导出最近 300 秒就有 5625 条。

实测（真机 Vivo V2425A，应用 0.3.0，导出时间 2026-10-10 11:18，原文件存于 `.local_debug/device-20261010/app_log.txt`，该目录被 git 忽略）：

| 标签 | 行数 | 占比 |
|---|---|---|
| `[FG_RAW_STATS]` | 2790 | 49.6% |
| `[FG_RAW_EVENTS]` | 891 | 15.8% |
| `[FG_RAW_STATS_SUMMARY]` | 812 | 14.4% |
| `[FG_RESOLVE_TRACE]` | 551 | 9.8% |
| `[FG_RAW_EVENTS_SUMMARY]` | 548 | 9.7% |
| 以上五个合计 | 5592 | **99.4%** |

- 五个标签全部来自 `service/ForegroundDiagnosticTracker.kt`，它是一次性的排障代码（"Vivo 上停留 Claude 时 foreground=NONE"），没有接任何开关，每个前台判定 tick（约 0.55 秒）无条件写 logcat 和落盘文件。用户已确认该问题已不记得，同意直接删除，后续需要时再重新加入。
- 300 秒内只发生一次前台切换。549 次 `FG_RESOLVE_TRACE` 只有 17 种不同状态、20 段连续相同区间，合并后约压缩 97%。
- 稳定前台下大量命中 `CACHE_HIT` 的原因：`MonitorService.getTopPackageName` 只查最近 30 秒的 `UsageStatsManager.queryEvents`，切换事件只在切换瞬间产生一次，停留超过 30 秒就滑出窗口；回退到最近 60 秒的 `queryUsageStats` 也无结果时，使用内存缓存 `mLastTopPackage`（及 `mLastTopEvidence`、`mLastTopResolvedAt`）。本次不改这一业务逻辑。
- 旧版本日志（2026-08-12 至 08-14）中，`FG_DECISION` 8 行证据块占 62.8%，旧式 `[EVENT] type=SUSPECT` 占 28.2%，同样是重复输出。
- 文件超过 10MB 时，`LogExporter.truncateLogFile` 整文件读入、丢前一半、再整体重写，所有类型一起丢，没有重要性区分。
- 全仓现有 1428 处日志调用点（`Log.*`、`LogExporter.*`、`NormalEvents.create`，100 个文件）。

## 2. 目标

1. 日志只在 **App 状态发生变化** 时写入；状态没变不写。
2. 重要事件（闹钟、规则、防护与权限、额度账务、状态迁移、崩溃）**不被噪声冲掉**。
3. 在源头分级，去掉导出时的 `exportDropPatterns` 过滤。
4. 出问题时能看到前因，即使进程被系统杀掉。
5. 降低磁盘和 CPU 开销。
6. 按模块组织，导出、治理测试、触发规则都能按模块处理。

## 3. 范围

**做**
- 统一日志门面，一次性迁移全部 1428 处调用点。
- 分层存储：永久账本 + 近期诊断分片。
- 删除 `ForegroundDiagnosticTracker` 及其单元测试。
- 退役 `exportDropPatterns`、`docs/ai/LOG_EVENT_MIGRATION.md` 迁移表，以及冻结 `LogExporter.kt` 的 `LogEventMigrationInventoryTest`（见 §11）。

**不做**
- 不改前台来源优先级、回退顺序、黑名单、可见性、锁屏、放行、额度、闹钟等任何业务策略。
- 不引入新的第三方日志库。
- 本轮不新增用户可见的"每日摘要"功能。

## 4. 核心原则

1. **状态变化才写。** "状态"指 App 的状态，不是前台应用换了一个。前台从 A 应用切到 B 应用，只要 App 状态没变，就不写永久账本。
2. **没事不写。** 休息、白名单、已解锁期间保持静默。内存里只记"从几点开始、已重复几次"，状态变化时补写一行汇总（持续时长、次数）。
3. **相同判断合并。** 连续相同的判断合并成一行。
4. **取证红线不变。** 诊断、日志和压缩档案只能事后排查，业务代码不得读取它们作为放行或拦截依据（`AGENT_CORE.md`）。内存缓冲同样只写不读。
5. **落盘的日志必须经统一门面。** 禁止再出现直写文件的诊断代码。
6. **命名严谨，对应代码。** 字段名、事件名、取值一律使用代码里的英文名称，不做中文翻译；来源字段使用代码枚举，并在文件头给出与真实 Android 接口的对照表（见 §8）。备注和提示文字等不重要的地方可以用中文。

## 5. 模块划分

按 `docs/product/system-overview.md` 的产品术语（时间规则、挑战闹钟、保护与边界）划分，调用点数量为按文件名归类的近似值。

```
时间规则
├─ RULE.CONFIG      参数改变与生效        51 处   3.6%   TimeRuleManager、QuotaConfigEventBridge、RulesFragment、expiry/
├─ RULE.QUOTA       额度账本              83 处   5.8%   CreditManager、UsableCount
├─ FOCUS.DETECT     前台检测             245 处  17.2%   MonitorService、CoreService、ForegroundReport*、PresenceNotification
└─ FOCUS.LOCK       锁屏与出口           252 处  17.6%   LockWindowManager、FastLockService、AppWindowManager

挑战闹钟
├─ ALARM.SCHEDULE   调度与恢复           201 处  14.1%   AlarmScheduler、AlarmReceiver、Heartbeat、Watchdog、Guardian
├─ ALARM.RING       响铃                  49 处   3.4%   AlarmVibrationService、Ringtone
└─ ALARM.CHALLENGE  挑战与界面           224 处  15.7%   LockAlarmOverlay、AlarmActivity、AlarmWindowManager、CameraXScan

保护与边界
└─ PROTECT          加强约束拦截         202 处  14.1%   AntiUninstall、PermissionManager、StartupProtection、AdminReceiver

系统层
└─ SYS              进程/启动/备份/崩溃  112 处   7.8%   MyApplication、BootReceiver、AutoBackup、CrashRecorder
```

另有约 9 处未归类（`SettingsBackupManager`、`PwaBypassManager` 等），实现时逐处归入。

**限制**：以下文件跨模块，必须逐个调用点归属，不能整文件归类：
- `ui/LockWindowManager.kt`（160 处，6500 多行状态机，混有锁屏、临时解锁、休息面板、挑战与闹钟 snooze）。
- `service/CoreService.kt`（无障碍前台检测与部分保护）。
- `ui/LockAlarmOverlay.kt`（按名称暂归 `ALARM.CHALLENGE`，实现前需读代码确认）。

**模块归属不逐行写入日志**，使用"事件类型 → 模块"登记表，导出和筛选时查表，不增加每行字节数。

现有事件类型对应：

| 模块 | 事件 |
|---|---|
| RULE.CONFIG | `CONFIG_CHANGE`、`RULE_EFFECTIVE` |
| RULE.QUOTA | `REST_QUOTA_CHANGE`、`REST_COUNT_CHANGE`、`PENALTY`、`BORROW`、`REFUND`、`CARRYOVER_*` |
| FOCUS.DETECT | `FG_DECISION`、`FOREGROUND_TRANSITION`、`FG_END` |
| FOCUS.LOCK | `LOCK_RESULT`、`LOCK_EXIT`、`TEMP_ACCESS_*`、`EMERGENCY_ACCESS_*`、`ALLOW_*`、`DESKTOP_TIMER_*` |
| ALARM.SCHEDULE | `ALARM_SCHEDULE`、`ALARM_TRIGGER`、`ALARM_RECOVERY`、`ALARM_WATCHDOG` |
| ALARM.RING | `ALARM_RING_START`、`ALARM_RING_END` |
| ALARM.CHALLENGE | `ALARM_CHALLENGE_*`、`ALARM_OVERLAY_*`、`ALARM_DISMISS`、`ALARM_SNOOZE` |
| PROTECT | `PROTECTION_STATE_CHANGE`、`SERVICE_STATE`、`BYPASS_RISK` |

## 6. 分级

### A 级：永久账本（`journal`，独立配额，不被噪声冲刷）

| 类别 | 事件 / tag |
|---|---|
| 闹钟 | `ALARM_SCHEDULE/TRIGGER/RING_START/RING_END/SNOOZE/DISMISS/CHALLENGE_*/RECOVERY/WATCHDOG`、`ALARM_OVERLAY_SHOW/HIDE`、`AlarmRecovery`、`AlarmReceiver [ALARM_TRIGGER_ACCEPTED]` |
| 规则与配置 | `CONFIG_CHANGE`、`RULE_EFFECTIVE`、`Import`、`ConfigChange`、`Config` |
| 防护与权限 | `PROTECTION_STATE_CHANGE`、`SERVICE_STATE`、`BYPASS_RISK`、`A11ySelfDisableAuth`、`PermissionManager`、`AntiUninstall [BLOCK]/[HANDLE]` |
| 额度账务 | `PENALTY`、`REFUND`、`BORROW`、`CARRYOVER_*`、`REST_QUOTA_CHANGE`、`REST_COUNT_CHANGE`、`BUSINESS_DAY_SETTLEMENT`、`QUOTA_TRANSACTION` |
| 状态迁移 | `LOCK_RESULT`、`LOCK_EXIT`、`TEMP_ACCESS_*`、`EMERGENCY_ACCESS_*`、`ALLOW_START/END`、`DESKTOP_TIMER_*`、`Rest [NATURAL_FREEDOM]`、`UNLOCK_RESULT`、`LockScreen [DEFERRED]/[AUTO_RELEASE]` |
| 崩溃 | `crash_log.txt`（沿用） |

**A 与 B 的分工**：A 记"发生了什么、为什么"，每个状态迁移事件（锁、解锁、倒计时等）附带**一行精简证据**，字段为前台包名、来源枚举、原因码，例如 `evidence=fg:com.ss.android.ugc.aweme src:EVENTS reason:EVENTS_BUSINESS_MATCH`，每个事件约多 100 字节。大多数情况下只看 A 就能知道"为什么"。B 保存体积大、不宜永久保留的完整证据，以及 **A 里没有对应事件的情况**，典型是"该锁却没锁"：没有发生锁就没有 A 事件，只有 B 里连续的前台判定行才能看出当时前台是什么、来源和原因是什么。

### B 级：近期诊断（`trace`，分片滚动，写满丢最老整片）

| 事件 | 处理 |
|---|---|
| `FG_DECISION` 8 行完整证据块 | **只保留真正的异常**：请求上锁、漏锁、来源之间意见分歧、真正的来源故障及其恢复，进 A（长期保留）。vivo 上"30 秒内没有前台事件 / 使用统计为空"（`NO_RECENT_FOREGROUND_EVENT`、`USAGE_STATS_MANAGER_NULL_OR_EMPTY`）触发的"来源故障"是正常安静窗口，不再写 8 行报告 |
| 前台采样 `[FG_SAMPLE]` | 每 5 秒一条（前台包、来源、动作、原因、三个来源各自状态），**只保留最近 3 分钟**，超出即丢；导出时并入 B 分区。没有状态变化时留着没用，所以不提升为长期记录 |
| 前台判定合并行 | 内存攒批，每约 5 秒落盘；连续相同判定合并为一段 |
| `AlarmOverlay` 逐步生命周期 | 保留最近几次闹钟的逐步轨迹；更早的仅保留 A 级 `ALARM_OVERLAY_SHOW/HIDE` |
| `[EXPIRY_LOCK]`、`COOLDOWN_START` | 近期保留 |
| `FOREGROUND_TRANSITION`、`FG_END` | 近期保留，更早的压成"每个应用累计停留时长" |

### C 级：不落盘（直接删除，或仅 logcat 的开发期输出）

优先级从高到低：

1. `ForegroundDiagnosticTracker` 的五个标签（`FG_RAW_*`、`FG_RESOLVE_TRACE`）：整体删除。
2. 旧式 `[EVENT] type=SUSPECT`（`FREE_TIME_BYPASS`、`SHOULD_BLOCK_FALSE` 成对重复）。
3. `FG_DECISION` 的无变化 tick。
4. `AntiUninstall [Event]` 无障碍事件转储。
5. `SystemDialog [VOLUME_KEY]`、`Timer [THREAD]/[KEEP]`、`PresenceDiag`。
6. 已退役但源码里仍存在的 `[VIS_CHECK_DIAG]`、`[FG_BYPASS_DIAG]`、心跳、`WATCHDOG_TICK`。

其余 `android.util.Log.*`：默认归 C 级，逐文件审，凡是 `Log.e/w` 且属于 A 级主题的，升级到 A 或 B。

## 7. 触发点（哪些状态变化才写，按模块）

| 模块 | 触发 |
|---|---|
| RULE.CONFIG | 每次参数改变、规则生效 |
| RULE.QUOTA | 每笔额度账务变化（扣费、借用、退款、结转、结算） |
| FOCUS.DETECT | App 状态迁移（见下表）；前台应用在同一 App 状态内切换只进 `trace`，不进 `journal` |
| FOCUS.LOCK | 白名单 → 黑名单被锁、回到桌面、申请解锁（解锁后监测不再输出）、桌面倒计时启动 / 结束、休息结束进入锁机 |
| ALARM.* | 调度、触发、响铃开始 / 结束、挑战开始 / 结果、延后、关闭、恢复、看门狗异常 |
| PROTECT | 防护状态变化、拦截动作、绕过风险、服务状态变化 |
| SYS | 进程启动、开机、备份与导入、崩溃 |
| 任何模块 | 休息、白名单、已解锁期间保持静默 |

具体的 App 状态集合（例如：放行、监测中、已锁定、临时解锁、应急放行、桌面倒计时）需在实现前对照 `SPEC.md` 与 `docs/specs/focus.md` 定稿，见 §13。

## 8. 存储与命名

```
filesDir/
  log/
    journal/             # A 级，分片滚动，合计约 4MB（每片 512KB）
      journal-000001.log ...
    trace/               # B 级，分片滚动，合计约 6MB（每片 1MB）
      trace-000001.log ...
  crash_log.txt          # 沿用
```

实现代码在 `com.decikeeper.app.logging` 包（不叫 `log`，因为仓库 `.gitignore` 忽略所有 `log/` 目录）。

- 总预算与现状一致，不超过 10MB。账本很小（旧日志 A 级两天约 250KB），因此 4MB 够用数月；其余给诊断分片。
- **不再整文件截断。** 分片写满后直接删除最老整片，不再 `readLines` 后整体重写。
- **内存攒批。** 判定记录先写内存，每隔约 5 秒批量写盘；状态变化立即写；服务销毁或系统内存紧张时也写。被杀时最多丢最近几秒。
- **一个写入线程。** 单线程队列写盘，不在判定循环里开关文件。
- 内存缓冲只写不读，业务代码无读取入口。
- **容量估算**：按 2026-10-10 样本合并后约每天 1.5MB 诊断，6MB 约可回看 3 到 4 天；用户多应用切换、反复锁解时会缩短。该数字为估算，真机跑几天后按实测调整。
- 以上数字（4MB / 6MB / 5 秒）为初始值，实现时放成常量。因为 A 已带精简证据、B 只承担"无事件时的证据"和完整证据块，B 的实际需求可能小于 6MB，真机验证后可下调，把空间让给 A 或直接减小总占用。

**导出格式**：导出文件分两个分区，**上半部分是全部 A（永久账本），下半部分是全部 B（近期诊断）**，各自按时间排序，每行开头带等级标记，与 §6 分级一致。需要某个 A 事件的前因时，按它的时间到下半部分的 B 里查找。（此前设想过把 A、B 按时间交错成一条时间线，用户更倾向两个分区，已按用户意见改为分区。）

- `A` = A 级，永久账本（存于 `journal/`）。
- `B` = B 级，近期诊断（存于 `trace/`）。
- C 级不落盘，没有标记。
- 文件头写图例，并分别给出 A、B 的时间范围，让读者知道诊断部分只能回看到什么时候；每个分区开头有一行带本次导出条数的标题。
- 需要只看重要事件时，用 `grep '^A'` 筛选。
- 同一个 8 行证据块视为一个整体，只在首行带标记，后续行缩进，导出和筛选时不得拆开。

**B 级同类重复折叠**：与同一类（按记录首行标签区分，如 `[FG_DECISION]`、`MonitorService: [EVENT] type=SUSPECT`）上一条相比，只有时间戳、各类 id、毫秒耗时不同的记录不再落盘，只累计次数；该类内容变化或导出时补写一行 `[REPEATED] of=<类别> n=<次数> first=<首次时间>`。包名、状态、原因码等实质变化一定产生新记录。A 级不折叠。

```
# ---------- A 永久账本（本次导出 2 条）----------
A 2026-10-10 09:12:40.000 [LOCK_RESULT] packageName=com.ss.android.ugc.aweme result=LOCKED reason=WINDOW_ATTACHED evidence=fg:com.ss.android.ugc.aweme src:EVENTS reason:EVENTS_BUSINESS_MATCH
A 2026-10-10 09:14:15.000 [LOCK_RESULT] packageName=com.ss.android.ugc.aweme result=LOCKED reason=EXPIRY_RELOCK
# ---------- B 近期诊断（本次导出 3 条）----------
B 2026-10-10 09:12:38.000 [FG_DECISION] ...
B 2026-10-10 09:12:58.000 [REPEATED] of=[FG_DECISION] n=14 first=2026-10-10 09:12:41.000
```

**来源字段与真实 Android 接口对照**（写在每个文件头部一次）：

| 来源枚举 | 真实接口 / 实体 |
|---|---|
| `EVENTS` | `UsageStatsManager.queryEvents(begin, end)` 返回的 `UsageEvents`，关注 `Event.MOVE_TO_FOREGROUND` / `ACTIVITY_RESUMED` 等 |
| `USAGE_STATS` | `UsageStatsManager.queryUsageStats(INTERVAL_DAILY, begin, end)`，读取 `UsageStats.lastTimeVisible` |
| `A11Y` | `AccessibilityService.getWindows()` 与 `AccessibilityEvent`（`TYPE_WINDOW_STATE_CHANGED`） |
| `CACHE` | 应用内存变量 `MonitorService.mLastTopPackage`，不是 Android 接口 |
| `HISTORY` | 应用自己的前台历史记录，不是 Android 接口 |

## 9. 门面设计（草案）

```kotlin
object AppLog {
    fun event(draft: NormalEventDraft)          // A 级，进 journal
    fun trace(tag: String, line: () -> String)  // B 级，进 trace，lambda 延迟构造
    fun dev(tag: String, line: () -> String)    // C 级，仅 logcat，release 默认丢弃
}
```

- 取代 `LogExporter.i/w/d/logEvent` 与裸 `Log.*`。
- `NormalEventLogger` 保留 `eventId` / `seq` 语义，不再各文件各自构造，改为由门面持有单例。
- 每行不再重复写 `processInstanceId`，只在进程启动行写一次（该调整会改变行格式，需用户同意）。
- 通过一条治理测试禁止业务和服务代码直接调用 `android.util.Log.*` 或 `LogExporter.logEvent`。

## 10. 日志样例预览

以下仅为格式示意；时间和前台应用来自真实样本，部分字段为示意，最终以代码为准。

5 分钟静止场景（11:13 到 11:18）：永久账本 0 行，近期诊断 9 行（原来 5625 行、1.6MB）：

```
11:13:06 → 11:16:28  fg=com.bbk.launcher2         src=CACHE        reason=EVENTS_EMPTY_30S->STATS_EMPTY->CACHE_HIT  n=368
11:16:28 → 11:16:32  fg=com.netease.uuremote      src=EVENTS       reason=EVENTS_BUSINESS_MATCH                     n=8
11:16:32 → 11:16:36  fg=com.android.filemanager   src=EVENTS       reason=EVENTS_BUSINESS_MATCH                     n=7
11:16:36 → 11:17:06  fg=com.netease.uuremote      src=EVENTS       reason=EVENTS_BUSINESS_MATCH                     n=56
11:17:06 → 11:17:06  fg=NONE                      src=EVENTS       reason=EVENTS_BUSINESS_MATCH                     n=1
11:17:07 → 11:17:17  fg=com.netease.uuremote      src=USAGE_STATS  reason=EVENTS_EMPTY_30S->USAGE_STATS_MATCH       n=19
11:17:17 → 11:17:46  fg=com.netease.uuremote      src=CACHE        reason=EVENTS_EMPTY_30S->STATS_EMPTY->CACHE_HIT  n=54
11:17:47 → 11:17:49  fg=com.bbk.launcher2         src=EVENTS       reason=EVENTS_BUSINESS_MATCH                     n=4
11:17:49 → 11:18:06  fg=com.decikeeper.app        src=EVENTS       reason=EVENTS_BUSINESS_MATCH                     n=32
```

永久账本一天的样子：

```
2026-10-10 07:30:02 [PROCESS_START]     version=0.3.0 device=V2425A android=16 instance=b02f4a3d
2026-10-10 08:00:03 [ALARM_TRIGGER]     alarmId=1 triggerAt=1786579202596 result=TRIGGERED
2026-10-10 09:12:40 [LOCK_RESULT]       packageName=com.ss.android.ugc.aweme result=LOCKED reason=WINDOW_ATTACHED
2026-10-10 09:12:44 [TEMP_ACCESS_START] requested=90s granted=90s result=APPROVED borrowed=true quotaCost=1.5min
2026-10-10 09:14:15 [LOCK_RESULT]       packageName=com.ss.android.ugc.aweme result=LOCKED reason=EXPIRY_RELOCK
2026-10-10 11:20:06 [QUIET_RUN]         state=ALLOW since=09:30:00 durationSec=7200 fgSwitches=43 locks=0
```

状态迁移发生时，`journal` 记事件，同时把内存里最近几秒判定和 8 行证据块写入 `trace`，用于事后排查。

## 11. 涉及文件与需要同步退役的约束

| 文件 | 动作 |
|---|---|
| `service/ForegroundDiagnosticTracker.kt` | 删除 |
| `test/.../ForegroundDiagnosticTrackerTest.kt` | 删除 |
| `service/MonitorService.kt` | 去掉对诊断器的调用（`analyzeAndLogUsageEvents`、`analyzeAndLogUsageStats`、`logResolveTrace`、`logRawAccessibilityEvidence`）和仅用于诊断的 180 秒 `queryEvents`，其余日志迁到门面；不改判定逻辑。`traceAndReturn` 中的 `CoreService.captureWindowSnapshot(time)` 必须保留：它刷新 `lastVisiblePackagesFromWindows`，被前台可见性判定读取 |
| `manager/expiry/ExpiryForegroundLog.kt`（新增） | 承接 `ExpiryCoordinator` 用到的两条低频日志（`[EXPIRY_FOREGROUND_SNAPSHOT]`、`[FG_RAW_A11Y]`，仅到期评估和桌面等待超时各写一次），原样迁出；门面落地后迁入门面 |
| `data/LogExporter.kt` | 拆出存储层；导出压缩改为读取 journal + trace；删除 `exportDropPatterns` |
| `data/EventLog.kt`、`data/NormalEventLog.kt` | 并入门面；旧 `[EVENT]` 格式停用，旧日志读取保持兼容 |
| `util/DiagnosticLogCompressor.kt`、`data/ForegroundDetectionLog.kt` | 按新状态变化规则重写或并入门面 |
| 约 100 个调用点文件 | 一次性切到门面 |
| `docs/ai/LOG_EVENT_MIGRATION.md` | 退役，归档到 `docs/archive/` |
| `test/governance/LogEventMigrationInventoryTest.kt` | 退役。该测试断言 `LogExporter.kt` 与 `HEAD` 字节级一致，与本次重构直接冲突，必须由用户批准后替换为新的治理测试，并在 `HISTORY.md` 记录决策 |

## 12. 验收标准

1. 同样的 5 分钟静止场景（前台一直是桌面或白名单应用），落盘行数从约 5600 降到个位数。
2. 白名单 → 黑名单 → 申请解锁 → 回到桌面 → 休息结束进入锁机，一轮下来每个迁移各有且仅有一条记录，且带证据。
3. 连续运行 3 天后，`journal` 中的闹钟、规则、额度、权限、崩溃记录仍完整可读。
4. 进程被强制停止后，重启导出的日志里仍能看到迁移前最近几秒的判定。
5. 导出文件仍满足 `HISTORY.md` 约定的"4 要素反馈范式"（机型 / 版本 / 复现 / 取证日志）。
6. 治理测试通过：业务代码无直接 `Log.*` / `LogExporter.logEvent` 调用，且日志代码无反向读取。
7. 现有锁屏、额度、闹钟的 JVM 测试全部保持通过（本次不改业务行为）。
8. 每个事件类型在"事件 → 模块"登记表中有且仅有一个归属。

## 13. 待定项（实现前须定稿）

1. App 状态集合与迁移图，对照 `SPEC.md` / `docs/specs/focus.md`。
2. `journal` / `trace` 的体积分配与批量写入间隔，真机验证后调参。
3. `CrashRecorder` 是否并入统一存储，还是继续独立文件。
4. 旧日志兼容范围：已安装版本升级后，旧 `ironwill_event_log.txt` 如何迁移或只读保留。
5. 是否需要重新加回 Vivo 前台检测诊断（作为 `trace` 级、默认关闭、可由开关打开的新实现）。
6. 跨模块文件（`LockWindowManager`、`CoreService`、`LockAlarmOverlay`）的逐点归属。
7. 来源字段是否每行写完整接口名，默认方案为每行只写枚举、对照表放文件头。

## 14. 实施顺序（草案）

1. 删除 `ForegroundDiagnosticTracker`，立即消除约 99% 的噪声。**已完成（未提交）**：编译通过，`testDebugUnitTest` 252 个测试类、1758 个用例，0 失败 0 错误，11 个跳过（本分支改动前未单独跑基线，跳过数是否一致待确认）。
2. 新增门面、"事件 → 模块"登记表与分层存储（含单元测试）。
3. 状态迁移触发点接入（锁屏、解锁、桌面倒计时、休息结束）。
4. 按模块分批提交，一次性迁移全部调用点。
5. 退役 `exportDropPatterns`、迁移表与冻结测试，补治理测试。
6. 真机验证后调参，归档本计划，结论回写 `SPEC.md` / `HISTORY.md`。
