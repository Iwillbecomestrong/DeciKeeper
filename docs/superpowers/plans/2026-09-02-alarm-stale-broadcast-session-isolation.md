# Alarm Stale Broadcast Session Isolation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Prevent rejected or stale alarm broadcasts and stale recovery callbacks from stopping, replacing, or clearing the currently active alarm session.

**Architecture:** Treat an accepted alarm session as authoritative runtime state. Rejected trigger broadcasts become side-effect-free at runtime; recovery/safety-net callbacks must match the current active alarm id before restarting vibration; `AlarmVibrationService.onDestroy()` only releases service resources and must not declare the business alarm session finished. Explicit challenge completion remains the authoritative teardown path.

**Tech Stack:** Android/Kotlin, AlarmManager, foreground Service, JUnit source-contract tests, GitHub Actions Windows self-hosted runner.

**Spec:** Root cause confirmed from current `dev`: `AlarmReceiver.cleanupStaleAlarm()` globally stops the vibration service, clears active state, and dismisses alarm window bookkeeping even when the rejected broadcast belongs to another or obsolete alarm.

## Global Constraints

- Base branch is `dev` at `77c82c4e54ec26f97c869185ec94446583b5e50c`.
- Work only on `fix/alarm-stale-broadcast-session-isolation`; do not write directly to `dev` or `main`.
- Keep the change minimal; do not refactor unrelated alarm UI or scheduling code.
- Use TDD: add failing regression tests before production changes.
- A rejected MAIN trigger must never stop the current vibration service, clear active state, or dismiss the current alarm window.
- Recovery and reboot safety-net callbacks may act only when their `alarmId` equals `AlarmScheduler.getActiveAlarmId(context)` and an alarm is active.
- `AlarmVibrationService.onDestroy()` must not clear business-level active alarm state.
- Explicit challenge completion continues to call `AlarmScheduler.clearAlarmActive()` and stop the vibration service.

---

### Task 1: Add regression contracts for stale-trigger isolation

**Files:**
- Create: `app/src/test/java/com/ironwill/app/AlarmSessionIsolationContractTest.kt`
- Test: `app/src/test/java/com/ironwill/app/AlarmSessionIsolationContractTest.kt`

**Interfaces:**
- Consumes: current source of `AlarmReceiver.kt`, `AlarmVibrationService.kt`, `LockAlarmOverlay.kt`.
- Produces: regression assertions that fail on current `dev` and define the intended lifecycle contract.

- [ ] **Step 1: Write the failing tests**

Create tests that read production source and assert:

```kotlin
@Test
fun rejectedTriggerCleanupDoesNotTearDownRunningAlarmSession() {
    val receiver = source("app/src/main/java/com/ironwill/app/receiver/AlarmReceiver.kt")
    val cleanup = receiver.substringAfter("private fun cleanupStaleAlarm").substringBefore("private data class ValidateResult")
    assertFalse(cleanup.contains("AlarmVibrationService.stop(context)"))
    assertFalse(cleanup.contains("AlarmScheduler.clearAlarmActive(context)"))
    assertFalse(cleanup.contains("AlarmWindowManager.dismiss()"))
}
```

Add separate tests requiring recovery and reboot safety-net blocks to compare requested `alarmId` with `AlarmScheduler.getActiveAlarmId(context)`, and requiring `AlarmVibrationService.onDestroy()` not to call `AlarmScheduler.clearAlarmActive(this)`. Preserve an assertion that normal challenge completion in `LockAlarmOverlay.kt` still clears active state and stops vibration.

- [ ] **Step 2: Verify RED**

Open a PR from the task branch to `dev` so the existing workflow runs `testDebugUnitTest`; confirm the new contract test fails against the unmodified production code for the expected assertions.

- [ ] **Step 3: Commit the failing tests**

Commit message:

```text
test(alarm): cover stale broadcast session isolation
```

---

### Task 2: Make rejected trigger cleanup runtime-side-effect-free

**Files:**
- Modify: `app/src/main/java/com/ironwill/app/receiver/AlarmReceiver.kt`
- Test: `app/src/test/java/com/ironwill/app/AlarmSessionIsolationContractTest.kt`

**Interfaces:**
- Consumes: `validateAlarmTrigger()` rejection reason and alarm id.
- Produces: logging-only stale-trigger cleanup that cannot terminate another active alarm session.

- [ ] **Step 1: Implement minimal production change**

Replace the runtime teardown inside `cleanupStaleAlarm()` with diagnostics only. The function may log the rejected id/reason but must not call `AlarmVibrationService.stop`, `AlarmScheduler.clearAlarmActive`, or `AlarmWindowManager.dismiss`.

- [ ] **Step 2: Run the focused regression test**

Expected: stale-trigger cleanup assertions pass.

- [ ] **Step 3: Commit**

```text
fix(alarm): isolate stale trigger cleanup from active session
```

---

### Task 3: Reject stale recovery and safety-net callbacks

**Files:**
- Modify: `app/src/main/java/com/ironwill/app/receiver/AlarmReceiver.kt`
- Test: `app/src/test/java/com/ironwill/app/AlarmSessionIsolationContractTest.kt`

**Interfaces:**
- Consumes: `AlarmScheduler.isAlarmActive(context)` and `AlarmScheduler.getActiveAlarmId(context)`.
- Produces: recovery/safety-net handlers that can restart only the same active alarm id.

- [ ] **Step 1: Implement id match guards**

For both `ACTION_ALARM_RECOVERY` and `ACTION_ALARM_REBOOT_SAFETY_NET`, read `activeAlarmId` and reject unless:

```kotlin
AlarmScheduler.isAlarmActive(context) && activeAlarmId == alarmId
```

Log requested and active ids when rejected.

- [ ] **Step 2: Run regression tests**

Expected: recovery and safety-net contract assertions pass.

- [ ] **Step 3: Commit**

```text
fix(alarm): guard recovery callbacks by active alarm id
```

---

### Task 4: Decouple service destruction from alarm-session teardown

**Files:**
- Modify: `app/src/main/java/com/ironwill/app/service/AlarmVibrationService.kt`
- Test: `app/src/test/java/com/ironwill/app/AlarmSessionIsolationContractTest.kt`

**Interfaces:**
- Consumes: Android Service lifecycle.
- Produces: `onDestroy()` that releases service-local resources without clearing authoritative alarm-session state.

- [ ] **Step 1: Remove business teardown from `onDestroy()`**

Delete the unconditional `AlarmScheduler.clearAlarmActive(this)` call and replace its log with a lifecycle-only message. Keep stopping ringtone/vibration, callbacks, receiver cleanup, and `isRunning=false`.

- [ ] **Step 2: Verify explicit user teardown still exists**

Keep `LockAlarmOverlay.dismissAlarmOverlay()` calling both `AlarmScheduler.clearAlarmActive(ctx)` and `AlarmVibrationService.stop(ctx)`.

- [ ] **Step 3: Run regression tests**

Expected: service-lifecycle and explicit-teardown assertions pass.

- [ ] **Step 4: Commit**

```text
fix(alarm): keep active session across vibration service teardown
```

---

### Task 5: Full CI verification

**Files:**
- No production files beyond Tasks 2-4.

**Interfaces:**
- Consumes: completed task branch.
- Produces: PR CI evidence and debug APK artifact when successful.

- [ ] **Step 1: Run the existing GitHub Actions workflow through the PR**

Workflow commands are:

```text
.\\gradlew.bat --no-daemon testDebugUnitTest
.\\gradlew.bat --no-daemon assembleDebug
```

- [ ] **Step 2: Verify the workflow succeeds**

Expected: `build-debug-apk` succeeds and uploads `IronWill-debug-apk`.

- [ ] **Step 3: Review final diff against `dev`**

Confirm only the plan, regression test, `AlarmReceiver.kt`, and `AlarmVibrationService.kt` changed, with no unrelated formatting churn.
