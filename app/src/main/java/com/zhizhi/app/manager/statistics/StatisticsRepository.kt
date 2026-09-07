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
import com.zhizhi.app.data.statistics.AppCategory
import com.zhizhi.app.data.statistics.CategoryDetailData
import com.zhizhi.app.data.statistics.CategoryUsageSummary
import com.zhizhi.app.data.statistics.ExitBucketData
import com.zhizhi.app.data.statistics.ExitStatsSummary
import com.zhizhi.app.data.statistics.ResolvedDateRange
import com.zhizhi.app.data.statistics.RuleProtectionBucketData
import com.zhizhi.app.data.statistics.RuleProtectionItem
import com.zhizhi.app.data.statistics.RuleProtectionSummary
import com.zhizhi.app.data.statistics.RuleRelaxationState
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
    const val KEY_CUSTOM_APP_CATEGORIES = "custom_app_categories_map"

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

    fun getCustomCategories(context: Context): Map<String, AppCategory> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val set = prefs.getStringSet(KEY_CUSTOM_APP_CATEGORIES, emptySet()) ?: emptySet()
        val map = mutableMapOf<String, AppCategory>()
        for (entry in set) {
            val colonIdx = entry.indexOf(':')
            if (colonIdx > 0) {
                val pkg = entry.substring(0, colonIdx)
                val catId = entry.substring(colonIdx + 1)
                AppCategory.fromId(catId)?.let { map[pkg] = it }
            }
        }
        return map
    }

    fun setCustomCategory(context: Context, packageName: String, category: AppCategory?) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val set = (prefs.getStringSet(KEY_CUSTOM_APP_CATEGORIES, emptySet()) ?: emptySet()).toMutableSet()
        set.removeAll { it.startsWith("$packageName:") }
        if (category != null) {
            set.add("$packageName:${category.id}")
            if (category == AppCategory.GAME) {
                toggleCustomGame(context, packageName, true)
            } else {
                toggleCustomGame(context, packageName, false)
            }
        }
        prefs.edit().putStringSet(KEY_CUSTOM_APP_CATEGORIES, set).apply()
    }

    fun isGame(context: Context, pkg: String, info: ApplicationInfo?): Boolean {
        val statsPrefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val customCategories = getCustomCategories(context)
        val customCat = customCategories[pkg]
        if (customCat != null) {
            return customCat == AppCategory.GAME
        }

        val customGames = statsPrefs.getStringSet(KEY_CUSTOM_GAMES, emptySet()) ?: emptySet()
        val customNonGames = statsPrefs.getStringSet(KEY_CUSTOM_NON_GAMES, emptySet()) ?: emptySet()

        if (customNonGames.contains(pkg)) return false
        if (customGames.contains(pkg)) return true

        val lower = pkg.lowercase()

        // 明确非游戏的网易/腾讯等大厂产品与远程工具白名单（优先排除，防止误判）
        val nonGameExclusions = listOf(
            "com.netease.cloudmusic", "com.netease.gameremote", "com.netease.uu",
            "com.netease.newsreader", "com.netease.mail", "com.netease.mobimail",
            "com.youdao.", "com.tencent.mm", "com.tencent.mobileqq", "com.tencent.tim",
            "com.tencent.wework", "com.tencent.qqlive", "com.tencent.qqmusic",
            "com.oray.sunlogin", "com.todesk", "com.teamviewer", "com.anydesk"
        )
        if (nonGameExclusions.any { lower.contains(it) }) return false

        if (info != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && info.category == ApplicationInfo.CATEGORY_GAME) {
                return true
            }
            @Suppress("DEPRECATION")
            if ((info.flags and ApplicationInfo.FLAG_IS_GAME) != 0) {
                return true
            }
        }

        val gameSignatures = listOf(
            "com.tencent.tmgp.", "com.tencent.game.", "com.mihoyo.", "com.hypergryph.",
            "com.supercell.", "com.riotgames.", "com.epicgames.",
            "com.ea.gp.", "com.mojang.", "com.bilibili.star.", "com.kurogame.",
            "com.yostar.", "com.habby.",
            // 精确网易主流游戏包名前缀
            "com.netease.moba.", "com.netease.party.", "com.netease.sky.",
            "com.netease.onmyoji", "com.netease.hyxd", "com.netease.mc.",
            "com.netease.dwrg", "com.netease.yys", "com.netease.shelter",
            "com.netease.ace", "com.netease.ldxy", "com.netease.wepie",
            "com.netease.h10.", "com.netease.g", "com.netease.x19",
            // 包名必须带有两端点的 .game. 或 .games.，避免 gameremote 等工具误伤
            ".game.", ".games."
        )
        return gameSignatures.any { lower.contains(it) }
    }

    /**
     * 科学行为 6 分类单真理源解析器
     */
    fun resolveCategory(context: Context, pkg: String, info: ApplicationInfo?): AppCategory {
        // 1. 用户自定义显式分类优先级最高
        val customCat = getCustomCategories(context)[pkg]
        if (customCat != null) return customCat

        val lower = pkg.lowercase()

        // 2. 远程桌面与实用工具优先收拢（防止带有 game 关键字或网易前缀的工具被误归入游戏）
        val remoteAndToolsSignatures = listOf(
            "com.netease.gameremote", "com.netease.uu", "com.oray.sunlogin",
            "com.todesk", "com.teamviewer", "com.anydesk"
        )
        if (remoteAndToolsSignatures.any { lower.contains(it) }) {
            return AppCategory.TOOLS_SYSTEM
        }

        // 3. 音乐音频 (在线音乐与有声播客) - 优先于游戏，防止网易云等被误伤
        val audioSignatures = listOf(
            "com.netease.cloudmusic", "com.tencent.qqmusic", "com.kugou.android",
            "cn.kuwo.player", "com.spotify.music", "com.luna.music", "com.ximalaya.ting.android",
            "fm.qingting", "app.podcast.cosmos", "com.apple.android.music", "podcast", "music", "audio"
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && info?.category == ApplicationInfo.CATEGORY_AUDIO) {
            return AppCategory.AUDIO
        }
        if (audioSignatures.any { lower.contains(it) }) return AppCategory.AUDIO

        // 4. 游戏
        if (isGame(context, pkg, info)) return AppCategory.GAME

        // 5. 视频娱乐 (含短视频与流媒体)
        val videoSignatures = listOf(
            "com.ss.android.ugc.aweme", "com.ss.android.ugc.live", "com.smile.gifmaker",
            "com.kuaishou.nebula", "tv.danmaku.bili", "com.bilibili.app.in", "tv.danmaku.bilibilihd",
            "com.xingin.xhs", "com.qiyi.video", "com.iqiyi.i18n", "com.tencent.qqlive",
            "com.youku.phone", "com.mgtv", "com.ss.android.article.video",
            "com.google.android.youtube", "video", "douyin", "kuaishou", "bilibili"
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && info?.category == ApplicationInfo.CATEGORY_VIDEO) {
            return AppCategory.VIDEO
        }
        if (videoSignatures.any { lower.contains(it) }) return AppCategory.VIDEO

        // 6. AI 与效率 (AI 助手与办公生产力)
        val aiSignatures = listOf(
            "com.openai.chatgpt", "com.anthropic.claude", "com.deepseek.chat",
            "com.moonshot.kimi", "com.doubao", "com.larus.nova", "com.baidu.wenxin",
            "com.alibaba.aliyun.tongyi", "com.zhipu.ai", "com.metaso.app",
            "notion.id", "cn.wps.moffice", "com.microsoft.office", "md.obsidian",
            "com.flomo.app", "com.youdao.note", "com.evernote", "ai.chat", "chatgpt", "deepseek"
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && info?.category == ApplicationInfo.CATEGORY_PRODUCTIVITY) {
            return AppCategory.AI_PRODUCTIVITY
        }
        if (aiSignatures.any { lower.contains(it) }) return AppCategory.AI_PRODUCTIVITY

        // 7. 社交通讯 (即时聊天与社交平台)
        val commSignatures = listOf(
            "com.tencent.mm", "com.tencent.mobileqq", "com.tencent.tim", "com.tencent.wework",
            "com.alibaba.android.rimet", "com.ss.android.lark", "org.telegram.messenger",
            "org.thunderdog.challegram", "com.whatsapp", "com.sina.weibo", "com.weico.international",
            "com.zhihu.android", "com.baidu.tieba", "cn.soulapp.android", "com.immomo.momo",
            "com.android.mms", "com.android.dialer", "com.android.contacts",
            "com.google.android.dialer", "com.google.android.apps.messaging",
            "im", "message", "chat"
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && info?.category == ApplicationInfo.CATEGORY_SOCIAL) {
            return AppCategory.COMMUNICATION
        }
        if (commSignatures.any { lower.contains(it) }) return AppCategory.COMMUNICATION

        // 8. 系统与工具 (默认兜底)
        return AppCategory.TOOLS_SYSTEM
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

                // 读取显式记录的出口事件计数，兼容历史账本
                val recRestCount = prefs?.getInt("exit_rest_count_$dateStr", 0) ?: 0
                var fallbackRestCount = 0
                if (prefs != null) {
                    val prefixCount = "count_usage_half_units_v2_${dateStr}_"
                    for ((k, v) in prefs.all) {
                        if (k.startsWith(prefixCount)) {
                            fallbackRestCount += ((v as? Int) ?: 0)
                        }
                    }
                }
                val dayRestCount = maxOf(recRestCount, fallbackRestCount, if (restM > 0) 1 else 0)

                val recTempCount = prefs?.getInt("exit_temp_unlock_count_$dateStr", 0) ?: 0
                var fallbackTempCount = 0
                if (prefs != null) {
                    val prefixBorrowV2 = "count_borrow_half_units_v2_${dateStr}_"
                    val prefixBorrowLegacy = "count_borrow_${dateStr}_"
                    for ((k, v) in prefs.all) {
                        if (k.startsWith(prefixBorrowV2) || k.startsWith(prefixBorrowLegacy)) {
                            fallbackTempCount += ((v as? Int) ?: 0)
                        }
                    }
                }
                val dayTempCount = maxOf(recTempCount, fallbackTempCount, if (tempM > 0) 1 else 0)

                val recPrivCount = prefs?.getInt("exit_privilege_count_$dateStr", 0) ?: 0
                val dayPrivCount = maxOf(recPrivCount, if (privM > 0) 1 else 0)

                val dayCooldownAttempts = prefs?.getInt("exit_cooldown_attempt_count_$dateStr", 0) ?: 0

                sumRestMinutes += restM
                sumRestCount += dayRestCount
                sumTempMinutes += tempM
                sumTempCount += dayTempCount
                sumPrivMinutes += privM
                sumPrivCount += dayPrivCount
                sumCooldownAttempts += dayCooldownAttempts
            }
            curDate = curDate.plusDays(1)
        }

        // 2. 分桶图表数据计算
        if (granularity == StatsGranularity.HOURLY) {
            val hourlyRestSec = IntArray(24)
            val hourlyBorrowSec = IntArray(24)
            val hourlyPrivMin = IntArray(24)
            val hourlyRestCount = IntArray(24)
            val hourlyTempCount = IntArray(24)
            val hourlyPrivCount = IntArray(24)

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

                for (h in 0..23) {
                    hourlyRestCount[h] = prefs.getInt("exit_rest_count_${dateStr}_$h", 0)
                    hourlyTempCount[h] = prefs.getInt("exit_temp_unlock_count_${dateStr}_$h", 0)
                    hourlyPrivCount[h] = prefs.getInt("exit_privilege_count_${dateStr}_$h", 0)
                }
            }

            for (b in buckets) {
                val bDateTime = LocalDateTime.ofInstant(Instant.ofEpochMilli(b.startMillis), zoneId)
                val isFuture = bDateTime.toLocalDate().isAfter(today) || b.startMillis > nowMillis
                val h = bDateTime.hour
                val restM = if (!isFuture) hourlyRestSec[h] / 60 else 0
                val tempM = if (!isFuture) hourlyBorrowSec[h] / 60 else 0
                val privM = if (!isFuture) hourlyPrivMin[h] else 0

                val rCount = if (!isFuture) maxOf(hourlyRestCount[h], if (restM > 0) 1 else 0) else 0
                val tCount = if (!isFuture) maxOf(hourlyTempCount[h], if (tempM > 0) 1 else 0) else 0
                val pCount = if (!isFuture) maxOf(hourlyPrivCount[h], if (privM > 0) 1 else 0) else 0

                bucketDataList.add(
                    ExitBucketData(
                        bucket = b,
                        restMinutes = restM.toFloat(),
                        restCount = rCount,
                        tempUnlockMinutes = tempM.toFloat(),
                        tempUnlockCount = tCount,
                        privilegeMinutes = privM.toFloat(),
                        privilegeCount = pCount
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
                var bRestCount = 0
                var bTempCount = 0
                var bPrivCount = 0
                var cursor = bStartDateTime.toLocalDate()
                val endCursor = bEndDateTime.toLocalDate()

                while (cursor.isBefore(endCursor)) {
                    if (weekdayFilter.isEnabled(cursor.dayOfWeek) && !cursor.isAfter(today)) {
                        val dStr = cursor.format(DateTimeFormatter.ofPattern("yyyyMMdd"))
                        var dRestSec = 0
                        var dBorrowSec = 0
                        var dPrivMin = 0

                        if (prefs != null) {
                            val prefixUsage = "usage_${dStr}_"
                            val prefixBorrow = "borrow_time_${dStr}_"
                            val prefixPriv = "privilege_usage_${dStr}_"

                            for ((k, v) in prefs.all) {
                                if (k.startsWith(prefixUsage)) {
                                    dRestSec += (v as? Int) ?: 0
                                } else if (k.startsWith(prefixBorrow)) {
                                    dBorrowSec += (v as? Int) ?: 0
                                } else if (k.startsWith(prefixPriv)) {
                                    dPrivMin += (v as? Int) ?: 0
                                }
                            }
                            bRestSec += dRestSec
                            bBorrowSec += dBorrowSec
                            bPrivMin += dPrivMin

                            val recRest = prefs.getInt("exit_rest_count_$dStr", 0)
                            var fallbackRest = 0
                            val prefixCount = "count_usage_half_units_v2_${dStr}_"
                            val prefixCountLegacy = "count_usage_${dStr}_"
                            for ((k, v) in prefs.all) {
                                if (k.startsWith(prefixCount) || k.startsWith(prefixCountLegacy)) {
                                    fallbackRest += ((v as? Int) ?: 0)
                                }
                            }
                            bRestCount += maxOf(recRest, fallbackRest, if (dRestSec > 0) 1 else 0)

                            val recTemp = prefs.getInt("exit_temp_unlock_count_$dStr", 0)
                            var fallbackTemp = 0
                            val prefixBorrowV2 = "count_borrow_half_units_v2_${dStr}_"
                            val prefixBorrowLegacy = "count_borrow_${dStr}_"
                            for ((k, v) in prefs.all) {
                                if (k.startsWith(prefixBorrowV2) || k.startsWith(prefixBorrowLegacy)) {
                                    fallbackTemp += ((v as? Int) ?: 0)
                                }
                            }
                            bTempCount += maxOf(recTemp, fallbackTemp, if (dBorrowSec > 0) 1 else 0)

                            val recPriv = prefs.getInt("exit_privilege_count_$dStr", 0)
                            bPrivCount += maxOf(recPriv, if (dPrivMin > 0) 1 else 0)
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
                        restCount = bRestCount,
                        tempUnlockMinutes = tempM.toFloat(),
                        tempUnlockCount = bTempCount,
                        privilegeMinutes = privM.toFloat(),
                        privilegeCount = bPrivCount
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

        fun isGameLocal(pkg: String, info: ApplicationInfo?): Boolean = isGame(context, pkg, info)

        fun getAppLabel(pkg: String): String {
            val info = appInfoMap[pkg]
            return if (info != null) {
                try { info.loadLabel(pm).toString() } catch (_: Exception) { pkg }
            } else {
                pkg
            }
        }

        fun resolveCategoryLocal(pkg: String, info: ApplicationInfo?): AppCategory {
            return resolveCategory(context, pkg, info)
        }

        fun getCategoryName(pkg: String, isGame: Boolean, info: ApplicationInfo?): String {
            return resolveCategoryLocal(pkg, info).displayName
        }

        fun isUserFacingApp(pkg: String, info: ApplicationInfo?): Boolean {
            if (pkg == context.packageName) return false
            if (pkg == "android" || pkg == "com.android.systemui") return false
            if (isGameLocal(pkg, info)) return true
            if (info == null) return false
            return pm.getLaunchIntentForPackage(pkg) != null
        }

        // 查询整个范围的事件（一次遍历提高效率）
        val rangeStart = resolvedRange.startMillis
        val rangeEnd = resolvedRange.endMillisExclusive.coerceAtMost(nowMillis)

        val bucketOpensMap = mutableMapOf<StatsBucket, MutableMap<String, Int>>()
        val bucketUsageMap = mutableMapOf<StatsBucket, MutableMap<String, Long>>()
        val totalOpensMap = mutableMapOf<String, Int>()
        val totalUsageMap = mutableMapOf<String, Long>()

        var hasUsageEvents = false

        if (rangeEnd > rangeStart) {
            try {
                val events = usm.queryEvents(rangeStart, rangeEnd)
                val event = UsageEvents.Event()
                var currentForegroundPkg: String? = null
                var lastResumedTimestamp = 0L

                while (events.hasNextEvent()) {
                    events.getNextEvent(event)
                    hasUsageEvents = true
                    val t = event.timeStamp
                    val pkg = event.packageName ?: continue
                    val eventType = event.eventType

                    // 检查事件发生日是否符合星期过滤
                    val eventDate = LocalDateTime.ofInstant(Instant.ofEpochMilli(t), zoneId).toLocalDate()
                    val matchesWeekday = weekdayFilter.matches(eventDate.dayOfWeek)
                    val targetBucket = buckets.find { t >= it.startMillis && t < it.endMillisExclusive }

                    // 处理前一个主前台 session 的排他结算 (对齐系统原生数字健康亮屏前台口径)
                    if (currentForegroundPkg != null && lastResumedTimestamp > 0L && t > lastResumedTimestamp) {
                        // 单次 session 上限 3 小时，防止遗漏 pause 事件导致异常延长
                        val sessionDuration = (t - lastResumedTimestamp).coerceAtMost(3 * 3600 * 1000L)
                        val prevDate = LocalDateTime.ofInstant(Instant.ofEpochMilli(lastResumedTimestamp), zoneId).toLocalDate()
                        val prevMatchesWeekday = weekdayFilter.matches(prevDate.dayOfWeek)

                        if (prevMatchesWeekday) {
                            val fgPkg = currentForegroundPkg!!
                            totalUsageMap[fgPkg] = (totalUsageMap[fgPkg] ?: 0L) + sessionDuration
                            val prevBucket = buckets.find { lastResumedTimestamp >= it.startMillis && lastResumedTimestamp < it.endMillisExclusive }
                            if (prevBucket != null) {
                                val map = bucketUsageMap.getOrPut(prevBucket) { mutableMapOf() }
                                map[fgPkg] = (map[fgPkg] ?: 0L) + sessionDuration
                            }
                        }
                    }

                    when (eventType) {
                        UsageEvents.Event.ACTIVITY_RESUMED, 1 -> {
                            if (matchesWeekday) {
                                if (targetBucket != null) {
                                    val bMap = bucketOpensMap.getOrPut(targetBucket) { mutableMapOf() }
                                    bMap[pkg] = (bMap[pkg] ?: 0) + 1
                                }
                                totalOpensMap[pkg] = (totalOpensMap[pkg] ?: 0) + 1
                            }
                            currentForegroundPkg = pkg
                            lastResumedTimestamp = t
                        }
                        UsageEvents.Event.ACTIVITY_PAUSED, 2,
                        UsageEvents.Event.ACTIVITY_STOPPED, 23,
                        UsageEvents.Event.SCREEN_NON_INTERACTIVE, 16 -> {
                            currentForegroundPkg = null
                            lastResumedTimestamp = 0L
                        }
                        else -> {
                            // 保持当前状态
                        }
                    }
                }

                // 最后一个活跃 session 结算
                if (currentForegroundPkg != null && lastResumedTimestamp > 0L && rangeEnd > lastResumedTimestamp) {
                    val sessionDuration = (rangeEnd - lastResumedTimestamp).coerceAtMost(30 * 60 * 1000L)
                    val lastDate = LocalDateTime.ofInstant(Instant.ofEpochMilli(lastResumedTimestamp), zoneId).toLocalDate()
                    if (weekdayFilter.matches(lastDate.dayOfWeek)) {
                        val fgPkg = currentForegroundPkg!!
                        totalUsageMap[fgPkg] = (totalUsageMap[fgPkg] ?: 0L) + sessionDuration
                        val b = buckets.find { lastResumedTimestamp >= it.startMillis && lastResumedTimestamp < it.endMillisExclusive }
                        if (b != null) {
                            val map = bucketUsageMap.getOrPut(b) { mutableMapOf() }
                            map[fgPkg] = (map[fgPkg] ?: 0L) + sessionDuration
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "queryEvents timeline processing failed: ${e.message}")
            }
        }

        // 仅在完全没有事件流的异常/老旧设备时，才回退至 queryUsageStats 兜底
        if (!hasUsageEvents && rangeEnd > rangeStart) {
            for (b in buckets) {
                val sDate = LocalDateTime.ofInstant(Instant.ofEpochMilli(b.startMillis), zoneId).toLocalDate()
                val isFuture = sDate.isAfter(today) || b.startMillis > nowMillis
                if (isFuture) continue
                if (!weekdayFilter.matches(sDate.dayOfWeek)) continue

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
                                totalUsageMap[pkg] = (totalUsageMap[pkg] ?: 0L) + fgTime
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "queryUsageStats fallback failed for bucket ${b.label}: ${e.message}")
                }
            }
        }

        // 提取所有有使用或打开的有效应用包名
        val allActivePackages = (totalUsageMap.keys + totalOpensMap.keys).filter { pkg ->
            isUserFacingApp(pkg, appInfoMap[pkg])
        }.toSet()

        val allGamePackages = allActivePackages.filter { pkg -> isGameLocal(pkg, appInfoMap[pkg]) }.toSet()
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
                        val isG = isGameLocal(pkg, appInfoMap[pkg])
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
                if (appViewMode == AppUsageViewMode.CATEGORIZED_GAMES && isGameLocal(pkg, appInfoMap[pkg]) && excludedApps.contains("游戏")) continue

                bucketTotalOpens += bOpens[pkg] ?: 0
                bucketTotalMillis += bUsage[pkg] ?: 0L
            }

            // 单桶物理时长上限限制（杜绝单日超 24 小时）
            val bucketMaxMillis = (b.endMillisExclusive - b.startMillis).coerceAtLeast(0L)
            bucketTotalMillis = bucketTotalMillis.coerceAtMost(bucketMaxMillis)

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
                    val bucketMaxMillis = (b.endMillisExclusive - b.startMillis).coerceAtLeast(0L)
                    gMillis = gMillis.coerceAtMost(bucketMaxMillis)
                    AppUsageBucketData(b, gOpens, gMillis / 60_000f)
                } else {
                    val matchingPkg = allActivePackages.firstOrNull { getAppLabel(it) == selectedApp || it == selectedApp }
                    if (matchingPkg != null) {
                        val opens = bOpens[matchingPkg] ?: 0
                        val millis = bUsage[matchingPkg] ?: 0L
                        val bucketMaxMillis = (b.endMillisExclusive - b.startMillis).coerceAtLeast(0L)
                        val clampedMillis = millis.coerceAtMost(bucketMaxMillis)
                        AppUsageBucketData(b, opens, clampedMillis / 60_000f)
                    } else {
                        AppUsageBucketData(b, 0, 0f)
                    }
                }
            }
        } else null

        // 科学 6 分法分类聚合汇总 (零数据大类自动隐藏)
        val categorySummaries = AppCategory.values().mapNotNull { cat ->
            val subApps = finalAppList.filter { app ->
                if (app.isGameCategory) {
                    cat == AppCategory.GAME
                } else {
                    val info = appInfoMap[app.packageName]
                    resolveCategoryLocal(app.packageName, info) == cat
                }
            }
            if (subApps.isEmpty()) return@mapNotNull null
            val catOpens = subApps.sumOf { it.openCount }
            val catMinutes = subApps.sumOf { it.usageDurationMinutes }
            if (catOpens == 0 && catMinutes == 0L) return@mapNotNull null
            CategoryUsageSummary(
                category = cat,
                totalUsageMinutes = catMinutes,
                totalOpenCount = catOpens,
                appCount = subApps.size,
                subApps = subApps.sortedByDescending { it.usageDurationMinutes }
            )
        }.sortedByDescending { it.totalUsageMinutes }

        return AppUsageSummary(
            totalOpens = totalOpens,
            totalUsageMinutes = totalUsageMinutes,
            appList = finalAppList,
            buckets = bucketDataList,
            selectedAppTrendBuckets = selectedTrend,
            hasUsageAccessPermission = true,
            categorySummaries = categorySummaries
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

        var totalFavorable = 0
        var totalUnfavorable = 0
        var totalReversed = 0

        val bucketDataList = buckets.map { b ->
            val sDate = LocalDateTime.ofInstant(Instant.ofEpochMilli(b.startMillis), zoneId).toLocalDate()
            val isFuture = sDate.isAfter(today) || b.startMillis > nowMillis

            if (isFuture) {
                return@map RuleProtectionBucketData(bucket = b)
            }

            val (fav, unfav, rev) = RuleChangeLedger.getCountsForDate(context, sDate)
            totalFavorable += fav
            totalUnfavorable += unfav
            totalReversed += rev

            RuleProtectionBucketData(
                bucket = b,
                favorableCount = fav,
                unfavorableCount = unfav,
                reversedCount = rev
            )
        }

        val netChange = totalFavorable - totalUnfavorable
        val recentRecords = RuleChangeLedger.getRecentRecords(context).map { record ->
            val timeLabel = LocalDateTime.ofInstant(Instant.ofEpochMilli(record.timestamp), zoneId)
                .format(DateTimeFormatter.ofPattern("MM-dd HH:mm"))
            RuleProtectionItem(
                id = record.id,
                timeLabel = timeLabel,
                ruleName = record.ruleName,
                editCount = 1,
                state = when (record.type) {
                    RuleChangeLedger.ChangeType.FAVORABLE -> RuleRelaxationState.FULLY_RESTORED
                    RuleChangeLedger.ChangeType.UNFAVORABLE -> RuleRelaxationState.PENDING_RELAXED
                    RuleChangeLedger.ChangeType.UNFAVORABLE_REVERSED -> RuleRelaxationState.PARTIALLY_RESTORED
                },
                stateTitle = when (record.type) {
                    RuleChangeLedger.ChangeType.FAVORABLE -> "有利改动 (加严)"
                    RuleChangeLedger.ChangeType.UNFAVORABLE -> "不利改动 (放宽)"
                    RuleChangeLedger.ChangeType.UNFAVORABLE_REVERSED -> "不利改动撤销"
                },
                stateSubTitle = record.detail.ifEmpty { "记录已归档至规则变动账本" }
            )
        }

        return RuleProtectionSummary(
            totalLoosenItems = totalUnfavorable,
            fullyRestoredCount = totalFavorable,
            partiallyRestoredCount = totalReversed,
            effectiveCount = totalUnfavorable,
            pendingCount = 0,
            buckets = bucketDataList,
            recentChanges = recentRecords,
            totalFavorable = totalFavorable,
            totalUnfavorable = totalUnfavorable,
            totalReversed = totalReversed,
            netChange = netChange
        )
    }

    /**
     * 获取指定分类的二级下钻明细数据
     */
    fun getCategoryDetail(
        context: Context,
        category: AppCategory,
        range: StatsDateRange,
        resolvedRange: ResolvedDateRange,
        weekdayFilter: WeekdayFilter,
        excludedApps: Set<String>
    ): CategoryDetailData {
        val appSummary = getAppUsageStats(context, range, resolvedRange, weekdayFilter, AppUsageViewMode.INDIVIDUAL_APPS, excludedApps)
        val pm = context.packageManager
        val installed = try { pm.getInstalledApplications(0) } catch (_: Exception) { emptyList() }
        val appInfoMap = installed.associateBy { it.packageName }

        val subApps = appSummary.appList.filter { app ->
            resolveCategory(context, app.packageName, appInfoMap[app.packageName]) == category
        }.sortedByDescending { it.usageDurationMinutes }

        val totalMinutes = subApps.sumOf { it.usageDurationMinutes }
        val totalOpens = subApps.sumOf { it.openCount }

        val buckets = appSummary.buckets.map { b ->
            val ratio = if (appSummary.totalUsageMinutes > 0) totalMinutes.toFloat() / appSummary.totalUsageMinutes else 0f
            val opensRatio = if (appSummary.totalOpens > 0) totalOpens.toFloat() / appSummary.totalOpens else 0f
            AppUsageBucketData(
                bucket = b.bucket,
                openCount = (b.openCount * opensRatio).toInt(),
                usageMinutes = b.usageMinutes * ratio
            )
        }

        return CategoryDetailData(
            category = category,
            totalUsageMinutes = totalMinutes,
            totalOpenCount = totalOpens,
            buckets = buckets,
            subApps = subApps
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

        return installed.filter {
            if (it.packageName == context.packageName) return@filter false
            if (it.packageName == "android" || it.packageName == "com.android.systemui") return@filter false
            pm.getLaunchIntentForPackage(it.packageName) != null
        }.map { info ->
            val pkg = info.packageName
            val label = try { info.loadLabel(pm).toString() } catch (_: Exception) { pkg }
            val cat = resolveCategory(context, pkg, info)
            InstalledAppMeta(
                packageName = pkg,
                appName = label,
                category = cat.displayName,
                isGame = cat == AppCategory.GAME
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
