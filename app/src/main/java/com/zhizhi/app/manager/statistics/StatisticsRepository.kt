package com.zhizhi.app.manager.statistics

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Build
import android.util.Log
import com.zhizhi.app.data.statistics.AlarmBucketData
import com.zhizhi.app.data.statistics.AlarmStatsSummary
import com.zhizhi.app.data.statistics.AlarmWakeRecord
import com.zhizhi.app.data.statistics.AppUsageBucketData
import com.zhizhi.app.data.statistics.AppUsageItem
import com.zhizhi.app.data.statistics.AppUsageSummary
import com.zhizhi.app.data.statistics.AppUsageViewMode
import com.zhizhi.app.data.statistics.ExitBucketData
import com.zhizhi.app.data.statistics.ExitStatsSummary
import com.zhizhi.app.data.statistics.ResolvedDateRange
import com.zhizhi.app.data.statistics.RuleProtectionBucketData
import com.zhizhi.app.data.statistics.RuleProtectionItem
import com.zhizhi.app.data.statistics.RuleProtectionSummary
import com.zhizhi.app.data.statistics.StatsBucket
import com.zhizhi.app.data.statistics.StatsDateRange
import com.zhizhi.app.data.statistics.StatsGranularity
import com.zhizhi.app.data.statistics.WeekdayFilter
import com.zhizhi.app.manager.PermissionManager
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 统计数据仓储层
 * 统一提供四大板块（休息、起床、应用、规则保护）的数据聚合。
 * 设计原则：
 * 1. 单真理源：100% 读取设备真实 UsageStatsManager 与业务本地账本；
 * 2. 彻底杜绝伪随机/模拟数据：无记录历史日期严格归零，杜绝虚假折线与未来幽灵数据；
 * 3. 游戏分类智能聚合：合并手机全部已安装游戏，并支持分类视图与单应用视图自由切换。
 */
object StatisticsRepository {

    private const val TAG = "StatisticsRepository"
    private val zoneId = ZoneId.systemDefault()
    private val shortDateFormatter = DateTimeFormatter.ofPattern("MM/dd")

    const val PREFS_NAME = "statistics_prefs"
    const val KEY_CUSTOM_GAMES = "custom_game_packages"
    const val KEY_CUSTOM_NON_GAMES = "custom_non_game_packages"

    data class AppMeta(val name: String, val category: String, val baseOpens: Int, val baseMinutes: Int)

    @Deprecated("Use real on-device data via UsageStatsManager & PackageManager")
    val CANONICAL_APPS = listOf(
        AppMeta("游戏", "娱乐", 4, 38),
        AppMeta("抖音", "娱乐", 3, 24),
        AppMeta("ChatGPT", "AI", 2, 52),
        AppMeta("B站", "娱乐", 2, 33),
        AppMeta("微信", "通讯", 1, 64),
        AppMeta("QQ", "通讯", 1, 46),
        AppMeta("浏览器", "工具", 1, 39),
        AppMeta("知乎", "内容", 2, 29),
        AppMeta("小红书", "娱乐", 3, 27),
        AppMeta("电话", "通讯", 0, 18)
    )

    // For testing override
    @androidx.annotation.VisibleForTesting
    var permissionChecker: ((Context) -> Boolean)? = null

    fun hasUsagePermission(context: Context): Boolean {
        return permissionChecker?.invoke(context) ?: PermissionManager.hasUsageStatsPermission(context)
    }

    // ==========================================
    // 1. 休息 / 正常出口统计 (真实数据源)
    // ==========================================
    private fun parseSegmentStartHour(key: String, segsJson: String?): Int {
        val colonRegex = Regex("""(\d{1,2}):(\d{2})""")
        val colonMatch = colonRegex.find(key)
        if (colonMatch != null) {
            val h = colonMatch.groupValues[1].toIntOrNull()
            if (h != null && h in 0..23) return h
        }

        val segRegex = Regex("""seg_(\d{1,2})_""")
        val segMatch = segRegex.find(key)
        if (segMatch != null) {
            val h = segMatch.groupValues[1].toIntOrNull()
            if (h != null && h in 0..23) return h
        }

        if (!segsJson.isNullOrBlank()) {
            try {
                val array = org.json.JSONArray(segsJson)
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    val id = obj.optString("id")
                    val name = obj.optString("name")
                    val nameClean = name.replace(" ", "").lowercase()
                    if ((id.isNotEmpty() && key.contains(id)) || (nameClean.isNotEmpty() && key.contains(nameClean))) {
                        val startTime = obj.optString("startTime")
                        if (startTime.isNotEmpty()) {
                            val h = startTime.split(":").firstOrNull()?.toIntOrNull()
                            if (h != null && h in 0..23) return h
                        }
                        if (obj.has("startHour")) {
                            return obj.getInt("startHour").coerceIn(0, 23)
                        }
                    }
                }
            } catch (_: Exception) {}
        }

        return 9
    }

    fun getExitStats(
        context: Context,
        range: StatsDateRange,
        resolvedRange: ResolvedDateRange,
        weekdayFilter: WeekdayFilter
    ): ExitStatsSummary {
        val buckets = StatsDateRangeResolver.buildBuckets(range, resolvedRange, weekdayFilter, zoneId)
        val bucketDataList = mutableListOf<ExitBucketData>()

        val today = LocalDate.now(zoneId)
        val nowMillis = System.currentTimeMillis()
        val prefs = try { context.getSharedPreferences("iron_will_ledger", Context.MODE_PRIVATE) } catch (e: Exception) { null }
        val segsJson = prefs?.getString("time_segments_json", null)
        val granularity = StatsDateRangeResolver.resolveBucketGranularity(range, resolvedRange)

        val sLocalDate = LocalDateTime.ofInstant(Instant.ofEpochMilli(resolvedRange.startMillis), zoneId).toLocalDate()
        val eLocalDate = LocalDateTime.ofInstant(Instant.ofEpochMilli(resolvedRange.endMillisExclusive), zoneId).toLocalDate()

        // 1. 范围总计计算：遍历自然日区间（严格单日单计，杜绝按分桶重复累加导致的数据翻倍）
        var sumRestCount = 0
        var sumRestMinutes = 0L
        var sumTempCount = 0
        var sumTempMinutes = 0L
        var sumPrivCount = 0
        var sumPrivMinutes = 0L
        var sumCooldownAttempts = 0

        var curDate = sLocalDate
        while (curDate.isBefore(eLocalDate) || (sLocalDate == eLocalDate && curDate == sLocalDate)) {
            if (weekdayFilter.isEnabled(curDate.dayOfWeek) && !curDate.isAfter(today)) {
                val dateStr = curDate.format(DateTimeFormatter.ofPattern("yyyyMMdd"))
                var dayRestSec = 0
                var dayBorrowSec = 0
                var dayPrivMin = 0

                if (prefs != null) {
                    val prefixUsage = "usage_${dateStr}_"
                    val prefixBorrow = "borrow_time_${dateStr}_"
                    val prefixPriv = "privilege_usage_${dateStr}_"

                    for ((k, v) in prefs.all) {
                        if (k.startsWith(prefixUsage)) {
                            dayRestSec += (v as? Int) ?: 0
                        } else if (k.startsWith(prefixBorrow)) {
                            dayBorrowSec += (v as? Int) ?: 0
                        } else if (k.startsWith(prefixPriv)) {
                            dayPrivMin += (v as? Int) ?: 0
                        }
                    }
                }

                val restM = dayRestSec / 60
                val tempM = dayBorrowSec / 60
                val privM = dayPrivMin

                sumRestMinutes += restM
                if (restM > 0) sumRestCount += 1
                sumTempMinutes += tempM
                if (tempM > 0) sumTempCount += 1
                sumPrivMinutes += privM
                if (privM > 0) sumPrivCount += 1
            }
            curDate = curDate.plusDays(1)
        }

        // 2. 分桶图表数据计算
        if (granularity == StatsGranularity.HOURLY) {
            val hourlyRestSec = IntArray(24)
            val hourlyBorrowSec = IntArray(24)
            val hourlyPrivMin = IntArray(24)

            val dateStr = sLocalDate.format(DateTimeFormatter.ofPattern("yyyyMMdd"))
            if (prefs != null && !sLocalDate.isAfter(today)) {
                val prefixUsage = "usage_${dateStr}_"
                val prefixBorrow = "borrow_time_${dateStr}_"
                val prefixPriv = "privilege_usage_${dateStr}_"

                for ((k, v) in prefs.all) {
                    val value = (v as? Int) ?: 0
                    if (value <= 0) continue
                    if (k.startsWith(prefixUsage)) {
                        val segKey = k.substring(prefixUsage.length)
                        val hour = parseSegmentStartHour(segKey, segsJson)
                        hourlyRestSec[hour] += value
                    } else if (k.startsWith(prefixBorrow)) {
                        val segKey = k.substring(prefixBorrow.length)
                        val hour = parseSegmentStartHour(segKey, segsJson)
                        hourlyBorrowSec[hour] += value
                    } else if (k.startsWith(prefixPriv)) {
                        val segKey = k.substring(prefixPriv.length)
                        val hour = parseSegmentStartHour(segKey, segsJson)
                        hourlyPrivMin[hour] += value
                    }
                }
            }

            for (b in buckets) {
                val bDateTime = LocalDateTime.ofInstant(Instant.ofEpochMilli(b.startMillis), zoneId)
                val isFuture = bDateTime.toLocalDate().isAfter(today) || b.startMillis > nowMillis
                val h = bDateTime.hour
                val restM = if (!isFuture) hourlyRestSec[h] / 60 else 0
                val tempM = if (!isFuture) hourlyBorrowSec[h] / 60 else 0
                val privM = if (!isFuture) hourlyPrivMin[h] else 0

                bucketDataList.add(
                    ExitBucketData(
                        bucket = b,
                        restMinutes = restM.toFloat(),
                        restCount = if (restM > 0) 1 else 0,
                        tempUnlockMinutes = tempM.toFloat(),
                        tempUnlockCount = if (tempM > 0) 1 else 0,
                        privilegeMinutes = privM.toFloat(),
                        privilegeCount = if (privM > 0) 1 else 0
                    )
                )
            }
        } else {
            for (b in buckets) {
                val bStartDateTime = LocalDateTime.ofInstant(Instant.ofEpochMilli(b.startMillis), zoneId)
                val bEndDateTime = LocalDateTime.ofInstant(Instant.ofEpochMilli(b.endMillisExclusive), zoneId)
                val isFuture = bStartDateTime.toLocalDate().isAfter(today) || b.startMillis > nowMillis

                if (isFuture) {
                    bucketDataList.add(
                        ExitBucketData(
                            bucket = b,
                            restMinutes = 0f,
                            restCount = 0,
                            tempUnlockMinutes = 0f,
                            tempUnlockCount = 0,
                            privilegeMinutes = 0f,
                            privilegeCount = 0
                        )
                    )
                    continue
                }

                var bRestSec = 0
                var bBorrowSec = 0
                var bPrivMin = 0
                var cursor = bStartDateTime.toLocalDate()
                val endCursor = bEndDateTime.toLocalDate()

                while (cursor.isBefore(endCursor)) {
                    if (weekdayFilter.isEnabled(cursor.dayOfWeek) && !cursor.isAfter(today)) {
                        val dStr = cursor.format(DateTimeFormatter.ofPattern("yyyyMMdd"))
                        if (prefs != null) {
                            val prefixUsage = "usage_${dStr}_"
                            val prefixBorrow = "borrow_time_${dStr}_"
                            val prefixPriv = "privilege_usage_${dStr}_"

                            for ((k, v) in prefs.all) {
                                if (k.startsWith(prefixUsage)) {
                                    bRestSec += (v as? Int) ?: 0
                                } else if (k.startsWith(prefixBorrow)) {
                                    bBorrowSec += (v as? Int) ?: 0
                                } else if (k.startsWith(prefixPriv)) {
                                    bPrivMin += (v as? Int) ?: 0
                                }
                            }
                        }
                    }
                    cursor = cursor.plusDays(1)
                }

                val restM = bRestSec / 60
                val tempM = bBorrowSec / 60
                val privM = bPrivMin

                bucketDataList.add(
                    ExitBucketData(
                        bucket = b,
                        restMinutes = restM.toFloat(),
                        restCount = if (restM > 0) 1 else 0,
                        tempUnlockMinutes = tempM.toFloat(),
                        tempUnlockCount = if (tempM > 0) 1 else 0,
                        privilegeMinutes = privM.toFloat(),
                        privilegeCount = if (privM > 0) 1 else 0
                    )
                )
            }
        }

        return ExitStatsSummary(
            totalRestCount = sumRestCount,
            totalRestMinutes = sumRestMinutes,
            totalTempUnlockCount = sumTempCount,
            totalTempUnlockMinutes = sumTempMinutes,
            totalPrivilegeCount = sumPrivCount,
            totalPrivilegeMinutes = sumPrivMinutes,
            cooldownAttemptCount = sumCooldownAttempts,
            buckets = bucketDataList
        )
    }

    // ==========================================
    // 2. 应用统计 (真机 UsageStatsManager + 游戏聚合)
    // ==========================================
    fun getAppUsageStats(
        context: Context,
        range: StatsDateRange,
        resolvedRange: ResolvedDateRange,
        weekdayFilter: WeekdayFilter,
        appViewMode: AppUsageViewMode = AppUsageViewMode.CATEGORIZED_GAMES,
        excludedApps: Set<String> = emptySet(),
        selectedApp: String? = null,
        customGamePackages: Set<String>? = null
    ): AppUsageSummary {
        val buckets = StatsDateRangeResolver.buildBuckets(range, resolvedRange, weekdayFilter, zoneId)
        val today = LocalDate.now(zoneId)
        val nowMillis = System.currentTimeMillis()

        val hasPermission = hasUsagePermission(context)
        if (!hasPermission) {
            val emptyBuckets = buckets.map { AppUsageBucketData(it, 0, 0f) }
            return AppUsageSummary(
                totalOpens = 0,
                totalUsageMinutes = 0L,
                appList = emptyList(),
                buckets = emptyBuckets,
                selectedAppTrendBuckets = null,
                hasUsageAccessPermission = false
            )
        }

        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
        if (usm == null) {
            val emptyBuckets = buckets.map { AppUsageBucketData(it, 0, 0f) }
            return AppUsageSummary(
                totalOpens = 0,
                totalUsageMinutes = 0L,
                appList = emptyList(),
                buckets = emptyBuckets,
                selectedAppTrendBuckets = null,
                hasUsageAccessPermission = true
            )
        }

        val pm = context.packageManager
        val installedApps = try {
            pm.getInstalledApplications(0)
        } catch (_: Exception) {
            emptyList()
        }
        val appInfoMap = installedApps.associateBy { it.packageName }

        // 自定义游戏名单
        val statsPrefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val savedCustomGames = statsPrefs.getStringSet(KEY_CUSTOM_GAMES, emptySet()) ?: emptySet()
        val savedCustomNonGames = statsPrefs.getStringSet(KEY_CUSTOM_NON_GAMES, emptySet()) ?: emptySet()
        val effectiveCustomGames = (customGamePackages ?: savedCustomGames) - savedCustomNonGames

        fun isGame(pkg: String, info: ApplicationInfo?): Boolean {
            if (savedCustomNonGames.contains(pkg)) return false
            if (effectiveCustomGames.contains(pkg)) return true
            if (info != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && info.category == ApplicationInfo.CATEGORY_GAME) {
                    return true
                }
                @Suppress("DEPRECATION")
                if ((info.flags and ApplicationInfo.FLAG_IS_GAME) != 0) {
                    return true
                }
            }
            val lower = pkg.lowercase()
            val gameSignatures = listOf(
                "com.tencent.tmgp.", "com.tencent.game.", "com.mihoyo.", "com.hypergryph.",
                "com.netease.", "com.supercell.", "com.riotgames.", "com.epicgames.",
                "com.ea.gp.", "com.mojang.", "com.bilibili.star.", "com.kurogame.",
                "com.yostar.", "com.habby.", ".game.", ".games."
            )
            return gameSignatures.any { lower.contains(it) }
        }

        fun getAppLabel(pkg: String): String {
            val info = appInfoMap[pkg]
            return if (info != null) {
                try { info.loadLabel(pm).toString() } catch (_: Exception) { pkg }
            } else {
                pkg
            }
        }

        fun getCategoryName(pkg: String, isGame: Boolean, info: ApplicationInfo?): String {
            if (isGame) return "游戏"
            if (info == null) return "其他"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                when (info.category) {
                    ApplicationInfo.CATEGORY_AUDIO, ApplicationInfo.CATEGORY_VIDEO -> return "影音"
                    ApplicationInfo.CATEGORY_SOCIAL -> return "社交"
                    ApplicationInfo.CATEGORY_NEWS -> return "资讯"
                    ApplicationInfo.CATEGORY_MAPS -> return "导航"
                    ApplicationInfo.CATEGORY_PRODUCTIVITY -> return "效率"
                    ApplicationInfo.CATEGORY_IMAGE -> return "拍摄"
                }
            }
            return "常用"
        }

        fun isUserFacingApp(pkg: String, info: ApplicationInfo?): Boolean {
            if (pkg == context.packageName) return false
            if (pkg == "android" || pkg == "com.android.systemui") return false
            if (isGame(pkg, info)) return true
            if (info == null) return false
            return pm.getLaunchIntentForPackage(pkg) != null
        }

        // 查询整个范围的事件（一次遍历提高效率）
        val rangeStart = resolvedRange.startMillis
        val rangeEnd = resolvedRange.endMillisExclusive.coerceAtMost(nowMillis)

        val bucketOpensMap = mutableMapOf<StatsBucket, MutableMap<String, Int>>()
        val totalOpensMap = mutableMapOf<String, Int>()

        fun isNoisePackage(packageName: String): Boolean {
            if (packageName.isBlank()) return true
            val lower = packageName.lowercase()
            if (lower.contains("launcher") || lower.contains("recents")) return false
            if (lower.contains("systemui") || lower == "com.vivo.upslide") return true
            if (lower.contains(".inputmethod") || lower.contains(".ime") || lower.contains("inputmethod")) return true
            return false
        }

        if (rangeEnd > rangeStart) {
            try {
                val events = usm.queryEvents(rangeStart, rangeEnd)
                val event = UsageEvents.Event()
                var lastActivePkg: String? = null

                while (events.hasNextEvent()) {
                    events.getNextEvent(event)
                    val eventType = event.eventType
                    val pkg = event.packageName ?: continue
                    val t = event.timeStamp

                    when (eventType) {
                        1 /* MOVE_TO_FOREGROUND */, 15 /* ACTIVITY_RESUMED */ -> {
                            if (isNoisePackage(pkg)) continue

                            // 只有当前台应用发生真实切换时，才算作一次全新的应用打开
                            // 同一应用内部各个页面之间的跳转 (lastActivePkg == pkg)，严禁重复计入打开次数
                            if (pkg != lastActivePkg) {
                                if (isUserFacingApp(pkg, appInfoMap[pkg])) {
                                    val bucket = buckets.find { t >= it.startMillis && t < it.endMillisExclusive }
                                    if (bucket != null) {
                                        val map = bucketOpensMap.getOrPut(bucket) { mutableMapOf() }
                                        map[pkg] = (map[pkg] ?: 0) + 1
                                    }
                                    totalOpensMap[pkg] = (totalOpensMap[pkg] ?: 0) + 1
                                }
                                lastActivePkg = pkg
                            }
                        }
                        16 /* SCREEN_NON_INTERACTIVE */, 17 /* KEYGUARD_SHOWN */ -> {
                            // 息屏或锁屏，前台会话中断；重新亮屏后再进入应用算作新一次使用
                            lastActivePkg = null
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "queryEvents failed: ${e.message}")
            }
        }

        // 逐桶查询真实前台使用时长 & 查询全区间前台时长（杜绝逐桶累加导致的重复倍增）
        val bucketUsageMap = mutableMapOf<StatsBucket, MutableMap<String, Long>>()
        val totalUsageMap = mutableMapOf<String, Long>()

        if (rangeEnd > rangeStart) {
            try {
                val overallStats = usm.queryUsageStats(UsageStatsManager.INTERVAL_BEST, rangeStart, rangeEnd)
                if (overallStats != null) {
                    for (st in overallStats) {
                        val pkg = st.packageName
                        val fgTime = st.totalTimeInForeground
                        if (fgTime > 0) {
                            totalUsageMap[pkg] = (totalUsageMap[pkg] ?: 0L) + fgTime
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "queryUsageStats overall failed: ${e.message}")
            }
        }

        for (b in buckets) {
            val sDate = LocalDateTime.ofInstant(Instant.ofEpochMilli(b.startMillis), zoneId).toLocalDate()
            val isFuture = sDate.isAfter(today) || b.startMillis > nowMillis
            if (isFuture) continue

            val bStart = b.startMillis
            val bEnd = b.endMillisExclusive.coerceAtMost(nowMillis)
            if (bEnd <= bStart) continue

            try {
                val stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_BEST, bStart, bEnd)
                if (stats != null) {
                    val map = bucketUsageMap.getOrPut(b) { mutableMapOf() }
                    for (st in stats) {
                        val pkg = st.packageName
                        val fgTime = st.totalTimeInForeground
                        if (fgTime > 0) {
                            map[pkg] = (map[pkg] ?: 0L) + fgTime
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "queryUsageStats failed for bucket ${b.label}: ${e.message}")
            }
        }

        // 提取所有有使用或打开的有效应用包名
        val allActivePackages = (totalUsageMap.keys + totalOpensMap.keys).filter { pkg ->
            isUserFacingApp(pkg, appInfoMap[pkg])
        }.toSet()

        val allGamePackages = allActivePackages.filter { pkg -> isGame(pkg, appInfoMap[pkg]) }.toSet()
        val allNonGamePackages = allActivePackages - allGamePackages

        // 生成应用列表
        val finalAppList = mutableListOf<AppUsageItem>()

        if (appViewMode == AppUsageViewMode.CATEGORIZED_GAMES) {
            // 分类汇总模式：所有游戏合并为单一虚拟大项「游戏」
            var gameOpens = 0
            var gameMillis = 0L
            allGamePackages.forEach { pkg ->
                val appLabel = getAppLabel(pkg)
                if (!excludedApps.contains(appLabel) && !excludedApps.contains(pkg) && !excludedApps.contains("游戏")) {
                    gameOpens += totalOpensMap[pkg] ?: 0
                    gameMillis += totalUsageMap[pkg] ?: 0L
                }
            }

            if ((gameOpens > 0 || gameMillis > 0 || allGamePackages.isNotEmpty()) && !excludedApps.contains("游戏")) {
                val gameMinutes = (gameMillis / 60_000L).coerceAtLeast(if (gameMillis > 15_000L) 1L else 0L)
                finalAppList.add(
                    AppUsageItem(
                        packageName = "category.games",
                        appName = "游戏",
                        category = "游戏",
                        openCount = gameOpens,
                        usageDurationMinutes = gameMinutes,
                        isGameCategory = true
                    )
                )
            }

            // 非游戏应用独立展示
            allNonGamePackages.forEach { pkg ->
                val label = getAppLabel(pkg)
                if (!excludedApps.contains(label) && !excludedApps.contains(pkg)) {
                    val opens = totalOpensMap[pkg] ?: 0
                    val millis = totalUsageMap[pkg] ?: 0L
                    if (opens > 0 || millis > 0) {
                        val minutes = (millis / 60_000L).coerceAtLeast(if (millis > 15_000L) 1L else 0L)
                        finalAppList.add(
                            AppUsageItem(
                                packageName = pkg,
                                appName = label,
                                category = getCategoryName(pkg, false, appInfoMap[pkg]),
                                openCount = opens,
                                usageDurationMinutes = minutes,
                                isGameCategory = false
                            )
                        )
                    }
                }
            }
        } else {
            // 单应用真实排行模式：每个应用（包括单款游戏）单独展示
            allActivePackages.forEach { pkg ->
                val label = getAppLabel(pkg)
                if (!excludedApps.contains(label) && !excludedApps.contains(pkg)) {
                    val opens = totalOpensMap[pkg] ?: 0
                    val millis = totalUsageMap[pkg] ?: 0L
                    if (opens > 0 || millis > 0) {
                        val isG = isGame(pkg, appInfoMap[pkg])
                        val minutes = (millis / 60_000L).coerceAtLeast(if (millis > 15_000L) 1L else 0L)
                        finalAppList.add(
                            AppUsageItem(
                                packageName = pkg,
                                appName = label,
                                category = getCategoryName(pkg, isG, appInfoMap[pkg]),
                                openCount = opens,
                                usageDurationMinutes = minutes,
                                isGameCategory = false
                            )
                        )
                    }
                }
            }
        }

        val totalOpens = finalAppList.sumOf { it.openCount }
        val totalUsageMinutes = finalAppList.sumOf { it.usageDurationMinutes }

        // 构建分桶趋势
        val bucketDataList = buckets.map { b ->
            val sDate = LocalDateTime.ofInstant(Instant.ofEpochMilli(b.startMillis), zoneId).toLocalDate()
            val isFuture = sDate.isAfter(today) || b.startMillis > nowMillis
            if (isFuture) {
                return@map AppUsageBucketData(b, 0, 0f)
            }

            val bUsage = bucketUsageMap[b] ?: emptyMap()
            val bOpens = bucketOpensMap[b] ?: emptyMap()

            var bucketTotalOpens = 0
            var bucketTotalMillis = 0L

            for (pkg in allActivePackages) {
                val label = getAppLabel(pkg)
                if (excludedApps.contains(label) || excludedApps.contains(pkg)) continue
                if (appViewMode == AppUsageViewMode.CATEGORIZED_GAMES && isGame(pkg, appInfoMap[pkg]) && excludedApps.contains("游戏")) continue

                bucketTotalOpens += bOpens[pkg] ?: 0
                bucketTotalMillis += bUsage[pkg] ?: 0L
            }

            val minutes = bucketTotalMillis / 60_000f
            AppUsageBucketData(b, bucketTotalOpens, minutes)
        }

        // 单应用或游戏大类单独选中的趋势
        val selectedTrend = if (selectedApp != null) {
            buckets.map { b ->
                val sDate = LocalDateTime.ofInstant(Instant.ofEpochMilli(b.startMillis), zoneId).toLocalDate()
                val isFuture = sDate.isAfter(today) || b.startMillis > nowMillis
                if (isFuture) {
                    return@map AppUsageBucketData(b, 0, 0f)
                }

                val bUsage = bucketUsageMap[b] ?: emptyMap()
                val bOpens = bucketOpensMap[b] ?: emptyMap()

                if (selectedApp == "游戏" && appViewMode == AppUsageViewMode.CATEGORIZED_GAMES) {
                    var gOpens = 0
                    var gMillis = 0L
                    for (gPkg in allGamePackages) {
                        gOpens += bOpens[gPkg] ?: 0
                        gMillis += bUsage[gPkg] ?: 0L
                    }
                    AppUsageBucketData(b, gOpens, gMillis / 60_000f)
                } else {
                    val matchingPkg = allActivePackages.firstOrNull { getAppLabel(it) == selectedApp || it == selectedApp }
                    if (matchingPkg != null) {
                        val opens = bOpens[matchingPkg] ?: 0
                        val millis = bUsage[matchingPkg] ?: 0L
                        AppUsageBucketData(b, opens, millis / 60_000f)
                    } else {
                        AppUsageBucketData(b, 0, 0f)
                    }
                }
            }
        } else null

        return AppUsageSummary(
            totalOpens = totalOpens,
            totalUsageMinutes = totalUsageMinutes,
            appList = finalAppList,
            buckets = bucketDataList,
            selectedAppTrendBuckets = selectedTrend,
            hasUsageAccessPermission = true
        )
    }

    // ==========================================
    // 3. 闹钟挑战统计 (真实打卡记录，无模拟)
    // ==========================================
    fun getAlarmStats(
        context: Context,
        range: StatsDateRange,
        resolvedRange: ResolvedDateRange,
        weekdayFilter: WeekdayFilter
    ): AlarmStatsSummary {
        val buckets = StatsDateRangeResolver.buildBuckets(range, resolvedRange, weekdayFilter, zoneId)
        val allRecords = mutableListOf<AlarmWakeRecord>()

        val today = LocalDate.now(zoneId)
        val nowMillis = System.currentTimeMillis()
        val prefs = try { context.getSharedPreferences("alarm_stats_ledger", Context.MODE_PRIVATE) } catch (_: Exception) { null }

        val bucketDataList = buckets.map { b ->
            val sDate = LocalDateTime.ofInstant(Instant.ofEpochMilli(b.startMillis), zoneId).toLocalDate()
            val isFuture = sDate.isAfter(today) || b.startMillis > nowMillis

            if (isFuture || prefs == null) {
                return@map AlarmBucketData(b, null, null, null, null)
            }

            val dateKey = sDate.format(DateTimeFormatter.ofPattern("yyyyMMdd"))
            if (prefs.contains("alarm_${dateKey}_wake_time")) {
                val wakeMinute = prefs.getInt("alarm_${dateKey}_wake_time", 420)
                val stage1 = prefs.getInt("alarm_${dateKey}_stage1_duration", 5)
                val total = prefs.getInt("alarm_${dateKey}_total_duration", 8)
                val rec = AlarmWakeRecord(
                    id = "alarm-$dateKey",
                    dateLabel = sDate.format(shortDateFormatter),
                    alarmMinuteOfDay = wakeMinute,
                    firstStageCompletedMinute = wakeMinute + stage1,
                    firstStageDurationMinutes = stage1,
                    totalFlowDurationMinutes = total
                )
                allRecords.add(rec)
                AlarmBucketData(
                    bucket = b,
                    alarmMinuteOfDay = wakeMinute.toFloat(),
                    firstStageCompletedMinute = (wakeMinute + stage1).toFloat(),
                    firstStageDurationMinutes = stage1.toFloat(),
                    totalFlowDurationMinutes = total.toFloat()
                )
            } else {
                AlarmBucketData(b, null, null, null, null)
            }
        }

        val validRecords = allRecords
        val medianAlarm = if (validRecords.isNotEmpty()) {
            val sorted = validRecords.map { it.alarmMinuteOfDay }.sorted()
            sorted[sorted.size / 2]
        } else null

        val medianStage1 = if (validRecords.isNotEmpty()) {
            val sorted = validRecords.map { it.firstStageDurationMinutes }.sorted()
            sorted[sorted.size / 2]
        } else null

        val medianTotal = if (validRecords.isNotEmpty()) {
            val sorted = validRecords.map { it.totalFlowDurationMinutes }.sorted()
            sorted[sorted.size / 2]
        } else null

        val recent = validRecords.takeLast(5).reversed()

        return AlarmStatsSummary(
            medianAlarmMinuteOfDay = medianAlarm,
            medianFirstStageDurationMinutes = medianStage1,
            medianTotalFlowDurationMinutes = medianTotal,
            recordedDaysCount = validRecords.size,
            buckets = bucketDataList,
            recentRecords = recent
        )
    }

    // ==========================================
    // 4. 规则保护统计 (真实 baseline/pending，无模拟)
    // ==========================================
    fun getRuleProtectionStats(
        context: Context,
        range: StatsDateRange,
        resolvedRange: ResolvedDateRange,
        weekdayFilter: WeekdayFilter
    ): RuleProtectionSummary {
        val buckets = StatsDateRangeResolver.buildBuckets(range, resolvedRange, weekdayFilter, zoneId)
        val today = LocalDate.now(zoneId)
        val nowMillis = System.currentTimeMillis()

        val pendingPrefs = try { context.getSharedPreferences("pending_config_prefs", Context.MODE_PRIVATE) } catch (_: Exception) { null }
        val pendingCount = pendingPrefs?.all?.size ?: 0

        val rulePrefs = try { context.getSharedPreferences("rule_protection_ledger", Context.MODE_PRIVATE) } catch (_: Exception) { null }
        val recentChanges = mutableListOf<RuleProtectionItem>()

        var totalFull = 0
        var totalPartial = 0
        var totalEffective = 0

        val bucketDataList = buckets.map { b ->
            val sDate = LocalDateTime.ofInstant(Instant.ofEpochMilli(b.startMillis), zoneId).toLocalDate()
            val isFuture = sDate.isAfter(today) || b.startMillis > nowMillis

            if (isFuture || rulePrefs == null) {
                return@map RuleProtectionBucketData(
                    bucket = b,
                    fullyRestoredCount = 0,
                    partiallyRestoredCount = 0,
                    effectiveCount = 0,
                    pendingCount = if (sDate == today) pendingCount else 0
                )
            }

            val dateKey = sDate.format(DateTimeFormatter.ofPattern("yyyyMMdd"))
            val bFull = rulePrefs.getInt("rule_${dateKey}_full", 0)
            val bPartial = rulePrefs.getInt("rule_${dateKey}_partial", 0)
            val bEffective = rulePrefs.getInt("rule_${dateKey}_effective", 0)

            totalFull += bFull
            totalPartial += bPartial
            totalEffective += bEffective

            RuleProtectionBucketData(
                bucket = b,
                fullyRestoredCount = bFull,
                partiallyRestoredCount = bPartial,
                effectiveCount = bEffective,
                pendingCount = if (sDate == today) pendingCount else 0
            )
        }

        val totalLoosen = totalFull + totalPartial + totalEffective

        return RuleProtectionSummary(
            totalLoosenItems = totalLoosen,
            fullyRestoredCount = totalFull,
            partiallyRestoredCount = totalPartial,
            effectiveCount = totalEffective,
            pendingCount = pendingCount,
            buckets = bucketDataList,
            recentChanges = recentChanges
        )
    }

    // ==========================================
    // 5. 应用与游戏名单管理辅助
    // ==========================================
    data class InstalledAppMeta(
        val packageName: String,
        val appName: String,
        val category: String,
        val isGame: Boolean
    )

    fun getInstalledApps(context: Context): List<InstalledAppMeta> {
        val pm = context.packageManager
        val installed = try { pm.getInstalledApplications(0) } catch (_: Exception) { emptyList() }
        val statsPrefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val customGames = statsPrefs.getStringSet(KEY_CUSTOM_GAMES, emptySet()) ?: emptySet()
        val customNonGames = statsPrefs.getStringSet(KEY_CUSTOM_NON_GAMES, emptySet()) ?: emptySet()

        return installed.filter {
            if (it.packageName == context.packageName) return@filter false
            if (it.packageName == "android" || it.packageName == "com.android.systemui") return@filter false
            pm.getLaunchIntentForPackage(it.packageName) != null
        }.map { info ->
            val pkg = info.packageName
            val label = try { info.loadLabel(pm).toString() } catch (_: Exception) { pkg }
            val isG = when {
                customNonGames.contains(pkg) -> false
                customGames.contains(pkg) -> true
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && info.category == ApplicationInfo.CATEGORY_GAME -> true
                @Suppress("DEPRECATION") (info.flags and ApplicationInfo.FLAG_IS_GAME) != 0 -> true
                else -> {
                    val lower = pkg.lowercase()
                    listOf("com.tencent.tmgp.", "com.mihoyo.", "com.hypergryph.", "com.netease.", ".game.", ".games.").any { lower.contains(it) }
                }
            }
            InstalledAppMeta(
                packageName = pkg,
                appName = label,
                category = if (isG) "游戏" else "应用",
                isGame = isG
            )
        }.sortedBy { it.appName }
    }

    fun toggleCustomGame(context: Context, packageName: String, isGame: Boolean) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val games = (prefs.getStringSet(KEY_CUSTOM_GAMES, emptySet()) ?: emptySet()).toMutableSet()
        val nonGames = (prefs.getStringSet(KEY_CUSTOM_NON_GAMES, emptySet()) ?: emptySet()).toMutableSet()

        if (isGame) {
            games.add(packageName)
            nonGames.remove(packageName)
        } else {
            games.remove(packageName)
            nonGames.add(packageName)
        }

        prefs.edit()
            .putStringSet(KEY_CUSTOM_GAMES, games)
            .putStringSet(KEY_CUSTOM_NON_GAMES, nonGames)
            .apply()
    }
}
