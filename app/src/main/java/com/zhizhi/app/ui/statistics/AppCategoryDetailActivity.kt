package com.zhizhi.app.ui.statistics

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.zhizhi.app.R
import com.zhizhi.app.data.statistics.AppCategory
import com.zhizhi.app.data.statistics.AppUsageItem
import com.zhizhi.app.data.statistics.CategoryDetailData
import com.zhizhi.app.data.statistics.StatsDateRange
import com.zhizhi.app.data.statistics.WeekdayFilter
import com.zhizhi.app.manager.statistics.StatisticsRepository
import com.zhizhi.app.manager.statistics.StatsDateRangeResolver
import com.zhizhi.app.ui.ThemeManager

/**
 * 分类二级详情页面
 * 集中展示该分类下所有具体应用的使用时长、打开次数排行及占比
 */
class AppCategoryDetailActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_CATEGORY_ID = "extra_category_id"
        const val EXTRA_RANGE_STR = "extra_range_str"
        const val EXTRA_WEEKDAY_DAYS = "extra_weekday_days"
        const val EXTRA_EXCLUDED_APPS = "extra_excluded_apps"

        fun serializeRange(range: StatsDateRange): String = when (range) {
            is StatsDateRange.Today -> "Today"
            is StatsDateRange.Yesterday -> "Yesterday"
            is StatsDateRange.ThisWeek -> "ThisWeek"
            is StatsDateRange.LastWeek -> "LastWeek"
            is StatsDateRange.Last7Days -> "Last7Days"
            is StatsDateRange.ThisMonth -> "ThisMonth"
            is StatsDateRange.LastMonth -> "LastMonth"
            is StatsDateRange.Last30Days -> "Last30Days"
            is StatsDateRange.ThisYear -> "ThisYear"
            is StatsDateRange.Custom -> "Custom:${range.startMillis}:${range.endMillisExclusive}"
        }

        fun deserializeRange(str: String?): StatsDateRange = when {
            str == "Today" -> StatsDateRange.Today
            str == "Yesterday" -> StatsDateRange.Yesterday
            str == "ThisWeek" -> StatsDateRange.ThisWeek
            str == "LastWeek" -> StatsDateRange.LastWeek
            str == "Last7Days" -> StatsDateRange.Last7Days
            str == "ThisMonth" -> StatsDateRange.ThisMonth
            str == "LastMonth" -> StatsDateRange.LastMonth
            str == "Last30Days" -> StatsDateRange.Last30Days
            str == "ThisYear" -> StatsDateRange.ThisYear
            str != null && str.startsWith("Custom:") -> {
                val parts = str.split(":")
                StatsDateRange.Custom(parts.getOrNull(1)?.toLongOrNull() ?: 0L, parts.getOrNull(2)?.toLongOrNull() ?: System.currentTimeMillis())
            }
            else -> StatsDateRange.ThisWeek
        }

        fun start(
            context: Context,
            category: AppCategory,
            range: StatsDateRange = StatsDateRange.ThisWeek,
            weekdayFilter: WeekdayFilter = WeekdayFilter.ALL,
            excludedApps: Set<String> = emptySet()
        ) {
            val intent = Intent(context, AppCategoryDetailActivity::class.java).apply {
                putExtra(EXTRA_CATEGORY_ID, category.id)
                putExtra(EXTRA_RANGE_STR, serializeRange(range))
                putExtra(EXTRA_WEEKDAY_DAYS, ArrayList(weekdayFilter.enabledDays.map { it.value }))
                putExtra(EXTRA_EXCLUDED_APPS, ArrayList(excludedApps))
            }
            context.startActivity(intent)
        }
    }

    private lateinit var category: AppCategory
    private lateinit var rvApps: RecyclerView
    private lateinit var tvTitle: TextView
    private lateinit var tvSubtitle: TextView
    private lateinit var tvDuration: TextView
    private lateinit var tvOpens: TextView
    private lateinit var tvAppCount: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.apply(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_app_category_detail)

        val catId = intent.getStringExtra(EXTRA_CATEGORY_ID) ?: AppCategory.GAME.id
        category = AppCategory.fromId(catId) ?: AppCategory.GAME

        initViews()
        loadData()
    }

    private fun initViews() {
        findViewById<TextView>(R.id.btn_back_category).setOnClickListener {
            finish()
        }

        tvTitle = findViewById(R.id.tv_category_title)
        tvSubtitle = findViewById(R.id.tv_cat_subtitle)
        tvDuration = findViewById(R.id.tv_cat_duration)
        tvOpens = findViewById(R.id.tv_cat_opens)
        tvAppCount = findViewById(R.id.tv_cat_app_count)

        tvTitle.text = "${category.icon} ${category.displayName} 详情"

        rvApps = findViewById(R.id.rv_category_apps)
        rvApps.layoutManager = LinearLayoutManager(this)
    }

    private fun loadData() {
        val range = deserializeRange(intent.getStringExtra(EXTRA_RANGE_STR))
        val resolvedRange = StatsDateRangeResolver.resolveRange(range)

        val daysExtra = intent.getIntegerArrayListExtra(EXTRA_WEEKDAY_DAYS)
        val weekdayFilter = if (daysExtra != null) {
            WeekdayFilter(daysExtra.map { java.time.DayOfWeek.of(it) }.toSet())
        } else {
            WeekdayFilter.ALL
        }

        val excludedApps = intent.getStringArrayListExtra(EXTRA_EXCLUDED_APPS)?.toSet() ?: emptySet()

        tvSubtitle.text = "${resolvedRange.displayName} · ${resolvedRange.dateSubText} · ${weekdayFilter.summaryText()}"

        val detail: CategoryDetailData = StatisticsRepository.getCategoryDetail(
            context = this,
            category = category,
            range = range,
            resolvedRange = resolvedRange,
            weekdayFilter = weekdayFilter,
            excludedApps = excludedApps
        )

        val hours = detail.totalUsageMinutes / 60
        val mins = detail.totalUsageMinutes % 60
        tvDuration.text = if (hours > 0) "${hours}h ${mins}min" else "${mins}min"
        tvOpens.text = "${detail.totalOpenCount} 次"
        tvAppCount.text = "${detail.subApps.size} 款"

        rvApps.adapter = SubAppAdapter(this, detail.subApps, detail.totalUsageMinutes) { app ->
            showCategoryChangeDialog(app)
        }
    }

    private fun showCategoryChangeDialog(app: AppUsageItem) {
        val categories = AppCategory.values()
        val items = categories.map { "${it.icon} ${it.displayName}" }.toTypedArray()
        val currentCat = StatisticsRepository.resolveCategory(this, app.packageName, null)
        val checkedIndex = categories.indexOf(currentCat).coerceAtLeast(0)

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("修改应用分类：${app.appName}")
            .setSingleChoiceItems(items, checkedIndex) { dialog, which ->
                val selectedCategory = categories[which]
                StatisticsRepository.setCustomCategory(this, app.packageName, selectedCategory)
                android.widget.Toast.makeText(this, "已将「${app.appName}」重分类为「${selectedCategory.displayName}」", android.widget.Toast.LENGTH_SHORT).show()
                dialog.dismiss()
                loadData()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private class SubAppAdapter(
        private val context: Context,
        private val apps: List<AppUsageItem>,
        private val totalCategoryMinutes: Long,
        private val onItemLongClick: (AppUsageItem) -> Unit
    ) : RecyclerView.Adapter<SubAppAdapter.ViewHolder>() {

        private val pm: PackageManager = context.packageManager

        class ViewHolder(v: View) : RecyclerView.ViewHolder(v) {
            val icon: ImageView = v.findViewById(R.id.iv_sub_app_icon)
            val name: TextView = v.findViewById(R.id.tv_sub_app_name)
            val opens: TextView = v.findViewById(R.id.tv_sub_app_opens)
            val duration: TextView = v.findViewById(R.id.tv_sub_app_duration)
            val percent: TextView = v.findViewById(R.id.tv_sub_app_percent)
            val pbRatio: ProgressBar = v.findViewById(R.id.pb_sub_app_ratio)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_category_sub_app, parent, false)
            return ViewHolder(v)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val app = apps[position]
            holder.name.text = app.appName
            holder.opens.text = "打开 ${app.openCount} 次 · 长按修改分类"

            val h = app.usageDurationMinutes / 60
            val m = app.usageDurationMinutes % 60
            holder.duration.text = if (h > 0) "${h}h ${m}min" else "${m}min"

            val ratio = if (totalCategoryMinutes > 0) {
                ((app.usageDurationMinutes.toFloat() / totalCategoryMinutes) * 100).toInt().coerceIn(0, 100)
            } else 0

            holder.percent.text = "$ratio%"
            holder.pbRatio.progress = ratio

            try {
                val appIcon: Drawable = pm.getApplicationIcon(app.packageName)
                holder.icon.setImageDrawable(appIcon)
            } catch (_: Exception) {
                holder.icon.setImageResource(android.R.drawable.sym_def_app_icon)
            }

            holder.itemView.setOnLongClickListener {
                onItemLongClick(app)
                true
            }
        }

        override fun getItemCount(): Int = apps.size
    }
}
