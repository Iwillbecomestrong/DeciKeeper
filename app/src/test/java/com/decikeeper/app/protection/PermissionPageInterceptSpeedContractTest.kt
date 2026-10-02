package com.decikeeper.app.protection

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 自启动 / 关联启动 / 悬浮窗页面（含 vivo 单独的全局管理页）的拦截速度契约，
 * 以及无障碍掉线时开关显示不得被改成「未开启」的 UI 契约。
 */
class PermissionPageInterceptSpeedContractTest {

    private fun read(path: String): String {
        val cwd = File(System.getProperty("user.dir") ?: ".")
        return listOf(File(cwd, path), File(cwd, path.removePrefix("app/")), File(cwd.parentFile ?: cwd, path))
            .firstOrNull { it.isFile }?.readText() ?: error("Cannot locate $path")
    }

    @Test
    fun detectionDebounceIsShort() {
        assertTrue(PermissionProtection.DETECTION_DEBOUNCE_MS <= 200L)
    }

    @Test
    fun fastNativeSearchRunsBeforeRecursiveScan() {
        val src = read("app/src/main/java/com/decikeeper/app/protection/PermissionProtection.kt")
        assertTrue(src.contains("hasProtectedAppNameFast(root) || hasProtectedAppName(root)"))
        assertTrue(src.contains("findAccessibilityNodeInfosByText"))
        assertEquals(listOf("决定守护", "DeciKeeper"), PermissionProtection.FAST_SEARCH_APP_NAMES)
    }

    @Test
    fun permissionProtectionRunsBeforeHeavyFullTreeScans() {
        val src = read("app/src/main/java/com/decikeeper/app/protection/ProtectionManager.kt")
        val perm = src.indexOf("permissionProtection?.check(")
        val anti = src.indexOf("antiUninstallProtection?.check(")
        assertTrue(perm in 0 until anti)
        // 只允许调用一次，避免重复扫描
        assertEquals(1, src.split("permissionProtection?.check(").size - 1)
    }

    @Test
    fun protectionSwitchesDoNotFollowLiveAccessibilityState() {
        val src = read("app/src/main/java/com/decikeeper/app/ui/RulesFragment.kt")
        val alarm = src.substringAfter("private fun updateAlarmPowerOffProtectionUI")
            .substringBefore("private fun setupFloatingGuardSwitch")
        assertTrue(alarm.contains("switchAlarmPowerOffProtection?.isChecked = configuredEnabled"))
        assertFalse(alarm.contains("switchAlarmPowerOffProtection?.isChecked = enabled"))
    }
}
