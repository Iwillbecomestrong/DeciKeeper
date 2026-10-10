package com.decikeeper.app.ui.statistics

import android.app.AlertDialog
import android.app.TimePickerDialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
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
import com.decikeeper.app.data.statistics.StatsDateRange
import com.decikeeper.app.manager.statistics.HistoryRecordFilter
import com.decikeeper.app.manager.statistics.HistoryRecordKind
import com.decikeeper.app.manager.statistics.HistoryRetention
import com.decikeeper.app.manager.statistics.HistoryRetentionPolicy
import com.decikeeper.app.manager.statistics.LedgerEntry
import com.decikeeper.app.manager.statistics.LedgerFilter
import com.decikeeper.app.manager.statistics.RestLedger
import com.decikeeper.app.manager.CreditManager
import com.decikeeper.app.manager.TimeRuleManager
import com.decikeeper.app.ui.ThemeManager
import com.decikeeper.app.manager.statistics.StatisticsRepository
import com.decikeeper.app.manager.statistics.StatsDateRangeResolver

/**
 * 统计明细
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
    private lateinit var btnAlarmRange: Button
    private lateinit var tvAlarmSummary: TextView
    private lateinit var tvAlarmEmpty: TextView
    private lateinit var rvAlarm: RecyclerView
    private lateinit var alarmAdapter: AlarmHistoryAdapter

    private lateinit var containerRest: View
    private lateinit var tvRestEmpty: TextView
    private lateinit var btnRestRange: Button
    private lateinit var btnRestFilter: Button
    private lateinit var tvRestSummary: TextView
    private lateinit var rvRest: RecyclerView
    private lateinit var restAdapter: RestHistoryAdapter
    private lateinit var btnViewSplit: Button
    private lateinit var btnViewList: Button
    private lateinit var splitContainer: View
    private lateinit var tvSplitInHeader: TextView
    private lateinit var tvSplitOutHeader: TextView
    private lateinit var tvSplitInEmpty: TextView
    private lateinit var tvSplitOutEmpty: TextView
    private lateinit var tvSplitNote: TextView
    private lateinit var rvInflow: RecyclerView
    private lateinit var rvOutflow: RecyclerView
    private lateinit var inflowAdapter: CompactLedgerAdapter
    private lateinit var outflowAdapter: CompactLedgerAdapter
    // 默认分栏对照：入流在左、出流在右，一眼分清钱从哪来、到哪去
    private var restSplitMode = true

    private var currentTab: String = TAB_REST
    // 与统计页共用 StatsDateRange；null = 全部
    private var restDisplayRange: StatsDateRange? = StatsDateRange.Today
    private var alarmDisplayRange: StatsDateRange? = StatsDateRange.Today
    private val filterSheet by lazy { StatisticsFilterBottomSheet(this) }
    private var restLedgerFilter: LedgerFilter = LedgerFilter.ALL

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

        val initialTab = intent.getStringExtra(EXTRA_TARGET_TAB) ?: TAB_REST
        switchTab(initialTab)
    }

    private fun initViews() {
        btnBack = findViewById(R.id.btn_history_back)
        btnRetention = findViewById(R.id.btn_history_retention)
        tabAlarm = findViewById(R.id.tab_history_alarm)
        tabRest = findViewById(R.id.tab_history_rest)

        containerAlarm = findViewById(R.id.container_alarm_history)
        tvAlarmEmpty = findViewById(R.id.tv_alarm_empty)
        btnAlarmRange = findViewById(R.id.btn_history_alarm_range)
        tvAlarmSummary = findViewById(R.id.tv_history_alarm_summary)
        rvAlarm = findViewById(R.id.rv_alarm_history)

        containerRest = findViewById(R.id.container_rest_history)
        tvRestEmpty = findViewById(R.id.tv_rest_empty)
        btnRestRange = findViewById(R.id.btn_history_rest_range)
        btnRestFilter = findViewById(R.id.btn_history_rest_filter)
        tvRestSummary = findViewById(R.id.tv_history_rest_summary)
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

        btnViewSplit = findViewById(R.id.btn_rest_view_split)
        btnViewList = findViewById(R.id.btn_rest_view_list)
        splitContainer = findViewById(R.id.split_rest_history)
        tvSplitInHeader = findViewById(R.id.tv_split_in_header)
        tvSplitOutHeader = findViewById(R.id.tv_split_out_header)
        tvSplitInEmpty = findViewById(R.id.tv_split_in_empty)
        tvSplitOutEmpty = findViewById(R.id.tv_split_out_empty)
        tvSplitNote = findViewById(R.id.tv_split_note)
        rvInflow = findViewById(R.id.rv_rest_inflow)
        rvOutflow = findViewById(R.id.rv_rest_outflow)
        inflowAdapter = CompactLedgerAdapter(LedgerFilter.INFLOW) { record -> confirmDeleteRestRecord(record) }
        outflowAdapter = CompactLedgerAdapter(LedgerFilter.OUTFLOW) { record -> confirmDeleteRestRecord(record) }
        rvInflow.layoutManager = LinearLayoutManager(this)
        rvOutflow.layoutManager = LinearLayoutManager(this)
        rvInflow.adapter = inflowAdapter
        rvOutflow.adapter = outflowAdapter
    }

    private fun setupListeners() {
        btnBack.setOnClickListener { finish() }
        btnRetention.setOnClickListener { showRetentionSettingsDialog() }

        tabAlarm.setOnClickListener { switchTab(TAB_ALARM) }
        tabRest.setOnClickListener { switchTab(TAB_REST) }
        btnRestRange.setOnClickListener { showRestRangePicker() }
        btnAlarmRange.setOnClickListener { showAlarmRangePicker() }
        btnRestFilter.setOnClickListener { showRestFilterPicker() }
        btnViewSplit.setOnClickListener { setRestSplitMode(true) }
        btnViewList.setOnClickListener { setRestSplitMode(false) }
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
        // 起床属于自然日口径：按起床那天的自然日切（额度流水才按业务日切）
        val records = HistoryRecordFilter.filterWake(StatisticsRepository.getAllWakeRecords(this), alarmDisplayRange)
        btnAlarmRange.text = "显示范围：${rangeLabel(alarmDisplayRange)}"
        tvAlarmSummary.text = "共 ${records.size} 条（按自然日）"
        alarmAdapter.submitList(records)
        tvAlarmEmpty.text = if (alarmDisplayRange == null) "暂无起床打卡记录" else "该范围内暂无起床打卡记录"
        tvAlarmEmpty.isVisible = records.isEmpty()
        rvAlarm.isVisible = records.isNotEmpty()
    }

    private fun showAlarmRangePicker() {
        filterSheet.showHistoryRangePicker(alarmDisplayRange) {
            alarmDisplayRange = it
            loadAlarmData()
        }
    }

    private fun loadRestData() {
        // 额度流水属于时间规则：按业务日（生效时间为界）切
        val businessDayStartMinutes = TimeRuleManager.getRefreshTime(this)
            .split(":").let { (it.getOrNull(0)?.toIntOrNull() ?: 8) * 60 + (it.getOrNull(1)?.toIntOrNull() ?: 0) }
        val inRange = HistoryRecordFilter.filterQuota(
            StatisticsRepository.getAllRestRecords(this),
            restDisplayRange,
            businessDayStartMinutes = businessDayStartMinutes
        )
        // 入流 / 出流汇总只看显示范围，不受类型筛选影响
        val summary = RestLedger.summarize(inRange)
        tvRestSummary.text = if (restDisplayRange == StatsDateRange.Today) {
            // 「今天」是单个业务日：入流 = 出流 + 当前结余，直接核对；多日范围里未用完的额度会作废，不套这个公式
            RestLedger.reconcileText(
                summary,
                RestLedger.reconcile(summary, CreditManager.getRemainingMinutes(this), CreditManager.getRemainingUsableCount(this))
            )
        } else {
            RestLedger.summaryText(summary)
        }
        val records = RestLedger.filter(inRange, restLedgerFilter)
        // 只有「全部」才插入缺口提示（相邻两笔必须是真实相邻，筛选后就不相邻了）
        val entries = RestLedger.buildEntries(
            records,
            includeGaps = restLedgerFilter == LedgerFilter.ALL,
            businessDayStartMinutes = businessDayStartMinutes
        )
        btnRestRange.text = "显示范围：${rangeLabel(restDisplayRange)}"
        btnRestFilter.text = "类型：${restLedgerFilter.displayName}"
        styleViewToggle()

        if (restSplitMode) {
            bindSplit(records)
            return
        }
        splitContainer.isVisible = false
        tvSplitNote.isVisible = false
        restAdapter.filter = restLedgerFilter
        restAdapter.submitList(entries)
        tvRestEmpty.isVisible = records.isEmpty()
        rvRest.isVisible = records.isNotEmpty()
    }

    private fun setRestSplitMode(split: Boolean) {
        if (restSplitMode == split) return
        restSplitMode = split
        loadRestData()
    }

    private fun styleViewToggle() {
        btnViewSplit.setBackgroundResource(if (restSplitMode) R.drawable.bg_zz_option_btn_selected else R.drawable.bg_zz_option_btn)
        btnViewSplit.setTextColor(if (restSplitMode) Color.WHITE else ThemeManager.getColorAttr(this, R.attr.zzText))
        btnViewList.setBackgroundResource(if (!restSplitMode) R.drawable.bg_zz_option_btn_selected else R.drawable.bg_zz_option_btn)
        btnViewList.setTextColor(if (!restSplitMode) Color.WHITE else ThemeManager.getColorAttr(this, R.attr.zzText))
    }

    /** 分栏对照：入流的放左边、出流的放右边，栏头写各自合计。 */
    private fun bindSplit(records: List<RestUsageRecord>) {
        rvRest.isVisible = false
        val columns = RestLedger.splitByFlow(records)
        val showDate = restDisplayRange != StatsDateRange.Today
        inflowAdapter.showDate = showDate
        outflowAdapter.showDate = showDate
        inflowAdapter.submitList(columns.inflow)
        outflowAdapter.submitList(columns.outflow)

        val (inMin, inCount) = RestLedger.columnTotal(columns.inflow, LedgerFilter.INFLOW)
        val (advMin, advCount) = RestLedger.advanceTotal(records)
        val (outMin, outCount) = RestLedger.columnTotal(columns.outflow, LedgerFilter.OUTFLOW)
        tvSplitInHeader.text = "入流（额度增加）\n" + RestLedger.flowAmountText(inMin, inCount) +
            if (advMin > 0.0 || advCount > 0.0) "\n另含借用提前发放 ${RestLedger.flowAmountText(advMin, advCount)}（只计入对账）" else ""
        tvSplitOutHeader.text = "出流（额度减少）\n" + RestLedger.flowAmountText(-outMin, -outCount)
        tintHeader(tvSplitInHeader, R.attr.zzSuccessSoft)
        tintHeader(tvSplitOutHeader, R.attr.zzWarningSoft)

        tvSplitInEmpty.isVisible = columns.inflow.isEmpty()
        tvSplitOutEmpty.isVisible = columns.outflow.isEmpty()
        tvRestEmpty.isVisible = records.isEmpty()
        splitContainer.isVisible = records.isNotEmpty()
        tvSplitNote.isVisible = columns.neither > 0 && records.isNotEmpty()
        tvSplitNote.text = "另有 ${columns.neither} 条不动额度的记录（免费休息、特权等），在「逐笔列表」里查看"
    }

    private fun tintHeader(header: TextView, colorAttr: Int) {
        header.backgroundTintList = android.content.res.ColorStateList.valueOf(ThemeManager.getColorAttr(this, colorAttr))
    }

    private fun showRestRangePicker() {
        filterSheet.showHistoryRangePicker(restDisplayRange) {
            restDisplayRange = it
            loadRestData()
        }
    }

    private fun rangeLabel(range: StatsDateRange?): String {
        if (range == null) return "全部"
        val resolved = StatsDateRangeResolver.resolveRange(range)
        return if (range is StatsDateRange.Custom) "${resolved.displayName} ${resolved.dateSubText}" else resolved.displayName
    }

    /**
     * 类型弹窗：左列入流、右列出流（与分栏对照一致），列头可点 = 选这一整个方向；
     * 「全部」「特权」不分方向，放在最上面。
     */
    private fun showRestFilterPicker() {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_ledger_filter, null)
        val dialog = AlertDialog.Builder(this)
            .setTitle("额度流水类型")
            .setView(view)
            .setNegativeButton("取消", null)
            .create()

        fun optionButton(filter: LedgerFilter, label: String, header: Boolean): Button {
            val selected = restLedgerFilter == filter
            return androidx.appcompat.widget.AppCompatButton(this).apply {
                text = label
                isAllCaps = false
                textSize = if (header) 13f else 12f
                if (header) setTypeface(typeface, Typeface.BOLD)
                setBackgroundResource(if (selected) R.drawable.bg_zz_option_btn_selected else R.drawable.bg_zz_option_btn)
                setTextColor(if (selected) Color.WHITE else ThemeManager.getColorAttr(this@StatsHistoryActivity, R.attr.zzText))
                // 半栏宽度放不下的长名字自动缩小，不截断
                androidx.core.widget.TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(
                    this, 9, textSize.toInt(), 1, android.util.TypedValue.COMPLEX_UNIT_PX
                )
                maxLines = 1
                setOnClickListener {
                    restLedgerFilter = filter
                    dialog.dismiss()
                    loadRestData()
                }
            }
        }

        fun lp(first: Boolean, weighted: Boolean = false) = LinearLayout.LayoutParams(
            if (weighted) 0 else ViewGroup.LayoutParams.MATCH_PARENT,
            (40 * resources.displayMetrics.density).toInt(),
            if (weighted) 1f else 0f
        ).apply { if (!first) { if (weighted) marginStart = (8 * resources.displayMetrics.density).toInt() else topMargin = (6 * resources.displayMetrics.density).toInt() } }

        val top = view.findViewById<LinearLayout>(R.id.row_ledger_filter_top)
        top.addView(optionButton(LedgerFilter.ALL, LedgerFilter.ALL.displayName, false), lp(true, true))
        top.addView(optionButton(LedgerFilter.PRIVILEGE, LedgerFilter.PRIVILEGE.displayName, false), lp(false, true))

        val colIn = view.findViewById<LinearLayout>(R.id.col_ledger_filter_in)
        colIn.addView(optionButton(LedgerFilter.INFLOW, "入流 · 全部", true), lp(true))
        RestLedger.inflowTypeFilters.forEach { colIn.addView(optionButton(it, it.displayName, false), lp(false)) }

        val colOut = view.findViewById<LinearLayout>(R.id.col_ledger_filter_out)
        colOut.addView(optionButton(LedgerFilter.OUTFLOW, "出流 · 全部", true), lp(true))
        RestLedger.outflowTypeFilters.forEach { colOut.addView(optionButton(it, it.displayName, false), lp(false)) }

        dialog.show()
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
    ) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        private var items: List<LedgerEntry> = emptyList()
        var filter: LedgerFilter = LedgerFilter.ALL
        private val expandedIds = mutableSetOf<String>()

        fun submitList(newItems: List<LedgerEntry>) {
            items = newItems
            notifyDataSetChanged()
        }

        override fun getItemViewType(position: Int): Int =
            if (items[position] is LedgerEntry.Gap) TYPE_GAP else TYPE_ROW

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inflater = LayoutInflater.from(parent.context)
            return if (viewType == TYPE_GAP) {
                GapViewHolder(inflater.inflate(R.layout.item_history_rest_gap, parent, false))
            } else {
                ViewHolder(inflater.inflate(R.layout.item_history_rest_card, parent, false))
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val entry = items[position]) {
                is LedgerEntry.Gap -> (holder as GapViewHolder).bind(entry)
                is LedgerEntry.Row -> {
                    val item = entry.record
                    val isExpanded = expandedIds.contains(item.id)
                    (holder as ViewHolder).bind(entry, isExpanded, filter, onDeleteClicked) {
                        if (isExpanded) {
                            expandedIds.remove(item.id)
                        } else {
                            expandedIds.add(item.id)
                        }
                        notifyItemChanged(position)
                    }
                }
            }
        }

        override fun getItemCount(): Int = items.size

        class GapViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            private val tvGap: TextView = view.findViewById(R.id.tv_history_rest_gap)

            fun bind(gap: LedgerEntry.Gap) {
                tvGap.text = RestLedger.gapText(gap)
            }
        }

        private companion object {
            const val TYPE_ROW = 0
            const val TYPE_GAP = 1
        }

        class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            private val badge: TextView = view.findViewById(R.id.tv_history_rest_type_badge)
            private val tvDate: TextView = view.findViewById(R.id.tv_history_rest_date)
            private val tvCost: TextView = view.findViewById(R.id.tv_history_rest_cost)
            private val tvBefore: TextView = view.findViewById(R.id.tv_history_rest_before)
            private val tvBalance: TextView = view.findViewById(R.id.tv_history_rest_balance)
            private val tvArrow: TextView = view.findViewById(R.id.tv_history_rest_arrow)
            private val layoutDetail: LinearLayout = view.findViewById(R.id.layout_history_rest_detail)

            fun bind(
                entry: LedgerEntry.Row,
                isExpanded: Boolean,
                filter: LedgerFilter,
                onDelete: (RestUsageRecord) -> Unit,
                onToggle: () -> Unit
            ) {
                val item = entry.record
                badge.text = item.typeName
                tvDate.text = item.dateLabel

                val (quotaDelta, countDelta) = RestLedger.displayedDelta(item, filter)
                val balanceText = "${RestLedger.formatAmount(item.balanceMinutes)}m / ${RestLedger.formatAmount(RestLedger.balanceCountInTimes(item))}次"
                tvCost.text = "${RestLedger.poolLabel(item)} ${RestLedger.formatDelta(quotaDelta, "m")} / 次数 ${RestLedger.formatDelta(countDelta, "次")}"
                // 左变动前、右变动后；特权是独立池子，不动休息额度，结余没有变化可言
                tvBefore.text = if (entry.beforeMinutes != null && entry.beforeCount != null) {
                    "变动前: ${RestLedger.formatAmount(entry.beforeMinutes)}m / ${RestLedger.formatAmount(entry.beforeCount)}次"
                } else ""
                tvBalance.text = if (item.type == RestLedger.TYPE_PRIVILEGE) "不占用休息额度" else "变动后结余: $balanceText"

                // 标题行已经说完的简单记录没有详情，不显示 ▼、点击不展开
                val lines = RestLedger.detailLines(item)
                val expandable = lines.isNotEmpty()
                val open = expandable && isExpanded
                tvArrow.isVisible = expandable
                tvArrow.text = if (open) "▲" else "▼"
                layoutDetail.isVisible = open
                if (open) renderLedgerDetail(layoutDetail, lines)

                itemView.setOnClickListener(if (expandable) View.OnClickListener { onToggle() } else null)
                // 删除入口：长按任意一条（简单记录没有展开面板，删除统一放长按）
                itemView.setOnLongClickListener {
                    onDelete(item)
                    true
                }
            }
        }
    }
}

/** 分栏对照里一栏的紧凑卡片：类型标签 + 金额 + 时间 + 结余，点开看详情，长按删除。 */
private class CompactLedgerAdapter(
    private val direction: LedgerFilter,
    private val onDeleteClicked: (RestUsageRecord) -> Unit
) : RecyclerView.Adapter<CompactLedgerAdapter.ViewHolder>() {

    private var items: List<RestUsageRecord> = emptyList()
    var showDate: Boolean = false
    private val expandedIds = mutableSetOf<String>()

    fun submitList(newItems: List<RestUsageRecord>) {
        items = newItems
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder =
        ViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.item_history_rest_compact, parent, false))

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        val lines = RestLedger.detailLines(item)
        val open = lines.isNotEmpty() && expandedIds.contains(item.id)
        holder.bind(item, direction, showDate, lines, open, onDeleteClicked) {
            if (!expandedIds.add(item.id)) expandedIds.remove(item.id)
            notifyItemChanged(position)
        }
    }

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val badge: TextView = view.findViewById(R.id.tv_compact_badge)
        private val amount: TextView = view.findViewById(R.id.tv_compact_amount)
        private val time: TextView = view.findViewById(R.id.tv_compact_time)
        private val balance: TextView = view.findViewById(R.id.tv_compact_balance)
        private val arrow: TextView = view.findViewById(R.id.tv_compact_arrow)
        private val detail: LinearLayout = view.findViewById(R.id.layout_compact_detail)

        fun bind(
            item: RestUsageRecord,
            direction: LedgerFilter,
            showDate: Boolean,
            lines: List<RestLedger.DetailLine>,
            open: Boolean,
            onDelete: (RestUsageRecord) -> Unit,
            onToggle: () -> Unit
        ) {
            badge.text = item.typeName
            val (minutes, count) = RestLedger.displayedDelta(item, direction)
            amount.text = RestLedger.flowAmountText(minutes, count)
            // dateLabel 形如「2026-10-10 12:27」：当天范围只留时刻，多日范围留「10-10 12:27」
            time.text = if (showDate) item.dateLabel.substringAfter('-', item.dateLabel) else item.dateLabel.substringAfterLast(' ')
            balance.text = if (item.type == RestLedger.TYPE_PRIVILEGE) "不占用休息额度"
            else "结余 ${RestLedger.formatAmount(item.balanceMinutes)}m / ${RestLedger.formatAmount(RestLedger.balanceCountInTimes(item))}次"

            arrow.isVisible = lines.isNotEmpty()
            arrow.text = if (open) "▲" else "▼"
            detail.isVisible = open
            if (open) renderLedgerDetail(detail, lines)
            itemView.setOnClickListener(if (lines.isNotEmpty()) View.OnClickListener { onToggle() } else null)
            itemView.setOnLongClickListener {
                onDelete(item)
                true
            }
        }
    }
}

private fun renderLedgerDetail(layoutDetail: LinearLayout, lines: List<RestLedger.DetailLine>) {
    layoutDetail.removeAllViews()
    val context = layoutDetail.context
    val density = context.resources.displayMetrics.density
    val textColor = ThemeManager.getColorAttr(context, R.attr.zzText)
    val mutedColor = ThemeManager.getColorAttr(context, R.attr.zzMuted)
    val lineColor = ThemeManager.getColorAttr(context, R.attr.zzLine)

    fun cell(text: String, weight: Float, gravity: Int, color: Int, sizeSp: Float, bold: Boolean) = TextView(context).apply {
        this.text = text
        this.gravity = gravity
        setTextColor(color)
        textSize = sizeSp
        if (bold) setTypeface(typeface, Typeface.BOLD)
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, weight)
    }

    lines.forEach { line ->
        if (line.total) {
            layoutDetail.addView(View(context).apply {
                setBackgroundColor(lineColor)
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (1 * density).toInt().coerceAtLeast(1)).apply {
                    topMargin = (6 * density).toInt()
                    bottomMargin = (6 * density).toInt()
                }
            })
        }
        val color = if (line.muted) mutedColor else textColor
        val size = if (line.muted) 11f else 12f
        if (line.isFullRow) {
            layoutDetail.addView(cell(line.label, 1f, Gravity.START, color, size, false).apply {
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            })
        } else {
            // 时长与次数左右并排、同等重要（版式同主页）
            layoutDetail.addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                addView(cell(line.label, 1.3f, Gravity.START, color, size, line.total))
                addView(cell(line.minutes, 1f, Gravity.END, color, size, line.total))
                addView(cell(line.count, 1f, Gravity.END, color, size, line.total))
            })
        }
    }
}
