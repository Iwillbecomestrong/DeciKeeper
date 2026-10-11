package com.decikeeper.app.ui

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
    fun secondaryEmergencyEntryAndExecutionAreRemoved() {
        val lock = source("src/main/java/com/decikeeper/app/ui/LockWindowManager.kt")
        val layout = source("src/main/res/layout/window_lock_screen.xml")
        assertFalse(layout.contains("btn_temporary_access_quick_10"))
        assertFalse(lock.contains("showEmergencyTenConfirmDialog"))
        assertFalse(lock.contains("scheduleEmergencyRelock"))
        assertFalse(lock.contains("temporaryEmergencyRemainingUsesInCooldown"))
        assertFalse(lock.contains("temp_emergency_"))
        assertFalse(lock.contains("recordEmergencyAccessStarted"))
    }

    @Test
    fun userFacingTemporaryAccessUsesCanonicalName() {
        val files = listOf(
            source("src/main/java/com/decikeeper/app/ui/HelpActivity.kt"),
            source("src/main/java/com/decikeeper/app/ui/LockWindowManager.kt"),
            source("src/main/res/layout/window_lock_screen.xml"),
            source("src/main/res/layout/activity_global_params_new.xml"),
            source("../docs/user/getting-started.md"),
            source("../docs/user/guide.md"),
            source("../docs/user/permissions.md")
        )
        val joined = files.joinToString("\n")

        assertFalse(joined.contains("超级临时"))
        // 「超级临时放行」被下一条的「临时放行」覆盖，保留此行以标明历史术语
        assertFalse(joined.contains("超级临时放行"))
        // 正式术语只有「临时解锁」；「临时放行」曾长期混用于用户可见文案，
        // 且原断言只拦「超级临时」，导致 Toast / 全局参数页仍在使用旧词。
        assertFalse(
            "用户可见文案不得再使用「临时放行」，正式术语是「临时解锁」",
            joined.contains("临时放行")
        )
        assertTrue(joined.contains("临时解锁"))
    }
}
