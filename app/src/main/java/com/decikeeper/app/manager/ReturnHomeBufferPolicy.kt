package com.decikeeper.app.manager

/** 回桌面后的关小窗豁免：每次豁免 3 秒，之后 30 秒内不再豁免。时间来自 elapsedRealtime。 */
internal class ReturnHomeBufferPolicy {
    companion object {
        const val GRACE_MS = 3_000L
        const val COOLDOWN_MS = 30_000L
        /** 冷却内回桌面也先撤掉锁屏这么久再重锁，让用户看到界面切换；不放行使用。 */
        const val MIN_VISIBLE_GAP_MS = 700L

        fun restore(snapshot: Snapshot, elapsedMs: Long, now: Long): ReturnHomeBufferPolicy {
            val elapsed = elapsedMs.coerceAtLeast(0L)
            return ReturnHomeBufferPolicy().apply {
                graceUntil = now + (snapshot.graceRemainingMs.coerceIn(0L, GRACE_MS) - elapsed).coerceAtLeast(0L)
                cooldownUntil = now + (snapshot.cooldownRemainingMs.coerceIn(0L, COOLDOWN_MS) - elapsed).coerceAtLeast(0L)
            }
        }
    }

    data class Snapshot(val graceRemainingMs: Long, val cooldownRemainingMs: Long)

    private var graceUntil = 0L
    private var cooldownUntil = 0L
    private var settleUntil = 0L

    /** 豁免中共享同一截止；冷却内返回 0，冷却从放行开始计，不被重复请求续期。 */
    fun beginGrace(now: Long, limited: Boolean = true): Long {
        if (!limited) {
            // 全屏应用回桌面只给“等待关闭确认”的时间：只用于不让存在性追踪提前重锁，
            // 不放行任何使用（重新打开仍会被拦），也不进入或消耗冷却。
            settleUntil = maxOf(settleUntil, now + GRACE_MS)
            return graceRemainingMs(now)
        }
        if (now >= graceUntil && now >= cooldownUntil) {
            graceUntil = now + GRACE_MS
            cooldownUntil = now + COOLDOWN_MS
        }
        return graceRemainingMs(now)
    }

    fun graceRemainingMs(now: Long): Long = (graceUntil - now).coerceAtLeast(0L)

    /** 存在性追踪等待关闭确认的剩余时间：豁免与全屏确认时间取较长者。 */
    fun settleRemainingMs(now: Long): Long = (maxOf(graceUntil, settleUntil) - now).coerceAtLeast(0L)

    fun hintText(now: Long): String? {
        if (graceRemainingMs(now) > 0L || now >= cooldownUntil) return null
        val seconds = (cooldownUntil - now + 999L) / 1_000L
        return "刚才回桌面已豁免 ${GRACE_MS / 1_000L} 秒，用于关闭小窗\n现在不再豁免，${seconds} 秒后重新豁免；请先关闭受限小窗"
    }

    fun snapshot(now: Long) = Snapshot(graceRemainingMs(now), (cooldownUntil - now).coerceAtLeast(0L))
}
