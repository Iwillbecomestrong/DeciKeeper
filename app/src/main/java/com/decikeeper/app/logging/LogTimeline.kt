package com.decikeeper.app.logging

/** 导出结果：上半部分是 A 级永久账本，下半部分是 B 级近期诊断，各自按时间排序。 */
data class LogTimelineExport(
    val lines: List<String>,
    val aRecordCount: Int,
    val bRecordCount: Int,
    val aRange: Pair<Long, Long>?,
    val bRange: Pair<Long, Long>?
)

/**
 * 把永久账本（A）与近期诊断（B）整理成两个分区：先列出全部 A，再列出全部 B。
 * 需要某个 A 事件的前因时，按它的时间到 B 分区里查找。
 *
 * 每条记录以统一时间头开始，后面不带时间头的物理行是它的续行，必须与首行一起保留或一起过滤。
 * 输出时首行以 `A ` 或 `B ` 开头，续行缩进两个空格，因此 `grep '^A'` 只会命中账本记录。
 */
object LogTimeline {

    private class Record(val timeMs: Long, val tier: LogTier, val lines: MutableList<String>)

    const val LEGEND = "# 上半部分 A=永久账本，下半部分 B=近期诊断；各自按时间排序，续行缩进两格属于上一条记录"

    fun merge(
        aLines: List<String>,
        bLines: List<String>,
        startMs: Long = Long.MIN_VALUE,
        endMs: Long = Long.MAX_VALUE
    ): LogTimelineExport {
        val a = parse(aLines, LogTier.A)
        val b = parse(bLines, LogTier.B)
        val selectedA = a.filter { it.timeMs in startMs..endMs }.sortedBy { it.timeMs }
        val selectedB = b.filter { it.timeMs in startMs..endMs }.sortedBy { it.timeMs }

        val out = ArrayList<String>(selectedA.sumOf { it.lines.size } + selectedB.sumOf { it.lines.size } + 2)
        out += sectionTitle("A 永久账本", selectedA.size)
        selectedA.forEach { render(it, out) }
        out += sectionTitle("B 近期诊断", selectedB.size)
        selectedB.forEach { render(it, out) }

        return LogTimelineExport(
            lines = out,
            aRecordCount = a.size,
            bRecordCount = b.size,
            aRange = range(a),
            bRange = range(b)
        )
    }

    /** 文件头：图例加 A、B 各自的时间范围，让读者知道诊断部分能回看到什么时候。 */
    fun headerLines(export: LogTimelineExport): List<String> = listOf(
        LEGEND,
        "# A 范围: ${describe(export.aRange)} (${export.aRecordCount} 条)",
        "# B 范围: ${describe(export.bRange)} (${export.bRecordCount} 条)"
    )

    private fun sectionTitle(name: String, count: Int): String = "# ---------- $name（本次导出 $count 条）----------"

    private fun render(record: Record, out: MutableList<String>) {
        record.lines.forEachIndexed { index, line ->
            out += if (index == 0) "${record.tier.marker} $line" else "  $line"
        }
    }

    private fun describe(range: Pair<Long, Long>?): String =
        range?.let { "${LogTime.format(it.first)} ~ ${LogTime.format(it.second)}" } ?: "无记录"

    private fun range(records: List<Record>): Pair<Long, Long>? =
        if (records.isEmpty()) null else records.minOf { it.timeMs } to records.maxOf { it.timeMs }

    private fun parse(lines: List<String>, tier: LogTier): List<Record> {
        val records = ArrayList<Record>()
        var current: Record? = null
        for (line in lines) {
            val time = LogTime.parseHeader(line)
            if (time != null) {
                current = Record(time, tier, mutableListOf(line)).also { records += it }
            } else {
                // 没有时间头且前面没有记录的残片（如分片被删除后的半条记录）直接丢弃。
                current?.lines?.add(line)
            }
        }
        return records
    }
}
