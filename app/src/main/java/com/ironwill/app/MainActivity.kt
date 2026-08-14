/**
 * =================================================================================
 * [Context Header]
 * * @file: MainActivity.kt
 * * @role: UI
 * * @description: 应用主入口与导航容器。
 * * @responsibilities:
 * 1. 初始化主页与底部导航。
 * 2. 管理主 Fragment 切换。
 * * @context:
 * - 上游: 系统启动器
 * - 下游: HomeFragment, RulesFragment, StatisticsFragment, AppWindowFragment
 * * @constraints (必读):
 * 1. 禁止承载复杂业务逻辑。
 * 2. UI 操作需主线程安全。
 * 3. 处理生命周期与权限异常。
 * =================================================================================
 */
package com.ironwill.app

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import com.google.android.material.bottomnavigation.BottomNavigationView

class MainActivity : AppCompatActivity() {

    private lateinit var bottomNav: BottomNavigationView

    // Fragment 实例
    private lateinit var homeFragment: HomeFragment
    private lateinit var rulesFragment: RulesFragment
    private lateinit var statisticsFragment: StatisticsFragment
    private lateinit var focusRulesFragment: FocusRulesFragment
    private lateinit var alarmFragment: AlarmFragment
    
    // 当前活跃的 Fragment
    private lateinit var activeFragment: Fragment

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 🔒 REQ-5 入口闸门：未同意用户不允许进入 MainActivity。
        // 历史背景：项目内有 7 个外部入口（BootReceiver/CoreService/MonitorService/
        // A11yGuardService/AlarmGuardianService/PermissionManager×2）会绕过
        // PrivacyConsentActivity 直接 startActivity(MainActivity)。即使所有外部
        // 入口都已收口到 MainActivityEntry，本防线仍是最后一道"拒绝服务"兜底。
        if (!PrivacyConsentManager.isConsented(this)) {
            Log.w("MainActivity", "onCreate: not consented, redirecting to PrivacyConsentActivity and clearing stack")

            // ─────────────────────────────────────────────────────────────────
            // REQ-5 Round 4 Fix #2: 兜底路径也要先保存当前 intent 的 extras，
            // 否则 upstack 的 PendingIntent（如 MonitorService 的
            // show_permission_dialog）会随"裸"跳同意页而丢失。
            //   - extra.hasExtra(...) → 才值得存（空 Bundle 没必要）
            //   - 存到 PendingMainActivityExtras（与正常路径同一 prefs），
            //     同意后由 PrivacyConsentActivity.goToMainAndFinish 回放。
            // ─────────────────────────────────────────────────────────────────
            val incoming = intent
            if (incoming != null) {
                val incomingExtras = incoming.extras
                if (incomingExtras != null && !incomingExtras.isEmpty) {
                    try {
                        PendingMainActivityExtras.save(this, incoming)
                        Log.i(
                            "MainActivity",
                            "onCreate: saved pending extras (size=${incomingExtras.size()}) " +
                                "before redirecting to consent"
                        )
                    } catch (e: Exception) {
                        Log.w("MainActivity", "onCreate: failed to save pending extras", e)
                    }
                }
            }

            // TODO(round-4 MAJOR-4): 真机验证这条兜底路径保留 extras。
            // 当前 emulator 不可用，无法跑 androidTest；待 emulator-tester
            // 验证：pm clear → 通知中心点击 → 期望同意页进入后→ 回主页自动
            // 弹权限对话框，show_permission_dialog 不丢。
            Log.w(
                "MainActivity",
                "TODO(round-4 MAJOR-4): needs androidTest on real device " +
                    "for fallback pending-extras path (BLOCKED_NEEDS_REAL_DEVICE)"
            )

            val redirect = Intent(this, PrivacyConsentActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            }
            startActivity(redirect)
            finishAffinity()
            return
        }

        setContentView(R.layout.activity_main)

        bottomNav = findViewById(R.id.bottom_navigation)
        
        // 初始化所有 Fragment
        initFragments(savedInstanceState)
        
        // 设置底部导航
        setupBottomNav()

        // 🔥 启动 MonitorService (核心监控服务)
        startMonitorService()

        // 🛡️ 启动 A11yGuardService (无障碍守护服务)
        startGuardService()

        // 🚀 Handle Navigation Intent (e.g. return from other activities)
        handleNavigateIntent(intent)
        // 🚀 Handle Permission Dialog Intent (e.g. from daily a11y health check)
        handlePermissionDialogIntent(intent)
    }
    
    private fun initFragments(savedInstanceState: Bundle?) {
        if (savedInstanceState == null) {
            // 首次启动，创建并添加
            homeFragment = HomeFragment()
            rulesFragment = RulesFragment()
            statisticsFragment = StatisticsFragment()
            focusRulesFragment = FocusRulesFragment()
            alarmFragment = AlarmFragment()

            // 添加所有 Fragment 到 FragmentManager，初始只显示 homeFragment
            supportFragmentManager.beginTransaction()
                .add(R.id.fragment_container, focusRulesFragment, "FOCUS_RULES").hide(focusRulesFragment)
                .add(R.id.fragment_container, statisticsFragment, "STATISTICS").hide(statisticsFragment)
                .add(R.id.fragment_container, rulesFragment, "RULES").hide(rulesFragment)
                .add(R.id.fragment_container, alarmFragment, "ALARM").hide(alarmFragment)
                .add(R.id.fragment_container, homeFragment, "HOME")
                .commit()

            activeFragment = homeFragment
        } else {
            // 恢复状态，查找已有 Instance (防止重复叠加)
            homeFragment = supportFragmentManager.findFragmentByTag("HOME") as? HomeFragment ?: HomeFragment()
            rulesFragment = supportFragmentManager.findFragmentByTag("RULES") as? RulesFragment ?: RulesFragment()
            statisticsFragment = supportFragmentManager.findFragmentByTag("STATISTICS") as? StatisticsFragment ?: StatisticsFragment()
            focusRulesFragment = supportFragmentManager.findFragmentByTag("FOCUS_RULES") as? FocusRulesFragment ?: FocusRulesFragment()
            alarmFragment = supportFragmentManager.findFragmentByTag("ALARM") as? AlarmFragment ?: AlarmFragment()

            // 恢复 activeFragment 指针
            activeFragment = when {
                !homeFragment.isHidden -> homeFragment
                !rulesFragment.isHidden -> rulesFragment
                !statisticsFragment.isHidden -> statisticsFragment
                !focusRulesFragment.isHidden -> focusRulesFragment
                !alarmFragment.isHidden -> alarmFragment
                else -> homeFragment
            }
        }
    }

    private fun setupBottomNav() {
        bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_home -> {
                    switchFragment(homeFragment)
                    true
                }
                R.id.nav_time_config -> {
                    switchFragment(focusRulesFragment)
                    true
                }
                R.id.nav_statistics -> {
                    switchFragment(statisticsFragment)
                    true
                }
                R.id.nav_alarm -> {
                    switchFragment(alarmFragment)
                    true
                }
                R.id.nav_rules -> {
                    switchFragment(rulesFragment)
                    true
                }
                else -> false
            }
        }
    }
    
    private fun switchFragment(targetFragment: Fragment) {
        if (activeFragment === targetFragment) return
        
        supportFragmentManager.beginTransaction()
            .hide(activeFragment)
            .show(targetFragment)
            .commit()
        
        activeFragment = targetFragment
    }

    fun showFocusRules(tabIndex: Int) {
        bottomNav.selectedItemId = R.id.nav_time_config
        focusRulesFragment.selectTab(tabIndex)
    }

    fun showAppWindow() {
        showFocusRules(FocusRulesFragment.TAB_APP_WINDOW)
    }
    
    private fun startGuardService() {
        try {
            val intent = Intent(this, A11yGuardService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        } catch (e: Exception) {
            Log.e("MainActivity", "Failed to start A11yGuardService", e)
        }
    }
    
    private fun startMonitorService() {
        try {
            val intent = Intent(this, MonitorService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
            android.util.Log.d("MainActivity", "MonitorService started")
        } catch (e: Exception) {
            android.util.Log.e("MainActivity", "Failed to start MonitorService", e)
        }
    }
    
    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleNavigateIntent(intent)
        handlePermissionDialogIntent(intent)
    }

    private fun handlePermissionDialogIntent(intent: Intent?) {
        if (intent?.getBooleanExtra("show_permission_dialog", false) == true) {
            intent.removeExtra("show_permission_dialog")
            // HomeFragment 可能需要一小段时间完成附加，postDelayed 确保安全
            window.decorView.post {
                try {
                    if (::homeFragment.isInitialized) {
                        homeFragment.checkAccessibilityPermission()
                    }
                } catch (e: Exception) {
                    Log.w("MainActivity", "Failed to check accessibility permission", e)
                }
        }
    }
    }

    private fun handleNavigateIntent(intent: Intent?) {
        val navigateTo = intent?.getStringExtra("navigate_to")
        when (navigateTo) {
            "rules" -> {
                bottomNav.selectedItemId = R.id.nav_rules
                intent?.removeExtra("navigate_to")
            }
            "home" -> {
                bottomNav.selectedItemId = R.id.nav_home
                intent?.removeExtra("navigate_to")
            }
            "statistics" -> {
                bottomNav.selectedItemId = R.id.nav_statistics
                intent?.removeExtra("navigate_to")
            }
            "time_config" -> {
                bottomNav.selectedItemId = R.id.nav_time_config
                focusRulesFragment.selectTab(FocusRulesFragment.TAB_TIME)
                intent?.removeExtra("navigate_to")
            }
            "app_window" -> {
                bottomNav.selectedItemId = R.id.nav_time_config
                focusRulesFragment.selectTab(FocusRulesFragment.TAB_APP_WINDOW)
                intent?.removeExtra("navigate_to")
            }
            "alarm" -> {
                bottomNav.selectedItemId = R.id.nav_alarm
                intent?.removeExtra("navigate_to")
            }
        }
    }
    
    override fun onResume() {
        super.onResume()
        
        // 🚀 [Fix] Trigger pending rules check whenever app comes to foreground
        val serviceIntent = Intent(this, MonitorService::class.java)
        serviceIntent.action = MonitorService.ACTION_REFRESH_RULES
        startService(serviceIntent)

        // 🚀 [Fix] Cleanup Zombie Logs
        ChangeLogManager.syncLogStatusWithPendingState(this)

        // 🚀 [Fix] Rescue Stuck Rules
        ChangeLogManager.applyStuckPendingRules(this)
        
    }

    override fun onStop() {
        super.onStop()
    }

    override fun onDestroy() {
        super.onDestroy()
    }
}
