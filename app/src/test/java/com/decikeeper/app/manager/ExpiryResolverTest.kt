package com.decikeeper.app.manager

import com.decikeeper.app.manager.expiry.ExpiryContext
import com.decikeeper.app.manager.expiry.ExpiryDecision
import com.decikeeper.app.manager.expiry.ExpiryReason
import com.decikeeper.app.manager.expiry.ExpiryResolver
import org.junit.Assert.assertEquals
import org.junit.Test

class ExpiryResolverTest {

    private val pkgWeChat = "com.tencent.mm"
    private val pkgPhone = "com.android.dialer"
    private val pkgLauncher = "com.android.launcher3"
    private val pkgChrome = "com.android.chrome"

    private fun mockIsBlacklist(pkg: String): Boolean {
        return pkg == pkgWeChat
    }

    private fun mockIsLauncher(pkg: String): Boolean {
        return pkg == pkgLauncher
    }

    @Test
    fun testCase1_WeChatForeground_ReturnsLock() {
        val context = ExpiryContext(
            reason = ExpiryReason.FREE_TIME_END,
            foregroundPackage = pkgWeChat,
            recentForegroundPackages = listOf(pkgWeChat),
            accessibilityEnabled = true,
            visibleBlacklistPackages = listOf(pkgWeChat),
            visibleWhitelistPackages = emptyList(),
            isLauncher = false
        )

        val (decision, _) = ExpiryResolver.resolve(context)
        assertEquals(ExpiryDecision.LOCK, decision)
    }

    @Test
    fun testCase2_PhoneForeground_ReturnsUnlock() {
        val context = ExpiryContext(
            reason = ExpiryReason.TEMP_ACCESS_END,
            foregroundPackage = pkgPhone,
            recentForegroundPackages = listOf(pkgPhone),
            accessibilityEnabled = true,
            visibleBlacklistPackages = emptyList(),
            visibleWhitelistPackages = listOf(pkgPhone),
            isLauncher = false
        )

        val (decision, _) = ExpiryResolver.resolve(context)
        assertEquals(ExpiryDecision.UNLOCK, decision)
    }

    @Test
    fun testCase3_LauncherOnExpiry_HistoryFallbackLock() {
        val context = ExpiryContext(
            reason = ExpiryReason.PRIVILEGE_END,
            foregroundPackage = pkgLauncher,
            recentForegroundPackages = listOf(pkgLauncher, pkgLauncher, pkgWeChat, pkgChrome),
            accessibilityEnabled = true,
            visibleBlacklistPackages = emptyList(),
            visibleWhitelistPackages = emptyList(),
            isLauncher = true
        )

        val (decision, _) = ExpiryResolver.resolve(context)
        assertEquals(ExpiryDecision.WAIT_DESKTOP_CONFIRM, decision)

        val (fallbackDecision, _) = ExpiryResolver.resolveHistoryFallback(
            recentForegroundPackages = context.recentForegroundPackages,
            isBlacklist = ::mockIsBlacklist,
            isLauncher = ::mockIsLauncher
        )
        assertEquals(ExpiryDecision.LOCK, fallbackDecision)
    }

    @Test
    fun testCase4_LauncherToPhoneTransition_ReturnsUnlock() {
        val context = ExpiryContext(
            reason = ExpiryReason.FREE_TIME_END,
            foregroundPackage = pkgPhone,
            recentForegroundPackages = listOf(pkgPhone, pkgLauncher),
            accessibilityEnabled = true,
            visibleBlacklistPackages = emptyList(),
            visibleWhitelistPackages = listOf(pkgPhone),
            isLauncher = false
        )

        val (decision, _) = ExpiryResolver.resolve(context)
        assertEquals(ExpiryDecision.UNLOCK, decision)
    }

    @Test
    fun testCase5_LauncherForegroundWithWeChatWindow_ReturnsLock() {
        val context = ExpiryContext(
            reason = ExpiryReason.TEMP_ACCESS_END,
            foregroundPackage = pkgLauncher,
            recentForegroundPackages = listOf(pkgLauncher),
            accessibilityEnabled = true,
            visibleBlacklistPackages = listOf(pkgWeChat),
            visibleWhitelistPackages = emptyList(),
            isLauncher = true
        )

        val (decision, _) = ExpiryResolver.resolve(context)
        assertEquals(ExpiryDecision.LOCK, decision)
    }

    @Test
    fun testCase6_WeChatForegroundWithPhoneWindow_ReturnsLock() {
        // 原则 C: 白名单不能覆盖黑名单，只要黑名单可见就锁
        val context = ExpiryContext(
            reason = ExpiryReason.FREE_TIME_END,
            foregroundPackage = pkgWeChat,
            recentForegroundPackages = listOf(pkgWeChat),
            accessibilityEnabled = true,
            visibleBlacklistPackages = listOf(pkgWeChat),
            visibleWhitelistPackages = listOf(pkgPhone),
            isLauncher = false
        )

        val (decision, _) = ExpiryResolver.resolve(context)
        assertEquals(ExpiryDecision.LOCK, decision)
    }

    @Test
    fun testCase7_NoAccessibility_LauncherForeground_ReturnsWaitDesktopConfirm() {
        val context = ExpiryContext(
            reason = ExpiryReason.FREE_TIME_END,
            foregroundPackage = pkgLauncher,
            recentForegroundPackages = listOf(pkgLauncher),
            accessibilityEnabled = false,
            visibleBlacklistPackages = emptyList(),
            visibleWhitelistPackages = emptyList(),
            isLauncher = true
        )

        val (decision, _) = ExpiryResolver.resolve(context)
        assertEquals(ExpiryDecision.WAIT_DESKTOP_CONFIRM, decision)
    }

    @Test
    fun testHistoryFallback_EmptyHistory_ReturnsUnlock() {
        val (decision, _) = ExpiryResolver.resolveHistoryFallback(
            recentForegroundPackages = emptyList(),
            isBlacklist = ::mockIsBlacklist,
            isLauncher = ::mockIsLauncher
        )
        assertEquals(ExpiryDecision.UNLOCK, decision)
    }

    @Test
    fun testHistoryFallback_AllLauncherHistory_ReturnsUnlock() {
        val (decision, _) = ExpiryResolver.resolveHistoryFallback(
            recentForegroundPackages = listOf(pkgLauncher, pkgLauncher),
            isBlacklist = ::mockIsBlacklist,
            isLauncher = ::mockIsLauncher
        )
        assertEquals(ExpiryDecision.UNLOCK, decision)
    }

    @Test
    fun testHistoryFallback_RecentWhitelistApp_ReturnsUnlock() {
        val (decision, _) = ExpiryResolver.resolveHistoryFallback(
            recentForegroundPackages = listOf(pkgLauncher, pkgChrome, pkgWeChat),
            isBlacklist = ::mockIsBlacklist,
            isLauncher = ::mockIsLauncher
        )
        assertEquals(ExpiryDecision.UNLOCK, decision)
    }
}
