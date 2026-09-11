package com.decikeeper.app.service

import com.decikeeper.app.manager.FloatingWindowProtectionManager
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class VisibleBlacklistPresenceAccessibilityGateTest {

    private val targetPkg = "com.tencent.mm"
    private var originalServiceActive = false
    private var originalVisiblePackages: Set<String> = emptySet()
    private val context = RuntimeEnvironment.getApplication()

    @Before
    fun saveCoreServiceState() {
        originalServiceActive = CoreService.isServiceActive
        originalVisiblePackages = CoreService.lastVisiblePackagesFromWindows
        context.getSharedPreferences(FloatingWindowProtectionManager.PREFS_NAME, 0)
            .edit().clear().commit()
    }

    @After
    fun restoreCoreServiceState() {
        CoreService.isServiceActive = originalServiceActive
        CoreService.lastVisiblePackagesFromWindows = originalVisiblePackages
        context.getSharedPreferences(FloatingWindowProtectionManager.PREFS_NAME, 0)
            .edit().clear().commit()
    }

    @Test
    fun `no accessibility rejects UsageStats-only small-window presence`() {
        CoreService.isServiceActive = false
        CoreService.lastVisiblePackagesFromWindows = setOf(targetPkg)
        val tracker = VisibleBlacklistPresenceTracker(desktopGraceDurationMs = 3000L)

        assertFalse(tracker.isLastTimeVisibleValid(targetPkg, 10_000L))
    }

    @Test
    fun `no accessibility prevents grace-expiry small-window relock`() {
        CoreService.isServiceActive = false
        CoreService.lastVisiblePackagesFromWindows = setOf(targetPkg)
        val tracker = VisibleBlacklistPresenceTracker(desktopGraceDurationMs = 3000L)

        tracker.onPresenceConfirmed(targetPkg, 10_000L)
        tracker.onDesktopReturn(11_000L)

        assertEquals(PresenceTrackerAction.NONE, tracker.evaluate(14_100L))
    }

    @Test
    fun `accessibility active but target window absent rejects UsageStats presence`() {
        CoreService.isServiceActive = true
        CoreService.lastVisiblePackagesFromWindows = setOf("com.bbk.launcher2")
        val tracker = VisibleBlacklistPresenceTracker(desktopGraceDurationMs = 3000L)

        assertFalse(tracker.isLastTimeVisibleValid(targetPkg, 10_000L))
    }

    @Test
    fun `accessibility active and target window visible allows clean presence candidate`() {
        CoreService.isServiceActive = true
        CoreService.lastVisiblePackagesFromWindows = setOf(targetPkg, "com.bbk.launcher2")
        val tracker = VisibleBlacklistPresenceTracker(desktopGraceDurationMs = 3000L)

        assertTrue(tracker.isLastTimeVisibleValid(targetPkg, 10_000L))
    }

    @Test
    fun `disabled floating protection rejects accessibility-backed presence`() {
        CoreService.isServiceActive = true
        CoreService.lastVisiblePackagesFromWindows = setOf(targetPkg)
        val tracker = VisibleBlacklistPresenceTracker(
            desktopGraceDurationMs = 3000L,
            protectionContext = context
        )

        assertFalse(tracker.isLastTimeVisibleValid(targetPkg, 10_000L))

        context.getSharedPreferences(FloatingWindowProtectionManager.PREFS_NAME, 0)
            .edit()
            .putBoolean(FloatingWindowProtectionManager.KEY_IS_ENABLED, true)
            .commit()
        assertTrue(tracker.isLastTimeVisibleValid(targetPkg, 10_000L))
    }
}
