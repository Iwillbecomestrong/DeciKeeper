# Alarm Reminder Redesign Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将知止闹钟收敛为“第一关 + 可选完成后延迟 + 可选第二关”的统一流程，并统一固定/渐进提醒、再响静音恢复与第二关独立提醒策略。

**Architecture:** 新增纯 Kotlin `AlarmAlertPolicy` 作为提醒强度与阶段继承的单一计算源；`AlarmScheduler.AlarmEntry` 只负责持久化配置，`AlarmVibrationService` 负责当前提醒周期与 Android 声音/震动执行，`LockAlarmOverlay` 负责挑战阶段切换，`AlarmFragment` 只负责展示与保存配置。保留现有 OEM 恢复、防关机、覆盖层重挂载代码，不以重构为由删除已有防御逻辑。

**Tech Stack:** Kotlin, Android Service/SharedPreferences, JUnit4 JVM tests, GitHub Actions, Gradle Android plugin.

**Spec:** `docs/specs/alarm.md`

## Global Constraints

- 第一关是核心；第二关默认可选，不作为所有用户的推荐必选流程。
- 完成后延迟为 `0` 时不等待；大于 `0` 时第一关完成后保持最低强度，单阶段延迟结束后开放关闭，双阶段延迟结束后进入第二关。
- 渐进提醒每个新提醒周期从 1 级开始，每 30 秒 +1，最高 10 级后保持；不再存在“5 分钟突破”行为。
- 新提醒周期至少包括：首次响铃、再响结束恢复、第二关开始。
- 再响只在第一关未完成时使用；再响期间声音与震动为 0，挑战与进度保持；次数/时长服从有效设置，不硬编码为 1 次。
- 第二关默认继承第一关提醒策略；仅当“第二关使用独立提醒设置”开启时读取第二关独立模式/强度。
- 扫码挑战仍为扫描任意二维码即可通过；不得破坏现有题库、收藏、四季主题、WindowInsets、OEM 重启恢复与覆盖层防御代码。
- 历史字段 `autoEscalate`、`progressiveMaxIntensity`、`progressiveMaxRingVolume` 可为旧数据兼容继续反序列化，但不再影响新提醒行为，也不再暴露为新 UI 设置。

---

### Task 1: 建立纯 Kotlin 提醒策略 seam

**Files:**
- Create: `app/src/main/java/com/zhizhi/app/manager/AlarmAlertPolicy.kt`
- Create: `app/src/test/java/com/zhizhi/app/AlarmAlertPolicyTest.kt`

**Interfaces:**
- Produces: `AlarmAlertPolicy.progressiveLevel(elapsedMs: Long): Int`
- Produces: `AlarmAlertPolicy.resolveStagePolicy(stage1: StageAlertPolicy, useIndependentStage2: Boolean, stage2: StageAlertPolicy?): StageAlertPolicy`
- Produces: `StageAlertPolicy(alarmMode, vibrationMode, fixedVibrationIntensity, fixedRingVolume)`

- [ ] **Step 1: Write the failing tests**

Tests must assert at least:

```kotlin
assertEquals(1, AlarmAlertPolicy.progressiveLevel(0L))
assertEquals(1, AlarmAlertPolicy.progressiveLevel(29_999L))
assertEquals(2, AlarmAlertPolicy.progressiveLevel(30_000L))
assertEquals(10, AlarmAlertPolicy.progressiveLevel(270_000L))
assertEquals(10, AlarmAlertPolicy.progressiveLevel(900_000L))
```

and stage 2 inheritance/override:

```kotlin
assertEquals(stage1, AlarmAlertPolicy.resolveStagePolicy(stage1, false, independent))
assertEquals(independent, AlarmAlertPolicy.resolveStagePolicy(stage1, true, independent))
```

- [ ] **Step 2: Run RED**

Run in GitHub CI:

```text
./gradlew :app:testDebugUnitTest --tests com.zhizhi.app.AlarmAlertPolicyTest
```

Expected: FAIL because `AlarmAlertPolicy` does not exist yet.

- [ ] **Step 3: Implement the smallest policy seam**

`progressiveLevel` must use one constant `PROGRESSIVE_STEP_MS = 30_000L`; negative elapsed time is treated as 0; result is clamped to 1..10.

- [ ] **Step 4: Run GREEN**

Run the focused test, then the full `:app:testDebugUnitTest` suite.

- [ ] **Step 5: Commit**

```text
feat(alarm): add unified alert policy
```

---

### Task 2: 持久化第二关独立提醒与兼容旧数据

**Files:**
- Modify: `app/src/main/java/com/zhizhi/app/manager/AlarmScheduler.kt`
- Test: `app/src/test/java/com/zhizhi/app/AlarmProductionMigrationTest.kt`
- Test: `app/src/test/java/com/zhizhi/app/AlarmAlertPolicyTest.kt`

**Interfaces:**
- `AlarmEntry.stage2UseIndependentAlertPolicy: Boolean = false`
- `AlarmEntry.stage2AlarmMode: AlarmMode = AlarmMode.VIBRATE_AND_RING`
- `AlarmEntry.stage2VibrationMode: VibrationMode = VibrationMode.PROGRESSIVE`
- `AlarmEntry.stage2VibrationIntensity: Int = 7`
- `AlarmEntry.stage2RingVolume: Int = 7`

- [ ] **Step 1: Add failing JSON compatibility tests**

Verify an old JSON record without new stage-2 fields still loads with `stage2UseIndependentAlertPolicy == false`, and a new record round-trips all new fields.

- [ ] **Step 2: Run RED**

Expected: compilation/assertion failure because the fields are absent.

- [ ] **Step 3: Extend `AlarmEntry`, `toJson`, `fromJson`, and `createAlarm`**

Do not delete legacy serialized fields in the same task. Old alarms must preserve their current first-stage behavior and inherit it for stage 2.

- [ ] **Step 4: Run focused migration tests and full unit suite**

- [ ] **Step 5: Commit**

```text
feat(alarm): persist stage two alert overrides
```

---

### Task 3: 统一提醒周期与渐进算法

**Files:**
- Modify: `app/src/main/java/com/zhizhi/app/service/AlarmVibrationService.kt`
- Modify: `app/src/main/java/com/zhizhi/app/manager/AlarmScheduler.kt`
- Test: `app/src/test/java/com/zhizhi/app/AlarmAlertPolicyTest.kt`
- Test: existing alarm service/source contract tests as applicable

**Interfaces:**
- Service state: `alertCycleStartTime: Long`
- Service state: `activeChallengeStage: Int` (`1` or `2`)
- Public service action: `startStage2AlertCycle(context, alarmId)`
- Existing persisted vibration start timestamp is reused as the persisted alert-cycle start timestamp for compatibility.

- [ ] **Step 1: Add failing contract assertions**

Assert production code no longer computes progressive levels with `/ 10`, does not branch on `autoEscalate`, and delegates level calculation to `AlarmAlertPolicy.progressiveLevel`.

- [ ] **Step 2: Run RED**

Expected: existing 10-second/5-minute logic violates the contract.

- [ ] **Step 3: Replace service calculations**

For both vibration and ring volume:

```kotlin
val elapsedMs = System.currentTimeMillis() - alertCycleStartTime
val level = AlarmAlertPolicy.progressiveLevel(elapsedMs)
```

Fixed mode continues using the configured fixed strength. Progressive mode always reaches level 10 naturally; legacy progressive-max and auto-escalate fields are ignored.

- [ ] **Step 4: Add/reset reminder cycles**

- First real ring: set/recover cycle start.
- Snooze expiry: set cycle start to `now`, persist it, clear attenuation, resume output.
- Stage 2 start: set `activeChallengeStage = 2`, set cycle start to `now`, resolve stage-2 effective policy, clear attenuation, resume output.

- [ ] **Step 5: Run tests**

Verify 30-second steps, fixed mode stability, and stage-2 policy inheritance.

- [ ] **Step 6: Commit**

```text
refactor(alarm): unify alert cycles and progressive ramp
```

---

### Task 4: 修正再响为配置驱动的静音窗口

**Files:**
- Modify: `app/src/main/java/com/zhizhi/app/ui/LockAlarmOverlay.kt`
- Modify: `app/src/main/java/com/zhizhi/app/manager/AlarmScheduler.kt`
- Modify: `scripts/test_alarm_snooze_contract.py`
- Test: existing alarm snooze/unit tests

**Interfaces:**
- `AlarmScheduler.getRemainingSnoozeCount(context, alarmId)` is the single remaining-count source.
- `AlarmVibrationService.silenceForSnooze(...)` preserves the active challenge.

- [ ] **Step 1: Update snooze contract to RED**

Replace old one-shot assertions with configurable-count assertions: no literal one-shot cap, overlay initializes remaining count from scheduler, successful snooze decrements by one, and default UI preserves max-count configuration.

- [ ] **Step 2: Run RED**

Expected: current hard-coded `1` behavior fails.

- [ ] **Step 3: Remove hard-coded one-shot behavior**

Use the effective max count and existing `snooze_used_*` ledger. Hardware entry points must call the same request path and must fail when the configured allowance is exhausted.

- [ ] **Step 4: Ensure snooze expiry restarts a fresh alert cycle**

Progressive mode must return to level 1 after the silent window rather than resume the old elapsed level.

- [ ] **Step 5: Run snooze contract + JVM suite**

- [ ] **Step 6: Commit**

```text
fix(alarm): make silent snooze configuration driven
```

---

### Task 5: 完成后延迟与第二关开始语义

**Files:**
- Modify: `app/src/main/java/com/zhizhi/app/ui/LockAlarmOverlay.kt`
- Modify: `app/src/main/java/com/zhizhi/app/service/AlarmVibrationService.kt`
- Test: `app/src/test/java/com/zhizhi/app/AlarmChallengeTwoStageFlowTest.kt`
- Test: add focused source/behavior contract if Android UI state is not directly JVM-testable

**Interfaces:**
- Existing serialized `stageCooldownSeconds` remains the compatibility storage key for the user-facing “完成后延迟”.
- Single stage: delay 0 -> close action becomes available immediately; delay >0 -> attenuated reminder + hidden close until countdown ends.
- Two stage: delay 0 -> start stage 2 immediately; delay >0 -> attenuated reminder until countdown ends, then `startStage2AlertCycle` and show stage 2.

- [ ] **Step 1: Add failing flow tests/contracts**

Cover single-stage delay 0, single-stage positive delay, two-stage delay 0, and two-stage positive delay.

- [ ] **Step 2: Run RED**

Expected: stage-2 alert cycle reset and new wording/semantics are missing.

- [ ] **Step 3: Integrate stage-2 cycle start in both immediate and delayed branches**

Do not auto-close a single-stage alarm at delay expiry; expose the close action so the user can close it after the intended morning interval.

- [ ] **Step 4: Run focused and full tests**

- [ ] **Step 5: Commit**

```text
feat(alarm): unify completion delay and stage two transition
```

---

### Task 6: 设置 UI 收敛

**Files:**
- Modify: `app/src/main/java/com/zhizhi/app/ui/AlarmFragment.kt`
- Modify: `app/src/main/res/layout/dialog_alarm_default_settings.xml`
- Modify: `app/src/main/res/layout/dialog_alarm_edit.xml`
- Modify: test dialog only where needed for fast verification
- Test: source/UI contract tests under `app/src/test/java/com/zhizhi/app/`

**Interfaces:**
- Progressive copy: `从最低强度开始，每 30 秒提高 1 级，直到最高强度`
- User-facing completion delay: minutes; persisted internally as seconds for compatibility.
- Stage-2 checkbox: `第二关使用独立提醒设置`

- [ ] **Step 1: Add failing UI/source contracts**

Assert the UI no longer exposes “5分钟后突破最大值”, progressive-max sliders, or forced one-shot snooze copy. Assert stage-2 independent checkbox exists and independent controls are hidden unless enabled.

- [ ] **Step 2: Run RED**

- [ ] **Step 3: Simplify first-stage reminder UI**

Keep fixed/progressive; progressive has no max slider and no auto-escalate checkbox. Fixed mode retains its strength controls.

- [ ] **Step 4: Add stage-2 independent section**

Default unchecked. When unchecked, stage 2 inherits stage 1. When checked, expose the same fixed/progressive + alarm mode + fixed strength controls for stage 2.

- [ ] **Step 5: Rename the cooldown UI to completion delay**

Default/edit UI uses minutes and converts to `stageCooldownSeconds`; `0` means no delay. Test UI may retain a clearly labeled seconds field for fast manual testing.

- [ ] **Step 6: Restore configurable snooze count in default settings**

`0` means disabled; positive values are respected by runtime.

- [ ] **Step 7: Run UI/source contracts and full JVM suite**

- [ ] **Step 8: Commit**

```text
feat(alarm): simplify reminder settings and stage two overrides
```

---

### Task 7: README / Spec / Plan / Help convergence

**Files:**
- Modify: `README.md`
- Modify: `docs/specs/alarm.md`
- Modify: `PLAN.md`
- Modify: `docs/使用手册.md` if the shipped behavior changes user instructions

- [ ] **Step 1: Update README product story**

Lead with the dormitory conflict: keeping the phone near the bed reduces disturbance to roommates but makes half-awake dismissal too easy. Explain QR as physical relocation, snooze as quiet-without-cancel, completion delay as optional morning friction, and stage 2 as an optional advanced morning task.

- [ ] **Step 2: Update the normative spec**

Define alert cycle, 30-second progressive ramp, completion delay, stage-2 inheritance/override, configurable snooze, and the distinction between lifecycle state and output mode.

- [ ] **Step 3: Update root PLAN**

Point to this implementation plan and mark only actually verified tasks complete.

- [ ] **Step 4: Sync the user manual / App Help source**

Do not create a second independent Help copy; the app Help remains generated from `docs/使用手册.md`.

- [ ] **Step 5: Commit**

```text
docs(alarm): converge wake flow documentation
```

---

### Task 8: CI, APK and final convergence gate

**Files:**
- Reuse: `.github/workflows/android-build.yml`
- Update/add branch-only contract workflow only if needed during RED/GREEN development.

- [ ] **Step 1: Run all JVM tests**

```text
./gradlew :app:testDebugUnitTest
```

Expected: PASS.

- [ ] **Step 2: Build Debug APK**

```text
./gradlew :app:assembleDebug
```

Expected: PASS and `app/build/outputs/apk/debug/app-debug.apk` exists.

- [ ] **Step 3: Verify branch against latest dev**

Before PR/merge, fetch latest `dev`, compare refs, resolve any new alarm/README/spec conflicts, and rerun tests/build after synchronization.

- [ ] **Step 4: Device verification checklist**

Verify on a real device without uninstalling data: first-stage progressive 30-second ramp, configurable snooze, snooze restart from level 1, single-stage completion delay, immediate and delayed stage-2 entry, independent stage-2 settings, volume-key snooze, and best-effort power-key/screen-off behavior.

- [ ] **Step 5: Open PR to `dev` with exact verification evidence**

Do not claim device/OEM behavior verified unless a real device run was performed.
