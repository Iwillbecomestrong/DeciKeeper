# UI Master Alignment Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Apply the approved DeciKeeper HTML master direction to the current `dev` Android UI without shrinking functionality or changing core lock/alarm behavior.

**Architecture:** Keep Kotlin business logic and existing view IDs stable wherever possible. Implement this pass as layout/resource work: first lock screen + rest request, then app selection, then alarm edit/list presentation. Add a source-level XML contract unit test so future visual refactors cannot silently remove the required controls or collapse the two-stage alarm/app-selection structure.

**Tech Stack:** Android XML layouts, Kotlin/JUnit4 unit tests, existing Material/AppCompat widgets.

**Spec:** Conversation-approved UI master decisions on 2026-09-06; source of truth priority is latest user decision > `dev` Kotlin runtime behavior > XML > docs.

## Global Constraints

- Base branch is `dev` at `31be03db84abedc5730e3fcd7c13ef4186777ed5`.
- Do not change quota, borrowing, lock, alarm scheduling, or permission business rules in this pass.
- Preserve every Kotlin-referenced view ID used by the modified layouts.
- Do not centralize permissions into a new settings page; base permissions remain on Home, alarm keepalive stays in Alarm, and accessibility prerequisites stay with accessibility/protection flows.
- Keep alarm two-stage multi-select challenge configuration complete.
- Keep app selection search + left/right columns + selected-state visibility.
- Lock screen action order must read top-to-bottom as return desktop, context action, weekly privilege.
- Lock screen background/cards should be light and calm rather than deep navy/teal.

---

### Task 1: Add UI layout contract tests

**Files:**
- Create: `app/src/test/java/com/decikeeper/app/UiMasterLayoutContractTest.kt`

**Interfaces:**
- Consumes: Android XML layout source files under `app/src/main/res/layout`.
- Produces: regression checks for required IDs, ordering, and complete alarm/app-selection structure.

- [ ] **Step 1: Write failing tests** asserting the lock screen contains `btn_weekly_privilege` after `btn_emergency`, app selection contains search + both list columns, and alarm edit contains two-stage controls and all challenge types.
- [ ] **Step 2: Run `gradlew testDebugUnitTest` and verify RED** because current `dev` lock XML does not contain `btn_weekly_privilege`.
- [ ] **Step 3: Keep tests source-only** so they do not depend on Robolectric or instrumentation.

### Task 2: Align lock screen and rest request UI

**Files:**
- Modify: `app/src/main/res/layout/window_lock_screen.xml`
- Modify: `app/src/main/res/layout/window_rest_request.xml`

**Interfaces:**
- Consumes: existing `LockWindowManager` IDs and three-button presentation logic.
- Produces: light lock surface, centered overlays, full-width three-action stack, visible weekly privilege control, complete rest input surface.

- [ ] **Step 1: Preserve all existing IDs referenced by `LockWindowManager`.**
- [ ] **Step 2: Rebuild the bottom action stack as `btn_back_to_home` → contextual middle action(s) → `btn_weekly_privilege`, all full-width.**
- [ ] **Step 3: Keep `tv_timer` and `tv_sub_status` above quota cards so Kotlin can display restriction/countdown/window-range text in the existing location.**
- [ ] **Step 4: Keep borrow/penalty overlays centered.**
- [ ] **Step 5: Restyle to a light neutral surface with restrained accent/warning colors.**
- [ ] **Step 6: Run layout contract tests.**

### Task 3: Align app selection and alarm configuration UI

**Files:**
- Modify: `app/src/main/res/layout/activity_app_selection.xml`
- Modify: `app/src/main/res/layout/dialog_alarm_edit.xml`
- Modify: `app/src/main/res/layout/fragment_alarm.xml`

**Interfaces:**
- Consumes: existing `AppSelectionActivity` and `AlarmFragment` view IDs/runtime visibility logic.
- Produces: complete two-column selection and complete two-stage alarm editing with lighter visual hierarchy.

- [ ] **Step 1: Keep search and 1:1 left/right app columns with selected list permanently visible.**
- [ ] **Step 2: Preserve segment tools and pending-change card IDs/visibility hooks.**
- [ ] **Step 3: Preserve alarm single/two-stage mode, stage 1 + stage 2 multi-select challenge IDs, challenge-count inputs, day checkboxes, and time picker.**
- [ ] **Step 4: Restyle Alarm home so the runtime-unified default settings entry, keepalive entry, alarm list, add/test actions, and bookmark entry fit the light master visual language.**
- [ ] **Step 5: Run all unit tests and `assembleDebug` through the existing PR CI workflow.**

### Task 4: Review and handoff

**Files:**
- No additional production files unless CI exposes a concrete regression.

- [ ] **Step 1: Review PR diff for accidental business-logic changes.**
- [ ] **Step 2: Confirm CI unit tests and debug APK build succeed.**
- [ ] **Step 3: Leave the work on the feature branch for user visual review; do not merge into `dev` without explicit approval.**
