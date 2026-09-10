package com.decikeeper.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class TemporaryAccessUserFacingCopyContractTest {
    private fun source(path: String): String {
        val cwd = File(System.getProperty("user.dir"))
        val candidates = listOf(
            File(cwd, path),
            File(cwd, "app/$path"),
            File(cwd.parentFile ?: cwd, path),
            File(cwd.parentFile ?: cwd, "app/$path")
        )
        return candidates.firstOrNull { it.isFile }?.readText()
            ?: error("Cannot locate $path from ${cwd.absolutePath}")
    }

    @Test
    fun userFacingTemporaryAccessUsesCanonicalName() {
        val files = listOf(
            source("src/main/java/com/decikeeper/app/ui/HelpActivity.kt"),
            source("src/main/java/com/decikeeper/app/ui/LockWindowManager.kt"),
            source("src/main/res/layout/window_lock_screen.xml"),
            source("src/main/res/layout/activity_global_params_new.xml"),
            source("../docs/使用手册.md")
        )
        val joined = files.joinToString("\n")

        assertFalse(joined.contains("超级临时"))
        assertFalse(joined.contains("超级临时放行"))
        assertTrue(joined.contains("临时解锁"))
    }
}
