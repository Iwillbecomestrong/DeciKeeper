package com.zhizhi.app.ui.statistics

import android.content.Context
import android.graphics.Color
import android.view.View
import androidx.core.view.isVisible
import com.github.mikephil.charting.charts.BarChart
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.components.YAxis
import com.github.mikephil.charting.data.BarData
import com.github.mikephil.charting.data.BarDataSet
import com.github.mikephil.charting.data.BarEntry
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.github.mikephil.charting.formatter.ValueFormatter
import com.zhizhi.app.R
import com.zhizhi.app.data.statistics.AlarmStatsSummary
import com.zhizhi.app.data.statistics.AlarmStatsView
import com.zhizhi.app.data.statistics.AppUsageBucketData
import com.zhizhi.app.data.statistics.ChartType
import com.zhizhi.app.data.statistics.ExitStatsSummary
import com.zhizhi.app.data.statistics.MetricDimension
import com.zhizhi.app.data.statistics.RuleProtectionSummary
import com.zhizhi.app.manager.statistics.StatsDateRangeResolver
import com.zhizhi.app.ui.ThemeManager
import java.util.Locale

/**
 * 统计页图表渲染引擎
 * 负责 MPAndroidChart 柱状图与折线图的自动切换、堆叠柱绘制、累加折线绘制、四季主题取色与坐标轴格式化。
 */
object StatisticsChartRenderer {

    // 鲜明高饱和图表标准色盘（色轮大跨度分散，解决无文字图表多维度混淆痛点）
    val COLOR_REST: Int = 0xFF2E7D32.toInt() // 翡翠绿 Vivid Emerald Green
    val COLOR_TEMP_UNLOCK: Int = 0xFF1976D2.toInt() // 亮蓝 Vivid Royal Blue
    val COLOR_PRIVILEGE: Int = 0xFFEA580C.toInt() // 亮橙 Vivid Tangerine Orange

    val COLOR_ALARM_TIME: Int = 0xFF2E7D32.toInt() // 计划闹钟时间：亮绿
    val COLOR_ALARM_FINISH: Int = 0xFFEA580C.toInt() // 完成时间/耗时：亮橙

    val COLOR_RULE_FAVORABLE: Int = 0xFF2E7D32.toInt() // 有利改动（加严）：亮绿
    val COLOR_RULE_UNFAVORABLE: Int = 0xFFEA580C.toInt() // 不利改动（放宽）：亮橙
    val COLOR_RULE_REVERSED: Int = 0xFF1976D2.toInt() // 撤销不利改动：亮蓝

    val COLOR_RULE_FULL: Int = COLOR_RULE_FAVORABLE // 兼容旧别名
    val COLOR_RULE_PARTIAL: Int = COLOR_RULE_REVERSED // 兼容旧别名
    val COLOR_RULE_EFFECTIVE: Int = COLOR_RULE_UNFAVORABLE // 兼容旧别名

    private fun withAlpha(color: Int, alpha: Int): Int =
        Color.argb(alpha.coerceIn(0, 255), Color.red(color), Color.green(color), Color.blue(color))

    fun formatDuration(minutes: Long): String {
        return if (minutes < 60) {
            "${minutes}m"
        } else {
            "${minutes / 60}h ${minutes % 60}m"
        }
    }

    fun formatClockTime(minuteOfDay: Int): String {
        val h = (minuteOfDay / 60) % 24
        val m = minuteOfDay % 60
        return String.format(Locale.getDefault(), "%02d:%02d", h, m)
    }

    /**
     * 基础通用图表初始化配置
     */
    private fun setupCommonChart(chart: com.github.mikephil.charting.charts.BarLineChartBase<*>, context: Context) {
        chart.description.isEnabled = false
        chart.axisRight.isEnabled = false
        chart.legend.isEnabled = false // 统一在原生布局绘制四季联动图例
        chart.setScaleEnabled(false)
        chart.isDoubleTapToZoomEnabled = false
        chart.setPinchZoom(false)
        chart.setDrawGridBackground(false)
        chart.setNoDataText("暂无记录")
        chart.setNoDataTextColor(ThemeManager.getColorAttr(context, R.attr.zzMuted))

        val textMuted = ThemeManager.getColorAttr(context, R.attr.zzMuted)
        val lineSoft = ThemeManager.getColorAttr(context, R.attr.zzLine)

        chart.xAxis.apply {
            position = XAxis.XAxisPosition.BOTTOM
            setDrawGridLines(false)
            textColor = textMuted
            axisLineColor = lineSoft
            textSize = 9f
            granularity = 1f
            isGranularityEnabled = true
        }

        chart.axisLeft.apply {
            setDrawGridLines(true)
            textColor = textMuted
            axisLineColor = lineSoft
            gridColor = lineSoft
            textSize = 9f
            axisMinimum = 0f
        }
    }

    private fun applyXAxisDecimation(xAxis: XAxis, labels: List<String>) {
        xAxis.valueFormatter = object : ValueFormatter() {
            override fun getFormattedValue(value: Float): String {
                val idx = value.toInt()
                if (idx < 0 || idx >= labels.size) return ""
                val n = labels.size
                if (n <= 12) return labels[idx]
                val step = kotlin.math.ceil(n / 6.0).toInt().coerceAtLeast(1)
                return if (idx % step == 0 || idx == n - 1) labels[idx] else ""
            }
        }
    }

    // ==========================================
    // 1. 休息页：堆叠柱状图 / 累加折线图
    // ==========================================
    fun renderRestChart(
        barChart: BarChart,
        lineChart: LineChart,
        summary: ExitStatsSummary,
        metric: MetricDimension,
        context: Context
    ) {
        val buckets = summary.buckets
        if (buckets.isEmpty()) {
            barChart.isVisible = false
            lineChart.isVisible = false
            return
        }

        val chartType = StatsDateRangeResolver.chooseChartType(buckets.size)
        val labels = buckets.map { it.bucket.label }
        val accentColor = ThemeManager.getColorAttr(context, R.attr.zzAccent)
        val altColor = ThemeManager.getColorAttr(context, R.attr.zzAlt)
        val warningColor = ThemeManager.getColorAttr(context, R.attr.zzWarning)

        val unit = if (metric == MetricDimension.DURATION) "h" else "次"
        val divisor = if (metric == MetricDimension.DURATION) 60f else 1f

        if (chartType == ChartType.BAR) {
            barChart.isVisible = true
            lineChart.isVisible = false
            setupCommonChart(barChart, context)
            applyXAxisDecimation(barChart.xAxis, labels)

            val entries = buckets.mapIndexed { index, b ->
                val rest = (if (metric == MetricDimension.DURATION) b.restMinutes else b.restCount.toFloat()) / divisor
                val temp = (if (metric == MetricDimension.DURATION) b.tempUnlockMinutes else b.tempUnlockCount.toFloat()) / divisor
                val priv = (if (metric == MetricDimension.DURATION) b.privilegeMinutes else b.privilegeCount.toFloat()) / divisor
                // 堆叠顺序：底部休息、中间临时解锁、顶部特权
                BarEntry(index.toFloat(), floatArrayOf(rest, temp, priv))
            }

            val dataSet = BarDataSet(entries, "").apply {
                colors = listOf(COLOR_REST, COLOR_TEMP_UNLOCK, COLOR_PRIVILEGE)
                stackLabels = arrayOf("休息", "临时解锁", "特权")
                setDrawValues(false)
            }

            barChart.data = BarData(dataSet).apply {
                barWidth = if (buckets.size > 7) 0.55f else 0.45f
            }

            barChart.axisLeft.apply {
                axisMinimum = 0f
                if (metric == MetricDimension.COUNT) {
                    granularity = 1f
                    isGranularityEnabled = true
                } else {
                    isGranularityEnabled = false
                }
                valueFormatter = object : ValueFormatter() {
                    override fun getFormattedValue(value: Float): String =
                        if (metric == MetricDimension.DURATION) String.format(Locale.getDefault(), "%.1fh", value)
                        else "${value.toInt()}次"
                }
            }
            barChart.invalidate()
        } else {
            barChart.isVisible = false
            lineChart.isVisible = true
            setupCommonChart(lineChart, context)
            applyXAxisDecimation(lineChart.xAxis, labels)

            // 三层累加折线：
            // L1 = 休息
            // L2 = 休息 + 临时解锁
            // L3 = 休息 + 临时解锁 + 特权
            val l1Entries = mutableListOf<Entry>()
            val l2Entries = mutableListOf<Entry>()
            val l3Entries = mutableListOf<Entry>()

            buckets.forEachIndexed { index, b ->
                val r = (if (metric == MetricDimension.DURATION) b.restMinutes else b.restCount.toFloat()) / divisor
                val t = (if (metric == MetricDimension.DURATION) b.tempUnlockMinutes else b.tempUnlockCount.toFloat()) / divisor
                val p = (if (metric == MetricDimension.DURATION) b.privilegeMinutes else b.privilegeCount.toFloat()) / divisor

                val l1 = r
                val l2 = r + t
                val l3 = r + t + p

                l1Entries.add(Entry(index.toFloat(), l1))
                l2Entries.add(Entry(index.toFloat(), l2))
                l3Entries.add(Entry(index.toFloat(), l3))
            }

            fun createLine(entries: List<Entry>, color: Int): LineDataSet {
                return LineDataSet(entries, "").apply {
                    this.color = color
                    lineWidth = 2.2f
                    setDrawCircles(false)
                    setDrawValues(false)
                    mode = LineDataSet.Mode.CUBIC_BEZIER
                }
            }

            val ds1 = createLine(l1Entries, COLOR_REST).apply {
                setDrawFilled(true)
                fillColor = withAlpha(COLOR_REST, 90)
            }
            val ds2 = createLine(l2Entries, COLOR_TEMP_UNLOCK)
            val ds3 = createLine(l3Entries, COLOR_PRIVILEGE)

            lineChart.data = LineData(ds3, ds2, ds1)
            lineChart.axisLeft.apply {
                axisMinimum = 0f
                if (metric == MetricDimension.COUNT) {
                    granularity = 1f
                    isGranularityEnabled = true
                } else {
                    isGranularityEnabled = false
                }
                valueFormatter = object : ValueFormatter() {
                    override fun getFormattedValue(value: Float): String =
                        if (metric == MetricDimension.DURATION) String.format(Locale.getDefault(), "%.1fh", value)
                        else "${value.toInt()}次"
                }
            }
            lineChart.invalidate()
        }
    }

    // ==========================================
    // 2. 应用页：趋势柱状图 / 折线图
    // ==========================================
    fun renderAppTrendChart(
        barChart: BarChart,
        lineChart: LineChart,
        buckets: List<AppUsageBucketData>,
        metric: MetricDimension,
        context: Context
    ) {
        if (buckets.isEmpty()) {
            barChart.isVisible = false
            lineChart.isVisible = false
            return
        }

        val chartType = StatsDateRangeResolver.chooseChartType(buckets.size)
        val labels = buckets.map { it.bucket.label }
        val accentColor = ThemeManager.getColorAttr(context, R.attr.zzAccent)
        val divisor = if (metric == MetricDimension.DURATION) 60f else 1f

        if (chartType == ChartType.BAR) {
            barChart.isVisible = true
            lineChart.isVisible = false
            setupCommonChart(barChart, context)
            applyXAxisDecimation(barChart.xAxis, labels)

            val entries = buckets.mapIndexed { index, b ->
                val v = (if (metric == MetricDimension.COUNT) b.openCount.toFloat() else b.usageMinutes) / divisor
                BarEntry(index.toFloat(), v)
            }

            val dataSet = BarDataSet(entries, "").apply {
                color = accentColor
                setDrawValues(false)
            }

            barChart.data = BarData(dataSet).apply {
                barWidth = if (buckets.size > 7) 0.55f else 0.45f
            }

            barChart.axisLeft.apply {
                axisMinimum = 0f
                if (metric == MetricDimension.COUNT) {
                    granularity = 1f
                    isGranularityEnabled = true
                } else {
                    isGranularityEnabled = false
                }
                valueFormatter = object : ValueFormatter() {
                    override fun getFormattedValue(value: Float): String =
                        if (metric == MetricDimension.DURATION) String.format(Locale.getDefault(), "%.1fh", value)
                        else "${value.toInt()}次"
                }
            }
            barChart.invalidate()
        } else {
            barChart.isVisible = false
            lineChart.isVisible = true
            setupCommonChart(lineChart, context)
            applyXAxisDecimation(lineChart.xAxis, labels)

            val entries = buckets.mapIndexed { index, b ->
                val v = (if (metric == MetricDimension.COUNT) b.openCount.toFloat() else b.usageMinutes) / divisor
                Entry(index.toFloat(), v)
            }

            val dataSet = LineDataSet(entries, "").apply {
                color = accentColor
                lineWidth = 2.4f
                setDrawCircles(false)
                setDrawValues(false)
                mode = LineDataSet.Mode.CUBIC_BEZIER
                setDrawFilled(true)
                fillColor = withAlpha(accentColor, 55)
            }

            lineChart.data = LineData(dataSet)
            lineChart.axisLeft.apply {
                axisMinimum = 0f
                if (metric == MetricDimension.COUNT) {
                    granularity = 1f
                    isGranularityEnabled = true
                } else {
                    isGranularityEnabled = false
                }
                valueFormatter = object : ValueFormatter() {
                    override fun getFormattedValue(value: Float): String =
                        if (metric == MetricDimension.DURATION) String.format(Locale.getDefault(), "%.1fh", value)
                        else "${value.toInt()}次"
                }
            }
            lineChart.invalidate()
        }
    }

    // ==========================================
    // 3. 起床页：时间视图 / 耗时视图
    // ==========================================
    fun renderAlarmChart(
        lineChart: LineChart,
        summary: AlarmStatsSummary,
        viewMode: AlarmStatsView,
        context: Context
    ) {
        val buckets = summary.buckets
        if (buckets.isEmpty()) {
            lineChart.isVisible = false
            return
        }

        lineChart.isVisible = true
        setupCommonChart(lineChart, context)
        val labels = buckets.map { it.bucket.label }
        applyXAxisDecimation(lineChart.xAxis, labels)

        val accentColor = ThemeManager.getColorAttr(context, R.attr.zzAccent)
        val altColor = ThemeManager.getColorAttr(context, R.attr.zzAlt)

        if (viewMode == AlarmStatsView.TIME) {
            val alarmEntries = mutableListOf<Entry>()
            val stageDoneEntries = mutableListOf<Entry>()

            buckets.forEachIndexed { index, b ->
                if (b.alarmMinuteOfDay != null) {
                    alarmEntries.add(Entry(index.toFloat(), b.alarmMinuteOfDay))
                }
                if (b.firstStageCompletedMinute != null) {
                    stageDoneEntries.add(Entry(index.toFloat(), b.firstStageCompletedMinute))
                }
            }

            val ds1 = LineDataSet(alarmEntries, "闹钟时间").apply {
                color = COLOR_ALARM_TIME
                lineWidth = 2.4f
                circleRadius = 3.5f
                setCircleColor(COLOR_ALARM_TIME)
                circleHoleRadius = 2f
                setDrawValues(false)
            }
            val ds2 = LineDataSet(stageDoneEntries, "第一阶段完成时间").apply {
                color = COLOR_ALARM_FINISH
                lineWidth = 2.4f
                circleRadius = 3.5f
                setCircleColor(COLOR_ALARM_FINISH)
                circleHoleRadius = 2f
                setDrawValues(false)
            }

            lineChart.data = LineData(ds1, ds2)
            lineChart.axisLeft.apply {
                val minVal = alarmEntries.minOfOrNull { it.y } ?: 360f
                val maxVal = stageDoneEntries.maxOfOrNull { it.y } ?: 540f
                axisMinimum = (minVal - 30).coerceAtLeast(0f)
                axisMaximum = maxVal + 30
                granularity = 30f
                isGranularityEnabled = true
                valueFormatter = object : ValueFormatter() {
                    override fun getFormattedValue(value: Float): String = formatClockTime(value.toInt())
                }
            }
        } else {
            val stage1Entries = mutableListOf<Entry>()
            val totalEntries = mutableListOf<Entry>()

            buckets.forEachIndexed { index, b ->
                if (b.firstStageDurationMinutes != null) {
                    stage1Entries.add(Entry(index.toFloat(), b.firstStageDurationMinutes))
                }
                if (b.totalFlowDurationMinutes != null) {
                    totalEntries.add(Entry(index.toFloat(), b.totalFlowDurationMinutes))
                }
            }

            val ds1 = LineDataSet(stage1Entries, "第一阶段耗时").apply {
                color = COLOR_ALARM_TIME
                lineWidth = 2.4f
                circleRadius = 3.5f
                setCircleColor(COLOR_ALARM_TIME)
                setDrawValues(false)
            }
            val ds2 = LineDataSet(totalEntries, "完整流程耗时").apply {
                color = COLOR_ALARM_FINISH
                lineWidth = 2.4f
                circleRadius = 3.5f
                setCircleColor(COLOR_ALARM_FINISH)
                setDrawValues(false)
            }

            lineChart.data = LineData(ds1, ds2)
            lineChart.axisLeft.apply {
                axisMinimum = 0f
                granularity = 5f
                isGranularityEnabled = true
                valueFormatter = object : ValueFormatter() {
                    override fun getFormattedValue(value: Float): String = "${value.toInt()}m"
                }
            }
        }
        lineChart.invalidate()
    }

    // ==========================================
    // 4. 规则保护页：状态堆叠柱 / 折线图
    // ==========================================
    fun renderRuleProtectionChart(
        barChart: BarChart,
        lineChart: LineChart,
        summary: RuleProtectionSummary,
        context: Context
    ) {
        val buckets = summary.buckets
        if (buckets.isEmpty()) {
            barChart.isVisible = false
            lineChart.isVisible = false
            return
        }

        val chartType = StatsDateRangeResolver.chooseChartType(buckets.size)
        val labels = buckets.map { it.bucket.label }
        val accentColor = ThemeManager.getColorAttr(context, R.attr.zzAccent)
        val altColor = ThemeManager.getColorAttr(context, R.attr.zzAlt)
        val warningColor = ThemeManager.getColorAttr(context, R.attr.zzWarning)

        if (chartType == ChartType.BAR) {
            barChart.isVisible = true
            lineChart.isVisible = false
            setupCommonChart(barChart, context)
            applyXAxisDecimation(barChart.xAxis, labels)

            val entries = buckets.mapIndexed { index, b ->
                val fav = b.favorableCount.toFloat()
                val rev = b.reversedCount.toFloat()
                val unfav = -b.unfavorableCount.toFloat()
                BarEntry(index.toFloat(), floatArrayOf(fav, rev, unfav))
            }

            val dataSet = BarDataSet(entries, "").apply {
                colors = listOf(COLOR_RULE_FAVORABLE, COLOR_RULE_REVERSED, COLOR_RULE_UNFAVORABLE)
                setDrawValues(false)
            }

            barChart.data = BarData(dataSet).apply {
                barWidth = if (buckets.size > 7) 0.55f else 0.45f
            }

            val maxPos = buckets.maxOfOrNull { it.favorableCount + it.reversedCount }?.coerceAtLeast(1) ?: 1
            val maxNeg = buckets.maxOfOrNull { it.unfavorableCount }?.coerceAtLeast(1) ?: 1
            val maxLimit = maxOf(maxPos, maxNeg) + 1

            barChart.axisLeft.apply {
                resetAxisMinimum()
                resetAxisMaximum()
                axisMinimum = -maxLimit.toFloat()
                axisMaximum = maxLimit.toFloat()
                setDrawZeroLine(true)
                zeroLineWidth = 1.5f
                zeroLineColor = ThemeManager.getColorAttr(context, R.attr.zzText)
                granularity = 1f
                isGranularityEnabled = true
                valueFormatter = object : ValueFormatter() {
                    override fun getFormattedValue(value: Float): String {
                        val v = value.toInt()
                        return if (v > 0) "+$v" else "$v"
                    }
                }
            }
            barChart.invalidate()
        } else {
            barChart.isVisible = false
            lineChart.isVisible = true
            setupCommonChart(lineChart, context)
            applyXAxisDecimation(lineChart.xAxis, labels)

            val favEntries = buckets.mapIndexed { index, b -> Entry(index.toFloat(), b.favorableCount.toFloat()) }
            val unfavEntries = buckets.mapIndexed { index, b -> Entry(index.toFloat(), b.unfavorableCount.toFloat()) }
            val revEntries = buckets.mapIndexed { index, b -> Entry(index.toFloat(), b.reversedCount.toFloat()) }

            fun createLine(entries: List<Entry>, color: Int): LineDataSet {
                return LineDataSet(entries, "").apply {
                    this.color = color
                    lineWidth = 2.2f
                    circleRadius = 2.8f
                    setCircleColor(color)
                    setDrawValues(false)
                }
            }

            lineChart.data = LineData(
                createLine(favEntries, COLOR_RULE_FAVORABLE),
                createLine(unfavEntries, COLOR_RULE_UNFAVORABLE),
                createLine(revEntries, COLOR_RULE_REVERSED)
            )

            lineChart.axisLeft.apply {
                axisMinimum = 0f
                granularity = 1f
                isGranularityEnabled = true
                valueFormatter = object : ValueFormatter() {
                    override fun getFormattedValue(value: Float): String = "${value.toInt()}项"
                }
            }
            lineChart.invalidate()
        }
    }

    /**
     * 规则变动图表别名
     */
    fun renderRuleChangeChart(
        barChart: BarChart,
        lineChart: LineChart,
        summary: RuleProtectionSummary,
        context: Context
    ) = renderRuleProtectionChart(barChart, lineChart, summary, context)
}
