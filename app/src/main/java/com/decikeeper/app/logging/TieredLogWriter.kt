package com.decikeeper.app.logging

import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** 调度边界，便于测试时手动触发。 */
fun interface LogScheduler {
    fun schedule(delayMs: Long, task: () -> Unit)
}

/** 单后台线程调度，守护线程，不阻止进程退出。 */
class ExecutorLogScheduler : LogScheduler {
    private val executor = ScheduledThreadPoolExecutor(1) { runnable ->
        Thread(runnable, "log-writer").apply { isDaemon = true }
    }

    override fun schedule(delayMs: Long, task: () -> Unit) {
        try {
            executor.schedule(task, delayMs, TimeUnit.MILLISECONDS)
        } catch (_: Exception) {
            // 日志调度失败不得影响业务。
        }
    }
}

/**
 * 内存攒批的分层写入器。
 *
 * - A 级记录尽快落盘（立即调度一次 flush）。
 * - B 级记录攒到 [flushIntervalMs] 后批量落盘，业务线程只做内存追加，不开关文件。
 * - [flush] 同步落盘，供导出、服务销毁和系统内存紧张时调用。
 * - 磁盘写入失败时吞掉异常并丢弃该批，日志不得影响业务。
 * - B 级连续重复记录由 [RepeatCollapser] 折叠，只在内容变化或 [flush] 时补一行 `[REPEATED]` 汇总。
 * - B 级待写缓冲超过 [maxPendingChars] 时丢弃最老部分，避免磁盘卡住时内存无限增长。
 */
class TieredLogWriter(
    private val storeA: SegmentedLogStore,
    private val storeB: SegmentedLogStore,
    private val scheduler: LogScheduler,
    private val flushIntervalMs: Long = 5_000L,
    private val maxPendingChars: Int = 512 * 1024
) {
    private val lock = Any()
    private val writeLock = Any()
    private val pendingA = StringBuilder()
    private val pendingB = StringBuilder()
    private val collapser = RepeatCollapser()
    private var immediateScheduled = false
    private var delayedScheduled = false

    fun append(tier: LogTier, record: String) {
        if (record.isEmpty()) return
        val text = if (record.endsWith('\n')) record else record + "\n"
        var scheduleImmediate = false
        var scheduleDelayed = false
        synchronized(lock) {
            when (tier) {
                LogTier.A -> {
                    pendingA.append(text)
                    if (!immediateScheduled) {
                        immediateScheduled = true
                        scheduleImmediate = true
                    }
                }
                LogTier.B -> {
                    // 与上一条只差时间/id/耗时的重复记录只计数、不落盘。
                    val collapsed = collapser.accept(text)
                    if (collapsed.isEmpty()) return
                    pendingB.append(collapsed)
                    trimPendingB()
                    if (!delayedScheduled && !immediateScheduled) {
                        delayedScheduled = true
                        scheduleDelayed = true
                    }
                }
            }
        }
        if (scheduleImmediate) {
            scheduler.schedule(0L) {
                synchronized(lock) { immediateScheduled = false }
                flush()
            }
        }
        if (scheduleDelayed) {
            scheduler.schedule(flushIntervalMs) {
                synchronized(lock) { delayedScheduled = false }
                flush()
            }
        }
    }

    fun flush() {
        synchronized(writeLock) {
            val a: String
            val b: String
            synchronized(lock) {
                a = pendingA.toString()
                b = pendingB.toString() + collapser.drainSummary()
                pendingA.setLength(0)
                pendingB.setLength(0)
            }
            if (a.isNotEmpty()) runCatching { storeA.append(a) }
            if (b.isNotEmpty()) runCatching { storeB.append(b) }
        }
    }

    private fun trimPendingB() {
        if (pendingB.length <= maxPendingChars) return
        val cut = pendingB.length - maxPendingChars / 2
        val nextLine = pendingB.indexOf("\n", cut)
        val end = if (nextLine < 0) pendingB.length else nextLine + 1
        pendingB.delete(0, end)
    }
}
