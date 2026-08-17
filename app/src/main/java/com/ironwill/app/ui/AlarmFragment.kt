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
package com.ironwill.app.ui

import android.content.Intent
import android.content.pm.ResolveInfo
import android.graphics.Color
import android.graphics.Typeface
import android.media.RingtoneManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.AlarmClock
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
import android.widget.TextView
import android.widget.TimePicker
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.ironwill.app.R
import com.ironwill.app.manager.AlarmScheduler
import com.ironwill.app.manager.AlarmSystemBackupUseCase




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
    private var tvVibrationSummary: TextView? = null
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

        // 默认设置 → 合并为单一入口
        tvSnoozeSummary = view.findViewById(R.id.tv_snooze_summary)
        tvVibrationSummary = view.findViewById(R.id.tv_vibration_summary)
        tvChallengeSummary = view.findViewById(R.id.tv_challenge_summary)
        tvUnlockSummary = view.findViewById(R.id.tv_unlock_summary)

        // 第一个卡片改标题为"闹钟默认设置"，点击进入统一弹窗
        (view.findViewById<LinearLayout>(R.id.layout_snooze_settings)?.getChildAt(0) as? TextView)?.text = "⚙️ 闹钟默认设置"
        view.findViewById<View>(R.id.layout_snooze_settings)?.setOnClickListener {
            showDefaultSettingsDialog()
        }

        // 隐藏分散的三个设置卡片
        listOf(R.id.layout_vibration_settings, R.id.layout_challenge_settings, R.id.layout_unlock_settings).forEach { id ->
            (view.findViewById<View>(id)?.parent as? View)?.visibility = View.GONE
        }

        // 收藏本按钮（合并为 1 个入口，内部 tab 切换）
        view.findViewById<Button>(R.id.btn_bookmarked_poems)?.apply {
            text = "收藏本"
            setOnClickListener {
                startActivity(Intent(requireContext(), BookmarkActivity::class.java))
            }
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

    private fun reloadAlarms() {
        alarms.clear()
        alarms.addAll(AlarmScheduler.getAlarms(requireContext()).sortedWith(compareBy({ it.hour }, { it.minute })))
        adapter?.updateAll(alarms)
        lastRenderedActiveAlarmId = AlarmScheduler.getActiveAlarmId(requireContext())
        lastRenderedOverlayShowing = LockWindowManager.isAlarmOverlayActuallyShowing
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
        val ctx = requireContext()

        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 20, 24, 20)
            setBackgroundResource(R.drawable.bg_sea_surface)
        }

        val scrollView = ScrollView(ctx).apply {
            addView(container)
        }

        val MP = LinearLayout.LayoutParams.MATCH_PARENT
        val WC = LinearLayout.LayoutParams.WRAP_CONTENT

        fun addSectionLabel(text: String) {
            container.addView(TextView(ctx).apply {
                this.text = text
                textSize = 14f
                setTextColor(Color.parseColor("#000000"))
                layoutParams = LinearLayout.LayoutParams(WC, WC).apply {
                    bottomMargin = dp(8)
                }
            })
        }

        // 辅助函数：标签+输入框（勿扰时段风格）
        fun addLabeledField(label: String, value: String, suffix: String = ""): EditText {
            addSectionLabel(label)
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(MP, WC).apply {
                    bottomMargin = dp(16)
                }
            }
            val et = EditText(ctx).apply {
                setText(value)
                inputType = InputType.TYPE_CLASS_NUMBER
                textSize = 14f
                setTextColor(Color.parseColor("#000000"))
                setBackgroundResource(R.drawable.bg_sea_bubble)
                setPadding(dp(16), dp(10), dp(16), dp(10))
                layoutParams = LinearLayout.LayoutParams(0, WC, 1f)
            }
            row.addView(et)
            if (suffix.isNotEmpty()) {
                row.addView(TextView(ctx).apply {
                    text = suffix
                    textSize = 14f
                    setTextColor(Color.parseColor("#000000"))
                    setPadding(dp(8), 0, 0, 0)
                })
            }
            container.addView(row)
            return et
        }

        // --- 1. 解锁方式 ---
        addSectionLabel("解锁方式")
        val rgMode = RadioGroup(ctx).apply {
            orientation = RadioGroup.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(MP, WC).apply { bottomMargin = dp(8) }
        }
        val rbSingle = RadioButton(ctx).apply { text = "单次"; id = View.generateViewId(); setTextColor(Color.parseColor("#000000")) }
        val rbTwoStage = RadioButton(ctx).apply { text = "双阶段"; id = View.generateViewId(); setTextColor(Color.parseColor("#000000")) }
        val curMode = AlarmScheduler.getDefaultUnlockMode(ctx)
        if (curMode == AlarmScheduler.UnlockMode.TWO_STAGE) rbTwoStage.isChecked = true else rbSingle.isChecked = true
        rgMode.addView(rbSingle)
        rgMode.addView(rbTwoStage)
        container.addView(rgMode)

        // Stage 1
        addSectionLabel("第一关类型")
        val curS1 = AlarmScheduler.getDefaultStage1Types(ctx)
        val cbS1Qr = CheckBox(ctx).apply { text = "扫码"; isChecked = "qr" in curS1; setTextColor(Color.parseColor("#000000")) }
        val cbS1Math = CheckBox(ctx).apply { text = "数学"; isChecked = "math" in curS1; setTextColor(Color.parseColor("#000000")) }
        val cbS1Poem = CheckBox(ctx).apply { text = "诗词"; isChecked = "poem" in curS1; setTextColor(Color.parseColor("#000000")) }
        val cbS1Word = CheckBox(ctx).apply { text = "单词"; isChecked = "word" in curS1; setTextColor(Color.parseColor("#000000")) }
        val cbS1Cloze = CheckBox(ctx).apply { text = "选词填空"; isChecked = "cloze" in curS1; setTextColor(Color.parseColor("#000000")) }
        val s1Row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(MP, WC).apply { bottomMargin = dp(4) }
        }
        s1Row.addView(cbS1Qr); s1Row.addView(cbS1Math); s1Row.addView(cbS1Poem); s1Row.addView(cbS1Word)
        val s1Row2 = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(MP, WC).apply { bottomMargin = dp(8) }
        }
        s1Row2.addView(cbS1Cloze)
        container.addView(s1Row)
        container.addView(s1Row2)

        // Stage 2
        val tvS2Label = TextView(ctx).apply {
            text = "第二关类型"
            textSize = 14f
            setTextColor(Color.parseColor("#000000"))
            layoutParams = LinearLayout.LayoutParams(WC, WC).apply { bottomMargin = dp(8) }
        }
        val curS2 = AlarmScheduler.getDefaultStage2Types(ctx)
        val cbS2Qr = CheckBox(ctx).apply { text = "扫码"; isChecked = "qr" in curS2; setTextColor(Color.parseColor("#000000")) }
        val cbS2Math = CheckBox(ctx).apply { text = "数学"; isChecked = "math" in curS2; setTextColor(Color.parseColor("#000000")) }
        val cbS2Poem = CheckBox(ctx).apply { text = "诗词"; isChecked = "poem" in curS2; setTextColor(Color.parseColor("#000000")) }
        val cbS2Word = CheckBox(ctx).apply { text = "单词"; isChecked = "word" in curS2; setTextColor(Color.parseColor("#000000")) }
        val cbS2Cloze = CheckBox(ctx).apply { text = "选词填空"; isChecked = "cloze" in curS2; setTextColor(Color.parseColor("#000000")) }
        val s2Row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(MP, WC).apply { bottomMargin = dp(4) }
        }
        s2Row.addView(cbS2Qr); s2Row.addView(cbS2Math); s2Row.addView(cbS2Poem); s2Row.addView(cbS2Word)
        val s2Row2 = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(MP, WC).apply { bottomMargin = dp(8) }
        }
        s2Row2.addView(cbS2Cloze)
        container.addView(tvS2Label)
        container.addView(s2Row)
        container.addView(s2Row2)

        // 阶段冷却时间（与双阶段绑定可见性，紧凑单行）
        val cooldownRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(MP, WC).apply { bottomMargin = dp(16) }
        }
        cooldownRow.addView(TextView(ctx).apply {
            text = "阶段冷却"; textSize = 13f
            setTextColor(Color.parseColor("#000000"))
            setPadding(0, 0, dp(8), 0)
        })
        val etCooldown = EditText(ctx).apply {
            setText(AlarmScheduler.getDefaultStageCooldownSeconds(ctx).toString())
            inputType = InputType.TYPE_CLASS_NUMBER; textSize = 13f
            setTextColor(Color.parseColor("#000000"))
            setBackgroundResource(R.drawable.bg_sea_bubble)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            layoutParams = LinearLayout.LayoutParams(dp(72), WC)
        }
        cooldownRow.addView(etCooldown)
        cooldownRow.addView(TextView(ctx).apply {
            text = " 秒（0=立即开放）"; textSize = 12f; setTextColor(Color.GRAY)
        })
        container.addView(cooldownRow)

        fun updateStage2Visibility() {
            val visible = rgMode.checkedRadioButtonId == rbTwoStage.id
            tvS2Label.visibility = if (visible) View.VISIBLE else View.GONE
            s2Row.visibility = if (visible) View.VISIBLE else View.GONE
            cooldownRow.visibility = if (visible) View.VISIBLE else View.GONE
        }
        rgMode.setOnCheckedChangeListener { _, _ -> updateStage2Visibility() }
        updateStage2Visibility()

        // 双列字段辅助函数
        fun addTwoFields(label1: String, v1: String, s1: String, label2: String?, v2: String?, s2: String?): Pair<EditText, EditText?> {
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(MP, WC).apply { bottomMargin = dp(12) }
            }
            fun makeCell(label: String, value: String, suffix: String): EditText {
                val cell = LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, WC, 1f)
                }
                cell.addView(TextView(ctx).apply { text = label; textSize = 13f; setTextColor(Color.parseColor("#000000")) })
                val et = EditText(ctx).apply {
                    setText(value); inputType = InputType.TYPE_CLASS_NUMBER; textSize = 13f
                    setTextColor(Color.parseColor("#000000"))
                    setBackgroundResource(R.drawable.bg_sea_bubble)
                    setPadding(dp(8), dp(6), dp(8), dp(6))
                    layoutParams = LinearLayout.LayoutParams(dp(56), WC).apply { marginStart = dp(4) }
                }
                cell.addView(et)
                cell.addView(TextView(ctx).apply { text = suffix; textSize = 12f; setTextColor(Color.GRAY); setPadding(dp(2), 0, 0, 0) })
                row.addView(cell)
                return et
            }
            val et1 = makeCell(label1, v1, s1)
            var et2: EditText? = null
            if (label2 != null && v2 != null) {
                row.addView(View(ctx).apply { layoutParams = LinearLayout.LayoutParams(dp(12), 0) })
                et2 = makeCell(label2!!, v2, s2 ?: "")
            } else {
                row.addView(View(ctx).apply { layoutParams = LinearLayout.LayoutParams(0, 0, 1f) })
            }
            container.addView(row)
            return Pair(et1, et2)
        }

        // --- 2. 默认挑战数量 ---
        addSectionLabel("默认挑战数量")
        val (etMathCount, etPoemCount) = addTwoFields(
            "数学题", AlarmScheduler.getDefaultMathCount(ctx).toString(), "道",
            "诗词句", AlarmScheduler.getDefaultPoemCount(ctx).toString(), "句"
        )
        val (etWordCount, etClozeCount) = addTwoFields(
            "单词", AlarmScheduler.getDefaultWordCount(ctx).toString(), "个",
            "选词填空", AlarmScheduler.getDefaultClozeCount(ctx).toString(), "道"
        )

        // --- 3. 再响设置 ---
        addSectionLabel("再响设置")
        val (etSnoozeMinutes, etSnoozeMaxCount) = addTwoFields(
            "间隔", AlarmScheduler.getSnoozeMinutes(ctx).toString(), "分钟",
            "最多", AlarmScheduler.getMaxSnoozeCount(ctx).toString(), "次"
        )

        // --- 4. 铃声与震动 ---
        addSectionLabel("铃声与震动")
        val rgRingMode = RadioGroup(ctx).apply {
            orientation = RadioGroup.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(MP, WC).apply { bottomMargin = dp(8) }
        }
        val rbVibrateRing = RadioButton(ctx).apply { text = "震动+铃声"; id = View.generateViewId(); setTextColor(Color.parseColor("#000000")) }
        val rbVibrateOnly = RadioButton(ctx).apply { text = "仅震动"; id = View.generateViewId(); setTextColor(Color.parseColor("#000000")) }
        val rbRingOnly = RadioButton(ctx).apply { text = "仅铃声"; id = View.generateViewId(); setTextColor(Color.parseColor("#000000")) }
        when (AlarmScheduler.getDefaultAlarmMode(ctx)) {
            AlarmScheduler.AlarmMode.VIBRATE_AND_RING -> rbVibrateRing.isChecked = true
            AlarmScheduler.AlarmMode.VIBRATE_ONLY -> rbVibrateOnly.isChecked = true
            AlarmScheduler.AlarmMode.RING_ONLY -> rbRingOnly.isChecked = true
        }
        rgRingMode.addView(rbVibrateRing); rgRingMode.addView(rbVibrateOnly); rgRingMode.addView(rbRingOnly)
        container.addView(rgRingMode)

        var pickedRingtoneUri: String? = AlarmScheduler.getDefaultRingtoneUri(ctx)
        val btnPickRingtone = Button(ctx).apply {
            text = if (pickedRingtoneUri != null) "已选自定义铃声" else "选择铃声（默认系统闹钟）"
            setAllCaps(false); textSize = 12f; setPadding(dp(12), dp(8), dp(12), dp(8))
            setTextColor(Color.parseColor("#000000"))
            setBackgroundResource(R.drawable.bg_sea_bubble)
            layoutParams = LinearLayout.LayoutParams(WC, WC).apply { bottomMargin = dp(16) }
        }
        btnPickRingtone.setOnClickListener {
            val intent = Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
                putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_ALARM)
                putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
                putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
                pickedRingtoneUri?.let { putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, android.net.Uri.parse(it)) }
            }
            startActivityForResult(intent, REQUEST_PICK_RINGTONE)
        }
        container.addView(btnPickRingtone)

        // 震动模式（渐进/固定）
        val rgVibModeDefault = RadioGroup(ctx).apply {
            orientation = RadioGroup.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(MP, WC).apply { bottomMargin = dp(8) }
        }
        val rbVibProgDefault = RadioButton(ctx).apply { text = "渐进"; id = View.generateViewId(); setTextColor(Color.parseColor("#000000")) }
        val rbVibFixedDefault = RadioButton(ctx).apply { text = "固定"; id = View.generateViewId(); setTextColor(Color.parseColor("#000000")) }
        when (AlarmScheduler.getDefaultVibrationMode(ctx)) {
            AlarmScheduler.VibrationMode.FIXED -> rbVibFixedDefault.isChecked = true
            else -> rbVibProgDefault.isChecked = true
        }
        rgVibModeDefault.addView(rbVibProgDefault); rgVibModeDefault.addView(rbVibFixedDefault)
        container.addView(rgVibModeDefault)

        // 震动强度 — 固定模式：单个滑块
        val vibRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(MP, WC).apply { bottomMargin = dp(12) }
        }
        vibRow.addView(TextView(ctx).apply { text = "震动"; textSize = 13f; setTextColor(Color.parseColor("#000000")); setPadding(0, 0, dp(8), 0) })
        val sbVibration = SeekBar(ctx).apply {
            max = 9; progress = AlarmScheduler.getDefaultVibrationIntensity(ctx) - 1
            layoutParams = LinearLayout.LayoutParams(0, WC, 1f)
        }
        val tvVibLabel = TextView(ctx).apply {
            text = "${sbVibration.progress + 1} / 10"; textSize = 14f
            setTextColor(Color.parseColor("#000000")); setPadding(dp(8), 0, 0, 0)
        }
        sbVibration.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) { tvVibLabel.text = "${p + 1} / 10" }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
        vibRow.addView(sbVibration); vibRow.addView(tvVibLabel)
        container.addView(vibRow)

        // 渐进模式提示文字（起始强度固定为 1）
        val progHintRow = TextView(ctx).apply {
            text = "起始强度: 1（固定）"
            textSize = 12f; setTextColor(Color.GRAY)
            layoutParams = LinearLayout.LayoutParams(MP, WC).apply { bottomMargin = dp(4) }
        }
        container.addView(progHintRow)

        // 震动最大强度（渐进模式可见）
        val progVibRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(MP, WC).apply { bottomMargin = dp(12) }
        }
        progVibRow.addView(TextView(ctx).apply { text = "震动最大"; textSize = 13f; setTextColor(Color.parseColor("#000000")); setPadding(0, 0, dp(8), 0) })
        val sbProgVibMax = SeekBar(ctx).apply {
            max = 9; progress = AlarmScheduler.getDefaultProgressiveMaxIntensity(ctx) - 1
            layoutParams = LinearLayout.LayoutParams(0, WC, 1f)
        }
        val tvProgVibMaxLabel = TextView(ctx).apply {
            text = "${sbProgVibMax.progress + 1} / 10"; textSize = 14f
            setTextColor(Color.parseColor("#000000")); setPadding(dp(8), 0, 0, 0)
        }
        sbProgVibMax.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) { tvProgVibMaxLabel.text = "${p + 1} / 10" }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
        progVibRow.addView(sbProgVibMax); progVibRow.addView(tvProgVibMaxLabel)
        container.addView(progVibRow)

        // 5分钟后自动升级（渐进模式可见）
        val autoEscalateRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(MP, WC).apply { bottomMargin = dp(12) }
        }
        val cbAutoEscalate = CheckBox(ctx).apply {
            text = "5分钟后自动升级到最大"
            textSize = 13f
            setTextColor(Color.parseColor("#000000"))
            isChecked = false
        }
        autoEscalateRow.addView(cbAutoEscalate)
        container.addView(autoEscalateRow)

        // 声音最大强度（渐进模式可见）
        val progVolRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(MP, WC).apply { bottomMargin = dp(12) }
        }
        progVolRow.addView(TextView(ctx).apply { text = "声音最大"; textSize = 13f; setTextColor(Color.parseColor("#000000")); setPadding(0, 0, dp(8), 0) })
        val sbProgVolMax = SeekBar(ctx).apply {
            max = 9; progress = AlarmScheduler.getDefaultProgressiveMaxRingVolume(ctx) - 1
            layoutParams = LinearLayout.LayoutParams(0, WC, 1f)
        }
        val tvProgVolMaxLabel = TextView(ctx).apply {
            text = "${sbProgVolMax.progress + 1} / 10"; textSize = 14f
            setTextColor(Color.parseColor("#000000")); setPadding(dp(8), 0, 0, 0)
        }
        sbProgVolMax.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) { tvProgVolMaxLabel.text = "${p + 1} / 10" }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
        progVolRow.addView(sbProgVolMax); progVolRow.addView(tvProgVolMaxLabel)
        container.addView(progVolRow)

        // 声音强度（仅非"仅震动"模式可见，横向同一行）
        val volRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(MP, WC).apply { bottomMargin = dp(12) }
        }
        volRow.addView(TextView(ctx).apply { text = "声音"; textSize = 13f; setTextColor(Color.parseColor("#000000")); setPadding(0, 0, dp(8), 0) })
        val sbRingVolume = SeekBar(ctx).apply {
            max = 9; progress = AlarmScheduler.getDefaultRingVolume(ctx) - 1
            layoutParams = LinearLayout.LayoutParams(0, WC, 1f)
        }
        val tvRingVolLabel = TextView(ctx).apply {
            text = "${sbRingVolume.progress + 1} / 10"; textSize = 14f
            setTextColor(Color.parseColor("#000000")); setPadding(dp(8), 0, 0, 0)
        }
        sbRingVolume.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) { tvRingVolLabel.text = "${p + 1} / 10" }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
        volRow.addView(sbRingVolume); volRow.addView(tvRingVolLabel)
        container.addView(volRow)

        fun updateRingtoneVisibility() {
            val modeId = rgRingMode.checkedRadioButtonId
            val isVibOnly = modeId == rbVibrateOnly.id
            val isRingOnly = modeId == rbRingOnly.id
            val isProgressive = rgVibModeDefault.checkedRadioButtonId == rbVibProgDefault.id
            btnPickRingtone.visibility = if (isVibOnly) View.GONE else View.VISIBLE
            // 渐进模式：根据铃声模式显示对应的最大强度滑块
            // 固定模式：显示独立震动/声音滑块
            progHintRow.visibility = if (!isProgressive) View.GONE else View.VISIBLE
            progVibRow.visibility = if (!isProgressive || isRingOnly) View.GONE else View.VISIBLE
            autoEscalateRow.visibility = if (isRingOnly) View.GONE else View.VISIBLE
            progVolRow.visibility = if (!isProgressive || isVibOnly) View.GONE else View.VISIBLE
            vibRow.visibility = if (isProgressive || isRingOnly) View.GONE else View.VISIBLE
            volRow.visibility = if (isProgressive || isVibOnly) View.GONE else View.VISIBLE
        }
        rgRingMode.setOnCheckedChangeListener { _, _ -> updateRingtoneVisibility() }
        updateRingtoneVisibility()
        rgVibModeDefault.setOnCheckedChangeListener { _, _ -> updateRingtoneVisibility() }
        rgMode.setOnCheckedChangeListener { _, _ -> updateStage2Visibility(); updateRingtoneVisibility() }

        // --- 弹窗 ---
        AlertDialog.Builder(ctx)
            .setTitle("⚙️ 默认设置")
            .setView(scrollView)
            .setPositiveButton("保存") { _, _ ->
                // 保存再响
                val snoozeMin = etSnoozeMinutes.text.toString().toIntOrNull() ?: 10
                val snoozeMax = etSnoozeMaxCount?.text.toString().toIntOrNull() ?: 2
                if (snoozeMin > 0) AlarmScheduler.setSnoozeMinutes(ctx, snoozeMin)
                if (snoozeMax >= 0) AlarmScheduler.setMaxSnoozeCount(ctx, snoozeMax)

                // 保存震动
                val defVibMode = if (rgVibModeDefault.checkedRadioButtonId == rbVibFixedDefault.id)
                    AlarmScheduler.VibrationMode.FIXED else AlarmScheduler.VibrationMode.PROGRESSIVE
                if (defVibMode == AlarmScheduler.VibrationMode.PROGRESSIVE) {
                    AlarmScheduler.setDefaultProgressiveMaxIntensity(ctx, sbProgVibMax.progress + 1)
                    AlarmScheduler.setDefaultProgressiveMaxRingVolume(ctx, sbProgVolMax.progress + 1)
                } else {
                    AlarmScheduler.setDefaultVibrationIntensity(ctx, sbVibration.progress + 1)
                }

                // 保存挑战数量
                val math = etMathCount.text.toString().toIntOrNull()
                val poem = etPoemCount?.text.toString().toIntOrNull()
                val word = etWordCount.text.toString().toIntOrNull()
                val cloze = etClozeCount?.text.toString().toIntOrNull()
                if (math != null && math > 0) AlarmScheduler.setDefaultMathCount(ctx, math)
                if (poem != null && poem > 0) AlarmScheduler.setDefaultPoemCount(ctx, poem)
                if (word != null && word > 0) AlarmScheduler.setDefaultWordCount(ctx, word)
                if (cloze != null && cloze > 0) AlarmScheduler.setDefaultClozeCount(ctx, cloze)

                // 保存解锁方式
                val mode = if (rbTwoStage.isChecked) AlarmScheduler.UnlockMode.TWO_STAGE else AlarmScheduler.UnlockMode.SINGLE
                val s1Types = mutableSetOf<String>().apply {
                    if (cbS1Qr.isChecked) add("qr"); if (cbS1Math.isChecked) add("math")
                    if (cbS1Poem.isChecked) add("poem"); if (cbS1Word.isChecked) add("word")
                    if (cbS1Cloze.isChecked) add("cloze")
                }
                val s2Types = mutableSetOf<String>().apply {
                    if (cbS2Qr.isChecked) add("qr"); if (cbS2Math.isChecked) add("math")
                    if (cbS2Poem.isChecked) add("poem"); if (cbS2Word.isChecked) add("word")
                    if (cbS2Cloze.isChecked) add("cloze")
                }
                val effectiveS1 = if (s1Types.isEmpty()) setOf("qr") else s1Types
                val effectiveS2 = if (mode == AlarmScheduler.UnlockMode.TWO_STAGE && s2Types.isEmpty()) setOf("math", "poem", "word", "cloze") else s2Types
                AlarmScheduler.setDefaultUnlockMode(ctx, mode)
                AlarmScheduler.setDefaultStage1Types(ctx, effectiveS1)
                AlarmScheduler.setDefaultStage2Types(ctx, effectiveS2)

                // 保存阶段冷却
                val cooldown = etCooldown.text.toString().toIntOrNull() ?: 0
                AlarmScheduler.setDefaultStageCooldownSeconds(ctx, cooldown)

                // 保存铃声模式
                val ringMode = when (rgRingMode.checkedRadioButtonId) {
                    rbVibrateRing.id -> AlarmScheduler.AlarmMode.VIBRATE_AND_RING
                    rbRingOnly.id -> AlarmScheduler.AlarmMode.RING_ONLY
                    else -> AlarmScheduler.AlarmMode.VIBRATE_ONLY
                }
                AlarmScheduler.setDefaultAlarmMode(ctx, ringMode)
                if (ringMode != AlarmScheduler.AlarmMode.VIBRATE_ONLY) {
                    pendingRingtoneUri?.let { AlarmScheduler.setDefaultRingtoneUri(ctx, it) }
                }
                AlarmScheduler.setDefaultRingVolume(ctx, sbRingVolume.progress + 1)
                AlarmScheduler.setDefaultVibrationMode(ctx, defVibMode)

                refreshSettingsSummaries()
                Toast.makeText(ctx, "默认设置已保存", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun refreshSettingsSummaries() {
        val ctx = context ?: return
        // 统一入口不显示单项摘要，避免误解"只能设置再响"
        tvSnoozeSummary?.text = ""

        val vib = AlarmScheduler.getDefaultVibrationIntensity(ctx)
        tvVibrationSummary?.text = "强度: $vib/10"

        val math = AlarmScheduler.getDefaultMathCount(ctx)
        val poem = AlarmScheduler.getDefaultPoemCount(ctx)
        val word = AlarmScheduler.getDefaultWordCount(ctx)
        val cloze = AlarmScheduler.getDefaultClozeCount(ctx)
        tvChallengeSummary?.text = "数学${math}道/诗词${poem}句/单词${word}个/选词${cloze}道"

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
        "cloze" -> "选词填空"
        else -> type
    }

    private fun showEditDialog(existing: AlarmScheduler.AlarmEntry?) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_alarm_edit, null)
        val timePicker = dialogView.findViewById<TimePicker>(R.id.tp_alarm_edit)
        timePicker.setIs24HourView(true)

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
        val layoutStage1 = dialogView.findViewById<View>(R.id.layout_stage1)
        val tvStage1Label = dialogView.findViewById<TextView>(R.id.tv_stage1_label)

        val layoutStage2 = dialogView.findViewById<View>(R.id.layout_stage2)
        val cbS2Qr = dialogView.findViewById<android.widget.CheckBox>(R.id.cb_s2_qr)
        val cbS2Math = dialogView.findViewById<android.widget.CheckBox>(R.id.cb_s2_math)
        val cbS2Poem = dialogView.findViewById<android.widget.CheckBox>(R.id.cb_s2_poem)
        val cbS2Word = dialogView.findViewById<android.widget.CheckBox>(R.id.cb_s2_word)
        val cbS2Cloze = dialogView.findViewById<android.widget.CheckBox>(R.id.cb_s2_cloze)

        // 阶段冷却时间（双阶段可见，程序化添加）
        val innerLayout = (dialogView as? android.view.ViewGroup)?.let { root ->
            (root.getChildAt(0) as? ScrollView)?.getChildAt(0) as? LinearLayout
        }
        val cooldownContainer = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) }
        }
        cooldownContainer.addView(TextView(requireContext()).apply { text = "阶段冷却时间"; textSize = 14f; setTextColor(Color.parseColor("#000000")) })
        cooldownContainer.addView(TextView(requireContext()).apply { text = "第一关通过后等指定秒数再开放第二关（0=立即开放）"; textSize = 12f; setTextColor(Color.GRAY) })
        val etEditCooldown = EditText(requireContext()).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            textSize = 14f; setTextColor(Color.parseColor("#000000"))
            setBackgroundResource(R.drawable.bg_sea_bubble)
            setPadding(dp(16), dp(10), dp(16), dp(10))
            setText((existing?.stageCooldownSeconds ?: AlarmScheduler.getDefaultStageCooldownSeconds(requireContext())).toString())
        }
        cooldownContainer.addView(etEditCooldown)
        innerLayout?.addView(cooldownContainer)

        // 铃声模式（程序化添加）
        val editRingtoneContainer = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) }
        }
        editRingtoneContainer.addView(TextView(requireContext()).apply { text = "铃声与震动"; textSize = 14f; setTextColor(Color.parseColor("#000000")) })
        val editRgRingMode = RadioGroup(requireContext()).apply { orientation = RadioGroup.HORIZONTAL }
        val editRbVibRing = RadioButton(requireContext()).apply { text = "震动+铃声"; id = View.generateViewId(); setTextColor(Color.parseColor("#000000")) }
        val editRbVibOnly = RadioButton(requireContext()).apply { text = "仅震动"; id = View.generateViewId(); setTextColor(Color.parseColor("#000000")) }
        val editRbRingOnly = RadioButton(requireContext()).apply { text = "仅铃声"; id = View.generateViewId(); setTextColor(Color.parseColor("#000000")) }
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
        val editRbVibProg = RadioButton(requireContext()).apply { text = "渐进"; id = View.generateViewId(); setTextColor(Color.parseColor("#000000")) }
        val editRbVibFixed = RadioButton(requireContext()).apply { text = "固定"; id = View.generateViewId(); setTextColor(Color.parseColor("#000000")) }
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
        editVibRow.addView(TextView(requireContext()).apply { text = "震动"; textSize = 13f; setTextColor(Color.parseColor("#000000")); setPadding(0, 0, dp(8), 0) })
        val editSbVib = SeekBar(requireContext()).apply {
            max = 9; progress = (existing?.vibrationIntensity ?: AlarmScheduler.getDefaultVibrationIntensity(requireContext())) - 1
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val editTvVibLabel = TextView(requireContext()).apply {
            text = "${editSbVib.progress + 1} / 10"; textSize = 14f
            setTextColor(Color.parseColor("#000000")); setPadding(dp(8), 0, 0, 0)
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
            textSize = 12f; setTextColor(Color.GRAY)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) }
        }
        editRingtoneContainer.addView(editProgHintRow)

        // 震动最大强度（渐进模式可见）
        val editProgVibRow = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) }
        }
        editProgVibRow.addView(TextView(requireContext()).apply { text = "震动最大"; textSize = 13f; setTextColor(Color.parseColor("#000000")); setPadding(0, 0, dp(8), 0) })
        val editSbProgVibMax = SeekBar(requireContext()).apply {
            max = 9; progress = (existing?.progressiveMaxIntensity ?: AlarmScheduler.getDefaultProgressiveMaxIntensity(requireContext())) - 1
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val editTvProgVibMaxLabel = TextView(requireContext()).apply {
            text = "${editSbProgVibMax.progress + 1} / 10"; textSize = 14f
            setTextColor(Color.parseColor("#000000")); setPadding(dp(8), 0, 0, 0)
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
            setTextColor(Color.parseColor("#000000"))
            isChecked = existing?.autoEscalate ?: false
        }
        editAutoEscalateRow.addView(editCbAutoEscalate)
        editRingtoneContainer.addView(editAutoEscalateRow)

        // 声音最大强度（渐进模式可见）
        val editProgVolRow = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) }
        }
        editProgVolRow.addView(TextView(requireContext()).apply { text = "声音最大"; textSize = 13f; setTextColor(Color.parseColor("#000000")); setPadding(0, 0, dp(8), 0) })
        val editSbProgVolMax = SeekBar(requireContext()).apply {
            max = 9; progress = (existing?.progressiveMaxRingVolume ?: AlarmScheduler.getDefaultProgressiveMaxRingVolume(requireContext())) - 1
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val editTvProgVolMaxLabel = TextView(requireContext()).apply {
            text = "${editSbProgVolMax.progress + 1} / 10"; textSize = 14f
            setTextColor(Color.parseColor("#000000")); setPadding(dp(8), 0, 0, 0)
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
        editVolContainer.addView(TextView(requireContext()).apply { text = "声音"; textSize = 13f; setTextColor(Color.parseColor("#000000")); setPadding(0, 0, dp(8), 0) })
        val editSbRingVol = SeekBar(requireContext()).apply {
            max = 9; progress = (existing?.ringVolume ?: AlarmScheduler.getDefaultRingVolume(requireContext())) - 1
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val editTvRingVolLabel = TextView(requireContext()).apply {
            text = "${editSbRingVol.progress + 1} / 10"; textSize = 14f
            setTextColor(Color.parseColor("#000000")); setPadding(dp(8), 0, 0, 0)
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
            cooldownContainer.visibility = if (isTwoStage) View.VISIBLE else View.GONE
        }
        rgUnlockMode.setOnCheckedChangeListener { _, _ -> updateStage2Visibility(); updateEditRingtoneVisibility() }

        if (existing != null) {
            timePicker.hour = existing.hour
            timePicker.minute = existing.minute
            checkboxes.forEachIndexed { index, cb -> cb.isChecked = existing.days.getOrNull(index) == true }

            val s1 = existing.stage1Types
            val s2 = existing.stage2Types
            cbS1Qr.isChecked = "qr" in s1
            cbS1Math.isChecked = "math" in s1
            cbS1Poem.isChecked = "poem" in s1
            cbS1Word.isChecked = "word" in s1
            cbS1Cloze?.isChecked = "cloze" in s1
            etMathCount.setText(existing.mathCount.toString())
            etPoemCount.setText(existing.poemCount.toString())
            etWordCount.setText(existing.wordCount.toString())
            etClozeCount?.setText(existing.clozeCount.toString())
            cbS2Qr.isChecked = "qr" in s2
            cbS2Math.isChecked = "math" in s2
            cbS2Poem.isChecked = "poem" in s2
            cbS2Word.isChecked = "word" in s2
            cbS2Cloze?.isChecked = "cloze" in s2

            if (existing.unlockMode == AlarmScheduler.UnlockMode.TWO_STAGE) rbUnlockTwoStage.isChecked = true
            else rbUnlockSingle.isChecked = true
        } else {
            timePicker.hour = 7
            timePicker.minute = 0
            checkboxes.forEach { it.isChecked = true }
            val defaultCtx = requireContext()
            val defS1 = AlarmScheduler.getDefaultStage1Types(defaultCtx)
            val defS2 = AlarmScheduler.getDefaultStage2Types(defaultCtx)
            val defMode = AlarmScheduler.getDefaultUnlockMode(defaultCtx)
            cbS1Qr.isChecked = "qr" in defS1
            cbS1Math.isChecked = "math" in defS1
            cbS1Poem.isChecked = "poem" in defS1
            cbS1Word.isChecked = "word" in defS1
            cbS1Cloze?.isChecked = "cloze" in defS1
            etMathCount.setText(AlarmScheduler.getDefaultMathCount(defaultCtx).toString())
            etPoemCount.setText(AlarmScheduler.getDefaultPoemCount(defaultCtx).toString())
            etWordCount.setText(AlarmScheduler.getDefaultWordCount(defaultCtx).toString())
            etClozeCount?.setText(AlarmScheduler.getDefaultClozeCount(defaultCtx).toString())
            cbS2Qr.isChecked = "qr" in defS2
            cbS2Math.isChecked = "math" in defS2
            cbS2Poem.isChecked = "poem" in defS2
            cbS2Word.isChecked = "word" in defS2
            cbS2Cloze?.isChecked = "cloze" in defS2
            if (defMode == AlarmScheduler.UnlockMode.TWO_STAGE) rbUnlockTwoStage.isChecked = true
            else rbUnlockSingle.isChecked = true
        }
        updateStage2Visibility()

        AlertDialog.Builder(requireContext())
            .setTitle(if (existing == null) "新增闹钟" else "编辑闹钟")
            .setView(dialogView)
            .setPositiveButton("保存") { _, _ ->
                val hour = timePicker.hour
                val minute = timePicker.minute
                val days = BooleanArray(7) { index -> checkboxes[index].isChecked }
                val normalizedDays = if (days.none { it }) BooleanArray(7) { true } else days

                val isTwoStage = rgUnlockMode.checkedRadioButtonId == R.id.rb_unlock_two_stage

                val s1Types = mutableSetOf<String>()
                if (cbS1Qr.isChecked) s1Types.add("qr")
                if (cbS1Math.isChecked) s1Types.add("math")
                if (cbS1Poem.isChecked) s1Types.add("poem")
                if (cbS1Word.isChecked) s1Types.add("word")
                if (cbS1Cloze?.isChecked == true) s1Types.add("cloze")

                val s2Types = mutableSetOf<String>()
                if (isTwoStage) {
                    if (cbS2Qr.isChecked) s2Types.add("qr")
                    if (cbS2Math.isChecked) s2Types.add("math")
                    if (cbS2Poem.isChecked) s2Types.add("poem")
                    if (cbS2Word.isChecked) s2Types.add("word")
                    if (cbS2Cloze?.isChecked == true) s2Types.add("cloze")
                } else {
                    s2Types.addAll(s1Types) // single-stage: same as stage 1 for backward compat
                }

                // Ensure at least one challenge in the first group
                val effectiveS1 = if (s1Types.isEmpty()) setOf("qr") else s1Types
                val useQr = "qr" in effectiveS1
                val useMath = "math" in effectiveS1
                val usePoem = "poem" in effectiveS1
                val useWord = "word" in effectiveS1
                val useCloze = "cloze" in effectiveS1

                // 两关共用同一套挑战数量（与默认设置一致）
                val mathCount = etMathCount.text.toString().toIntOrNull() ?: AlarmScheduler.getDefaultMathCount(requireContext())
                val poemCount = etPoemCount.text.toString().toIntOrNull() ?: AlarmScheduler.getDefaultPoemCount(requireContext())
                val wordCount = etWordCount.text.toString().toIntOrNull() ?: AlarmScheduler.getDefaultWordCount(requireContext())
                val clozeCount = etClozeCount?.text.toString().toIntOrNull() ?: AlarmScheduler.getDefaultClozeCount(requireContext())
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
                        mathCount, poemCount, wordCount, clozeCount,
                        vibrationIntensity, vibrationMode, unlockMode,
                        effectiveS1, s2Types,
                        stageCooldownSeconds = stageCooldownSec,
                        ringtoneUri = AlarmScheduler.getDefaultRingtoneUri(requireContext()),
                        alarmMode = editAlarmModeVal,
                        ringVolume = ringVolume,
                        progressiveMaxIntensity = progMax,
                        progressiveMaxRingVolume = progVolMax,
                        autoEscalate = autoEscalate
                    )
                } else {
                    existing.copy(
                        hour = hour, minute = minute, days = normalizedDays,
                        useQr = useQr, useMath = useMath, usePoem = usePoem, useWord = useWord,
                        mathCount = mathCount, poemCount = poemCount, wordCount = wordCount,
                        clozeCount = clozeCount,
                        vibrationIntensity = vibrationIntensity, vibrationMode = vibrationMode,
                        unlockMode = unlockMode,
                        stage1Types = effectiveS1, stage2Types = s2Types,
                        stageCooldownSeconds = stageCooldownSec,
                        alarmMode = editAlarmModeVal,
                        ringVolume = ringVolume,
                        progressiveMaxIntensity = progMax,
                        progressiveMaxRingVolume = progVolMax,
                        autoEscalate = autoEscalate
                    )
                }
                upsertAlarm(entry)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showTestDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_test_alarm, null)

        val rgUnlockMode = dialogView.findViewById<android.widget.RadioGroup>(R.id.rg_unlock_mode)
        val layoutStage1 = dialogView.findViewById<View>(R.id.layout_stage1)
        val layoutStage2 = dialogView.findViewById<View>(R.id.layout_stage2)
        val tvStage1Label = dialogView.findViewById<TextView>(R.id.tv_stage1_label)

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

        // Stage 2
        val cbS2Qr = dialogView.findViewById<android.widget.CheckBox>(R.id.cb_s2_qr)
        val cbS2Math = dialogView.findViewById<android.widget.CheckBox>(R.id.cb_s2_math)
        val cbS2Poem = dialogView.findViewById<android.widget.CheckBox>(R.id.cb_s2_poem)
        val cbS2Word = dialogView.findViewById<android.widget.CheckBox>(R.id.cb_s2_word)
        val cbS2Cloze = dialogView.findViewById<android.widget.CheckBox>(R.id.cb_s2_cloze)

        // 阶段冷却时间（双阶段可见，程序化添加）
        val testInnerLayout = (dialogView as? android.view.ViewGroup)?.let { root ->
            (root.getChildAt(0) as? ScrollView)?.getChildAt(0) as? LinearLayout
        }
        val testCooldownContainer = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) }
        }
        testCooldownContainer.addView(TextView(requireContext()).apply { text = "阶段冷却时间"; textSize = 14f; setTextColor(Color.parseColor("#000000")) })
        testCooldownContainer.addView(TextView(requireContext()).apply { text = "第一关通过后等指定秒数再开放第二关（0=立即开放）"; textSize = 12f; setTextColor(Color.GRAY) })
        val etTestCooldown = EditText(requireContext()).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            textSize = 14f; setTextColor(Color.parseColor("#000000"))
            setBackgroundResource(R.drawable.bg_sea_bubble)
            setPadding(dp(16), dp(10), dp(16), dp(10))
            setText(AlarmScheduler.getDefaultStageCooldownSeconds(requireContext()).toString())
        }
        testCooldownContainer.addView(etTestCooldown)
        testInnerLayout?.addView(testCooldownContainer)

        // 铃声模式（程序化添加）
        val testRingtoneContainer = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) }
        }
        testRingtoneContainer.addView(TextView(requireContext()).apply { text = "铃声与震动"; textSize = 14f; setTextColor(Color.parseColor("#000000")) })
        val testRgRingMode = RadioGroup(requireContext()).apply { orientation = RadioGroup.HORIZONTAL }
        val testRbVibRing = RadioButton(requireContext()).apply { text = "震动+铃声"; id = View.generateViewId(); setTextColor(Color.parseColor("#000000")) }
        val testRbVibOnly = RadioButton(requireContext()).apply { text = "仅震动"; id = View.generateViewId(); setTextColor(Color.parseColor("#000000")) }
        val testRbRingOnly = RadioButton(requireContext()).apply { text = "仅铃声"; id = View.generateViewId(); setTextColor(Color.parseColor("#000000")) }
        when (AlarmScheduler.getDefaultAlarmMode(requireContext())) {
            AlarmScheduler.AlarmMode.VIBRATE_AND_RING -> testRbVibRing.isChecked = true
            AlarmScheduler.AlarmMode.VIBRATE_ONLY -> testRbVibOnly.isChecked = true
            AlarmScheduler.AlarmMode.RING_ONLY -> testRbRingOnly.isChecked = true
        }
        testRgRingMode.addView(testRbVibRing); testRgRingMode.addView(testRbVibOnly); testRgRingMode.addView(testRbRingOnly)
        testRingtoneContainer.addView(testRgRingMode)

        // 模式（渐进/固定）
        val testRgVibMode = RadioGroup(requireContext()).apply { orientation = RadioGroup.HORIZONTAL }
        val testRbVibProg = RadioButton(requireContext()).apply { text = "渐进"; id = View.generateViewId(); isChecked = true; setTextColor(Color.parseColor("#000000")) }
        val testRbVibFixed = RadioButton(requireContext()).apply { text = "固定"; id = View.generateViewId(); setTextColor(Color.parseColor("#000000")) }
        testRgVibMode.addView(testRbVibProg); testRgVibMode.addView(testRbVibFixed)
        testRingtoneContainer.addView(testRgVibMode)

        // 震动强度 — 固定模式单滑块
        val testVibRow = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) }
        }
        testVibRow.addView(TextView(requireContext()).apply { text = "震动"; textSize = 13f; setTextColor(Color.parseColor("#000000")); setPadding(0, 0, dp(8), 0) })
        val testSbVib = SeekBar(requireContext()).apply {
            max = 9; progress = AlarmScheduler.getDefaultVibrationIntensity(requireContext()) - 1
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val testTvVibLabel = TextView(requireContext()).apply {
            text = "${testSbVib.progress + 1} / 10"; textSize = 14f
            setTextColor(Color.parseColor("#000000")); setPadding(dp(8), 0, 0, 0)
        }
        testSbVib.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) { testTvVibLabel.text = "${p + 1} / 10" }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
        testVibRow.addView(testSbVib); testVibRow.addView(testTvVibLabel)
        testRingtoneContainer.addView(testVibRow)

        // 渐进模式提示文字（起始强度固定为 1）
        val testProgHintRow = TextView(requireContext()).apply {
            text = "起始强度: 1（固定）"
            textSize = 12f; setTextColor(Color.GRAY)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) }
        }
        testRingtoneContainer.addView(testProgHintRow)

        // 震动最大强度（渐进模式可见）
        val testProgVibRow = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) }
        }
        testProgVibRow.addView(TextView(requireContext()).apply { text = "震动最大"; textSize = 13f; setTextColor(Color.parseColor("#000000")); setPadding(0, 0, dp(8), 0) })
        val testSbProgVibMax = SeekBar(requireContext()).apply {
            max = 9; progress = AlarmScheduler.getDefaultProgressiveMaxIntensity(requireContext()) - 1
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val testTvProgVibMaxLabel = TextView(requireContext()).apply {
            text = "${testSbProgVibMax.progress + 1} / 10"; textSize = 14f
            setTextColor(Color.parseColor("#000000")); setPadding(dp(8), 0, 0, 0)
        }
        testSbProgVibMax.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) { testTvProgVibMaxLabel.text = "${p + 1} / 10" }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
        testProgVibRow.addView(testSbProgVibMax); testProgVibRow.addView(testTvProgVibMaxLabel)
        testRingtoneContainer.addView(testProgVibRow)

        // 声音最大强度（渐进模式可见）
        val testProgVolRow = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) }
        }
        testProgVolRow.addView(TextView(requireContext()).apply { text = "声音最大"; textSize = 13f; setTextColor(Color.parseColor("#000000")); setPadding(0, 0, dp(8), 0) })
        val testSbProgVolMax = SeekBar(requireContext()).apply {
            max = 9; progress = AlarmScheduler.getDefaultProgressiveMaxRingVolume(requireContext()) - 1
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val testTvProgVolMaxLabel = TextView(requireContext()).apply {
            text = "${testSbProgVolMax.progress + 1} / 10"; textSize = 14f
            setTextColor(Color.parseColor("#000000")); setPadding(dp(8), 0, 0, 0)
        }
        testSbProgVolMax.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) { testTvProgVolMaxLabel.text = "${p + 1} / 10" }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
        testProgVolRow.addView(testSbProgVolMax); testProgVolRow.addView(testTvProgVolMaxLabel)
        testRingtoneContainer.addView(testProgVolRow)

        // 声音强度（固定模式可见）
        val testVolRow = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) }
        }
        testVolRow.addView(TextView(requireContext()).apply { text = "声音"; textSize = 13f; setTextColor(Color.parseColor("#000000")); setPadding(0, 0, dp(8), 0) })
        val testSbRingVol = SeekBar(requireContext()).apply {
            max = 9; progress = AlarmScheduler.getDefaultRingVolume(requireContext()) - 1
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val testTvRingVolLabel = TextView(requireContext()).apply {
            text = "${testSbRingVol.progress + 1} / 10"; textSize = 14f
            setTextColor(Color.parseColor("#000000")); setPadding(dp(8), 0, 0, 0)
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
            // 渐进：仅震动→只显示震动最大，仅铃声→只显示声音最大
            // 固定：仅震动→只显示震动，仅铃声→只显示声音
            testProgHintRow.visibility = if (!isProgressive) View.GONE else View.VISIBLE
            testProgVibRow.visibility = if (!isProgressive || isRingOnly) View.GONE else View.VISIBLE
            testProgVolRow.visibility = if (!isProgressive || isVibOnly) View.GONE else View.VISIBLE
            testVibRow.visibility = if (isProgressive || isRingOnly) View.GONE else View.VISIBLE
            testVolRow.visibility = if (isProgressive || isVibOnly) View.GONE else View.VISIBLE
        }
        testRgRingMode.setOnCheckedChangeListener { _, _ -> updateTestRingtoneVisibility() }
        updateTestRingtoneVisibility()
        testRgVibMode.setOnCheckedChangeListener { _, _ -> updateTestRingtoneVisibility() }

        fun updateStage2Visibility() {
            val isTwoStage = rgUnlockMode.checkedRadioButtonId == R.id.rb_unlock_two_stage
            layoutStage2.visibility = if (isTwoStage) View.VISIBLE else View.GONE
            tvStage1Label.text = if (isTwoStage) "第一关解锁方式（通过其一即可）" else "解锁方式配置（可并联多选）"
            testCooldownContainer.visibility = if (isTwoStage) View.VISIBLE else View.GONE
        }
        rgUnlockMode.setOnCheckedChangeListener { _, _ -> updateStage2Visibility(); updateTestRingtoneVisibility() }
        updateStage2Visibility()

        AlertDialog.Builder(requireContext())
            .setTitle("测试闹钟配置")
            .setView(dialogView)
            .setPositiveButton("开始测试") { _, _ ->
                val isTwoStage = rgUnlockMode.checkedRadioButtonId == R.id.rb_unlock_two_stage

                val s1Types = mutableSetOf<String>()
                if (cbS1Qr.isChecked) s1Types.add("qr")
                if (cbS1Math.isChecked) s1Types.add("math")
                if (cbS1Poem.isChecked) s1Types.add("poem")
                if (cbS1Word.isChecked) s1Types.add("word")
                if (cbS1Cloze?.isChecked == true) s1Types.add("cloze")

                val s2Types = mutableSetOf<String>()
                if (isTwoStage) {
                    if (cbS2Qr.isChecked) s2Types.add("qr")
                    if (cbS2Math.isChecked) s2Types.add("math")
                    if (cbS2Poem.isChecked) s2Types.add("poem")
                    if (cbS2Word.isChecked) s2Types.add("word")
                    if (cbS2Cloze?.isChecked == true) s2Types.add("cloze")
                } else {
                    s2Types.addAll(s1Types)
                }

                val effectiveS1 = if (s1Types.isEmpty()) setOf("qr") else s1Types
                val useQr = "qr" in effectiveS1
                val useMath = "math" in effectiveS1
                val usePoem = "poem" in effectiveS1
                val useWord = "word" in effectiveS1
                val useCloze = "cloze" in effectiveS1

                val mathCount = etMathCount.text.toString().toIntOrNull() ?: 1
                val poemCount = etPoemCount.text.toString().toIntOrNull() ?: 1
                val wordCount = etWordCount.text.toString().toIntOrNull() ?: 1
                val clozeCount = etClozeCount?.text.toString().toIntOrNull() ?: 3
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
                    useCloze = useCloze,
                    mathCount = mathCount, poemCount = poemCount, wordCount = wordCount, clozeCount = clozeCount,
                    vibrationIntensity = intensity, vibrationMode = vibrationMode,
                    unlockMode = unlockMode,
                    stage1Types = effectiveS1, stage2Types = s2Types,
                    stageCooldownSeconds = stageCooldownSec,
                    alarmMode = testAlarmMode,
                    ringtoneUri = AlarmScheduler.getDefaultRingtoneUri(requireContext()),
                    ringVolume = testRingVolume,
                    progressiveMaxIntensity = progMax,
                    progressiveMaxRingVolume = progVolMax
                )
                LockWindowManager.setTestAlarmConfig(config)

                // [id 流转] 测试闹钟使用专用 TEST_ALARM_ID=0,与真实闹钟 id 命名空间隔离
                // 真实闹钟重排后是 1..N,id=0 仅用于测试
                val intent = android.content.Intent(requireContext(), AlarmActivity::class.java).apply {
                    putExtra(AlarmScheduler.EXTRA_ALARM_ID, AlarmScheduler.TEST_ALARM_ID)
                }
                startActivity(intent)
            }
            .setNegativeButton("取消", null)
            .show()
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
        val index = alarms.indexOfFirst { it.id == entry.id }
        if (index >= 0) {
            alarms[index] = entry
            persistAndRefresh()
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
            .setMessage("同步会把这个闹钟加入到系统闹钟，防止 IronWill 可能出现权限或者其他的问题影响你的正事。")
            .setPositiveButton("知道了", null)
            .show()

    }

    private fun addSystemAlarm(alarm: AlarmScheduler.AlarmEntry) {
        val ctx = requireContext()
        val baseIntent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
            putExtra(AlarmClock.EXTRA_HOUR, alarm.hour)
            putExtra(AlarmClock.EXTRA_MINUTES, alarm.minute)
            putExtra(AlarmClock.EXTRA_DAYS, daysToSystemFormat(alarm.days))
            putExtra(AlarmClock.EXTRA_MESSAGE, "IronWill 备份闹钟")
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