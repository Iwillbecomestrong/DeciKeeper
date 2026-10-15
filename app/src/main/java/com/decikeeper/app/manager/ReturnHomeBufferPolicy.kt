package com.decikeeper.app.manager

/** 时间来自 elapsedRealtime；只管理返回缓冲，不持有额度或授权。 */
internal class ReturnHomeBufferPolicy {
    companion object {
        const val WINDOW_MS = 30_000L
        const val THRESHOLD = 5
        const val QUIET_MS = 60_000L
        const val GRACE_MS = 3_000L

        fun restore(snapshot: Snapshot, elapsedMs: Long, now: Long): ReturnHomeBufferPolicy {
            val elapsed = elapsedMs.coerceAtLeast(0L)
            return ReturnHomeBufferPolicy().apply {
                snapshot.clickAgesMs
                    .map { it.coerceAtLeast(0L) + elapsed }
                    .filter { it <= WINDOW_MS }
                    .sortedDescending()
                    .takeLast(THRESHOLD - 1)
                    .forEach { clicks.addLast(now - it) }
                strictUntil = now + (snapshot.strictRemainingMs.coerceIn(0L, QUIET_MS) - elapsed).coerceAtLeast(0L)
                graceUntil = now + (snapshot.graceRemainingMs.coerceIn(0L, GRACE_MS) - elapsed).coerceAtLeast(0L)
            }
        }
    }

    /** clickAgesMs：窗口内各次显式返回距快照时刻的毫秒数。 */
    data class Snapshot(
        val clickAgesMs: List<Long>,
        val strictRemainingMs: Long,
        val graceRemainingMs: Long
    )

    /** 滑动窗口：任意连续 30 秒内第 5 次显式返回即触发，不以首次点击为锚点。 */
    private val clicks = ArrayDeque<Long>()
    private var strictUntil = 0L
    private var graceUntil = 0L

    fun isStrict(now: Long): Boolean = now < strictUntil

    /** 严格状态下点击只回桌面：不计数、不续期，恢复时间只由 onRestriction 推迟。 */
    fun onReturnHome(now: Long) {
        if (isStrict(now)) return
        pruneClicks(now)
        clicks.addLast(now)
        if (clicks.size >= THRESHOLD) {
            strictUntil = now + QUIET_MS
            graceUntil = 0L
            clicks.clear()
        }
    }

    private fun pruneClicks(now: Long) {
        while (clicks.isNotEmpty() && now - clicks.first() > WINDOW_MS) clicks.removeFirst()
    }

    /** 只在新一次实际拦截时调用；UI tick 和已显示覆盖层的重复请求不续期。 */
    fun onRestriction(now: Long) {
        if (isStrict(now)) strictUntil = now + QUIET_MS
    }

    fun beginGrace(now: Long): Long {
        if (isStrict(now)) return 0L
        if (now >= graceUntil) graceUntil = now + GRACE_MS
        return graceRemainingMs(now)
    }

    fun graceRemainingMs(now: Long): Long =
        if (isStrict(now)) 0L else (graceUntil - now).coerceAtLeast(0L)

    fun hintText(now: Long): String? {
        if (isStrict(now)) {
            val seconds = (strictUntil - now + 999L) / 1_000L
            return "返回缓冲已暂停 · 恢复剩余 ${seconds}s\n关闭受限小窗后即可正常使用桌面；再次触发拦截会重新计时"
        }
        val live = clicks.filter { now - it <= WINDOW_MS }
        if (live.isEmpty()) return null
        val seconds = (live.first() + WINDOW_MS - now + 999L) / 1_000L
        return "30秒内已返回桌面 ${live.size}/$THRESHOLD 次 · 窗口剩余 ${seconds}s" +
            if (live.size == THRESHOLD - 1) "\n再次频繁返回将暂时取消返回缓冲" else ""
    }

    fun snapshot(now: Long) = Snapshot(
        clicks.filter { now - it <= WINDOW_MS }.map { now - it },
        (strictUntil - now).coerceAtLeast(0L), graceRemainingMs(now)
    )
}
