/**
 * 文件路径: app/src/main/java/com/ironwill\app\ui\AppSelectionActivity.kt
 * 文件说明: 黑名单应用选择页面。用户在此页面勾选需要在拦截期间屏蔽的应用。
 * 主要功能:
 * - 展示已安装的应用列表 (左侧列表)。
 * - 展示已选中的黑名单应用 (右侧列表)。
 * - 提供搜索功能过滤应用。
 * - 处理“适应期”与“正式期”不同的保存逻辑 (适应期立即生效，正式期次日生效)。
 */
package com.zhizhi.app.ui

import android.content.Context
import android.content.Intent
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.view.View
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.zhizhi.app.R
import com.zhizhi.app.data.AdverseAnalysis
import com.zhizhi.app.data.SegmentBlacklist
import com.zhizhi.app.manager.AlarmChallengeEmergencyWhitelist
import com.zhizhi.app.manager.ChangeLogManager
import com.zhizhi.app.manager.CreditManager
import com.zhizhi.app.manager.TimeRuleManager
import com.zhizhi.app.service.MonitorService

data class AppInfo(
    val name: String,
    val packageName: String,
    val icon: Drawable,
    var isSelected: Boolean
)

class AppSelectionActivity : AppCompatActivity() {
    private lateinit var tvModeHint: TextView
    private lateinit var listAllApps: ListView
    private lateinit var listSelectedApps: ListView
    private lateinit var btnSave: Button
    private lateinit var allAppsAdapter: AppListAdapter
    private lateinit var selectedAppsAdapter: AppListAdapter
    private val selectedApps = mutableSetOf<String>()
    private val originalSelection = HashSet<String>()
    private var allAppsList = listOf<AppInfo>()
    private var currentMode = "blacklist"
    private var isReadOnly = false
    private var followGlobal = false
    private var restoreRequested = false
    private var listMode = SegmentBlacklist.LIST_MODE_BLACKLIST
    private val PREF_LOCKOUT_DIALOG_LAST_SHOWN = "lockout_dialog_last_shown_blacklist"

    companion object {
        const val EXTRA_MODE = "EXTRA_MODE"
        const val MODE_BLACKLIST = "blacklist"
        const val MODE_RESULT = "result"
        const val MODE_SEGMENT_BLACKLIST = "segment_blacklist"
        const val MODE_ALARM_EMERGENCY_WHITELIST = "alarm_emergency_whitelist"

        internal fun initialSelectionFor(listApps: List<String>?, globalBlacklist: Set<String>): Set<String> =
            listApps?.toSet() ?: globalBlacklist
        internal fun selectAll(universe: Set<String>): Set<String> = universe.toSet()
        internal fun invertSelection(current: Set<String>, universe: Set<String>): Set<String> =
            universe.filterNot { current.contains(it) }.toSet()
        internal fun filterHardExempt(context: Context, packages: Set<String>): Set<String> =
            packages.filterNot { SegmentBlacklist.isHardExempt(context, it) }.toSet()
        internal fun resolveFinalFollowGlobal(
            enteredFollowGlobal: Boolean,
            restoreRequested: Boolean,
            selection: Set<String>,
            originalSelection: Set<String>
        ): Boolean = restoreRequested || (enteredFollowGlobal && selection == originalSelection)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.apply(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_app_selection)

        val rootView = findViewById<View>(R.id.root_app_selection)
        if (rootView != null) {
            val initialPaddingLeft = rootView.paddingLeft
            val initialPaddingTop = rootView.paddingTop
            val initialPaddingRight = rootView.paddingRight
            val initialPaddingBottom = rootView.paddingBottom
            androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(rootView) { v, insets ->
                val bars = insets.getInsets(
                    androidx.core.view.WindowInsetsCompat.Type.statusBars() or
                    androidx.core.view.WindowInsetsCompat.Type.displayCutout()
                )
                v.setPadding(initialPaddingLeft, initialPaddingTop + bars.top, initialPaddingRight, initialPaddingBottom)
                insets
            }
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { doFinish() }
        })

        currentMode = intent.getStringExtra("EXTRA_MODE") ?: "blacklist"
        val initialSelectionExtra = intent.getStringArrayListExtra("EXTRA_INITIAL_SELECTION")
        followGlobal = intent.getBooleanExtra("EXTRA_FOLLOW_GLOBAL", false)
        listMode = intent.getStringExtra("EXTRA_LIST_MODE") ?: CreditManager.getBlacklistMode(this)

        if (currentMode == "blacklist" && TimeRuleManager.isInSettingsLockoutPeriod(this)) {
            isReadOnly = true
            showLockoutDialogOncePerDay()
        }

        tvModeHint = findViewById(R.id.tv_mode_hint)
        tvModeHint.visibility = View.GONE
        listAllApps = findViewById(R.id.list_all_apps)
        listSelectedApps = findViewById(R.id.list_selected_apps)
        btnSave = findViewById(R.id.btn_save_apps)

        findViewById<android.widget.EditText>(R.id.et_search_apps).addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { filterApps(s.toString()) }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })

        if (currentMode == "blacklist") {
            val pendingSet = CreditManager.getPendingBlacklistApps(this)
            val savedBlocked = if (pendingSet != null && pendingSet.isNotEmpty()) pendingSet else CreditManager.getBlacklistApps(this)
            val display = SegmentBlacklist.toDisplaySelection(this, listMode, savedBlocked)
            selectedApps.addAll(display)
            originalSelection.addAll(display)
            btnSave.text = "保存配置"
            findViewById<TextView>(R.id.tv_title_main)?.text = "黑白名单"
            updatePendingHint()
            if (isReadOnly) applySettingsLockoutState()
        } else if (currentMode == "segment_blacklist") {
            val globalBlacklist = CreditManager.getBlacklistApps(this)
            val rawBlocked = initialSelectionFor(if (followGlobal) null else initialSelectionExtra?.toList(), globalBlacklist)
            val filteredBlocked = filterHardExempt(this, rawBlocked)
            val display = SegmentBlacklist.toDisplaySelection(this, listMode, filteredBlocked)
            selectedApps.addAll(display)
            originalSelection.addAll(display)
            btnSave.text = "保存名单"
            findViewById<TextView>(R.id.tv_title_main)?.text = "时段黑白名单"
            findViewById<androidx.cardview.widget.CardView>(R.id.card_pending_changes)?.visibility = View.GONE
            findViewById<TextView>(R.id.tv_lockout_hint)?.visibility = View.GONE
            findViewById<TextView>(R.id.tv_pending_hint_legacy)?.visibility = View.GONE
            findViewById<View>(R.id.ll_segment_tools)?.visibility = View.VISIBLE
            findViewById<Button>(R.id.btn_quick_import)?.visibility = View.GONE
        } else if (currentMode == MODE_ALARM_EMERGENCY_WHITELIST) {
            val savedPackages = AlarmChallengeEmergencyWhitelist.getPackages(this)
            selectedApps.addAll(savedPackages)
            originalSelection.addAll(savedPackages)
            btnSave.text = "保存应急白名单"
            findViewById<TextView>(R.id.tv_title_main)?.text = "闹钟应急白名单"
            tvModeHint.text = "闹钟响铃挑战期间允许进入的应急应用（最多选择 2 个）"
            tvModeHint.setTextColor(resources.getColor(android.R.color.darker_gray))
            findViewById<androidx.cardview.widget.CardView>(R.id.card_pending_changes)?.visibility = View.GONE
            findViewById<TextView>(R.id.tv_lockout_hint)?.visibility = View.GONE
            findViewById<TextView>(R.id.tv_pending_hint_legacy)?.visibility = View.GONE
            findViewById<View>(R.id.ll_segment_tools)?.visibility = View.GONE
            findViewById<Button>(R.id.btn_quick_import)?.visibility = View.GONE
            findViewById<androidx.appcompat.widget.SwitchCompat>(R.id.switch_list_mode)?.visibility = View.GONE
        } else {
            if (initialSelectionExtra != null) {
                selectedApps.addAll(initialSelectionExtra)
                originalSelection.addAll(initialSelectionExtra)
            }
            btnSave.text = "确认选择"
            findViewById<TextView>(R.id.tv_title_main)?.text = "选择应用"
            tvModeHint.text = "请勾选需要在应用时间窗中受限的应用"
            tvModeHint.setTextColor(resources.getColor(android.R.color.darker_gray))
            findViewById<androidx.cardview.widget.CardView>(R.id.card_pending_changes)?.visibility = View.GONE
        }

        loadInstalledApps()
        setupListModeSwitch()
        findViewById<Button>(R.id.btn_quick_import).setOnClickListener { showQuickImportDialog() }
        if (currentMode == "segment_blacklist") {
            findViewById<Button>(R.id.btn_select_all).setOnClickListener { onSelectAll() }
            findViewById<Button>(R.id.btn_invert).setOnClickListener { onInvertSelection() }
            findViewById<Button>(R.id.btn_restore_global).setOnClickListener { onRestoreGlobal() }
        }
        btnSave.setOnClickListener {
            if (currentMode == MODE_ALARM_EMERGENCY_WHITELIST) {
                AlarmChallengeEmergencyWhitelist.savePackages(this, selectedApps)
                Toast.makeText(this, "已保存应急白名单", Toast.LENGTH_SHORT).show()
                finish()
            } else if (currentMode == "blacklist") saveAppSelection() else returnResult()
        }
    }

    private fun returnResult() {
        val resultIntent = Intent()
        val blockedSet = SegmentBlacklist.toEffectiveBlockedSet(this, listMode, selectedApps.toSet())
        resultIntent.putStringArrayListExtra("EXTRA_RESULT_SELECTION", ArrayList(blockedSet))
        resultIntent.putExtra("EXTRA_LIST_MODE", listMode)
        resultIntent.putExtra("EXTRA_FOLLOW_GLOBAL", resolveFinalFollowGlobal(followGlobal, restoreRequested, selectedApps.toSet(), originalSelection))
        setResult(RESULT_OK, resultIntent)
        finish()
    }

    private fun onSelectAll() {
        selectedApps.clear()
        selectedApps.addAll(selectAll(allAppsList.map { it.packageName }.toSet()))
        syncSelectionState(); updateLists()
    }

    private fun onInvertSelection() {
        val universe = allAppsList.map { it.packageName }.toSet()
        val inverted = invertSelection(selectedApps.toSet(), universe)
        selectedApps.clear(); selectedApps.addAll(inverted)
        syncSelectionState(); updateLists()
    }

    private fun onRestoreGlobal() {
        AlertDialog.Builder(this)
            .setTitle("恢复为全局黑/白名单")
            .setMessage("确定恢复跟随全局黑/白名单吗？本时段的独立名单将被清除。")
            .setPositiveButton("恢复") { _, _ -> followGlobal = true; restoreRequested = true; selectedApps.clear(); returnResult() }
            .setNegativeButton("取消", null).show()
    }

    private fun setupListModeSwitch() {
        val switch = findViewById<androidx.appcompat.widget.SwitchCompat>(R.id.switch_list_mode)
        if (currentMode != "blacklist" && currentMode != "segment_blacklist") { switch.visibility = View.GONE; return }
        switch.isChecked = listMode == SegmentBlacklist.LIST_MODE_WHITELIST
        switch.setOnCheckedChangeListener { _, isChecked ->
            val newMode = if (isChecked) SegmentBlacklist.LIST_MODE_WHITELIST else SegmentBlacklist.LIST_MODE_BLACKLIST
            if (newMode == listMode) return@setOnCheckedChangeListener
            val currentBlocked = SegmentBlacklist.toEffectiveBlockedSet(this, listMode, selectedApps.toSet())
            listMode = newMode
            selectedApps.clear(); selectedApps.addAll(SegmentBlacklist.toDisplaySelection(this, newMode, currentBlocked))
            syncSelectionState(); updateLists(); refreshModeLabels()
        }
        refreshModeLabels()
    }

    private fun refreshModeLabels() {
        val isWhite = listMode == SegmentBlacklist.LIST_MODE_WHITELIST
        findViewById<TextView>(R.id.tv_selected_title)?.text = if (isWhite) "白名单" else "黑名单"
        findViewById<androidx.appcompat.widget.SwitchCompat>(R.id.switch_list_mode).text =
            if (isWhite) "白名单模式（勾选=放行）" else "黑名单模式（勾选=拦截）"
    }

    private fun showQuickImportDialog() { showUnifiedCategoryConfigDialog() }
    private fun syncSelectionState() { allAppsList.forEach { it.isSelected = selectedApps.contains(it.packageName) } }
    private fun isAppInstalled(packageName: String): Boolean = try { packageManager.getApplicationInfo(packageName, 0); true } catch (_: Exception) { false }

    private fun filterApps(query: String) {
        updateAllAppsList(if (query.isEmpty()) allAppsList else allAppsList.filter {
            it.name.contains(query, ignoreCase = true) || it.packageName.contains(query, ignoreCase = true)
        })
    }

    private fun updateAllAppsList(list: List<AppInfo>) {
        val pendingApps = CreditManager.getPendingBlacklistApps(this)
        allAppsAdapter = AppListAdapter(this, list, pendingApps, originalSelection, { appInfo, isChecked ->
            if (isChecked) {
                if (currentMode == MODE_ALARM_EMERGENCY_WHITELIST && selectedApps.size >= AlarmChallengeEmergencyWhitelist.MAX_APPS && !selectedApps.contains(appInfo.packageName)) {
                    Toast.makeText(this, "最多只能选择 2 个应急应用", Toast.LENGTH_SHORT).show()
                    appInfo.isSelected = false
                    allAppsList.find { it.packageName == appInfo.packageName }?.isSelected = false
                    (listAllApps.adapter as? BaseAdapter)?.notifyDataSetChanged()
                    return@AppListAdapter
                }
                selectedApps.add(appInfo.packageName)
            } else selectedApps.remove(appInfo.packageName)
            updateSelectedAppsList()
            appInfo.isSelected = isChecked
            allAppsList.find { it.packageName == appInfo.packageName }?.isSelected = isChecked
        }, isReadOnly)
        listAllApps.adapter = allAppsAdapter
    }

    private fun updateSelectedAppsList() {
        val pendingApps = CreditManager.getPendingBlacklistApps(this)
        val selectedAppsList = allAppsList.filter { selectedApps.contains(it.packageName) }
        if (currentMode == MODE_ALARM_EMERGENCY_WHITELIST) findViewById<TextView>(R.id.tv_selected_title)?.text = "应急应用 (${selectedApps.size}/2)"
        selectedAppsAdapter = AppListAdapter(this, selectedAppsList, pendingApps, originalSelection, { appInfo, isChecked ->
            if (!isChecked) {
                selectedApps.remove(appInfo.packageName)
                allAppsList.find { it.packageName == appInfo.packageName }?.isSelected = false
                updateSelectedAppsList()
                (listAllApps.adapter as? BaseAdapter)?.notifyDataSetChanged()
            }
        }, isReadOnly)
        listSelectedApps.adapter = selectedAppsAdapter
    }

    private fun saveAppSelection() {
        val blockedSet = SegmentBlacklist.toEffectiveBlockedSet(this, listMode, selectedApps.toSet())
        CreditManager.setBlacklistMode(this, listMode)
        val result = CreditManager.analyzeAndSaveBlacklistApps(this, blockedSet)
        AdverseAnalysis.showAnalysisDialog(this, result) { doFinish() }
    }

    private fun doFinish() {
        if (intent.getBooleanExtra("from_lock", false)) {
            startService(Intent(this, MonitorService::class.java).apply { action = MonitorService.ACTION_REFRESH_RULES })
        }
        finish()
    }

    private fun updatePendingHint() {
        tvModeHint.visibility = View.GONE
        val isAdaptation = CreditManager.isInAdaptationPeriod(this)
        val cardPending = findViewById<androidx.cardview.widget.CardView>(R.id.card_pending_changes)
        val tvPendingDetails = findViewById<TextView>(R.id.tv_pending_details)
        val tvLockout = findViewById<TextView>(R.id.tv_lockout_hint)
        val warningSoft = ThemeManager.getColorAttr(this, R.attr.zzWarningSoft)
        val warningColor = ThemeManager.getColorAttr(this, R.attr.zzWarning)
        val textColor = ThemeManager.getColorAttr(this, R.attr.zzText)
        val successSoft = ThemeManager.getColorAttr(this, R.attr.zzSuccessSoft)
        val successColor = ThemeManager.getColorAttr(this, R.attr.zzSuccess)

        if (isReadOnly && TimeRuleManager.isInSettingsLockoutPeriod(this)) {
            val refreshTime = TimeRuleManager.getRefreshTime(this)
            cardPending.visibility = View.VISIBLE
            cardPending.setCardBackgroundColor(warningSoft)
            tvLockout.visibility = View.VISIBLE
            tvLockout.setTextColor(warningColor)
            tvLockout.text = "设置已锁定（深夜保护期 00:00 ~ $refreshTime）\n当前仅供查看，无法修改配置。"
        } else tvLockout.visibility = View.GONE

        if (isAdaptation) {
            cardPending.visibility = View.VISIBLE
            cardPending.setCardBackgroundColor(successSoft)
            tvPendingDetails.text = "当前处于【适应期】，所有设置将立即生效。\n（适应期结束后，移除黑名单等放松操作将延迟到次日生效）"
            tvPendingDetails.setTextColor(successColor)
            findViewById<TextView>(R.id.tv_pending_hint_legacy).visibility = View.GONE
        } else {
            val changeLog = ChangeLogManager.getFormattedLogForCategory(this, "blacklist")
            if (changeLog.isNotEmpty()) {
                cardPending.visibility = View.VISIBLE
                cardPending.setCardBackgroundColor(warningSoft)
                tvPendingDetails.text = changeLog
                tvPendingDetails.setTextColor(textColor)
                findViewById<TextView>(R.id.tv_pending_hint_legacy).visibility = View.VISIBLE
            } else if (!(isReadOnly && TimeRuleManager.isInSettingsLockoutPeriod(this))) cardPending.visibility = View.GONE
            else tvPendingDetails.text = ""
        }
    }

    private fun showUnifiedCategoryConfigDialog() {
        val isBlacklistMode = currentMode == "blacklist"
        val savedCategories = com.zhizhi.app.data.BlacklistStore.getAutoBlacklistCategories(this)
        val isAutoEnabled = com.zhizhi.app.data.BlacklistStore.isAutoBlacklistEnabled(this)
        val dialogView = layoutInflater.inflate(R.layout.dialog_quick_import_config, null)
        val switchMaster = dialogView.findViewById<androidx.appcompat.widget.SwitchCompat>(R.id.switch_dialog_auto_master)
        val cbComm = dialogView.findViewById<CheckBox>(R.id.cb_dialog_comm)
        val cbVideo = dialogView.findViewById<CheckBox>(R.id.cb_dialog_video)
        val cbGame = dialogView.findViewById<CheckBox>(R.id.cb_dialog_game)
        val cbBrowser = dialogView.findViewById<CheckBox>(R.id.cb_dialog_browser)
        switchMaster?.isChecked = isAutoEnabled
        val masterCard = switchMaster?.parent?.parent as? View
        if (!isBlacklistMode && masterCard != null) masterCard.visibility = View.GONE

        fun isCategoryAllSelected(code: String): Boolean {
            val installedCategoryApps = CreditManager.getBlacklistCategory(code).filter { isAppInstalled(it) }
            return installedCategoryApps.isNotEmpty() && installedCategoryApps.all { selectedApps.contains(it) }
        }
        cbComm?.isChecked = savedCategories.contains("comm") || isCategoryAllSelected("comm")
        cbVideo?.isChecked = savedCategories.contains("video") || isCategoryAllSelected("video")
        cbGame?.isChecked = savedCategories.contains("game") || isCategoryAllSelected("game")
        cbBrowser?.isChecked = savedCategories.contains("browser") || isCategoryAllSelected("browser")

        AlertDialog.Builder(this)
            .setTitle(if (isBlacklistMode) "批量勾选与新应用自动拦截配置" else "快速批量应用设置")
            .setView(dialogView)
            .setPositiveButton("应用配置与变更") { _, _ ->
                if (isBlacklistMode && switchMaster != null) com.zhizhi.app.data.BlacklistStore.setAutoBlacklistEnabled(this, switchMaster.isChecked)
                val newCategories = mutableSetOf<String>()
                val categoryMap = mapOf("comm" to (cbComm?.isChecked == true), "video" to (cbVideo?.isChecked == true), "game" to (cbGame?.isChecked == true), "browser" to (cbBrowser?.isChecked == true))
                var addedCount = 0; var removedCount = 0
                categoryMap.forEach { (code, isChecked) ->
                    if (isChecked) newCategories.add(code)
                    CreditManager.getBlacklistCategory(code).filter { isAppInstalled(it) }.forEach { pkg ->
                        if (isChecked && selectedApps.add(pkg)) addedCount++
                        else if (!isChecked && selectedApps.remove(pkg)) removedCount++
                    }
                }
                if (isBlacklistMode) com.zhizhi.app.data.BlacklistStore.setAutoBlacklistCategories(this, newCategories)
                val msgList = mutableListOf<String>()
                if (addedCount > 0) msgList.add("批量导入 $addedCount 个应用")
                if (removedCount > 0) msgList.add("批量取消 $removedCount 个应用")
                Toast.makeText(this, if (msgList.isNotEmpty()) "已" + msgList.joinToString("，") else "自动加黑与分类配置已更新", Toast.LENGTH_SHORT).show()
                syncSelectionState(); updateLists()
            }.setNegativeButton("取消", null).show()
    }

    private fun applySettingsLockoutState() {
        if (!isReadOnly) return
        btnSave.isEnabled = false; btnSave.alpha = 0.4f
        findViewById<Button>(R.id.btn_quick_import)?.apply { isEnabled = false; alpha = 0.4f }
    }

    private fun showLockoutDialogOncePerDay() {
        if (!shouldShowLockoutDialog()) return
        val refreshTime = TimeRuleManager.getRefreshTime(this)
        AlertDialog.Builder(this).setTitle("设置已锁定")
            .setMessage("当前处于【深夜保护期】(00:00 ~ $refreshTime)。\n\n为防止通宵后冲动修改配置，此时段内不允许修改任何设置。\n\n请在 $refreshTime 后再来调整。")
            .setPositiveButton("我知道了", null).setCancelable(false).show()
        markLockoutDialogShown()
    }

    private fun shouldShowLockoutDialog(): Boolean {
        val prefs = getSharedPreferences(CreditManager.PREFS_NAME, Context.MODE_PRIVATE)
        val today = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault()).format(java.util.Date())
        return prefs.getString(PREF_LOCKOUT_DIALOG_LAST_SHOWN, null) != today
    }

    private fun markLockoutDialogShown() {
        val today = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault()).format(java.util.Date())
        getSharedPreferences(CreditManager.PREFS_NAME, Context.MODE_PRIVATE).edit().putString(PREF_LOCKOUT_DIALOG_LAST_SHOWN, today).apply()
    }

    private fun loadInstalledApps() {
        Thread {
            val pm = packageManager
            val packages = try { pm.getInstalledApplications(0) } catch (_: Exception) { emptyList() }
            val dangerousSet = if (currentMode == MODE_ALARM_EMERGENCY_WHITELIST) AlarmChallengeEmergencyWhitelist.getDangerousPackages(this) else emptySet()
            val appList = packages.filter {
                hasLaunchIntent(it.packageName) || (currentMode == MODE_ALARM_EMERGENCY_WHITELIST && selectedApps.contains(it.packageName))
            }.filter {
                if (currentMode == MODE_ALARM_EMERGENCY_WHITELIST) AlarmChallengeEmergencyWhitelist.isSelectable(this, it.packageName, dangerousSet)
                else !SegmentBlacklist.isHardExempt(this, it.packageName)
            }.map {
                AppInfo(
                    name = try { it.loadLabel(pm).toString() } catch (_: Exception) { it.packageName },
                    packageName = it.packageName,
                    icon = try { it.loadIcon(pm) } catch (_: Exception) { resources.getDrawable(android.R.drawable.sym_def_app_icon, null) },
                    isSelected = selectedApps.contains(it.packageName)
                )
            }.sortedBy { it.name }
            runOnUiThread { allAppsList = appList; updateLists() }
        }.start()
    }

    private fun hasLaunchIntent(packageName: String): Boolean = packageManager.getLaunchIntentForPackage(packageName) != null
    private fun updateLists() { filterApps(""); updateSelectedAppsList() }
}
