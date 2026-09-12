# 闹钟起床流水 SQLite 迁移计划

## 目标

将起床挑战历史从单个 SharedPreferences JSON 字符串迁移为 SQLite 按记录存储，保留已有用户数据、同日多次记录、统计口径和增删改能力，避免全量 JSON 重写与无提示丢失。

## 范围

- 新增起床流水 SQLite 数据库与表结构；
- 首次访问时幂等迁移旧 `alarm_wake_records_json` 与兼容旧日账本；
- `AlarmWakeStatsRecorder` 的读取、写入、修改、删除切换到数据库；
- 保留旧 JSON/日账本作为迁移来源，不再把它们作为运行时事实源；
- 更新手动/自动备份，使数据库记录可导出与恢复；
- 保持 `StatisticsRepository` 的“明细次数”和“按日聚合”口径不变；
- 增加 JVM 回归测试并完成全量测试与 Debug 构建。

## 非目标

- 不改变闹钟挑战规则、完成判定、额度、冷却和保护强度；
- 不改变同日多次记录在日级图表中采用最早主闹钟的既有统计口径；
- 不清空用户现有 SharedPreferences、数据库或备份；
- 不处理与本迁移无关的统计模块问题。

## 验收标准

- 旧 JSON 记录首次迁移后全部可读，重复启动/重复迁移不会产生重复记录；
- 旧版仅有的每日账本仍可迁移；
- 新增、修改、删除、查询记录均由 SQLite 完成；
- 同一天多条记录的原始明细全部保留，统计仍显示正确天数与次数；
- 备份包含数据库内容，恢复后记录可读；
- `./gradlew.bat :app:testDebugUnitTest` 通过；
- `./gradlew.bat :app:assembleDebug` 通过。

## 涉及文件

- `app/src/main/java/com/decikeeper/app/manager/statistics/AlarmWakeStatsRecorder.kt`
- `app/src/main/java/com/decikeeper/app/manager/statistics/AlarmWakeStatsDatabase.kt`
- `app/src/main/java/com/decikeeper/app/manager/SettingsBackupManager.kt`
- `app/src/main/java/com/decikeeper/app/manager/AutoBackupManager.kt`（如需要）
- `app/src/main/java/com/decikeeper/app/manager/statistics/StatisticsRepository.kt`
- `app/src/test/java/com/decikeeper/app/statistics/`

## 状态

Phase 1 已完成：确认旧 JSON/日账本、统计口径和备份分类边界。

Phase 2 已完成：实现 SQLite 表、幂等迁移、CRUD 和备份恢复，并通过定向测试。

Phase 3 已完成：旧版备份兼容测试通过；最终全量 JVM、Debug 构建、差异空白检查均通过。
