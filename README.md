<div align="center">

<img src="docs/images/logo.png" width="130" height="130" alt="知止 Logo" style="border-radius: 26px;" />

# 知止 (ZhiZhi)

**一个帮助人提前定义时间边界，并在适当时候停下来的开源 Android 工具**

[![License: GPL-3.0](https://img.shields.io/badge/License-GPL--3.0-blue.svg)](LICENSE)
[![Android](https://img.shields.io/badge/Android-SDK%2026--36-green.svg)](app/build.gradle.kts)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.0.21-purple.svg)](gradle/libs.versions.toml)
[![PRs Welcome](https://img.shields.io/badge/PRs-welcome-brightgreen.svg)](.github/CONTRIBUTING.md)

> **「知足不辱，知止不殆；知止而后有定。」** ——《道德经》/《大学》  
> **「我想要的不是更少的自由，而是更确定的自由。」**  
> **「约束要可信，自由也要可信。」**

[📖 使用手册](docs/使用手册.md) •
[📐 系统规范 (SPEC)](SPEC.md) •
[🗺️ 路线计划 (PLAN)](PLAN.md) •
[📜 决策经验 (HISTORY)](HISTORY.md) •
[🔄 更新日志](CHANGELOG.md) •
[🤖 AI 协作协议](AGENT_CORE.md)

</div>

---

## 💡 产品哲学与根本假设

市面上大多数自控软件给你的只是一个“开关”，它们本质上在做一件事：**假定你具有足够的意志力，一旦你想玩，就机械地禁止你。**

但真实的人性不是这样的。人在清醒时立下的誓言，往往在诱惑和疲惫面前不堪一击。

知止（ZhiZhi）的名字来源于古训：**知道什么时候停止。不是禁止一切娱乐，而是在适当的时候停下来。**

知止的根本假设只有一句话：

> **现有产品假设你不会失控，所以只有禁止；**  
> **知止假设你一定会失控，所以精心设计出口。**

### 五个本质维度的差异

| 维度 | 传统防沉迷 / 锁机产品 | 知止 (ZhiZhi) |
|:---|:---|:---|
| **核心视角** | 机械限制手机 | 管理不同时间下的**手机角色** |
| **规则形态** | 只有粗暴禁止 | **有边界，也有出口**（休息、临时解锁、每周特权） |
| **解锁心智** | 解锁代表意志失败与自责 | 解锁是一种**被有序管理的合理自由** |
| **规则变更** | 随时修改，即时生效（冲动时一键解开） | **对自己更严格立即生效，对自己更宽松延迟生效** |
| **产品生态** | 封闭黑盒，我设计你使用 | 开放白盒框架，**开源透明 + 允许用 AI 自主二次创作** |

---

## 📱 让手机在不同时间，扮演不同角色

自律不是把智能手机变成板砖，而是让它在合适的时间扮演合适的角色：

1. **学习与工作时间 ➔ 纯粹的工具**  
   保留查资料、看文档、背单词与通讯，屏蔽抖音、游戏与信息流。
2. **娱乐时间 ➔ 尽兴的娱乐设备**  
   进入预设的应用时间窗，在清晰的边界内畅快打游戏、看视频，**不带心理负担与负罪感**。
3. **睡眠时间 ➔ 纯粹的通讯工具**  
   仅保留电话接听与紧急短信，连浏览器、甚至深夜无休止提问的 **AI 聊天工具（如 ChatGPT）一并限制**——因为“再搜一个问题”、“再聊五分钟”同样是诱发失眠的无限黑洞。

---

## 🛡️ 承诺边界与信任设计

### 1. 坦诚的承诺边界
> **「它没有让我变得自律。它只是让我不再因为一次放纵，就把一整天都放掉。」**

- **知止服务谁**：内心还有目标、有要做的事，只是需要一条清晰可靠边界的人（备考学生、自习族、宿舍自控、亲子约定）。
- **知止不服务谁**：指望靠一个软件从天而降彻底拯救自己、完全放弃主动意识的人。

### 2. 关于卸载的信任契约
自控软件最容易让使用者产生“被程序绑架”的对抗和焦虑。知止从第一天起就确立了退出机制：

> **「它防的不是卸载，是那一瞬间想卸载的冲动。你今天说想走，明天就能走。」**

防卸载保护阻止的是你在想玩手机那一瞬间的冲动删除；只要你在应用内申请关闭保护，它会在次日刷新时刻解除，之后你可以像普通应用一样随时完全卸载。

---

## ✨ 核心系统概览

```text
               ┌── 自由系统（解决“停不下来”） ──┐
               │  • 两把锁：勿扰时段锁 + 纯娱乐时间窗锁
               │  • 额度滚存：未用额度 100% 顺延后续时段
知止架构 ───────┤  • 三个出口：申请休息、临时放行、每周特权
               │  • 动态延迟：加严即时生效，放松次日刷新生效
               └── 起床系统（解决“起不来”） ───┘
                  • 二维码强离床验证（杜绝半睡半醒在床上瞎蒙）
                  • 双阶段闹钟导航（把人从床头导航到洗漱间）
                  • Direct Boot 设备保护存储双写（防止关机避响）
```

## 📥 下载与安装指南

### 📱 普通用户安装（推荐）

1. 从 [GitHub Releases](https://github.com/Iwillbecomestrong/ZhiZhi/releases) 或 [Gitee Releases](https://gitee.com/my_room_welcome_you/zhizhi/releases) 下载最新发布的 `知止-ZhiZhi-v0.2.0-release.apk` 安装包。
2. **解除系统权限限制（Android 13+ 必读）**：
   由于开源侧载应用未走应用商店签名分发，Android 13 及更高版本会默认限制无障碍等关键权限。若开启无障碍服务时提示**“受限制的设置 / 出于安全考虑，此设置目前不可用”**，请执行一次性解除限制：
   > **进入系统「设置」➔「应用管理」➔ 找到「知止」➔ 点击右上角「⋮」（更多菜单）➔ 点击「允许受限制的设置」**。
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
# 安装新包名应用
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

---

### ⚠️ 数据安全与旧版备份迁移须知

> [!TIP]
> **旧版 IronWill 备份无缝恢复**：
> 若您此前使用过旧版 IronWill，可以在旧版中点击「设置 ➔ 导出配置」，然后在「知止」中点击「设置 ➔ 导入配置」直接选取该备份文件。知止已原生内置对 `ironwill_settings_backup` 格式的向后兼容支持，一键即可完整恢复您的全部勿扰时段、额度账本与自定义规则！

> [!CAUTION]
> **日常使用请勿随意卸载应用（尤其禁止执行 `adb uninstall com.zhizhi.app`）**！
> 卸载会将手机本地保存的加密规则、额度账本及自律记录全部抹除。
> 建议在应用内定期使用「导出配置 / 设置备份」功能将规则备份至外部存储。

---

## 📁 目录结构导览

知止遵循模块职责清晰、单向依赖与就地契约（Local README Gate）原则：

```text
ZhiZhi/
├── .github/              # 社区协作配置（Issue 模版、PR 模版、CONTRIBUTING.md、SECURITY.md）
├── app/                  # Android 客户端主工程（UI、Manager 业务层、Protection 防护层）
│   └── README.md         # App 模块开发指南、分层架构规范与构建单测说明
├── docs/                 # 项目知识中心与规格档案
│   ├── README.md         # 文档中心地图
│   ├── ARCHITECTURE.md   # 核心系统架构、依赖方向与重构蓝图
│   ├── DATA_SOURCES.md   # 词库与题面数据开源合规声明
│   ├── THIRD_PARTY_NOTICES.md # 第三方开源组件许可证声明
│   ├── PUBLISHING.md     # Gitee/GitHub 双推、构建签名与发版检查清单
│   ├── 使用手册.md        # 面向用户的详尽图文操作手册
│   ├── specs/            # 详细业务规格（requirements.md）
│   ├── history/          # 历史缺陷排查追踪（issue_tracker.md）与证据样本
│   ├── images/           # 视觉图档与高清 Logo
│   └── content/          # B 站首发内容稿件与口播字数统计工具
├── scripts/              # 数据清洗合规管线、日志审计工具与测试
│   └── README.md         # 脚本运行说明与自动化测试
├── CHANGELOG.md          # 语义化版本更迭日志
├── SPEC.md               # 项目意图真相（系统确认规格）
├── PLAN.md               # 项目实现真相（阶段路线图与当前工作）
├── HISTORY.md            # 项目决策真相（关键 [DECISION] 与防回归红线）
└── AGENT_CORE.md         # 所有 AI 助手通用的操作协作协议
```

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
