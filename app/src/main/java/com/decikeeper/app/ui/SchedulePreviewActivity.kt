/**
 * =================================================================================
 * [Context Header]
 * * @file: SchedulePreviewActivity.kt
 * * @role: UI
 * * @description: 排程预览页面入口。
 * * @responsibilities:
 * 1. 展示完整日程叠加预览。
 * 2. 支持切换预览模式。
 * * @context:
 * - 上游: TimeConfigFragment
 * - 下游: TimeRuleManager
 * * @constraints (必读):
 * 1. 禁止承载复杂业务逻辑。
 * 2. 仅负责 UI 展示。
 * 3. 需处理空数据。
 * =================================================================================
 */
package com.decikeeper.app.ui

import android.os.Bundle
import android.view.View
import android.widget.RadioGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.decikeeper.app.R
import com.decikeeper.app.data.TimeSegment
import com.decikeeper.app.manager.TimeRuleManager

class SchedulePreviewActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: TimeSegmentAdapter
    private val todaySegments = mutableListOf<TimeSegment>()
    private val tomorrowSegments = mutableListOf<TimeSegment>()

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.apply(this)
        super.onCreate(savedInstanceState)

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
            window.statusBarColor = android.graphics.Color.TRANSPARENT
            window.decorView.systemUiVisibility = android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE or android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        }

        setContentView(R.layout.activity_schedule_preview)
        val root = findViewById<View>(R.id.root_preview)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        findViewById<View>(R.id.btn_back).setOnClickListener { finish() }
        loadData()
        recyclerView = findViewById(R.id.rv_preview)
        recyclerView.layoutManager = LinearLayoutManager(this)
        setupAdapter(todaySegments)
        findViewById<RadioGroup>(R.id.rg_view_mode).setOnCheckedChangeListener { _, checkedId ->
            setupAdapter(if (checkedId == R.id.rb_view_today) todaySegments else tomorrowSegments)
        }
    }

    private fun loadData() {
        val todayCal = java.util.Calendar.getInstance()
        val todayDay = todayCal.get(java.util.Calendar.DAY_OF_WEEK)
        val tomorrowDay = java.util.Calendar.getInstance().apply { add(java.util.Calendar.DAY_OF_YEAR, 1) }
            .get(java.util.Calendar.DAY_OF_WEEK)
        val current = TimeRuleManager.getTimeSegments(this)
        val pending = TimeRuleManager.getPendingTimeSegments(this)
        val previewData = resolvePreviewDataForDays(
            activeSegments = current,
            pendingSegments = pending,
            hasPending = TimeRuleManager.hasPendingRules(this),
            todayDayOfWeek = todayDay,
            tomorrowDayOfWeek = tomorrowDay
        )
        todaySegments.addAll(previewData.todaySegments)
        tomorrowSegments.addAll(previewData.tomorrowSegments)
    }

    private fun setupAdapter(data: List<TimeSegment>) {
        adapter = TimeSegmentAdapter(data.toMutableList(), isReadOnly = true, onEdit = {}, onDelete = {}, onToggle = { _, _ -> })
        recyclerView.adapter = adapter
    }

    private fun sortSegments(list: MutableList<TimeSegment>, dayOfWeek: Int) {
        list.sortWith(Comparator { s1, s2 ->
            val s1Active = s1.isActiveToday(checkDayInput = dayOfWeek)
            val s2Active = s2.isActiveToday(checkDayInput = dayOfWeek)
            if (s1Active != s2Active) return@Comparator if (s1Active) -1 else 1
            val start1 = s1.startHour * 60 + s1.startMinute
            val start2 = s2.startHour * 60 + s2.startMinute
            if (start1 != start2) return@Comparator start1 - start2
            (s1.endHour * 60 + s1.endMinute) - (s2.endHour * 60 + s2.endMinute)
        })
    }

    companion object {
        data class PreviewData(val todaySegments: List<TimeSegment>, val tomorrowSegments: List<TimeSegment>)

        internal fun resolvePreviewDataForDays(
            activeSegments: List<TimeSegment>,
            pendingSegments: List<TimeSegment>?,
            hasPending: Boolean,
            todayDayOfWeek: Int,
            tomorrowDayOfWeek: Int
        ): PreviewData {
            val today = activeSegments
                .filter { it.isEnabled && it.isActiveToday(checkDayInput = todayDayOfWeek) }
                .sortedWith(compareBy<TimeSegment> { it.startHour * 60 + it.startMinute }.thenBy { it.endHour * 60 + it.endMinute })
            val tomorrowSource = if (hasPending && !pendingSegments.isNullOrEmpty()) pendingSegments else activeSegments
            val tomorrow = tomorrowSource
                .filter { it.isEnabled && it.isActiveToday(checkDayInput = tomorrowDayOfWeek) }
                .sortedWith(compareBy<TimeSegment> { it.startHour * 60 + it.startMinute }.thenBy { it.endHour * 60 + it.endMinute })
            return PreviewData(todaySegments = today, tomorrowSegments = tomorrow)
        }
    }
}
