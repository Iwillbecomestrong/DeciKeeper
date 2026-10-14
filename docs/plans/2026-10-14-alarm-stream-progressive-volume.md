# 共享闹钟音量随渐进阶梯提高

## 目标与范围

用户平时把系统闹钟音量调为 0，希望同时创建的系统闹钟不会因决定守护开始响铃就突然被抬到 80%。仅调整渐进响铃的音量输出与试听，保留固定模式、震动、再响、阶段恢复、通话让路和调度链；不加设置、不修改系统时钟的数据。测试和签名构建在本地执行，真机测试与公开发布留到晚上。

## 涉及文件

AlarmStreamVolumeLock.kt、AlarmAlertPolicy.kt、AlarmVibrationService.kt、AlarmVolumePreview.kt、AlarmFragment.kt、对应三个回归测试文件及 alarm scoped spec / 用户 guide / HISTORY。

## 验收

- 原音量为 0 时从最低非零档起步，单调爬升、不超过 80% 上限，最大声音设置限制结束档位。
- 升档不覆盖最初的音量快照，保护跟随当前目标，静音/停止/失败恢复 0；通话结束延续进度，再响和第二关按现有新周期起步。
- 试听与真实响铃使用同一模式换算，固定模式响度保持原行为；播放前先设通道目标，避免开头瞬间大声。
- 受影响测试、全量 JVM、Debug 与签名 Release 构建。

## 状态

- RED：零音量起步测试失败，期望 1，实际 12（最高档 15）。实现完成，35 项针对性回归全部通过。
- 全量命令：`gradlew.bat --console=plain --no-configuration-cache --continue --max-workers=2 :app:testDebugUnitTest :app:assembleDebug :app:assembleRelease`；退出码 0，1950 项中 1939 通过、11 跳过，失败和错误均为 0。Debug 与 Release 构建成功，Release APK v2 验签通过，最终 Manifest 没有 INTERNET 或 ACCESS_NETWORK_STATE。
- 当前 APK 仍为 versionName 0.4.1 / versionCode 4；本任务未升级版本、安装、提交、推送或发布。构建日志：`<临时目录>/decikeeper-alarm-volume-full-verification.log`。
- 真机响度、系统时钟播放与振动、四季和滑块试听未验证；不根据软件档位推断实测声压。
- AOCI 服务未连接，Overview 与索引维护不可执行，索引待对齐。
