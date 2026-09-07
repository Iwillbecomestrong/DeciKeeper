package com.zhizhi.app.ui

import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.github.mikephil.charting.charts.BarChart
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.components.Legend
import com.github.mikephil.charting.components.LegendEntry
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.BarData
import com.github.mikephil.charting.data.BarDataSet
import com.github.mikephil.charting.data.BarEntry
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.github.mikephil.charting.formatter.ValueFormatter
import com.github.mikephil.charting.highlight.Highlight
import com.github.mikephil.charting.listener.OnChartValueSelectedListener
import com.zhizhi.app.R
import com.zhizhi.app.manager.CreditManager
import com.zhizhi.app.manager.DayStats
import com.zhizhi.app.manager.MonthStats
import com.zhizhi.app.manager.TimeRuleManager
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * 周 / 月 / 年统计。视觉只使用当前四季主题提供的低饱和色，不再使用旧版高饱和彩虹色。
 * “加权”和“换算表”保留兼容 View ID，但产品入口已经隐藏。
 */
class StatisticsFragment : Fragment() {

    private enum class StatsMode { WEEK, MONTH, YEAR }

    private var currentMode = StatsMode.WEEK
    private var btnWeekStats: Button? = null
    private var btnMonthStats: Button? = null
    private var btnYearStats: Button? = null
    private var layoutWeekStats: LinearLayout? = null
    private var layoutMonthStats: LinearLayout? = null
    private var layoutYearStats: LinearLayout? = null
    private var btnPrevWeek: Button? = null
    private var btnNextWeek: Button? = null
    private var btnPrevMonth: Button? = null
    private var btnNextMonth: Button? = null
    private var btnPrevYear: Button? = null
    private var btnNextYear: Button? = null
    private var tvWeekRange: TextView? = null
    private var tvMonthRange: TextView? = null
    private var tvYearRange: TextView? = null
    private var chartWeek: BarChart? = null
    private var chartMonth: LineChart? = null
    private var chartYear: BarChart? = null
    private var cbWeighted: CheckBox? = null
    private var btnShowConversion: TextView? = null
    private var rgStatsType: RadioGroup? = null

    private var currentWeekOffset = 0
    private var currentMonthOffset = 0
    private var currentYearOffset = 0
    private var isRestStats = true

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.fragment_statistics, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        initViews(view)
        setupClickEvents()
        switchMode(StatsMode.WEEK)
    }

    override fun onResume() {
        super.onResume()
        refreshCurrentStats()
    }

    private fun initViews(view: View) {
        btnWeekStats = view.findViewById(R.id.btn_week_stats)
        btnMonthStats = view.findViewById(R.id.btn_month_stats)
        btnYearStats = view.findViewById(R.id.btn_year_stats)
        cbWeighted = view.findViewById(R.id.cb_weighted_stats)
        btnShowConversion = view.findViewById(R.id.btn_show_conversion)
        rgStatsType = view.findViewById(R.id.rg_stats_type)

        layoutWeekStats = view.findViewById(R.id.layout_week_stats)
        btnPrevWeek = view.findViewById(R.id.btn_prev_week)
        btnNextWeek = view.findViewById(R.id.btn_next_week)
        tvWeekRange = view.findViewById(R.id.tv_week_range)
        chartWeek = view.findViewById(R.id.chart_week)

        layoutMonthStats = view.findViewById(R.id.layout_month_stats)
        btnPrevMonth = view.findViewById(R.id.btn_prev_month)
        btnNextMonth = view.findViewById(R.id.btn_next_month)
        tvMonthRange = view.findViewById(R.id.tv_month_range)
        chartMonth = view.findViewById(R.id.chart_month)

        layoutYearStats = view.findViewById(R.id.layout_year_stats)
        btnPrevYear = view.findViewById(R.id.btn_prev_year)
        btnNextYear = view.findViewById(R.id.btn_next_year)
        tvYearRange = view.findViewById(R.id.tv_year_range)
        chartYear = view.findViewById(R.id.chart_year)

        // 两项已从产品 UI 移除，仅保留旧 ID 兼容。
        cbWeighted?.isChecked = false
        cbWeighted?.visibility = View.GONE
        btnShowConversion?.visibility = View.GONE
    }

    private fun setupClickEvents() {
        btnWeekStats?.setOnClickListener { switchMode(StatsMode.WEEK) }
        btnMonthStats?.setOnClickListener { switchMode(StatsMode.MONTH) }
        btnYearStats?.setOnClickListener { switchMode(StatsMode.YEAR) }

        rgStatsType?.setOnCheckedChangeListener { _, checkedId ->
            isRestStats = checkedId == R.id.rb_stats_rest
            refreshCurrentStats()
        }

        btnPrevWeek?.setOnClickListener { currentWeekOffset--; updateWeekStats() }
        btnNextWeek?.setOnClickListener {
            if (currentWeekOffset < 0) { currentWeekOffset++; updateWeekStats() }
        }
        btnPrevMonth?.setOnClickListener { currentMonthOffset--; updateMonthStats() }
        btnNextMonth?.setOnClickListener {
            if (currentMonthOffset < 0) { currentMonthOffset++; updateMonthStats() }
        }
        btnPrevYear?.setOnClickListener { currentYearOffset--; updateYearStats() }
        btnNextYear?.setOnClickListener {
            if (currentYearOffset < 0) { currentYearOffset++; updateYearStats() }
        }
    }

    private fun themeColor(attr: Int): Int {
        val value = TypedValue()
        requireContext().theme.resolveAttribute(attr, value, true)
        return value.data
    }

    private fun withAlpha(color: Int, alpha: Int): Int =
        Color.argb(alpha.coerceIn(0, 255), Color.red(color), Color.green(color), Color.blue(color))

    private fun switchMode(mode: StatsMode) {
        currentMode = mode
        val active = themeColor(R.attr.zzAccent)
        val inactive = themeColor(R.attr.zzSurface2)
        val activeText = Color.WHITE
        val inactiveText = themeColor(R.attr.zzText)

        listOf(
            btnWeekStats to (mode == StatsMode.WEEK),
            btnMonthStats to (mode == StatsMode.MONTH),
            btnYearStats to (mode == StatsMode.YEAR)
        ).forEach { (button, selected) ->
            button?.setBackgroundColor(if (selected) active else inactive)
            button?.setTextColor(if (selected) activeText else inactiveText)
        }

        layoutWeekStats?.visibility = if (mode == StatsMode.WEEK) View.VISIBLE else View.GONE
        layoutMonthStats?.visibility = if (mode == StatsMode.MONTH) View.VISIBLE else View.GONE
        layoutYearStats?.visibility = if (mode == StatsMode.YEAR) View.VISIBLE else View.GONE
        refreshCurrentStats()
    }

    private fun refreshCurrentStats() {
        if (!isAdded) return
        try {
            when (currentMode) {
                StatsMode.WEEK -> updateWeekStats()
                StatsMode.MONTH -> updateMonthStats()
                StatsMode.YEAR -> updateYearStats()
            }
        } catch (e: Exception) {
            Log.e("StatisticsFragment", "refreshStats failed", e)
            context?.let { Toast.makeText(it, "统计数据加载失败", Toast.LENGTH_SHORT).show() }
        }
    }

    private fun updateWeekStats() {
        val ctx = context ?: return
        val calendar = Calendar.getInstance().apply {
            firstDayOfWeek = Calendar.MONDAY
            add(Calendar.WEEK_OF_YEAR, currentWeekOffset)
            set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
        }
        val start = calendar.clone() as Calendar
        val end = calendar.clone() as Calendar
        end.add(Calendar.DAY_OF_MONTH, 6)
        val fmt = SimpleDateFormat("MM/dd", Locale.getDefault())
        tvWeekRange?.text = when (currentWeekOffset) {
            0 -> "本周"
            -1 -> "上周"
            else -> "${fmt.format(start.time)} – ${fmt.format(end.time)}"
        }
        setupWeekChart(CreditManager.getWeekStats(ctx, start))
    }

    private fun setupWeekChart(weekData: List<DayStats>) {
        val ctx = context ?: return
        val chart = chartWeek ?: return
        chart.clear()
        if (weekData.isEmpty()) {
            chart.setNoDataText("暂无统计数据")
            chart.invalidate()
            return
        }

        val segments = TimeRuleManager.getTimeSegments(ctx).sortedBy { it.startHour * 60 + it.startMinute }
        if (segments.isEmpty()) {
            chart.setNoDataText("暂无勿扰时段配置")
            chart.invalidate()
            return
        }
        val keys = segments.map { seg ->
            seg.name.replace(" ", "").lowercase().ifBlank {
                "seg_${seg.startHour}_${seg.startMinute}_${seg.endHour}_${seg.endMinute}"
            }
        }
        val palette = listOf(
            themeColor(R.attr.zzAccent),
            themeColor(R.attr.zzAlt),
            themeColor(R.attr.zzInfo),
            themeColor(R.attr.zzSuccess),
            themeColor(R.attr.zzWarning),
            themeColor(R.attr.zzMuted)
        )
        val colors = keys.indices.map { palette[it % palette.size] }

        val entries = weekData.mapIndexed { index, day ->
            val values = keys.mapIndexed { i, key ->
                val seg = segments[i]
                if (!seg.isEnabled) 0f else {
                    val used = (day.segmentMinutes[key] ?: 0).toFloat()
                    val capacity = TimeRuleManager.calculateDuration(seg).toFloat()
                    if (isRestStats) used / 60f
                    else if (used <= 0.001f) 0f else (capacity - used).coerceAtLeast(0f) / 60f
                }
            }.toFloatArray()
            BarEntry(index.toFloat(), values)
        }
        val hasData = entries.any { it.yVals?.any { v -> v > 0.001f } == true }
        if (!hasData) {
            chart.setNoDataText(if (isRestStats) "本周暂无休息时长记录" else "本周暂无勿扰时长记录")
            chart.invalidate()
            return
        }

        val dataSet = BarDataSet(entries, "").apply {
            this.colors = colors
            setDrawValues(true)
            valueTextSize = 10f
            valueTextColor = themeColor(R.attr.zzText)
            valueFormatter = object : ValueFormatter() {
                override fun getFormattedValue(value: Float): String =
                    if (value < 0.1f) "" else String.format(Locale.getDefault(), "%.1fh", value)
            }
        }
        chart.data = BarData(dataSet).apply { barWidth = 0.7f }
        chart.description.isEnabled = false
        chart.axisRight.isEnabled = false
        chart.setScaleEnabled(false)
        chart.isDoubleTapToZoomEnabled = false
        chart.legend.apply {
            isEnabled = true
            textColor = themeColor(R.attr.zzMuted)
            textSize = 10f
            verticalAlignment = Legend.LegendVerticalAlignment.TOP
            horizontalAlignment = Legend.LegendHorizontalAlignment.CENTER
            orientation = Legend.LegendOrientation.HORIZONTAL
            isWordWrapEnabled = true
            setCustom(segments.mapIndexed { i, seg ->
                LegendEntry(seg.name, Legend.LegendForm.SQUARE, Float.NaN, Float.NaN, null, colors[i])
            })
        }
        chart.xAxis.apply {
            position = XAxis.XAxisPosition.BOTTOM
            granularity = 1f
            axisMinimum = -0.5f
            axisMaximum = 6.5f
            setDrawGridLines(false)
            textColor = themeColor(R.attr.zzMuted)
            axisLineColor = themeColor(R.attr.zzLine)
            valueFormatter = object : ValueFormatter() {
                override fun getFormattedValue(value: Float): String =
                    listOf("一", "二", "三", "四", "五", "六", "日").getOrNull(value.toInt()) ?: ""
            }
        }
        chart.axisLeft.apply {
            axisMinimum = 0f
            setLabelCount(5, true)
            textColor = themeColor(R.attr.zzMuted)
            axisLineColor = themeColor(R.attr.zzLine)
            gridColor = themeColor(R.attr.zzLine)
            valueFormatter = object : ValueFormatter() {
                override fun getFormattedValue(value: Float) = String.format(Locale.getDefault(), "%.1fh", value)
            }
        }
        chart.setOnChartValueSelectedListener(object : OnChartValueSelectedListener {
            override fun onValueSelected(e: Entry?, h: Highlight?) {
                val bar = e as? BarEntry ?: return
                val stack = h?.stackIndex ?: return
                val value = bar.yVals?.getOrNull(stack) ?: return
                val label = segments.getOrNull(stack)?.name ?: return
                Toast.makeText(ctx, "$label · ${if (isRestStats) "休息" else "勿扰"} ${(value * 60).toInt()}min", Toast.LENGTH_SHORT).show()
            }
            override fun onNothingSelected() = Unit
        })
        chart.setExtraOffsets(4f, 12f, 4f, 8f)
        chart.animateY(250)
        chart.invalidate()
    }

    private fun updateMonthStats() {
        val ctx = context ?: return
        val calendar = Calendar.getInstance().apply { add(Calendar.MONTH, currentMonthOffset) }
        tvMonthRange?.text = if (currentMonthOffset == 0) "本月"
        else SimpleDateFormat("yyyy年MM月", Locale.getDefault()).format(calendar.time)
        setupMonthChart(CreditManager.getMonthStats(ctx, calendar))
    }

    private fun setupMonthChart(monthData: List<DayStats>) {
        val ctx = context ?: return
        val chart = chartMonth ?: return
        val dateFormat = SimpleDateFormat("yyyyMMdd", Locale.getDefault())
        val segments = TimeRuleManager.getTimeSegments(ctx)
        val entries = monthData.mapIndexed { index, day ->
            val calendar = Calendar.getInstance().apply { time = dateFormat.parse(day.date) ?: Date() }
            val capacity = TimeRuleManager.getDailyCapacity(ctx, calendar, segments).toFloat()
            val used = (day.segmentMinutes["total"] ?: 0).toFloat()
            val value = if (isRestStats) used / 60f
            else if (used <= 0.001f) 0f else (capacity - used).coerceAtLeast(0f) / 60f
            Entry(index.toFloat(), value)
        }
        val accent = themeColor(R.attr.zzAccent)
        val dataSet = LineDataSet(entries, if (isRestStats) "休息时长" else "勿扰时长").apply {
            color = accent
            lineWidth = 2.2f
            setCircleColor(accent)
            circleRadius = 3.5f
            circleHoleRadius = 1.8f
            setDrawValues(false)
            mode = LineDataSet.Mode.HORIZONTAL_BEZIER
            setDrawFilled(true)
            fillColor = withAlpha(accent, 42)
        }
        chart.data = LineData(dataSet)
        styleLineChart(chart)
        chart.setOnChartValueSelectedListener(simpleSelectionListener(ctx))
        chart.animateX(250)
        chart.invalidate()
    }

    private fun updateYearStats() {
        val ctx = context ?: return
        val calendar = Calendar.getInstance().apply { add(Calendar.YEAR, currentYearOffset) }
        tvYearRange?.text = if (currentYearOffset == 0) "本年" else "${calendar.get(Calendar.YEAR)}年"
        setupYearChart(CreditManager.getYearStats(ctx, calendar))
    }

    private fun setupYearChart(yearData: List<MonthStats>) {
        val ctx = context ?: return
        val chart = chartYear ?: return
        val entries = yearData.mapIndexed { index, month ->
            val used = month.totalMinutes.toFloat()
            val capacity = month.capacityMinutes.toFloat()
            val value = if (isRestStats) used / 60f
            else if (used <= 0.001f) 0f else (capacity - used).coerceAtLeast(0f) / 60f
            BarEntry(index.toFloat(), value)
        }
        val dataSet = BarDataSet(entries, if (isRestStats) "休息时长" else "勿扰时长").apply {
            color = themeColor(R.attr.zzAccent)
            setDrawValues(false)
        }
        chart.data = BarData(dataSet).apply { barWidth = 0.62f }
        chart.description.isEnabled = false
        chart.axisRight.isEnabled = false
        chart.legend.textColor = themeColor(R.attr.zzMuted)
        chart.xAxis.apply {
            position = XAxis.XAxisPosition.BOTTOM
            granularity = 1f
            setDrawGridLines(false)
            textColor = themeColor(R.attr.zzMuted)
            axisLineColor = themeColor(R.attr.zzLine)
            valueFormatter = object : ValueFormatter() {
                override fun getFormattedValue(value: Float) = "${value.toInt() + 1}"
            }
        }
        chart.axisLeft.apply {
            axisMinimum = 0f
            setLabelCount(5, true)
            textColor = themeColor(R.attr.zzMuted)
            axisLineColor = themeColor(R.attr.zzLine)
            gridColor = themeColor(R.attr.zzLine)
            valueFormatter = object : ValueFormatter() {
                override fun getFormattedValue(value: Float) = String.format(Locale.getDefault(), "%.0fh", value)
            }
        }
        chart.setScaleEnabled(false)
        chart.setOnChartValueSelectedListener(simpleSelectionListener(ctx))
        chart.animateY(250)
        chart.invalidate()
    }

    private fun styleLineChart(chart: LineChart) {
        chart.description.isEnabled = false
        chart.axisRight.isEnabled = false
        chart.legend.textColor = themeColor(R.attr.zzMuted)
        chart.setScaleEnabled(false)
        chart.xAxis.apply {
            position = XAxis.XAxisPosition.BOTTOM
            granularity = 1f
            setDrawGridLines(false)
            textColor = themeColor(R.attr.zzMuted)
            axisLineColor = themeColor(R.attr.zzLine)
            valueFormatter = object : ValueFormatter() {
                override fun getFormattedValue(value: Float): String {
                    val day = value.toInt() + 1
                    return if (day == 1 || day % 5 == 0) day.toString() else ""
                }
            }
        }
        chart.axisLeft.apply {
            axisMinimum = 0f
            setLabelCount(5, true)
            textColor = themeColor(R.attr.zzMuted)
            axisLineColor = themeColor(R.attr.zzLine)
            gridColor = themeColor(R.attr.zzLine)
            valueFormatter = object : ValueFormatter() {
                override fun getFormattedValue(value: Float) = String.format(Locale.getDefault(), "%.1fh", value)
            }
        }
    }

    private fun simpleSelectionListener(ctx: android.content.Context) = object : OnChartValueSelectedListener {
        override fun onValueSelected(e: Entry?, h: Highlight?) {
            val value = e?.y ?: return
            Toast.makeText(
                ctx,
                "${if (isRestStats) "休息" else "勿扰"} ${(value * 60).toInt()}min",
                Toast.LENGTH_SHORT
            ).show()
        }
        override fun onNothingSelected() = Unit
    }
}
