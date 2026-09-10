package com.decikeeper.app.manager

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.decikeeper.app.receiver.AlarmHeartbeatReceiver


/**
 * =================================================================================
 * [Context Header]
 * * @file: AlarmHeartbeatScheduler.kt
 * * @role: Domain Skill
 * * @description: AlarmManager 高频心跳调度器 — 距 alarm ≤30min 时按距离动态调整间隔。
 * * @responsibilities:
 * 1. 按 timeUntil 决定心跳间隔：>30min 不调度（让 Worker 处理）/ ≤30min 1min / ≤10min 30s / ≤3min 10s。
 * 2. 使用 setExactAndAllowWhileIdle(RTC_WAKEUP) —— 绕过 Doze；Vivo AppStandby 仍可能延后，但频率足够高时 AppStandby 累计延迟会被单次心跳消耗。
 * 3. self-rescheduling：每次心跳触发后 service 会再次调用 scheduleNext 续期。
 * * @context:
 * - 上游: AlarmGuardianService.onStartCommand (ACTION_HEARTBEAT)
 * - 下游: AlarmHeartbeatReceiver (广播接收)
 * * @constraints (必读):
 * 1. PendingIntent request code base = 8_000_000，避开 AlarmScheduler 已用的 1M-7M 区间。
 * 2. 远距 (>30min) 不调度 —— 让 WorkManager 处理，避免 AlarmManager 高频调用被 AppStandby 标记。
 * 3. 取消时只取消自己创建的 PendingIntent，不影响其他 receiver。
 * =================================================================================
 */
object AlarmHeartbeatScheduler {

    private const val TAG = "AlarmHeartbeatSched"
    /** PendingIntent request code 基础值。避开 AlarmScheduler 现有的 1M-7M 区间 */
    private const val RC_BASE = 8_000_000

    /** 远距阈值：超过此值不调度 AlarmManager 心跳，让 WorkManager 处理 */
    private const val FAR_THRESHOLD_MS = 30 * 60 * 1000L
    /** 中距阈值：超过此值用 1min 间隔 */
    private const val MID_THRESHOLD_MS = 10 * 60 * 1000L
    /** 近距阈值：超过此值用 30s 间隔 */
    private const val NEAR_THRESHOLD_MS = 3 * 60 * 1000L

    /**
     * 调度下一次高频心跳广播。
     * - timeUntil <= 0：不调度（已到 alarm 时刻，由其他路径处理）
     * - timeUntil > FAR_THRESHOLD：不调度（远距场景交给 WorkManager）
     * - FAR_THRESHOLD ≥ timeUntil > MID_THRESHOLD：1min 后
     * - MID_THRESHOLD ≥ timeUntil > NEAR_THRESHOLD：30s 后
     * - NEAR_THRESHOLD ≥ timeUntil：10s 后（Doze 允许的最小间隔）
     */
    fun scheduleNext(context: Context, alarmId: Int, nextAlarmTime: Long) {
        val now = System.currentTimeMillis()
        val timeUntil = nextAlarmTime - now
        if (timeUntil <= 0) return
        if (timeUntil > FAR_THRESHOLD_MS) {
            Log.d(TAG, "scheduleNext: timeUntil=${timeUntil / 60000}min > 30min, deferring to WorkManager")
            return
        }

        val interval = when {
            timeUntil > MID_THRESHOLD_MS -> 1 * 60 * 1000L     // ≤30min, >10min: 1min
            timeUntil > NEAR_THRESHOLD_MS -> 30 * 1000L       // ≤10min, >3min: 30s
            else -> 10 * 1000L                                // ≤3min: 10s
        }

        val pi = PendingIntent.getBroadcast(
            context,
            RC_BASE + alarmId,
            Intent(context, AlarmHeartbeatReceiver::class.java).apply {
                action = AlarmHeartbeatReceiver.ACTION_HEARTBEAT
                putExtra(AlarmHeartbeatReceiver.EXTRA_ALARM_ID, alarmId)
                putExtra(AlarmHeartbeatReceiver.EXTRA_NEXT_ALARM_TIME, nextAlarmTime)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val triggerAt = now + interval
        val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        try {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
            Log.d(TAG, "scheduleNext: alarmId=$alarmId, interval=${interval}ms, triggerAt=$triggerAt")
        } catch (e: SecurityException) {
            // SCHEDULE_EXACT_ALARM 未授予时降级到 setAndAllowWhileIdle
            try {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
                Log.w(TAG, "scheduleNext: fallback to setAndAllowWhileIdle (no SCHEDULE_EXACT_ALARM)")
            } catch (e2: Exception) {
                Log.w(TAG, "scheduleNext failed: ${e2.message}")
            }
        } catch (e: Exception) {
            Log.w(TAG, "scheduleNext failed: ${e.message}")
        }
    }

    /**
     * 取消指定 alarmId 的所有高频心跳 PendingIntent。
     */
    fun cancel(context: Context, alarmId: Int) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        try {
            val pi = PendingIntent.getBroadcast(
                context,
                RC_BASE + alarmId,
                Intent(context, AlarmHeartbeatReceiver::class.java).apply {
                    action = AlarmHeartbeatReceiver.ACTION_HEARTBEAT
                },
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
            )
            if (pi != null) {
                am.cancel(pi)
                pi.cancel()
                Log.d(TAG, "cancel: alarmId=$alarmId heartbeat alarm cancelled")
            }
        } catch (e: Exception) {
            Log.w(TAG, "cancel failed for alarmId=$alarmId: ${e.message}")
        }
    }
}
