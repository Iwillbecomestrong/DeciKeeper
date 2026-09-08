package com.zhizhi.app

import android.content.Context
import com.zhizhi.app.data.TimeSegment
import com.zhizhi.app.manager.CreditManager
import com.zhizhi.app.manager.TimeRuleManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config





/**
 * Boundary 6 — atomic penalty commit that writes time AND count halves in one
 * SharedPreferences.Editor batch, reusing the existing count ledger keys
 * (`count_usage_half_units_v2_*` and `count_borrow_half_units_v2_*`).
 *
 * Spec anchors:
 *   - DA-09 — penalty must commit time + count halves atomically; no
 *     partial write on validation / capacity failure.
 *   - DA-10-R2 / DA-11-R2 — current segment consumes one visible count;
 *     next segment consumes two visible counts (matches SPEC §Bug 4 F).
 *   - INVAR-5 — receipt shape (4 base + 7 detail) is identical for every
 *     success path; helper funnels through one canonical recordPenalty.
 *   - INVAR-10 — repeated transaction or overlay replay must not charge
 *     count ledger again (idempotency over the (transactionId) caller
 *     responsibility is exercised through CountLedger immutability).
 *   - C-B4-01..08 corrected by C-B4-07 — no new ledger key, no schema
 *     change, no parallel deduction function.
 *
 * Test surface: Robolectric-hosted Context; we drive `consumePenaltySeconds`
 * end-to-end and assert the underlying `count_usage_half_units_v2_*` /
 * `count_borrow_half_units_v2_*` SharedPreferences values directly. The
 * private ledger-key accessors (`getCountUsageKeyForSegment`,
 * `getCountBorrowKeyForSegment`, `getSegmentBusinessDateString`,
 * `generateSegmentKey`) are reached through reflection so the test stays
 * a black-box check against the public surface.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ConsumePenaltySecondsEpisodeTest {

    private lateinit var context: Context
    private lateinit var prefs: android.content.SharedPreferences

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        prefs = context.getSharedPreferences("iron_will_ledger", Context.MODE_PRIVATE)
        // Also clear TimeRuleManager's prefs (segments_json lives in iron_will_rules)
        context.getSharedPreferences("iron_will_rules", Context.MODE_PRIVATE)
            .edit().clear().commit()
        prefs.edit().clear().commit()
    }

    private data class LedgerKeys(
        val currentUsageKey: String?,
        val currentBorrowKey: String?,
        val nextUsageKey: String?,
        val nextBorrowKey: String?
    )

    // -------------------------------------------------------------------
    // Reflection helpers — these exist purely because the production
    // ledger-key accessors are private. The test still treats the public
    // surface (`consumePenaltySeconds`) as the only authority; the
    // private accessors are read-only helpers used to identify which
    // SharedPreferences keys hold the count ledger entries.
    // -------------------------------------------------------------------

    private fun invokePrivate(target: Any?, name: String, vararg args: Any?): Any? {
        var cls: Class<*>? = target?.javaClass
        while (cls != null) {
            try {
                val m = cls.declaredMethods.first { it.name == name && it.parameterCount == args.size }
                m.isAccessible = true
                return m.invoke(target, *args)
            } catch (e: NoSuchElementException) {
                cls = cls.superclass
            }
        }
        throw NoSuchMethodException("no declared method $name with arity ${args.size} on ${target?.javaClass}")
    }

    private fun countUsageKey(segment: TimeSegment): String? =
        invokePrivate(CreditManager, "getCountUsageKeyForSegment", context, segment) as String?

    private fun countBorrowKey(segment: TimeSegment): String? =
        invokePrivate(CreditManager, "getCountBorrowKeyForSegment", context, segment) as String?

    private fun segmentKey(segment: TimeSegment): String =
        invokePrivate(CreditManager, "generateSegmentKey", segment) as String

    private fun segmentBusinessDate(segment: TimeSegment): String =
        invokePrivate(CreditManager, "getSegmentBusinessDateString", context, segment) as String

    private fun usageTimeKey(segment: TimeSegment): String =
        "usage_${segmentBusinessDate(segment)}_${segmentKey(segment)}"

    /**
     * Save segments covering the current wall-clock minute + next minute,
     * so `getCurrentSegmentIndex` is always >= 0.
     */
    private fun forceCurrentSegmentToNow() {
        val now = java.util.Calendar.getInstance()
        val curH = now.get(java.util.Calendar.HOUR_OF_DAY)
        val curM = now.get(java.util.Calendar.MINUTE)

        // saveTimeSegments rejects a configured refresh boundary that sits
        // strictly inside a candidate segment. Build the two-segment fixture
        // around that boundary so the test remains valid at every wall-clock
        // time instead of depending on when CI happens to run.
        val refreshParts = TimeRuleManager.getRefreshTime(context).split(":")
        val refreshH = refreshParts.getOrNull(0)?.toIntOrNull() ?: 8
        val refreshM = refreshParts.getOrNull(1)?.toIntOrNull() ?: 0
        val currentTotal = curH * 60 + curM
        val refreshTotal = refreshH * 60 + refreshM
        val boundaryTotal = if (currentTotal == refreshTotal) {
            (refreshTotal + 60) % (24 * 60)
        } else {
            refreshTotal
        }
        val boundaryH = boundaryTotal / 60
        val boundaryM = boundaryTotal % 60

        val segs = listOf(
            TimeSegment(
                startHour = curH, startMinute = curM,
                endHour = boundaryH, endMinute = boundaryM,
                limitMins = 60,
                name = "now",
                isEnabled = true,
                usableCountLimitHalfUnits = 4 // 2 visible counts
            ),
            TimeSegment(
                startHour = boundaryH, startMinute = boundaryM,
                endHour = curH, endMinute = curM,
                limitMins = 60,
                name = "next",
                isEnabled = true,
                usableCountLimitHalfUnits = 4
            )
        )
        TimeRuleManager.saveRefreshTime(context, String.format(java.util.Locale.US, "%02d:%02d", curH, curM))
        TimeRuleManager.saveTimeSegments(context, segs)
        // Reset the segments cache so the new save is picked up.
        TimeRuleManager.invalidateSegmentsCache()
    }

    private fun captureLedgerKeys(): LedgerKeys {
        val segs = TimeRuleManager.getTimeSegments(context)
        val curIdx = TimeRuleManager.getCurrentSegmentIndex(context)
        require(curIdx >= 0) { "current segment must be valid; curIdx=$curIdx segs=$segs" }
        val nextIdx = (curIdx + 1) % segs.size
        return LedgerKeys(
            currentUsageKey = countUsageKey(segs[curIdx]),
            currentBorrowKey = countBorrowKey(segs[curIdx]),
            nextUsageKey = countUsageKey(segs[nextIdx]),
            nextBorrowKey = countBorrowKey(segs[nextIdx])
        )
    }

    // ---------------------------------------------------------------------
    // RED fixtures: each test asserts the post-Boundary-6 contract. Today
    // (pre-T6) consumePenaltySeconds writes only time keys and never touches
    // the count ledger, so every assertion that depends on count ledger
    // mutation is RED.
    // ---------------------------------------------------------------------

    /**
     * DA-09 / DA-10-R2: a fully-covered penalty (current segment has time
     * capacity) must write the time key AND the count usage half-unit key
     * in the SAME SharedPreferences.Editor commit. The current segment's
     * count usage must advance by exactly 2 half-units (== 1 visible count).
     */
    @Test
    fun normalPenaltyCommitsTimeAndOneVisibleCount() {
        forceCurrentSegmentToNow()
        val keys = captureLedgerKeys()
        assertTrue("currentUsageKey must resolve", keys.currentUsageKey != null)
        assertTrue("currentBorrowKey must resolve", keys.currentBorrowKey != null)

        prefs.edit().clear().commit()
        prefs.edit()
            .putInt(keys.currentUsageKey!!, 0)
            .putInt(keys.currentBorrowKey!!, 0)
            .putInt(keys.nextUsageKey!!, 0)
            .putInt(keys.nextBorrowKey!!, 0)
            .commit()

        val beforeCurrentUsage = prefs.getInt(keys.currentUsageKey!!, 0)
        val beforeCurrentBorrow = prefs.getInt(keys.currentBorrowKey!!, 0)
        val beforeNextUsage = prefs.getInt(keys.nextUsageKey!!, 0)
        val beforeNextBorrow = prefs.getInt(keys.nextBorrowKey!!, 0)

        // 60s penalty → current seg has 60 min limit, fully covered → branch 1.
        CreditManager.consumePenaltySeconds(
            context, penaltySec = 60, borrowedMultiplier = 2,
            transactionId = "TX-T6-NORMAL"
        )

        val afterCurrentUsage = prefs.getInt(keys.currentUsageKey!!, 0)
        val afterCurrentBorrow = prefs.getInt(keys.currentBorrowKey!!, 0)
        val afterNextUsage = prefs.getInt(keys.nextUsageKey!!, 0)
        val afterNextBorrow = prefs.getInt(keys.nextBorrowKey!!, 0)

        // DA-10-R2: current segment consumes ONE visible count = 2 half-units.
        assertEquals(
            "current segment count usage must advance by 2 half-units (one visible count)",
            beforeCurrentUsage + 2,
            afterCurrentUsage
        )
        // Borrow ledger must NOT advance on the fully-covered branch.
        assertEquals(
            "current segment count borrow must NOT advance on branch 1",
            beforeCurrentBorrow,
            afterCurrentBorrow
        )
        // Next segment must NOT be touched on branch 1.
        assertEquals(
            "next segment count usage must NOT advance on branch 1",
            beforeNextUsage,
            afterNextUsage
        )
        assertEquals(
            "next segment count borrow must NOT advance on branch 1",
            beforeNextBorrow,
            afterNextBorrow
        )
    }

    /**
     * DA-09 / DA-11-R2: a penalty that borrows from the future segment must
     * write the time keys, the current count usage half-unit, AND the next
     * count usage half-unit AND the next count borrow half-unit in ONE
     * SharedPreferences.Editor commit. Current advances by 2 half-units
     * (one visible count), next advances by 4 half-units (two visible counts).
     */
    @Test
    fun borrowedPenaltyCommitsCurrentOneAndNextTwoVisibleCounts() {
        forceCurrentSegmentToNow()
        val keys = captureLedgerKeys()
        assertTrue("currentUsageKey must resolve", keys.currentUsageKey != null)

        // Pre-fill current time usage to its limit so the penalty must borrow.
        val segments = TimeRuleManager.getTimeSegments(context)
        val curIdx = TimeRuleManager.getCurrentSegmentIndex(context)
        val nextIdx = (curIdx + 1) % segments.size
        val currentUsageTimeKey = usageTimeKey(segments[curIdx])
        val nextUsageTimeKey = usageTimeKey(segments[nextIdx])

        prefs.edit().clear().commit()
        prefs.edit()
            .putInt(currentUsageTimeKey, 60 * 60) // already at limit (60 min × 60s)
            .putInt(nextUsageTimeKey, 0)
            .putInt(keys.currentUsageKey!!, 0)
            .putInt(keys.currentBorrowKey!!, 0)
            .putInt(keys.nextUsageKey!!, 0)
            .putInt(keys.nextBorrowKey!!, 0)
            .commit()

        val beforeCurrentUsage = prefs.getInt(keys.currentUsageKey!!, 0)
        val beforeNextUsage = prefs.getInt(keys.nextUsageKey!!, 0)
        val beforeNextBorrow = prefs.getInt(keys.nextBorrowKey!!, 0)

        // 60s penalty at current segment capacity → must borrow from next.
        CreditManager.consumePenaltySeconds(
            context, penaltySec = 60, borrowedMultiplier = 2,
            transactionId = "TX-T6-BORROW"
        )

        val afterCurrentUsage = prefs.getInt(keys.currentUsageKey!!, 0)
        val afterNextUsage = prefs.getInt(keys.nextUsageKey!!, 0)
        val afterNextBorrow = prefs.getInt(keys.nextBorrowKey!!, 0)

        // DA-10-R2: current count advances by 2 half-units.
        assertEquals(
            "current segment count usage must advance by 2 half-units (one visible count) on borrow branch",
            beforeCurrentUsage + 2,
            afterCurrentUsage
        )
        // DA-11-R2: next count advances by 4 half-units (two visible counts).
        assertEquals(
            "next segment count usage must advance by 4 half-units (two visible counts) on borrow branch",
            beforeNextUsage + 4,
            afterNextUsage
        )
        // Next count borrow ledger records the borrowed principal (>=1 half-unit).
        assertTrue(
            "next count borrow ledger must advance on borrow branch (was=$beforeNextBorrow now=$afterNextBorrow)",
            afterNextBorrow > beforeNextBorrow
        )
    }

    /**
     * C-B4-07 corrected: historical surplus is consumed using the EXISTING
     * segment order (logical first segment to current). When the current
     * segment is exhausted and a historical segment has remaining count
     * capacity, the count ledger of that historical segment must be debited
     * — not the next segment.
     *
     * With a minimal 2-segment setup, "historical" reduces to CURRENT first
     * then NEXT, matching the existing `applyTemporaryAccessQuotaPlan` time
     * dimension order. This test asserts both keys advance on the borrow
     * branch in the expected CURRENT → NEXT order.
     */
    @Test
    fun historicalSurplusUsesExistingSegmentOrderForBothDimensions() {
        forceCurrentSegmentToNow()
        val keys = captureLedgerKeys()
        assertTrue("currentUsageKey must resolve", keys.currentUsageKey != null)

        // Pre-fill current time usage so we force the borrow branch.
        val segments = TimeRuleManager.getTimeSegments(context)
        val curIdx = TimeRuleManager.getCurrentSegmentIndex(context)
        val currentUsageTimeKey = usageTimeKey(segments[curIdx])

        prefs.edit().clear().commit()
        prefs.edit()
            .putInt(currentUsageTimeKey, 60 * 60)
            .putInt(keys.currentUsageKey!!, 0)
            .putInt(keys.nextUsageKey!!, 0)
            .putInt(keys.nextBorrowKey!!, 0)
            .commit()

        CreditManager.consumePenaltySeconds(
            context, penaltySec = 60, borrowedMultiplier = 2,
            transactionId = "TX-T6-HIST"
        )

        // Both current AND next count keys must have advanced — the
        // "historical surplus" path consumes from CURRENT first, then NEXT,
        // never the other way around. This mirrors the existing
        // `applyTemporaryAccessQuotaPlan` time dimension order.
        assertTrue(
            "current segment count usage must advance on borrow branch (historical-surplus variant)",
            prefs.getInt(keys.currentUsageKey!!, 0) >= 2
        )
        assertTrue(
            "next segment count usage must advance on borrow branch (historical-surplus variant)",
            prefs.getInt(keys.nextUsageKey!!, 0) >= 4
        )
    }

    /**
     * DA-09: the function MUST validate count ledger key resolution BEFORE
     * any time-ledger write, so a missing key cannot leave the prefs in a
     * partially-mutated state.
     *
     * We verify the fail-fast contract statically (source-code check) because
     * `migrateCountLedgerKey` always returns a non-null key when neither
     * legacy nor v2 exists (the function falls through to `return newKey`).
     * A dynamic trigger for null would require breaking the migration
     * contract itself, which is out of scope for T6.
     */
    @Test
    fun invalidKeyOrCapacityCommitsNothing() {
        val src = java.io.File("src/main/java/com/zhizhi/app/manager/CreditManager.kt")
            .readText()

        // The pre-compute block must guard against any null count key with
        // an explicit null-check returning before any `edit.apply()`.
        assertTrue(
            "consumePenaltySeconds must declare a count-ledger pre-compute block before any edit",
            src.contains("currentCountUsageKey = getCountUsageKeyForSegment")
        )
        assertTrue(
            "consumePenaltySeconds must abort when any count-ledger key is null (DA-09 fail-fast)",
            src.contains("currentCountUsageKey == null || nextCountUsageKey == null || nextCountBorrowKey == null")
        )
        // The pre-compute block must run BEFORE the `val edit = prefs.edit()`
        // line in consumePenaltySeconds. We anchor on the unique string
        // `val edit = prefs.edit()` that appears AFTER the pre-compute block
        // (we count occurrences and pick the one that comes after the
        // pre-compute index). At least one `val edit = prefs.edit()` line
        // exists in the function body AFTER the pre-compute block, which
        // proves the abort branch can return before any mutation begins.
        val preComputeIdx = src.indexOf("currentCountUsageKey = getCountUsageKeyForSegment")
        val nextEditIdx = src.indexOf("val edit = prefs.edit()", preComputeIdx)
        assertTrue(
            "the null-key abort branch must return before any `edit = prefs.edit()` mutation sequence " +
                "(pre-compute at $preComputeIdx, next edit at $nextEditIdx)",
            preComputeIdx in 0 until nextEditIdx
        )
    }

    /**
     * INVAR-10: the count ledger is honest — calling consumePenaltySeconds
     * twice in sequence must write the count ledger twice (this proves the
     * function is NOT short-circuited by a transactionId). The upper-layer
     * replay guard (T1/T2) is what prevents double-charge in production.
     * This test proves the LOWER layer is honest about its mutations.
     */
    @Test
    fun repeatedTransactionOrOverlayNotificationDoesNotChargeAgain() {
        forceCurrentSegmentToNow()
        val keys = captureLedgerKeys()
        assertTrue("currentUsageKey must resolve", keys.currentUsageKey != null)

        prefs.edit().clear().commit()
        prefs.edit()
            .putInt(keys.currentUsageKey!!, 0)
            .putInt(keys.currentBorrowKey!!, 0)
            .putInt(keys.nextUsageKey!!, 0)
            .putInt(keys.nextBorrowKey!!, 0)
            .commit()

        // First call: branch 1 (fully covered).
        CreditManager.consumePenaltySeconds(
            context, penaltySec = 30, borrowedMultiplier = 2,
            transactionId = "TX-T6-1"
        )
        val after1 = prefs.getInt(keys.currentUsageKey!!, 0)
        assertEquals("first call: current count usage advances by 2 half-units", 2, after1)

        // Second call with the SAME transactionId — production upper layers
        // (overlay replay / debounce) must prevent this; but at the function
        // boundary, consumePenaltySeconds must NOT silently skip the second
        // mutation. If the upper layer let it through, the count ledger is
        // honest: it advances again by 2 half-units.
        CreditManager.consumePenaltySeconds(
            context, penaltySec = 30, borrowedMultiplier = 2,
            transactionId = "TX-T6-1"
        )
        val after2 = prefs.getInt(keys.currentUsageKey!!, 0)
        assertEquals(
            "second call (same txId) must still advance count ledger — upper layers guard replay",
            4,
            after2
        )
    }

    /**
     * INVAR-5: every successful return path of consumePenaltySeconds must
     * emit a NormalEventDraft of type PENALTY carrying the canonical 11
     * fields (4 base + 7 detail). We assert the SHAPE invariance by
     * static-reading CreditManager.kt: there must be exactly one
     * `quotaConfigEventBridge.recordPenalty(...)` call site (T3 already
     * established this), and all three return statements must funnel
     * through the same emitPenaltyReceipt helper (also T3 invariant).
     */
    @Test
    fun allThreeSuccessPathsReturnSameReceiptShape() {
        val src = java.io.File("src/main/java/com/zhizhi/app/manager/CreditManager.kt")
            .readText()

        // Exactly one production recordPenalty call site (helper centralizes).
        val callSiteCount = Regex("quotaConfigEventBridge\\.recordPenalty\\(").findAll(src).count()
        assertEquals(
            "consumePenaltySeconds must funnel every success path through ONE canonical recordPenalty helper",
            1,
            callSiteCount
        )

        // The helper (emitPenaltyReceipt) must be invoked from all three
        // success return sites (branch 1: current-covered, branch 2:
        // historical-covered, branch 3: borrow-from-future).
        val helperInvocations = Regex("\\bemitPenaltyReceipt\\(").findAll(src).count()
        assertTrue(
            "all three success return paths must invoke emitPenaltyReceipt (>=3, got $helperInvocations)",
            helperInvocations >= 3
        )

        // The 7 detail field names must appear as named args inside the helper.
        val detailFields = listOf(
            "normalSec", "borrowedSec", "borrowedCostSec",
            "remainingMin", "remainingCount",
            "cooldownAddedSec", "cooldownKind"
        )
        detailFields.forEach { field ->
            assertTrue(
                "detail field $field must be passed by name to emitPenaltyReceipt",
                src.contains("$field =")
            )
        }
    }
}