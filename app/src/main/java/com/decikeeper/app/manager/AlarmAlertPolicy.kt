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
    val fixedRingVolume: Int,
    /** 渐进模式下声音爬升的上限档（1-10）；10 = 不限制，与未设置时完全一致。 */
    val progressiveMaxRingVolume: Int = AlarmAlertPolicy.MAX_LEVEL,
    /** 渐进模式下震动爬升的上限档（1-10）；10 = 不限制。 */
    val progressiveMaxVibrationIntensity: Int = AlarmAlertPolicy.MAX_LEVEL
)

object AlarmAlertPolicy {
    const val PROGRESSIVE_STEP_MS = 30_000L
    private const val MIN_LEVEL = 1
    const val MAX_LEVEL = 10

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

    /** 固定模式由通道控制音量，不再额外压低播放器；渐进模式仍使用平方增益。 */
    fun ringGain(level: Float, progressive: Boolean = false): Float {
        val v = level.coerceIn(MIN_LEVEL.toFloat(), MAX_LEVEL.toFloat()) / MAX_LEVEL
        return if (progressive) v * v else 1f
    }

    /**
     * 实际用于换算声音增益的档位（浮点）。渐进的 10 步均匀铺在「第 1 档 → 最大声音」之间：
     * `1 + (max − 1) × (步号 − 1) / 9`；max = 10 时恰好等于步号。固定模式直接用固定档。
     * [ringLevel] 仍返回步号，仅用于界面显示。
     */
    fun ringLevelValue(policy: StageAlertPolicy, elapsedMs: Long): Float {
        if (policy.vibrationMode != AlarmScheduler.VibrationMode.PROGRESSIVE) {
            return policy.fixedRingVolume.coerceIn(MIN_LEVEL, MAX_LEVEL).toFloat()
        }
        return scaledProgressiveLevel(elapsedMs, policy.progressiveMaxRingVolume)
    }

    /** 震动对应的 [ringLevelValue]：渐进的 10 步铺在「第 1 档 → 最大震动」之间，固定模式直接用固定档。 */
    fun vibrationLevelValue(policy: StageAlertPolicy, elapsedMs: Long): Float {
        if (policy.vibrationMode != AlarmScheduler.VibrationMode.PROGRESSIVE) {
            return policy.fixedVibrationIntensity.coerceIn(MIN_LEVEL, MAX_LEVEL).toFloat()
        }
        return scaledProgressiveLevel(elapsedMs, policy.progressiveMaxVibrationIntensity)
    }

    /** 档位(1-10，可为浮点) → 震动振幅 1-255；真实震动与滑块试震共用，保证一致。 */
    fun vibrationAmplitude(level: Float): Int = (level * 25.5).toInt().coerceIn(1, 255)

    private fun scaledProgressiveLevel(elapsedMs: Long, rawMax: Int): Float {
        val step = progressiveLevel(elapsedMs)
        val max = rawMax.coerceIn(MIN_LEVEL, MAX_LEVEL)
        return MIN_LEVEL + ((max - MIN_LEVEL) * (step - MIN_LEVEL)).toFloat() / (MAX_LEVEL - MIN_LEVEL)
    }
}
