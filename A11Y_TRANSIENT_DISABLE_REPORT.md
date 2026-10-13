# 无障碍覆盖安装瞬态解绑与增强保护丢失诊断报告 (A11Y_TRANSIENT_DISABLE_REPORT)

## 0. 元数据与测试环境

- **测试代码 Commit**: `HEAD`（见独立诊断提交）
- **APK 版本**: `0.3.0` (versionCode: `2`)
- **测试设备**: 待接入真机（如 vivo X100 / iQOO / ColorOS）
- **Android / OS 版本**: 待真机实测回填
- **诊断埋点就绪状态**: **READY** (已完成生命周期时间链、统一快照、调用来源追踪与单独浮窗追踪)

---

## 1. 核心假设定义与判定结论

### 假设定义
- **H1**: 覆盖安装/更新 APK 时，底层系统会经历短暂解绑（`CoreService.onUnbind`）；在该瞬态期间 `PermissionManager.isAccessibilityEnabled()` 偶尔返回 `false`，导致 `AccessibilityDependencyManager.reconcile(false)` 被立即调用，将 SharedPreferences 中的长期保护配置全部误写为 `false`（DISABLED），而在数秒后 `onServiceConnected` 恢复时，增强保护已无法自动复原。
- **H2**: 桌面守护小浮窗存在另一条独立的持久化关闭路径，与 `AccessibilityDependencyManager` 不同。

### 静态代码证据分析（已先行证实项）
1. **关于问题 D（五项保护为何一起关闭，尤其是桌面守护小浮窗）**：
   - 检查 `AccessibilityDependencyManager.kt` 历史提交发现：在 commit `4de78ee` (*feat(protection): 统一无障碍及依赖保护恢复基准立即关闭并联动小浮窗*) 中，已明确添加：
     ```kotlin
     GuardFloatingWindowManager.disableImmediatelyForAccessibilityUnavailable(context)
     ```
   - 因此，**桌面守护小浮窗与防卸载、隐私保护、小窗增强、闹钟防关机已完全绑在同一清理链条中**。只要 `reconcile(false)` 被调用，这五项保护将 100% 毫无保留地在同一时刻全部写入持久化 `false`！
2. **关于问题 B/C 与 6 秒防抖（Requirement 17）**：
   - `PermissionManager.kt` 中的轮询监控具备防抖：
     ```kotlin
     private const val A11Y_LOST_CONSECUTIVE_THRESHOLD = 2 // 连续 2 次（约 6 秒）才确认真丢失
     ```
   - 但在 `CoreService.kt` 的 `onUnbind()` 中：
     ```kotlin
     override fun onUnbind(intent: Intent?): Boolean {
         ...
         val systemAccessibilityEnabled = PermissionManager.isAccessibilityEnabled(this)
         AccessibilityDependencyManager.reconcile(this, systemAccessibilityEnabled)
     ```
   - 这里**完全绕过了 6 秒防抖**！一旦系统在解绑瞬间底层尚未重新注册组件导致单次 `isAccessibilityEnabled == false`，立即触发同步永久抹除！

---

## 2. 安装方式测试矩阵（真机执行模板）

| 安装方式 | 测试轮数 | onUnbind 次数 | 短暂 systemEnabled=false 次数 | reconnect 次数 | 保护状态异常(变OFF)次数 | 最小断连(ms) | 最大断连(ms) | 平均断连(ms) |
| :--- | :---: | :---: | :---: | :---: | :---: | :---: | :---: | :---: |
| **对照组 (正常使用 10min)** | 2 组 | 0 | 0 | 0 | 0 | - | - | - |
| **同 APK 重复覆盖 (`adb install -r`)** | 20 轮 | 待真机运行 | 待真机运行 | 待真机运行 | 待真机运行 | - | - | - |
| **版本递增更新 (`vN -> vN+1`)** | 10 轮 | 待真机运行 | 待真机运行 | 待真机运行 | 待真机运行 | - | - | - |
| **开发工具部署 (`gradlew installDebug`)**| 10 轮 | 待真机运行 | 待真机运行 | 待真机运行 | 待真机运行 | - | - | - |
| **真实手动关闭无障碍对照** | 1 次 | 1 | 1 (持续) | 0 (手动关闭期) | 1 (预期清理) | - | - | - |

---

## 3. 首次异常现场追踪与时间线示例

- **首次异常 RunId**: 待真机触发记录
- **异常时间戳**: 
- **复现特征**: 

### 典型时间链日志片段（规范模板）
```text
[A11Y_UNBIND] runId=A11Y_INSTALL_001 unbindSequenceId=A11Y-UNBIND-0001 uptime=1234567 systemEnabledImmediate=false serviceActiveBefore=true recentSignalAgeMs=420 lostCounter=0
[A11Y_DEP_RECONCILE] runId=A11Y_INSTALL_001 caller=CoreService.onUnbind systemEnabled=false serviceActive=false recentSignal=true lostCounter=0
[A11Y_DEP_RECONCILE] runId=A11Y_INSTALL_001 decision=FORCE_DISABLE reason=SYSTEM_A11Y_UNAVAILABLE
[PROTECTION_SNAPSHOT] trigger=A11Y_DEP_RECONCILE_BEFORE ...
[PROTECTION_FORCE_DISABLE] runId=A11Y_INSTALL_001 component=anti_uninstall caller=AccessibilityDependencyManager oldEnabled=true oldState=ACTIVE ... newEnabled=false newState=DISABLED
[PROTECTION_FORCE_DISABLE] runId=A11Y_INSTALL_001 component=guard_floating_window caller=AccessibilityDependencyManager oldEnabled=true oldState=ACTIVE ... newEnabled=false newState=DISABLED
[GUARD_DESIRED_WRITE] runId=A11Y_INSTALL_001 old=true new=false caller=AccessibilityDependencyManager reason=disableImmediatelyForAccessibilityUnavailable
...
[A11Y_RECONNECT] runId=A11Y_INSTALL_001 unbindSequenceId=A11Y-UNBIND-0001 gapMs=4206 uptime=1238773
[PROTECTION_SNAPSHOT] trigger=CoreService.onServiceConnected ...
```

---

## 4. 诊断结论与架构建议

1. **根因定性**:
   - `CoreService.onUnbind` 在系统更新覆盖安装时被底层触发是 Android 系统的常规生命周期行为。
   - 错误在于业务层将**“进程/服务生命周期瞬态回调”**与**“用户的长期意图（Desired State）”**强行混同，在单次 `onUnbind` 且未经过防抖验证时直接抹除了用户的持久化配置。
2. **建议修复策略（先诊断，后续对齐修复方案）**:
   - **方案 A（状态解耦，最佳架构）**：区分 `desiredEnabled` 与 `effectiveEnforcing`。`onUnbind` 仅进入暂挂（PAUSED/RECONNECTING），不写死持久化为 `false`；待 `onServiceConnected` 恢复时自动继续执行。只有用户在设置界面明确点击关闭、或无障碍长时间真正丢失并经防抖确认后，才进行不利变更持久化。
   - **方案 B（防抖门禁）**：`CoreService.onUnbind` 禁止单次直通调用 `reconcile(false)`，统一纳入 6 秒防抖。
