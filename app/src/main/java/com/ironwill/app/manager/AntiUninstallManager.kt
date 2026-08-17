/**
 * =================================================================================
 * [Context Header]
 * * @file: AntiUninstallManager.kt
 * * @role: Domain Skill
 * * @description: 防卸载保护状态与流程管理。
 * * @responsibilities:
 * 1. 维护防卸载保护状态机。
 * 2. 提供启用/关闭与延迟生效逻辑。
 * * @context:
 * - 上游: RulesFragment, MonitorService
 * - 下游: SharedPreferences
 * * @constraints (必读):
 * 1. 状态读写需线程安全。
 * 2. 必须处理适应期与延迟生效规则。
 * 3. 不引入 UI 依赖。
 * =================================================================================
 */
package com.ironwill.app.manager

import android.content.Context
import android.content.SharedPreferences
import com.ironwill.app.receiver.DeviceAdmin


/**
 * Manages the Anti-Uninstall protection state.
 * Implements delayed-disable mechanism:
 * 1. Default: ACTIVE (Locked)
 * 2. User Request: Starts next-day countdown (PENDING)
 * 3. Time Reached: Becomes DISABLED (no auto-recover)
 */
object AntiUninstallManager {

    private const val PREFS_NAME = "anti_uninstall_prefs"
    private const val KEY_IS_ENABLED = "is_protection_enabled"
    private const val KEY_PENDING_DISABLE = "pending_disable_request"
    
    enum class State {
        DISABLED,  // Protection is OFF (Legacy or initial state)
        ACTIVE,    // Protection is ON. Uninstall is blocked.
        PENDING    // Protection is ON. Waiting for Tomorrow 06:00.
    }

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    /**
     * returns the current protection state.
     */
    fun getProtectionState(context: Context): State {
        val prefs = getPrefs(context)
        
        // 1) Explicit switch OFF means protection disabled.
        val isEnabled = prefs.getBoolean(KEY_IS_ENABLED, false)
        if (!isEnabled) return State.DISABLED

        // 2) Pending delayed-disable flow
        val effectiveTime = ConfigPendingManager.getEffectiveTime(context, KEY_PENDING_DISABLE)
        
        if (effectiveTime == 0L) {
            return State.ACTIVE // No pending request, fully active
        }

        val now = System.currentTimeMillis()
        
           // 3) Effective time reached -> disable permanently (no 6h unlock window)
        if (now < effectiveTime) {
            return State.PENDING // Waiting...
        }
           getPrefs(context).edit().putBoolean(KEY_IS_ENABLED, false).apply()
           ConfigPendingManager.clearPending(context, KEY_PENDING_DISABLE)
           return State.DISABLED
    }
    
    /**
     * Toggles protection.
     * @param enabled TRUE = Immediate ON. FALSE = Request Delayed OFF.
     */
    fun setProtectionEnabled(context: Context, enabled: Boolean) {
        if (enabled) {
            // Turning ON: Immediate
            getPrefs(context).edit().putBoolean(KEY_IS_ENABLED, true).apply()
            ConfigPendingManager.clearPending(context, KEY_PENDING_DISABLE)
        } else {
            // Turning OFF: Check Adaptation Period
            // Turning OFF: Check Adaptation Period OR Baseline Restoration
            // If Baseline was OFF (false), then turning OFF now is just restoring to baseline (Immediate).
            val baselineIsActive = TimeRuleManager.getBaselineAntiUninstallState(context)
            
            if (TimeRuleManager.isInAdaptationPeriod(context) || !baselineIsActive) {
                // 🚀 Adaptation OR Restoration: Immediate OFF
                getPrefs(context).edit().putBoolean(KEY_IS_ENABLED, false).apply()
                ConfigPendingManager.clearPending(context, KEY_PENDING_DISABLE)
            } else {
                // Delayed (Normal) - Baseline was Active, so this is an Adverse Change
                // 🚀 [Fix] Enforce Strict Next Day for Anti-Uninstall Cancellation
                // This prevents 2:00 AM requests from unlocking at 8:00 AM same day.
                ConfigPendingManager.submitChange(context, KEY_PENDING_DISABLE, "true", strictNextDay = true)
            }
        }
    }

    /**
     * Legacy/Helper: Cancel the request
     */
    fun cancelUninstall(context: Context) {
        ConfigPendingManager.clearPending(context, KEY_PENDING_DISABLE)
    }

    /**
     * Helper for UI: Get timestamp when unlock becomes valid
     */
    fun getUnlockTime(context: Context): Long {
        return ConfigPendingManager.getEffectiveTime(context, KEY_PENDING_DISABLE)
    }
    
    fun getExpirationTime(context: Context): Long {
            return 0L
    }
    
    /**
     * Checks if the app is currently an active Device Admin.
     */
    fun isDeviceAdminActive(context: Context): Boolean {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? android.app.admin.DevicePolicyManager
        val component = android.content.ComponentName(context, DeviceAdmin::class.java)
        return dpm?.isAdminActive(component) == true
    }
}
