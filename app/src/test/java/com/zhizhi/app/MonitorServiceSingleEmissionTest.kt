package com.zhizhi.app

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test


class MonitorServiceSingleEmissionTest {
    @Test
    fun normalForegroundAndLifecycleFactsHaveNoLegacyDuplicate() {
        val monitor = source("app/src/main/java/com/zhizhi/app/service/MonitorService.kt")
        val core = source("app/src/main/java/com/zhizhi/app/service/CoreService.kt")

        assertFalse(monitor.contains("fun logForegroundResolution("))
        assertFalse(monitor.contains("[锁机] Blacklist app"))
        assertFalse(monitor.contains("[AUTO_RELEASE] pkg="))
        assertFalse(core.contains("[RESTORE_REQUEST] source=CoreService"))

        assertTrue(monitor.contains("foregroundNormalEventBridge"))
        assertTrue(monitor.contains("recordFgRequestLock("))
        assertTrue(monitor.contains("recordFgNoLock("))
    }

    private fun source(relativePath: String): String =
        generateSequence(File(System.getProperty("user.dir") ?: ".").absoluteFile) { it.parentFile }
            .map { File(it, relativePath) }
            .firstOrNull { it.isFile }
            ?.readText()
            ?: error("cannot find $relativePath")
}
