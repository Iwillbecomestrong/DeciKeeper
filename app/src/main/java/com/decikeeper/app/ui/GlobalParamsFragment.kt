package com.decikeeper.app.ui

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import com.decikeeper.app.R
import com.decikeeper.app.data.AdverseAnalysis
import com.decikeeper.app.manager.ChangeLogManager
import com.decikeeper.app.manager.CreditManager
import com.decikeeper.app.manager.EffectiveActivationPolicyManager
import com.decikeeper.app.manager.ProbationManager
import com.decikeeper.app.manager.TimeRuleEditWindowPolicyManager
import com.decikeeper.app.manager.TimeRuleManager

/** 时间规则页的全局参数第三页，复用原全局参数页面的完整编辑逻辑。 */
class GlobalParamsFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.activity_global_params_new, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val rootView = view.findViewById<View>(R.id.root_global_params)
        val initialPaddingLeft = rootView.paddingLeft
        val initialPaddingTop = rootView.paddingTop
        val initialPaddingRight = rootView.paddingRight
        val initialPaddingBottom = rootView.paddingBottom
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(rootView) { v, insets ->
            val bars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            v.setPadding(initialPaddingLeft, initialPaddingTop + bars.top, initialPaddingRight, initialPaddingBottom + bars.bottom)
            insets
        }
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

    fun reloadData() {
        if (isAdded && view != null) setupUI(TimeRuleManager.isTimeRuleEditLocked(requireContext()))
    }

    private fun setupUI(isLocked: Boolean = false) {
        val root = view ?: return
        val context = requireContext()
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
        val layoutPrivWindowInputs = root.findViewById<android.widget.LinearLayout>(R.id.layout_privilege_window_inputs)

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

        val rgEffectivePolicy = root.findViewById<android.widget.RadioGroup>(R.id.rg_effective_policy)
        val rbPolicyFavorable = root.findViewById<android.widget.RadioButton>(R.id.rb_policy_favorable)
        val rbPolicyAllNextDay = root.findViewById<android.widget.RadioButton>(R.id.rb_policy_all_next_day)

        val currentEffectivePolicy = EffectiveActivationPolicyManager.getPolicy(context)
        if (currentEffectivePolicy == EffectiveActivationPolicyManager.Policy.ALL_NEXT_DAY) {
            rgEffectivePolicy?.check(R.id.rb_policy_all_next_day)
        } else {
            rgEffectivePolicy?.check(R.id.rb_policy_favorable)
        }

        etRest.setText(pendingCoolDownRest.toString())
        etGap.setText(pendingCoolDownGap.toString())
        etBlacklist.setText(pendingStrict.toString())
        etBorrowPercent.setText(pendingBorrow.toString())
        etTempRestrictedAccessMax.setText(formatTemporaryAccessSecondsAsMinutes(pendingTempRestrictedAccessMax))
        etNotifyEntryFar.setText(currentEntryFar.toString())
        etNotifyEntryNear.setText(currentEntryNear.toString())
        etWeeklyPrivilegeCount.setText(pendingPrivilegeCount.toString())
        etWeeklyPrivilegeDuration.setText(pendingPrivilegeDuration.toString())
        etPrivWindowStart.setText(pendingPrivWindowStart)
        etPrivWindowEnd.setText(pendingPrivWindowEnd)

        if (isLocked) {
            listOf(etRest, etGap, etBlacklist, etBorrowPercent, etTempRestrictedAccessMax,
                etNotifyEntryFar, etNotifyEntryNear, etWeeklyPrivilegeCount, etWeeklyPrivilegeDuration,
                etPrivWindowStart, etPrivWindowEnd).forEach { it.isEnabled = false }
            rbPolicyFavorable?.isEnabled = false
            rbPolicyAllNextDay?.isEnabled = false
            rgEffectivePolicy?.isEnabled = false
        }

        val cardPending = root.findViewById<androidx.cardview.widget.CardView>(R.id.card_pending_changes)
        val tvPending = root.findViewById<android.widget.TextView>(R.id.tv_pending_changes)
        val tvLockout = root.findViewById<android.widget.TextView>(R.id.tv_lockout_hint)
        if (isLocked) {
            cardPending.visibility = View.VISIBLE
            tvLockout.visibility = View.VISIBLE
            tvLockout.text = TimeRuleEditWindowPolicyManager.getLockoutHintText(context)
        } else {
            tvLockout.visibility = View.GONE
        }
        val isAdaptation = TimeRuleManager.isInAdaptationPeriod(context)
        val finalText = ChangeLogManager.getFormattedLogForCategory(context, "global_params").trim()
        if (!isAdaptation && finalText.isNotEmpty()) {
            cardPending.visibility = View.VISIBLE
            tvPending.text = finalText
            cardPending.setOnClickListener {
                if (TimeRuleManager.hasPendingRules(context) || CreditManager.hasPendingApps(context)) showPendingRevokeDialog()
            }
        } else if (!isLocked) cardPending.visibility = View.GONE else tvPending.text = ""

        layoutPrivWindowInputs.visibility = View.VISIBLE

        val btnSave = root.findViewById<View>(R.id.btn_save)
        if (isLocked) {
            btnSave.isEnabled = false
            btnSave.alpha = 0.5f
        }

        btnSave.setOnClickListener {
            if (isLocked) return@setOnClickListener
            val newRest = etRest.text.toString().toIntOrNull() ?: currentCoolDownRest
            val newGap = etGap.text.toString().toIntOrNull() ?: currentCoolDownGap
            val newLockDuration = etBlacklist.text.toString().toLongOrNull() ?: currentStrict
            val newBorrow = etBorrowPercent.text.toString().toIntOrNull() ?: currentBorrow
            val tempMaxRaw = etTempRestrictedAccessMax.text.toString().trim()
            val newTempRestrictedAccessMax = if (isTemporaryAccessMinuteInputValid(tempMaxRaw, 3.0)) temporaryAccessMinutesToSeconds(tempMaxRaw.toDouble()).coerceIn(1, 180) else -1
            val newWeeklyCount = etWeeklyPrivilegeCount.text.toString().toIntOrNull() ?: currentPrivilegeCount
            val newWeeklyDuration = etWeeklyPrivilegeDuration.text.toString().toIntOrNull() ?: currentPrivilegeDuration
            if (newTempRestrictedAccessMax !in 1..180) {
                Toast.makeText(context, "临时使用受限应用时长上限必须在 0.1~3.0 分钟，最多 1 位小数", Toast.LENGTH_SHORT).show(); return@setOnClickListener
            }
            if (newWeeklyCount > 3) { Toast.makeText(context, "每周特权次数不能超过 3 次", Toast.LENGTH_SHORT).show(); return@setOnClickListener }
            if (newWeeklyDuration > 600) { Toast.makeText(context, "每周特权时长不能超过 10 小时 (600 分钟)", Toast.LENGTH_SHORT).show(); return@setOnClickListener }

            val newNotifyFar = etNotifyEntryFar.text.toString().toIntOrNull() ?: currentEntryFar
            val newNotifyNear = etNotifyEntryNear.text.toString().toIntOrNull() ?: currentEntryNear

            var newPrivWindowStart = etPrivWindowStart.text.toString().trim()
            var newPrivWindowEnd = etPrivWindowEnd.text.toString().trim()
            fun formatTime(t: String): String {
                if (t.contains(":")) {
                    val p = t.split(":")
                    if (p.size == 2) return String.format("%02d:%02d", p[0].toIntOrNull() ?: 0, p[1].toIntOrNull() ?: 0)
                }
                return t
            }
            newPrivWindowStart = formatTime(newPrivWindowStart)
            newPrivWindowEnd = formatTime(newPrivWindowEnd)
            val businessDayStart = TimeRuleManager.getRefreshTime(context)
            if (!com.decikeeper.app.data.PrivilegeWindow.isValid(newPrivWindowStart, newPrivWindowEnd, businessDayStart)) {
                Toast.makeText(context, "特权可用范围必须在业务日内：开始不早于生效时间 $businessDayStart，结束晚于开始且最晚 24:00", Toast.LENGTH_LONG).show(); return@setOnClickListener
            }
            val oldPolicy = EffectiveActivationPolicyManager.getPolicy(context)
            val selectedPolicy = if (rgEffectivePolicy?.checkedRadioButtonId == R.id.rb_policy_all_next_day) {
                EffectiveActivationPolicyManager.Policy.ALL_NEXT_DAY
            } else {
                EffectiveActivationPolicyManager.Policy.FAVORABLE_IMMEDIATE
            }
            val policyChanged = oldPolicy != selectedPolicy
            if (policyChanged) {
                EffectiveActivationPolicyManager.setPolicy(context, selectedPolicy)
            }
            val preservedRefreshTime = TimeRuleManager.getPendingRefreshTime(context) ?: TimeRuleManager.getRefreshTime(context)
            val result = TimeRuleManager.analyzeAndSaveGlobalParams(
                context, newRest, newGap, newLockDuration, newBorrow, newNotifyFar, newNotifyNear,
                newWeeklyCount, newWeeklyDuration, preservedRefreshTime, newTempRestrictedAccessMax,
                newPrivWindowStart, newPrivWindowEnd
            )
            if (policyChanged && result is com.decikeeper.app.data.AnalysisResult.NoChange) {
                val policyTitle = EffectiveActivationPolicyManager.getPolicyTitle(selectedPolicy)
                Toast.makeText(context, "设置已保存：生效策略已更新为【$policyTitle】", Toast.LENGTH_SHORT).show()
                reloadData()
            } else {
                AdverseAnalysis.showAnalysisDialog(context, result) { reloadData() }
            }
        }
    }

    private fun showPendingRevokeDialog() {
        val context = requireContext()
        AlertDialog.Builder(context).setTitle("撤销待生效的不利修改")
            .setMessage("确定要撤销所有尚未生效的不利修改吗？\n撤销后，这些修改将不会在明日生效。")
            .setPositiveButton("确认撤销") { _, _ ->
                TimeRuleManager.revokePendingChanges(context)
                CreditManager.revokePendingChanges(context)
                Toast.makeText(context, "已撤销所有待生效修改", Toast.LENGTH_SHORT).show()
                reloadData()
            }.setNegativeButton("取消", null).show()
    }
}
