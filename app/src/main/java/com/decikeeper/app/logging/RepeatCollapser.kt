package com.decikeeper.app.logging

/**
 * B 级近期诊断的"同类重复折叠"：与**同一类**上一条记录相比，只有时间戳、各类 id、毫秒耗时不同的
 * 记录视为它的重复，不再落盘，只累计次数；该类记录的内容变化或导出时，补写一行 `[REPEATED]` 汇总。
 *
 * "同类"按记录首行的标签区分（如 `[FG_DECISION]`、`MonitorService: [EVENT] type=SUSPECT`），
 * 因此不同类型的记录交替出现时，每一类仍能各自折叠。
 *
 * 归一化只抹掉时间、id 和耗时，包名、状态、原因码等任何实质内容变化都会产生新记录，
 * 因此不会掩盖真正的状态变化。A 级永久账本不使用折叠。
 *
 * 非线程安全，调用方（[TieredLogWriter]）负责加锁。
 */
class RepeatCollapser {

    private class State(var key: String) {
        var repeats: Int = 0
        var firstRepeatMs: Long = 0L
        var lastRepeatMs: Long = 0L
    }

    private val states = LinkedHashMap<String, State>()

    /** 返回需要落盘的文本：重复记录返回空串；新记录返回（该类上一轮的汇总行 +）记录本身。 */
    fun accept(record: String, nowMs: Long = System.currentTimeMillis()): String {
        val text = if (record.endsWith('\n')) record else record + "\n"
        val label = labelOf(text)
        val key = normalize(text)
        val state = states[label]
        if (state == null) {
            states[label] = State(key)
            return text
        }
        if (state.key == key) {
            val at = LogTime.parseHeader(text) ?: nowMs
            if (state.repeats == 0) state.firstRepeatMs = at
            state.repeats++
            state.lastRepeatMs = at
            return ""
        }
        val summary = summaryOf(label, state)
        state.key = key
        return summary + text
    }

    /** 取出所有尚未写出的重复汇总（没有则返回空串），并让下一次重复重新计数。 */
    fun drainSummary(): String {
        val sb = StringBuilder()
        states.entries
            .filter { it.value.repeats > 0 }
            .sortedBy { it.value.lastRepeatMs }
            .forEach { (label, state) -> sb.append(summaryOf(label, state)) }
        return sb.toString()
    }

    private fun summaryOf(label: String, state: State): String {
        if (state.repeats == 0) return ""
        val line = "${LogTime.format(state.lastRepeatMs)} [REPEATED] of=${label.replace(' ', '_')} " +
            "n=${state.repeats} first=${LogTime.format(state.firstRepeatMs)}\n"
        state.repeats = 0
        return line
    }

    companion object {
        private val dateTime = Regex("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}(\\.\\d+)?")
        private val clock = Regex("\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?")
        private val uuid = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
        private val traceId = Regex("\\b(LOCK|FG)-\\d{8}-\\d{6}-\\d{4}\\b")
        private val idFields = Regex("\\b(eventId|processInstanceId|seq|reportId|traceId|requestId|decisionRef|sessionId)=\\S+")
        private val durationFields = Regex("\\b(\\w*Ms)=-?\\d+")
        private val longNumber = Regex("\\b\\d{9,}\\b")

        /** 抹掉时间、id、耗时后的记录内容，用于判断两条记录是否只是同一状态的重复。 */
        fun normalize(text: String): String = text
            .replace(dateTime, "")
            .replace(clock, "")
            .replace(uuid, "")
            .replace(traceId, "ID")
            .replace(idFields) { it.groupValues[1] + "=" }
            .replace(durationFields) { it.groupValues[1] + "=#" }
            .replace(longNumber, "#")

        /**
         * 记录首行去掉时间头后，取到第一个 `key=value` 之前的词（最多三个）作为类别；
         * 以 `type=` 开头的词也计入，用来区分同一 tag 下的不同事件类型。
         */
        fun labelOf(text: String): String {
            val firstLine = text.substringBefore('\n')
            val body = if (LogTime.parseHeader(firstLine) != null) firstLine.drop(LogTime.HEADER_LENGTH + 1) else firstLine
            val words = ArrayList<String>(3)
            for (word in body.split(' ')) {
                if (word.contains('=')) {
                    if (word.startsWith("type=")) words += word
                    break
                }
                words += word
                if (words.size >= 3) break
            }
            return words.joinToString(" ").ifEmpty { "UNKNOWN" }
        }
    }
}
