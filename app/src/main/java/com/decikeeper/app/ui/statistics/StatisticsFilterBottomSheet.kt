package com.decikeeper.app.ui.statistics

import android.app.DatePickerDialog
import android.content.Context
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.core.widget.addTextChangedListener
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.decikeeper.app.R
import com.decikeeper.app.data.statistics.StatsDateRange
import com.decikeeper.app.data.statistics.WeekdayFilter
import com.decikeeper.app.manager.statistics.StatisticsRepository
import com.decikeeper.app.ui.ThemeManager
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 统计页两层筛选弹窗管理器
 */
class StatisticsFilterBottomSheet(private val context: Context) {

    private val zoneId = ZoneId.systemDefault()
    private val isoFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")

    // ==========================================
    // 1. 时间范围选择 Sheet
    // ==========================================
    fun showRangePicker(
        currentRange: StatsDateRange,
        onRangeSelected: (StatsDateRange) -> Unit
    ) = showRangeSheet(currentRange, includeAll = false) { it?.let(onRangeSelected) }

    /** 统计详情流水用：比统计页多一个「全部」，null 表示全部。 */
    fun showHistoryRangePicker(
        currentRange: StatsDateRange?,
        onRangeSelected: (StatsDateRange?) -> Unit
    ) = showRangeSheet(currentRange, includeAll = true, onRangeSelected = onRangeSelected)

    private fun showRangeSheet(
        currentRange: StatsDateRange?,
        includeAll: Boolean,
        onRangeSelected: (StatsDateRange?) -> Unit
    ) {
        val dialog = BottomSheetDialog(context)
        val view = LayoutInflater.from(context).inflate(R.layout.sheet_stats_date_range, null)
        dialog.setContentView(view)

        val btnToday = view.findViewById<Button>(R.id.opt_range_today)
        val btnYesterday = view.findViewById<Button>(R.id.opt_range_yesterday)
        val btnThisWeek = view.findViewById<Button>(R.id.opt_range_this_week)
        val btnLastWeek = view.findViewById<Button>(R.id.opt_range_last_week)
        val btnLast7 = view.findViewById<Button>(R.id.opt_range_last_7)
        val btnThisMonth = view.findViewById<Button>(R.id.opt_range_this_month)
        val btnLastMonth = view.findViewById<Button>(R.id.opt_range_last_month)
        val btnLast30 = view.findViewById<Button>(R.id.opt_range_last_30)
        val btnThisYear = view.findViewById<Button>(R.id.opt_range_this_year)
        val btnAll = view.findViewById<Button>(R.id.opt_range_all)
        val btnCustom = view.findViewById<Button>(R.id.opt_range_custom)
        val customBox = view.findViewById<LinearLayout>(R.id.layout_custom_date_box)
        val btnStartDate = view.findViewById<Button>(R.id.btn_custom_start_date)
        val btnEndDate = view.findViewById<Button>(R.id.btn_custom_end_date)
        val btnApplyCustom = view.findViewById<Button>(R.id.btn_apply_custom_range)
        val btnClose = view.findViewById<TextView>(R.id.btn_close_range_sheet)

        btnClose.setOnClickListener { dialog.dismiss() }
        if (includeAll) view.findViewById<TextView>(R.id.tv_range_desc).text = "决定“从哪一天看到哪一天”。"

        // 当前已是自定义范围时带入它的起止日期（结束时刻是开区间，减一天才是最后一天）
        var customStart = LocalDate.now().minusDays(18)
        var customEnd = LocalDate.now()
        if (currentRange is StatsDateRange.Custom) {
            customStart = Instant.ofEpochMilli(currentRange.startMillis).atZone(zoneId).toLocalDate()
            customEnd = Instant.ofEpochMilli(currentRange.endMillisExclusive).atZone(zoneId).toLocalDate().minusDays(1)
                .let { if (it.isBefore(customStart)) customStart else it }
        }

        btnStartDate.text = customStart.format(isoFormatter)
        btnEndDate.text = customEnd.format(isoFormatter)

        fun updateCustomButtons() {
            btnStartDate.text = customStart.format(isoFormatter)
            btnEndDate.text = customEnd.format(isoFormatter)
        }

        btnStartDate.setOnClickListener {
            DatePickerDialog(
                context,
                { _, y, m, d ->
                    customStart = LocalDate.of(y, m + 1, d)
                    if (customStart.isAfter(customEnd)) customEnd = customStart
                    updateCustomButtons()
                },
                customStart.year,
                customStart.monthValue - 1,
                customStart.dayOfMonth
            ).show()
        }

        btnEndDate.setOnClickListener {
            DatePickerDialog(
                context,
                { _, y, m, d ->
                    customEnd = LocalDate.of(y, m + 1, d)
                    if (customEnd.isBefore(customStart)) customStart = customEnd
                    updateCustomButtons()
                },
                customEnd.year,
                customEnd.monthValue - 1,
                customEnd.dayOfMonth
            ).show()
        }

        btnApplyCustom.setOnClickListener {
            val startMillis = customStart.atStartOfDay(zoneId).toInstant().toEpochMilli()
            val endMillisExclusive = customEnd.plusDays(1).atStartOfDay(zoneId).toInstant().toEpochMilli()
            onRangeSelected(StatsDateRange.Custom(startMillis, endMillisExclusive))
            dialog.dismiss()
        }

        val rangeButtons = mapOf(
            StatsDateRange.Today to btnToday,
            StatsDateRange.Yesterday to btnYesterday,
            StatsDateRange.ThisWeek to btnThisWeek,
            StatsDateRange.LastWeek to btnLastWeek,
            StatsDateRange.Last7Days to btnLast7,
            StatsDateRange.ThisMonth to btnThisMonth,
            StatsDateRange.LastMonth to btnLastMonth,
            StatsDateRange.Last30Days to btnLast30,
            StatsDateRange.ThisYear to btnThisYear
        )

        val isAll = includeAll && currentRange == null
        val isCustom = currentRange is StatsDateRange.Custom
        customBox.isVisible = isCustom

        btnAll.isVisible = includeAll
        btnAll.backgroundTintList = null
        btnAll.setBackgroundResource(if (isAll) R.drawable.bg_zz_primary_btn else R.drawable.bg_zz_option_btn)
        btnAll.setTextColor(if (isAll) Color.WHITE else ThemeManager.getColorAttr(context, R.attr.zzText))
        btnAll.paint.isFakeBoldText = true
        btnAll.setOnClickListener {
            onRangeSelected(null)
            dialog.dismiss()
        }

        rangeButtons.forEach { (range, btn) ->
            val isSel = range == currentRange
            btn.backgroundTintList = null
            btn.setBackgroundResource(if (isSel) R.drawable.bg_zz_primary_btn else R.drawable.bg_zz_option_btn)
            btn.setTextColor(if (isSel) Color.WHITE else ThemeManager.getColorAttr(context, R.attr.zzText))
            btn.paint.isFakeBoldText = true

            btn.setOnClickListener {
                onRangeSelected(range)
                dialog.dismiss()
            }
        }

        btnCustom.backgroundTintList = null
        btnCustom.setBackgroundResource(if (isCustom) R.drawable.bg_zz_primary_btn else R.drawable.bg_zz_option_btn)
        btnCustom.setTextColor(if (isCustom) Color.WHITE else ThemeManager.getColorAttr(context, R.attr.zzText))
        btnCustom.paint.isFakeBoldText = true

        btnCustom.setOnClickListener {
            customBox.isVisible = true
            btnCustom.setBackgroundResource(R.drawable.bg_zz_primary_btn)
            btnCustom.setTextColor(Color.WHITE)
            (rangeButtons.values + btnAll).forEach { b ->
                b.setBackgroundResource(R.drawable.bg_zz_option_btn)
                b.setTextColor(ThemeManager.getColorAttr(context, R.attr.zzText))
            }
        }

        dialog.show()
    }

    // ==========================================
    // 2. 筛选 Sheet (星期 + 双栏真实应用)
    // ==========================================
    fun showConditionFilter(
        currentWeekdayFilter: WeekdayFilter,
        currentExcludedApps: Set<String>,
        onFilterApplied: (WeekdayFilter, Set<String>) -> Unit
    ) {
        val dialog = BottomSheetDialog(context)
        val view = LayoutInflater.from(context).inflate(R.layout.sheet_stats_filter, null)
        dialog.setContentView(view)

        val activeDows = currentWeekdayFilter.enabledDays.toMutableSet()
        val excludedApps = currentExcludedApps.toMutableSet()

        view.findViewById<TextView>(R.id.btn_close_filter_sheet).setOnClickListener { dialog.dismiss() }

        // 星期按钮
        val dowButtons = mapOf(
            DayOfWeek.MONDAY to view.findViewById<Button>(R.id.dow_1),
            DayOfWeek.TUESDAY to view.findViewById<Button>(R.id.dow_2),
            DayOfWeek.WEDNESDAY to view.findViewById<Button>(R.id.dow_3),
            DayOfWeek.THURSDAY to view.findViewById<Button>(R.id.dow_4),
            DayOfWeek.FRIDAY to view.findViewById<Button>(R.id.dow_5),
            DayOfWeek.SATURDAY to view.findViewById<Button>(R.id.dow_6),
            DayOfWeek.SUNDAY to view.findViewById<Button>(R.id.dow_7)
        )

        val presetAll = view.findViewById<Button>(R.id.preset_week_all)
        val presetWorkday = view.findViewById<Button>(R.id.preset_week_workday)
        val presetWeekend = view.findViewById<Button>(R.id.preset_week_weekend)
        val presetClear = view.findViewById<Button>(R.id.preset_week_clear)

        fun syncDowButtons() {
            dowButtons.forEach { (dow, btn) ->
                val isSel = activeDows.contains(dow)
                btn.backgroundTintList = null
                btn.setBackgroundResource(if (isSel) R.drawable.bg_zz_primary_btn else R.drawable.bg_zz_option_btn)
                btn.setTextColor(if (isSel) Color.WHITE else ThemeManager.getColorAttr(context, R.attr.zzText))
                btn.paint.isFakeBoldText = true
            }
        }

        syncDowButtons()

        dowButtons.forEach { (dow, btn) ->
            btn.setOnClickListener {
                if (activeDows.contains(dow)) activeDows.remove(dow) else activeDows.add(dow)
                syncDowButtons()
            }
        }

        presetAll.setOnClickListener { activeDows.clear(); activeDows.addAll(DayOfWeek.values()); syncDowButtons() }
        presetWorkday.setOnClickListener {
            activeDows.clear()
            activeDows.addAll(listOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY))
            syncDowButtons()
        }
        presetWeekend.setOnClickListener {
            activeDows.clear()
            activeDows.addAll(listOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY))
            syncDowButtons()
        }
        presetClear.setOnClickListener { activeDows.clear(); syncDowButtons() }

        // 双栏真实应用
        val tvIncludedTitle = view.findViewById<TextView>(R.id.tv_included_title)
        val tvExcludedTitle = view.findViewById<TextView>(R.id.tv_excluded_title)
        val rvIncluded = view.findViewById<RecyclerView>(R.id.rv_included_apps)
        val rvExcluded = view.findViewById<RecyclerView>(R.id.rv_excluded_apps)
        val etSearch = view.findViewById<EditText>(R.id.et_app_filter_search)

        rvIncluded.layoutManager = LinearLayoutManager(context)
        rvExcluded.layoutManager = LinearLayoutManager(context)

        var searchQuery = ""

        fun renderDualAppLists() {
            val q = searchQuery.trim().lowercase()
            val all = StatisticsRepository.getInstalledApps(context)
            val filtered = if (q.isEmpty()) all else all.filter {
                it.appName.lowercase().contains(q) || it.category.lowercase().contains(q) || it.packageName.lowercase().contains(q)
            }

            val includedList = filtered.filter { !excludedApps.contains(it.appName) && !excludedApps.contains(it.packageName) }
            val excludedList = filtered.filter { excludedApps.contains(it.appName) || excludedApps.contains(it.packageName) }

            tvIncludedTitle.text = "纳入统计 (${all.size - excludedList.size})"
            tvExcludedTitle.text = "排除统计 (${excludedList.size})"

            rvIncluded.adapter = DualAppAdapter(includedList, true, { name ->
                excludedApps.add(name)
                renderDualAppLists()
            }, { pkg, isG ->
                StatisticsRepository.toggleCustomGame(context, pkg, isG)
                renderDualAppLists()
            })
            rvExcluded.adapter = DualAppAdapter(excludedList, false, { name ->
                excludedApps.remove(name)
                renderDualAppLists()
            }, { pkg, isG ->
                StatisticsRepository.toggleCustomGame(context, pkg, isG)
                renderDualAppLists()
            })
        }

        renderDualAppLists()

        etSearch.addTextChangedListener {
            searchQuery = it?.toString() ?: ""
            renderDualAppLists()
        }

        // 批量操作
        view.findViewById<Button>(R.id.btn_include_all).setOnClickListener {
            excludedApps.clear()
            renderDualAppLists()
        }
        view.findViewById<Button>(R.id.btn_exclude_all).setOnClickListener {
            val all = StatisticsRepository.getInstalledApps(context)
            excludedApps.addAll(all.map { it.appName })
            renderDualAppLists()
        }

        // 提交应用
        view.findViewById<Button>(R.id.btn_apply_filter).setOnClickListener {
            onFilterApplied(WeekdayFilter(activeDows), excludedApps)
            dialog.dismiss()
        }

        dialog.show()
    }

    private class DualAppAdapter(
        private val list: List<StatisticsRepository.InstalledAppMeta>,
        private val isIncluded: Boolean,
        private val onItemClick: (String) -> Unit,
        private val onToggleGame: (String, Boolean) -> Unit
    ) : RecyclerView.Adapter<DualAppAdapter.ViewHolder>() {

        class ViewHolder(v: View) : RecyclerView.ViewHolder(v) {
            val name: TextView = v.findViewById(R.id.tv_dual_app_name)
            val category: TextView = v.findViewById(R.id.tv_dual_app_category)
            val arrow: TextView = v.findViewById(R.id.tv_dual_app_arrow)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_stats_dual_app, parent, false)
            return ViewHolder(v)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = list[position]
            holder.name.text = item.appName
            holder.category.text = if (item.isGame) "🎮 游戏 (长按切换)" else item.category
            holder.arrow.text = if (isIncluded) "→" else "←"
            holder.itemView.setOnClickListener { onItemClick(item.appName) }
            holder.itemView.setOnLongClickListener {
                onToggleGame(item.packageName, !item.isGame)
                true
            }
        }

        override fun getItemCount(): Int = list.size
    }
}
