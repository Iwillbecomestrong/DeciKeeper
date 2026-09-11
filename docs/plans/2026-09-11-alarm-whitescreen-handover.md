# 交接：闹钟扫码成功后白屏且无法退出

> **给接手的 AI**：本文是自包含的交接说明。请先完整读完再看代码。任务尚未完成——已修复了两条独立缺陷，但**核心白屏仍未解决**，且我已定位到新的可疑点。

---

## 1. 一句话任务

**修复「锁屏闹钟 → 长按电源键 → 扫码成功 → 白屏且无法退出」的死锁。** 扫码本身是成功的、逻辑也走完了，但界面上不显示「关闭闹钟」与延迟倒计时，闹钟震动持续，用户只能靠 force-stop 脱困。

---

## 2. 环境与仓库

| 项 | 值 |
| --- | --- |
| 主仓库 | `G:/IronWIl0329/IronWill-OpenSource`（分支 `dev`，有 64 个未提交改动，**不要动**） |
| **本次工作 worktree** | `G:/IronWIl0329/IronWill-alarm-overlay` |
| 分支 | `fix/alarm-overlay-detach`（基线 `dev` = `f8a1ae0`） |
| 已有提交 | `212c467` → `fbce174` → `3e9ad33` → `ef04182` |
| 真机 | vivo V2425A (PD2425)，Android 13+，**无线 adb** |
| 设备地址 | `192.168.1.100:41579`（会变，用户会提供；另有 mDNS 重复条目，命令务必带 `-s`） |
| 工程约定 | **必读** `AGENT_CORE.md`（业务红线）、`SPEC.md`、`docs/specs/alarm.md` |

### 构建

```bash
# worktree 里没有 local.properties，需从主仓库复制
cp ../IronWill-OpenSource/local.properties .
./gradlew.bat --console=plain :app:testDebugUnitTest :app:assembleDebug
# 基线：1186 例 / 0 失败 / 11 跳过
```

### 安装（⚠️ 踩过的坑）

**不要用 `adb install`**——走无线时流式安装会**永久挂起**（实测卡住数分钟不返回，包根本没装上）。
改用「先 push 再本地装」，1 秒完成：

```bash
D=192.168.1.100:41579
adb -s $D push app/build/outputs/apk/debug/app-debug.apk /data/local/tmp/dk.apk
adb -s $D shell pm install -r -d /data/local/tmp/dk.apk   # 输出 Success
adb -s $D shell rm -f /data/local/tmp/dk.apk
adb -s $D shell dumpsys package com.decikeeper.app | grep lastUpdateTime   # 核对时间戳
```

**验证安装是否真的生效**：必须核对 `lastUpdateTime`。我第一次以为装上了，实际 `lastUpdateTime` 还是旧的。

### 抓日志（⚠️ 另一个坑）

`adb logcat -f <path>` 在本机被沙箱拒绝（报 `Read-only file system`）。必须用管道：

```bash
adb -s $D logcat -v threadtime 2>&1 | ForEach-Object { $_ | Out-File -FilePath 'G:\IronWIl0329\.alarm-verify3.log' -Encoding utf8 -Append }
```

**不要 `adb logcat -c`**——我第一次清了缓冲，把扫码成功那几秒的关键证据冲掉了。

---

## 3. 复现步骤

前置：**无障碍必须开启**（拦截逻辑在 `CoreService.onAccessibilityEventInternal`，无障碍关闭时收不到事件，整条路径不执行）。

1. 设一个 1–2 分钟后的闹钟，**锁屏**放置
2. 闹钟响、挑战界面出现
3. **长按电源键**，等关机菜单真的出现（这一步是复现关键）
4. 让菜单消失，回到挑战页
5. **点扫码 → 扫任意二维码**（扫码会成功）
6. **观察**：应出现「关闭闹钟」+ 延迟倒计时。**实际：白屏，震动持续**

脱困：`adb -s $D shell am force-stop com.decikeeper.app`

---

## 4. 已确认的证据链（两次真机复现）

### 4.1 第二次复现（装了 `212c467`+`fbce174`+`3e9ad33`）

```text
21:36:00.688  showAlarmOverlay: ENTER alarmId=1, useInline=true
21:36:00.715  showAlarmOverlay: inline mode (by caller request), isCurrentOverlayInline=true
21:36:10.704  AlarmActivity: launchQrScan: hasPermission=true      ← setContentView 替换内容视图
21:36:30.840  AlarmActivity: Scan success
21:36:30.842  [ALARM_CHALLENGE_RESULT] challengeId=QR-1 result=FINAL_CORRECT progress=1/1
21:36:30.843  applyScanCutoutLayers: SCAN_CUTOUT_DISABLED
21:36:30.894  Alarm attenuated: volume=0.02f                       ← 说明 onAnyChallengePassed() 执行完整
21:36:30.9+   ★ 再无任何 UI 日志
之后          Vibration keep-alive 每 3 秒续期，直到 force-stop
```

窗口状态（`dumpsys window`）：**无任何覆盖层窗口**，只剩 `AlarmActivity` + `MainActivity`；`mCurrentFocus=null`。
状态位：`alarm_active=true`、`active_challenge_completed=true`、`active_first_stage_passed=false`、`active_alarm_attenuated=true`。

### 4.2 第三次复现（装了 `ef04182`，即当前 HEAD）

**标记统计（关键）**：

```text
SCAN_CUTOUT_ENABLED                5
SCAN_CUTOUT_DISABLED               2      ← ★ 不配对：开孔模式被开了 5 次只关了 2 次
OVERLAY_ATTACH_ROLLED_BACK         0
OVERLAY_DETACHED_NEEDS_REBUILD     0
COMPLETED_STATE_DETACHED_REATTACH  0      ← ★ 新增的守卫仍然一次都没触发
COMPLETED_STATE_ATTACH_FAILED      0
REASSERT_VIEW_DETACHED             0
SWITCHED_TO_WINDOW_MODE            0      ← ★ 新增的窗口提升也没执行
voice_assistant                   54      ← C 生效
Scan success                       2
```

流程断点与 4.1 完全一致：`Scan success` → `SCAN_CUTOUT_DISABLED` → `attenuate` → 无 UI 日志。

---

## 5. 已做的改动与实测效果

| 提交 | 内容 | 实测 |
| --- | --- | --- |
| `212c467` | A：原子重挂原语 `attachAlarmViewAtomically`（失败回滚、三种结果留痕）+ 完成态渲染守卫 + reassert 去抖 | ❌ **未解决白屏** |
| `fbce174` | C：把 vivo 语音助手加入拦截（`SystemDialogDetectionPolicy.kt` 纯函数 + 限流 BACK） | ✅ **生效**（`voice_assistant` 命中 54 次） |
| `3e9ad33` | B'：扫码改为「不透明挡板 + 中间取景开孔」，使覆盖层全程保持 `alpha=1.0` 且可触摸 | ✅ **生效**（关机菜单不再出现） |
| `ef04182` | 判据由 `parent != null` 改为 `isAttachedToWindow` + `startScanMode` 里提升为独立窗口 | ❌ **未解决白屏** |

**结论：B' 与 C 目标达成；A 连续两次没打中。**

---

## 6. 未解问题与线索（接手重点）

### 6.1 为什么 `isAttachedToWindow` 判据仍判为"已附着"？

`resolveRenderableAlarmView()` 用 `current?.isAttachedToWindow == true` 判定，若为 true 则直接返回、不打任何日志（这也解释了 `COMPLETED_STATE_DETACHED_REATTACH` 为 0）。

而 `dumpsys` 显示**没有任何覆盖层窗口**。两者矛盾，说明：
- 要么 `mAlarmView` 确实附着在**某个**窗口上（例如仍挂在 AlarmActivity 的内容视图里，而那个内容视图是当前窗口），白屏另有原因；
- 要么 `LockWindowManager.getAlarmView()` 拿到的不是屏幕上那个视图。

**建议第一步**：在 `resolveRenderableAlarmView()` 里无条件打印诊断——`view.isAttachedToWindow`、`view.windowToken`、`view.parent?.javaClass`、`view.visibility`、`view.width/height`、`LockWindowManager.isCurrentOverlayInline()`。用真机跑一次就能定论。

### 6.2 强烈怀疑：**扫码取景开孔模式泄漏**（新线索，本次才出现）

`SCAN_CUTOUT_ENABLED=5` 但 `SCAN_CUTOUT_DISABLED=2`。

`applyScanCutoutLayers(view, true)` 会做四件事：根容器背景转透明、扫码容器背景转透明、**把 `root_content_layout` 设为 `GONE`**、显示 `layout_alarm_scan`。

**`root_content_layout` 是挑战内容的容器。若它停在 `GONE` 状态，挑战界面与完成态都不会显示 —— 这本身就是一块「白屏」。**

也就是说：**白屏可能根本不是"脱窗"，而是 B'（我引入的开孔模式）没有正确还原**。这条线的优先级应高于 6.1。

需要查：
- 谁在什么路径下多次调用 `setAlarmOverlayPassthrough(true)` 而没有配对调用 false？
  （已知调用点：`AlarmActivity.kt` L157/L200/L218/L495、`LockAlarmOverlay.kt` 在 reassert 的 2 秒恢复 Runnable 里也有一处）
- `reassertAlarmOverlayOverSystemDialog` 的 2 秒恢复 Runnable 会在扫码结束后再次调用 `setAlarmOverlayPassthrough(true)`，**可能在完成态之后又把内容设为 GONE**！这条路径极可疑。
- `savedRootBackground` / `savedScanBackground` 的单例保存策略在"多次 enable 只 disable 一次"时不配对。

### 6.3 可疑：`ensureAlarmOverlayInWindowMode()` 静默早退

```kotlin
fun ensureAlarmOverlayInWindowMode(): Boolean {
    if (!LockWindowManager.isCurrentOverlayInline()) return true   // ← 静默返回，无日志
```

而 `isCurrentOverlayInline()` = `mAlarmView != null && mAlarmView.layoutParams !is WindowManager.LayoutParams`（`LockWindowManager.kt:555`）——**只看 layoutParams 类型**。脱窗后它照样可能返回 true/false 而与真实状态不符。建议给早退分支补日志。

---

## 7. 关键代码位置（当前分支行号）

| 位置 | 说明 |
| --- | --- |
| `app/src/main/java/com/decikeeper/app/manager/AlarmOverlayAttachState.kt` | 新增的纯状态机（`classifyOverlayAttachState` / `resolveDetachedRecovery` / `resolveAttachAttemptOutcome`） |
| `app/src/main/java/com/decikeeper/app/manager/SystemDialogDetectionPolicy.kt` | 新增的系统弹层识别纯函数（含语音助手） |
| `LockAlarmOverlay.kt` `attachAlarmViewAtomically()` | 原子重挂原语（A 的核心） |
| `LockAlarmOverlay.kt` `resolveRenderableAlarmView()` | 完成态渲染守卫（**重点排查对象**） |
| `LockAlarmOverlay.kt` `setAlarmOverlayPassthrough()` / `applyScanCutoutLayers()` | B' 的取景开孔（**重点排查对象**） |
| `LockAlarmOverlay.kt` `showAlarmOverlayIfHidden()` | inline 模式曾有短路 return，已加 `isAttachedToWindow` 条件 |
| `LockAlarmOverlay.kt` `onAnyChallengePassed()` / `syncChallengeUiByStage()` | 完成态渲染入口 |
| `AlarmActivity.kt` `startScanMode()` / `finishScanAndRestoreOverlay()` | 扫码进出（`setContentView` 替换内容视图） |
| `CoreService.kt` :880 附近 | 系统弹层拦截块（关机菜单 + 语音助手） |
| `LockWindowManager.kt` :555 | `isCurrentOverlayInline()`（判据只看 layoutParams 类型） |

### 已有测试（1186 例全绿，改动时勿破坏）

- `AlarmOverlayAttachStateTest`（9）纯函数
- `AlarmOverlayAttachContractTest`（9）源文本契约：原子挂载、完成态守卫、去抖、脱窗判据必须是 `isAttachedToWindow`、`startScanMode` 提升早于 `setContentView`
- `AlarmScanCutoutContractTest`（5）B' 结构契约
- `SystemDialogDetectionPolicyTest`（9）识别策略

---

## 8. 红线与约束

1. **不得 `adb uninstall`**，会清空用户真实规则/题库/历史数据（`AGENT_CORE.md` §3.7）。覆盖安装用 `pm install -r -d`。
2. **B' 与 C 已生效，不要回退**。若判定 B' 是白屏元凶，则**只回退 `3e9ad33`**（`git revert 3e9ad33`），保留 A 与 C，并复测确认白屏是否消失——这是验证 6.2 假设最快的手段。
3. **不要改产品口径**：防关机拦截时机、挑战流程、额度规则都不在本次范围。
4. `AGENT_CORE.md` §3.5：`docs/history/issue_tracker.md` 中带真机根因的防御性逻辑，**未理解证据链前不得删除或"简化"**。特别注意 `issue_tracker.md` **05-27 行**记录了「移除覆盖层以让用户使用相机」这一取舍，`LockAlarmOverlay.kt` 中相关注释也标了 `[DO NOT DELETE]`。
5. 覆盖层窗口**渲染不了 SurfaceView**（`DecoratedBarcodeView` 基于 SurfaceView），这是 `startAlarmInlineScan` 从未接线的原因，写在 `handleAlarmScanClick` 的 KDoc 里——**不要试图把扫码器搬进覆盖层**。
6. 提交信息用中文，遵循 `type(scope): 简述`。

---

## 9. 建议的下一步顺序

1. **先验证 6.2 假设（成本最低、嫌疑最大）**：`git revert 3e9ad33` → 装 → 复现一次。若白屏消失，元凶就是取景开孔模式未配对还原。
2. 若 1 不成立，再做 6.1 的诊断打印，用真机确定 `isAttachedToWindow` / `windowToken` / `visibility` 的真实值。
3. 6.3 的 `isCurrentOverlayInline()` 判据建议一并复核——它现在是"看 layoutParams 类型"，在 inline↔window 切换与内容视图替换后都可能与真实状态不符。
4. 每次改动后按 §2 的流程安装（**核 `lastUpdateTime`**）并按 §3 复现。

---

## 10. 日志文件（仓库外，未入库）

| 文件 | 内容 |
| --- | --- |
| `G:/IronWIl0329/.alarm-repro.log` | 最早一次复现（2.5 MB） |
| `G:/IronWIl0329/.alarm-verify.log` | 第二次复现（装 A+C+B' 后，2.7 MB） |
| `G:/IronWIl0329/.alarm-verify2.log` | 第三次复现（装 `ef04182` 后，2.3 MB）——**最新，优先看这个** |
