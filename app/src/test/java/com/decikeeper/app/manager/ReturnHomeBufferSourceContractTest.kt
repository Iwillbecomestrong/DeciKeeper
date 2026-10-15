package com.decikeeper.app.manager

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class ReturnHomeBufferSourceContractTest {
    private fun source(path: String): String =
        generateSequence(File(System.getProperty("user.dir") ?: ".").absoluteFile) { it.parentFile }
            .map { File(it, path) }.first { it.isFile }.readText()

    @Test fun returnButtonAlwaysExitsWithoutCountingOrCharge() {
        val lock = source("app/src/main/java/com/decikeeper/app/ui/LockWindowManager.kt")
        val start = lock.indexOf("btnBackToHome?.setSafeOnClickListener")
        val end = lock.indexOf("纯粹三按钮架构点击逻辑", start)
        val click = lock.substring(start, end)
        assertTrue(click.contains("performReturnHome()"))
        listOf("onReturnHome", "StrictExit", "isStrict", "onRestriction").forEach {
            assertFalse("返回只放行一次并冷却，不再计数或续期: $it", lock.contains(it))
        }
        listOf("consumePenaltySeconds(", "issuePenaltyTransactionOnce", "mPendingReturnHomeAction", "FrequentReturnDeduction").forEach {
            assertFalse("obsolete return charge path: $it", lock.contains(it))
        }
    }

    @Test fun monitorAndPresenceUseTheSharedDeadlineAndCanCancelExistingGrace() {
        val monitor = source("app/src/main/java/com/decikeeper/app/service/MonitorService.kt")
        assertFalse(monitor.contains("mPresenceTracker.onDesktopReturn(now)"))
        assertTrue(monitor.contains("mPresenceTracker.onDesktopReturn(now, LockWindowManager.getDesktopReturnGraceUntil(now))"))
        assertTrue(monitor.contains("mPresenceTracker.limitDesktopGrace(LockWindowManager.getDesktopReturnGraceUntil(now))"))
        val layout = source("app/src/main/res/layout/window_lock_screen.xml")
        assertFalse(layout.contains("view_penalty_overlay"))
        assertFalse(layout.contains("btn_strict_exit"))
    }
}
