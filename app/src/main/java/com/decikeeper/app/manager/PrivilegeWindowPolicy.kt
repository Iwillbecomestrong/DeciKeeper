package com.decikeeper.app.manager

/**
 * 每周特权可用时间范围的纯规则：不能跨天；开始 ≥ 生效时间；结束 ≤ 24:00 且晚于开始。
 * 开始时间与生效时间相同视为「跟随生效时间」。
 */
internal object PrivilegeWindowPolicy {
    const val DEFAULT_END = "24:00"

    fun parseMinutes(text: String?, allowMidnightEnd: Boolean): Int? {
        val parts = text?.trim()?.split(":") ?: return null
        if (parts.size != 2) return null
        val h = parts[0].toIntOrNull() ?: return null
        val m = parts[1].toIntOrNull() ?: return null
        if (m !in 0..59) return null
        if (h in 0..23) return h * 60 + m
        return if (allowMidnightEnd && h == 24 && m == 0) 1440 else null
    }

    /** @return null 表示合法；否则为完整的拒绝原因。 */
    fun validate(start: String, end: String, refreshTime: String): String? {
        val refreshMins = parseMinutes(refreshTime, allowMidnightEnd = false) ?: (8 * 60)
        val rangeHint = "特权可用范围不能跨天：开始时间不得早于生效时间 $refreshTime，结束时间最晚 24:00，且须晚于开始时间。"
        val s = parseMinutes(start, allowMidnightEnd = false)
            ?: return "开始时间格式必须为 HH:mm。$rangeHint"
        val e = parseMinutes(end, allowMidnightEnd = true)
            ?: return "结束时间格式必须为 HH:mm（最晚 24:00）。$rangeHint"
        if (s < refreshMins || e <= s) return rangeHint
        return null
    }

    /** 有效开始时间 = max(已存开始时间, 生效时间)；未设置时跟随生效时间。 */
    fun effectiveStart(storedStart: String?, refreshTime: String): String {
        val refreshMins = parseMinutes(refreshTime, allowMidnightEnd = false) ?: return storedStart ?: refreshTime
        val storedMins = parseMinutes(storedStart, allowMidnightEnd = false) ?: return refreshTime
        return if (storedMins < refreshMins) refreshTime else storedStart!!.trim()
    }

    /** 未设置，或开始时间与生效时间相同：视为跟随生效时间。 */
    fun isFollowingRefresh(storedStart: String?, refreshTime: String): Boolean {
        if (storedStart == null) return true
        val s = parseMinutes(storedStart, allowMidnightEnd = false) ?: return false
        return s == parseMinutes(refreshTime, allowMidnightEnd = false)
    }

    /** 固定开始时间早于（新的）生效时间：需要提示用户特权将从生效时间起可用。 */
    fun conflictsWithRefresh(storedStart: String?, refreshTime: String): Boolean {
        val s = parseMinutes(storedStart, allowMidnightEnd = false) ?: return false
        val r = parseMinutes(refreshTime, allowMidnightEnd = false) ?: return false
        return s < r
    }
}
