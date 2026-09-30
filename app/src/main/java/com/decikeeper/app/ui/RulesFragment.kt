/**
 * =================================================================================
 * [Context Header]
 * * @file: RulesFragment.kt
 * * @role: UI
 * * @description: 设置页规则入口与权限开关的交互控制。
 * * @responsibilities:
 * 1. 渲染防卸载/每周特权等设置入口状态。
 * 2. 处理用户点击并触发对应的业务逻辑入口。
 * * @context:
 * - 上游: MainActivity / 设置入口
 * - 下游: AntiUninstallManager, CreditManager
 * * @constraints (必读):
 * 1. 不承载复杂业务计算，状态判定委托给 Manager。
 * 2. UI 更新需与业务状态保持一致，避免误导。
 * 3. 需处理无障碍未开启的异常路径。
 * =================================================================================
 */
package com.decikeeper.app.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.decikeeper.app.R
import com.decikeeper.app.data.LogExporter
import com.decikeeper.app.manager.A11ySelfDisableAuthorization
import com.decikeeper.app.manager.AccessibilityControlManager
import com.decikeeper.app.manager.SelfDisableReason
import com.decikeeper.app.manager.SettingsNavigator
import com.decikeeper.app.manager.AccessibilityDependencyManager
import com.decikeeper.app.manager.AlarmPowerOffProtectionManager
import com.decikeeper.app.manager.AntiUninstallManager
import com.decikeeper.app.manager.ConfigPendingManager
import com.decikeeper.app.manager.CreditManager
import com.decikeeper.app.manager.FloatingWindowProtectionManager
import com.decikeeper.app.manager.GuardFloatingWindowManager
import com.decikeeper.app.manager.ManualPermissionRecoveryManager
import com.decikeeper.app.manager.PermissionManager
import com.decikeeper.app.manager.PrivacyProtectionManager
import com.decikeeper.app.manager.RecentTasksHideManager
import com.decikeeper.app.manager.RomPermissionDetector
import com.decikeeper.app.manager.SettingsBackupManager
import com.decikeeper.app.manager.TimeRuleEditWindowPolicyManager
import com.decikeeper.app.manager.TimeRuleManager
import com.decikeeper.app.service.MonitorService
















class RulesFragment : Fragment() {

    internal data class AntiUninstallPrerequisiteState(
        val baseReady: Boolean,
        val bgBatteryReady: Boolean,
        val assocStartReady: Boolean
    ) {
        val allReady: Boolean
            get() = baseReady && bgBatteryReady && assocStartReady
    }

    companion object {
        private const val AUTO_START_CONFIRMED_KEY = "auto_start_confirmed"
        private const val AUTO_START_CONFIRMED_AT_KEY = "auto_start_confirmed_at"
        private const val BG_POPUP_CONFIRMED_KEY = "bg_popup_confirmed"
        private const val BG_POPUP_CONFIRMED_AT_KEY = "bg_popup_confirmed_at"
        private const val ASSOC_START_CONFIRMED_KEY = "assoc_start_confirmed"
        private const val ASSOC_START_CONFIRMED_AT_KEY = "assoc_start_confirmed_at"
        private const val KEY_A11Y_PENDING_DISABLE = "a11y_pending_disable"

        internal fun evaluateAntiUninstallPrerequisiteState(
            baseReady: Boolean,
            bgBatteryReady: Boolean,
            assocStartReady: Boolean
        ): AntiUninstallPrerequisiteState {
            return AntiUninstallPrerequisiteState(
                baseReady = baseReady,
                bgBatteryReady = bgBatteryReady,
                assocStartReady = assocStartReady
            )
        }

        internal fun isShortTimeManualConfirmValid(
            confirmed: Boolean,
            confirmedAtMillis: Long,
            nowMillis: Long,
            validWindowMillis: Long
        ): Boolean {
            return confirmed
        }

        internal fun shouldBlockAntiUninstallEnable(
            isAccessibilityEnabled: Boolean,
            allPrerequisitesReady: Boolean
        ): Boolean {
            return !isAccessibilityEnabled || !allPrerequisitesReady
        }

        internal fun isA11yReady(systemEnabled: Boolean, pendingDisable: Boolean): Boolean {
            return systemEnabled && !pendingDisable
        }

        internal fun shouldShowWeeklyPrivilegeEntryInSettings(): Boolean = false

        // ─────────────────────────────────────────────────────────────────────
        // 隐私空间拦截 (TASK-02): 可单测的判定函数
        // 这些函数是 UI 的"单一来源", UI / 协同关闭 / 文案 / Switch 状态全部
        // 经过它们, 禁止在 UI 或测试中重复推导 ACTIVE/PENDING 组合.
        // ─────────────────────────────────────────────────────────────────────

        /**
         * 开启前置: 无障碍未启用 → 阻止开启, 必须先引导开启无障碍.
         */
        internal fun shouldBlockPrivacySpaceGuardEnable(isAccessibilityEnabled: Boolean): Boolean {
            return !isAccessibilityEnabled
        }

        /**
         * Switch 显示状态: 只有 ACTIVE 时 checked.
         * 真机反馈: 之前 PENDING 也显示为开 (由 isEnforcing 推出), 视觉上与"已申请关闭"
         * 的状态矛盾. 改为对齐防关权限/防卸载 PENDING 视觉: PENDING/DISABLED 都 unchecked.
         *
         * 拦截语义保持不变: 门禁仍由 [PrivacyProtectionManager.isEnforcing] 控制,
         * PENDING 期 isEnforcing=true 仍拦; 此函数仅决定 Switch 视觉.
         */
        internal fun computePrivacySpaceGuardSwitchChecked(
            state: PrivacyProtectionManager.State,
            isEnforcing: Boolean
        ): Boolean {
            // 仅 ACTIVE 显示为开; PENDING/DISABLED 一律 unchecked
            // isEnforcing 参数保留为接口兼容与未来调试, 视觉决策改为只看 state
            return state == PrivacyProtectionManager.State.ACTIVE
        }

        /**
         * 文案映射:
         * - DISABLED → "未开启"
         * - ACTIVE → "已开启"
         * - PENDING → "待关闭（明日生效）"
         */
        internal fun computePrivacySpaceGuardUiText(
            state: PrivacyProtectionManager.State,
            isEnforcing: Boolean
        ): String {
            // isEnforcing 保留为保护链路的显式输入；PENDING 仍由 Manager 强制执行。
            return when (state) {
                PrivacyProtectionManager.State.PENDING -> "待关闭（明日生效）"
                PrivacyProtectionManager.State.ACTIVE -> "已开启"
                PrivacyProtectionManager.State.DISABLED -> "未开启"
            }
        }

        /**
         * 无障碍协同关闭资格判定:
         * - isEnforcing=true 且非 PENDING → 提交关闭 (走 strictNextDay)
         * - 已 PENDING → 不重复提交 (避免重置到期时间)
         * - DISABLED (isEnforcing=false) → 不提交
         */
        internal fun shouldRequestPrivacySpaceGuardDisableOnA11yDisable(
            isEnforcing: Boolean,
            isPending: Boolean
        ): Boolean {
            return isEnforcing && !isPending
        }

        /**
         * 无障碍协同关闭对话框的功能清单条目.
         * 真机反馈: 之前用 isEnforcing 判定才列出, 导致真机对话框看不到"隐私空间拦截"项.
         * 改为无条件硬列 (与防卸载/防关权限/闹钟防关机同级), 提交逻辑由调用方根据
         * ACTIVE 状态自行决定是否真正调用 setProtectionEnabled(ctx, false).
         */
        internal fun a11yCollaborativeDisableDialogItem(
            isEnforcing: Boolean,
            isPending: Boolean
        ): String {
            return "  • 隐私空间拦截 → 次日生效"
        }
    }

    internal data class AlarmPowerOffPrerequisiteState(
        val missingLabels: List<String>
    ) {
        val allReady: Boolean
            get() = missingLabels.isEmpty()
    }

    private var switchUninstallAndPermProtection: Switch? = null
    private var tvUninstallAndPermProtectionStatus: TextView? = null
    private var switchFloatingWindowProtection: Switch? = null
    private var tvFloatingWindowProtectionStatus: TextView? = null
    private var tvAccessibilityStatus: TextView? = null
    private var switchAccessibilityService: Switch? = null
    private var switchAlarmPowerOffProtection: Switch? = null
    private var tvAlarmPowerOffProtectionStatus: TextView? = null
    private var switchFloatingGuard: Switch? = null
    private var tvFloatingGuardStatus: TextView? = null
    private var switchFloatingGuardUiUpdating = false
    private var layoutDeviceAdminProtection: View? = null
    private var dividerDeviceAdmin: View? = null
    private var tvDeviceAdminProtectionStatus: TextView? = null
    private var switchDeviceAdminProtection: Switch? = null
    private var switchDeviceAdminProtectionUiUpdating = false
    private var lastDeviceAdminSwitchClickTime = 0L
    private var layoutPrivacySpaceGuard: View? = null
    private var dividerPrivacySpaceGuard: View? = null
    private var switchPrivacySpaceGuard: Switch? = null
    private var tvPrivacySpaceGuardStatus: TextView? = null
    private var switchRefreshSyncRules: Switch? = null
    private var tvRefreshSyncHintRules: TextView? = null
    private var tvRefreshTimeValueRules: TextView? = null
    private var switchRefreshSyncRulesUiUpdating = false
    private var rgSettingsEditWindowPolicy: RadioGroup? = null
    private var rbSettingsEditWindowAllDay: RadioButton? = null
    private var rbSettingsEditWindowEffectiveToMidnight: RadioButton? = null
    private var tvSettingsLockoutHint: TextView? = null
    private var tvSettingsEditWindowPending: TextView? = null
    private var isEditWindowPolicyUiUpdating = false
    private var btnQuickSendLogs: Button? = null
    private var btnExportSettings: Button? = null
    private var btnImportSettings: Button? = null

    private var pendingExportCategories: List<SettingsBackupManager.BackupCategory>? = null

    private val exportSettingsLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri: Uri? ->
        val categories = pendingExportCategories
        pendingExportCategories = null
        if (uri != null && categories != null) {
            exportSettingsToUri(uri, categories)
        }
    }

    private val importSettingsLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let { showImportCategoryDialog(it) }
    }

    // Protection Switch Debounces
    private var lastSwitchClickTime = 0L
    private var lastFloatingWindowSwitchClickTime = 0L
    private var lastUninstallAndPermSwitchClickTime = 0L
    private var lastA11yServiceSwitchClickTime = 0L
    private var lastPrivacySpaceGuardSwitchClickTime = 0L
    private var switchUninstallAndPermUiUpdating = false
    private var switchFloatingWindowUiUpdating = false
    private var switchA11yServiceUiUpdating = false
    private var switchAlarmPowerOffUiUpdating = false
    private var switchPrivacySpaceGuardUiUpdating = false
    private var alarmPrerequisiteDialogRefresh: (() -> Unit)? = null
    private var permProtectionGuideDialogRefresh: (() -> Unit)? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_rules, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        initViews(view)
        setupClickEvents(view)
        // Weekly privilege entry has been migrated to Home bottom area.
        view.findViewById<View>(R.id.card_weekly_privilege)?.visibility =
            if (shouldShowWeeklyPrivilegeEntryInSettings()) View.VISIBLE else View.GONE
    }

    override fun onResume() {
        super.onResume()
        val context = requireContext()
        val sysA11y = isAccessibilityServiceEnabled(context)
        val runIdTag = com.decikeeper.app.manager.ProtectionDiagnostics.currentTestRunId?.let { "runId=$it " } ?: ""
        android.util.Log.i("RulesFragment", "[RULES_ON_RESUME] ${runIdTag}systemA11yEnabled=$sysA11y")
        com.decikeeper.app.manager.ProtectionDiagnostics.dumpSnapshot(context, trigger = "RulesFragment.onResume")
        AccessibilityDependencyManager.reconcile(
            context,
            systemAccessibilityEnabled = sysA11y,
            caller = "RulesFragment.onResume"
        )
        updateAccessibilityServiceUI()
        updateEditWindowPolicyUI()
        updateRefreshTimeUI()
        updateAlarmPowerOffProtectionUI()
        updateFloatingWindowProtectionUI()
        updatePrivacySpaceGuardUI()
        updateUninstallAndPermProtectionUI()
        alarmPrerequisiteDialogRefresh?.invoke()
        permProtectionGuideDialogRefresh?.invoke()
        updateFloatingGuardUI()
        updateDeviceAdminProtectionUI()

        // 🚀 [Next-Day A11y Expiry Check] 检测昨日次日延迟关闭是否已在今日正式生效
        if (AccessibilityControlManager.consumeNextDayDisableBecameEffective(context) && sysA11y) {
            showNextDayA11yDisableGuideDialog(context)
        }

        // Loop check for anti-uninstall status
        view?.let { triggerAntiUninstallStatusLoop(it) }
    }

    override fun onDestroyView() {
        alarmPrerequisiteDialogRefresh = null
        permProtectionGuideDialogRefresh = null
        super.onDestroyView()
    }

    private fun initViews(view: View) {
        switchRefreshSyncRules = view.findViewById(R.id.switch_refresh_sync_rules)
        tvRefreshSyncHintRules = view.findViewById(R.id.tv_refresh_sync_hint_rules)
        tvRefreshTimeValueRules = view.findViewById(R.id.tv_refresh_time_value_rules)

        rgSettingsEditWindowPolicy = view.findViewById(R.id.rg_settings_edit_window_policy)
        rbSettingsEditWindowAllDay = view.findViewById(R.id.rb_settings_edit_window_all_day)
        rbSettingsEditWindowEffectiveToMidnight = view.findViewById(R.id.rb_settings_edit_window_effective_to_midnight)
        tvSettingsLockoutHint = view.findViewById(R.id.tv_settings_lockout_hint)
        tvSettingsEditWindowPending = view.findViewById(R.id.tv_settings_edit_window_pending)

        switchAlarmPowerOffProtection = view.findViewById(R.id.switch_alarm_power_off_protection)
        tvAlarmPowerOffProtectionStatus = view.findViewById(R.id.tv_alarm_power_off_status_main)
        switchFloatingWindowProtection = view.findViewById(R.id.switch_floating_window_protection)
        tvFloatingWindowProtectionStatus = view.findViewById(R.id.tv_floating_window_protection_status)
        layoutPrivacySpaceGuard = view.findViewById(R.id.layout_privacy_space_guard)
        dividerPrivacySpaceGuard = view.findViewById(R.id.divider_privacy_space_guard)
        switchPrivacySpaceGuard = view.findViewById(R.id.switch_privacy_space_guard)
        tvPrivacySpaceGuardStatus = view.findViewById(R.id.tv_privacy_space_guard_status)
        switchUninstallAndPermProtection = view.findViewById(R.id.switch_uninstall_and_perm_protection)
        tvUninstallAndPermProtectionStatus = view.findViewById(R.id.tv_uninstall_and_perm_protection_status)

        tvAccessibilityStatus = view.findViewById(R.id.tv_accessibility_status_main)
        switchAccessibilityService = view.findViewById(R.id.switch_accessibility_service)
        switchFloatingGuard = view.findViewById(R.id.switch_floating_guard)
        tvFloatingGuardStatus = view.findViewById(R.id.tv_floating_guard_status)
        layoutDeviceAdminProtection = view.findViewById(R.id.layout_device_admin_protection)
        dividerDeviceAdmin = view.findViewById(R.id.divider_device_admin)
        tvDeviceAdminProtectionStatus = view.findViewById(R.id.tv_device_admin_protection_status)
        switchDeviceAdminProtection = view.findViewById(R.id.switch_device_admin_protection)
        btnQuickSendLogs = view.findViewById(R.id.btn_quick_send_logs)
        btnExportSettings = view.findViewById(R.id.btn_export_settings)
        btnImportSettings = view.findViewById(R.id.btn_import_settings)

        // Setup Refresh Time Card
        setupRefreshTimeCard(view)

        // Setup Edit Window Policy
        setupEditWindowPolicySection(view)

        // Setup protection switches
        switchAlarmPowerOffProtection?.let { setupAlarmPowerOffProtectionSwitch(it) }
        switchFloatingGuard?.let { setupFloatingGuardSwitch(it) }
        switchFloatingWindowProtection?.let { setupFloatingWindowProtectionSwitch(it) }
        switchPrivacySpaceGuard?.let { setupPrivacySpaceGuardSwitch(it) }
        switchUninstallAndPermProtection?.let { setupUninstallAndPermProtectionSwitch(it) }
        switchDeviceAdminProtection?.let { setupDeviceAdminProtectionSwitch(it) }

        setupProtectionSectionHelp(view.findViewById(R.id.btn_protection_section_help))

        switchAccessibilityService?.let { setupAccessibilityServiceSwitch(it) }
    }

    private fun setupProtectionSectionHelp(view: View?) {
        view?.setOnClickListener {
            val ctx = context ?: return@setOnClickListener
            val isVivo = SettingsNavigator.isVivoOrIqoo()
            val isOppo = SettingsNavigator.isOppoOrOnePlusOrRealme()

            val message = buildString {
                append(getString(R.string.settings_protection_help_intro)).append("\n\n")
                append(getString(R.string.settings_protection_help_alarm)).append("\n\n")
                append(getString(R.string.settings_protection_help_floating_guard)).append("\n\n")
                append(getString(R.string.settings_protection_help_floating_window)).append("\n\n")
                append(getString(R.string.settings_protection_help_uninstall_perm))

                if (isVivo) {
                    append("\n\n").append(getString(R.string.settings_protection_help_vivo_note))
                } else if (isOppo) {
                    append("\n\n").append(getString(R.string.settings_protection_help_oppo_note))
                }
            }

            AlertDialog.Builder(ctx)
                .setTitle(R.string.settings_protection_help_title)
                .setMessage(message)
                .setPositiveButton(R.string.btn_got_it, null)
                .show()
        }
    }

    private fun setupEditWindowPolicySection(view: View) {
        view.findViewById<View>(R.id.card_edit_window_policy)?.setOnClickListener {
            val currentContext = context ?: return@setOnClickListener
            if (TimeRuleEditWindowPolicyManager.isEditLocked(currentContext)) {
                Toast.makeText(currentContext, TimeRuleEditWindowPolicyManager.getLockoutHintText(currentContext), Toast.LENGTH_SHORT).show()
            }
        }

        tvSettingsEditWindowPending?.setOnClickListener {
            val currentContext = context ?: return@setOnClickListener
            if (TimeRuleEditWindowPolicyManager.isEditLocked(currentContext)) {
                Toast.makeText(currentContext, TimeRuleEditWindowPolicyManager.getLockoutHintText(currentContext), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (TimeRuleEditWindowPolicyManager.getPendingPolicy(currentContext) != null) {
                AlertDialog.Builder(currentContext)
                    .setTitle("撤销待生效修改")
                    .setMessage("确定要撤销可修改时段的待生效修改，维持当前设置吗？")
                    .setPositiveButton("撤销") { _, _ ->
                        TimeRuleEditWindowPolicyManager.clearPendingPolicy(currentContext)
                        Toast.makeText(currentContext, "已撤销待生效修改", Toast.LENGTH_SHORT).show()
                        updateEditWindowPolicyUI()
                    }
                    .setNegativeButton("取消", null)
                    .show()
            }
        }

        rgSettingsEditWindowPolicy?.setOnCheckedChangeListener { _, checkedId ->
            if (isEditWindowPolicyUiUpdating) return@setOnCheckedChangeListener
            val currentContext = context ?: return@setOnCheckedChangeListener
            if (TimeRuleEditWindowPolicyManager.isEditLocked(currentContext)) {
                updateEditWindowPolicyUI()
                Toast.makeText(currentContext, TimeRuleEditWindowPolicyManager.getLockoutHintText(currentContext), Toast.LENGTH_SHORT).show()
                return@setOnCheckedChangeListener
            }

            val target = if (checkedId == R.id.rb_settings_edit_window_effective_to_midnight) {
                TimeRuleEditWindowPolicyManager.Policy.EFFECTIVE_TIME_TO_MIDNIGHT
            } else {
                TimeRuleEditWindowPolicyManager.Policy.ALL_DAY
            }

            when (val result = TimeRuleEditWindowPolicyManager.applyPolicyChange(currentContext, target)) {
                is TimeRuleEditWindowPolicyManager.PolicyChangeResult.Immediate -> {
                    Toast.makeText(currentContext, result.message, Toast.LENGTH_SHORT).show()
                }
                is TimeRuleEditWindowPolicyManager.PolicyChangeResult.Pending -> {
                    Toast.makeText(currentContext, result.message, Toast.LENGTH_LONG).show()
                }
                TimeRuleEditWindowPolicyManager.PolicyChangeResult.NoChange -> {}
            }

            updateEditWindowPolicyUI()
            updateFloatingWindowProtectionUI()
            updatePrivacySpaceGuardUI()
            updateUninstallAndPermProtectionUI()
        }

        updateEditWindowPolicyUI()
    }

    private fun updateEditWindowPolicyUI() {
        if (!isAdded) return
        val ctx = requireContext()
        val locked = TimeRuleEditWindowPolicyManager.isEditLocked(ctx)
        val activePolicy = TimeRuleEditWindowPolicyManager.getPolicy(ctx)
        val pendingPolicy = TimeRuleEditWindowPolicyManager.getPendingPolicy(ctx)

        val displayPolicy = pendingPolicy ?: activePolicy
        isEditWindowPolicyUiUpdating = true
        try {
            if (displayPolicy == TimeRuleEditWindowPolicyManager.Policy.EFFECTIVE_TIME_TO_MIDNIGHT) {
                rbSettingsEditWindowEffectiveToMidnight?.isChecked = true
            } else {
                rbSettingsEditWindowAllDay?.isChecked = true
            }

            rbSettingsEditWindowAllDay?.isEnabled = !locked
            rbSettingsEditWindowAllDay?.isClickable = !locked
            rbSettingsEditWindowEffectiveToMidnight?.isEnabled = !locked
            rbSettingsEditWindowEffectiveToMidnight?.isClickable = !locked
            rgSettingsEditWindowPolicy?.isEnabled = !locked
            rgSettingsEditWindowPolicy?.alpha = if (locked) 0.5f else 1.0f

            if (locked) {
                tvSettingsLockoutHint?.visibility = View.VISIBLE
                tvSettingsLockoutHint?.text = TimeRuleEditWindowPolicyManager.getLockoutHintText(ctx)
            } else {
                tvSettingsLockoutHint?.visibility = View.GONE
            }

            if (pendingPolicy != null) {
                val refreshTime = TimeRuleManager.getRefreshTime(ctx)
                val pendingTitle = TimeRuleEditWindowPolicyManager.getPolicyTitle(pendingPolicy)
                tvSettingsEditWindowPending?.visibility = View.VISIBLE
                tvSettingsEditWindowPending?.text = "待生效：将在明日 $refreshTime 切换为「$pendingTitle」（点击撤销）"
            } else {
                tvSettingsEditWindowPending?.visibility = View.GONE
            }
        } finally {
            isEditWindowPolicyUiUpdating = false
        }
    }

    private fun setupRefreshTimeCard(view: View) {
        switchRefreshSyncRules?.setOnCheckedChangeListener { _, isChecked ->
            if (switchRefreshSyncRulesUiUpdating) return@setOnCheckedChangeListener
            val context = context ?: return@setOnCheckedChangeListener
            if (TimeRuleEditWindowPolicyManager.isEditLocked(context)) {
                updateRefreshTimeUI()
                Toast.makeText(context, TimeRuleEditWindowPolicyManager.getLockoutHintText(context), Toast.LENGTH_SHORT).show()
                return@setOnCheckedChangeListener
            }
            TimeRuleManager.setRefreshTimeSynced(context, isChecked)
            if (isChecked) {
                val segments = TimeRuleManager.getPendingTimeSegments(context) ?: TimeRuleManager.getTimeSegments(context)
                val syncedTime = TimeRuleManager.findRefreshTimeFromSegments(segments)
                TimeRuleManager.saveRefreshTime(context, syncedTime)
            }
            updateRefreshTimeUI()
        }

        tvRefreshTimeValueRules?.setOnClickListener {
            val context = context ?: return@setOnClickListener
            if (TimeRuleEditWindowPolicyManager.isEditLocked(context)) {
                Toast.makeText(context, TimeRuleEditWindowPolicyManager.getLockoutHintText(context), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val isSynced = TimeRuleManager.isRefreshTimeSynced(context)
            if (isSynced) {
                Toast.makeText(context, "已开启自动同步勿扰结束时间，如需手动修改请先关闭自动同步", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            showRefreshTimePickerDialog(context)
        }
    }

    private fun showRefreshTimePickerDialog(context: Context) {
        val currentRefresh = TimeRuleManager.getRefreshTime(context)
        val parts = currentRefresh.split(":")
        val curHour = parts.getOrNull(0)?.toIntOrNull() ?: 8
        val curMinute = parts.getOrNull(1)?.toIntOrNull() ?: 0

        val timePicker = android.widget.TimePicker(context).apply {
            setIs24HourView(true)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                hour = curHour
                minute = curMinute
            } else {
                @Suppress("DEPRECATION")
                currentHour = curHour
                @Suppress("DEPRECATION")
                currentMinute = curMinute
            }
        }

        AlertDialog.Builder(context)
            .setTitle("设置每日生效时间 (04:00 - 12:00)")
            .setView(timePicker)
            .setPositiveButton("确定") { _, _ ->
                val selectedHour = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) timePicker.hour else @Suppress("DEPRECATION") timePicker.currentHour
                val selectedMinute = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) timePicker.minute else @Suppress("DEPRECATION") timePicker.currentMinute
                val formatted = String.format(java.util.Locale.US, "%02d:%02d", selectedHour, selectedMinute)

                if (formatted < "04:00" || formatted > "12:00") {
                    Toast.makeText(context, "生效时间必须在 04:00 - 12:00 之间", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                val effectiveSegments = TimeRuleManager.getPendingTimeSegments(context) ?: TimeRuleManager.getTimeSegments(context)
                val candidateMinutes = selectedHour * 60 + selectedMinute
                val preflight = com.decikeeper.app.manager.MinuteBoundary.isRefreshLegal(candidateMinutes, effectiveSegments)
                if (preflight is com.decikeeper.app.manager.BoundaryResult.Rejected) {
                    AlertDialog.Builder(context)
                        .setTitle("生效时间配置不当")
                        .setMessage("生效时间必须配置在空档期或勿扰时段边界")
                        .setPositiveButton("我知道了", null)
                        .show()
                    return@setPositiveButton
                }

                TimeRuleManager.saveRefreshTime(context, formatted)
                updateRefreshTimeUI()
                Toast.makeText(context, "每日生效时间已更新为 $formatted", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun updateRefreshTimeUI() {
        if (!isAdded) return
        val context = requireContext()
        val locked = TimeRuleEditWindowPolicyManager.isEditLocked(context)
        val isSynced = TimeRuleManager.isRefreshTimeSynced(context)

        switchRefreshSyncRulesUiUpdating = true
        try {
            switchRefreshSyncRules?.isChecked = isSynced
            switchRefreshSyncRules?.isEnabled = !locked
            switchRefreshSyncRules?.alpha = if (locked) 0.5f else 1.0f
        } finally {
            switchRefreshSyncRulesUiUpdating = false
        }

        if (isSynced) {
            val segments = TimeRuleManager.getPendingTimeSegments(context) ?: TimeRuleManager.getTimeSegments(context)
            val syncedTime = TimeRuleManager.findRefreshTimeFromSegments(segments)
            TimeRuleManager.saveRefreshTime(context, syncedTime)
            tvRefreshTimeValueRules?.text = syncedTime
            tvRefreshSyncHintRules?.text = "当前自动同步为：$syncedTime（跟随 04:00~12:00 内最早结束的勿扰时段）"
            tvRefreshSyncHintRules?.visibility = View.VISIBLE
            tvRefreshTimeValueRules?.alpha = 0.6f
            tvRefreshTimeValueRules?.isEnabled = false
        } else {
            val refreshTime = TimeRuleManager.getRefreshTime(context)
            tvRefreshTimeValueRules?.text = refreshTime
            tvRefreshSyncHintRules?.text = "跟随 04:00~12:00 内最早结束的勿扰时段"
            tvRefreshSyncHintRules?.visibility = View.GONE
            tvRefreshTimeValueRules?.alpha = if (locked) 0.5f else 1.0f
            tvRefreshTimeValueRules?.isEnabled = !locked
        }
    }

    private fun setupClickEvents(view: View) {
        view.findViewById<Button>(R.id.btn_open_help)?.setOnClickListener {
            RecentTasksHideManager.markNavigating()
            startActivity(Intent(requireContext(), HelpActivity::class.java))
        }
        // REQ-5: 设置 → 关于我们
        view.findViewById<Button>(R.id.btn_open_about)?.setOnClickListener {
            RecentTasksHideManager.markNavigating()
            startActivity(Intent(requireContext(), AboutActivity::class.java))
        }
        btnQuickSendLogs?.setOnClickListener {
            RecentTasksHideManager.markNavigating()
            LogExporter.showQuickSendLogOptions(requireContext(), "导出日志", "请选择要导出的时间范围")
        }
        btnExportSettings?.setOnClickListener {
            showExportCategoryDialog()
        }
        btnImportSettings?.setOnClickListener {
            RecentTasksHideManager.markNavigating()
            importSettingsLauncher.launch(arrayOf("application/json", "text/*", "*/*"))
        }
    }

    private fun showExportCategoryDialog() {
        val ctx = requireContext()
        val all = SettingsBackupManager.BackupCategory.ALL
        val checked = BooleanArray(all.size) { true }
        val names = all.map { it.displayName }.toTypedArray()

        AlertDialog.Builder(ctx)
            .setTitle("选择要导出的分类")
            .setMultiChoiceItems(names, checked) { _, which, isChecked ->
                checked[which] = isChecked
            }
            .setPositiveButton("导出") { _, _ ->
                val selected = all.filterIndexed { i, _ -> checked[i] }
                if (selected.isEmpty()) {
                    Toast.makeText(ctx, "请至少选择一个分类", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                pendingExportCategories = selected
                RecentTasksHideManager.markNavigating()
                exportSettingsLauncher.launch(SettingsBackupManager.createSuggestedFileName())
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun exportSettingsToUri(uri: Uri, categories: List<SettingsBackupManager.BackupCategory>) {
        val appContext = requireContext().applicationContext
        setBackupButtonsEnabled(false)
        Thread {
            val message = try {
                val result = SettingsBackupManager.exportToUri(appContext, uri, categories)
                val catDesc = categories.joinToString("、") { it.displayName }
                "✅ 已导出 $catDesc（${result.fileCount} 组，${result.entryCount} 项）"
            } catch (e: Exception) {
                "导出失败：${e.message ?: "未知错误"}"
            }
            activity?.runOnUiThread {
                val ctx = context ?: return@runOnUiThread
                setBackupButtonsEnabled(true)
                Toast.makeText(ctx, message, Toast.LENGTH_LONG).show()
            }
        }.start()
    }

    private fun showImportCategoryDialog(uri: Uri) {
        val ctx = requireContext()
        setBackupButtonsEnabled(false)
        Thread {
            val json = try {
                ctx.contentResolver.openInputStream(uri)?.use { stream ->
                    stream.bufferedReader(Charsets.UTF_8).readText()
                }
            } catch (e: Exception) { null }

            if (json == null) {
                activity?.runOnUiThread {
                    setBackupButtonsEnabled(true)
                    Toast.makeText(ctx, "无法读取备份文件", Toast.LENGTH_SHORT).show()
                }
                return@Thread
            }

            val availableCategories = SettingsBackupManager.getCategoriesInBackup(json)
            val all = SettingsBackupManager.BackupCategory.ALL
            val names = all.map { it.displayName }.toTypedArray()
            val checked = BooleanArray(all.size) { i ->
                i < availableCategories.size && all[i] in availableCategories
            }

            activity?.runOnUiThread {
                setBackupButtonsEnabled(true)
                val isAdaptation = TimeRuleManager.isInAdaptationPeriod(ctx)
                val title = if (isAdaptation) "选择要导入的分类（适应期·立即生效）"
                else "选择要导入的分类（将延期生效）"

                AlertDialog.Builder(ctx)
                    .setTitle(title)
                    .setMultiChoiceItems(names, checked) { _, which, isChecked ->
                        checked[which] = isChecked
                    }
                    .setPositiveButton(if (isAdaptation) "导入并覆盖" else "导入（延期生效）") { _, _ ->
                        val selected = all.filterIndexed { i, _ -> checked[i] }
                        if (selected.isEmpty()) {
                            Toast.makeText(ctx, "请至少选择一个分类", Toast.LENGTH_SHORT).show()
                            return@setPositiveButton
                        }
                        importSettingsFromUri(uri, json, selected, isAdaptation)
                    }
                    .setNegativeButton("取消", null)
                    .show()
            }
        }.start()
    }

    private fun importSettingsFromUri(
        uri: Uri,
        json: String,
        categories: List<SettingsBackupManager.BackupCategory>,
        isAdaptation: Boolean
    ) {
        val appContext = requireContext().applicationContext
        setBackupButtonsEnabled(false)
        Thread {
            val message = try {
                val result = SettingsBackupManager.applyBackupJson(appContext, json, categories)
                // 触发 UI 刷新，让 pending 标记在界面及时显示
                val refreshIntent = android.content.Intent(appContext, MonitorService::class.java)
                refreshIntent.action = MonitorService.ACTION_REFRESH_RULES
                appContext.startService(refreshIntent)
                val catDesc = categories.joinToString("、") { it.displayName }
                "✅ 已导入 $catDesc（${result.fileCount} 组，${result.entryCount} 项）"
            } catch (e: Exception) {
                "导入失败：${e.message ?: "未知错误"}"
            }
            activity?.runOnUiThread {
                val ctx = context ?: return@runOnUiThread
                setBackupButtonsEnabled(true)
                updateUninstallAndPermProtectionUI()
                updateAlarmPowerOffProtectionUI()
                        Toast.makeText(ctx, message, Toast.LENGTH_LONG).show()
            }
        }.start()
    }

    private fun setBackupButtonsEnabled(enabled: Boolean) {
        btnExportSettings?.isEnabled = enabled
        btnImportSettings?.isEnabled = enabled
        btnExportSettings?.alpha = if (enabled) 1.0f else 0.55f
        btnImportSettings?.alpha = if (enabled) 1.0f else 0.55f
    }

    private fun setupFloatingWindowProtectionSwitch(switchView: Switch) {
        val ctx = requireContext()
        updateFloatingWindowProtectionUI()
        (switchView.parent as? View)?.setOnClickListener {
            if (TimeRuleEditWindowPolicyManager.isEditLocked(ctx)) {
                Toast.makeText(ctx, TimeRuleEditWindowPolicyManager.getLockoutHintText(ctx), Toast.LENGTH_SHORT).show()
            }
        }
        switchView.setOnCheckedChangeListener { _, checked ->
            if (switchFloatingWindowUiUpdating) return@setOnCheckedChangeListener
            if (TimeRuleEditWindowPolicyManager.isEditLocked(ctx)) {
                switchFloatingWindowUiUpdating = true
                switchView.isChecked = !checked
                switchFloatingWindowUiUpdating = false
                Toast.makeText(ctx, TimeRuleEditWindowPolicyManager.getLockoutHintText(ctx), Toast.LENGTH_SHORT).show()
                return@setOnCheckedChangeListener
            }
            val now = System.currentTimeMillis()
            if (now - lastFloatingWindowSwitchClickTime < 1000L) {
                switchFloatingWindowUiUpdating = true
                switchView.isChecked = !checked
                switchFloatingWindowUiUpdating = false
                return@setOnCheckedChangeListener
            }
            lastFloatingWindowSwitchClickTime = now

            if (checked) {
                if (!isA11yServiceReady(ctx)) {
                    switchFloatingWindowUiUpdating = true
                    switchView.isChecked = false
                    switchFloatingWindowUiUpdating = false
                    Toast.makeText(ctx, getString(R.string.a11y_service_not_ready_toast), Toast.LENGTH_SHORT).show()
                    return@setOnCheckedChangeListener
                }
                FloatingWindowProtectionManager.setProtectionEnabled(ctx, true)
                Toast.makeText(ctx, "小窗可见性增强已开启", Toast.LENGTH_SHORT).show()
            } else {
                val isImmediate = TimeRuleManager.isInAdaptationPeriod(ctx) || !TimeRuleManager.getBaselineFloatingWindowProtectionState(ctx)
                FloatingWindowProtectionManager.setProtectionEnabled(ctx, false)
                val message = if (isImmediate) {
                    if (TimeRuleManager.isInAdaptationPeriod(ctx)) "适应期内，小窗可见性增强已立即关闭"
                    else "小窗可见性增强已立即关闭（恢复基准）"
                } else {
                    "已申请关闭小窗可见性增强（明日生效）"
                }
                Toast.makeText(ctx, message, Toast.LENGTH_LONG).show()
            }
            updateFloatingWindowProtectionUI()
        }
    }

    private fun setupUninstallAndPermProtectionSwitch(switchView: Switch) {
        val ctx = requireContext()
        updateUninstallAndPermProtectionUI()
        (switchView.parent as? View)?.setOnClickListener {
            if (TimeRuleEditWindowPolicyManager.isEditLocked(ctx)) {
                Toast.makeText(ctx, TimeRuleEditWindowPolicyManager.getLockoutHintText(ctx), Toast.LENGTH_SHORT).show()
            }
        }
        switchView.setOnCheckedChangeListener { _, checked ->
            if (switchUninstallAndPermUiUpdating) return@setOnCheckedChangeListener
            if (TimeRuleEditWindowPolicyManager.isEditLocked(ctx)) {
                switchUninstallAndPermUiUpdating = true
                switchView.isChecked = !checked
                switchUninstallAndPermUiUpdating = false
                Toast.makeText(ctx, TimeRuleEditWindowPolicyManager.getLockoutHintText(ctx), Toast.LENGTH_SHORT).show()
                return@setOnCheckedChangeListener
            }
            val now = System.currentTimeMillis()
            if (now - lastUninstallAndPermSwitchClickTime < 1000L) {
                switchUninstallAndPermUiUpdating = true
                switchView.isChecked = !checked
                switchUninstallAndPermUiUpdating = false
                return@setOnCheckedChangeListener
            }
            lastUninstallAndPermSwitchClickTime = now

            if (checked) {
                val permState = getHomePermissionState(ctx)
                val usageStatsOk = com.decikeeper.app.manager.PermissionManager.hasUsageStatsPermission(ctx)
                val a11yOk = isA11yServiceReady(ctx)

                if (!permState.allGranted || !usageStatsOk || !a11yOk) {
                    switchUninstallAndPermUiUpdating = true
                    switchView.isChecked = false
                    switchUninstallAndPermUiUpdating = false
                    showPermProtectionGuideDialog()
                    return@setOnCheckedChangeListener
                }
                if (!AlarmPowerOffProtectionManager.isLockScreenDisplayReady(ctx)) {
                    switchUninstallAndPermUiUpdating = true
                    switchView.isChecked = false
                    switchUninstallAndPermUiUpdating = false
                    showLockScreenMissingWarningDialog()
                    return@setOnCheckedChangeListener
                }
                enableUninstallAndPermProtection(ctx)
            } else {
                disableUninstallAndPermProtection(ctx)
            }
        }
    }

    private fun enableUninstallAndPermProtection(ctx: Context) {
        AntiUninstallManager.setProtectionEnabled(ctx, true)
        Toast.makeText(ctx, "防卸载与防关权限保护已开启", Toast.LENGTH_SHORT).show()
        updateUninstallAndPermProtectionUI()
        maybeAskBgBatteryPermissionAfterAntiUninstallEnabled()
    }

    private fun disableUninstallAndPermProtection(ctx: Context) {
        val isImmediate = TimeRuleManager.isInAdaptationPeriod(ctx) || (
            !TimeRuleManager.getBaselineAntiUninstallState(ctx)
        )
        AntiUninstallManager.setProtectionEnabled(ctx, false)
        updateUninstallAndPermProtectionUI()
        val message = if (isImmediate) {
            if (TimeRuleManager.isInAdaptationPeriod(ctx)) "适应期内，防卸载与防关权限保护已立即关闭"
            else "防卸载与防关权限保护已立即关闭（恢复基准）"
        } else {
            "已申请关闭防卸载与防关权限保护（明日生效）"
        }
        Toast.makeText(ctx, message, Toast.LENGTH_LONG).show()
    }

    /**
     * 无障碍服务 Switch — 开启时弹窗引导补齐基础权限+关联启动，然后开启无障碍服务。
     */
    private fun setupAccessibilityServiceSwitch(switchView: Switch) {
        val context = requireContext()
        val locked = TimeRuleEditWindowPolicyManager.isEditLocked(context)
        val systemEnabled = isAccessibilityServiceEnabled()
        val a11yState = AccessibilityControlManager.getState(context)
        switchA11yServiceUiUpdating = true
        // 只有业务处于 ACTIVE 且系统服务开启时才勾选
        switchView.isChecked = (a11yState == AccessibilityControlManager.State.ACTIVE && systemEnabled)
        switchView.isEnabled = !locked
        switchView.isClickable = !locked
        switchView.alpha = if (locked) 0.5f else 1.0f
        switchA11yServiceUiUpdating = false

        (switchView.parent as? View)?.setOnClickListener {
            if (TimeRuleEditWindowPolicyManager.isEditLocked(context)) {
                Toast.makeText(context, TimeRuleEditWindowPolicyManager.getLockoutHintText(context), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val state = AccessibilityControlManager.getState(context)
            val ok = isAccessibilityServiceEnabled(context)
            if (state == AccessibilityControlManager.State.PENDING) {
                Toast.makeText(context, "无障碍服务关闭申请生效中（明日生效），期间防关保护持续生效", Toast.LENGTH_SHORT).show()
            } else if (state == AccessibilityControlManager.State.DISABLED && ok) {
                showPostImmediateDisableGuideDialog(context)
            }
        }

        switchView.setOnCheckedChangeListener { _, isChecked ->
            if (switchA11yServiceUiUpdating) return@setOnCheckedChangeListener
            if (TimeRuleEditWindowPolicyManager.isEditLocked(context)) {
                switchA11yServiceUiUpdating = true
                switchView.isChecked = !isChecked
                switchA11yServiceUiUpdating = false
                Toast.makeText(context, TimeRuleEditWindowPolicyManager.getLockoutHintText(context), Toast.LENGTH_SHORT).show()
                return@setOnCheckedChangeListener
            }

            val now = System.currentTimeMillis()
            if (now - lastA11yServiceSwitchClickTime < 1000) {
                switchA11yServiceUiUpdating = true
                switchView.isChecked = !isChecked
                switchA11yServiceUiUpdating = false
                return@setOnCheckedChangeListener
            }
            lastA11yServiceSwitchClickTime = now

            if (isChecked) {
                if (isAccessibilityServiceEnabled()) {
                    // 已开启，确保业务状态与防关保护处于 ACTIVE（清除待关闭申请）
                    AccessibilityControlManager.setEnabled(context, true)
                    updateAccessibilityServiceUI()
                    Toast.makeText(context, "无障碍服务及防关保护已开启", Toast.LENGTH_SHORT).show()
                    return@setOnCheckedChangeListener
                }
                // 未开启 → 弹窗引导
                switchA11yServiceUiUpdating = true
                switchView.isChecked = false
                switchA11yServiceUiUpdating = false
                showAccessibilityServiceGuideDialog()
            } else {
                if (!isAccessibilityServiceEnabled()) {
                    // 系统已关闭 → 清除待关闭标记（如果有）并保持
                    setA11yPendingDisable(context, false)
                    switchA11yServiceUiUpdating = true
                    switchView.isChecked = false
                    switchA11yServiceUiUpdating = false
                    updateAccessibilityServiceUI()
                    return@setOnCheckedChangeListener
                }
                // 系统仍开启 → 弹窗确认关闭
                switchA11yServiceUiUpdating = true
                switchView.isChecked = true
                switchA11yServiceUiUpdating = false
                // 检查防关权限保护是否开启
                if (getCombinedProtectionState(context) != CombinedProtectionState.DISABLED) {
                    showA11yDisableWithProtectionDialog(switchView)
                } else {
                    showA11yDisableConfirmDialog(switchView)
                }
            }
        }
    }

    private fun getProtectionEffectivenessText(ctx: Context, baselineActive: Boolean): String {
        return if (TimeRuleManager.isInAdaptationPeriod(ctx) || !baselineActive) {
            "立即解除（恢复基准）"
        } else {
            "次日生效"
        }
    }

    private fun submitProtectionDisablesForAccessibility(ctx: Context) {
        if (AntiUninstallManager.getProtectionState(ctx) == AntiUninstallManager.State.ACTIVE) {
            AntiUninstallManager.setProtectionEnabled(ctx, false)
        }
        if (AccessibilityControlManager.getState(ctx) == AccessibilityControlManager.State.ACTIVE) {
            AccessibilityControlManager.setEnabled(ctx, false)
        }
        if (FloatingWindowProtectionManager.getProtectionState(ctx) == FloatingWindowProtectionManager.State.ACTIVE) {
            FloatingWindowProtectionManager.setProtectionEnabled(ctx, false)
        }
        if (GuardFloatingWindowManager.getProtectionState(ctx) == GuardFloatingWindowManager.State.ACTIVE) {
            GuardFloatingWindowManager.setFloatingGuardEnabled(ctx, false)
        }
        if (PrivacyProtectionManager.getProtectionState(ctx) == PrivacyProtectionManager.State.ACTIVE) {
            PrivacyProtectionManager.setProtectionEnabled(ctx, false)
        }
        if (AlarmPowerOffProtectionManager.isEnabled(ctx)) {
            AlarmPowerOffProtectionManager.setEnabled(ctx, false)
        }
    }

    private fun showA11yDisableConfirmDialog(switchView: Switch) {
        val ctx = requireContext()
        val density = ctx.resources.displayMetrics.density

        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((24 * density).toInt(), (20 * density).toInt(), (24 * density).toInt(), (20 * density).toInt())
        }

        val tvTitle = TextView(ctx).apply {
            text = "关闭无障碍服务"
            textSize = 18f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(ThemeManager.getColorAttr(ctx, R.attr.zzText))
        }
        container.addView(tvTitle)

        val antiText = getProtectionEffectivenessText(ctx, TimeRuleManager.getBaselineAntiUninstallState(ctx))
        val guardText = getProtectionEffectivenessText(ctx, TimeRuleManager.getBaselineFloatingGuardState(ctx))
        val floatingText = getProtectionEffectivenessText(ctx, TimeRuleManager.getBaselineFloatingWindowProtectionState(ctx))
        val privacyText = getProtectionEffectivenessText(ctx, TimeRuleManager.getBaselinePrivacyProtectionState(ctx))
        val a11yText = getProtectionEffectivenessText(ctx, TimeRuleManager.getBaselineA11yServiceState(ctx))

        val allImmediate = TimeRuleManager.isInAdaptationPeriod(ctx) || (
            !TimeRuleManager.getBaselineAntiUninstallState(ctx) &&
            !TimeRuleManager.getBaselineFloatingGuardState(ctx) &&
            !TimeRuleManager.getBaselineFloatingWindowProtectionState(ctx) &&
            !TimeRuleManager.getBaselinePrivacyProtectionState(ctx) &&
            !TimeRuleManager.getBaselineA11yServiceState(ctx)
        )

        val tvMessage = TextView(ctx).apply {
            textSize = 14f
            setPadding(0, (14 * density).toInt(), 0, 0)
            setTextColor(ThemeManager.getColorAttr(ctx, R.attr.zzMuted))
            setLineSpacing(0f, 1.25f)
            text = buildString {
                append("关闭无障碍服务后，以下保护功能将一并停用：\n\n")
                append("  • 防卸载与防关权限保护 → $antiText\n")
                append("  • 桌面守护小浮窗 → $guardText\n")
                append("  • 小窗可见性增强 → $floatingText\n")
                append("  • 隐私空间保护 → $privacyText\n")
                append("  • 闹钟防关机保护 → 立即关闭\n")
                append("  • 无障碍服务 → $a11yText\n\n")
                if (allImmediate) {
                    append("提示：检测到当前所有功能今日基准均为关闭，保护将【立即全部解除】。\n确认后可直接前往系统设置关闭无障碍服务。\n\n")
                    append("是否确认关闭？")
                } else {
                    append("关闭流程：\n")
                    append("1. 点击「确认关闭」提交以上所有关闭申请\n")
                    append("2. 立即解除项立即停用，其余项次日生效\n")
                    append("3. 次日全部生效后，可前往系统无障碍设置中关闭决定守护无障碍服务\n\n")
                    append("是否确认同时提交所有关闭申请？")
                }
            }
        }
        container.addView(tvMessage)

        val buttonRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, (20 * density).toInt(), 0, 0)
            gravity = Gravity.CENTER_VERTICAL
        }

        val btnCancel = com.google.android.material.button.MaterialButton(ctx).apply {
            text = "取消"
            setAllCaps(false)
            setTextColor(ThemeManager.getColorAttr(ctx, R.attr.zzText))
            backgroundTintList = android.content.res.ColorStateList.valueOf(ThemeManager.getColorAttr(ctx, R.attr.zzSurface2))
            cornerRadius = (12 * density).toInt()
            minHeight = (44 * density).toInt()
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(0, 0, (8 * density).toInt(), 0)
            }
        }

        val btnConfirm = com.google.android.material.button.MaterialButton(ctx).apply {
            text = if (allImmediate) "确认并前往关闭" else "确认关闭"
            setAllCaps(false)
            setTextColor(android.graphics.Color.WHITE)
            backgroundTintList = android.content.res.ColorStateList.valueOf(ThemeManager.getColorAttr(ctx, R.attr.zzAccent))
            cornerRadius = (12 * density).toInt()
            minHeight = (44 * density).toInt()
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        buttonRow.addView(btnCancel)
        buttonRow.addView(btnConfirm)
        container.addView(buttonRow)

        val dialog = AlertDialog.Builder(ctx)
            .setView(container)
            .create()

        btnCancel.setOnClickListener { dialog.dismiss() }

        btnConfirm.setOnClickListener {
            submitProtectionDisablesForAccessibility(ctx)
            val a11yImmediate = applyA11yDisableRequest(ctx)
            switchA11yServiceUiUpdating = true
            switchView.isChecked = false
            switchA11yServiceUiUpdating = false
            updateAccessibilityServiceUI()
            updateUninstallAndPermProtectionUI()
            updateAlarmPowerOffProtectionUI()
            updateFloatingWindowProtectionUI()
            updatePrivacySpaceGuardUI()
            updateFloatingGuardUI()
            dialog.dismiss()
            if (a11yImmediate) {
                showPostImmediateDisableGuideDialog(ctx)
            } else {
                Toast.makeText(ctx, "已提交关闭申请（明日生效），期间无障碍防关保护持续生效", Toast.LENGTH_LONG).show()
            }
        }

        dialog.show()
    }

    private fun showA11yDisableWithProtectionDialog(switchView: Switch) {
        val ctx = requireContext()
        val density = ctx.resources.displayMetrics.density

        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((24 * density).toInt(), (20 * density).toInt(), (24 * density).toInt(), (20 * density).toInt())
        }

        val tvTitle = TextView(ctx).apply {
            text = "关闭无障碍服务"
            textSize = 18f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(ThemeManager.getColorAttr(ctx, R.attr.zzText))
        }
        container.addView(tvTitle)

        val antiText = getProtectionEffectivenessText(ctx, TimeRuleManager.getBaselineAntiUninstallState(ctx))
        val guardText = getProtectionEffectivenessText(ctx, TimeRuleManager.getBaselineFloatingGuardState(ctx))
        val floatingText = getProtectionEffectivenessText(ctx, TimeRuleManager.getBaselineFloatingWindowProtectionState(ctx))
        val privacyText = getProtectionEffectivenessText(ctx, TimeRuleManager.getBaselinePrivacyProtectionState(ctx))
        val a11yText = getProtectionEffectivenessText(ctx, TimeRuleManager.getBaselineA11yServiceState(ctx))

        val allImmediate = TimeRuleManager.isInAdaptationPeriod(ctx) || (
            !TimeRuleManager.getBaselineAntiUninstallState(ctx) &&
            !TimeRuleManager.getBaselineFloatingGuardState(ctx) &&
            !TimeRuleManager.getBaselineFloatingWindowProtectionState(ctx) &&
            !TimeRuleManager.getBaselinePrivacyProtectionState(ctx) &&
            !TimeRuleManager.getBaselineA11yServiceState(ctx)
        )

        val tvMessage = TextView(ctx).apply {
            textSize = 14f
            setPadding(0, (14 * density).toInt(), 0, 0)
            setTextColor(ThemeManager.getColorAttr(ctx, R.attr.zzMuted))
            setLineSpacing(0f, 1.25f)
            text = buildString {
                append("「防卸载与防关权限保护」正在保护中，它会阻止对无障碍服务的关闭操作。\n\n")
                append("将同时提交以下功能的关闭申请：\n")
                append("  • 防卸载与防关权限保护 → $antiText\n")
                append("  • 桌面守护小浮窗 → $guardText\n")
                append("  • 小窗可见性增强 → $floatingText\n")
                append("  • 隐私空间保护 → $privacyText\n")
                append("  • 闹钟防关机保护 → 立即关闭\n")
                append("  • 无障碍服务 → $a11yText\n\n")
                if (allImmediate) {
                    append("提示：检测到当前所有功能今日基准均为关闭，保护将【立即全部解除】。\n确认后可直接前往系统设置关闭无障碍服务。\n\n")
                    append("是否确认关闭？")
                } else {
                    append("是否确认同时提交？")
                }
            }
        }
        container.addView(tvMessage)

        val buttonRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, (20 * density).toInt(), 0, 0)
            gravity = Gravity.CENTER_VERTICAL
        }

        val btnCancel = com.google.android.material.button.MaterialButton(ctx).apply {
            text = "取消"
            setAllCaps(false)
            setTextColor(ThemeManager.getColorAttr(ctx, R.attr.zzText))
            backgroundTintList = android.content.res.ColorStateList.valueOf(ThemeManager.getColorAttr(ctx, R.attr.zzSurface2))
            cornerRadius = (12 * density).toInt()
            minHeight = (44 * density).toInt()
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(0, 0, (8 * density).toInt(), 0)
            }
        }

        val btnConfirm = com.google.android.material.button.MaterialButton(ctx).apply {
            text = if (allImmediate) "确认并前往关闭" else "同时提交关闭"
            setAllCaps(false)
            setTextColor(android.graphics.Color.WHITE)
            backgroundTintList = android.content.res.ColorStateList.valueOf(ThemeManager.getColorAttr(ctx, R.attr.zzAccent))
            cornerRadius = (12 * density).toInt()
            minHeight = (44 * density).toInt()
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        buttonRow.addView(btnCancel)
        buttonRow.addView(btnConfirm)
        container.addView(buttonRow)

        val dialog = AlertDialog.Builder(ctx)
            .setView(container)
            .create()

        btnCancel.setOnClickListener { dialog.dismiss() }

        btnConfirm.setOnClickListener {
            submitProtectionDisablesForAccessibility(ctx)
            val a11yImmediate = applyA11yDisableRequest(ctx)
            switchA11yServiceUiUpdating = true
            switchView.isChecked = false
            switchA11yServiceUiUpdating = false
            updateAccessibilityServiceUI()
            updateUninstallAndPermProtectionUI()
            updateAlarmPowerOffProtectionUI()
            updateFloatingWindowProtectionUI()
            updatePrivacySpaceGuardUI()
            updateFloatingGuardUI()
            dialog.dismiss()
            if (a11yImmediate) {
                showPostImmediateDisableGuideDialog(ctx)
            } else {
                Toast.makeText(ctx, "已同时提交关闭申请（明日生效），期间无障碍防关保护持续生效", Toast.LENGTH_LONG).show()
            }
        }

        dialog.show()
    }

    /**
     * 无障碍服务引导弹窗 — 检查基础权限+关联启动，然后引导开启无障碍
     */
    private fun showAccessibilityServiceGuideDialog() {
        val ctx = requireContext()
        val density = ctx.resources.displayMetrics.density

        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((24 * density).toInt(), (20 * density).toInt(), (24 * density).toInt(), (20 * density).toInt())
        }

        val tvTitle = TextView(ctx).apply {
            text = "开启无障碍服务"
            textSize = 18f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(ThemeManager.getColorAttr(ctx, R.attr.zzText))
        }
        container.addView(tvTitle)

        val tvMessage = TextView(ctx).apply {
            textSize = 14f
            setPadding(0, (14 * density).toInt(), 0, 0)
            setTextColor(ThemeManager.getColorAttr(ctx, R.attr.zzMuted))
            setLineSpacing(0f, 1.25f)
        }
        container.addView(tvMessage)

        val btnMargin = (8 * density).toInt()

        fun buildButton(label: String): com.google.android.material.button.MaterialButton {
            return com.google.android.material.button.MaterialButton(ctx).apply {
                text = label
                setAllCaps(false)
                setTextColor(ThemeManager.getColorAttr(ctx, R.attr.zzText))
                backgroundTintList = android.content.res.ColorStateList.valueOf(ThemeManager.getColorAttr(ctx, R.attr.zzSurface2))
                cornerRadius = (12 * density).toInt()
                minHeight = (44 * density).toInt()
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    setMargins(0, 0, 0, btnMargin)
                }
            }
        }

        val btnBase = buildButton("基础权限")
        val btnAssocStart = buildButton("关联启动")

        // 当所有前置权限齐全时，显示"开启无障碍"按钮
        val btnEnable = com.google.android.material.button.MaterialButton(ctx).apply {
            text = "开启无障碍"
            setAllCaps(false)
            setTextColor(android.graphics.Color.WHITE)
            backgroundTintList = android.content.res.ColorStateList.valueOf(ThemeManager.getColorAttr(ctx, R.attr.zzAccent))
            cornerRadius = (12 * density).toInt()
            minHeight = (44 * density).toInt()
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            visibility = View.VISIBLE
        }

        val buttonContainer = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, (20 * density).toInt(), 0, (10 * density).toInt())
        }

        var dialog: AlertDialog? = null
        // 提前声明以便 updateDialogUi() 可引用（实际初始化在后面行）
        var cbAssocStart: android.widget.CheckBox? = null

        val prefs = ctx.getSharedPreferences(CreditManager.PREFS_NAME, Context.MODE_PRIVATE)

        fun updateDialogUi() {
            val permState = getHomePermissionState(ctx)
            val isChineseRom = isChineseRomDevice()
            val assocStartReady = !isChineseRom || isAssocStartPermissionSatisfied(ctx)
            val baseOk = permState.allGranted
            val assocStartConfirmed = prefs.getBoolean(ASSOC_START_CONFIRMED_KEY, false)

            tvMessage.text = buildString {
                append("开启无障碍服务后，可启用以下保护功能：\n")
                append("  • 防卸载保护\n")
                append("  • 防关权限保护\n")
                append("  • 闹钟防关机保护\n\n")
                append("请先补齐以下前置项：\n\n")
                if (!baseOk) {
                    append("基础权限未齐全：${permState.missingLabels.joinToString("、")}\n")
                }
                if (!assocStartReady && !assocStartConfirmed) {
                    append("关联启动未开启（非国产ROM可忽略）\n")
                }
                if (baseOk && (assocStartReady || assocStartConfirmed)) {
                    append("\n所有前置条件已齐全，可开启无障碍。")
                }
            }

            btnBase.text = if (baseOk) "基础权限已齐全" else "去补齐基础权限"
            btnBase.isEnabled = !baseOk
            btnBase.alpha = if (btnBase.isEnabled) 1.0f else 0.45f

            // 关联启动：按钮+勾选框状态同步
            if (assocStartConfirmed) {
                cbAssocStart!!.isChecked = true
                cbAssocStart!!.alpha = 0.6f
                btnAssocStart.text = "关联启动已开启（长按清除）"
                btnAssocStart.alpha = 0.6f
            } else {
                cbAssocStart!!.isChecked = false
                cbAssocStart!!.alpha = 1.0f
                btnAssocStart.text = if (assocStartReady) "关联启动已开启" else "去设置关联启动"
                btnAssocStart.alpha = if (assocStartReady) 0.6f else 1.0f
            }

            val allReady = baseOk && (assocStartReady || assocStartConfirmed)
            btnEnable.isEnabled = allReady
            btnEnable.text = when {
                !baseOk -> "请先补齐基础权限"
                !assocStartReady && !assocStartConfirmed -> "请先开启关联启动"
                else -> "开启无障碍"
            }
        }

        btnBase.setOnClickListener {
            val permState = getHomePermissionState(ctx)
            if (!permState.allGranted) {
                (activity as? MainActivity)
                    ?.findViewById<BottomNavigationView>(R.id.bottom_navigation)
                    ?.selectedItemId = R.id.nav_home
                Toast.makeText(ctx, "请在主页完成基础权限设置", Toast.LENGTH_SHORT).show()
                dialog?.dismiss()
            }
        }

        btnAssocStart.setOnClickListener {
            if (!isAssocStartPermissionSatisfied(ctx)) {
                try {
                    RecentTasksHideManager.markNavigating()
                    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.fromParts("package", ctx.packageName, null)
                    }
                    startActivity(intent)
                    Toast.makeText(ctx, "请在应用设置中开启「关联启动」", Toast.LENGTH_LONG).show()
                } catch (_: Exception) {
                    openAppDetailsSettings()
                }
                dialog?.dismiss()
            }
        }

        btnAssocStart.setOnLongClickListener {
            prefs.edit()
                .remove(ASSOC_START_CONFIRMED_KEY).remove(ASSOC_START_CONFIRMED_AT_KEY)
                .apply()
            updateDialogUi()
            Toast.makeText(ctx, "已清除关联启动确认状态，可重新检测", Toast.LENGTH_SHORT).show()
            true
        }
        btnEnable.setOnClickListener {
            com.decikeeper.app.protection.AccessibilityProtection.markManualSettingsLaunch()
            try {
                RecentTasksHideManager.markNavigating()
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                Toast.makeText(ctx, "请在系统中找到「决定守护」并开启无障碍服务", Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                RecentTasksHideManager.clearNavigating()
                Toast.makeText(ctx, "无法打开无障碍设置", Toast.LENGTH_SHORT).show()
            }
            dialog?.dismiss()
        }

        // 关联启动行：按钮 + 勾选框在同一行
        val rowAssocStart = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        cbAssocStart = android.widget.CheckBox(ctx).apply {
            text = "已开启"
            setTextColor(ThemeManager.getColorAttr(ctx, R.attr.zzText))
        }
        cbAssocStart!!.setOnCheckedChangeListener { _, checked ->
            val now = System.currentTimeMillis()
            if (checked) {
                prefs.edit()
                    .putBoolean(ASSOC_START_CONFIRMED_KEY, true).putLong(ASSOC_START_CONFIRMED_AT_KEY, now)
                    .apply()
            } else {
                prefs.edit()
                    .remove(ASSOC_START_CONFIRMED_KEY).remove(ASSOC_START_CONFIRMED_AT_KEY)
                    .apply()
            }
            updateDialogUi()
        }
        rowAssocStart.addView(btnAssocStart, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        rowAssocStart.addView(cbAssocStart!!)

        dialog = AlertDialog.Builder(ctx)
            .setView(container)
            .create()

        buttonContainer.addView(btnBase)
        buttonContainer.addView(rowAssocStart)
        buttonContainer.addView(btnEnable)

        container.addView(buttonContainer)

        updateDialogUi()
        dialog.show()
    }

    private fun setupAlarmPowerOffProtectionSwitch(switchView: Switch) {
        val context = requireContext()
        val systemEnabled = isAccessibilityServiceEnabled()
        switchAlarmPowerOffUiUpdating = true
        switchView.isChecked = systemEnabled && AlarmPowerOffProtectionManager.isEnabled(context)
        switchAlarmPowerOffUiUpdating = false

        switchView.setOnCheckedChangeListener { _, isChecked ->
            if (switchAlarmPowerOffUiUpdating) return@setOnCheckedChangeListener

            if (isChecked) {
                if (!isA11yServiceReady(context)) {
                    switchAlarmPowerOffUiUpdating = true
                    switchView.isChecked = false
                    switchAlarmPowerOffUiUpdating = false
                    Toast.makeText(context, getString(R.string.a11y_service_not_ready_toast), Toast.LENGTH_SHORT).show()
                    return@setOnCheckedChangeListener
                }
                val prerequisite = getAlarmPowerOffPrerequisiteState(context)
                if (!prerequisite.allReady) {
                    switchAlarmPowerOffUiUpdating = true
                    switchView.isChecked = false
                    switchAlarmPowerOffUiUpdating = false
                    showAlarmPowerOffProtectionGuideDialog()
                    return@setOnCheckedChangeListener
                }

                if (!AlarmPowerOffProtectionManager.canEnable(context)) {
                    switchAlarmPowerOffUiUpdating = true
                    switchView.isChecked = false
                    switchAlarmPowerOffUiUpdating = false
                    showAlarmPowerOffProtectionGuideDialog()
                    return@setOnCheckedChangeListener
                }
                AlarmPowerOffProtectionManager.setEnabled(context, true)
                Toast.makeText(context, "闹钟防关机保护已开启", Toast.LENGTH_SHORT).show()
                updateAlarmPowerOffProtectionUI()
            } else {
                AlarmPowerOffProtectionManager.setEnabled(context, false)
                Toast.makeText(context, "闹钟防关机保护已关闭", Toast.LENGTH_SHORT).show()
                updateAlarmPowerOffProtectionUI()
            }
        }
    }

    private fun showAlarmPowerOffProtectionGuideDialog() {
        val ctx = requireContext()
        val density = ctx.resources.displayMetrics.density

        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((24 * density).toInt(), (20 * density).toInt(), (24 * density).toInt(), (20 * density).toInt())
        }

        val tvTitle = TextView(ctx).apply {
            text = "开启闹钟防关机保护"
            textSize = 18f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(ThemeManager.getColorAttr(ctx, R.attr.zzText))
        }
        container.addView(tvTitle)

        val tvMessage = TextView(ctx).apply {
            textSize = 14f
            setPadding(0, (14 * density).toInt(), 0, 0)
            setTextColor(ThemeManager.getColorAttr(ctx, R.attr.zzMuted))
            setLineSpacing(0f, 1.25f)
        }
        container.addView(tvMessage)

        fun buildButton(label: String): com.google.android.material.button.MaterialButton {
            return com.google.android.material.button.MaterialButton(ctx).apply {
                text = label
                setAllCaps(false)
                setTextColor(ThemeManager.getColorAttr(ctx, R.attr.zzText))
                backgroundTintList = android.content.res.ColorStateList.valueOf(ThemeManager.getColorAttr(ctx, R.attr.zzSurface2))
                cornerRadius = (12 * density).toInt()
                minHeight = (44 * density).toInt()
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }
        }

        val lockScreenRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val btnLockScreen = buildButton("锁屏显示").apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val cbLockScreen = android.widget.CheckBox(ctx).apply {
            text = "已开启"
            setTextColor(ThemeManager.getColorAttr(ctx, R.attr.zzText))
        }
        lockScreenRow.addView(btnLockScreen)
        lockScreenRow.addView(cbLockScreen)

        var dialog: AlertDialog? = null
        var lockScreenUiUpdating = false

        fun updateDialogUi() {
            val status = AlarmPowerOffProtectionManager.getPrerequisiteStatus(ctx)
            val lockScreenOk = status.lockScreenDisplayReady

            tvMessage.text = buildString {
                append("闹钟防关机保护会在闹钟挑战期间拦截关机菜单等系统入口。\n")
                if (lockScreenOk) {
                    append("锁屏显示已确认，可返回设置页开启保护。")
                } else {
                    append("请先开启锁屏显示，然后勾选下方“已开启”。")
                }
            }

            btnLockScreen.text = if (lockScreenOk) "锁屏显示已开启" else "去开启锁屏显示"
            btnLockScreen.isEnabled = !lockScreenOk
            btnLockScreen.alpha = if (btnLockScreen.isEnabled) 1.0f else 0.45f
            lockScreenUiUpdating = true
            cbLockScreen.isChecked = lockScreenOk
            lockScreenUiUpdating = false

        }

        btnLockScreen.setOnClickListener {
            openAppDetailsSettings()
            Toast.makeText(ctx, "请在应用权限管理中开启「锁屏显示」", Toast.LENGTH_LONG).show()
        }

        cbLockScreen.setOnCheckedChangeListener { _, checked ->
            if (lockScreenUiUpdating) return@setOnCheckedChangeListener
            AlarmPowerOffProtectionManager.setLockScreenDisplayConfirmed(ctx, checked)
            updateDialogUi()
        }

        dialog = AlertDialog.Builder(ctx)
            .setView(container)
            .create()
        alarmPrerequisiteDialogRefresh = { updateDialogUi() }
        dialog?.setOnDismissListener { alarmPrerequisiteDialogRefresh = null }

        container.addView(lockScreenRow)

        updateDialogUi()
        dialog.show()
    }

    /**
     * 防关权限保护开启时，若锁屏显示未开启则提醒用户，
     * 因为后续闹钟防关机保护需要此项权限。
     */
    /**
     * 防关权限保护开启时，若锁屏显示未开启则提醒用户，
     * 因为后续闹钟防关机保护需要此项权限。
     */
    private fun showLockScreenMissingWarningDialog() {
        val ctx = requireContext()
        AlertDialog.Builder(ctx)
            .setTitle("锁屏显示未开启")
            .setMessage("检测到锁屏显示权限未开启，开启防关保护后将拦截系统设置，后续若想开启闹钟防关机需先申请关闭本保护并等待次日生效。建议先开启锁屏显示。")
            .setPositiveButton("开启锁屏显示") { _, _ ->
                openAppDetailsSettings()
                Toast.makeText(ctx, "请在应用权限管理中开启「锁屏显示」", Toast.LENGTH_LONG).show()
            }
            .setNegativeButton("不开锁屏，仍要开启") { _, _ ->
                val permState = getHomePermissionState(ctx)
                val usageStatsOk = com.decikeeper.app.manager.PermissionManager.hasUsageStatsPermission(ctx)
                val a11yOk = isA11yServiceReady(ctx)
                if (permState.allGranted && usageStatsOk && a11yOk) {
                    enableUninstallAndPermProtection(ctx)
                } else {
                    showPermProtectionGuideDialog()
                }
            }
            .show()
    }

    /**
     * 防卸载与防关权限保护引导弹窗 — 检查基础权限+使用情况访问+无障碍+锁屏显示，然后开启保护
     */
    private fun showPermProtectionGuideDialog() {
        val ctx = requireContext()

        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(42, 30, 42, 36)
            setBackgroundResource(R.drawable.bg_zz_card)
        }

        val topRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val tvTitle = TextView(ctx).apply {
            text = "🛡️ 开启防卸载与防关权限保护"
            textSize = 20f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(ThemeManager.getColorAttr(ctx, R.attr.zzText))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val btnClose = TextView(ctx).apply {
            text = "❌"
            textSize = 16f
            setTextColor(ThemeManager.getColorAttr(ctx, R.attr.zzMuted))
            setPadding(20, 6, 0, 6)
        }

        topRow.addView(tvTitle)
        topRow.addView(btnClose)
        container.addView(topRow)

        val tvMessage = TextView(ctx).apply {
            textSize = 14f
            setPadding(0, 20, 0, 0)
            setTextColor(ThemeManager.getColorAttr(ctx, R.attr.zzText))
        }
        container.addView(tvMessage)

        val density = ctx.resources.displayMetrics.density

        fun buildButton(label: String): Button {
            return Button(ctx).apply {
                text = label
                setAllCaps(false)
                textSize = 13f
                setTextColor(ThemeManager.getColorAttr(ctx, R.attr.zzText))
                setBackgroundResource(R.drawable.bg_zz_pill)
                backgroundTintList = android.content.res.ColorStateList.valueOf(ThemeManager.getColorAttr(ctx, R.attr.zzSurface2))
                minHeight = (44 * density).toInt()
                setPadding((12 * density).toInt(), (8 * density).toInt(), (12 * density).toInt(), (8 * density).toInt())
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    setMargins(0, (8 * density).toInt(), 0, 0)
                }
            }
        }

        val btnBase = buildButton("基础权限")
        val btnUsageAccess = buildButton("使用情况访问")
        val btnA11y = buildButton("开启无障碍")

        val lockScreenRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, (8 * density).toInt(), 0, 0)
            }
        }

        val btnLockScreen = Button(ctx).apply {
            text = "锁屏显示"
            setAllCaps(false)
            textSize = 13f
            setTextColor(ThemeManager.getColorAttr(ctx, R.attr.zzText))
            setBackgroundResource(R.drawable.bg_zz_pill)
            backgroundTintList = android.content.res.ColorStateList.valueOf(ThemeManager.getColorAttr(ctx, R.attr.zzSurface2))
            minHeight = (44 * density).toInt()
            setPadding((12 * density).toInt(), (8 * density).toInt(), (12 * density).toInt(), (8 * density).toInt())
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = (8 * density).toInt()
            }
        }

        val cbLockScreen = android.widget.CheckBox(ctx).apply {
            text = "已开启"
            textSize = 14f
            setTextColor(ThemeManager.getColorAttr(ctx, R.attr.zzText))
        }

        lockScreenRow.addView(btnLockScreen)
        lockScreenRow.addView(cbLockScreen)

        // 当基础权限+使用情况访问+无障碍齐全时，显示"开启保护"按钮
        val btnEnable = Button(ctx).apply {
            text = "开启保护"
            setAllCaps(false)
            textSize = 14f
            setTextColor(android.graphics.Color.WHITE)
            setBackgroundResource(R.drawable.bg_zz_pill)
            backgroundTintList = android.content.res.ColorStateList.valueOf(ThemeManager.getColorAttr(ctx, R.attr.zzAccentStrong))
            minHeight = (46 * density).toInt()
            setPadding((10 * density).toInt(), (10 * density).toInt(), (10 * density).toInt(), (10 * density).toInt())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, (14 * density).toInt(), 0, 0)
            }
            visibility = View.GONE
        }

        var dialog: AlertDialog? = null
        var lockScreenUiUpdating = false

        fun updateDialogUi() {
            val permState = getHomePermissionState(ctx)
            val usageStatsOk = com.decikeeper.app.manager.PermissionManager.hasUsageStatsPermission(ctx)
            val lockScreenOk = AlarmPowerOffProtectionManager.isLockScreenDisplayReady(ctx)
            val a11yOk = isA11yServiceReady(ctx)
            val baseOk = permState.allGranted

            tvMessage.text = buildString {
                append("「防卸载与防关权限保护」通过无障碍服务监控关键权限状态，防止卸载以及悬浮窗、通知等关键权限被系统或意外关闭。\n\n")
                append("请补齐以下关键权限。其中「使用情况访问」为时间规则运行所需，「锁屏显示」用于闹钟防关机保护：\n\n")
                if (!baseOk) {
                    append("⚠️ 基础权限未齐全：${permState.missingLabels.joinToString("、")}\n")
                } else {
                    append("✅ 基础权限已齐全\n")
                }
                if (!usageStatsOk) {
                    append("⚠️ 使用情况访问未开启（时间规则执行需要）\n")
                } else {
                    append("✅ 使用情况访问已开启\n")
                }
                if (!a11yOk) {
                    append("⚠️ 无障碍服务未开启或处于待关闭状态\n")
                } else {
                    append("✅ 无障碍已开启\n")
                }
                if (!lockScreenOk) {
                    append("⚠️ 锁屏显示未开启（闹钟防关机保护需要）\n")
                } else {
                    append("✅ 锁屏显示已开启\n")
                }
                if (baseOk && usageStatsOk && a11yOk) {
                    append("\n✅ 基础权限、使用情况访问和无障碍已齐全，可开启保护。")
                    if (!lockScreenOk) {
                        append("\n💡 建议先开启锁屏显示，以便今后使用闹钟防关机保护。")
                    }
                }
            }

            btnBase.text = if (baseOk) "基础权限已齐全" else "去补齐基础权限"
            btnBase.isEnabled = !baseOk
            btnBase.alpha = if (btnBase.isEnabled) 1.0f else 0.45f

            btnUsageAccess.text = if (usageStatsOk) "使用情况已开启" else "去开启使用情况"
            btnUsageAccess.isEnabled = !usageStatsOk
            btnUsageAccess.alpha = if (btnUsageAccess.isEnabled) 1.0f else 0.45f

            btnA11y.text = if (a11yOk) "无障碍已开启" else "去开启无障碍"
            btnA11y.isEnabled = !a11yOk
            btnA11y.alpha = if (btnA11y.isEnabled) 1.0f else 0.45f

            btnLockScreen.text = if (lockScreenOk) "锁屏显示已开启" else "去开启锁屏显示"
            btnLockScreen.isEnabled = !lockScreenOk
            btnLockScreen.alpha = if (btnLockScreen.isEnabled) 1.0f else 0.45f

            lockScreenUiUpdating = true
            cbLockScreen.isChecked = lockScreenOk
            lockScreenUiUpdating = false

            // 基础权限 + 使用情况访问 + 无障碍齐全时才显示"开启保护"按钮
            btnEnable.visibility = if (baseOk && usageStatsOk && a11yOk) View.VISIBLE else View.GONE
        }

        btnBase.setOnClickListener {
            val permState = getHomePermissionState(ctx)
            if (!permState.allGranted) {
                (activity as? MainActivity)
                    ?.findViewById<BottomNavigationView>(R.id.bottom_navigation)
                    ?.selectedItemId = R.id.nav_home
                Toast.makeText(ctx, "请在主页完成基础权限设置", Toast.LENGTH_SHORT).show()
                dialog?.dismiss()
            }
        }

        btnUsageAccess.setOnClickListener {
            try {
                RecentTasksHideManager.markNavigating()
                startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
                Toast.makeText(ctx, "请开启「使用情况访问」权限（若系统已显示开启仍提示未开，请关闭后重新打开一次）", Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                RecentTasksHideManager.clearNavigating()
                Toast.makeText(ctx, "无法跳转至使用情况访问设置页", Toast.LENGTH_SHORT).show()
            }
        }

        btnA11y.setOnClickListener {
            if (!isAccessibilityServiceEnabled()) {
                dialog?.dismiss()
                Toast.makeText(ctx, "请先使用上方「无障碍服务」开关补齐前置权限并开启无障碍", Toast.LENGTH_LONG).show()
            }
        }

        btnLockScreen.setOnClickListener {
            openAppDetailsSettings()
            Toast.makeText(ctx, "请在应用权限管理中开启「锁屏显示」，返回后勾选「已开启」", Toast.LENGTH_LONG).show()
        }

        cbLockScreen.setOnCheckedChangeListener { _, checked ->
            if (lockScreenUiUpdating) return@setOnCheckedChangeListener
            AlarmPowerOffProtectionManager.setLockScreenDisplayConfirmed(ctx, checked)
            updateDialogUi()
        }

        btnEnable.setOnClickListener {
            if (!AlarmPowerOffProtectionManager.isLockScreenDisplayReady(ctx)) {
                dialog?.dismiss()
                showLockScreenMissingWarningDialog()
            } else {
                enableUninstallAndPermProtection(ctx)
                dialog?.dismiss()
            }
        }

        dialog = AlertDialog.Builder(ctx)
            .setView(container)
            .create()
        dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))

        dialog.setOnDismissListener {
            permProtectionGuideDialogRefresh = null
        }

        btnClose.setOnClickListener { dialog?.dismiss() }

        container.addView(btnBase)
        container.addView(btnUsageAccess)
        container.addView(btnA11y)
        container.addView(lockScreenRow)
        container.addView(btnEnable)

        permProtectionGuideDialogRefresh = { updateDialogUi() }
        updateDialogUi()
        dialog.show()
    }

    private fun showAntiUninstallGuideDialog() {
        val ctx = requireContext()

        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(42, 30, 42, 36)
            setBackgroundResource(R.drawable.bg_zz_card)
        }

        val topRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val tvTitle = TextView(ctx).apply {
            text = "🛡️ 开启防卸载保护"
            textSize = 20f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(ThemeManager.getColorAttr(ctx, R.attr.zzText))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val btnClose = TextView(ctx).apply {
            text = "❌"
            textSize = 16f
            setTextColor(ThemeManager.getColorAttr(ctx, R.attr.zzMuted))
            setPadding(20, 6, 0, 6)
        }

        topRow.addView(tvTitle)
        topRow.addView(btnClose)
        container.addView(topRow)

        val tvMessage = TextView(ctx).apply {
            textSize = 14f
            setPadding(0, 20, 0, 0)
            setTextColor(ThemeManager.getColorAttr(ctx, R.attr.zzText))
        }
        container.addView(tvMessage)

        val density = ctx.resources.displayMetrics.density
        val btnMargin = (4 * density).toInt()

        fun buildButton(label: String): Button {
            return Button(ctx).apply {
                text = label
                setAllCaps(false)
                textSize = 13f
                setTextColor(ThemeManager.getColorAttr(ctx, R.attr.zzText))
                setBackgroundResource(R.drawable.bg_zz_pill)
                backgroundTintList = android.content.res.ColorStateList.valueOf(ThemeManager.getColorAttr(ctx, R.attr.zzSurface2))
                minHeight = (44 * density).toInt()
                setPadding((10 * density).toInt(), (8 * density).toInt(), (10 * density).toInt(), (8 * density).toInt())
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    setMargins(0, 0, 0, btnMargin)
                }
            }
        }

        val btnBase = buildButton("基础权限")
        val btnBgBattery = buildButton("允许后台耗电")
        val btnAssocStart = buildButton("关联启动")

        val btnA11y = buildButton("开启无障碍")

        var dialog: AlertDialog? = null

        fun updateDialogUi() {
            val permState = getHomePermissionState(ctx)
            val isChineseRom = isChineseRomDevice()
            val bgBatteryReady = !isChineseRom || isBgBatteryPermissionSatisfied(ctx)
            val assocStartReady = !isChineseRom || isAssocStartPermissionSatisfied(ctx)
            val prerequisite = evaluateAntiUninstallPrerequisiteState(
                baseReady = permState.allGranted,
                bgBatteryReady = bgBatteryReady,
                assocStartReady = assocStartReady
            )

            tvMessage.text = buildString {
                append("防卸载功能需要开启「无障碍服务」。\n")
                append("开启后，决定守护会保护关键防杀权限，防止应用被系统意外杀掉。\n\n")
                append("开启顺序：先补齐前置权限并开启无障碍，最后再开启防卸载。\n")
                append("关闭规则：防卸载与无障碍都按延迟生效；支持同次提交双关闭，无需分两次等待。\n\n")
                if (!prerequisite.baseReady) {
                    append("⚠️ 基础权限未齐全：${permState.missingLabels.joinToString("、")}")
                } else if (!prerequisite.bgBatteryReady || !prerequisite.assocStartReady) {
                    append("⚠️ 高级前置权限未齐全：\n")
                    if (!prerequisite.bgBatteryReady) append(" • 允许后台耗电\n")
                    if (!prerequisite.assocStartReady) append(" • 关联启动\n")
                } else {
                    append("✅ 前置权限已齐全，可直接开启无障碍。")
                }
            }

            btnBase.text = if (prerequisite.baseReady) "基础权限已齐全" else "去补齐基础权限"
            btnBase.isEnabled = !prerequisite.baseReady
            btnBase.alpha = if (btnBase.isEnabled) 1.0f else 0.45f

            btnBgBattery.text = if (prerequisite.bgBatteryReady) "允许后台耗电已开启" else "去开启允许后台耗电"
            btnBgBattery.isEnabled = !prerequisite.bgBatteryReady
            btnBgBattery.alpha = if (btnBgBattery.isEnabled) 1.0f else 0.45f

            btnAssocStart.text = if (prerequisite.assocStartReady) "关联启动已开启" else "去设置关联启动"
            btnAssocStart.isEnabled = !prerequisite.assocStartReady
            btnAssocStart.alpha = if (btnAssocStart.isEnabled) 1.0f else 0.45f

            btnA11y.isEnabled = prerequisite.allReady
            btnA11y.alpha = if (btnA11y.isEnabled) 1.0f else 0.45f
        }

        btnBase.setOnClickListener {
            val permState = getHomePermissionState(ctx)
            if (!permState.allGranted) {
                (activity as? MainActivity)
                    ?.findViewById<BottomNavigationView>(R.id.bottom_navigation)
                    ?.selectedItemId = R.id.nav_home
                Toast.makeText(ctx, "请在主页完成基础权限设置", Toast.LENGTH_SHORT).show()
                dialog?.dismiss()
            }
        }

        btnBgBattery.setOnClickListener {
            openBgBatterySettings()
            Toast.makeText(ctx, "完成设置后请返回本页重新检测", Toast.LENGTH_SHORT).show()
            dialog?.dismiss()
        }

        btnAssocStart.setOnClickListener {
            ManualPermissionRecoveryManager.recordSettingsVisit(ctx, ASSOC_START_CONFIRMED_KEY)
            if (!SettingsNavigator.openAssociateStartSettings(ctx)) {
                try {
                    RecentTasksHideManager.markNavigating()
                    startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:${ctx.packageName}")))
                } catch (_: Exception) {
                }
            }
            val tip = if (SettingsNavigator.isOppoOrOnePlusOrRealme()) {
                "请前往「设置 → 应用 → 关联启动」开启「决定守护」"
            } else {
                "请在系统设置中开启关联启动；返回后会优先自动识别，若机型仍误判可手动确认一次"
            }
            Toast.makeText(ctx, tip, Toast.LENGTH_LONG).show()
            dialog?.dismiss()
        }

        btnA11y.setOnClickListener {
            dialog?.dismiss()
            Toast.makeText(ctx, "请先使用上方「无障碍服务」开关补齐前置权限并开启无障碍", Toast.LENGTH_LONG).show()
        }

        dialog = AlertDialog.Builder(ctx)
            .setView(container)
            .create()
        dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))

        btnClose.setOnClickListener { dialog?.dismiss() }

        val buttonContainer = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 28, 0, (10 * density).toInt())
        }
        buttonContainer.addView(btnBase)
        buttonContainer.addView(btnBgBattery)
        buttonContainer.addView(btnAssocStart)
        buttonContainer.addView(btnA11y)

        container.addView(buttonContainer)

        updateDialogUi()
        dialog.show()
    }

    private fun getAntiUninstallPrerequisiteState(ctx: Context): AntiUninstallPrerequisiteState {
        val baseState = getHomePermissionState(ctx)
        val isChineseRom = isChineseRomDevice()
        val bgBatteryReady = !isChineseRom || isBgBatteryPermissionSatisfied(ctx)
        val assocStartReady = !isChineseRom || isAssocStartPermissionSatisfied(ctx)
        return evaluateAntiUninstallPrerequisiteState(
            baseReady = baseState.allGranted,
            bgBatteryReady = bgBatteryReady,
            assocStartReady = assocStartReady
        )
    }

    private fun getAlarmPowerOffPrerequisiteState(ctx: Context): AlarmPowerOffPrerequisiteState {
        val status = AlarmPowerOffProtectionManager.getPrerequisiteStatus(ctx)
        return AlarmPowerOffPrerequisiteState(
            missingLabels = status.missingLabels
        )
    }

    private fun isAssocStartPermissionSatisfied(ctx: Context): Boolean {
        if (!isChineseRomDevice()) return true
        return isManualPermissionShortTimeSatisfied(
            ctx = ctx,
            confirmedKey = ASSOC_START_CONFIRMED_KEY,
            confirmedAtKey = ASSOC_START_CONFIRMED_AT_KEY
        )
    }

    private fun isManualPermissionShortTimeSatisfied(
        ctx: Context,
        confirmedKey: String,
        confirmedAtKey: String
    ): Boolean {
        val prefs = ctx.getSharedPreferences(CreditManager.PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.getBoolean(confirmedKey, false)) {
            return true
        }

        when (detectAndSyncManualPermission(ctx, prefs, confirmedKey, confirmedAtKey)) {
            true -> return true
            false -> return false
            null -> return false
        }
    }

    private fun detectAndSyncManualPermission(
        ctx: Context,
        prefs: android.content.SharedPreferences,
        confirmedKey: String,
        confirmedAtKey: String
    ): Boolean? {
        val type = when (confirmedKey) {
            AUTO_START_CONFIRMED_KEY -> RomPermissionDetector.ManualPermission.AUTO_START
            BG_POPUP_CONFIRMED_KEY -> RomPermissionDetector.ManualPermission.BG_POPUP
            ASSOC_START_CONFIRMED_KEY -> RomPermissionDetector.ManualPermission.ASSOC_START
            else -> return null
        }

        return when (RomPermissionDetector.detectManualPermission(ctx, type)) {
            true -> {
                prefs.edit()
                    .putBoolean(confirmedKey, true)
                    .putLong(confirmedAtKey, System.currentTimeMillis())
                    .apply()
                ManualPermissionRecoveryManager.clearSettingsVisit(ctx, confirmedKey)
                true
            }
            false -> {
                false
            }
            null -> {
                val hasGuidedVisit = ManualPermissionRecoveryManager.hasRecentGuidedVisit(ctx, confirmedKey)
                if (ManualPermissionRecoveryManager.shouldAutoMarkByGuidedFlow(null, hasGuidedVisit)) {
                    prefs.edit()
                        .putBoolean(confirmedKey, true)
                        .putLong(confirmedAtKey, System.currentTimeMillis())
                        .apply()
                    ManualPermissionRecoveryManager.clearSettingsVisit(ctx, confirmedKey)
                    true
                } else {
                    null
                }
            }
        }
    }

    private data class HomePermissionState(
        val allGranted: Boolean,
        val missingLabels: List<String>
    )

    private fun maybeAskBgBatteryPermissionAfterAntiUninstallEnabled() {
        val ctx = context ?: return
        if (!isChineseRomDevice()) return
        if (isBgBatteryPermissionSatisfied(ctx)) return

        AlertDialog.Builder(ctx)
            .setTitle("后台耗电设置（可选）")
            .setMessage("为降低被系统省电策略误杀风险，建议开启「允许后台耗电」。\n\n仅在你准备长期开启防卸载时再设置即可，正常使用可稍后再配。")
            .setPositiveButton("去设置") { _, _ ->
                openBgBatterySettings()
                Toast.makeText(ctx, "返回后将自动识别是否已开启后台耗电", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("暂不需要", null)
            .show()
    }

    private fun isBgBatteryPermissionSatisfied(ctx: Context): Boolean {
        val bySystemApi = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = ctx.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
            pm.isIgnoringBatteryOptimizations(ctx.packageName)
        } else {
            true
        }

        // 兼容历史版本的手动标记，避免老用户状态回退
        val prefs = ctx.getSharedPreferences(CreditManager.PREFS_NAME, Context.MODE_PRIVATE)
        val byLegacyFlag = prefs.getBoolean("bg_battery_confirmed", false)
        val byManualConfirm = prefs.getBoolean("bg_battery_permission_confirmed", false)

        return bySystemApi || byLegacyFlag || byManualConfirm
    }

    private fun openBgBatterySettings() {
        val ctx = context ?: return
        val manufacturer = Build.MANUFACTURER.lowercase()
        val intents = mutableListOf<Intent>()

        // ★ 厂商专用 Intent（优先于通用，可能需 signature 权限，try-catch 兜底）
        when {
            manufacturer.contains("vivo") -> {
                // 1) Vivo 后台耗电管理（含 允许/智能限制/深度限制 三选项）
                intents.add(Intent().setComponent(android.content.ComponentName(
                    "com.iqoo.powersaving",
                    "com.iqoo.powersaving.activity.ExcessivePowerDescriptionActivity"
                )).apply { putExtra("package_name", ctx.packageName) })
                // 2) Vivo 电池使用情况页面
                intents.add(Intent().setComponent(android.content.ComponentName(
                    "com.iqoo.powersaving",
                    "com.iqoo.powersaving.fuelgauge.PowerUsageSummaryActivity"
                )))
                // 3) Vivo 高耗电管理
                intents.add(Intent().setComponent(android.content.ComponentName(
                    "com.vivo.abe",
                    "com.vivo.applicationbehaviorengine.ui.ExcessivePowerManagerActivity"
                )).apply { putExtra("package_name", ctx.packageName) })
                intents.add(Intent().setComponent(android.content.ComponentName(
                    "com.iqoo.powersaving",
                    "com.iqoo.powersaving.activity.ExcessivePowerManagerActivity"
                )).apply { putExtra("package_name", ctx.packageName) })
                intents.add(Intent().setComponent(android.content.ComponentName(
                    "com.iqoo.powersaving",
                    "com.iqoo.powersaving.BackgroundHighUsageActivity"
                )).apply { putExtra("package_name", ctx.packageName) })
                intents.add(Intent("com.vivo.abe.highpower.search.powermanageractivity").apply {
                    putExtra("package_name", ctx.packageName)
                })
                // 尝试系统设置-电池子页面
                intents.add(Intent().setComponent(android.content.ComponentName(
                    "com.android.settings",
                    "com.android.settings.Settings\$PowerUsageSummaryActivity"
                )))
                intents.add(Intent().setComponent(android.content.ComponentName(
                    "com.android.settings",
                    "com.android.settings.Settings\$BatterySaverSettingsActivity"
                )))
            }
            manufacturer.contains("xiaomi") -> {
                intents.add(Intent().setComponent(android.content.ComponentName(
                    "com.miui.powerkeeper",
                    "com.miui.powerkeeper.ui.HiddenAppsConfigActivity"
                )).apply {
                    putExtra("package_name", ctx.packageName)
                    putExtra("package_label", ctx.applicationInfo.loadLabel(ctx.packageManager).toString())
                })
            }
            manufacturer.contains("huawei") || manufacturer.contains("honor") -> {
                intents.add(Intent().setComponent(android.content.ComponentName(
                    "com.huawei.systemmanager",
                    "com.huawei.systemmanager.power.ui.HwPowerManagerActivity"
                )))
                intents.add(Intent().setComponent(android.content.ComponentName(
                    "com.huawei.systemmanager",
                    "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"
                )))
            }
            manufacturer.contains("oppo") || manufacturer.contains("realme") -> {
                intents.add(Intent().setComponent(android.content.ComponentName(
                    "com.coloros.oppoguardelf",
                    "com.coloros.powermanager.fuelgaue.PowerUsageModelActivity"
                )))
            }
        }

        // ★ 通用标准 Intent（兜底）
        // Vivo 上 VIEW_ADVANCED_POWER_USAGE_DETAIL 跳转到「应用的电池使用情况」
        // 那是一个完全不同的页面（另一座山），对用户没帮助，跳过
        if (!manufacturer.contains("vivo")) {
            intents.add(Intent("android.settings.VIEW_ADVANCED_POWER_USAGE_DETAIL",
                android.net.Uri.parse("package:${ctx.packageName}")))
        }
        // 电池使用情况汇总
        intents.add(Intent(Intent.ACTION_POWER_USAGE_SUMMARY))
        intents.add(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        intents.add(Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS))

        var launched = false
        for (intent in intents) {
            try {
                RecentTasksHideManager.markNavigating()
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent)
                launched = true
                break
            } catch (e: Exception) {
                RecentTasksHideManager.clearNavigating()
                Log.w("RulesFragment", "Failed to open battery settings", e)
            }
        }
        if (!launched) {
            val guideMsg = if (Build.MANUFACTURER.lowercase().contains("vivo")) {
                "操作步骤：\n1. 点击「电量」\n2. 点击「后台耗电管理」\n3. 找到决定守护并点击\n4. 选择「允许后台耗电」"
            } else {
                "请在后台耗电管理中选择「允许后台耗电」"
            }
            // 用 Toast 展示指引（兼容性好，不依赖 Activity 状态）
            try {
                // 显示多次 Toast 以延长可见时间（每次约3.5秒，共约10秒）
                val toast = Toast.makeText(ctx, guideMsg, Toast.LENGTH_LONG)
                toast.show()
                // 再补一次，让总显示时间更长
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    try {
                        val toast2 = Toast.makeText(ctx, guideMsg, Toast.LENGTH_LONG)
                        toast2.show()
                    } catch (_: Exception) {}
                }, 4000)
            } catch (e: Exception) {
                Log.w("RulesFragment", "Failed to show guide toast", e)
            }
            // 然后打开应用信息页面
            try {
                RecentTasksHideManager.markNavigating()
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    android.net.Uri.parse("package:${ctx.packageName}")))
            } catch (e: Exception) {
                RecentTasksHideManager.clearNavigating()
                Log.w("RulesFragment", "Failed to open app details settings", e)
            }
        }
    }

    private fun getHomePermissionState(ctx: Context): HomePermissionState {
        val hasOverlay = PermissionManager.hasOverlayPermission(ctx)
        val hasBgBattery = !isChineseRomDevice() || isBgBatteryPermissionSatisfied(ctx)
        val hasAutoStart = isManualPermissionShortTimeSatisfied(
            ctx = ctx,
            confirmedKey = AUTO_START_CONFIRMED_KEY,
            confirmedAtKey = AUTO_START_CONFIRMED_AT_KEY
        )

        val missing = mutableListOf<String>()
        if (!hasOverlay) missing.add("悬浮窗")
        if (!hasBgBattery) missing.add("后台耗电管理")
        if (!hasAutoStart) missing.add("自启动")

        val allGranted = missing.isEmpty()
        return HomePermissionState(allGranted = allGranted, missingLabels = missing)
    }

    private fun isChineseRomDevice(): Boolean {
        val manufacturer = Build.MANUFACTURER.lowercase()
        return manufacturer.contains("xiaomi") ||
            manufacturer.contains("vivo") ||
            manufacturer.contains("oppo") ||
            manufacturer.contains("huawei") ||
            manufacturer.contains("honor") ||
            manufacturer.contains("realme")
    }

    private fun triggerAntiUninstallStatusLoop(view: View) {
        view.postDelayed({
            if (isAdded && !isDetached) {
                updateUninstallAndPermProtectionUI()
                triggerAntiUninstallStatusLoop(view)
            }
        }, 60000)
    }

    private enum class CombinedProtectionState { DISABLED, ACTIVE, PENDING }

    private fun getCombinedProtectionState(context: Context): CombinedProtectionState {
        if (!isAccessibilityServiceEnabled(context)) return CombinedProtectionState.DISABLED
        val antiState = AntiUninstallManager.getProtectionState(context)
        return when (antiState) {
            AntiUninstallManager.State.ACTIVE -> CombinedProtectionState.ACTIVE
            AntiUninstallManager.State.PENDING -> CombinedProtectionState.PENDING
            AntiUninstallManager.State.DISABLED -> CombinedProtectionState.DISABLED
        }
    }

    private fun updateUninstallAndPermProtectionUI() {
        if (!isAdded) return
        val ctx = requireContext()
        val locked = TimeRuleEditWindowPolicyManager.isEditLocked(ctx)
        val state = getCombinedProtectionState(ctx)
        switchUninstallAndPermUiUpdating = true
        switchUninstallAndPermProtection?.isChecked = state == CombinedProtectionState.ACTIVE
        switchUninstallAndPermProtection?.isEnabled = !locked
        switchUninstallAndPermProtection?.isClickable = !locked
        switchUninstallAndPermProtection?.alpha = if (locked) 0.5f else 1.0f
        switchUninstallAndPermUiUpdating = false

        tvUninstallAndPermProtectionStatus?.let {
            it.text = when (state) {
                CombinedProtectionState.ACTIVE -> "状态: 已开启"
                CombinedProtectionState.PENDING -> "状态: 待关闭（明日生效）"
                CombinedProtectionState.DISABLED -> "状态: 未开启"
            }
            it.setTextColor(
                ThemeManager.getColorAttr(
                    ctx,
                    if (state == CombinedProtectionState.PENDING) R.attr.zzWarning
                    else if (state == CombinedProtectionState.ACTIVE) R.attr.zzSuccess
                    else R.attr.zzMuted
                )
            )
        }
    }

    private fun updateFloatingWindowProtectionUI() {
        if (!isAdded) return
        val ctx = requireContext()
        val locked = TimeRuleEditWindowPolicyManager.isEditLocked(ctx)
        val state = FloatingWindowProtectionManager.getProtectionState(ctx)
        switchFloatingWindowUiUpdating = true
        switchFloatingWindowProtection?.isChecked = state == FloatingWindowProtectionManager.State.ACTIVE
        switchFloatingWindowProtection?.isEnabled = !locked
        switchFloatingWindowProtection?.isClickable = !locked
        switchFloatingWindowProtection?.alpha = if (locked) 0.5f else 1.0f
        switchFloatingWindowUiUpdating = false
        tvFloatingWindowProtectionStatus?.let {
            it.text = when (state) {
                FloatingWindowProtectionManager.State.ACTIVE -> "状态: 已开启"
                FloatingWindowProtectionManager.State.PENDING -> "状态: 待关闭（明日生效）"
                FloatingWindowProtectionManager.State.DISABLED -> "状态: 未开启"
            }
            it.setTextColor(
                ThemeManager.getColorAttr(
                    ctx,
                    if (state == FloatingWindowProtectionManager.State.PENDING) R.attr.zzWarning
                    else if (state == FloatingWindowProtectionManager.State.ACTIVE) R.attr.zzSuccess
                    else R.attr.zzMuted
                )
            )
        }
    }

    private fun updateAccessibilityServiceUI() {
        if (!isAdded) return
        val ctx = requireContext()
        val locked = TimeRuleEditWindowPolicyManager.isEditLocked(ctx)
        val a11yOk = isAccessibilityServiceEnabled()
        val a11yState = AccessibilityControlManager.getState(ctx)

        switchA11yServiceUiUpdating = true
        // 只有业务处于 ACTIVE 且系统服务开启时才勾选
        switchAccessibilityService?.isChecked = (a11yState == AccessibilityControlManager.State.ACTIVE && a11yOk)
        switchAccessibilityService?.isEnabled = !locked
        switchAccessibilityService?.isClickable = !locked
        switchAccessibilityService?.alpha = if (locked) 0.5f else 1.0f
        switchA11yServiceUiUpdating = false

        tvAccessibilityStatus?.let {
            when {
                a11yState == AccessibilityControlManager.State.PENDING -> {
                    it.text = "状态: 待关闭（明日生效）"
                    it.setTextColor(ThemeManager.getColorAttr(ctx, R.attr.zzWarning))
                }
                a11yState == AccessibilityControlManager.State.ACTIVE && a11yOk -> {
                    it.text = "状态: 无障碍服务已开启（防关保护生效中）"
                    it.setTextColor(ThemeManager.getColorAttr(ctx, R.attr.zzSuccess))
                }
                a11yState == AccessibilityControlManager.State.ACTIVE && !a11yOk -> {
                    it.text = "状态: 无障碍服务已断开（保护配置保持中，请前往开启）"
                    it.setTextColor(ThemeManager.getColorAttr(ctx, R.attr.zzWarning))
                }
                a11yState == AccessibilityControlManager.State.DISABLED && a11yOk -> {
                    it.text = "状态: 守护已停用（待在系统设置中关闭服务）"
                    it.setTextColor(ThemeManager.getColorAttr(ctx, R.attr.zzWarning))
                }
                else -> {
                    it.text = "状态: 服务未开启"
                    it.setTextColor(ThemeManager.getColorAttr(ctx, R.attr.zzMuted))
                }
            }
        }
    }

    private fun showPostImmediateDisableGuideDialog(ctx: Context) {
        val density = ctx.resources.displayMetrics.density
        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((24 * density).toInt(), (20 * density).toInt(), (24 * density).toInt(), (20 * density).toInt())
        }

        val tvTitle = TextView(ctx).apply {
            text = "保护已解除"
            textSize = 18f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(ThemeManager.getColorAttr(ctx, R.attr.zzText))
        }
        container.addView(tvTitle)

        val tvMessage = TextView(ctx).apply {
            textSize = 14f
            setPadding(0, (14 * density).toInt(), 0, 0)
            setTextColor(ThemeManager.getColorAttr(ctx, R.attr.zzMuted))
            setLineSpacing(0f, 1.25f)
            text = "无障碍防关保护已解除，决定守护不再使用无障碍功能。\n\n是否现在前往系统设置关闭底层的无障碍服务？"
        }
        container.addView(tvMessage)

        val buttonRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, (20 * density).toInt(), 0, 0)
            gravity = Gravity.CENTER_VERTICAL
        }

        val btnCancel = com.google.android.material.button.MaterialButton(ctx).apply {
            text = "暂不关闭"
            setAllCaps(false)
            setTextColor(ThemeManager.getColorAttr(ctx, R.attr.zzText))
            backgroundTintList = android.content.res.ColorStateList.valueOf(ThemeManager.getColorAttr(ctx, R.attr.zzSurface2))
            cornerRadius = (12 * density).toInt()
            minHeight = (44 * density).toInt()
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(0, 0, (8 * density).toInt(), 0)
            }
        }

        val btnGo = com.google.android.material.button.MaterialButton(ctx).apply {
            text = "前往系统关闭"
            setAllCaps(false)
            setTextColor(android.graphics.Color.WHITE)
            backgroundTintList = android.content.res.ColorStateList.valueOf(ThemeManager.getColorAttr(ctx, R.attr.zzAccent))
            cornerRadius = (12 * density).toInt()
            minHeight = (44 * density).toInt()
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        buttonRow.addView(btnCancel)
        buttonRow.addView(btnGo)
        container.addView(buttonRow)

        val dialog = AlertDialog.Builder(ctx).setView(container).create()
        btnCancel.setOnClickListener { dialog.dismiss() }
        btnGo.setOnClickListener {
            dialog.dismiss()
            A11ySelfDisableAuthorization.authorize(SelfDisableReason.USER_CONFIRMED, 60_000L)
            openAccessibilitySettings()
        }
        dialog.show()
    }

    private fun showNextDayA11yDisableGuideDialog(ctx: Context) {
        val density = ctx.resources.displayMetrics.density
        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((24 * density).toInt(), (20 * density).toInt(), (24 * density).toInt(), (20 * density).toInt())
        }

        val tvTitle = TextView(ctx).apply {
            text = "无障碍关闭已生效"
            textSize = 18f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(ThemeManager.getColorAttr(ctx, R.attr.zzText))
        }
        container.addView(tvTitle)

        val tvMessage = TextView(ctx).apply {
            textSize = 14f
            setPadding(0, (14 * density).toInt(), 0, 0)
            setTextColor(ThemeManager.getColorAttr(ctx, R.attr.zzMuted))
            setLineSpacing(0f, 1.25f)
            text = "您昨日申请的无障碍服务关闭已正式生效，相关保护已解除。\n\n是否前往系统设置关闭底层的无障碍服务？"
        }
        container.addView(tvMessage)

        val buttonRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, (20 * density).toInt(), 0, 0)
            gravity = Gravity.CENTER_VERTICAL
        }

        val btnCancel = com.google.android.material.button.MaterialButton(ctx).apply {
            text = "稍后再说"
            setAllCaps(false)
            setTextColor(ThemeManager.getColorAttr(ctx, R.attr.zzText))
            backgroundTintList = android.content.res.ColorStateList.valueOf(ThemeManager.getColorAttr(ctx, R.attr.zzSurface2))
            cornerRadius = (12 * density).toInt()
            minHeight = (44 * density).toInt()
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(0, 0, (8 * density).toInt(), 0)
            }
        }

        val btnGo = com.google.android.material.button.MaterialButton(ctx).apply {
            text = "前往系统关闭"
            setAllCaps(false)
            setTextColor(android.graphics.Color.WHITE)
            backgroundTintList = android.content.res.ColorStateList.valueOf(ThemeManager.getColorAttr(ctx, R.attr.zzAccent))
            cornerRadius = (12 * density).toInt()
            minHeight = (44 * density).toInt()
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        buttonRow.addView(btnCancel)
        buttonRow.addView(btnGo)
        container.addView(buttonRow)

        val dialog = AlertDialog.Builder(ctx).setView(container).create()
        btnCancel.setOnClickListener { dialog.dismiss() }
        btnGo.setOnClickListener {
            dialog.dismiss()
            A11ySelfDisableAuthorization.authorize(SelfDisableReason.USER_CONFIRMED, 60_000L)
            openAccessibilitySettings()
        }
        dialog.show()
    }

    private fun updateAlarmPowerOffProtectionUI() {
        if (!isAdded) return
        val context = requireContext()
        val status = AlarmPowerOffProtectionManager.getPrerequisiteStatus(context)
        val configuredEnabled = AlarmPowerOffProtectionManager.isEnabled(context)
        val enabled = configuredEnabled && status.allReady

        switchAlarmPowerOffUiUpdating = true
        switchAlarmPowerOffProtection?.isChecked = enabled
        switchAlarmPowerOffUiUpdating = false

        tvAlarmPowerOffProtectionStatus?.text = if (enabled) "状态: 已开启" else "状态: 未开启"
        tvAlarmPowerOffProtectionStatus?.setTextColor(
            ThemeManager.getColorAttr(context, if (enabled) R.attr.zzSuccess else R.attr.zzMuted)
        )
    }

    private fun setupFloatingGuardSwitch(switchView: Switch) {
        val context = requireContext()
        val locked = TimeRuleEditWindowPolicyManager.isEditLocked(context)
        switchFloatingGuardUiUpdating = true
        switchView.isChecked = GuardFloatingWindowManager.isFloatingGuardEnabled(context)
        switchView.isEnabled = !locked
        switchView.isClickable = !locked
        switchView.alpha = if (locked) 0.5f else 1.0f
        switchFloatingGuardUiUpdating = false

        (switchView.parent as? View)?.setOnClickListener {
            if (TimeRuleEditWindowPolicyManager.isEditLocked(context)) {
                Toast.makeText(context, TimeRuleEditWindowPolicyManager.getLockoutHintText(context), Toast.LENGTH_SHORT).show()
            }
        }

        switchView.setOnCheckedChangeListener { _, isChecked ->
            if (switchFloatingGuardUiUpdating) return@setOnCheckedChangeListener
            if (TimeRuleEditWindowPolicyManager.isEditLocked(context)) {
                switchFloatingGuardUiUpdating = true
                switchView.isChecked = !isChecked
                switchFloatingGuardUiUpdating = false
                Toast.makeText(context, TimeRuleEditWindowPolicyManager.getLockoutHintText(context), Toast.LENGTH_SHORT).show()
                return@setOnCheckedChangeListener
            }
            if (isChecked) {
                if (!GuardFloatingWindowManager.canDrawOverlays(context)) {
                    switchFloatingGuardUiUpdating = true
                    switchView.isChecked = false
                    switchFloatingGuardUiUpdating = false
                    AlertDialog.Builder(context)
                        .setTitle("需要开启悬浮窗权限")
                        .setMessage("桌面守护小浮窗需要【显示在其他应用上层】权限。开启后将在屏幕边缘显示微型图标，防止后台多任务清理时服务被误杀。")
                        .setPositiveButton("前往开启") { _, _ ->
                            GuardFloatingWindowManager.requestOverlayPermission(context)
                        }
                        .setNegativeButton("取消", null)
                        .show()
                    return@setOnCheckedChangeListener
                }
                val prevState = GuardFloatingWindowManager.getProtectionState(context)
                GuardFloatingWindowManager.setFloatingGuardEnabled(context, true)
                if (prevState == GuardFloatingWindowManager.State.PENDING) {
                    Toast.makeText(context, "已取消关闭申请，桌面守护小浮窗保持开启", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(context, "已开启桌面守护小浮窗，防杀守护已生效", Toast.LENGTH_SHORT).show()
                }
                updateFloatingGuardUI()
            } else {
                val isImmediate = TimeRuleManager.isInAdaptationPeriod(context) || !TimeRuleManager.getBaselineFloatingGuardState(context)
                GuardFloatingWindowManager.setFloatingGuardEnabled(context, false)
                val message = if (isImmediate) {
                    if (TimeRuleManager.isInAdaptationPeriod(context)) "适应期内，桌面守护小浮窗已立即关闭"
                    else "桌面守护小浮窗已立即关闭（恢复基准）"
                } else {
                    "已申请关闭桌面守护小浮窗（明日生效）"
                }
                Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                switchFloatingGuardUiUpdating = true
                switchView.isChecked = false
                switchFloatingGuardUiUpdating = false
                updateFloatingGuardUI()
            }
        }
    }

    private fun updateFloatingGuardUI() {
        if (!isAdded) return
        val context = requireContext()
        val locked = TimeRuleEditWindowPolicyManager.isEditLocked(context)
        val state = GuardFloatingWindowManager.getProtectionState(context)
        switchFloatingGuardUiUpdating = true
        try {
            switchFloatingGuard?.isChecked = GuardFloatingWindowManager.isFloatingGuardEnabled(context)
            switchFloatingGuard?.isEnabled = !locked
            switchFloatingGuard?.isClickable = !locked
            switchFloatingGuard?.alpha = if (locked) 0.5f else 1.0f
        } finally {
            switchFloatingGuardUiUpdating = false
        }

        tvFloatingGuardStatus?.let { tv ->
            when (state) {
                GuardFloatingWindowManager.State.ACTIVE -> {
                    tv.text = "状态: 已开启"
                    tv.setTextColor(ThemeManager.getColorAttr(context, R.attr.zzSuccess))
                }
                GuardFloatingWindowManager.State.PENDING -> {
                    tv.text = "状态: 待关闭（明日生效）"
                    tv.setTextColor(ThemeManager.getColorAttr(context, R.attr.zzWarning))
                }
                GuardFloatingWindowManager.State.DISABLED -> {
                    tv.text = "状态: 未开启"
                    tv.setTextColor(ThemeManager.getColorAttr(context, R.attr.zzMuted))
                }
            }
        }
    }

    /**
     * 隐私空间拦截 (TASK-02): 规则页 UI 全程经 Manager,
     * 禁止 UI 直写 privacy_protection_prefs / KEY_IS_ENABLED / pending / baseline.
     */
    private fun setupPrivacySpaceGuardSwitch(switchView: Switch) {
        val context = requireContext()
        val locked = TimeRuleEditWindowPolicyManager.isEditLocked(context)
        // 初始化状态 (经 Manager, 严禁直读 prefs)
        val initialState = PrivacyProtectionManager.getProtectionState(context)
        val initialEnforcing = PrivacyProtectionManager.isEnforcing(context)
        switchPrivacySpaceGuardUiUpdating = true
        switchView.isChecked = computePrivacySpaceGuardSwitchChecked(initialState, initialEnforcing)
        switchView.isEnabled = !locked
        switchView.isClickable = !locked
        switchView.alpha = if (locked) 0.5f else 1.0f
        switchPrivacySpaceGuardUiUpdating = false

        (switchView.parent as? View)?.setOnClickListener {
            if (TimeRuleEditWindowPolicyManager.isEditLocked(context)) {
                Toast.makeText(context, TimeRuleEditWindowPolicyManager.getLockoutHintText(context), Toast.LENGTH_SHORT).show()
            }
        }

        switchView.setOnCheckedChangeListener { _, isChecked ->
            if (switchPrivacySpaceGuardUiUpdating) return@setOnCheckedChangeListener
            if (TimeRuleEditWindowPolicyManager.isEditLocked(context)) {
                switchPrivacySpaceGuardUiUpdating = true
                switchView.isChecked = !isChecked
                switchPrivacySpaceGuardUiUpdating = false
                Toast.makeText(context, TimeRuleEditWindowPolicyManager.getLockoutHintText(context), Toast.LENGTH_SHORT).show()
                return@setOnCheckedChangeListener
            }

            val now = System.currentTimeMillis()
            if (now - lastPrivacySpaceGuardSwitchClickTime < 1000) {
                switchPrivacySpaceGuardUiUpdating = true
                switchView.isChecked = !isChecked
                switchPrivacySpaceGuardUiUpdating = false
                return@setOnCheckedChangeListener
            }
            lastPrivacySpaceGuardSwitchClickTime = now

            if (isChecked) {
                // 开启必须无障碍已启用且非待关闭为前提
                if (!isA11yServiceReady(context)) {
                    switchPrivacySpaceGuardUiUpdating = true
                    switchView.isChecked = false
                    switchPrivacySpaceGuardUiUpdating = false
                    Toast.makeText(context, getString(R.string.a11y_service_not_ready_toast), Toast.LENGTH_SHORT).show()
                    return@setOnCheckedChangeListener
                }
                // 无障碍已开 → 立即开启 (经 Manager)
                PrivacyProtectionManager.setProtectionEnabled(context, true)
                Toast.makeText(context, getString(R.string.privacy_space_guard_toast_enabled), Toast.LENGTH_SHORT).show()
                updatePrivacySpaceGuardUI()
            } else {
                // 关闭: 对齐防关权限 — 直接提交次日关闭 + Toast (无弹窗)
                // 真机反馈: 之前复用了防卸载的 dialog_gentle_warning, 用户不需要温柔挽留.
                val isImmediate = TimeRuleManager.isInAdaptationPeriod(context) || !TimeRuleManager.getBaselinePrivacyProtectionState(context)
                PrivacyProtectionManager.setProtectionEnabled(context, false)
                val message = if (isImmediate) {
                    if (TimeRuleManager.isInAdaptationPeriod(context)) getString(R.string.privacy_space_guard_toast_adaptation_off)
                    else "隐私空间保护已立即关闭（恢复基准）"
                } else {
                    getString(R.string.privacy_space_guard_toast_pending)
                }
                Toast.makeText(context, message, if (isImmediate) Toast.LENGTH_SHORT else Toast.LENGTH_LONG).show()
                // 同步把开关刷为 unchecked (PENDING 视觉)
                switchPrivacySpaceGuardUiUpdating = true
                switchView.isChecked = false
                switchPrivacySpaceGuardUiUpdating = false
                updatePrivacySpaceGuardUI()
            }
        }
    }

    private fun updatePrivacySpaceGuardUI() {
        if (!isAdded) return
        val context = requireContext()
        val locked = TimeRuleEditWindowPolicyManager.isEditLocked(context)
        // 全程经 Manager
        val state = PrivacyProtectionManager.getProtectionState(context)
        val enforcing = PrivacyProtectionManager.isEnforcing(context)

        val isVivo = SettingsNavigator.isVivoOrIqoo()
        val visible = PrivacyProtectionManager.resolvePrivacySpaceUiVisibility(isVivo, state, enforcing)
        layoutPrivacySpaceGuard?.visibility = if (visible) View.VISIBLE else View.GONE
        dividerPrivacySpaceGuard?.visibility = if (visible) View.VISIBLE else View.GONE
        if (!visible) return

        switchPrivacySpaceGuardUiUpdating = true
        switchPrivacySpaceGuard?.isChecked = computePrivacySpaceGuardSwitchChecked(state, enforcing)
        switchPrivacySpaceGuard?.isEnabled = !locked
        switchPrivacySpaceGuard?.isClickable = !locked
        switchPrivacySpaceGuard?.alpha = if (locked) 0.5f else 1.0f
        switchPrivacySpaceGuardUiUpdating = false

        tvPrivacySpaceGuardStatus?.let {
            val statusText = computePrivacySpaceGuardUiText(state, enforcing)
            it.text = "状态: $statusText"
            // 颜色随状态变化, 保持对比度
            val color = when {
                state == PrivacyProtectionManager.State.PENDING -> ThemeManager.getColorAttr(context, R.attr.zzWarning)
                enforcing -> ThemeManager.getColorAttr(context, R.attr.zzSuccess)
                else -> ThemeManager.getColorAttr(context, R.attr.zzMuted)
            }
            it.setTextColor(color)
        }
    }

    /**
     * 隐私空间拦截开启引导弹窗 — 提示先开启无障碍服务。
     */
    private fun showPrivacySpaceGuardEnableGuideDialog() {
        val ctx = requireContext()
        val density = ctx.resources.displayMetrics.density

        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((24 * density).toInt(), (20 * density).toInt(), (24 * density).toInt(), (20 * density).toInt())
        }

        val tvTitle = TextView(ctx).apply {
            text = getString(R.string.privacy_space_guard_enable_guide_title)
            textSize = 18f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(ThemeManager.getColorAttr(ctx, R.attr.zzText))
        }
        container.addView(tvTitle)

        val tvMessage = TextView(ctx).apply {
            textSize = 14f
            setPadding(0, (14 * density).toInt(), 0, 0)
            setTextColor(ThemeManager.getColorAttr(ctx, R.attr.zzMuted))
            setLineSpacing(0f, 1.25f)
            text = getString(R.string.privacy_space_guard_enable_guide_body)
        }
        container.addView(tvMessage)

        val buttonRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, (20 * density).toInt(), 0, 0)
            gravity = Gravity.CENTER_VERTICAL
        }

        val btnCancel = com.google.android.material.button.MaterialButton(ctx).apply {
            text = "取消"
            setAllCaps(false)
            setTextColor(ThemeManager.getColorAttr(ctx, R.attr.zzText))
            backgroundTintList = android.content.res.ColorStateList.valueOf(ThemeManager.getColorAttr(ctx, R.attr.zzSurface2))
            cornerRadius = (12 * density).toInt()
            minHeight = (44 * density).toInt()
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(0, 0, (8 * density).toInt(), 0)
            }
        }

        val btnOpenA11y = com.google.android.material.button.MaterialButton(ctx).apply {
            text = getString(R.string.privacy_space_guard_enable_guide_open_a11y)
            setAllCaps(false)
            setTextColor(android.graphics.Color.WHITE)
            backgroundTintList = android.content.res.ColorStateList.valueOf(ThemeManager.getColorAttr(ctx, R.attr.zzAccent))
            cornerRadius = (12 * density).toInt()
            minHeight = (44 * density).toInt()
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        buttonRow.addView(btnCancel)
        buttonRow.addView(btnOpenA11y)
        container.addView(buttonRow)

        val dialog = AlertDialog.Builder(ctx)
            .setView(container)
            .create()

        btnCancel.setOnClickListener { dialog.dismiss() }
        btnOpenA11y.setOnClickListener {
            dialog.dismiss()
            openAccessibilitySettings()
        }

        dialog.show()
    }

    /** 打开系统无障碍设置 (用于隐私空间拦截开启引导) */
    private fun openAccessibilitySettings() {
        try {
            RecentTasksHideManager.markNavigating()
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            startActivity(intent)
        } catch (e: Exception) {
            RecentTasksHideManager.clearNavigating()
            Toast.makeText(requireContext(), "无法打开无障碍设置", Toast.LENGTH_SHORT).show()
        }
    }

    private fun applyA11yDisableRequest(ctx: Context): Boolean {
        val immediate = TimeRuleManager.isInAdaptationPeriod(ctx) || !TimeRuleManager.getBaselineA11yServiceState(ctx)
        AccessibilityControlManager.setEnabled(ctx, false)
        return immediate
    }

    private fun openAppDetailsSettings() {
        try {
            RecentTasksHideManager.markNavigating()
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${requireContext().packageName}"))
            startActivity(intent)
        } catch (e: Exception) {
            RecentTasksHideManager.clearNavigating()
            Toast.makeText(requireContext(), "无法打开应用设置", Toast.LENGTH_SHORT).show()
        }
    }

    private fun isAccessibilityServiceEnabled(ctx: Context? = context): Boolean {
        val target = ctx ?: context ?: return false
        val enabledServices = Settings.Secure.getString(target.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        return enabledServices?.contains(target.packageName) == true
    }

    internal fun isA11yPendingDisable(ctx: Context): Boolean {
        return AccessibilityControlManager.getState(ctx) == AccessibilityControlManager.State.PENDING
    }

    internal fun setA11yPendingDisable(ctx: Context, pending: Boolean) {
        if (pending) {
            AccessibilityControlManager.setEnabled(ctx, false)
        } else {
            AccessibilityControlManager.setEnabled(ctx, true)
        }
    }

    internal fun isA11yServiceReady(ctx: Context? = context): Boolean {
        val target = ctx ?: context ?: return false
        return isA11yReady(isAccessibilityServiceEnabled(target), isA11yPendingDisable(target))
    }

    private fun updateDeviceAdminProtectionUI() {
        val ctx = context ?: return
        val isDeviceAdminRecommended = com.decikeeper.app.oem.OemManager.current.isDeviceAdminRecommended()
        val state = AntiUninstallManager.getProtectionState(ctx)
        val isSystemAdminActive = AntiUninstallManager.isDeviceAdminActive(ctx)
        val uiState = AntiUninstallManager.resolveDeviceAdminUiState(
            isSupportedBrand = isDeviceAdminRecommended,
            protectionState = state,
            isSystemAdminActive = isSystemAdminActive
        )
        if (!uiState.visible) {
            layoutDeviceAdminProtection?.visibility = View.GONE
            dividerDeviceAdmin?.visibility = View.GONE
            return
        }

        layoutDeviceAdminProtection?.visibility = View.VISIBLE
        dividerDeviceAdmin?.visibility = View.VISIBLE

        val locked = TimeRuleEditWindowPolicyManager.isEditLocked(ctx)

        switchDeviceAdminProtectionUiUpdating = true
        switchDeviceAdminProtection?.isChecked = uiState.checked
        switchDeviceAdminProtection?.isEnabled = !locked
        switchDeviceAdminProtection?.isClickable = !locked
        switchDeviceAdminProtection?.alpha = if (locked) 0.5f else 1.0f
        switchDeviceAdminProtectionUiUpdating = false

        tvDeviceAdminProtectionStatus?.let {
            val statusText = when (uiState.status) {
                AntiUninstallManager.DeviceAdminUiStatus.PENDING -> "状态: 待关闭（明日生效）"
                AntiUninstallManager.DeviceAdminUiStatus.ACTIVE -> {
                    "状态: 已激活且受保护（OPPO/适配机型已底层防卸载）"
                }
                AntiUninstallManager.DeviceAdminUiStatus.NEEDS_ACTIVATION -> {
                    "状态: 已开启保护（系统未激活，点击开启）"
                }
                AntiUninstallManager.DeviceAdminUiStatus.NEEDS_DEACTIVATION -> {
                    "状态: 已到期可解除（点击前往系统界面取消激活）"
                }
                AntiUninstallManager.DeviceAdminUiStatus.DISABLED -> {
                    "状态: 未激活（点击跳转系统界面完成激活）"
                }
                AntiUninstallManager.DeviceAdminUiStatus.HIDDEN -> return@let
            }
            it.text = statusText
            val color = when (uiState.status) {
                AntiUninstallManager.DeviceAdminUiStatus.PENDING -> ThemeManager.getColorAttr(ctx, R.attr.zzWarning)
                AntiUninstallManager.DeviceAdminUiStatus.ACTIVE -> ThemeManager.getColorAttr(ctx, R.attr.zzSuccess)
                AntiUninstallManager.DeviceAdminUiStatus.NEEDS_ACTIVATION -> ThemeManager.getColorAttr(ctx, R.attr.zzWarning)
                AntiUninstallManager.DeviceAdminUiStatus.NEEDS_DEACTIVATION,
                AntiUninstallManager.DeviceAdminUiStatus.DISABLED,
                AntiUninstallManager.DeviceAdminUiStatus.HIDDEN -> ThemeManager.getColorAttr(ctx, R.attr.zzMuted)
            }
            it.setTextColor(color)
        }
    }

    private fun setupDeviceAdminProtectionSwitch(switchView: Switch) {
        val ctx = requireContext()
        (switchView.parent as? View)?.setOnClickListener {
            if (TimeRuleEditWindowPolicyManager.isEditLocked(ctx)) {
                Toast.makeText(ctx, TimeRuleEditWindowPolicyManager.getLockoutHintText(ctx), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val state = AntiUninstallManager.getProtectionState(ctx)
            val isSystemAdminActive = AntiUninstallManager.isDeviceAdminActive(ctx)
            if (state == AntiUninstallManager.State.DISABLED && isSystemAdminActive) {
                AlertDialog.Builder(ctx)
                    .setTitle("取消激活设备管理器")
                    .setMessage("保护已到期解除。现可前往系统设置页面手动取消激活「决定守护」的设备管理器。")
                    .setPositiveButton("前往系统设置") { _, _ ->
                        try {
                            RecentTasksHideManager.markNavigating()
                            startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS))
                        } catch (_: Exception) {
                            RecentTasksHideManager.clearNavigating()
                        }
                    }
                    .setNegativeButton("取消", null)
                    .show()
            }
        }

        switchView.setOnCheckedChangeListener { _, isChecked ->
            if (switchDeviceAdminProtectionUiUpdating) return@setOnCheckedChangeListener

            val now = android.os.SystemClock.uptimeMillis()
            if (now - lastDeviceAdminSwitchClickTime < 600L) {
                updateDeviceAdminProtectionUI()
                return@setOnCheckedChangeListener
            }
            lastDeviceAdminSwitchClickTime = now

            if (TimeRuleEditWindowPolicyManager.isEditLocked(ctx)) {
                updateDeviceAdminProtectionUI()
                Toast.makeText(ctx, TimeRuleEditWindowPolicyManager.getLockoutHintText(ctx), Toast.LENGTH_SHORT).show()
                return@setOnCheckedChangeListener
            }

            val state = AntiUninstallManager.getProtectionState(ctx)
            val isSystemAdminActive = AntiUninstallManager.isDeviceAdminActive(ctx)

            if (isChecked) {
                if (state == AntiUninstallManager.State.PENDING) {
                    AntiUninstallManager.setProtectionEnabled(ctx, true)
                    Toast.makeText(ctx, "已取消关闭申请，设备管理器防卸载锁保持开启", Toast.LENGTH_SHORT).show()
                    updateDeviceAdminProtectionUI()
                    return@setOnCheckedChangeListener
                }

                AntiUninstallManager.setProtectionEnabled(ctx, true)
                if (!isSystemAdminActive) {
                    try {
                        RecentTasksHideManager.markNavigating()
                        startActivity(AntiUninstallManager.getDeviceAdminIntent(ctx))
                        Toast.makeText(ctx, "请在系统对话框中点击「激活」", Toast.LENGTH_LONG).show()
                    } catch (e: Exception) {
                        RecentTasksHideManager.clearNavigating()
                        Toast.makeText(ctx, "无法打开系统设备管理器页面", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    Toast.makeText(ctx, "设备管理器防卸载锁已开启", Toast.LENGTH_SHORT).show()
                }
                updateDeviceAdminProtectionUI()
            } else {
                if (state == AntiUninstallManager.State.ACTIVE) {
                    AntiUninstallManager.setProtectionEnabled(ctx, false)
                    val isAdaptation = TimeRuleManager.isInAdaptationPeriod(ctx)
                    if (isAdaptation) {
                        Toast.makeText(ctx, "适应期内，设备管理器防卸载锁已立即关闭", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(ctx, "已申请关闭设备管理器防卸载锁（明日生效）", Toast.LENGTH_LONG).show()
                    }
                    updateDeviceAdminProtectionUI()
                } else if (state == AntiUninstallManager.State.PENDING) {
                    // PENDING 仍然是保护态；取消申请必须通过重新勾选恢复 ACTIVE。
                    updateDeviceAdminProtectionUI()
                    Toast.makeText(ctx, "设备管理器防卸载锁仍在保护中，关闭申请将在明日生效", Toast.LENGTH_LONG).show()
                } else if (state == AntiUninstallManager.State.DISABLED && isSystemAdminActive) {
                    updateDeviceAdminProtectionUI()
                    AlertDialog.Builder(ctx)
                        .setTitle("取消激活设备管理器")
                        .setMessage("保护已到期解除。现可前往系统设置页面手动取消激活「决定守护」的设备管理器。")
                        .setPositiveButton("前往系统设置") { _, _ ->
                            try {
                                RecentTasksHideManager.markNavigating()
                                startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS))
                            } catch (_: Exception) {
                                RecentTasksHideManager.clearNavigating()
                            }
                        }
                        .setNegativeButton("取消", null)
                        .show()
                } else {
                    updateDeviceAdminProtectionUI()
                }
            }
        }
    }
}
