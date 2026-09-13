package com.decikeeper.app.data

import android.content.Context
import com.decikeeper.app.manager.CreditManager
import com.decikeeper.app.manager.TimeRuleManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class AutoBlacklistConfigTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        val prefs = context.getSharedPreferences(CreditManager.PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        val baselinePrefs = context.getSharedPreferences("baseline_prefs", Context.MODE_PRIVATE)
        baselinePrefs.edit().clear().commit()
    }

    @Test
    fun testDefaultAutoBlacklistConfig() {
        assertFalse(BlacklistStore.isAutoBlacklistEnabled(context))
        val categories = BlacklistStore.getAutoBlacklistCategories(context)
        assertEquals(4, categories.size)
        assertTrue(categories.contains("comm"))
        assertTrue(categories.contains("video"))
        assertTrue(categories.contains("game"))
        assertTrue(categories.contains("browser"))
    }

    @Test
    fun testUpdateAutoBlacklistConfig() {
        BlacklistStore.setAutoBlacklistEnabled(context, false)
        assertFalse(BlacklistStore.isAutoBlacklistEnabled(context))

        BlacklistStore.setAutoBlacklistCategories(context, setOf("game", "browser"))
        val categories = BlacklistStore.getAutoBlacklistCategories(context)
        assertEquals(2, categories.size)
        assertTrue(categories.contains("game"))
        assertTrue(categories.contains("browser"))
        assertFalse(categories.contains("comm"))
    }

    @Test
    fun testMatchedCategoryRespectsConfig() {
        // QQ package is in "comm" category
        val qqPkg = "com.tencent.mobileqq"

        // Categories remain available, but new-app auto scanning is opt-in.
        BlacklistStore.setAutoBlacklistEnabled(context, true)
        assertEquals("comm", CreditManager.getMatchedCategory(context, qqPkg))

        // Disable comm category
        BlacklistStore.setAutoBlacklistCategories(context, setOf("video", "game"))
        assertNull(CreditManager.getMatchedCategory(context, qqPkg))

        // Re-enable comm category, but turn off master switch
        BlacklistStore.setAutoBlacklistCategories(context, setOf("comm"))
        BlacklistStore.setAutoBlacklistEnabled(context, false)
        assertNull(CreditManager.getMatchedCategory(context, qqPkg))
    }

    @Test
    fun testTryAutoBlacklistNewPackageWithDisabledConfig() {
        val qqPkg = "com.tencent.mobileqq"

        // Turn off master switch
        BlacklistStore.setAutoBlacklistEnabled(context, false)
        assertFalse(CreditManager.tryAutoBlacklistNewPackage(context, qqPkg))
        assertFalse(BlacklistStore.getBlacklistApps(context).contains(qqPkg))

        // Turn back on
        BlacklistStore.setAutoBlacklistEnabled(context, true)
        assertTrue(CreditManager.tryAutoBlacklistNewPackage(context, qqPkg))
        assertTrue(BlacklistStore.getBlacklistApps(context).contains(qqPkg))
    }

    @Test
    fun testUndoAutoAddedAppIsImmediateAndPreventsReAdd() {
        val qqPkg = "com.tencent.mobileqq"

        // Initial baseline empty
        BlacklistStore.saveBaselineBlacklist(context)

        // Auto-add new app
        BlacklistStore.setAutoBlacklistEnabled(context, true)
        CreditManager.tryAutoBlacklistNewPackage(context, qqPkg)
        assertTrue(BlacklistStore.getBlacklistApps(context).contains(qqPkg))

        // User unchecks qqPkg -> Reverting to baseline
        val result = BlacklistStore.analyzeAndSaveBlacklistApps(context, emptySet())
        assertTrue("Reverting auto-added app should be immediate", result is AnalysisResult.AllImmediate)
        assertFalse(BlacklistStore.getBlacklistApps(context).contains(qqPkg))

        // Should be added to userRemovedApps
        assertTrue(BlacklistStore.getUserRemovedApps(context).contains(qqPkg))

        // Subsequent tryAutoBlacklistNewPackage should skip because it's user-removed
        assertFalse(CreditManager.tryAutoBlacklistNewPackage(context, qqPkg))
    }

    @Test
    fun testAutoBlacklistSyncsToDecoupledSegments() {
        val qqPkg = "com.tencent.mobileqq"
        val existingPkg = "com.example.existing"

        val decoupledSeg = TimeSegment(
            name = "独立时段",
            startHour = 9,
            startMinute = 0,
            endHour = 12,
            endMinute = 0,
            limitMins = 30,
            listApps = listOf(existingPkg),
            listMode = SegmentBlacklist.LIST_MODE_BLACKLIST
        )
        val inheritedSeg = TimeSegment(
            name = "全局继承时段",
            startHour = 14,
            startMinute = 0,
            endHour = 18,
            endMinute = 0,
            limitMins = 30,
            listApps = null
        )

        TimeRuleManager.saveTimeSegments(context, listOf(decoupledSeg, inheritedSeg))
        BlacklistStore.setAutoBlacklistEnabled(context, true)

        val autoAdded = CreditManager.tryAutoBlacklistNewPackage(context, qqPkg)
        assertTrue(autoAdded)

        // 验证全局黑名单包含 qqPkg
        assertTrue(BlacklistStore.getBlacklistApps(context).contains(qqPkg))

        // 验证脱钩时段同步追加了 qqPkg，同时保留了原有应用
        val updatedSegments = TimeRuleManager.getTimeSegments(context)
        val updatedDecoupled = updatedSegments.find { it.name == "独立时段" }
        val updatedInherited = updatedSegments.find { it.name == "全局继承时段" }

        assertTrue(updatedDecoupled?.listApps?.contains(existingPkg) == true)
        assertTrue(updatedDecoupled?.listApps?.contains(qqPkg) == true)
        assertNull(updatedInherited?.listApps)
    }
}
