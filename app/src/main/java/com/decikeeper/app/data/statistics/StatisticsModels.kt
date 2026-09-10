package com.decikeeper.app.data.statistics

import java.time.DayOfWeek

/**
 * 统计页一级业务分区
 */
enum class StatsSection {
    REST,            // 休息
    ALARM,           // 起床
    APPS,            // 应用
    RULE_CHANGE,     // 规则变动
    RULE_PROTECTION  // 规则保护 (兼容别名)
}

/**
 * 指标展示维度：时长 / 次数
 */
enum class MetricDimension {
    DURATION,
    COUNT
}

/**
 * 起床统计视图：时间趋势 / 耗时趋势
 */
enum class AlarmStatsView {
    TIME,
    DURATION
}

/**
 * 统计聚合粒度
 */
enum class StatsGranularity {
    HOURLY,
    DAILY,
    WEEKLY,
    MONTHLY
}

/**
 * 图表呈现形式：柱状图 / 折线图
 */
enum class ChartType {
    BAR,
    LINE
}

/**
 * 规则放宽事件跟踪状态（互斥）
 */
enum class RuleRelaxationState {
    PENDING_RELAXED,    // 仍待生效（当前待生效）
    PARTIALLY_RESTORED, // 部分恢复（收回部分放宽，但仍比原基准宽）
    FULLY_RESTORED,     // 完全恢复（回到原边界或更严格）
    EFFECTIVE           // 最终生效（等待结束后正式生效）
}

/**
 * 规则出口类型
 */
enum class ExitType {
    REST,        // 休息
    TEMP_UNLOCK, // 临时解锁
    PRIVILEGE    // 特权
}

/**
 * 时间范围筛选定义
 */
sealed class StatsDateRange {
    data object Today : StatsDateRange()
    data object Yesterday : StatsDateRange()
    data object ThisWeek : StatsDateRange()
    data object LastWeek : StatsDateRange()
    data object Last7Days : StatsDateRange()
    data object ThisMonth : StatsDateRange()
    data object LastMonth : StatsDateRange()
    data object Last30Days : StatsDateRange()
    data object ThisYear : StatsDateRange()
    data class Custom(
        val startMillis: Long,
        val endMillisExclusive: Long
    ) : StatsDateRange()
}

/**
 * 已解析的精确绝对时间范围（含开始毫秒与结束毫秒）
 */
data class ResolvedDateRange(
    val startMillis: Long,
    val endMillisExclusive: Long,
    val dayCount: Int,
    val displayName: String,
    val dateSubText: String
)

/**
 * 星期过滤器
 */
data class WeekdayFilter(
    val enabledDays: Set<DayOfWeek> = DayOfWeek.values().toSet()
) {
    fun isEnabled(dayOfWeek: DayOfWeek): Boolean = enabledDays.contains(dayOfWeek)
    fun matches(dayOfWeek: DayOfWeek): Boolean = isEnabled(dayOfWeek)

    fun summaryText(): String {
        if (enabledDays.size == 7) return "全部星期"
        val workdays = setOf(
            DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY,
            DayOfWeek.THURSDAY, DayOfWeek.FRIDAY
        )
        if (enabledDays == workdays) return "周一至周五"
        val weekend = setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)
        if (enabledDays == weekend) return "周末"
        if (enabledDays.isEmpty()) return "未选日期"

        val names = mapOf(
            DayOfWeek.MONDAY to "周一",
            DayOfWeek.TUESDAY to "周二",
            DayOfWeek.WEDNESDAY to "周三",
            DayOfWeek.THURSDAY to "周四",
            DayOfWeek.FRIDAY to "周五",
            DayOfWeek.SATURDAY to "周六",
            DayOfWeek.SUNDAY to "周日"
        )
        return enabledDays.sorted().joinToString(" ") { names[it] ?: "" }
    }

    companion object {
        val ALL = WeekdayFilter(DayOfWeek.values().toSet())
        val WORKDAYS = WeekdayFilter(
            setOf(
                DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY,
                DayOfWeek.THURSDAY, DayOfWeek.FRIDAY
            )
        )
        val WORKDAY = WORKDAYS
        val WEEKEND = WeekdayFilter(setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY))
        val EMPTY = WeekdayFilter(emptySet())
    }
}

/**
 * 通用统计分桶数据项
 */
data class StatsBucket(
    val startMillis: Long,
    val endMillisExclusive: Long,
    val label: String
)

/**
 * 休息/临时解锁/特权单桶数据
 */
data class ExitBucketData(
    val bucket: StatsBucket,
    val restMinutes: Float,
    val restCount: Int,
    val tempUnlockMinutes: Float,
    val tempUnlockCount: Int,
    val privilegeMinutes: Float,
    val privilegeCount: Int
) {
    val totalMinutes: Float get() = restMinutes + tempUnlockMinutes + privilegeMinutes
    val totalCount: Int get() = restCount + tempUnlockCount + privilegeCount
}

/**
 * 休息总计数据
 */
data class ExitStatsSummary(
    val totalRestCount: Int,
    val totalRestMinutes: Long,
    val totalTempUnlockCount: Int,
    val totalTempUnlockMinutes: Long,
    val totalPrivilegeCount: Int,
    val totalPrivilegeMinutes: Long,
    val cooldownAttemptCount: Int,
    val buckets: List<ExitBucketData>,
    val recentRecords: List<RestUsageRecord> = emptyList()
)

/**
 * 休息额度与使用流水记录
 */
data class RestUsageRecord(
    val id: String,
    val timestamp: Long,
    val dateLabel: String,                 // 格式如 "2026-09-09 11:30"
    val type: String,                      // "NORMAL", "BORROW", "PRIVILEGE", "PENALTY", "REFUND"
    val typeName: String,                  // "常规休息", "借用休息", "周特权", "惩罚扣除", "提前退还"
    val reason: String = "",               // 具体原因描述
    val requestedMinutes: Double = 0.0,    // 申请/设定时长（分钟）
    val actualMinutes: Double = 0.0,       // 实际使用时长（分钟）
    val costMinutes: Double = 0.0,         // 扣减/变动时长（分钟，正数表示扣减，负数表示退还）
    val costCount: Double = 0.0,           // 扣减次数（支持0.5次，正数表示消耗，负数表示返还）
    val balanceMinutes: Double = 0.0,      // 变动后剩余时长（分钟）
    val balanceCount: Int = 0              // 变动后剩余次数
)

/**
 * 应用使用分桶与汇总
 */
enum class AppUsageViewMode {
    CATEGORIZED_GAMES, // 分类汇总（所有游戏合并为大类）
    INDIVIDUAL_APPS    // 单应用排行（真实明细独立展示）
}

data class AppUsageItem(
    val packageName: String,
    val appName: String,
    val category: String,
    val openCount: Int,
    val usageDurationMinutes: Long,
    val isGameCategory: Boolean = false
)

data class AppUsageBucketData(
    val bucket: StatsBucket,
    val openCount: Int,
    val usageMinutes: Float
)

/**
 * 科学行为 6 分法分类体系 (参考 iOS Screen Time 与防沉迷行为心理学)
 */
enum class AppCategory(val id: String, val displayName: String, val icon: String) {
    COMMUNICATION("communication", "社交通讯", "💬"),
    GAME("game", "游戏", "🎮"),
    VIDEO("video", "视频娱乐", "🎬"),
    AUDIO("audio", "音乐音频", "🎵"),
    AI_PRODUCTIVITY("ai_productivity", "AI与效率", "🤖"),
    TOOLS_SYSTEM("tools_system", "系统与工具", "🛠️");

    companion object {
        fun fromId(id: String): AppCategory? = entries.firstOrNull { it.id == id }
    }
}

data class CategoryUsageSummary(
    val category: AppCategory,
    val totalUsageMinutes: Long,
    val totalOpenCount: Int,
    val appCount: Int,
    val subApps: List<AppUsageItem>
)

data class CategoryDetailData(
    val category: AppCategory,
    val totalUsageMinutes: Long,
    val totalOpenCount: Int,
    val buckets: List<AppUsageBucketData>,
    val subApps: List<AppUsageItem>
)

data class AppUsageSummary(
    val totalOpens: Int,
    val totalUsageMinutes: Long,
    val appList: List<AppUsageItem>,
    val buckets: List<AppUsageBucketData>,
    val selectedAppTrendBuckets: List<AppUsageBucketData>? = null,
    val hasUsageAccessPermission: Boolean = true,
    val categorySummaries: List<CategoryUsageSummary> = emptyList()
)

/**
 * 起床统计明细记录
 */
data class AlarmWakeRecord(
    val id: String,
    val dateLabel: String,
    val alarmMinuteOfDay: Int,          // 计划响铃时间（0~1439）
    val firstStageCompletedMinute: Int, // 第一阶段完成时间（0~1439）
    val firstStageDurationMinutes: Int, // 第一阶段耗时（分钟）
    val totalFlowDurationMinutes: Int,  // 完整流程耗时（分钟）
    val completedAt: Long = 0L,
    val alarmId: Int = 0
)

data class AlarmBucketData(
    val bucket: StatsBucket,
    val alarmMinuteOfDay: Float?,
    val firstStageCompletedMinute: Float?,
    val firstStageDurationMinutes: Float?,
    val totalFlowDurationMinutes: Float?
)

data class AlarmStatsSummary(
    val medianAlarmMinuteOfDay: Int?,
    val medianFirstStageDurationMinutes: Int?,
    val medianTotalFlowDurationMinutes: Int?,
    val recordedDaysCount: Int,
    val buckets: List<AlarmBucketData>,
    val recentRecords: List<AlarmWakeRecord>
)

/**
 * 规则保护记录与分桶
 */
data class RuleProtectionItem(
    val id: String,
    val timeLabel: String,
    val ruleName: String,
    val editCount: Int,
    val state: RuleRelaxationState,
    val stateTitle: String,
    val stateSubTitle: String
)

data class RuleProtectionBucketData(
    val bucket: StatsBucket,
    val fullyRestoredCount: Int = 0,
    val partiallyRestoredCount: Int = 0,
    val effectiveCount: Int = 0,
    val pendingCount: Int = 0,
    val favorableCount: Int = 0,
    val unfavorableCount: Int = 0,
    val reversedCount: Int = 0
) {
    val totalCount: Int get() = fullyRestoredCount + partiallyRestoredCount + effectiveCount + pendingCount
    val netChange: Int get() = favorableCount - unfavorableCount
}

data class RuleProtectionSummary(
    val totalLoosenItems: Int = 0,
    val fullyRestoredCount: Int = 0,
    val partiallyRestoredCount: Int = 0,
    val effectiveCount: Int = 0,
    val pendingCount: Int = 0,
    val buckets: List<RuleProtectionBucketData> = emptyList(),
    val recentChanges: List<RuleProtectionItem> = emptyList(),
    val totalFavorable: Int = 0,
    val totalUnfavorable: Int = 0,
    val totalReversed: Int = 0,
    val netChange: Int = 0
)

/**
 * 统计页 UI 状态单真理源
 */
data class StatisticsUiState(
    val section: StatsSection = StatsSection.REST,
    val range: StatsDateRange = StatsDateRange.ThisWeek,
    val weekdayFilter: WeekdayFilter = WeekdayFilter.ALL,
    val restMetric: MetricDimension = MetricDimension.DURATION,
    val appMetric: MetricDimension = MetricDimension.COUNT,
    val alarmView: AlarmStatsView = AlarmStatsView.TIME,
    val appViewMode: AppUsageViewMode = AppUsageViewMode.CATEGORIZED_GAMES,
    val excludedApps: Set<String> = emptySet(),
    val selectedAppPackage: String? = null,
    val hasUsageAccessPermission: Boolean = true
)
