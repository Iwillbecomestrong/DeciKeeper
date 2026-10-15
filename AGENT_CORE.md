# AI 协作协议核心 (AGENT_CORE)

> **定位**：本文件是所有 AI 编码助手（Claude / Gemini / Codex / Cursor 等）在「决定守护 (DeciKeeper)」项目中必须遵守的公共操作协议。
> 各平台薄入口文件（`AGENTS.md`、`CLAUDE.md`、`GEMINI.md`）只保留环境特例并指向本协议。

---

## 1. Local README Gate

在修改、创建、移动、重命名或删除任何文件前：

1. 定位目标路径；
2. 先读取离目标最近的 `README.md`；
3. 遵守该目录的职责、依赖与验证约束；
4. 最近目录没有 README 时，向上继承根 README 与本协议。

---

## 2. 项目治理与“当前真相”分层

项目不把所有真相压在一份文件里：

| 层 | 主要载体 | 负责回答 |
| --- | --- | --- |
| **产品总览** | `README.md` + `docs/product/system-overview.md` | 这是什么、为什么这样设计 |
| **产品意图** | `SPEC.md` + `docs/specs/*.md` | 产品现在应该怎么工作 |
| **实现事实** | 当前代码 + 测试 | 当前版本实际上怎么工作 |
| **执行路线** | `docs/plans/`（分任务计划） | 接下来准备做什么 |
| **历史决策** | `HISTORY.md` + `docs/history/` + Git | 为什么会变成这样 |
| **操作协议** | `AGENT_CORE.md` | AI / 贡献者如何安全工作 |

发生冲突时，不允许靠一句“以某文件为准”掩盖问题：

> **SPEC 与代码/测试冲突 = Convergence Gap，必须明确审计并收敛。**

当前详细规格入口：

- `docs/product/system-overview.md`（产品总览层：定位、模块关系、设计理由）
- `docs/specs/focus.md`
- `docs/specs/alarm.md`
- `docs/specs/protection.md`
- `docs/specs/data.md`
- `docs/specs/statistics.md`

同一条详细业务规则只能有一个权威归属；摘要可以重复，但不得维护两套互相竞争的全量规格。

---

## 3. 核心物理与操作红线 (Core Invariants)

以下为跨模块的最高红线，任何任务均不得违背：

### 3.1 部署安全与真机构建（优先 Release）

- **优先编译 Release 签名包**：真机验证、日常测试与交付**必须优先编译 Release**：
  ```bash
  ./gradlew.bat --console=plain :app:assembleRelease
  ```
  严禁未加说明直接编译 Debug 包覆盖真机，避免因签名不一致导致安装失败或引入非正式运行环境差异。
- **严禁卸载清库**：绝对**禁止 `adb uninstall`**（会永久抹掉用户的真实规则、题库与自律历史数据）。真机更新一律使用保留数据的覆盖安装：
  ```bash
  adb push app/build/outputs/apk/release/app-release.apk /data/local/tmp/app-release.apk
  adb shell pm install -r -t -d -g --user 0 /data/local/tmp/app-release.apk
  ```
  安装完成后必须通过 `dumpsys package com.decikeeper.app | grep lastUpdateTime` 核验时间戳确认生效。

### 3.2 产品灵魂原则：加严立即、放宽延期

- 正常保护期内，加严立即生效；放宽进入对应模块的 pending / 延期流程；
- 适应期、Fresh Segment / Regret Period、恢复 baseline、闹钟防关机即时开关等明确例外，遵从对应 scoped spec 和当前测试；
- 严禁为了所谓的“代码统一/简洁”而删除这些已被确认的核心产品机制。

### 3.3 防御性代码与真实 OEM 根因保护

- `docs/history/issue_tracker.md` 中带有真实真机根因的防御性逻辑（前台 Session 边界、小窗/PIP/分屏 tracking、无障碍重连代际、防卸载强行停止路径、隐私空间、闹钟重启与 Direct Boot 恢复等），**在未理解完整证据链前严禁擅自删除或“简化”**。

### 3.4 取证隔离红线

- 诊断、日志和压缩档案只能作为事后排查证据，**绝对不得被业务代码读取为放行或拦截依据**。

### 3.5 拒绝双重事实（Single Source of Truth）

- 具体的闹钟规则、时间规则、额度计算、按键与弹窗细节等**业务规格必须归属且只归属 `docs/specs/*.md`**；
- 严禁在 `AGENT_CORE.md` 中复制粘贴或打补丁式维护具体的子功能规则。

---

## 4. Android 与 UI 能力边界

### 4.1 无障碍不是所有能力的唯一来源

无障碍服务是当前多项关键能力的重要技术基础，尤其包括：

- 系统设置页 / 卸载 / 权限保护；
- 窗口树与小窗可见性证据；
- 部分系统级防绕过路径。

但基础前台识别和锁机链还存在 UsageStats、MonitorService、覆盖层等其他证据/执行路径，因此不得再写成：

> “应用前台检测、时间拦截、防卸载全部单点依赖无障碍。”

无障碍不可用时，应准确描述哪些能力降级，而不是把“部分仍可工作”夸大成“完全等价”。

### 4.2 不重新引入废弃路径

防卸载的当前核心执行依赖无障碍/系统页面识别与覆盖链路。不要为了追求“更强”擅自改成依赖旧 Device Admin 的不可退出方案。

### 4.3 全屏安全区

所有全屏 Activity 和主页面根容器必须正确处理 `statusBars()` / `displayCutout()` 等 WindowInsets。顶部标题、返回键和关键操作不能被状态栏或前置摄像头打孔遮挡。

### 4.4 四季主题动态联动

当前 App 使用春夏秋冬四季主题系统（春·苔樱、夏·潮汐、秋·茶陶、冬·雪岚）。新增或修改 UI 时：

- 页面背景、卡片、主按钮、警告/危险状态、待生效高亮边框与徽章优先使用现有主题 Token；
- 不为方便直接硬编码一套固定色值，导致主题切换后局部“死色”；
- 使用主题 Token 也不能只做形式联动：四季切换后需要有可辨别的季相变化；
- UI 变更除了自动化契约，还应做四季切换的视觉审查。

### 4.5 UI 视觉工程三大物理红线与机器门禁 (Visual Quality & Sanity Invariants)

> **最高工程治理原则**：**任何 UI 视觉规范，不得仅以文档约束存在；凡可机器验证的规则，应尽量实现为静态检查、运行时断言或自动化测试。** 杜绝仅靠自觉记忆，确保 AI 与开发人员任何修改均受机器门禁守门。

#### 4.5.1 全局界面顶部安全区避让红线 (Top Insets & Camera Safe Area)
- **全局界面必留白**：凡属于全局界面（独立 Activity、全屏 Dialog、全屏悬浮窗），根布局必须显式声明 `android:fitsSystemWindows="true"`，或通过 `ViewCompat.setOnApplyWindowInsetsListener` 消费 `systemBars().top` 留出安全区内边距；
- **打孔屏/摄像头防重叠**：严禁内容顶格延伸至屏幕最顶端，导致标题栏、返回键或核心控件与手机前置摄像头（打孔屏）或状态栏图标穿模重叠。

#### 4.5.2 WCAG 2.1 强对比度与禁绝硬编码颜色红线 (Contrast & No Hardcoded Color)
- **文字与背景强对比**：所有文本必须肉眼清晰可辨，严格满足 WCAG 2.1 AA 级相对亮度对比度标准（普通文本 $\ge 4.5:1$，大号/粗体文本 $\ge 3.0:1$）；
- **禁绝硬编码相反色**：严禁在浅色背景（如 `?attr/zzPageBg`、`@drawable/bg_zz_card`）上硬编码白色文字（`Color.WHITE`），严禁在深色背景上硬编码深色文字；所有前景色与背景色必须接入 `ThemeManager` 语义 Token 或通过 `ContrastHelper` 动态决策；未选中态必须保留清晰边框与深色高对比文字。
- **图表多维度分类可辨识**：图表必须保证各分类维度清晰可辨，严禁使用无法区分的相近色；图例指示色必须与图表对应维度色值 100% 像素级对齐。具体色盘配置由对应模块规格（如 `docs/specs/statistics.md`）定义。

#### 4.5.3 文本全显示与多层次防截断契约 (No Text Overflow & Auto-Sizing)
- **两层检查机制（静态与运行时）**：静态阶段高概率发现风险，运行时布局阶段准确判断实际是否发生截断或溢出；
- **静态规则门禁**：横排多按钮（如弹窗底部并排按钮，单按钮可用宽 $< 100dp$），若文本 $\ge 4$ 个汉字，必须使用 `AppCompatButton` 并开启 `app:autoSizeTextType="uniform"`，设置合理内边距；列表项长文本标题必须配置 `android:maxLines >= 2` 或动态折行，严禁单行固定截断；
- **运行时 Layout 检查与多配置测试**：通过 `UiVisualSanityChecker` 自动检测已排版 View 的 `layout.getEllipsisCount`、`lineWidth > availableWidth`、`layout.height > availableHeight`，并在测试中覆盖多套配置矩阵（如标准字体、`fontScale=1.3` 大字号、不同屏幕宽度），确保任意配置下零截断、零穿模。

### 4.6 品牌深度差异化展示与界面纯净原则 (OEM Feature Isolation & Clean UI)

针对 Android 各厂商定制系统（ROM）的差异性，UI 与引导设计必须严格区分机型呈现，**严禁将所有品牌的适配方案或专有特性一股脑全量平铺给所有用户**，避免造成干扰和困惑。当前真机重点深度测试聚焦 **vivo** 与 **OPPO**：

1. **按品牌动态过滤专有能力**：
   - **「隐私空间保护」**：仅在 **vivo/iQOO** 机型默认显示，OPPO 及其他品牌默认隐藏；
   - **「设备管理器防卸载锁」**：仅在 **OPPO/一加/真我**（ColorOS）机型默认显示，vivo 及其他品牌默认隐藏；
   - 不向未适配或无关机型平铺不可用或无意义的开关。
2. **说明与引导不暴露无关差异**：
   - 功能说明、弹窗指引与 Toast 仅输出当前机型专属内容，严禁在一个机型的说明中混杂其他品牌的路径或搜索建议。
3. **安全退出口冗余原则（历史已激活全机型保留）**：
   - 若某设备已激活系统级保护（如设备管理器处于激活状态或隐私空间拦截仍在生效），无论当前处于何种品牌手机，该项设置**必须保持可见**，确保用户随时能查看当前状态并正常操作关闭/解除激活，严禁因机型隐藏导致用户无法退出。

---

## 5. 分层与实现约束

目标依赖方向：

```text
UI
 ↓
Manager / UseCase / Pure Kotlin seam
 ↓
Android service / data / persistence
```

要求：

- Fragment / Activity 不新增复杂业务判定；
- UI 不复制额度、锁策略、pending 分类；
- 可纯化的状态机优先抽成 JVM 可测函数；
- Android Service 容器负责系统生命周期，不成为业务规则垃圾桶；
- 历史代码尚未完全达到目标架构时，不得把“目标”冒充“当前事实”。

---

## 6. Git 与分支

### 6.1 任务分支

正常开发流程：

```text
最新 dev
  ↓
任务分支
  ↓
实现 + 测试 + 构建
  ↓
同步期间新增的 dev
  ↓
处理冲突 / 再验证
  ↓
PR → dev
```

不要在 `main` / `dev` 上直接进行高风险试错。

### 6.1.1 发版红线

发版（合并 `main`、打 tag、发 Release）必须按 [`.github/CONTRIBUTING.md`](.github/CONTRIBUTING.md) 第 7.1 节执行，其中不得违反：

- GitHub `main` 受保护：新提交必须先在 `dev` 上通过 CI 才能推到 `main`；禁止强推；Gitee `main` 与 GitHub `main` 必须是同一个提交；
- 先推 `main` 和 tag，再创建 Release；Release 只能用 `scripts/publish_release.ps1` 发布（预检 APK 哈希、构建提交 = tag）；
- 真机验收装的必须是最终发布的那个 APK 文件，测完不得重新编译；
- 发版前后用 `python scripts/verify_markdown_links.py --check-release-links` 确认 README 下载链接可下载。

### 6.2 Commit

```text
type(scope): 简述
```

常用 `type`：

`feat` / `fix` / `perf` / `docs` / `test` / `refactor` / `chore` / `style`

尽量保持一个 commit 只做一件可说明的事。

---

## 7. Convergence Gate

宣告非平凡任务完成前至少检查：

1. **需求**：实际实现与用户确认内容一致；
2. **规格**：确认的行为回写根 SPEC / 对应 scoped spec；
3. **测试**：运行受影响的测试，JVM 单元测试全量绿色通过（`./gradlew.bat testDebugUnitTest`）；
4. **构建**：代码变更至少完成编译检查；真机部署与日常测试必须优先完成 Release 签名构建（`:app:assembleRelease`）；
5. **OEM 行为**：涉及系统设置页、无障碍、小窗、重启等必须说明真机验证状态；
6. **UI**：涉及界面变化时检查安全区、四季主题和关键可访问性；
7. **Knowledge Sync**：PLAN / HISTORY / README 按各自职责更新；
8. **分支**：合并前确认任务分支没有落后最新 dev，或明确处理了差异；
9. **数据安全**：严格执行保留数据的覆盖安装，绝不执行 `adb uninstall` 抹掉用户数据。

测试或构建未完成时只能报告“待验证 / 部分验证”，不得说“已经完成”。

---

## 8. 文档放置规则

- 当前产品总原则 → `SPEC.md`
- 产品定位 / 模块关系 / 设计理由 → `docs/product/system-overview.md`
- 时间规则 / 额度 / 出口 → `docs/specs/focus.md`
- 起床系统 → `docs/specs/alarm.md`
- 加强约束 → `docs/specs/protection.md`
- 业务日 / 备份 / 持久化 → `docs/specs/data.md`
- 真实统计与应用分类 → `docs/specs/statistics.md`
- 未确认或待实施 → `docs/plans/`（**分任务计划；不再建立根总计划**）
- 历史根因与防回归 → `HISTORY.md` / `docs/history/`
- 面向用户的完整说明 → `docs/user/`
- 已退役方案 / 旧命名 / 废弃设计 → `docs/archive/`（**只归档，不再维护**）

不要创建 `SPEC_v2.md`、`PLAN_final.md` 等平行真相文件；版本历史交给 Git。
