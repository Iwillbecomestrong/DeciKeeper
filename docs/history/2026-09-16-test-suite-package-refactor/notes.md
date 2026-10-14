# Notes: 六项专项计划实施记录

## Baseline
- 工作区位于 `<repo>`，当前分支为 `dev`，已有大量用户未提交 Android 与文档改动。
- 本文件只记录本次实施证据；不覆盖或重写既有用户改动。

## Findings

- 基线首次命令因 PowerShell 参数拆分失败，未进入测试任务；这不是源码测试失败。
- 新增设备管理器 UI 映射后第一次编译发现颜色 `when` 未同步改用新状态类型，已修正；属于本轮编译回归而非基线问题。
- 组合门禁 `:app:testDebugUnitTest :app:lintRelease :app:assembleRelease` 成功；JUnit XML 汇总为 1357 tests、0 failures、0 errors、11 skipped。
- Markdown 链接检查通过：89 个文档文件。
- Release APK（小浮窗倒计时改动后重新生成）：`app/build/outputs/apk/release/app-release.apk`，包名 `com.decikeeper.app`，versionName `0.3.0`，versionCode `2`，arm64-v8a，大小 14,435,795 bytes，SHA-256 `fa3486178bf4a5cf2efe8fa6c444906b9021c82da7f17ed2e352556e4d5af55f`。
- Release verifier 修复了 Java properties 的 Windows SDK 路径转义，以及没有 `apkanalyzer` 时回退到 build-tools 的 `aapt2 dump permissions`；普通校验与 `-RequireSigned` 均通过。
- 最终 APK 未声明 `android.permission.INTERNET` 和 `android.permission.ACCESS_NETWORK_STATE`；ML Kit 保留，避免改变本地扫码业务链。Release runtime 解析确认 ML Kit 传递依赖包含 `play-services-*` 与 `com.google.android.datatransport`，文档已据此限定网络表述。
- 当前未执行：vivo/OPPO 真机状态与卸载效果、公共 Documents 实际落盘及卸载重装自动恢复、系统云/换机链、双渠道上传、真题来源材料的发布渠道整理、AOCI 维护。源码、AI 生成对联、项目生成翻译/释义的开源边界已确认；`git ls-remote --heads github/gitee` 本次 exit=0 但未返回分支 refs，因此不写成双端已同步。

待补充。

## Phase 9 findings: guard floating exit countdown
- Existing `GuardFloatingWindowManager` owns the persistent shield window, drag/snap position, and protection setting lifecycle; the new feature reuses that window.
- Privilege and rest use the canonical `CreditManager.getFreeTimeEnd()` plus privilege-active state; temporary unlock uses the persisted `decikeeper_lock_window_state/temp_access_until` deadline. Cooldown keys are intentionally excluded.
- RED was observed: the new projection test failed at test compilation because `GuardFloatingCountdown` and `FloatingExit` did not exist.
- GREEN was observed: `GuardFloatingCountdownTest` and `GuardFloatingWindowManagerTest` passed after adding the pure projection and UI integration.
- Confirmed UI contract: shield when no active exit; pure number in collapsed countdown state; fixed priority privilege > temporary unlock > rest; expanded read-only list; shield fallback on expiry or unavailable data; next-alarm countdown remains in the alarm challenge UI.
- Debug verification: `:app:testDebugUnitTest :app:lintDebug :app:assembleDebug` succeeded; the targeted countdown and existing floating-window tests also passed. Debug lint reports 2070 warnings plus 7 errors and 311 warnings filtered by the existing baseline; no new unbaselined failure was introduced by this feature.
- Device boundary remains open: overlay permission, edge expansion on the actual screen, game visual interference, and countdown refresh across process/ROM lifecycle were not claimed as real-device verified.
- Release repack verification: `assembleRelease` succeeded; `verify_release_artifact.ps1 -RequireSigned` passed for the new APK. Signature certificate SHA-256 is `D7EB8F0F01F387BDE97E8D5938BDE486FC6D0B3936C4A69BC838FE804CAA56D3`; no `INTERNET` or `ACCESS_NETWORK_STATE` permission was declared.
