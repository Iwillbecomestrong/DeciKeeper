package com.decikeeper.app.logging

import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** 日志记录头部的统一时间格式：`yyyy-MM-dd HH:mm:ss.SSS`，后面紧跟一个空格。 */
object LogTime {
    private val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    private val header = Regex("^(\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\\.\\d{3}) ")
    const val HEADER_LENGTH = 23

    fun format(epochMs: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        formatter.format(java.time.Instant.ofEpochMilli(epochMs).atZone(zone))

    /** 行以统一时间头开始时返回对应的毫秒时间，否则返回 null（说明它是上一条记录的续行）。 */
    fun parseHeader(line: String, zone: ZoneId = ZoneId.systemDefault()): Long? {
        val match = header.find(line) ?: return null
        return try {
            LocalDateTime.parse(match.groupValues[1], formatter).atZone(zone).toInstant().toEpochMilli()
        } catch (_: Exception) {
            null
        }
    }
}
