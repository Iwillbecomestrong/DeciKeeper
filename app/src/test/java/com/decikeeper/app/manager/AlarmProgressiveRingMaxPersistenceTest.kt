package com.decikeeper.app.manager

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class AlarmProgressiveRingMaxPersistenceTest {

    private fun entry(max: Int = 10, stage2Max: Int = 10) = AlarmScheduler.AlarmEntry(
        id = 3,
        hour = 6,
        minute = 30,
        enabled = true,
        days = BooleanArray(7) { true },
        progressiveMaxRingVolume = max,
        stage2ProgressiveMaxRingVolume = stage2Max
    )

    @Test
    fun json_roundTripsFirstAndSecondStageMax() {
        val restored = AlarmScheduler.fromJson(AlarmScheduler.toJson(entry(max = 4, stage2Max = 6)))

        assertEquals(4, restored.progressiveMaxRingVolume)
        assertEquals(6, restored.stage2ProgressiveMaxRingVolume)
    }

    @Test
    fun legacyJson_withoutTheNewKeys_meansNoLimit() {
        val legacy = JSONObject().apply {
            put("id", 1)
            put("hour", 7)
            put("minute", 0)
            put("enabled", true)
        }

        val alarm = AlarmScheduler.fromJson(legacy)

        assertEquals(10, alarm.progressiveMaxRingVolume)
        assertEquals(10, alarm.stage2ProgressiveMaxRingVolume)
    }

    @Test
    fun legacyStaleKey_isNeverReadBack() {
        // 2026-09-08 重设计前的版本可能存过非 10 的旧值；启用新功能时不得让这些旧闹钟突然变轻。
        val stale = JSONObject().apply {
            put("id", 1)
            put("hour", 7)
            put("minute", 0)
            put("enabled", true)
            put("progressiveMaxRingVolume", 3)
        }

        assertEquals(10, AlarmScheduler.fromJson(stale).progressiveMaxRingVolume)
    }

    @Test
    fun stageTwoMax_inheritsStageOneWhenAbsent() {
        val partial = JSONObject().apply {
            put("id", 1)
            put("hour", 7)
            put("minute", 0)
            put("enabled", true)
            put("progressiveRingMax", 5)
        }

        val alarm = AlarmScheduler.fromJson(partial)

        assertEquals(5, alarm.progressiveMaxRingVolume)
        assertEquals(5, alarm.stage2ProgressiveMaxRingVolume)
    }

    @Test
    fun json_clampsOutOfRangeValues() {
        val json = AlarmScheduler.toJson(entry()).apply {
            put("progressiveRingMax", 99)
            put("stage2ProgressiveRingMax", -4)
        }

        val alarm = AlarmScheduler.fromJson(json)

        assertEquals(10, alarm.progressiveMaxRingVolume)
        assertEquals(1, alarm.stage2ProgressiveMaxRingVolume)
    }

    @Test
    fun json_doesNotWriteTheStaleKeyAnymore() {
        assertFalse(AlarmScheduler.toJson(entry(max = 4)).has("progressiveMaxRingVolume"))
        assertFalse(AlarmScheduler.toJson(entry(max = 4)).has("progressiveMaxIntensity"))
    }

    @Test
    fun json_roundTripsFirstAndSecondStageVibrationMax() {
        val e = entry().copy(progressiveMaxIntensity = 3, stage2ProgressiveMaxIntensity = 6)

        val restored = AlarmScheduler.fromJson(AlarmScheduler.toJson(e))

        assertEquals(3, restored.progressiveMaxIntensity)
        assertEquals(6, restored.stage2ProgressiveMaxIntensity)
    }

    @Test
    fun legacyVibrationKey_isNeverReadBackAndStageTwoInheritsStageOne() {
        val stale = JSONObject().apply {
            put("id", 1)
            put("hour", 7)
            put("minute", 0)
            put("enabled", true)
            put("progressiveMaxIntensity", 2)
        }
        assertEquals(10, AlarmScheduler.fromJson(stale).progressiveMaxIntensity)

        val partial = JSONObject().apply {
            put("id", 1)
            put("hour", 7)
            put("minute", 0)
            put("enabled", true)
            put("progressiveVibrationMax", 5)
        }
        val alarm = AlarmScheduler.fromJson(partial)
        assertEquals(5, alarm.progressiveMaxIntensity)
        assertEquals(5, alarm.stage2ProgressiveMaxIntensity)
    }

    @Test
    fun json_clampsOutOfRangeVibrationMax() {
        val json = AlarmScheduler.toJson(entry()).apply {
            put("progressiveVibrationMax", 99)
            put("stage2ProgressiveVibrationMax", 0)
        }

        val alarm = AlarmScheduler.fromJson(json)

        assertEquals(10, alarm.progressiveMaxIntensity)
        assertEquals(1, alarm.stage2ProgressiveMaxIntensity)
    }
}
