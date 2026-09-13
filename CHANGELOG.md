# 更新日志 (CHANGELOG)

本项目遵循 [语义化版本 2.0.0](https://semver.org/lang/zh-CN/) 与 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/) 规范。

---

## [Unreleased]

## [0.3.0] - 2026-09-13

### 核心改进 (Changed / Improved)
- **全面更名「决定守护 (DeciKeeper)」**：
  - 中文名正式定名为「决定守护」，英文代号「DeciKeeper」。
  - Android 底层包名与 Application ID 彻底重构为 `com.decikeeper.app`，源码目录全面平移；`iron_will_*` 系列 prefs 键名统一迁移为 `decikeeper_*`。
  - 数据备份三重兼容：`SettingsBackupManager` 原生支持解析并导入 `decikeeper_settings_backup`、旧版 `zhizhi_settings_backup` 与更早 `ironwill_settings_backup` 规则文件，导入时自动将旧键名映射为新键名；`AutoBackupManager` 回退查找旧公共目录 `Documents/ZhiZhi/` 与旧快照，老用户换新包名后一键无损迁移。
- **双锁系统收敛与到期决策解析器 (ExpiryResolver)**：
  - 引入到期决策解析体系（`ExpiryResolver` / `ExpiryCoordinator` / `VisibleContainerResolver`），解决休息与特权到期后无条件硬锁屏、避免误锁正常退桌面与白名单应用。
  - 纯勿扰锁屏移除临时解锁入口，时间窗/双锁锁屏不提供申请休息，出口正交化。
- **冷却与借用口径平坦化收敛 (DECISION-29)**：
  - 彻底废除按秒分区加权模型，收敛为统一整单平坦倍率：触及冷却整单 ×2，借用整单 ×2，借用触及冷却整单 ×4（借 1 扣 4）。
- **浮窗与小窗防绕过保护 (FloatingWindowProtectionManager)**：
  - 新增小窗可见性增强守护，严格防御画中画与悬浮小窗绕过黑名单的漏洞。
- **开源结构治理与单源体系**：
  - 建立文档体系分层（`docs/product/`、`docs/user/`、`docs/specs/`、`docs/plans/`、`HISTORY.md`、`AGENT_CORE.md`）；
  - App 内置帮助文档与 `docs/user/` 建立构建期单源同步验证。

### 缺陷修复 (Fixed)
- **Direct Boot 恢复与开机静默处理**：
  - 修复开机直接启动（Direct Boot）时的状态恢复链，明确静默恢复时不主动拉起主界面 UI。
- **前台小窗与画中画拦截**：实现无障碍事件与窗口快照双流融合，精准拦截抖音等应用的画中画/小窗避锁漏洞。
- **应用时间窗**：修正时间窗锁机原因的界面展示与状态透传链路。
- **术语规范化**：生产用户可见文案彻底收敛为「临时解锁」，契约测试全面拦截「临时放行」等历史词汇。

---

## [0.2.0] - 2026-08-14

**IronWill 首次开源发布里程碑 (Initial Open-Source Release)**

### 新增特性 (Added)
- **本时段独立黑名单**：每个勿扰时段支持独立脱钩黑名单配置，支持全选、反选与恢复全局继承。
- **特权时长选择器自动截断与显示上限**：特权申请时长自动适配当日可用额度，超出上限自动截断并显示上限提示。
- **开源许可证**：以 GNU General Public License v3.0 (GPL-3.0) 许可证正式公开发布。
- **自动化构建流**：配置 GitHub Actions 工作流实现 Debug APK 自动化编译与单测校验。

### 数据与合规安全 (Security & Compliance)
- **数据合规清洗**：剥离所有商业真题解析逐字稿与来源证据，保留教育部公开真题题面。
- **AI 释义与翻译**：考研词库与完形填空释义全面替换为 AI 生成的简洁合规释义。
- **后端解耦**：彻底剥离早期云端同步后端，转向完全去中心化的 100% 本地运行架构。

---

## [0.1.0] - 2026-05 ~ 2026-07

**私有开发与真机演进里程碑 (Private Development Milestone)**

### 核心自律体系
- **勿扰时段 (DND Segments)**：支持全天多时段自由划分、休息额度跨时段滚存及紧急预支双倍扣减。
- **纯娱乐应用时间窗**：纯娱乐应用在时间窗外绝对不可启动，即使处于勿扰间隙（Gap）亦受拦截。
- **延迟生效铁律**：规则变更与每日法定基准比对，加严立即生效，放松次日刷新时刻生效（默认 08:00，可在全局参数中自定义）。
- **硬核防卸载与无障碍守护**：系统设置页、强行停止弹窗拦截（5秒热窗口）及防私密空间转移。
- **闹钟强挑战**：支持 Direct Boot 设备保护存储双写，防关机防护，提供数学、诗词、完形填空、扫码关闭挑战。
- **阶梯冷静期**：临时放行时长分段阶梯惩罚机制（1~60s / 61~120s / 121~180s）。
