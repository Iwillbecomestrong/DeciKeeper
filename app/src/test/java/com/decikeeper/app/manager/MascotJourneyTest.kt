package com.decikeeper.app.manager

import com.decikeeper.app.manager.MascotJourney.Segment
import com.decikeeper.app.manager.MascotJourney.State
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 团团赶路的进度计算：等待 / 行进 / 到达 / 无时段，含跨午夜与重叠。纯 JVM。 */
class MascotJourneyTest {

    private fun hm(h: Int, m: Int = 0) = h * 60 + m
    private val evening = Segment(hm(20), hm(23))          // 20:00-23:00，共 180 分钟
    private val overnight = Segment(hm(23), hm(2))         // 23:00-02:00（跨午夜），共 180 分钟

    private fun now(h: Int, m: Int = 0, s: Int = 0) = h * 60.0 + m + s / 60.0

    @Test
    fun noSegmentsMeansNone() {
        assertEquals(State.None, MascotJourney.compute(now(12), emptyList()))
    }

    @Test
    fun waitingCountsMinutesUntilNextStart() {
        val state = MascotJourney.compute(now(19, 30), listOf(evening))
        assertEquals(State.Waiting(30), state)
    }

    @Test
    fun waitingRoundsPartialMinutesUp() {
        val state = MascotJourney.compute(now(19, 59, 30), listOf(evening))
        assertEquals(State.Waiting(1), state)
    }

    @Test
    fun waitingWrapsToTomorrowWhenTodaysSegmentsAreOver() {
        // 23:30 之后 20:00 的时段已结束 15 分钟以上，等明天 20:00 开始
        val state = MascotJourney.compute(now(23, 30), listOf(evening))
        assertEquals(State.Waiting(hm(20) + 30), state)
    }

    @Test
    fun walkingStartsAtZeroAndReportsRemaining() {
        val state = MascotJourney.compute(now(20), listOf(evening)) as State.Walking
        assertEquals(0f, state.progress, 0.0001f)
        assertEquals(180, state.minutesLeft)
    }

    @Test
    fun walkingHalfwayIsHalf() {
        val state = MascotJourney.compute(now(21, 30), listOf(evening)) as State.Walking
        assertEquals(0.5f, state.progress, 0.0001f)
        assertEquals(90, state.minutesLeft)
    }

    @Test
    fun segmentEndIsExclusiveSoItArrivesAtTheEndMinute() {
        val state = MascotJourney.compute(now(23), listOf(evening))
        assertEquals(State.Arrived(0), state)
    }

    @Test
    fun arrivedLastsOnlyTheCheerWindow() {
        val justBefore = MascotJourney.compute(now(23, MascotJourney.ARRIVED_WINDOW_MINUTES - 1), listOf(evening))
        assertTrue(justBefore is State.Arrived)
        val after = MascotJourney.compute(now(23, MascotJourney.ARRIVED_WINDOW_MINUTES), listOf(evening))
        assertTrue(after is State.Waiting)
    }

    @Test
    fun crossMidnightSegmentWalksOnBothSidesOfMidnight() {
        val before = MascotJourney.compute(now(23, 30), listOf(overnight)) as State.Walking
        assertEquals(30f / 180f, before.progress, 0.0001f)
        val after = MascotJourney.compute(now(0, 30), listOf(overnight)) as State.Walking
        assertEquals(90f / 180f, after.progress, 0.0001f)
        assertEquals(90, after.minutesLeft)
    }

    @Test
    fun crossMidnightSegmentArrivesAfterItsEnd() {
        val state = MascotJourney.compute(now(2, 5), listOf(overnight))
        assertEquals(State.Arrived(5), state)
    }

    @Test
    fun walkingBeatsArrivedWhenAnotherSegmentIsRunning() {
        val morning = Segment(hm(9), hm(10))
        val state = MascotJourney.compute(now(10, 5), listOf(morning, Segment(hm(10), hm(12))))
        assertTrue(state is State.Walking)
    }

    @Test
    fun overlappingSegmentsFollowTheOneThatEndsLatest() {
        val short = Segment(hm(20), hm(21))
        val long = Segment(hm(20), hm(23))
        val state = MascotJourney.compute(now(20, 30), listOf(short, long)) as State.Walking
        assertEquals(30f / 180f, state.progress, 0.0001f)
        assertEquals(150, state.minutesLeft)
    }

    @Test
    fun nextSegmentIsTheNearestOne() {
        val state = MascotJourney.compute(now(8), listOf(evening, Segment(hm(12), hm(13))))
        assertEquals(State.Waiting(240), state)
    }

    @Test
    fun durationHandlesCrossMidnightAndFullDay() {
        assertEquals(180, overnight.durationMinutes)
        assertEquals(1440, Segment(hm(6), hm(6)).durationMinutes)
    }

    @Test
    fun captionsReadNaturally() {
        assertEquals("", MascotJourney.caption(State.None))
        assertEquals("团团在打盹，30 分钟后出发", MascotJourney.caption(State.Waiting(30)))
        assertEquals("团团在赶路，还剩 1 小时 30 分", MascotJourney.caption(State.Walking(0.5f, 90)))
        assertEquals("到啦！这一段守住了", MascotJourney.caption(State.Arrived(2)))
    }

    @Test
    fun formatMinutesHandlesHoursAndRemainders() {
        assertEquals("45 分钟", MascotJourney.formatMinutes(45))
        assertEquals("2 小时", MascotJourney.formatMinutes(120))
        assertEquals("1 小时 5 分", MascotJourney.formatMinutes(65))
        assertEquals("0 分钟", MascotJourney.formatMinutes(-3))
    }
}
