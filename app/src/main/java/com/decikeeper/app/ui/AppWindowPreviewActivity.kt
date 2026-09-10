/**
 * =================================================================================
 * [Context Header]
 * * @file: AppWindowPreviewActivity.kt
 * * @role: UI
 * * @description: 应用时间窗叠加预览页面。
 * * @responsibilities:
 * 1. 渲染时间窗预览视图。
 * 2. 切换按应用/按时间轴的展示模式。
 * * @context:
 * - 上游: AppWindowFragment
 * - 下游: AppWindowManager
 * * @constraints (必读):
 * 1. 禁止承载复杂业务逻辑。
 * 2. 仅负责 UI 展示与切换。
 * 3. 需处理空数据。
 * =================================================================================
 */
package com.decikeeper.app.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.tabs.TabLayout
import com.decikeeper.app.R
import com.decikeeper.app.manager.AppWindowManager
import java.util.Calendar

class AppWindowPreviewActivity : AppCompatActivity() {

    private lateinit var tabLayout: TabLayout
    private lateinit var toggleGroup: MaterialButtonToggleGroup
    private lateinit var recyclerView: RecyclerView
    private lateinit var tvEmpty: TextView
    private lateinit var adapter: PreviewAdapter
    private var allRules: List<AppWindowManager.AppWindowRule> = emptyList()
    private var selectedDayOfWeek: Int = -1
    private var isViewByApp: Boolean = true
    private data class DisplayEntry(val start: Int, val text: String)

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.apply(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_app_window_preview)

        val toolbar = findViewById<androidx.appcompat.widget.Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "排程预览"

        tabLayout = findViewById(R.id.tabLayoutDate)
        toggleGroup = findViewById(R.id.toggleGroupView)
        recyclerView = findViewById(R.id.recyclerView)
        tvEmpty = findViewById(R.id.tvEmpty)
        recyclerView.layoutManager = LinearLayoutManager(this)
        adapter = PreviewAdapter()
        recyclerView.adapter = adapter
        setupTabs()
        setupListeners()
        loadData()
    }

    private fun setupTabs() {
        tabLayout.removeAllTabs()
        val todayRuleDay = resolveRuleDay(isToday = true)
        val tomorrowRuleDay = resolveRuleDay(isToday = false)
        tabLayout.addTab(tabLayout.newTab().setText("今天(${formatRuleDay(todayRuleDay)})"))
        tabLayout.addTab(tabLayout.newTab().setText("明天(${formatRuleDay(tomorrowRuleDay)})"))
        tabLayout.selectTab(tabLayout.getTabAt(0))
    }

    private fun setupListeners() {
        tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) { refreshList() }
            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {}
        })
        toggleGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                isViewByApp = checkedId == R.id.btnViewByApp
                refreshList()
            }
        }
    }

    private fun loadData() { refreshList() }

    private fun getAppName(pkg: String): String = try {
        val pm = packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    } catch (_: Exception) { pkg }

    private fun formatTime(mins: Int): String = String.format("%02d:%02d", mins / 60, mins % 60)

    private fun refreshList() {
        val isToday = tabLayout.selectedTabPosition == 0
        val pendingRules = if (!isToday) AppWindowManager.getPendingRules(this)?.associateBy { it.id }?.values?.toList() else null
        val usePendingSource = !isToday && !pendingRules.isNullOrEmpty()
        val targetRules = if (usePendingSource) pendingRules!! else AppWindowManager.getRules(this)
        val ruleDay = resolveRuleDay(isToday)
        val displayRules = filterRulesForSnapshot(this, targetRules, usePendingSource)
        if (displayRules.isEmpty()) {
            tvEmpty.text = if (isToday) "今天(${formatRuleDay(ruleDay)})当前无生效规则" else "明天(${formatRuleDay(ruleDay)})无待生效规则"
            tvEmpty.visibility = View.VISIBLE
            recyclerView.visibility = View.GONE
            return
        }
        tvEmpty.visibility = View.GONE
        recyclerView.visibility = View.VISIBLE
        if (isViewByApp) updateViewByApp(displayRules, ruleDay, usePendingSource)
        else updateViewByTime(displayRules, ruleDay, usePendingSource)
    }

    private fun resolveRuleDay(isToday: Boolean): Int {
        val cal = Calendar.getInstance()
        if (!isToday) cal.add(Calendar.DAY_OF_YEAR, 1)
        val dayOfWeek = cal.get(Calendar.DAY_OF_WEEK)
        return if (dayOfWeek == Calendar.SUNDAY) 7 else dayOfWeek - 1
    }

    private fun formatRuleDay(ruleDay: Int): String = formatRuleDayLabel(ruleDay)

    private fun updateViewByApp(rules: List<AppWindowManager.AppWindowRule>, ruleDay: Int, usePendingSource: Boolean) {
        val appMap = mutableMapOf<String, MutableList<AppWindowManager.AppWindowRule>>()
        rules.forEach { rule -> rule.packageNames.forEach { pkg -> appMap.computeIfAbsent(pkg) { mutableListOf() }.add(rule) } }
        val items = appMap.keys.sortedBy { getAppName(it) }.map { pkg ->
            val appRules = appMap[pkg]!!
            val sortedRules = appRules.sortedWith(compareByDescending<AppWindowManager.AppWindowRule> {
                isPackageEffectiveOnDayForPreview(this, it, pkg, ruleDay, usePendingSource)
            }.thenBy { it.startTime })
            val subtitle = sortedRules.joinToString("\n") { rule ->
                val timeStr = "${formatTime(rule.startTime)} - ${formatTime(rule.endTime)}"
                val effectiveDays = computeRuleEffectiveDaysForPreview(this, rule, usePendingSource)
                "$timeStr ${buildRuleNameWithEffectiveDays(rule.name, formatDays(effectiveDays))}"
            }
            DisplayItem(
                title = getAppName(pkg), subtitle = subtitle, iconPkg = pkg, type = TYPE_APP,
                isEffectiveToday = appRules.any { rule ->
                    computeRuleEffectiveDaysForPreview(this, rule, usePendingSource).isNotEmpty() &&
                        (usePendingSource || (1..7).any { day -> AppWindowManager.isPackageEffectiveOnDay(this, rule, pkg, day) })
                }
            )
        }
        adapter.setData(items)
    }

    private data class MergedRange(val start: Int, val end: Int, val sources: List<String>)

    private fun updateViewByTime(rules: List<AppWindowManager.AppWindowRule>, ruleDay: Int, usePendingSource: Boolean) {
        val items = rules.sortedBy { it.startTime }.map { rule ->
            val effectiveDays = computeRuleEffectiveDaysForPreview(this, rule, usePendingSource)
            DisplayItem(
                title = "${formatTime(rule.startTime)} - ${formatTime(rule.endTime)}",
                subtitle = formatDays(effectiveDays), iconPkg = null, type = TYPE_TIME,
                extraInfo = rule.name, pkgList = rule.packageNames.toList(), daysStr = formatDays(effectiveDays), isEffectiveToday = true
            )
        }
        adapter.setData(items)
    }

    private fun formatDays(daysOfWeek: Set<Int>): String = when {
        daysOfWeek.containsAll((1..7).toList()) -> "每天"
        daysOfWeek.containsAll((2..6).toList()) && daysOfWeek.size == 5 -> "工作日"
        daysOfWeek.containsAll(listOf(1, 7)) && daysOfWeek.size == 2 -> "周末"
        else -> {
            val dayMap = mapOf(1 to "一", 2 to "二", 3 to "三", 4 to "四", 5 to "五", 6 to "六", 7 to "日")
            "周" + daysOfWeek.sorted().map { dayMap[it] ?: "" }.joinToString("、")
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) { finish(); return true }
        return super.onOptionsItemSelected(item)
    }

    data class DisplayItem(
        val title: String,
        val subtitle: String,
        val iconPkg: String?,
        val type: Int,
        val extraInfo: String? = null,
        val pkgList: List<String> = emptyList(),
        val daysStr: String = "",
        val isEffectiveToday: Boolean = true
    )

    companion object {
        const val TYPE_APP = 0
        const val TYPE_TIME = 1

        internal fun isRuleEffectiveOnDayForPreview(rule: AppWindowManager.AppWindowRule, ruleDay: Int, isPartiallyDelayedOnTargetDay: Boolean): Boolean =
            rule.isEnabled && rule.daysOfWeek.contains(ruleDay) && !isPartiallyDelayedOnTargetDay

        internal fun filterRulesEffectiveOnDay(context: android.content.Context, rules: List<AppWindowManager.AppWindowRule>, ruleDay: Int, usePendingSource: Boolean = false): List<AppWindowManager.AppWindowRule> =
            rules.filter { rule -> if (usePendingSource) isRuleScheduledOnDay(rule, ruleDay) else AppWindowManager.isRuleEffectiveOnDay(context, rule, ruleDay) }

        internal fun computeRuleEffectiveDaysForPreview(context: android.content.Context, rule: AppWindowManager.AppWindowRule, usePendingSource: Boolean): Set<Int> {
            if (!rule.isEnabled || rule.packageNames.isEmpty()) return emptySet()
            return if (usePendingSource) normalizeDays(rule.daysOfWeek)
            else (1..7).filter { day -> AppWindowManager.isRuleEffectiveOnDay(context, rule, day) }.toSet()
        }

        internal fun filterRulesForSnapshot(context: android.content.Context, rules: List<AppWindowManager.AppWindowRule>, usePendingSource: Boolean = false): List<AppWindowManager.AppWindowRule> =
            rules.filter { rule -> computeRuleEffectiveDaysForPreview(context, rule, usePendingSource).isNotEmpty() }

        private fun isPackageEffectiveOnDayForPreview(context: android.content.Context, rule: AppWindowManager.AppWindowRule, packageName: String, ruleDay: Int, usePendingSource: Boolean): Boolean {
            if (usePendingSource) return rule.isEnabled && rule.packageNames.contains(packageName) && normalizeDays(rule.daysOfWeek).contains(ruleDay)
            return AppWindowManager.isPackageEffectiveOnDay(context, rule, packageName, ruleDay)
        }

        private fun isRuleScheduledOnDay(rule: AppWindowManager.AppWindowRule, ruleDay: Int): Boolean = rule.isEnabled && normalizeDays(rule.daysOfWeek).contains(ruleDay)
        private fun normalizeDays(days: Set<Int>): Set<Int> { val cleaned = days.filter { it in 1..7 }.toSet(); return if (cleaned.isEmpty()) (1..7).toSet() else cleaned }
        internal fun buildRuleNameWithEffectiveDays(ruleName: String, daysText: String): String = "[$ruleName]（生效：$daysText）"
        internal fun formatRuleDayLabel(ruleDay: Int): String = when (ruleDay) {
            1 -> "周一"; 2 -> "周二"; 3 -> "周三"; 4 -> "周四"; 5 -> "周五"; 6 -> "周六"; 7 -> "周日"; else -> "未知"
        }
    }

    inner class PreviewAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        private var list: List<DisplayItem> = emptyList()
        fun setData(newList: List<DisplayItem>) { list = newList; notifyDataSetChanged() }
        override fun getItemViewType(position: Int): Int = list[position].type
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(
                if (viewType == TYPE_TIME) R.layout.item_app_window_preview_time else R.layout.item_app_window_preview_rule,
                parent, false
            )
            return if (viewType == TYPE_TIME) TimeViewHolder(view) else AppViewHolder(view)
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val item = list[position]
            holder.itemView.alpha = 1f
            if (holder is AppViewHolder) {
                holder.tvTitle.text = item.title
                holder.ivIcon.visibility = View.VISIBLE
                try { holder.ivIcon.setImageDrawable(packageManager.getApplicationIcon(item.iconPkg!!)) }
                catch (_: Exception) { holder.ivIcon.setImageResource(android.R.drawable.sym_def_app_icon) }
                holder.llContainer.removeAllViews()
                holder.llContainer.addView(TextView(this@AppWindowPreviewActivity).apply {
                    text = item.subtitle
                    setTextColor(if (item.isEffectiveToday) android.graphics.Color.DKGRAY else android.graphics.Color.GRAY)
                    textSize = 14f
                })
            } else if (holder is TimeViewHolder) {
                holder.tvName.text = item.extraInfo ?: "规则"
                holder.tvTime.text = item.title
                holder.tvDays.text = item.daysStr
                holder.tvDays.setTextColor(android.graphics.Color.GRAY)
                holder.llAppIcons.removeAllViews()
                val iconSize = (24 * resources.displayMetrics.density).toInt()
                val margin = (4 * resources.displayMetrics.density).toInt()
                item.pkgList.take(6).forEach { pkg ->
                    holder.llAppIcons.addView(android.widget.ImageView(this@AppWindowPreviewActivity).apply {
                        layoutParams = android.widget.LinearLayout.LayoutParams(iconSize, iconSize).apply { setMargins(0, 0, margin, 0) }
                        try { setImageDrawable(packageManager.getApplicationIcon(pkg)) }
                        catch (_: Exception) { setImageResource(android.R.drawable.sym_def_app_icon) }
                    })
                }
                if (item.pkgList.size > 6) holder.llAppIcons.addView(TextView(this@AppWindowPreviewActivity).apply {
                    text = "+${item.pkgList.size - 6}"
                    textSize = 12f
                    setTextColor(android.graphics.Color.GRAY)
                })
            }
        }

        override fun getItemCount() = list.size
        inner class AppViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val ivIcon: android.widget.ImageView = view.findViewById(R.id.ivAppIcon)
            val tvTitle: TextView = view.findViewById(R.id.tvAppName)
            val llContainer: android.widget.LinearLayout = view.findViewById(R.id.llRulesContainer)
        }
        inner class TimeViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val tvName: TextView = view.findViewById(R.id.tvName)
            val tvTime: TextView = view.findViewById(R.id.tvTime)
            val tvDays: TextView = view.findViewById(R.id.tvDays)
            val llAppIcons: android.widget.LinearLayout = view.findViewById(R.id.llAppIcons)
        }
    }
}
