/**
 * =================================================================================
 * [Context Header]
 * * @file: GlobalParamsActivity.kt
 * * @role: UI
 * * @description: 全局参数配置页面入口。
 * =================================================================================
 */
package com.zhizhi.app.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.EditText
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.zhizhi.app.R
import com.zhizhi.app.data.AdverseAnalysis
import com.zhizhi.app.manager.BoundaryResult
import com.zhizhi.app.manager.ChangeLogManager
import com.zhizhi.app.manager.CreditManager
import com.zhizhi.app.manager.MinuteBoundary
import com.zhizhi.app.manager.ProbationManager
import com.zhizhi.app.manager.TimeRuleManager
import com.zhizhi.app.service.MonitorService

class GlobalParamsActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_FROM_LOCK_SCREEN = "extra_from_lock_screen"
        internal fun parseRefreshTimeToMinutes(time: String): Int? {
            if (time.isBlank()) return null
            val parts = time.split(":")
            if (parts.size != 2) return null
            val h = parts[0].toIntOrNull() ?: return null
            val m = parts[1].toIntOrNull() ?: return null
            if (h < 0 || h > 24 || m < 0 || m > 59) return null
            val total = h * 60 + m
            return if (total > 24 * 60) null else total
        }
    }

    private var fromLockScreen = false

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.apply(this)
        super.onCreate(savedInstanceState)
        fromLockScreen = intent?.getBooleanExtra(EXTRA_FROM_LOCK_SCREEN, false) == true

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
            window.statusBarColor = android.graphics.Color.TRANSPARENT
            window.decorView.systemUiVisibility = android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        }
        setContentView(R.layout.activity_global_params_new)

        val rootView = findViewById<android.view.View>(R.id.root_global_params)
        val initialPaddingLeft = rootView.paddingLeft
        val initialPaddingTop = rootView.paddingTop
        val initialPaddingRight = rootView.paddingRight
        val initialPaddingBottom = rootView.paddingBottom
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(rootView) { v, insets ->
            val bars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            v.setPadding(initialPaddingLeft, initialPaddingTop + bars.top, initialPaddingRight, initialPaddingBottom + bars.bottom)
            insets
        }

        setupUI(TimeRuleManager.isInSettingsLockoutPeriod(this))
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { doFinish() }
        })
    }

    override fun onStop() {
        super.onStop()
        if (fromLockScreen) LockWindowManager.exitSettingsPassThrough("GlobalParamsActivity stopped")
    }

    private fun setupUI(isLocked: Boolean = false) {
        val etRest = findViewById<EditText>(R.id.et_cool_down_rest)
        val etGap = findViewById<EditText>(R.id.et_cool_down_gap)
        val etBlacklist = findViewById<EditText>(R.id.et_cool_down_blacklist)
        val etBorrowPercent = findViewById<EditText>(R.id.et_borrow_percentage)
        val etTempRestrictedAccessMax = findViewById<EditText>(R.id.et_temp_restricted_access_max_seconds)
        val etRefreshTime = findViewById<EditText>(R.id.et_refresh_time)
        val etNotifyEntryFar = findViewById<EditText>(R.id.et_notify_entry_far)
        val etNotifyEntryNear = findViewById<EditText>(R.id.et_notify_entry_near)
        val etWeeklyPrivilegeCount = findViewById<EditText>(R.id.et_weekly_privilege_limit)
        val etWeeklyPrivilegeDuration = findViewById<EditText>(R.id.et_weekly_privilege_duration)
        val etPrivWindowStart = findViewById<EditText>(R.id.et_privilege_window_start)
        val etPrivWindowEnd = findViewById<EditText>(R.id.et_privilege_window_end)
        val layoutPrivWindowInputs = findViewById<android.widget.LinearLayout>(R.id.layout_privilege_window_inputs)

        val currentCoolDownRest = TimeRuleManager.getCoolDownRestSeconds(this)
        val currentCoolDownGap = TimeRuleManager.getCoolDownGapSeconds(this)
        val currentBorrow = TimeRuleManager.getBorrowPercentage(this)
        val currentTempRestrictedAccessMax = TimeRuleManager.getTemporaryRestrictedAccessMaxSeconds(this)
        val currentEntryFar = TimeRuleManager.getNotifyEntryFarMinutes(this)
        val currentEntryNear = TimeRuleManager.getNotifyEntryNearMinutes(this)
        val currentPrivilegeCount = CreditManager.getWeeklyPrivilegeLimit(this)
        val currentPrivilegeDuration = CreditManager.getWeeklyPrivilegeDurationLimit(this)
        val currentPrivWindowStart = TimeRuleManager.getPrivilegeWindowStart(this)
        val currentPrivWindowEnd = TimeRuleManager.getPrivilegeWindowEnd(this)
        val currentStrict = TimeRuleManager.getStrictPenaltyDurationSeconds(this)
        val currentRefreshTime = TimeRuleManager.getRefreshTime(this)
        val hasPending = TimeRuleManager.hasPendingRules(this)

        fun getPendingInt(key: String, fallback: Int): Int {
            val prefs = getSharedPreferences("iron_will_rules_pending", Context.MODE_PRIVATE)
            return if (hasPending && prefs.contains(key)) prefs.getInt(key, fallback) else fallback
        }

        val pendingCoolDownRest = getPendingInt("cool_down_rest_seconds", currentCoolDownRest)
        val pendingCoolDownGap = getPendingInt("cool_down_gap_seconds", currentCoolDownGap)
        val pendingBorrow = getPendingInt("borrow_percentage", currentBorrow)
        val pendingTempRestrictedAccessMax = getPendingInt("temp_restricted_access_max_seconds", currentTempRestrictedAccessMax)
        val pendingPrivilegeCount = if (CreditManager.hasPendingWeeklyPrivilegeLimit(this)) CreditManager.getPendingWeeklyPrivilegeLimit(this) else currentPrivilegeCount
        val pendingPrivilegeDuration = if (CreditManager.hasPendingWeeklyPrivilegeLimit(this)) CreditManager.getPendingWeeklyPrivilegeDurationLimit(this) else currentPrivilegeDuration
        val pendingPrivWindowStart = TimeRuleManager.getPendingPrivilegeWindowStart(this) ?: currentPrivWindowStart
        val pendingPrivWindowEnd = TimeRuleManager.getPendingPrivilegeWindowEnd(this) ?: currentPrivWindowEnd
        val pendingRefresh = if (hasPending) TimeRuleManager.getPendingRefreshTime(this) ?: currentRefreshTime else currentRefreshTime
        val pendingStrict = if (hasPending) TimeRuleManager.getPendingStrictPenaltyDurationSeconds(this) ?: currentStrict else currentStrict

        etRest.setText(pendingCoolDownRest.toString())
        etGap.setText(pendingCoolDownGap.toString())
        etBlacklist.setText(pendingStrict.toString())
        etBorrowPercent.setText(pendingBorrow.toString())
        etTempRestrictedAccessMax.setText(formatTemporaryAccessSecondsAsMinutes(pendingTempRestrictedAccessMax))
        etRefreshTime.setText(pendingRefresh)
        etNotifyEntryFar.setText(currentEntryFar.toString())
        etNotifyEntryNear.setText(currentEntryNear.toString())
        etWeeklyPrivilegeCount.setText(pendingPrivilegeCount.toString())
        etWeeklyPrivilegeDuration.setText(pendingPrivilegeDuration.toString())
        etPrivWindowStart.setText(pendingPrivWindowStart)
        etPrivWindowEnd.setText(pendingPrivWindowEnd)

        if (isLocked) {
            listOf(etRest, etGap, etBlacklist, etBorrowPercent, etTempRestrictedAccessMax, etRefreshTime,
                etNotifyEntryFar, etNotifyEntryNear, etWeeklyPrivilegeCount, etWeeklyPrivilegeDuration,
                etPrivWindowStart, etPrivWindowEnd).forEach { it.isEnabled = false }
        }

        val cardPending = findViewById<androidx.cardview.widget.CardView>(R.id.card_pending_changes)
        val tvPending = findViewById<android.widget.TextView>(R.id.tv_pending_changes)
        val tvLockout = findViewById<android.widget.TextView>(R.id.tv_lockout_hint)
        if (!isLocked) tvLockout.visibility = android.view.View.GONE
        val isAdaptation = TimeRuleManager.isInAdaptationPeriod(this)
        val finalText = ChangeLogManager.getFormattedLogForCategory(this, "global_params").trim()
        if (!isAdaptation && finalText.isNotEmpty()) {
            cardPending.visibility = android.view.View.VISIBLE
            tvPending.text = finalText
            cardPending.setOnClickListener {
                if (TimeRuleManager.hasPendingRules(this) || CreditManager.hasPendingApps(this)) showPendingRevokeDialog()
            }
        } else if (!isLocked) cardPending.visibility = android.view.View.GONE else tvPending.text = ""

        val switchSync = findViewById<androidx.appcompat.widget.SwitchCompat>(R.id.switch_refresh_sync)
        val tvSyncHint = findViewById<android.widget.TextView>(R.id.tv_refresh_sync_hint)
        val isSynced = TimeRuleManager.isRefreshTimeSynced(this)
        switchSync.isChecked = isSynced
        tvSyncHint.visibility = if (isSynced) android.view.View.VISIBLE else android.view.View.GONE
        etRefreshTime.visibility = if (isSynced) android.view.View.GONE else android.view.View.VISIBLE

        fun syncWithFirstSegment() {
            val segments = TimeRuleManager.getPendingTimeSegments(this) ?: TimeRuleManager.getTimeSegments(this)
            val firstSeg = TimeRuleManager.getLogicalFirstSegment(this, segments)
            if (firstSeg != null) etRefreshTime.setText(String.format("%02d:%02d", firstSeg.startHour, firstSeg.startMinute))
            else {
                Toast.makeText(this, "未找到有效时间段，无法同步", Toast.LENGTH_SHORT).show()
                switchSync.isChecked = false
                etRefreshTime.isEnabled = true
                tvSyncHint.visibility = android.view.View.GONE
            }
        }
        if (isSynced) syncWithFirstSegment()
        switchSync.setOnCheckedChangeListener { _, checked ->
            etRefreshTime.visibility = if (checked) android.view.View.GONE else android.view.View.VISIBLE
            tvSyncHint.visibility = if (checked) android.view.View.VISIBLE else android.view.View.GONE
            if (checked) syncWithFirstSegment()
        }
        layoutPrivWindowInputs.visibility = android.view.View.VISIBLE
        TimeRuleManager.setPrivilegeWindowSynced(this, false)

        val btnSave = findViewById<android.view.View>(R.id.btn_save)
        if (isLocked) { switchSync.isEnabled = false; btnSave.isEnabled = false; btnSave.alpha = 0.5f }

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
                Toast.makeText(this, "临时使用受限应用时长上限必须在 0.1~3.0 分钟，最多 1 位小数", Toast.LENGTH_SHORT).show(); return@setOnClickListener
            }
            if (newWeeklyCount > 3) { Toast.makeText(this, "每周特权次数不能超过 3 次", Toast.LENGTH_SHORT).show(); return@setOnClickListener }
            if (newWeeklyDuration > 600) { Toast.makeText(this, "每周特权时长不能超过 10 小时 (600 分钟)", Toast.LENGTH_SHORT).show(); return@setOnClickListener }

            val newNotifyFar = etNotifyEntryFar.text.toString().toIntOrNull() ?: currentEntryFar
            val newNotifyNear = etNotifyEntryNear.text.toString().toIntOrNull() ?: currentEntryNear
            var newRefreshTime = etRefreshTime.text.toString().trim()
            if (switchSync.isChecked) {
                val firstSeg = TimeRuleManager.getLogicalFirstSegment(this, TimeRuleManager.getPendingTimeSegments(this) ?: TimeRuleManager.getTimeSegments(this))
                if (firstSeg != null && firstSeg.startHour >= 7) newRefreshTime = String.format("%02d:%02d", firstSeg.startHour, firstSeg.startMinute)
            }
            if (newRefreshTime.contains(":")) {
                val parts = newRefreshTime.split(":")
                if (parts.size == 2) newRefreshTime = String.format("%02d:%02d", parts[0].toIntOrNull() ?: 0, parts[1].toIntOrNull() ?: 0)
            }
            if (newRefreshTime < "07:00" || newRefreshTime > "23:59") {
                Toast.makeText(this, "生效时间必须在 07:00 - 23:59 之间", Toast.LENGTH_SHORT).show(); return@setOnClickListener
            }
            val effectiveSegments = TimeRuleManager.getPendingTimeSegments(this) ?: TimeRuleManager.getTimeSegments(this)
            val candidateMinutes = parseRefreshTimeToMinutes(newRefreshTime)
            if (candidateMinutes == null) { Toast.makeText(this, "生效时间格式无效", Toast.LENGTH_SHORT).show(); return@setOnClickListener }
            val preflight = MinuteBoundary.isRefreshLegal(candidateMinutes, effectiveSegments)
            if (preflight is BoundaryResult.Rejected) {
                android.util.Log.w("GlobalParamsActivity", "Refresh preflight REJECTED candidate=$newRefreshTime (${candidateMinutes}m) reason=${preflight.reason}")
                AlertDialog.Builder(this).setTitle("生效时间配置不当").setMessage("refresh time 必须配置在空档期或 DND 时段边界").setPositiveButton("我知道了", null).show()
                return@setOnClickListener
            }

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
            TimeRuleManager.setRefreshTimeSynced(this, switchSync.isChecked)
            TimeRuleManager.setPrivilegeWindowSynced(this, false)
            val result = TimeRuleManager.analyzeAndSaveGlobalParams(
                this, newRest, newGap, newLockDuration, newBorrow, newNotifyFar, newNotifyNear,
                newWeeklyCount, newWeeklyDuration, newRefreshTime, newTempRestrictedAccessMax,
                newPrivWindowStart, newPrivWindowEnd
            )
            AdverseAnalysis.showAnalysisDialog(this, result) { finish() }
        }
    }

    override fun onResume() {
        super.onResume()
        val promoted = ProbationManager.checkAndPromote(this)
        if (promoted.isNotEmpty()) {
            Toast.makeText(this, "已晋升 ${promoted.size} 个配置项为新基准", Toast.LENGTH_SHORT).show(); recreate(); return
        }
        val switchSync = findViewById<androidx.appcompat.widget.SwitchCompat>(R.id.switch_refresh_sync)
        if (switchSync != null && switchSync.isChecked) {
            TimeRuleManager.getLogicalFirstSegment(this, TimeRuleManager.getTimeSegments(this))?.let {
                findViewById<EditText>(R.id.et_refresh_time).setText(String.format("%02d:%02d", it.startHour, it.startMinute))
            }
        }
        setupUI(TimeRuleManager.isInSettingsLockoutPeriod(this))
    }

    private fun doFinish() {
        if (intent.getBooleanExtra("from_lock", false)) startService(Intent(this, MonitorService::class.java).apply { action = MonitorService.ACTION_REFRESH_RULES })
        finish()
    }

    private fun showProbationUndoDialog(items: List<ProbationManager.ProbationItem>) {
        val translationMap = mapOf(
            "cool_down_rest_seconds" to "休息冷却", "cool_down_gap_seconds" to "空档冷却",
            "borrow_percentage" to "借用比例", "temp_restricted_access_max_seconds" to "临时使用受限应用时长上限",
            "max_exits_per_segment" to "退出次数", "strict_penalty_duration" to "严厉惩罚",
            "notify_advance_minutes" to "远期预警", "notify_entry_near_minutes" to "近期预警"
        )
        val options = items.map { "${translationMap[it.key] ?: it.key}: 恢复到 ${it.originalValue}" }.toTypedArray()
        AlertDialog.Builder(this).setTitle("撤销有利修改 (恢复基准)").setItems(options) { _, which ->
            val item = items[which]
            if (ProbationManager.undoChange(this, item.key)) { Toast.makeText(this, "已撤销修改，恢复为: ${item.originalValue}", Toast.LENGTH_SHORT).show(); recreate() }
            else Toast.makeText(this, "撤销失败", Toast.LENGTH_SHORT).show()
        }.setPositiveButton("关闭", null).show()
    }

    private fun showPendingRevokeDialog() {
        AlertDialog.Builder(this).setTitle("撤销待生效的不利修改")
            .setMessage("确定要撤销所有尚未生效的不利修改吗？\n撤销后，这些修改将不会在明日生效。")
            .setPositiveButton("确认撤销") { _, _ ->
                TimeRuleManager.revokePendingChanges(this); CreditManager.revokePendingChanges(this)
                Toast.makeText(this, "已撤销所有待生效修改", Toast.LENGTH_SHORT).show(); recreate()
            }.setNegativeButton("取消", null).show()
    }
}
