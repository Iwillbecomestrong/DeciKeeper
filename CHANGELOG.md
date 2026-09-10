# 更新日志 (CHANGELOG)

本项目遵循 [语义化版本 2.0.0](https://semver.org/lang/zh-CN/) 与 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/) 规范。

---

## [Unreleased] - 开发中

### 核心改进 (Changed / Improved)
- **全面更名「决定守护 (DeciKeeper)」**：
  - 中文名正式定名为「决定守护」，英文代号「DeciKeeper」。
  - Android 底层包名与 Application ID 彻底重构为 `com.decikeeper.app`，源码目录全面平移；`iron_will_*` 系列 prefs 键名统一迁移为 `decikeeper_*`。
  - 数据备份三重兼容：`SettingsBackupManager` 原生支持解析并导入 `decikeeper_settings_backup`、旧版 `zhizhi_settings_backup` 与更早 `ironwill_settings_backup` 规则文件，导入时自动将旧键名映射为新键名；`AutoBackupManager` 回退查找旧公共目录 `Documents/ZhiZhi/` 与旧快照，老用户换新包名后一键无损迁移。
- **全面更名「知止 (ZhiZhi)」**：
  - 中文名正式定名为「知止」，英文代号「ZhiZhi」，寓意《道德经》*「知足不辱，知止不殆；知止而后有定。知道什么时候停止。不是禁止一切娱乐，而是在适当的时候停下来」*。
  - Android 底层包名与 Application ID 彻底重构为 `com.zhizhi.app`，源码目录全面平移。
  - 数据备份向后兼容：`SettingsBackupManager` 原生支持解析并导入旧版 `ironwill_settings_backup` 规则文件，老用户换新包名后一键无损迁移。
- **开源结构治理**：全面引入 `project-organization` 规范，建立生活文档体系（`SPEC.md`, `PLAN.md`, `HISTORY.md`, `AGENT_CORE.md`）与 Local README 门禁（`app/`, `docs/`, `scripts/`）。
- **代码集净化**：移除误入 Java 源码目录的非代码文件与截图，规范视觉图档路径。
- **开源社区模版**：补充 `.github/` 下的 Bug 反馈、功能建议 Issue 模版及 Pull Request 审查模版。

### 缺陷修复 (Fixed)
- **无障碍服务待关闭刷新与子保护强依赖门禁**：
  - 修复关闭无障碍服务弹窗确认及取消申请时遗漏自身 UI 刷新的缺陷，状态文字与颜色立即由绿色就绪切换为橙色「`状态: ⏳ 待关闭（明日生效）`」。
  - 确立无障碍服务的前置基石地位：当无障碍服务未开启或处于待关闭状态时，严格拦截防卸载、防关权限、闹钟防关机及隐私空间保护的单独开启，开关即时回弹并给出轻量友好 Toast 提示。
- **前台小窗与画中画拦截**：实现无障碍事件与窗口快照双流融合，精准拦截抖音等应用的画中画/小窗避锁漏洞。
- **应用时间窗**：修正时间窗锁机原因的界面展示与状态透传链路。
- **测试鲁棒性**：修复脚本单元测试在缺失私有真机日志样本时的条件跳过逻辑。

---

## [0.2.0] - 2026-08-14

**IronWill 首次开源发布里程碑 (Initial Open-Source Release)**

### 新增特性 (Added)
- **本时段独立黑名单**：每个勿扰时段支持独立脱钩黑名单配置，支持全选、反选与恢复全局继承。
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
