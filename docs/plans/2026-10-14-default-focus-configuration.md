# 默认勿扰时段与规则参数

## 输入与边界

用户要求将「一键设置」及未配置时的规则参数默认值改成其当前待生效配置。输入：`I:/Program Files/Netease/GameViewer/Download/最新配置作为默认配置.json`，SHA-256 `CC3595296CD48028991C0AFEE945FC579D417904AFC6BB449E448393F6200122`。

只读取待生效时段/日参数和待生效每周特权上限；缺少待生效值的规则参数按应用当前 getter 实际使用的偏好文件取值。刷新时刻从 ledger 读取 06:45，不使用 rules 残留的 12:00。个人应用名单、历史账本、基准、保护开关和绝对生效时间不成为内置配置，也不导入设备。

## 涉及文件与验收

- TimeRuleManager、CreditManager、TimeConfigFragment、GlobalParamsActivity；默认参数、预设时段及存储值保留回归；相关规格与用户帮助同步。
- 四个时段启用，每个 3 次；前三个休息 30 分钟，深夜 10 分钟；晚上仅工作日。
- 新默认值只用于缺少存储键时，已有参数及旧键迁移保留；一键加载继续走原有加严/放宽/待生效机制。
- 针对性测试、全量 JVM、Debug 与签名 Release 构建；不安装、不发布。

## 状态

- RED：新默认参数测试失败，休息冷却期望 90、实际 120。实现完成；新增测试覆盖默认参数、四段时间/额度/重复、已存值保留及旧键迁移。
- 旧默认范围断言已改为 07:00–23:00；刷新默认断言与缺少匹配时段的回退改为 06:45；模拟固定 08:00 的结余测试显式关闭自动跟随。缺少刷新基准时沿用当前刷新值，避免使用旧 08:00 默认制造偏离。
- 最终命令：`gradlew.bat --console=plain --no-configuration-cache --continue --max-workers=2 :app:testDebugUnitTest :app:assembleDebug :app:assembleRelease`，退出码 0；1954 项中 1943 通过、11 跳过，失败与错误均为 0。Debug / Release 构建成功，最终 Release APK v2 验签通过。
- 版本仍为 0.4.1；未安装、提交、推送或发布。日志：`<临时目录>/decikeeper-focus-defaults-full-verification.log`。
- AOCI 服务仍未连接，索引维护待对齐。
