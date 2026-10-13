package com.decikeeper.app.manager

import android.media.AudioManager

/** 某个系统音量通道的读写入口；抽象出来便于 JVM 测试。 */
internal interface StreamVolumeAccess {
    val maxVolume: Int
    fun get(): Int
    fun set(volume: Int)
}

internal class AudioManagerStreamVolumeAccess(
    private val audioManager: AudioManager,
    private val stream: Int
) : StreamVolumeAccess {
    override val maxVolume: Int get() = audioManager.getStreamMaxVolume(stream)
    override fun get(): Int = audioManager.getStreamVolume(stream)
    override fun set(volume: Int) = audioManager.setStreamVolume(stream, volume, 0)
}

/**
 * 闹钟专属通道的音量锁：响铃出声期间把通道锁在最大值的 80%，让系统音量键对闹钟失效；
 * 释放时还原锁定前用户自己的音量。只作用于传入的通道，调用方必须传闹钟通道，
 * 绝不能是与音乐/视频共用的媒体通道。
 */
internal class AlarmStreamVolumeLock(
    private val stream: StreamVolumeAccess,
    private val lockFraction: Float = 0.8f
) {
    private var savedVolume: Int? = null
    private var targetVolume: Int = 0

    val isLocked: Boolean get() = savedVolume != null

    fun lock() {
        if (isLocked) return
        savedVolume = stream.get()
        // 锁在 80% 而非 100%，给 MediaPlayer.setVolume() 留出足够动态范围。
        targetVolume = (stream.maxVolume * lockFraction).toInt().coerceAtLeast(1)
        stream.set(targetVolume)
    }

    /** 周期性调用：锁定期间用户把音量改了就立刻改回。 */
    fun enforce() {
        if (!isLocked) return
        if (stream.get() != targetVolume) stream.set(targetVolume)
    }

    fun release() {
        val saved = savedVolume ?: return
        savedVolume = null
        stream.set(saved)
    }
}

/** 通话期间（来电响铃 / 通话中）闹钟需让路；只认电话类音频模式，不含 VoIP 通信模式以免误判卡死。 */
internal object AlarmCallStatePolicy {
    fun isCallActive(audioMode: Int): Boolean =
        audioMode == AudioManager.MODE_RINGTONE || audioMode == AudioManager.MODE_IN_CALL
}
