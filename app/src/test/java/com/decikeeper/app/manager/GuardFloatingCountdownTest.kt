package com.decikeeper.app.manager

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GuardFloatingCountdownTest {

    private val now = 1_000_000L

    @Test
    fun activeItemsUsePrivilegeTemporaryUnlockRestPriority() {
        val items = GuardFloatingCountdown.activeItems(
            nowMs = now,
            privilegeEndMs = now + 10_000L,
            temporaryUnlockEndMs = now + 20_000L,
            restEndMs = now + 30_000L
        )

        assertEquals(
            listOf(
                FloatingExit.PRIVILEGE,
                FloatingExit.TEMPORARY_UNLOCK,
                FloatingExit.REST
            ),
            items.map { it.exit }
        )
    }

    @Test
    fun collapsedTextUsesHighestPriorityActiveExitOnly() {
        val text = GuardFloatingCountdown.collapsedText(
            nowMs = now,
            privilegeEndMs = now + 2 * 60_000L,
            temporaryUnlockEndMs = now + 20_000L,
            restEndMs = now + 30_000L
        )

        assertEquals("2:00", text)
    }

    @Test
    fun collapsedTextHidesExactDurationUntilLastHour() {
        assertEquals(
            ">1h",
            GuardFloatingCountdown.collapsedText(
                nowMs = now,
                privilegeEndMs = now + 3_600_001L
            )
        )
        assertEquals(
            "60:00",
            GuardFloatingCountdown.collapsedText(
                nowMs = now,
                privilegeEndMs = now + 3_600_000L
            )
        )
        assertEquals(
            "59:59",
            GuardFloatingCountdown.collapsedText(
                nowMs = now,
                privilegeEndMs = now + 3_599_000L
            )
        )
    }

    @Test
    fun expandedItemsShowAllActiveExitsWithExactDuration() {
        val items = GuardFloatingCountdown.expandedItems(
            nowMs = now,
            privilegeEndMs = now + 3_720_000L,
            temporaryUnlockEndMs = now + 65_000L,
            restEndMs = now + 30_000L
        )

        assertEquals(
            listOf("特权" to "1:02:00", "临时解锁" to "1:05", "休息" to "0:30"),
            items.map { it.label to it.text }
        )
    }

    @Test
    fun noActiveExitReturnsShieldFallback() {
        assertEquals(
            null,
            GuardFloatingCountdown.collapsedText(nowMs = now)
        )
        assertTrue(GuardFloatingCountdown.expandedItems(nowMs = now).isEmpty())
    }

    @Test
    fun expiredExitIsRemovedAndSubsecondRemainderRoundsUp() {
        val items = GuardFloatingCountdown.activeItems(
            nowMs = now,
            privilegeEndMs = now,
            temporaryUnlockEndMs = now + 1L,
            restEndMs = now - 1L
        )

        assertEquals(listOf(FloatingExit.TEMPORARY_UNLOCK), items.map { it.exit })
        assertEquals(1L, items.single().remainingSeconds)
        assertEquals("0:01", items.single().text)
    }
}
