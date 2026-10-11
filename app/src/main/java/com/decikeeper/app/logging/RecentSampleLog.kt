package com.decikeeper.app.logging

/** 固定间隔采样的限流器：距离上次采样不足 [intervalMs] 时返回 false。 */
class IntervalSampler(private val intervalMs: Long) {
    private var lastAtMs = Long.MIN_VALUE

    @Synchronized
    fun tryAcquire(nowMs: Long): Boolean {
        if (lastAtMs != Long.MIN_VALUE && nowMs - lastAtMs < intervalMs) return false
        lastAtMs = nowMs
        return true
    }
}

/**
 * 最近前台采样：5 秒一条，只保留最近 [windowMs]（默认 3 分钟），超出即丢弃。
 *
 * 存储是两个很小的分片，物理上自动淘汰旧分片；读取时再按"最新一条往前 [windowMs]"过滤，
 * 保证导出的采样至多覆盖 3 分钟。进程被杀后重启导出，仍能看到被杀前最后 3 分钟。
 * 采样没有对应的状态变化时留着也没用，所以不会被提升为永久记录。
 */
class RecentSampleLog(
    private val store: SegmentedLogStore,
    private val windowMs: Long = DEFAULT_WINDOW_MS
) {
    fun append(nowMs: Long, body: String) {
        store.append("${LogTime.format(nowMs)} $body\n")
    }

    /** 最近一条往前 [windowMs] 内的采样行（单行记录），按时间从旧到新。 */
    fun readRecentLines(): List<String> {
        val dated = store.readLines().mapNotNull { line -> LogTime.parseHeader(line)?.let { it to line } }
        if (dated.isEmpty()) return emptyList()
        val cutoff = dated.maxOf { it.first } - windowMs
        return dated.filter { it.first >= cutoff }.sortedBy { it.first }.map { it.second }
    }

    fun clear() = store.clear()

    companion object {
        const val DEFAULT_WINDOW_MS = 3 * 60 * 1000L
        const val SAMPLE_INTERVAL_MS = 5_000L
    }
}
