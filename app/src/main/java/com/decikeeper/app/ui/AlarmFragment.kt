/**
 * =================================================================================
 * [Context Header]
 * * @file: AlarmFragment.kt
 * * @role: UI
 * * @description: Alarm configuration screen.
 * * @responsibilities:
 * 1. 设置每日闹钟时间。
 * 2. 立刻测试闹钟。
 * * @context:
 * - 上游: MainActivity
 * - 下游: AlarmScheduler
 * * @constraints (必读):
 * 1. 禁止承载复杂业务逻辑。
 * =================================================================================
 */
package com.decikeeper.app.ui

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import com.decikeeper.app.manager.SettingsNavigator
import android.content.pm.ResolveInfo
import android.graphics.Color
import android.graphics.Typeface
import android.media.RingtoneManager
import com.decikeeper.app.manager.AlarmRingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.AlarmClock
import android.provider.Settings
import android.text.InputType
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.Space
import android.widget.TextView
import android.widget.TimePicker
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.decikeeper.app.R
import com.decikeeper.app.manager.AlarmEmergencyExitPolicy
import com.decikeeper.app.manager.AlarmEmergencyExitStore
import com.decikeeper.app.manager.AlarmOverlayRequirement
import com.decikeeper.app.manager.AlarmScheduler
import com.decikeeper.app.manager.AlarmSystemBackupUseCase




class AlarmFragment : Fragment() {

    companion object {
        private const val TAG = "AlarmFragment"
        private const val REQUEST_PICK_RINGTONE = 9001
    }

    private var recyclerView: RecyclerView? = null
    private var adapter: AlarmListAdapter? = null
    private var btnAdd: Button? = null
    private var pendingRingtoneUri: String? = null
    private var tvSnoozeSummary: TextView? = null
    private var tvKeepaliveSummary: TextView? = null
    private var tvEmergencyExitSummary: TextView? = null
    private var tvChallengeSummary: TextView? = null
    private var tvUnlockSummary: TextView? = null
    private val alarms = mutableListOf<AlarmScheduler.AlarmEntry>()
    private val activeAlarmRefreshHandler = Handler(Looper.getMainLooper())
    private var lastRenderedActiveAlarmId: Int = Int.MIN_VALUE
    private var lastRenderedOverlayShowing: Boolean = false
    private val activeAlarmRefreshRunnable = object : Runnable {
        override fun run() {
            refreshChallengeButtonsIfNeeded()
            activeAlarmRefreshHandler.postDelayed(this, 1000L)
        }
    }

    private var onRingtonePickedCallback: ((uri: String?, title: String) -> Unit)? = null
    private val ringtonePickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            val uri = result.data?.getStringExtra(AlarmRingtonePickerActivity.EXTRA_SELECTED_RINGTONE_URI)
            val title = result.data?.getStringExtra(AlarmRingtonePickerActivity.EXTRA_SELECTED_RINGTONE_TITLE)
                ?: AlarmRingtoneManager.getRingtoneTitle(requireContext(), uri)
            pendingRingtoneUri = uri
            onRingtonePickedCallback?.invoke(uri, title)
        }
    }

    private var pendingCameraPermissionCheckbox: CheckBox? = null
    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            pendingCameraPermissionCheckbox?.isChecked = true
            Toast.makeText(requireContext(), "相机权限已授予，已启用扫码关闹钟", Toast.LENGTH_SHORT).show()
        } else {
            pendingCameraPermissionCheckbox?.isChecked = false
            Toast.makeText(requireContext(), "未授予相机权限，无法开启扫码关闹钟", Toast.LENGTH_LONG).show()
        }
        pendingCameraPermissionCheckbox = null
    }

    private fun bindQrCheckboxPermission(checkbox: CheckBox) {
        checkbox.setOnClickListener {
            if (checkbox.isChecked) {
                val hasPerm = ContextCompat.checkSelfPermission(
                    requireContext(),
                    Manifest.permission.CAMERA
                ) == PackageManager.PERMISSION_GRANTED
                if (!hasPerm) {
                    checkbox.isChecked = false
                    pendingCameraPermissionCheckbox = checkbox
                    cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                }
            }
        }
    }

    private fun isVivoOrIqoo(): Boolean {
        val mf = Build.MANUFACTURER.lowercase()
        return mf.contains("vivo") || mf.contains("iqoo")
    }

    private fun isOppoOrOnePlusOrRealmeDevice(): Boolean {
        return SettingsNavigator.isOppoOrOnePlusOrRealme()
    }

    private fun isSleepStandbyConfirmed(prefs: SharedPreferences): Boolean {
        return prefs.getBoolean("sleep_standby_confirmed", false) ||
            prefs.getBoolean("vivo_sleep_standby_confirmed", false)
    }

    private fun isAlarmKeepaliveSatisfied(ctx: Context): Boolean {
        val isBgBatteryOk = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val powerManager = ctx.getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
            powerManager?.isIgnoringBatteryOptimizations(ctx.packageName) == true
        } else true

        val prefs = ctx.getSharedPreferences("decikeeper_oem_guide", Context.MODE_PRIVATE)
        val isSleepConfirmed = isSleepStandbyConfirmed(prefs)

        // 对 Vivo 和 OPPO 等国内典型机型强化睡眠优化确认，其他机型后台耗电满足即可
        val isStrictOem = isVivoOrIqoo() || isOppoOrOnePlusOrRealmeDevice()
        return isBgBatteryOk && (!isStrictOem || isSleepConfirmed)
    }

    private var hasPromptedKeepaliveInSession = false

    private fun checkAndPromptAlarmKeepalive(ctx: Context) {
        if (hasPromptedKeepaliveInSession) return
        val satisfied = isAlarmKeepaliveSatisfied(ctx)
        if (!satisfied) {
            hasPromptedKeepaliveInSession = true
            showAlarmKeepAliveGuideDialog()
        }
    }

    private fun formatEmergencyExitTime(millis: Long): String =
        java.text.SimpleDateFormat("M月d日 HH:mm", java.util.Locale.CHINA).format(java.util.Date(millis))

    private fun refreshEmergencyExitSummary() {
        val ctx = context ?: return
        val state = AlarmEmergencyExitStore.load(ctx)
        val remaining = AlarmEmergencyExitStore.remaining(ctx)
        val pending = state.pendingValue
        tvEmergencyExitSummary?.text = buildString {
            append("每月 ${state.active} 次 · 本月还剩 $remaining 次")
            if (pending != null) {
                append("\n待生效：${formatEmergencyExitTime(state.pendingEffectiveAt)} 起变为 $pending 次")
            }
        }
    }

    /** 调小立即生效；调大（超过基准）登记到下个自然月刷新时刻。 */
    private fun showEmergencyExitSettingsDialog() {
        val ctx = requireContext()
        val current = AlarmEmergencyExitStore.load(ctx).active
        val values = (AlarmEmergencyExitPolicy.MIN_MONTHLY..AlarmEmergencyExitPolicy.MAX_MONTHLY).toList()
        val labels = values.map { if (it == 0) "0 次（关闭应急退出）" else "$it 次" }.toTypedArray()
        AlertDialog.Builder(ctx)
            .setTitle("应急退出次数 / 月")
            .setSingleChoiceItems(labels, values.indexOf(current)) { dialog, which ->
                val requested = values[which]
                val next = AlarmEmergencyExitStore.requestMonthlyLimit(ctx, requested)
                val message = when {
                    next.pendingValue != null ->
                        "已登记：${formatEmergencyExitTime(next.pendingEffectiveAt)} 起变为 ${next.pendingValue} 次（调大需等下个月生效）"
                    next.active == current -> "未变化"
                    else -> "已调整为每月 ${next.active} 次，立即生效"
                }
                Toast.makeText(ctx, message, Toast.LENGTH_LONG).show()
                refreshEmergencyExitSummary()
                dialog.dismiss()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showAlarmKeepAliveGuideDialog(onDismiss: (() -> Unit)? = null) {
        val ctx = context ?: return
        val density = ctx.resources.displayMetrics.density
        val prefs = ctx.getSharedPreferences("decikeeper_oem_guide", Context.MODE_PRIVATE)

        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((24 * density).toInt(), (20 * density).toInt(), (24 * density).toInt(), (20 * density).toInt())
        }

        val tvTitle = TextView(ctx).apply {
            text = "闹钟防漏响保活配置"
            textSize = 18f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(ThemeManager.getColorAttr(ctx, R.attr.zzText))
        }
        container.addView(tvTitle)

        val tvMessage = TextView(ctx).apply {
            text = "为防止系统在夜间锁屏或后台待机时冻结闹钟导致漏响，请确保以下保活项已就绪："
            textSize = 13f
            setPadding(0, (10 * density).toInt(), 0, (14 * density).toInt())
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
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
        }

        val batteryLabel = SettingsNavigator.getBatteryOptimizationLabel()

        // 行 1：后台耗电/完全允许后台行为（优化为标准设置条目行，去除冗余灰色矩形框与复选框）
        val rowBgBattery = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, (6 * density).toInt(), 0, (12 * density).toInt())
        }
        val textContainer = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val tvBatteryTitle = TextView(ctx).apply {
            text = batteryLabel
            textSize = 15f
            setTextColor(ThemeManager.getColorAttr(ctx, R.attr.zzText))
            setTypeface(typeface, Typeface.BOLD)
        }
        val tvBatteryDesc = TextView(ctx).apply {
            text = "允许后台高耗电，防止系统清理闹钟进程"
            textSize = 12f
            setTextColor(ThemeManager.getColorAttr(ctx, R.attr.zzMuted))
            setPadding(0, (2 * density).toInt(), 0, 0)
        }
        textContainer.addView(tvBatteryTitle)
        textContainer.addView(tvBatteryDesc)
        rowBgBattery.addView(textContainer)

        val tvBatteryBadge = TextView(ctx).apply {
            text = "已开启 ✓"
            textSize = 13f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(android.graphics.Color.parseColor("#388E3C"))
            setPadding((8 * density).toInt(), (4 * density).toInt(), (8 * density).toInt(), (4 * density).toInt())
            visibility = View.GONE
        }
        val btnBgBattery = com.google.android.material.button.MaterialButton(ctx).apply {
            text = "去开启"
            setAllCaps(false)
            setTextColor(ThemeManager.getColorAttr(ctx, R.attr.zzText))
            backgroundTintList = android.content.res.ColorStateList.valueOf(ThemeManager.getColorAttr(ctx, R.attr.zzSurface2))
            cornerRadius = (12 * density).toInt()
            minHeight = (36 * density).toInt()
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            visibility = View.VISIBLE
        }
        rowBgBattery.addView(tvBatteryBadge)
        rowBgBattery.addView(btnBgBattery)
        container.addView(rowBgBattery)

        // 行 2：睡眠待机优化（全机型开放引导，支持一键勾选）
        val rowSleep = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, (10 * density).toInt())
        }
        val btnSleep = buildButton("去关闭「睡眠待机优化」")
        val cbSleep = CheckBox(ctx).apply {
            text = "已关闭"
            setTextColor(ThemeManager.getColorAttr(ctx, R.attr.zzText))
        }
        rowSleep.addView(btnSleep)
        rowSleep.addView(cbSleep)
        container.addView(rowSleep)

        val tvTip = TextView(ctx).apply {
            text = if (com.decikeeper.app.manager.SettingsNavigator.isVivoOrIqoo()) {
                "* 注：vivo/iQOO 在夜间待机会深度冻结应用，请在系统「电池 → 省电模式 → 省电管理」中关闭「睡眠待机优化」，确认后勾选“已关闭”（若系统无此项可直接勾选）。"
            } else {
                "* 注：部分机型（如 OPPO、Vivo）在夜间待机会深度冻结应用，请在系统「省电设置」中关闭「睡眠待机优化」，确认后勾选“已关闭”（若系统无此项可直接勾选）。"
            }
            textSize = 11f
            setTextColor(ThemeManager.getColorAttr(ctx, R.attr.zzMuted))
            setPadding(0, 0, 0, (10 * density).toInt())
        }
        container.addView(tvTip)

        var dialog: AlertDialog? = null
        var uiUpdating = false

        fun updateDialogUi() {
            uiUpdating = true
            val isBgBatteryOk = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val powerManager = ctx.getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
                powerManager?.isIgnoringBatteryOptimizations(ctx.packageName) == true
            } else true

            if (isBgBatteryOk) {
                tvBatteryBadge.visibility = View.VISIBLE
                btnBgBattery.visibility = View.GONE
            } else {
                tvBatteryBadge.visibility = View.GONE
                btnBgBattery.visibility = View.VISIBLE
            }

            val isSleepConfirmed = isSleepStandbyConfirmed(prefs)
            cbSleep.isChecked = isSleepConfirmed
            btnSleep.text = if (isSleepConfirmed) "睡眠待机优化已关闭" else "去关闭「睡眠待机优化」"
            btnSleep.alpha = if (isSleepConfirmed) 0.5f else 1.0f

            uiUpdating = false
            refreshSettingsSummaries()
        }

        btnBgBattery.setOnClickListener {
            openKeepAliveSettings(ctx)
        }

        btnSleep.setOnClickListener {
            openSleepOptimizationSettings(ctx)
        }
        cbSleep.setOnCheckedChangeListener { _, checked ->
            if (uiUpdating) return@setOnCheckedChangeListener
            prefs.edit()
                .putBoolean("sleep_standby_confirmed", checked)
                .putBoolean("vivo_sleep_standby_confirmed", checked)
                .apply()
            updateDialogUi()
        }

        updateDialogUi()

        dialog = AlertDialog.Builder(ctx)
            .setView(container)
            .setPositiveButton("完成", null)
            .setNegativeButton("取消", null)
            .create()
        dialog.setOnDismissListener { onDismiss?.invoke() }
        dialog.show()
    }

    private fun openKeepAliveSettings(ctx: Context) {
        com.decikeeper.app.manager.SettingsNavigator.openBatteryKeepAliveSettings(ctx)
        Toast.makeText(ctx, com.decikeeper.app.manager.SettingsNavigator.getBrandKeepAliveTips(), Toast.LENGTH_LONG).show()
    }

    private fun openSleepOptimizationSettings(ctx: Context) {
        com.decikeeper.app.manager.SettingsNavigator.openSleepOptimizationSettings(ctx)
        Toast.makeText(ctx, com.decikeeper.app.manager.SettingsNavigator.getSleepOptimizationTips(), Toast.LENGTH_LONG).show()
    }

    private fun openVivoBatterySettings(ctx: Context) = openSleepOptimizationSettings(ctx)

    /** 闹钟挑战只能承载在悬浮窗中；没有权限时不进入页面版挑战。 */
    private fun ensureAlarmOverlayPermission(ctx: Context = requireContext()): Boolean {
        if (AlarmOverlayRequirement.isGranted(ctx)) return true

        AlertDialog.Builder(ctx)
            .setTitle(AlarmOverlayRequirement.TITLE)
            .setMessage(AlarmOverlayRequirement.MESSAGE)
            .setPositiveButton("去开启") { _, _ ->
                try {
                    startActivity(
                        Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:${ctx.packageName}")
                        )
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "Unable to open overlay permission settings", e)
                    Toast.makeText(ctx, "请在系统设置中手动开启悬浮窗权限", Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton("稍后", null)
            .show()
        return false
    }

    private fun maybeShowKeepAliveGuideOnFirstActiveAlarm(onDismiss: (() -> Unit)? = null) {
        if (hasPromptedKeepaliveInSession) {
            onDismiss?.invoke()
            return
        }
        val ctx = context ?: return
        val prefs = ctx.getSharedPreferences("decikeeper_oem_guide", Context.MODE_PRIVATE)
        val shown = prefs.getBoolean("alarm_keepalive_guide_shown", false) ||
            prefs.getBoolean("sleep_standby_guide_shown", false) ||
            prefs.getBoolean("vivo_sleep_standby_guide_shown", false)
        if (!shown) {
            prefs.edit().putBoolean("alarm_keepalive_guide_shown", true).apply()
            showAlarmKeepAliveGuideDialog(onDismiss)
        } else {
            onDismiss?.invoke()
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_alarm, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        recyclerView = view.findViewById(R.id.rv_alarm_list)
        btnAdd = view.findViewById(R.id.btn_add_alarm)

        adapter = AlarmListAdapter(
            alarms,
            onToggle = { alarm, enabled ->
                updateAlarm(alarm.copy(enabled = enabled))
            },
            onEdit = { alarm ->
                showEditDialog(alarm)
            },
            onDelete = { alarm ->
                confirmDelete(alarm)
            },
            onChallenge = { alarm ->
                // [DO NOT DELETE] 挑战按钮是防卡死的兜底入口，而非普通功能按钮
                // 只能在"闹钟正在震动 + 挑战界面没有自动弹出"时使用（关联 Issue_Tracker 05-13）
                val ctx = requireContext()
                val isAlarmActive = AlarmScheduler.isAlarmActive(ctx)
                val activeAlarmId = AlarmScheduler.getActiveAlarmId(ctx)
                val isThisAlarmActive = isAlarmActive && activeAlarmId == alarm.id
                val overlayShowing = LockWindowManager.isAlarmOverlayActuallyShowing

                if (isThisAlarmActive && !overlayShowing) {
                    // 正常情况：当前闹钟在震动但没有覆盖层 → 手动进入挑战
                    if (ensureAlarmOverlayPermission(ctx)) {
                        val intent = android.content.Intent(ctx, AlarmActivity::class.java).apply {
                            putExtra(AlarmScheduler.EXTRA_ALARM_ID, activeAlarmId)
                        }
                        startActivity(intent)
                    }
                } else {
                    // 异常点击：闹钟没有在震动，或覆盖层已经显示
                    val reason = if (overlayShowing) {
                        "挑战界面已经在显示了，请回到前台查看"
                    } else if (isAlarmActive) {
                        "当前响铃的不是这个闹钟，请点击正在响铃闹钟对应的「挑战」按钮。"
                    } else {
                        "当前没有正在响铃的闹钟。\n\n「挑战」按钮仅在闹钟震动但挑战界面没有自动弹出时使用，用于手动关闭闹钟。"
                    }
                    androidx.appcompat.app.AlertDialog.Builder(ctx)
                        .setTitle("无法进入挑战")
                        .setMessage(reason)
                        .setPositiveButton("知道了", null)
                        .show()
                }
            },
            onSystemBackupClick = { alarm ->
                addSystemAlarm(alarm)
            },
            onSystemBackupHelp = {
                showSystemBackupHelpDialog()
            }
        )

        recyclerView?.layoutManager = LinearLayoutManager(requireContext())
        recyclerView?.adapter = adapter
        recyclerView?.isNestedScrollingEnabled = false

        btnAdd?.setOnClickListener {
            if (ensureAlarmOverlayPermission()) showEditDialog(null)
        }


        view.findViewById<View>(R.id.btn_emergency_whitelist)?.setOnClickListener {
            val intent = Intent(requireContext(), AppSelectionActivity::class.java).apply {
                putExtra(AppSelectionActivity.EXTRA_MODE, AppSelectionActivity.MODE_ALARM_EMERGENCY_WHITELIST)
            }
            startActivity(intent)
        }

        tvKeepaliveSummary = view.findViewById(R.id.tv_keepalive_summary)
        tvEmergencyExitSummary = view.findViewById(R.id.tv_emergency_exit_summary)
        view.findViewById<View>(R.id.layout_alarm_emergency_exit_settings)?.setOnClickListener {
            showEmergencyExitSettingsDialog()
        }
        refreshEmergencyExitSummary()
        tvChallengeSummary = view.findViewById(R.id.tv_challenge_summary)
        tvUnlockSummary = view.findViewById(R.id.tv_unlock_summary)

        // 闹钟保活独立卡片入口（主界面常驻）
        view.findViewById<View>(R.id.layout_alarm_keepalive)?.setOnClickListener {
            showAlarmKeepAliveGuideDialog()
        }

        // 隐藏已合并的分散设置卡片
        listOf(R.id.layout_challenge_settings, R.id.layout_unlock_settings).forEach { id ->
            (view.findViewById<View>(id)?.parent as? View)?.visibility = View.GONE
        }

        // 收藏本按钮（合并为 1 个入口，内部 tab 切换）
        view.findViewById<Button>(R.id.btn_bookmarked_poems)?.apply {
            text = "收藏本"
            setOnClickListener {
                startActivity(Intent(requireContext(), BookmarkActivity::class.java))
            }
        }
        view.findViewById<Button>(R.id.btn_custom_question_bank)?.setOnClickListener {
            startActivity(Intent(requireContext(), com.decikeeper.app.ui.questionbank.CustomQuestionBankActivity::class.java))
        }
        view.findViewById<Button>(R.id.btn_bookmarked_words)?.visibility = View.GONE
        view.findViewById<Button>(R.id.btn_bookmarked_clozes)?.visibility = View.GONE

        refreshSettingsSummaries()
        reloadAlarms()
    }

    override fun onResume() {
        super.onResume()
        refreshEmergencyExitSummary()
        reloadAlarms()
        activeAlarmRefreshHandler.removeCallbacks(activeAlarmRefreshRunnable)
        activeAlarmRefreshHandler.post(activeAlarmRefreshRunnable)
    }

    /** 仅在用户真正进入闹钟页时检查一次保活配置。 */
    fun onEnteredAlarmTab() {
        if (!isAdded) return
        reloadAlarms()
        checkAndPromptAlarmKeepalive(requireContext())
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_PICK_RINGTONE && resultCode == android.app.Activity.RESULT_OK) {
            pendingRingtoneUri = data?.getParcelableExtra<android.net.Uri>(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)?.toString()
        }
    }

    override fun onPause() {
        activeAlarmRefreshHandler.removeCallbacks(activeAlarmRefreshRunnable)
        super.onPause()
    }

    // ===== 原有闹钟管理功能 =====

    private var hasPromptedImportCameraPermission = false

    private fun reloadAlarms() {
        alarms.clear()
        alarms.addAll(AlarmScheduler.getAlarms(requireContext()).sortedWith(compareBy({ it.hour }, { it.minute })))
        adapter?.updateAll(alarms)
        lastRenderedActiveAlarmId = AlarmScheduler.getActiveAlarmId(requireContext())
        lastRenderedOverlayShowing = LockWindowManager.isAlarmOverlayActuallyShowing
        checkExistingAlarmsPermissionsAndGuide()
    }

    private fun checkExistingAlarmsPermissionsAndGuide() {
        val ctx = context ?: return

        if (alarms.isEmpty()) return

        // 2. 如果存在已开启的闹钟使用了扫码挑战，但未授予相机权限，主动提示授权
        val hasQrChallenge = alarms.any {
            it.enabled && (it.useQr || "qr" in it.stage1Types || "qr" in it.stage2Types)
        }
        val hasCamera = ContextCompat.checkSelfPermission(
            ctx,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED

        if (hasQrChallenge && !hasCamera && !hasPromptedImportCameraPermission) {
            hasPromptedImportCameraPermission = true
            AlertDialog.Builder(ctx)
                .setTitle("📷 需要相机权限")
                .setMessage("检测到已有开启的闹钟包含「扫码」关卡，但当前尚未获得相机权限。若未授权，锁屏下将无法直接扫码关闹钟。")
                .setPositiveButton("立即授权") { _, _ ->
                    cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                }
                .setNegativeButton("稍后设置", null)
                .show()
        }
    }

    private fun refreshChallengeButtonsIfNeeded() {
        val ctx = context ?: return
        val activeAlarmId = AlarmScheduler.getActiveAlarmId(ctx)
        val overlayShowing = LockWindowManager.isAlarmOverlayActuallyShowing
        if (activeAlarmId != lastRenderedActiveAlarmId || overlayShowing != lastRenderedOverlayShowing) {
            lastRenderedActiveAlarmId = activeAlarmId
            lastRenderedOverlayShowing = overlayShowing
            adapter?.notifyDataSetChanged()
        }
    }

    // ===== 默认设置统一弹窗 =====

    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()

    private fun verifyAlarmSyncResult() {
        val ctx = context ?: return
        val prefs = ctx.getSharedPreferences("alarm_sync_pending", android.content.Context.MODE_PRIVATE)
        if (!prefs.getBoolean("pending", false)) return
        val hour = prefs.getInt("expected_hour", -1)
        val minute = prefs.getInt("expected_minute", -1)
        val initiatedAt = prefs.getLong("initiated_at", 0)
        prefs.edit().clear().apply()
        if (System.currentTimeMillis() - initiatedAt > 5 * 60 * 1000) return
        val timeStr = String.format("%02d:%02d", hour, minute)
        AlertDialog.Builder(ctx)
            .setTitle("系统闹钟校验")
            .setMessage("是否已在系统闹钟中保存 ${timeStr} 的闹钟？")
            .setPositiveButton("已保存") { _, _ ->
                Toast.makeText(ctx, "系统闹钟 ${timeStr} 已确认 ✓", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("未保存") { _, _ ->
                Toast.makeText(ctx, "请重新创建系统闹钟", Toast.LENGTH_SHORT).show()
            }
            .show()
    }
    private fun refreshSettingsSummaries() {
        val ctx = context ?: return
        // 统一入口不显示单项摘要，避免误解"只能设置再响"
        tvSnoozeSummary?.text = ""
        val keepaliveOk = isAlarmKeepaliveSatisfied(ctx)
        tvKeepaliveSummary?.text = if (keepaliveOk) "已就绪 ✓" else "待配置（防睡眠冻结/后台保活）"

        val math = AlarmScheduler.getDefaultMathCount(ctx)
        val poem = AlarmScheduler.getDefaultPoemCount(ctx)
        val word = AlarmScheduler.getDefaultWordCount(ctx)
        val cloze = AlarmScheduler.getDefaultClozeCount(ctx)
        val custom = AlarmScheduler.getDefaultCustomCount(ctx)
        tvChallengeSummary?.text = "数学${math}道/诗词${poem}句/单词${word}个/选词${cloze}道/题库${custom}道"

        val mode = AlarmScheduler.getDefaultUnlockMode(ctx)
        val s1 = AlarmScheduler.getDefaultStage1Types(ctx)
        val s2 = AlarmScheduler.getDefaultStage2Types(ctx)
        val modeLabel = if (mode == AlarmScheduler.UnlockMode.TWO_STAGE) "双阶段" else "单次"
        val s1Label = s1.joinToString("/") { t -> mapTypeLabel(t) }
        val s2Label = s2.joinToString("/") { t -> mapTypeLabel(t) }
        tvUnlockSummary?.text = if (mode == AlarmScheduler.UnlockMode.TWO_STAGE) {
            "${modeLabel} | 一关:$s1Label | 二关:$s2Label"
        } else {
            "${modeLabel} | $s1Label"
        }
    }

    private fun mapTypeLabel(type: String): String = when (type) {
        "qr" -> "扫码"
        "math" -> "数学"
        "poem" -> "诗词"
        "word" -> "单词"
        "cloze" -> "选词"
        "custom" -> "题库"
        else -> type
    }


    data class UnifiedBankSelectionResult(
        val isClozeChecked: () -> Boolean,
        val getSelectedBankIds: () -> Set<String>
    )

    private fun setupUnifiedBankSelection(
        context: Context,
        container: LinearLayout?,
        isClozeSelected: Boolean,
        selectedBankMap: Map<String, Int>
    ): UnifiedBankSelectionResult {
        if (container == null) return UnifiedBankSelectionResult({ false }, { emptySet() })
        container.removeAllViews()
        container.visibility = View.VISIBLE

        val allBanks = com.decikeeper.app.manager.questionbank.CustomQuestionBankManager.getBanks(context)

        // 第一项：选词填空
        val clozeCb = CheckBox(context).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            text = "选词填空"
            textSize = 12f
            includeFontPadding = false
            setTextColor(ThemeManager.getColorAttr(context, R.attr.zzText))
            isChecked = isClozeSelected
        }

        val bankCheckBoxes = mutableListOf<Pair<String, CheckBox>>()
        val allItems = mutableListOf<View>()
        allItems.add(clozeCb)

        // 后续项：自定义题库（如考公、考研），紧随选词填空排布在同一流式网格中
        for (bank in allBanks) {
            val bankCb = CheckBox(context).apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                text = bank.title
                textSize = 12f
                maxLines = 2
                includeFontPadding = false
                setTextColor(ThemeManager.getColorAttr(context, R.attr.zzText))
                isChecked = selectedBankMap.containsKey(bank.id)
            }
            bankCheckBoxes.add(Pair(bank.id, bankCb))
            allItems.add(bankCb)
        }

        // 自适应列数：若题库名称均为短名称（<=3字），采用标准 4 列；若在 4-5 字（包括选词填空）自适应为 2 列；若 >5 字长名称自适应为 1 列整行展开
        val maxTitleLen = (listOf("选词填空") + allBanks.map { it.title }).maxOfOrNull { it.length } ?: 4
        val numCols = when {
            maxTitleLen <= 3 -> 4
            maxTitleLen <= 5 -> 2
            else -> 1
        }

        val chunks = allItems.chunked(numCols)
        for (chunk in chunks) {
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(2); bottomMargin = dp(2) }
                gravity = Gravity.CENTER_VERTICAL
            }
            for (view in chunk) {
                row.addView(view)
            }
            if (chunk.size < numCols) {
                for (i in 0 until (numCols - chunk.size)) {
                    val space = Space(context).apply {
                        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    }
                    row.addView(space)
                }
            }
            container.addView(row)
        }

        return UnifiedBankSelectionResult(
            isClozeChecked = { clozeCb.isChecked },
            getSelectedBankIds = {
                bankCheckBoxes.filter { it.second.isChecked }.map { it.first }.toSet()
            }
        )
    }

    private fun setupCustomBankCounts(
        context: Context,
        container: LinearLayout?,
        savedCounts: Map<String, Int>,
        defaultCount: Int = 3
    ): () -> Map<String, Int> {
        if (container == null) return { emptyMap() }
        container.removeAllViews()
        val allBanks = com.decikeeper.app.manager.questionbank.CustomQuestionBankManager.getBanks(context)
        if (allBanks.isEmpty()) {
            container.visibility = View.GONE
            return { emptyMap() }
        }
        container.visibility = View.VISIBLE

        val countMap = mutableMapOf<String, EditText>()

        // 按名称长度智能排布：短名称（<=3字）可两两并排为双列；长名称（>3字）独占一行，弹性撑满防截断
        val rows = mutableListOf<List<com.decikeeper.app.data.questionbank.CustomQuestionBank>>()
        val shortBuffer = mutableListOf<com.decikeeper.app.data.questionbank.CustomQuestionBank>()
        for (bank in allBanks) {
            if (bank.title.length > 3) {
                if (shortBuffer.isNotEmpty()) {
                    rows.add(shortBuffer.toList())
                    shortBuffer.clear()
                }
                rows.add(listOf(bank))
            } else {
                shortBuffer.add(bank)
                if (shortBuffer.size == 2) {
                    rows.add(shortBuffer.toList())
                    shortBuffer.clear()
                }
            }
        }
        if (shortBuffer.isNotEmpty()) {
            rows.add(shortBuffer.toList())
        }

        for (chunk in rows) {
            val isSingleLongRow = chunk.size == 1 && chunk[0].title.length > 3
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = dp(7)
                }
                gravity = Gravity.CENTER_VERTICAL
            }

            if (isSingleLongRow) {
                val bank = chunk[0]
                val tvTitle = TextView(context).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { marginEnd = dp(8) }
                    text = bank.title
                    textSize = 13f
                    maxLines = 2
                    setTextColor(ThemeManager.getColorAttr(context, R.attr.zzText))
                }

                val etCount = EditText(context).apply {
                    layoutParams = LinearLayout.LayoutParams(dp(52), dp(38))
                    inputType = android.text.InputType.TYPE_CLASS_NUMBER
                    gravity = Gravity.CENTER
                    textSize = 13f
                    setTextColor(ThemeManager.getColorAttr(context, R.attr.zzText))
                    setBackgroundResource(R.drawable.bg_input_field)
                    val initialVal = savedCounts[bank.id] ?: AlarmScheduler.getBankDefaultCount(context, bank.id, defaultCount)
                    setText(initialVal.toString())
                }
                countMap[bank.id] = etCount

                val tvUnit = TextView(context).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                    text = " 道"
                    textSize = 12f
                    setTextColor(ThemeManager.getColorAttr(context, R.attr.zzMuted))
                }

                row.addView(tvTitle)
                row.addView(etCount)
                row.addView(tvUnit)
            } else {
                for ((index, bank) in chunk.withIndex()) {
                    val col = LinearLayout(context).apply {
                        orientation = LinearLayout.HORIZONTAL
                        layoutParams = LinearLayout.LayoutParams(
                            0,
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                            1f
                        ).apply {
                            if (index > 0) marginStart = dp(8)
                        }
                        gravity = Gravity.CENTER_VERTICAL
                    }

                    val tvTitle = TextView(context).apply {
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                        ).apply { marginEnd = dp(6) }
                        text = bank.title
                        textSize = 13f
                        maxLines = 1
                        ellipsize = android.text.TextUtils.TruncateAt.END
                        setTextColor(ThemeManager.getColorAttr(context, R.attr.zzText))
                    }

                    val etCount = EditText(context).apply {
                        layoutParams = LinearLayout.LayoutParams(dp(52), dp(38))
                        inputType = android.text.InputType.TYPE_CLASS_NUMBER
                        gravity = Gravity.CENTER
                        textSize = 13f
                        setTextColor(ThemeManager.getColorAttr(context, R.attr.zzText))
                        setBackgroundResource(R.drawable.bg_input_field)
                        val initialVal = savedCounts[bank.id] ?: AlarmScheduler.getBankDefaultCount(context, bank.id, defaultCount)
                        setText(initialVal.toString())
                    }
                    countMap[bank.id] = etCount

                    val tvUnit = TextView(context).apply {
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                        )
                        text = " 道"
                        textSize = 12f
                        setTextColor(ThemeManager.getColorAttr(context, R.attr.zzMuted))
                    }

                    col.addView(tvTitle)
                    col.addView(etCount)
                    col.addView(tvUnit)
                    row.addView(col)
                }

                if (chunk.size == 1) {
                    val space = Space(context).apply {
                        layoutParams = LinearLayout.LayoutParams(
                            0,
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                            1f
                        ).apply {
                            marginStart = dp(8)
                        }
                    }
                    row.addView(space)
                }
            }

            container.addView(row)
        }

        return {
            val result = mutableMapOf<String, Int>()
            for ((bankId, et) in countMap) {
                val cnt = et.text.toString().toIntOrNull()?.coerceAtLeast(1) ?: defaultCount
                result[bankId] = cnt
            }
            result
        }
    }

    /** 新增 / 编辑闹钟界面一次提交的全部取值；新增、存为默认、测试三个出口共用。 */
    private data class EditForm(
        val hour: Int,
        val minute: Int,
        val days: BooleanArray,
        val isTwoStage: Boolean,
        val s1Types: Set<String>,
        val s2Types: Set<String>,
        val s1CustomBanks: Map<String, Int>,
        val s2CustomBanks: Map<String, Int>,
        val bankCounts: Map<String, Int>,
        val defaultCustomCount: Int,
        val mathCount: Int,
        val poemCount: Int,
        val wordCount: Int,
        val clozeCount: Int,
        val customCount: Int,
        val stageCooldownSec: Int,
        val snoozeMin: Int,
        val snoozeMax: Int,
        val s1Reminder: AlarmReminderSettingsView.Config,
        val s2UseIndependent: Boolean,
        val s2Reminder: AlarmReminderSettingsView.Config
    )

    private fun showEditDialog(existing: AlarmScheduler.AlarmEntry?) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_alarm_edit, null)
        var selectedHour = existing?.hour ?: 7
        var selectedMinute = existing?.minute ?: 0
        val tvAlarmEditTime = dialogView.findViewById<TextView>(R.id.tv_alarm_edit_time)
        val layoutPickAlarmTime = dialogView.findViewById<View>(R.id.layout_pick_alarm_time)
        fun updateTimeDisplay() {
            tvAlarmEditTime?.text = String.format(java.util.Locale.getDefault(), "%02d:%02d", selectedHour, selectedMinute)
        }
        updateTimeDisplay()
        layoutPickAlarmTime?.setOnClickListener {
            android.app.TimePickerDialog(requireContext(), { _, h, m ->
                selectedHour = h
                selectedMinute = m
                updateTimeDisplay()
            }, selectedHour, selectedMinute, true).show()
        }

        val cbMon = dialogView.findViewById<android.widget.CheckBox>(R.id.cb_day_mon)
        val cbTue = dialogView.findViewById<android.widget.CheckBox>(R.id.cb_day_tue)
        val cbWed = dialogView.findViewById<android.widget.CheckBox>(R.id.cb_day_wed)
        val cbThu = dialogView.findViewById<android.widget.CheckBox>(R.id.cb_day_thu)
        val cbFri = dialogView.findViewById<android.widget.CheckBox>(R.id.cb_day_fri)
        val cbSat = dialogView.findViewById<android.widget.CheckBox>(R.id.cb_day_sat)
        val cbSun = dialogView.findViewById<android.widget.CheckBox>(R.id.cb_day_sun)
        val checkboxes = listOf(cbMon, cbTue, cbWed, cbThu, cbFri, cbSat, cbSun)

        val rgUnlockMode = dialogView.findViewById<android.widget.RadioGroup>(R.id.rg_unlock_mode)
        val rbUnlockSingle = dialogView.findViewById<android.widget.RadioButton>(R.id.rb_unlock_single)
        val rbUnlockTwoStage = dialogView.findViewById<android.widget.RadioButton>(R.id.rb_unlock_two_stage)

        // Stage 1
        val cbS1Qr = dialogView.findViewById<android.widget.CheckBox>(R.id.cb_s1_qr)
        val cbS1Math = dialogView.findViewById<android.widget.CheckBox>(R.id.cb_s1_math)
        val cbS1Poem = dialogView.findViewById<android.widget.CheckBox>(R.id.cb_s1_poem)
        val cbS1Word = dialogView.findViewById<android.widget.CheckBox>(R.id.cb_s1_word)
        val cbS1Cloze = dialogView.findViewById<android.widget.CheckBox>(R.id.cb_s1_cloze)
        val etMathCount = dialogView.findViewById<EditText>(R.id.et_math_count)
        val etPoemCount = dialogView.findViewById<EditText>(R.id.et_poem_count)
        val etWordCount = dialogView.findViewById<EditText>(R.id.et_word_count)
        val etClozeCount = dialogView.findViewById<EditText>(R.id.et_cloze_count)
        val etCustomCount = dialogView.findViewById<EditText>(R.id.et_custom_count)
        val layoutStage1 = dialogView.findViewById<View>(R.id.layout_stage1)
        val tvStage1Label = dialogView.findViewById<TextView>(R.id.tv_stage1_label)

        val layoutStage2 = dialogView.findViewById<LinearLayout>(R.id.layout_stage2)
        val cbS2Qr = dialogView.findViewById<android.widget.CheckBox>(R.id.cb_s2_qr)
        val cbS2Math = dialogView.findViewById<android.widget.CheckBox>(R.id.cb_s2_math)
        val cbS2Poem = dialogView.findViewById<android.widget.CheckBox>(R.id.cb_s2_poem)
        val cbS2Word = dialogView.findViewById<android.widget.CheckBox>(R.id.cb_s2_word)
        val cbS2Cloze = dialogView.findViewById<android.widget.CheckBox>(R.id.cb_s2_cloze)

        val innerLayout = dialogView.findViewById<LinearLayout>(R.id.layout_alarm_edit_content)
        val layoutStage1Cooldown = dialogView.findViewById<View>(R.id.layout_stage1_cooldown)
        val etEditCooldown = dialogView.findViewById<EditText>(R.id.et_stage_cooldown) ?: EditText(requireContext())
        etEditCooldown.setText((existing?.stageCooldownSeconds ?: AlarmScheduler.getDefaultStageCooldownSeconds(requireContext())).toString())

        val etSnoozeMinutes = dialogView.findViewById<EditText>(R.id.et_snooze_minutes)
        val etSnoozeMaxCount = dialogView.findViewById<EditText>(R.id.et_snooze_max_count)
        etSnoozeMinutes?.setText(AlarmScheduler.getSnoozeMinutes(requireContext()).toString())
        etSnoozeMaxCount?.setText(AlarmScheduler.getMaxSnoozeCount(requireContext()).toString())

        val layoutS1CustomBanks = dialogView.findViewById<LinearLayout>(R.id.layout_s1_custom_banks)
        val layoutS2CustomBanks = dialogView.findViewById<LinearLayout>(R.id.layout_s2_custom_banks)
        val s1BankMap = existing?.stage1CustomBanks ?: AlarmScheduler.getDefaultStage1CustomBanks(requireContext())
        val s2BankMap = existing?.stage2CustomBanks ?: AlarmScheduler.getDefaultStage2CustomBanks(requireContext())
        val isS1Cloze = if (existing != null) "cloze" in existing.stage1Types else "cloze" in AlarmScheduler.getDefaultStage1Types(requireContext())
        val isS2Cloze = if (existing != null) "cloze" in existing.stage2Types else "cloze" in AlarmScheduler.getDefaultStage2Types(requireContext())
        val s1Result = setupUnifiedBankSelection(requireContext(), layoutS1CustomBanks, isS1Cloze, s1BankMap)
        val s2Result = setupUnifiedBankSelection(requireContext(), layoutS2CustomBanks, isS2Cloze, s2BankMap)

        val layoutCustomBankCounts = dialogView.findViewById<LinearLayout>(R.id.layout_custom_bank_counts)
        val editSavedCounts = if (existing != null) {
            existing.stage1CustomBanks + existing.stage2CustomBanks
        } else {
            AlarmScheduler.getDefaultStage1CustomBanks(requireContext()) + AlarmScheduler.getDefaultStage2CustomBanks(requireContext())
        }
        val getCustomBankCounts = setupCustomBankCounts(
            requireContext(),
            layoutCustomBankCounts,
            editSavedCounts,
            existing?.customCount ?: AlarmScheduler.getDefaultCustomCount(requireContext())
        )

        // 提醒设置：第一关卡片 + 第二关独立提醒开关与卡片放在同一区块，
        // 开关紧挨着它所控制的内容，不再挂在第二关任务列表的最底部。
        val reminderCtx = requireContext()
        val editReminderSection = LinearLayout(reminderCtx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(16) }
        }
        editReminderSection.addView(TextView(reminderCtx).apply {
            text = "提醒设置"
            textSize = 16f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(ThemeManager.getColorAttr(reminderCtx, R.attr.zzText))
        })
        val tvS1ReminderLabel = TextView(reminderCtx).apply {
            text = "第一关"
            textSize = 12f
            setTextColor(ThemeManager.getColorAttr(reminderCtx, R.attr.zzMuted))
            setPadding(0, dp(8), 0, 0)
        }
        editReminderSection.addView(tvS1ReminderLabel)
        val editS1Reminder = AlarmReminderSettingsView(reminderCtx, showRingtone = true).apply {
            setConfig(
                AlarmReminderSettingsView.Config(
                    alarmMode = existing?.alarmMode ?: AlarmScheduler.getDefaultAlarmMode(reminderCtx),
                    vibrationMode = existing?.vibrationMode ?: AlarmScheduler.getDefaultVibrationMode(reminderCtx),
                    vibrationIntensity = existing?.vibrationIntensity ?: AlarmScheduler.getDefaultVibrationIntensity(reminderCtx),
                    ringVolume = existing?.ringVolume ?: AlarmScheduler.getDefaultRingVolume(reminderCtx),
                    ringtoneUri = existing?.ringtoneUri ?: AlarmScheduler.getDefaultRingtoneUri(reminderCtx),
                    progressiveMaxRingVolume = existing?.progressiveMaxRingVolume
                        ?: AlarmScheduler.getDefaultProgressiveMaxRingVolume(reminderCtx),
                    progressiveMaxVibrationIntensity = existing?.progressiveMaxIntensity
                        ?: AlarmScheduler.getDefaultProgressiveMaxIntensity(reminderCtx)
                )
            )
            onPreviewVolume = { level -> AlarmVolumePreview.play(reminderCtx, getConfig().ringtoneUri, level, getConfig().vibrationMode) }
            onPreviewVibration = { level -> AlarmVibrationPreview.play(reminderCtx, level) }
            onPickRingtone = {
                onRingtonePickedCallback = { uri, _ -> setRingtone(uri) }
                ringtonePickerLauncher.launch(AlarmRingtonePickerActivity.createIntent(reminderCtx, getConfig().ringtoneUri))
            }
        }
        editReminderSection.addView(editS1Reminder, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })

        val editSwitchStage2Independent = androidx.appcompat.widget.SwitchCompat(reminderCtx).apply {
            isChecked = existing?.stage2UseIndependentAlertPolicy ?: AlarmScheduler.getDefaultStage2UseIndependentAlert(reminderCtx)
        }
        val editStage2IndependentRow = LinearLayout(reminderCtx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundResource(R.drawable.bg_zz_card_soft)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            isClickable = true
            isFocusable = true
            setOnClickListener { editSwitchStage2Independent.toggle() }
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) }
        }
        val s2IndependentTexts = LinearLayout(reminderCtx).apply { orientation = LinearLayout.VERTICAL }
        s2IndependentTexts.addView(TextView(reminderCtx).apply {
            text = "第二关使用独立提醒设置"
            textSize = 14f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(ThemeManager.getColorAttr(reminderCtx, R.attr.zzText))
        })
        s2IndependentTexts.addView(TextView(reminderCtx).apply {
            text = "关闭时，第二关沿用上面的提醒设置"
            textSize = 12f
            setTextColor(ThemeManager.getColorAttr(reminderCtx, R.attr.zzMuted))
        })
        editStage2IndependentRow.addView(s2IndependentTexts, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        editStage2IndependentRow.addView(editSwitchStage2Independent)
        editReminderSection.addView(editStage2IndependentRow)

        val editS2Reminder = AlarmReminderSettingsView(reminderCtx, showRingtone = false).apply {
            setConfig(
                AlarmReminderSettingsView.Config(
                    alarmMode = existing?.stage2AlarmMode ?: AlarmScheduler.getDefaultStage2AlarmMode(reminderCtx),
                    vibrationMode = existing?.stage2VibrationMode ?: AlarmScheduler.getDefaultStage2VibrationMode(reminderCtx),
                    vibrationIntensity = existing?.stage2VibrationIntensity ?: AlarmScheduler.getDefaultStage2VibrationIntensity(reminderCtx),
                    ringVolume = existing?.stage2RingVolume ?: AlarmScheduler.getDefaultStage2RingVolume(reminderCtx),
                    progressiveMaxRingVolume = existing?.stage2ProgressiveMaxRingVolume
                        ?: AlarmScheduler.getDefaultStage2ProgressiveMaxRingVolume(reminderCtx),
                    progressiveMaxVibrationIntensity = existing?.stage2ProgressiveMaxIntensity
                        ?: AlarmScheduler.getDefaultStage2ProgressiveMaxIntensity(reminderCtx)
                )
            )
            // 第二关卡片没有提示音选择，沿用第一关当前所选。
            onPreviewVolume = { level -> AlarmVolumePreview.play(reminderCtx, editS1Reminder.getConfig().ringtoneUri, level, getConfig().vibrationMode) }
            onPreviewVibration = { level -> AlarmVibrationPreview.play(reminderCtx, level) }
        }
        editReminderSection.addView(editS2Reminder, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
        innerLayout.addView(editReminderSection)

        fun updateReminderStage2Visibility() {
            val isTwo = rgUnlockMode.checkedRadioButtonId == R.id.rb_unlock_two_stage
            tvS1ReminderLabel.visibility = if (isTwo) View.VISIBLE else View.GONE
            editStage2IndependentRow.visibility = if (isTwo) View.VISIBLE else View.GONE
            editS2Reminder.visibility = if (isTwo && editSwitchStage2Independent.isChecked) View.VISIBLE else View.GONE
        }
        editSwitchStage2Independent.setOnCheckedChangeListener { _, _ -> updateReminderStage2Visibility() }

        fun updateStage2Visibility() {
            val isTwoStage = rgUnlockMode.checkedRadioButtonId == R.id.rb_unlock_two_stage
            layoutStage2.visibility = if (isTwoStage) View.VISIBLE else View.GONE
            tvStage1Label.text = if (isTwoStage) "第一关解锁方式（通过其一即可）" else "解锁方式配置（可并联多选）"
            layoutStage1Cooldown?.visibility = View.VISIBLE
            updateReminderStage2Visibility()
        }
        rgUnlockMode.setOnCheckedChangeListener { _, _ -> updateStage2Visibility() }

        val hasCamera = ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        if (existing != null) {
            checkboxes.forEachIndexed { index, cb -> cb.isChecked = existing.days.getOrNull(index) == true }

            val s1 = existing.stage1Types
            val s2 = existing.stage2Types
            cbS1Qr.isChecked = ("qr" in s1) && hasCamera
            cbS1Math.isChecked = "math" in s1
            cbS1Poem.isChecked = "poem" in s1
            cbS1Word.isChecked = "word" in s1
            cbS1Cloze?.isChecked = "cloze" in s1
            etMathCount.setText(existing.mathCount.toString())
            etPoemCount.setText(existing.poemCount.toString())
            etWordCount.setText(existing.wordCount.toString())
            etClozeCount?.setText(existing.clozeCount.toString())
            etCustomCount?.setText(existing.customCount.toString())
            cbS2Qr.isChecked = ("qr" in s2) && hasCamera
            cbS2Math.isChecked = "math" in s2
            cbS2Poem.isChecked = "poem" in s2
            cbS2Word.isChecked = "word" in s2
            cbS2Cloze?.isChecked = "cloze" in s2

            if (existing.unlockMode == AlarmScheduler.UnlockMode.TWO_STAGE) rbUnlockTwoStage.isChecked = true
            else rbUnlockSingle.isChecked = true
        } else {
            checkboxes.forEach { it.isChecked = true }
            val defaultCtx = requireContext()
            val defS1 = AlarmScheduler.getDefaultStage1Types(defaultCtx)
            val defS2 = AlarmScheduler.getDefaultStage2Types(defaultCtx)
            val defMode = AlarmScheduler.getDefaultUnlockMode(defaultCtx)
            cbS1Qr.isChecked = ("qr" in defS1) && hasCamera
            cbS1Math.isChecked = "math" in defS1
            cbS1Poem.isChecked = "poem" in defS1
            cbS1Word.isChecked = "word" in defS1
            cbS1Cloze?.isChecked = "cloze" in defS1
            etMathCount.setText(AlarmScheduler.getDefaultMathCount(defaultCtx).toString())
            etPoemCount.setText(AlarmScheduler.getDefaultPoemCount(defaultCtx).toString())
            etWordCount.setText(AlarmScheduler.getDefaultWordCount(defaultCtx).toString())
            etClozeCount?.setText(AlarmScheduler.getDefaultClozeCount(defaultCtx).toString())
            etCustomCount?.setText(AlarmScheduler.getDefaultCustomCount(defaultCtx).toString())
            cbS2Qr.isChecked = ("qr" in defS2) && hasCamera
            cbS2Math.isChecked = "math" in defS2
            cbS2Poem.isChecked = "poem" in defS2
            cbS2Word.isChecked = "word" in defS2
            cbS2Cloze?.isChecked = "cloze" in defS2
            if (defMode == AlarmScheduler.UnlockMode.TWO_STAGE) rbUnlockTwoStage.isChecked = true
            else rbUnlockSingle.isChecked = true
        }
        bindQrCheckboxPermission(cbS1Qr)
        bindQrCheckboxPermission(cbS2Qr)
        updateStage2Visibility()

        val editDialog = AlertDialog.Builder(requireContext())
            .setView(dialogView)
            .create()
        editDialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
        // 关闭弹窗（保存 / 测试 / 取消 / 点外面）时打断还在播的声音试听并还原闹钟通道音量，同时停掉试震。
        editDialog.setOnDismissListener {
            AlarmVolumePreview.stop()
            AlarmVibrationPreview.stop()
        }
        editDialog.show()

        dialogView.findViewById<TextView>(R.id.tv_alarm_edit_title).text = if (existing == null) "新增闹钟" else "编辑闹钟"
        dialogView.findViewById<Button>(R.id.btn_alarm_edit_save).text = "保存"

        // 校验并收集整个表单；任一项不合法时已弹 Toast 并返回 null。新增 / 存为默认 / 测试三个出口共用。
        fun collectForm(): EditForm? {
            val c = requireContext()
            val hour = selectedHour
            val minute = selectedMinute
            val days = BooleanArray(7) { index -> checkboxes[index].isChecked }
            val normalizedDays = if (days.none { it }) BooleanArray(7) { true } else days

            val isTwoStage = rgUnlockMode.checkedRadioButtonId == R.id.rb_unlock_two_stage
            val hasCameraNow = ContextCompat.checkSelfPermission(c, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
            val bankCounts = getCustomBankCounts()
            val defaultCustomCount = AlarmScheduler.getDefaultCustomCount(c)
            val s1BankIds = s1Result.getSelectedBankIds()
            val s2BankIds = if (isTwoStage) s2Result.getSelectedBankIds() else s1BankIds
            val s1CustomBanks = s1BankIds.associateWith { bankCounts[it] ?: defaultCustomCount }
            val s2CustomBanks = s2BankIds.associateWith { bankCounts[it] ?: defaultCustomCount }

            val s1Types = mutableSetOf<String>()
            if (cbS1Qr.isChecked && hasCameraNow) s1Types.add("qr")
            if (cbS1Math.isChecked) s1Types.add("math")
            if (cbS1Poem.isChecked) s1Types.add("poem")
            if (cbS1Word.isChecked) s1Types.add("word")
            if (s1Result.isClozeChecked()) s1Types.add("cloze")
            if (s1CustomBanks.isNotEmpty()) s1Types.add("custom")

            val s2Types = mutableSetOf<String>()
            if (isTwoStage) {
                if (cbS2Qr.isChecked && hasCameraNow) s2Types.add("qr")
                if (cbS2Math.isChecked) s2Types.add("math")
                if (cbS2Poem.isChecked) s2Types.add("poem")
                if (cbS2Word.isChecked) s2Types.add("word")
                if (s2Result.isClozeChecked()) s2Types.add("cloze")
                if (s2CustomBanks.isNotEmpty()) s2Types.add("custom")
            } else {
                s2Types.addAll(s1Types)
            }

            // 严禁任一关为空门禁拦截
            if (isTwoStage) {
                if (s1Types.isEmpty()) {
                    Toast.makeText(c, "两阶段模式下，第一关必须至少选择一种挑战！", Toast.LENGTH_SHORT).show()
                    return null
                }
                if (s2Types.isEmpty()) {
                    Toast.makeText(c, "两阶段模式下，第二关必须至少选择一种挑战！", Toast.LENGTH_SHORT).show()
                    return null
                }
            } else if (s1Types.isEmpty()) {
                Toast.makeText(c, "请至少选择一种挑战！", Toast.LENGTH_SHORT).show()
                return null
            }

            val customSum = s1CustomBanks.values.sum() + s2CustomBanks.values.sum()
            val customCount = if (customSum > 0) (s1CustomBanks.values + s2CustomBanks.values).maxOrNull() ?: defaultCustomCount else defaultCustomCount
            return EditForm(
                hour = hour,
                minute = minute,
                days = normalizedDays,
                isTwoStage = isTwoStage,
                s1Types = s1Types,
                s2Types = s2Types,
                s1CustomBanks = s1CustomBanks,
                s2CustomBanks = s2CustomBanks,
                bankCounts = bankCounts,
                defaultCustomCount = defaultCustomCount,
                mathCount = etMathCount.text.toString().toIntOrNull() ?: AlarmScheduler.getDefaultMathCount(c),
                poemCount = etPoemCount.text.toString().toIntOrNull() ?: AlarmScheduler.getDefaultPoemCount(c),
                wordCount = etWordCount.text.toString().toIntOrNull() ?: AlarmScheduler.getDefaultWordCount(c),
                clozeCount = etClozeCount?.text.toString().toIntOrNull() ?: AlarmScheduler.getDefaultClozeCount(c),
                customCount = customCount,
                stageCooldownSec = etEditCooldown.text.toString().toIntOrNull()?.coerceIn(0, 3600) ?: 0,
                snoozeMin = etSnoozeMinutes?.text?.toString()?.toIntOrNull() ?: 10,
                snoozeMax = etSnoozeMaxCount?.text?.toString()?.toIntOrNull() ?: 2,
                s1Reminder = editS1Reminder.getConfig(),
                s2UseIndependent = isTwoStage && editSwitchStage2Independent.isChecked,
                s2Reminder = editS2Reminder.getConfig()
            )
        }

        // 这些是全局设置，新增 / 保存 / 存为默认时一并落盘。
        fun applyGlobalSettings(f: EditForm) {
            val c = requireContext()
            for ((bId, count) in f.bankCounts) AlarmScheduler.setBankDefaultCount(c, bId, count)
            if (f.snoozeMin > 0) AlarmScheduler.setSnoozeMinutes(c, f.snoozeMin)
            if (f.snoozeMax >= 0) AlarmScheduler.setMaxSnoozeCount(c, f.snoozeMax)
        }

        fun saveAsDefault(f: EditForm) {
            val c = requireContext()
            applyGlobalSettings(f)
            val r = f.s1Reminder
            // 渐进「最大震动 / 最大声音」与固定值互不覆盖，渐进最大值始终一并存为默认；
            // 固定震动强度只在固定模式下覆盖默认，避免把旧闹钟里遗留的 1 写成默认。
            if (r.vibrationMode == AlarmScheduler.VibrationMode.FIXED) {
                AlarmScheduler.setDefaultVibrationIntensity(c, r.vibrationIntensity)
            }
            AlarmScheduler.setDefaultProgressiveMaxIntensity(c, r.progressiveMaxVibrationIntensity)
            AlarmScheduler.setDefaultProgressiveMaxRingVolume(c, r.progressiveMaxRingVolume)
            AlarmScheduler.setDefaultStage2ProgressiveMaxIntensity(c, f.s2Reminder.progressiveMaxVibrationIntensity)
            AlarmScheduler.setDefaultStage2ProgressiveMaxRingVolume(c, f.s2Reminder.progressiveMaxRingVolume)
            if (f.mathCount > 0) AlarmScheduler.setDefaultMathCount(c, f.mathCount)
            if (f.poemCount > 0) AlarmScheduler.setDefaultPoemCount(c, f.poemCount)
            if (f.wordCount > 0) AlarmScheduler.setDefaultWordCount(c, f.wordCount)
            if (f.clozeCount > 0) AlarmScheduler.setDefaultClozeCount(c, f.clozeCount)
            AlarmScheduler.setDefaultCustomCount(c, f.bankCounts.values.maxOrNull() ?: f.defaultCustomCount)

            val mode = if (f.isTwoStage) AlarmScheduler.UnlockMode.TWO_STAGE else AlarmScheduler.UnlockMode.SINGLE
            AlarmScheduler.setDefaultUnlockMode(c, mode)
            AlarmScheduler.setDefaultStage1Types(c, f.s1Types)
            AlarmScheduler.setDefaultStage2Types(c, f.s2Types)
            AlarmScheduler.setDefaultStage1CustomBanks(c, f.s1CustomBanks)
            AlarmScheduler.setDefaultStage2CustomBanks(c, f.s2CustomBanks)
            AlarmScheduler.setDefaultStageCooldownSeconds(c, f.stageCooldownSec)

            AlarmScheduler.setDefaultAlarmMode(c, r.alarmMode)
            if (r.alarmMode != AlarmScheduler.AlarmMode.VIBRATE_ONLY) {
                r.ringtoneUri?.let { AlarmScheduler.setDefaultRingtoneUri(c, it) }
            }
            AlarmScheduler.setDefaultRingVolume(c, r.ringVolume)
            AlarmScheduler.setDefaultVibrationMode(c, r.vibrationMode)
            AlarmScheduler.setDefaultStage2Alert(
                c, f.s2UseIndependent,
                f.s2Reminder.alarmMode, f.s2Reminder.vibrationMode,
                f.s2Reminder.vibrationIntensity, f.s2Reminder.ringVolume
            )
            refreshSettingsSummaries()
        }

        // 测试只用当前表单试响一次，不保存任何东西。
        fun launchTest(f: EditForm) {
            val c = requireContext()
            val customSum = f.s1CustomBanks.values.sum() + f.s2CustomBanks.values.sum()
            val testCustomCount = if (customSum > 0) customSum else (f.bankCounts.values.maxOrNull() ?: f.defaultCustomCount)
            val config = LockWindowManager.TestAlarmConfig(
                useQr = "qr" in f.s1Types, useMath = "math" in f.s1Types, usePoem = "poem" in f.s1Types,
                useWord = "word" in f.s1Types, useCloze = "cloze" in f.s1Types,
                useCustom = "custom" in f.s1Types || "custom" in f.s2Types,
                mathCount = f.mathCount, poemCount = f.poemCount, wordCount = f.wordCount, clozeCount = f.clozeCount,
                customCount = testCustomCount,
                vibrationIntensity = f.s1Reminder.vibrationIntensity, vibrationMode = f.s1Reminder.vibrationMode,
                unlockMode = if (f.isTwoStage) AlarmScheduler.UnlockMode.TWO_STAGE else AlarmScheduler.UnlockMode.SINGLE,
                stage1Types = f.s1Types, stage2Types = f.s2Types,
                stageCooldownSeconds = f.stageCooldownSec,
                alarmMode = f.s1Reminder.alarmMode,
                ringtoneUri = f.s1Reminder.ringtoneUri ?: AlarmScheduler.getDefaultRingtoneUri(c),
                ringVolume = f.s1Reminder.ringVolume,
                progressiveMaxIntensity = f.s1Reminder.progressiveMaxVibrationIntensity,
                progressiveMaxRingVolume = f.s1Reminder.progressiveMaxRingVolume,
                stage1CustomBanks = f.s1CustomBanks,
                stage2CustomBanks = f.s2CustomBanks,
                snoozeMinutes = f.snoozeMin.coerceAtLeast(1),
                snoozeMaxCount = f.snoozeMax.coerceAtLeast(0),
                stage2UseIndependentAlertPolicy = f.s2UseIndependent,
                stage2AlarmMode = f.s2Reminder.alarmMode,
                stage2VibrationMode = f.s2Reminder.vibrationMode,
                stage2VibrationIntensity = f.s2Reminder.vibrationIntensity,
                stage2RingVolume = f.s2Reminder.ringVolume,
                stage2ProgressiveMaxRingVolume = f.s2Reminder.progressiveMaxRingVolume,
                stage2ProgressiveMaxIntensity = f.s2Reminder.progressiveMaxVibrationIntensity
            )
            LockWindowManager.setTestAlarmConfig(config)
            // 测试闹钟使用专用 TEST_ALARM_ID=0，与真实闹钟 id 命名空间隔离。
            startActivity(android.content.Intent(c, AlarmActivity::class.java).apply {
                putExtra(AlarmScheduler.EXTRA_ALARM_ID, AlarmScheduler.TEST_ALARM_ID)
            })
        }

        dialogView.findViewById<Button>(R.id.btn_alarm_edit_cancel).setOnClickListener { editDialog.dismiss() }

        dialogView.findViewById<Button>(R.id.btn_alarm_edit_test).setOnClickListener {
            if (!ensureAlarmOverlayPermission()) return@setOnClickListener
            val f = collectForm() ?: return@setOnClickListener
            launchTest(f)
            editDialog.dismiss()
        }

        dialogView.findViewById<Button>(R.id.btn_alarm_edit_save_default).setOnClickListener {
            val f = collectForm() ?: return@setOnClickListener
            saveAsDefault(f)
            // 保存默认后弹窗保持打开：可继续点「保存」把这条闹钟也存下来，或点「测试」
            Toast.makeText(requireContext(), "默认设置已保存", Toast.LENGTH_SHORT).show()
        }

        dialogView.findViewById<Button>(R.id.btn_alarm_edit_save).setOnClickListener {
            // 用户可能在编辑过程中从系统设置撤销悬浮窗权限，保存时再次校验。
            if (!ensureAlarmOverlayPermission()) return@setOnClickListener
            val f = collectForm() ?: return@setOnClickListener
            applyGlobalSettings(f)

            val c = requireContext()
            val useQr = "qr" in f.s1Types
            val useMath = "math" in f.s1Types
            val usePoem = "poem" in f.s1Types
            val useWord = "word" in f.s1Types
            val unlockMode = if (f.isTwoStage) AlarmScheduler.UnlockMode.TWO_STAGE else AlarmScheduler.UnlockMode.SINGLE
            val r1 = f.s1Reminder
            val r2 = f.s2Reminder
            val entry = if (existing == null) {
                AlarmScheduler.createAlarm(
                    c, f.hour, f.minute, true, f.days,
                    useQr, useMath, usePoem, useWord,
                    f.mathCount, f.poemCount, f.wordCount, f.clozeCount, f.customCount,
                    r1.vibrationIntensity, r1.vibrationMode, unlockMode,
                    f.s1Types, f.s2Types,
                    stageCooldownSeconds = f.stageCooldownSec,
                    ringtoneUri = r1.ringtoneUri,
                    alarmMode = r1.alarmMode,
                    ringVolume = r1.ringVolume,
                    progressiveMaxIntensity = r1.progressiveMaxVibrationIntensity,
                    progressiveMaxRingVolume = r1.progressiveMaxRingVolume,
                    autoEscalate = false,
                    stage1CustomBanks = f.s1CustomBanks,
                    stage2CustomBanks = f.s2CustomBanks,
                    stage2UseIndependentAlertPolicy = f.s2UseIndependent,
                    stage2AlarmMode = r2.alarmMode,
                    stage2VibrationMode = r2.vibrationMode,
                    stage2VibrationIntensity = r2.vibrationIntensity,
                    stage2RingVolume = r2.ringVolume,
                    stage2ProgressiveMaxRingVolume = r2.progressiveMaxRingVolume,
                    stage2ProgressiveMaxIntensity = r2.progressiveMaxVibrationIntensity
                )
            } else {
                existing.copy(
                    hour = f.hour, minute = f.minute, days = f.days,
                    useQr = useQr, useMath = useMath, usePoem = usePoem, useWord = useWord,
                    mathCount = f.mathCount, poemCount = f.poemCount, wordCount = f.wordCount,
                    clozeCount = f.clozeCount, customCount = f.customCount,
                    vibrationIntensity = r1.vibrationIntensity, vibrationMode = r1.vibrationMode,
                    unlockMode = unlockMode,
                    stage1Types = f.s1Types, stage2Types = f.s2Types,
                    stageCooldownSeconds = f.stageCooldownSec,
                    ringtoneUri = r1.ringtoneUri,
                    alarmMode = r1.alarmMode,
                    ringVolume = r1.ringVolume,
                    progressiveMaxIntensity = r1.progressiveMaxVibrationIntensity,
                    progressiveMaxRingVolume = r1.progressiveMaxRingVolume,
                    autoEscalate = false,
                    stage1CustomBanks = f.s1CustomBanks,
                    stage2CustomBanks = f.s2CustomBanks,
                    stage2UseIndependentAlertPolicy = f.s2UseIndependent,
                    stage2AlarmMode = r2.alarmMode,
                    stage2VibrationMode = r2.vibrationMode,
                    stage2VibrationIntensity = r2.vibrationIntensity,
                    stage2RingVolume = r2.ringVolume,
                    stage2ProgressiveMaxRingVolume = r2.progressiveMaxRingVolume,
                    stage2ProgressiveMaxIntensity = r2.progressiveMaxVibrationIntensity
                )
            }
            val isFirstAlarm = existing == null && alarms.isEmpty()
            upsertAlarm(entry)
            editDialog.dismiss()
            if (entry.enabled) {
                maybeShowKeepAliveGuideOnFirstActiveAlarm {
                    if (isFirstAlarm && isAdded) maybeSuggestSystemAlarm(entry)
                }
            }
        }
    }

    private fun upsertAlarm(entry: AlarmScheduler.AlarmEntry) {
        val index = alarms.indexOfFirst { it.id == entry.id }
        if (index >= 0) {
            alarms[index] = entry
        } else {
            alarms.add(entry)
        }
        persistAndRefresh()
    }

    private fun updateAlarm(entry: AlarmScheduler.AlarmEntry) {
        if (entry.enabled && !ensureAlarmOverlayPermission()) {
            // 还原开关对应的内存列表状态，避免无权限时出现“看起来已开启”的假状态。
            adapter?.updateAll(alarms)
            return
        }
        val index = alarms.indexOfFirst { it.id == entry.id }
        if (index >= 0) {
            alarms[index] = entry
            persistAndRefresh()
            checkExistingAlarmsPermissionsAndGuide()
        }
    }

    private fun confirmDelete(entry: AlarmScheduler.AlarmEntry) {
        AlertDialog.Builder(requireContext())
            .setTitle("删除闹钟")
            .setMessage("确定删除该闹钟吗？")
            .setPositiveButton("删除") { _, _ ->
                alarms.removeAll { it.id == entry.id }
                persistAndRefresh()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun systemAlarmCreationHelp(): String {
        val common = getString(R.string.system_alarm_create_help)
        return if (isVivoOrIqoo()) {
            getString(R.string.system_alarm_create_help_vivo) + "\n\n" + common
        } else common
    }

    private fun showSystemBackupHelpDialog() {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.system_alarm_create)
            .setMessage(systemAlarmCreationHelp())
            .setPositiveButton("知道了", null)
            .show()

    }

    private fun maybeSuggestSystemAlarm(alarm: AlarmScheduler.AlarmEntry) {
        val ctx = context ?: return
        val prefs = ctx.getSharedPreferences("decikeeper_oem_guide", Context.MODE_PRIVATE)
        if (prefs.getBoolean("system_alarm_suggestion_shown", false)) return
        val dialog = AlertDialog.Builder(ctx)
            .setTitle("建议创建相同时间的系统闹钟")
            .setMessage(systemAlarmCreationHelp())
            .setPositiveButton("去创建") { _, _ -> addSystemAlarm(alarm) }
            .setNegativeButton("稍后", null)
            .create()
        dialog.show()
        prefs.edit().putBoolean("system_alarm_suggestion_shown", true).apply()
    }

    private fun addSystemAlarm(alarm: AlarmScheduler.AlarmEntry) {
        val ctx = requireContext()
        val baseIntent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
            putExtra(AlarmClock.EXTRA_HOUR, alarm.hour)
            putExtra(AlarmClock.EXTRA_MINUTES, alarm.minute)
            putExtra(AlarmClock.EXTRA_DAYS, daysToSystemFormat(alarm.days))
            putExtra(AlarmClock.EXTRA_MESSAGE, "决定守护 备份闹钟")
            putExtra(AlarmClock.EXTRA_SKIP_UI, false)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        // [REQ-4] 把"选包 + 任务栈策略"抽离到 use case，Fragment 只负责
        // 触发 + 写 alarm_sync_pending。任务栈本身由 use case 决定。
        val useCase = AlarmSystemBackupUseCase(ctx.packageManager)
        val candidates = useCase.querySetAlarmCandidates(baseIntent)
        val selected = useCase.pickClockCandidate(candidates)

        if (selected == null) {
            Log.w(TAG, "[ALARM_SYNC] no handler for ACTION_SET_ALARM")
            Toast.makeText(ctx, "未找到可用的系统闹钟应用，请手动添加", Toast.LENGTH_LONG).show()
            return
        }

        val targetPkg = selected.activityInfo.packageName
        val targetIntent = Intent(baseIntent).setPackage(targetPkg)
        Log.i(
            TAG,
            "[ALARM_SYNC] target=$targetPkg alarmId=${alarm.id} " +
                "time=${alarm.hour}:${String.format("%02d", alarm.minute)}"
        )

        val plan = useCase.buildLaunchPlan(context = ctx, targetPackage = targetPkg, targetIntent = targetIntent)

        try {
            useCase.execute(ctx, plan)
            // 记录创建请求，保留既有偏好键以兼容旧版本。
            ctx.getSharedPreferences("alarm_sync_pending", android.content.Context.MODE_PRIVATE).edit()
                .putBoolean("pending", true)
                .putInt("expected_hour", alarm.hour)
                .putInt("expected_minute", alarm.minute)
                .putLong("initiated_at", System.currentTimeMillis())
                .apply()
            Log.i(TAG, "[ALARM_SYNC] startActivity ok, pending flag set")
            Toast.makeText(ctx, "已打开系统闹钟，请保存闹钟", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Log.e(TAG, "[ALARM_SYNC] startActivity failed target=$targetPkg", e)
            Toast.makeText(ctx, "创建失败，请手动添加: ${alarm.hour}:${String.format("%02d", alarm.minute)}", Toast.LENGTH_LONG).show()
        }
    }

    @Suppress("unused")
    private fun pickClockCandidate(candidates: List<ResolveInfo>): ResolveInfo? {
        // [REQ-4] 选包逻辑已迁出到 AlarmSystemBackupUseCase.pickClockCandidate，
        // 保留此方法仅为兼容可能存在的反射调用者；新代码请直接调 use case。
        if (candidates.isEmpty()) return null
        val preferred = candidates.firstOrNull {
            val pkg = it.activityInfo.packageName.lowercase()
            pkg.contains("clock") || pkg.contains("deskclock")
        }
        return preferred ?: candidates.firstOrNull()
    }


    private fun daysToSystemFormat(days: BooleanArray): IntArray {
        val systemDays = mutableListOf<Int>()
        // 将 days 数组转换为系统格式（周一到周日 = 2-8）
        val dayMapping = intArrayOf(2, 3, 4, 5, 6, 7, 1) // Mon-Sun to Android format
        days.forEachIndexed { index, enabled ->
            if (enabled && index < dayMapping.size) {
                systemDays.add(dayMapping[index])
            }
        }
        return systemDays.toIntArray()
    }

    private fun persistAndRefresh() {
        val sorted = alarms.sortedWith(compareBy({ it.hour }, { it.minute }))
        AlarmScheduler.saveAlarms(requireContext(), sorted)
        adapter?.updateAll(sorted)
    }
}
