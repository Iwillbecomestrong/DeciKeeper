/**
 * =================================================================================
 * [Context Header]
 * * @file: DevGuardExemptionManager.kt
 * * @role: Domain Skill
 * * @description: 开发者进阶拦截（安装拦截 / 开发者选项拦截）的共享豁免时段与各自勾选的状态管理。
 * * @responsibilities:
 * 1. 保存共享豁免窗口与两个「豁免时段内放行」勾选。
 * 2. 严立宽延：改严立即生效，改宽次日刷新时刻生效；适应期、回到基准、或对应拦截在基准里本来就是关的，立即生效。
 * 3. 提供「此刻是否豁免放行」的判定。
 * * @context:
 * - 上游: RulesFragment, InstallProtection, DeveloperOptionsProtection
 * - 下游: SharedPreferences, ConfigPendingManager, TimeRuleManager（基准）
 * * @constraints (必读):
 * 1. 不引入 UI 依赖。
 * 2. 豁免只暂停拦截，不改写两个拦截各自的开关与 PENDING。
 * 3. 日期与时间拦截不参与豁免：放行改时间会让所有严立宽延失效。
 * 4. 待生效项在读取时按到期时间自行落地。
 * =================================================================================
 */
package com.decikeeper.app.manager

import android.content.Context
import android.content.SharedPreferences
import com.decikeeper.app.manager.DevGuardExemptionWindow.Change
import java.util.Calendar

object DevGuardExemptionManager {

    const val PREFS_NAME = "dev_guard_exemption_prefs"
    const val KEY_WINDOW = "dev_guard_exempt_window"
    const val KEY_INSTALL_FLAG = "dev_guard_exempt_install"
    const val KEY_DEVELOPER_OPTIONS_FLAG = "dev_guard_exempt_developer_options"

    const val KEY_PENDING_WINDOW = "dev_guard_exempt_window_pending"
    const val KEY_PENDING_INSTALL_FLAG = "dev_guard_exempt_install_pending"
    const val KEY_PENDING_DEVELOPER_OPTIONS_FLAG = "dev_guard_exempt_developer_options_pending"

    enum class Guard { INSTALL, DEVELOPER_OPTIONS }

    enum class Item(val activeKey: String, val pendingKey: String) {
        WINDOW(KEY_WINDOW, KEY_PENDING_WINDOW),
        INSTALL_FLAG(KEY_INSTALL_FLAG, KEY_PENDING_INSTALL_FLAG),
        DEVELOPER_OPTIONS_FLAG(KEY_DEVELOPER_OPTIONS_FLAG, KEY_PENDING_DEVELOPER_OPTIONS_FLAG)
    }

    enum class Outcome { APPLIED, PENDING, UNCHANGED, INVALID }

    private fun getPrefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun itemOf(guard: Guard): Item = when (guard) {
        Guard.INSTALL -> Item.INSTALL_FLAG
        Guard.DEVELOPER_OPTIONS -> Item.DEVELOPER_OPTIONS_FLAG
    }

    /** 把已到期的待生效项落地为生效值。 */
    private fun applyDuePending(context: Context) {
        val prefs = getPrefs(context)
        Item.values().forEach { item ->
            if (!ConfigPendingManager.isPendingEffective(context, item.pendingKey)) return@forEach
            val raw = pendingRaw(context, item)
            if (item == Item.WINDOW) {
                DevGuardExemptionWindow.parse(raw)?.let { prefs.edit().putString(item.activeKey, it.serialize()).apply() }
            } else if (raw != null) {
                prefs.edit().putBoolean(item.activeKey, raw == "true").apply()
            }
            ConfigPendingManager.clearPending(context, item.pendingKey)
        }
    }

    private fun pendingRaw(context: Context, item: Item): String? =
        context.getSharedPreferences("pending_config_prefs", Context.MODE_PRIVATE).getString(item.pendingKey, null)

    /** 当前生效的共享窗口。 */
    fun getWindow(context: Context): DevGuardExemptionWindow {
        applyDuePending(context)
        return DevGuardExemptionWindow.parse(getPrefs(context).getString(KEY_WINDOW, null))
            ?: DevGuardExemptionWindow.DEFAULT
    }

    fun isExemptionEnabled(context: Context, guard: Guard): Boolean {
        applyDuePending(context)
        return getPrefs(context).getBoolean(itemOf(guard).activeKey, false)
    }

    fun getPendingWindow(context: Context): DevGuardExemptionWindow? {
        applyDuePending(context)
        if (ConfigPendingManager.getEffectiveTime(context, KEY_PENDING_WINDOW) == 0L) return null
        return DevGuardExemptionWindow.parse(pendingRaw(context, Item.WINDOW))
    }

    fun getPendingExemption(context: Context, guard: Guard): Boolean? {
        applyDuePending(context)
        val item = itemOf(guard)
        if (ConfigPendingManager.getEffectiveTime(context, item.pendingKey) == 0L) return null
        return pendingRaw(context, item)?.let { it == "true" }
    }

    fun getPendingEffectiveTime(context: Context, item: Item): Long {
        applyDuePending(context)
        return ConfigPendingManager.getEffectiveTime(context, item.pendingKey)
    }

    /** 不改任何状态，只预判保存这个窗口会立即生效、挂起，还是无变化（供设置页提示）。 */
    fun previewWindow(context: Context, new: DevGuardExemptionWindow): Outcome {
        if (!new.isValid()) return Outcome.INVALID
        return when (DevGuardExemptionWindow.compare(getWindow(context), new)) {
            Change.SAME -> Outcome.UNCHANGED
            Change.STRICTER -> Outcome.APPLIED
            Change.LOOSER -> {
                val backToBaseline = DevGuardExemptionWindow.compare(
                    TimeRuleManager.getBaselineDevGuardExemptionWindow(context), new
                ) != Change.LOOSER
                if (TimeRuleManager.isInAdaptationPeriod(context) || backToBaseline || noGuardProtectedAtBaseline(context)) {
                    Outcome.APPLIED
                } else {
                    Outcome.PENDING
                }
            }
        }
    }

    fun setWindow(context: Context, new: DevGuardExemptionWindow): Outcome {
        val outcome = previewWindow(context, new)
        when (outcome) {
            Outcome.INVALID -> Unit
            Outcome.UNCHANGED -> ConfigPendingManager.clearPending(context, KEY_PENDING_WINDOW)
            Outcome.APPLIED -> applyWindow(context, new)
            Outcome.PENDING ->
                ConfigPendingManager.submitChange(context, KEY_PENDING_WINDOW, new.serialize(), strictNextDay = true)
        }
        return outcome
    }

    fun setExemptionEnabled(context: Context, guard: Guard, enabled: Boolean): Outcome {
        val item = itemOf(guard)
        val active = isExemptionEnabled(context, guard)
        return when (DevGuardExemptionWindow.compareFlag(active, enabled)) {
            Change.SAME -> {
                ConfigPendingManager.clearPending(context, item.pendingKey)
                Outcome.UNCHANGED
            }
            Change.STRICTER -> {
                applyFlag(context, item, enabled)
                Outcome.APPLIED
            }
            Change.LOOSER -> {
                val backToBaseline = DevGuardExemptionWindow.compareFlag(
                    TimeRuleManager.getBaselineDevGuardExemptionEnabled(context, guard), enabled
                ) != Change.LOOSER
                if (TimeRuleManager.isInAdaptationPeriod(context) || backToBaseline || !guardProtectedAtBaseline(context, guard)) {
                    applyFlag(context, item, enabled)
                    Outcome.APPLIED
                } else {
                    ConfigPendingManager.submitChange(context, item.pendingKey, enabled.toString(), strictNextDay = true)
                    Outcome.PENDING
                }
            }
        }
    }

    /**
     * 豁免只在对应拦截开着时才有用：基准里该拦截是关的，说明昨天没有这层保护，今天放宽豁免没有可「放松」的东西，
     * 不必卡到次日。基准只由每日快照写入，所以当天才开启的拦截不会让豁免立刻被锁住。
     */
    private fun guardProtectedAtBaseline(context: Context, guard: Guard): Boolean = when (guard) {
        Guard.INSTALL -> TimeRuleManager.getBaselineInstallGuardState(context)
        Guard.DEVELOPER_OPTIONS -> TimeRuleManager.getBaselineDeveloperOptionsGuardState(context)
    }

    /** 共享窗口同时作用于两个拦截：只有两个拦截在基准里都是关的，改宽窗口才可以立即生效。 */
    private fun noGuardProtectedAtBaseline(context: Context): Boolean =
        Guard.values().none { guardProtectedAtBaseline(context, it) }

    private fun applyWindow(context: Context, window: DevGuardExemptionWindow) {
        getPrefs(context).edit().putString(KEY_WINDOW, window.serialize()).apply()
        ConfigPendingManager.clearPending(context, KEY_PENDING_WINDOW)
    }

    private fun applyFlag(context: Context, item: Item, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(item.activeKey, enabled).apply()
        ConfigPendingManager.clearPending(context, item.pendingKey)
    }

    /** 此刻该拦截是否处于豁免放行：已勾选且当前本地时间在窗口内。 */
    fun isExempt(context: Context, guard: Guard, nowMillis: Long = System.currentTimeMillis()): Boolean {
        if (!isExemptionEnabled(context, guard)) return false
        val cal = Calendar.getInstance().apply { timeInMillis = nowMillis }
        val minuteOfDay = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        return getWindow(context).contains(minuteOfDay)
    }
}
