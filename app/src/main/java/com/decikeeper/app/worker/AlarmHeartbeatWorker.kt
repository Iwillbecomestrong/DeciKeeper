package com.decikeeper.app.worker

import android.content.Context
import android.util.Log
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.decikeeper.app.data.DefaultLogDebugConfig
import com.decikeeper.app.data.LogExporter
import com.decikeeper.app.manager.AlarmHeartbeatScheduler
import java.util.concurrent.TimeUnit




/**
 * =================================================================================
 * [Context Header]
 * * @file: AlarmHeartbeatWorker.kt
 * * @role: Domain Skill
 * * @description: WorkManager 周期性心跳 Worker — 远距场景（>15min）每 15min 输出一次心跳，免疫 Vivo AppStandby。
 * * @responsibilities:
 * 1. 距 alarm > 30min：续期自身 15min 后再次触发。
 * 2. 距 alarm ≤ 30min：切换到 AlarmHeartbeatScheduler（高频心跳接管）。
 * 3. 每次触发输出心跳日志，确保用户从导出日志能确认 Guardian 进程存活。
 * * @context:
 * - 上游: AlarmScheduler.scheduleHeartbeatWorker (enqueueUniqueWork REPLACE)
 * - 下游: AlarmHeartbeatScheduler.scheduleNext (≤30min 切换)
 * * @constraints (必读):
 * 1. WorkManager 使用 JobScheduler 机制，免疫 Vivo AppStandby 桶（与 AlarmManager 不同）。
 * 2. 与 AlarmWorker 职责不同：AlarmWorker 在 alarm 时刻检查/触发；本 Worker 在远距保活。
 * 3. unique work name = "heartbeat_worker_$alarmId"，多次 enqueue 用 REPLACE 覆盖避免累积。
 * =================================================================================
 */
class AlarmHeartbeatWorker(
    context: Context,
    params: WorkerParameters
) : Worker(context, params) {

    companion object {
        private const val TAG = "AlarmHeartbeatWorker"
        const val KEY_ALARM_ID = "alarm_id"
        const val KEY_NEXT_ALARM_TIME = "next_alarm_time"
        /** 远距心跳周期：距 alarm > 30min 时每 15min 一次 */
        private const val FAR_INTERVAL_MINUTES = 15L
        /** 切换到 AlarmManager 高频心跳的阈值 */
        private const val NEAR_THRESHOLD_MS = 30 * 60 * 1000L

        /**
         * 调度 Worker 自身周期心跳。
         * 由 AlarmScheduler.scheduleNext 在主路径末尾调用，确保 scheduleAll 一次就建立远距保活链。
         */
        fun schedule(context: Context, alarmId: Int, nextAlarmTime: Long) {
            val delay = nextAlarmTime - System.currentTimeMillis()
            // 如果 alarm 时间已经在 30min 内，直接交给 AlarmHeartbeatScheduler
            if (delay <= NEAR_THRESHOLD_MS) {
                Log.d(TAG, "schedule: alarmId=$alarmId, delay=${delay / 60000}min ≤ 30min, deferring to AlarmManager")
                return
            }
            val req = OneTimeWorkRequestBuilder<AlarmHeartbeatWorker>()
                .setInitialDelay(FAR_INTERVAL_MINUTES, TimeUnit.MINUTES)
                .setInputData(
                    workDataOf(
                        KEY_ALARM_ID to alarmId,
                        KEY_NEXT_ALARM_TIME to nextAlarmTime
                    )
                )
                .addTag("alarm_heartbeat_${alarmId}")
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                "heartbeat_worker_$alarmId",
                ExistingWorkPolicy.REPLACE,
                req
            )
            Log.i(TAG, "schedule: alarmId=$alarmId heartbeat worker enqueued (every ${FAR_INTERVAL_MINUTES}min)")
        }

        /**
         * 取消指定 alarmId 的远距心跳 Worker。
         */
        fun cancel(context: Context, alarmId: Int) {
            try {
                WorkManager.getInstance(context).cancelUniqueWork("heartbeat_worker_$alarmId")
                Log.d(TAG, "cancel: alarmId=$alarmId heartbeat worker cancelled")
            } catch (e: Exception) {
                Log.w(TAG, "cancel failed for alarmId=$alarmId: ${e.message}")
            }
        }
    }

    override fun doWork(): Result {
        val alarmId = inputData.getInt(KEY_ALARM_ID, -1)
        val nextAlarmTime = inputData.getLong(KEY_NEXT_ALARM_TIME, 0)
        if (alarmId < 0 || nextAlarmTime <= 0) {
            Log.w(TAG, "doWork: invalid input (alarmId=$alarmId, nextAlarmTime=$nextAlarmTime)")
            return Result.success()
        }

        val now = System.currentTimeMillis()
        val timeUntil = nextAlarmTime - now
        val minutesLeft = timeUntil / 60000
        if (DefaultLogDebugConfig.protectionVerbose) {
            LogExporter.i("AlarmGuardian", "💓 心跳(worker) | alarmId=$alarmId | ${minutesLeft}min")
        }

        if (timeUntil <= NEAR_THRESHOLD_MS) {
            // 切到 AlarmManager 高频
            AlarmHeartbeatScheduler.scheduleNext(applicationContext, alarmId, nextAlarmTime)
            Log.d(TAG, "doWork: switched to AlarmManager high-frequency heartbeat")
        } else {
            // 续期自身 15min 后
            schedule(applicationContext, alarmId, nextAlarmTime)
        }

        return Result.success()
    }
}
