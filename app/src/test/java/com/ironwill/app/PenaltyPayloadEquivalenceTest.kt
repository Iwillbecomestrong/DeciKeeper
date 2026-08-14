package com.ironwill.app

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Paths
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Boundary 3 — canonical penalty receipt contract.
 *
 * Spec anchors (REQ-010 / [Bug2-Payload]):
 *   - recordPenalty must emit one PENALTY event carrying 4 base fields
 *     (transactionId / ruleId / durationSec / reason) + 7 detail fields
 *     (normalSec / borrowedSec / borrowedCostSec / remainingMin /
 *      remainingCount / cooldownAddedSec / cooldownKind) — 11 total.
 *   - NormalEvent type stays PENALTY (no parallel event) — DA-14 / INVAR-2.
 *   - NormalEvents.create still receives Map<String,String> fields — schema
 *     unchanged (INVAR-7).
 *   - All three charge branches in CreditManager.consumePenaltySeconds
 *     (fully-covered-by-current, fully-covered-by-historical, borrow-from-future)
 *     must emit receipts with identical field shape — INVAR-4.
 *
 * This test is RED until Boundary 3 expands recordPenalty's signature with
 * the seven detail parameters and threads them through CreditManager's three
 * call sites.
 */
class PenaltyPayloadEquivalenceTest {

    private val clock = FixedEventClock(wallMs = 1_730_000_000_000L, elapsedMs = 12_000L)

    /**
     * Boundary 3 bridge-level contract: a single recordPenalty call must
     * publish a PENALTY event whose fields map carries exactly the 11
     * canonical fields — 4 base + 7 detail — in canonical order.
     */
    @Test
    fun penaltyEventContainsFourBaseAndSevenDetailFields() {
        val captured = mutableListOf<NormalEventDraft>()
        val bridge = bridge(captured)

        bridge.recordPenalty(
            transactionId = "TX-PEN-11",
            ruleId = "CONSECUTIVE_LOCK",
            durationSec = "30",
            reason = "REPEATED_LOCK",
            normalSec = "30",
            borrowedSec = "0",
            borrowedCostSec = "0",
            remainingMin = "12.5min",
            remainingCount = "0",
            cooldownAddedSec = "0",
            cooldownKind = "NONE"
        )

        assertEquals(1, captured.size)
        val event = captured.single()
        assertEquals("event type must remain PENALTY (no parallel event)", "PENALTY", event.type)

        // 4 base fields preserved
        assertEquals("TX-PEN-11", event.fields["transactionId"])
        assertEquals("CONSECUTIVE_LOCK", event.fields["ruleId"])
        assertEquals("30", event.fields["durationSec"])
        assertEquals("REPEATED_LOCK", event.fields["reason"])

        // 7 detail fields present
        assertNotNull("normalSec must be present", event.fields["normalSec"])
        assertNotNull("borrowedSec must be present", event.fields["borrowedSec"])
        assertNotNull("borrowedCostSec must be present", event.fields["borrowedCostSec"])
        assertNotNull("remainingMin must be present", event.fields["remainingMin"])
        assertNotNull("remainingCount must be present", event.fields["remainingCount"])
        assertNotNull("cooldownAddedSec must be present", event.fields["cooldownAddedSec"])
        assertNotNull("cooldownKind must be present", event.fields["cooldownKind"])

        // Exact values pass through unchanged (no semantic rewriting in bridge)
        assertEquals("30", event.fields["normalSec"])
        assertEquals("0", event.fields["borrowedSec"])
        assertEquals("0", event.fields["borrowedCostSec"])
        assertEquals("12.5min", event.fields["remainingMin"])
        assertEquals("0", event.fields["remainingCount"])
        assertEquals("0", event.fields["cooldownAddedSec"])
        assertEquals("NONE", event.fields["cooldownKind"])

        // Total: exactly 11 fields — no extra metadata leaks.
        assertEquals(
            "PENALTY receipt must carry exactly 11 canonical fields",
            11,
            event.fields.size
        )
    }

    /**
     * INVAR-2 / INVAR-4: all three CreditManager.consumePenaltySeconds
     * charge branches must construct their recordPenalty receipts from the
     * same parameter template — no branch may omit any of the 7 detail
     * fields, and no branch may silently change the field shape.
     *
     * Branch 1 (line ~3700): fully covered by current segment
     * Branch 2 (line ~3750): fully covered by historical segments
     * Branch 3 (line ~3804): borrow from future segments
     */
    @Test
    fun allThreeChargeBranchesEmitEquivalentFieldShape() {
        val credit = source("app/src/main/java/com/ironwill/app/CreditManager.kt")
        val bridgeSrc = source("app/src/main/java/com/ironwill/app/QuotaConfigEventBridge.kt")

        // The bridge must expose all 7 detail parameters in recordPenalty's signature.
        listOf(
            "normalSec", "borrowedSec", "borrowedCostSec",
            "remainingMin", "remainingCount",
            "cooldownAddedSec", "cooldownKind"
        ).forEach { field ->
            assertTrue(
                "bridge recordPenalty must declare parameter $field",
                bridgeSrc.contains("$field:") && bridgeSrc.contains("recordPenalty(")
            )
        }

        // Each charge branch return path must funnel through the canonical
        // receipt helper. There are three charge branches
        // (fully-covered-by-current / fully-covered-by-historical / borrow-from-future)
        // — each must invoke the helper so the 11-field shape stays uniform.
        // The single literal `quotaConfigEventBridge.recordPenalty(` lives
        // inside the helper; we verify (a) the helper is invoked from all
        // three return sites, (b) all 7 detail parameters are forwarded by
        // name to the bridge from CreditManager, so no branch can quietly
        // drop a field.
        val callSiteCount = Regex("quotaConfigEventBridge\\.recordPenalty\\(")
            .findAll(credit).count()
        assertEquals(
            "CreditManager must funnel every charge branch through exactly one canonical recordPenalty call site",
            1,
            callSiteCount
        )

        val helperInvocationCount = Regex("\\bemitPenaltyReceipt\\(")
            .findAll(credit).count()
        assertTrue(
            "all three charge branches must invoke the canonical receipt helper (expected >= 3, got $helperInvocationCount)",
            helperInvocationCount >= 3
        )

        val detailParams = listOf(
            "normalSec",
            "borrowedSec",
            "borrowedCostSec",
            "remainingMin",
            "remainingCount",
            "cooldownAddedSec",
            "cooldownKind"
        )

        detailParams.forEach { param ->
            val occurrences = Regex("$param\\s*=").findAll(credit).count()
            assertTrue(
                "parameter $param must be passed to recordPenalty from CreditManager " +
                    "(expected at least 1 occurrence, got $occurrences)",
                occurrences >= 1
            )
        }
    }

    /**
     * PENALTY must remain the single canonical event type — the bridge
     * must not introduce a parallel event (e.g. PENALTY_DETAIL,
     * PENALTY_APPLIED) that would double the noise budget. DA-14 / INVAR-2.
     */
    @Test
    fun penaltyTypeRemainsPenalty() {
        val bridgeSrc = source("app/src/main/java/com/ironwill/app/QuotaConfigEventBridge.kt")

        assertTrue(
            "bridge must still type= PENALTY for penalty receipts",
            bridgeSrc.contains("type = \"PENALTY\"")
        )
        assertTrue(
            "bridge must not introduce a parallel PENALTY_APPLIED event (INVAR-2)",
            !bridgeSrc.contains("type = \"PENALTY_APPLIED\"") &&
                !bridgeSrc.contains("type = \"PENALTY_DETAIL\"")
        )
    }

    private fun bridge(captured: MutableList<NormalEventDraft>): QuotaConfigEventBridge =
        QuotaConfigEventBridge(
            clock = clock,
            emit = captured::add,
            debugConfig = TestLogDebugConfig()
        )

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
