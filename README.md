<div align="center">

<img src="docs/images/logo.png" width="130" height="130" alt="知止 Logo" style="border-radius: 26px;" />

# 知止 ZhiZhi

**一个帮助你提前定义边界，在该停的时候停下来、该起的时候起来的开源 Android 工具。**

> **可以休息，也可以反悔，但别让一时冲动立刻推翻昨天的决定。**

[![License: GPL-3.0](https://img.shields.io/badge/License-GPL--3.0-blue.svg)](LICENSE)
[![Android](https://img.shields.io/badge/Android-8.0%2B-green.svg)](app/build.gradle.kts)
[![PRs Welcome](https://img.shields.io/badge/PRs-welcome-brightgreen.svg)](.github/CONTRIBUTING.md)

[📖 使用手册](docs/使用手册.md) ·
[📐 SPEC](SPEC.md) ·
[🗺️ PLAN](PLAN.md) ·
[📜 HISTORY](HISTORY.md) ·
[🤝 贡献指南](.github/CONTRIBUTING.md)

</div>

---

## 它解决什么问题？

知止主要解决两类很具体的问题：

1. **手机停不下来**：短视频、游戏、聊天、信息流不断挤占学习、工作和睡眠。
2. **闹钟关掉了，人却没起来**：半睡半醒把普通闹钟关掉，继续躺回去。

知止不是替你决定“应该做什么”。它更关心另一件事：

> **当你已经做出决定以后，怎样不让下一刻的冲动立刻把它推翻。**

---

## 核心机制

### 1. 加严立即，放宽延期

正常保护期内，受保护规则遵循：

- **加严限制：立即生效；**
- **放宽限制：延期到下一合法生效时刻。**

例如：缩短游戏允许时间可以立即生效；临时把 1 小时放宽到 3 小时，不会因为一时冲动马上生效。

> **可以反悔，但别秒反悔。**

### 2. 两把锁 + 三个出口

两把锁：

- **勿扰时段**：规定哪些时间进入限制状态。
- **应用时间窗**：规定某些高风险应用什么时候才允许使用。

三个出口：

- **休息**：只临时解除勿扰锁，不解除应用时间窗。
- **临时解锁**：处理扫码、验证码、短消息等极短需求；空档期或已经休息时不重复消耗休息额度。
- **每周特权**：用于少量、持续更久的特殊情况。

未使用额度可以在同一业务日内向后结余；额度不足时可以按规则向后续时段借用，当前核心口径是 **借 1、后续扣 2**。

详细规则见 [时间规则与额度规格](docs/specs/focus.md)。

### 3. 起床不是“把闹钟做得更响”

知止支持扫码、计算、诗词、单词等挑战。

扫码任务当前规则是：

> **扫描任意二维码即可通过。**

真正起作用的是二维码的物理位置。你可以把二维码放在卫生间、客厅或洗手台，让“关掉闹钟”必须伴随真实离床动作。

还可以开启可选第二阶段：第一阶段完成后，经过用户可配置的间隔，再完成第二次任务，减少扫完码又坐下来刷手机的情况。

详细规则见 [起床闹钟规格](docs/specs/alarm.md)。

### 4. 保护的是决定，不是永久绑住用户

知止提供防卸载、防关关键权限、隐私空间 / 特殊系统入口、闹钟挑战防关机等加强保护。

这些机制的目标不是让用户永远无法退出，而是增加冲动拆规则的摩擦。用户最终必须能够按产品规则关闭保护并退出。

不同 Android ROM 的行为差异很大，因此不会承诺“绝对无法绕过”，而是持续补强真实发现的绕过路径。

详细规则见 [保护机制规格](docs/specs/protection.md)。

---

## 第一次使用

1. 安装最新 APK。
2. 按应用内指引开启无障碍、悬浮窗、使用情况访问等必要权限。
3. 先配置一两个简单的勿扰时段和应用时间窗，确认你的设备运行稳定。
4. **不要一上来就开启防卸载。**先确认锁屏、前台检测、后台存活都正常，再开启更强保护。
5. 如果 Android 13+ 提示“受限制的设置”，需要在系统应用信息页允许该应用使用受限制设置后，再开启无障碍。

完整上手说明见 [docs/使用手册.md](docs/使用手册.md)。

---

## 下载

- [GitHub Releases](https://github.com/Iwillbecomestrong/ZhiZhi/releases)
- [Gitee Releases](https://gitee.com/my_room_welcome_you/zhizhi/releases)

---

## 开发与构建

环境以仓库当前 Gradle 配置为准。常用命令：

```bash
# Windows
gradlew.bat :app:testDebugUnitTest
gradlew.bat :app:assembleDebug

# Linux / macOS
./gradlew :app:testDebugUnitTest
./gradlew :app:assembleDebug
```

Debug APK：

```text
app/build/outputs/apk/debug/app-debug.apk
```

覆盖安装真实测试数据时优先使用：

```bash
adb install -r -d app/build/outputs/apk/debug/app-debug.apk
```

不要为了普通调试随意 `adb uninstall`，否则会清空本地规则和数据。

---

## 文档地图

| 文档 | 用途 |
| --- | --- |
| [SPEC.md](SPEC.md) | 当前产品总规格与跨模块契约 |
| [docs/specs/focus.md](docs/specs/focus.md) | 勿扰、应用时间窗、额度、休息、临时解锁、特权 |
| [docs/specs/alarm.md](docs/specs/alarm.md) | 闹钟、挑战、任意 QR、双阶段与恢复 |
| [docs/specs/protection.md](docs/specs/protection.md) | 防卸载、无障碍、权限、隐私空间、防关机 |
| [docs/specs/data.md](docs/specs/data.md) | 业务日、Active / Baseline / Pending、备份 |
| [docs/使用手册.md](docs/使用手册.md) | 面向用户的完整说明 |
| [HISTORY.md](HISTORY.md) | 关键历史决策与防回归经验 |
| [.github/CONTRIBUTING.md](.github/CONTRIBUTING.md) | 开发与 PR 规范 |

---

## 开源与数据

- 源代码按 [GPL-3.0](LICENSE) 发布。
- 数据来源与版权边界见 [docs/DATA_SOURCES.md](docs/DATA_SOURCES.md)。
- 第三方组件许可证见 [docs/THIRD_PARTY_NOTICES.md](docs/THIRD_PARTY_NOTICES.md)。
- 请不要向仓库提交未经授权的商业题库、出版物解析或付费翻译内容。

---

## 贡献

欢迎提交 Bug、兼容性问题、ROM 绕过路径、测试和代码改进。提交前请阅读 [.github/CONTRIBUTING.md](.github/CONTRIBUTING.md)。

知止会继续围绕一个简单方向迭代：

> **该停的时候停下来，该起的时候起来。**
