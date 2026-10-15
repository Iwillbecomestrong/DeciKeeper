package com.decikeeper.app.manager

/** 回桌面后的关小窗豁免：每次豁免 3 秒，之后 30 秒内不再豁免。时间来自 elapsedRealtime。 */
internal class ReturnHomeBufferPolicy {
    companion object {
        const val GRACE_MS = 3_000L
        const val COOLDOWN_MS = 30_000L

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

    /** 豁免中共享同一截止；冷却内返回 0，冷却从放行开始计，不被重复请求续期。 */
    fun beginGrace(now: Long): Long {
        if (now >= graceUntil && now >= cooldownUntil) {
            graceUntil = now + GRACE_MS
            cooldownUntil = now + COOLDOWN_MS
        }
        return graceRemainingMs(now)
    }

    fun graceRemainingMs(now: Long): Long = (graceUntil - now).coerceAtLeast(0L)

    fun hintText(now: Long): String? {
        if (graceRemainingMs(now) > 0L || now >= cooldownUntil) return null
        val seconds = (cooldownUntil - now + 999L) / 1_000L
        return "刚才回桌面已豁免 ${GRACE_MS / 1_000L} 秒，用于关闭小窗\n现在不再豁免，${seconds} 秒后重新豁免；请先关闭受限小窗"
    }

    fun snapshot(now: Long) = Snapshot(graceRemainingMs(now), (cooldownUntil - now).coerceAtLeast(0L))
}
