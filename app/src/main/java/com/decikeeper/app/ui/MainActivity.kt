package com.decikeeper.app.ui

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.decikeeper.app.R
import com.decikeeper.app.manager.ChangeLogManager
import com.decikeeper.app.manager.PrivacyConsentManager
import com.decikeeper.app.manager.PermissionManager
import com.decikeeper.app.manager.TimeRulesAccessGate
import com.decikeeper.app.manager.RecentTasksHideManager
import com.decikeeper.app.service.A11yGuardService
import com.decikeeper.app.service.MonitorService

class MainActivity : AppCompatActivity() {
    private lateinit var bottomNav: BottomNavigationView
    private lateinit var homeFragment: HomeFragment
    private lateinit var rulesFragment: RulesFragment
    private lateinit var statisticsFragment: StatisticsFragment
    private lateinit var focusRulesFragment: FocusRulesFragment
    private lateinit var alarmFragment: AlarmFragment
    private lateinit var activeFragment: Fragment

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.apply(this)
        super.onCreate(savedInstanceState)
        if (!PrivacyConsentManager.isConsented(this)) {
            Log.w("MainActivity", "onCreate: not consented, redirecting to PrivacyConsentActivity and clearing stack")
            // TODO(round-4 MAJOR-4): Pending extras fallback still needs emulator/real-device verification.
            Log.w("MainActivity", "BLOCKED_NEEDS_REAL_DEVICE: verify pending extras survive privacy-consent redirect")
            val incoming = intent
            if (incoming != null) {
                val incomingExtras = incoming.extras
                if (incomingExtras != null && !incomingExtras.isEmpty) {
                    try { PendingMainActivityExtras.save(this, incoming) }
                    catch (e: Exception) { Log.w("MainActivity", "onCreate: failed to save pending extras", e) }
                }
            }
            startActivity(Intent(this, PrivacyConsentActivity::class.java).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK) })
            finishAffinity()
            return
        }
        setContentView(R.layout.activity_main)
        val fragmentContainer = findViewById<View>(R.id.fragment_container)
        ViewCompat.setOnApplyWindowInsetsListener(fragmentContainer) { view, insets ->
            val topInset = insets.getInsets(
                WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.displayCutout()
            ).top
            view.setPadding(view.paddingLeft, topInset, view.paddingRight, view.paddingBottom)
            insets
        }
        ViewCompat.requestApplyInsets(fragmentContainer)
        bottomNav = findViewById(R.id.bottom_navigation)
        bottomNav.isItemActiveIndicatorEnabled = false
        initFragments(savedInstanceState)
        setupBottomNav()
        startMonitorService()
        startGuardService()
        handleNavigateIntent(intent)
        handlePermissionDialogIntent(intent)
    }

    fun showThemePicker(view: View) { ThemeManager.showPicker(this) }

    private fun initFragments(savedInstanceState: Bundle?) {
        if (savedInstanceState == null) {
            homeFragment = HomeFragment(); rulesFragment = RulesFragment(); statisticsFragment = StatisticsFragment(); focusRulesFragment = FocusRulesFragment(); alarmFragment = AlarmFragment()
            supportFragmentManager.beginTransaction()
                .add(R.id.fragment_container, focusRulesFragment, "FOCUS_RULES").hide(focusRulesFragment)
                .add(R.id.fragment_container, statisticsFragment, "STATISTICS").hide(statisticsFragment)
                .add(R.id.fragment_container, rulesFragment, "RULES").hide(rulesFragment)
                .add(R.id.fragment_container, alarmFragment, "ALARM").hide(alarmFragment)
                .add(R.id.fragment_container, homeFragment, "HOME").commit()
            activeFragment = homeFragment
        } else {
            homeFragment = supportFragmentManager.findFragmentByTag("HOME") as? HomeFragment ?: HomeFragment()
            rulesFragment = supportFragmentManager.findFragmentByTag("RULES") as? RulesFragment ?: RulesFragment()
            statisticsFragment = supportFragmentManager.findFragmentByTag("STATISTICS") as? StatisticsFragment ?: StatisticsFragment()
            focusRulesFragment = supportFragmentManager.findFragmentByTag("FOCUS_RULES") as? FocusRulesFragment ?: FocusRulesFragment()
            alarmFragment = supportFragmentManager.findFragmentByTag("ALARM") as? AlarmFragment ?: AlarmFragment()
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
                R.id.nav_home -> { switchFragment(homeFragment); true }
                R.id.nav_time_config -> selectTimeRules()
                R.id.nav_statistics -> { switchFragment(statisticsFragment); true }
                R.id.nav_alarm -> { switchFragment(alarmFragment); true }
                R.id.nav_rules -> { switchFragment(rulesFragment); true }
                else -> false
            }
        }
    }

    private fun switchFragment(targetFragment: Fragment) {
        if (activeFragment === targetFragment) return
        supportFragmentManager.beginTransaction().hide(activeFragment).show(targetFragment).commit()
        activeFragment = targetFragment
        if (targetFragment === alarmFragment) {
            alarmFragment.onEnteredAlarmTab()
        }
    }

    private fun selectTimeRules(): Boolean {
        if (!TimeRulesAccessGate.shouldEnter(PermissionManager.hasUsageStatsPermission(this))) {
            showUsageAccessRequiredDialog()
            return false
        }
        switchFragment(focusRulesFragment)
        return true
    }

    private fun showUsageAccessRequiredDialog() {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("需要使用情况访问")
            .setMessage("时间规则需要使用情况访问来识别当前前台应用并执行黑名单、勿扰时段规则。请先在系统设置中开启，返回后再次进入时间规则。")
            .setPositiveButton("去开启") { _, _ ->
                try {
                    startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
                } catch (e: Exception) {
                    Log.w("MainActivity", "Failed to open usage access settings", e)
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    fun showFocusRules(tabIndex: Int) { bottomNav.selectedItemId = R.id.nav_time_config; focusRulesFragment.selectTab(tabIndex) }
    fun showAppWindow() { showFocusRules(FocusRulesFragment.TAB_APP_WINDOW) }
    fun showAlarm() { bottomNav.selectedItemId = R.id.nav_alarm }

    private fun startGuardService() {
        try { val i = Intent(this, A11yGuardService::class.java); if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(i) else startService(i) }
        catch (e: Exception) { Log.e("MainActivity", "Failed to start A11yGuardService", e) }
    }

    private fun startMonitorService() {
        try { val i = Intent(this, MonitorService::class.java); if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(i) else startService(i) }
        catch (e: Exception) { Log.e("MainActivity", "Failed to start MonitorService", e) }
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
            window.decorView.post {
                try { if (::homeFragment.isInitialized) homeFragment.checkAccessibilityPermission() }
                catch (e: Exception) { Log.w("MainActivity", "Failed to check accessibility permission", e) }
            }
        }
    }

    private fun handleNavigateIntent(intent: Intent?) {
        when (intent?.getStringExtra("navigate_to")) {
            "rules" -> { bottomNav.selectedItemId = R.id.nav_rules; intent.removeExtra("navigate_to") }
            "home" -> { bottomNav.selectedItemId = R.id.nav_home; intent.removeExtra("navigate_to") }
            "statistics" -> { bottomNav.selectedItemId = R.id.nav_statistics; intent.removeExtra("navigate_to") }
            "time_config" -> { bottomNav.selectedItemId = R.id.nav_time_config; focusRulesFragment.selectTab(FocusRulesFragment.TAB_TIME); intent.removeExtra("navigate_to") }
            "app_window" -> { bottomNav.selectedItemId = R.id.nav_time_config; focusRulesFragment.selectTab(FocusRulesFragment.TAB_APP_WINDOW); intent.removeExtra("navigate_to") }
            "alarm" -> { bottomNav.selectedItemId = R.id.nav_alarm; intent.removeExtra("navigate_to") }
        }
    }

    override fun onResume() {
        super.onResume()
        val serviceIntent = Intent(this, MonitorService::class.java).apply { action = MonitorService.ACTION_REFRESH_RULES }
        startService(serviceIntent)
        ChangeLogManager.syncLogStatusWithPendingState(this)
        ChangeLogManager.applyStuckPendingRules(this)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        super.onBackPressed()
        if (RecentTasksHideManager.isHideEnabled(this)) {
            finishAndRemoveTask()
        }
    }
}
