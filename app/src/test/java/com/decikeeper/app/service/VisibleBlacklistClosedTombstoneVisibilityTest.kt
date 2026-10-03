package com.decikeeper.app.service

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 回归：小窗被关闭后，无障碍「事件-快照融合」会让该包在接下来 3 秒仍出现在可见集合里。
 * 「回桌面 3 秒宽限」一结束，Anti-Deadlock 就把它当作「物理可见」清掉抑制并重新上锁。
 * ACTIVITY_STOPPED 的关闭墓碑是权威证据，必须压过这种滞留的可见集合。
 */
class VisibleBlacklistClosedTombstoneVisibilityTest {

    private val MOVE_TO_FG = 1
    private val ACTIVITY_STOPPED = 23
    private val pkg = "com.ss.android.ugc.aweme"
    private val lingeringVisible = setOf(pkg, "com.bbk.launcher2")

    private fun newTracker() = VisibleBlacklistPresenceTracker(desktopGraceDurationMs = 3000L)

    @Test
    fun `window set that still lists a just-closed small window does not count as visible`() {
        val tracker = newTracker()
        tracker.onUsageEvent(pkg, MOVE_TO_FG, 10_000L)
        tracker.onUsageEvent(pkg, ACTIVITY_STOPPED, 13_000L)

        assertFalse(tracker.isPhysicallyVisible(pkg, lingeringVisible))
    }

    @Test
    fun `reopening the small window after it was closed makes it visible again`() {
        val tracker = newTracker()
        tracker.onUsageEvent(pkg, MOVE_TO_FG, 10_000L)
        tracker.onUsageEvent(pkg, ACTIVITY_STOPPED, 13_000L)
        tracker.onUsageEvent(pkg, MOVE_TO_FG, 20_000L)

        assertTrue(tracker.isPhysicallyVisible(pkg, lingeringVisible))
    }

    @Test
    fun `package present in windows with no lifecycle history is still visible`() {
        assertTrue(newTracker().isPhysicallyVisible(pkg, lingeringVisible))
    }

    @Test
    fun `package absent from the window set is not visible`() {
        assertFalse(newTracker().isPhysicallyVisible(pkg, setOf("com.bbk.launcher2")))
    }

    @Test
    fun `monitor service consults the close tombstone before treating a pending package as visible`() {
        val src = source("app/src/main/java/com/decikeeper/app/service/MonitorService.kt")

        assertFalse(
            "raw window set membership must not clear the pending home-click suppression",
            src.contains("CoreService.lastVisiblePackagesFromWindows.contains(pendingPkg)")
        )
        assertTrue(src.contains("mPresenceTracker.isPhysicallyVisible(pendingPkg, CoreService.lastVisiblePackagesFromWindows)"))
        assertTrue(src.contains("mPresenceTracker.isPhysicallyVisible(pkg, visiblePkgs)"))
    }

    private fun source(path: String): String {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            val f = File(dir, path)
            if (f.exists()) return f.readText()
            dir = dir.parentFile
        }
        error("source not found: $path")
    }
}
