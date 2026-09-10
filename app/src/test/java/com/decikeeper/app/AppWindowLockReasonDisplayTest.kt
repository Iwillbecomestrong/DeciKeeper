package com.decikeeper.app

import com.decikeeper.app.ui.LockWindowManager
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 验证应用时间窗与黑名单锁机原因显示及优先级判定契约：
 * - Case 1: 仅时间窗 (blacklist=false, appWindow=true) -> 触发原因：应用时间窗锁机（抖音）
 * - Case 2: 双锁 (blacklist=true, appWindow=true) -> 时间窗优先 -> 触发原因：应用时间窗锁机（抖音）
 * - Case 3: 仅黑名单 (blacklist=true, appWindow=false) -> 触发原因：黑名单锁机（抖音）
 */
class AppWindowLockReasonDisplayTest {

    @Test
    fun case1_onlyAppWindow_displaysAppWindowLockReason() {
        val result = LockWindowManager.resolveDisplayedLockReason(
            rawReason = "Blocked: com.ss.android.ugc.aweme",
            isAppWindowBlocked = true,
            isAntiUninstall = false,
            isBlacklist = false,
            appLabel = "抖音",
            blockedPackageName = "com.ss.android.ugc.aweme"
        )
        assertEquals("应用时间窗锁机（抖音）", result)
    }

    @Test
    fun case2_doubleLock_appWindowHasPriorityOverBlacklist() {
        val result = LockWindowManager.resolveDisplayedLockReason(
            rawReason = "Blocked: com.ss.android.ugc.aweme",
            isAppWindowBlocked = true,
            isAntiUninstall = false,
            isBlacklist = true,
            appLabel = "抖音",
            blockedPackageName = "com.ss.android.ugc.aweme"
        )
        assertEquals("应用时间窗锁机（抖音）", result)
    }

    @Test
    fun case3_onlyBlacklist_displaysBlacklistLockReason() {
        val result = LockWindowManager.resolveDisplayedLockReason(
            rawReason = "Blocked: com.ss.android.ugc.aweme",
            isAppWindowBlocked = false,
            isAntiUninstall = false,
            isBlacklist = true,
            appLabel = "抖音",
            blockedPackageName = "com.ss.android.ugc.aweme"
        )
        assertEquals("黑名单锁机（抖音）", result)
    }

    @Test
    fun fallbackPkgResolver_worksForAppWindowAndBlacklist() {
        val appWindowWithResolver = LockWindowManager.resolveDisplayedLockReason(
            rawReason = "Blocked: com.ss.android.ugc.aweme",
            isAppWindowBlocked = true,
            isAntiUninstall = false,
            isBlacklist = false,
            appLabel = null,
            blockedPackageName = "com.ss.android.ugc.aweme",
            packageLabelResolver = { if (it == "com.ss.android.ugc.aweme") "抖音" else null }
        )
        assertEquals("应用时间窗锁机（抖音）", appWindowWithResolver)

        val blacklistWithResolver = LockWindowManager.resolveDisplayedLockReason(
            rawReason = "Blocked: com.ss.android.ugc.aweme",
            isAppWindowBlocked = false,
            isAntiUninstall = false,
            isBlacklist = true,
            appLabel = null,
            blockedPackageName = "com.ss.android.ugc.aweme",
            packageLabelResolver = { if (it == "com.ss.android.ugc.aweme") "抖音" else null }
        )
        assertEquals("黑名单锁机（抖音）", blacklistWithResolver)
    }
}
