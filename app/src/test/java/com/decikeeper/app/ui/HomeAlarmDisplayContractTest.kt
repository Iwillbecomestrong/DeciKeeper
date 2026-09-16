package com.decikeeper.app.ui

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class HomeAlarmDisplayContractTest {
    @Test
    fun homeAlarmDisplayRefreshDoesNotDependOnCurrentTimeSegment() {
        val cwd = File(System.getProperty("user.dir"))
        val candidates = listOf(
            File(cwd, "src/main/java/com/decikeeper/app/ui/HomeFragment.kt"),
            File(cwd, "app/src/main/java/com/decikeeper/app/ui/HomeFragment.kt"),
            File(cwd.parentFile ?: cwd, "app/src/main/java/com/decikeeper/app/ui/HomeFragment.kt")
        )
        val source = candidates.firstOrNull { it.isFile }?.readText()
            ?: error("Cannot locate HomeFragment.kt from ${cwd.absolutePath}")

        val quotaDisplay = source.indexOf("private fun updateQuotaDisplay()")
        val alarmRefresh = source.indexOf("updateTodayAlarmDisplay()", quotaDisplay)
        val currentSegmentGuard = source.indexOf("if (currentSeg == null)", quotaDisplay)

        assertTrue("updateQuotaDisplay must exist", quotaDisplay >= 0)
        assertTrue("homepage alarm refresh must exist", alarmRefresh >= 0)
        assertTrue(
            "homepage alarm refresh must run before the no-current-segment early return",
            alarmRefresh < currentSegmentGuard
        )
    }
}
