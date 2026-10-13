package com.decikeeper.app.data

import com.decikeeper.app.data.SegmentActivationPolicy
import com.decikeeper.app.data.TimeSegment
import com.decikeeper.app.data.TimeSegmentJson
import java.util.Calendar
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SegmentCarryEligibilityTest {

    private fun ts(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long =
        Calendar.getInstance().apply {
            set(Calendar.YEAR, year)
            set(Calendar.MONTH, month - 1)
            set(Calendar.DAY_OF_MONTH, day)
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

    @Test
    fun enabledBeforeStartParticipatesNormally() {
        val start = ts(2026, 9, 1, 14, 0)
        val now = ts(2026, 9, 1, 16, 30)
        assertTrue(
            SegmentActivationPolicy.isEligibleForLiveRules(
                isEnabled = true,
                enabledStateChangedAt = ts(2026, 9, 1, 13, 50),
                segmentStartHour = 14,
                segmentStartMinute = 0,
                segmentEndHour = 16,
                segmentEndMinute = 0,
                refreshMinutes = 8 * 60,
                nowMillis = now
            )
        )
        assertTrue(start > 0)
    }

    @Test
    fun fullyDisabledSegmentNeverParticipatesInCarry() {
        assertFalse(
            SegmentActivationPolicy.isEligibleForLiveRules(
                isEnabled = false,
                enabledStateChangedAt = ts(2026, 9, 1, 13, 0),
                segmentStartHour = 14,
                segmentStartMinute = 0,
                segmentEndHour = 16,
                segmentEndMinute = 0,
                refreshMinutes = 8 * 60,
                nowMillis = ts(2026, 9, 1, 16, 30)
            )
        )
    }

    @Test
    fun enabledMidSegmentStaysEligibleAfterEndBecauseTheLedgerProrates() {
        val changedAt = ts(2026, 9, 1, 15, 0)

        assertTrue(
            SegmentActivationPolicy.isEligibleForLiveRules(
                isEnabled = true,
                enabledStateChangedAt = changedAt,
                segmentStartHour = 14,
                segmentStartMinute = 0,
                segmentEndHour = 16,
                segmentEndMinute = 0,
                refreshMinutes = 8 * 60,
                nowMillis = ts(2026, 9, 1, 15, 30)
            )
        )

        // 启用等同新建：折算由「今日额度」账承担（启用 15:00 → 折算后正常滚存），这里只看是否启用
        assertTrue(
            SegmentActivationPolicy.isEligibleForLiveRules(
                isEnabled = true,
                enabledStateChangedAt = changedAt,
                segmentStartHour = 14,
                segmentStartMinute = 0,
                segmentEndHour = 16,
                segmentEndMinute = 0,
                refreshMinutes = 8 * 60,
                nowMillis = ts(2026, 9, 1, 16, 30)
            )
        )
    }

    @Test
    fun enablingAfterSegmentEndedIsEligibleButTheLedgerGivesItZeroQuota() {
        assertTrue(
            SegmentActivationPolicy.isEligibleForLiveRules(
                isEnabled = true,
                enabledStateChangedAt = ts(2026, 9, 1, 17, 0),
                segmentStartHour = 14,
                segmentStartMinute = 0,
                segmentEndHour = 16,
                segmentEndMinute = 0,
                refreshMinutes = 8 * 60,
                nowMillis = ts(2026, 9, 1, 18, 0)
            )
        )
    }

    @Test
    fun lateEnableBecomesNormalAtNextLogicalOccurrence() {
        assertTrue(
            SegmentActivationPolicy.isEligibleForLiveRules(
                isEnabled = true,
                enabledStateChangedAt = ts(2026, 9, 1, 15, 0),
                segmentStartHour = 14,
                segmentStartMinute = 0,
                segmentEndHour = 16,
                segmentEndMinute = 0,
                refreshMinutes = 8 * 60,
                nowMillis = ts(2026, 9, 2, 13, 0)
            )
        )
    }

    @Test
    fun jsonPersistenceRecordsEnableToggleTimeWithoutUiSpecificLogic() {
        val changed = TimeSegment(
            startHour = 14,
            endHour = 16,
            limitMins = 30,
            isEnabled = true,
            enabledStateBaseline = false
        )

        val restored = TimeSegmentJson.parseSegmentsFromJson(
            TimeSegmentJson.segmentsToJson(listOf(changed))
        ).single()

        assertTrue(restored.enabledStateBaseline)
        assertTrue(restored.enabledStateChangedAt > 0L)
    }
}
