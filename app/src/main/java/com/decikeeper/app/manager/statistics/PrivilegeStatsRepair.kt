package com.decikeeper.app.manager.statistics

import android.content.Context
import com.decikeeper.app.manager.CreditManager

/**
 * 一次性修正：2026-10-06 那次特权被结算了两遍，统计里的分钟翻倍。
 *
 * 成因已查实并已修（`CreditManager` 的特权结算加了互斥）：设置备份里 10/06 的
 * `privilege_usage_*` 为 上午 208 / 下午 182，恰是该次特权覆盖的勿扰分钟 104 / 91 的两倍；
 * 其余日期都只记了一遍，是正常的。
 *
 * 只在数值**恰好等于**已确认的翻倍值时才减半，并打上已处理标记：其它设备、其它数值、
 * 第二次启动都不会被改动。修完后这段迁移可以整体删除。
 */
object PrivilegeStatsRepair {

    private const val KEY_DONE = "privilege_stats_20261006_double_settle_repaired"

    /** key → 已确认的翻倍值（真实值是它的一半）。 */
    private val DOUBLED = mapOf(
        "privilege_usage_20261006_上午" to 208,
        "privilege_usage_20261006_下午" to 182
    )

    @Synchronized
    fun repairOnce(context: Context) {
        val prefs = context.getSharedPreferences(CreditManager.PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_DONE, false)) return
        val editor = prefs.edit()
        DOUBLED.forEach { (key, doubled) ->
            if (prefs.getInt(key, -1) == doubled) editor.putInt(key, doubled / 2)
        }
        editor.putBoolean(KEY_DONE, true).apply()
    }
}
