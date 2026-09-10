package com.decikeeper.app.ui.statistics

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.addTextChangedListener
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.decikeeper.app.R
import com.decikeeper.app.data.statistics.AppCategory
import com.decikeeper.app.data.statistics.WeekdayFilter
import com.decikeeper.app.manager.statistics.StatisticsRepository
import com.decikeeper.app.ui.ThemeManager
import com.decikeeper.app.util.ContrastHelper
import java.time.DayOfWeek

/**
 * 全屏统计筛选界面 (星期构成与应用过滤)
 * 解决底部弹窗空间挤压、高度截断与双栏滑动体验问题。
 */
class StatsFilterActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_ACTIVE_DOWS = "extra_active_dows"
        const val EXTRA_EXCLUDED_APPS = "extra_excluded_apps"

        fun start(activity: Activity, requestCode: Int, weekdayFilter: WeekdayFilter, excludedApps: Set<String>) {
            val intent = Intent(activity, StatsFilterActivity::class.java).apply {
                putExtra(EXTRA_ACTIVE_DOWS, ArrayList(weekdayFilter.enabledDays.map { it.value }))
                putExtra(EXTRA_EXCLUDED_APPS, ArrayList(excludedApps))
            }
            activity.startActivityForResult(intent, requestCode)
        }

        fun start(fragment: androidx.fragment.app.Fragment, requestCode: Int, weekdayFilter: WeekdayFilter, excludedApps: Set<String>) {
            val intent = Intent(fragment.requireContext(), StatsFilterActivity::class.java).apply {
                putExtra(EXTRA_ACTIVE_DOWS, ArrayList(weekdayFilter.enabledDays.map { it.value }))
                putExtra(EXTRA_EXCLUDED_APPS, ArrayList(excludedApps))
            }
            fragment.startActivityForResult(intent, requestCode)
        }
    }

    private val activeDows = mutableSetOf<DayOfWeek>()
    private val excludedApps = mutableSetOf<String>()
    private var searchQuery = ""

    private lateinit var dowButtons: List<Pair<DayOfWeek, Button>>
    private lateinit var rvIncluded: RecyclerView
    private lateinit var rvExcluded: RecyclerView
    private lateinit var tvIncludedTitle: TextView
    private lateinit var tvExcludedTitle: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.apply(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_stats_filter)

        val initDows = intent.getIntegerArrayListExtra(EXTRA_ACTIVE_DOWS)
        if (initDows != null) {
            activeDows.addAll(initDows.map { DayOfWeek.of(it) })
        } else {
            activeDows.addAll(DayOfWeek.values())
        }

        val initExcluded = intent.getStringArrayListExtra(EXTRA_EXCLUDED_APPS)
        if (initExcluded != null) {
            excludedApps.addAll(initExcluded)
        }

        initViews()
    }

    private fun initViews() {
        findViewById<TextView>(R.id.btn_back_filter).setOnClickListener {
            finish()
        }

        findViewById<Button>(R.id.btn_save_filter).setOnClickListener {
            val resultIntent = Intent().apply {
                putExtra(EXTRA_ACTIVE_DOWS, ArrayList(activeDows.map { it.value }))
                putExtra(EXTRA_EXCLUDED_APPS, ArrayList(excludedApps))
            }
            setResult(Activity.RESULT_OK, resultIntent)
            finish()
        }

        // 星期按钮
        dowButtons = listOf(
            DayOfWeek.MONDAY to findViewById(R.id.dow_1),
            DayOfWeek.TUESDAY to findViewById(R.id.dow_2),
            DayOfWeek.WEDNESDAY to findViewById(R.id.dow_3),
            DayOfWeek.THURSDAY to findViewById(R.id.dow_4),
            DayOfWeek.FRIDAY to findViewById(R.id.dow_5),
            DayOfWeek.SATURDAY to findViewById(R.id.dow_6),
            DayOfWeek.SUNDAY to findViewById(R.id.dow_7)
        )

        val textColorUnselected = ThemeManager.getColorAttr(this, R.attr.zzText)
        val textColorSelected = ContrastHelper.COLOR_WHITE
        fun syncDowButtons() {
            dowButtons.forEach { (dow, btn) ->
                val selected = activeDows.contains(dow)
                btn.setBackgroundResource(if (selected) R.drawable.bg_zz_primary_btn else R.drawable.bg_zz_option_btn)
                btn.setTextColor(if (selected) textColorSelected else textColorUnselected)
            }
        }

        dowButtons.forEach { (dow, btn) ->
            btn.setOnClickListener {
                if (activeDows.contains(dow)) {
                    activeDows.remove(dow)
                } else {
                    activeDows.add(dow)
                }
                syncDowButtons()
            }
        }

        findViewById<Button>(R.id.preset_week_all).setOnClickListener {
            activeDows.clear()
            activeDows.addAll(DayOfWeek.values())
            syncDowButtons()
        }
        findViewById<Button>(R.id.preset_week_workday).setOnClickListener {
            activeDows.clear()
            activeDows.addAll(setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY))
            syncDowButtons()
        }
        findViewById<Button>(R.id.preset_week_weekend).setOnClickListener {
            activeDows.clear()
            activeDows.addAll(setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY))
            syncDowButtons()
        }
        findViewById<Button>(R.id.preset_week_clear).setOnClickListener {
            activeDows.clear()
            syncDowButtons()
        }

        syncDowButtons()

        // 双栏应用列表
        tvIncludedTitle = findViewById(R.id.tv_included_title)
        tvExcludedTitle = findViewById(R.id.tv_excluded_title)
        rvIncluded = findViewById(R.id.rv_included_apps)
        rvExcluded = findViewById(R.id.rv_excluded_apps)
        val etSearch = findViewById<EditText>(R.id.et_app_filter_search)

        rvIncluded.layoutManager = LinearLayoutManager(this)
        rvExcluded.layoutManager = LinearLayoutManager(this)

        fun renderDualAppLists() {
            val q = searchQuery.trim().lowercase()
            val all = StatisticsRepository.getInstalledApps(this)
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
            }, { item ->
                showCategoryDialog(item) { renderDualAppLists() }
            })
            rvExcluded.adapter = DualAppAdapter(excludedList, false, { name ->
                excludedApps.remove(name)
                renderDualAppLists()
            }, { item ->
                showCategoryDialog(item) { renderDualAppLists() }
            })
        }

        renderDualAppLists()

        etSearch.addTextChangedListener {
            searchQuery = it?.toString() ?: ""
            renderDualAppLists()
        }

        // 快捷筛选芯片
        findViewById<Button>(R.id.chip_exclude_comm).setOnClickListener {
            val commApps = StatisticsRepository.getInstalledApps(this).filter { it.category == "社交通讯" }
            excludedApps.addAll(commApps.map { it.appName })
            renderDualAppLists()
        }
        findViewById<Button>(R.id.chip_exclude_ai).setOnClickListener {
            val aiApps = StatisticsRepository.getInstalledApps(this).filter { it.category == "AI与效率" }
            excludedApps.addAll(aiApps.map { it.appName })
            renderDualAppLists()
        }
        findViewById<Button>(R.id.chip_exclude_video).setOnClickListener {
            val videoApps = StatisticsRepository.getInstalledApps(this).filter { it.category == "视频娱乐" }
            excludedApps.addAll(videoApps.map { it.appName })
            renderDualAppLists()
        }
        findViewById<Button>(R.id.chip_exclude_music).setOnClickListener {
            val musicApps = StatisticsRepository.getInstalledApps(this).filter { it.category == "音乐音频" }
            excludedApps.addAll(musicApps.map { it.appName })
            renderDualAppLists()
        }
        findViewById<Button>(R.id.chip_reset_apps).setOnClickListener {
            excludedApps.clear()
            renderDualAppLists()
        }

        // 批量操作
        findViewById<Button>(R.id.btn_include_all).setOnClickListener {
            excludedApps.clear()
            renderDualAppLists()
        }
        findViewById<Button>(R.id.btn_exclude_all).setOnClickListener {
            val all = StatisticsRepository.getInstalledApps(this)
            excludedApps.addAll(all.map { it.appName })
            renderDualAppLists()
        }
    }

    private fun showCategoryDialog(item: StatisticsRepository.InstalledAppMeta, onDone: () -> Unit) {
        val categories = AppCategory.values()
        val items = categories.map { "${it.icon} ${it.displayName}" }.toTypedArray()
        val currentCat = StatisticsRepository.resolveCategory(this, item.packageName, null)
        val checkedIndex = categories.indexOf(currentCat).coerceAtLeast(0)

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("修改应用分类：${item.appName}")
            .setSingleChoiceItems(items, checkedIndex) { dialog, which ->
                val selectedCategory = categories[which]
                StatisticsRepository.setCustomCategory(this, item.packageName, selectedCategory)
                android.widget.Toast.makeText(this, "已将「${item.appName}」重分类为「${selectedCategory.displayName}」", android.widget.Toast.LENGTH_SHORT).show()
                dialog.dismiss()
                onDone()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private class DualAppAdapter(
        private val list: List<StatisticsRepository.InstalledAppMeta>,
        private val isIncluded: Boolean,
        private val onItemClick: (String) -> Unit,
        private val onItemLongClick: (StatisticsRepository.InstalledAppMeta) -> Unit
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
            holder.category.text = "${item.category} (长按修改)"
            holder.arrow.text = if (isIncluded) "→" else "←"
            holder.itemView.setOnClickListener { onItemClick(item.appName) }
            holder.itemView.setOnLongClickListener {
                onItemLongClick(item)
                true
            }
        }

        override fun getItemCount(): Int = list.size
    }
}
