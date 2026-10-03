package com.decikeeper.app.manager

import android.content.Context
import com.decikeeper.app.manager.AlarmEmergencyExitPolicy.State
import com.decikeeper.app.util.DirectBootState

/**
 * 闹钟挑战「应急退出」的持久化与读写入口。规则全部在 [AlarmEmergencyExitPolicy]；
 * 这里只负责：读取时对齐业务日/业务月、写回、以及对外的少量查询。
 *
 * 独立 prefs 文件，不进入设置备份白名单：手动备份/恢复既不携带也不会重置额度。
 * 用户未解锁（CE 存储不可用）时一律视为无额度，避免重启后未解锁就绕过已用次数。
 */
internal object AlarmEmergencyExitStore {
    const val PREFS_NAME = "alarm_emergency_exit"

    private const val KEY_ACTIVE = "active"
    private const val KEY_BASELINE = "baseline"
    private const val KEY_BASELINE_DAY = "baseline_day"
    private const val KEY_PENDING_VALUE = "pending_value"
    private const val KEY_PENDING_EFFECTIVE = "pending_effective_at"
    private const val KEY_MONTH_KEY = "month_key"
    private const val KEY_MONTH_START = "month_start_config"
    private const val KEY_USED = "used_this_month"

    private val lock = Any()

    /** 对齐到 [now] 后的状态；有变化时写回。 */
    fun load(context: Context, now: Long = System.currentTimeMillis()): State = synchronized(lock) {
        val stored = read(context)
        val aligned = AlarmEmergencyExitPolicy.rollover(stored, CreditManager.getBusinessDateStringAt(context, now), now)
        if (aligned != stored) write(context, aligned)
        aligned
    }

    fun remaining(context: Context, now: Long = System.currentTimeMillis()): Int {
        if (!DirectBootState.isUserUnlocked(context)) return 0
        return AlarmEmergencyExitPolicy.remaining(load(context, now))
    }

    /** 额度可用时原子扣 1 次并返回 true。 */
    fun consumeOne(context: Context, now: Long = System.currentTimeMillis()): Boolean = synchronized(lock) {
        if (!DirectBootState.isUserUnlocked(context)) return false
        val consumed = AlarmEmergencyExitPolicy.consume(load(context, now)) ?: return false
        write(context, consumed)
        true
    }

    /** 设置每月次数：调小/不超基准立即生效，调大登记到下个自然月刷新时刻。 */
    fun requestMonthlyLimit(context: Context, requested: Int, now: Long = System.currentTimeMillis()): State = synchronized(lock) {
        val current = load(context, now)
        val next = AlarmEmergencyExitPolicy.requestChange(
            state = current,
            requested = requested,
            now = now,
            inAdaptation = CreditManager.isInAdaptationPeriod(context),
            nextEffectiveAt = nextMonthEffectiveTime(context, now)
        )
        write(context, next)
        next
    }

    fun nextMonthEffectiveTime(context: Context, now: Long = System.currentTimeMillis()): Long {
        val parts = TimeRuleManager.getRefreshTime(context).split(":")
        val hour = parts.getOrNull(0)?.toIntOrNull()?.coerceIn(0, 23) ?: 8
        val minute = parts.getOrNull(1)?.toIntOrNull()?.coerceIn(0, 59) ?: 0
        return AlarmEmergencyExitPolicy.nextMonthEffectiveTime(now, hour, minute)
    }

    private fun read(context: Context): State {
        val p = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val default = State()
        val pending = p.getInt(KEY_PENDING_VALUE, -1).takeIf { it >= 0 }
        return State(
            active = AlarmEmergencyExitPolicy.clamp(p.getInt(KEY_ACTIVE, default.active)),
            baseline = AlarmEmergencyExitPolicy.clamp(p.getInt(KEY_BASELINE, default.baseline)),
            baselineDay = p.getString(KEY_BASELINE_DAY, "") ?: "",
            pendingValue = pending,
            pendingEffectiveAt = p.getLong(KEY_PENDING_EFFECTIVE, 0L),
            monthKey = p.getString(KEY_MONTH_KEY, "") ?: "",
            monthStartConfig = AlarmEmergencyExitPolicy.clamp(p.getInt(KEY_MONTH_START, default.monthStartConfig)),
            usedThisMonth = p.getInt(KEY_USED, 0).coerceAtLeast(0)
        )
    }

    private fun write(context: Context, s: State) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putInt(KEY_ACTIVE, s.active)
            .putInt(KEY_BASELINE, s.baseline)
            .putString(KEY_BASELINE_DAY, s.baselineDay)
            .putInt(KEY_PENDING_VALUE, s.pendingValue ?: -1)
            .putLong(KEY_PENDING_EFFECTIVE, s.pendingEffectiveAt)
            .putString(KEY_MONTH_KEY, s.monthKey)
            .putInt(KEY_MONTH_START, s.monthStartConfig)
            .putInt(KEY_USED, s.usedThisMonth)
            .apply()
    }
}
