# 决定守护 DeciKeeper

**一个我自己用了大半年的 Android 工具：该做事时更容易把手机放下来，该起床时真的起得来。**

**一个不联网的 Android 自控应用：放宽规则要等到下一个生效周期，闹钟默认要完成挑战才能关。**

时间规则 + 挑战闹钟｜开源内测中

[⬇ 下载 v0.4.2 APK](https://github.com/Iwillbecomestrong/DeciKeeper/releases/download/v0.4.2/DeciKeeper-v0.4.2-arm64.apk) · [全部下载渠道](#下载) · [快速上手](docs/user/getting-started.md) · [功能指南](docs/user/guide.md) · [权限说明](docs/user/permissions.md) · [开发者指南](.github/CONTRIBUTING.md)

决定守护主要解决我自己长期遇到的两个问题：

- 该开始做事时，手机一拿起来就停不下来；
- 该起床时，闹钟按掉以后又继续睡。

![决定守护总览：时间规则与挑战闹钟](docs/images/readme配图.png)

### 三个特点

- **改规则很方便，但放宽不会立刻生效**：加严立即生效，放宽等到下一个生效周期（多数是次日）才生效。冲动时改不掉已经生效的规则，正常调整也不用被折腾。
- **闹钟默认要完成挑战才能关**（另有每月次数有限的应急退出）：扫码、计算、单词、诗词、选词填空、自定义题库，按你的需要选。我自己的用法是把二维码放在洗手台，让自己必须离床，但选什么挑战、要不要离床，由你决定。
- **完全不联网，数据只在你手机上**：最终 APK 不声明 `INTERNET` 权限，没有账号、不上传任何数据，源码 GPL-3.0 公开，可自行审计。需要无障碍等敏感权限的原因见 [权限说明](docs/user/permissions.md)。

目前 vivo / iQOO 是测试最充分的真机环境；OPPO 主要通过云测验证；其他 Android 品牌仍需要更多真机测试。

## 它怎么工作

决定守护有两个互相独立的模块：**时间规则**和**挑战闹钟**。

- **时间规则**把“修改规则”和“规则生效”分开：加严可以立即生效，放宽先保存、等到下一个生效周期才生效，恢复当前稳定规则可以立即恢复。规则由两把锁（**勿扰锁**、**应用时间窗**）和三个出口（**休息**、**临时解锁**、**每周特权**）组成。休息只解除勿扰锁，现在可以休息，不代表现在就可以打游戏。
- **挑战闹钟**参考了 QRAlarm 的扫码离床思路，再结合自己的需求发展，不宣称原创。

为什么这样设计、两把锁和三个出口的权能，见 [产品总览](docs/product/system-overview.md)；具体规则以 [SPEC](SPEC.md) 为准。

## 加强保护与兼容性

防卸载、防关关键权限、隐私空间与闹钟防关机等能力，目标是增加当场拆掉规则的难度；不承诺所有品牌、所有系统版本下绝对无法绕过。闹钟防关机是独立的即时开关，不套用时间规则的次日生效逻辑。

| 环境 | 当前状态 |
| --- | --- |
| **vivo / iQOO** | 长期主力真机，当前测试最充分 |
| **OPPO** | 主要官方云测，仍需要更多长期真机验证 |
| **荣耀 / 红米** | 暂未完成系统真机验证 |
| **其他 Android** | 欢迎参与测试 |

这里只表示测试充分程度，不代表品牌优劣。

## 第一次使用

先按 [快速上手](docs/user/getting-started.md) 开启基础权限，分别测试时间规则的拦截、休息和临时解锁；挑战闹钟先设短时测试，再在没有重要安排的夜晚进行跨夜测试。确认基础功能稳定后，再逐步开启无障碍、防卸载等加强保护。

## 下载

| 渠道 | 链接 |
| --- | --- |
| GitHub Releases | [DeciKeeper-v0.4.2-arm64.apk](https://github.com/Iwillbecomestrong/DeciKeeper/releases/download/v0.4.2/DeciKeeper-v0.4.2-arm64.apk) |
| 全部版本与校验 | [GitHub Releases](https://github.com/Iwillbecomestrong/DeciKeeper/releases) |
| 国内镜像（Gitee） | [DeciKeeper-v0.4.2-arm64.apk](https://gitee.com/my_room_welcome_you/DeciKeeper/releases/download/v0.4.2/DeciKeeper-v0.4.2-arm64.apk) · [全部发行版](https://gitee.com/my_room_welcome_you/DeciKeeper/releases) |

## 反馈、贡献与开源

- QQ 群：`980203768`
- 邮箱：`cyz200303@163.com`
- [GitHub Issues](https://github.com/Iwillbecomestrong/DeciKeeper/issues)：请勿公开上传含应用包名和时间戳的运行日志。

源代码按 [GPL-3.0](LICENSE) 发布。欢迎报告问题、补充 OEM 测试，也欢迎直接参与适配；完整产品理由见 [产品总览](docs/product/system-overview.md)，工程规则见 [SPEC](SPEC.md)。
