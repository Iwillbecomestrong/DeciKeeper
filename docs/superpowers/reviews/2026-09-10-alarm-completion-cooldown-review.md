# 对抗性审查：闹钟完成后延迟与覆盖层恢复

- 日期：2026-09-10
- 审查范围：当前未提交改动；重点为 `LockAlarmOverlay.kt`、`AlarmScheduler.kt`、`LockWindowManager.kt`、`AlarmActivity.kt` 及其调度调用者/测试。
- 审查方式：只读静态追踪与差异审查；未运行 Gradle/Android 测试，以遵守“不要修改任何文件”的本次审查限制。
- 结论：**FAIL**。必须使用 **Sol fallback route**。

## 规格与计划核对

`docs/specs/alarm.md:94-99` 要求单、双阶段的完成后延迟均以倒计时结束为边界；单阶段到期后必须显示关闭按钮与任务回顾。`docs/specs/alarm.md:125-128` 要求 Overlay/Activity 重建后按同一 alarm session 恢复已完成/未完成状态。

当前 `task_plan.md:1-28` 只覆盖“频繁锁屏惩罚扣次与弹窗文案”，没有记录本轮闹钟状态修复的需求、验收路径、测试或 OEM 验证。因此计划与本次实现存在治理漂移；`AGENT_CORE.md:210-222` 的受影响测试、Debug 构建、OEM 状态和 Knowledge Sync 证据也未针对本修复提供。

## 发现的问题

### P1 — 延迟到期后重建会回到题卡并重新开始完整延迟

**需要修复：是。阻塞合入。**

证据链：

1. 初次完成时，单阶段把绝对截止时间写入 `AlarmScheduler`，并隐藏关闭操作：`LockAlarmOverlay.kt:1528-1534`。
2. 正在延迟中重建时，代码确实使用持久化截止时间、隐藏全部题卡并继续隐藏操作：`LockAlarmOverlay.kt:2364-2380`。这是本修复覆盖的正常路径。
3. 但如果重建发生在截止时间已经过去、且原 `Handler` 尚未来得及执行到期分支时，`LockAlarmOverlay.kt:2386-2391` 仅清除截止时间并直接 `return`。它没有调用 `showAlarmActionPanel(..., forceHide = false)`，没有恢复回顾卡，也没有保存“已完成”状态。
4. 同一次新建视图已由 `initAlarmUI()` 在 `LockAlarmOverlay.kt:755-760` 重新显示已配置题卡，且该方法默认隐藏操作区：`LockAlarmOverlay.kt:1100`。所以用户获得的是可再次答题、却不能关闭的页面。
5. 再次完成会再次执行 `LockAlarmOverlay.kt:1528-1534`，把新的 `now + stageCooldownSeconds` 写入持久化状态，因而从已到期的旧延迟重新计满一段延迟。

复现状态路径：完成单阶段挑战 -> 写入 `completionCooldownUntil` -> 进程/Overlay 重建或主线程延迟到截止时间之后 -> `syncChallengeUiByStage()` 清 key 并返回 -> 题卡重现、关闭区仍隐藏 -> 再提交 -> 新延迟开始。

这违反了规格的“倒计时结束后方可关闭”及“重建后恢复同一 session”契约，并同时否定了本轮的持久化、题卡隐藏、冷却重提不重计时三个目标。

### P2 — 新增测试只验证源码字符串，未覆盖三条关键状态路径

**需要修复：是。阻塞 P1 修复后的验收，不单独阻塞当前编译。**

`AlarmSessionIsolationContractTest.kt:118-136` 只检查方法/ID 字符串是否存在；它没有设置持久化截止时间、模拟重建、验证题卡/关闭按钮/回顾的状态，也没有二次调用完成入口并断言截止时间不变。测试名称声称“idempotent”，但没有执行两次提交。

至少应以 Robolectric/JVM 行为测试覆盖：

- 冷却未到期重建：所有挑战卡隐藏，关闭按钮仍隐藏，截止时间不变；
- 冷却已到期重建：挑战卡不重现，关闭按钮与回顾恢复，持久化状态清除；
- 冷却中重复的普通提交与 QR 回调：均不重写/延后截止时间。

### P2 — 当前计划没有覆盖闹钟修改和验证证据

**需要修复：是（治理/交付修复）。**

`task_plan.md:3-25` 与其“Completed”结论只描述惩罚扣次、对应单测及 Debug 构建；没有本轮闹钟问题、受影响文件、恢复状态机、测试命令、构建结果或 OEM 验证。该状态与 `AGENT_CORE.md:210-222` 的完成门禁不一致。更新正式计划/验证记录后，才可将闹钟修复声称为完成。

## 审查通过的路径（非最终验收）

- 新 scheduled session 会清除 first-stage 时间戳及单阶段完成延迟：`AlarmScheduler.kt:944-1002`；Receiver 与 Guardian 均显式传递 `newSession = true`：`AlarmReceiver.kt:240-252`、`AlarmGuardianService.kt:359-370`。
- 单阶段冷却未到期的 Overlay 重建会从 Device Protected Storage 读取绝对截止时间，并经 `hideAlarmChallengeCards()` 隐藏 QR、数学、诗词、单词、Cloze、自定义题卡：`LockAlarmOverlay.kt:1432-1440`、`2364-2380`。
- 普通完成入口在冷却仍有效时会只同步 UI 并返回，未写新截止时间：`LockAlarmOverlay.kt:1443-1460`。QR 完成入口在第一关已通过后会落到该普通入口：`LockAlarmOverlay.kt:2474-2510`。
- 工作树差异检查未发现空白/冲突标记问题（`git diff --check`）；该检查不等同于 Kotlin 编译或行为验证。

## 处理要求

1. 先修复 P1：将“恢复时已到期”的状态收敛为明确的 completed/review/close UI，而非清 key 后直接返回；不要通过再次答题推进该状态。
2. 建立 P2 的行为测试，并在不修改用户数据的前提下运行受影响 JVM 测试、`assembleDebug`；涉及恢复/Overlay 的 OEM 路径须明确真机验证状态。
3. 回写本次闹钟修复的正式计划与验证证据。修复完成前，使用 **Sol fallback route**。
