# 历史记录保留与额度流水计划

## 目标

将历史记录的“显示范围”和“存储保留期”分离：起床记录默认保留 3 年，额度变化流水默认保留 3 个月；额度流水页面默认显示今天，并允许用户切换预设范围。

## 范围

- 起床记录：SQLite 自动清理默认 3 年内数据，并保留可配置保留期入口。
- 额度流水：SQLite 自动清理默认 3 个月内数据，并保留可配置保留期入口。
- 额度流水展示：明确显示额度增加/扣减、次数变化、变动后结余。
- 不改变额度计算、扣费、退款和起床统计业务规则。

## 验收标准

- [ ] 默认保留期为起床 3 年、额度流水 3 个月。
- [ ] 记录写入后清理过期记录，读取/迁移也不会重新带回过期记录。
- [ ] 用户可选择保留期；缩短保留期前有确认提示。
- [ ] 额度流水详情默认今天，可切换近 7 天、本月、全部。
- [ ] 每个新增行为有 JVM 回归测试，且全量单测与 Debug 构建通过。

## 涉及文件

- `app/src/main/java/com/decikeeper/app/manager/statistics/AlarmWakeStatsDatabase.kt`
- `app/src/main/java/com/decikeeper/app/manager/statistics/AlarmWakeStatsRecorder.kt`
- `app/src/main/java/com/decikeeper/app/manager/statistics/RestStatsDatabase.kt`
- `app/src/main/java/com/decikeeper/app/manager/statistics/RestStatsRecorder.kt`
- `app/src/main/java/com/decikeeper/app/ui/statistics/StatsHistoryActivity.kt`
- 对应布局、设置入口与 JVM 测试

## 状态

已完成：默认保留策略、SQLite 清理、额度流水展示/筛选、设置备份兼容与 JVM 回归测试均已落地。

## 验证

- 定向回归：`HistoryRetentionPolicyTest`、`AlarmWakeMultipleRecordsAndHistoryTest`、`RestStatsRecorderAndHistoryTest` 通过。
- 全量 JVM：`./gradlew.bat :app:testDebugUnitTest --no-configuration-cache` 通过。
- Debug 构建：`./gradlew.bat :app:assembleDebug --no-configuration-cache` 通过。
