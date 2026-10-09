package com.decikeeper.app.ui

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.cardview.widget.CardView
import androidx.fragment.app.Fragment
import com.decikeeper.app.R
import com.decikeeper.app.data.AnalysisResult
import com.decikeeper.app.manager.ChangeLogManager
import com.decikeeper.app.manager.CreditManager
import com.decikeeper.app.manager.EffectiveActivationPolicyManager
import com.decikeeper.app.manager.ProbationManager
import com.decikeeper.app.manager.TimeRuleEditWindowPolicyManager
import com.decikeeper.app.data.PrivilegeWindow
import com.decikeeper.app.manager.TimeRuleManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 时间规则页的规则参数第三页，支持失焦/改完即存与智能分流生效反馈。 */
class GlobalParamsFragment : Fragment() {

    private var isApplyingValues = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.activity_global_params_new, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        // 顶部留白统一：作为 FocusRulesFragment 的子 Fragment，不重复绑定系统状态栏 Insets
        setupUI(TimeRuleManager.isTimeRuleEditLocked(requireContext()))
    }

    override fun onResume() {
        super.onResume()
        if (!isAdded || view == null) return
        val context = requireContext()
        val promoted = ProbationManager.checkAndPromote(context)
        if (promoted.isNotEmpty()) {
            Toast.makeText(context, "已晋升 ${promoted.size} 个配置项为新基准", Toast.LENGTH_SHORT).show()
            reloadData()
            return
        }
        setupUI(TimeRuleManager.isTimeRuleEditLocked(context))
    }

    override fun onPause() {
        super.onPause()
        view?.findFocus()?.clearFocus()
    }

    fun reloadData() {
        if (isAdded && view != null) setupUI(TimeRuleManager.isTimeRuleEditLocked(requireContext()))
    }

    private fun setupUI(isLocked: Boolean = false) {
        val root = view ?: return
        val context = requireContext()
        isApplyingValues = true

        val etRest = root.findViewById<EditText>(R.id.et_cool_down_rest)
        val etGap = root.findViewById<EditText>(R.id.et_cool_down_gap)
        val etBlacklist = root.findViewById<EditText>(R.id.et_cool_down_blacklist)
        val etBorrowPercent = root.findViewById<EditText>(R.id.et_borrow_percentage)
        val etTempRestrictedAccessMax = root.findViewById<EditText>(R.id.et_temp_restricted_access_max_seconds)
        val etNotifyEntryFar = root.findViewById<EditText>(R.id.et_notify_entry_far)
        val etNotifyEntryNear = root.findViewById<EditText>(R.id.et_notify_entry_near)
        val etWeeklyPrivilegeCount = root.findViewById<EditText>(R.id.et_weekly_privilege_limit)
        val etWeeklyPrivilegeDuration = root.findViewById<EditText>(R.id.et_weekly_privilege_duration)
        val etPrivWindowStart = root.findViewById<EditText>(R.id.et_privilege_window_start)
        val etPrivWindowEnd = root.findViewById<EditText>(R.id.et_privilege_window_end)
        val layoutPrivWindowInputs = root.findViewById<View>(R.id.layout_privilege_window_inputs)

        val currentCoolDownRest = TimeRuleManager.getCoolDownRestSeconds(context)
        val currentCoolDownGap = TimeRuleManager.getCoolDownGapSeconds(context)
        val currentBorrow = TimeRuleManager.getBorrowPercentage(context)
        val currentTempRestrictedAccessMax = TimeRuleManager.getTemporaryRestrictedAccessMaxSeconds(context)
        val currentEntryFar = TimeRuleManager.getNotifyEntryFarMinutes(context)
        val currentEntryNear = TimeRuleManager.getNotifyEntryNearMinutes(context)
        val currentPrivilegeCount = CreditManager.getWeeklyPrivilegeLimit(context)
        val currentPrivilegeDuration = CreditManager.getWeeklyPrivilegeDurationLimit(context)
        val currentPrivWindowStart = TimeRuleManager.getPrivilegeWindowStart(context)
        val currentPrivWindowEnd = TimeRuleManager.getPrivilegeWindowEnd(context)
        val currentStrict = TimeRuleManager.getStrictPenaltyDurationSeconds(context)
        val hasPending = TimeRuleManager.hasPendingRules(context)

        fun getPendingInt(key: String, fallback: Int): Int {
            val prefs = context.getSharedPreferences("decikeeper_rules_pending", Context.MODE_PRIVATE)
            return if (hasPending && prefs.contains(key)) prefs.getInt(key, fallback) else fallback
        }

        val pendingCoolDownRest = getPendingInt("cool_down_rest_seconds", currentCoolDownRest)
        val pendingCoolDownGap = getPendingInt("cool_down_gap_seconds", currentCoolDownGap)
        val pendingBorrow = getPendingInt("borrow_percentage", currentBorrow)
        val pendingTempRestrictedAccessMax = getPendingInt("temp_restricted_access_max_seconds", currentTempRestrictedAccessMax)
        val pendingPrivilegeCount = if (CreditManager.hasPendingWeeklyPrivilegeLimit(context)) CreditManager.getPendingWeeklyPrivilegeLimit(context) else currentPrivilegeCount
        val pendingPrivilegeDuration = if (CreditManager.hasPendingWeeklyPrivilegeLimit(context)) CreditManager.getPendingWeeklyPrivilegeDurationLimit(context) else currentPrivilegeDuration
        val pendingPrivWindowStart = TimeRuleManager.getPendingPrivilegeWindowStart(context) ?: currentPrivWindowStart
        val pendingPrivWindowEnd = TimeRuleManager.getPendingPrivilegeWindowEnd(context) ?: currentPrivWindowEnd
        val pendingStrict = if (hasPending) TimeRuleManager.getPendingStrictPenaltyDurationSeconds(context) ?: currentStrict else currentStrict

        val rgEffectivePolicy = root.findViewById<RadioGroup>(R.id.rg_effective_policy)
        val rbPolicyFavorable = root.findViewById<RadioButton>(R.id.rb_policy_favorable)
        val rbPolicyAllNextDay = root.findViewById<RadioButton>(R.id.rb_policy_all_next_day)

        val currentEffectivePolicy = EffectiveActivationPolicyManager.getPolicy(context)
        if (currentEffectivePolicy == EffectiveActivationPolicyManager.Policy.ALL_NEXT_DAY) {
            rgEffectivePolicy?.check(R.id.rb_policy_all_next_day)
        } else {
            rgEffectivePolicy?.check(R.id.rb_policy_favorable)
        }

        etRest?.setText(pendingCoolDownRest.toString())
        etGap?.setText(pendingCoolDownGap.toString())
        etBlacklist?.setText(pendingStrict.toString())
        etBorrowPercent?.setText(pendingBorrow.toString())
        etTempRestrictedAccessMax?.setText(formatTemporaryAccessSecondsAsMinutes(pendingTempRestrictedAccessMax))
        etNotifyEntryFar?.setText(currentEntryFar.toString())
        etNotifyEntryNear?.setText(currentEntryNear.toString())
        etWeeklyPrivilegeCount?.setText(pendingPrivilegeCount.toString())
        etWeeklyPrivilegeDuration?.setText(pendingPrivilegeDuration.toString())
        etPrivWindowStart?.setText(pendingPrivWindowStart)
        etPrivWindowEnd?.setText(pendingPrivWindowEnd)

        if (isLocked) {
            listOfNotNull(etRest, etGap, etBlacklist, etBorrowPercent, etTempRestrictedAccessMax,
                etNotifyEntryFar, etNotifyEntryNear, etWeeklyPrivilegeCount, etWeeklyPrivilegeDuration,
                etPrivWindowStart, etPrivWindowEnd).forEach { it.isEnabled = false }
            rbPolicyFavorable?.isEnabled = false
            rbPolicyAllNextDay?.isEnabled = false
            rgEffectivePolicy?.isEnabled = false
        } else {
            listOfNotNull(etRest, etGap, etBlacklist, etBorrowPercent, etTempRestrictedAccessMax,
                etNotifyEntryFar, etNotifyEntryNear, etWeeklyPrivilegeCount, etWeeklyPrivilegeDuration,
                etPrivWindowStart, etPrivWindowEnd).forEach { it.isEnabled = true }
            rbPolicyFavorable?.isEnabled = true
            rbPolicyAllNextDay?.isEnabled = true
            rgEffectivePolicy?.isEnabled = true
        }

        val cardPending = root.findViewById<CardView>(R.id.card_pending_changes)
        val tvPending = root.findViewById<TextView>(R.id.tv_pending_changes)
        val tvLockout = root.findViewById<TextView>(R.id.tv_lockout_hint)
        if (isLocked) {
            cardPending?.visibility = View.VISIBLE
            tvLockout?.visibility = View.VISIBLE
            tvLockout?.text = TimeRuleEditWindowPolicyManager.getLockoutHintText(context)
        } else {
            tvLockout?.visibility = View.GONE
        }
        val isAdaptation = TimeRuleManager.isInAdaptationPeriod(context)
        val finalText = ChangeLogManager.getFormattedLogForCategory(context, "global_params").trim()
        if (!isAdaptation && finalText.isNotEmpty()) {
            cardPending?.visibility = View.VISIBLE
            tvPending?.text = finalText
            cardPending?.setOnClickListener {
                if (TimeRuleManager.hasPendingRules(context) || CreditManager.hasPendingApps(context)) showPendingRevokeDialog()
            }
        } else if (!isLocked) {
            cardPending?.visibility = View.GONE
        } else {
            tvPending?.text = ""
        }

        layoutPrivWindowInputs?.visibility = View.VISIBLE

        val btnSave = root.findViewById<View>(R.id.btn_save)
        btnSave?.visibility = View.GONE

        // 绑定自动保存监听器
        if (!isLocked) {
            rgEffectivePolicy?.setOnCheckedChangeListener { _, checkedId ->
                if (isApplyingValues) return@setOnCheckedChangeListener
                val targetPolicy = if (checkedId == R.id.rb_policy_all_next_day) {
                    EffectiveActivationPolicyManager.Policy.ALL_NEXT_DAY
                } else {
                    EffectiveActivationPolicyManager.Policy.FAVORABLE_IMMEDIATE
                }
                if (targetPolicy != EffectiveActivationPolicyManager.getPolicy(requireContext())) {
                    performSave()
                }
            }

            etRest?.let {
                setupAutoSave(it, { pendingCoolDownRest.toString() }, { text ->
                    val v = text.toIntOrNull()
                    Pair(v != null && v >= 0, "统一冷却必须为非负整数")
                })
            }
            etGap?.let {
                setupAutoSave(it, { pendingCoolDownGap.toString() }, { text ->
                    val v = text.toIntOrNull()
                    Pair(v != null && v >= 0, "空档冷却必须为非负整数")
                })
            }
            etBorrowPercent?.let {
                setupAutoSave(it, { pendingBorrow.toString() }, { text ->
                    val v = text.toIntOrNull()
                    Pair(v != null && v in 0..100, "借用比例必须在 0% ~ 100% 之间")
                })
            }
            etTempRestrictedAccessMax?.let {
                setupAutoSave(it, { formatTemporaryAccessSecondsAsMinutes(pendingTempRestrictedAccessMax) }, { text ->
                    Pair(isTemporaryAccessMinuteInputValid(text, 3.0), "临时使用受限应用时长上限必须在 0.1~3.0 分钟，最多 1 位小数")
                })
            }
            etNotifyEntryFar?.let {
                setupAutoSave(it, { currentEntryFar.toString() }, { text ->
                    val v = text.toIntOrNull()
                    Pair(v != null && v >= 0, "远期预警时间必须为非负整数")
                })
            }
            etNotifyEntryNear?.let {
                setupAutoSave(it, { currentEntryNear.toString() }, { text ->
                    val v = text.toIntOrNull()
                    Pair(v != null && v >= 0, "近期预警时间必须为非负整数")
                })
            }
            etWeeklyPrivilegeCount?.let {
                setupAutoSave(it, { pendingPrivilegeCount.toString() }, { text ->
                    val v = text.toIntOrNull()
                    Pair(v != null && v in 0..3, "每周特权次数不能超过 3 次")
                })
            }
            etWeeklyPrivilegeDuration?.let {
                setupAutoSave(it, { pendingPrivilegeDuration.toString() }, { text ->
                    val v = text.toIntOrNull()
                    Pair(v != null && v in 0..600, "每周特权时长不能超过 10 小时 (600 分钟)")
                })
            }
            // 特权可用范围必须落在业务日内：开始不早于生效时间，结束晚于开始且最晚 24:00。
            // 失焦即存时对每一次输入都校验，不合法的输入会被拒绝并还原。
            val privWindowMessage = { dayStart: String ->
                "特权可用范围必须在业务日内：开始不早于生效时间 $dayStart，结束晚于开始且最晚 24:00"
            }
            etPrivWindowStart?.let {
                setupAutoSave(it, { pendingPrivWindowStart }, { text ->
                    val dayStart = privilegeWindowBusinessDayStart()
                    val ok = PrivilegeWindow.parseMinutes(text) != null &&
                        PrivilegeWindow.isValid(formatTime(text), formatTime(etPrivWindowEnd?.text?.toString()?.trim().orEmpty()), dayStart)
                    Pair(ok, privWindowMessage(dayStart))
                }, onValidFormatted = { t -> formatTime(t) })
            }
            etPrivWindowEnd?.let {
                setupAutoSave(it, { pendingPrivWindowEnd }, { text ->
                    val dayStart = privilegeWindowBusinessDayStart()
                    val ok = PrivilegeWindow.parseMinutes(text) != null &&
                        PrivilegeWindow.isValid(formatTime(etPrivWindowStart?.text?.toString()?.trim().orEmpty()), formatTime(text), dayStart)
                    Pair(ok, privWindowMessage(dayStart))
                }, onValidFormatted = { t -> formatTime(t) })
            }
        }

        isApplyingValues = false
    }

    private fun setupAutoSave(
        editText: EditText,
        getOriginalValue: () -> String,
        validator: (String) -> Pair<Boolean, String?>,
        onValidFormatted: ((String) -> String)? = null
    ) {
        editText.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) return@setOnFocusChangeListener
            if (isApplyingValues || !isAdded || view == null) return@setOnFocusChangeListener
            if (TimeRuleManager.isTimeRuleEditLocked(requireContext())) return@setOnFocusChangeListener
            val currentText = editText.text.toString().trim()
            val original = getOriginalValue().trim()
            if (currentText == original) return@setOnFocusChangeListener

            val (isValid, errorMsg) = validator(currentText)
            if (!isValid) {
                Toast.makeText(requireContext(), errorMsg ?: "输入格式不正确", Toast.LENGTH_SHORT).show()
                editText.setText(original)
                return@setOnFocusChangeListener
            }
            onValidFormatted?.let { formatFn ->
                val formatted = formatFn(currentText)
                if (formatted != currentText) editText.setText(formatted)
            }
            performSave()
        }
        editText.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_DONE ||
                actionId == android.view.inputmethod.EditorInfo.IME_ACTION_NEXT) {
                editText.clearFocus()
                true
            } else {
                false
            }
        }
    }

    /** 特权范围校验用的业务日开始：优先取待生效的生效时间（次日起生效），否则取当前生效时间。 */
    private fun privilegeWindowBusinessDayStart(): String {
        val context = requireContext()
        return TimeRuleManager.getPendingRefreshTime(context) ?: TimeRuleManager.getRefreshTime(context)
    }

    private fun isValidTimeFormat(t: String): Boolean {
        if (!t.contains(":")) return false
        val parts = t.split(":")
        if (parts.size != 2) return false
        val h = parts[0].toIntOrNull() ?: return false
        val m = parts[1].toIntOrNull() ?: return false
        return h in 0..23 && m in 0..59
    }

    private fun formatTime(t: String): String {
        if (t.contains(":")) {
            val parts = t.split(":")
            if (parts.size == 2) {
                val h = parts[0].toIntOrNull() ?: 0
                val m = parts[1].toIntOrNull() ?: 0
                return String.format("%02d:%02d", h, m)
            }
        }
        return t
    }

    private fun performSave() {
        if (!isAdded || view == null) return
        val context = requireContext()
        if (TimeRuleManager.isTimeRuleEditLocked(context)) return

        val root = view ?: return
        val etRest = root.findViewById<EditText>(R.id.et_cool_down_rest)
        val etGap = root.findViewById<EditText>(R.id.et_cool_down_gap)
        val etBlacklist = root.findViewById<EditText>(R.id.et_cool_down_blacklist)
        val etBorrowPercent = root.findViewById<EditText>(R.id.et_borrow_percentage)
        val etTempRestrictedAccessMax = root.findViewById<EditText>(R.id.et_temp_restricted_access_max_seconds)
        val etNotifyEntryFar = root.findViewById<EditText>(R.id.et_notify_entry_far)
        val etNotifyEntryNear = root.findViewById<EditText>(R.id.et_notify_entry_near)
        val etWeeklyPrivilegeCount = root.findViewById<EditText>(R.id.et_weekly_privilege_limit)
        val etWeeklyPrivilegeDuration = root.findViewById<EditText>(R.id.et_weekly_privilege_duration)
        val etPrivWindowStart = root.findViewById<EditText>(R.id.et_privilege_window_start)
        val etPrivWindowEnd = root.findViewById<EditText>(R.id.et_privilege_window_end)
        val rgEffectivePolicy = root.findViewById<RadioGroup>(R.id.rg_effective_policy)

        val currentCoolDownRest = TimeRuleManager.getCoolDownRestSeconds(context)
        val currentCoolDownGap = TimeRuleManager.getCoolDownGapSeconds(context)
        val currentBorrow = TimeRuleManager.getBorrowPercentage(context)
        val currentTempRestrictedAccessMax = TimeRuleManager.getTemporaryRestrictedAccessMaxSeconds(context)
        val currentEntryFar = TimeRuleManager.getNotifyEntryFarMinutes(context)
        val currentEntryNear = TimeRuleManager.getNotifyEntryNearMinutes(context)
        val currentPrivilegeCount = CreditManager.getWeeklyPrivilegeLimit(context)
        val currentPrivilegeDuration = CreditManager.getWeeklyPrivilegeDurationLimit(context)
        val currentPrivWindowStart = TimeRuleManager.getPrivilegeWindowStart(context)
        val currentPrivWindowEnd = TimeRuleManager.getPrivilegeWindowEnd(context)
        val currentStrict = TimeRuleManager.getStrictPenaltyDurationSeconds(context)

        val newRest = etRest?.text?.toString()?.toIntOrNull() ?: currentCoolDownRest
        val newGap = etGap?.text?.toString()?.toIntOrNull() ?: currentCoolDownGap
        val newLockDuration = etBlacklist?.text?.toString()?.toLongOrNull() ?: currentStrict
        val newBorrow = etBorrowPercent?.text?.toString()?.toIntOrNull() ?: currentBorrow
        val tempMaxRaw = etTempRestrictedAccessMax?.text?.toString()?.trim() ?: ""
        val newTempRestrictedAccessMax = if (isTemporaryAccessMinuteInputValid(tempMaxRaw, 3.0)) {
            temporaryAccessMinutesToSeconds(tempMaxRaw.toDouble()).coerceIn(1, 180)
        } else {
            currentTempRestrictedAccessMax
        }
        val newWeeklyCount = etWeeklyPrivilegeCount?.text?.toString()?.toIntOrNull() ?: currentPrivilegeCount
        val newWeeklyDuration = etWeeklyPrivilegeDuration?.text?.toString()?.toIntOrNull() ?: currentPrivilegeDuration
        val newNotifyFar = etNotifyEntryFar?.text?.toString()?.toIntOrNull() ?: currentEntryFar
        val newNotifyNear = etNotifyEntryNear?.text?.toString()?.toIntOrNull() ?: currentEntryNear

        val newPrivWindowStart = formatTime(etPrivWindowStart?.text?.toString()?.trim() ?: currentPrivWindowStart)
        val newPrivWindowEnd = formatTime(etPrivWindowEnd?.text?.toString()?.trim() ?: currentPrivWindowEnd)

        val selectedPolicy = if (rgEffectivePolicy?.checkedRadioButtonId == R.id.rb_policy_all_next_day) {
            EffectiveActivationPolicyManager.Policy.ALL_NEXT_DAY
        } else {
            EffectiveActivationPolicyManager.Policy.FAVORABLE_IMMEDIATE
        }
        val preservedRefreshTime = TimeRuleManager.getPendingRefreshTime(context) ?: TimeRuleManager.getRefreshTime(context)
        val result = TimeRuleManager.analyzeAndSaveGlobalParams(
            context, newRest, newGap, newLockDuration, newBorrow, newNotifyFar, newNotifyNear,
            newWeeklyCount, newWeeklyDuration, preservedRefreshTime, newTempRestrictedAccessMax,
            newPrivWindowStart, newPrivWindowEnd,
            newEffectiveActivationPolicy = selectedPolicy
        )
        handleSaveFeedback(result)
    }

    private fun handleSaveFeedback(result: AnalysisResult) {
        if (!isAdded || view == null) return
        val context = requireContext()
        if (TimeRuleManager.isInAdaptationPeriod(context)) {
            Toast.makeText(context, "✅ 设置已保存并立即生效 (适应期)", Toast.LENGTH_SHORT).show()
            reloadData()
            return
        }

        when (result) {
            is AnalysisResult.NoChange -> {
                reloadData()
            }
            is AnalysisResult.AllImmediate -> {
                val msg = if (result.messages.isNotEmpty()) {
                    "✅ 立即生效:\n" + result.messages.joinToString("\n• ", prefix = "• ")
                } else {
                    "✅ 设置已保存并立即生效"
                }
                Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                reloadData()
            }
            is AnalysisResult.Mixed -> {
                val isAllNextDayPolicy =
                    EffectiveActivationPolicyManager.getPolicy(context) == EffectiveActivationPolicyManager.Policy.ALL_NEXT_DAY
                val hasDelayed = result.delayedChanges.isNotEmpty() || result.nextWeekChanges.isNotEmpty()

                if (hasDelayed) {
                    val sb = StringBuilder()
                    if (result.immediateChanges.isNotEmpty()) {
                        sb.append("✅ 立即生效 (有利项):\n")
                        result.immediateChanges.forEach { sb.append("• $it\n") }
                        sb.append("\n")
                    }
                    if (result.delayedChanges.isNotEmpty()) {
                        val timeStr = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(result.effectiveTime))
                        val delayedTitle = if (isAllNextDayPolicy) {
                            "🌅 次日 $timeStr 生效 (当前为「统一延后」):\n"
                        } else {
                            "🌅 次日 $timeStr 生效 (放宽限制):\n"
                        }
                        sb.append(delayedTitle)
                        result.delayedChanges.forEach { sb.append("• $it\n") }
                        sb.append("\n")
                    }
                    if (result.nextWeekChanges.isNotEmpty()) {
                        val effTime = if (result.nextWeekEffectiveTime > 0) result.nextWeekEffectiveTime else result.effectiveTime
                        val dateStr = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(effTime))
                        sb.append("📅 下周生效 ($dateStr):\n")
                        result.nextWeekChanges.forEach { sb.append("• $it\n") }
                    }

                    val title = if (!isAllNextDayPolicy) "⚠️ 包含延后生效项" else "变更生效分析"
                    AlertDialog.Builder(context)
                        .setTitle(title)
                        .setMessage(sb.toString().trim())
                        .setCancelable(false)
                        .setPositiveButton("确认仍要修改") { _, _ ->
                            reloadData()
                        }
                        .setNegativeButton("取消") { _, _ ->
                            TimeRuleManager.revokePendingChanges(context)
                            CreditManager.clearPendingWeeklyPrivilegeLimit(context)
                            ChangeLogManager.clearPendingLogsForCategory(context, "global_params")
                            Toast.makeText(context, "已取消修改并恢复原值", Toast.LENGTH_SHORT).show()
                            reloadData()
                        }
                        .show()
                } else {
                    val msg = if (result.immediateChanges.isNotEmpty()) {
                        "✅ 立即生效:\n" + result.immediateChanges.joinToString("\n• ", prefix = "• ")
                    } else {
                        "✅ 设置已保存"
                    }
                    Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                    reloadData()
                }
            }
            is AnalysisResult.AllDelayed -> {
                AlertDialog.Builder(context)
                    .setTitle("⚠️ 包含延后生效项")
                    .setMessage("所有修改均属于放宽限制，将在明日生效。")
                    .setCancelable(false)
                    .setPositiveButton("确认仍要修改") { _, _ ->
                        reloadData()
                    }
                    .setNegativeButton("取消") { _, _ ->
                        TimeRuleManager.revokePendingChanges(context)
                        CreditManager.clearPendingWeeklyPrivilegeLimit(context)
                        ChangeLogManager.clearPendingLogsForCategory(context, "global_params")
                        Toast.makeText(context, "已取消修改并恢复原值", Toast.LENGTH_SHORT).show()
                        reloadData()
                    }
                    .show()
            }
        }
    }

    private fun showPendingRevokeDialog() {
        val context = requireContext()
        AlertDialog.Builder(context).setTitle("撤销待生效的不利修改")
            .setMessage("确定要撤销所有尚未生效的不利修改吗？\n撤销后，这些修改将不会在明日生效。")
            .setPositiveButton("确认撤销") { _, _ ->
                TimeRuleManager.revokePendingChanges(context)
                CreditManager.clearPendingWeeklyPrivilegeLimit(context)
                ChangeLogManager.clearPendingLogsForCategory(context, "global_params")
                Toast.makeText(context, "已撤销所有待生效修改", Toast.LENGTH_SHORT).show()
                reloadData()
            }.setNegativeButton("取消", null).show()
    }
}
