package com.decikeeper.app.manager

import android.content.Context
import com.decikeeper.app.manager.AlarmEmergencyExitPolicy.State
import com.decikeeper.app.util.DirectBootState

/**
 * 闹钟挑战「应急退出」的持久化与读写入口。规则全部在 [AlarmEmergencyExitPolicy]；
 * 这里只负责：读取时对齐业务日/业务月、写回、以及对外的少量查询。
 *
 * 独立 prefs 文件。设置备份会携带它，导入时只走 [mergeFromBackup]（已用次数取较大值、
 * 每月次数加严立即/放宽延期），不会整文件覆盖，也不会被旧备份刷回额度。
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

    /**
     * 合并备份中的 `alarm_emergency_exit` 条目：备份先对齐到当前业务月再合并，
     * 已用次数同月取较大值。`backupValues` 为解码后的 prefs 键值。
     */
    fun mergeFromBackup(context: Context, backupValues: Map<String, Any?>, now: Long = System.currentTimeMillis()): State = synchronized(lock) {
        val local = load(context, now)
        val businessDate = CreditManager.getBusinessDateStringAt(context, now)
        val backup = AlarmEmergencyExitPolicy.rollover(parse(backupValues), businessDate, now)
        val merged = AlarmEmergencyExitPolicy.mergeFromBackup(
            local = local,
            backup = backup,
            now = now,
            inAdaptation = CreditManager.isInAdaptationPeriod(context),
            nextEffectiveAt = nextMonthEffectiveTime(context, now)
        )
        write(context, merged)
        merged
    }

    fun nextMonthEffectiveTime(context: Context, now: Long = System.currentTimeMillis()): Long {
        val parts = TimeRuleManager.getRefreshTime(context).split(":")
        val hour = parts.getOrNull(0)?.toIntOrNull()?.coerceIn(0, 23) ?: 8
        val minute = parts.getOrNull(1)?.toIntOrNull()?.coerceIn(0, 59) ?: 0
        return AlarmEmergencyExitPolicy.nextMonthEffectiveTime(now, hour, minute)
    }

    private fun read(context: Context): State =
        parse(context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).all)

    private fun parse(all: Map<String, *>): State {
        val default = State()
        fun int(key: String, fallback: Int) = (all[key] as? Number)?.toInt() ?: fallback
        val pending = int(KEY_PENDING_VALUE, -1).takeIf { it >= 0 }
        return State(
            active = AlarmEmergencyExitPolicy.clamp(int(KEY_ACTIVE, default.active)),
            baseline = AlarmEmergencyExitPolicy.clamp(int(KEY_BASELINE, default.baseline)),
            baselineDay = all[KEY_BASELINE_DAY] as? String ?: "",
            pendingValue = pending,
            pendingEffectiveAt = (all[KEY_PENDING_EFFECTIVE] as? Number)?.toLong() ?: 0L,
            monthKey = all[KEY_MONTH_KEY] as? String ?: "",
            monthStartConfig = AlarmEmergencyExitPolicy.clamp(int(KEY_MONTH_START, default.monthStartConfig)),
            usedThisMonth = int(KEY_USED, 0).coerceAtLeast(0)
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
