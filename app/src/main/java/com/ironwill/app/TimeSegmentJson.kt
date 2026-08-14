package com.ironwill.app

import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar

data class TimeSegment(
    val startHour: Int,
    val startMinute: Int = 0,
    val endHour: Int,
    val endMinute: Int = 0,
    val limitMins: Int,
    val name: String = "",
    val isCustomized: Boolean = false,
    // 🚀 [NEW] Repetition: List of Day indices (1=Sun, 2=Mon... 7=Sat)
    // Default: null or empty means Every Day
    val daysOfWeek: List<Int>? = null,
    // 🚀 [NEW] Toggle Enable/Disable
    val isEnabled: Boolean = true,
    // 可用次数上限。-1=自动计算(max(1, limitMins/5))，0=全禁，>0=用户设定
    val usableCountLimitHalfUnits: Int = UsableCount.AUTO_HALF_UNITS,
    // Legacy field retained for old serialized data compatibility.
    val lastModified: Long = 0L,
    // 🆕 [NEW] 本时段独立黑名单。null=跟随全局黑名单（继承态）；
    // 非空列表=本时段独立名单（脱钩态）；空列表=本时段什么都不锁。
    // 置于末尾以避免破坏既有位置参数构造调用。
    val listApps: List<String>? = null
) {
    fun isActiveToday(checkDayInput: Int? = null): Boolean {
        // 🚀 Check Enabled State First
        if (!isEnabled) return false

        val currentDay = checkDayInput ?: Calendar.getInstance().get(Calendar.DAY_OF_WEEK)
        // If daysOfWeek is defined and NOT empty, check if today is included
        if (daysOfWeek != null && daysOfWeek.isNotEmpty() && !daysOfWeek.contains(currentDay)) {
            return false // Not active today
        }
        return true
    }

    fun isInSegment(h: Int, m: Int): Boolean {
        // 🚀 [Debug] Verbose logging disabled to prevent spam
        // android.util.Log.d("IronWill_Time", "Checking Segment $name ($startHour:$startMinute-$endHour:$endMinute) Enabled=$isEnabled vs Now $h:$m. ActiveToday=${isActiveToday()}, daysOfWeek=$daysOfWeek")
        
        if (!isActiveToday()) {
            // android.util.Log.d("IronWill_Time", "  -> FALSE (not active today or disabled)")
            return false
        }
        return isInTimeRange(h, m)
    }

    /**
     * 🚀 [New] Static check for time range only, ignoring day-of-week or enabled status.
     * Used for adverse change analysis.
     */
    fun isInTimeRange(h: Int, m: Int): Boolean {
        val current = h * 60 + m
        val start = startHour * 60 + startMinute
        val end = endHour * 60 + endMinute

        val result = if (start > end) {
            // Cross-midnight segment
            current >= start || current < end
        } else {
            // Normal segment
            current >= start && current < end
        }
        
        // android.util.Log.d("IronWill_Time", "  -> current=$current, start=$start, end=$end, crossMidnight=${start > end}, result=$result")
        return result
    }

    fun formatTimeRange(): String {
        return String.format("%02d:%02d-%02d:%02d", startHour, startMinute, endHour, endMinute)
    }
    
    // 🚀 [NEW] Calculate segment duration in minutes (handles cross-midnight)
    fun getDurationMinutes(): Int {
        val startTotal = startHour * 60 + startMinute
        val endTotal = endHour * 60 + endMinute
        return if (endTotal > startTotal) {
            endTotal - startTotal
        } else {
            // Cross-midnight: e.g., 23:00 - 02:00 = (24:00 - 23:00) + (02:00 - 00:00) = 180 mins
            (24 * 60 - startTotal) + endTotal
        }
    }

    /**
     * 计算有效可用次数上限。
     * usableCountLimitHalfUnits >= 0 → 使用用户设定值
     * usableCountLimitHalfUnits == -1 → 自动计算 = max(1, limitMins / 5)
     */
    fun getEffectiveUsableCountLimitHalfUnits(): Int {
        if (usableCountLimitHalfUnits >= 0) return usableCountLimitHalfUnits
        return UsableCount.automaticHalfUnits(limitMins)
    }
}

object TimeSegmentJson {

    internal fun normalizeDaysList(days: List<Int>?): List<Int>? {
        if (days == null) return null
        val cleaned = days.filter { it in 1..7 }.distinct().sorted()
        return if (cleaned.isEmpty() || cleaned.size == 7) null else cleaned
    }

    fun segmentsToJson(segments: List<TimeSegment>): String {
         val array = JSONArray()
         segments.forEach { seg ->
             val obj = JSONObject()
             obj.put("startHour", seg.startHour)
             obj.put("startMinute", seg.startMinute)
             obj.put("endHour", seg.endHour)
             obj.put("endMinute", seg.endMinute)
             obj.put("limitMins", seg.limitMins)
             obj.put("name", seg.name)
             obj.put("isCustomized", seg.isCustomized)
             obj.put("isEnabled", seg.isEnabled)

             // 仅在非默认值(-1)时写入，减少 JSON 体积
             if (seg.usableCountLimitHalfUnits != UsableCount.AUTO_HALF_UNITS) {
                 obj.put("usableCountLimitHalfUnits", seg.usableCountLimitHalfUnits)
             }

             if (seg.daysOfWeek != null) {
                 val dArray = JSONArray()
                 seg.daysOfWeek.forEach { dArray.put(it) }
                 obj.put("daysOfWeek", dArray)
             }

             // 🆕 [NEW] 仅在 listApps 非 null 时写入（缺省不写以减小体积）。
             // 空列表同样会写入（序列化为空数组），以区分“脱钩后清空”与“跟随全局”。
             if (seg.listApps != null) {
                 val lArray = JSONArray()
                 seg.listApps.forEach { lArray.put(it) }
                 obj.put("listApps", lArray)
             }
             array.put(obj)
         }
         return array.toString()
    }

    fun parseSegmentsFromJson(json: String): List<TimeSegment> {
        val list = mutableListOf<TimeSegment>()
        try {
            val array = JSONArray(json)
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val days = if (obj.has("daysOfWeek")) {
                    val dArray = obj.getJSONArray("daysOfWeek")
                    val dList = mutableListOf<Int>()
                    for (j in 0 until dArray.length()) dList.add(dArray.getInt(j))
                    normalizeDaysList(dList)
                } else null

                // 🆕 [NEW] 仅在 JSON 存在 listApps 键时读取；存在但为空数组解析为空列表
                // （脱钩后清空），无键解析为 null（跟随全局）。二者必须区分。
                val listApps = if (obj.has("listApps")) {
                    val lArray = obj.getJSONArray("listApps")
                    val lList = mutableListOf<String>()
                    for (j in 0 until lArray.length()) lList.add(lArray.getString(j))
                    lList
                } else null
                
                list.add(TimeSegment(
                    startHour = obj.optInt("startHour"),
                    startMinute = obj.optInt("startMinute"),
                    endHour = obj.optInt("endHour"),
                    endMinute = obj.optInt("endMinute"),
                    limitMins = obj.optInt("limitMins"),
                    name = obj.optString("name"),
                    isCustomized = obj.optBoolean("isCustomized"),
                    daysOfWeek = days,
                    listApps = listApps,
                    isEnabled = obj.optBoolean("isEnabled", true),
                    // optInt 默认 -1 表示"未设置，自动计算"
                    usableCountLimitHalfUnits = when {
                        obj.has("usableCountLimitHalfUnits") -> obj.optInt("usableCountLimitHalfUnits", UsableCount.AUTO_HALF_UNITS)
                        obj.has("usableCountLimit") -> obj.optInt("usableCountLimit", -1).let { if (it < 0) it else it * 2 }
                        else -> UsableCount.AUTO_HALF_UNITS
                    },
                    lastModified = obj.optLong("lastModified", 0L)
                ))
            }
        } catch (e: Exception) {
            android.util.Log.e("TimeRuleManager", "Error parsing segments", e)
        }
        return list
    }
}
