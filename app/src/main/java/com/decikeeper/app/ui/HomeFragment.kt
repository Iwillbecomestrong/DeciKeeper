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
package com.decikeeper.app.ui

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.CountDownTimer
import java.util.Calendar
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
import com.decikeeper.app.R
import com.decikeeper.app.data.LogExporter
import com.decikeeper.app.data.calculateQuotaDetail
import com.decikeeper.app.data.formatUsableCountHalfUnits
import com.decikeeper.app.manager.AlarmScheduler
import com.decikeeper.app.manager.RecentTasksHideManager
import com.decikeeper.app.manager.AppVisibilityTracker
import com.decikeeper.app.manager.CreditManager
import com.decikeeper.app.manager.ManualPermissionRecoveryManager
import com.decikeeper.app.manager.PermissionManager
import com.decikeeper.app.manager.RomPermissionDetector
import com.decikeeper.app.manager.SettingsNavigator
import com.decikeeper.app.manager.TimeRuleManager
import com.decikeeper.app.manager.getEffectiveUsableCountLimit
import com.decikeeper.app.service.CoreService
import com.decikeeper.app.service.MonitorService
import com.decikeeper.app.test.PermissionTestRunner
import com.decikeeper.app.util.computeCurrentUsableRemaining
import java.util.Locale















class HomeFragment : Fragment() {

    // 🚀 [Request Notification Permission] 使用 registerForActivityResult 替代弃用的 requestPermissions
    private val notificationPermissionLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) {
        updateProtectionStatus()
        updateSetupGuide()
        if (activeProtectionDialog?.isShowing == true) {
            refreshProtectionDialogContent?.invoke()
        }
    }

    companion object {
        const val PREF_SETUP_GUIDE_DISMISSED: String = "setup_guide_dismissed"

        /**
         * 判定初始化设置向导卡片是否应当显示。
         *
         * @param isDismissed 用户是否已主动点击跳过
         * @param permsDone 基础权限是否全部开启
         * @param blacklistDone 是否已设置黑白名单
         * @param dndDone 是否已配置勿扰时段
         * @param alarmDone 是否已建立至少一个挑战闹钟
         * @return true 表示应展示向导卡片，false 表示向导已完成或已被跳过
         */
        fun shouldShowSetupGuide(
            isDismissed: Boolean,
            permsDone: Boolean,
            blacklistDone: Boolean,
            dndDone: Boolean,
            alarmDone: Boolean
        ): Boolean {
            if (isDismissed) return false
            val timeRulesBranchDone = blacklistDone && dndDone
            val functionalBranchDone = timeRulesBranchDone || alarmDone
            return !(permsDone && functionalBranchDone)
        }

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

        internal fun resolveHomeStatusPillModel(
            isResting: Boolean,
            restRemainSeconds: Long,
            tempAccessRemainSeconds: Long,
            tempCooldownRemainSeconds: Long,
            restCooldownRemainSeconds: Long,
            isDndSegment: Boolean
        ): HomeStatusPillModel {
            if (isResting || restRemainSeconds > 0) {
                return HomeStatusPillModel(
                    statusType = HomeSystemStatusType.RESTING,
                    isVisible = false,
                    text = "",
                    colorAttrId = R.attr.zzSuccess
                )
            }
            if (tempAccessRemainSeconds > 0) {
                val min = tempAccessRemainSeconds / 60
                val sec = tempAccessRemainSeconds % 60
                return HomeStatusPillModel(
                    statusType = HomeSystemStatusType.TEMP_ACCESS_ACTIVE,
                    isVisible = true,
                    text = "🟡 临时解锁中 还剩 ${min}min ${sec}s",
                    colorAttrId = R.attr.zzWarning
                )
            }
            val maxCooldown = maxOf(tempCooldownRemainSeconds, restCooldownRemainSeconds)
            if (maxCooldown > 0) {
                val isTempCool = tempCooldownRemainSeconds >= restCooldownRemainSeconds && tempCooldownRemainSeconds > 0
                val text = if (isTempCool) {
                    "🔒 临时解锁冷却 还剩 ${maxCooldown}s"
                } else {
                    "⏳ 休息冷却中 还剩 ${maxCooldown}s"
                }
                return HomeStatusPillModel(
                    statusType = HomeSystemStatusType.COOLDOWN,
                    isVisible = true,
                    text = text,
                    colorAttrId = R.attr.zzWarning
                )
            }
            if (isDndSegment) {
                return HomeStatusPillModel(
                    statusType = HomeSystemStatusType.DND_ACTIVE,
                    isVisible = true,
                    text = "🔒 勿扰中",
                    colorAttrId = R.attr.zzMuted
                )
            }
            return HomeStatusPillModel(
                statusType = HomeSystemStatusType.GAP,
                isVisible = true,
                text = "🍃 空档期",
                colorAttrId = R.attr.zzSuccess
            )
        }
    }

    enum class HomeSystemStatusType {
        RESTING,
        TEMP_ACCESS_ACTIVE,
        COOLDOWN,
        DND_ACTIVE,
        GAP
    }

    data class HomeStatusPillModel(
        val statusType: HomeSystemStatusType,
        val isVisible: Boolean,
        val text: String,
        val colorAttrId: Int
    )

    /**
     * 特权时长自动截断结果。
     */
    internal data class ClampResult(
        val actualMinutes: Int,
        val didClamp: Boolean,
        val originalRequested: Int
    )

    private var cardPermissionStatus: CardView? = null
    private var tvStatus: TextView? = null
    private var tvProtectionStatus: TextView? = null
    
    // Dashboard Controls
    private var tvHomeRefreshTimeBadge: TextView? = null
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
    
    // 休息时间卡片
    private var cardFreeTime: CardView? = null
    private var tvFreeTimeCountdown: TextView? = null
    private var btnStopFreeTime: Button? = null
    private var freeTimeTimer: CountDownTimer? = null

    // 今日闹钟卡片
    private var cardTodayAlarm: CardView? = null
    private var tvHomeAlarmStatusBadge: TextView? = null
    private var tvHomeNextAlarmCountdown: TextView? = null
    private var viewTodayAlarmTimeline: TodayAlarmView? = null

    // 休息冷却紧凑胶囊条
    private var layoutCooldownPill: View? = null
    private var tvCooldownPillText: TextView? = null

    // 适应期卡片
    private var cardAdaptation: CardView? = null
    private var btnEndAdaptation: Button? = null

    // ✈️ [NEW] Setup Guide Views
    private var cardSetupGuide: CardView? = null
    private var btnSetupSkip: View? = null
    private var tvIconPerm: TextView? = null
    private var tvIconBlacklist: TextView? = null
    private var tvIconDnd: TextView? = null
    private var tvIconAlarm: TextView? = null
    private var btnGoPerm: View? = null
    private var btnGoBlacklist: View? = null
    private var btnGoDnd: View? = null
    private var btnGoAlarm: View? = null

    private var uiTimer: CountDownTimer? = null

    // 🚀 [Fix] 防止 WindowLeaked - 保存当前显示的权限检查对话框引用
    private var activePermissionDialog: AlertDialog? = null

    // 🛡️ 权限设置弹窗引用与动态刷新闭包（支持在弹窗展示期间实时刷新权限勾选状态）
    private var activeProtectionDialog: AlertDialog? = null
    private var refreshProtectionDialogContent: (() -> Unit)? = null

    // 🚀 [Fix] 待确认权限队列 (持久化到SP，支持多项权限顺序确认)
    private val PREF_PENDING_CONFIRM_KEYS = "pending_confirm_keys"
    private val PREF_A11Y_LOST_DIALOG_LAST_SHOWN = "a11y_lost_dialog_last_shown"
    private val A11Y_LOST_DIALOG_COOLDOWN_MS = 60 * 60 * 1000L
    private val PREF_MANUAL_PERMISSION_LAST_ASK = "manual_permission_last_ask"
    private val PREF_MANUAL_PERMISSION_PROMPT_PRIMED = "manual_permission_prompt_primed"
    private val MANUAL_PERMISSION_ASK_COOLDOWN_MS = 15 * 60 * 1000L
    private val PERMISSION_PREFS_NAME = "decikeeper_permissions"
    private val KEY_OVERLAY_PROMPT_PRIMED = "overlay_permission_prompt_primed"

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
        androidx.core.content.ContextCompat.registerReceiver(
            requireContext(),
            updateReceiver,
            filter,
            androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    override fun onResume() {
        super.onResume()
        AppVisibilityTracker.setHomeVisible(true)

        // 🚀 [增强] 用户点完跳转链接回到主界面，发送隐式自启动测试广播
        val ctx = context
        if (ctx != null) {
            val testIntent = android.content.Intent("com.decikeeper.app.ACTION_TEST_AUTO_START")
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
        updateRefreshTimeBadge()
        startUiCountdown()
        updateAdaptationCardVisibility()
        updateSetupGuide() // 🚀 [NEW]
        // 🚀 [优化] 用户从设置页返回后自动识别并标记已处理权限
        processPendingPermissionAutoMark()

        // 🚀 [实时刷新] 若当前正在展示权限设置弹窗，控制弹窗内的各项权限开启状态
        if (activeProtectionDialog?.isShowing == true) {
            refreshProtectionDialogContent?.invoke()
        }
    }

    private fun updateRefreshTimeBadge() {
        val context = context ?: return
        val refreshTime = TimeRuleManager.getRefreshTime(context)
        tvHomeRefreshTimeBadge?.text = "🕒 每日生效时刻: $refreshTime"
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
            activeProtectionDialog?.let {
                if (it.isShowing) it.dismiss()
            }
        } catch (e: Exception) {
            Log.w("HomeFragment", "Failed to dismiss active protection dialog", e)
        }
        activeProtectionDialog = null
        refreshProtectionDialogContent = null

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
        
        // Dashboard Views
        tvHomeRefreshTimeBadge = view.findViewById(R.id.tv_home_refresh_time_badge)
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
        
        cardTodayAlarm = view.findViewById(R.id.card_today_alarm)
        tvHomeAlarmStatusBadge = view.findViewById(R.id.tv_home_alarm_status_badge)
        tvHomeNextAlarmCountdown = view.findViewById(R.id.tv_home_next_alarm_countdown)
        viewTodayAlarmTimeline = view.findViewById(R.id.view_today_alarm_timeline)
        cardTodayAlarm?.setOnClickListener {
            (activity as? MainActivity)?.findViewById<BottomNavigationView>(R.id.bottom_navigation)?.selectedItemId = R.id.nav_alarm
        }
        
        // 休息时间卡片与冷却胶囊条
        layoutCooldownPill = view.findViewById(R.id.layout_cooldown_pill)
        tvCooldownPillText = view.findViewById(R.id.tv_cooldown_pill_text)
        cardFreeTime = view.findViewById(R.id.card_free_time)
        tvFreeTimeCountdown = view.findViewById(R.id.tv_free_time_countdown)
        btnStopFreeTime = view.findViewById(R.id.btn_stop_free_time)
        
        // 适应期卡片
        cardAdaptation = view.findViewById(R.id.card_adaptation)
        btnEndAdaptation = view.findViewById(R.id.btn_end_adaptation)

        // ✈️ [NEW] Setup Guide Initialization
        cardSetupGuide = view.findViewById(R.id.card_setup_guide)
        btnSetupSkip = view.findViewById(R.id.btn_setup_skip)
        tvIconPerm = view.findViewById(R.id.tv_setup_icon_perm)
        tvIconBlacklist = view.findViewById(R.id.tv_setup_icon_blacklist)
        tvIconDnd = view.findViewById(R.id.tv_setup_icon_dnd)
        tvIconAlarm = view.findViewById(R.id.tv_setup_icon_alarm)
        btnGoPerm = view.findViewById(R.id.btn_setup_go_perm)
        btnGoBlacklist = view.findViewById(R.id.btn_setup_go_blacklist)
        btnGoDnd = view.findViewById(R.id.btn_setup_go_dnd)
        btnGoAlarm = view.findViewById(R.id.btn_setup_go_alarm)
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

            showWeeklyPrivilegeConfirmDialog(context)
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
                        updateTimerText()
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
                        updateTimerText()
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
        btnSetupSkip?.setOnClickListener {
            val prefs = ctx.getSharedPreferences(CreditManager.PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().putBoolean(PREF_SETUP_GUIDE_DISMISSED, true).apply()
            cardSetupGuide?.visibility = View.GONE
            Toast.makeText(ctx, "已跳过初始化向导，可在各页面随时配置", Toast.LENGTH_SHORT).show()
        }
        btnGoPerm?.setOnClickListener { showProtectionDialog() }
        btnGoBlacklist?.setOnClickListener { (activity as? MainActivity)?.showFocusRules(FocusRulesFragment.TAB_TIME) }
        btnGoDnd?.setOnClickListener { (activity as? MainActivity)?.showFocusRules(FocusRulesFragment.TAB_TIME) }
        btnGoAlarm?.setOnClickListener { (activity as? MainActivity)?.showAlarm() }
    }

    private fun getPrivilegeBoundaryLabel(context: Context): String {
        val windowEnd = TimeRuleManager.getPrivilegeWindowEnd(context)
        return buildPrivilegeBoundaryLabel(windowEnd)
    }

    private fun showWeeklyPrivilegeConfirmDialog(context: Context) {
        val remainingDuration = CreditManager.getRemainingWeeklyPrivilegeDuration(context)
        if (remainingDuration <= 0) {
            Toast.makeText(context, "当前无可用特权时长", Toast.LENGTH_SHORT).show()
            updateWeeklyPrivilegeEntryDisplay()
            return
        }

        val count = CreditManager.getWeeklyPrivilegeCount(context)
        val limit = CreditManager.getWeeklyPrivilegeLimit(context)

        AlertDialog.Builder(context)
            .setTitle("使用每周特权")
            .setMessage(
                "确定要使用每周特权吗？\n\n" +
                    "• 每日仅可启用一次\n" +
                    "• 你将选择一段【特权时长】，在该时长内进入【无限制】\n" +
                    "• 若跨越 24:00 将自动结束（夜间保护）\n" +
                    "• 特权进行中不可重复启用\n" +
                    "• 本周剩余机会: ${limit - count} 次，剩余时长: ${remainingDuration} 分钟"
            )
            .setPositiveButton("确认") { _, _ ->
                showWeeklyPrivilegeStartDialog(context)
            }
            .setNegativeButton("取消", null)
            .show()
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
            .setTitle("选择特权时长")
            .setMessage("本周剩余 $remainingDuration 分钟。请选择本次要使用的时长（超过可用时段将自动截断），确认后消耗 1 次特权。")
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

        updateRefreshTimeBadge()
        updateWeeklyPrivilegeEntryDisplay()
        updateTodayAlarmDisplay()

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
            val currentLimitMins = CreditManager.getSegmentEffectiveLimitMinutes(ctx, currentSeg)
            val currentLimitStr = formatMinutesForHome(currentLimitMins)
            val borrowedMinsFromNext = CreditManager.getBorrowedMinutesFromNext(ctx)

            // Time big number — 当前可用
            tvStatTimeBig?.text = "${formatMinutesForHome(remaining)}min"

            // Count column
            val usableLimit = CreditManager.getSegmentEffectiveUsableCountLimitHalfUnits(ctx, currentSeg)
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
                currentLimitMins,
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
                "时间 ${formatMinutesForHome(timeModel.currentRemaining)}/${currentLimitStr} + ${formatMinutesForHome(timeModel.surplusRemaining)}/${formatMinutesForHome(timeModel.surplusTotal)}min(结余)"
            } else {
                "时间 ${formatMinutesForHome(remaining)}/${currentLimitStr}min"
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
                infoText += "\n借用扣除 (×2) ：-${formatMinutesForHome(penaltyMins)}min  |  -${formatUsableCountHalfUnits(penaltyCount)}次"
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

    private fun updateTodayAlarmDisplay() {
        val ctx = context ?: return
        val alarms = try {
            com.decikeeper.app.manager.AlarmScheduler.getAlarms(ctx).filter { it.enabled }
        } catch (_: Exception) {
            emptyList()
        }

        if (alarms.isEmpty()) {
            tvHomeAlarmStatusBadge?.text = "0 个开启"
            tvHomeAlarmStatusBadge?.backgroundTintList = android.content.res.ColorStateList.valueOf(ThemeManager.getColorAttr(ctx, R.attr.zzSurface2))
            tvHomeAlarmStatusBadge?.setTextColor(ThemeManager.getColorAttr(ctx, R.attr.zzMuted))
            tvHomeNextAlarmCountdown?.text = "暂无开启中的闹钟，点击卡片去设置"
            viewTodayAlarmTimeline?.setAlarms(emptyList())
            return
        }

        tvHomeAlarmStatusBadge?.text = "${alarms.size} 个开启"
        tvHomeAlarmStatusBadge?.backgroundTintList = android.content.res.ColorStateList.valueOf(ThemeManager.getColorAttr(ctx, R.attr.zzAccentSoft))
        tvHomeAlarmStatusBadge?.setTextColor(ThemeManager.getColorAttr(ctx, R.attr.zzAccentStrong))

        val now = Calendar.getInstance()
        val currentMin = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
        val alarmMins = alarms.map { it.hour * 60 + it.minute }
        viewTodayAlarmTimeline?.setAlarms(alarmMins)

        // 计算下一次响铃倒计时
        val todayUpcoming = alarms.filter { (it.hour * 60 + it.minute) >= currentMin }
            .minByOrNull { (it.hour * 60 + it.minute) }
        val nextAlarm = todayUpcoming ?: alarms.minByOrNull { (it.hour * 60 + it.minute) }

        if (nextAlarm != null) {
            val targetMin = nextAlarm.hour * 60 + nextAlarm.minute
            val diffMins = if (targetMin >= currentMin) {
                targetMin - currentMin
            } else {
                1440 - currentMin + targetMin
            }
            val h = diffMins / 60
            val m = diffMins % 60
            val dayPrefix = if (targetMin >= currentMin) "今天" else "明天"
            val timeStr = String.format(Locale.getDefault(), "%02d:%02d", nextAlarm.hour, nextAlarm.minute)
            val countdownStr = if (h > 0) "${h} 小时 ${m} 分钟" else "${m} 分钟"
            tvHomeNextAlarmCountdown?.text = "距下一次响铃还剩 $countdownStr ($dayPrefix $timeStr)"
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

        val endTime = CreditManager.getFreeTimeEnd(ctx)
        val timeLeft = endTime - now
        val restRemainSeconds = if (timeLeft > 0) (timeLeft + 999) / 1000 else 0L

        // Card rest countdown tick (every second)
        if (timeLeft > 0) {
            tvRestCountdownInCard?.visibility = View.VISIBLE
            tvRestCountdownInCard?.text = "🟢 休息中 剩余 ${restRemainSeconds}s"
        } else {
            tvRestCountdownInCard?.visibility = View.GONE
        }

        val isPrivilege = CreditManager.isPrivilegeMode(ctx)

        if (timeLeft > 0) {
            val seconds = timeLeft / 1000
            val min = seconds / 60
            val sec = seconds % 60
            cardFreeTime?.visibility = View.VISIBLE
            btnStopFreeTime?.visibility = View.VISIBLE
            val label = if (isPrivilege) "🌟 特权中" else "🟢 休息中"
            tvFreeTimeCountdown?.text = "$label 还剩 ${min} min ${sec} s"
        } else {
            cardFreeTime?.visibility = View.GONE
        }

        val coolDownEnd = CreditManager.getCoolDownEnd(ctx)
        val coolLeft = coolDownEnd - now
        val restCooldownRemainSeconds = if (coolLeft > 0) (coolLeft + 999) / 1000 else 0L
        val isDndSegment = TimeRuleManager.getCurrentSegment(ctx) != null

        val pillModel = resolveHomeStatusPillModel(
            isResting = timeLeft > 0,
            restRemainSeconds = restRemainSeconds,
            tempAccessRemainSeconds = tempAccessRemainSeconds,
            tempCooldownRemainSeconds = tempCooldownRemainSeconds,
            restCooldownRemainSeconds = restCooldownRemainSeconds,
            isDndSegment = isDndSegment
        )

        if (pillModel.isVisible) {
            layoutCooldownPill?.visibility = View.VISIBLE
            tvCooldownPillText?.text = pillModel.text
            val color = ThemeManager.getColorAttr(ctx, pillModel.colorAttrId)
            tvCooldownPillText?.setTextColor(color)
        } else {
            layoutCooldownPill?.visibility = View.GONE
        }
    }
    
    private fun updateAdaptationCardVisibility() {
        val ctx = context ?: return
        cardAdaptation?.visibility = if (CreditManager.isInAdaptationPeriod(ctx)) View.VISIBLE else View.GONE
    }

    private fun updateProtectionStatus() {
        val ctx = context ?: return
        val sb = StringBuilder()
        
        val hasOverlay = PermissionManager.hasOverlayPermission(ctx)
        sb.append(if (hasOverlay) "✅ 悬浮窗权限：已开启\n" else "⚠️ 悬浮窗权限：未开启\n")

        val prefs = ctx.getSharedPreferences(CreditManager.PREFS_NAME, Context.MODE_PRIVATE)
        val hasBgBattery = !isChineseRomDevice() || isBgBatteryPermissionSatisfied(ctx)
        sb.append(if (hasBgBattery) "✅ 后台耗电管理：已开启\n" else "⚠️ 后台耗电管理：待设置\n")
        val hasAutoStart = isShortTimeConfirmed(prefs, "auto_start_confirmed")
        sb.append(if (hasAutoStart) "✅ 自启动权限：已确认" else "⚠️ 自启动权限：待确认")
    
        tvProtectionStatus?.text = sb.toString()

        val permState = getHomePermissionState(ctx)
        val permsDone = permState.allGranted
        if (permsDone) {
            // 权限全了，整个状态卡片都不需要展示，保持主页极致简洁
            cardPermissionStatus?.visibility = View.GONE
            tvStatus?.text = "状态：权限已就绪 (全面防护中)"
            tvStatus?.setTextColor(ThemeManager.getColorAttr(ctx, R.attr.zzSuccess))
            tvProtectionStatus?.visibility = View.GONE
            btnPermission?.visibility = View.GONE
        } else {
            cardPermissionStatus?.visibility = View.VISIBLE
            tvStatus?.text = "状态：需设置权限 ⚠️"
            tvStatus?.setTextColor(ThemeManager.getColorAttr(ctx, R.attr.zzWarning))
            tvProtectionStatus?.visibility = View.VISIBLE
            btnPermission?.visibility = View.VISIBLE
            btnPermission?.text = "去设置权限"
            btnPermission?.isEnabled = true
            btnPermission?.alpha = 1.0f
        }
    }

    private fun checkOverlayPermission() {
        val ctx = context ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !PermissionManager.hasOverlayPermission(ctx)) {
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
                .setMessage("决定守护需要「显示在其他应用上层」权限才能显示锁屏界面。")
                .setPositiveButton("去开启") { _, _ ->
                    try {
                        RecentTasksHideManager.markNavigating()
                        startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, android.net.Uri.parse("package:${ctx.packageName}")))
                    } catch (e: Exception) {
                        RecentTasksHideManager.clearNavigating()
                        Toast.makeText(ctx, "无法打开设置页面", Toast.LENGTH_SHORT).show()
                    }
                }
                .setNegativeButton("稍后", null)
                .setCancelable(false)
                .show()
        }
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
                            com.decikeeper.app.protection.AccessibilityProtection.markManualSettingsLaunch()
                            RecentTasksHideManager.markNavigating()
                            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                        } catch (e: Exception) {
                            RecentTasksHideManager.clearNavigating()
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
                    "$label 当前可由系统自动检测状态，暂不提供手动确认。\n\n请先去设置开启，返回后由决定守护自动识别。"
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

        if (manufacturer.contains("oppo") || manufacturer.contains("realme") || manufacturer.contains("oneplus")) {
            intents.add(Intent().setComponent(android.content.ComponentName("com.oplus.securitypermission", "com.oplus.securitypermission.startup.StartupAppListActivity")))
            intents.add(Intent().setComponent(android.content.ComponentName("com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity")))
            intents.add(Intent().setComponent(android.content.ComponentName("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity")))
            intents.add(Intent().setComponent(android.content.ComponentName("com.coloros.safecenter", "com.coloros.safecenter.permission.startupInit.StartupNormalAppListActivity")))
            intents.add(Intent().setComponent(android.content.ComponentName("com.oplus.securitypermission", "com.oplus.securitypermission.startup.AssociateStartupAppListActivity")))
            intents.add(Intent().setComponent(android.content.ComponentName("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.AssociateStartupAppListActivity")))
            // OPPO 单应用详情页没有自启动/关联启动开关，因此优先进入「设置 → 应用」
            intents.add(Intent(Settings.ACTION_APPLICATION_SETTINGS))
            intents.add(Intent("android.settings.APPLICATION_SETTINGS"))
        } else if (manufacturer.contains("vivo") || manufacturer.contains("iqoo")) {
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
            intents.add(
                Intent().setComponent(
                    android.content.ComponentName(
                        "com.vivo.permissionmanager",
                        "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"
                    )
                )
            )
        } else if (manufacturer.contains("xiaomi") || manufacturer.contains("redmi")) {
            intents.add(Intent().setComponent(android.content.ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")))
        } else if (manufacturer.contains("huawei") || manufacturer.contains("honor")) {
            intents.add(Intent().setComponent(android.content.ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity")))
            intents.add(Intent().setComponent(android.content.ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.appcontrol.activity.StartupAppListActivity")))
        }

        intents.add(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:${ctx.packageName}")))
        return intents
    }

    private fun launchManualPermissionSettings(permissionKey: String) {
        val ctx = context ?: return
        if (permissionKey == "auto_start_confirmed") {
            if (SettingsNavigator.openAutostartSettings(ctx)) {
                return
            }
        } else if (permissionKey == "assoc_start_confirmed") {
            if (SettingsNavigator.openAssociateStartSettings(ctx)) {
                return
            }
        }
        val intents = when (permissionKey) {
            "auto_start_confirmed", "bg_popup_confirmed", "assoc_start_confirmed" -> buildManualPermissionSettingsIntents(ctx)
            else -> emptyList()
        }

        for (intent in intents) {
            try {
                if (intent.resolveActivity(ctx.packageManager) != null) {
                    RecentTasksHideManager.markNavigating()
                    startActivity(intent)
                    return
                }
            } catch (_: Exception) {
                RecentTasksHideManager.clearNavigating()
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
            "recents_lock_confirmed" -> "多任务卡片加锁"
            else -> key
        }
    }

    private fun permissionConfirmedAtKey(key: String): String? {
        return when (key) {
            "auto_start_confirmed" -> "auto_start_confirmed_at"
            "bg_popup_confirmed" -> "bg_popup_confirmed_at"
            "assoc_start_confirmed" -> "assoc_start_confirmed_at"
            "recents_lock_confirmed" -> "recents_lock_confirmed_at"
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

        val prefs = ctx.getSharedPreferences(CreditManager.PREFS_NAME, Context.MODE_PRIVATE)

        fun populateDialogContent() {
            layout.removeAllViews()
            val currentCtx = context ?: return

            fun addItem(
                name: String,
                isGranted: Boolean,
                grantedLabel: String = "已开启",
                onLongClick: (() -> Unit)? = null,
                action: () -> Unit
            ) {
                if (isGranted) {
                    val row = android.widget.LinearLayout(currentCtx).apply {
                        orientation = android.widget.LinearLayout.HORIZONTAL
                        setPadding(0, 8, 0, 8)
                        gravity = android.view.Gravity.CENTER_VERTICAL
                    }
                    val tv = TextView(currentCtx).apply {
                        text = if (onLongClick != null) "✅ $name：$grantedLabel (长按重测)" else "✅ $name：$grantedLabel"
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
                    val density = currentCtx.resources.displayMetrics.density
                    val btn = Button(currentCtx).apply {
                        text = "👉 去设置：$name"
                        textSize = 14f
                        setTextColor(android.graphics.Color.WHITE)
                        setBackgroundResource(R.drawable.bg_zz_pill)
                        backgroundTintList = android.content.res.ColorStateList.valueOf(ThemeManager.getColorAttr(currentCtx, R.attr.zzAccent))
                        minHeight = (44 * density).toInt()
                        setPadding((12 * density).toInt(), (8 * density).toInt(), (12 * density).toInt(), (8 * density).toInt())
                        gravity = android.view.Gravity.CENTER
                        layoutParams = android.widget.LinearLayout.LayoutParams(
                            android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                        ).apply {
                            setMargins(0, (6 * density).toInt(), 0, (6 * density).toInt())
                        }
                        setOnClickListener { action() }
                    }
                    layout.addView(btn)
                }
            }

            fun addUndetectablePermissionItem(
                name: String,
                key: String,
                hint: String,
                linkedPendingKeys: List<String> = emptyList(),
                onAction: (() -> Unit)? = null,
                onLongClick: (() -> Unit)? = null
            ) {
                val granted = isShortTimeConfirmed(prefs, key)
                if (granted) {
                    addItem(name, true, grantedLabel = "已确认", onLongClick = onLongClick) {}
                    return
                }

                val density = currentCtx.resources.displayMetrics.density
                val row = android.widget.LinearLayout(currentCtx).apply {
                    orientation = android.widget.LinearLayout.HORIZONTAL
                    setPadding(0, 8, 0, 8)
                    gravity = android.view.Gravity.CENTER_VERTICAL
                }

                val btn = Button(currentCtx).apply {
                    text = "👉 去设置：$name"
                    textSize = 13f
                    setTextColor(android.graphics.Color.WHITE)
                    setBackgroundResource(R.drawable.bg_zz_pill)
                    backgroundTintList = android.content.res.ColorStateList.valueOf(ThemeManager.getColorAttr(currentCtx, R.attr.zzAccent))
                    minHeight = (44 * density).toInt()
                    setPadding((10 * density).toInt(), (6 * density).toInt(), (10 * density).toInt(), (6 * density).toInt())
                    gravity = android.view.Gravity.CENTER
                    layoutParams = android.widget.LinearLayout.LayoutParams(
                        0,
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                        1f
                    ).apply {
                        setMargins(0, 0, (8 * density).toInt(), 0)
                    }
                    setOnClickListener {
                        if (onAction != null) {
                            onAction()
                        } else {
                            launchManualPermissionSettings(key)
                            Toast.makeText(currentCtx, hint, Toast.LENGTH_LONG).show()
                            addPendingPermissionConfirm(key, markSettingsLaunch = true)
                            linkedPendingKeys.forEach { linkedKey ->
                                addPendingPermissionConfirm(linkedKey, markSettingsLaunch = true)
                            }
                        }
                    }
                }

                val check = android.widget.CheckBox(currentCtx).apply {
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
                            populateDialogContent()
                        } else {
                            prefs.edit()
                                .remove(key)
                                .remove("${key}_at")
                                .apply()
                            updateProtectionStatus()
                            updateSetupGuide()
                            populateDialogContent()
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

            val hasOverlay = PermissionManager.hasOverlayPermission(currentCtx)
            addItem("悬浮窗权限", hasOverlay) {
                try {
                    RecentTasksHideManager.markNavigating()
                    startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, android.net.Uri.parse("package:${currentCtx.packageName}")))
                } catch (e: Exception) {
                    RecentTasksHideManager.clearNavigating()
                    Log.w("HomeFragment", "Failed to open overlay settings", e)
                }
            }

            // [DO NOT DELETE] 通知权限：Android 13+ 运行时权限，前台服务需要此权限才能显示通知
            if (Build.VERSION.SDK_INT >= 33) {
                val hasNotification = currentCtx.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED
                addItem("通知权限", hasNotification) {
                    RecentTasksHideManager.markNavigating()
                    notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                }
            }

            val bgBatteryLabel = if (SettingsNavigator.isOppoOrOnePlusOrRealme()) {
                "后台行为控制（完全允许后台行为）"
            } else if (SettingsNavigator.isVivoOrIqoo()) {
                "后台耗电管理（允许后台高耗电）"
            } else {
                "后台耗电管理（允许后台运行）"
            }
            val hasBgBattery = !isChineseRomDevice() || isBgBatteryPermissionSatisfied(currentCtx)
            addItem(bgBatteryLabel, hasBgBattery) {
                addPendingPermissionConfirm("bg_battery_confirmed", markSettingsLaunch = true)
                openBgBatterySettings(currentCtx)
            }

            val autostartLabel = SettingsNavigator.getAutostartLabel()
            addUndetectablePermissionItem(
                name = autostartLabel,
                key = "auto_start_confirmed",
                hint = if (SettingsNavigator.isOppoOrOnePlusOrRealme()) {
                    "请在系统「设置 ➔ 应用 ➔ 自启动」中开启"
                } else {
                    "请在设置中开启「自启动」开关"
                },
                onLongClick = {
                    prefs.edit().remove("auto_start_confirmed").remove("auto_start_confirmed_at").apply()
                    updateProtectionStatus()
                    updateSetupGuide()
                    populateDialogContent()
                    Toast.makeText(currentCtx, "已清除自启动缓存，请重新检测", Toast.LENGTH_SHORT).show()
                }
            )

            // 多任务卡片加锁（防误清理保活推荐项）
            addUndetectablePermissionItem(
                name = "多任务卡片加锁（防误清理）",
                key = "recents_lock_confirmed",
                hint = "请在多任务界面下拉或长按决定守护卡片进行加锁",
                onAction = {
                    showRecentsLockGuideDialog(currentCtx)
                },
                onLongClick = {
                    prefs.edit().remove("recents_lock_confirmed").remove("recents_lock_confirmed_at").apply()
                    updateProtectionStatus()
                    updateSetupGuide()
                    populateDialogContent()
                    Toast.makeText(currentCtx, "已清除多任务加锁状态", Toast.LENGTH_SHORT).show()
                }
            )

            val permState = getHomePermissionState(currentCtx)
            if (permState.allGranted) {
                val tvAllReady = TextView(currentCtx).apply {
                    text = "🎉 所有基础权限已就绪！"
                    textSize = 14f
                    setTextColor(android.graphics.Color.parseColor("#2E7D32"))
                    setPadding(0, 16, 0, 0)
                    gravity = android.view.Gravity.CENTER_HORIZONTAL
                }
                layout.addView(tvAllReady)
            }
        }

        populateDialogContent()
        refreshProtectionDialogContent = { populateDialogContent() }

        val dialog = AlertDialog.Builder(ctx)
            .setTitle("🛡️ 权限设置")
            .setView(layout)
            .setPositiveButton("完成", null)
            .create()
        dialog.window?.setBackgroundDrawableResource(R.drawable.bg_zz_card)

        dialog.setOnDismissListener {
            if (activeProtectionDialog == dialog) {
                activeProtectionDialog = null
                refreshProtectionDialogContent = null
            }
            updateProtectionStatus()
            updateSetupGuide()
        }

        activeProtectionDialog = dialog
        dialog.show()
    }

    private fun showRecentsLockGuideDialog(ctx: Context) {
        val extraWindowTip = if (SettingsNavigator.isOppoOrOnePlusOrRealme()) {
            "\n\n• OPPO 强效保活妙招：\n  ColorOS 支持将决定守护设为「闪达小窗/迷你浮窗」常驻桌面或侧边栏，小窗模式下可完全免疫多任务一键划杀！"
        } else {
            ""
        }
        androidx.appcompat.app.AlertDialog.Builder(ctx)
            .setTitle("🔒 多任务卡片加锁指引")
            .setMessage(
                "• 作用：防止系统一键清理后台时误杀决定守护；\n\n" +
                "• 操作步骤：\n" +
                "  1. 呼出手机的多任务（最近任务）界面；\n" +
                "  2. 找到「决定守护」卡片；\n" +
                "  3. 下拉卡片，或长按/点击卡片右上角菜单；\n" +
                "  4. 点击【加锁】（卡片上出现 🔒 小锁图标）；" +
                extraWindowTip +
                "\n\n• 设置完成后，回到这里勾选「已开启」即可。"
            )
            .setPositiveButton("知道了", null)
            .show()
    }

    private fun updateSetupGuide() {
        val ctx = context ?: return
        val prefs = ctx.getSharedPreferences(CreditManager.PREFS_NAME, Context.MODE_PRIVATE)
        val isDismissed = prefs.getBoolean(PREF_SETUP_GUIDE_DISMISSED, false)
        
        // 1. Check Permissions
        val permState = getHomePermissionState(ctx)
        val permsDone = permState.allGranted
        
        fun updateStepBadge(tv: TextView?, isDone: Boolean, stepNum: String) {
            tv?.text = if (isDone) "✓" else stepNum
            val bgAttr = if (isDone) R.attr.zzAccentSoft else R.attr.zzSurface2
            val textAttr = if (isDone) R.attr.zzAccentStrong else R.attr.zzMuted
            tv?.backgroundTintList = android.content.res.ColorStateList.valueOf(ThemeManager.getColorAttr(ctx, bgAttr))
            tv?.setTextColor(ThemeManager.getColorAttr(ctx, textAttr))
        }

        updateStepBadge(tvIconPerm, permsDone, "1")
        btnGoPerm?.visibility = View.VISIBLE
        btnGoPerm?.isEnabled = !permsDone
        btnGoPerm?.alpha = if (permsDone) 0.4f else 1.0f
        (btnGoPerm as? TextView)?.text = if (permsDone) "权限已齐全" else "去开启 >"

        // 2. Check Blacklist
        val blacklistDone = CreditManager.getBlacklistApps(ctx).isNotEmpty()
        updateStepBadge(tvIconBlacklist, blacklistDone, "2")
        btnGoBlacklist?.visibility = if (blacklistDone) View.GONE else View.VISIBLE

        // 3. Check DND
        val dndDone = TimeRuleManager.getTimeSegments(ctx).isNotEmpty()
        updateStepBadge(tvIconDnd, dndDone, "3")
        btnGoDnd?.visibility = if (dndDone) View.GONE else View.VISIBLE

        // 4. Check Alarm
        val alarmDone = AlarmScheduler.getAlarms(ctx).isNotEmpty()
        updateStepBadge(tvIconAlarm, alarmDone, "4")
        btnGoAlarm?.visibility = if (alarmDone) View.GONE else View.VISIBLE

        // Show/Hide Card: Hide if dismissed or if (permsDone && ((blacklistDone && dndDone) || alarmDone))
        val shouldShow = shouldShowSetupGuide(
            isDismissed = isDismissed,
            permsDone = permsDone,
            blacklistDone = blacklistDone,
            dndDone = dndDone,
            alarmDone = alarmDone
        )
        cardSetupGuide?.visibility = if (shouldShow) View.VISIBLE else View.GONE
    }

    private data class HomePermissionState(
        val allGranted: Boolean,
        val missingLabels: List<String>
    )

    private fun getHomePermissionState(ctx: Context): HomePermissionState {
        val hasOverlay = PermissionManager.hasOverlayPermission(ctx)
        val hasNotification = if (Build.VERSION.SDK_INT >= 33) {
            ctx.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED
        } else true
        val prefs = ctx.getSharedPreferences(CreditManager.PREFS_NAME, Context.MODE_PRIVATE)
        val hasBgBattery = !isChineseRomDevice() || isBgBatteryPermissionSatisfied(ctx)
        val hasAutoStart = isShortTimeConfirmed(prefs, "auto_start_confirmed")

        val missing = mutableListOf<String>()
        if (!hasOverlay) missing.add("悬浮窗")
        if (!hasNotification) missing.add("通知权限")
        val bgBatteryMissingLabel = if (SettingsNavigator.isOppoOrOnePlusOrRealme()) "完全后台行为" else "后台耗电管理"
        val autostartMissingLabel = if (SettingsNavigator.isOppoOrOnePlusOrRealme()) SettingsNavigator.getAutostartLabel() else "自启动"
        if (!hasBgBattery) missing.add(bgBatteryMissingLabel)
        if (!hasAutoStart) missing.add(autostartMissingLabel)

        val allGranted = missing.isEmpty()
        return HomePermissionState(allGranted = allGranted, missingLabels = missing)
    }

    private fun isBgBatteryPermissionSatisfied(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
        val powerManager = ctx.getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
            ?: return false
        return powerManager.isIgnoringBatteryOptimizations(ctx.packageName) ||
            ctx.getSharedPreferences(CreditManager.PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean("bg_battery_confirmed", false)
    }

    private fun openBgBatterySettings(ctx: Context) {
        if (SettingsNavigator.openBatteryKeepAliveSettings(ctx)) {
            Toast.makeText(ctx, SettingsNavigator.getBrandKeepAliveTips(), Toast.LENGTH_LONG).show()
            return
        }
        val intents = listOf(
            Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:${ctx.packageName}"))
        )
        for (intent in intents) {
            try {
                RecentTasksHideManager.markNavigating()
                startActivity(intent)
                Toast.makeText(ctx, SettingsNavigator.getBrandKeepAliveTips(), Toast.LENGTH_LONG).show()
                return
            } catch (e: Exception) {
                RecentTasksHideManager.clearNavigating()
                Log.w("HomeFragment", "Failed to open battery settings", e)
            }
        }
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
