package com.zhizhi.app

import com.zhizhi.app.manager.AlarmAlertPolicy
import com.zhizhi.app.manager.AlarmScheduler
import com.zhizhi.app.manager.StageAlertPolicy
import org.junit.Assert.assertEquals
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
}
