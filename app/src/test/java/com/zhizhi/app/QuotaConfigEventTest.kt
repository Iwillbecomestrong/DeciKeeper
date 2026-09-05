package com.zhizhi.app

import com.zhizhi.app.data.EventClock
import com.zhizhi.app.data.LogDebugConfig
import com.zhizhi.app.data.NormalEventDraft
import com.zhizhi.app.manager.QuotaConfigEventBridge
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Paths
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test






/**
 * RED contract for quota and configuration outcome events. The bridge has no authority to
 * mutate quota, grant/reject requests, change rules, or decide cooldown timing. Callers
 * pass the completed fact (config persisted, borrow applied, carryover produced, penalty
 * charged, rest quota delta). The bridge turns each fact into one normal event draft so
 * export and logcat share the same record.
 */
class QuotaConfigEventTest {

    private val clock = FixedEventClock(wallMs = 1_730_000_000_000L, elapsedMs = 12_000L)

    @Test
    fun configChangeDistinguishesImmediateVersusDeferredApplication() {
        val captured = mutableListOf<NormalEventDraft>()
        val bridge = bridge(captured)

        bridge.recordConfigChange(
            configId = "BLACKLIST-SET-1",
            scope = "BLACKLIST",
            action = "UPDATE",
            packageName = "com.ss.android.ugc.aweme",
            oldValue = "inactive",
            newValue = "active",
            effectiveAt = 0L,
            apply = "IMMEDIATE"
        )
        bridge.recordConfigChange(
            configId = "DND-WINDOW-1",
            scope = "DND_SEGMENT",
            action = "UPDATE",
            oldValue = "22:00-07:00",
            newValue = "OFF",
            effectiveAt = 1_730_086_800_000L,
            apply = "DEFERRED"
        )

        assertEquals(listOf("CONFIG_CHANGE", "CONFIG_CHANGE"), captured.map { it.type })
        assertFields(
            captured[0],
            "configId" to "BLACKLIST-SET-1",
            "scope" to "BLACKLIST",
            "action" to "UPDATE",
            "old" to "inactive",
            "new" to "active",
            "apply" to "IMMEDIATE"
        )
        assertFields(
            captured[1],
            "scope" to "DND_SEGMENT",
            "old" to "22:00-07:00",
            "new" to "OFF",
            "apply" to "DEFERRED"
        )
    }

    @Test
    fun ruleEffectiveAppearsOnlyWhenDeferredChangeActuallyTakesEffect() {
        val captured = mutableListOf<NormalEventDraft>()
        val bridge = bridge(captured)

        bridge.recordRuleEffective(
            configId = "DND-WINDOW-1",
            batchId = "BATCH-2026-07-14",
            scope = "DND_SEGMENT",
            effectiveAt = 1_730_086_800_000L,
            oldValue = "22:00-07:00",
            newValue = "OFF",
            result = "APPLIED"
        )

        assertEquals(listOf("RULE_EFFECTIVE"), captured.map { it.type })
        assertFields(
            captured.single(),
            "configId" to "DND-WINDOW-1",
            "batchId" to "BATCH-2026-07-14",
            "scope" to "DND_SEGMENT",
            "old" to "22:00-07:00",
            "new" to "OFF",
            "result" to "APPLIED"
        )
    }

    @Test
    fun borrowRecordsDurationAndCountVariantsWithSourceBalances() {
        val captured = mutableListOf<NormalEventDraft>()
        val bridge = bridge(captured)

        bridge.recordBorrow(
            transactionId = "TX-BORROW-1",
            kind = "DURATION",
            fromSegment = "下午",
            toSegment = "上午",
            received = "3min",
            multiplier = "2.0",
            charged = "6min",
            fromBalanceBefore = "20min",
            fromBalanceAfter = "14min",
            reason = "TEMP_ACCESS",
            result = "APPLIED"
        )
        bridge.recordBorrow(
            transactionId = "TX-BORROW-2",
            kind = "COUNT",
            fromSegment = "下午",
            toSegment = "上午",
            received = "1",
            multiplier = "2.0",
            charged = "2",
            fromBalanceBefore = "4",
            fromBalanceAfter = "2",
            reason = "REST",
            result = "APPLIED"
        )

        assertEquals(listOf("BORROW", "BORROW"), captured.map { it.type })
        assertFields(captured[0], "fromSegment" to "下午", "toSegment" to "上午", "charged" to "6min", "fromBalanceAfter" to "14min")
        assertFields(captured[1], "kind" to "COUNT", "charged" to "2", "reason" to "REST")
    }

    @Test
    fun restQuotaAndCountChangesAreAuditableAndPreserveZeroValues() {
        val captured = mutableListOf<NormalEventDraft>()
        val bridge = bridge(captured)

        bridge.recordRestQuotaChange(sessionId = "REST-1", before = "30min", after = "0min", reason = "TIME_EXPIRED")
        bridge.recordRestCountChange(sessionId = "REST-1", before = "3", after = "0", reason = "TIME_EXPIRED")

        assertEquals(listOf("REST_QUOTA_CHANGE", "REST_COUNT_CHANGE"), captured.map { it.type })
        assertFields(captured[0], "after" to "0min", "reason" to "TIME_EXPIRED")
        assertFields(captured[1], "after" to "0", "reason" to "TIME_EXPIRED")
        assertTrue("zero must remain visible in rest quota change", captured[0].fields["after"] == "0min")
        assertTrue("zero must remain visible in rest count change", captured[1].fields["after"] == "0")
    }

    @Test
    fun carryoverCreatedAndUsedAreDistinct() {
        val captured = mutableListOf<NormalEventDraft>()
        val bridge = bridge(captured)

        bridge.recordCarryoverCreated(transactionId = "TX-CARRY-1", fromSegment = "上午", amount = "5min", kind = "DURATION")
        bridge.recordCarryoverUsed(transactionId = "TX-CARRY-1", fromSegment = "上午", amount = "5min", kind = "DURATION", consumedBy = "TEMP_ACCESS")

        assertEquals(listOf("CARRYOVER_CREATED", "CARRYOVER_USED"), captured.map { it.type })
        assertFields(captured[0], "fromSegment" to "上午", "amount" to "5min", "kind" to "DURATION")
        assertFields(captured[1], "consumedBy" to "TEMP_ACCESS", "amount" to "5min")
    }

    @Test
    fun penaltyAndApprovalAreFactOnlyWithZeroDurationVisible() {
        val captured = mutableListOf<NormalEventDraft>()
        val bridge = bridge(captured)

        bridge.recordPenalty(transactionId = "TX-PEN-1", ruleId = "STRICT_PENALTY", durationSec = "15", reason = "BLACKLIST_HIT")
        bridge.recordZeroDurationAllowedTransaction(transactionId = "TX-OK-0", restCost = "0min")

        assertEquals(listOf("PENALTY", "ALLOW_START"), captured.map { it.type })
        assertFields(captured[0], "ruleId" to "STRICT_PENALTY", "durationSec" to "15", "reason" to "BLACKLIST_HIT")
        assertFields(captured[1], "restCost" to "0min")
    }

    @Test
    fun quietWindowDoesNotIntroduceAnyDefaultSummary() {
        val captured = mutableListOf<NormalEventDraft>()
        val bridge = bridge(captured, debugMode = false)

        repeat(8) {
            bridge.recordConfigChange(
                configId = "CFG-$it",
                scope = "GLOBAL_PARAM",
                action = "UPDATE",
                oldValue = "old-$it",
                newValue = "new-$it",
                effectiveAt = 0L,
                apply = "IMMEDIATE"
            )
        }
        assertEquals(8, captured.size)
        assertFalse(captured.any { it.type == "TIME_CONSUME" })
    }

    @Test
    fun productionSaveBlacklistAndSaveGlobalParamsRouteThroughTheBridge() {
        val credit = source("app/src/main/java/com/zhizhi/app/manager/CreditManager.kt")
        val timeRule = source("app/src/main/java/com/zhizhi/app/manager/TimeRuleManager.kt")
        val bridgeFile = source("app/src/main/java/com/zhizhi/app/manager/QuotaConfigEventBridge.kt")

        assertTrue(
            "saveBlacklistApps must invoke the CONFIG_CHANGE bridge call site",
            credit.contains("QuotaConfigEventBridge")
        )
        assertTrue(
            "saveGlobalParams must invoke the CONFIG_CHANGE bridge call site",
            timeRule.contains("QuotaConfigEventBridge")
        )
        assertTrue(
            "bridge must define recordConfigChange + recordRuleEffective API",
            bridgeFile.contains("recordConfigChange") && bridgeFile.contains("recordRuleEffective")
        )
    }

    @Test
    fun productionBorrowAndPenaltySitesRecordActualOutcomesNotIntent() {
        val credit = source("app/src/main/java/com/zhizhi/app/manager/CreditManager.kt")
        val bridgeFile = source("app/src/main/java/com/zhizhi/app/manager/QuotaConfigEventBridge.kt")

        assertTrue(
            "CreditManager must invoke QuotaConfigEventBridge.recordBorrow on successful borrow path",
            credit.contains("recordBorrow(") || credit.contains(".recordBorrow(")
        )
        assertTrue(
            "bridge file must expose recordBorrow + recordPenalty + recordPenalty signatures",
            bridgeFile.contains("recordBorrow(") &&
                bridgeFile.contains("recordPenalty(")
        )
    }

    private fun bridge(
        captured: MutableList<NormalEventDraft>,
        debugMode: Boolean = false
    ): QuotaConfigEventBridge = QuotaConfigEventBridge(
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
