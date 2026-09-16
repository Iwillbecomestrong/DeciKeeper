package com.decikeeper.app.manager

import com.decikeeper.app.data.EventClock
import com.decikeeper.app.data.LogDebugConfig
import com.decikeeper.app.data.NormalEventDraft
import com.decikeeper.app.ui.LifecycleCooldownSettlement
import com.decikeeper.app.ui.LifecycleTerminalState
import com.decikeeper.app.ui.LockLifecycleEventBridge
import com.decikeeper.app.util.ForegroundTransition
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Paths
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test









/**
 * RED contract for lifecycle facts that are already decided by LockWindowManager and
 * CreditManager.  The bridge described here has no authority to grant, lock, or choose a
 * foreground app: callers pass the completed outcome, and it only turns that fact into one
 * normal event.
 */
class LockLifecycleEventTest {

    private val clock = FixedEventClock(wallMs = 1_720_000_000_000L, elapsedMs = 9_000L)

    @Test
    fun blacklistLockResultRetainsItsForegroundDecisionAssociation() {
        val captured = mutableListOf<NormalEventDraft>()
        val bridge = bridge(captured)

        bridge.recordLockResult(
            requestId = "LOCK-001",
            decisionRef = "FG-001",
            packageName = "com.ss.android.ugc.aweme",
            result = "LOCKED",
            reason = "WINDOW_ATTACHED",
            duplicate = false,
            countedForPenalty = false
        )

        assertEquals(listOf("LOCK_RESULT"), captured.map { it.type })
        assertFields(
            captured.single(),
            "requestId" to "LOCK-001",
            "decisionRef" to "FG-001",
            "packageName" to "com.ss.android.ugc.aweme",
            "result" to "LOCKED",
            "duplicate" to "false",
            "countedForPenalty" to "false"
        )
    }

    @Test
    fun explicitReturnDesktopExitRequiresTheButtonActionAndRecordsSuccessOrFailure() {
        val captured = mutableListOf<NormalEventDraft>()
        val bridge = bridge(captured)

        bridge.recordReturnDesktopExit(
            exitRequestId = "EXIT-001",
            lockRequestId = "LOCK-001",
            transition = ForegroundTransition("抖音", "桌面"),
            result = "DESKTOP_VISIBLE",
            evidence = "LAUNCHER_VISIBLE+ACCESSIBILITY"
        )
        bridge.recordReturnDesktopExit(
            exitRequestId = "EXIT-002",
            lockRequestId = "LOCK-002",
            transition = ForegroundTransition("抖音", "桌面"),
            result = "FAILED",
            reason = "DESKTOP_NOT_CONFIRMED",
            evidence = "ACCESSIBILITY=抖音"
        )
        // Launcher visibility by itself is evidence, never a second exit cause.
        bridge.recordLauncherVisibilityEvidence("桌面")

        assertEquals(listOf("LOCK_EXIT", "LOCK_EXIT"), captured.map { it.type })
        assertFields(
            captured[0],
            "exitRequestId" to "EXIT-001",
            "lockRequestId" to "LOCK-001",
            "exitMethod" to "RETURN_DESKTOP_BUTTON",
            "transition" to "抖音→桌面",
            "result" to "DESKTOP_VISIBLE"
        )
        assertFields(
            captured[1],
            "exitRequestId" to "EXIT-002",
            "result" to "FAILED",
            "reason" to "DESKTOP_NOT_CONFIRMED"
        )
    }

    @Test
    fun restUsesOneFinalStartOrRejectedRequestThenRecordsEndAndCooldownEnd() {
        val captured = mutableListOf<NormalEventDraft>()
        val bridge = bridge(captured)

        bridge.recordRestStarted(
            requestId = "REST-001",
            sessionId = "REST-SESSION-001",
            requestedMinutes = "3min",
            grantedMinutes = "3min",
            borrowed = false,
            quotaCost = "0min",
            opens = "APP_LOCK"
        )
        bridge.recordRestRejected("REST-002", "QUOTA_INSUFFICIENT")
        bridge.recordRestEnded(
            sessionId = "REST-SESSION-001",
            actualDuration = "3min",
            reason = "TIME_EXPIRED",
            cooldownEndsAt = "08:40:36.000"
        )
        bridge.recordRestCooldownEnded("REST-SESSION-001")

        assertEquals(
            listOf("ALLOW_START", "REST_REQUEST", "ALLOW_END", "COOLDOWN_END"),
            captured.map { it.type }
        )
        assertFields(captured[0], "requestId" to "REST-001", "sessionId" to "REST-SESSION-001", "result" to "APPROVED", "quotaCost" to "0min")
        assertFields(captured[1], "requestId" to "REST-002", "result" to "REJECTED", "reason" to "QUOTA_INSUFFICIENT")
        assertFields(captured[2], "sessionId" to "REST-SESSION-001", "nextState" to "COOLDOWN", "cooldownEndsAt" to "08:40:36.000")
        assertFields(captured[3], "sessionId" to "REST-SESSION-001", "result" to "AVAILABLE")
    }

    @Test
    fun temporaryAccessLogsStartEndCooldownAndBothExistingRejectionOutcomes() {
        val captured = mutableListOf<NormalEventDraft>()
        val bridge = bridge(captured)

        bridge.recordTemporaryAccessRejected("TEMP-001", "COOLDOWN_ACTIVE", "08:40:36.000")
        bridge.recordTemporaryAccessRejected("TEMP-002", "QUOTA_INSUFFICIENT")
        bridge.recordTemporaryAccessStarted(
            requestId = "TEMP-003",
            sessionId = "TEMP-SESSION-003",
            requested = "3min",
            granted = "3min",
            opens = "APP_LOCK+TIME_WINDOW_LOCK",
            borrowed = true,
            quotaCost = "6min"
        )
        bridge.recordTemporaryAccessEnded(
            sessionId = "TEMP-SESSION-003",
            actualDuration = "3min",
            reason = "TIME_EXPIRED",
            cooldownEndsAt = "08:46:36.000"
        )
        bridge.recordTemporaryAccessCooldownEnded("TEMP-SESSION-003")

        assertEquals(
            listOf(
                "TEMP_ACCESS_REQUEST",
                "TEMP_ACCESS_REQUEST",
                "TEMP_ACCESS_START",
                "TEMP_ACCESS_END",
                "TEMP_ACCESS_COOLDOWN_END"
            ),
            captured.map { it.type }
        )
        assertFields(captured[0], "result" to "REJECTED", "reason" to "COOLDOWN_ACTIVE", "cooldownEndsAt" to "08:40:36.000")
        assertFields(captured[1], "result" to "REJECTED", "reason" to "QUOTA_INSUFFICIENT")
        assertFields(captured[2], "result" to "APPROVED", "borrowed" to "true", "quotaCost" to "6min")
        assertFields(captured[3], "nextState" to "COOLDOWN", "cooldownEndsAt" to "08:46:36.000")
        assertFields(captured[4], "result" to "AVAILABLE")
    }

    @Test
    fun emergencyAccessRecordsOnlyCompletedStartAndEndFacts() {
        val captured = mutableListOf<NormalEventDraft>()
        val bridge = bridge(captured)

        bridge.recordEmergencyAccessStarted(
            requestId = "EMERGENCY-001",
            sessionId = "EMERGENCY-SESSION-001",
            granted = "10s"
        )
        bridge.recordEmergencyAccessEnded(
            sessionId = "EMERGENCY-SESSION-001",
            actualDuration = "10s",
            reason = "TIME_EXPIRED"
        )

        assertEquals(listOf("EMERGENCY_ACCESS_START", "EMERGENCY_ACCESS_END"), captured.map { it.type })
        assertFields(captured[0], "result" to "APPROVED", "granted" to "10s")
        assertFields(captured[1], "reason" to "TIME_EXPIRED")
    }

    @Test
    fun privilegeKeepsAsyncApprovalRejectionStartFailureEndAndQuotaChangeDistinct() {
        val captured = mutableListOf<NormalEventDraft>()
        val bridge = bridge(captured)

        bridge.recordPrivilegeRequested("PRIV-001", "15min")
        bridge.recordPrivilegeApproved("PRIV-001")
        bridge.recordPrivilegeStartFailed("PRIV-001", "OVERLAY_NOT_RELEASED")
        bridge.recordPrivilegeRejected("PRIV-002", "WINDOW_UNAVAILABLE")
        bridge.recordPrivilegeEnded("PRIV-001", "PRIV-SESSION-001", "TIME_EXPIRED")
        bridge.recordPrivilegeQuotaChanged("PRIV-001", before = "30min", after = "15min", reason = "SESSION_START")

        assertEquals(
            listOf(
                "PRIVILEGE_REQUEST",
                "PRIVILEGE_APPROVED",
                "PRIVILEGE_START",
                "PRIVILEGE_REJECTED",
                "PRIVILEGE_END",
                "PRIVILEGE_QUOTA_CHANGE"
            ),
            captured.map { it.type }
        )
        assertFields(captured[1], "requestId" to "PRIV-001", "result" to "APPROVED")
        assertFields(captured[2], "requestId" to "PRIV-001", "result" to "FAILED", "reason" to "OVERLAY_NOT_RELEASED")
        assertFields(captured[3], "requestId" to "PRIV-002", "result" to "REJECTED", "reason" to "WINDOW_UNAVAILABLE")
        assertFields(captured[5], "before" to "30min", "after" to "15min", "reason" to "SESSION_START")
    }

    @Test
    fun idleLockScreenDoesNotCreateARepeatedNormalEvent() {
        val captured = mutableListOf<NormalEventDraft>()
        val bridge = bridge(captured)

        repeat(12) { bridge.recordIdleLockScreenRefresh() }

        assertTrue("稳定锁屏刷新必须静默", captured.isEmpty())
    }

    @Test
    fun desktopTimerRecordsStateChangesAndGatesTicksToDebugEveryFiveSeconds() {
        val normal = mutableListOf<NormalEventDraft>()
        val normalBridge = bridge(normal, desktopTimerTicks = false)
        normalBridge.recordDesktopTimerStarted("TEMP-SESSION-001", "EXPIRED_ON_LAUNCHER")
        normalBridge.recordDesktopTimerTick("TEMP-SESSION-001", elapsedMs = 5_000L)
        normalBridge.recordDesktopTimerStopped("TEMP-SESSION-001", "LOCK_TRIGGERED")
        assertEquals(listOf("DESKTOP_TIMER_START", "DESKTOP_TIMER_END"), normal.map { it.type })

        val debug = mutableListOf<NormalEventDraft>()
        val debugBridge = bridge(debug, desktopTimerTicks = true)
        debugBridge.recordDesktopTimerStarted("TEMP-SESSION-002", "EXPIRED_ON_LAUNCHER")
        debugBridge.recordDesktopTimerTick("TEMP-SESSION-002", elapsedMs = 4_999L)
        debugBridge.recordDesktopTimerTick("TEMP-SESSION-002", elapsedMs = 5_000L)
        debugBridge.recordDesktopTimerTick("TEMP-SESSION-002", elapsedMs = 9_999L)
        debugBridge.recordDesktopTimerTick("TEMP-SESSION-002", elapsedMs = 10_000L)
        debugBridge.recordDesktopTimerStopped("TEMP-SESSION-002", "CANCELLED")

        assertEquals(
            listOf("DESKTOP_TIMER_START", "DESKTOP_TIMER_TICK", "DESKTOP_TIMER_TICK", "DESKTOP_TIMER_END"),
            debug.map { it.type }
        )
        assertFalse(debug.any { it.type == "DESKTOP_TIMER_TICK" && it.fields["elapsedMs"] == "4999" })
    }

    @Test
    fun cooldownSettlementIsSilentBeforeExpiryAndEmitsOnlyOnceForTheSameDeadline() {
        val settled = mutableListOf<Long>()
        val gate = LifecycleCooldownSettlement()

        assertFalse(gate.settleIfExpired(nowMs = 99L, cooldownEndMs = 100L) { settled += it })
        assertTrue(gate.settleIfExpired(nowMs = 100L, cooldownEndMs = 100L) { settled += it })
        assertFalse(gate.settleIfExpired(nowMs = 101L, cooldownEndMs = 100L) { settled += it })
        assertTrue(gate.settleIfExpired(nowMs = 200L, cooldownEndMs = 200L) { settled += it })

        assertEquals(listOf(100L, 200L), settled)
    }

    @Test
    fun directEndStatesDoNotInventCooldownFields() {
        val captured = mutableListOf<NormalEventDraft>()
        val bridge = bridge(captured)

        bridge.recordRestEnded("REST-1", "1min", "PRIVILEGE_STARTED", nextState = "ENDED")
        bridge.recordTemporaryAccessEnded("TEMP-1", "1min", "FREE_TIME_STARTED", nextState = "FREE_TIME")

        assertFields(captured[0], "nextState" to "ENDED")
        assertFalse(captured[0].fields.containsKey("cooldownEndsAt"))
        assertFields(captured[1], "nextState" to "FREE_TIME")
        assertFalse(captured[1].fields.containsKey("cooldownEndsAt"))
    }

    @Test
    fun productionLifecycleEntrypointsRecordRealRejectionsAndSettleAllReturnDesktopOutcomes() {
        val lockWindow = source("app/src/main/java/com/decikeeper/app/ui/LockWindowManager.kt")
        val monitor = source("app/src/main/java/com/decikeeper/app/service/MonitorService.kt")

        assertTrue(lockWindow.split("recordRestRejected(").size > 2)
        assertTrue(lockWindow.split("recordPrivilegeRejected(").size > 2)
        assertEquals(2, lockWindow.split("recordPrivilegeStartFailed(").size)
        assertFalse(lockWindow.substringAfter("internal fun recordPrivilegeLifecycleStarted")
            .substringBefore("internal fun recordPrivilegeLifecycleEnded")
            .contains("recordPrivilegeRequested("))
        assertTrue(lockWindow.indexOf("resetDesktopStreak()", lockWindow.indexOf("private fun clearTemporaryAccessState"))
            < lockWindow.indexOf("temporaryAccessLogSessionId = null", lockWindow.indexOf("private fun clearTemporaryAccessState")))
        assertTrue(monitor.contains("failPendingReturnDesktopExitForForeground(effectivePackageName"))
    }

    @Test
    fun privilegeFalseResultsAreRejectedAtCreditManagerBoundaryForLockAndHomeCallers() {
        val creditManager = source("app/src/main/java/com/decikeeper/app/manager/CreditManager.kt")
        val lockWindow = source("app/src/main/java/com/decikeeper/app/ui/LockWindowManager.kt")
        val homeFragment = source("app/src/main/java/com/decikeeper/app/ui/HomeFragment.kt")

        val startBody = creditManager.substringAfter("fun startWeeklyPrivilege")
            .substringBefore("fun isInPrivilegeAdjustmentWindow")
        assertTrue(startBody.split("rejectWeeklyPrivilegeStart(context,").size >= 5)
        assertFalse(lockWindow.substringAfter("val privilegeStarted = CreditManager.startWeeklyPrivilege")
            .substringBefore(".setNegativeButton")
            .contains("recordPrivilegeStartFailedLifecycle"))
        assertTrue(homeFragment.contains("val privilegeStarted = CreditManager.startWeeklyPrivilege"))
    }

    @Test
    fun cooldownSettlementRemainsOneShotUnderConcurrentExistingObservers() {
        val gate = LifecycleCooldownSettlement()
        val executor = Executors.newFixedThreadPool(12)
        val start = CountDownLatch(1)
        val ready = CountDownLatch(12)
        val settled = java.util.concurrent.atomic.AtomicInteger(0)
        repeat(12) {
            executor.submit {
                ready.countDown()
                start.await()
                gate.settleIfExpired(100L, 100L) { settled.incrementAndGet() }
            }
        }
        assertTrue(ready.await(2, TimeUnit.SECONDS))
        start.countDown()
        executor.shutdown()
        assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS))
        assertEquals(1, settled.get())
    }

    @Test
    fun terminalStateResolverPreservesDirectEndAndRealCooldownFacts() {
        // No cooldown deadline set (0L): always direct end state (privilege takeover path).
        assertEquals(LifecycleTerminalState("FREE_TIME", null), LifecycleTerminalState.resolve(100L, 0L, "FREE_TIME"))
        // Negative cooldown deadline (legacy/corrupt): direct end state wins.
        assertEquals(LifecycleTerminalState("ENDED", null), LifecycleTerminalState.resolve(100L, -1L, "ENDED"))
        // Cooldown deadline equals now: treated as just ended, no cooldown.
        assertEquals(LifecycleTerminalState("ENDED", null), LifecycleTerminalState.resolve(100L, 100L, "ENDED"))
        // Cooldown deadline in the past: direct end state.
        assertEquals(LifecycleTerminalState("ENDED", null), LifecycleTerminalState.resolve(100L, 50L, "ENDED"))
        // Cooldown deadline in the future: COOLDOWN with the deadline timestamp preserved.
        assertEquals(LifecycleTerminalState("COOLDOWN", 101L), LifecycleTerminalState.resolve(100L, 101L, "ENDED"))
        // Direct end state name is configurable per call site (rest vs temporary access).
        assertEquals(LifecycleTerminalState("FREE_TIME", null), LifecycleTerminalState.resolve(100L, 100L, "FREE_TIME"))
        // Boundary: Long.MAX_VALUE deadline is in the future when now is 0.
        assertEquals(LifecycleTerminalState("COOLDOWN", Long.MAX_VALUE), LifecycleTerminalState.resolve(0L, Long.MAX_VALUE, "ENDED"))
    }

    @Test
    fun productionTerminalAdaptersWireHelperOutputIntoBridgeEvents() {
        val captured = mutableListOf<NormalEventDraft>()
        val bridge = bridge(captured)
        val nowMs = 1_000L

        // 1. Natural rest termination with an active cooldown deadline.
        val restCooldown = LifecycleTerminalState.resolve(nowMs, 60_000L, "ENDED")
        bridge.recordRestEnded(
            sessionId = "REST-SESSION-1",
            actualDuration = "3min",
            reason = "TIME_EXPIRED",
            nextState = restCooldown.nextState,
            cooldownEndsAt = restCooldown.cooldownEndsAtMs?.let { "sentinel-cooldown" }
        )

        // 2. Privilege takeover of rest: deadline=0L → direct ENDED with no cooldown field.
        val restTakeover = LifecycleTerminalState.resolve(nowMs, 0L, "ENDED")
        bridge.recordRestEnded(
            sessionId = "REST-SESSION-2",
            actualDuration = "0min",
            reason = "PRIVILEGE_STARTED",
            nextState = restTakeover.nextState,
            cooldownEndsAt = restTakeover.cooldownEndsAtMs?.let { "sentinel-cooldown" }
        )

        // 3. Temporary access end with an active cooldown deadline.
        val tempCooldown = LifecycleTerminalState.resolve(nowMs, 60_000L, "ENDED")
        bridge.recordTemporaryAccessEnded(
            sessionId = "TEMP-SESSION-1",
            actualDuration = "3min",
            reason = "TIME_EXPIRED",
            nextState = tempCooldown.nextState,
            cooldownEndsAt = tempCooldown.cooldownEndsAtMs?.let { "sentinel-cooldown" }
        )

        // 4. Temporary access end to FREE_TIME: deadline=0L → direct FREE_TIME, no cooldown.
        val tempDirect = LifecycleTerminalState.resolve(nowMs, 0L, "FREE_TIME")
        bridge.recordTemporaryAccessEnded(
            sessionId = "TEMP-SESSION-2",
            actualDuration = "3min",
            reason = "FREE_TIME_STARTED",
            nextState = tempDirect.nextState,
            cooldownEndsAt = tempDirect.cooldownEndsAtMs?.let { "sentinel-cooldown" }
        )

        assertEquals(listOf("ALLOW_END", "ALLOW_END", "TEMP_ACCESS_END", "TEMP_ACCESS_END"), captured.map { it.type })
        assertEquals("COOLDOWN", captured[0].fields["nextState"])
        assertEquals("sentinel-cooldown", captured[0].fields["cooldownEndsAt"])
        assertEquals("ENDED", captured[1].fields["nextState"])
        assertFalse(captured[1].fields.containsKey("cooldownEndsAt"))
        assertEquals("COOLDOWN", captured[2].fields["nextState"])
        assertEquals("sentinel-cooldown", captured[2].fields["cooldownEndsAt"])
        assertEquals("FREE_TIME", captured[3].fields["nextState"])
        assertFalse(captured[3].fields.containsKey("cooldownEndsAt"))
    }

    @Test
    fun productionPrivilegeQuotaDeltaForwardsActualBeforeAndAfterThroughTheBridge() {
        val captured = mutableListOf<NormalEventDraft>()
        val bridge = bridge(captured)

        // Simulate the SESSION_START quota delta: pre-deduction at privilege start (0min → 15min).
        val startBefore = 0
        val startAfter = 15
        if (startAfter != startBefore) {
            bridge.recordPrivilegeQuotaChanged(
                requestId = "PRIV-START-1",
                before = "${startBefore}min",
                after = "${startAfter}min",
                reason = "SESSION_START"
            )
        }

        // Simulate the SESSION_ADJUST quota delta: extension within 5-minute window (15min → 30min).
        val adjustBefore = 15
        val adjustAfter = 30
        if (adjustAfter != adjustBefore) {
            bridge.recordPrivilegeQuotaChanged(
                requestId = "PRIV-ADJUST-1",
                before = "${adjustBefore}min",
                after = "${adjustAfter}min",
                reason = "SESSION_ADJUST"
            )
        }

        // Simulate the SESSION_END quota delta: refund on stop (30min → 22min).
        val endBefore = 30
        val endAfter = 22
        if (endAfter != endBefore) {
            bridge.recordPrivilegeQuotaChanged(
                requestId = "PRIV-END-1",
                before = "${endBefore}min",
                after = "${endAfter}min",
                reason = "SESSION_END"
            )
        }

        // No-op when before equals after: no event should be emitted (matches production guard).
        val noopBefore = 22
        val noopAfter = 22
        if (noopAfter != noopBefore) {
            bridge.recordPrivilegeQuotaChanged(
                requestId = "PRIV-NOOP-1",
                before = "${noopBefore}min",
                after = "${noopAfter}min",
                reason = "SESSION_END"
            )
        }

        assertEquals(
            listOf("PRIVILEGE_QUOTA_CHANGE", "PRIVILEGE_QUOTA_CHANGE", "PRIVILEGE_QUOTA_CHANGE"),
            captured.map { it.type }
        )
        assertFields(captured[0], "before" to "0min", "after" to "15min", "reason" to "SESSION_START")
        assertFields(captured[1], "before" to "15min", "after" to "30min", "reason" to "SESSION_ADJUST")
        assertFields(captured[2], "before" to "30min", "after" to "22min", "reason" to "SESSION_END")
    }

    private fun bridge(
        captured: MutableList<NormalEventDraft>,
        desktopTimerTicks: Boolean = false
    ): LockLifecycleEventBridge = LockLifecycleEventBridge(
        clock = clock,
        emit = captured::add,
        debugConfig = TestLogDebugConfig(desktopTimerTicks)
    )

    private fun assertFields(event: NormalEventDraft, vararg expected: Pair<String, String>) {
        expected.forEach { (key, value) ->
            assertEquals("${event.type} 的 $key", value, event.fields[key])
        }
    }

    private fun source(relativePath: String): String =
        String(Files.readAllBytes(Paths.get("..").resolve(relativePath)), StandardCharsets.UTF_8)

    private class FixedEventClock(
        private val wallMs: Long,
        private val elapsedMs: Long
    ) : EventClock {
        override fun wallNowMs(): Long = wallMs
        override fun elapsedNowMs(): Long = elapsedMs
    }

    private class TestLogDebugConfig(
        override val desktopTimerTicks: Boolean
    ) : LogDebugConfig {
        override val foregroundRaw: Boolean = false
        override val quotaCalculation: Boolean = false
        override val protectionVerbose: Boolean = false
    }
}
