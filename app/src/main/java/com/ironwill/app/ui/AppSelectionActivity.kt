/**
 * 文件路径: app/src/main/java/com/ironwill\app\ui\AppSelectionActivity.kt
 * 文件说明: 黑名单应用选择页面。用户在此页面勾选需要在拦截期间屏蔽的应用。
 * 主要功能:
 * - 展示已安装的应用列表 (左侧列表)。
 * - 展示已选中的黑名单应用 (右侧列表)。
 * - 提供搜索功能过滤应用。
 * - 处理“适应期”与“正式期”不同的保存逻辑 (适应期立即生效，正式期次日生效)。
 *
 * 主要函数:
 * - onCreate: 初始化界面，设置监听器，加载已选数据。
 * - loadInstalledApps: 异步加载手机中所有已安装的应用，并根据是否首次设置自动勾选常用软件。
 * - updateAllAppsList: 刷新左侧“全部应用”列表。
 * - updateSelectedAppsList: 刷新右侧“黑名单”列表。
 * - filterApps: 根据搜索关键词过滤应用列表。
 * - saveAppSelection: 保存用户的选择。
 *   - 若在适应期，调用 CreditManager.saveBlacklistApps 立即生效。
 *   - 若在正式期，调用 CreditManager.stagePendingBlacklistApps 暂存，次日生效。
 * - updatePendingHint: 更新界面提示，告知用户当前是适应期还是有待生效的修改。
 */
/**
 * =================================================================================
 * [Context Header]
 * * @file: AppSelectionActivity.kt
 * * @role: UI
 * * @description: 应用选择界面入口。
 * * @responsibilities:
 * 1. 渲染应用列表并支持筛选。
 * 2. 返回用户选择的应用集合。
 * * @context:
 * - 上游: RulesFragment / AppWindowFragment
 * - 下游: AppSelectionAdapter, CreditManager
 * * @constraints (必读):
 * 1. 禁止承载复杂业务逻辑。
 * 2. 保持主线程 UI 流畅。
 * 3. 需处理权限缺失与空列表。
 * =================================================================================
 */
package com.ironwill.app.ui

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.ironwill.app.R
import com.ironwill.app.data.AdverseAnalysis
import com.ironwill.app.data.SegmentBlacklist
import com.ironwill.app.manager.AlarmChallengeEmergencyWhitelist
import com.ironwill.app.manager.ChangeLogManager
import com.ironwill.app.manager.CreditManager
import com.ironwill.app.manager.TimeRuleManager
import com.ironwill.app.service.MonitorService




// 数据模型 (保持顶级类，避免重复定义)
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
    private val originalSelection = HashSet<String>() // 🚀 [Fix] Track Original State
    private var allAppsList = listOf<AppInfo>()

    private var currentMode = "blacklist" // "blacklist" | "result" | "segment_blacklist" | "alarm_emergency_whitelist"
    private var isReadOnly = false
    private var followGlobal = false // TASK-05: segment_blacklist 模式是否恢复跟随全局黑/白名单
    private var restoreRequested = false // [FIX TASK-05-review] 用户显式点击"恢复为全局黑/白名单"
    private var listMode = SegmentBlacklist.LIST_MODE_BLACKLIST // 黑/白名单模式

    private val PREF_LOCKOUT_DIALOG_LAST_SHOWN = "lockout_dialog_last_shown_blacklist"

    companion object {
        const val EXTRA_MODE = "EXTRA_MODE"
        const val MODE_BLACKLIST = "blacklist"
        const val MODE_RESULT = "result"
        const val MODE_SEGMENT_BLACKLIST = "segment_blacklist"
        const val MODE_ALARM_EMERGENCY_WHITELIST = "alarm_emergency_whitelist"

        /**
         * TASK-05 (segment-blacklist) — 名单页纯逻辑集合函数（Set<String>/List<String> 级，
         * 不依赖 AppInfo/Drawable，便于单测）。
         */

        /** 初始勾选来源：null=跟随全局黑/白名单（返回 globalBlacklist）；非空=名单本身。 */
        internal fun initialSelectionFor(listApps: List<String>?, globalBlacklist: Set<String>): Set<String> =
            listApps?.toSet() ?: globalBlacklist

        /** 全选：返回全集（硬剔除项已先在 loadInstalledApps 剔除，故不会进入全集）。 */
        internal fun selectAll(universe: Set<String>): Set<String> = universe.toSet()

        /** 反选：返回全集中不在当前勾选里的集合（勾上取消、没勾勾上）。 */
        internal fun invertSelection(current: Set<String>, universe: Set<String>): Set<String> =
            universe.filterNot { current.contains(it) }.toSet()

        /** 硬剔除过滤（需 Context，Robolectric 测）：剔除桌面/输入法/电话/短信/设置/IronWill。 */
        internal fun filterHardExempt(context: Context, packages: Set<String>): Set<String> =
            packages.filterNot { SegmentBlacklist.isHardExempt(context, it) }.toSet()

        /**
         * [FIX TASK-05-review] 保存时决定最终是否跟随全局黑名单：
         *   - 用户显式点了"恢复为全局黑名单" → 强制跟随（true）；
         *   - 否则仅在"进入时即跟随 且 勾选未发生任何变化"时才保持跟随；
         *   - 任何勾选变化（即使从跟随态进入）→ 脱钩（false），确保"修改保存即独立管理"。
         */
        internal fun resolveFinalFollowGlobal(
            enteredFollowGlobal: Boolean,
            restoreRequested: Boolean,
            selection: Set<String>,
            originalSelection: Set<String>
        ): Boolean = restoreRequested || (enteredFollowGlobal && selection == originalSelection)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_app_selection)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                doFinish()
            }
        })

        // 🚀 [Intent] Read Mode
        currentMode = intent.getStringExtra("EXTRA_MODE") ?: "blacklist"
        val initialSelectionExtra = intent.getStringArrayListExtra("EXTRA_INITIAL_SELECTION")
        followGlobal = intent.getBooleanExtra("EXTRA_FOLLOW_GLOBAL", false)
        // 黑/白名单模式：调用方显式传入则用之，否则读全局模式（继承态）。
        listMode = intent.getStringExtra("EXTRA_LIST_MODE") ?: CreditManager.getBlacklistMode(this)

        // 🚀 [NEW] Settings Lockout Period Check (Only for Blacklist Mode)
        // If in Result Mode (e.g. App Window), we might allow editing? App Window has its own checks.
        // Assuming Activity Check logic covers App Window too.
        if (currentMode == "blacklist" && TimeRuleManager.isInSettingsLockoutPeriod(this)) {
            isReadOnly = true
            showLockoutDialogOncePerDay()
        }

        tvModeHint = findViewById(R.id.tv_mode_hint)

        listAllApps = findViewById(R.id.list_all_apps)
        listSelectedApps = findViewById(R.id.list_selected_apps)
        btnSave = findViewById(R.id.btn_save_apps)
        
        // 🔍 搜索框逻辑
        val etSearch = findViewById<android.widget.EditText>(R.id.et_search_apps)
        etSearch.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                filterApps(s.toString())
            }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })

        // 加载现有配置
        if (currentMode == "blacklist") {
            val pendingSet = CreditManager.getPendingBlacklistApps(this)
            val savedBlocked = if (pendingSet != null && pendingSet.isNotEmpty()) {
                pendingSet
            } else {
                CreditManager.getBlacklistApps(this)
            }
            // 名单页展示"勾选集合"：白名单模式取补集还原为允许列表。
            val display = SegmentBlacklist.toDisplaySelection(this, listMode, savedBlocked)
            selectedApps.addAll(display)
            originalSelection.addAll(display) // 🚀 Track
            
            btnSave.text = "💾 保存配置"
            
            // 🚀 Blacklist Mode UI
            findViewById<TextView>(R.id.tv_title_main)?.text = "🛑 屏蔽应用"
            updatePendingHint()
            if (isReadOnly) {
                applySettingsLockoutState()
            }
        } else if (currentMode == "segment_blacklist") {
            // TASK-05: 本时段黑名单模式。初始勾选来源：followGlobal → 全局黑/白名单；否则独立名单。
            val globalBlacklist = CreditManager.getBlacklistApps(this)
            val rawBlocked = initialSelectionFor(
                if (followGlobal) null else initialSelectionExtra?.toList(),
                globalBlacklist
            )
            val filteredBlocked = filterHardExempt(this, rawBlocked)
            // 名单页展示"勾选集合"：白名单模式取补集还原为允许列表。
            val display = SegmentBlacklist.toDisplaySelection(this, listMode, filteredBlocked)
            selectedApps.addAll(display)
            originalSelection.addAll(display) // 🚀 Track

            btnSave.text = "💾 保存名单"

            findViewById<TextView>(R.id.tv_title_main)?.text = "🛡 本时段黑/白名单"
            if (followGlobal) {
                tvModeHint.text = "当前跟随全局黑/白名单，修改保存后即独立管理"
            } else {
                tvModeHint.text = "勾选本时段要锁的应用；可先勾想用的再点反选；桌面/输入法/电话/短信等已自动剔除"
            }
            tvModeHint.setTextColor(resources.getColor(android.R.color.darker_gray))

            // 本页不展示待生效（由 TimeConfigFragment 的 analyze 流程负责），也不做 lockout。
            findViewById<androidx.cardview.widget.CardView>(R.id.card_pending_changes)?.visibility = View.GONE
            findViewById<TextView>(R.id.tv_lockout_hint)?.visibility = View.GONE
            findViewById<TextView>(R.id.tv_pending_hint_legacy)?.visibility = View.GONE

            // 显示全选/反选/恢复工具条，隐藏黑名单专用的快速导入。
            findViewById<View>(R.id.ll_segment_tools)?.visibility = View.VISIBLE
            findViewById<Button>(R.id.btn_quick_import)?.visibility = View.GONE
        } else if (currentMode == MODE_ALARM_EMERGENCY_WHITELIST) {
            val savedPackages = AlarmChallengeEmergencyWhitelist.getPackages(this)
            selectedApps.addAll(savedPackages)
            originalSelection.addAll(savedPackages)

            btnSave.text = "💾 保存应急白名单"
            findViewById<TextView>(R.id.tv_title_main)?.text = "🛡️ 闹钟应急白名单"
            tvModeHint.text = "闹钟响铃挑战期间允许进入的应急应用（最多选择 2 个）"
            tvModeHint.setTextColor(resources.getColor(android.R.color.darker_gray))

            findViewById<androidx.cardview.widget.CardView>(R.id.card_pending_changes)?.visibility = View.GONE
            findViewById<TextView>(R.id.tv_lockout_hint)?.visibility = View.GONE
            findViewById<TextView>(R.id.tv_pending_hint_legacy)?.visibility = View.GONE
            findViewById<View>(R.id.ll_segment_tools)?.visibility = View.GONE
            findViewById<Button>(R.id.btn_quick_import)?.visibility = View.GONE
            findViewById<androidx.appcompat.widget.SwitchCompat>(R.id.switch_list_mode)?.visibility = View.GONE
        } else {
            // Result Mode
            if (initialSelectionExtra != null) {
                selectedApps.addAll(initialSelectionExtra)
                originalSelection.addAll(initialSelectionExtra) // 🚀 Track
            }
            btnSave.text = "✅ 确认选择"
            
            // 🚀 Generic UI
            findViewById<TextView>(R.id.tv_title_main)?.text = "✅ 选择应用"
            tvModeHint.text = "请勾选需要在应用时间窗中受限的应用"
            tvModeHint.setTextColor(resources.getColor(android.R.color.darker_gray))
            findViewById<androidx.cardview.widget.CardView>(R.id.card_pending_changes)?.visibility = View.GONE
        }

        loadInstalledApps()

        setupListModeSwitch()

        // ...
        // Quick Import Button
        findViewById<Button>(R.id.btn_quick_import).setOnClickListener {
            showQuickImportDialog()
        }

        // TASK-05: segment_blacklist 模式工具按钮（全选/反选/恢复为全局黑名单）
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
            } else if (currentMode == "blacklist") {
                saveAppSelection()
            } else {
                returnResult()
            }
        }
    }

    private fun returnResult() {
        val resultIntent = Intent()
        // 名单页返回「有效黑名单集合」+ 模式（白名单模式已在此转成补集）。
        val blockedSet = SegmentBlacklist.toEffectiveBlockedSet(this, listMode, selectedApps.toSet())
        resultIntent.putStringArrayListExtra("EXTRA_RESULT_SELECTION", ArrayList(blockedSet))
        resultIntent.putExtra("EXTRA_LIST_MODE", listMode)
        // [FIX TASK-05-review] 跟随态进入后只要勾选变化过就脱钩；否则保持原状态
        resultIntent.putExtra(
            "EXTRA_FOLLOW_GLOBAL",
            resolveFinalFollowGlobal(followGlobal, restoreRequested, selectedApps.toSet(), originalSelection)
        )
        setResult(RESULT_OK, resultIntent)
        finish()
    }

    // ── TASK-05: segment_blacklist 模式的全选/反选/恢复 ──

    private fun onSelectAll() {
        val universe = allAppsList.map { it.packageName }.toSet()
        selectedApps.clear()
        selectedApps.addAll(selectAll(universe))
        syncSelectionState()
        updateLists()
    }

    private fun onInvertSelection() {
        val universe = allAppsList.map { it.packageName }.toSet()
        val inverted = invertSelection(selectedApps.toSet(), universe)
        selectedApps.clear()
        selectedApps.addAll(inverted)
        syncSelectionState()
        updateLists()
    }

    private fun onRestoreGlobal() {
        AlertDialog.Builder(this)
            .setTitle("♻️ 恢复为全局黑/白名单")
            .setMessage("确定恢复跟随全局黑/白名单吗？本时段的独立名单将被清除。")
            .setPositiveButton("恢复") { _, _ ->
                followGlobal = true
                restoreRequested = true
                selectedApps.clear()
                returnResult()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ── 黑/白名单模式切换 ──
    private fun setupListModeSwitch() {
        val switch = findViewById<androidx.appcompat.widget.SwitchCompat>(R.id.switch_list_mode)
        // 仅 blacklist（全局）与 segment_blacklist（本时段）两种模式支持黑/白切换；result 模式隐藏。
        if (currentMode != "blacklist" && currentMode != "segment_blacklist") {
            switch.visibility = View.GONE
            return
        }
        switch.isChecked = listMode == SegmentBlacklist.LIST_MODE_WHITELIST
        switch.setOnCheckedChangeListener { _, isChecked ->
            val newMode = if (isChecked) SegmentBlacklist.LIST_MODE_WHITELIST else SegmentBlacklist.LIST_MODE_BLACKLIST
            if (newMode == listMode) return@setOnCheckedChangeListener
            // 切换模式：保持"有效黑名单"不变，仅重新推导展示勾选集合。
            val currentBlocked = SegmentBlacklist.toEffectiveBlockedSet(this, listMode, selectedApps.toSet())
            listMode = newMode
            val newDisplay = SegmentBlacklist.toDisplaySelection(this, newMode, currentBlocked)
            selectedApps.clear()
            selectedApps.addAll(newDisplay)
            syncSelectionState()
            updateLists()
            refreshModeLabels()
        }
        refreshModeLabels()
    }

    private fun refreshModeLabels() {
        val isWhite = listMode == SegmentBlacklist.LIST_MODE_WHITELIST
        findViewById<TextView>(R.id.tv_selected_title)?.text = if (isWhite) "✅ 白名单" else "🚫 黑名单"
        val switch = findViewById<androidx.appcompat.widget.SwitchCompat>(R.id.switch_list_mode)
        switch.text = if (isWhite) "⚪ 白名单模式 (勾选=放行)" else "⚫ 黑名单模式 (勾选=拦截)"
    }

    // 🚀 [NEW] Quick Import Dialog
    private fun showQuickImportDialog() {
        val isBlacklistMode = currentMode == "blacklist"
        
        val categoriesList = mutableListOf<String>()
        val codesList = mutableListOf<String>()
        
        // 1. Communication - Only for Blacklist (Emergency Control)
        if (isBlacklistMode) {
            categoriesList.add("💬 社交软件 (国内外)")
            codesList.add("comm")
        }
        
        // 2. Pure Entertainment - Always available
        categoriesList.add("🎬 短视频 (抖音/快手/B站/小红书)")
        codesList.add("video")
        categoriesList.add("🎮 大型手游 (王者/吃鸡/原神)")
        codesList.add("game")

        categoriesList.add("🌐 浏览器 (Chrome/Google/Firefox/Edge...)")
        codesList.add("browser")

        val categories = categoriesList.toTypedArray()
        val codes = codesList.toTypedArray()
        val checkedItems = BooleanArray(categories.size) { false }

        AlertDialog.Builder(this)
            .setTitle(if (isBlacklistMode) "⚡ 快速导入黑名单" else "⚡ 快速导入受控应用 (时间窗)")
            .setMultiChoiceItems(categories, checkedItems) { _, which, isChecked ->
                checkedItems[which] = isChecked
            }
            .setPositiveButton("导入选中") { _, _ ->
                var addedCount = 0
                for (i in checkedItems.indices) {
                    if (checkedItems[i]) {
                        val categoryApps = CreditManager.getBlacklistCategory(codes[i])
                        categoryApps.forEach { pkg ->
                            // Add only if installed (check specific method or reliable package manager)
                            if (isAppInstalled(pkg) && !selectedApps.contains(pkg)) {
                                selectedApps.add(pkg)
                                addedCount++
                            }
                        }
                    }
                }
                if (addedCount > 0) {
                    Toast.makeText(this, "已导入 $addedCount 个应用", Toast.LENGTH_SHORT).show()
                    // 🚀 [Fix] Hollow Checkbox Bug: Sync allAppsList state with selectedApps
                    syncSelectionState()
                    updateLists() // Refresh UI
                } else {
                    Toast.makeText(this, "未找到相关应用或已全部在列表中", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }
    
    // 🚀 [Fix] Helper to sync state
    private fun syncSelectionState() {
        allAppsList.forEach { appInfo ->
            appInfo.isSelected = selectedApps.contains(appInfo.packageName)
        }
    }
    
    private fun isAppInstalled(packageName: String): Boolean {
        return try {
            packageManager.getApplicationInfo(packageName, 0)
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun filterApps(query: String) {
        val filteredList = if (query.isEmpty()) {
            allAppsList
        } else {
            allAppsList.filter { 
                it.name.contains(query, ignoreCase = true) || 
                it.packageName.contains(query, ignoreCase = true) 
            }
        }
        updateAllAppsList(filteredList)
    }

    private fun updateAllAppsList(list: List<AppInfo>) {
        val pendingApps = CreditManager.getPendingBlacklistApps(this)
        // 🚀 [Refactor] Pass originalSelection
        allAppsAdapter = AppListAdapter(this, list, pendingApps, originalSelection, { appInfo, isChecked ->
            if (isChecked) {
                if (currentMode == MODE_ALARM_EMERGENCY_WHITELIST &&
                    selectedApps.size >= AlarmChallengeEmergencyWhitelist.MAX_APPS &&
                    !selectedApps.contains(appInfo.packageName)
                ) {
                    Toast.makeText(this, "最多只能选择 2 个应急应用", Toast.LENGTH_SHORT).show()
                    appInfo.isSelected = false
                    allAppsList.find { it.packageName == appInfo.packageName }?.isSelected = false
                    (listAllApps.adapter as? BaseAdapter)?.notifyDataSetChanged()
                    return@AppListAdapter
                }
                selectedApps.add(appInfo.packageName)
            } else {
                selectedApps.remove(appInfo.packageName)
            }
            // 注意：勾选/取消勾选左侧列表时，需要同时刷新右侧列表
            updateSelectedAppsList()
            // 如果是在搜索状态下，不需要刷新左侧列表（否则会重置搜索结果），只需要保持选中状态
            // 但由于 adapter 是重新创建的，所以状态会由 data class 中的 isSelected 决定
            // 我们需要确保 data class 的状态是同步的
            appInfo.isSelected = isChecked
            // 同步回主数据源
            allAppsList.find { it.packageName == appInfo.packageName }?.isSelected = isChecked
        }, isReadOnly)
        listAllApps.adapter = allAppsAdapter
    }
    
    private fun updateSelectedAppsList() {
        val pendingApps = CreditManager.getPendingBlacklistApps(this)
        val selectedAppsList = allAppsList.filter { selectedApps.contains(it.packageName) }
        if (currentMode == MODE_ALARM_EMERGENCY_WHITELIST) {
            findViewById<TextView>(R.id.tv_selected_title)?.text = "🛡️ 应急应用 (${selectedApps.size}/2)"
        }
        // 🚀 [Refactor] Pass originalSelection
        selectedAppsAdapter = AppListAdapter(this, selectedAppsList, pendingApps, originalSelection, { appInfo, isChecked ->
            if (!isChecked) {
                selectedApps.remove(appInfo.packageName)
                // 取消选中后，需要同步左侧列表的状态
                allAppsList.find { it.packageName == appInfo.packageName }?.isSelected = false
                // 刷新右侧（自己）
                updateSelectedAppsList()
                // 刷新左侧（即时反馈）
                (listAllApps.adapter as? android.widget.BaseAdapter)?.notifyDataSetChanged()
            }
        }, isReadOnly)
        listSelectedApps.adapter = selectedAppsAdapter
    }

    private fun saveAppSelection() {
        // 保存前把勾选集合转成有效黑名单集合，并落库模式（白名单模式取补集）。
        val blockedSet = SegmentBlacklist.toEffectiveBlockedSet(this, listMode, selectedApps.toSet())
        CreditManager.setBlacklistMode(this, listMode)
        val result = CreditManager.analyzeAndSaveBlacklistApps(this, blockedSet)
        
        AdverseAnalysis.showAnalysisDialog(this, result) {
            doFinish()
        }
    }

    private fun doFinish() {
        if (intent.getBooleanExtra("from_lock", false)) {
            // Explicitly tell CoreService to re-lock immediately
            // Explicitly tell Service to re-lock immediately
            val lockIntent = Intent(this, MonitorService::class.java)
            lockIntent.action = MonitorService.ACTION_REFRESH_RULES
            // lockIntent.putExtra("show_whitelist", true) // MonitorService logic needs to handle this if needed, or we rely on state
            startService(lockIntent)
            finish()
        } else {
            finish()
        }
    }

    

    private fun updatePendingHint() {
        tvModeHint.text = "🚫 勿扰时段拦截 (仅在勿扰时段内需申请解锁)"
        tvModeHint.setTextColor(android.graphics.Color.parseColor("#C62828"))

        val isAdaptation = CreditManager.isInAdaptationPeriod(this)
        val cardPending = findViewById<androidx.cardview.widget.CardView>(R.id.card_pending_changes)
        val tvPendingDetails = findViewById<TextView>(R.id.tv_pending_details)
        val tvLockout = findViewById<TextView>(R.id.tv_lockout_hint)

        if (isReadOnly && TimeRuleManager.isInSettingsLockoutPeriod(this)) {
            val refreshTime = TimeRuleManager.getRefreshTime(this)
            cardPending.visibility = android.view.View.VISIBLE
            cardPending.setCardBackgroundColor(android.graphics.Color.parseColor("#FFF3E0"))
            tvLockout.visibility = android.view.View.VISIBLE
            tvLockout.text = "🔒 设置已锁定 (深夜保护期 00:00 ~ $refreshTime)\n当前仅供查看，无法修改配置。"
        } else {
            tvLockout.visibility = android.view.View.GONE
        }

        if (isAdaptation) {
            // Adaptation: Use card to show "Adaptation Mode"
            cardPending.visibility = android.view.View.VISIBLE
            cardPending.setCardBackgroundColor(android.graphics.Color.parseColor("#E0F7FA"))
            tvPendingDetails.text = "当前处于【适应期】，所有设置将立即生效。\n（适应期结束后，移除黑名单等放松操作将延迟到次日生效）"
            tvPendingDetails.setTextColor(android.graphics.Color.parseColor("#006064"))
            findViewById<TextView>(R.id.tv_pending_hint_legacy).visibility = android.view.View.GONE
        } else {
             // 🚀 [ChangeLog] Use ChangeLogManager for display
             val changeLog = ChangeLogManager.getFormattedLogForCategory(this, "blacklist")
             
             if (changeLog.isNotEmpty()) {
                 cardPending.visibility = android.view.View.VISIBLE
                 cardPending.setCardBackgroundColor(android.graphics.Color.parseColor("#FFF8E1"))
                 tvPendingDetails.text = changeLog
                 tvPendingDetails.setTextColor(android.graphics.Color.parseColor("#5D4037"))
                 findViewById<TextView>(R.id.tv_pending_hint_legacy).visibility = android.view.View.VISIBLE
             } else {
                 if (!(isReadOnly && TimeRuleManager.isInSettingsLockoutPeriod(this))) {
                     cardPending.visibility = android.view.View.GONE
                 } else {
                     tvPendingDetails.text = ""
                 }
             }
        }
    }

    private fun applySettingsLockoutState() {
        if (!isReadOnly) return
        btnSave.isEnabled = false
        btnSave.alpha = 0.4f
        findViewById<Button>(R.id.btn_quick_import).apply {
            isEnabled = false
            alpha = 0.4f
        }
    }

    private fun showLockoutDialogOncePerDay() {
        if (!shouldShowLockoutDialog()) return
        val refreshTime = TimeRuleManager.getRefreshTime(this)
        AlertDialog.Builder(this)
            .setTitle("🔒 设置已锁定")
            .setMessage("当前处于【深夜保护期】(00:00 ~ $refreshTime)。\n\n为防止通宵后冲动修改配置，此时段内不允许修改任何设置。\n\n请在 $refreshTime 后再来调整。")
            .setPositiveButton("我知道了", null)
            .setCancelable(false)
            .show()
        markLockoutDialogShown()
    }

    private fun shouldShowLockoutDialog(): Boolean {
        val prefs = getSharedPreferences(CreditManager.PREFS_NAME, Context.MODE_PRIVATE)
        val today = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault()).format(java.util.Date())
        val lastShown = prefs.getString(PREF_LOCKOUT_DIALOG_LAST_SHOWN, null)
        return lastShown != today
    }

    private fun markLockoutDialogShown() {
        val prefs = getSharedPreferences(CreditManager.PREFS_NAME, Context.MODE_PRIVATE)
        val today = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault()).format(java.util.Date())
        prefs.edit().putString(PREF_LOCKOUT_DIALOG_LAST_SHOWN, today).apply()
    }

    private fun loadInstalledApps() {
        Thread {
            val pm = packageManager
            val packages = try {
                pm.getInstalledApplications(0)
            } catch (_: Exception) {
                emptyList()
            }

            val dangerousSet = if (currentMode == MODE_ALARM_EMERGENCY_WHITELIST) {
                AlarmChallengeEmergencyWhitelist.getDangerousPackages(this)
            } else {
                emptySet()
            }

            val appList = packages.filter {
                // 只显示有入口的APP，或是应急名单中已选中的系统应用（如电话/短信）
                hasLaunchIntent(it.packageName) ||
                    (currentMode == MODE_ALARM_EMERGENCY_WHITELIST && selectedApps.contains(it.packageName))
            }.filter {
                if (currentMode == MODE_ALARM_EMERGENCY_WHITELIST) {
                    AlarmChallengeEmergencyWhitelist.isSelectable(this, it.packageName, dangerousSet)
                } else {
                    // 硬剔除项（桌面/输入法/电话/短信/设置/系统导航/时钟/系统跟踪/IronWill）在所有名单页永不显示。
                    !SegmentBlacklist.isHardExempt(this, it.packageName)
                }
            }.map {
                val appName = try { it.loadLabel(pm).toString() } catch (_: Exception) { it.packageName }
                val pkgName = it.packageName

                AppInfo(
                    name = appName,
                    packageName = pkgName,
                    icon = try { it.loadIcon(pm) } catch (_: Exception) {
                        resources.getDrawable(android.R.drawable.sym_def_app_icon, null)
                    },
                    isSelected = selectedApps.contains(pkgName)
                )
            }.sortedBy { it.name }

            runOnUiThread {
                allAppsList = appList
                updateLists()
            }
        }.start()
    }

    private fun hasLaunchIntent(packageName: String): Boolean {
        return packageManager.getLaunchIntentForPackage(packageName) != null
    }

    private fun updateLists() {
        filterApps("")
        updateSelectedAppsList()
    }
}