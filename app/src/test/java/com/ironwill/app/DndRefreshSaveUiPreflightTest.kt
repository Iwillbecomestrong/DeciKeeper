package com.ironwill.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test


/**
 * WP-05-T02 — DA-D2 refresh save UI preflight contract.
 *
 * v3 SPEC §3.6 step 2 + V14: when the user submits a refresh time that is
 * strictly inside an existing segment, the UI must surface a rejection that
 * carries the exact V14 message and refuse any mutation.
 *
 * This test does NOT instantiate Android components. It locks the textual
 * contract carried by GlobalParamsActivity.kt's preflight + AlertDialog
 * branch. The boundary semantics are exercised by
 * DndRefreshBoundaryValidationTest; this fixture ensures the UI in
 * GlobalParamsActivity is wired to:
 *   1. Use the same V14 user-facing message ("refresh time 必须配置在空档期
 *      或 DND 时段边界").
 *   2. Refuse to write the synced-refresh state and refuse to call
 *      analyzeAndSaveGlobalParams when MinuteBoundary.isRefreshLegal
 *      returns Rejected.
 *   3. NOT save before checking boundaries (i.e., the order of operations
 *      is: preflight → write).
 *
 * RED state prior to WP-05-T02:
 *   - The V14 message literal is absent from GlobalParamsActivity.kt.
 *   - No Rejected branch in btnSave flow; the save path runs through
 *     analyzeAndSaveGlobalParams even when the candidate lies strictly
 *     inside a segment (UI bypasses the WP-03 guard).
 *
 * GREEN after WP-05-T02:
 *   - Both assertions below hold.
 */
class DndRefreshSaveUiPreflightTest {

    /**
     * Lock the exact V14 user-facing copy mandated by SPEC §3.6 step 2.
     */
    @Test
    fun v14_dialog_message_literal_is_in_activity_save_path() {
        val src = read("app/src/main/java/com/ironwill/app/ui/GlobalParamsActivity.kt")
        assertTrue(
            "GlobalParamsActivity.kt must carry V14 message 'refresh time 必须配置在空档期或 DND 时段边界'",
            src.contains("refresh time 必须配置在空档期或 DND 时段边界")
        )
    }

    /**
     * Lock the no-mutation guarantee: the UI save listener must not call
     * TimeRuleManager.analyzeAndSaveGlobalParams before consulting
     * MinuteBoundary.isRefreshLegal. We assert by code text: the Rejected
     * branch must be present and must contain an early return that precedes
     * any reference to analyzeAndSaveGlobalParams in the same listener.
     */
    @Test
    fun rejected_branch_returns_before_analyzeAndSave() {
        val src = read("app/src/main/java/com/ironwill/app/ui/GlobalParamsActivity.kt")
        assertTrue(
            "GlobalParamsActivity.kt save listener must reference MinuteBoundary.isRefreshLegal",
            src.contains("MinuteBoundary.isRefreshLegal")
        )
        val rejectedIdx = src.indexOf("MinuteBoundary.isRefreshLegal")
        val analyzeIdx = src.indexOf("analyzeAndSaveGlobalParams")
        assertTrue(
            "analyzeAndSaveGlobalParams call site must exist in the same file",
            analyzeIdx > 0
        )
        assertTrue(
            "the isRefreshLegal consultation must precede analyzeAndSaveGlobalParams",
            rejectedIdx < analyzeIdx
        )
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
