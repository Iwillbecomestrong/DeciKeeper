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
package com.zhizhi.app.ui

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.graphics.Color
import android.graphics.Typeface
import android.media.RingtoneManager
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
import android.widget.ScrollView
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
import com.zhizhi.app.R
import com.zhizhi.app.manager.AlarmScheduler
import com.zhizhi.app.manager.AlarmSystemBackupUseCase




class AlarmFragment : Fragment() {

    companion object {
        private const val TAG = "AlarmFragment"
        private const val REQUEST_PICK_RINGTONE = 9001
    }

    private var recyclerView: RecyclerView? = null
    private var adapter: AlarmListAdapter? = null
    private var btnAdd: Button? = null
    private var btnTest: Button? = null
    private var pendingRingtoneUri: String? = null
    private var tvSnoozeSummary: TextView? = null
    private var tvKeepaliveSummary: TextView? = null
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

    private fun showAlarmKeepAliveGuideDialog() {
        val ctx = context ?: return
        val isVivo = isVivoOrIqoo()
        val title = "闹钟保活指引"
        val message = if (isVivo) {
            "为防止 Vivo / OriginOS 在夜间锁屏待机时冻结闹钟进程导致漏响或延迟，请务必完成以下两项配置：\n\n" +
            "1. 关闭「睡眠待机优化」\n" +
            "   路径：设置 → 电池 → 更多电池设置 → 关闭「睡眠待机优化 / 睡眠模式」\n\n" +
            "2. 允许「后台高耗电」\n" +
            "   路径：设置 → 电池 → 后台耗电管理 → 找到 知止 → 选择「允许后台耗电」"
        } else {
            "为防止系统在夜间锁屏或后台待机时冻结闹钟进程导致漏响，建议完成以下配置：\n\n" +
            "1. 忽略电池优化（允许后台无限制运行）\n" +
            "2. 允许应用自启动与常驻后台\n" +
            "3. 在多任务后台将本应用锁定，防止被系统误清理"
        }

        AlertDialog.Builder(ctx)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("前往设置") { _, _ ->
                openKeepAliveSettings(ctx)
            }
            .setNegativeButton("我知道了", null)
            .show()
    }

    private fun openKeepAliveSettings(ctx: Context) {
        if (isVivoOrIqoo()) {
            openVivoBatterySettings(ctx)
        } else {
            val opened = com.zhizhi.app.manager.SettingsNavigator.openBatteryOptimization(ctx)
            if (!opened) {
                try {
                    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}"))
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    ctx.startActivity(intent)
                } catch (_: Exception) {}
            }
            Toast.makeText(ctx, "请允许后台运行并忽略电池优化", Toast.LENGTH_LONG).show()
        }
    }

    private fun openVivoBatterySettings(ctx: Context) {
        val intents = mutableListOf<Intent>()
        // 1) Vivo 后台耗电管理
        intents.add(Intent().setComponent(ComponentName(
            "com.iqoo.powersaving",
            "com.iqoo.powersaving.activity.ExcessivePowerDescriptionActivity"
        )).apply { putExtra("package_name", ctx.packageName) })
        // 2) 电池使用概览
        intents.add(Intent().setComponent(ComponentName(
            "com.iqoo.powersaving",
            "com.iqoo.powersaving.fuelgauge.PowerUsageSummaryActivity"
        )))
        // 3) Vivo 高耗电管理
        intents.add(Intent().setComponent(ComponentName(
            "com.vivo.abe",
            "com.vivo.applicationbehaviorengine.ui.ExcessivePowerManagerActivity"
        )).apply { putExtra("package_name", ctx.packageName) })
        intents.add(Intent().setComponent(ComponentName(
            "com.iqoo.powersaving",
            "com.iqoo.powersaving.activity.ExcessivePowerManagerActivity"
        )).apply { putExtra("package_name", ctx.packageName) })
        intents.add(Intent().setComponent(ComponentName(
            "com.iqoo.powersaving",
            "com.iqoo.powersaving.BackgroundHighUsageActivity"
        )).apply { putExtra("package_name", ctx.packageName) })
        intents.add(Intent("com.vivo.abe.highpower.search.powermanageractivity").apply {
            putExtra("package_name", ctx.packageName)
        })
        // 4) 系统设置-电池页面
        intents.add(Intent().setComponent(ComponentName(
            "com.android.settings",
            "com.android.settings.Settings\$PowerUsageSummaryActivity"
        )))
        intents.add(Intent().setComponent(ComponentName(
            "com.android.settings",
            "com.android.settings.Settings\$BatterySaverSettingsActivity"
        )))
        intents.add(Intent(Intent.ACTION_POWER_USAGE_SUMMARY))
        intents.add(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        intents.add(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}")))

        var launched = false
        for (intent in intents) {
            try {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent)
                launched = true
                break
            } catch (_: Exception) {}
        }
        Toast.makeText(ctx, "请关闭「睡眠待机优化」并允许「后台高耗电」", Toast.LENGTH_LONG).show()
    }

    private fun maybeShowKeepAliveGuideOnFirstActiveAlarm() {
        val ctx = context ?: return
        val prefs = ctx.getSharedPreferences("iron_will_oem_guide", Context.MODE_PRIVATE)
        val shown = prefs.getBoolean("alarm_keepalive_guide_shown", false) ||
            prefs.getBoolean("vivo_sleep_standby_guide_shown", false)
        if (!shown) {
            prefs.edit().putBoolean("alarm_keepalive_guide_shown", true).apply()
            showAlarmKeepAliveGuideDialog()
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_alarm, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        recyclerView = view.findViewById(R.id.rv_alarm_list)
        btnAdd = view.findViewById(R.id.btn_add_alarm)
        btnTest = view.findViewById(R.id.btn_test_alarm)

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
                    val intent = android.content.Intent(ctx, AlarmActivity::class.java).apply {
                        putExtra(AlarmScheduler.EXTRA_ALARM_ID, activeAlarmId)
                    }
                    startActivity(intent)
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
            showEditDialog(null)
        }

        btnTest?.setOnClickListener {
            showTestDialog()
        }

        view.findViewById<View>(R.id.btn_emergency_whitelist)?.setOnClickListener {
            val intent = Intent(requireContext(), AppSelectionActivity::class.java).apply {
                putExtra(AppSelectionActivity.EXTRA_MODE, AppSelectionActivity.MODE_ALARM_EMERGENCY_WHITELIST)
            }
            startActivity(intent)
        }

        tvKeepaliveSummary = view.findViewById(R.id.tv_keepalive_summary)
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
            startActivity(Intent(requireContext(), com.zhizhi.app.ui.questionbank.CustomQuestionBankActivity::class.java))
        }
        view.findViewById<Button>(R.id.btn_bookmarked_words)?.visibility = View.GONE
        view.findViewById<Button>(R.id.btn_bookmarked_clozes)?.visibility = View.GONE

        refreshSettingsSummaries()
        reloadAlarms()
    }

    override fun onResume() {
        super.onResume()
        reloadAlarms()
        activeAlarmRefreshHandler.removeCallbacks(activeAlarmRefreshRunnable)
        activeAlarmRefreshHandler.post(activeAlarmRefreshRunnable)
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

        // 1. 如果存在已开启的闹钟，首次自动触发保活引导（无论手动添加还是备份导入）
        val hasActiveAlarm = alarms.any { it.enabled }
        if (hasActiveAlarm) {
            maybeShowKeepAliveGuideOnFirstActiveAlarm()
        }

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
                Toast.makeText(ctx, "请重新同步到系统闹钟", Toast.LENGTH_SHORT).show()
            }
            .show()
    }
    private fun showDefaultSettingsDialog() {
        showTestDialog()
    }

    private fun refreshSettingsSummaries() {
        val ctx = context ?: return
        // 统一入口不显示单项摘要，避免误解"只能设置再响"
        tvSnoozeSummary?.text = ""
        tvKeepaliveSummary?.text = "防睡眠冻结/后台保活"

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

        val allBanks = com.zhizhi.app.manager.questionbank.CustomQuestionBankManager.getBanks(context)

        // 第一项：选词（统一为 2 字，与 扫码/数学/诗词/单词 保持整齐一致）
        val clozeCb = CheckBox(context).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            text = "选词"
            textSize = 12f
            includeFontPadding = false
            setTextColor(ThemeManager.getColorAttr(context, R.attr.zzText))
            isChecked = isClozeSelected
        }

        val bankCheckBoxes = mutableListOf<Pair<String, CheckBox>>()
        val allItems = mutableListOf<View>()
        allItems.add(clozeCb)

        // 后续项：自定义题库（如考公、考研），紧随选词排布在同一流式网格中
        for (bank in allBanks) {
            val bankCb = CheckBox(context).apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                text = bank.title
                textSize = 12f
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                includeFontPadding = false
                setTextColor(ThemeManager.getColorAttr(context, R.attr.zzText))
                isChecked = selectedBankMap.containsKey(bank.id)
            }
            bankCheckBoxes.add(Pair(bank.id, bankCb))
            allItems.add(bankCb)
        }

        // 自适应列数：若题库名称均为短名称（<=3字），统一采用标准 4 列与上一行（扫码/数学/诗词/单词）对齐；若有较长名称则自适应为 2 列
        val maxTitleLen = (listOf("选词") + allBanks.map { it.title }).maxOfOrNull { it.length } ?: 2
        val numCols = if (maxTitleLen <= 3) 4 else 2

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
        val allBanks = com.zhizhi.app.manager.questionbank.CustomQuestionBankManager.getBanks(context)
        if (allBanks.isEmpty()) {
            container.visibility = View.GONE
            return { emptyMap() }
        }
        container.visibility = View.VISIBLE

        val countMap = mutableMapOf<String, EditText>()
        val chunks = allBanks.chunked(2)

        for (chunk in chunks) {
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
                        0,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        1f
                    )
                    text = "${bank.title} "
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

        val layoutStage2 = dialogView.findViewById<View>(R.id.layout_stage2)
        val cbS2Qr = dialogView.findViewById<android.widget.CheckBox>(R.id.cb_s2_qr)
        val cbS2Math = dialogView.findViewById<android.widget.CheckBox>(R.id.cb_s2_math)
        val cbS2Poem = dialogView.findViewById<android.widget.CheckBox>(R.id.cb_s2_poem)
        val cbS2Word = dialogView.findViewById<android.widget.CheckBox>(R.id.cb_s2_word)
        val cbS2Cloze = dialogView.findViewById<android.widget.CheckBox>(R.id.cb_s2_cloze)

        val innerLayout = (dialogView as? android.view.ViewGroup)?.let { root ->
            (root.getChildAt(0) as? ScrollView)?.getChildAt(0) as? LinearLayout
        }
        val layoutStage1Cooldown = dialogView.findViewById<View>(R.id.layout_stage1_cooldown)
        val etEditCooldown = dialogView.findViewById<EditText>(R.id.et_stage_cooldown) ?: EditText(requireContext())
        etEditCooldown.setText((existing?.stageCooldownSeconds ?: AlarmScheduler.getDefaultStageCooldownSeconds(requireContext())).toString())

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

        // 铃声模式（程序化添加）
        val editRingtoneContainer = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) }
        }
        editRingtoneContainer.addView(TextView(requireContext()).apply { text = "铃声与震动"; textSize = 14f; setTextColor(ThemeManager.getColorAttr(requireContext(), R.attr.zzText)) })
        val editRgRingMode = RadioGroup(requireContext()).apply { orientation = RadioGroup.HORIZONTAL }
        val editRbVibRing = RadioButton(requireContext()).apply { text = "震动+铃声"; id = View.generateViewId(); setTextColor(ThemeManager.getColorAttr(requireContext(), R.attr.zzText)) }
        val editRbVibOnly = RadioButton(requireContext()).apply { text = "仅震动"; id = View.generateViewId(); setTextColor(ThemeManager.getColorAttr(requireContext(), R.attr.zzText)) }
        val editRbRingOnly = RadioButton(requireContext()).apply { text = "仅铃声"; id = View.generateViewId(); setTextColor(ThemeManager.getColorAttr(requireContext(), R.attr.zzText)) }
        val editAlarmMode = existing?.alarmMode ?: AlarmScheduler.getDefaultAlarmMode(requireContext())
        when (editAlarmMode) {
            AlarmScheduler.AlarmMode.VIBRATE_AND_RING -> editRbVibRing.isChecked = true
            AlarmScheduler.AlarmMode.VIBRATE_ONLY -> editRbVibOnly.isChecked = true
            AlarmScheduler.AlarmMode.RING_ONLY -> editRbRingOnly.isChecked = true
        }
        editRgRingMode.addView(editRbVibRing); editRgRingMode.addView(editRbVibOnly); editRgRingMode.addView(editRbRingOnly)
        editRingtoneContainer.addView(editRgRingMode)

        // 模式（渐进/固定）
        val editRgVibMode = RadioGroup(requireContext()).apply { orientation = RadioGroup.HORIZONTAL }
        val editRbVibProg = RadioButton(requireContext()).apply { text = "渐进"; id = View.generateViewId(); setTextColor(ThemeManager.getColorAttr(requireContext(), R.attr.zzText)) }
        val editRbVibFixed = RadioButton(requireContext()).apply { text = "固定"; id = View.generateViewId(); setTextColor(ThemeManager.getColorAttr(requireContext(), R.attr.zzText)) }
        when (existing?.vibrationMode ?: AlarmScheduler.getDefaultVibrationMode(requireContext())) {
            AlarmScheduler.VibrationMode.FIXED -> editRbVibFixed.isChecked = true
            else -> editRbVibProg.isChecked = true
        }
        editRgVibMode.addView(editRbVibProg); editRgVibMode.addView(editRbVibFixed)
        editRingtoneContainer.addView(editRgVibMode)

        // 震动强度 — 固定模式单滑块（程序化）
        val editVibRow = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) }
        }
        editVibRow.addView(TextView(requireContext()).apply { text = "震动"; textSize = 13f; setTextColor(ThemeManager.getColorAttr(requireContext(), R.attr.zzText)); setPadding(0, 0, dp(8), 0) })
        val editSbVib = SeekBar(requireContext()).apply {
            max = 9; progress = (existing?.vibrationIntensity ?: AlarmScheduler.getDefaultVibrationIntensity(requireContext())) - 1
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val editTvVibLabel = TextView(requireContext()).apply {
            text = "${editSbVib.progress + 1} / 10"; textSize = 14f
            setTextColor(ThemeManager.getColorAttr(requireContext(), R.attr.zzText)); setPadding(dp(8), 0, 0, 0)
        }
        editSbVib.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) { editTvVibLabel.text = "${p + 1} / 10" }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
        editVibRow.addView(editSbVib); editVibRow.addView(editTvVibLabel)
        editRingtoneContainer.addView(editVibRow)

        // 渐进模式提示文字（起始强度固定为 1）
        val editProgHintRow = TextView(requireContext()).apply {
            text = "起始强度: 1（固定）"
            textSize = 12f; setTextColor(ThemeManager.getColorAttr(requireContext(), R.attr.zzMuted))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) }
        }
        editRingtoneContainer.addView(editProgHintRow)

        // 震动最大强度（渐进模式可见）
        val editProgVibRow = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) }
        }
        editProgVibRow.addView(TextView(requireContext()).apply { text = "震动最大"; textSize = 13f; setTextColor(ThemeManager.getColorAttr(requireContext(), R.attr.zzText)); setPadding(0, 0, dp(8), 0) })
        val editSbProgVibMax = SeekBar(requireContext()).apply {
            max = 9; progress = (existing?.progressiveMaxIntensity ?: AlarmScheduler.getDefaultProgressiveMaxIntensity(requireContext())) - 1
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val editTvProgVibMaxLabel = TextView(requireContext()).apply {
            text = "${editSbProgVibMax.progress + 1} / 10"; textSize = 14f
            setTextColor(ThemeManager.getColorAttr(requireContext(), R.attr.zzText)); setPadding(dp(8), 0, 0, 0)
        }
        editSbProgVibMax.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) { editTvProgVibMaxLabel.text = "${p + 1} / 10" }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
        editProgVibRow.addView(editSbProgVibMax); editProgVibRow.addView(editTvProgVibMaxLabel)
        editRingtoneContainer.addView(editProgVibRow)

        // 5分钟后自动升级（渐进模式可见）
        val editAutoEscalateRow = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) }
        }
        val editCbAutoEscalate = CheckBox(requireContext()).apply {
            text = "5分钟后自动升级到最大"
            textSize = 13f
            setTextColor(ThemeManager.getColorAttr(requireContext(), R.attr.zzText))
            isChecked = existing?.autoEscalate ?: false
        }
        editAutoEscalateRow.addView(editCbAutoEscalate)
        editRingtoneContainer.addView(editAutoEscalateRow)

        // 声音最大强度（渐进模式可见）
        val editProgVolRow = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) }
        }
        editProgVolRow.addView(TextView(requireContext()).apply { text = "声音最大"; textSize = 13f; setTextColor(ThemeManager.getColorAttr(requireContext(), R.attr.zzText)); setPadding(0, 0, dp(8), 0) })
        val editSbProgVolMax = SeekBar(requireContext()).apply {
            max = 9; progress = (existing?.progressiveMaxRingVolume ?: AlarmScheduler.getDefaultProgressiveMaxRingVolume(requireContext())) - 1
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val editTvProgVolMaxLabel = TextView(requireContext()).apply {
            text = "${editSbProgVolMax.progress + 1} / 10"; textSize = 14f
            setTextColor(ThemeManager.getColorAttr(requireContext(), R.attr.zzText)); setPadding(dp(8), 0, 0, 0)
        }
        editSbProgVolMax.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) { editTvProgVolMaxLabel.text = "${p + 1} / 10" }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
        editProgVolRow.addView(editSbProgVolMax); editProgVolRow.addView(editTvProgVolMaxLabel)
        editRingtoneContainer.addView(editProgVolRow)

        // 声音强度（固定模式）
        val editVolContainer = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) }
        }
        editVolContainer.addView(TextView(requireContext()).apply { text = "声音"; textSize = 13f; setTextColor(ThemeManager.getColorAttr(requireContext(), R.attr.zzText)); setPadding(0, 0, dp(8), 0) })
        val editSbRingVol = SeekBar(requireContext()).apply {
            max = 9; progress = (existing?.ringVolume ?: AlarmScheduler.getDefaultRingVolume(requireContext())) - 1
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val editTvRingVolLabel = TextView(requireContext()).apply {
            text = "${editSbRingVol.progress + 1} / 10"; textSize = 14f
            setTextColor(ThemeManager.getColorAttr(requireContext(), R.attr.zzText)); setPadding(dp(8), 0, 0, 0)
        }
        editSbRingVol.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) { editTvRingVolLabel.text = "${p + 1} / 10" }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
        editVolContainer.addView(editSbRingVol); editVolContainer.addView(editTvRingVolLabel)
        editRingtoneContainer.addView(editVolContainer)
        innerLayout?.addView(editRingtoneContainer)

        fun updateEditRingtoneVisibility() {
            val modeId = editRgRingMode.checkedRadioButtonId
            val isVibOnly = modeId == editRbVibOnly.id
            val isRingOnly = modeId == editRbRingOnly.id
            val isProgressive = editRgVibMode.checkedRadioButtonId == editRbVibProg.id
            // 渐进：仅震动→只显示震动最大，仅铃声→只显示声音最大
            // 固定：仅震动→只显示震动，仅铃声→只显示声音
            editProgHintRow.visibility = if (!isProgressive) View.GONE else View.VISIBLE
            editProgVibRow.visibility = if (!isProgressive || isRingOnly) View.GONE else View.VISIBLE
            editAutoEscalateRow.visibility = if (isRingOnly) View.GONE else View.VISIBLE
            editProgVolRow.visibility = if (!isProgressive || isVibOnly) View.GONE else View.VISIBLE
            editVibRow.visibility = if (isProgressive || isRingOnly) View.GONE else View.VISIBLE
            editVolContainer.visibility = if (isProgressive || isVibOnly) View.GONE else View.VISIBLE
        }
        editRgRingMode.setOnCheckedChangeListener { _, _ -> updateEditRingtoneVisibility() }
        updateEditRingtoneVisibility()
        editRgVibMode.setOnCheckedChangeListener { _, _ -> updateEditRingtoneVisibility() }

        fun updateStage2Visibility() {
            val isTwoStage = rgUnlockMode.checkedRadioButtonId == R.id.rb_unlock_two_stage
            layoutStage2.visibility = if (isTwoStage) View.VISIBLE else View.GONE
            tvStage1Label.text = if (isTwoStage) "第一关解锁方式（通过其一即可）" else "解锁方式配置（可并联多选）"
            layoutStage1Cooldown?.visibility = if (isTwoStage) View.VISIBLE else View.GONE
        }
        rgUnlockMode.setOnCheckedChangeListener { _, _ -> updateStage2Visibility(); updateEditRingtoneVisibility() }

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
            .setTitle(if (existing == null) "新增闹钟" else "编辑闹钟")
            .setView(dialogView)
            .setPositiveButton("保存", null)
            .setNegativeButton("取消", null)
            .create()

        editDialog.show()

        editDialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val hour = selectedHour
            val minute = selectedMinute
            val days = BooleanArray(7) { index -> checkboxes[index].isChecked }
            val normalizedDays = if (days.none { it }) BooleanArray(7) { true } else days

            val isTwoStage = rgUnlockMode.checkedRadioButtonId == R.id.rb_unlock_two_stage

            val hasCameraNow = ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
            val bankCounts = getCustomBankCounts()
            for ((bId, count) in bankCounts) {
                AlarmScheduler.setBankDefaultCount(requireContext(), bId, count)
            }
            val defaultCustomCount = AlarmScheduler.getDefaultCustomCount(requireContext())
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
                    Toast.makeText(requireContext(), "两阶段模式下，第一关必须至少选择一种挑战！", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                if (s2Types.isEmpty()) {
                    Toast.makeText(requireContext(), "两阶段模式下，第二关必须至少选择一种挑战！", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
            } else {
                if (s1Types.isEmpty()) {
                    Toast.makeText(requireContext(), "请至少选择一种挑战！", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
            }

            val useQr = "qr" in s1Types
            val useMath = "math" in s1Types
            val usePoem = "poem" in s1Types
            val useWord = "word" in s1Types
            val useCloze = "cloze" in s1Types

            val mathCount = etMathCount.text.toString().toIntOrNull() ?: AlarmScheduler.getDefaultMathCount(requireContext())
            val poemCount = etPoemCount.text.toString().toIntOrNull() ?: AlarmScheduler.getDefaultPoemCount(requireContext())
            val wordCount = etWordCount.text.toString().toIntOrNull() ?: AlarmScheduler.getDefaultWordCount(requireContext())
            val clozeCount = etClozeCount?.text.toString().toIntOrNull() ?: AlarmScheduler.getDefaultClozeCount(requireContext())
            val customSum = s1CustomBanks.values.sum() + s2CustomBanks.values.sum()
            val customCount = if (customSum > 0) (s1CustomBanks.values + s2CustomBanks.values).maxOrNull() ?: defaultCustomCount else defaultCustomCount
            val isProgressive = editRgVibMode.checkedRadioButtonId != editRbVibFixed.id
            val vibrationIntensity = if (isProgressive) 1 else editSbVib.progress + 1
            val vibrationMode = if (editRgVibMode.checkedRadioButtonId == editRbVibFixed.id)
                AlarmScheduler.VibrationMode.FIXED else AlarmScheduler.VibrationMode.PROGRESSIVE
            val progMax = if (isProgressive) editSbProgVibMax.progress + 1 else 10
            val progVolMax = if (isProgressive) editSbProgVolMax.progress + 1 else 10
            val autoEscalate = editCbAutoEscalate.isChecked
            val ringVolume = editSbRingVol.progress + 1
            val unlockMode = if (isTwoStage) AlarmScheduler.UnlockMode.TWO_STAGE else AlarmScheduler.UnlockMode.SINGLE
            val stageCooldownSec = etEditCooldown.text.toString().toIntOrNull()?.coerceIn(0, 3600) ?: 0
            val editAlarmModeVal = when (editRgRingMode.checkedRadioButtonId) {
                editRbRingOnly.id -> AlarmScheduler.AlarmMode.RING_ONLY
                editRbVibOnly.id -> AlarmScheduler.AlarmMode.VIBRATE_ONLY
                else -> AlarmScheduler.AlarmMode.VIBRATE_AND_RING
            }

            val entry = if (existing == null) {
                AlarmScheduler.createAlarm(
                    requireContext(), hour, minute, true, normalizedDays,
                    useQr, useMath, usePoem, useWord,
                    mathCount, poemCount, wordCount, clozeCount, customCount,
                    vibrationIntensity, vibrationMode, unlockMode,
                    s1Types, s2Types,
                    stageCooldownSeconds = stageCooldownSec,
                    ringtoneUri = AlarmScheduler.getDefaultRingtoneUri(requireContext()),
                    alarmMode = editAlarmModeVal,
                    ringVolume = ringVolume,
                    progressiveMaxIntensity = progMax,
                    progressiveMaxRingVolume = progVolMax,
                    autoEscalate = autoEscalate,
                    stage1CustomBanks = s1CustomBanks,
                    stage2CustomBanks = s2CustomBanks
                )
            } else {
                existing.copy(
                    hour = hour, minute = minute, days = normalizedDays,
                    useQr = useQr, useMath = useMath, usePoem = usePoem, useWord = useWord,
                    mathCount = mathCount, poemCount = poemCount, wordCount = wordCount,
                    clozeCount = clozeCount, customCount = customCount,
                    vibrationIntensity = vibrationIntensity, vibrationMode = vibrationMode,
                    unlockMode = unlockMode,
                    stage1Types = s1Types, stage2Types = s2Types,
                    stageCooldownSeconds = stageCooldownSec,
                    alarmMode = editAlarmModeVal,
                    ringVolume = ringVolume,
                    progressiveMaxIntensity = progMax,
                    progressiveMaxRingVolume = progVolMax,
                    autoEscalate = autoEscalate,
                    stage1CustomBanks = s1CustomBanks,
                    stage2CustomBanks = s2CustomBanks
                )
            }
            upsertAlarm(entry)
            editDialog.dismiss()
        }
    }

    private fun showTestDialog() {
        val ctx = requireContext()
        val dialogView = layoutInflater.inflate(R.layout.dialog_test_alarm, null)

        val rgUnlockMode = dialogView.findViewById<android.widget.RadioGroup>(R.id.rg_unlock_mode)
        val rbUnlockSingle = dialogView.findViewById<RadioButton>(R.id.rb_unlock_single)
        val rbUnlockTwoStage = dialogView.findViewById<RadioButton>(R.id.rb_unlock_two_stage)
        val curMode = AlarmScheduler.getDefaultUnlockMode(ctx)
        if (curMode == AlarmScheduler.UnlockMode.TWO_STAGE) rbUnlockTwoStage.isChecked = true else rbUnlockSingle.isChecked = true

        val layoutStage1 = dialogView.findViewById<View>(R.id.layout_stage1)
        val layoutStage2 = dialogView.findViewById<View>(R.id.layout_stage2)
        val tvStage1Label = dialogView.findViewById<TextView>(R.id.tv_stage1_label)

        // Stage 1
        val cbS1Qr = dialogView.findViewById<android.widget.CheckBox>(R.id.cb_s1_qr)
        val cbS1Math = dialogView.findViewById<android.widget.CheckBox>(R.id.cb_s1_math)
        val cbS1Poem = dialogView.findViewById<android.widget.CheckBox>(R.id.cb_s1_poem)
        val cbS1Word = dialogView.findViewById<android.widget.CheckBox>(R.id.cb_s1_word)
        val cbS1Cloze = dialogView.findViewById<android.widget.CheckBox>(R.id.cb_s1_cloze)
        val curS1 = AlarmScheduler.getDefaultStage1Types(ctx)
        val hasCamera = ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        cbS1Qr.isChecked = ("qr" in curS1) && hasCamera
        bindQrCheckboxPermission(cbS1Qr)
        cbS1Math.isChecked = "math" in curS1
        cbS1Poem.isChecked = "poem" in curS1
        cbS1Word.isChecked = "word" in curS1
        cbS1Cloze?.isChecked = "cloze" in curS1

        // Stage 2
        val cbS2Qr = dialogView.findViewById<android.widget.CheckBox>(R.id.cb_s2_qr)
        val cbS2Math = dialogView.findViewById<android.widget.CheckBox>(R.id.cb_s2_math)
        val cbS2Poem = dialogView.findViewById<android.widget.CheckBox>(R.id.cb_s2_poem)
        val cbS2Word = dialogView.findViewById<android.widget.CheckBox>(R.id.cb_s2_word)
        val cbS2Cloze = dialogView.findViewById<android.widget.CheckBox>(R.id.cb_s2_cloze)
        val curS2 = AlarmScheduler.getDefaultStage2Types(ctx)
        cbS2Qr.isChecked = ("qr" in curS2) && hasCamera
        bindQrCheckboxPermission(cbS2Qr)
        cbS2Math.isChecked = "math" in curS2
        cbS2Poem.isChecked = "poem" in curS2
        cbS2Word.isChecked = "word" in curS2
        cbS2Cloze?.isChecked = "cloze" in curS2

        val etMathCount = dialogView.findViewById<EditText>(R.id.et_math_count)
        val etPoemCount = dialogView.findViewById<EditText>(R.id.et_poem_count)
        val etWordCount = dialogView.findViewById<EditText>(R.id.et_word_count)
        val etClozeCount = dialogView.findViewById<EditText>(R.id.et_cloze_count)
        val etCustomCount = dialogView.findViewById<EditText>(R.id.et_custom_count)
        etMathCount.setText(AlarmScheduler.getDefaultMathCount(ctx).toString())
        etPoemCount.setText(AlarmScheduler.getDefaultPoemCount(ctx).toString())
        etWordCount.setText(AlarmScheduler.getDefaultWordCount(ctx).toString())
        etClozeCount.setText(AlarmScheduler.getDefaultClozeCount(ctx).toString())
        etCustomCount.setText(AlarmScheduler.getDefaultCustomCount(ctx).toString())

        val etSnoozeMinutes = dialogView.findViewById<EditText>(R.id.et_snooze_minutes)
        val etSnoozeMaxCount = dialogView.findViewById<EditText>(R.id.et_snooze_max_count)
        etSnoozeMinutes?.setText(AlarmScheduler.getSnoozeMinutes(ctx).toString())
        etSnoozeMaxCount?.setText(AlarmScheduler.getMaxSnoozeCount(ctx).toString())

        val testInnerLayout = (dialogView as? android.view.ViewGroup)?.let { root ->
            (root.getChildAt(0) as? ScrollView)?.getChildAt(0) as? LinearLayout
        }
        val layoutTestStage1Cooldown = dialogView.findViewById<View>(R.id.layout_stage1_cooldown)
        val etTestCooldown = dialogView.findViewById<EditText>(R.id.et_stage_cooldown) ?: EditText(ctx)
        etTestCooldown.setText(AlarmScheduler.getDefaultStageCooldownSeconds(ctx).toString())

        val layoutTestS1CustomBanks = dialogView.findViewById<LinearLayout>(R.id.layout_s1_custom_banks)
        val layoutTestS2CustomBanks = dialogView.findViewById<LinearLayout>(R.id.layout_s2_custom_banks)
        val defTestS1Banks = AlarmScheduler.getDefaultStage1CustomBanks(ctx)
        val defTestS2Banks = AlarmScheduler.getDefaultStage2CustomBanks(ctx)
        val testS1Result = setupUnifiedBankSelection(ctx, layoutTestS1CustomBanks, "cloze" in curS1, defTestS1Banks)
        val testS2Result = setupUnifiedBankSelection(ctx, layoutTestS2CustomBanks, "cloze" in curS2, defTestS2Banks)

        val layoutTestCustomBankCounts = dialogView.findViewById<LinearLayout>(R.id.layout_custom_bank_counts)
        val testSavedCounts = defTestS1Banks + defTestS2Banks
        val getTestCustomBankCounts = setupCustomBankCounts(
            ctx,
            layoutTestCustomBankCounts,
            testSavedCounts,
            AlarmScheduler.getDefaultCustomCount(ctx)
        )

        // 铃声与震动
        val testRingtoneContainer = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) }
        }
        testRingtoneContainer.addView(TextView(ctx).apply { text = "铃声与震动"; textSize = 14f; setTypeface(typeface, Typeface.BOLD); setTextColor(ThemeManager.getColorAttr(ctx, R.attr.zzText)) })
        val testRgRingMode = RadioGroup(ctx).apply { orientation = RadioGroup.HORIZONTAL }
        val testRbVibRing = RadioButton(ctx).apply { text = "震动+铃声"; id = View.generateViewId(); setTextColor(ThemeManager.getColorAttr(ctx, R.attr.zzText)) }
        val testRbVibOnly = RadioButton(ctx).apply { text = "仅震动"; id = View.generateViewId(); setTextColor(ThemeManager.getColorAttr(ctx, R.attr.zzText)) }
        val testRbRingOnly = RadioButton(ctx).apply { text = "仅铃声"; id = View.generateViewId(); setTextColor(ThemeManager.getColorAttr(ctx, R.attr.zzText)) }
        when (AlarmScheduler.getDefaultAlarmMode(ctx)) {
            AlarmScheduler.AlarmMode.VIBRATE_AND_RING -> testRbVibRing.isChecked = true
            AlarmScheduler.AlarmMode.VIBRATE_ONLY -> testRbVibOnly.isChecked = true
            AlarmScheduler.AlarmMode.RING_ONLY -> testRbRingOnly.isChecked = true
        }
        testRgRingMode.addView(testRbVibRing); testRgRingMode.addView(testRbVibOnly); testRgRingMode.addView(testRbRingOnly)
        testRingtoneContainer.addView(testRgRingMode)

        val btnPickRingtone = Button(ctx).apply {
            var curUri = AlarmScheduler.getDefaultRingtoneUri(ctx)
            text = if (curUri != null) "已选自定义铃声" else "选择铃声（默认系统闹钟）"
            textSize = 12f
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) }
            setOnClickListener {
                val intent = Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
                    putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_ALARM)
                    putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
                    putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
                    curUri?.let { putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, Uri.parse(it)) }
                }
                startActivityForResult(intent, REQUEST_PICK_RINGTONE)
            }
        }
        testRingtoneContainer.addView(btnPickRingtone)

        // 模式（渐进/固定）
        val testRgVibMode = RadioGroup(ctx).apply { orientation = RadioGroup.HORIZONTAL }
        val testRbVibProg = RadioButton(ctx).apply { text = "渐进"; id = View.generateViewId(); setTextColor(ThemeManager.getColorAttr(ctx, R.attr.zzText)) }
        val testRbVibFixed = RadioButton(ctx).apply { text = "固定"; id = View.generateViewId(); setTextColor(ThemeManager.getColorAttr(ctx, R.attr.zzText)) }
        if (AlarmScheduler.getDefaultVibrationMode(ctx) == AlarmScheduler.VibrationMode.FIXED) {
            testRbVibFixed.isChecked = true
        } else {
            testRbVibProg.isChecked = true
        }
        testRgVibMode.addView(testRbVibProg); testRgVibMode.addView(testRbVibFixed)
        testRingtoneContainer.addView(testRgVibMode)

        // 震动强度 — 固定模式单滑块
        val testVibRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) }
        }
        testVibRow.addView(TextView(ctx).apply { text = "震动"; textSize = 13f; setTextColor(ThemeManager.getColorAttr(ctx, R.attr.zzText)); setPadding(0, 0, dp(8), 0) })
        val testSbVib = SeekBar(ctx).apply {
            max = 9; progress = (AlarmScheduler.getDefaultVibrationIntensity(ctx) - 1).coerceIn(0, 9)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val testTvVibLabel = TextView(ctx).apply {
            text = "${testSbVib.progress + 1} / 10"; textSize = 14f
            setTextColor(ThemeManager.getColorAttr(ctx, R.attr.zzText)); setPadding(dp(8), 0, 0, 0)
        }
        testSbVib.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) { testTvVibLabel.text = "${p + 1} / 10" }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
        testVibRow.addView(testSbVib); testVibRow.addView(testTvVibLabel)
        testRingtoneContainer.addView(testVibRow)

        // 渐进模式提示文字（起始强度固定为 1）
        val testProgHintRow = TextView(ctx).apply {
            text = "起始强度: 1（固定）"
            textSize = 12f; setTextColor(ThemeManager.getColorAttr(ctx, R.attr.zzMuted))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) }
        }
        testRingtoneContainer.addView(testProgHintRow)

        // 震动最大强度（渐进模式可见）
        val testProgVibRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) }
        }
        testProgVibRow.addView(TextView(ctx).apply { text = "震动最大"; textSize = 13f; setTextColor(ThemeManager.getColorAttr(ctx, R.attr.zzText)); setPadding(0, 0, dp(8), 0) })
        val testSbProgVibMax = SeekBar(ctx).apply {
            max = 9; progress = (AlarmScheduler.getDefaultProgressiveMaxIntensity(ctx) - 1).coerceIn(0, 9)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val testTvProgVibMaxLabel = TextView(ctx).apply {
            text = "${testSbProgVibMax.progress + 1} / 10"; textSize = 14f
            setTextColor(ThemeManager.getColorAttr(ctx, R.attr.zzText)); setPadding(dp(8), 0, 0, 0)
        }
        testSbProgVibMax.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) { testTvProgVibMaxLabel.text = "${p + 1} / 10" }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
        testProgVibRow.addView(testSbProgVibMax); testProgVibRow.addView(testTvProgVibMaxLabel)
        testRingtoneContainer.addView(testProgVibRow)

        // 声音最大强度（渐进模式可见）
        val testProgVolRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) }
        }
        testProgVolRow.addView(TextView(ctx).apply { text = "声音最大"; textSize = 13f; setTextColor(ThemeManager.getColorAttr(ctx, R.attr.zzText)); setPadding(0, 0, dp(8), 0) })
        val testSbProgVolMax = SeekBar(ctx).apply {
            max = 9; progress = (AlarmScheduler.getDefaultProgressiveMaxRingVolume(ctx) - 1).coerceIn(0, 9)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val testTvProgVolMaxLabel = TextView(ctx).apply {
            text = "${testSbProgVolMax.progress + 1} / 10"; textSize = 14f
            setTextColor(ThemeManager.getColorAttr(ctx, R.attr.zzText)); setPadding(dp(8), 0, 0, 0)
        }
        testSbProgVolMax.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) { testTvProgVolMaxLabel.text = "${p + 1} / 10" }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
        testProgVolRow.addView(testSbProgVolMax); testProgVolRow.addView(testTvProgVolMaxLabel)
        testRingtoneContainer.addView(testProgVolRow)

        // 声音强度（固定模式可见）
        val testVolRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) }
        }
        testVolRow.addView(TextView(ctx).apply { text = "声音"; textSize = 13f; setTextColor(ThemeManager.getColorAttr(ctx, R.attr.zzText)); setPadding(0, 0, dp(8), 0) })
        val testSbRingVol = SeekBar(ctx).apply {
            max = 9; progress = (AlarmScheduler.getDefaultRingVolume(ctx) - 1).coerceIn(0, 9)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val testTvRingVolLabel = TextView(ctx).apply {
            text = "${testSbRingVol.progress + 1} / 10"; textSize = 14f
            setTextColor(ThemeManager.getColorAttr(ctx, R.attr.zzText)); setPadding(dp(8), 0, 0, 0)
        }
        testSbRingVol.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) { testTvRingVolLabel.text = "${p + 1} / 10" }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
        testVolRow.addView(testSbRingVol); testVolRow.addView(testTvRingVolLabel)
        testRingtoneContainer.addView(testVolRow)
        testInnerLayout?.addView(testRingtoneContainer)

        fun updateTestRingtoneVisibility() {
            val modeId = testRgRingMode.checkedRadioButtonId
            val isVibOnly = modeId == testRbVibOnly.id
            val isRingOnly = modeId == testRbRingOnly.id
            val isProgressive = testRgVibMode.checkedRadioButtonId == testRbVibProg.id
            btnPickRingtone.visibility = if (isVibOnly) View.GONE else View.VISIBLE
            testProgHintRow.visibility = if (!isProgressive) View.GONE else View.VISIBLE
            testProgVibRow.visibility = if (!isProgressive || isRingOnly) View.GONE else View.VISIBLE
            testProgVolRow.visibility = if (!isProgressive || isVibOnly) View.GONE else View.VISIBLE
            testVibRow.visibility = if (isProgressive || isRingOnly) View.GONE else View.VISIBLE
            testVolRow.visibility = if (isProgressive || isVibOnly) View.GONE else View.VISIBLE
        }
        testRgRingMode.setOnCheckedChangeListener { _, _ -> updateTestRingtoneVisibility() }
        testRgVibMode.setOnCheckedChangeListener { _, _ -> updateTestRingtoneVisibility() }
        updateTestRingtoneVisibility()

        fun updateStage2Visibility() {
            val isTwoStage = rgUnlockMode.checkedRadioButtonId == R.id.rb_unlock_two_stage
            layoutStage2.visibility = if (isTwoStage) View.VISIBLE else View.GONE
            tvStage1Label.text = if (isTwoStage) "第一关解锁方式（通过其一即可）" else "解锁方式配置（可并联多选）"
            layoutTestStage1Cooldown?.visibility = if (isTwoStage) View.VISIBLE else View.GONE
        }
        rgUnlockMode.setOnCheckedChangeListener { _, _ -> updateStage2Visibility(); updateTestRingtoneVisibility() }
        updateStage2Visibility()

        val testDialog = AlertDialog.Builder(ctx)
            .setTitle("默认闹钟配置与测试")
            .setView(dialogView)
            .setPositiveButton("开始测试", null)
            .setNeutralButton("保存为默认设置", null)
            .setNegativeButton("退出", null)
            .create()

        testDialog.show()

        // 点击「保存为默认设置」
        testDialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
            val snoozeMin = etSnoozeMinutes?.text.toString().toIntOrNull() ?: 10
            if (snoozeMin > 0) AlarmScheduler.setSnoozeMinutes(ctx, snoozeMin)
            val snoozeMax = etSnoozeMaxCount?.text.toString().toIntOrNull() ?: 2
            if (snoozeMax > 0) AlarmScheduler.setMaxSnoozeCount(ctx, snoozeMax)

            val defVibMode = if (testRgVibMode.checkedRadioButtonId == testRbVibFixed.id)
                AlarmScheduler.VibrationMode.FIXED else AlarmScheduler.VibrationMode.PROGRESSIVE
            if (defVibMode == AlarmScheduler.VibrationMode.PROGRESSIVE) {
                AlarmScheduler.setDefaultProgressiveMaxIntensity(ctx, testSbProgVibMax.progress + 1)
                AlarmScheduler.setDefaultProgressiveMaxRingVolume(ctx, testSbProgVolMax.progress + 1)
            } else {
                AlarmScheduler.setDefaultVibrationIntensity(ctx, testSbVib.progress + 1)
            }

            val math = etMathCount.text.toString().toIntOrNull()
            val poem = etPoemCount.text.toString().toIntOrNull()
            val word = etWordCount.text.toString().toIntOrNull()
            val cloze = etClozeCount.text.toString().toIntOrNull()
            val bankCounts = getTestCustomBankCounts()
            for ((bId, count) in bankCounts) {
                AlarmScheduler.setBankDefaultCount(ctx, bId, count)
            }
            val defCustomCount = (bankCounts.values).maxOrNull() ?: AlarmScheduler.getDefaultCustomCount(ctx)

            if (math != null && math > 0) AlarmScheduler.setDefaultMathCount(ctx, math)
            if (poem != null && poem > 0) AlarmScheduler.setDefaultPoemCount(ctx, poem)
            if (word != null && word > 0) AlarmScheduler.setDefaultWordCount(ctx, word)
            if (cloze != null && cloze > 0) AlarmScheduler.setDefaultClozeCount(ctx, cloze)
            AlarmScheduler.setDefaultCustomCount(ctx, defCustomCount)

            val isTwoStage = rgUnlockMode.checkedRadioButtonId == R.id.rb_unlock_two_stage
            val mode = if (isTwoStage) AlarmScheduler.UnlockMode.TWO_STAGE else AlarmScheduler.UnlockMode.SINGLE
            val hasCameraNow = ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
            val testS1BankIds = testS1Result.getSelectedBankIds()
            val testS2BankIds = if (isTwoStage) testS2Result.getSelectedBankIds() else testS1BankIds
            val testS1Banks = testS1BankIds.associateWith { bankCounts[it] ?: defCustomCount }
            val testS2Banks = testS2BankIds.associateWith { bankCounts[it] ?: defCustomCount }

            val s1Types = mutableSetOf<String>().apply {
                if (cbS1Qr.isChecked && hasCameraNow) add("qr")
                if (cbS1Math.isChecked) add("math")
                if (cbS1Poem.isChecked) add("poem")
                if (cbS1Word.isChecked) add("word")
                if (testS1Result.isClozeChecked()) add("cloze")
                if (testS1Banks.isNotEmpty()) add("custom")
            }
            val s2Types = mutableSetOf<String>().apply {
                if (cbS2Qr.isChecked && hasCameraNow) add("qr")
                if (cbS2Math.isChecked) add("math")
                if (cbS2Poem.isChecked) add("poem")
                if (cbS2Word.isChecked) add("word")
                if (testS2Result.isClozeChecked()) add("cloze")
                if (testS2Banks.isNotEmpty()) add("custom")
            }

            val effectiveS1 = if (s1Types.isEmpty()) setOf("math") else s1Types
            val effectiveS2 = if (mode == AlarmScheduler.UnlockMode.TWO_STAGE && s2Types.isEmpty()) setOf("math", "poem", "word", "cloze") else s2Types
            AlarmScheduler.setDefaultUnlockMode(ctx, mode)
            AlarmScheduler.setDefaultStage1Types(ctx, effectiveS1)
            AlarmScheduler.setDefaultStage2Types(ctx, effectiveS2)
            AlarmScheduler.setDefaultStage1CustomBanks(ctx, testS1Banks)
            AlarmScheduler.setDefaultStage2CustomBanks(ctx, testS2Banks)

            val cooldown = etTestCooldown.text.toString().toIntOrNull()?.coerceIn(0, 3600) ?: 0
            AlarmScheduler.setDefaultStageCooldownSeconds(ctx, cooldown)

            val ringMode = when (testRgRingMode.checkedRadioButtonId) {
                testRbRingOnly.id -> AlarmScheduler.AlarmMode.RING_ONLY
                testRbVibOnly.id -> AlarmScheduler.AlarmMode.VIBRATE_ONLY
                else -> AlarmScheduler.AlarmMode.VIBRATE_AND_RING
            }
            AlarmScheduler.setDefaultAlarmMode(ctx, ringMode)
            if (ringMode != AlarmScheduler.AlarmMode.VIBRATE_ONLY) {
                pendingRingtoneUri?.let { AlarmScheduler.setDefaultRingtoneUri(ctx, it) }
            }
            AlarmScheduler.setDefaultRingVolume(ctx, testSbRingVol.progress + 1)
            AlarmScheduler.setDefaultVibrationMode(ctx, defVibMode)

            refreshSettingsSummaries()
            Toast.makeText(ctx, "默认设置已保存", Toast.LENGTH_SHORT).show()
            testDialog.dismiss()
        }

        // 点击「开始测试」
        testDialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val isTwoStage = rgUnlockMode.checkedRadioButtonId == R.id.rb_unlock_two_stage

            val hasCameraNow = ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
            val bankCounts = getTestCustomBankCounts()
            for ((bId, count) in bankCounts) {
                AlarmScheduler.setBankDefaultCount(ctx, bId, count)
            }
            val testCustomPerBank = (bankCounts.values).maxOrNull() ?: 3
            val testS1BankIds = testS1Result.getSelectedBankIds()
            val testS2BankIds = if (isTwoStage) testS2Result.getSelectedBankIds() else testS1BankIds
            val testS1Banks = testS1BankIds.associateWith { bankCounts[it] ?: testCustomPerBank }
            val testS2Banks = testS2BankIds.associateWith { bankCounts[it] ?: testCustomPerBank }

            val s1Types = mutableSetOf<String>()
            if (cbS1Qr.isChecked && hasCameraNow) s1Types.add("qr")
            if (cbS1Math.isChecked) s1Types.add("math")
            if (cbS1Poem.isChecked) s1Types.add("poem")
            if (cbS1Word.isChecked) s1Types.add("word")
            if (testS1Result.isClozeChecked()) s1Types.add("cloze")
            if (testS1Banks.isNotEmpty()) s1Types.add("custom")

            val s2Types = mutableSetOf<String>()
            if (isTwoStage) {
                if (cbS2Qr.isChecked && hasCameraNow) s2Types.add("qr")
                if (cbS2Math.isChecked) s2Types.add("math")
                if (cbS2Poem.isChecked) s2Types.add("poem")
                if (cbS2Word.isChecked) s2Types.add("word")
                if (testS2Result.isClozeChecked()) s2Types.add("cloze")
                if (testS2Banks.isNotEmpty()) s2Types.add("custom")
            } else {
                s2Types.addAll(s1Types)
            }

            // 严禁任一关为空门禁拦截
            if (isTwoStage) {
                if (s1Types.isEmpty()) {
                    Toast.makeText(ctx, "两阶段模式下，第一关必须至少选择一种挑战！", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                if (s2Types.isEmpty()) {
                    Toast.makeText(ctx, "两阶段模式下，第二关必须至少选择一种挑战！", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
            } else {
                if (s1Types.isEmpty()) {
                    Toast.makeText(ctx, "请至少选择一种挑战！", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
            }

            val useQr = "qr" in s1Types
            val useMath = "math" in s1Types
            val usePoem = "poem" in s1Types
            val useWord = "word" in s1Types
            val useCloze = "cloze" in s1Types
            val useCustom = "custom" in s1Types || "custom" in s2Types

            val mathCount = etMathCount.text.toString().toIntOrNull() ?: 1
            val poemCount = etPoemCount.text.toString().toIntOrNull() ?: 1
            val wordCount = etWordCount.text.toString().toIntOrNull() ?: 1
            val clozeCount = etClozeCount?.text.toString().toIntOrNull() ?: 3
            val testCustomSum = testS1Banks.values.sum() + testS2Banks.values.sum()
            val customCount = if (testCustomSum > 0) testCustomSum else testCustomPerBank
            val isProgressive = testRgVibMode.checkedRadioButtonId != testRbVibFixed.id
            val intensity = if (isProgressive) 1 else testSbVib.progress + 1
            val vibrationMode = if (testRgVibMode.checkedRadioButtonId == testRbVibFixed.id)
                AlarmScheduler.VibrationMode.FIXED else AlarmScheduler.VibrationMode.PROGRESSIVE
            val progMax = if (isProgressive) testSbProgVibMax.progress + 1 else 10
            val progVolMax = if (isProgressive) testSbProgVolMax.progress + 1 else 10
            val unlockMode = if (isTwoStage) AlarmScheduler.UnlockMode.TWO_STAGE else AlarmScheduler.UnlockMode.SINGLE
            val stageCooldownSec = etTestCooldown.text.toString().toIntOrNull()?.coerceIn(0, 3600) ?: 0
            val testAlarmMode = when (testRgRingMode.checkedRadioButtonId) {
                testRbRingOnly.id -> AlarmScheduler.AlarmMode.RING_ONLY
                testRbVibOnly.id -> AlarmScheduler.AlarmMode.VIBRATE_ONLY
                else -> AlarmScheduler.AlarmMode.VIBRATE_AND_RING
            }
            val testRingVolume = testSbRingVol.progress + 1

            val config = LockWindowManager.TestAlarmConfig(
                useQr = useQr, useMath = useMath, usePoem = usePoem, useWord = useWord,
                useCloze = useCloze, useCustom = useCustom,
                mathCount = mathCount, poemCount = poemCount, wordCount = wordCount, clozeCount = clozeCount,
                customCount = customCount,
                vibrationIntensity = intensity, vibrationMode = vibrationMode,
                unlockMode = unlockMode,
                stage1Types = s1Types, stage2Types = s2Types,
                stageCooldownSeconds = stageCooldownSec,
                alarmMode = testAlarmMode,
                ringtoneUri = pendingRingtoneUri ?: AlarmScheduler.getDefaultRingtoneUri(ctx),
                ringVolume = testRingVolume,
                progressiveMaxIntensity = progMax,
                progressiveMaxRingVolume = progVolMax,
                stage1CustomBanks = testS1Banks,
                stage2CustomBanks = testS2Banks
            )
            LockWindowManager.setTestAlarmConfig(config)

            // [id 流转] 测试闹钟使用专用 TEST_ALARM_ID=0,与真实闹钟 id 命名空间隔离
            // 真实闹钟重排后是 1..N,id=0 仅用于测试
            val intent = android.content.Intent(ctx, AlarmActivity::class.java).apply {
                putExtra(AlarmScheduler.EXTRA_ALARM_ID, AlarmScheduler.TEST_ALARM_ID)
            }
            startActivity(intent)
            testDialog.dismiss()
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
        if (entry.enabled) {
            maybeShowKeepAliveGuideOnFirstActiveAlarm()
        }
    }

    private fun updateAlarm(entry: AlarmScheduler.AlarmEntry) {
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

    private fun showSystemBackupHelpDialog() {
        AlertDialog.Builder(requireContext())
            .setTitle("同步到系统闹钟")
            .setMessage("同步会把这个闹钟加入到系统闹钟，防止 知止 可能出现权限或者其他的问题影响你的正事。")
            .setPositiveButton("知道了", null)
            .show()

    }

    private fun addSystemAlarm(alarm: AlarmScheduler.AlarmEntry) {
        val ctx = requireContext()
        val baseIntent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
            putExtra(AlarmClock.EXTRA_HOUR, alarm.hour)
            putExtra(AlarmClock.EXTRA_MINUTES, alarm.minute)
            putExtra(AlarmClock.EXTRA_DAYS, daysToSystemFormat(alarm.days))
            putExtra(AlarmClock.EXTRA_MESSAGE, "知止 备份闹钟")
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
            // 记录同步状态，onResume 时校验
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
            Toast.makeText(ctx, "同步失败，请手动添加: ${alarm.hour}:${String.format("%02d", alarm.minute)}", Toast.LENGTH_LONG).show()
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