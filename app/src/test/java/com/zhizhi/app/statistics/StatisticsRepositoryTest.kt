package com.zhizhi.app.statistics

import android.content.Context
import com.zhizhi.app.data.statistics.AppUsageViewMode
import com.zhizhi.app.data.statistics.StatsDateRange
import com.zhizhi.app.data.statistics.WeekdayFilter
import com.zhizhi.app.manager.statistics.StatisticsRepository
import com.zhizhi.app.manager.statistics.StatsDateRangeResolver
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@RunWith(RobolectricTestRunner::class)
class StatisticsRepositoryTest {

    private lateinit var context: Context
    private val zoneId = ZoneId.of("Asia/Shanghai")
    private val fixedNow = LocalDateTime.of(2026, 9, 7, 12, 0, 0)

    @Before
    fun setup() {
        context = RuntimeEnvironment.getApplication()
        StatisticsRepository.permissionChecker = null
    }

    @After
    fun tearDown() {
        StatisticsRepository.permissionChecker = null
    }

    @Test
    fun testExitStats_EmptyLedgerReturnsStrictZeroAndFutureBucketsZero() {
        val range = StatsDateRange.ThisWeek
        val resolved = StatsDateRangeResolver.resolveRange(range, fixedNow, zoneId)
        val summary = StatisticsRepository.getExitStats(context, range, resolved, WeekdayFilter.ALL)

        assertEquals(7, summary.buckets.size)
        // 无账本记录时严格归零，杜绝伪随机假数据
        assertEquals(0L, summary.totalRestMinutes)
        assertEquals(0, summary.totalRestCount)
        assertEquals(0L, summary.totalTempUnlockMinutes)
        assertEquals(0, summary.totalTempUnlockCount)
        assertEquals(0L, summary.totalPrivilegeMinutes)
        assertEquals(0, summary.cooldownAttemptCount)

        summary.buckets.forEach { b ->
            assertEquals(0f, b.restMinutes, 0.001f)
            assertEquals(0, b.restCount)
        }
    }

    @Test
    fun testExitStats_RealLedgerDataIsReadAccurately() {
        val today = fixedNow.toLocalDate()
        val dateStr = today.format(DateTimeFormatter.ofPattern("yyyyMMdd"))

        // 模拟向账本写入真实记录
        val prefs = context.getSharedPreferences("iron_will_ledger", Context.MODE_PRIVATE)
        // 保存测试分段
        val segsJson = """[{"id":"seg_test","startTime":"09:00","endTime":"18:00","restLimitMinutes":60,"borrowLimitMinutes":15,"privilegeLimitMinutes":30}]"""
        prefs.edit()
            .putString("time_segments_json", segsJson)
            .putInt("usage_${dateStr}_09:00-18:00", 1800) // 30 分钟休息
            .putInt("borrow_time_${dateStr}_09:00-18:00", 600) // 10 分钟临时解锁
            .putInt("privilege_usage_${dateStr}_09:00-18:00", 20) // 20 分钟特权
            .apply()

        val range = StatsDateRange.Today
        val resolved = StatsDateRangeResolver.resolveRange(range, fixedNow, zoneId)
        val summary = StatisticsRepository.getExitStats(context, range, resolved, WeekdayFilter.ALL)

        assertEquals(30L, summary.totalRestMinutes)
        assertEquals(1, summary.totalRestCount)
        assertEquals(10L, summary.totalTempUnlockMinutes)
        assertEquals(1, summary.totalTempUnlockCount)
        assertEquals(20L, summary.totalPrivilegeMinutes)
    }

    @Test
    fun testAppUsageStats_PermissionDeniedReturnsStatusFlagAndEmptyData() {
        StatisticsRepository.permissionChecker = { false }

        val range = StatsDateRange.ThisWeek
        val resolved = StatsDateRangeResolver.resolveRange(range, fixedNow, zoneId)
        val summary = StatisticsRepository.getAppUsageStats(
            context, range, resolved, WeekdayFilter.ALL, AppUsageViewMode.CATEGORIZED_GAMES
        )

        assertFalse(summary.hasUsageAccessPermission)
        assertEquals(0, summary.totalOpens)
        assertEquals(0L, summary.totalUsageMinutes)
        assertTrue(summary.appList.isEmpty())
        assertEquals(7, summary.buckets.size)
        summary.buckets.forEach {
            assertEquals(0, it.openCount)
            assertEquals(0f, it.usageMinutes, 0.001f)
        }
    }

    @Test
    fun testAppUsageStats_PermissionGrantedWithEmptySystemReturnsEmpty() {
        StatisticsRepository.permissionChecker = { true }

        val range = StatsDateRange.ThisWeek
        val resolved = StatsDateRangeResolver.resolveRange(range, fixedNow, zoneId)
        val summary = StatisticsRepository.getAppUsageStats(
            context, range, resolved, WeekdayFilter.ALL, AppUsageViewMode.CATEGORIZED_GAMES
        )

        assertTrue(summary.hasUsageAccessPermission)
        assertEquals(0, summary.totalOpens)
        assertEquals(0L, summary.totalUsageMinutes)
    }

    @Test
    fun testAlarmStats_EmptyLedgerReturnsStrictZeroAndNullMedians() {
        val range = StatsDateRange.ThisMonth
        val resolved = StatsDateRangeResolver.resolveRange(range, fixedNow, zoneId)

        val summary = StatisticsRepository.getAlarmStats(context, range, resolved, WeekdayFilter.ALL)
        assertEquals(0, summary.recordedDaysCount)
        assertNull(summary.medianAlarmMinuteOfDay)
        assertNull(summary.medianFirstStageDurationMinutes)
        assertNull(summary.medianTotalFlowDurationMinutes)
        assertTrue(summary.recentRecords.isEmpty())
    }

    @Test
    fun testAlarmStats_RealLedgerRecordCalculatesAccurateMedians() {
        val today = fixedNow.toLocalDate()
        val dateKey = today.format(DateTimeFormatter.ofPattern("yyyyMMdd"))

        val prefs = context.getSharedPreferences("alarm_stats_ledger", Context.MODE_PRIVATE)
        prefs.edit()
            .putInt("alarm_${dateKey}_wake_time", 450) // 07:30
            .putInt("alarm_${dateKey}_stage1_duration", 6)
            .putInt("alarm_${dateKey}_total_duration", 10)
            .apply()

        val range = StatsDateRange.ThisMonth
        val resolved = StatsDateRangeResolver.resolveRange(range, fixedNow, zoneId)
        val summary = StatisticsRepository.getAlarmStats(context, range, resolved, WeekdayFilter.ALL)

        assertEquals(1, summary.recordedDaysCount)
        assertEquals(Integer.valueOf(450), summary.medianAlarmMinuteOfDay)
        assertEquals(Integer.valueOf(6), summary.medianFirstStageDurationMinutes)
        assertEquals(Integer.valueOf(10), summary.medianTotalFlowDurationMinutes)
        assertEquals(1, summary.recentRecords.size)
        assertEquals(450, summary.recentRecords[0].alarmMinuteOfDay)
    }

    @Test
    fun testRuleProtectionStats_EmptyLedgerReturnsStrictZero() {
        val range = StatsDateRange.ThisMonth
        val resolved = StatsDateRangeResolver.resolveRange(range, fixedNow, zoneId)

        val summary = StatisticsRepository.getRuleProtectionStats(context, range, resolved, WeekdayFilter.ALL)
        assertEquals(0, summary.totalLoosenItems)
        assertEquals(0, summary.fullyRestoredCount)
        assertEquals(0, summary.partiallyRestoredCount)
        assertEquals(0, summary.effectiveCount)
        assertEquals(0, summary.pendingCount)
        assertTrue(summary.recentChanges.isEmpty())
    }

    @Test
    fun testCustomGameToggle_PersistsAndModifiesClassification() {
        val testPkg = "com.custom.myapp"
        StatisticsRepository.toggleCustomGame(context, testPkg, true)
        val prefs = context.getSharedPreferences(StatisticsRepository.PREFS_NAME, Context.MODE_PRIVATE)
        val customGames = prefs.getStringSet(StatisticsRepository.KEY_CUSTOM_GAMES, emptySet()) ?: emptySet()
        assertTrue(customGames.contains(testPkg))

        StatisticsRepository.toggleCustomGame(context, testPkg, false)
        val customGamesAfter = prefs.getStringSet(StatisticsRepository.KEY_CUSTOM_GAMES, emptySet()) ?: emptySet()
        val customNonGames = prefs.getStringSet(StatisticsRepository.KEY_CUSTOM_NON_GAMES, emptySet()) ?: emptySet()
        assertFalse(customGamesAfter.contains(testPkg))
        assertTrue(customNonGames.contains(testPkg))
    }
}
