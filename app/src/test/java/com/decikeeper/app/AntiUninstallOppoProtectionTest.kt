package com.decikeeper.app

import com.decikeeper.app.protection.AntiUninstallProtection
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 验证针对 OPPO (ColorOS) 及通用 OEM 手机的防卸载防护能力：
 * 1. 桌面 Launcher 包名精准识别（com.android.launcher, com.oplus.launcher 等）；
 * 2. 手机管家与应用商店监控包名完整性（com.coloros.phonemanager, com.heytap.market 等）；
 * 3. 桌面长按卸载确认弹窗特征与拦截判定（不误伤长按分享小菜单）；
 * 4. 手机管家批量卸载确认弹窗特征与拦截判定。
 */
class AntiUninstallOppoProtectionTest {

    @Test
    fun colorOsLauncherPackagesMustBeRecognizedAsLaunchers() {
        assertTrue("OPPO 经典默认桌面包名必须识别为 Launcher", AntiUninstallProtection.isLauncherPackage("com.android.launcher"))
        assertTrue("ColorOS 新版桌面包名必须识别为 Launcher", AntiUninstallProtection.isLauncherPackage("com.oplus.launcher"))
        assertTrue("OPPO 历史桌面包名必须识别为 Launcher", AntiUninstallProtection.isLauncherPackage("com.oppo.launcher"))
        assertTrue("OnePlus 桌面包名必须识别为 Launcher", AntiUninstallProtection.isLauncherPackage("com.oneplus.launcher"))
        assertTrue("Vivo 桌面包名必须兼容识别", AntiUninstallProtection.isLauncherPackage("com.vivo.launcher"))
        assertTrue("MIUI 桌面包名必须识别", AntiUninstallProtection.isLauncherPackage("com.miui.home"))
        assertTrue("华为桌面包名必须识别", AntiUninstallProtection.isLauncherPackage("com.huawei.android.launcher"))

        assertFalse("普通第三方应用不能被误判为 Launcher", AntiUninstallProtection.isLauncherPackage("com.tencent.mm"))
        assertFalse("空字符串不能判定为 Launcher", AntiUninstallProtection.isLauncherPackage(""))
    }

    @Test
    fun colorOsPhoneManagerAndMarketMustBeMonitored() {
        val monitored = AntiUninstallProtection.MONITORED_PACKAGES
        assertTrue("ColorOS 手机管家 (phonemanager) 必须受监控", monitored.contains("com.coloros.phonemanager"))
        assertTrue("OPlus 手机管家 (phonemanager) 必须受监控", monitored.contains("com.oplus.phonemanager"))
        assertTrue("ColorOS 欢太软件商店 (heytap.market) 必须受监控", monitored.contains("com.heytap.market"))
        assertTrue("ColorOS 安全中心必须受监控", monitored.contains("com.coloros.safecenter"))
        assertTrue("ColorOS 包安装器必须受监控", monitored.contains("com.oplus.packageinstaller"))
    }

    @Test
    fun oppoDesktopUninstallConfirmationDialogMustTriggerConfirmationPattern() {
        // 真机调试截图 2：桌面长按决定守护弹出的卸载确认框
        val dialogText = "要卸载“决定守护”吗？ 此应用的数据（包括文件和设置数据）将从本设备彻底删除 取消 卸载"

        val hasAppName = AntiUninstallProtection.PROTECTED_APP_NAMES.any { dialogText.contains(it, ignoreCase = true) }
        val hasUninstall = AntiUninstallProtection.UNINSTALL_KEYWORDS.any { dialogText.contains(it, ignoreCase = true) }
        val hasDestructive = AntiUninstallProtection.DESTRUCTIVE_UNINSTALL_KEYWORDS.any { dialogText.contains(it, ignoreCase = true) }
        val hasConfirm = AntiUninstallProtection.CONFIRM_KEYWORDS.any { dialogText.contains(it, ignoreCase = true) }
        val hasCancel = AntiUninstallProtection.CANCEL_KEYWORDS.any { dialogText.contains(it, ignoreCase = true) }
        val isLongPressMenu = AntiUninstallProtection.SAFE_MENU_KEYWORDS.any { dialogText.contains(it, ignoreCase = true) }

        assertTrue("必须识别到受保护应用名（决定守护）", hasAppName)
        assertTrue("必须识别到卸载关键词", hasUninstall)
        assertTrue("必须识别到强破坏性卸载文案（要卸载/彻底删除）", hasDestructive)
        assertTrue("必须识别到确认操作词（卸载）", hasConfirm)
        assertTrue("必须识别到取消按钮（取消）", hasCancel)
        assertFalse("确认对话框不能被误判为长按小菜单", isLongPressMenu)

        val isConfirmation = hasDestructive || hasConfirm || hasCancel
        assertTrue("必须判定为高危卸载确认弹窗", isConfirmation)
    }

    @Test
    fun oppoDesktopLongPressMenuMustNotBeBlockedAsConfirmationDialog() {
        // 长按应用图标弹出的功能菜单：包含分享、应用信息与卸载
        val menuText = "决定守护 分享 应用信息 卸载"

        val hasAppName = AntiUninstallProtection.PROTECTED_APP_NAMES.any { menuText.contains(it, ignoreCase = true) }
        val hasUninstall = AntiUninstallProtection.UNINSTALL_KEYWORDS.any { menuText.contains(it, ignoreCase = true) }
        val hasDestructive = AntiUninstallProtection.DESTRUCTIVE_UNINSTALL_KEYWORDS.any { menuText.contains(it, ignoreCase = true) }
        val isLongPressMenu = AntiUninstallProtection.SAFE_MENU_KEYWORDS.any { menuText.contains(it, ignoreCase = true) }
        val hasCancel = AntiUninstallProtection.CANCEL_KEYWORDS.any { menuText.contains(it, ignoreCase = true) }

        assertTrue("必须识别到应用名", hasAppName)
        assertTrue("必须识别到卸载选项", hasUninstall)
        assertFalse("长按菜单不包含破坏性文案", hasDestructive)
        assertTrue("必须识别到安全菜单特征词（分享/应用信息）", isLongPressMenu)
        assertFalse("长按菜单不包含取消按钮", hasCancel)
    }

    @Test
    fun oppoPhoneManagerBatchUninstallDialogMustTriggerProtection() {
        // 真机调试截图 1：手机管家/软件商店批量卸载弹出的确认弹窗
        val batchDialogText = "要卸载以下 1 个应用吗？ 将卸载应用并删除所有应用数据 决定守护 32 MB 一键卸载（32 MB） 取消"

        val hasAppName = AntiUninstallProtection.PROTECTED_APP_NAMES.any { batchDialogText.contains(it, ignoreCase = true) }
        val hasUninstall = AntiUninstallProtection.UNINSTALL_KEYWORDS.any { batchDialogText.contains(it, ignoreCase = true) }
        val hasDestructive = AntiUninstallProtection.DESTRUCTIVE_UNINSTALL_KEYWORDS.any { batchDialogText.contains(it, ignoreCase = true) }
        val hasConfirm = AntiUninstallProtection.CONFIRM_KEYWORDS.any { batchDialogText.contains(it, ignoreCase = true) }
        val hasCancel = AntiUninstallProtection.CANCEL_KEYWORDS.any { batchDialogText.contains(it, ignoreCase = true) }

        assertTrue("批量卸载弹窗必须识别出保护对象（决定守护）", hasAppName)
        assertTrue("必须识别出卸载/一键卸载动作", hasUninstall)
        assertTrue("必须识别出破坏性卸载文案（要卸载以下/删除所有应用数据）", hasDestructive)
        assertTrue("必须识别出一键卸载确认按钮", hasConfirm)
        assertTrue("必须识别出取消按钮", hasCancel)
    }

    @Test
    fun recentsInterfaceMustBeAccuratelyIdentified() {
        assertTrue("ColorOS Recents 必须精准识别", AntiUninstallProtection.isRecentsInterface("com.android.launcher", "com.android.quickstep.RecentsActivity"))
        assertTrue("Oplus QuickstepLauncher 必须识别", AntiUninstallProtection.isRecentsInterface("com.oplus.launcher", "com.android.launcher3.uioverrides.QuickstepLauncher"))
        assertTrue("OPPO 专用多任务界面必须识别", AntiUninstallProtection.isRecentsInterface("com.coloros.recents", "com.coloros.recents.RecentsActivity"))
        assertTrue("MIUI 多任务必须识别", AntiUninstallProtection.isRecentsInterface("com.miui.home", "com.miui.home.recents.RecentsActivity"))
        assertTrue("多任务特征文案（清除全部）必须识别", AntiUninstallProtection.isRecentsInterface("com.android.launcher", "com.android.launcher.Launcher", "清除全部 决定守护"))
        assertTrue("Vivo OriginOS 多任务特征资源ID必须识别", AntiUninstallProtection.isRecentsInterface("com.bbk.launcher2", "com.bbk.launcher2.Launcher", viewIds = listOf("com.vivo.recents:id/overview_panel2")))
        assertTrue("Vivo 单 Activity 多任务描述必须识别", AntiUninstallProtection.isRecentsInterface("com.bbk.launcher2", "com.bbk.launcher2.Launcher", "决定守护已锁定-下拉菜单 清除全部-按钮"))

        assertFalse("普通微信界面不能误判为多任务", AntiUninstallProtection.isRecentsInterface("com.tencent.mm", "com.tencent.mm.ui.LauncherUI"))
        assertFalse("桌面普通主页不能误判为多任务", AntiUninstallProtection.isRecentsInterface("com.android.launcher", "com.android.launcher.Workspace"))
    }

    @Test
    fun transitionFromDeciKeeperToRecentsMustBeBlocked() {
        val now = 10000L
        // 场景 1：用户在决定守护内，500ms 前还在决定守护，此时切多任务 -> 必须阻断！
        assertTrue(
            "刚离开决定守护切多任务必须阻断",
            AntiUninstallProtection.shouldBlockTransitionToRecents(
                lastDeciKeeperActiveTimeMs = now - 500L,
                nowMs = now,
                isRecents = true,
                isNavigating = false
            )
        )

        // 场景 2：用户在决定守护内，点击导出配置或跳系统设置（处于主动导航中） -> 绝对不能阻断！
        assertFalse(
            "处于主动外部导航时严禁阻断",
            AntiUninstallProtection.shouldBlockTransitionToRecents(
                lastDeciKeeperActiveTimeMs = now - 500L,
                nowMs = now,
                isRecents = true,
                isNavigating = true
            )
        )

        // 场景 3：用户离开决定守护已经很久（如 10 秒前），在其他应用切多任务 -> 严禁阻断！
        assertFalse(
            "离开决定守护已久切多任务不予阻断",
            AntiUninstallProtection.shouldBlockTransitionToRecents(
                lastDeciKeeperActiveTimeMs = now - 10000L,
                nowMs = now,
                isRecents = true,
                isNavigating = false
            )
        )

        // 场景 4：目标窗口不是多任务（如正常切回桌面） -> 严禁阻断！
        assertFalse(
            "目标窗口非多任务不予阻断",
            AntiUninstallProtection.shouldBlockTransitionToRecents(
                lastDeciKeeperActiveTimeMs = now - 500L,
                nowMs = now,
                isRecents = false,
                isNavigating = false
            )
        )
    }
}
