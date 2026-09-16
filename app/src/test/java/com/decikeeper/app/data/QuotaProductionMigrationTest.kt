package com.decikeeper.app.data

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test


class QuotaProductionMigrationTest {
    @Test
    fun quotaProductionUsesScopedRawLogsAndCompletedOutcomeEvents() {
        val credit = source("app/src/main/java/com/decikeeper/app/manager/CreditManager.kt")
        val lock = source("app/src/main/java/com/decikeeper/app/ui/LockWindowManager.kt")
        val home = source("app/src/main/java/com/decikeeper/app/ui/HomeFragment.kt")

        assertFalse(credit.contains("fun logDailyStatus("))
        assertFalse(lock.contains("CreditManager.logDailyStatus("))
        assertFalse(home.contains("CreditManager.logDailyStatus("))

        assertGuarded(credit, "[TIME_CONSUME]", "DefaultLogDebugConfig.quotaCalculation")
        assertGuarded(credit, "[STEP1]", "DefaultLogDebugConfig.quotaCalculation")
        assertGuarded(lock, "[BORROW_UI_DEBUG]", "DefaultLogDebugConfig.quotaCalculation")

        assertTrue(credit.contains("quotaConfigEventBridge.recordRestQuotaChange("))
        assertTrue(credit.contains("quotaConfigEventBridge.recordPenalty("))
        assertTrue(credit.contains("quotaConfigEventBridge.recordBorrow("))
    }

    private fun assertGuarded(source: String, token: String, flag: String) {
        val at = source.indexOf(token)
        assertTrue("$token must remain available for scoped diagnostics", at >= 0)
        val prefix = source.substring((at - 1200).coerceAtLeast(0), at)
        assertTrue("$token must be guarded by $flag", prefix.contains("if ($flag)"))
    }

    private fun source(relativePath: String): String =
        generateSequence(File(System.getProperty("user.dir") ?: ".").absoluteFile) { it.parentFile }
            .map { File(it, relativePath) }
            .firstOrNull { it.isFile }
            ?.readText()
            ?: error("cannot find $relativePath")
}