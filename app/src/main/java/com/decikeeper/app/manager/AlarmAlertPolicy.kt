package com.decikeeper.app.manager

/**
 * Pure reminder-policy seam shared by alarm stages and Android output services.
 *
 * Progressive reminders always start at level 1 and advance one level every 30 seconds
 * until level 10. Stage 2 inherits stage 1 unless an independent policy is explicitly enabled.
 */
data class StageAlertPolicy(
    val alarmMode: AlarmScheduler.AlarmMode,
    val vibrationMode: AlarmScheduler.VibrationMode,
    val fixedVibrationIntensity: Int,
    val fixedRingVolume: Int
)

object AlarmAlertPolicy {
    const val PROGRESSIVE_STEP_MS = 30_000L
    private const val MIN_LEVEL = 1
    private const val MAX_LEVEL = 10

    fun progressiveLevel(elapsedMs: Long): Int {
        val safeElapsed = elapsedMs.coerceAtLeast(0L)
        return (MIN_LEVEL + safeElapsed / PROGRESSIVE_STEP_MS)
            .toInt()
            .coerceIn(MIN_LEVEL, MAX_LEVEL)
    }

    fun resolveStagePolicy(
        stage1: StageAlertPolicy,
        useIndependentStage2: Boolean,
        stage2: StageAlertPolicy?
    ): StageAlertPolicy {
        return if (useIndependentStage2 && stage2 != null) stage2 else stage1
    }

    fun vibrationLevel(policy: StageAlertPolicy, elapsedMs: Long): Int {
        return if (policy.vibrationMode == AlarmScheduler.VibrationMode.PROGRESSIVE) {
            progressiveLevel(elapsedMs)
        } else {
            policy.fixedVibrationIntensity.coerceIn(MIN_LEVEL, MAX_LEVEL)
        }
    }

    fun ringLevel(policy: StageAlertPolicy, elapsedMs: Long): Int {
        return if (policy.vibrationMode == AlarmScheduler.VibrationMode.PROGRESSIVE) {
            progressiveLevel(elapsedMs)
        } else {
            policy.fixedRingVolume.coerceIn(MIN_LEVEL, MAX_LEVEL)
        }
    }
}
