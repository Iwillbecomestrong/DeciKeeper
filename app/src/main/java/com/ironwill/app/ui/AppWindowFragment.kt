/**
 * =================================================================================
 * [Context Header]
 * * @file: AppWindowFragment.kt
 * * @role: UI
 * * @description: 应用时间窗规则的配置与列表界面。
 * * @responsibilities:
 * 1. 展示时间窗规则与待生效状态。
 * 2. 提供新增/编辑/撤销入口。
 * * @context:
 * - 上游: MainActivity
 * - 下游: AppWindowManager, AppWindowAdapter
 * * @constraints (必读):
 * 1. 禁止承载复杂业务逻辑。
 * 2. UI 操作需主线程安全。
 * 3. 需处理空列表与权限异常。
 * =================================================================================
 */
package com.ironwill.app.ui

import android.annotation.SuppressLint
import android.app.TimePickerDialog
import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.ironwill.app.R
import com.ironwill.app.manager.AppWindowManager
import com.ironwill.app.manager.ChangeLogManager
import com.ironwill.app.manager.CreditManager
import com.ironwill.app.manager.EffectiveActivationPolicyManager
import com.ironwill.app.manager.TimeRuleManager
import java.util.Calendar







class AppWindowFragment : Fragment() {

    companion object {
        internal fun shouldRefreshWhenVisibilityChanged(hidden: Boolean): Boolean = !hidden
    }

    private lateinit var rvRules: RecyclerView
    private lateinit var adapter: AppWindowAdapter
    private lateinit var tvEmpty: TextView
    private lateinit var cardPending: androidx.cardview.widget.CardView
    private lateinit var tvPendingChanges: TextView
    private lateinit var tvLockoutHint: TextView
    private lateinit var btnUndo: Button
    private lateinit var btnAdd: Button
    private lateinit var btnBatchCycle: Button
    private var rulesWithStatus = mutableListOf<AppWindowManager.AppWindowRuleWithStatus>()
    
    // 🚀 [NEW] App Selection Callback Bridge
    private var onAppSelectionResult: ((Set<String>) -> Unit)? = null
    
    // 🚀 [NEW] Activity Result Launcher
    private val selectAppsLauncher = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            val list = result.data?.getStringArrayListExtra("EXTRA_RESULT_SELECTION")
            if (list != null) {
                onAppSelectionResult?.invoke(list.toSet())
            }
        }
        onAppSelectionResult = null
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_app_window, container, false)
        
        rvRules = view.findViewById(R.id.rvRules)

        tvEmpty = view.findViewById(R.id.tvEmpty)
        cardPending = view.findViewById(R.id.card_pending_changes)
        tvPendingChanges = view.findViewById(R.id.tv_pending_changes)
        tvLockoutHint = view.findViewById(R.id.tv_lockout_hint)


        btnUndo = view.findViewById(R.id.btn_undo)
        btnAdd = view.findViewById(R.id.btn_add)
        btnBatchCycle = view.findViewById(R.id.btn_batch_cycle)
        val btnPreview = view.findViewById<Button>(R.id.btnPreview)

        btnPreview.setOnClickListener {
            val intent = android.content.Intent(requireContext(), AppWindowPreviewActivity::class.java)
            startActivity(intent)
        }

        rvRules.layoutManager = LinearLayoutManager(context)
        adapter = AppWindowAdapter(
            requireContext(),
            rulesWithStatus,
            onEdit = { pos -> 
                val item = rulesWithStatus[pos]
                // 🚀 [Fix] Pass baselineRule for correct comparison (Baseline -> Target)
                showEditRuleDialog(item.rule, item.baselineRule) 
            },
            onDelete = { pos -> 
                val item = rulesWithStatus[pos]
                showDeleteConfirmDialog(item.rule, item.baselineRule) 
            },
            onToggle = { pos, isEnabled -> onToggleRule(pos, isEnabled) },
            onUndo = { pos -> onUndoRule(pos) }
        )
        rvRules.adapter = adapter
        
        // Setup Buttons
        btnAdd.setOnClickListener {
            showEditRuleDialog(null)
        }
        
        btnUndo.setOnClickListener {
            onUndoAll()
        }
        
        btnBatchCycle.setOnClickListener {
            showBatchRuleSelectionDialog()
        }

        refreshList()

        return view
    }
    
    override fun onResume() {
        super.onResume()
        refreshList()
        applySettingsLockoutState()
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!isAdded || view == null || !shouldRefreshWhenVisibilityChanged(hidden)) return
        refreshList()
        applySettingsLockoutState()
    }

    private fun applySettingsLockoutState() {
        val locked = TimeRuleManager.isInSettingsLockoutPeriod(requireContext())
        val alpha = if (locked) 0.5f else 1.0f
        btnAdd.isEnabled = !locked
        btnBatchCycle.isEnabled = !locked
        btnUndo.isEnabled = !locked
        btnAdd.alpha = alpha
        btnBatchCycle.alpha = alpha
        btnUndo.alpha = alpha
    }

    private fun isSettingsLocked(): Boolean {
        val ctx = requireContext()
        if (!TimeRuleManager.isInSettingsLockoutPeriod(ctx)) return false
        val refreshTime = TimeRuleManager.getRefreshTime(ctx)
        Toast.makeText(ctx, "当前处于深夜保护期(00:00 ~ $refreshTime)，无法修改时间窗设置", Toast.LENGTH_SHORT).show()
        return true
    }

    fun refreshList() {
        val rules = AppWindowManager.getDisplayRules(requireContext())
        rulesWithStatus.clear()
        rulesWithStatus.addAll(rules)
        adapter.notifyDataSetChanged()
        adapter.notifyDataSetChanged()
        tvEmpty.visibility = if (rules.isEmpty()) View.VISIBLE else View.GONE
        showPendingChanges()
    }
    
    private fun showPendingChanges() {
        val ctx = context ?: return
        val isLocked = TimeRuleManager.isInSettingsLockoutPeriod(ctx)
        if (isLocked) {
            val refreshTime = TimeRuleManager.getRefreshTime(ctx)
            cardPending.visibility = View.VISIBLE
            cardPending.setCardBackgroundColor(Color.parseColor("#FFF3E0"))
            tvLockoutHint.visibility = View.VISIBLE
            tvLockoutHint.text = "🔒 设置已锁定 (深夜保护期 00:00 ~ $refreshTime)\n当前仅供查看，无法修改配置。"
        } else {
            tvLockoutHint.visibility = View.GONE
            cardPending.setCardBackgroundColor(Color.parseColor("#FFF8E1"))
        }
        
        // 🚀 [Fix-3] 适应期内所有变更都立即生效，不会有 pending，但仍然检查以防万一
        // 不再直接 return 隐藏卡片——让下面的正常逻辑判断是否有 pending

        if (!AppWindowManager.hasPendingChanges(ctx)) {
            // [Fix: strategy switch UI refresh] 不移除 ChangeLog 条目（由 apply/cancel 路径清理），
            // 防止策略切换刚添加的变更日志被冲掉。
            if (!isLocked) {
                cardPending.visibility = View.GONE
            } else {
                tvPendingChanges.text = ""
            }
            return
        }
        
        val changeLog = ChangeLogManager.getFormattedLogForCategory(ctx, "app_window_rules")
        
        if (changeLog.isNotEmpty()) {
            cardPending.visibility = View.VISIBLE
            tvPendingChanges.text = changeLog
        } else {
            // 有 pending flag 但 ChangeLog 为空——显示兜底提示（动态读取实际生效时间）
            cardPending.visibility = View.VISIBLE
            val refreshTime = TimeRuleManager.getRefreshTime(ctx)
            tvPendingChanges.text = "有待生效的变更，将于明日 $refreshTime 生效"
        }
    }
    
    private fun onToggleRule(position: Int, isEnabled: Boolean) {
        if (isSettingsLocked()) return
        val item = rulesWithStatus[position]
        val rule = item.rule
        if (rule.isEnabled == isEnabled) return // No change
        
        val newRule = rule.copy(isEnabled = isEnabled)
        // 弹窗显示：对比当前活跃规则（只看本次改了什么）
        val displayAnalysis = AppWindowManager.analyzeChange(requireContext(), rule, newRule)
        // 生效判断：对比 baseline（决定有利/不利）
        val baselineAnalysis = AppWindowManager.analyzeChange(requireContext(), item.baselineRule ?: rule, newRule)

        if (baselineAnalysis.isOverallAdverse) {
            showDetailedConfirmDialog(displayAnalysis, {
                AppWindowManager.updateRule(requireContext(), newRule)
                refreshList()
            }, rule.id)
        } else {
            // Check for benign update conflict? (Unlikely for just Toggle)
            // But we can check overlap just in case, though overlap is allowed for App Window logic (Union).
            // User only asked for strict uniqueness on Definition (Name/Time).
            AppWindowManager.updateRule(requireContext(), newRule)
            refreshList()
        }
    }

    private fun onUndoRule(position: Int) {
        if (isSettingsLocked()) return
        if (position !in rulesWithStatus.indices) return
        val ruleWithStatus = rulesWithStatus[position]
        
        AppWindowManager.undoRuleChange(requireContext(), ruleWithStatus.rule.id)
        refreshList()
        Toast.makeText(requireContext(), "已撤销变更", Toast.LENGTH_SHORT).show()
    }

    private fun showEditRuleDialog(rule: AppWindowManager.AppWindowRule?, baseline: AppWindowManager.AppWindowRule? = null) {
        if (isSettingsLocked()) return
        val context = requireContext()
        val dialogView = LayoutInflater.from(context).inflate(R.layout.dialog_edit_app_window, null)
        val etName = dialogView.findViewById<EditText>(R.id.etName)
        val btnStartTime = dialogView.findViewById<Button>(R.id.btnStartTime)
        val btnEndTime = dialogView.findViewById<Button>(R.id.btnEndTime)
        val chipGroupDays = dialogView.findViewById<ChipGroup>(R.id.chipGroupDays)
        val chipGroupApps = dialogView.findViewById<ChipGroup>(R.id.chipGroupApps)
        val btnSelectApps = dialogView.findViewById<Button>(R.id.btnSelectApps)
        
        var startTime = rule?.startTime ?: 0
        var endTime = rule?.endTime ?: 0
        val selectedDays = rule?.daysOfWeek?.toMutableSet() ?: mutableSetOf(1,2,3,4,5,6,7)
        val selectedApps = rule?.packageNames?.toMutableSet() ?: mutableSetOf()

        etName.setText(rule?.name ?: "")
        
        fun formatTime(mins: Int): String {
            return String.format("%02d:%02d", mins / 60, mins % 60)
        }
        
        btnStartTime.text = formatTime(startTime)
        btnEndTime.text = formatTime(endTime)
        
        btnStartTime.setOnClickListener {
            TimePickerDialog(context, { _, h, m -> 
                startTime = h * 60 + m
                btnStartTime.text = formatTime(startTime)
            }, startTime / 60, startTime % 60, true).show()
        }
        
        btnEndTime.setOnClickListener {
             TimePickerDialog(context, { _, h, m -> 
                endTime = h * 60 + m
                btnEndTime.text = formatTime(endTime)
            }, endTime / 60, endTime % 60, true).show()
        }

        val btnDaily = dialogView.findViewById<Button>(R.id.btnDaily)
        val btnWorkdays = dialogView.findViewById<Button>(R.id.btnWorkdays)
        val btnWeekend = dialogView.findViewById<Button>(R.id.btnWeekend)
        
        fun updateChips() {
            for (i in 0 until chipGroupDays.childCount) {
                val chip = chipGroupDays.getChildAt(i) as Chip
                chip.setOnCheckedChangeListener(null)
                val day = chip.tag as Int
                chip.isChecked = selectedDays.contains(day)
                chip.setOnCheckedChangeListener { _, isChecked ->
                    if (isChecked) selectedDays.add(day) else selectedDays.remove(day)
                }
            }
        }
        
        btnDaily.setOnClickListener {
            selectedDays.clear()
            selectedDays.addAll(1..7)
            updateChips()
        }
        btnWorkdays.setOnClickListener {
            selectedDays.clear()
            selectedDays.addAll(1..5)
            updateChips()
        }
        btnWeekend.setOnClickListener {
            selectedDays.clear()
            selectedDays.addAll(listOf(6, 7))
            updateChips()
        }

        val daysMap = mapOf(1 to "周一", 2 to "周二", 3 to "周三", 4 to "周四", 5 to "周五", 6 to "周六", 7 to "周日")
        daysMap.forEach { (day, label) -> 
            val chip = Chip(context)
            chip.text = label
            chip.isCheckable = true
            chip.tag = day
            
            val typedValue = android.util.TypedValue()
            context.theme.resolveAttribute(android.R.attr.colorPrimary, typedValue, true)
            val primaryColor = typedValue.data
            
            val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf(-android.R.attr.state_checked))
            val colors = intArrayOf(primaryColor, Color.WHITE)
            val textColors = intArrayOf(Color.WHITE, Color.BLACK)
            
            chip.chipBackgroundColor = android.content.res.ColorStateList(states, colors)
            chip.setTextColor(android.content.res.ColorStateList(states, textColors))
            chip.chipStrokeWidth = 1f 
            chip.chipStrokeColor = android.content.res.ColorStateList.valueOf(Color.LTGRAY)
            
            chip.isChecked = selectedDays.contains(day)
            chip.setOnCheckedChangeListener { _, isChecked ->
                if (isChecked) selectedDays.add(day) else selectedDays.remove(day)
            }
            chipGroupDays.addView(chip)
        }

        fun updateAppChips() {
            chipGroupApps.removeAllViews()
            selectedApps.forEach { pkg ->
                val chip = Chip(context)
                // 🚀 [UI] Use Icon instead of text name
                try {
                    val icon = requireContext().packageManager.getApplicationIcon(pkg)
                    chip.chipIcon = icon
                    chip.isChipIconVisible = true
                } catch (e: Exception) {
                    // Fallback to text if icon fails
                    chip.text = getAppName(pkg).take(2) 
                }
                chip.text = " " // Empty text acts weird in some styles, space ensures layout? Or empty string.
                // chip.textStartPadding = 0f // Minimize padding if possible.
                chip.isCloseIconVisible = true
                chip.setOnCloseIconClickListener {
                    selectedApps.remove(pkg)
                    updateAppChips()
                }
                chipGroupApps.addView(chip)
            }
        }
        updateAppChips()

        btnSelectApps.setOnClickListener {
            showAppSelectionDialog(selectedApps) {
                updateAppChips()
            }
        }

        AlertDialog.Builder(context)
            .setTitle(if (rule == null) "新建规则" else "编辑规则")
            .setView(dialogView)
            .setPositiveButton("保存") { _, _ ->
                var name = etName.text.toString().trim()
                val allRules = AppWindowManager.getDisplayRules(context).map { it.rule }
                
                // 🚀 [Auto-Generate Name] If empty, generate "规则 N"
                if (name.isEmpty()) {
                     val existingNames = allRules.map { it.name }.toSet()
                     var i = 1
                     while (existingNames.contains("规则 $i")) i++
                     name = "规则 $i"
                }
                
                // 🚀 [Validation] Strict Uniqueness Check (User Requirement)
                val currentId = rule?.id
                
                // 1. Name Uniqueness
                val nameExists = allRules.any { it.id != currentId && it.name == name }
                if (nameExists) {
                    Toast.makeText(context, "名称已存在，请使用唯一名称", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                
                // 2. Time Range Uniqueness (Exact Match of Start, End, Days)
                // "时间段范围也是一样是唯一的 ... 不同的时间段或应用时间窗应该不同，如果改成一样，新修改的那个保存不成功"
                val timeExists = allRules.any { other ->
                     other.id != currentId && 
                     other.startTime == startTime && 
                     other.endTime == endTime && 
                     other.daysOfWeek == selectedDays
                }
                if (timeExists) {
                    Toast.makeText(context, "该时间段规则已存在 (时间与周期完全相同)，保存失败", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                val newRule = AppWindowManager.AppWindowRule(
                    id = rule?.id ?: java.util.UUID.randomUUID().toString(),
                    name = name,
                    startTime = startTime,
                    endTime = endTime,
                    daysOfWeek = selectedDays,
                    packageNames = selectedApps,
                    isEnabled = rule?.isEnabled ?: true
                )
                
                // 🚀 [Optimization] Check for No Functional Changes Check
                // If the new configuration is identical to the original rule (ignoring ID), skip analysis
                if (rule != null && 
                    rule.name == newRule.name &&
                    rule.startTime == newRule.startTime && 
                    rule.endTime == newRule.endTime &&
                    rule.isEnabled == newRule.isEnabled &&
                    rule.daysOfWeek == newRule.daysOfWeek &&
                    rule.packageNames == newRule.packageNames) {
                    
                    Toast.makeText(context, "设置已保存 (未发生变更)", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                
                // 🚀 对比当前活跃规则，只看本次修改（baseline 用于判断有利/不利）
                val analysis = AppWindowManager.analyzeChange(context, rule, newRule)
                
                // 🚀 [User Requirement] Always show dialog for any change (Restored, Favorable, or Adverse)
                showDetailedConfirmDialog(analysis, {
                    if (rule == null) AppWindowManager.addRule(context, newRule)
                    else AppWindowManager.updateRule(context, newRule)
                    refreshList()
                }, rule?.id)
            }
            .setNegativeButton("取消", null)
            .show()
    }
    
    private fun showDeleteConfirmDialog(rule: AppWindowManager.AppWindowRule, baseline: AppWindowManager.AppWindowRule? = null) {
            if (isSettingsLocked()) return
         // 弹窗显示：对比当前活跃规则
         val displayAnalysis = AppWindowManager.analyzeChange(requireContext(), rule, null)
         // 生效判断：对比 baseline
         val baselineAnalysis = AppWindowManager.analyzeChange(requireContext(), baseline, null)

         // 🚀 [User Requirement] Always show detailed dialog for deletion
         showDetailedConfirmDialog(displayAnalysis, {
             AppWindowManager.deleteRule(requireContext(), rule.id)
             refreshList()
         }, rule.id)
    }

    private fun showAppSelectionDialog(selectedApps: MutableSet<String>, onDismiss: () -> Unit) {
        // 🚀 [Refactor] Use Full Screen Activity instead of Dialog
        onAppSelectionResult = { newSelection ->
            selectedApps.clear()
            selectedApps.addAll(newSelection)
            onDismiss() // Update Chips
        }
        
        val intent = android.content.Intent(requireContext(), AppSelectionActivity::class.java)
        intent.putExtra("EXTRA_MODE", "result")
        intent.putStringArrayListExtra("EXTRA_INITIAL_SELECTION", ArrayList(selectedApps))
        selectAppsLauncher.launch(intent)
    }

    private fun getAppName(pkg: String): String {
        return try {
            val pm = requireContext().packageManager
            val info = pm.getApplicationInfo(pkg, 0)
            pm.getApplicationLabel(info).toString()
        } catch (e: Exception) {
            pkg
        }
    }


    private fun onUndoAll() {
        if (isSettingsLocked()) return
        if (!AppWindowManager.hasPendingChanges(requireContext())) {
            Toast.makeText(requireContext(), "当前没有待生效的变更", Toast.LENGTH_SHORT).show()
            return
        }
        
        // Step-by-step Undo
        val success = AppWindowManager.undoLastAction(requireContext())
        if (success) {
            refreshList()
            Toast.makeText(requireContext(), "已撤销上一步变更", Toast.LENGTH_SHORT).show()
        } else {
            // Fallback if stack is empty but hasPendingChanges is true (e.g. app restart cleared stack)
            // In that case, we can offer to clear all?
            // Or just say "Cannot undo further".
            // Logic: hasPendingChanges checks PREFS. undoStack is Memory.
            // If stack empty, we can't step-undo.
            // We should reload stack from prefs? No, we don't save stack to prefs.
            // So on app restart, undo history is lost.
            // In that case, we offer "Clear All" as fallback.
        
        }
    }

    private fun showBatchRuleSelectionDialog() {
        if (isSettingsLocked()) return
        val context = requireContext()
        val activeRules = AppWindowManager.getDisplayRules(context).map { it.rule }.distinctBy { it.id }
        
        if (activeRules.isEmpty()) {
            Toast.makeText(context, "没有可配置的规则", Toast.LENGTH_SHORT).show()
            return
        }

        val ruleNames = activeRules.map { "${it.name} (${it.getSummary()})" as CharSequence }.toTypedArray()
        val ruleIds = activeRules.map { it.id }.toTypedArray()
        val checkedItems = BooleanArray(activeRules.size) { true } // Default all checked

        AlertDialog.Builder(context)
            .setTitle("批量设置生效周期")
            .setMultiChoiceItems(ruleNames, checkedItems) { _, which, isChecked ->
                checkedItems[which] = isChecked
            }
            .setPositiveButton("下一步") { _, _ ->
                val selectedIds = mutableListOf<String>()
                for (i in checkedItems.indices) {
                    if (checkedItems[i]) {
                        selectedIds.add(ruleIds[i])
                    }
                }

                if (selectedIds.isEmpty()) {
                    Toast.makeText(context, "请至少选择一个规则", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                
                showBatchCycleDaySelectionDialog(selectedIds)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun applyBatchUpdate(targetRuleIds: List<String>, selectedDays: Set<Int>) {
        val context = requireContext()
        val activeRules = AppWindowManager.getDisplayRules(context)
        
        var updatedCount = 0
        activeRules.forEach { item ->
            // Only update if in selected list
            if (targetRuleIds.contains(item.rule.id) && item.rule.daysOfWeek != selectedDays) {
                 val newRule = item.rule.copy(daysOfWeek = selectedDays)
                 // AppWindowManager.updateRule handles Benign/Adverse logic (Adverse -> Pending)
                 AppWindowManager.updateRule(context, newRule)
                 updatedCount++
            }
        }
        
        refreshList()
        Toast.makeText(context, "已批量更新 ${updatedCount} 个规则的生效周期", Toast.LENGTH_SHORT).show()
    }

    private fun showBatchCycleDaySelectionDialog(targetRuleIds: List<String>) {
        val context = requireContext()
        val dialogView = LayoutInflater.from(context).inflate(R.layout.dialog_batch_cycle, null)
        
        val cbMap = mapOf(
            1 to dialogView.findViewById<CheckBox>(R.id.cbMon),
            2 to dialogView.findViewById<CheckBox>(R.id.cbTue),
            3 to dialogView.findViewById<CheckBox>(R.id.cbWed),
            4 to dialogView.findViewById<CheckBox>(R.id.cbThu),
            5 to dialogView.findViewById<CheckBox>(R.id.cbFri),
            6 to dialogView.findViewById<CheckBox>(R.id.cbSat),
            7 to dialogView.findViewById<CheckBox>(R.id.cbSun)
        )
        
        // Initial State: Default ALL
        cbMap.values.forEach { it.isChecked = true }
        
        val btnWorkdays = dialogView.findViewById<Button>(R.id.btnPresetWorkdays)
        val btnWeekend = dialogView.findViewById<Button>(R.id.btnPresetWeekend)
        
        btnWorkdays.setOnClickListener {
            cbMap.forEach { (day, cb) -> cb.isChecked = (day in 1..5) }
        }
        
        btnWeekend.setOnClickListener {
             cbMap.forEach { (day, cb) -> cb.isChecked = (day in 6..7) }
        }
        
        AlertDialog.Builder(context)
            .setTitle("设置生效周期")
            .setView(dialogView)
            .setPositiveButton("应用") { _, _ ->
                val selectedDays = cbMap.filter { it.value.isChecked }.keys
                if (selectedDays.isEmpty()) {
                    Toast.makeText(requireContext(), "请至少选择一天", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                applyBatchUpdate(targetRuleIds, selectedDays.toSet())
            }
            .setNegativeButton("取消", null)
            .show()

    }

    private fun showDetailedConfirmDialog(analysis: AppWindowManager.ChangeAnalysis, onConfirm: () -> Unit, targetRuleId: String? = null) {
        // 🚀 [Fix] 适应期内所有变更均立即生效，不区分有利/不利
        val isAdaptation = CreditManager.isInAdaptationPeriod(requireContext())
        if (isAdaptation) {
            val sb = StringBuilder()
            sb.append("当前处于【适应期】，所有修改均立即生效。\n\n")
            analysis.details.filter { it.type != AppWindowManager.ChangeType.INFO }.forEach {
                sb.append("• ${it.text}\n")
            }
            sb.append("\n(适应期结束后，放松规则的修改将延迟到次日生效)")

            AlertDialog.Builder(requireContext())
                .setTitle("✅ 适应期 - 立即生效")
                .setMessage(sb.toString().trim())
                .setPositiveButton("确定") { _, _ -> onConfirm() }
                .setNegativeButton("取消") { _, _ -> refreshList() }
                .show()
            return
        }

        // 🚀 [Optimization] No Change -> Toast Only
        if (analysis.details.any { it.type == AppWindowManager.ChangeType.INFO }) {
            Toast.makeText(requireContext(), "设置已保存 (未发生变更)", Toast.LENGTH_SHORT).show()
            onConfirm()
            return
        }

        val isAllNextDayPolicy =
            EffectiveActivationPolicyManager.getPolicy(requireContext()) == EffectiveActivationPolicyManager.Policy.ALL_NEXT_DAY
        val sb = StringBuilder()
        if (isAllNextDayPolicy) {
            sb.append("当前策略：全部修改均次日生效。\n\n")
        }
        
        // 1. Restored
        val restored = analysis.details.filter { it.type == AppWindowManager.ChangeType.RESTORED }
        if (restored.isNotEmpty()) {
            val label = if (isAllNextDayPolicy) "🟢 【恢复基准 (次日生效)】" else "🟢 【恢复基准 (立即生效)】"
            sb.append("$label\n")
            restored.forEach { sb.append("• ${it.text}\n") }
            sb.append("\n")
        }

        // 2. Favorable
        val favorable = analysis.details.filter { it.type == AppWindowManager.ChangeType.FAVORABLE }
        if (favorable.isNotEmpty()) {
            val label = when {
                isAllNextDayPolicy -> "🔵 【有利变更 (次日生效)】"
                analysis.isOverallAdverse -> "🔵 【有利变更 (随延迟生效)】"
                else -> "🔵 【有利变更 (立即生效)】"
            }
            sb.append("$label\n")
            favorable.forEach { sb.append("• ${it.text}\n") }
            sb.append("\n")
        }

        // 3. Adverse (Delayed)
        val adverse = analysis.details.filter { it.type == AppWindowManager.ChangeType.ADVERSE }
        if (adverse.isNotEmpty()) {
            sb.append("🔴 【不利变更 (次日生效)】\n")
            adverse.forEach { sb.append("• ${it.text}\n") }
            sb.append("\n")
        }
        
        // 🚀 检查该规则是否已有待生效的延迟变更，给用户提示
        if (targetRuleId != null) {
            val pendingRules = AppWindowManager.getPendingRules(requireContext())
            val pendingForThisRule = pendingRules?.find { it.id == targetRuleId }
            if (pendingForThisRule != null) {
                sb.append("\n\n📋 注意：该规则在「延迟生效表」中已有待生效变更：\n")
                sb.append("  - ${pendingForThisRule.name} (${AppWindowManager.formatTimeRange(pendingForThisRule)})\n")
                sb.append("本次修改将与上述变更累加，次日一起生效。")
            }
        }

        sb.append("\n确定要应用这些更改吗？")

        AlertDialog.Builder(requireContext())
            .setTitle(
                when {
                    isAllNextDayPolicy -> "⏳ 全部次日"
                    analysis.isOverallAdverse -> "⚠️ 包含延后生效项"
                    else -> "变更确认"
                }
            )
            .setMessage(sb.toString().trim())
            .setPositiveButton("确定") { _, _ -> onConfirm() }
            .setNegativeButton("取消") { _, _ -> refreshList() } // refresh logic needed for toggle revert
            .show()
    }
}
