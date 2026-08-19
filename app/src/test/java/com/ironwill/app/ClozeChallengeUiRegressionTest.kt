package com.ironwill.app

import com.ironwill.app.manager.AlarmChallengeManager
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClozeChallengeUiRegressionTest {

    @Test
    fun cleanPassageKeepsOnlyTheSingleFourUnderscoreTargetBlank() {
        val dirty = "Too often, careless use of words __ ____ a meeting. The words may ___ stir up reactions."

        val cleaned = AlarmChallengeManager.cleanPassage(dirty)

        assertEquals(1, Regex("_{4}").findAll(cleaned).count())
        assertFalse(Regex("(?<!_)_{1,3}(?!_)").containsMatchIn(cleaned))
        assertTrue(cleaned.contains("words ____ a meeting"))
        assertTrue(cleaned.contains("words may stir up reactions"))
    }

    @Test
    fun clozeOptionButtonsAllowLongAnswersToWrapInsteadOfClipping() {
        val layout = source("app/src/main/res/layout/window_alarm_overlay.xml")
        listOf("btn_cloze_a", "btn_cloze_b", "btn_cloze_c", "btn_cloze_d").forEach { id ->
            val block = layout.substringAfter("@+id/$id").substringBefore("/>")
            assertTrue("$id must be tall enough for two lines", block.contains("android:layout_height=\"64dp\""))
            assertTrue("$id must allow two lines", block.contains("android:maxLines=\"2\""))
            assertTrue("$id must shrink long choices when needed", block.contains("android:autoSizeTextType=\"uniform\""))
        }
    }

    @Test
    fun wordDetailsAreRenderedInsideTheAlarmRootInsteadOfACompetingDialogWindow() {
        val overlay = source("app/src/main/java/com/ironwill/app/ui/LockAlarmOverlay.kt")
        val dialogFunction = overlay
            .substringAfter("private fun showWordBookmarkDialog")
            .substringBefore("private fun rollMath")

        assertTrue(dialogFunction.contains("R.id.root_container"))
        assertTrue(dialogFunction.contains("root.addView"))
        assertFalse(dialogFunction.contains("AlertDialog.Builder"))
        assertFalse(dialogFunction.contains("window?.setType"))
    }

    private fun source(relativePath: String): String =
        generateSequence(File(System.getProperty("user.dir") ?: ".").absoluteFile) { it.parentFile }
            .map { File(it, relativePath) }
            .firstOrNull { it.isFile }
            ?.readText()
            ?: error("cannot find $relativePath")
}
