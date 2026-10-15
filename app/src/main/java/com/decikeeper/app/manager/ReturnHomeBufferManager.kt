package com.decikeeper.app.manager

import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import android.provider.Settings

/** 只持久化短期缓冲状态，历史额度与扣减账本不变。 */
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
            ReturnHomeBufferPolicy.Snapshot(
                storage.getString("clicks", "").orEmpty().split(',').mapNotNull { it.toLongOrNull() },
                storage.getLong("strict", 0L), storage.getLong("grace", 0L)
            ), elapsed, now
        )
        prefs = storage
    }

    @Synchronized fun onReturnHome() {
        policy.onReturnHome(SystemClock.elapsedRealtime())
        save()
    }

    @Synchronized fun onRestriction() {
        val now = SystemClock.elapsedRealtime()
        if (policy.isStrict(now)) {
            policy.onRestriction(now)
            save()
        }
    }

    @Synchronized fun isStrict(): Boolean = policy.isStrict(SystemClock.elapsedRealtime())

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
        // 同步落盘确保点击后紧接着进程重启也不会重新获得一轮缓冲。
        prefs?.edit()?.putInt("boot", bootCount)?.putLong("elapsed", now)
            ?.putLong("wall", System.currentTimeMillis())?.remove("count")?.remove("window")
            ?.putString("clicks", state.clickAgesMs.joinToString(","))
            ?.putLong("strict", state.strictRemainingMs)
            ?.putLong("grace", state.graceRemainingMs)?.commit()
    }
}
