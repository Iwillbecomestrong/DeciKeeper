package com.decikeeper.app.logging

import java.io.File
import java.io.FileOutputStream

/**
 * 分片滚动日志存储。每个分片是 `<prefix>-<6位序号>.log`，序号单调递增。
 *
 * 当前分片写满就新开一片；总大小超过 [maxTotalBytes] 时直接删除最老的整片，
 * 不读取、不重写任何已有内容。始终至少保留当前分片。
 *
 * 线程安全：所有读写在同一把锁下进行。
 */
class SegmentedLogStore(
    private val dir: File,
    private val prefix: String,
    private val segmentBytes: Long,
    private val maxTotalBytes: Long
) {
    init {
        require(segmentBytes > 0) { "segmentBytes must be positive" }
        require(maxTotalBytes >= segmentBytes) { "maxTotalBytes must not be smaller than segmentBytes" }
    }

    private val lock = Any()
    private val namePattern = Regex("^" + Regex.escape(prefix) + "-(\\d{6})\\.log$")

    /** 追加一段文本（可以是多条完整记录），文本不会被拆到两个分片里。 */
    fun append(text: String) {
        if (text.isEmpty()) return
        val bytes = text.toByteArray(Charsets.UTF_8)
        synchronized(lock) {
            dir.mkdirs()
            val segments = listSegments()
            var current = segments.lastOrNull()
            val nextIndex = (current?.let { indexOf(it) } ?: 0) + 1
            if (current == null || (current.length() > 0 && current.length() + bytes.size > segmentBytes)) {
                current = segmentFile(if (current == null) 1 else nextIndex)
            }
            FileOutputStream(current, true).use { it.write(bytes) }
            trimToBudget()
        }
    }

    /** 按时间从旧到新返回所有物理行，供时间线合并使用。 */
    fun readLines(): List<String> = synchronized(lock) {
        listSegments().flatMap { file ->
            try {
                file.readLines(Charsets.UTF_8)
            } catch (_: Exception) {
                emptyList()
            }
        }
    }

    fun totalBytes(): Long = synchronized(lock) { listSegments().sumOf { it.length() } }

    fun segmentCount(): Int = synchronized(lock) { listSegments().size }

    fun clear() = synchronized(lock) { listSegments().forEach { it.delete() } }

    private fun trimToBudget() {
        var segments = listSegments()
        while (segments.size > 1 && segments.sumOf { it.length() } > maxTotalBytes) {
            segments.first().delete()
            segments = segments.drop(1)
        }
    }

    private fun listSegments(): List<File> =
        (dir.listFiles() ?: emptyArray())
            .filter { namePattern.matches(it.name) }
            .sortedBy { indexOf(it) }

    private fun indexOf(file: File): Int =
        namePattern.matchEntire(file.name)?.groupValues?.get(1)?.toInt() ?: 0

    private fun segmentFile(index: Int): File = File(dir, "%s-%06d.log".format(prefix, index))
}
