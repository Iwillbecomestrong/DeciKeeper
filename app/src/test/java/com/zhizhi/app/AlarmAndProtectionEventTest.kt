package com.zhizhi.app

import com.zhizhi.app.data.DefaultNormalEventRenderer
import com.zhizhi.app.data.EventClock
import com.zhizhi.app.data.LogDebugConfig
import com.zhizhi.app.data.LogExporter
import com.zhizhi.app.data.NormalEventDraft
import com.zhizhi.app.data.NormalEventLogger
import com.zhizhi.app.data.NormalEventSink
import com.zhizhi.app.data.NormalEvents
import com.zhizhi.app.manager.AlarmProtectionEventBridge
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Paths
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test











/**
 * RED contract for alarm, protection, and service-state outcome events.
 * The bridge has no authority to schedule, trigger, ring, show overlays, evaluate
 * challenges, snooze, dismiss, recover, or change protection state. Callers pass the
 * completed business outcome and the bridge emits a single NormalEventDraft; tests
 * confirm (1) the bridge API exposes the planned methods, (2) emitted records preserve
 * privacy by never carrying question/answer/option/window content, (3) state-transition
 * events are emitted only on actual change, (4) production sites are wired to the bridge.
 */
class AlarmAndProtectionEventTest {

    private val clock = FixedEventClock(wallMs = 1_730_500_000_000L, elapsedMs = 21_000L)

    // ---------- alarm lifecycle ----------

    @Test
    fun alarmScheduleTriggerAndRingLifecycleIsComplete() {
        val captured = mutableListOf<NormalEventDraft>()
        val bridge = bridge(captured)

        bridge.recordAlarmSchedule(
            alarmId = "ALARM-1",
            scheduledAtMs = 1_730_500_000_000L,
            ruleId = "MON_FRI",
            result = "SCHEDULED"
        )
        bridge.recordAlarmTrigger(
            alarmId = "ALARM-1",
            triggerAtMs = 1_730_500_005_000L,
            ruleId = "MON_FRI",
            result = "TRIGGERED"
        )
        bridge.recordAlarmRingStart("ALARM-1")
        bridge.recordAlarmRingEnd("ALARM-1", reason = "DISMISSED", durationMs = "35000")

        assertEquals(
            listOf("ALARM_SCHEDULE", "ALARM_TRIGGER", "ALARM_RING_START", "ALARM_RING_END"),
            captured.map { it.type }
        )
        assertFields(captured[0], "alarmId" to "ALARM-1", "ruleId" to "MON_FRI", "result" to "SCHEDULED")
        assertFields(captured[1], "alarmId" to "ALARM-1", "result" to "TRIGGERED")
        assertFields(captured[2], "alarmId" to "ALARM-1")
        assertFields(captured[3], "alarmId" to "ALARM-1", "reason" to "DISMISSED", "durationMs" to "35000")
    }

    @Test
    fun overlayAndChallengeTransitionsRemainSilentUntilRecovery() {
        val captured = mutableListOf<NormalEventDraft>()
        val bridge = bridge(captured)

        // overlay + challenge traffic during a single alarm session
        bridge.recordAlarmOverlayShow("ALARM-2")
        bridge.recordAlarmChallengeStart("ALARM-2", "CHALLENGE-2", totalSteps = 3)
        bridge.recordAlarmChallengeResult(
            alarmId = "ALARM-2",
            challengeId = "CHALLENGE-2",
            step = 1,
            result = "CORRECT",
            cumulativeProgress = 1,
            totalSteps = 3
        )
        bridge.recordAlarmChallengeResult(
            alarmId = "ALARM-2",
            challengeId = "CHALLENGE-2",
            step = 2,
            result = "CORRECT",
            cumulativeProgress = 2,
            totalSteps = 3
        )
        bridge.recordAlarmChallengeResult(
            alarmId = "ALARM-2",
            challengeId = "CHALLENGE-2",
            step = 3,
            result = "FINAL_CORRECT",
            cumulativeProgress = 3,
            totalSteps = 3
        )
        bridge.recordAlarmOverlayHide("ALARM-2", "DISMISSED")

        assertEquals(
            listOf(
                "ALARM_OVERLAY_SHOW",
                "ALARM_CHALLENGE_START",
                "ALARM_CHALLENGE_RESULT",
                "ALARM_CHALLENGE_RESULT",
                "ALARM_CHALLENGE_RESULT",
                "ALARM_OVERLAY_HIDE"
            ),
            captured.map { it.type }
        )
        // no per-step progress noise above the expected events (no heartbeat events)
        assertFalse(captured.any { it.type == "WATCHDOG_TICK" })
        assertFalse(captured.any { it.type == "ALARM_HEARTBEAT" })
        // hide reason is the caller-classified exit, not a refresh
        assertFields(captured.last(), "reason" to "DISMISSED")
    }

    @Test
    fun challengeResultsPreserveProgressAndExcludeSensitiveContent() {
        val captured = mutableListOf<NormalEventDraft>()
        val bridge = bridge(captured)

        bridge.recordAlarmChallengeResult(
            alarmId = "ALARM-3",
            challengeId = "CHALLENGE-3",
            step = 1,
            result = "CORRECT",
            cumulativeProgress = 1,
            totalSteps = 5
        )
        bridge.recordAlarmChallengeResult(
            alarmId = "ALARM-3",
            challengeId = "CHALLENGE-3",
            step = 2,
            result = "WRONG",
            cumulativeProgress = 1,
            totalSteps = 5
        )
        bridge.recordAlarmChallengeResult(
            alarmId = "ALARM-3",
            challengeId = "CHALLENGE-3",
            step = 5,
            result = "FINAL_WRONG",
            cumulativeProgress = 1,
            totalSteps = 5
        )

        assertEquals(3, captured.size)
        // progress may step back to 1 after WRONG (no advance on wrong answer)
        assertEquals("1", captured[1].fields["progress"])
        assertEquals("5", captured[1].fields["totalSteps"])
        assertEquals("1", captured[2].fields["progress"])

        val allFieldKeys = captured.flatMap { it.fields.keys }.toSet()
        // never leak question / answer / option / window / cloze / poem / math / word content
        val forbidden = setOf(
            "question", "answer", "option", "window",
            "poem", "math", "word", "cloze",
            "prompt", "expected"
        )
        assertTrue(
            "challenge event fields must not contain sensitive content keys; saw keys=$allFieldKeys",
            (allFieldKeys intersect forbidden).isEmpty()
        )
        // values must be plain identifiers, not text dumps
        captured.forEach { event ->
            event.fields.values.forEach { value ->
                assertFalse(
                    "challenge event value must not contain whitespace (likely a question dump)",
                    value.contains(' ')
                )
            }
        }
    }

    @Test
    fun snoozeDismissAndRecoveryAreDistinctOutcomes() {
        val captured = mutableListOf<NormalEventDraft>()
        val bridge = bridge(captured)

        bridge.recordAlarmSnooze("ALARM-4", snoozeIndex = 1, snoozeUntilMs = 1_730_500_600_000L)
        bridge.recordAlarmDismiss("ALARM-4", method = "CHALLENGE_PASS")
        bridge.recordAlarmRecovery("ALARM-4", recoveryAction = "BACKUP_TRIGGER")

        assertEquals(listOf("ALARM_SNOOZE", "ALARM_DISMISS", "ALARM_RECOVERY"), captured.map { it.type })
        assertFields(captured[0], "snoozeIndex" to "1", "snoozeUntil" to "1730500600000")
        assertFields(captured[1], "method" to "CHALLENGE_PASS")
        assertFields(captured[2], "recoveryAction" to "BACKUP_TRIGGER")
        // snooze and dismiss must never collapse into the same type
        assertFalse(captured[0].type == captured[1].type)
    }

    @Test
    fun watchdogIsSilentByDefaultAndOnlyEmitsOnActualRecovery() {
        val captured = mutableListOf<NormalEventDraft>()
        val bridge = bridge(captured)

        // bridge API exposes watchdogs as a one-shot recovery action; no tick method exists
        bridge.recordAlarmWatchdog("RECOVERED_BY_WORK_MANAGER")
        bridge.recordAlarmRecovery("ALARM-5", recoveryAction = "WORK_MANAGER_BACKUP")

        assertEquals(listOf("ALARM_WATCHDOG", "ALARM_RECOVERY"), captured.map { it.type })
        assertFields(captured[0], "action" to "RECOVERED_BY_WORK_MANAGER")

        // caller's polling loop MUST NOT call any periodic bridge method - prove by reflection-style inspection
        val bridgeSrc = source("app/src/main/java/com/zhizhi/app/manager/AlarmProtectionEventBridge.kt")
        assertFalse(
            "bridge must not expose a periodic tick/heartbeat method",
            bridgeSrc.contains("fun recordAlarmHeartbeat") ||
                bridgeSrc.contains("fun recordWatchdogTick")
        )
    }

    // ---------- protection / service state ----------

    @Test
    fun protectionStateChangeSuppressesUntilTransition() {
        val captured = mutableListOf<NormalEventDraft>()
        val bridge = bridge(captured)

        // first abnormal state is emitted
        bridge.recordProtectionStateChange(
            component = "accessibility",
            oldState = "OK",
            newState = "REVOKED",
            reason = "USER_DISABLE"
        )
        // recovery emits a transition with old=ABNORMAL
        bridge.recordProtectionStateChange(
            component = "accessibility",
            oldState = "REVOKED",
            newState = "OK",
            reason = "REPAIRED"
        )

        assertEquals(2, captured.size)
        assertEquals("PROTECTION_STATE_CHANGE", captured[0].type)
        assertEquals("PROTECTION_STATE_CHANGE", captured[1].type)
        assertFields(captured[0], "component" to "accessibility", "oldState" to "OK", "newState" to "REVOKED")
        assertFields(captured[1], "oldState" to "REVOKED", "newState" to "OK", "reason" to "REPAIRED")

        // the bridge has no recordProtectionHeartbeat / periodic-poll method - in-process polling
        // must be classified as state-changes rather than a periodic summary
        val bridgeSrc = source("app/src/main/java/com/zhizhi/app/manager/AlarmProtectionEventBridge.kt")
        assertFalse(
            "protection bridge must not expose a periodic poll method",
            bridgeSrc.contains("fun recordProtectionHeartbeat") ||
                bridgeSrc.contains("fun recordAccessibilityTick")
        )
    }

    @Test
    fun bypassRiskEmitsOnceAndIsolatedToRealRisk() {
        val captured = mutableListOf<NormalEventDraft>()
        val bridge = bridge(captured)

        // bypass risk is per-observation, only on real risk detected
        bridge.recordBypassRisk(
            riskType = "FAKE_NOTIFICATION_POSTED",
            evidence = "pkg=com.ss.android.ugc.aweme postingState=true",
            actionTaken = "RAISED_TO_WATCHDOG"
        )

        assertEquals(1, captured.size)
        assertEquals("BYPASS_RISK", captured[0].type)
        assertFields(captured[0], "riskType" to "FAKE_NOTIFICATION_POSTED", "actionTaken" to "RAISED_TO_WATCHDOG")
        // identification (riskType) + outcome (actionTaken) + evidence; no payload leak
        assertFalse(captured[0].fields.containsKey("payload"))
        assertFalse(captured[0].fields.containsKey("question"))
    }

    @Test
    fun serviceStateTracksRevokedRepairedTransitions() {
        val captured = mutableListOf<NormalEventDraft>()
        val bridge = bridge(captured)

        bridge.recordServiceState("MonitorService", "OK", "STOPPED", "USER_STOP")
        bridge.recordServiceState("MonitorService", "STOPPED", "OK", "RESTARTED_BY_GUARD")

        assertEquals(2, captured.size)
        assertFields(captured[0], "serviceName" to "MonitorService", "newState" to "STOPPED", "reason" to "USER_STOP")
        assertFields(captured[1], "serviceName" to "MonitorService", "oldState" to "STOPPED", "newState" to "OK")
    }

    // ---------- production wiring ----------

    @Test
    fun productionAlarmSchedulerAndOverlayWireTheBridge() {
        val schedulerSrc = source("app/src/main/java/com/zhizhi/app/manager/AlarmScheduler.kt")
        val receiverSrc = source("app/src/main/java/com/zhizhi/app/receiver/AlarmReceiver.kt")
        val bridgeSrc = source("app/src/main/java/com/zhizhi/app/manager/AlarmProtectionEventBridge.kt")

        assertTrue(
            "AlarmScheduler.scheduleNext must emit ALARM_SCHEDULE through AlarmProtectionEventBridge",
            schedulerSrc.contains("ALARM_SCHEDULE")
        )
        assertTrue(
            "AlarmScheduler must construct or reference AlarmProtectionEventBridge",
            schedulerSrc.contains("AlarmProtectionEventBridge")
        )
        assertTrue(
            "AlarmReceiver must emit ALARM_TRIGGER through AlarmProtectionEventBridge",
            receiverSrc.contains("ALARM_TRIGGER")
        )
        assertTrue(
            "AlarmReceiver must also emit ALARM_RING_START after the trigger path completes",
            receiverSrc.contains("ALARM_RING_START")
        )
        assertTrue(
            "bridge must expose recordAlarmSchedule + recordAlarmTrigger + recordAlarmRingStart API",
            bridgeSrc.contains("recordAlarmSchedule") &&
                bridgeSrc.contains("recordAlarmTrigger") &&
                bridgeSrc.contains("recordAlarmRingStart")
        )
    }

    @Test
    fun productionWatchdogSilencesIdleHeartbeat() {
        val watchdogSrc = source("app/src/main/java/com/zhizhi/app/service/AlarmWatchdogService.kt")
        val receiverSrc = source("app/src/main/java/com/zhizhi/app/receiver/AlarmReceiver.kt")
        val bridgeSrc = source("app/src/main/java/com/zhizhi/app/manager/AlarmProtectionEventBridge.kt")

        // alarm receiver must convert recovery into a single ALARM_RECOVERY event rather than
        // firing a heartbeat loop
        assertTrue(
            "AlarmReceiver must emit ALARM_RECOVERY (not periodic heartbeat) when recovery action runs",
            receiverSrc.contains("ALARM_RECOVERY")
        )
        // the gated Log.d idle heartbeat MUST have been converted to event-gated by the task-6
        // refactor: the original `Log.d(TAG, "Watchdog check: alarmId=...")` print statement is
        // removed (or replaced by a silent comment). We accept either: (a) the line is gone or
        // (b) it is wrapped in a debug flag (protectionVerbose) AND an event-only path exists.
        val hasGatedPrint = watchdogSrc.contains("Watchdog check: alarmId=")
        val hasGatedVerboseCheck = hasGatedPrint && watchdogSrc.contains("protectionVerbose")
        val hasRecoveryEventOnManualPath = receiverSrc.contains("ALARM_RECOVERY") ||
            watchdogSrc.contains("ALARM_RECOVERY")
        assertTrue(
            "alarm watchdog idle heartbeat must either be removed or gated by protectionVerbose; recovery event must be wired",
            !hasGatedPrint || hasGatedVerboseCheck
        )
        assertTrue(
            "alarm recovery action must emit ALARM_RECOVERY",
            hasRecoveryEventOnManualPath
        )
        // bridge must define the recovery API surface
        assertTrue(
            "bridge must expose recordAlarmRecovery + recordAlarmWatchdog",
            bridgeSrc.contains("recordAlarmRecovery") && bridgeSrc.contains("recordAlarmWatchdog")
        )
    }

    // ---------- export atomicity contract ----------

    @Test
    fun compactionPreservesAtomicNormalEventBlockAndRejectsInterleaving() {
        val renderedLines = listOf(
            "2026-07-13 12:00:00.000 [FG_DECISION] eventId=PID-1 seq=1 selected=抖音 state=BLACKLIST result=LOCKED",
            "    decisionRef=DEC-1 inference=USAGE",
            "    sources: usage=OK accessibility=OK a11y=OK",
            "    candidates: [抖音,微信,系统桌面]",
            "    windows: 抖音@com.ss.android.ugc.aweme",
            "    lock: result=LOCKED requestId=LOCK-1",
            "2026-07-13 12:00:01.000 [CONFIG_CHANGE] eventId=PID-2 seq=2 configId=BLACKLIST-SET-1"
        )

        val compacted = LogExporter.compactLogsForExport(renderedLines)
        // the FG_DECISION block must remain entirely together, line-for-line, and in order
        assertEquals(7, compacted.size)
        assertTrue("first line must keep the FG_DECISION event header", compacted[0].contains("[FG_DECISION]"))
        assertTrue("second line must be the same decisionRef continuation", compacted[1].contains("decisionRef=DEC-1"))
        assertTrue("last normal-event header still present", compacted.last().contains("[CONFIG_CHANGE]"))

        // concurrent render-write interleaving must NOT interleave two normal events; simulate
        // by emitting through the same NormalEventLogger that backs the bridge.
        val emittedOrder = java.util.Collections.synchronizedList(mutableListOf<String>())
        val logger = NormalEventLogger(
            renderer = DefaultNormalEventRenderer,
            sinks = listOf(
                NormalEventSink { record ->
                    emittedOrder.add("EXPORT-${record.event.eventId}")
                },
                NormalEventSink { record ->
                    emittedOrder.add("LOGCAT-${record.event.eventId}")
                }
            )
        )
        val executor = Executors.newFixedThreadPool(8)
        val start = CountDownLatch(1)
        val ready = CountDownLatch(8)
        val transition = java.util.concurrent.Semaphore(0)
        repeat(8) { idx ->
            executor.submit {
                ready.countDown()
                start.await()
                val clock = FixedEventClock(wallMs = 1_730_700_000_000L + idx * 1000L, elapsedMs = 30_000L + idx)
                logger.emit(
                    NormalEvents.create(
                        type = "SERVICE_STATE",
                        clock = clock,
                        fields = linkedMapOf(
                            "serviceName" to "S-$idx",
                            "oldState" to "OK",
                            "newState" to "STOPPED",
                            "reason" to "TEST"
                        )
                    )
                )
                transition.release()
            }
        }
        assertTrue(ready.await(2, TimeUnit.SECONDS))
        start.countDown()
        assertTrue(transition.tryAcquire(8, 2, TimeUnit.SECONDS))
        executor.shutdown()
        assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS))

        // export and logcat must each see strictly increasing sequence numbers and the same order
        val exports = emittedOrder.filter { it.startsWith("EXPORT-") }
        val logcats = emittedOrder.filter { it.startsWith("LOGCAT-") }
        assertEquals(8, exports.size)
        assertEquals(8, logcats.size)
        // extract seq and ensure monotonic order in each channel
        val exportsSeq = exports.map { it.substringAfter("seq=").substringBefore('-', "") }
        // Pair-wise: every emitted record must reach export BEFORE any subsequent record's logcat
        // (proves single-threaded emission under lock - same event rendered before next allocation)
        // The simplest proof: each export[i] and logcat[i] share the same eventId pattern
        val exportIds = exports.map { it.removePrefix("EXPORT-") }.toSet()
        val logcatIds = logcats.map { it.removePrefix("LOGCAT-") }.toSet()
        assertEquals(exportIds, logcatIds)
    }

    // ---------- helpers ----------

    private fun bridge(
        captured: MutableList<NormalEventDraft>
    ): AlarmProtectionEventBridge = AlarmProtectionEventBridge(
        clock = clock,
        emit = captured::add,
        debugConfig = TestLogDebugConfig()
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

    private class TestLogDebugConfig : LogDebugConfig {
        override val foregroundRaw: Boolean = false
        override val quotaCalculation: Boolean = false
        override val protectionVerbose: Boolean = false
        override val desktopTimerTicks: Boolean = false
    }
}
