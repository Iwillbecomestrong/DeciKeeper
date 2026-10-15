package com.decikeeper.app.manager

import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import android.provider.Settings

/** 只持久化短期豁免状态，历史额度与扣减账本不变。 */
internal object ReturnHomeBufferManager {
    private var policy = ReturnHomeBufferPolicy()
    private var prefs: SharedPreferences? = null
    private var bootCount = -1

    @Synchronized fun init(context: Context) {
        if (prefs != null) return
        val storage = context.getSharedPreferences("return_home_buffer", Context.MODE_PRIVATE)
        bootCount = Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1)
        val now = SystemClock.elapsedRealtime()
        val elapsed = if (bootCount >= 0 && storage.getInt("boot", -2) == bootCount) {
            now - storage.getLong("elapsed", now)
        } else {
            System.currentTimeMillis() - storage.getLong("wall", System.currentTimeMillis())
        }
        policy = ReturnHomeBufferPolicy.restore(
            ReturnHomeBufferPolicy.Snapshot(storage.getLong("grace", 0L), storage.getLong("cooldown", 0L)),
            elapsed, now
        )
        prefs = storage
    }

    @Synchronized fun beginGrace(): Long {
        val remaining = policy.beginGrace(SystemClock.elapsedRealtime())
        save()
        return remaining
    }

    @Synchronized fun graceRemainingMs(): Long = policy.graceRemainingMs(SystemClock.elapsedRealtime())

    @Synchronized fun hintText(): String? = policy.hintText(SystemClock.elapsedRealtime())

    private fun save() {
        val now = SystemClock.elapsedRealtime()
        val state = policy.snapshot(now)
        // 同步落盘确保紧接着的进程重启也不会重新获得一次豁免。
        prefs?.edit()?.putInt("boot", bootCount)?.putLong("elapsed", now)
            ?.putLong("wall", System.currentTimeMillis())
            ?.remove("count")?.remove("window")?.remove("clicks")?.remove("strict")?.remove("exit_used")
            ?.putLong("grace", state.graceRemainingMs)?.putLong("cooldown", state.cooldownRemainingMs)?.commit()
    }
}
