/**
 * =================================================================================
 * [Context Header]
 * * @file: HomeFragment.kt
 * * @role: UI
 * * @description: 首页状态与快捷入口展示。
 * * @responsibilities:
 * 1. 展示权限/状态概览。
 * 2. 提供常用入口与引导。
 * * @context:
 * - 上游: MainActivity
 * - 下游: PermissionManager, CreditManager
 * * @constraints (必读):
 * 1. 禁止承载复杂业务逻辑。
 * 2. UI 更新需主线程安全。
 * 3. 需处理权限缺失。
 * =================================================================================
 */
package com.zhizhi.app.ui

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.CountDownTimer
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.cardview.widget.CardView
import androidx.core.content.FileProvider
import androidx.fragment.app.Fragment
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.zhizhi.app.R
import com.zhizhi.app.data.LogExporter
import com.zhizhi.app.data.calculateQuotaDetail
import com.zhizhi.app.data.formatUsableCountHalfUnits
import com.zhizhi.app.manager.AppVisibilityTracker
import com.zhizhi.app.manager.CreditManager
import com.zhizhi.app.manager.ManualPermissionRecoveryManager
import com.zhizhi.app.manager.RomPermissionDetector
import com.zhizhi.app.manager.TimeRuleManager
import com.zhizhi.app.manager.getEffectiveUsableCountLimit
import com.zhizhi.app.service.CoreService
import com.zhizhi.app.service.MonitorService
import com.zhizhi.app.test.PermissionTestRunner
import com.zhizhi.app.util.computeCurrentUsableRemaining
import java.util.Locale















class HomeFragment : Fragment() {

    // 🚀 [Request Notification Permission] 使用 registerForActivityResult 替代弃用的 requestPermissions
    private val notificationPermissionLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { /* 无需额外操作，下次 updateSetupGuide 会自动刷新状态 */ }

    companion object {
        internal fun shouldAutoMarkPendingPermission(
            detected: Boolean?,
            hasRecentGuidedVisit: Boolean = false
        ): Boolean {
            return ManualPermissionRecoveryManager.shouldAutoMarkByGuidedFlow(
                detected = detected,
                hasRecentGuidedVisit = hasRecentGuidedVisit
            )
        }

        internal fun shouldAllowManualFallbackConfirm(
            permissionKey: String,
            detected: Boolean?
        ): Boolean {
            return permissionKey == "auto_start_confirmed" ||
                permissionKey == "bg_popup_confirmed" ||
                permissionKey == "assoc_start_confirmed"
        }

        internal fun shouldSuppressFirstMissingPermissionPrompt(
            isPermissionMissing: Boolean,
            hasBeenPrimed: Boolean
        ): Boolean {
            return isPermissionMissing
        }

        internal fun shouldShowWeeklyPrivilegeEntryInHome(): Boolean = true

        internal fun buildWeeklyPrivilegeEntryStatusText(
            remainingDuration: Int,
            durationLimit: Int,
            remainingCount: Int,
            countLimit: Int,
            isActive: Boolean,
            unavailableReason: String?
        ): String {
            if (isActive) return "进行中：本次特权已启用，可随时提前结束。"
            return if (unavailableReason == null) {
                "本周剩余：${remainingDuration}/${durationLimit}min，${remainingCount}/${countLimit}次"
            } else {
                "当前不可用（$unavailableReason）\n本周剩余：${remainingDuration}/${durationLimit}min，${remainingCount}/${countLimit}次"
            }
        }

        internal fun buildWeeklyPrivilegeEntryButtonText(
            remainingDuration: Int,
            remainingCount: Int,
            isActive: Boolean,
            unavailableReason: String?
        ): String {
            if (isActive) return "结束本次特权"
            return if (unavailableReason == null) {
                "选择时长并启用 (${remainingDuration}min / ${remainingCount}次)"
            } else {
                "暂不可用（$unavailableReason）"
            }
        }

        internal fun isWeeklyPrivilegeRequestedDurationInRange(
            requested: Int,
            remainingDuration: Int
        ): Boolean {
            return requested in 1..remainingDuration
        }

        internal fun shouldAcceptWeeklyPrivilegeEffectiveMinutes(
            requested: Int,
            effective: Int
        ): Boolean {
            return requested > 0 && effective == requested
        }

        /**
         * 特权时长自动截断工具：将用户请求时长 clamp 到 effective 上限。
         *
         * 背景：CHANGELOG v0.2.0 已声明"特权时长选择器自动截断 + 显示上限"。
         * 当 requested > effective（典型：15:02 申请 59h，窗口 7:00-23:00 + 24:00 边界 → effective=478min）
         * 时，不应弹错误让用户重输，而应自动 clamp 到 effective 并 Toast 提示。
         *
         * @return ClampResult，包含最终使用的 clampedMinutes 和是否发生截断
         */
        internal fun clampRequestedToEffectiveMinutes(
            requested: Int,
            effective: Int
        ): ClampResult {
            // 边界情况：有效上限为 0（窗口外 / 配额耗尽）— 调用方应已处理
            val safeRequested = requested.coerceAtLeast(0)
            val safeEffective = effective.coerceAtLeast(0)
            return if (safeRequested > safeEffective) {
                ClampResult(actualMinutes = safeEffective, didClamp = true, originalRequested = safeRequested)
            } else {
                ClampResult(actualMinutes = safeRequested, didClamp = false, originalRequested = safeRequested)
            }
        }

        /**
         * 自动截断提示文案。
         * @param clampedMinutes 实际生效分钟数
         * @param boundaryLabel 边界标签，如 "到 23:00 边界" / "到 24:00 边界"
         */
        internal fun buildPrivilegeAutoClampToast(
            clampedMinutes: Int,
            boundaryLabel: String
        ): String {
            return "已自动调整为 $clampedMinutes 分钟 ($boundaryLabel)"
        }

        /**
         * 根据窗口结束时间生成边界标签。
         * 特权窗口结束 = effective 上界 (privileged session must end at boundary)。
         * 24:00 (windowEnd) → "到 24:00 边界"；其他 → "到 HH:MM 边界"。
         */
        internal fun buildPrivilegeBoundaryLabel(windowEndHHmm: String): String {
            return "到 $windowEndHHmm 边界"
        }
    }

    /**
     * 特权时长自动截断结果。
     */
    internal data class ClampResult(
        val actualMinutes: Int,
        val didClamp: Boolean,
        val originalRequested: Int
    )

    // Home 页控件
    private var cardPermissionStatus: CardView? = null
    private var tvStatus: TextView? = null
    private var tvProtectionStatus: TextView? = null
    private var tvCountdown: TextView? = null
    private var tvTempAccessStatus: TextView? = null
    
    // Dashboard Controls
    private var tvCurrentSegmentRange: TextView? = null
    private var tvStatTimeBig: TextView? = null
    private var tvStatTimeDetail: TextView? = null
    private var tvNextSegmentInfo: TextView? = null
    private var tvUsableCountBig: TextView? = null
    private var tvSurplusBorrowLine: TextView? = null
    private var tvRestCountdownInCard: TextView? = null

    private var cardWeeklyPrivilegeEntry: CardView? = null
    private var tvWeeklyPrivilegeEntryStatus: TextView? = null
    private var btnWeeklyPrivilegeEntry: Button? = null
    private var btnPermission: Button? = null
    private var btnHelp: Button? = null
    
    // 休息时间卡片
    private var cardFreeTime: CardView? = null
    private var tvFreeTimeCountdown: TextView? = null
    private var btnStopFreeTime: Button? = null
    private var freeTimeTimer: CountDownTimer? = null

    // 适应期卡片
    private var cardAdaptation: CardView? = null
    private var btnEndAdaptation: Button? = null

    // ✈️ [NEW] Setup Guide Views
    private var cardSetupGuide: CardView? = null
    private var tvIconPerm: TextView? = null
    private var tvIconBlacklist: TextView? = null
    private var tvIconDnd: TextView? = null
    private var btnGoPerm: View? = null
    private var btnGoBlacklist: View? = null
    private var btnGoDnd: View? = null

    private var uiTimer: CountDownTimer? = null

    // 🚀 [Fix] 防止 WindowLeaked - 保存当前显示的权限检查对话框引用
    private var activePermissionDialog: AlertDialog? = null

    // 🚀 [Fix] 待确认权限队列 (持久化到SP，支持多项权限顺序确认)
    private val PREF_PENDING_CONFIRM_KEYS = "pending_confirm_keys"
    private val PREF_A11Y_LOST_DIALOG_LAST_SHOWN = "a11y_lost_dialog_last_shown"
    private val A11Y_LOST_DIALOG_COOLDOWN_MS = 60 * 60 * 1000L
    private val PREF_MANUAL_PERMISSION_LAST_ASK = "manual_permission_last_ask"
    private val PREF_MANUAL_PERMISSION_PROMPT_PRIMED = "manual_permission_prompt_primed"
    private val MANUAL_PERMISSION_ASK_COOLDOWN_MS = 15 * 60 * 1000L
    private val PERMISSION_PREFS_NAME = "iron_will_permissions"
    private val KEY_OVERLAY_PROMPT_PRIMED = "overlay_permission_prompt_primed"
    private val KEY_USAGE_STATS_PROMPT_PRIMED = "usage_stats_permission_prompt_primed"

    // 🚀 [Fix] Receiver to update UI when rules change (e.g. Privilege started from LockScreen)
    private val updateReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (context != null) {
                updateQuotaDisplay()
                updateTimerText() // update free time card if needed
            }
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_home, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        initViews(view)
        setupClickEvents()
        
        // 🚀 Register Receiver
        val filter = android.content.IntentFilter().apply {
            addAction(MonitorService.ACTION_REFRESH_RULES)
            addAction(MonitorService.ACTION_END_FREE_TIME)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requireContext().registerReceiver(updateReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            requireContext().registerReceiver(updateReceiver, filter)
        }
    }

    override fun onResume() {
        super.onResume()
        AppVisibilityTracker.setHomeVisible(true)

        // 🚀 [增强] 用户点完跳转链接回到主界面，发送隐式自启动测试广播
        val ctx = context
        if (ctx != null) {
            val testIntent = android.content.Intent("com.zhizhi.app.ACTION_TEST_AUTO_START")
            testIntent.setPackage(ctx.packageName)
            try {
                ctx.sendBroadcast(testIntent)
            } catch (e: Exception) {
                android.util.Log.e("HomeFragment", "Failed to send auto start test broadcast", e)
            }
        }

        syncDetectedManualPermissionsOnResume()
        updateQuotaDisplay()
        updateProtectionStatus()
        startUiCountdown()
        updateAdaptationCardVisibility()
        updateSetupGuide() // 🚀 [NEW]
        // 🚀 [优化] 用户从设置页返回后自动识别并标记已处理权限
        processPendingPermissionAutoMark()
    }

    override fun onPause() {
        super.onPause()
        AppVisibilityTracker.setHomeVisible(false)
        uiTimer?.cancel()
        // 🚀 [Fix] Dismiss any showing dialog to prevent WindowLeaked
        try {
            activePermissionDialog?.let {
                if (it.isShowing) it.dismiss()
            }
        } catch (e: Exception) {
            Log.w("HomeFragment", "Failed to dismiss permission dialog", e)
        }
        activePermissionDialog = null
    }

    override fun onDestroyView() {
        uiTimer?.cancel()
        freeTimeTimer?.cancel()
        try {
            requireContext().unregisterReceiver(updateReceiver)
        } catch (e: Exception) {
            // Ignore if already unregistered
        }
        super.onDestroyView()
    }

    private fun initViews(view: View) {
        cardPermissionStatus = view.findViewById(R.id.card_permission_status)
        tvStatus = view.findViewById(R.id.tv_status)
        // [open-source] 原"长按运行自检套件"入口已移除（TestScenarioManager/TestScenarioRunner
        // 已迁入 test 源集，见 CONTRIBUTING.md 关键文件地图）。
        tvProtectionStatus = view.findViewById(R.id.tv_protection_status)
        tvCountdown = view.findViewById(R.id.tv_countdown)
        tvTempAccessStatus = view.findViewById(R.id.tv_temp_access_status)
        
        // Dashboard Views
        tvCurrentSegmentRange = view.findViewById(R.id.tv_current_segment_range)
        tvStatTimeBig = view.findViewById(R.id.tv_stat_time_big)
        tvStatTimeDetail = view.findViewById(R.id.tv_stat_time_detail)
        tvNextSegmentInfo = view.findViewById(R.id.tv_next_segment_info)
        tvUsableCountBig = view.findViewById(R.id.tv_usable_count_big)
        tvSurplusBorrowLine = view.findViewById(R.id.tv_surplus_borrow_line)
        tvRestCountdownInCard = view.findViewById(R.id.tv_rest_countdown_in_card)

        cardWeeklyPrivilegeEntry = view.findViewById(R.id.card_weekly_privilege_entry)
        tvWeeklyPrivilegeEntryStatus = view.findViewById(R.id.tv_weekly_privilege_entry_status)
        btnWeeklyPrivilegeEntry = view.findViewById(R.id.btn_weekly_privilege_entry)
        
        btnPermission = view.findViewById(R.id.btn_open_permission)
        btnHelp = view.findViewById(R.id.btn_help)
        
        // 休息时间卡片
        cardFreeTime = view.findViewById(R.id.card_free_time)
        tvFreeTimeCountdown = view.findViewById(R.id.tv_free_time_countdown)
        btnStopFreeTime = view.findViewById(R.id.btn_stop_free_time)
        
        // 适应期卡片
        cardAdaptation = view.findViewById(R.id.card_adaptation)
        btnEndAdaptation = view.findViewById(R.id.btn_end_adaptation)

        // ✈️ [NEW] Setup Guide Initialization
        cardSetupGuide = view.findViewById(R.id.card_setup_guide)
        tvIconPerm = view.findViewById(R.id.tv_setup_icon_perm)
        tvIconBlacklist = view.findViewById(R.id.tv_setup_icon_blacklist)
        tvIconDnd = view.findViewById(R.id.tv_setup_icon_dnd)
        btnGoPerm = view.findViewById(R.id.btn_setup_go_perm)
        btnGoBlacklist = view.findViewById(R.id.btn_setup_go_blacklist)
        btnGoDnd = view.findViewById(R.id.btn_setup_go_dnd)
    }

    private fun setupClickEvents() {
        val ctx = context ?: return
        
        var lastPermissionClickAt = 0L
        var pendingPermissionClick: Runnable? = null
        val doubleClickWindowMs = 320L

        btnPermission?.setOnClickListener {
            val button = btnPermission ?: return@setOnClickListener
            if (!button.isEnabled) return@setOnClickListener
            val now = SystemClock.elapsedRealtime()
            if (now - lastPermissionClickAt <= doubleClickWindowMs) {
                lastPermissionClickAt = 0L
                pendingPermissionClick?.let { button.removeCallbacks(it) }
                pendingPermissionClick = null

                val result = PermissionTestRunner.run(requireContext())
                AlertDialog.Builder(requireContext())
                    .setTitle("权限测试完成")
                    .setMessage(result)
                    .setPositiveButton("知道了", null)
                    .show()
            } else {
                lastPermissionClickAt = now
                pendingPermissionClick?.let { button.removeCallbacks(it) }
                val r = Runnable { showProtectionDialog() }
                pendingPermissionClick = r
                button.postDelayed(r, doubleClickWindowMs)
            }
        }
        btnHelp?.setOnClickListener { startActivity(Intent(ctx, HelpActivity::class.java)) }

        btnWeeklyPrivilegeEntry?.setOnClickListener {
            val context = context ?: return@setOnClickListener
            val isActive = CreditManager.isWeeklyPrivilegeSessionActive(context)
            if (isActive) {
                if (CreditManager.isInPrivilegeAdjustmentWindow(context)) {
                    val remainingMillis = (CreditManager.getFreeTimeEnd(context) - System.currentTimeMillis()).coerceAtLeast(0L)
                    val remainingMins = (remainingMillis / 60000L).toInt() + (if (remainingMillis % 60000L > 0) 1 else 0)
                    AlertDialog.Builder(context)
                        .setTitle("特权进行中")
                        .setMessage("当前特权剩余约 $remainingMins 分钟\n启用未超过5分钟，可调整时长或结束特权")
                        .setPositiveButton("调整时长") { _, _ -> showPrivilegeAdjustDialog(context) }
                        .setNeutralButton("结束特权") { _, _ ->
                            CreditManager.stopWeeklyPrivilege(context)
                            val intent = Intent(MonitorService.ACTION_END_FREE_TIME)
                            intent.setPackage(context.packageName)
                            context.sendBroadcast(intent)
                            updateQuotaDisplay()
                            updateTimerText()
                            Toast.makeText(context, "已结束每周特权", Toast.LENGTH_SHORT).show()
                        }
                        .setNegativeButton("取消", null)
                        .show()
                } else {
                    val preview = CreditManager.getPrivilegeRefundPreview(context)
                    val msg = if (preview != null) {
                        buildString {
                            append("确定要提前结束特权时间吗？\n\n")
                            val usedRatioPct = (preview.usedRatio * 100).toInt()
                            val refundRatioPct = (preview.refundRatio * 100).toInt()
                            append("已用：${"%.1f".format(preview.usedMins)} / ${"%.1f".format(preview.requestedMins)} 分钟（${usedRatioPct}%）\n")
                            append("返还比例：${refundRatioPct}%\n")
                            append("预计返还：${"%.1f".format(preview.refundAmount)} 分钟")
                        }
                    } else {
                        "确定要提前结束特权时间吗？"
                    }
                    AlertDialog.Builder(context)
                        .setTitle("确认结束每周特权")
                        .setMessage(msg)
                        .setPositiveButton("确定") { _, _ ->
                            CreditManager.stopWeeklyPrivilege(context)
                            val intent = Intent(MonitorService.ACTION_END_FREE_TIME)
                            intent.setPackage(context.packageName)
                            context.sendBroadcast(intent)
                            updateQuotaDisplay()
                            updateTimerText()
                            Toast.makeText(context, "已结束每周特权", Toast.LENGTH_SHORT).show()
                        }
                        .setNegativeButton("取消", null)
                        .show()
                }
                return@setOnClickListener
            }

            val unavailableReason = CreditManager.getWeeklyPrivilegeUnavailableReason(context)
            if (unavailableReason != null) {
                Toast.makeText(context, "当前不可启用：$unavailableReason", Toast.LENGTH_SHORT).show()
                updateWeeklyPrivilegeEntryDisplay()
                return@setOnClickListener
            }

            showWeeklyPrivilegeStartDialog(context)
        }

        // 停止休息按钮
        btnStopFreeTime?.setOnClickListener {
            if (CreditManager.isWeeklyPrivilegeSessionActive(ctx)) {
                val preview = CreditManager.getPrivilegeRefundPreview(ctx)
                val msg = if (preview != null) {
                    buildString {
                        append("确定要提前结束特权时间吗？\n\n")
                        val usedRatioPct = (preview.usedRatio * 100).toInt()
                        val refundRatioPct = (preview.refundRatio * 100).toInt()
                        append("已用：${"%.1f".format(preview.usedMins)} / ${"%.1f".format(preview.requestedMins)} 分钟（${usedRatioPct}%）\n")
                        append("返还比例：${refundRatioPct}%\n")
                        append("预计返还：${"%.1f".format(preview.refundAmount)} 分钟")
                    }
                } else {
                    "确定要提前结束特权时间吗？"
                }
                AlertDialog.Builder(ctx)
                    .setTitle("确认结束每周特权")
                    .setMessage(msg)
                    .setPositiveButton("确定") { _, _ ->
                        CreditManager.stopWeeklyPrivilege(ctx)
                        val intent = Intent(MonitorService.ACTION_END_FREE_TIME)
                        intent.setPackage(ctx.packageName)
                        ctx.sendBroadcast(intent)
                        cardFreeTime?.visibility = View.GONE
                        freeTimeTimer?.cancel()
                        tvCountdown?.text = "当前状态：勿扰锁定中"
                        Toast.makeText(ctx, "已结束每周特权", Toast.LENGTH_SHORT).show()
                    }
                    .setNegativeButton("取消", null)
                    .show()
            } else {
                // 计算返还预览
                val preview = CreditManager.getRestRefundPreview(ctx)
                val msg = if (preview != null) {
                    buildString {
                        append("确定要提前结束休息时间吗？\n\n")
                        val usedRatioPct = (preview.usedRatio * 100).toInt()
                        val refundRatioPct = (preview.refundRatio * 100).toInt()
                        append("已用：${"%.1f".format(preview.usedMins)} / ${"%.1f".format(preview.requestedMins)} 分钟（${usedRatioPct}%）\n")
                        append("返还比例：${refundRatioPct}%\n")
                        append("预计返还：${"%.1f".format(preview.refundAmount)} 分钟额度")
                    }
                } else {
                    "确定要提前结束休息时间吗？"
                }
                AlertDialog.Builder(ctx)
                    .setTitle("确认停止休息")
                    .setMessage(msg)
                    .setPositiveButton("确定") { _, _ ->
                        CreditManager.setFreeTimeEnd(ctx, 0)
                        val intent = Intent(MonitorService.ACTION_END_FREE_TIME)
                        intent.setPackage(ctx.packageName)
                        ctx.sendBroadcast(intent)
                        cardFreeTime?.visibility = View.GONE
                        freeTimeTimer?.cancel()
                        tvCountdown?.text = "当前状态：勿扰锁定中"
                        Toast.makeText(ctx, "已停止休息", Toast.LENGTH_SHORT).show()
                    }
                    .setNegativeButton("取消", null)
                    .show()
            }
        }

        // 结束适应期按钮
        btnEndAdaptation?.setOnClickListener {
            AlertDialog.Builder(ctx)
                .setTitle("确认结束适应期")
                .setMessage("结束后，修改规则将按以下方式生效：\n\n" +
                    "✅ 有利改动（加严规则）→ 立即生效\n\n" +
                    "⚠️ 不利改动（放松规则）：\n" +
                    "　• 每日参数 → 次日生效\n" +
                    "　• 每周参数 → 次周一生效\n\n" +
                    "确认已完成所有初始配置了吗？")
                .setPositiveButton("已完成") { _, _ ->
                    CreditManager.markFirstSetupDone(ctx)
                    TimeRuleManager.markFirstSetupDone(ctx)
                    cardAdaptation?.visibility = View.GONE
                    Toast.makeText(ctx, "适应期已结束", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("再想想", null)
                .show()
        }

        // ✈️ [NEW] Setup Guide Click Listeners
        btnGoPerm?.setOnClickListener { showProtectionDialog() }
        btnGoBlacklist?.setOnClickListener { (activity as? MainActivity)?.findViewById<BottomNavigationView>(R.id.bottom_navigation)?.selectedItemId = R.id.nav_rules }
        btnGoDnd?.setOnClickListener { (activity as? MainActivity)?.showFocusRules(FocusRulesFragment.TAB_TIME) }
    }

    private fun getPrivilegeBoundaryLabel(context: Context): String {
        val windowEnd = TimeRuleManager.getPrivilegeWindowEnd(context)
        return buildPrivilegeBoundaryLabel(windowEnd)
    }

    private fun showWeeklyPrivilegeStartDialog(context: Context) {
        val remainingDuration = CreditManager.getRemainingWeeklyPrivilegeDuration(context)
        if (remainingDuration <= 0) {
            Toast.makeText(context, "当前无可用特权时长", Toast.LENGTH_SHORT).show()
            updateWeeklyPrivilegeEntryDisplay()
            return
        }

        val view = layoutInflater.inflate(R.layout.dialog_privilege_time_picker, null)
        val npHour = view.findViewById<android.widget.NumberPicker>(R.id.np_hour)
        val npMinute = view.findViewById<android.widget.NumberPicker>(R.id.np_minute)

        npHour.minValue = 0
        npHour.maxValue = (remainingDuration / 60).coerceAtLeast(0)
        npHour.value = 0

        npMinute.minValue = 0
        npMinute.maxValue = 59
        npMinute.value = 0
        npMinute.setFormatter { i -> String.format(Locale.US, "%02d", i) }

        val dialog = AlertDialog.Builder(context)
            .setTitle("选择本次特权时长")
            .setMessage("本周剩余 $remainingDuration 分钟。请手动选择本次要使用的时长，确认后会消耗 1 次特权。")
            .setView(view)
            .setPositiveButton("确认启动", null)
            .setNegativeButton("取消", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val requested = npHour.value * 60 + npMinute.value
                if (requested <= 0) {
                    Toast.makeText(context, "请先选择特权时长", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                if (!isWeeklyPrivilegeRequestedDurationInRange(requested, remainingDuration)) {
                    Toast.makeText(context, "本周剩余特权时长只有 $remainingDuration 分钟", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }

                val effective = CreditManager.calculateWeeklyPrivilegeEffectiveMinutes(context, requested)
                if (effective <= 0) {
                    val reason = CreditManager.getWeeklyPrivilegeUnavailableReason(context) ?: "当前时段不可用"
                    Toast.makeText(context, "启用失败：$reason", Toast.LENGTH_SHORT).show()
                    updateWeeklyPrivilegeEntryDisplay()
                    return@setOnClickListener
                }
                // [CAT-B Fix] CHANGELOG v0.2.0: 自动 clamp 到 effective 边界 + Toast 提示，
                // 替代旧的"弹错误让用户重输"行为。
                val clamp = clampRequestedToEffectiveMinutes(requested, effective)
                if (clamp.didClamp) {
                    Toast.makeText(
                        context,
                        buildPrivilegeAutoClampToast(
                            clampedMinutes = clamp.actualMinutes,
                            boundaryLabel = getPrivilegeBoundaryLabel(context)
                        ),
                        Toast.LENGTH_LONG
                    ).show()
                }

                val privilegeStarted = CreditManager.startWeeklyPrivilege(context, clamp.actualMinutes)
                if (privilegeStarted) {
                    val refreshIntent = Intent(MonitorService.ACTION_REFRESH_RULES)
                    refreshIntent.setPackage(context.packageName)
                    context.sendBroadcast(refreshIntent)
                    updateQuotaDisplay()
                    updateTimerText()
                    Toast.makeText(context, "每周特权已启用（${clamp.actualMinutes} 分钟）", Toast.LENGTH_SHORT).show()
                    dialog.dismiss()
                } else {
                    val reason = CreditManager.getWeeklyPrivilegeUnavailableReason(context) ?: "条件不足"
                    Toast.makeText(context, "启用失败：$reason", Toast.LENGTH_SHORT).show()
                    updateWeeklyPrivilegeEntryDisplay()
                }
            }
        }

        dialog.show()
        // 统一弹窗按钮着色：取消(左)=守规→绿，确认启动(右)=逃避→红
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.setTextColor(0xFF2E7D32.toInt())
        dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(0xFFC62828.toInt())
    }

    private fun showPrivilegeAdjustDialog(context: Context) {
        val currentDuration = CreditManager.getCurrentPrivilegeDuration(context)
        val remainingDur = CreditManager.getRemainingWeeklyPrivilegeDuration(context)
        val maxAvailable = minOf(remainingDur + currentDuration, CreditManager.getWeeklyPrivilegeDurationLimit(context)).coerceAtLeast(0)
        val maxEffective = CreditManager.calculateWeeklyPrivilegeEffectiveMinutes(context, maxAvailable)
        val maxTotal = minOf(maxAvailable, maxEffective).coerceAtLeast(1)

        val view = layoutInflater.inflate(R.layout.dialog_privilege_time_picker, null)
        val npHour = view.findViewById<android.widget.NumberPicker>(R.id.np_hour)
        val npMinute = view.findViewById<android.widget.NumberPicker>(R.id.np_minute)

        val maxHours = (maxTotal / 60).coerceAtMost(12)
        val maxMins = maxTotal % 60

        npHour.minValue = 0
        npHour.maxValue = maxHours
        npHour.value = currentDuration / 60

        npMinute.minValue = 0
        npMinute.maxValue = if (npHour.value == maxHours) maxMins.coerceAtLeast(1) else 59
        npMinute.value = (currentDuration % 60).coerceIn(npMinute.minValue, npMinute.maxValue)
        npMinute.setFormatter { i -> String.format(Locale.US, "%02d", i) }

        npHour.setOnValueChangedListener { _, _, newVal ->
            npMinute.maxValue = if (newVal == maxHours) maxMins.coerceAtLeast(1) else 59
        }

        val dialog = AlertDialog.Builder(context)
            .setTitle("调整特权时长")
            .setMessage("当前时长: ${currentDuration}分钟\n请选择新的总时长")
            .setView(view)
            .setPositiveButton("确认", null)
            .setNegativeButton("取消", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val newDuration = npHour.value * 60 + npMinute.value
                if (newDuration <= 0) {
                    Toast.makeText(context, "时长必须大于0", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                // [CAT-B Fix] 调整窗口同样需要 clamp 到 maxEffective 边界（如 23:00 边界）
                val adjustClamp = clampRequestedToEffectiveMinutes(newDuration, maxEffective)
                if (adjustClamp.didClamp) {
                    Toast.makeText(
                        context,
                        buildPrivilegeAutoClampToast(
                            clampedMinutes = adjustClamp.actualMinutes,
                            boundaryLabel = getPrivilegeBoundaryLabel(context)
                        ),
                        Toast.LENGTH_LONG
                    ).show()
                }
                val success = CreditManager.adjustWeeklyPrivilegeDuration(context, adjustClamp.actualMinutes)
                if (success) {
                    Toast.makeText(context, "特权时长已调整为 ${adjustClamp.actualMinutes} 分钟", Toast.LENGTH_SHORT).show()
                    updateQuotaDisplay()
                    updateTimerText()
                    dialog.dismiss()
                } else {
                    Toast.makeText(context, "调整失败，可能已超过5分钟窗口", Toast.LENGTH_SHORT).show()
                }
            }
        }

        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.setTextColor(0xFF2E7D32.toInt())
        dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(0xFFC62828.toInt())
    }

    private fun updateQuotaDisplay() {
        val ctx = context ?: return
        val currentSeg = TimeRuleManager.getCurrentSegment(ctx)
        val now = System.currentTimeMillis()

        updateWeeklyPrivilegeEntryDisplay()

        if (currentSeg == null) {
            val prevSeg = TimeRuleManager.getPreviousSegment(ctx)
            val nextSeg = TimeRuleManager.getNextSegment(ctx)
            val prevEnd = prevSeg?.let { String.format("%02d:%02d", it.endHour, it.endMinute) } ?: "00:00"
            val nextStart = nextSeg?.let { String.format("%02d:%02d", it.startHour, it.startMinute) } ?: "24:00"
            tvCurrentSegmentRange?.text = "自由空档期 ($prevEnd - $nextStart)"

            tvStatTimeBig?.visibility = View.VISIBLE
            tvStatTimeDetail?.visibility = View.VISIBLE
            tvUsableCountBig?.visibility = View.VISIBLE
            tvSurplusBorrowLine?.visibility = View.GONE

            val remaining = CreditManager.getRemainingMinutes(ctx)
            val usableRemaining = CreditManager.getRemainingUsableCountHalfUnits(ctx)

            tvStatTimeBig?.text = "${formatMinutesForHome(remaining)}min"
            tvUsableCountBig?.text = "${formatUsableCountHalfUnits(usableRemaining)}次"

            val timeDetail = if (remaining > 0.0) "时间 ${formatMinutesForHome(remaining)}min(前期结余)" else "时间 0min"
            val countDetail = if (usableRemaining > 0) "次数 ${formatUsableCountHalfUnits(usableRemaining)}次(前期结余)" else "次数 0次"
            tvStatTimeDetail?.text = "$timeDetail  |  $countDetail"

            tvRestCountdownInCard?.apply {
                if (CreditManager.isFreeTime(ctx)) {
                    visibility = View.VISIBLE
                    val restEnd = CreditManager.getFreeTimeEnd(ctx)
                    val remainSec = ((restEnd - now + 999) / 1000).toInt()
                    text = "🟢 休息中 剩余 ${remainSec}s"
                } else {
                    visibility = View.GONE
                }
            }

            if (nextSeg != null) {
                val range = "${String.format("%02d:%02d", nextSeg.startHour, nextSeg.startMinute)} - ${String.format("%02d:%02d", nextSeg.endHour, nextSeg.endMinute)}"
                val nextLimit = nextSeg.limitMins
                val nextCountLimit = nextSeg.getEffectiveUsableCountLimit()
                // 🔴 [BugFix] 空档期：若上一段是逻辑最后一段，则下一段属于下一个业务日，不可借用
                val prevSegForBizCheck = TimeRuleManager.getPreviousSegment(ctx)
                val isNextDifferentBizDay = prevSegForBizCheck != null && TimeRuleManager.isLastLogicalSegment(ctx, TimeRuleManager.getTimeSegments(ctx).indexOf(prevSegForBizCheck))
                val borrowRatio = TimeRuleManager.getBorrowPercentage(ctx)
                val (maxBorrowMins, maxBorrowCount) = if (isNextDifferentBizDay) Pair(0.0, 0) else computeMaxBorrowableForNextSegment(nextLimit, nextCountLimit, borrowRatio)
                var infoText = "下一段 | ${nextSeg.name} ($range)"
                infoText += "\n时长 ${nextLimit}min  |  次数 ${nextCountLimit}次"
                infoText += if (isNextDifferentBizDay) "\n不可借用（明日额度）" else "\n最多可借：时长 ${formatMinutesForHome(maxBorrowMins)}min  |  次数 ${maxBorrowCount}次"
                tvNextSegmentInfo?.text = infoText
                tvNextSegmentInfo?.visibility = View.VISIBLE
            } else {
                tvNextSegmentInfo?.text = "今日时段已结束"
                tvNextSegmentInfo?.visibility = View.VISIBLE
            }
            return
        } else {
            // Normal Segment Mode — mirror lock screen dual-card style
            tvCurrentSegmentRange?.text = "${currentSeg.name} (${currentSeg.formatTimeRange()})"


            tvStatTimeBig?.visibility = View.VISIBLE
            tvStatTimeDetail?.visibility = View.VISIBLE
            tvUsableCountBig?.visibility = View.VISIBLE

            val remaining = CreditManager.getRemainingMinutes(ctx)
            val prevRemaining = CreditManager.getAllPreviousSegmentsRemainingMinutes(ctx)
            val currentLimitMins = TimeRuleManager.getMinutesLimitForSegment(ctx, currentSeg)
            val borrowedMinsFromNext = CreditManager.getBorrowedMinutesFromNext(ctx)

            // Time big number — 当前可用
            tvStatTimeBig?.text = "${formatMinutesForHome(remaining)}min"

            // Count column
            val usableLimit = currentSeg.getEffectiveUsableCountLimit()
            val usableUsed = CreditManager.getUsedUsableCountForCurrentSegment(ctx)
            val usableBorrowed = CreditManager.getBorrowedUsableCountFromNext(ctx)
            val usablePrevRemaining = CreditManager.getAllPreviousSegmentsRemainingUsableCount(ctx)
            // 🐛 [BugFix 2026-07-01] 借用后本应立即消耗的 principal 不应再加进 remaining；
            //     已借总额另起一行 "已借：时长+次数" 显示，避免重复计入。
            val usableRemaining = computeCurrentUsableRemaining(usableLimit, usableUsed, usablePrevRemaining)

            tvUsableCountBig?.text = "${formatUsableCountHalfUnits(usableRemaining)}次"

            // Detail row: use the actual carry-in, never the previous segment's full limit.
            val carry = CreditManager.captureSurplusCarrySnapshot(ctx, currentSeg)
            val timeModel = calculateQuotaDetail(
                remaining,
                currentLimitMins.toDouble(),
                prevRemaining,
                carry.timeTotal
            )
            val countModel = calculateQuotaDetail(
                usableRemaining.toDouble(),
                usableLimit.toDouble(),
                usablePrevRemaining.toDouble(),
                carry.countTotal.toDouble()
            )
            val timeDetail = if (timeModel.surplusTotal > 0.0) {
                "时间 ${formatMinutesForHome(timeModel.currentRemaining)}/${currentLimitMins} + ${formatMinutesForHome(timeModel.surplusRemaining)}/${formatMinutesForHome(timeModel.surplusTotal)}min(结余)"
            } else {
                "时间 ${formatMinutesForHome(remaining)}/${currentLimitMins}min"
            }
            val countDetail = if (countModel.surplusTotal > 0.0) {
                "次数 ${formatUsableCountHalfUnits(countModel.currentRemaining.toInt())}/${formatUsableCountHalfUnits(usableLimit)} + ${formatUsableCountHalfUnits(countModel.surplusRemaining.toInt())}/${formatUsableCountHalfUnits(countModel.surplusTotal.toInt())}次(结余)"
            } else {
                "次数 ${formatUsableCountHalfUnits(usableRemaining)}/${formatUsableCountHalfUnits(usableLimit)}次"
            }
            tvStatTimeDetail?.text = "$timeDetail  |  $countDetail"

            // 借用行：仅显示借了什么
            val borrowedMins = if (borrowedMinsFromNext > 0) borrowedMinsFromNext else 0.0
            val borrowedCount = if (usableBorrowed > 0) usableBorrowed else 0

            if (borrowedMins > 0 || borrowedCount > 0) {
                val parts = mutableListOf<String>()
                if (borrowedMins > 0) parts.add("时长 ${formatMinutesForHome(borrowedMins)}min")
                if (borrowedCount > 0) parts.add("次数 ${formatUsableCountHalfUnits(borrowedCount)}次")
                tvSurplusBorrowLine?.text = "已借：${parts.joinToString(" + ")}"
                tvSurplusBorrowLine?.setTextColor(android.graphics.Color.parseColor("#FFB74D"))
                tvSurplusBorrowLine?.visibility = View.VISIBLE
            } else {
                tvSurplusBorrowLine?.visibility = View.GONE
            }

            // Rest countdown in card
            tvRestCountdownInCard?.apply {
                if (CreditManager.isFreeTime(ctx)) {
                    visibility = View.VISIBLE
                    val restEnd = CreditManager.getFreeTimeEnd(ctx)
                    val remainSec = ((restEnd - now + 999) / 1000).toInt()
                    text = "🟢 休息中 剩余 ${remainSec}s"
                } else {
                    visibility = View.GONE
                }
            }
        }

        // Next Segment Info — 6 行统一格式含实际生效
        val nextSegObj = TimeRuleManager.getNextSegment(ctx)
        if (nextSegObj != null) {
            val range = "${String.format("%02d:%02d", nextSegObj.startHour, nextSegObj.startMinute)} - ${String.format("%02d:%02d", nextSegObj.endHour, nextSegObj.endMinute)}"
            val nextLimit = nextSegObj.limitMins
            val nextCountLimit = nextSegObj.getEffectiveUsableCountLimit()

            // 🔴 [BugFix] 当前段是逻辑最后一段时，下一段属于下一个业务日，不可借用
            val curIdx = TimeRuleManager.getCurrentSegmentIndex(ctx)
            val isNextDifferentBizDay = curIdx != -1 && TimeRuleManager.isLastLogicalSegment(ctx, curIdx)

            // 行3: 最多可借
            val borrowRatio = TimeRuleManager.getBorrowPercentage(ctx)
            val (maxBorrowMins, maxBorrowCount) = if (isNextDifferentBizDay) Pair(0.0, 0) else computeMaxBorrowableForNextSegment(nextLimit, nextCountLimit, borrowRatio)

            var infoText = "下一段 | ${nextSegObj.name} ($range)"
            infoText += "\n时长 ${nextLimit}min  |  次数 ${formatUsableCountHalfUnits(nextCountLimit)}次"

                infoText += if (isNextDifferentBizDay) "\n不可借用（明日额度）" else "\n最多可借：时长 ${formatMinutesForHome(maxBorrowMins)}min  |  次数 ${formatUsableCountHalfUnits(maxBorrowCount)}次"

            val borrowedMinsFromNext = CreditManager.getBorrowedMinutesFromNext(ctx)
            val borrowedCountFromNext = CreditManager.getBorrowedUsableCountFromNext(ctx)
            if (borrowedMinsFromNext > 0 || borrowedCountFromNext > 0) {
                infoText += "\n已预支：时长 ${formatMinutesForHome(borrowedMinsFromNext)}min  |  次数 ${formatUsableCountHalfUnits(borrowedCountFromNext)}次"
                val penaltyMins = borrowedMinsFromNext * 2.0
                val penaltyCount = borrowedCountFromNext * 2
                infoText += "\n惩罚扣除 (×2) ：-${formatMinutesForHome(penaltyMins)}min  |  -${formatUsableCountHalfUnits(penaltyCount)}次"
                val effectiveMins = (nextLimit - penaltyMins).coerceAtLeast(0.0)
                val effectiveCount = (nextCountLimit - penaltyCount).coerceAtLeast(0)
                infoText += "\n实际生效：时长 ${formatMinutesForHome(effectiveMins)}min  |  次数 ${formatUsableCountHalfUnits(effectiveCount)}次"
            }

            tvNextSegmentInfo?.text = infoText
            tvNextSegmentInfo?.visibility = View.VISIBLE
        } else {
            tvNextSegmentInfo?.text = "今日时段已结束"
            tvNextSegmentInfo?.visibility = View.VISIBLE
        }

    }

    private fun formatMinutesForHome(minutes: Double): String {
        val rounded = kotlin.math.round(minutes * 10.0) / 10.0
        return if (kotlin.math.abs(rounded - rounded.toInt()) < 0.0001) {
            rounded.toInt().toString()
        } else {
            String.format(Locale.US, "%.1f", rounded)
        }
    }

    private fun updateWeeklyPrivilegeEntryDisplay() {
        val ctx = context ?: return
        if (!shouldShowWeeklyPrivilegeEntryInHome()) {
            cardWeeklyPrivilegeEntry?.visibility = View.GONE
            return
        }

        cardWeeklyPrivilegeEntry?.visibility = View.VISIBLE
        val isActive = CreditManager.isWeeklyPrivilegeSessionActive(ctx)
        val countLimit = CreditManager.getWeeklyPrivilegeLimit(ctx)
        val countUsed = CreditManager.getWeeklyPrivilegeCount(ctx)
        val remainingCount = (countLimit - countUsed).coerceAtLeast(0)
        val durationLimit = CreditManager.getWeeklyPrivilegeDurationLimit(ctx)
        val durationUsed = CreditManager.getWeeklyPrivilegeDurationUsed(ctx)
        val remainingDuration = (durationLimit - durationUsed).coerceAtLeast(0)
        val unavailableReason = if (isActive) null else CreditManager.getWeeklyPrivilegeUnavailableReason(ctx)

        tvWeeklyPrivilegeEntryStatus?.text = buildWeeklyPrivilegeEntryStatusText(
            remainingDuration = remainingDuration,
            durationLimit = durationLimit,
            remainingCount = remainingCount,
            countLimit = countLimit,
            isActive = isActive,
            unavailableReason = unavailableReason
        )

        btnWeeklyPrivilegeEntry?.text = buildWeeklyPrivilegeEntryButtonText(
            remainingDuration = remainingDuration,
            remainingCount = remainingCount,
            isActive = isActive,
            unavailableReason = unavailableReason
        )
        btnWeeklyPrivilegeEntry?.isEnabled = isActive || unavailableReason == null
        btnWeeklyPrivilegeEntry?.alpha = if (btnWeeklyPrivilegeEntry?.isEnabled == true) 1.0f else 0.55f
    }
    
    private fun startUiCountdown() {
        uiTimer?.cancel()
        uiTimer = object : CountDownTimer(Long.MAX_VALUE, 1000) {
            private var refreshCounter = 0
            override fun onTick(millisUntilFinished: Long) {
                if (!isAdded) return
                updateTimerText()
                refreshCounter++
                if (refreshCounter >= 3) {
                    refreshCounter = 0
                    updateQuotaDisplay()
                }
            }
            override fun onFinish() {}
        }.start()
    }
    
    private fun updateTimerText() {
        val ctx = context ?: return
        val now = System.currentTimeMillis()
        val tempSnapshot = LockWindowManager.getTemporaryAccessSnapshot(ctx)
        val tempAccessRemainSeconds = tempSnapshot.accessRemainingSeconds
        val tempCooldownRemainSeconds = tempSnapshot.cooldownRemainingSeconds

        // Line 1: Temporary access status
        if (tempAccessRemainSeconds > 0) {
            tvTempAccessStatus?.visibility = View.VISIBLE
            val min = tempAccessRemainSeconds / 60
            val sec = tempAccessRemainSeconds % 60
            tvTempAccessStatus?.text = "\uD83D\uDFE1 临时解锁中 还剩 ${min}min ${sec}s"
        } else if (tempCooldownRemainSeconds > 0) {
            tvTempAccessStatus?.visibility = View.VISIBLE
            tvTempAccessStatus?.text = "\uD83D\uDD12 临时解锁冷却 还剩 ${tempCooldownRemainSeconds}s"
        } else {
            tvTempAccessStatus?.visibility = View.GONE
        }

        // Line 2: Rest / DND status

        val endTime = CreditManager.getFreeTimeEnd(ctx)

        // Card rest countdown tick (every second)
        if (now < endTime) {
            tvRestCountdownInCard?.visibility = View.VISIBLE
            val remainSec = ((endTime - now + 999) / 1000).toInt()
            tvRestCountdownInCard?.text = "🟢 休息中 剩余 ${remainSec}s"
        } else {
            tvRestCountdownInCard?.visibility = View.GONE
        }

        val timeLeft = endTime - System.currentTimeMillis()
        val isPrivilege = CreditManager.isPrivilegeMode(ctx)

        if (timeLeft > 0) {
            val seconds = timeLeft / 1000
            val min = seconds / 60
            val sec = seconds % 60
            cardFreeTime?.visibility = View.VISIBLE
            btnStopFreeTime?.visibility = View.VISIBLE
            val label = if (isPrivilege) "🌟 特权中" else "🟢 休息中"
            tvFreeTimeCountdown?.text = "$label 还剩 ${min} min ${sec} s"
            tvCountdown?.text = label
            tvCountdown?.setTextColor(android.graphics.Color.parseColor("#00AA00"))
        } else {
            // 检查冷却期（休息结束后的冷静期）
            val coolDownEnd = CreditManager.getCoolDownEnd(ctx)
            val coolLeft = coolDownEnd - System.currentTimeMillis()
            if (coolLeft > 0) {
                val coolSec = coolLeft / 1000
                cardFreeTime?.visibility = View.VISIBLE
                btnStopFreeTime?.visibility = View.GONE
                tvFreeTimeCountdown?.text = "⏳ 休息冷却中 还剩 ${coolSec}s"
                tvCountdown?.text = "⏳ 冷静期"
                tvCountdown?.setTextColor(android.graphics.Color.parseColor("#FF6600"))
            } else {
                cardFreeTime?.visibility = View.GONE
                val isSegment = TimeRuleManager.getCurrentSegment(ctx) != null
                if (!isSegment) {
                    tvCountdown?.text = "🟢 当前状态：自由状态"
                    tvCountdown?.setTextColor(android.graphics.Color.parseColor("#00AA00"))
                } else {
                    tvCountdown?.text = "🔒 当前状态：勿扰锁定中"
                    tvCountdown?.setTextColor(android.graphics.Color.parseColor("#666666"))
                }
            }
        }
    }
    
    private fun updateAdaptationCardVisibility() {
        val ctx = context ?: return
        cardAdaptation?.visibility = if (CreditManager.isInAdaptationPeriod(ctx)) View.VISIBLE else View.GONE
    }

    private fun updateProtectionStatus() {
        val ctx = context ?: return
        val sb = StringBuilder()
        
        val hasUsageStats = hasUsageStatsPermission(ctx)
        sb.append(if (hasUsageStats) "✅ 使用访问权限：已开启\n" else "❌ 使用访问权限：未开启\n")
        
        val hasOverlay = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) Settings.canDrawOverlays(ctx) else true
        sb.append(if (hasOverlay) "✅ 悬浮窗权限：已开启\n" else "❌ 悬浮窗权限：未开启\n")

        val prefs = ctx.getSharedPreferences(CreditManager.PREFS_NAME, Context.MODE_PRIVATE)
        val hasAutoStart = isShortTimeConfirmed(prefs, "auto_start_confirmed")
        sb.append(if (hasAutoStart) "✅ 自启动权限：已确认" else "⚠️ 自启动权限：待确认")

        val needBgPopup = isChineseRomDevice()
        val hasBgPopup = isShortTimeConfirmed(prefs, "bg_popup_confirmed")
        if (needBgPopup) {
            sb.append("\n")
            sb.append(if (hasBgPopup) "✅ 后台弹出界面权限：已确认" else "⚠️ 后台弹出界面权限：待确认")
        }

        // 根据需求：主页不再展示「允许后台耗电 / 关联启动 / 电池优化豁免」状态
    
        tvProtectionStatus?.text = sb.toString()

        val permState = getHomePermissionState(ctx)
        val permsDone = permState.allGranted
        if (permsDone) {
            // 权限全了，整个状态卡片都不需要展示，保持主页极致简洁
            cardPermissionStatus?.visibility = View.GONE
            tvStatus?.text = "状态：权限已就绪 (全面防护中)"
            tvStatus?.setTextColor(ctx.getColor(android.R.color.holo_green_dark))
            tvProtectionStatus?.visibility = View.GONE
            btnPermission?.visibility = View.GONE
        } else {
            cardPermissionStatus?.visibility = View.VISIBLE
            tvStatus?.text = "状态：需设置权限 ⚠️"
            tvStatus?.setTextColor(ctx.getColor(android.R.color.holo_red_dark))
            tvProtectionStatus?.visibility = View.VISIBLE
            btnPermission?.visibility = View.VISIBLE
            btnPermission?.text = "去设置权限"
            btnPermission?.isEnabled = true
            btnPermission?.alpha = 1.0f
        }
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

    private fun checkOverlayPermission() {
        val ctx = context ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(ctx)) {
            val prefs = ctx.getSharedPreferences(PERMISSION_PREFS_NAME, Context.MODE_PRIVATE)
            val hasBeenPrimed = prefs.getBoolean(KEY_OVERLAY_PROMPT_PRIMED, false)
            if (shouldSuppressFirstMissingPermissionPrompt(true, hasBeenPrimed)) {
                prefs.edit().putBoolean(KEY_OVERLAY_PROMPT_PRIMED, true).apply()
                return
            }
            val lastAskTime = prefs.getLong("last_overlay_permission_ask", 0)
            val now = System.currentTimeMillis()
            if (now - lastAskTime < 24 * 60 * 60 * 1000L) return
            prefs.edit().putLong("last_overlay_permission_ask", now).apply()

            activePermissionDialog = AlertDialog.Builder(ctx)
                .setTitle("缺少悬浮窗权限")
                .setMessage("知止需要「显示在其他应用上层」权限才能显示锁屏界面。")
                .setPositiveButton("去开启") { _, _ ->
                    try {
                        startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, android.net.Uri.parse("package:${ctx.packageName}")))
                    } catch (e: Exception) {
                        Toast.makeText(ctx, "无法打开设置页面", Toast.LENGTH_SHORT).show()
                    }
                }
                .setNegativeButton("稍后", null)
                .setCancelable(false)
                .show()
        }
    }

    private fun checkUsageStatsPermission() {
        val ctx = context ?: return
        if (hasUsageStatsPermission(ctx)) return

        val prefs = ctx.getSharedPreferences(PERMISSION_PREFS_NAME, Context.MODE_PRIVATE)
        val hasBeenPrimed = prefs.getBoolean(KEY_USAGE_STATS_PROMPT_PRIMED, false)
        if (shouldSuppressFirstMissingPermissionPrompt(true, hasBeenPrimed)) {
            prefs.edit().putBoolean(KEY_USAGE_STATS_PROMPT_PRIMED, true).apply()
            return
        }
        val lastAskTime = prefs.getLong("last_usage_stats_permission_ask", 0L)
        val now = System.currentTimeMillis()
        if (now - lastAskTime < 24 * 60 * 60 * 1000L) return
        prefs.edit().putLong("last_usage_stats_permission_ask", now).apply()

        activePermissionDialog = AlertDialog.Builder(ctx)
            .setTitle("缺少使用情况访问权限")
            .setMessage("知止需要「使用情况访问」权限来识别前台应用并执行拦截。")
            .setPositiveButton("去开启") { _, _ ->
                try {
                    startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
                } catch (_: Exception) {
                    Toast.makeText(ctx, "无法打开设置页面", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("稍后", null)
            .setCancelable(false)
            .show()
    }

    internal fun checkAccessibilityPermission() {
        val ctx = context ?: return
        val prefs = ctx.getSharedPreferences(CreditManager.PREFS_NAME, Context.MODE_PRIVATE)
        val nowEnabled = isAccessibilityServiceEnabled()
        // 🚀 不仅检查设置中是否勾选，还要检查服务是否真正活跃（没崩溃）
        val isActuallyActive = nowEnabled && (CoreService.isServiceActive || CoreService.hasRecentAccessibilitySignal())
        val wasEnabled = prefs.getBoolean("a11y_last_enabled", false)

        if (isActuallyActive) {
            if (!wasEnabled) {
                prefs.edit().putBoolean("a11y_last_enabled", true).apply()
            }
            prefs.edit().putLong(PREF_A11Y_LOST_DIALOG_LAST_SHOWN, 0L).apply()
            return
        }

        val hasEnabledAntiUninstall = prefs.getBoolean("has_enabled_anti_uninstall", false)
        if (wasEnabled || hasEnabledAntiUninstall) {
            val now = System.currentTimeMillis()
            val lastShown = prefs.getLong(PREF_A11Y_LOST_DIALOG_LAST_SHOWN, 0L)
            if (now - lastShown < A11Y_LOST_DIALOG_COOLDOWN_MS) {
                return
            }
            prefs.edit().putLong(PREF_A11Y_LOST_DIALOG_LAST_SHOWN, now).apply()
            val permState = getHomePermissionState(ctx)
            val missingText = if (permState.missingLabels.isNotEmpty()) {
                "\n\n请先补齐主页权限：${permState.missingLabels.joinToString("、")}"
            } else {
                ""
            }
            // 🚀 区分"已关闭"和"已开启但崩溃"两种场景
            val isCrashed = nowEnabled && !isActuallyActive
            val dialogTitle = if (isCrashed) "🛡️ 无障碍服务异常" else "🛡️ 防护已失效"
            val dialogMsg = if (isCrashed) {
                "检测到无障碍服务虽然已开启但无法正常工作（可能已崩溃），请先关闭再重新开启。\n\n如需排查原因，可选择发送最近日志。$missingText"
            } else {
                "检测到无障碍服务被关闭。请重新开启无障碍服务。\n\n如需排查原因，可选择发送最近日志。$missingText"
            }
            val positiveBtnText = if (isCrashed) "去重新开关" else "去重新开启"
            val dialog = AlertDialog.Builder(ctx)
                .setTitle(dialogTitle)
                .setMessage(dialogMsg)
                .setPositiveButton(positiveBtnText, null)
                .setNeutralButton("发送日志") { _, _ ->
                    LogExporter.showSendLogOptions(ctx, "发送最近日志", "请选择要发送的时间范围")
                }
                .setNegativeButton("稍后", null)
                .create()
            dialog.setOnShowListener {
                val btn = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                if (permState.missingLabels.isNotEmpty()) {
                    btn.isEnabled = false
                    btn.text = "先补齐主页权限"
                } else {
                    btn.setOnClickListener {
                        try {
                            com.zhizhi.app.protection.AccessibilityProtection.markManualSettingsLaunch()
                            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                        } catch (e: Exception) {
                            Log.w("HomeFragment", "Failed to open accessibility settings", e)
                        }
                    }
                }
            }
            dialog.show()
            activePermissionDialog = dialog
        }
    }

    /**
    * 用户从系统设置返回后，自动识别并标记待确认权限：
    * 1) 可系统检测的权限：按真实状态标记；
    * 2) ROM 私有且无法稳定检测的权限：若命中“去设置后返回”引导痕迹，自动回填。
     */
    private fun processPendingPermissionAutoMark() {
        val ctx = context ?: return
        val prefs = ctx.getSharedPreferences(CreditManager.PREFS_NAME, Context.MODE_PRIVATE)
        val pending = prefs.getStringSet(PREF_PENDING_CONFIRM_KEYS, emptySet())?.toMutableSet() ?: mutableSetOf()
        if (pending.isEmpty()) return

        val newlyMarked = mutableListOf<String>()
        val keepPending = mutableSetOf<String>()

        for (key in pending) {
            if (prefs.getBoolean(key, false)) continue

            val detected = detectPermissionStateForPendingKey(ctx, key)
            val hasRecentGuidedVisit =
                detected == null && ManualPermissionRecoveryManager.hasRecentGuidedVisit(ctx, key)

            if (shouldAutoMarkPendingPermission(detected, hasRecentGuidedVisit)) {
                markPermissionConfirmed(prefs, key)
                ManualPermissionRecoveryManager.clearSettingsVisit(ctx, key)
                newlyMarked.add(permissionLabelForKey(key))
            } else {
                when (detected) {
                    false -> {
                        ManualPermissionRecoveryManager.clearSettingsVisit(ctx, key)
                        keepPending.add(key)
                    }
                    null -> {
                        keepPending.add(key)
                    }
                    else -> {
                        keepPending.add(key)
                    }
                }
            }
        }

        prefs.edit().putStringSet(PREF_PENDING_CONFIRM_KEYS, keepPending).apply()

        if (newlyMarked.isNotEmpty()) {
            Toast.makeText(ctx, "已自动识别并更新：${newlyMarked.joinToString("、")}", Toast.LENGTH_SHORT).show()
        }

        updateProtectionStatus()
        updateSetupGuide()
    }

    private fun maybePromptManualPermissionRecovery() {
        val ctx = context ?: return
        if (!isChineseRomDevice()) return
        val prefs = ctx.getSharedPreferences(CreditManager.PREFS_NAME, Context.MODE_PRIVATE)

        val autoStartMissing = !isShortTimeConfirmed(prefs, "auto_start_confirmed")
        if (autoStartMissing) {
            showManualPermissionConfirmDialog("auto_start_confirmed")
            return
        }

        val bgPopupMissing = !isShortTimeConfirmed(prefs, "bg_popup_confirmed")
        if (bgPopupMissing) {
            showManualPermissionConfirmDialog("bg_popup_confirmed")
            return
        }

        val assocMissing = !isShortTimeConfirmed(prefs, "assoc_start_confirmed")
        if (assocMissing) {
            showManualPermissionConfirmDialog("assoc_start_confirmed")
        }
    }

    private fun showManualPermissionConfirmDialog(permissionKey: String, forceFromGuidedReturn: Boolean = false) {
        val ctx = context ?: return
        val prefs = ctx.getSharedPreferences(CreditManager.PREFS_NAME, Context.MODE_PRIVATE)

        if (!forceFromGuidedReturn) {
            val primedKey = "${PREF_MANUAL_PERMISSION_PROMPT_PRIMED}_$permissionKey"
            val hasBeenPrimed = prefs.getBoolean(primedKey, false)
            if (shouldSuppressFirstMissingPermissionPrompt(true, hasBeenPrimed)) {
                prefs.edit().putBoolean(primedKey, true).apply()
                return
            }

            val now = System.currentTimeMillis()
            val lastAsk = prefs.getLong("${PREF_MANUAL_PERMISSION_LAST_ASK}_$permissionKey", 0L)
            if (now - lastAsk < MANUAL_PERMISSION_ASK_COOLDOWN_MS) return
            prefs.edit().putLong("${PREF_MANUAL_PERMISSION_LAST_ASK}_$permissionKey", now).apply()
        }

        val label = permissionLabelForKey(permissionKey)
        val detectedNow = detectPermissionStateForPendingKey(ctx, permissionKey)
        val allowManualConfirm = shouldAllowManualFallbackConfirm(permissionKey, detectedNow)
        val fastFeedbackNotice = if (permissionKey == "auto_start_confirmed" || permissionKey == "bg_popup_confirmed") {
            "\n\n完成设置后返回 App，状态会快速自动更新。"
        } else {
            ""
        }
        val stickyConfirmNotice = "\n\n如果你已经在系统里开启，但 App 仍未识别，可以直接点“我已开启”完成一次性确认；确认后后续不会再反复要求。"
        val detectionNotice = when (detectedNow) {
            false -> "\n\n系统当前判定它可能未开启，但部分机型会误判。"
            null -> "\n\n当前机型无法稳定读取该权限状态。"
            else -> ""
        }

        val builder = AlertDialog.Builder(ctx)
            .setTitle("检测到权限可能未生效")
            .setMessage(
                if (allowManualConfirm) {
                    "$label 可能影响防护稳定性。$detectionNotice\n\n请先去系统设置确认是否已开启。$fastFeedbackNotice$stickyConfirmNotice"
                } else {
                    "$label 当前可由系统自动检测状态，暂不提供手动确认。\n\n请先去设置开启，返回后由知止自动识别。"
                }
            )
            .setPositiveButton("去设置") { _, _ ->
                launchManualPermissionSettings(permissionKey)
                addPendingPermissionConfirm(permissionKey, markSettingsLaunch = true)
            }
            .setNegativeButton("稍后", null)
            .setCancelable(false)

        if (allowManualConfirm) {
            builder.setNeutralButton("我已开启") { _, _ ->
                markPermissionConfirmed(prefs, permissionKey)
                val pending = prefs.getStringSet(PREF_PENDING_CONFIRM_KEYS, emptySet())?.toMutableSet() ?: mutableSetOf()
                pending.remove(permissionKey)
                prefs.edit().putStringSet(PREF_PENDING_CONFIRM_KEYS, pending).apply()
                updateProtectionStatus()
                updateSetupGuide()
            }
        }

        activePermissionDialog = builder.show()
    }

    private fun buildPermissionSettingsIntent(key: String): Intent? {
        val ctx = context ?: return null
        return when (key) {
            "auto_start_confirmed",
            "bg_popup_confirmed",
            "assoc_start_confirmed" -> buildManualPermissionSettingsIntents(ctx).firstOrNull()
            else -> null
        }
    }
    private fun buildManualPermissionSettingsIntents(ctx: Context): List<Intent> {
        val intents = mutableListOf<Intent>()
        val manufacturer = Build.MANUFACTURER.lowercase()

        if (manufacturer.contains("vivo") || manufacturer.contains("iqoo")) {
            intents.add(
                Intent().setComponent(
                    android.content.ComponentName(
                        "com.vivo.permissionmanager",
                        "com.vivo.permissionmanager.activity.SoftPermissionDetailActivity"
                    )
                ).apply {
                    putExtra("packagename", ctx.packageName)
                    putExtra("packageName", ctx.packageName)
                    putExtra("pkg", ctx.packageName)
                }
            )
        }

        intents.add(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:${ctx.packageName}")))
        return intents
    }

    private fun launchManualPermissionSettings(permissionKey: String) {
        val ctx = context ?: return
        val intents = when (permissionKey) {
            "auto_start_confirmed", "bg_popup_confirmed", "assoc_start_confirmed" -> buildManualPermissionSettingsIntents(ctx)
            else -> emptyList()
        }

        for (intent in intents) {
            try {
                if (intent.resolveActivity(ctx.packageManager) != null) {
                    startActivity(intent)
                    return
                }
            } catch (_: Exception) {
            }
        }
    }


    /**
     * 返回值：
     * - true: 已检测为开启
     * - false: 已检测为未开启
     * - null: 无法可靠检测（ROM 私有开关）
     */
    private fun detectPermissionStateForPendingKey(ctx: Context, key: String): Boolean? {
        return when (key) {
            "bg_battery_confirmed" -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    val pm = ctx.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
                    pm.isIgnoringBatteryOptimizations(ctx.packageName)
                } else {
                    true
                }
            }
            "auto_start_confirmed" -> RomPermissionDetector.detectManualPermission(
                ctx,
                RomPermissionDetector.ManualPermission.AUTO_START
            )
            "bg_popup_confirmed" -> RomPermissionDetector.detectManualPermission(
                ctx,
                RomPermissionDetector.ManualPermission.BG_POPUP
            )
            "assoc_start_confirmed" -> RomPermissionDetector.detectManualPermission(
                ctx,
                RomPermissionDetector.ManualPermission.ASSOC_START
            )
            else -> null
        }
    }

    private fun syncDetectedManualPermissionsOnResume() {
        val ctx = context ?: return
        val prefs = ctx.getSharedPreferences(CreditManager.PREFS_NAME, Context.MODE_PRIVATE)
        val manualKeys = listOf("auto_start_confirmed", "bg_popup_confirmed", "assoc_start_confirmed")

        for (key in manualKeys) {
            val detected = detectPermissionStateForPendingKey(ctx, key)
            when (detected) {
                true -> {
                    if (!isShortTimeConfirmed(prefs, key)) {
                        markPermissionConfirmed(prefs, key)
                    }
                    ManualPermissionRecoveryManager.clearSettingsVisit(ctx, key)
                }
                false -> {
                    // 保留用户已确认状态，避免 ROM 误检导致反复要求确认。
                }
                null -> {
                    // 若系统无法检测，不主动篡改现有确认状态。
                }
            }
        }
    }

    private fun addPendingPermissionConfirm(key: String, markSettingsLaunch: Boolean = false) {
        val ctx = context ?: return
        val prefs = ctx.getSharedPreferences(CreditManager.PREFS_NAME, Context.MODE_PRIVATE)
        val pending = prefs.getStringSet(PREF_PENDING_CONFIRM_KEYS, emptySet())?.toMutableSet() ?: mutableSetOf()
        pending.add(key)
        prefs.edit().putStringSet(PREF_PENDING_CONFIRM_KEYS, pending).apply()
        if (markSettingsLaunch) {
            ManualPermissionRecoveryManager.recordSettingsVisit(ctx, key)
        }
    }

    private fun permissionLabelForKey(key: String): String {
        return when (key) {
            "auto_start_confirmed" -> "自启动权限"
            "bg_popup_confirmed" -> "后台弹出界面权限"
            "bg_battery_confirmed" -> "允许后台耗电"
            "assoc_start_confirmed" -> "关联启动权限"
            else -> key
        }
    }

    private fun permissionConfirmedAtKey(key: String): String? {
        return when (key) {
            "auto_start_confirmed" -> "auto_start_confirmed_at"
            "bg_popup_confirmed" -> "bg_popup_confirmed_at"
            "assoc_start_confirmed" -> "assoc_start_confirmed_at"
            else -> null
        }
    }

    private fun markPermissionConfirmed(prefs: android.content.SharedPreferences, key: String) {
        val editor = prefs.edit().putBoolean(key, true)
        val confirmedAtKey = permissionConfirmedAtKey(key)
        if (confirmedAtKey != null) {
            editor.putLong(confirmedAtKey, System.currentTimeMillis())
        }
        editor.apply()
    }

    private fun isShortTimeConfirmed(prefs: android.content.SharedPreferences, key: String): Boolean {
        if (prefs.getBoolean(key, false)) {
            return true
        }

        val ctx = context
        if (ctx != null) {
            val detected = detectPermissionStateForPendingKey(ctx, key)
            when (detected) {
                true -> {
                    markPermissionConfirmed(prefs, key)
                    ManualPermissionRecoveryManager.clearSettingsVisit(ctx, key)
                    return true
                }
                false -> {
                    // 若当前仍未确认且系统明确判定未开启，则继续视为未确认。
                }
                null -> {
                    val hasRecentGuidedVisit = ManualPermissionRecoveryManager.hasRecentGuidedVisit(ctx, key)
                    if (shouldAutoMarkPendingPermission(detected, hasRecentGuidedVisit)) {
                        markPermissionConfirmed(prefs, key)
                        ManualPermissionRecoveryManager.clearSettingsVisit(ctx, key)
                        return true
                    }
                }
            }
        }

        return false
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val ctx = context ?: return false
        val enabledServices = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        return enabledServices?.contains(ctx.packageName) == true
    }

    private fun showProtectionDialog() {
        val ctx = context ?: return
        val layout = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(50, 40, 50, 40)
        }

        fun addItem(name: String, isGranted: Boolean, grantedLabel: String = "已开启", onLongClick: (() -> Unit)? = null, action: () -> Unit) {
            if (isGranted) {
                val row = android.widget.LinearLayout(ctx).apply {
                    orientation = android.widget.LinearLayout.HORIZONTAL
                    setPadding(0, 8, 0, 8)
                    gravity = android.view.Gravity.CENTER_VERTICAL
                }
                val tv = TextView(ctx).apply {
                    text = "✅ $name：$grantedLabel"
                    if (onLongClick != null) {
                        text = "✅ $name：$grantedLabel (长按重测)"
                    }
                    textSize = 15f
                    setTextColor(android.graphics.Color.parseColor("#2E7D32"))
                    layoutParams = android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                }
                if (onLongClick != null) {
                    row.setOnLongClickListener { 
                        onLongClick()
                        true 
                    }
                }
                row.addView(tv)
                layout.addView(row)
            } else {
                val btn = Button(ctx).apply {
                    text = "👉 去设置：$name"
                    textSize = 14f
                    setTextColor(android.graphics.Color.WHITE)
                    setBackgroundColor(android.graphics.Color.parseColor("#1976D2"))
                    setOnClickListener { action() }
                }
                layout.addView(btn)
            }
        }

        val prefs = ctx.getSharedPreferences(CreditManager.PREFS_NAME, Context.MODE_PRIVATE)

        fun addUndetectablePermissionItem(
            name: String,
            key: String,
            hint: String,
            linkedPendingKeys: List<String> = emptyList(),
            onLongClick: (() -> Unit)? = null
        ) {
            val granted = isShortTimeConfirmed(prefs, key)
            if (granted) {
                addItem(name, true, grantedLabel = "已确认", onLongClick = onLongClick) {}
                return
            }

            val row = android.widget.LinearLayout(ctx).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                setPadding(0, 8, 0, 8)
                gravity = android.view.Gravity.CENTER_VERTICAL
            }

            val btn = Button(ctx).apply {
                text = "👉 去设置：$name"
                textSize = 13f
                setTextColor(android.graphics.Color.WHITE)
                setBackgroundColor(android.graphics.Color.parseColor("#1976D2"))
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    0,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                    1f
                )
                setOnClickListener {
                    launchManualPermissionSettings(key)
                    Toast.makeText(ctx, hint, Toast.LENGTH_LONG).show()
                    addPendingPermissionConfirm(key, markSettingsLaunch = true)
                    linkedPendingKeys.forEach { linkedKey ->
                        addPendingPermissionConfirm(linkedKey, markSettingsLaunch = true)
                    }
                }
            }

            val check = android.widget.CheckBox(ctx).apply {
                text = "已开启"
                isChecked = false
                setOnCheckedChangeListener { _, checked ->
                    if (checked) {
                        markPermissionConfirmed(prefs, key)
                        val pending = prefs.getStringSet(PREF_PENDING_CONFIRM_KEYS, emptySet())?.toMutableSet() ?: mutableSetOf()
                        pending.remove(key)
                        prefs.edit().putStringSet(PREF_PENDING_CONFIRM_KEYS, pending).apply()
                        updateProtectionStatus()
                        updateSetupGuide()
                    } else {
                        prefs.edit()
                            .remove(key)
                            .remove("${key}_at")
                            .apply()
                        updateProtectionStatus()
                        updateSetupGuide()
                    }
                }
            }

            row.addView(btn)
            row.addView(check)
            if (onLongClick != null) {
                row.setOnLongClickListener {
                    onLongClick()
                    true
                }
            }
            layout.addView(row)
        }

        val hasUsageStats = hasUsageStatsPermission(ctx)
        addItem("使用情况访问权限", hasUsageStats) {
            try {
                val intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    intent.data = android.net.Uri.parse("package:${ctx.packageName}")
                }
                startActivity(intent)
            } catch (e: Exception) {
                try { startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) } catch (e2: Exception) {
                    Log.w("HomeFragment", "Failed to open usage access settings", e2)
                }
            }
        }

        val hasOverlay = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) Settings.canDrawOverlays(ctx) else true
        addItem("悬浮窗权限", hasOverlay) {
            try {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, android.net.Uri.parse("package:${ctx.packageName}")))
            } catch (e: Exception) {
                Log.w("HomeFragment", "Failed to open overlay settings", e)
            }
        }

        // [DO NOT DELETE] 通知权限：Android 13+ 运行时权限，前台服务需要此权限才能显示通知
        // 首次进入不主动弹窗（"只在使用时弹"），用户可从主页权限向导手动触发
        if (Build.VERSION.SDK_INT >= 33) {
            val hasNotification = ctx.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED
            addItem("通知权限", hasNotification) {
                notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        addUndetectablePermissionItem(
            name = "自启动权限",
            key = "auto_start_confirmed",
            hint = "请在权限详情页开启自启动、悬浮窗、后台弹出界面等开关",
            linkedPendingKeys = if (isChineseRomDevice()) listOf("bg_popup_confirmed") else emptyList(),
            onLongClick = {
                prefs.edit().remove("auto_start_confirmed").remove("auto_start_confirmed_at").apply()
                updateProtectionStatus()
                updateSetupGuide()
                Toast.makeText(ctx, "已清除自启动缓存，请重新检测", Toast.LENGTH_SHORT).show()
            }
        )

        if (isChineseRomDevice()) {
            addUndetectablePermissionItem(
                name = "后台弹出界面权限",
                key = "bg_popup_confirmed",
                hint = "请在权限详情页开启后台弹出界面权限",
                onLongClick = {
                    prefs.edit().remove("bg_popup_confirmed").remove("bg_popup_confirmed_at").apply()
                    updateProtectionStatus()
                    updateSetupGuide()
                    Toast.makeText(ctx, "已清除后台弹出权限缓存", Toast.LENGTH_SHORT).show()
                }
            )
        }

        // 根据需求：主页权限弹窗不再提供「允许后台耗电 / 关联启动 / 电池优化豁免」入口

        AlertDialog.Builder(ctx)
            .setTitle("🛡️ 权限设置")
            .setView(layout)
            .setPositiveButton("完成", null)
            .show()
    }

    private fun updateSetupGuide() {
        val ctx = context ?: return
        
        // 1. Check Permissions
        val permState = getHomePermissionState(ctx)
        val permsDone = permState.allGranted
        
        tvIconPerm?.text = if (permsDone) "✅" else "⭕"
        btnGoPerm?.visibility = View.VISIBLE
        btnGoPerm?.isEnabled = !permsDone
        btnGoPerm?.alpha = if (permsDone) 0.4f else 1.0f
        (btnGoPerm as? TextView)?.text = if (permsDone) "权限已齐全" else "去开启 >"

        // 2. Check Blacklist
        val blacklistDone = CreditManager.getBlacklistApps(ctx).isNotEmpty()
        tvIconBlacklist?.text = if (blacklistDone) "✅" else "⭕"
        btnGoBlacklist?.visibility = if (blacklistDone) View.GONE else View.VISIBLE

        // 3. Check DND
        val dndDone = TimeRuleManager.getTimeSegments(ctx).isNotEmpty()
        tvIconDnd?.text = if (dndDone) "✅" else "⭕"
        btnGoDnd?.visibility = if (dndDone) View.GONE else View.VISIBLE

        // Show/Hide Card: Hide if all ESSENTIAL (Perms, Blacklist, DND) are done.
        cardSetupGuide?.visibility = if (permsDone && blacklistDone && dndDone) View.GONE else View.VISIBLE
    }

    private data class HomePermissionState(
        val allGranted: Boolean,
        val missingLabels: List<String>
    )

    private fun getHomePermissionState(ctx: Context): HomePermissionState {
        val hasUsageStats = hasUsageStatsPermission(ctx)
        val hasOverlay = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) Settings.canDrawOverlays(ctx) else true
        val hasNotification = if (Build.VERSION.SDK_INT >= 33) {
            ctx.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED
        } else true
        val prefs = ctx.getSharedPreferences(CreditManager.PREFS_NAME, Context.MODE_PRIVATE)
        val hasAutoStart = isShortTimeConfirmed(prefs, "auto_start_confirmed")
        val needBgPopup = isChineseRomDevice()
        val hasBgPopup = isShortTimeConfirmed(prefs, "bg_popup_confirmed")

        val missing = mutableListOf<String>()
        if (!hasUsageStats) missing.add("使用情况访问")
        if (!hasOverlay) missing.add("悬浮窗")
        if (!hasNotification) missing.add("通知权限")
        if (!hasAutoStart) missing.add("自启动")
        if (needBgPopup && !hasBgPopup) missing.add("后台弹出界面")

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
}
