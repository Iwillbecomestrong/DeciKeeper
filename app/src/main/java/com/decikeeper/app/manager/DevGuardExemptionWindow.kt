/**
 * =================================================================================
 * [Context Header]
 * * @file: DevGuardExemptionWindow.kt
 * * @role: Domain Skill
 * * @description: 开发者进阶拦截共享豁免时段的纯函数（窗口命中、严宽判定、序列化）。
 * * @responsibilities:
 * 1. 判定某分钟是否落在窗口内（含开始、不含结束）。
 * 2. 判定窗口 / 勾选的改动是「严」还是「宽」。
 * * @context:
 * - 上游: DevGuardExemptionManager
 * - 下游: 无
 * * @constraints (必读):
 * 1. 不依赖 Android API，可 JVM 测试。
 * 2. 窗口不跨午夜：开始必须早于结束，结束最大 24:00。
 * =================================================================================
 */
package com.decikeeper.app.manager

data class DevGuardExemptionWindow(val startMinute: Int, val endMinute: Int) {

    enum class Change { SAME, STRICTER, LOOSER }

    fun isValid(): Boolean =
        startMinute in 0 until MINUTES_PER_DAY && endMinute in 1..MINUTES_PER_DAY && startMinute < endMinute

    /** 含开始、不含结束。 */
    fun contains(minuteOfDay: Int): Boolean = minuteOfDay >= startMinute && minuteOfDay < endMinute

    fun serialize(): String = "$startMinute-$endMinute"

    fun format(): String = "${formatMinute(startMinute)}–${formatMinute(endMinute)}"

    companion object {
        const val MINUTES_PER_DAY = 24 * 60

        val DEFAULT = DevGuardExemptionWindow(20 * 60 + 30, 21 * 60 + 30)

        /** 新窗口完全落在旧窗口内为严；相同为不变；其余（变长、平移）一律为宽。 */
        fun compare(old: DevGuardExemptionWindow, new: DevGuardExemptionWindow): Change = when {
            old == new -> Change.SAME
            new.startMinute >= old.startMinute && new.endMinute <= old.endMinute -> Change.STRICTER
            else -> Change.LOOSER
        }

        /** 勾选「豁免时段内放行」：取消为严，勾上为宽。 */
        fun compareFlag(old: Boolean, new: Boolean): Change = when {
            old == new -> Change.SAME
            old && !new -> Change.STRICTER
            else -> Change.LOOSER
        }

        fun parse(raw: String?): DevGuardExemptionWindow? {
            val parts = raw?.split("-") ?: return null
            if (parts.size != 2) return null
            val start = parts[0].toIntOrNull() ?: return null
            val end = parts[1].toIntOrNull() ?: return null
            return DevGuardExemptionWindow(start, end).takeIf { it.isValid() }
        }

        private fun formatMinute(minute: Int): String {
            val h = minute / 60
            val m = minute % 60
            return "%02d:%02d".format(h, m)
        }
    }
}
