# 最终独立验收：单阶段完成后延迟、底部操作与恢复

- 日期：2026-09-10
- 范围：当前未提交工作区中与闹钟完成后延迟相关的改动；基线为 `HEAD`（`975c4ba`）。不将统计、惩罚账本等无关脏改动计入本结论。
- 方法：只读规格、计划、历史、代码和测试审查；运行了 `git diff --check`。依照本次只读约束，未运行 Gradle、安装或真机验证。
- 结论：**FAIL**。在修复下列阻塞项前，必须使用 **Sol fallback route**。

## 已满足的验收点

1. **单阶段、无复习内容（仅 QR）时的倒计时可见性**：通过静态链路审查。
   - 单阶段扫码成功会落入 `onAnyChallengePassed()`：`LockAlarmOverlay.kt:2492-2527`；该入口标记完成、衰减提醒、写入绝对截止时间、隐藏关闭按钮并启动倒计时：`1488-1551`。
   - 倒计时直接更新 `tv_stage_cooldown`，不依赖可选复习区域：`2317-2332`。
   - 该控件位于挑战 `ScrollView` 与复习区之后、底部操作区之前：`window_alarm_overlay.xml:958-1001`。因此 QR 不产生复习卡时，倒计时仍有独立固定显示位。

2. **`tv_stage_cooldown` 布局位置**：通过。
   - 根内容是满屏纵向 `LinearLayout`：`window_alarm_overlay.xml:10-15`。
   - 挑战列表与复习区是可伸缩区域；冷却提示和操作区位于其后，均不在任一滚动容器内：`53-60`、`768-956`、`958-1001`。

3. **关闭闹钟按钮保持底部固定**：通过静态布局审查。
   - `layout_alarm_actions` 是根纵向布局的最后一个常规子节点，`btn_alarm_close` 位于其中：`971-1001`。
   - 挑战/复习区域使用 `0dp + layout_weight=1` 吸收剩余高度，不能把按钮推进到其自身滚动区域：`53-60`、`768-775`。

4. **已到期单阶段冷却的 Overlay 重建**：此前首轮审查的题卡重现/再次计时问题已被静态修复。
   - 同一 session 重建保留题目和回顾状态：`LockAlarmOverlay.kt:289-304`。
   - 未到期时从 Device Protected Storage 截止时间恢复，隐藏所有挑战卡并继续隐藏关闭按钮：`2375-2391`。
   - 已到期或已完成时调用 `showCompletedChallengeState()`，而不是重新提交挑战：`2397-2408`；该方法隐藏挑战、按需恢复回顾、显示关闭按钮：`1439-1475`。

## 阻塞问题

### P1：完成后延迟在服务/设备恢复后会丢失“最低强度”提醒状态

**影响：阻塞最终验收。** 单阶段完成后正处于延迟期时，若进程/震动服务被杀或设备重启，恢复路径会把已衰减的提醒重新按普通策略启动；界面倒计时虽然可恢复，但“完成后立即保持最低强度”的行为不能恢复。

证据链：

1. 完成入口只将截止时间和 completed 标记写入 `AlarmScheduler`，并以 `AlarmVibrationService.attenuate()` 将当前进程的服务衰减：`LockAlarmOverlay.kt:1534-1550`；持久化内容不包括 attenuation 状态。
2. 恢复广播只按 `active + alarmId` 判断，随后以不带 action 的普通 Intent 启动 `AlarmVibrationService`：`AlarmReceiver.kt:123-170`、`173-193`。
3. 普通启动会读取原始闹钟音量/震动配置并调用 `startVibrationImpl()`：`AlarmVibrationService.kt:418-472`。它没有检查 `AlarmScheduler.isChallengeCompleted()` 或 `getCompletionCooldownUntil()`。
4. `isAttenuated` 是服务进程内字段，默认 `false`：`AlarmVibrationService.kt:190`；只有显式 `ACTION_ATTENUATE` 才会设置为 `true`：`393-416`。进程重建后恢复分支不会发送该 action。

这与 `docs/specs/alarm.md:94-99` 的完成后衰减与 `docs/specs/alarm.md:188-199` 的重启恢复契约相冲突，也使本轮新增的持久化状态只恢复了 UI、没有恢复完整业务状态。

### P2：两阶段最终完成后的同一 session 重建没有消费 completed 标记

**影响：阻塞“不得与既有两阶段流程冲突”的验收。** 第二阶段最终完成、用户尚未点击关闭时，Overlay/Activity 重建会重新显示第二阶段 QR/题卡并隐藏关闭按钮。

证据链：

1. 任意最终完成均会写 `markChallengeCompleted(true)` 并显示完成态：`LockAlarmOverlay.kt:1529-1555`；第二阶段 QR 完成也会落入该入口：`2492-2527`。
2. 但 `syncChallengeUiByStage()` 仅在 `!isTwoStageUnlock` 分支读取 `isChallengeCompleted()`：`2375-2410`。
3. 两阶段重建继续进入第二阶段分支；其中 QR 只要已配置就再次可见，操作区被强制隐藏：`2450-2475`。该分支不检查 completed 标记，也不调用 `showCompletedChallengeState()`。

因此，单阶段修复不能宣称与现有两阶段 session 恢复完全兼容。

## 证据与验证边界

- `git diff --check` 无空白/冲突错误（仅报告既有 LF→CRLF 规范化警告）。
- `AlarmSessionIsolationContractTest.kt:131-179` 是源码字符串断言，未建立 SharedPreferences/Overlay 状态机的 Robolectric 行为测试；它未覆盖 P1 的服务重启后强度，也未覆盖 P2 的两阶段完成后重建。
- 这轮无针对当前未提交改动的 Gradle、APK 或真机证据。依 `AGENT_CORE.md` Convergence Gate，涉及重启/覆盖层/OEM 的改动不能仅凭静态审查宣称完成。

## Sol fallback route 要求

在修复 P1 与 P2 前，必须使用 **Sol fallback route**。该路线至少应：

1. 将“completed + cooldown deadline”作为恢复时的完整提醒状态，确保恢复服务显式保持/重新进入 attenuation，而非默认 Alert Cycle；
2. 在两阶段分支优先处理 completed 状态，恢复完成/关闭 UI，禁止已完成第二阶段重新出现挑战；
3. 增加 Robolectric/JVM 行为测试覆盖单阶段 QR 无回顾、冷却中/到期后重建、服务恢复、两阶段最终完成后重建；随后运行受影响测试与 `assembleDebug`，并记录 OEM/重启实机结果。
