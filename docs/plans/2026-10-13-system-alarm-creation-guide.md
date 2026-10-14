# 系统闹钟创建入口与首次建议

## 目标与范围

基于 dev 63040753：列表入口更名「一键创建系统闹钟」；首次从空列表新增保存真实闹钟后，在原有保活引导结束后建议创建同时间系统闹钟。提供「去创建 / 稍后」，提示仅展示一次。沿用系统时钟启动与参数传递，不改调度、保护或挑战链。

## 涉及文件

AlarmFragment.kt、item_alarm.xml、strings_system_alarm.xml、SystemAlarmCreationGuideContractTest.kt、alarm scoped spec、用户 guide 与 HISTORY。

## 验收与状态

- 入口、首次保存门禁、默认/测试隔离、持久化提示、既有系统时钟创建参数与布局自动缩字回归。
- 实现与三项新增契约测试完成；首次保存入口、一次提示标志、默认/测试隔离、既有系统时钟创建参数、独立行与自动缩字通过静态契约验证。
- 验证命令：`gradlew.bat --console=plain --no-configuration-cache --continue --max-workers=2 :app:testDebugUnitTest :app:assembleDebug :app:assembleRelease`，退出码 0；1943 项测试中 1928 通过、15 跳过，失败与错误均为 0。Debug 与签名 Release 构建成功，Release lintVital 无错误或警告。
- 四季、大字号与 OEM 系统时钟保存、跨夜响铃真机流程未验证；未安装、提交或推送。
- AOCI 服务未连接，无法执行 Overview 或收尾维护，索引待对齐。
