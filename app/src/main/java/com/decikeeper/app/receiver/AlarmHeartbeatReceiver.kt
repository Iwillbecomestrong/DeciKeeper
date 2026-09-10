package com.decikeeper.app.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.decikeeper.app.data.LogExporter
import com.decikeeper.app.manager.AlarmScheduler
import com.decikeeper.app.service.AlarmGuardianService




/**
 * =================================================================================
 * [Context Header]
 * * @file: AlarmHeartbeatReceiver.kt
 * * @role: Domain Skill
 * * @description: 闹钟心跳广播接收器 — 接收 AlarmManager 高频心跳,拉起 Guardian 服务并调度下一次。
 * * @responsibilities:
 * 1. 接收 AlarmManager 派发的高频心跳广播 (AlarmHeartbeatScheduler 调度)。
 * 2. 输出心跳日志,与 handler/worker 心跳形成"三轨并发"的可见性。
 * 3. 拉起 AlarmGuardianService 进程(service 内部会再次 scheduleNext 续期)。
 * * @context:
 * - 上游: AlarmHeartbeatScheduler (AlarmManager 调度)
 * - 下游: AlarmGuardianService (startForegroundService)
 * * @constraints (必读):
 * 1. 远距场景 (>30min) 不调度本 receiver，由 AlarmHeartbeatWorker 负责（避免 AlarmManager 被 AppStandby 批量延迟）。
 * 2. Receiver 跑在主线程，不能阻塞；当前只输出日志 + startService，毫秒级。
 * 3. 不需要 directBootAware —— Doze 阶段不需要心跳。
 * =================================================================================
 */
class AlarmHeartbeatReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "AlarmHeartbeat"
        const val ACTION_HEARTBEAT = "com.decikeeper.app.ACTION_HEARTBEAT"
        const val EXTRA_ALARM_ID = "heartbeat_alarm_id"
        const val EXTRA_NEXT_ALARM_TIME = "heartbeat_next_alarm_time"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_HEARTBEAT) return
        val alarmId = intent.getIntExtra(EXTRA_ALARM_ID, -1)
        val nextAlarmTime = intent.getLongExtra(EXTRA_NEXT_ALARM_TIME, 0)
        if (alarmId < 0 || nextAlarmTime <= 0) return

        val now = System.currentTimeMillis()
        val timeUntil = nextAlarmTime - now
        val minutesLeft = timeUntil / 60000
        LogExporter.i("AlarmGuardian", "💓 心跳(alarm) | alarmId=$alarmId | ${minutesLeft}min")

        // 拉起 service；service 内部 onStartCommand 会处理 ACTION_HEARTBEAT 并续期
        try {
            val serviceIntent = Intent(context, AlarmGuardianService::class.java).apply {
                action = AlarmGuardianService.ACTION_HEARTBEAT
                putExtra(AlarmScheduler.EXTRA_ALARM_ID, alarmId)
                putExtra(AlarmGuardianService.EXTRA_NEXT_ALARM_TIME, nextAlarmTime)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
        } catch (e: Exception) {
            Log.w(TAG, "service start failed: ${e.message}")
        }
    }
}
