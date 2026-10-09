package com.decikeeper.app.ui

import android.content.Context
import android.media.AudioAttributes
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import com.decikeeper.app.manager.AlarmAlertPolicy
import com.decikeeper.app.service.AlarmVibrationService

/**
 * 提醒设置里调「震动 / 最大震动」滑块后的短促试震，让用户松手就能感受这一档有多强。
 *
 * 与真实闹钟震动同一振幅换算（[AlarmAlertPolicy.vibrationAmplitude]）和同一用途（USAGE_ALARM）。
 * 真实闹钟正在响时不试震，避免打断正在进行的提醒。
 */
internal object AlarmVibrationPreview {
    private const val TAG = "AlarmVibrationPreview"
    const val PREVIEW_MS = 800L

    private var vibrator: Vibrator? = null

    /** 以 [level]（1-10）对应的强度震动一小下。 */
    fun play(context: Context, level: Int) {
        if (AlarmVibrationService.isRunning) return
        stop()
        try {
            val v = resolveVibrator(context.applicationContext) ?: return
            val amplitude = AlarmAlertPolicy.vibrationAmplitude(level.toFloat())
            val attrs = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build()
            v.vibrate(VibrationEffect.createOneShot(PREVIEW_MS, amplitude), attrs)
            vibrator = v
        } catch (e: Exception) {
            Log.w(TAG, "Vibration preview failed: ${e.message}")
        }
    }

    /** 立即停止试震；弹窗关闭时也要调用。 */
    fun stop() {
        try { vibrator?.cancel() } catch (_: Exception) {}
        vibrator = null
    }

    private fun resolveVibrator(app: Context): Vibrator? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            app.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            app.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
}
