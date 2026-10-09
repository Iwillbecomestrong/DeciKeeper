package com.decikeeper.app.data

/**
 * 特权可用时间范围：落在同一个业务日内的 `开始 ~ 结束`。
 *
 * - 开始不早于业务日开始（生效时间 / refresh time）；
 * - 结束晚于开始，最晚 24:00。
 *
 * 业务日从 refresh time 到次日 refresh time，范围夹在「业务日开始 ~ 24:00」之间，
 * 就不会跨业务日，也不会越过午夜，特权统计按业务日归属也不会拆到两天。
 */
internal object PrivilegeWindow {
    const val DEFAULT_START = "07:00"
    const val DEFAULT_END = "23:00"

    /** `HH:MM` 转当天分钟数；只接受 00:00 ~ 24:00，格式不对返回 null。 */
    fun parseMinutes(text: String?): Int? {
        val parts = text?.trim()?.split(":") ?: return null
        if (parts.size != 2) return null
        val hour = parts[0].toIntOrNull() ?: return null
        val minute = parts[1].toIntOrNull() ?: return null
        if (hour !in 0..24 || minute !in 0..59) return null
        if (hour == 24 && minute != 0) return null
        return hour * 60 + minute
    }

    /** 新输入的严格校验：[businessDayStart] 为业务日开始（refresh time），范围开始不得早于它。 */
    fun isValid(start: String?, end: String?, businessDayStart: String): Boolean {
        val startMins = parseMinutes(start) ?: return false
        val endMins = parseMinutes(end) ?: return false
        val dayStartMins = parseMinutes(businessDayStart) ?: return false
        return startMins >= dayStartMins && startMins < endMins
    }

    /** 默认范围：开始取 max(07:00, 业务日开始)，结束 23:00。 */
    fun defaultFor(businessDayStart: String): Pair<String, String> {
        val dayStartMins = parseMinutes(businessDayStart) ?: 0
        val defaultStartMins = parseMinutes(DEFAULT_START)!!
        val start = if (defaultStartMins >= dayStartMins) DEFAULT_START else businessDayStart
        return start to DEFAULT_END
    }

    /**
     * 读取已存储范围时的生效值。「开始不早于业务日开始」是后加的约束，老用户可能存着更早的开始：
     * 开始被收窄到业务日开始（保留结束）；收窄后仍不合法（旧版跨午夜等）才整体回落默认。
     */
    fun resolve(storedStart: String?, storedEnd: String?, businessDayStart: String): Pair<String, String> {
        if (storedStart == null && storedEnd == null) return defaultFor(businessDayStart)
        val dayStartMins = parseMinutes(businessDayStart) ?: return defaultFor(businessDayStart)
        val start = storedStart ?: defaultFor(businessDayStart).first
        val end = storedEnd ?: DEFAULT_END
        val startMins = parseMinutes(start) ?: return defaultFor(businessDayStart)
        val effectiveStart = if (startMins < dayStartMins) businessDayStart else start
        return if (isValid(effectiveStart, end, businessDayStart)) effectiveStart to end
        else defaultFor(businessDayStart)
    }
}
