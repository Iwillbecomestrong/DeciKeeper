# 频繁锁屏惩罚扣次实现记录

## 变更摘要

- `CreditManager.consumePenaltySeconds` 返回 `PenaltyChargeResult`，在同一 `SharedPreferences.Editor` 中提交时长与次数账本。
- 次数规则：普通惩罚扣 1 个 half-unit（0.5 次）；任一冷却期扣 2 个 half-units（1 次）；当前段不足时借用本金，下一段按 ×2 计费并封顶 4 个 half-units（2 次）。
- `LockWindowManager` 弹窗移除 `tx=...` 和毫秒时间戳，展示时长、当前段次数和跨段借用次数明细；事务 ID 仅保留在内部日志/回执。
- `RestUsageRecord.costCount` 改为 Double，支持流水记录与统计页展示 0.5 次。
- `docs/specs/focus.md` 已补充惩罚扣次与弹窗公示契约。

## 验证结果

通过：

```powershell
./gradlew testDebugUnitTest --tests com.zhizhi.app.ConsumePenaltySecondsEpisodeTest --tests com.zhizhi.app.PenaltyDisplayCommitTest --tests com.zhizhi.app.PenaltyReclockIntegrationTest
./gradlew testDebugUnitTest --tests com.zhizhi.app.PenaltyPayloadEquivalenceTest --tests com.zhizhi.app.statistics.RestStatsRecorderAndHistoryTest
./gradlew assembleDebug
```

目标三组测试共 16 个用例通过；受影响统计/回执测试与 Debug 构建均通过。Gradle configuration cache 有既有告警，不影响结果。

## 未完成项

- 未执行真机连续锁屏手动验证。
- 当前工作树尚未提交 commit。
