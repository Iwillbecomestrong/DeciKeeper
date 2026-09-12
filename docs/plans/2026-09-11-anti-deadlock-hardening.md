# 全软件防卡死与兼容性对抗性审核与加固计划

## 目标

在最新 `dev` 基线的专项分支上，审查闹钟、锁窗口、无障碍防卸载与线程调度链路的死锁、黑屏、ANR、无限退栈和覆盖层失灵风险；只做不削弱既有保护强度的最小加固，并以 JVM 契约测试、全量单测和 Debug 构建闭环。

## 范围

- `LockWindowManager.kt`
- `LockAlarmOverlay.kt`
- `AlarmActivity.kt`
- `CoreService.kt`
- `AntiUninstallProtection.kt`
- `MonitorService.kt`
- 上述模块的直接测试与必要审计报告

## 非目标

- 不改变额度、冷却、时间窗、题库导入或其他业务规则；
- 不重构无关 UI，不触碰统计模块独立分支；
- 不因防卡死而把核心阻断改成软提醒；
- 不删除 `docs/history/issue_tracker.md` 已验证的 OEM 防御逻辑。

## 阶段

- [x] 阶段 1：确认 `dev` 基线、工作区洁净并创建专项分支
- [x] 阶段 2：读取规格、历史 OEM 证据并完成 Reviewer 源码调用链审查
- [x] 阶段 3：完成 Challenger 对抗复核，固定可复现风险与严重级别
- [x] 阶段 4：编写失败优先的 JVM 防卡死契约测试
- [x] 阶段 5：实施最小加固，保留保护强度与异常自愈
- [ ] 阶段 6：运行目标测试、全量 `:app:testDebugUnitTest` 与 `:app:assembleDebug`（Debug 已通过；全量 JVM 被范围外额度测试阻断）
- [x] 阶段 7：生成审计报告并完成分支状态、文档与 AOCI 收尾检查（AOCI 维护因既有 drift 阻断且未越界处理）

## 验收门禁

1. 每个确认的卡死风险点都有专属 JVM 契约测试；
2. `./gradlew.bat :app:testDebugUnitTest` 通过；
3. `./gradlew.bat :app:assembleDebug` 成功；
4. 审计报告记录证据、因果链、对抗结论、修改范围与验证结果；
5. 涉及 OEM 生命周期的结论明确区分 JVM 证据与真机未验证边界。

## 决策记录

- 基线：`dev` 在 `0c38993`，工作区初始洁净；专项分支为 `audit/anti-deadlock-hardening`。
- `dev` 相对 `gitee/dev` ahead 36；本专项不自动推送，也不回写远端。

## 错误与阻塞

- 暂无。

## 当前状态

**阶段 6/7：** Reviewer/Challenger、最小加固、目标契约测试与 Debug 构建已完成；全量 JVM 被未修改的 `ConsumePenaltySecondsEpisodeTest` 5 项失败阻断，AOCI 维护被仓库既有 188 项 code drift 阻断。
