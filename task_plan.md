# Task Plan: 频繁锁屏惩罚扣次与弹窗文案

## Goal
实现锁屏惩罚的真实次数扣减拆解、跨段借用规则和用户可读弹窗明细，并通过指定单元测试与 Debug 构建验证。

## Phases
- [x] Phase 1: 读取规范、定位源码调用链与测试契约
- [x] Phase 2: 实现 CreditManager 扣减结果与 LockWindowManager 文案
- [x] Phase 3: 更新/补充单元测试
- [x] Phase 4: 运行指定测试、Debug 构建并复核差异
- [x] Phase 5: 整理交付记录

## Key Questions
1. 当前 `consumePenaltySeconds` 的时间、次数和账本写入路径是什么？
2. 当前段可用次数、下一段借用与弹窗格式的既有 helper 如何复用？

## Decisions Made
- 按用户确认的 half-unit 规则实现，保留事务 ID 仅在日志中。
- 先创建 `codex/` 任务分支，避免直接在 `dev` 上修改。

## Errors Encountered
- 首次 Gradle 资源处理被 `dialog_alarm_default_settings.xml` XML 声明前的空行阻塞，已删除前置空行。
- 首次目标测试编译因 `RestUsageRecord.costCount` 改为 Double 后仍有 Int 测试字面量，已更新为 Double。
- 目标测试首次运行有一个冷却断言把初始 0 次误写成了“上限 + 2”，已修正测试夹具断言；生产计费逻辑未因此修改。
- Gradle configuration cache 仍报告既有 `AndroidUnitTest` 脚本引用告警，但不影响本次测试与构建成功。

## Status
**Completed** - 已完成实现、测试、Debug 构建和差异复核；当前未提交 commit。
