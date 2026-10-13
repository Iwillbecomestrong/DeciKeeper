package com.decikeeper.app.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VisiblePackagesDiagnosticTest {

    @Test
    fun marksTombstonedPackagesAndKeepsTheFragmentFreeOfWhitespace() {
        val text = VisiblePackagesDiagnostic.render(
            visible = setOf("com.tencent.mm", "com.bbk.launcher2"),
            closed = setOf("com.tencent.mm"),
            source = VisiblePackagesDiagnostic.SRC_ACTIVE_FOCUSED_SCAN,
            ageMs = 1200L
        )

        assertEquals(
            "visible:com.bbk.launcher2,com.tencent.mm(CLOSED);src:ACTIVE_FOCUSED_SCAN;ageMs:1200;closed:com.tencent.mm",
            text
        )
        assertFalse("日志字段值不能含空白，否则会破坏 key=value 解析", text.any { it.isWhitespace() })
    }

    @Test
    fun emptySetsAreWrittenAsNoneSoAnAbsentWindowIsDistinguishableFromAMissingField() {
        val text = VisiblePackagesDiagnostic.render(
            visible = emptySet(),
            closed = emptySet(),
            source = VisiblePackagesDiagnostic.SRC_NONE,
            ageMs = -1L
        )

        assertEquals("visible:NONE;src:NONE;ageMs:-1;closed:NONE", text)
    }

    @Test
    fun aTombstoneForAPackageThatIsNotVisibleIsStillListed() {
        val text = VisiblePackagesDiagnostic.render(
            visible = setOf("com.android.systemui"),
            closed = setOf("com.tencent.mm"),
            source = VisiblePackagesDiagnostic.SRC_ALL_WINDOWS_FUSED,
            ageMs = 50L
        )

        assertTrue(text.contains("visible:com.android.systemui;"))
        assertTrue(text.endsWith("closed:com.tencent.mm"))
    }

    @Test
    fun longPackageListsAreCappedAndReportTheRemainder() {
        val visible = (1..11).map { "pkg.p%02d".format(it) }.toSet()

        val text = VisiblePackagesDiagnostic.render(visible, emptySet(), "S", 0L)

        assertTrue(text.contains("pkg.p08,+3;"))
        assertFalse(text.contains("pkg.p09"))
    }

    @Test
    fun trackerSnapshotListsOnlyPackagesCurrentlyHeldInTheClosedTombstone() {
        val tracker = VisibleBlacklistPresenceTracker()
        tracker.onUsageEvent("com.tencent.mm", 1, 1_000L) // MOVE_TO_FOREGROUND
        tracker.onUsageEvent("com.tencent.mm", 23, 2_000L) // ACTIVITY_STOPPED
        tracker.onUsageEvent("com.tencent.mqq", 1, 1_500L)

        assertEquals(setOf("com.tencent.mm"), tracker.closedPackagesSnapshot())

        tracker.onUsageEvent("com.tencent.mm", 1, 3_000L) // 再次进入前台，墓碑失效
        assertEquals(emptySet<String>(), tracker.closedPackagesSnapshot())
    }
}
