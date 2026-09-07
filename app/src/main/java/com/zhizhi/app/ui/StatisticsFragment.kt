package com.zhizhi.app.ui

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import com.github.mikephil.charting.charts.BarChart
import com.github.mikephil.charting.charts.LineChart
import com.zhizhi.app.R
import com.zhizhi.app.data.statistics.AlarmStatsView
import com.zhizhi.app.data.statistics.AppUsageViewMode
import com.zhizhi.app.data.statistics.MetricDimension
import com.zhizhi.app.data.statistics.ResolvedDateRange
import com.zhizhi.app.data.statistics.StatsDateRange
import com.zhizhi.app.data.statistics.StatsSection
import com.zhizhi.app.data.statistics.StatisticsUiState
import com.zhizhi.app.manager.statistics.StatisticsRepository
import com.zhizhi.app.manager.statistics.StatsDateRangeResolver
import com.zhizhi.app.ui.statistics.StatisticsChartRenderer
import com.zhizhi.app.ui.statistics.StatisticsFilterBottomSheet

/**
 * 知止 (ZhiZhi) 统计页
 * 依据最新交互体系（V20 堆叠与累加折线），重构为以用户真实行为为中心、可筛选、可扩展的统计系统。
 * 一级分区：休息（默认） | 起床 | 应用 | 规则保护
 * 严格遵照项目核心规范：
 * 1. UI 仅负责展示和交互分发，不编写底层业务判定，严禁直接读写业务 SharedPreferences；
 * 2. 颜色属性 100% 动态接入春夏秋冬四季主题；
 * 3. 超过 12 个横轴点自适应切换为折线图，不超过 12 个使用柱状图；
 * 4. 休息页短周期堆叠柱，长周期三层累加折线；
 * 5. 全面接入真机 UsageStatsManager 与本地账本真实数据，彻底杜绝伪随机/模拟数据。
 */
class StatisticsFragment : Fragment() {

    private val seasonalAccentAttr = R.attr.zzAccent
    private var uiState = StatisticsUiState()
    private lateinit var filterBottomSheet: StatisticsFilterBottomSheet

    // 头部控件
    private lateinit var btnOpenRange: LinearLayout
    private lateinit var tvRangeLabel: TextView
    private lateinit var btnOpenFilter: LinearLayout
    private lateinit var tvFilterLabel: TextView

    // 一级页签
    private lateinit var tabRest: Button
    private lateinit var tabAlarm: Button
    private lateinit var tabApps: Button
    private lateinit var tabRules: Button

    // 主卡片核心视图
    private lateinit var tvCardTitle: TextView
    private lateinit var tvCardSubtitle: TextView
    private lateinit var tvCardBadge: TextView
    private lateinit var layoutRuleHero: LinearLayout
    private lateinit var tvRuleHeroCount: TextView

    // 权限提示条
    private lateinit var layoutUsagePermissionWarning: LinearLayout
    private lateinit var btnGrantUsagePermission: Button

    // KPI 卡片
    private lateinit var gridKpis: View
    private lateinit var kpiCard1: View
    private lateinit var tvKpiTitle1: TextView
    private lateinit var tvKpiVal1: TextView
    private lateinit var tvKpiSub1: TextView

    private lateinit var kpiCard2: View
    private lateinit var tvKpiTitle2: TextView
    private lateinit var tvKpiVal2: TextView
    private lateinit var tvKpiSub2: TextView

    private lateinit var kpiCard3: View
    private lateinit var tvKpiTitle3: TextView
    private lateinit var tvKpiVal3: TextView
    private lateinit var tvKpiSub3: TextView

    private lateinit var kpiCard4: View
    private lateinit var tvKpiTitle4: TextView
    private lateinit var tvKpiVal4: TextView
    private lateinit var tvKpiSub4: TextView

    // 子维度切换
    private lateinit var layoutMetricToggle: LinearLayout
    private lateinit var btnMetric1: Button
    private lateinit var btnMetric2: Button

    // 图表说明与图例
    private lateinit var tvChartTitle: TextView
    private lateinit var tvChartTypeHint: TextView
    private lateinit var layoutLegend: LinearLayout
    private lateinit var legendItem1: View
    private lateinit var dotLegend1: View
    private lateinit var tvLegend1: TextView
    private lateinit var legendItem2: View
    private lateinit var dotLegend2: View
    private lateinit var tvLegend2: TextView
    private lateinit var legendItem3: View
    private lateinit var dotLegend3: View
    private lateinit var tvLegend3: TextView

    // 图表核心控件
    private lateinit var chartBar: BarChart
    private lateinit var chartLine: LineChart
    private lateinit var tvChartNote: TextView

    // 应用页专属视图
    private lateinit var layoutAppsExtra: LinearLayout
    private lateinit var layoutAppViewModeToggle: LinearLayout
    private lateinit var btnAppViewCategorized: Button
    private lateinit var btnAppViewIndividual: Button
    private lateinit var tvAppRankTitle: TextView
    private lateinit var tvAppListEmpty: TextView
    private lateinit var layoutRankBars: LinearLayout
    private lateinit var layoutAppList: LinearLayout

    // 起床页专属列表
    private lateinit var layoutAlarmExtra: LinearLayout
    private lateinit var layoutAlarmRecentList: LinearLayout

    // 规则保护页专属列表
    private lateinit var layoutRulesExtra: LinearLayout
    private lateinit var layoutRulesRecentList: LinearLayout

    // 次级卡片
    private lateinit var cardSecondary: View
    private lateinit var tvSecondaryTitle: TextView
    private lateinit var tvSecondaryDesc: TextView

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_statistics, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        filterBottomSheet = StatisticsFilterBottomSheet(requireContext())
        initViews(view)
        setupListeners()
        refreshStats()
    }

    override fun onResume() {
        super.onResume()
        refreshStats()
    }

    private fun initViews(v: View) {
        btnOpenRange = v.findViewById(R.id.btn_open_range)
        tvRangeLabel = v.findViewById(R.id.tv_range_label)
        btnOpenFilter = v.findViewById(R.id.btn_open_filter)
        tvFilterLabel = v.findViewById(R.id.tv_filter_label)

        tabRest = v.findViewById(R.id.tab_rest)
        tabAlarm = v.findViewById(R.id.tab_alarm)
        tabApps = v.findViewById(R.id.tab_apps)
        tabRules = v.findViewById(R.id.tab_rules)

        tvCardTitle = v.findViewById(R.id.tv_card_title)
        tvCardSubtitle = v.findViewById(R.id.tv_card_subtitle)
        tvCardBadge = v.findViewById(R.id.tv_card_badge)
        layoutRuleHero = v.findViewById(R.id.layout_rule_hero)
        tvRuleHeroCount = v.findViewById(R.id.tv_rule_hero_count)

        layoutUsagePermissionWarning = v.findViewById(R.id.layout_usage_permission_warning)
        btnGrantUsagePermission = v.findViewById(R.id.btn_grant_usage_permission)

        gridKpis = v.findViewById(R.id.grid_kpis)
        kpiCard1 = v.findViewById(R.id.kpi_card_1)
        tvKpiTitle1 = v.findViewById(R.id.tv_kpi_title_1)
        tvKpiVal1 = v.findViewById(R.id.tv_kpi_val_1)
        tvKpiSub1 = v.findViewById(R.id.tv_kpi_sub_1)

        kpiCard2 = v.findViewById(R.id.kpi_card_2)
        tvKpiTitle2 = v.findViewById(R.id.tv_kpi_title_2)
        tvKpiVal2 = v.findViewById(R.id.tv_kpi_val_2)
        tvKpiSub2 = v.findViewById(R.id.tv_kpi_sub_2)

        kpiCard3 = v.findViewById(R.id.kpi_card_3)
        tvKpiTitle3 = v.findViewById(R.id.tv_kpi_title_3)
        tvKpiVal3 = v.findViewById(R.id.tv_kpi_val_3)
        tvKpiSub3 = v.findViewById(R.id.tv_kpi_sub_3)

        kpiCard4 = v.findViewById(R.id.kpi_card_4)
        tvKpiTitle4 = v.findViewById(R.id.tv_kpi_title_4)
        tvKpiVal4 = v.findViewById(R.id.tv_kpi_val_4)
        tvKpiSub4 = v.findViewById(R.id.tv_kpi_sub_4)

        layoutMetricToggle = v.findViewById(R.id.layout_metric_toggle)
        btnMetric1 = v.findViewById(R.id.btn_metric_1)
        btnMetric2 = v.findViewById(R.id.btn_metric_2)

        tvChartTitle = v.findViewById(R.id.tv_chart_title)
        tvChartTypeHint = v.findViewById(R.id.tv_chart_type_hint)
        layoutLegend = v.findViewById(R.id.layout_legend)
        legendItem1 = v.findViewById(R.id.legend_item_1)
        dotLegend1 = v.findViewById(R.id.dot_legend_1)
        tvLegend1 = v.findViewById(R.id.tv_legend_1)
        legendItem2 = v.findViewById(R.id.legend_item_2)
        dotLegend2 = v.findViewById(R.id.dot_legend_2)
        tvLegend2 = v.findViewById(R.id.tv_legend_2)
        legendItem3 = v.findViewById(R.id.legend_item_3)
        dotLegend3 = v.findViewById(R.id.dot_legend_3)
        tvLegend3 = v.findViewById(R.id.tv_legend_3)

        chartBar = v.findViewById(R.id.chart_bar)
        chartLine = v.findViewById(R.id.chart_line)
        tvChartNote = v.findViewById(R.id.tv_chart_note)

        layoutAppsExtra = v.findViewById(R.id.layout_apps_extra)
        layoutAppViewModeToggle = v.findViewById(R.id.layout_app_view_mode_toggle)
        btnAppViewCategorized = v.findViewById(R.id.btn_app_view_categorized)
        btnAppViewIndividual = v.findViewById(R.id.btn_app_view_individual)
        tvAppRankTitle = v.findViewById(R.id.tv_app_rank_title)
        tvAppListEmpty = v.findViewById(R.id.tv_app_list_empty)
        layoutRankBars = v.findViewById(R.id.layout_rank_bars)
        layoutAppList = v.findViewById(R.id.layout_app_list)

        layoutAlarmExtra = v.findViewById(R.id.layout_alarm_extra)
        layoutAlarmRecentList = v.findViewById(R.id.layout_alarm_recent_list)

        layoutRulesExtra = v.findViewById(R.id.layout_rules_extra)
        layoutRulesRecentList = v.findViewById(R.id.layout_rules_recent_list)

        cardSecondary = v.findViewById(R.id.card_secondary)
        tvSecondaryTitle = v.findViewById(R.id.tv_secondary_title)
        tvSecondaryDesc = v.findViewById(R.id.tv_secondary_desc)

        // 兼容保留 View ID
        v.findViewById<CheckBox>(R.id.cb_weighted_stats)?.visibility = View.GONE
    }

    private fun setupListeners() {
        btnOpenRange.setOnClickListener {
            filterBottomSheet.showRangePicker(uiState.range) { newRange ->
                uiState = uiState.copy(range = newRange)
                refreshStats()
            }
        }

        btnOpenFilter.setOnClickListener {
            filterBottomSheet.showConditionFilter(uiState.weekdayFilter, uiState.excludedApps) { newFilter, newExcluded ->
                uiState = uiState.copy(
                    weekdayFilter = newFilter,
                    excludedApps = newExcluded,
                    selectedAppPackage = if (uiState.selectedAppPackage in newExcluded) null else uiState.selectedAppPackage
                )
                refreshStats()
            }
        }

        tabRest.setOnClickListener { switchSection(StatsSection.REST) }
        tabAlarm.setOnClickListener { switchSection(StatsSection.ALARM) }
        tabApps.setOnClickListener { switchSection(StatsSection.APPS) }
        tabRules.setOnClickListener { switchSection(StatsSection.RULE_PROTECTION) }

        btnGrantUsagePermission.setOnClickListener {
            try {
                startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
            } catch (e: Exception) {
                Log.e("StatisticsFragment", "Failed to open usage access settings", e)
            }
        }

        btnAppViewCategorized.setOnClickListener {
            if (uiState.appViewMode != AppUsageViewMode.CATEGORIZED_GAMES) {
                uiState = uiState.copy(appViewMode = AppUsageViewMode.CATEGORIZED_GAMES, selectedAppPackage = null)
                refreshStats()
            }
        }

        btnAppViewIndividual.setOnClickListener {
            if (uiState.appViewMode != AppUsageViewMode.INDIVIDUAL_APPS) {
                uiState = uiState.copy(appViewMode = AppUsageViewMode.INDIVIDUAL_APPS, selectedAppPackage = null)
                refreshStats()
            }
        }
    }

    private fun switchSection(section: StatsSection) {
        if (uiState.section != section) {
            uiState = uiState.copy(section = section)
            refreshStats()
        }
    }

    private fun syncTabButtons() {
        val ctx = context ?: return
        val tabs = listOf(
            tabRest to StatsSection.REST,
            tabAlarm to StatsSection.ALARM,
            tabApps to StatsSection.APPS,
            tabRules to StatsSection.RULE_PROTECTION
        )

        tabs.forEach { (btn, sec) ->
            val isSelected = uiState.section == sec
            btn.backgroundTintList = null
            btn.setBackgroundResource(if (isSelected) R.drawable.bg_zz_option_btn_selected else R.drawable.bg_zz_option_btn)
            btn.setTextColor(if (isSelected) Color.WHITE else ThemeManager.getColorAttr(ctx, R.attr.zzText))
            btn.paint.isFakeBoldText = true
        }
    }

    private fun refreshStats() {
        if (!isAdded) return
        val ctx = context ?: return
        syncTabButtons()

        val resolvedRange = StatsDateRangeResolver.resolveRange(uiState.range)
        tvRangeLabel.text = "${resolvedRange.displayName} · ${resolvedRange.dateSubText}"

        val excludedCount = uiState.excludedApps.size
        val appSummary = if (excludedCount == 0) "全部应用" else "排除 $excludedCount"
        tvFilterLabel.text = "${uiState.weekdayFilter.summaryText()} · $appSummary"

        when (uiState.section) {
            StatsSection.REST -> renderRest(resolvedRange)
            StatsSection.ALARM -> renderAlarm(resolvedRange)
            StatsSection.APPS -> renderApps(resolvedRange)
            StatsSection.RULE_PROTECTION -> renderRules(resolvedRange)
        }
    }

    // ==========================================
    // 1. 休息页渲染
    // ==========================================
    private fun renderRest(resolvedRange: ResolvedDateRange) {
        val ctx = context ?: return
        val summary = StatisticsRepository.getExitStats(ctx, uiState.range, resolvedRange, uiState.weekdayFilter)

        tvCardTitle.text = "休息"
        tvCardSubtitle.text = "${resolvedRange.displayName} · ${resolvedRange.dateSubText} · ${uiState.weekdayFilter.summaryText()}"
        tvCardBadge.text = "共${summary.totalRestCount + summary.totalTempUnlockCount + summary.totalPrivilegeCount}次放开"

        layoutUsagePermissionWarning.isVisible = false
        layoutRuleHero.isVisible = false
        gridKpis.isVisible = true
        kpiCard1.isVisible = true
        kpiCard2.isVisible = true
        kpiCard3.isVisible = true
        kpiCard4.isVisible = true

        tvKpiTitle1.text = "休息"
        tvKpiVal1.text = "${summary.totalRestCount} 次"
        tvKpiSub1.text = "累计时长 ${StatisticsChartRenderer.formatDuration(summary.totalRestMinutes)}"

        tvKpiTitle2.text = "临时解锁"
        tvKpiVal2.text = "${summary.totalTempUnlockCount} 次"
        tvKpiSub2.text = "累计时长 ${StatisticsChartRenderer.formatDuration(summary.totalTempUnlockMinutes)}"

        tvKpiTitle3.text = "特权"
        tvKpiVal3.text = "${summary.totalPrivilegeCount} 次"
        tvKpiSub3.text = "累计时长 ${StatisticsChartRenderer.formatDuration(summary.totalPrivilegeMinutes)}"

        tvKpiTitle4.text = "冷却中再次申请"
        tvKpiVal4.text = "${summary.cooldownAttemptCount} 次"
        tvKpiSub4.text = "只记申请事件"

        // 子维度切换：时长 vs 次数
        layoutMetricToggle.isVisible = true
        btnMetric1.text = "时长"
        btnMetric2.text = "次数"

        val isDuration = uiState.restMetric == MetricDimension.DURATION
        btnMetric1.backgroundTintList = null
        btnMetric2.backgroundTintList = null
        btnMetric1.setBackgroundResource(if (isDuration) R.drawable.bg_zz_option_btn_selected else R.drawable.bg_zz_option_btn)
        btnMetric1.setTextColor(if (isDuration) Color.WHITE else ThemeManager.getColorAttr(ctx, R.attr.zzText))
        btnMetric1.paint.isFakeBoldText = true
        btnMetric2.setBackgroundResource(if (!isDuration) R.drawable.bg_zz_option_btn_selected else R.drawable.bg_zz_option_btn)
        btnMetric2.setTextColor(if (!isDuration) Color.WHITE else ThemeManager.getColorAttr(ctx, R.attr.zzText))
        btnMetric2.paint.isFakeBoldText = true

        btnMetric1.setOnClickListener {
            if (uiState.restMetric != MetricDimension.DURATION) {
                uiState = uiState.copy(restMetric = MetricDimension.DURATION)
                refreshStats()
            }
        }
        btnMetric2.setOnClickListener {
            if (uiState.restMetric != MetricDimension.COUNT) {
                uiState = uiState.copy(restMetric = MetricDimension.COUNT)
                refreshStats()
            }
        }

        // 图表与说明
        val chartType = StatsDateRangeResolver.chooseChartType(summary.buckets.size)
        tvChartTitle.text = "休息 / 临时解锁 / 特权 · ${if (isDuration) "时长" else "次数"}"
        tvChartTypeHint.text = if (chartType == com.zhizhi.app.data.statistics.ChartType.LINE) "累加折线图" else "堆叠柱状图"

        // 图例
        layoutLegend.isVisible = true
        legendItem1.isVisible = true
        legendItem2.isVisible = true
        legendItem3.isVisible = true
        dotLegend1.setBackgroundColor(StatisticsChartRenderer.COLOR_REST)
        tvLegend1.text = "休息"
        dotLegend2.setBackgroundColor(StatisticsChartRenderer.COLOR_TEMP_UNLOCK)
        tvLegend2.text = "临时解锁"
        dotLegend3.setBackgroundColor(StatisticsChartRenderer.COLOR_PRIVILEGE)
        tvLegend3.text = "特权"

        StatisticsChartRenderer.renderRestChart(chartBar, chartLine, summary, uiState.restMetric, ctx)

        tvChartNote.text = "三者都属于“休息/放开规则”的出口，因此放在同一张图里。\n横轴不超过 12 个数据点时使用堆叠柱状图；超过 12 个数据点后改为累加折线/堆叠面积：底层休息，中层临时解锁，顶层特权。月视角按天保留数据，因此通常会直接显示这种累加趋势。"

        layoutAppsExtra.isVisible = false
        layoutAlarmExtra.isVisible = false
        layoutRulesExtra.isVisible = false

        cardSecondary.isVisible = true
        tvSecondaryTitle.text = "冷却中再次申请"
        tvSecondaryDesc.text = "冷却中的再次申请不是一次成功出口，因此不叠进休息图，只单独记录次数。"
    }

    // ==========================================
    // 2. 应用页渲染 (接入 UsageStatsManager 真实数据)
    // ==========================================
    private fun renderApps(resolvedRange: ResolvedDateRange) {
        val ctx = context ?: return
        val summary = StatisticsRepository.getAppUsageStats(
            ctx,
            uiState.range,
            resolvedRange,
            uiState.weekdayFilter,
            uiState.appViewMode,
            uiState.excludedApps,
            uiState.selectedAppPackage
        )

        val appCount = summary.appList.size
        val excludedCount = uiState.excludedApps.size
        val badge = if (excludedCount == 0) "已载入 $appCount 个应用" else "已载入 $appCount · 排除 $excludedCount"

        tvCardTitle.text = "应用"
        tvCardSubtitle.text = "${resolvedRange.displayName} · ${resolvedRange.dateSubText} · ${uiState.weekdayFilter.summaryText()}"
        tvCardBadge.text = badge

        // 权限感知
        layoutUsagePermissionWarning.isVisible = !summary.hasUsageAccessPermission

        layoutRuleHero.isVisible = false
        gridKpis.isVisible = true
        kpiCard1.isVisible = true
        kpiCard2.isVisible = true
        kpiCard3.isVisible = false
        kpiCard4.isVisible = false

        tvKpiTitle1.text = "打开次数"
        tvKpiVal1.text = "${summary.totalOpens} 次"
        tvKpiSub1.text = "全天进入前台"

        tvKpiTitle2.text = "实际使用"
        tvKpiVal2.text = StatisticsChartRenderer.formatDuration(summary.totalUsageMinutes)
        tvKpiSub2.text = "全天累计"

        // 子维度切换：次数 vs 时长
        layoutMetricToggle.isVisible = true
        btnMetric1.text = "次数"
        btnMetric2.text = "时长"

        val isCount = uiState.appMetric == MetricDimension.COUNT
        btnMetric1.backgroundTintList = null
        btnMetric2.backgroundTintList = null
        btnMetric1.setBackgroundResource(if (isCount) R.drawable.bg_zz_option_btn_selected else R.drawable.bg_zz_option_btn)
        btnMetric1.setTextColor(if (isCount) Color.WHITE else ThemeManager.getColorAttr(ctx, R.attr.zzText))
        btnMetric1.paint.isFakeBoldText = true
        btnMetric2.setBackgroundResource(if (!isCount) R.drawable.bg_zz_option_btn_selected else R.drawable.bg_zz_option_btn)
        btnMetric2.setTextColor(if (!isCount) Color.WHITE else ThemeManager.getColorAttr(ctx, R.attr.zzText))
        btnMetric2.paint.isFakeBoldText = true

        btnMetric1.setOnClickListener {
            if (uiState.appMetric != MetricDimension.COUNT) {
                uiState = uiState.copy(appMetric = MetricDimension.COUNT)
                refreshStats()
            }
        }
        btnMetric2.setOnClickListener {
            if (uiState.appMetric != MetricDimension.DURATION) {
                uiState = uiState.copy(appMetric = MetricDimension.DURATION)
                refreshStats()
            }
        }

        val bucketsToRender = summary.selectedAppTrendBuckets ?: summary.buckets
        val chartType = StatsDateRangeResolver.chooseChartType(bucketsToRender.size)
        val selectedApp = uiState.selectedAppPackage
        tvChartTitle.text = if (selectedApp != null) "$selectedApp · ${if (isCount) "打开次数" else "使用时长"}趋势" else "全部应用 · ${if (isCount) "打开次数" else "使用时长"}趋势"
        tvChartTypeHint.text = if (chartType == com.zhizhi.app.data.statistics.ChartType.LINE) "折线图" else "柱状图"

        layoutLegend.isVisible = false
        StatisticsChartRenderer.renderAppTrendChart(chartBar, chartLine, bucketsToRender, uiState.appMetric, ctx)

        tvChartNote.text = "应用统计看全天真实前台使用，不限定是否处于勿扰或受限时段。主图可切“次数 / 时长”；横轴不超过 12 个数据点时用柱状图，超过 12 个时自动切换折线图。"

        // 渲染排行榜与列表
        layoutAppsExtra.isVisible = true
        layoutAlarmExtra.isVisible = false
        layoutRulesExtra.isVisible = false

        // 视图模式切换按钮样式
        val isCat = uiState.appViewMode == AppUsageViewMode.CATEGORIZED_GAMES
        btnAppViewCategorized.backgroundTintList = null
        btnAppViewIndividual.backgroundTintList = null
        btnAppViewCategorized.setBackgroundResource(if (isCat) R.drawable.bg_zz_option_btn_selected else R.drawable.bg_zz_option_btn)
        btnAppViewCategorized.setTextColor(if (isCat) Color.WHITE else ThemeManager.getColorAttr(ctx, R.attr.zzText))
        btnAppViewCategorized.paint.isFakeBoldText = true

        btnAppViewIndividual.setBackgroundResource(if (!isCat) R.drawable.bg_zz_option_btn_selected else R.drawable.bg_zz_option_btn)
        btnAppViewIndividual.setTextColor(if (!isCat) Color.WHITE else ThemeManager.getColorAttr(ctx, R.attr.zzText))
        btnAppViewIndividual.paint.isFakeBoldText = true

        if (summary.appList.isEmpty()) {
            tvAppListEmpty.isVisible = true
            tvAppListEmpty.text = if (!summary.hasUsageAccessPermission) "请先授权「使用情况访问权限」以加载应用真实统计" else "当前时间段内暂无应用使用记录"
            layoutRankBars.removeAllViews()
            layoutAppList.removeAllViews()
            tvAppRankTitle.text = if (isCount) "打开次数排行" else "使用时长排行"
        } else {
            tvAppListEmpty.isVisible = false
            tvAppRankTitle.text = if (isCount) "打开次数排行" else "使用时长排行"

            val sortedList = summary.appList.sortedByDescending { if (isCount) it.openCount.toLong() else it.usageDurationMinutes }
            layoutRankBars.removeAllViews()
            val top5 = sortedList.take(5)
            val maxVal = top5.maxOfOrNull { if (isCount) it.openCount.toLong() else it.usageDurationMinutes }?.coerceAtLeast(1L) ?: 1L

            top5.forEach { app ->
                val v = layoutInflater.inflate(R.layout.item_stats_app_rank, layoutRankBars, false)
                val name = v.findViewById<TextView>(R.id.tv_rank_name)
                val fill = v.findViewById<View>(R.id.v_rank_fill)
                val valText = v.findViewById<TextView>(R.id.tv_rank_value)

                val curVal = if (isCount) app.openCount.toLong() else app.usageDurationMinutes
                name.text = if (app.isGameCategory) "🎮 ${app.appName}" else app.appName
                valText.text = if (isCount) "$curVal 次" else StatisticsChartRenderer.formatDuration(curVal)

                fill.post {
                    val parentWidth = (fill.parent as View).width
                    val targetW = (parentWidth * (curVal.toFloat() / maxVal)).toInt()
                    fill.layoutParams.width = targetW
                    fill.requestLayout()
                }
                layoutRankBars.addView(v)
            }

            layoutAppList.removeAllViews()
            sortedList.forEach { app ->
                val v = layoutInflater.inflate(R.layout.item_stats_app_row, layoutAppList, false)
                val root = v.findViewById<View>(R.id.ll_app_row)
                val icon = v.findViewById<TextView>(R.id.tv_app_icon)
                val name = v.findViewById<TextView>(R.id.tv_app_name)
                val cat = v.findViewById<TextView>(R.id.tv_app_category)
                val opens = v.findViewById<TextView>(R.id.tv_app_opens)
                val duration = v.findViewById<TextView>(R.id.tv_app_duration)
                val btnExclude = v.findViewById<Button>(R.id.btn_exclude_app)

                icon.text = if (app.isGameCategory) "🎮" else (app.appName.firstOrNull()?.toString() ?: "📱")
                name.text = app.appName
                cat.text = app.category
                opens.text = "${app.openCount} 次"
                duration.text = StatisticsChartRenderer.formatDuration(app.usageDurationMinutes)

                val isSelected = uiState.selectedAppPackage == app.appName
                root.setBackgroundResource(if (isSelected) R.drawable.bg_zz_option_btn_selected else 0)

                root.setOnClickListener {
                    uiState = uiState.copy(selectedAppPackage = if (isSelected) null else app.appName)
                    refreshStats()
                }

                btnExclude.setOnClickListener {
                    val newEx = uiState.excludedApps.toMutableSet()
                    newEx.add(app.appName)
                    uiState = uiState.copy(
                        excludedApps = newEx,
                        selectedAppPackage = if (uiState.selectedAppPackage == app.appName) null else uiState.selectedAppPackage
                    )
                    refreshStats()
                }
                layoutAppList.addView(v)
            }
        }

        cardSecondary.isVisible = true
        tvSecondaryTitle.text = "当前应用统计口径"
        tvSecondaryDesc.text = if (isCat) "当前为「分类汇总模式」：手机上所有的游戏已自动合并为一项「游戏」大类统计；其他常用应用保持独立排行。" else "当前为「单应用排行模式」：手机所有已使用应用（包括各个独立游戏）均按真实时长由高到低完整展开展示。"
    }

    // ==========================================
    // 3. 起床页渲染 (真实闹钟打卡)
    // ==========================================
    private fun renderAlarm(resolvedRange: ResolvedDateRange) {
        val ctx = context ?: return
        val summary = StatisticsRepository.getAlarmStats(ctx, uiState.range, resolvedRange, uiState.weekdayFilter)

        layoutUsagePermissionWarning.isVisible = false
        tvCardTitle.text = "起床"
        tvCardSubtitle.text = "${resolvedRange.displayName} · ${resolvedRange.dateSubText} · ${uiState.weekdayFilter.summaryText()}"
        tvCardBadge.text = if (summary.recordedDaysCount == 0) "暂无记录" else "记录 ${summary.recordedDaysCount} 天"

        layoutRuleHero.isVisible = false
        gridKpis.isVisible = true
        kpiCard1.isVisible = true
        kpiCard2.isVisible = true
        kpiCard3.isVisible = true
        kpiCard4.isVisible = false

        tvKpiTitle1.text = "中位计划时间"
        tvKpiVal1.text = summary.medianAlarmMinuteOfDay?.let {
            String.format("%02d:%02d", it / 60, it % 60)
        } ?: "--:--"
        tvKpiSub1.text = "响铃基准"

        tvKpiTitle2.text = "中位完成耗时"
        tvKpiVal2.text = summary.medianFirstStageDurationMinutes?.let { "$it 分钟" } ?: "--"
        tvKpiSub2.text = "第一阶段解除"

        tvKpiTitle3.text = "中位完整流程"
        tvKpiVal3.text = summary.medianTotalFlowDurationMinutes?.let { "$it 分钟" } ?: "--"
        tvKpiSub3.text = "全部挑战完成"

        // 子维度切换：计划时间 vs 流程耗时
        layoutMetricToggle.isVisible = true
        btnMetric1.text = "计划时间"
        btnMetric2.text = "流程耗时"

        val isTime = uiState.alarmView == AlarmStatsView.TIME
        btnMetric1.backgroundTintList = null
        btnMetric2.backgroundTintList = null
        btnMetric1.setBackgroundResource(if (isTime) R.drawable.bg_zz_option_btn_selected else R.drawable.bg_zz_option_btn)
        btnMetric1.setTextColor(if (isTime) Color.WHITE else ThemeManager.getColorAttr(ctx, R.attr.zzText))
        btnMetric1.paint.isFakeBoldText = true
        btnMetric2.setBackgroundResource(if (!isTime) R.drawable.bg_zz_option_btn_selected else R.drawable.bg_zz_option_btn)
        btnMetric2.setTextColor(if (!isTime) Color.WHITE else ThemeManager.getColorAttr(ctx, R.attr.zzText))
        btnMetric2.paint.isFakeBoldText = true

        btnMetric1.setOnClickListener {
            if (uiState.alarmView != AlarmStatsView.TIME) {
                uiState = uiState.copy(alarmView = AlarmStatsView.TIME)
                refreshStats()
            }
        }
        btnMetric2.setOnClickListener {
            if (uiState.alarmView != AlarmStatsView.DURATION) {
                uiState = uiState.copy(alarmView = AlarmStatsView.DURATION)
                refreshStats()
            }
        }

        // 图表渲染
        tvChartTitle.text = if (isTime) "响铃与完成时刻" else "阶段与完整耗时"
        tvChartTypeHint.text = "折线图"

        layoutLegend.isVisible = true
        legendItem1.isVisible = true
        legendItem2.isVisible = true
        legendItem3.isVisible = false

        if (isTime) {
            dotLegend1.setBackgroundColor(StatisticsChartRenderer.COLOR_ALARM_TIME)
            tvLegend1.text = "计划响铃"
            dotLegend2.setBackgroundColor(StatisticsChartRenderer.COLOR_ALARM_FINISH)
            tvLegend2.text = "一阶段完成"
        } else {
            dotLegend1.setBackgroundColor(StatisticsChartRenderer.COLOR_ALARM_FINISH)
            tvLegend1.text = "一阶段耗时"
            dotLegend2.setBackgroundColor(StatisticsChartRenderer.COLOR_ALARM_TIME)
            tvLegend2.text = "完整耗时"
        }

        chartBar.isVisible = false
        chartLine.isVisible = true
        StatisticsChartRenderer.renderAlarmChart(chartLine, summary, uiState.alarmView, ctx)

        tvChartNote.text = if (isTime) {
            "时刻图：实线/主柱显示一阶段完成时刻，虚线/半透明柱显示计划响铃时刻。两线间距即为起床拖延时间。"
        } else {
            "耗时图：堆叠显示一阶段解除耗时与完整流程耗时（分钟）。柱越高代表今早起床唤醒越艰难。"
        }

        layoutAppsExtra.isVisible = false
        layoutAlarmExtra.isVisible = true
        layoutRulesExtra.isVisible = false

        layoutAlarmRecentList.removeAllViews()
        if (summary.recentRecords.isEmpty()) {
            val tv = TextView(ctx)
            tv.text = "暂无打卡记录"
            tv.setTextColor(ThemeManager.getColorAttr(ctx, R.attr.zzMuted))
            tv.textSize = 12f
            layoutAlarmRecentList.addView(tv)
        } else {
            summary.recentRecords.forEach { rec ->
                val v = layoutInflater.inflate(R.layout.item_stats_alarm_row, layoutAlarmRecentList, false)
                val date = v.findViewById<TextView>(R.id.tv_alarm_date)
                val plan = v.findViewById<TextView>(R.id.tv_alarm_time_main)
                val stage1Time = v.findViewById<TextView>(R.id.tv_alarm_stage1_time)
                val s1 = v.findViewById<TextView>(R.id.tv_alarm_stage1_duration)
                val total = v.findViewById<TextView>(R.id.tv_alarm_total_duration)

                date.text = rec.dateLabel
                plan.text = String.format("%02d:%02d 计划", rec.alarmMinuteOfDay / 60, rec.alarmMinuteOfDay % 60)
                stage1Time.text = String.format("%02d:%02d 完成", rec.firstStageCompletedMinute / 60, rec.firstStageCompletedMinute % 60)
                s1.text = "${rec.firstStageDurationMinutes}m"
                total.text = "流程 ${rec.totalFlowDurationMinutes}m"
                layoutAlarmRecentList.addView(v)
            }
        }

        cardSecondary.isVisible = true
        tvSecondaryTitle.text = "起床统计口径"
        tvSecondaryDesc.text = "中位数能有效排除偶发失眠或极特殊日期的极端值干扰，更能体现长期真实的作息生物钟。"
    }

    // ==========================================
    // 4. 规则保护页渲染 (真实 baseline/pending 记录)
    // ==========================================
    private fun renderRules(resolvedRange: ResolvedDateRange) {
        val ctx = context ?: return
        val summary = StatisticsRepository.getRuleProtectionStats(ctx, uiState.range, resolvedRange, uiState.weekdayFilter)

        layoutUsagePermissionWarning.isVisible = false
        tvCardTitle.text = "防破防"
        tvCardSubtitle.text = "${resolvedRange.displayName} · ${resolvedRange.dateSubText} · ${uiState.weekdayFilter.summaryText()}"
        tvCardBadge.text = if (summary.totalLoosenItems == 0) "无放宽记录" else "放宽 ${summary.totalLoosenItems} 次"

        layoutRuleHero.isVisible = true
        tvRuleHeroCount.text = "${summary.fullyRestoredCount + summary.partiallyRestoredCount}"

        gridKpis.isVisible = true
        kpiCard1.isVisible = true
        kpiCard2.isVisible = true
        kpiCard3.isVisible = true
        kpiCard4.isVisible = true

        tvKpiTitle1.text = "完全恢复"
        tvKpiVal1.text = "${summary.fullyRestoredCount} 项"
        tvKpiSub1.text = "已回到原边界"

        tvKpiTitle2.text = "部分恢复"
        tvKpiVal2.text = "${summary.partiallyRestoredCount} 项"
        tvKpiSub2.text = "收回部分放宽"

        tvKpiTitle3.text = "已正式生效"
        tvKpiVal3.text = "${summary.effectiveCount} 项"
        tvKpiSub3.text = "等待后放宽"

        tvKpiTitle4.text = "等待生效中"
        tvKpiVal4.text = "${summary.pendingCount} 项"
        tvKpiSub4.text = "处于缓冲期"

        layoutMetricToggle.isVisible = false
        tvChartTitle.text = "放宽尝试与恢复趋势"
        val chartType = StatsDateRangeResolver.chooseChartType(summary.buckets.size)
        tvChartTypeHint.text = if (chartType == com.zhizhi.app.data.statistics.ChartType.LINE) "累加折线图" else "堆叠柱状图"

        layoutLegend.isVisible = true
        legendItem1.isVisible = true
        legendItem2.isVisible = true
        legendItem3.isVisible = true
        dotLegend1.setBackgroundColor(StatisticsChartRenderer.COLOR_RULE_FULL)
        tvLegend1.text = "完全恢复"
        dotLegend2.setBackgroundColor(StatisticsChartRenderer.COLOR_RULE_PARTIAL)
        tvLegend2.text = "部分恢复"
        dotLegend3.setBackgroundColor(StatisticsChartRenderer.COLOR_RULE_EFFECTIVE)
        tvLegend3.text = "已生效"

        StatisticsChartRenderer.renderRuleProtectionChart(chartBar, chartLine, summary, ctx)

        tvChartNote.text = "规则保护重点衡量“冲动放宽被拦住/恢复”的比例。柱状图堆叠展示完全恢复、部分恢复和最终放宽生效项。"

        layoutAppsExtra.isVisible = false
        layoutAlarmExtra.isVisible = false
        layoutRulesExtra.isVisible = true

        layoutRulesRecentList.removeAllViews()
        if (summary.recentChanges.isEmpty()) {
            val tv = TextView(ctx)
            tv.text = "暂无规则修改记录"
            tv.setTextColor(ThemeManager.getColorAttr(ctx, R.attr.zzMuted))
            tv.textSize = 12f
            layoutRulesRecentList.addView(tv)
        } else {
            summary.recentChanges.forEach { item ->
                val v = layoutInflater.inflate(R.layout.item_stats_rule_row, layoutRulesRecentList, false)
                val time = v.findViewById<TextView>(R.id.tv_rule_time)
                val name = v.findViewById<TextView>(R.id.tv_rule_name)
                val edits = v.findViewById<TextView>(R.id.tv_rule_edits)
                val state = v.findViewById<TextView>(R.id.tv_rule_state)
                val substate = v.findViewById<TextView>(R.id.tv_rule_substate)

                time.text = item.timeLabel
                name.text = item.ruleName
                edits.text = "${item.editCount} 次修改"
                state.text = item.stateTitle
                substate.text = item.stateSubTitle
                layoutRulesRecentList.addView(v)
            }
        }

        cardSecondary.isVisible = true
        tvSecondaryTitle.text = "规则保护口径"
        tvSecondaryDesc.text = "只要修改后重新收紧即算作挽救成功。冲动期内的多次微调合并为同一次放宽尝试。"
    }
}
