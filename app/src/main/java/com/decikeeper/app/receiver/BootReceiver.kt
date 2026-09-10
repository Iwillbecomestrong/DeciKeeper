/**
 * =================================================================================
 * [Context Header]
 * * @file: BootReceiver.kt
 * * @role: Domain Skill
 * * @description: 系统启动完成后的服务拉起入口。
 * * @responsibilities:
 * 1. 响应开机广播并启动核心服务。
 * 2. 恢复必要的运行状态。
 * * @context:
 * - 上游: 系统广播
 * - 下游: CoreService, MonitorService
 * * @constraints (必读):
 * 1. 执行需快速，避免阻塞。
 * 2. 需处理权限与版本差异。
 * 3. 不包含 UI 逻辑。
 * =================================================================================
 */
package com.decikeeper.app.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.decikeeper.app.data.LogExporter
import com.decikeeper.app.manager.AlarmScheduler
import com.decikeeper.app.manager.AlarmWindowManager
import com.decikeeper.app.manager.PermissionManager
import com.decikeeper.app.manager.StartupA11yCache
import com.decikeeper.app.manager.StartupProtectionManager
import com.decikeeper.app.service.AlarmVibrationService
import com.decikeeper.app.service.CoreService
import com.decikeeper.app.service.MonitorService
import com.decikeeper.app.ui.AlarmActivity












class BootReceiver : BroadcastReceiver() {
    companion object {
        /** Test seam: observes USER_PRESENT overlay restoration without opening a real window. */
        internal var userPresentOverlayRestorer: ((Context, Int) -> Unit)? = null
    }

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action
        Log.d("DeciKeeper", "BootReceiver received action: $action")

        // 1. FastLock: 读 DPS 缓存（~1ms），不查 Settings.Secure。
        // earlyBootFastLockStarted 先检查（volatile，零开销），MyApplication 已拉起则跳过。
        if (!StartupProtectionManager.earlyBootFastLockStarted) {
            val cached = StartupA11yCache.isA11yWasEnabledForStartup(context)
            Log.d("DeciKeeper", "BootReceiver: startup a11y DPS cache=$cached")
            if (cached) {
                try {
                    // 直接 addView，不等 Service 启动（毫秒级出现）
                    StartupProtectionManager.attachEarlyBootFastLock(context)
                    StartupProtectionManager.startFastLock(context)
                    StartupProtectionManager.earlyBootFastLockStarted = true
                    Log.d("DeciKeeper", "BootReceiver: FastLock overlay attached + service requested from DPS cache.")
                    // [2026-06-25] 诊断日志：记录 BootReceiver 路径是否触发 FastLock 及接收的 action
                    // 用于排查"初始化界面刷新两次"现象
                    // 详见 Issue_AntiUninstallBootGap 尝试 8
                    Log.i("DeciKeeper", "[BootRecv] earlyFastLock path: cached=$cached action=$action")
                } catch (e: Exception) {
                    Log.e("DeciKeeper", "BootReceiver: Failed to start FastLockService", e)
                }
            } else {
                Log.d("DeciKeeper", "BootReceiver: A11y cache false, skipping FastLock.")
            }
        } else {
            Log.d("DeciKeeper", "BootReceiver: FastLock already handled by MyApplication, skipping.")
        }

        // 2. Only start MonitorService if User is Unlocked
        // LOCKED_BOOT_COMPLETED means credential encrypted storage is not available yet.
        if (action == Intent.ACTION_LOCKED_BOOT_COMPLETED) {
            Log.i("DeciKeeper", "BootReceiver: In Direct Boot mode. MonitorService deferred until unlock.")

            // 完整 alarms_json 尚不可读，只用 DPS bootstrap 重注册未来触发器。
            try {
                AlarmScheduler.scheduleAllForDirectBoot(context)
            } catch (e: Exception) {
                Log.e("IronWill", "BootReceiver: Failed to schedule future alarms in Direct Boot", e)
            }

            // ⏰ 即使手机未解锁，也要检查闹钟是否在震动期间被强制重启
            // 若是，直接启动震动服务（无需 Activity），让用户不解锁也能被闹钟震动唤醒
            try {
                if (AlarmScheduler.isAlarmActive(context)) {
                    val alarmId = AlarmScheduler.getActiveAlarmId(context)
                    Log.i("DeciKeeper", "BootReceiver: [DirectBoot] Alarm was active (id=$alarmId), starting vibration service directly")
                    LogExporter.logEvent("AlarmRecovery", "DirectBoot: starting vibration service (alarmId=$alarmId)")
                    val vibrateIntent = Intent(context, AlarmVibrationService::class.java).apply {
                        putExtra(AlarmVibrationService.EXTRA_ALARM_ID, alarmId)
                        putExtra(AlarmVibrationService.EXTRA_DIRECT_BOOT, true)
                        putExtra(
                            AlarmScheduler.EXTRA_SCHEDULE_TOKEN,
                            AlarmScheduler.getActiveScheduleToken(context)
                        )
                    }
                    context.startForegroundService(vibrateIntent)
                }
            } catch (e: Exception) {
                Log.e("DeciKeeper", "BootReceiver: Failed to resume alarm in Direct Boot", e)
                LogExporter.logEvent("AlarmRecovery", "DirectBoot ERROR: ${e.message}")
            }
            return
        }

        // 3. USER_PRESENT: 用户解锁后恢复被省电模式杀死的服务
        if (action == Intent.ACTION_USER_PRESENT) {
            Log.i("DeciKeeper", "BootReceiver: User present. Checking service health after potential power-save kill.")

            // 检查 CoreService 是否存活，如已死则尝试恢复 MonitorService 兜底
            if (!CoreService.isServiceActive) {
                Log.w("DeciKeeper", "BootReceiver: CoreService not active after unlock, restarting MonitorService")
                try {
                    val monitorIntent = Intent(context, MonitorService::class.java)
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                        context.startForegroundService(monitorIntent)
                    } else {
                        context.startService(monitorIntent)
                    }
                } catch (e: Exception) {
                    Log.e("DeciKeeper", "BootReceiver: Failed to restart MonitorService on USER_PRESENT", e)
                }

                // 如果 CoreService 持续不工作（启动了但连不上），更新缓存避免下次 FastLock 无限等待
                try {
                    if (PermissionManager.isAccessibilityEnabled(context) && !CoreService.isServiceActive) {
                        StartupA11yCache.setA11yWasEnabled(context, false)
                        Log.w("DeciKeeper", "BootReceiver: A11y enabled in settings but not active, cleared cache to prevent FastLock hang")
                    }
                } catch (e: Exception) {
                    Log.w("DeciKeeper", "BootReceiver: Failed to update A11y cache", e)
                }

            }
            // 恢复闹钟调度（可能在省电模式期间被清除）
            try {
                AlarmScheduler.scheduleAll(context)
                if (AlarmScheduler.isAlarmActive(context)) {
                    val activeAlarmId = AlarmScheduler.getActiveAlarmId(context)
                    if (activeAlarmId > 0) {
                        val vibrateIntent = Intent(context, AlarmVibrationService::class.java).apply {
                            putExtra(AlarmVibrationService.EXTRA_ALARM_ID, activeAlarmId)
                        }
                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                            context.startForegroundService(vibrateIntent)
                        } else {
                            context.startService(vibrateIntent)
                        }
                        userPresentOverlayRestorer?.invoke(context, activeAlarmId)
                            ?: AlarmWindowManager.show(context, activeAlarmId)
                    }
                }
            } catch (e: Exception) {
                Log.w("DeciKeeper", "BootReceiver: Failed to schedule alarm on USER_PRESENT", e)
            }

            return
        }

        // Standard BOOT_COMPLETED or QuickBoot
        Log.d("DeciKeeper", "BootReceiver: User unlocked (or full boot). Starting full services silently.")
        
        // ⏰ [P0] Alarm recovery FIRST, before MonitorService starts
        try {
            val wasActive = AlarmScheduler.isAlarmActive(context)
            LogExporter.logEvent("AlarmRecovery", "BootReceiver: isAlarmActive=$wasActive")
            Log.i("DeciKeeper", "BootReceiver: [NEW] isAlarmActive=$wasActive")
            if (wasActive) {
                val activeAlarmId = AlarmScheduler.getActiveAlarmId(context)
                Log.i("DeciKeeper", "BootReceiver: Alarm was active (id=$activeAlarmId), resuming alarm")
                LogExporter.logEvent("AlarmRecovery", "BootReceiver: resuming alarm (id=$activeAlarmId)")
                val alarmIntent = Intent(context, AlarmActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    putExtra(AlarmScheduler.EXTRA_ALARM_ID, activeAlarmId)
                }
                context.startActivity(alarmIntent)
            }
        } catch (e: Exception) {
            Log.e("DeciKeeper", "BootReceiver: Failed to resume alarm", e)
            LogExporter.logEvent("AlarmRecovery", "BootReceiver: ERROR: ${e.message}")
        }

        // ⏰ 再恢复闹钟调度
        try {
            AlarmScheduler.scheduleAll(context)
        } catch (e: Exception) {
            Log.e("DeciKeeper", "BootReceiver: Failed to schedule alarm", e)
        }

        // 🚀 [STEP 2] Start MonitorService (after alarm recovery)
        try {
            val monitorIntent = Intent(context, MonitorService::class.java)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                context.startForegroundService(monitorIntent)
            } else {
                context.startService(monitorIntent)
            }
        } catch (e: Exception) {
            Log.e("DeciKeeper", "BootReceiver: Failed to start MonitorService", e)
        }

        Log.i("ZhiZhiBoot", "Boot recovery completed silently, UI launch skipped")

    }
}
