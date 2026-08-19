package com.ironwill.app

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class AlarmChallengeReentryContractTest {
    @Test
    fun activeAlarmChallengeButtonAlwaysRoutesToRecoveryEntryPoint() {
        val adapter = source("app/src/main/java/com/ironwill/app/ui/AlarmListAdapter.kt")

        assertTrue(
            "when the visible Challenge button is clicked, stale internal overlay state must not block recovery",
            adapter.contains("AlarmWindowManager.reenterChallenge(itemView.context, item.id)")
        )
    }

    @Test
    fun challengeRecoveryRefreshesScreenWakeAndHandlesUnexpectedScreenOff() {
        val window = source("app/src/main/java/com/ironwill/app/manager/AlarmWindowManager.kt")

        assertTrue(window.contains("fun reenterChallenge(context: Context, alarmId: Int)"))
        assertTrue(window.contains("CHALLENGE_REENTER_REQUEST"))
        assertTrue(window.contains("SCREEN_KEEP_ALIVE_REFRESH"))
        assertTrue(
            "while an alarm remains active, an unexpected screen-off must trigger a wake refresh",
            window.contains("Intent.ACTION_SCREEN_OFF") && window.contains("SCREEN_OFF_RECOVERY")
        )
    }

    @Test
    fun clozePassageKeepsOnlyTheSingleFourUnderscoreTargetBlank() {
        val dirty = "Too often, careless use of words __ ____ a meeting. The words may ___ stir up reactions."
        val cleaned = invokeClozeSanitizer(dirty)

        assertEquals(1, Regex("_{4}").findAll(cleaned).count())
        assertFalse(Regex("(?<!_)_{1,3}(?!_)").containsMatchIn(cleaned))
        assertTrue(cleaned.contains("words ____ a meeting"))
        assertTrue(cleaned.contains("words may stir up reactions"))
    }

    @Test
    fun clozePassageViewConfiguresLongOptionsInsteadOfLeavingFixedButtonsUntouched() {
        val wordLookup = source("app/src/main/java/com/ironwill/app/ui/WordLookupTextView.kt")
        val uiHelper = sourceOrNull("app/src/main/java/com/ironwill/app/ui/ClozeChallengeUi.kt")

        assertTrue(
            "the cloze passage view must configure its four sibling option buttons after attachment",
            wordLookup.contains("ClozeChallengeUi.configureOptionButtons(this)")
        )
        assertTrue("cloze UI helper must exist", uiHelper != null)
        assertTrue(uiHelper!!.contains("height = dp(view, 64)"))
        assertTrue(uiHelper.contains("maxLines = 2"))
        assertTrue(uiHelper.contains("setAutoSizeTextTypeUniformWithConfiguration"))
    }

    @Test
    fun wordDetailsUseAnInViewAlarmOverlayBeforeTheLegacyDialogCallback() {
        val wordLookup = source("app/src/main/java/com/ironwill/app/ui/WordLookupTextView.kt")
        val overlayCall = "ClozeChallengeUi.showWordDetailInsideAlarmRoot"
        val legacyCallback = "onWordPressed?.invoke"

        assertTrue(wordLookup.contains(overlayCall))
        assertTrue(wordLookup.contains(legacyCallback))
        assertTrue(
            "alarm-root word lookup must render in the same challenge view before falling back to a separate-window callback",
            wordLookup.indexOf(overlayCall) < wordLookup.indexOf(legacyCallback)
        )
    }

    @Test
    fun clozeSkipActionsOnlyChangeQuestionAndNeverCountAsCorrectProgress() {
        val overlay = source("app/src/main/java/com/ironwill/app/ui/LockAlarmOverlay.kt")
        val skipBlock = overlay
            .substringAfter("btnClozeSkip.setOnClickListener {")
            .substringBefore("btnClozeBookmark.setOnClickListener {")
        val bookmarkSkipBlock = overlay
            .substringAfter("btnClozeBookmark.setOnClickListener {")
            .substringBefore("// 提示按钮")

        assertTrue(skipBlock.contains("settleCurrentCloze(\"skipped\")"))
        assertTrue(skipBlock.contains("rollCloze()"))
        assertFalse("plain cloze skip must not advance solved count", skipBlock.contains("currentClozeIndex++"))
        assertFalse("plain cloze skip must never pass the challenge", skipBlock.contains("onAnyChallengePassed()"))

        assertTrue(bookmarkSkipBlock.contains("settleCurrentCloze(\"bookmarked\""))
        assertTrue(bookmarkSkipBlock.contains("rollCloze()"))
        assertFalse("bookmark-and-skip must not advance solved count", bookmarkSkipBlock.contains("currentClozeIndex++"))
        assertFalse("bookmark-and-skip must never pass the challenge", bookmarkSkipBlock.contains("onAnyChallengePassed()"))
    }

    private fun invokeClozeSanitizer(input: String): String {
        val clazz = try {
            Class.forName("com.ironwill.app.ui.ClozeChallengeUi")
        } catch (_: ClassNotFoundException) {
            fail("ClozeChallengeUi is missing")
            return input
        }
        val method = try {
            clazz.getDeclaredMethod("sanitizePassageForDisplay", String::class.java)
        } catch (_: NoSuchMethodException) {
            fail("sanitizePassageForDisplay is missing")
            return input
        }
        method.isAccessible = true
        return method.invoke(null, input) as String
    }

    private fun source(relativePath: String): String =
        sourceOrNull(relativePath) ?: error("cannot find $relativePath")

    private fun sourceOrNull(relativePath: String): String? =
        generateSequence(File(System.getProperty("user.dir") ?: ".").absoluteFile) { it.parentFile }
            .map { File(it, relativePath) }
            .firstOrNull { it.isFile }
            ?.readText()
}
