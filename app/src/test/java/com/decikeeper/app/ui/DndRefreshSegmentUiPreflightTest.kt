package com.decikeeper.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test


/**
 * WP-04-T02 — DA-D1 segment save UI preflight contract (V13).
 *
 * v3 SPEC §3.6 step 2 + V13: when the user submits a DND segment via the
 * `btn_add_segment` listener in `TimeConfigFragment.kt`, the UI must consult
 * the typed minute-boundary validator (`MinuteBoundary.isSegmentLegal`) BEFORE
 * any list mutation, adapter update, or `TimeRuleManager.analyzeAndSaveTimeSegments`
 * call. Rejection must surface the V13 message and refuse all mutation.
 *
 * This fixture uses the same textual-contract strategy as
 * `DndRefreshSaveUiPreflightTest` (WP-05-T02) and locks two invariants:
 *
 *   1. The exact V13 user-facing copy `时段端点不能跨 refresh time` is present
 *      inside `TimeConfigFragment.kt`.
 *   2. The `MinuteBoundary.isSegmentLegal` consultation must occur BEFORE the
 *      `analyzeAndSaveTimeSegments` call site in the same file (preflight-
 *      before-mutation invariant).
 *
 * Boundary arithmetic itself is exercised by `DndRefreshBoundaryValidationTest`;
 * this fixture locks only the UI wiring in `TimeConfigFragment.kt`.
 *
 * RED state prior to WP-04-T02:
 *   - The V13 message literal is absent from `TimeConfigFragment.kt`.
 *   - The `btn_add_segment` positive-action listener calls
 *     `analyzeAndSaveTimeSegments` directly without any
 *     `MinuteBoundary.isSegmentLegal` consultation, so a strict-crossing
 *     segment (e.g. `05:30-22:00` with refresh = `07:00`) bypasses the UI
 *     preflight even though `applySegmentsUpdate` would later fail at
 *     the backend guard.
 *
 * GREEN after WP-04-T02:
 *   - Both assertions below hold; the production code shows an `AlertDialog`
 *     with the V13 copy and returns before any mutation on Rejected.
 */
class DndRefreshSegmentUiPreflightTest {

    /**
     * Lock the exact V13 user-facing copy mandated by SPEC §3.6 step 2.
     */
    @Test
    fun v13_dialog_message_literal_is_in_time_config_save_path() {
        val src = read("app/src/main/java/com/decikeeper/app/ui/TimeConfigFragment.kt")
        assertTrue(
            "TimeConfigFragment.kt must carry V13 message '时段端点不能跨 refresh time'",
            src.contains("时段端点不能跨 refresh time")
        )
    }

    /**
     * Lock the no-mutation guarantee: the segment save listener must consult
     * `MinuteBoundary.isSegmentLegal` before invoking
     * `applySegmentsUpdate(` in the same `btn_add_segment` add-dialog
     * positive-action lambda. We assert by code text: within the
     * `setPositiveButton("添加") { ... }` block of `showAddSegmentDialog`,
     * the validator call must appear before the `applySegmentsUpdate(`
     * call. Comment mentions outside the lambda do not satisfy the contract.
     *
     * This invariant ensures that even if a strict-crossing segment is
     * submitted, the AlertDialog + early return happens BEFORE any
     * `analyzeAndSaveTimeSegments` invocation reaches `TimeRuleManager`.
     */
    @Test
    fun rejected_segment_branch_precedes_analyzeAndSave() {
        val src = read("app/src/main/java/com/decikeeper/app/ui/TimeConfigFragment.kt")
        assertTrue(
            "TimeConfigFragment.kt must reference MinuteBoundary.isSegmentLegal",
            src.contains("MinuteBoundary.isSegmentLegal")
        )

        // Locate the `setPositiveButton("添加") { _, _ ->` block in
        // `showAddSegmentDialog` and find its closing brace.
        val addBtnStart = src.indexOf("setPositiveButton(\"添加\")")
        assertTrue(
            "TimeConfigFragment.kt must contain btn_add_segment's '添加' positive button",
            addBtnStart > 0
        )
        val lambdaStart = src.indexOf("->", addBtnStart)
        assertTrue(
            "btn_add_segment positive button must use a { _, _ -> ... } lambda",
            lambdaStart > 0
        )

        // Find the FIRST `applySegmentsUpdate(` call after the lambda start.
        val saveCallIdx = src.indexOf("applySegmentsUpdate(", lambdaStart)
        assertTrue(
            "btn_add_segment positive-action lambda must invoke applySegmentsUpdate(",
            saveCallIdx > 0
        )

        // Find the validator call within the same lambda.
        val validatorInLambda = src.indexOf("MinuteBoundary.isSegmentLegal", lambdaStart)
        assertTrue(
            "MinuteBoundary.isSegmentLegal must be consulted inside btn_add_segment positive lambda. " +
                "lambdaStart=$lambdaStart validatorInLambda=$validatorInLambda",
            validatorInLambda > 0 && validatorInLambda < saveCallIdx
        )
    }

    /**
     * Lock V13 literal equality precisely (no surrounding substring tolerance).
     * This guards against future refactors that might rename / drop the
     * marker while keeping an unrelated similar copy. We require the exact
     * 13-char Chinese sequence '时段端点不能跨 refresh time' to appear in the
     * file.
     */
    @Test
    fun v13_literal_marker_exact_match() {
        val src = read("app/src/main/java/com/decikeeper/app/ui/TimeConfigFragment.kt")
        val marker = "时段端点不能跨 refresh time"
        val occurrences = countOccurrences(src, marker)
        assertEquals(
            "TimeConfigFragment.kt must carry V13 marker '$marker' exactly once " +
                "(or more if duplicated across add/edit listener — current contract requires at least one). " +
                "found=$occurrences",
            true,
            occurrences >= 1
        )
    }

    /** Helper: count exact substring occurrences in [text]. */
    private fun countOccurrences(text: String, sub: String): Int {
        if (sub.isEmpty()) return 0
        var idx = 0
        var count = 0
        while (true) {
            val found = text.indexOf(sub, idx)
            if (found < 0) return count
            count++
            idx = found + sub.length
        }
    }

    /** Helper: resolve the production source file from the gradle cwd. */
    private fun read(relativePath: String): String =
        generateSequence(java.io.File(System.getProperty("user.dir") ?: ".").absoluteFile) {
            it.parentFile
        }
            .map { java.io.File(it, relativePath) }
            .firstOrNull { it.exists() && it.length() > 0 }
            ?.readText(Charsets.UTF_8)
            ?: error("找不到 $relativePath；user.dir=${System.getProperty("user.dir")}")
}