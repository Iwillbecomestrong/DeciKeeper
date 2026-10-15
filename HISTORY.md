# 决定守护 (DeciKeeper) 架构决策与经验档案 (HISTORY)

> **定位**：本文件是「决定守护」项目的**历史决策真相（Historical Decision Truth）**，沉淀核心架构演进决策、高价值教训与防回归红线。
> 开源前完整的问题追踪与历史排查记录详见 [docs/history/issue_tracker.md](docs/history/issue_tracker.md)。

---

## 1. 关键架构决策记录 [DECISION]

### 固定响铃的共享通道跟随所选强度（2026-10-14）

- 旧固定模式把共享闹钟通道设到约 80%，只对自身播放器使用三次方衰减，导致同通道系统闹钟可能明显更响。用户确认通道应跟随所选强度，避免固定抬高。
- 固定模式直接使用 `ringLevelValue` 设置通道档位，自身播放器增益改为 1；试听采用同一换算。沿用通道最高约 80% 的上限（仅最高强度达到上限），渐进曲线与音量恢复链不变，不承诺不同铃声响度相同或相差 1 dB。

### 内置时段与规则参数采用用户提供的待生效配置（2026-10-14）

- 从用户提供的 schemaVersion=2 设置备份中读取 `decikeeper_rules_pending` 的时段/参数，以及 ledger 中待生效的每周特权 2 次/480 分钟；没有待生效值的字段按实际使用位置读取，刷新时刻为 ledger 中的 06:45，而不是 rules 中残留的 12:00。
- 「一键设置」更新为上午 08:30–12:00、下午 12:45–17:00、工作日晚上 17:45–21:30、深夜 22:30–06:45；每段 3 次，休息分别 30/30/30/10 分钟。缺少配置时的参数默认值同步调整；保留旧存储值和旧键迁移，不导入个人名单、时间戳或额度使用记录。

### 渐进响铃不再立即抬高共享闹钟音量（2026-10-14）

- 原实现开始播放时把 `STREAM_ALARM` 锁到约 80%，只有决定守护自己的播放器增益渐进；同时间系统时钟不受这份增益控制，可能立即变响。零音量复现测试期望 1 档、旧代码实际设到 12/15 档。
- 渐进模式改为共享通道从最低非零档随现有 30 秒阶梯提高，锁住当前目标，保留最大声音上限；播放器采用平方增益与通道共同爬升。固定模式保留原有响度计算。试听、第一关及第二关使用同一模式换算，播放前先设目标，播放失败或已有静音/退出路径恢复原音量，包括 0。
- 不修改系统时钟的数据或振动，不承诺系统时钟唤醒决定守护；新响度曲线和 OEM 同时响铃仍待真机校准。

### 桌面图标采用用户提供的灯塔原图（2026-10-14）

- 替换五档密度的桌面图标资源及 `docs/images/logo.png` 原图；直接缩放原图，不重绘、不做圆形裁切，不新增圆形图标配置。独立通知小图标与业务逻辑不变。

### 系统闹钟入口明确为单向创建（2026-10-13）

- 列表「同步」更名「一键创建系统闹钟」，长文案移入独立行；首次从空列表新增保存真实闹钟后，在保活引导结束后建议创建相同时间的系统闹钟，提示本地只展示一次。
- 复用现有时钟启动流程与重复日期参数；系统时钟仍需用户确认并保存，后续修改、删除和响铃状态分别管理。额外提醒降低风险，不承诺绝不延迟或漏响。

### [DECISION-55] 回桌面豁免：由扣额度/计数改为“3 秒豁免 + 30 秒冷却”（2026-10-15）

- **背景**：额度耗尽后反复“回桌面 → 打开受限小窗”，每次回桌面都有几秒豁免，可以一直刷；原扣减受剩余额度与借用上限约束，扣不到就不追扣。
- **第一性原理**：豁免唯一正当用途是给用户几秒关掉桌面上的受限小窗；被滥用的也是这几秒。要限制的只有两次豁免之间的间隔。
- **决策**：① 回桌面（锁屏页按钮或系统 Home 手势）豁免 3 秒；② 豁免开始后 30 秒内不再豁免，冷却固定，不被重复请求、点击或新的拦截续期；③ 不再计数、不扣时长/次数/借用、不延长冷却，与剩余额度无关；④ 冷却内锁屏页提示剩余等待；⑤ 删除「频繁返回扣减」的统计明细筛选，历史记录只读并仍计入出流；⑥ 临时解锁保持不变（仍扣次数，作为刚需短时使用的正门）；⑦ 用户可见文案不用“缓冲”，统一说“豁免”。
- **全屏应用**：真机日志显示，冷却内豁免为 0 时，全屏微信回桌面约 0.2 秒内会被存在性追踪误锁回（系统要 1–2 秒才确认应用已关）。因此回桌面时检测受限应用窗口是否近似全屏（无障碍窗口范围）：全屏只给存在性追踪 3 秒“等待关闭确认”，不放行使用、不入冷却；小窗才适用 3 秒豁免 + 30 秒冷却。真机内部日志进一步证实：点击后 45 毫秒内，前台判定仍用点击前的陈旧 UsageEvents（仍是该应用在前台）再次上锁，用户只看到按钮闪了一下；因此全屏返回还要设置 `homeClickPendingPackage`，抑制对同一应用的陈旧前台重锁，直到用户真正离开该应用；窗口树取不到根节点的大窗口按全屏处理。
- **演进**：先后做过“30 秒内 5 次点击进入严格状态、新拦截续期”（桌面小窗关不掉时会无限被弹回）、“第 5 次不豁免 + 固定 60 秒 + 手动出口”，最终因规则过多、状态复杂而合并为单条冷却规则。曾考虑 App 主动关闭受限小窗，因无障碍点关闭按钮依赖厂商界面而放弃；曾考虑每周手动豁免次数作为规则参数，因成本高、价值有限，暂缓。
- **防坑警告**：不得让点击或拦截续期冷却；不得把间隔改回按点击次数计数；不得删除系统识别用的去抖逻辑来换取“立即拦截”；旧扣减账本与历史流水只读保留。
- **局限**：每 30 秒仍能拿到 3 秒（约 10%）；错过豁免最多等 30 秒；系统 Home 手势与桌面抖动也会触发放行，可能提前消耗一次，需要真机观察；手机重启或用户改系统时间时冷却剩余时间可能缩短。5 秒 / 30 秒为初值，待真机验证后调整。

### [DECISION-54] 来回切换生效策略时，规则参数与全局黑名单不再丢待生效、不再悄悄偏离基准（2026-10-14）

- **证据与根因**：用户实测：每周特权次数基准 3，统一延后下改 2（待生效）→ 切到宽严有别 → 再切回统一延后，待生效消失、生效中的值已是 2；此时改回 3 反而出现「2 -> 3」。原因是切换策略时只有勿扰时段（`TimeRuleManager`）和应用时间窗（`AppWindowManager`）会按新策略重新对齐 active / pending，规则参数在 `analyzeAndSaveGlobalParams` 里「统一延后」把当前生效值当作不动，全局黑名单则完全没参与切换。同类问题涉及休息 / 空档冷却、借用比例、临时使用受限应用时长、黑名单惩罚时长、每周特权次数与总时长，以及全局黑名单。
- **修复**：切到「统一延后」时，参数的「当前值」取当天基准（生效中的值回滚，用户当前输入作为待生效目标）；基准键或生效键不存在时保持原值。新增 `BlacklistStore.recalculatePendingAfterPolicySwitch`，口径与时段、应用时间窗一致，并接入 `EffectiveActivationPolicyManager.setPolicy`。切到「宽严有别」时挂着的加严参数项立即生效，并写进保存提示。
- **边界**：时段与应用时间窗原本就正确，仅补回归测试锁住；刷新时刻和预警分钟本来就立即生效；「时间规则可修改时段」当前不随统一延后延迟，属于既有行为，未改。

### [DECISION-53] 应用内切换页面不再把仍在前台的应用标成「已关闭」（2026-10-13）

- **证据与根因**：真机日志（2026-10-13 21:30–21:33）里，微信回桌面后小窗仍在无障碍可见集合中，却约 30 秒不重新上锁；`FG_SAMPLE` 的 `a11yVisible` 显示 `com.tencent.mm(CLOSED)`，系统 UsageEvents 对得上：微信从 `SingleChatInfoUI` 回到 `LauncherUI` 时，先 `LauncherUI RESUMED`，随后才 `SingleChatInfoUI STOPPED`。`VisibleBlacklistPresenceTracker` 不区分 Activity，把这条旧页面的 STOPPED 当成整个应用关闭，写入关闭墓碑，小窗兜底（`MiniWindowOverrider`）因此认为它不可见。该墓碑来自 10-03 的小窗关闭修复（`36490b51`）。
- **修复**：Presence 追踪器记录每个包最近恢复的 Activity；STOPPED 的 Activity 不是最近恢复的那个时，视为应用内页面切换，不写墓碑、不清 Session。拿不到类名时保持原行为；真正关闭小窗（最后恢复的 Activity 停止）仍写墓碑，10-03 的修复目标不变。
- **边界**：只改 UsageEvents 的墓碑判定，不改回桌面 3 秒宽限、小窗兜底和锁屏流程；`ForegroundSessionTracker` 本来就按 Activity 跟踪，未改。

### [DECISION-52] 生效策略名称以应用内「统一延后」为准（2026-10-13）

- **背景**：同一策略在应用内叫「统一延后」、在规格与指南里叫「全部次日」，两处对不上；「次日」也不准确——该策略下每项修改各等自己的下一个生效周期（日参数 / 勿扰时段 / 应用时间窗 / 全局黑名单次日 08:00，每周特权下周一，闹钟应急次数月参数下月 1 日）。曾考虑改名「全部下一周期」，最终保留应用内现有名称，避免为改名重新发版。
- **决策**：规格、用户指南、待生效提示、测试统一称「统一延后」；「全部次日」继续列在 `RuleDisplayBaselineConsistencyTest` 的禁用旧文案里。策略枚举 `ALL_NEXT_DAY` 与存储值不变。
- **边界**：只改文档与少数提示文案，不改生效规则；`docs/archive`、`docs/history`、历史计划文档保留原文。

### [DECISION-51] 次数扣费写回实际额度来源，避免切段后结余复活（2026-10-12）

- **证据与根因**：用户 10-12 导出的流水中，上午发放 3 次、用 1.5 次；下午用 4.5 次后剩 0；晚上发放 3 次却剩 4.5 次。普通次数扣费把全部成本记在当前段，当前段负结余暂时抵消前序结余；切段后逐段取零丢掉负数，已用掉的上午 1.5 次重新计入。
- **修复**：普通次数扣费与借用事务共用按段扣费函数，先扣当前段今日有效次数，再扣同业务日、启用且有滚存资格的前序段，写回各段用量。借用事务不得以配置满额替代今日有效次数。
- **防回归与边界**：`UsableCountCarryConsumptionTest` 覆盖切段、加权跨段扣费、停用段过滤、今日额度及借用事务失败零扣费；不猜测回填历史流水，不修改时长、冷却或借用倍率。

### [DECISION-50] 开发者进阶拦截：共享豁免时段、严立宽延；新增日期与时间拦截（2026-10-11）

- **背景**：安装拦截与开发者选项拦截是给开发者本人设的保护，开发时、晚上优化软件时会挡路；同时核查发现项目没有任何防改系统时间的机制，用户在真机确认「日期与时间」页里确实能改，时间规则、额度与所有「次日生效」的延迟都因此可被绕过。
- **决策**：① 两项拦截合并为设置页一行「开发者进阶拦截」，弹窗内管理；② 新增**一个共享**豁免窗口（默认预填 20:30–21:30，不跨午夜，**不设长度上限**）与两项各自的「豁免时段内放行」勾选，默认全关；窗口内放行、窗口外照拦，豁免只暂停拦截，不改开关与 PENDING；③ 豁免参数严立宽延：窗口缩短、取消勾选立即生效，变长、平移、勾上次日刷新时刻生效，适应期、回到基准、或对应拦截在基准里本来就是关的（豁免只在拦截开着时才有用，放宽没有可「放松」的保护）立即生效，值进每日基准快照；④ 新增独立保护项「日期与时间拦截」（按工具栏标题「日期与时间」识别，BACK + HOME），放在「加强约束功能」第一项，**不参与豁免**。这修订了 DECISION-43「不另设更新窗口」的一条。
- **防坑警告**：「日期与时间拦截」绝不能进豁免时段，否则每天留一个改时间的窗口，所有严立宽延失效；识别不得用「页面任意位置出现『日期与时间』」，设置首页有同名入口行；窗口改宽只靠次日生效延迟，不设上限意味着可被改成全天，这是用户明确接受的取舍。
- **局限**：日期与时间拦截只挡设置页入口，adb（`settings put` / `date`）、快捷设置、第三方应用直达该页不在范围；真正的兜底是尚未实现的「时间跳变检测」（对比墙钟变化与 `elapsedRealtime` 变化），另立计划。豁免判定与时间规则同用 `System.currentTimeMillis()`，受同一风险影响。详见 `docs/plans/2026-10-11-dev-advanced-guard-exemption.md`。

### [DECISION-48] 闹钟扫码预览迁入覆盖层并保留相机恢复链（2026-10-11）

- **根因与决策**：全屏覆盖层的透明孔依赖下层 Activity 的预览位置，分屏后两者坐标不一致。改为同窗口不透明 TextureView 取景器，保留 dev 的硬重绑、绑定代际和会话检查。
- **防回归**：取景器必须在等待出帧时可见；分析放行仍需真实出帧、前台和附着条件。不能把旧「出帧才显示取景孔」直接搬到新预览，否则会隐藏相机表面。
- **验证边界**：自动化验证见分任务计划，vivo 分屏与系统弹窗合成仍需真机验证。


### [DECISION-47] 所有生效冷却中禁止临时解锁，免费状态不豁免（2026-10-11）

- **背景**：休息中与空档期可免费反复临时解锁，且冷却中仍有 10 秒二级应急，削弱应用时间窗约束。
- **决策**：用户确认所有生效冷却中禁止临时解锁；冷却结束后保留原有免费与计费规则，休息申请的双倍成本和每周特权规则不变。读取全局冷却及持久化临时解锁冷却，区分预设的休息后冷却与已生效冷却。
- **实现清理**：用户进一步确认彻底取消二级应急，删除专属按钮、弹窗、状态与持久化读写、计次/等待策略、10 秒回锁定时器及独立日志分支；保留普通临时解锁到期链。旧版残留的应急字段不参与业务判定。
- **防回归**：锁屏按钮、申请入口、最终授权共用门禁；借用确认扣费移至最终授权门禁之后，避免旧弹窗确认先扣费再拒绝。覆盖 10 秒应急、精确截止边界、休息中独立冷却、持久化恢复与拒绝不扣额度。验证记录见 `docs/plans/2026-10-11-temporary-access-cooldown.md`。

### [DECISION-49] 延迟生效表：三页统一抬头、逐项撤销；额度明细按入流 / 出流筛选；时间规则页卡顿根因（2026-10-10）

- **背景**：勿扰时段编辑、规则生效策略切换明显卡顿；勿扰时段有「延迟生效修改」抬头而应用时间窗、规则参数没有；规则参数只能「一次撤销全部」，应用时间窗只能按卡片撤销；额度明细筛选里只有细分类型，没有「入流 / 出流」，「额度重算」不分增减。
- **卡顿根因**：`SegmentBlacklist.allLaunchablePackages` 对每个已装应用各问一次系统（几百次 IPC），却在主线程被反复触发——时段卡片每次绑定白名单摘要都算一遍；不利分析 `classifyListAppsAdverse` 里 `listSelectable(context)` 在名单根本没动时也急切计算；切换生效策略 / 保存时段会连着跑多轮分析。修复：全集加 60 秒缓存（按 `PackageManager` 实例区分，空结果不缓存），`listSelectable` 改懒取，名单「只增不删」时直接判有利不再过滤，进入时间规则页时后台预热。
- **决策**：新增 `PendingChangeTable` 把四处待生效修改按「待生效值 vs 生效值」的差异统一枚举，`PendingChangesDialog` 做成同一张表，三页卡片共用并统一叫「延迟生效修改」；撤销逐项进行。规则见 `docs/specs/focus.md` §8.3A。额度明细新增「入流（额度增加）/ 出流（额度减少）」去向筛选，「额度重算」拆成「重算增加 / 重算减少」，筛选与顶部汇总共用 `RestLedger.flowOf`，见 `docs/specs/statistics.md` §4A.1。
- **额度重算拆条**：时长与次数一增一减的重算落成两条记录（时长一条、次数一条，首尾相接），每条只属一个方向，统计与筛选不必再拆。
- **防回归**：新增待生效存储时，必须让 `PendingChangeTable` 能枚举并撤销它；撤销只回到生效值；`PendingChangeTableTest`、`RestLedgerTest`。
- **局限**：卡顿结论来自代码路径分析，未在真机上做过 profile；仅部分日期延后的新增时间窗应用不能单独撤销。

### [DECISION-46] 时段「今日额度」按业务日存账，改动只记差额，不再靠 lastModified 记号折算（2026-10-10）

- **背景**：10-09 真机流水里，改严一个已过的时段让它的滚存整段清零（18:16 少 10.1 分钟），之后一次混合保存重建时段时丢了改动标记，额度又加回来（19:49 +10.1 分钟）。根因是时段额度每次读取时现算，靠 `lastModified` 事后折算：编辑已有时段也被当成「新建」而失去滚存；`createHybridSegment` 丢记号又让折算被撤销。严立宽延与统一延后的滚存因此不一致。
- **决策**：每个业务日、每个时段存「今日额度」（`quota_entitle_<日>_<段>`，时长 + 次数 + 是否可滚存），首次读取按旧折算公式落账一次（兼顾新建 / 导入的防刷与旧数据迁移），之后读取直接取账；时段改动在 `QuotaRecalcRecorder.around` 里按「新额度 − 旧额度」把差额加到当天的账上（最小取 0），改动前先落账以免被旧记号折算。读取端只改 `getSegmentProration` 内部，签名不变。`markNewSegments` 对已有时段沿用旧记号；`createHybridSegment` 保留记号。恢复回宽（规则允许立即生效时）按差额加回；已用超出新额度不追扣、不挂账，每段剩余取 0。
- **启用 = 新建**（用户 10-10 确认）：启用已停用的时段、把今天加进生效日，都按启用时刻折算，折算后的额度正常滚存；`SegmentActivationPolicy` 只看是否启用。
- **未回补**：迁移当天已被旧规则扣掉的滚存不补回，只对迁移后的改动生效。
- **防回归**：改严已过时段只少差额；立即生效的混合保存不得让结余增加；`DailySegmentQuotaLedgerTest`。详见 `docs/plans/2026-10-10-daily-segment-quota-ledger.md`。

### [DECISION-45] 扣费与展示结余同口径；额度流水按入流 / 出流筛选并记录额度发放与借用；特权结算不超过计划结束（2026-10-09）

- **背景**：真机出现账面异常。① 结余 20m 时休息 20m，流水里结余却剩 3m；② 常规休息一笔「次数 -2」、休息 0.5 分钟却「次数 -1」，次数显示被放大一倍；③ 21:39 申请 22 分钟的休息，22:00 自然进入空档期，退回 11 分钟。③ 经核对是装机包早于 `86b31df`：当时休息跨过时段末尾时重叠部分按 0.5× 计费，已用部分只收一半，多退约一半；该折扣已在 DECISION-40 删除，当前 dev 只退未用部分（应退约 1 分钟），**退款逻辑未改**；④ 特权统计图两次 4 小时的特权合计 9h2m。
- **决策**：
  1. 扣费与展示结余共用 `CreditManager.getSegmentQuotaRemainingSeconds`：此前 `consumeSeconds` 扣前序段时没有套用展示口径的过滤（跨业务日、改动后失去结余资格、无限额段不结余），把额度从展示里已被排除的段扣掉，真正算作结余的段没动。
  2. 流水 `costCount` 统一为「次」：休息记录此前把 half-unit 当「次」写入。旧记录读取时换算，不迁移数据库；结余展示不再截断小数。
  3. 额度流水筛选按入流 / 出流分组：入流为额度发放、提前退还；出流为常规、借用、冷却中（休息与临时解锁合并，借用与冷却中可同时成立）、频繁返回扣减；特权是独立池子，不参与对账。同一范围内入流应不小于出流，小于说明记录不全。
  4. 新增「额度发放」（进入新勿扰时段记一笔，`QuotaGrantRecorder`）、临时解锁流水；借用记录的成本为当前段扣 + 下一段预扣，并写明借了多少、预扣多少；「违规惩罚」改称「频繁返回扣减」。时段被修改导致结余变化时记一条「额度重算」（`QuotaRecalcRecorder`），业务日切换时 pending 并入不记。
  5. 借用改记为「提前发放下一段额度再扣除」（用户 10-09 讨论）：借用记录带 `advancedMinutes` / `advancedCount`（= 借用扣除 ×2），下一段发放只补发剩下的并写明已提前发放的部分。原先借用只记「总扣减」、变动后只反映当前段，变动前还原不出，下一段发放还会冒出等于预扣量的缺口；改后每一笔都能接上。流水详情用主页用词（本次预支、借用扣除 (×2)、实际生效），时长与次数并排；简单记录不展开，删除改长按；「今天」范围核对 入流 = 出流 + 当前结余。流水表升到 v2（加两列，旧记录默认 0）。
  5. 特权自动结算时刻取「到期时间」与「当日 24:00」较早者（`resolvePrivilegeSettlementTime`），并把结算结束时刻限制在计划结束之内（`clampPrivilegeStopAt`）：此前 24:00 的分支排在到期分支之前，结算一旦拖过午夜，特权统计会按 24:00 计，时长被多算。已写入的历史统计不会自动回算。
  6. 特权统计整段计入并按业务日归属（`recordPrivilegeRestUsage`）：统计图只看各出口每天用了多少时间，所以空档期内的特权分钟也计入（key 为 `privilege_usage_<业务日>_seg_<小时>_gap`，勿扰时段内的仍用时段 key）。
  7. 日期口径统一：时间规则相关（休息 / 临时解锁 / 特权统计、额度流水、额度刷新）一律按业务日，起床 / 闹钟 / 应用使用按自然日；统计页图表说明里注明口径，额度流水范围（`HistoryRecordFilter.filterQuota` 的 `businessDayStartMinutes`）改按业务日切。
  8. 特权可用范围限定在业务日内（`PrivilegeWindowPolicy`（与另一会话的同类实现合并后以它为准，默认结束 24:00，不改用户已存数据）：开始不早于生效时间，结束晚于开始、最晚 24:00），设置界面严格校验新输入；已存储旧值读取时收窄（开始早于生效时间——这条约束是后加的）或回落默认（旧版跨午夜）。删除 UI 早已不可开启的「反向同步首段」代码，以及申请 / 调整时长处的 24:00 截断（范围结束已保证不过午夜）。结算处保留 24:00 兜底，只为旧版本持久化的跨午夜会话。10/06、10/07 两次特权的历史统计偏高（合计 9h02m，实际两次各 4 小时），**已由设置备份里的原始键值证实原因**：10/06 的 `privilege_usage_*`（上午 208 + 下午 182）恰好是该次特权覆盖的勿扰分钟（上午 104 + 下午 91）的两倍，即**同一次特权被结算了两遍**；10/07 与 10/03 都只记了一遍。根因是特权结算没有互斥，到期瞬间监控线程与界面线程同时触发结算，都通过了「特权还开着吗」的检查（已加锁，锁内重新检查，`privilegeSettleLock`）。已写入的 10/06 统计不回算，随时间滚出统计范围。
  9. 额度发放「补记」：时段早已开始才检测到（如装包后第一次轮询）时，不能把当前结余当成发放后的结余（设置备份里 08:30、12:45 两条发放就是这样，结余与前后记录接不上）。改为按「上一笔流水的变动后 + 本次发放」估算并注明「补记」。
  10. 10-06 翻倍的特权统计一次性回算（用户 10-09 选「精确修正」，不再「将就」）：因成因已用备份证实（上午 208 / 下午 182 恰为真实 104 / 91 的两倍），`PrivilegeStatsRepair` 只在数值恰好等于这两个已确认的翻倍值时减半并打已处理标记，其它数值、其它设备、第二次启动都不动；修完后可整体删除。
  11. 今天两笔补记的额度发放存的「变动后」是装包那一刻的当前结余（旧版补记 bug，新版已按「上一笔变动后 + 本次发放」估算）：08:30 显示变动前 −9m / −1.5 次（应为 0 / 0）。`LedgerGrantBalanceRepair` 一次性把 08:30 的 21m/11 修为 30m/14、12:45 的 26m/16 修为 49.1m/22（均由下一笔休息记录倒推验证），只在存的值恰好等于已确认的错误值时才改，打已处理标记；修完可整体删除。「使用前」改称「变动前」。
  12. 延迟生效逐项拆分（用户 10-09 备份里发现：生效次数 10 / 待生效 6，应用时间窗 21:00-23:30 生效、20:30-23:00 待生效）：根因是 `getCategorizedSegmentDiff` 没有「次数上限」项，混合保存时只改次数的时段被当成没变化；应用时间窗修改规则时没有拆分收紧与放宽。已补次数项，并让应用时间窗取「现在生效 ∩ 想要」为今天生效的窗口。复现与回归用 `SegmentPendingAccumulationTest`、`AppWindowPendingAccumulationTest`。
  13. 规则参数待生效记录「只显示一个」：`analyzeAndSaveGlobalParams` 写修改记录时按文案映射标签，「每周特权次数」「每周特权总时长」都落到 `weekly_privilege`，而 `ChangeLogManager` 按标签去重，后一条顶掉前一条（而 `checkParam` 清理时用的是 `weekly_privilege_count` / `_duration`，两边还对不上）。已抽成 `globalParamLogTag`，两项各用各的标签；`ChangeLogManager` 本来就按 `weekly_privilege` 前缀识别每周特权，无需改动。
- **防坑警告**：新增任何额度扣费路径，扣的段必须和 `getRemainingMinutes` 展示的段一致；流水的次数写入前先确认单位是「次」不是 half-unit；`balanceCount` 恒为 half-unit；借用流水必须记总扣减，否则入流 ≥ 出流的对账不成立。
- **局限**：旧版冷却中休息按成本倍率近似识别；额度发放只从本版本起记录，更早的范围内会提示入流小于出流；特权多算的具体成因（上述结算顺序问题是否为唯一原因）尚未在真机数据上证实。

### [DECISION-44] 渐进提醒新增「最大声音」「最大震动」上限，只设最大值；隐藏的控件收起不留空隙（2026-10-09）

- **背景**：声音增益是 `(档/10)^3`，真机实测第 2 档已能听到、第 3 档已很响，渐进 1→10 对声音偏陡，后几档只是更吵，「温和唤醒」失效。2026-09-08 提醒重设计曾停用旧的 `progressiveMax*` 设置并不再暴露为 UI。
- **决策**：① 渐进模式新增「最大声音」（1–10，默认 10），10 步每步 30 秒仍保留，均匀铺在 Level 1 与最大声音之间，第 k 步有效档位 `1 + (max − 1)(k − 1)/9`；② 只影响渐进爬升，不碰固定模式；先做声音，随后应用户要求补上「最大震动」（振幅同样按 `1 + (max − 1)(k − 1)/9` 铺开）；③ 不设最小值（起步始终 Level 1）；④ 第一关、第二关各有一份，沿用「默认设置 + 单个闹钟可改」；⑤ 声音滑块松手试听约 2 秒、震动滑块松手试震约 0.8 秒（振幅换算与真实震动共用 `AlarmAlertPolicy.vibrationAmplitude`），声音试听期间复用 `AlarmStreamVolumeLock` 把闹钟通道锁到 80%，与真实响铃一致。
- **有意回退**：这是对 09-08「不再暴露为 UI 设置」的有意回退，仅限声音上限一项；`autoEscalate`、`progressiveMin/MaxIntensity` 仍停用。
- **反转 §2.3**：原规定“切换时弹窗高度必须稳定、隐藏控件用 `INVISIBLE` 占位、不得 `GONE`”；用户觉得空隙难看，要求自动填补，故改为隐藏控件直接收起（`GONE`），对应契约测试 `DefaultAlarmSettingsLayoutContractTest` 同步翻转。弹窗在 `ScrollView` 内，高度变化无碍。
- **防坑警告**：持久化使用**新键**（声音：`progressiveRingMax`、`stage2ProgressiveRingMax`、`default_progressive_ring_max`、`default_stage2_progressive_ring_max`；震动：`progressiveVibrationMax`、`stage2ProgressiveVibrationMax`、`default_progressive_vibration_max`、`default_stage2_progressive_vibration_max`），不得读回旧键 `progressiveMaxRingVolume` / `progressiveMaxIntensity` / `default_progressive_max_ring` / `default_progressive_max_intensity`，否则早期版本遗留的非 10 值会让旧闹钟突然变轻；默认 10 必须与改动前逐位等价；渐进的最大值与固定值始终一并保存、互不覆盖，渐进模式不再把震动强度强制记成 1。

### [DECISION-43] 安装拦截：拦在安装器的风险拦截页，先于防卸载派发（2026-10-08）

- **背景**：开发者选项拦截堵住了 adb 装包，但仍可把改动过的安装包用文件管理器打开、经系统安装器覆盖安装，同签名更新保留全部数据。
- **决策**：① 新增独立保护开关，三态与开发者选项拦截一致，基准并入每日快照；② 识别以安装器包名（`.packageinstaller`）加页面应用名为准，vivo 上取风险拦截页的 `tv_app_name` 控件按 id 直查，其他页面用「应用名 + 安装 / 更新关键词 + 无卸载关键词」兜底；③ 命中后 BACK + HOME，派发先于防卸载；④ 与开发者选项拦截共用 `ProtectionBlockAction`。
- **防坑警告**：扫描超预算且没见到应用名必须放行，不得再 fail-closed（`24897a40` 的教训）；必须先于防卸载派发，否则其安装流程豁免会放行该页；卸载页留给防卸载，不得在这里接；vivo 风险拦截页只在应用未备案时出现，识别不能写死其类名。
- **局限**：不挡 adb、应用商店自动更新、安全模式；两项拦截同时处于待关闭时，自己也装不了包，约定稳定后才同时开启。

### [DECISION-42] 频繁返回扣减：应用时间窗锁的返回桌面也计数，进度提示去掉「距首次」（2026-10-08）

- **背景**：计数只认黑名单锁，应用时间窗锁下反复点「返回桌面」不会累计，等于留了一条绕过路径；进度提示里「距首次 Xs」与「窗口剩余 Ys」信息重复。
- **决策**：黑名单锁与应用时间窗锁共用同一个计数窗口和扣减规则，但必须处于勿扰时段内（空档期没有额度可扣，不计数），防卸载锁不计；提示只保留「窗口剩余 Ys」。

### [DECISION-41] 保护开关基准并入每日快照，入口先拍、不再补拍（2026-10-08）

- **背景**：每日基准快照只写规则、黑名单和应用窗口，保护开关状态只由 `saveBaselineParams` 写入，而它仅在适应期结束或用户保存规则时（`ensureFreshBaseline`）才被调用。用户当天先开启开关、再保存任何规则，就会把「已开启」记成当天基准，之后关闭被误判为需要次日生效。
- **决策**：① 每日快照新增写入保护开关状态；② 抽出幂等的 `DailyBaselineManager.ensureTodaySnapshot`，监控服务、Application、主界面共用，拍照前先并入到期的待修改记录；③ 删除 `ensureFreshBaseline`，保存规则不再触发补拍。基准只在「刷新时刻之后第一次到入口」与「适应期结束」两个时刻写入，后写覆盖先写。
- **防坑警告**：新增任何依赖基准的保护开关，必须把它的状态写进 `saveBaselineParams`；不得用「保存规则」充当拍基准的时机；Direct Boot 未解锁时不得调用 `ensureTodaySnapshot`（访问 CE 存储）。契约测试 `MonitorServiceDailyBaselineContractTest` 与行为测试 `DailyBaselineManagerTest` 锁住上述行为。
- **一次性修复**：升级当天旧版本已把当天开启的「开发者选项拦截」误记进基准，且当天快照不会重拍，故新增版本标记的一次性修复，只把这一项基准清回「关」（用户确认该开关当天刷新时刻本来是关的），不改开关本身、不碰其他项；发布一个版本后可整段删除，不得扩展成通用「重置基准」。
- **局限**：基准无法从「当前值 + 待修改记录」事后还原：待修改只记关闭请求，不记当天的立即开启；所以靠「入口先拍」而不是事后拼凑。

### [DECISION-40] 临时解锁一刀切计费、冷却时长统一（0=无冷却，特权结束后也冷却）、提前结束全额退未用时长；锁屏去掉重复的休息倒计时行（2026-10-08）

- **背景**：休息开始时 `grantFreeTime` 会预置 `CoolDownEnd = 休息结束 + 冷却`。旧结算把临时解锁拆成「休息剩余秒数免费 + 超出部分付费」，超出部分又落在这段预置冷却内，被整单 ×2，用户在休息中申请临时解锁仍被双倍收费；另外 `MonitorService` 每秒向锁屏副标题追加「🟢 休息中: m 分 s 秒」，而 `updateStatus` 每 2 秒整段重写副标题，该行周期性消失再出现，与卡片内的「休息中 剩余 Ns」重复且一闪一闪。
- **决策**：
  1. 一刀切计费：申请时长**完全落在**休息 / 空档期内才免费（时长 0、次数 0）；一旦超出，**整段**（含休息内那部分）都按勿扰计费，不再拆「免费段 + 付费段」。整段碰到冷却期（休息冷却从休息结束时刻起算）则整段 ×2，没有冷却则 ×1。拆段计费对用户更友好，但规则更难理解，故取简单口径。
  2. 冷却时长统一：只读全局参数「休息冷却」，休息结束、临时解锁结束、特权结束（到时 / 提前结束 / 跨 24:00 截断）共用，`0` 表示无冷却（不再被换成 60 秒，临时解锁冷却不再退回阶梯公式）；特权生效期间不计冷却。
  3. 提前结束休息 / 特权：未用时长全额退还（去掉向上取整多收、勿扰段 0.5 倍折扣、特权 75% 保底）；次数不退，保持现状。
  4. 空档期申请超出空档期：整段计费，碰勿扰时段 ×1、碰冷却取较高倍率（×2），先扣前序结余，不足扣下一勿扰段额度（不算借用），仍不足拒绝（`CreditManager.consumeGapOverflowCharge`）；临时解锁原阶梯冷却公式已删除，仅保留统一冷却。
  5. 删除副标题里的休息倒计时行（`MonitorService.startFreeTimeCountdownUI` 与 `LockWindowManager.updateFreeTimeText`），休息倒计时只保留卡片内 `tv_rest_countdown_in_card`。
- **防坑警告**：`resolveTemporaryAccessChargeSnapshot` 的 `freeSec` 只用于判断「是否整段在免费窗口内」，超出后不得再按 `freeSec` 拆分计费。

### [DECISION-39] 开发者选项拦截：按页面标题识别，严立宽延（2026-10-08）

- **背景**：冲动时最省力的绕过路径是进「开发者选项」打开 USB 调试或无线调试，再用电脑 adb 关闭无障碍或卸载；原有保护只覆盖应用管理、权限与无障碍页面。
- **决策**：① 新增独立保护开关，三态与小窗可见性增强一致，开启立即生效，关闭走严格次日延迟，基准为关或适应期内立即关闭；② 识别条件为设置包内「工具栏标题等于开发者选项」，或页面出现至少两个开发者选项独有条目，或类名含 `DevelopmentSettings`；③ 命中后 BACK + HOME；④ 并入「关闭无障碍」的协同关闭与确认弹窗。
- **防坑警告**：不得以页面任意位置出现「开发者选项」文字判定——设置首页、系统管理页有同名入口行，会把父页面一并拦住；vivo 承载页是通用容器 `com.vivo.settings.VivoSubSettings`，不能单靠类名；文案比较须忽略空白字符（真机为「USB 调试」「撤消 USB 调试授权」）。
- **局限**：不阻止保护开启前已建立的 adb 连接，不覆盖安全模式与多用户；自动关闭已开启的调试需 `WRITE_SECURE_SETTINGS`，留作后续可选增强。无障碍断开 / 失效时只让拦截暂停、状态文字提示未生效，不改写持久化开关与 pending（与既有依赖管理一致）。

### [DECISION-38] 无障碍内容读取收窄（2026-10-05）

- 用户确认普通第三方应用不做内容识别，保留桌面、设置、商店、管家和卸载工具等保护入口。
- CoreService 在取事件文字和防护窗口树前按来源包过滤，ProtectionManager 再核验窗口所属包；复用现有 OEM 名单并识别默认桌面与卸载权限，移除任意界面的节点预热及 PWA 事件正文读取。
- 包名/可见性驱动的时间规则保留；该调整不证明 vivo 系统关闭服务弹窗已经解决，仍需原始弹窗与真机验证。读取边界见 `docs/specs/protection.md`，回归见 `ProtectionContentScopeTest`。

### [DECISION-37] 闹钟挑战增加「应急退出」：每月有限次数、长按防误触、放宽下月生效
- **背景**：挑战闹钟没有真正的出口，摄像头损坏、题库异常、生病或急事时用户只能强制重启或设法卸载，而闹钟带防关机保护，这条路更难走；产品总览又要求加强保护必须允许最终退出。
- **决策**：① 挑战界面提供「应急退出」，长按 3 秒加窗口内确认，只结束本次响铃，不算完成挑战，不动闹钟设置；② 每月次数用户可设 0–3，默认 1；调小/调回基准立即生效，调大待下个自然月刷新时刻生效；③ 当月可用 = min(生效配置, 月初配置) − 已用，月界按业务日刷新时刻划分；④ 独立 prefs 存储，不进设置备份白名单；测试闹钟不消耗次数。
- **防坑警告**：入口不得绑定 onClick 或响应按键/无障碍点击；不得把应急退出记为挑战完成；月界不得写死 00:00；调大不得增加当月额度；不得与「再响/休息/特权」混用命名。
- **局限**：一次出口只救一个早上，持续性故障仍需改挑战配置（放宽配置本身延期生效）。

### [DECISION-36] 「频繁锁屏惩罚」改为「频繁返回扣减」：回执即时展示、冷却期时长翻倍
- **背景**：第 5 次点「返回桌面」时已静默扣费，回执却缓存到下一次锁屏 attach 才弹出，用户感知成「点进黑名单才被罚」；回执里「当前额度」实为本次从当前段扣掉的秒数，与「扣除时长」重复；冷却期只翻倍次数，时长仍固定 30s；「惩罚」措辞让用户不适。
- **决策**：① 第 5 次点击当场弹回执，确认后才回桌面，删除 attach 时延迟展示；② 锁屏提示增加「距首次 / 窗口剩余」秒数并在 4/5 时预警；③ 回执拆为「本次扣除」与「当前剩余额度（扣减后时长+次数）」；④ 冷却期扣除时长 30s→60s，冷却期延长维持 +30s；⑤ 用户可见文案统一为「扣减/借用扣除」，代码标识符与日志事件名不变；规则与文案收口到 `FrequentReturnDeduction`。
- **防坑警告**：不得把回执重新缓存到下一次锁屏展示；不得在 LockWindowManager 硬编码阈值、窗口、时长或「惩罚」文案，统一引用 `FrequentReturnDeduction`；`ReturnHomePenaltySourceContractTest` 守住此契约。

### [DECISION-35] 惩罚借用上限收口与「刷新时刻同步」被回写
- **背景**：① 频繁锁屏惩罚的次数借用只受固定「最多 4 个半次」限制，借用本金还记在**下一段**的借用账本，而上限计算读的是**当前段**账本，连续惩罚看不到已借量，次数借用被突破；时长借用还会越过下一段去扣更后面的时段；逻辑最后一段（下一段属于下一业务日）也能借。② 把勿扰时段结束时间 07:30→07:15（放宽）并开启「同步」后，界面提示生效时刻已同步为 07:15，延迟生效表与实际生效时刻仍是 07:30。
- **根因（②）**：混合路径里 `saveRefreshTime` 的入口守卫对照仍在生效的**旧时段**校验（07:15 落在旧时段 …–07:30 内部）被静默拒绝；即使放行，随后 `saveTimeSegments(hybrid)` 又按保守的 hybrid 时段（仍是 07:30）重新推导刷新时刻，把它改回 07:30。
- **决策**：
  1. 惩罚借用本金记在当前段账本；次数与时长借用都受「下一段额度 × 借用比例 − 已借(×2)」和下一段实际剩余约束，只剩一点扣一点、没了不扣；只从下一段借，逻辑最后一段不可借；回执 `timeDeductSec` 为实际扣除量。
  2. 刷新时刻同步对照**即将生效的新时段**校验（`saveRefreshTimeValidated`），写入后同步所有待生效项的生效时间戳；混合路径调用 `saveTimeSegments` 时关闭其内部的刷新时刻重推导（`syncRefreshFromSegments=false`）。
- **防坑警告**：任何读写借用账本的新代码必须与 `getBorrowedUsableCountHalfUnitsFromNext` 同口径（当前段账本）；任何「刷新时刻 ↔ 时段」联动都要警惕多处写入者互相回写，并用 `RefreshTimeSyncOnSegmentShortenTest` 守护。测试夹具不得依赖墙钟（当前段若恰是逻辑最后一段则不可借）。

### [DECISION-34] FastLock 开机提速：Direct Boot 红线与解锁即时触发
- **背景**：解锁进桌面后，防卸载初始化遮罩要 5~15s 才弹出（依赖低优先级排队的 `BOOT_COMPLETED`）。首次提速尝试在锁屏（Direct Boot）期提前拉起 `FastLockService`，`ThemeManager.currentKey()` 读取 CE SharedPreferences 抛 `IllegalStateException`（`SharedPreferences in credential encrypted storage are not available until after user (id 0) is unlocked`），服务连续崩溃，比基线更慢（约 43s 才恢复）。
- **决策**：
  1. **Direct Boot 红线**：未解锁时 FastLock 链路不得访问 CE。`DirectBootState.isUserUnlocked` 为唯一判定；`ThemeManager` 未解锁返回 `Theme_DeciKeeper`；`StartupProtectionManager.shouldKeepInitialization` 未解锁返回 `false`。
  2. **统一 CAS 入口** `triggerBootFastLock(context, channel)`：未解锁返回 → 只读 DPS 缓存 → CAS 抢占 → attach + 起服务；后到通道只做 reassert。
  3. **解锁通道**：进程出生于 Direct Boot 时由 `BootUnlockTrigger` 动态注册 `USER_UNLOCKED`/`USER_PRESENT`；`CoreService.onServiceConnected` 在 armed 进程中作为补充通道。
  4. attach 门禁改为零 IPC（`shouldAttachBootOverlay`：解锁 + DPS 缓存 + 防卸载状态，不查 `Settings.Secure`），并输出 `[FastLockBoot] phase` 阶段耗时日志。
- **防坑警告**：
  - `ACTION_USER_UNLOCKED` 只投递给**动态注册**的接收器，manifest 声明无效；
  - 锁屏期禁止 `startForegroundService(FastLockService)`，任何新通道必须先过 `DirectBootState`；
  - 「vivo 40 秒冷冻 = 系统崩溃惩罚」的机制**未被证实**（也可能是 `START_STICKY` 重启退避），修复方向不变；
  - 单测环境不含 Android 资源，无法 inflate `window_fast_lock`，测试经 `StartupProtectionManager.overlayViewFactory` 注入。
- **真机验证（V2425A，2026-10-01）通过**：遮罩撑满 10 秒，期间无法卸载。验证中追加了两项决策：
  5. **锁屏期预挂遮罩**：解锁广播实测晚于系统解锁约 2.8s（进程被调度延迟，非广播慢），故在 Direct Boot 把遮罩预挂在 keyguard 之下，解锁信号到达后才启动倒计时/超时/服务；锁屏期门禁只读 DPS（无障碍缓存 + `StartupAntiUninstallCache` 防卸载镜像，由 `AntiUninstallManager.getProtectionState` 同步），解锁后若 CE 门禁关闭则撤掉预挂遮罩。
  6. **无障碍服务名必须按组件归一化**：vivo 在 `ENABLED_ACCESSIBILITY_SERVICES` 里存短格式 `pkg/.service.CoreService`，自行拼全名 `contains` 会误判"已关闭"——`FastLockService` 后台校验曾因此把缓存改 false 并在 0.3s 内 `stopSelf`，遮罩一闪而过。统一走 `PermissionManager.isAccessibilityEnabled` / `isComponentListed`，`FastLockA11yNameContractTest` 守护。遮罩布局默认倒计时文案须与 `FAST_LOCK_MAX_VISIBLE_MS`（10s）一致。
- **未做/待办**：预 inflate（需先用 `[FastLockBoot] phase` 数据确认 `inflateMs` 是否显著）；无障碍在 Direct Boot 期连上时被 CE 异常跳过的 `onServiceConnected` 后续初始化需在解锁后补跑；`CoreService.onUnbind` 锁屏期读 CE、Direct Boot 出生进程不执行 `finishApplicationInit` 为独立问题。
- 详细方案与真机证据：[docs/plans/2026-10-01-fastlock-startup-acceleration-spec.md](docs/plans/2026-10-01-fastlock-startup-acceleration-spec.md)。

### [DECISION-33] vivo 快捷支付/超级卡包无障碍重连与 Transient Overlay 代际死锁治理
- **背景**：在 vivo OriginOS（机型 V2425A 等）上，勿扰时段从屏幕边缘滑出快捷入口/超级卡包（`com.vivo.card`）调起微信支付时，vivo 系统级安全沙盒临时解绑无障碍服务（`onUnbind`，持续约 1.4s）后重新连接。重连后主锁屏虽然重建（generation 从 1 变 2），但因 `LockWindowManager` 中的 transient overlay（`mRestWindow`、`isInputPanelOpen`、`isSettingsPassThrough`）未纳入代际治理且未清理，导致 `isInputPanelOpen` 永久泄露为 true；随后 `MonitorService.checkForegroundApp()` 将 pause 门禁置于最顶部，导致 `currentLockedPkg` 无法重置、前台检测与 `mLastTopResolvedAt` 永久冻结；同时 `CoreService` 在 `isInputPanelOpen` 时 early-return 截断了 `ProtectionManager`，掐死了防卸载与防关权限，但因底层原始 signal 持续更新导致 `PermissionManager` 误判为健康（假健康）。
- **决策**：
  1. **Transient Overlay 代际治理与超时自愈**：将 `mRestWindow` 与 `isInputPanelOpen` 纳入 `accessibilityGeneration` 代际治理；在 `setCoreService(null)`、`onUnbind`、`resetLockState`、`dismissLockScreen` 以及重连代际递增时，强制回收 transient 覆盖层；增加 60 秒最大安全超时自愈机制，杜绝 pause 标志永久残留；
  2. **CoreService 保护链不可剥夺**：建立 `CoreServiceProtectionDispatchPolicy` 纯策略类；`isInputPanelOpen` 仅用于跳过普通应用的黑名单重度扫描以保障打字流畅，**绝对不得截断 `ProtectionManager.onAccessibilityEvent`**，防卸载与防关权限享有最高分发权；
  3. **MonitorService 门禁与状态自愈前置**：将 stale `currentLockedPkg` 清理与孤立输入面板检测前置于 pause 门禁之前，确保锁屏消失后前台检测绝不卡死；
  4. **vivo 厂商特征补齐**：将 `com.vivo.card` 纳入 `usageCarrierNoisePackages` 与 `ForegroundSessionTracker` 系统级载体过滤；
  5. **健康判定多维重构**：接入 `CoreServiceProtectionDispatchPolicy.isAccessibilityPipelineHealthy`，区分底层 raw signal 与保护链真正 dispatch，彻底根除假健康。
- **防坑警告**：任何 UI 输入面板或 transient 弹窗切勿只管打开不设超时与代际清理，绝不可将打字优化直接作为全局 early-return 杀掉保护链。

### [DECISION-32] 大型测试套件模块化分包、全屏安全区避让与 SQLite 生命周期收敛
- **背景**：测试套件包含 167+ 个测试文件，原先扁平堆叠在测试根目录下，缺乏领域分层与架构防腐；全屏悬浮锁屏与闹钟锁屏在刘海屏/挖孔屏设备上存在元素遮挡隐患；统计流水线 `RestStatsRecorder` 与 `RestStatsDatabase.clear()` 存在多次打开数据库且未严格配对关闭导致的 CloseGuard 资源泄漏隐患；CI 依赖的 GitHub Actions 运行在即将废弃的 Node 20 runtime 下且 `main` 分支缺少强制状态检查门禁。
- **决策**：
  1. **测试套件领域模块化**：将全部测试重构划分至 10 个顶层领域包（`manager`, `ui`, `protection`, `data`, `service`, `questionbank`, `oem`, `governance`, `testutil`, `statistics`），设立 `app/src/test/README.md` 单真理源；建立 `TestPackageLayoutContractTest` 治理门禁，禁止任何根目录平铺测试，未知目录直接在 CI 中阻断；
  2. **全屏安全区避让 (WindowInsets & Cutout)**：新建 `OverlaySafeInsets.kt`，实现 `resolveOverlaySafeInsets`（每边取 `max(systemBars, displayCutout)`）与 `installOverlaySafeInsets`（记录初始 padding 防重复累加）；在 `LockWindowManager`（`layout_background_text` 与 `layout_bottom_buttons`）与 `LockAlarmOverlay`（`root_content_layout`）全面装配，配备 Robolectric 挖孔测试与源码静态契约测试；
  3. **SQLite 资源生命周期收敛**：`RestStatsRecorder.kt` 统一收口至 `withDatabase`，在 `finally` 中严格调用 `database.close()` 释放连接；`RestStatsDatabase.clear()` 保持单次绑定 `val db = writableDatabase` 完成事务，彻底消除孤立 helper 泄漏；增加 100 次闭环高频操作测试与治理契约测试；
  4. **CI 与依赖现代化**：CI actions 全面升级至 Node 24 兼容版本（`checkout@v7`, `setup-java@v6`, `cache@v6`, `upload-artifact@v7`）；清除 Gradle 8.13 下无效的 9.4 属性 `org.gradle.tooling.parallel`；GitHub 仓库 Ruleset 为 `main` 固化 `Build & Test (Ubuntu)` 必选状态检查；
  5. **ChatGPT Web 闭环审查**：通过 `/spec-loop-web-tdd` 流程由 ChatGPT Web（High Reasoning + GitHub Connector）进行两轮深度审查与实施计划落地，最终在 commit `fd71a87` 达成 `FINAL VERDICT: APPROVED`，官方 GitHub Actions 流水线（Run ID: 35098789469）全绿通过（1390 个单测全部通过）。

### [DECISION-31] 产品故事收敛：生效机制与挑战闹钟来源
- **时间规则**：从系统限额、专注模式、番茄钟及限制工具的实际使用中，确认“修改摩擦高”并不等于“放宽困难”，而缺少正常出口又会降低长期可用性。因此收敛为“编辑方便、保存不等于生效、加严立即、放宽 pending、恢复稳定基准立即”；先有勿扰锁及休息/结余/借用/特权，后因游戏等高风险应用会消耗出口而形成应用时间窗，最终成为两把锁与三个出口。
- **挑战闹钟**：大学时手机放床下带来的离床效果是个人经验；读研后手机放床边又出现按掉继续睡。通过 GPT 查询现有方案时了解到 QRAlarm，明确参考其扫码离床思路。最小 demo 很快，真正长期投入在扫码鲁棒性、锁屏后台、重启恢复和关机/重启绕过面；随后按自身需求加入多题型、自定义题库、完成后缓冲和可选后续阶段。对外不宣称扫码离床或多阶段挑战原创。

### [DECISION-29] 冷却倍率全链路收敛为平坦倍率，删除借用按秒加权
- **背景**：DECISION-27 已把休息/临时解锁的计费口径定为"整单倍率"，并在 `focus.md` §3.3 声明"不再按秒分区加权"。但代码里仍残留三处按秒加权：`calculateWeightedRestCountImpl`（次数）、`calculateWeightedQuotaSeconds`（跨段末时长）、`calculateWeightedBorrowSeconds`（借用，`zoneWeighted`）。结果是同一个"冷却期申请"因是否需要借用而得到不同扣费，且用户可见弹窗自相矛盾（三处写"整单直接翻倍"、三处写"加权扣费"）。`focus.md` 又同时保留"冷却 ×2 与借用是两个不同概念、不得混为一谈"，与代码中二者相乘的事实冲突。
- **决策**：
  1. 正常申请（休息/临时解锁）触及冷却期 → 时长与次数**整单 ×2**；
  2. 借用触及冷却期 → 借用惩罚 ×2 与冷却 ×2 **相乘**，**整单 ×4**（借 1 扣 4）；未触及冷却时仍为借 1 扣 2；
  3. **删除 `zoneWeighted` 这条生产调用链**，一律采用平坦倍率；空档秒仍不计费（沿用旧 `gap×0` 语义）；
  4. `focus.md` / `guide.md` / `SPEC.md` 中"两个不同概念"的表述改为"二者会相乘"。
- **防坑警告**：`calculateWeightedBorrowSeconds` 此前**没有任何测试覆盖**，改公式时全量测试全绿也发现不了问题；已补 `BorrowPenaltyFormulaTest`。`WeightedRestCountTest` 中"触及冷却即整单翻倍"的期望也已按新口径更新，其旧的比例 helper 覆盖按 `focus.md` §3.3 的说明仅作兼容保留。

### [DECISION-28] 文档分层重构：增设产品总览层，退役根 PLAN.md
- **背景**：README 同时承担产品介绍、使用手册与规格摘要三种职责；根 SPEC 偏工程契约，缺少「为什么这样设计」的解释层；`docs/使用手册.md` 内容陈旧；根 `PLAN.md` 同时与「当前任务」和 `HISTORY.md` 竞争。结果是新贡献者和 AI 容易把决定守护误解成单纯的防沉迷工具或闹钟工具，也说不清规则保护与挑战闹钟为什么属于同一个产品。
- **决策**：
  1. 确立四层文档：`README.md`（是什么 / 有什么）→ `docs/product/system-overview.md`（为什么这样设计）→ `SPEC.md` + `docs/specs/`（规则怎么实现）→ `docs/user/`（怎么用）；
  2. 新增 `docs/product/system-overview.md` 作为产品总览层的唯一归属，承载产品定位、两个核心模块、统一设计原则、自由出口体系、产品边界与术语规范；
  3. **退役根 `PLAN.md` 与 `task_plan.md`**：本项目不维护总计划，执行路线按任务拆分到 `docs/plans/`，已经发生的决策交给 `HISTORY.md` / `docs/history/`；
  4. `docs/使用手册.md` 拆分为 `docs/user/getting-started.md`、`guide.md`、`permissions.md`，原文件移入 `docs/archive/`；
  5. 旧计划与散落工作稿（`docs/work/`、`docs/superpowers/`、根目录 `.history_full.md`、`notes.md`、`penalty-charge-implementation.md`）统一移入 `docs/archive/`，只归档不再维护。
- **术语统一**：产品正式名「决定守护 / DeciKeeper」，系统名「决定保护系统」，模块名「规则保护」「挑战闹钟」，规则配置入口「时间规则」；用户入口名与系统模块名（挑战闹钟 / 起床系统）并存但不互相替换。历史名称（IronWill / ZhiZhi / 知止 / 承诺保护系统）只允许留在 `HISTORY.md`、`docs/history/`、`docs/archive/` 与 Git 提交历史。
- **防坑警告**：`docs/plans/` 是**分任务计划**目录，不是第二份总计划；不要把全局路线重新塞回一份文件，也不要用 `SPEC_v2.md`、`PLAN_final.md` 之类的平行真相文件规避收敛。

### [DECISION-25] v0.2.0 单机内测版 About / Privacy 信息页收敛
- **背景**：当前 About 页沿用了面向正式运营场景的 7 个法务入口，向没有账号、服务器和商业化的单机内测用户展示了不必要的 SDK、个人信息、共享、注销和投诉页面，增加理解成本并造成产品状态误解。
- **决策**：
  1. About 页当前只展示版本信息、功能说明、隐私说明、开源许可、内容来源和可选反馈邮箱；
  2. 新增 `privacy_notice.txt`，用普通用户语言说明本地存储与通知、相机、无障碍、悬浮窗、使用情况访问权限用途；
  3. 旧文档资源、首屏同意流程和 `LegalDocumentActivity` 路由保留，但旧入口从当前导航隐藏，未来增加账号、云同步或数据服务时再恢复对应入口；
  4. 不改闹钟、权限申请、挑战和保护业务逻辑。

### [DECISION-26] 统一三大临时放行到期决策解析器与桌面等待状态机 (Unified Expiry Decision Resolver)
- **背景**：此前 Free Time (休息) 与 Weekly Privilege (特权) 到期时直接调用 `LockWindowManager.showLockScreen(...)` 无条件硬锁，跳过已有前台证据链，导致退出到桌面的正常操作与白名单应用被误锁；同时到期遇到 UsageStats 桌面滞后或 unknown 时缺乏统一协调。
- **决策**：
  1. **纯策略决策层解耦**：建立 `com.decikeeper.app.manager.expiry` 独立包，抽取纯 Kotlin Seam `ExpiryResolver` 与数据模型 `ExpiryDecision`、`ExpiryContext`，支持 100% 纯 JVM 单元测试守护；
  2. **三大原则确立**：
     - 原则 A（重新评估而非立即锁）：禁止 FreeTime / TempAccess / Privilege 直接硬锁，统一进入 Resolver；
     - 原则 B（黑名单最高否决权）：无论前台还是小窗浮窗，只要可见窗口存在黑名单，立即判定 `LOCK`；
     - 原则 C（白名单非覆盖黑名单）：仅在无黑名单且前台明确为白名单时判定 `UNLOCK`；
  3. **Desktop Pending 状态机**：前台为桌面或未知时进入 `DESKTOP_PENDING`，启动 35 秒计时器（`DESKTOP_CONFIRM_MS = 35000L`）；期间监听黑名单出现立即 `LOCK`、白名单出现立即 `UNLOCK`，超时则通过 `resolveHistoryFallback` 结合 `recentForegroundLog` 最近有效历史裁决；
  4. **全链路统一**：彻底清除 `MonitorService` 中两处硬编码锁屏，重构 desktop streak 为 History fallback 最终裁决，输出标准 `[EXPIRY_RESOLVE]` 日志。

### [DECISION-27] 双锁系统与临时解锁机制 Spec v1.0 架构收敛
- **背景**：此前临时解锁收费逻辑容易与“解哪把锁”混淆；纯勿扰锁屏上挂有次级临时解锁小按钮，造成休息与临时解锁职责交叉；锁屏展示期间即使时间自然进入放行时段，旧逻辑也要求必须显式退出桌面再进；特权与临时解锁流程未彻底隔离。
- **决策**：
  1. **锁屏出口呈现严格正交化**：
     - 纯勿扰锁屏（DND Lock）：仅提供「申请休息」与「每周特权」，彻底移除「临时解锁」入口（`shouldShowStandaloneTemporaryAccess = false`）；
     - 应用时间窗锁屏（App Window Lock）与双锁并存锁屏（Dual Lock）：优先展示时间窗限制，仅提供「临时解锁」与「每周特权」，不提供「申请休息」（因休息无法解除时间窗限制，避免用户扣除休息额度后依然被拦截）；
  2. **自然跨界自动放行（NATURAL_RULE_RELEASE）**：
     - 时间进入允许区间（空档期或时间窗开启）是真正的规则解除；
     - 锁屏感知到底层应用已无任何生效锁阻挡后，通过新增合法凭据 `LockDismissAuthority.NATURAL_RULE_RELEASE` 自动关闭锁屏放行底层应用，消除强制按 Home 的割裂感；回到桌面依然是非解除状态；
  3. **基于 UserState 单一真相源计费**：
     - 临时解锁计费完全由用户当前状态决定：`FREE_TIME` 与 `REST` 自由状态下完全免费（时长 0、次数 0、不吃冷却翻倍）；
     - `NORMAL` 状态下消耗当前勿扰时段额度：实际申请时长（上限 3.0 分钟）、基础 0.5 次（1 half-unit）、冷却整单翻倍、借用本金翻倍（借 1 扣 2，借 0.5 次扣 1 次）；特权状态下双锁已解除，不进入临时解锁流程；
  4. **8 大核心验收测试守护**：落地 `DualLockSystemContractTest`，更新 `LockScreenPresentationPolicyTest`、`LockExitAuthorityContractTest` 与 `AutomaticRestrictionReleasePolicyTest`，建立不可动摇的回归防护网。



### [DECISION-01] 闹钟状态与 Direct Boot 设备保护存储双写
- **背景**：Android 设备强制关机重启后，在首次输入锁屏密码前处于 Direct Boot 模式，凭据加密存储（Credential Protected Storage）不可读，导致闹钟标志位丢失无法恢复震动。
- **决策**：所有闹钟激活状态必须使用 `createDeviceProtectedStorageContext()` 双写到设备保护存储，且必须使用 `commit()` 同步写入，服务声明 `android:directBootAware="true"`。
- **防坑警告**：Direct Boot 下 `startForeground` 若通知系统未就绪可能崩溃，必须用 `try-catch` 防护，绝不阻断唤醒。

### [DECISION-02] 锁屏覆盖层代际机制 (Accessibility Generation Isolation)
- **背景**：在 Vivo 等机型上，无障碍服务被系统回收再重连时，因静态 `_isLocked=true` 与视图残留，导致新前台黑名单请求误触发 `LOCK_ALREADY_ATTACHED` 短路，产生严重漏拦。
- **决策**：`CoreService` 维护原子代际序号 `accessibilityGeneration: AtomicLong`，每次 `onServiceConnected` 递增；`LockWindowManager` 记录视图附着时的代际。仅当代际匹配时复用视图，代际过期立即强制重建。
- **防坑警告**：防卸载锁（`_isAntiUninstallLock=true`）具备最高优先级，跨代际保留，不得被黑名单普通锁请求降级。

### [DECISION-03] 防卸载拦截 5 秒热窗口对称化
- **背景**：用户在系统设置中点击“强行停止”或“卸载”时，不同厂商弹窗出现时机与文本抓取存在时间差；深度遍历 AccessibilityNode 耗时长达数百毫秒，给用户留下操作时间缝隙。
- **决策**：统一引入 5 秒热窗口（Hot Window）机制。一旦识别到进入设置关键页或点击敏感操作，立即激活热窗口，后续弹窗命中直接执行 BACK+HOME 退栈，跳过深层文本抓取。
- **防坑警告**：加严拦截规则（如隐私空间添加应用）优先采用页面特征单闸立即拦截，严禁依赖耗时的节点深度文本比对。

### [DECISION-04] 生效策略与法定基准 (Baseline) 比对
- **背景**：若放宽规则与加严规则即时混合生效，用户易在自控崩溃时反复修改规则避锁。
- **决策**：确立“加严立即生效，放松次日生效”铁律。比对基准以每日固化生成的快照文件 `iron_will_rules_baseline` 为准（默认每日 08:00，可在全局参数中自定义刷新时刻），杜绝以当天工作态作为比对源产生的漂移。

### [DECISION-05] 临时放行分段阶梯惩罚
- **背景**：早期固定 2 倍冷静期容易被恶意利用，用户会频繁短时间申请放行。
- **决策**：冷却公式实施分段阶梯（0~60s: 1x, 61~120s: 2x, 121~180s: 3x）。申请时间越长，不仅扣除额度倍增，冷却锁定惩罚更呈指数上升。

### [DECISION-06] 全面更名「知止 (ZhiZhi)」与包名重构 (com.zhizhi.app)
- **背景**：原名称与现有外部商业应用产生冲突；开源首发需要更深刻独特的东方哲学意蕴（“知足不辱，知止不殆；知止而后有定”）。
- **决策**：彻底更名为「知止」(ZhiZhi)，Android 包名全面平移至 `com.zhizhi.app`。同时在 `SettingsBackupManager` 中实现双协议解析，原生向后兼容 `ironwill_settings_backup` 导入，保障旧版数据无缝迁移。
- **防坑警告**：包名变更后为全新独立应用，不可覆盖安装旧版，需通过导出/导入备份完成规则迁移。

### [DECISION-07] 无障碍服务强依赖门禁与状态即时同步
- **背景**：无障碍服务是防卸载、防关权限、闹钟防关机与隐私空间保护的底层执行基石。此前无障碍服务申请关闭后，UI 刷新缺失且未严格阻断子保护的开启，造成“无障碍待关闭但子保护仍可独立开启”的逻辑违背。
- **决策**：
  1. 确立无障碍服务绝对前置门禁：当无障碍服务处于「系统未开启」或「待关闭（明日生效）」时，一律禁止单独开启防卸载、防关权限、闹钟防关机与隐私空间保护，点击开启时开关回弹并弹出轻量 Toast 提示。
  2. 修复弹窗确认关闭与取消申请时的即时 UI 状态刷新，统一文案为「`状态: ⏳ 待关闭（明日生效）`」（橙色 `#FF9800`）。

### [DECISION-08] 规范问题反馈 4 要素范式与双官网 Issues 分级渠道
- **背景**：此前应用内日志导出收件人为占位假邮箱（`support@example.com`），且用户反馈由于缺乏机型、ROM、知止版本和时间戳上下文，开发者无法有效排错。合规文本与外部文档中亦遗留占位符。
- **决策**：
  1. 确立「三级反馈渠道体系」：首选 GitHub/Gitee Issues 公开追踪，私密/离线反馈使用邮箱 `cyz200303@163.com`，应用内「设置 → 🧾 日志诊断 → 发送日志」定性为日志提取取证与分享工具。
  2. 确立「4 要素问题反馈范式」：运行环境（机型+ROM+Android版本）、应用版本（Version/Commit SHA）、操作与复现步骤（时间+期望+实际异常）、取证日志附件（`app_log.txt`）。

### [DECISION-10] 全屏沉浸式防前置摄像头安全区与弹窗主题无缝化规范
- **背景**：Android 现代设备多采用顶部打孔前置摄像头与刘海屏。若全屏 Activity 或主页面未严格计算 WindowInsets，顶部标题栏、操作按钮将被摄像头遮挡；同时弹窗内部硬编码背景色产生割裂的独立小框。
- **决策**：
  1. 所有全屏 Activity（`AppSelectionActivity`、`GlobalParamsActivity` 等）及主页面，必须通过 `ViewCompat.setOnApplyWindowInsetsListener` 监听 `statusBars() or displayCutout()` 并动态设置顶部 padding，确保防前置摄像头留白。
  2. 弹窗移除内嵌硬编码背景，完全融入四季主题 `?attr/zzSurface`；彻底剔除硬编码 hex 颜色与非主题 emoji（如轮椅图标 `♿`），遵循主题 Token。

### [DECISION-11] 闹钟响铃界面四季主题深度联动与生活化文案净化
- **背景**：此前闹钟响铃悬浮窗与扫码界面硬编码暗黑纯黑底色（`#0B1114`），未跟随知止系统的四季主题（春·苔樱、夏·潮汐、秋·茶陶、冬·雪岚）动态联动，导致夜间/清晨界面割裂突兀；且双阶段冷却文案中存在“请勿沉迷手机”等对用户的说教与贬低用语。
- **决策**：
  1. **四季主题联动**：`window_alarm_overlay.xml` 与 `activity_alarm_scan.xml` 根容器背景统一绑定为 `?attr/zzPageBg`，卡片与输入容器采用 `?attr/zzSurface`，文字统一采用 `?attr/zzText`；`AlarmActivity` 在 `onCreate()` 首行统一调用 `ThemeManager.apply(this)`；`LockAlarmOverlay` 在 inflate 时使用 `ContextThemeWrapper(ctx, ThemeManager.themeRes(ctx))` 包裹，各动态组件通过 `ThemeManager.getColorAttr` 注入当前主题色值。
  2. **生活化文案净化**：彻底移除“勿沉迷手机”等说教用语，改为正向、温和的生活化提示：“可以先去刷牙、洗脸，挑战界面不会关闭”。
  3. **契约测试守护**：增加 `AlarmOverlayThemeContractTest`，对布局背景、Activity 主题注入、悬浮窗主题包裹及温和文案做静态断言守护，防止后续意外回归。

---

## 2. 核心防回归红线与惨痛教训

| 陷阱场景 | 根本原因 | 防御与防回归规则 |
|:---|:---|:---|
| **Vivo 视频/空闲误报桌面** | Vivo 系统在用户无操作看视频时，UsageStats 长期报系统桌面 | 引入 35s 桌面计时器兜底；到期时若检测到桌面且存在桌面累计，坚决执行重锁。 |
| **息屏睡死到期不重锁** | 息屏期间前台包名常为空，重试超时后直接退出 | 重试超时后调用 `forceRelockAfterExpiry()` 强制弹锁屏。 |
| **取证与业务决策串扰** | 曾尝试利用诊断投票修改黑名单放行判定 | **绝对红线**：诊断与取证系统只负责记录与分析，严禁直接参与任何业务决策。 |
| **跨规则边界前台持续使用漏锁** | 用户在规则生效前进入黑名单应用并持续使用，因旧前台事件超过 8s 导致常规 ForegroundGuard 拒绝，且无无障碍服务时 60s 降级门禁因 USAGE_STATS/CACHE 切换反复 reset | 建立未关闭前台 Session 状态机 `ForegroundSessionTracker`；当发生 ALLOW->BLOCK 规则边沿跃迁时执行单次 Session 边界锁定；FallbackGate 联动 Session 阻止状态抖动重置。 |

### [DECISION-09] 移除弹性调度

- 用户确认该功能价值不足，移除首页按钮、时段标题点击入口和专用时间平移方法。
- 不再通过快捷调度改变开始时间并奖惩额度；常规时段编辑保持原流程。
- 不对已经保存的时段及额度进行迁移或还原，避免猜测历史调整量而覆盖用户配置。

### [DECISION-07] 统一休息与临时解锁的冷却机制
- **决策**：废除原设计的独立冷却计时，引入全局统一的“冷静期间隙”。临时解锁与休息共享该计时器，冷静期内强行申请临时解锁也将触发双倍扣减惩罚。
- **原因**：降低认知负担，防止用户利用无惩罚（或惩罚较轻）的临时解锁来规避休息冷静期的双倍扣费，堵住逃避自律的漏洞。

### [DECISION-08] 休息时长与额度扣减动态折算 & 废除主页独立临时解锁
- **决策**：休息申请按时长动态扣次（≤3分钟扣0.5次，>3分钟扣1次）。全局“临时解锁”概念废除，转为应用时间窗专属的“应用短暂解锁”。主页移除特权按钮与临时解锁按钮，极致精简。
- **原因**：避免主页按钮冗杂（申请休息和临时解锁同时出现导致认知混乱）。细化短休息概念以贴合用户“只需看一眼微信”的微小需求，又不至于消耗完整次数。保证“总次数”约束力的前提下提升灵活度。

### [DECISION-09] 确立三大解锁机制的正交权能矩阵 (临时放行 / 特权 / 休息)
- **背景**：此前临时放行、应用时间窗解锁与休息的边界存在重叠与连带勾选的繁琐交互（如勾选“连带解开勿扰锁”），概念边界不够锋利。
- **决策**：
  1. **临时放行**：定性为「短期内解开任意锁」。无需额外勾选，直接解开眼前遇到的任何锁（含勿扰锁与时间窗锁）。
  2. **特权**：定性为「长时间解开任意锁」。每周有限额度（1~2次），用于长时间需要全面解除限制的场景。
  3. **休息**：定性为「只能解开勿扰锁」。不可解开应用时间窗锁，防沉迷底线不因小憩而动摇；继续维持 ≤3min 扣 0.5 次、>3min 扣 1 次的轻量化阶梯折算。
- **价值**：彻底消除多余勾选框与认知困扰，三把钥匙各司其职，权力边界极其清晰。

### [DECISION-10] 双阶段闹钟流程重构：离床验证 + 延迟关闭缓冲（可选晨间任务）
- **背景**：此前起床闹钟被定性为“两道关卡强制闯关”，第一关被窄化为扫码，第二关被窄化为做题，中间强制等待冷却。用户真实痛点不是“早上做两套题”，而是“人虽然离床扫码了，但下床后第一件事立刻坐下来拿手机刷”；且并非所有人都想在清晨做题。
- **决策**：
  1. **重新定义双阶段流程**：第一阶段为「离床验证」，核心是逼人离开床铺产生真实物理动作（二维码是手段而非唯一概念）；第二阶段为「防回床延迟关闭缓冲（可选晨间任务）」，核心是“不要让闹钟动作立刻结束”，进入锁屏缓冲期（如 5 分钟），锁定手机防止沉迷。
  2. **严格双重门禁**：若第二阶段未勾选任何任务，延迟倒计时结束后**自动关闭闹钟并解锁手机**（纯延迟洗漱模式）；若勾选了第二阶段任务（计算/单词/填空），倒计时结束后才开放任务卡片，必须完成题目方可关闭闹钟。
  3. **延迟倒计时持久化续接**：倒计时依赖 `firstStagePassedAt` 持久化时间戳。进程杀死、Direct Boot 重启或屏幕重亮时自动续接；若已过期且无任务，直接执行关闭解除，杜绝死锁。
  4. **合规与题库生态**：知止提供晨间任务框架而非预置全量题库；公版内容入库，考研、考公等合法资料由用户自行导入。

### [DECISION-11] 闹钟默认设置持久化容错与测试闹钟全对齐闭环
- **背景**：
  1. 用户在「默认设置」修改后保存，再次点进发现修改未生效。根因是 `AlarmScheduler.getDefaultStage2Types` 在读取空集合（即用户选择第二阶段无任务、纯延迟关闭模式）时，因 `stored.isNotEmpty()` 检查失败而强制回退到默认的 `math, poem, word`，覆盖了用户的空选项；且 `cbAutoEscalate` 在默认设置弹窗初始化及保存路径遗漏了读写绑定。
  2. 测试闹钟弹窗与新增/编辑闹钟的配置项未完全对齐（缺少「5分钟后自动升级到最大」、自定义铃声选择等），且测试弹窗中的表单项固定未预填全局默认配置，用户无法直观在测试中感知与调整真实生效的默认参数。
- **决策**：
  1. **SharedPreferences 语义容错**：`getDefaultStage2Types` 先检查 `prefs.contains(KEY_DEFAULT_STAGE2_TYPES)`。只要键已存在，即使值为空集合也完整尊重（代表用户选定的纯延迟关闭），不再回退；保存时统一采用新构造的 `HashSet(types)`，避免 SharedPreferences 对 `Set` 复用引用的脏读。
  2. **测试闹钟与新增闹钟 100% 对齐**：测试弹窗打开时完整预填全局默认配置（解锁模式、第一/二阶段题型、题目数、渐进/固定震动及上限、铃声模式、自定义铃声、5分钟自动升级、延迟关闭秒数等）；所有字段在 `LockWindowManager.TestAlarmConfig` 与运行时全链路传递。
  3. **双向测试与保存闭环**：
     - 测试弹窗提供【开始测试】、【保存为默认设置】与【取消】。点击【保存为默认设置】后将当前表单内容即时持久化为全局默认设置，并**保持弹窗开启**，方便用户继续点击【开始测试】或微调。
     - 「默认设置」弹窗新增【测试当前配置】按钮，无需在不同入口之间往返跳转。

### [DECISION-12] 前台未关闭会话 (ForegroundSessionTracker) 与规则边界即时锁定
- **背景**：用户在规则生效前进入黑名单应用（如微信），之后持续停留在该应用内。当 23:00 勿扰规则生效时，因旧前台事件超过 `MAX_FOREGROUND_EVENT_AGE_MS = 8s`，导致常规 `ForegroundGuard` 拒绝锁定。特别在无无障碍服务场景下，`ForegroundGuardFallbackGate`（60 秒保底门禁）因证据源在 `CACHE` 与 `USAGE_STATS` 间抖动被反复 `reset()`，导致用户停留数小时都不会触发锁屏，直到切出再切回才触发。
- **决策**：
  1. **建立纯逻辑会话状态机 `ForegroundSessionTracker`**：通过 UsageEvents（MOVE_TO_FG / ACTIVITY_RESUMED）开启 Session，收到反证（MOVE_TO_BG / ACTIVITY_PAUSED / ACTIVITY_STOPPED / 其它正常应用或桌面进入前台 / 屏幕关闭 / 锁屏）时关闭或替换当前 Session。过滤系统 UI 与输入法等瞬时干扰。
  2. **内存纯粹性与进程隔离**：Session 纯内存维护，**禁止跨进程持久化**，服务重启必须重新获取有效前台事件方可建立会话，杜绝旧持久化缓存误锁。
  3. **规则边沿跃迁单次锁定 (ALLOW → BLOCK)**：通过全局模式跃迁（Gap->DND、Rest->Work）及单应用拦截状态边沿检测，在规则刚刚收紧时激活一次性边界锁定权限。若常规守卫未过但存在未关闭的前台 Session 且屏幕未锁屏，即时触发锁机并消耗该权限，防止普通轮询无限制依赖旧会话。
  4. **FallbackGate 联动会话**：修改 `isSustainedForegroundFallbackEligible`，当具有活跃 Session 时允许 `USAGE_STATS` 维持资格，解决证据源切换抖动导致 60s 计数清零的痛点，同时天然阻断纯后台音频应用。
  5. **边界门禁解耦与测试守护 (RuleBoundaryLockGate)**：将边界跃迁逻辑从 Android `MonitorService` 庞大流程中解耦为纯对象 `RuleBoundaryLockGate`，支持离线 100% 纯 JVM 单元测试，全面守护跨规则边界跃迁、稳态防无限重锁、息屏与锁屏阻断场景。
  6. **完整双锁判定提前与 Keyguard 生命周期接线**：提前使用 `LockDecisionPolicy.shouldBlock` 计算最终业务拦截状态，统一覆盖 DND 勿扰与应用时间窗（App Window）双锁边界；Keyguard 锁定立即执行 `onKeyguardLocked` 永久关闭旧会话，防止解锁后旧 Session 残留。
  7. **状态机电平与边沿严格解耦**：`RuleBoundaryLockGate` 坚决杜绝把 `wasGap` / `wasRest` 等持续状态（Level）作为放行依据，改为显式边沿跃迁标志 `leftGap`（`wasGap && !currentIsGap`）与 `endedRest`（`wasRest && !currentIsRest`），仅在脱离自由时段的瞬间激活一次；单次边界标记 `mForceRuleBoundaryCheck` 触发后立即消费清零，彻底防范稳态期间无限重锁或守卫穿透。
  8. **跨 Session 显式生命周期治理与系统 UI 噪声过滤**：在用户回到桌面、PWA 绕过、进入自研主界面、锁屏及息屏等切出场景，显式调用 `foregroundGuardFallbackGate.reset()`，断绝 60s 降级保底计时跨 Session 泄漏；将系统 UI 噪声过滤精准扩展至 Vivo 状态栏插件（`com.vivo.systemuiplugin`）与手势导航（`com.vivo.upslide`），同时白名单保护桌面 Launcher 绝不被误判为噪声。
  9. **业务边沿判定与前台守卫完全解耦**：建立独立的 `mLastBusinessBlockedByPackage` 状态映射，直接基于业务模型计算 `isBusinessAllowToBlock` 边沿，杜绝因旧前台事件超过 8s 提前返回导致时间窗关闭边沿丢失的问题；单次强制标记 `mForceRuleBoundaryCheck` 确保在当前轮次第一次判定时无条件消费清零。
  10. **Activity 级多页面生命周期跟踪与 UsageEvents 签名精准去重**：`ForegroundSessionTracker` 升级跟踪活跃 Activity 集合，防止应用内部页面切换（A.paused -> B.resumed -> A.stopped）中迟到的 A.stopped 误关正在前台的 B 页面；`MonitorService` 维护 `(timestamp, type:className)` 事件签名集合，彻底杜绝 30s 历史窗口重扫描时的同毫秒事件反复重放；完成 Vivo 真机取证（详见 [docs/verification/2026-09-06-vivo-ui-session-verification.md](docs/verification/2026-09-06-vivo-ui-session-verification.md)）。

### [DECISION-13] 全量 UI 四季主题动态联动与伪动态死色根治
- **背景**：用户在切换春·苔樱、夏·潮汐、秋·茶陶、冬·雪岚四季主题时，发现三处核心 UI 元素完全没有随主题变化：
  1. 关闭无障碍服务弹窗的确认按钮；
  2. 勿扰时段页面的「延迟生效表」背景颜色；
  3. 待生效修改项对应的列表卡片高亮框。
  排查发现：`themes.xml` 中四季主题的 `zzWarning`、`zzWarningSoft`、`zzDanger`、`zzDangerSoft` 色值微调差值 < 5%（全是一模一样的暗米黄与灰紫红），造成“代码引用了 Token 但视觉完全是死色”的伪动态现象；同时无障碍关闭确认主按钮误用了非当季主色，且列表卡片未接入 MaterialCardView 高亮 Stroke。
- **决策**：
  1. **四季色彩季相基因重构**：将 Warning/Danger 系列彻底重构为肉眼清晰可辨的季相色盘：
     - 春：嫩芽草木金绿与生机浅底，春樱暮红；
     - 夏：潮汐青金与清凉浅碧海青底，浅海珊瑚红；
     - 秋：陶土焦糖金橙与温润暖杏底，晚秋枫叶砖赭红；
# 决定守护 (DeciKeeper) 架构决策与经验档案 (HISTORY)

> **定位**：本文件是「决定守护」项目的**历史决策真相（Historical Decision Truth）**，沉淀核心架构演进决策、高价值教训与防回归红线。
> 开源前完整的问题追踪与历史排查记录详见 [docs/history/issue_tracker.md](docs/history/issue_tracker.md)。

---

## 1. 关键架构决策记录 [DECISION]

### [DECISION-01] 闹钟状态与 Direct Boot 设备保护存储双写
- **背景**：Android 设备强制关机重启后，在首次输入锁屏密码前处于 Direct Boot 模式，凭据加密存储（Credential Protected Storage）不可读，导致闹钟标志位丢失无法恢复震动。
- **决策**：所有闹钟激活状态必须使用 `createDeviceProtectedStorageContext()` 双写到设备保护存储，且必须使用 `commit()` 同步写入，服务声明 `android:directBootAware="true"`。
- **防坑警告**：Direct Boot 下 `startForeground` 若通知系统未就绪可能崩溃，必须用 `try-catch` 防护，绝不阻断唤醒。

### [DECISION-02] 锁屏覆盖层代际机制 (Accessibility Generation Isolation)
- **背景**：在 Vivo 等机型上，无障碍服务被系统回收再重连时，因静态 `_isLocked=true` 与视图残留，导致新前台黑名单请求误触发 `LOCK_ALREADY_ATTACHED` 短路，产生严重漏拦。
- **决策**：`CoreService` 维护原子代际序号 `accessibilityGeneration: AtomicLong`，每次 `onServiceConnected` 递增；`LockWindowManager` 记录视图附着时的代际。仅当代际匹配时复用视图，代际过期立即强制重建。
- **防坑警告**：防卸载锁（`_isAntiUninstallLock=true`）具备最高优先级，跨代际保留，不得被黑名单普通锁请求降级。

### [DECISION-03] 防卸载拦截 5 秒热窗口对称化
- **背景**：用户在系统设置中点击“强行停止”或“卸载”时，不同厂商弹窗出现时机与文本抓取存在时间差；深度遍历 AccessibilityNode 耗时长达数百毫秒，给用户留下操作时间缝隙。
- **决策**：统一引入 5 秒热窗口（Hot Window）机制。一旦识别到进入设置关键页或点击敏感操作，立即激活热窗口，后续弹窗命中直接执行 BACK+HOME 退栈，跳过深层文本抓取。
- **防坑警告**：加严拦截规则（如隐私空间添加应用）优先采用页面特征单闸立即拦截，严禁依赖耗时的节点深度文本比对。

### [DECISION-04] 生效策略与法定基准 (Baseline) 比对
- **背景**：若放宽规则与加严规则即时混合生效，用户易在自控崩溃时反复修改规则避锁。
- **决策**：确立“加严立即生效，放松次日生效”铁律。比对基准以每日固化生成的快照文件 `iron_will_rules_baseline` 为准（默认每日 08:00，可在全局参数中自定义刷新时刻），杜绝以当天工作态作为比对源产生的漂移。

### [DECISION-05] 临时放行分段阶梯惩罚
- **背景**：早期固定 2 倍冷静期容易被恶意利用，用户会频繁短时间申请放行。
- **决策**：冷却公式实施分段阶梯（0~60s: 1x, 61~120s: 2x, 121~180s: 3x）。申请时间越长，不仅扣除额度倍增，冷却锁定惩罚更呈指数上升。

### [DECISION-06] 全面更名「知止 (ZhiZhi)」与包名重构 (com.zhizhi.app)
- **背景**：原名称与现有外部商业应用产生冲突；开源首发需要更深刻独特的东方哲学意蕴（“知足不辱，知止不殆；知止而后有定”）。
- **决策**：彻底更名为「知止」(ZhiZhi)，Android 包名全面平移至 `com.zhizhi.app`。同时在 `SettingsBackupManager` 中实现双协议解析，原生向后兼容 `ironwill_settings_backup` 导入，保障旧版数据无缝迁移。
- **防坑警告**：包名变更后为全新独立应用，不可覆盖安装旧版，需通过导出/导入备份完成规则迁移。

### [DECISION-07] 无障碍服务强依赖门禁与状态即时同步
- **背景**：无障碍服务是防卸载、防关权限、闹钟防关机与隐私空间保护的底层执行基石。此前无障碍服务申请关闭后，UI 刷新缺失且未严格阻断子保护的开启，造成“无障碍待关闭但子保护仍可独立开启”的逻辑违背。
- **决策**：
  1. 确立无障碍服务绝对前置门禁：当无障碍服务处于「系统未开启」或「待关闭（明日生效）」时，一律禁止单独开启防卸载、防关权限、闹钟防关机与隐私空间保护，点击开启时开关回弹并弹出轻量 Toast 提示。
  2. 修复弹窗确认关闭与取消申请时的即时 UI 状态刷新，统一文案为「`状态: ⏳ 待关闭（明日生效）`」（橙色 `#FF9800`）。

### [DECISION-08] 规范问题反馈 4 要素范式与双官网 Issues 分级渠道
- **背景**：此前应用内日志导出收件人为占位假邮箱（`support@example.com`），且用户反馈由于缺乏机型、ROM、知止版本和时间戳上下文，开发者无法有效排错。合规文本与外部文档中亦遗留占位符。
- **决策**：
  1. 确立「三级反馈渠道体系」：首选 GitHub/Gitee Issues 公开追踪，私密/离线反馈使用邮箱 `cyz200303@163.com`，应用内「设置 → 🧾 日志诊断 → 发送日志」定性为日志提取取证与分享工具。
  2. 确立「4 要素问题反馈范式」：运行环境（机型+ROM+Android版本）、应用版本（Version/Commit SHA）、操作与复现步骤（时间+期望+实际异常）、取证日志附件（`app_log.txt`）。

#### [DECISION-20] 统计与闹钟轻量化、防刷手机衰减与题库平铺收藏交互
- **背景**：
  1. 统计筛选与分类详情在不同主题下出现未跟随四季主题问题，星期块清空后因 MaterialButton 默认 `backgroundTint` 导致颜色残留。
  2. 闹钟编辑内嵌完整表盘 `TimePicker` 占用大半个屏幕空间，与时间规则轻量时间选择器不统一。
  3. 单次模式缺乏防刷手机延迟，做完题立刻解锁容易坐下刷手机；第一阶段完成时音量震动未平滑衰减至 1 级。
  4. 题库选择存在抽象总开关导致只勾总开关而出空题的体验 Bug，题目收藏与题库回顾、全局收藏本未互通。
- **决策**：
  1. `StatsFilterActivity` 与 `AppCategoryDetailActivity` 严格注入 `ThemeManager.apply(this)`，星期块清空时配置 `app:backgroundTint="@null"` 彻底清除选中色。
  2. 闹钟时间编辑改为紧凑时间卡片展示（如 `07:00 🕒 (点击更改)`），点击唤起 `TimePickerDialog` 浮层表盘。
  3. 单次模式在挑战完成后若配置 `stageCooldownSeconds > 0` 立即将音量与震动衰减为 1（单项降单项，两者均有均降），启动防刷手机倒计时缓冲，倒计时结束自动显示关闭按钮。
  4. 彻底移除冗余的“自定义题库”顶层总开关 CheckBox，直接平铺显示具体导入题库（名称、题数、复选框与抽取数），未勾选具体题库绝不出题。
  5. 闹钟关闭后的复习回顾卡片（诗词、单词、选词、自定义题）增加「⭐ 收藏」切换交互；自定义题库支持题目级 `isFavorite` 状态持久化，与题库回顾页「⭐ 收藏」Tab 及全局「我的收藏」（`BookmarkActivity`）双向互通。

### [DECISION-10] 全屏沉浸式防前置摄像头安全区与弹窗主题无缝化规范
- **背景**：Android 现代设备多采用顶部打孔前置摄像头与刘海屏。若全屏 Activity 或主页面未严格计算 WindowInsets，顶部标题栏、操作按钮将被摄像头遮挡；同时弹窗内部硬编码背景色产生割裂的独立小框。
- **决策**：
  1. 所有全屏 Activity（`AppSelectionActivity`、`GlobalParamsActivity` 等）及主页面，必须通过 `ViewCompat.setOnApplyWindowInsetsListener` 监听 `statusBars() or displayCutout()` 并动态设置顶部 padding，确保防前置摄像头留白。
  2. 弹窗移除内嵌硬编码背景，完全融入四季主题 `?attr/zzSurface`；彻底剔除硬编码 hex 颜色与非主题 emoji（如轮椅图标 `♿`），遵循主题 Token。

### [DECISION-12] 统计页 V20 行为中心化重构与图表自适应引擎
- **背景**：旧版统计页基于“周/月/年 + 休息时长/勿扰时长”模式，将勿扰时段本身（规则边界而非用户主动行为）作为一级统计，且跨度聚合时粗暴固定图表形式，无法呈现打开次数、双阶段起床耗时、规则放宽与恢复状态。
- **决策**：
  1. 确立「休息（默认）/ 起床 / 应用 / 规则保护」四合一统计体系，移除“勿扰时长”一级统计；
  2. 解耦两层正交筛选：时间范围只决定起止日期；日期构成支持任意星期组合与左右双栏互移应用范围筛选；
  3. 图表阈值自适应引擎：先计算实际绘图数据点数量，$N \le 12$ 采用堆叠/分组柱状图，$N > 12$ 采用同语义累加折线图；
  4. 规则保护按稳定待放宽状态（完全恢复/部分恢复/最终生效/仍待生效）跟踪 episode，杜绝按时间切会话导致的误判；
  5. 严格单向分层（`ui/` ➔ `manager/statistics/` ➔ `data/statistics/`），零 SharedPreferences 直接读写。

---

## 2. 核心防回归红线与惨痛教训

| 陷阱场景 | 根本原因 | 防御与防回归规则 |
|:---|:---|:---|
| **Vivo 视频/空闲误报桌面** | Vivo 系统在用户无操作看视频时，UsageStats 长期报系统桌面 | 引入 35s 桌面计时器兜底；到期时若检测到桌面且存在桌面累计，坚决执行重锁。 |
| **息屏睡死到期不重锁** | 息屏期间前台包名常为空，重试超时后直接退出 | 重试超时后调用 `forceRelockAfterExpiry()` 强制弹锁屏。 |
| **取证与业务决策串扰** | 曾尝试利用诊断投票修改黑名单放行判定 | **绝对红线**：诊断与取证系统只负责记录与分析，严禁直接参与任何业务决策。 |
| **跨规则边界前台持续使用漏锁** | 用户在规则生效前进入黑名单应用并持续使用，因旧前台事件超过 8s 导致常规 ForegroundGuard 拒绝，且无无障碍服务时 60s 降级门禁因 USAGE_STATS/CACHE 切换反复 reset | 建立未关闭前台 Session 状态机 `ForegroundSessionTracker`；当发生 ALLOW->BLOCK 规则边沿跃迁时执行单次 Session 边界锁定；FallbackGate 联动 Session 阻止状态抖动重置。 |

### [DECISION-09] 移除弹性调度

- 用户确认该功能价值不足，移除首页按钮、时段标题点击入口和专用时间平移方法。
- 不再通过快捷调度改变开始时间并奖惩额度；常规时段编辑保持原流程。
- 不对已经保存的时段及额度进行迁移或还原，避免猜测历史调整量而覆盖用户配置。

### [DECISION-07] 统一休息与临时解锁的冷却机制
- **决策**：废除原设计的独立冷却计时，引入全局统一的“冷静期间隙”。临时解锁与休息共享该计时器，冷静期内强行申请临时解锁也将触发双倍扣减惩罚。
- **原因**：降低认知负担，防止用户利用无惩罚（或惩罚较轻）的临时解锁来规避休息冷静期的双倍扣费，堵住逃避自律的漏洞。

### [DECISION-08] 休息时长与额度扣减动态折算 & 废除主页独立临时解锁
- **决策**：休息申请按时长动态扣次（≤3分钟扣0.5次，>3分钟扣1次）。全局“临时解锁”概念废除，转为应用时间窗专属的“应用短暂解锁”。主页移除特权按钮与临时解锁按钮，极致精简。
- **原因**：避免主页按钮冗杂（申请休息和临时解锁同时出现导致认知混乱）。细化短休息概念以贴合用户“只需看一眼微信”的微小需求，又不至于消耗完整次数。保证“总次数”约束力的前提下提升灵活度。

### [DECISION-09] 确立三大解锁机制的正交权能矩阵 (临时放行 / 特权 / 休息)
- **背景**：此前临时放行、应用时间窗解锁与休息的边界存在重叠与连带勾选的繁琐交互（如勾选“连带解开勿扰锁”），概念边界不够锋利。
- **决策**：
  1. **临时放行**：定性为「短期内解开任意锁」。无需额外勾选，直接解开眼前遇到的任何锁（含勿扰锁与时间窗锁）。
  2. **特权**：定性为「长时间解开任意锁」。每周有限额度（1~2次），用于长时间需要全面解除限制的场景。
  3. **休息**：定性为「只能解开勿扰锁」。不可解开应用时间窗锁，防沉迷底线不因小憩而动摇；继续维持 ≤3min 扣 0.5 次、>3min 扣 1 次的轻量化阶梯折算。
- **价值**：彻底消除多余勾选框与认知困扰，三把钥匙各司其职，权力边界极其清晰。

### [DECISION-10] 双阶段闹钟流程重构：离床验证 + 延迟关闭缓冲（可选晨间任务）
- **背景**：此前起床闹钟被定性为“两道关卡强制闯关”，第一关被窄化为扫码，第二关被窄化为做题，中间强制等待冷却。用户真实痛点不是“早上做两套题”，而是“人虽然离床扫码了，但下床后第一件事立刻坐下来拿手机刷”；且并非所有人都想在清晨做题。
- **决策**：
  1. **重新定义双阶段流程**：第一阶段为「离床验证」，核心是逼人离开床铺产生真实物理动作（二维码是手段而非唯一概念）；第二阶段为「防回床延迟关闭缓冲（可选晨间任务）」，核心是“不要让闹钟动作立刻结束”，进入锁屏缓冲期（如 5 分钟），锁定手机防止沉迷。
  2. **严格双重门禁**：若第二阶段未勾选任何任务，延迟倒计时结束后**自动关闭闹钟并解锁手机**（纯延迟洗漱模式）；若勾选了第二阶段任务（计算/单词/填空），倒计时结束后才开放任务卡片，必须完成题目方可关闭闹钟。
  3. **延迟倒计时持久化续接**：倒计时依赖 `firstStagePassedAt` 持久化时间戳。进程杀死、Direct Boot 重启或屏幕重亮时自动续接；若已过期且无任务，直接执行关闭解除，杜绝死锁。
  4. **合规与题库生态**：知止提供晨间任务框架而非预置全量题库；公版内容入库，考研、考公等合法资料由用户自行导入。

### [DECISION-11] 闹钟默认设置持久化容错与测试闹钟全对齐闭环
- **背景**：
  1. 用户在「默认设置」修改后保存，再次点进发现修改未生效。根因是 `AlarmScheduler.getDefaultStage2Types` 在读取空集合（即用户选择第二阶段无任务、纯延迟关闭模式）时，因 `stored.isNotEmpty()` 检查失败而强制回退到默认的 `math, poem, word`，覆盖了用户的空选项；且 `cbAutoEscalate` 在默认设置弹窗初始化及保存路径遗漏了读写绑定。
  2. 测试闹钟弹窗与新增/编辑闹钟的配置项未完全对齐（缺少「5分钟后自动升级到最大」、自定义铃声选择等），且测试弹窗中的表单项固定未预填全局默认配置，用户无法直观在测试中感知与调整真实生效的默认参数。
- **决策**：
  1. **SharedPreferences 语义容错**：`getDefaultStage2Types` 先检查 `prefs.contains(KEY_DEFAULT_STAGE2_TYPES)`。只要键已存在，即使值为空集合也完整尊重（代表用户选定的纯延迟关闭），不再回退；保存时统一采用新构造的 `HashSet(types)`，避免 SharedPreferences 对 `Set` 复用引用的脏读。
  2. **测试闹钟与新增闹钟 100% 对齐**：测试弹窗打开时完整预填全局默认配置（解锁模式、第一/二阶段题型、题目数、渐进/固定震动及上限、铃声模式、自定义铃声、5分钟自动升级、延迟关闭秒数等）；所有字段在 `LockWindowManager.TestAlarmConfig` 与运行时全链路传递。
  3. **双向测试与保存闭环**：
     - 测试弹窗提供【开始测试】、【保存为默认设置】与【取消】。点击【保存为默认设置】后将当前表单内容即时持久化为全局默认设置，并**保持弹窗开启**，方便用户继续点击【开始测试】或微调。
     - 「默认设置」弹窗新增【测试当前配置】按钮，无需在不同入口之间往返跳转。

### [DECISION-12] 前台未关闭会话 (ForegroundSessionTracker) 与规则边界即时锁定
- **背景**：用户在规则生效前进入黑名单应用（如微信），之后持续停留在该应用内。当 23:00 勿扰规则生效时，因旧前台事件超过 `MAX_FOREGROUND_EVENT_AGE_MS = 8s`，导致常规 `ForegroundGuard` 拒绝锁定。特别在无无障碍服务场景下，`ForegroundGuardFallbackGate`（60 秒保底门禁）因证据源在 `CACHE` 与 `USAGE_STATS` 间抖动被反复 `reset()`，导致用户停留数小时都不会触发锁屏，直到切出再切回才触发。
- **决策**：
  1. **建立纯逻辑会话状态机 `ForegroundSessionTracker`**：通过 UsageEvents（MOVE_TO_FG / ACTIVITY_RESUMED）开启 Session，收到反证（MOVE_TO_BG / ACTIVITY_PAUSED / ACTIVITY_STOPPED / 其它正常应用或桌面进入前台 / 屏幕关闭 / 锁屏）时关闭或替换当前 Session。过滤系统 UI 与输入法等瞬时干扰。
  2. **内存纯粹性与进程隔离**：Session 纯内存维护，**禁止跨进程持久化**，服务重启必须重新获取有效前台事件方可建立会话，杜绝旧持久化缓存误锁。
  3. **规则边沿跃迁单次锁定 (ALLOW → BLOCK)**：通过全局模式跃迁（Gap->DND、Rest->Work）及单应用拦截状态边沿检测，在规则刚刚收紧时激活一次性边界锁定权限。若常规守卫未过但存在未关闭的前台 Session 且屏幕未锁屏，即时触发锁机并消耗该权限，防止普通轮询无限制依赖旧会话。
  4. **FallbackGate 联动会话**：修改 `isSustainedForegroundFallbackEligible`，当具有活跃 Session 时允许 `USAGE_STATS` 维持资格，解决证据源切换抖动导致 60s 计数清零的痛点，同时天然阻断纯后台音频应用。
  5. **边界门禁解耦与测试守护 (RuleBoundaryLockGate)**：将边界跃迁逻辑从 Android `MonitorService` 庞大流程中解耦为纯对象 `RuleBoundaryLockGate`，支持离线 100% 纯 JVM 单元测试，全面守护跨规则边界跃迁、稳态防无限重锁、息屏与锁屏阻断场景。
  6. **完整双锁判定提前与 Keyguard 生命周期接线**：提前使用 `LockDecisionPolicy.shouldBlock` 计算最终业务拦截状态，统一覆盖 DND 勿扰与应用时间窗（App Window）双锁边界；Keyguard 锁定立即执行 `onKeyguardLocked` 永久关闭旧会话，防止解锁后旧 Session 残留。
  7. **状态机电平与边沿严格解耦**：`RuleBoundaryLockGate` 坚决杜绝把 `wasGap` / `wasRest` 等持续状态（Level）作为放行依据，改为显式边沿跃迁标志 `leftGap`（`wasGap && !currentIsGap`）与 `endedRest`（`wasRest && !currentIsRest`），仅在脱离自由时段的瞬间激活一次；单次边界标记 `mForceRuleBoundaryCheck` 触发后立即消费清零，彻底防范稳态期间无限重锁或守卫穿透。
  8. **跨 Session 显式生命周期治理与系统 UI 噪声过滤**：在用户回到桌面、PWA 绕过、进入自研主界面、锁屏及息屏等切出场景，显式调用 `foregroundGuardFallbackGate.reset()`，断绝 60s 降级保底计时跨 Session 泄漏；将系统 UI 噪声过滤精准扩展至 Vivo 状态栏插件（`com.vivo.systemuiplugin`）与手势导航（`com.vivo.upslide`），同时白名单保护桌面 Launcher 绝不被误判为噪声。
  9. **业务边沿判定与前台守卫完全解耦**：建立独立的 `mLastBusinessBlockedByPackage` 状态映射，直接基于业务模型计算 `isBusinessAllowToBlock` 边沿，杜绝因旧前台事件超过 8s 提前返回导致时间窗关闭边沿丢失的问题；单次强制标记 `mForceRuleBoundaryCheck` 确保在当前轮次第一次判定时无条件消费清零。
  10. **Activity 级多页面生命周期跟踪与 UsageEvents 签名精准去重**：`ForegroundSessionTracker` 升级跟踪活跃 Activity 集合，防止应用内部页面切换（A.paused -> B.resumed -> A.stopped）中迟到的 A.stopped 误关正在前台的 B 页面；`MonitorService` 维护 `(timestamp, type:className)` 事件签名集合，彻底杜绝 30s 历史窗口重扫描时的同毫秒事件反复重放；完成 Vivo 真机取证（详见 [docs/verification/2026-09-06-vivo-ui-session-verification.md](docs/verification/2026-09-06-vivo-ui-session-verification.md)）。

### [DECISION-13] 全量 UI 四季主题动态联动与伪动态死色根治
- **背景**：用户在切换春·苔樱、夏·潮汐、秋·茶陶、冬·雪岚四季主题时，发现三处核心 UI 元素完全没有随主题变化：
  1. 关闭无障碍服务弹窗的确认按钮；
  2. 勿扰时段页面的「延迟生效表」背景颜色；
  3. 待生效修改项对应的列表卡片高亮框。
  排查发现：`themes.xml` 中四季主题的 `zzWarning`、`zzWarningSoft`、`zzDanger`、`zzDangerSoft` 色值微调差值 < 5%（全是一模一样的暗米黄与灰紫红），造成“代码引用了 Token 但视觉完全是死色”的伪动态现象；同时无障碍关闭确认主按钮误用了非当季主色，且列表卡片未接入 MaterialCardView 高亮 Stroke。
- **决策**：
  1. **四季色彩季相基因重构**：将 Warning/Danger 系列彻底重构为肉眼清晰可辨的季相色盘：
     - 春：嫩芽草木金绿与生机浅底，春樱暮红；
     - 夏：潮汐青金与清凉浅碧海青底，浅海珊瑚红；
     - 秋：陶土焦糖金橙与温润暖杏底，晚秋枫叶砖赭红；
     - 冬：雪岚冷金灰与寒江冰霜浅蓝底，雪夜暮紫红。
  2. **统一主操作按钮的主题色彩流转**：关闭无障碍服务确认主按钮与全 App 弹窗主按钮保持 100% 一致，统一绑定 `?attr/zzAccent`，切主题即随当季主色动态流转。
  3. **待生效修改项 MaterialCardView 高亮边框体系**：
     - 列表项升级为 `MaterialCardView`，为“待生效: 修改”项赋予当季 `warningColor` 的 1.5dp 高亮边框（Stroke）与 `warningSoft` 季节浅底；
     - 新增/删除项分别动态绑定 1.5dp 的 `successColor` / `dangerColor` 高亮框，普通项维持 1dp `zzLine`；
     - 彻底消除删除/撤回按钮的硬编码色值。
  4. **全量 UI 随主题变化红线**：写入 `AGENT_CORE.md` 业务红线 8，严禁伪动态死色与脱离主题的独立组件。
  5. **锁屏全屏界面与遮罩四季主题彻底打通**：
     - 根治 `LockWindowManager` 中将 ContextThemeWrapper 硬编码为 `R.style.Theme_IronWill`（春季）导致夏/秋/冬锁屏一动不动的历史遗留问题，统一使用 `ThemeManager.themeRes(context)`；
     - `showLockScreen` 检测主题变更并重构 View，杜绝静态 View 脏缓存；
     - 重构快速锁屏/防卸载初始化遮罩 `window_fast_lock.xml` 为素雅主题 Token 样式，清除旧版生硬蓝绿硬编码与 Emoji。

### [DECISION-14] 临时解锁/休息计费简化（整单翻倍与借用翻倍）及额度耗尽置灰体系
- **背景**：
  1. 用户反馈历史扣费模型过于复杂：特别是增加了 $\le 3$ 分钟短休息/解锁按 0.5 次扣除后，冷却期内又按照冷却剩余秒数与 DND 秒数碎切加权，导致出现加权小数比率并四舍五入，用户难以理解为什么冷却期申请 3 分钟次数没有翻倍；
  2. 锁屏界面上，当临时解锁的当前额度与借用额度全部用尽后，按钮没有变灰禁用（时长或次数任一耗尽均应禁用）。
- **决策**：
  1. **彻底取消碎秒加权，升级为整单直接翻倍体系**：
     - **基础次数阶梯**：申请时长 $\le 3$ 分钟（180秒）基础扣 0.5 次（1 halfUnit）；$> 3$ 分钟基础扣 1.0 次（2 halfUnits）；
     - **冷却期内申请（整单直接翻倍 ×2）**：只要申请时处于冷却期内，时长直接翻倍扣 $2T$；次数直接翻倍（$\le 3$ 分钟扣 1 次，$> 3$ 分钟扣 2 次）；
     - **跨段借用惩罚（借一还二，下阶段偿还翻倍 ×2）**：不足部分从下一阶段借入，下阶段扣除偿还翻倍（借用本金 × 2）。例如冷却期申请 3 分钟全靠借用，本次扣 6 分钟/1次，下阶段偿还扣 12 分钟/2次；
  2. **锁屏临时解锁按钮耗尽置灰与文案更新**：
     - **纯函数耗尽判定**：`totalAvailableMins < 0.1 || totalAvailableCountHalfUnits < 1` 即判定为额度耗尽；
     - **视觉与交互呈现**：按钮变灰禁用（`isEnabled = false`, `alpha = 0.5f`），文案变更为「临时解锁已用完」；
      - **全链路覆盖**：时间窗锁屏主操作按钮（`btnEmergency`）与勿扰锁屏独立按钮（`btnTemporaryAccess`）同步生效，点击安全拦截并 Toast 提示。

### [DECISION-15] 自定义挑战题库（Markdown 智能导入与双重回顾）架构闭环
- **背景**：用户反馈起床挑战中的自带内容（数学/公版古诗词/英语词汇）无法满足备考（如考研政治、法考、医考、专业课）或个性化知识点复习的强诉求；且市面自律软件缺乏自主题库导入与错题回顾体系。同时，知止作为开源自律软件，不适合在仓库内直接预置有版权争议的商业辅导题库，必须通过引导用户自行提供资料/借助 AI 生成并导入的方式实现。
- **决策**：
  1. **通用单选题模型与纯 Kotlin 解析引擎**：
     - 确立统一四选一格式（题干/材料、A/B/C/D 4个选项、标准答案、解析/知识点短文）；
     - 实现 `CustomQuestionBankParser`，兼容 `---` 与 `###` 分块，支持行号级语法警告定位（`ParseIssue`），确保畸形输入绝不导致应用崩溃；
  2. **内置 AI 出题提示词与模板一键复制**：
     - 在导入界面提供「🤖 复制 AI 出题提示词」与「📋 复制 Markdown 模板」，用户可直接发给 ChatGPT/DeepSeek/Claude 批量生成符合格式的标准 Markdown 内容；
  3. **多题库管理与闹钟两阶段解耦接入**：
     - 支持题库多选与独立开关（`enabled`），`AlarmScheduler` 与 `LockWindowManager` 完整对齐 `customCount` 与 `useCustom` 配置；
     - 题库在两阶段中均可按需勾选，既可作为第一阶段离床验证，也可作为第二阶段延迟关闭后的晨间清醒任务；
  4. **双重回顾闭环机制**：
     - **即时复盘**：关闹钟后的回顾覆盖层新增任务卡片 Tab，原位展示刚才挑战中涉及题目的完整题干、正确选项与深度解析；
     - **常驻错题本**：提供题库列表（`CustomQuestionBankActivity`）与题目回顾（`CustomQuestionReviewActivity`），按题库或全量错题（`wrongCount > 0`）精准筛选，支持将已掌握题目一键移出错题本；
  5. **100% 四季主题接入与合规规范**：
     - 所有新建布局、列表卡片与控件完全遵循 `?attr/zz*` 主题 Token，所有新建 Activity 启动前严格执行 `ThemeManager.apply(this)`，杜绝硬编码颜色。

### [DECISION-16] 统计图表鲜明辨识色盘与视觉高对比度规范
- **背景**：
  1. 用户反馈统计页中「休息」、「临时解锁」和「特权」在堆叠柱和累加面积折线图中颜色区分极小。排查发现此前图表颜色直接绑定了四季主题中的莫兰迪色系（`zzAccent`、`zzAlt`、`zzWarning`），在春季等主题下，`zzAccent`（苔绿）与 `zzWarning`（棕绿/草木金绿）色相极近，且图表内部无文字标签，用户无法快速辨析各个分类；
  2. 统计页的「时长」与「次数」子维度切换按钮以及 KPI 关键指标卡片的副文本（累计时长）可读性严重下降：切换按钮未选中态与卡片底色模糊融合，字体字号过小（9sp）且误用极浅的 `zzSubtle`，导致“时长和次数看不清，字体颜色和背景颜色没有区分开”。
- **决策**：
  1. **鲜明高饱和图表色盘标准**：
     - 在 `StatisticsChartRenderer` 确立专门用于无内嵌文字图表的多维度互补色盘，色轮上大跨度分散：
       - 休息 (Rest)：翡翠绿 `0xFF2E7D32`（`#2E7D32`）
       - 临时解锁 (Temp Unlock)：亮蓝 `0xFF1976D2`（`#1976D2`）
       - 特权 (Privilege)：亮橙 `0xFFEA580C`（`#EA580C`）
       - 起床闹钟：计划时间亮绿，完成/耗时亮橙
       - 规则保护：完全恢复亮绿，部分恢复亮蓝，最终生效亮橙
     - 图例小圆点（`dot_legend_*`）在 `StatisticsFragment` 中像素级与上述图表数据集色彩同步。
  2. **视觉强对比度与排版规范**：
     - **选中按钮/页签**：统一使用当季主强调色 `?attr/zzAccent` 填充背景，搭配加粗**纯白（`#FFFFFF`）字体**，杜绝低饱和淡色背景与浅强调色的模糊交叠；
     - **未选中按钮/页签**：统一采用实体卡片底色 `?attr/zzSurface` 搭配 `1.2dp ?attr/zzLineStrong` 清晰边框，文本使用加粗 `?attr/zzText`，确保在任何页面背景下清晰可辨；
     - **KPI 关键指标卡片**：
       - 卡片增加 `?attr/zzSurface` 底色与 `1.2dp ?attr/zzLineStrong` 边框，立体清晰；
       - 主指标（次数）：升级为 `20sp` 加粗 `?attr/zzText`；
       - 次指标（累计时长）：升级为 `12sp` 加粗 `?attr/zzAccentStrong` 并文案显式标注“累计时长”，彻底废弃 `9sp ?attr/zzSubtle`。
  3. **项目记忆固化**：
      - 将 UI 强对比度与图表鲜明辨识规范作为不可违背的业务红线写入 `AGENT_CORE.md`（红线 9）与 `SPEC.md`（4.5 节）。

### [DECISION-17] 统计真实数据抽取、智能游戏聚合与防倍增聚合设计
- **背景**：
  1. 用户发现统计页出现未来日期（如9月13日）的数据，经排查发现此前统计模块采用了基于 `Random(seed)` 的确定性伪随机数生成算法与预置假应用列表（`CANONICAL_APPS`），并未读取真机实际使用数据；
  2. 用户指出统计数据必须反映手机真实使用情况，且手机上的各类游戏应支持分类汇总（合并集合手机上的所有游戏），同时也要保留单个具体游戏的查看能力。
- **决策**：
  1. **零伪造数据原则 (Zero Mock Policy)**：
     - 全面废弃并移除 `seeded(...)` 伪随机逻辑；
     - 接入 Android 原生 `UsageStatsManager` 与 `PackageManager` 提取真实前台应用包名、使用时长与启动次数；
     - 未产生行为的历史天数与未来日期数据严格归零（0 分钟、0 次数），空列表展示「暂无记录」。
  2. **应用统计双重视图模式 (Dual View Modes)**：
     - 新增「分类汇总（游戏合并）」与「单应用真实排行」双视图切换，默认为分类汇总模式；
     - 在分类汇总模式下，所有已识别游戏自动聚合为单一项目「🎮 游戏」，列表与条形榜展示合并后的总时长与总打开次数；
     - 切换至单应用排行模式时，展开展示每个游戏的独立条目与真实排行。
  3. **智能游戏识别与自定义覆写契约**：
     - 系统自动判定：基于 Android 8.0+ `ApplicationInfo.CATEGORY_GAME`、`ApplicationInfo.FLAG_IS_GAME` 以及知名游戏厂商包名前缀（`com.tencent.tmgp`, `com.mihoyo`, `com.hypergryph`, `.game.` 等）；
     - 用户手动覆写：在筛选抽屉（`StatisticsFilterBottomSheet`）中，用户长按任意应用可自由将其标记为游戏或移出游戏，标记持久化于本地 `statistics_prefs`。
  4. **权限感知与引导**：
     - 当应用缺少 `PACKAGE_USAGE_STATS` 权限时，顶部弹出醒目的授权提示条与「去授权」按钮，点击直接跳转系统 `Settings.ACTION_USAGE_ACCESS_SETTINGS`；数据优雅降级为空，杜绝崩溃或假数据。
  5. **账本聚合单日单计防倍增设计**：
     - 针对出口统计（休息/临时解锁/特权），修复逐分桶累加账本导致 24 小时整点分桶将当日记录倍增 15~24 倍的致命缺陷；
     - 总量指标严格按自然日区间单次累加；整点分桶通过解析规则段起始时间（如 `09:00-18:00` 或 `seg_9_0_18_0`）精准映射到对应的整点。

### [DECISION-18] 闹钟提示隔离、多分类展开选择、四季主题响铃界面与两阶段衰减防卡死闭环
- **背景**：
  1. 提示按钮在题目无提示时也显示，且此前直接把答案或解析当提示输出，严重破坏了清晨做题挑战的自律唤醒意义；
  2. 闹钟挑战原本只能选一个通用的自定义题库配置，所有导入的分类（如四级、六级、考研）混淆在一起，无法按学科/分类指定出题与配比；
  3. 闹钟响铃全屏界面（`LockAlarmOverlay`）排版生硬且未接入四季主题，且未对打孔前置摄像头进行安全区适配；
  4. Markdown 导入抗干扰能力弱，且缺少直接将真题/已有题库清洗转换的标准 AI 提示词；
  5. 第一关结束后若未勾选题目会卡死在全黑屏无按钮状态；且第一关离床通过后闹钟依然高分贝狂响，未实现平滑降级。
- **决策**：
  1. **提示与答案/解析物理隔离**：`CustomQuestion` 新增可选 `hint` 字段；`LockAlarmOverlay` 默认彻底隐藏提示按钮，仅当题目显式存在 `hint.isNotBlank()` 时才显示，点击仅展示提示，绝不泄露答案或解析；
  2. **闹钟挑战题库多分类动态展开**：在新增/编辑与测试闹钟弹窗中，动态列出所有已导入题库（按 `title` 命名，如四级、六级、考研），支持独立勾选与单独指定出题数量（`stage1CustomBanks` / `stage2CustomBanks`）；
  3. **四季主题季相美化与安全区适配**：`LockAlarmOverlay` 完整接入四季主题季相 Token（深色半透明季相背景、强调色边框与按钮、高对比度大字体排版），并通过 WindowInsets 适配顶部状态栏与打孔前置摄像头安全区留白；
  4. **全格式智能容错与双 AI Prompt**：支持 `.json`、`.md`、纯文本自动识别解析（`parseContent`），增强对分割线与中英文冒号的容错；提供「全新知识点出题」与「已有题库/真题格式清洗转换」双提示词一键复制；
  5. **两阶段衰减与空关门禁防卡死**：
     - 两阶段模式必须满足每一关均至少选择一种挑战，否则拦截保存与测试（弹窗不关闭，Toast 拦截）；
     - 倒计时缓冲归属第一关下方；第一关离床完成时，立即调用 `AlarmVibrationService.attenuate(context)` 将响铃与震动降为最微弱温和模式；
     - 修复第一关结束/第二关无题目时的全黑屏无按钮卡死 Bug（自动触发复盘与关闭卡片）。

### [DECISION-19] 统计科学行为 6 分法、下钻详情页、真实前台排他时间线与规则变动 0 轴架构
- **背景**：
  1. 应用分类此前仅简单区分游戏与非游戏，无法反映现代自律/防沉迷对社交通讯、短视频、影音、AI学习等多维度的精细化管控需求；且缺乏点击分类进入二级下钻查看内部具体应用明细的能力；
  2. 原生 `UsageStats.totalTimeInForeground` 包含后台音乐播放、浮窗及多任务重叠，导致统计总时长经常超出 24 小时/天（严重虚高），与系统数字健康存在巨大出入；
  3. 筛选抽屉（BottomSheet）在应用过多时受到手机屏幕高度挤压截断，双栏滑动体验差；且标签块与文字由于固定高度和未对齐的色差导致对比度低甚至文字底部被截断；
  4. 第 4 分区「防破防」术语非正式且包含模糊的“部分恢复”，缺乏清晰的收紧（有利）与放宽（不利）的量化走向；
  5. 首页休息倒计时期间占用过大版面（24sp 巨型卡片），挤压了核心状态区。
- **决策**：
  1. **科学行为 6 分类与下钻二级详情页**：
     - 参考 iOS Screen Time 建立社交通讯、游戏、视频娱乐、音乐音频、AI与效率、系统与工具 6 大分类；
     - 实现 `AppCategoryDetailActivity`，提供分类 KPI、趋势及子应用使用排行与百分比进度条。
  2. **亮屏排他前台事件流时间线算法**：
     - 基于 `UsageEvents` 的 Resumed/Paused/Stopped/Screen-Off 严格计算屏幕开启时的排他前台时间片；
     - 单日累加物理截断在 $\le 24\text{h}$ 之内，彻底消除数据虚高膨胀，对齐系统原生数字健康口径。
  3. **全屏筛选界面 (StatsFilterActivity) 与 WCAG 对比度保障**：
     - 升级筛选抽屉为独立全屏 Activity，配备星期预设与双栏实时搜索过滤；
     - 接入 `ContrastHelper`（WCAG 2.1 相对亮度对比度 $\ge 4.5:1$），字体在纯黑与纯白间自适应选取，配合 `includeFontPadding="false"` 杜绝文字截断。
  4. **「规则变动」与 0 轴净变动图表**：
     - 正式更名为「规则变动」，剔除“部分恢复”，统一收敛为：有利改动（加严）、不利改动（放宽）、不利改动撤销（挽救）及规则净变动；
     - MPAndroidChart 绘制 0 轴水平基准线，上方绘制有利与挽救（绿色/蓝色），下方绘制不利放宽（橙红），直观呈现纪律走向。
  5. **首页冷却横幅瘦身**：
     - 引入紧凑 34dp、13sp 胶囊横幅（`layout_cooldown_pill`），休息中自动收拢，仅在活跃放行时展开主卡片。

### [DECISION-20] 休息出口统计频次精准打点、历史账本回溯与时间规则生效策略底部内嵌
- **背景**：
  1. 用户多次申请休息，但统计页休息次数始终为 1。根因是 `StatisticsRepository.kt` 在天级循环中直接使用 `if (restM > 0) sumRestCount += 1`，并将分桶硬编码为 `restCount = if (restM > 0) 1 else 0`，退化成了“是否有休息的天数”而非真实频次；
  2. 时间规则界面的「时间规则生效策略」卡片固定占用屏幕底部空间，由于该参数极少调整，常驻吸底导致勿扰时段与应用时间窗列表可用高度严重受限。
- **决策**：
  1. **出口事件账本打点与历史平滑兼容**：
     - 在 `CreditManager` 增加 `recordRestEvent`、`recordTempUnlockEvent`、`recordPrivilegeEvent`、`recordCooldownAttemptEvent` 计数打点（按天与按小时分别记录真实调用次数）；
     - 在 `grantFreeTime`、`performGrant` 与 `startTemporaryAccess` 等授权路径注入打点；
     - `StatisticsRepository.getExitStats` 在无显式计数的新旧交替期，通过 `count_usage_half_units_v2_${dateStr}_*`（每次休息/放行消耗的可用次数账本）回溯真实历史频次，并由 `maxOf(recRestCount, fallbackRestCount, if (restM > 0) 1 else 0)` 保底，彻底修复历史数据与即时数据；分桶图表按涵盖的小时/天真实累加。
  2. **时间规则生效策略移入滚动列表底部**：
     - `fragment_focus_rules.xml` 顶层保留固定 `TabLayout` 便于随时切页；
     - 将原本固定吸底的生效策略 CardView 移入主 `NestedScrollView` 内部最底部；
     - 勿扰时段与应用时间窗列表恢复全屏纵向浏览高度，生效策略卡片随列表自然滚动，滑到底部即可配置，兼顾契约测试与极简视觉体验。

### [DECISION-21] 根除 NestedScrollView XML nestedScrollingEnabled="false" 引起的构造期 NPE 崩溃
- **背景**：安装新版后应用频繁闪退，抓取崩溃日志显示 `Attempt to invoke virtual method 'void androidx.core.view.NestedScrollingChildHelper.setNestedScrollingEnabled(boolean)' on a null object reference`。
- **根因分析**：在 Android XML 中对 `<androidx.core.widget.NestedScrollView>` 直接声明 `android:nestedScrollingEnabled="false"`，Android 框架 `View.<init>` 在基类构造执行期间解析该 XML 属性并调用虚方法 `setNestedScrollingEnabled`。由于 `NestedScrollView` 内部的 `mChildHelper` 是在派生类构造函数体中才被实例化，此时 `mChildHelper` 尚为 `null`，引发致命 NPE 闪退。
- **决策与红线**：
  1. **严禁在 XML 中为 `NestedScrollView` 设置 `android:nestedScrollingEnabled`**：如需禁用嵌套滑动，必须在代码中视图初始化完成后调用 `isNestedScrollingEnabled = false`。
  2. **容器与子 Fragment 滚动层级归一化**：外层容器 `fragment_focus_rules.xml` 统一承载主滚动控制器 `scroll_focus_rules`；子 Fragment `fragment_time_config.xml` 与 `fragment_app_window.xml` 的根节点统一为 `FrameLayout (wrap_content)`，杜绝多层 NestedScrollView 嵌套与手势冲突。

### [DECISION-22] 使用统计分类单真理源收敛、用户自定义分类与 MaterialButton Insets 截断防御
- **背景**：
  1. 用户反馈网易云音乐、UU远程被误判为游戏，大量应用分类混乱；
  2. 主界面显示“系统与工具”仅 7 分钟，但点击下钻二级详情页却显示 25 款应用且统计达 1 小时，且微信、抖音、ChatGPT 等应用均出现在该分类中；
  3. 星期预设按钮（全部星期、周一周五）及统计切换按钮（行为大类汇总、单应用真实排行）文字底部被切断/遮挡。
- **根因分析**：
  1. `StatisticsRepository.kt` 原逻辑中 `isGame` 使用粗粒度 `"com.netease."` 通配符前缀匹配，且在所有分类前执行，致使网易全家桶全部误判为游戏；
  2. `getCategoryDetail()` 中存在严重漏洞 `AppCategory.TOOLS_SYSTEM -> true`，导致除游戏外所有应用（包括社交、视频、AI）全部穿透归入系统与工具；
  3. MaterialButton 拥有默认的上下 6dp 内部 Insets（`android:insetTop` / `android:insetBottom`），在 32~36dp 紧凑高度下，扣除 Insets 后绘制高度仅剩约 12dp，致使 11sp/12sp 汉字底部笔画严重截断。
- **决策与红线**：
  1. **抽离单真理源 `resolveCategory(context, pkg, info)`**：
     - 彻底剔除通配符 `"com.netease."`，建立网易非游戏应用白名单豁免，严格限定游戏包名规则（`moba.`、`party.`、`sky.` 等）；
     - 将向日葵、ToDesk、UU远程统一收敛至「系统与工具」；
     - 修复 `getCategoryDetail`，统一使用 `resolveCategory` 过滤，消除主界面与二级详情口径断裂。
  2. **引入持久化自定义分类**：
     - 新增 `KEY_CUSTOM_APP_CATEGORIES` 与 `setCustomCategory` API，长按二级详情页或筛选页任意条目可快速重设 6 大分类，自定义覆盖系统默认并享有最高优先级。
  3. **紧凑 MaterialButton 必须显式清空 Insets**：
     - 紧凑按钮（$\le 38\text{dp}$）必须声明 `android:insetTop="0dp"` 与 `android:insetBottom="0dp"`，配合 `android:includeFontPadding="false"` 与 `android:paddingVertical="0dp"`，彻底防御字体裁剪。

### [DECISION-23] 统计与核心数据防丢闭环：历史借用回溯与自动双快照外部备份
- **背景**：
  1. 用户反馈重装应用后，部分数据未丢失（系统 UsageStatsManager 保留应用时长），但临时解锁次数清零；
  2. 深入排查发现：`StatisticsRepository` 统计临时解锁时只读了显式打点的 `exit_temp_unlock_count_*`，未像休息次数那样回溯 `count_borrow_*` 账本；
  3. `SettingsBackupManager` 遗漏了 `count_*`（次数账本）、`rule_protection_ledger`（规则保护统计）、`alarm_stats_ledger`（唤醒统计）、`statistics_prefs`（自定义应用分类覆写与筛选）以及自定义题库 `custom_question_banks.json`。此外，由于国内 ROM 未启用 Google 云备份，应用卸载时私有存储会被完全抹除。
- **决策与红线**：
  1. **统计回溯双源对齐**：
     - `StatisticsRepository` 在计算临时解锁次数时，向前扫描回溯 `count_borrow_half_units_v2_${dateStr}_` 与 `count_borrow_${dateStr}_`，对齐休息次数回溯机制，救回历史临时解锁次数。
  2. **全量备份范围补全**：
     - `SettingsBackupManager` 在 `LEDGER` 分类中纳入 `count_*`、`rule_protection_ledger`、`alarm_stats_ledger`、`statistics_prefs`，在 `ALARM` 分类中纳入自定义题库 `custom_question_banks.json` 与 `bookmarked_clozes`。
  3. **公共文档目录双快照自动备份与重装静默恢复**：
     - 新增 `AutoBackupManager`，在关键变更（打点、时段、时间窗、题库）发生时 30 秒防抖自动落盘；
     - 采用双快照轮转机制（`zhizhi_auto_backup.json` + `_prev.json`），并在公共文档目录 `Documents/ZhiZhi/` 与内部私有目录双写；
     - 在 `MyApplication.onCreate()` 检测空白全新安装或重装，静默自动恢复外部快照并弹出轻量 Toast 提示，实现重装数据免删跨代留存。
### [DECISION-24] 全面更名「决定守护 (DeciKeeper)」与包名重构 (com.decikeeper.app)
- **背景**：开源首发更名「知止 (ZhiZhi)」后，品牌进一步收敛为更直白的自律语义；「知止」与既有名称意象仍易混淆，且需要与决策守护的产品定位（决定守护每一次自控）对齐。
- **决策**：彻底更名为「决定守护」(DeciKeeper)，Android 包名全面平移至 `com.decikeeper.app`，源码目录与全部 prefs 键名（`iron_will_*` → `decikeeper_*`）统一迁移。`SettingsBackupManager` 实现三协议解析（`decikeeper_settings_backup` / `zhizhi_settings_backup` / `ironwill_settings_backup`），导入旧备份时自动将 `iron_will_*` 键名映射为 `decikeeper_*`，实现旧版数据无缝迁移；`AutoBackupManager` 保留对旧公共目录 `Documents/ZhiZhi/` 与旧快照文件名 `zhizhi_auto_backup*.json` 的回退查找。
- **防坑警告**：包名变更后为全新独立应用，不可覆盖安装旧版，需通过导出/导入备份完成规则迁移；通知渠道、`Theme.*` 样式与 `Theme.IronWill` 兼容别名保留以支撑旧布局与旧渠道清理。
