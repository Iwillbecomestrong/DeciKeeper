package com.decikeeper.app.protection

import android.app.Application
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.ResolveInfo
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class ProtectionContentScopeTest {
    private val context get() = RuntimeEnvironment.getApplication()

    @Test
    fun `ordinary apps and misleading names do not authorize content inspection`() {
        listOf("com.tencent.mm", "com.eg.android.AlipayGphone", "com.android.chrome",
            "com.example.game", "com.example.launcher", "com.example.security",
            "com.android.settings.fake", context.packageName, "", null).forEach {
            assertFalse("Must not inspect $it", ProtectionManager.shouldInspectContent(context, it))
        }
    }

    @Test
    fun `existing system protection surfaces remain available`() {
        val packages = AntiUninstallProtection.MONITORED_PACKAGES +
            AntiUninstallProtection.LAUNCHER_PACKAGES +
            AntiUninstallProtection.PRIVATE_SPACE_PACKAGES +
            PermissionProtection.SETTINGS_PACKAGES + AccessibilityProtection.RELEVANT_PACKAGES + "android"
        packages.forEach {
            assertTrue("Protection surface lost: $it", ProtectionManager.shouldInspectContent(context, it))
        }
    }

    @Test
    fun `actual third party home is allowed without a launcher name heuristic`() {
        val pkg = "com.example.desktop"
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        shadowOf(context.packageManager).addResolveInfoForIntent(home, ResolveInfo().apply {
            activityInfo = ActivityInfo().apply {
                packageName = pkg
                name = "$pkg.Home"
                applicationInfo = ApplicationInfo().apply { packageName = pkg }
            }
        })
        assertTrue(ProtectionManager.shouldInspectContent(context, pkg))
        assertFalse(ProtectionManager.shouldInspectContent(context, "com.example.launcher"))
    }

    @Test
    fun `third party uninstall capability permits inspection and removal drops access`() {
        val pkg = "com.example.appmanager"
        val permission = "android.permission.REQUEST_DELETE_PACKAGES"
        val pm = shadowOf(context.packageManager)
        pm.installPackage(PackageInfo().apply {
            packageName = pkg
            applicationInfo = ApplicationInfo().apply { packageName = pkg }
            requestedPermissions = arrayOf(permission)
            requestedPermissionsFlags = intArrayOf(PackageInfo.REQUESTED_PERMISSION_GRANTED)
        })
        assertTrue(ProtectionManager.canRequestUninstall(context, pkg))
        assertTrue(ProtectionManager.shouldInspectContent(context, pkg))
        pm.removePackage(pkg)
        assertFalse(ProtectionManager.shouldInspectContent(context, pkg))
    }
}
