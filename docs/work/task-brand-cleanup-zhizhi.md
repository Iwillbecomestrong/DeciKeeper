# 任务规格 (SPEC): 全面净化「知止 (ZhiZhi)」通知与界面残留

## 1. 目标 (Goal)
彻底消除 Android 通知栏、通知渠道、多语言资源文件及各 UI 界面（帮助手册、权限引导、锁屏弹窗）中遗留的「IronWill」旧品牌名称与文案，统一替换为「知止 (ZhiZhi)」，并在应用启动时自动注销清理旧系统的 `ironwill_*` 通知渠道。

## 2. 非目标 (Non-Goals)
- 不修改向后兼容旧版配置导入时所必须保留的 `ironwill_settings_backup` 协议标识符。
- 不影响底层核心防卸载与锁机算法逻辑。

## 3. 具体行为与界面契约 (Behavior & Interfaces)
1. **前台服务与通知栏净化**：
   - `MonitorService` 前台服务通知标题改为「知止 正在运行」，内容改为「正在守护自律时间边界...」，通知渠道 ID 更新为 `zhizhi_monitor_channel`，名称为「知止后台监控服务」。
   - `A11yGuardService` 守护通知标题改为「知止 守护中」/「知止 运行中」，渠道 ID 为 `ZhiZhiGuardChannel`，名称为「知止守护服务」。
   - `FastLockService` 渠道 ID 为 `zhizhi_fast_lock`，名称为「知止开机保护」。
   - `AlarmGuardianService` 标题改为「知止 守护中」。
   - `AlarmChallengeEmergencyGuardService` 标题改为「知止 闹钟挑战进行中」。
   - `AlarmVibrationService` 标题改为「知止」。
   - `PermissionManager` 渠道 ID 改为 `ZhiZhiPermChannel`，名称为「知止权限监控」。
2. **旧渠道注销清理**：
   - 在 `MyApplication.onCreate` 或通知服务初始化时，调用 `NotificationManager.deleteNotificationChannel()` 清理旧渠道 `ironwill_monitor_channel`、`IronWillGuardChannel`、`IronWillPermChannel`、`ironwill_fast_lock`。
3. **UI 与引导文案净化**：
   - `RulesFragment`：多任务隐藏、无障碍开启引导、电量耗电引导中文案中的「IronWill」全面平移为「知止」。
   - `HomeFragment`：悬浮窗权限弹窗、使用情况访问权限弹窗中文字替换为「知止」。
   - `HelpActivity`：标题与全篇用户手册正文中的「IronWill」全面替换为「知止」。
   - `LockWindowManager`：初始化防卸载文字「IronWill 软件防卸载功能正在初始化」替换为「知止防卸载功能正在初始化」。
4. **多语言资源包净化**：
   - `values-zh-rTW/strings.xml`：`app_name` 设为「知止」，描述中的 IronWill 替换为知止。
   - `values-en/strings.xml` 及所有 `values-*/strings.xml`：`app_name` 设为「ZhiZhi」，描述与防卸载拦截文案中的「IronWill」替换为「ZhiZhi」。

## 4. 验证与验收标准 (Acceptance Criteria)
1. 真机通知栏下拉不再出现任何「IronWill 正在运行」或「IronWill」字样，全部显示为「知止 正在运行」与「知止 守护中」。
2. 在真机通知设置（Notification Channels）中，所有分类显示为「知止后台监控服务」、「知止守护服务」、「知止权限监控」等。
3. 单元测试全量通过，APK 构建正常。
