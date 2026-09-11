# Notes: 频繁锁屏惩罚扣次与弹窗文案

## Sources

### User-confirmed behavior
- 惩罚弹窗移除面向用户的事务 ID 与毫秒时间戳。
- 非冷却基础扣除 1 个 half-unit，即 0.5 次；冷却中扣除 2 个 half-units，即 1 次。
- 当前段不足时向下一段借用，下一段偿还成本为借用本金的 2 倍，最多 4 个 half-units。

### Repository contracts
- `docs/specs/focus.md`：次数 half-unit、冷却整单倍率、同业务日借用与“借 1、后续扣 2”。
- `AGENT_CORE.md`：先读最近 README；保持 UI → Manager → Data 边界；Android 代码变化需要 JVM 测试与 Debug 构建。

## Findings
- `CreditManager.consumePenaltySeconds` 当前直接写入时长账本，再用固定常量写当前段 `2`、下一段 `4`、下一段借用账本 `2` 个 half-units；函数没有返回值。
- 现有 `TemporaryAccessQuotaPlan` 已使用同名次数拆解字段，但惩罚路径仍未返回拆解结果，可沿用字段语义，不新增重复计算 helper。
- 现有统一惩罚回执保留 11 字段；本次只把 `RestStatsRecorder.costCount` 改为真实半次数折算值，事务 ID 继续传给内部日志/事件。
- `LockWindowManager.issuePenaltyTransactionOnce` 已计算时长的正常/借用拆解与冷却来源，但把 `transactionId` 拼到了 `mPendingPenaltyMessage`，且未调用新的扣次结果。

## Verification

- 指定三组测试通过；冷却测试第一次失败是测试期望错误：夹具先写入当前次数 0，却断言为上限加 2，已改为断言写入 2 个 half-units。
- 受影响的 `PenaltyPayloadEquivalenceTest`、`RestStatsRecorderAndHistoryTest` 通过。
- `assembleDebug` 通过；资源阶段曾暴露 `dialog_alarm_default_settings.xml` XML 声明前空行，已修复。
- `rg` 复核弹窗构建块不再包含 `tx=`；内部 `Log.d` 仍保留事务 ID。
