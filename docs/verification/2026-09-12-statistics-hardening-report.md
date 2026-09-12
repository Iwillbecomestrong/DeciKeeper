# 统计逻辑加固审计报告

## 结论

本次统计加固已完成源码级修复与 JVM 回归验证。闹钟/休息历史不再依赖单个 SharedPreferences JSON 作为运行时真理源；应用使用时长、分类下钻、规则分桶和筛选列表的已确认口径问题已修复。

## 已修复

1. UsageStats 只有真实前台状态转换才结算 session，忽略无关事件，未结束 session 统一使用 3 小时保护上限。
2. session 按本地午夜和图表桶边界切分，星期筛选不再吞掉跨日区间。
3. 分类详情按应用逐桶聚合，不再用全局比例近似缩放趋势。
4. 规则保护的周/月桶按桶内每个自然日累加，而不是只读取桶起始日。
5. 待生效数量改为读取时间规则、应用时间窗、黑名单和通用延迟配置的实际 pending 源。
6. 休息与规则最近记录遵守当前时间范围和星期筛选。
7. 休息流水迁移到 `rest_stats.db`，保留旧 JSON 一次性迁移、SQLite CRUD 和 Ledger 备份恢复。
8. 闹钟历史继续使用 `alarm_stats.db`，同时保留旧备份兼容。

## 验证证据

- 新增统计加固回归测试：UsageStats 重复累计、未结束 session 上限、跨桶切分、分类逐桶聚合、规则月桶、pending 计数、筛选范围和休息备份恢复。
- `./gradlew.bat :app:testDebugUnitTest --no-configuration-cache`：1267 tests，0 failures，0 errors，11 skipped。
- `./gradlew.bat :app:assembleDebug --no-configuration-cache`：成功。
- `git diff --check`：通过。

## 仍需真机确认

UsageStatsManager 的 OEM 事件序列、Usage Access 权限授予/撤销、旧版本升级迁移和系统备份恢复仍需在至少一台 OEM 真机上验证；这些行为无法由当前 Robolectric JVM 测试完全替代。

另外，旧版 `decikeeper_ledger` 的 `usage_*`、`borrow_time_*` 和 `privilege_usage_*` 只有自然日累计值，没有事件时间戳，因此自定义到小时的旧账本无法被无损拆分。本次没有伪造小时精度；新产生的流水已经具备 SQLite 时间戳，后续可继续把自定义区间总计切换到明细账本。
