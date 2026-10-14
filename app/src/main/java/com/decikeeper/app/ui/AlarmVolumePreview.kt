package com.decikeeper.app.ui

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.decikeeper.app.manager.AlarmAlertPolicy
import com.decikeeper.app.manager.AlarmScheduler
import com.decikeeper.app.manager.AlarmStreamVolumeLock
import com.decikeeper.app.manager.AudioManagerStreamVolumeAccess
import com.decikeeper.app.service.AlarmVibrationService

/**
 * 提醒设置里调「声音 / 最大声音」滑块后的短促试听。
 *
 * 与真实响铃保持一致：走闹钟通道（USAGE_ALARM），试听期间借 [AlarmStreamVolumeLock]
 * 按所选模式与档位设置通道和播放器增益，结束或被打断后还原原来的音量。绝不碰媒体音量。
 * 真实闹钟正在响时不试听，避免干扰正在进行的提醒。
 */
internal object AlarmVolumePreview {
    private const val TAG = "AlarmVolumePreview"
    const val PREVIEW_MS = 2000L

    private val handler = Handler(Looper.getMainLooper())
    private var player: MediaPlayer? = null
    private var volumeLock: AlarmStreamVolumeLock? = null
    private var stopRunnable: Runnable? = null

    /** 以 [level]（1-10）对应的响度试听 [ringtoneUri]；为空则用系统默认闹钟铃声。 */
    fun play(context: Context, ringtoneUri: String?, level: Int, mode: AlarmScheduler.VibrationMode) {
        if (AlarmVibrationService.isRunning) return
        stop()
        val app = context.applicationContext
        val uri = ringtoneUri?.takeIf { it.isNotBlank() }?.let(Uri::parse)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: return
        try {
            val audio = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val progressive = mode == AlarmScheduler.VibrationMode.PROGRESSIVE
            volumeLock = AlarmStreamVolumeLock(
                AudioManagerStreamVolumeAccess(audio, AudioManager.STREAM_ALARM)
            ).also { it.lock(level.toFloat()) }
            val gain = AlarmAlertPolicy.ringGain(level.toFloat(), progressive)
            player = MediaPlayer().apply {
                setDataSource(app, uri)
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                setVolume(gain, gain)
                prepare()
                start()
            }
            val runnable = Runnable { stop() }
            stopRunnable = runnable
            handler.postDelayed(runnable, PREVIEW_MS)
        } catch (e: Exception) {
            Log.w(TAG, "Volume preview failed: ${e.message}")
            stop()
        }
    }

    /** 立即停止试听并还原闹钟通道音量；弹窗关闭时也要调用。 */
    fun stop() {
        stopRunnable?.let { handler.removeCallbacks(it) }
        stopRunnable = null
        player?.let { mp ->
            try { if (mp.isPlaying) mp.stop() } catch (_: Exception) {}
            try { mp.release() } catch (_: Exception) {}
        }
        player = null
        volumeLock?.release()
        volumeLock = null
    }
}
