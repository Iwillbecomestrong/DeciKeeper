package com.decikeeper.app.statistics

import com.decikeeper.app.data.statistics.AppUsageBucketData
import com.decikeeper.app.data.statistics.StatsBucket
import com.decikeeper.app.data.statistics.StatsDateRange
import com.decikeeper.app.data.statistics.WeekdayFilter
import com.decikeeper.app.manager.SettingsBackupManager
import com.decikeeper.app.manager.statistics.RestStatsRecorder
import com.decikeeper.app.manager.statistics.StatisticsRepository
import com.decikeeper.app.manager.statistics.StatsDateRangeResolver
import com.decikeeper.app.manager.statistics.UsageTimelineEvent
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
class StatisticsHardeningTest {

    private val zoneId = ZoneId.of("Asia/Shanghai")
    private val base = LocalDateTime.of(2026, 9, 12, 0, 0)
    private val context = RuntimeEnvironment.getApplication()

    @Before
    fun setUp() {
        context.getSharedPreferences("rule_change_ledger", 0).edit().clear().commit()
        context.getSharedPreferences("decikeeper_rules_pending", 0).edit().clear().commit()
        context.getSharedPreferences("app_window_pending", 0).edit().clear().commit()
        context.getSharedPreferences("decikeeper_pending_apps", 0).edit().clear().commit()
        context.getSharedPreferences("pending_config_prefs", 0).edit().clear().commit()
        RestStatsRecorder.clearAll(context)
    }

    @Test
    fun usageTimeline_ignoresUnrelatedEventsAndDoesNotDoubleCount() {
        val bucket = bucket(0, 120)
        val result = StatisticsRepository.aggregateUsageTimeline(
            events = listOf(
                UsageTimelineEvent(at(0), "pkg.demo", 1),
                UsageTimelineEvent(at(30), "pkg.demo", 7), // USER_INTERACTION / unrelated
                UsageTimelineEvent(at(60), "pkg.demo", 2)
            ),
            rangeStart = at(0),
            rangeEnd = at(120),
            buckets = listOf(bucket),
            weekdayFilter = WeekdayFilter.ALL,
            zoneId = zoneId
        )

        assertEquals(60 * 60 * 1000L, result.totalUsageMillis["pkg.demo"])
    }

    @Test
    fun usageTimeline_capsUnclosedSessionAtThreeHours() {
        val result = StatisticsRepository.aggregateUsageTimeline(
            events = listOf(UsageTimelineEvent(at(0), "pkg.demo", 1)),
            rangeStart = at(0),
            rangeEnd = at(4 * 60),
            buckets = listOf(bucket(0, 4 * 60)),
            weekdayFilter = WeekdayFilter.ALL,
            zoneId = zoneId
        )

        assertEquals(3 * 60 * 60 * 1000L, result.totalUsageMillis["pkg.demo"])
    }

    @Test
    fun usageTimeline_splitsSessionAtBucketBoundary() {
        val first = bucket(0, 60)
        val second = bucket(60, 120)
        val result = StatisticsRepository.aggregateUsageTimeline(
            events = listOf(
                UsageTimelineEvent(at(50), "pkg.demo", 1),
                UsageTimelineEvent(at(70), "pkg.demo", 2)
            ),
            rangeStart = at(0),
            rangeEnd = at(120),
            buckets = listOf(first, second),
            weekdayFilter = WeekdayFilter.ALL,
            zoneId = zoneId
        )

        assertEquals(10 * 60 * 1000L, result.bucketUsageMillis[first]?.get("pkg.demo"))
        assertEquals(10 * 60 * 1000L, result.bucketUsageMillis[second]?.get("pkg.demo"))
    }

    @Test
    fun categoryTrend_sumsTheSameAppsPerBucketInsteadOfGlobalRatio() {
        val first = bucket(0, 60)
        val second = bucket(60, 120)
        val result = StatisticsRepository.aggregateCategoryTrendBuckets(
            buckets = listOf(first, second),
            perAppBuckets = mapOf(
                "video" to listOf(
                    AppUsageBucketData(first, 1, 10f),
                    AppUsageBucketData(second, 0, 0f)
                ),
                "game" to listOf(
                    AppUsageBucketData(first, 0, 0f),
                    AppUsageBucketData(second, 2, 20f)
                )
            ),
            packageNames = setOf("video")
        )

        assertEquals(1, result[0].openCount)
        assertEquals(10f, result[0].usageMinutes, 0.001f)
        assertEquals(0, result[1].openCount)
        assertEquals(0f, result[1].usageMinutes, 0.001f)
    }

    @Test
    fun ruleProtection_monthBucketAggregatesEveryDayInTheMonth() {
        val now = LocalDateTime.now(zoneId)
        val first = now.withDayOfMonth(1).toLocalDate()
        val middle = first.plusDays(10)
        val prefs = context.getSharedPreferences("rule_change_ledger", 0)
        prefs.edit()
            .putInt("rule_${first.format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd"))}_unfavorable", 1)
            .putInt("rule_${middle.format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd"))}_unfavorable", 2)
            .commit()

        val range = StatsDateRange.ThisYear
        val resolved = StatsDateRangeResolver.resolveRange(range, now, zoneId)
        val summary = StatisticsRepository.getRuleProtectionStats(context, range, resolved, WeekdayFilter.ALL)
        val currentMonthBucket = summary.buckets.first { 
            LocalDateTime.ofInstant(Instant.ofEpochMilli(it.bucket.startMillis), zoneId).month == now.month
        }

        assertEquals(3, currentMonthBucket.unfavorableCount)
        assertEquals(3, summary.totalUnfavorable)
    }

    @Test
    fun ruleProtection_reportsPendingRuleDomains() {
        context.getSharedPreferences("decikeeper_rules_pending", 0).edit()
            .putBoolean("has_pending_changes", true)
            .commit()

        val now = LocalDateTime.now(zoneId)
        val range = StatsDateRange.Today
        val resolved = StatsDateRangeResolver.resolveRange(range, now, zoneId)
        val summary = StatisticsRepository.getRuleProtectionStats(context, range, resolved, WeekdayFilter.ALL)

        assertEquals(1, summary.pendingCount)
    }

    @Test
    fun restRecentRecords_followTheSelectedDateRange() {
        val now = System.currentTimeMillis()
        RestStatsRecorder.record(
            context = context,
            type = "NORMAL",
            typeName = "常规休息",
            actualMinutes = 5.0,
            timestamp = now - 8 * 24 * 60 * 60 * 1000L
        )
        val current = RestStatsRecorder.record(
            context = context,
            type = "NORMAL",
            typeName = "常规休息",
            actualMinutes = 5.0,
            timestamp = now - 60 * 60 * 1000L
        )

        val resolved = StatsDateRangeResolver.resolveRange(StatsDateRange.Today, LocalDateTime.now(zoneId), zoneId)
        val summary = StatisticsRepository.getExitStats(context, StatsDateRange.Today, resolved, WeekdayFilter.ALL)

        assertEquals(listOf(current.id), summary.recentRecords.map { it.id })
    }

    @Test
    fun restHistory_isIncludedInLedgerBackupAndCanBeRestored() {
        val source = RestStatsRecorder.record(
            context = context,
            type = "NORMAL",
            typeName = "常规休息",
            actualMinutes = 12.0,
            timestamp = System.currentTimeMillis()
        )
        val backup = org.json.JSONObject(
            SettingsBackupManager.buildBackupJson(
                context,
                listOf(SettingsBackupManager.BackupCategory.LEDGER)
            )
        )
        assertEquals(source.id, backup.getJSONArray("restUsageRecords").getJSONObject(0).getString("id"))

        RestStatsRecorder.replaceAllRecords(context, emptyList())
        SettingsBackupManager.applyBackupJson(
            context,
            backup.toString(),
            listOf(SettingsBackupManager.BackupCategory.LEDGER)
        )

        assertEquals(source.id, RestStatsRecorder.getAllRecords(context).single().id)
    }

    private fun at(minutes: Int): Long = base.plusMinutes(minutes.toLong()).atZone(zoneId).toInstant().toEpochMilli()

    private fun bucket(startMinutes: Int, endMinutes: Int): StatsBucket =
        StatsBucket(at(startMinutes), at(endMinutes), "$startMinutes-$endMinutes")
}
