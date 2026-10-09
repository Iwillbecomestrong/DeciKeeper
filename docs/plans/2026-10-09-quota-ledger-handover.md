# 额度流水与特权统计修复 · 交接文档（2026-10-09）

> 状态：**进行中，额度流水页仍有未解决的问题**（用户 10-09 14:40 口头反馈，具体现象待用户补充，见 §5）。
> 本文是计划 / 交接，不是产品真相；确认后的规则已回写 `docs/specs/*.md` 与 `HISTORY.md`（DECISION-45）。

## 1. 工作位置

| 项 | 值 |
| --- | --- |
| 仓库 | `G:\IronWIl0329\IronWill-OpenSource` |
| worktree | `.claude/worktrees/fix-rest-quota-refund`（分支 `worktree-fix-rest-quota-refund`，基于 dev） |
| 提交 | `60fdd50 wip(quota)`（过程快照，待按主题拆分）→ `3ce5db4 merge`（合并 dev `2403926`） |
| 工作区 | 干净；`local.properties` 是从主仓库复制来的签名配置，被 `.gitignore` 忽略，**不要提交、不要在对话里贴内容** |
| 构建 | `ANDROID_HOME='I:\AndordStudio' ./gradlew.bat --console=plain :app:testDebugUnitTest`、`... :app:assembleRelease`（Release 产物 `app/build/outputs/apk/release/app-release.apk`） |
| 安装 | 覆盖安装，**严禁 `adb uninstall`**：`adb push` 到 `/data/local/tmp/app-release.apk` → `adb shell pm install -r -t -d -g --user 0 ...`；Git Bash 下要加 `MSYS_NO_PATHCONV=1`，adb 在 `I:/AndordStudio/platform-tools/adb.exe` |
| 真机 | `192.168.1.137:35275`（V2425A，已装 14:38 的包，含合并后的全部改动）；`192.168.1.100:37923` 上是 10:21 的旧版 |

## 2. 起因（用户 10-08 晚的真机流水）

1. 21:39 申请 22 分钟的休息（只借了次数，时长没借），22:00 自然进入空档期，退回 11 分钟；
2. 结余 20m 时休息 20m，流水里结余却剩 3m；
3. 常规休息「次数 -2」、休息 0.5 分钟「次数 -1」；
4. 特权统计图两次 4 小时的特权合计 9h2m，10/06 一根柱子 6.5h。

## 3. 已确认的结论与已做的修改

### 3.1 退款 11 分钟：不是当前代码的 bug

`MonitorService.endFreeTimeEarly` 当前公式是「只退未用、已用不退」，22/21 应退约 1 分钟。11 是**旧公式**的结果：`86b31df`（10-08 17:09，合入 dev 为 `c135654`）刚删掉了「休息跨时段末尾、重叠部分 ×0.5」的 Early Off Bonus，旧包会多退约一半。用户 10-08 测试时装的是更早的包。**退款逻辑未改，保持 dev 原样**（中途我误改过两版，已全部撤回，不要再动）。

### 3.2 结余对不上（20−20=3）

`CreditManager.consumeSeconds` 扣前序段时没套用 `getRemainingMinutes` 的过滤（跨业务日、改动后失去结余资格、无限额段不结余）。现在两者共用 `getSegmentQuotaRemainingSeconds`。`ConsumeMatchesRemainingTest` 已验证旧逻辑下失败。**这是推断，没拿到用户当时的时段配置，不排除另有原因。**

### 3.3 次数单位

内部用 half-unit（0.5 次 = 1），流水原来把它当「次」写。现在新记录 `costCount` 统一为「次」；旧的 `NORMAL`/`BORROW` 记录读取时除以 2（`RestLedger.costCountInTimes`）；`balanceCount` 恒为 half-unit。结余展示不再 `toInt()` 截断。

### 3.4 额度流水页（`StatsHistoryActivity` + `RestLedger.kt`）

- 筛选顺序：全部 / 额度发放 / 提前退还 / 常规 / 借用 / 冷却中 / 频繁返回扣减 / 特权。入流 = 额度发放 + 提前退还；出流 = 常规 + 借用 + 冷却中 + 频繁返回扣减；**特权是独立池子**，不参与对账。
- 休息与临时解锁合并在常规 / 借用 / 冷却中里；借用与冷却中可同时成立（`REST_BORROW_COOLING` 等），这样的记录同时出现在两个筛选里。
- 类型字符串：`REST` / `TEMP`（可带 `_BORROW`、`_COOLING`）、`GRANT`、`PRIVILEGE`、`PENALTY`、`REFUND`；旧版 `NORMAL`/`BORROW` 按成本 ≥ 申请 ×1.9 近似判冷却。
- 新增记录：临时解锁（只记收费的）、额度发放（`QuotaGrantRecorder`，监控轮询里每个业务日每个时段记一笔，时间取时段起点）、借用记录写**总扣减**（当前段扣 + 下一段预扣，来自 `TemporaryAccessQuotaPlan`），原因里写明借了多少。
- 「违规惩罚」改称「频繁返回扣减」（`typeName`，规格 §4.5 要求用户可见文案叫「扣减」）。
- 页面顶部一行入流/出流汇总（`RestLedger.summarize` / `summaryText`），入流小于出流时提示「记录可能不全」。
- 「今天 / 近 7 天 / 本月」按**业务日**切（`HistoryRecordFilter.filterQuota(businessDayStartMinutes=…)`）。

### 3.5 特权

- 统计整段计入（含空档期分钟，key `privilege_usage_<业务日>_seg_<小时>_gap`），按业务日归属（`recordPrivilegeRestUsage`）。
- 结算：`resolvePrivilegeSettlementTime`（到期与 24:00 取较早，原来 24:00 分支排在前面，结算拖过午夜会多算）、`clampPrivilegeStopAt`（结算不晚于计划结束）。
- **可用范围限定在业务日内**：开始不早于生效时间，结束晚于开始、最晚 24:00（`data/PrivilegeWindow.kt`；读取旧值时开始被收窄、跨午夜回落默认；规则参数页失焦即存时严格校验新输入，`GlobalParamsFragment` 里的 `privilegeWindowBusinessDayStart`）。
- 删除：「反向同步首段」全部残留、申请 / 调整时长处的 24:00 截断。**保留**结算处的 24:00 兜底，只为旧版本持久化的跨午夜会话（`TestScenarioManager` 里还有对应场景）。
- 历史统计（10/06、10/07 偏高）**不回算**（用户决定「将就」）。原因：当时没有「特权不得早于业务日开始」的约束 + 旧版只计勿扰时段内分钟 + 统计按自然日读业务日的 key。多算 62 分钟的完整成因**没有在真机数据上证实**。

### 3.6 日期口径（用户原则）

时间规则相关（休息 / 临时解锁 / 特权统计、额度流水、额度刷新）一律**业务日**；起床 / 闹钟 / 应用使用**自然日**。已写入 `SPEC.md` §5.2、`docs/specs/statistics.md` §4A.0，休息页 / 起床页图表说明末尾各有一句口径提示。

## 4. 未做 / 已知局限

- **统计图「今天」的范围解析**（`StatsDateRangeResolver`）仍按自然日取 key，生效时间之前的凌晨读不到当天业务日的数据。只写了文档，没改。
- `wip(quota)` 提交混合了多个主题，合回 dev 前建议拆成：额度流水 / 扣费同口径 / 特权范围与统计口径。
- 入流/出流汇总：**额度发放只从本版本起记**，更早的范围必然提示「入流小于出流」，这是预期，不是 bug。
- 失焦即存校验是逐个字段的，往后挪范围要先改结束再改开始。

## 5. 额度流水「账目对不上」（用户 14:41 反馈，已定位到两类原因，待真机复测）

### 5.1 用户的现象（设备 192.168.1.137，当天「今天」范围）

用户的核对公式：**入流 = 出流 + 当前结余**。汇总行 入流 66.1m / 14 次，出流 22.1m / 4.5 次，首页当前可用 26m / 8 次：

- 时长 66.1 − 22.1 − 26 = **18.0m**，次数 14 − 4.5 − 8 = **1.5 次**，对不上；
- 逐笔看：12:48 变动后 46.1m / 10.5 次 → 13:16 用 1.1m / 0.5 次，变动后 45m / 10 次（接得上）→ 14:14 用 1m / 0.5 次，变动后 32m / 8.5 次：**13:16 → 14:14 之间少了 12m / 1 次**；14:14 变动后 32m / 8.5 次 → 首页 26m / 8 次：**又少了 6m / 0.5 次**。12 + 6 = 18，1 + 0.5 = 1.5，恰好等于汇总差额。结论：**汇总公式没算错，是有三笔左右的出口没进流水**（每笔 6m / 0.5 次这个形状很整齐，但具体是什么出口没确认）。

### 5.2 原因一：没记进流水的出口

- 设备在 14:38 才装到含「临时解锁流水」的包；此前的包**不记临时解锁**，14:01 那个包更是没有额度流水新功能。14:38 之前的临时解锁、以及之后任何没覆盖到的出口都不会出现在流水里。最可能就是这个，**需要用户回忆 13:16 ~ 14:38 有没有用过临时解锁**（用户 14:50 回复「好像用过临时解锁」，原因一基本坐实；每笔 6m / 0.5 次的具体口径仍待新包复测确认），并在新包上复测（做一次临时解锁，看流水是否多一行、前后能否接上）。
- 代码里仍然**不进流水**的扣额度路径（查 `consumeMinutes` / `consumeSeconds` / `addUsedMinutes` 调用得出）：
  1. `MonitorService` 约 2380 行 `CreditManager.addUsedMinutes(this, 1)`：监控每分钟对「黑名单但没被拦住」的应用计费 1 分钟（`SegmentBlacklist.shouldBill`），**只扣时长，不扣次数、不写流水**；
  2. ~~`CreditManager.adjustScheduleRestTime`：闹钟起床奖惩~~：**已核对，没有任何调用方（死代码），不会动额度**。**用户决定：闹钟起床的奖惩不属于额度，不记进额度流水**（闹钟 / 起床是自然日口径的另一条线）。若以后真的让它改额度，再另行讨论，不要默认补流水；
  3. 规则 / 时段被修改后额度重算（`getSegmentProration` 折算 / 改了某段额度），余额会变但没有任何记录。**用户 14:55 要求：加上这个记录**（新增流水类型「额度重算」），**已实现，见 5.7**。
  次数的 1 / 0.5 次缺口说明至少有一部分是**扣次数的出口**（临时解锁、休息、频繁返回扣减）；纯时长的 `addUsedMinutes` 解释不了次数缺口。

### 5.3 原因二：额度发放的「变动后」不对（已修）

12:45 那行「额度发放 +35m / +7 次」写着变动后 26m / 8 次，但它后面 12:48 的记录是 46.1m / 10.5 次。26m / 8 次恰好就是用户 14:41 首页显示的**当前**结余：这条发放是 14:38 装包后**第一次检测到该时段**才补记的，时间戳取时段开始（12:45），结余却是记录那一刻的。已改：时段开始超过 2 分钟才检测到时，按「上一笔流水的变动后 + 本次发放」估算结余，并在原因里注明「补记」（`QuotaGrantRecorder`）。

### 5.4 已做：流水写出「使用前」，并提示缺口（让用户一眼分清「算错」还是「漏记」）

- 每笔流水多一行「使用前：X m / Y 次」（= 变动后 + 变动量）；借用记录因变动量含下一段预扣还原不出，特权是独立池子，都不显示；
- 「全部」视图里，相邻两笔若「上一笔变动后 ≠ 下一笔使用前」，中间插一行红字提示「⚠ 这两笔之间账面少了 12m / 1次 …」（`RestLedger.buildEntries` / `gapText`，`item_history_rest_gap.xml`）；跨业务日不比较；筛选后不显示缺口；
- 单测 `RestLedgerTest` 用上面的真机数字覆盖（缺口 12m / 1 次、接得上的链、借用 / 特权不误报、跨业务日不比较），`QuotaGrantRecorderTest` 覆盖补记估算。
- **两个判断口径**：缺口 > 0 = 出口没记（或上一笔结余偏高）；缺口 < 0 = 入流没记（或上一笔结余偏低）；两种可能都在提示文字里写出，不替用户下结论。

### 5.5 接手后的下一步

1. 装新包后让用户做：一次临时解锁、一次休息，看流水是否多出对应行、「使用前」与上一笔「变动后」是否一致；
2. 若仍出现缺口，缺口大小 + 前后两笔的时间，可以反推漏记的是哪类出口（6m / 0.5 次这类整齐数字优先查临时解锁 / 频繁返回扣减 / 冷却翻倍）；
3. 决定是否为 `addUsedMinutes` 的每分钟计费、`adjustScheduleRestTime` 补流水类型（需要用户确认口径：它们算「出流」的哪一类？）。

### 5.6 重要线索：临时解锁生效期间可能被「每分钟计费」又扣一次（待验证，未改）

`MonitorService` 约 2378 行：前台应用不被拦（`!shouldBlock`）时，若 `SegmentBlacklist.shouldBill`（黑名单命中 + 在勿扰时段内 + 非自由时间 + 距上次计费 ≥ 60 秒）就 `CreditManager.addUsedMinutes(this, 1)`，只扣 1 分钟时长，不扣次数、不写流水。

- 休息 / 特权期间 `isFreeTime=true`，不会走到计费；**唯一会走到的是「临时解锁生效中」**（`calculateIsBusinessBlocked` 把 `isTemporaryAccess` 算成不拦）。而临时解锁是**申请时一次性按时长预扣**（`focus.md` §4.3），这样同一次临时解锁在使用期间又被每分钟扣 1 分钟，等于**扣两遍**。
- 与真机缺口吻合：3 分钟的临时解锁 = 预扣 3m + 使用中最多每分钟计费 3m = **6m / 0.5 次**；缺口 12m / 1 次 = 2 次，6m / 0.5 次 = 1 次，合计 3 次 18m / 1.5 次。
- 规格里没有「每分钟计费」的任何描述（只有 `SegmentBillingTest` 锁住了触发条件），疑似早期「按使用量扣费」模型遗留。**需要用户确认是否是 bug，确认后再删或限制为非临时解锁场景**；修之前先在新包上复测：做一次 3 分钟临时解锁，看额度是否一次性减少 3m 还是 6m。

### 5.7 已实现：额度重算流水（用户要求；单测全绿，真机未验证）

实现：`QuotaRecalcRecorder.around`（包住 `TimeRuleManager.saveTimeSegments`，内部实现拆成 `saveTimeSegmentsInternal`）；`CreditManager.withDayChangeSettlement` 标记业务日切换结算，期间不记；`RestLedger.TYPE_RECALC` / `LedgerFilter.RECALC`（排在提前退还之后）；汇总里时长、次数各自按正负归入入流 / 出流。测试：`QuotaRecalcRecorderTest`、`RestLedgerTest`。以下是当时的设计要点：

时段被修改后（额度上限 / 时段起止 / 修改折算）当前结余会变，应写一条类型「额度重算」的流水，让缺口有出处。设计要点（尚未动手）：

- 类型 `RECALC`，出流 / 入流方向由变化正负决定（结余减少 = 出流，增加 = 入流），汇总里归入对应方向；筛选里加一项「额度重算」（顺序放在额度发放、提前退还之后）；
- 记录点：`TimeRuleManager.saveTimeSegments` 是时段写入的唯一出口（调用点：适应期立即生效、有利修改立即生效、pending 到期并入、混合保存，见 `TimeRuleManager` 约 2293 / 3143 / 3247 / 3552 行）；在写入前后各取一次 `CreditManager.getRemainingMinutes` / `getRemainingUsableCount`，**有变化才记**，`costMinutes = 变动前 − 变动后`（减少为正），写明「时段 X 额度 a → b」；
- 注意：`getRemainingMinutes` 内部会触发 `checkDayChange` → `applyPendingToActive` → `saveTimeSegments`，要避免递归和在业务日切换时误记（日切换是额度重新开始，不是重算）；
- 测试：改某段额度前后结余变化被记录一次；无变化不记；业务日切换不记。

### 5.8 其它可重点怀疑 / 核对的点

1. **借用记录的总扣减口径**：`performGrant` 在 `borrowPlan` 存在时记 `current + next`，但「变动后结余」只反映当前可用，二者对不上是设计如此（见 `RestLedger` 注释）；若用户期望结余能逐笔对上，需要重新设计（例如把下一段预扣单独记一笔）。
2. **额度发放金额**：`QuotaGrantRecorder` 取 `getSegmentProration(...).effectiveLimitMins`（该段自身额度，不含结余），时间取时段起点；检查跨天段、修改过的段（`lastModified` 折算）、特权段是否记得合理。
3. **入流/出流对账**：提前退还只退时长不退次数；`REFUND` 的 `costCount` 恒为 0；频繁返回扣减的 `costCount`/`costMinutes` 是否与实际一致；借用的下一段预扣会在下一段的「额度发放」之外再次减少可用额度，对账时是否被重复计算。
4. **临时解锁流水**：只记 `chargeSnapshot.dndSec > 0` 的收费情况；非计划路径（`consumeGapOverflowCharge`、二级应急）是否漏记或重复；`countBorrowedFromNext` 路径的次数口径。
5. **旧记录兼容**：旧 `BORROW`/`NORMAL` 的次数换算与冷却近似识别可能误判。
6. 回到 §3.2：若「结余对不上」依旧复现，需要用户给出当时的时段配置（时段、额度、生效时间），再用 `ConsumeMatchesRemainingTest` 的方式复现。
7. 日志在 `/sdcard/Android/data/com.decikeeper.app/files/app_log.txt`，但只有最近几分钟，复现后要**立刻**拉取；`/data/data` 下的数据拿不到（release 包，不可 `run-as`）。

## 6. 验证清单（真机，均未验证）

1. 规则参数页：生效时间 08:00 时，特权开始输入 07:00 失焦后应被拒绝并还原；旧设置里更早的开始被收窄到生效时间；
2. 统计页：休息页末尾有「按业务日统计」，起床页有「按自然日统计」；
3. 额度流水：「今天」按业务日切、筛选顺序、顶部入流/出流、借用/冷却中记录、特权显示「特权池 / 不占用休息额度」；
4. 新申请一次特权，统计图按业务日显示满时长（含空档期）；
5. 先确认手机装的是 `c135654` 之后的包，再复测「只借次数、自然进空档期」，期望退约 1 分钟而不是 11。

## 7. 关键文件

| 作用 | 文件 |
| --- | --- |
| 流水口径、筛选、汇总 | `app/src/main/java/com/decikeeper/app/manager/statistics/RestLedger.kt` |
| 额度发放 | `.../manager/statistics/QuotaGrantRecorder.kt`（挂钩在 `MonitorService` 轮询） |
| 范围按业务日切 | `.../manager/statistics/HistoryRecordFilter.kt` |
| 流水页 UI | `.../ui/statistics/StatsHistoryActivity.kt`、`res/layout/activity_stats_history.xml`、`ui/StatisticsFragment.kt` |
| 休息 / 临时解锁 / 借用记录写入 | `.../ui/LockWindowManager.kt`（`performGrant`、`startTemporaryAccess` 末尾） |
| 扣费与结余同口径 | `.../manager/CreditManager.kt`（`getSegmentQuotaRemainingSeconds`、`consumeSeconds`） |
| 特权范围 / 结算 | `.../data/PrivilegeWindow.kt`、`.../data/PrivilegeSettlement.kt`、`TimeRuleManager.kt`、`CreditManager.kt` |
| 规则参数页校验 | `.../ui/GlobalParamsFragment.kt`、`GlobalParamsActivity.kt` |
| 测试 | `RestLedgerTest`、`QuotaGrantRecorderTest`、`QuotaLedgerBusinessDayRangeTest`、`ConsumeMatchesRemainingTest`、`PrivilegeWindowTest`、`PrivilegeUsageRecordingTest`、`PrivilegeSettlementTest` |
| 规格 / 决策 | `docs/specs/focus.md` §3.3A / §5.1，`docs/specs/statistics.md` §4A，`SPEC.md` §5.2，`HISTORY.md` DECISION-45，`CHANGELOG.md` |

## 8. 工具与流程提示（踩过的坑）

- 本环境没有 `python`/`python3`：别用脚本改文件，用编辑工具；复杂的 `sed`/带变量的命令会被 worktree 隔离钩子拒绝，拆成简单命令。
- Gradle 需要 `ANDROID_HOME='I:\AndordStudio'`（worktree 里的 `local.properties` 只为 Release 签名而复制，sdk 路径用环境变量）。
- 全量单测约 2 分钟，Release 约 2 分钟；`TestScenarioManager`（`src/test/.../testutil`）是一个场景工具，会随单测编译，改了公共 API 要同步改它。
- 退款逻辑不要再动（§3.1）。
- 合回 dev 前按 `AGENT_CORE.md` §7 走 Convergence Gate：规格已回写，测试与 Release 构建已过，真机验证见 §6。
