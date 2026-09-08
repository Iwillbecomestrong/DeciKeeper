package com.zhizhi.app

import com.zhizhi.app.manager.AlarmScheduler
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AlarmStage2AlertPolicyPersistenceTest {

    @Test
    fun legacyJson_withoutStageTwoAlertFields_inheritsStageOne() {
        val legacy = JSONObject().apply {
            put("id", 7)
            put("hour", 6)
            put("minute", 30)
            put("enabled", true)
            put("vibrationIntensity", 8)
            put("vibrationMode", AlarmScheduler.VibrationMode.FIXED.name)
            put("alarmMode", AlarmScheduler.AlarmMode.RING_ONLY.name)
            put("ringVolume", 6)
        }

        val alarm = AlarmScheduler.fromJson(legacy)

        assertFalse(alarm.stage2UseIndependentAlertPolicy)
        assertEquals(AlarmScheduler.AlarmMode.RING_ONLY, alarm.alarmMode)
        assertEquals(AlarmScheduler.VibrationMode.FIXED, alarm.vibrationMode)
        assertEquals(8, alarm.vibrationIntensity)
        assertEquals(6, alarm.ringVolume)
    }

    @Test
    fun newJson_roundTripsIndependentStageTwoAlertPolicy() {
        val original = AlarmScheduler.AlarmEntry(
            id = 9,
            hour = 7,
            minute = 15,
            enabled = true,
            days = BooleanArray(7) { true },
            vibrationIntensity = 7,
            vibrationMode = AlarmScheduler.VibrationMode.PROGRESSIVE,
            alarmMode = AlarmScheduler.AlarmMode.VIBRATE_AND_RING,
            ringVolume = 7,
            stage2UseIndependentAlertPolicy = true,
            stage2AlarmMode = AlarmScheduler.AlarmMode.RING_ONLY,
            stage2VibrationMode = AlarmScheduler.VibrationMode.FIXED,
            stage2VibrationIntensity = 4,
            stage2RingVolume = 5
        )

        val restored = AlarmScheduler.fromJson(AlarmScheduler.toJson(original))

        assertTrue(restored.stage2UseIndependentAlertPolicy)
        assertEquals(AlarmScheduler.AlarmMode.RING_ONLY, restored.stage2AlarmMode)
        assertEquals(AlarmScheduler.VibrationMode.FIXED, restored.stage2VibrationMode)
        assertEquals(4, restored.stage2VibrationIntensity)
        assertEquals(5, restored.stage2RingVolume)
    }
}
