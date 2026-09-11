/**
 * =================================================================================
 * [Context Header]
 * * @file: TimeConfigFragment.kt
 * * @role: UI
 * * @description: 勿扰时段配置界面（类似应用时间窗，点击卡片弹窗编辑，即时保存）
 * =================================================================================
 */
package com.decikeeper.app.ui

import android.app.TimePickerDialog
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.cardview.widget.CardView
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.decikeeper.app.R
import com.decikeeper.app.data.AdverseAnalysis
import com.decikeeper.app.data.SegmentAdverseAnalysis
import com.decikeeper.app.data.SegmentBlacklist
import com.decikeeper.app.data.TimeSegment
import com.decikeeper.app.data.UsableCount
import com.decikeeper.app.data.formatUsableCountHalfUnits
import com.decikeeper.app.manager.BoundaryResult
import com.decikeeper.app.manager.ChangeLogManager
import com.decikeeper.app.manager.CreditManager
import com.decikeeper.app.manager.MinuteBoundary
import com.decikeeper.app.manager.TimeRuleManager
import com.decikeeper.app.service.MonitorService














class TimeConfigFragment : Fragment() {

    companion object {
        internal fun shouldReloadWhenVisibilityChanged(hidden: Boolean): Boolean = !hidden

        /**
         * WP-04-T02: parse HH:mm refresh text to minute-of-day.
         * Mirrors `TimeRuleManager.parseHHMMToMinutes` (which is private to
         * the manager) and `GlobalParamsActivity.parseRefreshTimeToMinutes`.
         * Returns null when the input is malformed.
         */
        internal fun parseRefreshMinutes(time: String): Int? {
            if (time.isBlank()) return null
            val parts = time.split(":")
            if (parts.size != 2) return null
            val h = parts[0].toIntOrNull() ?: return null
            val m = parts[1].toIntOrNull() ?: return null
            if (h < 0 || h > 24) return null
            if (m < 0 || m > 59) return null
            val total = h * 60 + m
            if (total > 24 * 60) return null
            return total
        }

        /**
         * TASK-05 (segment-blacklist)：名单页结果回写为 TimeSegment.listApps。
         * followGlobal=true → null（重新跟随全局黑名单）；否则用选择列表（空列表=不锁任何）。
         */
        internal fun resolveListAppsFromResult(followGlobal: Boolean, selection: List<String>): List<String>? =
            if (followGlobal) null else selection

        /**
         * TASK-05：本时段黑名单摘要文案（供弹窗 tv_segment_list_summary 与列表摘要复用）。
         *   null → 跟随全局黑名单；空 → 本时段不锁任何应用；非空 → 独立管理 N 个应用。
         */
        internal fun segmentListSummary(listApps: List<String>?): String = when {
            listApps == null -> "跟随全局黑/白名单"
            listApps.isEmpty() -> "本时段不锁任何应用"
            else -> "独立管理 ${listApps.size} 个应用"
        }

        /** 模式感知摘要：白名单模式显示"白名单 N 个应用"（补集还原后计数）。 */
        internal fun segmentListSummary(context: android.content.Context, segment: TimeSegment): String = when {
            segment.listApps == null -> "跟随全局黑/白名单"
            segment.listMode == SegmentBlacklist.LIST_MODE_WHITELIST -> {
                val white = SegmentBlacklist.toDisplaySelection(context, segment.listMode, segment.listApps.toSet())
                if (white.isEmpty()) "白名单模式 (全部拦截)" else "白名单 ${white.size} 个应用"
            }
            segment.listApps.isEmpty() -> "本时段不锁任何应用"
            else -> "独立管理 ${segment.listApps.size} 个应用"
        }
    }

    private var recyclerView: RecyclerView? = null
    private var adapter: TimeSegmentAdapter? = null
    private val segments = mutableListOf<TimeSegment>()
    
    // Views
    private var cardPending: CardView? = null
    private var tvPendingChanges: TextView? = null

    // TASK-05: 本时段黑名单编辑状态（弹窗位置 + 摘要视图，供 segmentListLauncher 回调同步）
    private var editingSegmentPosition: Int = -1
    private var editingListSummaryView: TextView? = null
    // [FIX 2026-08-14] 名单回调的"待提交值"：onActivityResult 先于 onResume 执行，
    // 回调写入 segments[position] 后会被 onResume->reloadData() 用存储旧值覆盖，
    // 故用独立字段暂存，供时段编辑弹窗「保存」时读取（listApps=null=跟随全局也是有效值）。
    private var editingListAppsPending: List<String>? = null
    private var editingListModePending: String = SegmentBlacklist.LIST_MODE_BLACKLIST
    private var editingListAppsTouched: Boolean = false

    private val segmentListLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            val selection = result.data?.getStringArrayListExtra("EXTRA_RESULT_SELECTION")?.toList() ?: emptyList()
            val followGlobal = result.data?.getBooleanExtra("EXTRA_FOLLOW_GLOBAL", false) ?: false
            val newMode = result.data?.getStringExtra("EXTRA_LIST_MODE") ?: SegmentBlacklist.LIST_MODE_BLACKLIST
            val pos = editingSegmentPosition
            if (pos in segments.indices) {
                // selection 已是「有效黑名单集合」（白名单模式已在选择器里转成补集）。
                val newListApps = resolveListAppsFromResult(followGlobal, selection)
                // [FIX 2026-08-14] 选择器只暂存、不保存不弹窗：
                // 1) 先更新内存列表（若 reloadData 未随后覆盖则立即生效）；
                // 2) 再写入"待提交"字段，供编辑弹窗「保存」读取，避免被 onResume 的 reloadData 覆盖。
                segments[pos] = segments[pos].copy(listApps = newListApps, listMode = newMode)
                editingListAppsPending = newListApps
                editingListModePending = newMode
                editingListAppsTouched = true
                editingListSummaryView?.text = segmentListSummary(requireContext(), segments[pos])
                adapter?.notifyItemChanged(pos)
            }
        }
        editingSegmentPosition = -1
        editingListSummaryView = null
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_time_config, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        initViews(view)
        setupClickEvents(view)
        applySettingsLockoutState()
        reloadData()
    }

    override fun onResume() {
        super.onResume()
        if (!isAdded || view == null) return
        applySettingsLockoutState()
        reloadData()
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!isAdded || view == null || !shouldReloadWhenVisibilityChanged(hidden)) return
        applySettingsLockoutState()
        reloadData()
    }

    private fun isSettingsLocked(): Boolean {
        val ctx = requireContext()
        if (!TimeRuleManager.isInSettingsLockoutPeriod(ctx)) return false
        Toast.makeText(ctx, "当前处于深夜保护期，无法修改设置", Toast.LENGTH_SHORT).show()
        return true
    }

    private fun applySettingsLockoutState() {
        val locked = TimeRuleManager.isInSettingsLockoutPeriod(requireContext())
        val alpha = if (locked) 0.5f else 1.0f
        view?.findViewById<Button>(R.id.btn_add_segment)?.apply {
            isEnabled = !locked; this.alpha = alpha
        }
        view?.findViewById<Button>(R.id.btn_preset_segments)?.apply {
            isEnabled = !locked; this.alpha = alpha
        }
        view?.findViewById<Button>(R.id.btn_batch_repetition)?.apply {
            isEnabled = !locked; this.alpha = alpha
        }
    }

    private fun initViews(view: View) {
        recyclerView = view.findViewById(R.id.rv_segments)
        cardPending = view.findViewById(R.id.card_pending_changes)
        tvPendingChanges = view.findViewById(R.id.tv_pending_changes)
        
        adapter = TimeSegmentAdapter(
            segments,
            isReadOnly = TimeRuleManager.isInSettingsLockoutPeriod(requireContext()),
            onEdit = { pos -> showEditSegmentDialog(pos) },
            onDelete = { pos -> deleteSegment(pos) },
            onToggle = { pos, isChecked -> onToggleSegment(pos, isChecked) }
        )
        
        recyclerView?.layoutManager = object : LinearLayoutManager(requireContext()) {
            override fun canScrollVertically(): Boolean = false
        }
        recyclerView?.adapter = adapter
        recyclerView?.isNestedScrollingEnabled = false
        recyclerView?.setHasFixedSize(false)
    }

    private fun setupClickEvents(view: View) {
        view.findViewById<Button>(R.id.btn_global_blacklist).setOnClickListener {
            startActivity(Intent(requireContext(), AppSelectionActivity::class.java))
        }
        view.findViewById<Button>(R.id.btn_header_preview).setOnClickListener {
            startActivity(Intent(requireContext(), SchedulePreviewActivity::class.java))
        }
        view.findViewById<Button>(R.id.btn_add_segment).setOnClickListener {
            if (isSettingsLocked()) return@setOnClickListener
            showAddSegmentDialog()
        }
        view.findViewById<Button>(R.id.btn_preset_segments).setOnClickListener {
            if (isSettingsLocked()) return@setOnClickListener
            showPresetSegmentsDialog()
        }
        view.findViewById<Button>(R.id.btn_batch_repetition).setOnClickListener {
            if (isSettingsLocked()) return@setOnClickListener
            showBatchRepetitionDialog()
        }
    }

    // ============== 数据加载 ==============

    fun reloadData() {
        val ctx = context ?: return
        segments.clear()
        adapter?.resetStates()

        val pending = TimeRuleManager.getPendingTimeSegments(ctx)
        val active = TimeRuleManager.getTimeSegments(ctx)

        if (pending == null) {
            segments.addAll(active)
            adapter?.setPendingDeletions(emptyList())
        } else {
            segments.addAll(pending)
            val deletions = mutableListOf<TimeSegment>()
            active.forEach { activeSeg ->
                val stillExists = pending.any { p ->
                    p.name == activeSeg.name ||
                    (p.startHour == activeSeg.startHour && p.startMinute == activeSeg.startMinute &&
                     p.endHour == activeSeg.endHour && p.endMinute == activeSeg.endMinute &&
                     SegmentAdverseAnalysis.isDaysOverlapping(activeSeg.daysOfWeek, p.daysOfWeek))
                }
                if (!stillExists) {
                    segments.add(activeSeg)
                    deletions.add(activeSeg)
                }
            }
            adapter?.setPendingDeletions(deletions)
            markNewSegments(active, pending)
        }
        sortSegments(segments)
        adapter?.notifyDataSetChanged()
        showPendingChanges()
    }

    private fun sortSegments(list: MutableList<TimeSegment>) {
        list.sortWith { a, b ->
            val aStart = a.startHour * 60 + a.startMinute
            val bStart = b.startHour * 60 + b.startMinute
            if (aStart != bStart) aStart - bStart
            else (a.endHour * 60 + a.endMinute) - (b.endHour * 60 + b.endMinute)
        }
    }

    private fun markNewSegments(current: List<TimeSegment>, pending: List<TimeSegment>) {
        val a = adapter ?: return
        pending.forEach { p ->
            val match = current.find { c ->
                c.name == p.name || (c.startHour == p.startHour && c.startMinute == p.startMinute &&
                    c.endHour == p.endHour && c.endMinute == p.endMinute)
            }
            if (match == null) a.markAsNew(p)
            else if (!SegmentAdverseAnalysis.isSingleSegmentEqual(p, match)) a.markAsModified(p)
        }
    }

    private fun showPendingChanges() {
        val ctx = context ?: return
        // DA-C: 废弃"深夜设置锁定" UI 分支已移除 (WP-04-T01 v3)。
        // 兼容方法 TimeRuleManager.isInSettingsLockoutPeriod() 仍返回 false,占位控件
        // tv_lockout_hint 也保留以免破坏 layout。lockout hint 永远 GONE。
        view?.findViewById<TextView>(R.id.tv_lockout_hint)?.visibility = View.GONE

        if (TimeRuleManager.isInAdaptationPeriod(ctx)) {
            cardPending?.visibility = View.GONE
            return
        }

        val pendingSegments = TimeRuleManager.getPendingTimeSegments(ctx)
        if (pendingSegments.isNullOrEmpty()) {
            // [Fix: strategy switch UI refresh] 不移除 ChangeLog 条目（由 apply/cancel 路径清理），
            // 仅隐藏卡片。防止策略切换刚添加的变更日志被冲掉。
            cardPending?.visibility = View.GONE
            return
        }

        val changeLog = ChangeLogManager.getFormattedLogForCategory(ctx, "segments")
        val displayText = if (changeLog.isNotEmpty()) changeLog else {
            val activeSegments = TimeRuleManager.getTimeSegments(ctx)
            SegmentAdverseAnalysis.getPendingDiffString(ctx, activeSegments, pendingSegments)
                ?.joinToString("\n\n") ?: ""
        }

        if (displayText.isNotEmpty()) {
            cardPending?.visibility = View.VISIBLE
            tvPendingChanges?.text = displayText
        } else {
            cardPending?.visibility = View.GONE
        }
    }

    // ============== 即时保存 ==============

    private fun saveSegmentsImmediately() {
        val ctx = context ?: return
        val a = adapter ?: return
        val pendingDeletions = a.getPendingDeletions()
        val finalSegments = segments.filter { it !in pendingDeletions }

        // Validation
        if (finalSegments.any { TimeRuleManager.calculateDuration(it) == 0 }) {
            Toast.makeText(ctx, "检测到时长为0的时段，请检查配置", Toast.LENGTH_SHORT).show()
            return
        }
        val names = finalSegments.map { it.name }
        if (names.distinct().size != names.size) {
            Toast.makeText(ctx, "时段名称重复，请修改", Toast.LENGTH_SHORT).show()
            return
        }
        if (TimeRuleManager.checkSegmentOverlap(finalSegments)) {
            Toast.makeText(ctx, "时段存在重叠，请调整", Toast.LENGTH_SHORT).show()
            return
        }

        val result = TimeRuleManager.analyzeAndSaveTimeSegments(ctx, finalSegments)
        AdverseAnalysis.showAnalysisDialog(ctx, result) {
            reloadData()
            sendRefreshBroadcast()
        }
    }

    private fun sendRefreshBroadcast() {
        val intent = Intent(MonitorService.ACTION_REFRESH_RULES)
        intent.setPackage(requireContext().packageName)
        requireContext().sendBroadcast(intent)
    }

    // ============== 增删改操作 ==============

    private fun deleteSegment(position: Int) {
        if (position < 0 || position >= segments.size) return
        val a = adapter ?: return
        val segment = segments[position]

        if (a.isPendingDeletion(segment)) {
            // Undo delete
            a.unmarkPendingDeletion(segment)
            a.notifyItemChanged(position)
            saveSegmentsImmediately()
            return
        }

        val ctx = context ?: return
        val activeSegments = TimeRuleManager.getTimeSegments(ctx)
        val isExistingActive = activeSegments.any { aSeg ->
            aSeg.name == segment.name || (aSeg.startHour == segment.startHour && aSeg.startMinute == segment.startMinute &&
                aSeg.endHour == segment.endHour && aSeg.endMinute == segment.endMinute)
        }

        if (isExistingActive && !a.getNewSegments().contains(segment)) {
            a.markPendingDeletion(segment)
            a.notifyItemChanged(position)
            saveSegmentsImmediately()
        } else {
            segments.removeAt(position)
            a.notifyItemRemoved(position)
            saveSegmentsImmediately()
        }
    }

    private fun onToggleSegment(position: Int, isEnabled: Boolean) {
        if (isSettingsLocked()) return
        if (position !in segments.indices) return
        val seg = segments[position]
        if (seg.isEnabled == isEnabled) return
        
        segments[position] = seg.copy(isEnabled = isEnabled)
        saveSegmentsImmediately()
    }

    // ============== 编辑弹窗 ==============

    private fun showEditSegmentDialog(position: Int) {
        if (isSettingsLocked()) return
        if (position !in segments.indices) return
        val segment = segments[position]
        // [FIX 2026-08-14] 每个编辑弹窗独立：打开时重置名单"待提交"状态。
        editingListAppsTouched = false
        editingListAppsPending = null
        editingListModePending = segment.listMode
        val ctx = requireContext()
        val dialogView = LayoutInflater.from(ctx).inflate(R.layout.dialog_edit_segment, null)

        val etName = dialogView.findViewById<EditText>(R.id.et_edit_name)
        val btnStart = dialogView.findViewById<Button>(R.id.btn_edit_start)
        val btnEnd = dialogView.findViewById<Button>(R.id.btn_edit_end)
        val etLimit = dialogView.findViewById<EditText>(R.id.et_edit_limit)
        val btnDays = dialogView.findViewById<Button>(R.id.btn_edit_days)
        val btnSegmentList = dialogView.findViewById<Button>(R.id.btn_edit_segment_list)
        val tvSegmentListSummary = dialogView.findViewById<TextView>(R.id.tv_segment_list_summary)

        var startHour = segment.startHour
        var startMinute = segment.startMinute
        var endHour = segment.endHour
        var endMinute = segment.endMinute
        var selectedDays = segment.daysOfWeek // null = everyday

        fun formatTime(h: Int, m: Int) = String.format("%02d:%02d", h, m)

        etName.setText(segment.name)
        btnStart.text = formatTime(startHour, startMinute)
        btnEnd.text = formatTime(endHour, endMinute)
        etLimit.setText(segment.limitMins.toString())
        btnDays.text = formatDaysText(selectedDays)
        tvSegmentListSummary.text = segmentListSummary(ctx, segment)

        val etUsableCount = dialogView.findViewById<EditText>(R.id.et_edit_usable_count)
        val tvUsableCountHint = dialogView.findViewById<TextView>(R.id.tv_usable_count_hint)
        if (segment.usableCountLimitHalfUnits >= 0) {
            etUsableCount.setText(formatUsableCountHalfUnits(segment.usableCountLimitHalfUnits))
        } else {
            etUsableCount.setText("")
        }
        fun updateUsableCountHint() {
            val limit = etLimit.text.toString().toIntOrNull() ?: segment.limitMins
            val defaultCount = maxOf(1, limit / 5)
            tvUsableCountHint.text = "留空=自动(默认 " + defaultCount + "次)"
        }
        updateUsableCountHint()
        etLimit.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) { updateUsableCountHint() }
        })

        btnStart.setOnClickListener {
            TimePickerDialog(ctx, { _, h, m -> startHour = h; startMinute = m; btnStart.text = formatTime(h, m) },
                startHour, startMinute, true).show()
        }
        btnEnd.setOnClickListener {
            TimePickerDialog(ctx, { _, h, m -> endHour = h; endMinute = m; btnEnd.text = formatTime(h, m) },
                endHour, endMinute, true).show()
        }
        btnDays.setOnClickListener {
            showDaysPicker(ctx, selectedDays) { days ->
                selectedDays = days
                btnDays.text = formatDaysText(days)
            }
        }
        btnSegmentList.setOnClickListener {
            editingSegmentPosition = position
            editingListSummaryView = tvSegmentListSummary
            // [FIX 2026-08-14] 若本次弹窗内名单已被回调更新过（含 null=跟随全局），
            // 以"待提交值"为准；否则用打开弹窗时的名单。
            val currentListApps = if (editingListAppsTouched) editingListAppsPending else segment.listApps
            // 模式：跟随全局时用全局模式，否则用本时段名单自己的模式。
            val currentListMode = when {
                editingListAppsTouched -> editingListModePending
                segment.listApps == null -> CreditManager.getBlacklistMode(requireContext())
                else -> segment.listMode
            }
            val intent = Intent(requireContext(), AppSelectionActivity::class.java)
            intent.putExtra("EXTRA_MODE", "segment_blacklist")
            intent.putStringArrayListExtra("EXTRA_INITIAL_SELECTION", ArrayList(currentListApps ?: emptyList()))
            intent.putExtra("EXTRA_FOLLOW_GLOBAL", currentListApps == null)
            intent.putExtra("EXTRA_LIST_MODE", currentListMode)
            segmentListLauncher.launch(intent)
        }

        AlertDialog.Builder(ctx)
            .setTitle("编辑时段")
            .setView(dialogView)
            .setPositiveButton("保存") { _, _ ->
                val name = etName.text.toString().ifBlank { segment.name }
                val limit = etLimit.text.toString().toIntOrNull() ?: segment.limitMins
                val usableCountStr = etUsableCount.text.toString().trim()
                val usableCountLimitHalfUnits = UsableCount.parseHalfUnits(usableCountStr)
                if (usableCountLimitHalfUnits == null) {
                    Toast.makeText(requireContext(), "可用次数必须是非负数字", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                val newSegment = TimeSegment(
                    name = name,
                    startHour = startHour, startMinute = startMinute,
                    endHour = endHour, endMinute = endMinute,
                    limitMins = limit,
                    daysOfWeek = selectedDays,
                    isEnabled = segment.isEnabled,
                    usableCountLimitHalfUnits = usableCountLimitHalfUnits,
                    listMode = if (editingListAppsTouched) editingListModePending else segment.listMode,
                    // [FIX 2026-08-14] 以"待提交值"为准；listApps=null（跟随全局）是有效值，
                    // 不能 Elvis 回退到打开弹窗时的旧名单（否则"恢复为全局黑名单"一保存就变回独立管理）。
                    listApps = if (editingListAppsTouched) editingListAppsPending else segment.listApps,
                    lastModified = System.currentTimeMillis()
                )

                // 🚀 [Fix] 先验证重叠，再更新 UI（防止验证失败但 UI 已改）
                val a = adapter ?: return@setPositiveButton
                val pendingDeletions = a.getPendingDeletions()
                val tempList = segments.toMutableList()
                tempList[position] = newSegment
                val finalCheck = tempList.filter { it !in pendingDeletions }
                if (TimeRuleManager.checkSegmentOverlap(finalCheck)) {
                    Toast.makeText(ctx, "时段存在重叠，请调整", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                segments[position] = newSegment
                if (a.isNewSegment(segment)) {
                    a.markAsNew(newSegment)
                } else if (!SegmentAdverseAnalysis.isSingleSegmentEqual(segment, newSegment)) {
                    a.markAsModified(newSegment)
                }
                adapter?.notifyItemChanged(position)
                saveSegmentsImmediately()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ============== 添加弹窗 ==============

    private fun showAddSegmentDialog() {
        val ctx = requireContext()
        val dialogView = LayoutInflater.from(ctx).inflate(R.layout.dialog_edit_segment, null)

        val etName = dialogView.findViewById<EditText>(R.id.et_edit_name)
        val btnStart = dialogView.findViewById<Button>(R.id.btn_edit_start)
        val btnEnd = dialogView.findViewById<Button>(R.id.btn_edit_end)
        val etLimit = dialogView.findViewById<EditText>(R.id.et_edit_limit)
        val btnDays = dialogView.findViewById<Button>(R.id.btn_edit_days)

        // TASK-05: 新增时段默认跟随全局黑名单；名单入口仅在编辑弹窗提供。
        dialogView.findViewById<View>(R.id.ll_segment_list_section)?.visibility = View.GONE

        var startHour = 8; var startMinute = 0
        var endHour = 22; var endMinute = 0
        var selectedDays: List<Int>? = null

        fun formatTime(h: Int, m: Int) = String.format("%02d:%02d", h, m)

        etName.hint = "新时段"
        btnStart.text = formatTime(startHour, startMinute)
        btnEnd.text = formatTime(endHour, endMinute)
        etLimit.setText("30")
        btnDays.text = "每天"

        val etUsableCount = dialogView.findViewById<EditText>(R.id.et_edit_usable_count)
        val tvUsableCountHint = dialogView.findViewById<TextView>(R.id.tv_usable_count_hint)
        etUsableCount.setText("")
        fun updateUsableCountHint() {
            val limit = etLimit.text.toString().toIntOrNull() ?: 30
            val defaultCount = maxOf(1, limit / 5)
            tvUsableCountHint.text = "留空=自动(默认 " + defaultCount + "次)"
        }
        updateUsableCountHint()
        etLimit.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) { updateUsableCountHint() }
        })

        btnStart.setOnClickListener {
            TimePickerDialog(ctx, { _, h, m -> startHour = h; startMinute = m; btnStart.text = formatTime(h, m) },
                startHour, startMinute, true).show()
        }
        btnEnd.setOnClickListener {
            TimePickerDialog(ctx, { _, h, m -> endHour = h; endMinute = m; btnEnd.text = formatTime(h, m) },
                endHour, endMinute, true).show()
        }
        btnDays.setOnClickListener {
            showDaysPicker(ctx, selectedDays) { days ->
                selectedDays = days
                btnDays.text = formatDaysText(days)
            }
        }

        AlertDialog.Builder(ctx)
            .setTitle("添加新时段")
            .setView(dialogView)
            .setPositiveButton("添加") { _, _ ->
                val name = etName.text.toString().ifBlank { "新时段" }
                val limit = etLimit.text.toString().toIntOrNull() ?: 30

                val usableCountStr = etUsableCount.text.toString().trim()
                val usableCountLimitHalfUnits = UsableCount.parseHalfUnits(usableCountStr)
                if (usableCountLimitHalfUnits == null) {
                    Toast.makeText(requireContext(), "可用次数必须是非负数字", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                val newSegment = TimeSegment(
                    name = name,
                    startHour = startHour, startMinute = startMinute,
                    endHour = endHour, endMinute = endMinute,
                    limitMins = limit,
                    daysOfWeek = selectedDays,
                    isEnabled = true,
                    usableCountLimitHalfUnits = usableCountLimitHalfUnits,
                    lastModified = System.currentTimeMillis()
                )

                // DA-D1 UI preflight: 拒绝跨 refresh time 的 DND 时段。
                // 必须在任何 list/adapter mutation 或 save 调用之前。
                // 拒绝路径仅弹 AlertDialog,不调 analyzeAndSaveTimeSegments,
                // 不修改 list/adapter/pending(与 WP-03 atomicity 契约一致)。
                val refreshText = TimeRuleManager.getRefreshTime(ctx)
                val refreshMinutes = parseRefreshMinutes(refreshText)
                if (refreshMinutes != null) {
                    val startMin = startHour * 60 + startMinute
                    val endMin = endHour * 60 + endMinute
                    when (val verdict =
                        MinuteBoundary.isSegmentLegal(startMin, endMin, refreshMinutes)) {
                        is BoundaryResult.Rejected -> {
                            android.util.Log.w(
                                "TimeConfigFragment",
                                "Segment preflight REJECTED start=${startHour}:${startMinute} " +
                                    "end=${endHour}:${endMinute} refresh=${refreshText} " +
                                    "(${refreshMinutes}m) reason=${verdict.reason}"
                            )
                            AlertDialog.Builder(ctx)
                                .setTitle("时段端点不能跨 refresh time")
                                .setMessage(
                                    "时段端点不能跨 refresh time。" +
                                        "请调整勿扰时段的范围,使其完全位于 refresh time 之前或之后," +
                                        "或在规则参数中调整生效时间(06:00 - 10:00)。"
                                )
                                .setPositiveButton("我知道了", null)
                                .show()
                            return@setPositiveButton
                        }
                        is BoundaryResult.Legal -> {
                            // fall through to overlap check + mutation
                        }
                    }
                }

                // 🚀 [Fix] 先验证重叠，再添加到列表（防止验证失败但 UI 已改）
                val a = adapter ?: return@setPositiveButton
                val pendingDeletions = a.getPendingDeletions()
                val tempList = segments.toMutableList()
                tempList.add(newSegment)
                val finalCheck = tempList.filter { it !in pendingDeletions }
                if (TimeRuleManager.checkSegmentOverlap(finalCheck)) {
                    Toast.makeText(ctx, "时段存在重叠，请调整", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                segments.add(newSegment)
                sortSegments(segments)
                adapter?.markAsNew(newSegment)
                adapter?.notifyDataSetChanged()
                saveSegmentsImmediately()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ============== 生效周期选择器 ==============

    private fun showDaysPicker(ctx: android.content.Context, currentDays: List<Int>?, onResult: (List<Int>?) -> Unit) {
        val days = arrayOf("周日", "周一", "周二", "周三", "周四", "周五", "周六")
        val checked = BooleanArray(7) { i ->
            currentDays == null || currentDays.contains(i + 1)
        }
        AlertDialog.Builder(ctx)
            .setTitle("选择生效周期")
            .setMultiChoiceItems(days, checked) { _, which, isChecked -> checked[which] = isChecked }
            .setPositiveButton("确定") { _, _ ->
                val newDays = mutableListOf<Int>()
                var allChecked = true
                checked.forEachIndexed { idx, c ->
                    if (c) newDays.add(idx + 1) else allChecked = false
                }
                onResult(if (allChecked || newDays.isEmpty()) null else newDays)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun formatDaysText(days: List<Int>?): String {
        if (days == null || days.size == 7) return "每天"
        val map = mapOf(1 to "日", 2 to "一", 3 to "二", 4 to "三", 5 to "四", 6 to "五", 7 to "六")
        return "周" + days.sorted().map { map[it] ?: "" }.joinToString("")
    }

    // ============== 预设 & 批量 ==============

    private fun showPresetSegmentsDialog() {
        val ctx = requireContext()
        AlertDialog.Builder(ctx)
            .setTitle("⚡ 恢复默认方案")
            .setMessage("确定要加载默认配置吗？(深夜每天生效，其余时段仅工作日)\n\n" +
                "① 深夜: 22:00 - 07:00 (休息 10min, 2次)\n" +
                "② 上午: 08:45 - 12:00 (休息 30min, 6次)\n" +
                "③ 下午: 12:45 - 17:00 (休息 30min, 6次)\n" +
                "④ 晚上: 17:45 - 21:00 (休息 20min, 4次)\n\n" +
                "这将覆盖当前所有配置。")
            .setPositiveButton("确定") { _, _ ->
                segments.clear()
                adapter?.resetStates()
                val workdays = listOf(2, 3, 4, 5, 6)
                val everyday = listOf(1, 2, 3, 4, 5, 6, 7)
                segments.add(TimeSegment(name = "深夜", startHour = 22, startMinute = 0, endHour = 7, endMinute = 0, limitMins = 10, daysOfWeek = everyday))
                segments.add(TimeSegment(name = "上午", startHour = 8, startMinute = 45, endHour = 12, endMinute = 0, limitMins = 30, daysOfWeek = workdays))
                segments.add(TimeSegment(name = "下午", startHour = 12, startMinute = 45, endHour = 17, endMinute = 0, limitMins = 30, daysOfWeek = workdays))
                segments.add(TimeSegment(name = "晚上", startHour = 17, startMinute = 45, endHour = 21, endMinute = 0, limitMins = 20, daysOfWeek = workdays))
                segments.forEach { adapter?.markAsNew(it) }
                sortSegments(segments)
                adapter?.notifyDataSetChanged()
                saveSegmentsImmediately()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showBatchRepetitionDialog() {
        if (segments.isEmpty()) {
            Toast.makeText(requireContext(), "没有可设置的时段", Toast.LENGTH_SHORT).show()
            return
        }
        val names = segments.map { it.name }.toTypedArray()
        val checked = BooleanArray(names.size) { true }
        AlertDialog.Builder(requireContext())
            .setTitle("📅 批量设置周期")
            .setMultiChoiceItems(names, checked) { _, which, isChecked -> checked[which] = isChecked }
            .setPositiveButton("下一步") { _, _ ->
                showBatchDaysSelectionDialog(checked)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showBatchDaysSelectionDialog(checkedSegments: BooleanArray) {
        val ctx = requireContext()
        val days = arrayOf("周日", "周一", "周二", "周三", "周四", "周五", "周六")
        val checkedDays = BooleanArray(7) { true }
        AlertDialog.Builder(ctx)
            .setTitle("选择生效日期")
            .setMultiChoiceItems(days, checkedDays) { _, which, isChecked -> checkedDays[which] = isChecked }
            .setPositiveButton("应用") { _, _ ->
                val selectedDays = checkedDays.mapIndexed { i, c -> if (c) i + 1 else null }.filterNotNull()
                val normalizedDays = if (selectedDays.size == 7) null else selectedDays
                checkedSegments.forEachIndexed { i, checked ->
                    if (checked && i < segments.size) {
                        segments[i] = segments[i].copy(daysOfWeek = normalizedDays, lastModified = System.currentTimeMillis())
                    }
                }
                adapter?.notifyDataSetChanged()
                saveSegmentsImmediately()
            }
            .setNegativeButton("取消", null)
            .show()
    }
}
