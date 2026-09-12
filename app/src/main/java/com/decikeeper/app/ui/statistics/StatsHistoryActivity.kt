package com.decikeeper.app.ui.statistics

import android.app.AlertDialog
import android.app.TimePickerDialog
import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.decikeeper.app.R
import com.decikeeper.app.data.statistics.AlarmWakeRecord
import com.decikeeper.app.data.statistics.RestUsageRecord
import com.decikeeper.app.manager.statistics.HistoryDisplayRange
import com.decikeeper.app.manager.statistics.HistoryRecordFilter
import com.decikeeper.app.manager.statistics.HistoryRecordKind
import com.decikeeper.app.manager.statistics.HistoryRetention
import com.decikeeper.app.manager.statistics.HistoryRetentionPolicy
import com.decikeeper.app.ui.ThemeManager
import com.decikeeper.app.manager.statistics.StatisticsRepository

/**
 * 历史记录中心
 * 聚合「起床打卡历史」与「休息额度流水」双页签，支持用户查看全部明细，
 * 并支持手动修正计划/完成时间、删除打卡记录、展开查看流水详情及删除流水。
 */
class StatsHistoryActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_TARGET_TAB = "extra_target_tab"
        const val TAB_ALARM = "ALARM"
        const val TAB_REST = "REST"
    }

    private lateinit var btnBack: ImageView
    private lateinit var btnRetention: TextView
    private lateinit var tabAlarm: Button
    private lateinit var tabRest: Button

    private lateinit var containerAlarm: View
    private lateinit var tvAlarmEmpty: TextView
    private lateinit var rvAlarm: RecyclerView
    private lateinit var alarmAdapter: AlarmHistoryAdapter

    private lateinit var containerRest: View
    private lateinit var tvRestEmpty: TextView
    private lateinit var btnRestRange: Button
    private lateinit var rvRest: RecyclerView
    private lateinit var restAdapter: RestHistoryAdapter

    private var currentTab: String = TAB_ALARM
    private var restDisplayRange: HistoryDisplayRange = HistoryDisplayRange.TODAY

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(ThemeManager.themeRes(this))
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_stats_history)

        val root = findViewById<View>(R.id.root_stats_history)
        val initialPaddingTop = root.paddingTop
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val topInset = insets.getInsets(
                WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.displayCutout()
            ).top
            view.setPadding(view.paddingLeft, initialPaddingTop + topInset, view.paddingRight, view.paddingBottom)
            insets
        }
        ViewCompat.requestApplyInsets(root)

        initViews()
        setupListeners()

        val initialTab = intent.getStringExtra(EXTRA_TARGET_TAB) ?: TAB_ALARM
        switchTab(initialTab)
    }

    private fun initViews() {
        btnBack = findViewById(R.id.btn_history_back)
        btnRetention = findViewById(R.id.btn_history_retention)
        tabAlarm = findViewById(R.id.tab_history_alarm)
        tabRest = findViewById(R.id.tab_history_rest)

        containerAlarm = findViewById(R.id.container_alarm_history)
        tvAlarmEmpty = findViewById(R.id.tv_alarm_empty)
        rvAlarm = findViewById(R.id.rv_alarm_history)

        containerRest = findViewById(R.id.container_rest_history)
        tvRestEmpty = findViewById(R.id.tv_rest_empty)
        btnRestRange = findViewById(R.id.btn_history_rest_range)
        rvRest = findViewById(R.id.rv_rest_history)

        rvAlarm.layoutManager = LinearLayoutManager(this)
        alarmAdapter = AlarmHistoryAdapter(
            onItemClicked = { record -> showAlarmActionDialog(record) }
        )
        rvAlarm.adapter = alarmAdapter

        rvRest.layoutManager = LinearLayoutManager(this)
        restAdapter = RestHistoryAdapter(
            onDeleteClicked = { record -> confirmDeleteRestRecord(record) }
        )
        rvRest.adapter = restAdapter
    }

    private fun setupListeners() {
        btnBack.setOnClickListener { finish() }
        btnRetention.setOnClickListener { showRetentionSettingsDialog() }

        tabAlarm.setOnClickListener { switchTab(TAB_ALARM) }
        tabRest.setOnClickListener { switchTab(TAB_REST) }
        btnRestRange.setOnClickListener { showRestRangePicker() }
    }

    private fun switchTab(target: String) {
        currentTab = target
        val isAlarm = target == TAB_ALARM

        tabAlarm.backgroundTintList = null
        tabRest.backgroundTintList = null

        tabAlarm.setBackgroundResource(if (isAlarm) R.drawable.bg_zz_option_btn_selected else R.drawable.bg_zz_option_btn)
        tabAlarm.setTextColor(if (isAlarm) Color.WHITE else ThemeManager.getColorAttr(this, R.attr.zzText))

        tabRest.setBackgroundResource(if (!isAlarm) R.drawable.bg_zz_option_btn_selected else R.drawable.bg_zz_option_btn)
        tabRest.setTextColor(if (!isAlarm) Color.WHITE else ThemeManager.getColorAttr(this, R.attr.zzText))

        containerAlarm.isVisible = isAlarm
        containerRest.isVisible = !isAlarm

        if (isAlarm) {
            loadAlarmData()
        } else {
            loadRestData()
        }
    }

    private fun loadAlarmData() {
        val records = StatisticsRepository.getAllWakeRecords(this)
        alarmAdapter.submitList(records)
        tvAlarmEmpty.isVisible = records.isEmpty()
        rvAlarm.isVisible = records.isNotEmpty()
    }

    private fun loadRestData() {
        val records = HistoryRecordFilter.filterQuota(
            StatisticsRepository.getAllRestRecords(this),
            restDisplayRange
        )
        btnRestRange.text = "显示范围：${restDisplayRange.displayName}"
        restAdapter.submitList(records)
        tvRestEmpty.isVisible = records.isEmpty()
        rvRest.isVisible = records.isNotEmpty()
    }

    private fun showRestRangePicker() {
        val options = HistoryDisplayRange.entries.toTypedArray()
        val selected = options.indexOf(restDisplayRange).coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle("额度流水显示范围")
            .setSingleChoiceItems(options.map { it.displayName }.toTypedArray(), selected) { dialog, which ->
                restDisplayRange = options[which]
                dialog.dismiss()
                loadRestData()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showRetentionSettingsDialog() {
        val wake = HistoryRetentionPolicy.get(this, HistoryRecordKind.WAKE)
        val quota = HistoryRetentionPolicy.get(this, HistoryRecordKind.QUOTA)
        AlertDialog.Builder(this)
            .setTitle("历史记录保留")
            .setItems(arrayOf("起床记录（当前：${wake.displayName}）", "额度流水（当前：${quota.displayName}）")) { _, which ->
                showRetentionPicker(if (which == 0) HistoryRecordKind.WAKE else HistoryRecordKind.QUOTA)
            }
            .setMessage("起床记录默认保留 3 年；额度流水默认保留 3 个月。")
            .setNegativeButton("关闭", null)
            .show()
    }

    private fun showRetentionPicker(kind: HistoryRecordKind) {
        val current = HistoryRetentionPolicy.get(this, kind)
        val options = HistoryRetentionPolicy.options(kind)
        val labels = options.map { it.displayName }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(if (kind == HistoryRecordKind.WAKE) "起床记录保留多久" else "额度流水保留多久")
            .setSingleChoiceItems(labels, options.indexOf(current)) { dialog, which ->
                val selected = options[which]
                dialog.dismiss()
                val apply = {
                    HistoryRetentionPolicy.set(this, kind, selected)
                    if (kind == HistoryRecordKind.WAKE) {
                        com.decikeeper.app.manager.statistics.AlarmWakeStatsRecorder.purgeExpired(this)
                    } else {
                        com.decikeeper.app.manager.statistics.RestStatsRecorder.purgeExpired(this)
                    }
                    if (currentTab == TAB_ALARM) loadAlarmData() else loadRestData()
                    Toast.makeText(this, "已设置保留 ${selected.displayName}", Toast.LENGTH_SHORT).show()
                }
                if (retentionRank(selected) < retentionRank(current)) {
                    AlertDialog.Builder(this)
                        .setTitle("确认清理旧记录")
                        .setMessage("缩短保留期会删除更早的${if (kind == HistoryRecordKind.WAKE) "起床记录" else "额度流水"}，此操作不可恢复。")
                        .setPositiveButton("确认清理") { _, _ -> apply() }
                        .setNegativeButton("取消", null)
                        .show()
                } else {
                    apply()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun retentionRank(retention: HistoryRetention): Int = when (retention) {
        HistoryRetention.THIRTY_DAYS -> 1
        HistoryRetention.THREE_MONTHS -> 2
        HistoryRetention.ONE_YEAR -> 3
        HistoryRetention.THREE_YEARS -> 4
        HistoryRetention.FOREVER -> 5
    }

    private fun showAlarmActionDialog(record: AlarmWakeRecord) {
        val options = arrayOf("修改计划响铃时间", "修改打卡完成时间", "删除此条打卡记录")
        AlertDialog.Builder(this)
            .setTitle("${record.dateLabel} 打卡记录")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> pickPlannedTime(record)
                    1 -> pickCompletedTime(record)
                    2 -> confirmDeleteAlarmRecord(record)
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun pickPlannedTime(record: AlarmWakeRecord) {
        val curH = record.alarmMinuteOfDay / 60
        val curM = record.alarmMinuteOfDay % 60
        TimePickerDialog(this, { _, hourOfDay, minute ->
            val newMinuteOfDay = hourOfDay * 60 + minute
            val updated = record.copy(alarmMinuteOfDay = newMinuteOfDay)
            StatisticsRepository.updateWakeRecord(this, updated)
            loadAlarmData()
            Toast.makeText(this, "计划响铃时间已修改为 %02d:%02d".format(hourOfDay, minute), Toast.LENGTH_SHORT).show()
        }, curH, curM, true).show()
    }

    private fun pickCompletedTime(record: AlarmWakeRecord) {
        val curH = record.firstStageCompletedMinute / 60
        val curM = record.firstStageCompletedMinute % 60
        TimePickerDialog(this, { _, hourOfDay, minute ->
            val newCompletedMinute = hourOfDay * 60 + minute
            val diff = (newCompletedMinute - record.alarmMinuteOfDay).coerceAtLeast(0)
            val updated = record.copy(
                firstStageCompletedMinute = newCompletedMinute,
                firstStageDurationMinutes = diff,
                totalFlowDurationMinutes = maxOf(record.totalFlowDurationMinutes, diff)
            )
            StatisticsRepository.updateWakeRecord(this, updated)
            loadAlarmData()
            Toast.makeText(this, "完成时间已修改为 %02d:%02d".format(hourOfDay, minute), Toast.LENGTH_SHORT).show()
        }, curH, curM, true).show()
    }

    private fun confirmDeleteAlarmRecord(record: AlarmWakeRecord) {
        AlertDialog.Builder(this)
            .setTitle("确认删除")
            .setMessage("确定要删除 ${record.dateLabel} 的起床打卡记录吗？")
            .setPositiveButton("删除") { _, _ ->
                StatisticsRepository.deleteWakeRecord(this, record.id)
                loadAlarmData()
                Toast.makeText(this, "已删除该条起床记录", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun confirmDeleteRestRecord(record: RestUsageRecord) {
        AlertDialog.Builder(this)
            .setTitle("确认删除")
            .setMessage("确定要删除此条额度流水记录（${record.typeName}，${record.dateLabel}）吗？")
            .setPositiveButton("删除") { _, _ ->
                StatisticsRepository.deleteRestRecord(this, record.id)
                loadRestData()
                Toast.makeText(this, "已删除该条额度流水", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ==========================================
    // 起床打卡适配器
    // ==========================================
    private class AlarmHistoryAdapter(
        private val onItemClicked: (AlarmWakeRecord) -> Unit
    ) : RecyclerView.Adapter<AlarmHistoryAdapter.ViewHolder>() {

        private var items: List<AlarmWakeRecord> = emptyList()

        fun submitList(newItems: List<AlarmWakeRecord>) {
            items = newItems
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_history_alarm_card, parent, false)
            return ViewHolder(v)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            holder.bind(items[position], onItemClicked)
        }

        override fun getItemCount(): Int = items.size

        class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            private val tvDate: TextView = view.findViewById(R.id.tv_history_alarm_date)
            private val tvPlan: TextView = view.findViewById(R.id.tv_history_alarm_plan)
            private val tvStage1: TextView = view.findViewById(R.id.tv_history_alarm_stage1_time)
            private val tvDurations: TextView = view.findViewById(R.id.tv_history_alarm_durations)

            fun bind(item: AlarmWakeRecord, onClick: (AlarmWakeRecord) -> Unit) {
                tvDate.text = item.dateLabel
                tvPlan.text = String.format("%02d:%02d", item.alarmMinuteOfDay / 60, item.alarmMinuteOfDay % 60)
                tvStage1.text = String.format("%02d:%02d", item.firstStageCompletedMinute / 60, item.firstStageCompletedMinute % 60)
                tvDurations.text = "一阶段 ${item.firstStageDurationMinutes}m / 全程 ${item.totalFlowDurationMinutes}m"
                itemView.setOnClickListener { onClick(item) }
            }
        }
    }

    // ==========================================
    // 额度流水适配器
    // ==========================================
    private class RestHistoryAdapter(
        private val onDeleteClicked: (RestUsageRecord) -> Unit
    ) : RecyclerView.Adapter<RestHistoryAdapter.ViewHolder>() {

        private var items: List<RestUsageRecord> = emptyList()
        private val expandedIds = mutableSetOf<String>()

        fun submitList(newItems: List<RestUsageRecord>) {
            items = newItems
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_history_rest_card, parent, false)
            return ViewHolder(v)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = items[position]
            val isExpanded = expandedIds.contains(item.id)
            holder.bind(item, isExpanded, onDeleteClicked) {
                if (isExpanded) {
                    expandedIds.remove(item.id)
                } else {
                    expandedIds.add(item.id)
                }
                notifyItemChanged(position)
            }
        }

        override fun getItemCount(): Int = items.size

        class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            private val badge: TextView = view.findViewById(R.id.tv_history_rest_type_badge)
            private val tvDate: TextView = view.findViewById(R.id.tv_history_rest_date)
            private val tvCost: TextView = view.findViewById(R.id.tv_history_rest_cost)
            private val tvBalance: TextView = view.findViewById(R.id.tv_history_rest_balance)
            private val tvArrow: TextView = view.findViewById(R.id.tv_history_rest_arrow)

            private val layoutDetail: View = view.findViewById(R.id.layout_history_rest_detail)
            private val tvDetailReason: TextView = view.findViewById(R.id.tv_history_rest_detail_reason)
            private val tvDetailDuration: TextView = view.findViewById(R.id.tv_history_rest_detail_duration)
            private val tvDetailCost: TextView = view.findViewById(R.id.tv_history_rest_detail_cost)
            private val tvDetailBalance: TextView = view.findViewById(R.id.tv_history_rest_detail_balance)
            private val btnDelete: TextView = view.findViewById(R.id.btn_delete_rest_record)

            fun bind(
                item: RestUsageRecord,
                isExpanded: Boolean,
                onDelete: (RestUsageRecord) -> Unit,
                onToggle: () -> Unit
            ) {
                badge.text = item.typeName
                tvDate.text = item.dateLabel

                val quotaDelta = -item.costMinutes
                val countDelta = -item.costCount
                tvCost.text = "额度 ${formatDelta(quotaDelta, "m")} / 次数 ${formatDelta(countDelta, "次")}"
                tvBalance.text = "变动后结余: ${item.balanceMinutes.toInt()}m / ${item.balanceCount}次"

                tvArrow.text = if (isExpanded) "▲" else "▼"
                layoutDetail.isVisible = isExpanded

                if (isExpanded) {
                    tvDetailReason.text = if (item.reason.isNotBlank()) "原因说明: ${item.reason}" else "原因说明: 无"
                    tvDetailDuration.text = "申请时长: ${item.requestedMinutes}m  |  实际使用: ${item.actualMinutes}m"
                    tvDetailCost.text = "额度变化: ${formatDelta(quotaDelta, "m")}  |  次数变化: ${formatDelta(countDelta, "次")}"
                    tvDetailBalance.text = "当前结余: ${item.balanceMinutes.toInt()} 分钟 / ${item.balanceCount} 次"
                    btnDelete.setOnClickListener { onDelete(item) }
                }

                itemView.setOnClickListener { onToggle() }
            }

            private fun formatDelta(value: Double, unit: String): String {
                val amount = if (value % 1.0 == 0.0) value.toInt().toString() else "%.1f".format(value)
                return when {
                    value > 0 -> "+${amount}${unit}"
                    value < 0 -> "${amount}${unit}"
                    else -> "0${unit}"
                }
            }
        }
    }
}
