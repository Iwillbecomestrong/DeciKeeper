package com.decikeeper.app.manager

import android.content.Context
import java.util.Calendar

/**
 * 把「当天生效且真正受限」的时段交给 [MascotJourney] 计算。
 * 生效判断沿用 `TimeSegment.isActiveToday`（含逻辑日与启用状态）；
 * 额度大于时段时长的是免费时段，不受限，团子不为它赶路。
 */
internal object MascotJourneyProvider {

    fun current(context: Context, nowMillis: Long = System.currentTimeMillis()): MascotJourney.State {
        val segments = try {
            TimeRuleManager.getTimeSegments(context)
                .filter { it.isActiveToday() && it.limitMins <= it.getDurationMinutes() }
                .map {
                    MascotJourney.Segment(
                        startMinute = it.startHour * 60 + it.startMinute,
                        endMinute = it.endHour * 60 + it.endMinute
                    )
                }
        } catch (_: Exception) {
            emptyList()
        }
        val cal = Calendar.getInstance().apply { timeInMillis = nowMillis }
        val nowMinute = cal.get(Calendar.HOUR_OF_DAY) * 60.0 + cal.get(Calendar.MINUTE) + cal.get(Calendar.SECOND) / 60.0
        return MascotJourney.compute(nowMinute, segments)
    }
}
