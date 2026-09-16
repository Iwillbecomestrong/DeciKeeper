package com.decikeeper.app.protection

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import com.decikeeper.app.manager.AntiUninstallManager

class AntiUninstallInitializationGateTest {

    @Test
    fun onlyActiveOrPendingStateMayShowInitialization() {
        assertTrue(AntiUninstallManager.shouldShowInitialization(AntiUninstallManager.State.ACTIVE))
        assertTrue(AntiUninstallManager.shouldShowInitialization(AntiUninstallManager.State.PENDING))
        assertFalse(AntiUninstallManager.shouldShowInitialization(AntiUninstallManager.State.DISABLED))
    }

    @Test
    fun antiUninstallManagerExposesActiveOrPendingInitializationGate() {
        val manager = source("app/src/main/java/com/decikeeper/app/manager/AntiUninstallManager.kt")
        assertTrue(manager.contains("shouldShowInitialization"))
    }

    @Test
    fun configurationChangeAndAlarmDismissConsultAntiUninstallState() {
        val coreService = source("app/src/main/java/com/decikeeper/app/service/CoreService.kt")
        val alarmOverlay = source("app/src/main/java/com/decikeeper/app/ui/LockAlarmOverlay.kt")
        val lockWindow = source("app/src/main/java/com/decikeeper/app/ui/LockWindowManager.kt")

        assertTrue(coreService.contains("StartupProtectionManager.shouldKeepInitialization"))
        assertTrue(alarmOverlay.contains("StartupProtectionManager.shouldKeepInitialization"))
        assertTrue(lockWindow.contains("StartupProtectionManager.shouldKeepInitialization"))
    }

    private fun source(path: String): String {
        val cwd = File(System.getProperty("user.dir") ?: ".")
        val candidates = listOf(
            File(cwd, path),
            File(cwd, path.removePrefix("app/")),
            File(cwd.parentFile ?: cwd, path),
            File(cwd.parentFile ?: cwd, path.removePrefix("app/"))
        )
        return candidates.firstOrNull { it.isFile }?.readText()
            ?: error("Cannot locate $path from ${cwd.absolutePath}")
    }
}
