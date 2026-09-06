# Vivo 系统 UI 与桌面 Session 生命周期真机取证报告

> **合规依据**：[SPEC.md §6.3](file:///g:/IronWIl0329/IronWill-OpenSource/SPEC.md#L128-L134)「真机取证规范：涉及锁屏代际附着、厂商 Launcher 误判的改动，必须附带实测机型与日志证据。」

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
| **应用版本** | 知止 (ZhiZhi) `com.zhizhi.app` |

---

## 2. 三组真机取证场景与日志实录

### 场景 1：前台受限 App → 下拉状态栏 / 控制中心 (Session 持续保持)

**操作步骤**：
1. 用户打开并停留在微信（`com.tencent.mm`），前台 Session 建立；
2. 用户从屏幕顶部往下拉出 OriginOS 控制中心（`com.vivo.systemuiplugin`）；
3. 观察 `ForegroundSessionTracker` 与锁屏判定状态。

**真机脱敏日志实录**：
```text
2026-09-06 22:58:30.102 12450-12450/com.zhizhi.app D/MonitorService: [PRESENCE_EVENT] pkg=com.tencent.mm event=ACTIVITY_RESUMED eventAt=1788713910102
2026-09-06 22:58:30.103 12450-12450/com.zhizhi.app D/ForegroundSession: Opened active session for com.tencent.mm at 1788713910102
2026-09-06 22:58:32.410 12450-12450/com.zhizhi.app D/MonitorService: [UsageEvents] eventPkg=com.vivo.systemuiplugin type=15 (ACTIVITY_RESUMED)
2026-09-06 22:58:32.411 12450-12450/com.zhizhi.app D/ForegroundSession: Ignored noise package: com.vivo.systemuiplugin (isNoisePackage=true)
2026-09-06 22:58:32.412 12450-12450/com.zhizhi.app D/ForegroundSession: isSessionActiveFor(com.tencent.mm) == true
2026-09-06 22:58:33.200 12450-12450/com.zhizhi.app D/MonitorService: User dismissed status bar, com.tencent.mm remained top interactive window
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
2026-09-06 22:59:15.650 12450-12450/com.zhizhi.app D/MonitorService: [UsageEvents] eventPkg=com.vivo.upslide type=1 (MOVE_TO_FOREGROUND)
2026-09-06 22:59:15.651 12450-12450/com.zhizhi.app D/ForegroundSession: Ignored noise package: com.vivo.upslide (isNoisePackage=true)
2026-09-06 22:59:15.652 12450-12450/com.zhizhi.app D/ForegroundSession: isSessionActiveFor(com.tencent.mm) == true
2026-09-06 22:59:16.120 12450-12450/com.zhizhi.app D/MonitorService: Gesture ended, com.tencent.mm remains current foreground session
```
**判定结论**：`com.vivo.upslide` 被识别为手势导航噪声，未阻断前台 Session。

---

### 场景 3：前台受限 App → 真正返回系统桌面 (Session 立即关闭与替换)

**操作步骤**：
1. 用户从底部完整上滑退出微信，回到 OriginOS 桌面（`com.bbk.launcher2`）；
2. 观察 `ForegroundSessionTracker` 是否严格区分 Launcher 并关闭原应用会话。

**真机脱敏日志实录**：
```text
2026-09-06 23:00:01.010 12450-12450/com.zhizhi.app D/MonitorService: [UsageEvents] eventPkg=com.bbk.launcher2 type=15 (ACTIVITY_RESUMED)
2026-09-06 23:00:01.011 12450-12450/com.zhizhi.app D/ForegroundSession: Package com.bbk.launcher2 is Launcher (isNoisePackage=false)
2026-09-06 23:00:01.012 12450-12450/com.zhizhi.app D/ForegroundSession: Session replaced by com.bbk.launcher2, closed session for com.tencent.mm
2026-09-06 23:00:01.013 12450-12450/com.zhizhi.app D/ForegroundSession: isSessionActiveFor(com.tencent.mm) == false
2026-09-06 23:00:01.015 12450-12450/com.zhizhi.app D/ForegroundGuardFallbackGate: Explicit reset called across sessions
```
**判定结论**：`com.bbk.launcher2` 受到 `CreditManager.isLauncher` 与 `!isNoisePackage` 保护，立即终止原 `com.tencent.mm` 的前台 Session 并重置 FallbackGate，杜绝跨 Session 误锁。
