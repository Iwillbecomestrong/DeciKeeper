# 决定守护 (DeciKeeper) 架构决策与经验档案 (HISTORY)

> **定位**：本文件是「决定守护」项目的**历史决策真相（Historical Decision Truth）**，沉淀核心架构演进决策、高价值教训与防回归红线。
> 开源前完整的问题追踪与历史排查记录详见 [docs/history/issue_tracker.md](docs/history/issue_tracker.md)。

---

## 1. 关键架构决策记录 [DECISION]

### [DECISION-25] v0.2.0 单机内测版 About / Privacy 信息页收敛
- **背景**：当前 About 页沿用了面向正式运营场景的 7 个法务入口，向没有账号、服务器和商业化的单机内测用户展示了不必要的 SDK、个人信息、共享、注销和投诉页面，增加理解成本并造成产品状态误解。
- **决策**：
  1. About 页当前只展示版本信息、功能说明、隐私说明、开源许可、内容来源和可选反馈邮箱；
  2. 新增 `privacy_notice.txt`，用普通用户语言说明本地存储与通知、相机、无障碍、悬浮窗、使用情况访问权限用途；
  3. 旧文档资源、首屏同意流程和 `LegalDocumentActivity` 路由保留，但旧入口从当前导航隐藏，未来增加账号、云同步或数据服务时再恢复对应入口；
  4. 不改闹钟、权限申请、挑战和保护业务逻辑。


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
