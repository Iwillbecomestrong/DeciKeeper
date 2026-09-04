package com.zhizhi.app.manager

import com.zhizhi.app.data.DefaultLogDebugConfig
import com.zhizhi.app.data.EventClock
import com.zhizhi.app.data.LogDebugConfig
import com.zhizhi.app.data.NormalEventDraft
import com.zhizhi.app.data.NormalEvents




/**
 * Alarm, protection, and service-state outcome bridge. The bridge holds no authority
 * over alarm scheduling, ringtone playback, overlay alpha, notification behavior,
 * protection enforcement, or accessibility decisions. Production callers hand a
 * completed fact (alarm scheduled, alarm triggered, ring started/ended, overlay
 * shown/hidden, challenge step result, snooze/dismiss, watchdog recovery, or
 * protection/service state transition), and the bridge emits one NormalEventDraft
 * so export and logcat share the same record.
 *
 * Emitted types follow the task-6 contract of the event-driven log noise reduction
 * plan:
 *   ALARM_SCHEDULE, ALARM_TRIGGER, ALARM_RING_START, ALARM_RING_END,
 *   ALARM_OVERLAY_SHOW, ALARM_OVERLAY_HIDE, ALARM_CHALLENGE_START,
 *   ALARM_CHALLENGE_RESULT, ALARM_SNOOZE, ALARM_DISMISS, ALARM_RECOVERY,
 *   ALARM_WATCHDOG, PROTECTION_STATE_CHANGE, SERVICE_STATE, BYPASS_RISK.
 */
class AlarmProtectionEventBridge(
    private val clock: EventClock,
    private val emit: (NormalEventDraft) -> Unit,
    private val debugConfig: LogDebugConfig = DefaultLogDebugConfig
) {

    fun recordAlarmSchedule(
        alarmId: String,
        scheduledAtMs: Long,
        ruleId: String? = null,
        result: String
    ) {
        val fields = LinkedHashMap<String, String>()
        fields["alarmId"] = alarmId
        fields["scheduledAt"] = scheduledAtMs.toString()
        ruleId?.let { fields["ruleId"] = it }
        fields["result"] = result
        emit(
            NormalEvents.create(
                type = "ALARM_SCHEDULE",
                clock = clock,
                fields = fields
            )
        )
    }

    fun recordAlarmTrigger(
        alarmId: String,
        triggerAtMs: Long,
        ruleId: String? = null,
        result: String
    ) {
        val fields = LinkedHashMap<String, String>()
        fields["alarmId"] = alarmId
        fields["triggerAt"] = triggerAtMs.toString()
        ruleId?.let { fields["ruleId"] = it }
        fields["result"] = result
        emit(
            NormalEvents.create(
                type = "ALARM_TRIGGER",
                clock = clock,
                fields = fields
            )
        )
    }

    fun recordAlarmRingStart(alarmId: String) {
        emit(
            NormalEvents.create(
                type = "ALARM_RING_START",
                clock = clock,
                fields = linkedMapOf("alarmId" to alarmId)
            )
        )
    }

    fun recordAlarmRingEnd(alarmId: String, reason: String, durationMs: String) {
        emit(
            NormalEvents.create(
                type = "ALARM_RING_END",
                clock = clock,
                fields = linkedMapOf(
                    "alarmId" to alarmId,
                    "reason" to reason,
                    "durationMs" to durationMs
                )
            )
        )
    }

    fun recordAlarmOverlayShow(alarmId: String) {
        emit(
            NormalEvents.create(
                type = "ALARM_OVERLAY_SHOW",
                clock = clock,
                fields = linkedMapOf("alarmId" to alarmId)
            )
        )
    }

    /**
     * Overlay hide can come from dismiss, scan, or permission; reason is the caller-classified
     * exit cause so a pure refresh or transient focus change never promotes itself to a hide event.
     */
    fun recordAlarmOverlayHide(alarmId: String, reason: String) {
        emit(
            NormalEvents.create(
                type = "ALARM_OVERLAY_HIDE",
                clock = clock,
                fields = linkedMapOf(
                    "alarmId" to alarmId,
                    "reason" to reason
                )
            )
        )
    }

    fun recordAlarmChallengeStart(alarmId: String, challengeId: String, totalSteps: Int) {
        emit(
            NormalEvents.create(
                type = "ALARM_CHALLENGE_START",
                clock = clock,
                fields = linkedMapOf(
                    "alarmId" to alarmId,
                    "challengeId" to challengeId,
                    "totalSteps" to totalSteps.toString()
                )
            )
        )
    }

    /**
     * Per-step challenge outcome. result must be one of CORRECT, WRONG, FINAL_CORRECT, FINAL_WRONG.
     * The bridge never sees the question, answer, picked option, or window content; it only carries
     * identifiers and cumulative progress so privacy + sensitive-content rules are preserved.
     */
    fun recordAlarmChallengeResult(
        alarmId: String,
        challengeId: String,
        step: Int,
        result: String,
        cumulativeProgress: Int,
        totalSteps: Int
    ) {
        emit(
            NormalEvents.create(
                type = "ALARM_CHALLENGE_RESULT",
                clock = clock,
                fields = linkedMapOf(
                    "alarmId" to alarmId,
                    "challengeId" to challengeId,
                    "step" to step.toString(),
                    "result" to result,
                    "progress" to cumulativeProgress.toString(),
                    "totalSteps" to totalSteps.toString()
                )
            )
        )
    }

    fun recordAlarmSnooze(alarmId: String, snoozeIndex: Int, snoozeUntilMs: Long) {
        emit(
            NormalEvents.create(
                type = "ALARM_SNOOZE",
                clock = clock,
                fields = linkedMapOf(
                    "alarmId" to alarmId,
                    "snoozeIndex" to snoozeIndex.toString(),
                    "snoozeUntil" to snoozeUntilMs.toString()
                )
            )
        )
    }

    fun recordAlarmDismiss(alarmId: String, method: String) {
        emit(
            NormalEvents.create(
                type = "ALARM_DISMISS",
                clock = clock,
                fields = linkedMapOf(
                    "alarmId" to alarmId,
                    "method" to method
                )
            )
        )
    }

    fun recordAlarmRecovery(alarmId: String, recoveryAction: String) {
        emit(
            NormalEvents.create(
                type = "ALARM_RECOVERY",
                clock = clock,
                fields = linkedMapOf(
                    "alarmId" to alarmId,
                    "recoveryAction" to recoveryAction
                )
            )
        )
    }

    /**
     * Watchdog idle heartbeat must stay silent; only an actual recovery action promotes itself
     * to a real event.
     */
    fun recordAlarmWatchdog(action: String) {
        emit(
            NormalEvents.create(
                type = "ALARM_WATCHDOG",
                clock = clock,
                fields = linkedMapOf("action" to action)
            )
        )
    }

    fun recordProtectionStateChange(
        component: String,
        oldState: String,
        newState: String,
        reason: String
    ) {
        emit(
            NormalEvents.create(
                type = "PROTECTION_STATE_CHANGE",
                clock = clock,
                fields = linkedMapOf(
                    "component" to component,
                    "oldState" to oldState,
                    "newState" to newState,
                    "reason" to reason
                )
            )
        )
    }

    fun recordServiceState(
        serviceName: String,
        oldState: String,
        newState: String,
        reason: String
    ) {
        emit(
            NormalEvents.create(
                type = "SERVICE_STATE",
                clock = clock,
                fields = linkedMapOf(
                    "serviceName" to serviceName,
                    "oldState" to oldState,
                    "newState" to newState,
                    "reason" to reason
                )
            )
        )
    }

    fun recordBypassRisk(riskType: String, evidence: String, actionTaken: String) {
        emit(
            NormalEvents.create(
                type = "BYPASS_RISK",
                clock = clock,
                fields = linkedMapOf(
                    "riskType" to riskType,
                    "evidence" to evidence,
                    "actionTaken" to actionTaken
                )
            )
        )
    }
}
