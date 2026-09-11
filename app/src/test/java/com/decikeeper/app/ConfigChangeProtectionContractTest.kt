package com.decikeeper.app

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfigChangeProtectionContractTest {

    @Test
    fun configurationChangeGatesBeforeStartingProtection() {
        val source = source("app/src/main/java/com/decikeeper/app/service/CoreService.kt")
        val gate = source.indexOf("StartupProtectionManager.shouldKeepInitialization")
        val start = source.indexOf("StartupProtectionManager.startFastLock")

        assertTrue(gate >= 0)
        assertTrue(start >= 0)
        assertTrue(gate < start)
    }

    @Test
    fun fastLockAndPhysicalOverlayShareTheSameGate() {
        val fastLock = source("app/src/main/java/com/decikeeper/app/service/FastLockService.kt")
        val lockWindow = source("app/src/main/java/com/decikeeper/app/ui/LockWindowManager.kt")
        val startup = source("app/src/main/java/com/decikeeper/app/manager/StartupProtectionManager.kt")

        assertTrue(fastLock.contains("StartupProtectionManager.shouldKeepInitialization"))
        assertTrue(lockWindow.contains("StartupProtectionManager.shouldKeepInitialization"))
        assertTrue(startup.contains("AntiUninstallManager.shouldShowInitialization"))
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
