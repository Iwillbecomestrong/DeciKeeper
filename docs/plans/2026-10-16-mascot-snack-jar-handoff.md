# 团团零食罐 · 交接文档（2026-10-16）

> 给接手的 AI：先读 `AGENT_CORE.md`、`CLAUDE.md`（入口协议）和本文件。用户习惯：**默认中文回复**，改文件就改、不必叙述；发版、推送、合并、装机都要先得用户确认。

## 1. 目标一句话

把「额度」做成**团团的零食**：每个勿扰时段团团有一罐饼干，用户每次解锁/借用都是在向团团要零食；放宽额度要「等快递」，加严立即生效。团团只是**显示层**，**不得改变任何额度规则**。

## 2. 当前状态（接手点）

### 2.1 分支与 worktree

| 路径 | 分支 | 内容 |
|---|---|---|
| `G:\IronWIl0329\IronWill-OpenSource` | `dev` | 主工作区。已含：扫码修复（DECISION-56）、备份纳入应急退出额度与白名单（DECISION-57）、导出按分类表。**未升 v0.4.4，未推送** |
| `...\IronWill-OpenSource-mascot` | `feat/mascot-companion` | 团团赶路首版（首页 + 锁屏页进度条，三姿势） |
| `...\IronWill-OpenSource-math` | `feat/math-display` | 题目公式显示（轻量上下标 + `$...$` 交给 JLaTeXMath，DECISION-58） |
| `...\IronWill-OpenSource-test` | `test/integration-mascot-math` | **仅验证用**，合了上面两支；已装到用户手机，**不得发版** |
| `...\IronWill-OpenSource-snackjar` | `feat/mascot-snack-jar` | **本文件所在的新 worktree**，基于 `feat/mascot-companion`，接手后在这里开发 |

所有 worktree 的 `local.properties`（签名与 SDK，被 git 忽略）已从主工作区复制；新建 worktree 要自己复制。

### 2.2 真机

- 手机序列号 `10CF290X6R001BU`（iQOO Neo10）。USB 调试时断时续：Windows 里能看到设备但 `adb devices` 为空时，让用户在开发者选项里关开 USB 调试、撤销授权、重插。
- 当前装的是测试分支 release，SHA-256 `c7d93cb9…`（含团团赶路、公式、备份）。**用户尚未回报三件事的验证结果**。

### 2.3 已完成的团团相关代码（在 `feat/mascot-companion`）

- `manager/MascotJourney.kt`：纯计算，等待/行进/到达/无时段，含跨午夜与重叠；`caption()` 文案。单测 `MascotJourneyTest`。
- `manager/MascotJourneyProvider.kt`：读 `TimeRuleManager.getTimeSegments`，按 `isActiveToday()` 且 `limitMins <= 时段时长`（非免费时段）挑选。
- `ui/MascotJourneyView.kt`：自定义 View，每 30 秒自刷新，无时段自动隐藏；`app:mascotBob="false"` 关闭常驻起伏（锁屏悬浮窗用）；到达时一次性欢呼。
- 布局：`fragment_home.xml`（“今日规则”卡之前）、`window_lock_screen.xml`（`tv_sub_status` 与 `layout_quota_bubble` 之间）；`values/attrs.xml` 的 `MascotJourneyView`。
- 图：`res/drawable-nodpi/mascot_walk.webp`、`mascot_sleep.webp`、`mascot_happy.webp`（取自宣传视频已有姿势，最长边 320px）。
- `docs/DATA_SOURCES.md` 已记素材来源（GPT 生成）。

## 3. 已和用户确认的设计

角色叫**团团**（不是“团子”）。

1. **三条条**（锁屏页，团团站在旁边）
   - 时段进度条：已做（团团赶路）。
   - 时长条：当前时段**可用时长**剩多少，普通进度条（像油量表），数据即锁屏页 `tv_stat_time_big`。
   - 次数条：**可用次数**，一排饼干，**0.5 次 = 半块饼干**（规则：解锁用 ≤3 分钟扣 0.5 次，>3 分钟扣 1 次，见 `LockWindowManager` 里 `formatUsableCountHalfUnits`）。
2. **借用 = 从下一个时段的罐子里拿**：锁屏页右侧画一个淡色的“下一时段罐子”，借用时团团踮脚伸手，饼干从右罐飞到左罐，右罐相应变少。对应现有借用规则（借的是下一段额度）。
3. **放宽 = 等快递**：放宽额度后罐子不立刻变大，团团旁出现小包裹 + 倒计时（“新罐子明早 06:45 送到”）；生效时拆包裹、换大罐；到货前撤回则包裹退回。**加严立即生效**：罐子当场缩小，团团开心。数据来源：待生效值与生效时间（`decikeeper_rules_pending` 的 `effective_timestamp`、`ConfigPendingManager`、`ChangeLogManager`）。第一版只做“时段可用时长/次数”的放宽，其余类型（每周特权、借用比例等）仍用文字。放在锁屏页和「延迟生效表」页。
4. **点团团有反应**：压扁弹回 + 随机小话（“别戳啦”“再给我一块”）；连点 5 次隐藏动作；加防抖，不影响任何挑战。
5. **挑战闹钟界面只在两个时刻出现团团**：**延迟时**（关卡之间的等待冷却 → “刚醒”姿势；等相机连接/加载 → “刷牙”姿势）和**关闭时**（完成 → “开心”）。**答题、响铃、扫码过程中不出现**；只放角落，不挡题目和「应急」按钮，不拦截触摸。
6. 文案克制：**不把“变胖/变丑”当成后果**，不羞辱、不愧疚。额度用多了 = 团团的罐子见底、有点委屈；守住 = 开心。不做排行榜/积分商城/断签惩罚。

## 4. 素材与 Codex 生成流水线

### 4.1 已有姿势（可直接用）

`videos/promo-mv/public/char/*.png`（透明底 RGBA，已裁边，**`videos/` 被 .gitignore**）：走路、熟睡、安稳睡、开心、刚醒、委屈、定妆、着急伸手、伸手、捧手机、写字、刷牙、被吵醒、背面、爬梯子等。要用就挑图、裁边、缩到最长边约 320px、转 WebP（质量 90）放进 `app/src/main/res/drawable-nodpi/`，每张约 10–15KB。

### 4.2 新姿势与小物件（Codex 正在后台生成）

用户已确认清单：**6 个姿势**——抱满罐子（已出）、抱空罐子委屈、吃饼干、拆包裹、趴窗口等快递、踮脚伸手拿旁边罐子里的饼干；**3 个小物件**——一块饼干、空玻璃罐、小包裹。

- 脚本：`I:\mascot-gen\run.sh`（顺序执行，每张约 2–3 分钟，用 `-i` 传定妆/开心/走路作参考图保持角色一致）。结果路径逐行追加到 `I:\mascot-gen\work\results.txt`，以 `ALLDONE` 结尾；每张的日志在同目录 `<名字>.log/.txt`。
- 第一张已拷到 `I:\mascot-gen\raw\抱满罐子.png`（效果很好，风格一致）。其余图的原文件在 `C:\Users\23248\.codex\generated_images\<id>\*.png`，按 `results.txt` 取出，复制到 `I:\mascot-gen\raw\<名字>.png`。
- 调用方式（用 npm 版，旧版不支持该模型）：  
  `"/c/Users/23248/AppData/Roaming/npm/codex.cmd" exec -m gpt-6.1-sol --skip-git-repo-check -s read-only -C <目录> -i <参考图> -o <输出.txt> "<提示词>"`，提示词里要求“绿幕 #00FF00 底、无阴影、无文字，保存并只回复文件路径”。
- **C 盘常满**（约 3.8GB 空闲）：每张约 1.5MB，问题不大，但批量前先 `df -h /c`。
- 抠绿幕：`videos/promo-mv/scripts/key_green.py <输入> <输出.png> [预览.png]`；或本次对单张图用的 `scratch/mascot/key.py`（按 `g - max(r,b)` 求 alpha 并反算前景色去绿边，`scratch/` 被忽略，需要的话重写即可）。
- 流程：全部生成 → 抠图 → 拼一张总览图给用户挑（哪些留、哪些重做）→ 选定的缩成 WebP 进 `res`，并更新 `docs/DATA_SOURCES.md` 的素材列表。**不要让用户自己生图**（用户明确反感），用户只负责挑选。

## 5. 代码地图（接入点）

- 锁屏页布局 `res/layout/window_lock_screen.xml`；逻辑在 `ui/LockWindowManager.kt`：可用时长文本约 3188、3229 行（`tv_stat_time_big`），可用次数约 3198 行（`tv_usable_count_big`，半次单位），借用/结余行 `tv_surplus_borrow_line`，休息倒计时 `tv_rest_countdown_in_card`，下一段信息 `tv_next_segment_info`。
- 额度/借用/半次单位的权威逻辑：`manager/CreditManager.kt`、`LockWindowManager`（如“≤3 分钟扣 0.5 次”约 5929、6472 行附近）。**显示层只读取，不得改规则**。
- 待生效：`manager/TimeRuleManager.kt`（`applyPending…`）、`manager/ConfigPendingManager.kt`、`ChangeLogManager.kt`；延迟生效表页见 HISTORY 的 DECISION-49。
- 挑战闹钟：`ui/LockAlarmOverlay.kt`（关卡冷却 `alarmStageCooldownSeconds`、完成态 `dismissAlarmOverlay`、等相机的 `tv_alarm_scan_preview_status`“正在连接摄像头…”文本在 `window_alarm_overlay.xml`）。**悬浮窗历史上有白屏/被系统移除的坑，只做轻量更新**。
- 新组件建议：`SnackBarsView`（时长条 + 饼干次数条 + 邻罐）与 `MascotView`（姿势切换 + 点击反应）放在 `ui/`，计算逻辑放 `manager/` 做纯 Kotlin 单测；与 `MascotJourney` 共用同一份“当前时段”输入，避免重复算。

## 6. 工程约束与踩过的坑

- **提交规范**：`type(scope): 中文简述`（≤50 字，无句号）；先建任务分支 → 过测试 → 才合 `dev`；**发版/推送/合并/装机先问用户**。发版流程见 `.github/CONTRIBUTING.md` 7.1 与 `scripts/publish_release.ps1`；v0.4.4 还没发（要升版本号、写 CHANGELOG 和 release notes）。
- **HISTORY.md 决策编号**：最新是 DECISION-58（公式显示），下一个用 59；新的在最前，**先 `grep "^### \[DECISION-"` 看现有编号再编**（我曾误撞号）。
- **新增 SharedPreferences 必须登记** `manager/BackupPrefsClassification.kt`（`BackupPrefsClassificationTest` 扫源码强制）。若要存“已解锁/计数”等，注意统计有保留期，按“只增不减”合并，并更新 `docs/specs/data.md` 6.5。
- **悬浮窗里不用 WebView**；颜色一律取主题属性（`?attr/zz*`，经 `ThemeManager.getColorAttr`），四季主题 × 浅色/深色都要在真机看。
- **中文文案现状是直接写在代码和布局里**（项目沿用这种做法）。
- **Windows / 工具坑**：
  - 不要用 bash heredoc 里的 `python -` 写带反斜杠的 Kotlin/文档（`\f`、`\t`、`\n` 会被转义吃掉）。写含 LaTeX/反斜杠的内容请用编辑工具直接写文件。
  - bash 的 `/tmp` 与 Windows Python 的 `/tmp` 不是同一个目录。
  - Kotlin 里 `"$x"` 会插值，要写 `\$`（原样字符串里用 `${'$'}`）。
  - adb：用 `adb -s <序列号> install -r -g "$(cygpath -w <apk>)"`，装完用 `pm path` + 设备端 `sha256sum` 和本机比对；不要 `adb uninstall`（会清用户数据）。
  - Robolectric 4.11.1 在 Windows **没有原生图形运行时**，无法渲染截图，视觉效果只能真机看。
  - Gradle：依赖已缓存，可加 `--offline`；首次引入新依赖需联网。
  - 日志：release 包已去掉应用的 logcat 输出，真机排查靠应用内“导出日志”。
- 测试命令：`./gradlew.bat :app:testDebugUnitTest`（全量约 2–3 分钟）、`./gradlew.bat :app:assembleRelease`（release 签名，需要 `local.properties`）。

## 7. 待办（按优先级）

**P0 素材**
1. 等 `results.txt` 出现 `ALLDONE`，把 5 个姿势 + 3 个小物件拷到 `I:\mascot-gen\raw\`，抠图，拼总览图给用户挑；不合格的重跑（提示词在 `run.sh`，可改）。
2. 选定的转 WebP 放进 `res/drawable-nodpi/`（命名建议 `mascot_jar_full/empty`、`mascot_eat`、`mascot_parcel_open`、`mascot_peek`、`mascot_reach`、`obj_cookie`、`obj_jar`、`obj_parcel`），更新 DATA_SOURCES。

**P1 三条条（锁屏页）**
3. 时长条 + 饼干次数条（半块饼干）：读现有额度数据，纯计算 + 单测；布局不得挤压底部按钮。
4. 邻罐与借用动画（单次动画，不常驻）。

**P2 快递与延迟生效表**
5. 放宽包裹 + 倒计时；加严即时缩罐；撤回退回。仅“时段可用时长/次数”。

**P3 挑战闹钟与点击**
6. 只在“延迟”和“关闭”时出现团团（见 §3.5）；7. 点击反应与彩蛋（含防抖）。

**P4 收尾**
8. 全量单测；release 装机；用户验证；写 HISTORY（DECISION-59）与 `docs/specs/alarm.md`/`focus.md` 里相关说明；合 `dev` 前先问用户。

**验收标准（每项）**：单测通过；真机四季主题与深色下都清楚；锁屏页底部按钮可点；不改变任何额度/借用/解锁规则；没有新增未登记的 prefs。

## 8. 未决问题（接手后先向用户确认）

- 团团在“额度用多了”时的表情：用户倾向可爱幽默，我建议“委屈但不愧疚”。
- 每周特权上限、借用比例等其它放宽类型是否也做包裹（我建议第二批）。
- 首页是否也放零食罐（目前只定了锁屏页）。
- 用户尚未验证 §2.2 里装在手机上的三件事，结果可能带来修改。
