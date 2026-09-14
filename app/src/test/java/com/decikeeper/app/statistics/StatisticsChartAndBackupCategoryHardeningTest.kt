package com.decikeeper.app.statistics

import android.content.Context
import com.decikeeper.app.data.statistics.AlarmBucketData
import com.decikeeper.app.data.statistics.AlarmStatsSummary
import com.decikeeper.app.data.statistics.AlarmStatsView
import com.decikeeper.app.data.statistics.AppUsageBucketData
import com.decikeeper.app.data.statistics.ChartType
import com.decikeeper.app.data.statistics.ExitBucketData
import com.decikeeper.app.data.statistics.ExitStatsSummary
import com.decikeeper.app.data.statistics.MetricDimension
import com.decikeeper.app.data.statistics.StatsBucket
import com.decikeeper.app.data.statistics.StatsDateRange
import com.decikeeper.app.data.statistics.StatsGranularity
import com.decikeeper.app.manager.CreditManager
import com.decikeeper.app.manager.SettingsBackupManager
import com.decikeeper.app.manager.statistics.StatsDateRangeResolver
import com.decikeeper.app.ui.statistics.StatisticsChartRenderer
import com.github.mikephil.charting.charts.BarChart
import com.github.mikephil.charting.charts.LineChart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
class StatisticsChartAndBackupCategoryHardeningTest {

    private val context: Context = RuntimeEnvironment.getApplication()
    private val zoneId = ZoneId.of("Asia/Shanghai")

    @Before
    fun setUp() {
        context.getSharedPreferences(CreditManager.PREFS_NAME, Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("decikeeper_rules", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("rest_stats_ledger", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun customSixtyDays_resolvesToDailyGranularityAndLineChart() {
        val start = LocalDate.of(2026, 1, 1).atStartOfDay(zoneId).toInstant().toEpochMilli()
        val end = LocalDate.of(2026, 3, 2).atStartOfDay(zoneId).toInstant().toEpochMilli() // 60 days
        val range = StatsDateRange.Custom(start, end)
        val resolved = StatsDateRangeResolver.resolveRange(range, LocalDateTime.of(2026, 3, 2, 12, 0), zoneId)

        assertEquals(60, resolved.dayCount)
        val granularity = StatsDateRangeResolver.resolveBucketGranularity(range, resolved)
        assertEquals(StatsGranularity.DAILY, granularity)

        val buckets = StatsDateRangeResolver.buildBuckets(range, resolved, zoneId = zoneId)
        assertEquals(60, buckets.size)
        assertEquals(ChartType.LINE, StatsDateRangeResolver.chooseChartType(buckets.size))
    }

    @Test
    fun chartRenderer_alarmSettingMax_doesNotLeakGhost600hToSubsequentRestOrAppLineChart() {
        val barChart = BarChart(context)
        val lineChart = LineChart(context)

        // 1. 模拟起床时刻视图渲染：闹钟和完成时间落在 ~570 分钟，axisMaximum 会被设为 600f
        val alarmBuckets = listOf(
            AlarmBucketData(
                bucket = StatsBucket(0, 1000, "09/01"),
                alarmMinuteOfDay = 480f,
                firstStageCompletedMinute = 570f,
                firstStageDurationMinutes = 90f,
                totalFlowDurationMinutes = 95f
            )
        )
        StatisticsChartRenderer.renderAlarmChart(
            lineChart = lineChart,
            summary = AlarmStatsSummary(
                medianAlarmMinuteOfDay = 480,
                medianFirstStageDurationMinutes = 90,
                medianTotalFlowDurationMinutes = 95,
                recordedDaysCount = 1,
                recordedRecordsCount = 1,
                buckets = alarmBuckets,
                recentRecords = emptyList()
            ),
            viewMode = AlarmStatsView.TIME,
            context = context
        )
        // 验证此时由于设置了具体时刻区间，isAxisMaxCustom 为 true，上限约为 600f
        assertTrue("闹钟时刻视图应设定了坐标轴最大值", lineChart.axisLeft.isAxisMaxCustom)
        assertEquals(600f, lineChart.axisLeft.axisMaximum, 0.1f)

        // 2. 模拟用户切换到长周期（如 30 天或 60 天，大于 12 个点）的“休息”折线图，最高数据仅 3h
        val restBuckets = (1..20).map { i ->
            ExitBucketData(
                bucket = StatsBucket(i.toLong() * 1000, (i + 1).toLong() * 1000, "09/$i"),
                restMinutes = if (i == 5) 180f else 0f, // 最高 3h (180 分钟)
                restCount = if (i == 5) 1 else 0,
                tempUnlockMinutes = 0f,
                tempUnlockCount = 0,
                privilegeMinutes = 0f,
                privilegeCount = 0
            )
        }
        StatisticsChartRenderer.renderRestChart(
            barChart = barChart,
            lineChart = lineChart,
            summary = ExitStatsSummary(
                totalRestCount = 1,
                totalRestMinutes = 180L,
                totalTempUnlockCount = 0,
                totalTempUnlockMinutes = 0L,
                totalPrivilegeCount = 0,
                totalPrivilegeMinutes = 0L,
                cooldownAttemptCount = 0,
                buckets = restBuckets
            ),
            metric = MetricDimension.DURATION,
            context = context
        )

        // 3. 核心断言：经过重置后，isAxisMaxCustom 必须恢复为 false（由 MPAndroidChart 自适应数据），
        // 且 spaceTop 为 20f 保证顶部呼吸留白空间，彻底终结 600h 幽灵！
        assertFalse("切换至休息折线图后必须清除残留的自定义最大值", lineChart.axisLeft.isAxisMaxCustom)
        assertEquals(20f, lineChart.axisLeft.spaceTop, 0.1f)

        // 4. 模拟切换至应用趋势折线图，同样必须彻底重置并带留白
        val appBuckets = (1..15).map { i ->
            AppUsageBucketData(
                bucket = StatsBucket(i.toLong() * 1000, (i + 1).toLong() * 1000, "09/$i"),
                usageMinutes = if (i == 3) 120f else 30f,
                openCount = 5
            )
        }
        StatisticsChartRenderer.renderAppTrendChart(
            barChart = barChart,
            lineChart = lineChart,
            buckets = appBuckets,
            metric = MetricDimension.DURATION,
            context = context
        )
        assertFalse("切换至应用趋势折线图后必须清除残留的自定义最大值", lineChart.axisLeft.isAxisMaxCustom)
        assertEquals(20f, lineChart.axisLeft.spaceTop, 0.1f)
    }

    @Test
    fun backupCategory_displayNameIsRuleParams_andLedgerFileIncludesRestStatsLedger() {
        assertEquals("规则参数", SettingsBackupManager.BackupCategory.GLOBAL_PARAMS.displayName)
        val ledgerFiles = SettingsBackupManager.getExclusiveFilesForCategory(SettingsBackupManager.BackupCategory.LEDGER)
        assertTrue("LEDGER 独占文件必须包含 rest_stats_ledger", ledgerFiles.contains("rest_stats_ledger"))
    }

    @Test
    fun importBackup_whenOnlyGlobalParamsSelected_doesNotPolluteLedgerUsageData() {
        val ledgerPrefs = context.getSharedPreferences(CreditManager.PREFS_NAME, Context.MODE_PRIVATE)

        // 准备一个包含规则参数与历史额度流水的备份 JSON
        val backupJson = """
        {
          "type": "decikeeper_settings_backup",
          "schemaVersion": 1,
          "preferenceFiles": [
            {
              "name": "decikeeper_ledger",
              "entries": {
                "config_refresh_time": { "type": "String", "value": "04:00" },
                "strict_penalty_duration": { "type": "Int", "value": 1800 },
                "usage_20260901_morning": { "type": "Int", "value": 450 },
                "count_usage_20260901_morning": { "type": "Int", "value": 3 }
              }
            }
          ]
        }
        """.trimIndent()

        // 1. 仅导入 GLOBAL_PARAMS（规则参数）
        val result = SettingsBackupManager.applyBackupJson(
            context,
            backupJson,
            listOf(SettingsBackupManager.BackupCategory.GLOBAL_PARAMS)
        )
        assertTrue(result.fileCount > 0)

        // 验证：规则参数成功导入
        assertEquals("04:00", ledgerPrefs.getString("config_refresh_time", null))
        assertEquals(1800, ledgerPrefs.getInt("strict_penalty_duration", 0))

        // 验证：额度流水与统计键（usage_*、count_*）绝对没有被写入！
        assertFalse("未选择统计数据分类时，额度流水不得被导入", ledgerPrefs.contains("usage_20260901_morning"))
        assertFalse("未选择统计数据分类时，次数流水不得被导入", ledgerPrefs.contains("count_usage_20260901_morning"))

        // 2. 如果再导入 LEDGER（统计数据）
        val resultLedger = SettingsBackupManager.applyBackupJson(
            context,
            backupJson,
            listOf(SettingsBackupManager.BackupCategory.LEDGER)
        )
        assertTrue(resultLedger.fileCount > 0)
        assertEquals(450, ledgerPrefs.getInt("usage_20260901_morning", 0))
        assertEquals(3, ledgerPrefs.getInt("count_usage_20260901_morning", 0))
    }
}
