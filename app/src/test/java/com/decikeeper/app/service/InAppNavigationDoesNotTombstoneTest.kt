package com.decikeeper.app.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 回归（真机日志 2026-10-13 21:30）：微信从聊天详情页回到主界面时，系统先恢复新页面
 * （LauncherUI RESUMED），随后才停止旧页面（SingleChatInfoUI STOPPED）。旧页面的 STOPPED
 * 不能把仍在屏幕上的微信标成「已关闭」，否则回桌面后小窗仍在，却约 30 秒不重新上锁。
 */
class InAppNavigationDoesNotTombstoneTest {

    private val RESUMED = 15
    private val PAUSED = 16
    private val STOPPED = 23
    private val mm = "com.tencent.mm"
    private val detail = "com.tencent.mm.ui.SingleChatInfoUI"
    private val main = "com.tencent.mm.ui.LauncherUI"
    private val visible = setOf(mm, "com.bbk.launcher2")

    private fun newTracker() = VisibleBlacklistPresenceTracker(desktopGraceDurationMs = 3000L)

    @Test
    fun `stopping the old page after the new page resumed does not close the package`() {
        val tracker = newTracker()
        tracker.onUsageEvent(mm, RESUMED, 1_000L, detail)
        tracker.onUsageEvent(mm, PAUSED, 2_000L, detail)
        tracker.onUsageEvent(mm, RESUMED, 2_000L, main)
        tracker.onUsageEvent(mm, STOPPED, 3_000L, detail)

        assertTrue(tracker.isPhysicallyVisible(mm, visible))
        assertEquals(emptySet<String>(), tracker.closedPackagesSnapshot())
    }

    @Test
    fun `stopping the last resumed page closes the package`() {
        val tracker = newTracker()
        tracker.onUsageEvent(mm, RESUMED, 1_000L, detail)
        tracker.onUsageEvent(mm, RESUMED, 2_000L, main)
        tracker.onUsageEvent(mm, PAUSED, 5_000L, main)
        tracker.onUsageEvent(mm, STOPPED, 6_000L, main)

        assertFalse(tracker.isPhysicallyVisible(mm, visible))
        assertEquals(setOf(mm), tracker.closedPackagesSnapshot())
    }

    @Test
    fun `a stopped event without a class name keeps the previous closing behaviour`() {
        val tracker = newTracker()
        tracker.onUsageEvent(mm, RESUMED, 1_000L, main)
        tracker.onUsageEvent(mm, STOPPED, 2_000L)

        assertFalse(tracker.isPhysicallyVisible(mm, visible))
    }

    @Test
    fun `an old page stopping does not clear an established presence session`() {
        val tracker = newTracker()
        tracker.onUsageEvent(mm, RESUMED, 1_000L, detail)
        tracker.onPresenceConfirmed(mm, 1_500L)
        tracker.onUsageEvent(mm, RESUMED, 2_000L, main)

        val action = tracker.onUsageEvent(mm, STOPPED, 3_000L, detail)

        assertEquals(PresenceTrackerAction.NONE, action)
        assertEquals(PresenceTrackerState.CONFIRMED_VISIBLE, tracker.state)
    }

    @Test
    fun `reopening after a real close makes the package visible again`() {
        val tracker = newTracker()
        tracker.onUsageEvent(mm, RESUMED, 1_000L, main)
        tracker.onUsageEvent(mm, STOPPED, 2_000L, main)
        tracker.onUsageEvent(mm, RESUMED, 3_000L, detail)

        assertTrue(tracker.isPhysicallyVisible(mm, visible))
    }
}
