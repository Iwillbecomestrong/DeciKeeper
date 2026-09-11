# 文档漂移审计（2026-09-11）

> **定位**：本文件是任务级审计记录，不是产品真相。它记录「现有文档与当前代码/测试之间已经确认的差异」，供决定重写范围使用。
> **状态**：审计已全部完成（12 份文档）。另有真机复现记录见 §5。
> **方法**：7 个只读审计单元并行执行，每单元对照 `AGENT_CORE.md` 的 Convergence Gap 协议，逐条把文档结论拿到代码与测试里找证据；所有结论要求 `文件:行号` 级证据，拿不准的一律归入「无法判定」。
> **边界**：全程只读，未修改任何被审计对象；未运行 gradlew / python（避免写盘）。

---

## 0. 结论摘要

| 文档 | 可验证结论 | 已漂移 | 无法判定 | 已过期引用 |
| --- | ---: | ---: | ---: | ---: |
| [`docs/ARCHITECTURE.md`](../ARCHITECTURE.md) | 33 | **15** | 1 | 1 |
| [`docs/ai/LOG_EVENT_MIGRATION.md`](../ai/LOG_EVENT_MIGRATION.md) | 21 | **12** | 1 | 2 |
| [`docs/specs/alarm.md`](../specs/alarm.md) | 52 | 8 | 3 | 0 |
| [`docs/specs/protection.md`](../specs/protection.md) | 97 | 8 | 4 | 1 |
| [`docs/specs/statistics.md`](../specs/statistics.md) | 29 | 8 | 1 | 0 |
| [`docs/PUBLISHING.md`](../PUBLISHING.md) | 15 | 7 | 1 | 0 |
| [`docs/specs/data.md`](../specs/data.md) | 47 | 5 | 4 | 0 |
| [`docs/DATA_SOURCES.md`](../DATA_SOURCES.md) | 17 | 4 | 0 | 2 |
| [`CHANGELOG.md`](../../CHANGELOG.md) | 9 | 4 | 1 | 0 |
| [`docs/THIRD_PARTY_NOTICES.md`](../THIRD_PARTY_NOTICES.md) | 12 | 2 | 0 | 0 |
| [`docs/specs/focus.md`](../specs/focus.md) | 8 核对点 | 2 | 0 | 0 |
| [`docs/user/getting-started.md`](../user/getting-started.md) | 22 | 3 | 1 | 1 |
| [`docs/user/guide.md`](../user/guide.md) | 40 | 9 | 1 | 1 |

已确认漂移合计 **87 条**。审计已全部完成。

### 三种漂移形态（决定修法不同）

**A. 文档写的是设计意图，代码停在中间态。**
代表是 `ARCHITECTURE.md`：它写死的三条硬性规则当前**全部不成立**，且自称的「重构进行中」没有任何进度载体。
→ 不能靠改措辞修，必须先决定「让代码追上文档」还是「让文档承认现状」。

**B. 功能已实现但入口被隐藏，文档当作可用功能描述。**
代表是 `statistics.md`（4 条）与 `protection.md`（2 条）。
→ 文档比产品超前。修法是给文档标注「已实现、当前隐藏」，而不是删除描述。

**C. 契约测试是绿的，但守护力为零。**
最危险的一类，详见 §3。
→ 这类会让「有测试守护」成为假信号，直接影响你判断哪份文档可信。

---

## 1. 按文档的漂移清单

### 1.1 `docs/ARCHITECTURE.md` —— 漂移 15 / 33，最严重

**硬性规则三条全部不成立：**

- [高] 「业务层不得 import UI 类」(:31) → `manager/` 有 **8 处真实 UI 依赖**（均有运行时调用，非死 import），`service/` 另有 16 处。证据：`manager/AlarmScheduler.kt:37` → `:1486` `Intent(context, AlarmActivity::class.java)`；`manager/AlarmWindowManager.kt:43-44` → `:141`/`:215`；`manager/CreditManager.kt:46-47` → `:149`/`:896`；`manager/PermissionManager.kt:37-38` → `:443`/`:505`；`manager/ReminderManager.kt:28` → `:86`/`:109`。**全测试树无任何分层/依赖方向契约测试。**
- [高] 「UI 不得直接读写业务 SharedPreferences」(:30) → `ui/` **5 个文件共 20 处**直接操作业务 prefs。证据：`ui/HomeFragment.kt:1119` 等 10 处读 `CreditManager.PREFS_NAME`；`ui/RulesFragment.kt:2302-2303`、`:1946-1948`；`ui/GlobalParamsFragment.kt:100` 与 `ui/GlobalParamsActivity.kt:110` 直读 `"decikeeper_rules_pending"`；`ui/AppSelectionActivity.kt:410/417`。（`CreditManager.PREFS_NAME = "decikeeper_ledger"`，`manager/CreditManager.kt:105`）
- [高] 「包结构 manager / ui / protection / data / **diag**」(:75) → `diag/` **不存在**，全仓 0 处 `app.diag` 引用；诊断代码实际在 `data/`（EventLog、NormalEventLog、ForegroundDiagnosticReport、CrashRecorder、ForegroundDetectionLog）与 `util/`（DiagnosticLogCompressor、DiagnosticTimestampFormatter）。实际目录为 data / manager / protection / receiver / service / test / ui / util / worker。

**其余漂移：**

- [中] 「系统/数据层 (data/ + Android SDK)」(:23-25) → `data/` 反向依赖上层：`data/SegmentBlacklist.kt:4-5`（import manager.CreditManager / TimeRuleManager）、`data/BlacklistStore.kt:4-7`（含 `service.MonitorService`）、`data/AdverseAnalysis.kt:23-24`、`data/SegmentAdverseAnalysis.kt:4`。
- [中] 「本时段有效名单 = SegmentBlacklist.effectiveList(当前段)」(:52) → MonitorService **从不调用** `effectiveList`，用的是 `isInEffectiveList`/`isSubjectToBlock`（`service/MonitorService.kt:1460/1616/2376/2658`）；`effectiveList` 全仓唯一调用点是 `data/SegmentAdverseAnalysis.kt:173`。且 `service/MonitorService.kt:2600` 自建分支绕过了 SegmentBlacklist 自称的「唯一语义来源」（`data/SegmentBlacklist.kt:111-123`）。
- [中] 「锁屏状态机…LockWindowManager.kt」(:42) → 该 6531 行业务状态机位于 **`ui/`**（`ui/LockWindowManager.kt:151`），并被 manager/、service/ 反向依赖。
- [中] 「Fragment 业务逻辑下沉到 Manager/UseCase」(:73) → 仍待办：RulesFragment 2047 行、AlarmFragment 1650 行、HomeFragment 1624 行；Manager 侧仅 `manager/AlarmSystemBackupUseCase.kt:34`。
- [中] 「核心 Manager 由 object 单例改为接口 + 构造注入」(:74) → 仍待办：`TimeRuleManager.kt:223`、`CreditManager.kt:101`、`AppWindowManager.kt:34`、`AlarmScheduler.kt:57`、`LockWindowManager.kt:151`、`SegmentBlacklist.kt:17` 全为 object；全仓 interface 仅 6 处，无 Manager 接口。
- [中] 「巨型文件按职责拆分」(:72) → 第 1 步已完成（TestScenarioManager 已移出 main）；后续部分完成：LockWindowManager 6531 行、MonitorService 3533 行、TimeRuleManager 3177 行、CreditManager 3286 行。
- [低] 「TimeRuleManager 将拆分」(:38) → 序列化已拆至 `data/TimeSegmentJson.kt:245`（`TimeRuleManager.kt:307/309` 仅委托）；边界校验与时段计算仍在内。
- [低] 「MonitorService 判定流水线将抽为纯对象」(:41) → **已完成**：`manager/LockDecision.kt:34`，由 `LockDecisionPolicyTest.kt` 守护。
- [低] 「CreditManager 将拆分」(:40) → 已部分拆出 `data/UsableCount.kt`、`UsableCountLedgerMigration.kt`、`TemporaryAccessQuotaPlan.kt`、`QuotaDetailModel.kt`、`BlacklistStore.kt`。
- [低] 「baseline = 08:00 快照」(:64) → 时点**可配置**：`manager/TimeRuleManager.kt:2687-2690` `getString("config_refresh_time", "08:00")`；同文件另有 06:00 口径（`:499`、`:2052`、`:2093`）。
- [低] 「变更性质**一律**与 baseline 比对」(:67) → 「一律」不成立：`TimeRuleManager.kt:355-369` 适应期整段绕过 baseline 直接 `saveBaselineSnapshot` + return。
- [低] 「双锁判定…特权绕过两把锁」(:54) → 绕过口实为三个，漏列**临时解锁**：`manager/LockDecision.kt:48-52` `isSafe || isPrivilege || isTemporaryAccess`。

**已过期引用**：`plans/`（:6、:70，该处以 plans/ 承载拆分步骤排期，但 `docs/plans/` 无此类文件）。

> 其中 :6 与 :70 两处引用**是本轮文档重构自己引入的**，已就地修正为「当前没有承载它的计划文件」。

---

### 1.2 `docs/ai/LOG_EVENT_MIGRATION.md` —— 漂移 12 / 21，行号锚点大面积失效

- [高] 「`LogExporter.kt:90-137 exportDropPatterns` **36 条**」→ 实际 **40 条** Regex，listOf 起于 :91、闭括号 :138。
- [高] 「Round 3 扩展到 **48 行**」→ 实测 **49** 个数据行（`:35-83`）。
- [高] 「MonitorService.kt:144/145/146/147」→ 这四行是 vivo/systemui 包名字面量；4 个压缩器定义在 :257-260，`observe()` 真实调用点在 :2328/:2394/:2785/:2888/:3108。
- [高] 「`[VIS_DECISION]` MonitorService.kt:2487」→ :2487 是 `try {`；真实调用点 :3118。
- [高] 「`FG_DECISION` MonitorService.kt:2248/2333/2335」→ 真实调用点 **:2811 / :2913**。
- [高] 「`[STEP1/2/3]` CreditManager.kt:3689/3765/3783/3797」→ 实际 :3471 / :3513 / :3531 / :3545；且 3765/3783/3797 **超出文件总行数 3744**。
- [高] 「`[TIME_CONSUME]` CreditManager.kt:3564-3635」→ 7 处实际在 :3335-3406。
- [高] 「penalty receipt 发射点 #1/#2/#3 → :3700/:3750/:3804」→ `emitPenaltyReceipt` 定义 :3580、**全仓唯一调用点 :3619**；3750/3804 超出行数。「三处 call point」不成立。
- [高] 「`[BORROW_UI_DEBUG]` LockWindowManager.kt:4774 等」→ 实际 :3181 / :1528 / :1661,1666,1935,1940,1963,2023；3417/3421/3471 现为无关代码。
- [中] 「`[AUTO_RELEASE]` LockWindowManager.kt:3937 / MonitorService.kt:2058」→ 现存唯一输出点 `MonitorService.kt:2591`。
- [中] 「心跳 AlarmHeartbeatReceiver:46 / AlarmGuardianService:280 / AlarmHeartbeatWorker:100」→ 实际 **52 / 305 / 106**。
- [中] 「CoreService 450/538、1300/1303、1327/1331」与「ForegroundDetectionLog.kt:65 lastStableKey」→ 实际行内容全部不符。
- **已过期引用**：`docs/issues/ForegroundDiagnosticReport__FINAL.md`（目录不存在）；「`docs/archive/superpowers/` 中的 R3 handoff 文件」（该目录下无任何 R3 文件）。

> **本文件即使全错也不会被发现** —— 见 §3。

---

### 1.3 `docs/specs/alarm.md` —— 漂移 8 / 52

- [高] 「顶底适配 WindowInsets，为状态栏与打孔摄像头预留安全边距」(:173) → **已独立复核**：`LockAlarmOverlay.kt` 对 `WindowInsets`/`statusBars`/`displayCutout` 命中 **0**，仅 :3233 `LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES`；`window_alarm_overlay.xml` 无 `fitsSystemWindows`，只有硬编码 `padding="24dp"`。对照：MainActivity / AppSelectionActivity / GlobalParams* / SchedulePreviewActivity 各有 2-3 处真实 insets 处理。
  > 附注：AGENT_CORE §4.3 原文限定「全屏 **Activity** 和主页面根容器」，闹钟挑战是 `TYPE_APPLICATION_OVERLAY` 悬浮窗，**是否纳入 §4.3 需产品判断**；但文档把它写成已实现事实，这条漂移是确定的。
- [高] 「默认彻底隐藏提示按钮；仅当题目显式包含提示才显示；绝不泄露答案」(:149-150) → 仅**自定义题库**满足（`LockAlarmOverlay.kt:1572-1575`）；诗词/单词/选词填空三处布局按钮**无默认 gone**（`window_alarm_overlay.xml:194-203,313-322,423-434`）；诗词提示为有意设计的渐进提示「答案以「XX」开头」（`:1013-1024`，`:1456` `poem.down.take(2)`）。
  > **修正**：这不是代码违反规格，而是**文档措辞过宽**——§5.3 标题本已限定「自定义挑战题库…提示隔离」。严重度由「高」降为「中」，修法是限定文档适用范围。
- [中] 「用户显式保存『第二阶段无任务』时不得被默认值偷偷回填」(:265) → **已独立复核**：`AlarmFragment.kt:1550-1551` 对空值静默替换为默认集合后落盘 —— `effectiveS1 = if (s1Types.isEmpty()) setOf("math")`、`effectiveS2 = if (TWO_STAGE && s2Types.isEmpty()) setOf("math","poem","word","cloze")`，随后 :1553/:1554 写入默认设置。
- [中] 「空关门禁：每关至少选一种，否则拦截保存与测试」(:116) → 新增/编辑保存（`:1171-1179`）与「开始测试」（`:1613-1628`）确有拦截；但同一测试弹窗的「保存为默认设置」（`:1494-1576`）**无任何空值校验即持久化**。
- [中] 「深色半透明季相背景」(:172) → 代码注释与实现**相反**：`LockAlarmOverlay.kt:776-778` 明确「必须是不透明实体色，绝不允许透明」；`window_alarm_overlay.xml:7` `?attr/zzPageBg`；`themes.xml:16` `zzPageBg=#F7F8F2`（浅色）。
- [中] 「任意 QR 即可通过」「Recovery 有最大边界」(:315,:323) → 两条**只有实现、无自动化测试守护**（`AlarmActivity.kt:174-202`；`AlarmScheduler.kt:94` `MAX_RECOVERY_COUNT=40`；测试侧 0 命中）。
- [低] 「内置双 AI 提示词」(:179) → 实为三个且命名不同（原创出题 / 现有题库转换 / 格式修复），`CustomQuestionBankImportActivity.kt:61-83`。
- [低] 「未勾选具体题库时绝不抽取」(:93) → 运行期 types 含 "custom" 而题库为空时，队列被填 null 并**回退到全库随机抽题**（`LockAlarmOverlay.kt:430-432`、`:1556-1558`）。

**说明**：`stageCooldownSeconds`(:40)、第二关同题型含扫码、空任务纯延迟收尾、30s/+1 级阶梯（`AlarmAlertPolicy.kt:17-26`，有契约测试）、Direct Boot 双写 —— 这些**均成立**。

---

### 1.4 `docs/specs/protection.md` —— 漂移 8 / 97

**先说好消息**：全文**未出现**「前台检测/时间拦截/防卸载单点依赖无障碍」这类被 AGENT_CORE §4.1 禁止的表述；防卸载执行路径确为无障碍事件扫描 + 系统页面识别 + `GLOBAL_ACTION` 退出 + 覆盖层身份，**无 Device Admin 依赖**；无「绝对无法绕过」承诺。守护测试：`RulesSettingsUiContractTest`、`PrivacyProtectionRetirementContractTest`、`LockGenerationIsolationTest`、`AntiUninstallForceStopDialogTest`。

- [高] 「开启：立即；关闭：立即」(:193-194)、「开启和关闭均立即生效」(:340) → **已独立复核**：`AlarmPowerOffProtectionManager.isEnabled()` 在 `app/src/main` 中**只有 UI 在读**（`RulesFragment.kt:789/901/1142/2058` 渲染开关状态）；真正拦截关机菜单的是 `CoreService.kt:880` 的 `if (LockWindowManager.isAlarmOverlayShowing)` —— **只判断覆盖层在不在，完全不看开关值**。关掉开关，拦截行为不变。
- [高] 「设置页必须保留黑白名单、全局参数等入口」(:293) → **已独立复核**：`fragment_rules.xml` 对「全局黑名单/黑白名单/全局参数」命中 **0**。实际位置：`fragment_time_config.xml:36` `btn_global_blacklist`；全局参数是时间规则页第三 tab（`FocusRulesFragment.TAB_GLOBAL_PARAMS = 2`）。契约测试反向断言：`RulesSettingsUiContractTest.kt:85-90`、`:93-102`。
- [中] 「关闭：提交严格下一业务日的 pending」(:133) → 适应期内**直接立即 DISABLED**（`AccessibilityControlManager.kt:78-82`），未走 pending。
- [中] 「系统『锁屏显示』应用权限」(:208) → 实为**本地人工勾选位**（`AlarmPowerOffProtectionManager.kt:113-116`；契约测试命名 `lockScreenDisplayUsesIndependentManualConfirmation`）。
- [中] 「防关权限保护 = 关键无障碍能力」(:118) → `PermissionProtection.kt:63-79` 实际覆盖悬浮窗 / 使用情况 / 自启动 / 电池优化等多类，不限无障碍；与本文件 §11.3 口径不一致。
- [中] 「[非目标] 通过设备管理权永久阻止用户卸载」(:288) → **已独立复核**：拦截链确无设备管理权（`isDeviceAdminActive` 定义在 `AntiUninstallManager.kt:135`、**调用点 0**），但 `DeviceAdmin` receiver **仍注册在 Manifest**（`AndroidManifest.xml:285-295`，含 `BIND_DEVICE_ADMIN` 与 `@xml/device_admin`），启用时 Toast 宣称「防卸载保护已激活」。文档未反映该残留组件。
- [低] 「隐藏**知止**在多任务界面」(:336) → 现文案为「隐藏决定守护在多任务界面」（`fragment_rules.xml:138`）。
- [低] :35 有乱码残字 `bu'zhi'd`。

---

### 1.5 `docs/specs/statistics.md` —— 漂移 8 / 29

- [中] 「四个正交一级分区（休息/起床/应用/规则变动）」(:23-27) → **已独立复核**：`fragment_statistics.xml:192`（tab_apps）与 `:208`（tab_rules）**永久 `visibility="gone"`**，仅休息/起床公开。
- [中] 「采用独立全屏 StatsFilterActivity」(:86) → 类与功能齐备，但入口 `btn_open_filter` 为 `visibility="gone"`（`fragment_statistics.xml:86,95`）。
- [中] 「智能文字选色…黑白自动择优」(:93) → `ContrastHelper.getOptimalTextColor` **生产零调用**（唯一调用者是测试），选中态硬编码白字（`StatsFilterActivity.kt:106-112`）。
- [中] 「废弃直接对 totalTimeInForeground 累加」(:16) → 该累加**仍作为无事件流回退保留**（`StatisticsRepository.kt:744-745,762-766`）。
- [中] 「任意自然日绝不允许超过 24 小时」(:17) → 实际截断口径是**单桶时长**，粒度可含周/月（`StatisticsRepository.kt:884-886`、`StatsDateRangeResolver.kt:133-143`）；唯一相关测试**未调用生产代码**。
- [低] 「彻底移除假应用列表」(:11) → `CANONICAL_APPS` 仍在源码（@Deprecated、无引用，`StatisticsRepository.kt:56-70`）。
- [低] 「所有文字按钮 includeFontPadding=false」(:94) → 未全覆盖：fragment 4/59、filter 24/29。
- [低] 「收敛为 3 大原子指标」(:65) → 本节列 4 项，代码模型亦 4 项。

---

### 1.6 `docs/PUBLISHING.md` —— 漂移 7 / 15

- [高] 「Gitee 仓库 ❌ 尚未创建」(:16) → **已独立复核**：`gitee` remote 早已配置，`git ls-remote gitee` 返回 main/dev 均可达。连带失效：§2 第 0 步（:26-38）、:232 清单首项。
- [高] 「本地 main 已与远端同步…已快进至 79ee151」(:17) → 79ee151 落后 main **302 个提交**，main 现为 `975c4ba`。
- [中] 「9.3 MB，997 条目」(:18) → 体积成立；条目实测 **1044**。
- [中] 「3 个不同作者邮箱」(:181-187) → 实际 4 个身份，**漏记 github-actions[bot]**，两个人工邮箱计数翻倍（180 / 131 / 16 / 11）。
- [中] 方案 A 指令「git config user.email 用 GitHub noreply」(:196-197) → 仓库级与全局均仍为 gitee noreply 地址，即文档自述「GitHub 无法关联」的那个。
- [低] 「git remote -v 应看到 origin/github/gitee 三组」(:70) → 实际只有 `gitee`、`github` 两个，无 `origin`。
- [低] 「GitHub Actions 已配置 android-build.yml」(:20) → 实有 **4 个** workflow。
- :6「最后核验：2026-09-04」早于后续更名提交，文档内容已晚于核验日期。

---

### 1.7 `docs/specs/data.md` —— 漂移 5 / 47

- [中] 「根据规则边界推导业务日」(:68) → 业务日锚点的 source of truth 是**用户偏好 refresh time**，规则段边界仅 fallback（`CreditManager.kt:2338-2357`、`TimeRuleManager.kt:2020-2045`，代码自称 "v3 反转优先级"）。
- [中] 「兼容旧类型标识：ironwill_settings_backup」(:128) → 实际受理**三种**，还有 `zhizhi_settings_backup`（`SettingsBackupManager.kt:39-42`、`:663`）。
- [中] §6 备份协议全文(:122-136) → 未记录 **AutoBackupManager 自动双写通道**（Documents/DeciKeeper + files/auto_backup + ZhiZhi 旧目录回退，`AutoBackupManager.kt:27-35,78-95,154-191`）。
- [低] 「DPS 保存关键闹钟状态」(:86,:99) → DPS 还承载规则、账本、黑名单、无障碍启动缓存。
- [低] 数据域表与备份协议**未声明**「隐私同意不参与备份/迁移」→ 代码与契约测试已锁死（`backup_rules.xml:18`、`data_extraction_rules.xml:18,22`、`BackupRulesExcludesConsentOnlyTest.kt:41-47`）。

---

### 1.8 `docs/DATA_SOURCES.md` —— 漂移 4 / 17

- [高] 「考研词汇.txt 48KB … 高风险」(:18) → 实际 **43,006 B（≈42KB）**；该行脱离 5 列表头、字段语义错位，且与同文档 :14 对同一文件判定「**极低**」**直接矛盾**。
- [中] 「仓库数据层可发布」(:4) → 见 §2 的 `duilian.json` 非法 JSON。
- [中] 「第三方库均为 Apache-2.0/MIT」(:25) → 实际含 **JUnit 4（EPL-1.0）** 与 **org.json（JSON License）**，与本仓库自己的 `THIRD_PARTY_NOTICES.md:19-21` 冲突。
- [低] 「原始数据备份到 scripts/.bak/」(:33) → `.gitignore` 有该规则，但目录**不存在**。
- **已过期引用**：`scripts/_translate_options_youdao.py`(:23)、`_translate_passages.py`(:24) 均不存在。

---

### 1.9 `docs/THIRD_PARTY_NOTICES.md` —— 漂移 2 / 12

全量 `implementation` 清单反查结论：**无未申报的第三方库**。

- [中] 「GridLayout / WorkManager / ViewPager2 见 libs.versions.toml」(:9) → 三者**硬编码在模块脚本**，版本目录只登记 10 个库（`app/build.gradle.kts:138,141,146`）。
- [中] 测试依赖表(:15-21) → 漏记 `androidx.test.ext:junit 1.1.5` 与 `espresso-core 3.5.1`。

---

### 1.10 `CHANGELOG.md` —— 漂移 4 / 9

- [高] **代码反向引用 CHANGELOG 条目，但该条目不存在** → `HomeFragment.kt:153` 写「CHANGELOG v0.2.0 已声明『特权时长选择器自动截断 + 显示上限』」，`:699` 与 `PrivilegeAutoClampTest.kt:14`、`TestScenarioManager.kt:9398` 同样引用；而 **CHANGELOG 对「自动截断/特权时长」命中数为 0**（[0.2.0] 段只有 3 条 Added + 3 条 Security）。
- [高] 明显落后：自 2026-08-15 起有 **329 个提交**，Unreleased 仅列 9 条；未收录 Direct Boot 恢复（b2dad00）、静默恢复不启动主页（0c04619）等已合入 dev 的变更。
- [中] [Unreleased] 内**并存两条互斥更名**（「决定守护 DeciKeeper」:10-13 与「知止 ZhiZhi」:14-17），后者已被取代却仍留在 Unreleased。
- [中] 版本号自洽性：`versionCode = 1` / `versionName = "0.2.0"`，而 [0.2.0] 已标为发布；`git tag` 为空，0.2.0 与任何提交/产物无绑定。

---

### 1.11 `docs/specs/focus.md` —— 漂移 2 / 8 核对点（**目前最健康的一份规格**）

审计覆盖 8 个核对点：成立 **6** / 已漂移 **2** / 无法判定 0。

**已漂移：**

- [中高] 「当前产品口径是 **借 1，下一时段扣 2**」「冷却 `×2` 与借用『借 1、后续扣 2』是两个不同概念…UI 和文档不得混为一谈」(:244、:248) → 实际偿还额是**分区加权后再乘惩罚**：`CreditManager.kt:2006-2008` `zoneWeighted = coolingOverlapSec*2 + dndOverlapSec*1 + gapOverlapSec*0` / `return zoneWeighted * 2`。即**冷却重叠部分实际按 ×4 偿还**（×2 惩罚 × ×2 冷却），冷却与借用是**相乘**关系而非文档所述的并列概念。活调用点：`LockWindowManager.kt:6183`（5 参重载，显式传入 cooling/dnd overlap）；只有 2 参默认重载（`CreditManager.kt:2104`，经 `:2025` 全按勿扰段计）才等价于 ×2。
- [低] 「切换策略时 `EffectiveActivationPolicyManager` 要求 `TimeRuleManager` 与 `AppWindowManager` 重新协调 active / pending 状态」(:304) → 适应期内该协调被**直接跳过**：`TimeRuleManager.kt:1727` `if (isInAdaptationPeriod(context)) return`、`AppWindowManager.kt:601-602`；而 `EffectiveActivationPolicyManager.kt:54-56` 是无条件调用。§8.4 未记录该例外。

**成立的 6 点（关键锚点）：**

- 休息计费为**整单倍率**而非分段阶梯：`LockWindowManager.kt:3620-3623`、`:6692-6696`、`:6770-6773`；旧比例 helper `Util/UsableCount.kt:24-32` 在生产中**零调用**（与 §3.3 描述一致）。
- 生效策略作用范围确为两把锁：`EffectiveActivationPolicyManager.kt:54-56,69,71,76`；非 UI 消费点仅 `TimeRuleManager.kt:2914` 与 `AppWindowManager.kt:852`；黑白名单走独立路径（`BlacklistStore.kt:292-293` 只看适应期，不看策略）。
- 休息**不解除**应用时间窗：`LockWindowManager.kt:5492-5493`、`:5586-5605`。
- 临时解锁免费条件（空档期 / 已休息）：`LockWindowManager.kt:4334-4341`，预览路径同源 `:4040-4065`。
- 「下一刷新时刻」= 用户配置 `config_refresh_time`，默认 `"08:00"`，**非固定 00:00**：`TimeRuleManager.kt:2695-2697`、`:2028-2053`。
- 加严立即 / 放宽延期主原则成立：`TimeRuleManager.kt:389-411`；适应期例外**已被文档记录**（focus.md:312-314 ↔ `AdverseAnalysis.kt:45-49`、`TimeRuleManager.kt:2074-2079`）；Fresh Segment 例外由 `SegmentAdverseAnalysis` 与其测试定义（文档 §9.2 已声明）。
- 同业务日结余与跨午夜归属起始日：`CreditManager.kt:1841-1848`、`:2690-2692`、`:2710-2712`、`:2535-2558`。

**契约测试（强证据）**：`TemporaryAccessChargeSnapshotTest`（:30/:49/:68/:158-179/:213-260）、`BorrowLimitCalculationTest`（:39-41、:63-65 显式 `perRawWeight = 2`）、`DualLockSystemContractTest`（:95-118、:122-126、:214-215）、`SegmentCarryEligibilityTest`、`A01BusinessDayFilterValidationTest`、`EffectiveActivationPolicyCopyTest`。

> **⚠️ 证据根修正**：本单元最初读取的是**主工作区 `IronWill-OpenSource` 的工作态**（含未提交改动），行号与本分支不一致（同号内容不同）。已按本文档所在分支重新锚定并逐条复核：**两条漂移的实质结论均成立**（借用公式确实存在于 dev 基线，见 `CreditManager.kt:2006-2008`），正文行号已全部替换为本分支坐标。这是 7 个单元中**唯一**出现证据根偏差的一个，其余 6 个已抽样验证锚点正确。

### 1.12 `docs/user/` —— 漂移 12 / 62

> **⚠️ 证据根偏差**：本单元以**主工作区**（dev + 64 个未提交改动）为"当前代码"基线，与本文档所在分支不同的地方已逐条标注。涉及的 3 处差异见 §4.1。

**`getting-started.md`（22 条：成立 17 / 漂移 3 / 无法判定 1 / 过期引用 1）**

- [高] 「首页的 **5 项**基础权限」(:63、:79) → **已实测**：`HomeFragment.kt` 权限引导实际只有 **4 项**——悬浮窗权限(:1685)、通知权限(:1697，仅 Android 13+)、后台耗电管理(:1702)、自启动权限(:1707)；Android<13 只有 3 项。「使用情况访问」「后台弹出界面权限」已从引导移除并被 `RulesSettingsUiContractTest.kt:93-98` 锁定。**两树一致。**
  > 更严重的是：`HelpManualSingleSourceContractTest.kt:63` 原本写 `assertTrue(contains("首页的 5 项基础权限"))`，**把错数字钉死了**，谁改对谁测试红。**已修复**（见 §4）。
- [低] 「**计算** / 诗词 / 单词 / 选词填空」(:143) → 界面文案实为「**数学**」「**选词**」：`AlarmFragment.kt:641,670`、`dialog_alarm_edit.xml:174`。
- [低] 「该时段的限制应用」(:105) → 实际字段为「**本时段黑/白名单**」并可选择跟随全局：`dialog_edit_segment.xml:185,195`（`dialog_add_segment.xml` 已无代码引用）。
- 过期引用：:34 的「挑战闹钟 / 起床系统」命名对照（见 §4.1 两树差异）。
- 无法判定：:66-69「后台运行基本稳定」「ROM 没有频繁杀掉」属用户自测项。

**`guide.md`（40 条：成立 29 / 漂移 9 / 无法判定 1 / 过期引用 1）**

- [高] 「发起申请时已处于冷却：本次应计费额度和次数**统一按 2 倍**扣除」「**不是**『冷却还剩几秒就只给那几秒加倍』的碎片化算法」(:195、:197，同源 :107-109、:138 表格、:386 问答) → 实际代码**正是**按秒加权：只有落在冷却期的重叠秒数 ×2，其余勿扰 ×1、空档/休息 ×0。证据：`LockWindowManager.kt:4623-4645`、`:3651`、`:4170-4171`（弹窗原文「触碰到冷却期的重叠部分会加权扣费，不重叠的部分正常扣费」）、`CreditManager.kt:2233`；强契约测试 `WeightedRestCountTest.kt:96-105`（50/50 勿扰+冷却 → 2 次；50/50 空档+冷却 → 1 次，注明"Issue_Tracker 06-24 用户报告的回归场景"）。
  > 与 §1.11 的 `focus.md` 漂移**同源**：两处文档都说"统一翻倍/两个独立概念"，代码实际都是加权相乘。且 `LockWindowManager.kt:4103,4127` 的锁屏旧文案「整单直接翻倍（×2）」本身也与计算不一致——**文档疑与这段旧 UI 文案同源**。
- [中] 「第二关的题型…也可以**留空**」(:296) → 保存时被拦截：`AlarmFragment.kt:1170-1177`（「两阶段模式下，第二关必须至少选择一种挑战！」）。**两树一致**，且与 `AGENT_CORE.md` §3.4「第二阶段任务允许为空」**直接冲突**（业务红线级 Convergence Gap）。
- [中] 「只是扫码、验证码、回一句消息：用**临时解锁**」(:372，§3.2 同) → 主工作区纯勿扰锁屏已**移除该入口**：`LockScreenPresentationPolicy.kt:59-63`（`return false`）、`LockWindowManager.kt:3053-3058`、契约测试 `DualLockSystemContractTest.kt:164-187`。**两树不一致**（本分支仍为 `mode == DND`）。
- [低] 「**统一延后**（部分文案中也称"全部次日"）」(:236、:392) → 时间规则页单选文案实为「**全部次日**」：`fragment_focus_rules.xml:135`。
- [低] 「该时段的限制应用」(:44) → 同上，实为「本时段黑/白名单」。
- 过期引用：:254 的「挑战闹钟 / 起床系统」命名对照。
- 无法判定：:360「某个旧版本字段无法恢复，以当前导入界面的实际结果为准」→ 未找到该面向用户的失败提示实现。

---

## 2. 审计中发现的代码 / 资产实体缺陷（非文档问题）

这些是在翻代码取证时顺带捞出的，**独立于文档重写**，建议单独排期。

> 状态：以下 5 项中，**乱码**与 **DeviceAdmin 残留**已于本轮修复（见 §4）；其余 3 项待处理。

- **`app/src/main/assets/duilian.json` 不是合法 JSON，且被打进 APK。** 第 201 行对象末尾缺逗号，PowerShell 与 Node 两边均解析失败（line 203）。全仓库唯一引用是构建中间产物 `merger.xml`，**没有任何代码读它**（App 只加载 `duilian_single.json`，`AlarmChallengeManager.kt:476`）。但它在 assets 下，**每个 APK 都背着 65,853 字节坏数据**。`duilian_single.json` 本身合法——说明当年清洗过一份，坏的那份没删。
- **`AlarmPowerOffProtectionManager` 的开关没有消费点。** 见 §1.4 第一条。这既是文档漂移，也意味着该功能**实际不可关闭**（拦截只随闹钟覆盖层状态走）。
- **`DeviceAdmin` receiver 残留。** Manifest 仍注册设备管理组件（`AndroidManifest.xml:285-295`），但 `isDeviceAdminActive` 调用点为 0。声明了不用的高敏权限组件。
- **两处源码中文乱码，共 105 行。** `service/CoreService.kt` 56 行、`protection/AntiUninstallProtection.kt` 49 行（GBK 被当 UTF-8 读的典型症状）。11 处落在非注释行，**全部是 `Log.*` 诊断日志**，未污染用户可见文案。但 `AntiUninstallProtection.kt` 在 AGENT_CORE 划定的防卸载高风险区，日志不可读会拖慢 `docs/history/issue_tracker.md` 那套真机排查；这些日志里还残留 `IronWill` 旧名。
- **覆盖层 inline→window 切换后窗口丢失，导致扫码成功后白屏且无法退出。** 见 §5.1。触发条件明确：锁屏 + 长按电源键调出关机菜单。`mAlarmView` 成为无父容器的游离视图后，`showCompletedChallengeState` 无渲染目标。
- **`AlarmFragment.kt:1550-1551` 静默回填默认挑战集合。** 用户显式保存「第二阶段无任务」会被替换为 `setOf("math","poem","word","cloze")` 并落盘，与 alarm.md 的空关门禁契约相反。

---

## 3. 守护力为零的「假契约测试」

这一类最值得单独警惕：**测试是绿的，但它不守护它声称守护的东西。**

- **`LogEventMigrationInventoryTest`**：只断言文档中**含有**那些字符串，**从不与源码实际行号比对**（`:209-216`、`:224-229`）。这就是 `LOG_EVENT_MIGRATION.md` 12 处行号全错、多处行号已超出文件总行数，测试却依然通过的原因。
- **`AppUsageCalculationTest.kt:44-50`**：24 小时截断断言**自行 coerce 本地字面量**，未调用生产代码，所以 `StatisticsRepository.kt:884-886` 的真实口径漂移无人守护。
- **`BusinessDayChangeTest.kt:200-239`**：是测试内自写的**镜像函数**，而非生产绑定契约。
- **分层/依赖方向**：`ARCHITECTURE.md` 的三条硬性规则，**全测试树 0 条契约测试**守护。

---

## 4. 审计后已处理 / 待裁决

### 已处理（本轮）

| 项 | 处理 | 验证 |
| --- | --- | --- |
| 两处源码中文乱码 105 行（`CoreService.kt` / `AntiUninstallProtection.kt`） | GBK→UTF-8 反向解码还原 **119 行**；其中 47 行各有 1 字符永久丢失，按上下文补全 | 全量单测 1154 用例 0 失败；构建通过 |
| `DeviceAdmin` 残留组件 | 删除 `receiver/DeviceAdmin.kt`、`res/xml/device_admin.xml`，移除 Manifest receiver 块与 `AntiUninstallManager.isDeviceAdminActive()` | 合并后清单命中数 0，全项目零残留引用 |
| 「首页的 5 项基础权限」错数字 | 文档改为实际四项（悬浮窗/通知/后台耗电管理/自启动）；**测试断言一并修正**（原断言把错数字钉死） | `HelpManualSingleSourceContractTest` 4/4 PASS |
| `ARCHITECTURE.md` 中本分支自引入的 `plans/` 悬空引用 | 改为「当前没有承载它的计划文件」 | — |

> **更正**：乱码**并非只影响日志**。`AntiUninstallProtection.kt:1508` 的 `.setTitle("导出日志 (防卸�?")` 是**用户可见弹窗标题**，字符已被损坏。

### 已裁决并落地（2026-09-11）

**① 借用/冷却口径 —— 已裁决：改为平坦倍率，删除按秒加权**

产品决定：

| 场景 | 倍率 |
| --- | --- |
| NORMAL 申请（休息 / 临时解锁）触及冷却期 | 时长与次数**整单 ×2** |
| FREE_TIME / REST | 仍免费，不受冷却影响 |
| 借用触及冷却期 | **整单 ×4**（借用惩罚 ×2 × 冷却 ×2） |
| 借用未触及冷却 | 整单 ×2（借一还二） |

已落地：

- `LockWindowManager.calculateWeightedRestCountImpl`：触及冷却 → 2；否则保留 `round(勿扰/总)`
- `LockWindowManager.calculateWeightedQuotaSeconds`：触及冷却 → 整单 ×2
- `CreditManager.calculateWeightedBorrowSeconds`：删除 `zoneWeighted`，改为触及冷却 → 整单 ×4、否则 ×2；空档秒不计费（沿用旧 `gap×0`）
- 弹窗文案：「加权扣费」全部改为「整单翻倍」
- `WeightedRestCountTest` 更新 7 条期望（混合场景由 1 次变 2 次）
- **新增 `BorrowPenaltyFormulaTest`（5 例）**——该函数此前**无任何测试覆盖**

> **修正本报告先前表述**：初版称"代码是加权、文档说整单翻倍，两边相反"，**不准确**。
> 主线路径本来就是整单翻倍（`LockWindowManager.kt:3637` `overlapSec = if (inAnyCooling) requestedSec else 0`，全有或全无）；
> 真正的按秒加权只在 `calculateWeightedRestCountImpl`（次数）、`calculateWeightedQuotaSeconds`（跨段末时长）与 `calculateWeightedBorrowSeconds`（借用）三处。

**② 第二阶段是否允许为空 —— 已裁决：不允许为空**

代码（`AlarmFragment.kt` 保存与开始测试均拦截，报「两阶段模式下，第二关必须至少选择一种挑战！」）是**对的**；
`AGENT_CORE.md` §3.4 原文「第二阶段任务允许为空」**已过期，已改为**：

> - 双阶段模式下**每一关都必须至少选择一种挑战**（含扫码），空值在保存与开始测试时被拦截；
> - 第二阶段**允许与第一阶段重复题型**（包括扫码）。

**③ 哪棵树是"当前代码"**：仍未裁决，见下方 §4.1。

### 仍待处理

`AlarmFragment.kt` 的「保存为默认设置」路径（`:1550-1551`）会**静默把空的第二阶段回填成 4 种题型**，
与上面「不允许为空」的口径以及 `alarm.md` §265「不得被默认值逻辑偷偷重新填回」相冲突，尚未处理。

### 4.1 两棵树并存造成的基线问题

主工作区 `IronWill-OpenSource` 有 **64 个未提交改动**，其中包含**产品行为变更**，导致"当前代码"存在两个版本：

| 行为 | 提交的 dev（本分支） | 主工作区未提交工作 |
| --- | --- | --- |
| 纯勿扰锁屏的「临时解锁」入口 | `mode == DND`（显示） | `return false`（移除） |
| 底部导航闹钟页名称 | 闹钟 | 挑战闹钟 |
| `LockScreenPresentationPolicy` | 旧行为 | 已向 `SPEC.md` §2.3 收敛 |

**关键**：`SPEC.md` §2.3 明确写「纯勿扰锁屏仅提供『申请休息』与『每周特权』，彻底移除『临时解锁』」——**即未提交的工作是在向 SPEC 收敛，而提交的 dev 仍是旧行为**。

这解释了本次审计中大量"文档对不上代码"的现象：**文档写的是目标态，dev 是收敛前状态，未提交工作是收敛过程**。在决定"哪份文档该重写"之前，需要先确定"以哪棵树为准"，否则所有行号与结论都会随基线漂移（本次 7 个单元中已有 1 个因此返工）。

---

## 5. 真机复现记录（2026-09-11 · vivo V2425A / PD2425）

### 5.1 覆盖层 inline→window 切换后丢失窗口 → 扫码成功后白屏且无法退出【真机复现】

> **本节已于 2026-09-11 第二次复现后重写。**初版把根因写成"状态位竞态矛盾"，**该判断是错的**——`active_first_stage_passed=false` 在单次模式下是正常值（`markFirstStagePassed` 仅在 `isTwoStageUnlock` 时调用，见 `LockAlarmOverlay.kt:2700`）。保留此说明以免后续排查被误导。

**现象**：锁屏下进入扫码挑战页 → **长按电源键**调出关机菜单 → 回到挑战页 → 扫码（可成功）→ **白屏**，不显示「关闭闹钟」与延迟倒计时；闹钟震动持续，用户无法退出。**开启无障碍后必现**。

**前置条件：无障碍必须开启。** 这不是"操作步骤漏了"，而是**功能依赖**——关机菜单拦截逻辑位于 `CoreService.onAccessibilityEventInternal`，无障碍关闭时该服务收不到事件，拦截路径根本不会执行。

对照运行（20:42–20:44，无障碍处于关闭态）的日志证据：

```text
20:42:31.484  A11yGuardService: [NOTIFY-DIAG] Transition detected (healthy→inactive), wasEnabled=true, wasActive=true → isEnabled=false, isActive=false
20:43:07.064  PermissionManager: [Diag] a11y enabled=false active=false recentSignal=false
20:43:07.065  PermissionManager: [NOTIFY-DIAG][MISSING] noLost branch. allMissing=[无障碍服务]
```

`A11y Event` 首次出现于 **20:44 分**（20:44: 13 条 / 20:45: 26 条 / 20:46: 102 条）；20:40–20:44 区间内 `ensureAlarmOverlayInWindowMode`、`ALARM_BLOCK`、`shutdown_dialog`、`reassertAlarmOverlay` **命中全部为 0**。

**无障碍关闭时的实际表现**（用户观察）：长按电源键 → 挑战界面直接消失，需解锁进入 App 重新点开挑战才能继续。**无障碍开启时**拦截路径才生效，才会发生下述窗口丢失。

**复现运行时间线**（logcat 全程落盘，未清缓冲）：

```text
20:46:12.403  ensureAlarmOverlayInWindowMode: switched from inline to window mode
              (TYPE_ACCESSIBILITY_OVERLAY)              ← 覆盖层被切成独立窗口
20:46:12.403  reassertAlarmOverlayOverSystemDialog: reason=shutdown_dialog
20:46:14~33   btn_alarm_scan clicked ×6
              每次 → setAlarmOverlayPassthrough: enable=true
              随后 → shutdown_dialog 再次出现 / shutdown_dialog_after_back
20:46:39.217  AlarmActivity: Scan success
20:46:39.223  [ALARM_CHALLENGE_RESULT] challengeId=QR-1 step=1
              result=FINAL_CORRECT progress=1 totalSteps=1     ← 挑战确实完成
20:46:39.233  AlarmVibrationService: Sent ACTION_ATTENUATE
20:46:39.291  Alarm attenuated: volume=0.02f, vibration=mild 20/255 pulse
20:46:39.3+   ★ 再无任何 UI 相关日志
20:47:36      AlarmVibrationService: Vibration keep-alive: re-triggering   ← 持续到排查时
```

**根因**：`ensureAlarmOverlayInWindowMode()` 把覆盖层从 inline 切为独立窗口后，**该窗口在关机菜单交互过程中丢失**，而 `mAlarmView` 引用仍被持有，成为无父容器的游离视图。代码自身反复检测到该坏状态（全会话 **4 次**）：

```text
forceResetAlarmOverlay: mAlarmView=true, parent=false, layoutParamsType=LayoutParams, isCurrentOverlayInline=true
showAlarmOverlay: stale overlay detected (active=true, attached=true, view=true), force-resetting
```

扫码成功后 `onAnyChallengePassed()` **执行完整**（`challenge_completed` 写入、`attenuate()` 已调用，对应 `LockAlarmOverlay.kt:1708-1723`），但紧随其后的 `showCompletedChallengeState(view)`（`:1723`）作用在**已脱离窗口的视图**上，屏幕上不产生任何渲染；同时 AlarmActivity 的挑战内容因"已完成"而不再绘制 → **白屏**。`alarm_active` 保持 true，震动 keep-alive 持续续期 → **无法退出**。

**窗口状态**：排查时 `TYPE_ACCESSIBILITY_OVERLAY` 与 `TYPE_APPLICATION_OVERLAY` **均不存在**，仅剩 `AlarmActivity` 与 `MainActivity`——覆盖层窗口确已丢失。

**因果链定位**：`无障碍开启`（前置）→ 关机菜单被拦截（`CoreService.kt:880`）→ `ensureAlarmOverlayInWindowMode()` 切换窗口模式 → 抢层级过程中窗口丢失 → 游离视图 → 扫码成功后白屏。**根因是第 3–4 步的窗口生命周期管理，无障碍只是让这条路径可达。**

**与 5.3 的关系**：`reassertAlarmOverlayOverSystemDialog` 在本次运行触发 **12 次**，即应用持续与系统抢占窗口层级；"不牢固的防关机"正是这一抢占过程的表现，而抢占过程本身会破坏自己的窗口。

### 5.2 无障碍枚举期间的框架级异常反复抛出

```text
java.lang.IllegalArgumentException: parameter must be a descendant of this view
  at android.view.ViewGroup.offsetRectBetweenParentAndChild(...)
  at ...AccessibilityInteractionController$AccessibilityNodePrefetcher...
  at com.decikeeper.app.service.CoreService.onAccessibilityEventInternal(CoreService.kt:988)
```

单次会话内出现 **54 次**，均被 `AntiUninstallProtection` 的 `CRITICAL: Service Crash Prevention` 捕获。触发点在 `getRootInActiveWindow()`——系统在无障碍枚举 view 树时视图被并发修改的框架级竞态，属于锁屏/挑战切换期的高频噪声。

### 5.3 「闹钟防关机」开关无效的真机验证（部分完成）

用户实测：

- **未锁屏**、处于扫码界面时长按电源键 → **无任何防关机行为**（与 §1.4 静态结论一致：拦截只由 `LockWindowManager.isAlarmOverlayShowing` 触发）；
- **锁屏**时长按电源键 → 出现**不牢固的部分拦截**：关机菜单被短暂压制后仍回到挑战页，继而触发 §6.1 的白屏与死锁。

> **状态**：静态分析（`AlarmPowerOffProtectionManager.isEnabled()` 无运行时消费点）与真机现象方向一致，但**开关开/关的对照实验尚未完成**，暂记为部分验证。

### 5.4 处置

首次卡死时按用户授权执行 `adb shell am force-stop com.decikeeper.app`（只杀进程，未改数据）：进程退出、前台通知清除、20 秒内未自行拉起。

**更正**：初版称"矛盾状态位仍存在、下次启动会再次恢复坏会话"——**该警告不成立**。重启后实测 `alarm_active=false`，状态位被正常清理（`challenge_completed=true` + `first_stage_passed=false` 本就是单次模式完成后的正常组合）。

---

## 6. 方法与边界

- 7 个只读审计单元并行执行，每单元负责一份或一组文档；所有结论要求 `文件:行号` 级证据，拿不准的归入「无法判定」。
- 其中 6 条最高影响结论由主会话**独立复核**（已在正文标注「已独立复核」）；复核中修正了 2 条子代理判定的**严重度与归因**（alarm.md 提示按钮一条降级并改归因为文档措辞、protection.md 全局黑名单一条修正了文件坐标）。
- 全程只读：未修改任何被审计对象，未执行 `git add`/`commit`，未运行 `gradlew`/`python`（避免写入 `build/`、`__pycache__`）。
- 历史名 `ZhiZhi` / `zhizhi_settings_backup` / `iron_will_*` 已核对为**有意的数据兼容标识**，不计入漂移。
