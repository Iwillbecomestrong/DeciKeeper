<div align="center">

<img src="docs/images/logo.png" width="130" height="130" alt="IronWill Logo" style="border-radius: 26px;" />

# IronWill

**一个不商量、不提供“就这一次”机会的 Android 强自律执行工具**

[![License: GPL-3.0](https://img.shields.io/badge/License-GPL--3.0-blue.svg)](LICENSE)
[![Android](https://img.shields.io/badge/Android-SDK%2026--36-green.svg)](app/build.gradle.kts)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.0.21-purple.svg)](gradle/libs.versions.toml)
[![PRs Welcome](https://img.shields.io/badge/PRs-welcome-brightgreen.svg)](.github/CONTRIBUTING.md)

[📖 使用手册](docs/使用手册.md) •
[📐 系统规范 (SPEC)](SPEC.md) •
[🗺️ 路线计划 (PLAN)](PLAN.md) •
[📜 决策经验 (HISTORY)](HISTORY.md) •
[🔄 更新日志](CHANGELOG.md) •
[🤖 AI 协作协议](AGENT_CORE.md)

</div>

---

## 💡 产品哲学与核心逻辑

你在清醒状态下制定的自律规则，往往在诱惑面前不堪一击。IronWill 的核心逻辑只有一句话：

> **事先定好规则 → 到了时间/遇到情况 → IronWill 自动锁机 → 必须消耗休息额度或完成挑战才能解锁**

它替你无条件执行规则，不商量，不妥协，不提供“再玩五分钟”的漏洞。

---

## ✨ 核心特性

- 🕒 **勿扰时段（DND Segments）**：支持将一天切分为多个时段（深夜/上午/下午/晚间），各时段拥有独立休息时长与退出次数；未用尽额度 **100% 自动滚存**，支持紧急预支（触发惩罚）。
- 🚫 **本时段独立黑名单**：每个时段可继承全局黑名单，亦可完全脱钩独立管控（支持全选/反选/一键复原）；系统桌面、输入法、电话、短信等核心关键应用受永久白名单保护，绝对不锁。
- 🪟 **应用时间窗**：纯娱乐应用（抖音/B站/游戏/浏览器等）受到**勿扰时段与时间窗双重限制**，即使在勿扰间隙（Gap 期），时间窗外也严禁启动。
- ⏳ **阶梯冷静期与双倍惩罚**：临时解锁提供分段惩罚机制（1~60s / 61~120s / 121~180s），冷却期双倍扣费，杜绝高频恶意尝试。
- 🛡️ **防卸载与防规避拦截**：底层依托 Android 无障碍服务与设备管理器，毫秒级退栈拦截“系统设置-应用信息”页、“强行停止”弹窗（5 秒热窗口防护）以及向隐私空间添加应用的逃避行为。
- ⏰ **硬核闹钟挑战**：闹钟响铃期间防关机，采用设备保护存储（Direct Boot 双写）防杀，必须通过数学题、诗词重排、考研完形填空或二维码扫描才能彻底关闭。
- ⚖️ **规则延迟生效铁律**：**加严（增加限制）立即生效，放松（放宽限制）次日生效**（默认次日 08:00，可在「全局参数」中自定义每日重置时间），彻底封死冲动避锁通道。

---

## 📁 目录结构导览

IronWill 遵循模块职责清晰、单向依赖与就地契约（Local README Gate）原则：

```text
IronWill/
├── .github/              # 社区协作配置（Issue 模版、PR 模版、CONTRIBUTING.md、SECURITY.md）
├── app/                  # Android 客户端主工程（UI、Manager 业务层、Protection 防护层）
│   └── README.md         # App 模块开发指南、分层架构规范与构建单测说明
├── docs/                 # 项目知识中心与规格档案
│   ├── README.md         # 文档中心地图
│   ├── ARCHITECTURE.md   # 核心系统架构、依赖方向与重构蓝图
│   ├── DATA_SOURCES.md   # 词库与题面数据开源合规声明
│   ├── THIRD_PARTY_NOTICES.md # 第三方开源组件许可证声明
│   ├── 使用手册.md        # 面向用户的详尽图文操作手册
│   ├── specs/            # 详细业务规格（requirements.md）
│   ├── history/          # 历史缺陷排查追踪（issue_tracker.md）与证据样本
│   └── images/           # 视觉图档与高清 Logo
├── scripts/              # 数据清洗合规管线、日志审计工具与测试
│   └── README.md         # 脚本运行说明与自动化测试
├── CHANGELOG.md          # 语义化版本更迭日志
├── SPEC.md               # 项目意图真相（系统确认规格）
├── PLAN.md               # 项目实现真相（阶段路线图与当前工作）
├── HISTORY.md            # 项目决策真相（关键 [DECISION] 与防回归红线）
└── AGENT_CORE.md         # 所有 AI 助手通用的操作协作协议
```

---

## 🛠️ 安装与部署指南

### 📱 普通用户安装（推荐）

1. 从 [Releases 页面](https://github.com/Iwillbecomestrong/IronWill/releases) 下载最新发布的 `IronWill-release.apk` 并直接安装。
2. **解除系统权限限制（Android 13+ 必读）**：
   由于开源侧载应用未走应用商店签名分发，Android 13 及更高版本会默认限制无障碍等关键权限。若开启无障碍服务时提示**“受限制的设置 / 出于安全考虑，此设置目前不可用”**，请执行一次性解除限制：
   > **进入系统「设置」➔「应用管理」➔ 找到「IronWill」➔ 点击右上角「⋮」（更多菜单）➔ 点击「允许受限制的设置」**。
   > 验证锁屏密码或指纹后，返回即可顺利开启**无障碍服务**、**悬浮窗（应用上层显示）**与**使用情况访问**等核心自律权限。
3. 详细图文权限开启指导请查阅 [📖 使用手册](docs/使用手册.md)。

---

### 💻 开发者编译与调试

#### 环境要求
- **JDK**：17（推荐 Eclipse Temurin 17）
- **Android SDK**：Compile SDK 36，Min SDK 26 (Android 8.0+)
- **构建工具**：Gradle Wrapper 8.13

#### 本地编译产物
```powershell
# Windows
.\gradlew.bat :app:assembleDebug

# Linux / macOS
./gradlew :app:assembleDebug
```
产物位置：`app/build/outputs/apk/debug/app-debug.apk`

#### ADB 调试安装
```powershell
# 覆盖安装更新（-r 保留用户数据，-d 允许降级安装）
adb install -r -d app/build/outputs/apk/debug/app-debug.apk
```

---

### ⚠️ 数据安全与卸载恢复须知

> [!CAUTION]
> **日常使用请勿随意卸载应用（尤其禁止执行 `adb uninstall com.ironwill.app`）**！
> 卸载会将手机本地保存的加密规则、额度账本及自律记录全部抹除。

- **数据备份与恢复**：建议在应用内定期使用「导出配置 / 设置备份」功能将规则备份至外部存储。
- **重装恢复**：若因换机或误删需要重新安装，可通过导入之前导出的备份文件一键恢复所有时段与额度规则；**但重新安装后，必须按照上述指引重新授予系统各项核心权限**。

---

## 🤝 架构与贡献

- **架构设计**：请阅读 [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)，严格遵循 `UI ➔ Manager ➔ Data` 单向依赖。
- **贡献流程与红线**：提交 PR 前请仔细阅读 [.github/CONTRIBUTING.md](.github/CONTRIBUTING.md) 及 [.github/SECURITY.md](.github/SECURITY.md)。
- **已知历史陷阱**：修改锁屏、无障碍及前台判定前，必须阅读 [HISTORY.md](HISTORY.md) 与 [docs/history/issue_tracker.md](docs/history/issue_tracker.md)。
- **多 AI 开发**：AI 编码助手请首先加载并遵循 [AGENT_CORE.md](AGENT_CORE.md)。

---

## 📄 数据资产与版权声明

- `app/src/main/assets/` 下的词库释义由 AI 辅助生成；真题题面源自教育部历年公开考研英语真题；商业书籍详细解析已完全从开源版本中剥离。
- 数据集来源详见 [docs/DATA_SOURCES.md](docs/DATA_SOURCES.md)；第三方库许可证声明详见 [docs/THIRD_PARTY_NOTICES.md](docs/THIRD_PARTY_NOTICES.md)。请勿向本仓库提交任何来源于商业出版物或未经授权付费翻译抓取的数据。

---

## 📜 开源许可证

本项目基于 **GNU General Public License v3.0 (GPL-3.0)** 许可开源。详见 [LICENSE](LICENSE) 文件。
