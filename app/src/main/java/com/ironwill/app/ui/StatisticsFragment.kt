/**
 * StatisticsFragment - 统计数据页面 Fragment (完整版)
 * 从 StatisticsActivity 迁移，展示周、月、年三个维度的统计数据
 */
/**
 * =================================================================================
 * [Context Header]
 * * @file: StatisticsFragment.kt
 * * @role: UI
 * * @description: 统计信息展示界面。
 * * @responsibilities:
 * 1. 展示时长与配额统计。
 * 2. 提供统计筛选入口。
 * * @context:
 * - 上游: MainActivity
 * - 下游: CreditManager, TimeRuleManager
 * * @constraints (必读):
 * 1. 禁止承载复杂业务逻辑。
 * 2. UI 更新需主线程安全。
 * 3. 处理空数据。
 * =================================================================================
 */
package com.ironwill.app.ui

import android.graphics.Canvas
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import com.github.mikephil.charting.animation.ChartAnimator
import com.github.mikephil.charting.charts.BarChart
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.components.Legend
import com.github.mikephil.charting.components.LegendEntry
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.*
import com.github.mikephil.charting.formatter.ValueFormatter
import com.github.mikephil.charting.highlight.Highlight
import com.github.mikephil.charting.interfaces.dataprovider.BarDataProvider
import com.github.mikephil.charting.interfaces.datasets.IBarDataSet
import com.github.mikephil.charting.listener.OnChartValueSelectedListener
import com.github.mikephil.charting.renderer.BarChartRenderer
import com.github.mikephil.charting.utils.ViewPortHandler
import com.ironwill.app.R
import com.ironwill.app.manager.CreditManager
import com.ironwill.app.manager.DayStats
import com.ironwill.app.manager.MonthStats
import com.ironwill.app.manager.TimeRuleManager
import java.text.SimpleDateFormat
import java.util.*






class StatisticsFragment : Fragment() {

    private enum class StatsMode { WEEK, MONTH, YEAR }
    private var currentMode = StatsMode.WEEK
    
    // 按钮
    private var btnWeekStats: Button? = null
    private var btnMonthStats: Button? = null
    private var btnYearStats: Button? = null
    
    // 周统计
    private var layoutWeekStats: LinearLayout? = null
    private var btnPrevWeek: Button? = null
    private var btnNextWeek: Button? = null
    private var tvWeekRange: TextView? = null
    private var chartWeek: BarChart? = null
    private var currentWeekOffset = 0
    
    // 月统计
    private var layoutMonthStats: LinearLayout? = null
    private var btnPrevMonth: Button? = null
    private var btnNextMonth: Button? = null
    private var tvMonthRange: TextView? = null
    private var chartMonth: LineChart? = null
    private var currentMonthOffset = 0
    
    // 年统计
    private var layoutYearStats: LinearLayout? = null
    private var btnPrevYear: Button? = null
    private var btnNextYear: Button? = null
    private var tvYearRange: TextView? = null
    private var chartYear: BarChart? = null
    private var currentYearOffset = 0
    
    // 选项
    private var cbWeighted: CheckBox? = null
    private var btnShowConversion: TextView? = null
    private var rgStatsType: RadioGroup? = null
    
    private var isWeighted = false
    private var isRestStats = true

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_statistics, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        initViews(view)
        setupClickEvents()
        switchMode(StatsMode.WEEK)
    }

    private fun initViews(view: View) {
        btnWeekStats = view.findViewById(R.id.btn_week_stats)
        btnMonthStats = view.findViewById(R.id.btn_month_stats)
        btnYearStats = view.findViewById(R.id.btn_year_stats)
        
        cbWeighted = view.findViewById(R.id.cb_weighted_stats)
        btnShowConversion = view.findViewById(R.id.btn_show_conversion)
        rgStatsType = view.findViewById(R.id.rg_stats_type)
        
        // 周统计
        layoutWeekStats = view.findViewById(R.id.layout_week_stats)
        btnPrevWeek = view.findViewById(R.id.btn_prev_week)
        btnNextWeek = view.findViewById(R.id.btn_next_week)
        tvWeekRange = view.findViewById(R.id.tv_week_range)
        chartWeek = view.findViewById(R.id.chart_week)
        
        // 月统计
        layoutMonthStats = view.findViewById(R.id.layout_month_stats)
        btnPrevMonth = view.findViewById(R.id.btn_prev_month)
        btnNextMonth = view.findViewById(R.id.btn_next_month)
        tvMonthRange = view.findViewById(R.id.tv_month_range)
        chartMonth = view.findViewById(R.id.chart_month)
        
        // 年统计
        layoutYearStats = view.findViewById(R.id.layout_year_stats)
        btnPrevYear = view.findViewById(R.id.btn_prev_year)
        btnNextYear = view.findViewById(R.id.btn_next_year)
        tvYearRange = view.findViewById(R.id.tv_year_range)
        chartYear = view.findViewById(R.id.chart_year)
    }

    private fun setupClickEvents() {
        btnWeekStats?.setOnClickListener { switchMode(StatsMode.WEEK) }
        btnMonthStats?.setOnClickListener { switchMode(StatsMode.MONTH) }
        btnYearStats?.setOnClickListener { switchMode(StatsMode.YEAR) }
        
        cbWeighted?.setOnCheckedChangeListener { _, isChecked ->
            isWeighted = isChecked
            chartWeek?.invalidate()
            refreshCurrentStats()
        }
        
        rgStatsType?.setOnCheckedChangeListener { _, checkedId ->
            isRestStats = (checkedId == R.id.rb_stats_rest)
            refreshCurrentStats()
        }
        
        btnShowConversion?.setOnClickListener {
            showConversionTable()
        }
        
        // 周导航
        btnPrevWeek?.setOnClickListener {
            currentWeekOffset--
            updateWeekStats()
        }
        btnNextWeek?.setOnClickListener {
            if (currentWeekOffset < 0) {
                currentWeekOffset++
                updateWeekStats()
            }
        }
        
        // 月导航
        btnPrevMonth?.setOnClickListener {
            currentMonthOffset--
            updateMonthStats()
        }
        btnNextMonth?.setOnClickListener {
            if (currentMonthOffset < 0) {
                currentMonthOffset++
                updateMonthStats()
            }
        }
        
        // 年导航
        btnPrevYear?.setOnClickListener {
            currentYearOffset--
            updateYearStats()
        }
        btnNextYear?.setOnClickListener {
            if (currentYearOffset < 0) {
                currentYearOffset++
                updateYearStats()
            }
        }
    }

    private fun refreshCurrentStats() {
        try {
            when (currentMode) {
                StatsMode.WEEK -> updateWeekStats()
                StatsMode.MONTH -> updateMonthStats()
                StatsMode.YEAR -> updateYearStats()
            }
        } catch (e: Exception) {
            Log.e("StatisticsFragment", "refreshStats failed", e)
            Toast.makeText(requireContext(), "统计数据加载失败: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun switchMode(mode: StatsMode) {
        currentMode = mode
        
        val activeColor = Color.parseColor("#00838F")
        val inactiveColor = Color.parseColor("#757575")
        
        btnWeekStats?.setBackgroundColor(if (mode == StatsMode.WEEK) activeColor else inactiveColor)
        btnMonthStats?.setBackgroundColor(if (mode == StatsMode.MONTH) activeColor else inactiveColor)
        btnYearStats?.setBackgroundColor(if (mode == StatsMode.YEAR) activeColor else inactiveColor)
        
        layoutWeekStats?.visibility = if (mode == StatsMode.WEEK) View.VISIBLE else View.GONE
        layoutMonthStats?.visibility = if (mode == StatsMode.MONTH) View.VISIBLE else View.GONE
        layoutYearStats?.visibility = if (mode == StatsMode.YEAR) View.VISIBLE else View.GONE
        
        when (mode) {
            StatsMode.WEEK -> updateWeekStats()
            StatsMode.MONTH -> updateMonthStats()
            StatsMode.YEAR -> updateYearStats()
        }
    }

    private fun updateWeekStats() {
        val ctx = context ?: return
        val calendar = Calendar.getInstance()
        calendar.firstDayOfWeek = Calendar.MONDAY
        calendar.add(Calendar.WEEK_OF_YEAR, currentWeekOffset)
        calendar.set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
        
        val startOfWeek = calendar.clone() as Calendar
        val endOfWeek = calendar.clone() as Calendar
        endOfWeek.add(Calendar.DAY_OF_MONTH, 6)
        
        val dateFormat = SimpleDateFormat("MM/dd", Locale.getDefault())
        val weekLabel = when (currentWeekOffset) {
            0 -> "本周"
            -1 -> "上周"
            else -> "${dateFormat.format(startOfWeek.time)} - ${dateFormat.format(endOfWeek.time)}"
        }
        tvWeekRange?.text = weekLabel
        
        val weekData = CreditManager.getWeekStats(ctx, startOfWeek)
        setupWeekChart(weekData)
    }

    private fun setupWeekChart(weekData: List<DayStats>) {
        val ctx = context ?: return
        val chart = chartWeek ?: return
        
        chart.clear()
        chart.data = null
        
        try {
            if (weekData.isEmpty()) {
                chart.setNoDataText("暂无统计数据")
                chart.invalidate()
                return
            }
            
            val segments = TimeRuleManager.getTimeSegments(ctx)
            if (segments.isEmpty()) {
                chart.setNoDataText("暂无时段配置，请先添加勿扰时段")
                chart.invalidate()
                return
            }
            
            val sortedSegments = segments.sortedBy { it.startHour * 60 + it.startMinute }
            val segmentNames = sortedSegments.map { it.name }.toMutableList()
            val segmentKeys = sortedSegments.map { seg ->
                val nameKey = seg.name.replace(" ", "").lowercase()
                if (nameKey.isNotEmpty()) nameKey else "seg_${seg.startHour}_${seg.startMinute}_${seg.endHour}_${seg.endMinute}"
            }.toMutableList()
            // 🚀 [移除临时解锁独立统计] 临时解锁时间已计入各时段额度，不再单独显示

            val colors = listOf(
                Color.parseColor("#4CAF50"),
                Color.parseColor("#2196F3"),
                Color.parseColor("#9C27B0"),
                Color.parseColor("#FF9800"),
                Color.parseColor("#F44336"),
                Color.parseColor("#00BCD4"),
                Color.parseColor("#FFEB3B"),
                Color.parseColor("#607D8B")
            ).take(segmentKeys.size)

            if (segmentKeys.isEmpty()) {
                chart.setNoDataText("暂无可用时段")
                chart.invalidate()
                return
            }
            
            val entries = mutableListOf<BarEntry>()
            weekData.forEachIndexed { index, day ->
                val values = segmentKeys.mapIndexed { i, key ->
                    val seg = sortedSegments[i]
                    if (!seg.isEnabled) return@mapIndexed 0f

                    val usageMins = (day.segmentMinutes[key] ?: 0).toFloat()
                    val capacityMins = TimeRuleManager.calculateDuration(seg).toFloat()

                    if (isRestStats) {
                        usageMins / 60f
                    } else {
                        if (usageMins <= 0.001f) 0f
                        else ((capacityMins - usageMins).coerceAtLeast(0f)) / 60f
                    }
                }.toFloatArray()
                
                if (values.isNotEmpty()) {
                    entries.add(BarEntry(index.toFloat(), values))
                }
            }
            
            if (entries.isEmpty()) {
                chart.setNoDataText("暂无统计数据")
                chart.invalidate()
                return
            }
            
            val hasNonZeroData = entries.any { entry ->
                entry.yVals?.any { it > 0.001f } == true
            }
            
            if (!hasNonZeroData) {
                chart.setNoDataText(if (isRestStats) "本周暂无休息时长记录" else "本周暂无自律时长记录")
                chart.invalidate()
                return
            }
            
            val dataSet = BarDataSet(entries, "").apply {
                this.colors = colors
                setDrawValues(true)
                valueTextSize = 12f
                valueTextColor = Color.BLACK
                valueFormatter = object : ValueFormatter() {
                    override fun getFormattedValue(value: Float): String {
                        if (value < 0.1f) return ""
                        return String.format("%.1fh", value)
                    }
                }
            }
            
            val barData = BarData(dataSet)
            barData.barWidth = 0.7f
            
            chart.apply {
                if (renderer !is WeekBarChartRenderer) {
                    renderer = WeekBarChartRenderer(this, animator, viewPortHandler)
                }
                
                description.isEnabled = false
                setTouchEnabled(true)
                isDragEnabled = true
                setScaleEnabled(false)
                
                setOnChartValueSelectedListener(object : OnChartValueSelectedListener {
                    override fun onValueSelected(e: Entry?, h: Highlight?) {
                        if (e == null || h == null) return
                        val barEntry = e as BarEntry
                        val stackIndex = h.stackIndex
                        
                        if (stackIndex >= 0 && stackIndex < (barEntry.yVals?.size ?: 0)) {
                            val value = barEntry.yVals!![stackIndex]
                            val minutes = (value * 60).toInt()
                            val segmentName = if (stackIndex < segmentNames.size) segmentNames[stackIndex] else "Unknown"
                            val type = if (isRestStats) "休息" else "自律"
                            Toast.makeText(ctx, "$segmentName ($type): ${minutes}m", Toast.LENGTH_SHORT).show()
                        }
                    }
                    override fun onNothingSelected() {}
                })
                
                legend.apply {
                    isEnabled = true
                    textSize = 11f
                    verticalAlignment = Legend.LegendVerticalAlignment.TOP
                    horizontalAlignment = Legend.LegendHorizontalAlignment.CENTER
                    orientation = Legend.LegendOrientation.HORIZONTAL
                    isWordWrapEnabled = true
                    xEntrySpace = 8f
                    yEntrySpace = 4f
                    formSize = 10f
                    
                    setCustom(segmentNames.mapIndexed { idx, name ->
                        LegendEntry(name, Legend.LegendForm.SQUARE, Float.NaN, Float.NaN, null, colors[idx])
                    })
                }
                
                data = barData // 设置数据放在 Legend.setCustom 之后，确保能够正确计算图例尺寸
                setExtraOffsets(5f, 20f, 5f, 10f)
                minOffset = 0f
                
                xAxis.apply {
                    position = XAxis.XAxisPosition.BOTTOM
                    granularity = 1f
                    axisMinimum = -0.5f
                    axisMaximum = 6.5f
                    setDrawGridLines(false)
                    axisLineWidth = 2f
                    valueFormatter = object : ValueFormatter() {
                        override fun getFormattedValue(value: Float): String {
                            val days = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")
                            return days.getOrNull(value.toInt()) ?: ""
                        }
                    }
                }
                
                axisLeft.apply {
                    axisMinimum = 0f
                    setLabelCount(5, true)
                    setDrawGridLines(true)
                    axisLineWidth = 2f
                    gridColor = Color.parseColor("#E0E0E0")
                    valueFormatter = object : ValueFormatter() {
                        override fun getFormattedValue(value: Float) = "${String.format("%.1f", value)}h"
                    }
                }
                axisRight.isEnabled = false
                
                isDoubleTapToZoomEnabled = false
                setPinchZoom(false)
                animateY(500)
                invalidate()
            }
        } catch (e: Exception) {
            Log.e("StatisticsFragment", "updateWeekStats chart failed", e)
            chart.setNoDataText("图表加载失败")
            chart.invalidate()
        }
    }

    private fun updateMonthStats() {
        val ctx = context ?: return
        val calendar = Calendar.getInstance()
        calendar.add(Calendar.MONTH, currentMonthOffset)
        
        val monthFormat = SimpleDateFormat("yyyy年MM月", Locale.getDefault())
        tvMonthRange?.text = if (currentMonthOffset == 0) "本月" else monthFormat.format(calendar.time)
        
        val monthData = CreditManager.getMonthStats(ctx, calendar)
        setupMonthChart(monthData)
    }

    private fun setupMonthChart(monthData: List<DayStats>) {
        val ctx = context ?: return
        val chart = chartMonth ?: return
        val dateFormat = SimpleDateFormat("yyyyMMdd", Locale.getDefault())
        val entries = mutableListOf<Entry>()
        
        chart.setOnChartValueSelectedListener(object : OnChartValueSelectedListener {
            override fun onValueSelected(e: Entry?, h: Highlight?) {
                if (e == null) return
                val value = e.y
                val minutes = (value * 60).toInt()
                val label = if (isRestStats) "休息" else "自律"
                Toast.makeText(ctx, "$label: ${minutes}m (${String.format("%.1f", value)}h)", Toast.LENGTH_SHORT).show()
            }
            override fun onNothingSelected() {}
        })
        
        val segments = TimeRuleManager.getTimeSegments(ctx)
        
        monthData.forEachIndexed { index, day ->
            val calendar = Calendar.getInstance()
            calendar.time = dateFormat.parse(day.date) ?: Date()
            
            val dailyCapacity = TimeRuleManager.getDailyCapacity(ctx, calendar, segments).toFloat()
            val usageMins = (day.segmentMinutes["total"] ?: 0).toFloat()
            
            val value = if (isRestStats) {
                usageMins / 60f
            } else {
                if (usageMins <= 0.001f) 0f
                else ((dailyCapacity - usageMins).coerceAtLeast(0f)) / 60f
            }
            entries.add(Entry(index.toFloat(), value))
        }
        
        val baseLabel = if (isRestStats) "休息时长" else "自律时长"
        val label = if (isWeighted) "$baseLabel(加权) h" else "$baseLabel h"
        val dataSet = LineDataSet(entries, label).apply {
            color = Color.parseColor("#00838F")
            lineWidth = 2.5f
            setCircleColor(Color.parseColor("#00838F"))
            circleRadius = 4f
            circleHoleRadius = 2f
            setDrawCircleHole(true)
            setDrawValues(false)
            mode = LineDataSet.Mode.HORIZONTAL_BEZIER
            // cubicIntensity = 0.2f // Not used for Horizontal Bezier
            setDrawFilled(true)
            fillColor = Color.parseColor("#8000838F")
        }
        
        chart.apply {
            data = LineData(dataSet)
            description.isEnabled = false
            legend.isEnabled = true
            setExtraOffsets(10f, 10f, 10f, 20f)
            
            xAxis.apply {
                position = XAxis.XAxisPosition.BOTTOM
                granularity = 1f
                setDrawGridLines(false)
                axisLineWidth = 2f
                valueFormatter = object : ValueFormatter() {
                    override fun getFormattedValue(value: Float): String {
                        val day = value.toInt() + 1
                        return if (day == 1 || day % 5 == 0) "${day}日" else ""
                    }
                }
            }
            
            axisLeft.apply {
                axisMinimum = 0f
                setLabelCount(5, true)
                axisLineWidth = 2f
                setDrawGridLines(true)
                gridColor = Color.parseColor("#E0E0E0")
                valueFormatter = object : ValueFormatter() {
                    override fun getFormattedValue(value: Float) = "${String.format("%.1f", value)}h"
                }
            }
            axisRight.isEnabled = false
            
            setTouchEnabled(true)
            isDragEnabled = true
            setScaleEnabled(false)
            animateX(500)
            invalidate()
        }
    }

    private fun updateYearStats() {
        val ctx = context ?: return
        val calendar = Calendar.getInstance()
        calendar.add(Calendar.YEAR, currentYearOffset)
        
        tvYearRange?.text = if (currentYearOffset == 0) "本年" else "${calendar.get(Calendar.YEAR)}年"
        
        val yearData = CreditManager.getYearStats(ctx, calendar)
        setupYearChart(yearData)
    }

    private fun setupYearChart(yearData: List<MonthStats>) {
        val ctx = context ?: return
        val chart = chartYear ?: return
        val entries = mutableListOf<BarEntry>()
        
        chart.setOnChartValueSelectedListener(object : OnChartValueSelectedListener {
            override fun onValueSelected(e: Entry?, h: Highlight?) {
                if (e == null) return
                val value = e.y
                val minutes = (value * 60).toInt()
                val label = if (isRestStats) "休息" else "自律"
                Toast.makeText(ctx, "$label: ${minutes}m (${String.format("%.1f", value)}h)", Toast.LENGTH_SHORT).show()
            }
            override fun onNothingSelected() {}
        })
        
        yearData.forEachIndexed { index, monthStats ->
            val usageMins = monthStats.totalMinutes.toFloat()
            val capacityMins = monthStats.capacityMinutes.toFloat()
            
            val value = if (isRestStats) {
                usageMins / 60f
            } else {
                if (usageMins <= 0.001f) 0f
                else ((capacityMins - usageMins).coerceAtLeast(0f)) / 60f
            }
            entries.add(BarEntry(index.toFloat(), value))
        }
        
        val baseLabel = if (isRestStats) "休息时长" else "自律时长"
        val label = if (isWeighted) "$baseLabel(加权) h" else "$baseLabel h"
        val dataSet = BarDataSet(entries, label).apply {
            color = Color.parseColor("#00838F")
            setDrawValues(false)
            valueTextSize = 10f
        }
        
        chart.apply {
            data = BarData(dataSet)
            description.isEnabled = false
            legend.isEnabled = true
            setExtraOffsets(10f, 10f, 10f, 20f)
            
            xAxis.apply {
                position = XAxis.XAxisPosition.BOTTOM
                granularity = 1f
                setDrawGridLines(false)
                axisLineWidth = 2f
                valueFormatter = object : ValueFormatter() {
                    override fun getFormattedValue(value: Float) = "${value.toInt() + 1}月"
                }
            }
            
            axisLeft.apply {
                axisMinimum = 0f
                setLabelCount(5, true)
                axisLineWidth = 2f
                setDrawGridLines(true)
                gridColor = Color.parseColor("#E0E0E0")
                valueFormatter = object : ValueFormatter() {
                    override fun getFormattedValue(value: Float) = "${String.format("%.1f", value)}h"
                }
            }
            axisRight.isEnabled = false
            
            animateY(500)
            invalidate()
        }
    }

    private fun showConversionTable() {
        val ctx = context ?: return
        val segments = TimeRuleManager.getTimeSegments(ctx)
        if (segments.isEmpty()) return

        val message = StringBuilder("📊 换算规则说明\n\n")
        message.append("✨ 提前完成奖励 (×0.5)\n")
        message.append("━━━━━━━━━━━━━━━━━━━━\n")
        message.append("当您在【任意管控时段】即将结束时申请休息，\n")
        message.append("且休息一直持续到【时段结束之后】时，\n")
        message.append("该次休息在时段内的时长按 0.5 倍计费。\n\n")
        message.append("━━━━━━━━━━━━━━━━━━━━\n")
        message.append("📋 当前时段列表:\n\n")
        
        segments.forEachIndexed { index, seg ->
            message.append("${index + 1}. ${seg.name} (${seg.formatTimeRange()})\n")
        }

        AlertDialog.Builder(ctx)
            .setTitle("⚖️ 数据换算表")
            .setMessage(message.toString())
            .setPositiveButton("知道了", null)
            .show()
    }

    // 自定义渲染器：仅在堆叠柱状图顶部显示总计
    inner class WeekBarChartRenderer(chart: BarDataProvider?, animator: ChartAnimator?, viewPortHandler: ViewPortHandler?) :
        BarChartRenderer(chart, animator, viewPortHandler) {
        
        private val vph = viewPortHandler
        private val anim = animator

        override fun drawValues(c: Canvas) {
            val data = mChart.barData
            val dataSets = data.dataSets
            if (dataSets == null || vph == null || anim == null) return

            val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
            paint.textAlign = android.graphics.Paint.Align.CENTER

            for (i in dataSets.indices) {
                val dataSet = dataSets[i] as IBarDataSet
                if (!dataSet.isDrawValuesEnabled || dataSet.entryCount == 0) continue

                paint.typeface = dataSet.valueTypeface
                paint.textSize = dataSet.valueTextSize
                
                val formatter = dataSet.valueFormatter
                val trans = mChart.getTransformer(dataSet.axisDependency)
                
                val count = dataSet.entryCount
                val positions = FloatArray(count * 2)

                for (j in 0 until count) {
                    val e = dataSet.getEntryForIndex(j) as BarEntry
                    positions[j * 2] = e.x
                    positions[j * 2 + 1] = e.y * anim.phaseY
                }

                trans.pointValuesToPixel(positions)

                for (j in 0 until count) {
                    val x = positions[j * 2]
                    val y = positions[j * 2 + 1]

                    if (!vph.isInBoundsRight(x)) break
                    if (!vph.isInBoundsLeft(x) || !vph.isInBoundsY(y)) continue

                    val entry = dataSet.getEntryForIndex(j) as BarEntry
                    val valVal = entry.y
                    
                    if (valVal < 0.1f) continue
                    
                    val text = formatter.getFormattedValue(valVal)
                    paint.color = dataSet.getValueTextColor(j)
                    c.drawText(text, x, y - 10f, paint)
                }
            }
        }
    }
}
