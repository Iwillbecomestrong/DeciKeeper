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
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.decikeeper.app.R
import com.decikeeper.app.data.LogExporter
import com.decikeeper.app.manager.AccessibilityControlManager
import com.decikeeper.app.manager.AlarmPowerOffProtectionManager
import com.decikeeper.app.manager.AntiUninstallManager
import com.decikeeper.app.manager.ConfigPendingManager
import com.decikeeper.app.manager.CreditManager
import com.decikeeper.app.manager.ManualPermissionRecoveryManager
import com.decikeeper.app.manager.PrivacyProtectionManager
import com.decikeeper.app.manager.RecentTasksHideManager
import com.decikeeper.app.manager.RomPermissionDetector
import com.decikeeper.app.manager.SettingsBackupManager
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
         * - DISABLED → "已关闭"
         * - ACTIVE → "已生效"
         * - PENDING → "次日生效,当前仍拦截" (强制从 isEnforcing 推出)
         */
        internal fun computePrivacySpaceGuardUiText(
            state: PrivacyProtectionManager.State,
            isEnforcing: Boolean
        ): String {
            // 必须基于 isEnforcing 判断 PENDING 期是否仍拦截, 不得仅用 state 推导
            return when {
                state == PrivacyProtectionManager.State.PENDING && isEnforcing ->
                    "次日生效,当前仍拦截"
                state == PrivacyProtectionManager.State.ACTIVE -> "已生效"
                else -> "已关闭"
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
        val lockScreenReady: Boolean,
        val a11yReady: Boolean
    ) {
        val allReady: Boolean
            get() = lockScreenReady && a11yReady
    }

    private var switchUninstall: Switch? = null
    private var tvUninstallStatus: TextView? = null
    private var switchPermProtection: Switch? = null
    private var tvPermProtectionStatus: TextView? = null
    private var tvAccessibilityStatus: TextView? = null
    private var switchAccessibilityService: Switch? = null
    private var switchAlarmPowerOffProtection: Switch? = null
    private var tvAlarmPowerOffProtectionStatus: TextView? = null
    private var switchHideInRecentTasks: Switch? = null
    private var switchHideInRecentTasksUiUpdating = false
    private var switchPrivacySpaceGuard: Switch? = null
    private var tvPrivacySpaceGuardStatus: TextView? = null
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

    // Anti-Uninstall Debounce
    private var lastSwitchClickTime = 0L
    private var lastPermProtectionSwitchClickTime = 0L
    private var lastA11yServiceSwitchClickTime = 0L
    private var lastPrivacySpaceGuardSwitchClickTime = 0L
    private var switchUninstallUiUpdating = false
    private var switchPermProtectionUiUpdating = false
    private var switchA11yServiceUiUpdating = false
    private var switchAlarmPowerOffUiUpdating = false
    private var switchPrivacySpaceGuardUiUpdating = false
    private var pendingAskAlarmPowerOffAfterAccessibility = false

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
        updateAntiUninstallUI()
        updatePermProtectionUI()
        updateAccessibilityServiceUI()
        updateAlarmPowerOffProtectionUI()
        updateHideInRecentTasksUI()
        updatePrivacySpaceGuardUI()

        // After returning from accessibility settings, ask about 闹钟防关机保护
        if (pendingAskAlarmPowerOffAfterAccessibility) {
            pendingAskAlarmPowerOffAfterAccessibility = false
            if (isAdded && isAccessibilityServiceEnabled() &&
                !AlarmPowerOffProtectionManager.isEnabled(requireContext())
            ) {
                showEnableAlarmPowerOffSuggestionDialog()
            }
        }

        // Loop check for anti-uninstall status
        view?.let { triggerAntiUninstallStatusLoop(it) }
    }

    private fun initViews(view: View) {
        switchUninstall = view.findViewById(R.id.switch_uninstall_protection)
        tvUninstallStatus = view.findViewById(R.id.tv_uninstall_status_main)
        switchPermProtection = view.findViewById(R.id.switch_accessibility)
        tvPermProtectionStatus = view.findViewById(R.id.tv_perm_protection_status)
        tvAccessibilityStatus = view.findViewById(R.id.tv_accessibility_status_main)
        switchAlarmPowerOffProtection = view.findViewById(R.id.switch_alarm_power_off_protection)
        tvAlarmPowerOffProtectionStatus = view.findViewById(R.id.tv_alarm_power_off_status_main)
        switchHideInRecentTasks = view.findViewById(R.id.switch_hide_in_recent_tasks)
        switchAccessibilityService = view.findViewById(R.id.switch_accessibility_service)
        switchPrivacySpaceGuard = view.findViewById(R.id.switch_privacy_space_guard)
        tvPrivacySpaceGuardStatus = view.findViewById(R.id.tv_privacy_space_guard_status)
        btnQuickSendLogs = view.findViewById(R.id.btn_quick_send_logs)
        btnExportSettings = view.findViewById(R.id.btn_export_settings)
        btnImportSettings = view.findViewById(R.id.btn_import_settings)

        // Setup Anti-Uninstall Switch
        switchUninstall?.let { setupAntiUninstallSwitch(it) }

        // Setup Accessibility Switch
        switchPermProtection?.let { setupPermProtectionSwitch(it) }

        switchAlarmPowerOffProtection?.let { setupAlarmPowerOffProtectionSwitch(it) }

        switchHideInRecentTasks?.let { setupHideInRecentTasksSwitch(it) }

        view.findViewById<View>(R.id.btn_hide_in_recent_tasks_help)?.setOnClickListener {
            androidx.appcompat.app.AlertDialog.Builder(requireContext())
                .setTitle("隐藏决定守护在多任务界面")
                .setMessage(
                    "防止用户划掉多进程时不小心误划决定守护。\n\n" +
                    "开关开启后,只要你不打开决定守护软件本身," +
                    "在其它软件打开任务界面时决定守护就不会出现在最近任务列表里。"
                )
                .setPositiveButton("知道了", null)
                .show()
        }

        switchAccessibilityService?.let { setupAccessibilityServiceSwitch(it) }

        switchPrivacySpaceGuard?.let { setupPrivacySpaceGuardSwitch(it) }

    }

    private fun setupClickEvents(view: View) {
        view.findViewById<Button>(R.id.btn_select_apps)?.setOnClickListener {
            startActivity(Intent(requireContext(), AppSelectionActivity::class.java))
        }
        view.findViewById<Button>(R.id.btn_global_params_rules)?.setOnClickListener {
            startActivity(Intent(requireContext(), GlobalParamsActivity::class.java))
        }
        // REQ-5: 设置 → 关于我们
        view.findViewById<Button>(R.id.btn_open_about)?.setOnClickListener {
            startActivity(Intent(requireContext(), AboutActivity::class.java))
        }
        btnQuickSendLogs?.setOnClickListener {
            LogExporter.showQuickSendLogOptions(requireContext(), "导出日志", "请选择要导出的时间范围")
        }
        btnExportSettings?.setOnClickListener {
            showExportCategoryDialog()
        }
        btnImportSettings?.setOnClickListener {
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
                updateAntiUninstallUI()
                updatePermProtectionUI()
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

    private fun setupAntiUninstallSwitch(switchView: Switch) {
        val context = requireContext()
        val state = AntiUninstallManager.getProtectionState(context)

        // Init State
        switchView.isChecked = (state == AntiUninstallManager.State.ACTIVE || state == AntiUninstallManager.State.PENDING)

        switchView.setOnCheckedChangeListener { _, isChecked ->
            if (switchUninstallUiUpdating) return@setOnCheckedChangeListener

            // 🚀 [Debounce] Ignore rapid toggles within 1 second
            val now = System.currentTimeMillis()
            if (now - lastSwitchClickTime < 1000) {
                switchUninstallUiUpdating = true
                switchView.isChecked = !isChecked
                switchUninstallUiUpdating = false
                return@setOnCheckedChangeListener
            }
            lastSwitchClickTime = now

            if (isChecked) {
                if (!isA11yServiceReady(context)) {
                    switchUninstallUiUpdating = true
                    switchView.isChecked = false
                    switchUninstallUiUpdating = false
                    Toast.makeText(context, getString(R.string.a11y_service_not_ready_toast), Toast.LENGTH_SHORT).show()
                    return@setOnCheckedChangeListener
                }
                AntiUninstallManager.setProtectionEnabled(context, true)
                Toast.makeText(context, "防卸载保护已开启", Toast.LENGTH_SHORT).show()
                updateAntiUninstallUI()
                maybeAskBgBatteryPermissionAfterAntiUninstallEnabled()
            } else {
                if (TimeRuleManager.isInAdaptationPeriod(context)) {
                    AntiUninstallManager.setProtectionEnabled(context, false)
                    Toast.makeText(context, "适应期内，保护已立即关闭", Toast.LENGTH_SHORT).show()
                    updateAntiUninstallUI()
                } else if (AccessibilityControlManager.getState(context) != AccessibilityControlManager.State.ACTIVE) {
                    // 防关权限保护未开启，次日生效无意义 → 立即关闭
                    AntiUninstallManager.setProtectionEnabled(context, false)
                    Toast.makeText(context, "防卸载保护已关闭", Toast.LENGTH_SHORT).show()
                    updateAntiUninstallUI()
                } else {
                    switchUninstallUiUpdating = true
                    switchView.isChecked = true
                    switchUninstallUiUpdating = false

                    val dialogView = LayoutInflater.from(context).inflate(R.layout.dialog_gentle_warning, null)
                    val tvMessage = dialogView.findViewById<com.decikeeper.app.ui.TypewriterTextView>(R.id.tv_gentle_message)
                    val btnConfirm = dialogView.findViewById<Button>(R.id.btn_confirm)
                    val btnCancel = dialogView.findViewById<Button>(R.id.btn_cancel)

                    val dialog = AlertDialog.Builder(context)
                        .setView(dialogView)
                        .setCancelable(false)
                        .create()

                    dialog.show()

                    val message = "哪怕只有一分钟，你因为我而放下了手机、看了一眼身边的世界，我的存在就有了意义。"
                    tvMessage.animateText(message, 50)

                    btnCancel.setOnClickListener {
                        dialog.dismiss()
                    }

                    btnConfirm.setOnClickListener {
                        AntiUninstallManager.setProtectionEnabled(context, false)
                        updateAntiUninstallUI()
                        Toast.makeText(context, "已申请关闭防卸载（明日生效），可立即继续申请关闭无障碍", Toast.LENGTH_LONG).show()
                        switchUninstallUiUpdating = true
                        switchView.isChecked = false
                        switchUninstallUiUpdating = false
                        dialog.dismiss()
                    }
                }
            }
        }
    }

    private fun setupPermProtectionSwitch(switchView: Switch) {
        val context = requireContext()
        val currentState = AccessibilityControlManager.getState(context)
        val systemEnabled = isAccessibilityServiceEnabled()
        switchView.isChecked = currentState == AccessibilityControlManager.State.ACTIVE && systemEnabled

        switchView.setOnCheckedChangeListener { _, isChecked ->
            if (switchPermProtectionUiUpdating) return@setOnCheckedChangeListener

            val now = System.currentTimeMillis()
            if (now - lastPermProtectionSwitchClickTime < 1000) {
                switchPermProtectionUiUpdating = true
                switchView.isChecked = !isChecked
                switchPermProtectionUiUpdating = false
                return@setOnCheckedChangeListener
            }
            lastPermProtectionSwitchClickTime = now

            if (isChecked) {
                if (!isA11yServiceReady(context)) {
                    switchPermProtectionUiUpdating = true
                    switchView.isChecked = false
                    switchPermProtectionUiUpdating = false
                    Toast.makeText(context, getString(R.string.a11y_service_not_ready_toast), Toast.LENGTH_SHORT).show()
                    return@setOnCheckedChangeListener
                }
                // 防关权限保护：直接开启，不弹引导弹窗（无障碍未开 Toast 提示）
                AccessibilityControlManager.setEnabled(context, true)
                Toast.makeText(context, "防关权限保护已开启", Toast.LENGTH_SHORT).show()
                updatePermProtectionUI()
            } else {
                AccessibilityControlManager.setEnabled(context, false)
                Toast.makeText(context, "已申请关闭防关权限保护（明日生效，可与防卸载同次提交）", Toast.LENGTH_LONG).show()
                switchPermProtectionUiUpdating = true
                switchView.isChecked = false
                switchPermProtectionUiUpdating = false
                updatePermProtectionUI()
            }
        }
    }

    /**
     * 无障碍服务 Switch — 开启时弹窗引导补齐基础权限+后台高耗电+关联启动，然后开启无障碍服务
     */
    private fun setupAccessibilityServiceSwitch(switchView: Switch) {
        val context = requireContext()
        val systemEnabled = isAccessibilityServiceEnabled()
        val pendingDisable = isA11yPendingDisable(context)
        switchA11yServiceUiUpdating = true
        // 待关闭状态也显示为 unchecked
        switchView.isChecked = systemEnabled && !pendingDisable
        switchA11yServiceUiUpdating = false

        switchView.setOnCheckedChangeListener { _, isChecked ->
            if (switchA11yServiceUiUpdating) return@setOnCheckedChangeListener

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
                    // 已开启，检查是否有待关闭标记需要清除
                    if (isA11yPendingDisable(context)) {
                        setA11yPendingDisable(context, false)
                        updateAccessibilityServiceUI()
                        Toast.makeText(context, "已取消关闭申请", Toast.LENGTH_SHORT).show()
                    }
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
                if (AccessibilityControlManager.getState(context) == AccessibilityControlManager.State.ACTIVE) {
                    showA11yDisableWithProtectionDialog(switchView)
                } else {
                    showA11yDisableConfirmDialog(switchView)
                }
            }
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

        val tvMessage = TextView(ctx).apply {
            textSize = 14f
            setPadding(0, (14 * density).toInt(), 0, 0)
            setTextColor(ThemeManager.getColorAttr(ctx, R.attr.zzMuted))
            setLineSpacing(0f, 1.25f)
            text = "关闭无障碍服务后，以下保护功能将一并停用：\n\n" +
                "  • 防卸载保护 → 提交次日关闭申请\n" +
                "  • 防关权限保护 → 提交次日关闭申请\n" +
                "  • 闹钟防关机保护 → 立即关闭\n" +
                a11yCollaborativeDisableDialogItem(
                    isEnforcing = PrivacyProtectionManager.isEnforcing(ctx),
                    isPending = PrivacyProtectionManager.getProtectionState(ctx) == PrivacyProtectionManager.State.PENDING
                ) + "\n\n" +
                "关闭流程：\n" +
                "1. 点击「确认关闭」提交以上所有关闭申请\n" +
                "2. 除闹钟防关机保护立即生效外，其余次日生效\n" +
                "3. 生效后，可前往系统无障碍设置中关闭决定守护无障碍服务\n\n" +
                "是否确认同时提交所有关闭申请？"
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
            text = "确认关闭"
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
            // 防卸载保护 → 提交次日关闭申请（如果当前开启）
            if (AntiUninstallManager.getProtectionState(ctx) == AntiUninstallManager.State.ACTIVE) {
                AntiUninstallManager.setProtectionEnabled(ctx, false)
            }
            // 防关权限保护 → 提交次日关闭申请（如果当前开启）
            if (AccessibilityControlManager.getState(ctx) == AccessibilityControlManager.State.ACTIVE) {
                AccessibilityControlManager.setEnabled(ctx, false)
            }
            // 闹钟防关机保护 → 立即关闭
            if (AlarmPowerOffProtectionManager.isEnabled(ctx)) {
                AlarmPowerOffProtectionManager.setEnabled(ctx, false)
            }
            // 隐私空间拦截 → 提交次日关闭申请（仅 ACTIVE 时, 对齐防卸载 if ACTIVE 写法）
            // PENDING 不重复提交（避免重置到期时间）, DISABLED 无操作
            if (PrivacyProtectionManager.getProtectionState(ctx) == PrivacyProtectionManager.State.ACTIVE) {
                PrivacyProtectionManager.setProtectionEnabled(ctx, false)
            }
            // 无障碍服务 → 提交次日关闭申请
            ConfigPendingManager.submitChange(ctx, "a11y_service_self_pending", "true", strictNextDay = true)
            setA11yPendingDisable(ctx, true)
            switchA11yServiceUiUpdating = true
            switchView.isChecked = false
            switchA11yServiceUiUpdating = false
            updateAccessibilityServiceUI()
            updateAntiUninstallUI()
            updatePermProtectionUI()
            updateAlarmPowerOffProtectionUI()
            updatePrivacySpaceGuardUI()
            Toast.makeText(ctx, "已同时提交关闭申请（闹钟防关机已立即关闭，其余次日生效）", Toast.LENGTH_LONG).show()
            dialog.dismiss()
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

        val tvMessage = TextView(ctx).apply {
            textSize = 14f
            setPadding(0, (14 * density).toInt(), 0, 0)
            setTextColor(ThemeManager.getColorAttr(ctx, R.attr.zzMuted))
            setLineSpacing(0f, 1.25f)
            text = "「防关权限保护」正在保护中，它会阻止对无障碍服务的关闭操作。\n\n" +
                "将同时提交以下功能的关闭申请：\n" +
                "  • 防卸载保护 → 次日生效（如果已开启）\n" +
                "  • 防关权限保护 → 次日生效\n" +
                "  • 闹钟防关机保护 → 立即关闭\n" +
                a11yCollaborativeDisableDialogItem(
                    isEnforcing = PrivacyProtectionManager.isEnforcing(ctx),
                    isPending = PrivacyProtectionManager.getProtectionState(ctx) == PrivacyProtectionManager.State.PENDING
                ) + "\n" +
                "  • 无障碍服务 → 次日生效\n\n" +
                "是否确认同时提交？"
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
            text = "同时提交关闭"
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
            // 防卸载保护 → 提交次日关闭申请（如果当前开启）
            if (AntiUninstallManager.getProtectionState(ctx) == AntiUninstallManager.State.ACTIVE) {
                AntiUninstallManager.setProtectionEnabled(ctx, false)
            }
            // 防关权限保护 → 提交次日关闭申请
            AccessibilityControlManager.setEnabled(ctx, false)
            // 闹钟防关机保护 → 立即关闭
            if (AlarmPowerOffProtectionManager.isEnabled(ctx)) {
                AlarmPowerOffProtectionManager.setEnabled(ctx, false)
            }
            // 隐私空间拦截 → 提交次日关闭申请（仅 ACTIVE 时, 对齐防卸载 if ACTIVE 写法）
            // PENDING 不重复提交（避免重置到期时间）, DISABLED 无操作
            if (PrivacyProtectionManager.getProtectionState(ctx) == PrivacyProtectionManager.State.ACTIVE) {
                PrivacyProtectionManager.setProtectionEnabled(ctx, false)
            }
            // 无障碍服务 → 提交次日关闭申请
            ConfigPendingManager.submitChange(ctx, "a11y_service_self_pending", "true", strictNextDay = true)
            setA11yPendingDisable(ctx, true)
            switchA11yServiceUiUpdating = true
            switchView.isChecked = false
            switchA11yServiceUiUpdating = false
            updateAccessibilityServiceUI()
            updateAntiUninstallUI()
            updatePermProtectionUI()
            updateAlarmPowerOffProtectionUI()
            updatePrivacySpaceGuardUI()
            Toast.makeText(ctx, "已同时提交关闭申请（闹钟防关机已立即关闭，其余次日生效）", Toast.LENGTH_LONG).show()
            dialog.dismiss()
        }

        dialog.show()
    }

    /**
     * 无障碍服务引导弹窗 — 检查基础权限+后台高耗电+关联启动，然后引导开启无障碍
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
        val btnBgBattery = buildButton("允许后台耗电")
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
        var cbBgBattery: android.widget.CheckBox? = null
        var cbAssocStart: android.widget.CheckBox? = null

        val prefs = ctx.getSharedPreferences(CreditManager.PREFS_NAME, Context.MODE_PRIVATE)

        fun updateDialogUi() {
            val permState = getHomePermissionState(ctx)
            val isChineseRom = isChineseRomDevice()
            val bgBatteryReady = !isChineseRom || isBgBatteryPermissionSatisfied(ctx)
            val assocStartReady = !isChineseRom || isAssocStartPermissionSatisfied(ctx)
            val baseOk = permState.allGranted
            val bgBatteryConfirmed = prefs.getBoolean("bg_battery_permission_confirmed", false)
            val assocStartConfirmed = prefs.getBoolean(ASSOC_START_CONFIRMED_KEY, false)

            tvMessage.text = buildString {
                append("开启无障碍服务后，可启用以下保护功能：\n")
                append("  • 防卸载保护\n")
                append("  • 防关权限保护\n")
                append("  • 闹钟防关机保护\n\n")
                append("请先补齐以下前置权限：\n\n")
                if (!baseOk) {
                    append("基础权限未齐全：${permState.missingLabels.joinToString("、")}\n")
                }
                if (!bgBatteryReady && !bgBatteryConfirmed) {
                    append("允许后台耗电未开启（非国产ROM可忽略）\n")
                }
                if (!assocStartReady && !assocStartConfirmed) {
                    append("关联启动未开启（非国产ROM可忽略）\n")
                }
                if (baseOk && (bgBatteryReady || bgBatteryConfirmed) && (assocStartReady || assocStartConfirmed)) {
                    append("\n所有前置权限已齐全，可开启无障碍。")
                }
            }

            btnBase.text = if (baseOk) "基础权限已齐全" else "去补齐基础权限"
            btnBase.isEnabled = !baseOk
            btnBase.alpha = if (btnBase.isEnabled) 1.0f else 0.45f

            // 允许后台耗电：按钮+勾选框状态同步
            // [Fix] 不禁用控件，改用 alpha 表示状态。禁用状态下长按/取消勾选不触发触摸事件。
            if (bgBatteryConfirmed) {
                cbBgBattery!!.isChecked = true
                cbBgBattery!!.alpha = 0.6f
                btnBgBattery.text = "允许后台耗电已开启（长按清除）"
                btnBgBattery.alpha = 0.6f
            } else {
                cbBgBattery!!.isChecked = false
                cbBgBattery!!.alpha = 1.0f
                btnBgBattery.text = if (bgBatteryReady) "允许后台耗电已开启" else "去开启允许后台耗电"
                btnBgBattery.alpha = if (bgBatteryReady) 0.6f else 1.0f
            }

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

            val allReady = baseOk && (bgBatteryReady || bgBatteryConfirmed) && (assocStartReady || assocStartConfirmed)
            btnEnable.isEnabled = allReady
            btnEnable.text = if (allReady) "开启无障碍" else "请先补齐以上权限"

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
            if (!isBgBatteryPermissionSatisfied(ctx)) {
                openBgBatterySettings()
                dialog?.dismiss()
            }
        }

        btnBgBattery.setOnLongClickListener {
            prefs.edit()
                .remove("bg_battery_permission_confirmed")
                .remove("bg_battery_permission_confirmed_at")
                .apply()
            updateDialogUi()
            Toast.makeText(ctx, "已清除后台耗电确认状态，可重新检测", Toast.LENGTH_SHORT).show()
            true
        }

        btnAssocStart.setOnClickListener {
            if (!isAssocStartPermissionSatisfied(ctx)) {
                try {
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
                .remove(AUTO_START_CONFIRMED_KEY).remove(AUTO_START_CONFIRMED_AT_KEY)
                .remove(BG_POPUP_CONFIRMED_KEY).remove(BG_POPUP_CONFIRMED_AT_KEY)
                .apply()
            updateDialogUi()
            Toast.makeText(ctx, "已清除关联启动确认状态，可重新检测", Toast.LENGTH_SHORT).show()
            true
        }
        btnEnable.setOnClickListener {
            com.decikeeper.app.protection.AccessibilityProtection.markManualSettingsLaunch()
            try {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                Toast.makeText(ctx, "请在系统中找到「决定守护」并开启无障碍服务", Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Toast.makeText(ctx, "无法打开无障碍设置", Toast.LENGTH_SHORT).show()
            }
            dialog?.dismiss()
        }

        // 后台耗电行：按钮 + 勾选框在同一行
        val rowBgBattery = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        cbBgBattery = android.widget.CheckBox(ctx).apply {
            text = "已开启"
            setTextColor(ThemeManager.getColorAttr(ctx, R.attr.zzText))
        }
        cbBgBattery!!.setOnCheckedChangeListener { _, checked ->
            if (checked) {
                prefs.edit()
                    .putBoolean("bg_battery_permission_confirmed", true)
                    .putLong("bg_battery_permission_confirmed_at", System.currentTimeMillis())
                    .apply()
            } else {
                prefs.edit()
                    .remove("bg_battery_permission_confirmed")
                    .remove("bg_battery_permission_confirmed_at")
                    .apply()
            }
            updateDialogUi()
        }
        rowBgBattery.addView(btnBgBattery, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        rowBgBattery.addView(cbBgBattery!!)

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
                    .putBoolean(AUTO_START_CONFIRMED_KEY, true).putLong(AUTO_START_CONFIRMED_AT_KEY, now)
                    .putBoolean(BG_POPUP_CONFIRMED_KEY, true).putLong(BG_POPUP_CONFIRMED_AT_KEY, now)
                    .apply()
            } else {
                prefs.edit()
                    .remove(ASSOC_START_CONFIRMED_KEY).remove(ASSOC_START_CONFIRMED_AT_KEY)
                    .remove(AUTO_START_CONFIRMED_KEY).remove(AUTO_START_CONFIRMED_AT_KEY)
                    .remove(BG_POPUP_CONFIRMED_KEY).remove(BG_POPUP_CONFIRMED_AT_KEY)
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
        buttonContainer.addView(rowBgBattery)
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

        val gap = (8 * density).toInt()
        val firstRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, (20 * density).toInt(), 0, (10 * density).toInt())
            gravity = Gravity.CENTER_VERTICAL
            isBaselineAligned = false
        }

        fun buildButton(label: String): com.google.android.material.button.MaterialButton {
            return com.google.android.material.button.MaterialButton(ctx).apply {
                text = label
                setAllCaps(false)
                setTextColor(ThemeManager.getColorAttr(ctx, R.attr.zzText))
                backgroundTintList = android.content.res.ColorStateList.valueOf(ThemeManager.getColorAttr(ctx, R.attr.zzSurface2))
                cornerRadius = (12 * density).toInt()
                minHeight = (44 * density).toInt()
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    setMargins(0, 0, gap, 0)
                }
            }
        }

        val btnLockScreen = buildButton("锁屏显示")
        val btnA11y = buildButton("开启无障碍").apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        var dialog: AlertDialog? = null

        fun updateDialogUi() {
            val lockScreenOk = AlarmPowerOffProtectionManager.canUseFullScreenIntent(ctx)
            val a11yOk = isA11yServiceReady(ctx)

            tvMessage.text = buildString {
                append("闹钟防关机保护会在闹钟挑战期间拦截关机菜单等系统入口。\n")
                append("请先补齐以下权限，然后再次尝试开启：\n\n")
                if (!lockScreenOk) {
                    append("锁屏显示未开启\n")
                }
                if (!a11yOk) {
                    append("无障碍服务未开启或处于待关闭状态\n")
                }
                if (lockScreenOk && a11yOk) {
                    append("所有权限已齐全，可开启功能。")
                }
            }

            btnLockScreen.text = if (lockScreenOk) "锁屏显示已开启" else "去开启锁屏显示"
            btnLockScreen.isEnabled = !lockScreenOk
            btnLockScreen.alpha = if (btnLockScreen.isEnabled) 1.0f else 0.45f

            btnA11y.text = if (a11yOk) "无障碍已开启" else "去开启无障碍"
            btnA11y.isEnabled = !a11yOk
            btnA11y.alpha = if (btnA11y.isEnabled) 1.0f else 0.45f
        }

        btnLockScreen.setOnClickListener {
            try {
                val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                    putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName)
                }
                startActivity(intent)
            } catch (_: Exception) {
                openAppDetailsSettings()
            }
            Toast.makeText(ctx, "请在通知设置中开启「锁屏显示」和「全屏通知」", Toast.LENGTH_LONG).show()
            dialog?.dismiss()
        }

        btnA11y.setOnClickListener {
            dialog?.dismiss()
            Toast.makeText(ctx, "请先使用上方「无障碍服务」开关补齐前置权限并开启无障碍", Toast.LENGTH_LONG).show()
        }

        dialog = AlertDialog.Builder(ctx)
            .setView(container)
            .create()

        firstRow.addView(btnLockScreen)
        firstRow.addView(btnA11y)

        container.addView(firstRow)

        updateDialogUi()
        dialog.show()
    }

    /**
     * 防关权限保护开启时，若锁屏显示未开启则提醒用户，
     * 因为后续闹钟防关机保护需要此项权限。
     */
    private fun showLockScreenSuggestionDialog() {
        val ctx = requireContext()
        AlertDialog.Builder(ctx)
            .setTitle("锁屏显示未开启")
            .setMessage("「闹钟防关机保护」需要「锁屏显示/全屏通知」权限才能拦截关机菜单。\n\n" +
                "建议现在顺便去开启，避免以后想开闹钟防关机保护时还要先关闭权限保护（需次日生效）。\n\n" +
                "是否现在去开启锁屏显示？")
            .setPositiveButton("去开启锁屏显示") { _, _ ->
                try {
                    val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                        putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName)
                    }
                    startActivity(intent)
                } catch (_: Exception) {
                    openAppDetailsSettings()
                }
                Toast.makeText(ctx, "请在通知设置中开启「锁屏显示」和「全屏通知」", Toast.LENGTH_LONG).show()
            }
            .setNegativeButton("暂不开启", null)
            .show()
    }

    /**
     * 防关权限保护引导弹窗 — 检查基础权限+锁屏显示+无障碍，然后开启保护
     */
    private fun showPermProtectionGuideDialog() {
        val ctx = requireContext()

        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(42, 30, 42, 36)
            setBackgroundResource(R.drawable.bg_sea_depths)
        }

        val topRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val tvTitle = TextView(ctx).apply {
            text = "🛡️ 开启防关权限保护"
            textSize = 20f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(android.graphics.Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val btnClose = TextView(ctx).apply {
            text = "❌"
            textSize = 16f
            setPadding(20, 6, 0, 6)
        }

        topRow.addView(tvTitle)
        topRow.addView(btnClose)
        container.addView(topRow)

        val tvMessage = TextView(ctx).apply {
            textSize = 14f
            setPadding(0, 20, 0, 0)
            setTextColor(android.graphics.Color.WHITE)
        }
        container.addView(tvMessage)

        val density = ctx.resources.displayMetrics.density
        val gap = (8 * density).toInt()
        val firstRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 28, 0, (10 * density).toInt())
            gravity = Gravity.CENTER_VERTICAL
            isBaselineAligned = false
        }

        fun buildButton(label: String): Button {
            return Button(ctx).apply {
                text = label
                setAllCaps(false)
                setTextColor(android.graphics.Color.WHITE)
                setBackgroundResource(R.drawable.bg_dialog_button_outline)
                minHeight = (48 * density).toInt()
                setPadding((10 * density).toInt(), (12 * density).toInt(), (10 * density).toInt(), (12 * density).toInt())
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    setMargins(0, 0, gap, 0)
                }
            }
        }

        val btnBase = buildButton("基础权限")
        val btnLockScreen = buildButton("锁屏显示")
        val btnA11y = buildButton("开启无障碍").apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        // 当基础权限+无障碍齐全时，显示"开启保护"按钮
        val btnEnable = Button(ctx).apply {
            text = "开启保护"
            setAllCaps(false)
            setTextColor(android.graphics.Color.WHITE)
            setBackgroundResource(R.drawable.bg_dialog_button_outline)
            minHeight = (48 * density).toInt()
            setPadding((10 * density).toInt(), (12 * density).toInt(), (10 * density).toInt(), (12 * density).toInt())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            visibility = View.GONE
        }

        var dialog: AlertDialog? = null

        fun updateDialogUi() {
            val permState = getHomePermissionState(ctx)
            val lockScreenOk = AlarmPowerOffProtectionManager.canUseFullScreenIntent(ctx)
            val a11yOk = isA11yServiceReady(ctx)
            val baseOk = permState.allGranted

            tvMessage.text = buildString {
                append("「防关权限保护」通过无障碍服务监控关键权限状态，防止悬浮窗、通知等权限被系统意外关闭。\n\n")
                append("请补齐以下权限。其中「锁屏显示」为可选，但建议一同开启：\n\n")
                if (!baseOk) {
                    append("⚠️ 基础权限未齐全：${permState.missingLabels.joinToString("、")}\n")
                } else {
                    append("✅ 基础权限已齐全\n")
                }
                if (!lockScreenOk) {
                    append("ℹ️ 锁屏显示未开启（可选，但推荐开启以支持闹钟防关机）\n")
                } else {
                    append("✅ 锁屏显示已开启\n")
                }
                if (!a11yOk) {
                    append("⚠️ 无障碍服务未开启或处于待关闭状态\n")
                } else {
                    append("✅ 无障碍已开启\n")
                }
                if (baseOk && a11yOk) {
                    append("\n✅ 基础权限和无障碍已齐全，可开启保护。")
                    if (!lockScreenOk) {
                        append("\n💡 建议先开启锁屏显示，以便今后使用闹钟防关机保护。")
                    }
                }
            }

            btnBase.text = if (baseOk) "基础权限已齐全" else "去补齐基础权限"
            btnBase.isEnabled = !baseOk
            btnBase.alpha = if (btnBase.isEnabled) 1.0f else 0.45f

            btnLockScreen.text = if (lockScreenOk) "锁屏显示已开启" else "去开启锁屏显示"
            btnLockScreen.isEnabled = !lockScreenOk
            btnLockScreen.alpha = if (btnLockScreen.isEnabled) 1.0f else 0.45f

            btnA11y.text = if (a11yOk) "无障碍已开启" else "去开启无障碍"
            btnA11y.isEnabled = !a11yOk
            btnA11y.alpha = if (btnA11y.isEnabled) 1.0f else 0.45f

            // 基础权限 + 无障碍齐全时才显示"开启保护"按钮
            btnEnable.visibility = if (baseOk && a11yOk) View.VISIBLE else View.GONE
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

        btnLockScreen.setOnClickListener {
            try {
                val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                    putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName)
                }
                startActivity(intent)
            } catch (_: Exception) {
                openAppDetailsSettings()
            }
            Toast.makeText(ctx, "请在通知设置中开启「锁屏显示」和「全屏通知」", Toast.LENGTH_LONG).show()
            dialog?.dismiss()
        }

        btnA11y.setOnClickListener {
            if (!isAccessibilityServiceEnabled()) {
                dialog?.dismiss()
                Toast.makeText(ctx, "请先使用上方「无障碍服务」开关补齐前置权限并开启无障碍", Toast.LENGTH_LONG).show()
            }
        }

        btnEnable.setOnClickListener {
            AccessibilityControlManager.setEnabled(ctx, true)
            Toast.makeText(ctx, "✅ 防关权限保护已开启", Toast.LENGTH_SHORT).show()
            updatePermProtectionUI()
            dialog?.dismiss()
        }

        dialog = AlertDialog.Builder(ctx)
            .setView(container)
            .create()

        btnClose.setOnClickListener { dialog?.dismiss() }

        firstRow.addView(btnBase)
        firstRow.addView(btnLockScreen)
        firstRow.addView(btnA11y)

        container.addView(firstRow)
        container.addView(btnEnable)

        updateDialogUi()
        dialog.show()
    }

    /**
     * 用户从无障碍设置返回后且在系统中开启了无障碍服务，
     * 询问是否同时开启闹钟防关机保护。
     */
    private fun showEnableAlarmPowerOffSuggestionDialog() {
        val ctx = requireContext()
        AlertDialog.Builder(ctx)
            .setTitle("是否同时开启闹钟防关机保护？")
            .setMessage("防关权限保护已开启！是否同时开启「闹钟防关机保护」？\n\n" +
                "开启后，闹钟挑战期间会自动拦截系统关机菜单，防止你通过关机逃避闹钟。")
            .setPositiveButton("开启") { _, _ ->
                if (AlarmPowerOffProtectionManager.canEnable(ctx)) {
                    AlarmPowerOffProtectionManager.setEnabled(ctx, true)
                    Toast.makeText(ctx, "闹钟防关机保护已开启", Toast.LENGTH_SHORT).show()
                    updateAlarmPowerOffProtectionUI()
                } else {
                    Toast.makeText(ctx, "尚有权限未补齐，请在「加强约束功能」中手动开启", Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton("暂不需要", null)
            .show()
    }

    private fun showAntiUninstallGuideDialog() {
        val ctx = requireContext()

        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(42, 30, 42, 36)
            setBackgroundResource(R.drawable.bg_sea_depths)
        }

        val topRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val tvTitle = TextView(ctx).apply {
            text = "🛡️ 开启防卸载保护"
            textSize = 20f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(android.graphics.Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val btnClose = TextView(ctx).apply {
            text = "❌"
            textSize = 16f
            setPadding(20, 6, 0, 6)
        }

        topRow.addView(tvTitle)
        topRow.addView(btnClose)
        container.addView(topRow)

        val tvMessage = TextView(ctx).apply {
            textSize = 14f
            setPadding(0, 20, 0, 0)
            setTextColor(android.graphics.Color.WHITE)
        }
        container.addView(tvMessage)

        val density = ctx.resources.displayMetrics.density
        val btnMargin = (4 * density).toInt()

        fun buildButton(label: String): Button {
            return Button(ctx).apply {
                text = label
                setAllCaps(false)
                setTextColor(android.graphics.Color.WHITE)
                setBackgroundResource(R.drawable.bg_dialog_button_outline)
                minHeight = (48 * density).toInt()
                setPadding((10 * density).toInt(), (12 * density).toInt(), (10 * density).toInt(), (12 * density).toInt())
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
            try {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:${ctx.packageName}")))
            } catch (_: Exception) {
            }
            Toast.makeText(ctx, "请在系统设置中开启关联启动；返回后会优先自动识别，若机型仍误判可手动确认一次", Toast.LENGTH_LONG).show()
            dialog?.dismiss()
        }

        btnA11y.setOnClickListener {
            dialog?.dismiss()
            Toast.makeText(ctx, "请先使用上方「无障碍服务」开关补齐前置权限并开启无障碍", Toast.LENGTH_LONG).show()
        }

        dialog = AlertDialog.Builder(ctx)
            .setView(container)
            .create()

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
        val lockScreenReady = AlarmPowerOffProtectionManager.canUseFullScreenIntent(ctx)
        val a11yReady = isA11yServiceReady(ctx)
        return AlarmPowerOffPrerequisiteState(
            lockScreenReady = lockScreenReady,
            a11yReady = a11yReady
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
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent)
                launched = true
                break
            } catch (e: Exception) {
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
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    android.net.Uri.parse("package:${ctx.packageName}")))
            } catch (e: Exception) {
                Log.w("RulesFragment", "Failed to open app details settings", e)
            }
        }
    }

    private fun getHomePermissionState(ctx: Context): HomePermissionState {
        val hasUsageStats = hasUsageStatsPermission(ctx)
        val hasOverlay = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) Settings.canDrawOverlays(ctx) else true
        val hasAutoStart = isManualPermissionShortTimeSatisfied(
            ctx = ctx,
            confirmedKey = AUTO_START_CONFIRMED_KEY,
            confirmedAtKey = AUTO_START_CONFIRMED_AT_KEY
        )
        val needBgPopup = isChineseRomDevice()
        val hasBgPopup = isManualPermissionShortTimeSatisfied(
            ctx = ctx,
            confirmedKey = BG_POPUP_CONFIRMED_KEY,
            confirmedAtKey = BG_POPUP_CONFIRMED_AT_KEY
        )

        val missing = mutableListOf<String>()
        if (!hasUsageStats) missing.add("使用情况访问")
        if (!hasOverlay) missing.add("悬浮窗")
        if (!hasAutoStart) missing.add("自启动")
        if (needBgPopup && !hasBgPopup) missing.add("后台弹出界面")

        val allGranted = missing.isEmpty()
        return HomePermissionState(allGranted = allGranted, missingLabels = missing)
    }

    private fun hasUsageStatsPermission(context: Context): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as android.app.AppOpsManager
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(android.app.AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), context.packageName)
        } else {
            appOps.checkOpNoThrow(android.app.AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), context.packageName)
        }
        return mode == android.app.AppOpsManager.MODE_ALLOWED
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
                updateAntiUninstallUI()
                triggerAntiUninstallStatusLoop(view)
            }
        }, 60000)
    }

    private fun updateAntiUninstallUI() {
        if (!isAdded) return
        val context = requireContext()

        val isAccessibilityEnabled = isAccessibilityServiceEnabled()
        val state = AntiUninstallManager.getProtectionState(context)

        if (isAccessibilityEnabled) {
            // Record that user has enabled it at least once
            val ledgerPrefs = context.getSharedPreferences(CreditManager.PREFS_NAME, Context.MODE_PRIVATE)
            if (!ledgerPrefs.getBoolean("has_enabled_anti_uninstall", false)) {
                ledgerPrefs.edit().putBoolean("has_enabled_anti_uninstall", true).apply()
            }

            switchUninstallUiUpdating = true
            when (state) {
                AntiUninstallManager.State.ACTIVE -> {
                    tvUninstallStatus?.text = "状态: 防卸载已生效"
                    tvUninstallStatus?.setTextColor(ThemeManager.getColorAttr(context, R.attr.zzSuccess))
                    switchUninstall?.isChecked = true
                }
                AntiUninstallManager.State.PENDING -> {
                    tvUninstallStatus?.text = "状态: 待关闭 (明日生效)"
                    tvUninstallStatus?.setTextColor(ThemeManager.getColorAttr(context, R.attr.zzWarning))
                    switchUninstall?.isChecked = false
                }
                AntiUninstallManager.State.DISABLED -> {
                    tvUninstallStatus?.text = "状态: 已关闭"
                    tvUninstallStatus?.setTextColor(ThemeManager.getColorAttr(context, R.attr.zzMuted))
                    switchUninstall?.isChecked = false
                }
            }
            switchUninstallUiUpdating = false
        } else {
            switchUninstallUiUpdating = true
            tvUninstallStatus?.text = "状态: 需开启无障碍服务"
            tvUninstallStatus?.setTextColor(ThemeManager.getColorAttr(context, R.attr.zzWarning))
            switchUninstall?.isChecked = false
            switchUninstallUiUpdating = false
        }
    }

    private fun updatePermProtectionUI() {
        if (!isAdded) return
        val context = requireContext()
        val systemEnabled = isAccessibilityServiceEnabled()
        val state = AccessibilityControlManager.getState(context)

        switchPermProtectionUiUpdating = true
        when (state) {
            AccessibilityControlManager.State.ACTIVE -> {
                switchPermProtection?.isChecked = systemEnabled
            }
            AccessibilityControlManager.State.PENDING -> {
                switchPermProtection?.isChecked = false
            }
            AccessibilityControlManager.State.DISABLED -> {
                switchPermProtection?.isChecked = false
            }
        }
        switchPermProtectionUiUpdating = false

        tvPermProtectionStatus?.let {
            when (state) {
                AccessibilityControlManager.State.PENDING -> {
                    it.text = "状态: 待关闭（明日生效）"
                    it.setTextColor(ThemeManager.getColorAttr(context, R.attr.zzWarning))
                    it.visibility = android.view.View.VISIBLE
                }
                AccessibilityControlManager.State.ACTIVE -> {
                    if (systemEnabled) {
                        it.text = "状态: 已生效"
                        it.setTextColor(ThemeManager.getColorAttr(context, R.attr.zzSuccess))
                    } else {
                        it.text = "状态: 需开启无障碍服务"
                        it.setTextColor(ThemeManager.getColorAttr(context, R.attr.zzWarning))
                    }
                    it.visibility = android.view.View.VISIBLE
                }
                AccessibilityControlManager.State.DISABLED -> {
                    it.text = "状态: 已关闭"
                    it.setTextColor(ThemeManager.getColorAttr(context, R.attr.zzMuted))
                    it.visibility = android.view.View.VISIBLE
                }
            }
        }
    }

    private fun updateAccessibilityServiceUI() {
        if (!isAdded) return
        val ctx = requireContext()
        val a11yOk = isAccessibilityServiceEnabled()
        val pendingDisable = isA11yPendingDisable(ctx)
        // 如果系统已关闭但还标记着待关闭，清除标记
        if (!a11yOk && pendingDisable) {
            setA11yPendingDisable(ctx, false)
        }
        val finalPending = if (a11yOk) pendingDisable else false

        switchA11yServiceUiUpdating = true
        switchAccessibilityService?.isChecked = a11yOk && !finalPending
        switchA11yServiceUiUpdating = false

        tvAccessibilityStatus?.let {
            if (finalPending) {
                it.text = "状态: 待关闭（明日生效）"
                it.setTextColor(ThemeManager.getColorAttr(ctx, R.attr.zzWarning))
            } else if (a11yOk) {
                it.text = "状态: 无障碍服务已开启，可启用各项保护"
                it.setTextColor(ThemeManager.getColorAttr(ctx, R.attr.zzSuccess))
            } else {
                it.text = "状态: 服务未开启"
                it.setTextColor(ThemeManager.getColorAttr(ctx, R.attr.zzMuted))
            }
        }
    }

    private fun updateAlarmPowerOffProtectionUI() {
        if (!isAdded) return
        val context = requireContext()
        val systemEnabled = isAccessibilityServiceEnabled()
        val enabled = systemEnabled && AlarmPowerOffProtectionManager.isEnabled(context)

        switchAlarmPowerOffUiUpdating = true
        switchAlarmPowerOffProtection?.isChecked = enabled
        switchAlarmPowerOffUiUpdating = false

        if (!systemEnabled) {
            tvAlarmPowerOffProtectionStatus?.text = "状态: 需开启无障碍服务"
            tvAlarmPowerOffProtectionStatus?.setTextColor(ThemeManager.getColorAttr(context, R.attr.zzWarning))
        } else if (enabled) {
            tvAlarmPowerOffProtectionStatus?.text = "状态: 已开启"
            tvAlarmPowerOffProtectionStatus?.setTextColor(ThemeManager.getColorAttr(context, R.attr.zzSuccess))
        } else {
            tvAlarmPowerOffProtectionStatus?.text = "状态: 已关闭"
            tvAlarmPowerOffProtectionStatus?.setTextColor(ThemeManager.getColorAttr(context, R.attr.zzMuted))
        }
    }

    /**
     * 最近任务隐藏 (TASK-04): 仅写 RecentTasksHideManager,不直读 prefs;
     * listener guard 防初始化时同步触发写回 prefs 死循环.
     */
    private fun setupHideInRecentTasksSwitch(switchView: Switch) {
        val context = requireContext()
        switchHideInRecentTasksUiUpdating = true
        switchView.isChecked = RecentTasksHideManager.isHideEnabled(context)
        switchHideInRecentTasksUiUpdating = false

        switchView.setOnCheckedChangeListener { _, isChecked ->
            if (switchHideInRecentTasksUiUpdating) return@setOnCheckedChangeListener
            RecentTasksHideManager.setHideEnabled(context, isChecked)
            val msg = if (isChecked) "已开启。下次不在决定守护内打开多任务时生效"
            else "已关闭。下次启动决定守护后会在多任务界面显示"
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateHideInRecentTasksUI() {
        if (!isAdded) return
        switchHideInRecentTasksUiUpdating = true
        try {
            switchHideInRecentTasks?.isChecked = RecentTasksHideManager.isHideEnabled(requireContext())
        } finally {
            switchHideInRecentTasksUiUpdating = false
        }
    }

    /**
     * 隐私空间拦截 (TASK-02): 规则页 UI 全程经 Manager,
     * 禁止 UI 直写 privacy_protection_prefs / KEY_IS_ENABLED / pending / baseline.
     */
    private fun setupPrivacySpaceGuardSwitch(switchView: Switch) {
        val context = requireContext()
        // 初始化状态 (经 Manager, 严禁直读 prefs)
        val initialState = PrivacyProtectionManager.getProtectionState(context)
        val initialEnforcing = PrivacyProtectionManager.isEnforcing(context)
        switchPrivacySpaceGuardUiUpdating = true
        switchView.isChecked = computePrivacySpaceGuardSwitchChecked(initialState, initialEnforcing)
        switchPrivacySpaceGuardUiUpdating = false

        switchView.setOnCheckedChangeListener { _, isChecked ->
            if (switchPrivacySpaceGuardUiUpdating) return@setOnCheckedChangeListener

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
                if (TimeRuleManager.isInAdaptationPeriod(context)) {
                    PrivacyProtectionManager.setProtectionEnabled(context, false)
                    Toast.makeText(context, getString(R.string.privacy_space_guard_toast_adaptation_off), Toast.LENGTH_SHORT).show()
                } else {
                    PrivacyProtectionManager.setProtectionEnabled(context, false)
                    Toast.makeText(context, getString(R.string.privacy_space_guard_toast_pending), Toast.LENGTH_LONG).show()
                }
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
        // 全程经 Manager
        val state = PrivacyProtectionManager.getProtectionState(context)
        val enforcing = PrivacyProtectionManager.isEnforcing(context)

        switchPrivacySpaceGuardUiUpdating = true
        switchPrivacySpaceGuard?.isChecked = computePrivacySpaceGuardSwitchChecked(state, enforcing)
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
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(requireContext(), "无法打开无障碍设置", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openAppDetailsSettings() {
        try {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${requireContext().packageName}"))
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(requireContext(), "无法打开应用设置", Toast.LENGTH_SHORT).show()
        }
    }

    private fun isAccessibilityServiceEnabled(ctx: Context? = context): Boolean {
        val target = ctx ?: context ?: return false
        val enabledServices = Settings.Secure.getString(target.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        return enabledServices?.contains(target.packageName) == true
    }

    internal fun isA11yPendingDisable(ctx: Context): Boolean {
        return ctx.getSharedPreferences(CreditManager.PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_A11Y_PENDING_DISABLE, false)
    }

    internal fun setA11yPendingDisable(ctx: Context, pending: Boolean) {
        ctx.getSharedPreferences(CreditManager.PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_A11Y_PENDING_DISABLE, pending)
            .apply()
    }

    internal fun isA11yServiceReady(ctx: Context? = context): Boolean {
        val target = ctx ?: context ?: return false
        return isA11yReady(isAccessibilityServiceEnabled(target), isA11yPendingDisable(target))
    }
}
