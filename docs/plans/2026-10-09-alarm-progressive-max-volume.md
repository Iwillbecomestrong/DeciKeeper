# 渐进提醒「最大声音」上限

> 状态：**实现中**。本文不是产品真相；规则已写入 `docs/specs/alarm.md` §7.1.1，决策见 `HISTORY.md` DECISION-44。真机验证完成后归档到 `docs/archive/plans/`。

## 1. 目标

渐进声音按 `(档/10)^3` 增长，真机实测第 2 档已能听到、第 3 档已很响，后几档只是更吵。让用户手动限制渐进的最大声音（及最大震动），10 步仍保留，均匀铺在 Level 1 与最大值之间。

## 2. 范围

**做**：渐进模式「最大声音」「最大震动」（各 1–10，默认 10）；第一关 / 第二关各一份；默认设置 + 单个闹钟可改；声音滑块松手试听约 2 秒、震动滑块松手试震约 0.8 秒；切换提醒方式 / 强度时隐藏的控件收起、不留空隙（反转原 §2.3）。

**不做**：最小声音 / 最小震动；固定模式。

## 3. 公式

第 k 步（k = 1..10）有效档位 `L(k) = 1 + (max − 1)(k − 1)/9`，声音增益 `(L/10)^3`，震动振幅 `L × 25.5`。max = 10 时与旧行为逐位相同。界面显示的当前档仍是步号 k。

## 4. 落点

| 层 | 文件 | 改动 |
|---|---|---|
| 策略 | `manager/AlarmAlertPolicy.kt` | `StageAlertPolicy.progressiveMaxRingVolume`；`ringLevelValue()` |
| 服务 | `service/AlarmVibrationService.kt` | 策略填入最大值；增益改用浮点档位 |
| 存储 | `manager/AlarmScheduler.kt` | 新键 `progressiveRingMax`、`stage2ProgressiveRingMax`；默认键 `default_progressive_ring_max`、`default_stage2_progressive_ring_max` |
| 备份 | `manager/SettingsBackupManager.kt` | 默认键加入闹钟分类 |
| 界面 | `ui/AlarmReminderSettingsView.kt` | 声音行在渐进模式下作为「最大声音」；松手回调试听 |
| 试听 | `ui/AlarmVolumePreview.kt` | 2 秒试听，复用 `AlarmStreamVolumeLock` |
| 接线 | `ui/AlarmFragment.kt`、`ui/LockWindowManager.kt`、`ui/LockAlarmOverlay.kt` | 去掉写死的 10，透传第一关 / 第二关 |

旧键 `progressiveMaxRingVolume`、`progressiveMaxIntensity`、`default_progressive_max_ring`、`default_progressive_max_intensity` 不再读写（防早期版本遗留值让旧闹钟变轻）；震动新键为 `progressiveVibrationMax`、`stage2ProgressiveVibrationMax`、`default_progressive_vibration_max`、`default_stage2_progressive_vibration_max`。

## 5. 验证

- JVM 测试：`AlarmAlertPolicyTest`、`AlarmReminderSettingsViewTest`、存储往返与备份清单契约。
- 真机（vivo）：最大声音调到 5，测试闹钟走完 4.5 分钟，爬升平缓且不超上限；松手试听响度与实际一致；10 与改动前听感一致；第二关独立设置生效；备份导入导出保留。
