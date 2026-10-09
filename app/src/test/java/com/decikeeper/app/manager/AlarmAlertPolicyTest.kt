package com.decikeeper.app.manager

import com.decikeeper.app.manager.AlarmAlertPolicy
import com.decikeeper.app.manager.AlarmScheduler
import com.decikeeper.app.manager.StageAlertPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AlarmAlertPolicyTest {

    @Test
    fun progressiveLevel_startsAtOneAndIncreasesEveryThirtySeconds() {
        assertEquals(1, AlarmAlertPolicy.progressiveLevel(-1L))
        assertEquals(1, AlarmAlertPolicy.progressiveLevel(0L))
        assertEquals(1, AlarmAlertPolicy.progressiveLevel(29_999L))
        assertEquals(2, AlarmAlertPolicy.progressiveLevel(30_000L))
        assertEquals(2, AlarmAlertPolicy.progressiveLevel(59_999L))
        assertEquals(3, AlarmAlertPolicy.progressiveLevel(60_000L))
    }

    @Test
    fun progressiveLevel_reachesTenNaturallyAndThenStaysThere() {
        assertEquals(8, AlarmAlertPolicy.progressiveLevel(239_999L))
        assertEquals(9, AlarmAlertPolicy.progressiveLevel(240_000L))
        assertEquals(10, AlarmAlertPolicy.progressiveLevel(270_000L))
        assertEquals(10, AlarmAlertPolicy.progressiveLevel(900_000L))
    }

    @Test
    fun stageTwo_inheritsStageOneUnlessIndependentPolicyIsEnabled() {
        val stage1 = StageAlertPolicy(
            alarmMode = AlarmScheduler.AlarmMode.VIBRATE_AND_RING,
            vibrationMode = AlarmScheduler.VibrationMode.PROGRESSIVE,
            fixedVibrationIntensity = 7,
            fixedRingVolume = 7
        )
        val independent = StageAlertPolicy(
            alarmMode = AlarmScheduler.AlarmMode.RING_ONLY,
            vibrationMode = AlarmScheduler.VibrationMode.FIXED,
            fixedVibrationIntensity = 4,
            fixedRingVolume = 5
        )

        assertEquals(stage1, AlarmAlertPolicy.resolveStagePolicy(stage1, false, independent))
        assertEquals(stage1, AlarmAlertPolicy.resolveStagePolicy(stage1, true, null))
        assertEquals(independent, AlarmAlertPolicy.resolveStagePolicy(stage1, true, independent))
    }

    @Test
    fun effectiveLevel_usesProgressiveRampOrFixedStrength() {
        val progressive = StageAlertPolicy(
            alarmMode = AlarmScheduler.AlarmMode.VIBRATE_AND_RING,
            vibrationMode = AlarmScheduler.VibrationMode.PROGRESSIVE,
            fixedVibrationIntensity = 8,
            fixedRingVolume = 6
        )
        val fixed = progressive.copy(vibrationMode = AlarmScheduler.VibrationMode.FIXED)

        assertEquals(1, AlarmAlertPolicy.vibrationLevel(progressive, 0L))
        assertEquals(4, AlarmAlertPolicy.vibrationLevel(progressive, 90_000L))
        assertEquals(4, AlarmAlertPolicy.ringLevel(progressive, 90_000L))
        assertEquals(8, AlarmAlertPolicy.vibrationLevel(fixed, 90_000L))
        assertEquals(6, AlarmAlertPolicy.ringLevel(fixed, 90_000L))
    }

    private fun ringPolicy(max: Int, mode: AlarmScheduler.VibrationMode = AlarmScheduler.VibrationMode.PROGRESSIVE) =
        StageAlertPolicy(
            alarmMode = AlarmScheduler.AlarmMode.VIBRATE_AND_RING,
            vibrationMode = mode,
            fixedVibrationIntensity = 8,
            fixedRingVolume = 6,
            progressiveMaxRingVolume = max
        )

    @Test
    fun ringLevelValue_withDefaultMaxMatchesTheOldRampExactly() {
        val policy = ringPolicy(10)
        for (step in 1..10) {
            val elapsed = (step - 1) * AlarmAlertPolicy.PROGRESSIVE_STEP_MS
            assertEquals(step.toFloat(), AlarmAlertPolicy.ringLevelValue(policy, elapsed), 0f)
        }
        assertEquals(10f, AlarmAlertPolicy.ringLevelValue(policy, 900_000L), 0f)
    }

    @Test
    fun ringLevelValue_spreadsTenStepsBetweenLevelOneAndTheMax() {
        val policy = ringPolicy(5)
        assertEquals(1f, AlarmAlertPolicy.ringLevelValue(policy, 0L), 1e-6f)
        assertEquals(1f + 4f / 9f, AlarmAlertPolicy.ringLevelValue(policy, 30_000L), 1e-6f)
        assertEquals(5f, AlarmAlertPolicy.ringLevelValue(policy, 270_000L), 1e-6f)
        assertEquals(5f, AlarmAlertPolicy.ringLevelValue(policy, 900_000L), 1e-6f)
        var previous = 0f
        for (step in 1..10) {
            val value = AlarmAlertPolicy.ringLevelValue(policy, (step - 1) * AlarmAlertPolicy.PROGRESSIVE_STEP_MS)
            assertTrue("step $step must rise", value > previous)
            previous = value
        }
    }

    @Test
    fun ringLevelValue_maxIsClampedToOneThroughTen() {
        assertEquals(10f, AlarmAlertPolicy.ringLevelValue(ringPolicy(99), 900_000L), 0f)
        assertEquals(1f, AlarmAlertPolicy.ringLevelValue(ringPolicy(-3), 900_000L), 0f)
    }

    @Test
    fun ringLevelValue_fixedModeIgnoresTheMax() {
        val fixed = ringPolicy(3, AlarmScheduler.VibrationMode.FIXED)
        assertEquals(6f, AlarmAlertPolicy.ringLevelValue(fixed, 0L), 0f)
        assertEquals(6f, AlarmAlertPolicy.ringLevelValue(fixed, 900_000L), 0f)
    }

    @Test
    fun ringLevel_displayStepIsNotAffectedByTheMax() {
        assertEquals(4, AlarmAlertPolicy.ringLevel(ringPolicy(5), 90_000L))
        assertEquals(10, AlarmAlertPolicy.ringLevel(ringPolicy(5), 900_000L))
    }

    @Test
    fun ringGain_isTheCubicCurveAndClampsOutOfRangeLevels() {
        assertEquals(0.001f, AlarmAlertPolicy.ringGain(1f), 1e-6f)
        assertEquals(0.125f, AlarmAlertPolicy.ringGain(5f), 1e-6f)
        assertEquals(1f, AlarmAlertPolicy.ringGain(10f), 0f)
        assertEquals(0.001f, AlarmAlertPolicy.ringGain(-2f), 1e-6f)
        assertEquals(1f, AlarmAlertPolicy.ringGain(42f), 0f)
    }

    @Test
    fun vibrationAmplitude_isLevelTimesTwentyFivePointFiveClampedToOneThroughTwoFiftyFive() {
        assertEquals(25, AlarmAlertPolicy.vibrationAmplitude(1f))
        assertEquals(127, AlarmAlertPolicy.vibrationAmplitude(5f))
        assertEquals(255, AlarmAlertPolicy.vibrationAmplitude(10f))
        assertEquals(1, AlarmAlertPolicy.vibrationAmplitude(0f))
        assertEquals(255, AlarmAlertPolicy.vibrationAmplitude(99f))
    }

    @Test
    fun vibrationLevel_isNotAffectedByTheRingMax() {
        assertEquals(4, AlarmAlertPolicy.vibrationLevel(ringPolicy(2), 90_000L))
    }

    private fun vibePolicy(max: Int, mode: AlarmScheduler.VibrationMode = AlarmScheduler.VibrationMode.PROGRESSIVE) =
        ringPolicy(10, mode).copy(progressiveMaxVibrationIntensity = max)

    @Test
    fun vibrationLevelValue_withDefaultMaxMatchesTheOldRampExactly() {
        val policy = vibePolicy(10)
        for (step in 1..10) {
            val elapsed = (step - 1) * AlarmAlertPolicy.PROGRESSIVE_STEP_MS
            assertEquals(step.toFloat(), AlarmAlertPolicy.vibrationLevelValue(policy, elapsed), 0f)
        }
    }

    @Test
    fun vibrationLevelValue_spreadsTenStepsBetweenLevelOneAndTheMax() {
        val policy = vibePolicy(4)
        assertEquals(1f, AlarmAlertPolicy.vibrationLevelValue(policy, 0L), 1e-6f)
        assertEquals(2f, AlarmAlertPolicy.vibrationLevelValue(policy, 90_000L), 1e-6f)
        assertEquals(4f, AlarmAlertPolicy.vibrationLevelValue(policy, 270_000L), 1e-6f)
        assertEquals(4f, AlarmAlertPolicy.vibrationLevelValue(policy, 900_000L), 1e-6f)
    }

    @Test
    fun vibrationLevelValue_fixedModeIgnoresTheMaxAndMaxIsClamped() {
        assertEquals(8f, AlarmAlertPolicy.vibrationLevelValue(vibePolicy(2, AlarmScheduler.VibrationMode.FIXED), 0L), 0f)
        assertEquals(10f, AlarmAlertPolicy.vibrationLevelValue(vibePolicy(77), 900_000L), 0f)
        assertEquals(1f, AlarmAlertPolicy.vibrationLevelValue(vibePolicy(-1), 900_000L), 0f)
    }

    @Test
    fun ringLevelValue_isNotAffectedByTheVibrationMax() {
        assertEquals(10f, AlarmAlertPolicy.ringLevelValue(vibePolicy(2), 900_000L), 0f)
    }
}
