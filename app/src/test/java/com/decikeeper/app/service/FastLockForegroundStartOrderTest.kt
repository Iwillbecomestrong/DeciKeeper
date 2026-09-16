package com.decikeeper.app.service

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class FastLockForegroundStartOrderTest {

    @Test
    fun antiUninstallGateMustRunBeforeForegroundServiceIsRequested() {
        val source = source("app/src/main/java/com/decikeeper/app/manager/StartupProtectionManager.kt")
        val startFunction = source.substringAfter("fun startFastLock(")
        val gate = startFunction.indexOf("shouldKeepInitialization")
        val foregroundStart = startFunction.indexOf("context.startForegroundService(intent)")

        assertTrue(gate >= 0)
        assertTrue(foregroundStart >= 0)
        assertTrue(gate < foregroundStart)
    }

    @Test
    fun fastLockServiceRegistersForegroundBeforeItsInternalFallbackGate() {
        val source = source("app/src/main/java/com/decikeeper/app/service/FastLockService.kt")
        val onCreate = source.substringAfter("override fun onCreate()")
        val foreground = onCreate.indexOf("startForegroundCompat()")
        val gate = onCreate.indexOf("shouldKeepInitialization")

        assertTrue(foreground >= 0)
        assertTrue(gate >= 0)
        assertTrue(foreground < gate)
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
