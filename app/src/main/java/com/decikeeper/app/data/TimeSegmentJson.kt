package com.decikeeper.app.data

import com.decikeeper.app.MyApplication
import java.util.Calendar
import org.json.JSONArray
import org.json.JSONObject

/**
 * Canonical clock for quota-rule weekday eligibility.
 *
 * 决定守护的 quota day is not the natural 00:00-24:00 day. It is the interval
 * between two configured effective/refresh boundaries. Before today's refresh
 * time we are still inside the previous logical day, so weekday-based segment
 * activation must continue using the previous day of week.
 *
 * Explicit calendar/statistics callers can still pass a concrete day directly
 * to [TimeSegment.isActiveToday]; this clock is only the default for live rules.
 */
object LogicalDayClock {
    private const val LEDGER_PREFS_NAME = "decikeeper_ledger"
    private const val KEY_REFRESH_TIME = "config_refresh_time"
    private const val DEFAULT_REFRESH_MINUTES = 8 * 60

    fun resolveDayOfWeek(timestamp: Long, refreshMinutes: Int): Int {
        val effectiveRefreshMinutes = refreshMinutes.coerceIn(0, 24 * 60 - 1)
        val cal = Calendar.getInstance().apply { timeInMillis = timestamp }
        val currentMinutes = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        if (currentMinutes < effectiveRefreshMinutes) {
            cal.add(Calendar.DAY_OF_YEAR, -1)
        }
        return cal.get(Calendar.DAY_OF_WEEK)
    }

    fun currentConfiguredRefreshMinutes(): Int {
        return runCatching {
            val raw = MyApplication.instance
                .getSharedPreferences(LEDGER_PREFS_NAME, android.content.Context.MODE_PRIVATE)
                .getString(KEY_REFRESH_TIME, "08:00")
            parseRefreshMinutes(raw)
        }.getOrDefault(DEFAULT_REFRESH_MINUTES)
    }

    fun currentConfiguredDayOfWeek(nowMillis: Long = System.currentTimeMillis()): Int {
        return resolveDayOfWeek(nowMillis, currentConfiguredRefreshMinutes())
    }

    private fun parseRefreshMinutes(raw: String?): Int {
        if (raw.isNullOrBlank()) return DEFAULT_REFRESH_MINUTES
        val parts = raw.split(":")
        if (parts.size != 2) return DEFAULT_REFRESH_MINUTES
        val hour = parts[0].toIntOrNull() ?: return DEFAULT_REFRESH_MINUTES
        val minute = parts[1].toIntOrNull() ?: return DEFAULT_REFRESH_MINUTES
        if (hour !in 0..23 || minute !in 0..59) return DEFAULT_REFRESH_MINUTES
        return hour * 60 + minute
    }
}

/**
 * Live activation policy for a single logical-day segment occurrence.
 *
 * 启用已停用的时段等同新建时段：启用时刻在起点前 → 满额；进行中 → 按（启用时刻到结束）÷ 整段折算；
 * 已结束 → 当天额度为 0。额度折算由「今日额度」账承担（`CreditManager.applyEntitlementDelta`），
 * 折算后的额度正常参与滚存，所以这里不再因为启用得晚而取消滚存资格，只看是否启用。
 * 其余参数保留是为了兼容既有调用方。
 */
object SegmentActivationPolicy {
    @Suppress("UNUSED_PARAMETER")
    fun isEligibleForLiveRules(
        isEnabled: Boolean,
        enabledStateChangedAt: Long,
        segmentStartHour: Int,
        segmentStartMinute: Int,
        segmentEndHour: Int,
        segmentEndMinute: Int,
        refreshMinutes: Int,
        nowMillis: Long
    ): Boolean = isEnabled
}

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
    // 可用次数上限。-1=自动计算(max(1, round(limitMins/10)) 次)，0=全禁，>0=用户设定
    val usableCountLimitHalfUnits: Int = UsableCount.AUTO_HALF_UNITS,
    // Legacy field retained for old serialized data compatibility.
    val lastModified: Long = 0L,
    // 🆕 [NEW] 名单模式：blacklist=黑名单(勾选即拦截)；whitelist=白名单(勾选即放行，其余拦截)。
    // listApps 始终存储「有效黑名单集合」（白名单模式已在保存时转成补集），
    // 本字段仅用于界面展示/编辑时的模式还原；跟随全局(listApps==null)时忽略。
    val listMode: String = SegmentBlacklist.LIST_MODE_BLACKLIST,
    // 🆕 [NEW] 本时段独立黑名单。null=跟随全局黑名单（继承态）；
    // 非空列表=本时段独立名单（脱钩态）；空列表=本时段什么都不锁。
    // 置于末尾以避免破坏既有位置参数构造调用。
    val listApps: List<String>? = null,
    // Timestamp of the most recent enabled/disabled transition. Separate from
    // lastModified so unrelated edits do not revoke carry eligibility.
    val enabledStateChangedAt: Long = 0L,
    // Persisted enabled state used to detect copy(isEnabled=...) without requiring
    // every UI caller to remember to stamp a timestamp explicitly.
    val enabledStateBaseline: Boolean = isEnabled
) {
    fun isActiveToday(checkDayInput: Int? = null): Boolean {
        // 🚀 Check Enabled State First
        if (!isEnabled) return false

        val isExplicitCalendarCheck = checkDayInput != null
        // Live rule evaluation follows the logical/business day (refresh -> refresh),
        // not the physical weekday that flips at 00:00. Explicit callers (stats,
        // historical reports) can still supply their own concrete calendar day.
        val currentDay = checkDayInput ?: LogicalDayClock.currentConfiguredDayOfWeek()
        // If daysOfWeek is defined and NOT empty, check if today is included
        if (daysOfWeek != null && daysOfWeek.isNotEmpty() && !daysOfWeek.contains(currentDay)) {
            return false // Not active on this logical day
        }

        // Explicit calendar/statistics checks ask only about configured weekday
        // eligibility; they must not depend on the current clock or toggle history.
        if (isExplicitCalendarCheck) return true

        val now = System.currentTimeMillis()
        val effectiveChangedAt = if (enabledStateBaseline != isEnabled) now else enabledStateChangedAt
        return SegmentActivationPolicy.isEligibleForLiveRules(
            isEnabled = isEnabled,
            enabledStateChangedAt = effectiveChangedAt,
            segmentStartHour = startHour,
            segmentStartMinute = startMinute,
            segmentEndHour = endHour,
            segmentEndMinute = endMinute,
            refreshMinutes = LogicalDayClock.currentConfiguredRefreshMinutes(),
            nowMillis = now
        )
    }

    fun isInSegment(h: Int, m: Int): Boolean {
        // 🚀 [Debug] Verbose logging disabled to prevent spam
        // android.util.Log.d("DeciKeeper_Time", "Checking Segment $name ($startHour:$startMinute-$endHour:$endMinute) Enabled=$isEnabled vs Now $h:$m. ActiveToday=${isActiveToday()}, daysOfWeek=$daysOfWeek")
        
        if (!isActiveToday()) {
            // android.util.Log.d("DeciKeeper_Time", "  -> FALSE (not active today or disabled)")
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
            current >= start && current < end
        }
        
        // android.util.Log.d("DeciKeeper_Time", "  -> current=$current, start=$start, end=$end, crossMidnight=${start > end}, result=$result")
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
     * usableCountLimitHalfUnits == -1 → 自动计算 = max(1, round(limitMins / 10)) 次
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

             // A copied segment retains the persisted baseline. A changed toggle is
             // therefore detectable here even if the UI only did copy(isEnabled=...).
             // Minute precision matches segment boundary precision and keeps repeated
             // serialization deterministic within the same minute.
             val enabledStateChangedAt = if (seg.enabledStateBaseline != seg.isEnabled) {
                 (System.currentTimeMillis() / 60_000L) * 60_000L
             } else {
                 seg.enabledStateChangedAt
             }
             obj.put("enabledStateBaseline", seg.isEnabled)
             if (enabledStateChangedAt > 0L) {
                 obj.put("enabledStateChangedAt", enabledStateChangedAt)
             }

             // 仅在非默认值(-1)时写入，减少 JSON 体积
             if (seg.usableCountLimitHalfUnits != UsableCount.AUTO_HALF_UNITS) {
                 obj.put("usableCountLimitHalfUnits", seg.usableCountLimitHalfUnits)
             }

             if (seg.lastModified > 0L) {
                 obj.put("lastModified", seg.lastModified)
             }

             if (seg.daysOfWeek != null) {
                 val dArray = JSONArray()
                 seg.daysOfWeek.forEach { dArray.put(it) }
                 obj.put("daysOfWeek", dArray)
             }

             // 🆕 [NEW] 仅在 listApps 非 null 时读取；存在但为空数组解析为空列表
             // （脱钩后清空），无键解析为 null（跟随全局）。二者必须区分。
             if (seg.listApps != null) {
                 val lArray = JSONArray()
                 seg.listApps.forEach { lArray.put(it) }
                 obj.put("listApps", lArray)
             }

             // 🆕 [NEW] 名单模式：默认黑名单不写；白名单写入。
             if (seg.listMode != SegmentBlacklist.LIST_MODE_BLACKLIST) {
                 obj.put("listMode", seg.listMode)
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
                val isEnabled = obj.optBoolean("isEnabled", true)
                
                list.add(TimeSegment(
                    startHour = obj.optInt("startHour"),
                    startMinute = obj.optInt("startMinute"),
                    endHour = obj.optInt("endHour"),
                    endMinute = obj.optInt("endMinute"),
                    limitMins = obj.optInt("limitMins"),
                    name = obj.optString("name"),
                    isCustomized = obj.optBoolean("isCustomized"),
                    daysOfWeek = days,
                    listMode = obj.optString("listMode", SegmentBlacklist.LIST_MODE_BLACKLIST),
                    listApps = listApps,
                    isEnabled = isEnabled,
                    // optInt 默认 -1 表示"未设置，自动计算"
                    usableCountLimitHalfUnits = when {
                        obj.has("usableCountLimitHalfUnits") -> obj.optInt("usableCountLimitHalfUnits", UsableCount.AUTO_HALF_UNITS)
                        obj.has("usableCountLimit") -> obj.optInt("usableCountLimit", -1).let { if (it < 0) it else it * 2 }
                        else -> UsableCount.AUTO_HALF_UNITS
                    },
                    lastModified = obj.optLong("lastModified", 0L),
                    enabledStateChangedAt = obj.optLong("enabledStateChangedAt", 0L),
                    enabledStateBaseline = obj.optBoolean("enabledStateBaseline", isEnabled)
                ))
            }
        } catch (e: Exception) {
            android.util.Log.e("TimeRuleManager", "Error parsing segments", e)
        }
        return list
    }
}
