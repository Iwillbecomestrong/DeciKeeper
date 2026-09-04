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
package com.zhizhi.app.ui

import android.os.Bundle
import android.view.View
import android.widget.RadioGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.zhizhi.app.R
import com.zhizhi.app.data.TimeSegment
import com.zhizhi.app.manager.TimeRuleManager




class SchedulePreviewActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: TimeSegmentAdapter
    
    // Sources
    private val todaySegments = mutableListOf<TimeSegment>()
    private val tomorrowSegments = mutableListOf<TimeSegment>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Status Bar
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
             window.statusBarColor = android.graphics.Color.TRANSPARENT
             window.decorView.systemUiVisibility = android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE or android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        }
        
        setContentView(R.layout.activity_schedule_preview)
        
        // Insets
        val root = findViewById<View>(R.id.root_preview)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        
        findViewById<View>(R.id.btn_back).setOnClickListener { finish() }
        
        // Load Data
        loadData()
        
        // Init List
        recyclerView = findViewById(R.id.rv_preview)
        recyclerView.layoutManager = LinearLayoutManager(this)
        
        // Default Today
        val todayCal = java.util.Calendar.getInstance()
        val todayDay = todayCal.get(java.util.Calendar.DAY_OF_WEEK)
        val tomorrowCal = java.util.Calendar.getInstance().apply { add(java.util.Calendar.DAY_OF_YEAR, 1) }
        val tomorrowDay = tomorrowCal.get(java.util.Calendar.DAY_OF_WEEK)

        setupAdapter(todaySegments)
        
        val rgMode = findViewById<RadioGroup>(R.id.rg_view_mode)
        rgMode.setOnCheckedChangeListener { _, checkedId ->
            if (checkedId == R.id.rb_view_today) {
                setupAdapter(todaySegments)
            } else {
                setupAdapter(tomorrowSegments)
            }
        }
    }
    
    private fun loadData() {
        val todayCal = java.util.Calendar.getInstance()
        val todayDay = todayCal.get(java.util.Calendar.DAY_OF_WEEK)
        val tomorrowCal = java.util.Calendar.getInstance().apply { add(java.util.Calendar.DAY_OF_YEAR, 1) }
        val tomorrowDay = tomorrowCal.get(java.util.Calendar.DAY_OF_WEEK)
        val current = TimeRuleManager.getTimeSegments(this)
        val pending = TimeRuleManager.getPendingTimeSegments(this)
        val hasPending = TimeRuleManager.hasPendingRules(this)

        val previewData = resolvePreviewDataForDays(
            activeSegments = current,
            pendingSegments = pending,
            hasPending = hasPending,
            todayDayOfWeek = todayDay,
            tomorrowDayOfWeek = tomorrowDay
        )
        todaySegments.addAll(previewData.todaySegments)
        tomorrowSegments.addAll(previewData.tomorrowSegments)
    }
    
    private fun setupAdapter(data: List<TimeSegment>) {
        // ReadOnly = true
        adapter = TimeSegmentAdapter(data.toMutableList(), isReadOnly = true, onEdit = {}, onDelete = {}, onToggle = { _, _ -> })
        // adapter.previewTargetDay = targetDay // 🚀 [User Feedback] No need for day-based highlight
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

            val end1 = s1.endHour * 60 + s1.endMinute
            val end2 = s2.endHour * 60 + s2.endMinute
            end1 - end2
        })
    }

    companion object {
        data class PreviewData(
            val todaySegments: List<TimeSegment>,
            val tomorrowSegments: List<TimeSegment>
        )

        internal fun resolvePreviewDataForDays(
            activeSegments: List<TimeSegment>,
            pendingSegments: List<TimeSegment>?,
            hasPending: Boolean,
            todayDayOfWeek: Int,
            tomorrowDayOfWeek: Int
        ): PreviewData {
            val today = activeSegments
                .filter { it.isEnabled && it.isActiveToday(checkDayInput = todayDayOfWeek) }
                .sortedWith(compareBy<TimeSegment> { it.startHour * 60 + it.startMinute }
                    .thenBy { it.endHour * 60 + it.endMinute })

            val tomorrowSource = if (hasPending && !pendingSegments.isNullOrEmpty()) {
                pendingSegments
            } else {
                activeSegments
            }
            val tomorrow = tomorrowSource
                .filter { it.isEnabled && it.isActiveToday(checkDayInput = tomorrowDayOfWeek) }
                .sortedWith(compareBy<TimeSegment> { it.startHour * 60 + it.startMinute }
                    .thenBy { it.endHour * 60 + it.endMinute })

            return PreviewData(todaySegments = today, tomorrowSegments = tomorrow)
        }
    }
}
