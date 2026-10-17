package com.decikeeper.app.manager

import org.junit.Assert.*
import org.junit.Test

class SnackJarPresentationTest {
    private fun quota(minutes: Double = 5.0, total: Double = 10.0, half: Int = 3, totalHalf: Int = 6) =
        SnackJarPresentation.Quota(minutes, total, half, totalHalf)

    private fun snapshot(q: SnackJarPresentation.Quota = quota()) =
        SnackJarPresentation.Snapshot("day:morning", q)

    @Test fun halfUnitProducesHalfCookieAndEmptySlotsRemainVisible() {
        assertEquals(listOf(1f, 0.5f, 0f), SnackJarPresentation.cookieFills(3, 6))
    }

    @Test fun enormousImportedCountDoesNotAllocateEnormousCookieRows() {
        val cells = SnackJarPresentation.cookieFills(Int.MAX_VALUE, Int.MAX_VALUE)
        assertEquals(12, cells.size)
        assertTrue(cells.all { it == 1f })
    }

    @Test fun negativeQuotaNeverDrawsNegativeOrFilledCookies() {
        assertEquals(listOf(0f), SnackJarPresentation.cookieFills(-2, -4))
        assertEquals(0f, SnackJarPresentation.timeFraction(quota(-1.0, 0.0)), 0f)
        assertEquals(0f, SnackJarPresentation.timeFraction(quota(Double.NaN, 10.0)), 0f)
    }

    @Test fun carryCanExceedConfiguredQuotaWithoutOverflowingTheBar() {
        assertEquals(1f, SnackJarPresentation.timeFraction(quota(15.0, 10.0)), 0f)
        assertEquals(0.5f, SnackJarPresentation.timeFraction(quota()), 0f)
    }

    @Test fun initialAttachAndSegmentChangeDoNotReplayBorrowAnimation() {
        val next = snapshot().copy(borrowedHalfUnits = 2)
        assertEquals(SnackJarPresentation.Effect.NONE, SnackJarPresentation.transition(null, next, 0))
        assertEquals(SnackJarPresentation.Effect.NONE,
            SnackJarPresentation.transition(snapshot(), next.copy(identity = "day:evening"), 0))
    }

    @Test fun borrowOnlyAnimatesOnAnIncreaseAndNotOnRepeatedRefresh() {
        val before = snapshot()
        val after = before.copy(borrowedHalfUnits = 1)
        assertEquals(SnackJarPresentation.Effect.BORROW, SnackJarPresentation.transition(before, after, 0))
        assertEquals(SnackJarPresentation.Effect.NONE, SnackJarPresentation.transition(after, after, 0))
    }

    private val parcel = SnackJarPresentation.Parcel("morning", 20, 8, 1000)

    @Test fun elapsedTimestampAloneDoesNotMeanParcelWasApplied() {
        val pending = snapshot().copy(parcels = listOf(parcel))
        assertEquals(SnackJarPresentation.Effect.NONE, SnackJarPresentation.transition(pending, pending, 2000))
    }

    @Test fun removedParcelNeedsAppliedTargetAsWellAsEffectiveTime() {
        val pending = snapshot().copy(parcels = listOf(parcel))
        val applied = snapshot().copy(activeLimits = mapOf("morning" to (20 to 8)))
        assertEquals(SnackJarPresentation.Effect.DELIVERED, SnackJarPresentation.transition(pending, applied, 1000))
        assertEquals(SnackJarPresentation.Effect.RETURNED, SnackJarPresentation.transition(pending, snapshot(), 2000))
        assertEquals(SnackJarPresentation.Effect.RETURNED, SnackJarPresentation.transition(pending, applied, 999))
    }

    @Test fun renamedPendingSegmentCanStillReceiveItsParcel() {
        val pending = snapshot().copy(parcels = listOf(parcel.copy(targetSegmentName = "新上午")))
        val applied = snapshot().copy(activeLimits = mapOf("新上午" to (20 to 8)))
        assertEquals(SnackJarPresentation.Effect.DELIVERED, SnackJarPresentation.transition(pending, applied, 1000))
    }

    @Test fun stricterConfigurationShrinksWithoutPretendingConsumptionIsARuleChange() {
        assertEquals(SnackJarPresentation.Effect.NONE,
            SnackJarPresentation.transition(snapshot(), snapshot(quota(2.0)), 0))
        assertEquals(SnackJarPresentation.Effect.STRICTER,
            SnackJarPresentation.transition(snapshot(), snapshot(quota(total = 8.0)), 0))
    }

    @Test fun tapDebounceAndFiveAcceptedTapsDoNotCountRejectedTouches() {
        val taps = SnackJarPresentation.Taps()
        assertEquals(SnackJarPresentation.Reaction.TAP, taps.accept(0))
        assertNull(taps.accept(100))
        assertEquals(SnackJarPresentation.Reaction.TAP, taps.accept(200))
        taps.accept(400)
        taps.accept(600)
        assertEquals(SnackJarPresentation.Reaction.SURPRISE, taps.accept(800))
        assertEquals(SnackJarPresentation.Reaction.TAP, taps.accept(3000))
    }

    @Test fun alarmMascotOnlyAppearsWhileWaitingOrCompleted() {
        assertEquals(SnackJarPresentation.AlarmPose.NONE, SnackJarPresentation.alarmPose(false, false, false))
        assertEquals(SnackJarPresentation.AlarmPose.WAKING, SnackJarPresentation.alarmPose(true, false, false))
        assertEquals(SnackJarPresentation.AlarmPose.BRUSHING, SnackJarPresentation.alarmPose(false, true, false))
        assertEquals(SnackJarPresentation.AlarmPose.HAPPY, SnackJarPresentation.alarmPose(false, false, true))
        assertEquals(SnackJarPresentation.AlarmPose.WAKING, SnackJarPresentation.alarmPose(true, false, true))
    }
}
