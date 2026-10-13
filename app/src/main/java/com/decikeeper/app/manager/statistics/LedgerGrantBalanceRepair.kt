package com.decikeeper.app.manager.statistics

import android.content.Context

/**
 * 一次性修正：2026-10-09 两笔「补记」的额度发放存进库里的「变动后」是错的。
 *
 * 旧版补记（时段早已开始、装包后第一次轮询才检测到）把「装包那一刻的当前结余」当成了发放后的结余，
 * 于是 08:30 的发放显示成变动前 −9m / −1.5 次（应为 0 / 0）。新版补记已按「上一笔变动后 + 本次发放」估算，
 * 这里只修已经写进库里的这两条：
 * - 08:30 上午：变动后 21m / 11 half-unit → 30m / 14（09:23 休息扣 3m 后剩 27m，倒推得 30m）
 * - 12:45 下午：变动后 26m / 16 half-unit → 49.1m / 22（12:48 休息扣 3m 后剩 46.1m，倒推得 49.1m）
 *
 * 只在存的值恰好等于上面已确认的错误值时才改，改完打已处理标记；修完后这段迁移可以整体删除。
 */
object LedgerGrantBalanceRepair {

    private const val PREFS_NAME = "rest_stats_repair"
    private const val KEY_DONE = "grant_balance_20261009_repaired"

    private class Fix(
        val dateLabel: String,
        val wrongMinutes: Double,
        val wrongHalfUnits: Int,
        val rightMinutes: Double,
        val rightHalfUnits: Int
    )

    private val FIXES = listOf(
        Fix("2026-10-09 08:30", 21.0, 11, 30.0, 14),
        Fix("2026-10-09 12:45", 26.0, 16, 49.1, 22)
    )

    @Synchronized
    fun repairOnce(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_DONE, false)) return
        val records = RestStatsRecorder.getAllRecords(context)
        FIXES.forEach { fix ->
            val target = records.firstOrNull {
                it.type == RestLedger.TYPE_GRANT && it.dateLabel == fix.dateLabel &&
                    kotlin.math.abs(it.balanceMinutes - fix.wrongMinutes) < 0.001 && it.balanceCount == fix.wrongHalfUnits
            } ?: return@forEach
            RestStatsRecorder.updateRecord(
                context,
                target.copy(balanceMinutes = fix.rightMinutes, balanceCount = fix.rightHalfUnits)
            )
        }
        prefs.edit().putBoolean(KEY_DONE, true).apply()
    }
}
