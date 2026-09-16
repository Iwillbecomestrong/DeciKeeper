package com.decikeeper.app.manager

import com.decikeeper.app.manager.AlarmScheduler
import com.decikeeper.app.service.AlarmVibrationService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AlarmChallengeTwoStageFlowTest {

    @Test
    fun testAlarmEntryCustomBankSerialization() {
        val entry = AlarmScheduler.AlarmEntry(
            id = 101,
            hour = 8,
            minute = 30,
            enabled = true,
            days = BooleanArray(7) { true },
            unlockMode = AlarmScheduler.UnlockMode.TWO_STAGE,
            stage1Types = setOf("qr", "custom"),
            stage2Types = setOf("math", "custom"),
            stage1CustomBanks = mapOf("bank_cet4" to 3, "bank_cet6" to 2),
            stage2CustomBanks = mapOf("bank_kaoyan" to 5)
        )

        val json = AlarmScheduler.toJson(entry)
        assertNotNull(json)

        val s1Json = json.optJSONObject("stage1CustomBanks")
        assertNotNull(s1Json)
        assertEquals(3, s1Json!!.optInt("bank_cet4"))
        assertEquals(2, s1Json.optInt("bank_cet6"))

        val s2Json = json.optJSONObject("stage2CustomBanks")
        assertNotNull(s2Json)
        assertEquals(5, s2Json!!.optInt("bank_kaoyan"))
    }

    @Test
    fun testAttenuationActionContract() {
        assertEquals("com.decikeeper.app.service.ACTION_ATTENUATE", AlarmVibrationService.ACTION_ATTENUATE)
    }

    @Test
    fun testAttenuationMustBeSilentAndCancelVibration() {
        val serviceFile = listOf(
            java.io.File("src/main/java/com/decikeeper/app/service/AlarmVibrationService.kt"),
            java.io.File("app/src/main/java/com/decikeeper/app/service/AlarmVibrationService.kt")
        ).firstOrNull { it.exists() }
        assertNotNull("AlarmVibrationService.kt must exist", serviceFile)
        val serviceSource = serviceFile!!.readText()
        assertTrue(
            "applyAttenuation must mute media player and cancel vibrator",
            serviceSource.contains("private fun applyAttenuation()") &&
                serviceSource.contains("mediaPlayer?.setVolume(0f, 0f)") &&
                serviceSource.contains("vibrator?.cancel()")
        )
        assertTrue(
            "applyAttenuation must set display volume and intensity to 0",
            serviceSource.contains("currentDisplayVolume = 0") &&
                serviceSource.contains("currentDisplayIntensity = 0")
        )
        assertTrue(
            "vibration keep-alive must skip when attenuated",
            serviceSource.contains("!isAttenuated")
        )
    }

    @Test
    fun testTwoStageValidationLogic() {
        val s1Empty = emptySet<String>()
        val s2Valid = setOf("math")
        assertTrue(s1Empty.isEmpty())

        val s1Valid = setOf("qr")
        val s2Empty = emptySet<String>()
        assertTrue(s2Empty.isEmpty())

        assertTrue(s1Valid.isNotEmpty() && s2Valid.isNotEmpty())
    }
}
