package com.ironwill.app

import com.ironwill.app.data.DefaultNormalEventRenderer
import com.ironwill.app.data.EventClock
import com.ironwill.app.data.NormalEvent
import com.ironwill.app.data.NormalEventDraft
import com.ironwill.app.ui.LockLifecycleEventBridge
import com.ironwill.app.util.ForegroundTransition
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test








class LockExitEvidenceDedupTest {
    @Test
    fun failedExitRendersOneCanonicalEvidenceKeyAndNoLegacyLifecycleText() {
        val captured = mutableListOf<NormalEventDraft>()
        val bridge = LockLifecycleEventBridge(
            clock = FixedClock,
            emit = captured::add
        )

        bridge.recordReturnDesktopExit(
            exitRequestId = "EXIT-1",
            lockRequestId = "LOCK-1",
            transition = ForegroundTransition("com.ss.android.ugc.aweme", "桌面"),
            result = "FAILED",
            reason = "DESKTOP_NOT_CONFIRMED",
            evidence = "DESKTOP_NOT_CONFIRMED package=com.ss.android.ugc.aweme evidence=EVENTS"
        )

        val text = DefaultNormalEventRenderer.render(
            NormalEvent("process-1", "process", 1L, captured.single())
        )
        assertEquals(1, Regex("""(?:^|\s)evidence=""").findAll(text).count())
        assertTrue(text.contains("evidence=DESKTOP_NOT_CONFIRMED|EVENTS"))

        val source = source("app/src/main/java/com/ironwill/app/ui/LockWindowManager.kt")
        assertFalse(source.contains("LogExporter.i(\"LockScreen\", \"解锁:"))
        assertFalse(source.contains("LogExporter.i(\"LockScreen\", \"🏠 回到桌面"))
        assertFalse(source.contains("LogExporter.i(\"TempAccess\", \"临时解锁:"))
    }

    @Test
    fun canonicalizerPreservesKeyValueEvidenceTokens() {
        val captured = mutableListOf<NormalEventDraft>()
        val bridge = LockLifecycleEventBridge(
            clock = FixedClock,
            emit = captured::add
        )

        // Case 1: key=value token with no marker — must be preserved verbatim.
        bridge.recordReturnDesktopExit(
            exitRequestId = "EXIT-CASE-1",
            lockRequestId = "LOCK-CASE-1",
            transition = ForegroundTransition("抖音", "桌面"),
            result = "FAILED",
            reason = "ACCESSIBILITY",
            evidence = "ACCESSIBILITY=抖音"
        )

        // Case 2: leading reason followed by source=... and package=... — package dropped.
        bridge.recordReturnDesktopExit(
            exitRequestId = "EXIT-CASE-2",
            lockRequestId = "LOCK-CASE-2",
            transition = ForegroundTransition("com.ss.android.ugc.aweme", "桌面"),
            result = "FAILED",
            reason = "FOREGROUND_CONFIRMED",
            evidence = "FOREGROUND_CONFIRMED source=EVENTS package=com.ss.android.ugc.aweme"
        )

        // Case 3: pending timeout with foreground=... — no `=` in leading segment, must keep it.
        bridge.recordReturnDesktopExit(
            exitRequestId = "EXIT-CASE-3",
            lockRequestId = "LOCK-CASE-3",
            transition = ForegroundTransition("抖音", "桌面"),
            result = "FAILED",
            reason = "PENDING_TIMEOUT",
            evidence = "PENDING_TIMEOUT foreground=抖音"
        )

        // Case 4: already-canonical LAUNCHER_VISIBLE+ACCESSIBILITY must round-trip unchanged.
        bridge.recordReturnDesktopExit(
            exitRequestId = "EXIT-CASE-4",
            lockRequestId = "LOCK-CASE-4",
            transition = ForegroundTransition("抖音", "桌面"),
            result = "DESKTOP_VISIBLE",
            evidence = "LAUNCHER_VISIBLE+ACCESSIBILITY"
        )

        // Case 5: canonicalized pipe-joined input — must be idempotent.
        bridge.recordReturnDesktopExit(
            exitRequestId = "EXIT-CASE-5",
            lockRequestId = "LOCK-CASE-5",
            transition = ForegroundTransition("com.ss.android.ugc.aweme", "桌面"),
            result = "FAILED",
            reason = "DESKTOP_NOT_CONFIRMED",
            evidence = "DESKTOP_NOT_CONFIRMED|EVENTS"
        )

        assertEquals(5, captured.size)
        assertEvidence(captured[0], "ACCESSIBILITY=抖音")
        assertEvidence(captured[1], "FOREGROUND_CONFIRMED|source=EVENTS")
        assertEvidence(captured[2], "PENDING_TIMEOUT|foreground=抖音")
        assertEvidence(captured[3], "LAUNCHER_VISIBLE+ACCESSIBILITY")
        assertEvidence(captured[4], "DESKTOP_NOT_CONFIRMED|EVENTS")
    }

    private fun assertEvidence(event: NormalEventDraft, expected: String) {
        val actual = event.fields["evidence"]
        assertEquals(
            "${event.type} ${event.fields["exitRequestId"]} evidence mismatch",
            expected,
            actual
        )
    }

    private fun source(relativePath: String): String =
        generateSequence(File(System.getProperty("user.dir") ?: ".").absoluteFile) { it.parentFile }
            .map { File(it, relativePath) }
            .firstOrNull { it.isFile }
            ?.readText()
            ?: error("cannot find $relativePath")

    private object FixedClock : EventClock {
        override fun wallNowMs() = 1_720_000_000_000L
        override fun elapsedNowMs() = 42L
    }
}
