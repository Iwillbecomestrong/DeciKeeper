package com.zhizhi.app.worker

import android.content.Context
import android.util.Log
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.zhizhi.app.manager.AlarmScheduler


class AlarmWorker(
    context: Context,
    params: WorkerParameters
) : Worker(context, params) {

    companion object {
        private const val TAG = "AlarmWorker"
        const val KEY_ALARM_ID = "alarm_id"
    }

    override fun doWork(): Result {
        val alarmId = inputData.getInt(KEY_ALARM_ID, -1)
        if (alarmId <= 0) {
            Log.w(TAG, "Invalid alarmId: $alarmId")
            return Result.failure()
        }

        Log.i(TAG, "WorkManager alarm check: alarmId=$alarmId")

        val now = System.currentTimeMillis()
        val activeId = AlarmScheduler.getActiveAlarmId(applicationContext)

        if (activeId == alarmId) {
            Log.i(TAG, "Alarm already active (id=$alarmId), no action needed")
            return Result.success()
        }

        Log.w(TAG, "Alarm $alarmId should be active but is not! Triggering manually via WorkManager")
        AlarmScheduler.triggerAlarmFromWorkManager(applicationContext, alarmId)

        return Result.success()
    }
}
