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
package com.decikeeper.app.manager

import android.content.Context
import android.content.SharedPreferences


/**
 * Manages the Anti-Uninstall protection state.
 * Implements delayed-disable mechanism:
 * 1. Default: DISABLED (用户需在设置页显式开启)
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

    /** 设备管理器行的纯状态映射，UI 不自行复制保护状态判断。 */
    enum class DeviceAdminUiStatus {
        HIDDEN,
        DISABLED,
        NEEDS_ACTIVATION,
        ACTIVE,
        PENDING,
        NEEDS_DEACTIVATION
    }

    data class DeviceAdminUiState(
        val visible: Boolean,
        val checked: Boolean,
        val status: DeviceAdminUiStatus
    )

    /**
     * 仅在 OPPO/一加/真我等已实测适配机型默认推荐并展示设备管理器；
     * vivo/iQOO 及其他品牌默认隐藏，避免干扰用户。若用户已经激活或保护仍在运行，则全机型保持可见以便退出。
     * 该函数不读取设备、不写持久化，仅描述 UI 对当前真实状态的映射。
     */
    internal fun resolveDeviceAdminUiState(
        isSupportedBrand: Boolean,
        protectionState: State,
        isSystemAdminActive: Boolean
    ): DeviceAdminUiState {
        val visible = isSupportedBrand || isSystemAdminActive
        if (!visible) {
            return DeviceAdminUiState(false, false, DeviceAdminUiStatus.HIDDEN)
        }

        val status = when (protectionState) {
            State.PENDING -> DeviceAdminUiStatus.PENDING
            State.ACTIVE -> if (isSystemAdminActive) {
                DeviceAdminUiStatus.ACTIVE
            } else {
                DeviceAdminUiStatus.NEEDS_ACTIVATION
            }
            State.DISABLED -> if (isSystemAdminActive) {
                DeviceAdminUiStatus.NEEDS_DEACTIVATION
            } else {
                DeviceAdminUiStatus.DISABLED
            }
        }
        return DeviceAdminUiState(
            visible = true,
            checked = isSystemAdminActive && protectionState != State.DISABLED,
            status = status
        )
    }

    internal fun shouldShowInitialization(state: State): Boolean {
        return state == State.ACTIVE || state == State.PENDING
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

    internal fun disableImmediatelyForAccessibilityUnavailable(context: Context) {
        getPrefs(context).edit().putBoolean(KEY_IS_ENABLED, false).apply()
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
     * 检查当前应用是否已激活设备管理器权限
     */
    fun isDeviceAdminActive(context: Context): Boolean {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? android.app.admin.DevicePolicyManager
        val componentName = android.content.ComponentName(context, com.decikeeper.app.receiver.AdminReceiver::class.java)
        return dpm?.isAdminActive(componentName) == true
    }

    /**
     * 获取请求激活设备管理器的 Intent
     */
    fun getDeviceAdminIntent(context: Context): android.content.Intent {
        val componentName = android.content.ComponentName(context, com.decikeeper.app.receiver.AdminReceiver::class.java)
        val intent = android.content.Intent(android.app.admin.DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
        intent.putExtra(android.app.admin.DevicePolicyManager.EXTRA_DEVICE_ADMIN, componentName)
        intent.putExtra(android.app.admin.DevicePolicyManager.EXTRA_ADD_EXPLANATION, "激活设备管理器以防止应用在桌面和系统中被轻易卸载。")
        return intent
    }
}
