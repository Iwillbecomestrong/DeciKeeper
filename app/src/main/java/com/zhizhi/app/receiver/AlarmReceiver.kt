package com.zhizhi.app.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.zhizhi.app.data.AndroidLogcatNormalEventSink
import com.zhizhi.app.data.DefaultLogDebugConfig
import com.zhizhi.app.data.DefaultNormalEventRenderer
import com.zhizhi.app.data.LogExporter
import com.zhizhi.app.data.NormalEventLogger
import com.zhizhi.app.data.SystemEventClock
import com.zhizhi.app.manager.AlarmProtectionEventBridge
import com.zhizhi.app.manager.AlarmScheduler
import com.zhizhi.app.manager.AlarmWindowManager
import com.zhizhi.app.service.AlarmVibrationService

/**
 * =================================================================================
 * [Context Header]
 * @file: AlarmReceiver.kt
 * @role: Domain Skill
 * @description: 闹钟广播接收器，处理闹钟触发、恢复及防恶意弹窗逻辑。
 * @responsibilities:
 * 1. 接收并分发闹钟触发/恢复广播。
 * 2. 通过 AlarmWindowManager 显示悬浮窗（替代 Activity 弹窗），避免被系统识别为恶意行为。
 * 3. 在正常触发时重置 Recovery 计数。
 * @context:
 * - 上游: AlarmManager / BootReceiver
 * - 下游: AlarmWindowManager, AlarmVibrationService, AlarmScheduler
 * @constraints (必读):
 * 1. 不再直接启动 AlarmActivity，改为调用 AlarmWindowManager.show() 显示悬浮窗。
 * 2. 保留节流机制作为防御性代码，防止极端情况下频繁触发 Service 启动。
 * 3. 保持原有闹钟业务逻辑不变，仅做最小化防御性增强。
 * 4. [NEW: 权威校验] 所有正式 MAUN 触发必须先通过数据库/token/time 校验后才能执行副作用。
 * =================================================================================
 */
class AlarmReceiver : BroadcastReceiver() {

    // Event-driven logging bridge (task-6). The receiver has no authority over the
    // bridge decision — it only records the completed trigger / ring-start outcome
    // after the existing validation, throttling, and AlarmManager-driven effects succeed.
    private val alarmProtectionNormalEventLogger by lazy {
        NormalEventLogger(
            renderer = DefaultNormalEventRenderer,
            sinks = listOf(LogExporter.normalEventSink(), AndroidLogcatNormalEventSink())
        )
    }
    private val alarmProtectionEventBridge by lazy {
        AlarmProtectionEventBridge(
            clock = SystemEventClock,
            emit = alarmProtectionNormalEventLogger::emit,
            debugConfig = DefaultLogDebugConfig
        )
    }

    companion object {
        // 节流窗口：2000ms 内不重复启动 AlarmActivity / startForegroundService
        private const val TRIGGER_THROTTLE_MS = 2000L
        @Volatile
        @JvmField
        var lastTriggerTime: Long = 0L
        @Volatile
        @JvmField
        var lastTriggeredAlarmId: Int = -1

        /** 允许提前触发的容错窗口（毫秒），防止因系统时钟微小偏差误拒 */
        private const val ALLOWED_EARLY_TOLERANCE_MS = 5 * 60 * 1000L  // 5 分钟
        /** 允许延迟到达的容错窗口（毫秒），防止因 Doze/AppStandby 延迟投递误拒 */
        private const val ALLOWED_LATE_TOLERANCE_MS = 60 * 60 * 1000L   // 60 分钟
    }

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        val receivedAlarmId = intent.getIntExtra(AlarmScheduler.EXTRA_ALARM_ID, -1)
        val receivedKind = intent.getStringExtra(AlarmScheduler.EXTRA_INTENT_KIND) ?: "UNKNOWN"
        val receivedToken = intent.getStringExtra(AlarmScheduler.EXTRA_SCHEDULE_TOKEN).orEmpty()
        val receivedScheduledAt = intent.getLongExtra(AlarmScheduler.EXTRA_SCHEDULED_AT_MILLIS, 0L)
        val receivedAt = System.currentTimeMillis()
        LogExporter.logEvent(
            "AlarmReceiver",
            "[ALARM_RECEIVED] action=$action id=$receivedAlarmId kind=$receivedKind token=${receivedToken.take(8)} scheduledAt=$receivedScheduledAt receivedAt=$receivedAt"
        )

        // ----- 预热闹钟：提前唤醒进程，确保主闹钟到达时 App 已在内存中 -----
        if (action == AlarmScheduler.ACTION_ALARM_PRE_WARM) {
            val alarmId = intent.getIntExtra(AlarmScheduler.EXTRA_ALARM_ID, -1)
            Log.i("AlarmReceiver", "Pre-warm alarm received: alarmId=$alarmId")
            LogExporter.i("Alarm", "🔥 预热保活 | id=$alarmId | mode=MIN (无 fullScreenIntent, 避免 Vivo 提前响)")
            AlarmVibrationService.startPreWarm(context, alarmId)
            return
        }

        // ----- 兜底闹钟：主闹钟延迟时的最后保障 -----
        if (action == AlarmScheduler.ACTION_ALARM_BACKUP || action == AlarmScheduler.ACTION_ALARM_BACKUP2) {
            val alarmId = intent.getIntExtra(AlarmScheduler.EXTRA_ALARM_ID, -1)
            if (AlarmScheduler.isAlarmActive(context)) {
                Log.i("AlarmReceiver", "Backup alarm: main alarm already active (alarmId=$alarmId), skipping")
                LogExporter.logEvent("AlarmOverlay", "BACKUP_SKIP_ALREADY_ACTIVE id=$alarmId")
                return
            }
            // 备份闹钟也做权威校验
            val validateResult = validateAlarmTrigger(context, alarmId, intent)
            if (!validateResult.accepted) {
                Log.w("AlarmReceiver", "Backup alarm: validation failed (alarmId=$alarmId, reason=${validateResult.reason})")
                LogExporter.logEvent("AlarmReceiver", "BACKUP_REJECTED id=$alarmId reason=${validateResult.reason} detail=${validateResult.detail}")
                return
            }
            Log.w("AlarmReceiver", "Backup alarm: main alarm NOT active, triggering backup (alarmId=$alarmId)")
            AlarmScheduler.setAlarmActive(context, alarmId)
            LogExporter.logEvent("AlarmRecovery", "Backup alarm triggered (id=$alarmId)")
            alarmProtectionEventBridge.recordAlarmRecovery(
                alarmId = alarmId.toString(),
                recoveryAction = "BACKUP_TRIGGER"
            )
            AlarmVibrationService.start(context, alarmId)
            AlarmWindowManager.show(context, alarmId)
            return
        }

        // ----- 恢复闹钟：重启后自动恢复震动（不解锁也能触发）-----
        if (action == AlarmScheduler.ACTION_ALARM_RECOVERY) {
            val alarmId = intent.getIntExtra(AlarmScheduler.EXTRA_ALARM_ID, -1)
            val recoveryCount = AlarmScheduler.getRecoveryCount(context, alarmId)
            if (recoveryCount >= AlarmScheduler.MAX_RECOVERY_COUNT) {
                Log.w("AlarmReceiver", "Recovery alarm: max recovery count reached ($recoveryCount), skipping (alarmId=$alarmId)")
                AlarmScheduler.cancelRecoveryAlarm(context)
                AlarmScheduler.resetRecoveryCount(context, alarmId)
                return
            }
            if (AlarmScheduler.isAlarmActive(context)) {
                Log.i("AlarmReceiver", "Recovery alarm: alarm active, starting vibration (alarmId=$alarmId)")
                val vibrateIntent = Intent(context, AlarmVibrationService::class.java).apply {
                    putExtra(AlarmVibrationService.EXTRA_ALARM_ID, alarmId)
                }
                context.startForegroundService(vibrateIntent)
                if (AlarmVibrationService.isRunning) {
                    Log.i("AlarmReceiver", "Recovery alarm: service confirmed running, cancelling recovery (insurance done)")
                    AlarmScheduler.cancelRecoveryAlarm(context)
                    AlarmScheduler.resetRecoveryCount(context, alarmId)
                } else {
                    AlarmScheduler.scheduleRecoveryAlarm(context, alarmId)
                }
            } else {
                Log.i("AlarmReceiver", "Recovery alarm: alarm NOT active, skipping (alarmId=$alarmId)")
            }
            return
        }

        // ----- 重启安全网：Direct Boot 阶段触发 -----
        if (action == AlarmScheduler.ACTION_ALARM_REBOOT_SAFETY_NET) {
            val alarmId = intent.getIntExtra(AlarmScheduler.EXTRA_ALARM_ID, -1)
            if (AlarmScheduler.isAlarmActive(context)) {
                Log.i("AlarmReceiver", "RebootSafetyNet: alarm active, starting vibration (alarmId=$alarmId)")
                val vibrateIntent = Intent(context, AlarmVibrationService::class.java).apply {
                    putExtra(AlarmVibrationService.EXTRA_ALARM_ID, alarmId)
                }
                context.startForegroundService(vibrateIntent)
            }
            return
        }

        // ----- 正常闹钟触发 / 休眠 -----
        if (action != AlarmScheduler.ACTION_ALARM_TRIGGER && action != AlarmScheduler.ACTION_ALARM_SNOOZE) return
        val alarmId = intent.getIntExtra(AlarmScheduler.EXTRA_ALARM_ID, -1)

        // [防御性代码] 节流检查：只对同一个 alarmId 做防抖，不同闹钟不互相节流
        val now = System.currentTimeMillis()
        val timeSinceLastTrigger = now - lastTriggerTime
        if (alarmId == lastTriggeredAlarmId && timeSinceLastTrigger < TRIGGER_THROTTLE_MS && lastTriggerTime > 0) {
            Log.w("AlarmReceiver", "Alarm trigger throttled for same id: alarmId=$alarmId, lastTrigger=${timeSinceLastTrigger}ms ago")
            LogExporter.logEvent("AlarmOverlay", "TRIGGER_THROTTLED id=$alarmId since=${timeSinceLastTrigger}ms")
            return
        }
        lastTriggerTime = now
        lastTriggeredAlarmId = alarmId

        if (action == AlarmScheduler.ACTION_ALARM_SNOOZE) {
            AlarmScheduler.setAlarmActive(context, alarmId)
            AlarmVibrationService.start(context, alarmId)
            LogExporter.logEvent("AlarmOverlay", "RECEIVER_SHOW_REQUEST action=$action id=$alarmId")
            AlarmWindowManager.show(context, alarmId)
            return
        }

        // ----- ACTION_ALARM_TRIGGER: 权威校验 -----
        val validateResult = validateAlarmTrigger(context, alarmId, intent)
        if (!validateResult.accepted) {
            Log.w("AlarmReceiver", "Alarm trigger REJECTED: alarmId=$alarmId, reason=${validateResult.reason}")
            LogExporter.logEvent("AlarmReceiver", "[ALARM_TRIGGER_REJECTED] alarmId=$alarmId reason=${validateResult.reason} detail=${validateResult.detail}")
            cleanupStaleAlarm(context, alarmId, validateResult.reason)
            return
        }
        LogExporter.logEvent("AlarmReceiver", "[ALARM_TRIGGER_ACCEPTED] alarmId=$alarmId token=${validateResult.alarmToken?.take(8)}")

        AlarmScheduler.resetSnoozeCount(context, alarmId)
        AlarmScheduler.resetRecoveryCount(context, alarmId)

        val actualTime = System.currentTimeMillis()
        val alarm = validateResult.alarmEntry
        if (alarm != null) {
            val scheduledTime = AlarmScheduler.calculateNextTriggerForLogging(alarm)
            val delayMs = actualTime - scheduledTime
            val triggerTimeStr = java.text.SimpleDateFormat("HH:mm:ss").format(java.util.Date(scheduledTime))
            val actualTimeStr = java.text.SimpleDateFormat("HH:mm:ss").format(java.util.Date(actualTime))
            Log.i("AlarmReceiver", "Alarm triggered: alarmId=$alarmId, scheduled=$triggerTimeStr, actual=$actualTimeStr, delayMs=$delayMs")
        }

        AlarmScheduler.setAlarmActive(context, alarmId)
        LogExporter.logEvent("AlarmRecovery", "setAlarmActive(id=$alarmId) from AlarmReceiver trigger")
        AlarmVibrationService.start(context, alarmId)
        AlarmScheduler.cancelRecoveryAlarm(context)
        AlarmScheduler.resetRecoveryCount(context, alarmId)

        alarmProtectionEventBridge.recordAlarmTrigger(
            alarmId = alarmId.toString(),
            triggerAtMs = actualTime,
            ruleId = null,
            result = "TRIGGERED"
        )
        alarmProtectionEventBridge.recordAlarmRingStart(alarmId = alarmId.toString()) // ALARM_RING_START

        LogExporter.logEvent("AlarmOverlay", "RECEIVER_SHOW_REQUEST action=$action id=$alarmId")
        AlarmWindowManager.show(context, alarmId)

        if (alarm != null && alarm.enabled) {
            AlarmScheduler.scheduleNext(context, alarm)
        }
    }

    /**
     * 权威校验广播：验证 alarm 记录是否存在、是否启用、token 是否匹配、时间是否容错。
     *
     * @return ValidateResult 包含是否通过以及原因。
     */
    private fun validateAlarmTrigger(
        context: Context,
        alarmId: Int,
        intent: Intent
    ): ValidateResult {
        val now = System.currentTimeMillis()
        val receivedToken = intent.getStringExtra(AlarmScheduler.EXTRA_SCHEDULE_TOKEN) ?: ""
        val receivedScheduledAt = intent.getLongExtra(AlarmScheduler.EXTRA_SCHEDULED_AT_MILLIS, 0L)
        val intentKind = intent.getStringExtra(AlarmScheduler.EXTRA_INTENT_KIND) ?: "UNKNOWN"
        val isBackup = intentKind == AlarmScheduler.AlarmIntentKind.BACKUP.name ||
            intentKind == AlarmScheduler.AlarmIntentKind.BACKUP2.name

        Log.i("AlarmReceiver", "validateAlarmTrigger: alarmId=$alarmId, kind=$intentKind, receivedToken=${receivedToken.take(8)}, receivedScheduledAt=$receivedScheduledAt, now=$now")

        // 1. 数据库记录是否存在
        val alarm = AlarmScheduler.findAlarm(context, alarmId)
        if (alarm == null) {
            return ValidateResult(
                accepted = false,
                reason = "NOT_FOUND",
                detail = "alarmId=$alarmId not in database"
            )
        }

        // 2. 是否启用
        if (!alarm.enabled) {
            return ValidateResult(
                accepted = false,
                reason = "DISABLED",
                detail = "alarmId=$alarmId is disabled",
                alarmEntry = alarm
            )
        }

        // 3. scheduleToken 校验。
        // 正式 MAIN 仍严格拒绝过期 token；BACKUP/BACKUP2 允许在时间窗校验通过的前提下继续，
        // 因为 scheduleNext() 可能已把数据库 token 更新，而 OEM 仍投递此前创建的兜底 PendingIntent。
        val dbToken = alarm.scheduleToken
        if (receivedToken.isBlank()) {
            return ValidateResult(
                accepted = false,
                reason = "NO_TOKEN",
                detail = "alarmId=$alarmId received empty token (old broadcast without token)",
                alarmEntry = alarm
            )
        }
        if (dbToken.isNotBlank() && receivedToken != dbToken) {
            if (!isBackup) {
                return ValidateResult(
                    accepted = false,
                    reason = "STALE_TOKEN",
                    detail = "alarmId=$alarmId receivedToken=${receivedToken.take(8)} dbToken=${dbToken.take(8)}",
                    alarmEntry = alarm
                )
            }
            Log.w(
                "AlarmReceiver",
                "Backup stale token tolerated pending time validation: alarmId=$alarmId kind=$intentKind received=${receivedToken.take(8)} db=${dbToken.take(8)}"
            )
            LogExporter.logEvent(
                "AlarmReceiver",
                "[ALARM_BACKUP_STALE_TOKEN_TOLERATED] id=$alarmId kind=$intentKind received=${receivedToken.take(8)} db=${dbToken.take(8)}"
            )
        }

        // 4. scheduledAt 容错检查
        if (receivedScheduledAt > 0) {
            val deltaMs = now - receivedScheduledAt
            if (deltaMs < -ALLOWED_EARLY_TOLERANCE_MS) {
                return ValidateResult(
                    accepted = false,
                    reason = "TOO_EARLY",
                    detail = "alarmId=$alarmId receivedScheduledAt=$receivedScheduledAt now=$now deltaMs=$deltaMs",
                    alarmEntry = alarm
                )
            }
            if (deltaMs > ALLOWED_LATE_TOLERANCE_MS) {
                return ValidateResult(
                    accepted = false,
                    reason = "TOO_LATE",
                    detail = "alarmId=$alarmId receivedScheduledAt=$receivedScheduledAt now=$now deltaMs=$deltaMs",
                    alarmEntry = alarm
                )
            }
        }

        return ValidateResult(
            accepted = true,
            reason = "OK",
            alarmEntry = alarm,
            alarmToken = alarm.scheduleToken
        )
    }

    /**
     * 清理被拒绝的旧闹钟残留状态：
     * - 关闭悬浮窗（如果显示）
     * - 停止振动服务
     * - 清除 active 状态
     */
    private fun cleanupStaleAlarm(context: Context, alarmId: Int, reason: String) {
        try {
            AlarmVibrationService.stop(context)
            AlarmScheduler.clearAlarmActive(context)
            AlarmWindowManager.dismiss()
            Log.i("AlarmReceiver", "cleanupStaleAlarm: cleaned up alarmId=$alarmId (reason=$reason)")
            LogExporter.logEvent("AlarmRecovery", "STALE_CLEANUP id=$alarmId reason=$reason")
        } catch (e: Exception) {
            Log.w("AlarmReceiver", "cleanupStaleAlarm failed: ${e.message}")
        }
    }

    private data class ValidateResult(
        val accepted: Boolean,
        val reason: String,
        val detail: String = "",
        val alarmEntry: AlarmScheduler.AlarmEntry? = null,
        val alarmToken: String? = null
    )
}
