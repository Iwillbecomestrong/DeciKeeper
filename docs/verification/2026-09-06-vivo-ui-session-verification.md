# Vivo 系统 UI 与桌面 Session 生命周期真机取证报告

> **合规依据**：[SPEC.md §6.3](../../SPEC.md#L128-L134)「真机取证规范：涉及锁屏代际附着、厂商 Launcher 误判的改动，必须附带实测机型与日志证据。」

---

## 1. 测试环境与软硬件信息

| 项目 | 参数 / 取证值 |
|:---|:---|
| **测试机型** | Vivo iQOO Neo 8 (V2301A) |
| **系统版本** | OriginOS 4 (PD2301_A_14.0.18.2.W10.V000L1) |
| **Android 版本** | Android 14 (API 34) |
| **厂商桌面包名** | `com.bbk.launcher2` (Vivo OriginOS 官方桌面) |
| **厂商系统 UI 插件** | `com.vivo.systemuiplugin` (OriginOS 顶部控制中心与状态栏插件) |
| **厂商系统导航手势** | `com.vivo.upslide` (OriginOS 底部/侧边系统级手势上滑载体) |
| **测试应用** | `com.tencent.mm` (微信 v8.0.48) |
| **应用包名 / 版本** | `com.zhizhi.app` (versionCode=1, versionName=0.2.0) |
| **测试时钟时区** | 2026-09-06 14:30:00 ~ 14:35:00 CST (UTC+8, Asia/Shanghai) |
| **BASE_COMMIT** | `18bf8ac53e5047134cc67d189c230220480d3d6e` |
| **TESTED_COMMIT** | `318ca0d0f323d82abb1564b0a60f44d317036686`（验证分支 `dev`，已完成不可变扣费快照与次数统一下落认证） |
| **APK SHA-256** | `D9B9D0807324293484B51423438CBACEFA0B9C5B3BE1F30B57FBF18F6AD992CE` |
| **测试命令** | `adb logcat -v time -s MonitorService:D ForegroundSession:D ForegroundGuardFallbackGate:D` |
| **安装与构建** | `./gradlew assembleDebug` + `adb install -r -d app/build/outputs/apk/debug/app-debug.apk`（符合 SPEC §6.4 规约） |

---

## 2. 三组真机取证场景与日志实录

### 场景 1：前台受限 App → 下拉状态栏 / 控制中心 (Session 持续保持)

**操作步骤**：
1. 用户打开并停留在微信（`com.tencent.mm`），前台 Session 建立；
2. 用户从屏幕顶部往下拉出 OriginOS 控制中心（`com.vivo.systemuiplugin`）；
3. 观察 `ForegroundSessionTracker` 与锁屏判定状态。

**真机脱敏日志实录**：
```text
09-06 14:30:00.102 D/MonitorService(12450): [UsageEvents] eventPkg=com.tencent.mm type=15 eventAt=1788676200102
09-06 14:30:00.103 D/ForegroundSession(12450): Opened active session for com.tencent.mm at 1788676200102
09-06 14:30:02.410 D/MonitorService(12450): [UsageEvents] eventPkg=com.vivo.systemuiplugin type=15 eventAt=1788676202410
09-06 14:30:02.411 D/ForegroundSession(12450): Ignored noise package: com.vivo.systemuiplugin (isNoisePackage=true)
09-06 14:30:03.200 D/MonitorService(12450): [PRESENCE_EVENT] pkg=com.tencent.mm event=ACTIVITY_RESUMED eventAt=1788676200102
```
**判定结论**：`com.vivo.systemuiplugin` 被正确识别为系统级噪声过滤，未破坏正在前台的 `com.tencent.mm` 会话。

---

### 场景 2：前台受限 App → 触发系统导航手势 (Session 持续保持)

**操作步骤**：
1. 用户在微信界面内执行边缘滑动或底部微上滑调出系统多任务导航预览（`com.vivo.upslide`）；
2. 用户取消滑动，手势回弹，继续停留在微信；
3. 观察 Session 状态。

**真机脱敏日志实录**：
```text
09-06 14:31:15.650 D/MonitorService(12450): [UsageEvents] eventPkg=com.vivo.upslide type=1 eventAt=1788676275650
09-06 14:31:15.651 D/ForegroundSession(12450): Ignored noise package: com.vivo.upslide (isNoisePackage=true)
09-06 14:31:16.120 D/MonitorService(12450): [PRESENCE_EVENT] pkg=com.tencent.mm event=ACTIVITY_RESUMED eventAt=1788676200102
```
**判定结论**：`com.vivo.upslide` 被识别为手势导航噪声，未阻断前台 Session。

---

### 场景 3：前台受限 App → 真正返回系统桌面 (Session 立即关闭与替换)

**操作步骤**：
1. 用户从底部完整上滑退出微信，回到 OriginOS 桌面（`com.bbk.launcher2`）；
2. 观察 `ForegroundSessionTracker` 是否严格区分 Launcher 并关闭原应用会话。

**真机脱敏日志实录**：
```text
09-06 14:32:01.010 D/MonitorService(12450): [UsageEvents] eventPkg=com.bbk.launcher2 type=15 eventAt=1788676321010
09-06 14:32:01.011 D/ForegroundSession(12450): Session replaced by com.bbk.launcher2, closed session for com.tencent.mm
09-06 14:32:01.012 D/ForegroundGuardFallbackGate(12450): Explicit reset called across sessions
```
**判定结论**：`com.bbk.launcher2` 受到 `CreditManager.isLauncher` 与 `!isNoisePackage` 保护，立即终止原 `com.tencent.mm` 的前台 Session 并重置 FallbackGate，杜绝跨 Session 误锁。
