package com.decikeeper.app.manager

import android.media.AudioManager
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AlarmStreamVolumeLockTest {

    @Test
    fun `silent alarm stream starts at its lowest nonzero step and restores zero`() {
        val stream = FakeStream(maxVolume = 15, volume = 0)
        val lock = AlarmStreamVolumeLock(stream)

        lock.lock()
        assertEquals(1, stream.volume)
        lock.release()
        assertEquals(0, stream.volume)
    }

    private class FakeStream(override val maxVolume: Int, var volume: Int) : StreamVolumeAccess {
        var writes = 0
        override fun get(): Int = volume
        override fun set(volume: Int) {
            writes++
            this.volume = volume
        }
    }

    @Test
    fun `lock raises the stream to 80 percent and release restores the original volume`() {
        val stream = FakeStream(maxVolume = 15, volume = 3)
        val lock = AlarmStreamVolumeLock(stream)

        lock.lock(10f)
        assertTrue(lock.isLocked)
        assertEquals(12, stream.volume)

        lock.release()
        assertFalse(lock.isLocked)
        assertEquals(3, stream.volume)
    }

    @Test
    fun `enforce puts the volume back when the user lowers it while locked`() {
        val stream = FakeStream(maxVolume = 15, volume = 3)
        val lock = AlarmStreamVolumeLock(stream)
        lock.lock(10f)

        stream.volume = 1
        lock.enforce()

        assertEquals(12, stream.volume)
    }

    @Test
    fun `changing the locked step keeps the original silent volume`() {
        val stream = FakeStream(maxVolume = 15, volume = 0)
        val lock = AlarmStreamVolumeLock(stream)

        lock.lock(1f)
        assertEquals(1, stream.volume)
        lock.lock(3f)
        assertEquals(3, stream.volume)
        stream.volume = 0
        lock.enforce()
        assertEquals(3, stream.volume)
        lock.lock(10f)
        assertEquals(12, stream.volume)
        lock.release()
        assertEquals(0, stream.volume)
    }

    @Test
    fun `progressive steps are monotonic bounded and respect fractional caps`() {
        for (maxVolume in listOf(1, 7, 15, 25)) {
            val stream = FakeStream(maxVolume, 0)
            val lock = AlarmStreamVolumeLock(stream)
            var previous = 0
            for (step in 1..10) {
                lock.lock(step.toFloat())
                assertTrue(stream.volume >= previous)
                assertTrue(stream.volume in 1..(maxVolume * 0.8f).toInt().coerceAtLeast(1))
                if (step == 1) assertEquals(1, stream.volume)
                previous = stream.volume
            }
            lock.lock(4.5f)
            assertTrue(stream.volume <= previous)
            lock.release()
            assertEquals(0, stream.volume)
        }
    }

    @Test
    fun `silence and resume restart low without losing the zero volume`() {
        val stream = FakeStream(maxVolume = 15, volume = 0)
        val lock = AlarmStreamVolumeLock(stream)
        lock.lock(10f)
        lock.release()
        lock.lock()
        assertEquals(1, stream.volume)
        lock.release()
        assertEquals(0, stream.volume)
    }

    @Test
    fun `service lowers the channel before playback and releases it on failure`() {
        val service = source("app/src/main/java/com/decikeeper/app/service/AlarmVibrationService.kt")
        val start = service.substringAfter("private fun startRingtone(").substringBefore("private fun releaseMediaPlayer()")
        assertTrue(start.indexOf("startVolumeLock()") < start.indexOf("mediaPlayer?.start()"))
        assertTrue(start.substringAfter("catch (e: Exception)").contains("stopVolumeLock()"))
        val lock = service.substringAfter("private fun startVolumeLock()").substringBefore("private fun stopVolumeLock()")
        assertTrue(lock.contains("AlarmAlertPolicy.ringLevelValue(policy, elapsedMs)"))
        assertFalse(lock.contains("else 10f"))
        assertTrue(lock.indexOf("lock.lock(level)") < lock.indexOf("if (wasLocked) return"))
    }

    @Test
    fun `fixed level five uses a lower stream step and restores zero`() {
        val stream = FakeStream(maxVolume = 15, volume = 0)
        val lock = AlarmStreamVolumeLock(stream)
        val policy = StageAlertPolicy(
            AlarmScheduler.AlarmMode.RING_ONLY,
            AlarmScheduler.VibrationMode.FIXED,
            fixedVibrationIntensity = 1,
            fixedRingVolume = 5
        )
        lock.lock(AlarmAlertPolicy.ringLevelValue(policy, 0L))
        assertEquals(5, stream.volume)
        lock.lock(AlarmAlertPolicy.ringLevelValue(policy, 300_000L))
        assertEquals(5, stream.volume)
        lock.release()
        assertEquals(0, stream.volume)
    }

    @Test
    fun `enforce does nothing when not locked`() {
        val stream = FakeStream(maxVolume = 15, volume = 3)
        val lock = AlarmStreamVolumeLock(stream)

        lock.enforce()

        assertEquals(3, stream.volume)
        assertEquals(0, stream.writes)
    }

    @Test
    fun `second lock keeps the first saved volume and release is idempotent`() {
        val stream = FakeStream(maxVolume = 15, volume = 3)
        val lock = AlarmStreamVolumeLock(stream)

        lock.lock()
        lock.lock()
        lock.release()
        lock.release()

        assertEquals(3, stream.volume)
    }

    @Test
    fun `re-locking after release saves the volume the user has at that moment`() {
        val stream = FakeStream(maxVolume = 15, volume = 3)
        val lock = AlarmStreamVolumeLock(stream)
        lock.lock()
        lock.release()

        stream.volume = 5
        lock.lock()
        lock.release()

        assertEquals(5, stream.volume)
    }

    @Test
    fun `tiny stream range still locks to at least one step`() {
        val stream = FakeStream(maxVolume = 1, volume = 0)
        val lock = AlarmStreamVolumeLock(stream)

        lock.lock()

        assertEquals(1, stream.volume)
    }

    @Test
    fun `only telephony ringing and in-call audio modes pause the alarm`() {
        assertTrue(AlarmCallStatePolicy.isCallActive(AudioManager.MODE_RINGTONE))
        assertTrue(AlarmCallStatePolicy.isCallActive(AudioManager.MODE_IN_CALL))
        assertFalse(AlarmCallStatePolicy.isCallActive(AudioManager.MODE_NORMAL))
        assertFalse(AlarmCallStatePolicy.isCallActive(AudioManager.MODE_IN_COMMUNICATION))
    }

    @Test
    fun `vibration service never touches the shared media volume`() {
        val service = source("app/src/main/java/com/decikeeper/app/service/AlarmVibrationService.kt")

        assertFalse(
            "alarm must not read or write STREAM_MUSIC: it is shared with the user's music and video",
            service.contains("STREAM_MUSIC")
        )
        assertFalse(
            "alarm must not play as USAGE_MEDIA: that routes it through the media volume",
            service.contains("USAGE_MEDIA")
        )
        assertTrue(service.contains("AlarmStreamVolumeLock"))
        assertTrue(service.contains("AlarmCallStatePolicy"))
    }

    private fun source(path: String): String {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            val f = File(dir, path)
            if (f.exists()) return f.readText()
            dir = dir.parentFile
        }
        error("source not found: $path")
    }
}
