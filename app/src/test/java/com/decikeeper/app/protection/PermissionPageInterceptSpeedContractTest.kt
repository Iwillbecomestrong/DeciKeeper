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
    @Test
    fun vivoGlobalListPagesAreBlockedImmediately() {
        val pkg = "com.vivo.permissionmanager"
        assertTrue(PermissionProtection.isVivoGlobalListPage(pkg, "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"))
        assertTrue(PermissionProtection.isVivoGlobalListPage(pkg, "com.vivo.permissionmanager.activity.FloatWindowManagerActivity"))
    }

    @Test
    fun perAppDetailPageAndOtherPackagesAreNotBlanketBlocked() {
        // 其他应用的应用详情页不得被整页拦截（仍按「识别到决定守护才拦」）
        assertFalse(
            PermissionProtection.isVivoGlobalListPage(
                "com.vivo.permissionmanager",
                "com.vivo.permissionmanager.activity.SoftPermissionDetailActivity"
            )
        )
        // 非 vivo 权限管理包不受影响，即使类名相似
        assertFalse(PermissionProtection.isVivoGlobalListPage("com.android.settings", "BgStartUpManagerActivity"))
        assertFalse(PermissionProtection.isVivoGlobalListPage("com.example.app", "FloatWindowManagerActivity"))
    }

    @Test
    fun globalListBlockHappensBeforeAnyTreeScan() {
        val src = read("app/src/main/java/com/decikeeper/app/protection/PermissionProtection.kt")
        val body = src.substringAfter("fun check(service: AccessibilityService")
        val block = body.indexOf("isVivoGlobalListPage(pkg, eventClassName)")
        val scan = body.indexOf("hasProtectedAppNameFast(root)")
        assertTrue(block in 0 until scan)
    }

    @Test
    fun enhancementStatusTextsShowInactiveWhenAccessibilityDown() {
        val src = read("app/src/main/java/com/decikeeper/app/ui/RulesFragment.kt")
        assertTrue(src.contains("private fun a11yInactiveSuffix"))
        // 桌面守护小浮窗 / 小窗可见性增强 / 隐私空间保护 都必须使用该提示
        assertTrue(src.substringAfter("private fun updateFloatingGuardUI").substringBefore("setupPrivacySpaceGuardSwitch").contains("a11yInactiveSuffix"))
        assertTrue(src.substringAfter("private fun updateFloatingWindowProtectionUI").substringBefore("private fun updateAccessibilityServiceUI").contains("a11yInactiveSuffix"))
        assertTrue(src.substringAfter("private fun updatePrivacySpaceGuardUI").substringBefore("隐私空间拦截开启引导弹窗").contains("a11yInactiveSuffix"))
    }
}
