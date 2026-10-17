package com.decikeeper.app.manager

import android.content.Context
import com.decikeeper.app.data.TimeSegment

/** 从与锁屏大数字相同的当前段、余额及现有账本构造显示快照。 */
internal object SnackJarProvider {
    fun identity(context: Context, current: TimeSegment) =
        "${CreditManager.getBusinessDateString(context)}:${current.name}:${current.formatTimeRange()}"

    fun read(context: Context, current: TimeSegment, minutes: Double, halfUnits: Int): SnackJarPresentation.Snapshot {
        val segments = TimeRuleManager.getTimeSegments(context)
        val carry = CreditManager.captureSurplusCarrySnapshot(context, current)
        val next = TimeRuleManager.getNextSegment(context)
        val index = segments.indexOf(current)
        val tomorrow = index >= 0 && TimeRuleManager.isLastLogicalSegment(context, index)
        return SnackJarPresentation.Snapshot(
            identity = identity(context, current),
            quota = SnackJarPresentation.Quota(minutes,
                CreditManager.getSegmentEffectiveLimitMinutes(context, current) + carry.timeTotal,
                halfUnits, (CreditManager.getSegmentEffectiveUsableCountLimitHalfUnits(context, current).toLong() +
                    carry.countTotal).coerceIn(0, Int.MAX_VALUE.toLong()).toInt()),
            nextName = next?.let { it.name + if (tomorrow) "（明日）" else "" },
            nextQuota = next?.let { CreditManager.getSegmentSnackQuota(context, it, tomorrow) },
            borrowedMinutes = CreditManager.getBorrowedMinutesFromNext(context),
            borrowedHalfUnits = CreditManager.getBorrowedUsableCountHalfUnitsFromNext(context),
            parcels = PendingChangeTable.items(context, PendingChangeTable.Scope.SEGMENT)
                .mapNotNull { it.snackParcel }.sortedBy { it.effectiveMillis },
            activeLimits = segments.associate { it.name to (it.limitMins to it.getEffectiveUsableCountLimitHalfUnits()) }
        )
    }
}
