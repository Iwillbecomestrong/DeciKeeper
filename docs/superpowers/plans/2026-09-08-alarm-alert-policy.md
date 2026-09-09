# Alarm Alert Policy & Wake Flow Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将知止闹钟收敛为“第一关 + 可选完成后延迟 + 可选第二关”的统一流程，并让固定/渐进提醒、第二关继承/独立设置、再响恢复都共享同一套提醒策略。

**Architecture:** 新增纯 Kotlin `AlarmAlertPolicy` 作为提醒强度与配置继承的单一规则源；`AlarmScheduler` 负责持久化和配置解析；`AlarmVibrationService` 只执行当前提醒周期；`LockAlarmOverlay` 负责挑战/延迟/第二关状态推进；`AlarmFragment` 负责设置 UI。渐进计时从整次 service 运行时间中拆出独立 `alertCycleStartTime`，每次“第一次响 / 再响结束 / 第二关开始”都新建提醒周期。

**Tech Stack:** Kotlin, Android SDK, SharedPreferences / Device Protected Storage, JUnit 4 JVM tests, Python source-contract checks, GitHub Actions, Gradle / Java 17.

**Spec:** `docs/specs/alarm.md`

## Global Constraints

- 第一关是核心；第二关默认关闭、只作为可选增强。
- 完成后延迟是独立配置，`0` 表示不延迟；延迟期间保持最低强度，不自行渐进。
- 第二关默认完整继承第一关提醒策略；勾选“第二关使用独立提醒设置”后才保存和使用第二关独立配置。
- 渐进提醒从最低强度 `1` 开始，每 `30` 秒提高 `1` 级，直到 `10` 后保持；不再存在“5 分钟突破”产品逻辑。
- 新提醒周期至少包括：首次响铃、再响结束、第二关开始；渐进只能按 `now - alertCycleStartTime` 计算。
- 再响期间声音与震动均为 `0`，挑战状态和进度保持；再响结束且第一关未完成时开启新的提醒周期。
- 再响时长/次数按“当前闹钟覆盖 → 默认设置”解析；默认值可以是 1 次，但底层不得硬编码只能一次。
- 扫码挑战仍是“扫描任意二维码即可通过”；不得因为本改造重新引入绑定二维码。
- 既有 OEM 恢复、防残留覆盖层、Direct Boot / recovery 防御逻辑不得删除；进程恢复时不能把同一提醒周期错误重置为新的周期。
- Android 代码改动必须通过 `:app:testDebugUnitTest` 与 `:app:assembleDebug`；OEM 电源键/熄屏代理仍需标记真机验证边界。
- 不新增第三方依赖。

---

## File Map

**Create**
- `app/src/main/java/com/zhizhi/app/manager/AlarmAlertPolicy.kt` — 纯 Kotlin 提醒强度、第二关继承、再响覆盖解析。
- `app/src/test/java/com/zhizhi/app/AlarmAlertPolicyTest.kt` — 30 秒阶梯、固定模式、继承/独立、再响覆盖的纯 JVM 测试。

**Modify**
- `app/src/main/java/com/zhizhi/app/manager/AlarmScheduler.kt` — AlarmEntry 新字段、默认值、JSON 兼容、再响覆盖、alert-cycle 持久化。
- `app/src/main/java/com/zhizhi/app/service/AlarmVibrationService.kt` — 按 alert cycle 执行固定/渐进提醒；移除 5 分钟突破行为；第二关策略切换。
- `app/src/main/java/com/zhizhi/app/ui/LockAlarmOverlay.kt` — 统一第一关完成、完成后延迟、第二关启动、再响次数与新周期恢复。
- `app/src/main/java/com/zhizhi/app/ui/LockWindowManager.kt` — 测试闹钟配置扩展，暴露当前阶段测试提醒策略。
- `app/src/main/java/com/zhizhi/app/ui/AlarmFragment.kt` — 默认/单闹钟/测试配置 UI；第二关独立提醒设置；再响覆盖；移除突破设置。
- `app/src/main/res/layout/dialog_alarm_default_settings.xml` — 默认配置文案与第二关独立设置控件。
- `app/src/main/res/layout/dialog_alarm_edit.xml` — 完成后延迟与单闹钟再响覆盖入口。
- `app/src/main/res/layout/dialog_test_alarm.xml` — 测试闹钟与真实配置保持一致。
- `app/src/test/java/com/zhizhi/app/AlarmChallengeTwoStageFlowTest.kt` — 完成后延迟与第二关入口回归。
- `app/src/test/java/com/zhizhi/app/AlarmProductionMigrationTest.kt` — 旧 JSON / 旧渐进字段兼容回归。
- `scripts/test_alarm_snooze_contract.py` — 从“强制一次”改为“按解析配置”；增加再响结束重置提醒周期契约。
- `.github/workflows/alarm-snooze-contract.yml` — 泛化为 `ai/alarm-*` 分支的闹钟契约 + JVM + APK 验证。
- `README.md` — 宿舍起点、再响、完成后延迟、可选第二关的用户级说明。
- `SPEC.md` — 起床系统根摘要同步 scoped spec，不复制实现细节。
- `PLAN.md` — 把本轮闹钟收敛列为当前执行路线并在完成后勾选。
- `docs/使用手册.md` — 面向用户同步设置方式；App Help 继续由同源资源生成。

---

### Task 1: Add a Pure Alarm Alert Policy Seam

**Files:**
- Create: `app/src/main/java/com/zhizhi/app/manager/AlarmAlertPolicy.kt`
- Create: `app/src/test/java/com/zhizhi/app/AlarmAlertPolicyTest.kt`

**Interfaces:**
- Produces:
  - `data class AlarmAlertConfig(...)`
  - `data class AlarmSnoozeConfig(val minutes: Int, val maxUses: Int)`
  - `object AlarmAlertPolicy`
  - `const val PROGRESSIVE_STEP_MS = 30_000L`
  - `fun progressiveLevel(elapsedMs: Long): Int`
  - `fun level(mode: AlarmScheduler.VibrationMode, fixedLevel: Int, elapsedMs: Long): Int`
  - `fun resolveStage2(stage1: AlarmAlertConfig, useIndependent: Boolean, stage2Override: AlarmAlertConfig?): AlarmAlertConfig`
  - `fun resolveSnooze(defaultConfig: AlarmSnoozeConfig, minutesOverride: Int?, maxUsesOverride: Int?): AlarmSnoozeConfig`

- [ ] **Step 1: Write failing policy tests**

```kotlin
class AlarmAlertPolicyTest {
    @Test fun progressiveStartsAtOneAndStepsEveryThirtySeconds() {
        assertEquals(1, AlarmAlertPolicy.progressiveLevel(0))
        assertEquals(1, AlarmAlertPolicy.progressiveLevel(29_999))
        assertEquals(2, AlarmAlertPolicy.progressiveLevel(30_000))
        assertEquals(9, AlarmAlertPolicy.progressiveLevel(239_999))
        assertEquals(10, AlarmAlertPolicy.progressiveLevel(270_000))
        assertEquals(10, AlarmAlertPolicy.progressiveLevel(999_999))
    }

    @Test fun fixedModeNeverChangesWithElapsedTime() {
        assertEquals(
            6,
            AlarmAlertPolicy.level(
                AlarmScheduler.VibrationMode.FIXED,
                fixedLevel = 6,
                elapsedMs = 900_000
            )
        )
    }
}
```

Add focused tests for stage-2 inheritance/independent override and field-by-field snooze fallback.

- [ ] **Step 2: Run the focused test and verify RED**

```bash
./gradlew :app:testDebugUnitTest --tests "com.zhizhi.app.AlarmAlertPolicyTest" --stacktrace
```

Expected: FAIL because `AlarmAlertPolicy` / config types do not exist.

- [ ] **Step 3: Write the minimal pure policy**

```kotlin
data class AlarmAlertConfig(
    val alarmMode: AlarmScheduler.AlarmMode,
    val vibrationMode: AlarmScheduler.VibrationMode,
    val vibrationIntensity: Int,
    val ringVolume: Int,
    val ringtoneUri: String?
)

data class AlarmSnoozeConfig(
    val minutes: Int,
    val maxUses: Int
)

object AlarmAlertPolicy {
    const val PROGRESSIVE_STEP_MS = 30_000L

    fun progressiveLevel(elapsedMs: Long): Int =
        (1 + (elapsedMs.coerceAtLeast(0L) / PROGRESSIVE_STEP_MS).toInt())
            .coerceIn(1, 10)

    fun level(
        mode: AlarmScheduler.VibrationMode,
        fixedLevel: Int,
        elapsedMs: Long
    ): Int = when (mode) {
        AlarmScheduler.VibrationMode.PROGRESSIVE -> progressiveLevel(elapsedMs)
        AlarmScheduler.VibrationMode.FIXED -> fixedLevel.coerceIn(1, 10)
    }

    fun resolveStage2(
        stage1: AlarmAlertConfig,
        useIndependent: Boolean,
        stage2Override: AlarmAlertConfig?
    ): AlarmAlertConfig =
        if (useIndependent && stage2Override != null) stage2Override else stage1

    fun resolveSnooze(
        defaultConfig: AlarmSnoozeConfig,
        minutesOverride: Int?,
        maxUsesOverride: Int?
    ): AlarmSnoozeConfig = AlarmSnoozeConfig(
        minutes = (minutesOverride ?: defaultConfig.minutes).coerceAtLeast(1),
        maxUses = (maxUsesOverride ?: defaultConfig.maxUses).coerceAtLeast(0)
    )
}
```

- [ ] **Step 4: Run the focused test and verify GREEN**

Run the same Gradle test command. Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/zhizhi/app/manager/AlarmAlertPolicy.kt \
        app/src/test/java/com/zhizhi/app/AlarmAlertPolicyTest.kt
git commit -m "feat(alarm): add unified alert policy"
```

---

### Task 2: Persist Stage-2 Alert Overrides, Snooze Overrides, and Alert-Cycle Time

**Files:**
- Modify: `app/src/main/java/com/zhizhi/app/manager/AlarmScheduler.kt`
- Modify: `app/src/test/java/com/zhizhi/app/AlarmProductionMigrationTest.kt`

**Interfaces:**
- Consumes: `AlarmAlertConfig`, `AlarmSnoozeConfig`, `AlarmAlertPolicy.resolveStage2`, `AlarmAlertPolicy.resolveSnooze`.
- Produces:
  - `AlarmEntry.stage2UseIndependentAlert: Boolean`
  - nullable stage-2 override fields for alarm mode, vibration mode, fixed vibration, fixed ring volume, ringtone
  - nullable per-alarm snooze overrides
  - `fun resolveStageAlertConfig(context: Context, alarmId: Int, stage: Int): AlarmAlertConfig`
  - `fun resolveSnoozeConfig(context: Context, alarmId: Int): AlarmSnoozeConfig`
  - `fun saveAlertCycleStartTime(context: Context, timeMs: Long)`
  - `fun getAlertCycleStartTime(context: Context): Long`
  - `fun clearAlertCycleStartTime(context: Context)`

- [ ] **Step 1: Add migration tests before production changes**

Cover: old JSON with no new fields, legacy `autoEscalate=true`, stage-2 override round-trip, global snooze fallback, partial snooze override, and old `stageCooldownSeconds` compatibility.

- [ ] **Step 2: Run migration tests and verify RED**

```bash
./gradlew :app:testDebugUnitTest --tests "com.zhizhi.app.AlarmProductionMigrationTest" --stacktrace
```

- [ ] **Step 3: Extend `AlarmEntry` without destructive migration**

```kotlin
val stage2UseIndependentAlert: Boolean = false,
val stage2AlarmMode: AlarmMode? = null,
val stage2VibrationMode: VibrationMode? = null,
val stage2VibrationIntensity: Int? = null,
val stage2RingVolume: Int? = null,
val stage2RingtoneUri: String? = null,
val snoozeMinutesOverride: Int? = null,
val snoozeMaxCountOverride: Int? = null,
```

Keep `stageCooldownSeconds` as the persisted compatibility field for this release, but change its product/UI meaning to “完成后延迟”.

- [ ] **Step 4: Add default + per-alarm resolution**

Use global prefs when overrides are `null`. Fresh-install default max snooze count becomes `1`; an existing stored preference remains authoritative. `getRemainingSnoozeCount` and `recordSnoozeUsed` must use resolved max uses for the current alarm.

- [ ] **Step 5: Persist alert-cycle start in Device Protected Storage**

Add a key distinct from `KEY_VIBRATION_START_TIME`; clear it in `clearAlarmActive()`. Same-session recovery reuses it instead of starting a new cycle.

- [ ] **Step 6: Run migration and scheduler tests**

```bash
./gradlew :app:testDebugUnitTest --tests "com.zhizhi.app.AlarmProductionMigrationTest" --stacktrace
./gradlew :app:testDebugUnitTest --tests "com.zhizhi.app.AlarmScheduler*" --stacktrace
```

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/zhizhi/app/manager/AlarmScheduler.kt \
        app/src/test/java/com/zhizhi/app/AlarmProductionMigrationTest.kt
git commit -m "feat(alarm): persist alert and snooze overrides"
```

---

### Task 3: Replace Service-Time Escalation with Alert-Cycle Escalation

**Files:**
- Modify: `app/src/main/java/com/zhizhi/app/service/AlarmVibrationService.kt`
- Modify: `app/src/test/java/com/zhizhi/app/AlarmAlertPolicyTest.kt`

**Interfaces:**
- Consumes: `AlarmScheduler.resolveStageAlertConfig`, `AlarmAlertPolicy.level`, persisted alert-cycle time.
- Produces:
  - `ACTION_RESTART_ALERT_CYCLE`
  - `fun restartAlertCycle(context: Context, alarmId: Int)`
  - service-local `beginAlertCycle(nowMs: Long)`.

- [ ] **Step 1: Extend tests for cycle reset semantics**

Model first ring, snooze silence, snooze resume, and stage-2 start. Every new cycle must begin at level 1 in progressive mode.

- [ ] **Step 2: Verify current source is RED**

Current code still uses `serviceStartTime`, `/ 10`, and `autoEscalate`; the new contract must fail before implementation.

- [ ] **Step 3: Split session time from alert-cycle time**

Keep `serviceStartTime` only for service/session diagnostics if still needed. Add `alertCycleStartTime` loaded from `AlarmScheduler.getAlertCycleStartTime(this)`; initialize only when no same-session value exists.

- [ ] **Step 4: Resolve the active stage’s alert config**

Stage 1 uses normal alarm fields. Stage 2 uses the stage-2 resolver when `isFirstStagePassed()` is true.

- [ ] **Step 5: Remove the old escalation formulas**

Delete runtime use of:

```kotlin
elapsedSec / 10
elapsedSec > 300
autoEscalate
progressiveMaxIntensity
progressiveMaxRingVolume
```

Use:

```kotlin
val elapsedMs = System.currentTimeMillis() - alertCycleStartTime
val level = AlarmAlertPolicy.progressiveLevel(elapsedMs)
```

for both vibration and ring in progressive mode.

- [ ] **Step 6: Snooze expiry starts a fresh alert cycle**

When the same first-stage session is still active: clear silent state, `beginAlertCycle(now)`, restart output, and keep challenge state unchanged.

- [ ] **Step 7: Add a stage-2 restart action**

`restartAlertCycle(context, alarmId)` validates the same active session, resets the cycle timestamp, resolves stage-2 policy, and starts output.

- [ ] **Step 8: Verify focused tests**

```bash
./gradlew :app:testDebugUnitTest --tests "com.zhizhi.app.AlarmAlertPolicyTest" --stacktrace
```

- [ ] **Step 9: Commit**

```bash
git add app/src/main/java/com/zhizhi/app/service/AlarmVibrationService.kt \
        app/src/test/java/com/zhizhi/app/AlarmAlertPolicyTest.kt
git commit -m "refactor(alarm): reset progressive alerts per cycle"
```

---

### Task 4: Unify Stage-1 Completion, Post-Stage Delay, and Stage-2 Start

**Files:**
- Modify: `app/src/main/java/com/zhizhi/app/ui/LockAlarmOverlay.kt`
- Modify: `app/src/test/java/com/zhizhi/app/AlarmChallengeTwoStageFlowTest.kt`

**Interfaces:**
- Consumes: `AlarmVibrationService.attenuate`, `AlarmVibrationService.restartAlertCycle`.
- Produces focused helpers:
  - `completeStageOne(ctx: Context)`
  - `enterPostStageDelay(ctx: Context)`
  - `finishPostStageDelay(ctx: Context)`
  - `startStageTwo(ctx: Context)`
  - `showFinalReviewAndClose(ctx: Context)`

- [ ] **Step 1: Write flow tests and verify RED**

Cover single-stage delay `0`, single-stage delay `>0`, two-stage delay `0`, two-stage delay `>0`, fresh stage-2 alert cycle, QR/answer shared path, and delay recovery after overlay recreation.

- [ ] **Step 2: Remove duplicated Stage-1 transition code**

Both `onAnyChallengePassed()` and `onScanChallengePassed()` route first-stage success into `completeStageOne(ctx)`.

- [ ] **Step 3: Generalize cooldown into post-stage delay**

The old timer is no longer gated by `isTwoStageUnlock`. During delay all challenge cards and close/re-snooze actions are unavailable, output stays attenuated, and copy says “完成后延迟中”.

At expiry:
- second stage enabled → `startStageTwo`;
- otherwise → existing review/close completion state.

Actual service teardown remains on the existing explicit close path so review cards still work.

- [ ] **Step 4: Start stage 2 with a fresh alert cycle**

Prepare stage-2 questions/UI, then call:

```kotlin
AlarmVibrationService.restartAlertCycle(ctx, alarmId)
```

- [ ] **Step 5: Run focused tests**

```bash
./gradlew :app:testDebugUnitTest --tests "com.zhizhi.app.AlarmChallengeTwoStageFlowTest" --stacktrace
```

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/zhizhi/app/ui/LockAlarmOverlay.kt \
        app/src/test/java/com/zhizhi/app/AlarmChallengeTwoStageFlowTest.kt
git commit -m "feat(alarm): unify delay and optional second stage"
```

---

### Task 5: Make Snooze Configuration-Driven Instead of Hard-Coded One-Shot

**Files:**
- Modify: `app/src/main/java/com/zhizhi/app/ui/LockAlarmOverlay.kt`
- Modify: `app/src/main/java/com/zhizhi/app/manager/AlarmScheduler.kt`
- Modify: `scripts/test_alarm_snooze_contract.py`

**Interfaces:**
- Consumes: `AlarmScheduler.resolveSnoozeConfig`, `getSnoozeUsedCount`, `recordSnoozeUsed`.

- [ ] **Step 1: Rewrite the source contract and verify RED**

Replace assertions that demand a forced one-shot with assertions for resolved max uses, shared hardware/button eligibility, challenge preservation, and fresh-cycle restart after snooze.

```bash
python3 scripts/test_alarm_snooze_contract.py
```

Expected: FAIL on current hard-coded one-shot behavior.

- [ ] **Step 2: Resolve snooze when overlay initializes**

```kotlin
val snooze = AlarmScheduler.resolveSnoozeConfig(context, alarmId)
alarmSnoozeMinutes = snooze.minutes
alarmRemainingSnoozeCount =
    (snooze.maxUses - AlarmScheduler.getSnoozeUsedCount(context, alarmId))
        .coerceAtLeast(0)
```

- [ ] **Step 3: Consume one configured use per request**

After `recordSnoozeUsed`, decrement remaining count by one instead of forcing it to zero.

- [ ] **Step 4: Keep hardware paths configuration-aware**

Volume keys and best-effort screen-off/power proxy continue to call the same snooze request path and fail cleanly at maxUses `0` or exhaustion.

- [ ] **Step 5: Verify source contract**

```bash
python3 scripts/test_alarm_snooze_contract.py
```

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/zhizhi/app/ui/LockAlarmOverlay.kt \
        app/src/main/java/com/zhizhi/app/manager/AlarmScheduler.kt \
        scripts/test_alarm_snooze_contract.py
git commit -m "fix(alarm): honor configured snooze limits"
```

---

### Task 6: Update Default, Per-Alarm, and Test Configuration UI

**Files:**
- Modify: `app/src/main/java/com/zhizhi/app/ui/AlarmFragment.kt`
- Modify: `app/src/main/java/com/zhizhi/app/ui/LockWindowManager.kt`
- Modify: `app/src/main/res/layout/dialog_alarm_default_settings.xml`
- Modify: `app/src/main/res/layout/dialog_alarm_edit.xml`
- Modify: `app/src/main/res/layout/dialog_test_alarm.xml`
- Test: add a focused alarm-settings contract test rather than mixing unrelated assertions into theme tests.

**Interfaces:**
- Consumes: new `AlarmEntry` fields and default-setting getters/setters.

- [ ] **Step 1: Add UI/source contract assertions and verify RED**

Require:
- “完成后延迟” + `0=不延迟`;
- delay visible even when second stage is off;
- “第二关使用独立提醒设置”;
- no active “5分钟后突破/自动升级” setting;
- progressive hint “从 1 开始，每 30 秒 +1，直到 10”;
- per-alarm “使用默认再响设置” plus override duration/count.

- [ ] **Step 2: Simplify stage-1 alert controls**

Progressive mode shows only the 30-second rule; remove/hide progressive max sliders and auto-escalate control. Fixed mode keeps fixed vibration/ring sliders as applicable.

- [ ] **Step 3: Add second-stage independent alert controls**

Default:

```text
第二关提醒：沿用第一关
[ ] 第二关使用独立提醒设置
```

When enabled, reveal the same simplified alert policy controls. When disabled, save stage-2 override fields as `null`; do not keep a shadow copy synchronized with stage 1.

- [ ] **Step 4: Make completion delay independent of stage-2 visibility**

Rename the old cooldown UI and keep it visible for single-stage alarms too.

- [ ] **Step 5: Restore configurable default snooze count and add per-alarm override**

Default dialog saves entered duration/count. Per-alarm edit uses a checked-by-default “使用默认再响设置”; unchecked reveals override values.

- [ ] **Step 6: Extend test-alarm configuration**

Test alarms can exercise stage-1 policy, delay, optional stage 2, and stage-2 independent policy without mutating real alarm snooze/session data.

- [ ] **Step 7: Run alarm tests**

```bash
./gradlew :app:testDebugUnitTest --tests "com.zhizhi.app.Alarm*" --stacktrace
```

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/com/zhizhi/app/ui/AlarmFragment.kt \
        app/src/main/java/com/zhizhi/app/ui/LockWindowManager.kt \
        app/src/main/res/layout/dialog_alarm_default_settings.xml \
        app/src/main/res/layout/dialog_alarm_edit.xml \
        app/src/main/res/layout/dialog_test_alarm.xml \
        app/src/test/java/com/zhizhi/app
git commit -m "feat(alarm): expose unified stage alert settings"
```

---

### Task 7: Synchronize README, Root SPEC, PLAN, and User Manual

**Files:**
- Modify: `README.md`
- Modify: `SPEC.md`
- Modify: `PLAN.md`
- Modify: `docs/specs/alarm.md`
- Modify: `docs/使用手册.md`

**Interfaces:**
- Consumes: final implemented behavior from Tasks 1–6.

- [ ] **Step 1: Rewrite the README alarm section around the actual problem**

Use the宿舍 origin, QR/题目 distinction, “再响让环境安静但挑战继续”, optional completion delay, and optional stage 2. Do not market second stage as a default need.

- [ ] **Step 2: Update root `SPEC.md` only as a summary**

Point to `docs/specs/alarm.md`; summarize first stage, optional delay, optional second stage, and configuration-driven snooze without duplicating detailed state rules.

- [ ] **Step 3: Mark scoped spec convergence**

Remove implementation-gap items that are now closed. Keep real limits such as power-key best-effort and OEM/process recovery caveats.

- [ ] **Step 4: Update root `PLAN.md`**

Record the new 30-second policy, alert-cycle clock, stage-2 inherit/override, configuration-driven snooze, independent completion delay, and docs/help convergence. Remove stale “current branch” claims after merge.

- [ ] **Step 5: Update `docs/使用手册.md`**

Explain hostel QR + snooze, delay `0` vs 20/30 minutes, optional second-stage exam questions, and progressive restart from 1 after snooze/stage 2. App Help remains generated from this source.

- [ ] **Step 6: Run affected tests/contracts**

```bash
python3 scripts/test_alarm_snooze_contract.py
./gradlew :app:testDebugUnitTest --tests "com.zhizhi.app.Alarm*" --stacktrace
```

- [ ] **Step 7: Commit**

```bash
git add README.md SPEC.md PLAN.md docs/specs/alarm.md docs/使用手册.md
git commit -m "docs(alarm): align wake flow and alert policy"
```

---

### Task 8: GitHub CI, APK, Latest-dev Sync, and PR Convergence Gate

**Files:**
- Modify: `.github/workflows/alarm-snooze-contract.yml`

**Interfaces:**
- Produces reproducible GitHub CI evidence and a Debug APK artifact for the exact branch head.

- [ ] **Step 1: Generalize the alarm workflow trigger**

```yaml
on:
  push:
    branches:
      - 'ai/alarm-*'
```

Keep Python contract, Java 17, full unit tests, debug APK build, and artifact upload.

- [ ] **Step 2: Push and verify GitHub-hosted alarm workflow GREEN**

Record exact run id + commit SHA. On failure, fetch logs, fix, and rerun before proceeding.

- [ ] **Step 3: Open/refresh a Draft PR into `dev`**

PR body includes behavior summary, migration compatibility, snooze semantics, stage-2 inheritance/override, tests, and remaining OEM/manual verification. This triggers existing self-hosted Windows `android-build.yml`.

- [ ] **Step 4: Verify self-hosted Windows CI GREEN**

Require all unit tests, debug APK build, and artifact upload. Never report green while queued/in-progress.

- [ ] **Step 5: Sync latest `dev` before merge**

If `dev` advanced, bring it into the task branch, resolve conflicts by product semantics, then rerun both CI paths.

- [ ] **Step 6: Identify/download the exact APK artifact**

Report branch, exact HEAD SHA, workflow run id, and artifact name. APK must match the verified head.

- [ ] **Step 7: Final Convergence Review**

Check README, root SPEC, scoped alarm spec, PLAN, user manual, production code, tests, and source contract. Explicitly note power-key best-effort and OEM/reboot device-verification boundaries.

- [ ] **Step 8: Mark PR ready for review**

Do not auto-merge unless the user explicitly asks to merge after final green evidence.
